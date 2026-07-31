package com.caradvice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpcomingInsightServiceTest {

    private JdbcTemplate jdbc;
    private UpcomingInsightService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new UpcomingInsightService(jdbc);
    }

    @Test
    void markSkriverRadenOchTommerCachen() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L));
        assertThat(service.hiddenIds()).containsExactly(7L);

        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L, 9L));
        service.mark(9L);

        // utan cachetömning hade den gamla mängden legat kvar i fem minuter
        assertThat(service.hiddenIds()).containsExactlyInAnyOrder(7L, 9L);
        verify(jdbc).update(eq("INSERT INTO insight_upcoming(insight_id, marked_at) VALUES (?, ?)"), eq(9L), any());
    }

    @Test
    void markUtanIdGorIngenting() {
        service.mark(null);
        verify(jdbc, times(0)).update(anyString(), any(), any());
    }

    @Test
    void releaseSvararOmRadenFanns() {
        when(jdbc.update(anyString(), eq(5L))).thenReturn(1);
        assertThat(service.release(5L)).isTrue();

        when(jdbc.update(anyString(), eq(6L))).thenReturn(0);
        assertThat(service.release(6L)).isFalse();
    }

    @Test
    void hiddenIdsCachasMellanAnrop() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L));

        service.hiddenIds();
        service.hiddenIds();
        service.hiddenIds();

        // en läsning räcker — mängden slås upp på varje insiktsuppslag
        verify(jdbc, times(1)).queryForList(anyString(), eq(Long.class));
    }

    @Test
    void dbfelDoljerIngenting() {
        doThrow(new RuntimeException("DB nere")).when(jdbc).execute(anyString());
        // fail open: hellre en kommande insikt synlig än att alla insikter slocknar
        assertThat(service.hiddenIds()).isEmpty();
        assertThat(service.isUpcoming(3L)).isFalse();
    }

    @Test
    void isUpcomingSvararPaMarkeradeIdn() {
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(42L));
        assertThat(service.isUpcoming(42L)).isTrue();
        assertThat(service.isUpcoming(43L)).isFalse();
        assertThat(service.isUpcoming(null)).isFalse();
    }
}
