package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifierade förbrukningssiffror för bensin-/diesel-/hybridbilar (l/mil).
 * Datat kommer från Bilresa-projektets handkodade fordonsdatabas (ice-consumption.csv,
 * ~950 motorvarianter) och används dels av GET /api/ice-consumption (Bilresas kalkylator),
 * dels för att ersätta AI:ns gissade consumptionLiterPerMil med verifierade värden.
 * Ren JdbcTemplate utan JPA-entitet — samma mönster som new_car_price (validate-fällan).
 */
@Service
public class IceConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(IceConsumptionService.class);
    private static final Pattern HP_PATTERN = Pattern.compile("(\\d{2,4})\\s*hk");

    public record Variant(String brand, String variant, String fuel, double literPerMil) {}

    private final JdbcTemplate jdbc;

    // Tabellen är statisk seed-data — cachas i minnet efter första läsningen
    private volatile List<Variant> cachedAll = null;

    public IceConsumptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTableAndSeed() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ice_consumption (
                brand VARCHAR(50) NOT NULL,
                variant VARCHAR(150) NOT NULL,
                fuel VARCHAR(20) NOT NULL,
                liter_per_mil NUMERIC(4,2) NOT NULL,
                PRIMARY KEY (brand, variant)
            )
            """);

        List<String[]> rows = readCsv();
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM ice_consumption", Integer.class);
        if (existing != null && existing >= rows.size()) return; // redan seedad

        int added = 0;
        for (String[] r : rows) {
            added += jdbc.update("""
                INSERT INTO ice_consumption(brand, variant, fuel, liter_per_mil)
                SELECT ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM ice_consumption WHERE brand = ? AND variant = ?)
                """, r[0], r[1], r[2], Double.parseDouble(r[3]), r[0], r[1]);
        }
        cachedAll = null;
        log.info("ice_consumption seedad — {} nya rader ({} i CSV)", added, rows.size());
    }

    private List<String[]> readCsv() {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("ice-consumption.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("brand;")) continue;
                String[] f = line.split(";");
                if (f.length == 4) rows.add(f);
            }
        } catch (Exception e) {
            log.error("Kunde inte läsa ice-consumption.csv: {}", e.getMessage());
        }
        return rows;
    }

    public List<Variant> findAll() {
        List<Variant> all = cachedAll;
        if (all == null) {
            all = jdbc.queryForList("SELECT brand, variant, fuel, liter_per_mil FROM ice_consumption")
                    .stream().map(r -> new Variant(
                            (String) r.get("brand"), (String) r.get("variant"),
                            (String) r.get("fuel"), ((Number) r.get("liter_per_mil")).doubleValue()))
                    .toList();
            cachedAll = all;
        }
        return all;
    }

    /** Rader för GET /api/ice-consumption — carName = "märke variant" som i /api/ev-consumption. */
    public List<Map<String, Object>> listForApi() {
        return findAll().stream()
                .map(v -> Map.<String, Object>of(
                        "carName", v.brand() + " " + v.variant(),
                        "fuel", v.fuel(),
                        "literPerMil", v.literPerMil()))
                .toList();
    }

    /**
     * Verifierad förbrukning för en rekommenderad bil, t.ex. "Volkswagen Golf (2019)".
     * Kandidater: märket förekommer i titeln OCH variantens modellord (första ordet)
     * förekommer i titeln. Vid flera kandidater väljs närmast hästkraftstal (om angivet),
     * annars medianvarianten. fuelPref ("bensin"/"diesel"/"hybrid") filtrerar om möjligt.
     */
    public Variant consumptionForTitle(String title, Integer horsepower, String fuelPref) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", ""));

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            String modelWord = normalize(v.variant()).split("\\s+")[0];
            if (t.contains(modelWord)) candidates.add(v);
        }
        if (candidates.isEmpty()) return null;

        if (fuelPref != null && !fuelPref.isBlank()) {
            String fp = fuelPref.toLowerCase(Locale.ROOT);
            List<Variant> filtered = candidates.stream().filter(v -> v.fuel().equals(fp)).toList();
            if (!filtered.isEmpty()) candidates = filtered;
        }

        if (horsepower != null && horsepower > 0) {
            return candidates.stream()
                    .min(Comparator.comparingInt(v -> {
                        Integer hp = parseHp(v.variant());
                        return hp == null ? 10_000 : Math.abs(hp - horsepower);
                    }))
                    .orElse(null);
        }
        List<Variant> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(Variant::literPerMil)).toList();
        return sorted.get(sorted.size() / 2);
    }

    /** Kompakt förbrukningsrad för jämförelseprompten: median per drivmedel för modellen. */
    public String consumptionSummaryForTitle(String title) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", ""));

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            String modelWord = normalize(v.variant()).split("\\s+")[0];
            if (t.contains(modelWord)) candidates.add(v);
        }
        if (candidates.isEmpty()) return null;

        // l/100km i prompten — AI:n svarar i den enheten i consumptionLiterPerMil (frontend-konventionen)
        StringBuilder sb = new StringBuilder();
        for (String fuel : new String[]{"bensin", "diesel", "hybrid", "laddhybrid"}) {
            List<Double> values = candidates.stream()
                    .filter(v -> v.fuel().equals(fuel))
                    .map(Variant::literPerMil).sorted().toList();
            if (values.isEmpty()) continue;
            double median = values.get(values.size() / 2);
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format(Locale.forLanguageTag("sv"), "%.1f l/100km (%s)", median * 10, fuel));
        }
        return sb.isEmpty() ? null : "förbrukning ca " + sb;
    }

    static Integer parseHp(String variant) {
        Matcher m = HP_PATTERN.matcher(variant);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("š", "s").replace("ë", "e").replace("é", "e")
                .replaceAll("\\s+", " ").trim();
    }
}
