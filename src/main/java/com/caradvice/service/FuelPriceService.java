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
import java.time.Duration;
import java.util.Locale;

/**
 * Dagsaktuella svenska bränslepriser från Bilresa-backendens /api/fuel-price
 * (globalpetrolprices.com, nationellt snitt). Injiceras i AI-promptarna så att
 * chatt/rekommendationer räknar bränslekostnad på verkligt pris i stället för
 * modellens gissning. 6 h cache; vid hämtfel returneras tom sträng (prompten
 * klarar sig utan raden) och nytt försök görs efter 5 min.
 */
@Service
public class FuelPriceService {

    private static final Logger log = LoggerFactory.getLogger(FuelPriceService.class);
    private static final String FUEL_PRICE_URL = "https://bilresa.onrender.com/api/fuel-price";
    private static final long TTL_MS = 6 * 60 * 60 * 1000;
    private static final long RETRY_MS = 5 * 60 * 1000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String cachedContext = "";
    private volatile long nextRefreshAt = 0L;

    /** Promptrad med aktuella priser, eller tom sträng om priserna inte kunnat hämtas. */
    public String promptContext() {
        if (System.currentTimeMillis() >= nextRefreshAt) {
            String fetched = fetchContext();
            cachedContext = fetched;
            nextRefreshAt = System.currentTimeMillis() + (fetched.isEmpty() ? RETRY_MS : TTL_MS);
        }
        return cachedContext;
    }

    private String fetchContext() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(FUEL_PRICE_URL))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "";
            JsonNode json = mapper.readTree(resp.body());
            double bensin = json.path("bensin95").asDouble(0);
            double diesel = json.path("diesel").asDouble(0);
            if (bensin <= 0) return "";
            return buildContext(bensin, diesel);
        } catch (Exception e) {
            log.warn("Kunde inte hämta bränslepris från Bilresa: {}", e.getMessage());
            return "";
        }
    }

    static String buildContext(double bensin, double diesel) {
        StringBuilder sb = new StringBuilder(String.format(Locale.forLanguageTag("sv"),
                "AKTUELLA BRÄNSLEPRISER (Sverige, dagsfärska): bensin 95 ca %.2f kr/l", bensin));
        if (diesel > 0)
            sb.append(String.format(Locale.forLanguageTag("sv"), ", diesel ca %.2f kr/l", diesel));
        sb.append(". Använd DESSA priser i alla bränslekostnadsberäkningar — anta aldrig andra bränslepriser.");
        return sb.toString();
    }
}
