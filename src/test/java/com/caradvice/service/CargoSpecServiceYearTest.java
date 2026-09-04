package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Arsmodellen i cargo_spec_year: vilken rad ett bilkort far nar tabellen bar TVA generationer.
 *
 * <p>MG4 ar fallet regeln byggdes for (uppmatt 2026-09-04 mot ev-databases egna sidor):
 * {@code MG4} 363 l ar forsta generationen (4 287 mm), medan {@code MG4 Urban} 577 l ar
 * MY26-bilen pa 4 395 mm — en annan bil. Utan arsmodell valde matchningen den forsta raden i
 * tabellordningen, och "MG4 (2026)" fick 363 l.
 */
class CargoSpecServiceYearTest {

    private final CargoSpecRepository repo = mock(CargoSpecRepository.class);

    /** jdbc = null: arsmodellerna matas in direkt i stallet for genom sidotabellen. */
    private CargoSpecService medArsmodeller(Map<String, Integer> ar) {
        return new CargoSpecService(repo, null) {
            @Override
            Map<String, Integer> arsmodeller() { return ar; }
        };
    }

    private void tabellen() {
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("MG4", 363, 1177),
                new CargoSpec("MG MG4 Urban Standard Range", 577, 1364)));
    }

    @Test
    void gammalTitelFarInteEnKommandeGenerationsVolym() {
        tabellen();
        var dto = medArsmodeller(Map.of("mg mg4 urban standard range", 2026)).formatForTitle("MG4 (2023)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(363);
    }

    @Test
    void nyTitelFarNyaGenerationensVolym() {
        tabellen();
        var dto = medArsmodeller(Map.of("mg mg4 urban standard range", 2026)).formatForTitle("MG4 (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(577);
    }

    @Test
    void utanArtalITitelnVinnerDenODATERADEBasraden() {
        // Ett kort utan arsmodell ska svara som fore regeln: basraden, inte den nyaste varianten.
        tabellen();
        var dto = medArsmodeller(Map.of("mg mg4 urban standard range", 2026)).formatForTitle("MG4");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(363);
    }

    @Test
    void utanArsmodellstabellSvararMatchningenSomForut() {
        // Tom map = ingen rad ar daterad. Ett DB-fel far gora volymerna odaterade, aldrig osynliga.
        tabellen();
        var dto = medArsmodeller(Map.of()).formatForTitle("MG4 (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(363);   // kortaste namnet vinner, som forut
    }

    @Test
    void generalistradenDateras_INTE_avEnVariantsida() {
        // Skrapad "Kia EV6 Long Range 2WD" fyller raden "Kia EV6". Daterade vi DEN med 2026 hade
        // ett EV6-kort fran 2022 stangts ute fran sin egen bagagevolym.
        CargoSpec generalist = new CargoSpec("Kia EV6", null, null);
        when(repo.findAll()).thenReturn(List.of(generalist));
        List<String> skrivna = new ArrayList<>();
        CargoSpecService service = new CargoSpecService(repo, null) {
            @Override
            void sattArsmodell(String carName, int year) { skrivna.add(carName + "=" + year); }
        };

        assertThat(service.fillFromScrape("Kia EV6 Long Range 2WD", 490, 1300, 2026)).isTrue();
        assertThat(generalist.getCargoLiters()).isEqualTo(490);
        assertThat(skrivna).isEmpty();
    }

    @Test
    void egenRadFarSittArtalAvenNarVolymenRedanFinns() {
        // Backfyllningen: raden finns med volym sedan tidigare och ska anda kunna dateras,
        // annars hade tabellen behovt tommas for att arsmodellerna skulle komma in.
        CargoSpec variant = new CargoSpec("MG MG4 Urban Standard Range", 577, 1364);
        when(repo.findAll()).thenReturn(List.of(variant));
        List<String> skrivna = new ArrayList<>();
        CargoSpecService service = new CargoSpecService(repo, null) {
            @Override
            void sattArsmodell(String carName, int year) { skrivna.add(carName + "=" + year); }
        };

        assertThat(service.fillFromScrape("MG MG4 Urban Standard Range", 577, 1364, 2026)).isFalse();
        assertThat(skrivna).containsExactly("MG MG4 Urban Standard Range=2026");
    }
}
