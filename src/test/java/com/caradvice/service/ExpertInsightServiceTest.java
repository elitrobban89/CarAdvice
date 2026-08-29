package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    void bydsEgenLaddhybridbadgeArEnPhevMarkor() {
        /*
         * DM-i/DM-p är BYD:s namn på laddhybriddrivlinan, inlagt 2026-08-26 sedan "BYD Seal U"
         * visat sig vara samma fälla som "Seal 6": elbilsraden BYD Seal matchade rubriken och
         * gjorde en laddhybrid till ren elbil. Syskonsiffervakten i EvSpecService biter inte på
         * en BOKSTAV, men badgen är ett drivlinebevis rubriken bär själv — 55 av 56 DM-i-annonser
         * i korpusen är hybrid eller laddhybrid enligt Blockets eget fuel-fält.
         */
        assertThat(ExpertInsightService.drivetrainOf("BYD Seal U DM-i Comfort Paket")).isEqualTo("phev");
        assertThat(ExpertInsightService.drivetrainOf("BYD Seal 6 DM-i Touring Comfort")).isEqualTo("phev");
        assertThat(ExpertInsightService.drivetrainOf("Den avancerade DM-p-drivlinan")).isEqualTo("phev");
        // Mellanslagsvarianten är medvetet utelämnad: "dm" är också decimeter, och markörerna
        // prövas mot insiktstexter och inte bara annonsrubriker
        assertThat(ExpertInsightService.drivetrainOf("Bagaget mäter 30 dm i djupled")).isNull();
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

    // --- findForCarTitle: modellnamn får inte matcha ett ANNAT, längre namn ---

    @Test
    void bensinPoloVisasIntePaIdPoloKort() {
        // Skarpt fall 2026-08-24: "polo" är en delsträng av "id. polo", så bensin-Polons rader
        // låg på elbilskortet. "id. polo" börjar tidigare i titeln och vinner.
        ExpertInsight polo = insikt("Teknikens Värld", "Volkswagen", "Polo",
                "Bilen har 16-tumshjul som ger en hård körning på ojämna vägar.", 7);
        ExpertInsight idPolo = insikt("Vi Bilägare", "Volkswagen", "ID. Polo",
                "Instegsversionen kostar från 320 900 kr.", 8);
        when(repo.findAll()).thenReturn(List.of(polo, idPolo));
        when(evSpecService.isKnownEv("Volkswagen ID. Polo 155 kW")).thenReturn(true);
        assertThat(service().findForCarTitle("Volkswagen ID. Polo 155 kW"))
                .singleElement()
                .extracting(m -> m.get("insight"))
                .asString().startsWith("Instegsversionen");
    }

    @Test
    void a6InsiktSlasInteUtAvGenerellEtronInsikt() {
        // Motprov mot "längsta namnet vinner": "e-tron" är längre än "a6" men står SENARE i
        // titeln, och kortet gäller en A6. Positionen avgör, inte längden.
        ExpertInsight a6 = insikt("M3", "Audi", "A6", "A6 har luftfjädring som tillval.", 8);
        ExpertInsight etron = insikt("M3", "Audi", "e-tron", "e-tron-modellerna delar laddteknik.", 7);
        when(repo.findAll()).thenReturn(List.of(a6, etron));
        assertThat(service().findForCarTitle("Audi A6 Avant e-tron quattro"))
                .singleElement()
                .extracting(m -> m.get("insight"))
                .asString().startsWith("A6 har");
    }

    @Test
    void sealInsiktVisasIntePaSealionKort() {
        // "seal" ligger inuti "sealion" utan mellanrum — bara namngränsen fångar den, och
        // positionsregeln kan inte hjälpa (båda börjar på samma plats)
        ExpertInsight seal = insikt("Bilexpert", "BYD", "Seal", "Seal är en sedan med 570 km räckvidd.", 8);
        when(repo.findAll()).thenReturn(List.of(seal));
        assertThat(service().findForCarTitle("BYD SEALION 7 82.5 kWh AWD Design")).isEmpty();
    }

    @Test
    void cx3SmittarInteCx30() {
        // Siffergränsen: "cx-3" får inte matcha "cx-30"
        ExpertInsight cx3 = insikt("Vi Bilägare", "Mazda", "CX-3", "CX-3 har liten baklucka.", 6);
        when(repo.findAll()).thenReturn(List.of(cx3));
        assertThat(service().findForCarTitle("Mazda CX-30 2.0 Skyactiv-G 150 hk")).isEmpty();
    }

    @Test
    void modellnamnSomSlutarPaPlustecknetMatchasAnda() {
        // Namngränsen skrivs med lookaround, inte \b — "C-HR+" slutar på ett icke-bokstavstecken
        // och hade annars aldrig matchat sitt eget kort
        ExpertInsight chrPlus = insikt("M3", "Toyota", "C-HR+", "C-HR+ är den eldrivna varianten.", 8);
        when(repo.findAll()).thenReturn(List.of(chrPlus));
        when(evSpecService.isKnownEv("Toyota C-HR+ 77 kWh")).thenReturn(true);
        assertThat(service().findForCarTitle("Toyota C-HR+ 77 kWh")).hasSize(1);
    }

    @Test
    void variantkortBehallerSinaModellinsikter() {
        // Regeln får inte kapa den vanliga varianttiteln: "Model Y" står först i titeln
        ExpertInsight modelY = insikt("M3", "Tesla", "Model Y", "Model Y har stor baklucka.", 8);
        when(repo.findAll()).thenReturn(List.of(modelY));
        when(evSpecService.isKnownEv("Tesla Model Y Long Range AWD")).thenReturn(true);
        assertThat(service().findForCarTitle("Tesla Model Y Long Range AWD")).hasSize(1);
    }

    @Test
    void modelPositionHittarNamngranserna() {
        assertThat(ExpertInsightService.modelPosition("volkswagen id. polo 155 kw", "Polo")).isEqualTo(15);
        assertThat(ExpertInsightService.modelPosition("byd sealion 7 82.5 kwh", "Seal")).isEqualTo(-1);
        assertThat(ExpertInsightService.modelPosition("audi sq7 4.0 tfsi", "Q7")).isEqualTo(-1);
        assertThat(ExpertInsightService.modelPosition("bmw ix3", "X3")).isEqualTo(-1);
        assertThat(ExpertInsightService.modelPosition("mazda cx-30 2.0", "CX-3")).isEqualTo(-1);
        assertThat(ExpertInsightService.modelPosition("toyota c-hr+ 77 kwh", "C-HR+")).isEqualTo(7);
    }

    @Test
    void motorkoderRaknasSomForbranning() {
        // Skarpt fall 2026-08-24: Teknikens Världs bensin-Polo-test nämnde ingen av de gamla
        // markörerna — bara motorkoden — och landade därför på elbilskortet ID. Polo
        assertThat(ExpertInsightService.drivetrainOf("Provbilen hade en 115-hästkrafts TSI-motor."))
                .isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("2.0 TDI drar 0,49 l/mil")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Audis 2.0 TFSI quattro")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Peugeots 1.5 BlueHDi")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Renault 1.3 dCi")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Hyundais 1.6 CRDi")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("Fords 1.0 EcoBoost")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("PureTech-motorn är känd för kamremmen")).isEqualTo("ice");
    }

    @Test
    void motorkodPaLaddhybridForblirLaddhybrid() {
        // ICE prövas sist, så en laddhybrid som nämner sin bensinmotorkod behåller phev och
        // filtreras alltså inte bort från laddhybridkort
        assertThat(ExpertInsightService.drivetrainOf("Laddhybriden har en 1,4 TSI och 13 kWh batteri"))
                .isEqualTo("phev");
    }

    @Test
    void eSkyactivArIngenForbranningsmarkor() {
        // Mazda MX-30 är en ELBIL med e-Skyactiv-drivlina — koden får aldrig in i listan
        assertThat(ExpertInsightService.drivetrainOf("MX-30 använder e-Skyactiv-drivlinan")).isNull();
    }

    @Test
    void bensinInsiktMedMotorkodVisasInteFaElbilskort() {
        // Hela kedjan: "polo" är en delsträng av "id. polo", så bensinraden matchar titeln —
        // det är drivlinefiltret som måste stoppa den
        ExpertInsight bensinPolo = insikt("Teknikens Värld", "Volkswagen", "Polo",
                "Provbilen hade en 115-hästkrafts TSI-motor kopplad till en dubbelkopplingslåda.", 7);
        when(repo.findAll()).thenReturn(List.of(bensinPolo));
        when(evSpecService.isKnownEv("Volkswagen ID. Polo 155 kW - 52 kWh")).thenReturn(true);
        assertThat(service().findForCarTitle("Volkswagen ID. Polo 155 kW - 52 kWh")).isEmpty();
    }

    @Test
    void bensinInsiktMedMotorkodVisasFortfarandePaBensinkort() {
        // Motsatsen måste också hålla: filtret får inte tömma bensinkortet
        ExpertInsight bensinPolo = insikt("Teknikens Värld", "Volkswagen", "Polo",
                "Provbilen hade en 115-hästkrafts TSI-motor kopplad till en dubbelkopplingslåda.", 7);
        when(repo.findAll()).thenReturn(List.of(bensinPolo));
        assertThat(service().findForCarTitle("Volkswagen Polo 1.0 TSI Life")).hasSize(1);
    }

    // --- U+2011: AI:ns hårda bindestreck får inte stänga av drivlinevakten ---

    @Test
    void drivetrainOfKlararSmaltBindestreck() {
        // Skarpt fall 2026-08-24: "plug‑in" (NON-BREAKING HYPHEN) matchade inte plug[- ]?in
        assertThat(ExpertInsightService.drivetrainOf("Audi A6 plug‑in‑hybrid drar lite"))
                .isEqualTo("phev");
        assertThat(ExpertInsightService.drivetrainOf("En self‑charging lösning utan sladd"))
                .isEqualTo("hev");
        assertThat(ExpertInsightService.drivetrainOf("Bilen har kam‑rem")).isNull(); // delat ord är inte "kamrem"
    }

    @Test
    void laddhybridinsiktMedSmaltStreckVisasIntePaElbilskort() {
        // Hela kedjan: raden låg i drift på Audi A6 Avant e-tron, ett rent elbilskort
        // Ordet "hybrid" är medvetet BORTA ur texten: med det kvar fastnar raden på HEV-markören
        // och testet blir grönt även utan fixen — det är plug‑in-ordet som ska bära domen
        ExpertInsight phev = insikt("Bilexpert", "Audi", "A6",
                "Audi A6 plug‑in laddar på 2 timmar och har bäst bränsleekonomi i klassen.", 8);
        when(repo.findAll()).thenReturn(List.of(phev));
        when(evSpecService.isKnownEv("Audi A6 Avant e-tron quattro")).thenReturn(true);
        assertThat(service().findForCarTitle("Audi A6 Avant e-tron quattro")).isEmpty();
    }

    @Test
    void modellnamnMedSmaltBindestreckMatcharSittEgetKort() {
        // 12 modellnamn stavas "C‑HR", "E‑Klass", "Puma Gen‑E" — titlarna kommer från
        // kurerad CSV med vanligt streck, så raderna kunde aldrig nå sina egna kort
        ExpertInsight chr = insikt("M3", "Toyota", "C‑HR", "Bagageutrymmet är litet baktill.", 7);
        when(repo.findAll()).thenReturn(List.of(chr));
        assertThat(service().findForCarTitle("Toyota C-HR 1.8 Hybrid 122 hk")).hasSize(1);
    }

    @Test
    void flattenSpacesKokarNerAllaStreckvarianter() {
        assertThat(ExpertInsightService.flattenSpaces("C‑HR")).isEqualTo("c-hr");
        assertThat(ExpertInsightService.flattenSpaces("C‐HR")).isEqualTo("c-hr");
        assertThat(ExpertInsightService.flattenSpaces("C–HR")).isEqualTo("c-hr");
        assertThat(ExpertInsightService.flattenSpaces("C—HR")).isEqualTo("c-hr");
        assertThat(ExpertInsightService.flattenSpaces("C−HR")).isEqualTo("c-hr");
        assertThat(ExpertInsightService.flattenSpaces("C-HR")).isEqualTo("c-hr");
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

    // --- listRecent (admin-vyn) ---

    /**
     * Admin-svaret måste bära fuel_type, inte bara kategorin. Rader utan car_make (allmänna
     * köpråd) når varken bilkort eller chatt — de filtreras bort på carMake == null — och kommer
     * bara in i rekommendationsprompten via buildExpertContext, som matchar kategori ELLER
     * fuel_type. Utan fältet i listan går det inte att skilja en rad som når AI:n från en som
     * ligger död i tabellen: 2026-08-29 stod fem sådana rader omätbara just därför.
     */
    @Test
    void adminlistanBarFuelTypeAvenNarMarketSaknas() {
        ExpertInsight utanMarke = new ExpertInsight("Bilexpert", null, null, "el", null,
                "Räkna med 20-25 % sämre räckvidd vintertid.", null);
        when(repo.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(List.of(utanMarke));

        Map<String, Object> rad = service().listRecent(null, 50).get(0);

        assertThat(rad).containsEntry("fuelType", "el")
                       .containsEntry("carMake", null)
                       .containsEntry("category", null);
    }

    // --- kategorivakten (InsightTaxonomy) ---

    /**
     * Kategorin är inte fri text: buildExpertContext matchar den mot formulärets värde med
     * likhet, så "crossover" gör raden osynlig för rekommendationsprompten i stället för
     * felplacerad. Den skrevs tyst före 2026-08-29 — två Dacia Striker-rader låg så i drift.
     */
    @Test
    void patchOkandKategoriAvvisas() {
        raddaMedId(42L);
        assertThatThrownBy(() -> service().updateInsight(42L, Map.of("category", "crossover")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Okänd kategori");
        verify(repo, never()).save(any());
    }

    /** Alias är stavningar av en kategori som FINNS — de skrivs om, inte avvisas. */
    @Test
    void patchSkriverOmAliasTillFormularetsVarde() {
        ExpertInsight row = raddaMedId(42L);
        savePasserarIgenom();

        service().updateInsight(42L, Map.of("category", " Småbil "));

        assertThat(row.getCategory()).isEqualTo("smaabil");
    }

    /**
     * CSV-importen kastar inte raden — texten är fortfarande värd ett bilkort — men kategorin
     * nollas, för ett påhittat värde är samma sak som inget värde i matchningen.
     */
    @Test
    void csvImportNollarOkandKategoriMenBehallerRaden() {
        savePasserarIgenom();
        ArgumentCaptor<ExpertInsight> sparad = ArgumentCaptor.forClass(ExpertInsight.class);

        int antal = service().importCsv("Ford,Transit,diesel,transportbil,Rymlig skåpbil.,"
                + System.lineSeparator() + "Kia,Picanto,bensin,ekonomibil,Billig i drift.,");

        assertThat(antal).isEqualTo(2);
        verify(repo, times(2)).save(sparad.capture());
        assertThat(sparad.getAllValues().get(0).getCategory()).isNull();
        assertThat(sparad.getAllValues().get(0).getInsight()).isEqualTo("Rymlig skåpbil.");
        assertThat(sparad.getAllValues().get(1).getCategory()).isEqualTo("smaabil");
    }

    /**
     * Kategoribytet finns för att RÄTTA stavningar. Utan kontroll av målet kunde samma
     * endpoint skapa felet den ska laga: "suv" -> "crossover" gör 382 rader osynliga i ett anrop.
     */
    @Test
    void kategoribyteTillOkantVardeAvvisas() {
        assertThatThrownBy(() -> service().renameCategory("suv", "crossover"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Okänd kategori");
        verify(repo, never()).renameCategory(anyString(), anyString());
    }

    @Test
    void kategoribyteKanoniserarMalet() {
        when(repo.renameCategory("småbil", "smaabil")).thenReturn(12);
        assertThat(service().renameCategory("småbil", "Ekonomibil")).isEqualTo(12);
    }
}
