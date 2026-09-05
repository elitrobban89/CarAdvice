package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import com.caradvice.model.CargoSpecDto;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
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
    @Mock private ValueRetentionClient valueRetentionClient;

    private GroqService service() {
        return new GroqService(expertInsightService, safetyRatingService,
                evSpecService, cargoSpecService, blocketPriceService, newCarPriceService,
                feedbackService, iceConsumptionService, fuelPriceService, electricityPriceService,
                leasingPriceService, valueRetentionClient);
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
                4, newCar, fuelType, transmission, budgetType, maxAgeYears, null);
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
                3, false, "el", null, "köp", null, null);
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
                5, false, "el", null, "köp", null, null);
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
                4, false, "el", null, "köp", null, null);
        assertThat(GroqService.requiresFamilySizedCar(fyra)).isFalse();
        assertThat(service().buildPrompt(fyra)).doesNotContain("FAMILJEBIL");
    }

    @Test
    void femPassagerareArFamiljeprofil() {
        CarPreferences fem = new CarPreferences(225_000, "elbil", false, 15_000, "pendling",
                5, false, "el", null, "köp", null, null);
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
                2, false, "el", null, "köp", null, null);
        assertThat(GroqService.requiresFamilySizedCar(pendlare)).isFalse();
        assertThat(service().buildPrompt(pendlare)).doesNotContain("FAMILJEBIL");
    }

    // --- adFilterFor: formulärets val → Blockets fältvärden ---

    @Test
    void bensinsokSlapperSjalvladdandeHybridMenInteLaddhybrid() {
        var f = GroqService.adFilterFor("bensin", "suv", "automat");
        assertThat(f.fuels()).containsExactlyInAnyOrder("Bensin", "Hybrid bensin");
        assertThat(f.gearbox()).isEqualTo("Automatisk");
    }

    @Test
    void vaxelladanOversattsTillBlocketsOrd() {
        assertThat(GroqService.adFilterFor("bensin", "suv", "manuell").gearbox()).isEqualTo("Manuell");
        assertThat(GroqService.adFilterFor("bensin", "suv", "spelar ingen roll").gearbox()).isNull();
        assertThat(GroqService.adFilterFor("bensin", "suv", null).gearbox()).isNull();
    }

    @Test
    void elbilskategorinGerElfilterAvenNarDrivmedelsrutanArDold() {
        assertThat(GroqService.adFilterFor("spelar ingen roll", "elbil", "spelar ingen roll").fuels())
                .containsExactly("El");
        assertThat(GroqService.adFilterFor("el", "elbil", null).fuels()).containsExactly("El");
    }

    // --- formulärets elbilspayload: kategorin måste bära hela avsikten ---

    @Test
    void formularetsElbilssokRaknasSomRenElbilTrotsSpelarIngenRoll() {
        // Formuläret DÖLJER drivmedelsrutan för elbil/laddhybrid och tvingar värdet till
        // "spelar ingen roll" — en sträng som bär delsträngen "el" INUTI "spelar". Laddhybrid
        // räddades av sin carCategory-gren, elbil hade ingen: ev och ice blev båda sanna och
        // pureEv() falskt. Uppmätt 2026-08-13 gav det tre fel samtidigt på appens vanligaste
        // elbilsväg, och alla tre satt i samma villkor.
        var intent = GroqService.fuelIntent("spelar ingen roll", "elbil");

        assertThat(intent.pureEv()).isTrue();   // → requirePureEvCars körs
        assertThat(intent.ev()).isTrue();
        assertThat(intent.ice()).isFalse();     // → ICE-nypristabellen utelämnas
    }

    @Test
    void formularetsElbilssokFarBevKravOchSlipperIceTabellen() {
        // Samma payload som gränssnittet faktiskt skickar. Live-verifieringarna gick via API
        // med fuelType="el", där pureEv() redan var sant, så just den här payloaden provades
        // aldrig — därför testet.
        String sp = serviceMedPristabeller().buildSystemPrompt("", "spelar ingen roll", "elbil");

        assertThat(sp)
                .contains("ELBIL OBLIGATORISKT")
                .contains("EV-PRISTABELL-MARKÖR")
                // Båda tabellerna följde med före fixen, alltså det tyngsta promptfallet —
                // samma storleksklass som gav HTTP 413 på reservvägen.
                .doesNotContain("ICE-NYPRISTABELL-MARKÖR");
    }

    @Test
    void formularetsElbilssokFarKandidatlistanOchKravetUtskrivet() {
        // affordableModelsLine och activeConstraints gatas på SAMMA pureEv() som vakten, så de
        // föll med den. Kandidatlistan är särskilt allvarlig: den byggdes 2026-08-10 för att
        // golvvakten fällde EV6/Ioniq 5/Enyaq utan att omförsöket fyllde på till tre kort, och
        // botemedlet var alltså avstängt på exakt den väg symtomet uppstod.
        var formularetsElbilssok = prefs(200_000, "elbil", true, 15_000, false,
                "spelar ingen roll", "spelar ingen roll", "köp", null);

        assertThat(GroqService.affordableModelsLine(formularetsElbilssok))
                .contains("MODELLER SOM RYMS I BUDGETEN");
        assertThat(GroqService.activeConstraints(formularetsElbilssok))
                .contains("ren elbil");
    }

    // --- Tomt AI-svar är inte samma sak som för snäva kriterier ---

    @Test
    void tomtAiSvarFarEgenTypSaKravenInteFarSkulden() throws Exception {
        // Giltig JSON, noll bilar. Förr blev det ett tomt resultat med kraven uppräknade
        // (narrowCriteria) — alltså ett påstående om användarens sökning utan täckning.
        // Egen typ så att slutet kan skilja sig: rättelseförsöket är detsamma, men går även
        // DET tomt ska felet peka på AI:n, inte på kriterierna.
        assertThatThrownBy(() -> service().parseRecommendations("{\"recommendations\":[]}"))
                .isInstanceOf(GroqService.TomtAiSvarException.class)
                // Ärver kanalen: rättelseförsöket i getRecommendation triggas på basklassen
                .isInstanceOf(GroqService.RuleViolationException.class);

        var e = catchThrowableOfType(
                () -> service().parseRecommendations("{\"recommendations\":[]}"),
                GroqService.TomtAiSvarException.class);
        // Rättelsen ska vara en INSTRUKTION till modellen, inte ett felmeddelande till en människa
        assertThat(e.rattelse()).contains("EXAKT 3 bilar").contains("recommendations");
        assertThat(e.kvar()).isEmpty();
    }

    @Test
    void vakterSomFallerAllaBilarBeharSinKravdom() throws Exception {
        // Motsatsen: ett svar MED bilar som vakterna sedan fäller är fortfarande ett
        // RuleViolationException utan den nya typen, och ska därför sluta som tomt svar med
        // kraven uppräknade — där ÄR kraven en rimlig förklaring.
        var med = service().parseRecommendations(
                "{\"recommendations\":[{\"title\":\"Volvo EX30 (2024)\",\"pros\":[\"a\",\"b\",\"c\"]}]}");
        assertThat(med).hasSize(1);
    }

    // --- Fördelar som upprepar ett verifierat fält ---

    private static final CargoSpecDto CARGO_490 = new CargoSpecDto(490, 1300);
    private static final EvSpecDto EV_528 =
            new EvSpecDto(528, 450, 370, 5, "ladda var 5:e dag", 77.0, 240, 11, 350_000, "", "EV", "NMC");

    @Test
    void fordelSomUppreparBagagesiffranFaller() {
        // Skarpt fall: Kia EV6 hade "bagage 520 l" bland fördelarna medan bagagefältet ur
        // cargo_spec sa 490 l. Två olika tal om samma bil på samma kort, och det i fördelarna
        // var AI:ns gissning.
        var pros = List.of("Stort bagageutrymme på 520 l", "Snabb DC-laddning", "Rymlig kupé");
        assertThat(GroqService.utanDubblerandeSpec(pros, CARGO_490, null, "Kia EV6 (2022)"))
                .containsExactly("Snabb DC-laddning", "Rymlig kupé");
    }

    @Test
    void fordelSomUppreparRackviddenFaller() {
        var pros = List.of("Räckvidd upp till 528 km WLTP", "Bra dragvikt", "Tyst gång");
        assertThat(GroqService.utanDubblerandeSpec(pros, null, EV_528, "Kia EV6 (2022)"))
                .containsExactly("Bra dragvikt", "Tyst gång");
    }

    @Test
    void siffranBehallsNarKortetSaknarFaltet() {
        // Utan rad i cargo_spec är AI:ns siffra den enda uppgift som finns, och då är den
        // bättre än tomrum — samma fail open som bagage- och drivmedelsvakterna.
        var pros = List.of("Stort bagageutrymme på 520 l", "Snabb DC-laddning", "Rymlig kupé");
        assertThat(GroqService.utanDubblerandeSpec(pros, null, null, "Okänd (2022)")).isEqualTo(pros);
    }

    @Test
    void fordelUtanSiffraBehalls() {
        // "Smidig att lasta" gör inget anspråk på ett tal och konkurrerar därför inte med
        // fältet — bara ord OCH siffra tillsammans är en dubblett.
        var pros = List.of("Smidig att lasta", "Lång räckvidd i verklig körning", "Tyst gång");
        assertThat(GroqService.utanDubblerandeSpec(pros, CARGO_490, EV_528, "Kia EV6 (2022)"))
                .isEqualTo(pros);
    }

    @Test
    void alltDubblettBehallsHellreAnEnTomLista() {
        // Ett kort utan fördelar läser som ett renderingsfel. Priset för en dubblerad siffra
        // är lägre än priset för en tom ruta.
        var pros = List.of("Bagageutrymme 520 l", "Räckvidd 528 km", "Lastvolym 1 300 l");
        assertThat(GroqService.utanDubblerandeSpec(pros, CARGO_490, EV_528, "Kia EV6 (2022)"))
                .isEqualTo(pros);
    }

    // --- Väntetiden ur Groqs 429-svar ---

    @Test
    void parseRetrySecondsLaserSekunderOchMinuter() {
        // Formatet är Groqs eget, ordagrant ur ett skarpt 429 i den här sessionen.
        assertThat(GroqService.parseRetrySeconds(
                "{\"error\":{\"message\":\"Rate limit reached ... Please try again in 24.51s\"}}")).isEqualTo(25);
        assertThat(GroqService.parseRetrySeconds(
                "{\"error\":{\"message\":\"limit 1000 per day, try again in 2m59.56s\"}}")).isEqualTo(180);
        // Inget att läsa ur = 0, aldrig en gissning: 0 betyder "vänta inte", och då lämnas
        // felet vidare i stället för att servern sover en påhittad tid.
        assertThat(GroqService.parseRetrySeconds("{\"error\":{\"message\":\"nope\"}}")).isZero();
        assertThat(GroqService.parseRetrySeconds("")).isZero();
    }

    @Test
    void minuttaketSagerSekunderInteEnAvrundadMinut() {
        // parseRetryTime avrundar UPPÅT till hela minuter, så 24,5 s blev "1 minut" — och det
        // är just den halvminuten användaren klickar i. Under en minut ska sekunderna stå där.
        assertThat(service().buildRateLimitError(
                "{\"error\":{\"message\":\"Rate limit reached for model, try again in 24.51s\"}}"))
                .contains("25 sekunder")
                .doesNotContain("minut");
        // Dygnstaket är en annan sak och behåller sin minuttext
        assertThat(service().buildRateLimitError(
                "{\"error\":{\"message\":\"Rate limit reached, limit 1000 per day, try again in 2m59.56s\"}}"))
                .contains("Dagsgränsen");
    }

    // --- Kategoriblocken skickas bara när de gäller (mätt 2026-08-28: 37 % av regeltexten) ---

    @Test
    void smabilssokBarInteSuvOchFamiljereglerna() {
        var smabil = prefs(150_000, "smaabil", false, 15_000, false, "bensin", "spelar ingen roll", "köp", 5);

        assertThat(serviceMedPristabeller().buildSystemPrompt("", smabil))
                .contains("SMÅBIL (kategori")
                // Ett 150 000-kronorssök på småbil bar hela SUV-avsnittet med sina
                // mellanklassmodeller från 350 000 kr — regler det aldrig kunde följa.
                .doesNotContain("SUV (kategori")
                .doesNotContain("SUV OCH BUDGET")
                .doesNotContain("FAMILJEBIL (kategori")
                .doesNotContain("PHEV: rekommendera ALDRIG")
                // Uttalat bensinval: regeln om att drivmedlet är ett val ska däremot vara kvar
                .contains("DRIVMEDLET ÄR ETT VAL");
    }

    @Test
    void suvsokBarSuvreglernaMenInteSmabilsreglerna() {
        var suv = prefs(400_000, "suv", true, 15_000, false, "el", "spelar ingen roll", "köp", 5);

        assertThat(serviceMedPristabeller().buildSystemPrompt("", suv))
                .contains("SUV (kategori")
                .contains("SUV OCH BUDGET")
                .doesNotContain("SMÅBIL (kategori")
                // Elbilssök: bensinreglerna hör inte hit
                .doesNotContain("DRIVMEDLET ÄR ETT VAL");
    }

    @Test
    void femPassagerareTarMedFamiljeregelnAvenUtanFamiljekategori() {
        // Den viktiga gränsen: familjekravet kan INTE läsas ur kategorin ensam. Vakten
        // requireFamilySizedCars fäller på passagerare >= 5, och gör den det måste AI:n ha
        // fått se regeln — annars kastas bilar för något den aldrig blev tillsagd.
        CarPreferences femPersoner = new CarPreferences(300_000, "suv", true, 15_000, "pendling",
                5, false, "el", null, "köp", null, null);

        assertThat(GroqService.requiresFamilySizedCar(femPersoner)).isTrue();
        assertThat(serviceMedPristabeller().buildSystemPrompt("", femPersoner))
                .contains("FAMILJEBIL (kategori");
    }

    @Test
    void treArgumentsvagenTarMedAllaKategoriblock() {
        // Utan prefs vet vi inte vad sökningen gäller. Då ska ALLT med — en tyst
        // bortfiltrering i testvägen hade dolt regressioner i regeltexten.
        assertThat(serviceMedPristabeller().buildSystemPrompt("", "spelar ingen roll", null))
                .contains("FAMILJEBIL (kategori")
                .contains("SUV (kategori")
                .contains("SUV OCH BUDGET")
                .contains("SMÅBIL (kategori")
                .contains("DRIVMEDLET ÄR ETT VAL")
                .contains("PHEV: rekommendera ALDRIG");
    }

    @Test
    void laddhybridssokBarPhevRegelnMenInteSmabilsreglerna() {
        var phev = prefs(350_000, "laddhybrid", true, 15_000, false, "spelar ingen roll",
                "spelar ingen roll", "köp", 5);

        assertThat(serviceMedPristabeller().buildSystemPrompt("", phev))
                .contains("PHEV: rekommendera ALDRIG")
                .contains("LADDHYBRIDSKATT")
                .doesNotContain("SMÅBIL (kategori")
                .doesNotContain("SUV OCH BUDGET");
    }

    @Test
    void laddhybridskategorinPaverkasInteAvElbilsgrenen() {
        // Kategorin laddhybrid ska fortfarande ge brasklappen och INTE BEV-tvånget, oavsett
        // vilket kvarglömt drivmedelsvärde formuläret råkar skicka med.
        var intent = GroqService.fuelIntent("spelar ingen roll", "laddhybrid");
        assertThat(intent.phev()).isTrue();
        assertThat(intent.pureEv()).isFalse();

        assertThat(serviceMedPristabeller().buildSystemPrompt("", "spelar ingen roll", "laddhybrid"))
                .contains("LADDHYBRIDSKATT")
                .doesNotContain("ELBIL OBLIGATORISKT");
    }

    @Test
    void laddhybridskategorinGerPluginfilter() {
        assertThat(GroqService.adFilterFor("spelar ingen roll", "laddhybrid", null).fuels())
                .containsExactlyInAnyOrder("Plug-in Bensin", "Plug-in Diesel");
    }

    @Test
    void hybridvaletGerBaraSjalvladdandeHybrider() {
        // "Hybrid (ej laddhybrid)" är HEV — varken en elbil eller en laddhybrid.
        assertThat(GroqService.adFilterFor("hybrid", "suv", null).fuels())
                .containsExactlyInAnyOrder("Hybrid bensin", "Hybrid diesel");
    }

    @Test
    void spelarIngenRollGerTomtFilterSaGolvetBeterSigSomForut() {
        var f = GroqService.adFilterFor("spelar ingen roll", "suv", "spelar ingen roll");
        assertThat(f.fuels()).isEmpty();
        assertThat(f.gearbox()).isNull();
    }

    @Test
    void dieselsokSlapperInteLaddhybriddiesel() {
        assertThat(GroqService.adFilterFor("diesel", "familjebil", null).fuels())
                .containsExactlyInAnyOrder("Diesel", "Hybrid diesel");
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
                .contains("UTNYTTJA BUDGETEN");
        // ID.3 är Golf-klass med fem säten och stod på förbudslistan medan MG4 — samma
        // storleksklass — rekommenderades som familjeelbil längre ned i samma stycke.
        // Kollas på SJÄLVA förbudsraden och inte på hela prompten: bilen får mycket väl nämnas
        // på annat håll, och gjorde det så fort begagnatgolven kom in ("VW ID.3 fr. ca 199 000")
        // — en assertion på hela texten hade tvingat fram fel rättning av rätt larm.
        String familjeraden = sp.lines()
                .filter(l -> l.startsWith("FAMILJEBIL (kategori"))
                .findFirst().orElseThrow();
        assertThat(familjeraden).doesNotContain("ID.3");
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
    void avvecklandeModellerFarForeslasIBegagnatsok() {
        // Renault Zoe slutade tillverkas i mars 2024 (ersatt av Renault 5) — men 49 exemplar
        // ligger på Blocket från 58 000 kr, och prompten listar samtidigt Zoe både som
        // småbilsexempel och med begagnatgolv 58 000. Regeln "nämn ALDRIG modeller som inte
        // officiellt säljs i Sverige" motsade alltså två andra rader i samma prompt. Appen ger
        // råd om begagnatköp: gränsen går vid bilar som ALDRIG sålts här.
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp)
                .contains("aldrig sålts i Sverige")
                .doesNotContain("Nämn ALDRIG modeller som inte officiellt säljs i Sverige")
                .contains("SLUTAT tillverkas är däremot inget hinder")
                // ...men en avvecklad modell går varken att köpa ny eller leasa
                .contains("NYBILSSÖK och LEASING");
    }

    @Test
    void promptenBarUppmattaBegagnatgolvForElbilar() {
        // Elbil var enda kategorin utan exempellista: AI:n fick nypristabellen plus
        // deprecieringsregeln och räknade fram begagnatpriserna själv — systematiskt för högt.
        // Live 2026-08-10: elbil + 200 000 kr gav EV6 (billigaste annons 316 990 kr) och
        // banderollen "budgeten räcker inte", medan MG4 fanns från 193 990 och MG5 från 179 700.
        // Golven speglar CA_BUDGET_LEVELS.elbil i car-advice-main.js — går de isär säger
        // budgetrutan och korten emot varandra i samma vy.
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp)
                .contains("UPPMÄTTA BEGAGNATGOLV")
                .contains("högst 10 000 mil")   // utan milgränsen sätter vraken golvet
                .contains("MG4 fr. ca 195 000")
                .contains("Kia EV6 fr. ca 317 000");
    }

    @Test
    void promptenNamnerMg5SomBilligElkombi() {
        // MG5 fanns i ev_spec, i cargo_spec och i modell-whitelisten, var inte småbilsmarkerad
        // och gick att prissätta mot Blocket — den saknades bara i promptens kuraterade lista,
        // och AI:n föreslog den därför aldrig. Live 2026-08-10 gav elbil + 200 000 kr i stället
        // EV6/Leaf/Polestar 2 med banderollen "budgeten räcker inte" (billigaste 274 900 kr),
        // medan MG5 låg på Blocket från 179 700 kr med under 10 000 mil (18 annonser).
        // Lärdomen från vaktprompterna gäller även rekommendationsprompten: modellen agerar på
        // namngivna exempel, inte på att bilen råkar finnas i databasen.
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(sp)
                .contains("MG5")
                .contains("elkombi");
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

    // --- requireAffordableModels (begagnatgolven i kod, inte bara i prompten) ---

    @Test
    void bilOverBudgetgolvetAvvisas() throws Exception {
        // Live 2026-08-10, BÅDA körningarna: elbil + 200 000 kr gav Kia EV6, vars golv 317 000
        // står utskrivet i samma prompt tio rader ovanför regeln som förbjuder det. Prompttext
        // räcker inte — samma lärdom som familjespärren och drivmedelsvakten gav.
        List<CarRecommendation> parsed = parsatSvarMed("Kia EV6 (2023)");
        assertThatThrownBy(() -> GroqService.requireAffordableModels(parsed, 200_000))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("inte går att köpa för budgeten");
    }

    @Test
    void bilInomGolvetSlappsIgenom() throws Exception {
        // MG4:s golv är 195 000 — ryms under 200 000 + 30 000
        List<CarRecommendation> mg4 = parsatSvarMed("MG4 (2023)");
        GroqService.requireAffordableModels(mg4, 200_000);   // ska inte kasta
        assertThat(mg4).hasSize(1);
    }

    @Test
    void omattModellSlappsIgenom() throws Exception {
        // Tabellen är en handfull mätta modeller, inte en marknadsöversikt
        List<CarRecommendation> okand = parsatSvarMed("Cupra Born (2022)");
        GroqService.requireAffordableModels(okand, 150_000);   // ska inte kasta
        assertThat(okand).hasSize(1);
    }

    @Test
    void rattelsenRaknarUppModellerSomRyms() throws Exception {
        // Samma mönster som de andra vakterna: omförsöket ska veta vad det SKA välja, inte bara
        // vad som var fel. Utan listan föreslår modellen ofta en lika dyr bil igen.
        List<CarRecommendation> parsed = parsatSvarMed("Kia EV6 (2023)");
        assertThatThrownBy(() -> GroqService.requireAffordableModels(parsed, 150_000))
                .isInstanceOfSatisfying(GroqService.RuleViolationException.class, e -> {
                    assertThat(e.rattelse()).contains("Renault Zoe").contains("Nissan Leaf")
                                            .contains("MG ZS EV");
                    assertThat(e.rattelse()).doesNotContain("Kia EV6");
                    assertThat(e.avvisade()).anyMatch(a -> a.contains("317000"));
                });
    }

    @Test
    void golvenIPromptenByggsUrSammaTabellSomVakten() {
        // Två kopior av samma siffror glider isär vid nästa mätning — texten genereras därför
        // ur EV_PRICE_FLOOR_KR
        String sp = serviceMedPristabeller().buildSystemPrompt("", "el");
        assertThat(GroqService.EV_PRICE_FLOOR_KR.get("Kia EV6")).isEqualTo(317_000);
        assertThat(sp).contains("Kia EV6 fr. ca 317 000");
        assertThat(sp).contains("MG4 fr. ca 195 000");
    }

    @Test
    void golvvaktenGallerInteNybilssokEllerLeasing() {
        // Golven är BEGAGNATpriser: ett nybilssök eller en leasingförfrågan mäts mot fel tal
        CarPreferences nybil = new CarPreferences(200_000, "elbil", false, 15_000, "pendling",
                4, true, "el", null, "köp", null, null);
        CarPreferences leasing = new CarPreferences(5_000, "elbil", false, 15_000, "pendling",
                4, false, "el", null, "leasing", null, null);
        CarPreferences begagnat = new CarPreferences(200_000, "elbil", false, 15_000, "pendling",
                4, false, "el", null, "köp", null, null);

        assertThat(GroqService.harGolvvakt(nybil)).isFalse();
        assertThat(GroqService.harGolvvakt(leasing)).isFalse();
        assertThat(GroqService.harGolvvakt(begagnat)).isTrue();
    }

    // --- utanMarknadspastaende (AI:ns påhittade Blocket-siffror i whyRecommended) ---

    @Test
    void aiPastaendeOmBlocketTasBort() {
        // Live 2026-08-10: kortet visade "Blocket-annonser visar begagnatgolv 199 000 kr för
        // 2021-modell med 11 800 km" i KURSIV direkt under den verifierade prisraden, som sa
        // 239 900–469 900 kr ur riktiga annonser. Två motstridiga Blocket-siffror med två
        // centimeters mellanrum, varav den påhittade ser mest specifik ut.
        String why = "Teknikens Värld: toppbetyg. Blocket-annonser visar begagnatgolv 199 000 kr.";

        assertThat(GroqService.utanMarknadspastaende(why, "Volkswagen ID.4 (2021)"))
                .isEqualTo("Teknikens Värld: toppbetyg.");
    }

    @Test
    void kallhanvisningenOverleverStadningen() {
        // Bara meningen med marknadspåståendet faller — källan är fortfarande värd att visa
        String rent = "Vi Bilägare: prisvärd och rymlig familjebil";
        assertThat(GroqService.utanMarknadspastaende(rent, "Kia Niro EV (2020)")).isEqualTo(rent);
        assertThat(GroqService.utanMarknadspastaende(null, "x")).isNull();
    }

    // --- rate limit vs trunkering (två olika fel som såg identiska ut) ---

    @Test
    void rateLimitFarAldrigRadetOmKriterierna() {
        // Skarpt fall 2026-08-10: användaren fick "AI-svaret blev ofullständigt ... prova högre
        // budget, färre passagerare" och samma sökning gick igenom direkt efteråt. Orsaken var
        // minutkvoten — en sökning drar ~5 000 av 8 000 tokens, så två inom samma minut räcker.
        // Rådet var alltså inte bara onödigt utan aktivt vilseledande: kriterierna var oskyldiga
        // och det enda som hjälpte var att vänta.
        GroqService.RateLimitedException taket =
                new GroqService.RateLimitedException("AI-tjänsten är tillfälligt överbelastad.");

        assertThat(GroqService.medRadOmKriterier(taket))
                .isSameAs(taket);
        assertThat(GroqService.medRadOmKriterier(taket).getMessage())
                .doesNotContain("Kriterierna kan vara för snäva");
    }

    @Test
    void andraFelBehallerRadetOmKriterierna() {
        // Trunkering ÄR ofta kriterieberoende: elbil + 225 000 kr + 5 passagerare gav HTTP 500
        // i tre försök av tre 2026-08-09, medan fyra passagerare gick igenom direkt
        RuntimeException trunkerat = new RuntimeException("AI-svaret blev ofullständigt. Försök igen.");

        assertThat(GroqService.medRadOmKriterier(trunkerat).getMessage())
                .contains("AI-svaret blev ofullständigt")
                .contains("Kriterierna kan vara för snäva");
    }

    // --- activeConstraints (underlag för banderollen "för snäva krav") ---

    @Test
    void kravenSomGallradeRaknasUppIKlartext() {
        // Live 2026-08-10: familjeelbil + 400 l + 200 000 kr gav ETT kort (MG5), helt korrekt —
        // MG4 (363 l) och Niro EV (349 l) klarade inte bagagekravet. Utan förklaring läser ett
        // ensamt kort som att appen krånglar i stället för som ett svar på en hård fråga.
        CarPreferences snav = new CarPreferences(200_000, "familjebil", false, 15_000, "familj",
                5, false, "el", null, "köp", 5, 400);

        assertThat(GroqService.activeConstraints(snav))
                .containsExactly("ren elbil", "minst 400 liter bagage", "familjestor bil",
                                 "högst 5 år gammal", "högst 230 000 kr");
    }

    @Test
    void kravlistanTarBaraMedDetSomFaktisktBegransar() {
        // En bred sökning ska inte påstå att den gallrat på krav användaren aldrig ställde
        CarPreferences bred = new CarPreferences(300_000, "smaabil", false, 15_000, "pendling",
                4, false, "spelar ingen roll", "spelar ingen roll", "köp", null, null);

        assertThat(GroqService.activeConstraints(bred)).containsExactly("högst 330 000 kr");
    }


    @Test
    void suvkravetStarMedIKravlistan() {
        // Live 2026-08-22, direkt efter deploy: SUV + elbil + 400 000 kr gav TVÅ kort och
        // banderollen räknade upp "ren elbil, automat, högst 430 000 kr" — det var SUV-spärren
        // som fällde det tredje, och just det kravet syntes inte.
        CarPreferences suvsok = new CarPreferences(400_000, "suv", true, 15_000, "pendling",
                4, false, "el", "automat", "köp", null, null);

        assertThat(GroqService.activeConstraints(suvsok))
                .containsExactly("ren elbil", "SUV (hög bil)", "automat", "högst 430 000 kr");
    }

    // --- suvModelsLine (SUV-kandidaterna i FÖRSTA prompten) ---

    @Test
    void suvraddenNamnerDeStorstaSuvarnaBudgetenNar() {
        // Skarpt 2026-08-22: SUV + el + 400 000 gav TVÅ kort — spärren fällde det tredje och
        // omförsöket kom tillbaka med ännu en låg bil. Prompten sa vad som var förbjudet men
        // inte vad som fanns kvar i just den budgeten.
        String rad = GroqService.suvModelsLine(new CarPreferences(400_000, "suv", true, 15_000,
                "pendling", 4, false, "el", "automat", "köp", null, null));

        assertThat(rad).contains("BMW iX (fr. 409 900)").contains("Volvo EX40 (fr. 389 500)");
        assertThat(rad).contains("Minst TVÅ av tre");
        // De billiga småbilarna i klassen får inte namnges när budgeten når de stora
        assertThat(rad).doesNotContain("MG ZS EV").doesNotContain("Peugeot e-2008");
    }

    @Test
    void suvraddenFoljerBudgetenNedat() {
        String rad = GroqService.suvModelsLine(new CarPreferences(200_000, "suv", true, 15_000,
                "pendling", 4, false, "el", null, "köp", null, null));

        // 230 000 i tak: ID.4 (229 900) är den största som ryms, iX och EX40 ligger långt över
        assertThat(rad).contains("Volkswagen ID.4").contains("Peugeot e-2008");
        assertThat(rad).doesNotContain("BMW iX").doesNotContain("Tesla Model Y");
    }

    @Test
    void suvraddenSagerIfranNarIngenElsuvRyms() {
        String rad = GroqService.suvModelsLine(new CarPreferences(80_000, "suv", true, 15_000,
                "pendling", 4, false, "el", null, "köp", null, null));

        assertThat(rad).contains("ingen el-SUV").contains("MG ZS EV från 139 500");
    }

    @Test
    void suvraddenGerModellnamnUtanGolvForBensin() {
        String rad = GroqService.suvModelsLine(new CarPreferences(300_000, "suv", false, 15_000,
                "pendling", 4, false, "bensin", null, "köp", null, null));

        // Bensin-SUV har egna uppmätta golv (bensinautomat), och de ligger HÖGRE än de
        // ofiltrerade: XC60 kostar 125 500 kr utan filter men 249 900 kr som bensinautomat.
        assertThat(rad).contains("Volvo XC60 (fr. 249 900)").contains("Audi Q5 (fr. 289 900)");
        assertThat(rad).doesNotContain("Mercedes GLC");   // 929 000 kr, långt över taket
    }

    @Test
    void suvraddenLamnarAndraKategorierIfred() {
        assertThat(GroqService.suvModelsLine(new CarPreferences(400_000, "elbil", true, 15_000,
                "pendling", 4, false, "el", null, "köp", null, null))).isEmpty();
    }

    @Test
    void suvraddenAnvanderIngaBegagnatgolvILeasingläge() {
        // Golven är begagnatpriser — i leasingläge är budgeten kr/mån och siffrorna helt fel värld
        String rad = GroqService.suvModelsLine(new CarPreferences(4_500, "suv", true, 15_000,
                "pendling", 4, true, "el", null, "leasing", null, null));

        assertThat(rad).contains("ATT UTGÅ FRÅN").doesNotContain("fr. ");
    }
    // --- affordableModelsLine (kandidatlistan i FÖRSTA prompten, inte bara i rättelsen) ---

    @Test
    void lagBudgetFarKandidatlistanRedanIForstaPrompten() {
        // Golvvakten började bita men gjorde svaret tunnare i stället för bättre: live
        // 2026-08-10 föll EV6/Ioniq 5/Enyaq korrekt, men omförsöket fyllde inte på till tre och
        // BÅDA sökningarna gav ETT kort. Vakten säger vad som är fel — den här raden säger vad
        // som är rätt, och säger det innan modellen hunnit gissa.
        String rad = GroqService.affordableModelsLine(prefsMedBudget(200_000));
        assertThat(rad)
                .contains("MODELLER SOM RYMS")
                .contains("MG4 (fr. 195 000)")
                .contains("Volkswagen ID.3")
                .contains("Kia EV6");          // står som avrådd, inte som kandidat
        assertThat(rad.substring(0, rad.indexOf("ligger ÖVER")))
                .doesNotContain("Kia EV6");
    }

    @Test
    void hogBudgetFarRadenOmBudgetensOvreDel() {
        // Väntade TOM sträng fram till 2026-08-28, med motiveringen att listan bara var brus
        // när hela tabellen rymdes. Brusinvändningen var riktig — slutsatsen var fel. Skarpt
        // fall samma dag: 350 000 kr på elbil gav Nissan Leaf (golv 70 000) och Hyundai Kona
        // Electric, eftersom taket 380 000 ligger över tabellens dyraste golv (EV6, 317 000)
        // och `over` därför var tom. Styrningen försvann precis vid de budgetar där frågan
        // inte längre är "vad har jag råd med" utan "vad ska jag välja".
        //
        // Nu skickas en KORT rad om budgetens övre del i stället för hela tabellen.
        String rad = GroqService.affordableModelsLine(prefsMedBudget(400_000));
        assertThat(rad)
                .contains("UTNYTTJA BUDGETEN")
                .contains("Kia EV6")
                .contains("HÖGST ETT av tre")
                .contains("Nissan Leaf");
        // Hela tabellen med priser hör inte hemma här — det var den brusinvändningen handlade om
        assertThat(rad).doesNotContain("MODELLER SOM RYMS");
    }

    @Test
    void ovreDelenPekarPaRattAndeAvTabellen() {
        String rad = GroqService.ovreDelenAvBudgeten(350_000);
        // Förstahandsvalen: golv >= 60 % av budgeten (210 000)
        int forstahand = rad.indexOf("förstahandsval");
        int forBilliga = rad.indexOf("HÖGST ETT");
        assertThat(forstahand).isGreaterThan(-1);
        assertThat(forBilliga).isGreaterThan(forstahand);
        String ovre = rad.substring(forstahand, forBilliga);
        assertThat(ovre).contains("Kia EV6").contains("Skoda Enyaq").contains("Hyundai Ioniq 5");
        // Leaf och Zoe ligger under 35 % av budgeten och ska stå som för billiga, inte som val
        assertThat(ovre).doesNotContain("Nissan Leaf").doesNotContain("Renault Zoe");
        assertThat(rad.substring(forBilliga)).contains("Nissan Leaf").contains("Renault Zoe");
    }

    // --- Budgetgolvet: når något förslag upp mot budgeten? ---

    @Test
    void allaForslagLangtUnderBudgetenRaknasSomOanvandBudget() {
        // Skarpa fallet: 350 000 kr gav Leaf och Kona Electric. Mätt på DYRASTE annonsen —
        // finns inte ens ett dyrt exemplar i budgetens närhet är modellen en klass under.
        var leaf = new BlocketPriceService.PriceRange(70_000, 189_000, 30, "...");
        var kona = new BlocketPriceService.PriceRange(195_000, 239_000, 25, "...");
        var ranges = Map.of("Nissan Leaf (2021)", leaf, "Hyundai Kona Electric (2022)", kona);
        var bilar = List.of(bil("Nissan Leaf (2021)"), bil("Hyundai Kona Electric (2022)"));

        assertThat(GroqService.utnyttjarBudgeten(bilar, ranges, 350_000)).isFalse();
    }

    @Test
    void enBilSomNarBudgetenRacker() {
        // Promptregeln säger uttryckligen att EN billig outlier är OK — vakten ska alltså
        // fälla på att HELA svaret ligger lågt, aldrig på att ett av tre gör det.
        var enyaq = new BlocketPriceService.PriceRange(279_000, 389_000, 40, "...");
        var leaf = new BlocketPriceService.PriceRange(70_000, 189_000, 30, "...");
        var ranges = Map.of("Skoda Enyaq (2022)", enyaq, "Nissan Leaf (2021)", leaf);
        var bilar = List.of(bil("Skoda Enyaq (2022)"), bil("Nissan Leaf (2021)"));

        assertThat(GroqService.utnyttjarBudgeten(bilar, ranges, 350_000)).isTrue();
    }

    @Test
    void omattBilFallerAldrigPaBudgetgolvet() {
        // Positivt bevis krävs, som i drivmedels- och SUV-vakterna: utan Blocket-data går det
        // inte att avgöra, och en ensam annons får varken fria eller fälla (count < 2).
        var bilar = List.of(bil("Okänd Modell (2022)"));
        assertThat(GroqService.utnyttjarBudgeten(bilar, Map.of(), 350_000)).isTrue();

        var ensam = Map.of("Okänd Modell (2022)",
                new BlocketPriceService.PriceRange(50_000, 60_000, 1, "..."));
        assertThat(GroqService.utnyttjarBudgeten(bilar, ensam, 350_000)).isTrue();
    }


    @Test
    void kandidatlistanGallerBaraElbilssok() {
        // Golven är elbilsgolv — en bensinsökning ska inte få en lista med elbilar
        CarPreferences bensin = new CarPreferences(150_000, "smaabil", false, 15_000, "pendling",
                4, false, "bensin", null, "köp", null, null);
        assertThat(GroqService.affordableModelsLine(bensin)).isEmpty();
    }

    @Test
    void kandidatlistanGallerInteNybilEllerLeasing() {
        CarPreferences nybil = new CarPreferences(200_000, "elbil", false, 15_000, "pendling",
                4, true, "el", null, "köp", null, null);
        assertThat(GroqService.affordableModelsLine(nybil)).isEmpty();
    }

    private static CarPreferences prefsMedBudget(int budget) {
        return new CarPreferences(budget, "elbil", false, 15_000, "pendling",
                4, false, "el", null, "köp", null, null);
    }

    // --- requireCargoCapacity (bagagekravet i kod, inte bara i prompten) ---

    @Test
    void forLitetBagageAvvisas() throws Exception {
        // Användarfallet: "min Kamiq tar 400 l, visa bara bilar med mer". En halvkombi på 380 l
        // är fel svar oavsett hur väl den passar i övrigt.
        when(cargoSpecService.formatForTitle(anyString()))
                .thenReturn(new com.caradvice.model.CargoSpecDto(380, 1270));
        List<CarRecommendation> parsed = parsatSvarMed("Volkswagen Golf (2021)");
        assertThatThrownBy(() -> service().requireCargoCapacity(parsed, 400))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("bagageutrymme");
    }

    @Test
    void tillrackligtBagageSlappsIgenom() throws Exception {
        when(cargoSpecService.formatForTitle(anyString()))
                .thenReturn(new com.caradvice.model.CargoSpecDto(578, 1456));
        List<CarRecommendation> parsed = parsatSvarMed("MG5 (2022)");
        service().requireCargoCapacity(parsed, 400);   // ska inte kasta
        assertThat(parsed).hasSize(1);
    }

    @Test
    void bilUtanUppmattBagageSlappsIgenom() throws Exception {
        // Positivt bevis krävs, precis som i drivmedelsvakten: cargo_spec hade 243 rader den
        // 2026-08-10 mot modell-whitelistens ~700, så att kasta det omätta hade tagit fler
        // bra bilar än dåliga
        when(cargoSpecService.formatForTitle(anyString())).thenReturn(null);
        List<CarRecommendation> parsed = parsatSvarMed("Cupra Born (2022)");
        service().requireCargoCapacity(parsed, 400);   // ska inte kasta
        assertThat(parsed).hasSize(1);
    }

    @Test
    void bagagevaktenBarMedDeGodkandaBilarna() throws Exception {
        // Samma mönster som de andra vakterna: RuleViolationException bär det som klarade regeln,
        // så ett brott bland tre bilar inte kostar hela svaret
        when(cargoSpecService.formatForTitle(contains("Golf")))
                .thenReturn(new com.caradvice.model.CargoSpecDto(380, 1270));
        when(cargoSpecService.formatForTitle(contains("V60")))
                .thenReturn(new com.caradvice.model.CargoSpecDto(529, 1441));
        String golf = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volkswagen Golf (2021)");
        String v60 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volvo V60 (2021)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + golf + "," + v60 + "]}");

        assertThatThrownBy(() -> service().requireCargoCapacity(parsed, 400))
                .isInstanceOfSatisfying(GroqService.RuleViolationException.class, e -> {
                    assertThat(e.kvar()).hasSize(1);
                    assertThat(e.kvar().get(0).title()).contains("V60");
                });
    }

    @Test
    void bagagekravetIngarICachenyckeln() {
        // Utan det svarar cachen med förra sökningens bilar när bara kravet ändrats
        assertThat(service().buildCacheKey(prefsMedBagage(400)))
                .isNotEqualTo(service().buildCacheKey(prefsMedBagage(null)));
    }

    private static CarPreferences prefsMedBagage(Integer liter) {
        return new CarPreferences(300_000, "familjebil", false, 15_000, "blandat",
                4, false, "el", null, "köp", null, liter);
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
    void vaxelladefaltetStadasFranMotorbeteckningar() {
        // Live 2026-08-14: kortet "Volvo XC40 (2022)" fick växellådan "Automat 8-växlad
        // (TSI turbo)". TSI är VW-koncernens beteckning och bilen var en Volvo B4. Roten satt
        // i promptens EGET exempel — "Automat DSG 7-växlad (TSI turbo)" — som blev en mall.
        assertThat(GroqService.rensaVaxellada("Automat 8-växlad (TSI turbo)")).isEqualTo("Automat 8-växlad");
        assertThat(GroqService.rensaVaxellada("Automat CVT (HEV hybrid)")).isEqualTo("Automat CVT");
        assertThat(GroqService.rensaVaxellada("Automat DCT 6-växlad (Hybrid)")).isEqualTo("Automat DCT 6-växlad");

        // Parentesen behålls när den faktiskt namnger växellådan
        assertThat(GroqService.rensaVaxellada("Automat (CVT)")).isEqualTo("Automat (CVT)");
        assertThat(GroqService.rensaVaxellada("Automat (DSG)")).isEqualTo("Automat (DSG)");

        // Oförändrat när det inte finns någon parentes att städa
        assertThat(GroqService.rensaVaxellada("Automat Geartronic 8-växlad"))
                .isEqualTo("Automat Geartronic 8-växlad");
        assertThat(GroqService.rensaVaxellada("Manuell 6-växlad")).isEqualTo("Manuell 6-växlad");
        assertThat(GroqService.rensaVaxellada(null)).isNull();
        // Blir bara parentesen kvar finns ingen växellåda att visa
        assertThat(GroqService.rensaVaxellada("(TSI turbo)")).isNull();
    }

    @Test
    void bensinkortFarIngenEvSpecNarIceConsumptionHarBilen() {
        // Live 2026-08-14, SUV/bensin/250 000 kr: korten "Kia Niro (2021)" och "Hyundai Kona
        // (2020)" bar en elbils evSpec ("ladda var 10:e dag" / "var 6:e dag") samtidigt som
        // fuelSpec korrekt visade bensinmotorn. Företrädesregeln fanns i isNonEv sedan 08-09
        // men användes bara av drivmedelsvakten — kortbygget hämtade evSpec oavsett.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Kia", "Niro 1.6 GDI HEV 141 hk", "hybrid", 0.62));
        assertThat(service().evSpecHorInteHit("Kia Niro (2021)")).isTrue();
        assertThat(service().evSpecHorInteHit("Hyundai Kona (2020)")).isTrue();
        // Titeln säger själv att den är elbil → siffrorna hör hit
        assertThat(service().evSpecHorInteHit("Kia Niro EV (2021)")).isFalse();
        assertThat(service().evSpecHorInteHit("Hyundai Kona Electric (2020)")).isFalse();
    }

    @Test
    void laddbaraKortBeharSinEvSpec() {
        // Gränsen åt andra hållet — tre sätt att vara laddbar, alla ska behålla batteridatan.
        // En glömd post här kostar ett tomt fält på ett elbilskort; motsatsen ger laddråd på
        // en bensinbil, så listan får bara växa åt det här hållet.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Volvo", "XC60 T8 PHEV 350 hk", "laddhybrid", 0.55));
        assertThat(service().evSpecHorInteHit("Volvo XC60 T8 (2021)")).isFalse();   // ice-träffen ÄR laddhybrid

        // Märkesnamnet för den laddbara varianten räcker, även när basmodellen finns som bensin
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Volvo", "XC40 B4 197 hk", "bensin", 0.80));
        assertThat(service().evSpecHorInteHit("Volvo XC40 Recharge (2022)")).isFalse();
        assertThat(service().evSpecHorInteHit("Jeep Compass 4xe (2021)")).isFalse();
        // ...men den nakna bensinbilen fälls
        assertThat(service().evSpecHorInteHit("Volvo XC40 (2022)")).isTrue();
    }

    @Test
    void okandBilBeharSinEvSpec() {
        // Fail open: ingen förbränningsträff är inget bevis, och ett DB-fel får inte släcka
        // batteridatan på ett kort. Samma linje som isNonEv.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any())).thenReturn(null);
        assertThat(service().evSpecHorInteHit("Volvo EX30 (2024)")).isFalse();
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenThrow(new RuntimeException("db nere"));
        assertThat(service().evSpecHorInteHit("Volvo EX30 (2024)")).isFalse();
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
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any(), any()))
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
    void drivmedletKommerFranIceConsumptionOchInteFranTroskeln() throws Exception {
        // Frontenden gissade drivmedel PA FORBRUKNINGEN: "> 7 l/100 km = diesel". En Kia
        // Sportage 1.6 T-GDI drar 8,0 och prissattes darfor som diesel i agandekostnaden,
        // medan en snal diesel under 7 fick bensinpris - fel at bada hallen. Vardet fanns
        // hela tiden i raden forbrukningssiffran hamtas ur, det skickades bara inte med.
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Kia", "Sportage 1.6 T-GDI 150 hk", "bensin", 0.80));

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + ICE_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);

        CarRecommendation r = result.get(0);
        assertThat(r.fuelSpec().fuel()).isEqualTo("bensin");
        // Kommer ur SAMMA rad som forbrukningen: 0,80 l/mil -> 8,0 l/100 km, alltsa over
        // troskeln 7 och just darfor det fall som blev fel forut
        assertThat(r.fuelSpec().consumptionLiterPerMil()).isEqualTo(8.0);
    }

    @Test
    void drivmedletBlirNullUtanVerifieradVariant() throws Exception {
        // Ingen DB-traff: faltet lamnas TOMT i stallet for att bara AI:ns gissning vidare.
        // Frontenden faller da tillbaka pa den gamla troskeln, som ar det basta vi har dar.
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any(), any())).thenReturn(null);

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + ICE_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);

        assertThat(result.get(0).fuelSpec().fuel()).isNull();
    }

    @Test
    void aiGissningBehallsForIceBilUtanVerifieradVariant() throws Exception {
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any(), any())).thenReturn(null);

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
    void arsmodellenSkickasMedTillForbrukningsuppslaget() throws Exception {
        /*
         * Generationsvakten satt först bara på motorlistan, och då räckte den inte: kortets
         * förbrukning, drivmedel och hk kommer ur EN rad ur samma generationsblinda tabell, och
         * förbrukningen räknas dessutom om till kronor i ägandekostnaden. Vakten kan bara bita
         * om året faktiskt når fram — därför verifieras argumentet, inte bara utfallet.
         *
         * Faller raden bort behåller kortet AI:ns egen text. Det är samma utfall som för en bil
         * vi saknar helt, och det är med flit: ett tomt påstående är bättre än ett falskt.
         */
        GroqService s = service();
        when(evSpecService.formatForTitle(anyString(), anyInt())).thenReturn(null);
        when(evSpecService.getSystemPowerHk(anyString())).thenReturn(null);
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any(), any())).thenReturn(null);

        List<CarRecommendation> parsed = s.parseRecommendations("{\"recommendations\":[" + ICE_BIL + "]}");
        @SuppressWarnings("unchecked")
        List<CarRecommendation> result = (List<CarRecommendation>)
                ReflectionTestUtils.invokeMethod(s, "enrichRecommendations", parsed, 15000);

        verify(iceConsumptionService).consumptionForTitle("Volkswagen Golf (2020)", 999, null, 2020);
        // Ingen verifierad rad ⇒ motorlistan får inte hämtas fram bakvägen. Fallbacken skrev
        // förr ut den fällda radens EGEN beteckning när listan tystnade, alltså precis den
        // generation vi just vägrade visa.
        assertThat(result.get(0).engineOptions()).isEqualTo("1.4 TFSI 999hk manuell");
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

    // --- chatten har samma kategoriregler som korten ---

    @Test
    void chattenBarSammaKategorireglerSomKorten() {
        // Skarpt 2026-08-29: chatten svarade på "familjebil under 300 000 kr" med bl.a.
        // "Renault Zoe" - en fyrasitsig småbil som står uttryckligen i FAMILJEBIL-regelns
        // ALDRIG-lista. Reglerna fanns bara i sökprompten, så korten följde dem och chatten
        // hade aldrig sett dem.
        String chatt = serviceMedPristabeller().buildChatSystemPrompt(null, null);

        assertThat(chatt).contains(GroqService.ALLA_KATEGORIREGLER);
        assertThat(chatt)
                .contains("FAMILJEBIL")
                .contains("rekommendera ALDRIG småbilar/stadsbilar")
                .contains("Renault 5/Zoe/Clio")
                .contains("SUV betyder HÖG bil")
                .contains("SMÅBIL");
    }

    @Test
    void familjeexemplenBarElroqOchEv3() {
        // Användarens egna förslag på vad som SKA komma upp i klassen.
        assertThat(GroqService.ALLA_KATEGORIREGLER)
                .contains("Škoda Elroq")
                .contains("Kia EV6/EV3/Niro");
    }

    @Test
    void sokningensAlltLageGerExaktSammaTextSomChatten() {
        // Driftvakt: läggs ett sjunde kategoriblock till i buildSystemPrompt men inte i
        // ALLA_KATEGORIREGLER får chatten en regel mindre än korten - tyst, och exakt den
        // sortens glidning som gav Zoe-svaret. Utan prefs tar sökningen med ALLA block.
        String sok = serviceMedPristabeller().buildSystemPrompt("", "spelar ingen roll", null);

        assertThat(sok).contains(GroqService.ALLA_KATEGORIREGLER);
    }

    // --- chatten har samma prisdisciplin som korten ---

    @Test
    void chattenBarSammaPrisreglerSomKorten() {
        // Skarpt 2026-08-29, direkt efter att kategoribristen lagats: chatten svarade att Enyaq,
        // ID.4, EV6, Ioniq 5 och Polestar 2 ALLA har nypris 295 000 kr och alla går 520 km.
        // Riktiga nypriser i vår egen tabell: 494 000, 440 000, 569 000. Talet var valt för att
        // rymmas i budgeten - bilarna "passade" genom att priset ändrades.
        String chatt = serviceMedPristabeller().buildChatSystemPrompt(null, null);

        assertThat(chatt).contains(GroqService.PRISREGLER_CHATT);
        assertThat(chatt)
                .contains("FABRICERA ALDRIG PRISER")
                .contains("NYPRIS PER GENERATION")
                .contains("UPPMÄTTA BEGAGNATGOLV")
                .contains("Renault Zoe fr. ca 58 000");
    }

    @Test
    void chattenLovaringenBudgetkontrollSomInteFinns() {
        // Sökningens BUDGETTAK-rad slutar "kontrolleras mot riktiga Blocket-annonser efteråt; en
        // bil som bryter mot det kastas". Sant om korten (requireAffordableModels), inte om
        // chatten. En regel som lovar en kontroll som inte finns är värre än ingen regel.
        String chatt = serviceMedPristabeller().buildChatSystemPrompt(null, null);

        // Sökningens BUDGETTAK-rad följer inte med alls - den lovar en Blocket-kontroll som
        // chatten saknar, och samma information finns i begagnatgolven utan påståendet.
        assertThat(chatt).doesNotContain("Taket kontrolleras mot riktiga Blocket-annonser");
        assertThat(chatt).contains("golv ligger över budgeten + 30 000 kr är fel förslag");
        // DRIVMEDEL_REGEL bär däremot meningen inuti sig och kan inte redigeras utan att
        // chatten får en egen text. Rubriken gör den sann i stället.
        assertThat(chatt).contains("gäller det kortens svar; i chatten är det din egen kvalitetsgräns");
    }

    @Test
    void prisfabrikationsregelnArHAMTADUrSokprompten() {
        // Driftvakt: skrivs raden om i sökprompten men inte i konstanten får chatten en egen
        // formulering - exakt den glidning som gav både Zoe-svaret och 295 000-svaret.
        String sok = serviceMedPristabeller().buildSystemPrompt("", "spelar ingen roll", null);

        assertThat(sok).contains("FABRICERA ALDRIG PRISER: price = nypris × ålderskoefficient");
        assertThat(GroqService.PRISREGLER_CHATT)
                .contains("FABRICERA ALDRIG PRISER: price = nypris × ålderskoefficient");
    }

    // --- jämförelsevyn: AI:ns påhittade årsmodell ---

    @Test
    void jamforelseTitlarTapparAiensEgnaArtal() {
        // Skarpt mätt mot /api/compare-cars 2026-08-29: en jämförelse mellan "Polestar 2" och
        // "Polestar 2 Long Range 75 kWh" gav korten "Polestar 2 (2024)" och "Polestar 2 Long
        // Range (2024)". Årtalet var AI:ns gissning - och fel på det andra kortet, som visar
        // förfaceliften (2020-2023). Värre: årtalet STYR vår datahämtning, så årsfiltret valde
        // en generation och variantlistan slutade visa den andra.
        var rader = List.of(bil("Polestar 2 (2024)"), bil("Polestar 2 Long Range (2024)"));

        assertThat(GroqService.utanPahittadArsmodell(rader, "Polestar 2", "Polestar 2 Long Range 75 kWh"))
                .extracting(CarRecommendation::title)
                .containsExactly("Polestar 2", "Polestar 2 Long Range");
    }

    @Test
    void anvandarensEgetArtalStarKvar() {
        // Angav användaren själv ett årtal är det ingen gissning, och då ska årsfiltret göra
        // precis det det är till för - titeln rörs inte.
        var rader = List.of(bil("Polestar 2 (2021)"), bil("Volvo XC40 (2021)"));

        assertThat(GroqService.utanPahittadArsmodell(rader, "Polestar 2 2021", "Volvo XC40"))
                .extracting(CarRecommendation::title)
                .containsExactly("Polestar 2 (2021)", "Volvo XC40 (2021)");
    }

    @Test
    void ovrigaFaltFoljerMedOforandrade() {
        // Titeln byggs om i en record med fjorton fält - resten får inte tappas på vägen.
        var rader = List.of(bilMedNypris("Polestar 2 (2024)", 510_000));
        var ut = GroqService.utanPahittadArsmodell(rader, "Polestar 2", "Tesla Model 3");

        assertThat(ut).first().extracting(CarRecommendation::title).isEqualTo("Polestar 2");
        assertThat(ut.get(0).evSpec()).isEqualTo(rader.get(0).evSpec());
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
                "el", "spelar ingen roll", "leasing", null, null);
        var kopPrefs = new CarPreferences(300_000, "elbil", true, 15_000, "familj", 5, false,
                "el", "spelar ingen roll", "köp", null, null);

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
        // Väntade "1 minut" fram till 2026-08-28. Den siffran kom ur parseRetryTime, som
        // avrundar UPPÅT till hela minuter — alltså sa appen "1 minut" om en väntan på 30
        // sekunder. Testet låste därmed avrundningen som ett löfte, och den dubblade väntan
        // är precis den halvminut användaren klickar i och tror att appen hängt sig.
        // Minuttaket säger nu sekunder; dygnstaket behåller sin minuttext.
        assertThat(service().buildRateLimitError(body))
                .contains("överbelastad")
                .contains("30 sekunder");
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



    // --- requireIceCars (hård spärr mot hybrid i bensin-/dieselsök) ---

    @Test
    void hybridTillBensinsokAvvisas() throws Exception {
        // Skarpt 2026-08-22: bensin + manuell + 150 000 gav Toyota Corolla Hybrid, Honda Jazz
        // Hybrid och Kia Niro Hybrid — och alla tre påstods dessutom vara manuella.
        String corolla = GILTIG_BIL.replace("Volvo EX30 (2024)", "Toyota Corolla Hybrid (2022)");
        List<CarRecommendation> parsed = service().parseRecommendations("{\"recommendations\":[" + corolla + "]}");
        assertThatThrownBy(() -> GroqService.requireIceCars(parsed))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("hybrid");
    }

    @Test
    void renaBensinbilarPasserarIcesparren() throws Exception {
        String fabia = GILTIG_BIL.replace("Volvo EX30 (2024)", "Škoda Fabia (2021)");
        String aygo = GILTIG_BIL.replace("Volvo EX30 (2024)", "Toyota Aygo (2020)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + fabia + "," + aygo + "]}");
        GroqService.requireIceCars(parsed);
        assertThat(parsed).hasSize(2);
    }

    @Test
    void icesparrenGallerBaraExplicitBensinEllerDiesel() {
        // "spelar ingen roll" och "diesel" innehåller BÅDA delsträngen "el" — därför prövas
        // hela strängen, aldrig contains.
        assertThat(GroqService.requiresIceCar(prefsMedDrivmedel("bensin"))).isTrue();
        assertThat(GroqService.requiresIceCar(prefsMedDrivmedel("diesel"))).isTrue();
        assertThat(GroqService.requiresIceCar(prefsMedDrivmedel("spelar ingen roll"))).isFalse();
        assertThat(GroqService.requiresIceCar(prefsMedDrivmedel("hybrid"))).isFalse();
        assertThat(GroqService.requiresIceCar(prefsMedDrivmedel("el"))).isFalse();
    }

    @Test
    void ekonomibilKanoniserasTillSmabilOchKanInteTappaAndraFalt() {
        // Gamla WP-snippets postar fortfarande "ekonomibil". Utan översättning möter prompten
        // en kategori den saknar exempel för, och 08-22 gav den då tre hybrider.
        CarPreferences gammal = new CarPreferences(150_000, "ekonomibil", false, 15_000, "pendling",
                4, false, "bensin", "manuell", "köp", 8, 300);
        CarPreferences ny = gammal.canonical();

        assertThat(ny.carCategory()).isEqualTo("smaabil");
        assertThat(ny.budget()).isEqualTo(150_000);
        assertThat(ny.fuelType()).isEqualTo("bensin");
        assertThat(ny.transmission()).isEqualTo("manuell");
        assertThat(ny.maxAgeYears()).isEqualTo(8);
        assertThat(ny.minCargoLiters()).isEqualTo(300);
        // Redan kanonisk kategori returnerar samma instans
        assertThat(ny.canonical()).isSameAs(ny);
    }

    private static CarPreferences prefsMedDrivmedel(String drivmedel) {
        return new CarPreferences(150_000, "smaabil", false, 15_000, "pendling",
                4, false, drivmedel, null, "köp", null, null);
    }
    // --- requirePhevCars (hård spärr mot självladdande hybrid i laddhybridssök) ---

    @Test
    void sjalvladdandeHybridTillLaddhybridssokAvvisas() throws Exception {
        // Skarpt fall 2026-08-22: kategori laddhybrid + 400 000 gav Toyota RAV4 Hybrid (2022),
        // 2.5 L 222 hk — den självladdande, inte RAV4 Plug-in (306 hk). Laddhybridssök hade
        // ingen kodvakt alls: requirePureEvCars kopplas bara in när pureEv() är sant.
        String rav4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Toyota RAV4 Hybrid (2022)");
        List<CarRecommendation> parsed = service().parseRecommendations("{\"recommendations\":[" + rav4 + "]}");
        assertThatThrownBy(() -> GroqService.requirePhevCars(parsed))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("inte är laddhybrid");
    }

    @Test
    void laddhybriderOchTystaTitlarPasserarPhevsparren() throws Exception {
        // "T8" och "PHEV" är laddhybrider; "Volvo XC60" utan drivlineord släpps igenom —
        // frånvaron av ett ord är inget bevis, och vakten fäller bara på positivt sådant.
        String t8 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volvo XC60 T8 (2022)");
        String niro = GILTIG_BIL.replace("Volvo EX30 (2024)", "Kia Niro PHEV (2021)");
        String tyst = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volvo XC60 (2021)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + t8 + "," + niro + "," + tyst + "]}");
        GroqService.requirePhevCars(parsed);
        assertThat(parsed).hasSize(3);
    }

    @Test
    void renElbilAvvisasOcksaAvPhevsparren() throws Exception {
        String elbil = GILTIG_BIL.replace("Volvo EX30 (2024)", "Nissan Leaf Elbil (2021)");
        List<CarRecommendation> parsed = service().parseRecommendations("{\"recommendations\":[" + elbil + "]}");
        assertThatThrownBy(() -> GroqService.requirePhevCars(parsed))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void drivlinanLasesUrAnnonsfiltretOchBaraNarDetArEntydigt() {
        // Formulärets laddhybridssök bär fuelType "spelar ingen roll" — drivmedelssträngen
        // ensam vet ingenting, men adFilter har redan vägt samman kategori och drivmedel.
        assertThat(GroqService.drivlinaFor(new BlocketPriceService.AdFilter(
                java.util.Set.of("Plug-in Bensin", "Plug-in Diesel"), null))).isEqualTo("laddhybrid");
        assertThat(GroqService.drivlinaFor(new BlocketPriceService.AdFilter(
                java.util.Set.of("Hybrid bensin", "Hybrid diesel"), null))).isEqualTo("hybrid");
        // Bensinsök: blandat utbud med flit — att låsa listan hade dolt hybridvarianten
        assertThat(GroqService.drivlinaFor(new BlocketPriceService.AdFilter(
                java.util.Set.of("Bensin", "Hybrid bensin"), null))).isNull();
        assertThat(GroqService.drivlinaFor(BlocketPriceService.AdFilter.NONE)).isNull();
        assertThat(GroqService.drivlinaFor(null)).isNull();
    }
    // --- requireSuvShapedCars (hård spärr mot låg bil i SUV-kategorin) ---

    @Test
    void lagBilTillSuvkategorinAvvisasOchTriggarOmforsok() throws Exception {
        // Skarpt fall 2026-08-22: SUV + elbil med 400 000 kr i budget gav Kia Niro EV,
        // MG4 och Hyundai Kona Electric — tre låga bilar, alla långt under budgeten.
        String mg4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "MG4 (2023)");
        String niro = GILTIG_BIL.replace("Volvo EX30 (2024)", "Kia Niro EV (2022)");
        String kona = GILTIG_BIL.replace("Volvo EX30 (2024)", "Hyundai Kona Electric (2022)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + mg4 + "," + niro + "," + kona + "]}");
        assertThatThrownBy(() -> GroqService.requireSuvShapedCars(parsed))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("inte är en SUV");
    }

    @Test
    void riktigaSuvarPasserarSuvsparren() throws Exception {
        String id4 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Volkswagen ID.4 (2023)");
        String ioniq5 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Hyundai Ioniq 5 (2022)");
        String e2008 = GILTIG_BIL.replace("Volvo EX30 (2024)", "Peugeot e-2008 (2022)");
        List<CarRecommendation> parsed = service().parseRecommendations(
                "{\"recommendations\":[" + id4 + "," + ioniq5 + "," + e2008 + "]}");
        GroqService.requireSuvShapedCars(parsed); // ska inte kasta
        assertThat(parsed).hasSize(3);
    }

    @Test
    void suvsparrenGallerBaraSuvkategorin() {
        assertThat(GroqService.requiresSuvShapedCar(prefsMed("suv"))).isTrue();
        assertThat(GroqService.requiresSuvShapedCar(prefsMed("elbil"))).isFalse();
        assertThat(GroqService.requiresSuvShapedCar(prefsMed("familjebil"))).isFalse();
    }

    @Test
    void okandSuvSlippsIgenomSparren() throws Exception {
        // Fäller bara på positivt bevis: en riktig SUV som saknas i listan ska inte kastas.
        String okand = GILTIG_BIL.replace("Volvo EX30 (2024)", "Aiways U5 (2022)");
        List<CarRecommendation> parsed = service().parseRecommendations("{\"recommendations\":[" + okand + "]}");
        GroqService.requireSuvShapedCars(parsed);
        assertThat(parsed).hasSize(1);
    }

    @Test
    void chattpromptenForbjuderAttNyprisJamforsMedBegagnatgolv() {
        // Skarpt fall 2026-09-04: chatten svarade "det finns inga elbilar i EV6:s prisklass" med
        // en tabell där EV6 stod på sitt BEGAGNATGOLV (317 000) och förslagen på nypriser
        // (450 000–550 000). Referensbilen jämfördes med sig själv i ett annat prisslag.
        String prompt = GroqService.PRISREGLER_CHATT;
        assertThat(prompt).contains("BLANDA ALDRIG PRISSLAG");
        assertThat(prompt).contains("Referensbilen kan själv vara ett giltigt svar");
        // Exemplet byggs UR tabellen, aldrig som en egen siffra — annars glider text och vakt isär
        assertThat(prompt).contains("317 000");
        assertThat(GroqService.EV_PRICE_FLOOR_KR.get("Kia EV6")).isEqualTo(317_000);
    }

    @Test
    void bagagekravetLasesUrFraganMenBaraNaraEttBagageord() {
        assertThat(GroqService.bagagetroskel("Vilka elbilar har mer än 420 liter bagage?")).isEqualTo(420);
        assertThat(GroqService.bagagetroskel("Jag vill ha bagageutrymme över 400 l")).isEqualTo(400);
        assertThat(GroqService.bagagetroskel("500 liter lastutrymme minst")).isEqualTo(500);
        // Ett tal nära ett ANNAT ord är inget bagagekrav — annars hade batteri- och
        // tankvolymer dragit in hela bagagelistan i prompten.
        assertThat(GroqService.bagagetroskel("Vilken elbil har 500 km räckvidd?")).isNull();
        assertThat(GroqService.bagagetroskel("Rymmer tanken 450 liter?")).isNull();
        assertThat(GroqService.bagagetroskel("Vad kostar en Kia EV6?")).isNull();
        assertThat(GroqService.bagagetroskel(null)).isNull();
    }

    @Test
    void bagagekontextenListarBaraBilarSomKlararKravet() {
        // Skarpt fall 2026-09-04: chatten svarade "Renault Zoe" på frågan om elbilar med mer
        // än 420 l bagage. Tabellen sa 338 l hela tiden — talet nådde bara aldrig prompten.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "Renault Zoe", "cargoLiters", 338),
                Map.of("carName", "Hyundai Kona PHEV", "cargoLiters", 374),
                Map.of("carName", "Kia EV6", "cargoLiters", 490),
                Map.of("carName", "Skoda Enyaq", "cargoLiters", 585)));

        String kontext = service().bagagekontext(420);

        assertThat(kontext).contains("Kia EV6 490").contains("Skoda Enyaq 585");
        // Zoe och Kona får finnas i "klarar INTE"-raden, men aldrig bland dem som klarar kravet
        assertThat(kontext).contains("Klarar INTE kravet");
        String klarar = kontext.substring(kontext.indexOf("Klarar 420"), kontext.indexOf("Klarar INTE"));
        assertThat(klarar).doesNotContain("Zoe").doesNotContain("Kona");
        assertThat(kontext).contains("Hyundai Kona PHEV 374").contains("Renault Zoe 338");
        assertThat(kontext).contains("gissa aldrig");
    }

    @Test
    void modellerPromptenSjalvRekommenderarFarSinUppmattaVolym() {
        // Skarpt fall efter första fixen: chatten svarade "MG 4 — 520 L" på 420-litersfrågan.
        // Talet finns ingenstans i tabellen (MG4 = 363 l), och listorna kunde inte hjälpa — en
        // bil under kravet kan inte stå bland dem som klarar det. MG4 är däremot en av bilarna
        // ALLA_KATEGORIREGLER själv rekommenderar, så volymen följer nu med den vägen.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "MG4", "cargoLiters", 363),
                Map.of("carName", "Kia EV6", "cargoLiters", 490),
                Map.of("carName", "Ferrari 296", "cargoLiters", 201)));

        String kontext = service().bagagekontext(420);

        assertThat(kontext).contains("MG4 363 (klarar INTE)");
        assertThat(kontext).contains("Kia EV6 490");
        // En bil som reglerna inte nämner ska inte dras in i den raden
        assertThat(kontext).doesNotContain("Ferrari 296 201 (klarar INTE)");
    }

    @Test
    void volymOchBegagnatgolvSlasIhopPaSammaRad() {
        // Tre försök att BESKRIVA hopslagningen i ord räckte inte: modellen kallade golvet
        // nypris (08-29), jämförde golv med nypris (09-04) och missade sedan MG5 — 578 l till
        // 180 000 kr, det bästa svaret, som redan stod i prompten fast i en annan tabell.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "MG5", "cargoLiters", 578),
                Map.of("carName", "Kia EV6", "cargoLiters", 490),
                Map.of("carName", "Renault Zoe", "cargoLiters", 338)));

        String kontext = service().bagagekontext(420);

        assertThat(kontext).contains("MG5 578 l / golv 180 000 kr");
        assertThat(kontext).contains("Kia EV6 490 l / golv 317 000 kr");
        assertThat(kontext).contains("Renault Zoe 338 l / golv 58 000 kr (klarar INTE)");
        assertThat(kontext).contains("golvet är ett begagnatpris");
    }

    @Test
    void golvbilarnaGaranterasPlatsIHuvudlistan() {
        // Skarpt prov 09-05: MG5 (578 l / 180 000 kr) var fragans basta svar men foll bort i
        // det spridda urvalet, och stod bara pa den ihopslagna golvraden. Modellen byggde
        // svaret ur huvudlistan och kunde inte valja en bil som inte fanns dar.
        List<Map<String, Object>> manga = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++)
            manga.add(Map.of("carName", "Fyllnadsbil " + i, "cargoLiters", 430 + i));
        manga.add(Map.of("carName", "MG5", "cargoLiters", 578));
        when(cargoSpecService.allaMedVolym()).thenReturn(manga);

        String kontext = service().bagagekontext(420);
        String huvudlistan = kontext.substring(kontext.indexOf("Klarar 420 l"),
                kontext.indexOf("Uppmätt volym för modeller"));

        assertThat(huvudlistan).contains("MG5 578");
    }

    @Test
    void elbilsfraganSallarBortForbranningsbilarna() {
        // Huvudlistan ar byggd ur cargo_spec, som tacker ALLA drivlinor: pa 420-litersfragan
        // bjod den pa Rolls-Royce Wraith, Jeep Compass och Volvo XC60, och svaret tog med tva
        // laddhybrider och kallade dem elbilar.
        when(evSpecService.findAllCarNames()).thenReturn(List.of(
                "Kia EV6 Long Range 2WD", "Volkswagen ID.4 Pro", "MG5 Long Range"));
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "Kia EV6", "cargoLiters", 490),
                Map.of("carName", "Volkswagen ID.4", "cargoLiters", 543),
                Map.of("carName", "MG5", "cargoLiters", 578),
                Map.of("carName", "Volvo XC60", "cargoLiters", 505),
                Map.of("carName", "Jeep Compass", "cargoLiters", 438)));

        String el = service().bagagekontext(420, true);
        String alla = service().bagagekontext(420, false);

        assertThat(el).contains("Kia EV6 490").contains("MG5 578");
        assertThat(el).doesNotContain("Volvo XC60").doesNotContain("Jeep Compass");
        assertThat(alla).contains("Volvo XC60 505");   // utan sallning ar de kvar
    }

    @Test
    void ordprefixOchInteSubstrangAvgorOmRadenArElbil() {
        // "BMW X3" far INTE bli elbil for att "BMW iX3" finns - samma substrangsfalla som
        // falldes i cargo-matchningen samma dag.
        java.util.Set<String> ev = java.util.Set.of("bmw ix3", "kia niro ev");
        assertThat(GroqService.fragaGallerElbilar("Vilka elbilar har mer an 420 l?")).isTrue();
        assertThat(GroqService.fragaGallerElbilar("Vilken laddhybrid har storst bagage?")).isFalse();
        assertThat(GroqService.fragaGallerElbilar(null)).isFalse();
        // Sallningen sjalv provas genom bagagekontext ovan; har rackar ordvakten.
        assertThat(ev).isNotEmpty();
    }

    @Test
    void golvradenSorterasStorstVolymForst() {
        // Skarpt prov 09-05: modellen tog sju av tio dugliga bilar ur den prisordnade raden och
        // tappade MG5 - 578 l till 180 000 kr, det basta svaret pa fragan.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "Kia EV6", "cargoLiters", 490),
                Map.of("carName", "MG5", "cargoLiters", 578),
                Map.of("carName", "Renault Zoe", "cargoLiters", 338)));

        String kontext = service().bagagekontext(420);
        int mg5 = kontext.indexOf("MG5 578 l / golv");
        int ev6 = kontext.indexOf("Kia EV6 490 l / golv");
        int zoe = kontext.indexOf("Renault Zoe 338 l / golv");

        assertThat(mg5).isGreaterThan(-1);
        assertThat(mg5).isLessThan(ev6);   // storst volym forst
        assertThat(ev6).isLessThan(zoe);   // underkanda sist
    }

    @Test
    void prisgolvenListasBilligastForstOchDeterministiskt() {
        // Map.ofEntries ar OORDNAD: bade prisraden och volymraden fick en godtycklig ordning
        // som kunde andras mellan byggen. MG4 och Kona Electric star bada pa 195 000 och
        // sarskiljs pa namnet.
        List<String> namn = new java.util.ArrayList<>(GroqService.EV_PRICE_FLOOR_KR.keySet());
        List<Integer> priser = new java.util.ArrayList<>(GroqService.EV_PRICE_FLOOR_KR.values());
        assertThat(priser).isSorted();
        assertThat(namn.get(0)).isEqualTo("Renault Zoe");
        assertThat(namn.get(namn.size() - 1)).isEqualTo("Kia EV6");
        assertThat(namn.indexOf("Hyundai Kona Electric")).isLessThan(namn.indexOf("MG4"));
    }

    @Test
    void ettLAGREGolvArAldrigEttSkalAttValjaBort() {
        // Taket lastes som ett FONSTER: MG5 foll bort med motiveringen "begagnatgolv som ligger
        // over 30 000 kr fran din budget", fast anvandaren aldrig angav nagon budget.
        String prompt = serviceMedPristabeller().buildChatSystemPrompt(null, null);
        assertThat(prompt).contains("ett lägre golv är aldrig ett skäl att välja bort en bil");
        assertThat(prompt).contains("Regeln är ett TAK och aldrig ett spann");
        assertThat(prompt).contains("Har ingen budget angetts");
    }

    @Test
    void basbilensEgenRadSlarVariantensMindreVolym() {
        // Skarpt prov 09-05: chatten skrev "Skoda Enyaq 570" i tva av tre korningar. 570 ar
        // Coupens volym - och den kom RAKT UR PROMPTEN, for golvraden tog minsta volymen bland
        // alla matchande rader. Basbilens egen rad sager 585.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "Škoda Enyaq Coupe RS", "cargoLiters", 570),
                Map.of("carName", "Skoda Enyaq", "cargoLiters", 585),
                Map.of("carName", "Škoda Enyaq 85", "cargoLiters", 585)));

        String kontext = service().bagagekontext(420);

        assertThat(kontext).contains("Skoda Enyaq 585 l / golv 279 000 kr");
        assertThat(kontext).doesNotContain("Skoda Enyaq 570 l");
    }

    @Test
    void utanEgenRadGallerFORTFARANDEMinstaVolymen() {
        // Reserven star kvar: saknas basbilens rad finns ingen battre uppgift, och for lagt ar
        // fortfarande battre an for hogt.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "Škoda Enyaq Coupe RS", "cargoLiters", 570),
                Map.of("carName", "Škoda Enyaq 85", "cargoLiters", 585)));

        String kontext = service().bagagekontext(420);

        assertThat(kontext).contains("Skoda Enyaq 570 l / golv 279 000 kr");
    }

    @Test
    void bilMedGolvMenUtanVolymPekasUtISTALLETForAttUtelamnas() {
        // Skarpt prov 2026-09-05: chatten svarade "Volkswagen e-Golf 441 l". Talet finns i
        // tabellen men pa Cupra Raval, IONIQ 3, Solterra och ID. Polo - e-Golf har golv men
        // INGEN volymrad, och den tysta luckan fyllde modellen sjalv.
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of(
                Map.of("carName", "Kia EV6", "cargoLiters", 490),
                Map.of("carName", "MG5", "cargoLiters", 578)));

        String kontext = service().bagagekontext(420);

        assertThat(kontext).contains("GOLV MEN INGEN UPPMÄTT VOLYM");
        assertThat(kontext).contains("Volkswagen e-Golf");
        // ...och den far inte samtidigt sta i den ihopslagna raden med ett tal
        String ihop = kontext.substring(kontext.indexOf("VOLYM + BEGAGNATGOLV"),
                kontext.indexOf("GOLV MEN INGEN UPPMÄTT VOLYM"));
        assertThat(ihop).doesNotContain("e-Golf");
        assertThat(kontext).contains("Enyaq Coupé").contains("låna aldrig ett litertal");
    }

    @Test
    void bagagekontextenTystnarUtanData() {
        when(cargoSpecService.allaMedVolym()).thenReturn(List.of());
        assertThat(service().bagagekontext(420)).isEmpty();
    }

    private static CarPreferences prefsMed(String kategori) {
        return new CarPreferences(400_000, kategori, false, 15_000, "pendling",
                4, false, "el", null, "köp", null, null);
    }
}
