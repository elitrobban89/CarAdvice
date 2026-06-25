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

    // Rear legroom in mm — verified from evspecifications.com; estimates marked with ~
    private static final Map<String, Integer> LEGROOM_MM = new java.util.HashMap<>(Map.ofEntries(
        // Volvo
        Map.entry("Volvo EX30",              821),
        Map.entry("Volvo EX40",              917),
        Map.entry("Volvo C40",               917),
        Map.entry("Volvo XC40",              917),
        Map.entry("Volvo EX60",              950),
        Map.entry("Volvo EX90",              926),
        Map.entry("Volvo ES90",              980),  // ~
        Map.entry("Volvo XC60",              975),  // ~
        Map.entry("Volvo XC90",             1000),  // ~
        Map.entry("Volvo S60",               940),  // ~
        Map.entry("Volvo V60",               940),  // ~
        // Tesla
        Map.entry("Tesla Model 3",           894),
        Map.entry("Tesla Model Y",          1029),
        Map.entry("Tesla Model S",           975),
        // Volkswagen
        Map.entry("Volkswagen ID.3",         879),
        Map.entry("Volkswagen ID.4",         954),
        Map.entry("Volkswagen ID.5",         950),
        Map.entry("Volkswagen ID.7",        1000),
        Map.entry("Volkswagen ID.Buzz",     1100),
        // Hyundai
        Map.entry("Hyundai IONIQ 5",        1001),
        Map.entry("Hyundai IONIQ 6",         985),
        Map.entry("Hyundai IONIQ 9",        1100),  // ~
        Map.entry("Hyundai INSTER",          700),  // ~
        Map.entry("Hyundai Kona",            820),
        Map.entry("Hyundai Kona Electric",   820),
        // Kia
        Map.entry("Kia EV6",               1006),
        Map.entry("Kia EV3",                820),
        Map.entry("Kia EV9",               1087),
        Map.entry("Kia Niro EV",             937),
        Map.entry("Kia Niro",               937),
        // Polestar
        Map.entry("Polestar 2",             862),
        Map.entry("Polestar 3",             950),
        Map.entry("Polestar 4",             910),
        // BMW
        Map.entry("BMW i4",                 944),
        Map.entry("BMW i5",                 980),
        Map.entry("BMW i7",               1040),  // ~
        Map.entry("BMW iX1",                910),
        Map.entry("BMW iX2",                885),  // ~
        Map.entry("BMW iX3",                920),
        Map.entry("BMW iX",                 988),
        // Audi
        Map.entry("Audi Q4 e-tron",         984),
        Map.entry("Audi Q6 e-tron",         990),
        Map.entry("Audi Q8 e-tron",         960),
        Map.entry("Audi A6 e-tron",         990),  // ~
        Map.entry("Audi e-tron GT",         875),  // ~
        // Mercedes
        Map.entry("Mercedes EQA",           693),
        Map.entry("Mercedes EQB",           968),
        Map.entry("Mercedes EQC",           880),
        Map.entry("Mercedes EQE",          1050),
        Map.entry("Mercedes EQS",          1050),
        Map.entry("Mercedes CLA",           870),  // ~
        Map.entry("Mercedes G 580",         900),  // ~
        // Skoda
        Map.entry("Skoda Enyaq",            945),
        Map.entry("Skoda Elroq",            900),
        Map.entry("Skoda Epiq",             820),  // ~
        // Cupra / Seat
        Map.entry("Cupra Born",             879),
        Map.entry("Cupra Tavascan",         900),  // ~
        Map.entry("Cupra Raval",            720),  // ~
        // MG
        Map.entry("MG4",                    870),
        Map.entry("MG ZS EV",              920),
        Map.entry("MG ZS",                  920),
        Map.entry("MG5",                    870),
        // BYD
        Map.entry("BYD Atto 3",             880),
        Map.entry("BYD ATTO 2",             760),  // ~
        Map.entry("BYD Dolphin",            810),
        Map.entry("BYD Seal",               900),  // ~
        Map.entry("BYD TANG",              1000),  // ~
        // Dacia
        Map.entry("Dacia Spring",           843),
        // Fiat
        Map.entry("Fiat 500e",              702),
        Map.entry("Fiat 600e",              780),  // ~
        Map.entry("Fiat Grande Panda",      800),  // ~
        // Abarth
        Map.entry("Abarth 500e",            702),  // same as Fiat 500e
        Map.entry("Abarth 600e",            780),  // same as Fiat 600e
        // Renault
        Map.entry("Renault Zoe",            740),
        Map.entry("Renault Megane E-Tech",  835),
        Map.entry("Renault 5 E-Tech",       770),  // ~
        Map.entry("Renault 4 E-Tech",       850),  // ~
        Map.entry("Renault Scenic E-Tech",  950),  // ~
        Map.entry("Renault Twingo E-Tech",  650),  // ~
        // Nissan
        Map.entry("Nissan Leaf",            808),
        Map.entry("Nissan Ariya",           940),
        Map.entry("Nissan Micra",           750),  // ~
        // Toyota / Subaru
        Map.entry("Toyota bZ4X",            897),
        Map.entry("Toyota C-HR",            870),  // ~
        Map.entry("Toyota Urban Cruiser",   870),  // ~ (same platform as Suzuki e VITARA)
        Map.entry("Subaru Solterra",        942),  // ~ (same platform as bZ4X)
        // Ford
        Map.entry("Ford Mustang Mach-E",    975),
        Map.entry("Ford Capri",             940),  // ~
        Map.entry("Ford Explorer",         1000),  // ~
        Map.entry("Ford Puma Gen-E",        820),  // ~
        // Peugeot
        Map.entry("Peugeot e-208",          762),  // ~
        Map.entry("Peugeot e-2008",         820),  // ~
        Map.entry("Peugeot e-308",          870),  // ~
        Map.entry("Peugeot e-3008",         905),  // ~
        Map.entry("Peugeot e-5008",         960),  // ~
        // Opel
        Map.entry("Opel Corsa Electric",    780),  // ~
        Map.entry("Opel Mokka Electric",    800),  // ~
        Map.entry("Opel Astra Electric",    866),  // ~
        Map.entry("Opel Frontera Electric", 850),  // ~
        Map.entry("Opel Grandland Electric",940),  // ~
        // Citroën
        Map.entry("Citroen e-C3",           750),  // ~
        Map.entry("Citroen e-C4",           867),  // ~
        // DS
        Map.entry("DS 3 E-Tense",           790),  // ~
        Map.entry("DS 4 E-Tense",           880),  // ~
        Map.entry("DS 7 E-Tense",           950),  // ~
        // Mini
        Map.entry("Mini Cooper E",          720),  // ~
        Map.entry("Mini Cooper SE",         720),  // ~
        Map.entry("Mini Aceman",            780),  // ~
        Map.entry("Mini Countryman E",      900),  // ~
        // Porsche
        Map.entry("Porsche Taycan",         875),  // ~
        Map.entry("Porsche Macan Electric", 900),  // ~
        Map.entry("Porsche Cayenne Electric",980), // ~
        // Genesis
        Map.entry("Genesis GV60",           950),  // ~
        Map.entry("Genesis GV70",           960),  // ~
        Map.entry("Genesis G80",           1000),  // ~
        // Smart
        Map.entry("Smart 1",                875),  // ~
        Map.entry("Smart 3",                820),  // ~
        Map.entry("Smart 5",                940),  // ~
        // Alfa Romeo / Lancia
        Map.entry("Alfa Romeo Junior",      850),  // ~
        Map.entry("Lancia Ypsilon",         750),  // ~
        // Jeep
        Map.entry("Jeep Avenger Electric",  800),  // ~
        // Honda
        Map.entry("Honda eNy1",             880),  // ~
        // Lexus
        Map.entry("Lexus RZ",               950),  // ~
        // Leapmotor
        Map.entry("Leapmotor C10",          920),  // ~
        // NIO
        Map.entry("NIO ET5",                910),  // ~
        Map.entry("NIO EL6",                950),  // ~
        // Zeekr
        Map.entry("Zeekr 001",             1000),  // ~
        Map.entry("Zeekr 7X",              960),   // ~
        // Alpine
        Map.entry("Alpine A290",            730),  // ~
        // Mazda
        Map.entry("Mazda 6e",               970),  // ~
        // Xpeng
        Map.entry("Xpeng G6",              950),
        Map.entry("Xpeng G9",              960)    // ~
    ));

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
