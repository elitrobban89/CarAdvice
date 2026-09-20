package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Officiella privatleasingpriser från märkenas egna sidor.
 *
 * <p>Blockets leasingannonser är handlarnas erbjudanden — riktiga, men blandade i bindningstid,
 * årsmodell och kampanjvillkor. Märkets eget listpris är den siffra kunden faktiskt möter på
 * märkets sajt, och den fanns inte i appen alls: leasingkortet räknade fram listpris/85 och gav
 * en Škoda Enyaq "~2 000 kr/mån" när det officiella priset är 5 295 kr/mån.
 *
 * <p>Källan är VW Financial Services plattform bakom privatleasing.skoda.se. Samma API betjänar
 * hela koncernen genom {@code Channel}-headern — utan den svarar det 500. Märken utanför
 * VW-gruppen (Toyota, Kia, Volvo, Tesla …) har egna sajter med egna strukturer och täcks inte;
 * där får Blocket-annonserna fortsätta gälla.
 */
@Service
public class LeasingPriceService {

    private static final Logger log = LoggerFactory.getLogger(LeasingPriceService.class);
    private static final String API_URL = "https://nosp-api.vwfs-se-nosp.vwfs.io/api/category?nocache=";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    /** Priserna rör sig i kampanjcykler, inte per timme. */
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1_000L;
    private static final long FAILURE_TTL_MS = 30 * 60 * 1_000L;

    private static final String VOLVO_URL = "https://www.volvocars.com/se/car-finance/operating-lease/";
    /** {@code prices[ex30-electric].contract_hire … car-price">3 895 kr/mån} → EX30, 3895. */
    private static final Pattern VOLVO_PRICE = Pattern.compile(
            "prices\\[([a-z0-9]+)[a-z0-9\\-]*\\]\\.contract_hire.{0,400}?car-price\">([\\d\\s\\u00a0]+)\\s*kr\\s*/\\s*m[åa]n",
            Pattern.DOTALL);

    /** Märke → Channel-header. "volkswagen" svarar 500; rätt värde är "vw". */
    private static final Map<String, String> CHANNELS = Map.of(
            "skoda", "skoda", "škoda", "skoda",
            "volkswagen", "vw", "vw", "vw",
            "audi", "audi",
            "cupra", "cupra",
            "seat", "seat");

    /** En modell i märkets utbud, med sitt "från"-pris. */
    public record LeasingOffer(String model, int monthlyKr, String brand) {}

    private record CacheEntry(List<LeasingOffer> offers, long timestamp, boolean failed) {
        CacheEntry(List<LeasingOffer> offers, long timestamp) { this(offers, timestamp, false); }

        /** Misslyckade hämtningar cachas kort: annars görs ett nytt anrop per bil i varje sökning. */
        boolean fresh() {
            long age = System.currentTimeMillis() - timestamp;
            return failed ? age < FAILURE_TTL_MS : age < CACHE_TTL_MS;
        }
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Officiellt privatleasingpris för en AI-titel, eller null när märket inte täcks eller
     * modellen inte finns i märkets aktuella utbud — vilket i sig är ett svar: en modell som
     * inte går att privatleasa ska inte visas med ett leasingpris.
     */
    public Integer monthlyForTitle(String carTitle) {
        LeasingOffer offer = offerForTitle(carTitle);
        return offer != null ? offer.monthlyKr() : null;
    }

    public LeasingOffer offerForTitle(String carTitle) {
        if (carTitle == null || carTitle.isBlank()) return null;
        List<String> words = words(carTitle);
        if (words.size() < 2) return null;

        String marke = words.get(0);
        String channel = CHANNELS.get(marke);
        List<LeasingOffer> utbud = "volvo".equals(marke) ? volvoOffers()
                : channel != null ? offers(channel) : List.of();
        if (utbud.isEmpty()) return null;
        return bestMatch(utbud, marke, words.subList(1, words.size()));
    }

    /** Egen metod för att gå att testa utan HTTP. */
    static LeasingOffer bestMatch(List<LeasingOffer> utbud, String marke, List<String> modelWords) {
        // DRIVLINAN MÅSTE STÄMMA, annars prissätts bilen som en annan bil.
        //
        // Skarpt i drift 2026-09-20: kortet "Cupra Leon Sportstourer e-Hybrid" fick
        // **3 595 kr/mån** — priset på "CUPRA Leon Sportstourer eTSI 150hk DSG", alltså
        // MILDHYBRIDEN. Laddhybriden kostar 4 395. Ordmatchningen är drivlineblind: titelns
        // [leon, sportstourer, e-hybrid] har den generiska "Leon Sportstourer" som inledning,
        // och bland lika specifika träffar vinner det LÄGSTA priset — som är fel bils.
        //
        // Ett felaktigt pris är värre än inget pris: AI:ns gissning är åtminstone märkt som en
        // uppskattning, medan katalogpriset skrivs ut som märkets eget. Vi matchar därför bara
        // inom samma drivlina, och en laddhybrid utan laddhybridserbjudande får hellre stå utan.
        boolean titelnArPhev = titelnBarLaddhybridsbadge(modelWords);
        LeasingOffer bast = null;
        int bastaLangd = 0;
        for (LeasingOffer offer : utbud) {
            List<String> offerWords = utanMarke(words(offer.model()), marke);
            if (LEASING_PHEV.matcher(offer.model()).find() != titelnArPhev) continue;
            if (!matches(modelWords, offerWords)) continue;
            // Mest specifika modellen vinner: "Enyaq Coupé" ska inte få "Enyaq":s pris bara för
            // att det är lägre. Bland lika specifika är "från"-priset det lägsta, som märket
            // själv anger det.
            int langd = Math.min(offerWords.size(), modelWords.size());
            if (bast == null || langd > bastaLangd
                    || (langd == bastaLangd && offer.monthlyKr() < bast.monthlyKr())) {
                bast = offer;
                bastaLangd = langd;
            }
        }
        if (bast != null || !titelnArPhev || modelWords.isEmpty()) return bast;

        // LADDHYBRIDENS FALLBACK: samma modellfamilj, billigaste laddhybridserbjudandet.
        //
        // Katalogen namnger laddhybriden med en HEL trimrad — "Superb Combi Selection Explore
        // Edition iV" — medan AI:n skriver "Superb iV Combi". Ordmatchningen kräver att den ena
        // är en INLEDNING av den andra, och det är de inte: orden kommer i olika ordning och
        // trimnivån ligger emellan. Bada kort stod därför utan katalogpris i drift 2026-09-20,
        // trots att erbjudandet fanns.
        //
        // Familjen (första modellordet) plus drivlinan räcker för ett FRÅN-pris, som är vad
        // katalogen ger: "billigaste laddhybrid-Superb". <b>Medveten gräns:</b> delar två
        // karosser familjenamn — Leon och Leon Sportstourer — får hatchbacken kombins från-pris
        // när bara den ena finns som laddhybrid. Det är samma modell och samma drivlina, och
        // alternativet är AI:ns gissning.
        String familj = modelWords.get(0);
        for (LeasingOffer offer : utbud) {
            if (!LEASING_PHEV.matcher(offer.model()).find()) continue;
            List<String> offerWords = utanMarke(words(offer.model()), marke);
            if (offerWords.isEmpty() || !offerWords.get(0).equals(familj)) continue;
            if (bast == null || offer.monthlyKr() < bast.monthlyKr()) bast = offer;
        }
        return bast;
    }

    /**
     * Säger TITELN att bilen är laddhybrid? Nästan {@link #LEASING_PHEV}, men inte riktigt.
     *
     * <p><b>{@code iV} betyder olika saker på de två sidorna.</b> I KATALOGEN är badgen entydig:
     * Škodas nuvarande elbilar heter Enyaq, Elroq och Epiq utan suffix, så {@code iV} där alltid
     * är en laddhybrid (mätt: 16 av 117 erbjudanden, noll falska). I en TITEL är den det inte —
     * Škoda kallade elbilen {@code Enyaq iV} i flera år, och de annonserna lever kvar. Med
     * {@code iV} som laddhybridsbevis blev {@code "Škoda Enyaq iV 80"} plötsligt en laddhybrid
     * och tappade sitt leasingpris helt (fångat av det befintliga provet, inte i drift).
     *
     * <p>Undantaget är därför skrivet som en EXCEPTION-lista över Škodas iV-döpta ELBILAR, inte
     * som en lista över iV-laddhybriderna: nästa laddhybrid får rätt svar automatiskt, och en ny
     * elbil med iV i namnet kostar bara ett UTEBLIVET pris — aldrig ett felaktigt, eftersom en
     * laddhybridstitel utan laddhybridserbjudande ger {@code null}. Det är åt rätt håll.
     */
    private static final Pattern SKODA_IV_ELBILAR =
            Pattern.compile("\\b(enyaq|elroq|epiq)\\b", Pattern.CASE_INSENSITIVE);

    static boolean titelnBarLaddhybridsbadge(List<String> modelWords) {
        String text = String.join(" ", modelWords);
        if (!LEASING_PHEV.matcher(text).find()) return false;
        boolean baraIv = !Pattern.compile(
                "e-?hybrid|\\btfsi\\s+e\\b|\\bgte\\b|\\bphev\\b|plug-?in|\\brecharge\\b|\\bt8\\b",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
        return !(baraIv && SKODA_IV_ELBILAR.matcher(text).find());
    }

    /** Cupra listar sina modeller som "CUPRA Born" medan titeln redan bär märket. */
    private static List<String> utanMarke(List<String> offerWords, String marke) {
        return offerWords.size() > 1 && offerWords.get(0).equals(marke)
                ? offerWords.subList(1, offerWords.size()) : offerWords;
    }

    /**
     * Titelns modellord mot utbudets: den enas ord ska vara en inledning av den andras.
     * "Škoda Enyaq iV 80 (2023)" matchar "Enyaq" men inte "Enyaq Coupé" — samma regel som
     * dedupen i GroqService, och av samma skäl: utrustningsnivåer får inte bli egna modeller,
     * men syskonmodeller får inte slås ihop.
     */
    private static boolean matches(List<String> titleWords, List<String> offerWords) {
        if (titleWords.isEmpty() || offerWords.isEmpty()) return false;
        List<String> kort = titleWords.size() <= offerWords.size() ? titleWords : offerWords;
        List<String> lang = titleWords.size() <= offerWords.size() ? offerWords : titleWords;
        return lang.subList(0, kort.size()).equals(kort);
    }

    private List<LeasingOffer> offers(String channel) {
        CacheEntry cached = cache.get(channel);
        if (cached != null && cached.fresh()) return cached.offers();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + System.currentTimeMillis()))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Channel", channel)     // utan denna svarar API:t 500
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Leasingpriser [{}]: HTTP {}", channel, response.statusCode());
                return misslyckades(channel, cached);
            }
            List<LeasingOffer> offers = parse(mapper.readTree(response.body()), channel);
            cache.put(channel, new CacheEntry(offers, System.currentTimeMillis()));
            log.info("Leasingpriser [{}]: {} modeller hämtade", channel, offers.size());
            return offers;
        } catch (Exception e) {
            log.warn("Leasingpriser [{}] misslyckades: {}", channel, e.getMessage());
            return misslyckades(channel, cached);
        }
    }

    /** Behåll gammal data om den finns, annars cacha misslyckandet kort. */
    private List<LeasingOffer> misslyckades(String nyckel, CacheEntry cached) {
        if (cached != null && !cached.offers().isEmpty()) return cached.offers();
        cache.put(nyckel, new CacheEntry(List.of(), System.currentTimeMillis(), true));
        return List.of();
    }

    /**
     * Volvo ligger utanför VW-plattformen och har ingen JSON — priserna står i sidans markup,
     * kopplade till modellen genom {@code prices[<modell>-<drivlina>].contract_hire}.
     *
     * <p>Bara {@code contract_hire} plockas: samma sida visar även "Care by Volvo", som är ett
     * abonnemang med försäkring och service inbakat och alltså ett annat pris för en annan sak.
     *
     * <p>Sidan kräver en fullständig webbläsaruppsättning headers — med bara User-Agent svarar
     * den 403.
     */
    private List<LeasingOffer> volvoOffers() {
        CacheEntry cached = cache.get("volvo");
        if (cached != null && cached.fresh()) return cached.offers();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOLVO_URL))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.8")
                    .header("sec-ch-ua", "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Upgrade-Insecure-Requests", "1")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Leasingpriser [volvo]: HTTP {} — botskyddet svarade, faller tillbaka på Blocket",
                        response.statusCode());
                return misslyckades("volvo", cached);
            }
            List<LeasingOffer> offers = parseVolvo(response.body());
            cache.put("volvo", new CacheEntry(offers, System.currentTimeMillis()));
            log.info("Leasingpriser [volvo]: {} modeller hämtade", offers.size());
            return offers;
        } catch (Exception e) {
            log.warn("Leasingpriser [volvo] misslyckades: {}", e.getMessage());
            return misslyckades("volvo", cached);
        }
    }

    /** Egen metod för att gå att testa utan HTTP. */
    List<LeasingOffer> parseVolvo(String html) {
        List<LeasingOffer> offers = new ArrayList<>();
        if (html == null) return offers;
        Matcher m = VOLVO_PRICE.matcher(html);
        while (m.find()) {
            Integer monthly = monthlyFrom(m.group(2) + " kr/mån");
            if (monthly != null) offers.add(new LeasingOffer(m.group(1).toUpperCase(Locale.ROOT), monthly, "volvo"));
        }
        return offers;
    }

    /** Kategorierna och deras varianter → modell + månadspris. Egen metod för att gå att testa utan HTTP. */
    List<LeasingOffer> parse(JsonNode categories, String brand) {
        List<LeasingOffer> offers = new ArrayList<>();
        if (categories == null || !categories.isArray()) return offers;
        for (JsonNode category : categories) {
            addOffer(offers, category, brand);
            for (JsonNode sub : category.path("subCategories")) addOffer(offers, sub, brand);
        }
        return offers;
    }

    private void addOffer(List<LeasingOffer> offers, JsonNode node, String brand) {
        String model = cleanModel(node.path("title").asText(""));
        Integer monthly = monthlyFrom(node.path("preamble").asText(""));
        if (!model.isEmpty() && monthly != null) offers.add(new LeasingOffer(model, monthly, brand));
    }

    /** "Privatleasing från 5 295 kr/mån" → 5295. */
    static Integer monthlyFrom(String preamble) {
        if (preamble == null) return null;
        Matcher m = Pattern.compile("(\\d[\\d\\s\\u00a0]*)\\s*kr\\s*/\\s*m[åa]n").matcher(preamble);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1).replaceAll("[\\s\\u00a0]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "Nya Epiq" → "Epiq". Märket marknadsför nyheter med prefix som inte hör till modellnamnet. */
    static String cleanModel(String title) {
        return title == null ? "" : title.replaceAll("(?i)^\\s*nya\\s+", "").trim();
    }

    private static List<String> words(String s) {
        String rensad = CarTitle.stripYear(s).toLowerCase(new Locale("sv", "SE"))
                .replaceAll("[*/]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return rensad.isEmpty() ? List.of() : new ArrayList<>(List.of(rensad.split(" ")));
    }

    /**
     * Laddhybridsmarkörer i märkenas EGNA erbjudandenamn — en annan värld än insikternas.
     *
     * <p><b>Varför en egen lista och inte {@code ExpertInsightService.PHEV_MARKER} eller
     * {@code GroqService.PHEV_TRIMKOD}:</b> de två läser löptext och annonsrubriker, det här
     * läser märkenas erbjudandekataloger, där drivlinan är en BADGE och inte ett ord. Mätt mot
     * de 117 erbjudanden de fem VWFS-kanalerna svarade med 2026-09-20 fångar ingen av de två
     * befintliga listorna Škodas {@code iV}, VW:s {@code eHybrid} eller Audis {@code 40 TFSI e}.
     *
     * <p><b>Mätningen:</b> 16 av 117 erbjudanden är laddhybrider, och kontrollistan — varje namn
     * som INTE träffade men bär {@code hybrid}, {@code iv} eller ett ensamt {@code e} — innehöll
     * noll laddhybrider. Audis elbilar ({@code A2/Q4/A6/Q6 e-tron}) och {@code A3 35 TFSI} faller
     * alltså korrekt utanför.
     *
     * <p><b>{@code iv} är Škodas badge och bärs inte av deras nuvarande elbilar</b> (Enyaq, Elroq,
     * Epiq står utan suffix). Historiskt hette elbilen {@code Enyaq iV}, så dyker ett sådant namn
     * upp igen i katalogen blir det en falsk träff — kontrollera listan om Škoda byter tillbaka.
     */
    private static final Pattern LEASING_PHEV = Pattern.compile(
            "\\biv\\b|e-?hybrid|\\btfsi\\s+e\\b|\\bgte\\b|\\bphev\\b|plug-?in|\\brecharge\\b|\\bt8\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Laddhybriderna som faktiskt GÅR att privatleasa just nu, billigast först — en per modell.
     *
     * <p><b>Felet den lagar (rapporterat 2026-09-20).</b> Ett laddhybridssök i leasingläge
     * (5 000 kr/mån) gav <i>Volvo V60 Recharge (2026)</i>, <i>Škoda Octavia iV (2026)</i> och
     * <i>Audi A3 Sportback e-tron (2027)</i> med prisintervallen "4 800–5 200", "4 600–5 000" och
     * "4 900–5 300 kr/mån". Alla tre talen var AI:ns egna, och <b>Octavia iV finns inte i Škodas
     * privatleasingutbud</b> (bara Octavia Combi Selection/Sportline, utan laddhybrid) medan
     * <i>A3 Sportback e-tron</i> är namnet på 2014–2018 års modell — dagens heter
     * {@code A3 40 TFSI e} eller {@code A3 Sportback e-hybrid} och kostar 4 995 kr/mån.
     * Prompten namngav inga leasingbara laddhybrider alls, så modellen fyllde luckan själv.
     *
     * <p><b>Live, inte hårdkodat — och det är skillnaden mot begagnatgolven.</b>
     * {@code GroqService.PHEV_PRICE_FLOOR_KR} måste mätas för hand eftersom ingen publicerar
     * begagnatgolv; leasingutbudet publicerar märkena själva och det roterar i kampanjcykler.
     * En hårdkodad tabell hade åldrats precis som Octavia iV gjorde. Cachen är 12 h, så en
     * sökning kostar normalt inget nätverk.
     *
     * <p><b>En rad per modell, den billigaste.</b> Škodas Superb iV ligger som tre trimnivåer
     * (4 995 / 5 165 / 5 350) och tre rader om samma bil säger inte mer än en. Namnet behålls
     * ORDAGRANT från katalogen — det är det namn som går att teckna.
     */
    public List<LeasingOffer> phevOffers() {
        List<LeasingOffer> alla = new ArrayList<>(volvoOffers());
        for (String channel : new java.util.TreeSet<>(CHANNELS.values())) alla.addAll(offers(channel));
        return phevUrUtbud(alla);
    }

    /** Sållningen och hopslagningen, utan HTTP — samma skäl som {@link #parse} och {@link #bestMatch}. */
    static List<LeasingOffer> phevUrUtbud(List<LeasingOffer> alla) {
        Map<String, LeasingOffer> billigastPerModell = new java.util.LinkedHashMap<>();
        for (LeasingOffer offer : alla) {
            if (!LEASING_PHEV.matcher(offer.model()).find()) continue;
            List<String> ord = utanMarke(words(offer.model()), offer.brand());
            if (ord.isEmpty()) continue;
            String nyckel = offer.brand() + "|" + ord.get(0);
            LeasingOffer nuvarande = billigastPerModell.get(nyckel);
            if (nuvarande == null || offer.monthlyKr() < nuvarande.monthlyKr())
                billigastPerModell.put(nyckel, offer);
        }
        return billigastPerModell.values().stream()
                .sorted(java.util.Comparator.comparingInt(LeasingOffer::monthlyKr)
                        .thenComparing(LeasingOffer::model))
                .toList();
    }

    /** Alla märken vi täcker, för admin/diagnostik. */
    public Map<String, Integer> coverage() {
        Map<String, Integer> out = new HashMap<>();
        for (String channel : CHANNELS.values()) out.put(channel, offers(channel).size());
        out.put("volvo", volvoOffers().size());
        return out;
    }
}
