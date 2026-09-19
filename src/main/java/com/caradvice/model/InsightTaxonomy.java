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
 */
public final class InsightTaxonomy {

    private InsightTaxonomy() {}

    /** Exakt formulärets kategorivärden (ca-category i wordpress-snippet.html / test.html). */
    public static final Set<String> CATEGORIES =
            Set.of("familjebil", "suv", "elbil", "laddhybrid", "smaabil");

    /** Insiktens {@code fuel_type}. OBS: drivmedelsrutan postar {@code el}, inte {@code elbil} —
     *  ett {@code elbil}-värde matchar bara när rutan göms och kategorin får agera drivmedel. */
    public static final Set<String> FUEL_TYPES =
            Set.of("elbil", "bensin", "diesel", "hybrid", "laddhybrid");

    private static final Map<String, String> CATEGORY_ALIASES =
            Map.of("småbil", "smaabil", "smabil", "smaabil", "ekonomibil", "smaabil");

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
     */
    public static final List<String> STORA_MODELLER = List.of(
            "model 3", "model s", "model x", "model y", "id.7", "polestar 2", "polestar 3",
            "polestar 4", "saab 9-3", "saab 9-5", "cadillac bls", "jogger", "duster",
            "yaris cross", "passat", "octavia", "superb", "insignia", "mondeo");

    /**
     * Kategorin som bilens egen modell motsäger — felets text för loggen, annars {@code null}.
     *
     * <p>Två håll, inte ett: en {@code suv}-rad om en låg bil och en {@code smaabil}-rad om en
     * stor. En vakt som bara ser åt ena hållet är halv, och båda felen fanns i tabellen samtidigt
     * (10 respektive 9 rader den 2026-09-19).
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
