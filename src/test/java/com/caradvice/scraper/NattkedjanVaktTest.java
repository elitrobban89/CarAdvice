package com.caradvice.scraper;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nattkedjevakten tar igen en missad natt (2026-09-27). Proven låser när den kör och — lika
 * viktigt — när den INTE gör det: i fönstret runt den ordinarie körningen, mot en ny databas,
 * och när kedjan redan pågår.
 *
 * <p>Tiderna i web_scrape_status är svensk tid; klockan i proven är UTC (sommartid = UTC+2).
 *
 * @author Robert Andersson Kopler
 */
class NattkedjanVaktTest {

    private final JobStatusService status = mock(JobStatusService.class);
    private final NattkedjanScheduler kedjan = mock(NattkedjanScheduler.class);
    private final NattkedjanVakt vakt = new NattkedjanVakt(status, kedjan, true);

    private void klockan(String utc) {
        vakt.klocka = Clock.fixed(Instant.parse(utc), ZoneOffset.UTC);
    }

    private void senast(String status, String startedAt, String finishedAt) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", status);
        m.put("startedAt", startedAt);
        m.put("finishedAt", finishedAt);
        when(this.status.lastRun("web-insights")).thenReturn(m);
    }

    @Test
    void enMissadNattTasIgen() {
        // skrapningen blev klar 09-25 01:37 svensk tid, nu är det 09-26 12:00 UTC = 36 h senare
        senast("OK", "2026-09-25 01:20:00", "2026-09-25 01:37:25");
        klockan("2026-09-26T12:00:00Z");
        assertThat(vakt.skalAttTaIgen()).contains("36 timmar");
    }

    @Test
    void enVanligDagGorIngenting() {
        senast("OK", "2026-09-27 01:20:00", "2026-09-27 01:37:25");
        klockan("2026-09-27T12:00:00Z");
        assertThat(vakt.skalAttTaIgen()).isNull();
    }

    @Test
    void enOmstartMittIKedjanTasIgen() {
        senast("RUNNING", "2026-09-27 01:20:00", null);
        klockan("2026-09-27T06:00:00Z"); // 08:00 svensk tid, nästan sju timmar senare
        assertThat(vakt.skalAttTaIgen()).contains("RUNNING");
    }

    @Test
    void enPagaendeSkrapningAvbrytsInte() {
        senast("RUNNING", "2026-09-27 12:30:00", null);
        klockan("2026-09-27T11:00:00Z"); // 13:00 svensk tid, en halvtimme in
        assertThat(vakt.skalAttTaIgen()).isNull();
    }

    @Test
    void aldrigIFonstretRuntDenOrdinarieKorningen() {
        senast("OK", "2026-09-20 01:20:00", "2026-09-20 01:37:25");
        klockan("2026-09-27T23:30:00Z");
        assertThat(vakt.skalAttTaIgen()).isNull();
        klockan("2026-09-28T01:30:00Z");
        assertThat(vakt.skalAttTaIgen()).isNull();
    }

    @Test
    void enNyDatabasArIngenMissadNatt() {
        when(status.lastRun("web-insights")).thenReturn(Map.of("status", "NEVER_RUN"));
        klockan("2026-09-27T12:00:00Z");
        assertThat(vakt.skalAttTaIgen()).isNull();
    }

    @Test
    void inteMedanKedjanRedanKor() {
        senast("OK", "2026-09-20 01:20:00", "2026-09-20 01:37:25");
        when(kedjan.pagar()).thenReturn(true);
        klockan("2026-09-27T12:00:00Z");
        assertThat(vakt.skalAttTaIgen()).isNull();
    }

    @Test
    void kedjanStartarAldrigTvaGangerSamtidigt() throws Exception {
        // den ordinarie 23:00-körningen och en igenkörning får inte krocka
        EvSpecSyncScheduler ev = mock(EvSpecSyncScheduler.class);
        NattkedjanScheduler riktig = new NattkedjanScheduler(ev, mock(CargoSpecSyncScheduler.class),
                mock(WebInsightSyncScheduler.class), mock(MobilityStatsSyncScheduler.class));
        java.util.concurrent.CountDownLatch inne = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch slapp = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> { inne.countDown(); slapp.await(); return null; }).when(ev).dailySync();

        Thread forsta = new Thread(riktig::kor);
        forsta.start();
        inne.await();
        assertThat(riktig.pagar()).isTrue();
        riktig.kor(); // andra starten medan den första hänger i ev-ledet
        slapp.countDown();
        forsta.join();

        org.mockito.Mockito.verify(ev, org.mockito.Mockito.times(1)).dailySync();
        assertThat(riktig.pagar()).isFalse();
    }
}
