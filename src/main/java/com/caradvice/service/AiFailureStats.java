package com.caradvice.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rullande minnesbuffert över AI-svar som inte gick att tolka.
 *
 * <p><b>Varför den finns.</b> "AI-svaret blev ofullständigt" loggade fram till 2026-09-08 bara
 * svarets LÄNGD och Jacksons felmening — inte vad modellen faktiskt skickade, och inte vilken
 * modell det var. Ett fel som inte bär med sig vad det såg går inte att lösa i efterhand; det
 * kan bara gissas om. Loggraden är nu utökad, men en logg hjälper bara den som kan läsa
 * värdens logg. Den här bufferten gör samma fakta läsbara över {@code GET /api/admin/ai-failures}.
 *
 * <p><b>Modellnamnet är hela poängen.</b> Sedan kedjan fick en fjärde modell räcker det inte
 * att veta ATT ett svar var otolkbart — frågan är alltid vilken modell som skrev det, och om
 * felen samlas hos en av dem eller ligger jämnt.
 *
 * <p><b>Ingen databas</b>, av samma skäl som {@link TokenUsageStats}: det här är underlag för
 * felsökning i timmar, inte historik i månader. En omstart nollställer, och det är i sin
 * ordning — men se då upp för fällan att läsa en tom rapport som "inga fel".
 */
public class AiFailureStats {

    /** Så många misslyckanden sparas i detalj. Nog för ett mönster, inte nog för att växa fritt. */
    static final int SENASTE_MAX = 20;

    /** Råsvaret kapas — ett helt svar är flera kilobyte och bufferten ska rymmas i minnet. */
    static final int RASVAR_MAX_TECKEN = 1200;

    private record Fel(String etikett, String modell, String finishReason, String orsak,
                       int langd, String rasvar, long tid) {}

    private final Deque<Fel> senaste = new ArrayDeque<>();
    private final Map<String, Long> perModell = new TreeMap<>();

    public synchronized void registrera(String etikett, String modell, String finishReason,
                                        String orsak, String rasvar) {
        String innehall = rasvar == null ? "" : rasvar;
        String kapat = innehall.length() > RASVAR_MAX_TECKEN
                ? innehall.substring(0, RASVAR_MAX_TECKEN) + "…[kapad]" : innehall;
        senaste.addLast(new Fel(etikett, modell, finishReason, orsak, innehall.length(), kapat,
                System.currentTimeMillis()));
        while (senaste.size() > SENASTE_MAX) senaste.removeFirst();
        perModell.merge(modell, 1L, Long::sum);
    }

    public synchronized Map<String, Object> rapport() {
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("antal", senaste.size());
        // Tom rapport betyder "inget sedan omstarten", ALDRIG "inga fel" — startades tjänsten
        // nyss finns det inget att se ännu. Samma fälla som token-usage har.
        ut.put("notis", "Rullande i minnet, nollställs vid omstart. Tomt = inget sedan starten,"
                + " inte 'inga fel'. Se uptimeSeconds i /api/version.");
        ut.put("perModell", perModell);
        long nu = System.currentTimeMillis();
        List<Map<String, Object>> rader = senaste.stream().map(f -> {
            Map<String, Object> rad = new LinkedHashMap<>();
            rad.put("etikett", f.etikett());
            rad.put("modell", f.modell());
            rad.put("finishReason", f.finishReason());
            rad.put("orsak", f.orsak());
            rad.put("langd", f.langd());
            rad.put("sekunderSedan", (nu - f.tid()) / 1000);
            rad.put("rasvar", f.rasvar());
            return rad;
        }).toList();
        ut.put("senaste", rader);
        return ut;
    }
}
