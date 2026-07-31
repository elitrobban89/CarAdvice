package com.caradvice.scraper;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.service.UpcomingInsightService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
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
    private static final long DEFAULT_BACKOFF_MS = 10_000;  // bara när Groq inte säger något själv
    private static final long MIN_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final Pattern RETRY_IN_PATTERN =
            Pattern.compile("try again in (?:(\\d+)m)?([\\d.]+)s", Pattern.CASE_INSENSITIVE);
    // Vakterna körs per källa, men chunkas: gpt-oss-120b är en reasoning-modell och
    // resonemanget växer med batchens storlek. Med 25 insikter och 400 tokens gick HELA
    // budgeten till reasoning (finish_reason=length, tomt content) — mätt 2026-07-31.
    private static final int GUARD_BATCH_SIZE = 10;
    private static final int GUARD_MAX_TOKENS = 1500;
    private static final int MIN_DISCOVERED_LINKS = 5;   // färre än så = källan håller på att sina
    private static final int MIN_TEXT_CHARS = 400;
    private static final int MAX_TEXT_CHARS = 7_000;

    private static final String SYSTEM_PROMPT = """
            Du är en assistent som extraherar bilexpertinsikter ur text från svenska motorsajter.

            Analysera texten och extrahera KONKRETA insikter om specifika bilar eller bilkategorier.
            Returnera ett JSON-objekt med fältet "insights" som en array.

            Varje insikt ska ha exakt dessa fält:
            - "car_make": biltillverkare (t.ex. "Volvo", "Toyota") — obligatoriskt; hoppa över
              insikter som inte handlar om ett specifikt bilmärke
            - "car_model": modell (t.ex. "EX30", "RAV4") — obligatoriskt; en insikt om ett helt
              märke eller om en motor som sitter i flera modeller ("BMW:s N47-diesel") ska hoppas
              över helt, inte sparas med tom modell
            - "fuel_type": ett av: "elbil", "bensin", "diesel", "hybrid", "laddhybrid" — eller ""
            - "category": ett av: "ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil" — eller ""
            - "insight": 1-3 meningar på svenska med källans konkreta åsikt eller fakta, i tredje person
            - "rating": källans betyg omräknat till heltal på skalan 1-10, annars "". Räkna om
              andra skalor proportionerligt: "4 av 5" eller 4 stjärnor → 8, "3 av 5" → 6,
              "7 av 10" → 7, "8,5 av 10" → 9 (avrunda). Betyget måste stå uttryckligen i
              texten (poäng, stjärnor eller "betyg X") — härled ALDRIG ett betyg ur tonläget
            - "source_ref": för ägaromdömen: recensentens namn + datum (t.ex. "Andreas Skoglund 2026-06-12"); för testresultat om en specifik bil: "märke modell" (t.ex. "Volvo V60"); annars ""

            Regler:
            - Inkludera BARA konkreta påståenden om bilar (styrkor, svagheter, mätvärden, testresultat, kända fel)
            - Insikten ska vara användbar för någon som ska KÖPA personbil i Sverige. Returnera INGA insikter alls om:
              * superbilar/hypercars, racing-/motorsportbilar eller lyxbilar långt över vanliga konsumentpriser
              * lastbilar, bussar och yrkesfordon; A-traktorer och mopedbilar
              * prototyper, konceptbilar, entusiastombyggnader, veteran-/samlarbilar
              * specialutgåvor/jubileumsmodeller där innehållet handlar om färger, fälgar och dekor
              * fabriks-, försäljnings- och företagsnyheter (nedläggningar, marknadsandelar, showrooms, mässor, lanseringar)
                — MEN utmärkelser till en specifik modell (Årets Bil/Car of the Year, "bäst i test",
                topplacering i försäljningsstatistik) är RELEVANTA och ska inkluderas
              * trafikregler, lagändringar, böter, körkorts-, besiktnings- och försäkringsregler
            - "car_make"/"car_model" måste vara bilens verkliga officiella namn — hitta aldrig på
              eller gissa modellnamn; är du osäker: sätt ""
            - "car_make" ska vara märkets vanliga kortform utan undermärken och tillägg
              (skriv "Mercedes", aldrig "Mercedes-Benz" eller "Mercedes-AMG"; "VW" skrivs "Volkswagen")
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

    // Parafraser fångas inte av textjämförelse — två artiklar om samma bil ger "32,2 kWh
    // Blade-batteri" och "Blade-batteriet på 32,2 kWh" som skilda rader (BYD Shark fick
    // två hela uppsättningar). Kandidater vars bil redan har insikter i DB får därför
    // ett extra Groq-anrop som pekar ut faktaupprepningar.
    private static final String DEDUP_PROMPT = """
            Du jämför nya bilinsikter mot insikter som redan finns i en databas.

            En ny kandidat är DUBBLETT om dess huvudsakliga fakta eller påstående om samma bil
            redan täcks av en befintlig insikt — även när formuleringen är helt annorlunda
            (t.ex. samma dragvikt, batteristorlek, räckvidd, testresultat eller omdöme).
            En kandidat är också dubblett om den upprepar fakta från en kandidat med LÄGRE index
            (den första behålls, den senare markeras).
            En kandidat som tillför ny eller kompletterande information är INTE en dubblett.

            Svara ENDAST med valid JSON: {"duplicates": [indexen för de kandidater som är dubbletter]}
            Om ingen är dubblett: {"duplicates": []}
            """;

    // Exkluderingslistan i SYSTEM_PROMPT läcker under extraktionsarbetet (Nissan Tekton
    // "säljs ej i Sverige", Lamborghini Temerario, Porsche-auktioner) — samma lärdom som
    // med parafraserna: ett separat, smalt Groq-anrop dömer säkrare än regler inbakade
    // i den stora prompten.
    // Sverigekravet var först mjukt, med ett undantag för "snart lanserad modell med
    // bekräftat namn och konkreta uppgifter" — det undantaget blev hålet: en artikel om
    // en ännu ej säljstartad bil innehåller nästan alltid effekt, vikt eller räckvidd,
    // så vakten läste specdumpar som köpvägledning (Zeekr 9X Ultra, nästa MX-5, båda
    // 2026-07-30; Elbilens nya posttyper 2026-07-29 drar in mycket sådant). Kriteriet är
    // därför "går att köpa i Sverige idag", inte "hur konkreta uppgifterna är".
    // Sedan 2026-07-31 kastas de kommande modellerna inte längre: de sparas med
    // upcoming-flagga och hålls utanför prompter och bilkort tills bilen släpps (natten
    // till 07-31 slängdes 13 rader om nya el-GLA:n — innehåll som blir användbart om
    // några månader). Skillnaden mot IRRELEVANT är bekräftad modell + konkret innehåll.
    static final String RELEVANCE_PROMPT = """
            Du granskar bilinsikter innan de sparas i en databas vars enda syfte är att
            hjälpa svenska privatpersoner att välja och köpa personbil.

            AVGÖRANDE: anges ett svenskt pris i kronor för modellen är den RELEVANT — då
            säljs den här. Blockera aldrig en prissatt bil med motiveringen att den är ny
            eller okänd. (DS N°8 med startpris 849 900 kr stoppades felaktigt 2026-07-31.)

            En insikt är IRRELEVANT om den handlar om:
            - en bil som inte går att köpa i Sverige IDAG och inte heller är på väg hit.
              Kravet är hårt: konkreta uppgifter (effekt, vikt, räckvidd, mått, testvärden,
              pris i kronor) gör INTE en bil relevant om läsaren inte kan köpa den. Hit hör
              modeller som bara säljs på andra marknader (t.ex. Lada eller varianter som
              säljs i Kina/USA), modeller som lämnat den svenska marknaden, samt rykten om
              kommande namn ("kan heta X"), plattform, teknik eller lanseringsår
            - kuriosa, rekordförsök och bragder (längsta sträcka på en tank, extremt låg
              förbrukning med specialdäck/körstil), eller retrospektiva jämförelsetester av
              utgångna prestandabilar — underhållande, men ingen köpvägledning
            - superbilar/hypercars, racingbilar eller lyxbilar långt över vanliga
              konsumentpriser. Riktmärke: nybilspris över ca 1,5 miljoner kronor, ELLER en
              prestandaversion vars enda innehåll är effekt och acceleration. Hit hör även
              stora V8-SUV:ar och AMG/M/RS-toppversioner (Mercedes-AMG GLE 63 S med 585 hk
              släpptes felaktigt igenom 2026-07-31). En vanlig familjebil eller elbil under
              den nivån är däremot RELEVANT hur snabb den än är
            - lastbilar, bussar, yrkesfordon, A-traktorer eller mopedbilar
            - prototyper, konceptbilar, entusiastombyggnader eller veteran-/samlarbilar
            - specialutgåvor, jubileums- och signatureditioner vars innehåll är utseende,
              namn eller upplaga i stället för egenskaper — färger, fälgar, dekor, emblem,
              "firar 25 år", "begränsad upplaga om 500 exemplar" (Mini Cooper Oxford Edition
              släpptes felaktigt igenom 2026-07-31). Handlar texten om utrustning, räckvidd
              eller pris för utgåvan är den däremot RELEVANT
            - ren design- och stämningsprosa utan något kontrollerbart: "skalade ytor", "rund
              central pekskärm", "gokart-känsla", "återger arvet". En insikt måste innehålla
              minst en sak en köpare kan kontrollera eller jämföra — en siffra, ett
              testresultat, ett känt fel, en utrustningsdetalj eller ett pris
            - auktioner, fabriks-, försäljnings- eller företagsnyheter, produktions- och
              lagersiffror, marknadsstatistik
            - trafikregler, lagändringar, böter, skatter eller försäkringsregler
            - vilken bil en känd person (idrottare, artist, politiker) kör, äger eller setts i
            En insikt är RELEVANT om den kan hjälpa en svensk bilköpare att välja eller
            värdera en personbil (styrkor, svagheter, mätvärden, testresultat, kända fel).
            Utmärkelser till en specifik modell är också RELEVANTA (Årets Bil/Car of the Year,
            "bäst i test", mest sålda bilen i sin klass) — de är köpsignaler, inte företagsnyheter.

            En insikt är KOMMANDE (varken irrelevant eller direkt användbar) om den handlar om
            en namngiven, bekräftad modell eller generation som ska säljas i Sverige men ännu
            inte går att köpa här: presenterad men inte prissatt, annonserad säljstart längre
            fram, eller ny generation av en modell som redan säljs här. Insikten måste ha
            konkret innehåll (mått, effekt, räckvidd, utrustning, testintryck) — lösa rykten
            om namn eller lanseringsår är IRRELEVANTA, inte kommande.

            Svara ENDAST med valid JSON:
            {"irrelevant": [index...], "upcoming": [index...]}
            Ett index får bara stå i en av listorna. Om alla är direkt användbara:
            {"irrelevant": [], "upcoming": []}
            """;

    /**
     * Källor som återkommande levererar mest skräp får en andra, smalare vakt ovanpå
     * RELEVANCE_PROMPT. CarUp publicerar mycket översatt amerikanskt innehåll
     * (mekaniker-listicles, EPA-siffror för bilar som inte säljs här) — tre auditer i rad
     * (2026-07-09, 07-25, 07-26) har visat att den generella vakten släpper igenom det
     * trots att reglerna finns, medan bra rader (VW Arteon begagnat) kommer från samma
     * källa. Att lyfta ut CarUp hade alltså kostat mer än det smakat.
     */
    static final Set<String> STRICT_SOURCES = Set.of("CarUp");

    private static final String STRICT_RELEVANCE_PROMPT = """
            Du gör en sista, hård granskning av bilinsikter från en källa som ofta
            publicerar översatt amerikanskt innehåll. Databasen används bara av svenska
            privatpersoner som ska köpa personbil i Sverige.

            Markera en insikt som IRRELEVANT om något av detta gäller:
            - bilen går inte att köpa i Sverige idag, varken ny hos handlare eller begagnad
              på den svenska marknaden (typiska exempel: modeller som bara sålts i USA)
            - insikten bygger på utländska mätvärden eller testcykler (EPA, miles, mpg,
              amerikanska priser i dollar) för en bil som ännu inte säljs här
            - insikten är ett svepande omdöme utan konkret underlag ("en mekaniker tycker
              att den är dyr att reparera", "den är opålitlig") utan att ange vilket fel,
              vilken motor, vilken årsmodell eller vilken kostnad det handlar om
            - innehållet är hämtat ur en topplista/video av typen "bilar mekaniker aldrig
              skulle köpa" utan svensk förankring

            Behåll insikten om den beskriver ett konkret, kontrollerbart förhållande om en
            bil som en svensk köpare faktiskt kan hitta i marknaden — kända fel med angiven
            motor/årsmodell, mätvärden, testresultat, utrustning eller prisläge på begagnat.

            Svara ENDAST med valid JSON: {"irrelevant": [indexen för de irrelevanta insikterna]}
            Om alla ska behållas: {"irrelevant": []}
            """;

    /**
     * mode ARTICLES: hämta artikellänkar (via sitemap/rss/listing), extrahera per artikel — dedup på URL.
     * mode PAGE: extrahera insikter direkt från sidan — dedup per ägaromdöme/bilmodell via source_ref.
     */
    record Source(String expert, Mode mode, Discover discover, String url,
                  String base, String linkPattern, String kind, List<String> extraUrls) {}

    enum Mode { ARTICLES, PAGE }
    enum Discover { SITEMAP, RSS, LISTING, WPJSON, NONE }

    /**
     * Utfall för en källa. En källa som inte hittade något att skrapa (0 länkar, tom sida)
     * gav förut samma "0" i scrape-status som en källa där allt fungerade men dedupen tog
     * allt — en layoutändring hos källan kunde alltså gå obemärkt förbi hur länge som helst.
     * warning != null lyfts därför fram i statusraden i stället för antalet.
     */
    record SourceResult(int saved, String warning) {
        static SourceResult of(int saved) { return new SourceResult(saved, null); }

        String label() {
            if (warning == null) return String.valueOf(saved);
            // en varnande källa kan ändå ha levererat — dölj inte siffran bakom varningen
            return saved > 0 ? saved + " (" + warning + ")" : warning;
        }
    }

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
            // Elbilen publicerar inte i standardtypen "posts" (den innehåller 3 poster totalt)
            // utan i egna posttyper. tester + artiklar är de redaktionella; "nyheter" (7 000+)
            // är kort notisflöde och ger sällan konkreta insikter — därför medvetet utelämnad.
            new Source("Elbilen", Mode.ARTICLES, Discover.WPJSON,
                    "https://elbilen.se/wp-json/wp/v2/tester?per_page=10&_fields=link,"
                            + "https://elbilen.se/wp-json/wp/v2/artiklar?per_page=10&_fields=link", null, null,
                    "artikel/test från elbilsmagasinet Elbilen", List.of()),
            new Source("CarUp", Mode.ARTICLES, Discover.WPJSON,
                    "https://www.carup.se/wp-json/wp/v2/posts?per_page=15&_fields=link", null, null,
                    "artikel/nyhet från bilsajten CarUp", List.of()),
            // car.info är borttagen: /sv-se/user-reviews serverar bara ett filterskal —
            // omdömestexterna hämtas av JS efteråt, så en ren HTTP-hämtning ser inga
            // omdömen och inga länkar till enskilda omdömen. Källan sparade aldrig en
            // enda insikt. Kräver headless browser för att återinföras.
            new Source("Folksam", Mode.PAGE, Discover.NONE,
                    "https://www.folksam.se/tester-och-goda-rad/vara-tester/hur-saker-ar-bilen", null, null,
                    "Folksams krocksäkerhetsstudie 'Hur säker är bilen' baserad på verkliga olyckor", List.of()));

    /** Slår upp en konfigurerad källa på namn. null om källan inte finns. Används av testerna. */
    static Source sourceByName(String expert) {
        return SOURCES.stream().filter(s -> s.expert().equals(expert)).findFirst().orElse(null);
    }

    @Value("${groq.api.key}")
    private String apiKey;

    // gpt-oss-120b är production-tier och bra på svensk extraktion
    @Value("${groq.insight.model:openai/gpt-oss-120b}")
    private String insightModel;

    // Vakterna (relevans, extravakt, parafras-dedup) körs som eget anrop och kan peka på en
    // annan modell än extraktionen — Groq har egen TPM-pott per modell.
    //
    // PRÖVAT OCH FÖRKASTAT: gpt-oss-20b som vakt (testkörning i prod 2026-07-31 20:18).
    // Den separata potten fungerade — vaktanropen slutade helt synas bland 429:orna — men
    // 20b dömde för brusigt åt BÅDA hållen i samma körning: släppte igenom Mini Oxford
    // Edition (dekorutgåva), europeisk försäljningsstatistik, Teslas produktionsmilstolpe
    // och en AMG GLE 63 S, samtidigt som den stoppade alla fem DS N°8-raderna trots att
    // artikeln angav svenskt pris (849 900 kr). Vakterna ÄR kvalitetsspärren mot skräp i
    // insiktsdatabasen, så de ligger kvar på den stora modellen. Tidsvinsten satt ändå i
    // retry-after-backoffen och i att vakterna körs per källa, inte i modellvalet.
    @Value("${groq.guard.model:openai/gpt-oss-120b}")
    private String guardModel;

    private final ExpertInsightRepository insightRepo;
    private final JdbcTemplate jdbc;
    private final JobStatusService jobStatus;
    private final UpcomingInsightService upcomingService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public WebInsightScraperService(ExpertInsightRepository insightRepo, JdbcTemplate jdbc,
                                    JobStatusService jobStatus, UpcomingInsightService upcomingService) {
        this.insightRepo = insightRepo;
        this.jdbc = jdbc;
        this.jobStatus = jobStatus;
        this.upcomingService = upcomingService;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS web_insight_seen (
                seen_key VARCHAR(500) PRIMARY KEY
            )
            """);
        jobStatus.ensureTable();
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
            jobStatus.markStarted(JOB_NAME);
            jobStatus.markFinished(JOB_NAME, 0, "GROQ_API_KEY saknas — synken hoppades över");
            return 0;
        }
        jobStatus.markStarted(JOB_NAME);
        int total = 0;
        List<String> perSource = new ArrayList<>();
        for (Source source : SOURCES) {
            try {
                SourceResult r = source.mode() == Mode.PAGE ? processPage(source) : processArticles(source);
                log.info("Web insights [{}]: {} nya insikter", source.expert(), r.saved());
                if (r.warning() != null) {
                    log.error("SCRAPER ALERT [{}]: {} — källan kan ha ändrat sin HTML-struktur. Manuell koll behövs.",
                            source.expert(), r.warning());
                }
                total += r.saved();
                perSource.add(source.expert() + ": " + r.label());
            } catch (Exception e) {
                log.warn("Web insights [{}]: källan misslyckades: {}", source.expert(), e.getMessage());
                perSource.add(source.expert() + ": FEL (" + e.getMessage() + ")");
            }
        }
        log.info("Web insight sync klar — {} nya insikter totalt", total);
        jobStatus.markFinished(JOB_NAME, total, String.join(", ", perSource));
        return total;
    }

    // ── Körstatus (läses av GET /api/admin/scrape-status) ────────────────────

    private static final String JOB_NAME = JobStatusService.JOB_WEB_INSIGHTS;

    /** Senaste körningens status. RUNNING = startad men inte klar (eller avbruten av omstart mitt i). */
    public Map<String, Object> lastRunStatus() {
        return jobStatus.lastRun(JOB_NAME);
    }

    // ── Artikelkällor ─────────────────────────────────────────────────────────

    private SourceResult processArticles(Source source) throws Exception {
        List<String> urls = new ArrayList<>(source.extraUrls());
        urls.addAll(discover(source));
        Set<String> unique = new LinkedHashSet<>(urls);
        if (unique.isEmpty()) return new SourceResult(0, "INGA LANKAR (0 artikel-URL:er)");

        // Elbilen svalt i det tysta: endpointen svarade 200 men innehöll bara 3 artiklar, så
        // dedupen tog allt varje natt och statusraden visade ett oskyldigt "0". Ett magert men
        // icke-tomt utbud är därför också värt en varning — det är så en källa dör numera.
        String warning = unique.size() < MIN_DISCOVERED_LINKS
                ? "MAGERT UTBUD (" + unique.size() + " artikel-URL:er)" : null;

        // Insikterna samlas för HELA källan och filtreras sedan i ett svep. Vakterna och
        // parafras-dedupen kostade förut ett Groq-anrop per artikel var (upp till tre extra
        // anrop × 12 artiklar per källa); nu blir det ett par anrop per källa i stället.
        int processed = 0;
        List<JsonNode> collected = new ArrayList<>();
        List<String> extracted = new ArrayList<>();
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
                collected.addAll(extractInsights(text, source.kind(), url));
                extracted.add(url);
                Thread.sleep(GROQ_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Web insights [{}]: hoppar över {}: {}", source.expert(), url, e.getMessage());
            }
        }
        int saved = saveInsights(source.expert(), collected, null);
        // Först efter sparandet — annars tappas artiklar tyst om körningen dör i filtreringen
        extracted.forEach(this::markSeen);
        return new SourceResult(saved, warning);
    }

    // ── Sidkällor (car.info, Folksam) ─────────────────────────────────────────

    private SourceResult processPage(Source source) throws Exception {
        String text = fetchPageText(source.url());
        if (text.length() < MIN_TEXT_CHARS) {
            log.warn("Web insights [{}]: för lite text på sidan ({} tecken) — JS-renderad?", source.expert(), text.length());
            return new SourceResult(0, "FOR LITE TEXT (" + text.length() + " tecken, JS-renderad?)");
        }
        List<JsonNode> insights = extractInsights(text, source.kind(), source.url());
        int saved = saveInsights(source.expert(), insights, source.expert());
        Thread.sleep(GROQ_DELAY_MS);
        return SourceResult.of(saved);
    }

    /** Sparar insikter. dedupExpert != null → deduplicera varje insikt via source_ref-nyckel (sidkällor). */
    int saveInsights(String expert, List<JsonNode> insights, String dedupExpert) {
        int saved = 0;
        for (JsonNode ins : filterKnownDuplicates(filterStrict(expert, filterIrrelevant(insights)))) {
            String insightText = ins.path("insight").asText("");
            if (insightText.isBlank() || isTemplateEcho(ins)) continue;

            // Insikter utan märke visas aldrig (ExpertInsightService utesluter carMake == null
            // överallt) — spara dem inte.
            if (ins.path("car_make").asText("").isBlank()) {
                log.info("Web insights: hoppar över insikt utan bilmärke: {}", truncate(insightText, 80));
                continue;
            }

            // Utan modell hamnar insikten i findForCarTitle:s makeOnly-hink och visas på VARJE
            // bil av märket — en N47-dieselvarning dyker upp på ett BMW i4-kort. Kuraterade
            // CSV-rader får fortsatt vara märkesbreda; skrapade får inte.
            if (ins.path("car_model").asText("").isBlank()) {
                log.info("Web insights: hoppar över märkesbred insikt utan modell: {}", truncate(insightText, 80));
                continue;
            }

            if (dedupExpert != null) {
                String ref = ins.path("source_ref").asText("").trim();
                String key = dedupExpert + "|" + (ref.isBlank()
                        ? insightText.substring(0, Math.min(60, insightText.length())) : ref);
                if (isSeen(key)) continue;
                markSeen(key);
            }

            ExpertInsight stored = insightRepo.save(new ExpertInsight(
                    expert,
                    blankToNull(ins.path("car_make").asText("")),
                    blankToNull(ins.path("car_model").asText("")),
                    validOrNull(ins.path("fuel_type").asText(""), VALID_FUEL_TYPES),
                    validOrNull(ins.path("category").asText(""), VALID_CATEGORIES),
                    insightText,
                    parseRating(ins.path("rating"))));
            if (ins.path(UPCOMING_FIELD).asBoolean(false)) {
                upcomingService.mark(stored.getId());
            }
            saved++;
        }
        return saved;
    }

    // ── Relevansvakt ──────────────────────────────────────────────────────────

    /**
     * Slänger insikter utan köparrelevans (ej-Sverige-bilar, hypercars, auktioner m.m.)
     * via ett separat Groq-anrop. Körs före dubblettfiltret så skräp aldrig kostar
     * dedup-anrop. Detta är den enda spärren mot att irrelevant/hallucinerat innehåll
     * hamnar i den "verifierade" insiktsdatabasen — därför fail-closed vid fel: hoppar
     * över hela batchen den här körningen hellre än att spara den ofiltrerad.
     */
    List<JsonNode> filterIrrelevant(List<JsonNode> insights) {
        return runGuard(RELEVANCE_PROMPT, insights, "relevansvakten", true);
    }

    /**
     * Extra vakt för {@link #STRICT_SOURCES}. Körs efter den generella vakten (bara på det
     * som överlevt den — färre tokens) och före dubblettfiltret. Samma fail-closed-regel:
     * hellre en tappad insikt än en oskärskådad från en källa som bevisligen läcker.
     */
    List<JsonNode> filterStrict(String expert, List<JsonNode> insights) {
        if (!STRICT_SOURCES.contains(expert)) return insights;
        return runGuard(STRICT_RELEVANCE_PROMPT, insights, "extravakten [" + expert + "]", false);
    }

    /**
     * Vakterna körs per källa (inte per artikel) sedan 2026-07-31 — men en hel källas
     * insikter i en prompt kan bli lång, så batchen chunkas. Varje chunk är sitt eget
     * fail-closed-fönster: ett Groq-fel tappar den chunken, inte hela källan.
     */
    private List<JsonNode> runGuard(String prompt, List<JsonNode> insights, String label, boolean flagUpcoming) {
        if (insights.isEmpty() || apiKey == null || apiKey.isBlank()) return insights;
        List<JsonNode> kept = new ArrayList<>();
        for (int start = 0; start < insights.size(); start += GUARD_BATCH_SIZE) {
            List<JsonNode> chunk = insights.subList(start, Math.min(insights.size(), start + GUARD_BATCH_SIZE));
            kept.addAll(runGuardChunk(prompt, chunk, label, flagUpcoming));
        }
        return kept;
    }

    private List<JsonNode> runGuardChunk(String prompt, List<JsonNode> insights, String label, boolean flagUpcoming) {
        Set<Integer> irrelevant;
        Set<Integer> upcoming;
        try {
            String body = postGroq(guardModel, prompt, buildRelevanceUserContent(insights), GUARD_MAX_TOKENS, label);
            if (body == null) {
                log.warn("Web insights: {} fick inget svar — hoppar över batchen ({} insikter) den här körningen", label, insights.size());
                return List.of();
            }
            // Ett tomt eller oparsbart svar är INTE "inget var irrelevant". Reasoning-modellen
            // kan bränna hela tokenbudgeten och svara 200 med tomt content — tolkas det som en
            // tom irrelevant-lista blir vakten en tyst nolla och batchen sparas ofiltrerad.
            irrelevant = parseIndexesOrNull(body, "irrelevant");
            if (irrelevant == null) {
                log.warn("Web insights: {} svarade utan användbar lista — hoppar över batchen ({} insikter) den här körningen",
                        label, insights.size());
                return List.of();
            }
            Set<Integer> flagged = flagUpcoming ? parseIndexesOrNull(body, "upcoming") : null;
            upcoming = flagged == null ? Set.of() : flagged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Web insights: {} misslyckades — hoppar över batchen ({} insikter) den här körningen: {}",
                    label, insights.size(), e.getMessage());
            return List.of();
        }
        List<JsonNode> kept = new ArrayList<>();
        for (int i = 0; i < insights.size(); i++) {
            JsonNode ins = insights.get(i);
            if (irrelevant.contains(i)) {
                log.info("Web insights: {} stoppar {} {}: {}", label,
                        ins.path("car_make").asText(), ins.path("car_model").asText(),
                        truncate(ins.path("insight").asText(""), 80));
                continue;
            }
            if (upcoming.contains(i)) {
                markUpcoming(ins);
                log.info("Web insights: {} markerar {} {} som kommande modell: {}", label,
                        ins.path("car_make").asText(), ins.path("car_model").asText(),
                        truncate(ins.path("insight").asText(""), 80));
            }
            kept.add(ins);
        }
        return kept;
    }

    /** Fältet läses av saveInsights och följer med insikten genom dedupen. */
    private static void markUpcoming(JsonNode ins) {
        if (ins instanceof ObjectNode node) node.put(UPCOMING_FIELD, true);
    }

    static final String UPCOMING_FIELD = "_upcoming";

    static String buildRelevanceUserContent(List<JsonNode> insights) {
        StringBuilder sb = new StringBuilder("INSIKTER:\n");
        for (int i = 0; i < insights.size(); i++) {
            JsonNode c = insights.get(i);
            sb.append(i).append(" (").append(c.path("car_make").asText("").trim()).append(" ")
                    .append(c.path("car_model").asText("").trim()).append("): ")
                    .append(c.path("insight").asText("")).append("\n");
        }
        return sb.toString();
    }

    // ── Dubblettfiltrering mot befintliga insikter ────────────────────────────

    /**
     * Släpper bara igenom insikter som inte redan finns i DB för samma bil:
     * först normaliserad textjämförelse (gratis), sedan ett Groq-anrop för
     * parafraser. Insikter utan märke+modell och alla fel-lägen släpps igenom
     * ofiltrerade — hellre en dubblett än en tappad insikt.
     */
    List<JsonNode> filterKnownDuplicates(List<JsonNode> insights) {
        List<JsonNode> kept = new ArrayList<>();
        List<JsonNode> candidates = new ArrayList<>();
        Map<String, List<String>> existingByCar = new LinkedHashMap<>();
        for (JsonNode ins : insights) {
            String text = ins.path("insight").asText("");
            String make = ins.path("car_make").asText("").trim();
            String model = ins.path("car_model").asText("").trim();
            if (text.isBlank() || make.isBlank() || model.isBlank()) {
                kept.add(ins);
                continue;
            }
            List<String> existing = existingByCar.computeIfAbsent(make + " " + model,
                    k -> existingTexts(make, model));
            String norm = normalizeForCompare(text);
            if (existing.stream().anyMatch(e -> normalizeForCompare(e).equals(norm))) {
                log.info("Web insights: hoppar över exakt dubblett för {} {}", make, model);
                continue;
            }
            if (existing.isEmpty()) {
                // först i batchen för en ny bil — sparas, och senare rader i samma
                // batch jämförs mot den (fångar AI:ns egna upprepningar i en artikel)
                kept.add(ins);
                existing.add(text);
                continue;
            }
            candidates.add(ins);
        }
        if (!candidates.isEmpty()) {
            Set<Integer> dups = paraphraseDuplicates(candidates, existingByCar);
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode c = candidates.get(i);
                if (dups.contains(i)) {
                    log.info("Web insights: hoppar över parafras-dubblett för {} {}: {}",
                            c.path("car_make").asText(), c.path("car_model").asText(),
                            truncate(c.path("insight").asText(""), 80));
                } else {
                    kept.add(c);
                }
            }
        }
        return kept;
    }

    /**
     * Befintliga insiktstexter för samma bil. Märket matchas med prefix åt båda hållen
     * och modellen på token-delmängd — AI:n stavar samma bil olika mellan artiklar
     * ("Mercedes-Benz CLA 45 4MATIC+" / "Mercedes AMG CLA 45 4Matic+"), och exakt
     * matchning släppte igenom hela dubblettuppsättningar.
     */
    private List<String> existingTexts(String make, String model) {
        return insightRepo.findByMakePrefix(make, PageRequest.of(0, 200)).stream()
                .filter(e -> sameCar(model, e.getCarModel()))
                .limit(15)
                .map(ExpertInsight::getInsight)
                .filter(t -> t != null && !t.isBlank())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Samma bil om den ena modellens tokens är en delmängd av den andras ("EV4" ⊆ "EV4 AWD"). */
    static boolean sameCar(String a, String b) {
        Set<String> ta = modelTokens(a);
        Set<String> tb = modelTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        return ta.containsAll(tb) || tb.containsAll(ta);
    }

    private static Set<String> modelTokens(String s) {
        if (s == null) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String t : s.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!t.isBlank()) tokens.add(t);
        }
        return tokens;
    }

    /** Gemener + all interpunktion/whitespace bort — fångar omimporter och triviala varianter. */
    static String normalizeForCompare(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private Set<Integer> paraphraseDuplicates(List<JsonNode> candidates, Map<String, List<String>> existingByCar) {
        if (apiKey == null || apiKey.isBlank()) return Set.of();
        try {
            String body = postGroq(guardModel, DEDUP_PROMPT,
                    buildDedupUserContent(candidates, existingByCar), 500, "parafras-dedup");
            return body == null ? Set.of() : parseDuplicateIndexes(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (Exception e) {
            log.warn("Web insights: parafras-dedup misslyckades — sparar utan filtrering: {}", e.getMessage());
            return Set.of();
        }
    }

    static String buildDedupUserContent(List<JsonNode> candidates, Map<String, List<String>> existingByCar) {
        Set<String> cars = new LinkedHashSet<>();
        for (JsonNode c : candidates) {
            cars.add(c.path("car_make").asText("").trim() + " " + c.path("car_model").asText("").trim());
        }
        StringBuilder sb = new StringBuilder("BEFINTLIGA INSIKTER:\n");
        for (String car : cars) {
            sb.append("\n").append(car).append(":\n");
            for (String t : existingByCar.getOrDefault(car, List.of())) {
                sb.append("- ").append(t).append("\n");
            }
        }
        sb.append("\nNYA KANDIDATER:\n");
        for (int i = 0; i < candidates.size(); i++) {
            JsonNode c = candidates.get(i);
            sb.append(i).append(" (").append(c.path("car_make").asText("").trim()).append(" ")
                    .append(c.path("car_model").asText("").trim()).append("): ")
                    .append(c.path("insight").asText("")).append("\n");
        }
        return sb.toString();
    }

    Set<Integer> parseDuplicateIndexes(String responseBody) {
        return parseIndexes(responseBody, "duplicates");
    }

    /** Fail open — tom mängd betyder "filtrera inget". Används av dedupen. */
    Set<Integer> parseIndexes(String responseBody, String field) {
        Set<Integer> parsed = parseIndexesOrNull(responseBody, field);
        return parsed == null ? Set.of() : parsed;
    }

    /**
     * Som {@link #parseIndexes} men skiljer "modellen svarade tom lista" från "inget
     * användbart svar" — null betyder det senare. Vakterna måste kunna se skillnaden:
     * en reasoning-modell som bränt tokenbudgeten svarar 200 med tomt content, och den
     * tystnaden får inte läsas som ett godkännande av hela batchen.
     */
    Set<Integer> parseIndexesOrNull(String responseBody, String field) {
        try {
            String content = contentOf(responseBody);
            if (content.isBlank()) {
                log.warn("Web insights: tomt {}-svar (tokenbudgeten kan ha gått till reasoning)", field);
                return null;
            }
            JsonNode arr = mapper.readTree(content).path(field);
            if (!arr.isArray()) return null;
            Set<Integer> out = new HashSet<>();
            arr.forEach(n -> { if (n.canConvertToInt()) out.add(n.asInt()); });
            return out;
        } catch (Exception e) {
            log.warn("Web insights: kunde inte parsa {}-svar: {}", field, e.getMessage());
            return null;
        }
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
            case WPJSON -> discoverWpJson(source);
            case NONE -> List.of();
        };
    }

    /**
     * WPJSON-källans url kan innehålla flera endpoints separerade med komma — sajter som
     * Elbilen lägger sitt redaktionella material i egna posttyper i stället för "posts",
     * och då räcker inte en enda endpoint. Länkarna varvas inte, de läggs i tur och ordning.
     */
    private List<String> discoverWpJson(Source source) throws Exception {
        List<String> links = new ArrayList<>();
        for (String endpoint : source.url().split(",")) {
            links.addAll(parseWpJsonLinks(fetchRaw(endpoint.trim())));
        }
        return links;
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
        String body = postGroq(SYSTEM_PROMPT, "Källa: " + kind + "\n\nText:\n\n" + trimmed, 1500, label);
        return body == null ? List.of() : parseInsightJson(body, label);
    }

    /** Extraktionen körs på insiktsmodellen. */
    private String postGroq(String systemPrompt, String userContent, int maxTokens, String label) throws Exception {
        return postGroq(insightModel, systemPrompt, userContent, maxTokens, label);
    }

    /** Skickar en chat completion till Groq. Returnerar svarskroppen, eller null vid fel/kvarstående 429. */
    private String postGroq(String model, String systemPrompt, String userContent, int maxTokens, String label) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)),
                "max_tokens", maxTokens,
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
                long wait = retryDelayMs(resp.headers().firstValue("retry-after").orElse(null),
                        resp.body(), attempt);
                log.info("Web insights: Groq rate limit — väntar {}s ({})", wait / 1000.0, label);
                Thread.sleep(wait);
                continue;
            }
            if (resp.statusCode() != 200) {
                log.warn("Web insights: Groq {} för {}: {}", resp.statusCode(), label, truncate(resp.body(), 200));
                return null;
            }
            return resp.body();
        }
        log.warn("Web insights: rate limit kvarstår efter 3 försök — hoppar över {}", label);
        return null;
    }

    /**
     * Hur länge vi ska vänta på en 429. Groq säger själv till — i <code>retry-after</code>-headern
     * (sekunder) och i felmeddelandet ("try again in 2m59.56s"). Den fasta trappan 30/60/90 s
     * som stod här förut kostade 8 av 12 minuter i nattkörningen 2026-07-31: alla 16 väntor
     * loggades som första försöket, dvs. omförsöket lyckades varje gång och 30 s var för mycket.
     * Taket finns för att en enstaka lång gräns inte ska binda hela synken; golvet för att
     * inte hamra vidare direkt.
     */
    static long retryDelayMs(String retryAfterHeader, String body, int attempt) {
        Long fromServer = parseSeconds(retryAfterHeader);
        if (fromServer == null) fromServer = parseWaitFromBody(body);
        long wait = fromServer != null ? fromServer : DEFAULT_BACKOFF_MS * (attempt + 1);
        return Math.max(MIN_BACKOFF_MS, Math.min(MAX_BACKOFF_MS, wait));
    }

    /** "2.5" / "30" → millisekunder. Ogiltigt eller saknat → null. */
    private static Long parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return (long) (Double.parseDouble(raw.trim()) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Plockar "try again in 2m59.56s" ur felkroppen när headern saknas. */
    private static Long parseWaitFromBody(String body) {
        if (body == null) return null;
        Matcher m = RETRY_IN_PATTERN.matcher(body);
        if (!m.find()) return null;
        long ms = 0;
        if (m.group(1) != null) ms += Long.parseLong(m.group(1)) * 60_000;
        if (m.group(2) != null) ms += (long) (Double.parseDouble(m.group(2)) * 1000);
        return ms;
    }

    private String contentOf(String responseBody) throws Exception {
        String content = mapper.readTree(responseBody)
                .path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.startsWith("```")) {
            content = content.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "");
        }
        return content;
    }

    List<JsonNode> parseInsightJson(String responseBody, String label) {
        try {
            JsonNode insights = mapper.readTree(contentOf(responseBody)).path("insights");
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
