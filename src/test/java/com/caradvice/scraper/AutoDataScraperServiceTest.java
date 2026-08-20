package com.caradvice.scraper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsern körs mot sparade sidor från auto-data.net, hämtade 2026-08-12. Siffrorna nedan är
 * sidornas egna — ändras strukturen ska testet säga det, inte nattkörningen.
 */
class AutoDataScraperServiceTest {

    private String fixtur(String namn) {
        try (InputStream in = getClass().getResourceAsStream("/autodata/" + namn)) {
            if (in == null) throw new IllegalStateException("saknar fixtur " + namn);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // --- generationssidan är motoralternativlistan ---

    @Test
    void generationssidanGerHelaMotorutbudet() {
        var motorer = AutoDataScraperService.parseMotorAlternativ(fixtur("golf-viii-generation.html"));

        // Golf VIII såldes med 21 varianter. Poängen med källan är just att den listar ALLA,
        // medan ice-consumption.csv bara bär 13 Golf-rader och ev_spec ingen alls.
        assertThat(motorer).hasSize(21);
        assertThat(motorer).extracting(AutoDataScraperService.MotorAlternativ::namn)
                .contains("1.0 TSI (90 Hp)", "1.5 TSI (150 Hp)", "2.0 TDI (115 Hp)",
                        "GTI 2.0 TSI (245 Hp) DSG", "GTE 1.4 TSI (245 Hp) eHybrid DSG");
    }

    @Test
    void effektenBrytsUtUrNamnet() {
        var motorer = AutoDataScraperService.parseMotorAlternativ(fixtur("golf-viii-generation.html"));

        var tsi150 = motorer.stream().filter(m -> m.namn().equals("1.5 TSI (150 Hp)")).findFirst().orElseThrow();

        assertThat(tsi150.hk()).isEqualTo(150);
        assertThat(tsi150.sokvag()).isEqualTo("/en/volkswagen-golf-viii-1.5-tsi-150hp-38152");
    }

    @Test
    void arsspannetFoljerMedForGenerationsvalet() {
        // Utan årsspann går det inte att avgöra vilken generation en årsmodell hör till, och då
        // är vi tillbaka i MG4/Leaf-felet: en rad som matchar ett kort den inte hör till.
        var motorer = AutoDataScraperService.parseMotorAlternativ(fixtur("golf-viii-generation.html"));

        assertThat(motorer).allSatisfy(m -> assertThat(m.arsspann()).isNotNull());
        assertThat(motorer).extracting(AutoDataScraperService.MotorAlternativ::arsspann)
                .contains("2020 - 2024");
    }

    @Test
    void varjeVariantRaknasEnGang() {
        // Varje rad har TVÅ länkar till samma variantsida — en i rubrikcellen och en i datacellen.
        // Utan th-filtret blir listan dubbelt så lång och varje motor står två gånger på kortet.
        var motorer = AutoDataScraperService.parseMotorAlternativ(fixtur("golf-viii-generation.html"));

        assertThat(motorer).extracting(AutoDataScraperService.MotorAlternativ::sokvag).doesNotHaveDuplicates();
    }

    @Test
    void nyaMarkupenGerSammaMotorlista() {
        /*
         * Sajten bytte markup mellan 2026-08-12 och 2026-08-14: tabellen är nu divar
         * (div.thi) där den förut var th.i, och årsspannet ligger i span.cur för en PÅGÅENDE
         * generation där en avslutad har span.end. Parsern gav noll rader på varje sida, alltså
         * returnerade bagageForBil null för varenda bil.
         *
         * Det syntes inte i cargo-coverage eftersom arbetslistan råkade vara tom (602/602/0).
         * Ett jobb som inte har något att göra kan inte skilja "inget kvar att fylla" från
         * "trasig" — det var mätningen av motorjoinen som råkade avslöja det.
         *
         * Fixturen är Skoda Octavia IV (facelift 2024), hämtad 2026-08-14.
         */
        var motorer = AutoDataScraperService.parseMotorAlternativ(fixtur("octavia-iv-generation-nymarkup.html"));

        assertThat(motorer).hasSize(8);
        assertThat(motorer).extracting(AutoDataScraperService.MotorAlternativ::namn)
                .contains("2.0 TDI (150 Hp) DSG", "1.5 TSI (150 Hp) Mild Hybrid DSG",
                        "RS 2.0 TSI (265 Hp) DSG");
        // Varje rad har två länkar till samma variantsida — rubrikcellen och datacellen
        assertThat(motorer).extracting(AutoDataScraperService.MotorAlternativ::sokvag).doesNotHaveDuplicates();
        // span.cur i stället för span.end: utan den går årsspannet förlorat för alla nya bilar
        assertThat(motorer).allSatisfy(m -> assertThat(m.arsspann()).isNotNull());
        assertThat(motorer.get(0).hk()).isEqualTo(265);
    }

    @Test
    void sidaUtanVariantlistaGerTomLista() {
        // Fel URL eller ändrad struktur ska ge tom lista, inte krascha nattkörningen.
        assertThat(AutoDataScraperService.parseMotorAlternativ("<html><body>ingen bil här</body></html>")).isEmpty();
        assertThat(AutoDataScraperService.parseMotorAlternativ("")).isEmpty();
        assertThat(AutoDataScraperService.parseMotorAlternativ(null)).isEmpty();
    }

    // --- variantsidan bär bagagevolymen ---

    @Test
    void variantsidanGerNormalOchMaxvolym() {
        var vol = AutoDataScraperService.parseBagagevolym(fixtur("golf-viii-1.5-tsi-150hp.html"));

        // 380 l är normalvolymen (baksätet uppfällt) och den enda som bagagevakten får döma mot.
        assertThat(vol.minLiter()).isEqualTo(380);
        assertThat(vol.maxLiter()).isEqualTo(1237);
    }

    @Test
    void imperialkolumnenTasInteForLitertalet() {
        // Cellen är "380 l <span class="val2">13.42 cu. ft.</span>". Utan att val2 skalas bort
        // kan en girig siffermatchning ge 13 liter — ett tal som ser rimligt ut och är fel.
        var vol = AutoDataScraperService.parseBagagevolym(fixtur("golf-viii-1.5-tsi-150hp.html"));

        assertThat(vol.minLiter()).isNotIn(13, 43);
    }

    @Test
    void branslebehallarenForvaxlasInteMedBagaget() {
        // Samma tabell har "Fuel tank capacity 50 l" tre rader ned, alltså samma enhet och format.
        var vol = AutoDataScraperService.parseBagagevolym(fixtur("golf-viii-1.5-tsi-150hp.html"));

        assertThat(vol.minLiter()).isNotEqualTo(50);
    }

    // --- navigering: märke → modell → generation ---

    @Test
    void markeslistanFinnsPaVarjeSida() {
        // Sidfoten bär de populäraste märkena, så vi slipper sitemapen (27 MB, fyra skärvor, sju
        // språk). Hela listan ligger på /en/allbrands — se markesindexetBarFlerMarkenAnSidfoten.
        var marken = AutoDataScraperService.parseMarken(fixtur("golf-model.html"));

        assertThat(marken).containsKeys("volkswagen", "skoda", "volvo", "land rover");
        assertThat(marken.get("volkswagen")).isEqualTo("https://www.auto-data.net/en/volkswagen-brand-80");
    }

    @Test
    void markessidanListarModellerna() {
        var modeller = AutoDataScraperService.parseModeller(fixtur("volkswagen-brand.html"));

        assertThat(modeller).hasSize(84);
        assertThat(modeller).extracting(AutoDataScraperService.Modell::namn).contains("Golf", "Passat", "ID.4");
    }

    @Test
    void modellsidanGerArsspannOchKaross() {
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        assertThat(gen).hasSize(39);

        var viii = gen.stream().filter(g -> g.titel().equals("Volkswagen Golf VIII")).findFirst().orElseThrow();
        assertThat(viii.franAr()).isEqualTo(2020);
        assertThat(viii.tillAr()).isEqualTo(2024);
        assertThat(viii.kaross()).isEqualTo("Hatchback");
        assertThat(viii.sokvag()).isEqualTo("/en/volkswagen-golf-viii-generation-7367");
    }

    @Test
    void generationSomFortfarandeSaljsHarOppetSlut() {
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        var facelift = gen.stream().filter(g -> g.titel().contains("VIII (facelift 2024)")).findFirst().orElseThrow();
        assertThat(facelift.franAr()).isEqualTo(2024);
        assertThat(facelift.tillAr()).isNull();
        assertThat(facelift.galler(2026)).isTrue();
    }

    @Test
    void arsmodellenValjerGeneration() {
        // Kärnan i hela MG4/Leaf-buggfamiljen: en 2021:a ska få 2020-2024-generationen,
        // inte den senaste raden bara för att den är den senaste.
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        var vald = AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", 2021);

        assertThat(vald.titel()).isEqualTo("Volkswagen Golf VIII");
        assertThat(vald.kaross()).isEqualTo("Hatchback");
    }

    @Test
    void generationsaretArGenerationensEgetOchInteFaceliftens() {
        /*
         * Uppmätt live 2026-08-14: auto-datas SENASTE generation är nästan alltid en facelift,
         * och dess franAr är faceliftens år. Golf VIII står som "Volkswagen Golf VIII (facelift
         * 2024)" med start 2024 fast generationen kom 2020 — och det är 2020 års motorlista vår
         * CSV bär. Samma sak på Kia Sportage V (2024 mot 2021) och Volvo XC60 II (2025 mot 2017).
         *
         * Sparas faceliftåret i ice_generation fäller vakten varje kort från 2020-2023, alltså
         * modeller där listan hade varit helt riktig. Överblockering syns aldrig som ett fel
         * värde utan som ett TOMT fält, och sedan 2026-08-14 tappar kortet dessutom förbrukning,
         * drivmedel och hk i samma svep — vakten är delad.
         */
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        // Senaste generationen ÄR faceliften...
        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", null).titel())
                .isEqualTo("Volkswagen Golf VIII (facelift 2024)");
        // ...men årtalet vi sparar ska vara generationens eget
        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Volkswagen Golf")).isEqualTo(2020);

        // Karossen följer med som förut: kombin har sin egen generation och sitt eget startår
        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Volkswagen Golf Variant"))
                .isEqualTo(2020);
        assertThat(AutoDataScraperService.basgenerationsStartAr(List.of(), "Volkswagen Golf")).isNull();
    }

    @Test
    void faceliftmarkeringenStrippasUrTiteln() {
        assertThat(AutoDataScraperService.basTitel("Skoda Octavia IV (facelift 2024)"))
                .isEqualTo("skoda octavia iv");
        assertThat(AutoDataScraperService.basTitel("Volvo XC60 II (restyling 2025)"))
                .isEqualTo("volvo xc60 ii");
        // ...men generationsnumret och karossordet får INTE strippas — de skiljer generationer åt
        assertThat(AutoDataScraperService.basTitel("Volkswagen Golf VIII Variant"))
                .isEqualTo("volkswagen golf viii variant");
    }

    @Test
    void dateringenIParentesenStrippasOcksa() {
        /*
         * 2026-08-18: där generationsnumret saknas daterar auto-data raden i stället, och då
         * hamnade basgenerationen i en annan grupp än sin egen facelift.
         *
         *   "Volvo S90 (facelift 2020)"  →  volvo s90
         *   "Volvo S90 (2016)"           →  volvo s90 2016   ← egen grupp, fel
         *   "Volvo S90"       (1997)     →  volvo s90        ← samma grupp som faceliften
         *
         * Faceliftraden grupperades alltså med den ombadgade 960:an från 1997 i stället för med
         * bilen den är en facelift av, och gruppens minsta år blev 1997.
         */
        assertThat(AutoDataScraperService.basTitel("Volvo S90 (2016)")).isEqualTo("volvo s90");
        assertThat(AutoDataScraperService.basTitel("Volvo S90 (facelift 2020)")).isEqualTo("volvo s90");
        assertThat(AutoDataScraperService.basTitel("Volvo S90")).isEqualTo("volvo s90");

        // Även när ordet skalats bort ur en parentes som bar båda delarna
        assertThat(AutoDataScraperService.basTitel("Volvo XC60 I (2013 facelift)")).isEqualTo("volvo xc60 i");

        // Undermodellen behåller sitt eget namn och ska INTE dras in i basbilens grupp
        assertThat(AutoDataScraperService.basTitel("Volvo S90 L (2016)")).isEqualTo("volvo s90 l");

        // Chassikoden är ingen datering och står kvar — den är generationens identitet
        assertThat(AutoDataScraperService.basTitel("BMW 3 Series Sedan (G20)"))
                .isEqualTo("bmw 3 series sedan g20");
    }

    @Test
    void enNamneIEnAnnanEpokDrarInteNerStartaret() {
        /*
         * Uppmätt i drift 2026-08-18: ice_generation stod på "Volvo s90" → 1997 och
         * "Volvo v90" → 1996, alltså den ombadgade 960:an. Vår CSV bär B4/B5/B6/T8 PHEV =
         * andra generationen (2016-), så årtalet var två decennier fel.
         *
         * Felet är farligare än ett tomt fält: matchByTitle ÄRVER filtret, så en S90 från 1997
         * hade fått 2016 års motorlista i stället för ingen alls. Gruppens minsta år går alltså
         * inte att använda när nyckeln bara är en bastitel — bastitlar återanvänds mellan
         * generationer som ligger tjugo år isär.
         */
        var gen = AutoDataScraperService.parseGenerationer(fixtur("volvo-s90-model.html"));
        assertThat(gen).isNotEmpty();

        // Senaste raden ÄR faceliften från 2020...
        assertThat(AutoDataScraperService.valjGeneration(gen, "Volvo s90", null, false).titel())
                .isEqualTo("Volvo S90 (facelift 2020)");
        // ...och kedjan bakåt stannar på basgenerationen 2016, inte på 1997 års namne
        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Volvo s90")).isEqualTo(2016);
    }

    @Test
    void faceliftkedjanNarAldrigForegaendeGeneration() {
        /*
         * Gränsen åt andra hållet. XC90-sidan bär två generationer med VAR SIN facelift:
         *
         *   Volvo XC90 II (facelift 2024)   2024 -
         *   Volvo XC90 II (facelift 2019)   2019 - 2024
         *   Volvo XC90 II                   2015 - 2019
         *   Volvo XC90 (facelift 2007)      2007 - 2014
         *   Volvo XC90                      2002 - 2006
         *
         * Kedjan tar två steg (2024 → 2019 → 2015) och stannar på XC90 II, som inte själv är en
         * facelift. Att den stannar där är inte en slump utan skarvkravet: 2014 möter inte 2015
         * i den riktningen kedjan går, och romerska siffran håller dessutom grupperna isär.
         * Ett svar på 2002 hade gett varje XC90-kort mellan 2002 och 2014 andra generationens
         * motorlista.
         */
        var gen = AutoDataScraperService.parseGenerationer(fixtur("volvo-xc90-model.html"));
        assertThat(gen).isNotEmpty();

        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Volvo xc90")).isEqualTo(2015);
    }

    @Test
    void faceliftmarkeringenKannsIgenVarSomHelstITiteln() {
        assertThat(AutoDataScraperService.arFacelift("Volvo S90 (facelift 2020)")).isTrue();
        assertThat(AutoDataScraperService.arFacelift("Volvo XC60 I (2013 facelift)")).isTrue();
        assertThat(AutoDataScraperService.arFacelift("BMW 3 Series Sedan (G20 LCI, facelift 2022)")).isTrue();
        assertThat(AutoDataScraperService.arFacelift("Volvo XC60 II (restyling 2025)")).isTrue();
        // Basraden är per definition ingen facelift — det är den som avslutar kedjan
        assertThat(AutoDataScraperService.arFacelift("Volvo S90 (2016)")).isFalse();
        assertThat(AutoDataScraperService.arFacelift("Volkswagen Golf VIII")).isFalse();
        assertThat(AutoDataScraperService.arFacelift(null)).isFalse();
    }

    @Test
    void faceliftmarkeringenStrippasAvenNarChassikodenStarForst() {
        /*
         * 2026-08-16: regeln letade efter en parentes som BÖRJAR med "facelift", vilket bara
         * stämmer när chassikoden saknas. Auto-data skriver oftare "(XE30, facelift 2020)" —
         * då matchade inget, faceliftversionen fick en egen bastitel, gruppen blev ensam och
         * basgenerationsStartAr returnerade faceliftens år. Alltså exakt felet metoden skrevs
         * för att hindra: Golf VIII som 2024 i stället för 2020.
         */
        assertThat(AutoDataScraperService.basTitel("Lexus IS III (XE30, facelift 2020)"))
                .isEqualTo("lexus is iii xe30");
        // samma bastitel som basgenerationen, alltså samma grupp — det är hela poängen
        assertThat(AutoDataScraperService.basTitel("Lexus IS III (XE30)"))
                .isEqualTo("lexus is iii xe30");

        /*
         * Gränsen åt andra hållet: chassikoden ska vara KVAR i bastiteln. BMW skiljer inte sina
         * generationer med romerska siffror utan bara med kod, så skalas koden bort blir G20 och
         * F30 samma grupp — och basgenerationsStartAr tar det minsta årtalet i gruppen, alltså
         * F30:s 2011 för en G20 från 2018.
         */
        assertThat(AutoDataScraperService.basTitel("BMW 3 Series Sedan (G20)"))
                .isNotEqualTo(AutoDataScraperService.basTitel("BMW 3 Series Sedan (F30)"));
    }

    @Test
    void chassikodenFallerInteEnGeneration() {
        /*
         * Den enskilt största orsaken till att generationsifyllningen stod stilla (2026-08-16):
         * titelnRymsIBilnamnet kräver att varje kvarvarande ord finns i bilnamnet, och
         * chassikoden stod inte i TITELBRUS. "xe30" fällde alltså VARENDA generation på
         * modellsidan och hela modellen antecknades som ett nej. 56 av 139 parkerade missar låg
         * på BMW och 28 på Audi, två märken där auto-data sätter kod på nästan varje titel.
         */
        var gen = AutoDataScraperService.parseGenerationer(fixtur("lexus-is-model.html"));
        assertThat(gen).isNotEmpty();

        assertThat(AutoDataScraperService.valjGeneration(gen, "Lexus is", null, false)).isNotNull();
        // IS III (XE30) kom 2013 — inte faceliftens 2020 eller 2025
        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Lexus is")).isEqualTo(2013);
    }

    @Test
    void karossordetFallerGenerationenBaraNarKarossenAvgor() {
        /*
         * Alla sju RS4-generationer är Avant, Cabrio eller Saloon — det finns ingen titel som kan
         * passera karosskravet, så modellen kunde aldrig dateras hur väl parsern än fungerade.
         * För ÅRTALET spelar karossen ingen roll: Avant och Saloon är samma generation samma år.
         */
        var gen = AutoDataScraperService.parseGenerationer(fixtur("audi-rs4-model.html"));

        assertThat(AutoDataScraperService.valjGeneration(gen, "Audi rs4", null, false)).isNotNull();
        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Audi rs4")).isEqualTo(2017);

        /*
         * Gränsen åt andra hållet, och den är inte teoretisk: bagagevolymen ÄR karossberoende, och
         * en halvkombi som får kombins liter är ett rakt fel. Med karosskravet påslaget står
         * filtret kvar precis som förut.
         */
        assertThat(AutoDataScraperService.valjGeneration(gen, "Audi rs4", null, true)).isNull();

        var golf = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));
        assertThat(AutoDataScraperService.valjGeneration(golf, "Volkswagen Golf", 2021, true).titel())
                .isEqualTo("Volkswagen Golf VIII");
    }

    @Test
    void utanKarosskravValjsBasbilenInteUndermodellen() {
        /*
         * Ordningen måste vändas när karosskravet är av. Med "mest specifika titeln först" — rätt
         * regel när karossen valts — vinner en UNDERMODELL: "Audi TT RS Roadster" i stället för
         * "Audi TT Coupe", och dess startår är RS-versionens 2016, inte generationens 2014.
         */
        var gen = AutoDataScraperService.parseGenerationer(fixtur("audi-tt-model.html"));

        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, "Audi tt")).isEqualTo(2014);
    }

    @Test
    void bmwSeriebeteckningOversattsTillSerie() {
        /*
         * ice_consumption har 56 BMW-rader som "BMW 320d" och "BMW 330e", medan auto-data bara
         * känner "3 Series". Modellvalet hittade därför ingenting alls. Serien står i första
         * siffran, och det gäller även M-varianterna.
         */
        assertThat(AutoDataScraperService.uppslagsnamn("BMW 320d")).isEqualTo("bmw 3 series");
        assertThat(AutoDataScraperService.uppslagsnamn("BMW 330e")).isEqualTo("bmw 3 series");
        assertThat(AutoDataScraperService.uppslagsnamn("BMW 128ti")).isEqualTo("bmw 1 series");
        assertThat(AutoDataScraperService.uppslagsnamn("BMW m135i")).isEqualTo("bmw 1 series");
        assertThat(AutoDataScraperService.uppslagsnamn("BMW m760i")).isEqualTo("bmw 7 series");

        /*
         * Gränsen åt andra hållet: bokstavsmodellerna finns som egna modeller hos auto-data och
         * får INTE översättas. X3 är ingen 3-serie, och M3 är en egen modellsida.
         */
        assertThat(AutoDataScraperService.uppslagsnamn("BMW x3")).isEqualTo("BMW x3");
        assertThat(AutoDataScraperService.uppslagsnamn("BMW m3")).isEqualTo("BMW m3");
        assertThat(AutoDataScraperService.uppslagsnamn("Volkswagen golf")).isEqualTo("Volkswagen golf");
        assertThat(AutoDataScraperService.uppslagsnamn(null)).isNull();
    }

    @Test
    void avkortadeModellnamnOversattsTillAutoDatasEgna() {
        /*
         * allModelNames ger märke + ETT modellord, så "Mercedes-Benz C 220 d" blir
         * "Mercedes-Benz c". Auto-data listar modellen som "C-class", och modellsidaFor kräver
         * att modellnamnet ryms i bilnamnet — därför föll uppslaget redan på modellsidan.
         * 27 av 310 namnplåtar låg som ej-hittad av precis det skälet 2026-08-20.
         */
        assertThat(AutoDataScraperService.uppslagsnamn("Mercedes-Benz c")).isEqualTo("Mercedes-Benz C-class");
        assertThat(AutoDataScraperService.uppslagsnamn("Mercedes-Benz s")).isEqualTo("Mercedes-Benz S-class");
        assertThat(AutoDataScraperService.uppslagsnamn("Hyundai santa")).isEqualTo("Hyundai Santa Fe");
        assertThat(AutoDataScraperService.uppslagsnamn("Jeep grand")).isEqualTo("Jeep Grand Cherokee");
        assertThat(AutoDataScraperService.uppslagsnamn("Toyota land")).isEqualTo("Toyota Land Cruiser");

        /*
         * De tre stavningarna man inte hade gissat, alla avlästa ur a.modeli > strong:
         * Kia skriver Cee'd med apostrof, Suzuki har ingen naken SX4, och Mini har varken
         * "Cooper" eller "John Cooper Works" som modeller — båda är generationer av Hatch.
         */
        assertThat(AutoDataScraperService.uppslagsnamn("Kia ceed")).isEqualTo("Kia Cee'd");
        assertThat(AutoDataScraperService.uppslagsnamn("Kia proceed")).isEqualTo("Kia Pro Cee'd");
        assertThat(AutoDataScraperService.uppslagsnamn("Suzuki sx4")).isEqualTo("Suzuki SX4 S-Cross");
        assertThat(AutoDataScraperService.uppslagsnamn("Mini cooper")).isEqualTo("Mini Hatch");
        assertThat(AutoDataScraperService.uppslagsnamn("Mini john")).isEqualTo("Mini Hatch");
    }

    @Test
    void nycklarSomRymmerFleraBilarOversattsInte() {
        /*
         * Gränsen åt andra hållet, och den är ett medvetet avstående. Ett årtal sparas per
         * NYCKEL, så en nyckel som rymmer flera bilar får ett årtal som är fel för de andra:
         * "Audi rs" är både RS Q3 och RS Q8, "Land Rover range" är Range Rover, Evoque OCH
         * Sport. Utan årtal är vakten inert och kortet visar listan som förut — det ofarliga
         * läget. "Toyota gr" (GR Yaris) finns inte som modell hos auto-data över huvud taget.
         */
        assertThat(AutoDataScraperService.uppslagsnamn("Audi rs")).isEqualTo("Audi rs");
        assertThat(AutoDataScraperService.uppslagsnamn("Land Rover range")).isEqualTo("Land Rover range");
        assertThat(AutoDataScraperService.uppslagsnamn("Toyota gr")).isEqualTo("Toyota gr");
    }

    @Test
    void citroensDiakritFallerTillbakaPaAsciiSomMarkessluggen() {
        /*
         * normalisera släpper igenom åäöéèü men INTE ë, som blir ett blanksteg: "Citroën c3"
         * blev "citro n c3" och matchade inget märke alls, eftersom auto-datas märkesnyckel
         * kommer ur URL-sluggen och är ren ASCII ("citroen"). Alla fem Citroën-rader låg som
         * ej-hittad av det skälet — felet satt i en teckenklass, inte i modellnamnet.
         */
        assertThat(AutoDataScraperService.uppslagsnamn("Citroën c3")).isEqualTo("Citroen c3");
        assertThat(AutoDataScraperService.uppslagsnamn("Citroën berlingo")).isEqualTo("Citroen berlingo");

        // Och c5 bär BÅDA lagren: först ASCII, sedan modelltabellen. Alla våra fem C5-rader är
        // C5 Aircross; naken "C5" är en annan bil (2001-2017) och hade gett fel generationsår.
        assertThat(AutoDataScraperService.uppslagsnamn("Citroën c5")).isEqualTo("Citroen C5 Aircross");
    }

    @Test
    void oversattningenTraffarModellenPaRiktigaMarkessidor() {
        /*
         * Det avgörande provet: alias ÄR ingenting värt om auto-datas modellista inte tar emot
         * dem. Sidorna är hämtade skarpt 2026-08-20, och gamla formen ska falla på samma sida
         * som den nya träffar — annars bevisar testet bara att strängen ändrats.
         */
        var mercedes = AutoDataScraperService.parseModeller(fixtur("mercedes-benz-brand.html"));
        assertThat(AutoDataScraperService.modellsidaFor("Mercedes-Benz c", mercedes)).isNull();
        assertThat(AutoDataScraperService.modellsidaFor(
                AutoDataScraperService.uppslagsnamn("Mercedes-Benz c"), mercedes))
                .endsWith("mercedes-benz-c-class-model-1368");

        // Apostrofen är hela poängen med Kia: normalisera gör "Cee'd" till "cee d", så vårt
        // hopskrivna "ceed" kunde aldrig träffa. Och ProCeed måste vinna över Cee'd — längsta
        // modellnamnet som ryms vinner, annars hade en ProCeed daterats som en Cee'd.
        var kia = AutoDataScraperService.parseModeller(fixtur("kia-brand.html"));
        assertThat(AutoDataScraperService.modellsidaFor("Kia ceed", kia)).isNull();
        assertThat(AutoDataScraperService.modellsidaFor(
                AutoDataScraperService.uppslagsnamn("Kia ceed"), kia)).contains("kia-ceed-model");
        assertThat(AutoDataScraperService.modellsidaFor(
                AutoDataScraperService.uppslagsnamn("Kia proceed"), kia)).contains("kia-pro-ceed-model");

        // Mini har ingen modell som heter Cooper — bilen ligger under Hatch.
        var mini = AutoDataScraperService.parseModeller(fixtur("mini-brand.html"));
        assertThat(mini).extracting(AutoDataScraperService.Modell::namn).doesNotContain("Cooper");
        assertThat(AutoDataScraperService.modellsidaFor("Mini cooper", mini)).isNull();
        assertThat(AutoDataScraperService.modellsidaFor(
                AutoDataScraperService.uppslagsnamn("Mini cooper"), mini)).contains("hatch");
    }

    @Test
    void markesindexetBarFlerMarkenAnSidfoten() {
        /*
         * Sidfoten såg ut som hela märkeslistan tills Abarth och Alpine visade sig saknas där:
         * fyra av våra namnplåtar (Abarth 500/595/695, Alpine A110) kunde alltså inte slås upp
         * hur rätt modellnamnet än var. /en/allbrands är sidans egen länk vidare till resten.
         */
        var sidfoten = AutoDataScraperService.parseMarken(fixtur("volkswagen-brand.html"));
        var indexet = AutoDataScraperService.parseMarken(fixtur("allbrands.html"));

        assertThat(sidfoten).doesNotContainKeys("abarth", "alpine");
        assertThat(indexet).containsKeys("abarth", "alpine");
        assertThat(indexet.size()).isGreaterThan(sidfoten.size() * 4);

        // Äkta delmängd: inget märke tappas, alltså kan ingen befintlig rad byta märkesträff.
        assertThat(indexet.keySet()).containsAll(sidfoten.keySet());
    }

    @Test
    void bmwSerienGarAttDateraEfterOversattningen() {
        // Hela kedjan för en översatt rad: 3-seriens sida ska ge G20:ans startår, inte F30:ans.
        var gen = AutoDataScraperService.parseGenerationer(fixtur("bmw-3-series-model.html"));
        assertThat(gen).isNotEmpty();

        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 330e");
        assertThat(AutoDataScraperService.valjGeneration(gen, uppslag, null, false)).isNotNull();
        assertThat(AutoDataScraperService.basgenerationsStartAr(gen, uppslag)).isEqualTo(2018);
    }

    @Test
    void kombinValjsBaraNarBilnamnetSagerKombi() {
        // Golf VIII och Golf VIII Variant delar årsspann men har olika bagagevolym. Utan
        // karossfiltret hade en halvkombi kunnat få kombins liter.
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", 2021).titel())
                .isEqualTo("Volkswagen Golf VIII");
        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf Variant", 2021).titel())
                .isEqualTo("Volkswagen Golf VIII Variant");
        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf Alltrack", 2021).titel())
                .isEqualTo("Volkswagen Golf VIII Alltrack");
    }

    @Test
    void arsmodellUtanforAllaSpannGerNull() {
        // Hellre ingen volym än fel generations volym — att falla tillbaka på "närmaste"
        // generation är exakt det som gav Nissan Leaf 2019 en 2026-modells räckvidd.
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", 1960)).isNull();
    }

    @Test
    void iSkarvaretVinnerDenNyareGenerationen() {
        // Generationer överlappar alltid: Golf VII såldes till 2021 medan VIII kom 2020, och
        // både "2020 - 2024" och "2024 -" rymmer 2024. Ett krav på entydighet hade gett null för
        // nästan varje skarvår, så den som börjar senast vinner.
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", 2021).titel())
                .isEqualTo("Volkswagen Golf VIII");            // inte VII, som såldes till 2021
        assertThat(AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", 2024).titel())
                .isEqualTo("Volkswagen Golf VIII (facelift 2024)");
    }

    @Test
    void aldreArsmodellFarAldrigEnNyareGenerationsSiffror() {
        // Skyddet som faktiskt betyder något: det var precis det här som gav Nissan Leaf 2019
        // en 2026-modells räckvidd och MG ZS EV 2020 faceliftens batteri.
        var gen = AutoDataScraperService.parseGenerationer(fixtur("golf-model.html"));

        var vald = AutoDataScraperService.valjGeneration(gen, "Volkswagen Golf", 2012);

        assertThat(vald.franAr()).isLessThanOrEqualTo(2012);
        assertThat(vald.titel()).doesNotContain("VIII");
    }

    @Test
    void modellsidaUtanGenerationerGerTomLista() {
        assertThat(AutoDataScraperService.parseGenerationer("<html><body>tomt</body></html>")).isEmpty();
        assertThat(AutoDataScraperService.parseGenerationer(null)).isEmpty();
        assertThat(AutoDataScraperService.valjGeneration(List.of(), "Volkswagen Golf", 2021)).isNull();
    }

    @Test
    void sidaUtanBagageradGerNull() {
        assertThat(AutoDataScraperService.parseBagagevolym("<html><body><th>Kerb Weight</th><td>1265 kg</td></body></html>")).isNull();
        assertThat(AutoDataScraperService.parseBagagevolym("")).isNull();
        assertThat(AutoDataScraperService.parseBagagevolym(null)).isNull();
    }
    @Test
    void effektprovetHittarGenerationenSomBarVaraHastkrafter() {
        // 2026-08-19: hela BMW:s 5-serie låg som vakten-avstod (0 av 8) därför att uppslaget bara
        // prövade den NYASTE generationen. Vår rad "BMW 520d 2.0 D 190 hk" är G30; auto-datas
        // senaste är G61 Touring från 2024 med 197/208/299/303/489 hk. Disjunkt → nej, fast rätt
        // svar låg tre rader ned på samma sida.
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        assertThat(alla).isNotEmpty();
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");

        // Motorlistan per generation, som sidorna faktiskt ser ut: G60/G61 bär den nya B48/B47
        // med 197-208 hk, G30 LCI bär 190 hk-dieseln som är vår.
        java.util.function.Function<AutoDataScraperService.Generation,
                java.util.List<AutoDataScraperService.MotorAlternativ>> motorer = g -> {
            if (g.titel().contains("G61") || g.titel().contains("G60"))
                return motorlista(197, 208, 299, 489);
            if (g.titel().contains("G30") || g.titel().contains("G31"))
                return motorlista(190, 252, 340, 286);
            return motorlista(150, 218);   // äldre generationer
        };

        var prov = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", java.util.Set.of(190), motorer);

        assertThat(prov.utfall()).isEqualTo(AutoDataScraperService.Provutfall.TRAFF);
        // G30 LCI är en facelift; kedjan/kodgruppen tar oss till generationens eget startår.
        assertThat(prov.franAr()).isEqualTo(2017);
        assertThat(prov.generation()).contains("G30");
    }

    @Test
    void effektprovetAvstarNarIngenGenerationBarVaraHastkrafter() {
        // Vakten blir inte svagare av att flera generationer prövas: bär ingen av dem vår
        // hästkraft är svaret fortfarande nej. Det är CX-5-fallet — en ny generation med en enda
        // motor får inte datera de sju varianter vi har.
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");

        var prov = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", java.util.Set.of(999),
                g -> motorlista(190, 197, 208));

        assertThat(prov.utfall()).isEqualTo(AutoDataScraperService.Provutfall.INGEN_TRAFF);
        assertThat(prov.franAr()).isNull();   // inget årtal sparas på en gissning
    }

    @Test
    void effektprovetSlutarLetaEfterTaketSaEnSaknadModellInteHamtarHalvaSajten() {
        // Sidhämtningen kostar 1,5 s styck. 5-seriens sida har 29 generationer, och en modell vars
        // hästkraft inte finns någonstans skulle annars beta av allihop varje natt.
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        assertThat(alla.size()).isGreaterThan(AutoDataScraperService.MAX_GENERATIONER_PROV);
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");
        java.util.concurrent.atomic.AtomicInteger hamtningar = new java.util.concurrent.atomic.AtomicInteger();

        AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", java.util.Set.of(999), g -> {
            hamtningar.incrementAndGet();
            return motorlista(190);
        });

        assertThat(hamtningar.get()).isEqualTo(AutoDataScraperService.MAX_GENERATIONER_PROV);
    }

    @Test
    void tomMotorlistaArHamtningsfelOchParkerarInteModellen() {
        // Ett nät som inte svarar är inget nej. Går ingen enda motorlista att läsa står den nyaste
        // generationens årtal kvar OPRÖVAT — samma bedömning som före 2026-08-19 — i stället för
        // att modellen parkeras i 30 dagar för något som var nätet.
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");

        var prov = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", java.util.Set.of(190),
                g -> java.util.List.of());

        assertThat(prov.utfall()).isEqualTo(AutoDataScraperService.Provutfall.OPROVAD);
        assertThat(prov.franAr()).isNotNull();
    }

    @Test
    void utanEgnaHastkrafterGallerDetGamlaSvaret() {
        // Ingen motorlista hos oss = inget att pröva mot. Då är den nyaste generationen fortfarande
        // bästa gissningen, precis som förut, och den märks som OPRÖVAD i loggen.
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");

        var prov = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", java.util.Set.of(),
                g -> motorlista(190));

        assertThat(prov.utfall()).isEqualTo(AutoDataScraperService.Provutfall.OPROVAD);
        assertThat(prov.franAr()).isNotNull();
    }

    @Test
    void familjenSkiljerTvaGenerationerSomBarSammaBeteckningMedSammaEffekt() {
        /*
         * 730d-fallet, mätt skarpt 2026-08-19. "BMW 730d 3.0 D 286 hk" finns i BÅDA generationerna
         * — G11 (2015) och G70 (2022) — så beteckningsprovet kan inte skilja dem åt och nyast vann.
         * Resten av sjuan landade på 2015, alltså daterades EN rad i familjen tjugo procent fel åt
         * det dyra hållet. Familjen avgör: G70 delar bara vår egen 286 med de sju sjuorna, medan
         * G11 LCI bär hela lineupen.
         *
         * Här spelar 5-seriens fixtur samma roll: den nyaste generationen bär bara vår egen
         * hästkraft, den äldre bär våra syskon också.
         */
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");
        java.util.Set<Integer> egna = java.util.Set.of(190);
        java.util.Set<Integer> familjen = java.util.Set.of(190, 252, 340, 286);
        java.util.function.Function<AutoDataScraperService.Generation,
                java.util.List<AutoDataScraperService.MotorAlternativ>> motorer = g -> {
            if (g.titel().contains("G60") || g.titel().contains("G61"))
                return java.util.List.of(motor("520d (190 Hp)", 190));            // smal: bara vår egen
            if (g.titel().contains("G30") || g.titel().contains("G31"))
                return java.util.List.of(motor("520d (190 Hp)", 190), motor("530d (286 Hp)", 286),
                                         motor("540i (340 Hp)", 340));
            return motorlista(150, 218);
        };

        var utan = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", egna, motorer);
        var med = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", egna, familjen, motorer);

        assertThat(utan.franAr()).isEqualTo(2023);            // den nyaste vinner utan familj
        assertThat(med.franAr()).isEqualTo(2017);             // familjen pekar ut rätt generation
        assertThat(med.generation()).contains("G30");
    }

    @Test
    void familjenFarInteDraEnBredTraffBakat() {
        /*
         * Motprovet, och det är hela skälet att regeln är smal. "BMW 320d 190 hk" finns i både
         * G20 (2018) och F30 (2011), och ett svar på 2011 vore samma sorts fel som 730d:s 2022 —
         * fast åt andra hållet. G20 bär dessutom 318d:ns 150 och 330i:ns 258, alltså delar den mer
         * än vår egen rad med familjen: träffen är inte smal, den gamla regeln råder, och den
         * äldre generationen hämtas aldrig ens.
         */
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");
        java.util.concurrent.atomic.AtomicInteger hamtningar = new java.util.concurrent.atomic.AtomicInteger();

        var med = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d",
                java.util.Set.of(190), java.util.Set.of(190, 252, 340, 286), g -> {
                    hamtningar.incrementAndGet();
                    if (g.titel().contains("G60") || g.titel().contains("G61"))
                        return java.util.List.of(motor("520d (190 Hp)", 190), motor("530i (252 Hp)", 252));
                    return java.util.List.of(motor("520d (190 Hp)", 190), motor("530d (286 Hp)", 286),
                                             motor("540i (340 Hp)", 340));
                });

        assertThat(med.franAr()).isEqualTo(2023);
        // Den breda träffen stänger sökningen direkt — annars hade varje BMW-rad kostat sex sidor.
        assertThat(hamtningar.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void enfamiljsmodellPaverkasInteAlls() {
        // Varje icke-BMW är sin egen familj ("Volkswagen golf" slår upp sig själv), och då ska
        // provet svara precis som före familjeregeln — samma årtal, samma antal hämtningar.
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");
        java.util.Set<Integer> egna = java.util.Set.of(190);
        java.util.function.Function<AutoDataScraperService.Generation,
                java.util.List<AutoDataScraperService.MotorAlternativ>> motorer =
                g -> java.util.List.of(motor("520d (190 Hp)", 190));

        var utan = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", egna, motorer);
        var med = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d", egna, egna, motorer);

        assertThat(med.franAr()).isEqualTo(utan.franAr());
        assertThat(med.generation()).isEqualTo(utan.generation());
    }

    @Test
    void familjtackningenRaknarDistinktaHastkrafter() {
        var deras = java.util.List.of(motor("730d (286 Hp)", 286), motor("740i (340 Hp)", 340),
                                      motor("740d (340 Hp)", 340), motor("i7 (544 Hp)", 544));

        assertThat(AutoDataScraperService.familjTackning(deras, java.util.Set.of(286, 340, 530))).isEqualTo(2);
        assertThat(AutoDataScraperService.familjTackning(deras, java.util.Set.of(286))).isEqualTo(1);
        assertThat(AutoDataScraperService.familjTackning(deras, java.util.Set.of())).isZero();
    }

    private static java.util.List<AutoDataScraperService.MotorAlternativ> motorlista(int... hk) {
        java.util.List<AutoDataScraperService.MotorAlternativ> ut = new java.util.ArrayList<>();
        for (int h : hk) ut.add(new AutoDataScraperService.MotorAlternativ("520d (" + h + " Hp) Steptronic", h, "2020 - 2024", "/en/x"));
        return ut;
    }

    @Test
    void bensinarenMedSammaHastkraftFarInteDateraDieseln() {
        /*
         * Mätt mot skarpa auto-data 2026-08-19, och det här är hela anledningen att provet inte
         * får nöja sig med en siffra: G60:s sida från 2023 bär "520i (190 Hp)" — en BENSINARE —
         * medan vår rad "BMW 520d 2.0 D 190 hk" är G30:ans diesel. Ett prov på bara hästkraften
         * tog G60 som träff och hade daterat en 2017-bil till 2023, alltså precis det fel vakten
         * finns för att stoppa. Bär listan vår beteckning jämförs bara de motorerna: 520d är
         * 197 hk hos G60 och 190 hk hos G30 LCI.
         */
        var alla = AutoDataScraperService.parseGenerationer(fixtur("bmw-5-series-model.html"));
        String uppslag = AutoDataScraperService.uppslagsnamn("BMW 520d");

        var prov = AutoDataScraperService.artalMedEffektprov(alla, uppslag, "BMW 520d",
                java.util.Set.of(190), g -> g.titel().contains("G60") || g.titel().contains("G61")
                        ? java.util.List.of(motor("520i (208 Hp)", 208), motor("520i (190 Hp)", 190),
                                            motor("520d (197 Hp)", 197))
                        : java.util.List.of(motor("520i (184 Hp)", 184), motor("520d (190 Hp)", 190)));

        assertThat(prov.utfall()).isEqualTo(AutoDataScraperService.Provutfall.TRAFF);
        assertThat(prov.generation()).contains("G30");
        assertThat(prov.franAr()).isEqualTo(2017);
    }

    @Test
    void utanBeteckningIListanGallerRenHastkraft() {
        // Reservregeln, och den bär alla andra märken: "Volkswagen golf" heter inte något som
        // liknar "1.5 TSI (150 Hp)", så utan den hade ingen icke-BMW kunnat dateras alls.
        var deras = java.util.List.of(motor("1.5 TSI (150 Hp)", 150), motor("2.0 TDI (115 Hp)", 115));

        assertThat(AutoDataScraperService.barVaraEffekter(deras, null, java.util.Set.of(150))).isTrue();
        assertThat(AutoDataScraperService.barVaraEffekter(deras, "golf", java.util.Set.of(150))).isTrue();
        assertThat(AutoDataScraperService.barVaraEffekter(deras, null, java.util.Set.of(999))).isFalse();
    }

    @Test
    void beteckningenMatchasSomEgetOrdSaM5InteBlirM550i() {
        // "m5" är en äkta delsträng av "M550i". Utan ordgräns hade en M5-rad matchat M550i:s
        // 530 hk och daterats efter fel bil.
        var deras = java.util.List.of(motor("M550i (530 Hp) xDrive", 530), motor("M5 (600 Hp)", 600));

        assertThat(AutoDataScraperService.barVaraEffekter(deras, "m5", java.util.Set.of(600))).isTrue();
        assertThat(AutoDataScraperService.barVaraEffekter(deras, "m5", java.util.Set.of(530))).isFalse();
    }

    @Test
    void beteckningenPlockasBaraUrNamnSomBarEn() {
        assertThat(AutoDataScraperService.motorbeteckning("BMW 520d")).isEqualTo("520d");
        assertThat(AutoDataScraperService.motorbeteckning("BMW m5")).isEqualTo("m5");
        assertThat(AutoDataScraperService.motorbeteckning("BMW 330e")).isEqualTo("330e");
        // Modellnamn är inga beteckningar — annars hade provet letat efter "golf" bland motorerna.
        assertThat(AutoDataScraperService.motorbeteckning("Volkswagen golf")).isNull();
        assertThat(AutoDataScraperService.motorbeteckning("Mazda cx-5")).isNull();
        assertThat(AutoDataScraperService.motorbeteckning(null)).isNull();
    }

    @Test
    void kombinFarInteDateraSedanenEttArForSent() {
        /*
         * BMW 3-serien är sedan (G20, 2018) och kombi (G21, 2019), och vår rad "BMW 330i 258 hk"
         * saknar karossord — den matchar båda. Nyast vinner annars, alltså kombins 2019, och ett
         * för HÖGT årtal är den dyra riktningen: det tystar 2018 års sedan, som i dag får sin
         * lista. Träffar inom NARA_AR vägs därför mot varandra och lägsta startåret vinner.
         */
        var alla = java.util.List.of(
                new AutoDataScraperService.Generation("BMW 3 Series Touring (G21)", 2019, null, "Station wagon", "/t"),
                new AutoDataScraperService.Generation("BMW 3 Series Sedan (G20)", 2018, null, "Sedan", "/s"));

        var prov = AutoDataScraperService.artalMedEffektprov(alla, "bmw 3 series", "BMW 330i",
                java.util.Set.of(258), g -> java.util.List.of(motor("330i (258 Hp)", 258)));

        assertThat(prov.utfall()).isEqualTo(AutoDataScraperService.Provutfall.TRAFF);
        assertThat(prov.franAr()).isEqualTo(2018);
    }

    @Test
    void enEnsamNyckelFarInteVinnasAvEnEndaDeladHastkraft() {
        /*
         * Mätt på hela tabellen 2026-08-20: 24 rader hade ett årtal från en generation som bar
         * EN av våra hästkrafter medan en annan bar fyra till sex. Mercedes GLC stod på 2026
         * (X540, en helt ny bil som auto-data publicerat långt före säljstart) i stället för
         * 2022 — alltså tystnade varenda GLC 2022-2025.
         *
         * Orsaken är att smal-regeln bara kan slå till när familjen är STÖRRE än vår egen rad,
         * och en modell som är ensam om sitt uppslagsnamn har familjen == vår egen lista. Då
         * bröts sökningen direkt efter första träffen, och X254 hämtades aldrig ens.
         */
        var alla = java.util.List.of(
                new AutoDataScraperService.Generation("Mercedes-Benz GLC SUV (X540)", 2026, null, "SUV", "/x540"),
                new AutoDataScraperService.Generation("Mercedes-Benz GLC (X254)", 2022, null, "SUV", "/x254"));
        var vara = java.util.Set.of(204, 163, 197, 258, 265, 313, 421);
        java.util.concurrent.atomic.AtomicInteger hamtningar = new java.util.concurrent.atomic.AtomicInteger();

        var prov = AutoDataScraperService.artalMedEffektprov(alla, "Mercedes-Benz C-class",
                "Mercedes-Benz GLC 220 d", vara, vara, g -> {
                    hamtningar.incrementAndGet();
                    // X540 delar exakt EN siffra med oss — sammanträffandet som vann förut.
                    if (g.titel().contains("X540"))
                        return java.util.List.of(motor("GLC 300 (204 Hp)", 204), motor("GLC 400 (489 Hp)", 489));
                    return java.util.List.of(motor("GLC 200 (204 Hp)", 204), motor("GLC 200 d (163 Hp)", 163),
                            motor("GLC 220 d (197 Hp)", 197), motor("GLC 300 (258 Hp)", 258),
                            motor("GLC 300 d (265 Hp)", 265), motor("GLC 300 e (313 Hp)", 313));
                });

        assertThat(prov.franAr()).isEqualTo(2022);
        assertThat(prov.generation()).contains("X254");
        // Sökningen MÅSTE ha gått vidare förbi den första träffen för att hitta den.
        assertThat(hamtningar.get()).isEqualTo(2);
    }

    @Test
    void heltackandeTraffStangerSokningenAven_narFamiljenArStorre() {
        /*
         * Gränsen åt andra hållet, och den är mätt: gränsen går vid VÅR EGEN lista, inte
         * familjens. "BMW 520d 190 hk" mot en familj på fyra effekter träffar G60 som bär 190
         * och 252 — ofullständigt mot familjen, men vår egen rad har bara 190 och den är helt
         * täckt. Skulle familjen få avgöra hade varje bred BMW-träff fortsatt leta och dragits
         * bakåt, alltså exakt det fel smal-regeln skrevs för att hindra.
         *
         * Motprovet i familjenFarInteDraEnBredTraffBakat mäter samma gräns genom hämtningarna;
         * det här mäter den genom att säga ut villkoret.
         */
        var alla = java.util.List.of(
                new AutoDataScraperService.Generation("BMW 5 Series Sedan (G60)", 2023, null, "Sedan", "/g60"),
                new AutoDataScraperService.Generation("BMW 5 Series Sedan (G30)", 2016, null, "Sedan", "/g30"));
        java.util.concurrent.atomic.AtomicInteger hamtningar = new java.util.concurrent.atomic.AtomicInteger();

        var prov = AutoDataScraperService.artalMedEffektprov(alla, "bmw 5 series", "BMW 520d",
                java.util.Set.of(190), java.util.Set.of(190, 252, 340, 286), g -> {
                    hamtningar.incrementAndGet();
                    if (g.titel().contains("G60"))
                        return java.util.List.of(motor("520d (190 Hp)", 190), motor("530i (252 Hp)", 252));
                    return java.util.List.of(motor("520d (190 Hp)", 190), motor("530d (286 Hp)", 286),
                            motor("540i (340 Hp)", 340));
                });

        assertThat(prov.franAr()).isEqualTo(2023);
        assertThat(hamtningar.get()).isEqualTo(1);
    }

    private static AutoDataScraperService.MotorAlternativ motor(String namn, int hk) {
        return new AutoDataScraperService.MotorAlternativ(namn, hk, "2020 - 2024", "/en/x");
    }

}
