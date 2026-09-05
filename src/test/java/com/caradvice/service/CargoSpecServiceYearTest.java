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

    // --- cargo_spec_fuel: drivmedlet skrivs av den kalla som fyllde raden ---

    @Test
    void drivmedletSkrivsAvenNarRadenMatchadesUnderEttKORTARENamn() {
        // Till skillnad fran arsmodellen: "Kia EV6" ar en elbil oavsett vilken variantsida som
        // fyllde raden. Artalet varierar mellan generationer, drivmedlet gor det inte.
        CargoSpec generalist = new CargoSpec("Kia EV6", null, null);
        when(repo.findAll()).thenReturn(List.of(generalist));
        List<String> skrivna = new ArrayList<>();
        CargoSpecService service = new CargoSpecService(repo, null) {
            @Override
            void sattDrivmedel(String carName, String fuel) { skrivna.add(carName + "=" + fuel); }
            @Override
            void sattArsmodell(String carName, int year) { /* provas pa annat hall */ }
        };

        assertThat(service.fillFromScrape("Kia EV6 Long Range 2WD", 490, 1300, 2026, "el")).isTrue();
        assertThat(skrivna).containsExactly("Kia EV6=el");
    }

    @Test
    void drivmedletSkrivsAvenNarVolymenRedanFinns() {
        // Backfyllningen: raderna bar volym sedan lange, och tabellen skulle annars behova tommas
        // for att drivmedlet skulle komma in.
        CargoSpec fylld = new CargoSpec("Kia EV6", 490, 1300);
        when(repo.findAll()).thenReturn(List.of(fylld));
        List<String> skrivna = new ArrayList<>();
        CargoSpecService service = new CargoSpecService(repo, null) {
            @Override
            void sattDrivmedel(String carName, String fuel) { skrivna.add(carName + "=" + fuel); }
        };

        assertThat(service.fillFromScrape("Kia EV6 Long Range 2WD", 490, 1300, 0, "el")).isFalse();
        assertThat(skrivna).containsExactly("Kia EV6=el");
    }

    @Test
    void utanDrivmedelFranKallanSkrivsIngenting() {
        CargoSpec rad = new CargoSpec("Volvo XC60", null, null);
        when(repo.findAll()).thenReturn(List.of(rad));
        List<String> skrivna = new ArrayList<>();
        CargoSpecService service = new CargoSpecService(repo, null) {
            @Override
            void sattDrivmedel(String carName, String fuel) {
                if (fuel != null) skrivna.add(carName + "=" + fuel);
            }
        };

        assertThat(service.fillFromScrape("Volvo XC60 B4 AWD", 483, 1410)).isTrue();
        assertThat(skrivna).isEmpty();
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

    // --- Tom rad far vinna: grannraden ar ofta en ANNAN BIL (uppmatt 2026-09-05) ---

    @Test
    void tomRadVinnerHellreAnAttGeEnANNANBilsVolym() {
        // Ett steg "ifylld rad slar tom rad" provades och BACKADES. Tystnad ar ratt svar nar
        // tabellen inte kanner bilen; raden bredvid ar ofta en annan modell.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Ford Mustang", null, null),
                new CargoSpec("Ford Mustang Mach-E", 402, 1420)));
        assertThat(medArsmodeller(Map.of()).formatForTitle("Ford Mustang (2021)")).isNull();
    }

    // --- Pass 1 kraver ORD, inte substrang (uppmatt mot 1 837 bilnamn 2026-09-05) ---

    @Test
    void substrangIEttAnnatModellnamnGerIngenTraff() {
        // "tt" finns i "quattro". I drift gav det Audi TT e-tron GT:s 405 l.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Audi TT", null, null),
                new CargoSpec("Audi e-tron GT quattro", 405, null)));
        assertThat(medArsmodeller(Map.of()).formatForTitle("Audi TT (2018)")).isNull();
    }

    @Test
    void elbilensRadFarInteSvaraForForbranningsbilen() {
        // "x3" ar en substrang av "ix3": BMW X3 fick iX3:ans 510 l i drift.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("BMW X3", 550, 1600),
                new CargoSpec("BMW iX3", 510, 1560)));
        var dto = medArsmodeller(Map.of()).formatForTitle("BMW X3 (2022)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(550);
    }

    @Test
    void bokstavsklassFarSinEgenRad() {
        // "a" ar en substrang av "a klass" OCH av "c klass": alla tre Mercedes-klasserna fick
        // A-Klass 370 l i drift, aven nar deras egna rader fanns i tabellen.
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Mercedes A-Klass", 370, 1210),
                new CargoSpec("Mercedes C-klass", 455, 1510)));
        var dto = medArsmodeller(Map.of()).formatForTitle("Mercedes C-klass (2023)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(455);
    }

    @Test
    void titelMedTrimordGarFortfarandeViaPass2() {
        // Pass 2 ar OFORANDRAT och bar de verkliga korttitlarna: raden "Volvo XC60" nas av
        // "Volvo XC60 B4 AWD (2023)" fast titeln har ord raden saknar.
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo XC60", 483, 1410)));
        var dto = medArsmodeller(Map.of()).formatForTitle("Volvo XC60 B4 AWD (2023)");
        assertThat(dto).isNotNull();
        assertThat(dto.cargoLiters()).isEqualTo(483);
    }

    @Test
    void grannradMedHELTORDFarInteHellerSvaraForEnAnnanModell() {
        // Ordgrans raddar inte regeln: "Ford Mustang" star som hela ord i "Ford Mustang Mach-E",
        // och det ar anda en annan bil (402 l ur en elbil pa en Mustang-kupe).
        when(repo.findAll()).thenReturn(List.of(
                new CargoSpec("Ford Mustang", null, null),
                new CargoSpec("Ford Mustang Mach-E", 402, 1420)));
        assertThat(medArsmodeller(Map.of()).formatForTitle("Ford Mustang (2021)")).isNull();
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
