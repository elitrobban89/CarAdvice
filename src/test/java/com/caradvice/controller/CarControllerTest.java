package com.caradvice.controller;

import com.caradvice.model.EvSpecDto;
import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.RateLimitLogRepository;
import com.caradvice.scraper.CargoSpecSyncService;
import com.caradvice.scraper.EvDatabaseScraperService;
import com.caradvice.scraper.JobStatusService;
import com.caradvice.scraper.MobilityStatsSyncService;
import com.caradvice.scraper.WebInsightScraperService;
import com.caradvice.service.CargoSpecService;
import com.caradvice.service.EvSpecService;
import com.caradvice.service.ExpertInsightService;
import com.caradvice.service.FeedbackService;
import com.caradvice.service.GroqService;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.SafetyRatingService;
import com.caradvice.service.UpcomingInsightService;
import com.caradvice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-lagertester för CarController: X-Admin-Key-skyddet, valideringsfel,
 * rate limits (sök/feedback), cachemarkering och Groq-hälsokollens statuskoder.
 * Alla tjänster mockas — inga externa anrop, ingen databas.
 */
@WebMvcTest(controllers = CarController.class, properties = "admin.key=test-admin")
class CarControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CarController controller;

    @MockBean private GroqService groqService;
    @MockBean private ExpertInsightService expertInsightService;
    @MockBean private SafetyRatingService safetyRatingService;
    @MockBean private EvDatabaseScraperService evScraper;
    @MockBean private CargoSpecSyncService cargoSpecSyncService;
    @MockBean private CargoSpecService cargoSpecService;
    @MockBean private UserService userService;
    @MockBean private RateLimitLogRepository rateLimitLogRepo;
    @MockBean private CargoSpecRepository cargoSpecRepo;
    @MockBean private EvSpecRepository evSpecRepo;
    @MockBean private FeedbackService feedbackService;
    @MockBean private WebInsightScraperService webInsightScraper;
    @MockBean private JobStatusService jobStatus;
    @MockBean private UpcomingInsightService upcomingInsightService;
    @MockBean private IceConsumptionService iceConsumptionService;
    @MockBean private com.caradvice.service.IceGenerationService iceGenerationService;
    @MockBean private com.caradvice.service.EvPowerService evPowerService;
    @MockBean private com.caradvice.service.EvFactCandidateService evFactCandidateService;
    @MockBean private com.caradvice.service.CarVideoService carVideoService;
    @MockBean private MobilityStatsSyncService mobilityStatsSyncService;
    @MockBean private EvSpecService evSpecService;
    @MockBean private com.caradvice.service.UsageStatsService usageStatsService;
    @MockBean private com.caradvice.service.VpicYearCheckService vpicYearCheckService;
    @MockBean private com.caradvice.service.UpcomingAdCheckService upcomingAdCheckService;

    // --- health ---

    @Test
    void healthSvararOkMedSpecCountOchScrapeStatus() throws Exception {
        when(evSpecRepo.count()).thenReturn(42L);
        when(webInsightScraper.lastRunStatus()).thenReturn(
            Map.of("status", "OK", "finishedAt", "2026-07-14T04:05:00"));
        mvc.perform(get("/api/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OK"))
           .andExpect(jsonPath("$.evSpecs").value(42))
           .andExpect(jsonPath("$.lastScrape").value("OK"))
           .andExpect(jsonPath("$.lastScrapeFinishedAt").value("2026-07-14T04:05:00"));
    }

    @Test
    void healthRapporterarDegradedVidTomDatabas() throws Exception {
        when(evSpecRepo.count()).thenReturn(0L);
        when(webInsightScraper.lastRunStatus()).thenReturn(Map.of("status", "NEVER_RUN"));
        mvc.perform(get("/api/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("DEGRADED"))
           .andExpect(jsonPath("$.evSpecs").value(0))
           .andExpect(jsonPath("$.lastScrape").value("NEVER_RUN"));
    }

    @Test
    void healthTalDatabasfelUtanAttKrascha() throws Exception {
        when(evSpecRepo.count()).thenThrow(new RuntimeException("DB nere"));
        when(webInsightScraper.lastRunStatus()).thenThrow(new RuntimeException("DB nere"));
        mvc.perform(get("/api/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("DEGRADED"))
           .andExpect(jsonPath("$.lastScrape").value("ERROR"));
    }

    // Ett anrop ska räcka för "lever den, och vilken kod kör den?" — UptimeRobot pekar bara hit
    @Test
    void healthVisarKortCommitFranRender() throws Exception {
        when(evSpecRepo.count()).thenReturn(42L);
        when(webInsightScraper.lastRunStatus()).thenReturn(Map.of("status", "OK"));
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "appCommit", "6754daf0123456789abcdef");
        try {
            mvc.perform(get("/api/health"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("OK"))
               .andExpect(jsonPath("$.commit").value("6754daf"));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(controller, "appCommit", "");
        }
    }

    @Test
    void healthGerUnknownCommitUtanRendervariabler() throws Exception {
        when(evSpecRepo.count()).thenReturn(42L);
        when(webInsightScraper.lastRunStatus()).thenReturn(Map.of("status", "OK"));
        mvc.perform(get("/api/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.commit").value("unknown"));
    }

    // --- version (svarar på "hann deployen ut?") ---

    @Test
    void versionUtanRenderVariablerGerUnknownOchLocal() throws Exception {
        mvc.perform(get("/api/version"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.version").value("1.0.0"))
           .andExpect(jsonPath("$.commit").value("unknown"))
           .andExpect(jsonPath("$.commitFull").value("unknown"))
           .andExpect(jsonPath("$.branch").value("local"))
           .andExpect(jsonPath("$.uptimeSeconds").isNumber());
    }

    @Test
    void versionKortarNerRendersCommitShaTillSjuTecken() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "appCommit", "6754daf0123456789abcdef");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "appBranch", "master");
        try {
            mvc.perform(get("/api/version"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.commit").value("6754daf"))
               .andExpect(jsonPath("$.commitFull").value("6754daf0123456789abcdef"))
               .andExpect(jsonPath("$.branch").value("master"));
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(controller, "appCommit", "");
            org.springframework.test.util.ReflectionTestUtils.setField(controller, "appBranch", "");
        }
    }

    // --- admin-nyckelskyddet ---

    @Test
    void adminEndpointUtanNyckelGer403() throws Exception {
        mvc.perform(get("/api/admin/scrape-status"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void adminEndpointMedFelNyckelGer403() throws Exception {
        mvc.perform(get("/api/admin/scrape-status").header("X-Admin-Key", "fel-nyckel"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointMedRattNyckelSlappsIn() throws Exception {
        when(webInsightScraper.lastRunStatus()).thenReturn(Map.of("status", "OK"));

        mvc.perform(get("/api/admin/scrape-status").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void upcomingListanKraverAdminNyckelOchRaknarRader() throws Exception {
        mvc.perform(get("/api/admin/insights/upcoming"))
           .andExpect(status().isForbidden());

        when(upcomingInsightService.list()).thenReturn(List.of(
                Map.of("insight_id", 12, "car_make", "Mercedes", "car_model", "GLA")));

        mvc.perform(get("/api/admin/insights/upcoming").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.count").value(1))
           .andExpect(jsonPath("$.insights[0].car_model").value("GLA"));
    }

    @Test
    void parkeraInsiktKraverNyckelOchAttRadenFinns() throws Exception {
        mvc.perform(post("/api/admin/insights/12/upcoming"))
           .andExpect(status().isForbidden());
        verify(upcomingInsightService, never()).mark(any());

        // Okänt id ska aldrig markeras — annars fylls kön med id:n utan rader
        when(expertInsightService.exists(99L)).thenReturn(false);
        mvc.perform(post("/api/admin/insights/99/upcoming").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isNotFound());
        verify(upcomingInsightService, never()).mark(99L);

        when(expertInsightService.exists(12L)).thenReturn(true);
        when(upcomingInsightService.isUpcoming(12L)).thenReturn(true);
        mvc.perform(post("/api/admin/insights/12/upcoming").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.marked").value(12));
        verify(upcomingInsightService).mark(12L);
    }

    /** mark() sväljer DB-fel och svarar void, så utan efterkontrollen blir ett haveri en 200:a. */
    @Test
    void parkeringSomInteBetGer500() throws Exception {
        when(expertInsightService.exists(13L)).thenReturn(true);
        when(upcomingInsightService.isUpcoming(13L)).thenReturn(false);
        mvc.perform(post("/api/admin/insights/13/upcoming").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isInternalServerError());
    }

    @Test
    void slappKommandeInsiktGer200EllerNarInteMarkerad404() throws Exception {
        when(upcomingInsightService.release(12L)).thenReturn(true);
        mvc.perform(delete("/api/admin/insights/12/upcoming").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.released").value(12));

        when(upcomingInsightService.release(99L)).thenReturn(false);
        mvc.perform(delete("/api/admin/insights/99/upcoming").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isNotFound());
    }

    @Test
    void glomSeenNyckelKraverNyckelOchReturnerarAntalet() throws Exception {
        mvc.perform(delete("/api/admin/seen-keys").param("key", "https://carup.se/nagot"))
           .andExpect(status().isForbidden());

        when(webInsightScraper.forgetSeen("https://carup.se/nagot", false)).thenReturn(1);

        mvc.perform(delete("/api/admin/seen-keys").param("key", "https://carup.se/nagot")
                .header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.removed").value(1));
    }

    @Test
    void glomSeenNyckelGer400NarTjanstenAvvisarVardet() throws Exception {
        // ett för kort prefix skulle tömma hela web_insight_seen
        when(webInsightScraper.forgetSeen("h", true))
                .thenThrow(new IllegalArgumentException("prefix maaste vara minst 12 tecken (fick 1)"));

        mvc.perform(delete("/api/admin/seen-keys").param("key", "h").param("prefix", "true")
                .header("X-Admin-Key", "test-admin"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void scrapeStatusListarAllaSchemalagdaJobb() throws Exception {
        when(webInsightScraper.lastRunStatus()).thenReturn(Map.of("status", "OK"));
        when(jobStatus.allJobs()).thenReturn(Map.of(
                "ev-specs", Map.of("status", "OK", "startedAt", "2026-07-31 02:00:00"),
                "cargo-specs", Map.of("status", "NEVER_RUN")));

        mvc.perform(get("/api/admin/scrape-status").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OK"))
           .andExpect(jsonPath("$.jobs.['ev-specs'].status").value("OK"))
           .andExpect(jsonPath("$.jobs.['cargo-specs'].status").value("NEVER_RUN"));
    }

    @Test
    void scrapeStatusSvararAndaOmJobbtabellenKrasar() throws Exception {
        when(webInsightScraper.lastRunStatus()).thenReturn(Map.of("status", "OK"));
        when(jobStatus.allJobs()).thenThrow(new RuntimeException("DB nere"));

        mvc.perform(get("/api/admin/scrape-status").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OK"))
           .andExpect(jsonPath("$.jobs").doesNotExist());
    }

    // --- /api/recommend ---

    @Test
    void rekommendationLyckasForAnonymMedKvarvarandeSokningar() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0, null));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.1.1.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":\"200000\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.loggedIn").value(false))
           .andExpect(jsonPath("$.subscriber").value(false))
           .andExpect(jsonPath("$.remainingSearches").value(29))  // 30 i timmen för alla, en förbrukad
           .andExpect(jsonPath("$.cached").doesNotExist());
    }

    @Test
    void cachadRekommendationMarkerasMedAlder() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), true, 300, null));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.2.2.2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.cached").value(true))
           .andExpect(jsonPath("$.cachedAgeMinutes").value(5));
    }

    @Test
    void budgetunderskottFoljerMedISvaret() throws Exception {
        // Gick kriterierna inte ihop (låg budget + hårt ålderskrav) visas korten ändå, men
        // frontend måste få veta varför de ligger över budget — annars läser tre bilar till
        // dubbla priset som en trasig rekommendation. Live-fyndet var 100 000 kr + max 3 år.
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0, 249_900));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.9.9.9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":\"100000\",\"maxAgeYears\":3}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.budgetShortfallFromKr").value(249_900));
    }

    @Test
    void utanBudgetunderskottSaknasFaltetHelt() throws Exception {
        // Fältet får inte finnas i normalfallet — frontend ritar banderollen på dess blotta närvaro
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0, null));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.9.9.8")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":\"400000\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.budgetShortfallFromKr").doesNotExist());
    }

    @Test
    void tomtSvarBarKravenIStalletForEttFel() throws Exception {
        // Klarade INGEN bil kraven returnerar GroqService tomt i stället för att kasta. Förr
        // blev det HTTP 500 "AI:n föreslog en bilmodell som inte kunde verifieras" — ett
        // tekniskt fel som skyllde på AI:n för något som oftast är ett rimligt svar på en hård
        // fråga. Live 2026-08-10: familjeelbil + 400 l + 200 000 kr gav 500 i ena körningen och
        // ett Tesla-kort i nästa.
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0, null));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.9.9.7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":\"200000\",\"carCategory\":\"familjebil\",\"fuelType\":\"el\","
                       + "\"passengers\":5,\"minCargoLiters\":400}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.recommendations").isEmpty())
           .andExpect(jsonPath("$.narrowCriteria.kvar").value(0))
           .andExpect(jsonPath("$.narrowCriteria.krav[0]").value("ren elbil"))
           .andExpect(jsonPath("$.narrowCriteria.krav[1]").value("minst 400 liter bagage"));
    }

    @Test
    void budgetalternativReturnerarBilarInomBudget() throws Exception {
        // "100 000 kr räcker inte" är korrekt men torftigt — MG ZS EV kring 100 000 kr och
        // Nissan Leaf 2016+ finns i prisklassen, de är bara äldre än 3 år.
        when(groqService.findBudgetAlternatives(any())).thenReturn(List.of(
                new GroqService.BudgetAlternative("MG ZS EV (2020)", 99_000),
                new GroqService.BudgetAlternative("Nissan Leaf (2017)", 85_000)));

        mvc.perform(post("/api/budget-alternatives")
                .header("X-Forwarded-For", "10.7.7.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":100000,\"carCategory\":\"elbil\",\"maxAgeYears\":3}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.alternatives[0].title").value("MG ZS EV (2020)"))
           .andExpect(jsonPath("$.alternatives[0].fromKr").value(99_000))
           .andExpect(jsonPath("$.alternatives[1].title").value("Nissan Leaf (2017)"));
    }

    @Test
    void budgetalternativSvararTomtIStalletForAttFallaBanderollen() throws Exception {
        // Raden är en förbättring av banderollen, inte ett krav — ett fel får inte ge 500
        when(groqService.findBudgetAlternatives(any())).thenThrow(new RuntimeException("Groq nere"));

        mvc.perform(post("/api/budget-alternatives")
                .header("X-Forwarded-For", "10.7.7.2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":100000}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.alternatives").isEmpty());
    }

    @Test
    void rekommendationRateLimitGer429EfterTrettioSokningar() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0, null));

        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/recommend")
                    .header("X-Forwarded-For", "10.3.3.3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
               .andExpect(status().isOk());
        }
        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.3.3.3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isTooManyRequests())
           .andExpect(jsonPath("$.rateLimited").value(true))
           // Väggen pekar på prenumerationen — gratiskontot som mellansteg finns inte längre
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("denna timme")))
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("obegränsade")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dygnstaketBiterAvenNarTimpottenArTom() throws Exception {
        // Dygnstaket går inte att nå med 31 anrop i rad — timtaket stoppar långt innan. Det
        // slår bara mot något som malt i flera timmar, alltså ett skript. Historiken seedas
        // därför direkt: 100 sökningar spridda över dygnet men UTANFÖR den senaste timmen,
        // så timpotten är orörd och bara dygnsbromsen kan fälla anropet.
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0, null));
        Map<String, List<Long>> logg = (Map<String, List<Long>>)
                org.springframework.test.util.ReflectionTestUtils.getField(controller, "ipRequestLog");
        long nu = System.currentTimeMillis();
        List<Long> gamla = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) gamla.add(nu - (2L + i % 20) * 3600_000L / 2);
        logg.put("10.9.9.9", gamla);

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.9.9.9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isTooManyRequests())
           .andExpect(jsonPath("$.rateLimited").value(true))
           // Dygnsbromsen och timtaket är två olika besked — "vänta en stund" duger inte här
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("senaste dygnet")));
        logg.remove("10.9.9.9");
    }

    @Test
    void usageKraverAdminNyckel() throws Exception {
        mvc.perform(get("/api/admin/usage")).andExpect(status().isForbidden());

        when(usageStatsService.snapshot()).thenReturn(Map.of("accounts", 7L, "activeSubscribers", 0L));
        mvc.perform(get("/api/admin/usage").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.accounts").value(7));
    }

    @Test
    void groqFelGer500MedFelmeddelande() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenThrow(new RuntimeException("AI-svaret blev ofullständigt. Försök igen."));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.4.4.4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.success").value(false))
           .andExpect(jsonPath("$.error").value("AI-svaret blev ofullständigt. Försök igen."));
    }

    // --- /api/compare-cars ---

    @Test
    void jamforelseUtanTvaBilarGer400() throws Exception {
        mvc.perform(post("/api/compare-cars")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"car1\":\"Tesla Model 3\",\"car2\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Ange två bilmodeller"));
    }

    @Test
    void jamforelseAvSammaBilGer400() throws Exception {
        mvc.perform(post("/api/compare-cars")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"car1\":\"Tesla Model 3\",\"car2\":\"tesla model 3\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Välj två olika bilar"));
    }

    // --- /api/chat ---

    @Test
    void chattUtanMeddelandenGerInfoSvar() throws Exception {
        mvc.perform(post("/api/chat")
                .header("X-Forwarded-For", "10.5.5.5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reply").value("Inga meddelanden."));
    }

    @Test
    void chattFragorRaknasMotSammaPottSomRekommendationer() throws Exception {
        when(groqService.chat(any(), any())).thenReturn("svar");
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"fråga\"}]}";
        // 30 chattfrågor drar hela timpotten (samma pool som /recommend)
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/api/chat")
                    .header("X-Forwarded-For", "10.6.6.6")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
               .andExpect(status().isOk());
        }
        // 31:a blockeras med timgränsens meddelande
        mvc.perform(post("/api/chat")
                .header("X-Forwarded-For", "10.6.6.6")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isTooManyRequests())
           .andExpect(jsonPath("$.rateLimited").value(true))
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("denna timme")));
    }

    @Test
    void searchStatusPeekarUtanAttForbruka() throws Exception {
        mvc.perform(get("/api/search-status").header("X-Forwarded-For", "10.7.7.7"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.remaining").value(30))
           // limit + period måste med: baren kan inte gissa "i dag" vs "denna timme"
           .andExpect(jsonPath("$.limit").value(30))
           .andExpect(jsonPath("$.period").value("hour"))
           .andExpect(jsonPath("$.subscriber").value(false))
           .andExpect(jsonPath("$.loggedIn").value(false));
        // Andra anropet ger SAMMA remaining = peek förbrukar ingen sökning
        mvc.perform(get("/api/search-status").header("X-Forwarded-For", "10.7.7.7"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.remaining").value(30));
    }

    // --- /api/insights ---

    @Test
    void insikterForBilkortReturnerasMedKalla() throws Exception {
        when(expertInsightService.findForCarTitle("Tesla Model 3 (2021)"))
                .thenReturn(List.of(Map.of("expert", "Teknikens Värld", "insight", "Toppbetyg.", "rating", 9)));

        mvc.perform(get("/api/insights").param("car", "Tesla Model 3 (2021)"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].expert").value("Teknikens Värld"))
           .andExpect(jsonPath("$[0].insight").value("Toppbetyg."))
           .andExpect(jsonPath("$[0].rating").value(9));
    }

    @Test
    void insikterUtanTraffGerTomLista() throws Exception {
        when(expertInsightService.findForCarTitle(any())).thenReturn(List.of());

        mvc.perform(get("/api/insights").param("car", "Okänd Bil"))
           .andExpect(status().isOk())
           .andExpect(content().json("[]"));
    }

    // --- /api/feedback ---

    @Test
    void giltigFeedbackSparas() throws Exception {
        when(feedbackService.save("Tesla Model 3 (2021)", "up")).thenReturn(true);

        mvc.perform(post("/api/feedback")
                .header("X-Forwarded-For", "10.6.6.6")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"carTitle\":\"Tesla Model 3 (2021)\",\"vote\":\"up\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void ogiltigFeedbackGer400() throws Exception {
        when(feedbackService.save(any(), any())).thenReturn(false);

        mvc.perform(post("/api/feedback")
                .header("X-Forwarded-For", "10.7.7.7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"carTitle\":\"Tesla\",\"vote\":\"sideways\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Ogiltig feedback"));
    }

    @Test
    void feedbackRateLimitGer429EfterTioRoster() throws Exception {
        when(feedbackService.save(any(), any())).thenReturn(true);

        String body = "{\"carTitle\":\"Tesla\",\"vote\":\"up\"}";
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/feedback")
                    .header("X-Forwarded-For", "10.8.8.8")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
               .andExpect(status().isOk());
        }
        mvc.perform(post("/api/feedback")
                .header("X-Forwarded-For", "10.8.8.8")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isTooManyRequests());
    }

    // --- GET /api/admin/cargo-coverage ---

    @Test
    void bagagetackningenKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/cargo-coverage"))
           .andExpect(status().isForbidden());
    }

    @Test
    void bagagetackningenVisarHurMangaSomHarVolym() throws Exception {
        // Utan endpointen gick nattens ifyllning bara att avläsa i Render-loggen — och
        // bagagevaktens "fäll bara på positivt bevis" vilar på just den här siffran
        when(cargoSpecService.coverage()).thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
                "total", 243L, "medVolym", 243L, "utanVolym", 0L)));

        mvc.perform(get("/api/admin/cargo-coverage").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total").value(243))
           .andExpect(jsonPath("$.medVolym").value(243))
           .andExpect(jsonPath("$.utanVolym").value(0));
    }

    // --- GET /api/admin/ice-generations ---

    @Test
    void iceGenerationListanKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/ice-generations"))
           .andExpect(status().isForbidden());
    }

    @Test
    void iceGenerationListanVisarArtalenInteBaraAntalet() throws Exception {
        // Räknaren i cargo-coverage stod på 44 medan varenda rad bar faceliftens årtal —
        // antalet kan inte avslöja ett fel som sitter i värdet. Golf VIII kom 2020, inte 2024.
        when(iceGenerationService.lista()).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("model", "volkswagen golf", "franAr", 2020)),
                new java.util.LinkedHashMap<>(Map.of("model", "volvo xc60", "franAr", 2017))));
        when(iceGenerationService.antalMissar()).thenReturn(118L);

        mvc.perform(get("/api/admin/ice-generations").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total").value(2))
           // total + missar är hur långt ifyllningen kommit av 310 modeller; står summan
           // still natt efter natt är arbetslistan slut, inte jobbet trasigt
           .andExpect(jsonPath("$.missar").value(118))
           .andExpect(jsonPath("$.generations[0].model").value("volkswagen golf"))
           .andExpect(jsonPath("$.generations[0].franAr").value(2020));
    }

    @Test
    void iceGenerationListanDelarUppMissarnaPaOrsak() throws Exception {
        // "Utan träff" var förut EN siffra för både ett dött uppslag och en vakt som avstod med
        // flit, och just den hopslagningen lät 139 falska nej ligga parkerade i 30 dagar utan att
        // något såg fel ut. Domineras svaret av ej-hittad är det uppslaget som är trasigt.
        when(iceGenerationService.lista()).thenReturn(List.of());
        when(iceGenerationService.antalMissar()).thenReturn(139L);
        when(iceGenerationService.missarPerOrsak()).thenReturn(
                new java.util.LinkedHashMap<>(Map.of("ej-hittad", 131L, "vakten-avstod", 8L)));

        mvc.perform(get("/api/admin/ice-generations").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.missarPerOrsak.ej-hittad").value(131))
           .andExpect(jsonPath("$.missarPerOrsak.vakten-avstod").value(8));
    }

    // --- POST /api/admin/ice-consumption/sync ---

    @Test
    void iceConsumptionSynkKraverNyckel() throws Exception {
        mvc.perform(post("/api/admin/ice-consumption/sync"))
           .andExpect(status().isForbidden());
    }

    @Test
    void iceConsumptionSynkSvararMedVadSomAndrades() throws Exception {
        // Enda vägen att få en RÄTTELSE i CSV:n till drift: seedningen kan bara lägga till, och
        // ett ändrat variantnamn är en ny nyckel — utan borttagningen blir den gamla raden kvar.
        when(iceConsumptionService.synkaFranCsv()).thenReturn(
                new java.util.LinkedHashMap<>(Map.of("tillagda", 1, "borttagna", 1, "total", 957)));

        mvc.perform(post("/api/admin/ice-consumption/sync").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.tillagda").value(1))
           .andExpect(jsonPath("$.borttagna").value(1))
           .andExpect(jsonPath("$.total").value(957));
    }

    // --- GET /api/admin/cargo-specs ---

    @Test
    void cargoSpecListanKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/cargo-specs"))
           .andExpect(status().isForbidden());
    }

    @Test
    void cargoSpecListanVisarLiterenInteBaraAntalet() throws Exception {
        // Täckningen stod på 602/602/0 medan parsern var död: ett jobb utan arbete kan inte
        // skilja "inget kvar att fylla" från "trasig", och en räknare avslöjar aldrig ett fel
        // som sitter i VÄRDET. Samma lucka som ice-generations-listan fyllde för årtalen.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("carName", "Volvo V60", "cargoLiters", 529)),
                new java.util.LinkedHashMap<>(Map.of("carName", "Kia Niro EV", "cargoLiters", 475))));

        mvc.perform(get("/api/admin/cargo-specs").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total").value(2))
           .andExpect(jsonPath("$.cargoSpecs[0].carName").value("Volvo V60"))
           .andExpect(jsonPath("$.cargoSpecs[0].cargoLiters").value(529));
    }
    // --- GET /api/admin/ice-generations/missar ---

    @Test
    void missListanKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/ice-generations/missar"))
           .andExpect(status().isForbidden());
        verify(iceGenerationService, never()).listaMissar(any());
    }

    @Test
    void missListanVisarModellernaInteBaraAntalet() throws Exception {
        // Räkningen sa "vakten-avstod: 111" och där tog granskningen slut — en siffra kan inte
        // skilja ett klokt avstående från en hel märkesfamilj som föll på samma fel. Precis det
        // hade hänt: chassikoden i titeln fällde varenda BMW-, Audi- och Lexus-generation medan
        // de 139 parkerade nejen såg friska ut i räkningen.
        when(iceGenerationService.listaMissar(null)).thenReturn(List.of(
                rad("bmw 3-serie", "vakten-avstod", "2026-08-18", 1L, 29L),
                rad("lexus nx", "vakten-avstod", "2026-08-18", 1L, 29L)));
        when(iceGenerationService.antalMissar()).thenReturn(138L);

        mvc.perform(get("/api/admin/ice-generations/missar").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.antal").value(2))
           // totalen står bredvid det filtrerade antalet: utan den läses "2 rader" som hela
           // sanningen även när anropet bar ett filter
           .andExpect(jsonPath("$.totalt").value(138))
           .andExpect(jsonPath("$.missar[0].model").value("bmw 3-serie"))
           .andExpect(jsonPath("$.missar[0].orsak").value("vakten-avstod"))
           // dagarKvar svarar på det räkningen aldrig kunde: när prövas modellen om?
           .andExpect(jsonPath("$.missar[0].dagarKvar").value(29));
    }

    @Test
    void missListanSlapperIgenomOrsaksfiltret() throws Exception {
        // Filtret finns för att de 111 vakten-avstod ska gå att läsa utan att de 27 döda
        // uppslagen skräpar ner listan — de två nejen kräver olika åtgärder.
        when(iceGenerationService.listaMissar("ej-hittad")).thenReturn(List.of(
                rad("alfa romeo 147", "ej-hittad", "2026-08-18", 1L, 29L)));

        mvc.perform(get("/api/admin/ice-generations/missar")
                        .param("orsak", "ej-hittad")
                        .header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.antal").value(1))
           .andExpect(jsonPath("$.missar[0].orsak").value("ej-hittad"));
        verify(iceGenerationService).listaMissar("ej-hittad");
    }

    private static Map<String, Object> rad(String model, String orsak, String dag, long alder, long kvar) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("model", model);
        m.put("orsak", orsak);
        m.put("forsoktDag", dag);
        m.put("alderDagar", alder);
        m.put("dagarKvar", kvar);
        return m;
    }

    // --- DELETE /api/admin/ice-generations/missar ---

    @Test
    void missrensningenKraverNyckel() throws Exception {
        mvc.perform(delete("/api/admin/ice-generations/missar"))
           .andExpect(status().isForbidden());
        verify(iceGenerationService, never()).rensaMissar();
    }

    @Test
    void missrensningenLaterEnRattningFaVerkanSammaNatt() throws Exception {
        // En miss är ett nej på frågan vi ställde. Rättas uppslaget är gamla nej inte längre
        // svar, och utan den här överlever de sin rättning i upp till 30 dagar.
        when(iceGenerationService.rensaMissar()).thenReturn(139);

        mvc.perform(delete("/api/admin/ice-generations/missar").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.deleted").value(139));
    }

    // --- DELETE /api/admin/ice-generations/modell ---

    @Test
    void riktadGenerationsraderingKraverNyckel() throws Exception {
        mvc.perform(delete("/api/admin/ice-generations/modell").param("model", "Volvo s90"))
           .andExpect(status().isForbidden());
        verify(iceGenerationService, never()).radera(anyString());
    }

    @Test
    void riktadGenerationsraderingLagarEnRadUtanAttTommaTabellen() throws Exception {
        /*
         * 2026-08-18 bar två av 172 rader fel generation — "Volvo s90" 1997 och "Volvo v90"
         * 1996, den ombadgade 960:an i stället för 2016 års bil — medan de övriga 170 var
         * riktiga. Enda vägen tillbaka var DELETE /api/admin/ice-generations, som tömmer HELA
         * tabellen och alla missar: tre nätter utan generationsdata för att laga två rader.
         */
        when(iceGenerationService.radera("Volvo s90")).thenReturn(1);

        mvc.perform(delete("/api/admin/ice-generations/modell")
                        .param("model", "Volvo s90")
                        .header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.model").value("Volvo s90"))
           .andExpect(jsonPath("$.deleted").value(1));

        // ...och den stora rensningen får inte gå igång på vägen
        verify(iceGenerationService, never()).rensa();
    }

    // --- GET /api/admin/ice-generations/vpic-check ---

    @Test
    void vpicKollenKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/ice-generations/vpic-check"))
           .andExpect(status().isForbidden());
        verify(vpicYearCheckService, never()).granska(any(), any(), any());
    }

    @Test
    void vpicKollenRedovisarVarjeStatusForSigOchKapningen() throws Exception {
        /*
         * Rapporten måste kunna svara på "hur mycket granskades?" och inte bara "hittade du
         * något?" — en halv granskning som ser ut som en hel är samma fälla som täckningsmätaren
         * på 602/602/0. Och INGEN_DATA är en egen post, aldrig ett godkännande: mätt 2026-08-18
         * har vPIC noll modeller för Škoda, Renault, Dacia och Cupra, så den utgången är den
         * vanligaste och säger ingenting om raden.
         */
        var avvikelse = new com.caradvice.service.VpicYearCheckService.Dom(
                "Volvo s90", 1997,
                com.caradvice.service.VpicYearCheckService.Status.AVVIKER,
                "vPIC saknar modellen [2000, 2003] men har den 2017");
        when(vpicYearCheckService.granska(any(), eq(null), any())).thenReturn(
                new com.caradvice.service.VpicYearCheckService.Rapport(
                        170, 4, 96,
                        new java.util.LinkedHashMap<>(Map.of("OK", 62L, "AVVIKER", 1L, "INGEN_DATA", 107L)),
                        List.of(avvikelse)));

        mvc.perform(get("/api/admin/ice-generations/vpic-check").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.kontrollerade").value(170))
           // hoppade > 0 = anropstaket tog slut, inte att raderna var friska
           .andExpect(jsonPath("$.hoppade").value(4))
           .andExpect(jsonPath("$.vpicAnrop").value(96))
           .andExpect(jsonPath("$.perStatus.INGEN_DATA").value(107))
           .andExpect(jsonPath("$.avvikelser[0].model").value("Volvo s90"))
           .andExpect(jsonPath("$.avvikelser[0].vartArtal").value(1997));
    }

    @Test
    void annonskollenAvKonKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/insights/upcoming/ad-check"))
           .andExpect(status().isForbidden());
        verify(upcomingAdCheckService, never()).granska(any());
    }

    @Test
    void annonskollenRedovisarLarmetOchRadernaBakomDet() throws Exception {
        /*
         * Rapporten är rådgivande: den ska svara "vilka rader ska jag titta på", inte bara
         * "något är fel". Därför följer id:na med — och LARM skiljs från GRANSKA, för en bil
         * som säljs kan mycket väl ha en korrekt parkerad rad om nästa generation.
         */
        var larm = new com.caradvice.service.UpcomingAdCheckService.Dom(
                "Hyundai", "Ioniq 3",
                com.caradvice.service.UpcomingAdCheckService.Status.LARM, 4,
                List.of(1381L, 1384L), List.of(1381L, 1384L),
                List.of("Hyundai IONIQ 3 Standard Range Select"));
        when(upcomingAdCheckService.granska(any())).thenReturn(
                new com.caradvice.service.UpcomingAdCheckService.Rapport(
                        14, 48, 0,
                        new java.util.LinkedHashMap<>(Map.of(
                                "LARM", 1L, "UPPSLAG_MISSLYCKADES", 0L,
                                "GRANSKA", 3L, "INGA_ANNONSER", 10L)),
                        List.of(larm)));

        mvc.perform(get("/api/admin/insights/upcoming/ad-check").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.bilar").value(14))
           .andExpect(jsonPath("$.rader").value(48))
           .andExpect(jsonPath("$.hoppade").value(0))
           .andExpect(jsonPath("$.perStatus.GRANSKA").value(3))
           .andExpect(jsonPath("$.domar[0].status").value("LARM"))
           .andExpect(jsonPath("$.domar[0].carModel").value("Ioniq 3"))
           .andExpect(jsonPath("$.domar[0].raderUtanNyhetsord[0]").value(1381));

        // Kollen är rådgivande — den får aldrig släppa en rad på egen hand.
        verify(upcomingInsightService, never()).release(any());
    }

    @Test
    void vpicKollenSlapperIgenomMarkesfiltret() throws Exception {
        when(vpicYearCheckService.granska(any(), eq("Volvo"), any())).thenReturn(
                new com.caradvice.service.VpicYearCheckService.Rapport(
                        18, 0, 12, new java.util.LinkedHashMap<>(Map.of("OK", 18L)), List.of()));

        mvc.perform(get("/api/admin/ice-generations/vpic-check")
                        .param("make", "Volvo")
                        .header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.kontrollerade").value(18))
           .andExpect(jsonPath("$.avvikelser").isEmpty());
    }

    // --- GET /api/admin/ev-specs ---

    @Test
    void adminEvSpecListanKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/ev-specs"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminEvSpecListanReturnerarRaderMedPrisvardhet() throws Exception {
        when(evSpecService.listAllWithValueLabel(15000)).thenReturn(List.of(
                Map.of("carName", "MG4 Extended Range", "priceKr", 375_000,
                       "valueLabel", "Utmärkt prisvärdhet")));

        mvc.perform(get("/api/admin/ev-specs").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].carName").value("MG4 Extended Range"))
           .andExpect(jsonPath("$[0].valueLabel").value("Utmärkt prisvärdhet"));
    }

    @Test
    void adminEvSpecListanTarKmPerArSomParameter() throws Exception {
        // kmPerYear styr daysPerCharge i DTO:n — default 15000 ska gå att åsidosätta
        when(evSpecService.listAllWithValueLabel(30000)).thenReturn(List.of());

        mvc.perform(get("/api/admin/ev-specs").param("kmPerYear", "30000")
                .header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk());
        verify(evSpecService).listAllWithValueLabel(30000);
    }

    // --- GET /api/admin/insights + DELETE /api/admin/insights/{id} ---

    @Test
    void adminInsiktslistanKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/insights"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminInsiktslistanReturnerarSenasteInsikter() throws Exception {
        when(expertInsightService.listRecent(null, 50, 0)).thenReturn(List.of(
                Map.of("id", 42, "expert", "CarUp", "insight", "Bra bil.")));

        mvc.perform(get("/api/admin/insights").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value(42))
           .andExpect(jsonPath("$[0].expert").value("CarUp"));
    }

    @Test
    void adminInsiktslistanSkickarVidareSidnumret() throws Exception {
        // Sidan ar enda satten att rakna upp hela tabellen — taket ar 500 rader.
        when(expertInsightService.listRecent(null, 500, 1)).thenReturn(List.of(
                Map.of("id", 7, "expert", "Bilexpert", "insight", "Rad pa sida tva.")));

        mvc.perform(get("/api/admin/insights?limit=500&page=1").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    void adminKategoribyteKraverNyckel() throws Exception {
        mvc.perform(post("/api/admin/insights/rename-category")
                .param("from", "småbil").param("to", "smaabil"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminKategoribyteReturnerarAntal() throws Exception {
        when(expertInsightService.renameCategory("småbil", "smaabil")).thenReturn(12);

        mvc.perform(post("/api/admin/insights/rename-category")
                .header("X-Admin-Key", "test-admin")
                .param("from", "småbil").param("to", "smaabil"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.updated").value(12))
           .andExpect(jsonPath("$.to").value("smaabil"));
    }

    /**
     * Kategoribytet ska svara 400, inte 500, när målet inte finns i formuläret — vakten sitter
     * i tjänsten och kastar IllegalArgumentException, och utan try/catch här hade admin fått
     * ett stackspår i stället för att få veta vilka kategorier som går att välja.
     */
    @Test
    void adminKategoribyteTillOkantVardeGer400() throws Exception {
        when(expertInsightService.renameCategory("suv", "crossover"))
                .thenThrow(new IllegalArgumentException("Okänd kategori: crossover"));

        mvc.perform(post("/api/admin/insights/rename-category")
                .header("X-Admin-Key", "test-admin")
                .param("from", "suv").param("to", "crossover"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Okänd kategori: crossover"));
    }

    @Test
    void adminInsiktsraderingPaIdGer404NarIdSaknas() throws Exception {
        when(expertInsightService.deleteById(999L)).thenReturn(false);

        mvc.perform(delete("/api/admin/insights/999").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isNotFound());
    }

    @Test
    void adminInsiktsraderingPaIdReturnerarRaderad() throws Exception {
        when(expertInsightService.deleteById(42L)).thenReturn(true);

        mvc.perform(delete("/api/admin/insights/42").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.deleted").value(1))
           .andExpect(jsonPath("$.id").value(42));
    }

    // --- POST /api/admin/sync-mobility-stats ---

    @Test
    void mobilityStatsSyncKraverNyckel() throws Exception {
        mvc.perform(post("/api/admin/sync-mobility-stats"))
           .andExpect(status().isForbidden());
    }

    @Test
    void mobilityStatsSyncReturnerarResultatet() throws Exception {
        when(mobilityStatsSyncService.syncNow()).thenReturn(Map.of("status", "OK", "imported", 3));

        mvc.perform(post("/api/admin/sync-mobility-stats").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.imported").value(3));
    }

    @Test
    void mobilityStatsSyncGer502VidFel() throws Exception {
        when(mobilityStatsSyncService.syncNow()).thenReturn(Map.of("status", "ERROR", "error", "Hittade ingen xlsx-rapport"));

        mvc.perform(post("/api/admin/sync-mobility-stats").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isBadGateway())
           .andExpect(jsonPath("$.error").value("Hittade ingen xlsx-rapport"));
    }

    // --- PATCH /api/admin/insights/{id} ---

    @Test
    void adminInsiktspatchKraverNyckel() throws Exception {
        mvc.perform(patch("/api/admin/insights/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"suv\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminInsiktspatchReturnerarUppdateradRad() throws Exception {
        when(expertInsightService.updateInsight(eq(42L), any()))
                .thenReturn(Optional.of(Map.of("id", 42, "category", "suv")));

        mvc.perform(patch("/api/admin/insights/42")
                .header("X-Admin-Key", "test-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"suv\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.category").value("suv"));
    }

    @Test
    void adminInsiktspatchGer404NarIdSaknas() throws Exception {
        when(expertInsightService.updateInsight(eq(999L), any())).thenReturn(Optional.empty());

        mvc.perform(patch("/api/admin/insights/999")
                .header("X-Admin-Key", "test-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"suv\"}"))
           .andExpect(status().isNotFound());
    }

    @Test
    void adminInsiktspatchGer400VidOgiltigtFalt() throws Exception {
        when(expertInsightService.updateInsight(eq(42L), any()))
                .thenThrow(new IllegalArgumentException("Okänt fält: categori"));

        mvc.perform(patch("/api/admin/insights/42")
                .header("X-Admin-Key", "test-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categori\":\"suv\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Okänt fält: categori"));
    }

    // --- DELETE /api/admin/feedback ---

    @Test
    void adminFeedbackRaderingKraverNyckel() throws Exception {
        mvc.perform(delete("/api/admin/feedback").param("car", "Tesla Model 3 (2021)"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminFeedbackRaderingReturnerarAntal() throws Exception {
        when(feedbackService.deleteByCarTitle("TEST-VERIFIERING (raderas)")).thenReturn(2);

        mvc.perform(delete("/api/admin/feedback")
                .header("X-Admin-Key", "test-admin")
                .param("car", "TEST-VERIFIERING (raderas)"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.deleted").value(2))
           .andExpect(jsonPath("$.car").value("TEST-VERIFIERING (raderas)"));
    }

    // --- /api/health/groq (UptimeRobot-övervakad) ---

    @Test
    void groqHealthUtanKonfigGer503() throws Exception {
        when(groqService.isConfigured()).thenReturn(false);

        mvc.perform(get("/api/health/groq"))
           .andExpect(status().isServiceUnavailable())
           .andExpect(jsonPath("$.status").value("UNCONFIGURED"));
    }

    @Test
    void groqHealthMedSaknadModellGer503() throws Exception {
        when(groqService.isConfigured()).thenReturn(true);
        when(groqService.checkModels())
                .thenReturn(new GroqService.ModelStatus(List.of("qwen/qwen3.6-27b"), null, 0));

        mvc.perform(get("/api/health/groq"))
           .andExpect(status().isServiceUnavailable())
           .andExpect(jsonPath("$.status").value("MODEL_MISSING"))
           .andExpect(jsonPath("$.missing[0]").value("qwen/qwen3.6-27b"));
    }

    @Test
    void groqHealthTransientFelGer200Unknown() throws Exception {
        when(groqService.isConfigured()).thenReturn(true);
        when(groqService.checkModels())
                .thenReturn(new GroqService.ModelStatus(List.of(), "timeout", 0));

        mvc.perform(get("/api/health/groq"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void groqHealthAlltValGer200MedModellista() throws Exception {
        when(groqService.isConfigured()).thenReturn(true);
        when(groqService.checkModels()).thenReturn(new GroqService.ModelStatus(List.of(), null, 0));
        when(groqService.configuredModels()).thenReturn(List.of("qwen/qwen3.6-27b", "openai/gpt-oss-20b"));

        mvc.perform(get("/api/health/groq"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("OK"))
           .andExpect(jsonPath("$.models.length()").value(2));
    }

    // ── /api/ev-spec ────────────────────────────────────────────────────────
    // Publik väg till samma specsiffror som korten visar, för märkesväljaren i
    // "Jämför bilar fritt". Den viktiga egenskapen är att en bil UTAN elbilsdata
    // ger tomt objekt och 200 — inte 404, som hade tvingat frontenden att skilja
    // "ingen elbil" från "tjänsten trasig" och rita ett felmeddelande på en Golf.

    @Test
    void evSpecGerSpecarnaForEnElbil() throws Exception {
        when(evSpecService.formatForTitle("Kia EV6", 12430)).thenReturn(
                new EvSpecDto(528, 470, 370, 12, "Ladda var 12:e dag", 77.4, 240, 11,
                              459900, "Bra prisvärdhet", "EV", "NMC"));

        mvc.perform(get("/api/ev-spec").param("car", "Kia EV6"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.wltpKm").value(528))
           .andExpect(jsonPath("$.maxDcKw").value(240))
           .andExpect(jsonPath("$.daysLabel").value("Ladda var 12:e dag"));
    }

    @Test
    void evSpecGerTomtObjektForBilUtanElbilsdata() throws Exception {
        when(evSpecService.formatForTitle(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(null);

        mvc.perform(get("/api/ev-spec").param("car", "Volkswagen Golf"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.wltpKm").doesNotExist());
    }

    @Test
    void evSpecKorstrackanStyrLaddintervallet() throws Exception {
        // Rutan ska säga samma sak som kortet hade sagt för samma sökning, och
        // "ladda var N:e dag" är det enda talet som beror på körsträckan.
        when(evSpecService.formatForTitle("Volvo EX30", 30000)).thenReturn(
                new EvSpecDto(476, 420, 330, 5, "Ladda var 5:e dag", 69.0, 153, 11,
                              379900, null, "EV", null));

        mvc.perform(get("/api/ev-spec").param("car", "Volvo EX30").param("kmPerYear", "30000"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.daysLabel").value("Ladda var 5:e dag"));

        verify(evSpecService).formatForTitle("Volvo EX30", 30000);
    }

    @Test
    void evSpecUtanBilnamnGer400() throws Exception {
        mvc.perform(get("/api/ev-spec").param("car", "   "))
           .andExpect(status().isBadRequest());

        verify(evSpecService, never()).formatForTitle(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }
}
