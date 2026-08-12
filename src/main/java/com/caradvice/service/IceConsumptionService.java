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

    /**
     * Distinkta "märke modellord"-kombinationer (t.ex. "Volvo V60", "Škoda Octavia") ur
     * ice_consumption — kompletterar cargo_spec/ev_spec-whitelisten med rena ICE-modeller
     * för GroqServices modellhallucinationsvakt.
     */
    public java.util.Set<String> allModelNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Variant v : findAll()) {
            names.add(v.brand() + " " + modelWord(v));
        }
        return names;
    }

    /**
     * Modellordet i variant-strängen — hoppar över en inledande upprepning av märkesnamnet
     * (t.ex. "Mazda 3 2.0 Skyactiv-X 186 hk" → "3", inte "mazda"). Utan detta matchar en rad
     * som "Mazda 3 ..." ALLA Mazda-titlar (modellordet "mazda" finns per definition redan i
     * titeln via märkeskontrollen) — skarpt fall: Mazda CX-5 fick Mazda 3:ans Skyactiv-X-motor
     * i engineOptions. Samma mönster för DS (alla rader) och MG ZS/HS.
     */
    private static String modelWord(Variant v) {
        String[] words = normalize(v.variant()).split("\\s+");
        if (words.length > 1 && words[0].equals(normalize(v.brand()))) return words[1];
        return words[0];
    }

    /**
     * Titeln uppdelad i hela ord — modellordet måste matcha ETT av dessa exakt, inte bara
     * förekomma nånstans i titelsträngen. Utan detta ger korta/numeriska modellord falska
     * träffar: "3" (Mazda 3) är en substräng av "cx-30" (Mazda CX-30), "6" (Mazda 6) av
     * "cx-60" — en CX-30-titel skulle annars kunna få Mazda 3:ans motor och tvärtom.
     */
    private static java.util.Set<String> titleTokens(String t) {
        return new java.util.HashSet<>(java.util.Arrays.asList(t.split("\\s+")));
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
        String t = normalize(CarTitle.stripYear(title));
        java.util.Set<String> tokens = titleTokens(t);

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            if (tokens.contains(modelWord(v))) candidates.add(v);
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

    /**
     * Samtliga verifierade motoralternativ för en modell, som elbilskorten redan visar.
     *
     * <p>Tabellen bär 957 varianter fördelat på 304 modeller, och 197 av dem har fler än en —
     * Volvo XC60 finns som B5, B6, D4, D5, T6 PHEV och T8 PHEV. Kortet visade ändå bara EN,
     * vald av {@link #consumptionForTitle} som den hästkraftsnärmaste, så resten av utbudet var
     * osynligt trots att det låg i databasen.
     *
     * <p>Drivmedelsfiltret är avsiktligt INTE med: motoralternativ är just vad bilen går att få
     * med, och en köpare som tittar på en bensinbil har nytta av att se att samma modell finns
     * som diesel och laddhybrid. Förbrukningssiffran, som ska gälla den bil kortet visar, väljs
     * fortfarande med drivmedelsfilter i {@code consumptionForTitle}.
     *
     * <p>Sorterat på effekt, svagast först — samma ordning som en prislista. Dubbletter på
     * beteckning slås ihop: CSV:n bär samma motor för flera årsmodeller.
     *
     * @return null när modellen saknas i tabellen, så anroparen kan behålla AI:ns egen text
     */
    public String engineOptionsForTitle(String title) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(CarTitle.stripYear(title));
        java.util.Set<String> tokens = titleTokens(t);

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            if (tokens.contains(modelWord(v))) candidates.add(v);
        }
        if (candidates.isEmpty()) return null;

        List<String> namn = candidates.stream()
                .sorted(Comparator.comparingInt(v -> {
                    Integer hp = parseHp(v.variant());
                    return hp == null ? Integer.MAX_VALUE : hp;
                }))
                .map(IceConsumptionService::engineDescriptor)
                .distinct()
                .toList();

        return namn.isEmpty() ? null : String.join(" · ", namn);
    }

    /** Kompakt förbrukningsrad för jämförelseprompten: median per drivmedel för modellen. */
    public String consumptionSummaryForTitle(String title) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(CarTitle.stripYear(title));
        java.util.Set<String> tokens = titleTokens(t);

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            if (tokens.contains(modelWord(v))) candidates.add(v);
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

    /**
     * Motorbeteckningen utan modellnamnet i fronten — för att visa som engineOptions utan att
     * upprepa bilens modell (som redan står i titeln på kortet). "CX-5 2.0 Skyactiv-G 165 hk"
     * → "2.0 Skyactiv-G 165 hk"; "Mazda 3 2.0 Skyactiv-X 186 hk" → "2.0 Skyactiv-X 186 hk"
     * (hoppar även över märkesupprepningen, se modelWord()).
     */
    static String engineDescriptor(Variant v) {
        String[] words = v.variant().split("\\s+");
        int skip = (words.length > 1 && words[0].equalsIgnoreCase(v.brand())) ? 2 : 1;
        if (words.length <= skip) return v.variant();
        return String.join(" ", java.util.Arrays.asList(words).subList(skip, words.length));
    }

    /** Samma blankstegsstädning som EvSpecService.normalize — se motiveringen där. */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("š", "s").replace("ë", "e").replace("é", "e")
                .replaceAll("\\p{Cf}", "")
                .replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
}
