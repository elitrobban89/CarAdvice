package com.caradvice.scraper;

import com.caradvice.data.DataLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * När nattsynken får skriva över ett sparat pris.
 *
 * @author Robert Andersson Kopler
 */
class EvDatabaseScraperServicePrisTest {

    @Test
    void tomtPrisFyllsI() {
        assertThat(EvDatabaseScraperService.prisBorUppdateras(null, 425_000)).isTrue();
        assertThat(EvDatabaseScraperService.prisBorUppdateras(0, 425_000)).isTrue();
    }

    @Test
    void enPrissankningSkrivsOver() {
        // Förut behöll en bil sitt lanseringspris för alltid.
        assertThat(EvDatabaseScraperService.prisBorUppdateras(499_000, 449_000)).isTrue();
        assertThat(EvDatabaseScraperService.prisBorUppdateras(449_000, 499_000)).isTrue();
    }

    @Test
    void avrundningsbrusSkrivsInte() {
        // Euro räknat till kronor och avrundat till tusental — några tusen hit eller dit är brus.
        assertThat(EvDatabaseScraperService.prisBorUppdateras(425_000, 428_000)).isFalse();
        assertThat(EvDatabaseScraperService.prisBorUppdateras(425_000, 425_000)).isFalse();
    }

    @Test
    void ettSaknatSkrapatPrisRaderAldrigEttSparat() {
        assertThat(EvDatabaseScraperService.prisBorUppdateras(425_000, 0)).isFalse();
    }

    @Test
    void svenskaListpriserAgsAvDataLoader() {
        // Natten skrev EUR x 11,5 över dem och bara nästa uppstart rättade tillbaka.
        assertThat(DataLoader.agerPris("Kia EV3 Long Range")).isTrue();
        assertThat(DataLoader.agerPris("Volvo EX60 P6")).isTrue();
        assertThat(DataLoader.agerPris("Volvo EX90 Twin Motor")).isTrue();
        assertThat(DataLoader.agerPris("Kia EV6 GT 77.4 kWh")).isTrue();
        assertThat(DataLoader.agerPris("MG4 Urban Standard Range")).isFalse();
    }

    @Test
    void utgangnaGenerationerRorsInteAvSynken() {
        assertThat(DataLoader.agerBatteri("Hyundai Kona Electric 64 kWh")).isTrue();
        assertThat(DataLoader.agerBatteri("MG4 Standard Range")).isTrue();
        assertThat(DataLoader.agerBatteri("Tesla Model S")).isTrue();
        assertThat(DataLoader.agerBatteri("Volvo EX60 P6")).isFalse();
    }
}
