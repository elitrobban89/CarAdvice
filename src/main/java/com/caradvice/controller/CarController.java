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
    private final com.caradvice.service.EvFactCandidateService evFactCandidateService;
    private final Map<String, List<Long>> ipRequestLog = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    /*
     * TRAPPAN AVSKAFFAD 2026-08-22: gratiskontot finns inte längre som egen nivå.
     *
     * Historiken: fram till 08-16 hade båda leden timvis påfyllning (10 anonymt, 30 inloggad),
     * och anonymnivån kunde därför aldrig ta slut — 10/h är 240/dygn medan en bilköpare gör
     * 5-10 sökningar totalt. Trattmätningen samma dag visade följden: ETT konto i hela
     * databasen, utvecklarens eget. Anonymt sänktes då till 5 per rullande dygn, just för att
     * ge ett skäl att registrera sig.
     *
     * Sex dagar senare säger mätningen att skälet inte behövdes, för det fanns ingen att ge
     * det till: 13 sökningar på sju dygn från EN enda distinkt nyckel, 2 konton (båda
     * utvecklarens) och 0 sparade sökningar. Gratiskontot löste alltså ett problem som inte
     * var flaskhalsen — och kontoflödet var dessutom trasigt fram till 08-20 (rel="noopener"),
     * så nivån hade knappt två dygn med fungerande registrering.
     *
     * Nu gäller SAMMA tak för alla utan prenumeration, inloggad som ej: 30 i timmen. Ett konto
     * skapas hädanefter för att prenumerera, inte för att få en större pott. Prenumeranter
     * räknas inte alls (se subscriber-grenen i getRecommendation).
     *
     * DYGNSTAKET ÄR EN KOSTNADSBROMS, INTE EN KONVERTERINGSSPÄRR. 30/timme per IP är 720
     * sökningar per dygn, och varje sökning är ett Groq-anrop mot en gratisplan med TPM-tak.
     * En besökare når aldrig 100; ett skript gör det på en kvart.
     *
     * Båda fönstren är RULLANDE och inte kalenderdygn, av två skäl: ingen klippkant vid
     * midnatt, och potten frigörs gradvis i stället för allt-eller-inget — vilket spelar roll
     * eftersom nyckeln är en IP. Svenska mobiloperatörer kör CGNAT, så tusentals abonnenter
     * delar utgående IPv4; med ett kalenderdygn hade en handfull användare kunnat låsa ute
     * alla andra bakom samma NAT till midnatt.
     */
    private static final int SEARCHES_PER_HOUR = 30;
    private static final long HOUR_WINDOW_MS = 3_600_000L;
    private static final int SEARCHES_PER_DAY = 100;
    private static final long DAY_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /**
     * Poster äldre än så gallras ur {@link #ipRequestLog}. MÅSTE vara det LÄNGSTA fönstret:
     * tim- och dygnstaket delar samma karta och samma nyckel (IP), så om timfönstret fick
     * gallra hade dygnstaket aldrig kunnat räkna längre än en timme tillbaka.
     */
    private static final long PRUNE_WINDOW_MS = DAY_WINDOW_MS;

    /** Persistensen måste täcka dygnsfönstret — se {@link #cleanupRateLimitLogs}. */
    private static final int RATE_LOG_RETENTION_HOURS = 48;
    private static final int RATE_LOG_RESTORE_HOURS = 24;

    /**
     * Chatten: samma tak för alla utan prenumeration sedan 2026-08-22, inloggad som ej.
     * Var 20/min oinloggad och 50/min inloggad — skillnaden fanns bara för att göra
     * gratiskontot värt att skapa, och den nivån finns inte längre.
     */
    private static final int CHAT_RATE_LIMIT = 50;
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
                         com.caradvice.service.VpicYearCheckService vpicYearCheckService,
                         com.caradvice.service.EvFactCandidateService evFactCandidateService) {
        this.evFactCandidateService = evFactCandidateService;
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

    /**
     * Admin: bilen finns inte att köpa i Sverige än — parkera insikten tills den gör det.
     *
     * <p>Skraparen markerar kommande modeller själv, men rader som visar sig vara oåtkomliga
     * i efterhand hade ingen väg hit: BYD Shark har tio insikter och noll annonser på Blocket,
     * och utan den här endpointen var radering enda alternativet. Verifierar via
     * {@code isUpcoming} eftersom {@code mark} sväljer DB-fel och svarar void — annars hade
     * ett misslyckande sett ut som en lyckad parkering.
     */
    @PostMapping("/admin/insights/{id}/upcoming")
    public ResponseEntity<?> markUpcoming(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @PathVariable Long id) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        if (!expertInsightService.exists(id))
            return ResponseEntity.status(404).body(Map.of("error", "Insikt " + id + " finns inte"));
        upcomingInsightService.mark(id);
        if (!upcomingInsightService.isUpcoming(id))
            return ResponseEntity.status(500).body(Map.of("error", "Kunde inte parkera insikt " + id));
        return ResponseEntity.ok(Map.of("marked", id));
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
    public ResponseEntity<?> recommend(@RequestBody CarPreferences rawPrefs, HttpServletRequest request,
                                       @RequestHeader(value = "Authorization", required = false) String auth) {
        // Gamla WP-snippets postar fortfarande kategorin "ekonomibil" — se canonical().
        CarPreferences prefs = rawPrefs == null ? null : rawPrefs.canonical();
        String ip = getClientIp(request);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        if (!subscriber && isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", limitMessage(ip),
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
            if (!subscriber) body.put("remainingSearches", remainingSearches(ip));
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
        if (!subscriber && isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "error", limitMessage(ip),
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
    public ResponseEntity<?> budgetAlternatives(@RequestBody CarPreferences rawPrefs, HttpServletRequest httpReq,
                                                @RequestHeader(value = "Authorization", required = false) String auth) {
        CarPreferences prefs = rawPrefs == null ? null : rawPrefs.canonical();
        String ip = getClientIp(httpReq);
        boolean subscriber = userService.isActiveSubscriber(auth);
        boolean loggedIn = subscriber || userService.isLoggedIn(auth);
        if (!subscriber && isRateLimited(ip)) {
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
        body.put("remaining", subscriber ? null : remainingSearches(ip));
        // Baren behöver veta VILKEN pott siffran gäller — "kvar i dag" och "kvar denna
        // timme" är olika texter, och klienten kan inte gissa det ur remaining ensam.
        // Sedan trappan avskaffades är timpotten den som gäller i praktiken; dygnstaket är
        // en kostnadsbroms som en besökare aldrig når, så baren ska inte prata om det.
        body.put("limit", subscriber ? null : SEARCHES_PER_HOUR);
        body.put("period", subscriber ? null : "hour");
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
        if (!subscriber && isRateLimited(ip))
            return ResponseEntity.status(429).body(Map.of("error", limitMessage(ip),
                    "rateLimited", true));
        int chatLimit = CHAT_RATE_LIMIT;
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
        if (!subscriber && isRateLimited(ip))
            return ResponseEntity.status(429).build();
        int chatLimitStream = CHAT_RATE_LIMIT;
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
     * Synkar {@code ice_consumption} mot CSV:n — enda vägen att få en RÄTTELSE till drift.
     *
     * <p>Seedningen vid uppstart hoppar över allt så fort tabellen är minst lika stor som CSV:n,
     * och skriver bara INSERT för nycklar som saknas. Ett ändrat variantnamn är en ny nyckel, så
     * en rättelse hade gett två rader för samma bil och en motorlista med båda. Se
     * {@code IceConsumptionService.synkaFranCsv} för spärren som hindrar att ett läsfel tömmer
     * tabellen.
     *
     * <p>Ändras en rad som bär hästkrafter måste modellens generationsårtal räknas om efteråt
     * ({@code DELETE /api/admin/ice-generations/modell?model=...}) — effektprovet läser just den
     * siffran, och ett årtal som sparats utan den är oprövat.
     */
    @PostMapping("/admin/ice-consumption/sync")
    public ResponseEntity<?> synkaIceConsumption(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(iceConsumptionService.synkaFranCsv());
    }
    /**
     * Hela {@code cargo_spec} — bilnamn och uppmätt bagagevolym.
     *
     * <p>Samma instrument som {@link #listaIceGenerations} är för årtalen, och det fanns inte
     * förrän 2026-08-20: admin-API:t hade {@code import} och {@code upsert} för bagagedata plus
     * en RÄKNARE, men inget som visar talen. Just den luckan gjorde cargo-haveriet osynligt —
     * täckningen stod på 602/602/0 medan parsern var död, och en täckning på 100 % kan inte
     * röra sig och larmar därför aldrig. Ett fel som sitter i VÄRDET syns bara om värdet går
     * att läsa.
     *
     * <p>Bara rader med volym listas; namnen utan siffra är arbetslistan och räknas redan av
     * {@code utanVolym} i {@link #cargoCoverage}.
     */
    @GetMapping("/admin/cargo-specs")
    public ResponseEntity<?> listaCargoSpecs(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        var rader = cargoSpecService.allaMedVolym();
        return ResponseEntity.ok(Map.of("total", rader.size(), "cargoSpecs", rader));
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
     * Läser de parkerade missarna modellvis — vilka modeller som fick nej, varför, och när de
     * kommer tillbaka på arbetslistan.
     *
     * <p>Fanns inte förrän 2026-08-19. Dittills svarade {@code GET /api/admin/ice-generations}
     * med <b>antalet</b> missar per orsak, och den siffran går inte att granska: 111 rader stod
     * som {@code vakten-avstod} utan att det gick att se om vakten avstod klokt eller om en hel
     * märkesfamilj föll på samma fel. Att skillnaden spelar roll är mätt, inte befarat — 139
     * parkerade nej såg friska ut i räkningen 2026-08-16 medan chassikoden i titeln i själva
     * verket fällde varenda BMW-, Audi- och Lexus-generation.
     *
     * <p>Rapporten är därför läsning och rättar ingenting. Ser en familj trasig ut är åtgärden
     * densamma som då: laga uppslaget och töm missarna med {@code DELETE} på samma väg, så
     * prövas de om redan i natt.
     *
     * @param orsak valfritt filter, {@code vakten-avstod} eller {@code ej-hittad}
     */
    @GetMapping("/admin/ice-generations/missar")
    public ResponseEntity<?> listaIceGenerationMissar(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(required = false) String orsak) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        List<Map<String, Object>> rader = iceGenerationService.listaMissar(orsak);
        // Totalen står bredvid det filtrerade antalet med flit: utan den läses "27 rader" som
        // hela sanningen även när anropet bar ?orsak=ej-hittad.
        return ResponseEntity.ok(Map.of(
                "antal", rader.size(),
                "totalt", iceGenerationService.antalMissar(),
                "perOrsak", iceGenerationService.missarPerOrsak(),
                "missar", rader));
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

    /**
     * Vad prompterna faktiskt kostar hos Groq, per modell och per anrop.
     *
     * <p>Fanns inte förrän 2026-08-22, och utan den gick tokenbudgeten inte att tuna: Groq mäter
     * {@code prompt + reserverade max_tokens} mot minuttaket 8 000, men {@code usage}-fältet i
     * svaret lästes aldrig. Det enda som avslöjade promptstorleken var 413-felen — alltså först
     * när taket redan sprängts.
     *
     * <p>{@code anropSomRymsPerMinut} är svaret på "varför går det inte att söka flera gånger i
     * rad": är prompten 4 700 och 3 000 reserverade blir talet 1.
     */
    @GetMapping("/admin/token-usage")
    public ResponseEntity<?> tokenUsage(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(groqService.tokenAnvandning());
    }

    /**
     * Fyndlistan: vilka av nattens nya insikter som duger som karusellrad i Elbilsladdning.
     *
     * <p>Rådgivande på samma sätt som vPIC-vakten — den skriver ingenting och publicerar
     * ingenting. Karusellen i {@code ev-app.js} är handkurerad, och raderna härifrån är
     * AI-extraherad text som ingen läst mot källan; se {@link com.caradvice.service.EvFactCandidateService}
     * för varför det steget inte går att automatisera bort.
     *
     * @param efterId senaste redan granskade insikts-id. Skicka tillbaka {@code hogstaId} från
     *                förra körningen — det är hela dubblettskyddet mot karusellen. Utan värde
     *                bedöms de 60 nyaste raderna.
     */
    @GetMapping("/admin/ev-fact-candidates")
    public ResponseEntity<?> evFactCandidates(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                              @RequestParam(required = false) Long efterId,
                                              @RequestParam(defaultValue = "25") int limit) {
        if (isAdminUnauthorized(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(evFactCandidateService.hittaKandidater(efterId, Math.max(1, Math.min(limit, 100))));
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

    /** Antal poster inom ett fönster, utan att förbruka något. */
    private long anvandaInom(String ip, long fonsterMs) {
        long windowStart = System.currentTimeMillis() - fonsterMs;
        return ipRequestLog.getOrDefault(ip, List.of()).stream().filter(t -> t >= windowStart).count();
    }

    /**
     * Gallringen och räkningen använder OLIKA fönster med flit: listan rensas mot det längsta
     * ({@link #PRUNE_WINDOW_MS}) så dygnstaket har kvar sin historik, medan timtaket bara ser
     * den senaste timmen.
     *
     * <p>Båda taken prövas på samma lista. Timtaket är det som en verklig besökare möter;
     * dygnstaket är kostnadsbromsen och slår bara mot något som beter sig som ett skript.
     */
    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long pruneBefore = now - PRUNE_WINDOW_MS;
        ipRequestLog.compute(ip, (k, times) -> {
            List<Long> list = (times == null) ? new ArrayList<>() : times;
            list.removeIf(t -> t < pruneBefore);
            list.add(now);
            return list;
        });
        return anvandaInom(ip, HOUR_WINDOW_MS) > SEARCHES_PER_HOUR
                || anvandaInom(ip, DAY_WINDOW_MS) > SEARCHES_PER_DAY;
    }

    /**
     * Kvarvarande sökningar utan att förbruka någon — det MINSTA av de två taken, för det är
     * det som faktiskt stoppar nästa sökning. Att bara visa timpotten hade lovat 30 sökningar
     * åt någon som har två kvar på dygnet.
     */
    private int remainingSearches(String ip) {
        long kvarTimme = SEARCHES_PER_HOUR - anvandaInom(ip, HOUR_WINDOW_MS);
        long kvarDygn = SEARCHES_PER_DAY - anvandaInom(ip, DAY_WINDOW_MS);
        return (int) Math.max(0, Math.min(kvarTimme, kvarDygn));
    }

    /**
     * Väggen ska peka på vad som faktiskt tog slut. Timtaket och dygnstaket är två helt olika
     * besked: det ena betyder "vänta en stund", det andra "det här ser ut som en robot".
     */
    private String limitMessage(String ip) {
        if (anvandaInom(ip, DAY_WINDOW_MS) > SEARCHES_PER_DAY) {
            return "Du har använt " + SEARCHES_PER_DAY + " sökningar det senaste dygnet."
                    + " Som prenumerant är sökningarna obegränsade.";
        }
        return "Du har använt dina " + SEARCHES_PER_HOUR + " sökningar denna timme."
                + " Försök igen om en stund — som prenumerant är sökningarna obegränsade.";
    }
}
