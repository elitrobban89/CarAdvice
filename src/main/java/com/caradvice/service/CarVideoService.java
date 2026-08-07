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
            JsonNode hit = search(car);
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

    /** Första träffen, eller null. "recension test" på svenska ger svenska biltester. */
    private JsonNode search(String car) {
        try {
            String q = URLEncoder.encode(car + " recension test", StandardCharsets.UTF_8);
            URI uri = URI.create(SEARCH_URL + "?part=snippet&type=video&maxResults=1&safeSearch=strict"
                    + "&relevanceLanguage=sv&regionCode=SE&q=" + q + "&key=" + apiKey);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("car_video: YouTube svarade {} för {}", resp.statusCode(), car);
                return null;
            }
            JsonNode items = mapper.readTree(resp.body()).path("items");
            return items.isArray() && !items.isEmpty() ? items.get(0) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("car_video: sökning misslyckades för {}: {}", car, e.getMessage());
            return null;
        }
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
