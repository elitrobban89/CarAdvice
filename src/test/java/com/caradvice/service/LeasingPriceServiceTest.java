package com.caradvice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Officiella privatleasingpriser. Testdatan är formen som källorna faktiskt svarar med,
 * mätt 2026-08-07: VW Financial Services JSON för koncernmärkena, Volvos sidmarkup för Volvo.
 *
 * @author Robert Andersson Kopler
 */
class LeasingPriceServiceTest {

    private final LeasingPriceService service = new LeasingPriceService();
    private final ObjectMapper mapper = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode skodaSvar() throws Exception {
        return mapper.readTree("""
            [
              {"title":"Enyaq","preamble":"Privatleasing från 5 295 kr/mån",
               "subCategories":[{"title":"Enyaq 85 Selection Solid Edition",
                                 "preamble":"Privatleasing från 5 295 kr/mån","subCategories":[]}]},
              {"title":"Enyaq Coupé","preamble":"Privatleasing från 6 925 kr/mån","subCategories":[]},
              {"title":"Nya Epiq","preamble":"Privatleasing från 3 895 kr/mån","subCategories":[]},
              {"title":"Kamiq","preamble":"Privatleasing från 3 100 kr/mån","subCategories":[]},
              {"title":"Utan pris","preamble":"Kommer snart","subCategories":[]}
            ]
            """);
    }

    @Test
    void modellerOchPriserPlockasUrKategorierna() throws Exception {
        var offers = service.parse(skodaSvar(), "skoda");

        assertThat(offers).extracting(LeasingPriceService.LeasingOffer::model)
                .contains("Enyaq", "Enyaq Coupé", "Epiq", "Kamiq")
                .doesNotContain("Utan pris");   // utan pris är erbjudandet inget erbjudande
    }

    @Test
    void nyaStrypsUrModellnamnet() {
        // "Nya Epiq" är marknadsföring, inte modellnamn
        assertThat(LeasingPriceService.cleanModel("Nya Epiq")).isEqualTo("Epiq");
        assertThat(LeasingPriceService.cleanModel("Enyaq Coupé")).isEqualTo("Enyaq Coupé");
    }

    @Test
    void manadsprisetLasesUrPreamblen() {
        assertThat(LeasingPriceService.monthlyFrom("Privatleasing från 5 295 kr/mån")).isEqualTo(5_295);
        assertThat(LeasingPriceService.monthlyFrom("Privatleasing från 12 995 kr/mån")).isEqualTo(12_995);
        assertThat(LeasingPriceService.monthlyFrom("Kommer snart")).isNull();
        assertThat(LeasingPriceService.monthlyFrom(null)).isNull();
    }

    @Test
    void volvosSidmarkupGerModellOchPris() {
        // Formen på volvocars.com/se/car-finance/operating-lease
        String html = """
            <a data-x="data:offerSelector:prices[xc90-hybrid].contract_hire,data:was">
              <span>Från <span><span data-autoid="car-price">12 995 kr/mån</span></span></span></a>
            <a data-x="data:offerSelector:prices[ex30-electric].contract_hire,data:was">
              <span>Från <span><span data-autoid="car-price">3 895 kr/mån</span></span></span></a>
            <p>Kör EX90 inklusive service och försäkring</p><p>Pris från 15 695 kr/mån</p>
            """;

        var offers = service.parseVolvo(html);

        assertThat(offers).extracting(LeasingPriceService.LeasingOffer::model)
                .containsExactly("XC90", "EX30")
                .doesNotContain("EX90");   // Care by Volvo är ett abonnemang, inte privatleasing
        assertThat(offers.get(0).monthlyKr()).isEqualTo(12_995);
        assertThat(offers.get(1).monthlyKr()).isEqualTo(3_895);
    }

    private static LeasingPriceService.LeasingOffer offer(String model, int kr, String brand) {
        return new LeasingPriceService.LeasingOffer(model, kr, brand);
    }

    @Test
    void utrustningsnivaMatcharModellenMenSyskonmodellenInte() {
        var utbud = List.of(offer("Enyaq", 5_295, "skoda"), offer("Enyaq Coupé", 6_925, "skoda"));

        // Utrustningsnivån hör till modellen
        assertThat(LeasingPriceService.bestMatch(utbud, "skoda", List.of("enyaq", "iv", "80")).monthlyKr())
                .isEqualTo(5_295);
        // Syskonmodellen har sitt eget pris och ska inte ärva det lägre
        assertThat(LeasingPriceService.bestMatch(utbud, "skoda", List.of("enyaq", "coupé")).monthlyKr())
                .isEqualTo(6_925);
    }

    @Test
    void marketIUtbudetsModellnamnStorInte() {
        // Cupra listar sina modeller som "CUPRA Born" medan titeln redan bär märket
        var utbud = List.of(offer("CUPRA Born*/**", 3_895, "cupra"));

        assertThat(LeasingPriceService.bestMatch(utbud, "cupra", List.of("born")).monthlyKr())
                .isEqualTo(3_895);
    }

    @Test
    void modellSomInteFinnsIUtbudetGerNull() {
        // Ingen träff är ett svar: modellen går inte att privatleasa
        var utbud = List.of(offer("Enyaq", 5_295, "skoda"), offer("Kamiq", 3_100, "skoda"));

        assertThat(LeasingPriceService.bestMatch(utbud, "skoda", List.of("octavia"))).isNull();
    }

    // --- phevUrUtbud: vilka laddhybrider som faktiskt går att privatleasa ---

    /**
     * Ordagranna erbjudandenamn ur de fem VWFS-kanalerna 2026-09-20. Blandningen är hela
     * poängen: laddhybriderna ligger bland elbilar, mildhybrider och rena bensinbilar, och det
     * är DEM sållningen ska skilja ut.
     */
    private static List<LeasingPriceService.LeasingOffer> katalogen() {
        return List.of(
                offer("Kodiaq Selection Explore Edition iV", 5_280, "skoda"),
                offer("Kodiaq Sportline Explore Edition iV", 5_645, "skoda"),
                offer("Kodiaq Selection Explore", 4_430, "skoda"),          // bensin
                offer("Superb Combi Selection Explore Edition iV", 4_995, "skoda"),
                offer("Superb Combi L&K Explore Edition iV", 5_350, "skoda"),
                offer("Superb Combi Selection Explore", 5_325, "skoda"),    // bensin
                offer("Octavia Combi Selection", 3_480, "skoda"),           // ingen iV langre
                offer("Enyaq 85 Selection Solid Edition", 5_295, "skoda"),  // elbil
                offer("Passat Sportscombi Edition eHybrid", 4_995, "vw"),
                offer("Passat R-Line SWE Edition eHybrid", 5_295, "vw"),
                offer("Tiguan R-Line SWE Edition eHybrid", 4_995, "vw"),
                offer("Tiguan Life Edition", 3_495, "vw"),                  // bensin
                offer("Golf Life Edition", 3_495, "vw"),                    // ingen GTE langre
                offer("ID.4 Pro Edition", 4_995, "vw"),                     // elbil
                offer("A3 40 TFSI e Proline", 4_995, "audi"),
                offer("A3 35 TFSI S line", 3_595, "audi"),                  // bensin
                offer("A3 Sportback e-hybrid Proline", 4_995, "audi"),
                offer("A5 Avant e-hybrid quattro Proline Edition", 5_395, "audi"),
                offer("A2 e-tron Proline", 4_995, "audi"),                  // elbil
                offer("Q6 e-tron quattro Proline Edition", 8_895, "audi"),  // elbil
                offer("CUPRA Leon Sportstourer Swedish Edition e-HYBRID", 4_395, "cupra"),
                offer("CUPRA Formentor Swedish Edition e-HYBRID", 4_695, "cupra"),
                offer("CUPRA Formentor eTSI 150hk DSG", 3_495, "cupra"),    // mildhybrid
                offer("Ibiza Style Edition", 2_845, "seat"));               // bensin
    }

    @Test
    void bara_laddhybriderna_kommer_med() {
        var phev = LeasingPriceService.phevUrUtbud(katalogen());
        var namn = phev.stream().map(LeasingPriceService.LeasingOffer::model).toList();

        // Elbilarna faller korrekt: "e-tron" och "ID.4" ar inga laddhybridbadgar
        assertThat(namn).noneMatch(n -> n.contains("e-tron") || n.contains("ID.4") || n.contains("Enyaq"));
        // Mildhybriden och bensinbilarna likasa - "eTSI" och "35 TFSI" ar inte "TFSI e"
        assertThat(namn).noneMatch(n -> n.contains("eTSI") || n.contains("35 TFSI") || n.contains("Ibiza"));
        // ...och alla fyra badgarna fangas: iV, eHybrid, TFSI e och e-HYBRID
        assertThat(namn).anyMatch(n -> n.contains("Superb Combi Selection Explore Edition iV"));
        assertThat(namn).anyMatch(n -> n.contains("eHybrid"));
        assertThat(namn).anyMatch(n -> n.contains("TFSI e"));
        assertThat(namn).anyMatch(n -> n.contains("e-HYBRID"));
    }

    @Test
    void en_rad_per_modell_den_billigaste() {
        var phev = LeasingPriceService.phevUrUtbud(katalogen());

        // Superb iV ligger som tre trimnivaer i katalogen; bara den billigaste ska med
        assertThat(phev).filteredOn(o -> o.model().startsWith("Superb")).hasSize(1);
        assertThat(phev).filteredOn(o -> o.model().startsWith("Superb"))
                .allMatch(o -> o.monthlyKr() == 4_995);
        assertThat(phev).filteredOn(o -> o.model().startsWith("Kodiaq"))
                .singleElement().matches(o -> o.monthlyKr() == 5_280);
        // Cupra listar market i sitt eget modellnamn - det far inte bli nyckeln, annars hade
        // ALLA cupror slagits ihop till en rad
        assertThat(phev).filteredOn(o -> "cupra".equals(o.brand())).hasSize(2);
    }

    @Test
    void billigast_forst_och_tomt_utbud_ger_tom_lista() {
        var phev = LeasingPriceService.phevUrUtbud(katalogen());

        assertThat(phev).isNotEmpty();
        assertThat(phev.get(0).monthlyKr()).isEqualTo(4_395);   // Cupra Leon Sportstourer
        assertThat(phev).isSortedAccordingTo(
                java.util.Comparator.comparingInt(LeasingPriceService.LeasingOffer::monthlyKr));
        // Svarar katalogerna inte alls ska raden tiga, inte gissa
        assertThat(LeasingPriceService.phevUrUtbud(List.of())).isEmpty();
    }

    @Test
    void laddhybridFarAldrigMildhybridensPris() {
        // Skarpt i drift 2026-09-20: kortet "Cupra Leon Sportstourer e-Hybrid" fick 3 595
        // kr/man - priset pa "Leon Sportstourer eTSI 150hk DSG", alltsa MILDHYBRIDEN.
        // Laddhybriden kostar 4 395. Ordmatchningen var drivlineblind, och bland lika
        // specifika traffar vinner lagsta priset - som var fel bils.
        var utbud = List.of(
                offer("CUPRA Leon Sportstourer", 3_595, "cupra"),
                offer("CUPRA Leon Sportstourer eTSI 150hk DSG", 3_595, "cupra"),
                offer("CUPRA Leon Sportstourer Swedish Edition e-HYBRID", 4_395, "cupra"));

        var traff = LeasingPriceService.bestMatch(utbud, "cupra",
                List.of("leon", "sportstourer", "e-hybrid"));
        assertThat(traff).isNotNull();
        assertThat(traff.monthlyKr()).isEqualTo(4_395);

        // ...och at andra hallet: mildhybriden ska inte fa laddhybridens pris heller
        var mild = LeasingPriceService.bestMatch(utbud, "cupra", List.of("leon", "sportstourer"));
        assertThat(mild.monthlyKr()).isEqualTo(3_595);
    }

    @Test
    void laddhybridenHittarSinFamiljAvenNarTrimraderaSkiljerSig() {
        // Katalogen: "Superb Combi Selection Explore Edition iV". AI:n: "Superb iV Combi".
        // Ingen ar en INLEDNING av den andra - orden kommer i olika ordning och trimnivan
        // ligger emellan - sa bada korten stod utan katalogpris i drift. Familjen plus
        // drivlinan racker for ett FRAN-pris, och det billigaste vinner.
        var utbud = List.of(
                offer("Superb Combi Selection Explore", 5_325, "skoda"),          // bensin
                offer("Superb Combi Selection Explore Edition iV", 4_995, "skoda"),
                offer("Superb Combi L&K Explore Edition iV", 5_350, "skoda"));

        var traff = LeasingPriceService.bestMatch(utbud, "skoda", List.of("superb", "iv", "combi"));
        assertThat(traff).isNotNull();
        assertThat(traff.monthlyKr()).isEqualTo(4_995);
    }

    @Test
    void skodasIvBetyderOlikaSakerITitelnOchIKatalogen() {
        // I katalogen ar iV entydigt en laddhybrid (Enyaq/Elroq/Epiq star utan suffix). I en
        // TITEL ar den det inte - Skoda kallade elbilen "Enyaq iV" i flera ar. Utan undantaget
        // blev elbilstiteln en laddhybrid och tappade sitt leasingpris helt.
        assertThat(LeasingPriceService.titelnBarLaddhybridsbadge(List.of("enyaq", "iv", "80")))
                .isFalse();
        assertThat(LeasingPriceService.titelnBarLaddhybridsbadge(List.of("superb", "iv", "combi")))
                .isTrue();
        // ...och en Skoda-elbil som OCKSA bar en riktig laddhybridbadge ar fortfarande laddhybrid
        assertThat(LeasingPriceService.titelnBarLaddhybridsbadge(List.of("kodiaq", "iv"))).isTrue();
        assertThat(LeasingPriceService.titelnBarLaddhybridsbadge(List.of("leon", "e-hybrid"))).isTrue();
        assertThat(LeasingPriceService.titelnBarLaddhybridsbadge(List.of("enyaq", "coupe"))).isFalse();
    }

    @Test
    void utanLaddhybridIUtbudetGesIngetPrisAlls() {
        // Ett FELAKTIGT pris ar varre an inget: AI:ns gissning ar atminstone markt som en
        // uppskattning, medan katalogpriset skrivs ut som markets eget.
        var utbud = List.of(
                offer("Octavia Combi Selection", 3_480, "skoda"),
                offer("Octavia Combi Sportline", 4_550, "skoda"));

        assertThat(LeasingPriceService.bestMatch(utbud, "skoda", List.of("octavia", "iv")))
                .isNull();
    }

    @Test
    void okantMarkeGerIngenTraff() {
        // Toyota, Kia, Tesla har egna sajter med egna strukturer — de tacks inte
        assertThat(service.offerForTitle("Toyota Yaris (2024)")).isNull();
        assertThat(service.offerForTitle("Enyaq")).isNull();      // utan märke går det inte att veta
        assertThat(service.offerForTitle(null)).isNull();
    }
}
