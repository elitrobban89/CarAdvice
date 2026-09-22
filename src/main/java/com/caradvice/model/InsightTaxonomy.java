package com.caradvice.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kategorierna en expertinsikt får bära — en enda lista, delad av alla skrivvägar.
 *
 * <p><b>Varför den finns.</b> {@code buildExpertContext} matchar insiktens {@code category} mot
 * det värde formuläret postar, med likhet. En kategori utanför rullgardinen når därför aldrig en
 * rekommendationsprompt: raden är inte fel, den är osynlig. Skrapan har haft en whitelist sedan
 * 2026-08-10, men de två andra skrivvägarna (CSV-importen och admin-PATCH) hade ingen, och
 * 2026-08-29 låg tre sådana rader kvar i drift — två {@code crossover} (Dacia Striker) och en
 * {@code transportbil} — utan att något larmat. Listan bodde dessutom inne i skrapan, så de nya
 * vakterna hade blivit en fjärde kopia att glömma bort.
 *
 * <p><b>Listan MÅSTE spegla formulärets {@code <select>} exakt.</b> Läggs en kategori till i
 * gränssnittet ska den läggas till här — annars kastas den bort på vägen in.
 *
 * <p><b>Aliasen är stavningar av kategorier som finns</b>, inte översättningar av kategorier som
 * saknas: {@code småbil} och {@code ekonomibil} är samma hylla som {@code smaabil}
 * ({@link CarPreferences#canonicalCategory()} gör samma sak åt sökvägen), medan {@code crossover}
 * och {@code sportbil} medvetet INTE mappas vidare — att tysta döpa om en sportbil till småbil
 * vore att ljuga i datan för att komma runt en saknad knapp.
 *
 * @author Robert Andersson Kopler
 */
public final class InsightTaxonomy {

    private InsightTaxonomy() {}

    /** Exakt formulärets kategorivärden (ca-category i wordpress-snippet.html / test.html). */
    public static final Set<String> CATEGORIES =
            Set.of("familjebil", "suv", "elbil", "laddhybrid", "smaabil");

    /** Insiktens {@code fuel_type} — tabellens stavning, som {@link #FUEL_ALIASES} översätter till. */
    public static final Set<String> FUEL_TYPES =
            Set.of("elbil", "bensin", "diesel", "hybrid", "laddhybrid");

    private static final Map<String, String> CATEGORY_ALIASES =
            Map.of("småbil", "smaabil", "smabil", "smaabil", "ekonomibil", "smaabil");

    /**
     * Drivmedelsrutan postar {@code el}, tabellen stavar det {@code elbil} — samma drivmedel,
     * två stavningar, och {@code buildExpertContext} jämför med likhet.
     *
     * <p><b>Vad det kostade.</b> Mätt i drift 2026-09-22: <b>561 av 1 162 rader</b> bär
     * {@code fuel_type = elbil} och <b>en enda</b> bar {@code el} (id 1056, rättad samma dag).
     * En sökning där användaren uttryckligen valde <i>El</i> i rullgardinen frågade alltså efter
     * den enda raden och missade de 561 — elbilsinsikterna nådde prompten bara via sin
     * {@code category}, och bara när användaren råkat välja samma kategori som raden bar.
     * Kategorifältet har haft både whitelist och alias sedan 2026-08-10; drivmedelsfältet hade
     * whitelist bara i skrapan och alias ingenstans.
     *
     * <p><b>Aliaset är en stavning, ingen översättning</b> — samma gräns som
     * {@link #CATEGORY_ALIASES}. {@code mildhybrid} och {@code etanol} står därför INTE här:
     * de är egna drivlinor som formuläret inte har någon knapp för, och att mappa dem till
     * {@code hybrid} respektive {@code bensin} vore att skriva om datan för att komma runt
     * en saknad knapp. De faller i stället på whitelisten och sparas utan drivmedel.
     */
    private static final Map<String, String> FUEL_ALIASES = Map.of("el", "elbil");

    /**
     * Drivmedlet i tabellens stavning. Okända värden släpps igenom oförändrade — det här är
     * LÄSvägens översättning, och en sökning ska inte tystna för att formuläret fått ett nytt
     * värde innan den här listan hunnit med. Skrivvägarna använder {@link #validFuel} i stället.
     */
    public static String canonicalFuel(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        return FUEL_ALIASES.getOrDefault(v, v);
    }

    /** Som {@link #canonicalFuel}, men {@code null} när värdet inte är ett drivmedel vi känner. */
    public static String validFuel(String raw) {
        String v = canonicalFuel(raw);
        return (v != null && FUEL_TYPES.contains(v)) ? v : null;
    }

    /** Som {@link #isUnknownCategory}: skiljer "tomt" (tillåtet) från "påhittat" (avvisas). */
    public static boolean isUnknownFuel(String raw) {
        return raw != null && !raw.isBlank() && validFuel(raw) == null;
    }

    /** Felmeddelandet som når admin — listar vad som faktiskt går att välja. */
    public static String fuelError(String raw) {
        return "Okänt drivmedel: " + raw + " (tillåtna: "
                + String.join(", ", new java.util.TreeSet<>(FUEL_TYPES)) + ", eller tomt)";
    }

    /**
     * Modeller som bevisligen INTE är SUV:ar — halvkombier, sedaner, kombier och låga crossovers.
     *
     * <p>Listan bodde i {@code GroqService.NON_SUV_MARKERS} och vaktade bara REKOMMENDATIONERNA:
     * ett SUV-sök som fick MG4, Kia Niro och Hyundai Kona tillbaka 2026-08-22. Samma modellnamn
     * behövs på skrivvägen — insiktstabellen hade 2026-09-19 tio rader med {@code category = suv}
     * på just de här bilarna (tre Kia Niro, fem Hyundai Kona, en Polestar 2 och en VW Golf
     * Alltrack). En kopia till hade blivit en till att glömma uppdatera, så listan flyttade
     * hit och GroqService pekar på den.
     *
     * <p>Niro och Kona står med efter användarens uttryckliga besked: de marknadsförs som SUV men
     * är inte de höga bilar kategorin lovar. Gränsen går vid XC40/Kamiq-höjd och uppåt.
     */
    public static final List<String> LAGA_MODELLER = List.of(
            "mg4", "mg 4", "mg5", "mg 5", "id.3", "id3", "model 3", "polestar 2",
            "zoe", "leaf", "e-golf", "golf", "ioniq 6", "i4", "ë-c4", "e-c4",
            "niro", "kona", "corsa", "megane", "id.7", "civic", "octavia", "passat");

    /**
     * Modeller som bevisligen INTE är småbilar — mellanklass och uppåt, plus SUV:ar.
     *
     * <p><b>Mätt i drift 2026-09-19</b> genom att läsa alla 194 rader med {@code smaabil} i
     * insiktstabellen. Nio av dem var mellanklassbilar eller SUV:ar: Saab 9-3 och Cadillac BLS
     * (natten mot 09-19), Polestar 2, Tesla Model 3, VW ID.7, två Dacia Jogger, två Dacia Duster
     * Extreme — den sista med texten <i>"en prisvärd kompakt SUV"</i> i samma rad som kategorin
     * sa småbil. Promptregeln har sagt <i>"ALDRIG SUV:ar eller mellanklassbilar"</i> sedan
     * 2026-08-10 och räckte alltså inte; samma lärdom som växel-, drivmedels- och SUV-vakterna.
     *
     * <p>Bara modeller vi VET är för stora står här, och syskon av exakt samma kaross (Model S/X/Y,
     * Polestar 3/4, Saab 9-5). En okänd modell släpps igenom hellre än att en riktig småbil
     * kastas — vakten fäller på positivt bevis, aldrig på frånvaro.
     *
     * <p><b>VW ID.3 tillkom 2026-09-19 em på användarens beslut</b>, och den är listans enda
     * kompaktbil. Skälet är att ID.3 uttryckligen ströks ur småbilslistan i rekommendations-
     * prompten redan tidigare — den är Golf-klass med fem säten — men den regeln fanns bara på
     * läsvägen, så skrapan kunde fortsätta skriva {@code smaabil} på den. Sex ID.3 GTI-rader
     * (1545, 1552, 1553, 1554, 1556, 1557) och två vanliga ID.3 (850, 989) låg så i drift.
     * <b>Gränsen för listan går alltså inte vid en storleksklass utan vid vad som ska få agera
     * småbil i prompten</b> — Golf-klass och uppåt gör det inte.
     *
     * <p>{@code id3} står med bredvid {@code id.3} eftersom AI:n skriver bägge formerna; samma
     * dubblering finns i {@link #LAGA_MODELLER}.
     */
    public static final List<String> STORA_MODELLER = List.of(
            "model 3", "model s", "model x", "model y", "id.3", "id3", "id.7", "polestar 2",
            "polestar 3", "polestar 4", "saab 9-3", "saab 9-5", "cadillac bls", "jogger",
            "duster", "yaris cross", "passat", "octavia", "superb", "insignia", "mondeo");

    /**
     * Lyxbilar och sportbilar — modeller som ALDRIG får bära {@code smaabil} eller {@code familjebil}.
     *
     * <p>Regeln har stått i skrapans prompt sedan 2026-08-18 ("en sportbil eller lyxbil är ALDRIG
     * smaabil/familjebil") och rättades då för hand: id 1234 (Golf GTI) och 1248 (RS5 Avant) fick
     * tom kategori. Den fortsatte ändå läcka — natten mot 2026-09-19 kom två <i>Audi A8</i>-rader in
     * som {@code familjebil}, och en genomgång av hela tabellen samma dag hittade nio sådana rader:
     * tre A8, tre Tesla Model S, en Mercedes S-Klass, en EQS 450 och en Maserati Grecale Trofeo,
     * plus tre sportbilsrader (två Mazda MX-5 som {@code smaabil}, en BMW M5 som {@code familjebil}).
     * Fjärde promptregeln i rad som behövde kodstöd.
     *
     * <p><b>Varför det gör skada:</b> en lyxbil bland familjebilarna är inte bara fel hylla — den är
     * ett köpråd i en helt annan prisklass än den användaren sökte i.
     *
     * <p><b>Listan är medvetet SMAL och innehåller bara namngivna modeller.</b> Märkesbreda
     * effektbeteckningar som {@code amg} och {@code rs} hade tagit med sig vanliga bilar av samma
     * familj, och varmhalvkombierna (GTI, GR Yaris, Cooper S, Cupra VZ) är en egen gränsdragning
     * som användaren äger — mätningen 09-19 visade dessutom att markören {@code cooper s} fångar
     * eldrivna <i>Mini Cooper SE</i>, som inte är någon sportbil alls.
     *
     * <p><b>Två rader står kvar som {@code familjebil} med flit och får INTE in i listan:</b>
     * id 1239 (Polestar 5) och id 1246 (Audi A6) — de är användarens egna gränsfall.
     *
     * <p><b>{@code jaguar xj} och {@code lexus ls} tillkom 2026-09-22.</b> En körning av vakten
     * mot alla 1 162 rader i drift fällde <b>noll</b> — listorna var alltså ifatt sin egen tabell —
     * men fem rader stod kvar som {@code familjebil} på bilar i exakt det segment listan redan
     * täcker: två <i>Jaguar XJ8</i> (1613, 1615) och tre <i>Lexus LS 460</i> (1359–1361). Samma
     * hylla som A8, S-Klass, 7-serie, Panamera och Quattroporte, bara utan markör. En av dem
     * (1615) var dessutom ett prisråd — <i>"säljs för 50 000–60 000 kr … ett fynd för en klassisk
     * lyxbil"</i> — mitt i familjebilarna. Båda markörerna bär märkesnamnet med sig, eftersom
     * {@code ls} och {@code xj} som lösa ord hade träffat delsträngar i andra modellnamn; hela
     * tabellen gav noll andra träffar på dem.
     *
     * <p><b>Fyra rader om elektriska <i>Mercedes-AMG CLA 45</i> (875–878) och en om
     * <i>BMW M350 xDrive</i> (1453) lämnades avsiktligt utanför.</b> De är prestandaversioner av
     * en kompakt- respektive mellanklassbil, inte flaggskepp, och gränsen för varmhalvkombierna
     * är användarens egen (samma skäl som GTI, GR Yaris, Cooper S och Cupra VZ står utanför).
     */
    public static final List<String> LYX_OCH_SPORTMODELLER = List.of(
            // Lyxflaggskepp
            "a8", "s8", "s-klass", "s klass", "eqs", "7-serie", "7 serie", "i7", "model s",
            "panamera", "taycan", "quattroporte", "levante", "maserati", "bentley", "rolls-royce",
            "ghost", "phantom", "cullinan", "escalade", "jaguar xj", "lexus ls",
            // Sportbilar
            "911", "718", "cayman", "boxster", "corvette", "emira", "supra", "gt-r", "f-type",
            "amg gt", "m4", "m5", "m8", "rs4", "rs5", "rs6", "rs7", "mx-5", "brz", "gr86",
            "ferrari", "lamborghini", "mclaren");

    /**
     * Så gammal ska en bil vara för att räknas som veteran: Transportstyrelsens egen gräns.
     *
     * <p><b>Rullande med flit.</b> Ett fast årtal hade åldrats som varje annan hårdkodad
     * baslinje i det här projektet; 30 år är definitionen som gäller i Sverige (skattebefrielse
     * och besiktning vartannat år), och den flyttar sig ett steg varje nyår av sig själv.
     */
    public static final int VETERANALDER_AR = 30;

    /**
     * Ord som ensamma bevisar att texten handlar om ett samlar- eller auktionsobjekt.
     *
     * <p>Listan är inventerad ur de 1 159 raderna i drift 2026-09-20, inte gissad: den träffar
     * fyra rader som årsregeln missar (VW Bubbla som "unikt samlarobjekt", Volvo 240 "gömd i
     * 44 år", Volvo 240 GL såld efter budkrig, Volvo V70 såld på auktion) och noll rader som
     * hör hemma i ett köpråd. Prövad mot repots fyra kurerade CSV:er (240 rader): noll träffar,
     * samma utfall som kategorivakten fick.
     *
     * <p><b>Medvetet UTELÄMNADE:</b> {@code klassisk} (sex rader, de flesta om bilar man
     * faktiskt kan köpa), {@code välbevarad} och {@code stillestånd} — alla tre är vanliga ord
     * i en begagnattext, och de rader som verkligen var veteranbilar fälls redan av årtalet.
     * Ett för brett filter tystar riktiga köpråd, precis som {@code LYX_OCH_SPORTMODELLER}
     * lärde när {@code amg} och {@code rs} prövades som markörer.
     */
    private static final java.util.regex.Pattern SAMLARORD = java.util.regex.Pattern.compile(
            "samlarobjekt|samlarbil|veteranbil|veteranfordon|renoveringsobjekt|entusiastprojekt"
            + "|budkrig|på auktion|auktionerades|klubbades|gömd i \\d+ år");

    /**
     * Årtalet i en MODELLÅRSPOSITION — "från 1987", "1972 års", "levererades 1977".
     *
     * <p>Positionen är hela poängen. Ett naket fyrsiffrigt tal i texten kan vara vad som helst
     * ("Euro NCAP-testet 2017", "Bilprovningens 2025-statistik", "tillverkats sedan 1974" om en
     * modell som säljs ny i dag), och en regel som läste varje årtal hade fällt dem. Mätt över
     * hela tabellen 2026-09-20: mönstret träffar nio rader, och alla nio handlar om en bil från
     * det årtalet.
     */
    private static final java.util.regex.Pattern MODELLAR = java.util.regex.Pattern.compile(
            "(?:från|årsmodell|modellår|levererades|tillverkad|tillverkades|byggd|byggdes)"
            + "\\s+(?:omkring\\s+|cirka\\s+|ca\\s+)?(\\d{4})|(\\d{4})\\s*års\\b");

    /**
     * Modeller som bevisligen slutade tillverkas, och året de gjorde det.
     *
     * <p><b>Varför den behövs vid sidan av årsregeln.</b> {@link #MODELLAR} kräver ett årtal i
     * modellårsposition och {@link #SAMLARORD} ett samlarord. Tre rader i drift 2026-09-22 bar
     * varken det ena eller det andra och stod därför kvar som köpråd: en Volvo 780 beskriven med
     * motor och nypris, en Volvo 240 om nya däck och bromsar, och en Audi 100 5E om en utmärkelse.
     * Ingen mening i dem avslöjar att bilen är fyrtio år gammal — men <b>modellnamnet gör det</b>.
     *
     * <p><b>Årtalet står i tabellen, inte i koden, och gränsen rullar.</b> Regeln är densamma som
     * för en enskild bil: modellen är veteran när den upphörde för {@link #VETERANALDER_AR} år
     * sedan eller mer. Därför går {@code volvo 940} (1998) och {@code saab 9000} (1998) att ha
     * med redan nu — de biter först 2028, av sig själva, utan att någon behöver minnas dem. Ett
     * hårdkodat "de här är veteraner" hade åldrats exakt som varje annan baslinje i projektet.
     *
     * <p><b>Urvalsregeln — två krav, båda nödvändiga:</b>
     * <ol>
     *   <li><b>Ingen levande namne.</b> {@code renault 4} och {@code mini} är uteslutna trots att
     *       originalen är stendöda: Renault 4 E-Tech kom 2025 och Mini säljs som nybil. En markör
     *       som träffar en bil man kan köpa i dag är värre än ingen markör alls.</li>
     *   <li><b>Marginal till gränsen.</b> {@code audi 80} (1996) utelämnas fastän den precis
     *       kvalificerar — ligger mitt eget årtal ett år fel vänder utfallet. Modeller nära
     *       gränsen får vänta tills de klarar den med marginal.</li>
     * </ol>
     *
     * <p><b>Markören bär alltid MÄRKET</b>, av samma skäl som {@code LYX_OCH_SPORTMODELLER} lärde
     * när {@code ls} och {@code xj} prövades lösa: matchningen är en delsträngsjämförelse, så
     * {@code 240} ensamt hade träffat allt från Mercedes 240D till en effektangivelse.
     *
     * <p><b>Mätt mot hela tabellen (1 162 rader) 2026-09-22 innan den skrevs:</b> sju träffar,
     * <b>noll</b> falska. Fyra av de sju fångade textreglerna redan; modellregelns egna bidrag är
     * exakt de tre rader som beskrivs ovan. Att 24 av markörerna inte träffar något i dag är
     * meningen — vakten finns för raderna som kommer, inte för en städning.
     */
    public static final Map<String, Integer> UTGANGNA_MODELLER = Map.ofEntries(
            Map.entry("volvo pv", 1965),      Map.entry("volvo duett", 1969),
            Map.entry("volvo amazon", 1970),  Map.entry("volvo 140", 1974),
            Map.entry("volvo 164", 1975),     Map.entry("volvo 260", 1985),
            Map.entry("volvo 760", 1990),     Map.entry("volvo 780", 1990),
            Map.entry("volvo 740", 1992),     Map.entry("volvo 240", 1993),
            Map.entry("volvo 960", 1997),     Map.entry("volvo 940", 1998),
            Map.entry("saab sonett", 1974),   Map.entry("saab 96", 1980),
            Map.entry("saab 99", 1984),       Map.entry("saab 9000", 1998),
            Map.entry("audi 100", 1994),
            Map.entry("bmw 2002", 1976),      Map.entry("bmw 1502", 1977),
            Map.entry("opel rekord", 1986),   Map.entry("opel ascona", 1988),
            Map.entry("opel kadett", 1991),
            Map.entry("ford taunus", 1982),   Map.entry("ford cortina", 1982),
            Map.entry("ford sierra", 1993),
            Map.entry("citroen 2cv", 1990),   Map.entry("citroën 2cv", 1990),
            Map.entry("fiat 127", 1983),      Map.entry("trabant 601", 1991),
            Map.entry("mercedes w123", 1986));

    /**
     * Är bilens MODELL utgången sedan {@link #VETERANALDER_AR} år? Motiveringen, annars {@code null}.
     *
     * <p>Den längsta träffande markören vinner — samma regel som radvalet i servicelagret, och
     * den som gör en framtida {@code "volvo 240 gl"} mer specifik än {@code "volvo 240"}. Utan
     * den hade utfallet hängt på {@link Map}-ordningen, som är <b>oordnad</b> i {@code Map.ofEntries}.
     *
     * <p>Saknas modellen görs ingenting: namnet är det enda beviset regeln har, och ett märke
     * utan modell ("Volvo") säger inget om årgången.
     */
    public static String utgangenModell(String carMake, String carModel) {
        if (carModel == null || carModel.isBlank()) return null;
        String namn = ((carMake == null ? "" : carMake) + " " + carModel).toLowerCase(Locale.ROOT).trim();
        int gransar = java.time.Year.now().getValue() - VETERANALDER_AR;
        String bast = null;
        int bastAr = 0;
        for (Map.Entry<String, Integer> e : UTGANGNA_MODELLER.entrySet()) {
            if (e.getValue() > gransar) continue;           // modellen är inte 30 år ännu
            if (!namn.contains(e.getKey())) continue;
            if (bast == null || e.getKey().length() > bast.length()) {
                bast = e.getKey();
                bastAr = e.getValue();
            }
        }
        return bast == null ? null
                : "modellen " + bast + " slutade tillverkas " + bastAr;
    }

    /**
     * Veteranvakten med bilens namn i handen — textreglerna först, modellregeln som fångstnät.
     *
     * <p>Ordningen är inte godtycklig: en text som SÄGER sin årsmodell ger en mer precis
     * motivering ("årsmodell 1977") än modellnamnet kan ge ("modellen audi 100 slutade tillverkas
     * 1994"), och motiveringen är det som hamnar i loggen och i kategorivaktens buffert.
     */
    public static String veteranInnehall(String insight, String carMake, String carModel) {
        String texten = veteranInnehall(insight);
        return texten != null ? texten : utgangenModell(carMake, carModel);
    }

    /**
     * Är insikten en veteran-/samlarbil i stället för ett köpråd? Motiveringen, annars {@code null}.
     *
     * <p><b>Femte promptregeln som behövde kodstöd.</b> Skrapans prompt har uteslutit
     * "renoveringsobjekt … ett entusiastprojekt är ingen köpvägledning" sedan 2026-08-10 och
     * läckte ändå: natten mot 2026-09-20 kom tre rader in på en gång — två Saab 9000 ur en
     * annons där bilen stått stilla i nästan 40 år (24 respektive 61 mil på mätaren) och en
     * fabriksny VW Bubbla från 1964. Nattrapporten kallade det tredje gången på tio dagar.
     * Samma lärdom som kategori-, växel-, drivmedels- och SUV-vakterna gav.
     *
     * <p><b>Raden sparas, men tas ur rekommendationspoolen.</b> Anropssidan tömmer BÅDE
     * {@code category} och {@code fuel_type}, och båda behövs:
     * {@code buildExpertContext} hämtar med {@code findByCategoryIgnoreCaseOrFuelTypeIgnoreCase},
     * så en bensinrad når varje bensinsökning även utan kategori. Texten står kvar på bilkortet
     * och i chatten, som matchar på märke och modell — en veteranbil är sann och intressant men
     * inget köpråd, och användarens linje sedan Fisker Ocean 2026-08-19 är att fakta ska finnas
     * kvar som bilhistoria.
     */
    public static String veteranInnehall(String insight) {
        if (insight == null || insight.isBlank()) return null;
        String text = insight.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher samlare = SAMLARORD.matcher(text);
        if (samlare.find()) return "samlarobjekt (\"" + samlare.group() + "\")";

        int gransar = java.time.Year.now().getValue() - VETERANALDER_AR;
        java.util.regex.Matcher ar = MODELLAR.matcher(text);
        while (ar.find()) {
            String funnet = ar.group(1) != null ? ar.group(1) : ar.group(2);
            int arsmodell = Integer.parseInt(funnet);
            if (arsmodell >= 1900 && arsmodell <= gransar)
                return "årsmodell " + arsmodell + " är " + VETERANALDER_AR + " år eller äldre";
        }
        return null;
    }

    /**
     * Kategorin som bilens egen modell motsäger — felets text för loggen, annars {@code null}.
     *
     * <p>Tre regler, inte en: en {@code suv}-rad om en låg bil, en {@code smaabil}-rad om en stor,
     * och en {@code smaabil}- eller {@code familjebil}-rad om en lyx- eller sportbil. En vakt som
     * bara ser åt ena hållet är halv, och alla tre felen fanns i tabellen samtidigt den 2026-09-19
     * (10, 9 respektive 12 rader).
     *
     * <p><b>Varför det gör skada:</b> {@code buildExpertContext} hämtar rader med
     * {@code findByCategoryIgnoreCaseOrFuelTypeIgnoreCase} — alltså ALLA rader med kategorin,
     * oavsett vilken bil sökningen gäller. En Tesla Model 3-rad med {@code smaabil} kan därför
     * landa i prompten för ett småbilssök och beskriva en bil användaren inte frågat om.
     */
    public static String kategoriMotsagelse(String category, String carMake, String carModel) {
        String kanonisk = canonicalCategory(category);
        if (kanonisk == null || carModel == null || carModel.isBlank()) return null;
        String namn = ((carMake == null ? "" : carMake) + " " + carModel).toLowerCase(Locale.ROOT).trim();
        if ("suv".equals(kanonisk) && traffar(namn, LAGA_MODELLER))
            return namn + " är ingen SUV";
        if ("smaabil".equals(kanonisk) && traffar(namn, STORA_MODELLER))
            return namn + " är ingen småbil";
        if (("smaabil".equals(kanonisk) || "familjebil".equals(kanonisk))
                && traffar(namn, LYX_OCH_SPORTMODELLER))
            return namn + " är en lyx- eller sportbil";
        return null;
    }

    private static boolean traffar(String namn, List<String> modeller) {
        return modeller.stream().anyMatch(namn::contains);
    }

    /**
     * Som {@link #canonicalCategory(String)}, men släpper kategorin när bilen motsäger den.
     *
     * <p><b>Kategorin stryks, en ny skrivs ALDRIG dit.</b> Att {@code LAGA_MODELLER} bevisar att
     * en bil inte är SUV säger ingenting om vad den är i stället, och att gissa hade varit att
     * byta AI:ns gissning mot vår egen — samma regel som växelvakten. Raden sparas med sina fakta
     * i behåll: bilkortet matchar på märke och modell, inte på kategori, så den syns där som förut.
     */
    public static String canonicalCategory(String raw, String carMake, String carModel) {
        String kanonisk = canonicalCategory(raw);
        return kategoriMotsagelse(kanonisk, carMake, carModel) == null ? kanonisk : null;
    }

    /**
     * Kategorin i kanonisk form, eller {@code null} om värdet inte finns i formuläret.
     * Tomt och {@code null} in ger {@code null} ut — en kategorilös rad är tillåten, den når
     * prompten via {@code fuel_type} i stället.
     */
    public static String canonicalCategory(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        v = CATEGORY_ALIASES.getOrDefault(v, v);
        return CATEGORIES.contains(v) ? v : null;
    }

    /** Som {@link #canonicalCategory}, men skiljer "tomt" (tillåtet) från "påhittat" (avvisas). */
    public static boolean isUnknownCategory(String raw) {
        return raw != null && !raw.isBlank() && canonicalCategory(raw) == null;
    }

    /** Felmeddelandet som når admin — listar vad som faktiskt går att välja. */
    public static String categoryError(String raw) {
        return "Okänd kategori: " + raw + " (tillåtna: "
                + String.join(", ", new java.util.TreeSet<>(CATEGORIES)) + ", eller tomt)";
    }
}
