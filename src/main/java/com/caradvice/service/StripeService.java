package com.caradvice.service;

import com.caradvice.model.User;
import com.caradvice.repository.UserRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StripeService {

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

    public void handleWebhook(String payload, String sigHeader) throws Exception {
        Stripe.apiKey = secretKey;

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new RuntimeException("Ogiltig webhook-signatur");
        }

        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();

        switch (event.getType()) {
            case "checkout.session.completed" -> {
                if (deserializer.getObject().isPresent()) {
                    Session session = (Session) deserializer.getObject().get();
                    String userIdStr = session.getMetadata().get("userId");
                    String customerId = session.getCustomer();
                    if (userIdStr != null) {
                        userRepo.findById(Long.parseLong(userIdStr)).ifPresent(u -> {
                            u.setStripeCustomerId(customerId);
                            u.setSubscriptionStatus("active");
                            userRepo.save(u);
                        });
                    }
                }
            }
            case "customer.subscription.deleted", "customer.subscription.paused" -> {
                if (deserializer.getObject().isPresent()) {
                    Subscription sub = (Subscription) deserializer.getObject().get();
                    String customerId = sub.getCustomer();
                    userRepo.findByStripeCustomerId(customerId).ifPresent(u -> {
                        u.setSubscriptionStatus("inactive");
                        userRepo.save(u);
                    });
                }
            }
            case "customer.subscription.resumed", "invoice.payment_succeeded" -> {
                if (deserializer.getObject().isPresent() &&
                        deserializer.getObject().get() instanceof Subscription sub) {
                    String customerId = sub.getCustomer();
                    userRepo.findByStripeCustomerId(customerId).ifPresent(u -> {
                        u.setSubscriptionStatus("active");
                        userRepo.save(u);
                    });
                }
            }
        }
    }
}
