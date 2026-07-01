package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NewCarPriceService {

    private static final Logger log = LoggerFactory.getLogger(NewCarPriceService.class);
    private final JdbcTemplate jdbc;

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
            // Peugeot (extra)
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
            jdbc.update("INSERT INTO new_car_price(car_name, price_kr) VALUES (?,?) ON CONFLICT (car_name) DO NOTHING",
                    row[0], row[1]);
        }
    }

    public String buildPriceReferenceContext() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT car_name, price_kr FROM new_car_price ORDER BY price_kr, car_name");
        if (rows.isEmpty()) return "";
        String prices = rows.stream()
                .limit(50)
                .map(r -> r.get("car_name") + " fr. " + formatSek(((Number) r.get("price_kr")).intValue()))
                .collect(Collectors.joining(", "));
        return "ICE-nypris Sverige (SEK): " + prices;
    }

    public int upsert(String carName, int priceKr) {
        return jdbc.update(
                "INSERT INTO new_car_price(car_name, price_kr) VALUES (?,?) ON CONFLICT (car_name) DO UPDATE SET price_kr = EXCLUDED.price_kr",
                carName, priceKr);
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
