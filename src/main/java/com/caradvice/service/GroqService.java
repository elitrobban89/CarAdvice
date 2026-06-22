package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    public record Result(List<CarRecommendation> recommendations, boolean fromCache, long cacheAgeSeconds) {}

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.chat.model:llama-3.1-8b-instant}")
    private String chatModel;

    private final ExpertInsightService expertInsightService;
    private final SafetyRatingService safetyRatingService;
    private final EvSpecService evSpecService;
    private final CargoSpecService cargoSpecService;
    private final BlocketPriceService blocketPriceService;

    public GroqService(ExpertInsightService expertInsightService, SafetyRatingService safetyRatingService,
                       EvSpecService evSpecService, CargoSpecService cargoSpecService,
                       BlocketPriceService blocketPriceService) {
        this.expertInsightService = expertInsightService;
        this.safetyRatingService = safetyRatingService;
        this.evSpecService = evSpecService;
        this.cargoSpecService = cargoSpecService;
        this.blocketPriceService = blocketPriceService;
    }

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final long CACHE_TTL_MS = 4 * 60 * 60 * 1000;
    private static final int MAX_CACHE_SIZE = 200;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(List<CarRecommendation> result, long timestamp) {}

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Result getRecommendation(CarPreferences prefs) throws Exception {
        String key = buildCacheKey(prefs);
        CacheEntry cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            long ageSeconds = (System.currentTimeMillis() - cached.timestamp()) / 1000;
            return new Result(cached.result(), true, ageSeconds);
        }

        String prompt = buildPrompt(prefs);
        String expertContext = "";
        try { expertContext = expertInsightService.buildExpertContext(prefs); } catch (Exception ignored) {}

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1050,
                "temperature", 0.3,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", buildSystemPrompt(expertContext)),
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

        if (response.statusCode() == 429) {
            Map<String, Object> fallbackBody = Map.of(
                    "model", chatModel,
                    "max_tokens", 1050,
                    "temperature", 0.3,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", buildSystemPrompt(expertContext)),
                            Map.of("role", "user", "content", prompt)
                    )
            );
            HttpRequest fallbackRequest = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(fallbackBody)))
                    .build();
            response = httpClient.send(fallbackRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                if (cached != null) {
                    long ageSeconds = (System.currentTimeMillis() - cached.timestamp()) / 1000;
                    return new Result(cached.result(), true, ageSeconds);
                }
                throw new RuntimeException(buildRateLimitError(response.body()));
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ". Försök igen om en stund.");
            }
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ". Försök igen om en stund.");
        }

        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        JsonNode recsNode = mapper.readTree(content).at("/recommendations");
        List<CarRecommendation> parsed = mapper.convertValue(
                recsNode,
                mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class)
        );

        // Fetch Blocket prices in parallel while the rest enriches sequentially
        List<CompletableFuture<String>> blocketFutures = parsed.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        BlocketPriceService.PriceRange pr = blocketPriceService.fetchPriceRange(r.title());
                        return pr != null ? pr.formatted() : null;
                    } catch (Exception e) {
                        return null;
                    }
                }))
                .toList();

        List<CarRecommendation> result = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            CarRecommendation r = parsed.get(i);
            String safety = null;
            com.caradvice.model.EvSpecDto evSpec = null;
            com.caradvice.model.CargoSpecDto cargo = null;
            String blocketPrice = null;
            try { safety = safetyRatingService.formatForTitle(r.title()); } catch (Exception ignored) {}
            try { evSpec = evSpecService.formatForTitle(r.title(), prefs.kmPerYear()); } catch (Exception ignored) {}
            try { cargo = cargoSpecService.formatForTitle(r.title()); } catch (Exception ignored) {}
            try { blocketPrice = blocketFutures.get(i).get(6, TimeUnit.SECONDS); } catch (Exception ignored) {}
            result.add(new CarRecommendation(
                    r.title(), r.price(), r.whyRecommended(), r.pros(), r.con(),
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, r.fuelSpec(), blocketPrice, r.horsepower(), r.engineOptions()));
        }

        evictIfNeeded();
        cache.put(key, new CacheEntry(result, System.currentTimeMillis()));
        return new Result(result, false, 0);
    }

    private void evictIfNeeded() {
        if (cache.size() < MAX_CACHE_SIZE) return;
        long cutoff = cache.values().stream()
                .mapToLong(CacheEntry::timestamp)
                .sorted()
                .skip(cache.size() / 2)
                .findFirst()
                .orElse(0L);
        cache.values().removeIf(e -> e.timestamp() < cutoff);
    }

    public List<CarRecommendation> compareSpecific(String car1, String car2) throws Exception {
        String userPrompt = "Jämför dessa exakt 2 bilar: 1. " + car1 + "  2. " + car2;

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 1200,
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", buildCompareSystemPrompt()),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            Map<String, Object> fallbackBody = Map.of(
                    "model", chatModel,
                    "max_tokens", 1200,
                    "temperature", 0.2,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", buildCompareSystemPrompt()),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );
            HttpRequest fallbackRequest = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(fallbackBody)))
                    .build();
            response = httpClient.send(fallbackRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429)
                throw new RuntimeException(buildRateLimitError(response.body()));
            if (response.statusCode() != 200)
                throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ". Försök igen om en stund.");
        }
        if (response.statusCode() != 200)
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ". Försök igen om en stund.");

        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        JsonNode recsNode = mapper.readTree(content).at("/recommendations");
        List<CarRecommendation> parsed = mapper.convertValue(
                recsNode,
                mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class)
        );

        List<CompletableFuture<String>> blocketFutures = parsed.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        BlocketPriceService.PriceRange pr = blocketPriceService.fetchPriceRange(r.title());
                        return pr != null ? pr.formatted() : null;
                    } catch (Exception e) { return null; }
                }))
                .toList();

        List<CarRecommendation> result = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            CarRecommendation r = parsed.get(i);
            String safety = null;
            com.caradvice.model.EvSpecDto evSpec = null;
            com.caradvice.model.CargoSpecDto cargo = null;
            String blocketPrice = null;
            try { safety = safetyRatingService.formatForTitle(r.title()); } catch (Exception ignored) {}
            try { evSpec = evSpecService.formatForTitle(r.title(), 15000); } catch (Exception ignored) {}
            try { cargo = cargoSpecService.formatForTitle(r.title()); } catch (Exception ignored) {}
            try { blocketPrice = blocketFutures.get(i).get(6, TimeUnit.SECONDS); } catch (Exception ignored) {}
            result.add(new CarRecommendation(
                    r.title(), r.price(), r.whyRecommended(), r.pros(), r.con(),
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, r.fuelSpec(), blocketPrice, r.horsepower(), r.engineOptions()));
        }
        return result;
    }

    private String buildCompareSystemPrompt() {
        return """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Jämför EXAKT de 2 bilar användaren anger. Svara ENDAST med JSON (EXAKT 2 bilar):
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"bilens styrka","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"vem passar bilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","engineOptions":"motorvarianter kommaseparerade; elbil: '51 kWh 170hk (420km)'","fuelSpec":null}]}
                Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"typ (turbo/ej)","horsepower":N,"engineVolumeLiters":X.X}. Elbil/laddhybrid: fuelSpec=null, inga turbobeteckningar.
                Ange exakt årsmodell. Svara på svenska.
                PRISER — fältet "price" ska ALLTID vara ett intervall som "280 000–320 000 kr". Exakta siffror med mellanslag, aldrig förkortningar, aldrig extra text.
                Begagnad ca: listpris×0.85 (1år), ×0.75 (2år).
                Referenspriser (SEK): Spring 195 000, Dolphin 300–340 000, MG4 330–365 000, EV3/EX30/Model3 430 000, Polestar2 609 000, ModelY LR 600 000.
                """;
    }

    private String buildCacheKey(CarPreferences prefs) {
        return prefs.budget() + "|" + prefs.carCategory() + "|" + prefs.hasCharger() + "|" +
               prefs.kmPerYear() + "|" + prefs.usage() + "|" + prefs.passengers() + "|" + prefs.newCar() + "|" +
               (prefs.fuelType() != null ? prefs.fuelType() : "") + "|" +
               (prefs.transmission() != null ? prefs.transmission() : "") + "|" +
               (prefs.budgetType() != null ? prefs.budgetType() : "köp");
    }

    private String parseRetryTime(String body) {
        try {
            Matcher m = Pattern.compile("try again in (\\d+m[\\d.]+s|[\\d.]+s)").matcher(body);
            if (!m.find()) return "en stund";
            String t = m.group(1);
            Matcher minMatcher = Pattern.compile("(\\d+)m").matcher(t);
            Matcher secMatcher = Pattern.compile("([\\d.]+)s").matcher(t);
            int minutes = minMatcher.find() ? Integer.parseInt(minMatcher.group(1)) : 0;
            double seconds = secMatcher.find() ? Double.parseDouble(secMatcher.group(1)) : 0;
            int total = (int) Math.ceil(minutes + seconds / 60.0);
            return total <= 1 ? "1 minut" : total + " minuter";
        } catch (Exception e) {
            return "en stund";
        }
    }

    private String buildRateLimitError(String body) {
        try {
            JsonNode err = mapper.readTree(body);
            String msg = err.at("/error/message").asText("");
            log.warn("Groq 429: {}", msg);
            if (msg.contains("per day") || msg.contains("RPD") || msg.contains("TPD")) {
                return "Dagsgränsen för AI-anrop är nådd. Försök igen om " + parseRetryTime(body) + ".";
            }
        } catch (Exception ignored) {}
        return "AI-tjänsten är tillfälligt överbelastad. Vänta " + parseRetryTime(body) + " och försök igen.";
    }

    public String chat(List<Map<String, String>> messages, String carContext) throws Exception {
        String expertContext = "";
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages)); } catch (Exception ignored) {}
        String systemPrompt = buildChatSystemPrompt(carContext, expertContext);

        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", systemPrompt));
        msgs.addAll(messages);

        Map<String, Object> requestBody = Map.of(
                "model", chatModel,
                "max_tokens", 900,
                "temperature", 0.5,
                "messages", msgs
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
            throw new RuntimeException("Groq svarade " + response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        return json.at("/choices/0/message/content").asText("Inget svar.");
    }

    public InputStream chatStream(List<Map<String, String>> messages, String carContext) throws Exception {
        String expertContext = "";
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages)); } catch (Exception ignored) {}
        String systemPrompt = buildChatSystemPrompt(carContext, expertContext);

        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", systemPrompt));
        msgs.addAll(messages);

        Map<String, Object> requestBody = Map.of(
                "model", chatModel, "max_tokens", 900, "temperature", 0.5, "stream", true, "messages", msgs);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401) throw new RuntimeException("AI-tjänsten är inte korrekt konfigurerad.");
        if (response.statusCode() == 429) throw new RuntimeException("För många frågor — vänta en minut och försök igen.");
        if (response.statusCode() != 200) throw new RuntimeException("Groq svarade " + response.statusCode());
        return response.body();
    }

    private String buildChatSystemPrompt(String carContext, String expertContext) {
        String base = """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svarar på köp, jämförelser, driftkostnad, skatt, värdeminskning och tillförlitlighet.
                Ej hjälp med laddstationer/navigering. Ej bilfrågor: "Det faller utanför mitt område."
                Svara på svenska. Använd **fetstil** och - listor.
                Expertinsikter: citera bara om direkt relevant för exakt den bil/ämne som frågas — aldrig om annan bil. Citera: "**[namn]:** [insikt]".
                SKATT elbilar: befriade från fordonsskatt — nämn aldrig generella årsavgifter.
                PRISER — fältet "price" ska ALLTID vara ett intervall som "280 000–320 000 kr". Exakta siffror, aldrig förkortningar. Referenspriser (SEK): Spring 195 000, Dolphin 300–340 000, MG4 330–365 000, EV3/EX30/Model3 430 000, Kamiq 290–350 000, Golf 320–400 000. Blocket-priser i kontexten prioriteras.
                """;
        if (carContext != null && !carContext.isBlank())
            base += "\n\nAktuella bilrekommendationer:\n" + carContext;
        if (expertContext != null && !expertContext.isBlank())
            base += "\n\n" + expertContext;
        return base;
    }

    private List<String> extractUserTexts(List<Map<String, String>> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .toList();
    }

    private String buildSystemPrompt(String expertContext) {
        String base = """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källa t.ex. 'Teknikens Värld: toppbetyg'","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"varför bilen passar profilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade","fuelSpec":null}]}
                OBLIGATORISKA fält — sätt ALDRIG null: horsepower (systemeffekt i hk som heltal), engineOptions (STRÄNG med kommaseparerade varianter; bensin/diesel ex: '1.0 TSI 95hk manuell, 1.5 TSI 150hk DSG automat'; elbil ex: '44 kWh 95hk (400km), 60 kWh 204hk (570km)').
                Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"Automat DSG 7-växlad (TSI turbo)","horsepower":N,"engineVolumeLiters":X.X} — ange turbo/ej turbo. Elbil/laddhybrid: fuelSpec=null, inga turbobeteckningar i engineOptions.
                Exakt 3 bilar. fitSummary konkret och personlig. Driftkostnad i pros vid hög körsträcka.
                PRISER — fältet "price" ska ALLTID vara ett intervall som "85 000–100 000 kr". Exakta siffror med mellanslag, aldrig förkortningar, aldrig extra text.
                Begagnad ca: listpris×0.85 (1år), ×0.75 (2år), ×0.65 (3år). Välj årsmodell som ryms i budget.
                Referenspriser för vanliga bilar (SEK): Spring 195 000, Dolphin 300–340 000, MG4 330–365 000, EV3/EX30/Model3 430 000, Polestar2 609 000, Fabia 240–300 000, Kamiq 290–350 000, Golf 320–400 000.
                """;
        if (expertContext != null && !expertContext.isBlank())
            return base + "\n" + expertContext;
        return base;
    }

    private String buildPrompt(CarPreferences prefs) {
        String laddning = prefs.hasCharger() ? "ja" : "nej – undvik renodlad elbil";
        String bilTyp = prefs.newCar() ? "ny" : "begagnad";
        int km = prefs.kmPerYear();
        String milprofil = km < 10000 ? "lågmilare" : km < 20000 ? "normalmilare" : "högmilare";
        boolean isLeasing = "leasing".equals(prefs.budgetType());
        String budgetInfo = isLeasing
                ? String.format("%,d kr/mån (leasing, ca %,d kr i listpris)", prefs.budget(), prefs.budget() * 85)
                : String.format("%,d kr (%s)", prefs.budget(), bilTyp);
        String fuelLine = (prefs.fuelType() != null && !prefs.fuelType().isBlank()
                && !"spelar ingen roll".equals(prefs.fuelType()))
                ? " Drivmedel: " + prefs.fuelType() + "." : "";
        String transmissionLine = (prefs.transmission() != null && !prefs.transmission().isBlank()
                && !"spelar ingen roll".equals(prefs.transmission()))
                ? " Växellåda: " + prefs.transmission() + " – rekommendera endast bilar med denna växellåda." : "";

        return """
                Budget: %s. Kategori: %s. Laddbox: %s. Körsträcka: %,d km/år (%s). Användning: %s. Passagerare: %d.%s%s
                """.formatted(
                budgetInfo, prefs.carCategory(), laddning,
                km, milprofil, prefs.usage(), prefs.passengers(), fuelLine, transmissionLine
        );
    }
}
