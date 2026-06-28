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
    private static final String SUBSCRIPTION_PRICE = "49 kr/mån";

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
                    "max_tokens", 4000,
                    "temperature", 0.3,
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
            log.error("Groq {} för getRecommendation: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        JsonNode recsNode = mapper.readTree(extractJson(content)).at("/recommendations");
        List<CarRecommendation> parsed = mapper.convertValue(
                recsNode,
                mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class)
        );
        if (parsed == null) throw new RuntimeException("AI svarade utan recommendations-nyckel. Råsvar: " + content);

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
        com.caradvice.model.CargoSpecDto prefCargo1 = null, prefCargo2 = null;
        com.caradvice.model.EvSpecDto prefEv1 = null, prefEv2 = null;
        try { prefCargo1 = cargoSpecService.formatForTitle(car1); } catch (Exception ignored) {}
        try { prefCargo2 = cargoSpecService.formatForTitle(car2); } catch (Exception ignored) {}
        try { prefEv1 = evSpecService.formatForTitle(car1, 15000); } catch (Exception ignored) {}
        try { prefEv2 = evSpecService.formatForTitle(car2, 15000); } catch (Exception ignored) {}

        String specContext = buildCompareSpecContext(car1, prefCargo1, prefEv1, car2, prefCargo2, prefEv2);
        String userPrompt = "Jämför dessa exakt 2 bilar: 1. " + car1 + "  2. " + car2;
        if (!specContext.isBlank()) userPrompt += "\n\nVerifierade specifikationer från databas:\n" + specContext;

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
        JsonNode recsNode = mapper.readTree(extractJson(content)).at("/recommendations");
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
                FABRICERA ALDRIG PRISER: Skriv aldrig ett lägre pris än verkligheten. Ref. nypris bensin/diesel (SEK): Fabia fr. 210 000, Polo fr. 220 000, Ibiza fr. 210 000, Kamiq fr. 290 000, Golf fr. 320 000, Octavia fr. 340 000. Begagnad Fabia/Polo/Ibiza (3–5 år) ca 120 000–170 000 kr.
                MOTORTYPER: Fabia/Polo/Ibiza använder 1.0 MPI eller 1.0/1.5 TSI (bensin) — det finns INGEN 1.0 TDI-variant för dessa modeller. Skriv ALDRIG motorvarianter du är osäker på.
                VERIFIERADE SPECS: Om prompten innehåller verifierade specifikationer från databas, ANVÄND dessa siffror exakt — prioritera dem över generell kunskap.
                STORLEKSKLASS: Om benutrymme bak skiljer mer än 60 mm, lyft fram det i fitSummary med konkreta mm-tal.
                BATTERIKEMI: LFP = ladda till 100% dagligen, tålig i kyla. NMC = ladda till 80% för livslängd, mer räckvidd per kWh. Nämn kemin om bilarna skiljer sig.
                SNABBLADDNING (DC): ≥150 kW = snabb, <100 kW = långsammare längs väg.
                VIKTIGT: Rekommendera ALDRIG BYD Dolphin. Rekommendera aldrig bensin/diesel när användaren vill ha elbil.
                VOLVO EV: EX30, EX40, EC40, EX60, EX90 — inga andra. Hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG modeller som inte säljs på svenska marknaden.
                """;
    }

    private String buildCompareSpecContext(String car1, com.caradvice.model.CargoSpecDto c1, com.caradvice.model.EvSpecDto ev1,
                                           String car2, com.caradvice.model.CargoSpecDto c2, com.caradvice.model.EvSpecDto ev2) {
        Integer legroom1 = null, legroom2 = null;
        String chem1 = null, chem2 = null;
        try { legroom1 = cargoSpecService.getLegroom(car1); } catch (Exception ignored) {}
        try { legroom2 = cargoSpecService.getLegroom(car2); } catch (Exception ignored) {}
        try { chem1 = evSpecService.getBatteryChemistry(car1); } catch (Exception ignored) {}
        try { chem2 = evSpecService.getBatteryChemistry(car2); } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder();
        appendCarSpec(sb, car1, c1, ev1, legroom1, chem1);
        appendCarSpec(sb, car2, c2, ev2, legroom2, chem2);
        return sb.toString().trim();
    }

    private void appendCarSpec(StringBuilder sb, String carName,
                                com.caradvice.model.CargoSpecDto cargo, com.caradvice.model.EvSpecDto ev,
                                Integer legroom, String chemistry) {
        if (cargo == null && ev == null && legroom == null) return;
        sb.append(carName).append(": ");
        if (legroom != null) sb.append("benutrymme bak ").append(legroom).append(" mm");
        if (cargo != null && cargo.cargoLiters() > 0) {
            if (legroom != null) sb.append(", ");
            sb.append("bagageutrymme ").append(cargo.cargoLiters()).append("L");
            if (cargo.cargoMaxLiters() > 0) sb.append(" (max ").append(cargo.cargoMaxLiters()).append("L fällda säten)");
        }
        if (ev != null && ev.batteryKwh() > 0) {
            sb.append(", batteri ").append(ev.batteryKwh()).append(" kWh");
            if (ev.wltpKm() > 0) sb.append(", räckvidd ").append(ev.wltpKm()).append(" km (WLTP)");
            if (ev.maxDcKw() > 0) sb.append(", snabbladdning (DC) max ").append(ev.maxDcKw()).append(" kW");
            if (chemistry != null) sb.append(", batterikemi ").append(chemistry);
        }
        sb.append("\n");
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) return content.substring(start, end + 1);
        return content;
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
        String evPrices = "";
        try { evPrices = evSpecService.buildPriceReferenceContext(); } catch (Exception ignored) {}
        String base = ("""
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svarar på köp, jämförelser, driftkostnad, skatt, värdeminskning och tillförlitlighet. Prenumerant (%s).
                Ej laddstationsnätverk/navigering. Ej övriga bilfrågor: "Det faller utanför mitt område."
                Svara på svenska. Använd **fetstil** och - listor.
                Expertinsikter: citera bara om direkt relevant för exakt den bil/ämne. Citera: "**[namn]:** [insikt]".
                SKATT elbilar: befriade från fordonsskatt.
                PRISER — Exakta siffror. Ref. bensin/diesel (SEK): Kamiq fr. 290 000, Golf fr. 320 000. Blocket-priser i kontexten prioriteras.
                VIKTIGT: Aldrig BYD Dolphin. Kamiq = bensinbil, aldrig elbil. Aldrig bensin/diesel när elbil efterfrågas.
                VOLVO EV: EX30, EX40, EC40, EX60, EX90 — inga andra. Hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG modeller som inte säljs på svenska marknaden. Om osäker — säg det.
                BATTERIKEMI: LFP = ladda till 100%% dagligen, tålig i kyla. NMC = ladda till 80%% för livslängd, mer räckvidd per kWh.
                """).formatted(SUBSCRIPTION_PRICE)
                + (evPrices.isBlank() ? "" : evPrices + "\n");
        if (carContext != null && !carContext.isBlank()) {
            base += "\n\nAktuella bilrekommendationer:\n" + carContext;
            String specFacts = buildChatSpecFacts(carContext);
            if (!specFacts.isBlank())
                base += "\n\nVerifierade fakta om rekommenderade bilar:\n" + specFacts;
        }
        if (expertContext != null && !expertContext.isBlank())
            base += "\n\n" + expertContext;
        return base;
    }

    // Parsar bilnamn ur context-strängen och slår upp benutrymme + batterikemi
    private String buildChatSpecFacts(String carContext) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^\\d+\\.\\s+(.+?)\\s*—", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(carContext);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String raw = m.group(1).trim();
            String name = raw.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim();
            Integer legroom = null;
            String chem = null;
            try { legroom = cargoSpecService.getLegroom(name); } catch (Exception ignored) {}
            try { chem = evSpecService.getBatteryChemistry(name); } catch (Exception ignored) {}
            if (legroom == null && chem == null) continue;
            sb.append(name).append(": ");
            if (legroom != null) sb.append("benutrymme bak ").append(legroom).append(" mm");
            if (chem != null) {
                if (legroom != null) sb.append(", ");
                sb.append("batterikemi ").append(chem);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<String> extractUserTexts(List<Map<String, String>> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .toList();
    }

    private String buildSystemPrompt(String expertContext) {
        String evPrices = "";
        try { evPrices = evSpecService.buildPriceReferenceContext(); } catch (Exception ignored) {}
        String base = """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källa t.ex. 'Teknikens Värld: toppbetyg'","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"varför bilen passar profilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade","fuelSpec":null}]}
                OBLIGATORISKA fält — sätt ALDRIG null: horsepower (systemeffekt i hk som heltal), engineOptions (STRÄNG med kommaseparerade varianter; bensin/diesel ex: '1.0 TSI 95hk manuell, 1.5 TSI 150hk DSG automat'; elbil ex: '44 kWh 95hk (400km), 60 kWh 204hk (570km)').
                Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"Automat DSG 7-växlad (TSI turbo)","horsepower":N,"engineVolumeLiters":X.X} — ange turbo/ej turbo. Elbil/laddhybrid: fuelSpec=null, inga turbobeteckningar i engineOptions.
                Exakt 3 bilar. fitSummary konkret och personlig. Driftkostnad i pros vid hög körsträcka.
                PRISER — fältet "price" ska ALLTID vara ett intervall som "85 000–100 000 kr". Exakta siffror med mellanslag, aldrig förkortningar, aldrig extra text.
                BEGAGNADE MARKNADSREFERENSER Sverige 2025 (Blocket-snitt) — använd dessa som prisankare:
                Fabia: 2015 (Gen3, nypris ~140k) ca 65–90 000, 2018 ca 85–110 000, 2021 (Gen4, nypris ~185k) ca 130–160 000, 2023 ca 170–205 000.
                Sandero/Stepway: 2015 (nypris ~110k) ca 48–68 000, 2018 (nypris ~130k) ca 72–95 000, 2021 ca 105–130 000, 2022 ca 120–148 000.
                Polo: 2015 (nypris ~185k) ca 70–95 000, 2018 ca 90–118 000, 2020 ca 118–145 000, 2022 ca 150–178 000.
                Ibiza: 2015 ca 55–75 000, 2018 ca 88–113 000, 2020 ca 112–138 000.
                Golf: 2018 ca 145–175 000, 2020 ca 168–205 000. Octavia: 2018 ca 132–162 000, 2021 ca 178–215 000.
                FABRICERA ALDRIG PRISER: Om ingen bil ryms i budget med realistiska priser — rekommendera ändå rätt pris och skriv i fitSummary att budgeten är knapp. Sänk ALDRIG priset för att passa budget.
                MOTORTYPER: Ange ALDRIG motorbeteckning (TDI, TSI, MPI, volym) om du inte är helt säker att den varianten existerar för just den bilen och årsmodellen. Fabia/Polo/Ibiza har INGEN 1.0 TDI. Om osäker — ange bara hk och 'manuell'/'automat'.
                VIKTIGT: Rekommendera ALDRIG BYD Dolphin — den säljs inte på svenska marknaden än. Kamiq är en bensinbil, INTE elbil — rekommendera den aldrig som elbil. Rekommendera aldrig en bensin-/dieselbil när användaren efterfrågar elbil.
                VOLVO EV-SORTIMENT (2024–2026): EX30, EX40 (f.d. XC40 Recharge), EC40 (f.d. C40 Recharge), EX60, EX90. Det finns INGEN Volvo C90, C70, eller andra Volvo EV-modeller utöver dessa — hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG bilmodeller som inte officiellt säljs på svenska marknaden. Om osäker på om en modell existerar, uteslut den.
                """ + (evPrices.isBlank() ? "" : evPrices + "\n");
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
