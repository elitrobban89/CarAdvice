package com.caradvice.repository;

import com.caradvice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findBySessionToken(String sessionToken);
    Optional<User> findByStripeCustomerId(String stripeCustomerId);
}
