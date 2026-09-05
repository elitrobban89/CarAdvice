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
        // Provas pa en modell UTAN kurerad markor, dar arsmodellen ar det enda som skiljer raderna.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Skoda Enyaq", 585, 1710),
                new CargoSpec("Skoda Enyaq Coupe RS", 570, 1610)));
        var dto = medArsmodeller(Map.of()).formatForTitle("Skoda Enyaq (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(585);   // kortaste namnet vinner, som forut
    }

    @Test
    void markorenGallerAvenNarArsmodellstabellenAerTom() {
        // Markoren ar kod, inte data: den overlever ett tapp av cargo_spec_year. Provet star kvar
        // som paminnelse om att svaret pa "MG4 (2026)" INTE langre beror pa sidotabellen.
        tabellen();
        var dto = medArsmodeller(Map.of()).formatForTitle("MG4 (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(577);
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

    // --- Generationsmarkoren: tva generationer med SAMMA arsmodell ---

    /**
     * HELA MG4-tabellen som den ser ut i drift, inte tva rader.
     *
     * <p>Tvaradsfixturen ovan gav gront pa "MG4 (2026)" -> 577 medan driften svarade 388: de tre
     * XPOWER/Premium-raderna finns ocksa, bar OCKSA arsmodell 2026 (ev-database skriver "(MY26)"
     * och "Available since February 2026" pa bada karosserna) och vann pa kortast namn.
     * Uppmatt 2026-09-05: XPOWER 4 287 mm / 388 l, Urban 4 395 mm / 577 l.
     */
    private void helaMg4Tabellen() {
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("MG4", 363, 1177),
                new CargoSpec("MG MG4 Premium Extended Range", 388, 1164),
                new CargoSpec("MG MG4 Premium Long Range", 388, 1164),
                new CargoSpec("MG MG4 XPOWER", 388, 1164),
                new CargoSpec("MG MG4 Urban Comfort Long Range", 577, 1364),
                new CargoSpec("MG MG4 Urban Standard Range", 577, 1364)));
    }

    private static final Map<String, Integer> MG4_ARSMODELLER = Map.of(
            "mg mg4 premium extended range", 2026,
            "mg mg4 premium long range", 2026,
            "mg mg4 xpower", 2026,
            "mg mg4 urban comfort long range", 2026,
            "mg mg4 urban standard range", 2026);

    @Test
    void mg42026FarUrbanradenTrotsAttBadaGenerationernaAr2026() {
        helaMg4Tabellen();
        var dto = medArsmodeller(MG4_ARSMODELLER).formatForTitle("MG4 (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(577);
        assertThat(dto.cargoMaxLiters()).isEqualTo(1364);
    }

    @Test
    void titelSomSjalvNamnerEnVariantPaverkasInte() {
        // "MG4 XPOWER (2026)" har redan filtrerat bort Urban-raderna i pass 1. Markoren far
        // inte kunna dra over ett kort till en annan bil an den titeln namnger.
        helaMg4Tabellen();
        var dto = medArsmodeller(MG4_ARSMODELLER).formatForTitle("MG4 XPOWER (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(388);
    }

    @Test
    void utanArtalGallerBasradenAvenMedMarkor() {
        helaMg4Tabellen();
        var dto = medArsmodeller(MG4_ARSMODELLER).formatForTitle("MG4");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(363);
    }

    @Test
    void arForeMarkorensForstaArsmodellRorsInte() {
        helaMg4Tabellen();
        var dto = medArsmodeller(MG4_ARSMODELLER).formatForTitle("MG4 (2023)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(363);   // enda raden som inte ar daterad 2026
    }

    // --- Tom rad far inte skugga en ifylld (Enyaq, uppmatt 2026-09-05) ---

    @Test
    void tomRadSkuggarInteEnIfylldMedLangreNamn() {
        // Driften: "Skoda Enyaq" utan volym vann pa kortast namn over "Skoda Enyaq iV" (585 l),
        // och kortet fick INGEN volym alls fast siffran lag i tabellen.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Skoda Enyaq", null, null),
                new CargoSpec("Skoda Enyaq iV", 585, 1710)));
        var dto = medArsmodeller(Map.of()).formatForTitle("Skoda Enyaq (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(585);
    }

    @Test
    void ifylldAldreRadSlarTomRadMedRattArsmodell() {
        // Darfor star volymsteget FORE arsmodellen: en tom rad med ratt artal sager ingenting.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Skoda Enyaq", null, null),
                new CargoSpec("Skoda Enyaq iV", 585, 1710)));
        var dto = medArsmodeller(Map.of("skoda enyaq", 2026)).formatForTitle("Skoda Enyaq (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(585);
    }

    @Test
    void enSAMButanVolymFarFortfarandeMatcha() {
        // Regeln ar en RANGORDNING, inte ett filter: ar den tomma raden enda traffen ska svaret
        // vara null som forut - inte ett undantag och inte en annan bils volym.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Skoda Enyaq", null, null),
                new CargoSpec("Skoda Octavia", 600, 1700)));
        assertThat(medArsmodeller(Map.of()).formatForTitle("Skoda Enyaq (2026)")).isNull();
    }

    @Test
    void framtidaGenerationVinnerInteBaraForAttDenHarVolym() {
        // Steg (1) star kvar over volymsteget: en rad daterad EFTER kortets ar ar utesluten,
        // aven nar den ar den enda med en siffra.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("MG4", null, null),
                new CargoSpec("MG MG4 Urban Standard Range", 577, 1364)));
        assertThat(medArsmodeller(Map.of("mg mg4 urban standard range", 2026))
                .formatForTitle("MG4 (2023)")).isNull();
    }

    @Test
    void modellUtanMarkorValjerSomForut() {
        // Regeln far inte lacka till andra bilar: utan kurerad rad galler steg (4) kortast namn.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Skoda Enyaq", 585, 1710),
                new CargoSpec("Skoda Enyaq Coupe RS", 570, 1610)));
        var dto = medArsmodeller(Map.of(
                "skoda enyaq", 2026, "skoda enyaq coupe rs", 2026)).formatForTitle("Skoda Enyaq (2026)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(585);
    }
}
