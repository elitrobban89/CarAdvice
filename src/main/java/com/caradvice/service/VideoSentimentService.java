package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "YouTube-betyg" på bilkortet: vad säger kommentarerna under recensionen om BILEN?
 *
 * <p>Kommentarerna hämtas med {@code commentThreads.list}, som kostar 1 kvotenhet mot
 * sökningens 100 — den dyra delen är alltså redan betald när videon slogs upp. Sedan får
 * Groq klassa dem, för det är inte ett ordräkningsproblem: "bromsarna gnisslar men det är
 * fixat på garanti" är inte negativt, och "grym video, tack!" säger ingenting alls om bilen.
 *
 * <p><b>Två gränser som är medvetna, inte tillfälliga:</b>
 * <ul>
 *   <li>Prompten räknar BARA omdömen om bilen. Beröm och kritik av kanalen, filmningen
 *       eller programledaren ska ignoreras — annars mäter betyget videons kvalitet.</li>
 *   <li>Under {@link #MIN_RELEVANT_COMMENTS} bilrelaterade kommentarer visas inget betyg
 *       alls. Ett "Dåligt" byggt på tre kommentarer är sämre än ingen ruta, och
 *       kommentarsfält är hur som helst inget representativt ägarurval.</li>
 * </ul>
 */
@Service
public class VideoSentimentService {

    private static final Logger log = LoggerFactory.getLogger(VideoSentimentService.class);
    private static final String COMMENTS_URL = "https://www.googleapis.com/youtube/v3/commentThreads";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final int MAX_COMMENTS = 60;
    private static final int MIN_RELEVANT_COMMENTS = 12;
    private static final int MAX_COMMENT_CHARS = 300;
    private static final int TTL_DAYS = 30;

    private static final String PROMPT = """
            Du läser YouTube-kommentarer under en bilrecension och sammanfattar vad de
            säger om BILEN.

            Räkna BARA omdömen om bilen: köregenskaper, kvalitet, fel, räckvidd, pris,
            komfort, ägarerfarenheter. Ignorera allt som handlar om videon, kanalen,
            programledaren, filmningen eller andra tittare ("grym video", "bra jobbat",
            "först!"). Ignorera också politik, märkeskrig och kommentarer om helt andra
            bilmodeller.

            Svara ENDAST med valid JSON:
            {"verdict": "bra|blandat|daligt", "relevanta": <antal kommentarer du räknade>,
             "summary": "<en mening på svenska om vad kommentarerna lyfter>"}

            "bra" = tydlig övervikt positiva omdömen om bilen.
            "daligt" = tydlig övervikt negativa.
            "blandat" = jämnt, eller lika mycket beröm som kritik.
            Är nästan inga kommentarer om bilen: sätt relevanta till det låga antalet ändå.
            Summary ska nämna VAD de tycker, inte att de tycker: "flera vittnar om
            mjukvarustrul men berömmer stolarna" — inte "kommentarerna är blandade".
            """;

    private final JdbcTemplate jdbc;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${youtube.api.key:}")
    private String youtubeKey;

    @Value("${groq.api.key:}")
    private String groqKey;

    @Value("${groq.chat.model:openai/gpt-oss-20b}")
    private String model;

    public VideoSentimentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        // Egen tabell i stället för kolumner på car_video: prod kör ddl-auto=validate och
        // en ny tabell är portabel där ALTER TABLE är en risk. Samma skäl som insight_upcoming.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_video_sentiment (
                car_name VARCHAR(200) PRIMARY KEY,
                video_id VARCHAR(20),
                verdict VARCHAR(10),
                summary VARCHAR(500),
                comment_count INT,
                fetched_at VARCHAR(40)
            )
            """);
    }

    /** Tom map när betyg saknas, underlaget är för tunt eller någon nyckel inte är satt. */
    public Map<String, Object> forVideo(String carName, String videoId) {
        if (carName == null || carName.isBlank() || videoId == null || videoId.isBlank()) return Map.of();

        Map<String, Object> cached = readCache(carName, videoId);
        if (cached != null) return cached;
        if (youtubeKey.isBlank() || groqKey.isBlank()) return Map.of();

        List<String> comments = fetchComments(videoId);
        if (comments.isEmpty()) {
            write(carName, videoId, "", "", 0);   // kommentarer avstängda eller tomma
            return Map.of();
        }
        JsonNode verdictJson = classify(comments);
        if (verdictJson == null) return Map.of();   // Groq-fel: skriv inget, prova igen senare

        String verdict = verdictJson.path("verdict").asText("").toLowerCase(java.util.Locale.ROOT);
        int relevant = verdictJson.path("relevanta").asInt(0);
        String summary = verdictJson.path("summary").asText("");
        if (!List.of("bra", "blandat", "daligt").contains(verdict)) verdict = "";
        write(carName, videoId, verdict, summary, relevant);
        return toResult(verdict, summary, relevant);
    }

    private Map<String, Object> readCache(String car, String videoId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT video_id, verdict, summary, comment_count, fetched_at "
                            + "FROM car_video_sentiment WHERE car_name = ?", car);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            // Bytt video (ny recension vald) eller för gammal dom → räkna om
            if (!videoId.equals(str(row.get("video_id")))) return null;
            if (isStale(str(row.get("fetched_at")))) return null;
            return toResult(str(row.get("verdict")), str(row.get("summary")),
                    row.get("comment_count") == null ? 0 : ((Number) row.get("comment_count")).intValue());
        } catch (Exception e) {
            log.warn("car_video_sentiment: kunde inte läsa cache för {}: {}", car, e.getMessage());
            return Map.of();
        }
    }

    private static boolean isStale(String fetchedAt) {
        try {
            return LocalDateTime.parse(fetchedAt).isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(TTL_DAYS));
        } catch (Exception e) {
            return true;
        }
    }

    private void write(String car, String videoId, String verdict, String summary, int count) {
        try {
            jdbc.update("DELETE FROM car_video_sentiment WHERE car_name = ?", car);
            jdbc.update("INSERT INTO car_video_sentiment"
                            + "(car_name, video_id, verdict, summary, comment_count, fetched_at) VALUES (?,?,?,?,?,?)",
                    car, videoId, verdict, summary.length() > 500 ? summary.substring(0, 500) : summary,
                    count, LocalDateTime.now(ZoneOffset.UTC).toString());
        } catch (Exception e) {
            log.warn("car_video_sentiment: kunde inte spara {}: {}", car, e.getMessage());
        }
    }

    /** Toppkommentarer efter relevans. 1 kvotenhet — billigt nog att göra per video. */
    private List<String> fetchComments(String videoId) {
        List<String> out = new ArrayList<>();
        try {
            URI uri = URI.create(COMMENTS_URL + "?part=snippet&order=relevance&textFormat=plainText"
                    + "&maxResults=" + MAX_COMMENTS
                    + "&videoId=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                    + "&key=" + youtubeKey);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // 403 = kommentarer avstängda på videon; vanligt och inget att larma om
                log.info("car_video_sentiment: kommentarer ej tillgängliga för {} (HTTP {})",
                        videoId, resp.statusCode());
                return out;
            }
            for (JsonNode item : mapper.readTree(resp.body()).path("items")) {
                String text = item.path("snippet").path("topLevelComment").path("snippet")
                        .path("textDisplay").asText("").replace('\n', ' ').trim();
                if (!text.isBlank()) {
                    out.add(text.length() > MAX_COMMENT_CHARS ? text.substring(0, MAX_COMMENT_CHARS) : text);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("car_video_sentiment: kunde inte hämta kommentarer för {}: {}", videoId, e.getMessage());
        }
        return out;
    }

    /**
     * Hur länge kommentarsanalysen håller sig borta från Groq efter ett 429.
     *
     * <p>Tjänsten kör på {@code groq.chat.model}, som också är rekommendationernas
     * FALLBACK-modell — de delar alltså per-minut-hink. Analysen är en trevlighet på ett bilkort
     * medan rekommendationen är hela svaret användaren väntar på, så när hinken är tom ska den
     * här vika undan. Utan spärren gjorde den tvärtom: mätt i produktionsloggen 2026-08-13
     * kl. 08:59-09:01 gick åtta {@code car_video_sentiment}-anrop rakt in i 429 under exakt de
     * minuter då rekommendationsvägen svalt, och varje sådant anrop är ren belastning — svaret
     * kastas ändå.
     *
     * <p>Ett saknat omdöme kostar ingenting: {@code classify} returnerar redan null vid fel och
     * anroparen klarar det. Fönstret är satt efter Groqs egna {@code try again in}-besked i
     * samma logg (10-59 s).
     */
    private static final long GROQ_COOL_OFF_MS = 60_000L;

    /** Epoch-ms då kommentarsanalysen får prata med Groq igen. 0 = ingen paus. */
    private volatile long groqPausedUntil = 0L;

    private JsonNode classify(List<String> comments) {
        if (System.currentTimeMillis() < groqPausedUntil) return null;
        try {
            StringBuilder user = new StringBuilder("KOMMENTARER:\n");
            for (int i = 0; i < comments.size(); i++) {
                user.append(i).append(": ").append(comments.get(i)).append('\n');
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 400);
            body.put("temperature", 0.2);
            body.put("reasoning_effort", "low");
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", PROMPT);
            messages.addObject().put("role", "user").put("content", user.toString());

            HttpRequest req = HttpRequest.newBuilder(URI.create(GROQ_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + groqKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                if (resp.statusCode() == 429) {
                    groqPausedUntil = System.currentTimeMillis() + GROQ_COOL_OFF_MS;
                    log.warn("car_video_sentiment: Groq svarade 429 — pausar kommentarsanalysen {} s"
                            + " så rekommendationerna får hinken", GROQ_COOL_OFF_MS / 1000);
                } else {
                    log.warn("car_video_sentiment: Groq svarade {}", resp.statusCode());
                }
                return null;
            }
            String content = mapper.readTree(resp.body()).path("choices").path(0)
                    .path("message").path("content").asText("");
            return parseJson(content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("car_video_sentiment: klassificeringen misslyckades: {}", e.getMessage());
            return null;
        }
    }

    /** Reasoning-modellen kan rama in JSON i text — plocka ut objektet. */
    JsonNode parseJson(String content) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            return mapper.readTree(content.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** Under tröskeln visas ingen ruta — se klassdokumentationen. */
    static Map<String, Object> toResult(String verdict, String summary, int relevant) {
        if (verdict == null || verdict.isBlank() || relevant < MIN_RELEVANT_COMMENTS) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        out.put("label", switch (verdict) {
            case "bra" -> "Bra";
            case "daligt" -> "Dåligt";
            default -> "Blandat";
        });
        out.put("summary", summary);
        out.put("commentCount", relevant);
        return out;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
