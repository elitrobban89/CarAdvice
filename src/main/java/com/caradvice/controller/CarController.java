package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.RateLimitLog;
import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.RateLimitLogRepository;
import com.caradvice.scraper.CargoSpecSyncService;
import com.caradvice.scraper.EvDatabaseScraperService;
import com.caradvice.scraper.JobStatusService;
import com.caradvice.scraper.MobilityStatsSyncService;
import com.caradvice.scraper.WebInsightScraperService;
import com.caradvice.service.CargoSpecService;
import com.caradvice.service.CarVideoService;
import com.caradvice.service.EvSpecService;
import com.caradvice.service.ExpertInsightService;
import com.caradvice.service.FeedbackService;
import com.caradvice.service.GroqService;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.IceGenerationService;
import com.caradvice.service.NewCarPriceService;
import com.caradvice.service.SafetyRatingService;
import com.caradvice.service.UpcomingInsightService;
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
import java.time.Instant;
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
    private final CarVideoService carVideoService;
    private final MobilityStatsSyncService mobilityStatsSyncService;
    private final EvSpecService evSpecService;
    private final IceGenerationService iceGenerationService;
    private final com.caradvice.service.VpicYearCheckService vpicYearCheckService;
    private final com.caradvice.service.EvPowerService evPowerService;
    private final JobStatusService jobStatus;
    private final UpcomingInsightService upcomingInsightService;
    private final com.caradvice.service.UsageStatsService usageStatsService;
    private final Map<String, List<Long>> ipRequestLog = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    /*
     * Gratisnivån hade fram till 2026-08-16 timvis påfyllning i BÅDA leden (10 anonymt,
     * 30 inloggad). Anonymnivån kunde därmed aldrig ta slut — 10/h är 240/dygn, och en
     * bilköpare gör 5-10 sökningar totalt. Första trattmätningen samma dag visade följden:
     * ETT konto i hela databasen, och det var utvecklarens eget. Ingen hade någonsin fått
     * en anledning att registrera sig, eftersom anonymnivån redan räckte hela vägen.
     *
     * Anonymt är nu 5 per RULLANDE dygn. Rullande och inte kalenderdygn av två skäl:
     * ingen klippkant vid midnatt, och potten frigörs gradvis i stället för allt-eller-inget
     * — vilket spelar roll eftersom nyckeln är en IP. Svenska mobiloperatörer kör CGNAT, så
     * tusentals abonnenter delar utgående IPv4; med ett kalenderdygn hade en handfull
     * användare kunnat låsa ute alla andra bakom samma NAT till midnatt.
     *
     * Inloggad ligger kvar på 30/timme. Den stora skillnaden mot 5/dygn ÄR poängen: det är
     * det enda som gör ett gratiskonto värt att skapa.
     */
    private static final int ANON_SEARCHES_PER_DAY = 5;
    private static final long ANON_WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_LOGGED_IN_REQUESTS_PER_HOUR = 30;
    private static final long LOGGED_IN_WINDOW_MS = 3_600_000L;

    /**
     * Poster äldre än så gallras ur {@link #ipRequestLog}. MÅSTE vara det LÄNGSTA fönstret:
     * anonym och inloggad delar samma karta och samma nyckel (IP), så om den inloggades
     * timfönster fick gallra hade en utloggning gett en tömd dygnspott på köpet.
     */
    private static final long PRUNE_WINDOW_MS = ANON_WINDOW_MS;

    /** Persistensen måste täcka dygnsfönstret — se {@link #cleanupRateLimitLogs}. */
    private static final int RATE_LOG_RETENTION_HOURS = 48;
    private static final int RATE_LOG_RESTORE_HOURS = 24;

    private static final int CHAT_RATE_LIMIT = 20;
    private static final int CHAT_LOGGED_IN_RATE_LIMIT = 50;
    private static final long CHAT_WINDOW_MS = 60_000L;
    private final ConcurrentHashMap<String, Deque<Long>> chatTimestamps = new ConcurrentHashMap<>();

    private static final int FEEDBACK_RATE_LIMIT = 10;
    private final ConcurrentHashMap<String, Deque<Long>> feedbackTimestamps = new ConcurrentHashMap<>();

    @Value("${admin.key}")
    private String adminKey;

    // Fylls av Maven vid bygget (@project.version@) respektive av Render vid deploy
    // (RENDER_GIT_COMMIT/RENDER_GIT_BRANCH). Lokalt är de två sistnämnda tomma.
    @Value("${app.version:unknown}")
    private String appVersion;

    @Value("${app.commit:}")
    private String appCommit;

    @Value("${app.branch:}")
    private String appBranch;

    private final Instant startedAt = Instant.now();

    public CarController(GroqService groqService, ExpertInsightService expertInsightService,
                         SafetyRatingService safetyRatingService, EvDatabaseScraperService evScraper,
                         CargoSpecSyncService cargoSpecSyncService, CargoSpecService cargoSpecService,
                         UserService userService, RateLimitLogRepository rateLimitLogRepo,
                         CargoSpecRepository cargoSpecRepo, EvSpecRepository evSpecRepo,
                         FeedbackService feedbackService, WebInsightScraperService webInsightScraper,
                         IceConsumptionService iceConsumptionService, CarVideoService carVideoService,
                         MobilityStatsSyncService mobilityStatsSyncService,
                         EvSpecService evSpecService, JobStatusService jobStatus,
                         UpcomingInsightService upcomingInsightService,
                         IceGenerationService iceGenerationService,
                         com.caradvice.service.EvPowerService evPowerService,
                         com.caradvice.service.UsageStatsService usageStatsService,
                         com.caradvice.service.VpicYearCheckService vpicYearCheckService) {
        this.vpicYearCheckService = vpicYearCheckService;
        this.usageStatsService = usageStatsService;
        this.iceGenerationService = iceGenerationService;
        this.evPowerService = evPowerService;
        this.jobStatus = jobStatus;
        this.upcomingInsightService = upcomingInsightService;
        this.evSpecService = evSpecService;
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
        this.carVideoService = carVideoService;
        this.mobilityStatsSyncService = mobilityStatsSyncService;
    }

    @PostConstruct
    public void initRateLimits() {
        try {
            // Måste täcka dygnsfönstret, annars ger varje omdeploy alla en ny full pott
            // — och Render deployar ofta.
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(RATE_LOG_RESTORE_HOURS);
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
            rateLimitLogRepo.deleteByRequestTimeBefore(
                    LocalDateTime.now(ZoneOffset.UTC).minusHours(RATE_LOG_RETENTION_HOURS));
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
        Thread.ofVirtual().start(() ->
                jobStatus.track(JobStatusService.JOB_EV_SPECS, evScraper::syncFromEvDatabase));
        return ResponseEntity.accepted().body(Map.of("status", "sync started — check server logs for result"));
    }

    @PostMapping("/admin/sync-cargo-specs")
    public ResponseEntity<?> syncCargoSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Thread.ofVirtual().start(() ->
                jobStatus.track(JobStatusService.JOB_CARGO_SPECS, cargoSpecSyncService::syncCarNames));
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

    // Admin: hämta senaste Mobility Sweden-månadsrapporten (xlsx) och ersätt
    // statistikinsikterna under källnamnet "Mobility Sweden månadsläget".
    // Körs synkront (~5 s) så svaret visar resultatet direkt.
    @PostMapping("/admin/sync-mobility-stats")
    public ResponseEntity<?> syncMobilityStats(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Map<String, Object> result = mobilityStatsSyncService.syncNow();
        return "OK".equals(result.get("status"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(502).body(result);
    }

    // Admin: insikter som väntar på att bilen ska bli köpbar i Sverige. De ligger i
    // expert_insight men döljs för prompter och bilkort tills de släpps.
    @GetMapping("/admin/insights/upcoming")
    public ResponseEntity<?> upcomingInsights(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        List<Map<String, Object>> rows = upcomingInsightService.list();
        return ResponseEntity.ok(Map.of("count", rows.size(), "insights", rows));
    }

    // Admin: bilen går att köpa nu — insikten blir synlig som vilken annan som helst.
    @DeleteMapping("/admin/insights/{id}/upcoming")
    public ResponseEntity<?> releaseUpcoming(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                             @PathVariable Long id) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return upcomingInsightService.release(id)
                ? ResponseEntity.ok(Map.of("released", id))
                : ResponseEntity.status(404).body(Map.of("error", "Insikten var inte markerad som kommande"));
    }

    /**
     * Trattmätning: konton, prenumeranter, sparade sökningar och sökvolym.
     *
     * <p>Fanns inte förrän 2026-08-16, och utan den gick betalmodellen inte att bedöma —
     * Stripe visar bara dem som nått kassan, aldrig dem som registrerat sig och avstått.
     * Se {@link com.caradvice.service.UsageStatsService} för vad varje siffra betyder.
     */
    @GetMapping("/admin/usage")
    public ResponseEntity<?> usage(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            return ResponseEntity.ok(usageStatsService.snapshot());
        } catch (Exception e) {
            log.warn("Kunde inte läsa användningsstatistik: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Admin: senaste körningens status för de schemalagda jobben.
    // Toppnivån är insiktsscrapen (oförändrat svar sedan tidigare); "jobs" listar alla fyra.
    @GetMapping("/admin/scrape-status")
    public ResponseEntity<?> scrapeStatus(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Map<String, Object> out = new LinkedHashMap<>(webInsightScraper.lastRunStatus());
        try {
            out.put("jobs", jobStatus.allJobs());
        } catch (Exception e) {
            log.warn("Kunde inte läsa jobbstatus: {}", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    // Admin: seed already-processed keys (URL:er/omdömes-refs) so the scraper skips them — text body, one key per line
    @PostMapping("/admin/import/seen-keys")
    public ResponseEntity<?> importSeenKeys(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                            @RequestBody String body) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int added = webInsightScraper.seedSeen(List.of(body.split("\\R")));
        return ResponseEntity.ok(Map.of("added", added, "table", "web_insight_seen"));
    }

    // Admin: glöm en processad nyckel så nästa nattkörning läser om artikeln. Motsatsen till
    // import/seen-keys — en artikel som tappats på rate limit (eller lästs med en gammal prompt)
    // kom annars aldrig tillbaka. prefix=true tar hela serien som börjar på värdet.
    @DeleteMapping("/admin/seen-keys")
    public ResponseEntity<?> forgetSeenKey(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                           @RequestParam("key") String seenKey,
                                           @RequestParam(value = "prefix", defaultValue = "false") boolean prefix) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            int removed = webInsightScraper.forgetSeen(seenKey, prefix);
            return ResponseEntity.ok(Map.of("removed", removed, "table", "web_insight_seen"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
        if (!subscriber && isRateLimited(ip, loggedIn)) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", limitMessage(loggedIn),
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
            if (!subscriber) body.put("remainingSearches", remainingSearches(ip, loggedIn));
            if (result.fromCache()) {
                body.put("cached", true);
                body.put("cachedAgeMinutes", result.cacheAgeSeconds() / 60);
            }
            // Satt bara när kriterierna inte gick ihop och korten därför ligger över budget
            if (result.budgetShortfallFromKr() != null) {
                body.put("budgetShortfallFromKr", result.budgetShortfallFromKr());
            } else if (result.recommendations().size() < 3) {
                // Färre än tre kort UTAN budgetdom betyder att regelvakterna fällde bilar som
                // inte höll kraven — korten som blev kvar är alltså rätt, men användaren ser
                // bara ett tunt svar. Bara det ena beskedet i taget: budgetbanderollen säger
                // redan sitt, och två rutor med överlappande budskap läser som ett renderingsfel.
                body.put("narrowCriteria", Map.of(
                        "kvar", result.recommendations().size(),
                        "krav", GroqService.activeConstraints(prefs)));
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
        if (!subscriber && isRateLimited(ip, loggedIn)) {
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "error", limitMessage(loggedIn),
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

    // Publikt: vad räcker budgeten till om ålderskravet lyfts? Anropas lazy av frontend
    // BARA när rekommendationssvaret bar budgetShortfallFromKr — alltså när kriterierna inte
    // gick ihop. Egen endpoint för att inte lägga ett tredje Groq-anrop plus Blocket-uppslag
    // i /api/recommend, som redan har 35 s klienttimeout. Samma timpott som sök och chatt.
    @PostMapping("/budget-alternatives")
    public ResponseEntity<?> budgetAlternatives(@RequestBody CarPreferences prefs, HttpServletRequest httpReq,
                                                @RequestHeader(value = "Authorization", required = false) String auth) {
        String ip = getClientIp(httpReq);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        if (!subscriber && isRateLimited(ip, loggedIn)) {
            return ResponseEntity.status(429).body(Map.of("success", false, "rateLimited", true));
        }
        if (!subscriber) persistRateLimit(ip);
        try {
            return ResponseEntity.ok(Map.of("success", true,
                    "alternatives", groqService.findBudgetAlternatives(prefs)));
        } catch (Exception e) {
            log.warn("Budgetalternativ misslyckades: {}", e.getMessage());
            // Banderollen står kvar med sin grundtext — raden är en förbättring, inte ett krav
            return ResponseEntity.ok(Map.of("success", false, "alternatives", List.of()));
        }
    }

    /** Peek på timpotten UTAN att förbruka en sökning — frontenden synkar demoräknaren
     *  efter en chattfråga (chatt + rekommendationer delar samma pott, se /chat). */
    @GetMapping("/search-status")
    public ResponseEntity<?> searchStatus(HttpServletRequest request,
                                          @RequestHeader(value = "Authorization", required = false) String auth) {
        String ip = getClientIp(request);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        Map<String, Object> body = new HashMap<>();
        body.put("subscriber", subscriber);
        body.put("loggedIn", loggedIn);
        body.put("remaining", subscriber ? null : remainingSearches(ip, loggedIn));
        // Baren behöver veta VILKEN pott siffran gäller — "kvar i dag" och "kvar denna
        // timme" är olika texter, och klienten kan inte gissa det ur remaining ensam.
        body.put("limit", subscriber ? null : limitFor(loggedIn));
        body.put("period", subscriber ? null : (loggedIn ? "hour" : "day"));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> req, HttpServletRequest httpReq,
                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        String ip = getClientIp(httpReq);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        // Kombinerad pott: chattfrågor räknas mot SAMMA pott som rekommendationer och
        // jämförelser (5/dygn anonymt, 30/h inloggad) — en fråga i chatten drar en sökning.
        if (!subscriber && isRateLimited(ip, loggedIn))
            return ResponseEntity.status(429).body(Map.of("error", limitMessage(loggedIn),
                    "rateLimited", true));
        int chatLimit = loggedIn ? CHAT_LOGGED_IN_RATE_LIMIT : CHAT_RATE_LIMIT;
        long now = System.currentTimeMillis();
        Deque<Long> times = chatTimestamps.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CHAT_WINDOW_MS) times.pollFirst();
            if (!subscriber && times.size() >= chatLimit)
                return ResponseEntity.status(429).body(Map.of("error", "För många frågor — vänta en minut och försök igen.", "rateLimited", true));
            times.addLast(now);
        }
        if (!subscriber) persistRateLimit(ip); // dra en sökning ur timpotten
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
        // Kombinerad pott: chattfrågor drar en sökning ur samma pott som sök/jämförelse.
        if (!subscriber && isRateLimited(ip, loggedInStream))
            return ResponseEntity.status(429).build();
        int chatLimitStream = loggedInStream ? CHAT_LOGGED_IN_RATE_LIMIT : CHAT_RATE_LIMIT;
        long now = System.currentTimeMillis();
        Deque<Long> times = chatTimestamps.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CHAT_WINDOW_MS) times.pollFirst();
            if (!subscriber && times.size() >= chatLimitStream)
                return ResponseEntity.status(429).build();
            times.addLast(now);
        }
        if (!subscriber) persistRateLimit(ip); // dra en sökning ur timpotten
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

    // Publikt: lätta live-antal för uppstartssplashen ("aktuellt värde"). Alla räkningar är
    // best-effort — en enskild tabell som fallerar ska inte fälla hela svaret (splashen
    // klampar mot golvvärden och faller tillbaka på källnamn). Ingen rate-limit, cachas i
    // webbläsaren.
    //   models   = distinkta "märke modell" över cargo_spec ∪ ev_spec ∪ ice_consumption
    //   variants = totala variantrader (cargo + elbils- + förbrukningsvarianter)
    //   insights = antal expertinsikter
    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        TreeSet<String> models = new TreeSet<>();
        long variants = 0, insights = 0;
        try { models.addAll(cargoSpecRepo.findAllCarNames()); variants += cargoSpecRepo.count(); }
        catch (Exception e) { log.warn("Stats: cargo_spec: {}", e.getMessage()); }
        try { models.addAll(evSpecRepo.findAllCarNames()); variants += evSpecRepo.count(); }
        catch (Exception e) { log.warn("Stats: ev_spec: {}", e.getMessage()); }
        try { models.addAll(iceConsumptionService.allModelNames()); variants += iceConsumptionService.findAll().size(); }
        catch (Exception e) { log.warn("Stats: ice_consumption: {}", e.getMessage()); }
        try { insights = expertInsightService.count(); }
        catch (Exception e) { log.warn("Stats: insikter: {}", e.getMessage()); }
        out.put("models", models.size());
        out.put("variants", variants);
        out.put("insights", insights);
        return ResponseEntity.ok(out);
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

    // Publikt: bilrecension på YouTube för ett bilkort. Hämtas lazy av frontend efter att
    // korten renderats — YouTube-uppslaget ska aldrig ligga i rekommendationssvarets väg,
    // och en bil utan recension ska bara sakna raden. Tomt objekt när inget finns.
    @GetMapping("/car-video")
    public ResponseEntity<?> carVideo(@RequestParam String car) {
        return ResponseEntity.ok(carVideoService.findForCarTitle(car));
    }

    // Admin: glöm cachad YouTube-video så nästa visning slår upp på nytt — efter ändrad
    // sökning/rankning, eller när en bil fått en video som inte håller måttet.
    @DeleteMapping("/admin/car-video")
    public ResponseEntity<?> forgetCarVideo(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                            @RequestParam(required = false) String car,
                                            @RequestParam(defaultValue = "false") boolean all) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        if (!all && (car == null || car.isBlank()))
            return ResponseEntity.badRequest().body(Map.of("error", "ange car=<bil> eller all=true"));
        int deleted = all ? carVideoService.forgetAll() : carVideoService.forget(car);
        return ResponseEntity.ok(Map.of("deleted", deleted, "table", "car_video"));
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
        out.put("commit", shortCommit());
        return ResponseEntity.ok(out);
    }

    // Kort commit-hash som matchar git log --oneline; tom lokalt → "unknown"
    private String shortCommit() {
        return appCommit.isBlank() ? "unknown" : appCommit.substring(0, Math.min(7, appCommit.length()));
    }

    // Vilken kod som faktiskt kör — svarar på "hann deployen ut?" utan Render-dashboarden.
    // commit/branch kommer från Renders miljövariabler; kör man lokalt blir de "unknown"/"local".
    // uptimeSeconds avslöjar dessutom spindown: låg siffra = instansen har nyss startat om.
    @GetMapping("/version")
    public ResponseEntity<?> version() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", appVersion);
        out.put("commit", shortCommit());
        out.put("commitFull", appCommit.isBlank() ? "unknown" : appCommit);
        out.put("branch", appBranch.isBlank() ? "local" : appBranch);
        out.put("startedAt", startedAt.toString());
        out.put("uptimeSeconds", Instant.now().getEpochSecond() - startedAt.getEpochSecond());
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

    // Admin: hela ev_spec med samma härledda fält som bilkortet visar — för granskning av
    // datakvalitet (prisvärdhetsetikett, saknade priser, orimliga räckvidder). Läsning bakom
    // admin-nyckeln eftersom det är hela databasen i ett svar, inte en enskild bil.
    @GetMapping("/admin/ev-specs")
    public ResponseEntity<?> listEvSpecs(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                         @RequestParam(defaultValue = "15000") int kmPerYear) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(evSpecService.listAllWithValueLabel(kmPerYear));
    }

    // Admin: hur många bilnamn som har uppmätt bagagevolym. Utan den här går nattens ifyllning
    // bara att avläsa i Render-loggen, och bagagevaktens "fäll bara på positivt bevis" vilar
    // på just den siffran.
    /**
     * Bagagetäckningen, plus {@code iceGenerations} — antal modeller som fått sitt
     * generationsår ifyllt av samma 03:00-jobb.
     *
     * <p>Talet ligger här och inte bara i loggen av ett konkret skäl: nattkontrollrutinerna kan
     * inte läsa Render-loggen, så en siffra som bara finns där går aldrig att bevaka. Samma
     * lärdom som kollisionstalet i EV-synken gav — det står bara i loggens {@code Sync
     * complete}-rad och har därför aldrig kunnat följas.
     *
     * <p>Vakten i {@code IceConsumptionService.engineOptionsForTitle} är verkningslös så länge
     * talet är 0: okänt generationsår betyder "ingen åsikt" och listan visas som förut.
     */
    @GetMapping("/admin/cargo-coverage")
    public ResponseEntity<?> cargoCoverage(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Map<String, Object> ut = new LinkedHashMap<>(cargoSpecService.coverage());
        try { ut.put("iceGenerations", iceGenerationService.antal()); }
        catch (Exception e) { log.warn("cargo-coverage: ice_generation kunde inte räknas: {}", e.getMessage()); }
        // evPowers fylls av 02:00-synken, inte 03:00-jobbet, men ligger här av samma skäl som
        // iceGenerations: en siffra som bara står i loggen går aldrig att bevaka.
        try { ut.put("evPowers", evPowerService.antal()); }
        catch (Exception e) { log.warn("cargo-coverage: ev_power kunde inte räknas: {}", e.getMessage()); }
        return ResponseEntity.ok(ut);
    }

    /**
     * Hela {@code ice_generation} — modellnamn och generationens startår.
     *
     * <p>Räknaren i {@link #cargoCoverage} visar bara HUR MÅNGA rader som fyllts, aldrig VILKA
     * årtal de bär, och det är årtalen felet sitter i: raderna 2026-08-14 bar faceliftens år
     * (Golf VIII som 2024 i stället för 2020) och räknaren stod på 44 hela tiden utan att
     * avslöja något. Efter ombyggnaden 2026-08-15 gick den till 32, men om årtalen blivit rätt
     * gick bara att avgöra med ett skarpt sök — och varje sådant drar ur den fria sökkvoten
     * (10/h). Samma skäl som räknaren själv finns: en siffra som inte går att läsa går inte
     * att bevaka.
     */
    @GetMapping("/admin/ice-generations")
    public ResponseEntity<?> listaIceGenerations(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        List<Map<String, Object>> rader = iceGenerationService.lista();
        // missar med i svaret: total + missar är hur långt ifyllningen kommit av 310 modeller,
        // och står summan still natt efter natt är arbetslistan slut — inte jobbet trasigt.
        // missarPerOrsak skiljer de två nej som förut låg i samma siffra: "ej-hittad" är ett
        // dött uppslag, "vakten-avstod" är ett medvetet avstående. Domineras svaret av det
        // första är det uppslaget som är trasigt, inte auto-data som saknar bilarna.
        return ResponseEntity.ok(Map.of(
                "total", rader.size(),
                "missar", iceGenerationService.antalMissar(),
                "missarPerOrsak", iceGenerationService.missarPerOrsak(),
                "generations", rader));
    }

    /**
     * Glömmer de parkerade missarna så nattjobbet prövar om dem redan i natt.
     *
     * <p>Hör ihop med varje rättning i auto-data-uppslaget: en miss är ett nej på frågan vi
     * ställde, och rättar vi frågan är gamla nej inte längre svar. Utan den här överlever ett
     * felaktigt nej sin rättning med upp till {@code MISS_GILTIG_DAGAR} dagar.
     */
    @DeleteMapping("/admin/ice-generations/missar")
    public ResponseEntity<?> rensaIceGenerationMissar(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int borttagna = iceGenerationService.rensaMissar();
        log.info("ice_generation_miss tömd på begäran — {} rader borta, 03:00-jobbet prövar om dem", borttagna);
        return ResponseEntity.ok(Map.of("deleted", borttagna));
    }

    /**
     * Granskar årtalen i {@code ice_generation} mot NHTSA:s öppna register vPIC.
     *
     * <p>Svarar på frågan räknaren aldrig kunde: <b>är värdena rimliga?</b> Felet 2026-08-18 —
     * "Volvo s90" daterad till 1997 fast motorlistan beskriver 2016 års bil — hittades genom att
     * en människa ögnade 172 rader. vPIC ser samma sak automatiskt: den har S90 i dag men saknar
     * den 2000 och 2003, alltså ligger ett generationsbyte mellan vårt årtal och nu.
     *
     * <p><b>Rapporten är rådgivande och skriver aldrig något.</b> Amerikanska modellår ligger ett
     * år före de europeiska, och täckningen är amerikansk — Škoda, Renault, Dacia och Cupra har
     * noll modeller i vPIC. Därför är {@code INGEN_DATA} en egen post i räkningen och aldrig ett
     * godkännande; den är den vanligaste utgången, och det är väntat.
     *
     * <p>Kör den efter en rättning i uppslaget, eller när generationslistan vuxit. Den är
     * medvetet ingen nattlig kontroll: den hade larmat varje morgon på de ~100 modeller vPIC
     * inte känner till, och ett larm som är falskt varje dag slutar läsas.
     *
     * @param make valfritt märkesfilter ("Volvo") — utan det granskas alla rader inom anropstaket
     */
    @GetMapping("/admin/ice-generations/vpic-check")
    public ResponseEntity<?> vpicArtalskoll(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(required = false) String make) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        var rapport = vpicYearCheckService.granska(
                iceGenerationService.lista(), make, iceConsumptionService::delaModellnamn);
        return ResponseEntity.ok(Map.of(
                "kontrollerade", rapport.kontrollerade(),
                // hoppade > 0 betyder att anropstaket tog slut, inte att raderna var friska —
                // en tyst kapning hade gjort en halv granskning omöjlig att skilja från en hel.
                "hoppade", rapport.hoppade(),
                "vpicAnrop", rapport.anrop(),
                "perStatus", rapport.perStatus(),
                "avvikelser", rapport.avvikelser()));
    }

    /**
     * Tar bort EN modells årtal så att 03:00-jobbet fyller om just den i natt.
     *
     * <p>Finns därför att felen kommer modellvis. 2026-08-18 bar två av 172 rader fel generation
     * — "Volvo s90" 1997 och "Volvo v90" 1996, den ombadgade 960:an i stället för 2016 års bil —
     * medan de övriga 170 var riktiga. Enda vägen tillbaka var {@code DELETE
     * /api/admin/ice-generations}, som tömmer <b>hela</b> tabellen OCH alla missar: 310 modeller
     * att beta om på 150 försök per natt, alltså tre nätter utan generationsdata för att laga två
     * rader. Arbetslistan hoppar över varje modell som redan har ett årtal, så utan en riktad
     * radering blir ett fel värde permanent.
     *
     * <p>Missen tas bort i samma svep ({@code spara} gör det åt andra hållet): en modell som
     * ligger som miss hoppas över lika säkert som en som har ett årtal, och då hade raderingen
     * inte gett något nytt försök.
     *
     * @param model modellnamnet exakt som det står i {@code GET /api/admin/ice-generations}
     */
    @DeleteMapping("/admin/ice-generations/modell")
    public ResponseEntity<?> raderaIceGeneration(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam String model) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int borttagna = iceGenerationService.radera(model);
        log.info("ice_generation: {} borttagen på begäran ({} rader), 03:00-jobbet fyller om den",
                model, borttagna);
        return ResponseEntity.ok(Map.of("model", model, "deleted", borttagna));
    }

    /**
     * Tömmer {@code ice_generation} så att 03:00-jobbet fyller om den från grunden.
     *
     * <p>Gäller felet bara enstaka modeller — använd {@code DELETE
     * /api/admin/ice-generations/modell?model=...} i stället. Den här kostar tre nätter.
     *
     * <p>Finns för att raderna 2026-08-14 visade sig bära <b>faceliftens</b> årtal i stället för
     * generationens — Golf VIII stod som 2024 fast den kom 2020 — och arbetslistan hoppar över
     * varje modell som redan har ett årtal, så ett fel värde hade blivit permanent. Tabellen är
     * härledd data som kostar ett par nätter att bygga om, aldrig något användaren matat in.
     *
     * <p>Behåll endpointen: samma sak händer nästa gång sajtens generationsindelning ändras.
     */
    @DeleteMapping("/admin/ice-generations")
    public ResponseEntity<?> rensaIceGenerations(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        int borttagna = iceGenerationService.rensa();
        log.info("ice_generation tömd på begäran — {} rader borta, 03:00-jobbet fyller om", borttagna);
        return ResponseEntity.ok(Map.of("deleted", borttagna));
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

    // Admin: rätta fält på en enskild insikt (kategori, modell, text, rating) —
    // tidigare var DELETE enda alternativet vid t.ex. felkategorisering
    @PatchMapping("/admin/insights/{id}")
    public ResponseEntity<?> patchInsight(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @PathVariable Long id,
                                          @RequestBody Map<String, Object> fields) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        try {
            return expertInsightService.updateInsight(id, fields)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Insikt " + id + " finns inte")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    private int limitFor(boolean loggedIn) {
        return loggedIn ? MAX_LOGGED_IN_REQUESTS_PER_HOUR : ANON_SEARCHES_PER_DAY;
    }

    private long windowFor(boolean loggedIn) {
        return loggedIn ? LOGGED_IN_WINDOW_MS : ANON_WINDOW_MS;
    }

    /**
     * Gallringen och räkningen använder OLIKA fönster med flit: listan rensas mot det
     * längsta ({@link #PRUNE_WINDOW_MS}) så ingen historik går förlorad för den andra
     * nivån, medan bara posterna inom anroparens eget fönster räknas mot gränsen.
     */
    private boolean isRateLimited(String ip, boolean loggedIn) {
        long now = System.currentTimeMillis();
        long pruneBefore = now - PRUNE_WINDOW_MS;
        List<Long> updated = ipRequestLog.compute(ip, (k, times) -> {
            List<Long> list = (times == null) ? new ArrayList<>() : times;
            list.removeIf(t -> t < pruneBefore);
            list.add(now);
            return list;
        });
        long windowStart = now - windowFor(loggedIn);
        long used = updated.stream().filter(t -> t >= windowStart).count();
        return used > limitFor(loggedIn);
    }

    /**
     * Kvarvarande sökningar utan att förbruka någon. Fönstret filtreras om här — förut
     * räknades hela listan, vilket var ofarligt när båda nivåerna delade ett timfönster
     * men blir fel så fort de har olika: en anonym besökares dygnsposter hade annars
     * dragits av från en inloggads timpott.
     */
    private int remainingSearches(String ip, boolean loggedIn) {
        long windowStart = System.currentTimeMillis() - windowFor(loggedIn);
        List<Long> times = ipRequestLog.getOrDefault(ip, List.of());
        long used = times.stream().filter(t -> t >= windowStart).count();
        return (int) Math.max(0, limitFor(loggedIn) - used);
    }

    /** Väggen ska peka på nästa steg i trappan, inte bara konstatera att det tog slut. */
    private String limitMessage(boolean loggedIn) {
        return loggedIn
                ? "Du har använt dina " + MAX_LOGGED_IN_REQUESTS_PER_HOUR
                  + " sökningar denna timme. Försök igen om en stund."
                : "Du har använt dina " + ANON_SEARCHES_PER_DAY
                  + " gratis sökningar i dag. Skapa ett gratiskonto för "
                  + MAX_LOGGED_IN_REQUESTS_PER_HOUR + " sökningar i timmen!";
    }
}
