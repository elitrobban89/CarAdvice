package com.caradvice.scraper;

import com.caradvice.service.UpcomingAutoReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** @author Robert Andersson Kopler */
@Component
public class WebInsightSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebInsightSyncScheduler.class);
    private final WebInsightScraperService scraper;
    private final UpcomingAutoReleaseService autoRelease;

    public WebInsightSyncScheduler(WebInsightScraperService scraper, UpcomingAutoReleaseService autoRelease) {
        this.scraper = scraper;
        this.autoRelease = autoRelease;
    }

    // Körs varje dag 02:00 Stockholm — en timme efter CargoSpec-synken, och klar före molnrutinerna 02:30/03:30
    @Scheduled(cron = "0 0 2 * * *", zone = "Europe/Stockholm")
    public void dailySync() {
        log.info("Daily web insight sync triggered");
        try {
            int saved = scraper.syncAll();
            log.info("Daily web insight sync finished — {} new insights", saved);
        } catch (Exception e) {
            log.error("Daily web insight sync failed: {}", e.getMessage(), e);
        }
        slappSaljbaraUrKon();
    }

    /**
     * Autosläppet direkt efter skrapningen, så en bil som redan säljs aldrig blir liggande i
     * kommande-kön till morgonen. Tjänsten byggdes 2026-09-11 för att "nattrutinen" skulle kalla
     * på den, men molnrutinen får inte göra POST mot admin-API:t — så den kördes aldrig, och
     * VW ID. Polo (1626, 42 annonser, inga nyhetsord) låg kvar tills en människa släppte den
     * 2026-09-24. Körs även när skrapningen fallerat: kön har rader från tidigare nätter.
     * Egen try — ett trasigt annonsuppslag får inte se ut som en trasig skrapning.
     */
    void slappSaljbaraUrKon() {
        try {
            var utfall = autoRelease.kor(false);
            if (utfall.taketSlogTill())
                log.warn("Autosläpp efter nattsynken: taket slog till — ingenting släpptes");
            else
                log.info("Autosläpp efter nattsynken: {} rad(er) släppta på {} bil(ar)", utfall.slappta(), utfall.bilar());
        } catch (Exception e) {
            log.error("Autosläpp efter nattsynken misslyckades: {}", e.getMessage(), e);
        }
    }
}
