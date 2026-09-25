package com.caradvice.controller;

import com.caradvice.scraper.NattkedjanStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Granskningens kvitto (2026-09-25). Reservkörningen avstår när kvittot finns — ett kvitto som
 * vem som helst kunde sätta hade alltså kunnat tysta nattens enda granskning.
 *
 * @author Robert Andersson Kopler
 */
class NattkedjanControllerTest {

    private final NattkedjanStatus status = new NattkedjanStatus();
    private final NattkedjanController controller = new NattkedjanController(status, "hemlig");

    @Test
    void kvittoUtanNyckelAvvisas() {
        assertThat(controller.granskad(null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.granskad("fel").getStatusCode().value()).isEqualTo(403);
        assertThat(status.somKarta().get("granskad")).isNull();
    }

    @Test
    void kvittoMedNyckelSyns() {
        assertThat(controller.granskad("hemlig").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.nattkedjan().get("granskad")).isNotNull();
    }

    @Test
    void allaFaltFinnsAvenForeForstaNatten() {
        // Efter en omstart ska reserven se null, inte ett saknat fält — samma svar som "ingen kvittens"
        assertThat(controller.nattkedjan())
                .containsKeys("kedjanKlar", "signalSkickad", "signalUtfall", "granskad");
    }
}
