package com.caradvice.controller;

import com.caradvice.model.User;
import com.caradvice.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            User user = userService.register(body.get("email"), body.get("password"));
            return ResponseEntity.ok(userDto(user));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            User user = userService.login(body.get("email"), body.get("password"));
            return ResponseEntity.ok(userDto(user));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        userService.logout(userService.extractToken(auth));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> user = userService.findByToken(userService.extractToken(auth));
        if (user.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "Inte inloggad"));
        return ResponseEntity.ok(userDto(user.get()));
    }

    private Map<String, Object> userDto(User u) {
        return Map.of(
                "email", u.getEmail(),
                "token", u.getSessionToken() != null ? u.getSessionToken() : "",
                "subscriptionStatus", u.getSubscriptionStatus()
        );
    }
}
