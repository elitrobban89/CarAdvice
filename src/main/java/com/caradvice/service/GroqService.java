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
    private static final String DEPRECIATION_RULE =
            "NYPRIS PER GENERATION: Se \"ICE-nypris\"-tabellen nedan. Begagnatpris = nypris (för bilens generation) × koefficient: ×0.85 (1år), ×0.75 (2år), ×0.65 (3år), ×0.57 (4år), ×0.50 (5år), ×0.44 (6år), ×0.39 (7år), ×0.34 (8+år).";

    public record Result(List<CarRecommendation> recommendations, boolean fromCache, long cacheAgeSeconds) {}

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:qwen/qwen3.6-27b}")
    private String model;

    @Value("${groq.chat.model:openai/gpt-oss-20b}")
    private String chatModel;

    private final ExpertInsightService expertInsightService;
    private final SafetyRatingService safetyRatingService;
    private final EvSpecService evSpecService;
    private final CargoSpecService cargoSpecService;
    private final BlocketPriceService blocketPriceService;
    private final NewCarPriceService newCarPriceService;

    public GroqService(ExpertInsightService expertInsightService, SafetyRatingService safetyRatingService,
                       EvSpecService evSpecService, CargoSpecService cargoSpecService,
                       BlocketPriceService blocketPriceService, NewCarPriceService newCarPriceService) {
        this.expertInsightService = expertInsightService;
        this.safetyRatingService = safetyRatingService;
        this.evSpecService = evSpecService;
        this.cargoSpecService = cargoSpecService;
        this.blocketPriceService = blocketPriceService;
        this.newCarPriceService = newCarPriceService;
    }

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final long CACHE_TTL_MS = 4 * 60 * 60 * 1000;
    private static final int MAX_CACHE_SIZE = 200;
    private static final long PRICES_TTL_MS = 60 * 60 * 1000;
    private static final int CHAT_MAX_HISTORY = 8;

    private volatile String cachedIcePrices = "";
    private volatile String cachedEvPrices = "";
    private volatile long pricesCachedAt = 0L;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(List<CarRecommendation> result, long timestamp) {}

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private HttpRequest buildRequest(Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private HttpResponse<String> callGroqWithFallback(Object primaryBody, Object fallbackBody) throws Exception {
        HttpResponse<String> resp = httpClient.send(buildRequest(primaryBody), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 429) return resp;
        return httpClient.send(buildRequest(fallbackBody), HttpResponse.BodyHandlers.ofString());
    }

    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear) {
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
            try {
                evSpec = evSpecService.formatForTitle(r.title(), kmPerYear);
                // Drop EV/PHEV match if the title year predates the technology
                if (evSpec != null) {
                    Matcher ym = Pattern.compile("\\((\\d{4})\\)").matcher(r.title());
                    if (ym.find()) {
                        int titleYear = Integer.parseInt(ym.group(1));
                        boolean isPhev = "PHEV".equals(evSpec.carType());
                        if (isPhev && titleYear < 2014) evSpec = null;       // PHEVs before 2014 don't exist
                        else if (!isPhev && titleYear < 2011) evSpec = null; // consumer EVs before 2011 don't exist
                    }
                }
            } catch (Exception ignored) {}
            try { cargo = cargoSpecService.formatForTitle(r.title()); } catch (Exception ignored) {}
            try { blocketPrice = blocketFutures.get(i).get(6, TimeUnit.SECONDS); } catch (Exception ignored) {}
            result.add(new CarRecommendation(
                    r.title(), r.price(), r.whyRecommended(), r.pros(), r.con(),
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, r.fuelSpec(), blocketPrice, r.horsepower(), r.engineOptions()));
        }
        return result;
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
        String systemPrompt = buildSystemPrompt(expertContext);

        Map<String, Object> primaryBody = Map.of(
                "model", model, "max_tokens", 2000, "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", "/no_think\n" + prompt)));

        Map<String, Object> fallbackBody = Map.of(
                "model", chatModel, "max_tokens", 2000, "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", prompt)));

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody);

        if (response.statusCode() == 429) {
            if (cached != null) {
                long ageSeconds = (System.currentTimeMillis() - cached.timestamp()) / 1000;
                return new Result(cached.result(), true, ageSeconds);
            }
            throw new RuntimeException(buildRateLimitError(response.body()));
        }
        if (response.statusCode() != 200) {
            log.error("Groq {} för getRecommendation: {}", response.statusCode(), response.body());
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        if (content.isBlank()) {
            String finishReason = json.at("/choices/0/finish_reason").asText("unknown");
            log.warn("Groq returned empty content for getRecommendation, finish_reason={}", finishReason);
            throw new RuntimeException("AI-tjänsten returnerade tomt svar. Försök igen.");
        }
        List<CarRecommendation> parsed = parseRecommendations(content);

        List<CarRecommendation> result = enrichRecommendations(parsed, prefs.kmPerYear());
        evictIfNeeded();
        cache.put(key, new CacheEntry(result, System.currentTimeMillis()));
        return new Result(result, false, 0);
    }

    private synchronized void refreshPricesIfNeeded() {
        if (System.currentTimeMillis() - pricesCachedAt < PRICES_TTL_MS) return;
        try { cachedIcePrices = newCarPriceService.buildPriceReferenceContext(); } catch (Exception e) { cachedIcePrices = ""; }
        try { cachedEvPrices = evSpecService.buildPriceReferenceContext(); } catch (Exception e) { cachedEvPrices = ""; }
        pricesCachedAt = System.currentTimeMillis();
    }

    private String getIcePrices() { refreshPricesIfNeeded(); return cachedIcePrices; }
    private String getEvPrices()  { refreshPricesIfNeeded(); return cachedEvPrices; }

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
        String compareCacheKey = "compare|" + car1 + "|" + car2;
        CacheEntry cachedCompare = cache.get(compareCacheKey);
        if (cachedCompare != null && System.currentTimeMillis() - cachedCompare.timestamp() < CACHE_TTL_MS)
            return cachedCompare.result();

        com.caradvice.model.CargoSpecDto prefCargo1 = null, prefCargo2 = null;
        com.caradvice.model.EvSpecDto prefEv1 = null, prefEv2 = null;
        try { prefCargo1 = cargoSpecService.formatForTitle(car1); } catch (Exception ignored) {}
        try { prefCargo2 = cargoSpecService.formatForTitle(car2); } catch (Exception ignored) {}
        try { prefEv1 = evSpecService.formatForTitle(car1, 15000); } catch (Exception ignored) {}
        try { prefEv2 = evSpecService.formatForTitle(car2, 15000); } catch (Exception ignored) {}

        String specContext = buildCompareSpecContext(car1, prefCargo1, prefEv1, car2, prefCargo2, prefEv2);
        String userPrompt = "Jämför dessa exakt 2 bilar: 1. " + car1 + "  2. " + car2;
        if (!specContext.isBlank()) userPrompt += "\n\nVerifierade specifikationer från databas:\n" + specContext;
        String compareSystemPrompt = buildCompareSystemPrompt();

        Map<String, Object> primaryBody = Map.of(
                "model", model, "max_tokens", 2000, "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", compareSystemPrompt),
                        Map.of("role", "user", "content", "/no_think\n" + userPrompt)));

        Map<String, Object> fallbackBody = Map.of(
                "model", chatModel, "max_tokens", 2000, "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", compareSystemPrompt),
                        Map.of("role", "user", "content", userPrompt)));

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody);

        if (response.statusCode() == 429)
            throw new RuntimeException(buildRateLimitError(response.body()));
        if (response.statusCode() != 200)
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ". Försök igen om en stund.");

        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        if (content.isBlank()) {
            String finishReason = json.at("/choices/0/finish_reason").asText("unknown");
            log.warn("Groq returned empty content for compareSpecific, finish_reason={}", finishReason);
            throw new RuntimeException("AI-tjänsten returnerade tomt svar. Försök igen.");
        }
        List<CarRecommendation> parsed = parseRecommendations(content);

        List<CarRecommendation> result = enrichRecommendations(parsed, 15000);
        evictIfNeeded();
        cache.put(compareCacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    private String buildCompareSystemPrompt() {
        String icePrices = getIcePrices();
        return """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Jämför EXAKT de 2 bilar användaren anger. Svara ENDAST med JSON (EXAKT 2 bilar):
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"bilens styrka","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"vem passar bilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade; elbil: '51 kWh 170hk (420km)'","fuelSpec":null}]}
                OBLIGATORISKT: horsepower (systemeffekt i hk som heltal, ALDRIG null — elbil ex: EX30=200hk, Model Y=300hk). Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"typ (turbo/ej)","horsepower":N,"engineVolumeLiters":X.X}. Elbil/laddhybrid: fuelSpec=null, inga turbobeteckningar.
                Ange exakt årsmodell. Svara på svenska.
                PRISER — fältet "price" ska ALLTID vara ett intervall som "280 000–320 000 kr". Exakta siffror med mellanslag, aldrig förkortningar, aldrig extra text.
                %s
                FABRICERA ALDRIG PRISER: Skriv aldrig ett lägre pris än verkligheten.
                MOTORTYPER: Skriv ALDRIG motorbeteckning du inte är helt säker på existerar för just den bilen och årsmodellen.
                VERIFIERADE SPECS: Om prompten innehåller verifierade specifikationer från databas, ANVÄND dessa siffror exakt — prioritera dem över generell kunskap.
                STORLEKSKLASS: Om benutrymme bak skiljer mer än 60 mm, lyft fram det i fitSummary med konkreta mm-tal.
                BATTERIKEMI: LFP = ladda till 100%% dagligen, tålig i kyla. NMC = ladda till 80%% för livslängd, mer räckvidd per kWh. Nämn kemin om bilarna skiljer sig.
                SNABBLADDNING (DC): ≥150 kW = snabb, <100 kW = långsammare längs väg.
                VIKTIGT: Rekommendera ALDRIG BYD Dolphin. Rekommendera aldrig bensin/diesel när användaren vill ha elbil.
                VOLVO EV: EX30, EX40, EC40, EX60, EX90 — inga andra. Hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG modeller som inte säljs på svenska marknaden.
                """.formatted(DEPRECIATION_RULE)
                + (icePrices.isBlank() ? "" : icePrices + "\n");
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
        // Strip <think>...</think> blocks produced by qwen reasoning models
        String cleaned = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) return cleaned.substring(start, end + 1);
        return cleaned;
    }

    private List<CarRecommendation> parseRecommendations(String content) throws Exception {
        String jsonStr = extractJson(content);
        JsonNode root;
        try {
            root = mapper.readTree(jsonStr);
        } catch (Exception e) {
            log.warn("AI returned truncated/invalid JSON (len={}): {}", content.length(), e.getMessage());
            throw new RuntimeException("AI-svaret blev ofullständigt. Försök igen.");
        }
        // Try standard key first, then common fallbacks AI sometimes uses
        for (String key : new String[]{"recommendations", "cars", "bilar", "results", "items"}) {
            JsonNode node = root.get(key);
            if (node != null && node.isArray() && !node.isEmpty()) {
                List<CarRecommendation> parsed = mapper.convertValue(
                        node, mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class));
                if (parsed != null && !parsed.isEmpty()) return parsed;
            }
        }
        // Last resort: if root itself is an array
        if (root.isArray() && !root.isEmpty()) {
            List<CarRecommendation> parsed = mapper.convertValue(
                    root, mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class));
            if (parsed != null && !parsed.isEmpty()) return parsed;
        }
        log.warn("AI returned no parseable recommendations. Raw: {}", content);
        throw new RuntimeException("AI:n returnerade ett oväntat svar. Försök igen.");
    }

    private String buildCacheKey(CarPreferences prefs) {
        return prefs.budget() + "|" + prefs.carCategory() + "|" + prefs.hasCharger() + "|" +
               prefs.kmPerYear() + "|" + prefs.usage() + "|" + prefs.passengers() + "|" + prefs.newCar() + "|" +
               (prefs.fuelType() != null ? prefs.fuelType() : "") + "|" +
               (prefs.transmission() != null ? prefs.transmission() : "") + "|" +
               (prefs.budgetType() != null ? prefs.budgetType() : "köp") + "|" +
               (prefs.maxAgeYears() != null ? prefs.maxAgeYears() : "");
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

        List<Map<String, String>> history = messages.size() > CHAT_MAX_HISTORY
                ? messages.subList(messages.size() - CHAT_MAX_HISTORY, messages.size()) : messages;
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", systemPrompt));
        msgs.addAll(history);

        Map<String, Object> primaryBody = Map.of("model", chatModel, "max_tokens", 1800, "temperature", 0.5, "messages", msgs);
        Map<String, Object> fallbackBody = Map.of("model", model, "max_tokens", 1800, "temperature", 0.5, "messages", msgs);

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody);

        if (response.statusCode() == 429)
            throw new RuntimeException("AI-tjänsten är tillfälligt överbelastad. Försök igen om en stund.");
        if (response.statusCode() != 200)
            throw new RuntimeException("Groq svarade " + response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        return json.at("/choices/0/message/content").asText("Inget svar.");
    }

    public InputStream chatStream(List<Map<String, String>> messages, String carContext) throws Exception {
        String expertContext = "";
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages)); } catch (Exception ignored) {}
        String systemPrompt = buildChatSystemPrompt(carContext, expertContext);

        List<Map<String, String>> history = messages.size() > CHAT_MAX_HISTORY
                ? messages.subList(messages.size() - CHAT_MAX_HISTORY, messages.size()) : messages;
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", systemPrompt));
        msgs.addAll(history);

        Map<String, Object> primaryBody = Map.of("model", chatModel, "max_tokens", 1800, "temperature", 0.5, "stream", true, "messages", msgs);
        Map<String, Object> fallbackBody = Map.of("model", model, "max_tokens", 1800, "temperature", 0.5, "stream", true, "messages", msgs);

        HttpResponse<InputStream> response = httpClient.send(buildRequest(primaryBody), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 429)
            response = httpClient.send(buildRequest(fallbackBody), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 401) throw new RuntimeException("AI-tjänsten är inte korrekt konfigurerad.");
        if (response.statusCode() == 429) throw new RuntimeException("För många frågor — vänta en minut och försök igen.");
        if (response.statusCode() != 200) throw new RuntimeException("Groq svarade " + response.statusCode());
        return response.body();
    }

    private String buildChatSystemPrompt(String carContext, String expertContext) {
        String icePrices = getIcePrices();
        String evPrices = getEvPrices();
        String base = ("""
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svarar på köp, jämförelser, driftkostnad, skatt, värdeminskning och tillförlitlighet. Prenumerant (%s).
                Ej laddstationsnätverk/navigering. Ej övriga bilfrågor: "Det faller utanför mitt område."
                Svara på svenska. Använd **fetstil** och - listor.
                Expertinsikter: citera bara om direkt relevant för exakt den bil/ämne. Citera: "**[namn]:** [insikt]".
                SKATT elbilar: befriade från fordonsskatt.
                PRISER — Exakta siffror. Blocket-priser i kontexten prioriteras. Beräkna begagnatpris från rätt generations-nypris (se ICE-tabell) × deprecieringskoefficient.
                MOTORTYPER: Ange ALDRIG motorbeteckning om du inte är helt säker. Om osäker — ange bara hk och 'manuell'/'automat'.
                VIKTIGT: Aldrig BYD Dolphin. Kamiq = bensinbil, aldrig elbil. Aldrig bensin/diesel när elbil efterfrågas.
                VOLVO EV: EX30, EX40, EC40, EX60, EX90 — inga andra. Hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG modeller som inte säljs på svenska marknaden. Om osäker — säg det.
                BATTERIKEMI: LFP = ladda till 100%% dagligen, tålig i kyla. NMC = ladda till 80%% för livslängd, mer räckvidd per kWh.
                """).formatted(SUBSCRIPTION_PRICE)
                + (icePrices.isBlank() ? "" : icePrices + "\n")
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
        String icePrices = getIcePrices();
        String evPrices = getEvPrices();
        String base = """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källa t.ex. 'Teknikens Värld: toppbetyg'","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"varför bilen passar profilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade","fuelSpec":null}]}
                OBLIGATORISKA fält — sätt ALDRIG null: horsepower (systemeffekt i hk som heltal), engineOptions (STRÄNG med kommaseparerade varianter; bensin/diesel ex: '1.0 TSI 95hk manuell, 1.5 TSI 150hk DSG automat'; elbil ex: '44 kWh 95hk (400km), 60 kWh 204hk (570km)').
                Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"Automat DSG 7-växlad (TSI turbo)","horsepower":N,"engineVolumeLiters":X.X} — ange turbo/ej turbo. Elbil/laddhybrid: fuelSpec=null, inga turbobeteckningar i engineOptions.
                Exakt 3 bilar. fitSummary konkret och personlig. Driftkostnad i pros vid hög körsträcka.
                PRISER — fältet "price" ska ALLTID vara ett intervall som "85 000–100 000 kr". Exakta siffror med mellanslag, aldrig förkortningar, aldrig extra text.
                """ + DEPRECIATION_RULE + "\n" + """
                FABRICERA ALDRIG PRISER: Priset i "price"-fältet = nypris × ålderskoefficient (se NYPRIS-regel). Kontrollera alltid mot nypristabellen. Exempel: Octavia 2021+ nypris 340 000 kr, 3 år gammal → 340 000×0.65=221 000 kr – kan ALDRIG säljas för 100 000 kr. Om budget inte räcker: välj en ANNAN BIL (billigare modell, äldre generation, eller lägre segment) – sänk ALDRIG priset på en bil för att passa budgeten. Skriv i fitSummary om budget är knapp.
                MOTORTYPER: Ange ALDRIG motorbeteckning (TDI, TSI, MPI, volym) om du inte är helt säker på att varianten existerar. Om osäker — ange bara hk och 'manuell'/'automat'.
                VIKTIGT: Rekommendera ALDRIG BYD Dolphin — den säljs inte på svenska marknaden än. Kamiq är en bensinbil, INTE elbil — rekommendera den aldrig som elbil. Rekommendera aldrig en bensin-/dieselbil när användaren efterfrågar elbil.
                PHEV/LADDHYBRID: Laddhybrider (PHEV) existerade inte i stor skala före 2014. Golf GTE (laddhybrid) lanserades 2014 — rekommendera ALDRIG Golf (2013) eller äldre som laddhybrid. Outlander PHEV: 2013+. Passat GTE: 2015+. C-MAX Energi: 2013. Rekommendera ALDRIG en modell som laddhybrid om årsmodellen är äldre än modellens faktiska PHEV-lansering.
                VOLVO EV-SORTIMENT (2024–2026): EX30, EX40 (f.d. XC40 Recharge), EC40 (f.d. C40 Recharge), EX60, EX90. Det finns INGEN Volvo C90, C70, eller andra Volvo EV-modeller utöver dessa — hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG bilmodeller som inte officiellt säljs på svenska marknaden. Om osäker på om en modell existerar, uteslut den.
                """ + (icePrices.isBlank() ? "" : icePrices + "\n")
                    + (evPrices.isBlank() ? "" : evPrices + "\n");
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
        int currentYear = java.time.Year.now().getValue();
        String maxAgeLine = (!prefs.newCar() && prefs.maxAgeYears() != null)
                ? " ÅLDERSKRAV: Max " + prefs.maxAgeYears() + " år — ENDAST årsmodell " +
                  (currentYear - prefs.maxAgeYears()) + " eller nyare accepteras. En " +
                  (currentYear - prefs.maxAgeYears() - 1) + " eller äldre bil är FELAKTIG och ska ALDRIG rekommenderas." +
                  " Ange ALLTID ett specifikt år i title-fältet, t.ex. \"Dacia Sandero (" + (currentYear - 1) + ")\" — ALDRIG \"(2021+)\" eller liknande generationsnotation." : "";

        return """
                Budget: %s. Kategori: %s. Laddbox: %s. Körsträcka: %,d km/år (%s). Användning: %s. Passagerare: %d.%s%s%s
                """.formatted(
                budgetInfo, prefs.carCategory(), laddning,
                km, milprofil, prefs.usage(), prefs.passengers(), fuelLine, transmissionLine, maxAgeLine
        );
    }
}
