package com.caradvice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Textblocket som ger chatten värdetappslistan från Elbilsladdning.
 *
 * <p>Formatet går rakt in i en systemprompt, så det som provas är att siffrorna kommer med, att
 * BÅDA källorna skrivs ut, och att ett tomt eller trasigt svar ger tom sträng i stället för ett
 * halvt block — en prompt med "VÄRDETAPP:" och inga rader hade bjudit in modellen att fylla i
 * själv, vilket är precis det vi vill undvika.
 */
class ValueRetentionClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ValueRetentionClient klient = new ValueRetentionClient();

    private String text(String json) throws Exception {
        return klient.byggText(mapper.readTree(json));
    }

    private static final String SVAR = """
            {"modeller":[
              {"model":"Audi e-tron 55 quattro","modelYear":2021,"newPriceKr":970000,
               "medianPriceKr":365000,"cheapestPriceKr":298700,"adCount":28,"retentionPct":38},
              {"model":"Nissan Leaf e+ 62 kWh","modelYear":2021,"newPriceKr":461500,
               "medianPriceKr":189900,"cheapestPriceKr":169800,"adCount":14,"retentionPct":41}],
             "nyprisKalla":"Nypris enligt Kvdbil och Bilpriser (kvd.se, 2024-01-22)",
             "prisKalla":"Medianpris bland dagens annonser på Blocket, max 15 000 mil"}
            """;

    @Test
    void alltChattenBehoverFinnsIBlocket() throws Exception {
        String t = text(SVAR);
        assertThat(t)
                .contains("Audi e-tron 55 quattro")
                .contains("38 % kvar")
                .contains("tappat 62 %")          // räknas ut här, inte av modellen
                .contains("970000")               // nypris
                .contains("365000")               // median idag
                .contains("298700")               // billigast
                .contains("28 annonser");         // underlaget, så modellen kan väga siffran
    }

    @Test
    void badaKallornaSkrivsUtSeparat() throws Exception {
        /*
         * Nypriset och dagspriset har OLIKA ursprung — Kvdbil respektive vår egen Blocket-mätning.
         * Ett svar som klumpar ihop dem till "enligt Blocket" är fel om nypriset kom från Kvdbil,
         * så båda måste finnas i prompten. Samma krav som marknadsangivelsen på
         * värdeminskningsrader i skrapern.
         */
        String t = text(SVAR);
        assertThat(t).contains("Kvdbil").contains("Blocket");
        assertThat(t).contains("Källa nypris:").contains("Källa dagspris:");
    }

    @Test
    void instruktionenBerOmEnMarkdownTabellOchForbjuderPahittadeRader() throws Exception {
        String t = text(SVAR);
        assertThat(t)
                .contains("MARKDOWN-TABELL")
                .contains("Hitta aldrig på rader som inte står här");
    }

    @Test
    void storstTappForstBevarasFranApiSvaret() throws Exception {
        // Elbilsladdning sorterar redan störst tapp först; ordningen får inte kastas om här,
        // för instruktionen till modellen säger att tabellen ska ha störst tapp först.
        String t = text(SVAR);
        assertThat(t.indexOf("Audi e-tron")).isLessThan(t.indexOf("Nissan Leaf"));
    }

    @Test
    void tomListaGerTomStrangOchIngenRubrik() throws Exception {
        /*
         * Före Elbilsladdnings första synk är listan tom. Då ska chatten inte få NÅGOT
         * värdetappsavsnitt — ett block med rubrik men utan rader hade bjudit in modellen att
         * hitta på innehållet, vilket är värre än att sakna avsnittet.
         */
        assertThat(text("{\"modeller\":[],\"nyprisKalla\":\"x\"}")).isEmpty();
        assertThat(text("{}")).isEmpty();
    }

    @Test
    void radUtanModellnamnEllerProcentHoppasOver() throws Exception {
        String t = text("""
                {"modeller":[
                  {"model":"","retentionPct":40,"newPriceKr":1,"medianPriceKr":1,"adCount":1},
                  {"model":"Riktig bil","retentionPct":45,"newPriceKr":400000,"medianPriceKr":180000,"adCount":9}],
                 "nyprisKalla":"k","prisKalla":"p"}
                """);
        assertThat(t).contains("Riktig bil");
        assertThat(t).doesNotContain("- : ");
    }

    @Test
    void saknadBilligastUppgiftFallerInteRaden() throws Exception {
        String t = text("""
                {"modeller":[{"model":"Testbil","retentionPct":50,"newPriceKr":400000,
                  "medianPriceKr":200000,"adCount":7}],"nyprisKalla":"k","prisKalla":"p"}
                """);
        assertThat(t).contains("Testbil").contains("7 annonser");
        assertThat(t).doesNotContain("billigast null");
    }
}
