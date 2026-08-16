package com.caradvice.repository;

import com.caradvice.model.RateLimitLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RateLimitLogRepository extends JpaRepository<RateLimitLog, Long> {

    @Query("SELECT r FROM RateLimitLog r WHERE r.requestTime > :cutoff AND r.endpointType = 'recommend'")
    List<RateLimitLog> findRecentRecommend(@Param("cutoff") LocalDateTime cutoff);

    /** Antal sökningar sedan {@code cutoff} — den enda användningshistorik som finns. */
    @Query("SELECT COUNT(r) FROM RateLimitLog r WHERE r.requestTime > :cutoff AND r.endpointType = 'recommend'")
    long countRecentRecommend(@Param("cutoff") LocalDateTime cutoff);

    /** Distinkta nycklar (IP eller konto) sedan {@code cutoff} — grovt mått på unika sökare. */
    @Query("SELECT COUNT(DISTINCT r.ip) FROM RateLimitLog r WHERE r.requestTime > :cutoff AND r.endpointType = 'recommend'")
    long countDistinctKeysSince(@Param("cutoff") LocalDateTime cutoff);

    void deleteByRequestTimeBefore(LocalDateTime cutoff);
}
