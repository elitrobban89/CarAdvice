package com.caradvice.scraper;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nattens jobb körs som EN kedja från 23:00 UTC i stället för på fyra klockslag (2026-09-25).
 *
 * <p>Proven låser ordningen (skrapningen ska se nattens specar), att ett trasigt led inte tar
 * resten med sig, att mobility-ledet följer svenskt datum, och att inget jobb har kvar ett eget
 * klockslag — då hade det körts två gånger per natt.
 *
 * @author Robert Andersson Kopler
 */
class NattkedjanSchedulerTest {

    private final EvSpecSyncScheduler ev = mock(EvSpecSyncScheduler.class);
    private final CargoSpecSyncScheduler cargo = mock(CargoSpecSyncScheduler.class);
    private final WebInsightSyncScheduler web = mock(WebInsightSyncScheduler.class);
    private final MobilityStatsSyncScheduler mobility = mock(MobilityStatsSyncScheduler.class);
    private final NattkedjanStatus status = new NattkedjanStatus();
    private final GitHubSignal signal = mock(GitHubSignal.class);
    private final NattkedjanScheduler kedjan = new NattkedjanScheduler(ev, cargo, web, mobility, status, signal);

    private void klockan(String utc) {
        kedjan.klocka = Clock.fixed(Instant.parse(utc), ZoneOffset.UTC);
    }

    @Test
    void ledenKorsIRattOrdning() {
        klockan("2026-09-25T23:00:00Z");
        kedjan.kor();
        InOrder ordning = inOrder(ev, cargo, web);
        ordning.verify(ev).dailySync();
        ordning.verify(cargo).dailySync();
        ordning.verify(web).dailySync();
    }

    @Test
    void signalenGarSistOchUtfalletSparas() {
        klockan("2026-09-25T23:00:00Z");
        when(signal.skicka()).thenReturn("skickad");
        kedjan.kor();
        InOrder ordning = inOrder(web, signal);
        ordning.verify(web).dailySync();
        ordning.verify(signal).skicka();
        assertThat(status.somKarta()).containsEntry("signalUtfall", "skickad")
                .containsEntry("kedjanKlar", "2026-09-25T23:00:00Z");
    }

    @Test
    void ettTrasigtLedTarInteResten() {
        klockan("2026-09-25T23:00:00Z");
        doThrow(new IllegalStateException("ev-database nere")).when(ev).dailySync();
        doThrow(new IllegalStateException("Bilweb nere")).when(cargo).dailySync();
        kedjan.kor();
        verify(web).dailySync();
    }

    @Test
    void mobilityGarDenFjardeISvenskTid() {
        // 23:00 UTC den 3:e är redan den 4:e i Stockholm (01:00 sommartid, 00:00 vintertid)
        klockan("2026-10-03T23:00:00Z");
        kedjan.kor();
        verify(mobility).monthlySync();
    }

    @Test
    void mobilityGarInteAndraDagar() {
        klockan("2026-10-04T23:00:00Z"); // den 5:e i Stockholm
        kedjan.kor();
        verify(mobility, never()).monthlySync();
    }

    @Test
    void kedjanStartarEfterSvenskMidnattBadaHalvaren() throws Exception {
        Scheduled s = NattkedjanScheduler.class.getMethod("kor").getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("0 0 23 * * *");
        assertThat(s.zone()).isEqualTo("UTC");
    }

    @Test
    void ingetJobbHarKvarEttEgetKlockslag() {
        for (Class<?> c : List.of(EvSpecSyncScheduler.class, CargoSpecSyncScheduler.class,
                WebInsightSyncScheduler.class, MobilityStatsSyncScheduler.class)) {
            List<Method> schemalagda = Arrays.stream(c.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Scheduled.class)).toList();
            assertThat(schemalagda).as(c.getSimpleName() + " körs redan av kedjan").isEmpty();
        }
    }
}
