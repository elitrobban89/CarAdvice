package com.caradvice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tester för promptradens format — själva HTTP-hämtningen mockas inte (ren stränglogik). */
class FuelPriceServiceTest {

    @Test
    void promptradInnehallerBadaPriserna() {
        String s = FuelPriceService.buildContext(16.39, 18.29);
        assertThat(s)
                .contains("AKTUELLA BRÄNSLEPRISER")
                .contains("bensin 95 ca 16,39 kr/l")
                .contains("diesel ca 18,29 kr/l")
                .contains("anta aldrig andra bränslepriser");
    }

    @Test
    void dieselUtelamnasOmSaknas() {
        String s = FuelPriceService.buildContext(16.39, 0);
        assertThat(s).contains("bensin 95").doesNotContain("diesel");
    }
}
