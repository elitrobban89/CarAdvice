package com.caradvice.model;

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
