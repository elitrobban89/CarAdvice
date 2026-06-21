package com.caradvice.service;

import com.caradvice.model.User;
import com.caradvice.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository repo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void backfillSubscriptionStartedAt() {
        repo.findAll().forEach(u -> {
            if ("active".equals(u.getSubscriptionStatus()) && u.getSubscriptionStartedAt() == null) {
                u.setSubscriptionStartedAt(u.getCreatedAt());
                repo.save(u);
            }
        });
    }

    public User register(String email, String password) {
        if (email == null || !email.contains("@")) throw new RuntimeException("Ogiltig e-postadress");
        if (password == null || password.length() < 6) throw new RuntimeException("Lösenordet måste vara minst 6 tecken");
        if (repo.findByEmail(email.toLowerCase()).isPresent()) throw new RuntimeException("E-postadressen är redan registrerad");
        User user = new User(email.toLowerCase(), encoder.encode(password));
        user.setSessionToken(UUID.randomUUID().toString());
        return repo.save(user);
    }

    public User login(String email, String password) {
        User user = repo.findByEmail(email == null ? "" : email.toLowerCase())
                .orElseThrow(() -> new RuntimeException("Fel e-postadress eller lösenord"));
        if (!encoder.matches(password, user.getPasswordHash()))
            throw new RuntimeException("Fel e-postadress eller lösenord");
        user.setSessionToken(UUID.randomUUID().toString());
        return repo.save(user);
    }

    public Optional<User> findByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findBySessionToken(token);
    }

    public void logout(String token) {
        findByToken(token).ifPresent(u -> {
            u.setSessionToken(null);
            repo.save(u);
        });
    }

    public boolean isActiveSubscriber(String authHeader) {
        String token = extractToken(authHeader);
        return findByToken(token)
                .map(u -> "active".equals(u.getSubscriptionStatus()))
                .orElse(false);
    }

    public boolean isLoggedIn(String authHeader) {
        String token = extractToken(authHeader);
        return findByToken(token).isPresent();
    }

    public String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) return authHeader.substring(7);
        return null;
    }
}
