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
                    mock(com.caradvice.service.CargoSpecService.class),
                    mock(com.caradvice.service.EvPowerService.class));

    @Test
    void systemeffektenLasesUrTotalPowerCellen() {
        /*
         * Kortets hk var AI:ns gissning för i praktiken varje elbil — samma två kort fick 150 hk
         * i en körning 2026-08-14 och 95 hk dagen innan, mot riktiga 136 och 143. Sidan vi ändå
         * hämtar bär siffran: <td>Total Power</td><td>105 kW (143 PS)</td>.
         *
         * PS-talet i parentesen tas i första hand — PS är metriska hästkrafter, alltså samma
         * enhet som svenska "hk", så det slipper ett avrundningssteg.
         *
         * Etiketten matchas som HEL cell och inte som delsträng i brödtexten: sidan bär även
         * "Total Torque" och laddeffekter i kW, och en girig regex hade plockat vilken som helst.
         */
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse("""
                <table>
                  <tr><td>Total Power</td><td>105 kW (143 PS)</td></tr>
                  <tr><td>Total Torque</td><td>353 Nm</td></tr>
                  <tr><td>Fastcharge Power (max)</td><td>92 kW</td></tr>
                </table>""");
        assertThat(EvDatabaseScraperService.parseSystemPowerHk(doc)).isEqualTo(143);

        // Utan parentes räknas kW om: 150 kW × 1,3596 ≈ 204 hk
        assertThat(EvDatabaseScraperService.parseSystemPowerHk(org.jsoup.Jsoup.parse(
                "<table><tr><td>Total Power</td><td>150 kW</td></tr></table>"))).isEqualTo(204);

        // Saknad rad ger 0, alltså "ingen uppgift" — inte ett påhittat tal
        assertThat(EvDatabaseScraperService.parseSystemPowerHk(org.jsoup.Jsoup.parse(
                "<table><tr><td>Total Torque</td><td>353 Nm</td></tr></table>"))).isZero();
        assertThat(EvDatabaseScraperService.parseSystemPowerHk(null)).isZero();
    }

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

    // ── Steg 2: korta DB-ord får inte träffa som delsträng ──────────────────────

    @Test
    void siffra_traffar_inte_inuti_ett_langre_tal() {
        // Skarpt fall 2026-08-11: "Hyundai IONIQ 3 61 kWh" skrev sina siffror till raden
        // "Hyundai IONIQ 6", för "6" ryms i "61". Två helt olika bilar.
        var ioniq6 = spec("Hyundai IONIQ 6", 614);
        assertThat(service.findMatch("Hyundai IONIQ 3 61 kWh", 0, db(ioniq6))).isNull();
    }

    @Test
    void modellsiffra_traffar_inte_inuti_effekt_eller_batterital() {
        // "Renault 4 E-Tech 52kWh" träffade "Renault 5 E-Tech 52kWh" — "5" ryms i "52".
        var renault5 = spec("Renault 5 E-Tech 52kWh 150hp", 410);
        assertThat(service.findMatch("Renault 4 E-Tech 52kWh 150hp", 0, db(renault5))).isNull();
    }

    @Test
    void enbokstavsmodell_traffar_inte_inuti_ett_langre_modellnamn() {
        // "Mercedes-Benz GLC 400 4MATIC" träffade "Mercedes-Benz C 400 4MATIC" — "c" ryms i "glc".
        // Samma mekanism gav "Audi RS e-tron GT" → "Audi S e-tron GT".
        var cKlass = spec("Mercedes-Benz C 400 4MATIC", 600);
        assertThat(service.findMatch("Mercedes-Benz GLC 400 4MATIC", 0, db(cKlass))).isNull();

        var sEtron = spec("Audi S e-tron GT", 590);
        assertThat(service.findMatch("Audi RS e-tron GT", 0, db(sEtron))).isNull();
    }

    @Test
    void kort_dbord_traffar_fortfarande_som_eget_ord() {
        // Spärren får bara stoppa delsträngar. Står bokstaven som eget ord är det rätt bil.
        var cKlass = spec("Mercedes-Benz C 400 4MATIC", 600);
        assertThat(service.findMatch("Mercedes-Benz C 400 4MATIC Sedan", 600, db(cKlass)))
                .isSameAs(cKlass);
    }

    @Test
    void langt_dbord_far_fortfarande_traffa_som_delstrang() {
        // Motivet till fallbacken lever kvar: normaliseringen delar upp namn olika beroende på
        // skiljetecken, och för ord på tre tecken eller mer är delsträngsträffen ofarlig.
        var s = spec("Tesla Model Y Long Range", 533);
        assertThat(service.findMatch("Tesla Model Y LongRange AWD", 533, db(s))).isSameAs(s);
    }

    @Test
    void ett_riktigt_ord_traffar_inte_inuti_ett_annat_riktigt_ord() {
        /*
         * Uppmätt mot skarp data 2026-08-14: "BYD SEALION 7" skrev till raden "BYD Seal" — "seal"
         * ryms i "sealion", fyra tecken, alltså långt över längdgränsen. Raden fick en annan bils
         * räckvidd och batteri, och kollisionsspärren dolde det som "olika varianter".
         *
         * Längdgränsen kan inte fånga det här: orden ÄR långa. Skillnaden mot en äkta
         * särskrivning är att "sealion" inte går att dela upp i DB-namnets egna ord — "seal" +
         * "ion", och "ion" står ingenstans.
         *
         * Mätningen: 627 träffar oförändrade, 3 borta (samtliga SEALION), 0 som byter rad.
         */
        var seal = spec("BYD Seal", 520);
        assertThat(service.findMatch("BYD SEALION 7 82.5 kWh RWD Comfort", 456, db(seal))).isNull();

        // Gränsen åt andra hållet: den riktiga Seal-varianten träffar förstås fortfarande
        assertThat(service.findMatch("BYD SEAL 82.5 kWh RWD Design", 520, db(seal))).isSameAs(seal);
    }

    @Test
    void omdopt_modell_med_elprefix_traffar_fortfarande() {
        // Volvo döpte om C40 till EC40. Utan elprefix-undantaget hade synken skapat en andra rad
        // för samma bil — dubbletter under två namn är precis det som städades bort 2026-07-28
        // (756 rader). Samma prefixmönster som e-Golf och e-Rifter i EvSpecService.
        var c40 = spec("Volvo C40 Single Motor", 476);
        assertThat(service.findMatch("Volvo EC40 Single Motor ER", 476, db(c40))).isSameAs(c40);
    }

    @Test
    void utan_skentraffar_ar_varianterna_inte_langre_oavgjorda() {
        // Bonuseffekten: de åtta Tesla-varianterna föll i oavgjort-spärren för att BÅDA de lika
        // långa DB-namnen träffade via delsträng — "y" ryms i "yoke". Nu matchar bara den rätta.
        var modell3 = spec("Tesla Model 3", 513);
        var modellY = spec("Tesla Model Y", 533);
        assertThat(service.findMatch("Tesla Model 3 Yoke Premium", 513, db(modell3, modellY)))
                .isSameAs(modell3);
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
