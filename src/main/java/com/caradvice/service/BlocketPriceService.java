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
     * <p>Årsmodellen filtreras på {@code docs[].year} eftersom API:ts egna årsparametrar inte
     * fungerar. Utan den filtreringen fick varje årsmodell av samma bil identiskt prisspann —
     * en Leaf 2018 och en Leaf 2022 visades båda 129 900–198 900 kr.
     *
     * @return null när ingen annons matchar bilen och årsmodellen — anroparen får då avgöra
     *         på annan grund (se GroqService.verifiedFloor)
     */
    public PriceRange fetchPriceRange(String carTitle) {
        String query = extractSearchQuery(carTitle);
        if (query == null || query.isBlank()) return null;
        Integer year = extractYear(carTitle);

        String cacheKey = year != null ? query + "|" + year : query;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS)
            return cached.result();

        try {
            String encodedQ = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // year_min/year_max satt här tidigare — Blockets API IGNORERAR dem (verifierat
            // 2026-08-07: identiskt svar med och utan, liksom med year=, modelYear_min= och
            // filter=[{"key":"year"}]). Årsmodellen filtreras därför på docs[].year nedan.
            String url = SEARCH_URL + "?q=" + encodedQ + "&page=0&lim=" + FETCH_LIMIT;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonNode docs = mapper.readTree(response.body()).path("docs");
            if (!docs.isArray() || docs.isEmpty()) return null;

            PriceRange result = priceRangeFrom(docs, year);
            if (result == null) return null;
            cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            return result;

        } catch (Exception e) {
            return null;
        }
    }

    /** Träfflistan → prisspann. Egen metod för att gå att testa utan HTTP. */
    PriceRange priceRangeFrom(JsonNode docs, Integer year) {
        List<Integer> prices = new ArrayList<>();
        for (JsonNode doc : docs) {
            int amount = doc.path("price").path("amount").asInt(0);
            // Privatleasingannonser ligger i samma träfflista med månadsavgiften i
            // price.amount (3 795–4 495 kr) och samma price_unit "kr" — inget fält skiljer
            // dem åt, men ingen begagnad bil kostar under 10 000 kr att köpa.
            if (amount <= 10_000) continue;
            if (year != null) {
                int adYear = doc.path("year").asInt(0);
                if (adYear < year - 1 || adYear > year + 1) continue;
            }
            prices.add(amount);
        }
        if (prices.isEmpty()) return null;

        Collections.sort(prices);
        int n = prices.size();
        int median = prices.get(n / 2);
        int min = prices.get(0), max = prices.get(n - 1);
        for (int p : prices) { if (p >= 0.4 * median) { min = p; break; } }
        for (int p : prices) { if (p <= 2.5 * median) max = p; }
        String formatted = sekFmt.format(min) + " – " + sekFmt.format(max)
                + " kr (" + n + " annonser)";
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
