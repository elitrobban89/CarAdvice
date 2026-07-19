package com.caradvice.controller;

import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.RateLimitLogRepository;
import com.caradvice.scraper.CargoSpecSyncService;
import com.caradvice.scraper.EvDatabaseScraperService;
import com.caradvice.scraper.MobilityStatsSyncService;
import com.caradvice.scraper.WebInsightScraperService;
import com.caradvice.service.CargoSpecService;
import com.caradvice.service.ExpertInsightService;
import com.caradvice.service.FeedbackService;
import com.caradvice.service.GroqService;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.SafetyRatingService;
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
import static org.mockito.ArgumentMatchers.eq;
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
    @MockBean private IceConsumptionService iceConsumptionService;
    @MockBean private MobilityStatsSyncService mobilityStatsSyncService;

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

    // --- /api/recommend ---

    @Test
    void rekommendationLyckasForAnonymMedKvarvarandeSokningar() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.1.1.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"budget\":\"200000\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.success").value(true))
           .andExpect(jsonPath("$.loggedIn").value(false))
           .andExpect(jsonPath("$.subscriber").value(false))
           .andExpect(jsonPath("$.remainingSearches").value(9))
           .andExpect(jsonPath("$.cached").doesNotExist());
    }

    @Test
    void cachadRekommendationMarkerasMedAlder() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), true, 300));

        mvc.perform(post("/api/recommend")
                .header("X-Forwarded-For", "10.2.2.2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.cached").value(true))
           .andExpect(jsonPath("$.cachedAgeMinutes").value(5));
    }

    @Test
    void rekommendationRateLimitGer429EfterTioSokningar() throws Exception {
        when(groqService.getRecommendation(any()))
                .thenReturn(new GroqService.Result(List.of(), false, 0));

        for (int i = 0; i < 10; i++) {
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
           .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Logga in")));
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

    // --- GET /api/admin/insights + DELETE /api/admin/insights/{id} ---

    @Test
    void adminInsiktslistanKraverNyckel() throws Exception {
        mvc.perform(get("/api/admin/insights"))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminInsiktslistanReturnerarSenasteInsikter() throws Exception {
        when(expertInsightService.listRecent(null, 50)).thenReturn(List.of(
                Map.of("id", 42, "expert", "CarUp", "insight", "Bra bil.")));

        mvc.perform(get("/api/admin/insights").header("X-Admin-Key", "test-admin"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value(42))
           .andExpect(jsonPath("$[0].expert").value("CarUp"));
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
}
