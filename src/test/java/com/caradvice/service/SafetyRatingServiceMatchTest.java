package com.caradvice.service;

import com.caradvice.model.SafetyRating;
import com.caradvice.repository.SafetyRatingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tester för formatForTitle — Euro NCAP-radens titelmatchning. Modellnamn måste
 * väljas så att contains-matchningen inte träffar fel bil (därför "MG4", inte "4").
 */
class SafetyRatingServiceMatchTest {

    private final SafetyRatingRepository repo = mock(SafetyRatingRepository.class);
    private final SafetyRatingService service = new SafetyRatingService(repo);

    @Test
    void mg4MatcharUtanAttTraffaAndraMgModeller() {
        when(repo.findAll()).thenReturn(List.of(
                new SafetyRating("MG", "MG4", 2022, 5, 83, 80, 75, 78)));

        assertThat(service.formatForTitle("MG4 (2023)"))
                .contains("★★★★★").contains("83% vuxna").contains("Euro NCAP 2022");
        assertThat(service.formatForTitle("MG ZS EV (2022)")).isNull();
        assertThat(service.formatForTitle("MG5 (2023)")).isNull();
    }

    @Test
    void renault5ETechMatcharMenInteZoe() {
        when(repo.findAll()).thenReturn(List.of(
                new SafetyRating("Renault", "5 E-Tech", 2024, 4, 80, 80, 76, 68)));

        assertThat(service.formatForTitle("Renault 5 E-Tech (2024)"))
                .contains("★★★★☆").contains("Euro NCAP 2024");
        assertThat(service.formatForTitle("Renault Zoe ZE50 (2021)")).isNull();
    }

    @Test
    void bilUtanBetygGerNull() {
        when(repo.findAll()).thenReturn(List.of(
                new SafetyRating("MG", "MG4", 2022, 5, 83, 80, 75, 78)));

        // Euro NCAP har aldrig krocktestat nya ë-C3 — den ska visa "–", inte fel bils betyg
        assertThat(service.formatForTitle("Citroën ë-C3 (2024)")).isNull();
        assertThat(service.formatForTitle(null)).isNull();
    }
}
