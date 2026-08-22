package com.caradvice.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rullande mätning av Groqs {@code usage} per anrop, i minnet.
 *
 * <p><b>Varför den finns.</b> Minuttaket hos Groq är 8 000 tokens per modell, och en förfrågan
 * mäts som {@code prompt + reserverade max_tokens} — inte som svarets längd. Med 3 000
 * reserverade räcker en prompt på 4 700 för att fylla nästan hela budgeten, och då ryms ett
 * enda anrop per minut. Varje tusen tokens som kortas bort ur prompten är en sökning till i
 * samma minut, men <b>utan mätning går det inte att veta vad som ska kortas</b>.
 *
 * <p>Talen låg tidigare bara i 413-felen, alltså först när taket redan sprängts. Ett mätvärde
 * som bara syns vid haveri kan inte styra en optimering — samma lärdom som cargo-täckningen
 * gav när parsern var död bakom 602/602/0.
 *
 * <p><b>Ingen databas.</b> Det här är underlag för tuning, inte historik: siffrorna är
 * intressanta i timmar, inte i månader, och en tabell till hade kostat mer än den gav. En
 * omstart nollställer, och det är i sin ordning — mätningen görs om på minuter.
 */
public class TokenUsageStats {

    /** Så många anrop sparas i detalj. Räcker för att se ett mönster utan att växa fritt. */
    static final int SENASTE_MAX = 25;

    private record Anrop(String modell, int prompt, int svar, int reserverat, long tid) {}

    private record Summa(long anrop, long promptSumma, int promptMax, long svarSumma, int svarMax) {
        Summa plus(int prompt, int svar) {
            return new Summa(anrop + 1, promptSumma + prompt, Math.max(promptMax, prompt),
                    svarSumma + svar, Math.max(svarMax, svar));
        }
        static Summa forsta(int prompt, int svar) {
            return new Summa(1, prompt, prompt, svar, svar);
        }
    }

    private final Deque<Anrop> senaste = new ArrayDeque<>();
    private final Map<String, Summa> perModell = new TreeMap<>();

    public synchronized void registrera(String modell, int prompt, int svar, int reserverat) {
        senaste.addLast(new Anrop(modell, prompt, svar, reserverat, System.currentTimeMillis()));
        while (senaste.size() > SENASTE_MAX) senaste.removeFirst();
        perModell.merge(modell, Summa.forsta(prompt, svar), (gammal, ny) -> gammal.plus(prompt, svar));
    }

    /**
     * Rapporten. {@code motMinuttaket} är det tal som faktiskt avgör hur många anrop som ryms:
     * prompt plus reserverade tokens, inte prompt plus svar.
     */
    public synchronized Map<String, Object> rapport() {
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("minuttakPerModell", 8000);
        ut.put("matPunkter", senaste.size());

        List<Map<String, Object>> modeller = perModell.entrySet().stream().map(e -> {
            Summa s = e.getValue();
            Map<String, Object> rad = new LinkedHashMap<>();
            rad.put("modell", e.getKey());
            rad.put("anrop", s.anrop());
            rad.put("promptSnitt", s.anrop() == 0 ? 0 : s.promptSumma() / s.anrop());
            rad.put("promptMax", s.promptMax());
            rad.put("svarSnitt", s.anrop() == 0 ? 0 : s.svarSumma() / s.anrop());
            rad.put("svarMax", s.svarMax());
            return rad;
        }).toList();
        ut.put("perModell", modeller);

        List<Map<String, Object>> rader = senaste.stream().map(a -> {
            Map<String, Object> rad = new LinkedHashMap<>();
            rad.put("modell", a.modell());
            rad.put("prompt", a.prompt());
            rad.put("svar", a.svar());
            rad.put("reserverat", a.reserverat());
            rad.put("motMinuttaket", a.prompt() + a.reserverat());
            rad.put("sekunderSedan", (System.currentTimeMillis() - a.tid()) / 1000);
            return rad;
        }).toList();
        ut.put("senaste", rader);

        int varsta = senaste.stream().mapToInt(a -> a.prompt() + a.reserverat()).max().orElse(0);
        ut.put("varstaMotMinuttaket", varsta);
        ut.put("anropSomRymsPerMinut", varsta == 0 ? null : 8000 / Math.max(1, varsta));
        ut.put("notis", "motMinuttaket = prompt + reserverade max_tokens. Groq mäter SÅ, inte "
                + "prompt + svar — därför kortar man prompten, inte taket.");
        return ut;
    }
}
