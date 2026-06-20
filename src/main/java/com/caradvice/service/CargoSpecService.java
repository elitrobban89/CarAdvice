package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.model.CargoSpecDto;
import com.caradvice.repository.CargoSpecRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

@Service
public class CargoSpecService {

    private final CargoSpecRepository repo;

    public CargoSpecService(CargoSpecRepository repo) {
        this.repo = repo;
    }

    public CargoSpecDto formatForTitle(String title) {
        if (title == null) return null;
        String cleaned = normalize(title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim());
        String[] titleWords = cleaned.split("\\s+");
        Set<String> titleSet = new HashSet<>(Arrays.asList(titleWords));

        List<CargoSpec> all = repo.findAll();

        // Pass 1: all title words appear as substrings in stored name
        CargoSpec match = all.stream()
                .filter(cs -> {
                    String name = normalize(cs.getCarName());
                    for (String w : titleWords) if (!name.contains(w)) return false;
                    return true;
                })
                .findFirst().orElse(null);

        // Pass 2: all stored-name words are exact words in title (longest match wins)
        if (match == null) {
            match = all.stream()
                    .filter(cs -> {
                        String[] nameWords = normalize(cs.getCarName()).split("\\s+");
                        for (String w : nameWords) if (!titleSet.contains(w)) return false;
                        return true;
                    })
                    .max(Comparator.comparingInt(cs ->
                            normalize(cs.getCarName()).split("\\s+").length))
                    .orElse(null);
        }

        if (match == null) return null;
        return new CargoSpecDto(
                match.getCargoLiters() != null ? match.getCargoLiters() : 0,
                match.getCargoMaxLiters() != null ? match.getCargoMaxLiters() : 0
        );
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
