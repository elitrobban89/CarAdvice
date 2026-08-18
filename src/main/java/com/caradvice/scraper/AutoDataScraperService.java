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
     *
     * <p><b>Chassikoderna lades till 2026-08-16</b> och var den enskilt största orsaken till att
     * generationsifyllningen stod stilla. Auto-data sätter fabrikskoden i varje titel — "Lexus IS
     * III (XE30, facelift 2020)", "BMW X3 (G01)", "Audi RS4 Avant (B9)" — och koden stod inte i
     * listan. {@link #titelnRymsIBilnamnet} kräver att varje kvarvarande ord finns i bilnamnet,
     * så {@code xe30} fällde <b>varenda</b> generation på modellsidan och hela modellen
     * antecknades som ett nej. Av 139 parkerade missar låg 56 på BMW och 28 på Audi, två märken
     * där auto-data sätter kod på i princip varje generation. Koden bär ingen information vi kan
     * använda: den står aldrig i ett bilnamn, och den skiljer inte kaross från kaross.
     *
     * <p>Mönstret kräver <b>både</b> en bokstav och en siffra, vilket är det som skiljer en
     * chassikod från de två sorters ord som måste överleva: rena karossord ("Avant", "Coupe",
     * "Sportback") saknar siffra, och serienummer som bär betydelse ("3" i "3 Series") saknar
     * bokstav. Att skala bort ett ord kan bara göra filtret mer tillåtande, aldrig få fel
     * generation vald — karossvalet ligger kvar i {@link #titelnRymsIBilnamnet}.
     */
    private static final Pattern TITELBRUS = Pattern.compile(
            "(?i)\\b(facelift|restyling|\\d{4}|[ivx]+|\\d-door|door|doors)\\b"
                    + "|\\b(?=[a-z0-9]*[a-z])(?=[a-z0-9]*\\d)[a-z]{0,3}\\d{1,3}[a-z]{0,2}\\b"
                    + "|[()\\-,]");

    /**
     * Bilnamn där vår CSV bär motorbeteckningen men auto-data listar serien.
     *
     * <p>{@code ice_consumption} har 56 BMW-rader som "BMW 320d" och "BMW 330e", medan auto-data
     * bara känner "3 Series" — modellvalet hittade därför ingenting alls och varje rad blev ett
     * nej. Serien står i beteckningens första siffra, och det gäller även M-varianterna
     * ("m135i" → 1 Series). Ren bokstavsmodell ("BMW X3", "BMW M3") träffar auto-datas egen
     * modellista direkt och får inte översättas.
     */
    private static final Pattern BMW_SERIEBETECKNING = Pattern.compile("^bmw m?([1-8])\\d{2}[a-z]*$");

    /**
     * Motoralternativen på en generationssida, i sidans egen ordning (starkast först).
     *
     * <p>Raderna ser ut som {@code <div class="thi"><a href="..."><strong><span class="tit">1.5
     * TSI (150 Hp)</span> <span class="cur">2024 - </span></strong></a></div>}. Varje rad har
     * dessutom en andra länk till samma sida i sin datacell ({@code div.tdi}) — därför plockas
     * bara rubrikcellen, annars står varje motor två gånger på kortet.
     *
     * <p><b>Sajten bytte markup någon gång mellan 2026-08-12 och 2026-08-14</b>: tabellen är nu
     * divar ({@code div.thi}) där den förut var {@code th.i}, och årsspannet ligger i
     * {@code span.cur} för en pågående generation där en avslutad har {@code span.end}. Effekten
     * var total tystnad — parsern gav noll rader på varje sida, alltså returnerade
     * {@link #bagageForBil} null för varenda bil. Skadan syntes inte i {@code cargo-coverage}
     * eftersom arbetslistan råkade vara tom (602/602/0), och det är läxan: <b>ett jobb som inte
     * har något att göra kan inte skilja "inget kvar att fylla" från "trasig".</b>
     *
     * <p>Båda formerna godtas. Den gamla kostar ingenting att behålla, sparade sidor från
     * 2026-08-12 ligger kvar som fixturer, och sajten har visat att den byter fram och tillbaka.
     *
     * @return tom lista när sidan inte har någon variantlista (fel URL, eller struktur som ändrats)
     */
    public static List<MotorAlternativ> parseMotorAlternativ(String html) {
        List<MotorAlternativ> ut = new ArrayList<>();
        if (html == null || html.isBlank()) return ut;

        Document doc = Jsoup.parse(html);
        for (Element lank : doc.select("div.thi > a[href], th.i > a[href]")) {
            Element titel = lank.selectFirst("span.tit");
            if (titel == null) continue;

            String namn = titel.text().trim();
            if (namn.isEmpty()) continue;

            Element ar = lank.selectFirst("span.end, span.cur");
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
        return valjGeneration(alla, bilnamn, arsmodell, true);
    }

    /**
     * Samma val, men karosskravet går att stänga av.
     *
     * <p><b>Varför någon skulle vilja det.</b> De två uppslagen har olika tålighet för kaross.
     * Bagagevolymen <i>är</i> karossberoende — en halvkombi som får kombins volym är ett rakt
     * fel, och därför måste karossordet stämma. Generationens <b>startår</b> är det inte: Audi
     * TT Coupe och TT Roadster är samma generation samma år, och Golf VIII kom 2020 oavsett
     * kaross. Med karosskravet påslaget fanns det för många modeller ingen titel som kunde
     * passera: alla sju RS4-generationer är Avant, Cabrio eller Saloon och alla 28 TT-titlar bär
     * Coupe eller Roadster, så modellen kunde aldrig dateras hur väl parsern än fungerade.
     *
     * <p>Att släppa karossen är ofarligt just här därför att årtalet ändå passerar
     * {@code beskriverSammaGeneration}: delar auto-datas motorlista ingen hästkraftssiffra med
     * vår, sparas inget årtal. Väljer vi alltså fel kaross fångas det ett steg senare.
     */
    public static Generation valjGeneration(List<Generation> alla, String bilnamn, Integer arsmodell,
                                            boolean karossMasteStamma) {
        if (alla == null || alla.isEmpty()) return null;

        List<Generation> kvar = new ArrayList<>();
        for (Generation g : alla) {
            if (arsmodell != null && !g.galler(arsmodell)) continue;
            if (karossMasteStamma && bilnamn != null && !titelnRymsIBilnamnet(g.titel(), bilnamn)) continue;
            kvar.add(g);
        }
        if (kvar.isEmpty()) return null;

        // Mest specifika titeln först, precis som ev_spec- och insiktsmatchningen: "Golf VIII"
        // ryms i varje Golf-namn, så utan det steget hade basmodellen tagit Alltrackens plats.
        //
        // Utan karosskrav vänds ordningen: då har ingen titel valts bort på kaross, och den MEST
        // specifika är en undermodell — "Audi TT RS Roadster" i stället för "Audi TT Coupe", vars
        // startår är RS-versionens 2016 och inte generationens 2014. Minst karossord är basbilen,
        // alltså den vars generationsår är det vi vill datera.
        if (karossMasteStamma) {
            return kvar.stream()
                    .max(java.util.Comparator
                            .comparingInt((Generation g) -> karossordAntal(g.titel()))
                            .thenComparingInt(g -> g.franAr() != null ? g.franAr() : Integer.MIN_VALUE))
                    .orElse(null);
        }
        // Utan karosskrav har ingen titel valts bort, och då är "mest specifika" fel fråga: varje
        // kaross och varje undermodell ligger kvar i högen. Den nyaste generationen är den vår
        // motorlista beskriver — kortare titel bryter lika, så valet är stabilt mellan sedan och
        // kombi samma år. Att det blir en undermodell ("TT RS" i stället för "TT") gör inget:
        // basgenerationsStartAr grupperar på chassikod och tar generationens lägsta år ändå.
        return kvar.stream()
                .max(java.util.Comparator
                        .comparingInt((Generation g) -> g.franAr() != null ? g.franAr() : Integer.MIN_VALUE)
                        .thenComparingInt(g -> -g.titel().length()))
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

    /**
     * En hämtning som inte gick fram. Se {@link #hamta} för varför det inte får bli tomsträng.
     */
    public static class HamtningsFel extends RuntimeException {
        HamtningsFel(String url, Throwable orsak) {
            super("auto-data: kunde inte hämta " + url + " — " + orsak.getMessage(), orsak);
        }
    }

    /**
     * Sidans html. Kastar {@link HamtningsFel} när hämtningen inte gick fram.
     *
     * <p><b>Varför den inte längre returnerar tomsträng</b> (2026-08-16). Varje undantag —
     * timeout, 429, 5xx — svaldes och kom tillbaka som {@code ""}. Parsern gjorde då en tom lista
     * av det, uppslaget returnerade {@code null}, och generationsifyllningen kunde inte skilja det
     * från ett svar vi förstått. Den antecknade alltså ett <b>nej</b> och parkerade modellen i 30
     * dagar för något som var ett nätverksfel.
     *
     * <p>Löftet fanns redan i {@code AutoDataCargoFillService.fyllGenerationsar}: ett undantag ska
     * inte antecknas som miss, bara ett svar vi förstått. Det löftet gick inte att hålla så länge
     * felet aldrig nådde dit som ett undantag. Båda nattjobbens loopar fångar per modell, så en
     * trasig sida fäller fortfarande aldrig hela körningen.
     *
     * <p>Felet cachas inte heller längre. Förut låste ett {@code ""} fast sidan som tom för resten
     * av körningen, så ett enda hicka-svar tidigt kunde döma varje modell som delade märkessida.
     */
    String hamta(String url) {
        String cachad = sidcache.get(url);
        if (cachad != null) return cachad;
        try {
            Thread.sleep(PAUS_MS);
            String html = Jsoup.connect(url).userAgent(UA).timeout(TIMEOUT_MS).get().html();
            sidcache.put(url, html);
            return html;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HamtningsFel(url, e);
        } catch (Exception e) {
            log.warn("auto-data: kunde inte hämta {} — {}", url, e.getMessage());
            throw new HamtningsFel(url, e);
        }
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
     * modellens NUVARANDE generation, så det är just den senaste vi vill datera. Karosskravet är
     * avstängt och seriealiaset påslaget — se {@link #valjGeneration(List, String, Integer, boolean)}
     * respektive {@link #uppslagsnamn}.
     */
    public Generation generationForNamn(String bilnamn) {
        String uppslag = uppslagsnamn(bilnamn);
        return valjGeneration(generationerFor(uppslag), uppslag, null, false);
    }

    /**
     * Motoralternativen för generationsprövningen, med samma tolerans som {@link #generationForNamn}.
     *
     * <p>Egen ingång därför att {@link #motorerForBil} används av bagagehämtningen, som måste ha
     * kvar karosskravet. Utan den här hade hästkraftsjämförelsen i generationsifyllningen fått
     * en tom lista för varje modell som räddas av toleransen — och en tom lista räknas som
     * hämtningsfel, alltså hade årtalet sparats <b>utan</b> att ha prövats mot vår motorlista.
     */
    public List<MotorAlternativ> motorerForGenerationsprovning(String bilnamn) {
        Generation gen = generationForNamn(bilnamn);
        return gen == null ? List.of() : parseMotorAlternativ(hamta(BAS + gen.sokvag()));
    }

    /**
     * Bilnamnet som auto-data känner igen det, eller namnet oförändrat.
     *
     * <p>Bara BMW:s serier hittills — se {@link #BMW_SERIEBETECKNING}. Översättningen används
     * genomgående i uppslaget och inte bara vid modellvalet: titelkontrollen jämför mot samma
     * namn, och "BMW 3 Series Sedan" ryms inte i "BMW 320d".
     */
    static String uppslagsnamn(String bilnamn) {
        if (bilnamn == null) return null;
        Matcher m = BMW_SERIEBETECKNING.matcher(normalisera(bilnamn));
        return m.matches() ? "bmw " + m.group(1) + " series" : bilnamn;
    }

    /**
     * Startåret för den generation motorlistan beskriver — <b>faceliftens år räknas inte</b>.
     *
     * <p>Uppmätt 2026-08-14: auto-datas senaste generation är nästan alltid en facelift, och dess
     * {@code franAr} är faceliftens år, inte generationens. Golf VIII står som
     * "Volkswagen Golf VIII (facelift 2024)" med start 2024 fast generationen kom 2020; Kia
     * Sportage V likaså 2024 mot 2021, Volvo XC60 II 2025 mot 2017. Sparas faceliftåret fäller
     * vakten varje kort från 2020–2023 — modeller där listan hade varit helt riktig.
     *
     * <p>Det är inte ett litet fel: vakten avstår hellre än gissar, så överblockering syns aldrig
     * som ett felaktigt värde utan som ett <b>tomt fält</b>, och sedan 2026-08-14 tappar kortet
     * dessutom förbrukning, drivmedel och hk i samma svep.
     *
     * <p>Därför tas det MINSTA startåret bland generationerna med samma <b>chassikod</b>, alltså
     * 8S oavsett kaross och facelift. Ett för lågt årtal är den ofarliga riktningen: då visas
     * listan som förut. Ett för högt tystar en bil som hade fått rätt svar.
     *
     * <p><b>Utan chassikod går det inte att ta gruppens minsta</b> (2026-08-18). Bastiteln är då
     * enda nyckeln, och den återanvänds mellan generationer som ligger tjugo år isär: Volvo skriver
     * "Volvo S90 (facelift 2020)", "Volvo S90 (2016)" och — för den ombadgade 960:an — bara
     * "Volvo S90" 1997–1998. Alla tre får samma bastitel, och gruppens minsta blev <b>1997</b> för
     * en motorlista som beskriver B4/B5/B6/T8, alltså andra generationen. Samma sak för V90 (1996).
     * Ett fel årtal är farligare här än på ytan: {@code matchByTitle} ärver filtret, så en S90 från
     * 1997 hade fått 2016 års motorer.
     *
     * <p>Lösningen är att följa <b>faceliftkedjan</b> i stället: en faceliftrad pekar bakåt på den
     * rad vars årsspann SLUTAR där faceliftens börjar, och kedjan stannar på första raden som inte
     * själv är en facelift. Det är generationens start. Namnet 1997 nås aldrig — dess spann slutar
     * 1998, inte 2016. Kedjan går inte att använda för kodgrupperna: "Audi TT RS Coupe (8S)" är
     * ingen facelift utan en undermodell, och den skulle stanna på sitt eget 2016 i stället för
     * generationens 2014. Därför bär de två nyckelsorterna var sin regel.
     *
     * @return startåret, eller null när modellen eller årtalet inte gick att slå upp
     */
    public Integer basgenerationsStartAr(String bilnamn) {
        String uppslag = uppslagsnamn(bilnamn);
        return basgenerationsStartAr(generationerFor(uppslag), uppslag);
    }

    /** Samma regel utan hämtning, så den kan provas mot en sparad modellsida. */
    public static Integer basgenerationsStartAr(List<Generation> alla, String bilnamn) {
        Generation senaste = valjGeneration(alla, bilnamn, null, false);
        if (senaste == null) return null;

        String nyckel = grupperingsnyckel(senaste.titel());
        List<Generation> gruppen = new ArrayList<>();
        for (Generation g : alla) if (nyckel.equals(grupperingsnyckel(g.titel()))) gruppen.add(g);

        // Chassikoden ÄR generationens identitet hos auto-data — samma kod kan inte betyda två
        // generationer, så gruppens minsta år är generationens start. Det är den regeln som ger
        // Audi TT (8S) 2014 i stället för RS-versionens 2016.
        if (nyckel.startsWith(KOD_PREFIX)) {
            Integer minsta = senaste.franAr();
            for (Generation g : gruppen) {
                if (g.franAr() != null && (minsta == null || g.franAr() < minsta)) minsta = g.franAr();
            }
            return minsta;
        }
        return faceliftKedjansStart(senaste, gruppen);
    }

    /**
     * Startåret för den generation {@code senaste} är en facelift av, hittat genom att följa
     * kedjan bakåt rad för rad.
     *
     * <p>Steget bakåt kräver att den föregående radens spann <b>slutar där faceliftens börjar</b>.
     * Det är skarvet som skiljer generationens egen basrad från en namne i en annan epok, och det
     * är hela poängen: Volvo XC90 II (facelift 2024) → (facelift 2019) → XC90 II 2015 stannar på
     * 2015 och når aldrig XC90 2002, trots att den raden ligger på samma modellsida.
     *
     * <p>Kedjan stannar också på första rad som inte själv bär en faceliftmarkering — en
     * generations basrad är per definition ingen facelift, och därunder finns bara äldre
     * generationer. Går inget steg att ta står faceliftens eget år kvar, vilket är samma svar
     * som före 2026-08-14 och alltså inget nytt fel.
     */
    private static Integer faceliftKedjansStart(Generation senaste, List<Generation> gruppen) {
        Generation nuvarande = senaste;
        // Varje steg sänker franAr strikt och gruppen är ändlig, så loopen kan inte snurra.
        while (arFacelift(nuvarande.titel()) && nuvarande.franAr() != null) {
            Generation foregaende = null;
            for (Generation g : gruppen) {
                if (g == nuvarande || g.franAr() == null || g.franAr() >= nuvarande.franAr()) continue;
                // Öppet slut hör bara den nyaste raden till, och den är redan vår utgångspunkt.
                if (g.tillAr() == null) continue;
                if (Math.abs(g.tillAr() - nuvarande.franAr()) > FACELIFT_SKARV_AR) continue;
                if (foregaende == null || g.franAr() > foregaende.franAr()) foregaende = g;
            }
            if (foregaende == null) break;
            nuvarande = foregaende;
        }
        return nuvarande.franAr();
    }

    /**
     * Så många år får skarvet mellan en generation och dess facelift glappa.
     *
     * <p>Auto-data skriver oftast samma årtal på båda sidor ("Golf VIII" 2020–2024 möter
     * "Golf VIII (facelift 2024)"), men modellåret och kalenderåret går isär ibland. Ett år
     * räcker för det; två hade börjat nå föregående generation på modeller med korta liv.
     */
    private static final int FACELIFT_SKARV_AR = 1;

    /** Sant när titeln bär auto-datas faceliftmarkering, oavsett var i parentesen den står. */
    static boolean arFacelift(String titel) {
        return titel != null && FACELIFTORD.matcher(titel).find();
    }

    private static final Pattern FACELIFTORD = Pattern.compile("(?i)\\b(facelift|restyling)\\b");

    /** Prefixet som skiljer en chassikodsnyckel från en bastitelsnyckel i {@link #grupperingsnyckel}. */
    private static final String KOD_PREFIX = "kod:";

    /**
     * Nyckeln som samlar en generations alla rader: chassikoden när den finns, annars bastiteln.
     *
     * <p><b>Varför koden vinner över titeln.</b> Bastiteln skiljer karosserna åt, och det är rätt
     * för bagage men fel för ett årtal: "Audi TT Coupe (8S)" från 2014 och "Audi TT RS Coupe (8S)"
     * från 2016 är samma generation, men olika bastitlar. Årtalet blev då undermodellens 2016, och
     * ett för högt årtal tystar varje kort mellan generationens start och det året — precis den
     * överblockering som {@link #basgenerationsStartAr} finns för att undvika. Chassikoden är
     * generationens egen identitet hos auto-data och samlar både karosser och faceliftar: 8S ger
     * 2014, XE30 ger 2013, B9 ger 2017, G20 ger 2018.
     *
     * <p>Saknas kod faller vi tillbaka på bastiteln, som förut — Volkswagen skriver "Golf VIII
     * (facelift 2024)" utan kod, och där gör generationsnumret samma jobb (2020).
     */
    private static String grupperingsnyckel(String titel) {
        String kod = chassiKod(titel);
        return kod != null ? KOD_PREFIX + kod : basTitel(titel);
    }

    /**
     * Chassikoden ur titelns parentes: "BMW 3 Series Sedan (G20 LCI, facelift 2022)" → "g20".
     *
     * <p>Bara det första ordet i parentesen räknas, och bara om det bär både bokstav och siffra —
     * så "(facelift 2024)" ger null och BMW:s faceliftmarkering "LCI" följer inte med in i
     * nyckeln, vilket är det som får G20 och G20 LCI att hamna i samma generation.
     */
    static String chassiKod(String titel) {
        if (titel == null) return null;
        Matcher m = CHASSIKOD.matcher(titel);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private static final Pattern CHASSIKOD = Pattern.compile(
            "(?i)\\(\\s*((?=[a-z0-9]*[a-z])(?=[a-z0-9]*\\d)[a-z]{0,3}\\d{1,3}[a-z]{0,2})\\b");

    /**
     * Generationstiteln utan faceliftmarkering: "Skoda Octavia IV (facelift 2024)" → "skoda octavia iv".
     *
     * <p><b>Chassikoden måste bort här också</b> (2026-08-16). Regeln letade efter en parentes som
     * <i>börjar</i> med "facelift", vilket bara stämmer när koden saknas. Auto-data skriver oftare
     * "Lexus IS III (XE30, facelift 2020)", och då matchade inget: faceliftversionen fick en egen
     * bastitel, gruppen blev ensam och {@link #basgenerationsStartAr} returnerade faceliftens år.
     * Det är exakt felet metoden skrevs för att hindra — Golf VIII som 2024 i stället för 2020 —
     * bara på de titlar där koden råkade stå först i parentesen.
     *
     * <p><b>Chassikoden ska däremot vara kvar här</b>, till skillnad från i {@link #TITELBRUS}.
     * Den är generationens identitet: BMW skiljer inte sina generationer med romerska siffror utan
     * bara med kod, så "3 Series Sedan (G20)" och "3 Series Sedan (F30)" blir samma bastitel om
     * koden skalas bort — och {@link #basgenerationsStartAr} tar det minsta årtalet i gruppen,
     * alltså F30:s 2011 för en G20 från 2018. Samma fälla som att slå ihop Golf VII och VIII.
     * Modellbeteckningar som "XC60" är dessutom kodformade, och de måste självklart stå kvar.
     *
     * <p><b>Ett ensamt årtal i parentesen ska också bort</b> (2026-08-18). Där generationsnumret
     * saknas daterar auto-data raden i stället: "Volvo S90 (2016)" är basgenerationen till
     * "Volvo S90 (facelift 2020)". Med årtalet kvar blev de två olika bastitlar, faceliftraden
     * hamnade ensam i sin grupp — tillsammans med den ombadgade "Volvo S90" från 1997, som inte
     * har någon parentes alls. Årtalet är auto-datas datering, inte en del av modellnamnet, och
     * med det bortskalat hittar {@link #basgenerationsStartAr} rätt basrad. Att 1997:an nu ligger
     * i samma grupp är ofarligt: faceliftkedjan kräver att spannen möts, och 1998 möter inte 2016.
     *
     * <p>Kvar blir alltså precis faceliftmarkeringen och dateringen, oavsett om de står ensamma i
     * parentesen eller efter en kod — och romerska siffror rörs aldrig, eftersom generationsnumret
     * är hela poängen med en bastitel.
     */
    static String basTitel(String titel) {
        if (titel == null) return "";
        String utan = titel.replaceAll("(?i),?\\s*\\b(facelift|restyling)\\b\\s*\\d{0,4}", " ");
        // Efter faceliftorden kan parentesen stå kvar med bara ett årtal i sig, både när den
        // började så ("S90 (2016)") och när ordet skalats bort ur den ("XC60 I (2013 facelift)").
        return normalisera(utan.replaceAll("\\(\\s*(?:19|20)\\d{2}\\s*\\)", " "));
    }

    /** Märkes- och modelluppslaget, delat av ingångarna ovan. */
    private Generation generationForBil(String bilnamn, Integer arsmodell) {
        return valjGeneration(generationerFor(bilnamn), bilnamn, arsmodell);
    }

    /** Alla generationer på modellens sida, tom lista när märket eller modellen inte hittas. */
    private List<Generation> generationerFor(String bilnamn) {
        if (bilnamn == null || bilnamn.isBlank()) return List.of();

        Map<String, String> marken = parseMarken(hamta(BAS + "/en/volkswagen-brand-80"));
        String markessida = markessidaFor(bilnamn, marken);
        if (markessida == null) {
            log.debug("auto-data: inget märke matchar {}", bilnamn);
            return List.of();
        }

        String modellsida = modellsidaFor(bilnamn, parseModeller(hamta(markessida)));
        if (modellsida == null) {
            log.debug("auto-data: ingen modell matchar {}", bilnamn);
            return List.of();
        }
        return parseGenerationer(hamta(modellsida));
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
