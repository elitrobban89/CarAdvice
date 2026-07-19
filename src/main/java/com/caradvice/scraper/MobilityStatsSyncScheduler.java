package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MobilityStatsSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MobilityStatsSyncScheduler.class);
    private final MobilityStatsSyncService service;

    public MobilityStatsSyncScheduler(MobilityStatsSyncService service) {
        this.service = service;
    }

    // Den 4:e varje månad 05:00 Stockholm — månadsrapporten publiceras den 1:a–3:e
    @Scheduled(cron = "0 0 5 4 * *", zone = "Europe/Stockholm")
    public void monthlySync() {
        log.info("Monthly Mobility Sweden stats sync triggered");
        Object status = service.syncNow().get("status");
        log.info("Monthly Mobility Sweden stats sync finished — status {}", status);
    }
}
