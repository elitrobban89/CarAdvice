package com.caradvice.scraper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobStatusServiceTest {

    private JdbcTemplate jdbc;
    private JobStatusService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new JobStatusService(jdbc);
    }

    private void givenRow(String job, String startedAt, String finishedAt, Integer count, String detail) {
        when(jdbc.queryForList(anyString(), eq(job))).thenReturn(List.of(withNulls(startedAt, finishedAt, count, detail)));
    }

    private static java.util.Map<String, Object> withNulls(String startedAt, String finishedAt, Integer count, String detail) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("started_at", startedAt);
        row.put("finished_at", finishedAt);
        row.put("new_insights", count);
        row.put("detail", detail);
        return row;
    }

    @Test
    void trackReturnerarJobbetsAntalOchSparaStartOchSlut() {
        int result = service.track(JobStatusService.JOB_EV_SPECS, () -> 294);

        assertThat(result).isEqualTo(294);
        // en rad per markStarted/markFinished: DELETE + INSERT vardera
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(eq("DELETE FROM web_scrape_status WHERE job = ?"), eq(JobStatusService.JOB_EV_SPECS));
    }

    @Test
    void trackSvaljerUndantagOchMarkerarFel() {
        int result = service.track(JobStatusService.JOB_CARGO_SPECS, () -> {
            throw new IllegalStateException("Bilweb nere");
        });

        assertThat(result).isEqualTo(-1);
        verify(jdbc).update(anyString(), eq(JobStatusService.JOB_CARGO_SPECS), any(), any(),
                eq(null), eq(JobStatusService.ERROR_PREFIX + "Bilweb nere"));
    }

    @Test
    void lastRunGerNeverRunMedSchemaNarRadSaknas() {
        when(jdbc.queryForList(anyString(), eq(JobStatusService.JOB_MOBILITY_STATS))).thenReturn(List.of());

        Map<String, Object> status = service.lastRun(JobStatusService.JOB_MOBILITY_STATS);

        assertThat(status.get("status")).isEqualTo("NEVER_RUN");
        assertThat((String) status.get("info")).contains("den 4:e varje månad 05:00");
    }

    @Test
    void lastRunGerOkNarKorningenAvslutats() {
        givenRow(JobStatusService.JOB_WEB_INSIGHTS, "2026-07-31 04:00:00", "2026-07-31 04:12:17", 9, "CarUp: 6");

        Map<String, Object> status = service.lastRun(JobStatusService.JOB_WEB_INSIGHTS);

        assertThat(status.get("status")).isEqualTo("OK");
        assertThat(status.get("startedAt")).isEqualTo("2026-07-31 04:00:00");
        assertThat(status.get("newInsights")).isEqualTo(9);
        assertThat(status.get("perSource")).isEqualTo("CarUp: 6");
    }

    @Test
    void lastRunGerRunningNarSlutTidSaknas() {
        givenRow(JobStatusService.JOB_EV_SPECS, "2026-07-31 02:00:00", null, null, null);

        assertThat(service.lastRun(JobStatusService.JOB_EV_SPECS).get("status")).isEqualTo("RUNNING");
    }

    @Test
    void lastRunGerErrorNarDetailBarFelprefix() {
        givenRow(JobStatusService.JOB_CARGO_SPECS, "2026-07-31 03:00:00", "2026-07-31 03:00:04", null,
                JobStatusService.ERROR_PREFIX + "Bilweb nere");

        assertThat(service.lastRun(JobStatusService.JOB_CARGO_SPECS).get("status")).isEqualTo("ERROR");
    }

    @Test
    void allJobsListarAllaFyraIKorordning() {
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(List.of());

        Map<String, Object> jobs = service.allJobs();

        assertThat(jobs.keySet()).containsExactly(
                JobStatusService.JOB_EV_SPECS, JobStatusService.JOB_CARGO_SPECS,
                JobStatusService.JOB_WEB_INSIGHTS, JobStatusService.JOB_MOBILITY_STATS);
    }

    @Test
    void statusskrivningFallerAldrigJobbet() {
        doThrow(new RuntimeException("DB nere")).when(jdbc).execute(anyString());

        // markStarted/markFinished sväljer felet — track() ska ändå returnera jobbets resultat
        assertThat(service.track(JobStatusService.JOB_EV_SPECS, () -> 7)).isEqualTo(7);
    }

    @Test
    void allJobsRapporterarErrorPerJobbVidDbfel() {
        when(jdbc.queryForList(anyString(), anyString())).thenThrow(new RuntimeException("DB nere"));

        Map<String, Object> jobs = service.allJobs();

        assertThat(jobs).hasSize(4);
        jobs.values().forEach(v -> assertThat(((Map<?, ?>) v).get("status")).isEqualTo("ERROR"));
    }
}
