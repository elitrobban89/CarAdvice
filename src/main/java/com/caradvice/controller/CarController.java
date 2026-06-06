package com.caradvice.controller;

import com.caradvice.model.CarPreferences;
import com.caradvice.service.GroqService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
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
}
