package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class NewCarPriceService {

    private static final Logger log = LoggerFactory.getLogger(NewCarPriceService.class);
    private static final long ROWS_TTL_MS = 30 * 60 * 1_000L;
    private final JdbcTemplate jdbc;
    private volatile List<Map<String, Object>> cachedRows;
    private volatile long rowsCachedAt;

    public NewCarPriceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTableAndSeed() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS new_car_price (
                car_name VARCHAR(200) PRIMARY KEY,
                price_kr INT NOT NULL
            )
            """);

        seedDefaults();
    }

    private void seedDefaults() {
        Object[][] data = {
            // Dacia
            { "Dacia Sandero 2015-2020",        110_000 },
            { "Dacia Sandero 2021+",            155_000 },
            { "Dacia Sandero Stepway 2021+",    175_000 },
            { "Dacia Duster 2018-2021",         215_000 },
            { "Dacia Duster 2022+",             265_000 },
            { "Dacia Jogger 2022+",             240_000 },
            // Škoda
            { "Škoda Fabia 2015-2021 (Gen3)",   140_000 },
            { "Škoda Fabia 2022+ (Gen4)",       210_000 },
            { "Škoda Kamiq 2019+",              290_000 },
            { "Škoda Scala 2020+",              250_000 },
            { "Škoda Karoq 2018+",              330_000 },
            { "Škoda Octavia 2021+",            340_000 },
            { "Škoda Kodiaq 2023+",             490_000 },
            // Volkswagen
            { "Volkswagen Polo 2018-2021",      185_000 },
            { "Volkswagen Polo 2022+",          225_000 },
            { "Volkswagen Golf 2020+",          320_000 },
            { "Volkswagen Golf 2017-2019",      260_000 },
            { "Volkswagen Tiguan 2021+",        440_000 },
            { "Volkswagen Passat 2020+",        440_000 },
            { "Volkswagen T-Roc 2020+",         360_000 },
            { "Volkswagen T-Cross 2019+",       290_000 },
            // Seat / Cupra
            { "Seat Ibiza 2018-2021",           175_000 },
            { "Seat Ibiza 2022+",               215_000 },
            { "Seat Arona 2018+",               240_000 },
            { "Seat Leon 2021+",                280_000 },
            { "Cupra Born 2022+",               380_000 },
            // Toyota
            { "Toyota Yaris 2020+",             230_000 },
            { "Toyota Aygo X 2022+",            195_000 },
            { "Toyota Yaris Cross 2021+",       285_000 },
            { "Toyota Corolla 2019+",           300_000 },
            { "Toyota C-HR 2023+",              325_000 },
            { "Toyota RAV4 2019+",              420_000 },
            // Renault
            { "Renault Clio 2020+",             220_000 },
            { "Renault Captur 2020+",           255_000 },
            { "Renault Arkana 2021+",           320_000 },
            // Hyundai
            { "Hyundai i20 2021+",              220_000 },
            { "Hyundai i30 2021+",              275_000 },
            { "Hyundai Kona 2018-2022",         250_000 },
            { "Hyundai Kona 2023+",             330_000 },
            { "Hyundai Tucson 2021+",           390_000 },
            // Kia
            { "Kia Stonic 2018+",               235_000 },
            { "Kia Ceed 2019+",                 275_000 },
            { "Kia Sportage 2022+",             380_000 },
            // Peugeot / Opel
            { "Peugeot 208 2020+",              240_000 },
            { "Peugeot 2008 2020+",             290_000 },
            { "Opel Corsa 2020+",               230_000 },
            { "Opel Astra 2022+",               310_000 },
            { "Opel Grandland 2022+",           390_000 },
            // Ford
            { "Ford Fiesta 2019-2023",          220_000 },
            { "Ford Puma 2020+",                310_000 },
            { "Ford Focus 2019+",               290_000 },
            // Honda / Mazda / Nissan
            { "Honda Jazz 2021+",               280_000 },
            { "Mazda2 2016-2022",               185_000 },
            { "Mazda3 2019+",                   310_000 },
            { "Mazda CX-30 2020+",              360_000 },
            { "Mazda CX-5 2017+",               390_000 },
            { "Nissan Micra 2017-2022",         195_000 },
            { "Nissan Qashqai 2021+",           380_000 },
            // ── PHEV / Laddhybrider ──────────────────────────────────────
            { "Kia Niro PHEV 2017-2022",         290_000 },
            { "Kia Niro PHEV 2023+",             340_000 },
            { "Hyundai Ioniq PHEV 2016-2022",    280_000 },
            { "Toyota Prius PHEV 2016-2022",     340_000 },
            { "Toyota Prius PHEV 2023+",         410_000 },
            { "Toyota RAV4 PHEV 2019+",          495_000 },
            { "Toyota C-HR PHEV 2023+",          380_000 },
            { "Toyota Yaris Cross PHEV 2021+",   380_000 },
            { "Mitsubishi Outlander PHEV 2014-2021", 430_000 },
            { "Mitsubishi Outlander PHEV 2022+", 520_000 },
            { "Volkswagen Golf GTE 2014-2020",   380_000 },
            { "Volkswagen Golf GTE 2021+",       430_000 },
            { "Volkswagen Passat GTE 2015-2022", 470_000 },
            { "Volvo XC60 PHEV 2017+",           620_000 },
            { "Volvo XC40 PHEV 2020+",           490_000 },
            { "Volvo V60 PHEV 2019+",            550_000 },
            { "BMW 330e 2019+",                  510_000 },
            { "BMW X1 PHEV 2020+",               490_000 },
            { "Ford Kuga PHEV 2020+",            430_000 },
            { "Hyundai Tucson PHEV 2021+",       440_000 },
            { "Kia Sportage PHEV 2022+",         440_000 },
            { "Renault Megane E-Tech PHEV 2020+",340_000 },
            { "Cupra Formentor PHEV 2021+",      450_000 },
            { "Skoda Octavia iV PHEV 2021+",     415_000 },
            { "Peugeot 308 PHEV 2021+",          380_000 },
            { "Opel Astra PHEV 2022+",           390_000 },
            { "Mercedes C300e 2022+",            570_000 },
            { "Mazda CX-60 PHEV 2022+",          580_000 },
            // ── Peugeot (ICE extra) ───────────────────────────────────
            { "Peugeot 3008 2021+",             400_000 },
            // Ford (extra)
            { "Ford Kuga 2020+",                380_000 },
            // Citroën
            { "Citroën C3 2017-2024",           215_000 },
            { "Citroën ë-C3 2024+",             240_000 },
            { "Citroën C3 Aircross 2018+",      260_000 },
            // Volvo (ICE/mild-hybrid)
            { "Volvo XC40 2018+",               420_000 },
            { "Volvo XC60 2018+",               560_000 },
            { "Volvo V60 2019+",                490_000 },
            { "Volvo S60 2019+",                470_000 },
            { "Volvo V90 2017+",                600_000 },
            // Kia (extra)
            { "Kia Picanto 2017+",              160_000 },
            { "Kia Rio 2017+",                  195_000 },
            // Hyundai (extra)
            { "Hyundai i10 2020+",              175_000 },
            // Audi / BMW / Mercedes (ICE)
            { "Audi A3 2021+",                  420_000 },
            { "Audi A4 2020+",                  530_000 },
            { "BMW 1-serie 2020+",              430_000 },
            { "BMW 3-serie 2019+",              530_000 },
            { "Mercedes A-klass 2019+",         370_000 },
            { "Mercedes C-klass 2022+",         560_000 },
        };

        for (Object[] row : data) {
            // Portabel "insert if missing" — H2 (lokal fallback) stöder inte Postgres ON CONFLICT
            jdbc.update("""
                INSERT INTO new_car_price(car_name, price_kr)
                SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM new_car_price WHERE car_name = ?)
                """, row[0], row[1], row[0]);
        }
    }

    /**
     * Så många nyprisrader som får följa med in i systemprompten.
     *
     * <p>Sänkt från 50 till 30 2026-08-13 som en del av promptbantningen: Groq mäter
     * {@code prompt + reserverade max_tokens} mot per-minut-taket på 8 000, och prompten låg
     * mätt i produktionsloggen på 4 644-4 765 tokens — nära nog taket att reservvägen avvisades
     * med 413. Raderna är sorterade på pris stigande, så de 20 som faller bort är de DYRASTE i
     * tabellen; de behövs minst, eftersom {@code DEPRECIATION_RULE} bara använder tabellen som
     * prisankare och de dyra bilarna sällan ryms i en begagnatbudget ändå.
     */
    private static final int MAX_PRICE_ROWS_IN_PROMPT = 30;

    public String buildPriceReferenceContext() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT car_name, price_kr FROM new_car_price ORDER BY price_kr, car_name");
        if (rows.isEmpty()) return "";
        String prices = rows.stream()
                .limit(MAX_PRICE_ROWS_IN_PROMPT)
                .map(r -> r.get("car_name") + " fr. " + formatSek(((Number) r.get("price_kr")).intValue()))
                .collect(Collectors.joining(", "));
        return "ICE-nypris Sverige (SEK): " + prices;
    }

    /**
     * Nypriset för en AI-titel, eller null när modellen inte finns i tabellen.
     *
     * <p>Tabellnamnen bär generation som årsspann ("Volkswagen Polo 2018-2021",
     * "Toyota Yaris 2020+"), så matchningen sker i två steg: modellnamnet måste vara en
     * inledning av titeln ord för ord — "Volkswagen Golf" matchar "Volkswagen Golf (2022)"
     * men inte "Volkswagen Golf GTE" mot en vanlig Golf — och årsmodellen måste rymmas i
     * generationens spann. Ord för ord, aldrig tecken: annars hade "Kia Ceed" svalt "Kia Ceed
     * SW" lika gärna som "Kia Ceedx".
     *
     * <p>Finns flera generationer av samma modell vinner den vars årsspann innehåller
     * titelns årtal; utan årtal i titeln väljs den dyraste, som är den nyaste generationen.
     */
    public Integer priceForTitle(String title) {
        if (title == null || title.isBlank()) return null;
        List<String> titleWords = words(CarTitle.stripYear(title));
        if (titleWords.isEmpty()) return null;
        Integer titleYear = CarTitle.year(title);

        Integer bastaPris = null;
        int bastaLangd = 0;
        for (Map<String, Object> row : cachedRows()) {
            String carName = String.valueOf(row.get("car_name"));
            int priceKr = ((Number) row.get("price_kr")).intValue();

            int[] span = generationSpan(carName);
            List<String> nameWords = words(carName.replaceAll("\\s+\\d{4}(-\\d{4}|\\+)?(\\s|$).*", " ")
                    .replaceAll("\\(Gen\\d\\)", "").trim());
            if (nameWords.isEmpty() || nameWords.size() > titleWords.size()) continue;
            if (!titleWords.subList(0, nameWords.size()).equals(nameWords)) continue;
            if (titleYear != null && span != null && (titleYear < span[0] || titleYear > span[1])) continue;

            // Längsta modellnamnet vinner: "Toyota RAV4 PHEV" före "Toyota RAV4"
            if (nameWords.size() > bastaLangd || (nameWords.size() == bastaLangd
                    && bastaPris != null && priceKr > bastaPris)) {
                bastaLangd = nameWords.size();
                bastaPris = priceKr;
            }
        }
        return bastaPris;
    }

    /** Generationens årsspann ur namnet: "2018-2021" → [2018, 2021], "2020+" → [2020, 9999]. */
    private static int[] generationSpan(String carName) {
        Matcher m = Pattern.compile("(\\d{4})\\s*(?:-\\s*(\\d{4})|(\\+))").matcher(carName);
        if (!m.find()) return null;
        int from = Integer.parseInt(m.group(1));
        int to = m.group(2) != null ? Integer.parseInt(m.group(2)) : 9999;
        return new int[]{from, to};
    }

    private static List<String> words(String s) {
        String rensad = s.toLowerCase().replaceAll("\\s+", " ").trim();
        return rensad.isEmpty() ? List.of() : List.of(rensad.split(" "));
    }

    private List<Map<String, Object>> cachedRows() {
        if (System.currentTimeMillis() - rowsCachedAt > ROWS_TTL_MS || cachedRows == null) {
            try {
                cachedRows = jdbc.queryForList("SELECT car_name, price_kr FROM new_car_price");
                rowsCachedAt = System.currentTimeMillis();
            } catch (Exception e) {
                return cachedRows != null ? cachedRows : List.of();
            }
        }
        return cachedRows;
    }

    public int upsert(String carName, int priceKr) {
        // Portabel upsert utan ON CONFLICT: uppdatera först, annars villkorad insert
        int updated = jdbc.update("UPDATE new_car_price SET price_kr = ? WHERE car_name = ?", priceKr, carName);
        if (updated > 0) return updated;
        return jdbc.update("""
                INSERT INTO new_car_price(car_name, price_kr)
                SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM new_car_price WHERE car_name = ?)
                """, carName, priceKr, carName);
    }

    public int delete(String carName) {
        return jdbc.update("DELETE FROM new_car_price WHERE car_name = ?", carName);
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("SELECT car_name, price_kr FROM new_car_price ORDER BY car_name");
    }

    private static String formatSek(int amount) {
        String s = String.valueOf(amount);
        StringBuilder sb = new StringBuilder();
        int start = s.length() % 3;
        if (start > 0) sb.append(s, 0, start);
        for (int i = start; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }
}
