package com.caradvice.service;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tester för findForCarTitle — den publika insiktslistan per bilkort
 * (konsumeras av GET /api/insights). Repo mockas.
 */
class ExpertInsightServiceCarLookupTest {

    private final ExpertInsightRepository repo = mock(ExpertInsightRepository.class);
    private final ExpertInsightService service = new ExpertInsightService(repo);

    private static ExpertInsight insight(String expert, String make, String model, String text, Integer rating) {
        return new ExpertInsight(expert, make, model, "el", "kombi", text, rating);
    }

    @Test
    void marketMasteFinnasITiteln() {
        when(repo.findAll()).thenReturn(List.of(
                insight("Teknikens Värld", "Tesla", "Model 3", "Toppbetyg i test.", 9)));

        assertThat(service.findForCarTitle("Volvo XC60 (2020)")).isEmpty();
        assertThat(service.findForCarTitle("Tesla Model 3 (2021)")).hasSize(1);
    }

    @Test
    void insiktOmAnnanModellAvSammaMarkeUtesluts() {
        when(repo.findAll()).thenReturn(List.of(
                insight("Vi Bilägare", "Tesla", "Model S", "Dyr i inköp.", null),
                insight("Teknikens Värld", "Tesla", "Model 3", "Bäst i klassen.", 9)));

        List<Map<String, Object>> result = service.findForCarTitle("Tesla Model 3 (2021)");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("insight")).isEqualTo("Bäst i klassen.");
    }

    @Test
    void modellspecifikaPrioriterasForeMarkesgenerellaOchMax3() {
        when(repo.findAll()).thenReturn(List.of(
                insight("M Sverige", "Tesla", null, "Generellt om märket 1.", null),
                insight("M Sverige", "Tesla", null, "Generellt om märket 2.", null),
                insight("Teknikens Värld", "Tesla", "Model 3", "Modellspecifik 1.", 8),
                insight("Vi Bilägare", "Tesla", "Model 3", "Modellspecifik 2.", 7),
                insight("car.info", "Tesla", "Model 3", "Modellspecifik 3.", 9)));

        List<Map<String, Object>> result = service.findForCarTitle("Tesla Model 3 (2021)");
        assertThat(result).hasSize(3);
        // Alla tre platser tas av modellspecifika — de generella trängs ut
        assertThat(result).allSatisfy(m ->
                assertThat((String) m.get("insight")).startsWith("Modellspecifik"));
    }

    @Test
    void ratingMedNarDenFinnsOchExpertnamnFallerTillbaka() {
        when(repo.findAll()).thenReturn(List.of(
                insight(null, "Kia", "EV6", "Vann årets elbilstest.", 10)));

        List<Map<String, Object>> result = service.findForCarTitle("Kia EV6 (2022)");
        assertThat(result.get(0).get("expert")).isEqualTo("Bilexpert");
        assertThat(result.get(0).get("rating")).isEqualTo(10);
    }

    @Test
    void tomEllerNullTitelGerTomLista() {
        assertThat(service.findForCarTitle(null)).isEmpty();
        assertThat(service.findForCarTitle("  ")).isEmpty();
    }
}
