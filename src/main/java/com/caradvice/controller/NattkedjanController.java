package com.caradvice.controller;

import com.caradvice.scraper.NattkedjanStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Nattkedjans status och granskningens kvitto — se {@link NattkedjanStatus} för varför kvittot
 * behövs.
 *
 * <p>Egen controller i stället för två metoder till i {@code CarController}: den har redan
 * en lång konstruktor, och kvittot är nattgranskningens angelägenhet, inte bilrådets.
 *
 * @author Robert Andersson Kopler
 */
@RestController
@RequestMapping("/api")
public class NattkedjanController {

    private final NattkedjanStatus status;
    private final String adminKey;

    public NattkedjanController(NattkedjanStatus status, @Value("${admin.key}") String adminKey) {
        this.status = status;
        this.adminKey = adminKey;
    }

    /** Öppen — bara tidsstämplar och ett utfall, ingenting hemligt. */
    @GetMapping("/nattkedjan")
    public Map<String, Object> nattkedjan() {
        return status.somKarta();
    }

    /**
     * Granskningen kvitterar att den är gjord. Den ENDA skrivning molnrutinen får göra mot appen,
     * och den skriver bara en tidsstämpel i minnet — ingenting i databasen.
     */
    @PostMapping("/admin/nattkedjan/granskad")
    public ResponseEntity<?> granskad(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (key == null || !adminKey.equals(key)) return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        Instant nu = Instant.now();
        status.granskad(nu);
        return ResponseEntity.ok(Map.of("granskad", nu.toString()));
    }
}
