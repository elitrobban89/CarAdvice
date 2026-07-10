package com.caradvice.scraper;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hämtar bilinsikter från svenska motorsajter, extraherar dem med Groq och sparar
 * i expert_insight. Inkrementell: processade artikel-URL:er och sedda ägaromdömen
 * lagras i web_insight_seen så nattliga körningar aldrig skapar dubbletter.
 */
@Service
public class WebInsightScraperService {

    private static final Logger log = LoggerFactory.getLogger(WebInsightScraperService.class);

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; CarAdviceBot/1.0; +https://caradvice.onrender.com)";
    private static final int FETCH_TIMEOUT_MS = 20_000;
    private static final long FETCH_DELAY_MS = 1_500;   // artighet mot sajterna
    private static final long GROQ_DELAY_MS = 5_000;    // TPM-gräns 8000 tokens/min
    private static final int MAX_ARTICLES_PER_SOURCE = 12;
    private static final int MIN_TEXT_CHARS = 400;
    private static final int MAX_TEXT_CHARS = 7_000;

    private static final String SYSTEM_PROMPT = """
            Du är en assistent som extraherar bilexpertinsikter ur text från svenska motorsajter.

            Analysera texten och extrahera KONKRETA insikter om specifika bilar eller bilkategorier.
            Returnera ett JSON-objekt med fältet "insights" som en array.

            Varje insikt ska ha exakt dessa fält:
            - "car_make": biltillverkare (t.ex. "Volvo", "Toyota") eller "" om generell insikt
            - "car_model": modell (t.ex. "EX30", "RAV4") eller ""
            - "fuel_type": ett av: "elbil", "bensin", "diesel", "hybrid", "laddhybrid" — eller ""
            - "category": ett av: "ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil" — eller ""
            - "insight": 1-3 meningar på svenska med källans konkreta åsikt eller fakta, i tredje person
            - "rating": heltal 1-10 om källan ger betyg, annars ""
            - "source_ref": för ägaromdömen: recensentens namn + datum (t.ex. "Andreas Skoglund 2026-06-12"); för testresultat om en specifik bil: "märke modell" (t.ex. "Volvo V60"); annars ""

            Regler:
            - Inkludera BARA konkreta påståenden om bilar (styrkor, svagheter, mätvärden, testresultat, kända fel)
            - Insikten ska vara användbar för någon som ska KÖPA personbil i Sverige. Returnera INGA insikter alls om:
              * superbilar/hypercars, racing-/motorsportbilar eller lyxbilar långt över vanliga konsumentpriser
              * lastbilar, bussar och yrkesfordon; A-traktorer och mopedbilar
              * prototyper, konceptbilar, entusiastombyggnader, veteran-/samlarbilar
              * specialutgåvor/jubileumsmodeller där innehållet handlar om färger, fälgar och dekor
              * fabriks-, försäljnings- och företagsnyheter (nedläggningar, marknadsandelar, showrooms, mässor, lanseringar)
              * trafikregler, lagändringar, böter, körkorts-, besiktnings- och försäkringsregler
            - "car_make"/"car_model" måste vara bilens verkliga officiella namn — hitta aldrig på
              eller gissa modellnamn; är du osäker: sätt ""
            - En insikt ska handla om en specifik bilmodell eller bilkategori — aldrig om företag,
              marknaden i stort eller branschen
            - "category" ska stämma med bilens faktiska typ:
              * "smaabil" = liten stadsbil (t.ex. Toyota Aygo, Renault Clio) — ALDRIG SUV:ar eller mellanklassbilar
              * "suv" = SUV/crossover oavsett drivlina (t.ex. Volvo XC60, Kia EV5)
              * "familjebil" = mellanstor/stor kombi, sedan eller halvkombi (t.ex. VW Passat, VW ID.7)
              * en sportbil eller lyxbil är ALDRIG "ekonomibil"/"smaabil"/"familjebil"
              * sätt "" om ingen kategori passar
            - Ignorera navigationstext, annonser, medlemserbjudanden och orelaterat innehåll
            - Varje insikt ska vara självbärande och kunna läsas utan artikelkontext
            - Max 5 insikter per artikel, max 10 för sidor med många ägaromdömen
            - Om texten inte innehåller något konkret om bilar: returnera {"insights": []}
            - Svara ENDAST med valid JSON, inget annat
            """;

    /**
     * mode ARTICLES: hämta artikellänkar (via sitemap/rss/listing), extrahera per artikel — dedup på URL.
     * mode PAGE: extrahera insikter direkt från sidan — dedup per ägaromdöme/bilmodell via source_ref.
     */
    record Source(String expert, Mode mode, Discover discover, String url,
                  String base, String linkPattern, String kind, List<String> extraUrls) {}

    enum Mode { ARTICLES, PAGE }
    enum Discover { SITEMAP, RSS, LISTING, WPJSON, NONE }

    private static final List<Source> SOURCES = List.of(
            new Source("Teknikens Värld", Mode.ARTICLES, Discover.SITEMAP,
                    "https://teknikensvarld.se/sitemap.xml", null, null,
                    "artikel/test från motortidningen Teknikens Värld", List.of()),
            new Source("Vi Bilägare", Mode.ARTICLES, Discover.RSS,
                    "https://www.vibilagare.se/rss.xml", null, null,
                    "artikel/test från motortidningen Vi Bilägare", List.of()),
            new Source("M Sverige", Mode.ARTICLES, Discover.LISTING,
                    "https://msverige.se/allt-om-bilen/motor-testar/bilar/",
                    "https://msverige.se", "href=\"(/allt-om-bilen/motor-testar/bilar/[a-z0-9\\-]+/?)\"",
                    "biltest från Riksförbundet M Sverige", List.of()),
            new Source("Bytbil", Mode.ARTICLES, Discover.LISTING,
                    "https://nybil.bytbil.com/posts",
                    "https://nybil.bytbil.com", "href=\"(/posts/[a-z0-9\\-]+)\"",
                    "biltest/nybilsartikel från Bytbil", List.of()),
            new Source("M3", Mode.ARTICLES, Discover.RSS,
                    "https://www.m3.se/feed/", null, null,
                    // M3 är en teknikssajt — icke-bilartiklar ger tom insiktslista och filtreras bort
                    "test/artikel från teknikmagasinet M3",
                    List.of("https://www.m3.se/article/1860897/basta-elbil-test.html")),
            // WordPress REST API (wp-json) — öppen på dessa sajter trots att delar av
            // sidorna är JS-renderade. Icke-bilartiklar (t.ex. F1-nyheter) ger tom
            // insiktslista från prompten och filtreras bort, precis som för M3.
            new Source("Auto Motor & Sport", Mode.ARTICLES, Discover.WPJSON,
                    "https://www.automotorsport.se/wp-json/wp/v2/posts?per_page=15&_fields=link", null, null,
                    "artikel/test från motortidningen Auto Motor & Sport", List.of()),
            new Source("Elbilen", Mode.ARTICLES, Discover.WPJSON,
                    "https://elbilen.se/wp-json/wp/v2/posts?per_page=15&_fields=link", null, null,
                    "artikel/test från elbilsmagasinet Elbilen", List.of()),
            new Source("CarUp", Mode.ARTICLES, Discover.WPJSON,
                    "https://www.carup.se/wp-json/wp/v2/posts?per_page=15&_fields=link", null, null,
                    "artikel/nyhet från bilsajten CarUp", List.of()),
            new Source("Bilägare (car.info)", Mode.PAGE, Discover.NONE,
                    "https://www.car.info/sv-se/user-reviews", null, null,
                    "ägaromdömen från verkliga bilägare på car.info", List.of()),
            new Source("Folksam", Mode.PAGE, Discover.NONE,
                    "https://www.folksam.se/tester-och-goda-rad/vara-tester/hur-saker-ar-bilen", null, null,
                    "Folksams krocksäkerhetsstudie 'Hur säker är bilen' baserad på verkliga olyckor", List.of()));

    @Value("${groq.api.key}")
    private String apiKey;

    // gpt-oss-120b är production-tier och bra på svensk extraktion
    @Value("${groq.insight.model:openai/gpt-oss-120b}")
    private String insightModel;

    private final ExpertInsightRepository insightRepo;
    private final JdbcTemplate jdbc;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public WebInsightScraperService(ExpertInsightRepository insightRepo, JdbcTemplate jdbc) {
        this.insightRepo = insightRepo;
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS web_insight_seen (
                seen_key VARCHAR(500) PRIMARY KEY
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS web_scrape_status (
                job VARCHAR(50) PRIMARY KEY,
                started_at VARCHAR(40),
                finished_at VARCHAR(40),
                new_insights INT,
                detail VARCHAR(2000)
            )
            """);
    }

    boolean isSeen(String key) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM web_insight_seen WHERE seen_key = ?", Integer.class, truncateKey(key));
        return n != null && n > 0;
    }

    void markSeen(String key) {
        try {
            jdbc.update("INSERT INTO web_insight_seen(seen_key) VALUES (?)", truncateKey(key));
        } catch (DuplicateKeyException ignored) {
            // redan markerad — ofarligt
        }
    }

    /** Seedar redan processade nycklar (t.ex. från den lokala Python-körningen). Returnerar antal nya. */
    public int seedSeen(List<String> keys) {
        int added = 0;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            if (!isSeen(key.trim())) {
                markSeen(key.trim());
                added++;
            }
        }
        return added;
    }

    private static String truncateKey(String key) {
        return key.length() > 500 ? key.substring(0, 500) : key;
    }

    /** Kör hela synken. Returnerar antal nya insikter som sparats. */
    public int syncAll() {
        ensureTable();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Web insight sync: GROQ_API_KEY saknas — hoppar över");
            recordStatus(now(), now(), 0, "GROQ_API_KEY saknas — synken hoppades över");
            return 0;
        }
        String startedAt = now();
        recordStatus(startedAt, null, null, null);
        int total = 0;
        List<String> perSource = new ArrayList<>();
        for (Source source : SOURCES) {
            try {
                int n = source.mode() == Mode.PAGE ? processPage(source) : processArticles(source);
                log.info("Web insights [{}]: {} nya insikter", source.expert(), n);
                total += n;
                perSource.add(source.expert() + ": " + n);
            } catch (Exception e) {
                log.warn("Web insights [{}]: källan misslyckades: {}", source.expert(), e.getMessage());
                perSource.add(source.expert() + ": FEL (" + e.getMessage() + ")");
            }
        }
        log.info("Web insight sync klar — {} nya insikter totalt", total);
        recordStatus(startedAt, now(), total, String.join(", ", perSource));
        return total;
    }

    // ── Körstatus (läses av GET /api/admin/scrape-status) ────────────────────

    private static final String JOB_NAME = "web-insights";

    private static String now() {
        return ZonedDateTime.now(ZoneId.of("Europe/Stockholm"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void recordStatus(String startedAt, String finishedAt, Integer newInsights, String detail) {
        jdbc.update("DELETE FROM web_scrape_status WHERE job = ?", JOB_NAME);
        jdbc.update("INSERT INTO web_scrape_status(job, started_at, finished_at, new_insights, detail) VALUES (?,?,?,?,?)",
                JOB_NAME, startedAt, finishedAt, newInsights, detail == null ? null : truncate(detail, 2000));
    }

    /** Senaste körningens status. RUNNING = startad men inte klar (eller avbruten av omstart mitt i). */
    public Map<String, Object> lastRunStatus() {
        ensureTable();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT started_at, finished_at, new_insights, detail FROM web_scrape_status WHERE job = ?", JOB_NAME);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("status", "NEVER_RUN");
            out.put("info", "Ingen synk har körts ännu (schemalagd 04:00 Europe/Stockholm)");
            return out;
        }
        Map<String, Object> row = rows.get(0);
        out.put("status", row.get("finished_at") == null ? "RUNNING" : "OK");
        out.put("startedAt", row.get("started_at"));
        out.put("finishedAt", row.get("finished_at"));
        out.put("newInsights", row.get("new_insights"));
        out.put("perSource", row.get("detail"));
        return out;
    }

    // ── Artikelkällor ─────────────────────────────────────────────────────────

    private int processArticles(Source source) throws Exception {
        List<String> urls = new ArrayList<>(source.extraUrls());
        urls.addAll(discover(source));
        Set<String> unique = new LinkedHashSet<>(urls);

        int saved = 0;
        int processed = 0;
        for (String url : unique) {
            if (processed >= MAX_ARTICLES_PER_SOURCE) break;
            if (isSeen(url)) continue;
            processed++;
            try {
                Thread.sleep(FETCH_DELAY_MS);
                String text = fetchPageText(url);
                if (text.length() < MIN_TEXT_CHARS) {
                    log.debug("Web insights [{}]: för lite text ({} tecken): {}", source.expert(), text.length(), url);
                    markSeen(url);
                    continue;
                }
                List<JsonNode> insights = extractInsights(text, source.kind(), url);
                saved += saveInsights(source.expert(), insights, null);
                markSeen(url);
                Thread.sleep(GROQ_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Web insights [{}]: hoppar över {}: {}", source.expert(), url, e.getMessage());
            }
        }
        return saved;
    }

    // ── Sidkällor (car.info, Folksam) ─────────────────────────────────────────

    private int processPage(Source source) throws Exception {
        String text = fetchPageText(source.url());
        if (text.length() < MIN_TEXT_CHARS) {
            log.warn("Web insights [{}]: för lite text på sidan ({} tecken) — JS-renderad?", source.expert(), text.length());
            return 0;
        }
        List<JsonNode> insights = extractInsights(text, source.kind(), source.url());
        int saved = saveInsights(source.expert(), insights, source.expert());
        Thread.sleep(GROQ_DELAY_MS);
        return saved;
    }

    /** Sparar insikter. dedupExpert != null → deduplicera varje insikt via source_ref-nyckel (sidkällor). */
    private int saveInsights(String expert, List<JsonNode> insights, String dedupExpert) {
        int saved = 0;
        for (JsonNode ins : insights) {
            String insightText = ins.path("insight").asText("");
            if (insightText.isBlank() || isTemplateEcho(ins)) continue;

            if (dedupExpert != null) {
                String ref = ins.path("source_ref").asText("").trim();
                String key = dedupExpert + "|" + (ref.isBlank()
                        ? insightText.substring(0, Math.min(60, insightText.length())) : ref);
                if (isSeen(key)) continue;
                markSeen(key);
            }

            insightRepo.save(new ExpertInsight(
                    expert,
                    blankToNull(ins.path("car_make").asText("")),
                    blankToNull(ins.path("car_model").asText("")),
                    validOrNull(ins.path("fuel_type").asText(""), VALID_FUEL_TYPES),
                    validOrNull(ins.path("category").asText(""), VALID_CATEGORIES),
                    insightText,
                    parseRating(ins.path("rating"))));
            saved++;
        }
        return saved;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // category/fuel_type matchar användarens sökpreferenser i buildExpertContext — ett påhittat
    // värde utanför listan gör ingen skada, men ett FELAKTIGT (Ferrari som "ekonomibil") förgiftar
    // rekommendationsprompten. Whitelist + promptregeln ovan håller fälten ärliga.
    private static final Set<String> VALID_CATEGORIES =
            Set.of("ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil");
    private static final Set<String> VALID_FUEL_TYPES =
            Set.of("elbil", "bensin", "diesel", "hybrid", "laddhybrid");

    /** AI:n ekar ibland fältmallen tillbaka som en rad ("car_make car_model" / "insight") — hittades 6 st i DB. */
    static boolean isTemplateEcho(JsonNode ins) {
        return "insight".equalsIgnoreCase(ins.path("insight").asText("").trim())
                || "car_make".equalsIgnoreCase(ins.path("car_make").asText("").trim());
    }

    static String validOrNull(String s, Set<String> allowed) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return allowed.contains(v) ? v : null;
    }

    private static Integer parseRating(JsonNode node) {
        if (node.canConvertToInt()) {
            int r = node.asInt();
            return (r >= 1 && r <= 10) ? r : null;
        }
        try {
            int r = Integer.parseInt(node.asText("").trim());
            return (r >= 1 && r <= 10) ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Artikelupptäckt ───────────────────────────────────────────────────────

    List<String> discover(Source source) throws Exception {
        return switch (source.discover()) {
            case SITEMAP -> discoverSitemap(source.url());
            case RSS -> discoverRss(source.url());
            case LISTING -> discoverListing(source);
            case WPJSON -> parseWpJsonLinks(fetchRaw(source.url()));
            case NONE -> List.of();
        };
    }

    /** WordPress REST API: [{"link":"https://..."}, ...] — nyaste först. */
    List<String> parseWpJsonLinks(String json) throws Exception {
        List<String> links = new ArrayList<>();
        for (JsonNode post : mapper.readTree(json)) {
            String link = post.path("link").asText("");
            if (!link.isBlank()) links.add(link);
        }
        return links;
    }

    /** WordPress-sitemapindex: ta senaste post-sitemapen och returnera dess nyaste URL:er. */
    private List<String> discoverSitemap(String url) throws Exception {
        String index = fetchRaw(url);
        List<String> children = matchAll(index, "<loc>([^<]+)</loc>");
        List<String> postMaps = children.stream().filter(c -> c.toLowerCase().contains("post")).toList();
        if (postMaps.isEmpty()) postMaps = children;
        if (postMaps.isEmpty()) return List.of();

        String xml = fetchRaw(postMaps.get(postMaps.size() - 1)); // nyaste post-sitemapen ligger sist
        Matcher m = Pattern.compile("<url>\\s*<loc>([^<]+)</loc>(?:\\s*<lastmod>([^<]+)</lastmod>)?").matcher(xml);
        List<String[]> entries = new ArrayList<>();
        while (m.find()) entries.add(new String[]{m.group(1), m.group(2) == null ? "" : m.group(2)});
        entries.sort((a, b) -> b[1].compareTo(a[1]));
        return entries.stream().map(e -> e[0]).toList();
    }

    private List<String> discoverRss(String url) throws Exception {
        String xml = fetchRaw(url);
        List<String> links = matchAll(xml, "(?s)<item>.*?<link>([^<]+)</link>");
        if (links.isEmpty()) links = matchAll(xml, "(?s)<item>.*?<link><!\\[CDATA\\[([^\\]]+)\\]\\]></link>");
        return links;
    }

    private List<String> discoverListing(Source source) throws Exception {
        String html = fetchRaw(source.url());
        String listingPath = source.url().replace(source.base(), "").replaceAll("/$", "");
        List<String> urls = new ArrayList<>();
        for (String path : matchAll(html, source.linkPattern())) {
            if (path.replaceAll("/$", "").equals(listingPath)) continue;
            urls.add(source.base() + path);
        }
        return urls;
    }

    private static List<String> matchAll(String text, String regex) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) result.add(m.group(1));
        return result;
    }

    // ── Hämtning ──────────────────────────────────────────────────────────────

    private String fetchRaw(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "sv-SE,sv;q=0.9")
                .timeout(FETCH_TIMEOUT_MS)
                .ignoreContentType(true)
                .execute().body();
    }

    private String fetchPageText(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "sv-SE,sv;q=0.9")
                .timeout(FETCH_TIMEOUT_MS)
                .get();
        doc.select("script, style, noscript, svg, nav, footer, header").remove();
        return doc.body() == null ? "" : doc.body().text();
    }

    // ── Groq-extraktion ───────────────────────────────────────────────────────

    List<JsonNode> extractInsights(String text, String kind, String label) throws Exception {
        String trimmed = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        Map<String, Object> body = Map.of(
                "model", insightModel,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", "Källa: " + kind + "\n\nText:\n\n" + trimmed)),
                "max_tokens", 1500,
                "temperature", 0.2,
                // gpt-oss är en reasoning-modell — utan low kan hela tokenbudgeten gå åt till reasoning
                "reasoning_effort", "low");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                long wait = 30_000L * (attempt + 1);
                log.info("Web insights: Groq rate limit — väntar {}s", wait / 1000);
                Thread.sleep(wait);
                continue;
            }
            if (resp.statusCode() != 200) {
                log.warn("Web insights: Groq {} för {}: {}", resp.statusCode(), label, truncate(resp.body(), 200));
                return List.of();
            }
            return parseInsightJson(resp.body(), label);
        }
        log.warn("Web insights: rate limit kvarstår efter 3 försök — hoppar över {}", label);
        return List.of();
    }

    List<JsonNode> parseInsightJson(String responseBody, String label) {
        try {
            String content = mapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.startsWith("```")) {
                content = content.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "");
            }
            JsonNode insights = mapper.readTree(content).path("insights");
            if (!insights.isArray()) return List.of();
            List<JsonNode> result = new ArrayList<>();
            insights.forEach(result::add);
            return result;
        } catch (Exception e) {
            log.warn("Web insights: kunde inte parsa JSON-svar för {}: {}", label, e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
