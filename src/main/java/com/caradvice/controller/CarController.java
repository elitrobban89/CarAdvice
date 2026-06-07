package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.service.GroqService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://elitrobban.se", "http://localhost:8080", "http://localhost:3000", "http://127.0.0.1:8080"})
public class CarController {

    private final GroqService groqService;
    private final Map<String, List<Long>> ipRequestLog = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_HOUR = 10;

    public CarController(GroqService groqService) {
        this.groqService = groqService;
    }

    @PostMapping("/recommend")
    public ResponseEntity<?> recommend(@RequestBody CarPreferences prefs, HttpServletRequest request) {
        String ip = getClientIp(request);
        if (isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "error", "För många förfrågningar från din IP. Försök igen om en stund."
            ));
        }
        try {
            GroqService.Result result = groqService.getRecommendation(prefs);
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("recommendations", result.recommendations());
            if (result.fromCache()) {
                body.put("cached", true);
                body.put("cachedAgeMinutes", result.cacheAgeSeconds() / 60);
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    @GetMapping("/recommend/test")
    public ResponseEntity<?> recommendTest() {
        boolean configured = groqService.isConfigured();
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "groq", configured ? "OK" : "WARN",
                "rekommendation", configured
        ));
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long windowStart = now - 3_600_000;
        ipRequestLog.compute(ip, (k, times) -> {
            List<Long> updated = (times == null) ? new ArrayList<>() : times;
            updated.removeIf(t -> t < windowStart);
            updated.add(now);
            return updated;
        });
        return ipRequestLog.get(ip).size() > MAX_REQUESTS_PER_HOUR;
    }
}
