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

    /** Alla kända bilnamn i ev_spec — används av GroqServices modellhallucinationsvakt. */
    public List<String> findAllCarNames() {
        return repo.findAllCarNames();
    }

    public EvSpecDto formatForTitle(String title, int kmPerYear) {
        if (title == null) return null;
        String cleaned = normalize(title
                .replaceAll("\\s*\\(?\\d{4}\\)?\\s*$", "")   // strip year
                .replaceAll("(?i)\\bElectric\\b", "")         // "MG4 Electric" → "MG4"
                .replaceAll("(?i)\\be-(?=[A-Za-z])", "")      // "e-Niro" → "Niro", "e-C3" → "C3"
                .trim());
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

        if (match == null) return null;
        String chemistry = null;
        try { chemistry = getBatteryChemistry(title); } catch (Exception ignored) {}
        return toDto(match, kmPerYear, chemistry);
    }

    private EvSpecDto toDto(EvSpec spec, int kmPerYear, String chemistry) {
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
        return new EvSpecDto(wltp, summer, winter, days, daysLabel, bat, maxDc, maxAc, price, valueLabel, carType, chemistry);
    }

    // Battery chemistry per model. LFP: kan laddas till 100% dagligen utan slitage,
    // tåligare vid kyla. NMC: högre energitäthet, mer räckvidd per kg.
    // Format: "LFP", "NMC", "LFP/NMC" (beroende på variant)
    private static final Map<String, String> BATTERY_CHEMISTRY = new java.util.HashMap<>(Map.ofEntries(
        // Volvo — EX30 SM = LFP, alla övriga = NMC
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
        Map.entry("Volvo ES90",                            "NMC"),
        Map.entry("Volvo XC60",                            "NMC"),
        Map.entry("Volvo XC90",                            "NMC"),
        Map.entry("Volvo S60",                             "NMC"),
        Map.entry("Volvo V60",                             "NMC"),
        // Tesla — SR/RWD = LFP, LR/Performance = NMC
        Map.entry("Tesla Model 3",                         "LFP/NMC"),
        Map.entry("Tesla Model Y",                         "LFP/NMC"),
        Map.entry("Tesla Model S",                         "NMC"),
        Map.entry("Tesla Model X",                         "NMC"),
        // Volkswagen-koncernen
        Map.entry("Volkswagen ID.3",                       "NMC"),
        Map.entry("Volkswagen ID.4",                       "NMC"),
        Map.entry("Volkswagen ID.5",                       "NMC"),
        Map.entry("Volkswagen ID.7",                       "NMC"),
        Map.entry("Volkswagen ID.Buzz",                    "NMC"),
        Map.entry("Skoda Enyaq",                           "NMC"),
        Map.entry("Skoda Elroq",                           "NMC"),
        Map.entry("Skoda Epiq",                            "NMC"),
        Map.entry("Cupra Born",                            "NMC"),
        Map.entry("Cupra Tavascan",                        "NMC"),
        Map.entry("Cupra Raval",                           "NMC"),
        Map.entry("Audi Q4 e-tron",                        "NMC"),
        Map.entry("Audi Q6 e-tron",                        "NMC"),
        Map.entry("Audi Q8 e-tron",                        "NMC"),
        Map.entry("Audi A6 e-tron",                        "NMC"),
        Map.entry("Audi e-tron GT",                        "NMC"),
        Map.entry("Porsche Taycan",                        "NMC"),
        Map.entry("Porsche Macan Electric",                "NMC"),
        Map.entry("Porsche Cayenne Electric",              "NMC"),
        // Hyundai / Kia — EV3 SR = LFP, övriga = NMC
        Map.entry("Hyundai IONIQ 5",                       "NMC"),
        Map.entry("Hyundai IONIQ 6",                       "NMC"),
        Map.entry("Hyundai IONIQ 9",                       "NMC"),
        Map.entry("Hyundai INSTER Standard Range",         "LFP"),
        Map.entry("Hyundai INSTER Long Range",             "NMC"),
        Map.entry("Hyundai INSTER",                        "LFP/NMC"),
        Map.entry("Hyundai Kona Electric",                 "NMC"),
        Map.entry("Hyundai Kona",                          "NMC"),
        Map.entry("Kia EV6",                               "NMC"),
        Map.entry("Kia EV3 Standard Range",                "LFP"),
        Map.entry("Kia EV3 Long Range",                    "NMC"),
        Map.entry("Kia EV3",                               "LFP/NMC"),
        Map.entry("Kia EV4",                               "NMC"),
        Map.entry("Kia EV9",                               "NMC"),
        Map.entry("Kia Niro EV",                           "NMC"),
        Map.entry("Genesis GV60",                          "NMC"),
        Map.entry("Genesis GV70",                          "NMC"),
        Map.entry("Genesis G80",                           "NMC"),
        // Polestar
        Map.entry("Polestar 2",                            "NMC"),
        Map.entry("Polestar 3",                            "NMC"),
        Map.entry("Polestar 4",                            "NMC"),
        Map.entry("Polestar 5",                            "NMC"),
        // BMW-koncernen
        Map.entry("BMW i4",                                "NMC"),
        Map.entry("BMW i5",                                "NMC"),
        Map.entry("BMW i7",                                "NMC"),
        Map.entry("BMW iX1",                               "NMC"),
        Map.entry("BMW iX2",                               "NMC"),
        Map.entry("BMW iX3",                               "NMC"),
        Map.entry("BMW iX",                                "NMC"),
        Map.entry("Mini Cooper E",                         "NMC"),
        Map.entry("Mini Cooper SE",                        "NMC"),
        Map.entry("Mini Aceman",                           "NMC"),
        Map.entry("Mini Countryman E",                     "NMC"),
        // Mercedes
        Map.entry("Mercedes EQA",                          "NMC"),
        Map.entry("Mercedes EQB",                          "NMC"),
        Map.entry("Mercedes EQC",                          "NMC"),
        Map.entry("Mercedes EQE",                          "NMC"),
        Map.entry("Mercedes EQS",                          "NMC"),
        Map.entry("Mercedes CLA",                          "NMC"),
        Map.entry("Mercedes G 580",                        "NMC"),
        // Stellantis (Peugeot / Opel / Citroën / Fiat / DS / Alfa / Lancia / Jeep)
        Map.entry("Peugeot e-208",                         "NMC"),
        Map.entry("Peugeot e-2008",                        "NMC"),
        Map.entry("Peugeot e-308",                         "NMC"),
        Map.entry("Peugeot e-3008",                        "NMC"),
        Map.entry("Peugeot e-5008",                        "NMC"),
        Map.entry("Opel Corsa Electric",                   "NMC"),
        Map.entry("Opel Mokka Electric",                   "NMC"),
        Map.entry("Opel Astra Electric",                   "NMC"),
        Map.entry("Opel Frontera Electric",                "LFP/NMC"),
        Map.entry("Opel Grandland Electric",               "NMC"),
        Map.entry("Citroen e-C3",                          "LFP"),
        Map.entry("Citroen e-C4",                          "NMC"),
        Map.entry("Fiat 500e",                             "NMC"),
        Map.entry("Fiat 600e",                             "NMC"),
        Map.entry("Fiat Grande Panda",                     "LFP/NMC"),
        Map.entry("Abarth 500e",                           "NMC"),
        Map.entry("Abarth 600e",                           "NMC"),
        Map.entry("DS 3 E-Tense",                          "NMC"),
        Map.entry("Alfa Romeo Junior",                     "NMC"),
        Map.entry("Lancia Ypsilon",                        "NMC"),
        Map.entry("Jeep Avenger Electric",                 "NMC"),
        // Renault / Nissan / Alpine / Mitsubishi
        Map.entry("Renault Zoe",                           "NMC"),
        Map.entry("Renault Megane E-Tech",                 "NMC"),
        Map.entry("Renault 5 E-Tech",                      "NMC"),
        Map.entry("Renault 4 E-Tech",                      "NMC"),
        Map.entry("Renault Scenic E-Tech",                 "NMC"),
        Map.entry("Renault Twingo E-Tech",                 "NMC"),
        Map.entry("Nissan Leaf",                           "NMC"),
        Map.entry("Nissan Ariya",                          "NMC"),
        Map.entry("Nissan Micra",                          "NMC"),
        Map.entry("Alpine A290",                           "NMC"),
        // Toyota / Subaru / Lexus
        Map.entry("Toyota bZ4X",                           "NMC"),
        Map.entry("Toyota C-HR",                           "NMC"),
        Map.entry("Toyota Urban Cruiser",                  "LFP/NMC"),
        Map.entry("Subaru Solterra",                       "NMC"),
        Map.entry("Lexus RZ",                              "NMC"),
        // Ford
        Map.entry("Ford Mustang Mach-E",                   "NMC"),
        Map.entry("Ford Puma Gen-E",                       "LFP"),
        Map.entry("Ford Capri",                            "NMC"),
        Map.entry("Ford Explorer",                         "NMC"),
        // BYD — alla använder Blade Battery (LFP); Seal Performance = NMC
        Map.entry("BYD Dolphin",                           "LFP"),
        Map.entry("BYD Atto 3",                            "LFP"),
        Map.entry("BYD ATTO 2",                            "LFP"),
        Map.entry("BYD Seal",                              "LFP/NMC"),
        Map.entry("BYD TANG",                              "LFP"),
        // Dacia
        Map.entry("Dacia Spring",                          "LFP"),
        // MG
        Map.entry("MG4 Standard Range",                    "LFP"),
        Map.entry("MG4 Standard",                          "LFP"),
        Map.entry("MG4",                                   "LFP/NMC"),
        Map.entry("MG ZS EV",                              "NMC"),
        Map.entry("MG Marvel R",                           "NMC"),
        // Smart (Geely/Mercedes JV)
        Map.entry("Smart 1",                               "NMC"),
        Map.entry("Smart 3",                               "NMC"),
        Map.entry("Smart 5",                               "NMC"),
        // Honda
        Map.entry("Honda eNy1",                            "NMC"),
        // Leapmotor — all LFP
        Map.entry("Leapmotor C10",                         "LFP"),
        Map.entry("Leapmotor T03",                         "LFP"),
        // NIO
        Map.entry("NIO ET5",                               "NMC"),
        Map.entry("NIO EL6",                               "NMC"),
        // Xpeng / Zeekr
        Map.entry("Xpeng G6",                              "NMC"),
        Map.entry("Xpeng G9",                              "NMC"),
        Map.entry("Zeekr 001",                             "NMC"),
        Map.entry("Zeekr 7X",                              "NMC"),
        // Mazda
        Map.entry("Mazda 6e",                              "NMC")
    ));

    private static final java.util.Set<String> PRICE_STRIP_WORDS = java.util.Set.of(
        "standard", "extended", "long", "short", "single", "twin", "dual",
        "range", "motor", "performance", "plus", "pro", "max", "rwd", "awd",
        "quattro", "xpower", "sport", "premium", "comfort", "launch", "edition",
        "gt", "rs", "cross", "country"
    );

    private static String baseModelName(String carName) {
        String base = java.util.Arrays.stream(carName.split("\\s+"))
            .filter(w -> !PRICE_STRIP_WORDS.contains(w.toLowerCase()))
            .collect(java.util.stream.Collectors.joining(" "))
            .trim();
        return base.isBlank() ? carName : base;
    }

    public String buildPriceReferenceContext() {
        java.util.Map<String, Integer> minPrices = new java.util.TreeMap<>();
        repo.findAll().stream()
            .filter(ev -> ev.getPriceKr() != null && ev.getPriceKr() > 50_000)
            .forEach(ev -> minPrices.merge(baseModelName(ev.getCarName()), ev.getPriceKr(), Math::min));
        // Only keep brands actually sold on the Swedish/European market
        java.util.Set<String> knownBrands = java.util.Set.of(
            "Audi","BMW","BYD","Citroën","Cupra","Dacia","Fiat","Ford","Honda","Hyundai",
            "Kia","Leapmotor","MG","Mazda","Mercedes","Mini","Nissan","Opel","Peugeot",
            "Renault","Seat","Škoda","Smart","Tesla","Toyota","Volkswagen","Volvo","Xpeng","Zeekr"
        );
        if (minPrices.isEmpty()) return "";
        String prices = minPrices.entrySet().stream()
            .filter(e -> knownBrands.stream().anyMatch(b -> e.getKey().startsWith(b)))
            .sorted(java.util.Map.Entry.comparingByValue())
            .limit(25)
            .map(e -> e.getKey() + " fr. " + formatSek(e.getValue()))
            .collect(java.util.stream.Collectors.joining(", "));
        String valuePicks = buildValueRangeLine();
        return "EV-referenspriser (fr.pris från databas): " + prices
            + (valuePicks.isEmpty() ? "" : "\n" + valuePicks);
    }

    /** Etablerade märken för prisvärd räckvidd-listan — inga okända kinesiska utmanarmärken. */
    private static final java.util.Set<String> VALUE_PICK_BRANDS = java.util.Set.of(
        "Audi","BMW","Citroën","Cupra","Dacia","Fiat","Ford","Honda","Hyundai",
        "Kia","MG","Mazda","Mercedes","Mini","Nissan","Opel","Peugeot",
        "Renault","Seat","Škoda","Smart","Tesla","Toyota","Volkswagen","Volvo"
    );

    /**
     * Rankar mest räckvidd per krona (nypris, minst 400 km WLTP) bland etablerade märken —
     * statistiken som lyfter t.ex. Kia EV3 och Volvo EX30 som prisvärda förslag. 400 km-golvet
     * hindrar billiga småbilar (Zoe, Spring) från att dominera listan.
     */
    String buildValueRangeLine() {
        record Pick(String name, int rangeKm, int priceKr) {
            double kmPerKrona() { return rangeKm / (double) priceKr; }
        }
        java.util.Map<String, Pick> best = new java.util.HashMap<>();
        repo.findAll().stream()
            .filter(ev -> ev.getPriceKr() != null && ev.getPriceKr() > 50_000
                    && ev.getRangeKm() != null && ev.getRangeKm() >= 400)
            .filter(ev -> VALUE_PICK_BRANDS.stream().anyMatch(b -> ev.getCarName().startsWith(b)))
            .forEach(ev -> {
                Pick p = new Pick(baseModelName(ev.getCarName()), ev.getRangeKm(), ev.getPriceKr());
                best.merge(p.name(), p, (a, b) -> a.kmPerKrona() >= b.kmPerKrona() ? a : b);
            });
        if (best.isEmpty()) return "";
        return "PRISVÄRD RÄCKVIDD (mest km räckvidd per krona i nypris, minst 400 km, etablerade märken): "
            + best.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Pick::kmPerKrona).reversed())
                .limit(8)
                .map(p -> p.name() + " (" + p.rangeKm() + " km, fr. " + formatSek(p.priceKr()) + " kr)")
                .collect(java.util.stream.Collectors.joining(", "))
            + " — lyft gärna dessa som prisvärda förslag när de passar profilen.";
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

    public String getBatteryChemistry(String title) {
        if (title == null) return null;
        String cleaned = normalize(title
                .replaceAll("\\s*\\(?\\d{4}\\)?\\s*$", "")
                .replaceAll("(?i)\\bElectric\\b", "")
                .replaceAll("(?i)\\be-(?=[A-Za-z])", "")
                .trim());
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
