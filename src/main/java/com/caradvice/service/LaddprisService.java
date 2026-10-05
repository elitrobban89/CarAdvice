package com.caradvice.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Laddpriserna per nätverk som nattrutinen belagt, för elbilsassistenten ({@code GET /api/laddpriser}).
 *
 * <p>Priserna stod förut hårdkodade i Elbilsladdnings {@code OperatorPriceService}, senast
 * uppdaterade i juni. Nu kontrollerar nattrutinen nätverkens egna prissidor och skriver det den
 * kunnat belägga i {@code morgonfix/laddpriser.json}; Elbilsladdning hämtar listan och lägger den
 * över sin tabell, som blir reserv. Båda sidor har vakter: här avvisas allt som inte är ett pris
 * med källa och datum, där avvisas orimliga nivåer och stora hopp.
 *
 * @author Robert Andersson Kopler
 */
@Service
public class LaddprisService {

    private static final Logger log = LoggerFactory.getLogger(LaddprisService.class);
    static final String RESURS = "morgonfix/laddpriser.json";

    private final Supplier<InputStream> kalla;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public LaddprisService() {
        this(LaddprisService::lasResurs);
    }

    LaddprisService(Supplier<InputStream> kalla) {
        this.kalla = kalla;
    }

    private static InputStream lasResurs() {
        try {
            ClassPathResource r = new ClassPathResource(RESURS);
            return r.exists() ? r.getInputStream() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Giltiga priser i filens ordning. Ogiltiga hoppas över och loggas. */
    public List<Map<String, Object>> priser() {
        try (InputStream in = kalla.get()) {
            if (in == null) return List.of();
            List<Map<String, Object>> ut = new ArrayList<>();
            for (JsonNode n : json.readTree(in).path("priser")) {
                String fel = fel(n);
                if (fel != null) {
                    log.warn("Laddpris ogiltigt ({}), publiceras inte: {}", fel, n);
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("natverk", n.path("natverk").asString().trim().toLowerCase());
                m.put("pris", n.path("pris").asString().trim());
                m.put("kalla", n.path("kalla").asString().trim());
                m.put("kontrollerad", n.path("kontrollerad").asString());
                ut.add(m);
            }
            return ut;
        } catch (Exception e) {
            log.warn("{} kunde inte läsas: {}", RESURS, e.getMessage());
            return List.of();
        }
    }

    /** @return skälet att inte publicera priset, eller null */
    static String fel(JsonNode n) {
        String natverk = n.path("natverk").asString("").trim();
        String pris = n.path("pris").asString("").trim();
        String kalla = n.path("kalla").asString("").trim();
        String datum = n.path("kontrollerad").asString("");
        if (natverk.length() < 3) return "nätverk";
        if (!pris.toLowerCase().startsWith("gratis") && !pris.matches("~?\\d{1,2}([,.]\\d{1,2})? kr/kWh"))
            return "inte ett pris i kr/kWh";
        if (!kalla.startsWith("https://")) return "källan ska vara nätverkets egen sida (https)";
        try {
            LocalDate.parse(datum);
        } catch (Exception e) {
            return "kontrollerad-datum";
        }
        return null;
    }
}
