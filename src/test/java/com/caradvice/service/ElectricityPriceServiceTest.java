package com.caradvice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tester för promptradens format — själva HTTP-hämtningen mockas inte (ren stränglogik). */
class ElectricityPriceServiceTest {

    @Test
    void promptradInnehallerHemmaOchSnabbladdning() {
        String s = ElectricityPriceService.buildContext(4.83);
        assertThat(s)
                .contains("AKTUELLA ELPRISER")
                .contains("hemmaladdning ca 1,50–3,50 kr/kWh")
                .contains("2,50 kr/kWh inkl. elnät, energiskatt och moms")
                .contains("snabbladdning ca 4,83 kr/kWh")
                .contains("ALDRIG på rena spotpriser");
    }

    @Test
    void snabbladdningUtelamnasOmPrisetSaknas() {
        String s = ElectricityPriceService.buildContext(0);
        assertThat(s)
                .contains("hemmaladdning ca 1,50–3,50 kr/kWh")
                .doesNotContain("snabbladdning");
    }
}
