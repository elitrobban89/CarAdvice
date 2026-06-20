package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.scraper.EvDatabaseScraperService;
import com.caradvice.service.ExpertInsightService;
import com.caradvice.service.GroqService;
import com.caradvice.service.SafetyRatingService;
import com.caradvice.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://elitrobban.se", "http://localhost:8080", "http://localhost:3000", "http://127.0.0.1:8080"})
public class CarController {

    private final GroqService groqService;
    private final ExpertInsightService expertInsightService;
    private final SafetyRatingService safetyRatingService;
    private final EvDatabaseScraperService evScraper;
    private final UserService userService;
    private final Map<String, List<Long>> ipRequestLog = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_REQUESTS_PER_HOUR = 10;
    private static final int MAX_LOGGED_IN_REQUESTS_PER_HOUR = 30;

    private static final int CHAT_RATE_LIMIT = 10;
    private static final int CHAT_LOGGED_IN_RATE_LIMIT = 30;
    private static final long CHAT_WINDOW_MS = 60_000L;
    private final ConcurrentHashMap<String, Deque<Long>> chatTimestamps = new ConcurrentHashMap<>();

    @Value("${admin.key}")
    private String adminKey;

    public CarController(GroqService groqService, ExpertInsightService expertInsightService,
                         SafetyRatingService safetyRatingService, EvDatabaseScraperService evScraper,
                         UserService userService) {
        this.groqService = groqService;
        this.expertInsightService = expertInsightService;
        this.safetyRatingService = safetyRatingService;
        this.evScraper = evScraper;
        this.userService = userService;
    }

    @PostMapping("/admin/sync-ev-specs")
    public ResponseEntity<?> syncEvSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!adminKey.equals(key)) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        Thread.ofVirtual().start(() -> {
            try { evScraper.syncFromEvDatabase(); }
            catch (Exception e) { /* logged inside scraper */ }
        });
        return ResponseEntity.accepted().body(Map.of("status", "sync started — check server logs for result"));
    }

    @PostMapping("/recommend")
    public ResponseEntity<?> recommend(@RequestBody CarPreferences prefs, HttpServletRequest request,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        String ip = getClientIp(request);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        int limit = loggedIn ? MAX_LOGGED_IN_REQUESTS_PER_HOUR : MAX_REQUESTS_PER_HOUR;
        if (!subscriber && isRateLimited(ip, limit)) {
            String msg = loggedIn
                    ? "Du har använt dina 30 sökningar denna timme. Försök igen om en stund."
                    : "Du har använt dina 10 gratis sökningar denna timme. Logga in för 30 sökningar per timme!";
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", msg,
                    "rateLimited", true
            ));
        }
        try {
            GroqService.Result result = groqService.getRecommendation(prefs);
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("recommendations", result.recommendations());
            body.put("subscriber", subscriber);
            body.put("loggedIn", loggedIn);
            if (!subscriber) body.put("remainingSearches", remainingSearches(ip, limit));
            if (result.fromCache()) {
                body.put("cached", true);
                body.put("cachedAgeMinutes", result.cacheAgeSeconds() / 60);
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> req, HttpServletRequest httpReq,
                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        String ip = getClientIp(httpReq);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        int chatLimit = loggedIn ? CHAT_LOGGED_IN_RATE_LIMIT : CHAT_RATE_LIMIT;
        long now = System.currentTimeMillis();
        Deque<Long> times = chatTimestamps.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CHAT_WINDOW_MS) times.pollFirst();
            if (!subscriber && times.size() >= chatLimit)
                return ResponseEntity.status(429).body(Map.of("error", "För många frågor — vänta en minut och försök igen.", "rateLimited", true));
            times.addLast(now);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages = (List<Map<String, String>>) req.get("messages");
            if (messages == null || messages.isEmpty())
                return ResponseEntity.ok(Map.of("reply", "Inga meddelanden."));
            String context = (String) req.get("context");
            return ResponseEntity.ok(Map.of("reply", groqService.chat(messages, context)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody Map<String, Object> req, HttpServletRequest httpReq,
                                                            @RequestHeader(value = "Authorization", required = false) String auth) {
        String ip = getClientIp(httpReq);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedInStream = subscriber || userService.isLoggedIn(auth);
        int chatLimitStream = loggedInStream ? CHAT_LOGGED_IN_RATE_LIMIT : CHAT_RATE_LIMIT;
        long now = System.currentTimeMillis();
        Deque<Long> times = chatTimestamps.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CHAT_WINDOW_MS) times.pollFirst();
            if (!subscriber && times.size() >= chatLimitStream)
                return ResponseEntity.status(429).build();
            times.addLast(now);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) req.get("messages");
        if (messages == null || messages.isEmpty())
            return ResponseEntity.badRequest().build();
        String context = (String) req.get("context");

        StreamingResponseBody body = outputStream -> {
            try (InputStream is = groqService.chatStream(messages, context);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonNode node = mapper.readTree(data);
                        String token = node.at("/choices/0/delta/content").asText("");
                        if (!token.isEmpty()) {
                            outputStream.write(("data: " + mapper.writeValueAsString(token) + "\n\n").getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                outputStream.write(("data: " + mapper.writeValueAsString("[ERR]" + e.getMessage()) + "\n\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        };

        return ResponseEntity.ok()
                .header("Content-Type", "text/event-stream; charset=UTF-8")
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    @GetMapping("/recommend/test")
    public ResponseEntity<?> recommendTest() {
        boolean configured = groqService.isConfigured();
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "groq", configured ? "OK" : "WARN",
                "rekommendation", configured
        ));
    }

    // Admin: import expert insights from CSV (car_make,car_model,fuel_type,category,insight,rating)
    @PostMapping("/admin/import/insights")
    public ResponseEntity<?> importInsights(@RequestHeader("X-Admin-Key") String key,
                                            @RequestBody String csv) {
        if (!adminKey.equals(key)) return ResponseEntity.status(403).body("Unauthorized");
        try {
            int count = expertInsightService.importCsv(csv);
            return ResponseEntity.ok(Map.of("imported", count, "table", "expert_insight"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Admin: import Euro NCAP safety ratings from CSV (car_make,car_model,test_year,stars,adult_pct,child_pct,pedestrian_pct,safety_assist_pct)
    @PostMapping("/admin/import/safety")
    public ResponseEntity<?> importSafety(@RequestHeader("X-Admin-Key") String key,
                                          @RequestBody String csv) {
        if (!adminKey.equals(key)) return ResponseEntity.status(403).body("Unauthorized");
        try {
            int count = safetyRatingService.importCsv(csv);
            return ResponseEntity.ok(Map.of("imported", count, "table", "safety_rating"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isRateLimited(String ip, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = now - 3_600_000;
        ipRequestLog.compute(ip, (k, times) -> {
            List<Long> updated = (times == null) ? new ArrayList<>() : times;
            updated.removeIf(t -> t < windowStart);
            updated.add(now);
            return updated;
        });
        return ipRequestLog.get(ip).size() > limit;
    }

    private int remainingSearches(String ip, int limit) {
        List<Long> times = ipRequestLog.getOrDefault(ip, List.of());
        return Math.max(0, limit - times.size());
    }
}
