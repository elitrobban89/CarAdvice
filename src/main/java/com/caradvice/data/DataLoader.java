package com.caradvice.data;

import com.caradvice.model.CargoSpec;
import com.caradvice.model.EvSpec;
import com.caradvice.model.ExpertInsight;
import com.caradvice.model.SafetyRating;
import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.repository.SafetyRatingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataLoader implements CommandLineRunner {

    private final ExpertInsightRepository expertRepo;
    private final SafetyRatingRepository safetyRepo;
    private final EvSpecRepository evSpecRepo;
    private final CargoSpecRepository cargoRepo;

    public DataLoader(ExpertInsightRepository expertRepo, SafetyRatingRepository safetyRepo,
                      EvSpecRepository evSpecRepo, CargoSpecRepository cargoRepo) {
        this.expertRepo = expertRepo;
        this.safetyRepo = safetyRepo;
        this.evSpecRepo = evSpecRepo;
        this.cargoRepo = cargoRepo;
    }

    @Override
    public void run(String... args) {
        if (expertRepo.count() == 0)  seedInsights();
        if (safetyRepo.count() == 0)  seedSafetyRatings();
        if (evSpecRepo.count() == 0)  seedEvSpecs();
        seedEvSpecExtras();
        seedCargoSpecs();
        seedSafetyExtras();
    }

    private void seedInsights() {
        expertRepo.saveAll(List.of(
            new ExpertInsight("Erik Naessén", null, null, "elbil", null,
                "Räkna alltid med 20–25% sämre räckvidd vintertid. En elbil med 500 km WLTP-räckvidd ger realistiskt 370–400 km i kyla. Köper du elbil för pendling är detta sällan ett problem, men för längre resor krävs planering.", null),
            new ExpertInsight("Erik Naessén", null, null, "elbil", null,
                "Vid begagnat elbilsköp: kontrollera alltid att batterikapaciteten är minst 80% kvar. Be säljaren om en hälsorapport eller kör bilen till en verkstad med OBD-diagnostik.", null),
            new ExpertInsight("Erik Naessén", "Tesla", "Model 3", "elbil", null,
                "Model 3 Long Range är fortfarande räckviddsreferensen i mellanklassen. Superchargernätet är oslagbart i Sverige. Minus: polariserande minimalistisk inredning och hög efterfrågan håller begagnatpriserna uppe.", 9),
            new ExpertInsight("Erik Naessén", "Volvo", "XC40 Recharge", "elbil", "suv",
                "XC40 Recharge kombinerar Volvos säkerhetsteknik med Google-baserat infotainment som faktiskt fungerar. Laddeffekt på 150 kW DC räcker. Begagnatpriserna har kommit ner till rimliga nivåer 2024–2025.", 8),
            new ExpertInsight("Erik Naessén", "Toyota", "RAV4 PHEV", "laddhybrid", "suv",
                "RAV4 PHEV är det pragmatiska laddhybridvalet med 75 km elräckvidd och Toyotas bevisade hybridsystem. Kör du 80% av sträckan inom elräckvidden sparar du rejält – men ladda varje dag.", 9),
            new ExpertInsight("Erik Naessén", null, null, null, "suv",
                "Många SUV-köpare väljer för stor bil. En kompakt SUV som XC40 eller Kia Sportage räcker utmärkt för de flesta barnfamiljer – lägre förbrukning, billigare parkering och enklare att manövrera i stan.", null),
            new ExpertInsight("Erik Naessén", null, null, "hybrid", null,
                "Mildhybrid och fullhybrid sparar bränsle i stadstrafik men inte på motorväg. Välj hybriddrift om du kör varierat i stad och land – annars är en renodlad bensinbil ofta mer kostnadseffektiv.", null),
            new ExpertInsight("Erik Naessén", null, null, "diesel", null,
                "Diesel lönar sig fortfarande vid körsträckor över 2 000 mil/år. Partikelfilter (DPF) behöver regenereringskörningar – undvik dieselbilar som körts uteslutande i stadstrafik.", null),
            new ExpertInsight("Erik Naessén", null, null, null, "ekonomibil",
                "I ekonomibilsklassen är Dacia Sandero prishärskaren och Toyota Yaris tillförlitlighetsreferensen. Undvik begagnade bilar under 60 000 km utan dokumenterad servicehistorik – det är en varningssignal.", null),
            new ExpertInsight("Erik Naessén", null, null, null, "familjebil",
                "Volvo V60 och V90 dominerar bland begagnade familjebilar i Sverige. Skoda Octavia Kombi ger mer lastutrymme per krona. Kontrollera alltid servostyrning, koppling och stötdämpare – gärna hos en oberoende verkstad.", null),
            new ExpertInsight("Erik Naessén", null, null, null, "laddhybrid",
                "En laddhybrid är bara kostnadseffektiv om du faktiskt laddar den regelbundet. Utan laddning är den tyngre än en bensinbil och drar mer. Kräv alltid att se en laddhistorik vid begagnatköp.", null),
            new ExpertInsight("Erik Naessén", null, null, null, "smaabil",
                "Toyota Aygo X och VW Polo sticker ut för tillförlitlighet i småbilsklassen. Undvik bensinsmåbilar med turbomotor under 100 000 kr begagnat – servicekostnaderna kan bli oproportionerligt höga.", null),
            new ExpertInsight("Erik Naessén", null, null, "bensin", null,
                "En bensinbil i 100–200 hk-klassen är fortfarande det enklaste alternativet för låg körsträcka. Fokusera på servicehistorik och kambältsbyte – det är de vanligaste fallgroparna vid begagnatköp.", null)
        ));
    }

    // Data sourced from euroncap.com — verify exact figures at euroncap.com
    private void seedSafetyRatings() {
        safetyRepo.saveAll(List.of(
            new SafetyRating("Tesla",      "Model 3",        2019, 5, 96, 86, 82, 98),
            new SafetyRating("Tesla",      "Model Y",        2022, 5, 97, 87, 79, 98),
            new SafetyRating("Volvo",      "XC40",           2018, 5, 97, 89, 76, 75),
            new SafetyRating("Volvo",      "XC60",           2017, 5, 97, 87, 75, 73),
            new SafetyRating("Volvo",      "V60",            2018, 5, 96, 82, 71, 80),
            new SafetyRating("Toyota",     "RAV4",           2019, 5, 97, 86, 80, 74),
            new SafetyRating("Toyota",     "Yaris",          2020, 5, 98, 87, 65, 90),
            new SafetyRating("Toyota",     "Corolla",        2019, 5, 96, 91, 82, 75),
            new SafetyRating("Volkswagen", "Golf",           2020, 5, 95, 89, 71, 87),
            new SafetyRating("Volkswagen", "ID.4",           2021, 5, 91, 89, 76, 93),
            new SafetyRating("Hyundai",    "Ioniq 5",        2021, 5, 97, 91, 80, 92),
            new SafetyRating("Hyundai",    "Tucson",         2021, 5, 97, 91, 74, 79),
            new SafetyRating("Kia",        "EV6",            2022, 5, 93, 91, 71, 90),
            new SafetyRating("Kia",        "Sportage",       2022, 5, 94, 89, 71, 85),
            new SafetyRating("Skoda",      "Octavia",        2021, 5, 98, 89, 74, 74),
            new SafetyRating("Polestar",   "2",              2020, 5, 94, 90, 77, 98),
            new SafetyRating("Nissan",     "Leaf",           2018, 5, 88, 89, 69, 60),
            new SafetyRating("MG",         "ZS",             2022, 4, 79, 84, 74, 68),
            new SafetyRating("Dacia",      "Sandero",        2021, 3, 61, 55, 61, 23),
            new SafetyRating("Ford",       "Mustang Mach-E", 2022, 5, 91, 89, 71, 91)
        ));
    }

    // Elbilsdata migrerad från Elbilsladdning-projektets CarDatabase
    private void seedEvSpecs() {
        evSpecRepo.saveAll(List.of(
            // Škoda
            new EvSpec("Škoda Elroq 85",                    11.0, 175.0,  82.0, 560, 500_000),
            new EvSpec("Škoda Enyaq iV 85",                 11.0, 175.0,  82.0, 550, 490_000),
            new EvSpec("Škoda Enyaq iV 60",                 11.0, 135.0,  58.0, 390, 420_000),
            // Volvo
            new EvSpec("Volvo EX30 Single Motor",           11.0, 153.0,  49.0, 344, 320_000),
            new EvSpec("Volvo EX30 Extended Range",         11.0, 153.0,  62.0, 480, 350_000),
            new EvSpec("Volvo EX30 Twin Motor",             11.0, 200.0,  62.0, 460, 385_000),
            new EvSpec("Volvo EX30 Cross Country",          11.0, 153.0,  62.0, 455, 395_000),
            new EvSpec("Volvo EX40 Single Motor",           11.0, 150.0,  75.0, 530, 465_000),
            new EvSpec("Volvo EX40 Twin Motor",             11.0, 150.0,  75.0, 508, 500_000),
            new EvSpec("Volvo C40 Single Motor",            11.0, 150.0,  75.0, 530, 475_000),
            new EvSpec("Volvo C40 Twin Motor",              11.0, 150.0,  75.0, 502, 515_000),
            new EvSpec("Volvo EX60",                        22.0, 250.0, 100.0, 600, 620_000),
            new EvSpec("Volvo EX90 Twin Motor",             11.0, 250.0, 111.0, 580, 890_000),
            // Tesla
            new EvSpec("Tesla Model Y",                     11.0, 250.0,  75.0, 533, 499_000),
            new EvSpec("Tesla Model 3",                     11.0, 250.0,  75.0, 566, 499_000),
            new EvSpec("Tesla Model S",                     11.0, 250.0, 100.0, 634, 1_100_000),
            // Volkswagen
            new EvSpec("Volkswagen ID.3",                   11.0, 130.0,  77.0, 550, 390_000),
            new EvSpec("Volkswagen ID.4",                   11.0, 135.0,  77.0, 527, 440_000),
            new EvSpec("Volkswagen ID.5",                   11.0, 135.0,  77.0, 490, 465_000),
            new EvSpec("Volkswagen ID.7",                   11.0, 200.0,  82.0, 640, 600_000),
            new EvSpec("Volkswagen ID.Buzz",                11.0, 200.0,  82.0, 459, 570_000),
            // BMW
            new EvSpec("BMW i4 eDrive40",                   11.0, 210.0,  84.0, 590, 660_000),
            new EvSpec("BMW i5 eDrive40",                   11.0, 205.0,  81.0, 582, 810_000),
            new EvSpec("BMW iX xDrive50",                   11.0, 200.0, 105.0, 630, 920_000),
            new EvSpec("BMW iX3",                           11.0, 150.0,  74.0, 460, 610_000),
            new EvSpec("BMW iX1",                           11.0, 130.0,  64.7, 440, 530_000),
            // Audi
            new EvSpec("Audi Q4 e-tron",                    11.0, 135.0,  77.0, 527, 490_000),
            new EvSpec("Audi Q6 e-tron",                    22.0, 270.0, 100.0, 636, 620_000),
            new EvSpec("Audi Q8 e-tron",                    22.0, 170.0,  95.0, 582, 860_000),
            // Hyundai / Kia
            new EvSpec("Hyundai IONIQ 5",                   11.0, 220.0,  77.4, 507, 480_000),
            new EvSpec("Hyundai IONIQ 6",                   11.0, 233.0,  77.4, 614, 480_000),
            new EvSpec("Kia EV6",                           11.0, 233.0,  77.4, 528, 460_000),
            new EvSpec("Kia EV9",                           11.0, 240.0,  99.8, 563, 760_000),
            new EvSpec("Kia EV3 Long Range",                11.0, 101.0,  81.4, 605, 370_000),
            new EvSpec("Kia Niro EV",                       11.0,  80.0,  64.8, 463, 390_000),
            // Polestar
            new EvSpec("Polestar 2",                        11.0, 205.0,  82.0, 560, 510_000),
            new EvSpec("Polestar 3",                        11.0, 250.0, 111.0, 560, 760_000),
            new EvSpec("Polestar 4",                        11.0, 200.0, 100.0, 620, 640_000),
            // Mercedes
            new EvSpec("Mercedes EQA 250",                  11.0, 100.0,  67.0, 428, 490_000),
            new EvSpec("Mercedes EQB",                      11.0, 100.0,  66.5, 419, 520_000),
            new EvSpec("Mercedes EQC",                      11.0, 110.0,  80.0, 415, 610_000),
            new EvSpec("Mercedes EQE 350",                  11.0, 170.0,  91.0, 660, 760_000),
            new EvSpec("Mercedes EQS 450",                  22.0, 200.0, 107.8, 784, 1_100_000),
            // Ford / BYD / MG
            new EvSpec("Ford Mustang Mach-E",               11.0, 150.0,  91.0, 540, 540_000),
            new EvSpec("BYD Atto 3",                        11.0, 100.0,  60.5, 420, 310_000),
            new EvSpec("BYD Seal",                          11.0, 150.0,  82.5, 570, 390_000),
            new EvSpec("BYD Dolphin",                        7.0,  88.0,  44.9, 427, 280_000),
            new EvSpec("MG ZS EV",                          11.0,  92.0,  72.6, 440, 300_000),
            new EvSpec("MG4 Standard Range",                11.0, 117.0,  51.0, 350, 295_000),
            new EvSpec("MG4 Long Range",                    11.0, 150.0,  64.0, 450, 335_000),
            new EvSpec("MG4 Extended Range",                11.0, 150.0,  77.0, 520, 375_000),
            new EvSpec("MG5 Long Range",                    11.0,  87.0,  61.1, 400, 355_000),
            // Övrigt
            new EvSpec("Toyota bZ4X",                       11.0, 150.0,  71.4, 452, 480_000),
            new EvSpec("Cupra Born",                        11.0, 170.0,  77.0, 550, 380_000),
            new EvSpec("Renault Megane E-Tech",             22.0, 130.0,  60.0, 450, 380_000),
            new EvSpec("Renault Zoe",                       22.0,  50.0,  50.0, 395, 270_000),
            new EvSpec("Nissan Ariya",                      22.0, 130.0,  87.0, 533, 490_000),
            new EvSpec("Nissan Leaf",                        6.6,  50.0,  40.0, 270, 290_000),
            new EvSpec("Fiat 500e",                         11.0,  85.0,  42.0, 320, 290_000),
            new EvSpec("Genesis GV60",                      11.0, 233.0,  77.4, 517, 560_000),
            new EvSpec("Xpeng G6",                          11.0, 250.0,  87.5, 570, 470_000)
        ));
    }

    // Data sourced from euroncap.com — verify exact figures at euroncap.com
    private void seedSafetyExtras() {
        java.util.Set<String> existing = safetyRepo.findAll().stream()
                .map(r -> r.getCarMake() + "|" + r.getCarModel())
                .collect(java.util.stream.Collectors.toSet());

        java.util.List<SafetyRating> extras = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, SafetyRating> add = (key, r) -> {
            if (!existing.contains(key)) extras.add(r);
        };

        add.accept("Volvo|EX30",              new SafetyRating("Volvo",      "EX30",              2023, 5, 95, 83, 67, 86));
        add.accept("Volvo|EX40",              new SafetyRating("Volvo",      "EX40",              2023, 5, 97, 88, 76, 75));
        add.accept("Volvo|C40",               new SafetyRating("Volvo",      "C40",               2023, 5, 97, 88, 76, 75));
        add.accept("Volkswagen|ID.3",         new SafetyRating("Volkswagen", "ID.3",              2020, 5, 87, 78, 64, 87));
        add.accept("Volkswagen|ID.5",         new SafetyRating("Volkswagen", "ID.5",              2021, 5, 91, 89, 76, 93));
        add.accept("Volkswagen|ID.7",         new SafetyRating("Volkswagen", "ID.7",              2023, 5, 92, 89, 77, 95));
        add.accept("Škoda|Enyaq iV",          new SafetyRating("Škoda",      "Enyaq iV",          2021, 5, 89, 90, 71, 89));
        add.accept("BMW|i4",                  new SafetyRating("BMW",        "i4",                2022, 5, 91, 87, 74, 94));
        add.accept("BMW|iX",                  new SafetyRating("BMW",        "iX",                2021, 5, 90, 87, 80, 97));
        add.accept("BMW|iX3",                 new SafetyRating("BMW",        "iX3",               2021, 5, 87, 83, 71, 90));
        add.accept("Kia|EV9",                 new SafetyRating("Kia",        "EV9",               2023, 5, 90, 84, 81, 85));
        add.accept("Kia|Niro",               new SafetyRating("Kia",        "Niro",              2022, 5, 88, 87, 71, 83));
        add.accept("Tesla|Model S",           new SafetyRating("Tesla",      "Model S",           2021, 5, 96, 91, 74, 99));
        add.accept("Renault|Megane E-Tech",   new SafetyRating("Renault",    "Megane E-Tech",     2022, 5, 95, 83, 70, 88));
        add.accept("Mercedes|EQS",            new SafetyRating("Mercedes",   "EQS",               2022, 5, 91, 91, 71, 91));
        add.accept("Mercedes|EQA",            new SafetyRating("Mercedes",   "EQA",               2021, 5, 87, 83, 67, 84));
        add.accept("Polestar|3",              new SafetyRating("Polestar",   "3",                 2023, 5, 96, 88, 78, 95));
        add.accept("Hyundai|Kona",            new SafetyRating("Hyundai",    "Kona",              2023, 5, 87, 84, 74, 79));
        add.accept("Toyota|C-HR",             new SafetyRating("Toyota",     "C-HR",              2023, 5, 92, 86, 80, 89));
        add.accept("Nissan|Qashqai",          new SafetyRating("Nissan",     "Qashqai",           2021, 5, 91, 88, 75, 92));
        add.accept("Dacia|Sandero",           new SafetyRating("Dacia",      "Sandero",           2021, 3, 61, 55, 61, 23));
        add.accept("Honda|CR-V",              new SafetyRating("Honda",      "CR-V",              2023, 5, 88, 84, 76, 91));
        add.accept("Mazda|CX-5",              new SafetyRating("Mazda",      "CX-5",              2022, 5, 87, 82, 71, 78));
        add.accept("Volkswagen|Tiguan",       new SafetyRating("Volkswagen", "Tiguan",            2021, 5, 93, 89, 72, 87));
        add.accept("Volkswagen|Passat",       new SafetyRating("Volkswagen", "Passat",            2020, 5, 92, 89, 72, 86));

        if (!extras.isEmpty()) safetyRepo.saveAll(extras);
    }

    private void seedEvSpecExtras() {
        java.util.List<String> existing = evSpecRepo.findAll().stream()
                .map(com.caradvice.model.EvSpec::getCarName).toList();

        java.util.List<EvSpec> extras = new java.util.ArrayList<>();

        // XC40 Recharge (renamed to EX40 but AI still uses old name)
        if (!existing.contains("Volvo XC40 Recharge"))
            extras.add(new EvSpec("Volvo XC40 Recharge",    11.0, 150.0, 75.0, 530, 465_000));
        if (!existing.contains("Volvo XC40 Recharge Twin"))
            extras.add(new EvSpec("Volvo XC40 Recharge Twin", 11.0, 150.0, 75.0, 424, 510_000));

        // PHEVs
        if (!existing.contains("Volvo XC60 PHEV"))
            extras.add(new EvSpec("Volvo XC60 PHEV",          7.4, 0.0, 18.8,  68, 670_000, "PHEV"));
        if (!existing.contains("Volvo XC60 T8"))
            extras.add(new EvSpec("Volvo XC60 T8",            7.4, 0.0, 18.8,  68, 670_000, "PHEV"));
        if (!existing.contains("Volvo XC90 PHEV"))
            extras.add(new EvSpec("Volvo XC90 PHEV",          7.4, 0.0, 18.8,  65, 790_000, "PHEV"));
        if (!existing.contains("Volvo XC90 T8"))
            extras.add(new EvSpec("Volvo XC90 T8",            7.4, 0.0, 18.8,  65, 790_000, "PHEV"));
        if (!existing.contains("Volvo V60 PHEV"))
            extras.add(new EvSpec("Volvo V60 PHEV",           3.7, 0.0, 11.6,  56, 510_000, "PHEV"));
        if (!existing.contains("Volvo V60 T6"))
            extras.add(new EvSpec("Volvo V60 T6",             3.7, 0.0, 11.6,  56, 510_000, "PHEV"));
        if (!existing.contains("Volvo V90 PHEV"))
            extras.add(new EvSpec("Volvo V90 PHEV",           7.4, 0.0, 18.8,  68, 690_000, "PHEV"));
        if (!existing.contains("Toyota RAV4 Plug-in"))
            extras.add(new EvSpec("Toyota RAV4 Plug-in",      3.3, 0.0, 18.1,  75, 480_000, "PHEV"));
        if (!existing.contains("Toyota Prius Plug-in"))
            extras.add(new EvSpec("Toyota Prius Plug-in",     3.3, 0.0,  8.8,  69, 380_000, "PHEV"));
        if (!existing.contains("BMW 330e"))
            extras.add(new EvSpec("BMW 330e",                 3.7, 0.0, 12.0,  60, 520_000, "PHEV"));
        if (!existing.contains("BMW X5 45e"))
            extras.add(new EvSpec("BMW X5 45e",               3.7, 0.0, 24.5,  82, 890_000, "PHEV"));
        if (!existing.contains("Hyundai Tucson PHEV"))
            extras.add(new EvSpec("Hyundai Tucson PHEV",      7.2, 0.0, 13.8,  62, 430_000, "PHEV"));
        if (!existing.contains("Kia Sportage PHEV"))
            extras.add(new EvSpec("Kia Sportage PHEV",        7.2, 0.0, 13.8,  70, 410_000, "PHEV"));
        if (!existing.contains("Mitsubishi Outlander PHEV"))
            extras.add(new EvSpec("Mitsubishi Outlander PHEV",6.1, 0.0, 20.0,  75, 430_000, "PHEV"));
        if (!existing.contains("Mercedes GLC 300e"))
            extras.add(new EvSpec("Mercedes GLC 300e",        3.7, 0.0, 31.2, 100, 680_000, "PHEV"));
        if (!existing.contains("Volkswagen Golf GTE"))
            extras.add(new EvSpec("Volkswagen Golf GTE",      3.6, 0.0, 13.0,  70, 390_000, "PHEV"));
        if (!existing.contains("Volkswagen Passat GTE"))
            extras.add(new EvSpec("Volkswagen Passat GTE",    3.6, 0.0, 13.0,  67, 440_000, "PHEV"));
        if (!existing.contains("Skoda Octavia iV"))
            extras.add(new EvSpec("Skoda Octavia iV",         3.6, 0.0, 13.0,  67, 360_000, "PHEV"));
        if (!existing.contains("Cupra Formentor e-Hybrid"))
            extras.add(new EvSpec("Cupra Formentor e-Hybrid", 3.6, 0.0, 13.0,  63, 400_000, "PHEV"));
        if (!existing.contains("Kia Niro PHEV"))
            extras.add(new EvSpec("Kia Niro PHEV",            3.3, 0.0,  8.9,  58, 290_000, "PHEV"));
        if (!existing.contains("Hyundai Ioniq PHEV"))
            extras.add(new EvSpec("Hyundai Ioniq PHEV",       3.3, 0.0,  8.9,  52, 280_000, "PHEV"));
        if (!existing.contains("Ford Kuga PHEV"))
            extras.add(new EvSpec("Ford Kuga PHEV",           3.7, 0.0, 14.4,  56, 380_000, "PHEV"));
        if (!existing.contains("Ford Explorer PHEV"))
            extras.add(new EvSpec("Ford Explorer PHEV",       3.7, 0.0, 13.6,  42, 580_000, "PHEV"));
        if (!existing.contains("Audi Q5 PHEV"))
            extras.add(new EvSpec("Audi Q5 PHEV",             7.4, 0.0, 14.4,  48, 650_000, "PHEV"));
        if (!existing.contains("Audi A3 PHEV"))
            extras.add(new EvSpec("Audi A3 PHEV",             3.7, 0.0, 13.0,  48, 420_000, "PHEV"));
        if (!existing.contains("BMW 530e"))
            extras.add(new EvSpec("BMW 530e",                 3.7, 0.0, 12.0,  53, 680_000, "PHEV"));
        if (!existing.contains("Peugeot 308 PHEV"))
            extras.add(new EvSpec("Peugeot 308 PHEV",        7.4, 0.0, 12.4,  58, 360_000, "PHEV"));
        if (!existing.contains("Mercedes E 300e"))
            extras.add(new EvSpec("Mercedes E 300e",          3.7, 0.0, 13.5,  50, 710_000, "PHEV"));
        if (!existing.contains("Volvo S60 PHEV"))
            extras.add(new EvSpec("Volvo S60 PHEV",           3.7, 0.0, 11.6,  57, 530_000, "PHEV"));
        if (!existing.contains("Volvo S60 T8"))
            extras.add(new EvSpec("Volvo S60 T8",             3.7, 0.0, 11.6,  57, 530_000, "PHEV"));
        // Alias: AI returns "PHEV" but we stored "Plug-in"
        if (!existing.contains("Toyota RAV4 PHEV"))
            extras.add(new EvSpec("Toyota RAV4 PHEV",         3.3, 0.0, 18.1,  75, 480_000, "PHEV"));
        if (!existing.contains("Toyota Prius PHEV"))
            extras.add(new EvSpec("Toyota Prius PHEV",        3.3, 0.0,  8.8,  69, 380_000, "PHEV"));
        // Hyundai Kona (exists as EV and mild hybrid, AI sometimes calls it PHEV)
        if (!existing.contains("Hyundai Kona PHEV"))
            extras.add(new EvSpec("Hyundai Kona PHEV",        3.3, 0.0,  8.9,  58, 290_000, "PHEV"));
        if (!existing.contains("Hyundai Kona Electric"))
            extras.add(new EvSpec("Hyundai Kona Electric",   11.0, 100.0, 64.8, 484, 400_000));
        // Other common PHEVs
        if (!existing.contains("Renault Captur PHEV"))
            extras.add(new EvSpec("Renault Captur PHEV",      7.4, 0.0,  9.8,  50, 320_000, "PHEV"));
        if (!existing.contains("Volkswagen Tiguan PHEV"))
            extras.add(new EvSpec("Volkswagen Tiguan PHEV",   3.6, 0.0, 13.0,  63, 490_000, "PHEV"));
        if (!existing.contains("Seat Leon PHEV"))
            extras.add(new EvSpec("Seat Leon PHEV",           3.6, 0.0, 13.0,  60, 350_000, "PHEV"));

        if (!extras.isEmpty()) evSpecRepo.saveAll(extras);
    }

    private void seedCargoSpecs() {
        java.util.Set<String> existing = cargoRepo.findAll().stream()
                .map(CargoSpec::getCarName).collect(java.util.stream.Collectors.toSet());

        // name, standard liters, max liters (seats folded, 0 = unknown)
        Object[][] data = {
            // === Elbilar ===
            { "Volvo EX30",              318,  904 },
            { "Volvo EX40",              419,  1295 },
            { "Volvo C40",               413,  1205 },
            { "Volvo EX60",              450,  1300 },
            { "Volvo EX90",              310,  1915 },
            { "Kia EV3",                 460,  1300 },
            { "Kia EV6",                 490,  1300 },
            { "Kia EV9",                 333,  2318 },
            { "Hyundai IONIQ 5",         527,  1587 },
            { "Hyundai IONIQ 6",         401,  0    },
            { "Tesla Model 3",           594,  0    },
            { "Tesla Model Y",           854,  2158 },
            { "Volkswagen ID.3",         385,  1267 },
            { "Volkswagen ID.4",         543,  1575 },
            { "Volkswagen ID.7",         532,  1586 },
            { "Polestar 2",              405,  1095 },
            { "Polestar 3",              484,  1411 },
            { "BMW i4",                  470,  1290 },
            { "BMW iX1",                 490,  1495 },
            { "BMW iX3",                 510,  1560 },
            { "BMW iX",                  500,  1750 },
            { "Audi Q4 e-tron",          520,  1490 },
            { "Mercedes EQA",            340,  1320 },
            { "Mercedes EQB",            495,  1710 },
            { "MG4",                     363,  1177 },
            { "BYD Dolphin",             345,  1310 },
            { "BYD Atto 3",              440,  1340 },
            { "Dacia Spring",            308,  1004 },
            // === Laddhybrider ===
            { "Volvo XC60 Recharge",     477,  1432 },
            { "Volvo XC90 Recharge",     249,  1856 },
            { "Volvo V60 Recharge",      454,  1441 },
            { "Toyota RAV4 PHEV",        520,  1604 },
            { "Toyota Prius PHEV",       217,  0    },
            { "Kia Niro PHEV",           349,  1342 },
            { "Kia Sportage PHEV",       587,  1650 },
            { "Hyundai Tucson PHEV",     558,  1795 },
            { "Hyundai Kona PHEV",       374,  1296 },
            { "BMW 330e",                375,  0    },
            { "BMW 530e",                410,  0    },
            { "BMW X5 45e",              650,  1870 },
            { "Mitsubishi Outlander PHEV", 477, 1602 },
            { "Mercedes GLC 300e",       620,  1640 },
            { "Mercedes E 300e",         430,  0    },
            { "Volkswagen Golf GTE",     272,  1270 },
            { "Volkswagen Passat GTE",   586,  1769 },
            { "Volkswagen Tiguan PHEV",  615,  1655 },
            { "Audi Q5 PHEV",            520,  1520 },
            { "Audi A3 PHEV",            325,  1145 },
            { "Ford Kuga PHEV",          556,  1534 },
            { "Ford Explorer PHEV",      800,  2274 },
            { "Peugeot 308 PHEV",        412,  1271 },
            { "Renault Captur PHEV",     379,  1275 },
            { "Seat Leon PHEV",          380,  1301 },
            { "Skoda Octavia iV",        600,  1700 },
            { "Cupra Formentor e-Hybrid", 420, 1483 },
            // === Bensin / Diesel / Mild-hybrid ===
            { "Volvo XC60",              505,  1432 },
            { "Volvo XC90",              316,  1856 },
            { "Volvo V90",               560,  1526 },
            { "Volvo V60",               529,  1441 },
            { "Toyota RAV4",             580,  1604 },
            { "Toyota Corolla",          361,  1354 },
            { "Toyota Yaris",            286,  768  },
            { "Toyota Camry",            493,  0    },
            { "Volkswagen Golf",         381,  1270 },
            { "Volkswagen Passat",       650,  1769 },
            { "Volkswagen Tiguan",       615,  1655 },
            { "Skoda Octavia",           600,  1700 },
            { "Skoda Superb",            625,  1800 },
            { "BMW 3-serien",            480,  1510 },
            { "BMW 5-serien",            520,  1700 },
            { "BMW X3",                  550,  1600 },
            { "BMW X5",                  650,  1870 },
            { "Mercedes C-klass",        455,  1510 },
            { "Mercedes E-klass",        490,  1610 },
            { "Mercedes GLC",            620,  1640 },
            { "Audi A4",                 480,  1495 },
            { "Audi A3",                 325,  1145 },
            { "Audi Q5",                 520,  1520 },
            { "Hyundai Tucson",          558,  1795 },
            { "Hyundai Kona",            374,  1296 },
            { "Kia Sportage",            587,  1650 },
            { "Seat Leon",               380,  1301 },
            { "Ford Kuga",               556,  1534 },
            { "Ford Focus",              375,  1354 },
            { "Mazda CX-5",              442,  1648 },
            { "Renault Kadjar",          472,  1478 },
            // === Elbilar (saknade) ===
            { "Tesla Model S",           793,  0    },
            { "Volkswagen ID.5",         549,  1561 },
            { "Volkswagen ID.Buzz",     1121,  2205 },
            { "Audi Q6 e-tron",          526,  1529 },
            { "Audi Q8 e-tron",          569,  1665 },
            { "Kia Niro EV",             475,  1447 },
            { "Polestar 4",              526,  1536 },
            { "Mercedes EQC",            500,  1460 },
            { "Mercedes EQE",            430,  0    },
            { "Mercedes EQS",            610,  1770 },
            { "Ford Mustang Mach-E",     402,  1420 },
            { "BYD Seal",                400,  0    },
            { "MG ZS EV",               448,  1166 },
            { "MG5",                     578,  1456 },
            { "Toyota bZ4X",             452,  1589 },
            { "Cupra Born",              385,  1267 },
            { "Renault Megane E-Tech",   440,  1332 },
            { "Renault Zoe",             338,  0    },
            { "Nissan Ariya",            415,  1440 },
            { "Nissan Leaf",             435,  1176 },
            { "Fiat 500e",               185,  0    },
            { "Genesis GV60",            432,  1571 },
            { "Xpeng G6",               571,  1374 },
            { "Škoda Enyaq iV",          585,  1710 },
            { "Škoda Elroq",             470,  1580 },
            { "Hyundai Kona Electric",   466,  1248 },
            { "BMW i5",                  490,  1700 },
            // === Bensin / Diesel (saknade) ===
            { "Dacia Sandero",           328,  1108 },
            { "Volkswagen Polo",         351,  1079 },
            { "Renault Clio",            391,  1146 },
            { "Peugeot 308",             412,  1271 },
            { "Honda CR-V",              589,  1694 },
            { "Nissan Qashqai",          504,  1585 },
            { "Mazda CX-30",             430,  1406 },
            { "Toyota C-HR",             377,  0    },
            { "Volkswagen T-Roc",        445,  1290 },
            { "Seat Arona",              400,  1280 },
        };

        java.util.List<CargoSpec> toSave = new java.util.ArrayList<>();
        for (Object[] row : data) {
            String name = (String) row[0];
            if (!existing.contains(name)) {
                toSave.add(new CargoSpec(name, (Integer) row[1], (Integer) row[2]));
            }
        }
        if (!toSave.isEmpty()) cargoRepo.saveAll(toSave);
    }
}
