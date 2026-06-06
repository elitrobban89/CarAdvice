package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.service.GroqService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            String recommendation = groqService.getRecommendation(prefs);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "recommendation", recommendation
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
        try {
            CarPreferences testPrefs = new CarPreferences(300000, "familjebil", true, 15000, "familj", 4, false);
            String recommendation = groqService.getRecommendation(testPrefs);
            boolean groqOk = recommendation != null && recommendation.toLowerCase().contains("rekommendation");
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "groq", groqOk ? "OK" : "WARN",
                    "rekommendation", groqOk
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "groq", "FAIL",
                    "error", e.getMessage()
            ));
        }
    }
}
