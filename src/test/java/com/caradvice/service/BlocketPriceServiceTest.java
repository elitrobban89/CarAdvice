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

    // --- milgränsen: billigaste annonsen får inte vara marknadens mest slitna bil ---

    @Test
    void slitnaExemplarSatterInteGolvet() {
        // Live 2026-08-08: en 200 000-budget fick Skoda Enyaq med prisraden "från 229 900 kr".
        // Den annonsen hade gått 21 091 mil, och eftersom budgettaket mäts mot billigaste
        // annonsen slank bilen under taket — billigaste exemplaret under 10 000 mil kostade
        // 339 900 kr. Samma bild för ID.4 (249 000 mot 309 900) och EV6 (279 900 mot 316 990).
        var enyaq = docsMedMil(new int[]{2024, 229_900, 21_091}, new int[]{2024, 269_900, 15_100},
                new int[]{2024, 339_900, 7_010}, new int[]{2024, 359_000, 4_200});

        var utan = service.priceRangeFrom(enyaq, 2024, null);
        var med = service.priceRangeFrom(enyaq, 2024, null, BlocketPriceService.MAX_MILEAGE_MIL);

        assertThat(utan.minKr()).isEqualTo(229_900);
        assertThat(med.minKr()).isEqualTo(339_900);
        assertThat(med.count()).isEqualTo(2);
    }

    @Test
    void saknadKorstrackaSlappsIgenom() {
        // Fältet är tomt på just de leasingannonser som ändå faller på prisgolvet. Att kasta
        // okända hade strukit riktiga annonser den dagen Blocket slutar fylla i fältet.
        var blandat = docsMedMil(new int[]{2024, 289_900, -1}, new int[]{2024, 319_900, 5_000},
                new int[]{2024, 259_900, 18_000});

        var range = service.priceRangeFrom(blandat, 2024, null, BlocketPriceService.MAX_MILEAGE_MIL);

        assertThat(range.minKr()).isEqualTo(289_900);
        assertThat(range.count()).isEqualTo(2);
    }

    @Test
    void milgransenLattasISteg_inteRaktUtTillHelaMarknaden() {
        // Samma Enyaq-lista, men bara ETT exemplar under 10 000 mil — då räcker steget inte
        // till en prisrad (två annonser krävs). Förr togs hela marknaden in direkt och golvet
        // sattes av 21 091-milaren igen; nu prövas 15 000 mil först och golvet blir 269 900.
        var enyaq = docsMedMil(new int[]{2024, 229_900, 21_091}, new int[]{2024, 269_900, 15_100},
                new int[]{2024, 289_900, 12_400}, new int[]{2024, 339_900, 7_010});

        var steg1 = service.priceRangeFrom(enyaq, 2024, null, BlocketPriceService.MAX_MILEAGE_MIL);
        var steg2 = service.priceRangeFrom(enyaq, 2024, null, BlocketPriceService.RELAXED_MILEAGE_MIL);
        var helaMarknaden = service.priceRangeFrom(enyaq, 2024, null);

        assertThat(steg1.count()).isEqualTo(1);          // för tunt, trappan går vidare
        assertThat(steg2.count()).isEqualTo(2);          // räcker — här stannar den
        assertThat(steg2.minKr()).isEqualTo(289_900);
        assertThat(helaMarknaden.minKr()).isEqualTo(229_900);   // steget vi INTE tar
    }

    @Test
    void trappanGarFranSnavastTillHelaMarknaden() {
        // Ordningen är hela poängen: släpps gränsen i ett hopp sätts golvet av marknadens
        // mest slitna bil igen. Sista steget är null = ingen gräns, kvar som sista utväg.
        assertThat(BlocketPriceService.MILEAGE_STEPS_MIL)
                .containsExactly(BlocketPriceService.MAX_MILEAGE_MIL,
                        BlocketPriceService.RELAXED_MILEAGE_MIL, null);
    }

    /** Bygger en träfflista med körsträcka: (årsmodell, pris, mil). Negativ mil = fältet saknas. */
    private JsonNode docsMedMil(int[]... arsmodellPrisMil) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arsmodellPrisMil.length; i++) {
            if (i > 0) sb.append(',');
            int[] rad = arsmodellPrisMil[i];
            sb.append(String.format("{\"year\":%d,\"price\":{\"amount\":%d,\"price_unit\":\"kr\"}", rad[0], rad[1]));
            if (rad[2] >= 0) sb.append(String.format(",\"mileage\":%d,\"mileage_unit\":\"SCANDINAVIAN_MILE\"", rad[2]));
            sb.append('}');
        }
        try {
            return mapper.readTree(sb.append(']').toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    void avbetalningSatterInteLeasinggolvet() {
        // Live 2026-08-12: annons 18117231 (HedBil i Jönköping) låg i Blockets Leasing-hink på
        // 2 723 kr/mån, men annonstexten säger "Köp på avbetalning 2723kr / månad eller leasing
        // för företag 2358kr / månad". Samma form på ID.4: 3 191 kr på en 2021:a med 10 570 mil,
        // medan privatleasingen börjar på 3 395 kr. Momsuppdelningen är enda skiljelinjen —
        // sales_form är 5 på allihop.
        var blandat = leasingDocs(new Integer[]{2021, 2_723, 2_178}, new Integer[]{2021, 3_191, 2_552},
                new Integer[]{2027, 3_395, null}, new Integer[]{2027, 3_595, null});

        var range = service.leasingRangeFrom(blandat, null);

        assertThat(range.minKr()).isEqualTo(3_395);   // inte 2 723
        assertThat(range.count()).isEqualTo(2);
    }

    @Test
    void privatleasingUtanMomsuppdelningRaknasFortfarande() {
        // Gränsen åt andra hållet: regeln får inte tömma leasingspannet. Märkeshandlarnas
        // privatleasing (2027, noll mil) saknar amount_ex_vat och ska passera.
        var bara = leasingDocs(new Integer[]{2027, 3_995, null}, new Integer[]{2027, 5_295, null});

        assertThat(service.leasingRangeFrom(bara, null).count()).isEqualTo(2);
    }

    /** Leasingannonser: (årsmodell, kr/mån, pris ex moms). Null ex moms = ingen momsuppdelning. */
    private JsonNode leasingDocs(Integer[]... arsmodellPrisExMoms) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arsmodellPrisExMoms.length; i++) {
            if (i > 0) sb.append(',');
            Integer[] rad = arsmodellPrisExMoms[i];
            sb.append(String.format("{\"year\":%d,\"price\":{\"amount\":%d,\"price_unit\":\"kr\"",
                    rad[0], rad[1]));
            if (rad[2] != null) sb.append(String.format(",\"amount_ex_vat\":%d", rad[2]));
            sb.append("}}");
        }
        try {
            return mapper.readTree(sb.append(']').toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
