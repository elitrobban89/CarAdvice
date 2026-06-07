package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Service
public class GroqService {

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public List<CarRecommendation> getRecommendation(CarPreferences prefs) throws Exception {
        String prompt = buildPrompt(prefs);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1024,
                "temperature", 0.3,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", buildSystemPrompt()),
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Groq API fel: " + response.statusCode() + " - " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        JsonNode recsNode = mapper.readTree(content).at("/recommendations");
        return mapper.convertValue(
                recsNode,
                mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class)
        );
    }

    private String buildSystemPrompt() {
        return """
                Svensk bilrådgivare, svenska marknaden 2024–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källa+motivering","pros":["fördel1","fördel2","fördel3"],"con":"nackdel","fitSummary":"passning"}]}
                Exakt 3 bilar. Pris anpassat ny/begagnad. Fördelar specifika för profilen. Driftkostnad i pros vid hög körsträcka.
                """;
    }

    private String buildPrompt(CarPreferences prefs) {
        String laddning = prefs.hasCharger() ? "ja" : "nej – undvik renodlad elbil";
        String bilTyp = prefs.newCar() ? "ny" : "begagnad";
        int km = prefs.kmPerYear();
        String milprofil = km < 10000 ? "lågmilare" : km < 20000 ? "normalmilare" : "högmilare";

        return """
                Budget: %,d kr (%s). Kategori: %s. Laddbox: %s. Körsträcka: %,d km/år (%s). Användning: %s. Passagerare: %d.
                """.formatted(
                prefs.budget(), bilTyp, prefs.carCategory(), laddning,
                km, milprofil, prefs.usage(), prefs.passengers()
        );
    }
}
