package com.caradvice.data;

import com.caradvice.repository.CargoSpecRepository;
import com.caradvice.repository.EvSpecRepository;
import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.repository.SafetyRatingRepository;
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

    private DataLoader loader() {
        return new DataLoader(jdbc,
                mock(ExpertInsightRepository.class), mock(SafetyRatingRepository.class),
                mock(EvSpecRepository.class), mock(CargoSpecRepository.class),
                mock(NewCarPriceService.class), mock(FeedbackService.class),
                mock(WebInsightScraperService.class), mock(IceConsumptionService.class));
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
    void indexetArUnikOchIdempotent() {
        loader().dedupeEvSpecs();

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(ddl.capture());

        // IF NOT EXISTS: DataLoader kör vid varje uppstart, inte bara första gången
        assertThat(ddl.getValue()).contains("CREATE UNIQUE INDEX IF NOT EXISTS")
                .contains("ev_spec").contains("car_name");
    }
}
