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
}
