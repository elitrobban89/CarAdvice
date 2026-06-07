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

    public List<CarRecommendation> getRecommendation(CarPreferences prefs) throws Exception {
        String prompt = buildPrompt(prefs);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 2048,
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
                Du är en kunnig och opartisk svensk bilrådgivare med djup kunskap om den svenska marknaden 2024–2026.

                Returnera ALLTID ett JSON-objekt med exakt denna struktur – ingen text utanför JSON:
                {
                  "recommendations": [
                    {
                      "title": "Märke Modell Variant (årsmodell)",
                      "price": "XXX 000 – YYY 000 kr",
                      "whyRecommended": "Varför denna bil är välrecenserad baserat på t.ex. Teknikens Värld, Auto Motor & Sport, Bilprovningens statistik eller ägarrecensioner",
                      "pros": ["Specifik fördel 1 kopplad till just denna persons körsträcka och användning", "Specifik fördel 2", "Specifik fördel 3"],
                      "con": "En konkret nackdel att vara medveten om",
                      "fitSummary": "En kort mening om varför detta val passar just dessa specifika behov"
                    }
                  ]
                }

                Regler:
                - Rekommendera ALLTID exakt 3 konkreta bilar
                - Anpassa prisintervall till om bilen är ny eller begagnad
                - Fördelar ska vara specifika för personens situation – inte generiska påståenden
                - Nämn uppskattad driftkostnad (bränsle/el per år) i pros om det är relevant för körsträckan
                - Svara på svenska
                """;
    }

    private String buildPrompt(CarPreferences prefs) {
        String laddningInfo = prefs.hasCharger()
                ? "Ja, har laddbox hemma – elbil eller laddhybrid är aktuellt."
                : "Nej, ingen laddmöjlighet hemma – undvik renodlade elbilar.";

        String bilTyp = prefs.newCar() ? "ny" : "begagnad";
        int km = prefs.kmPerYear();
        String milprofil = km < 10000
                ? "lågmilare – driftkostnad spelar mindre roll, prioritera pris och tillförlitlighet"
                : km < 20000
                ? "normalmilare – balansera inköpspris mot driftkostnad"
                : "högmilare – prioritera låg driftkostnad och hög driftsäkerhet";

        return """
                Ge 3 bilrekommendationer baserat på följande profil:

                - Budget: %,d kr (%s bil)
                - Bilkategori: %s
                - Laddmöjlighet hemma: %s
                - Körsträcka per år: %,d km (%s)
                - Användning: %s
                - Antal passagerare (inkl. förare): %d

                Anpassa varje rekommendation specifikt till denna persons profil.
                """.formatted(
                prefs.budget(),
                bilTyp,
                prefs.carCategory(),
                laddningInfo,
                km,
                milprofil,
                prefs.usage(),
                prefs.passengers()
        );
    }
}
