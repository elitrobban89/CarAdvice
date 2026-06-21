package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.springframework.stereotype.Service;

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
                .stream().limit(5).toList();

        if (insights.isEmpty()) return "";
        return formatInsights(insights, "Expertinsikter (använd som extra underlag i din analys):\n");
    }

    public String buildChatExpertContext(List<String> recentMessages) {
        List<ExpertInsight> all = repo.findAll();
        if (all.isEmpty()) return "";

        // Pick insights that mention car makes/models found in recent messages
        String combined = String.join(" ", recentMessages).toLowerCase();
        List<ExpertInsight> matched = new ArrayList<>();
        List<ExpertInsight> general = new ArrayList<>();

        for (ExpertInsight i : all) {
            if (i.getCarMake() != null && combined.contains(i.getCarMake().toLowerCase())) {
                matched.add(i);
            } else if (i.getCarMake() == null) {
                general.add(i);
            }
        }

        List<ExpertInsight> selected = new ArrayList<>(matched);
        // Fill up to 6 with general insights
        for (ExpertInsight g : general) {
            if (selected.size() >= 6) break;
            selected.add(g);
        }

        if (selected.isEmpty()) return "";
        return formatInsights(selected, "Bilexpertinsikter (referera till dessa när relevant, ange källan):\n");
    }

    public ExpertInsight save(ExpertInsight insight) {
        return repo.save(insight);
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
        return importCsv(csv, "Erik Naessén");
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
            sb.append(" (").append(i.getExpertName()).append(")\n");
        }
        return sb.toString();
    }
}
