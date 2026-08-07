package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bilrecensioner på YouTube till bilkorten. Slår upp en video per modell via YouTube
 * Data API v3 och cachar träffen i {@code car_video} — sökningen kostar 100 kvotenheter
 * av dygnets 10 000, så varje modell får kosta det EN gång, inte en gång per besökare.
 *
 * <p>Tre spärrar mot att kvoten tar slut:
 * <ul>
 *   <li>Träffen cachas permanent per modell.</li>
 *   <li>Även en <em>miss</em> cachas (tom {@code video_id}) — annars hade varje visning av
 *       en bil utan recension kostat 100 enheter igen. Missar prövas om efter
 *       {@link #MISS_RETRY_DAYS} dagar, eftersom en nylanserad bil får recensioner senare.</li>
 *   <li>Ett tak på {@link #MAX_LOOKUPS_PER_DAY} nya sökningar per dygn och process. Endpointen
 *       är publik, så utan tak hade uppräknade bilnamn kunnat bränna dygnskvoten.</li>
 * </ul>
 *
 * <p>Utan {@code YOUTUBE_API_KEY} är tjänsten helt passiv och svarar tomt — bilkortet
 * renderar då ingen videorad alls i stället för en trasig.
 */
@Service
public class CarVideoService {

    private static final Logger log = LoggerFactory.getLogger(CarVideoService.class);
    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";
    private static final int MISS_RETRY_DAYS = 30;
    private static final int MAX_LOOKUPS_PER_DAY = 80;
    private static final int MAX_CAR_NAME = 200;
    private static final int SEARCH_RESULTS = 5;

    /**
     * Kanaler som får gå före YouTubes relevansordning, svenska först och engelska som
     * andrahandsval. Matchas som gemen delsträng mot {@code channelTitle}, så
     * "Elbilsmagasinet Sverige" träffar lika bra som "Elbilsmagasinet" och kanalen
     * "Peter Esse " (med efterföljande mellanslag, uppmätt 2026-08-07) inte missas —
     * kanalnamn ändras, och exakt matchning hade tystnat vid minsta omdöpning.
     *
     * <p>Listorna är kurerade, inte uttömmande. Finns ingen av dem bland träffarna tas
     * första träffen som vanligt.
     */
    private static final List<String> SWEDISH_CHANNELS = List.of(
            "elbilsmagasinet",
            "peter esse",
            "teknikens värld",
            "vi bilägare",
            "auto motor & sport",
            "auto motor och sport");

    /** Andrahandsval när ingen svensk kanal finns i träfflistan. */
    private static final List<String> ENGLISH_CHANNELS = List.of(
            "autotrader",
            "carwow",
            "top gear",
            "auto express");

    private final JdbcTemplate jdbc;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${youtube.api.key:}")
    private String apiKey;

    private LocalDate quotaDay = LocalDate.now(ZoneOffset.UTC);
    private int lookupsToday = 0;

    public CarVideoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_video (
                car_name VARCHAR(200) PRIMARY KEY,
                video_id VARCHAR(20),
                title VARCHAR(300),
                channel VARCHAR(150),
                fetched_at VARCHAR(40)
            )
            """);
    }

    /**
     * @return tom map när ingen recension finns, nyckeln saknas eller kvoten är slut —
     *         aldrig null, så anroparen kan svara 200 med tomt objekt.
     */
    public Map<String, Object> findForCarTitle(String carTitle) {
        String car = normalize(carTitle);
        if (car.isEmpty()) return Map.of();

        Map<String, Object> cached = readCache(car);
        if (cached != null) return cached;

        String videoId = null, title = null, channel = null;
        if (canLookUp()) {
            JsonNode hit = pickBest(search(car, true));
            // Ingen svensk träff alls — då är en engelsk recension bättre än ingen rad.
            // Kostar ytterligare 100 enheter, men bara för bilar utan svensk bevakning,
            // och svaret cachas som alla andra.
            if (hit == null && canLookUp()) hit = pickBest(search(car, false));
            if (hit != null) {
                videoId = hit.path("id").path("videoId").asText("");
                title = hit.path("snippet").path("title").asText("");
                channel = hit.path("snippet").path("channelTitle").asText("");
            }
            // Även en miss skrivs — annars kostar samma bil 100 enheter vid varje visning
            writeCache(car, videoId, title, channel);
        }
        return toResult(videoId, title, channel);
    }

    /**
     * Glömmer en cachad video så nästa visning slår upp på nytt. Behövs av två skäl:
     * sökningen eller rankningen har ändrats, eller så fick en bil en video som inte
     * håller måttet. Kostar 100 kvotenheter nästa gång bilen visas.
     */
    public int forget(String carTitle) {
        String car = normalize(carTitle);
        if (car.isEmpty()) return 0;
        try {
            return jdbc.update("DELETE FROM car_video WHERE car_name = ?", car);
        } catch (Exception e) {
            log.warn("car_video: kunde inte glömma {}: {}", car, e.getMessage());
            return 0;
        }
    }

    /** Tömmer hela cachen — nästa visning av varje bil kostar ett nytt uppslag. */
    public int forgetAll() {
        try {
            return jdbc.update("DELETE FROM car_video");
        } catch (Exception e) {
            log.warn("car_video: kunde inte tömma cachen: {}", e.getMessage());
            return 0;
        }
    }

    /** Årtalet i "Volvo EX60 (2024)" hör inte hemma i en recensionssökning. */
    static String normalize(String carTitle) {
        if (carTitle == null) return "";
        String s = carTitle.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim();
        return s.length() > MAX_CAR_NAME ? s.substring(0, MAX_CAR_NAME) : s;
    }

    private Map<String, Object> readCache(String car) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT video_id, title, channel, fetched_at FROM car_video WHERE car_name = ?", car);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            String videoId = str(row.get("video_id"));
            if (videoId.isEmpty() && missIsStale(str(row.get("fetched_at")))) return null;
            return toResult(videoId, str(row.get("title")), str(row.get("channel")));
        } catch (Exception e) {
            log.warn("car_video: kunde inte läsa cache för {}: {}", car, e.getMessage());
            return Map.of();   // hellre inget videoblock än ett YouTube-anrop per besökare
        }
    }

    /** En bil utan recension idag kan ha en om en månad — men bara en gång i månaden. */
    private static boolean missIsStale(String fetchedAt) {
        try {
            return LocalDateTime.parse(fetchedAt.replace(' ', 'T'))
                    .isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(MISS_RETRY_DAYS));
        } catch (Exception e) {
            return true;
        }
    }

    private void writeCache(String car, String videoId, String title, String channel) {
        try {
            jdbc.update("DELETE FROM car_video WHERE car_name = ?", car);
            jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                    car, videoId == null ? "" : videoId, trim(title, 300), trim(channel, 150),
                    LocalDateTime.now(ZoneOffset.UTC).toString());
        } catch (Exception e) {
            log.warn("car_video: kunde inte spara {}: {}", car, e.getMessage());
        }
    }

    private synchronized boolean canLookUp() {
        if (apiKey == null || apiKey.isBlank()) return false;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(quotaDay)) {
            quotaDay = today;
            lookupsToday = 0;
        }
        if (lookupsToday >= MAX_LOOKUPS_PER_DAY) {
            log.warn("car_video: dygnstaket {} sökningar nått — serverar bara cache", MAX_LOOKUPS_PER_DAY);
            return false;
        }
        lookupsToday++;
        return true;
    }

    /**
     * Söker på svenska, alltid med märke plus modell i citattecken så träffen garanterat
     * gäller rätt bil. Recensionsordet är en OR-lista (YouTubes {@code |}-operator):
     * en nylanserad bil hinner sällan få en färdig "recension", men däremot en första
     * provkörning — "Första provkörningen: Nya Volvo EX60" är precis vad man vill se på
     * kortet så länge bilen är ny.
     *
     * <p>{@link #SEARCH_RESULTS} träffar hämtas fast bara en används: en sökning kostar
     * 100 kvotenheter oavsett hur många resultat den returnerar, så urvalet är gratis.
     */
    private JsonNode search(String car, boolean swedish) {
        try {
            String q = URLEncoder.encode(swedish
                    ? "\"" + car + "\" recension|provkörning|test"
                    : "\"" + car + "\" review", StandardCharsets.UTF_8);
            URI uri = URI.create(SEARCH_URL + "?part=snippet&type=video&maxResults=" + SEARCH_RESULTS
                    + "&safeSearch=strict&relevanceLanguage=" + (swedish ? "sv&regionCode=SE" : "en")
                    + "&q=" + q + "&key=" + apiKey);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("car_video: YouTube svarade {} för {}", resp.statusCode(), car);
                return null;
            }
            JsonNode items = mapper.readTree(resp.body()).path("items");
            return items.isArray() && !items.isEmpty() ? items : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("car_video: sökning misslyckades för {}: {}", car, e.getMessage());
            return null;
        }
    }

    /** Titlar som lovar en provkörning snarare än en nyhetsnotis om samma bil. */
    private static final List<String> REVIEW_WORDS = List.of(
            "recension", "provkör", "test", "review", "first drive", "körd");

    /**
     * Rankningen väger två saker: kanalen och vad klippet är. Kanalen väger tyngst —
     * svensk känd kanal före engelsk känd före okänd — men inom samma kanalklass går ett
     * klipp vars titel lovar en provkörning före en nyhetsnotis. Utan titelvikten valdes
     * "Volvo EX60 levererad – nu gäller det!" före den första provkörningen av samma bil,
     * från samma sorts kanal (uppmätt skarpt 2026-08-07).
     *
     * <p>Vid lika poäng vinner YouTubes egen ordning, alltså tidigaste träffen.
     */
    static JsonNode pickBest(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) return null;
        JsonNode best = null;
        int bestScore = Integer.MAX_VALUE;
        for (JsonNode item : items) {
            int score = score(item);
            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    /** Lägre är bättre: kanalklassen dominerar, titeln skiljer inom klassen. */
    private static int score(JsonNode item) {
        String channel = item.path("snippet").path("channelTitle").asText("").toLowerCase(Locale.ROOT);
        String title = item.path("snippet").path("title").asText("").toLowerCase(Locale.ROOT);
        int tier = matches(channel, SWEDISH_CHANNELS) ? 0 : matches(channel, ENGLISH_CHANNELS) ? 1 : 2;
        return tier * 10 + (matches(title, REVIEW_WORDS) ? 0 : 1);
    }

    private static boolean matches(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static Map<String, Object> toResult(String videoId, String title, String channel) {
        if (videoId == null || videoId.isBlank()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", videoId);
        out.put("title", title == null ? "" : title);
        out.put("channel", channel == null ? "" : channel);
        out.put("url", "https://www.youtube.com/watch?v=" + videoId);
        out.put("thumbnail", String.format(Locale.ROOT, "https://i.ytimg.com/vi/%s/hqdefault.jpg", videoId));
        return out;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
