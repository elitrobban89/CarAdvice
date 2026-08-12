package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CargoSpecSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CargoSpecSyncScheduler.class);
    private final CargoSpecSyncService service;
    private final AutoDataCargoFillService autoDataFill;
    private final JobStatusService jobStatus;

    public CargoSpecSyncScheduler(CargoSpecSyncService service, AutoDataCargoFillService autoDataFill,
                                  JobStatusService jobStatus) {
        this.service = service;
        this.autoDataFill = autoDataFill;
        this.jobStatus = jobStatus;
    }

    // Runs every day at 03:00 Stockholm time — one hour after the EV sync
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Stockholm")
    public void dailySync() {
        log.info("Daily CargoSpec sync triggered");
        int added = jobStatus.track(JobStatusService.JOB_CARGO_SPECS, () -> {
            int nya = service.syncCarNames();
            // Namnen kommer från Bilweb och täcker hela marknaden, volymerna från ev-database
            // som bara har elbilar. auto-data fyller resten — och körs efter namnhämtningen så
            // att nattens nya bilar kan få sin volym direkt.
            int fyllda = autoDataFill.fyllSaknadeVolymer();
            log.info("Daily CargoSpec sync: {} nya bilnamn, {} bagagevolymer ifyllda", nya, fyllda);
            return nya + fyllda;
        });
        log.info("Daily CargoSpec sync finished — {} rader berörda", added);
    }
}
