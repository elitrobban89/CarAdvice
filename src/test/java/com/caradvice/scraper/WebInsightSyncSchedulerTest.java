package com.caradvice.scraper;

import com.caradvice.service.UpcomingAdCheckService.Rapport;
import com.caradvice.service.UpcomingAutoReleaseService;
import com.caradvice.service.UpcomingAutoReleaseService.Utfall;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nattsynken ska släppa säljbara bilar ur kommande-kön direkt efter skrapningen.
 *
 * <p>Autosläppet fanns sedan 2026-09-11 men hade ingen anropare som faktiskt körde: molnrutinen
 * får inte göra POST mot admin-API:t. VW ID. Polo (1626) låg därför kvar med 42 annonser tills
 * den släpptes för hand 2026-09-24. Proven låser att släppet körs, i rätt ordning, och att det
 * varken fälls av en trasig skrapning eller fäller synken själv.
 *
 * @author Robert Andersson Kopler
 */
class WebInsightSyncSchedulerTest {

    private final WebInsightScraperService scraper = mock(WebInsightScraperService.class);
    private final UpcomingAutoReleaseService autoRelease = mock(UpcomingAutoReleaseService.class);
    private final WebInsightSyncScheduler scheduler = new WebInsightSyncScheduler(scraper, autoRelease);

    private static Utfall utfall() {
        return new Utfall(1, 1, false, false, List.of(), new Rapport(1, 1, 0, Map.of(), List.of()));
    }

    @Test
    void slapperUrKonEfterSkrapningen() {
        when(scraper.syncAll()).thenReturn(2);
        when(autoRelease.kor(false)).thenReturn(utfall());

        scheduler.dailySync();

        // Efter, inte före: nattens nya kommande-rader ska också hinna prövas
        InOrder ordning = inOrder(scraper, autoRelease);
        ordning.verify(scraper).syncAll();
        ordning.verify(autoRelease).kor(false);
    }

    @Test
    void slapperAvenNarSkrapningenFaller() {
        // Kön bär rader från tidigare nätter - en död skrapning gör dem inte mindre säljbara
        when(scraper.syncAll()).thenThrow(new RuntimeException("Groq nere"));
        when(autoRelease.kor(false)).thenReturn(utfall());

        scheduler.dailySync();

        verify(autoRelease).kor(false);
    }

    @Test
    void ettTrasigtAnnonsuppslagFallerInteSynken() {
        when(scraper.syncAll()).thenReturn(0);
        when(autoRelease.kor(false)).thenThrow(new RuntimeException("Blocket svarar inte"));

        assertThatCode(scheduler::dailySync).doesNotThrowAnyException();
    }
}
