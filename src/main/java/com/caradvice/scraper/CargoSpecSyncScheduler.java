package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CargoSpecSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CargoSpecSyncScheduler.class);
    private final CargoSpecSyncService service;

    public CargoSpecSyncScheduler(CargoSpecSyncService service) {
        this.service = service;
    }

    // Runs every day at 03:00 Stockholm time — one hour after the EV sync
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Stockholm")
    public void dailySync() {
        log.info("Daily CargoSpec sync triggered");
        try {
            int added = service.syncCarNames();
            log.info("Daily CargoSpec sync finished — {} new cars added", added);
        } catch (Exception e) {
            log.error("Daily CargoSpec sync failed: {}", e.getMessage(), e);
        }
    }
}
