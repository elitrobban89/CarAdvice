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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    // Lenient: de allra flesta testerna gäller matchByTitle och når aldrig företrädeskollen
    // i isKnownEv, så en strikt stubb hade fällt dem på "unnecessary stubbing".
    @Mock(lenient = true)
    private IceConsumptionService iceConsumptionService;

    private EvSpecService service() {
        return new EvSpecService(repo, iceConsumptionService);
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

    // ── PHEV-rader får inte fastna på ett elbilskort ────────────────────────────

    @Test
    void laddhybridradenMatcharInteEttElbilskort() {
        // Skarpt fall 2026-08-11: kortet "Hyundai Kona Electric (2020)" fick motoralternativet
        // "8.9 kWh (58 km) · PHEV". Matchningen strippar "Electric" (för att "MG4 Electric" ska
        // hitta "MG4"), så titeln blir "Hyundai Kona" — och båda orden finns i "Hyundai Kona PHEV".
        EvSpec elbil = new EvSpec("Hyundai Kona Electric", 11.0, 100.0, 65.4, 514, 400_000);
        EvSpec phev  = new EvSpec("Hyundai Kona PHEV", 3.7, 0.0, 8.9, 58, 290_000);
        when(repo.findAll()).thenReturn(List.of(phev, elbil));

        assertThat(service().verifiedEngineOptions("Hyundai Kona Electric (2020)"))
                .contains("65.4").doesNotContain("8.9");
        assertThat(service().formatForTitle("Hyundai Kona Electric (2020)", 15000).wltpKm())
                .isEqualTo(514);
    }

    @Test
    void laddhybridradenHittarFortfarandeSinEgenTitel() {
        // Spärren prövas mot titelns EGNA ord före strippningen, annars hade PHEV-kortet
        // blivit av med sin enda rad
        EvSpec phev = new EvSpec("Kia Niro PHEV", 3.7, 0.0, 8.9, 58, 290_000);
        when(repo.findAll()).thenReturn(List.of(phev));
        assertThat(service().formatForTitle("Kia Niro PHEV (2021)", 15000)).isNotNull();
    }

    @Test
    void gteRadenFastnarInteVidEGolf() {
        // Andra omgången av samma bugg, hittad i verifieringssöket 2026-08-11: kortet
        // "Volkswagen e-Golf (2019)" fick "13 kWh (70 km) · GTE". e-prefixregeln gör titeln till
        // "volkswagen golf", som matchar laddhybriden Golf GTE. Spärren tog bara "phev"/"hev" —
        // tabellen har flera namnkonventioner för samma sak.
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Volkswagen Golf GTE", 3.6, 0.0, 13.0, 70, 0)));
        assertThat(service().verifiedEngineOptions("Volkswagen e-Golf (2019)")).isNull();
        assertThat(service().isKnownEv("Volkswagen e-Golf")).isFalse();
        // ...men GTE-kortet hittar fortfarande sin egen rad
        assertThat(service().isKnownEv("Volkswagen Golf GTE (2020)")).isTrue();
    }

    @Test
    void plugInRadenFastnarInteVidBasmodellen() {
        // Toyota Prius Plug-in och Toyota RAV4 Plug-in är samma bilar som Prius/RAV4 PHEV under
        // en annan namnkonvention — båda konventionerna måste spärras
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Toyota Prius Plug-in", 3.3, 0.0, 8.8, 69, 0)));
        assertThat(service().isKnownEv("Toyota Prius")).isFalse();
        assertThat(service().isKnownEv("Toyota Prius Plug-in (2021)")).isTrue();
    }

    @Test
    void elbilarMedLaddhybridliknandeNamnSlappsIgenom() {
        // Filtret får inte bli för brett: "Recharge" är Volvos namn för BÅDE elbil och
        // laddhybrid, DS "E-Tense" likaså, och Jeeps "4xe" sitter på en 96 kWh-elbil.
        // Alla tre är riktiga elbilar i tabellen och deras kort ska fungera.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo XC40 Recharge", 11.0, 150.0, 75.0, 530, 465_000),
                new EvSpec("DS Automobiles DS 3 E-Tense", 11.0, 100.0, 50.8, 404, 0),
                new EvSpec("Jeep Compass Electric 4xe", 11.0, 160.0, 96.1, 606, 0)));

        assertThat(service().isKnownEv("Volvo XC40 Recharge")).isTrue();
        assertThat(service().isKnownEv("DS Automobiles DS 3 E-Tense")).isTrue();
        assertThat(service().isKnownEv("Jeep Compass Electric 4xe")).isTrue();
    }

    @Test
    void bensinbilMedLaddhybridvariantRaknasInteSomElbil() {
        // isKnownEv svarar insiktsfiltret på "är kortet en ren elbil?". "Volvo XC60" matchade
        // "Volvo XC60 PHEV", så en bensin-XC60 klassades som elbil och tappade sina
        // förbränningsinsikter — samma matchning, större skada än fel siffra i ett chip.
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Volvo XC60 PHEV", 3.7, 0.0, 18.8, 68, 500_000)));
        assertThat(service().isKnownEv("Volvo XC60")).isFalse();
        assertThat(service().isKnownEv("Volvo XC60 PHEV")).isTrue();
    }

    // ── Årsmodellen väljer generation också i spec-chipsen ──────────────────────

    @Test
    void arsmodellenValjerGenerationAvenIMatchByTitle() {
        // keepGenerationForYear satt bara i verifiedEngineOptions, så motorlistan visade rätt
        // generation medan chipsen bredvid kunde visa den andra. Samma generationsdata styr nu båda.
        EvSpec gen1 = new EvSpec("MG4 Long Range", 11.0, 140.0, 64.0, 450, 300_000);
        EvSpec gen2 = new EvSpec("MG4 Premium Long Range", 11.0, 144.0, 52.8, 416, 320_000);
        when(repo.findAll()).thenReturn(List.of(gen1, gen2));

        // 2023 är gen 1 — gen 2 började säljas 2025
        assertThat(service().formatForTitle("MG4 Long Range (2023)", 15000).wltpKm()).isEqualTo(450);
    }

    /** Leaf-raderna som DataLoader skapar dem, plus synkens rad för 2026 års bil. */
    private static List<EvSpec> leafRader() {
        return List.of(
                new EvSpec("Nissan Leaf",            6.6, 150.0, 75.1, 624, 290_000),  // gen 3, 2026
                new EvSpec("Nissan Leaf 24 kWh",     3.6,  50.0, 24.0, 120, 0),        // gen 1
                new EvSpec("Nissan Leaf 30 kWh",     3.6,  50.0, 30.0, 150, 0),        // gen 1
                new EvSpec("Nissan Leaf 40 kWh",     6.6,  50.0, 40.0, 270, 0),        // gen 2
                new EvSpec("Nissan Leaf e+ 62 kWh",  6.6, 100.0, 62.0, 385, 0));       // gen 2
    }

    @Test
    void leafFran2019FarAndraGenerationensSiffror() {
        // Skarpt fall 2026-08-11: ett elbilssök på 175 000 kr gav kortet "Nissan Leaf (2019)"
        // med motoralternativet "75.1 kWh (624 km)" — 2026 års bil. Det var den enda Leaf-raden
        // som fanns, så årsfiltret hade inget att välja mellan.
        when(repo.findAll()).thenReturn(leafRader());

        assertThat(service().verifiedEngineOptions("Nissan Leaf (2019)"))
                .contains("40").contains("62")
                .doesNotContain("75.1").doesNotContain("24").doesNotContain("30");
        // och chipsen bredvid ska visa samma generation
        assertThat(service().formatForTitle("Nissan Leaf (2019)", 15000).wltpKm())
                .isIn(270, 385);
    }

    @Test
    void leafFran2013FarForstaGenerationensSiffror() {
        when(repo.findAll()).thenReturn(leafRader());
        assertThat(service().verifiedEngineOptions("Nissan Leaf (2013)"))
                .contains("24").doesNotContain("40").doesNotContain("75.1");
    }

    @Test
    void leafFran2026FarNyasteRaden() {
        // Den otaggade raden ÄR den nyaste bilen här, tvärtemot MG4 där otaggat = äldst.
        // Utan en egen gen 3-tagg hade 2026 års Leaf räknats som den äldsta generationen.
        when(repo.findAll()).thenReturn(leafRader());
        assertThat(service().formatForTitle("Nissan Leaf (2026)", 15000).wltpKm()).isEqualTo(624);
    }

    @Test
    void leafUtanArsmodellVisarAllaGenerationer() {
        // Ingen årsmodell = inget att välja på. Hellre för mycket information än fel.
        when(repo.findAll()).thenReturn(leafRader());
        assertThat(service().verifiedEngineOptions("Nissan Leaf"))
                .contains("24").contains("40").contains("62").contains("75.1");
    }

    @Test
    void zsEvFran2020FarPreFaceliftSiffror() {
        // Samma fall som Leaf, hittat i samma verifieringssök: den enda ZS-raden var
        // faceliftens Long Range (72,6 kWh / 440 km), så kortet "MG ZS EV (2020)" fick
        // 2022 års bil. Pre-facelift var 44,5 kWh / 263 km.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG ZS EV",          11.0, 92.0, 72.6, 440, 300_000),   // facelift LR
                new EvSpec("MG ZS EV 44.5 kWh",  6.6, 76.0, 44.5, 263, 0)));       // pre-facelift

        assertThat(service().formatForTitle("MG ZS EV (2020)", 15000).wltpKm()).isEqualTo(263);
        assertThat(service().formatForTitle("MG ZS EV (2023)", 15000).wltpKm()).isEqualTo(440);
    }

    @Test
    void enyaqIvOchFaceliftBlandasInte() {
        // Femte ingången till samma bugg: Enyaq ligger i tabellen under TVÅ namnkonventioner.
        // "iV" är förfaceliftnamnet (2020-2025), utan iV är faceliften (2025-). Uppmätt
        // 2026-08-12 stod alla fem raderna som parallella val på samma kort.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Škoda Enyaq iV 60", 11.0, 135.0, 58.0, 390, 420_000),
                new EvSpec("Škoda Enyaq iV 80", 11.0, 135.0, 82.0, 530, 540_000),
                new EvSpec("Škoda Enyaq iV 85", 11.0, 175.0, 82.0, 550, 490_000),
                new EvSpec("Škoda Enyaq 60",    11.0, 105.0, 58.0, 455, 494_000),
                new EvSpec("Škoda Enyaq 85",    11.0, 165.0, 77.0, 582, 535_000)));

        // Vilken VARIANT som väljs inom generationen avgörs av prisvärdhetsrankningen och prövas
        // på annat håll; det här testet låser att generationen är rätt. En 2022:a måste hamna på
        // en iV-rad (390/530/550 km) och aldrig på faceliftens (455/582).
        assertThat(service().formatForTitle("Škoda Enyaq (2022)", 15000).wltpKm()).isIn(390, 530, 550);
        assertThat(service().formatForTitle("Škoda Enyaq (2026)", 15000).wltpKm()).isIn(455, 582);
    }

    @Test
    void enyaqKortetVisarBaraSinEgenGenerationsMotorer() {
        // Frågan som avslöjade felet: "vilka är Long Range-alternativen?" gick inte att svara på
        // eftersom listan blandade två generationer. En iV ska visa iV:ns utbud, inget annat.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Škoda Enyaq iV 60", 11.0, 135.0, 58.0, 390, 420_000),
                new EvSpec("Škoda Enyaq iV 85", 11.0, 175.0, 82.0, 550, 490_000),
                new EvSpec("Škoda Enyaq 85",    11.0, 165.0, 77.0, 582, 535_000)));

        String iv = service().verifiedEngineOptions("Škoda Enyaq (2022)");

        assertThat(iv).contains("390 km").contains("550 km");
        assertThat(iv).doesNotContain("582 km");   // faceliftens rad hör inte hit
    }

    @Test
    void eGolfHittarSinaRaderMenBensinGolfGorDetInte() {
        // e-Golf saknades helt i tabellen, så kortet föll tillbaka på AI:ns fritext (som gav
        // "45 kWh" — en kapacitet bilen aldrig haft). Raderna finns nu, men "Golf" ensamt är
        // en av Sveriges vanligaste bensinbilar och får INTE plocka upp dem.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volkswagen e-Golf 24.2 kWh", 3.6, 40.0, 24.2, 130, 0),
                new EvSpec("Volkswagen e-Golf 35.8 kWh", 7.2, 40.0, 35.8, 231, 0)));

        assertThat(service().verifiedEngineOptions("Volkswagen e-Golf (2019)"))
                .contains("35.8").contains("231");
        assertThat(service().isKnownEv("Volkswagen Golf")).isFalse();
        assertThat(service().isKnownEv("Volkswagen Golf (2019)")).isFalse();
    }

    @Test
    void hybridCHRFarInteElbilsCHRnsSiffror() {
        // Femte ingången till samma bugg, rapporterad live 2026-08-14: ett SUV/bensin-sök på
        // 250 000 kr gav "Toyota C-HR (2021)" rådet "ladda var 13:e dag". Bilen ÄR en hybrid —
        // Blocket-matchningen gjorde rätt — men pass 1 matchar titelns ord som DELSTRÄNGAR i
        // det lagrade namnet, och "c-hr" är delsträng av "c-hr+". Plustecknet var det enda som
        // bar identiteten, och delsträngsmatchningen raderade just det.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Toyota C-HR+ 57.7 kWh", 11.0, 150.0, 54.0, 458, 437_000),
                new EvSpec("Toyota C-HR+ 77 kWh", 11.0, 150.0, 72.0, 609, 506_000)));

        assertThat(service().formatForTitle("Toyota C-HR (2021)", 15000)).isNull();
        assertThat(service().verifiedEngineOptions("Toyota C-HR (2021)")).isNull();
        assertThat(service().isKnownEv("Toyota C-HR")).isFalse();
        // ...men elbilen hittar fortfarande sin egen rad
        assertThat(service().isKnownEv("Toyota C-HR+ (2026)")).isTrue();
    }

    @Test
    void bensinMercedesFarInteEQnsSiffror() {
        // Samma inventering 2026-08-14: Mercedes-trion är värre än C-HR, för här är även
        // motorbeteckningen delsträng — en bensin-"GLA 250" träffar "GLA 250+" eftersom
        // "250" är delsträng av "250+". Alla tre har rader i ice_consumption (CLA 3, GLA 5,
        // GLB 4), så det finns en live-väg till varje.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Mercedes-Benz CLA 250+", 11.0, 320.0, 85.0, 792, 0),
                new EvSpec("Mercedes-Benz GLA 250+", 11.0, 320.0, 85.0, 700, 0),
                new EvSpec("Mercedes-Benz GLB 250+", 11.0, 320.0, 85.0, 700, 0)));

        // Plus-fallet är strukturellt löst av ordgränsmatchningen: "250" och "250+" är
        // olika ord, så bensinbilens motorbeteckning når inte elbilens rad.
        assertThat(service().isKnownEv("Mercedes-Benz GLA 250")).isFalse();
        assertThat(service().isKnownEv("Mercedes-Benz GLB 350")).isFalse();
        assertThat(service().formatForTitle("Mercedes-Benz GLA 250 (2021)", 15000)).isNull();
        // ...och elbilen känns igen när titeln bär plustecknet
        assertThat(service().isKnownEv("Mercedes-Benz CLA 250+ (2026)")).isTrue();

        // GRÄNSEN, medvetet inte löst här: en NAKEN namnplåt når fortfarande raden, eftersom
        // radens ord är en äkta övermängd av titelns. Ingen strängregel kan skilja dem åt —
        // "Mercedes-Benz CLA" finns som både bensinbil och elbil, och i den riktiga tabellen
        // hjälper det inte att blockera "250+" eftersom raden "Mercedes-Benz CLA 200" (58 kWh,
        // också elbil) fångar samma titel. Mätningen 2026-08-14 gav därför exakt samma 52
        // felträffar med och utan plus-tokens. Den här klassen avgörs ett lager upp, av
        // drivmedelsvakten och av att ice_consumption prövas före ev_spec.
        assertThat(service().isKnownEv("Mercedes-Benz CLA (2020)")).isTrue();
    }

    @Test
    void bensinXC40FarInteRechargeSiffror() {
        // Sjätte ingången, hittad i det skarpa söket 2026-08-14 som verifierade C-HR-fixen:
        // SUV/bensin/250 000 kr gav kortet "Volvo XC40 (2022)" en elbils evSpec (75 kWh,
        // 530 km, "ladda var 11:e dag") SAMTIDIGT som fuelSpec korrekt visade bensinmotorn
        // B4 197 hk / 0,8 l per mil. "Recharge" var medvetet undantagen ur spärren för att
        // elbilskorten skulle fungera — men undantaget skyddade inte bensinbilen.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo XC40 Recharge", 11.0, 150.0, 75.0, 530, 465_000),
                new EvSpec("Volvo XC40 Recharge Twin", 11.0, 150.0, 75.0, 424, 0)));

        assertThat(service().formatForTitle("Volvo XC40 (2022)", 15000)).isNull();
        assertThat(service().verifiedEngineOptions("Volvo XC40 (2022)")).isNull();
        assertThat(service().isKnownEv("Volvo XC40")).isFalse();
        // ...men elbilen hittar fortfarande sin egen rad när titeln bär namnet
        assertThat(service().isKnownEv("Volvo XC40 Recharge (2022)")).isTrue();
        assertThat(service().verifiedEngineOptions("Volvo XC40 Recharge (2022)")).isNotNull();
    }

    @Test
    void isKnownEvGerIceConsumptionForetrade() {
        // "Hyundai Kona" och "Kia Niro" finns som BÅDE bensinbil och elbil, och matchningen
        // svarar ja på den nakna titeln eftersom radens ord ("Kona Electric") är en äkta
        // övermängd av titelns. Kortet klassades då som ren elbil och tappade sina
        // förbränningsinsikter — kamrems- och oljeråd som hör hemma just där.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Hyundai Kona Electric 64 kWh", 11.0, 77.0, 64.8, 484, 0)));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Hyundai", "Kona 1.6 T-GDI 198 hk", "bensin", 0.68));

        assertThat(service().isKnownEv("Hyundai Kona (2020)")).isFalse();
        // Säger titeln själv att den är elbil vinner den över förbränningsraden
        assertThat(service().isKnownEv("Hyundai Kona Electric (2020)")).isTrue();
        // Spec-chipsen är en annan fråga och rörs inte — matchningen finns kvar
        assertThat(service().formatForTitle("Hyundai Kona Electric (2020)", 15000)).isNotNull();
    }

    @Test
    void rentElbilsnamnUtanForbranningsradForblirElbil() {
        // Skälet att INTE lösa det med en "electric"-token: Dacia Spring och Alpine A290 bär
        // ordet men finns BARA som elbil, så en ordlista hade tystat deras kort. Samma gräns
        // som för e-prefixen. Utan förbränningsrad står ev_spec-svaret kvar.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Dacia Spring Electric 100", 7.0, 30.0, 24.0, 220, 0),
                new EvSpec("Alpine A290 Electric 220 hp", 11.0, 100.0, 52.0, 380, 0)));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any())).thenReturn(null);

        assertThat(service().isKnownEv("Dacia Spring (2022)")).isTrue();
        assertThat(service().isKnownEv("Alpine A290 (2025)")).isTrue();
    }

    @Test
    void isKnownEvFailarOpenVidDbFel() {
        // Namnträffen finns — en trasig uppslagning får inte göra en elbil till något annat.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 0)));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenThrow(new RuntimeException("db nere"));

        assertThat(service().isKnownEv("Volvo EX30 (2024)")).isTrue();
    }

    @Test
    void tekniskUppdateringskodLasesInteSomArsmodell() {
        // Ordgränsmatchningen (2026-08-14) avslöjade ett parsningsfel som delsträngsmatchningen
        // dolde: ev-databases tekniska uppdateringskod "(TU2025)" träffade årsstrippningen, som
        // kapade "2025)" mitt i ordet och lämnade skräpordet "(tu" i titeln. Raden kunde då inte
        // matcha ens sitt EGET namn, och modelYear läste 2025 som annonsens årsmodell.
        assertThat(EvSpecService.modelYear("Renault Scenic E-Tech EV60 170hp (TU2025)")).isZero();
        assertThat(EvSpecService.modelYear("Toyota C-HR (2021)")).isEqualTo(2021);
        assertThat(EvSpecService.modelYear("Volvo XC40 2022")).isEqualTo(2022);

        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Renault Scenic E-Tech EV60 170hp (TU2025)", 22.0, 130.0, 60.0, 430, 0)));
        assertThat(service().isKnownEv("Renault Scenic E-Tech EV60 170hp (TU2025)")).isTrue();
    }

    @Test
    void streckvarianterINormaliseringen() {
        // Samma sorts fälla som de smala mellanslagen: AI:n skrev "Toyota C‑HR" med
        // icke-brytande bindestreck i det skarpa söket 2026-08-14. Lagrade namn bär U+002D,
        // så en sådan titel kunde aldrig matcha någon rad alls — tyst fel, kortet föll bara
        // tillbaka på AI:ns fritext. Måste fungera åt BÅDA håll: en riktig träff ska bli av,
        // och spärrarna ska fortsätta bita fast strecket är av en annan sort.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Ford Mustang Mach-E", 11.0, 150.0, 88.0, 600, 0),
                new EvSpec("Toyota C-HR+ 77 kWh", 11.0, 150.0, 72.0, 609, 506_000)));

        assertThat(service().isKnownEv("Ford Mustang Mach‑E (2021)")).isTrue();   // U+2011
        assertThat(service().isKnownEv("Ford Mustang Mach–E (2021)")).isTrue();   // U+2013
        assertThat(service().isKnownEv("Ford Mustang Mach-E (2021)")).isTrue();        // U+002D
        // Plus-spärren biter även när titeln bär en annan streckvariant
        assertThat(service().isKnownEv("Toyota C‑HR (2021)")).isFalse();
    }

    @Test
    void elbilarMedPlusITrimnamnSlappsIgenom() {
        // Gränsen åt andra hållet: spärren får INTE bli ett generellt "alla plus-tokens".
        // För de här modellerna finns inget basord som förbränningsbil, så en enkel titel
        // ska hitta trimraden — samma resonemang som "Audi Q4 e-tron behöver inget skydd".
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Smart #1 Pro+", 22.0, 150.0, 62.0, 440, 0),
                new EvSpec("XPENG P7+ RWD Long Range", 11.0, 168.0, 73.0, 602, 0),
                new EvSpec("Geely EX5 Pro+", 11.0, 100.0, 68.4, 430, 0),
                new EvSpec("Nissan Leaf e+ 62 kWh", 6.6, 100.0, 62.0, 385, 0)));

        assertThat(service().isKnownEv("Smart #1")).isTrue();
        assertThat(service().isKnownEv("XPENG P7+")).isTrue();
        assertThat(service().isKnownEv("Geely EX5")).isTrue();
        assertThat(service().isKnownEv("Nissan Leaf e+ (2019)")).isTrue();
    }

    @Test
    void ePrefixIRadnamnetHittasMedOrdmatchning() {
        // Titelsidan har alltid strippat "e-", den lagrade sidan inte — så rader vars MODELLORD
        // bär prefixet var omöjliga att träffa med ordmatchning och fick aldrig några
        // verifierade motoralternativ. Gäller ~30 rader: e-Tourneo, e-Caravelle, e-Rifter...
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Ford e-Tourneo Courier", 11.0, 100.0, 43.6, 296, 0)));
        assertThat(service().verifiedEngineOptions("Ford e-Tourneo Courier (2024)"))
                .contains("43.6");
    }

    @Test
    void trimSomBaraUpprepningAvKwhTasBort() {
        // Raderna vi själva lagt in för utgångna generationer särskiljs av kWh-talet i namnet,
        // så trimmet blev en upprepning: "44.5 kWh (263 km) · 44.5 kWh"
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG ZS EV 44.5 kWh", 6.6, 76.0, 44.5, 263, 0)));
        assertThat(service().verifiedEngineOptions("MG ZS EV (2020)")).isEqualTo("44.5 kWh (263 km)");
    }

    @Test
    void trimSomSagerNagotMerBehalls() {
        // "e+" är modellbeteckningen, inte kapaciteten — den raden ska fortfarande märkas ut
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Nissan Leaf e+ 62 kWh", 6.6, 100.0, 62.0, 385, 0)));
        assertThat(service().verifiedEngineOptions("Nissan Leaf (2019)")).contains("e+");
    }

    @Test
    void utanArsmodellRorsGenerationsvaletInte() {
        // Ingen årsmodell i titeln = inget att välja på; passens egen tiebreak gäller som förut
        EvSpec gen1 = new EvSpec("MG4 Long Range", 11.0, 140.0, 64.0, 450, 300_000);
        EvSpec gen2 = new EvSpec("MG4 Premium Long Range", 11.0, 144.0, 52.8, 416, 320_000);
        when(repo.findAll()).thenReturn(List.of(gen1, gen2));
        assertThat(service().formatForTitle("MG4 Long Range", 15000)).isNotNull();
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

        // Samma kapacitet men olika rackvidd = tva riktiga varianter, alltsa var sin rad med
        // sitt namn. Forut blev de "65 kWh (450–480 km)" och anvandaren kunde inte se vilken
        // version raden gallde — det var hela poangen med faltet.
        assertThat(service().verifiedEngineOptions("Volvo EX30 (2024)"))
                .isEqualTo("51 kWh (344 km) · Single Motor, "
                         + "65 kWh (450 km) · Twin Motor Performance, "
                         + "65 kWh (480 km) · Single Motor Extended Range");
    }

    @Test
    void nettoOchBruttokapacitetForSammaBatteriBlirEnRad() {
        // Samma bil listad av tva kallor: EV6 Standard Range ligger inne bade som 60 kWh
        // (netto) och 63 kWh (brutto) — OLIKA kapacitet men SAMMA rackvidd, vilket ar
        // signaturen for en dubblett. Utan ihopslagningen visas bilen tva ganger.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Standard Range 2WD", 11.0, 180.0, 60.0, 428, 400_000),
                new EvSpec("Kia EV6 Standard Range 63 kWh", 11.0, 180.0, 63.0, 428, 400_000)));

        assertThat(service().verifiedEngineOptions("Kia EV6")).isEqualTo("63 kWh (428 km)");
    }

    @Test
    void sammaKapacitetMedOlikaRackviddArTvaVarianter() {
        // Motsatsen till testet ovan och skalet till att regeln bytte form 2026-08-10: MG4:s
        // 52,8 kWh finns som tva olika bilar (Urban Comfort 416 km, Urban Premium 405 km) och
        // 61,7 kWh som tva till (Premium Long Range 452 km, XPOWER 405 km). Enbart pa
        // kapaciteten sags de som ett batteri och slogs ihop till "52.8 kWh (405–416 km)" —
        // utan namn, eftersom en hopslagen grupp lamnas omarkt. Anvandaren sag da inte vilken
        // version raden gallde. Rackvidden ar det som skiljer variant fran dubblett.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Urban Comfort Long Range", 11.0, 87.0, 52.8, 416, 0),
                new EvSpec("MG4 Urban Premium Long Range", 11.0, 87.0, 52.8, 405, 0),
                new EvSpec("MG4 Premium Long Range", 11.0, 154.0, 61.7, 452, 0),
                new EvSpec("MG MG4 XPOWER", 11.0, 140.0, 61.7, 405, 471_000)));

        assertThat(service().verifiedEngineOptions("MG4 (2025)"))
                .isEqualTo("52.8 kWh (405 km) · Urban Premium Long Range, "
                         + "52.8 kWh (416 km) · Urban Comfort Long Range, "
                         + "61.7 kWh (405 km) · XPOWER, "
                         + "61.7 kWh (452 km) · Premium Long Range");
    }

    @Test
    void variantUtanRackviddSlasIhopSomForut() {
        // Saknas rackvidden finns inget att skilja raderna at med — da galler den gamla
        // regeln, annars hade en rad utan siffror blivit en egen tom variant.
        //
        // Fixturen anvande tidigare EV6, men EV6 ar generationstaggad sedan 2026-08-13 och
        // generationen ingar i gruppidentiteten — tva rader ur olika generationer slas darfor
        // aldrig ihop, oavsett rackvidd. Regeln som testas har handlar om den saknade
        // rackvidden, sa fixturen anvander nu en OTAGGAD modell for att prova den i isolering.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV9 Long Range", 11.0, 233.0, 96.0, 0, 0),
                new EvSpec("Kia EV9 Long Range 2WD", 11.0, 233.0, 99.8, 579, 0)));

        assertThat(service().verifiedEngineOptions("Kia EV9")).isEqualTo("99.8 kWh (579 km)");
    }

    @Test
    void stellantisFemtioOchFemtiofyraHallsIsarTrotsDiakritisktNamn() {
        // Bada generationerna fanns redan som rader — bara taggen saknades, alltsa klass B.
        // Testet finns lika mycket for NYCKELFORMEN: GENERATION slas upp med normalize(carName),
        // som tar bort diakriter men BEHALLER bindestreck. "Citroën ë-C4" blir "citroen e-c4".
        // En felstavad nyckel ger ingen traff, och en otaggad rad raknas som aldst — alltsa
        // exakt det fel taggningen skulle laga, fast tyst.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Citroën ë-C4", 11.0, 100.0, 46.3, 354, 0),
                new EvSpec("Citroën ë-C4 54 kWh", 11.0, 100.0, 50.8, 418, 0)));

        assertThat(service().verifiedEngineOptions("Citroën ë-C4 (2022)"))
                .contains("46.3 kWh (354 km)").doesNotContain("418 km");
        assertThat(service().verifiedEngineOptions("Citroën ë-C4 (2025)"))
                .contains("50.8 kWh (418 km)").doesNotContain("354 km");
    }

    @Test
    void suffixSkyddarInteEnRadFranAttHamnaPaFelKort() {
        // Forsta inventeringen friade ID.3 med motiveringen att "Neo" i radnamnet skulle hindra
        // en match mot titeln "Volkswagen ID.3". Det var bakvant: verifiedEngineOptions kraver
        // att varje ord i TITELN finns i RADENS namn, sa en LANGRE rad matchar alltid en KORTARE
        // titel. Live 2026-08-13 gav ett kort for en 2022:a Neo-varianter med 630 km.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volkswagen ID.3 Pro 58 kWh",  11.0, 120.0, 58.0, 409, 0),
                new EvSpec("Volkswagen ID.3 Neo 79 kWh",  11.0, 185.0, 79.0, 630, 0)));

        // Utan tagg drar den korta titeln in bada raderna — det ar sjalva exponeringen.
        assertThat(service().verifiedEngineOptions("Volkswagen ID.3 (2022)"))
                .contains("58 kWh (409 km)").doesNotContain("630 km");
        assertThat(service().verifiedEngineOptions("Volkswagen ID.3 (2026)"))
                .contains("79 kWh (630 km)").doesNotContain("409 km");
    }

    @Test
    void klassAModellerFarSinEgenGenerationEfterArsmodell() {
        // Inventeringen 2026-08-13 hittade nio modeller vars ENDA rad bar nyaste generationens
        // siffror, sa arsfiltret hade inget att valja mellan. Kona ar mallexemplet: gen 1
        // (2018-2022) fanns inte alls, sa ett kort for en 2020:a fick gen 2:s 65,4 kWh / 514 km.
        //
        // Testet provar bada hallen. Att bara prova den gamla arsmodellen hade missat den fallan
        // Leaf-arbetet skrev upp: en OTAGGAD rad raknas som fromYear 0, alltsa aldst, sa den
        // nyaste raden maste vara taggad for att den gamla arsmodellen inte ska fa den anda.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Hyundai Kona Electric 39 kWh", 7.2,  44.0, 39.2, 305, 0),
                new EvSpec("Hyundai Kona Electric 64 kWh", 11.0, 77.0, 64.0, 484, 0),
                new EvSpec("Hyundai Kona Electric",        11.0, 102.0, 65.4, 514, 0)));

        assertThat(service().verifiedEngineOptions("Hyundai Kona Electric (2020)"))
                .contains("39.2 kWh (305 km)").contains("64 kWh (484 km)")
                .doesNotContain("514 km");
        assertThat(service().verifiedEngineOptions("Hyundai Kona Electric (2024)"))
                .contains("65.4 kWh (514 km)")
                .doesNotContain("305 km").doesNotContain("484 km");
    }

    @Test
    void ev6PreFaceliftOchFaceliftSlasAldrigIhopEfterTaggningen() {
        // Inventeringen 2026-08-13 gick igenom alla 539 ev_spec-rader och EV6 var den ENDA
        // modell dar bada generationerna redan fanns men ingen var taggad. Ett kort for en
        // 2022:a kunde darfor fa faceliftens 84 kWh. Generationen ingar i gruppidentiteten,
        // sa taggningen halls isar dem aven nar kWh-avstandet ar litet (77,4 mot 80).
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Long Range 2WD 77.4 kWh", 11.0, 233.0, 77.4, 528, 0),
                new EvSpec("Kia EV6 Long Range 2WD", 11.0, 258.0, 80.0, 582, 0)));

        // Domen ar vilken GENERATION arsmodellen far, inte hur trimetiketten formateras.
        assertThat(service().verifiedEngineOptions("Kia EV6 (2022)"))
                .contains("77.4 kWh (528 km)").doesNotContain("80 kWh");
        assertThat(service().verifiedEngineOptions("Kia EV6 (2025)"))
                .contains("80 kWh (582 km)").doesNotContain("77.4 kWh");
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
                .isEqualTo("77.4 kWh (424 km) · GT 77.4 kWh, "
                         + "77.4 kWh (528 km) · Long Range 2WD 77.4 kWh, "
                         + "84 kWh (546 km) · Long Range AWD 84 kWh, "
                         + "84 kWh (582 km) · Long Range 2WD 84 kWh");
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
        EvSpec dup1 = new EvSpec("Volvo EX30 Twin Motor Performance", 11.0, 200.0, 69.0, 460, 430_000);
        EvSpec dup2 = new EvSpec("Volvo EX30 Twin Motor Performance", 11.0, 200.0, 69.0, 460, 430_000);
        when(repo.findAll()).thenReturn(List.of(dup1, dup2));
        assertThat(service().verifiedEngineOptions("Volvo EX30"))
                .isEqualTo("69 kWh (460 km) · Twin Motor Performance");
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
        // En hopslagen grupp beskriver samma bil under tva kallors namn — vilket av dem som
        // skrivs ut vore godtyckligt, sa gruppen lamnas omarkt
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Standard Range 2WD", 11.0, 180.0, 60.0, 428, 400_000),
                new EvSpec("Kia EV6 Standard Range 63 kWh", 11.0, 180.0, 63.0, 428, 400_000)));

        assertThat(service().verifiedEngineOptions("Kia EV6")).isEqualTo("63 kWh (428 km)");
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
    void dubbletterInomSammaGenerationSlasFortfarandeIhop() {
        // Generationsspärren får inte slå sönder den vanliga ihopslagningen: båda raderna är
        // gen 2, olika kapacitet och samma räckvidd, alltså samma bil netto och brutto
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Urban Comfort Long Range", 11.0, 87.0, 52.8, 416, 0),
                new EvSpec("MG4 Urban Premium Long Range", 11.0, 87.0, 54.0, 416, 0)));

        assertThat(service().verifiedEngineOptions("MG4")).isEqualTo("54 kWh (416 km)");
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
