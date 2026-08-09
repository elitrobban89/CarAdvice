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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock private ElectricityPriceService electricityPriceService;
    @Mock private LeasingPriceService leasingPriceService;

    private GroqService service() {
        return new GroqService(expertInsightService, safetyRatingService,
                evSpecService, cargoSpecService, blocketPriceService, newCarPriceService,
                feedbackService, iceConsumptionService, fuelPriceService, electricityPriceService,
                leasingPriceService);
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
    void fyraPassagerareArInteFamiljeprofil() {
        // Fyra är formulärets DEFAULTVÄRDE — gick gränsen där var strängaste läget påslaget för
        // alla som inte rörde fältet. Live 2026-08-09: elbil/225 000 kr gav två kort med fyra
        // passagerare men tre med två (Zoe, Leaf, MG ZS EV), alltså just det billiga utbudet.
        CarPreferences fyra = new CarPreferences(225_000, "elbil", false, 15_000, "pendling",
                4, false, "el", null, "köp", null);
        assertThat(GroqService.requiresFamilySizedCar(fyra)).isFalse();
        assertThat(service().buildPrompt(fyra)).doesNotContain("FAMILJEBIL");
    }

    @Test
    void femPassagerareArFamiljeprofil() {
        CarPreferences fem = new CarPreferences(225_000, "elbil", false, 15_000, "pendling",
                5, false, "el", null, "köp", null);
        assertThat(GroqService.requiresFamilySizedCar(fem)).isTrue();
    }

    @Test
    void id3RaknasInteSomSmabil() throws Exception {
        // Golf-klass med fem säten — samma storleksklass som MG4, som prompten samtidigt
        // rekommenderar som familjeelbil. Förbudet var en motsägelse, inte en gräns.
        // Promptens sida av samma regel kollas i promptenForbjuderSmabilarSomFamiljebil...
        List<CarRecommendation> id3 = parsatSvarMed("Volkswagen ID.3 (2021)");
        GroqService.requireFamilySizedCars(id3); // ska inte kasta
        assertThat(id3).hasSize(1);
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
                .contains("FAMILJEBIL (kategori \"familjebil\", användning \"familj\" eller 5+ passagerare)")
                .contains("Dacia Spring")
                .contains("UTNYTTJA BUDGETEN")
                // ID.3 är Golf-klass med fem säten och stod på förbudslistan medan MG4 — samma
                // storleksklass — rekommenderades som familjeelbil längre ned i samma stycke
                .doesNotContain("VW ID.3");
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
    void laddhybridskategorinFarSkattebrasklapp() {
        // Formulärets drivmedelslista saknar "laddhybrid" (bensin/diesel/hybrid/el/spelar ingen
        // roll) — valet görs som KATEGORI, så brasklappen måste trigga på carCategory
        String sp = serviceMedPristabeller().buildSystemPrompt("", "spelar ingen roll", "laddhybrid");
        assertThat(sp)
                .contains("LADDHYBRIDSKATT")
                .contains("1 januari 2027")
                // viktigaste nyansen: appen föreslår mest begagnat, och begagnat påverkas inte
                .contains("begagnad laddhybrid påverkas inte")
                .contains("lång elräckvidd");
    }

    @Test
    void sjalvladdandeHybridArIngenElbil() {
        // "Hybrid (ej laddhybrid)" matchade wantsEv på delsträngen "hybrid": förfrågan fick
        // BEV-tvång OCH blev av med ICE-nypristabellen, trots att en HEV är en bensinbil
        String sp = serviceMedPristabeller().buildSystemPrompt("", "hybrid");
        assertThat(sp)
                .doesNotContain("ELBIL OBLIGATORISKT")
                .contains("ICE-NYPRISTABELL-MARKÖR")
                .doesNotContain("EV-PRISTABELL-MARKÖR");
    }

    @Test
    void laddhybridskategorinForbjuderInteLaddhybrider() {
        // Drivmedelsrutan DÖLJS när kategorin är elbil/laddhybrid men skickas ändå med sitt
        // gamla värde. Ett kvarglömt "el" gav då både BEV-tvång och laddhybridsbrasklapp i
        // samma prompt — modellen beordrades att aldrig föreslå det användaren bett om.
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el", "laddhybrid");
        assertThat(sp).contains("LADDHYBRIDSKATT").doesNotContain("ELBIL OBLIGATORISKT");
        // ren elbilsförfrågan ska fortfarande få BEV-tvånget
        assertThat(serviceMedPristabeller().buildSystemPrompt("", "el", "elbil"))
                .contains("ELBIL OBLIGATORISKT");
    }

    @Test
    void ovrigaKategorierFarIngenSkattebrasklapp() {
        assertThat(serviceMedPristabeller().buildSystemPrompt("", "bensin", "familjebil"))
                .doesNotContain("LADDHYBRIDSKATT");
        assertThat(serviceMedPristabeller().buildSystemPrompt("", "el", "elbil"))
                .doesNotContain("LADDHYBRIDSKATT");
        // tvåargumentsvarianten (utan kategori) ska bete sig som förut
        assertThat(serviceMedPristabeller().buildSystemPrompt("", "bensin"))
                .doesNotContain("LADDHYBRIDSKATT");
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

    // --- requirePureEvCars (ELBIL OBLIGATORISKT i kod, inte bara i prompten) ---

    private List<CarRecommendation> parsatSvarMed(String titel) throws Exception {
        return service().parseRecommendations(
                "{\"recommendations\":[" + GILTIG_BIL.replace("Volvo EX30 (2024)", titel) + "]}");
    }

    @Test
    void hybridITitelnAvvisasIRentElbilssok() throws Exception {
        // Skarpt fall 2026-08-09: 350 000-budgeten gav Prius + Niro Hybrid + CR-V Hybrid
        List<CarRecommendation> parsed = parsatSvarMed("Kia Niro Hybrid (2021)");
        assertThatThrownBy(() -> service().requirePureEvCars(parsed))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("inte är elbil");
    }

    @Test
    void forbranningsbilUtanDrivlineordFangasViaIceConsumption() throws Exception {
        // "Toyota Prius (2015)" säger ingenting om drivlinan i titeln — databasen får avgöra
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Toyota", "Prius 2.5 Hybrid 223 hk", "hybrid", 0.44));
        List<CarRecommendation> parsed = parsatSvarMed("Toyota Prius (2015)");
        assertThatThrownBy(() -> service().requirePureEvCars(parsed))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void tvetydigtModellnamnFallsAvenOmDetFinnsSomElbil() throws Exception {
        // Live 2026-08-09: elbilssök gav "Kia Niro (2021)". Namnet finns som HEV, PHEV OCH
        // elbil, och ev_spec:s fuzzy-matchning slog mot "Kia Niro EV" innan hybridträffen
        // provades. Utan drivlineord i titeln pekar namnet inte ut någon variant — och
        // tvetydigheten är skadlig i sig, Blocket-uppslaget matchar då hybridannonser.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Kia", "Niro 1.6 GDI HEV 141 hk", "hybrid", 0.45));
        List<CarRecommendation> parsed = parsatSvarMed("Kia Niro (2021)");
        assertThatThrownBy(() -> service().requirePureEvCars(parsed))
                .isInstanceOf(GroqService.RuleViolationException.class);
    }

    @Test
    void sammaModellMedEvITitelnSlapsIgenom() throws Exception {
        // "Kia Niro EV" har drivlineordet och når aldrig databasuppslaget — inga stubbar
        List<CarRecommendation> parsed = parsatSvarMed("Kia Niro EV (2021)");
        service().requirePureEvCars(parsed);
        assertThat(parsed).hasSize(1);
    }

    @Test
    void elbilUtanDrivlineordSlapsIgenomNarNamnetInteDelasMedForbranningsbil() throws Exception {
        // "Volvo EX30" har inget drivlineord i titeln och finns inte som bensin/diesel/hybrid,
        // alltså ingen tvetydighet att fälla på. ev_spec konsulteras inte längre här — se
        // tvetydigtModellnamnFallsAvenOmDetFinnsSomElbil för varför.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any())).thenReturn(null);
        List<CarRecommendation> parsed = parsatSvarMed("Volvo EX30 (2024)");
        service().requirePureEvCars(parsed); // ska inte kasta
        assertThat(parsed).hasSize(1);
    }

    @Test
    void okandBilSlapsIgenomFailOpen() throws Exception {
        // Ingen träff i ice_consumption: inget bevis för förbränning ⇒ ingen fällning.
        // Vakten får aldrig kasta en riktig elbil bara för att whitelisten är ofullständig.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any())).thenReturn(null);
        List<CarRecommendation> parsed = parsatSvarMed("Leapmotor B10 (2025)");
        service().requirePureEvCars(parsed);
        assertThat(parsed).hasSize(1);
    }

    @Test
    void elbilstitelMedEgetDrivlineordBehoverIngenDatabas() throws Exception {
        // "ev" i titeln räcker — inga stubbar, alltså rörs varken ev_spec eller ice_consumption
        List<CarRecommendation> parsed = parsatSvarMed("Kia Niro EV (2019)");
        service().requirePureEvCars(parsed);
        assertThat(parsed).hasSize(1);
    }

    @Test
    void regelbrottetBarMedDeElbilarSomFannsISvaret() throws Exception {
        // Mjuka vägen: hellre ett kort som stämmer med sökningen än ett felmeddelande.
        // MG4 saknar drivlineord men finns inte som förbränningsbil, så den släpps igenom;
        // Niro Hybrid fälls redan på titelordet och når aldrig databasen.
        String mg4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "MG4 (2021)");
        String niro = GILTIG_BIL.replace("Volvo EX30 (2024)", "Kia Niro Hybrid (2021)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + mg4 + "," + niro + "]}");

        assertThatThrownBy(() -> service().requirePureEvCars(parsed))
                .isInstanceOf(GroqService.RuleViolationException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> assertThat(e.kvar()).hasSize(1)
                        .allSatisfy(r -> assertThat(r.title()).isEqualTo("MG4 (2021)")));
    }

    @Test
    void heltHybridsvarLamnarIngaElbilarAttVisa() throws Exception {
        // Inget att falla tillbaka på ⇒ felet är det enda ärliga svaret, och getRecommendation
        // kastar vidare i stället för att visa tre hybrider på ett elbilssök.
        String prius = GILTIG_BIL.replace("Volvo EX30 (2024)", "Toyota Prius Hybrid (2020)");
        String niro = GILTIG_BIL.replace("Volvo EX30 (2024)", "Kia Niro Hybrid (2021)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + prius + "," + niro + "]}");

        assertThatThrownBy(() -> service().requirePureEvCars(parsed))
                .isInstanceOf(GroqService.RuleViolationException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> assertThat(e.kvar()).isEmpty());
    }

    @Test
    void familjesparrenBarMedDeRymligaBilarna() throws Exception {
        // Generaliserat mönster: varje regelvakt bär med sig det som klarade regeln, inte bara
        // drivmedelsvakten. Ett brott bland tre bilar kostade förut hela svaret.
        String zoe = GILTIG_BIL.replace("Volvo EX30 (2024)", "Renault Zoe (2023)");
        String id4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "VW ID.4 (2023)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + zoe + "," + id4 + "]}");

        assertThatThrownBy(() -> GroqService.requireFamilySizedCars(parsed))
                .isInstanceOf(GroqService.RuleViolationException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> assertThat(e.kvar()).hasSize(1)
                        .allSatisfy(r -> assertThat(r.title()).isEqualTo("VW ID.4 (2023)")));
    }

    // --- requireRealisticModelYears (årsmodellvakten) ---

    @Test
    void arsmodellForeLanseringAvvisas() throws Exception {
        // Skarpt fall 2026-08-09: "Kia EV3 (2022)" — EV3 lanserades 2024
        List<CarRecommendation> parsed = parsatSvarMed("Kia EV3 (2022)");
        assertThatThrownBy(() -> GroqService.requireRealisticModelYears(parsed))
                .isInstanceOf(GroqService.RuleViolationException.class)
                .hasMessageContaining("årsmodell som inte finns");
    }

    @Test
    void arsmodellEfterLanseringSlapsIgenom() throws Exception {
        List<CarRecommendation> parsed = parsatSvarMed("Kia EV3 (2025)");
        GroqService.requireRealisticModelYears(parsed); // ska inte kasta
        assertThat(parsed).hasSize(1);
    }

    @Test
    void modellUtanforListanOchTitelUtanArtalSlapsIgenom() throws Exception {
        // Fail open: vakten kräver positivt bevis, annars faller riktiga bilar
        GroqService.requireRealisticModelYears(parsatSvarMed("Volkswagen Golf (2009)"));
        GroqService.requireRealisticModelYears(parsatSvarMed("Kia EV3"));
    }

    @Test
    void arsmodellvaktenBarMedDeGiltigaBilarna() throws Exception {
        String ev3 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Kia EV3 (2022)");
        String mg4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "MG4 (2022)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + ev3 + "," + mg4 + "]}");
        assertThatThrownBy(() -> GroqService.requireRealisticModelYears(parsed))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> assertThat(e.kvar()).hasSize(1)
                        .allSatisfy(r -> assertThat(r.title()).isEqualTo("MG4 (2022)")));
    }

    @Test
    void lanseringsaretLasesAvenUrTrimvariantOchUtanParentes() {
        assertThat(GroqService.launchYearFor("Kia EV4 Long Range (2026)")).isEqualTo(2025);
        assertThat(GroqService.launchYearFor("Volvo EX30 2024")).isEqualTo(2023);
        assertThat(GroqService.launchYearFor("Volkswagen Passat GTE (2018)")).isEqualTo(2015);
        assertThat(GroqService.launchYearFor("Nissan Leaf (2018)")).isNull();
    }

    @Test
    void regelbrottetBarMedSigRattelsenTillAin() throws Exception {
        // Rättelseförsöket behöver två saker som felmeddelandet inte innehåller: bilarna vid
        // namn och regeln formulerad som en INSTRUKTION. Skarpt fall 2026-08-09: elbil för
        // 175 000 kr gav enbart hybrider i båda omgångarna och därmed HTTP 500.
        String prius = GILTIG_BIL.replace("Volvo EX30 (2024)", "Toyota Prius Hybrid (2020)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + prius + "]}");

        assertThatThrownBy(() -> service().requirePureEvCars(parsed))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> {
                    assertThat(e.kvar()).isEmpty();
                    assertThat(e.avvisade()).containsExactly("Toyota Prius Hybrid (2020)");
                    assertThat(e.rattelse()).contains("RENA batterielbilar");
                });
    }

    @Test
    void allaRegelvakterFormulerarSinRattelse() throws Exception {
        // Utan rättelsetext hoppas rättelseförsöket över — då hade vakten tystnat i det tysta
        assertThatThrownBy(() -> GroqService.requireFamilySizedCars(parsatSvarMed("Renault Zoe (2023)")))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> assertThat(e.rattelse()).contains("familjestora"));
        assertThatThrownBy(() -> GroqService.requireRealisticModelYears(parsatSvarMed("Kia EV3 (2022)")))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(GroqService.RuleViolationException.class))
                .satisfies(e -> assertThat(e.rattelse()).contains("årsmodell före modellens lansering"));
    }

    @Test
    void felmeddelandetSagerVadSomGarAttAndra() {
        // "Försök igen" är ett dåligt råd när samma sökning misslyckas varje gång
        RuntimeException ut = GroqService.medRadOmKriterier(
                new RuntimeException("AI-svaret blev ofullständigt. Försök igen."));
        assertThat(ut.getMessage())
                .contains("AI-svaret blev ofullständigt.")
                .contains("högre budget")
                .contains("färre passagerare");
    }

    @Test
    void vaktenGallerBaraRentElbilssok() {
        // Delsträngsfällan: både "diesel" och "spelar ingen roll" innehåller "el"
        assertThat(GroqService.fuelIntent("el", "elbil").pureEv()).isTrue();
        assertThat(GroqService.fuelIntent("diesel", "suv").pureEv()).isFalse();
        assertThat(GroqService.fuelIntent("spelar ingen roll", "suv").pureEv()).isFalse();
        assertThat(GroqService.fuelIntent("hybrid", "suv").pureEv()).isFalse();
        assertThat(GroqService.fuelIntent("bensin", "smaabil").pureEv()).isFalse();
        // Kvarglömt "el" i den dolda drivmedelsrutan får inte ge BEV-tvång åt en laddhybrid
        assertThat(GroqService.fuelIntent("el", "laddhybrid").pureEv()).isFalse();
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

    // --- enrichRecommendations: verifierad hk/motorbeteckning ersätter AI:ns gissning för ICE-bilar ---

    private static final String ICE_BIL = """
            {"title":"Volkswagen Golf (2020)","price":"180 000–210 000 kr",
             "whyRecommended":"Pålitlig familjebil","pros":["rymlig","billig i drift","bra andrahandsvärde"],
             "con":"tråkig design","fitSummary":"passar pendlaren","expertOpinion":"Mjuk och tyst.",
             "horsepower":999,"engineOptions":"1.4 TFSI 999hk manuell",
             "fuelSpec":{"consumptionLiterPerMil":0.65,"gearbox":"Manuell","horsepower":999,"engineVolumeLiters":1.4}}""";

    @Test
    void verifieradHkOchMotorbeteckningErsatterAiGissningForIceBil() throws Exception {
        // Skarpt fall: AI:n gissar fel hk (999) — ice_consumption-varianten bär riktig hk och beteckning
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Volkswagen", "Golf 1.5 TSI 150 hk", "bensin", 0.55));

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + ICE_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);

        CarRecommendation r = result.get(0);
        assertThat(r.horsepower()).isEqualTo(150);
        assertThat(r.fuelSpec().horsepower()).isEqualTo(150); // frontend visar egen chip för detta fält
        assertThat(r.engineOptions()).isEqualTo("1.5 TSI 150 hk"); // modellordet ("Golf") strippat
    }

    @Test
    void aiGissningBehallsForIceBilUtanVerifieradVariant() throws Exception {
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any())).thenReturn(null);

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + ICE_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);

        CarRecommendation r = result.get(0);
        assertThat(r.horsepower()).isEqualTo(999);
        assertThat(r.fuelSpec().horsepower()).isEqualTo(999);
        assertThat(r.engineOptions()).isEqualTo("1.4 TFSI 999hk manuell");
    }

    @Test
    void feltypadeFaltGerBegripligtFelIstalletForKrasch() {
        // pros som sträng istället för array — schemafel ska ge användarvänligt fel, inte 500
        String content = "{\"recommendations\":[{\"title\":\"Volvo EX30\",\"pros\":\"inte en lista\"}]}";
        assertThatThrownBy(() -> service().parseRecommendations(content))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("oväntat svar");
    }

    // --- exceedsBudgetCeiling (budgeten kontrollerad mot riktiga annonser, inte bara promptad) ---

    @Test
    void bilLangtOverBudgetFallerPaTaket() {
        // Live-fynd: budget 275 000 gav Kia EV3 som börjar på 359 000 kr — 84 000 över
        var blocket = new BlocketPriceService.PriceRange(359_000, 425_000, 40, "...");
        assertThat(GroqService.exceedsBudgetCeiling(blocket, 275_000)).isTrue();
    }

    @Test
    void bilStraxOverBudgetSlapperIgenom() {
        // EX30 på 300 000 mot 275 000-budget är +25 000 — inom marginalen och godkänd av användaren
        var blocket = new BlocketPriceService.PriceRange(300_000, 380_000, 40, "...");
        assertThat(GroqService.exceedsBudgetCeiling(blocket, 275_000)).isFalse();
    }

    @Test
    void taketGarVidExakt30000Over() {
        int budget = 275_000;
        var precisPa = new BlocketPriceService.PriceRange(budget + 30_000, 400_000, 10, "...");
        var precisOver = new BlocketPriceService.PriceRange(budget + 30_001, 400_000, 10, "...");
        assertThat(GroqService.exceedsBudgetCeiling(precisPa, budget)).isFalse();
        assertThat(GroqService.exceedsBudgetCeiling(precisOver, budget)).isTrue();
    }

    @Test
    void billigBilFallerAldrigPaTaket() {
        // Bara ett tak, inget golv — en bil under budget är fortfarande köpbar
        var blocket = new BlocketPriceService.PriceRange(120_000, 160_000, 25, "...");
        assertThat(GroqService.exceedsBudgetCeiling(blocket, 275_000)).isFalse();
    }

    @Test
    void enAnnonsFallerIngenBil() {
        // Samma tröskel som correctedPrice: en ensam fel-/scamannons ska inte kunna fälla en bil
        var blocket = new BlocketPriceService.PriceRange(900_000, 900_000, 1, "...");
        assertThat(GroqService.exceedsBudgetCeiling(blocket, 275_000)).isFalse();
    }

    @Test
    void utanBlocketDataGallerIngetTak() {
        assertThat(GroqService.exceedsBudgetCeiling(null, 275_000)).isFalse();
    }

    @Test
    void taketMatsMotBilligasteAnnonsenInteSnittet() {
        // Intervallet spänner över taket: billigaste exemplaret går att köpa, alltså godkänd
        var blocket = new BlocketPriceService.PriceRange(290_000, 500_000, 30, "...");
        assertThat(GroqService.exceedsBudgetCeiling(blocket, 275_000)).isFalse();
    }

    // --- mergeWithinBudget (vilka bilar som överlever budgettaket) ---

    private static CarRecommendation bil(String titel) {
        return new CarRecommendation(titel, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static BlocketPriceService.PriceRange range(int minKr) {
        return new BlocketPriceService.PriceRange(minKr, minKr + 80_000, 40, "...");
    }

    /** Bil med verifierat nypris ur ev_spec — referensen som gäller när Blocket är tyst. */
    private static CarRecommendation bilMedNypris(String titel, int nyprisKr) {
        var evSpec = new EvSpecDto(572, 486, 400, 12, "ladda var 12:e dag", 78.0, 135, 11,
                nyprisKr, "Utmärkt prisvärdhet", "EV", "LFP/NMC");
        return new CarRecommendation(titel, null, null, null, null, null, null, null, evSpec,
                null, null, null, null, null);
    }

    @Test
    void forDyrBilTasBortAvenNarOmforsoketIntearBattre() {
        // Live-fynd: omförsöket gav ingen förbättring och hela ursprungssvaret behölls, vilket
        // slappte igenom Volvo EX40 pa 439 000 kr mot en 275 000-budget — 164 000 over taket
        var original = List.of(bil("Volvo EX40 (2022)"), bil("Kia Niro EV (2022)"));
        var ranges = Map.of("Volvo EX40 (2022)", range(439_000), "Kia Niro EV (2022)", range(244_000));
        var retried = List.of(bil("Volvo EX40 (2022)"));

        var result = GroqService.mergeWithinBudget(retried, ranges, original, ranges, 275_000);

        assertThat(result).extracting(CarRecommendation::title).containsExactly("Kia Niro EV (2022)");
    }

    @Test
    void taketGallerAvenNybilssokEftersomBegagnatprisetArEnUndreGrans() {
        // Live 2026-08-07: MG4 (billigaste annons 249 900 kr) foreslogs for en 200 000-budget
        // utan att nagon sparr utloste. Nybilssok hoppade over kontrollen helt, men slutsatsen
        // haller i EN riktning: kostar billigaste BEGAGNADE exemplaret mer an taket kan en NY
        // omojligt kosta mindre. 200 000 + 30 000 = 230 000 < 249 900.
        var mg4 = new BlocketPriceService.PriceRange(249_900, 307_990, 48, "...");
        assertThat(GroqService.exceedsBudgetCeiling(mg4, 200_000)).isTrue();

        // och bilarna anvandaren faktiskt kan kopa for pengarna faller inte pa taket
        var zsEv = new BlocketPriceService.PriceRange(144_900, 234_900, 34, "...");
        var leaf = new BlocketPriceService.PriceRange(129_800, 189_800, 44, "...");
        assertThat(GroqService.exceedsBudgetCeiling(zsEv, 200_000)).isFalse();
        assertThat(GroqService.exceedsBudgetCeiling(leaf, 200_000)).isFalse();
    }

    // --- nypriset som referens nar Blocket ar tyst ---

    @Test
    void nyprisetFallerBilenNarBlocketSaknarAnnonser() {
        // Live 2026-08-07: Kia EV3 foreslogs for en 200 000-budget med AI-priset
        // "170 000-190 000 kr" och NOLL annonser. Vart eget ev_spec bar 370 000 kr pa samma
        // kort, men bade correctedPrice och taket kraver annonser — ingen sparr utloste.
        var ev3 = bilMedNypris("Kia EV3 (2020)", 370_000);
        assertThat(GroqService.exceedsBudgetCeiling(ev3, null, 200_000)).isTrue();

        var golv = GroqService.verifiedFloor(ev3, null);
        assertThat(golv.kr()).isEqualTo(370_000);
        assertThat(golv.fromBlocket()).isFalse();   // far aldrig presenteras som ett annonspris
    }

    @Test
    void blocketVinnerOverNypriset() {
        // Begagnatmarknaden ar mattstocken: en Leaf som kostade 290 000 ny gar att kopa for
        // 129 800 idag, och da ar det den siffran som avgor.
        var leaf = bilMedNypris("Nissan Leaf (2018)", 290_000);
        assertThat(GroqService.exceedsBudgetCeiling(leaf, range(129_800), 200_000)).isFalse();
        assertThat(GroqService.verifiedFloor(leaf, range(129_800)).fromBlocket()).isTrue();
    }

    @Test
    void enAnnonsRackerInteSomMattstockUtanFallerTillbakaPaNypriset() {
        // Samma troskel som correctedPrice: en ensam annons ar ingen marknad
        var ev3 = bilMedNypris("Kia EV3 (2020)", 370_000);
        var enAnnons = new BlocketPriceService.PriceRange(150_000, 150_000, 1, "...");
        assertThat(GroqService.exceedsBudgetCeiling(ev3, enAnnons, 200_000)).isTrue();
    }

    @Test
    void utanBadeAnnonserOchNyprisFallerIngenBil() {
        assertThat(GroqService.exceedsBudgetCeiling(bil("Ovanlig Modell (2022)"), null, 200_000)).isFalse();
        assertThat(GroqService.verifiedFloor(bil("Ovanlig Modell (2022)"), null)).isNull();
    }

    @Test
    void bilSomBaraHarNyprisUnderTaketStarKvar() {
        // Bara ett tak, inget golv — nypriset ska inte kasta ut en bil som ryms i budgeten
        var billig = bilMedNypris("Dacia Spring (2023)", 190_000);
        assertThat(GroqService.exceedsBudgetCeiling(billig, null, 200_000)).isFalse();
    }

    @Test
    void nyprisfalldBilForsvinnerUrSammanslagningen() {
        var original = List.of(bilMedNypris("Kia EV3 (2020)", 370_000), bil("MG ZS EV (2020)"));
        var ranges = Map.of("MG ZS EV (2020)", range(139_900));

        var result = GroqService.mergeWithinBudget(List.of(), Map.of(), original, ranges, 200_000);

        assertThat(result).extracting(CarRecommendation::title).containsExactly("MG ZS EV (2020)");
    }

    // --- prisraden nar annonser saknas ---

    @Test
    void utanAnnonserRaknasPrisetUrNypriset() {
        // Kia EV3 stod pa "170 000-190 000 kr" med noll annonser medan ev_spec bar 370 000 kr.
        // Utan annonser kan correctedPrice inte saga emot — da raknar vi i stallet.
        assertThat(GroqService.estimatedPrice(370_000, 2025, 2026, false)).isEqualTo("ca 315 000 kr");
        assertThat(GroqService.estimatedPrice(370_000, 2022, 2026, false)).isEqualTo("ca 211 000 kr");
    }

    @Test
    void nybilssokFarNyprisetRaktAv() {
        // Ber anvandaren om en ny bil ar nypriset vad hen faktiskt betalar
        assertThat(GroqService.estimatedPrice(370_000, 2025, 2026, true)).isEqualTo("fr. 370 000 kr");
        assertThat(GroqService.estimatedPrice(370_000, null, 2026, true)).isEqualTo("fr. 370 000 kr");
    }

    @Test
    void aldstaKoefficientenGallerForGamlaBilar() {
        // Kurvan slutar pa 8+ ar (0,34) — en 15 ar gammal bil skrivs inte ned mer
        assertThat(GroqService.estimatedPrice(300_000, 2018, 2026, false))
                .isEqualTo(GroqService.estimatedPrice(300_000, 2011, 2026, false));
    }

    @Test
    void utanArsmodellRaknasIngetBegagnatpris() {
        // Ingen alder att skriva ned med — en oskriven siffra ar battre an en pahittad
        assertThat(GroqService.estimatedPrice(370_000, null, 2026, false)).isNull();
        assertThat(GroqService.estimatedPrice(0, 2022, 2026, false)).isNull();
    }

    // --- nybilssok mater mot nypriset, begagnatsok mot annonserna ---

    @Test
    void nybilssokDomerPaNyprisetInteAnnonserna() {
        // Live 2026-08-07: 200 000 kr + "ny" gav Nissan Leaf 2018-2020 med annonser fran
        // 129 800 kr. Bilen gick igenom taket pa ett begagnatpris i ett NYBILSSOK, och
        // anvandaren fick inget besked om att budgeten inte racker till en ny bil.
        var leaf = bilMedNypris("Nissan Leaf (2020)", 290_000);
        var annonser = range(129_800);

        assertThat(GroqService.exceedsBudgetCeiling(leaf, annonser, 290_000, 200_000, false, true)).isTrue();
        // samma bil i ett begagnatsok ar helt korrekt — dar ar annonspriset mattstocken
        assertThat(GroqService.exceedsBudgetCeiling(leaf, annonser, 290_000, 200_000, false, false)).isFalse();
    }

    @Test
    void blocketArSekundartINybilssok() {
        // Saknas nypris faller nybilssoket tillbaka pa annonserna — battre an ingen dom alls
        var utanNypris = bil("Ovanlig Modell (2022)");

        assertThat(GroqService.exceedsBudgetCeiling(utanNypris, range(400_000), null, 200_000, false, true)).isTrue();
        assertThat(GroqService.exceedsBudgetCeiling(utanNypris, range(150_000), null, 200_000, false, true)).isFalse();
    }

    @Test
    void nyprisetKanKommaFranIceTabellen() {
        // Bensinbilar har ingen evSpec — nypriset kommer da ur new_car_price via kartan
        var golf = bil("Volkswagen Golf (2022)");

        assertThat(GroqService.exceedsBudgetCeiling(golf, range(150_000), 320_000, 200_000, false, true)).isTrue();
        var golv = GroqService.verifiedFloor(golf, range(150_000), 320_000, true);
        assertThat(golv.kr()).isEqualTo(320_000);
        assertThat(golv.fromBlocket()).isFalse();
    }

    @Test
    void banderollensSiffraArNyprisINybilssok() {
        // Texten byter till "som ny" — ett begagnatpris dar hade svarat pa en annan fraga
        var recs = List.of(bilMedNypris("Nissan Leaf (2020)", 290_000),
                bilMedNypris("Kia EV3 (2025)", 370_000));
        var ranges = Map.of("Nissan Leaf (2020)", range(129_800), "Kia EV3 (2025)", range(359_000));

        assertThat(GroqService.cheapest(recs, ranges, Map.of(), true)).isEqualTo(290_000);
        assertThat(GroqService.cheapest(recs, ranges)).isEqualTo(129_800);   // begagnatsok: oforandrat
    }

    // --- tre olika modeller garanteras ---

    @Test
    void utrustningsnivaGorInteEnBilTillTvaModeller() {
        // Live 2026-08-07: "Volkswagen ID.4 (2024)" och "Volkswagen ID.4 Pro (2022)" kom i
        // samma svar. Dedupen fanns bara i budgetomforsoket, som inte kordes.
        assertThat(GroqService.sameModel("Volkswagen ID.4 (2024)", "Volkswagen ID.4 Pro (2022)")).isTrue();
        assertThat(GroqService.sameModel("Kia EV6 (2023)", "Kia EV6 GT-Line (2023)")).isTrue();
        assertThat(GroqService.sameModel("Škoda Enyaq (2021)", "Škoda Enyaq iV (2023)")).isTrue();
        assertThat(GroqService.sameModel("Volkswagen ID.4 (2021)", "Volkswagen ID.4 (2022)")).isTrue();
    }

    @Test
    void olikaModellerFarInteSlasIhop() {
        assertThat(GroqService.sameModel("Tesla Model Y (2022)", "Tesla Model 3 (2022)")).isFalse();
        assertThat(GroqService.sameModel("Volvo EX30 (2023)", "Volvo EX40 (2023)")).isFalse();
        assertThat(GroqService.sameModel("Volkswagen ID.4 (2022)", "Volkswagen ID.5 (2022)")).isFalse();
        // Jamforelsen gar pa hela ord: EX30 ar ingen inledning av EX300
        assertThat(GroqService.sameModel("Volvo EX30 (2023)", "Volvo EX300 (2023)")).isFalse();
    }

    @Test
    void dubbletterTasBortIOrdning() {
        var recs = List.of(bil("Volkswagen ID.4 (2024)"), bil("MG4 (2023)"),
                bil("Volkswagen ID.4 Pro (2022)"));

        assertThat(GroqService.distinctModels(recs)).extracting(CarRecommendation::title)
                .containsExactly("Volkswagen ID.4 (2024)", "MG4 (2023)");
    }

    @Test
    void treOlikaModellerRorsInte() {
        var recs = List.of(bil("Volkswagen ID.4 (2024)"), bil("MG4 (2023)"), bil("Kia EV6 (2022)"));

        assertThat(GroqService.distinctModels(recs)).hasSize(3);
    }

    // --- leasingtaket (kr/man mot kr/man) ---

    @Test
    void leasingTaketMatsIKronorPerManad() {
        // Live 2026-08-07: Kia EV6 GT-Line foreslogs pa 8 295 kr/man mot en 5 000-budget
        // utan att nagon sparr slog till — leasing stod helt utanfor taket
        var ev6 = new BlocketPriceService.PriceRange(8_295, 8_295, 2, "...");
        assertThat(GroqService.exceedsBudgetCeiling(bil("Kia EV6 GT-Line (2023)"), ev6, 5_000, true)).isTrue();

        // Enyaq pa 4 850-4 980 kr/man ryms i samma budget
        var enyaq = new BlocketPriceService.PriceRange(4_850, 4_980, 2, "...");
        assertThat(GroqService.exceedsBudgetCeiling(bil("Škoda Enyaq iV 80 (2023)"), enyaq, 5_000, true)).isFalse();
    }

    @Test
    void enEndaLeasingannonsRackerForAttFallaBilen() {
        // Live 2026-08-07 pa 468a3da: EV6 GT-Line stod kvar pa 8 295 kr/man mot 5 000-budget
        // eftersom kravet pa tva annonser arvdes fran koplaget. Leasingannonser laggs av
        // bilhandlare och utbudet per modell ar tunt — en annons ar ett riktigt prisbesked.
        var enAnnons = new BlocketPriceService.PriceRange(8_295, 8_295, 1, "...");

        assertThat(GroqService.exceedsBudgetCeiling(bil("Kia EV6 GT-Line (2023)"), enAnnons, 5_000, true)).isTrue();
        // ... men priset pa kortet skrivs inte om pa en ensam annons
        assertThat(GroqService.correctedPrice("5 000–5 600 kr/mån", enAnnons, "Kia EV6 GT-Line (2023)", true))
                .isEqualTo("5 000–5 600 kr/mån");
    }

    @Test
    void enEndaAnnonsFallerFortfarandeIngenKopbil() {
        // Koplaget ar ororet: dar finns privatannonser och scamrisken ar verklig
        var enAnnons = new BlocketPriceService.PriceRange(900_000, 900_000, 1, "...");

        assertThat(GroqService.exceedsBudgetCeiling(bil("Bil (2022)"), enAnnons, 275_000, false)).isFalse();
    }

    @Test
    void leasingMarginalenAr500KronorInte30000() {
        // Kopmarginalen hade gjort taket meningslost: en 5 000-budget skulle rymma allt
        var precisPa = new BlocketPriceService.PriceRange(5_500, 6_000, 5, "...");
        var precisOver = new BlocketPriceService.PriceRange(5_501, 6_000, 5, "...");
        assertThat(GroqService.exceedsBudgetCeiling(bil("Bil (2023)"), precisPa, 5_000, true)).isFalse();
        assertThat(GroqService.exceedsBudgetCeiling(bil("Bil (2023)"), precisOver, 5_000, true)).isTrue();
    }

    @Test
    void nyprisetFarAldrigDomaILeasinglage() {
        // ev_spec bar bilens pris i kronor. Mot en manadsbudget hade det fallt varenda bil.
        var ev3 = bilMedNypris("Kia EV3 (2025)", 370_000);

        assertThat(GroqService.exceedsBudgetCeiling(ev3, null, 5_000, true)).isFalse();
        assertThat(GroqService.exceedsBudgetCeiling(ev3, null, 5_000, false)).isTrue();
    }

    @Test
    void forDyrLeasingbilForsvinnerUrSammanslagningen() {
        var original = List.of(bil("Kia EV6 GT-Line (2023)"), bil("Škoda Enyaq iV 80 (2023)"));
        var ranges = Map.of("Kia EV6 GT-Line (2023)", new BlocketPriceService.PriceRange(8_295, 8_295, 2, "..."),
                "Škoda Enyaq iV 80 (2023)", new BlocketPriceService.PriceRange(4_850, 4_980, 2, "..."));

        var result = GroqService.mergeWithinBudget(List.of(), Map.of(), original, ranges, 5_000, true);

        assertThat(result).extracting(CarRecommendation::title).containsExactly("Škoda Enyaq iV 80 (2023)");
    }

    @Test
    void leasingprisetRattasIKronorPerManad() {
        // Samma jamforelse som for kop, men enheten far inte tappas bort pa vagen
        var leasing = new BlocketPriceService.PriceRange(4_850, 4_980, 9, "...");

        assertThat(GroqService.correctedPrice("2 500–3 000 kr/mån", leasing, "Enyaq (2023)", true))
                .isEqualTo("4 850–4 980 kr/mån");
        assertThat(GroqService.correctedPrice("4 900–5 100 kr/mån", leasing, "Enyaq (2023)", true))
                .isEqualTo("4 900–5 100 kr/mån");
    }

    @Test
    void leasingprompenBerOmManadskostnad() {
        var leasingPrefs = new CarPreferences(5_000, "elbil", true, 15_000, "familj", 5, true,
                "el", "spelar ingen roll", "leasing", null);
        var kopPrefs = new CarPreferences(300_000, "elbil", true, 15_000, "familj", 5, false,
                "el", "spelar ingen roll", "köp", null);

        assertThat(service().buildPrompt(leasingPrefs)).contains("kr/mån");
        assertThat(service().buildPrompt(kopPrefs)).doesNotContain("kr/mån");

        // Privatleasing tecknas pa en ny bil — "Škoda Enyaq iV 80 (2023)" gar inte att leasa
        int arIar = java.time.Year.now().getValue();
        assertThat(service().buildPrompt(leasingPrefs))
                .contains("PRIVATLEASING GÄLLER NYA BILAR")
                .contains(String.valueOf(arIar));
        assertThat(service().buildPrompt(kopPrefs)).doesNotContain("PRIVATLEASING");
    }

    // --- cheapest (vad banderollen säger att bilen faktiskt kostar) ---

    @Test
    void banderollenTarAldrigEttNyprisSomAnnonspris() {
        // Texten lyder "... pa Blocket just nu" — ett nypris dar vore en ren losning
        var recs = List.of(bilMedNypris("Kia EV3 (2020)", 370_000));
        assertThat(GroqService.cheapest(recs, Map.of())).isNull();
    }

    @Test
    void billigasteVerkligaPrisetPlockasUrUrvalet() {
        // Live-fynd 2026-08-07: budget 100 000 kr + max 3 år gav MG4 fr. 249 900, Enyaq fr.
        // 374 900 och EV6 fr. 349 000. Kriterierna gick inte ihop, omförsöket hittade inget
        // inom taket, och korten visades utan att någonstans säga varför de var för dyra.
        var recs = List.of(bil("MG4 (2023)"), bil("Škoda Enyaq (2022)"), bil("Kia EV6 (2022)"));
        var ranges = Map.of("MG4 (2023)", range(249_900),
                "Škoda Enyaq (2022)", range(374_900),
                "Kia EV6 (2022)", range(349_000));

        assertThat(GroqService.cheapest(recs, ranges)).isEqualTo(249_900);
    }

    @Test
    void billigastePrisetIgnorerarBilarUtanBlocketData() {
        var recs = List.of(bil("Okänd bil (2020)"), bil("MG4 (2023)"));
        var ranges = Map.of("MG4 (2023)", range(249_900));

        assertThat(GroqService.cheapest(recs, ranges)).isEqualTo(249_900);
        assertThat(GroqService.cheapest(List.of(bil("Okänd bil (2020)")), ranges)).isNull();
        assertThat(GroqService.cheapest(List.of(), ranges)).isNull();
    }

    @Test
    void omforsoketsBilarKommerForst() {
        var original = List.of(bil("Kia Niro EV (2022)"));
        var retried = List.of(bil("Renault Megane E-Tech (2023)"));
        var ranges = Map.of("Kia Niro EV (2022)", range(244_000),
                "Renault Megane E-Tech (2023)", range(230_000));

        var result = GroqService.mergeWithinBudget(retried, ranges, original, ranges, 275_000);

        assertThat(result).extracting(CarRecommendation::title)
                .containsExactly("Renault Megane E-Tech (2023)", "Kia Niro EV (2022)");
    }

    @Test
    void sammaBilIBadaOmgangarnaDubbleras() {
        var bilen = List.of(bil("Kia Niro EV (2022)"));
        var ranges = Map.of("Kia Niro EV (2022)", range(244_000));

        var result = GroqService.mergeWithinBudget(bilen, ranges, bilen, ranges, 275_000);

        assertThat(result).hasSize(1);
    }

    @Test
    void sammaModellIOlikaArsmodellRaknasSomEnBil() {
        // Live-fynd efter forsta merge-versionen: omforsoket gav ID.4 (2022) och ursprunget
        // ID.4 (2021). Var lista var fri fran dubbletter, men ihopslagna blev det samma bil
        // tva ganger — dedup pa exakt titel racker inte, den maste ga pa modell.
        var retried = List.of(bil("Volkswagen ID.4 (2022)"));
        var original = List.of(bil("Volkswagen ID.4 (2021)"), bil("MG ZS EV (2022)"));
        var ranges = Map.of("Volkswagen ID.4 (2022)", range(304_990),
                "Volkswagen ID.4 (2021)", range(280_000), "MG ZS EV (2022)", range(144_900));

        var result = GroqService.mergeWithinBudget(retried, ranges, original, ranges, 275_000);

        assertThat(result).extracting(CarRecommendation::title)
                .containsExactly("Volkswagen ID.4 (2022)", "MG ZS EV (2022)");
    }

    @Test
    void aldrigFlerAnTreBilar() {
        var retried = List.of(bil("A (2022)"), bil("B (2022)"), bil("C (2022)"));
        var original = List.of(bil("D (2022)"), bil("E (2022)"));
        var ranges = new java.util.HashMap<String, BlocketPriceService.PriceRange>();
        for (String t : List.of("A (2022)", "B (2022)", "C (2022)", "D (2022)", "E (2022)")) ranges.put(t, range(200_000));

        assertThat(GroqService.mergeWithinBudget(retried, ranges, original, ranges, 275_000)).hasSize(3);
    }

    @Test
    void ingenBilInomBudgetGerTomLista() {
        // Anroparen faller da tillbaka pa ursprungssvaret — tomt resultat hjalper ingen
        var original = List.of(bil("Volvo EX40 (2022)"));
        var ranges = Map.of("Volvo EX40 (2022)", range(439_000));

        assertThat(GroqService.mergeWithinBudget(List.of(), Map.of(), original, ranges, 275_000)).isEmpty();
    }

    @Test
    void bilUtanBlocketDataFallerInteBort() {
        // Ingen prisdata = ingen grund att falla bilen pa; samma linje som exceedsBudgetCeiling
        var original = List.of(bil("Ovanlig Modell (2022)"));

        assertThat(GroqService.mergeWithinBudget(List.of(), Map.of(), original, Map.of(), 275_000))
                .extracting(CarRecommendation::title).containsExactly("Ovanlig Modell (2022)");
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

    // --- isFresh / store (delade cachehjälpare för både rekommendationer och jämförelser) ---

    @Test
    void isFreshAerFalsktForSaknadPost() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(GroqService.class, "isFresh", new Object[]{null}))
                .isFalse();
    }

    @Test
    void storeLaeggerInPostenSomIsFreshGodkanner() {
        GroqService s = service();
        ReflectionTestUtils.invokeMethod(s, "store", "compare|Volvo XC60|BMW X3", List.of(), null);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cache =
                (java.util.Map<String, Object>) ReflectionTestUtils.getField(s, "cache");
        Object post = cache.get("compare|Volvo XC60|BMW X3");

        assertThat(post).isNotNull();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(GroqService.class, "isFresh", post)).isTrue();
    }

    @Test
    void isFreshAerFalsktForPostAeldreAenTtl() throws Exception {
        GroqService s = service();
        ReflectionTestUtils.invokeMethod(s, "store", "nyckel", List.of(), null);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cache =
                (java.util.Map<String, Object>) ReflectionTestUtils.getField(s, "cache");

        // Bygg en likadan post men med tidsstämpel 5 timmar tillbaka (TTL:n är 4 h)
        var ctor = cache.get("nyckel").getClass().getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object gammal = ctor.newInstance(List.of(), System.currentTimeMillis() - 5 * 60 * 60 * 1000L, null, null);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(GroqService.class, "isFresh", gammal)).isFalse();
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
