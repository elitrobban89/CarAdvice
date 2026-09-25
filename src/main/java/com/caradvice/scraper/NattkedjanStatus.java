package com.caradvice.scraper;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tre tidsstämplar som låter nattgranskningen startas av en signal UTAN att tystnad kan se ut
 * som hälsa: när kedjan blev klar, hur signalen till GitHub gick, och när granskningen kvitterade.
 *
 * <p><b>Varför kvittot.</b> Granskningsrutinen startas av signalen, och den fasta körningen senare
 * på natten är reserv. Reserven måste veta om granskningen redan är gjord — annars blir det två
 * rapporter varje natt, eller ingen alls de nätter signalen gick fram men rutinen aldrig startade.
 * Signalens utfall räcker inte som bevis: en skickad signal är inte en körd granskning. Bara
 * granskningens egen kvittens är det.
 *
 * <p>Allt ligger i minnet. En omstart nollställer det, och det är rätt håll att fela åt: utan
 * kvitto kör reserven hela granskningen i stället för att avstå.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class NattkedjanStatus {

    private final AtomicReference<Instant> klar = new AtomicReference<>();
    private final AtomicReference<Instant> signalTid = new AtomicReference<>();
    private final AtomicReference<String> signalUtfall = new AtomicReference<>();
    private final AtomicReference<Instant> granskad = new AtomicReference<>();

    public void klar(Instant nar) { klar.set(nar); }

    public void signal(Instant nar, String utfall) {
        signalTid.set(nar);
        signalUtfall.set(utfall);
    }

    public void granskad(Instant nar) { granskad.set(nar); }

    /** Tidsstämplarna som ISO-strängar i UTC, null där något ännu inte hänt sedan omstart. */
    public Map<String, Object> somKarta() {
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("kedjanKlar", text(klar.get()));
        ut.put("signalSkickad", text(signalTid.get()));
        ut.put("signalUtfall", signalUtfall.get());
        ut.put("granskad", text(granskad.get()));
        return ut;
    }

    private static String text(Instant t) { return t == null ? null : t.toString(); }
}
