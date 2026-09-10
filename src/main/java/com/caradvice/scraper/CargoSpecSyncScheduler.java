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
        // trackDetailed, inte track: de tre delarna måste stå var för sig i scrape-status,
        // annars går "0 volymer, 5 nya namn" inte att skilja från "5 volymer, 0 nya namn".
        // Se JobStatusService.Utfall för natten som gjorde skillnaden omöjlig att avgöra.
        int added = jobStatus.trackDetailed(JobStatusService.JOB_CARGO_SPECS, () -> {
            int nya = service.syncCarNames();
            // Namnen kommer från Bilweb och täcker hela marknaden, volymerna från ev-database
            // som bara har elbilar. auto-data fyller resten — och körs efter namnhämtningen så
            // att nattens nya bilar kan få sin volym direkt.
            int fyllda = autoDataFill.fyllSaknadeVolymer();
            // Efter bagaget: sidcachen är varm för just de modeller vi nyss besökt, så
            // generationsåret blir nästan gratis för dem. Se IceGenerationService för varför
            // bara årtalet hämtas och inte hela motorutbudet.
            int generationer = autoDataFill.fyllGenerationsar();
            log.info("Daily CargoSpec sync: {} nya bilnamn, {} bagagevolymer, {} generationsår ifyllda",
                    nya, fyllda, generationer);
            return new JobStatusService.Utfall(nya + fyllda + generationer,
                    "nya bilnamn: " + nya + ", bagagevolymer: " + fyllda + ", generationsår: " + generationer);
        });
        log.info("Daily CargoSpec sync finished — {} rader berörda", added);
    }
}
