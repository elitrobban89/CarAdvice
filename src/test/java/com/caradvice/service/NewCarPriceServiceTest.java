package com.caradvice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nyprisuppslaget per bil, mot H2 (samma SQL körs mot Postgres i prod).
 * Tabellnamnen bär generation som årsspann, vilket matchningen måste klara.
 */
class NewCarPriceServiceTest {

    private NewCarPriceService service() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:ncp_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        NewCarPriceService s = new NewCarPriceService(new JdbcTemplate(ds));
        s.ensureTableAndSeed();
        return s;
    }

    @Test
    void nyprisHittasForEnVanligTitel() {
        assertThat(service().priceForTitle("Volkswagen Golf (2022)")).isEqualTo(320_000);
    }

    @Test
    void arsmodellenValjerGeneration() {
        // "Volkswagen Polo 2018-2021" 185 000 mot "Volkswagen Polo 2022+" 225 000
        NewCarPriceService s = service();
        assertThat(s.priceForTitle("Volkswagen Polo (2020)")).isEqualTo(185_000);
        assertThat(s.priceForTitle("Volkswagen Polo (2023)")).isEqualTo(225_000);
    }

    @Test
    void utanArtalValjsNyasteGenerationen() {
        assertThat(service().priceForTitle("Volkswagen Polo")).isEqualTo(225_000);
    }

    @Test
    void langreModellnamnVinnerOverKortare() {
        // "Toyota RAV4 2019+" 420 000 mot "Toyota RAV4 PHEV 2019+" 495 000
        assertThat(service().priceForTitle("Toyota RAV4 PHEV (2022)")).isEqualTo(495_000);
        assertThat(service().priceForTitle("Toyota RAV4 (2022)")).isEqualTo(420_000);
    }

    @Test
    void okandModellGerNull() {
        NewCarPriceService s = service();
        assertThat(s.priceForTitle("Ferrari 812 Superfast (2022)")).isNull();
        assertThat(s.priceForTitle("Volkswagen")).isNull();   // bara märket räcker inte
        assertThat(s.priceForTitle(null)).isNull();
        assertThat(s.priceForTitle("  ")).isNull();
    }

    @Test
    void arsmodellUtanforGenerationensSpannMatcharInte() {
        // Ford Fiesta finns bara som 2019-2023 i tabellen
        assertThat(service().priceForTitle("Ford Fiesta (2015)")).isNull();
    }

    @Test
    void modellnamnetMasteMatchaHelaOrd() {
        // "Kia Ceed 2019+" ska inte matcha en titel som bara delar teckenföljd
        assertThat(service().priceForTitle("Kia Ceedx (2021)")).isNull();
    }
}
