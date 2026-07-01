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

@Service
public class BlocketPriceService {

    private static final String SEARCH_URL =
            "https://www.blocket.se/mobility/search/api/search/SEARCH_ID_CAR_USED";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final long CACHE_TTL_MS = 30 * 60 * 1_000L;
    private static final int FETCH_LIMIT = 20;

    public record PriceRange(int minKr, int maxKr, int count, String formatted) {}

    private record CacheEntry(PriceRange result, long timestamp) {}

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final NumberFormat sekFmt = NumberFormat.getNumberInstance(new Locale("sv", "SE"));

    public PriceRange fetchPriceRange(String carTitle) {
        String query = extractSearchQuery(carTitle);
        if (query == null || query.isBlank()) return null;

        CacheEntry cached = cache.get(query);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS)
            return cached.result();

        try {
            String encodedQ = URLEncoder.encode(query, StandardCharsets.UTF_8);
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

            List<Integer> prices = new ArrayList<>();
            for (JsonNode doc : docs) {
                int amount = doc.path("price").path("amount").asInt(0);
                if (amount > 10_000) prices.add(amount);
            }
            if (prices.isEmpty()) return null;

            Collections.sort(prices);
            int min = prices.get(0);
            int max = prices.get(prices.size() - 1);
            String formatted = sekFmt.format(min) + " – " + sekFmt.format(max)
                    + " kr (" + prices.size() + " annonser)";

            PriceRange result = new PriceRange(min, max, prices.size(), formatted);
            cache.put(query, new CacheEntry(result, System.currentTimeMillis()));
            return result;

        } catch (Exception e) {
            return null;
        }
    }

    private String extractSearchQuery(String title) {
        if (title == null) return null;
        String s = title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").trim();
        // Strip engine displacement: "1.0 TSI", "1.5 T-GDI", "2.0 TDI", etc.
        s = s.replaceAll("\\s+\\d+[.,]\\d+.*$", "").trim();
        // Strip battery capacity: "26 kWh", "51 kWh", etc.
        s = s.replaceAll("(?i)\\s+\\d+\\s*kwh.*$", "").trim();
        // Strip EV range variants
        s = s.replaceAll("(?i)\\s+(Long Range|Short Range|Extended Range|Standard Range|Single Motor|Dual Motor|Grande Autonomie).*$", "").trim();
        return s;
    }
}
