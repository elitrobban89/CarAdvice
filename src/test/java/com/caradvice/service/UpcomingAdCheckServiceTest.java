package com.caradvice.service;

import com.caradvice.service.UpcomingAdCheckService.Dom;
import com.caradvice.service.UpcomingAdCheckService.Rapport;
import com.caradvice.service.UpcomingAdCheckService.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Annonskollen av kommande-kön.
 *
 * <p><b>Annonsnamnen nedan är hämtade ur Blockets skarpa svar 2026-09-02</b>, inte påhittade —
 * hela värdet i kollen ligger i att den skiljer "Hyundai IONIQ / 3 Standard Range Select" från
 * "Hyundai IONIQ / Standard Range Select", och den skillnaden går inte att uppfinna vid
 * skrivbordet. Testerna går aldrig ut på nätet: uppslaget skickas in som en söm.
 *
 * @author Robert Andersson Kopler
 */
class UpcomingAdCheckServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> sokningar = new ArrayList<>();

    private UpcomingAdCheckService tjanst() {
        return new UpcomingAdCheckService(null);
    }

    /** Annonssvar: fritextfråga → annonsernas rubrik + trimnivå, som Blocket skickar dem. */
    private Function<String, JsonNode> annonser(Map<String, List<String[]>> svar) {
        return q -> {
            sokningar.add(q);
            List<String[]> rader = svar.get(q);
            if (rader == null) return null; // uppslaget misslyckades
            ArrayNode docs = MAPPER.createArrayNode();
            for (String[] rad : rader) {
                docs.addObject().put("heading", rad[0]).put("model_specification", rad[1]);
            }
            return docs;
        };
    }

    /** Samma söm, men med annonsens årsmodell, pris och mätarställning: rad = {rubrik, trim, år, pris, mil}. */
    private Function<String, JsonNode> annonserMedFakta(Map<String, List<String[]>> svar) {
        return q -> {
            sokningar.add(q);
            List<String[]> rader = svar.get(q);
            if (rader == null) return null;
            ArrayNode docs = MAPPER.createArrayNode();
            for (String[] rad : rader) {
                var doc = docs.addObject().put("heading", rad[0]).put("model_specification", rad[1]);
                if (rad[2] != null) doc.put("year", Integer.parseInt(rad[2]));
                if (rad[3] != null) doc.putObject("price").put("amount", Long.parseLong(rad[3]));
                if (rad[4] != null) doc.put("mileage", Long.parseLong(rad[4]));
            }
            return docs;
        };
    }

    /** Samma söm med Blockets drivmedelsfält: rad = {rubrik, trim, fuel}. Tomt fuel = fältet saknas. */
    private Function<String, JsonNode> annonserMedDrivmedel(Map<String, List<String[]>> svar) {
        return q -> {
            sokningar.add(q);
            List<String[]> rader = svar.get(q);
            if (rader == null) return null;
            ArrayNode docs = MAPPER.createArrayNode();
            for (String[] rad : rader) {
                var doc = docs.addObject().put("heading", rad[0]).put("model_specification", rad[1]);
                if (rad[2] != null) doc.put("fuel", rad[2]);
            }
            return docs;
        };
    }

    private Map<String, Object> rad(long id, String make, String model, String insight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insight_id", id);
        m.put("car_make", make);
        m.put("car_model", model);
        m.put("insight", insight);
        return m;
    }

    private Dom dom(Rapport r, String model) {
        return r.domar().stream().filter(d -> d.carModel().equals(model)).findFirst().orElseThrow();
    }

    // ── Namnmatchningen ───────────────────────────────────────────────────────

    @Test
    void modellordenMasteStaEfterVarandraIAnnonsen() {
        // Blocket skriver "3" i trimnivån, inte i rubriken — de sex Ioniq 3-raderna parkerades
        // 2026-09-02 fast bilen stod till salu.
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "Ioniq 3", "Hyundai IONIQ 3 Standard Range Select")).isTrue();
        // Samma märke, samma trimnamn, men ingen trea: det här är inte Ioniq 3.
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "Ioniq 3", "Hyundai IONIQ Standard Range Select")).isFalse();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "Ioniq 3", "Hyundai Ioniq 5 77.4 kWh AWD Advanced")).isFalse();
    }

    @Test
    void modellenSomTeckenPrefixArIngenTraff() {
        // "Model Y L" är ett teckenprefix av "Model Y Long Range" — en contains-regel hade
        // släppt ut de två köade Model Y L-raderna på varje långfärds-Y i landet.
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "Model Y L", "Tesla Model Y Long Range AWD")).isFalse();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "Model Y L", "Tesla Model Y L 6-sits")).isTrue();
    }

    @Test
    void hopslagenModellFarMatchaEttHeltOrd() {
        // Blocket registrerar laddhybriden utan mellanslag: heading "Lexus NX450h+".
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "NX 450h+", "Lexus NX450h+ 450h+ Business Plus Plug-In")).isTrue();
        // Men hopslagningen måste vara ett EGET ord, annars är regel 2 bara contains igen:
        // "modelyl" ligger inuti "modelylong" utan att vara det.
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "Model Y L", "Tesla ModelYLong Range")).isFalse();
    }

    @Test
    void syskonmodellerOchFamiljesuffixFallerBort() {
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen("EX50", "Volvo EX40 Ultra Twin")).isFalse();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen("EX50", "Volvo EX90 Plus")).isFalse();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen("A2 e-tron", "Audi e-tron 55 quattro")).isFalse();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen("ID. Cross", "Volkswagen ID. Buzz Pro")).isFalse();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen("ID. Cross", "Volkswagen ID.4 Pro")).isFalse();
    }

    @Test
    void streckOchDiakriterKokasNerSomPaBilkorten() {
        // U+2011 NON-BREAKING HYPHEN i annonsen, vanligt bindestreck i vår modell.
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "E-Outback", "Subaru E‑Outback 74.7 kWh AWD Touring")).isTrue();
        assertThat(UpcomingAdCheckService.annonsenNamnerModellen(
                "e-C3", "Citroën ë-C3 Max")).isTrue();
    }

    // ── Nyhetsordet som skiljer felparkering från riktig parkering ─────────────

    @Test
    void nyhetsordSkiljerNastaGenerationFranRenFakta() {
        // De korrekt parkerade raderna i kön 2026-09-02, ordagrant.
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Den nya femte generationens Hyundai Tucson blir 4,7 meter lång")).isTrue();
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Hyundai planerar en EREV-version av Santa Fe")).isTrue();
        // De felparkerade Ioniq 3-raderna, ordagrant — ren fakta om en bil man kan köpa.
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Pris för Ioniq 3 Standard Range Select startar på 344 900 kr")).isFalse();
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "E‑Outback kan dra släp på upp till 1 500 kg")).isFalse();
    }

    /**
     * Böjningshålet från 2026-09-18: id 1573 gav LARM mot 46 annonser för den XC70 som såldes
     * 2004–2016, enbart för att listan bar {@code lanseras} men inte {@code lanserat}.
     */
    @Test
    void aktivBojningAvLanseraOchOvervagandeRaknasSomNyhetsord() {
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Volvo har lanserat en laddhybrid av XC70 i Kina med lång räckvidd och "
                        + "överväger att ta den till den europeiska marknaden")).isTrue();
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Volvo lanserar XC70 i Kina")).isTrue();
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Mercedes lanserade C-klass Electric i Kina")).isTrue();
        // Gränsen åt överblockeringshållet: stammen får inte matcha inuti ett annat ord, och
        // en ren faktarad om en bil hos handlaren står kvar som LARM.
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Lanseringsfesten avslöjade inget om priset")).isFalse();
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Pris för Ioniq 3 Standard Range Select startar på 344 900 kr")).isFalse();
        // Och den som ordlistan ALDRIG kan rädda: raden är skriven som ren presensfakta om en
        // bil som inte går att köpa (id 1578, el-Range Rover mot 49 bensin-/dieselannonser).
        // Ordlistan ska fortsätta säga nej här — raden räddas i stället av drivlinan i annonserna,
        // se elversionsradMotBaraFossilaAnnonserGerAnnanDrivlina.
        assertThat(UpcomingAdCheckService.sagerAttBilenArKommande(
                "Med smart mjukvara, luftfjädring och 900 mm vadardjup klarar den 2,8 ton "
                        + "tunga el‑Range Rover lätt över klippor och leriga underlag")).isFalse();
    }

    // ── Domarna ───────────────────────────────────────────────────────────────

    @Test
    void bilTillSaluMedRenFaktaradGerLarm() {
        Rapport r = tjanst().granska(
                List.of(rad(1384, "Hyundai", "Ioniq 3",
                            "Pris för Ioniq 3 Standard Range Select startar på 344 900 kr"),
                        rad(1385, "Hyundai", "Ioniq 3",
                            "Privatleasing för Ioniq 3 Standard Range kan börja på 3 495 kr per månad")),
                annonser(Map.of("Hyundai Ioniq 3", List.of(
                        new String[] {"Hyundai IONIQ", "3 Standard Range Select"},
                        new String[] {"Hyundai IONIQ", "Standard Range Select"},
                        new String[] {"Hyundai Ioniq 5", "77.4 kWh AWD Advanced"}))));

        Dom d = dom(r, "Ioniq 3");
        assertThat(d.status()).isEqualTo(Status.LARM);
        // Bara annonsen som faktiskt bär trean räknas — inte Blockets tre råa träffar.
        assertThat(d.annonser()).isEqualTo(1);
        assertThat(d.raderUtanNyhetsord()).containsExactly(1384L, 1385L);
        assertThat(sokningar).containsExactly("Hyundai Ioniq 3");
    }

    @Test
    void bilTillSaluDarVarjeRadGallerNastaGenerationGerGranska() {
        // Tucson säljs, men alla fyra köade rader handlar om femte generationen. Ett automatiskt
        // släpp hade lagt dem på dagens Tucson-kort — därför GRANSKA och inte LARM.
        Rapport r = tjanst().granska(
                List.of(rad(1274, "Hyundai", "Tucson",
                            "Den nya femte generationens Hyundai Tucson blir 4,7 meter lång"),
                        rad(1293, "Hyundai", "Tucson",
                            "Bakdörrarnas öppningsvinkel på den nya Tucson ökas från 73 ° till 83 °")),
                annonser(Map.of("Hyundai Tucson", List.<String[]>of(
                        new String[] {"Hyundai Tucson", "1.6 T-GDi Advanced"}))));

        Dom d = dom(r, "Tucson");
        assertThat(d.status()).isEqualTo(Status.GRANSKA);
        assertThat(d.annonser()).isEqualTo(1);
        assertThat(d.raderUtanNyhetsord()).isEmpty();
    }

    @Test
    void ingenAnnonsBarModellnamnetGerIngaAnnonser() {
        Rapport r = tjanst().granska(
                List.of(rad(723, "Dacia", "Striker", "Bagageutrymmet på 600 liter placerar Striker i toppskiktet")),
                annonser(Map.of("Dacia Striker", List.of())));

        assertThat(dom(r, "Striker").status()).isEqualTo(Status.INGA_ANNONSER);
        assertThat(dom(r, "Striker").annonser()).isZero();
    }

    // ── Drivlinan som skiljer två bilar med samma namn ────────────────────────

    /**
     * Id 1578 ur kön 2026-09-18, mot Blockets skarpa svar samma dag: 49 annonser bär namnet
     * Range Rover och <b>42 är diesel, 7 bensin, 0 el</b>. Raden är ren presensfakta, så
     * {@code NYHETSORD} kan aldrig rädda den — men el-Range Rovern går inte att köpa.
     */
    @Test
    void elversionsradMotBaraFossilaAnnonserGerAnnanDrivlina() {
        Rapport r = tjanst().granska(
                List.of(rad(1578, "Range Rover", "Range Rover",
                            "Med smart mjukvara, luftfjädring och 900 mm vadardjup klarar den 2,8 ton "
                                    + "tunga el‑Range Rover lätt över klippor och leriga underlag")),
                annonserMedDrivmedel(Map.of("Range Rover Range Rover", List.of(
                        new String[] {"Land Rover Range Rover Sport", "Sport 3.0", "Diesel"},
                        new String[] {"Land Rover Range Rover Sport", "3.0 TDV6 4WD Automatisk", "Diesel"},
                        new String[] {"Land Rover Range Rover", "5.0 V8 Autobiography", "Bensin"}))));

        Dom d = dom(r, "Range Rover");
        assertThat(d.status()).isEqualTo(Status.ANNAN_DRIVLINA);
        assertThat(d.annonser()).isEqualTo(3);
        // Raden står kvar som "läses som ren fakta" — vakten döljer inte VARFÖR den var ett larm.
        assertThat(d.raderUtanNyhetsord()).containsExactly(1578L);
        // Beviset ska gå att läsa i rapporten utan ett eget uppslag.
        assertThat(d.exempel().get(0)).isEqualTo("Land Rover Range Rover Sport Sport 3.0 (Diesel)");
    }

    /**
     * Motprovet ur samma kö samma dag: Mazda 6e svarade med 45 träffar där samtliga är
     * {@code fuel=El}. Vakten får inte tysta larmet på den bil faran faktiskt gäller.
     */
    @Test
    void elversionsradMotElAnnonserStarKvarSomLarm() {
        Rapport r = tjanst().granska(
                List.of(rad(1551, "Mazda", "6e",
                            "Den el-Mazda 6e som säljs i Sverige har ett 68,8 kWh-batteri")),
                annonserMedDrivmedel(Map.of("Mazda 6e", List.of(
                        new String[] {"Mazda 6e", "68.8 kWh Takumi Plus", "El"},
                        new String[] {"Mazda 6e", "80 kWh Homura", "El"}))));

        assertThat(dom(r, "6e").status()).isEqualTo(Status.LARM);
    }

    @Test
    void annonserUtanDrivmedelsfaltFarInteTystaLarmet() {
        // Tomt fält är inget bevis för att bilen är fossil — samma avvägning som AdFilter gör
        // åt andra hållet. Utan känt drivmedel står larmet kvar.
        Rapport r = tjanst().granska(
                List.of(rad(1578, "Range Rover", "Range Rover",
                            "Den el‑Range Rover väger 2,8 ton")),
                annonserMedDrivmedel(Map.of("Range Rover Range Rover", List.<String[]>of(
                        new String[] {"Land Rover Range Rover Sport", "Sport 3.0", null}))));

        assertThat(dom(r, "Range Rover").status()).isEqualTo(Status.LARM);
    }

    @Test
    void generellFaktaradBredvidElversionsradBeharLarmet() {
        // 1578 gäller elversionen, men 1579 är en rad om bilen som står hos handlaren i dag.
        // Grannen får inte tysta den.
        Rapport r = tjanst().granska(
                List.of(rad(1578, "Range Rover", "Range Rover",
                            "Den el‑Range Rover väger 2,8 ton"),
                        rad(1579, "Range Rover", "Range Rover",
                            "Range Rover har 900 mm vadardjup och luftfjädring")),
                annonserMedDrivmedel(Map.of("Range Rover Range Rover", List.<String[]>of(
                        new String[] {"Land Rover Range Rover Sport", "Sport 3.0", "Diesel"}))));

        Dom d = dom(r, "Range Rover");
        assertThat(d.status()).isEqualTo(Status.LARM);
        assertThat(d.raderUtanNyhetsord()).containsExactly(1578L, 1579L);
    }

    @Test
    void elordetMasteSittaPaBilensNamn() {
        // Kvalificeraren direkt före märkets eller modellens första ord.
        assertThat(UpcomingAdCheckService.gallerElversionAv(
                "den 2,8 ton tunga el‑Range Rover", "Range Rover", "Range Rover")).isTrue();
        assertThat(UpcomingAdCheckService.gallerElversionAv(
                "Den eldrivna Range Rover väger 2,8 ton", "Range Rover", "Range Rover")).isTrue();
        assertThat(UpcomingAdCheckService.gallerElversionAv(
                "Den elektriska Volvo XC70 får 200 km räckvidd", "Volvo", "XC70")).isTrue();
        // Elordet i en jämförelse gör inte bensinbilen till en elversion — fyndlistans fälla,
        // ordagrant ur Lexus LBX-raden som stod som kandidat 2026-09-17.
        assertThat(UpcomingAdCheckService.gallerElversionAv(
                "Lexus LBX har en bensintank på 36 liter och en förbrukning på 0,57 l/mil "
                        + "jämfört med elbilar", "Lexus", "LBX")).isFalse();
        // Och ordet får inte matcha inuti ett annat ord.
        assertThat(UpcomingAdCheckService.gallerElversionAv(
                "Range Rover har elassisterad servostyrning", "Range Rover", "Range Rover")).isFalse();
        assertThat(UpcomingAdCheckService.gallerElversionAv(
                "Tesla Model Y har eluppvärmd ratt", "Tesla", "Model Y")).isFalse();
    }

    @Test
    void exemplenBarArsmodellPrisOchMatarstallning() {
        // Skarpa Blocket-svaret 2026-09-10. Namnen ensamma läser som en ombyggd A2 från 2003 —
        // det är 2027, 0 mil och priset som avgör att raderna var korrekt fällda.
        Rapport r = tjanst().granska(
                List.of(rad(1449, "Audi", "A2 e-tron",
                            "Audi A2 e-tron klarar upp till 646 kilometer på en laddning")),
                annonserMedFakta(Map.of("Audi A2 e-tron", List.of(
                        new String[] {"Audi A2", "e-tron 125,00 kW Proline", "2027", "454800", "0"},
                        new String[] {"Audi A2", "e-tron 240,00 kW S line", "2027", "822300", "0"}))));

        Dom d = dom(r, "A2 e-tron");
        assertThat(d.status()).isEqualTo(Status.LARM);
        assertThat(d.exempel()).containsExactly(
                "Audi A2 e-tron 125,00 kW Proline (2027, 454800 kr, 0 mil)",
                "Audi A2 e-tron 240,00 kW S line (2027, 822300 kr, 0 mil)");
    }

    @Test
    void annonsUtanArsmodellOchPrisGerBaraNamnet() {
        // Saknade fält utelämnas i stället för att skrivas ut som nollor — en "0 kr"-annons
        // hade läst som en gratis bil i stället för som ett tomt fält.
        Rapport r = tjanst().granska(
                List.of(rad(1471, "Kia", "EV3", "Kia EV3 har 204 hk och ett 81,4 kWh-batteri")),
                annonser(Map.of("Kia EV3", List.<String[]>of(
                        new String[] {"Kia EV3", "Long Range"}))));

        assertThat(dom(r, "EV3").exempel()).containsExactly("Kia EV3 Long Range");
    }

    @Test
    void leasingavgiftMarksMedManad() {
        // Skarpa raderna 2026-09-10. Blocket lägger leasingerbjudanden i köpträfflistan med
        // price_unit "kr", så 3 794 kr stod bredvid 264 800 kr som vore de samma sorts tal.
        // Den saknade mätarställningen (sista fältet null) är också leasing — mätningen hittade
        // ingen annons med lågt pris och tomt mileage som var något annat.
        Rapport r = tjanst().granska(
                List.of(rad(1412, "Hyundai", "Santa Fe", "Hyundai Santa Fe kommer som laddhybrid"),
                        rad(1414, "Hyundai", "Tucson", "Nya Tucson lanseras med EREV-drivlina")),
                annonserMedFakta(Map.of(
                        "Hyundai Santa Fe", List.<String[]>of(
                                new String[] {"Hyundai Santa Fe", "Advanced 7-sits Business Lease",
                                              "2025", "3794", "0"}),
                        "Hyundai Tucson", List.<String[]>of(
                                new String[] {"Hyundai Tucson", "PHEV Advanced Business lease",
                                              "2026", "2885", null}))));

        assertThat(dom(r, "Santa Fe").exempel()).containsExactly(
                "Hyundai Santa Fe Advanced 7-sits Business Lease (2025, 3794 kr/mån, 0 mil)");
        assertThat(dom(r, "Tucson").exempel()).containsExactly(
                "Hyundai Tucson PHEV Advanced Business lease (2026, 2885 kr/mån)");
    }

    @Test
    void slitenBilUnderTiotusenBehallerKopprismarkningen() {
        // Motexemplet som gör att gränsen inte kan vara priset ensamt: en RAV4 från 2002 med
        // 30 000 mil kostar verkligen 7 500 kr, och "7500 kr/mån" hade varit ren felinformation.
        Rapport r = tjanst().granska(
                List.of(rad(1490, "Toyota", "RAV4", "Toyota RAV4 kommer i en ny generation")),
                annonserMedFakta(Map.of("Toyota RAV4", List.<String[]>of(
                        new String[] {"Toyota RAV4", "5-dörrar 2.0 VVT-i 4x4 Manuell",
                                      "2002", "7500", "30000"}))));

        assertThat(dom(r, "RAV4").exempel()).containsExactly(
                "Toyota RAV4 5-dörrar 2.0 VVT-i 4x4 Manuell (2002, 7500 kr, 30000 mil)");
    }

    @Test
    void lagMatarstallningPaAldreBilArOcksaLeasing() {
        // De tre äldre lågprisannonserna i mätningen som INTE var slitna bilar var alla leasing:
        // en Leaf från 2021 med 5 335 mil och 3 850 kr i månaden. Aldersgränsen ensam hade
        // alltså skrivit ut dem som köppriser.
        Rapport r = tjanst().granska(
                List.of(rad(1495, "Nissan", "Leaf", "Nissan Leaf kommer med 75 kWh-batteri")),
                annonserMedFakta(Map.of("Nissan Leaf", List.<String[]>of(
                        new String[] {"Nissan Leaf", "[Leasing 3850kr/mån] NISSAN LEAF 62KWH",
                                      "2021", "3850", "5335"}))));

        assertThat(dom(r, "Leaf").exempel()).containsExactly(
                "Nissan Leaf [Leasing 3850kr/mån] NISSAN LEAF 62KWH (2021, 3850 kr/mån, 5335 mil)");
    }

    @Test
    void misslyckatUppslagArAldrigEttGodkannande() {
        // Tom lista och nätfel får inte hamna i samma hink: ett trasigt uppslag som räknades som
        // "inga annonser" hade tyst friskförklarat hela kön.
        Rapport r = tjanst().granska(
                List.of(rad(769, "BYD", "Shark", "Via V2L-funktionen kan batteriet leverera upp till 6 kW")),
                annonser(Map.of()));

        assertThat(dom(r, "Shark").status()).isEqualTo(Status.UPPSLAG_MISSLYCKADES);
        assertThat(r.perStatus().get(Status.INGA_ANNONSER.name())).isZero();
    }

    @Test
    void ettUppslagPerBilOchLarmenLiggerForst() {
        Rapport r = tjanst().granska(
                List.of(rad(1, "Dacia", "Striker", "Striker är 4,62 meter lång"),
                        rad(2, "Dacia", "Striker", "Bagageutrymmet är 600 liter"),
                        rad(3, "Subaru", "E-Outback", "E-Outback kan dra släp på upp till 1 500 kg")),
                annonser(Map.of(
                        "Dacia Striker", List.of(),
                        "Subaru E-Outback", List.of(
                                new String[] {"Subaru E-Outback", "74.7 kWh AWD Limited"},
                                new String[] {"Subaru Outback", "2.0 4WD Business"}))));

        assertThat(sokningar).containsExactly("Dacia Striker", "Subaru E-Outback");
        assertThat(r.bilar()).isEqualTo(2);
        assertThat(r.rader()).isEqualTo(3);
        assertThat(r.domar().get(0).status()).isEqualTo(Status.LARM);
        assertThat(dom(r, "E-Outback").annonser()).isEqualTo(1); // bensin-Outbacken räknas inte
        assertThat(dom(r, "Striker").rader()).containsExactly(1L, 2L);
    }

    @Test
    void raderUtanMarkeEllerModellHopposOver() {
        Rapport r = tjanst().granska(
                List.of(rad(9, "", "", "En rad utan bil"),
                        rad(10, "BYD", "", "Märke men ingen modell")),
                annonser(Map.of()));

        assertThat(r.bilar()).isZero();
        assertThat(r.rader()).isZero();
        assertThat(sokningar).isEmpty();
    }

    @Test
    void anropstaketSynsSomHoppadeIStalletForAttTystna() {
        List<Map<String, Object>> ko = new ArrayList<>();
        Map<String, List<String[]>> svar = new LinkedHashMap<>();
        for (int i = 0; i < UpcomingAdCheckService.MAX_ANROP + 3; i++) {
            ko.add(rad(i, "Märke" + i, "Modell" + i, "En rad"));
            svar.put("Märke" + i + " Modell" + i, List.of());
        }

        Rapport r = tjanst().granska(ko, annonser(svar));

        assertThat(sokningar).hasSize(UpcomingAdCheckService.MAX_ANROP);
        assertThat(r.hoppade()).isEqualTo(3);
    }
}
