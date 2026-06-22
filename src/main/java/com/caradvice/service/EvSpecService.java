package com.caradvice.service;

import com.caradvice.model.EvSpec;
import com.caradvice.model.EvSpecDto;
import com.caradvice.repository.EvSpecRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;

@Service
public class EvSpecService {

    private final EvSpecRepository repo;

    public EvSpecService(EvSpecRepository repo) {
        this.repo = repo;
    }

    public EvSpecDto formatForTitle(String title, int kmPerYear) {
        if (title == null) return null;
        String cleaned = normalize(title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim());
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

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase();
    }
}
