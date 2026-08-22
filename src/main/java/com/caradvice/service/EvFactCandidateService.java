package com.caradvice.service;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fyndlista: vilka av nattens nya insikter som duger som karusellrad i Elbilsladdning.
 *
 * <p><b>Varför en fyndlista och inte automatisk publicering.</b> Karusellen i {@code ev-app.js}
 * är handkurerad, och kurateringen bär beslut den här koden inte kan ta: fem rader ur samma
 * CarUp-studie slås ihop till ETT tips om batterihälsa, Euro 7 och batteripasset ligger med
 * flit i samma slide, och ID. Cross är ett medvetet undantag från regeln om kommande modeller.
 * Framför allt är insikterna <b>AI-extraherad text som ingen människa läst mot källan</b> — och
 * det har redan kostat en gång: 2026-08-18 gick tre few-shot-exempel som alla var fel-på-bilen
 * rakt igenom och fällde ett berömmande mätvärde (Puma Gen-E). En rad härifrån är därför ett
 * <b>förslag att granska</b>, aldrig något som får publiceras oläst.
 *
 * <p><b>Vattenmärket är {@code efterId}, inte en tidsstämpel.</b> {@code expert_insight} har
 * ingen skapandetid — id är den enda ordningen som finns. Rutinen skickar tillbaka
 * {@code hogstaId} från förra körningen, och allt under det är redan bedömt. Det är också hela
 * dubblettskyddet mot karusellen: en rad som en gång granskats kommer aldrig upp igen, utan att
 * den här koden behöver veta vad som faktiskt hamnade i {@code ev-app.js}.
 *
 * <p><b>Avslagen räknas var för sig.</b> "Ingen laddkoppling", "kommande modell" och "märket
 * säljer inga elbilar här" är tre olika nej, och slås de ihop går det inte att se vilket som
 * växer. Samma lärdom som {@code IceGenerationService.missarPerOrsak} och
 * {@code VpicYearCheckService.Status.INGEN_DATA} — två sorters svar i samma siffra döljer
 * vilket av dem som rör sig.
 *
 * <p><b>Noll kandidater är inte automatiskt en lugn natt.</b> Står {@code granskade} på 0 har
 * ingenting bedömts, och det betyder antingen ett vattenmärke som sprungit förbi listan eller
 * ett nattjobb som slutat skriva. Rapporten säger det rakt ut i {@code varning} i stället för
 * att svara med en tom lista som ser frisk ut — ett tomt jobb som inte kan larma är precis det
 * som lät cargo-parsern ligga död bakom 602/602/0.
 */
@Service
public class EvFactCandidateService {

    /**
     * Hur många rader som som mest bedöms i ett anrop. Taket finns för att ett glömt
     * {@code efterId} annars hade dragit hela tabellen (2 000+ rader) genom poängsättningen.
     */
    static final int MAX_GRANSKADE = 300;

    /** Utan {@code efterId}: så många av de nyaste raderna bedöms. Ungefär en veckas skrapning. */
    static final int GRANSKADE_UTAN_VATTENMARKE = 60;

    /**
     * Teman som gör en insikt laddningsrelevant. Ordningen styr ikonvalet: första temat som
     * träffar bestämmer ikonen, så det mest specifika ligger först. Batterihälsa före
     * laddeffekt, annars hade "kapaciteten kvar efter 10 000 mil" fått blixtikon för att
     * ordet kW råkade stå i samma mening.
     */
    private static final List<Tema> TEMAN = List.of(
            new Tema("batterihalsa", "🩺",
                    List.of("batterihälsa", "kapacitet kvar", "kapaciteten kvar", "degrader",
                            "batterigaranti", "batteriets hälsa", "battericell")),
            new Tema("v2l-v2h", "🏠",
                    List.of("v2l", "v2h", "vehicle-to", "reservkraft", "mata ström", "mata tillbaka")),
            new Tema("hemmaladdning", "🔌",
                    List.of("laddbox", "hushållsuttag", "laddförlust", "ac-laddning", "11 kw", "22 kw")),
            new Tema("laddtid", "⏱️",
                    List.of("laddtid", "laddstopp", "10 till 80", "10-80", "10–80", "10 % till 80",
                            "minuters laddning", "på 22 minuter", "laddas från")),
            // "laddar med upp till 400 kW" är den vanligaste formuleringen i materialet och
            // fångas inte av något substantiv — verbformerna måste stå med, annars faller
            // varje rad om ett laddnätverk igenom som "ingen laddkoppling".
            new Tema("laddeffekt", "⚡",
                    List.of("kw-laddare", "snabbladd", "supercharger", "800 v", "800-volt", "800 volt",
                            "laddeffekt", "maxeffekt", "kw dc", "dc-laddning", "ccs", "laddnät",
                            "laddare", "laddpunkt", "laddstation",
                            "laddar med", "laddas med", "ladda med")),
            new Tema("forbrukning", "🔋",
                    List.of("kwh/100", "kwh per 100", "kwh/mil", "kwh per mil", "förbrukning",
                            "förbrukade", "drar bara")),
            new Tema("rackvidd", "📏",
                    List.of("räckvidd", "wltp", "epa", "mil på en laddning", "km på en laddning",
                            "räckviddstest")),
            new Tema("kyla", "❄️",
                    List.of("kyla", "vinter", "kall väderlek", "minusgrader")),
            // Sist med flit: den fångar rader som handlar om laddning utan att nämna effekt,
            // tid eller nätverk, och ligger den tidigare stjäl den ikonen från ett specifikare
            // tema. Behovet är mätt — 12-voltsraden (id 1249: en Model S som stått sex månader
            // och sedan varken gick att låsa upp, starta eller LADDA) föll igenom hela listan
            // fram till 2026-08-22, trots att den blev ett av de två tips som faktiskt lades in.
            new Tema("laddning", "🪫",
                    List.of("ladda", "laddas", "laddat", "laddning", "urladdat", "urladdad"))
    );

    /**
     * Ord som gör en rad olämplig som publikt tips oavsett hur många teman den träffar.
     * Ett haveri eller en återkallelse om en enskild bil är inte "visste du att" — det är
     * en nyhet med kort hållbarhet och en utpekad ägare.
     */
    private static final List<String> DISKVALIFICERANDE = List.of(
            "återkall", "recall", "stämning", "konkurs", "haveri", "brand i", "brann");

    /**
     * Siffra + enhet i samma svep. {@code SMALT_MELLANSLAG} i teckenklassen är inte
     * kosmetika: de skrapade texterna bär U+00A0 och U+202F mellan tal och enhet, och Javas
     * {@code \s} matchar ingetdera — samma fälla som en gång lät "e‑Golf" med U+2011 glida
     * förbi {@code CarTitle}. Utan dem hade "542 km" med hårt mellanslag inte räknats som
     * ett mätvärde och raden fått lägre poäng än den förtjänar.
     */
    private static final String SMALT_MELLANSLAG = "\\s\\x{00A0}\\x{202F}\\x{2009}";
    private static final Pattern MATVARDE = Pattern.compile(
            "(\\d[\\d" + SMALT_MELLANSLAG + "]*(?:[.,]\\d+)?)[" + SMALT_MELLANSLAG + "]*"
                    + "(kwh/100\\s*km|kwh/mil|kwh|kw|km|mil|%|minuter|min|volt|liter)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE);

    /**
     * Raden måste bära minst ett av de här orden för att över huvud taget handla om eldrift.
     *
     * <p>Mätt behov, inte befarat: utan spärren blev "blandad bränsleförbrukning på 0,71 liter
     * per mil" för en bensindriven <b>Kia K4 Sportswagon</b> en kandidat 2026-08-22, för att
     * temat "förbrukning" är drivmedelsblint. En förbrukningssiffra i liter hör inte hemma i en
     * elbilskarusell. Samma sorts blindhet som prisgolvet hade innan {@code fuelIntent}.
     *
     * <p>{@code supercharger} står med trots att ordet varken bär "ladd" eller "kwh" — annars
     * föll en rad om Superchargernätet ut som "inte eldrift".
     */
    private static final List<String> ELMARKORER = List.of(
            "kwh", "ladd", "elbil", "eldrift", "elmotor", "batteri", "v2l", "v2h",
            "högvolt", "supercharger");

    /**
     * Märken som inte säljs i Sverige. Handhållen lista, och det är ett medvetet val:
     * <b>ingen tabell vi har skiljer den svenska marknaden från den europeiska.</b> Mätt
     * 2026-08-22 innehåller {@code ev_spec} tolv Zeekr-rader och tre Genesis, och
     * {@code cargo_spec} bär båda märkena också — den första versionen av den här vakten
     * prövade märket mot {@code ev_spec} och avvisade därför <b>noll</b> rader av 60, medan
     * enhetstestet såg friskt ut på en påhittad femmärkeslista. Fixturen bar bara min egen
     * hypotes; först det skarpa provet visade det.
     *
     * <p>Listan <b>faller öppet</b>: ett okänt märke släpps igenom. Ett falskt fynd kostar en
     * rad att läsa bort, ett tyst bortfiltrerat tips kostar ett tips ingen någonsin ser.
     */
    private static final List<String> EJ_I_SVERIGE = List.of(
            "zeekr", "genesis", "nio", "xpeng", "fisker", "rivian", "lucid", "vinfast");

    /** Andel gemensamma ord där två rader räknas som samma faktum ur två källor. */
    static final double DUBBLETT_GRANS = 0.5;

    private final ExpertInsightRepository repo;
    private final UpcomingInsightService upcomingService;

    public EvFactCandidateService(ExpertInsightRepository repo, UpcomingInsightService upcomingService) {
        this.repo = repo;
        this.upcomingService = upcomingService;
    }

    private record Tema(String namn, String ikon, List<String> ord) {}

    /**
     * Bedömer insikterna över vattenmärket och returnerar dem som duger som karusellrad.
     *
     * @param efterId senaste redan granskade id, eller null för de {@value #GRANSKADE_UTAN_VATTENMARKE} nyaste
     * @param limit   max antal kandidater i svaret
     */
    public Map<String, Object> hittaKandidater(Long efterId, int limit) {
        Pageable sida = PageRequest.of(0, MAX_GRANSKADE);
        List<ExpertInsight> rader = (efterId == null)
                ? repo.findAllByOrderByIdDesc(PageRequest.of(0, GRANSKADE_UTAN_VATTENMARKE))
                : repo.findByIdGreaterThanOrderByIdDesc(efterId, sida);

        Set<Long> kommande = upcomingService.hiddenIds();

        Map<String, Integer> avslag = new TreeMap<>();
        List<Map<String, Object>> kandidater = new ArrayList<>();
        long hogstaId = efterId == null ? 0L : efterId;

        for (ExpertInsight rad : rader) {
            if (rad.getId() != null) hogstaId = Math.max(hogstaId, rad.getId());
            String text = rad.getInsight() == null ? "" : rad.getInsight();
            String normaliserad = text.toLowerCase(Locale.ROOT);

            if (kommande.contains(rad.getId())) { rakna(avslag, "kommande-modell"); continue; }
            if (DISKVALIFICERANDE.stream().anyMatch(normaliserad::contains)) {
                rakna(avslag, "olamplig-som-tips"); continue;
            }

            List<Tema> traffade = TEMAN.stream().filter(t -> harOrd(normaliserad, t.ord())).toList();
            if (traffade.isEmpty()) { rakna(avslag, "ingen-laddkoppling"); continue; }

            // Elmarkören prövas EFTER temat, så att orsaken blir den upplysande av de två:
            // "inte-eldrift" betyder då en rad som såg laddningsrelevant ut men handlar om
            // en bensinbil, inte vilken rad som helst utan laddkoppling.
            if (ELMARKORER.stream().noneMatch(normaliserad::contains)) {
                rakna(avslag, "inte-eldrift"); continue;
            }

            // Märkeslös rad slipper marknadsprövningen med flit: ett faktum om ett laddnätverk
            // eller en EU-förordning har inget märke att pröva, och är ofta det bästa tipset.
            String marke = rad.getCarMake() == null ? "" : rad.getCarMake().trim();
            if (!marke.isEmpty() && saljsInteHar(marke)) {
                rakna(avslag, "marknad-saknas"); continue;
            }

            kandidater.add(byggKandidat(rad, text, traffade, marke.isEmpty()));
        }

        List<Map<String, Object>> unika = slaIhopDubbletter(kandidater, avslag);
        unika.sort((a, b) -> Integer.compare((int) b.get("poang"), (int) a.get("poang")));
        List<Map<String, Object>> svar = unika.size() > limit ? unika.subList(0, limit) : unika;

        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("efterId", efterId);
        ut.put("granskade", rader.size());
        ut.put("hogstaId", hogstaId);
        ut.put("kandidater", svar);
        ut.put("avvisadePerOrsak", avslag);
        ut.put("notis", "AI-extraherad text — läs mot källan innan den publiceras. "
                + "Skicka hogstaId som efterId nästa körning.");
        if (rader.isEmpty()) {
            ut.put("varning", "0 insikter över efterId " + efterId
                    + " — antingen har vattenmärket sprungit förbi listan eller så har nattjobbet slutat skriva."
                    + " En tom lista är här INTE ett besked om en lugn natt.");
        }
        return ut;
    }

    private Map<String, Object> byggKandidat(ExpertInsight rad, String text, List<Tema> traffade, boolean utanMarke) {
        String ikon = traffade.get(0).ikon();
        int poang = traffade.size() + (harMatvarde(text) ? 2 : 0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rad.getId());
        m.put("poang", poang);
        m.put("expert", rad.getExpertName());
        m.put("carMake", rad.getCarMake());
        m.put("carModel", rad.getCarModel());
        m.put("teman", traffade.stream().map(Tema::namn).toList());
        m.put("ikon", ikon);
        m.put("utanMarke", utanMarke);
        m.put("insight", text);
        m.put("karusellrad", karusellrad(ikon, text, rad.getExpertName()));
        return m;
    }

    /**
     * Färdig rad i {@code staticFacts}-format. Fetstilen sätts på FÖRSTA mätvärdet och inte
     * på fler: karusellens rader framhäver siffran som är själva poängen, och att gissa vilka
     * av tre siffror som bär tipset är ett redaktörsbeslut. Resten får den som granskar sätta.
     */
    String karusellrad(String ikon, String text, String kalla) {
        String brodtext = framhavForstaMatvardet(text.trim());
        if (kalla != null && !kalla.isBlank() && !brodtext.contains(kalla)) {
            brodtext = brodtext.replaceAll("\\.$", "") + " (" + kalla + ").";
        }
        return "{ icon: '" + ikon + "', text: '" + brodtext.replace("'", "\\'") + "' },";
    }

    private static boolean harMatvarde(String text) {
        return MATVARDE.matcher(text).find();
    }

    private static String framhavForstaMatvardet(String text) {
        Matcher m = MATVARDE.matcher(text);
        if (!m.find()) return text;
        return text.substring(0, m.start()) + "<strong>" + m.group() + "</strong>" + text.substring(m.end());
    }

    /** Prefixmatch åt båda hållen, samma skäl som repots {@code findByMakePrefix}: "Mercedes" ↔ "Mercedes-Benz". */
    private static boolean saljsInteHar(String marke) {
        String m = marke.toLowerCase(Locale.ROOT);
        return EJ_I_SVERIGE.stream().anyMatch(e -> e.startsWith(m) || m.startsWith(e));
    }

    /**
     * Nyckelordsträff med gräns FÖRE ordet men inte efter.
     *
     * <p>Gränsen före är inte kosmetika: utan den matchade temat "räckvidd" på {@code epa}
     * inne i <b>rep</b>arationskostnad, och två rader om trasiga bilar blev kandidater
     * 2026-08-22 (Volvo XC90:s ERAD-fel och en hybridbatterireparation). Ingen av dem har
     * med laddning att göra.
     *
     * <p>Att gränsen efter ordet medvetet saknas är svensk böjning: "laddaren",
     * "laddstationer" och "räckvidden" ska alla träffa, och ett avslutande {@code \b} hade
     * fällt allihop.
     */
    private static boolean harOrd(String normaliseradText, List<String> nyckelord) {
        for (String ord : nyckelord) {
            int från = 0;
            int i;
            while ((i = normaliseradText.indexOf(ord, från)) >= 0) {
                boolean gränsFöre = i == 0 || !Character.isLetterOrDigit(normaliseradText.charAt(i - 1));
                if (gränsFöre) return true;
                från = i + 1;
            }
        }
        return false;
    }

    /**
     * Samma faktum ur två källor blir en rad. Behövs mätt och inte befarat: 2026-08-22 låg
     * Mercedes C 400 e:s laddsiffror som id 1266 (Teknikens Värld) och 1277 (M3) med nästan
     * identisk text, och en automatik utan det här steget hade lagt in tipset två gånger.
     * Den bevarade raden är den med högst poäng; den andra listas som {@code ocksaFran} så
     * att två oberoende källor syns i stället för att tystas.
     */
    private List<Map<String, Object>> slaIhopDubbletter(List<Map<String, Object>> kandidater,
                                                        Map<String, Integer> avslag) {
        List<Map<String, Object>> behallna = new ArrayList<>();
        List<Set<String>> ordmangder = new ArrayList<>();

        for (Map<String, Object> k : kandidater) {
            Set<String> ord = betydelsebarandeOrd((String) k.get("insight"));
            int traff = -1;
            for (int i = 0; i < ordmangder.size(); i++) {
                if (overlapp(ord, ordmangder.get(i)) >= DUBBLETT_GRANS) { traff = i; break; }
            }
            if (traff < 0) {
                behallna.add(k);
                ordmangder.add(ord);
                continue;
            }
            rakna(avslag, "dubblett");
            Map<String, Object> original = behallna.get(traff);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ocksa = (List<Map<String, Object>>) original.computeIfAbsent(
                    "ocksaFran", x -> new ArrayList<Map<String, Object>>());
            Map<String, Object> syskon = new LinkedHashMap<>();
            syskon.put("id", k.get("id"));
            syskon.put("expert", k.get("expert"));
            ocksa.add(syskon);
        }
        return behallna;
    }

    /** Ord kortare än fyra tecken bär ingen ämnesinformation och gör bara överlappet brusigt. */
    private static Set<String> betydelsebarandeOrd(String text) {
        if (text == null) return Set.of();
        String rensad = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
        return new LinkedHashSet<>(Arrays.stream(rensad.split(" "))
                .filter(o -> o.length() >= 4)
                .toList());
    }

    private static double overlapp(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        long gemensamma = a.stream().filter(b::contains).count();
        return (double) gemensamma / Math.min(a.size(), b.size());
    }

    private static void rakna(Map<String, Integer> avslag, String orsak) {
        avslag.merge(orsak, 1, Integer::sum);
    }
}
