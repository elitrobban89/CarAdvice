package com.caradvice.repository;

import com.caradvice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findBySessionToken(String sessionToken);
    Optional<User> findByStripeCustomerId(String stripeCustomerId);

    /* Trattmätning — se UsageStatsService. Stripe ser bara de som nått kassan; de
     * som registrerat sig men aldrig klickat på köpknappen finns bara här. */
    long countBySubscriptionStatus(String subscriptionStatus);
    long countByCreatedAtAfter(LocalDateTime cutoff);
    /** Satt vid FÖRSTA aktiveringen och nollställs aldrig — antal som någonsin betalat. */
    long countBySubscriptionStartedAtIsNotNull();
    long countByCancelAtPeriodEndTrue();
}
