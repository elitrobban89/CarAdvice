package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Värdetappslistan från systerprojektet Elbilsladdning, för chattens bruk.
 *
 * <p><b>Varför den hämtas i stället för att räknas om.</b> Elbilsladdning räknar varje måndag ut
 * hur mycket tio vanliga elbilar har kvar av sitt nypris: nypriset för årsmodell 2021 kommer
 * från Kvdbils och Bilprisers artikel och medianpriset från en egen Blocket-mätning. Samma
 * räkning här hade blivit en andra kopia av både seeddatat och alla Blocket-fällor — och två
 * ställen som glider isär. Chatten frågar därför den som redan vet.
 *
 * <p><b>Fail-soft är inte valfritt här.</b> Elbilsladdning kör på Renders gratisnivå och kan
 * vara nedspunnen eller ha slut på timmar, vilket ger allt från 30 sekunders väckningstid till
 * 503. En chattfråga får aldrig vänta på det: uteblir svaret får chatten helt enkelt inget
 * värdetappsavsnitt i sin kontext, och modellen svarar som förut. Se
 * {@code project_render_tiers} — snabbt 503 från de gratistjänsterna är väntat, inte ett fel.
 *
 * <p><b>Cachen är lång med flit.</b> Datat uppdateras en gång i veckan, så en sextimmarscache
 * kostar ingen färskhet men gör att en chattkonversation aldrig triggar mer än ett anrop.
 */
@Service
public class ValueRetentionClient {

    private static final Logger log = LoggerFactory.getLogger(ValueRetentionClient.class);

    /** Hur länge ett hämtat svar återanvänds. Datat byts en gång i veckan. */
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    /**
     * Hur länge ett MISSLYCKANDE får hålla oss borta.
     *
     * <p>Kortare än den vanliga cachen, för ett nej ska inte överleva lika länge som ett ja —
     * men inte noll heller: en nedspunnen gratistjänst svarar långsamt, och utan spärr hade
     * varje chattfråga betalat väntetiden igen. Aldrig cacha svaret som tomt i sex timmar; det
     * vore samma fälla som {@code feedback_failsoft_doljer_orsaken} beskriver.
     */
    private static final long FEL_TTL_MS = 10 * 60 * 1000L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${elbilsladdning.url:https://elbilsladdning.onrender.com}")
    private String elbilsladdningUrl;

    private volatile String cachadText = null;
    private volatile long cachadTid = 0;

    /**
     * Värdetappslistan som ett kompakt textblock för chattens systemprompt, eller tom sträng.
     *
     * <p>Text och inte JSON: det här går rakt in i en prompt, och en tabell i klartext är både
     * kortare i tokens och lättare för modellen att återge korrekt än nästlad JSON.
     */
    public String chatKontext() {
        long nu = System.currentTimeMillis();
        String cache = cachadText;
        if (cache != null && nu - cachadTid < (cache.isEmpty() ? FEL_TTL_MS : CACHE_TTL_MS)) {
            return cache;
        }

        String text = hamta();
        cachadText = text;
        cachadTid = nu;
        return text;
    }

    private String hamta() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(elbilsladdningUrl + "/api/value-retention"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET().build();

            HttpResponse<String> svar = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (svar.statusCode() != 200) {
                log.info("värdetapp: Elbilsladdning svarade {} — chatten får inget värdetappsavsnitt", svar.statusCode());
                return "";
            }
            return byggText(mapper.readTree(svar.body()));

        } catch (Exception e) {
            log.info("värdetapp: kunde inte hämtas ({}) — chatten får inget värdetappsavsnitt", e.getMessage());
            return "";
        }
    }

    /** JSON → textblock. Paketsynlig så formatet går att pröva utan HTTP. */
    String byggText(JsonNode rot) {
        JsonNode modeller = rot.path("modeller");
        if (!modeller.isArray() || modeller.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("VÄRDETAPP elbilar (årsmodell 2021, fem år gamla). Kvar av nypris, störst tapp först:\n");
        for (JsonNode m : modeller) {
            String namn = m.path("model").asText("");
            int kvar = m.path("retentionPct").asInt(0);
            if (namn.isEmpty() || kvar == 0) continue;
            sb.append("- ").append(namn)
              .append(": ").append(kvar).append(" % kvar (tappat ").append(100 - kvar).append(" %)")
              .append(", nypris ").append(m.path("newPriceKr").asInt()).append(" kr")
              .append(", median idag ").append(m.path("medianPriceKr").asInt()).append(" kr");
            if (m.path("cheapestPriceKr").isNumber())
                sb.append(", billigast ").append(m.path("cheapestPriceKr").asInt()).append(" kr");
            sb.append(" (").append(m.path("adCount").asInt()).append(" annonser)\n");
        }

        // Källorna följer med i prompten, inte bara i koden. Modellen ska kunna skriva ut varifrån
        // talen kommer — nypriset och medianen har OLIKA ursprung, och ett svar som klumpar ihop
        // dem till "enligt Blocket" är fel om nypriset kom från Kvdbil.
        sb.append("Källa nypris: ").append(rot.path("nyprisKalla").asText("Kvdbil/Bilpriser")).append(". ");
        sb.append("Källa dagspris: ").append(rot.path("prisKalla").asText("median på Blocket")).append(".\n");
        sb.append("Frågar någon vilken elbil som tappat mest i värde, om fynd på begagnade elbilar "
                + "eller om värdeminskning på el: svara med en MARKDOWN-TABELL av raderna ovan "
                + "(kolumner: Modell | Kvar av nypris | Nypris | Median idag), störst tapp först, "
                + "och skriv ut båda källorna under tabellen. Hitta aldrig på rader som inte står här.\n");
        return sb.toString();
    }
}
