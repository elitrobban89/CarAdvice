package com.caradvice.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Läser motoralternativ och bagagevolym ur auto-data.net.
 *
 * <p>Källan valdes 2026-08-12 efter att nio kandidater provats skarpt: den är den enda som bär
 * BÅDA uppgifterna för både förbränningsbilar och elbilar. {@code bilweb.se}, {@code car.info}
 * och {@code carfolio.com} visade ingen bagagevolym alls på sina modellsidor,
 * {@code biluppgifter.se} har ingen modellväg, och {@code ultimatespecs.com} hade fältet men
 * lämnade det tomt. {@code robots.txt} slutar med {@code User-agent: *} / {@code Allow: /}.
 *
 * <p>Sajten har fyra nivåer — märke, modell, generation, variant — och de två vi behöver ligger
 * på var sin nivå:
 * <ul>
 *   <li><b>Generationssidan</b> listar samtliga motorvarianter. Det är motoralternativen, i ett
 *       enda anrop: Golf VIII ger 21 rader från 1.0 TSI (90 Hp) till R 2.0 TSI (333 Hp).</li>
 *   <li><b>Variantsidan</b> bär {@code Trunk (boot) space - minimum/maximum}. Volymen hör till
 *       karossen och generationen, inte till motorn, så EN variantsida räcker per generation —
 *       annars hade en Golf krävt 21 hämtningar för samma två tal.</li>
 * </ul>
 *
 * <p>Minimumraden är den vi vill ha: den är normalvolymen med baksätet uppfällt, alltså samma
 * mått som {@code GroqService.requireCargoCapacity} dömer mot. Maxvolymen är nästan tre gånger
 * så stor (Golf: 380 mot 1 237 l) och hade gjort bagagekravet verkningslöst.
 *
 * <p>Parsningen är skild från hämtningen så den kan provas mot sparade sidor utan HTTP — samma
 * mönster som {@code EvDatabaseScraperServiceMatchTest}, och av samma skäl: när sidstrukturen
 * ändras ska testet säga det, inte nattkörningen.
 */
@Service
public class AutoDataScraperService {

    private static final Logger log = LoggerFactory.getLogger(AutoDataScraperService.class);

    static final String BAS = "https://www.auto-data.net";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    /** Samma paus som {@code CargoSpecSyncService} håller mot Bilweb. */
    private static final int PAUS_MS = 1500;
    private static final int TIMEOUT_MS = 20_000;

    /** "1.5 TSI (150 Hp)" → 150. Effekten står alltid inom parentes, alltid följd av "Hp". */
    private static final Pattern EFFEKT = Pattern.compile("\\((\\d+)\\s*Hp\\)", Pattern.CASE_INSENSITIVE);

    /** "380 l" → 380. Cellen bär även "13.42 cu. ft." i ett eget span som skalas bort först. */
    private static final Pattern LITER = Pattern.compile("(\\d[\\d\\s]*)\\s*l\\b");

    /**
     * En motorvariant som den står på generationssidan.
     *
     * @param namn      "1.5 TSI (150 Hp)" eller "GTI 2.0 TSI (245 Hp) DSG"
     * @param hk        effekten utbruten ur namnet, null när den saknas
     * @param arsspann  "2020 - 2024" — avgör vilken generation en årsmodell hör till
     * @param sokvag    "/en/volkswagen-golf-viii-1.5-tsi-150hp-38152", för bagagehämtningen
     */
    public record MotorAlternativ(String namn, Integer hk, String arsspann, String sokvag) {}

    /** Bagagevolymen i liter. {@code min} är normalvolymen, {@code max} med nedfällt säte. */
    public record Bagagevolym(Integer minLiter, Integer maxLiter) {}

    /** En modell på ett märkes sida. {@code namn} är sidans eget, t.ex. "Golf" eller "ID.4". */
    public record Modell(String namn, String sokvag) {}

    /**
     * En generation på en modellsida.
     *
     * @param titel    "Volkswagen Golf VIII (facelift 2024)"
     * @param franAr   första årsmodellen, null när sidan inte anger något
     * @param tillAr   sista årsmodellen, null när generationen fortfarande säljs
     * @param kaross   "Hatchback", "Station wagon (estate)", "Sedan" — avgör vilken bagagevolym som gäller
     */
    public record Generation(String titel, Integer franAr, Integer tillAr, String kaross, String sokvag) {
        /** Sant när årsmodellen ryms i generationens spann. Öppet slut räknas som pågående. */
        public boolean galler(int arsmodell) {
            if (franAr != null && arsmodell < franAr) return false;
            return tillAr == null || arsmodell <= tillAr;
        }
    }

    /**
     * Ord i en generationstitel som aldrig står i ett bilnamn hos oss och därför inte får
     * diskvalificera en träff: generationsnumret, faceliftmarkeringen och dörrantalet.
     */
    private static final Pattern TITELBRUS = Pattern.compile(
            "(?i)\\b(facelift|restyling|\\d{4}|[ivx]+|\\d-door|door|doors)\\b|[()\\-,]");

    /**
     * Motoralternativen på en generationssida, i sidans egen ordning (starkast först).
     *
     * <p>Raderna ser ut som {@code <th class="i"><a href="..."><strong><span class="tit">1.5 TSI
     * (150 Hp)</span> <span class="end">2020 - 2024</span></strong></a></th>}. Varje rad har
     * dessutom en andra länk till samma sida i sin datacell — därför plockas bara {@code th}:s.
     *
     * @return tom lista när sidan inte har någon variantlista (fel URL, eller struktur som ändrats)
     */
    public static List<MotorAlternativ> parseMotorAlternativ(String html) {
        List<MotorAlternativ> ut = new ArrayList<>();
        if (html == null || html.isBlank()) return ut;

        Document doc = Jsoup.parse(html);
        for (Element lank : doc.select("th.i > a[href]")) {
            Element titel = lank.selectFirst("span.tit");
            if (titel == null) continue;

            String namn = titel.text().trim();
            if (namn.isEmpty()) continue;

            Element ar = lank.selectFirst("span.end");
            ut.add(new MotorAlternativ(namn, effektAv(namn),
                    ar != null ? ar.text().trim() : null, lank.attr("href")));
        }
        if (ut.isEmpty()) log.warn("auto-data: generationssidan gav noll motoralternativ — struktur ändrad?");
        return ut;
    }

    /**
     * Bagagevolymen på en variantsida.
     *
     * <p>Tabellen "Space, Volume and weights" har en rad per mått; vi letar upp rubrikcellen på
     * text eftersom radernas ordning skiljer sig mellan biltyper (elbilar saknar t.ex.
     * bränsletanken). Minimum kan saknas på enstaka bilar medan maximum finns — då returneras
     * ändå posten, med null i det fält som fattas, så anroparen själv får avgöra om den duger.
     *
     * @return null när sidan inte bär någon bagagerad alls
     */
    public static Bagagevolym parseBagagevolym(String html) {
        if (html == null || html.isBlank()) return null;

        Document doc = Jsoup.parse(html);
        Integer min = literVid(doc, "Trunk (boot) space - minimum");
        Integer max = literVid(doc, "Trunk (boot) space - maximum");
        return (min == null && max == null) ? null : new Bagagevolym(min, max);
    }

    /** Talet i datacellen bredvid rubriken, eller null när raden saknas eller inte bär liter. */
    private static Integer literVid(Document doc, String rubrik) {
        for (Element th : doc.select("th")) {
            if (!th.text().trim().startsWith(rubrik)) continue;

            Element td = th.nextElementSibling();
            if (td == null) continue;

            // val2 är imperialkolumnen ("13.42 cu. ft.") och får inte matcha litersiffran.
            Element ren = td.clone();
            ren.select("span.val2").remove();

            Matcher m = LITER.matcher(ren.text());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1).replaceAll("\\s", ""));
                } catch (NumberFormatException ignored) { /* faller igenom till null */ }
            }
        }
        return null;
    }

    /**
     * Märkena, som de listas i sidfoten på varje sida ({@code /en/volkswagen-brand-80}).
     *
     * <p>Listan finns överallt, så den kan hämtas från vilken sida som helst — det gör att vi
     * slipper sitemapen, som är 27 MB fördelat på fyra skärvor och dessutom bär sju språk.
     */
    public static Map<String, String> parseMarken(String html) {
        Map<String, String> ut = new LinkedHashMap<>();
        if (html == null || html.isBlank()) return ut;

        for (Element a : Jsoup.parse(html).select("a[href~=/en/[a-z0-9-]+-brand-\\d+]")) {
            String href = a.attr("href");
            Matcher m = Pattern.compile("/en/([a-z0-9-]+)-brand-\\d+$").matcher(href);
            if (!m.find()) continue;
            ut.putIfAbsent(m.group(1).replace('-', ' '), href.startsWith("http") ? href : BAS + href);
        }
        return ut;
    }

    /** Modellerna på ett märkes sida. Länkarna bär klassen {@code modeli}. */
    public static List<Modell> parseModeller(String html) {
        List<Modell> ut = new ArrayList<>();
        if (html == null || html.isBlank()) return ut;

        for (Element a : Jsoup.parse(html).select("a.modeli[href]")) {
            Element namn = a.selectFirst("strong");
            if (namn == null || namn.text().isBlank()) continue;
            ut.add(new Modell(namn.text().trim(), a.attr("href")));
        }
        return ut;
    }

    /**
     * Generationerna på en modellsida, med årsspann och kaross.
     *
     * <p>Varje block ser ut som {@code Volkswagen Golf VIII | 2020 - 2024 | Hatchback | Power:
     * from 90 to 333 Hp | Dimensions: ...}. Årsspannet och karossen är hela poängen: utan dem
     * går det inte att skilja en 2020 års halvkombi från 2024 års kombi, och då är vi tillbaka
     * i samma fel som gav Nissan Leaf 2019 en 2026-modells siffror.
     */
    public static List<Generation> parseGenerationer(String html) {
        List<Generation> ut = new ArrayList<>();
        if (html == null || html.isBlank()) return ut;

        for (Element block : Jsoup.parse(html).select("#generr > div")) {
            Element a = block.selectFirst("a[href*=-generation-]");
            if (a == null) continue;

            String titel = a.attr("title").split(" - ")[0].trim();
            if (titel.isEmpty()) titel = a.text().trim();

            String text = block.text();
            ut.add(new Generation(titel, arFor(text, true), arFor(text, false),
                    karossAv(text), a.attr("href")));
        }
        if (ut.isEmpty()) log.warn("auto-data: modellsidan gav noll generationer — struktur ändrad?");
        return ut;
    }

    /**
     * Generationen som en årsmodell hör till, eller null när svaret inte är entydigt.
     *
     * <p>Två filter, i den ordningen:
     * <ol>
     *   <li><b>Årsmodellen</b> måste rymmas i spannet. Ingen träff ger null — att falla tillbaka
     *       på "närmaste" generation är precis felet som gav 2019 års Leaf 2026 års räckvidd.</li>
     *   <li><b>Karossen</b>, via titeln. En generationstitel som bär ord bilnamnet saknar
     *       ("Golf VIII <b>Variant</b>" mot bilnamnet "Volkswagen Golf") beskriver en annan
     *       kaross och väljs bort — annars får en halvkombi kombins bagagevolym.</li>
     * </ol>
     *
     * <p>Står flera kvar vinner den som börjar SENAST. Generationer överlappar alltid ett par år
     * — Golf VII såldes till 2021 medan Golf VIII kom 2020 — så ett krav på entydighet hade gett
     * null för nästan varje årsmodell i ett skarvår. Den nyare generationen är rätt gissning för
     * en begagnatannons, eftersom den gamla fasas ut snabbt när den nya väl finns.
     *
     * <p><b>Det osäkra fallet som blir kvar</b> är en bil från skarvårets första år: en Golf
     * 2020 kan vara antingen VII eller VIII. Skadan är dock begränsad till det ena året och
     * mätvärdena ligger nära varandra (Golf VIII 380 l mot faceliftens 381 l), medan alternativet
     * — att inte svara alls — hade lämnat de flesta bilar utan både volym och motoralternativ.
     * Årsfiltret är kvar och är det som skyddar: en 2019:a kan aldrig få en 2024-generations
     * siffror, vilket var själva felet i Leaf- och MG4-fallen.
     */
    public static Generation valjGeneration(List<Generation> alla, String bilnamn, Integer arsmodell) {
        if (alla == null || alla.isEmpty()) return null;

        List<Generation> kvar = new ArrayList<>();
        for (Generation g : alla) {
            if (arsmodell != null && !g.galler(arsmodell)) continue;
            if (bilnamn != null && !titelnRymsIBilnamnet(g.titel(), bilnamn)) continue;
            kvar.add(g);
        }
        if (kvar.isEmpty()) return null;

        // Mest specifika titeln först, precis som ev_spec- och insiktsmatchningen: "Golf VIII"
        // ryms i varje Golf-namn, så utan det steget hade basmodellen tagit Alltrackens plats.
        return kvar.stream()
                .max(java.util.Comparator
                        .comparingInt((Generation g) -> karossordAntal(g.titel()))
                        .thenComparingInt(g -> g.franAr() != null ? g.franAr() : Integer.MIN_VALUE))
                .orElse(null);
    }

    /** Antal karossord i titeln, alltså det som skiljer "Golf VIII Alltrack" från "Golf VIII". */
    private static int karossordAntal(String titel) {
        int n = 0;
        for (String ord : normalisera(TITELBRUS.matcher(titel).replaceAll(" ")).split("\\s+"))
            if (!ord.isBlank()) n++;
        return n;
    }

    /**
     * Sant när generationstiteln inte bär något extra ord utöver bilnamnet.
     *
     * <p>Generationsnummer, faceliftmarkering och dörrantal räknas inte som extra — de står
     * aldrig i ett bilnamn hos oss. Kvar blir karossord som "Variant", "Coupe" och "Alltrack",
     * och de måste finnas i bilnamnet för att generationen ska godtas.
     */
    private static boolean titelnRymsIBilnamnet(String titel, String bilnamn) {
        String namn = " " + normalisera(bilnamn) + " ";
        for (String ord : normalisera(TITELBRUS.matcher(titel).replaceAll(" ")).split("\\s+")) {
            if (ord.isBlank()) continue;
            if (!namn.contains(" " + ord + " ")) return false;
        }
        return true;
    }

    private static String normalisera(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9åäöéèü.\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Karossen står mellan årsspannet och "Power:" i blockets text. Jsoup slår ihop fälten till
     * en enda rad, så de går inte att dela på blanksteg — de plockas isär på sina grannar.
     */
    private static String karossAv(String text) {
        Matcher m = Pattern.compile("(?:19|20)\\d{2}\\s*-\\s*(?:(?:19|20)\\d{2})?\\s*(.*?)\\s*Power\\s*:").matcher(text);
        if (!m.find()) return null;
        String kaross = m.group(1).trim();
        return kaross.isEmpty() ? null : kaross;
    }

    /** Första respektive sista året i "2020 - 2024". Öppet slut ("2024 -") ger null som slutår. */
    private static Integer arFor(String text, boolean forsta) {
        Matcher m = Pattern.compile("(19|20)(\\d{2})\\s*-\\s*((19|20)\\d{2})?").matcher(text);
        if (!m.find()) return null;
        if (forsta) return Integer.parseInt(m.group(1) + m.group(2));
        return m.group(3) != null ? Integer.parseInt(m.group(3)) : null;
    }

    // --- hämtning ---------------------------------------------------------------------------
    // Parsningen ovan är statisk och HTTP-fri med flit; allt nedanför rör nätet och testas inte
    // mot sajten, av samma skäl som EvDatabaseScraperService: ett test som kräver internet
    // slutar vara ett test och blir en väderrapport.

    /** Sidor vi redan hämtat under körningen. En Golf-generation delas av alla Golf-rader. */
    private final Map<String, String> sidcache = new ConcurrentHashMap<>();

    String hamta(String url) {
        return sidcache.computeIfAbsent(url, u -> {
            try {
                Thread.sleep(PAUS_MS);
                return Jsoup.connect(u).userAgent(UA).timeout(TIMEOUT_MS).get().html();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception e) {
                log.warn("auto-data: kunde inte hämta {} — {}", u, e.getMessage());
                return "";
            }
        });
    }

    /**
     * Hela kedjan för en bil: märke → modell → generation → variantsida.
     *
     * <p>Fyra hämtningar första gången, men bara den sista är unik per bil — märkes-, modell-
     * och generationssidan delas av alla rader för samma modell och ligger kvar i {@link #sidcache}.
     *
     * @param bilnamn  "Volkswagen Golf" som det står i vår databas
     * @param arsmodell årsmodell, eller null när namnet inte bär något år
     * @return bagagevolymen, eller null när något steg inte gick att slå upp
     */
    public Bagagevolym bagageForBil(String bilnamn, Integer arsmodell) {
        Generation gen = generationForBil(bilnamn, arsmodell);
        if (gen == null) return null;

        List<MotorAlternativ> motorer = parseMotorAlternativ(hamta(BAS + gen.sokvag()));
        if (motorer.isEmpty()) return null;

        // Volymen hör till karossen, inte motorn — en variantsida räcker för hela generationen.
        return parseBagagevolym(hamta(BAS + motorer.get(0).sokvag()));
    }

    /** Motoralternativen för en bil, hela generationens utbud. Tom lista när uppslaget misslyckas. */
    public List<MotorAlternativ> motorerForBil(String bilnamn, Integer arsmodell) {
        Generation gen = generationForBil(bilnamn, arsmodell);
        return gen == null ? List.of() : parseMotorAlternativ(hamta(BAS + gen.sokvag()));
    }

    /**
     * Senaste generationen för en modell, utan årsmodell att gå på.
     *
     * <p>Publik ingång åt generationsifyllningen: {@code ice_consumption}s motorlista beskriver
     * modellens NUVARANDE generation, så det är just den senaste vi vill datera.
     */
    public Generation generationForNamn(String bilnamn) {
        return generationForBil(bilnamn, null);
    }

    /** Märkes- och modelluppslaget, delat av båda ingångarna ovan. */
    private Generation generationForBil(String bilnamn, Integer arsmodell) {
        if (bilnamn == null || bilnamn.isBlank()) return null;

        Map<String, String> marken = parseMarken(hamta(BAS + "/en/volkswagen-brand-80"));
        String markessida = markessidaFor(bilnamn, marken);
        if (markessida == null) {
            log.debug("auto-data: inget märke matchar {}", bilnamn);
            return null;
        }

        String modellsida = modellsidaFor(bilnamn, parseModeller(hamta(markessida)));
        if (modellsida == null) {
            log.debug("auto-data: ingen modell matchar {}", bilnamn);
            return null;
        }
        return valjGeneration(parseGenerationer(hamta(modellsida)), bilnamn, arsmodell);
    }

    /** Längsta märkesnamnet som inleder bilnamnet vinner — "Land Rover" före "Land". */
    private static String markessidaFor(String bilnamn, Map<String, String> marken) {
        String namn = normalisera(bilnamn);
        String bast = null, bastUrl = null;
        for (Map.Entry<String, String> e : marken.entrySet()) {
            String marke = normalisera(e.getKey());
            if (!namn.equals(marke) && !namn.startsWith(marke + " ")) continue;
            if (bast == null || marke.length() > bast.length()) { bast = marke; bastUrl = e.getValue(); }
        }
        return bastUrl;
    }

    /** Samma regel för modellen: mest specifika namnet som ryms i bilnamnet vinner. */
    private static String modellsidaFor(String bilnamn, List<Modell> modeller) {
        String namn = " " + normalisera(bilnamn) + " ";
        Modell bast = null;
        for (Modell m : modeller) {
            if (!namn.contains(" " + normalisera(m.namn()) + " ")) continue;
            if (bast == null || m.namn().length() > bast.namn().length()) bast = m;
        }
        if (bast == null) return null;
        return bast.sokvag().startsWith("http") ? bast.sokvag() : BAS + bast.sokvag();
    }

    private static Integer effektAv(String namn) {
        Matcher m = EFFEKT.matcher(namn);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
