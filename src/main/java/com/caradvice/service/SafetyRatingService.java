package com.caradvice.service;

import com.caradvice.model.SafetyRating;
import com.caradvice.repository.SafetyRatingRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SafetyRatingService {

    private final SafetyRatingRepository repo;

    public SafetyRatingService(SafetyRatingRepository repo) {
        this.repo = repo;
    }

    public String formatForTitle(String title) {
        if (title == null) return null;
        String cleaned = title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim().toLowerCase();
        List<SafetyRating> all = repo.findAll();
        return all.stream()
                .filter(sr -> sr.getCarMake() != null && sr.getCarModel() != null)
                .filter(sr -> cleaned.contains(sr.getCarMake().toLowerCase())
                           && cleaned.contains(sr.getCarModel().toLowerCase()))
                .findFirst()
                .map(this::format)
                .orElse(null);
    }

    public SafetyRating save(SafetyRating rating) {
        return repo.save(rating);
    }

    public int importCsv(String csv) {
        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("car_make")) continue;
            String[] f = parseCsvLine(line);
            if (f.length < 4) continue;
            try {
                String make = f[0];
                String model = f[1];
                Integer year  = parse(f, 2);
                Integer stars = parse(f, 3);
                Integer adult = parse(f, 4);
                Integer child = parse(f, 5);
                Integer ped   = parse(f, 6);
                Integer sa    = parse(f, 7);
                repo.save(new SafetyRating(make, model, year, stars, adult, child, ped, sa));
                count++;
            } catch (Exception ignored) {}
        }
        return count;
    }

    private String format(SafetyRating sr) {
        String stars = "★".repeat(sr.getStars()) + "☆".repeat(5 - sr.getStars());
        StringBuilder sb = new StringBuilder(stars);
        if (sr.getAdultPct() != null)      sb.append(" · ").append(sr.getAdultPct()).append("% vuxna");
        if (sr.getChildPct() != null)      sb.append(" · ").append(sr.getChildPct()).append("% barn");
        if (sr.getPedestrianPct() != null) sb.append(" · ").append(sr.getPedestrianPct()).append("% fotgängare");
        if (sr.getTestYear() != null)      sb.append(" · Euro NCAP ").append(sr.getTestYear());
        return sb.toString();
    }

    private Integer parse(String[] fields, int index) {
        if (index >= fields.length || fields[index] == null || fields[index].isBlank()) return null;
        return Integer.parseInt(fields[index].trim());
    }

    static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                String val = current.toString().trim();
                result.add(val.isEmpty() ? null : val);
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        result.add(last.isEmpty() ? null : last);
        return result.toArray(new String[0]);
    }
}
