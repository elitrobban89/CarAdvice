package com.caradvice.service;

import com.caradvice.model.EvSpec;
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

    public String formatForTitle(String title, int kmPerYear) {
        if (title == null) return null;
        String cleaned = normalize(title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim());
        String[] words = cleaned.split("\\s+");

        List<EvSpec> all = repo.findAll();
        return all.stream()
                .filter(ev -> {
                    String name = normalize(ev.getCarName());
                    for (String w : words) {
                        if (!name.contains(w)) return false;
                    }
                    return true;
                })
                .findFirst()
                .map(ev -> format(ev, kmPerYear))
                .orElse(null);
    }

    private String format(EvSpec spec, int kmPerYear) {
        int wltp        = spec.getRangeKm();
        int summerRange = (int) (wltp * 0.85);
        int winterRange = (int) (wltp * 0.70);

        StringBuilder sb = new StringBuilder();
        sb.append("WLTP ").append(wltp).append(" km");
        sb.append(" · Sommar ~").append(summerRange).append(" km");
        sb.append(" · Vinter ~").append(winterRange).append(" km");

        if (kmPerYear > 0) {
            double dailyKm = kmPerYear / 365.0;
            int days = (int) Math.max(1, Math.round(summerRange / dailyKm));
            String dayStr = days == 1 ? "dagligen"
                          : days == 2 ? "varannan dag"
                          : "var " + days + ":e dag";
            sb.append(" · Laddas ").append(dayStr);
        }

        if (spec.getBatteryKwh() != null) {
            double kwh = spec.getBatteryKwh();
            String kwhStr = kwh == (long) kwh ? String.valueOf((long) kwh) : String.valueOf(kwh);
            sb.append(" · ").append(kwhStr).append(" kWh");
        }
        if (spec.getMaxDcKw() != null && spec.getMaxDcKw() > 0) {
            sb.append(" · Max ").append((int) spec.getMaxDcKw().doubleValue()).append(" kW DC");
        }

        if (spec.getPriceKr() != null && spec.getPriceKr() > 0) {
            double score = (double) wltp / (spec.getPriceKr() / 100_000.0);
            String value = score > 160 ? "Utmärkt prisvärdhet"
                         : score > 130 ? "Bra prisvärdhet"
                         : score > 100 ? "Ok prisvärdhet"
                         : null;
            if (value != null) sb.append(" · ").append(value);
        }

        return sb.toString();
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase();
    }
}
