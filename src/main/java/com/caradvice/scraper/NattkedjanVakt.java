package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Tar igen en missad nattkedja.
 *
 * <p><b>Varför.</b> Kedjan körs en gång per dygn, 23:00 UTC. Låg Render nere då, eller startade
 * tjänsten om mitt i kedjan (en deploy räcker), blev det ingen skrapning alls det dygnet — och
 * nattrutinen kunde bara skriva LARM och vänta på en människa. Allt som behövs för att se det
 * finns redan i {@code web_scrape_status}: skrapningens senaste {@code finished_at}.
 *
 * <p><b>Regeln.</b> Vid uppstart och en gång i timmen: är skrapningen (kedjans sista dagliga led)
 * äldre än {@value #MAX_ALDER_TIMMAR} timmar, eller har den stått som RUNNING i mer än
 * {@value #HANGD_TIMMAR} timmar (omstart mitt i), körs hela kedjan i bakgrunden. Aldrig inom
 * fönstret runt den ordinarie körningen, aldrig två gånger inom {@value #MIN_MELLANRUM_TIMMAR}
 * timmar, och aldrig utanför Render — lokalt hade varje uppstart mot en gammal databas dragit
 * igång en full skrapning mot Groq.
 *
 * <p>{@code NEVER_RUN} tas inte igen: det är en ny databas, inte en missad natt.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class NattkedjanVakt {

    private static final Logger log = LoggerFactory.getLogger(NattkedjanVakt.class);
    private static final DateTimeFormatter STATUSFORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    static final int MAX_ALDER_TIMMAR = 26;
    static final int HANGD_TIMMAR = 3;
    static final int MIN_MELLANRUM_TIMMAR = 20;

    private final JobStatusService jobStatus;
    private final NattkedjanScheduler kedjan;
    private final boolean paRender;

    Clock klocka = Clock.systemUTC();
    private volatile Instant senasteIgenkorning;

    /** RENDER sätts av Render själv - lokalt är den tom och vakten gör ingenting. */
    @Autowired
    public NattkedjanVakt(JobStatusService jobStatus, NattkedjanScheduler kedjan,
                          @Value("${RENDER:false}") boolean paRender) {
        this.jobStatus = jobStatus;
        this.kedjan = kedjan;
        this.paRender = paRender;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void vidUppstart() {
        kollaIBakgrunden("uppstart");
    }

    @Scheduled(cron = "0 20 * * * *", zone = "UTC")
    public void varjeTimme() {
        kollaIBakgrunden("timkoll");
    }

    private void kollaIBakgrunden(String varfor) {
        if (!paRender) return;
        Thread t = new Thread(() -> {
            try {
                String skal = skalAttTaIgen();
                if (skal != null) {
                    senasteIgenkorning = klocka.instant();
                    log.warn("Nattkedjan tas igen ({}): {}", varfor, skal);
                    kedjan.kor();
                }
            } catch (Exception e) {
                log.warn("Nattkedjevakten kunde inte kolla: {}", e.getMessage());
            }
        }, "nattkedjevakt");
        t.setDaemon(true);
        t.start();
    }

    /** @return skälet att köra kedjan igen, eller null om allt är som det ska */
    String skalAttTaIgen() {
        Instant nu = klocka.instant();
        if (kedjan.pagar()) return null;
        int timme = nu.atZone(ZoneOffset.UTC).getHour();
        // 22:00-01:59 UTC: den ordinarie körningen står i tur eller pågår just nu
        if (timme >= 22 || timme < 2) return null;
        if (senasteIgenkorning != null
                && Duration.between(senasteIgenkorning, nu).toHours() < MIN_MELLANRUM_TIMMAR) return null;

        Map<String, Object> senast = jobStatus.lastRun("web-insights");
        String status = String.valueOf(senast.get("status"));
        if ("NEVER_RUN".equals(status)) return null;
        if ("RUNNING".equals(status)) {
            Instant start = tolka(senast.get("startedAt"));
            if (start != null && Duration.between(start, nu).toHours() >= HANGD_TIMMAR)
                return "skrapningen har stått som RUNNING sedan " + senast.get("startedAt") + " (omstart mitt i kedjan?)";
            return null;
        }
        Instant klar = tolka(senast.get("finishedAt"));
        if (klar == null) return null;
        long timmar = Duration.between(klar, nu).toHours();
        return timmar >= MAX_ALDER_TIMMAR
                ? "senaste skrapningen blev klar " + senast.get("finishedAt") + ", " + timmar + " timmar sedan"
                : null;
    }

    private static Instant tolka(Object tid) {
        if (tid == null) return null;
        try {
            return LocalDateTime.parse(tid.toString(), STATUSFORMAT).atZone(STOCKHOLM).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
