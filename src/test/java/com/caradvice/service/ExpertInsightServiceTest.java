package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tester för RAG-kontextbygget: vilka expertinsikter som väljs ut
 * och hur de formateras innan de skickas med i AI-prompten.
 */
@ExtendWith(MockitoExtension.class)
class ExpertInsightServiceTest {

    @Mock
    private ExpertInsightRepository repo;

    private ExpertInsightService service() {
        return new ExpertInsightService(repo);
    }

    private static CarPreferences prefs(String category, String fuelType) {
        return new CarPreferences(300_000, category, false, 15_000, "pendling",
                4, true, fuelType, "automat", "köp", null);
    }

    private static ExpertInsight insikt(String expert, String make, String model, String text, Integer rating) {
        return new ExpertInsight(expert, make, model, "el", "suv", text, rating);
    }

    // --- buildExpertContext (rekommendationsflödet) ---

    @Test
    void tomtResultatGerTomStrang() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of());
        assertThat(service().buildExpertContext(prefs("suv", "el"))).isEmpty();
    }

    @Test
    void begransasTillTvaInsikter() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of(
                insikt("Vi Bilägare", "Volvo", "XC40", "Insikt 1", 7),
                insikt("Vi Bilägare", "Kia", "EV6", "Insikt 2", 8),
                insikt("Vi Bilägare", "Tesla", "Model Y", "Insikt 3", 9)));

        String ctx = service().buildExpertContext(prefs("suv", "el"));
        assertThat(ctx).contains("Insikt 1").contains("Insikt 2").doesNotContain("Insikt 3");
    }

    @Test
    void formateringInnehallerBilBetygOchKalla() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of(
                insikt("Teknikens Värld", "Volvo", "XC40", "Bra köp begagnad", 8)));

        String ctx = service().buildExpertContext(prefs("suv", "el"));
        assertThat(ctx).contains("Volvo XC40: Bra köp begagnad [8/10] (Teknikens Värld)");
    }

    @Test
    void namngivenExpertVisasMedSittNamn() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of(
                insikt("M Sverige", "Volvo", "XC40", "Insikt", null)));

        String ctx = service().buildExpertContext(prefs("suv", "el"));
        assertThat(ctx).contains("(M Sverige)");
    }

    @Test
    void saknatExpertnamnBlirBilexpert() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of(
                insikt(null, "Volvo", "XC40", "Insikt", null)));

        String ctx = service().buildExpertContext(prefs("suv", "el"));
        assertThat(ctx).contains("(Bilexpert)");
    }

    @Test
    void spelarIngenRollSomBransleAnvanderKategorin() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("kombi", "kombi")).thenReturn(List.of());
        service().buildExpertContext(prefs("kombi", "spelar ingen roll"));
        verify(repo).findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("kombi", "kombi");
    }

    // --- buildChatExpertContext (chattflödet) ---

    @Test
    void chattInsiktKravsAttMarketNamns() {
        when(repo.findAll()).thenReturn(List.of(
                insikt("Vi Bilägare", "Volvo", "XC40", "Volvoinsikt", 7),
                insikt("Vi Bilägare", "Kia", "EV6", "Kiainsikt", 8)));

        String ctx = service().buildChatExpertContext(List.of("Vad tycker du om volvo xc40?"));
        assertThat(ctx).contains("Volvoinsikt").doesNotContain("Kiainsikt");
    }

    @Test
    void generellaInsikterUtanMarkeTasAldrigMed() {
        when(repo.findAll()).thenReturn(List.of(
                insikt("Vi Bilägare", null, null, "Generell insikt", null)));

        String ctx = service().buildChatExpertContext(List.of("Vilken bil ska jag köpa?"));
        assertThat(ctx).isEmpty();
    }

    @Test
    void chattBegransasTillTreInsikter() {
        when(repo.findAll()).thenReturn(List.of(
                insikt("Vi Bilägare", "Volvo", "XC40", "Insikt 1", 7),
                insikt("Vi Bilägare", "Volvo", "XC60", "Insikt 2", 7),
                insikt("Vi Bilägare", "Volvo", "XC90", "Insikt 3", 7),
                insikt("Vi Bilägare", "Volvo", "EX30", "Insikt 4", 7)));

        String ctx = service().buildChatExpertContext(List.of("Berätta om Volvo"));
        assertThat(ctx).contains("Insikt 3").doesNotContain("Insikt 4");
    }

    // --- importCsv ---

    @Test
    void importHopparOverHeaderKommentarerOchTommaRader() {
        String csv = """
                car_make,car_model,fuel_type,category,insight,rating
                # kommentar

                Volvo,XC60,diesel,suv,Bra dragkraft,8
                Kia,EV6,el,suv,Snabbladdar imponerande,9
                """;

        int count = service().importCsv(csv, "Teknikens Värld");
        assertThat(count).isEqualTo(2);
        verify(repo, times(2)).save(any(ExpertInsight.class));
    }

    @Test
    void importParsarFaltOchBetyg() {
        ArgumentCaptor<ExpertInsight> captor = ArgumentCaptor.forClass(ExpertInsight.class);

        service().importCsv("Volvo,XC60,diesel,suv,Bra dragkraft,8", "Teknikens Värld");

        verify(repo).save(captor.capture());
        ExpertInsight sparad = captor.getValue();
        assertThat(sparad.getCarMake()).isEqualTo("Volvo");
        assertThat(sparad.getCarModel()).isEqualTo("XC60");
        assertThat(sparad.getInsight()).isEqualTo("Bra dragkraft");
        assertThat(sparad.getRating()).isEqualTo(8);
        assertThat(sparad.getExpertName()).isEqualTo("Teknikens Värld");
    }

    @Test
    void importMedOgiltigtBetygGerNullRating() {
        ArgumentCaptor<ExpertInsight> captor = ArgumentCaptor.forClass(ExpertInsight.class);

        service().importCsv("Volvo,XC60,diesel,suv,Bra dragkraft,inte-ett-tal", "Vi Bilägare");

        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getRating()).isNull();
    }

    @Test
    void importHopparOverRaderMedForFaFalt() {
        int count = service().importCsv("Volvo,XC60,diesel", "Vi Bilägare");
        assertThat(count).isZero();
    }
}
