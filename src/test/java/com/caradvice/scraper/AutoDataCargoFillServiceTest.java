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

    /** Ett prov som svarar "årtalet är bevisat", som scrapern gör när en generation bär vår hk. */
    private static AutoDataScraperService.Artalsprov traff(int franAr) {
        return new AutoDataScraperService.Artalsprov(franAr, AutoDataScraperService.Provutfall.TRAFF, "gen");
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
        when(iceConsumption.effekterForModell(anyString())).thenReturn(Set.of());
        when(autoData.artalMedEffektprov(eq("Volkswagen golf"), any(), any())).thenReturn(traff(2020));

        service.fyllGenerationsar();

        // den kända missen kostar inte ett enda försök — hela budgeten går till otestad modell
        verify(autoData, never()).artalMedEffektprov(eq("Alfa Romeo 159"), any(), any());
        verify(iceGenerations).spara("Volkswagen golf", 2020);
    }

    @Test
    void utebliventArtalAntecknasSomMiss() {
        modeller("Ford ecosport");
        when(autoData.artalMedEffektprov(eq("Ford ecosport"), any(), any())).thenReturn(
                new AutoDataScraperService.Artalsprov(null, AutoDataScraperService.Provutfall.OPROVAD, null));

        service.fyllGenerationsar();

        verify(iceGenerations).noteraMiss("Ford ecosport", IceGenerationService.ORSAK_EJ_HITTAD);
        verify(iceGenerations, never()).spara(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void felGenerationAntecknasOcksaSomMiss() {
        // CX-5-fallet: ingen av auto-datas generationer delar effekt med vår CSV (150-230 hk),
        // alltså två olika bilar. Avståendet är rätt — men det är ett svar vi förstått, och att
        // fråga om det varje natt är rent slöseri med budgeten. Sedan 2026-08-19 betyder ett nej
        // dessutom att ALLA prövade generationer sagt nej, inte bara den nyaste.
        modeller("Mazda cx-5");
        when(iceConsumption.effekterForModell("Mazda cx-5")).thenReturn(Set.of(150, 194, 230));
        when(autoData.artalMedEffektprov(eq("Mazda cx-5"), any(), any())).thenReturn(
                new AutoDataScraperService.Artalsprov(null, AutoDataScraperService.Provutfall.INGEN_TRAFF, null));

        service.fyllGenerationsar();

        verify(iceGenerations).noteraMiss("Mazda cx-5", IceGenerationService.ORSAK_VAKTEN_AVSTOD);
        verify(iceGenerations, never()).spara(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void naetverksfelAntecknasINTESomMiss() {
        // Ett undantag är en trasig hämtning, inte ett nej. Antecknat som miss hade ett
        // avbrott mitt i natten låst ute modellen i 30 dagar — och en död sajt (2026-08-13,
        // då varenda modell missade) hade fryst hela tabellen.
        modeller("Volvo xc60");
        when(autoData.artalMedEffektprov(eq("Volvo xc60"), any(), any())).thenThrow(new RuntimeException("timeout"));

        service.fyllGenerationsar();

        verify(iceGenerations, never()).noteraMiss(anyString(), anyString());
    }

    @Test
    void hamtningsfelIMotorlistanParkerarInteModellen() {
        /*
         * Kärnan i felet 2026-08-16: AutoDataScraperService.hamta svalde varje undantag och
         * lämnade tomsträng, så ett 429-svar kom hit som ett prydligt null — omöjligt att skilja
         * från ett svar vi förstått. Modellen parkerades då i 30 dagar för ett nätverksfel.
         * Nu kastar hamta i stället, och löftet i catch-grenen går att hålla.
         */
        modeller("Lexus is");
        when(autoData.artalMedEffektprov(eq("Lexus is"), any(), any()))
                .thenThrow(new AutoDataScraperService.HamtningsFel("/en/lexus-is", new RuntimeException("429")));

        service.fyllGenerationsar();

        verify(iceGenerations, never()).noteraMiss(anyString(), anyString());
        verify(iceGenerations, never()).spara(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void oprovatArtalSparasAndaOchParkerarInteModellen() {
        /*
         * OPRÖVAD betyder att ingen motorlista gick att läsa — antingen för att vi saknar egna
         * hästkrafter eller för att sidorna inte svarade. Det är inget nej, och modellen får inte
         * parkeras för det: en tom motorlista drabbar just de modeller som räddas av
         * karosstoleransen (Audi RS4, Audi TT, hela BMW-serien), alltså precis dem rättningen
         * släppte in.
         */
        modeller("Audi rs4");
        when(iceConsumption.effekterForModell("Audi rs4")).thenReturn(Set.of(450));
        when(autoData.artalMedEffektprov(eq("Audi rs4"), any(), any())).thenReturn(
                new AutoDataScraperService.Artalsprov(2017, AutoDataScraperService.Provutfall.OPROVAD, "RS4 (B9)"));

        service.fyllGenerationsar();

        verify(iceGenerations).spara("Audi rs4", 2017);
        verify(iceGenerations, never()).noteraMiss(anyString(), anyString());
    }

    @Test
    void varaEgnaHastkrafterSkickasMedTillProvet() {
        // Provet kan inte göra sitt jobb utan vår motorlista: det är just jämförelsen mot den som
        // skiljer "rätt generation" från "senaste generationen". Skickas en tom mängd blir varje
        // årtal OPRÖVAT och vakten är i praktiken avstängd.
        modeller("BMW 520d");
        when(iceConsumption.effekterForModell("BMW 520d")).thenReturn(Set.of(190));
        when(autoData.artalMedEffektprov(eq("BMW 520d"), eq(Set.of(190)), any())).thenReturn(traff(2017));

        service.fyllGenerationsar();

        verify(iceGenerations).spara("BMW 520d", 2017);
    }

    @Test
    void familjensHelaMotorlistaSkickasMedSaSyskonenKanAvgora() {
        /*
         * 730d-fallet: samma beteckning med samma effekt i två generationer går inte att skilja åt
         * med en enda rad, och nyast vann — 730d blev 2022 medan resten av sjuan blev 2015.
         * Syskonens hästkrafter är det som avgör, så de måste nå fram till provet. Familjen byggs
         * ur HELA modellistan och inte ur arbetslistan: de flesta syskon är redan ifyllda och
         * hade annars saknats just när de behövs.
         */
        modeller("BMW 730d");
        when(iceConsumption.allModelNames())
                .thenReturn(new LinkedHashSet<>(List.of("BMW 730d", "BMW 730i", "BMW 740i", "Volvo xc60")));
        when(iceGenerations.harArtal("BMW 730i")).thenReturn(true);      // syskonen är redan klara
        when(iceGenerations.harArtal("BMW 740i")).thenReturn(true);
        when(iceGenerations.harArtal("Volvo xc60")).thenReturn(true);
        when(iceConsumption.effekterForModell("BMW 730d")).thenReturn(Set.of(286));
        when(iceConsumption.effekterForModell("BMW 730i")).thenReturn(Set.of(265));
        when(iceConsumption.effekterForModell("BMW 740i")).thenReturn(Set.of(340));
        when(iceConsumption.effekterForModell("Volvo xc60")).thenReturn(Set.of(197));
        when(autoData.artalMedEffektprov(eq("BMW 730d"), any(), any())).thenReturn(traff(2015));

        service.fyllGenerationsar();

        // Vår egen rad oförändrad, familjen = hela sjuan — men INTE Volvon, som är en annan sida.
        verify(autoData).artalMedEffektprov("BMW 730d", Set.of(286), Set.of(286, 265, 340));
        verify(iceGenerations).spara("BMW 730d", 2015);
    }
}
