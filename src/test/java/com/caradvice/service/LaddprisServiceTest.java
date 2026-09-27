package com.caradvice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Laddpriserna som nattrutinen belägger för elbilsassistenten (2026-09-27).
 *
 * @author Robert Andersson Kopler
 */
class LaddprisServiceTest {

    private static LaddprisService med(String priser) {
        String fil = "{\"priser\":[" + priser + "]}";
        return new LaddprisService(() -> new ByteArrayInputStream(fil.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ettBelagtPrisPubliceras() {
        var ut = med("{\"natverk\":\"IONITY\",\"pris\":\"~6,49 kr/kWh\",\"kalla\":\"https://ionity.eu/sv/priser\",\"kontrollerad\":\"2026-09-28\"}").priser();
        assertThat(ut).hasSize(1);
        assertThat(ut.get(0)).containsEntry("natverk", "ionity").containsEntry("pris", "~6,49 kr/kWh");
    }

    @Test
    void ettPrisUtanKallaEllerDatumPubliceras_inte() {
        assertThat(med("{\"natverk\":\"ionity\",\"pris\":\"~6,49 kr/kWh\",\"kalla\":\"\",\"kontrollerad\":\"2026-09-28\"}").priser()).isEmpty();
        assertThat(med("{\"natverk\":\"ionity\",\"pris\":\"~6,49 kr/kWh\",\"kalla\":\"https://ionity.eu\",\"kontrollerad\":\"igår\"}").priser()).isEmpty();
    }

    @Test
    void ettOtolkbartPrisPubliceras_inte() {
        assertThat(med("{\"natverk\":\"tesla\",\"pris\":\"se appen\",\"kalla\":\"https://tesla.com\",\"kontrollerad\":\"2026-09-28\"}").priser()).isEmpty();
        assertThat(med("{\"natverk\":\"ikea\",\"pris\":\"Gratis (för kunder)\",\"kalla\":\"https://ikea.se\",\"kontrollerad\":\"2026-09-28\"}").priser()).hasSize(1);
    }

    @Test
    void verkligaFilenArGiltig() throws Exception {
        int iFilen;
        try (var in = new ClassPathResource(LaddprisService.RESURS).getInputStream()) {
            iFilen = new ObjectMapper().readTree(in).path("priser").size();
        }
        assertThat(new LaddprisService().priser()).hasSize(iFilen);
    }
}
