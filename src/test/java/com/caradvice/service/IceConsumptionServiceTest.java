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
    void jamforelsesammanfattningInnehallerDrivmedel() {
        String s = service.consumptionSummaryForTitle("Audi A4 (2020)");
        assertThat(s).startsWith("förbrukning ca").contains("(bensin)").contains("(diesel)").contains("l/mil");
    }

    @Test
    void hkParsasUrVariantnamn() {
        assertThat(IceConsumptionService.parseHp("Golf 1.5 TSI 150 hk")).isEqualTo(150);
        assertThat(IceConsumptionService.parseHp("Golf 1.5 TSI")).isNull();
    }
}
