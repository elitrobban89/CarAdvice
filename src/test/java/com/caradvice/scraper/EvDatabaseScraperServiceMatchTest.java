package com.caradvice.scraper;

import com.caradvice.model.EvSpec;
import com.caradvice.repository.EvSpecRepository;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * findMatch är den funktion som avgör om nattsynken uppdaterar en befintlig rad eller skapar
 * en ny. Missar den skapas en parallell rad för samma bil — så uppstod EV6-tvillingarna.
 * Steg 3 (DB-namnet mer specifikt än det skrapade) är den riskabla riktningen och testas hårdast.
 */
class EvDatabaseScraperServiceMatchTest {

    private final EvDatabaseScraperService service =
            new EvDatabaseScraperService(mock(EvSpecRepository.class));

    /** Samma normalisering som tjänsten använder internt för nameMap-nycklarna. */
    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase().replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static EvSpec spec(String namn, int rangeKm) {
        return new EvSpec(namn, 11.0, 233.0, 77.4, rangeKm, 500_000);
    }

    private static Map<String, EvSpec> db(EvSpec... specs) {
        Map<String, EvSpec> m = new LinkedHashMap<>();
        for (EvSpec s : specs) m.put(norm(s.getCarName()), s);
        return m;
    }

    // ── EV6-fallet: DB-namnet är mer specifikt än ev-databases ──────────────────

    @Test
    void raknvidden_avgor_vilken_variant_som_traffas() {
        var preFacelift = spec("Kia EV6 Long Range 2WD 77.4 kWh", 528);
        var facelift    = spec("Kia EV6 Long Range 2WD 84 kWh",   582);
        var db = db(preFacelift, facelift);

        // ev-database säger bara "Kia EV6 Long Range 2WD" — räckvidden skiljer varianterna åt
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 582, db)).isSameAs(facelift);
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 528, db)).isSameAs(preFacelift);
    }

    @Test
    void utan_rackvidd_gors_ingen_omvand_matchning() {
        // Räckvidden är enda sättet att skilja varianterna åt — utan den vore träffen en gissning
        var db = db(spec("Kia EV6 Long Range 2WD 84 kWh", 582));
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 0, db)).isNull();
    }

    @Test
    void rackvidd_langt_ifran_ger_ingen_traff() {
        // 582 mot 428 km är inte samma bil, hur väl namnet än matchar
        var db = db(spec("Kia EV6 Long Range 2WD 84 kWh", 582));
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 428, db)).isNull();
    }

    @Test
    void tva_lika_nara_varianter_ger_ingen_traff() {
        // Lika nära åt var sitt håll = ingen aning om vilken. Hellre en ny rad som syns än att
        // skriva över fel variants data i tysthet.
        var db = db(spec("Kia EV6 Long Range 2WD 84 kWh", 570),
                    spec("Kia EV6 Long Range 2WD 77.4 kWh", 590));
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 580, db)).isNull();
    }

    @Test
    void for_kort_skrapat_namn_letar_inte_omvant() {
        // "Kia EV6" skulle annars matcha varenda EV6-variant i tabellen
        var db = db(spec("Kia EV6 Long Range 2WD 84 kWh", 582));
        assertThat(service.findMatch("Kia EV6", 582, db)).isNull();
    }

    // ── De två äldre stegen ska vara oförändrade ────────────────────────────────

    @Test
    void exakt_namn_traffar_fortfarande() {
        var s = spec("Volvo EX30 Single Motor", 344);
        assertThat(service.findMatch("Volvo EX30 Single Motor", 344, db(s))).isSameAs(s);
    }

    @Test
    void kortare_dbnamn_i_langre_skrapat_traffar_fortfarande() {
        // Steg 2: DB har "Tesla Model 3", ev-database säger "Tesla Model 3 Long Range AWD"
        var s = spec("Tesla Model 3", 566);
        assertThat(service.findMatch("Tesla Model 3 Long Range AWD", 566, db(s))).isSameAs(s);
    }

    @Test
    void steg2_vinner_over_steg3_nar_bada_skulle_kunna_traffa() {
        // Exakt/kortare-matchning är säkrare än den räckviddsgissande omvända riktningen
        var kort = spec("Kia EV6", 528);
        var lang = spec("Kia EV6 Long Range 2WD 84 kWh", 582);
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 582, db(kort, lang))).isSameAs(kort);
    }

    @Test
    void olik_bil_matchar_inte_alls() {
        var db = db(spec("Kia EV6 Long Range 2WD 84 kWh", 582));
        assertThat(service.findMatch("Hyundai IONIQ 5 Long Range", 582, db)).isNull();
    }

    @Test
    void dbrad_utan_rackvidd_hoppas_over() {
        var utan = new EvSpec("Kia EV6 Long Range 2WD 84 kWh", 11.0, 263.0, 84.0, 0, 0);
        var db = db(utan);
        assertThat(service.findMatch("Kia EV6 Long Range 2WD", 582, db)).isNull();
    }
}
