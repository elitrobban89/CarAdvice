package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvSpecSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvSpecSyncScheduler.class);
    private final EvDatabaseScraperService scraper;

    public EvSpecSyncScheduler(EvDatabaseScraperService scraper) {
        this.scraper = scraper;
    }

    // Runs every day at 03:00 (server time = UTC on Render)
    @Scheduled(cron = "0 0 3 * * *")
    public void dailySync() {
        log.info("Daily EV spec sync triggered");
        try {
            int updated = scraper.syncFromEvDatabase();
            log.info("Daily sync finished — {} records updated", updated);
        } catch (Exception e) {
            log.error("Daily sync failed: {}", e.getMessage(), e);
        }
    }
}
