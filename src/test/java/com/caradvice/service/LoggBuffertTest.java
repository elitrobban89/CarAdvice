package com.caradvice.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loggbufferten som ersätter "be om Render-loggen" i nattrutinen (2026-09-27).
 *
 * @author Robert Andersson Kopler
 */
class LoggBuffertTest {

    private LoggBuffert buffert;
    private final LoggerContext ctx = new LoggerContext();

    @BeforeEach
    void setUp() {
        buffert = new LoggBuffert();
        buffert.setContext(ctx);
        buffert.start();
    }

    private void logga(Level niva, String logger, String text) {
        buffert.doAppend(new LoggingEvent("x", ctx.getLogger(logger), niva, text, null, null));
    }

    @Test
    void baraVarningarOchFelSparas() {
        logga(Level.INFO, "com.caradvice.X", "vanlig rad");
        logga(Level.DEBUG, "com.caradvice.X", "brus");
        logga(Level.WARN, "com.caradvice.scraper.WebInsightScraperService", "hoppar over batchen");
        assertThat(buffert.antal()).isEqualTo(1);
        assertThat(buffert.senaste(null, null, 10).get(0))
                .containsEntry("kalla", "WebInsightScraperService")
                .containsEntry("meddelande", "hoppar over batchen");
    }

    @Test
    void nyastForstOchSokningPaText() {
        logga(Level.WARN, "a.B", "auto-data bagage: 3 fyllda");
        logga(Level.ERROR, "a.C", "Autosläpp AVBRUTET");
        logga(Level.WARN, "a.B", "auto-data bagage: 5 fyllda");

        assertThat(buffert.senaste(null, "BAGAGE", 10)).extracting(r -> r.get("meddelande"))
                .containsExactly("auto-data bagage: 5 fyllda", "auto-data bagage: 3 fyllda");
        assertThat(buffert.senaste("ERROR", null, 10)).hasSize(1);
    }

    @Test
    void bufferternaArEnRingMedFastStorlek() {
        for (int i = 0; i < LoggBuffert.MAX + 50; i++) logga(Level.WARN, "a.B", "rad " + i);
        assertThat(buffert.antal()).isEqualTo(LoggBuffert.MAX);
        // den äldsta har fallit ut, den nyaste står först
        assertThat(buffert.senaste(null, null, 1).get(0)).containsEntry("meddelande", "rad " + (LoggBuffert.MAX + 49));
    }
}
