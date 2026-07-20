package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import com.caradvice.model.EvSpecDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock private IceConsumptionService iceConsumptionService;
    @Mock private FuelPriceService fuelPriceService;

    private GroqService service() {
        return new GroqService(expertInsightService, safetyRatingService,
                evSpecService, cargoSpecService, blocketPriceService, newCarPriceService,
                feedbackService, iceConsumptionService, fuelPriceService);
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

    @Test
    void kategorinFamiljebilFlaggasSomFamiljebilIPrompten() {
        // Familjekriteriet sitter på bilkategorin — passagerare 3 så att bara kategorin triggar
        CarPreferences familj = new CarPreferences(300_000, "familjebil", true, 15_000, "blandat",
                3, false, "el", null, "köp", null);
        assertThat(service().buildPrompt(familj))
                .contains("FAMILJEBIL")
                .contains("MG4/VW ID.4 eller större")
                .contains("ALDRIG småbil");
    }

    @Test
    void familjeAnvandningFlaggasSomFamiljebilIPrompten() {
        // Skarpt läge: "Renault Zoe (2023)" för familjekörning/300k — äldre inklistrade
        // WordPress-snippets skickar fortfarande usage "familj" och ska täckas
        CarPreferences familj = new CarPreferences(300_000, "elbil", true, 15_000, "familj",
                5, false, "el", null, "köp", null);
        assertThat(service().buildPrompt(familj))
                .contains("FAMILJEBIL")
                .contains("MG4/VW ID.4 eller större")
                .contains("ALDRIG småbil");
    }

    @Test
    void tvaPassagerarePendlingArInteFamiljeprofil() {
        CarPreferences pendlare = new CarPreferences(300_000, "elbil", true, 15_000, "pendling",
                2, false, "el", null, "köp", null);
        assertThat(GroqService.requiresFamilySizedCar(pendlare)).isFalse();
        assertThat(service().buildPrompt(pendlare)).doesNotContain("FAMILJEBIL");
    }

    // --- buildSystemPrompt ---

    @Test
    void expertkontextBifogasIslutet() {
        String sp = serviceMedPristabeller().buildSystemPrompt("EXPERTINSIKT-MARKÖR", "bensin");
        assertThat(sp).contains("EXPERTINSIKT-MARKÖR");
    }

    @Test
    void promptenForbjuderSmabilarSomFamiljebilOchKraverBudgetutnyttjande() {
        // 300k-familjebilssökning gav Dacia Spring för 150k — storleks- och budgetregler krävs
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp)
                .contains("FAMILJEBIL (kategori \"familjebil\", användning \"familj\" eller 4+ passagerare)")
                .contains("Dacia Spring")
                .contains("UTNYTTJA BUDGETEN");
    }

    @Test
    void promptenListarBepravadeFamiljebilar() {
        // Kuraterad lista: V60/V90, Octavia Combi, Ceed SW, Enyaq, Jogger — med säljargument
        String sp = serviceMedPristabeller().buildSystemPrompt("", "bensin");
        assertThat(sp)
                .contains("Volvo V60/V90")
                .contains("Octavia Combi")
                .contains("Ceed SW")
                .contains("Dacia Jogger")
                .contains("7 säten");
    }

    @Test
    void promptenPrioriterarEtableradeMarkenForeOkandaKinesiska() {
        // "europeiska bilar, inte kinesiska okända" — Zeekr/Xpeng/Leapmotor/BYD aldrig förstaval
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp)
                .contains("MÄRKESPRIORITET")
                .contains("aldrig som förstaval")
                .contains("PRISVÄRD RÄCKVIDD");
    }

    @Test
    void promptenSparrarArsmodellerForeLanseringen() {
        // AI:n föreslog "Kia EV2 (2023)" — modellen lanseras 2026 och finns inte begagnad
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp).contains("före modellens verkliga lansering").contains("Kia EV2");
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
                .contains("ALLTID EXAKT 3 OLIKA bilar")
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
    void sammaBilFleraGangerAvvisas() {
        // Skarpt läge: AI:n returnerade "Dacia Spring Electric 70" tre gånger —
        // ska trigga omförsöket med reservmodellen, inte visas för användaren
        String content = "{\"recommendations\":[" + GILTIG_BIL + "," + GILTIG_BIL + "," + GILTIG_BIL + "]}";
        assertThatThrownBy(() -> service().parseRecommendations(content))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("samma bil");
    }

    @Test
    void sammaModellOlikaArGiltigJamforelse() throws Exception {
        // "MG4 (2022)" vs "MG4 (2024)" är en legitim jämförelse — bara identiska titlar avvisas
        String bil2024 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volvo EX30 (2025)");
        List<CarRecommendation> r = service().parseRecommendations(
                "{\"recommendations\":[" + GILTIG_BIL + "," + bil2024 + "]}");
        assertThat(r).hasSize(2);
    }

    // --- requireFamilySizedCars (hård spärr mot småbil som familjebil) ---

    @Test
    void smabilTillFamiljeprofilAvvisasOchTriggarOmforsok() throws Exception {
        String zoe = GILTIG_BIL.replace("Volvo EX30 (2024)", "Renault Zoe (2023)");
        List<CarRecommendation> parsed = service().parseRecommendations("{\"recommendations\":[" + zoe + "]}");
        assertThatThrownBy(() -> GroqService.requireFamilySizedCars(parsed))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("för liten");
    }

    @Test
    void rymligaBilarPasserarFamiljesparren() throws Exception {
        String id4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "VW ID.4 (2023)");
        String mg4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "MG4 (2023)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + id4 + "," + mg4 + "]}");
        GroqService.requireFamilySizedCars(parsed); // ska inte kasta
        assertThat(parsed).hasSize(2);
    }

    // --- requireKnownModels (modellhallucinationsvakt mot cargo_spec/ev_spec/ice_consumption) ---

    @SuppressWarnings("unchecked")
    private void setKnownModels(GroqService s, java.util.Set<String>... tokenSets) {
        ReflectionTestUtils.setField(s, "knownModelTokenSets", List.of(tokenSets));
    }

    @Test
    void paahittatModellnamnAvvisasOchTriggarOmforsok() throws Exception {
        GroqService s = service();
        setKnownModels(s, java.util.Set.of("volvo", "v60"), java.util.Set.of("skoda", "octavia"));
        String fake = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volvo C70 (2019)");
        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + fake + "]}");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(s, "requireKnownModels", parsed))
                .hasMessageContaining("inte kunde verifieras");
    }

    @Test
    void trimvariantMedExtraOrdGodkannsMotBasmodell() throws Exception {
        // Databasen har "Skoda Octavia" — AI:ns "Octavia Combi" ska godkännas (övermängd)
        GroqService s = service();
        setKnownModels(s, java.util.Set.of("skoda", "octavia"));
        String combi = GILTIG_BIL.replace("Volvo EX30 (2024)", "Skoda Octavia Combi (2021)");
        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + combi + "]}");
        ReflectionTestUtils.invokeMethod(s, "requireKnownModels", parsed); // ska inte kasta
    }

    @Test
    void kortareTitelGodkannsMotDatabaspostMedExtraTrimord() throws Exception {
        // Databasen har "Peugeot e-208 50 kWh" — AI:ns kortare "Peugeot e-208" ska godkännas (delmängd)
        GroqService s = service();
        setKnownModels(s, java.util.Set.of("peugeot", "e", "208", "50", "kwh"));
        String p208 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Peugeot e-208 (2023)");
        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + p208 + "]}");
        ReflectionTestUtils.invokeMethod(s, "requireKnownModels", parsed); // ska inte kasta
    }

    @Test
    void tomWhitelistSlapperIgenomAllt() throws Exception {
        // Cachen inte laddad än (t.ex. första anropet) — släpp igenom hellre än att fälla korrekt
        GroqService s = service();
        String fake = GILTIG_BIL.replace("Volvo EX30 (2024)", "Fiat Multiplina (2022)");
        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + fake + "]}");
        ReflectionTestUtils.invokeMethod(s, "requireKnownModels", parsed); // ska inte kasta
    }

    @Test
    void modelTokensNormaliserarDiakritik() {
        java.util.Set<String> tokens = (java.util.Set<String>) ReflectionTestUtils.invokeMethod(
                GroqService.class, "modelTokens", "Škoda Octavia");
        assertThat(tokens).containsExactlyInAnyOrder("skoda", "octavia");
    }

    @Test
    void buildKnownModelTokenSetsSlarIhopAllaKallorOchFiltrerarEnordsposter() {
        GroqService s = service();
        when(cargoSpecService.findAllCarNames()).thenReturn(List.of("Volvo V60", "Ogiltig"));
        when(evSpecService.findAllCarNames()).thenReturn(List.of("Kia EV6"));
        when(iceConsumptionService.allModelNames()).thenReturn(java.util.Set.of("Toyota Corolla"));
        @SuppressWarnings("unchecked")
        List<java.util.Set<String>> tokenSets = (List<java.util.Set<String>>)
                ReflectionTestUtils.invokeMethod(s, "buildKnownModelTokenSets");
        assertThat(tokenSets).containsExactlyInAnyOrder(
                java.util.Set.of("volvo", "v60"), java.util.Set.of("kia", "ev6"), java.util.Set.of("toyota", "corolla"));
    }

    // --- enrichRecommendations: verifierade kWh/räckvidd-varianter ersätter AI:ns engineOptions-fritext ---

    @Test
    void verifieradeMotoralternativErsatterAiFritext() throws Exception {
        // Skarpt fall: AI:n gav EX30 fabricerade "58 kWh 150hk (420km), 77 kWh 200hk (540km)"
        GroqService s = service();
        EvSpecDto evSpec = new EvSpecDto(344, 292, 240, 1, "ladda varje dag", 51.0, 153, 11, 370_000, "", "EV", "LFP");
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(evSpec);
        when(evSpecService.verifiedEngineOptions(anyString())).thenReturn("51 kWh (344 km), 65 kWh (480 km)");

        String bil = GILTIG_BIL.replace("\"51 kWh 272hk (344km)\"", "\"58 kWh 150hk (420km), 77 kWh 200hk (540km)\"");
        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + bil + "]}");

        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);
        assertThat(result.get(0).engineOptions()).isEqualTo("51 kWh (344 km), 65 kWh (480 km)");
    }

    @Test
    void aiFritextBehallsUtanEvSpecTraff() throws Exception {
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + GILTIG_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);
        assertThat(result.get(0).engineOptions()).isEqualTo("51 kWh 272hk (344km)");
    }

    // --- enrichRecommendations: verifierad systemeffekt ersätter AI:ns hk-gissning ---

    @Test
    void verifieradSystemeffektErsatterAiGissning() throws Exception {
        // Skarpt fall: AI:n gav MG Marvel R "150hk" — riktig siffra (180) ska visas istället
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(180);

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + GILTIG_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);
        assertThat(result.get(0).horsepower()).isEqualTo(180);
    }

    @Test
    void aiGissningBehallsUtanVerifieradSystemeffekt() throws Exception {
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + GILTIG_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);
        assertThat(result.get(0).horsepower()).isEqualTo(272); // GILTIG_BIL:s AI-värde, oförändrat
    }

    @Test
    void feltypadeFaltGerBegripligtFelIstalletForKrasch() {
        // pros som sträng istället för array — schemafel ska ge användarvänligt fel, inte 500
        String content = "{\"recommendations\":[{\"title\":\"Volvo EX30\",\"pros\":\"inte en lista\"}]}";
        assertThatThrownBy(() -> service().parseRecommendations(content))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("oväntat svar");
    }

    // --- correctedPrice (Blocket-verkligheten vinner över AI:ns priskalkyl) ---

    @Test
    void aiPrisUnderBlocketIntervalletErsattsMedBlocketPriset() {
        // Skarpt läge: Kia EV6 fick 200 000–210 000 kr, Blocket börjar på 333 500 kr
        var blocket = new BlocketPriceService.PriceRange(333_500, 429_900, 50, "333 500 – 429 900 kr (50 annonser)");
        assertThat(GroqService.correctedPrice("200 000–210 000 kr", blocket, "Kia EV6 (2022)"))
                .isEqualTo("333 500–429 900 kr");
    }

    @Test
    void aiPrisOverBlocketIntervalletErsattsOcksa() {
        var blocket = new BlocketPriceService.PriceRange(150_000, 200_000, 30, "...");
        assertThat(GroqService.correctedPrice("280 000–320 000 kr", blocket, "VW Golf (2020)"))
                .isEqualTo("150 000–200 000 kr");
    }

    @Test
    void aiPrisSomOverlapparBlocketBehalls() {
        var blocket = new BlocketPriceService.PriceRange(300_000, 400_000, 20, "...");
        assertThat(GroqService.correctedPrice("350 000–380 000 kr", blocket, "Kia EV6 (2022)"))
                .isEqualTo("350 000–380 000 kr");
    }

    @Test
    void enAnnonsSkriverInteOverAiPriset() {
        // 1 annons kan vara fynd/felannons och saknar percentil-outlier-skydd i BlocketPriceService — litar på kalkylen
        var blocket = new BlocketPriceService.PriceRange(500_000, 550_000, 1, "...");
        assertThat(GroqService.correctedPrice("200 000–210 000 kr", blocket, "Volvo V60 (2021)"))
                .isEqualTo("200 000–210 000 kr");
    }

    @Test
    void tvaAnnonserRackerForAttSkrivaOverAiPriset() {
        // Tröskeln sänkt från 3 till 2 annonser — två oberoende fynd räcker för att lita på verkligheten
        var blocket = new BlocketPriceService.PriceRange(500_000, 550_000, 2, "...");
        assertThat(GroqService.correctedPrice("200 000–210 000 kr", blocket, "Volvo V60 (2021)"))
                .isEqualTo("500 000–550 000 kr");
    }

    @Test
    void utanBlocketDataBehallsAiPriset() {
        assertThat(GroqService.correctedPrice("200 000–210 000 kr", null, "Volvo V60 (2021)"))
                .isEqualTo("200 000–210 000 kr");
    }

    @Test
    void jamforelsepromptenKraverAttJamforelseordStammerMedSiffrorna() {
        // Skarpt läge: EV6:s con sa "mindre benutrymme (1006 mm vs 954 mm)" — tvärtemot siffrorna
        assertThat(serviceMedPristabeller().buildCompareSystemPrompt())
                .contains("SIFFERLOGIK")
                .contains("MER benutrymme");
    }

    @Test
    void jamforelsepromptenForbjuderFelDrivlina() {
        // Skarpt läge: MG Marvel R (ren elbil) kallades "laddhybrid med hög bränsleförbrukning"
        assertThat(serviceMedPristabeller().buildCompareSystemPrompt())
                .contains("DRIVLINA")
                .contains("ALDRIG hybrid/laddhybrid")
                .contains("Marvel R");
    }

    @Test
    void jamforelsepromptenInnehallerBadeIceOchEvNypriser() {
        // EV6 vs ID.4 fick fantasipriser (200k) — EV-nypristabellen saknades i jämförelseprompten
        assertThat(serviceMedPristabeller().buildCompareSystemPrompt())
                .contains("ICE-NYPRISTABELL-MARKÖR")
                .contains("EV-PRISTABELL-MARKÖR");
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
    void reservmodellenIngarIHalsokollen() {
        // qwen är preview-tier och numera reserv — en avveckling ska fortfarande larma
        GroqService s = serviceMedModeller("openai/gpt-oss-120b", "openai/gpt-oss-20b");
        ReflectionTestUtils.setField(s, "reserveModel", "qwen/qwen3.6-27b");
        assertThat(s.configuredModels())
                .containsExactly("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b");
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
