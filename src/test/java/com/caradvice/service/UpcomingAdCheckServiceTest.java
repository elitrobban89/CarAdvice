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
