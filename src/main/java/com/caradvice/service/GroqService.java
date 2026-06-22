package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.CarRecommendation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final long CACHE_TTL_MS = 2 * 60 * 60 * 1000;
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
                "max_tokens", 1500,
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
                    "max_tokens", 1500,
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
                throw new RuntimeException("Dagsgränsen för AI-anrop är nådd. Försök igen om " + parseRetryTime(response.body()) + ".");
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
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, r.fuelSpec(), blocketPrice));
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
                "max_tokens", 1800,
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
                    "max_tokens", 1800,
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
                throw new RuntimeException("Dagsgränsen för AI-anrop är nådd. Försök igen om " + parseRetryTime(response.body()) + ".");
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
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, r.fuelSpec(), blocketPrice));
        }
        return result;
    }

    private String buildCompareSystemPrompt() {
        return """
                Svensk bilrådgivare, svenska marknaden 2025–2026. Du jämför exakt de 2 bilar som användaren anger.
                Svara ENDAST med JSON i exakt detta format (EXAKT 2 bilar i arrayen):
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"en mening om bilens styrka, t.ex. referens från Teknikens Värld","pros":["fördel1","fördel2","fördel3"],"con":"nackdel","fitSummary":"en mening om vem bilen passar","expertOpinion":"Bilexpertens syn på bilen — max 2 meningar om körkänsla, tillförlitlighet och begagnatvärde. Nämn INTE listpris — Blocket-pris visas separat.","fuelSpec":null}]}
                För bensin- och dieselbilar: sätt "fuelSpec":{"consumptionLiterPerMil":X.X,"gearbox":"Automat 7-växlad (turbo)","horsepower":150,"engineVolumeLiters":1.5} med verkliga värden.
                För elbil och laddhybrid: sätt "fuelSpec":null.
                Ange alltid exakt årsmodell i title (t.ex. "MG ZS EV (2024)"), välj den vanligaste varianten på svenska marknaden.
                Returnera EXAKT 2 bilar — inte fler, inte färre. Svara på svenska.

                KRITISKT — PRISER: Använd ALDRIG påhittade eller orimligt låga priser.
                Verifierade svenska nyprisintervall 2025–2026:
                - Dacia Spring ~195 000 kr, BYD Dolphin ~300 000–340 000 kr, MG4 ~330 000–365 000 kr
                - MG ZS EV ~290 000–350 000 kr, Kia Niro EV ~380 000–430 000 kr
                - Kia EV3 ~430 000 kr, Volvo EX30 ~430 000 kr, Tesla Model 3 ~427 000 kr
                - Polestar 2 ~609 000 kr, Tesla Model Y Long Range ~600 000 kr, Polestar 4 ~660 000 kr
                Begagnad 1 år: nypris × 0.85. Begagnad 2 år: nypris × 0.75.
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
                Du är en svensk bilrådgivare för bensin-, diesel-, hybrid- och elbilar på den svenska marknaden 2025–2026.

                Du svarar på frågor om köp, jämförelser, driftkostnader, försäkring, skatt, värdeminskning, räckvidd och tillförlitlighet.

                Du hjälper INTE med att hitta närmaste laddstation, realtidsladdning eller navigering.
                Om användaren frågar om sådant svarar du: "Det kan jag inte hjälpa med här — för att hitta laddstationer rekommenderar jag elbilsladdning-appen."
                Om frågan inte handlar om bilar alls svarar du: "Det faller utanför mitt område — jag är specialiserad på bilköp och bilrådgivning."

                Svara alltid på svenska. Var konkret och hjälpsam. Använd **fetstil** och listor med - för struktur.
                När du har expertinsikter som är relevanta för frågan, inkludera dem tydligt med attributionen "**[expertnamn]:** [insikt]" i slutet av ditt svar — använd det namn som anges i insikten.

                VIKTIGT — nämn ALDRIG specifika obligatoriska årsavgifter, abonnemangsavgifter eller månadsavgifter som är knutna till ett specifikt leasingavtal eller erbjudande. Sådana avgifter gäller inte generellt för bilen och är missvisande. Tala enbart om allmänna ägandekostnader (skatt, försäkring, drivmedel, service).

                PRISER — använd aldrig orimligt låga priser. Verifierade svenska nyprisintervall 2025–2026:
                Budget-elbilar: Dacia Spring ~195 000 kr, BYD Dolphin ~300 000–340 000 kr, MG4 ~330 000–365 000 kr.
                Mellanklass-elbilar: Kia EV3 ~430 000 kr, Volvo EX30 ~430 000 kr, Tesla Model 3 ~427 000 kr, BYD Seal ~400 000–460 000 kr.
                Premium-elbilar: Polestar 2 ~609 000 kr, Tesla Model Y Long Range ~600 000 kr, Polestar 4 ~660 000 kr.
                Om bilrekommendationer med Blocket-priser finns i kontexten nedan — prioritera de priserna framför dina egna uppskattningar.
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
                Svensk bilrådgivare, svenska marknaden 2025–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källhänvisning till tidning eller test t.ex. 'Teknikens Värld: toppbetyg i klassen' eller 'Vi Bilägare: bäst i test'","pros":["fördel1","fördel2","fördel3"],"con":"nackdel","fitSummary":"varför just denna bil passar denna specifika persons profil","expertOpinion":"Bilexpertens syn på denna bil — max 2 meningar om körkänsla, tillförlitlighet och verkligt ägarvärde på begagnatmarknaden. Nämn INTE listpris eller nypris — Blocket-pris visas redan separat. Basera på expertinsikterna om de finns, annars generell bedömning.","fuelSpec":null}]}
                För bensin- och dieselbilar: sätt "fuelSpec":{"consumptionLiterPerMil":X.X,"gearbox":"Automat DSG 7-växlad (TSI turbo)","horsepower":150,"engineVolumeLiters":1.5} med verkliga värden för den rekommenderade varianten. Ange alltid om motorn är turboladdad eller atmosfärisk i gearbox-strängen, t.ex. "Manuell 5-växlad (ej turbo)", "Automat DSG 7-växlad (TSI turbo)", "CVT (ej turbo)". För elbil och laddhybrid: sätt "fuelSpec":null.
                Exakt 3 bilar. Fördelar specifika för profilen. Driftkostnad i pros vid hög körsträcka. fitSummary konkret och personlig. expertOpinion alltid på svenska.
                Motorvariant: ange alltid exakt vilken motor och växellåda du rekommenderar (t.ex. "1.0 TSI 110hk DSG" inte bara "Skoda Fabia"). Om bilen finns i populär alternativvariant (t.ex. manuell 75hk och automat 110hk TSI), nämn det i pros med prisskillnad: "Finns även som 110hk TSI DSG-automat (+20 000 kr, turbo)".

                KRITISKT — PRISER: Använd ALDRIG påhittade eller orimligt låga priser. Priset i fältet "price" ska vara det verkliga svenska marknadspriset på Blocket/Bytbil för den aktuella modellen och årsmodellen, med formatet "X–Y kr" eller "X kr/mån" (leasing). En begagnad bil kostar ungefär: nypris × 0,85 (1 år), × 0,75 (2 år), × 0,65 (3 år). Välj årsmodell som faktiskt ryms i budgeten.

                Verifierade nyprisintervall för elbilar på svenska marknaden 2025–2026:
                - Budget (under 350 000 kr ny): Dacia Spring (~195 000 kr), BYD Dolphin (~300 000–340 000 kr), MG4 (~330 000–365 000 kr, kampanjpris ibland lägre)
                - Mellanklass (350 000–550 000 kr ny): Kia EV3 (~430 000 kr), Volvo EX30 (~430 000 kr), Tesla Model 3 (~427 000 kr), Kia EV6, Hyundai IONIQ 5, Volkswagen ID.4, Toyota bZ4X, BYD Seal (~400 000–460 000 kr)
                - Premium (550 000+ kr ny): Polestar 2 (~609 000 kr), Tesla Model Y Long Range (~600 000 kr), Volvo EX40, Hyundai IONIQ 6, Polestar 3, Polestar 4 (~660 000 kr), Audi Q6 e-tron, BMW iX1, Volvo EX60
                OBS: Kia EV3 och Volvo EX30 kostar ~430 000 kr ny och ~365 000 kr begagnad (1 år) — rekommendera dem ALDRIG under 300 000 kr. En 2024 Kia EV3 kostar INTE 179 000 kr — sådana priser är felaktiga och ska aldrig användas.
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
