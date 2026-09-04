package com.caradvice.service;

import com.caradvice.model.CargoSpecDto;
import com.caradvice.model.EvSpecDto;
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
import java.util.LinkedHashMap;
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

    /**
     * Uppmätta begagnatgolv för elbilar — prisankare åt modellvalet.
     *
     * <p>Elbilskategorin var den enda utan exempellista i prompten (SUV och Småbil har sina),
     * så AI:n fick nypristabellen plus {@link #DEPRECIATION_RULE} och skulle räkna ut
     * begagnatpriserna själv. Den överskattar dem systematiskt: live 2026-08-10 gav elbil +
     * 200 000 kr förslagen EV6/Leaf/Polestar 2, där EV6:s billigaste annons låg på 316 990 kr
     * och utlöste banderollen om att budgeten inte räcker — medan MG4 fanns från 193 990 kr och
     * MG5 från 179 700 kr. Ingen vakt hade fällt någon av dem; de föreslogs bara aldrig.
     *
     * <p><b>Håll listan i synk med {@code CA_BUDGET_LEVELS.elbil} i car-advice-main.js.</b> Det
     * är samma siffror sedda från två håll — budgetrutan säger vad pengarna räcker till, den här
     * listan säger åt AI:n vad den får föreslå — och går de isär motsäger sidan sig själv inom
     * samma vy. Golven är mätta mot Blocket med **högst 10 000 mil** och samma outlier-trimning
     * som prisraden på korten; utan milgränsen sätts golvet av marknadens mest slitna exemplar.
     * Siffrorna åldras: mät om båda listorna samtidigt, aldrig bara den ena.
     */
    /**
     * Uppmätta begagnatgolv, som DATA och inte bara som prompttext.
     *
     * <p>Första versionen stod bara i prompten, och två live-sökningar 2026-08-10 visade att det
     * inte räcker: Kia EV6 föreslogs för en 200 000-budget i BÅDA körningarna, med sitt eget golv
     * på 317 000 utskrivet tio rader ovanför regeln som förbjuder det. Samma lärdom som
     * familjespärren, drivmedelsvakten, årsmodellvakten och bagagekravet gav — en regel som bara
     * står i prompten är ingen regel. {@link #requireAffordableModels} läser den här tabellen.
     *
     * <p>Golven är billigaste annons med högst 10 000 mil (samma underlag som prisraden på
     * korten) och speglar {@code CA_BUDGET_LEVELS.elbil} i car-advice-main.js — mät om båda
     * samtidigt. Nyckeln matchas ord för ord mot titeln, mest specifika namnet vinner.
     */
    static final Map<String, Integer> EV_PRICE_FLOOR_KR = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("Renault Zoe",           58_000),
            Map.entry("Nissan Leaf",           70_000),
            Map.entry("MG ZS EV",             130_000),
            Map.entry("Volkswagen e-Golf",    139_000),
            Map.entry("Kia Niro EV",          175_000),
            Map.entry("MG5",                  180_000),
            Map.entry("MG4",                  195_000),
            Map.entry("Hyundai Kona Electric",195_000),
            Map.entry("Volkswagen ID.3",      199_000),
            Map.entry("Polestar 2",           209_000),
            Map.entry("Tesla Model 3",        215_000),
            Map.entry("Volkswagen ID.4",      229_500),
            Map.entry("Hyundai Ioniq 5",      269_000),
            Map.entry("Skoda Enyaq",          279_000),
            Map.entry("Kia EV6",              317_000)));

    /** Promptraden byggs UR tabellen — annars glider text och vakt isär vid nästa mätning. */
    private static final String EV_PRICE_FLOORS =
            "ELBIL (kategori \"elbil\") — UPPMÄTTA BEGAGNATGOLV på svenska marknaden (billigaste annons"
            + " med högst 10 000 mil, augusti 2026). Använd dem som prisankare i stället för att räkna"
            + " fram priset ur nypriset:\n"
            // Locale.ROOT med flit: svensk locale ger HÅRT mellanslag (U+00A0) som
            // grupperingstecken, och då matchar varken testet eller en sökning i prompten det
            // som står där. Samma familj av fälla som U+202F i AI-titlarna 2026-08-10.
            + EV_PRICE_FLOOR_KR.entrySet().stream()
                    .map(e -> e.getKey() + " fr. ca "
                            + String.format(java.util.Locale.ROOT, "%,d", e.getValue()).replace(',', ' '))
                    .collect(java.util.stream.Collectors.joining(", "))
            + ".\nEn modell vars golv ligger över budgeten + 30 000 kr är fel förslag — välj i stället en"
            + " modell vars golv ligger nära budgeten. Golvet är billigaste exemplaret: ett välutrustat"
            + " eller lågmilat exemplar kostar mer.";

    /**
     * {@code budgetShortfallFromKr} är null i normalfallet. Är den satt gick ingen bil att
     * hitta inom budgettaket — korten visas ändå (tomt resultat hjälper ingen), och värdet
     * är billigaste verkliga marknadspris bland dem så frontend kan säga varför.
     */
    public record Result(List<CarRecommendation> recommendations, boolean fromCache, long cacheAgeSeconds,
                         Integer budgetShortfallFromKr) {}

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
    private final ValueRetentionClient valueRetentionClient;
    private final SafetyRatingService safetyRatingService;
    private final EvSpecService evSpecService;
    private final CargoSpecService cargoSpecService;
    private final BlocketPriceService blocketPriceService;
    private final NewCarPriceService newCarPriceService;
    private final FeedbackService feedbackService;
    private final IceConsumptionService iceConsumptionService;
    private final FuelPriceService fuelPriceService;
    private final ElectricityPriceService electricityPriceService;
    private final LeasingPriceService leasingPriceService;

    public GroqService(ExpertInsightService expertInsightService, SafetyRatingService safetyRatingService,
                       EvSpecService evSpecService, CargoSpecService cargoSpecService,
                       BlocketPriceService blocketPriceService, NewCarPriceService newCarPriceService,
                       FeedbackService feedbackService, IceConsumptionService iceConsumptionService,
                       FuelPriceService fuelPriceService, ElectricityPriceService electricityPriceService,
                       LeasingPriceService leasingPriceService,
                       ValueRetentionClient valueRetentionClient) {
        this.valueRetentionClient = valueRetentionClient;
        this.leasingPriceService = leasingPriceService;
        this.expertInsightService = expertInsightService;
        this.safetyRatingService = safetyRatingService;
        this.evSpecService = evSpecService;
        this.cargoSpecService = cargoSpecService;
        this.blocketPriceService = blocketPriceService;
        this.newCarPriceService = newCarPriceService;
        this.feedbackService = feedbackService;
        this.iceConsumptionService = iceConsumptionService;
        this.fuelPriceService = fuelPriceService;
        this.electricityPriceService = electricityPriceService;
    }

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models";
    /**
     * Tokentaket för ett rekommendationssvar. Får INTE höjas utan att prompten krymps först.
     *
     * <p>Höjdes till 4000 2026-08-09 för att komma åt "AI-svaret blev ofullständigt", och gav
     * inom minuter <b>HTTP 413</b> live. Groq mäter en enskild förfrågan som
     * {@code prompt + reserverade max_tokens} mot per-minut-taket på 8 000 tokens, och avvisar
     * hela anropet med 413 "request too large" när summan går över — det är alltså inte svarets
     * längd som räknas utan takets. Systemprompten (nypristabeller, expertkontext,
     * feedback-kontext) är stor nog att 4 000 reserverade tokens spränger gränsen.
     *
     * <p>Vill man åt trunkeringen är vägen att korta systemprompten, inte att höja taket.
     * Reserverade tokens räknas dessutom mot dygnstaket (200 000 per organisation) som delas
     * med nattscrapern.
     */
    private static final int RECOMMENDATION_MAX_TOKENS = 3000;
    /**
     * Omförsökets tokentak. Lika med det ordinarie, och får inte höjas över det — samma regel
     * som {@link #RECOMMENDATION_MAX_TOKENS}, av samma skäl.
     *
     * <p>Stod på 4500 från {@code a0d7256} (2026-08-09) och sänktes sedan till 3400 för att
     * bekämpa trunkering, men aldrig hela vägen ned. Återställningen till 3000 som stängde 413
     * på primärvägen rörde alltså aldrig reservvägen, och den bar felet vidare tills trafiken
     * blev hög nog att visa det: mätt i produktionsloggen 2026-08-13 kl. 08:59-09:01 avvisades
     * sex anrop mot {@code qwen/qwen3.6-27b} med 413, med {@code Requested} 8044, 8092, 8110,
     * 8151, 8157 och 8165 mot taket 8000. Drar man bort de 3400 reserverade landar prompten på
     * 4644-4765 tokens — varenda ett av dem hade rymts under 8000 med 3000 reserverade.
     *
     * <p>Avvägningen är medveten: 3400 fanns för att ett omförsök med samma tak ofta upprepar
     * trunkeringen. Men 413 är ett hårt fel som fäller hela anropet, medan trunkering utlöser
     * just det omförsök som finns här — den sämre av de två utfallen är alltså 413. Vägen till
     * att bli av med båda går genom en kortare systemprompt, inte genom ett högre tak.
     */
    private static final int RETRY_MAX_TOKENS = RECOMMENDATION_MAX_TOKENS;
    private static final long CACHE_TTL_MS = 4 * 60 * 60 * 1000;
    private static final int MAX_CACHE_SIZE = 200;
    private static final long PRICES_TTL_MS = 60 * 60 * 1000;
    private static final int CHAT_MAX_HISTORY = 8;

    private volatile String cachedIcePrices = "";
    private volatile String cachedEvPrices = "";
    private volatile long pricesCachedAt = 0L;

    // Modellhallucinationsvakt: ordmängder för varje känd bil ur cargo_spec/ev_spec/ice_consumption
    // (~700+ modeller) — samma seed-data och uppdateringscykel som pris-cachen ovan.
    private volatile List<Set<String>> knownModelTokenSets = List.of();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    /** Se {@link #registreraTokenanvandning} — läses av {@code GET /api/admin/token-usage}. */
    private final TokenUsageStats tokenStatistik = new TokenUsageStats();

    public Map<String, Object> tokenAnvandning() { return tokenStatistik.rapport(); }
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * {@code alternativeRanges} används bara av budgetalternativen — de behöver sina
     * Blocket-priser cachade tillsammans med bilarna, annars hade en cacheträff tappat
     * priserna som är hela poängen med raden. Null för vanliga poster.
     */
    private record CacheEntry(List<CarRecommendation> result, long timestamp, Integer budgetShortfallFromKr,
                              Map<String, BlocketPriceService.PriceRange> alternativeRanges) {}

    /**
     * Utfallet av budgetkontrollen. {@code shortfallFromKr} är satt bara när INGEN bil gick
     * att hitta inom taket i någondera omgången — värdet är då billigaste verkliga
     * Blocket-pris bland de bilar som ändå visas, så användaren får veta vad som faktiskt
     * krävs i stället för att gissa varför förslagen ligger över budget.
     */
    private record BudgetOutcome(List<CarRecommendation> recommendations, Integer shortfallFromKr) {}

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

    /**
     * Hur länge servern får sova på ett 429 innan den ger upp och lämnar felet vidare.
     *
     * <p>Minuttaket är per MODELL och släpper nästan alltid inom en halvminut — kedjan har tre
     * modeller med var sin budget, så att ALLA tre är fulla betyder att användaren klickat i
     * täta skurar. Då är en kort paus ett bättre svar än ett felmeddelande: väggen blir en
     * väntan. Taket finns för att pausen aldrig får äta upp klientens egen timeout.
     */
    private static final int MAX_429_WAIT_SECONDS = 25;

    /**
     * Provar modellerna i tur och ordning och byter vid 429 — taket är per modell, så nästa
     * modell har en egen budget. Är ALLA fulla sover den en gång i den tid Groq själv anger
     * och provar första modellen på nytt.
     *
     * <p>Utan pausen kastades 429:an rakt ut i gränssnittet fast taket ofta hade släppt inom
     * 20 sekunder — och eftersom en sökning kan kosta flera anrop (fallback, reservmodell,
     * budget- och regelomförsök) räckte två-tre klick i rad för att tömma hela kedjan.
     * Klientens tak måste vara större än {@link #MAX_429_WAIT_SECONDS} plus rundturerna,
     * annars byter man bara ett ärligt "vänta" mot en timeout.
     */
    private HttpResponse<String> callGroqWithFallback(Object... bodies) throws Exception {
        HttpResponse<String> resp = null;
        for (Object body : bodies) {
            resp = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
            registreraTokenanvandning(body, resp);
            if (resp.statusCode() != 429) return resp;
        }
        if (resp == null || bodies.length == 0) return resp;

        int vanta = parseRetrySeconds(resp.body());
        if (vanta <= 0 || vanta > MAX_429_WAIT_SECONDS) {
            log.warn("Alla {} modeller gav 429 och vantetiden ({} s) ryms inte i pausen — lamnar felet vidare",
                    bodies.length, vanta);
            return resp;
        }
        log.info("Alla {} modeller gav 429 — sover {} s och provar forsta modellen igen", bodies.length, vanta);
        try {
            Thread.sleep(vanta * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resp;
        }
        HttpResponse<String> omforsok = httpClient.send(buildRequest(bodies[0]), HttpResponse.BodyHandlers.ofString());
        registreraTokenanvandning(bodies[0], omforsok);
        return omforsok;
    }

    /**
     * Groqs egen {@code usage}-räkning per anrop — <b>enda sättet att veta hur stor prompten
     * faktiskt är</b>.
     *
     * <p>Fram till 2026-08-22 lästes fältet aldrig. Det enda som avslöjade promptstorleken var
     * felmeddelanden: 413-raderna 2026-08-13 bar {@code Requested} 8044-8165, och därifrån gick
     * det att räkna baklänges till 4 644-4 765 prompttokens. Ett tal som bara syns när det redan
     * sprängt taket går inte att tuna mot — samma blindhet som täckningsmätaren hade när
     * bagageparsern var död.
     *
     * <p>Talet spelar roll av en konkret anledning: Groq mäter {@code prompt + reserverade
     * max_tokens} mot minuttaket 8 000. Med 3 000 reserverade fyller en prompt på 4 700 nästan
     * hela budgeten, och då ryms bara ETT anrop per minut och modell. Varje tusen tokens som
     * kortas bort är alltså en sökning till i samma minut.
     *
     * <p>Sparas i minnet och exponeras av {@code GET /api/admin/token-usage}. Inget skrivs till
     * databasen: det här är ett mätvärde för tuning, inte historik, och en tabell till hade
     * kostat mer än den gav.
     */
    private void registreraTokenanvandning(Object body, HttpResponse<String> resp) {
        if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) return;
        try {
            JsonNode usage = mapper.readTree(resp.body()).path("usage");
            if (usage.isMissingNode()) return;
            String modell = (body instanceof Map<?, ?> m && m.get("model") != null)
                    ? String.valueOf(m.get("model")) : "okänd";
            int prompt = usage.path("prompt_tokens").asInt();
            int svar = usage.path("completion_tokens").asInt();
            int reserverat = (body instanceof Map<?, ?> m2 && m2.get("max_tokens") instanceof Integer i) ? i : 0;
            tokenStatistik.registrera(modell, prompt, svar, reserverat);
            log.info("Groq {}: prompt {} + svar {} tokens (reserverat {}, mot minuttaket räknas {})",
                    modell, prompt, svar, reserverat, prompt + reserverat);
        } catch (Exception ignored) {
            // Mätningen får aldrig fälla ett svar som gick igenom.
        }
    }

    private Map<String, Object> jsonCallBody(String modelName, double temperature, String systemPrompt, String userPrompt) {
        return jsonCallBody(modelName, temperature, systemPrompt, userPrompt, RECOMMENDATION_MAX_TOKENS);
    }

    /**
     * Med eget takvärde. Reservmodellen körde en tid med ett HÖGRE tak än ordinarie, eftersom
     * "AI-svaret blev ofullständigt" betyder att svaret inte hann skrivas färdigt och ett
     * omförsök med samma tak ofta upprepar trunkeringen. Det gav i stället 413 på reservvägen —
     * se {@link #RETRY_MAX_TOKENS}. Överlagringen finns kvar för att taket ska gå att sätta per
     * anrop, men inget anrop får längre reservera mer än {@link #RECOMMENDATION_MAX_TOKENS}.
     */
    private Map<String, Object> jsonCallBody(String modelName, double temperature, String systemPrompt,
                                             String userPrompt, int maxTokens) {
        // response_format tillkom 2026-08-28: JSON var fram till dess BARA ombedd i prompttexten
        // ("Svara ENDAST med JSON"), och en promptregel är ett önskemål — samma lärdom som bakom
        // familje-, drivmedels- och SUV-vakterna. Skarpt fall samma dag: fyra sökningar i rad
        // (elbil, 350 000 kr, 4 passagerare, 300 l bagage, max 5 år) gav "AI-svaret blev
        // ofullständigt", och token-usage visade att BÅDA modellerna skrev färdigt av sig själva
        // — 946/944/917 tokens för 120b och 764/629/755/1026 för 20b, alla mot 3000 reserverade.
        // Svaret höggs alltså inte av; det var ogiltig JSON, och omförsöket med reservmodellen
        // upprepade felet varje gång. Med json_object kan modellen inte producera trasig JSON.
        //
        // KRAV: varje anropare måste ha ordet "JSON" i prompten, annars avvisar Groq anropet.
        // Uppfyllt av båda systemprompterna ("Svara ENDAST med JSON"). Gäller bara den här
        // vägen — chattens body byggs separat och streamar fri text, den ska INTE ha formatet.
        //
        // Mätt mot Groq före ändringen: gpt-oss-120b 651 tokens med formatet mot 716 utan,
        // 20b 520 mot 540 — alltså inget pristillägg mot minutbudgeten, snarare tvärtom.
        return Map.of(
                "model", modelName, "max_tokens", maxTokens, "temperature", temperature,
                "reasoning_effort", reasoningEffortFor(modelName),
                "response_format", Map.of("type", "json_object"),
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
            // Mätningen låg BARA i callGroqWithFallback, och det här omförsöket går utanför den.
            // Reservmodellens anrop var därmed osynliga i /api/admin/token-usage: qwen syntes
            // aldrig i en enda mätning 2026-08-28 trots att den var konfigurerad och frisk, och
            // varje siffra jag läste den dagen underskattade förbrukningen. En mätning som tyst
            // utelämnar en av vägarna är värre än ingen — man felsöker i halvmörker och tror
            // att man ser hela bilden.
            registreraTokenanvandning(reserveBody, retry);
            // Ett rate limit på omförsöket är INTE samma fel som det första: förr kastades det
            // ursprungliga trunkeringsfelet vidare, så användaren fick "AI-svaret blev
            // ofullständigt" plus rådet att lätta på sina kriterier — fast kriterierna var
            // oskyldiga och det enda som hjälpte var att vänta en minut. En sökning drar ~5 000
            // av minutbudgetens 8 000 tokens, så två inom samma minut räcker för att utlösa det.
            // Återanvänder buildRateLimitError: den skiljer dygnstaket (TPD/RPD) från minuttaket
            // och läser väntetiden ur Groqs eget svar, i stället för att gissa "en minut".
            if (retry.statusCode() == 429)
                throw new RateLimitedException(buildRateLimitError(retry.body())
                        + " Dina kriterier är inte problemet.", parseRetrySeconds(retry.body()));
            if (retry.statusCode() != 200) throw first;
            try {
                List<CarRecommendation> parsed = extractAndParse(retry, label + " (omförsök)");
                if (validator != null) validator.accept(parsed);
                return parsed;
            } catch (RuntimeException second) {
                // Bröt BÅDA omgångarna mot en regel vinner den som lämnade flest godkända bilar —
                // annars kastas omförsökets bättre svar bort bara för att det kom sist.
                if (first instanceof RuleViolationException f && second instanceof RuleViolationException s
                        && s.kvar().size() > f.kvar().size()) throw s;
                throw first;
            }
        }
    }

    /**
     * Skarpt läge: AI:n satte 200 000–210 000 kr på en Kia EV6 som på Blocket börjar vid 333 500 kr.
     * Ligger AI:ns intervall helt under eller helt över Blockets årsfiltrerade annonsintervall
     * ersätts det med Blocket-intervallet — verkligheten vinner över deprecieringskalkylen.
     * Minst 2 annonser krävs (sänkt från 3) så att en enstaka fel-/scamannons inte ensam
     * skriver över rimliga priser — BlocketPriceService trimmar bara percentil-outliers vid
     * ≥5 träffar, så en enda annons har noll skydd mot fluktannonser.
     */
    static String correctedPrice(String aiPrice, BlocketPriceService.PriceRange blocket, String title) {
        return correctedPrice(aiPrice, blocket, title, false);
    }

    /** leasing=true: samma jämförelse men i kr/mån, mot privatleasingannonserna. */
    static String correctedPrice(String aiPrice, BlocketPriceService.PriceRange blocket, String title,
                                 boolean leasing) {
        if (aiPrice == null || blocket == null || blocket.count() < 2) return aiPrice;
        java.util.List<Long> nums = new ArrayList<>();
        Matcher m = Pattern.compile("\\d[\\d\\s\\u00a0]*").matcher(aiPrice);
        while (m.find()) {
            try { nums.add(Long.parseLong(m.group().replaceAll("[\\s\\u00a0]", ""))); } catch (NumberFormatException ignored) {}
        }
        if (nums.isEmpty()) return aiPrice;
        long aiMin = nums.get(0), aiMax = nums.get(nums.size() - 1);
        if (aiMax < blocket.minKr() || aiMin > blocket.maxKr()) {
            String enhet = leasing ? " kr/mån" : " kr";
            log.warn("AI-pris {} för {} utanför Blocket-intervallet {}–{}{} ({} annonser) — ersätter",
                    aiPrice, title, blocket.minKr(), blocket.maxKr(), enhet, blocket.count());
            return formatSekSpace(blocket.minKr()) + "–" + formatSekSpace(blocket.maxKr()) + enhet;
        }
        return aiPrice;
    }

    /** Nedskrivningen ur {@link #DEPRECIATION_RULE} — samma kurva som prompten föreskriver för AI:n. */
    private static final double[] AGE_COEFFICIENTS = {1.0, 0.85, 0.75, 0.65, 0.57, 0.50, 0.44, 0.39, 0.34};

    /**
     * Prisraden när Blocket inte kan säga emot: räknad ur verifierat nypris i stället för gissad.
     *
     * <p>Utan annonser kunde varken {@code correctedPrice} eller budgettaket röra AI:ns siffra.
     * Live 2026-08-07 stod Kia EV3 på "170 000–190 000 kr" med noll annonser medan vårt eget
     * {@code ev_spec} bar 370 000 kr — 200 000 kr fel, helt utan täckning. Nyprisreferensen
     * fäller numera bilen när den ligger över budgettaket, men en bil som ryms i budgeten stod
     * kvar med samma påhittade intervall.
     *
     * <p>Talet är uttryckligen märkt "ca": det är en beräkning, inte marknadsdata. Nybilssök får
     * nypriset rakt av eftersom det är vad användaren faktiskt betalar.
     *
     * @return null när årsmodellen saknas i titeln och sökningen gäller begagnat — då finns
     *         ingen ålder att skriva ned med, och en oskriven siffra är bättre än en påhittad
     */
    static String estimatedPrice(int nyprisKr, Integer titleYear, int currentYear, boolean newCar) {
        if (nyprisKr <= 0) return null;
        if (newCar) return "fr. " + formatSekSpace(nyprisKr) + " kr";
        if (titleYear == null) return null;
        int alder = Math.max(0, currentYear - titleYear);
        double koefficient = AGE_COEFFICIENTS[Math.min(alder, AGE_COEFFICIENTS.length - 1)];
        int uppskattat = (int) Math.round(nyprisKr * koefficient / 1000d) * 1000;
        return "ca " + formatSekSpace(uppskattat) + " kr";
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

    /**
     * Hur långt över budgeten en bils billigaste Blocket-annons får ligga.
     *
     * Live-fynd: budget 275 000 kr gav Kia EV3, som börjar på 359 000 kr på Blocket — 84 000 kr
     * över, alltså inte köpbar för användaren. Budgeten var tidigare enbart en promptregel
     * ("UTNYTTJA BUDGETEN"), aldrig kontrollerad mot verklig marknadsdata, och correctedPrice
     * gjorde felet synligt utan att åtgärda det: den bytte AI:ns påhittade pris mot Blockets
     * riktiga och visade 359 000 kr på ett kort som föreslagits för en 275 000-budget.
     *
     * Bara ett tak, inget golv: en bil under budget är fortfarande köpbar, och prompten ska
     * kunna lägga ett prisvärt fynd bland förslagen.
     */
    static final int BUDGET_CEILING_MARGIN_KR = 30_000;

    /**
     * Blocket-verifierat budgettak. Jämför mot annonsintervallets LÄGSTA pris: räcker inte
     * den billigaste annonsen finns bilen inte att köpa inom budget, oavsett var snittet ligger.
     *
     * Kräver minst 2 annonser av samma skäl som correctedPrice — en ensam fel- eller scamannons
     * ska inte kunna fälla en bil som egentligen är prisvärd.
     */
    static boolean exceedsBudgetCeiling(BlocketPriceService.PriceRange blocket, int budgetKr) {
        if (blocket == null || blocket.count() < 2) return false;
        return blocket.minKr() > budgetKr + BUDGET_CEILING_MARGIN_KR;
    }

    /** Lägsta pris vi kan belägga för en bil, och om siffran kommer från Blocket eller är ett nypris. */
    record VerifiedFloor(int kr, boolean fromBlocket) {}

    /**
     * Blocket är måttstocken — appen handlar om vad som faktiskt går att köpa begagnat idag.
     * Nypriset ur ev_spec är enbart en referens, och används bara när Blocket är helt tyst.
     *
     * Live-fynd 2026-08-07: Kia EV3 föreslogs för en 200 000-budget med AI-priset
     * "170 000–190 000 kr" och NOLL annonser. Utan annonser kunde varken correctedPrice eller
     * budgettaket säga emot, trots att vårt eget ev_spec bar 370 000 kr på samma kort. Saknas
     * annonser finns inget bevis att bilen går att köpa över huvud taget, och då är nypriset
     * den enda verifierade siffra vi har: ligger den över taket ska bilen inte stå kvar.
     *
     * Nypriset får aldrig läcka ut som ett annonspris — banderollen skriver ut sin siffra som
     * "... på Blocket just nu", därför är {@code cheapest} fortfarande Blocket-only.
     */
    static VerifiedFloor verifiedFloor(CarRecommendation r, BlocketPriceService.PriceRange blocket) {
        return verifiedFloor(r, blocket, null, false);
    }

    /**
     * Nybilssök vänder på ordningen: nypriset är måttstocken och Blocket sekundärt.
     *
     * <p>Ber användaren om en NY bil är begagnatpriset inte vad hen betalar — då är nypriset
     * det enda relevanta talet, och Blocket bara en nödlösning för modeller vi saknar nypris
     * för. I begagnatsök gäller det omvända: annonserna är verkligheten och nypriset en
     * referens för när de saknas.
     *
     * @param nyprisKr nypris ur {@code ev_spec} (elbil/PHEV) eller {@code new_car_price} (ICE)
     */
    static VerifiedFloor verifiedFloor(CarRecommendation r, BlocketPriceService.PriceRange blocket,
                                       Integer nyprisKr, boolean newCar) {
        Integer nypris = nyprisKr != null ? nyprisKr
                : (r != null && r.evSpec() != null && r.evSpec().priceKr() > 0) ? r.evSpec().priceKr() : null;
        boolean harBlocket = blocket != null && blocket.count() >= 2 && blocket.minKr() > 0;

        if (newCar && nypris != null) return new VerifiedFloor(nypris, false);
        if (harBlocket) return new VerifiedFloor(blocket.minKr(), true);
        if (nypris != null) return new VerifiedFloor(nypris, false);
        return null;
    }

    /** Som ovan, men med bilens egen specdata som referens när Blocket inte kan döma. */
    static boolean exceedsBudgetCeiling(CarRecommendation r, BlocketPriceService.PriceRange blocket, int budgetKr) {
        return exceedsBudgetCeiling(r, blocket, budgetKr, false);
    }

    /**
     * Hur långt över en leasingbudget månadskostnaden får ligga. 30 000 kr är räknat för
     * köpbudgetar och hade gjort taket meningslöst här: en 5 000-budget skulle rymma allt.
     */
    static final int LEASING_CEILING_MARGIN_KR = 500;

    /**
     * Leasingläget mäter kr/mån mot kr/mån och har två skillnader mot köp.
     *
     * <p>Nyprisfallbacken stängs av: {@code ev_spec} bär bilens pris i kronor, och en jämförelse
     * mot en månadsbudget hade fällt varenda bil (370 000 > 5 000). Utan leasingannonser finns
     * alltså ingen grund att döma på, precis som taket fungerade före nyprisreferensen.
     *
     * <p>Marginalen är 500 kr/mån i stället för 30 000 kr.
     *
     * <p>En enda annons räcker här, till skillnad från köp. Tvåannonskravet finns för att en
     * ensam fel- eller scamannons inte ska fälla en prisvärd bil, men leasingannonser läggs av
     * bilhandlare (sales_form=5 är företagsannonser) och utbudet per modell är tunt. Live
     * 2026-08-07 hade Kia EV6 GT-Line exakt en annons — 8 295 kr/mån mot en 5 000-budget — och
     * kravet på två släppte igenom just det fall taket byggdes för.
     *
     * <p>{@code correctedPrice} står kvar på två annonser även i leasingläge: att fälla en bil
     * på en ensam annons är en mindre risk än att skriva om priset användaren ser till den.
     */
    static boolean exceedsBudgetCeiling(CarRecommendation r, BlocketPriceService.PriceRange blocket,
                                        int budgetKr, boolean leasing) {
        return exceedsBudgetCeiling(r, blocket, null, budgetKr, leasing, false);
    }

    static boolean exceedsBudgetCeiling(CarRecommendation r, BlocketPriceService.PriceRange blocket,
                                        Integer nyprisKr, int budgetKr, boolean leasing, boolean newCar) {
        if (leasing) {
            return blocket != null && blocket.count() >= 1
                    && blocket.minKr() > budgetKr + LEASING_CEILING_MARGIN_KR;
        }
        VerifiedFloor golv = verifiedFloor(r, blocket, nyprisKr, newCar);
        return golv != null && golv.kr() > budgetKr + BUDGET_CEILING_MARGIN_KR;
    }

    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear) {
        return enrichRecommendations(parsed, kmPerYear, null, false);
    }

    /**
     * leasing=true hämtar privatleasingannonserna i stället för köpannonserna. Tidigare
     * hoppades Blocket över helt i leasingläge, eftersom begagnatpriserna är fel prisläge —
     * men annonserna finns, de ligger bara bakom {@code sales_form=5} och räknas i kr/mån.
     */
    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean leasing) {
        return enrichRecommendations(parsed, kmPerYear, fuelPref, leasing, null);
    }

    /**
     * rangesOut != null: Blocket-intervallet per titel läggs där för anropare som behöver
     * siffrorna efteråt (budgettaket) — CarRecommendation bär bara den formaterade strängen.
     */
    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean leasing,
                                                          Map<String, BlocketPriceService.PriceRange> rangesOut) {
        return enrichRecommendations(parsed, kmPerYear, fuelPref, leasing, rangesOut, null);
    }

    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean leasing,
                                                          Map<String, BlocketPriceService.PriceRange> rangesOut,
                                                          Map<String, Integer> nyprisOut) {
        return enrichRecommendations(parsed, kmPerYear, fuelPref, leasing, rangesOut, nyprisOut, false,
                BlocketPriceService.AdFilter.NONE);
    }

    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean leasing,
                                                          Map<String, BlocketPriceService.PriceRange> rangesOut,
                                                          Map<String, Integer> nyprisOut, boolean newCar) {
        return enrichRecommendations(parsed, kmPerYear, fuelPref, leasing, rangesOut, nyprisOut, newCar,
                BlocketPriceService.AdFilter.NONE);
    }

    /**
     * adFilter begränsar vilka annonser som får sätta prisgolvet till dem som matchar
     * användarens drivmedel och växellåda — se {@link #adFilterFor}. {@code AdFilter.NONE} för
     * anropare utan {@link CarPreferences} (jämförelseläget), som före 2026-08-13.
     */
    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean leasing,
                                                          Map<String, BlocketPriceService.PriceRange> rangesOut,
                                                          Map<String, Integer> nyprisOut, boolean newCar,
                                                          BlocketPriceService.AdFilter adFilter) {
        List<CompletableFuture<BlocketPriceService.PriceRange>> blocketFutures = parsed.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return leasing ? blocketPriceService.fetchLeasingRange(r.title())
                                       : blocketPriceService.fetchPriceRange(r.title(), adFilter);
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
                    Integer titleYear = CarTitle.year(r.title());
                    if (titleYear != null) {
                        boolean isPhev = "PHEV".equals(evSpec.carType());
                        if (isPhev && titleYear < 2014) evSpec = null;       // PHEVs before 2014 don't exist
                        else if (!isPhev && titleYear < 2011) evSpec = null; // consumer EVs before 2011 don't exist
                    }
                }
                // ice_consumption har företräde: en bensinbil eller självladdande hybrid ska
                // inte bära laddråd. Se evSpecHorInteHit för de skarpa fallen.
                if (evSpec != null && evSpecHorInteHit(r.title())) evSpec = null;
            } catch (Exception ignored) {}
            try { cargo = cargoSpecService.formatForTitle(r.title()); } catch (Exception ignored) {}
            try { blocketRange = blocketFutures.get(i).get(6, TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (rangesOut != null && blocketRange != null) rangesOut.put(r.title(), blocketRange);
            // Nypris per titel: ev_spec för el/PHEV, new_car_price för bensin/diesel. Behövs
            // separat eftersom kortet bara bär evSpec — en ICE-bil har inget prisfält alls.
            Integer nypris = evSpec != null && evSpec.priceKr() > 0 ? evSpec.priceKr() : null;
            if (nypris == null) {
                try { nypris = newCarPriceService.priceForTitle(r.title()); } catch (Exception ignored) {}
            }
            if (nyprisOut != null && nypris != null) nyprisOut.put(r.title(), nypris);

            // Märkets eget listpris går före handlarannonserna i leasingläge: det är siffran
            // kunden möter på märkets sajt. Saknas modellen i utbudet betyder det att den inte
            // går att privatleasa — då står Blocket-annonserna kvar som enda uppgift.
            if (leasing) {
                LeasingPriceService.LeasingOffer officiellt = null;
                try { officiellt = leasingPriceService.offerForTitle(r.title()); } catch (Exception ignored) {}
                if (officiellt != null) {
                    blocketRange = new BlocketPriceService.PriceRange(officiellt.monthlyKr(),
                            officiellt.monthlyKr(), 1,
                            "från " + formatSekSpace(officiellt.monthlyKr()) + " kr/mån ("
                                    + officiellt.brand() + " privatleasing)");
                    if (rangesOut != null) rangesOut.put(r.title(), blocketRange);
                }
            }
            String blocketPrice = blocketRange != null ? blocketRange.formatted() : null;
            // Samma jämförelse i båda lägena, men aldrig över prislägena: i leasingläge är
            // både AI:ns siffra och annonsintervallet kr/mån
            String price = correctedPrice(r.price(), blocketRange, r.title(), leasing);

            // Utan annonser kan correctedPrice inte säga emot — då räknas priset ur nypriset
            // i stället för att AI:ns gissning får stå oemotsagd (Kia EV3: "170 000–190 000 kr"
            // för en bil som kostar 370 000 ny)
            boolean harAnnonser = blocketRange != null && blocketRange.count() >= 2;
            if (!leasing && !harAnnonser && nypris != null) {
                String uppskattat = estimatedPrice(nypris, CarTitle.year(r.title()),
                        java.time.Year.now().getValue(), newCar);
                if (uppskattat != null && !uppskattat.equals(price)) {
                    log.warn("Inga annonser för {} — AI-priset {} ersätts med {} räknat på nypris {} kr",
                            r.title(), price, uppskattat, nypris);
                    price = uppskattat;
                }
            }

            // Ersätt AI:ns gissade förbrukning med verifierad siffra från ice_consumption om matchning finns.
            // OBS enhetskonventionen: consumptionLiterPerMil bär l/100km (frontend delar med 10 vid visning
            // och räknar ägandekostnad på l/100km) — ice_consumption lagrar l/mil, därav ×10 här.
            com.caradvice.model.FuelSpecDto fuelSpec = r.fuelSpec();
            // fuel nollställs medvetet: AI:ns egen gissning kastas och fältet fylls bara av
            // den verifierade ice_consumption-raden nedan. Samma linje som förbrukning och hk.
            if (fuelSpec != null) fuelSpec = new com.caradvice.model.FuelSpecDto(
                    fuelSpec.consumptionLiterPerMil(), rensaVaxellada(fuelSpec.gearbox()),
                    fuelSpec.horsepower(), fuelSpec.engineVolumeLiters(), null);
            IceConsumptionService.Variant iceVariant = null;
            if (fuelSpec != null && fuelSpec.consumptionLiterPerMil() != null) {
                try {
                    Integer hp = fuelSpec.horsepower() != null ? fuelSpec.horsepower() : r.horsepower();
                    // Årsmodellen skickas med av samma skäl som till motorlistan nedan: tabellen
                    // bär EN generations motorer per modell. Utan den fick en Kia Sportage (2020)
                    // gen 5:ans (2022+) förbrukning märkt som verifierad, och den siffran räknas
                    // vidare till kronor i ägandekostnaden.
                    iceVariant = iceConsumptionService.consumptionForTitle(
                            r.title(), hp, fuelPref, CarTitle.year(r.title()));
                } catch (Exception ignored) {}

                Double consumption = iceVariant != null ? iceVariant.literPerMil() * 10 : null;
                // Ingen verifierad match men AI:n svarade i l/mil-skala (< 3 kan inte vara l/100km) — normalisera
                if (consumption == null && fuelSpec.consumptionLiterPerMil() > 0
                        && fuelSpec.consumptionLiterPerMil() < 3) {
                    consumption = fuelSpec.consumptionLiterPerMil() * 10;
                }
                // Drivmedlet kommer ur SAMMA rad som förbrukningssiffran. Frontenden gissade
                // tidigare på tröskeln "> 7 l/100 km = diesel", vilket gjorde en Kia Sportage
                // 1.6 T-GDI (8,0) till diesel och en snål diesel till bensin.
                String fuel = iceVariant != null ? iceVariant.fuel() : null;
                if (consumption != null || fuel != null) fuelSpec = new com.caradvice.model.FuelSpecDto(
                        consumption != null ? consumption : fuelSpec.consumptionLiterPerMil(),
                        fuelSpec.gearbox(), fuelSpec.horsepower(), fuelSpec.engineVolumeLiters(), fuel);
            }

            if (safety == null && evSpec == null && cargo == null && blocketRange == null) {
                log.info("Ingen verifierad specdata hittades för \"{}\" — bygger enbart på AI:ns friitext", r.title());
            }

            // Ersätt AI:ns "Motor & batterialternativ"-fritext med verifierade kWh/räckvidd-varianter
            // ur ev_spec om bilen har en träff — fångar t.ex. EX30 med fabricerade 58/77/44 kWh
            // istället för de riktiga 51/65/65 kWh-varianterna.
            String engineOptions = r.engineOptions();
            if (evSpec != null) {
                try {
                    String verified = evSpecService.verifiedEngineOptions(r.title());
                    if (verified != null) engineOptions = verified;
                } catch (Exception ignored) {}
            }

            // Ersätt AI:ns gissade systemeffekt med verifierad hk för modeller där gissningen
            // historiskt varit fel (t.ex. MG Marvel R "150hk" mot riktiga 180/288).
            Integer horsepower = r.horsepower();
            try {
                Integer verifiedHp = evSpecService.getSystemPowerHk(r.title());
                if (verifiedHp != null) horsepower = verifiedHp;
            } catch (Exception ignored) {}

            // Samma verifiering för bensin-/diesel-/hybridbilar: ice_consumption-varianten som redan
            // hittades ovan (för förbrukningen) bär även riktig hk och motorbeteckning i sin variant-
            // sträng ("Golf 1.5 TSI 150 hk") — ersätter AI:ns friitext för både topplevel-hk OCH
            // fuelSpec.horsepower (frontend visar en egen chip för fuelSpec.horsepower — måste rättas
            // tillsammans, annars uppstår samma "två olika hk-tal på samma kort"-motsägelse som
            // Marvel R hade innan engineOptions verifierades där).
            if (evSpec == null && iceVariant != null) {
                Integer verifiedHp = IceConsumptionService.parseHp(iceVariant.variant());
                if (verifiedHp != null) {
                    horsepower = verifiedHp;
                    if (fuelSpec != null) fuelSpec = new com.caradvice.model.FuelSpecDto(
                            fuelSpec.consumptionLiterPerMil(), fuelSpec.gearbox(), verifiedHp,
                            fuelSpec.engineVolumeLiters(), fuelSpec.fuel());
                }
                // Hela motorutbudet, inte bara den variant förbrukningssiffran togs från —
                // elbilskorten har alltid visat sina batterivarianter som lista, medan
                // förbränningskorten visade en enda motor fast databasen bar flera.
                // Årsmodellen skickas med: tabellen bär EN generations motorer per modell, så
                // ett äldre kort ska hellre få AI:ns egen text än 2020 års motorutbud.
                //
                // Fallbacken nedan var en bakdörr så länge vakten satt enbart här: när listan
                // tystnade för en för gammal årsmodell skrevs i stället den fällda generationens
                // EGEN beteckning ut. Nu bär consumptionForTitle samma vakt, så iceVariant är
                // redan null i det läget och hela blocket hoppas över — descriptorn kan bara nå
                // hit när modellen har en verifierad rad som vakten släppt igenom.
                // Drivlinan skickas med: för ett laddhybrids- eller hybridsök beskriver den
                // ofiltrerade listan en annan bil än den kortet visar (XC60 Recharge fick
                // "D4 · D5 · B6" 2026-08-22). Bensin- och dieselsök får hela utbudet som förut.
                String kravdDrivlina = drivlinaFor(adFilter);
                String allaMotorer = iceConsumptionService.engineOptionsForTitle(
                        r.title(), CarTitle.year(r.title()), kravdDrivlina);
                // Fallbacken är en BAKDÖRR när en drivlina krävs: listan avstår korrekt när
                // modellen saknar laddbara rader, men engineDescriptor bär den enda variant
                // consumptionForTitle hittade — och den kan vara en diesel. Skarpt 2026-08-22,
                // efter att listan lagats: "Škoda Kodiaq iV" fick "2.0 TDI 200 hk" den vägen.
                // Samma sorts bakdörr som generationsvakten en gång hade.
                if (allaMotorer != null) engineOptions = allaMotorer;
                else if (kravdDrivlina == null) engineOptions = IceConsumptionService.engineDescriptor(iceVariant);
            }

            result.add(new CarRecommendation(
                    r.title(), price, utanMarknadspastaende(r.whyRecommended(), r.title()),
                    utanDubblerandeSpec(r.pros(), cargo, evSpec, r.title()), r.con(),
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, fuelSpec, blocketPrice, horsepower, engineOptions));
        }
        return result;
    }

    /**
     * Tar bort AI:ns egna marknadspåståenden ur {@code whyRecommended}.
     *
     * <p>Fältet ska bära en källa ("Teknikens Värld: toppbetyg"), men live 2026-08-10 skrev
     * modellen "Blocket-annonser visar begagnatgolv 199 000 kr för 2021-modell med 11 800 km"
     * — en påhittad siffra som renderas i kursiv stil direkt UNDER kortets verifierade prisrad,
     * där det stod 239 900–469 900 kr ur riktiga annonser. Två motstridiga Blocket-siffror med
     * två centimeters mellanrum, varav den påhittade ser mest specifik ut.
     *
     * <p>Samma skäl som bakom "FABRICERA ALDRIG PRISER": en gissad siffra bredvid en verifierad
     * är värre än ingen siffra alls. Prompten säger nu ifrån, men prompttext har visat sig vara
     * en svag garanti i det här projektet — därför tas påståendet bort i kod också. Bara den
     * meningen faller, inte hela fältet: källhänvisningen är fortfarande värd att visa.
     */
    /**
     * En fördel som upprepar bagagevolymen — "Stort bagageutrymme på 520 l". Kräver BÅDE ett
     * bagageord och en litersiffra: "smidig att lasta" är en riktig fördel och ska stå kvar.
     */
    private static final Pattern BAGAGE_UPPREPNING = Pattern.compile(
            "(?iu)(bagage|lastutrymm|lastvolym|baklucka)");
    private static final Pattern LITER_SIFFRA = Pattern.compile("(?iu)\\b\\d{3,4}\\s*(l|liter)\\b");
    /** Samma sak för räckvidden — "Räckvidd 528 km WLTP", "upp till 52 mil". */
    private static final Pattern RACKVIDD_UPPREPNING = Pattern.compile(
            "(?iu)(räckvidd|wltp|på en laddning)");
    private static final Pattern STRACKA_SIFFRA = Pattern.compile("(?iu)\\b\\d{2,4}\\s*(km|mil)\\b");

    /**
     * Fördelar som bara upprepar en siffra kortet redan visar i ett eget, VERIFIERAT fält.
     *
     * <p>Skarpt fall 2026-08-28: Kia EV6 hade "bagage 520 l" bland fördelarna medan
     * bagagefältet — hämtat ur {@code cargo_spec} — sa 490 l. Två olika tal om samma bil på
     * samma kort, och det som stod i fördelarna var AI:ns egen gissning. Samma grundproblem
     * som {@link #utanMarknadspastaende} löser för whyRecommended: en gissad siffra bredvid en
     * verifierad är värre än ingen siffra alls, för läsaren kan inte veta vilken som gäller.
     *
     * <p><b>Bara när fältet faktiskt finns.</b> Saknar bilen rad i {@code cargo_spec} är AI:ns
     * 520 l den enda uppgift som finns, och då är den bättre än tomrum — samma fail open som
     * bagagevakten och drivmedelsvakten bygger på.
     *
     * <p><b>Kräver både ord och siffra.</b> "Smidig att lasta" och "lång räckvidd i verklig
     * körning" är riktiga fördelar utan att göra anspråk på ett tal, och de ska stå kvar.
     *
     * <p><b>Tömmer aldrig listan.</b> Vore alla tre fördelarna sifferupprepningar lämnas de
     * kvar orörda: ett kort utan fördelar läser som ett renderingsfel, och priset för en
     * dubblerad siffra är lägre än priset för en tom ruta.
     */
    static List<String> utanDubblerandeSpec(List<String> pros, CargoSpecDto cargo, EvSpecDto evSpec, String title) {
        if (pros == null || pros.isEmpty()) return pros;
        List<String> kvar = new ArrayList<>();
        List<String> tappade = new ArrayList<>();
        for (String p : pros) {
            if (p == null) continue;
            boolean bagagedubblett = cargo != null && cargo.cargoLiters() > 0
                    && BAGAGE_UPPREPNING.matcher(p).find() && LITER_SIFFRA.matcher(p).find();
            boolean rackviddsdubblett = evSpec != null
                    && RACKVIDD_UPPREPNING.matcher(p).find() && STRACKA_SIFFRA.matcher(p).find();
            if (bagagedubblett || rackviddsdubblett) tappade.add(p);
            else kvar.add(p);
        }
        if (tappade.isEmpty()) return pros;
        if (kvar.isEmpty()) {
            log.info("Alla fördelar för \"{}\" var sifferupprepningar — behåller dem hellre än en tom lista", title);
            return pros;
        }
        log.info("Tog bort {} fördel(ar) som upprepade ett verifierat fält för \"{}\": {}",
                tappade.size(), title, tappade);
        return kvar;
    }

    static String utanMarknadspastaende(String why, String title) {
        if (why == null || why.isBlank()) return why;
        String[] meningar = why.split("(?<=[.!?])\\s+");
        StringBuilder kvar = new StringBuilder();
        boolean tappat = false;
        for (String m : meningar) {
            String lower = m.toLowerCase();
            boolean marknad = lower.contains("blocket") || lower.contains("annons")
                    || lower.contains("begagnatgolv") || lower.contains("mätarställning");
            if (marknad) { tappat = true; continue; }
            if (kvar.length() > 0) kvar.append(' ');
            kvar.append(m);
        }
        if (tappat) log.info("Tog bort AI:ns marknadspåstående ur whyRecommended för \"{}\": {}", title, why);
        return kvar.toString().trim();
    }

    public Result getRecommendation(CarPreferences prefs) throws Exception {
        String key = buildCacheKey(prefs); //Skapar cachen etiketten
        CacheEntry cached = cache.get(key); //Slår upp cachen i lådan av sparade svar (rå post — 429-vägen nedan får använda även en utgången)
        if (isFresh(cached)) { //Fanns det något cachat och isf är det inom 4 timmar?
            long ageSeconds = (System.currentTimeMillis() - cached.timestamp()) / 1000;
            return new Result(cached.result(), true, ageSeconds, cached.budgetShortfallFromKr()); //Ja på båda då returneras det cachade svaret går alltså inte en fråga till LLM
        } //Annars blir det alltså en fråga till LLM om cachat svar inte finns --> Sparar alltså tid o pengar

        String prompt = buildPrompt(prefs);
        String expertContext = "";
        try { expertContext = expertInsightService.buildExpertContext(prefs); } catch (Exception ignored) {}
        String systemPrompt = withEnergyPrices(buildSystemPrompt(expertContext, prefs));
        String feedbackContext = getFeedbackContext();
        if (!feedbackContext.isBlank()) systemPrompt = systemPrompt + "\n" + feedbackContext;
//Här går ett riktigt anrop ifall cachat svar ej finns ovan alltså
        Map<String, Object> primaryBody = jsonCallBody(model, 0.3, systemPrompt, prompt);
        Map<String, Object> fallbackBody = jsonCallBody(chatModel, 0.3, systemPrompt, prompt);
        Map<String, Object> reserveBody = jsonCallBody(reserveModel, 0.3, systemPrompt, prompt, RETRY_MAX_TOKENS);

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody, reserveBody);

        if (response.statusCode() == 429) {
            if (cached != null) {
                long ageSeconds = (System.currentTimeMillis() - cached.timestamp()) / 1000;
                return new Result(cached.result(), true, ageSeconds, cached.budgetShortfallFromKr());
            }
            throw new RateLimitedException(buildRateLimitError(response.body()),
                    parseRetrySeconds(response.body()));
        }
        if (response.statusCode() != 200) {
            log.error("Groq {} för getRecommendation: {}", response.statusCode(), response.body());
            throw new RuntimeException(buildGroqErrorMessage(response.statusCode(), response.body()));
        }

        java.util.function.Consumer<List<CarRecommendation>> validator = validatorFor(prefs);
        List<CarRecommendation> parsed;
        try {
            parsed = parseWithRetry(response, reserveBody, "getRecommendation", validator);
        } catch (RuleViolationException e) {
            // Både första svaret och omförsöket bröt mot en regel. Samma avvägning som
            // budgettaket gör: en kortare lista med bilar som stämmer med sökningen är mer värd
            // än ett felmeddelande.
            parsed = keepPassingCars(e, validator);
            if (parsed.isEmpty()) parsed = retryAfterRuleViolation(systemPrompt, prompt, e, validator);
            if (parsed.isEmpty()) {
                // Klarade INGEN bil kraven är tomt resultat ärligare än ett fel. Felet skyllde
                // på AI:n ("AI:n föreslog en bilmodell som inte kunde verifieras") för något
                // som oftast är ett rimligt svar på en hård fråga: sökningen har numera fem
                // vakter som kan fälla samtidigt — okänd modell, årsmodell, familjestorlek,
                // drivmedel, bagage och prisgolv — och ju fler krav, desto större chans att
                // ingenting överlever hela kedjan. Controllern sätter narrowCriteria på ett
                // tomt svar precis som på ett tunt, så användaren får kraven uppräknade i
                // stället för ett tekniskt fel. Live 2026-08-10: familjeelbil + 400 l +
                // 200 000 kr gav HTTP 500 i ena körningen och ett Tesla-kort i nästa.
                //
                // MEN bara när det var VAKTERNA som fällde. Kom svaret tomt från AI:n redan
                // från början säger det ingenting om kraven, och då blir narrowCriteria ett
                // påstående om användarens sökning utan täckning — hen sitter och lättar på
                // krav som aldrig var problemet. Skarpt 2026-08-28: elbil 350 000 kr föll så,
                // och exakt samma sökning gick igenom på tredje försöket.
                if (e instanceof TomtAiSvarException) {
                    log.warn("AI:n svarade utan bilar två gånger i rad ({}) — felet är AI:ns, inte kravens",
                            String.join(", ", activeConstraints(prefs)));
                    throw new RuntimeException("AI-tjänsten svarade utan innehåll två gånger i rad."
                            + " Försök igen — dina kriterier är inte problemet.");
                }
                log.warn("Ingen bil klarade kraven ({}) — returnerar tomt svar i stället för fel",
                        String.join(", ", activeConstraints(prefs)));
                return new Result(List.of(), false, 0, null);
            }
            log.warn("Visar {} av 3 kort — resten bröt mot en regel ({})", parsed.size(), e.getMessage());
        } catch (RuntimeException e) {
            throw medRadOmKriterier(e);
        }

        boolean isLeasing = "leasing".equals(prefs.budgetType());
        Map<String, BlocketPriceService.PriceRange> ranges = new LinkedHashMap<>();
        Map<String, Integer> nypriser = new LinkedHashMap<>();
        List<CarRecommendation> result = enrichRecommendations(parsed, prefs.kmPerYear(), prefs.fuelType(),
                isLeasing, ranges, nypriser, prefs.newCar(),
                adFilterFor(prefs.fuelType(), prefs.carCategory(), prefs.transmission()));

        // Taket gäller alla lägen. Nybilssök omfattas trots att begagnatpriset är fel måttstock
        // där, för slutsatsen håller i EN riktning: kostar billigaste BEGAGNADE exemplaret mer
        // än taket kan en NY omöjligt kosta mindre. Att hoppa över kontrollen helt var den enda
        // oskyddade vägen — live 2026-08-07 föreslogs MG4 (billigaste annons 249 900 kr) för en
        // 200 000-budget, alltså 20 000 kr över taket, utan att någon spärr utlöstes.
        //
        // Leasing stod utanför så länge Blocket inte hämtades där. Nu finns kr/mån att mäta mot,
        // och samma natt föreslogs Kia EV6 GT-Line på 8 295 kr/mån mot en 5 000-budget.
        // GOLVET, mätt mot marknaden. Taket har haft tre lager sedan 2026-08-07 — validatorn,
        // exceedsBudgetCeiling med sitt omförsök, och prompten. Golvet hade BARA promptregeln
        // ("UTNYTTJA BUDGETEN ... en billig outlier är OK men aldrig som enda nivå"), och en
        // promptregel är ett önskemål: samma lärdom som familje-, drivmedels- och SUV-vakterna,
        // och som response_format. Skarpt fall 2026-08-28: elbil, 350 000 kr, 4 passagerare,
        // 300 l bagage → Nissan Leaf och Hyundai Kona Electric, alla tre en klass under.
        //
        // Körs FÖRE takkontrollen så att båda mäter på samma data: omförsökets bilar skrivs in
        // i samma ranges/nypriser, och taket får sista ordet över det golvet släppt fram.
        //
        // Bara begagnatköp. Nypris och leasing har egna prisvärldar, precis som golvvakten
        // ovan (harGolvvakt) — och ett nybilssök kan inte "utnyttja budgeten" åt fel håll på
        // samma sätt, där är nypriset given måttstock.
        if (!isLeasing && !prefs.newCar() && !utnyttjarBudgeten(result, ranges, prefs.budget())) {
            result = retryForBudgetUsage(prefs, systemPrompt, prompt, result, ranges, nypriser);
        }

        Integer shortfall = null;
        List<CarRecommendation> over = overBudget(result, ranges, nypriser, prefs.budget(), isLeasing, prefs.newCar());
        if (!over.isEmpty()) {
            BudgetOutcome outcome = retryWithinBudget(prefs, systemPrompt, prompt, result, over, ranges,
                    nypriser, isLeasing);
            result = outcome.recommendations();
            // Banderollen skriver ut siffran som ett Blocket-pris i kronor — en månadskostnad
            // där hade läst som att bilen kostar 8 295 kr att köpa
            shortfall = isLeasing ? null : outcome.shortfallFromKr();
        }

        // Dedupen låg bara i budgetomförsöket, så utan omförsök fanns ingen kontroll alls
        List<CarRecommendation> unika = distinctModels(result);
        if (unika.size() < result.size()) {
            log.warn("AI föreslog samma modell flera gånger: {} — behåller {} av {}",
                    result.stream().map(CarRecommendation::title).toList(), unika.size(), result.size());
            result = unika;
        }

        store(key, result, shortfall);
        return new Result(result, false, 0, shortfall);
    }

    /**
     * Plockar ihop upp till tre bilar som håller budgeten ur båda försöken. Omförsöket först —
     * dess bilar valdes med budgettaket i prompten — sedan påfyllning ur ursprungssvaret.
     * Tom lista betyder att ingendera omgången gav en köpbar bil.
     *
     * Dedupen går på MODELL, inte titel: två listor som var för sig är fria från dubbletter kan
     * tillsammans innehålla samma bil i olika årsmodell. Första versionen dedupade på exakt
     * titel och gav "Volkswagen ID.4 (2022)" bredvid "Volkswagen ID.4 (2021)" — samma bil två
     * gånger, vilket promptregeln om tre OLIKA modeller uttryckligen förbjuder.
     */
    static List<CarRecommendation> mergeWithinBudget(
            List<CarRecommendation> retried, Map<String, BlocketPriceService.PriceRange> retryRanges,
            List<CarRecommendation> original, Map<String, BlocketPriceService.PriceRange> ranges,
            int budgetKr) {
        return mergeWithinBudget(retried, retryRanges, original, ranges, budgetKr, false);
    }

    static List<CarRecommendation> mergeWithinBudget(
            List<CarRecommendation> retried, Map<String, BlocketPriceService.PriceRange> retryRanges,
            List<CarRecommendation> original, Map<String, BlocketPriceService.PriceRange> ranges,
            int budgetKr, boolean leasing) {
        return mergeWithinBudget(retried, retryRanges, original, ranges, Map.of(), budgetKr, leasing, false);
    }

    static List<CarRecommendation> mergeWithinBudget(
            List<CarRecommendation> retried, Map<String, BlocketPriceService.PriceRange> retryRanges,
            List<CarRecommendation> original, Map<String, BlocketPriceService.PriceRange> ranges,
            Map<String, Integer> nypriser, int budgetKr, boolean leasing, boolean newCar) {
        List<CarRecommendation> out = new ArrayList<>();
        for (CarRecommendation r : retried) {
            if (out.size() >= 3) break;
            if (!exceedsBudgetCeiling(r, retryRanges.get(r.title()), nypriser.get(r.title()), budgetKr, leasing, newCar)
                    && out.stream().noneMatch(k -> sameModel(k.title(), r.title())))
                out.add(r);
        }
        for (CarRecommendation r : original) {
            if (out.size() >= 3) break;
            if (!exceedsBudgetCeiling(r, ranges.get(r.title()), nypriser.get(r.title()), budgetKr, leasing, newCar)
                    && out.stream().noneMatch(k -> sameModel(k.title(), r.title())))
                out.add(r);
        }
        return out;
    }

    /** Titel utan årtalsparentes, gemener — "Volkswagen ID.4 (2021)" och "(2022)" blir samma nyckel. */
    private static String modelKey(String title) {
        return title == null ? "" : CarTitle.stripYear(title).toLowerCase();
    }

    /** Modellnyckelns ord i ordning. (Egen metod: modelTokens är upptagen av modellverifieringen.) */
    private static List<String> modelKeyWords(String title) {
        String key = modelKey(title);
        return key.isEmpty() ? List.of() : List.of(key.split("\\s+"));
    }

    /**
     * Samma bil när den enas ord är en inledning av den andras: "Volkswagen ID.4" och
     * "Volkswagen ID.4 Pro" är en modell i två utrustningsnivåer, liksom "Kia EV6" och
     * "Kia EV6 GT-Line" eller "Škoda Enyaq" och "Škoda Enyaq iV".
     *
     * <p>Jämförelsen går på hela ord, aldrig på tecken: "Volvo EX30" är ingen inledning av
     * "Volvo EX300", och "Tesla Model Y" och "Tesla Model 3" skiljer sig i sista ordet.
     */
    static boolean sameModel(String a, String b) {
        List<String> ta = modelKeyWords(a), tb = modelKeyWords(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        List<String> kort = ta.size() <= tb.size() ? ta : tb;
        List<String> lang = ta.size() <= tb.size() ? tb : ta;
        return lang.subList(0, kort.size()).equals(kort);
    }

    /**
     * Tre förslag ska vara tre OLIKA modeller. Promptregeln säger det, men inget höll AI:n till
     * den utanför budgetomförsöket: live 2026-08-07 kom "Volkswagen ID.4 (2024)" och
     * "Volkswagen ID.4 Pro (2022)" i samma svar. Dubbletten tas bort i stället för att ersättas
     * — två olika bilar är mer värt än tre kort där två är samma bil.
     */
    static List<CarRecommendation> distinctModels(List<CarRecommendation> recs) {
        List<CarRecommendation> out = new ArrayList<>();
        for (CarRecommendation r : recs) {
            if (out.stream().noneMatch(k -> sameModel(k.title(), r.title()))) out.add(r);
        }
        return out;
    }

    /** Rekommendationer vars billigaste Blocket-annons — eller nypris när annonser saknas — ligger över taket. */
    /** Andel av budgeten som minst en bil måste nå för att svaret ska räknas som ett svar på frågan. */
    static final double BUDGET_GOLV_ANDEL = 0.70;

    /**
     * Når någon av bilarna upp mot budgeten?
     *
     * <p><b>Mätt på DYRASTE annonsen, inte på golvet.</b> Golvet är billigaste exemplaret, och
     * en Škoda Enyaq med golv 279 000 kr är ett utmärkt svar på 350 000 — man får en nyare och
     * bättre utrustad. Frågan är om det över huvud taget FINNS exemplar av modellen i
     * budgetens närhet. Når inte ens den dyraste annonsen dit är modellen en klass under det
     * användaren bett om, och då är svaret fel oavsett hur prisvärd bilen är.
     *
     * <p><b>Fäller bara på positivt bevis</b>, precis som drivmedels- och SUV-vakterna: en bil
     * utan Blocket-data kan inte dömas, och finns ingen mätt bil alls svarar metoden ja. Två
     * annonser krävs av samma skäl som i correctedPrice — en ensam fel- eller scamannons ska
     * varken fria eller fälla.
     */
    static boolean utnyttjarBudgeten(List<CarRecommendation> cars,
                                     Map<String, BlocketPriceService.PriceRange> ranges, int budgetKr) {
        int krav = (int) Math.round(budgetKr * BUDGET_GOLV_ANDEL);
        boolean nagonMatt = false;
        for (CarRecommendation r : cars) {
            BlocketPriceService.PriceRange pr = ranges.get(r.title());
            if (pr == null || pr.count() < 2) continue;
            nagonMatt = true;
            if (pr.maxKr() >= krav) return true;
        }
        return !nagonMatt;
    }

    /**
     * Ett omförsök när ingen av bilarna når budgeten — och det ORIGINALET behålls om omförsöket
     * inte blev bättre.
     *
     * <p>Skillnaden mot takets omförsök är viktig: en bil över taket går inte att köpa, alltså
     * MÅSTE den bort. En bil under budget är fullt köpbar, bara ett svar på en annan fråga —
     * därför får den här vakten aldrig göra svaret tommare. Omförsöket vinner bara om det både
     * utnyttjar budgeten OCH håller taket; i alla andra utfall står originalet kvar.
     */
    private List<CarRecommendation> retryForBudgetUsage(CarPreferences prefs, String systemPrompt, String prompt,
                                                        List<CarRecommendation> original,
                                                        Map<String, BlocketPriceService.PriceRange> ranges,
                                                        Map<String, Integer> nypriser) {
        String namn = original.stream().map(r -> {
            BlocketPriceService.PriceRange pr = ranges.get(r.title());
            return pr == null ? r.title()
                    : r.title() + " (dyraste annons " + formatSekSpace(pr.maxKr()) + " kr)";
        }).collect(java.util.stream.Collectors.joining(", "));
        log.warn("Ingen bil når {} % av budgeten {} kr: {} — omförsök",
                Math.round(BUDGET_GOLV_ANDEL * 100), prefs.budget(), namn);

        String skarptPrompt = prompt + String.format("""

                VIKTIGT — FÖRRA FÖRSÖKET UTNYTTJADE INTE BUDGETEN: %s. Inte ens de dyraste
                annonserna för de bilarna ligger i närheten av budgeten %,d kr, alltså är de en
                klass under det användaren frågat efter. Föreslå minst TVÅ bilar där %,d kr
                räcker till något nyare, rymligare eller bättre utrustat. Den tredje får vara
                ett billigare prisvärt alternativ.
                """, namn, prefs.budget(), prefs.budget())
                + ovreDelenAvBudgeten(prefs.budget());

        try {
            Map<String, Object> body = jsonCallBody(model, 0.3, systemPrompt, skarptPrompt);
            Map<String, Object> fallback = jsonCallBody(chatModel, 0.3, systemPrompt, skarptPrompt);
            Map<String, Object> reserve = jsonCallBody(reserveModel, 0.3, systemPrompt, skarptPrompt);
            HttpResponse<String> response = callGroqWithFallback(body, fallback, reserve);
            if (response.statusCode() != 200) return original;

            List<CarRecommendation> parsed = parseWithRetry(response, reserve,
                    "getRecommendation (budgetgolv)", validatorFor(prefs));
            // Samma ranges/nypriser som originalet: takkontrollen nedanför läser dem, och en
            // egen karta hade lämnat de nya titlarna omätta och därmed osynliga för taket.
            List<CarRecommendation> retried = enrichRecommendations(parsed, prefs.kmPerYear(),
                    prefs.fuelType(), false, ranges, nypriser, prefs.newCar(),
                    adFilterFor(prefs.fuelType(), prefs.carCategory(), prefs.transmission()));

            if (retried.isEmpty()) return original;
            if (!utnyttjarBudgeten(retried, ranges, prefs.budget())) {
                log.warn("Omförsöket nådde inte heller budgeten — behåller originalet");
                return original;
            }
            if (!overBudget(retried, ranges, nypriser, prefs.budget(), false, prefs.newCar()).isEmpty()) {
                log.warn("Omförsöket utnyttjade budgeten men bröt taket — behåller originalet");
                return original;
            }
            log.info("Budgetgolvet: omförsöket gav {}", retried.stream().map(CarRecommendation::title).toList());
            return retried;
        } catch (Exception e) {
            log.warn("Omförsöket för budgetgolvet misslyckades: {} — behåller originalet", e.toString());
            return original;
        }
    }

    private static List<CarRecommendation> overBudget(List<CarRecommendation> recs,
                                                      Map<String, BlocketPriceService.PriceRange> ranges,
                                                      Map<String, Integer> nypriser,
                                                      int budgetKr, boolean leasing, boolean newCar) {
        List<CarRecommendation> over = new ArrayList<>();
        for (CarRecommendation r : recs) {
            if (exceedsBudgetCeiling(r, ranges.get(r.title()), nypriser.get(r.title()), budgetKr, leasing, newCar))
                over.add(r);
        }
        return over;
    }

    /**
     * Ett omförsök där de för dyra bilarna pekas ut vid namn med sitt verkliga Blocket-pris.
     * Först efter berikningen vet vi vad bilarna faktiskt kostar, så det här kan inte ligga i
     * parseWithRetry med de andra regelvakterna — därför en egen, senare runda.
     *
     * Resultatet plockas ihop av de bilar som HÅLLER budgeten ur båda försöken, dedupat på
     * titel, upp till tre. Första versionen behöll i stället hela ursprungssvaret när
     * omförsöket inte blev bättre, och släppte då igenom en Volvo EX40 på 439 000 kr mot en
     * 275 000-budget — 164 000 kr över taket. En kortare lista med köpbara bilar är mer värd
     * än en full lista där en bil inte går att köpa.
     *
     * Bara om ingendera omgången gav en enda bil inom budget faller vi tillbaka på
     * ursprungssvaret — tomt resultat hjälper ingen.
     */
    private BudgetOutcome retryWithinBudget(CarPreferences prefs, String systemPrompt, String prompt,
                                            List<CarRecommendation> original, List<CarRecommendation> over,
                                            Map<String, BlocketPriceService.PriceRange> ranges,
                                            Map<String, Integer> nypriser, boolean leasing) {
        int marginal = leasing ? LEASING_CEILING_MARGIN_KR : BUDGET_CEILING_MARGIN_KR;
        StringBuilder namn = new StringBuilder();
        for (CarRecommendation r : over) {
            BlocketPriceService.PriceRange pr = ranges.get(r.title());
            if (namn.length() > 0) namn.append(", ");
            namn.append(r.title());
            if (leasing) {
                if (pr != null) namn.append(" (billigaste leasing ")
                        .append(formatSekSpace(pr.minKr())).append(" kr/mån)");
                continue;
            }
            VerifiedFloor golv = verifiedFloor(r, pr, nypriser.get(r.title()), prefs.newCar());
            // Säg alltid vilken sorts pris siffran är, annars ljuger prompten om marknaden
            if (golv != null) namn.append(golv.fromBlocket()
                    ? " (billigaste annons " + formatSekSpace(golv.kr()) + " kr)"
                    : " (nypris fr. " + formatSekSpace(golv.kr()) + " kr)");
        }
        log.warn("AI föreslog bil(ar) över budgettaket {} + {} {}: {} — omförsök",
                prefs.budget(), marginal, leasing ? "kr/mån" : "kr", namn);

        // "Äldre årsmodell" är fel råd i ett nybilssök — där återstår enklare nivå eller billigare
        // märke. I leasing finns dessutom bindningstiden och milpaketet att skruva på.
        String utvagar = leasing
                ? "enklare utrustningsnivå, ett billigare märke i samma storleksklass eller en längre bindningstid"
                : prefs.newCar()
                    ? "enklare utrustningsnivå eller ett billigare märke i samma storleksklass"
                    : "äldre årsmodell, enklare utrustningsnivå eller ett billigare märke i samma storleksklass";
        String skarptPrompt = prompt + (leasing
                ? String.format("""

                    VIKTIGT — FÖRRA FÖRSÖKET BRÖT MOT BUDGETEN: %s. Budgettaket är %,d kr/mån
                    (budget + %,d kr/mån) räknat på BILLIGASTE privatleasingannonsen på Blocket.
                    Föreslå andra bilar som faktiskt går att leasa för pengarna: %s.
                    """, namn, prefs.budget() + marginal, marginal, utvagar)
                : String.format("""

                    VIKTIGT — FÖRRA FÖRSÖKET BRÖT MOT BUDGETEN: %s. Budgettaket är %,d kr
                    (budget + %,d kr) räknat på %s. Föreslå andra bilar som faktiskt går att
                    köpa för pengarna: %s.
                    """, namn, prefs.budget() + marginal, marginal,
                        prefs.newCar() ? "NYPRISET" : "BILLIGASTE annonsen på Blocket", utvagar));

        try {
            Map<String, Object> body = jsonCallBody(model, 0.3, systemPrompt, skarptPrompt);
            Map<String, Object> fallback = jsonCallBody(chatModel, 0.3, systemPrompt, skarptPrompt);
            Map<String, Object> reserve = jsonCallBody(reserveModel, 0.3, systemPrompt, skarptPrompt);
            HttpResponse<String> response = callGroqWithFallback(body, fallback, reserve);
            if (response.statusCode() != 200)
                return new BudgetOutcome(original, cheapest(over, ranges, nypriser, prefs.newCar()));

            List<CarRecommendation> parsed = parseWithRetry(response, reserve, "getRecommendation (budgettak)",
                    validatorFor(prefs));
            Map<String, BlocketPriceService.PriceRange> retryRanges = new LinkedHashMap<>();
            Map<String, Integer> retryNypriser = new LinkedHashMap<>();
            List<CarRecommendation> retried = enrichRecommendations(
                    parsed, prefs.kmPerYear(), prefs.fuelType(), leasing, retryRanges, retryNypriser, prefs.newCar(),
                    adFilterFor(prefs.fuelType(), prefs.carCategory(), prefs.transmission()));

            Map<String, Integer> allaNypriser = new LinkedHashMap<>(nypriser);
            allaNypriser.putAll(retryNypriser);
            List<CarRecommendation> withinBudget = mergeWithinBudget(retried, retryRanges, original, ranges,
                    allaNypriser, prefs.budget(), leasing, prefs.newCar());

            if (withinBudget.isEmpty()) {
                // Kriterierna går inte ihop — typiskt låg budget plus hårt ålderskrav. Korten
                // visas ändå, men frontend måste kunna säga VARFÖR de ligger över budget:
                // utan det läser tre bilar till dubbla priset som en trasig rekommendation.
                Map<String, BlocketPriceService.PriceRange> alla = new LinkedHashMap<>(ranges);
                alla.putAll(retryRanges);
                Integer from = cheapest(concat(original, retried), alla, allaNypriser, prefs.newCar());
                log.warn("Budgetomförsök: ingen bil inom budget i någondera omgången — behåller första svaret,"
                        + " billigaste verkliga pris {} kr mot budget {} kr", from, prefs.budget());
                return new BudgetOutcome(original, from);
            }
            log.info("Budgettak: {} bil(ar) inom budget efter omförsök (var {} av {} över taket)",
                    withinBudget.size(), over.size(), original.size());
            return new BudgetOutcome(withinBudget, null);
        } catch (Exception e) {
            log.warn("Budgetomförsök misslyckades: {} — filtrerar ursprungssvaret", e.getMessage());
            List<CarRecommendation> kept = new ArrayList<>(original);
            kept.removeAll(over);
            return kept.isEmpty()
                    ? new BudgetOutcome(original, cheapest(over, ranges, nypriser, prefs.newCar()))
                    : new BudgetOutcome(kept, null);
        }
    }

    /** En bil som faktiskt går att köpa för budgeten, med sitt verkliga lägstapris. */
    public record BudgetAlternative(String title, int fromKr) {}

    /**
     * Vad räcker budgeten till om ålderskravet lyfts? Körs bara när den vanliga sökningen
     * inte hittade en enda bil inom taket, och besvarar frågan användaren egentligen har:
     * "vad kan jag köpa för pengarna?"
     *
     * <p>Bakgrund: 100 000 kr + max 3 år gav MG4 från 249 900 kr — 2,5x budgeten. Men det
     * finns elbilar i den prisklassen, de är bara äldre: MG ZS EV kring 120 000 kr och
     * Nissan Leaf ännu billigare. Att svara "kriterierna går inte ihop" är korrekt men
     * onödigt torftigt när svaret "för 100 000 kr är det 5–10 år gamla elbilar som gäller,
     * till exempel dessa" går att räkna fram.
     *
     * <p>Egen endpoint, inte del av rekommendationssvaret: det hade lagt ett tredje
     * Groq-anrop plus Blocket-uppslag i en begäran som redan har 35 s klienttimeout.
     * Frontend hämtar raden efter att korten och banderollen ritats.
     *
     * @return upp till tre bilar inom budgettaket, tom lista om ingen hittades
     */
    public List<BudgetAlternative> findBudgetAlternatives(CarPreferences prefs) throws Exception {
        CarPreferences utanAlderskrav = new CarPreferences(
                prefs.budget(), prefs.carCategory(), prefs.hasCharger(), prefs.kmPerYear(),
                prefs.usage(), prefs.passengers(), prefs.newCar(), prefs.fuelType(),
                prefs.transmission(), prefs.budgetType(), null, prefs.minCargoLiters());

        String key = "budgetalt|" + buildCacheKey(utanAlderskrav);
        CacheEntry cached = cache.get(key);
        if (isFresh(cached)) return toAlternatives(cached.result(), cached.alternativeRanges());

        String expertContext = "";
        try { expertContext = expertInsightService.buildExpertContext(utanAlderskrav); } catch (Exception ignored) {}
        String systemPrompt = withEnergyPrices(
                buildSystemPrompt(expertContext, prefs));
        String prompt = buildPrompt(utanAlderskrav) + String.format("""

                VIKTIGT — VAD RÄCKER BUDGETEN TILL? Bortse HELT från ålderskrav den här
                gången: årsmodellen får vara hur gammal som helst. Budgettaket är %,d kr
                räknat på BILLIGASTE annonsen på Blocket, och det är hårt. Föreslå de bilar
                som faktiskt går att köpa för pengarna, även om de är 5–10 år gamla.
                """, prefs.budget() + BUDGET_CEILING_MARGIN_KR);

        Map<String, Object> body = jsonCallBody(model, 0.3, systemPrompt, prompt);
        Map<String, Object> fallback = jsonCallBody(chatModel, 0.3, systemPrompt, prompt);
        Map<String, Object> reserve = jsonCallBody(reserveModel, 0.3, systemPrompt, prompt);
        HttpResponse<String> response = callGroqWithFallback(body, fallback, reserve);
        if (response.statusCode() != 200) return List.of();

        List<CarRecommendation> parsed = parseWithRetry(response, reserve, "findBudgetAlternatives");
        Map<String, BlocketPriceService.PriceRange> ranges = new LinkedHashMap<>();
        List<CarRecommendation> enriched = enrichRecommendations(
                parsed, prefs.kmPerYear(), prefs.fuelType(), false, ranges, null, false,
                adFilterFor(prefs.fuelType(), prefs.carCategory(), prefs.transmission()));

        List<CarRecommendation> inomBudget = new ArrayList<>();
        for (CarRecommendation r : enriched) {
            if (!exceedsBudgetCeiling(r, ranges.get(r.title()), prefs.budget())) inomBudget.add(r);
        }
        storeAlternatives(key, inomBudget, ranges);
        log.info("Budgetalternativ utan ålderskrav: {} av {} inom taket för budget {} kr",
                inomBudget.size(), enriched.size(), prefs.budget());
        return toAlternatives(inomBudget, ranges);
    }

    /** Bara bilar med känt Blocket-pris — utan pris kan banderollen inte säga något vettigt. */
    private static List<BudgetAlternative> toAlternatives(
            List<CarRecommendation> recs, Map<String, BlocketPriceService.PriceRange> ranges) {
        if (ranges == null) return List.of();
        List<BudgetAlternative> out = new ArrayList<>();
        for (CarRecommendation r : recs) {
            BlocketPriceService.PriceRange pr = ranges.get(r.title());
            if (pr == null || pr.minKr() <= 0) continue;
            out.add(new BudgetAlternative(r.title(), pr.minKr()));
            if (out.size() == 3) break;
        }
        return out;
    }

    private static List<CarRecommendation> concat(List<CarRecommendation> a, List<CarRecommendation> b) {
        List<CarRecommendation> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Billigaste kända Blocket-pris i urvalet, null om ingen av bilarna har ett pris.
     * Medvetet utan nyprisfallback: siffran hamnar i banderollen som "... på Blocket just nu".
     */
    static Integer cheapest(List<CarRecommendation> recs, Map<String, BlocketPriceService.PriceRange> ranges) {
        return cheapest(recs, ranges, Map.of(), false);
    }

    /**
     * I nybilssök är siffran billigaste NYPRIS i stället — banderollen säger då "som ny" och
     * inte "på Blocket just nu". Ett begagnatpris där hade svarat på en fråga användaren inte
     * ställde: att en ny bil inte får plats i budgeten blir inte sant för att en begagnad gör det.
     */
    static Integer cheapest(List<CarRecommendation> recs, Map<String, BlocketPriceService.PriceRange> ranges,
                            Map<String, Integer> nypriser, boolean newCar) {
        Integer min = null;
        for (CarRecommendation r : recs) {
            Integer pris = null;
            if (newCar) {
                Integer nypris = nypriser.get(r.title());
                if (nypris == null && r.evSpec() != null && r.evSpec().priceKr() > 0) nypris = r.evSpec().priceKr();
                pris = nypris;
            } else {
                BlocketPriceService.PriceRange pr = ranges.get(r.title());
                if (pr != null && pr.minKr() > 0) pris = pr.minKr();
            }
            if (pris == null || pris <= 0) continue;
            if (min == null || pris < min) min = pris;
        }
        return min;
    }

    private synchronized void refreshPricesIfNeeded() {
        if (System.currentTimeMillis() - pricesCachedAt < PRICES_TTL_MS) return;
        try { cachedIcePrices = newCarPriceService.buildPriceReferenceContext(); } catch (Exception e) { cachedIcePrices = ""; }
        try { cachedEvPrices = evSpecService.buildPriceReferenceContext(); } catch (Exception e) { cachedEvPrices = ""; }
        try { knownModelTokenSets = buildKnownModelTokenSets(); } catch (Exception ignored) {}
        pricesCachedAt = System.currentTimeMillis();
    }

    private String getIcePrices() { refreshPricesIfNeeded(); return cachedIcePrices; }
    private String getEvPrices()  { refreshPricesIfNeeded(); return cachedEvPrices; }

    /** Ordmängder för varje känt bilnamn ur cargo_spec + ev_spec + ice_consumption (~700+ modeller). */
    private List<Set<String>> buildKnownModelTokenSets() {
        Set<String> names = new LinkedHashSet<>();
        try { names.addAll(cargoSpecService.findAllCarNames()); } catch (Exception ignored) {}
        try { names.addAll(evSpecService.findAllCarNames()); } catch (Exception ignored) {}
        try { names.addAll(iceConsumptionService.allModelNames()); } catch (Exception ignored) {}
        List<Set<String>> tokenSets = new ArrayList<>();
        for (String name : names) {
            Set<String> tokens = modelTokens(name);
            if (tokens.size() >= 2) tokenSets.add(tokens);
        }
        return tokenSets;
    }

    /** Gemener, diakritik bortnormaliserad, uppdelat på ord — samma mönster som WebInsightScraperService.modelTokens. */
    private static Set<String> modelTokens(String s) { //Hallucinationsvakt styckar upp bilnamn blir tokenset i ord och kollar requireKnownModels i databas
        if (s == null) return Set.of();
        String norm = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase();
        Set<String> tokens = new HashSet<>();
        for (String t : norm.split("[^\\p{L}\\p{N}]+")) {
            if (!t.isBlank()) tokens.add(t);
        }
        return tokens;
    }

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

    /** Färsk = finns och är yngre än TTL:n. Enda stället TTL-aritmetiken står — anropas från båda cachevägarna. */
    private static boolean isFresh(CacheEntry entry) {
        return entry != null && System.currentTimeMillis() - entry.timestamp() < CACHE_TTL_MS;
    }

    /** Städa vid behov och lägg in svaret med färsk tidsstämpel. */
    private void store(String key, List<CarRecommendation> result, Integer budgetShortfallFromKr) {
        evictIfNeeded();
        cache.put(key, new CacheEntry(result, System.currentTimeMillis(), budgetShortfallFromKr, null));
    }

    /** Budgetalternativen cachas med sina Blocket-priser — se {@link CacheEntry}. */
    private void storeAlternatives(String key, List<CarRecommendation> result,
                                   Map<String, BlocketPriceService.PriceRange> ranges) {
        evictIfNeeded();
        cache.put(key, new CacheEntry(result, System.currentTimeMillis(), null, ranges));
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
        if (isFresh(cachedCompare)) return cachedCompare.result();

        com.caradvice.model.CargoSpecDto prefCargo1 = null, prefCargo2 = null;
        com.caradvice.model.EvSpecDto prefEv1 = null, prefEv2 = null;
        try { prefCargo1 = cargoSpecService.formatForTitle(car1); } catch (Exception ignored) {}
        try { prefCargo2 = cargoSpecService.formatForTitle(car2); } catch (Exception ignored) {}
        try { prefEv1 = evSpecService.formatForTitle(car1, 15000); } catch (Exception ignored) {}
        try { prefEv2 = evSpecService.formatForTitle(car2, 15000); } catch (Exception ignored) {}

        String specContext = buildCompareSpecContext(car1, prefCargo1, prefEv1, car2, prefCargo2, prefEv2);
        String userPrompt = "Jämför dessa exakt 2 bilar: 1. " + car1 + "  2. " + car2;
        if (!specContext.isBlank()) userPrompt += "\n\nVerifierade specifikationer från databas:\n" + specContext;
        String compareSystemPrompt = withEnergyPrices(buildCompareSystemPrompt());

        Map<String, Object> primaryBody = jsonCallBody(model, 0.2, compareSystemPrompt, userPrompt);
        Map<String, Object> fallbackBody = jsonCallBody(chatModel, 0.2, compareSystemPrompt, userPrompt);
        Map<String, Object> reserveBody = jsonCallBody(reserveModel, 0.2, compareSystemPrompt, userPrompt);

        HttpResponse<String> response = callGroqWithFallback(primaryBody, fallbackBody, reserveBody);

        // Samma nödutgång som getRecommendation: hellre ett utgånget svar än ett felmeddelande
        if (response.statusCode() == 429) {
            if (cachedCompare != null) return cachedCompare.result();
            throw new RateLimitedException(buildRateLimitError(response.body()),
                    parseRetrySeconds(response.body()));
        }
        if (response.statusCode() != 200)
            throw new RuntimeException(buildGroqErrorMessage(response.statusCode(), response.body()));

        List<CarRecommendation> parsed = parseWithRetry(response, reserveBody, "compareSpecific");
        parsed = utanPahittadArsmodell(parsed, car1, car2);

        List<CarRecommendation> result = enrichRecommendations(parsed, 15000);
        store(compareCacheKey, result, null);   // jämförelsen har ingen budget att bryta mot
        return result;
    }

    /**
     * Korttitlarna utan AI:ns egen årsmodell — men bara när användaren själv inte angav någon.
     *
     * <p>Systemprompten ber om titeln på formen {@code "Märke Modell (år)"}, och AI:n fyller i ett
     * årtal även när frågan bara var två modellnamn. I en jämförelse är den siffran en gissning,
     * och den <b>styr vår egen datahämtning</b>: {@code keepGenerationForYear} väljer generation
     * ur den, varpå {@code verifiedEngineOptions} slutar visa modellens övriga generationer.
     *
     * <p><b>Uppmätt skarpt mot {@code /api/compare-cars} 2026-08-29:</b> en jämförelse mellan
     * {@code Polestar 2} och {@code Polestar 2 Long Range 75 kWh} gav korten "Polestar 2 (2024)"
     * och "Polestar 2 Long Range (2024)" med en variant var — 79 kWh (659 km) mot 75 kWh (515 km)
     * — utan att någonstans säga att den senare är 2020 års bil. Årtalet var dessutom fel på just
     * det kortet: raden är förfaceliften, som slutade säljas 2023. Generationsmärkningen i
     * variantlistan kunde aldrig slå till, eftersom listan efter årsfiltret bara bar EN generation.
     *
     * <p>Anger användaren själv ett årtal ("Polestar 2 2021") är det inte en gissning, och då rörs
     * titeln inte — filtret ska då göra precis det det är till för.
     */
    static List<CarRecommendation> utanPahittadArsmodell(List<CarRecommendation> rader, String car1, String car2) {
        if (rader == null) return null;
        if (EvSpecService.modelYear(car1) != 0 || EvSpecService.modelYear(car2) != 0) return rader;
        return rader.stream().map(r -> new CarRecommendation(
                        CarTitle.stripYear(r.title()), r.price(), r.whyRecommended(), r.pros(), r.con(),
                        r.fitSummary(), r.expertOpinion(), r.safetyRating(), r.evSpec(), r.cargoSpec(),
                        r.fuelSpec(), r.blocketPrice(), r.horsepower(), r.engineOptions()))
                .collect(java.util.stream.Collectors.toList());
    }

    String buildCompareSystemPrompt() {
        String icePrices = getIcePrices();
        String evPrices = getEvPrices();
        return """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Jämför EXAKT de 2 bilar användaren anger. Svara ENDAST med JSON (EXAKT 2 bilar):
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"bilens styrka","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"vem passar bilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade; elbil: '51 kWh 170hk (420km)'","fuelSpec":null}]}
                OBLIGATORISKT: horsepower (systemeffekt i hk som heltal, ALDRIG null — elbil ex: EX30=200hk, Model Y=300hk). Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"endast växellådan, t.ex. Automat 8-växlad — aldrig motor-/turbobeteckning","horsepower":N,"engineVolumeLiters":X.X}. Elbil/laddhybrid: fuelSpec=null.
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
            // Årsmodellen ur namnet: medianen räknas på tabellens rader, och de beskriver EN
            // generation. En för gammal bil ska inte få en nyare generations siffra i prompten.
            String summary = iceConsumptionService.consumptionSummaryForTitle(
                    carName, CarTitle.year(carName));
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
        // RÄTTELSEFÖRSÖK i stället för ett rakt fel (2026-08-28). Svaret var GILTIG JSON — det
        // är inte samma sak som "AI-svaret blev ofullständigt" — men innehöll noll användbara
        // bilar. Förr kastades felet rakt ut, och det enda omförsök som fanns skickade SAMMA
        // prompt till reservmodellen utan att säga vad som var fel; koden konstaterar redan på
        // två andra ställen att det ofta ger samma fel igen.
        //
        // RuleViolationException är kanalen getRecommendation redan byggt för "svaret dög
        // inte": tom kvar-lista leder rakt till retryAfterRuleViolation, som pekar ut felet och
        // upprepar kravet som en instruktion. Samma grepp som budgettaket och regelvakterna.
        // Skarpt fall: elbil 350 000 kr, andra försöket av tre.
        throw new TomtAiSvarException("AI:n returnerade ett oväntat svar. Försök igen.",
                "Ditt förra svar innehöll INGA bilar i fältet \"recommendations\" — listan var tom"
                + " eller låg under fel nyckel. Svara med EXAKT 3 bilar i en lista under nyckeln"
                + " \"recommendations\", i formatet som beskrivs ovan.");
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

    /**
     * Småbilsmarkörer — spegel av FAMILJEBIL-regelns förbudslista i systemprompten.
     *
     * <p>VW ID.3 stod här men är borttagen: den är Golf-klass med fem säten, alltså samma
     * storleksklass som MG4 — som prompten samtidigt rekommenderar som familjeelbil. Att
     * förbjuda den ena och föreslå den andra var en motsägelse i regeln, inte en gräns.
     */
    private static final List<String> SMALL_CAR_MARKERS = List.of(
            "zoe", "renault 5", "clio", "twingo", "dacia spring", "spring electric",
            "ë-c3", "e-c3", "fiat 500", "500e", "panda", "corsa", "aygo",
            "picanto", "i10", "e-up", "up!", "mii", "citigo");

    /**
     * Modeller som INTE är SUV — halvkombier, sedaner och fastbacks som ändå dyker upp när
     * kategorin är "suv".
     *
     * <p>Skarpt fall 2026-08-22: ett SUV-sök på elbil med 400 000 kr i budget gav <b>Kia Niro
     * EV, MG4 och Hyundai Kona Electric</b>. MG4 är en halvkombi, och Niro och Kona är låga
     * crossovers — ingen av dem är den höga bilen kategorin lovar. Promptregeln räckte inte,
     * av exakt samma skäl som familjekravet och drivmedelskravet behövde kodstöd: en regel som
     * bara står i prompten är ett önskemål.
     *
     * <p><b>Fäller bara på positivt bevis</b>, precis som {@link #requirePureEvCars}. Listan
     * är namngivna modeller vi vet är låga — en okänd modell släpps igenom hellre än att en
     * riktig SUV kastas för att den saknas i en lista.
     *
     * <p>Niro och Kona står med efter användarens uttryckliga besked: de marknadsförs som SUV
     * men är inte de "höga bilar" kategorin ska ge. Gränsen går vid XC40/Kamiq-höjd och uppåt.
     */
    private static final List<String> NON_SUV_MARKERS = List.of(
            "mg4", "mg 4", "mg5", "mg 5", "id.3", "id3", "model 3", "polestar 2",
            "zoe", "leaf", "e-golf", "golf", "ioniq 6", "i4", "ë-c4", "e-c4",
            "niro", "kona", "corsa", "megane", "id.7", "civic", "octavia", "passat");

    /**
     * Lanseringsår per modell — spegel av årsmodellregeln i systemprompten ("Rekommendera ALDRIG
     * en årsmodell före modellens verkliga lansering").
     *
     * <p>Bara modeller vars lanseringsår faktiskt är kontrollerat står här, och listan är
     * medvetet densamma som promptens. En gissad siffra fäller riktiga bilar: hade tabellen
     * fyllts ur {@code new_car_price} — som ser ut att bära årtal i generationsnamnen — hade
     * "Volkswagen Polo 2018-2021" gjort 2015 års Polo till en påhittad bil, fast tabellen bara
     * saknar äldre generationer. Utökas listan måste årtalet vara verifierat, inte härlett.
     *
     * <p>Nycklarna matchas mot titeln utan årtal, gemener. Modellhallucinationsvakten fångar
     * inte det här: {@code requireKnownModels} godkänner "Kia EV3 (2022)" eftersom modellen
     * finns i databasen — det är bara årsmodellen som inte existerar. Skarpt fall 2026-08-09.
     */
    private static final Map<String, Integer> MODEL_LAUNCH_YEAR = Map.ofEntries(
            Map.entry("kia ev2", 2026),
            Map.entry("kia ev3", 2024),
            Map.entry("kia ev4", 2025),
            Map.entry("kia ev5", 2025),
            Map.entry("renault 5 e-tech", 2024),
            Map.entry("citroën ë-c3", 2024),
            Map.entry("citroen e-c3", 2024),
            Map.entry("volvo ex30", 2023),
            Map.entry("golf gte", 2014),
            Map.entry("outlander phev", 2013),
            Map.entry("passat gte", 2015));

    /** Kontrollerat lanseringsår för titelns modell, eller null när modellen inte står i listan. */
    static Integer launchYearFor(String title) {
        if (title == null) return null;
        String utanAr = CarTitle.stripYear(title).toLowerCase();
        Integer tidigast = null;
        for (Map.Entry<String, Integer> e : MODEL_LAUNCH_YEAR.entrySet()) {
            // Längsta träffen vinner så "Kia EV4 Long Range" inte råkar matcha en kortare nyckel
            if (utanAr.contains(e.getKey()) && (tidigast == null || e.getValue() > tidigast))
                tidigast = e.getValue();
        }
        return tidigast;
    }

    /**
     * Skarpt läge: årsmodellregeln i kod. Live 2026-08-09 föreslogs "Kia EV3 (2022)" — EV3
     * lanserades 2024, så den årsmodellen finns inte att köpa. Regeln stod bara i prompten och
     * modellvakten släppte igenom bilen eftersom EV3 finns i databasen.
     *
     * <p>Fäller bara på POSITIVT bevis: en modell utanför listan, eller en titel utan årtal,
     * går alltid igenom.
     */
    static void requireRealisticModelYears(List<CarRecommendation> parsed) {
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            Integer lansering = launchYearFor(r.title());
            Integer arsmodell = CarTitle.year(r.title());
            if (lansering != null && arsmodell != null && arsmodell < lansering)
                avvisade.add(r.title() + " (lanserad " + lansering + ")");
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog årsmodell(er) före modellens lansering: {} — {} bil(ar) kvar",
                String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException(
                "AI:n föreslog en årsmodell som inte finns. Försök igen.", kvar, avvisade,
                "Årsmodellen måste finnas: föreslå ALDRIG en årsmodell före modellens lansering.");
    }

    /**
     * Kategori familjebil eller 5+ passagerare kräver familjestor bil — speglar FAMILJEBIL-regeln.
     * Användning "familj" täcks också: äldre inklistrade WordPress-snippets skickar den fortfarande.
     *
     * <p>Gränsen gick vid 4 och det var formulärets DEFAULTVÄRDE, så det strängaste läget var
     * påslaget för alla som inte rörde fältet. Skarpt fall 2026-08-09: ett elbilssök på
     * 225 000 kr gav två kort, medan samma sökning med två passagerare gav tre — Renault Zoe,
     * Nissan Leaf och MG ZS EV, alltså precis det billiga elbilsutbudet som spärren höll ute.
     * Fyra personer får plats i en Golf-klassad bil; först vid fem börjar storleken avgöra.
     */
    static boolean requiresFamilySizedCar(CarPreferences prefs) {
        return (prefs.carCategory() != null && prefs.carCategory().toLowerCase().contains("familj"))
                || (prefs.usage() != null && prefs.usage().toLowerCase().contains("familj"))
                || prefs.passengers() >= 5;
    }

    /**
     * Skarpt läge: "Renault Zoe (2023)" föreslogs för familjekörning med 300k-budget trots
     * promptregeln — ett regelbrott triggar omförsöket med reservmodellen i parseWithRetry.
     */
    static void requireFamilySizedCars(List<CarRecommendation> parsed) {
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            String t = r.title() == null ? "" : r.title().toLowerCase();
            if (SMALL_CAR_MARKERS.stream().anyMatch(t::contains)) avvisade.add(r.title());
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog småbil(ar) till familjeprofil: {} — {} bil(ar) kvar",
                String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException("AI:n föreslog en för liten bil för profilen. Försök igen.", kvar, avvisade,
                "Alla tre bilar måste vara familjestora: kombi, SUV eller rymlig halvkombi/sedan"
                + " i storleksklass MG4/VW ID.4 eller större. ALDRIG småbil eller stadsbil.");
    }

    /**
     * Uppmätta begagnatgolv för el-SUV:ar — kandidatlistan som SUV-sök får i FÖRSTA prompten.
     *
     * <p><b>Varför en egen tabell.</b> {@link #EV_PRICE_FLOOR_KR} blandar karosser: den bär
     * Zoe, Leaf, ID.3 och Model 3 för att den svarar på frågan "vad ryms i budgeten", inte
     * "vilken SUV ryms i budgeten". Skickas den listan till ett SUV-sök pekar den ut precis de
     * låga bilar spärren sedan fäller.
     *
     * <p><b>Mätt 2026-08-22</b> med appens egen {@code BlocketPriceService} (milgränstrappan,
     * {@code fuel=El}, billigaste annonsen per modell), antal annonser inom parentes:
     * MG ZS EV (30), e-2008 (68), Mokka-e (21), ID.4 (62), Model X (36), Mach-E (96),
     * Enyaq (92), Q4 e-tron (89), Ioniq 5 (41), EQA (94), XC40 Recharge (70), bZ4X (100),
     * C40 Recharge (99), Solterra (100), Ariya (99), EV6 (96), Model Y (98), EQB (99),
     * iX1 (91), Scenic (82), iX3 (99), EC40 (94), EX40 (80), Elroq (97), iX (91),
     * Atto 3 (79), Enyaq Coupe (99), EX30 (83), EV3 (92), Tavascan (85), EV5 (96),
     * EQE SUV (47), EV9 (59), Ioniq 9 (64), EX90 (98).
     *
     * <p><b>Att siffrorna åldras är ofarligt här, och det är avsiktligt.</b> Ett begagnatgolv
     * sjunker med tiden — BMW iX3 kostar 764 300 kr ny men går redan att köpa för 364 800 kr
     * begagnad. Tabellen fäller ingenting: den väljer bara vilka modeller som NAMNGES i
     * prompten. Det som faktiskt kastar en för dyr bil är {@code exceedsBudgetCeiling}, och den
     * mäter mot riktiga annonser vid varje sökning. En inaktuell rad här gör alltså listan
     * försiktig, aldrig fel — till skillnad från {@link #EV_PRICE_FLOOR_KR}, som fäller bilar
     * och därför måste stämma.
     */
    static final Map<String, Integer> SUV_EV_PRICE_FLOOR_KR = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("MG ZS EV",              139_500),
            Map.entry("Peugeot e-2008",        175_000),
            Map.entry("Opel Mokka-e",          179_900),
            Map.entry("Volkswagen ID.4",       229_900),
            Map.entry("BYD Atto 3",            239_000),
            Map.entry("Tesla Model X",         269_900),
            Map.entry("Ford Mustang Mach-E",   279_000),
            Map.entry("Skoda Enyaq",           279_000),
            Map.entry("Audi Q4 e-tron",        284_700),
            Map.entry("Hyundai Ioniq 5",       285_000),
            Map.entry("Mercedes EQA",          289_800),
            Map.entry("Volvo XC40 Recharge",   289_900),
            Map.entry("Toyota bZ4X",           294_900),
            Map.entry("Volvo C40 Recharge",    298_800),
            Map.entry("Subaru Solterra",       299_900),
            Map.entry("Nissan Ariya",          299_900),
            Map.entry("Skoda Enyaq Coupe",     304_900),
            Map.entry("Volvo EX30",            305_000),
            Map.entry("Kia EV6",               314_900),
            Map.entry("Tesla Model Y",         318_900),
            Map.entry("Mercedes EQB",          326_990),
            Map.entry("BMW iX1",               328_700),
            Map.entry("Renault Scenic",        349_900),
            Map.entry("Kia EV3",               359_000),
            Map.entry("BMW iX3",               364_800),
            Map.entry("Volvo EC40",            379_000),
            Map.entry("Volvo EX40",            389_500),
            Map.entry("Skoda Elroq",           389_500),
            Map.entry("Cupra Tavascan",        394_900),
            Map.entry("BMW iX",                409_900),
            Map.entry("Mercedes EQE SUV",      469_000),
            Map.entry("Kia EV5",               475_300),
            Map.entry("Kia EV9",               629_800),
            Map.entry("Hyundai Ioniq 9",       663_748),
            Map.entry("Volvo EX90",            719_000)));

    /**
     * Så många modeller namnges. Listan sorteras med den DYRASTE först, alltså den största
     * SUV budgeten når — det är hela poängen: felet var att svaret la sig långt under budgeten,
     * och en lista som börjar i den billiga änden hade upprepat just det.
     */
    static final int SUV_FORSLAG_MAX = 8;

    /**
     * Uppmätta begagnatgolv för bensin-/hybrid-SUV:ar.
     *
     * <p><b>Mätt 2026-08-22</b> med {@code BlocketPriceService} och filtret
     * {@code fuel=Bensin|Hybrid bensin, transmission=Automatisk} — alltså samma sorts annonser
     * ett SUV-sök på bensin faktiskt landar i. Antal annonser inom parentes: X1 (14),
     * Tiguan (29), Kamiq (69), Tucson (39), Karoq (79), RAV4 (65), Sportage (24), GLA (46),
     * Q3 (63), XC40 (48), XC60 (14), Kodiaq (42), Q5 (24), X3 (21), T-Roc (51), XC90 (17),
     * Q7 (16), GLC (21).
     *
     * <p><b>Golvet är alltid dyrare än det ofiltrerade.</b> Volvo XC60 ligger på 125 500 kr utan
     * filter och 249 900 kr som bensinautomat, och VW T-Roc saknar bensinautomater under
     * 10 000 mil helt — se {@code BlocketPriceService.AdFilter}. Ett SUV-sök på bensin ska
     * mötas av den dyrare siffran, för det är den bil användaren kan köpa.
     *
     * <p>Precis som {@link #SUV_EV_PRICE_FLOOR_KR} fäller den här tabellen ingenting: den
     * väljer bara vilka modeller som namnges. Diesel- och manuellsök får därför också den här
     * listan, trots att deras golv ligger lägre — ett för högt golv gör listan försiktig, och
     * {@code exceedsBudgetCeiling} mäter ändå mot annonser som matchar sökningen.
     */
    static final Map<String, Integer> SUV_ICE_PRICE_FLOOR_KR = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("BMW X1",              134_900),
            Map.entry("Volkswagen Tiguan",   148_800),
            Map.entry("Škoda Kamiq",         184_800),
            Map.entry("Hyundai Tucson",      184_900),
            Map.entry("Škoda Karoq",         189_900),
            Map.entry("Toyota RAV4",         208_900),
            Map.entry("Kia Sportage",        209_800),
            Map.entry("Mercedes GLA",        245_000),
            Map.entry("Audi Q3",             249_000),
            Map.entry("Volvo XC40",          249_900),
            Map.entry("Volvo XC60",          249_900),
            Map.entry("Škoda Kodiaq",        289_800),
            Map.entry("Audi Q5",             289_900),
            Map.entry("BMW X3",              319_900),
            Map.entry("Volkswagen T-Roc",    405_600),
            Map.entry("Volvo XC90",          419_900),
            Map.entry("Audi Q7",             539_000),
            Map.entry("Mercedes GLC",        929_000)));

    /**
     * SUV-kandidaterna i FÖRSTA prompten, inte som tillrättavisning efteråt.
     *
     * <p>Samma lärdom som {@link #affordableModelsLine}: {@code requireSuvShapedCars} säger vad
     * som är fel, den här raden säger vad som är rätt. Skarpt 2026-08-22 gav ett SUV-sök på
     * 400 000 kr bara TVÅ kort — spärren fällde det tredje och omförsöket kom tillbaka med
     * ännu en låg bil, för prompten hade sagt vilka bilar som var förbjudna men inte vilka som
     * fanns kvar i just den budgeten.
     */
    static String suvModelsLine(CarPreferences prefs) {
        if (!requiresSuvShapedCar(prefs)) return "";
        boolean el = fuelIntent(prefs.fuelType(), prefs.carCategory()).pureEv();
        Map<String, Integer> golv = el ? SUV_EV_PRICE_FLOOR_KR : SUV_ICE_PRICE_FLOOR_KR;
        String rubrik = el ? "EL-SUV" : "SUV";

        // Golven är begagnatpriser: i leasing- och nybilsläge är de fel prisvärld helt och hållet.
        if (!harGolvvakt(prefs)) return " " + rubrik + ":AR ATT UTGÅ FRÅN: " + String.join(", ",
                golv.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(12).map(Map.Entry::getKey).toList()) + ".";

        int tak = prefs.budget() + BUDGET_CEILING_MARGIN_KR;
        List<Map.Entry<String, Integer>> ryms = golv.entrySet().stream()
                .filter(e -> e.getValue() <= tak)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(SUV_FORSLAG_MAX)
                .toList();
        if (ryms.isEmpty()) {
            // Billigast räknas fram, inte läses ur ordningen: Map.ofEntries är oordnad, så
            // LinkedHashMap-omslaget bevarar INTE raderna som de står skrivna här i filen.
            Map.Entry<String, Integer> billigast = golv.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .orElseThrow();
            return " " + rubrik + " OCH BUDGET: ingen " + (el ? "el-SUV" : "SUV")
                    + " har ett uppmätt begagnatgolv under " + kr(tak)
                    + " kr. Billigast är " + billigast.getKey() + " från " + kr(billigast.getValue())
                    + " kr — säg det rakt ut i fitSummary i stället för att hitta på en billigare bil.";
        }
        String lista = ryms.stream()
                .map(e -> e.getKey() + " (fr. " + kr(e.getValue()) + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return " " + rubrik + ":AR SOM RYMS I BUDGETEN (uppmätta begagnatgolv, störst först): " + lista
                + ". Minst TVÅ av tre förslag ska väljas härifrån — det är de största SUV:ar"
                + " budgeten når, och en billigare bil är fel svar när dessa finns.";
    }

    /** Tusentalsavgränsare med mellanslag, Locale.ROOT — svensk locale ger hårt mellanslag. */
    private static String kr(int belopp) {
        return String.format(java.util.Locale.ROOT, "%,d", belopp).replace(',', ' ');
    }

    /**
     * Drivlinan sökningen låser till, avläst ur ANNONSFILTRET — {@code null} när utbudet får
     * visas fritt.
     *
     * <p>Läses ur {@code AdFilter} och inte ur {@code fuelType} av ett konkret skäl: en
     * laddhybridssökning gjord från formuläret bär {@code carCategory=laddhybrid} och
     * {@code fuelType="spelar ingen roll"}, så drivmedelssträngen ensam vet ingenting.
     * {@code adFilterFor} har redan vägt samman kategori och drivmedel åt oss.
     *
     * <p>Bara ENTYDIGA filter ger ett svar. Ett bensinsök släpper igenom både "Bensin" och
     * "Hybrid bensin" — där är utbudet blandat med flit, och att låsa listan till den ena
     * hade dolt att modellen finns som den andra.
     */
    static String drivlinaFor(BlocketPriceService.AdFilter filter) {
        if (filter == null || filter.fuels() == null || filter.fuels().isEmpty()) return null;
        if (filter.fuels().stream().allMatch(f -> f.startsWith("Plug-in"))) return "laddhybrid";
        if (filter.fuels().stream().allMatch(f -> f.startsWith("Hybrid"))) return "hybrid";
        return null;
    }

    /**
     * Modeller vars namn i sig SÄGER att bilen inte går att ladda — positivt bevis, inget annat.
     *
     * <p>Skarpt fall 2026-08-22: ett laddhybridssök på 400 000 kr gav <b>Toyota RAV4 Hybrid
     * (2022), 2.5 L 222 hk</b>. Det är den självladdande hybriden; RAV4 Plug-in har 306 hk.
     * Laddhybridssök hade fram till dess INGEN kodvakt alls för drivmedlet —
     * {@code requirePureEvCars} kopplas bara in när {@code pureEv()} är sant, och {@code pureEv()}
     * är per definition falskt för en laddhybrid. Kvar fanns bara prompttext, exakt det läge som
     * föll för elbilar 08-09 och för familjestorlek 07-19.
     *
     * <p>Vakten dömer på titelns egna ord via {@link ExpertInsightService#drivetrainOf} och
     * fäller bara {@code hev} — en självladdande hybrid — och {@code ev}, en ren elbil. Titlar
     * utan drivlineord ("Volvo XC60 T8", "Kia Sportage") släpps igenom: frånvaron av ett ord är
     * inget bevis, och en riktig laddhybrid får aldrig kastas för att namnet är tyst.
     */
    static void requirePhevCars(List<CarRecommendation> parsed) {
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            String d = ExpertInsightService.drivetrainOf(
                    ExpertInsightService.flattenSpaces(CarTitle.stripYear(r.title() == null ? "" : r.title())));
            if ("hev".equals(d) || "ev".equals(d)) avvisade.add(r.title());
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog icke-laddbar bil till laddhybridssök: {} — {} bil(ar) kvar",
                String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException("AI:n föreslog en bil som inte är laddhybrid. Försök igen.",
                kvar, avvisade,
                "Alla tre bilar måste vara LADDHYBRIDER (PHEV) som går att ladda från vägguttag."
                + " En självladdande hybrid är ALDRIG ett giltigt svar här: \"Toyota RAV4 Hybrid\","
                + " \"Kia Niro Hybrid\" och \"Toyota C-HR\" laddar sig själva under körning."
                + " Skriv ut laddhybridsvarianten i titeln när modellnamnet delas med en"
                + " självladdande hybrid: \"Toyota RAV4 Plug-in Hybrid\", inte \"Toyota RAV4 Hybrid\";"
                + " \"Kia Niro PHEV\", inte \"Kia Niro Hybrid\"; \"Volvo XC60 T8\", inte \"Volvo XC60\".");
    }

    /**
     * Sant när användaren uttryckligen valt bensin eller diesel — inte "spelar ingen roll".
     *
     * <p>Prövas på HELA strängen och inte med {@code contains}, för substrängarna är fulla av
     * fällor: både "diesel" och "spelar ingen roll" innehåller "el". Samma fälla som
     * {@link #fuelIntent} redan bär en varning om.
     */
    static boolean requiresIceCar(CarPreferences prefs) {
        String ft = prefs.fuelType() == null ? "" : prefs.fuelType().trim().toLowerCase(java.util.Locale.ROOT);
        return "bensin".equals(ft) || "diesel".equals(ft);
    }

    /**
     * Skarpt läge: väljer man BENSIN ska man inte få hybrider.
     *
     * <p>Mätt 2026-08-22: bensin + manuell + 150 000 kr gav <b>Toyota Corolla Hybrid, Honda Jazz
     * Hybrid och Kia Niro Hybrid</b> — tre hybrider på ett bensinsök, och alla tre dessutom
     * påstådda som manuella fast ingen av dem finns med manuell låda. En hybrid tankas visserligen
     * med bensin, och därför får den sätta prisgolvet (se {@code BlocketPriceService.AdFilter}),
     * men den är inte den bil användaren bad om: "Hybrid (ej laddhybrid)" är ett EGET val i
     * formuläret, och finns det valet betyder bensin bensin.
     *
     * <p>Fäller på positivt bevis ur titelns egna ord, precis som {@link #requirePureEvCars} och
     * {@link #requirePhevCars}. En titel utan drivlineord ("Škoda Fabia") släpps igenom.
     */
    static void requireIceCars(List<CarRecommendation> parsed) {
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            String d = ExpertInsightService.drivetrainOf(
                    ExpertInsightService.flattenSpaces(CarTitle.stripYear(r.title() == null ? "" : r.title())));
            if ("hev".equals(d) || "phev".equals(d) || "ev".equals(d)) avvisade.add(r.title());
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog hybrid/elbil till bensin- eller dieselsök: {} — {} bil(ar) kvar",
                String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException("AI:n föreslog en hybrid till ett bensin- eller dieselsök. Försök igen.",
                kvar, avvisade,
                "Alla tre bilar måste ha REN förbränningsmotor. En hybrid, laddhybrid eller elbil är"
                + " ALDRIG ett giltigt svar här — hybrid är ett eget val i formuläret, och användaren"
                + " valde bort det. Föreslå aldrig \"Toyota Corolla Hybrid\", \"Honda Jazz Hybrid\","
                + " \"Kia Niro Hybrid\" eller liknande; välj i stället rena bensin- eller"
                + " dieselmodeller som Škoda Fabia, Toyota Aygo, VW Polo, Hyundai i20 eller Kia Picanto.");
    }

    /** Kategorin är SUV — då ska bilarna vara höga. Speglar SUV-regeln i systemprompten. */
    static boolean requiresSuvShapedCar(CarPreferences prefs) {
        return prefs.carCategory() != null && prefs.carCategory().toLowerCase().contains("suv");
    }

    /**
     * Skarpt läge: SUV betyder HÖG bil. Regeln stod bara i prompten och höll inte — ett
     * SUV-sök på elbil med 400 000 kr gav 2026-08-22 Kia Niro EV, MG4 och Hyundai Kona
     * Electric, alltså tre låga bilar, varav den dyraste ligger långt under budgeten.
     *
     * <p>Fäller på positivt bevis ur {@link #NON_SUV_MARKERS} och triggar omförsöket i
     * parseWithRetry, precis som de andra regelvakterna.
     */
    static void requireSuvShapedCars(List<CarRecommendation> parsed) {
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            String t = r.title() == null ? "" : r.title().toLowerCase();
            if (NON_SUV_MARKERS.stream().anyMatch(t::contains)) avvisade.add(r.title());
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog icke-SUV till SUV-kategorin: {} — {} bil(ar) kvar",
                String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException("AI:n föreslog en bil som inte är en SUV. Försök igen.", kvar, avvisade,
                "Alla tre bilar måste vara riktiga SUV:ar: HÖG kaross, hög sittposition och stor"
                + " markfrigång — i storleksklass Volvo XC40/Škoda Kamiq eller större. En halvkombi,"
                + " sedan eller låg crossover är ALDRIG ett giltigt svar här: aldrig MG4, VW ID.3,"
                + " Tesla Model 3, Polestar 2, Kia Niro eller Hyundai Kona."
                + " Elbil: Volvo EX40/EC40, VW ID.4, Hyundai Ioniq 5, Kia EV6/EV9, Tesla Model Y,"
                + " Škoda Enyaq, Audi Q4 e-tron, Peugeot e-2008, BMW iX1/iX3, Mercedes EQB."
                + " Bensin/diesel/hybrid: Volvo XC40/XC60/XC90, Audi Q3/Q5/Q7, Škoda Kamiq/Karoq/Kodiaq,"
                + " VW T-Roc/Tiguan, Toyota RAV4, Kia Sportage, Hyundai Tucson, BMW X1/X3, Mercedes GLA/GLC.");
    }

    /**
     * Skarpt läge: ELBIL OBLIGATORISKT i kod. Regeln fanns bara som prompttext och höll inte —
     * live 2026-08-09 gav ett rent elbilssök (fuelType "el", kategori elbil) Toyota Prius,
     * Kia Niro Hybrid och Honda CR-V Hybrid på 150 000-budgeten, och Kia Niro Hybrid tillsammans
     * med två elbilar på 200 000. Familjekravet fick kodstöd 2026-07-19; drivmedelskravet stod
     * kvar oskyddat trots samma sorts brott.
     *
     * <p>Fäller bara på POSITIVT bevis för förbränning eller hybrid. Att sakna rad i ev_spec
     * duger inte som bevis: whitelisten är inte komplett, och en riktig elbil som saknas där
     * ska inte kastas ut. Ett regelbrott triggar bara omförsöket i parseWithRetry, precis som
     * de andra regelvakterna.
     */
    void requirePureEvCars(List<CarRecommendation> parsed) {
        List<CarRecommendation> elbilar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            if (isNonEv(r.title())) avvisade.add(r.title());
            else elbilar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog icke-elbil(ar) till rent elbilssök: {} — {} elbil(ar) kvar",
                String.join(", ", avvisade), elbilar.size());
        throw new RuleViolationException("AI:n föreslog en bil som inte är elbil. Försök igen.", elbilar, avvisade,
                "Alla tre bilar måste vara RENA batterielbilar (BEV). ALDRIG hybrid, laddhybrid,"
                + " bensin eller diesel — en bil med förbränningsmotor är aldrig ett giltigt svar här."
                + " Skriv ut elbilsvarianten i titeln när modellnamnet delas med en hybrid:"
                + " \"Kia Niro EV\", inte \"Kia Niro\"; \"Volvo XC40 Recharge\", inte \"Volvo XC40\".");
    }

    /**
     * Regelbrott som går att städa bort i efterhand: bär med sig de bilar som FAKTISKT klarade
     * regeln, så att den mjuka vägen i getRecommendation kan visa dem i stället för att svara
     * med ett felmeddelande. Fällningen triggar först omförsöket precis som förut — listan
     * används bara när även omförsöket bröt mot regeln.
     *
     * <p>Alla tre regelvakter kastar den här. Förut hade var och en sin egen hårda väg ut, och
     * ett enda regelbrott bland tre bilar kostade hela svaret: live 2026-08-09 gav elbil +
     * 225 000 kr + 5 passagerare HTTP 500 i tre försök av tre, dels på en overifierbar modell,
     * dels på avhuggen JSON. Två riktiga bilar är ett bättre svar än noll.
     */
    static class RuleViolationException extends RuntimeException {
        private final transient List<CarRecommendation> kvar;
        private final transient List<String> avvisade;
        private final transient String rattelse;

        RuleViolationException(String message, List<CarRecommendation> kvar,
                               List<String> avvisade, String rattelse) {
            super(message);
            this.kvar = List.copyOf(kvar);
            this.avvisade = List.copyOf(avvisade);
            this.rattelse = rattelse;
        }

        /** De godkända bilarna ur svaret — tom när ingen bil klarade regeln. */
        List<CarRecommendation> kvar() { return kvar; }

        /** Bilarna som fälldes, vid namn — de pekas ut för AI:n i rättelseförsöket. */
        List<String> avvisade() { return avvisade; }

        /** Regeln formulerad som en instruktion till AI:n, inte som ett felmeddelande. */
        String rattelse() { return rattelse; }
    }

    /**
     * AI:n svarade med giltig JSON men UTAN bilar — inte samma sak som att vakterna fällde allt.
     *
     * <p>Egen typ för att de två fallen ska kunna sluta olika. Fäller vakterna varenda bil är
     * tomt svar det ärliga beskedet: kraven gick inte ihop, och användaren får dem uppräknade
     * ({@code narrowCriteria}). Men ett tomt AI-svar säger ingenting om kraven — då blir samma
     * beteende ett påstående om användarens sökning som inte har täckning, och hen sitter och
     * lättar på krav som aldrig var problemet. Skarpt fall 2026-08-28: elbil 350 000 kr föll
     * på det, och exakt samma sökning gick igenom på tredje försöket.
     *
     * <p>Ärver kvar/avvisade/rättelse så att rättelseförsöket fungerar likadant — det är bara
     * SLUTET som skiljer, när även rättelsen kommit tillbaka tom.
     */
    static class TomtAiSvarException extends RuleViolationException {
        TomtAiSvarException(String message, String rattelse) {
            super(message, List.of(), List.of("(svaret innehöll inga bilar)"), rattelse);
        }
    }

    /**
     * Är titeln bevisligen något annat än en ren elbil? Titelns egna drivlineord först — de
     * flesta elbilstitlar saknar sådana helt ("EV6", "EX30" är ETT ord var), så ordlöst svar
     * betyder inget i sig. Då avgör databaserna: finns bilen i ev_spec är den en elbil, annars
     * fäller en träff i ice_consumption ("Toyota Prius 2.5 Hybrid", "Honda HR-V 1.5 e:HEV").
     * Fail open vid DB-fel — en trasig uppslagning får inte fälla korrekta bilar.
     */
    /**
     * Ska kortet bära {@code ev_spec} alls?
     *
     * <p>Skarpt sök 2026-08-14 (SUV/bensin/250 000 kr) gav korten "Kia Niro (2021)" och
     * "Hyundai Kona (2020)" en elbils {@code evSpec} — <i>ladda var 10:e dag</i> respektive
     * <i>var 6:e dag</i> — samtidigt som deras {@code fuelSpec} korrekt visade bensinmotorn
     * (6,2 och 6,8 l per mil). Samma symtom som C-HR-buggen dagen innan, men via en helt annan
     * väg: här är namnmatchningen inte fel. {@code Kia Niro} <b>finns</b> som elbil, och radens
     * ord är en äkta övermängd av titelns, så ingen strängregel i {@link EvSpecService} kan
     * skilja dem åt.
     *
     * <p><b>Företrädesregeln fanns redan men satt på fel ställe.</b> {@link #isNonEv} har sedan
     * 2026-08-09 regeln att {@code ice_consumption} prövas före {@code ev_spec} — men den
     * användes bara av drivmedelsvakten, alltså för att avgöra om ett ELBILSSÖK fått riktiga
     * elbilar. Kortbygget hämtade {@code evSpec} för varje titel oavsett vad användaren sökt på,
     * med en årsmodellkoll som enda filter. Samma princip gäller nu båda.
     *
     * <p>Tre saker behåller sin {@code ev_spec}, för de har faktiskt en sladd:
     * <ul>
     *   <li>titeln säger själv {@code ev} eller {@code phev}</li>
     *   <li>förbränningsträffen är en <b>laddhybrid</b> — den har batteri och elräckvidd, och
     *       {@code Volvo XC60 T8} ska visa dem</li>
     *   <li>titeln bär ett märkesnamn för den laddbara varianten som inte är ett generellt
     *       drivlineord: Volvos {@code Recharge}, DS {@code E-Tense}, Jeeps {@code 4xe},
     *       Renaults {@code E-Tech}. Utan dem hade ett äkta elbils-XC40 tappat sina chips,
     *       eftersom XC40 också finns som bensinbil i {@code ice_consumption}.</li>
     * </ul>
     * Listan får bara växa åt det hållet — en glömd post kostar ett tomt fält på ett elbilskort,
     * medan motsatsen ger laddråd på en bensinbil. Fail open vid DB-fel, av samma skäl.
     */
    /**
     * Växellådefältet med motorbeteckningar bortstädade.
     *
     * <p>Skarpt sök 2026-08-14 gav kortet "Volvo XC40 (2022)" växellådan
     * <i>"Automat 8-växlad (TSI turbo)"</i>. <b>TSI är VW-koncernens motorbeteckning</b> och
     * hör inte hemma på en Volvo, som använder T- och B-beteckningar — bilen var dessutom en
     * B4. Samma bil hade i en tidigare körning korrekt "Automat Geartronic 8-växlad", så
     * strängen varierade mellan körningar: den kom från AI:n, inte från databasen.
     *
     * <p><b>Roten satt i promptens egna exempel:</b> {@code "gearbox":"Automat DSG 7-växlad
     * (TSI turbo)"} plus instruktionen "ange turbo/ej turbo". Modellen fyllde i den
     * parentes prompten bad om och tog motorfamiljen ur exemplet. Det är samma mönster som
     * MG5-fallet: <b>modellen agerar på namngivna exempel</b>, så ett exempel med ett
     * märkesspecifikt ord blir en mall som följer med till fel märke.
     *
     * <p>Exemplet är utbytt och beteckningarna uttryckligen förbjudna — men regeln står på
     * TVÅ ställen av samma skäl som utmärkelseskärpningen: en prompt kan ignoreras. Till
     * skillnad från förbrukning, hästkrafter och motoralternativ går växellådan inte att
     * verifiera mot {@code ice_consumption}, som inte lagrar den. Därför städning i stället
     * för ersättning.
     *
     * <p>En parentes behålls bara om den innehåller ett känt VÄXELLÅDEORD — "Automat (CVT)"
     * är vettigt, "(TSI turbo)" är det inte. Okänt innehåll faller bort, och det är rätt håll
     * att fela åt: kvar står alltid själva växellådan, och motorn visas ändå verifierad i
     * {@code engineOptions} och {@code horsepower}.
     */
    static String rensaVaxellada(String gearbox) {
        if (gearbox == null) return null;
        String rensad = PARENTES.matcher(gearbox)
                .replaceAll(m -> VAXELLADEORD.matcher(m.group(1)).find()
                        ? java.util.regex.Matcher.quoteReplacement(m.group(0)) : "");
        rensad = rensad.replaceAll("\\s{2,}", " ").trim();
        return rensad.isEmpty() ? null : rensad;
    }

    private static final java.util.regex.Pattern PARENTES =
            java.util.regex.Pattern.compile("\\s*\\(([^)]*)\\)");
    private static final java.util.regex.Pattern VAXELLADEORD = java.util.regex.Pattern.compile(
            "(?i)\\b(cvt|e-cvt|dsg|dct|edc|imt|amt|automat\\w*|manuell\\w*|geartronic|powershift|"
            + "steptronic|tiptronic|multitronic|xtronic|s.tronic|pdk|zf|varierbar)\\b");

    boolean evSpecHorInteHit(String title) {   // paketprivat: testet läser den direkt
        String rent = CarTitle.stripYear(title == null ? "" : title);
        String drivetrain = ExpertInsightService.drivetrainOf(rent);
        if ("ev".equals(drivetrain) || "phev".equals(drivetrain)) return false;
        if (LADDBAR_VARIANT.matcher(rent.toLowerCase()).find()) return false;
        if ("hev".equals(drivetrain)) return true;   // självladdande hybrid — ingen sladd
        try {
            IceConsumptionService.Variant v = iceConsumptionService.consumptionForTitle(title, null, null);
            if (v == null) return false;             // ingen förbränningsträff — inget bevis, behåll
            return !"laddhybrid".equalsIgnoreCase(v.fuel());
        } catch (Exception e) {
            return false;
        }
    }

    /** Märkesnamn för den laddbara varianten. Se {@link #evSpecHorInteHit}. */
    private static final java.util.regex.Pattern LADDBAR_VARIANT =
            java.util.regex.Pattern.compile("\\b(recharge|e-tense|4xe|e-tech)\\b");

    private boolean isNonEv(String title) {
        String drivetrain = ExpertInsightService.drivetrainOf(CarTitle.stripYear(title == null ? "" : title));
        if ("hev".equals(drivetrain) || "phev".equals(drivetrain) || "ice".equals(drivetrain)) return true;
        if (drivetrain != null) return false; // "ev" — titeln säger det själv
        try {
            // ice_consumption prövas FÖRE ev_spec. Omvänd ordning släppte igenom tvetydiga
            // namn: live 2026-08-09 gav ett elbilssök "Kia Niro (2021)", som finns som HEV,
            // PHEV och elbil. ev_spec:s fuzzy-matchning slog mot "Kia Niro EV" och godkände
            // raden innan hybridträffen ens provades.
            //
            // Ett namn som finns som förbränning eller hybrid fälls därför även om det OCKSÅ
            // finns som elbil. En titel utan drivlineord pekar inte ut någon av varianterna,
            // och tvetydigheten är skadlig i sig: Blocket-uppslaget matchar då hybridannonser
            // och kortet får fel prisbild. Rätt svar är "Kia Niro EV", inte "Kia Niro".
            if (iceConsumptionService.consumptionForTitle(title, null, null) != null) return true;
            return false;   // varken drivlineord eller förbränningsträff — inget bevis, släpp igenom
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ett sista försök där de fällda bilarna pekas ut vid namn och regeln upprepas som en
     * instruktion. Exakt samma grepp som budgetomförsöket (`retryWithinBudget`), och skälet är
     * detsamma: omförsöket i {@code parseWithRetry} skickar bara om SAMMA prompt till
     * reservmodellen utan att säga vad som var fel, och får därför ofta samma fel igen.
     *
     * <p>Görs bara när alternativet är ett felmeddelande — alltså när ingen bil alls klarade
     * regeln i vare sig första svaret eller omförsöket. Skarpt fall 2026-08-09: elbil för
     * 175 000 kr gav enbart hybrider i båda omgångarna, och användaren fick HTTP 500 fast det
     * finns gott om elbilar i det prisläget.
     *
     * <p>Fail open: misslyckas även det här returneras tom lista och felet går ut som förut.
     */
    private List<CarRecommendation> retryAfterRuleViolation(
            String systemPrompt, String prompt, RuleViolationException violation,
            java.util.function.Consumer<List<CarRecommendation>> validator) {
        if (violation.avvisade().isEmpty() || violation.rattelse() == null) return List.of();
        log.warn("Regelbrott utan räddningsbara bilar ({}) — rättelseförsök", violation.getMessage());
        String skarptPrompt = prompt + String.format("""

                VIKTIGT — FÖRRA FÖRSÖKET BRÖT MOT KRAVEN: %s. %s
                Föreslå tre ANDRA bilar som uppfyller kravet.
                """, String.join(", ", violation.avvisade()), violation.rattelse());
        try {
            Map<String, Object> body = jsonCallBody(model, 0.3, systemPrompt, skarptPrompt);
            Map<String, Object> fallback = jsonCallBody(chatModel, 0.3, systemPrompt, skarptPrompt);
            Map<String, Object> reserve = jsonCallBody(reserveModel, 0.3, systemPrompt, skarptPrompt,
                    RETRY_MAX_TOKENS);
            HttpResponse<String> response = callGroqWithFallback(body, fallback, reserve);
            if (response.statusCode() != 200) return List.of();
            List<CarRecommendation> parsed = extractAndParse(response, "getRecommendation (rättelse)");
            try {
                validator.accept(parsed);
                return parsed;
            } catch (RuleViolationException e) {
                return keepPassingCars(e, validator);
            }
        } catch (Exception e) {
            log.warn("Rättelseförsöket misslyckades: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Samma fel, men med vad användaren faktiskt kan ändra. "Försök igen" är ett dåligt råd när
     * samma sökning misslyckas varje gång: elbil + 225 000 kr + 5 passagerare gav HTTP 500 i tre
     * försök av tre 2026-08-09, medan fyra passagerare gick igenom direkt. Orsaken skrivs inte
     * om — den varierar mellan avhuggen JSON och overifierbar modell, och gissas inte här.
     */
    static RuntimeException medRadOmKriterier(RuntimeException e) {
        // Rate limit får ALDRIG rådet om kriterierna. Det är inte bara onödigt utan aktivt
        // vilseledande: sökningen var korrekt, taket släpper av sig självt, och att sänka
        // kraven hjälper inte. Skarpt fall 2026-08-10 — användaren fick "AI-svaret blev
        // ofullständigt ... prova högre budget" och samma sökning gick igenom direkt efteråt.
        if (e instanceof RateLimitedException) return e;
        return new RuntimeException(e.getMessage()
                + " Kriterierna kan vara för snäva — prova högre budget, färre passagerare"
                + " eller ett annat drivmedel.");
    }

    /**
     * Groq svarade 429 — appen har slagit i minutkvoten, inte i något användaren gjort.
     *
     * <p>Egen typ och inte bara en text, eftersom {@link #medRadOmKriterier} måste kunna skilja
     * fallet från de andra: rådet "prova högre budget, färre passagerare" är fel svar på ett
     * tak som släpper av sig självt om en minut.
     */
    public static class RateLimitedException extends RuntimeException {
        /**
         * Sekunder tills taket släpper, ur Groqs eget svar. 0 = okänt.
         *
         * <p>Behövs för att gränssnittet ska kunna räkna NER i stället för att bara säga "vänta
         * en stund": knappen låses lika länge som taket faktiskt varar, och då kan det andra
         * klicket inte bli ett fel. Ett tal och inte en text, eftersom mottagaren räknar med det.
         */
        private final int retryAfterSeconds;

        RateLimitedException(String message) {
            this(message, 0);
        }

        RateLimitedException(String message, int retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() { return retryAfterSeconds; }
    }

    /**
     * De bilar som klarar ALLA regler, ur ett svar som brutit mot minst en.
     *
     * <p>Vakterna körs i kedja och den första som fäller avbryter resten, så listan i undantaget
     * är bara rensad från det regelbrottet. En overifierbar modell kan alltså plockas bort medan
     * en hybrid står kvar i ett elbilssök. Därför körs kedjan om på det som blev kvar tills den
     * går ren — varje varv tar bort minst en bil, så loopen avslutas alltid.
     */
    private static List<CarRecommendation> keepPassingCars(
            RuleViolationException violation, java.util.function.Consumer<List<CarRecommendation>> validator) {
        List<CarRecommendation> kvar = violation.kvar();
        while (!kvar.isEmpty()) {
            try {
                validator.accept(kvar);
                return kvar;
            } catch (RuleViolationException e) {
                kvar = e.kvar();
            }
        }
        return List.of();
    }

    /**
     * Regelvakterna för ett rekommendationssvar, byggda på ETT ställe. Budgetomförsöket var
     * annars en oskyddad andra dörr: dess svar går rakt in i det sammanslagna resultatet utan
     * att ha mött någon vakt, och det är just vid låg budget — där omförsöket utlöses — som
     * drivmedelsbrotten dök upp.
     */
    private java.util.function.Consumer<List<CarRecommendation>> validatorFor(CarPreferences prefs) {
        java.util.function.Consumer<List<CarRecommendation>> validator = this::requireKnownModels;
        validator = validator.andThen(GroqService::requireRealisticModelYears);
        if (requiresFamilySizedCar(prefs)) validator = validator.andThen(GroqService::requireFamilySizedCars);
        if (requiresSuvShapedCar(prefs)) validator = validator.andThen(GroqService::requireSuvShapedCars);
        if (fuelIntent(prefs.fuelType(), prefs.carCategory()).pureEv())
            validator = validator.andThen(this::requirePureEvCars);
        if (fuelIntent(prefs.fuelType(), prefs.carCategory()).phev())
            validator = validator.andThen(GroqService::requirePhevCars);
        if (requiresIceCar(prefs)) validator = validator.andThen(GroqService::requireIceCars);
        if (prefs.minCargoLiters() != null && prefs.minCargoLiters() > 0) {
            int krav = prefs.minCargoLiters();
            validator = validator.andThen(p -> requireCargoCapacity(p, krav));
        }
        if (harGolvvakt(prefs)) {
            int budget = prefs.budget();
            validator = validator.andThen(p -> requireAffordableModels(p, budget));
        }
        return validator;
    }

    /**
     * Skarpt läge: fäller bilar vars UPPMÄTTA begagnatgolv ligger över budgettaket.
     *
     * <p>Byggd efter två live-sökningar 2026-08-10 där prompttexten inte räckte: elbil +
     * 200 000 kr gav Kia EV6 (golv 317 000) båda gångerna, tillsammans med Ioniq 5 och Enyaq —
     * alla långt över taket, och alla med sitt golv utskrivet i samma prompt. Vakten är alltså
     * inte en dubblett av {@code exceedsBudgetCeiling}: den kör FÖRE Blocket-uppslaget, på ren
     * kunskap om modellen, och hinner därför utlösa ett omförsök som pekar ut både de fällda
     * bilarna och vilka modeller som faktiskt ryms.
     *
     * <p>Gäller bara begagnatsök: golven är begagnatpriser, så ett nybilssök eller en
     * leasingförfrågan mäts mot fel tal och lämnas åt {@code exceedsBudgetCeiling}. Okänd modell
     * släpps igenom — tabellen är en handfull mätta modeller, inte en marknadsöversikt.
     */
    static void requireAffordableModels(List<CarRecommendation> parsed, int budgetKr) {
        int tak = budgetKr + BUDGET_CEILING_MARGIN_KR;
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            Integer golv = floorForTitle(r.title());
            if (golv != null && golv > tak) avvisade.add(r.title() + " (golv " + golv + " kr)");
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;

        String raryms = EV_PRICE_FLOOR_KR.entrySet().stream()
                .filter(e -> e.getValue() <= tak)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("AI föreslog bil(ar) vars begagnatgolv överstiger {} kr: {} — {} bil(ar) kvar",
                tak, String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException(
                "AI:n föreslog bilar som inte går att köpa för budgeten. Försök igen.", kvar, avvisade,
                "Modellens billigaste exemplar måste rymmas under " + tak + " kr."
                + (raryms.isBlank() ? "" : " Modeller som gör det: " + raryms + "."));
    }

    /**
     * Kraven som faktiskt begränsade sökningen, i klartext — underlag för banderollen när
     * färre än tre kort blev kvar.
     *
     * <p>Prompten kräver exakt tre bilar, men regelvakterna får fälla: live 2026-08-10 gav
     * familjeelbil + 400 l + 200 000 kr ett enda kort (MG5), vilket var helt korrekt — MG4
     * (363 l) och Niro EV (349 l) klarade inte bagagekravet. Utan förklaring läser ett ensamt
     * kort som att appen krånglar i stället för som ett svar på en hård fråga. Samma resonemang
     * som budgetbanderollen bygger på, men den täcker bara prisfallet.
     *
     * <p>Listan byggs ur {@link CarPreferences} och inte ur vakternas utfall: vilka vakter som
     * fällde vad är borta när svaret sätts ihop, medan kraven användaren ställde alltid finns
     * kvar — och det är dem hen kan lätta på.
     */
    public static List<String> activeConstraints(CarPreferences prefs) {
        List<String> krav = new ArrayList<>();
        if (fuelIntent(prefs.fuelType(), prefs.carCategory()).pureEv()) krav.add("ren elbil");
        if (prefs.minCargoLiters() != null && prefs.minCargoLiters() > 0)
            krav.add("minst " + prefs.minCargoLiters() + " liter bagage");
        if (requiresFamilySizedCar(prefs)) krav.add("familjestor bil");
        // Utan raden läser ett SUV-sök som gav två kort som att appen krånglar: banderollen
        // räknade upp "ren elbil, automat, högst 430 000 kr" medan det var SUV-spärren som
        // fällde det tredje kortet. Mätt live 2026-08-22, direkt efter att spärren deployats.
        if (requiresSuvShapedCar(prefs)) krav.add("SUV (hög bil)");
        if (!prefs.newCar() && prefs.maxAgeYears() != null)
            krav.add("högst " + prefs.maxAgeYears() + " år gammal");
        if (prefs.transmission() != null && !prefs.transmission().isBlank()
                && !"spelar ingen roll".equals(prefs.transmission()))
            krav.add(prefs.transmission());
        // Tusentalsavgränsare som överallt annars i appen — "230000 kr" läser som ett fel.
        // Locale.ROOT av samma skäl som i golvlistan: svensk locale ger hårt mellanslag.
        krav.add("högst " + String.format(java.util.Locale.ROOT, "%,d",
                prefs.budget() + BUDGET_CEILING_MARGIN_KR).replace(',', ' ') + " kr");
        return krav;
    }

    /**
     * Kandidatlistan för en låg budget — de mätta elbilsmodeller som faktiskt ryms.
     *
     * <p>Byggd efter att golvvakten börjat bita men gjort svaret tunnare i stället för bättre:
     * live 2026-08-10 föll EV6, Ioniq 5 och Enyaq korrekt, men omförsöket fyllde inte på till
     * tre och båda sökningarna gav **ett** kort. Vakten säger vad som är fel; den här raden
     * säger vad som är rätt, och den säger det i FÖRSTA prompten i stället för som
     * tillrättavisning efteråt. Samma data ur {@link #EV_PRICE_FLOOR_KR}, andra tidpunkt.
     *
     * <p>Tom sträng när listan inte tillför något: när användaren inte söker elbil (golven är
     * elbilsgolv), i nybils- och leasingläge (fel prisunderlag) och när budgeten rymmer hela
     * tabellen ändå — då är listan bara brus som kostar tokens.
     */
    static String affordableModelsLine(CarPreferences prefs) {
        if (!harGolvvakt(prefs)) return "";
        if (!fuelIntent(prefs.fuelType(), prefs.carCategory()).pureEv()) return "";
        int tak = prefs.budget() + BUDGET_CEILING_MARGIN_KR;

        List<String> ryms = EV_PRICE_FLOOR_KR.entrySet().stream()
                .filter(e -> e.getValue() <= tak)
                .map(e -> e.getKey() + " (fr. " + String.format(java.util.Locale.ROOT, "%,d", e.getValue())
                        .replace(',', ' ') + ")")
                .toList();
        List<String> over = EV_PRICE_FLOOR_KR.entrySet().stream()
                .filter(e -> e.getValue() > tak)
                .map(Map.Entry::getKey)
                .toList();
        if (ryms.isEmpty()) return "";

        // RYMS HELA TABELLEN fick raden falla bort ända till 2026-08-28, med motiveringen att
        // den då bara var brus som kostade tokens. Skarpt fall samma dag: budget 350 000 kr,
        // elbil — taket blev 380 000 och tabellens dyraste golv är Kia EV6 på 317 000, alltså
        // var `over` tom och HELA styrningen avstängd. Svaret blev Nissan Leaf (golv 70 000)
        // och Hyundai Kona Electric. Styrningen försvann alltså precis vid de budgetar där
        // frågan inte längre är "vad har jag råd med" utan "vad ska jag välja".
        //
        // Vid hög budget skickas därför en KORT rad om budgetens övre del i stället för hela
        // tabellen — brusinvändningen var riktig, det var slutsatsen som var fel.
        String ovreDel = ovreDelenAvBudgeten(prefs.budget());
        if (over.isEmpty()) return ovreDel;

        return " MODELLER SOM RYMS I BUDGETEN (uppmätta begagnatgolv, billigaste exemplar): "
                + String.join(", ", ryms) + ". Utgå från dessa. Följande ligger ÖVER taket "
                + tak + " kr och kastas av kontrollen även om de passar profilen i övrigt: "
                + String.join(", ", over) + "." + ovreDel;
    }

    /** Golvet räknat som andel av budgeten: modeller häröver hör hemma i budgetens övre del. */
    static final double BUDGET_OVRE_DEL_ANDEL = 0.60;
    /** Under den här andelen av budgeten är modellen ett billigare alternativ, inte ett svar. */
    static final double BUDGET_LANGT_UNDER_ANDEL = 0.35;

    /**
     * Vilka modeller som hör hemma i budgetens övre del, och vilka som är för billiga för att
     * vara mer än ett prisvärt alternativ.
     *
     * <p>Golven är BILLIGASTE ANNONS, inte vad budgeten köper: en Enyaq med golv 279 000 kr är
     * rätt bil för 350 000 — man får bara en nyare och bättre utrustad. Därför säger raden
     * inget om exakta priser, bara vilken ände av tabellen frågan gäller.
     *
     * <p>Tom sträng när uppdelningen inte säger något: utan billiga modeller finns inget att
     * varna för, och utan dyra finns inget att peka på.
     */
    static String ovreDelenAvBudgeten(int budgetKr) {
        int ovreGrans = (int) Math.round(budgetKr * BUDGET_OVRE_DEL_ANDEL);
        int langtUnder = (int) Math.round(budgetKr * BUDGET_LANGT_UNDER_ANDEL);
        List<String> ovre = EV_PRICE_FLOOR_KR.entrySet().stream()
                .filter(e -> e.getValue() >= ovreGrans && e.getValue() <= budgetKr + BUDGET_CEILING_MARGIN_KR)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        List<String> billiga = EV_PRICE_FLOOR_KR.entrySet().stream()
                .filter(e -> e.getValue() < langtUnder)
                .map(Map.Entry::getKey)
                .toList();
        if (ovre.isEmpty() || billiga.isEmpty()) return "";
        return " UTNYTTJA BUDGETEN: dessa ligger i budgetens övre del och är förstahandsval — "
                + String.join(", ", ovre) + ". Följande är byggda för en betydligt lägre budget"
                + " och får vara HÖGST ETT av tre förslag, aldrig hela svaret: "
                + String.join(", ", billiga) + ".";
    }

    /**
     * Golvvakten gäller bara begagnatsök — golven är begagnatpriser, så ett nybilssök eller en
     * leasingförfrågan mäts mot fel tal och lämnas åt {@code exceedsBudgetCeiling}, som redan
     * hanterar båda lägena med egna referenser (nypris respektive kr/mån).
     */
    static boolean harGolvvakt(CarPreferences prefs) {
        return !prefs.newCar() && !"leasing".equals(prefs.budgetType());
    }

    /** Golvet för titelns modell, eller null när modellen inte är mätt. Mest specifika namnet vinner. */
    static Integer floorForTitle(String title) {
        if (title == null) return null;
        Set<String> ord = modelTokens(CarTitle.stripYear(title));
        return EV_PRICE_FLOOR_KR.entrySet().stream()
                .filter(e -> ord.containsAll(modelTokens(e.getKey())))
                .max(java.util.Comparator.comparingInt(e -> modelTokens(e.getKey()).size()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Skarpt läge: bagagekravet från formuläret, kontrollerat mot {@code cargo_spec}.
     *
     * <p>Faller bara på POSITIVT bevis — en uppmätt normalvolym under kravet. En bil vi inte
     * mätt släpps igenom, av samma skäl som drivmedelsvakten gör det: tabellen hade 243 rader
     * den 2026-08-10 mot modell-whitelistens ~700, så att kasta det omätta hade tagit fler bra
     * bilar än dåliga och gett två kort i stället för tre. Kortets bagagechip visar "–" för de omätta, så det
     * syns vilka som är overifierade.
     *
     * <p>Mätt på NORMALvolymen (baksätet uppfällt). Maxvolymen är nästan tre gånger så stor —
     * MG5 lastar 578 l normalt och 1 456 l med nedfällt säte — så fel kolumn hade gjort kravet
     * verkningslöst.
     */
    void requireCargoCapacity(List<CarRecommendation> parsed, int minLiters) {
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            Integer liter = null;
            try {
                CargoSpecDto cs = cargoSpecService.formatForTitle(r.title());
                if (cs != null && cs.cargoLiters() > 0) liter = cs.cargoLiters();
            } catch (Exception ignored) {}   // specuppslaget får aldrig fälla hela svaret
            if (liter != null && liter < minLiters) avvisade.add(r.title() + " (" + liter + " l)");
            else kvar.add(r);
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog bil(ar) under bagagekravet {} l: {} — {} bil(ar) kvar",
                minLiters, String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException(
                "AI:n föreslog en bil med för litet bagageutrymme. Försök igen.", kvar, avvisade,
                "Alla bilar måste ha minst " + minLiters + " liter bagageutrymme med baksätet"
                + " uppfällt. Välj en rymligare kaross — kombi eller SUV i stället för halvkombi.");
    }

    /**
     * Skarpt läge: kräver att varje rekommenderad titel matchar minst en känd modell ur
     * cargo_spec/ev_spec/ice_consumption (~700+ modeller, byggda av buildKnownModelTokenSets).
     * Matchning är ordmängd-delmängd i endera riktningen — samma mönster som
     * WebInsightScraperService.sameCar — så trimvarianter som "Toyota Corolla Touring Sports"
     * mot databasens "Toyota Corolla" godkänns, men rena påhitt som "Volvo C70" eller
     * "Fiat Multiplina" fångas. Whitelisten är inte uttömmande (mycket ovanliga varianter kan
     * saknas) så ett regelbrott triggar bara omförsöket i parseWithRetry, precis som de andra
     * regelvakterna ovan — inte ett permanent avslag.
     */
    private void requireKnownModels(List<CarRecommendation> parsed) {
        List<Set<String>> known = knownModelTokenSets;
        if (known.isEmpty()) return; // whitelisten inte laddad än — släpp igenom hellre än att fälla korrekt
        List<CarRecommendation> kvar = new ArrayList<>();
        List<String> avvisade = new ArrayList<>();
        for (CarRecommendation r : parsed) {
            String name = r.title() == null ? "" : CarTitle.stripYear(r.title());
            Set<String> titleTokens = modelTokens(name);
            boolean matched = titleTokens.size() < 2
                    || known.stream().anyMatch(k -> titleTokens.containsAll(k) || k.containsAll(titleTokens));
            if (matched) kvar.add(r);
            else avvisade.add(r.title());
        }
        if (avvisade.isEmpty()) return;
        log.warn("AI föreslog modell(er) som inte kunde verifieras mot databasen: {} — {} bil(ar) kvar",
                String.join(", ", avvisade), kvar.size());
        throw new RuleViolationException("AI:n föreslog en bilmodell som inte kunde verifieras. Försök igen.",
                kvar, avvisade,
                "Föreslå bara modeller som verkligen finns på den svenska marknaden."
                + " Hitta ALDRIG på modellnamn eller versioner.");
    }

    private List<CarRecommendation> convertRecommendations(JsonNode node) {
        try {
            List<CarRecommendation> raw = mapper.convertValue(
                    node, mapper.getTypeFactory().constructCollectionType(List.class, CarRecommendation.class));
            return raw == null ? null : raw.stream().map(GroqService::withNormalizedTitle).toList();
        } catch (IllegalArgumentException e) {
            log.warn("AI recommendations did not match expected schema: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Titeln städad till "Märke Modell (år)" innan något annat rör den. Enda stället — allt
     * nedströms (Blockets årsfilter, dedupen, nyprisuppslaget, leasingmatchningen och
     * årsmodellvakten) läser årtalet ur titeln och tappade det tyst när AI:n skrev det i mitten.
     */
    private static CarRecommendation withNormalizedTitle(CarRecommendation r) {
        String stadad = CarTitle.normalize(r.title());
        if (stadad == null || stadad.equals(r.title())) return r;
        log.info("Titeln städad: \"{}\" → \"{}\"", r.title(), stadad);
        return new CarRecommendation(stadad, r.price(), r.whyRecommended(), r.pros(), r.con(),
                r.fitSummary(), r.expertOpinion(), r.safetyRating(), r.evSpec(), r.cargoSpec(),
                r.fuelSpec(), r.blocketPrice(), r.horsepower(), r.engineOptions());
    }
//Ettikettskaparen pipe sammanslagen sträng
    String buildCacheKey(CarPreferences prefs) {
        return prefs.budget() + "|" + prefs.carCategory() + "|" + prefs.hasCharger() + "|" +
               prefs.kmPerYear() + "|" + prefs.usage() + "|" + prefs.passengers() + "|" + prefs.newCar() + "|" +
               (prefs.fuelType() != null ? prefs.fuelType() : "") + "|" +
               (prefs.transmission() != null ? prefs.transmission() : "") + "|" +
               (prefs.budgetType() != null ? prefs.budgetType() : "köp") + "|" +
               (prefs.maxAgeYears() != null ? prefs.maxAgeYears() : "") + "|" +
               // Utan bagagekravet i nyckeln svarar cachen med den förra sökningens bilar när
               // bara kravet ändrats — och det är just den ändringen användaren vill se effekten av
               (prefs.minCargoLiters() != null ? prefs.minCargoLiters() : "");
    }

    /**
     * Väntetiden i SEKUNDER ur Groqs 429-svar ("try again in 24.51s", "try again in 2m59.56s").
     * 0 när svaret inte säger något.
     *
     * <p>Egen metod bredvid {@link #parseRetryTime}, som avrundar UPPÅT till hela minuter:
     * "24,5 sekunder" blev där "1 minut". Det duger som text i ett dagsgränsmeddelande men
     * inte som väntetid — varken serverns egen paus eller knappens nedräkning ska ljuga på
     * halvminuten, och det är just den halvminuten användaren klickar i.
     */
    static int parseRetrySeconds(String body) {
        try {
            Matcher m = Pattern.compile("try again in (?:(\\d+)m)?([\\d.]+)s").matcher(body);
            if (!m.find()) return 0;
            int minutes = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
            double seconds = Double.parseDouble(m.group(2));
            return (int) Math.ceil(minutes * 60 + seconds);
        } catch (Exception e) {
            return 0;
        }
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
        // Minuttaket släpper nästan alltid inom sekunder. "Vänta 1 minut" (parseRetryTime
        // avrundar uppåt) fick användaren att tro att appen var trasig i en halvminut som
        // egentligen var 25 sekunder — och att klicka igen i den halvminuten är precis vad
        // man gör. Säg sekunderna när de finns.
        int sek = parseRetrySeconds(body);
        if (sek > 0 && sek < 60)
            return "AI-tjänsten är tillfälligt överbelastad. Vänta " + sek + " sekunder och försök igen.";
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
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages), carContext); } catch (Exception ignored) {}
        // Användarens egen text följer med: bär frågan ett bagagekrav grundas svaret i cargo_spec.
        String systemPrompt = buildChatSystemPrompt(carContext, expertContext,
                String.join(" ", extractUserTexts(messages)));

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
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages), carContext); } catch (Exception ignored) {}
        // Användarens egen text följer med: bär frågan ett bagagekrav grundas svaret i cargo_spec.
        String systemPrompt = buildChatSystemPrompt(carContext, expertContext,
                String.join(" ", extractUserTexts(messages)));

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

    String buildChatSystemPrompt(String carContext, String expertContext) {
        return buildChatSystemPrompt(carContext, expertContext, null);
    }

    String buildChatSystemPrompt(String carContext, String expertContext, String userText) {
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
                // Samma kategori- och prisregler som korten. Chatten vet inte vilken kategori
                // frågan gäller, så hela uppsättningen följer med — se ALLA_KATEGORIREGLER.
                + REGELRUBRIK_CHATT + ALLA_KATEGORIREGLER + PRISREGLER_CHATT
                + (icePrices.isBlank() ? "" : icePrices + "\n")
                + (evPrices.isBlank() ? "" : evPrices + "\n");
        if (carContext != null && !carContext.isBlank()) {
            base += "\n\nAktuella bilrekommendationer:\n" + carContext;
            String specFacts = buildChatSpecFacts(carContext);
            if (!specFacts.isBlank())
                base += "\n\nVerifierade fakta om rekommenderade bilar:\n" + specFacts;
        }
        // Bagagefrågor grundas i cargo_spec i stället för i modellens minne — se bagagekontext.
        Integer troskel = bagagetroskel(userText);
        if (troskel != null) {
            String bagage = bagagekontext(troskel);
            if (!bagage.isBlank()) base += "\n\n" + bagage;
        }
        if (expertContext != null && !expertContext.isBlank())
            base += "\n\n" + expertContext;
        // Värdetappslistan från systerprojektet Elbilsladdning — samma tal som dess fyndtabell
        // visar, så en fråga om värdeminskning på el får ETT svar oavsett var den ställs.
        // Fail-open var för sig: ligger systertjänsten nere (den kör gratisnivå och spinner ner)
        // får chatten helt enkelt inget värdetappsavsnitt och svarar som förut.
        try {
            String varde = valueRetentionClient.chatKontext();
            if (!varde.isBlank()) base += "\n\n" + varde;
        } catch (Exception ignored) {}
        return withEnergyPrices(base);
    }

    /**
     * Lägger på dagsaktuella bränslepriser (Bilresa-backenden) och elpriser
     * (hemmaladdning + snabbladdningssnitt från Elbilsladdning) sist i systemprompten.
     * Var för sig fail-open — en källa som ligger nere tar inte med sig den andra.
     */
    private String withEnergyPrices(String systemPrompt) {
        String out = systemPrompt;
        try {
            String fuel = fuelPriceService.promptContext();
            if (!fuel.isEmpty()) out += "\n" + fuel;
        } catch (Exception ignored) {}
        try {
            String electricity = electricityPriceService.promptContext();
            if (!electricity.isEmpty()) out += "\n" + electricity;
        } catch (Exception ignored) {}
        return out;
    }

    private String buildChatSpecFacts(String carContext) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^\\d+\\.\\s+(.+?)\\s*—", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(carContext);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String raw = m.group(1).trim();
            String name = CarTitle.stripYear(raw);
            Integer legroom = null;
            String chem = null;
            String safety = null;
            String consumption = null;
            com.caradvice.model.CargoSpecDto bagage = null;
            // Bagagevolymen fanns i tabellen men aldrig i chattens prompt — bara benutrymmet
            // gick in här. Chatten svarade därför på bagagefrågor ur modellens eget minne.
            try { bagage = cargoSpecService.formatForTitle(name); } catch (Exception ignored) {}
            try { legroom = cargoSpecService.getLegroom(name); } catch (Exception ignored) {}
            try { chem = evSpecService.getBatteryChemistry(name); } catch (Exception ignored) {}
            try { safety = safetyRatingService.formatForTitle(name); } catch (Exception ignored) {}
            // Årtalet plockas ur den ORÖRDA raden — name har fått det bortstrippat, men vakten
            // behöver det för att kunna avstå när bilen är äldre än tabellens generation.
            try { consumption = iceConsumptionService.consumptionSummaryForTitle(
                    name, CarTitle.year(raw)); } catch (Exception ignored) {}
            boolean harBagage = bagage != null && bagage.cargoLiters() > 0;
            if (legroom == null && chem == null && safety == null && consumption == null && !harBagage) continue;
            sb.append(name).append(": ");
            boolean needsComma = false;
            if (harBagage) {
                sb.append("bagage ").append(bagage.cargoLiters()).append(" l");
                if (bagage.cargoMaxLiters() > 0) sb.append(" (max ").append(bagage.cargoMaxLiters()).append(" l)");
                needsComma = true;
            }
            if (legroom != null) {
                if (needsComma) sb.append(", ");
                sb.append("benutrymme bak ").append(legroom).append(" mm");
                needsComma = true;
            }
            if (chem != null) {
                if (needsComma) sb.append(", ");
                sb.append("batterikemi ").append(chem);
                needsComma = true;
            }
            if (safety != null) {
                if (needsComma) sb.append(", ");
                sb.append(safety);
                needsComma = true;
            }
            if (consumption != null) {
                if (needsComma) sb.append(", ");
                sb.append(consumption);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Bagagekravet i användarens egen fråga, eller {@code null} när frågan inte handlar om
     * bagagevolym. Talet måste stå NÄRA ett bagageord — "420 liter" i en fråga om tankvolym
     * eller batteri är inte ett bagagekrav.
     */
    private static final java.util.regex.Pattern BAGAGEKRAV = java.util.regex.Pattern.compile(
            "(?iu)(?:(?:bagage|lastutrymm|lastvolym|baklucka)\\w*[^.!?]{0,60}?(\\d{3,4})\\s*(?:l\\b|liter)"
            + "|(\\d{3,4})\\s*(?:l\\b|liter)[^.!?]{0,60}?(?:bagage|lastutrymm|lastvolym|baklucka))");

    static Integer bagagetroskel(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Matcher m = BAGAGEKRAV.matcher(text);
        if (!m.find()) return null;
        String tal = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            int v = Integer.parseInt(tal);
            // Under 100 l är ingen bagagevolym och över 2500 l är ingen personbil — ett tal
            // utanför spannet är något annat som råkade stå nära ordet.
            return (v >= 100 && v <= 2500) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Hur många tecken den verifierade bagagelistan får ta i systemprompten.
     *
     * <p>Sänkt från 3 000 till 1 500 efter det andra skarpa provet: hela blocket landade på
     * ~940 tokens, och den långa listan fylldes av bilar långt över kravet (Rolls-Royce,
     * Cadillac, skåpbilar) som inte gör svaret bättre på frågan "mer än 420 liter". Kortare
     * lista, samma spridning.
     */
    private static final int BAGAGELISTA_TECKEN = 1500;
    /** Hur många bilar UNDER kravet som räknas upp som kontrast. */
    private static final int BAGAGELISTA_UNDER = 6;

    /**
     * Verifierade bagagevolymer ur {@code cargo_spec} som chatten måste grunda sitt svar i.
     *
     * <p><b>Felet regeln lagar</b> (rapporterat av användaren 2026-09-04). På frågan om elbilar
     * med mer än 420 l bagage svarade chatten bl.a. <b>Renault Zoe</b> och <b>Hyundai Kona PHEV</b>.
     * Vår egen tabell säger 338 l respektive 374 l — siffrorna FANNS, men de nådde aldrig
     * prompten: {@code buildChatSpecFacts} injicerade benutrymme, batterikemi, säkerhet och
     * förbrukning, men aldrig bagagevolymen, och bara för bilar som redan låg i kortkontexten.
     * En fråga utan kortkontext ("vilka elbilar har mer än 420 liter?") hade därför noll
     * verifierade tal att stå på, och modellen svarade ur sitt eget minne.
     *
     * <p><b>Varför en lista och inte bara en regel.</b> Rekommendationsvägen har en efterkontroll
     * mot {@code cargo_spec} som kastar bilar under kravet ({@code requireCargoCapacity}), men
     * chatten <b>strömmar</b> sitt svar — det finns inget färdigt svar att granska innan
     * användaren läser det. Vakten måste därför sitta i prompten, och en regel utan data hade
     * bara gjort chatten svarslös.
     *
     * <p><b>Listan kapas med flit.</b> 411 av 642 rader klarar 420 l, vilket är ~1 100 tokens —
     * för mycket bredvid en prompt som redan är stor (Groqs 8 000 TPM räknar prompt + svar, se
     * 413-fällan). Urvalet sprids därför jämnt över hela spannet i stället för att toppa listan
     * med de största bilarna: både gränsfallen strax över kravet och de riktigt rymliga ska
     * finnas med. Raderna strax UNDER kravet följer med som avskräckande exempel — det är de
     * som annars smyger in i ett svar.
     */
    String bagagekontext(int troskel) {
        List<Map<String, Object>> alla;
        try { alla = cargoSpecService.allaMedVolym(); } catch (Exception e) { return ""; }
        if (alla == null || alla.isEmpty()) return "";

        record Rad(String namn, int liter) {}
        List<Rad> over = new ArrayList<>();
        List<Rad> under = new ArrayList<>();
        for (Map<String, Object> r : alla) {
            Object namn = r.get("carName");
            Object liter = r.get("cargoLiters");
            if (namn == null || !(liter instanceof Number n) || n.intValue() <= 0) continue;
            (n.intValue() >= troskel ? over : under).add(new Rad(namn.toString(), n.intValue()));
        }
        if (over.isEmpty()) return "";
        over.sort(java.util.Comparator.comparingInt(Rad::liter));
        under.sort(java.util.Comparator.comparingInt(Rad::liter).reversed());

        // Jämnt spritt urval: varje k:te rad, så att både 420 l och 900 l finns representerade.
        int tecken = 0;
        for (Rad r : over) tecken += r.namn().length() + 6;
        int steg = Math.max(1, (int) Math.ceil((double) tecken / BAGAGELISTA_TECKEN));
        StringBuilder listan = new StringBuilder();
        int antal = 0;
        for (int i = 0; i < over.size(); i += steg) {
            if (antal++ > 0) listan.append(", ");
            listan.append(over.get(i).namn()).append(" ").append(over.get(i).liter());
        }

        StringBuilder ut = new StringBuilder();
        ut.append("VERIFIERADE BAGAGEVOLYMER (vår egen cargo_spec-tabell, liter med baksätet uppfällt).\n");
        ut.append("Klarar ").append(troskel).append(" l — ").append(antal).append(" av ")
          .append(over.size()).append(" modeller, urvalet spritt över hela spannet: ")
          .append(listan).append("\n");
        if (!under.isEmpty()) {
            // Också de här sprids i stället för att toppas: en ren närmiss-lista blev tolv rader
            // strax under kravet (och tre av dem samma AMG GT), medan felet vi lagar handlar om
            // små bilar långt under gränsen. Spridningen visar hela fallhöjden.
            int stegUnder = Math.max(1, (int) Math.ceil((double) under.size() / BAGAGELISTA_UNDER));
            ut.append("Klarar INTE kravet (spritt urval): ");
            int skrivna = 0;
            for (int i = 0; i < under.size() && skrivna < BAGAGELISTA_UNDER; i += stegUnder) {
                if (skrivna++ > 0) ut.append(", ");
                ut.append(under.get(i).namn()).append(" ").append(under.get(i).liter());
            }
            ut.append("\n");
        }
        String rekommenderade = volymerForRekommenderadeModeller(alla, troskel);
        if (!rekommenderade.isBlank())
            ut.append("Uppmätt volym för modeller som mina egna kategoriregler pekar ut: ")
              .append(rekommenderade).append("\n");
        ut.append("REGEL: påstå ALDRIG att en bil klarar ett bagagekrav utan att ha dess verifierade ")
          .append("siffra. Saknas bilen i listan ovan — säg att du inte har en verifierad volym för den, ")
          .append("gissa aldrig. Uppmätta tal går före ditt eget minne också när de skiljer sig.");
        return ut.toString();
    }

    /** Hur många rekommenderade modeller som får sin volym med. */
    private static final int BAGAGELISTA_REKOMMENDERADE = 24;
    /** Hur många varianter av samma modell som får plats — sex Enyaq-rader säger inget mer än tre. */
    private static final int BAGAGELISTA_PER_MODELL = 3;

    /**
     * Uppmätta volymer för de modeller som prompten SJÄLV pekar ut som förstahandsval.
     *
     * <p><b>Felet den lagar, uppmätt skarpt efter första fixen 2026-09-04.</b> Med bara "klarar"-
     * och "klarar inte"-listorna svarade chatten fortfarande <b>"MG 4 — 520 L"</b> på
     * 420-litersfrågan. Talet finns ingenstans i vår tabell (MG4 står på 363 l), och listorna
     * kunde inte hjälpa: en bil UNDER kravet kan per definition inte stå bland dem som klarar
     * det, och det spridda urvalet av underkända bilar råkade inte träffa just MG4. Samtidigt är
     * MG4 en av modellerna {@link #ALLA_KATEGORIREGLER} själv rekommenderar för elbilssök — så
     * prompten bad om bilen med ena handen och lämnade volymen öppen för gissning med den andra.
     *
     * <p>Matchningen går via {@link ExpertInsightService#modelPosition} med samma ordgränsregel
     * som bilkorten, och prövar både hela modellnamnet ("Enyaq iV") och första modellordet
     * ("Enyaq") — reglerna skriver "Škoda Enyaq" medan tabellen har "Škoda Enyaq iV", och en
     * exakt jämförelse gav 8 träffar där ordgränsvarianten ger 29.
     */
    private String volymerForRekommenderadeModeller(List<Map<String, Object>> alla, int troskel) {
        String regler = ExpertInsightService.flattenSpaces(ALLA_KATEGORIREGLER);
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> perModell = new LinkedHashMap<>();  // "skoda enyaq" -> antal varianter
        int antal = 0;
        for (Map<String, Object> r : alla) {
            if (antal >= BAGAGELISTA_REKOMMENDERADE) break;
            Object namnO = r.get("carName");
            Object literO = r.get("cargoLiters");
            if (namnO == null || !(literO instanceof Number n) || n.intValue() <= 0) continue;
            String namn = namnO.toString();
            String[] ord = namn.trim().split("\\s+");
            List<String> kandidater = ord.length > 1
                    ? List.of(String.join(" ", java.util.Arrays.copyOfRange(ord, 1, ord.length)), ord[1])
                    : List.of(namn);
            boolean traff = false;
            for (String k : kandidater) {
                if (k.length() >= 2 && ExpertInsightService.modelPosition(regler, k) >= 0) { traff = true; break; }
            }
            if (!traff) continue;
            // Sex Enyaq-varianter och fem MG MG4-rader säger inget mer än ett par av varje, och
            // de trängde ut andra modeller ur listan. TVÅ nycklar behövs: "Hyundai IONIQ" som
            // enda grupp gjorde IONIQ 5 och 6 till varianter av IONIQ 3 och klippte bort dem,
            // medan "Škoda Enyaq 85" och "85x" verkligen är varianter av samma bil.
            String grupp = ExpertInsightService.flattenSpaces(
                    ord.length > 1 ? ord[0] + " " + ord[1] : namn);
            String variant = ord.length > 2 ? grupp + " " + ExpertInsightService.flattenSpaces(ord[2]) : grupp;
            if (perModell.merge(variant, 1, Integer::sum) > 1) continue;
            if (perModell.merge(grupp, 1, Integer::sum) > BAGAGELISTA_PER_MODELL) continue;
            if (antal++ > 0) sb.append(", ");
            sb.append(namn).append(" ").append(n.intValue());
            if (n.intValue() < troskel) sb.append(" (klarar INTE)");
        }
        return sb.toString();
    }

    private List<String> extractUserTexts(List<Map<String, String>> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .toList();
    }

    /**
     * Brasklapp för laddhybrider. EU:s beräkning av laddhybriders CO2 skärps 1 januari 2027:
     * förbrukningen mäts med både fulladdat och nästan tomt batteri, så samma bil får ett högre
     * officiellt CO2-värde och därmed högre fordonsskatt (malus tas ut de tre första åren efter
     * nyregistrering). Gäller NYA laddhybrider registrerade från 2027 — redan registrerade bilar
     * behåller sin skatt, vilket är avgörande här eftersom appen mest föreslår begagnat.
     * Siffrorna är hämtade ur carup.se/chocken-bilskatt-kan-oka-med-1300.
     *
     * <p>Hårdkodad i stället för skrapad av två skäl: skatteregler är uttryckligen uteslutna ur
     * relevansvakten, och en kategoribred notis saknar car_make — sådana rader sparas aldrig
     * (se saveInsights) och skulle inte visas ens om de sparades.
     */
    static final String PHEV_TAX_CAVEAT = """
            LADDHYBRIDSKATT (nämn detta i "con" eller fitSummary för varje laddhybrid): EU:s beräkning av \
            laddhybriders koldioxidutsläpp skärps 1 januari 2027 — förbrukningen mäts då med både fulladdat \
            och nästan tomt batteri, så samma bil får ett högre officiellt CO2-värde och därmed högre \
            fordonsskatt. Regeln gäller NYA laddhybrider som registreras från 2027; en redan registrerad \
            bil behåller sin skatt, så en begagnad laddhybrid påverkas inte. Exempel: en bil som mätts till \
            48 g/km kan få 82 g/km och gå från 360 kr/år till ca 3 500 kr/år — ca 9 400 kr mer under de tre \
            malusåren. Bilar med litet batteri och under ca 5–6 mils elräckvidd drabbas hårdast, medan \
            10–14 mils räckvidd klarar sig bättre. RÅD: föredra laddhybrider med lång elräckvidd. Skriv \
            ALDRIG en exakt skattesiffra för en enskild modell — hänvisa till Transportstyrelsen för det \
            verkliga beloppet.
            """;

    String buildSystemPrompt(String expertContext, String fuelType) {
        return buildSystemPrompt(expertContext, fuelType, null);
    }

    /**
     * Vad användaren vill ha för drivlina, tolkat ur formulärets drivmedel och kategori.
     * {@code pureEv()} är villkoret bakom ELBIL OBLIGATORISKT i systemprompten.
     */
    record FuelIntent(boolean ev, boolean ice, boolean phev, boolean hev) {
        /** Ren batterielbil: el önskat, inget förbränningsinslag och ingen laddhybrid. */
        boolean pureEv() { return ev && !ice && !phev; }
    }

    /**
     * Enda stället drivmedelssträngen tolkas. Egen metod eftersom regelvakten
     * {@link #requirePureEvCars} måste pröva EXAKT samma villkor som prompten ställer —
     * delsträngsmatchningen nedan är full av fällor ("diesel" och "spelar ingen roll"
     * innehåller båda "el"), och två kopior av villkoret hade glidit isär vid första
     * ändringen.
     *
     * <p>"Hybrid (ej laddhybrid)" är en bensinbil med elassistans, inte en elbil. Valet
     * matchade {@code ev} på delsträngen "hybrid" och föll ur {@code ice}, vilket gav två fel
     * samtidigt: systemprompten krävde "ENBART renodlade batterielbilar (BEV)" för en
     * HEV-förfrågan, och ICE-nypristabellen utelämnades trots att en hybrid prissätts som en
     * bensinbil. Därför testas {@code hev} på exakt "hybrid", före allt annat.
     *
     * <p>Drivmedelslistan i formuläret saknar "laddhybrid" (valen är bensin/diesel/hybrid/el/
     * spelar ingen roll) — laddhybrid uttrycks som KATEGORI. fuelType testas ändå för
     * API-anropare som skickar drivmedlet direkt.
     *
     * <p><b>KATEGORIN "elbil" är lika bindande som "laddhybrid", och det måste stå här och inte
     * hos anroparna.</b> Formuläret DÖLJER drivmedelsrutan för båda kategorierna och tvingar
     * värdet till "spelar ingen roll" — en sträng som bär delsträngen "el" inuti "spelar".
     * Laddhybrid räddades av sin {@code carCategory}-gren, men elbil hade ingen: {@code ev} och
     * {@code ice} blev båda sanna och {@code pureEv()} FALSKT. Uppmätt 2026-08-13 gav
     * formulärets elbilssök därför tre fel samtidigt, alla på appens vanligaste elbilsväg:
     *
     * <ol>
     *   <li>{@code requirePureEvCars} kördes aldrig — drivmedelsvakten var avstängd</li>
     *   <li>"ELBIL OBLIGATORISKT" saknades i systemprompten</li>
     *   <li>BÅDA nypristabellerna följde med, alltså det tyngsta promptfallet — samma
     *       storleksklass som gav HTTP 413 (se {@link #RETRY_MAX_TOKENS})</li>
     * </ol>
     *
     * <p>Att fixa det hos varje anropare hade krävt tre kopior av samma villkor, vilket är
     * precis det den här metoden finns för att förhindra. Live-verifieringarna missade felet
     * för att de gick via API med {@code fuelType="el"}, där {@code pureEv()} redan var sant —
     * formulärets payload provades aldrig.
     */
    static FuelIntent fuelIntent(String fuelType, String carCategory) {
        boolean elbilKategori = "elbil".equals(carCategory);
        boolean hev = !elbilKategori && "hybrid".equalsIgnoreCase(fuelType == null ? null : fuelType.trim());
        boolean ev = elbilKategori || (!hev && fuelType != null &&
                (fuelType.contains("el") || fuelType.contains("hybrid") || fuelType.contains("phev")));
        boolean ice = !elbilKategori && (hev || fuelType == null || fuelType.isBlank() ||
                fuelType.contains("bensin") || fuelType.contains("diesel") ||
                fuelType.equals("spelar ingen roll"));
        String ft = fuelType == null ? "" : fuelType.toLowerCase();
        boolean phev = "laddhybrid".equals(carCategory) || ft.contains("laddhybrid") || ft.contains("phev");
        return new FuelIntent(ev, ice, phev, hev);
    }

    /**
     * Formulärets val → Blockets fältvärden, så att prisgolvet sätts av en bil användaren
     * faktiskt kan köpa. Se {@link BlocketPriceService.AdFilter} för mätningen som motiverar
     * filtret och för fältens verkliga värden.
     *
     * <p>Bygger på {@link #fuelIntent} i stället för att tolka strängen på nytt — det är hela
     * poängen med att den metoden finns, och en andra tolkning hade glidit isär vid första
     * ändringen precis som dess javadoc varnar för.
     *
     * <p>"Hybrid bensin" räknas som bensin: en självladdande hybrid är en bensinbil, samma
     * bedömning som {@code fuelIntent} gör när den lägger HEV under {@code ice}. "Plug-in
     * Bensin" räknas däremot inte — den som bett om bensin har inte bett om en laddhybrid.
     *
     * <p>Kategorin elbil behöver INGEN egen gren här: {@code fuelIntent} avgör den redan, och
     * en andra kopia av det villkoret var precis vad som gjorde felet svårt att se från början.
     */
    static BlocketPriceService.AdFilter adFilterFor(String fuelType, String carCategory, String transmission) {
        String gearbox = null;
        if (transmission != null) {
            String t = transmission.trim().toLowerCase();
            if (t.equals("automat")) gearbox = "Automatisk";
            else if (t.equals("manuell")) gearbox = "Manuell";
        }

        FuelIntent intent = fuelIntent(fuelType, carCategory);
        String ft = fuelType == null ? "" : fuelType.toLowerCase();
        java.util.Set<String> fuels;
        if (intent.pureEv())                                  fuels = java.util.Set.of("El");
        else if (intent.phev())                               fuels = java.util.Set.of("Plug-in Bensin", "Plug-in Diesel");
        else if (intent.hev())                                fuels = java.util.Set.of("Hybrid bensin", "Hybrid diesel");
        else if (ft.contains("bensin"))                       fuels = java.util.Set.of("Bensin", "Hybrid bensin");
        else if (ft.contains("diesel"))                       fuels = java.util.Set.of("Diesel", "Hybrid diesel");
        else                                                  fuels = java.util.Set.of();

        return new BlocketPriceService.AdFilter(fuels, gearbox);
    }

    // ── Kategoriblocken: skickas bara när de gäller sökningen ────────────────────────
    // Mätt 2026-08-28: 2 547 av regeltextens 6 972 tecken (37 %) var kategori- eller
    // drivmedelsbundna och följde ändå med VARJE sökning. Ett småbilssök bar hela
    // SUV-avsnittet, ett elbilssök bar bensinreglerna. Besparing per sökning, mätt:
    // ~1 046 tokens (smaabil), ~1 080 (laddhybrid), ~974 (bensin/diesel), ~914
    // (familjebil), ~555 (suv) — en fjärdedel av hela prompten.
    //
    // Raderna är ORDAGRANT de som stod i textblocket; bara var de bor har ändrats.
    private static final String FAMILJEBIL_REGEL =
            "FAMILJEBIL (kategori \"familjebil\", användning \"familj\" eller 5+ passagerare): rekommendera ALDRIG småbilar/stadsbilar (t.ex. Dacia Spring, Citroën ë-C3, Renault 5/Zoe/Clio, Fiat 500e/Panda, Opel Corsa, Toyota Aygo) — välj kombi, SUV eller rymlig halvkombi/sedan. Utgå från — bensin/diesel/hybrid: Volvo V60/V90, Škoda Octavia Combi, Kia Ceed SW, Dacia Jogger (finns med 7 säten); elbil: Škoda Enyaq, Škoda Elroq, VW ID.4, Kia EV6/EV3/Niro, Polestar 2, MG4, MG5 (elkombi, 578 l bagage, billigast i klassen under ca 250 000 kr).\n";
    private static final String SUV_REGEL =
            "SUV (kategori \"suv\"): SUV betyder HÖG bil — hög sittposition, stor markfrigång, kaross i storleksklass Volvo XC40 / Škoda Kamiq eller större. En halvkombi, sedan eller låg crossover är ALDRIG en SUV: föreslå aldrig MG4, MG5, VW ID.3, Tesla Model 3, Polestar 2, Renault Zoe, Nissan Leaf, Kia Niro eller Hyundai Kona i den här kategorin, hur väl de än passar i övrigt. Drivmedlet avgör modellen, blanda ALDRIG ihop namn som liknar varandra men är olika bilar — bensin/diesel/hybrid: Volvo XC40/XC60/XC90, Audi Q3/Q5/Q7, Škoda Kamiq/Karoq/Kodiaq, VW T-Roc/Tiguan, Toyota RAV4/C-HR (hybrid), Kia Sportage, Hyundai Tucson, BMW X1/X3, Mercedes GLA/GLC; elbil: Volvo EX40/EC40 (ALDRIG \"XC40\" som elbil — XC40 är bensin/diesel/PHEV, EX40 är den rena elbilen), VW ID.4/ID.5, Hyundai Ioniq 5, Kia EV6/EV9, Tesla Model Y, Škoda Enyaq, Audi Q4 e-tron, Peugeot e-2008 (prisvärd liten el-SUV), BMW iX1/iX3, Mercedes EQB.\n";
    private static final String SUV_BUDGET_REGEL =
            "SUV OCH BUDGET: matcha SUV-storleken mot budgeten. Från ca 350 000 kr ska minst två av tre vara riktiga mellanklass-SUV:ar (VW ID.4, Hyundai Ioniq 5, Tesla Model Y, Volvo EX40, Škoda Enyaq, Kia EV6, Audi Q4 e-tron) — att svara med billiga små bilar långt under budgeten är fel svar även om de är prisvärda. Peugeot e-2008 och MG ZS EV hör hemma i de LÄGRE budgetspannen, inte som svar på en halv miljon.\n";
    private static final String SMABIL_REGEL =
            "SMÅBIL (kategori \"smaabil\"): bensin/diesel t.ex. Toyota Aygo, Škoda Fabia, VW Polo, Hyundai i20, Kia Picanto, Ford Fiesta, Dacia Sandero; hybrid t.ex. Toyota Yaris Hybrid; elbil t.ex. Renault Zoe, Renault 5 E-Tech.\n";
    private static final String DRIVMEDEL_REGEL =
            "DRIVMEDLET ÄR ETT VAL, INTE ETT UNGEFÄR: väljer användaren \"bensin\" eller \"diesel\" ska ALLA tre bilar ha ren förbränningsmotor. En hybrid är ett EGET val i formuläret (\"Hybrid (ej laddhybrid)\"), så ett bensinsök som svarar med Toyota Corolla Hybrid, Honda Jazz Hybrid eller Kia Niro Hybrid har svarat på fel fråga. Kontrolleras i kod efteråt; en bil som bryter mot det kastas.\n";
    private static final String PHEV_REGEL =
            "PHEV: rekommendera ALDRIG en årsmodell äldre än modellens faktiska PHEV-lansering (Golf GTE 2014+, Outlander PHEV 2013+, Passat GTE 2015+).\n";

    /**
     * Alla kategori- och drivmedelsblock i sökningens egen ordning.
     *
     * <p>Sökvägen plockar blocken efter formulärets svar; det går inte i chatten, som inte vet
     * vad frågan gäller förrän den ställts. Men det läget finns redan — {@code prefs == null}
     * ger hela uppsättningen, och det är exakt samma situation. Konstanten är den uppsättningen
     * under ett namn, så chatten kan få ORDAGRANT samma regler som korten i stället för en egen
     * formulering som glider isär från vakterna i koden.
     *
     * <p><b>Varför chatten behövde dem.</b> Reglerna fanns bara i sökprompten, och 2026-08-29
     * svarade chatten på en fråga om familjebil under 300 000 kr med "Volkswagen ID.4, Škoda
     * Enyaq iV, Kia EV6, Hyundai Kona PHEV, Renault Zoe och MG4" — Zoe är en fyrasitsig småbil
     * och står uttryckligen i FAMILJEBIL-regelns ALDRIG-lista, den som korten alltid följt.
     * Samma fråga genom sökformuläret hade aldrig gett det svaret.
     */
    static final String ALLA_KATEGORIREGLER =
            FAMILJEBIL_REGEL + SUV_REGEL + SUV_BUDGET_REGEL + SMABIL_REGEL + DRIVMEDEL_REGEL + PHEV_REGEL;

    /**
     * Prisdisciplinen ur sökprompten, ordagrant, åt chatten.
     *
     * <p><b>Varför.</b> Chatten hade en enda mening om priser och ingen kontroll efteråt. Skarpt
     * 2026-08-29, direkt efter att kategoribristen lagats: på "familjebil under 300 000 kr" svarade
     * den att Enyaq, ID.4, EV6, Ioniq 5 och Polestar 2 <b>alla</b> har nypris 295 000 kr, alla går
     * 520 km och alla tappar 45 % på fem år. De riktiga nypriserna i vår egen tabell är 494 000,
     * 440 000 respektive 569 000 kr. Talet var valt för att rymmas i budgeten, alltså fabricerat
     * precis så som sökprompten förbjuder — bilarna "passade" budgeten genom att priset ändrades.
     *
     * <p><b>Vad som INTE följer med.</b> Sökningens BUDGETTAK-rad slutar med "kontrolleras mot
     * riktiga Blocket-annonser efteråt; en bil som bryter mot det kastas". Det är sant om korten,
     * som går genom {@code requireAffordableModels}, men inte om chatten — den har ingen sådan
     * vakt. En regel som lovar en kontroll som inte finns är värre än ingen regel, så chatten får
     * i stället begagnatgolven, som bär samma information utan påståendet: en modell vars golv
     * ligger över budgeten + 30 000 kr är fel förslag.
     *
     * <p>Raden om fabricerade priser är hämtad ur sökprompten och inte avskriven; ett prov kräver
     * att sökprompten fortfarande innehåller exakt den strängen, annars faller bygget i stället
     * för att chatten tyst får en egen formulering.
     */
    private static final String PRISFABRIKATION_REGEL =
            "FABRICERA ALDRIG PRISER: price = nypris × ålderskoefficient, kontrollera mot nypristabellen. Ex: Octavia 2021+ nypris 340 000 kr, 3 år → 221 000 kr — kan ALDRIG kosta 100 000 kr. Räcker inte budgeten: byt till billigare bil, sänk ALDRIG priset.";

    /**
     * Enda raden som är skriven FÖR chatten, och skälet är utdataformen — inte att reglerna skiljer.
     *
     * <p>{@link #EV_PRICE_FLOORS} och {@link #DEPRECIATION_RULE} talar om samma tal på två sätt:
     * golven är uppmätta BEGAGNATpriser att använda "i stället för att räkna fram priset ur
     * nypriset", medan avskrivningsregeln säger "begagnatpris = nypris × koefficient". I ett kort
     * krockar de aldrig — där finns ETT prisfält och en kodvakt efteråt. I fritext bygger modellen
     * en tabell med båda kolumnerna och fyller den ena med den andras tal.
     *
     * <p><b>Uppmätt 2026-08-29</b>, i samma svar som annars var rätt: kolumnen "Pris (nypris)"
     * innehöll golven — Enyaq 279 000, ID.4 229 500, Polestar 2 209 000, MG4 195 000, MG5 180 000,
     * alltså nästan exakt tabellens värden — medan de riktiga nypriserna är 494 000, 440 000 och
     * 510 000 kr. Sedan drogs värdeminskning av EN GÅNG TILL ur golvet, så Enyaq landade på
     * "begagnatpris ≈ 221 000 kr". Fabriceringen var borta; dubbelavdraget kom i stället.
     */
    private static final String GOLVEN_AR_BEGAGNATPRIS =
            "BEGAGNATGOLVEN OVAN ÄR REDAN BEGAGNATPRISER: kalla dem aldrig nypris, och dra aldrig av"
            + " värdeminskning på dem en gång till. Golvet är vad billigaste annonsen kostar i dag;"
            + " nypriset står i nypristabellen och är ett HÖGRE tal.\n";

    static final String PRISREGLER_CHATT =
            DEPRECIATION_RULE + "\n" + EV_PRICE_FLOORS + "\n" + GOLVEN_AR_BEGAGNATPRIS + PRISFABRIKATION_REGEL + "\n";

    /**
     * Rubriken som gör de lånade reglerna sanna i chatten.
     *
     * <p>Blocken är skrivna för sökningen och några av dem lovar en kodkontroll:
     * {@code DRIVMEDEL_REGEL} slutar "Kontrolleras i kod efteråt; en bil som bryter mot det
     * kastas". Det gäller korten, som går genom drivmedels- och budgetvakterna, men inte chattens
     * fritext. Att redigera bort meningen ur den delade texten hade gett chatten en egen version
     * — precis den glidning som orsakade både Zoe-svaret och 295 000-svaret. Rubriken säger i
     * stället vems svar kontrollerna gäller, så samma ord blir sanna på båda ställena.
     *
     * <p>Föll ut av ett prov som krävde att chatten INTE lovar en kontroll den saknar; utan det
     * hade meningen följt med tyst.
     */
    private static final String REGELRUBRIK_CHATT =
            "\nREGLER FÖR MODELLVAL OCH PRIS — samma regler som sökresultaten följer. "
            + "Där en regel säger att något \"kontrolleras i kod\" eller \"kastas\" gäller det "
            + "kortens svar; i chatten är det din egen kvalitetsgräns och den är lika bindande.\n";

    /**
     * Utan prefs vet vi inte vad sökningen gäller — då följer ALLA kategoriblock med.
     * Det är 2- och 3-argumentsvägens beteende, och testernas: en tyst bortfiltrering
     * där hade dolt regressioner i regeltexten.
     */
    String buildSystemPrompt(String expertContext, String fuelType, String carCategory) {
        return buildSystemPrompt(expertContext, fuelType, carCategory, null);
    }

    /** Skarpa vägen: prefs avgör vilka kategoriblock som är relevanta. */
    String buildSystemPrompt(String expertContext, CarPreferences prefs) {
        return buildSystemPrompt(expertContext, prefs.fuelType(), prefs.carCategory(), prefs);
    }

    String buildSystemPrompt(String expertContext, String fuelType, String carCategory,
                             CarPreferences prefs) {
        FuelIntent intent = fuelIntent(fuelType, carCategory);
        boolean wantsEv = intent.ev(), wantsIce = intent.ice(), wantsPhev = intent.phev();
        String icePrices = (wantsIce && !wantsEv) || wantsIce ? getIcePrices() : "";
        String evPrices  = wantsEv || (!wantsIce) ? getEvPrices() : "";
        // Filter: pure EV/PHEV → no ICE table; pure ICE → no EV table
        if (wantsEv && !wantsIce) icePrices = "";
        if (!wantsEv && wantsIce)  evPrices  = "";

        // Villkoren är AVSIKTLIGT samma predikat som kodvakterna använder
        // (requiresFamilySizedCar, requiresSuvShapedCar, fuelIntent): står regeln i
        // prompten ska vakten kunna falla på den, och tvärtom. Glider de isär får AI:n
        // antingen en regel ingen kontrollerar, eller kastas bilar för en regel den
        // aldrig fick se. FAMILJEBIL kan INTE gissas ur kategorin ensam — regeln gäller
        // även "användning familj" och 5+ passagerare, och de finns bara i prefs.
        boolean allt = (prefs == null);
        String ftLower = fuelType == null ? "" : fuelType.toLowerCase().trim();
        StringBuilder kat = new StringBuilder();
        if (allt || requiresFamilySizedCar(prefs)) kat.append(FAMILJEBIL_REGEL);
        if (allt || requiresSuvShapedCar(prefs))   kat.append(SUV_REGEL).append(SUV_BUDGET_REGEL);
        if (allt || "smaabil".equalsIgnoreCase(carCategory)) kat.append(SMABIL_REGEL);
        // Bara ett UTTALAT förbränningsval: "spelar ingen roll" är inget val, och regeln
        // handlar om att respektera det användaren faktiskt kryssat i.
        if (allt || ftLower.contains("bensin") || ftLower.contains("diesel") || ftLower.equals("hybrid"))
            kat.append(DRIVMEDEL_REGEL);
        if (allt || wantsPhev) kat.append(PHEV_REGEL);
        String kategoriRegler = kat.toString();

        String base = """
                Svensk bilrådgivare, sv. marknaden 2025–2026. Svara ENDAST med JSON:
                {"recommendations":[{"title":"Märke Modell (år)","price":"X–Y kr","whyRecommended":"källa t.ex. 'Teknikens Värld: toppbetyg'","pros":["p1","p2","p3"],"con":"nackdel","fitSummary":"varför bilen passar profilen","expertOpinion":"max 2 meningar om körkänsla och tillförlitlighet — ej listpris","horsepower":150,"engineOptions":"motorvarianter kommaseparerade","fuelSpec":null}]}
                horsepower (hk, heltal) och engineOptions (kommaseparerad STRÄNG) får ALDRIG vara null. engineOptions bensin/diesel ex: '1.0 TSI 95hk manuell, 1.5 TSI 150hk DSG automat'; elbil ex: '44 kWh 95hk (400km), 60 kWh 204hk (570km)'.
                Bensin/diesel fuelSpec: {"consumptionLiterPerMil":X.X,"gearbox":"Automat 7-växlad","horsepower":N,"engineVolumeLiters":X.X}. gearbox ska bara innehålla VÄXELLÅDAN — "Manuell 6-växlad", "Automat 8-växlad", "Automat CVT", "Automat DSG 7-växlad". Skriv ALDRIG motor- eller turbobeteckningar där (TSI, TDI, GDI, HEV, turbo): de hör till motorn, sätts av databasen och blir fel på fel märke — TSI är VW-koncernens beteckning och hör inte hemma på en Volvo. Elbil/laddhybrid: fuelSpec=null.
                ALLTID EXAKT 3 OLIKA bilar (tre olika modeller — aldrig samma bil två gånger) — aldrig färre. Om budgeten är knapp: billigare segment, äldre årsmodell eller annat märke (nämn det i fitSummary). fitSummary konkret och personlig; driftkostnad i pros vid hög körsträcka.
                """ + kategoriRegler + EV_PRICE_FLOORS + """
                UTNYTTJA BUDGETEN: minst en rekommendation ska ligga nära budgeten (topp ~80–100 %) — föreslå aldrig bara väsentligt billigare bilar när budgeten räcker till något rymligare, nyare eller bättre utrustat. En billig outlier är OK som prisvärt alternativ, men aldrig som enda nivå.
                BUDGETTAK: en bil får ALDRIG kosta mer än budgeten + 30 000 kr på begagnatmarknaden, räknat på den BILLIGASTE annonsen. Går modellens billigaste exemplar inte under taket är bilen fel förslag hur väl den än passar — byt till äldre årsmodell, enklare utrustning eller billigare märke i samma storleksklass. Taket kontrolleras mot riktiga Blocket-annonser efteråt; en bil som bryter mot det kastas.
                SIKTA MOT SPANNET: minst två av tre förslag ska ligga inom ±30 000 kr från budgeten. Det tredje får vara billigare om det är ett genuint prisvärt alternativ.
                "price" är ALLTID ett intervall som "85 000–100 000 kr" — siffror med mellanslag, inga förkortningar eller extra text.
                """ + DEPRECIATION_RULE + "\n" + """
                "whyRecommended" är en KÄLLA eller ett omdöme ("Teknikens Värld: toppbetyg", "Vi Bilägare: prisvärd och rymlig") — ALDRIG pris- eller marknadsuppgifter. Skriv aldrig om Blocket, antal annonser, begagnatgolv eller mätarställningar där; de står redan på kortet.
                FABRICERA ALDRIG PRISER: price = nypris × ålderskoefficient, kontrollera mot nypristabellen. Ex: Octavia 2021+ nypris 340 000 kr, 3 år → 221 000 kr — kan ALDRIG kosta 100 000 kr. Räcker inte budgeten: byt till billigare bil, sänk ALDRIG priset.
                Ange motorbeteckning (TDI/TSI/MPI/volym) bara om du är säker på att varianten finns — annars bara hk + 'manuell'/'automat'.
                Rekommendera ALDRIG BYD Dolphin eller Hyundai INSTER. Håll dig till dessa märken: Audi, BMW, BYD, Citroën, Cupra, Dacia, Fiat, Ford, Honda, Hyundai, Kia, Leapmotor, MG, Mazda, Mercedes, Mini, Nissan, Opel, Peugeot, Renault, Seat, Škoda, Smart, Tesla, Toyota, Volkswagen, Volvo, Xpeng, Zeekr. Kamiq är bensinbil, INTE elbil. Aldrig bensin/diesel när användaren efterfrågar elbil.
                MÄRKESPRIORITET: föredra etablerade europeiska, koreanska och japanska märken (samt Tesla och MG). Leapmotor, Xpeng, Zeekr och BYD bara om inget etablerat märke matchar budget och behov — aldrig som förstaval. Bilar med bra räckvidd per krona (se PRISVÄRD RÄCKVIDD) är starka förslag när de passar profilen.
                Rekommendera ALDRIG en årsmodell före modellens verkliga lansering — nyheter om en modell betyder inte att den finns begagnad. Ex: Kia EV2 lanseras 2026 (finns ALDRIG begagnad), Kia EV3 2024+, EV4/EV5 2025+, Renault 5 E-Tech 2024+, Citroën ë-C3 2024+, Volvo EX30 2023+.
                Volvos enda EV-modeller: EX30, EX40, EC40, EX60, EX90 — det finns inga andra (ingen C90/C70).
                Nämn ALDRIG modeller som aldrig sålts i Sverige. Att en modell SLUTAT tillverkas är däremot inget hinder i ett begagnatsök — Renault Zoe, VW e-Golf och äldre Nissan Leaf är vanliga billiga begagnade elbilar. Undantag: i NYBILSSÖK och LEASING måste modellen gå att köpa ny idag. Hitta ALDRIG på modellnamn, versioner eller specifikationer — om osäker, välj en bil du är säker på finns.
                """ + (wantsEv && !wantsIce && !wantsPhev ? "ELBIL OBLIGATORISKT: ENBART renodlade batterielbilar (BEV) — aldrig PHEV, laddhybrid eller bensin/diesel.\n" : "")
                    + (wantsPhev ? PHEV_TAX_CAVEAT : "")
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

        // Systemprompten säger att "price" är ett köpprisintervall. I leasingläge är budgeten
        // kr/mån, och ett köppris på kortet läser då som att bilen kostar så mycket att äga.
        String leasingPrisLine = isLeasing
                ? " PRIS I LEASINGLÄGE: fältet \"price\" ska vara MÅNADSKOSTNADEN som intervall,"
                  + " t.ex. \"4 500–5 200 kr/mån\" — aldrig ett köppris eller listpris."
                  // Privatleasing tecknas på en ny bil ur märkets aktuella utbud. En "Škoda
                  // Enyaq iV 80 (2023)" går inte att privatleasa alls — den finns bara begagnad.
                  + " PRIVATLEASING GÄLLER NYA BILAR: föreslå ENDAST modeller som säljs nya i"
                  + " Sverige idag, med årsmodell " + currentYear + " eller " + (currentYear + 1)
                  + " i title-fältet. En utgången årsmodell går inte att leasa." : "";

        // Bagagekravet mäts mot NORMALvolymen (baksätet uppfällt) — maxvolymen med nedfällt säte
        // är ett annat mått och nästan tre gånger så stort, så en bil skulle glida igenom på fel
        // siffra. Kravet kontrolleras mot cargo_spec efteråt; en bil som bryter mot det kastas.
        String cargoLine = (prefs.minCargoLiters() != null && prefs.minCargoLiters() > 0)
                ? " BAGAGEKRAV: minst " + prefs.minCargoLiters() + " liter bagageutrymme med"
                  + " baksätet UPPFÄLLT (normalvolym, inte maxvolym med nedfällt säte). En bil med"
                  + " mindre bagage är FELAKTIG oavsett hur väl den passar i övrigt."
                  + " KAROSSEN AVGÖR — typiska volymer uppfällt/nedfällt: kompakt-SUV 400–520 /"
                  + " 1 300–1 600 l, mellankombi 500–610 / 1 500–1 650 l, stor kombi 600–700 /"
                  + " 1 700–1 950 l, stor SUV (7-sits) 600–780 / upp till 2 000 l, skåp-/fritidsbil"
                  + " 650–900 / 2 500–3 900 l. Räcker inte karossen till kravet, byt karosstyp i"
                  + " stället för att föreslå en större motor eller dyrare utrustningsnivå." : "";

        return """
                Budget: %s. Kategori: %s. Laddbox: %s. Körsträcka: %,d km/år (%s). Användning: %s. Passagerare: %d.%s%s%s%s%s%s
                """.formatted(
                budgetInfo, prefs.carCategory(), laddning,
                km, milprofil, usageText, prefs.passengers(), fuelLine, transmissionLine, maxAgeLine,
                leasingPrisLine, cargoLine, affordableModelsLine(prefs) + suvModelsLine(prefs)
        );
    }
}
