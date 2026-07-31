package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CargoSpecSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CargoSpecSyncScheduler.class);
    private final CargoSpecSyncService service;
    private final JobStatusService jobStatus;

    public CargoSpecSyncScheduler(CargoSpecSyncService service, JobStatusService jobStatus) {
        this.service = service;
        this.jobStatus = jobStatus;
    }

    // Runs every day at 03:00 Stockholm time — one hour after the EV sync
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Stockholm")
    public void dailySync() {
        log.info("Daily CargoSpec sync triggered");
        int added = jobStatus.track(JobStatusService.JOB_CARGO_SPECS, service::syncCarNames);
        log.info("Daily CargoSpec sync finished — {} new cars added", added);
    }
}
