package com.caradvice.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rullande minnesbuffert över kategorier som vakten strukit.
 *
 * <p><b>Varför den finns.</b> Kategorivakten (2026-09-19) stryker en kategori som bilens egen
 * modell motsäger — {@code smaabil} på en Tesla Model 3, {@code suv} på en Kia Niro. Utslaget
 * loggades, och loggen ligger hos Render: det finns ingen {@code RENDER_API_KEY} i miljön och
 * ingen av de två nattrutinerna når dashboarden. En vakt vars utfall bara går att läsa där den
 * inte går att läsa är i praktiken tyst, och en tyst vakt kan inte skiljas från en vakt som
 * aldrig fäller. Samma skäl som {@link AiFailureStats} byggdes av.
 *
 * <p><b>Modellnamnet är hela poängen.</b> Att en kategori ströks säger ingenting i sig — frågan
 * är alltid VILKEN bil, för det är namnet som avgör om listan träffade rätt (vakten gjorde sitt
 * jobb) eller för brett (listan behöver rättas). Därför bärs {@code bil} och {@code motsagelse}
 * med, och {@code perBil} visar om utslagen samlas hos en enda modell.
 *
 * <p><b>{@code totalt} kapas aldrig, {@code iBufferten} gör det.</b> En kapad buffert slutar
 * röra sig när taket är nått, och ett tal som inte kan röra sig kan inte larma — precis den
 * fällan som lät cargo-täckningen stå på 602/602/0 medan parsern var död. Räknaren står därför
 * bredvid bufferten, inte i stället för den.
 *
 * <p><b>Ingen databas</b>, som {@link AiFailureStats} och {@code TokenUsageStats}: det här är
 * underlag för felsökning i timmar, inte historik i månader. En omstart nollställer, och det är
 * i sin ordning — men se då upp för fällan att läsa en tom rapport som "vakten fäller aldrig".
 */
@Component
public class KategoriVaktStats {

    /** Så många utslag sparas i detalj. Nog för ett mönster, inte nog för att växa fritt. */
    static final int SENASTE_MAX = 30;

    /** Insikten kapas — bufferten ska rymmas i minnet, och början räcker för att känna igen raden. */
    static final int INSIKT_MAX_TECKEN = 160;

    private record Utslag(String kalla, String kategori, String bil, String motsagelse,
                          String insikt, long tid) {}

    private final Deque<Utslag> senaste = new ArrayDeque<>();
    private final Map<String, Long> perBil = new TreeMap<>();
    private final Map<String, Long> perKalla = new TreeMap<>();
    private long totalt;

    /**
     * @param kalla       var raden skrevs ifrån ("web-insights", "CSV-import [Vi Bilägare]")
     * @param kategori    kategorin som ströks, i kanonisk form
     * @param bil         märke + modell, som vakten läste dem
     * @param motsagelse  vaktens egen motivering, samma text som loggraden bär
     * @param insikt      radens text — den sparas ändå, och utdraget gör den igenkännbar
     */
    public synchronized void registrera(String kalla, String kategori, String bil,
                                        String motsagelse, String insikt) {
        String text = insikt == null ? "" : insikt;
        String kapat = text.length() > INSIKT_MAX_TECKEN
                ? text.substring(0, INSIKT_MAX_TECKEN) + "…[kapad]" : text;
        senaste.addLast(new Utslag(kalla, kategori, bil, motsagelse, kapat, System.currentTimeMillis()));
        while (senaste.size() > SENASTE_MAX) senaste.removeFirst();
        totalt++;
        if (bil != null) perBil.merge(bil, 1L, Long::sum);
        if (kalla != null) perKalla.merge(kalla, 1L, Long::sum);
    }

    public synchronized Map<String, Object> rapport() {
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("totalt", totalt);
        ut.put("iBufferten", senaste.size());
        // Tom rapport betyder "inget sedan omstarten", ALDRIG "vakten fäller aldrig" — startades
        // tjänsten nyss finns det inget att se ännu. Samma fälla som ai-failures har.
        ut.put("notis", "Rullande i minnet, nollställs vid omstart. Tomt = inget sedan starten,"
                + " inte 'vakten faller aldrig'. Se uptimeSeconds i /api/version."
                + " totalt kapas aldrig, iBufferten kapas vid " + SENASTE_MAX + ".");
        ut.put("perBil", perBil);
        ut.put("perKalla", perKalla);
        long nu = System.currentTimeMillis();
        List<Map<String, Object>> rader = senaste.stream().map(u -> {
            Map<String, Object> rad = new LinkedHashMap<>();
            rad.put("kalla", u.kalla());
            rad.put("kategori", u.kategori());
            rad.put("bil", u.bil());
            rad.put("motsagelse", u.motsagelse());
            rad.put("insikt", u.insikt());
            rad.put("sekunderSedan", (nu - u.tid()) / 1000);
            return rad;
        }).toList();
        ut.put("senaste", rader);
        return ut;
    }

    /** Bara för tester — bufferten delas av flera skrivvägar och ska inte läcka mellan prov. */
    public synchronized void nollstall() {
        senaste.clear();
        perBil.clear();
        perKalla.clear();
        totalt = 0;
    }
}
