package com.caradvice.service;

import com.caradvice.model.CarPreferences;
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

    public String getRecommendation(CarPreferences prefs) throws Exception {
        String prompt = buildPrompt(prefs);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Du är en kunnig och opartisk svensk bilrådgivare. " +
                                "Basera alltid dina rekommendationer på bilar som fått goda recensioner och betyg inom sin kategori – " +
                                "t.ex. välrecenserade familjebbilar, topprankade elbilar, pålitliga ekonomibilar. " +
                                "Motivera varför just dessa modeller är högt värderade av experter och ägare. " +
                                "Ge alltid konkreta bilmodeller med tydliga motiveringar. " +
                                "Svara på svenska."),
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
        return json.at("/choices/0/message/content").asText();
    }

    private String buildPrompt(CarPreferences prefs) {
        String laddningInfo = prefs.hasCharger()
                ? "Ja, har laddbox hemma – elbil eller laddhybrid är aktuellt."
                : "Nej, ingen laddmöjlighet hemma – undvik renodlade elbilar.";

        String bilTyp = prefs.newCar() ? "nytt" : "begagnat";

        return """
                Ge mig 3 konkreta bilrekommendationer baserat på följande krav:

                - Budget: %,d kr (%s bil)
                - Bilkategori: %s – rekommendera bilar som fått bra recensioner och höga betyg inom just denna kategori
                - Laddmöjlighet hemma: %s
                - Körsträcka per år: %,d km – välj bränsletyp och modell som är kostnadseffektiv för denna körsträcka
                - Ny eller begagnad: %s – anpassa modellår och prisförväntningar därefter
                - Användning: %s
                - Antal passagerare (inkl. förare): %d

                För varje bil, ange:
                1. Modell, ungefärligt pris och varför den är välrecenserad i sin kategori
                2. Tre fördelar för just denna person baserat på körsträcka och användning
                3. En nackdel att tänka på
                4. Varför den passar just dessa behov
                """.formatted(
                prefs.budget(),
                bilTyp,
                prefs.carCategory(),
                laddningInfo,
                prefs.kmPerYear(),
                bilTyp,
                prefs.usage(),
                prefs.passengers()
        );
    }
}
