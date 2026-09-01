package com.caradvice.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsern körs mot sparade sidor från bilweb.se, hämtade 2026-09-01 — dagen då den gamla
 * källan {@code /sok/bilar} visade sig svara 404 och nattjobbet tyst slutade hämta märken.
 * Fixturerna är sidornas egen markup (länkblocken, utan annonslistorna).
 */
class CargoSpecSyncServiceTest {

    private Document fixtur(String namn) {
        try (InputStream in = getClass().getResourceAsStream("/bilweb/" + namn)) {
            if (in == null) throw new IllegalStateException("saknar fixtur " + namn);
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void alla_markenGerHelaMarkeslistan() {
        var makes = CargoSpecSyncService.parseMakes(fixtur("alla-marken.html"));

        assertThat(makes).hasSize(170);
        // Bilweb skriver "Mercedes", inte "Mercedes-Benz" — samma stavning som bilkorten redan
        // använder sedan namnrättelsen 2026-08-27, så namnen möts utan omskrivning.
        assertThat(makes).containsEntry("Audi", "audi")
                .containsEntry("Alfa Romeo", "alfa-romeo")
                .containsEntry("Mercedes", "mercedes");
    }

    @Test
    void markesnamnetTarInteMedAnnonsantalet() {
        // Namnet ligger i första spanen och antalet i nästa, så link.text() ger "Alfa Romeo 51".
        // Ett sådant namn hade blivit bilar som "Alfa Romeo 51 Giulia" i tabellen.
        var makes = CargoSpecSyncService.parseMakes(fixtur("alla-marken.html"));

        assertThat(makes.keySet()).noneMatch(namn -> namn.matches(".*\\d+$"));
    }

    @Test
    void markessidanGerModellerna() {
        var modeller = CargoSpecSyncService.parseModels(fixtur("sok-audi.html"), "audi");

        // 48, inte 47: modellchipen ger 47 och omdömeslistans /sok/audi/<modell>#omdomen ger
        // dessutom "TTC", Bilwebs eget namn på en modell som saknar chip. Samma utfall som den
        // gamla parsern gav — selektorn är oförändrad, bara adressen är ny.
        assertThat(modeller).hasSize(48);
        assertThat(modeller).contains("A3", "A4 Allroad", "Q4 e-tron", "RS e-tron GT", "SQ8 e-tron");
    }

    @Test
    void visaAnnonserLankenArIngenModell() {
        // Varje populär modell har TVÅ länkar med samma href: chipet med modellnamnet och
        // toplistans "Visa 143 annonser →". Utan filtret blev den andra en bil i tabellen.
        var modeller = CargoSpecSyncService.parseModels(fixtur("sok-audi.html"), "audi");

        assertThat(modeller).noneMatch(m -> m.toLowerCase().contains("annons"));
    }
}
