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
        // Sidfoten bär alla märken, så vi slipper sitemapen (27 MB i fyra skärvor, sju språk).
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
}
