package com.caradvice.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
}
