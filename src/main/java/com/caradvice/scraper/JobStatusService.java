package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Körstatus för de schemalagda jobben, sparad i DB så den överlever omstart/omdeploy.
 * Renders loggar rullar bort — den här tabellen svarar på "gick nattjobbet?" utan dashboarden.
 * Läses av GET /api/admin/scrape-status.
 */
@Service
public class JobStatusService {

    private static final Logger log = LoggerFactory.getLogger(JobStatusService.class);

    public static final String JOB_EV_SPECS = "ev-specs";
    public static final String JOB_CARGO_SPECS = "cargo-specs";
    public static final String JOB_WEB_INSIGHTS = "web-insights";
    public static final String JOB_MOBILITY_STATS = "mobility-stats";

    /** Visas i statussvaret så NEVER_RUN går att tolka utan att slå upp cron-uttrycken. */
    static final Map<String, String> SCHEDULES = Map.of(
            JOB_EV_SPECS, "dagligen 02:00 Europe/Stockholm",
            JOB_CARGO_SPECS, "dagligen 03:00 Europe/Stockholm",
            JOB_WEB_INSIGHTS, "dagligen 04:00 Europe/Stockholm",
            JOB_MOBILITY_STATS, "den 4:e varje månad 05:00 Europe/Stockholm");

    /** Jobben i körordning — statussvaret listar alla, även de som aldrig kört. */
    static final List<String> JOBS = List.of(JOB_EV_SPECS, JOB_CARGO_SPECS, JOB_WEB_INSIGHTS, JOB_MOBILITY_STATS);

    /** Prefix i detail-kolumnen som markerar misslyckad körning (slipper schemaändring). */
    static final String ERROR_PREFIX = "FEL: ";

    private final JdbcTemplate jdbc;

    public JobStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS web_scrape_status (
                job VARCHAR(50) PRIMARY KEY,
                started_at VARCHAR(40),
                finished_at VARCHAR(40),
                new_insights INT,
                detail VARCHAR(2000)
            )
            """);
    }

    /** Kör jobbet och registrerar start/slut/fel. Fel loggas och sväljs — schemaläggarna ska inte krascha. */
    public int track(String job, CountingJob work) {
        markStarted(job);
        try {
            int count = work.run();
            markFinished(job, count, null);
            return count;
        } catch (Exception e) {
            log.error("Jobb [{}] misslyckades: {}", job, e.getMessage(), e);
            markFailed(job, e.getMessage());
            return -1;
        }
    }

    public void markStarted(String job) {
        write(job, now(), null, null, null);
    }

    public void markFinished(String job, Integer count, String detail) {
        String startedAt = startedAtOf(job);
        write(job, startedAt, now(), count, detail);
    }

    public void markFailed(String job, String error) {
        String startedAt = startedAtOf(job);
        write(job, startedAt, now(), null, ERROR_PREFIX + error);
    }

    /** Senaste körningen för ett jobb. RUNNING = startad men aldrig avslutad (t.ex. omstart mitt i). */
    public Map<String, Object> lastRun(String job) {
        ensureTable();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT started_at, finished_at, new_insights, detail FROM web_scrape_status WHERE job = ?", job);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("status", "NEVER_RUN");
            out.put("info", "Ingen körning registrerad ännu (schemalagd " + SCHEDULES.getOrDefault(job, "—") + ")");
            return out;
        }
        Map<String, Object> row = rows.get(0);
        String detail = (String) row.get("detail");
        out.put("status", statusOf(row.get("finished_at"), detail));
        out.put("startedAt", row.get("started_at"));
        out.put("finishedAt", row.get("finished_at"));
        out.put("newInsights", row.get("new_insights"));
        out.put("perSource", detail);
        out.put("schedule", SCHEDULES.getOrDefault(job, "—"));
        return out;
    }

    /** Alla jobb i körordning — jobbnamn → senaste körningens status. */
    public Map<String, Object> allJobs() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String job : JOBS) {
            try {
                out.put(job, lastRun(job));
            } catch (Exception e) {
                out.put(job, Map.of("status", "ERROR", "info", String.valueOf(e.getMessage())));
            }
        }
        return out;
    }

    private static String statusOf(Object finishedAt, String detail) {
        if (finishedAt == null) return "RUNNING";
        return detail != null && detail.startsWith(ERROR_PREFIX) ? "ERROR" : "OK";
    }

    /** Behåller starttiden när körningen avslutas — raden skrivs om i sin helhet. */
    private String startedAtOf(String job) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT started_at FROM web_scrape_status WHERE job = ?", job);
            if (!rows.isEmpty()) return (String) rows.get(0).get("started_at");
        } catch (Exception e) {
            log.warn("Kunde inte läsa starttid för [{}]: {}", job, e.getMessage());
        }
        return now();
    }

    private void write(String job, String startedAt, String finishedAt, Integer count, String detail) {
        try {
            ensureTable();
            jdbc.update("DELETE FROM web_scrape_status WHERE job = ?", job);
            jdbc.update("INSERT INTO web_scrape_status(job, started_at, finished_at, new_insights, detail) VALUES (?,?,?,?,?)",
                    job, startedAt, finishedAt, count, truncate(detail, 2000));
        } catch (Exception e) {
            // Statusraden får aldrig fälla själva jobbet
            log.warn("Kunde inte skriva körstatus för [{}]: {}", job, e.getMessage());
        }
    }

    static String now() {
        return ZonedDateTime.now(ZoneId.of("Europe/Stockholm"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Jobb som returnerar ett antal (uppdaterade rader, nya insikter …). */
    @FunctionalInterface
    public interface CountingJob {
        int run() throws Exception;
    }
}
