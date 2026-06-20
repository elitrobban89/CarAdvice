package com.caradvice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ca_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "subscription_status")
    private String subscriptionStatus = "inactive";

    @Column(name = "session_token", unique = true)
    private String sessionToken;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public User() {}

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getStripeCustomerId() { return stripeCustomerId; }
    public String getSubscriptionStatus() { return subscriptionStatus; }
    public String getSessionToken() { return sessionToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setStripeCustomerId(String v) { this.stripeCustomerId = v; }
    public void setSubscriptionStatus(String v) { this.subscriptionStatus = v; }
    public void setSessionToken(String v) { this.sessionToken = v; }
}
