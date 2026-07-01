package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExpertInsightService {

    private final ExpertInsightRepository repo;

    public ExpertInsightService(ExpertInsightRepository repo) {
        this.repo = repo;
    }

    public String buildExpertContext(CarPreferences prefs) {
        String category = prefs.carCategory();
        String fuelType = ("spelar ingen roll".equalsIgnoreCase(prefs.fuelType()))
                ? category
                : prefs.fuelType();

        List<ExpertInsight> insights = repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase(category, fuelType)
                .stream().limit(2).toList();

        if (insights.isEmpty()) return "";
        return formatInsights(insights, "Expertinsikter (använd som extra underlag i din analys):\n");
    }

    public String buildChatExpertContext(List<String> recentMessages) {
        List<ExpertInsight> all = repo.findAll();
        if (all.isEmpty()) return "";

        // Only include insights whose car make is explicitly mentioned in the conversation.
        // Never add general (carMake == null) insights — they appear regardless of topic and cause off-topic noise.
        String combined = String.join(" ", recentMessages).toLowerCase();
        List<ExpertInsight> matched = new ArrayList<>();

        for (ExpertInsight i : all) {
            if (i.getCarMake() != null && combined.contains(i.getCarMake().toLowerCase())) {
                matched.add(i);
            }
        }

        if (matched.isEmpty()) return "";
        List<ExpertInsight> selected = matched.stream().limit(3).toList();
        return formatInsights(selected, "Bilexpertinsikter (referera BARA till dessa om de direkt gäller den bil användaren frågar om just nu — inkludera dem INTE om de handlar om en annan bil):\n");
    }

    public ExpertInsight save(ExpertInsight insight) {
        return repo.save(insight);
    }

    @Transactional
    public void deleteByExpert(String expertName) {
        repo.deleteByExpertName(expertName);
    }

    public long countByExpert(String expertName) {
        return repo.countByExpertName(expertName);
    }

    public int importCsv(String csv, String expertName) {
        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("car_make")) continue;
            String[] f = SafetyRatingService.parseCsvLine(line);
            if (f.length < 5) continue;
            String carMake   = blank(f[0]) ? null : f[0];
            String carModel  = blank(f[1]) ? null : f[1];
            String fuelType  = blank(f[2]) ? null : f[2];
            String category  = blank(f[3]) ? null : f[3];
            String insight   = f[4];
            Integer rating   = null;
            if (f.length > 5 && !blank(f[5])) {
                try { rating = Integer.parseInt(f[5].trim()); } catch (NumberFormatException ignored) {}
            }
            repo.save(new ExpertInsight(expertName, carMake, carModel, fuelType, category, insight, rating));
            count++;
        }
        return count;
    }

    public int importCsv(String csv) {
        return importCsv(csv, "Bilexpert");
    }

    private boolean blank(String s) { return s == null || s.isBlank() || s.equals("null"); }

    private String formatInsights(List<ExpertInsight> insights, String header) {
        StringBuilder sb = new StringBuilder(header);
        for (ExpertInsight i : insights) {
            sb.append("- ");
            if (i.getCarMake() != null && i.getCarModel() != null)
                sb.append(i.getCarMake()).append(" ").append(i.getCarModel()).append(": ");
            sb.append(i.getInsight());
            if (i.getRating() != null)
                sb.append(" [").append(i.getRating()).append("/10]");
            sb.append(" (").append(resolveExpertName(i.getExpertName())).append(")\n");
        }
        return sb.toString();
    }

    private String resolveExpertName(String name) {
        if (name == null) return "Bilexpert";
        return switch (name) {
            case "Bilprovningen", "Teknikens Värld", "Vi Bilägare" -> name;
            default -> "Bilexpert";
        };
    }
}
