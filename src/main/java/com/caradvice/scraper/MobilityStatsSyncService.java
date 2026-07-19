package com.caradvice.scraper;

import com.caradvice.model.ExpertInsight;
import com.caradvice.service.ExpertInsightService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hämtar Mobility Swedens månadsrapport (xlsx-bilagan i månadspressmeddelandet) och
 * uppdaterar registreringsstatistik-insikterna. Själva statistikdatabasen på
 * mobilitysweden.se är en Power BI-embed (oskrapbar), men pressmeddelandena har en
 * "Månadsrapport Nyregistreringar <månad> <år>.xlsx" med arken "PB - Rankinglista"
 * (alla personbilar) och "Elbil ranking" — YTD-sorterade modelltopplistor.
 *
 * Insikterna skrivs under ett eget källnamn och ERSÄTTS varje körning — de årsvisa
 * kuraterade raderna under "Mobility Sweden" rörs aldrig.
 */
@Service
public class MobilityStatsSyncService {

    private static final Logger log = LoggerFactory.getLogger(MobilityStatsSyncService.class);

    /** Eget källnamn så månadsraderna kan ersättas utan att röra de kuraterade årsraderna */
    static final String EXPERT_NAME = "Mobility Sweden månadsläget";

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; CarAdviceBot/1.0; +https://caradvice.onrender.com)";
    private static final String SITE = "https://mobilitysweden.se";
    private static final String LISTING_URL_PREFIX =
            SITE + "/statistik/Nyregistreringar_per_manad_1/nyregistreringar-";

    private static final String SHEET_TOTAL = "PB - Rankinglista";
    private static final String SHEET_EV = "Elbil ranking";
    private static final int COL_MODEL = 3;   // D — modellnamn (versalt)
    private static final int COL_MONTH = 5;   // F — antal aktuell månad
    private static final int COL_YTD = 12;    // M — ackumulerat i år
    private static final int MAX_DATA_ROWS = 25;

    private static final List<String> MONTHS = List.of(
            "januari", "februari", "mars", "april", "maj", "juni",
            "juli", "augusti", "september", "oktober", "november", "december");

    /** Rapportens gruppnamn → modellnamn som matchar bilkortens titlar */
    private static final Map<String, String> MODEL_GROUP_MAP = Map.of(
            "EX/XC40", "EX40",
            "ID.7/ID.7 TOURER", "ID.7");

    private static final Map<String, String> MAKE_MAP = Map.of(
            "VW", "Volkswagen",
            "MERCEDES-BENZ", "Mercedes",
            "BMW", "BMW");

    private final ExpertInsightService insightService;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public MobilityStatsSyncService(ExpertInsightService insightService) {
        this.insightService = insightService;
    }

    /** Hämtar senaste rapporten och ersätter månadsinsikterna. Returnerar körsammanfattning. */
    public Map<String, Object> syncNow() {
        try {
            String xlsxUrl = findLatestReportUrl();
            if (xlsxUrl == null) return Map.of("status", "ERROR", "error", "Hittade ingen xlsx-rapport");

            List<ExpertInsight> insights = parseReport(download(xlsxUrl));
            if (insights.isEmpty()) return Map.of("status", "ERROR", "error", "Inga rader kunde tolkas ur " + xlsxUrl);

            insightService.deleteByExpert(EXPERT_NAME);
            insights.forEach(insightService::save);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("imported", insights.size());
            result.put("source", xlsxUrl);
            result.put("insights", insights.stream()
                    .map(i -> i.getCarMake() + " " + i.getCarModel() + ": " + i.getInsight()).toList());
            log.info("Mobility stats sync: {} insikter ur {}", insights.size(), xlsxUrl);
            return result;
        } catch (Exception e) {
            log.error("Mobility stats sync misslyckades: {}", e.getMessage(), e);
            return Map.of("status", "ERROR", "error", String.valueOf(e.getMessage()));
        }
    }

    // ── Upptäckt ──────────────────────────────────────────────────────────────

    /** Årssidan listar pressmeddelanden äldst först — leta xlsx bakifrån. I januari kan
     *  årets sida sakna rapporter (decemberrapporten släpps i januari på fjolårssidan). */
    String findLatestReportUrl() throws Exception {
        int year = LocalDate.now(ZoneId.of("Europe/Stockholm")).getYear();
        for (int y : new int[]{year, year - 1}) {
            List<String> articles = extractArticleUrls(fetchHtml(LISTING_URL_PREFIX + y), y);
            for (int i = articles.size() - 1; i >= 0 && i >= articles.size() - 3; i--) {
                String xlsx = extractXlsxUrl(fetchHtml(articles.get(i)));
                if (xlsx != null) return xlsx;
            }
        }
        return null;
    }

    static List<String> extractArticleUrls(String listingHtml, int year) {
        Document doc = Jsoup.parse(listingHtml, SITE);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=/nyregistreringar-" + year + "/]")) {
            urls.add(a.absUrl("href"));
        }
        return new ArrayList<>(urls);
    }

    static String extractXlsxUrl(String articleHtml) {
        Document doc = Jsoup.parse(articleHtml, SITE);
        for (Element a : doc.select("a[href$=.xlsx]")) {
            String decoded = URLDecoder.decode(a.attr("href"), StandardCharsets.UTF_8).toLowerCase();
            if (decoded.contains("nadsrapport") || decoded.contains("nyregistreringar")) return a.absUrl("href");
        }
        return null;
    }

    // ── Parsning ──────────────────────────────────────────────────────────────

    /** En modellrad ur rankingarket */
    record RankRow(String rawName, int monthCount, int ytd) {}

    List<ExpertInsight> parseReport(byte[] xlsx) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet total = wb.getSheet(SHEET_TOTAL);
            Sheet ev = wb.getSheet(SHEET_EV);
            if (total == null || ev == null) return List.of();

            String period = findPeriod(total);                     // "juni 2026"
            String range = periodRange(period);                    // "januari–juni 2026"
            List<RankRow> totalTop = dataRows(total);
            List<RankRow> evTop = dataRows(ev);
            if (totalTop.isEmpty() || evTop.isEmpty() || period == null) return List.of();

            List<ExpertInsight> out = new ArrayList<>();

            RankRow leader = totalTop.get(0);
            String[] lm = normalizeName(leader.rawName());
            out.add(new ExpertInsight(EXPERT_NAME, lm[0], lm[1], null, null,
                    "Sveriges mest registrerade bil " + range + ": " + fmt(leader.ytd())
                            + " nyregistreringar (Mobility Swedens månadsrapport).", null));

            RankRow evLeader = evTop.get(0);
            String[] em = normalizeName(evLeader.rawName());
            String runnerUp = evTop.size() > 1 ? displayName(evTop.get(1).rawName()) : null;
            out.add(new ExpertInsight(EXPERT_NAME, em[0], em[1], "elbil", null,
                    "Sveriges mest registrerade elbil " + range + ": " + fmt(evLeader.ytd())
                            + " nyregistreringar" + (runnerUp != null ? ", före " + runnerUp : "") + ".", null));

            // Månadens etta (kan skilja sig från YTD-ettan) — hoppa över om samma bil
            RankRow monthLeader = totalTop.stream()
                    .max((a, b) -> Integer.compare(a.monthCount(), b.monthCount())).orElse(leader);
            if (!monthLeader.rawName().equals(leader.rawName())) {
                String[] mm = normalizeName(monthLeader.rawName());
                out.add(new ExpertInsight(EXPERT_NAME, mm[0], mm[1], null, null,
                        "Månadens mest registrerade bil i Sverige (" + period + "): "
                                + fmt(monthLeader.monthCount()) + " nyregistreringar.", null));
            }
            return out;
        }
    }

    /** Datarader = sträng i modellkolumnen + tal i YTD-kolumnen (hoppar rubrikrader) */
    static List<RankRow> dataRows(Sheet sheet) {
        List<RankRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (rows.size() >= MAX_DATA_ROWS) break;
            String name = stringAt(row, COL_MODEL);
            Integer ytd = intAt(row, COL_YTD);
            if (name == null || ytd == null || name.contains("Modell")) continue;
            Integer month = intAt(row, COL_MONTH);
            rows.add(new RankRow(name, month == null ? 0 : month, ytd));
        }
        return rows;
    }

    /** Letar "Nyregistreringar <månad> <år>" i arkets översta rader */
    static String findPeriod(Sheet sheet) {
        Pattern p = Pattern.compile("Nyregistreringar\\s+(\\p{L}+)\\s+(\\d{4})");
        for (Row row : sheet) {
            if (row.getRowNum() > 8) break;
            for (Cell c : row) {
                if (c.getCellType() == CellType.STRING) {
                    Matcher m = p.matcher(c.getStringCellValue());
                    if (m.find()) return m.group(1).toLowerCase() + " " + m.group(2);
                }
            }
        }
        return null;
    }

    /** "juni 2026" → "januari–juni 2026" (bara "januari 2026" i januari) */
    static String periodRange(String period) {
        if (period == null) return null;
        String month = period.split(" ")[0];
        return MONTHS.indexOf(month) <= 0 ? period : "januari–" + period;
    }

    // ── Namnnormalisering ─────────────────────────────────────────────────────

    /** "VOLVO EX/XC40" → {"Volvo", "EX40"}; "VW ID.7/ID.7 TOURER" → {"Volkswagen", "ID.7"} */
    static String[] normalizeName(String raw) {
        String s = raw.trim();
        int sp = s.indexOf(' ');
        if (sp < 0) return new String[]{titleCase(s), null};
        String make = s.substring(0, sp);
        String model = s.substring(sp + 1).trim();
        make = MAKE_MAP.getOrDefault(make, titleCase(make));
        model = MODEL_GROUP_MAP.getOrDefault(model,
                model.contains("/") ? model.substring(0, model.indexOf('/')).trim() : model);
        // Rena ordtokens ("MODEL", "TOURER") får versalgemener; korta modellkoder (CLA, EQS)
        // och tokens med siffror/punkt (XC60, ID.7, BZ4X) behålls som de är
        model = Arrays.stream(model.split("\\s+"))
                .map(t -> t.matches("[A-ZÅÄÖ]{4,}") ? titleCase(t) : t)
                .collect(Collectors.joining(" "));
        return new String[]{make, model};
    }

    static String displayName(String raw) {
        String[] parts = normalizeName(raw);
        return parts[1] == null ? parts[0] : parts[0] + " " + parts[1];
    }

    private static String titleCase(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase();
    }

    /** 7594 → "7 594" (vanligt mellanslag, inte NBSP) */
    private static String fmt(int n) {
        return String.format(java.util.Locale.US, "%,d", n).replace(',', ' ').replace(' ', ' ');
    }

    private static String stringAt(Row row, int col) {
        Cell c = row.getCell(col);
        if (c == null || c.getCellType() != CellType.STRING) return null;
        String v = c.getStringCellValue().trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer intAt(Row row, int col) {
        Cell c = row.getCell(col);
        if (c == null || c.getCellType() != CellType.NUMERIC) return null;
        return (int) Math.round(c.getNumericCellValue());
    }

    // ── Hämtning (paketprivat så tester kan stubba) ───────────────────────────

    String fetchHtml(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "sv-SE,sv;q=0.9")
                .timeout(20_000)
                .execute().body();
    }

    byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode() + " för " + url);
        return resp.body();
    }
}
