package com.caradvice.service;

import com.caradvice.model.CargoSpecDto;
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
    private static final String EV_PRICE_FLOORS = """
            ELBIL (kategori "elbil") — UPPMÄTTA BEGAGNATGOLV på svenska marknaden (billigaste annons med högst 10 000 mil, augusti 2026). Använd dem som prisankare i stället för att räkna fram priset ur nypriset:
            Renault Zoe fr. ca 58 000, Nissan Leaf fr. ca 70 000, MG ZS EV fr. ca 130 000, VW e-Golf fr. ca 139 000, Kia Niro EV fr. ca 175 000, MG5 fr. ca 180 000 (elkombi), MG4 och Hyundai Kona Electric fr. ca 195 000, VW ID.3 fr. ca 199 000, Polestar 2 fr. ca 209 000, Tesla Model 3 fr. ca 215 000, VW ID.4 fr. ca 229 500, Škoda Enyaq fr. ca 279 000, Kia EV6 fr. ca 317 000.
            En modell vars golv ligger över budgeten + 30 000 kr är fel förslag — välj i stället en modell vars golv ligger nära budgeten. Golvet är billigaste exemplaret: ett välutrustat eller lågmilat exemplar kostar mer.""";

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
                       LeasingPriceService leasingPriceService) {
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
    /** Omförsökets tokentak — se jsonCallBody(…, maxTokens) för varför det är högre än ordinarie. */
    private static final int RETRY_MAX_TOKENS = 3400;
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

    private HttpResponse<String> callGroqWithFallback(Object... bodies) throws Exception {
        HttpResponse<String> resp = null;
        for (Object body : bodies) {
            resp = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 429) return resp;
        }
        return resp;
    }

    private Map<String, Object> jsonCallBody(String modelName, double temperature, String systemPrompt, String userPrompt) {
        return jsonCallBody(modelName, temperature, systemPrompt, userPrompt, RECOMMENDATION_MAX_TOKENS);
    }

    /**
     * Med eget takvärde. Reservmodellen får mer utrymme än ordinarie: "AI-svaret blev
     * ofullständigt" betyder att svaret inte hann skrivas färdigt inom taket, och att göra om
     * försöket med samma tak upprepar oftast samma trunkering.
     */
    private Map<String, Object> jsonCallBody(String modelName, double temperature, String systemPrompt,
                                             String userPrompt, int maxTokens) {
        return Map.of(
                "model", modelName, "max_tokens", maxTokens, "temperature", temperature,
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
        return enrichRecommendations(parsed, kmPerYear, fuelPref, leasing, rangesOut, nyprisOut, false);
    }

    private List<CarRecommendation> enrichRecommendations(List<CarRecommendation> parsed, int kmPerYear,
                                                          String fuelPref, boolean leasing,
                                                          Map<String, BlocketPriceService.PriceRange> rangesOut,
                                                          Map<String, Integer> nyprisOut, boolean newCar) {
        List<CompletableFuture<BlocketPriceService.PriceRange>> blocketFutures = parsed.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return leasing ? blocketPriceService.fetchLeasingRange(r.title())
                                       : blocketPriceService.fetchPriceRange(r.title());
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
            IceConsumptionService.Variant iceVariant = null;
            if (fuelSpec != null && fuelSpec.consumptionLiterPerMil() != null) {
                try {
                    Integer hp = fuelSpec.horsepower() != null ? fuelSpec.horsepower() : r.horsepower();
                    iceVariant = iceConsumptionService.consumptionForTitle(r.title(), hp, fuelPref);
                } catch (Exception ignored) {}

                Double consumption = iceVariant != null ? iceVariant.literPerMil() * 10 : null;
                // Ingen verifierad match men AI:n svarade i l/mil-skala (< 3 kan inte vara l/100km) — normalisera
                if (consumption == null && fuelSpec.consumptionLiterPerMil() > 0
                        && fuelSpec.consumptionLiterPerMil() < 3) {
                    consumption = fuelSpec.consumptionLiterPerMil() * 10;
                }
                if (consumption != null) fuelSpec = new com.caradvice.model.FuelSpecDto(
                        consumption, fuelSpec.gearbox(), fuelSpec.horsepower(), fuelSpec.engineVolumeLiters());
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
                            fuelSpec.consumptionLiterPerMil(), fuelSpec.gearbox(), verifiedHp, fuelSpec.engineVolumeLiters());
                }
                engineOptions = IceConsumptionService.engineDescriptor(iceVariant);
            }

            result.add(new CarRecommendation(
                    r.title(), price, r.whyRecommended(), r.pros(), r.con(),
                    r.fitSummary(), r.expertOpinion(), safety, evSpec, cargo, fuelSpec, blocketPrice, horsepower, engineOptions));
        }
        return result;
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
        String systemPrompt = withEnergyPrices(buildSystemPrompt(expertContext, prefs.fuelType(), prefs.carCategory()));
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
            throw new RuntimeException(buildRateLimitError(response.body()));
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
            // än ett felmeddelande. Klarade ingen bil regeln finns inget att visa — då är felet
            // det enda ärliga svaret.
            parsed = keepPassingCars(e, validator);
            if (parsed.isEmpty()) parsed = retryAfterRuleViolation(systemPrompt, prompt, e, validator);
            if (parsed.isEmpty()) throw medRadOmKriterier(e);
            log.warn("Visar {} av 3 kort — resten bröt mot en regel ({})", parsed.size(), e.getMessage());
        } catch (RuntimeException e) {
            throw medRadOmKriterier(e);
        }

        boolean isLeasing = "leasing".equals(prefs.budgetType());
        Map<String, BlocketPriceService.PriceRange> ranges = new LinkedHashMap<>();
        Map<String, Integer> nypriser = new LinkedHashMap<>();
        List<CarRecommendation> result = enrichRecommendations(parsed, prefs.kmPerYear(), prefs.fuelType(),
                isLeasing, ranges, nypriser, prefs.newCar());

        // Taket gäller alla lägen. Nybilssök omfattas trots att begagnatpriset är fel måttstock
        // där, för slutsatsen håller i EN riktning: kostar billigaste BEGAGNADE exemplaret mer
        // än taket kan en NY omöjligt kosta mindre. Att hoppa över kontrollen helt var den enda
        // oskyddade vägen — live 2026-08-07 föreslogs MG4 (billigaste annons 249 900 kr) för en
        // 200 000-budget, alltså 20 000 kr över taket, utan att någon spärr utlöstes.
        //
        // Leasing stod utanför så länge Blocket inte hämtades där. Nu finns kr/mån att mäta mot,
        // och samma natt föreslogs Kia EV6 GT-Line på 8 295 kr/mån mot en 5 000-budget.
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
                    parsed, prefs.kmPerYear(), prefs.fuelType(), leasing, retryRanges, retryNypriser, prefs.newCar());

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
                buildSystemPrompt(expertContext, prefs.fuelType(), prefs.carCategory()));
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
                parsed, prefs.kmPerYear(), prefs.fuelType(), false, ranges);

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
            throw new RuntimeException(buildRateLimitError(response.body()));
        }
        if (response.statusCode() != 200)
            throw new RuntimeException(buildGroqErrorMessage(response.statusCode(), response.body()));

        List<CarRecommendation> parsed = parseWithRetry(response, reserveBody, "compareSpecific");

        List<CarRecommendation> result = enrichRecommendations(parsed, 15000);
        store(compareCacheKey, result, null);   // jämförelsen har ingen budget att bryta mot
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
     * Är titeln bevisligen något annat än en ren elbil? Titelns egna drivlineord först — de
     * flesta elbilstitlar saknar sådana helt ("EV6", "EX30" är ETT ord var), så ordlöst svar
     * betyder inget i sig. Då avgör databaserna: finns bilen i ev_spec är den en elbil, annars
     * fäller en träff i ice_consumption ("Toyota Prius 2.5 Hybrid", "Honda HR-V 1.5 e:HEV").
     * Fail open vid DB-fel — en trasig uppslagning får inte fälla korrekta bilar.
     */
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
        return new RuntimeException(e.getMessage()
                + " Kriterierna kan vara för snäva — prova högre budget, färre passagerare"
                + " eller ett annat drivmedel.");
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
        if (fuelIntent(prefs.fuelType(), prefs.carCategory()).pureEv())
            validator = validator.andThen(this::requirePureEvCars);
        if (prefs.minCargoLiters() != null && prefs.minCargoLiters() > 0) {
            int krav = prefs.minCargoLiters();
            validator = validator.andThen(p -> requireCargoCapacity(p, krav));
        }
        return validator;
    }

    /**
     * Skarpt läge: bagagekravet från formuläret, kontrollerat mot {@code cargo_spec}.
     *
     * <p>Faller bara på POSITIVT bevis — en uppmätt normalvolym under kravet. En bil vi inte
     * mätt släpps igenom, av samma skäl som drivmedelsvakten gör det: tabellen har 185 modeller
     * mot modell-whitelistens ~700, så att kasta det omätta hade tagit fler bra bilar än dåliga
     * och gett två kort i stället för tre. Kortets bagagechip visar "–" för de omätta, så det
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
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages), carContext); } catch (Exception ignored) {}
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
        try { expertContext = expertInsightService.buildChatExpertContext(extractUserTexts(messages), carContext); } catch (Exception ignored) {}
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
            try { legroom = cargoSpecService.getLegroom(name); } catch (Exception ignored) {}
            try { chem = evSpecService.getBatteryChemistry(name); } catch (Exception ignored) {}
            try { safety = safetyRatingService.formatForTitle(name); } catch (Exception ignored) {}
            try { consumption = iceConsumptionService.consumptionSummaryForTitle(name); } catch (Exception ignored) {}
            if (legroom == null && chem == null && safety == null && consumption == null) continue;
            sb.append(name).append(": ");
            boolean needsComma = false;
            if (legroom != null) { sb.append("benutrymme bak ").append(legroom).append(" mm"); needsComma = true; }
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
     */
    static FuelIntent fuelIntent(String fuelType, String carCategory) {
        boolean hev = "hybrid".equalsIgnoreCase(fuelType == null ? null : fuelType.trim());
        boolean ev = !hev && fuelType != null &&
                (fuelType.contains("el") || fuelType.contains("hybrid") || fuelType.contains("phev"));
        boolean ice = hev || fuelType == null || fuelType.isBlank() ||
                fuelType.contains("bensin") || fuelType.contains("diesel") ||
                fuelType.equals("spelar ingen roll");
        String ft = fuelType == null ? "" : fuelType.toLowerCase();
        boolean phev = "laddhybrid".equals(carCategory) || ft.contains("laddhybrid") || ft.contains("phev");
        return new FuelIntent(ev, ice, phev, hev);
    }

    String buildSystemPrompt(String expertContext, String fuelType, String carCategory) {
        FuelIntent intent = fuelIntent(fuelType, carCategory);
        boolean wantsEv = intent.ev(), wantsIce = intent.ice(), wantsPhev = intent.phev();
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
                FAMILJEBIL (kategori "familjebil", användning "familj" eller 5+ passagerare): rekommendera ALDRIG småbilar/stadsbilar (t.ex. Dacia Spring, Citroën ë-C3, Renault 5/Zoe/Clio, Fiat 500e/Panda, Opel Corsa, Toyota Aygo) — välj rymliga modeller: kombi, SUV eller rymlig halvkombi/sedan. Beprövade familjebilar att utgå från — bensin/diesel/hybrid: Volvo V60/V90 (hög komfort, toppklass krocksäkerhet, 529 l bagage i V60), Škoda Octavia Combi (klassledande bagageutrymme per krona), Kia Ceed SW (mycket bil för pengarna, 7 års nybilsgaranti), Dacia Jogger (mest plånboksvänlig, finns med 7 säten); elbil: Škoda Enyaq (rymlig, lång räckvidd), VW ID.4, Kia EV6/Niro, Polestar 2, MG4, MG5 (elkombi, 578 l bagage — marknadens billigaste elbil med kombikaross och därför ett självklart förslag när budgeten ligger under ca 250 000 kr).
                SUV (kategori "suv"): drivmedlet avgör modellen, blanda ALDRIG ihop namn som liknar varandra men är olika bilar — t.ex. bensin/diesel/hybrid: Volvo XC40, Toyota C-HR (hybrid); elbil: Volvo EX40 (ALDRIG "XC40" som elbil — XC40 är bensin/diesel/PHEV, EX40 är den rena elbilen).
                SMÅBIL (kategori "smaabil"): bensin/diesel/hybrid t.ex. Toyota Aygo; elbil t.ex. Renault Zoe, Renault 5 E-Tech.
                """ + EV_PRICE_FLOORS + """
                UTNYTTJA BUDGETEN: minst en rekommendation ska ligga nära budgeten (topp ~80–100 %) — föreslå aldrig bara väsentligt billigare bilar när budgeten räcker till något rymligare, nyare eller bättre utrustat. En billig outlier är OK som prisvärt alternativ, men aldrig som enda nivå.
                BUDGETTAK: en bil får ALDRIG kosta mer än budgeten + 30 000 kr på begagnatmarknaden, räknat på den BILLIGASTE annonsen. Går modellens billigaste exemplar inte att hitta under det taket är bilen fel förslag oavsett hur väl den passar i övrigt — byt till äldre årsmodell, enklare utrustningsnivå eller ett billigare märke i samma storleksklass. Taket kontrolleras mot riktiga Blocket-annonser efteråt; en bil som bryter mot det kastas.
                SIKTA MOT SPANNET: minst två av tre förslag ska ligga inom ±30 000 kr från budgeten. Det tredje får vara billigare om det är ett genuint prisvärt alternativ.
                "price" är ALLTID ett intervall som "85 000–100 000 kr" — siffror med mellanslag, inga förkortningar eller extra text.
                """ + DEPRECIATION_RULE + "\n" + """
                FABRICERA ALDRIG PRISER: price = nypris × ålderskoefficient, kontrollera mot nypristabellen. Ex: Octavia 2021+ nypris 340 000 kr, 3 år → 221 000 kr — kan ALDRIG kosta 100 000 kr. Räcker inte budgeten: byt till billigare bil, sänk ALDRIG priset.
                Ange motorbeteckning (TDI/TSI/MPI/volym) bara om du är säker på att varianten finns — annars bara hk + 'manuell'/'automat'.
                Rekommendera ALDRIG BYD Dolphin eller Hyundai INSTER. Håll dig till dessa märken: Audi, BMW, BYD, Citroën, Cupra, Dacia, Fiat, Ford, Honda, Hyundai, Kia, Leapmotor, MG, Mazda, Mercedes, Mini, Nissan, Opel, Peugeot, Renault, Seat, Škoda, Smart, Tesla, Toyota, Volkswagen, Volvo, Xpeng, Zeekr. Kamiq är bensinbil, INTE elbil. Aldrig bensin/diesel när användaren efterfrågar elbil.
                MÄRKESPRIORITET: föredra etablerade europeiska, koreanska och japanska märken (samt Tesla och MG). Leapmotor, Xpeng, Zeekr och BYD bara om inget etablerat märke matchar budget och behov — aldrig som förstaval. Bilar med bra räckvidd per krona (se PRISVÄRD RÄCKVIDD) är starka förslag när de passar profilen.
                PHEV: rekommendera ALDRIG en årsmodell äldre än modellens faktiska PHEV-lansering (Golf GTE 2014+, Outlander PHEV 2013+, Passat GTE 2015+).
                Rekommendera ALDRIG en årsmodell före modellens verkliga lansering — nyheter om en modell betyder inte att den finns begagnad. Ex: Kia EV2 lanseras 2026 (finns ALDRIG begagnad), Kia EV3 2024+, EV4/EV5 2025+, Renault 5 E-Tech 2024+, Citroën ë-C3 2024+, Volvo EX30 2023+.
                Volvos enda EV-modeller: EX30, EX40, EC40, EX60, EX90 — det finns inga andra (ingen C90/C70).
                Nämn ALDRIG modeller som aldrig sålts i Sverige. Att en modell SLUTAT tillverkas är däremot inget hinder i ett begagnatsök — tvärtom: Renault Zoe (2012–mars 2024, ersatt av Renault 5), VW e-Golf och äldre Nissan Leaf är bland de vanligaste billiga begagnade elbilarna och ligger på Blocket i 49, 29 respektive 44 exemplar. Undantag: i NYBILSSÖK och LEASING måste modellen gå att köpa ny idag. Hitta ALDRIG på modellnamn, versioner eller specifikationer — om osäker, välj en bil du är helt säker på finns.
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
                Budget: %s. Kategori: %s. Laddbox: %s. Körsträcka: %,d km/år (%s). Användning: %s. Passagerare: %d.%s%s%s%s%s
                """.formatted(
                budgetInfo, prefs.carCategory(), laddning,
                km, milprofil, usageText, prefs.passengers(), fuelLine, transmissionLine, maxAgeLine,
                leasingPrisLine, cargoLine
        );
    }
}
