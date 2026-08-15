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
    void listanGerTomListaOmTabellenInteGarAttLasa() {
        // Samma fail-soft som franArFor: en granskningsendpoint får aldrig fälla ett anrop.
        // Talet i cargo-coverage kommer från en egen query, så en tom lista mot ett positivt
        // antal där är i sig signalen om att något är fel.
        when(jdbc.queryForList(anyString())).thenThrow(new RuntimeException("ice_generation saknas"));

        assertThat(service.lista()).isEmpty();
    }
}
