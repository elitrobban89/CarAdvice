package com.caradvice.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Årsmodellen i en AI-titel — med eller utan parentes.
 *
 * <p>Prompten ber om "Märke Modell (år)", och koden litade på det: sexton parsers i åtta
 * klasser matchade bara den parentesformen. AI:n skriver ibland "Volkswagen ID.4 2026" i
 * stället, och då föll allt som hänger på årtalet samtidigt — Blockets årsfilter, dedupen,
 * nyprisuppslaget och leasingmatchningen. Live 2026-08-07 fick en ID.4 hela modellens
 * annonsspann (3 995–7 961 kr/mån) och missade sitt officiella leasingpris.
 *
 * <p>Ett bart årtal måste vara 2010 eller senare. Annars äter regeln modellnamn: Peugeot 2008
 * och BMW 2002 är bilar, inte årsmodeller. Peugeot 3008 och 5008 skyddas redan av att årtal
 * börjar på 19 eller 20.
 */
public final class CarTitle {

    private static final Pattern YEAR_SUFFIX =
            Pattern.compile("\\s*(?:\\((19|20)(\\d{2})\\+?\\)|\\b(20)(1\\d|2\\d|3\\d)\\+?)\\s*$");

    private CarTitle() {}

    /** Titeln utan avslutande årsmodell: "Volkswagen ID.4 2026" och "(2026)" → "Volkswagen ID.4". */
    public static String stripYear(String title) {
        if (title == null) return null;
        return YEAR_SUFFIX.matcher(title.trim()).replaceAll("").trim();
    }

    /** Årsmodellen, eller null när titeln saknar en. */
    public static Integer year(String title) {
        if (title == null) return null;
        Matcher m = YEAR_SUFFIX.matcher(title.trim());
        if (!m.find()) return null;
        String year = m.group(1) != null ? m.group(1) + m.group(2) : m.group(3) + m.group(4);
        try {
            return Integer.parseInt(year);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
