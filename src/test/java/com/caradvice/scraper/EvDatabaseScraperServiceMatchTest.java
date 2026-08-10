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
            new EvDatabaseScraperService(mock(EvSpecRepository.class),
                    mock(com.caradvice.service.CargoSpecService.class));

    @Test
    void bagagevolymLasesUrCellenEfterEtiketten() {
        // Sidorna besöks ändå varje natt, så volymen kostar inget extra anrop. "Cargo Volume" och
        // "Cargo Volume Max" står som varsin rad i SAMMA tabell — en textsökning på den första
        // hade lika gärna kunnat plocka den andras siffra, därför exakt cell-matchning.
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
                <table>
                  <tr><td>Cargo Volume</td><td>627 L</td></tr>
                  <tr><td>Cargo Volume Max</td><td>1835 L</td></tr>
                  <tr><td>Cargo Volume Frunk</td><td>62 L</td></tr>
                </table>""");

        assertThat(EvDatabaseScraperService.extractCargoCell(doc, "Cargo Volume")).isEqualTo(627);
        assertThat(EvDatabaseScraperService.extractCargoCell(doc, "Cargo Volume Max")).isEqualTo(1835);
        assertThat(EvDatabaseScraperService.extractCargoCell(doc, "Finns inte")).isZero();
    }

    @Test
    void bagagevolymUtanSiffraGerNoll() {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
                "<table><tr><td>Cargo Volume</td><td>n/a</td></tr></table>");
        assertThat(EvDatabaseScraperService.extractCargoCell(doc, "Cargo Volume")).isZero();
    }

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

    @Test
    void evDatabasesEgnaDrivlinenamnBlockeras() {
        // P3/P5/P8 är ev-databases namn på EX30:s drivlinor, som vi redan har under Volvos
        // egna namn — och de anger nettokapacitet där vi har brutto (49 mot 51, 65 mot 69).
        // Utan spärren i synken återskapas raderna vid nästa 02:00 efter varje radering.
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX30 P3")).isTrue();
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX30 P5 Long Range")).isTrue();
        assertThat(EvDatabaseScraperService.isAliasName("volvo ex30 p8 awd")).isTrue();
    }

    @Test
    void riktigaEx30NamnPasserarAliasSparren() {
        // Spärren får bara ta "P" följt av en siffra — allt annat är bilar vi vill ha kvar
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX30 Single Motor")).isFalse();
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX30 Twin Motor Performance")).isFalse();
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX30 Cross Country")).isFalse();
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX30 Plus")).isFalse();
        assertThat(EvDatabaseScraperService.isAliasName("Volvo EX90 P3")).isFalse();
        assertThat(EvDatabaseScraperService.isAliasName(null)).isFalse();
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

    // ── Steg 2: oavgjort mellan två lika långa DB-namn ──────────────────────────

    @Test
    void steg2_tva_lika_langa_dbnamn_ger_ingen_traff() {
        // Båda DB-namnen är tre ord och båda ryms i det skrapade namnet. Utan spärren avgjorde
        // nameMap:ens iterationsordning vem som fick siffrorna.
        var awd = spec("Tesla Model 3 AWD", 566);
        var rwd = spec("Tesla Model 3 RWD", 513);
        assertThat(service.findMatch("Tesla Model 3 AWD RWD Dual", 566, db(awd, rwd))).isNull();
    }

    @Test
    void steg2_langre_dbnamn_slar_kortare_utan_att_rakna_som_oavgjort() {
        // Olika längd är inte oavgjort — det längsta (mest specifika) namnet ska fortfarande vinna
        var kort = spec("Tesla Model 3", 500);
        var lang = spec("Tesla Model 3 Long Range", 566);
        assertThat(service.findMatch("Tesla Model 3 Long Range AWD", 566, db(kort, lang))).isSameAs(lang);
    }

    // ── Kollisionsspärren: flera skrapade bilar om samma DB-rad ─────────────────

    @Test
    void forsta_ansprakstagaren_far_raden() {
        Map<String, String> claims = new LinkedHashMap<>();
        assertThat(EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Premium Long Range"))
                .isNull();
    }

    @Test
    void andra_bilen_om_samma_rad_blockeras() {
        // Skarpt fall 2026-08-10: tre andra generationens MG4-sidor pekade var för sig entydigt
        // ut samma rad "MG4 Long Range" — den sist processade vann, tyst.
        Map<String, String> claims = new LinkedHashMap<>();
        EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Premium Long Range");

        assertThat(EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Urban Premium Long Range"))
                .isEqualTo("MG MG4 Premium Long Range");
        assertThat(EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Urban Comfort Long Range"))
                .isEqualTo("MG MG4 Premium Long Range");
    }

    @Test
    void samma_bil_tva_ganger_ar_ingen_kollision() {
        // Dubblettrader på cheatsheeten ska inte larma — det är samma bil, inte två varianter
        Map<String, String> claims = new LinkedHashMap<>();
        EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Premium Long Range");
        assertThat(EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Premium Long Range"))
                .isNull();
    }

    @Test
    void olika_rader_stor_inte_varandra() {
        Map<String, String> claims = new LinkedHashMap<>();
        assertThat(EvDatabaseScraperService.claimRow(claims, "mg4 long range", "MG MG4 Premium Long Range")).isNull();
        assertThat(EvDatabaseScraperService.claimRow(claims, "mg4 standard range", "MG MG4 Urban Standard Range")).isNull();
    }
}
