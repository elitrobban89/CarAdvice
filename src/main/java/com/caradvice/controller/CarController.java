package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.RateLimitLog;
import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.RateLimitLogRepository;
import com.caradvice.scraper.CargoSpecSyncService;
import com.caradvice.scraper.EvDatabaseScraperService;
import com.caradvice.scraper.WebInsightScraperService;
import com.caradvice.service.CargoSpecService;
import com.caradvice.service.ExpertInsightService;
import com.caradvice.service.FeedbackService;
import com.caradvice.service.GroqService;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.NewCarPriceService;
import com.caradvice.service.SafetyRatingService;
import com.caradvice.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class CarController {

    private static final Logger log = LoggerFactory.getLogger(CarController.class);

    private final GroqService groqService;
    private final ExpertInsightService expertInsightService;
    private final SafetyRatingService safetyRatingService;
    private final EvDatabaseScraperService evScraper;
    private final CargoSpecSyncService cargoSpecSyncService;
    private final CargoSpecService cargoSpecService;
    private final UserService userService;
    private final RateLimitLogRepository rateLimitLogRepo;
    private final CargoSpecRepository cargoSpecRepo;
    private final EvSpecRepository evSpecRepo;
    private final FeedbackService feedbackService;
    private final WebInsightScraperService webInsightScraper;
    private final IceConsumptionService iceConsumptionService;
    private final Map<String, List<Long>> ipRequestLog = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_REQUESTS_PER_HOUR = 10;
    private static final int MAX_LOGGED_IN_REQUESTS_PER_HOUR = 30;

    private static final int CHAT_RATE_LIMIT = 20;
    private static final int CHAT_LOGGED_IN_RATE_LIMIT = 50;
    private static final long CHAT_WINDOW_MS = 60_000L;
    private final ConcurrentHashMap<String, Deque<Long>> chatTimestamps = new ConcurrentHashMap<>();

    private static final int FEEDBACK_RATE_LIMIT = 10;
    private final ConcurrentHashMap<String, Deque<Long>> feedbackTimestamps = new ConcurrentHashMap<>();

    @Value("${admin.key}")
    private String adminKey;

    public CarController(GroqService groqService, ExpertInsightService expertInsightService,
                         SafetyRatingService safetyRatingService, EvDatabaseScraperService evScraper,
                         CargoSpecSyncService cargoSpecSyncService, CargoSpecService cargoSpecService,
                         UserService userService, RateLimitLogRepository rateLimitLogRepo,
                         CargoSpecRepository cargoSpecRepo, EvSpecRepository evSpecRepo,
                         FeedbackService feedbackService, WebInsightScraperService webInsightScraper,
                         IceConsumptionService iceConsumptionService) {
        this.groqService = groqService;
        this.expertInsightService = expertInsightService;
        this.safetyRatingService = safetyRatingService;
        this.evScraper = evScraper;
        this.cargoSpecSyncService = cargoSpecSyncService;
        this.cargoSpecService = cargoSpecService;
        this.userService = userService;
        this.rateLimitLogRepo = rateLimitLogRepo;
        this.cargoSpecRepo = cargoSpecRepo;
        this.evSpecRepo = evSpecRepo;
        this.feedbackService = feedbackService;
        this.webInsightScraper = webInsightScraper;
        this.iceConsumptionService = iceConsumptionService;
    }

    @PostConstruct
    public void initRateLimits() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
            rateLimitLogRepo.findRecentRecommend(cutoff).forEach(entry -> {
                long ts = entry.getRequestTime().toEpochSecond(ZoneOffset.UTC) * 1000;
                ipRequestLog.computeIfAbsent(entry.getIp(), k -> new ArrayList<>()).add(ts);
            });
            log.debug("Rate limit: restored {} IP entries from DB", ipRequestLog.size());
        } catch (Exception e) {
            log.warn("Could not restore rate limit history from DB: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 15 * * * *")
    public void cleanupRateLimitLogs() {
        try {
            rateLimitLogRepo.deleteByRequestTimeBefore(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        } catch (Exception ignored) {}
    }

    private void persistRateLimit(String ip) {
        Thread.ofVirtual().start(() -> {
            try {
                rateLimitLogRepo.save(new RateLimitLog(ip, "recommend", LocalDateTime.now(ZoneOffset.UTC)));
            } catch (Exception ignored) {}
        });
    }

    @PostMapping("/admin/sync-ev-specs")
    public ResponseEntity<?> syncEvSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Thread.ofVirtual().start(() -> {
            try { evScraper.syncFromEvDatabase(); }
            catch (Exception e) { /* logged inside scraper */ }
        });
        return ResponseEntity.accepted().body(Map.of("status", "sync started — check server logs for result"));
    }

    @PostMapping("/admin/sync-cargo-specs")
    public ResponseEntity<?> syncCargoSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Thread.ofVirtual().start(() -> {
            try { cargoSpecSyncService.syncCarNames(); }
            catch (Exception e) { /* logged inside service */ }
        });
        return ResponseEntity.accepted().body(Map.of("status", "CargoSpec sync started — check server logs for result"));
    }

    @PostMapping("/admin/sync-web-insights")
    public ResponseEntity<?> syncWebInsights(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Thread.ofVirtual().start(() -> {
            try { webInsightScraper.syncAll(); }
            catch (Exception e) { /* logged inside service */ }
        });
        return ResponseEntity.accepted().body(Map.of("status", "web insight sync started — check server logs for result"));
    }

    // Admin: senaste insiktsscrape-körningens status (nattlig 04:00 eller manuell trigger)
    @GetMapping("/admin/scrape-status")
    public ResponseEntity<?> scrapeStatus(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(webInsightScraper.lastRunStatus());
    }

    // Admin: seed already-processed keys (URL:er/omdömes-refs) so the scraper skips them — text body, one key per line
    @PostMapping("/admin/import/seen-keys")
    public ResponseEntity<?> importSeenKeys(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                            @RequestBody String body) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int added = webInsightScraper.seedSeen(List.of(body.split("\\R")));
        return ResponseEntity.ok(Map.of("added", added, "table", "web_insight_seen"));
    }

    @PostMapping("/admin/import/cargospecs")
    public ResponseEntity<?> importCargoSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                              @RequestBody String csv) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            int count = cargoSpecService.importCsv(csv);
            return ResponseEntity.ok(Map.of("imported", count, "table", "cargo_spec"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/upsert/cargospecs")
    public ResponseEntity<?> upsertCargoSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                              @RequestBody String csv) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            int count = cargoSpecService.upsertCsv(csv);
            return ResponseEntity.ok(Map.of("upserted", count, "table", "cargo_spec"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
        if (!subscriber) persistRateLimit(ip);
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

    @PostMapping("/compare-cars")
    public ResponseEntity<?> compareCars(@RequestBody Map<String, String> req, HttpServletRequest httpReq,
                                         @RequestHeader(value = "Authorization", required = false) String auth) {
        String car1 = req.getOrDefault("car1", "").trim();
        String car2 = req.getOrDefault("car2", "").trim();
        if (car1.isBlank() || car2.isBlank())
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Ange två bilmodeller"));
        if (car1.equalsIgnoreCase(car2))
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Välj två olika bilar"));

        String ip = getClientIp(httpReq);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        int limit = loggedIn ? MAX_LOGGED_IN_REQUESTS_PER_HOUR : MAX_REQUESTS_PER_HOUR;
        if (!subscriber && isRateLimited(ip, limit)) {
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "error", loggedIn ? "För många förfrågningar. Försök igen om en stund."
                                      : "Du har använt alla gratis förfrågningar. Prenumerera för obegränsat!",
                    "rateLimited", true));
        }
        if (!subscriber) persistRateLimit(ip);

        try {
            List<com.caradvice.model.CarRecommendation> result = groqService.compareSpecific(car1, car2);
            return ResponseEntity.ok(Map.of("success", true, "recommendations", result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
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

    @GetMapping("/cars")
    public ResponseEntity<List<String>> getCars() {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(cargoSpecRepo.findAllCarNames());
        names.addAll(evSpecRepo.findAllCarNames());
        return ResponseEntity.ok(new ArrayList<>(names));
    }

    @GetMapping("/ev-consumption")
    public ResponseEntity<List<Map<String, Object>>> getEvConsumption() {
        List<Map<String, Object>> result = evSpecRepo.findAll().stream()
            .filter(e -> e.getBatteryKwh() != null && e.getRangeKm() != null && e.getRangeKm() > 0)
            .map(e -> {
                double kwhPerMil = Math.round((e.getBatteryKwh() * 10.0 / e.getRangeKm()) * 100.0) / 100.0;
                return Map.<String, Object>of("carName", e.getCarName(), "kwhPerMil", kwhPerMil);
            })
            .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // Publikt: verifierade bensin/diesel/hybrid-förbrukningssiffror (l/mil) — konsumeras av
    // Bilresas bränslekostnadskalkylator, samma mönster som /ev-consumption
    @GetMapping("/ice-consumption")
    public ResponseEntity<List<Map<String, Object>>> getIceConsumption() {
        return ResponseEntity.ok(iceConsumptionService.listForApi());
    }

    // Publikt: expertinsikter för ett bilkort (märke + helst modell måste matcha titeln) —
    // konsumeras av frontend efter att korten renderats, källan visas alltid
    @GetMapping("/insights")
    public ResponseEntity<?> insightsForCar(@RequestParam String car) {
        return ResponseEntity.ok(expertInsightService.findForCarTitle(car));
    }

    // Övervakas av UptimeRobot mot /api/health — nyckelordsövervakning på "OK" larmar
    // även när databasen är tom/onåbar (status blir då DEGRADED trots HTTP 200)
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        long evSpecs = 0;
        try {
            evSpecs = evSpecRepo.count();
        } catch (Exception e) {
            log.warn("Health: kunde inte räkna EV-specs: {}", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", evSpecs > 0 ? "OK" : "DEGRADED");
        out.put("evSpecs", evSpecs);
        try {
            Map<String, Object> run = webInsightScraper.lastRunStatus();
            out.put("lastScrape", run.get("status"));
            out.put("lastScrapeFinishedAt", run.get("finishedAt"));
        } catch (Exception e) {
            out.put("lastScrape", "ERROR");
        }
        return ResponseEntity.ok(out);
    }

    // Tumme upp/ner på en rekommenderad bil — anonym, max 10 röster/min per IP
    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody Map<String, String> req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);
        long now = System.currentTimeMillis();
        Deque<Long> times = feedbackTimestamps.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CHAT_WINDOW_MS) times.pollFirst();
            if (times.size() >= FEEDBACK_RATE_LIMIT)
                return ResponseEntity.status(429).body(Map.of("error", "För många röster. Vänta en stund."));
            times.addLast(now);
        }
        boolean saved = feedbackService.save(req.get("carTitle"), req.get("vote"));
        if (!saved) return ResponseEntity.badRequest().body(Map.of("error", "Ogiltig feedback"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/admin/feedback")
    public ResponseEntity<?> feedbackSummary(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(feedbackService.summary());
    }

    // Admin: radera alla röster för en bil (exakt titel) — städning av test-/skräpröster
    @DeleteMapping("/admin/feedback")
    public ResponseEntity<?> deleteFeedback(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                            @RequestParam String car) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int deleted = feedbackService.deleteByCarTitle(car);
        return ResponseEntity.ok(Map.of("deleted", deleted, "car", car));
    }

    // Övervakas av UptimeRobot: 503 när en konfigurerad Groq-modell avvecklats.
    // Transienta fel mot Groq ger 200 + UNKNOWN för att slippa falsklarm.
    @GetMapping("/health/groq")
    public ResponseEntity<?> groqHealth() {
        if (!groqService.isConfigured())
            return ResponseEntity.status(503).body(Map.of("status", "UNCONFIGURED"));
        GroqService.ModelStatus st = groqService.checkModels();
        if (st.error() != null)
            return ResponseEntity.ok(Map.of("status", "UNKNOWN", "error", st.error()));
        if (!st.missing().isEmpty())
            return ResponseEntity.status(503).body(Map.of("status", "MODEL_MISSING", "missing", st.missing()));
        return ResponseEntity.ok(Map.of("status", "OK", "models", groqService.configuredModels()));
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

    // Admin: lista senaste insikterna (nyast först) för kvalitetsgranskning av nattens skrapning
    @GetMapping("/admin/insights")
    public ResponseEntity<?> listInsights(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @RequestParam(required = false) String expert,
                                          @RequestParam(defaultValue = "50") int limit) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(expertInsightService.listRecent(expert, limit));
    }

    // Admin: normalisera en kategoristavning i insiktstabellen ("småbil" → "smaabil") —
    // buildExpertContext matchar exakt mot frontendens kategorivärden
    @PostMapping("/admin/insights/rename-category")
    public ResponseEntity<?> renameInsightCategory(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                   @RequestParam String from, @RequestParam String to) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int updated = expertInsightService.renameCategory(from, to);
        return ResponseEntity.ok(Map.of("updated", updated, "from", from, "to", to));
    }

    // Admin: radera en enskild skräpinsikt på id (grovstädning per källa görs med ?expert=)
    @DeleteMapping("/admin/insights/{id}")
    public ResponseEntity<?> deleteInsightById(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                               @PathVariable Long id) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        boolean deleted = expertInsightService.deleteById(id);
        if (!deleted) return ResponseEntity.status(404).body(Map.of("error", "Insikt " + id + " finns inte"));
        return ResponseEntity.ok(Map.of("deleted", 1, "id", id));
    }

    @DeleteMapping("/admin/insights")
    public ResponseEntity<?> deleteInsightsByExpert(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                    @RequestParam String expert) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        long before = expertInsightService.countByExpert(expert);
        expertInsightService.deleteByExpert(expert);
        return ResponseEntity.ok(Map.of("deleted", before, "expert", expert));
    }

    // Admin: import expert insights from CSV (car_make,car_model,fuel_type,category,insight,rating)
    // Optional query param: ?expert=Peter+Esse  (default: Bilexpert)
    @PostMapping("/admin/import/insights")
    public ResponseEntity<?> importInsights(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                            @RequestParam(defaultValue = "Bilexpert") String expert,
                                            @RequestBody String csv) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            int count = expertInsightService.importCsv(csv, expert);
            return ResponseEntity.ok(Map.of("imported", count, "table", "expert_insight", "expert", expert));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Admin: import Euro NCAP safety ratings from CSV (car_make,car_model,test_year,stars,adult_pct,child_pct,pedestrian_pct,safety_assist_pct)
    @PostMapping("/admin/import/safety")
    public ResponseEntity<?> importSafety(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @RequestBody String csv) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            int count = safetyRatingService.importCsv(csv);
            return ResponseEntity.ok(Map.of("imported", count, "table", "safety_rating"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdminUnauthorized(String key) {
        return key == null || !adminKey.equals(key);
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
        List<Long> updated = ipRequestLog.compute(ip, (k, times) -> {
            List<Long> list = (times == null) ? new ArrayList<>() : times;
            list.removeIf(t -> t < windowStart);
            list.add(now);
            return list;
        });
        return updated.size() > limit;
    }

    private int remainingSearches(String ip, int limit) {
        List<Long> times = ipRequestLog.getOrDefault(ip, List.of());
        return Math.max(0, limit - times.size());
    }
}
