package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** @author Robert Andersson Kopler */
@Component
public class EvSpecSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvSpecSyncScheduler.class);
    private final EvDatabaseScraperService scraper;
    private final JobStatusService jobStatus;

    public EvSpecSyncScheduler(EvDatabaseScraperService scraper, JobStatusService jobStatus) {
        this.scraper = scraper;
        this.jobStatus = jobStatus;
    }

    // Första ledet i NattkedjanScheduler (23:00 UTC) — har inget eget klockslag längre
    public void dailySync() {
        log.info("Daily EV spec sync triggered");
        int updated = jobStatus.track(JobStatusService.JOB_EV_SPECS, scraper::syncFromEvDatabase);
        log.info("Daily sync finished — {} records updated", updated);
    }
}
