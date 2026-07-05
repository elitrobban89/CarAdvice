package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tester för GroqServices rena logik: promptbygget, JSON-parsningen av AI-svaret,
 * cachenyckeln och felmeddelandena. Inga HTTP-anrop görs — allt som testas är
 * strängar in, strängar/objekt ut. Kringservicarna mockas med Mockito.
 */
@ExtendWith(MockitoExtension.class)
class GroqServiceTest {

    @Mock private ExpertInsightService expertInsightService;
    @Mock private SafetyRatingService safetyRatingService;
    @Mock private EvSpecService evSpecService;
    @Mock private CargoSpecService cargoSpecService;
    @Mock private BlocketPriceService blocketPriceService;
    @Mock private NewCarPriceService newCarPriceService;
    @Mock private FeedbackService feedbackService;

    private GroqService service() {
        return new GroqService(expertInsightService, safetyRatingService,
                evSpecService, cargoSpecService, blocketPriceService, newCarPriceService, feedbackService);
    }

    /** Stubbar pristabellerna som buildSystemPrompt hämtar via prisreferens-cachen. */
    private GroqService serviceMedPristabeller() {
        when(newCarPriceService.buildPriceReferenceContext()).thenReturn("ICE-NYPRISTABELL-MARKÖR");
        when(evSpecService.buildPriceReferenceContext()).thenReturn("EV-PRISTABELL-MARKÖR");
        return service();
    }

    private static CarPreferences prefs(int budget, String category, boolean hasCharger, int kmPerYear,
                                        boolean newCar, String fuelType, String transmission,
                                        String budgetType, Integer maxAgeYears) {
        return new CarPreferences(budget, category, hasCharger, kmPerYear, "pendling",
                4, newCar, fuelType, transmission, budgetType, maxAgeYears);
    }

    // --- buildFeedbackContext (tummen ner-signal i systemprompten) ---

    @Test
    void tomFeedbackGerTomStrang() {
        assertThat(GroqService.buildFeedbackContext(List.of())).isEmpty();
    }

    @Test
    void ogilladeBilarListasSomUndvikSignal() {
        String ctx = GroqService.buildFeedbackContext(List.of("Renault Zoe (2021)", "Fiat 500e (2020)"));
        assertThat(ctx)
                .contains("ANVÄNDARFEEDBACK")
                .contains("Renault Zoe (2021), Fiat 500e (2020)")
                .contains("BARA om inget likvärdigt alternativ");
    }

    // --- reasoningEffortFor ---

    @Test
    void gptOssFarLowOchQwenFarNone() {
        // gpt-oss stöder inte "none"; qwen ska ha reasoning helt avstängd
        assertThat(GroqService.reasoningEffortFor("openai/gpt-oss-20b")).isEqualTo("low");
        assertThat(GroqService.reasoningEffortFor("qwen/qwen3.6-27b")).isEqualTo("none");
    }

    // --- buildPrompt ---

    @Test
    void promptInnehallerKategoriAnvandningOchPassagerare() {
        String p = service().buildPrompt(prefs(300_000, "suv", true, 15_000, false, "el", "automat", "köp", null));
        assertThat(p)
                .contains("Kategori: suv")
                .contains("Användning: pendling")
                .contains("Passagerare: 4")
                .contains("(begagnad)");
    }

    @Test
    void milprofilKlassificerasEfterKorstracka() {
        assertThat(service().buildPrompt(prefs(300_000, "suv", true, 8_000, false, "el", null, "köp", null)))
                .contains("lågmilare");
        assertThat(service().buildPrompt(prefs(300_000, "suv", true, 15_000, false, "el", null, "köp", null)))
                .contains("normalmilare");
        assertThat(service().buildPrompt(prefs(300_000, "suv", true, 25_000, false, "el", null, "köp", null)))
                .contains("högmilare");
    }

    @Test
    void utanLaddboxAvraddsBadeBevOchPhev() {
        String p = service().buildPrompt(prefs(300_000, "suv", false, 15_000, false, "el", null, "köp", null));
        assertThat(p)
                .contains("Laddbox: nej – undvik renodlad elbil (BEV) och laddhybrid (PHEV)")
                .contains("elhybrid (HEV) som laddar sig själv");
    }

    @Test
    void uttryckligtLaddhybridvalVinnerOverLaddboxregeln() {
        String p = service().buildPrompt(prefs(300_000, "laddhybrid", false, 15_000, false, "hybrid", null, "köp", null));
        assertThat(p)
                .contains("Laddbox: nej – undvik renodlad elbil")
                .doesNotContain("laddhybrid (PHEV)");
    }

    @Test
    void leasingBudgetSkrivsSomManadskostnad() {
        String p = service().buildPrompt(prefs(5_000, "suv", true, 15_000, true, "el", null, "leasing", null));
        assertThat(p).contains("kr/mån (leasing");
    }

    @Test
    void spelarIngenRollSomBransleUtelamnasUrPrompten() {
        String p = service().buildPrompt(prefs(300_000, "suv", true, 15_000, false, "spelar ingen roll", null, "köp", null));
        assertThat(p).doesNotContain("Drivmedel");
    }

    @Test
    void valdVaxelladaTasMedSomKrav() {
        String p = service().buildPrompt(prefs(300_000, "suv", true, 15_000, false, "bensin", "manuell", "köp", null));
        assertThat(p).contains("Växellåda: manuell");
    }

    @Test
    void maxAlderGerAlderskravMedRattArsmodell() {
        int aldstaTillatna = Year.now().getValue() - 5;
        String p = service().buildPrompt(prefs(300_000, "suv", true, 15_000, false, "bensin", null, "köp", 5));
        assertThat(p)
                .contains("ÅLDERSKRAV: Max 5 år")
                .contains("ENDAST årsmodell " + aldstaTillatna + " eller nyare");
    }

    @Test
    void maxAlderIgnorerasForNybil() {
        String p = service().buildPrompt(prefs(300_000, "suv", true, 15_000, true, "bensin", null, "köp", 5));
        assertThat(p).doesNotContain("ÅLDERSKRAV");
    }

    // --- buildSystemPrompt ---

    @Test
    void expertkontextBifogasIslutet() {
        String sp = serviceMedPristabeller().buildSystemPrompt("EXPERTINSIKT-MARKÖR", "bensin");
        assertThat(sp).contains("EXPERTINSIKT-MARKÖR");
    }

    @Test
    void renElbilsForfraganFarEvTabellMenInteIceTabell() {
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp)
                .contains("EV-PRISTABELL-MARKÖR")
                .doesNotContain("ICE-NYPRISTABELL-MARKÖR")
                .contains("ELBIL OBLIGATORISKT");
    }

    @Test
    void bensinForfraganFarIceTabellMenInteEvTabell() {
        String sp = serviceMedPristabeller().buildSystemPrompt("", "bensin");
        assertThat(sp)
                .contains("ICE-NYPRISTABELL-MARKÖR")
                .doesNotContain("EV-PRISTABELL-MARKÖR")
                .doesNotContain("ELBIL OBLIGATORISKT");
    }

    @Test
    void systempromptenKraverExaktTreBilarOchForbjuderFabriceradePriser() {
        String sp = serviceMedPristabeller().buildSystemPrompt("", "bensin");
        assertThat(sp)
                .contains("ALLTID EXAKT 3 bilar")
                .contains("FABRICERA ALDRIG PRISER")
                .contains("BYD Dolphin");
    }

    // --- extractJson ---

    @Test
    void thinkBlockFranResoneringsmodellStrippas() {
        String content = "<think>Jag funderar på bilar...</think>{\"recommendations\":[]}";
        assertThat(service().extractJson(content)).isEqualTo("{\"recommendations\":[]}");
    }

    @Test
    void textRuntJsonStrippas() {
        String content = "Här är mina rekommendationer:\n{\"recommendations\":[]}\nHoppas det hjälper!";
        assertThat(service().extractJson(content)).isEqualTo("{\"recommendations\":[]}");
    }

    // --- parseRecommendations ---

    private static final String GILTIG_BIL = """
            {"title":"Volvo EX30 (2024)","price":"300 000–350 000 kr",
             "whyRecommended":"Teknikens Värld: toppbetyg","pros":["kvick","kompakt","billig i drift"],
             "con":"trångt baksäte","fitSummary":"passar pendlaren","expertOpinion":"Kvick och tyst.",
             "horsepower":272,"engineOptions":"51 kWh 272hk (344km)","fuelSpec":null}""";

    @Test
    void standardnyckelnRecommendationsParsas() throws Exception {
        List<CarRecommendation> r = service().parseRecommendations(
                "{\"recommendations\":[" + GILTIG_BIL + "]}");
        assertThat(r).hasSize(1);
        assertThat(r.get(0).title()).isEqualTo("Volvo EX30 (2024)");
        assertThat(r.get(0).horsepower()).isEqualTo(272);
        assertThat(r.get(0).pros()).containsExactly("kvick", "kompakt", "billig i drift");
    }

    @Test
    void fallbackNyckelnCarsParsas() throws Exception {
        // AI:n döper ibland om rotnyckeln — "cars", "bilar" m.fl. ska också fungera
        List<CarRecommendation> r = service().parseRecommendations("{\"cars\":[" + GILTIG_BIL + "]}");
        assertThat(r).hasSize(1);
    }

    @Test
    void rotArrayUtanNyckelParsas() throws Exception {
        List<CarRecommendation> r = service().parseRecommendations("[" + GILTIG_BIL + "]");
        assertThat(r).hasSize(1);
    }

    @Test
    void thinkBlockOchOmgivandeTextHindrarInteParsning() throws Exception {
        String content = "<think>resonemang</think>Såklart! {\"recommendations\":[" + GILTIG_BIL + "]}";
        assertThat(service().parseRecommendations(content)).hasSize(1);
    }

    @Test
    void okandaExtrafaltKraschaInteParsningen() throws Exception {
        // AI:n hittar ibland på egna fält — de ska ignoreras, inte fälla svaret
        String bil = GILTIG_BIL.replaceFirst("\\{", "{\"co2Grams\":123,\"topSpeed\":180,");
        List<CarRecommendation> r = service().parseRecommendations("{\"recommendations\":[" + bil + "]}");
        assertThat(r).hasSize(1);
        assertThat(r.get(0).title()).isEqualTo("Volvo EX30 (2024)");
    }

    @Test
    void avhuggetJsonGerBegripligtFelmeddelande() {
        String truncated = "{\"recommendations\":[{\"title\":\"Volvo EX30\",\"price\":\"300 0";
        assertThatThrownBy(() -> service().parseRecommendations(truncated))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ofullständigt");
    }

    @Test
    void jsonUtanBilarGerBegripligtFelmeddelande() {
        assertThatThrownBy(() -> service().parseRecommendations("{\"message\":\"inga bilar hittades\"}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("oväntat svar");
    }

    @Test
    void feltypadeFaltGerBegripligtFelIstalletForKrasch() {
        // pros som sträng istället för array — schemafel ska ge användarvänligt fel, inte 500
        String content = "{\"recommendations\":[{\"title\":\"Volvo EX30\",\"pros\":\"inte en lista\"}]}";
        assertThatThrownBy(() -> service().parseRecommendations(content))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("oväntat svar");
    }

    // --- buildCacheKey ---

    @Test
    void olikaMaxAlderGerOlikaCachenycklar() {
        GroqService s = service();
        String utan = s.buildCacheKey(prefs(300_000, "suv", true, 15_000, false, "el", "automat", "köp", null));
        String med = s.buildCacheKey(prefs(300_000, "suv", true, 15_000, false, "el", "automat", "köp", 5));
        assertThat(utan).isNotEqualTo(med);
    }

    @Test
    void nullFaltIPreferensernaKraschaInteCachenyckeln() {
        String key = service().buildCacheKey(prefs(300_000, "suv", true, 15_000, false, null, null, null, null));
        assertThat(key).contains("köp"); // budgetType null faller tillbaka på "köp"
    }

    // --- missingModels / configuredModels (hälsokoll mot Groqs /models-lista) ---

    private GroqService serviceMedModeller(String model, String chatModel) {
        return serviceMedModeller(model, chatModel, "");
    }

    private GroqService serviceMedModeller(String model, String chatModel, String watchedModels) {
        GroqService s = service();
        ReflectionTestUtils.setField(s, "model", model);
        ReflectionTestUtils.setField(s, "chatModel", chatModel);
        ReflectionTestUtils.setField(s, "watchedModels", watchedModels);
        return s;
    }

    @Test
    void ingaModellerSaknasNarBadaFinnsIListan() throws Exception {
        GroqService s = serviceMedModeller("qwen/qwen3.6-27b", "openai/gpt-oss-20b");
        String body = """
                {"data":[{"id":"qwen/qwen3.6-27b"},{"id":"openai/gpt-oss-20b"},{"id":"openai/gpt-oss-120b"}]}""";
        assertThat(s.missingModels(body)).isEmpty();
    }

    @Test
    void avveckladModellRapporterasSomSaknad() throws Exception {
        // Scenariot från llama-3.3-70b-avvecklingen: modellen försvinner ur /models-listan
        GroqService s = serviceMedModeller("llama-3.3-70b-versatile", "openai/gpt-oss-20b");
        String body = """
                {"data":[{"id":"openai/gpt-oss-20b"}]}""";
        assertThat(s.missingModels(body)).containsExactly("llama-3.3-70b-versatile");
    }

    @Test
    void tomModellistaGerBadaModellernaSomSaknade() throws Exception {
        GroqService s = serviceMedModeller("qwen/qwen3.6-27b", "openai/gpt-oss-20b");
        assertThat(s.missingModels("{\"data\":[]}"))
                .containsExactly("qwen/qwen3.6-27b", "openai/gpt-oss-20b");
    }

    @Test
    void sammaModellIBadaRollernaListasBaraEnGang() {
        GroqService s = serviceMedModeller("openai/gpt-oss-20b", "openai/gpt-oss-20b");
        assertThat(s.configuredModels()).containsExactly("openai/gpt-oss-20b");
    }

    @Test
    void bevakadeExtramodellerIngarIHalsokollen() throws Exception {
        // gpt-oss-120b används av Tag/VaderKlader som saknar egen hälsokoll — bevakas härifrån
        GroqService s = serviceMedModeller("qwen/qwen3.6-27b", "openai/gpt-oss-20b",
                "openai/gpt-oss-120b, openai/gpt-oss-20b");
        assertThat(s.configuredModels())
                .containsExactly("qwen/qwen3.6-27b", "openai/gpt-oss-20b", "openai/gpt-oss-120b");
        String utan120b = """
                {"data":[{"id":"qwen/qwen3.6-27b"},{"id":"openai/gpt-oss-20b"}]}""";
        assertThat(s.missingModels(utan120b)).containsExactly("openai/gpt-oss-120b");
    }

    // --- buildRateLimitError / buildGroqErrorMessage ---

    @Test
    void dagsgransMedRetrytidFormateras() {
        String body = "{\"error\":{\"message\":\"Rate limit reached for model, limit 1000 per day, try again in 2m59.56s\"}}";
        assertThat(service().buildRateLimitError(body))
                .contains("Dagsgränsen")
                .contains("3 minuter");
    }

    @Test
    void vanlig429UtanDagsgransBlirOverbelastad() {
        String body = "{\"error\":{\"message\":\"Rate limit reached, try again in 30s\"}}";
        assertThat(service().buildRateLimitError(body))
                .contains("överbelastad")
                .contains("1 minut");
    }

    @Test
    void oparsbar429KroppGerGenerisktMeddelande() {
        assertThat(service().buildRateLimitError("<html>502 Bad Gateway</html>"))
                .contains("en stund");
    }

    @Test
    void jsonValidateFailedBlirOfullstandigtSvar() {
        String body = "{\"error\":{\"code\":\"json_validate_failed\",\"message\":\"...\"}}";
        assertThat(service().buildGroqErrorMessage(400, body)).contains("ofullständigt");
    }

    @Test
    void ovrigaGroqFelInkluderarStatuskoden() {
        assertThat(service().buildGroqErrorMessage(500, "internal error"))
                .contains("500");
    }
}
