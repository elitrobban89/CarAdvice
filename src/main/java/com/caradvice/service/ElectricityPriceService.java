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
 * Elpriser till AI-promptarna. Utan dem räknade chatten elkostnad på rena spotpriser
 * (~0,30 kr/kWh) och underskattade driftkostnaden grovt — elnät, energiskatt och moms
 * saknades. Hemmaladdningsintervallet är kurerat (samma siffror som Elbilsladdnings
 * prompt), snabbladdningssnittet hämtas från Elbilsladdnings /api/charging-price
 * (nationellt snitt över operatörernas publika listpriser) — samma mönster som
 * FuelPriceService mot Bilresa. 6 h cache, nytt försök efter 5 min vid hämtfel.
 * Går snabbladdningspriset inte att hämta faller raden tillbaka på enbart
 * hemmaladdning, så prompten aldrig står helt utan elpris.
 */
@Service
public class ElectricityPriceService {

    private static final Logger log = LoggerFactory.getLogger(ElectricityPriceService.class);
    private static final String CHARGING_PRICE_URL = "https://elbilsladdning.onrender.com/api/charging-price";
    private static final long TTL_MS = 6 * 60 * 60 * 1000;
    private static final long RETRY_MS = 5 * 60 * 1000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String cachedContext = "";
    private volatile long nextRefreshAt = 0L;

    /** Promptrad med elpriser — hemmaladdning alltid, snabbladdning när snittet kunnat hämtas. */
    public String promptContext() {
        if (System.currentTimeMillis() >= nextRefreshAt) {
            double dc = fetchNationalAverage();
            cachedContext = buildContext(dc);
            nextRefreshAt = System.currentTimeMillis() + (dc > 0 ? TTL_MS : RETRY_MS);
        }
        return cachedContext;
    }

    /** 0 om priset inte kunde hämtas. */
    private double fetchNationalAverage() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CHARGING_PRICE_URL))
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return 0;
            JsonNode json = mapper.readTree(resp.body());
            return json.path("avgNationalKr").asDouble(0);
        } catch (Exception e) {
            log.warn("Kunde inte hämta snabbladdningspris från Elbilsladdning: {}", e.getMessage());
            return 0;
        }
    }

    static String buildContext(double dcAverage) {
        StringBuilder sb = new StringBuilder(
                "AKTUELLA ELPRISER (Sverige): hemmaladdning ca 1,50–3,50 kr/kWh beroende på elavtal"
                        + " — räkna med ca 2,50 kr/kWh inkl. elnät, energiskatt och moms");
        if (dcAverage > 0)
            sb.append(String.format(Locale.forLanguageTag("sv"),
                    ", publik snabbladdning ca %.2f kr/kWh (nationellt snitt över operatörernas listpriser)",
                    dcAverage));
        sb.append(". Använd DESSA priser i alla elkostnadsberäkningar — räkna ALDRIG på rena spotpriser"
                + " utan nätavgift och skatt, och blanda inte ihop kr/kWh med kr/mil.");
        return sb.toString();
    }
}
