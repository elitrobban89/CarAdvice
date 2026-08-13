package com.caradvice.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tester mot riktig H2 in-memory-databas — verifierar att seeden från
 * ice-consumption.csv (Bilresa-datat) laddas och att titelmatchningen
 * hittar rätt variant. Seeden görs en gång för hela klassen (957 rader).
 */
class IceConsumptionServiceTest {

    private static IceConsumptionService service;

    @BeforeAll
    static void seed() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:ice_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        service = new IceConsumptionService(new JdbcTemplate(ds));
        service.ensureTableAndSeed();
    }

    @Test
    void seedenLaddarHundratalsVarianter() {
        assertThat(service.findAll().size()).isGreaterThan(900);
    }

    @Test
    void apiListanHarRattFaltOchEnheter() {
        List<Map<String, Object>> list = service.listForApi();
        assertThat(list).isNotEmpty();
        Map<String, Object> row = list.get(0);
        assertThat(row).containsKeys("carName", "fuel", "literPerMil");
        list.forEach(r -> assertThat(((Number) r.get("literPerMil")).doubleValue())
                .isBetween(0.1, 3.0));
    }

    @Test
    void titelMedArtalMatcharModell() {
        IceConsumptionService.Variant v = service.consumptionForTitle("Volkswagen Golf (2019)", null, "bensin");
        assertThat(v).isNotNull();
        assertThat(v.brand()).isEqualTo("Volkswagen");
        assertThat(v.variant()).startsWith("Golf");
        assertThat(v.fuel()).isEqualTo("bensin");
    }

    @Test
    void hastkrafterValjerNarmasteVariant() {
        // Audi A4 finns med 150/204/265 hk bensin — 210 hk ska ge 204-varianten
        IceConsumptionService.Variant v = service.consumptionForTitle("Audi A4 (2020)", 210, "bensin");
        assertThat(v).isNotNull();
        assertThat(v.variant()).contains("204 hk");
    }

    @Test
    void dieselPreferensFiltrerar() {
        IceConsumptionService.Variant v = service.consumptionForTitle("Audi A4 (2020)", null, "diesel");
        assertThat(v).isNotNull();
        assertThat(v.fuel()).isEqualTo("diesel");
    }

    @Test
    void okandBilGerNull() {
        assertThat(service.consumptionForTitle("Koenigsegg Jesko (2023)", null, null)).isNull();
        assertThat(service.consumptionSummaryForTitle("Koenigsegg Jesko (2023)")).isNull();
    }

    @Test
    void jamforelsesammanfattningArILiterPer100km() {
        // Prompten ska tala AI:ns enhet (l/100km) — inte tabellens l/mil
        String s = service.consumptionSummaryForTitle("Audi A4 (2020)");
        assertThat(s).startsWith("förbrukning ca").contains("(bensin)").contains("(diesel)").contains("l/100km");
    }

    @Test
    void hkParsasUrVariantnamn() {
        assertThat(IceConsumptionService.parseHp("Golf 1.5 TSI 150 hk")).isEqualTo(150);
        assertThat(IceConsumptionService.parseHp("Golf 1.5 TSI")).isNull();
    }

    // --- Modellord som upprepar märkesnamnet ("Mazda 3 2.0 ...") ska inte matcha andra modeller ---

    @Test
    void mazdaCx5MatcharInteMazda3sMotor() {
        // Skarpt fall: CX-5 fick Mazda 3:ans Skyactiv-X-motor innan modelWord() hoppade över
        // den upprepade märkesprefixen i variant-strängen ("Mazda 3 2.0 Skyactiv-X 186 hk")
        IceConsumptionService.Variant v = service.consumptionForTitle("Mazda CX-5 (2023)", null, "bensin");
        assertThat(v).isNotNull();
        assertThat(v.variant()).startsWith("CX-5");
        assertThat(v.variant()).doesNotContain("Skyactiv-X");
    }

    @Test
    void mazda3MatcharFortfarandeSigSjalv() {
        IceConsumptionService.Variant v = service.consumptionForTitle("Mazda 3 (2022)", null, "bensin");
        assertThat(v).isNotNull();
        assertThat(v.variant()).startsWith("Mazda 3");
    }

    @Test
    void dsFyraMatcharInteDsTre() {
        // Alla DS-rader har märket upprepat i variant-strängen ("DS 3 1.2 PureTech ...")
        IceConsumptionService.Variant v = service.consumptionForTitle("DS 4 (2021)", null, "bensin");
        assertThat(v).isNotNull();
        assertThat(v.variant()).startsWith("DS 4");
    }

    @Test
    void mazdaCx30MatcharInteMazda3sMotor() {
        // "3" (modellordet for Mazda 3-raderna) ar en substrang av "cx-30" — utan ordgrans-
        // matchning skulle en CX-30-titel kunna fa Mazda 3:ans motorvarianter
        IceConsumptionService.Variant v = service.consumptionForTitle("Mazda CX-30 (2022)", null, "bensin");
        assertThat(v).isNotNull();
        assertThat(v.variant()).startsWith("CX-30");
    }

    @Test
    void mazdaCx60MatcharInteMazda6sMotor() {
        // Samma fallgrop: "6" ar en substrang av "cx-60"
        IceConsumptionService.Variant v = service.consumptionForTitle("Mazda CX-60 (2023)", null, "diesel");
        assertThat(v).isNotNull();
        assertThat(v.variant()).startsWith("CX-60");
    }

    @Test
    void modellnamnListanHopparOverMarkesupprepning() {
        // allModelNames() normaliserar (gemener) modellordet, precis som innan denna fix
        assertThat(service.allModelNames()).contains("Mazda 3", "Mazda cx-5", "DS 4");
        assertThat(service.allModelNames()).doesNotContain("Mazda mazda", "DS ds");
    }

    // --- engineDescriptor (motorbeteckning utan modellnamn, för engineOptions-kortet) ---

    @Test
    void engineDescriptorStripparModellord() {
        var v = new IceConsumptionService.Variant("Mazda", "CX-5 2.0 Skyactiv-G 165 hk", "bensin", 0.8);
        assertThat(IceConsumptionService.engineDescriptor(v)).isEqualTo("2.0 Skyactiv-G 165 hk");
    }

    @Test
    void engineDescriptorStripparAvenUpprepatMarke() {
        var v = new IceConsumptionService.Variant("Mazda", "Mazda 3 2.0 Skyactiv-X 186 hk", "bensin", 0.68);
        assertThat(IceConsumptionService.engineDescriptor(v)).isEqualTo("2.0 Skyactiv-X 186 hk");
    }

    // --- generationsvakten: listan är EN generation, inte alla ---

    @Test
    void aldreArsmodellFarIngenMotorlistaAlls() {
        /*
         * Uppmätt 2026-08-13: tabellen bär EN generations motorer per modell. Samtliga tretton
         * Golf-rader är Golf VIII (2020-2024) — 1.0 eTSI Mild Hybrid, eHybrid PHEV, GTI
         * Clubsport — och Golf VII har noll rader hos oss. Ett kort för "Golf (2018)" fick
         * alltså 2020 års motorutbud. Det gällde 202 av 310 namnplåtar.
         *
         * Rätt svar är att AVSTÅ, inte att gissa: de äldre raderna finns inte, och att hämta
         * dem okurerat från auto-data hade gett 46 Golf VII-varianter varav flera (1.5 TGI,
         * 1.4 TGI — fordonsgas) aldrig sålts i Sverige. Anroparen faller tillbaka på AI:ns
         * egen motortext, precis som för en modell vi saknar helt.
         */
        // Nyckeln är samma form som allModelNames() bygger, alltså märket som det står plus
        // modellordet NORMALISERAT till gemener: "Volkswagen golf". Formen är inte snygg men
        // den måste vara identisk i uppslaget och i ifyllningen, annars matchar de aldrig.
        IceGenerationService gen = mock(IceGenerationService.class);
        when(gen.franArFor("Volkswagen golf")).thenReturn(2020);
        service.setIceGenerations(gen);
        try {
            // Årsmodellen skickas SEPARAT — enargsvarianten betyder "inget år att gå på" och
            // visar listan som förut. GroqService plockar året ur titeln med CarTitle.year.
            assertThat(service.engineOptionsForTitle("Volkswagen Golf (2018)", 2018)).isNull();
            assertThat(service.engineOptionsForTitle("Volkswagen Golf (2022)", 2022)).isNotNull();
            assertThat(service.engineOptionsForTitle("Volkswagen Golf (2018)")).isNotNull();
        } finally {
            service.setIceGenerations(null);
        }
    }

    @Test
    void utanArtalEllerUtanKantGenerationsarVisasListanSomForut() {
        // Tabellen fylls över flera nätter och ett kort får inte tappa sina motoralternativ
        // under tiden — okänd generation betyder "ingen åsikt", inte "dölj".
        IceGenerationService gen = mock(IceGenerationService.class);
        when(gen.franArFor(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        service.setIceGenerations(gen);
        try {
            assertThat(service.engineOptionsForTitle("Volvo XC60 (2012)", 2012)).isNotNull();
            assertThat(service.engineOptionsForTitle("Volvo XC60", null)).isNotNull();
        } finally {
            service.setIceGenerations(null);
        }
    }

    // --- motoralternativ som lista, som elbilskorten redan visar ---

    @Test
    void helaMotorutbudetVisas_inteBaraEnVariant() {
        // Volvo XC60 finns som B5, B6, D4, D5, T6 PHEV och T8 PHEV i tabellen, men kortet
        // visade bara den hästkraftsnärmaste enda varianten — resten låg osynligt i databasen.
        String motorer = service.engineOptionsForTitle("Volvo XC60 (2021)");

        assertThat(motorer).contains("B5", "B6", "D4", "D5", "T8");
        assertThat(motorer.split(" · ")).hasSizeGreaterThan(3);
    }

    @Test
    void motoralternativenSorterasSvagastForst() {
        String motorer = service.engineOptionsForTitle("Volvo XC60 (2021)");

        var hk = java.util.Arrays.stream(motorer.split(" · "))
                .map(IceConsumptionService::parseHp)
                .filter(java.util.Objects::nonNull).toList();

        assertThat(hk).isSorted();
    }

    @Test
    void modellordsskyddetGallerAvenMotorlistan() {
        // Samma fälla som förbrukningssiffran hade: "3" är delsträng av "cx-30". Motorn att
        // pröva på måste vara en som BARA Mazda 3 har — Skyactiv-X 186 hk duger inte, den
        // sitter i båda bilarna på riktigt. Turbo 2.5 265 hk och 1.8 Skyactiv-D finns bara i 3:an.
        String cx30 = service.engineOptionsForTitle("Mazda CX-30 (2022)");

        assertThat(cx30).doesNotContain("Turbo 2.5 265 hk");
        assertThat(cx30).doesNotContain("1.8 Skyactiv-D 116 hk");
        assertThat(cx30).contains("Skyactiv-X 186 hk");   // CX-30 har den faktiskt själv
    }

    @Test
    void okandModellGerNull() {
        // Null, inte tom sträng: anroparen ska kunna behålla AI:ns egen motortext i stället.
        assertThat(service.engineOptionsForTitle("Koenigsegg Jesko (2023)")).isNull();
        assertThat(service.engineOptionsForTitle(null)).isNull();
    }
}
