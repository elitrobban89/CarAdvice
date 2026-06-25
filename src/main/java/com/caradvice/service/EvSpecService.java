package com.caradvice.service;

import com.caradvice.model.EvSpec;
import com.caradvice.model.EvSpecDto;
import com.caradvice.repository.EvSpecRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;

@Service
public class EvSpecService {

    private final EvSpecRepository repo;

    public EvSpecService(EvSpecRepository repo) {
        this.repo = repo;
    }

    public EvSpecDto formatForTitle(String title, int kmPerYear) {
        if (title == null) return null;
        String cleaned = normalize(title.replaceAll("\\s*\\(?\\d{4}\\)?\\s*$", "").trim());
        String[] titleWords = cleaned.split("\\s+");
        java.util.Set<String> titleSet = new java.util.HashSet<>(java.util.Arrays.asList(titleWords));

        List<EvSpec> all = repo.findAll();

        // Pass 1: all title words are contained in stored name as substrings
        EvSpec match = all.stream()
                .filter(ev -> {
                    String name = normalize(ev.getCarName());
                    for (String w : titleWords) if (!name.contains(w)) return false;
                    return true;
                })
                .findFirst().orElse(null);

        // Pass 2: all stored-name words are exact words in the title
        // e.g. "Tesla Model 3" matches "Tesla Model 3 Long Range"
        // Uses word-set so "ev" does NOT match "phev"
        if (match == null) {
            match = all.stream()
                    .filter(ev -> {
                        String[] nameWords = normalize(ev.getCarName()).split("\\s+");
                        for (String w : nameWords) if (!titleSet.contains(w)) return false;
                        return true;
                    })
                    .max(java.util.Comparator.comparingInt(ev ->
                            normalize(ev.getCarName()).split("\\s+").length))
                    .orElse(null);
        }

        // Pass 3: all title words appear as words in the stored name
        // Handles "MG4" matching "MG4 Long Range", "Volvo EX30" matching "Volvo EX30 Single Motor"
        if (match == null) {
            match = all.stream()
                    .filter(ev -> {
                        java.util.Set<String> nameSet = new java.util.HashSet<>(
                                java.util.Arrays.asList(normalize(ev.getCarName()).split("\\s+")));
                        for (String w : titleWords) if (!nameSet.contains(w)) return false;
                        return true;
                    })
                    .max(java.util.Comparator.comparingInt(ev ->
                            normalize(ev.getCarName()).split("\\s+").length))
                    .orElse(null);
        }

        return match == null ? null : toDto(match, kmPerYear);
    }

    private EvSpecDto toDto(EvSpec spec, int kmPerYear) {
        int wltp   = spec.getRangeKm() != null ? spec.getRangeKm() : 0;
        int summer = (int) (wltp * 0.85);
        int winter = (int) (wltp * 0.70);

        int days = 0;
        String daysLabel = "";
        if (kmPerYear > 0 && summer > 0) {
            double daily = kmPerYear / 365.0;
            days = (int) Math.max(1, Math.round(summer / daily));
            daysLabel = days == 1 ? "ladda varje dag"
                      : days == 2 ? "ladda varannan dag"
                      : "ladda var " + days + ":e dag";
        }

        int maxDc  = spec.getMaxDcKw()    != null ? (int) spec.getMaxDcKw().doubleValue()    : 0;
        int maxAc  = spec.getMaxAcKw()    != null ? (int) spec.getMaxAcKw().doubleValue()     : 0;
        double bat = spec.getBatteryKwh() != null ? spec.getBatteryKwh()                      : 0.0;
        int price  = spec.getPriceKr()    != null ? spec.getPriceKr()                         : 0;

        String valueLabel = "";
        if (price > 0 && wltp > 0) {
            double priceUnit  = price / 100_000.0;                          // units of 100k kr
            double rangeScore = wltp / priceUnit;                           // km per 100k kr
            double batScore   = bat > 0 ? (bat / priceUnit) * 4.0 : 0;     // kWh per 100k kr, weighted
            double dcBonus    = maxDc >= 150 ? 20 : maxDc >= 100 ? 12 : maxDc >= 50 ? 5 : 0;
            double score      = rangeScore * 0.6 + batScore + dcBonus;
            valueLabel = score > 145 ? "Utmärkt prisvärdhet"
                       : score > 110 ? "Bra prisvärdhet"
                       : score > 80  ? "Ok prisvärdhet"
                       : "";
        }

        String carType = spec.getCarType() != null ? spec.getCarType() : "EV";
        return new EvSpecDto(wltp, summer, winter, days, daysLabel, bat, maxDc, maxAc, price, valueLabel, carType);
    }

    // Battery chemistry per model. LFP: kan laddas till 100% dagligen utan slitage,
    // tåligare vid kyla. NMC: högre energitäthet, mer räckvidd per kg.
    // Format: "LFP", "NMC", "LFP/NMC" (beroende på variant)
    private static final Map<String, String> BATTERY_CHEMISTRY = Map.ofEntries(
        Map.entry("Dacia Spring",                          "LFP"),
        Map.entry("BYD Dolphin",                           "LFP"),
        Map.entry("BYD Atto 3",                            "LFP"),
        Map.entry("MG4 Standard Range",                    "LFP"),
        Map.entry("MG4 Standard",                          "LFP"),
        Map.entry("Volvo EX30 Single Motor Extended Range","NMC"),
        Map.entry("Volvo EX30 Twin Motor Performance",     "NMC"),
        Map.entry("Volvo EX30 Cross Country",              "NMC"),
        Map.entry("Volvo EX30 Single Motor",               "LFP"),
        Map.entry("Volvo EX30",                            "LFP/NMC"),
        Map.entry("Volvo EX40",                            "NMC"),
        Map.entry("Volvo XC40 Recharge",                   "NMC"),
        Map.entry("Volvo C40",                             "NMC"),
        Map.entry("Volvo EX60",                            "NMC"),
        Map.entry("Volvo EX90",                            "NMC"),
        Map.entry("Tesla Model 3",                         "LFP/NMC"),
        Map.entry("Tesla Model Y",                         "LFP/NMC"),
        Map.entry("Volkswagen ID.3",                       "NMC"),
        Map.entry("Volkswagen ID.4",                       "NMC"),
        Map.entry("Volkswagen ID.5",                       "NMC"),
        Map.entry("Volkswagen ID.7",                       "NMC"),
        Map.entry("Volkswagen ID.Buzz",                    "NMC"),
        Map.entry("Hyundai IONIQ 5",                       "NMC"),
        Map.entry("Hyundai IONIQ 6",                       "NMC"),
        Map.entry("Kia EV6",                               "NMC"),
        Map.entry("Kia EV3",                               "LFP"),
        Map.entry("Kia EV9",                               "NMC"),
        Map.entry("Kia Niro EV",                           "NMC"),
        Map.entry("Polestar 2",                            "NMC"),
        Map.entry("Polestar 3",                            "NMC"),
        Map.entry("Polestar 4",                            "NMC"),
        Map.entry("BMW i4",                                "NMC"),
        Map.entry("BMW i5",                                "NMC"),
        Map.entry("BMW iX1",                               "NMC"),
        Map.entry("BMW iX3",                               "NMC"),
        Map.entry("BMW iX",                                "NMC"),
        Map.entry("Audi Q4 e-tron",                        "NMC"),
        Map.entry("Audi Q6 e-tron",                        "NMC"),
        Map.entry("Mercedes EQA",                          "NMC"),
        Map.entry("Mercedes EQB",                          "NMC"),
        Map.entry("Mercedes EQC",                          "NMC"),
        Map.entry("Mercedes EQE",                          "NMC"),
        Map.entry("Mercedes EQS",                          "NMC"),
        Map.entry("Skoda Enyaq",                           "NMC"),
        Map.entry("Cupra Born",                            "NMC"),
        Map.entry("MG4",                                   "LFP/NMC"),
        Map.entry("MG ZS EV",                              "NMC"),
        Map.entry("Fiat 500e",                             "NMC"),
        Map.entry("Renault Zoe",                           "NMC"),
        Map.entry("Renault Megane E-Tech",                 "NMC"),
        Map.entry("Nissan Leaf",                           "NMC"),
        Map.entry("Nissan Ariya",                          "NMC"),
        Map.entry("Toyota bZ4X",                           "NMC"),
        Map.entry("Ford Mustang Mach-E",                   "NMC"),
        Map.entry("Hyundai Kona Electric",                 "NMC")
    );

    public String getBatteryChemistry(String title) {
        if (title == null) return null;
        String cleaned = normalize(title.replaceAll("\\s*\\(?\\d{4}\\)?\\s*$", "").trim());
        String[] cleanedWords = cleaned.split("\\s+");
        java.util.Set<String> cleanedSet = new java.util.HashSet<>(java.util.Arrays.asList(cleanedWords));

        return BATTERY_CHEMISTRY.entrySet().stream()
                .filter(e -> {
                    String[] keyWords = normalize(e.getKey()).split("\\s+");
                    for (String w : keyWords) if (!cleanedSet.contains(w)) return false;
                    return true;
                })
                .max(java.util.Comparator.comparingInt(
                        (Map.Entry<String, String> e) -> normalize(e.getKey()).split("\\s+").length))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase();
    }
}
