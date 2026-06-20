package com.caradvice.data;

import com.caradvice.model.ExpertInsight;
import com.caradvice.model.SafetyRating;
import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.repository.SafetyRatingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataLoader implements CommandLineRunner {

    private final ExpertInsightRepository expertRepo;
    private final SafetyRatingRepository safetyRepo;

    public DataLoader(ExpertInsightRepository expertRepo, SafetyRatingRepository safetyRepo) {
        this.expertRepo = expertRepo;
        this.safetyRepo = safetyRepo;
    }

    @Override
    public void run(String... args) {
        if (expertRepo.count() == 0) seedInsights();
        if (safetyRepo.count() == 0) seedSafetyRatings();
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
            new SafetyRating("Tesla",      "Model 3",       2019, 5, 96, 86, 82, 98),
            new SafetyRating("Tesla",      "Model Y",       2022, 5, 97, 87, 79, 98),
            new SafetyRating("Volvo",      "XC40",          2018, 5, 97, 89, 76, 75),
            new SafetyRating("Volvo",      "XC60",          2017, 5, 97, 87, 75, 73),
            new SafetyRating("Volvo",      "V60",           2018, 5, 96, 82, 71, 80),
            new SafetyRating("Toyota",     "RAV4",          2019, 5, 97, 86, 80, 74),
            new SafetyRating("Toyota",     "Yaris",         2020, 5, 98, 87, 65, 90),
            new SafetyRating("Toyota",     "Corolla",       2019, 5, 96, 91, 82, 75),
            new SafetyRating("Volkswagen", "Golf",          2020, 5, 95, 89, 71, 87),
            new SafetyRating("Volkswagen", "ID.4",          2021, 5, 91, 89, 76, 93),
            new SafetyRating("Hyundai",    "Ioniq 5",       2021, 5, 97, 91, 80, 92),
            new SafetyRating("Hyundai",    "Tucson",        2021, 5, 97, 91, 74, 79),
            new SafetyRating("Kia",        "EV6",           2022, 5, 93, 91, 71, 90),
            new SafetyRating("Kia",        "Sportage",      2022, 5, 94, 89, 71, 85),
            new SafetyRating("Skoda",      "Octavia",       2021, 5, 98, 89, 74, 74),
            new SafetyRating("Polestar",   "2",             2020, 5, 94, 90, 77, 98),
            new SafetyRating("Nissan",     "Leaf",          2018, 5, 88, 89, 69, 60),
            new SafetyRating("MG",         "ZS",            2022, 4, 79, 84, 74, 68),
            new SafetyRating("Dacia",      "Sandero",       2021, 3, 61, 55, 61, 23),
            new SafetyRating("Ford",       "Mustang Mach-E",2022, 5, 91, 89, 71, 91)
        ));
    }
}
