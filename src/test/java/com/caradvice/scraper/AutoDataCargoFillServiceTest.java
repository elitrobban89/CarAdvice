package com.caradvice.scraper;

import com.caradvice.service.CargoSpecService;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.IceGenerationService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Generationsifyllningens arbetslista. Taket räknar FÖRSÖK, inte träffar, så vad listan
 * innehåller avgör hur långt fronten hinner — se {@link IceGenerationService#noteraMiss}.
 */
class AutoDataCargoFillServiceTest {

    private final AutoDataScraperService autoData = mock(AutoDataScraperService.class);
    private final CargoSpecService cargoSpecs = mock(CargoSpecService.class);
    private final IceConsumptionService iceConsumption = mock(IceConsumptionService.class);
    private final IceGenerationService iceGenerations = mock(IceGenerationService.class);

    private final AutoDataCargoFillService service =
            new AutoDataCargoFillService(autoData, cargoSpecs, iceConsumption, iceGenerations);

    private void modeller(String... namn) {
        when(iceConsumption.allModelNames()).thenReturn(new LinkedHashSet<>(List.of(namn)));
    }

    @Test
    void kandaNejProvasInteOmOchAterFrontenIngenBudget() {
        /*
         * Kärnan i felet 2026-08-15: listan filtrerade bara på harArtal och betades i
         * bokstavsordning med tak på 150 FÖRSÖK. Natten gav 32 årtal, alltså gick 118 försök
         * till modeller som missade — och utan spår efter en miss provades samma 118 om först
         * nästa natt, före varje otestad modell. Budgeten för nya modeller krymper då
         * 150 → 32 → ~7 → ~0 och fronten stannar runt märke 20 av 42. Volkswagen är märke 41
         * och Volvo 42, så Golf och XC60 hade aldrig fått ett årtal.
         */
        modeller("Alfa Romeo 159", "Volkswagen golf");
        when(iceGenerations.harFarskMiss("Alfa Romeo 159")).thenReturn(true);
        when(autoData.basgenerationsStartAr("Volkswagen golf")).thenReturn(2020);
        when(iceConsumption.effekterForModell(anyString())).thenReturn(Set.of());

        service.fyllGenerationsar();

        // den kända missen kostar inte ett enda försök — hela budgeten går till otestad modell
        verify(autoData, never()).basgenerationsStartAr("Alfa Romeo 159");
        verify(iceGenerations).spara("Volkswagen golf", 2020);
    }

    @Test
    void utebliventArtalAntecknasSomMiss() {
        modeller("Ford ecosport");
        when(autoData.basgenerationsStartAr("Ford ecosport")).thenReturn(null);

        service.fyllGenerationsar();

        verify(iceGenerations).noteraMiss("Ford ecosport");
        verify(iceGenerations, never()).spara(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void felGenerationAntecknasOcksaSomMiss() {
        // CX-5-fallet: auto-datas senaste generation (141 hk) delar ingen effekt med vår CSV
        // (150-230 hk), alltså två olika bilar. Avståendet är rätt — men det är ett svar vi
        // förstått, och att fråga om det varje natt är rent slöseri med budgeten.
        modeller("Mazda cx-5");
        when(autoData.basgenerationsStartAr("Mazda cx-5")).thenReturn(2025);
        when(iceConsumption.effekterForModell("Mazda cx-5")).thenReturn(Set.of(150, 194, 230));
        when(autoData.motorerForBil(eq("Mazda cx-5"), any())).thenReturn(List.of(
                new AutoDataScraperService.MotorAlternativ("2.5 e-Skyactiv G", 141, "2025-", "/x")));

        service.fyllGenerationsar();

        verify(iceGenerations).noteraMiss("Mazda cx-5");
        verify(iceGenerations, never()).spara(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void naetverksfelAntecknasINTESomMiss() {
        // Ett undantag är en trasig hämtning, inte ett nej. Antecknat som miss hade ett
        // avbrott mitt i natten låst ute modellen i 30 dagar — och en död sajt (2026-08-13,
        // då varenda modell missade) hade fryst hela tabellen.
        modeller("Volvo xc60");
        when(autoData.basgenerationsStartAr("Volvo xc60")).thenThrow(new RuntimeException("timeout"));

        service.fyllGenerationsar();

        verify(iceGenerations, never()).noteraMiss(anyString());
    }
}
