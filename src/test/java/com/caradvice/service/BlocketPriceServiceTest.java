package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prisspannet räknas på riktiga träfflistor. Siffrorna nedan är mätta mot Blockets API
 * 2026-08-07 — de kommer från de faktiska annonserna, inte påhittade exempel.
 */
class BlocketPriceServiceTest {

    private final BlocketPriceService service = new BlocketPriceService();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Träfflista med märke och modell, för förväxlingskontrollen. */
    private JsonNode docsMedModell(String[]... markeModellPris) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < markeModellPris.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("{\"year\":2023,\"make\":\"%s\",\"model\":\"%s\",\"price\":{\"amount\":%s}}",
                    markeModellPris[i][0], markeModellPris[i][1], markeModellPris[i][2]));
        }
        try {
            return mapper.readTree(sb.append(']').toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- fritextsökningen matchar syskonmodeller ---

    @Test
    void syskonmodellMedAnnanSiffraRaknasInte() {
        // Live-fynd: q=Volkswagen ID.4 gav ID. Buzz på 699 900 kr, som satte taket för ID.4:s
        // prisspann. Även ID.5 GTX och Tiguan Allspace kom med i samma träfflista.
        var traffar = docsMedModell(
                new String[]{"Volkswagen", "ID.4 GTX", "499800"},
                new String[]{"Volkswagen", "ID.4 Pro", "329000"},
                new String[]{"Volkswagen", "ID. Buzz", "699900"},
                new String[]{"Volkswagen", "ID.5 GTX", "459900"},
                new String[]{"Volkswagen", "Tiguan Allspace", "389000"});

        var range = service.priceRangeFrom(traffar, 2023, BlocketPriceService.modelDigits("Volkswagen ID.4"));

        assertThat(range.count()).isEqualTo(2);
        assertThat(range.minKr()).isEqualTo(329_000);
        assertThat(range.maxKr()).isEqualTo(499_800);
    }

    @Test
    void modellnamnUtanSiffraFiltrerasInteAlls() {
        // Enyaq, Model Y, Leaf — ingen siffra att jämföra, och en gissande regel hade
        // kostat riktiga annonser. Då är det bättre att inte filtrera.
        assertThat(BlocketPriceService.modelDigits("Skoda Enyaq iV")).isNull();
        assertThat(BlocketPriceService.modelDigits("Tesla Model Y")).isNull();

        var traffar = docsMedModell(new String[]{"Skoda", "Enyaq", "349000"},
                new String[]{"Skoda", "Enyaq Coupé", "419000"});

        assertThat(service.priceRangeFrom(traffar, 2023, null).count()).isEqualTo(2);
    }

    @Test
    void forstaSiffranIModellnamnetStyr() {
        assertThat(BlocketPriceService.modelDigits("Volkswagen ID.4")).isEqualTo("4");
        assertThat(BlocketPriceService.modelDigits("Volvo XC60")).isEqualTo("60");
        assertThat(BlocketPriceService.modelDigits("Kia EV3")).isEqualTo("3");
        // Audi Q4 e-tron 40: modellsiffran kommer först, effektbeteckningen ska inte styra
        assertThat(BlocketPriceService.modelDigits("Audi Q4 e-tron 40")).isEqualTo("4");
    }

    /** Bygger en träfflista: varje par är (årsmodell, pris). */
    private JsonNode docs(int[]... arsmodellOchPris) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arsmodellOchPris.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("{\"year\":%d,\"price\":{\"amount\":%d,\"price_unit\":\"kr\"}}",
                    arsmodellOchPris[i][0], arsmodellOchPris[i][1]));
        }
        try {
            return mapper.readTree(sb.append(']').toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void billigasteAnnonsenArUndreGransen() {
        // Live-fynd: EV3 visades från 434 900 kr (20:e percentilen) medan billigaste
        // annonsen låg på 369 900 kr — 65 000 kr fel på en siffra budgettaket mäts mot
        var ev3 = docs(new int[]{2025, 369_900}, new int[]{2025, 409_800}, new int[]{2026, 429_900},
                new int[]{2026, 469_900}, new int[]{2026, 479_000}, new int[]{2025, 508_300});

        var range = service.priceRangeFrom(ev3, 2025);

        assertThat(range.minKr()).isEqualTo(369_900);
        assertThat(range.maxKr()).isEqualTo(508_300);
        assertThat(range.count()).isEqualTo(6);
    }

    @Test
    void fabriceradArsmodellGerIngetPrisAlls() {
        // Kia EV3 (2022) — modellen fanns inte då. Tidigare gav samma sökning hela
        // modellens alla årsmodeller, alltså ett prisspann för en bil som inte existerar.
        var ev3 = docs(new int[]{2025, 369_900}, new int[]{2026, 479_000}, new int[]{2027, 508_300});

        assertThat(service.priceRangeFrom(ev3, 2022)).isNull();
    }

    @Test
    void arsmodellenAvgorSpannet() {
        // Samma modell, olika år: utan filtrering fick en Leaf 2018 och en Leaf 2022
        // identiskt spann eftersom API:ts årsparametrar ignoreras
        var leaf = docs(new int[]{2018, 69_500}, new int[]{2018, 89_900}, new int[]{2019, 99_900},
                new int[]{2022, 179_900}, new int[]{2022, 199_900}, new int[]{2023, 239_900});

        assertThat(service.priceRangeFrom(leaf, 2018).maxKr()).isEqualTo(99_900);
        assertThat(service.priceRangeFrom(leaf, 2022).minKr()).isEqualTo(179_900);
    }

    @Test
    void arsmodellenSlapperIgenomEttArAtVarderaHallet() {
        var bilen = docs(new int[]{2020, 150_000}, new int[]{2022, 190_000}, new int[]{2024, 260_000});

        var range = service.priceRangeFrom(bilen, 2021);

        assertThat(range.count()).isEqualTo(2);   // 2020 och 2022, inte 2024
        assertThat(range.maxKr()).isEqualTo(190_000);
    }

    @Test
    void privatleasingRaknasInteSomKoppris() {
        // Leasingannonser ligger i samma träfflista med månadsavgiften i price.amount
        // och samma price_unit "kr" — 4 495 kr/mån är inget bilpris
        var ev3 = docs(new int[]{2026, 4_495}, new int[]{2026, 4_495}, new int[]{2026, 3_795},
                new int[]{2026, 429_900}, new int[]{2026, 479_000});

        var range = service.priceRangeFrom(ev3, 2026);

        assertThat(range.count()).isEqualTo(2);
        assertThat(range.minKr()).isEqualTo(429_900);
    }

    @Test
    void uppenbarFluffannonsDrarInteNerSpannet() {
        // Under 40 % av medianen: en "1 kr"-lockannons eller felskrivet pris ska inte
        // bli bilens lägstapris — men bara sådana avvikare tas bort
        var bilen = docs(new int[]{2022, 25_000}, new int[]{2022, 189_900}, new int[]{2022, 199_900},
                new int[]{2022, 209_900}, new int[]{2022, 219_900});

        assertThat(service.priceRangeFrom(bilen, 2022).minKr()).isEqualTo(189_900);
    }

    @Test
    void orimligtHogtPrisDrarInteUppSpannet() {
        var bilen = docs(new int[]{2022, 189_900}, new int[]{2022, 199_900}, new int[]{2022, 209_900},
                new int[]{2022, 219_900}, new int[]{2022, 1_900_000});

        assertThat(service.priceRangeFrom(bilen, 2022).maxKr()).isEqualTo(219_900);
    }

    @Test
    void utanArtalIRubrikenGallerAllaArsmodeller() {
        var bilen = docs(new int[]{2015, 89_900}, new int[]{2020, 149_900}, new int[]{2025, 249_900});

        assertThat(service.priceRangeFrom(bilen, null).count()).isEqualTo(3);
    }

    // --- leasing är ett eget prisläge, inte en billig bil ---

    @Test
    void leasingRaknasIKronorPerManad() {
        // Samma sökning, andra annonser: 4 495 kr/mån är inte 4 495 kr för en bil
        var ev3 = docs(new int[]{2026, 4_495}, new int[]{2026, 4_795}, new int[]{2026, 6_995},
                new int[]{2026, 479_000});

        var range = service.leasingRangeFrom(ev3, 2026);

        assertThat(range.minKr()).isEqualTo(4_495);
        assertThat(range.maxKr()).isEqualTo(6_995);
        assertThat(range.count()).isEqualTo(3);          // köpannonsen räknas inte med
        assertThat(range.formatted()).contains("kr/mån");
    }

    @Test
    void kopochLeasingDelarAldrigSammaSpann() {
        // Blockets sales_form räcker inte som skiljelinje: mätt 2026-08-07 låg leasingannonser
        // på 4 495 kr märkta "begagnad till salu". Beloppets storlek är enda tillförlitliga testet.
        var blandat = docs(new int[]{2026, 4_495}, new int[]{2026, 479_000}, new int[]{2026, 509_000});

        assertThat(service.priceRangeFrom(blandat, 2026).minKr()).isEqualTo(479_000);
        assertThat(service.leasingRangeFrom(blandat, 2026).maxKr()).isEqualTo(4_495);
    }

    @Test
    void leasingBrydSigInteOmArsmodellen() {
        // Leasingannonserna ligger på 2026-2027 medan AI:n sätter ett begagnat-årtal i titeln.
        // Med årsfilter hade träfflistan tömts — därför skickas year=null för leasing.
        var ev3 = docs(new int[]{2026, 4_495}, new int[]{2027, 4_795});

        assertThat(service.leasingRangeFrom(ev3, null)).isNotNull();
        assertThat(service.leasingRangeFrom(ev3, 2022)).isNull();   // vad årsfiltret hade gjort
    }

    @Test
    void koppprisIEnLeasingannonsRaknasInteSomManadskostnad() {
        // Live-fynd: en ID.4 låg som leasingannons med 539 500 kr i månadsfältet
        var ev = docs(new int[]{2027, 539_500}, new int[]{2027, 4_495}, new int[]{2026, 4_695});

        var range = service.leasingRangeFrom(ev, null);

        assertThat(range.count()).isEqualTo(2);
        assertThat(range.maxKr()).isEqualTo(4_695);
    }

    @Test
    void ingenLeasingannonsGerNull() {
        var barakop = docs(new int[]{2026, 479_000}, new int[]{2026, 509_000});

        assertThat(service.leasingRangeFrom(barakop, 2026)).isNull();
    }

    @Test
    void ingenMatchandeAnnonsGerNull() {
        assertThat(service.priceRangeFrom(docs(new int[]{2020, 4_495}), 2020)).isNull();
        assertThat(service.priceRangeFrom(docs(), 2020)).isNull();
    }
}
