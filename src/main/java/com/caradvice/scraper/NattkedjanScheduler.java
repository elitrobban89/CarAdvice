package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Nattens fyra jobb i EN följd: ev-specs → cargo-specs → web-insights (med autosläppet), och
 * mobility-stats den 4:e.
 *
 * <p><b>Varför en kedja och inte fyra klockslag.</b> Jobben startade med en timmes mellanrum
 * (00/01/02) fast de tillsammans tar drygt 40 minuter (2026-09-25: 13 + 9 + 19). Tre timmars
 * väggtid för 40 minuters arbete — och molnrutinerna fick gissa när sista jobbet var klart, med
 * tio minuters marginal. Nu börjar varje led när det förra är klart, i samma ordning som förut,
 * så nattens nya bilnamn hinner få sin volym och skrapningen ser nattens specar.
 *
 * <p><b>Varför UTC och inte Europe/Stockholm.</b> Molnrutinerna schemaläggs i UTC. Med appen på
 * svensk tid gled de isär en timme vid varje tidsomställning — från 2026-10-25 hade
 * kontrollrutinen gått FÖRE skrapningen och larmat falskt varje natt. Båda sidor på UTC håller
 * avståndet konstant året runt. 23:00 UTC är 01:00 sommartid och 00:00 vintertid, alltså alltid
 * efter svensk midnatt — rutinernas "dagens datum" håller båda halvåren.
 *
 * <p>Varje led har en egen {@code try}: ett trasigt ev-database får inte ta cargo och skrapningen
 * med sig. {@link JobStatusService#track} fångar redan jobbens fel, men mobility-synken och
 * autosläppet går inte genom den.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class NattkedjanScheduler {

    private static final Logger log = LoggerFactory.getLogger(NattkedjanScheduler.class);
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    private final EvSpecSyncScheduler evSpecs;
    private final CargoSpecSyncScheduler cargoSpecs;
    private final WebInsightSyncScheduler webInsights;
    private final MobilityStatsSyncScheduler mobility;
    private final NattkedjanStatus status;
    private final GitHubSignal signal;

    /** Utbytbar i prov — mobility-ledet hänger på datumet. */
    Clock klocka = Clock.systemUTC();

    public NattkedjanScheduler(EvSpecSyncScheduler evSpecs, CargoSpecSyncScheduler cargoSpecs,
                               WebInsightSyncScheduler webInsights, MobilityStatsSyncScheduler mobility,
                               NattkedjanStatus status, GitHubSignal signal) {
        this.evSpecs = evSpecs;
        this.cargoSpecs = cargoSpecs;
        this.webInsights = webInsights;
        this.mobility = mobility;
        this.status = status;
        this.signal = signal;
    }

    @Scheduled(cron = "0 0 23 * * *", zone = "UTC")
    public void kor() {
        Instant start = klocka.instant();
        log.info("Nattkedjan startar");
        led("ev-specs", evSpecs::dailySync);
        led("cargo-specs", cargoSpecs::dailySync);
        led("web-insights", webInsights::dailySync);
        // Månadsrapporten publiceras den 1:a–3:e; datumet räknas i svensk tid som förut
        if (LocalDate.ofInstant(klocka.instant(), STOCKHOLM).getDayOfMonth() == 4)
            led("mobility-stats", mobility::monthlySync);
        Instant klar = klocka.instant();
        log.info("Nattkedjan klar efter {} min", Duration.between(start, klar).toMinutes());
        status.klar(klar);
        // Sist: signalen startar granskningen, som ska se en färdig natt. Kastar aldrig.
        status.signal(klocka.instant(), signal.skicka());
    }

    private void led(String namn, Runnable jobb) {
        try {
            jobb.run();
        } catch (Exception e) {
            log.error("Nattkedjan: {} kastade {} — kedjan fortsätter", namn, e.getMessage(), e);
        }
    }
}
