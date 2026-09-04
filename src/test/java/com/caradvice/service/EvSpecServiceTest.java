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

    // ── verifiedEngineOptions pass 2: titeln får vara bredare än radnamnet ──────

    @Test
    void trimordITitelnStopparInteDenVerifieradeVariantlistan() {
        // Skarpt i drift 2026-08-29: AI:n döpte ett jämförelsekort till "Polestar 2 Single
        // Motor". Orden "single" och "motor" står inte i något radnamn, så pass 1 gav noll och
        // kortet föll tillbaka på AI:ns FRITEXT - medan spec-chipsen bredvid visade verifierade
        // siffror, eftersom matchByTitle har ett pass 2 som det här saknade.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000),
                new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0)));

        assertThat(service().verifiedEngineOptions("Polestar 2 Single Motor"))
                .isEqualTo("79 kWh (659 km) · från 2024");
    }

    @Test
    void pass1HarFortfarandeForetrade() {
        // Pass 2 är en RESERV, inte ett tillägg: när titeln matchar radnamnen direkt ska hela
        // variantlistan visas som förut, inte kapas till basmodellen.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000),
                new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0)));

        assertThat(service().verifiedEngineOptions("Polestar 2"))
                .contains("75 kWh (515 km)").contains("79 kWh (659 km)");
    }

    @Test
    void pass2SlapperInteEnLaddhybridrubrikPaEnElbilsrad() {
        // Samma riktning som i matchByTitle är den riskabla: titeln bredare än radnamnet.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo XC60", 11.0, 150.0, 78.0, 500, 600_000, "EV")));

        assertThat(service().verifiedEngineOptions("Volvo XC60 T8 Plug-in Hybrid")).isNull();
    }

    @Test
    void pass2SiffranEfterNamnetGorTitelnTillEnAnnanBil() {
        // "BYD Seal 6" är laddhybridkombin, en annan bil än elbilssedanen "BYD Seal".
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("BYD Seal", 11.0, 150.0, 82.5, 570, 500_000)));

        assertThat(service().verifiedEngineOptions("BYD Seal 6 DM-i")).isNull();
        assertThat(service().verifiedEngineOptions("BYD Seal Excellence AWD")).isNotNull();
    }

    // ── Generationen skrivs ut när listan spänner över flera ───────────────────

    @Test
    void polestar2VarianternaSagerVilkenGenerationDeGaller() {
        // Skarpt fall 2026-08-29: en användare jämförde Polestar 2 med Polestar 2 Long Range och
        // såg "79 kWh (659 km)" mot "75 kWh (515 km)" — en Long Range med MINDRE batteri än
        // basmodellen. Båda siffrorna är riktiga, de beskriver olika bilar: faceliften 2024 mot
        // förfaceliften. Raden sa inte det.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000),
                new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0)));

        String rader = service().verifiedEngineOptions("Polestar 2");
        assertThat(rader).contains("75 kWh (515 km)").contains("2020–2023");
        assertThat(rader).contains("79 kWh (659 km)").contains("från 2024");
    }

    @Test
    void enskildTaggadRadFarSittSpannUtanSyskonIListan() {
        // Bugganmälans andra kort. "Polestar 2 Long Range" matchar BARA förfaceliftraden —
        // den är den enda som heter Long Range — så listan spänner inte över flera
        // generationer och stod därför omärkt bredvid ett Polestar 2-kort med större batteri.
        // Modellnyckeln på Generation gör att slutåret ändå går att räkna ut.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000),
                new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0)));

        assertThat(service().verifiedEngineOptions("Polestar 2 Long Range"))
                .isEqualTo("75 kWh (515 km) · 2020–2023");
    }

    @Test
    void ingenGenerationsmarkningNarModellenBaraHarEn() {
        // "från 2020" på varje rad i en modell som bara funnits i ett utförande tillför inget
        // och gör listan längre — märkningen finns för att lösa en tvetydighet, inte som pynt.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 400_000),
                new EvSpec("Volvo EX30 Twin Motor", 11.0, 153.0, 69.0, 460, 450_000)));

        assertThat(service().verifiedEngineOptions("Volvo EX30"))
                .doesNotContain("från").doesNotContain("till 2");
    }

    @Test
    void arsmodellIRubrikenTarBortBehovetAvMarkning() {
        // Har annonsen ett årtal har generationsfiltret redan valt EN generation, och då svarar
        // kortet på en bestämd bil. Att ändå räkna upp årsspann hade varit brus.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000),
                new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0)));

        assertThat(service().verifiedEngineOptions("Polestar 2 (2021)"))
                .contains("75 kWh (515 km)").doesNotContain("2020–2023").doesNotContain("från");
    }

    // ── Radens EGET namn slår en längre rad ────────────────────────────────────

    @Test
    void radensEgetNamnSlarLangreRadMedFlerOrd() {
        // Skarpt fel 2026-08-29: /api/ev-spec?car=Polestar 2 svarade 75 kWh, 155 kW DC och
        // 515 km — siffrorna från "Polestar 2 Long Range 75 kWh". Kortet motsade sig självt,
        // eftersom motorlistan strax under DC-brickan listade radens egna 79 kWh (659 km).
        EvSpec egen   = new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000);
        EvSpec langre = new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0);
        when(repo.findAll()).thenReturn(List.of(langre, egen));

        EvSpecDto dto = service().formatForTitle("Polestar 2", 15000);
        assertThat(dto.wltpKm()).isEqualTo(659);
        assertThat(dto.maxDcKw()).isEqualTo(207);
        assertThat(dto.batteryKwh()).isEqualTo(79.0);
    }

    @Test
    void titelSomStavarUtVariantenFarFortfarandeDenLangreRaden() {
        // Motprovet till ovan: säger titeln SJÄLV "Long Range 75 kWh" är den längre raden
        // rätt svar, och exakthetsregeln får inte dra tillbaka den till basraden.
        EvSpec egen   = new EvSpec("Polestar 2", 11.0, 207.0, 79.0, 659, 510_000);
        EvSpec langre = new EvSpec("Polestar 2 Long Range 75 kWh", 11.0, 155.0, 75.0, 515, 0);
        when(repo.findAll()).thenReturn(List.of(egen, langre));

        assertThat(service().formatForTitle("Polestar 2 Long Range 75 kWh", 15000).wltpKm()).isEqualTo(515);
    }

    @Test
    void utanExaktRadVinnerFortfarandeLangstaNamnet() {
        // Finns ingen rad som HETER titeln rör regeln ingenting — pickForYear väljer som förr.
        EvSpec kort = new EvSpec("Volvo EX30 Single Motor", 11.0, 153.0, 51.0, 344, 400_000);
        EvSpec lang = new EvSpec("Volvo EX30 Single Motor Extended Range", 11.0, 153.0, 69.0, 480, 450_000);
        when(repo.findAll()).thenReturn(List.of(kort, lang));

        assertThat(service().formatForTitle("Volvo EX30", 15000).wltpKm()).isEqualTo(480);
    }

    // ── PHEV-rader får inte fastna på ett elbilskort ────────────────────────────

    @Test
    void laddhybridradenMatcharInteEttElbilskort() {
        // Skarpt fall 2026-08-11: kortet "Hyundai Kona Electric (2020)" fick motoralternativet
        // "8.9 kWh (58 km) · PHEV". Matchningen strippar "Electric" (för att "MG4 Electric" ska
        // hitta "MG4"), så titeln blir "Hyundai Kona" — och båda orden finns i "Hyundai Kona PHEV".
        EvSpec elbil = new EvSpec("Hyundai Kona Electric", 11.0, 100.0, 65.4, 514, 400_000);
        EvSpec phev  = new EvSpec("Hyundai Kona PHEV", 3.7, 0.0, 8.9, 58, 290_000, "PHEV");
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
        EvSpec phev = new EvSpec("Kia Niro PHEV", 3.7, 0.0, 8.9, 58, 290_000, "PHEV");
        when(repo.findAll()).thenReturn(List.of(phev));
        assertThat(service().formatForTitle("Kia Niro PHEV (2021)", 15000)).isNotNull();
    }

    @Test
    void gteRadenFastnarInteVidEGolf() {
        // Andra omgången av samma bugg, hittad i verifieringssöket 2026-08-11: kortet
        // "Volkswagen e-Golf (2019)" fick "13 kWh (70 km) · GTE". e-prefixregeln gör titeln till
        // "volkswagen golf", som matchar laddhybriden Golf GTE. Spärren tog bara "phev"/"hev" —
        // tabellen har flera namnkonventioner för samma sak.
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Volkswagen Golf GTE", 3.6, 0.0, 13.0, 70, 0, "PHEV")));
        assertThat(service().verifiedEngineOptions("Volkswagen e-Golf (2019)")).isNull();
        assertThat(service().isKnownEv("Volkswagen e-Golf")).isFalse();
        // ...men GTE-kortet hittar fortfarande sin egen rad
        assertThat(service().isKnownEv("Volkswagen Golf GTE (2020)")).isTrue();
    }

    @Test
    void plugInRadenFastnarInteVidBasmodellen() {
        // Toyota Prius Plug-in och Toyota RAV4 Plug-in är samma bilar som Prius/RAV4 PHEV under
        // en annan namnkonvention — båda konventionerna måste spärras
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Toyota Prius Plug-in", 3.3, 0.0, 8.8, 69, 0, "PHEV")));
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
    void elbilensEgetFullaNamnVinnerOverIceForetradet() {
        /*
         * Tätningen 2026-08-25. ice_consumption-företrädet fällde HELA namnplåten så fort
         * märket fanns som förbränningsbil, och tog då med sig elbilen som bär plåtens namn:
         * "Mercedes-Benz GLA 250+" är en ren elbil, men "Mercedes-Benz gla" finns i
         * ice_consumption, så isKnownEv svarade false. Insiktsfiltret hoppas över helt när
         * kortets drivlina är okänd — och då stod märkesbreda E20- och dieselvarningar kvar
         * på ett batterikort. 41 rena elbilar över 14 märken låg i det hålet.
         *
         * Titeln måste vara ordagrant radens namn. Är den vagare (en delmängd, "Mercedes-Benz
         * GLA") säger den inte vilken variant annonsen gäller, och är den bredare är den en
         * bensinannons — båda ska falla på företrädet som förr.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Mercedes-Benz GLA 250+", 11.0, 100.0, 70.5, 502, 0)));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant(
                        "Mercedes-Benz", "GLA 200 1.3 163 hk", "bensin", 0.62));

        assertThat(service().isKnownEv("Mercedes-Benz GLA 250+")).isTrue();
        assertThat(service().isKnownEv("Mercedes-Benz GLA 250+ (2025)")).isTrue();
        // Vagare än raden: naken namnplåt, företrädet gäller
        assertThat(service().isKnownEv("Mercedes-Benz GLA")).isFalse();
        // En BENSINANNONS får aldrig bli elbil. Notera att den här raden inte längre räcker
        // som prov på "bredare än raden": annonsregeln (steg 2, samma dag) släpper med flit
        // igenom en bredare titel som bär ett SÄRSKILJANDE ord. Skyddet mot bensinannonsen
        // ligger i att den saknar just ett sådant ord — se annonstitelUtanSarskiljandeOrd...
        when(iceConsumptionService.findAll()).thenReturn(List.of(
                new IceConsumptionService.Variant("Mercedes-Benz", "GLA 200 1.3 163 hk", "bensin", 0.62)));
        assertThat(service().isKnownEv("Mercedes-Benz GLA 200 1.3 163 hk")).isFalse();
    }

    @Test
    void annonstitelMedElbilensFullaNamnOchSarskiljandeOrdArElbil() {
        /*
         * Steg 2 i tätningen (2026-08-25). Exaktregeln lagade bara kort vars titel kommer ur
         * våra egna tabeller — en riktig annonsrubrik bär årtal och utrustningsnivå och är
         * aldrig ett exakt ev_spec-namn. Villkoret är att titeln bär hela radnamnet OCH radens
         * särskiljande ord ("250+", som ingen bensin-GLA har).
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Mercedes-Benz GLA 250+", 11.0, 100.0, 70.5, 502, 0)));
        when(iceConsumptionService.findAll()).thenReturn(List.of(
                new IceConsumptionService.Variant("Mercedes-Benz", "GLA 200 1.3 163 hk", "bensin", 0.62),
                new IceConsumptionService.Variant("Mercedes-Benz", "GLA 250 2.0 224 hk", "bensin", 0.70)));

        assertThat(service().isKnownEv("Mercedes-Benz GLA 250+ AMG Line 2023")).isTrue();
        assertThat(service().isKnownEv("Mercedes-Benz GLA 250+ Progressive Advanced Plus")).isTrue();
    }

    @Test
    void annonstitelUtanSarskiljandeOrdLamnasOrord() {
        // "Mercedes-Benz GLA 200" finns som BÅDE bensinbil och elbil under exakt samma namn.
        // Raden har därför inget särskiljande ord, och en annonsrubrik går inte att avgöra —
        // att gissa "elbil" hade tagit förbränningsinsikterna från en bensinannons.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Mercedes-Benz GLA 200", 11.0, 100.0, 58.0, 465, 0)));
        when(iceConsumptionService.findAll()).thenReturn(List.of(
                new IceConsumptionService.Variant("Mercedes-Benz", "GLA 200 1.3 163 hk", "bensin", 0.62)));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant(
                        "Mercedes-Benz", "GLA 200 1.3 163 hk", "bensin", 0.62));

        assertThat(service().isKnownEv("Mercedes-Benz GLA 200 AMG Line 2023")).isFalse();
        // Det exakta namnet svarar däremot fortfarande sant — där är titeln inte tvetydig
        assertThat(service().isKnownEv("Mercedes-Benz GLA 200")).isTrue();
    }

    @Test
    void radMedNullCarTypeArEnRenElbil() {
        /*
         * Uppmätt i drift 2026-09-03: kortet "Renault Megane E-Tech" bar laddhybridinsikten
         * id 478 trots att titeln ordagrant ÄR namnet på en EV-rad. car_type kom till med
         * laddhybridaliasen och fylls bara av konstruktorn, så rader som fanns FÖRE kolumnen
         * står kvar med NULL. toDto läste dem som elbilar (admin-dumpen visar "EV"), de tre
         * matchningsreglerna hoppade tyst över dem — och isKnownEv föll vidare till
         * ice_consumption-företrädet, hittade en bensin-Megane och svarade false. Kortets
         * drivlina blev okänd, och då stängs HELA drivlinefiltret av.
         *
         * Testerna ovan kunde aldrig fånga det: en stubbad rad får sitt carType av
         * konstruktorn och kan inte vara null. Den här raden bygger driftens data.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Renault Megane E-Tech", 22.0, 130.0, 60.0, 470, 400_000, null)));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant(
                        "Renault", "Megane 1.3 TCe 140 hk", "bensin", 0.62));

        assertThat(service().isKnownEv("Renault Megane E-Tech")).isTrue();
    }

    @Test
    void nullCarTypeSlapperInteIgenomLaddhybridraderna() {
        // Motprovet: uppluckringen gäller BARA null. En rad som säger "PHEV" ska fortsatt
        // falla på ice_consumption-företrädet, annars hade tabellens 37 laddhybridalias
        // börjat svara "ren elbil" på sina egna namn.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("BMW 530e", 3.7, 0.0, 12.0, 53, 680_000, "PHEV")));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant(
                        "BMW", "530i 2.0 252 hk", "bensin", 0.72));

        assertThat(service().isKnownEv("BMW 530e")).isFalse();
    }

    @Test
    void annonstitelSomStavarUtEnHelForbranningsvariantArIngenElbil() {
        /*
         * Villkor 3. "Mini Countryman Cooper SE ALL4 PHEV 224 hk" bär elbilsradens alla ord
         * (mini, cooper, se) och "se" är särskiljande mot Cooper-raderna — men titeln stavar
         * ut en hel laddhybridvariant, och då vinner den. Utan ledet blev laddhybriden
         * klassad som ren elbil och tappade sina förbränningsinsikter.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Mini Cooper SE", 11.0, 95.0, 54.2, 402, 0)));
        when(iceConsumptionService.findAll()).thenReturn(List.of(
                new IceConsumptionService.Variant("Mini", "Cooper 1.5 136 hk", "bensin", 0.58),
                new IceConsumptionService.Variant("Mini", "Countryman Cooper SE ALL4 PHEV 224 hk", "laddhybrid", 0.20)));
        // Företrädet måste stubbas också, annars svarar den lenient-mockade tjänsten null och
        // titeln passerar på det gamla ledet i stället — testet hade blivit grönt av fel skäl.
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant(
                        "Mini", "Countryman Cooper SE ALL4 PHEV 224 hk", "laddhybrid", 0.20));

        // Utan ordet PHEV i rubriken hänger allt på villkor 4: "countryman" är modellordet för
        // en ANNAN namnplåt än elbilens, så rubriken handlar inte om Mini Cooper SE. Just det
        // här fallet gled igenom när villkor 3 var det enda skyddet.
        assertThat(service().isKnownEv("Mini Countryman Cooper SE ALL4 224 hk")).isFalse();
        // Elbilen själv går fortfarande igenom — Countryman-raden är ingen kandidat för
        // "Mini Cooper SE", för modellordet "countryman" står inte i elbilens namn
        assertThat(service().isKnownEv("Mini Cooper SE Favoured 2024")).isTrue();
    }

    // ── Syskonmodellen som numreras med en siffra ──────────────────────────────

    @Test
    void siffranEfterNamnetGorTitelnTillEnAnnanBil() {
        /*
         * Vakthålets spegelbild, skarpt fall 2026-08-26. "BYD Seal 6" är BYD:s LADDHYBRIDKOMBI
         * och en annan bil än elbilssedanen "BYD Seal", men pass 2 kräver bara att RADENS ord
         * finns i titeln — {byd, seal} ryms i {byd, seal, 6}. Följden var att isKnownEv svarade
         * sant, ExpertInsightService.titleDrivetrain satte "ev", och varje insikt som säger
         * "laddhybrid" filtrerades bort som drivlinekrock: id 1302 ("snabbladdning begränsad
         * till 26 kW, vilket är lågt för en laddhybrid") syntes i 0 av 20 sampel på den nakna
         * titeln. Kortet bar dessutom sedanens spec-chips — 82,5 kWh och 570 km på en bil som
         * går 8,4 mil på el.
         *
         * BYD finns inte i ice_consumption, så företrädet kan inte rädda fallet: listan är tom
         * här med flit, och utan siffervillkoret svarar båda vägarna sant.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("BYD Seal", 11.0, 150.0, 82.5, 570, 390_000, "EV")));
        when(iceConsumptionService.findAll()).thenReturn(List.of());

        assertThat(service().isKnownEv("BYD Seal 6")).isFalse();
        assertThat(service().isKnownEv("BYD Seal 6 Comfort PHEV 0% RÄNTA")).isFalse();
        assertThat(service().isKnownEv("BYD Seal 6 DM-i Touring Comfort")).isFalse();
        assertThat(service().formatForTitle("BYD Seal 6 Comfort", 15000)).isNull();
        // Elbilen själv är orörd — både naken och med annonsens egna ord
        assertThat(service().isKnownEv("BYD Seal")).isTrue();
        assertThat(service().formatForTitle("BYD Seal Design AWD 2024", 15000)).isNotNull();
    }

    @Test
    void hybridrubrikNarAldrigEnRenElbilsrad() {
        /*
         * Syskonfällan en gång till, men med en BOKSTAV: "BYD Seal U" är laddhybrid-SUV:en
         * bredvid elbilssedanen "BYD Seal", och siffervakten ovan biter inte på ett U.
         * En bokstavsregel mättes och förkastades — 48 äkta elbilsannonser hade tappat sina
         * spec-chips på "M Sport", "S line" och "N Line" mot 25 rättade rubriker.
         *
         * Rubriken säger däremot drivlinan själv: 21 av 23 Seal U-annonser bär PHEV eller BYD:s
         * badge DM-i. En hybridrubrik får därför aldrig matcha en rad med carType "EV", hur väl
         * namnen än stämmer. Mätt över 2 266 riktiga elbilsrubriker: NOLL bär ett laddhybrid-
         * eller hybridord, så kostnaden är uppmätt noll — och på köpet föll 9 Megane E-Tech
         * Plug-in, 3 Opel Mokka Hybrid, 2 Mini Countryman SE PHEV, 2 Kona Hybrid, 1 Niro Hybrid
         * och 1 IONIQ Plug-in bort från sina elbilsrader. Två av dem (Megane E-Tech, Mini
         * Countryman SE ALL4) stod som "går inte att laga" i annonsregelns mätning 2026-08-25.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("BYD Seal", 11.0, 150.0, 82.5, 570, 390_000, "EV"),
                new EvSpec("Kia Niro PHEV", 3.7, 0.0, 8.9, 58, 290_000, "PHEV")));
        when(iceConsumptionService.findAll()).thenReturn(List.of());

        assertThat(service().formatForTitle("BYD Seal U DM-i Comfort Paket", 15000)).isNull();
        assertThat(service().formatForTitle("BYD Seal U BOOST PHEV", 15000)).isNull();
        assertThat(service().isKnownEv("BYD Seal U DM-i Comfort Paket")).isFalse();
        // Laddhybridraderna ska tvärtom fortsätta hitta sina egna rubriker — det är hela
        // deras uppgift, och vakten prövas bara mot carType "EV"
        assertThat(service().formatForTitle("Kia Niro PHEV (2021)", 15000)).isNotNull();
        // Elbilen själv orörd
        assertThat(service().formatForTitle("BYD Seal Design AWD 2024", 15000)).isNotNull();
        // GRÄNSEN, medvetet låst: en rubrik som varken namnger sin drivlina eller bär en siffra
        // går inte att avgöra ur titeln. Skärps regeln någon gång ska raden ändras med flit.
        assertThat(service().formatForTitle("BYD Seal U Boost", 15000)).isNotNull();
    }

    @Test
    void tvasiffrigTrimnivaEfterNamnetArSammaBil() {
        /*
         * Gränsen åt andra hållet, och den är den dyra riktningen. Att fälla varje otolkat TAL
         * efter modellnamnet vore samma trubbighet som den förkastade delmängdsregeln: mätt mot
         * 1 349 riktiga Blocket-rubriker är tvåsiffriga tal efter namnet nästan alltid en
         * utrustningsnivå av SAMMA bil, och den varianten hade tagit spec-chipsen från över
         * hundra äkta elbilsannonser. Bara en ensam siffra 1–9 räknas som syskonmodell.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Audi Q4 e-tron", 11.0, 125.0, 82.0, 520, 500_000, "EV"),
                new EvSpec("Nissan Leaf", 6.6, 50.0, 39.0, 270, 250_000, "EV")));

        assertThat(service().formatForTitle("Audi Q4 e-tron 40 Proline Advanced 204hk", 15000)).isNotNull();
        assertThat(service().formatForTitle("Audi Q4 e-tron 45 Quattro 265hk", 15000)).isNotNull();
        assertThat(service().formatForTitle("Nissan Leaf 40 kWh Tekna", 15000)).isNotNull();
        // Säljarens egna procentsatser är inga syskonmodeller — "0" räknas inte, och ett
        // procenttecken diskvalificerar ordet
        assertThat(service().formatForTitle("Nissan Leaf 0% ränta Tekna", 15000)).isNotNull();
    }

    @Test
    void titelnsEgetElectricUpphaverSyskonsiffran() {
        /*
         * Porsches eldrivna Macan heter "Macan 4" och "Macan 4S" i utrustningsnivå mot raden
         * "Porsche Macan Electric" — där ÄR siffran en trimnivå, och regeln hade fällt en äkta
         * elbil. Rubrikerna bär ordet Electric, som är ett positivt drivlinebevis titeln ger
         * själv: av 22 riktiga Seal 6-rubriker säger noll Electric, av korpusens elbilsannonser
         * 98. Ordet prövas mot den ostrippade titeln — rensadTitel städar bort det.
         *
         * Priset står kvar och är medvetet: en Macan-rubrik UTAN ordet tappar sina spec-chips.
         * De annonserna klassas ändå inte som elbil idag (bensin-Macan finns i ice_consumption
         * och företrädet fäller dem), och utebliven data är billigare än fel data.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Porsche Macan Electric", 11.0, 270.0, 95.0, 613, 900_000, "EV")));

        assertThat(service().formatForTitle("Porsche Macan 4 Electric Panorama Chrono", 15000)).isNotNull();
        assertThat(service().formatForTitle("Porsche Macan 4 Sport Edition", 15000)).isNull();
    }

    @Test
    void laddhybridsaliasetSvararInteSantPaExaktaNamnet() {
        // Kravet på carType "EV" i den nya regeln är strikt: PHEV-aliasraderna finns i samma
        // tabell och ska INTE bli "ren elbil" av att titeln råkar vara radens namn. De går
        // som förr via drivlineordet i titeln — här saknas det, så företrädet ska fälla.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo XC60 T8", 3.7, 0.0, 18.8, 68, 500_000, "PHEV")));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant(
                        "Volvo", "XC60 B4 2.0 197 hk", "diesel", 0.55));

        assertThat(service().isKnownEv("Volvo XC60 T8")).isFalse();
    }

    @Test
    void exaktaNamnetSvararSantUtanAttGaViaNamntraffen() {
        /*
         * Ledet ligger före namnträffskravet i isKnownEv, så svaret hänger inte på att
         * matchByTitle hittar raden från titeln. Namn med accent och plustecken är den sort
         * där fuzzy-matchning historiskt gått sönder (se plustecknet i matchningsNamn), så
         * provet tas på ett sådant namn.
         *
         * OBS: det här är INTE lagningen av ett observerat driftfel. Jag trodde först att
         * kortet för den här bilan visade förbränningsinsikter i drift, men det var mitt eget
         * prov som var trasigt — skalet mangla é:et i titeln, så servern fick en annan sträng.
         * Bilen var aldrig i hålet.
         */
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Mercedes-Benz AMG GT 4-Door Coupé 53 4MATIC+", 11.0, 320.0, 89.0, 600, 0)));

        assertThat(service().isKnownEv("Mercedes-Benz AMG GT 4-Door Coupé 53 4MATIC+")).isTrue();
    }

    @Test
    void bensinbilMedLaddhybridvariantRaknasInteSomElbil() {
        // isKnownEv svarar insiktsfiltret på "är kortet en ren elbil?". "Volvo XC60" matchade
        // "Volvo XC60 PHEV", så en bensin-XC60 klassades som elbil och tappade sina
        // förbränningsinsikter — samma matchning, större skada än fel siffra i ett chip.
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Volvo XC60 PHEV", 3.7, 0.0, 18.8, 68, 500_000, "PHEV")));
        assertThat(service().isKnownEv("Volvo XC60")).isFalse();
        assertThat(service().isKnownEv("Volvo XC60 PHEV")).isTrue();
    }

    @Test
    void laddhybridensTvaNamnTackerVarsinTitelform() {
        /*
         * Volvos laddhybrider ligger under två namn med IDENTISKA siffror, och det ser ut som
         * dubblettrader ända tills man läser drivlinekrock: "phev" är ett DRIVLINEORD, så
         * PHEV-raden filtreras bort för varje titel som saknar ordet. Raderna täcker alltså
         * varsin titelform, och den som tar bort den ena tystnar korten för den andra.
         *
         * Testet finns för att designen är osynlig i datan — uppmätt 2026-08-21 saknade V90 sin
         * T8-halva medan fyra syskon hade båda, och utan det här provet ser en spegling ut som
         * slöseri nästa gång någon städar.
         */
        // Räckvidderna skiljer sig BARA här, som spårämne: i drift är raderna identiska, och
        // då går det inte att se vilken av dem träffen kom från.
        EvSpec phev = new EvSpec("Volvo V90 PHEV", 7.4, 0.0, 18.8, 68, 690_000, "PHEV");
        EvSpec t8   = new EvSpec("Volvo V90 T8",   7.4, 0.0, 18.8, 60, 690_000, "PHEV");
        when(repo.findAll()).thenReturn(List.of(phev, t8));

        // Annonsens form: T8. Bara T8-raden är möjlig — PHEV-raden kräver ordet i titeln.
        assertThat(service().formatForTitle("Volvo V90 T8 Recharge AWD (2021)", 15000).wltpKm())
                .isEqualTo(60);
        // AI:ns form: PHEV. Då är det tvärtom.
        assertThat(service().formatForTitle("Volvo V90 PHEV (2021)", 15000).wltpKm())
                .isEqualTo(68);
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
    void radMedElectricINamnetNasAvEnAnnonstitel() {
        // Skarpt fall 2026-08-24: raden hittade sitt EGET namn, men så fort annonsen bar ett
        // extra ord föll pass 1 och pass 2 letade efter "electric" — som titelsidan just
        // strippat bort. 26 rader var drabbade, alla med ordet i namnet.
        when(repo.findAll()).thenReturn(List.of(spec("Hyundai Kona Electric 64 kWh")));
        assertThat(service().formatForTitle("Hyundai Kona Electric 64 kWh 2024 Business Edition", 15000))
                .isNotNull();
    }

    @Test
    void opelradMedElectricNasAvAnnonstitel() {
        // Opel hade 11 av de 26 raderna
        when(repo.findAll()).thenReturn(List.of(spec("Opel Corsa Electric 50 kWh")));
        assertThat(service().formatForTitle("Opel Corsa Electric 50 kWh 2023 GS Line 4500 mil", 15000))
                .isNotNull();
    }

    @Test
    void nakenKonatitelBlirFortfarandeInteElbil() {
        // Vakten från 2026-08-14 måste överleva strippningen: "Hyundai Kona Electric" blir
        // "hyundai kona" i ordjämförelsen, och en naken Kona-titel träffar då raden — men
        // ice_consumption-företrädet ska ändå fälla den, för bilen finns som bensinbil
        when(repo.findAll()).thenReturn(List.of(spec("Hyundai Kona Electric 64 kWh")));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Hyundai", "Kona 1.6 T-GDI 198 hk", "bensin", 0.72));
        assertThat(service().isKnownEv("Hyundai Kona")).isFalse();
    }

    @Test
    void etronRaknasSomElbilTrotsAttA6FinnsSomBensinbil() {
        // Skarpt fall 2026-08-24: ice_consumption-företrädet fällde Audi A6 Avant e-tron på
        // "Audi A6 40 TFSI", varpå drivlinefiltret stängdes av helt på kortet och en
        // laddhybridinsikt låg kvar på en ren elbil
        when(repo.findAll()).thenReturn(List.of(spec("Audi A6 Avant e-tron")));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Audi", "A6 40 TFSI 204 hk", "bensin", 0.62));
        assertThat(service().isKnownEv("Audi A6 Avant e-tron quattro")).isTrue();
    }

    @Test
    void idPoloRaknasSomElbilTrotsAttPoloFinnsSomBensinbil() {
        when(repo.findAll()).thenReturn(List.of(spec("Volkswagen ID. Polo 155 kW")));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Volkswagen", "Polo 1.0 TSI 95 hk", "bensin", 0.55));
        assertThat(service().isKnownEv("Volkswagen ID. Polo 155 kW")).isTrue();
    }

    @Test
    void bensintitelNasInteAvElbilsundantaget() {
        // Undantaget får inte göra en förbränningsbil till elbil: titeln saknar elbilsnamnet
        // OCH når ingen ev_spec-rad
        when(repo.findAll()).thenReturn(List.of(spec("Audi A6 Avant e-tron")));
        assertThat(service().isKnownEv("Audi A6 40 TFSI 204 hk")).isFalse();
    }

    @Test
    void dieseltitelArAldrigElbilAvenOmNamnetMatchar() {
        // Regression som Electric-strippningen öppnade: "Porsche Cayenne Electric" blev matchbar
        // för "Porsche Cayenne 3.0 Diesel", och dieselordet räckte för att passera drivlineledet
        // i isKnownEv — en diesel klassades som ren elbil och tappade sina förbränningsinsikter
        when(repo.findAll()).thenReturn(List.of(spec("Porsche Cayenne Electric")));
        when(iceConsumptionService.consumptionForTitle(anyString(), any(), any()))
                .thenReturn(new IceConsumptionService.Variant("Porsche", "Cayenne 3.0 Diesel 262 hk", "diesel", 0.85));
        assertThat(service().isKnownEv("Porsche Cayenne 3.0 Diesel 262 hk")).isFalse();
    }

    @Test
    void laddhybridtitelSvararFortfarandeSant() {
        // Kontraktet från 2026-08-14 får inte ändras av diesel-undantaget ovan.
        // carType måste vara PHEV som i drift: tabellens 37 laddhybridrader är alla typade,
        // och hybridtitelvakten (2026-08-26) fäller en hybridrubrik mot en EV-typad rad.
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Volvo XC60 T8 PHEV", 11.0, 150.0, 60.0, 400, 400_000, "PHEV")));
        assertThat(service().isKnownEv("Volvo XC60 T8 PHEV 455 hk")).isTrue();
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
                // Prisslaget MÅSTE stå i rubriken: raden ligger bredvid de uppmätta
                // begagnatgolven i chattprompten, och en omärkt prisrad blandades med dem.
                .contains("EV-NYPRIS")
                .contains("INTE begagnatpriser")
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

        assertThat(service().verifiedEngineOptions("Kia EV6")).isEqualTo("63 kWh (428 km) · från 2025");
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

        // Årsspannen kom till 2026-08-29: utan årsmodell i rubriken står generationerna bredvid
        // varandra, och då måste raden säga VILKEN bil varje siffra gäller.
        assertThat(service().verifiedEngineOptions("Kia EV6"))
                .isEqualTo("77.4 kWh (424 km) · GT 77.4 kWh · 2021–2024, "
                         + "77.4 kWh (528 km) · Long Range 2WD 77.4 kWh · 2021–2024, "
                         + "84 kWh (546 km) · Long Range AWD 84 kWh · från 2025, "
                         + "84 kWh (582 km) · Long Range 2WD 84 kWh · från 2025");
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

        // "MG MG4 XPOWER" är den enda av de fyra som står i GENERATION (gen 2), och otaggade
        // rader räknas som den äldsta generationen — därav "till 2024" på de tre andra.
        assertThat(service().verifiedEngineOptions("MG4"))
                .isEqualTo("41.9 kWh (325 km) · Standard Range · till 2024, "
                         + "52.8 kWh (405 km) · Long Range · till 2024, "
                         + "61.7 kWh (405 km) · XPOWER · från 2025, "
                         + "74.4 kWh (545 km) · Extended Range · till 2024");
    }

    @Test
    void markesprefixetIngarInteITrimnamnet() {
        // ev-database lagrar "MG MG4 XPOWER" medan titeln bara sager "MG4". Utan att skala bort
        // market fore modellordet blir trimmet "MG XPOWER".
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG MG4 XPOWER", 11.0, 140.0, 61.7, 405, 471_000)));

        assertThat(service().verifiedEngineOptions("MG4")).isEqualTo("61.7 kWh (405 km) · XPOWER · från 2025");
    }

    @Test
    void trimnamnetVisasBaraNarGruppenArEnEndaVariant() {
        // En hopslagen grupp beskriver samma bil under tva kallors namn — vilket av dem som
        // skrivs ut vore godtyckligt, sa gruppen lamnas omarkt
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("Kia EV6 Standard Range 2WD", 11.0, 180.0, 60.0, 428, 400_000),
                new EvSpec("Kia EV6 Standard Range 63 kWh", 11.0, 180.0, 63.0, 428, 400_000)));

        assertThat(service().verifiedEngineOptions("Kia EV6")).isEqualTo("63 kWh (428 km) · från 2025");
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

        // Spärren håller isär raderna i datan; årsspannet säger samma sak till användaren.
        assertThat(service().verifiedEngineOptions("MG4"))
                .isEqualTo("51 kWh (350 km) · Standard Range · till 2024, "
                         + "52.8 kWh (416 km) · Urban Comfort Long Range · från 2025");
    }

    @Test
    void dubbletterInomSammaGenerationSlasFortfarandeIhop() {
        // Generationsspärren får inte slå sönder den vanliga ihopslagningen: båda raderna är
        // gen 2, olika kapacitet och samma räckvidd, alltså samma bil netto och brutto
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG4 Urban Comfort Long Range", 11.0, 87.0, 52.8, 416, 0),
                new EvSpec("MG4 Urban Premium Long Range", 11.0, 87.0, 54.0, 416, 0)));

        assertThat(service().verifiedEngineOptions("MG4")).isEqualTo("54 kWh (416 km) · från 2025");
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
                .isEqualTo("84 kWh (570 km) · från 2024");
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
    void arsmodellenValjerGenerationensEffekt() {
        /*
         * Live 2026-08-14: ett elbilssök gav "Volkswagen e-Golf (2018)" och "MG ZS EV (2019)"
         * med 150 hk var — AI:ns gissning, eftersom listan bara innehöll MG Marvel R. Samma
         * sökning dagen innan gav 95 hk på tre kort, alltså ett tal som varierar mellan
         * körningar.
         *
         * Båda modellerna har TVÅ generationer med olika effekt, och en namnnyckel utan årtal
         * hade gett fel siffra åt halva årsspannet — med vår verifieringsetikett på.
         * Källkontrollerat 2026-08-14: e-Golf 24,2 kWh = 85 kW, 35,8 kWh = 100 kW;
         * ZS EV 44,5 kWh = 105 kW, Long Range 72,6 kWh = 115 kW.
         */
        assertThat(service().getSystemPowerHk("Volkswagen e-Golf (2018)")).isEqualTo(136);
        assertThat(service().getSystemPowerHk("Volkswagen e-Golf (2015)")).isEqualTo(115);
        assertThat(service().getSystemPowerHk("MG ZS EV (2019)")).isEqualTo(143);
        assertThat(service().getSystemPowerHk("MG ZS EV (2023)")).isEqualTo(156);
    }

    @Test
    void utanArtalAvstarNarGenerationernaSkiljerSig() {
        // Två generationer, olika effekt, inget år att välja på: en "verifierad" siffra som
        // gäller halva årsspannet är sämre än AI:ns gissning, för den bär vår etikett.
        assertThat(service().getSystemPowerHk("Volkswagen e-Golf")).isNull();
        // ...men en modell med en enda känd effekt behöver inget årtal
        assertThat(service().getSystemPowerHk("MG Marvel R")).isEqualTo(180);
    }

    @Test
    void hamtadEffektSlarDenHandskrivnaListan() {
        /*
         * Den handskrivna listan hade två poster mot ev_spec:s 553 rader, alltså gissade AI:n
         * hk för i praktiken varje elbil. ev-database skriver "Total Power 105 kW (143 PS)" på
         * varje bilsida — samma sida nattsynken redan hämtar — och siffran landar i ev_power.
         *
         * Uppslaget går via matchByTitle och ÄRVER därmed dess årsmodellfilter, så svaret blir
         * generationsrätt utan egen årslogik. Det är hela skälet att nyckeln är radens car_name
         * och inte modellnamnet.
         */
        EvPowerService power = org.mockito.Mockito.mock(EvPowerService.class);
        when(repo.findAll()).thenReturn(List.of(
                new EvSpec("MG ZS EV 44.5 kWh", 6.0, 76.0, 44.5, 263, 0)));
        when(power.hkFor("MG ZS EV 44.5 kWh")).thenReturn(143);

        EvSpecService s = service();
        s.setEvPowerService(power);
        assertThat(s.getSystemPowerHk("MG ZS EV (2019)")).isEqualTo(143);
    }

    @Test
    void handskrivnaListanGallerNarTabellenArTom() {
        // Tabellen fylls av nattsynken och är tom vid första uppstart. Då ska listan gälla som
        // förut — och för modeller ev-database inte längre listar (e-Golf är utgången) är den
        // handskrivna posten det enda som finns.
        EvPowerService power = org.mockito.Mockito.mock(EvPowerService.class);
        when(repo.findAll()).thenReturn(List.of(new EvSpec("Volkswagen e-Golf 35.8 kWh", 7.0, 40.0, 35.8, 231, 0)));
        when(power.hkFor(anyString())).thenReturn(null);

        EvSpecService s = service();
        s.setEvPowerService(power);
        assertThat(s.getSystemPowerHk("Volkswagen e-Golf (2018)")).isEqualTo(136);
    }

    @Test
    void bensinbilFarInteElbilensEffekt() {
        // Nyckeln är "Volkswagen e-Golf". Elprefixet strippas ur titeln för att "Kia e-Niro" ska
        // kunna matcha nyckeln "Kia Niro" — men då måste den OSTRIPPADE formen finnas kvar också,
        // annars ser nyckeln ingen skillnad på e-Golf och en vanlig bensin-Golf. Samma
        // åtkomstväg som gav en bensin-XC60 elbilssiffror 2026-08-11.
        assertThat(service().getSystemPowerHk("Volkswagen Golf (2018)")).isNull();
        assertThat(service().getSystemPowerHk("Volkswagen Golf GTI (2018)")).isNull();
    }

    @Test
    void nullTitelGerNullForVerifieradHk() {
        assertThat(service().getSystemPowerHk(null)).isNull();
    }
}
