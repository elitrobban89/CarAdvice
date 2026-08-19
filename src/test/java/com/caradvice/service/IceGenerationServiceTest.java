package com.caradvice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IceGenerationServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IceGenerationService service = new IceGenerationService(jdbc);

    @Test
    void misslistanRaknarNerTillDagenModellenProvasOmIgen() {
        // Siffran "vakten-avstod: 111" gick inte att granska — den kunde lika gärna vara en hel
        // märkesfamilj som föll på samma fel, vilket redan hänt en gång med chassikoden i titeln.
        // dagarKvar svarar dessutom på det räkningen aldrig kunde: när kommer modellen tillbaka
        // på arbetslistan?
        long idag = java.time.LocalDate.now().toEpochDay();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("model_name", "bmw 3-serie", "forsokt_dag", (int) (idag - 1), "orsak", "vakten-avstod"),
                Map.of("model_name", "alfa romeo 147", "forsokt_dag", (int) (idag - 40), "orsak", "ej-hittad")));

        List<Map<String, Object>> rader = service.listaMissar(null);

        assertThat(rader).hasSize(2);
        assertThat(rader.get(0)).containsEntry("model", "bmw 3-serie")
                                .containsEntry("orsak", "vakten-avstod")
                                .containsEntry("alderDagar", 1L)
                                .containsEntry("dagarKvar", 29L);
        // Fönstret gick ut för tio dagar sedan. "minus tio dagar kvar" är ingen upplysning —
        // noll betyder att natten prövar om modellen.
        assertThat(rader.get(1)).containsEntry("dagarKvar", 0L);
    }

    @Test
    void misslistansFilterSlapperBaraGenomEnOrsak() {
        // De två nejen kräver olika åtgärder: ett dött uppslag lagas i parsern, ett avstående är
        // en fråga om vaktens gräns. Utan filter drunknar de 111 avståendena i resten.
        long idag = java.time.LocalDate.now().toEpochDay();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("model_name", "bmw 3-serie", "forsokt_dag", (int) idag, "orsak", "vakten-avstod"),
                Map.of("model_name", "alfa romeo 147", "forsokt_dag", (int) idag, "orsak", "ej-hittad")));

        assertThat(service.listaMissar("ej-hittad"))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("model", "alfa romeo 147"));
        // Tom sträng är inget filter — annars hade ett bortglömt ?orsak= tömt listan tyst.
        assertThat(service.listaMissar("")).hasSize(2);
    }

    @Test
    void misslistanTalarRaderSkrivnaForeOrsakskolumnen() {
        // Kolumnen kom till 2026-08-16, efter tabellen. Rader från före det har NULL där, och
        // en NPE i granskningen hade gjort just de äldsta missarna omöjliga att läsa.
        long idag = java.time.LocalDate.now().toEpochDay();
        java.util.Map<String, Object> gammal = new java.util.HashMap<>();
        gammal.put("model_name", "audi a4");
        gammal.put("forsokt_dag", (int) idag);
        gammal.put("orsak", "okand");   // COALESCE i SQL:en gör NULL till okand
        when(jdbc.queryForList(anyString())).thenReturn(List.of(gammal));

        assertThat(service.listaMissar(null)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("orsak", "okand"));
    }

    @Test
    void listanDoperOmKolumnernaTillKortetsSprak() {
        // Kolumnnamnen är SQL-sidans (model_name, fran_ar) och svaret är API:ts (model, franAr).
        // Går de isär blir endpointen tyst obrukbar: fälten finns kvar men heter fel, och en
        // granskning som letar efter årtalet hittar ingenting att jämföra med.
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("model_name", "volkswagen golf", "fran_ar", 2020L)));

        assertThat(service.lista()).containsExactly(
                Map.of("model", "volkswagen golf", "franAr", 2020));
    }

    @Test
    void farskMissHindrarNyttForsokMenGlomsEfterFonstret() {
        // Missen finns för att frigöra nattens budget, inte för att döma modellen för alltid:
        // 2026-08-13 missade varenda modell därför att sajten bytt markup och parsern var död,
        // och ett permanent nej hade fryst det haveriet till ett tomt bord.
        long idag = java.time.LocalDate.now().toEpochDay();
        when(jdbc.queryForList("SELECT model_name, forsokt_dag FROM ice_generation_miss")).thenReturn(List.of(
                Map.of("model_name", "ford ecosport", "forsokt_dag", (int) (idag - 1)),
                Map.of("model_name", "mazda cx-5",
                        "forsokt_dag", (int) (idag - IceGenerationService.MISS_GILTIG_DAGAR))));

        assertThat(service.harFarskMiss("Ford ecosport")).isTrue();     // igår → hoppas över
        assertThat(service.harFarskMiss("Mazda cx-5")).isFalse();       // fönstret ute → prövas om
        assertThat(service.harFarskMiss("Volkswagen golf")).isFalse();  // aldrig prövad
    }

    @Test
    void missenGarBortNarModellenAndaGerEttArtal() {
        // Träffen är färskare än anteckningen, och en kvarglömd rad hade räknats i antalMissar
        // utan att betyda något — talet är till för att visa hur långt ifyllningen kommit.
        service.spara("Volkswagen golf", 2020);

        org.mockito.Mockito.verify(jdbc).update(
                "DELETE FROM ice_generation_miss WHERE model_name = ?", "Volkswagen golf");
    }

    @Test
    void tomningenTarMedMissarna() {
        // DELETE-endpointen används när källan gett fel data. Då är "vi prövade och fick inget"
        // lika opålitligt som årtalen, och kvarliggande missar hade dessutom spärrat just de
        // modeller ombyggnaden ska nå.
        service.rensa();

        org.mockito.Mockito.verify(jdbc).update("DELETE FROM ice_generation");
        org.mockito.Mockito.verify(jdbc).update("DELETE FROM ice_generation_miss");
    }

    @Test
    void listanGerTomListaOmTabellenInteGarAttLasa() {
        // Samma fail-soft som franArFor: en granskningsendpoint får aldrig fälla ett anrop.
        // Talet i cargo-coverage kommer från en egen query, så en tom lista mot ett positivt
        // antal där är i sig signalen om att något är fel.
        when(jdbc.queryForList(anyString())).thenThrow(new RuntimeException("ice_generation saknas"));

        assertThat(service.lista()).isEmpty();
    }

    @Test
    void missenBarSinOrsakSaEttDottUppslagGarAttSkiljaFranEttAvstaende() {
        /*
         * 2026-08-16: jobbet räknade alla nej i EN siffra, "utan träff". Den dolde att 139
         * parkerade missar nästan uteslutande var ett DÖTT UPPSLAG (chassikoden fällde varenda
         * generation) och inte vakten som avstod med flit. Med orsaken sparad syns skillnaden
         * direkt i granskningen: domineras svaret av ej-hittad är det vi som är trasiga.
         */
        service.noteraMiss("Lexus is", IceGenerationService.ORSAK_EJ_HITTAD);

        org.mockito.Mockito.verify(jdbc).update(
                "INSERT INTO ice_generation_miss(model_name, forsokt_dag, orsak) VALUES (?, ?, ?)",
                "Lexus is", (int) java.time.LocalDate.now().toEpochDay(),
                IceGenerationService.ORSAK_EJ_HITTAD);
    }

    @Test
    void orsaksrakningenTalerEnTabellUtanOrsakskolumn() {
        // Kolumnen läggs till i efterhand på en befintlig tabell, så rader från före 2026-08-16
        // saknar orsak. De ska räknas som "okand" och inte fälla granskningen.
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("orsak", "ej-hittad", "antal", 131L),
                Map.of("orsak", "okand", "antal", 8L)));

        assertThat(service.missarPerOrsak())
                .containsEntry("ej-hittad", 131L)
                .containsEntry("okand", 8L);
    }

    @Test
    void missarnaGarAttGlommaSaEnRattningFarVerkanDirekt() {
        /*
         * En miss är ett nej på frågan vi ställde, och rättar vi frågan är gamla nej inte längre
         * svar. Utan den här hade 2026-08-16 års rättningar legat verkningslösa till mitten av
         * september, eftersom MISS_GILTIG_DAGAR är 30.
         */
        when(jdbc.update("DELETE FROM ice_generation_miss")).thenReturn(139);

        assertThat(service.rensaMissar()).isEqualTo(139);
        // cachen måste släppas, annars ser nattjobbet samma missar som före rensningen
        assertThat(service.harFarskMiss("Lexus is")).isFalse();
    }
}
