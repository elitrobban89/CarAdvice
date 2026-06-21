package com.caradvice.service;

import com.caradvice.model.User;
import com.caradvice.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionCollection;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${stripe.secret.key}")
    private String secretKey;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    @Value("${stripe.price.id}")
    private String priceId;

    @Value("${app.base.url}")
    private String baseUrl;

    private final UserRepository userRepo;

    public StripeService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public String createCheckoutSession(User user) throws Exception {
        Stripe.apiKey = secretKey;

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(baseUrl + "/success.html?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/cancel.html")
                .setCustomerEmail(user.getEmail())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .putMetadata("userId", String.valueOf(user.getId()))
                .putMetadata("token", user.getSessionToken())
                .build();

        Session session = Session.create(params);
        return session.getUrl();
    }

    private LocalDateTime toLocalDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds == 0) return null;
        return LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC);
    }

    public void handleWebhook(String payload, String sigHeader) throws Exception {
        Stripe.apiKey = secretKey;

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new RuntimeException("Ogiltig webhook-signatur");
        }

        String type = event.getType();
        log.info("Stripe webhook received: {}", type);

        // Parse raw JSON — works regardless of API version mismatch
        JsonNode data = mapper.readTree(payload).path("data").path("object");

        switch (type) {
            case "checkout.session.completed" -> {
                String userIdStr = data.path("metadata").path("userId").asText(null);
                String customerId = data.path("customer").asText(null);
                String subscriptionId = data.path("subscription").asText(null);
                log.info("checkout.session.completed — userId={} customerId={} subscriptionId={}", userIdStr, customerId, subscriptionId);
                if (userIdStr != null && customerId != null) {
                    LocalDateTime endsAt = fetchSubscriptionEnd(subscriptionId);
                    final String cid = customerId;
                    final LocalDateTime ea = endsAt;
                    userRepo.findById(Long.parseLong(userIdStr)).ifPresentOrElse(u -> {
                        u.setStripeCustomerId(cid);
                        u.setSubscriptionStatus("active");
                        if (ea != null) u.setSubscriptionEndsAt(ea);
                        if (u.getSubscriptionStartedAt() == null) u.setSubscriptionStartedAt(LocalDateTime.now(ZoneOffset.UTC));
                        userRepo.save(u);
                        log.info("Activated subscription for userId={}", u.getId());
                    }, () -> log.warn("User not found for userId={}", userIdStr));
                }
            }
            case "customer.subscription.created", "customer.subscription.resumed", "invoice.payment_succeeded" -> {
                String customerId = data.path("customer").asText(null);
                long periodEnd = data.path("current_period_end").asLong(0);
                LocalDateTime endsAt = toLocalDateTime(periodEnd > 0 ? periodEnd : null);
                log.info("{} — customerId={} endsAt={}", type, customerId, endsAt);
                activateByCustomerId(customerId, endsAt);
            }
            case "customer.subscription.updated" -> {
                String customerId = data.path("customer").asText(null);
                boolean cancelAtEnd = data.path("cancel_at_period_end").asBoolean(false);
                // current_period_end moved to items in newer Stripe API versions — use cancel_at as fallback
                long endTs = data.path("current_period_end").asLong(0);
                if (endTs == 0) endTs = data.path("cancel_at").asLong(0);
                LocalDateTime endsAt = toLocalDateTime(endTs > 0 ? endTs : null);
                log.info("subscription.updated — customerId={} cancelAtEnd={} endsAt={}", customerId, cancelAtEnd, endsAt);
                if (customerId != null) {
                    userRepo.findByStripeCustomerId(customerId).ifPresent(u -> {
                        u.setCancelAtPeriodEnd(cancelAtEnd);
                        if (endsAt != null) u.setSubscriptionEndsAt(endsAt);
                        userRepo.save(u);
                    });
                }
            }
            case "customer.subscription.deleted", "customer.subscription.paused" -> {
                String customerId = data.path("customer").asText(null);
                log.info("{} — customerId={}", type, customerId);
                if (customerId != null) {
                    userRepo.findByStripeCustomerId(customerId).ifPresent(u -> {
                        u.setSubscriptionStatus("inactive");
                        u.setCancelAtPeriodEnd(false);
                        userRepo.save(u);
                        log.info("Deactivated subscription for user={}", u.getEmail());
                    });
                }
            }
        }
    }

    public void reactivateSubscription(User user) throws Exception {
        Stripe.apiKey = secretKey;
        if (user.getStripeCustomerId() == null) throw new RuntimeException("Ingen aktiv prenumeration");
        SubscriptionCollection subs = Subscription.list(SubscriptionListParams.builder()
                .setCustomer(user.getStripeCustomerId())
                .setStatus(SubscriptionListParams.Status.ACTIVE)
                .build());
        if (subs.getData().isEmpty()) throw new RuntimeException("Ingen prenumeration att återaktivera");
        Subscription updated = subs.getData().get(0).update(SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(false)
                .build());
        user.setCancelAtPeriodEnd(false);
        Long periodEnd = updated.getCurrentPeriodEnd();
        if (periodEnd != null && periodEnd > 0) user.setSubscriptionEndsAt(toLocalDateTime(periodEnd));
        userRepo.save(user);
        log.info("Subscription reactivated for user={}", user.getEmail());
    }

    public void cancelSubscription(User user) throws Exception {
        Stripe.apiKey = secretKey;
        if (user.getStripeCustomerId() == null) throw new RuntimeException("Ingen aktiv prenumeration");
        SubscriptionCollection subs = Subscription.list(SubscriptionListParams.builder()
                .setCustomer(user.getStripeCustomerId())
                .setStatus(SubscriptionListParams.Status.ACTIVE)
                .build());
        if (subs.getData().isEmpty()) throw new RuntimeException("Ingen aktiv prenumeration hittades");
        Subscription updated = subs.getData().get(0).update(SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(true)
                .build());
        user.setCancelAtPeriodEnd(true);
        Long cancelAt = updated.getCancelAt();
        if (cancelAt != null && cancelAt > 0) user.setSubscriptionEndsAt(toLocalDateTime(cancelAt));
        userRepo.save(user);
        log.info("Subscription set to cancel at period end for user={}", user.getEmail());
    }

    private LocalDateTime fetchSubscriptionEnd(String subscriptionId) {
        if (subscriptionId == null) return null;
        try {
            Subscription sub = Subscription.retrieve(subscriptionId);
            return toLocalDateTime(sub.getCurrentPeriodEnd());
        } catch (Exception e) {
            log.warn("Could not fetch subscription {}: {}", subscriptionId, e.getMessage());
            return null;
        }
    }

    private void activateByCustomerId(String customerId, LocalDateTime endsAt) {
        if (customerId == null) return;
        Optional<User> userOpt = userRepo.findByStripeCustomerId(customerId);
        if (userOpt.isEmpty()) {
            log.info("No user by stripeCustomerId={}, trying email lookup", customerId);
            try {
                Customer customer = Customer.retrieve(customerId);
                String email = customer.getEmail();
                log.info("Stripe customer email={}", email);
                if (email != null) userOpt = userRepo.findByEmail(email.toLowerCase());
            } catch (Exception e) {
                log.error("Failed to retrieve Stripe customer {}: {}", customerId, e.getMessage());
            }
        }
        if (userOpt.isPresent()) {
            User u = userOpt.get();
            u.setStripeCustomerId(customerId);
            u.setSubscriptionStatus("active");
            if (endsAt != null) u.setSubscriptionEndsAt(endsAt);
            if (u.getSubscriptionStartedAt() == null) u.setSubscriptionStartedAt(LocalDateTime.now(ZoneOffset.UTC));
            userRepo.save(u);
            log.info("Subscription activated for user={}", u.getEmail());
        } else {
            log.warn("Could not find user for customerId={}", customerId);
        }
    }
}
