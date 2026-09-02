package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Rådgivande koll av kommande-kön mot Blockets annonser: <b>står raden i kö för en bil som
 * faktiskt redan går att köpa?</b>
 *
 * <p><b>Varför den finns.</b> Kommande-vakten i {@code WebInsightScraperService} är ett
 * Groq-anrop, och en språkmodell vet sämst av allt just det den ska döma: om en helt ny modell
 * hunnit börja levereras. Natten 2026-09-02 parkerade den elva rader och <b>tio var fel</b> —
 * sex om Hyundai Ioniq 3 och fyra om Subaru E-Outback, båda till salu hos svenska handlare, den
 * senare med rader som redan låg <i>osparkerade</i> på sitt eget bilkort från tidigare nätter.
 * Samma överblockering fällde Kia EV3 och Polestar 4 den 2026-08-28. Felet hittas i dag genom att
 * en människa läser kön rad för rad och slår upp varje bil för hand; den här kollen gör det i ett
 * anrop.
 *
 * <p><b>Den släpper aldrig en rad själv — och det är inte försiktighet, det är mätt.</b> Den
 * självklara automatiken ("har Blocket annonser, parkera inte") prövades mot hela kön 2026-09-02
 * och hade släppt ut <b>Hyundai Tucson (4 rader), Santa Fe (1) och Lexus NX 450h+ (1)</b> på
 * bilkorten. Alla tre är korrekt parkerade: bilarna säljs, men texterna handlar om <i>nästa
 * generation</i> ("den nya femte generationens Tucson", en EREV-version, den NX som introduceras
 * mot slutet av 2026). Sex riktiga parkeringar hade alltså offrats för att rädda tio felaktiga,
 * och en osläppt bil på ett bilkort går inte att ta tillbaka medan en rad i kön går att släppa
 * fram. Skillnaden mellan grupperna finns inte i annonsdatan utan i texten — se
 * {@link #NYHETSORD}.
 *
 * <p><b>Därför är {@link Status#GRANSKA} en egen utgång.</b> Att slå ihop "bilen säljs och raden
 * ser ut som ren fakta" med "bilen säljs men raden handlar om nästa generation" hade gjort
 * rapporten värdelös på samma sätt som ett {@code INGEN_DATA} som räknas som ett godkännande i
 * {@code VpicYearCheckService}: två sorters svar i samma siffra döljer vilket av dem som växer.
 *
 * <p><b>Varför den inte är ett nattjobb.</b> Samma skäl som vPIC-vakten: {@code GRANSKA} kommer
 * att stå kvar natt efter natt för Tucson, Santa Fe och NX 450h+, och ett larm som är falskt varje
 * dag gör att rapporten slutar läsas. Den körs när någon frågar.
 */
@Service
public class UpcomingAdCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpcomingAdCheckService.class);

    /**
     * Tak på antalet Blocket-uppslag i en körning. Kön låg på 48 rader över 14 bilar 2026-09-02,
     * så taket bör aldrig slå — men <b>en kapad körning måste synas</b> ({@code hoppade} i
     * {@link Rapport}), annars ser en halv granskning ut som en hel.
     */
    static final int MAX_ANROP = 40;

    /** Så många annonsnamn som följer med per bil, nog för att avgöra om träffen är rätt bil. */
    private static final int MAX_EXEMPEL = 3;

    /**
     * Orden som skiljer "raden handlar om nästa generation" från "raden är ren fakta om bilen
     * som står hos handlaren i dag".
     *
     * <p>Mätt på kön 2026-09-02: <b>varenda</b> korrekt parkerad rad för Tucson, Santa Fe och
     * NX 450h+ bär ett av orden ("den nya femte generationens Tucson", "Hyundai planerar en
     * EREV-version", "Den nya laddhybriden NX 450h+"), medan <b>ingen</b> av de sex felparkerade
     * Ioniq 3-raderna gör det ("Pris för Ioniq 3 Standard Range Select startar på 344 900 kr")
     * och bara en av fyra E-Outback-rader.
     *
     * <p>Texten körs genom {@code foldDiacritics} före matchningen, så orden står folierade här:
     * {@code vantas}, {@code premiar}, {@code saljstart}. Missar ordlistan ett nyhetsord blir
     * domen {@code LARM} i stället för {@code GRANSKA} — ett falskt larm i en rådgivande rapport,
     * aldrig ett släpp.
     */
    static final Pattern NYHETSORD = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(ny|nya|nytt|nasta generation|kommande|kommer att|planerar|planeras"
            + "|lanseras|lansering|lanseringen|vantas|premiar|saljstart|introduceras|blir|debuterar)"
            + "(?![\\p{L}\\p{N}])");

    /** Tecken som skalas bort i annonsernas kanter — Blocket skriver "Outback," och "EV6." */
    private static final Pattern KANTTECKEN = Pattern.compile("^[.,;:!?\"'()\\[\\]|&/]+|[.,;:!?\"'()\\[\\]|&/]+$");

    private final BlocketPriceService blocket;

    public UpcomingAdCheckService(BlocketPriceService blocket) {
        this.blocket = blocket;
    }

    /**
     * Utfallet för en bil i kön. <b>Ordningen är rapportens prioritetsordning</b> — domarna
     * sorteras på den, så det som kräver ett beslut ligger överst.
     */
    public enum Status {
        /** Bilen har annonser OCH minst en köad rad saknar nyhetsord — troligen felparkerad. */
        LARM,
        /** Blocket svarade inte. Ingen åsikt — och uttryckligen inte ett godkännande. */
        UPPSLAG_MISSLYCKADES,
        /** Bilen har annonser, men varje köad rad säger själv att den gäller något kommande. */
        GRANSKA,
        /** Inga annonser bär modellnamnet. Parkeringen ser riktig ut. */
        INGA_ANNONSER
    }

    /**
     * @param annonser            annonser vars namn bär modellbeteckningen (inte Blockets råa träffantal)
     * @param raderUtanNyhetsord  de köade id:n som läses som ren fakta om en bil man kan köpa
     */
    public record Dom(String carMake, String carModel, Status status, int annonser,
                      List<Long> rader, List<Long> raderUtanNyhetsord, List<String> exempel) {}

    public record Rapport(int bilar, int rader, int hoppade,
                          Map<String, Long> perStatus, List<Dom> domar) {}

    /** Granskar kön som den ligger i {@code UpcomingInsightService.list()}. */
    public Rapport granska(List<Map<String, Object>> koRader) {
        return granska(koRader, blocket::searchAds);
    }

    /** Samma granskning med annonsuppslaget som söm, så domarna går att prova utan HTTP. */
    Rapport granska(List<Map<String, Object>> koRader, Function<String, JsonNode> annonssok) {
        Map<String, List<Map<String, Object>>> perBil = new LinkedHashMap<>();
        int rader = 0;
        for (Map<String, Object> rad : koRader) {
            String make = text(rad.get("car_make"));
            String model = text(rad.get("car_model"));
            // Rader utan märke eller modell når varken kort eller prompt och har ingen bil att
            // slå upp — de är ett eget problem, inte den här kollens.
            if (make.isBlank() || model.isBlank()) continue;
            perBil.computeIfAbsent(make + " " + model, k -> new ArrayList<>()).add(rad);
            rader++;
        }

        List<Dom> domar = new ArrayList<>();
        Map<Status, Long> rakning = new LinkedHashMap<>();
        int anrop = 0, hoppade = 0;

        for (Map.Entry<String, List<Map<String, Object>>> bil : perBil.entrySet()) {
            List<Map<String, Object>> gruppen = bil.getValue();
            String make = text(gruppen.get(0).get("car_make"));
            String model = text(gruppen.get(0).get("car_model"));

            if (anrop >= MAX_ANROP) { hoppade++; continue; }
            anrop++;

            JsonNode docs = annonssok.apply(bil.getKey());
            Dom dom = domFor(make, model, gruppen, docs);
            domar.add(dom);
            rakning.merge(dom.status(), 1L, Long::sum);
        }

        Map<String, Long> perStatus = new LinkedHashMap<>();
        for (Status s : Status.values()) perStatus.put(s.name(), rakning.getOrDefault(s, 0L));

        domar.sort(Comparator.comparingInt((Dom d) -> d.status().ordinal()));
        log.info("Annonskoll av kommande-kön: {} bilar, {} rader, {} larm, {} att granska, {} hoppade",
                domar.size(), rader, perStatus.get(Status.LARM.name()),
                perStatus.get(Status.GRANSKA.name()), hoppade);
        return new Rapport(domar.size(), rader, hoppade, perStatus, domar);
    }

    private Dom domFor(String make, String model, List<Map<String, Object>> gruppen, JsonNode docs) {
        List<Long> alla = new ArrayList<>();
        List<Long> utanNyhetsord = new ArrayList<>();
        for (Map<String, Object> rad : gruppen) {
            Long id = idOf(rad.get("insight_id"));
            if (id == null) continue;
            alla.add(id);
            if (!sagerAttBilenArKommande(text(rad.get("insight")))) utanNyhetsord.add(id);
        }

        if (docs == null)
            return new Dom(make, model, Status.UPPSLAG_MISSLYCKADES, 0, alla, utanNyhetsord, List.of());

        List<String> exempel = new ArrayList<>();
        int annonser = 0;
        for (JsonNode doc : docs) {
            String namn = annonsnamn(doc);
            if (!annonsenNamnerModellen(model, namn)) continue;
            annonser++;
            if (exempel.size() < MAX_EXEMPEL) exempel.add(namn);
        }

        Status status = annonser == 0 ? Status.INGA_ANNONSER
                : utanNyhetsord.isEmpty() ? Status.GRANSKA
                : Status.LARM;
        return new Dom(make, model, status, annonser, alla, utanNyhetsord, exempel);
    }

    /** Rubrik + trimnivå: "Hyundai IONIQ" ensamt räcker inte, "3 Long Range Trend" sitter i specen. */
    static String annonsnamn(JsonNode doc) {
        String rubrik = doc.path("heading").asText("");
        String spec = doc.path("model_specification").asText("");
        return (rubrik + " " + spec).trim();
    }

    static boolean sagerAttBilenArKommande(String text) {
        return NYHETSORD.matcher(ExpertInsightService.foldDiacritics(
                ExpertInsightService.flattenSpaces(text))).find();
    }

    /**
     * Bär annonsens namn modellbeteckningen?
     *
     * <p><b>Två regler, båda framtvingade av mätning 2026-09-02.</b>
     * <ol>
     *   <li><b>Modellens ord som sammanhängande delsekvens av annonsens.</b> Ren
     *       {@code contains} på strängen fäller inte {@code Model Y L} mot
     *       "Tesla Model Y Long Range" — modellen är ett tecken-prefix av annonsen. Ord för ord
     *       är {@code l} inte {@code long}, och träffen uteblir som den ska.</li>
     *   <li><b>Modellens ord hopslagna = EN hel token i annonsen.</b> Blocket registrerar
     *       laddhybriden som "Lexus NX450h+" utan mellanslag medan vi lagrar "NX 450h+", så
     *       regel 1 ensam missar den. Regeln är smal med flit: den kräver att hela hopslagningen
     *       är ett eget ord i annonsen, så {@code Model Y L} -> {@code modelyl} fortfarande inte
     *       träffar "Model Y Long Range". Den lösare varianten — stryk mellanslagen på BÅDA
     *       sidor och gör {@code contains} — gör precis det felet.</li>
     * </ol>
     *
     * <p>Att fråga på hela fritexten och räkna Blockets träffantal duger inte: {@code q=Kia EV3}
     * svarar med syskonmodeller, och {@code q=Subaru E-Outback} gav 2026-09-02 lika många
     * bensin-Outback som elbilar. Det är namnet i annonsen som avgör, inte att sökningen svarade.
     */
    static boolean annonsenNamnerModellen(String model, String annonsnamn) {
        List<String> m = ord(model);
        List<String> a = ord(annonsnamn);
        if (m.isEmpty() || a.isEmpty()) return false;

        for (int i = 0; i + m.size() <= a.size(); i++) {
            boolean lika = true;
            for (int j = 0; j < m.size(); j++) {
                if (!a.get(i + j).equals(m.get(j))) { lika = false; break; }
            }
            if (lika) return true;
        }
        return m.size() > 1 && a.contains(String.join("", m));
    }

    /**
     * Namnet till jämförbara ord. Går genom {@code flattenSpaces} + {@code foldDiacritics} av
     * samma skäl som bilkortens matchning: annonser och insikter bär U+2011 och U+202F, och
     * "Citroën" stavas på två sätt i vår egen data.
     */
    private static List<String> ord(String s) {
        String rent = ExpertInsightService.foldDiacritics(ExpertInsightService.flattenSpaces(s));
        List<String> ut = new ArrayList<>();
        for (String bit : rent.split(" ")) {
            String t = KANTTECKEN.matcher(bit).replaceAll("");
            if (!t.isBlank()) ut.add(t);
        }
        return ut;
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static Long idOf(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
