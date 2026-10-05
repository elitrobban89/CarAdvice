package com.caradvice.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Laddtipsen som nattrutinen skriver till elbilsassistentens karusell (2026-09-27). Texten är
 * AI-formulerad och visas publikt, så proven låser främst vad som INTE får nå fram.
 *
 * @author Robert Andersson Kopler
 */
class LaddtipsServiceTest {

    private final ExpertInsightService insikter = mock(ExpertInsightService.class);
    private final UpcomingInsightService kon = mock(UpcomingInsightService.class);

    @BeforeEach
    void setUp() {
        when(insikter.exists(anyLong())).thenReturn(true);
    }

    private LaddtipsService med(String... tips) {
        String fil = "{\"tips\":[" + String.join(",", tips) + "]}";
        LaddtipsService s = new LaddtipsService(insikter, kon,
                () -> new ByteArrayInputStream(fil.getBytes(StandardCharsets.UTF_8)));
        s.klocka = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneId.of("Europe/Stockholm"));
        return s;
    }

    private static String tips(String datum, String text, long... ids) {
        StringBuilder id = new StringBuilder();
        for (long i : ids) id.append(id.length() == 0 ? "" : ",").append(i);
        return "{\"datum\":\"" + datum + "\",\"ikon\":\"⚡\",\"text\":\"" + text
                + "\",\"kalla\":\"Teknikens Värld\",\"insikter\":[" + id + "]}";
    }

    private static final String BRA = "Laddkurvan säger mer än toppeffekten: **MG4 Urban** håller nära full effekt till 70 procent.";

    @Test
    void ettGiltigtTipsVisasMedKalla() {
        var ut = med(tips("2026-09-20", BRA, 1510)).aktuella();
        assertThat(ut).hasSize(1);
        assertThat(ut.get(0)).containsEntry("kalla", "Teknikens Värld").containsEntry("text", BRA);
    }

    @Test
    void htmlITextenNarAldrigFram() {
        // AI-text ur skrapad press visas publikt - en tagg är en injektion, inte formatering
        assertThat(med(tips("2026-09-20", BRA + " <img src=x onerror=alert(1)>", 1)).aktuella()).isEmpty();
    }

    @Test
    void ettTipsUtanKallaVisasInte() {
        String utanKalla = "{\"datum\":\"2026-09-20\",\"ikon\":\"⚡\",\"text\":\"" + BRA + "\",\"kalla\":\"\"}";
        assertThat(med(utanKalla).aktuella()).isEmpty();
    }

    @Test
    void gamlaTipsFallerBort() {
        assertThat(med(tips("2026-01-01", BRA, 1)).aktuella()).isEmpty();
        assertThat(med(tips("2026-06-01", BRA, 1)).aktuella()).hasSize(1);
    }

    @Test
    void ettTipsFallerNarDessInsiktUnderkants() {
        // vilar tipset på en rad rutinen sedan dolt eller parkerat är tipset inte längre belagt
        when(kon.doldaIds()).thenReturn(Set.of(7L));
        when(kon.hiddenIds()).thenReturn(Set.of(8L));
        when(insikter.exists(9L)).thenReturn(false);
        var s = med(tips("2026-09-20", BRA, 7), tips("2026-09-21", BRA, 8), tips("2026-09-22", BRA, 9),
                tips("2026-09-23", BRA, 10));
        assertThat(s.aktuella()).extracting(m -> m.get("datum")).containsExactly("2026-09-23");
    }

    @Test
    void nyastForstOchTak() {
        String[] alla = new String[LaddtipsService.MAX_VISADE + 3];
        for (int i = 0; i < alla.length; i++) alla[i] = tips(String.format("2026-09-%02d", i + 1), BRA, i + 1);
        var ut = med(alla).aktuella();
        assertThat(ut).hasSize(LaddtipsService.MAX_VISADE);
        assertThat(ut.get(0)).containsEntry("datum", String.format("2026-09-%02d", alla.length));
    }

    @Test
    void verkligaFilenArGiltig() throws Exception {
        // den incheckade filen får aldrig ha ett tips som tyst faller i drift - då fäller det bygget
        var s = new LaddtipsService(insikter, kon);
        int iFilen;
        try (var in = new ClassPathResource(LaddtipsService.RESURS).getInputStream()) {
            iFilen = new ObjectMapper().readTree(in).path("tips").size();
        }
        assertThat(s.giltiga()).hasSize(iFilen);
    }
}
