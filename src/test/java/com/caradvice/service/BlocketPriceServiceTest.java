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

    @Test
    void ingenMatchandeAnnonsGerNull() {
        assertThat(service.priceRangeFrom(docs(new int[]{2020, 4_495}), 2020)).isNull();
        assertThat(service.priceRangeFrom(docs(), 2020)).isNull();
    }
}
