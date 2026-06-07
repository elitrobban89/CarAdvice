package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import com.caradvice.service.GroqService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "https://elitrobban.se")
public class CarController {

    private final GroqService groqService;

    public CarController(GroqService groqService) {
        this.groqService = groqService;
    }

    @PostMapping("/recommend")
    public ResponseEntity<?> recommend(@RequestBody CarPreferences prefs) {
        try {
            List<CarRecommendation> recommendations = groqService.getRecommendation(prefs);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "recommendations", recommendations
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Kunde inte hämta rekommendation: " + e.getMessage()
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
}
