package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebInsightSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebInsightSyncScheduler.class);
    private final WebInsightScraperService scraper;

    public WebInsightSyncScheduler(WebInsightScraperService scraper) {
        this.scraper = scraper;
    }

    // Körs varje dag 04:00 Stockholm — en timme efter CargoSpec-synken
    @Scheduled(cron = "0 0 4 * * *", zone = "Europe/Stockholm")
    public void dailySync() {
        log.info("Daily web insight sync triggered");
        try {
            int saved = scraper.syncAll();
            log.info("Daily web insight sync finished — {} new insights", saved);
        } catch (Exception e) {
            log.error("Daily web insight sync failed: {}", e.getMessage(), e);
        }
    }
}
