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
        return formatInsights(selected, "Expertinsikter från Erik Naessén (referera till dessa när relevant, ange källan):\n");
    }

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
