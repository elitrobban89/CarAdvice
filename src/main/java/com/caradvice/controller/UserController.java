package com.caradvice.controller;

import com.caradvice.model.SavedSearch;
import com.caradvice.model.User;
import com.caradvice.service.SavedSearchService;
import com.caradvice.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final SavedSearchService savedSearchService;

    public UserController(UserService userService, SavedSearchService savedSearchService) {
        this.userService = userService;
        this.savedSearchService = savedSearchService;
    }

    @PostMapping("/saved-searches")
    public ResponseEntity<?> saveSearch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> body) {
        Optional<User> user = userService.findByToken(userService.extractToken(auth));
        if (user.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "Inte inloggad"));
        try {
            SavedSearch saved = savedSearchService.save(
                    user.get(),
                    body.get("prefsJson"),
                    body.get("recommendationsJson"),
                    body.getOrDefault("label", "Sparad sökning")
            );
            return ResponseEntity.ok(Map.of("id", saved.getId(), "label", saved.getLabel() != null ? saved.getLabel() : ""));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/saved-searches")
    public ResponseEntity<?> listSaved(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> user = userService.findByToken(userService.extractToken(auth));
        if (user.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "Inte inloggad"));
        List<SavedSearch> list = savedSearchService.findByUser(user.get());
        return ResponseEntity.ok(list.stream().map(s -> Map.of(
                "id", (Object) s.getId(),
                "label", s.getLabel() != null ? s.getLabel() : "",
                "prefsJson", s.getPrefsJson() != null ? s.getPrefsJson() : "{}",
                "recommendationsJson", s.getRecommendationsJson() != null ? s.getRecommendationsJson() : "[]",
                "createdAt", s.getCreatedAt().toString()
        )).toList());
    }

    @DeleteMapping("/saved-searches/{id}")
    public ResponseEntity<?> deleteSearch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long id) {
        Optional<User> user = userService.findByToken(userService.extractToken(auth));
        if (user.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "Inte inloggad"));
        boolean deleted = savedSearchService.deleteById(id, user.get());
        return deleted
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(404).body(Map.of("error", "Hittades inte"));
    }
}
