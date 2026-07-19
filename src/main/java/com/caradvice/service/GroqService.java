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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Value("${groq.model:openai/gpt-oss-120b}")
    private String model;

    @Value("${groq.chat.model:openai/gpt-oss-20b}")
    private String chatModel;

    // Reservmodell för rekommendationer/jämförelser: tredje 429-utväg (egen TPM-pott hos Groq)
    // och omförsöksmodell när svaret kom tillbaka trunkerat/tomt.
    // qwen3.6-27b är preview-tier hos Groq ("evaluation only") — därför reserv, inte primär.
    // Bevakas av hälsokollen så en avveckling larmar via UptimeRobot.
    @Value("${groq.reserve.model:qwen/qwen3.6-27b}")
    private String reserveModel;

    // Extra modeller som hälsokollen bevakar utöver de egna — Tag/VaderKlader kör gpt-oss-120b
    // men saknar egen /health/groq, så avveckling larmas härifrån
    @Value("${groq.watched.models:openai/gpt-oss-120b}")
    private String watchedModels;

    private final ExpertInsightService expertInsightService;
    private final SafetyRatingService safetyRatingService;
    private final EvSpecService evSpecService;
    private final CargoSpecService cargoSpecService;
    private final BlocketPriceService blocketPriceService;
    private final NewCarPriceService newCarPriceService;
    private final FeedbackService feedbackService;
    private final IceConsumptionService iceConsumptionService;
    private final FuelPriceService fuelPriceService;

    public GroqService(ExpertInsightService expertInsightService, SafetyRatingService safetyRatingService,
                       EvSpecService evSpecService, CargoSpecService cargoSpecService,
                       BlocketPriceService blocketPriceService, NewCarPriceService newCarPriceService,
                       FeedbackService feedbackService, IceConsumptionService iceConsumptionService,
                       FuelPriceService fuelPriceService) {
        this.expertInsightService = expertInsightService;
        this.safetyRatingService = safetyRatingService;
        this.evSpecService = evSpecService;
        this.cargoSpecService = cargoSpecService;
        this.blocketPriceService = blocketPriceService;
        this.newCarPriceService = newCarPriceService;
        this.feedbackService = feedbackService;
        this.iceConsumptionService = iceConsumptionService;
        this.fuelPriceService = fuelPriceService;
    }

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models";
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

    /** qwen3.6 stöder "none" (stänger av reasoning helt); gpt-oss tar bara low/medium/high. */
    static String reasoningEffortFor(String modelName) {
        return modelName.startsWith("openai/") ? "low" : "none";
    }

    private HttpRequest buildRequest(Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private HttpResponse<String> callGroqWithFallback(Object... bodies) throws Exception {
        HttpResponse<String> resp = null;
        for (Object body : bodies) {
            resp = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 429) return resp;
        }
        return resp;
    }

    private Map<String, Object> jsonCallBody(String modelName, double temperature, String systemPrompt, String userPrompt) {
        return Map.of(
                "model", modelName, "max_tokens", 3000, "temperature", temperature,
                "reasoning_effort", reasoningEffortFor(modelName),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
    }

    private List<CarRecommendation> extractAndParse(HttpResponse<String> response, String label) throws Exception {
        JsonNode json = mapper.readTree(response.body());
        String content = json.at("/choices/0/message/content").asText();
        if (content.isBlank())
            content = json.at("/choices/0/message/reasoning").asText();
        if (content.isBlank()) {
            String finishReason = json.at("/choices/0/finish_reason").asText("unknown");
            log.warn("Groq empty content {} finish_reason={} body={}", label, finishReason, response.body());
            throw new RuntimeException("AI-tjänsten returnerade tomt svar. Försök igen.");
        }
        return parseRecommendations(content);
    }

    /** Tolkar svaret; vid tomt/trunkerat svar görs ETT omförsök med reservmodellen innan felet släpps ut. */
    private List<CarRecommendation> parseWithRetry(HttpResponse<String> response, Object reserveBody, String label) throws Exception {
        return parseWithRetry(response, reserveBody, label, null);
    }

    /** Som ovan, men med extra regelvalidering (t.ex. familjestorlek) som också triggar omförsöket. */
    private List<CarRecommendation> parseWithRetry(HttpResponse<String> response, Object reserveBody, String label,
                                                   java.util.function.Consumer<List<CarRecommendation>> validator) throws Exception {
        try {
            List<CarRecommendation> parsed = extractAndParse(response, label);
            if (validator != null) validator.accept(parsed);
            return parsed;
        } catch (RuntimeException first) {
            log.warn("{}: ofullständigt/tomt svar — omförsök med {}", label, reserveModel);
            HttpResponse<String> retry = httpClient.send(buildRequest(reserveBody), HttpResponse.BodyHandlers.ofString());
            if (retry.statusCode() != 200) throw first;
            try {
                List<CarRecommendation> parsed = extractAndParse(retry, label + " (omförsök)");
                if (validator != null) validator.accept(parsed);
                return parsed;
            } catch (RuntimeException second) {
                throw first;
            }
        }
    }

    /**
     * Skarpt läge: AI:n satte 200 000–210 000 kr på en Kia EV6 som på Blocket börjar vid 333 500 kr.
     * Ligger AI:ns intervall helt under eller helt över Blockets årsfiltrerade annonsintervall
     * ersätts det med Blocket-intervallet — verkligheten vinner över deprecieringskalkylen.
     * Minst 3 annonser krävs så att enstaka fynd/felannonser inte skriver över rimliga priser.
     */
    static String correctedPrice(String aiPrice, BlocketPriceService.PriceRange blocket, String title) {
        if (aiPrice == null || blocket == null || blocket.count() < 3) return aiPrice;
        java.util.List<Long> nums = new ArrayList<>();
        Matcher m = Pattern.compile("\\d[\\d\\s\\u00a0]*").matcher(aiPrice);
        while (m.find()) {
            try { nums.add(Long.parseLong(m.group().replaceAll("[\\s\\u00a0]", ""))); } catch (NumberFormatException ignored) {}
        }
        if (nums.isEmpty()) return aiPrice;
        long aiMin = nums.get(0), aiMax = nums.get(nums.size() - 1);
        if (aiMax < blocket.minKr() || aiMin > blocket.maxKr()) {
            log.warn("AI-pris {} för {} utanför Blocket-intervallet {}–{} kr ({} annonser) — ersätter",
                    aiPrice, title, blocket.minKr(), blocket.maxKr(), blocket.count());
            return formatSekSpace(blocket.minKr()) + "–" + formatSekSpace(blocket.maxKr()) + " kr";
        }
        return aiPrice;
    }

    private static String formatSekSpace(int amount) {
        String s = String.valueOf(amount);
        StringBuilder sb = new StringBuilder();
        int start = s.length() % 3;
        if (start > 0) sb.append(s, 0, start);
        for (int i = start; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear) {
        return enrichRecommendations(parsed, kmPerYear, null, false);
    }

    /**
     * skipBlocket=true i leasingläge: Blocket är begagnatmarknad och irrelevant för leasing —
     * varken prisrad eller prissnapping ska baseras på den, och skrapanropen sparas in.
     */
    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean skipBlocket) {
        List<CompletableFuture<BlocketPriceService.PriceRange>> blocketFutures = parsed.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return skipBlocket ? null : blocketPriceService.fetchPriceRange(r.title());
                    } catch (Exception e) { return null; }
                }))
                .toList();

        List<CarRecommendation> result = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            CarRecommendation r = parsed.get(i);
            String safety = null;
            com.caradvice.model.EvSpecDto evSpec = null;
            com.caradvice.model.CargoSpecDto cargo = null;
            BlocketPriceService.PriceRange blocketRange = null;
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
            try { blocketRange = blocketFutures.get(i).get(6, TimeUnit.SECONDS); } catch (Exception ignored) {}
            String blocketPrice = blocketRange != null ? blocketRange.formatted() : null;
            String price = correctedPrice(r.price(), blocketRange, r.title());

            // Ersätt AI:ns gissade förbrukning med verifierad siffra från ice_consumption om matchning finns.
            // OBS enhetskonventionen: consumptionLiterPerMil bär l/100km (frontend delar med 10 vid visning
            // och räknar ägandekostnad på l/100km) — ice_consumption lagrar l/mil, därav ×10 här.
            com.caradvice.model.FuelSpecDto fuelSpec = r.fuelSpec();
            if (fuelSpec != null && fuelSpec.consumptionLiterPerMil() != null) {
                Double consumption = null;
                try {
                    Integer hp = fuelSpec.horsepower() != null ? fuelSpec.horsepower() : r.horsepower();
                    IceConsumptionService.Variant v = iceConsumptionService.consumptionForTitle(r.title(), hp, fuelPref);
                    if (v != null) consumption = v.literPerMil() * 10;
                } catch (Exception ignored) {}
                // Ingen verifierad match men AI:n svarade i l/mil-skala (< 3 kan inte vara l/100km) — normalisera
                if (consumption == null && fuelSpec.consumptionLiterPerMil() > 0
                        && fuelSpec.consumptionLiterPerMil() < 3) {
                    consumption = fuelSpec.consumptionLiterPerMil() * 10;
                }
                if (consumption != null) fuelSpec = new com.caradvice.model.FuelSpecDto(
                        consumption, fuelSpec.gearbox(), fuelSpec.horsepower(), fuelSpec.engineVolumeLiters());
            }

            result.add(new CarRecommendation(
                    r.title(), price, r.whyRecommended(), r.pros(), r.con(),
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, fuelSpec, blocketPrice, r.horsepower(), r.engineOptions()));
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
        String systemPrompt = withFuelPrices(buildSystemPrompt(expertContext, prefs.fuelType()));
        String feedbackContext = getFeedbackContext();
        if (!feedbackContext.isBlank()) systemPrompt = systemPrompt + "\n" + feedbackContext;

        Map<String, Object> primaryBody = jsonCallBody(model, 0.3, systemPrompt, prompt);
        Map<String, Object> fallbackBody = jsonCallBody(chatModel, 0.3, systemPrompt, prompt);
        Map<String, Object> reserveBody = jsonCallBody(reserveModel, 0.3, systemPrompt, prompt);

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody, reserveBody);

        if (response.statusCode() == 429) {
            if (cached != null) {
                long ageSeconds = (System.currentTimeMillis() - cached.timestamp()) / 1000;
                return new Result(cached.result(), true, ageSeconds);
            }
            throw new RuntimeException(buildRateLimitError(response.body()));
        }
        if (response.statusCode() != 200) {
            log.error("Groq {} för getRecommendation: {}", response.statusCode(), response.body());
            throw new RuntimeException(buildGroqErrorMessage(response.statusCode(), response.body()));
        }

        List<CarRecommendation> parsed = parseWithRetry(response, reserveBody, "getRecommendation",
                requiresFamilySizedCar(prefs) ? GroqService::requireFamilySizedCars : null);

        List<CarRecommendation> result = enrichRecommendations(parsed, prefs.kmPerYear(), prefs.fuelType(),
                "leasing".equals(prefs.budgetType()));
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

    // ── Feedback-loop: tummen ner-bilar injiceras som negativ signal i prompten ──

    private volatile String cachedFeedbackContext = "";
    private volatile long feedbackCachedAt = 0L;

    /** Netto minst 2 tummar ner ⇒ med på undvik-listan; max 10 bilar; uppdateras en gång/timme. */
    private String getFeedbackContext() {
        if (System.currentTimeMillis() - feedbackCachedAt >= PRICES_TTL_MS) {
            try {
                cachedFeedbackContext = buildFeedbackContext(feedbackService.dislikedCars(2, 10));
            } catch (Exception e) {
                cachedFeedbackContext = "";
            }
            feedbackCachedAt = System.currentTimeMillis();
        }
        return cachedFeedbackContext;
    }

    static String buildFeedbackContext(List<String> dislikedCars) {
        if (dislikedCars.isEmpty()) return "";
        return "ANVÄNDARFEEDBACK: Dessa bilar har fått övervägande tummen ner av användarna — "
                + "rekommendera dem BARA om inget likvärdigt alternativ finns: "
                + String.join(", ", dislikedCars);
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
        String compareSystemPrompt = withFuelPrices(buildCompareSystemPrompt());

        Map<String, Object> primaryBody = jsonCallBody(model, 0.2, compareSystemPrompt, userPrompt);
        Map<String, Object> fallbackBody = jsonCallBody(chatModel, 0.2, compareSystemPrompt, userPrompt);
        Map<String, Object> reserveBody = jsonCallBody(reserveModel, 0.2, compareSystemPrompt, userPrompt);

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody, reserveBody);

        if (response.statusCode() == 429)
            throw new RuntimeException(buildRateLimitError(response.body()));
        if (response.statusCode() != 200)
            throw new RuntimeException(buildGroqErrorMessage(response.statusCode(), response.body()));

        List<CarRecommendation> parsed = parseWithRetry(response, reserveBody, "compareSpecific");

        List<CarRecommendation> result = enrichRecommendations(parsed, 15000);
        evictIfNeeded();
        cache.put(compareCacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    String buildCompareSystemPrompt() {
        String icePrices = getIcePrices();
        String evPrices = getEvPrices();
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
                DRIVLINA: en bil märkt "ren elbil (BEV)" i verifierade specs är ALDRIG hybrid/laddhybrid — nämn aldrig bränsleförbrukning, bensinmotor eller växellåda för den (ex: MG Marvel R är en ren elbil). "laddhybrid (PHEV)" = laddhybrid. Motsäg ALDRIG specsens drivlina.
                STORLEKSKLASS: Om benutrymme bak skiljer mer än 60 mm, lyft fram det i fitSummary med konkreta mm-tal.
                SIFFERLOGIK: orden "mer/mindre/större/snabbare än" MÅSTE stämma med de verifierade siffrorna — högre mm/L/kW/km = mer utrymme/volym/laddfart/räckvidd. Ex: 1006 mm vs 954 mm = bilen med 1006 mm har MER benutrymme; skriv ALDRIG tvärtom. Kontrollera varje jämförelseord i pros/con/fitSummary mot siffrorna innan du svarar.
                BATTERIKEMI: LFP = ladda till 100%% dagligen, tålig i kyla. NMC = ladda till 80%% för livslängd, mer räckvidd per kWh. Nämn kemin om bilarna skiljer sig.
                SNABBLADDNING (DC): ≥150 kW = snabb, <100 kW = långsammare längs väg.
                VIKTIGT: Rekommendera ALDRIG BYD Dolphin. Rekommendera aldrig bensin/diesel när användaren vill ha elbil.
                VOLVO EV: EX30, EX40, EC40, EX60, EX90 — inga andra. Hitta ALDRIG på Volvo-modeller.
                GENERELLT: Nämn ALDRIG modeller som inte säljs på svenska marknaden.
                """.formatted(DEPRECIATION_RULE)
                + (icePrices.isBlank() ? "" : icePrices + "\n")
                + (evPrices.isBlank() ? "" : evPrices + "\n");
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
        appendConsumption(sb, car1);
        appendConsumption(sb, car2);
        return sb.toString().trim();
    }

    private void appendConsumption(StringBuilder sb, String carName) {
        try {
            String summary = iceConsumptionService.consumptionSummaryForTitle(carName);
            if (summary != null) sb.append(carName).append(": ").append(summary).append("\n");
        } catch (Exception ignored) {}
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
            sb.append(", ").append("PHEV".equals(ev.carType()) ? "laddhybrid (PHEV)" : "ren elbil (BEV)");
            sb.append(", batteri ").append(ev.batteryKwh()).append(" kWh");
            if (ev.wltpKm() > 0) sb.append(", räckvidd ").append(ev.wltpKm()).append(" km (WLTP)");
            if (ev.maxDcKw() > 0) sb.append(", snabbladdning (DC) max ").append(ev.maxDcKw()).append(" kW");
            if (chemistry != null) sb.append(", batterikemi ").append(chemistry);
        }
        sb.append("\n");
    }

    String extractJson(String content) {
        // Strip <think>...</think> blocks produced by qwen reasoning models
        String cleaned = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        int objStart = cleaned.indexOf('{');
        int arrStart = cleaned.indexOf('[');
        // Bare root array: keep the brackets, otherwise the array fallback in parseRecommendations never fires
        if (arrStart != -1 && (objStart == -1 || arrStart < objStart)) {
            int arrEnd = cleaned.lastIndexOf(']');
            if (arrEnd > arrStart) return cleaned.substring(arrStart, arrEnd + 1);
        }
        int objEnd = cleaned.lastIndexOf('}');
        if (objStart != -1 && objEnd > objStart) return cleaned.substring(objStart, objEnd + 1);
        return cleaned;
    }

    List<CarRecommendation> parseRecommendations(String content) throws Exception {
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
                List<CarRecommendation> parsed = convertRecommendations(node);
                if (parsed != null && !parsed.isEmpty()) return requireDistinctTitles(parsed);
            }
        }
        // Last resort: if root itself is an array
        if (root.isArray() && !root.isEmpty()) {
            List<CarRecommendation> parsed = convertRecommendations(root);
            if (parsed != null && !parsed.isEmpty()) return requireDistinctTitles(parsed);
        }
        log.warn("AI returned no parseable recommendations. Raw: {}", content);
        throw new RuntimeException("AI:n returnerade ett oväntat svar. Försök igen.");
    }

    /**
     * AI:n har föreslagit samma bil tre gånger i skarpt läge — identiska titlar triggar
     * omförsöket med reservmodellen i parseWithRetry. Exakt titeljämförelse: "MG4 (2022)"
     * vs "MG4 (2024)" är en giltig jämförelse och ska INTE avvisas.
     */
    private static List<CarRecommendation> requireDistinctTitles(List<CarRecommendation> parsed) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CarRecommendation r : parsed) {
            String t = r.title() == null ? "" : r.title().trim().toLowerCase();
            if (!t.isEmpty() && !seen.add(t)) {
                log.warn("AI föreslog samma bil flera gånger: {}", r.title());
                throw new RuntimeException("AI:n föreslog samma bil flera gånger. Försök igen.");
            }
        }
        return parsed;
    }

    /** Småbilsmarkörer — spegel av FAMILJEBIL-regelns förbudslista i systemprompten. */
    private static final List<String> SMALL_CAR_MARKERS = List.of(
            "zoe", "renault 5", "clio", "twingo", "dacia spring", "spring electric",
            "ë-c3", "e-c3", "fiat 500", "500e", "panda", "corsa", "aygo",
            "id.3", "picanto", "i10", "e-up", "up!", "mii", "citigo");

    /**
     * Kategori familjebil eller 4+ passagerare kräver familjestor bil — speglar FAMILJEBIL-regeln.
     * Användning "familj" täcks också: äldre inklistrade WordPress-snippets skickar den fortfarande.
     */
    static boolean requiresFamilySizedCar(CarPreferences prefs) {
        return (prefs.carCategory() != null && prefs.carCategory().toLowerCase().contains("familj"))
                || (prefs.usage() != null && prefs.usage().toLowerCase().contains("familj"))
                || prefs.passengers() >= 4;
    }

    /**
     * Skarpt läge: "Renault Zoe (2023)" föreslogs för familjekörning med 300k-budget trots
     * promptregeln — ett regelbrott triggar omförsöket med reservmodellen i parseWithRetry.
     */
    static void requireFamilySizedCars(List<CarRecommendation> parsed) {
        for (CarRecommendation r : parsed) {
            String t = r.title() == null ? "" : r.title().toLowerCase();
            for (String marker : SMALL_CAR_MARKERS) {
                if (t.contains(marker)) {
                    log.warn("AI föreslog småbil till familjeprofil: {}", r.title());
                    throw new RuntimeException("AI:n föreslog en för liten bil för profilen. Försök igen.");
                }
            }
        }
    }

    private List<CarRecommendation> convertRecommendations(JsonNode node) {
        try {
            return mapper.convertValue(
                    node, mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class));
        } catch (IllegalArgumentException e) {
            log.warn("AI recommendations did not match expected schema: {}", e.getMessage());
            return null;
        }
    }

    String buildCacheKey(CarPreferences prefs) {
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

    String buildRateLimitError(String body) {
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

    String buildGroqErrorMessage(int status, String body) {
        try {
            JsonNode err = mapper.readTree(body);
            String code = err.at("/error/code").asText("");
            if ("json_validate_failed".equals(code))
                return "AI-svaret blev ofullständigt. Försök igen.";
        } catch (Exception ignored) {}
        return "AI-tjänsten svarade med fel " + status + ". Försök igen om en stund.";
    }

    // --- Modellhälsokoll: avvecklade modeller (som llama-3.3-70b) försvinner ur Groqs /models-lista ---

    public record ModelStatus(List<String> missing, String error, long checkedAtMs) {}

    // UptimeRobot pingar var 5:e minut — fråga Groq max en gång i timmen
    private static final long MODEL_STATUS_TTL_MS = 60 * 60 * 1000;
    private volatile ModelStatus cachedModelStatus;

    public List<String> configuredModels() {
        Set<String> models = new LinkedHashSet<>();
        models.add(model);
        models.add(chatModel);
        if (reserveModel != null && !reserveModel.isBlank()) models.add(reserveModel);
        if (watchedModels != null) {
            for (String m : watchedModels.split(",")) {
                if (!m.isBlank()) models.add(m.trim());
            }
        }
        return List.copyOf(models);
    }

    public ModelStatus checkModels() {
        ModelStatus cached = cachedModelStatus;
        if (cached != null && System.currentTimeMillis() - cached.checkedAtMs() < MODEL_STATUS_TTL_MS) return cached;
        ModelStatus fresh = fetchModelStatus();
        // Fel (nätverk, 5xx) cachas inte — nästa ping försöker igen direkt
        if (fresh.error() == null) cachedModelStatus = fresh;
        return fresh;
    }

    private ModelStatus fetchModelStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_MODELS_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                return new ModelStatus(List.of(), "Groq /models svarade " + resp.statusCode(), System.currentTimeMillis());
            List<String> missing = missingModels(resp.body());
            if (!missing.isEmpty()) log.error("Groq-modeller saknas i /models-listan: {}", missing);
            return new ModelStatus(missing, null, System.currentTimeMillis());
        } catch (Exception e) {
            return new ModelStatus(List.of(), "Kunde inte nå Groq: " + e.getMessage(), System.currentTimeMillis());
        }
    }

    /** Vilka av de konfigurerade modellerna som saknas i ett /models-svar ({"data":[{"id":...},...]}). */
    List<String> missingModels(String modelsResponseBody) throws Exception {
        JsonNode data = mapper.readTree(modelsResponseBody).get("data");
        Set<String> available = new HashSet<>();
        if (data != null && data.isArray()) data.forEach(n -> available.add(n.path("id").asText()));
        return configuredModels().stream().filter(m -> !available.contains(m)).toList();
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

        Map<String, Object> primaryBody = Map.of("model", chatModel, "max_tokens", 1800, "temperature", 0.5,
                "reasoning_effort", reasoningEffortFor(chatModel), "messages", msgs);
        Map<String, Object> fallbackBody = Map.of("model", model, "max_tokens", 1800, "temperature", 0.5,
                "reasoning_effort", reasoningEffortFor(model), "messages", msgs);

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

        Map<String, Object> primaryBody = Map.of("model", chatModel, "max_tokens", 1800, "temperature", 0.5, "stream", true,
                "reasoning_effort", reasoningEffortFor(chatModel), "messages", msgs);
        Map<String, Object> fallbackBody = Map.of("model", model, "max_tokens", 1800, "temperature", 0.5, "stream", true,
                "reasoning_effort", reasoningEffortFor(model), "messages", msgs);

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
                ENHETER: 1 svensk mil = 10 km (1500 mil/år = 15 000 km/år). Förbrukning anges i l/100km eller l/mil — håll isär dem i beräkningar.
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
        return withFuelPrices(base);
    }

    /** Lägger på dagsaktuella bränslepriser (Bilresa-backenden) sist i systemprompten. */
    private String withFuelPrices(String systemPrompt) {
        try {
            String prices = fuelPriceService.promptContext();
            return prices.isEmpty() ? systemPrompt : systemPrompt + "\n" + prices;
        } catch (Exception e) {
            return systemPrompt;
        }
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

    String buildSystemPrompt(String expertContext, String fuelType) {
        boolean wantsEv = fuelType != null &&
                (fuelType.contains("el") || fuelType.contains("hybrid") || fuelType.contains("phev"));
        boolean wantsIce = fuelType == null || fuelType.isBlank() ||
                fuelType.contains("bensin") || fuelType.contains("diesel") ||
                fuelType.equals("spelar ingen roll");
        String icePrices = (wantsIce && !wantsEv) || wantsIce ? getIcePrices() : "";
        String evPrices  = wantsEv || (!wantsIce) ? getEvPrices() : "";
        // Filter: pure EV/PHEV → no ICE table; pure ICE → no EV table
        if (wantsEv && !wantsIce) icePrices = "";
        if (!wantsEv && wantsIce)  evPrices  = "";

        String base = """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källa t.ex. 'Teknikens Värld: toppbetyg'","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"varför bilen passar profilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade","fuelSpec":null}]}
                horsepower (hk, heltal) och engineOptions (kommaseparerad STRÄNG) får ALDRIG vara null. engineOptions bensin/diesel ex: '1.0 TSI 95hk manuell, 1.5 TSI 150hk DSG automat'; elbil ex: '44 kWh 95hk (400km), 60 kWh 204hk (570km)'.
                Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"Automat DSG 7-växlad (TSI turbo)","horsepower":N,"engineVolumeLiters":X.X} — ange turbo/ej turbo. Elbil/laddhybrid: fuelSpec=null, aldrig turbobeteckningar.
                ALLTID EXAKT 3 OLIKA bilar (tre olika modeller — aldrig samma bil två gånger) — aldrig färre. Om budgeten är knapp: billigare segment, äldre årsmodell eller annat märke (nämn det i fitSummary). fitSummary konkret och personlig; driftkostnad i pros vid hög körsträcka.
                FAMILJEBIL (kategori "familjebil", användning "familj" eller 4+ passagerare): rekommendera ALDRIG småbilar/stadsbilar (t.ex. Dacia Spring, Citroën ë-C3, Renault 5/Zoe/Clio, Fiat 500e/Panda, VW ID.3, Opel Corsa, Toyota Aygo) — välj rymliga modeller: kombi, SUV eller rymlig halvkombi/sedan. Beprövade familjebilar att utgå från — bensin/diesel/hybrid: Volvo V60/V90 (hög komfort, toppklass krocksäkerhet, 529 l bagage i V60), Škoda Octavia Combi (klassledande bagageutrymme per krona), Kia Ceed SW (mycket bil för pengarna, 7 års nybilsgaranti), Dacia Jogger (mest plånboksvänlig, finns med 7 säten); elbil: Škoda Enyaq (rymlig, lång räckvidd), VW ID.4, Kia EV6/Niro, Polestar 2, MG4.
                UTNYTTJA BUDGETEN: minst en rekommendation ska ligga nära budgeten (topp ~80–100 %) — föreslå aldrig bara väsentligt billigare bilar när budgeten räcker till något rymligare, nyare eller bättre utrustat. En billig outlier är OK som prisvärt alternativ, men aldrig som enda nivå.
                "price" är ALLTID ett intervall som "85 000–100 000 kr" — siffror med mellanslag, inga förkortningar eller extra text.
                """ + DEPRECIATION_RULE + "\n" + """
                FABRICERA ALDRIG PRISER: price = nypris × ålderskoefficient, kontrollera mot nypristabellen. Ex: Octavia 2021+ nypris 340 000 kr, 3 år → 221 000 kr — kan ALDRIG kosta 100 000 kr. Räcker inte budgeten: byt till billigare bil, sänk ALDRIG priset.
                Ange motorbeteckning (TDI/TSI/MPI/volym) bara om du är säker på att varianten finns — annars bara hk + 'manuell'/'automat'.
                Rekommendera ALDRIG BYD Dolphin eller Hyundai INSTER. Håll dig till dessa märken: Audi, BMW, BYD, Citroën, Cupra, Dacia, Fiat, Ford, Honda, Hyundai, Kia, Leapmotor, MG, Mazda, Mercedes, Mini, Nissan, Opel, Peugeot, Renault, Seat, Škoda, Smart, Tesla, Toyota, Volkswagen, Volvo, Xpeng, Zeekr. Kamiq är bensinbil, INTE elbil. Aldrig bensin/diesel när användaren efterfrågar elbil.
                MÄRKESPRIORITET: föredra etablerade europeiska, koreanska och japanska märken (samt Tesla och MG). Leapmotor, Xpeng, Zeekr och BYD bara om inget etablerat märke matchar budget och behov — aldrig som förstaval. Bilar med bra räckvidd per krona (se PRISVÄRD RÄCKVIDD) är starka förslag när de passar profilen.
                PHEV: rekommendera ALDRIG en årsmodell äldre än modellens faktiska PHEV-lansering (Golf GTE 2014+, Outlander PHEV 2013+, Passat GTE 2015+).
                Rekommendera ALDRIG en årsmodell före modellens verkliga lansering — nyheter om en modell betyder inte att den finns begagnad. Ex: Kia EV2 lanseras 2026 (finns ALDRIG begagnad), Kia EV3 2024+, EV4/EV5 2025+, Renault 5 E-Tech 2024+, Citroën ë-C3 2024+, Volvo EX30 2023+.
                Volvos enda EV-modeller: EX30, EX40, EC40, EX60, EX90 — det finns inga andra (ingen C90/C70).
                Nämn ALDRIG modeller som inte officiellt säljs i Sverige. Hitta ALDRIG på modellnamn, versioner eller specifikationer — om osäker, välj en bil du är helt säker på finns.
                """ + (wantsEv && !wantsIce ? "ELBIL OBLIGATORISKT: ENBART renodlade batterielbilar (BEV) — aldrig PHEV, laddhybrid eller bensin/diesel.\n" : "")
                    + (icePrices.isBlank() ? "" : icePrices + "\n")
                    + (evPrices.isBlank()  ? "" : evPrices  + "\n");
        if (expertContext != null && !expertContext.isBlank())
            return base + "\n" + expertContext;
        return base;
    }

    String buildPrompt(CarPreferences prefs) {
        String laddning = prefs.hasCharger() ? "ja"
                : "laddhybrid".equals(prefs.carCategory())
                    ? "nej – undvik renodlad elbil"
                    : "nej – undvik renodlad elbil (BEV) och laddhybrid (PHEV). Om hybrid passar profilen: föreslå ENDAST elhybrid (HEV) som laddar sig själv under körning, t.ex. Toyota/Lexus/Honda/Kia HEV.";
        String bilTyp = prefs.newCar() ? "ny" : "begagnad";
        int km = prefs.kmPerYear();
        String milprofil = km < 10000 ? "lågmilare" : km < 20000 ? "normalmilare" : "högmilare";
        boolean isLeasing = "leasing".equals(prefs.budgetType());
        String budgetInfo = isLeasing
                ? String.format("%,d kr/mån (leasing, ca %,d kr i listpris)", prefs.budget(), prefs.budget() * 85)
                : String.format("%,d kr (%s)", prefs.budget(), bilTyp);
        String usageText = requiresFamilySizedCar(prefs)
                ? prefs.usage() + " — FAMILJEBIL: endast rymliga bilar (kombi, SUV eller rymlig halvkombi/sedan i storleksklass MG4/VW ID.4 eller större), ALDRIG småbil/stadsbil"
                : prefs.usage();
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
                km, milprofil, usageText, prefs.passengers(), fuelLine, transmissionLine, maxAgeLine
        );
    }
}
