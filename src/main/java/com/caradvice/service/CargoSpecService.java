package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.model.CargoSpecDto;
import com.caradvice.repository.CargoSpecRepository;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

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

        if (match == null || match.getCargoLiters() == null) return null;
        return new CargoSpecDto(match.getCargoLiters(),
                match.getCargoMaxLiters() != null ? match.getCargoMaxLiters() : 0);
    }

    // Updates existing entries that have null cargo_liters; inserts new entries
    @Transactional
    public int upsertCsv(String csv) {
        List<CargoSpec> all = repo.findAll();
        Map<String, CargoSpec> byNorm = new HashMap<>();
        for (CargoSpec cs : all) byNorm.put(normalize(cs.getCarName()), cs);

        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isBlank() || line.startsWith("#") || line.toLowerCase().startsWith("car_name")) continue;
            String[] parts = line.split(",", 3);
            String name = parts[0].trim().replaceAll("^\"|\"$", "");
            if (name.isBlank() || parts.length < 2) continue;
            Integer liters = null, maxLiters = null;
            try { liters = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
            if (parts.length > 2) { try { maxLiters = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {} }
            if (liters == null) continue;

            String normName = normalize(name);
            CargoSpec existing = byNorm.get(normName);
            if (existing != null && existing.getCargoLiters() == null) {
                existing.setCargoLiters(liters);
                if (maxLiters != null) existing.setCargoMaxLiters(maxLiters);
                repo.save(existing);
                count++;
            } else if (existing == null) {
                CargoSpec newEntry = new CargoSpec(name, liters, maxLiters);
                repo.save(newEntry);
                byNorm.put(normName, newEntry);
                count++;
            }
        }
        return count;
    }

    // CSV format: car_name,cargo_liters,cargo_max_liters (header row optional, cargo cols optional)
    public int importCsv(String csv) {
        Set<String> existing = repo.findAllCarNames().stream()
                .map(CargoSpecService::normalize)
                .collect(Collectors.toSet());
        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isBlank() || line.startsWith("#") || line.toLowerCase().startsWith("car_name")) continue;
            String[] parts = line.split(",", 3);
            String name = parts[0].trim().replaceAll("^\"|\"$", "");
            if (name.isBlank()) continue;
            Integer liters = null, maxLiters = null;
            if (parts.length > 1) { try { liters = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {} }
            if (parts.length > 2) { try { maxLiters = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {} }
            if (!existing.contains(normalize(name))) {
                repo.save(new CargoSpec(name, liters, maxLiters));
                existing.add(normalize(name));
                count++;
            }
        }
        return count;
    }

    // Rear legroom in mm — sourced from evspecifications.com
    private static final Map<String, Integer> LEGROOM_MM = Map.ofEntries(
        Map.entry("Volvo EX30",              821),
        Map.entry("Volvo EX40",              917),
        Map.entry("Volvo C40",               917),
        Map.entry("Volvo XC40",              917),
        Map.entry("Volvo EX60",              950),
        Map.entry("Volvo EX90",              926),
        Map.entry("Tesla Model 3",           894),
        Map.entry("Tesla Model Y",          1029),
        Map.entry("Tesla Model S",           975),
        Map.entry("Volkswagen ID.3",         879),
        Map.entry("Volkswagen ID.4",         954),
        Map.entry("Volkswagen ID.5",         950),
        Map.entry("Volkswagen ID.7",        1000),
        Map.entry("Volkswagen ID.Buzz",     1100),
        Map.entry("Hyundai IONIQ 5",        1001),
        Map.entry("Hyundai IONIQ 6",         985),
        Map.entry("Kia EV6",               1006),
        Map.entry("Kia EV3",                820),
        Map.entry("Kia EV9",              1087),
        Map.entry("Kia Niro EV",             937),
        Map.entry("Kia Niro",               937),
        Map.entry("Polestar 2",             862),
        Map.entry("Polestar 3",             950),
        Map.entry("Polestar 4",             910),
        Map.entry("BMW i4",                 944),
        Map.entry("BMW i5",                 980),
        Map.entry("BMW iX1",                910),
        Map.entry("BMW iX3",                920),
        Map.entry("BMW iX",                 988),
        Map.entry("Audi Q4 e-tron",         984),
        Map.entry("Audi Q6 e-tron",         990),
        Map.entry("Audi Q8 e-tron",         960),
        Map.entry("Mercedes EQA",           693),
        Map.entry("Mercedes EQB",           968),
        Map.entry("Mercedes EQC",           880),
        Map.entry("Mercedes EQE",          1050),
        Map.entry("Mercedes EQS",          1050),
        Map.entry("Skoda Enyaq",            945),
        Map.entry("Skoda Elroq",            900),
        Map.entry("Cupra Born",             879),
        Map.entry("MG4",                    870),
        Map.entry("MG ZS EV",              920),
        Map.entry("MG ZS",                  920),
        Map.entry("MG5",                    870),
        Map.entry("BYD Atto 3",             880),
        Map.entry("BYD Dolphin",            810),
        Map.entry("Dacia Spring",           843),
        Map.entry("Fiat 500e",              702),
        Map.entry("Renault Zoe",            740),
        Map.entry("Renault Megane E-Tech",  835),
        Map.entry("Nissan Leaf",            808),
        Map.entry("Nissan Ariya",           940),
        Map.entry("Toyota bZ4X",            897),
        Map.entry("Ford Mustang Mach-E",    975),
        Map.entry("Xpeng G6",              950),
        Map.entry("Hyundai Kona",           820),
        Map.entry("Hyundai Kona Electric",  820)
    );

    public Integer getLegroom(String title) {
        if (title == null) return null;
        String cleaned = normalize(title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim());
        String[] cleanedWords = cleaned.split("\\s+");
        Set<String> cleanedSet = new HashSet<>(Arrays.asList(cleanedWords));

        // Pass 1: all map-key words are present in title
        return LEGROOM_MM.entrySet().stream()
                .filter(e -> {
                    String[] keyWords = normalize(e.getKey()).split("\\s+");
                    for (String w : keyWords) if (!cleanedSet.contains(w)) return false;
                    return true;
                })
                .max(Comparator.comparingInt(e -> normalize(e.getKey()).split("\\s+").length))
                .map(Map.Entry::getValue)
                .orElse(null);
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
