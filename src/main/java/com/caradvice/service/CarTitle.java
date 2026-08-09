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

    /**
     * Årtal var som helst i titeln, inte bara sist. Samma 2010-gräns som ovan: ett bart årtal
     * måste vara 2010 eller senare, annars äts Peugeot 2008 och BMW 2002 som årsmodeller.
     */
    private static final Pattern YEAR_ANYWHERE =
            Pattern.compile("\\(((?:19|20)\\d{2})\\+?\\)|\\b(20(?:1\\d|2\\d|3\\d))\\+?\\b");

    /**
     * Teknikuppgifter som hör hemma i specfälten, inte i titeln: "60 kWh", "1.3 kWh", "204 hk",
     * "150 kW". Live 2026-08-09 kom "Nissan Leaf 2019 60 kWh" och "Renault Zoe 2019 1.3 kWh" —
     * den senare dessutom med en påhittad batteristorlek (Zoe har 41 eller 52 kWh, siffran ser
     * ut att vara förbrukning i kWh/mil på fel plats).
     */
    private static final Pattern SPEC_JUNK =
            Pattern.compile("\\b\\d+(?:[.,]\\d+)?\\s*(kwh|kw|hk|hp)\\b", Pattern.CASE_INSENSITIVE);

    private CarTitle() {}

    /**
     * AI-titeln i den form resten av koden räknar med: "Märke Modell (år)".
     *
     * <p>Prompten ber om den formen men får den inte alltid. "Nissan Leaf 2019 60 kWh" har
     * årtalet i MITTEN, och då returnerar {@link #year(String)} null eftersom dess mönster är
     * ankrat i slutet av strängen — allt som hänger på årsmodellen slutar tyst att fungera:
     * Blockets årsfilter, dedupen, nyprisuppslaget, leasingmatchningen och årsmodellvakten.
     * Att laga varje konsument hade blivit fem halva lösningar, så titeln städas i stället en
     * gång, där AI-svaret tolkas.
     *
     * <p>Bara årtal och teknikuppgifter rörs. Trimnamn måste överleva: "Kia EV4 Long Range"
     * och "MG4 Extended Range" är modellnamn, inte brus.
     */
    public static String normalize(String title) {
        if (title == null) return null;
        String utan = SPEC_JUNK.matcher(title).replaceAll(" ");
        Matcher m = YEAR_ANYWHERE.matcher(utan);
        String year = null;
        if (m.find()) year = m.group(1) != null ? m.group(1) : m.group(2);
        String namn = m.reset().replaceAll(" ")
                .replaceAll("[\\s,;–—-]+$", "")   // skiljetecken som blir hängande när årtalet lyfts ut
                .replaceAll("\\s+", " ")
                .trim();
        if (namn.isEmpty()) return title.trim();   // bara årtal kvar — lämna orörd hellre än tom
        return year == null ? namn : namn + " (" + year + ")";
    }

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
