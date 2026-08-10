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
    void varianterMedSammaBatteriSlasIhopTillEttRackviddsspann() {
        // Skarpt fall: EX30 fick 58/77/44 kWh av AI:n — riktiga batterier är 51 och 65/69 kWh.
        // De två 65-varianterna ar samma batteri i olika drivlinor → ett spann, inte tva rader.
        EvSpec singleMotor = new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 370_000);
        EvSpec extendedRange = new EvSpec("Volvo EX30 Single Motor Extended Range", 11.0, 153.0, 65.0, 480, 420_000);
        EvSpec twinPerformance = new EvSpec("Volvo EX30 Twin Motor Performance", 11.0, 153.0, 65.0, 450, 460_000);
        when(repo.findAll()).thenReturn(List.of(extendedRange, twinPerformance, singleMotor));

        // 51 kWh star ensam pa sin rad och far darfor sitt trimnamn; 65 kWh-gruppen ar tva
        // varianter och lamnas omarkt (se trimnamnetVisasBaraNarGruppenArEnEndaVariant)
        assertThat(service().verifiedEngineOptions("Volvo EX30 (2024)"))
                .isEqualTo("51 kWh (344 km) · Single Motor, 65 kWh (450–480 km)");
    }

    @Test
    void nettoOchBruttokapacitetForSammaBatteriBlirEnRad() {
        // Produktionsfallet: 19 EX30-rader under tva namngenerationer (Single Motor/Twin Motor
        // och ev-databases P3/P5/P8) gav nio rader pa kortet. Bilen har tva batterier.
        // 49/51 ar samma batteri (netto/brutto), likasa 65/69.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo EX30 P3", 11.0, 153.0, 49.0, 337, 320_000),
                new EvSpec("Volvo EX30 P5", 11.0, 153.0, 49.0, 339, 330_000),
                new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 370_000),
                new EvSpec("Volvo EX30 P8 AWD", 11.0, 200.0, 65.0, 450, 430_000),
                new EvSpec("Volvo EX30 P3 Long Range", 11.0, 153.0, 65.0, 463, 400_000),
                new EvSpec("Volvo EX30 P5 Long Range", 11.0, 153.0, 65.0, 476, 410_000),
                new EvSpec("Volvo EX30 Cross Country", 11.0, 153.0, 69.0, 436, 395_000),
                new EvSpec("Volvo EX30 Twin Motor Performance", 11.0, 200.0, 69.0, 460, 430_000),
                new EvSpec("Volvo EX30 Single Motor Extended Range", 11.0, 153.0, 69.0, 480, 370_000)));

        assertThat(service().verifiedEngineOptions("Volvo EX30 (2024)"))
                .isEqualTo("51 kWh (337–344 km), 69 kWh (436–480 km)");
    }

    @Test
    void tvaGenerationersBatterierIsammaModellHallsIsar() {
        // EV6 finns med 77,4 kWh (2021-2024) och 84 kWh (2024-2026) — 8,5 % isar, alltsa tva
        // riktiga batterier och inte netto/brutto av samma. Regressionsskydd for toleransen:
        // med den ursprungliga 10 %-gransen slogs de ihop till en enda rad.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Long Range 2WD 77.4 kWh", 11.0, 233.0, 77.4, 528, 0),
                new EvSpec("Kia EV6 GT 77.4 kWh", 11.0, 233.0, 77.4, 424, 0),
                new EvSpec("Kia EV6 Long Range 2WD 84 kWh", 11.0, 263.0, 84.0, 582, 0),
                new EvSpec("Kia EV6 Long Range AWD 84 kWh", 11.0, 263.0, 84.0, 546, 0)));

        assertThat(service().verifiedEngineOptions("Kia EV6"))
                .isEqualTo("77.4 kWh (424–528 km), 84 kWh (546–582 km)");
    }

    @Test
    void tydligtOlikaBatterierHallsIsar() {
        // 58 och 77 kWh ar over toleransen (10 %) — tva riktiga val, ska inte slas ihop
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Standard", 11.0, 180.0, 58.0, 394, 400_000),
                new EvSpec("Kia EV6 Long Range", 11.0, 240.0, 77.0, 528, 480_000)));

        assertThat(service().verifiedEngineOptions("Kia EV6"))
                .isEqualTo("58 kWh (394 km) · Standard, 77 kWh (528 km) · Long Range");
    }

    @Test
    void variantUtanRackviddVisasUtanKmParentes() {
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 0, 370_000)));

        assertThat(service().verifiedEngineOptions("Volvo EX30")).isEqualTo("51 kWh · Single Motor");
    }

    @Test
    void duplicerandeDbRaderMedSammaVariantDedupas() {
        // Samma modell kan finnas i flera identiska rader (skett i produktion) — ska bara visas en gång
        EvSpec dup1 = new EvSpec("Volvo EX30 P3 Long Range", 11.0, 153.0, 65.0, 480, 420_000);
        EvSpec dup2 = new EvSpec("Volvo EX30 P3 Long Range", 11.0, 153.0, 65.0, 480, 420_000);
        when(repo.findAll()).thenReturn(List.of(dup1, dup2));
        assertThat(service().verifiedEngineOptions("Volvo EX30")).isEqualTo("65 kWh (480 km) · P3 Long Range");
    }

    @Test
    void trimnamnetSkiljerModellensVarianterAt() {
        // Skarpt fall: MG4 visade fyra rader som bara skilde sig i kWh, dar TVA hade samma
        // rackvidd (Long Range och XPOWER, bada 405 km) — omojligt att se vilken rad som var
        // vilken bil. Namnen finns i DB:n, de kastades bara bort. Siffrorna ar de som faktiskt
        // ligger i produktion 2026-08-10.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Standard Range", 11.0, 117.0, 41.9, 325, 295_000),
                new EvSpec("MG4 Long Range", 11.0, 150.0, 52.8, 405, 335_000),
                new EvSpec("MG MG4 XPOWER", 11.0, 140.0, 61.7, 405, 471_000),
                new EvSpec("MG4 Extended Range", 11.0, 150.0, 74.4, 545, 375_000)));

        assertThat(service().verifiedEngineOptions("MG4"))
                .isEqualTo("41.9 kWh (325 km) · Standard Range, "
                         + "52.8 kWh (405 km) · Long Range, "
                         + "61.7 kWh (405 km) · XPOWER, "
                         + "74.4 kWh (545 km) · Extended Range");
    }

    @Test
    void markesprefixetIngarInteITrimnamnet() {
        // ev-database lagrar "MG MG4 XPOWER" medan titeln bara sager "MG4". Utan att skala bort
        // market fore modellordet blir trimmet "MG XPOWER".
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG MG4 XPOWER", 11.0, 140.0, 61.7, 405, 471_000)));

        assertThat(service().verifiedEngineOptions("MG4")).isEqualTo("61.7 kWh (405 km) · XPOWER");
    }

    @Test
    void trimnamnetVisasBaraNarGruppenArEnEndaVariant() {
        // En hopslagen grupp beskriver flera bilar — 49/51 kWh ar P3, P5 OCH Single Motor.
        // Ett godtyckligt utvalt namn hade pekat ut fel bil, sa gruppen lamnas omarkt.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo EX30 P3", 11.0, 153.0, 49.0, 337, 320_000),
                new EvSpec("Volvo EX30 P5", 11.0, 153.0, 49.0, 339, 330_000),
                new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 370_000)));

        assertThat(service().verifiedEngineOptions("Volvo EX30")).isEqualTo("51 kWh (337–344 km)");
    }

    @Test
    void tvaModellgenerationerSlasInteIhopTrotsNastanLikaBatterier() {
        // MG4 gen 1 har 51 kWh/350 km och gen 2 har 52,8 kWh/416 km — 3,5 % isär, alltså långt
        // inom 8 %-toleransen. Utan generationsspärren blev raden "52.8 kWh (350–416 km)", som
        // parar andra generationens batteri med första generationens räckvidd. Ingen siffra kan
        // skilja fallen åt (EV6 GT och Long Range delar batteri men går 424 mot 528 km och SKA
        // slås ihop), så generationen är uppgiven i EvSpecService.GENERATION.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Standard Range", 11.0, 117.0, 51.0, 350, 295_000),
                new EvSpec("MG4 Urban Comfort Long Range", 11.0, 87.0, 52.8, 416, 0)));

        assertThat(service().verifiedEngineOptions("MG4"))
                .isEqualTo("51 kWh (350 km) · Standard Range, "
                         + "52.8 kWh (416 km) · Urban Comfort Long Range");
    }

    @Test
    void varianterInomSammaGenerationSlasFortfarandeIhop() {
        // Spärren får inte slå sönder den vanliga ihopslagningen: båda raderna är gen 2
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Urban Comfort Long Range", 11.0, 87.0, 52.8, 416, 0),
                new EvSpec("MG4 Urban Premium Long Range", 11.0, 87.0, 52.8, 405, 0)));

        assertThat(service().verifiedEngineOptions("MG4")).isEqualTo("52.8 kWh (405–416 km)");
    }

    @Test
    void arsmodellenIAnnonsenValjerGeneration() {
        // En MG4 från 2023 är gen 1 och en från 2025 är gen 2 — kortet ska visa den generation
        // annonsbilen faktiskt är, inte alla sju batterierna för båda.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Standard Range", 11.0, 117.0, 51.0, 350, 295_000),
                new EvSpec("MG4 Extended Range", 11.0, 150.0, 77.0, 520, 375_000),
                new EvSpec("MG4 Urban Standard Range", 11.0, 82.0, 41.9, 325, 0),
                new EvSpec("MG4 Premium Extended Range", 11.0, 144.0, 74.4, 545, 0)));

        assertThat(service().verifiedEngineOptions("MG4 (2023)"))
                .isEqualTo("51 kWh (350 km) · Standard Range, 77 kWh (520 km) · Extended Range");
        assertThat(service().verifiedEngineOptions("MG4 (2025)"))
                .isEqualTo("41.9 kWh (325 km) · Urban Standard Range, "
                         + "74.4 kWh (545 km) · Premium Extended Range");
        // Utan årsmodell finns inget att välja på — då visas båda generationerna som förut
        assertThat(service().verifiedEngineOptions("MG4"))
                .contains("51 kWh").contains("41.9 kWh");
    }

    @Test
    void arsmodellPaverkarInteModellerUtanGenerationer() {
        // Filtret får bara slå till där generationer är uppgivna — alla andra modeller orörda
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Standard", 11.0, 180.0, 58.0, 394, 400_000),
                new EvSpec("Kia EV6 Long Range", 11.0, 240.0, 77.0, 528, 480_000)));

        assertThat(service().verifiedEngineOptions("Kia EV6 (2023)"))
                .isEqualTo("58 kWh (394 km) · Standard, 77 kWh (528 km) · Long Range");
    }

    @Test
    void arsmodellAldreAnAllaRaderTommerIngenLista() {
        // 2019 är före båda generationerna — hellre för mycket information än ett tomt fält
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Urban Standard Range", 11.0, 82.0, 41.9, 325, 0)));

        assertThat(service().verifiedEngineOptions("MG4 (2019)"))
                .isEqualTo("41.9 kWh (325 km) · Urban Standard Range");
    }

    @Test
    void basmodellenUtanTrimFarIngenEtikett() {
        // Raden ar modellen sjalv ("MG4") — inget blir kvar nar titelorden tagits bort
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4", 11.0, 150.0, 64.0, 450, 335_000)));

        assertThat(service().verifiedEngineOptions("MG4")).isEqualTo("64 kWh (450 km)");
    }

    @Test
    void hartMellanslagITitelnHindrarInteMatchningen() {
        // Skarpt fall fran produktion: AI:n skrev "Hyundai IONIQ 5" med SMALT HART MELLANSLAG
        // (U+202F) mellan orden. Javas \s matchar inte det tecknet, sa namnet blev ETT ord och
        // all ordmatchning missade - kortet foll tillbaka pa AI:ns egen fritext i stallet for
        // de verifierade siffrorna. Tecknet byggs ur sin kodpunkt i stallet for att skrivas
        // rakt in: osynliga tecken gar inte att se i en diff och overlever inte en kopiering.
        String nnbsp = String.valueOf((char) 0x202F);
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Hyundai IONIQ 5", 11.0, 233.0, 84.0, 570, 500_000)));

        assertThat(service().verifiedEngineOptions("Hyundai IONIQ" + nnbsp + "5 (2024)"))
                .isEqualTo("84 kWh (570 km)");
    }

    @Test
    void vanligtHartMellanslagOchZeroWidthHanterasOcksa() {
        String nbsp = String.valueOf((char) 0x00A0);       // NO-BREAK SPACE
        String zeroWidth = String.valueOf((char) 0x200B);  // ZERO WIDTH SPACE
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Hyundai IONIQ 5", 11.0, 233.0, 84.0, 570, 500_000)));

        assertThat(service().verifiedEngineOptions("Hyundai" + nbsp + "IONIQ" + zeroWidth + " 5"))
                .isEqualTo("84 kWh (570 km)");
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
