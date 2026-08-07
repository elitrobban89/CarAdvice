package com.caradvice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Officiella privatleasingpriser. Testdatan är formen som källorna faktiskt svarar med,
 * mätt 2026-08-07: VW Financial Services JSON för koncernmärkena, Volvos sidmarkup för Volvo.
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

    @Test
    void okantMarkeGerIngenTraff() {
        // Toyota, Kia, Tesla har egna sajter med egna strukturer — de tacks inte
        assertThat(service.offerForTitle("Toyota Yaris (2024)")).isNull();
        assertThat(service.offerForTitle("Enyaq")).isNull();      // utan märke går det inte att veta
        assertThat(service.offerForTitle(null)).isNull();
    }
}
