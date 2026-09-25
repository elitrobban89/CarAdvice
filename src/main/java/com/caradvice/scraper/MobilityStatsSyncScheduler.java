package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** @author Robert Andersson Kopler */
@Component
public class MobilityStatsSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MobilityStatsSyncScheduler.class);
    private final MobilityStatsSyncService service;

    public MobilityStatsSyncScheduler(MobilityStatsSyncService service) {
        this.service = service;
    }

    // Sista ledet i NattkedjanScheduler den 4:e varje månad — månadsrapporten publiceras den 1:a–3:e
    public void monthlySync() {
        log.info("Monthly Mobility Sweden stats sync triggered");
        Object status = service.syncNow().get("status");
        log.info("Monthly Mobility Sweden stats sync finished — status {}", status);
    }
}
