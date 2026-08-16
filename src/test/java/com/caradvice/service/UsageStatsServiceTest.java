package com.caradvice.service;

import com.caradvice.repository.RateLimitLogRepository;
import com.caradvice.repository.SavedSearchRepository;
import com.caradvice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageStatsServiceTest {

    private UserRepository userRepo;
    private SavedSearchRepository savedSearchRepo;
    private RateLimitLogRepository rateLimitLogRepo;
    private UsageStatsService service;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        savedSearchRepo = mock(SavedSearchRepository.class);
        rateLimitLogRepo = mock(RateLimitLogRepository.class);
        service = new UsageStatsService(userRepo, savedSearchRepo, rateLimitLogRepo);

        when(savedSearchRepo.count()).thenReturn(0L);
        when(userRepo.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);
        when(userRepo.countByCancelAtPeriodEndTrue()).thenReturn(0L);
        when(rateLimitLogRepo.countRecentRecommend(any(LocalDateTime.class))).thenReturn(0L);
        when(rateLimitLogRepo.countDistinctKeysSince(any(LocalDateTime.class))).thenReturn(0L);
    }

    private void konton(long antal, long aktiva, long harBetalat) {
        when(userRepo.count()).thenReturn(antal);
        when(userRepo.countBySubscriptionStatus(anyString())).thenReturn(aktiva);
        when(userRepo.countBySubscriptionStartedAtIsNotNull()).thenReturn(harBetalat);
    }

    /**
     * Läget 2026-08-16: ett enda konto, som en gång betalat och slutat. Utan spärren
     * rapporterade mätningen "100 %" — det bästa tänkbara talet, från vårt eget testkonto,
     * på precis den siffra betalmodellen skulle bedömas på.
     */
    @Test
    void ettEndaKontoGerIngenKonverteringsprocent() {
        konton(1, 0, 1);

        Map<String, Object> snapshot = service.snapshot();

        assertThat(snapshot.get("conversionPct")).isNull();
        assertThat((String) snapshot.get("conversionNote")).contains("För få konton (1 av 20)");
    }

    @Test
    void strax_under_gransen_ar_fortfarande_tomt() {
        konton(19, 3, 5);

        assertThat(service.snapshot().get("conversionPct")).isNull();
    }

    @Test
    void vidGransenRaknasAndelenUt() {
        konton(20, 3, 5);

        Map<String, Object> snapshot = service.snapshot();

        assertThat(snapshot.get("conversionPct")).isEqualTo(25.0);
        assertThat((String) snapshot.get("conversionNote")).contains("20 konton");
    }

    /** Tom databas får inte heller ge ett tal — och framför allt inte dividera med noll. */
    @Test
    void tomDatabasGerVarkenTalEllerKrasch() {
        konton(0, 0, 0);

        assertThat(service.snapshot().get("conversionPct")).isNull();
    }

    /** churnedSubscribers får aldrig bli negativt om active råkar ligga över everSubscribed. */
    @Test
    void churnKanInteBliNegativ() {
        konton(30, 8, 5);

        assertThat(service.snapshot().get("churnedSubscribers")).isEqualTo(0L);
    }
}
