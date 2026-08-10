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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @Mock
    private EvSpecService evSpecService;

    @Mock
    private UpcomingInsightService upcomingService;

    private ExpertInsightService service() {
        return new ExpertInsightService(repo, evSpecService, upcomingService);
    }

    private static CarPreferences prefs(String category, String fuelType) {
        return new CarPreferences(300_000, category, false, 15_000, "pendling",
                4, true, fuelType, "automat", "köp", null, null);
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
    void begransasTillMaxFemSlumpadeInsikter() {
        // 7 insikter i poolen → exakt MAX_RECOMMEND_INSIGHTS (5) hamnar i prompten; urvalet är slumpat
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of(
                insikt("Vi Bilägare", "Volvo", "XC40", "Insikt 1", 7),
                insikt("Vi Bilägare", "Kia", "EV6", "Insikt 2", 8),
                insikt("Vi Bilägare", "Tesla", "Model Y", "Insikt 3", 9),
                insikt("M Sverige", "Skoda", "Enyaq", "Insikt 4", 8),
                insikt("Teknikens Värld", "VW", "ID.4", "Insikt 5", 7),
                insikt("Folksam", "Nissan", "Ariya", "Insikt 6", null),
                insikt("Bytbil", "BMW", "iX1", "Insikt 7", 9)));

        String ctx = service().buildExpertContext(prefs("suv", "el"));
        assertThat(ctx.lines().filter(l -> l.startsWith("- ")).count())
                .isEqualTo(ExpertInsightService.MAX_RECOMMEND_INSIGHTS);
    }

    @Test
    void farreInsikterAnMaxTasMedAllihop() {
        when(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase("suv", "el")).thenReturn(List.of(
                insikt("Vi Bilägare", "Volvo", "XC40", "Insikt 1", 7),
                insikt("Vi Bilägare", "Kia", "EV6", "Insikt 2", 8)));

        String ctx = service().buildExpertContext(prefs("suv", "el"));
        assertThat(ctx).contains("Insikt 1").contains("Insikt 2");
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
        // Urvalet roterar (shuffle) — vilka tre som kommer med är inte deterministiskt, antalet är det
        assertThat(ctx.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(3);
    }

    @Test
    void modelltraffGarForeRenMarkestraff() {
        when(repo.findAll()).thenReturn(List.of(
                insikt("Vi Bilägare", "Audi", "Q3", "Q3-insikt 1", 7),
                insikt("Vi Bilägare", "Audi", "Q3", "Q3-insikt 2", 7),
                insikt("Vi Bilägare", "Audi", "Q3", "Q3-insikt 3", 7),
                insikt("Vi Bilägare", "Audi", "e-tron", "Fyndläge på begagnad e-tron", 7)));

        String ctx = service().buildChatExpertContext(List.of("Är Audi e-tron ett bra köp?"));
        assertThat(ctx).contains("Fyndläge på begagnad e-tron");
    }

    @Test
    void valdBilIKontextenRaknasSomOmMarketNamnts() {
        when(repo.findAll()).thenReturn(List.of(
                insikt("Vi Bilägare", "Audi", "e-tron", "Audiinsikt", 7),
                insikt("Vi Bilägare", "Kia", "EV6", "Kiainsikt", 8)));

        String ctx = service().buildChatExpertContext(
                List.of("Vad tycker du om den?"), "1. Audi e-tron (2020) — 280 000 kr");
        assertThat(ctx).contains("Audiinsikt").doesNotContain("Kiainsikt");
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

    @Test
    void kategoriByteDelegererTillRepo() {
        when(repo.renameCategory("småbil", "smaabil")).thenReturn(12);
        assertThat(service().renameCategory(" småbil ", " smaabil ")).isEqualTo(12);
        verify(repo).renameCategory("småbil", "smaabil");
    }

    @Test
    void kategoriByteMedBlankInputGorInget() {
        assertThat(service().renameCategory(null, "smaabil")).isZero();
        assertThat(service().renameCategory("småbil", " ")).isZero();
        verify(repo, never()).renameCategory(any(), any());
    }

    // --- updateInsight (admin-PATCH) ---

    private ExpertInsight raddaMedId(Long id) {
        ExpertInsight row = insikt("CarUp", "Kia", "EV3", "Rymlig och prisvärd", null);
        when(repo.findById(id)).thenReturn(Optional.of(row));
        return row;
    }

    private void savePasserarIgenom() {
        when(repo.save(any(ExpertInsight.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void patchAndrarBaraSkickadeFaltOchNormaliserarKategori() {
        ExpertInsight row = raddaMedId(42L);
        savePasserarIgenom();

        Map<String, Object> resultat = service().updateInsight(42L, Map.of("category", " SUV ")).orElseThrow();

        assertThat(row.getCategory()).isEqualTo("suv");
        assertThat(row.getCarMake()).isEqualTo("Kia");
        assertThat(row.getInsight()).isEqualTo("Rymlig och prisvärd");
        assertThat(resultat.get("category")).isEqualTo("suv");
    }

    @Test
    void patchMedRatingSomStrangOchTomningAvCarModel() {
        ExpertInsight row = raddaMedId(42L);
        savePasserarIgenom();

        Map<String, Object> falt = new java.util.HashMap<>();
        falt.put("rating", "8");
        falt.put("carModel", "");
        service().updateInsight(42L, falt);

        assertThat(row.getRating()).isEqualTo(8);
        assertThat(row.getCarModel()).isNull();
    }

    @Test
    void patchOkantFaltAvvisas() {
        assertThatThrownBy(() -> service().updateInsight(42L, Map.of("categori", "suv")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Okänt fält: categori");
        verify(repo, never()).save(any());
    }

    @Test
    void patchTomInsightTextAvvisas() {
        raddaMedId(42L);
        assertThatThrownBy(() -> service().updateInsight(42L, Map.of("insight", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insight");
        verify(repo, never()).save(any());
    }

    @Test
    void patchOgiltigRatingAvvisas() {
        raddaMedId(42L);
        assertThatThrownBy(() -> service().updateInsight(42L, Map.of("rating", 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-10");
    }

    @Test
    void patchSaknatIdGerTomOptional() {
        when(repo.findById(999L)).thenReturn(Optional.empty());
        assertThat(service().updateInsight(999L, Map.of("category", "suv"))).isEmpty();
    }

    @Test
    void patchUtanFaltAvvisas() {
        assertThatThrownBy(() -> service().updateInsight(42L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minst ett fält");
    }

    // --- findForCarTitle: drivlinefilter på bilkortsinsikter ---

    @Test
    void hevInsiktVisasIntePaEvKort() {
        // Skarpt läge: Vi Bilägares Niro HEV-test (4,8 l/100 km) visades på Kia Niro EV-kortet
        ExpertInsight hevTest = insikt("Vi Bilägare", "Kia", "Niro",
                "Vi Bilägare testar Kia Niro HEV och konstaterar 4,8 l/100 km. Self-charging hybrid utan laddningskrav.", 8);
        when(repo.findAll()).thenReturn(List.of(hevTest));
        assertThat(service().findForCarTitle("Kia Niro EV (2023)")).isEmpty();
    }

    @Test
    void evInsiktVisasPaEvKort() {
        ExpertInsight evTest = insikt("Vi Bilägare", "Kia", "Niro",
                "Niro EV är en prisvärd elbil med bra räckvidd.", 8);
        when(repo.findAll()).thenReturn(List.of(evTest));
        assertThat(service().findForCarTitle("Kia Niro EV (2023)")).hasSize(1);
    }

    @Test
    void insiktUtanDrivlinaVisasOavsettVariant() {
        ExpertInsight allmaen = insikt("Vi Bilägare", "Kia", "Niro",
                "Rymlig kupé och marknadens längsta garanti.", 8);
        when(repo.findAll()).thenReturn(List.of(allmaen));
        assertThat(service().findForCarTitle("Kia Niro EV (2023)")).hasSize(1);
    }

    @Test
    void titelUtanDrivlinaFiltrerarInte() {
        // "Kia EV6" har ingen HEV-variant och "ev6" är ett ord — inget drivlinefilter ska slå till
        ExpertInsight hybridText = insikt("Vi Bilägare", "Volvo", "V60",
                "V60 finns även som laddhybrid.", 7);
        when(repo.findAll()).thenReturn(List.of(hybridText));
        assertThat(service().findForCarTitle("Volvo V60 (2021)")).hasSize(1);
    }

    @Test
    void drivetrainOfSkiljerPaVarianterna() {
        assertThat(ExpertInsightService.drivetrainOf("Kia Niro PHEV laddhybrid")).isEqualTo("phev");
        assertThat(ExpertInsightService.drivetrainOf("Niro HEV self-charging hybrid")).isEqualTo("hev");
        assertThat(ExpertInsightService.drivetrainOf("Kia Niro EV (2023)")).isEqualTo("ev");
        assertThat(ExpertInsightService.drivetrainOf("Kia EV6 (2022)")).isNull(); // "ev6" är ett ord
        assertThat(ExpertInsightService.drivetrainOf("Kia Niro")).isNull();
    }

    @Test
    void drivetrainOfKannerIgenForbranningsord() {
        assertThat(ExpertInsightService.drivetrainOf("N47-dieselmotorn kan få kamkedjebrott.")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("EcoBoost-motorer med kamremmar i olja.")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Modellerna är kompatibla med E20.")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Bensinbilar före 2011 klarar bara E5.")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Igensatt partikelfilter är ett känt fel.")).isEqualTo("ice");
    }

    @Test
    void hybridVinnerOverForbranningsordISammaText() {
        // En hybridinsikt nämner nästan alltid bensinmotorn också — den ska förbli phev/hev,
        // annars filtreras laddhybridinsikter bort från laddhybridkort
        assertThat(ExpertInsightService.drivetrainOf("Laddhybriden drar 2,7 l/100 km bensin."))
                .isEqualTo("phev");
        assertThat(ExpertInsightService.drivetrainOf("Hybriden kombinerar elmotor med bensinmotor."))
                .isEqualTo("hev");
    }

    @Test
    void drivlinemarkorerTalSvenskaAndelser() {
        // "\bhybrid\b" missade bestämd form — ofarligt när utfallet blev null, men efter att
        // ICE-ledet tillkom hade "hybridEN ... bensin" klassats som förbränning
        assertThat(ExpertInsightService.drivetrainOf("Hybriden är billig i drift")).isEqualTo("hev");
        assertThat(ExpertInsightService.drivetrainOf("Laddhybriderna blir dyrare 2027")).isEqualTo("phev");
        assertThat(ExpertInsightService.drivetrainOf("Elbilarna stjäls sällan")).isEqualTo("ev");
    }

    @Test
    void elbilsordVinnerSaTaycanTurboInteBlirForbranning() {
        // Medvetet utelämnade ICE-ord: "turbo" (Porsche Taycan Turbo S ÄR en elbil) och
        // "olja"/"växellåda" (elbilar har reduktionsväxel med olja)
        assertThat(ExpertInsightService.drivetrainOf("Taycan Turbo S är en snabb elbil")).isEqualTo("ev");
        assertThat(ExpertInsightService.drivetrainOf("Oljan i växellådan bör bytas")).isNull();
    }

    @Test
    void forbranningsinsiktVisasIntePaElbilskort() {
        // Fords EcoBoost-kamremsvarning är märkesbred (carModel == null) och hamnade därför
        // på VARJE Ford-kort — inklusive Mustang Mach-E, som är en ren elbil
        ExpertInsight kamrem = insikt("CarUp", "Ford", null,
                "Ford med trecylindriga EcoBoost-motorer har problem med kamremmar i olja.", null);
        when(repo.findAll()).thenReturn(List.of(kamrem));
        when(evSpecService.isKnownEv("Ford Mustang Mach-E (2023)")).thenReturn(true);

        assertThat(service().findForCarTitle("Ford Mustang Mach-E (2023)")).isEmpty();
    }

    @Test
    void forbranningsinsiktVisasFortfarandePaForbranningskort() {
        // Samma insikt på en bensin-/dieselbil av samma märke ska INTE filtreras bort
        ExpertInsight kamrem = insikt("CarUp", "Ford", null,
                "Ford med trecylindriga EcoBoost-motorer har problem med kamremmar i olja.", null);
        when(repo.findAll()).thenReturn(List.of(kamrem));
        when(evSpecService.isKnownEv("Ford Focus (2019)")).thenReturn(false);

        assertThat(service().findForCarTitle("Ford Focus (2019)")).hasSize(1);
    }

    @Test
    void hybridkortBehallerForbranningsinsikt() {
        // En hybrid HAR en bensinmotor — Toyotas oljebytesråd hör hemma på ett Corolla
        // Hybrid-kort. Ren likhetsjämförelse hade tystat hybridkorten när ice-ledet infördes
        ExpertInsight olja = insikt("CarUp", "Toyota", null,
                "För vanliga bensinmotorer i moderna Toyota-bilar föreslås oljebyten var 8 000 km.", null);
        when(repo.findAll()).thenReturn(List.of(olja));

        assertThat(service().findForCarTitle("Toyota Corolla Hybrid (2022)")).hasSize(1);
    }

    @Test
    void drivetrainsCompatibleSlapperIceOveralltUtomPaElbil() {
        assertThat(ExpertInsightService.drivetrainsCompatible("ev", "ice")).isFalse();
        assertThat(ExpertInsightService.drivetrainsCompatible("hev", "ice")).isTrue();
        assertThat(ExpertInsightService.drivetrainsCompatible("phev", "ice")).isTrue();
        assertThat(ExpertInsightService.drivetrainsCompatible("ev", "hev")).isFalse();
        assertThat(ExpertInsightService.drivetrainsCompatible("ev", "ev")).isTrue();
    }

    @Test
    void evSpecFelSlackerInteInsikterna() {
        // Fail open: ett DB-fel i ev_spec-uppslaget får stänga av filtreringen, inte kortet
        ExpertInsight allman = insikt("Vi Bilägare", "Volvo", "EX30", "Bra räckvidd.", 8);
        when(repo.findAll()).thenReturn(List.of(allman));
        when(evSpecService.isKnownEv("Volvo EX30 (2024)")).thenThrow(new RuntimeException("DB nere"));

        assertThat(service().findForCarTitle("Volvo EX30 (2024)")).hasSize(1);
    }
}
