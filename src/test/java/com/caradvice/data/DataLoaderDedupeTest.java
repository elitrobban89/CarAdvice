package com.caradvice.data;

import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.repository.SafetyRatingRepository;
import com.caradvice.scraper.EvDatabaseScraperService;
import com.caradvice.scraper.WebInsightScraperService;
import com.caradvice.service.FeedbackService;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.NewCarPriceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataLoaderDedupeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EvSpecRepository evSpecRepo = mock(EvSpecRepository.class);

    private DataLoader loader() {
        return new DataLoader(jdbc,
                mock(ExpertInsightRepository.class), mock(SafetyRatingRepository.class),
                evSpecRepo, mock(CargoSpecRepository.class),
                mock(NewCarPriceService.class), mock(FeedbackService.class),
                mock(WebInsightScraperService.class), mock(IceConsumptionService.class),
                mock(com.caradvice.service.CarVideoService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aliasraderRaderasMenRiktigaVarianterBehalls() {
        // ev-databases P3/P5/P8 är samma EX30-varianter som våra egna rader, med netto- i
        // stället för bruttokapacitet. De var osynliga så länge närliggande batterier slogs
        // ihop; med en rad per variant blev bilen nio rader för fyra bilar.
        com.caradvice.model.EvSpec p3 = new com.caradvice.model.EvSpec("Volvo EX30 P3", 11.0, 153.0, 49.0, 339, 0);
        com.caradvice.model.EvSpec p5lr = new com.caradvice.model.EvSpec("Volvo EX30 P5 Long Range", 11.0, 153.0, 65.0, 476, 0);
        com.caradvice.model.EvSpec riktig = new com.caradvice.model.EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 0);
        org.mockito.Mockito.when(evSpecRepo.findAll()).thenReturn(java.util.List.of(p3, p5lr, riktig));

        loader().dedupeEvSpecs();

        ArgumentCaptor<Iterable<com.caradvice.model.EvSpec>> raderade = ArgumentCaptor.forClass(Iterable.class);
        verify(evSpecRepo).deleteAll(raderade.capture());
        assertThat(raderade.getValue()).containsExactly(p3, p5lr);
    }

    @Test
    void behallerNyasteRadenPerBilnamn() {
        loader().dedupeEvSpecs();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());

        // MAX(id) — inte MIN: den högsta id:n är den kopia nattsynkens nameMap får sist ur
        // findAll() och därmed den enda som faktiskt hållits uppdaterad. MIN hade bevarat
        // en frusen rad och kastat den färska.
        assertThat(sql.getValue()).contains("DELETE FROM ev_spec")
                .contains("MAX(id)").contains("GROUP BY car_name")
                .doesNotContain("MIN(id)");
    }

    @Test
    void stadarForeIndexetSkapas() {
        loader().dedupeEvSpecs();

        // Ordningen är inte kosmetisk: CREATE UNIQUE INDEX misslyckas mot en tabell som
        // fortfarande har dubbletter, och då hade skyddet aldrig kommit på plats.
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.anyString());
        order.verify(jdbc).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void uteslutnaMarkenRaderasEfterIndexet() {
        loader().dedupeEvSpecs();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
        // dubblettstädningen är update(String) utan argument — den här överlagringen
        // (update med parameter) träffas bara av märkesraderingarna, en per märke
        verify(jdbc, org.mockito.Mockito.times(EvDatabaseScraperService.EXCLUDED_BRANDS.size()))
                .update(sql.capture(), arg.capture());

        assertThat(arg.getAllValues()).contains("TOGG%", "Jaecoo%", "Changan%");
        // ILIKE finns bara i Postgres — portabiliteten är avsiktlig
        assertThat(sql.getAllValues()).noneMatch(s -> s.contains("ILIKE"));
    }

    @Test
    void ev6PriserTackerAllaSexVarianter() {
        // Raderna lades in med pris 0 i tron att nattsynken skulle fylla dem, men findMatch
        // matchar bara kortare DB-namn än det skrapade — "84 kWh"-suffixet gjorde träff omöjlig
        assertThat(DataLoader.EV6_PRISER).hasSize(6);
        assertThat(DataLoader.EV6_PRISER.keySet())
                .allMatch(n -> n.startsWith("Kia EV6 "));
        assertThat(DataLoader.EV6_PRISER.values()).allMatch(p -> p > 50_000);
        // AWD ska kosta mer än 2WD i samma generation, GT mest av allt
        assertThat(DataLoader.EV6_PRISER.get("Kia EV6 Long Range AWD 84 kWh"))
                .isGreaterThan(DataLoader.EV6_PRISER.get("Kia EV6 Long Range 2WD 84 kWh"));
        assertThat(DataLoader.EV6_PRISER.get("Kia EV6 GT 77.4 kWh"))
                .isGreaterThan(DataLoader.EV6_PRISER.get("Kia EV6 Long Range AWD 77.4 kWh"));
    }

    @Test
    void indexetArUnikOchIdempotent() {
        loader().dedupeEvSpecs();

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(ddl.capture());

        // IF NOT EXISTS: DataLoader kör vid varje uppstart, inte bara första gången
        assertThat(ddl.getValue()).contains("CREATE UNIQUE INDEX IF NOT EXISTS")
                .contains("ev_spec").contains("car_name");
    }
}
