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

        List<EvSpec> all = repo.findAll();

        // Pass 1: all title words appear in stored name (title is subset of stored name)
        EvSpec match = all.stream()
                .filter(ev -> {
                    String name = normalize(ev.getCarName());
                    for (String w : titleWords) if (!name.contains(w)) return false;
                    return true;
                })
                .findFirst().orElse(null);

        // Pass 2: all stored-name words appear in title (stored name is subset of title)
        // e.g. "Tesla Model 3" matches "Tesla Model 3 Long Range"
        if (match == null) {
            match = all.stream()
                    .filter(ev -> {
                        String[] nameWords = normalize(ev.getCarName()).split("\\s+");
                        for (String w : nameWords) if (!cleaned.contains(w)) return false;
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
            double score = (double) wltp / (price / 100_000.0);
            valueLabel = score > 160 ? "Utmärkt prisvärdhet"
                       : score > 130 ? "Bra prisvärdhet"
                       : score > 100 ? "Ok prisvärdhet"
                       : "";
        }

        return new EvSpecDto(wltp, summer, winter, days, daysLabel, bat, maxDc, maxAc, price, valueLabel);
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase();
    }
}
