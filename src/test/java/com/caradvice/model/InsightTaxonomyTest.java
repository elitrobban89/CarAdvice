package com.caradvice.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kategorivakten: bilens egen modell får motsäga kategorin AI:n satte.
 *
 * <p>Bakgrunden är natten mot 2026-09-19, då Saab 9-3 och Cadillac BLS sparades som
 * {@code smaabil} trots att promptregeln uttryckligen förbjudit mellanklassbilar sedan
 * 2026-08-10. Genomgången samma morgon visade att felet fanns åt BÅDA håll i tabellen:
 * nio för stora bilar som {@code smaabil} och tio låga bilar som {@code suv}.
 *
 * @author Robert Andersson Kopler
 */
class InsightTaxonomyTest {

    @Test
    void storBilFarInteVaraSmaabil() {
        // De nio raderna som faktiskt låg i drift 2026-09-19
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Saab", "9-3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Cadillac", "BLS")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Tesla", "Model 3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Polestar", "2")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID.7")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Dacia", "Jogger")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Dacia", "Duster Extreme")).isNull();
    }

    @Test
    void lagBilFarInteVaraSuv() {
        // Samma tio rader åt andra hållet — vakten som bara ser ett håll är halv
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Kia", "Niro")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Hyundai", "Kona Electric")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Hyundai", "Kona N")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Polestar", "2")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volkswagen", "Golf Alltrack")).isNull();
    }

    /**
     * ID.3 är listans enda kompaktbil, tillagd 2026-09-19 em på användarens beslut: den ströks ur
     * småbilslistan i rekommendationsprompten för att den är Golf-klass med fem säten, men regeln
     * fanns bara på läsvägen — sex ID.3 GTI-rader och två vanliga ID.3 låg som smaabil i drift.
     */
    @Test
    void id3ArAldrigSmaabil() {
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID.3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID.3 GTI")).isNull();
        // AI:n skriver bagge formerna
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID3")).isNull();
        // Syskonen far inte falla med: ID.4 och ID. Polo ar inte samma bil
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID. Polo")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volkswagen", "ID.4")).isEqualTo("suv");
        // suv-hallet var redan tackt av LAGA_MODELLER och ska fortsatta falla
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volkswagen", "ID.3")).isNull();
    }

    /**
     * Tredje regeln: en lyx- eller sportbil ar ALDRIG smaabil eller familjebil. Raderna nedan lag
     * i drift 2026-09-19 - nio lyxrader och tre sportbilsrader.
     */
    @Test
    void lyxOchSportbilarFarVarkenVaraSmaabilEllerFamiljebil() {
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Audi", "A8")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Tesla", "Model S")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Mercedes", "S-Klass")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Mercedes", "EQS 450")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Maserati", "Grecale Trofeo")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "BMW", "M5")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Mazda", "MX-5")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Porsche", "911 Carrera")).isNull();
        // De fem raderna som stod kvar 2026-09-22: samma segment som A8 och S-Klass, men utan markör
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Jaguar", "XJ8")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Lexus", "LS 460")).isNull();
    }

    /**
     * Anvandarens tva medvetna gransfall (id 1239 och 1246) star kvar som familjebil - listan far
     * inte ata dem, och en SUV-kategori pa en lyxbil ar sann och ska sta kvar.
     */
    @Test
    void lyxvaktenRorInteGransfallenEllerSuvKategorin() {
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Polestar", "5")).isEqualTo("familjebil");
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Audi", "A6")).isEqualTo("familjebil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Cadillac", "Escalade")).isEqualTo("suv");
        assertThat(InsightTaxonomy.canonicalCategory("elbil", "Tesla", "Model S")).isEqualTo("elbil");
        // Varmhalvkombierna ar en egen gransdragning och ingar INTE i listan
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "GR Yaris")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Mini", "Cooper SE")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Cupra", "Raval VZ")).isEqualTo("smaabil");
        // Prestandaversioner av vanliga bilar hor till samma gransdragning (id 875-878 och 1453)
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Mercedes", "CLA 45")).isEqualTo("familjebil");
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "BMW", "M350 xDrive")).isEqualTo("familjebil");
        // Jaguar XF ar mellanklass som A6 och E-Klass - markoren bar market just for att inte ata den
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Jaguar", "XF")).isEqualTo("familjebil");
    }

    /**
     * Drivmedelsfaltet far den normalisering kategorifaltet haft sedan 2026-08-10.
     *
     * <p>Rullgardinen postar {@code el}, tabellen stavar {@code elbil}, och 2026-09-22 bar
     * 561 av 1 162 rader den ena stavningen och EN rad den andra. Aliaset ar en stavning:
     * {@code mildhybrid} och {@code etanol} ar egna drivlinor utan knapp i formularet och
     * ska falla, inte skrivas om till narmaste granne.
     */
    @Test
    void drivmedletOversattsTillTabellensStavning() {
        assertThat(InsightTaxonomy.canonicalFuel("el")).isEqualTo("elbil");
        assertThat(InsightTaxonomy.canonicalFuel("El ")).isEqualTo("elbil");
        assertThat(InsightTaxonomy.canonicalFuel("elbil")).isEqualTo("elbil");
        assertThat(InsightTaxonomy.canonicalFuel("laddhybrid")).isEqualTo("laddhybrid");
        assertThat(InsightTaxonomy.canonicalFuel("")).isNull();
        assertThat(InsightTaxonomy.canonicalFuel(null)).isNull();
        // Lasvagen slapper igenom ett okant varde oforandrat - en sokning ska inte tystna
        assertThat(InsightTaxonomy.canonicalFuel("vatgas")).isEqualTo("vatgas");
        // Skrivvagen gor det inte
        assertThat(InsightTaxonomy.validFuel("el")).isEqualTo("elbil");
        assertThat(InsightTaxonomy.validFuel("mildhybrid")).isNull();
        assertThat(InsightTaxonomy.validFuel("etanol")).isNull();
        assertThat(InsightTaxonomy.isUnknownFuel("mildhybrid")).isTrue();
        assertThat(InsightTaxonomy.isUnknownFuel("el")).isFalse();
        assertThat(InsightTaxonomy.isUnknownFuel("")).isFalse();
        assertThat(InsightTaxonomy.isUnknownFuel(null)).isFalse();
        assertThat(InsightTaxonomy.fuelError("mildhybrid")).contains("mildhybrid", "elbil", "eller tomt");
    }

    @Test
    void lyxvaktensMotsagelseNamnerBilen() {
        assertThat(InsightTaxonomy.kategoriMotsagelse("familjebil", "Audi", "A8"))
                .isEqualTo("audi a8 är en lyx- eller sportbil");
    }

    @Test
    void riktigaSmaabilarOchSuvarSlapperIgenom() {
        // Fäller på positivt bevis: en modell utanför listorna rörs aldrig
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Yaris")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Aygo X")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Dacia", "Sandero")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volvo", "XC60")).isEqualTo("suv");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Skoda", "Kodiaq")).isEqualTo("suv");
    }

    @Test
    void yarisCrossFallerMenYarisStarKvar() {
        // Yaris Cross är en SUV, Yaris är bilen kategorin handlar om — delsträngen får inte
        // ta med sig syskonet i fallet
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Yaris Cross")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Yaris Hybrid")).isEqualTo("smaabil");
    }

    @Test
    void vaktenRorInteAndraKategorier() {
        // Bara smaabil och suv har motbevis — en Model 3 som familjebil eller elbil är sann
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Tesla", "Model 3")).isEqualTo("familjebil");
        assertThat(InsightTaxonomy.canonicalCategory("elbil", "Tesla", "Model 3")).isEqualTo("elbil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Tesla", "Model Y")).isEqualTo("suv");
    }

    @Test
    void aliasKanoniseradsForeMotbeviset() {
        // "småbil" skrivs om till "smaabil" först — annars hade aliaset gått fritt förbi vakten
        assertThat(InsightTaxonomy.canonicalCategory("småbil", "Saab", "9-3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("ekonomibil", "Tesla", "Model 3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("SMAABIL ", "Dacia", "Duster")).isNull();
    }

    @Test
    void utanModellRorsIngenting() {
        // Märkesbreda rader (CSV-kurerade) har ingen modell att motsäga kategorin med
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", null)).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", null, "")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.kategoriMotsagelse("suv", "Kia", null)).isNull();
    }

    @Test
    void motsagelsenBerattarVadSomFalldes() {
        // Texten går till loggen — utan den syns aldrig VARFÖR en kategori försvann
        assertThat(InsightTaxonomy.kategoriMotsagelse("smaabil", "Tesla", "Model 3"))
                .isEqualTo("tesla model 3 är ingen småbil");
        assertThat(InsightTaxonomy.kategoriMotsagelse("suv", "Kia", "Niro"))
                .isEqualTo("kia niro är ingen SUV");
        assertThat(InsightTaxonomy.kategoriMotsagelse("smaabil", "Toyota", "Yaris")).isNull();
    }

    @Test
    void okandKategoriArFortfarandeNull() {
        // Whitelisten gäller som förut — vakten läggs till, den ersätter ingenting
        assertThat(InsightTaxonomy.canonicalCategory("sportbil", "Porsche", "911")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("", "Toyota", "Yaris")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory(null, "Toyota", "Yaris")).isNull();
    }

    // ── Veteranvakten ────────────────────────────────────────────────────────────

    @Test
    void veteranbilarnaSomKomInNattenMot0920() {
        // De tre raderna natten mot 2026-09-20, ordagrant ur drift. Promptregeln mot
        // renoveringsobjekt har funnits sedan 2026-08-10 och läckte för tredje gången på
        // tio dagar — femte regeln i rad som behövde kodstöd.
        assertThat(InsightTaxonomy.veteranInnehall(
                "Saab 9000 Turbo från 1987 har endast gått 24 mil och är mycket välbevarad utan"
                + " rost, men elektroniken har försämrats av att ha stått stilla i nästan 40 år."))
                .contains("1987");
        assertThat(InsightTaxonomy.veteranInnehall(
                "Saab 9000 CD Turbo från 1988 har endast gått 61 kilometer, är rostfri och i gott"
                + " skick, men likt sin syster lider den av elektronikproblem."))
                .contains("1988");
        assertThat(InsightTaxonomy.veteranInnehall(
                "Den fabriksnya Volkswagen Bubbla från 1964 har en fyrcylindrig boxermotor på 40"
                + " hästkrafter och 87 Nm i vridmoment."))
                .contains("1964");
    }

    @Test
    void samlarordenFallerRaderSomArtaletMissar() {
        // Fyra rader i drift bär inget årsmodellsårtal alls men är lika mycket samlarobjekt.
        assertThat(InsightTaxonomy.veteranInnehall(
                "Bilen säljs för mellan 195 000 och 215 000 kronor och anses vara ett unikt"
                + " samlarobjekt med en ovanlig elstyrd sufflett.")).isNotNull();
        assertThat(InsightTaxonomy.veteranInnehall(
                "En fabriksny Volvo 240 med endast 114 mil på mätaren, gömd i 44 år, annonserades"
                + " för 300 000 kr.")).isNotNull();
        assertThat(InsightTaxonomy.veteranInnehall(
                "En välbevarad Volvo 240 GL såldes för 138 000 kr efter ett budkrig på nätet."))
                .isNotNull();
        assertThat(InsightTaxonomy.veteranInnehall(
                "En Volvo V70 såldes på auktion för cirka 90 000 kr.")).isNotNull();
    }

    @Test
    void vanligaKopradSlappsIgenom() {
        // Noll falska träffar över hela tabellen (1 159 rader) och repots fyra kurerade
        // CSV:er (240 rader), mätt 2026-09-20. De tre nedan är de riskabla formerna: ett
        // årtal som INTE är en årsmodell, och ett generationsspann inom parentes.
        assertThat(InsightTaxonomy.veteranInnehall(
                "Volvo XC60 fick 98 % vuxenskydd i Euro NCAP-testet 2017 och utsågs till årets"
                + " bästa bil.")).isNull();
        assertThat(InsightTaxonomy.veteranInnehall(
                "Bilprovningens 2025-statistik: hjullager underkänns på 1,4 % av besiktningar."))
                .isNull();
        assertThat(InsightTaxonomy.veteranInnehall(
                "Mazda 6 (2002-2012) lider av legendariska rostproblem på bakre subframe."))
                .isNull();
        assertThat(InsightTaxonomy.veteranInnehall("")).isNull();
        assertThat(InsightTaxonomy.veteranInnehall(null)).isNull();
    }

    @Test
    void gransenRullarMedKalendern() {
        // 30-årsgränsen är Transportstyrelsens och flyttar sig ett steg varje nyår. Provet
        // räknar den själv i stället för att skriva ett årtal som blir fel nästa år.
        int iAr = java.time.Year.now().getValue();
        assertThat(InsightTaxonomy.veteranInnehall("Bilen från " + (iAr - 31) + " gick bra."))
                .isNotNull();
        assertThat(InsightTaxonomy.veteranInnehall("Bilen från " + (iAr - 29) + " gick bra."))
                .isNull();
    }

    @Test
    void utgangenModellFallerDeTreSomTextenInteAvslojar() {
        // Ordagrant ur drift 2026-09-22. Ingen av de tre bär årtal i modellårsposition eller
        // samlarord — textreglerna returnerar null, och raderna stod därför kvar som köpråd
        // tills de rättades för hand. Mätningen före bygget: modellregelns EGNA bidrag över
        // hela tabellen (1 162 rader) är exakt de här tre, och noll falska träffar.
        String volvo780 = "Volvo 780 hade en 2,8-liters V6-motor med 156 hk och såldes"
                + " ursprungligen för omkring 300 000 kr.";
        String volvo240 = "Bilen har nyligen fått nya däck, nya bromsar och ett nytt bakre"
                + " avgassystem samt har genomgått frekvent oljebyte.";
        String audi100 = "Audi 100 5E utsågs till Årets Bil i Norden för sin säkerhet, komfort"
                + " och lättkörda egenskaper, särskilt på vintern.";

        // Textregeln ensam ser dem inte — det är hela skälet till att vakten finns.
        assertThat(InsightTaxonomy.veteranInnehall(volvo780)).isNull();
        assertThat(InsightTaxonomy.veteranInnehall(volvo240)).isNull();
        assertThat(InsightTaxonomy.veteranInnehall(audi100)).isNull();

        assertThat(InsightTaxonomy.veteranInnehall(volvo780, "Volvo", "780")).contains("1990");
        assertThat(InsightTaxonomy.veteranInnehall(volvo240, "Volvo", "240")).contains("1993");
        assertThat(InsightTaxonomy.veteranInnehall(audi100, "Audi", "100 5E")).contains("1994");
    }

    @Test
    void fordOrionArUtgangenSomFordSierra() {
        // Natten mot 2026-09-25: en CarUp-artikel om en övergiven bilhandlare gav fyra
        // Ford-rader ur samma stycke. Ford Sierra (1993) fångades av UTGANGNA_MODELLER och
        // ströks, men Ford Orion — som byggdes på samma Escort-plattform och försvann samma
        // år, 1993, när namnet gick upp i Escort-serien — saknades i listan och stod kvar
        // som familjebil. Ingen levande "Orion" säljs i dag, och 1993 ligger med samma
        // marginal till 30-årsgränsen som Sierra redan har.
        assertThat(InsightTaxonomy.UTGANGNA_MODELLER.get("ford sierra")).isEqualTo(1993);
        assertThat(InsightTaxonomy.utgangenModell("Ford", "Orion")).contains("1993");
    }

    @Test
    void levandeModellerRorsInteAvModellregeln() {
        // Urvalsregelns första krav: ingen levande namne. Renault 4 och Mini är de farliga
        // fallen — originalen är stendöda men båda namnen säljs som nybil i dag, så de står
        // medvetet UTANFÖR tabellen. Slinker de in blir varje nybilsråd om dem osynligt.
        assertThat(InsightTaxonomy.utgangenModell("Renault", "4 E-Tech")).isNull();
        assertThat(InsightTaxonomy.utgangenModell("Mini", "Cooper SE")).isNull();
        assertThat(InsightTaxonomy.utgangenModell("Volvo", "XC60")).isNull();
        assertThat(InsightTaxonomy.utgangenModell("Volvo", "V70")).isNull();
        assertThat(InsightTaxonomy.utgangenModell("Audi", "A6")).isNull();
        // Märke utan modell är inget bevis — namnet är det enda regeln har att gå på.
        assertThat(InsightTaxonomy.utgangenModell("Volvo", null)).isNull();
        assertThat(InsightTaxonomy.utgangenModell("Volvo", "  ")).isNull();
        assertThat(InsightTaxonomy.utgangenModell(null, null)).isNull();
    }

    @Test
    void modellensVeteranstatusRullarOcksaMedKalendern() {
        // Volvo 940 upphörde 1998 och ligger med i tabellen REDAN NU, men får inte bita
        // förrän den fyllt VETERANALDER_AR. Provet räknar gränsen själv, så det följer med
        // över årsskiftet 2027/2028 i stället för att bli rött — samma princip som textregeln.
        int gransar = java.time.Year.now().getValue() - InsightTaxonomy.VETERANALDER_AR;
        int sista940 = InsightTaxonomy.UTGANGNA_MODELLER.get("volvo 940");
        assertThat(sista940).isEqualTo(1998);

        if (sista940 > gransar) {
            assertThat(InsightTaxonomy.utgangenModell("Volvo", "940")).isNull();
        } else {
            assertThat(InsightTaxonomy.utgangenModell("Volvo", "940")).contains("1998");
        }
        // Volvo 240 (1993) ligger före gränsen och ska bita i dag.
        assertThat(InsightTaxonomy.UTGANGNA_MODELLER.get("volvo 240")).isLessThanOrEqualTo(gransar);
    }

    @Test
    void varjeMarkorBarMarketOchEttRimligtArtal() {
        // Två regler som skyddar tabellen mot framtida tillägg:
        // 1) markören måste innehålla ett mellanslag, alltså märke + modell. En lös "240"
        //    hade träffat delsträngar överallt — samma läxa som lyxvaktens ls/xj gav.
        // 2) årtalet måste vara ett verkligt tillverkningsår, inte en platshållare.
        assertThat(InsightTaxonomy.UTGANGNA_MODELLER).isNotEmpty();
        InsightTaxonomy.UTGANGNA_MODELLER.forEach((markor, sistaAr) -> {
            assertThat(markor)
                    .as("markören \"%s\" måste bära märket, annars matchar den delsträngar", markor)
                    .contains(" ");
            assertThat(markor).isLowerCase();
            assertThat(sistaAr)
                    .as("orimligt tillverkningsår för \"%s\"", markor)
                    .isBetween(1900, java.time.Year.now().getValue());
        });
    }
}
