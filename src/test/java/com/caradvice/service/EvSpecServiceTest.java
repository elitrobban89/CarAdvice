package com.caradvice.service;

import com.caradvice.model.EvSpec;
import com.caradvice.model.EvSpecDto;
import com.caradvice.repository.EvSpecRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tester för fuzzy-matchningen mellan AI:ns biltitlar och databasens EV-specar.
 * Repositoryt mockas med Mockito — testerna kör utan databas och verifierar
 * enbart matchningslogiken (pass 1–3) och DTO-beräkningarna.
 */
@ExtendWith(MockitoExtension.class)
class EvSpecServiceTest {

    @Mock
    private EvSpecRepository repo;

    private EvSpecService service() {
        return new EvSpecService(repo);
    }

    private static EvSpec spec(String name) {
        return new EvSpec(name, 11.0, 150.0, 60.0, 400, 400_000);
    }

    @Test
    void nullTitelGerNull() {
        assertThat(service().formatForTitle(null, 15000)).isNull();
    }

    @Test
    void ingenMatchningGerNull() {
        when(repo.findAll()).thenReturn(List.of(spec("Tesla Model 3")));
        assertThat(service().formatForTitle("Renault Zoe", 15000)).isNull();
    }

    @Test
    void titelordSomSubstrangarMatchar() {
        // Pass 1: alla titelord finns som substrängar i lagrat namn
        when(repo.findAll()).thenReturn(List.of(spec("Volvo EX30 Single Motor")));
        assertThat(service().formatForTitle("Volvo EX30", 15000)).isNotNull();
    }

    @Test
    void langreTitelMatcharKortareLagratNamn() {
        // Pass 2: "Tesla Model 3 Long Range" ska hitta lagrade "Tesla Model 3"
        when(repo.findAll()).thenReturn(List.of(spec("Tesla Model 3")));
        assertThat(service().formatForTitle("Tesla Model 3 Long Range", 15000)).isNotNull();
    }

    @Test
    void valjerLangstaLagradeNamnetVidFleraMatchningar() {
        // Pass 2 tar mest specifika träffen: "Tesla Model 3" före "Tesla"
        EvSpec generisk = spec("Tesla");
        EvSpec specifik = new EvSpec("Tesla Model 3", 11.0, 250.0, 60.0, 500, 500_000);
        when(repo.findAll()).thenReturn(List.of(generisk, specifik));

        EvSpecDto dto = service().formatForTitle("Tesla Model 3 Performance", 15000);
        assertThat(dto.wltpKm()).isEqualTo(500); // den specifika, inte den generiska (400)
    }

    @Test
    void arsmodellIslutetStrippas() {
        when(repo.findAll()).thenReturn(List.of(spec("Volvo EX30")));
        assertThat(service().formatForTitle("Volvo EX30 (2025)", 15000)).isNotNull();
    }

    @Test
    void ePrefixStrippas() {
        // "Kia e-Niro" ska matcha lagrade "Kia Niro"
        when(repo.findAll()).thenReturn(List.of(spec("Kia Niro")));
        assertThat(service().formatForTitle("Kia e-Niro", 15000)).isNotNull();
    }

    @Test
    void electricSuffixStrippas() {
        when(repo.findAll()).thenReturn(List.of(spec("MG4 Long Range")));
        assertThat(service().formatForTitle("MG4 Electric", 15000)).isNotNull();
    }

    @Test
    void diakritiskaTeckenNormaliseras() {
        // "Škoda" i databasen ska matcha "Skoda" i titeln
        when(repo.findAll()).thenReturn(List.of(spec("Škoda Enyaq")));
        assertThat(service().formatForTitle("Skoda Enyaq", 15000)).isNotNull();
    }

    @Test
    void dtoBeraknarRackviddOchLaddintervall() {
        EvSpec tesla = new EvSpec("Tesla Model 3", 11.0, 250.0, 60.0, 500, 500_000);
        when(repo.findAll()).thenReturn(List.of(tesla));

        EvSpecDto dto = service().formatForTitle("Tesla Model 3", 15000);

        assertThat(dto.wltpKm()).isEqualTo(500);
        assertThat(dto.summerKm()).isEqualTo(425);  // 85 % av WLTP
        assertThat(dto.winterKm()).isEqualTo(350);  // 70 % av WLTP
        // 15000 km/år = 41,1 km/dag → 425 km sommarräckvidd / 41,1 ≈ var 10:e dag
        assertThat(dto.daysPerCharge()).isEqualTo(10);
        assertThat(dto.daysLabel()).isEqualTo("ladda var 10:e dag");
    }

    @Test
    void prisvardhetsEtikettBeraknas() {
        // score = (500/5)*0,6 + (60/5)*4 + 20 (DC≥150) = 128 → "Bra prisvärdhet"
        EvSpec tesla = new EvSpec("Tesla Model 3", 11.0, 250.0, 60.0, 500, 500_000);
        when(repo.findAll()).thenReturn(List.of(tesla));

        EvSpecDto dto = service().formatForTitle("Tesla Model 3", 15000);
        assertThat(dto.valueLabel()).isEqualTo("Bra prisvärdhet");
    }

    @Test
    void batterikemiSlasUppForKandModell() {
        assertThat(service().getBatteryChemistry("Volvo EX30 Twin Motor Performance"))
                .isEqualTo("NMC");
    }

    @Test
    void okandModellGerIngenBatterikemi() {
        assertThat(service().getBatteryChemistry("Okänd Bil XYZ")).isNull();
    }

    // --- buildValueRangeLine (prisvärd räckvidd per krona) ---

    @Test
    void prisvardRackviddRankarKmPerKronaOchFiltrerarKortRackvidd() {
        // Kia EV3 605 km/370k slår EX30 480 km/370k; Zoe under 400 km ska inte med
        EvSpec ev3  = new EvSpec("Kia EV3 Long Range", 11.0, 101.0, 81.4, 605, 370_000);
        EvSpec ex30 = new EvSpec("Volvo EX30 Single Motor Extended Range", 11.0, 153.0, 69.0, 480, 370_000);
        EvSpec zoe  = new EvSpec("Renault Zoe", 22.0, 50.0, 50.0, 395, 270_000);
        when(repo.findAll()).thenReturn(List.of(ex30, zoe, ev3));

        String line = service().buildValueRangeLine();
        assertThat(line)
                .contains("PRISVÄRD RÄCKVIDD")
                .contains("Kia EV3 (605 km")
                .contains("Volvo EX30 (480 km")
                .doesNotContain("Zoe");
        assertThat(line.indexOf("Kia EV3")).isLessThan(line.indexOf("Volvo EX30"));
    }

    @Test
    void okandaKinesiskaMarkenUteslutsUrPrisvardListan() {
        // "europeiska bilar, inte kinesiska okända" — Zeekr/Xpeng/Leapmotor/BYD listas inte
        EvSpec zeekr = new EvSpec("Zeekr 7X", 22.0, 360.0, 100.0, 615, 600_000);
        when(repo.findAll()).thenReturn(List.of(zeekr));
        assertThat(service().buildValueRangeLine()).isEmpty();
    }

    @Test
    void prisreferensenInkluderarPrisvardRackvidd() {
        EvSpec ev3 = new EvSpec("Kia EV3 Long Range", 11.0, 101.0, 81.4, 605, 370_000);
        when(repo.findAll()).thenReturn(List.of(ev3));
        assertThat(service().buildPriceReferenceContext())
                .contains("EV-referenspriser")
                .contains("PRISVÄRD RÄCKVIDD");
    }

    // --- verifiedEngineOptions (ersätter AI:ns fritext med riktiga kWh/räckvidd-varianter) ---

    @Test
    void ingenMatchGerNullForVerifieradeMotoralternativ() {
        when(repo.findAll()).thenReturn(List.of(spec("Tesla Model 3")));
        assertThat(service().verifiedEngineOptions("Renault Zoe")).isNull();
    }

    @Test
    void allaVarianterListasSorteradeEfterBatteristorlek() {
        // Skarpt fall: EX30 fick 58/77/44 kWh av AI:n — riktiga varianter är 51/65/65 kWh
        EvSpec singleMotor = new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 370_000);
        EvSpec extendedRange = new EvSpec("Volvo EX30 Single Motor Extended Range", 11.0, 153.0, 65.0, 480, 420_000);
        EvSpec twinPerformance = new EvSpec("Volvo EX30 Twin Motor Performance", 11.0, 153.0, 65.0, 450, 460_000);
        when(repo.findAll()).thenReturn(List.of(extendedRange, twinPerformance, singleMotor));

        String result = service().verifiedEngineOptions("Volvo EX30 (2024)");
        // Sorterad efter kWh, sen räckvidd — 65 kWh-varianterna (450/480 km) i stigande räckviddsordning
        assertThat(result).isEqualTo("51 kWh (344 km), 65 kWh (450 km), 65 kWh (480 km)");
    }

    @Test
    void duplicerandeDbRaderMedSammaVariantDedupas() {
        // Samma modell kan finnas i flera identiska rader (skett i produktion) — ska bara visas en gång
        EvSpec dup1 = new EvSpec("Volvo EX30 P3 Long Range", 11.0, 153.0, 65.0, 480, 420_000);
        EvSpec dup2 = new EvSpec("Volvo EX30 P3 Long Range", 11.0, 153.0, 65.0, 480, 420_000);
        when(repo.findAll()).thenReturn(List.of(dup1, dup2));
        assertThat(service().verifiedEngineOptions("Volvo EX30")).isEqualTo("65 kWh (480 km)");
    }

    @Test
    void nullTitelGerNullForVerifieradeMotoralternativ() {
        assertThat(service().verifiedEngineOptions(null)).isNull();
    }

    // --- getSystemPowerHk (verifierad hk för modeller AI:n historiskt gissat fel på) ---

    @Test
    void marvelRStandardGerVerifieradHk() {
        // AI:n gav "150hk" — riktig siffra för Standard/RWD-varianten är 180
        assertThat(service().getSystemPowerHk("MG Marvel R (2022)")).isEqualTo(180);
    }

    @Test
    void marvelRPerformanceGerMestSpecifikaTraffen() {
        // "Performance" i titeln ska ge 288, inte råka matcha bas-nyckeln "MG Marvel R" (180)
        assertThat(service().getSystemPowerHk("MG Marvel R Performance (2022)")).isEqualTo(288);
    }

    @Test
    void okandModellGerIngenVerifieradHk() {
        assertThat(service().getSystemPowerHk("Renault Zoe")).isNull();
    }

    @Test
    void nullTitelGerNullForVerifieradHk() {
        assertThat(service().getSystemPowerHk(null)).isNull();
    }
}
