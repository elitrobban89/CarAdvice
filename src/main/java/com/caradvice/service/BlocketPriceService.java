package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BlocketPriceService {

    private static final String SEARCH_URL =
            "https://www.blocket.se/mobility/search/api/search/SEARCH_ID_CAR_USED";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final long CACHE_TTL_MS = 30 * 60 * 1_000L;
    private static final int FETCH_LIMIT = 60;
    /** Blocket returnerar aldrig fler än 50 träffar, oavsett {@code lim} — och {@code page} ignoreras. */
    private static final int PAGE_CAP = 50;

    /** Försäljningsform i Blockets API: 1 = begagnad till salu, 2 = ny bil till salu, 5 = leasing. */
    private static final String SALES_FORM_KOP = "&sales_form=1&sales_form=2";
    private static final String SALES_FORM_LEASING = "&sales_form=5";

    /** Under detta är beloppet en månadsavgift, inte ett köppris. */
    private static final int LOWEST_PLAUSIBLE_CAR_PRICE_KR = 10_000;
    /** Över detta är beloppet ett köppris, inte en månadsavgift. */
    private static final int HIGHEST_PLAUSIBLE_MONTHLY_KR = 20_000;

    public record PriceRange(int minKr, int maxKr, int count, String formatted) {}

    private record CacheEntry(PriceRange result, long timestamp) {}

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final NumberFormat sekFmt = NumberFormat.getNumberInstance(new Locale("sv", "SE"));

    /**
     * Billigaste och dyraste annonsen för bilen, årsmodellsfiltrerad ±1 år.
     *
     * <p>Undre gränsen var tidigare 20:e percentilen, vilket kastade bort en femtedel av den
     * verkliga marknadens billigaste annonser: Kia EV3 visades från 434 900 kr när billigaste
     * annonsen låg på 369 900 kr, Dacia Sandero från 105 000 kr när marknaden börjar på
     * 42 900 kr. Siffran är inte kosmetisk — budgettaket mäts mot den, så bilar föll som "för
     * dyra" trots att de gick att köpa. Nu tas i stället bara uppenbara avvikare bort:
     * medianrelativa gränser (0,4× och 2,5×) fångar fluff- och scamannonser utan att stryka
     * ett helt prisspann.
     *
     * <p>Årsmodellen sätts med {@code year_from}/{@code year_to}. Det hette tidigare
     * {@code year_min}/{@code year_max}, som API:t tyst ignorerade — varje årsmodell av samma
     * bil fick då identiskt prisspann (en Leaf 2018 och en Leaf 2022 visades båda
     * 129 900–198 900 kr). Filtreringen görs dessutom om på {@code docs[].year} här, eftersom
     * parametrarna redan en gång slutat fungera utan att något gick sönder synligt.
     *
     * <p>Sorterat på pris stigande, inte relevans: träfflistan kapas vid 50 annonser och
     * {@code page} ignoreras, så relevansordningen gömde de billigaste bilarna bakom taket.
     * Kia EV3 började på 359 000 kr — vi visade 369 900 kr, och före det 434 900 kr. Är listan
     * full hämtas även den dyraste änden, annars vore maxpriset den 50:e billigaste bilen.
     *
     * @return null när ingen annons matchar bilen och årsmodellen — anroparen får då avgöra
     *         på annan grund (se GroqService.verifiedFloor)
     */
    public PriceRange fetchPriceRange(String carTitle) {
        return fetchRange(carTitle, false);
    }

    /**
     * Samma sökning, men privatleasing — där är beloppet kr/mån och ett helt annat prisläge.
     * Leasingannonser ligger annars mitt i köpträfflistan med månadsavgiften i samma
     * {@code price.amount} och samma {@code price_unit} "kr": 4 495 kr bredvid 479 000 kr.
     */
    public PriceRange fetchLeasingRange(String carTitle) {
        return fetchRange(carTitle, true);
    }

    private PriceRange fetchRange(String carTitle, boolean leasing) {
        String query = extractSearchQuery(carTitle);
        if (query == null || query.isBlank()) return null;
        // Privatleasing gäller nya bilar — annonserna ligger på årsmodell 2026-2027 medan AI:n
        // sätter ett årtal som hör begagnatmarknaden till. Årsfiltret hade tömt träfflistan.
        Integer year = leasing ? null : extractYear(carTitle);

        String cacheKey = (leasing ? "leasing|" : "") + (year != null ? query + "|" + year : query);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS)
            return cached.result();

        try {
            JsonNode billigast = fetchDocs(query, year, leasing, "PRICE_ASC");
            if (billigast == null || billigast.isEmpty()) return null;

            List<Integer> prices = pricesFrom(billigast, year, leasing);
            // Full lista = vi såg bara den billigaste änden; dyraste priset finns bortom taket
            boolean kapad = billigast.size() >= PAGE_CAP;
            if (kapad) {
                JsonNode dyrast = fetchDocs(query, year, leasing, "PRICE_DESC");
                if (dyrast != null) prices.addAll(pricesFrom(dyrast, year, leasing));
            }

            PriceRange result = rangeOf(prices, leasing, kapad);
            if (result == null) return null;
            cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            return result;

        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode fetchDocs(String query, Integer year, boolean leasing, String sort) throws Exception {
        String url = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&page=0&lim=" + FETCH_LIMIT + "&sort=" + sort
                + (leasing ? SALES_FORM_LEASING : SALES_FORM_KOP);
        if (year != null) url += "&year_from=" + (year - 1) + "&year_to=" + (year + 1);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonNode docs = mapper.readTree(response.body()).path("docs");
        return docs.isArray() ? docs : null;
    }

    /** Träfflistan → prisspann. Egen metod för att gå att testa utan HTTP. */
    PriceRange priceRangeFrom(JsonNode docs, Integer year) {
        return rangeOf(pricesFrom(docs, year, false), false, false);
    }

    /** Som ovan, för privatleasingens kr/mån. */
    PriceRange leasingRangeFrom(JsonNode docs, Integer year) {
        return rangeOf(pricesFrom(docs, year, true), true, false);
    }

    /**
     * Beloppen ur träfflistan, med fel prisläge bortsorterat. {@code sales_form} räcker inte:
     * mätt 2026-08-07 låg leasingannonser på 4 495 kr märkta som "begagnad till salu", så
     * beloppets storlek är den enda tillförlitliga skiljelinjen mellan kr och kr/mån.
     */
    private List<Integer> pricesFrom(JsonNode docs, Integer year, boolean leasing) {
        List<Integer> prices = new ArrayList<>();
        for (JsonNode doc : docs) {
            int amount = doc.path("price").path("amount").asInt(0);
            if (leasing ? (amount <= 0 || amount > HIGHEST_PLAUSIBLE_MONTHLY_KR)
                        : amount <= LOWEST_PLAUSIBLE_CAR_PRICE_KR) continue;
            if (year != null) {
                int adYear = doc.path("year").asInt(0);
                if (adYear < year - 1 || adYear > year + 1) continue;
            }
            prices.add(amount);
        }
        return prices;
    }

    private PriceRange rangeOf(List<Integer> prices, boolean leasing, boolean kapad) {
        if (prices.isEmpty()) return null;
        Collections.sort(prices);
        int n = prices.size();
        int median = prices.get(n / 2);
        int min = prices.get(0), max = prices.get(n - 1);
        for (int p : prices) { if (p >= 0.4 * median) { min = p; break; } }
        for (int p : prices) { if (p <= 2.5 * median) max = p; }
        String antal = kapad ? "50+ annonser" : n + " annonser";
        String formatted = sekFmt.format(min) + " – " + sekFmt.format(max)
                + (leasing ? " kr/mån (" : " kr (") + antal + ")";
        return new PriceRange(min, max, n, formatted);
    }

    private Integer extractYear(String title) {
        if (title == null) return null;
        Matcher m = Pattern.compile("\\((\\d{4})\\+?\\)\\s*$").matcher(title.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private String extractSearchQuery(String title) {
        if (title == null) return null;
        String s = title.replaceAll("\\s*\\(\\d{4}\\+?\\)\\s*$", "").trim();
        // Strip engine displacement: "1.0 TSI", "1.5 T-GDI", "2.0 TDI", etc.
        s = s.replaceAll("\\s+\\d+[.,]\\d+.*$", "").trim();
        // Strip battery capacity: "26 kWh", "51 kWh", etc.
        s = s.replaceAll("(?i)\\s+\\d+\\s*kwh.*$", "").trim();
        // Strip EV range variants
        s = s.replaceAll("(?i)\\s+(Long Range|Short Range|Extended Range|Standard Range|Single Motor|Dual Motor|Grande Autonomie).*$", "").trim();
        return s;
    }
}
