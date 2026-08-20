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
     * Modellnamn där vår nyckelform inte går att slå upp hos auto-data, och vad den heter där.
     *
     * <p><b>Varför de aldrig kunde hittas.</b> {@code IceConsumptionService.allModelNames} bygger
     * nyckeln som märke + <b>ett enda</b> modellord — "Mercedes-Benz C 220 d" blir
     * {@code "Mercedes-Benz c"}, "Hyundai Santa Fe" blir {@code "Hyundai santa"}. Enordsformen är
     * med flit och får inte ändras: den finns för att en rad som "Mazda 3 ..." annars matchar
     * varenda Mazda-titel. Men auto-datas modellista bär hela namnet ("C-class", "Santa Fe"), och
     * {@code modellsidaFor} kräver att modellnamnet ryms i bilnamnet — därför föll uppslaget
     * redan på modellsidan, oavsett hur väl resten av kedjan fungerade. Mätt 2026-08-20:
     * <b>27 av 310</b> namnplåtar låg som {@code ej-hittad}, och talet stod stilla genom hela
     * generationsarbetet eftersom orsaken satt före allt det rörde.
     *
     * <p><b>Namnen är avlästa skarpt</b>, inte gissade: varje värde står ordagrant i
     * {@code a.modeli > strong} på märkets sida 2026-08-20. Tre av dem är inte den stavning man
     * hade gissat — Kia skriver <b>"Cee'd"</b> och <b>"Pro Cee'd"</b> med apostrof (som
     * {@code normalisera} gör till blanksteg, alltså "cee d"), Suzuki har ingen naken "SX4" utan
     * bara <b>"SX4 S-Cross"</b>, och Mini har varken "Cooper" eller "John Cooper Works" som
     * modeller — båda är generationer av <b>"Hatch"</b>.
     *
     * <p><b>Tre nycklar lämnas med flit oöversatta</b> därför att de rymmer FLERA bilar och ett
     * årtal per nyckel då blir fel för de andra:
     * <ul>
     *   <li>{@code "Audi rs"} = RS Q3 <i>och</i> RS Q8 (RS3–RS7 har egna nycklar).</li>
     *   <li>{@code "Land Rover range"} = Range Rover, Range Rover Evoque <i>och</i> Range Rover
     *       Sport.</li>
     *   <li>{@code "Toyota gr"} = GR Yaris, som auto-data inte har som modell alls.</li>
     * </ul>
     * Utan årtal är vakten inert och kortet visar listan som förut — det är det ofarliga läget,
     * och det är bättre än att ge en Evoque en Range Rovers generationsår.
     */
    private static final Map<String, String> MODELLALIAS = Map.ofEntries(
            Map.entry("mercedes benz a", "Mercedes-Benz A-class"),
            Map.entry("mercedes benz b", "Mercedes-Benz B-class"),
            Map.entry("mercedes benz c", "Mercedes-Benz C-class"),
            Map.entry("mercedes benz e", "Mercedes-Benz E-class"),
            Map.entry("mercedes benz g", "Mercedes-Benz G-class"),
            Map.entry("mercedes benz s", "Mercedes-Benz S-class"),
            Map.entry("hyundai santa", "Hyundai Santa Fe"),
            Map.entry("jeep grand", "Jeep Grand Cherokee"),
            Map.entry("toyota land", "Toyota Land Cruiser"),
            Map.entry("kia ceed", "Kia Cee'd"),
            Map.entry("kia proceed", "Kia Pro Cee'd"),
            Map.entry("suzuki sx4", "Suzuki SX4 S-Cross"),
            Map.entry("mini cooper", "Mini Hatch"),
            Map.entry("mini john", "Mini Hatch"),
            Map.entry("audi tts", "Audi TT"),
            // Alla fem C5-rader hos oss är C5 Aircross; naken "C5" är en annan bil (2001-2017).
            Map.entry("citroen c5", "Citroen C5 Aircross"));

    /**
     * Märkesindexet — <b>alla</b> märken, inte de 70 som får plats i sidfoten.
     *
     * <p>Sidfoten på en märkes- eller modellsida bär bara de populäraste, och {@code /en/allbrands}
     * är sidans egen länk vidare till resten. Mätt 2026-08-20: <b>70 mot 391</b> märken, och
     * listan är en äkta delmängd — <b>ingen</b> av våra 310 namnplåtar byter märkesträff, fyra
     * får en de aldrig kunde få (Abarth 500/595/695 och Alpine A110, som saknades i sidfoten och
     * därför var omöjliga att slå upp oavsett modellnamn).
     */
    private static final String MARKESINDEX = BAS + "/en/allbrands";

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
     * Märkena i en märkeslista — sidfotens eller {@link #MARKESINDEX}.
     *
     * <p>Samma markup på båda, så den kan hämtas från vilken sida som helst — det gör att vi
     * slipper sitemapen, som är 27 MB fördelat på fyra skärvor och dessutom bär sju språk.
     *
     * <p><b>Men de är inte lika långa.</b> Sidfoten bär bara de 70 populäraste märkena, och det
     * såg ut som hela listan ända tills Abarth och Alpine visade sig saknas där 2026-08-20.
     * Nattjobbet läser därför {@link #MARKESINDEX} (391 märken), inte sidfoten.
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
     * <p>Tre lager, i tur och ordning: märkets stavning, BMW:s serier
     * ({@link #BMW_SERIEBETECKNING}) och modellnamnstabellen ({@link #MODELLALIAS}).
     * Översättningen används genomgående i uppslaget och inte bara vid modellvalet:
     * titelkontrollen jämför mot samma namn, och "BMW 3 Series Sedan" ryms inte i "BMW 320d".
     */
    static String uppslagsnamn(String bilnamn) {
        if (bilnamn == null) return null;
        String namn = asciiMarke(bilnamn);
        Matcher m = BMW_SERIEBETECKNING.matcher(normalisera(namn));
        if (m.matches()) return "bmw " + m.group(1) + " series";
        return MODELLALIAS.getOrDefault(normalisera(namn), namn);
    }

    /**
     * Bokstäver som {@link #normalisera} SLÄNGER, utbytta mot den ASCII-form auto-data använder.
     *
     * <p>Auto-datas märkesnycklar kommer ur URL-sluggen och är ren ASCII ({@code citroen}), medan
     * vår CSV skriver märket som det stavas ({@code Citroën}). {@code normalisera} släpper igenom
     * {@code åäöéèü} men inte {@code ë} — den blir ett blanksteg, så "Citroën C3" blev
     * <b>"citro n c3"</b> och matchade inget märke alls. Alla fem Citroën-rader låg därför som
     * {@code ej-hittad} 2026-08-20, och felet satt i en teckenklass, inte i modellnamnet.
     *
     * <p>Bytet är smalt med flit: bara tecken vi mätt i vår egen data står här. Nästa märke med
     * en bokstav utanför allow-listan faller på exakt samma sätt och ska läggas till här — den
     * generella riktningen (fälla ALLA diakriter) är fel, för {@code åäö} bär betydelse i de
     * svenska namn samma metod passerar.
     */
    private static String asciiMarke(String bilnamn) {
        return bilnamn.replace('ë', 'e').replace('Ë', 'E');
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
    /**
     * Hur många generationer effektprovet får hämta innan det ger upp.
     *
     * <p>Kostnaden är {@link #PAUS_MS} per sida som inte redan ligger i {@link #sidcache}, och
     * betalas bara av modeller vars NYASTE generation inte matchar — de som stämmer direkt kostar
     * som förut. Taket är mätt mot de familjer som faktiskt föll 2026-08-19: BMW 5-serien har 29
     * generationer på sin modellsida, och G30 LCI — den vars motorlista bär vår {@code 520d}
     * 190 hk — ligger på plats <b>3</b> sorterat nyast först. 7-serien landar på plats 3-4 och
     * 3-seriens {@code 330i} på plats 2. Sex ger marginal utan att en modell som saknas helt ska
     * hinna hämta halva sajten.
     */
    static final int MAX_GENERATIONER_PROV = 6;

    /** Vad effektprovet kom fram till. Se {@link #artalMedEffektprov}. */
    public enum Provutfall {
        /** En generation delar hästkraft med vår motorlista — årtalet är bevisat. */
        TRAFF,
        /** Ingen av de prövade generationerna delade hästkraft — vi vet inte, och gissar inte. */
        INGEN_TRAFF,
        /** Ingen motorlista gick att läsa. Ett hämtningsfel är inget bevis, varken för eller emot. */
        OPROVAD
    }

    /** Årtalet och hur det bevisades. {@code franAr} är null när modellen inte gick att slå upp. */
    public record Artalsprov(Integer franAr, Provutfall utfall, String generation) {}

    /**
     * Startåret för den generation vars motorlista delar hästkraft med vår — inte nödvändigtvis
     * den nyaste.
     *
     * <p><b>Varför den nyaste inte räcker</b> (2026-08-19). Uppslaget prövade bara auto-datas
     * senaste generation, och {@code ice_consumption} är kurerad för den svenska marknaden vid en
     * viss tidpunkt. När BMW bytte generation på 5-serien (G60/G61, 2023-24) och 7-serien (G70,
     * 2022) blev vår motorlista äldre än sidans, hästkraftsproven blev disjunkta, och <b>hela
     * serier</b> parkerades som {@code vakten-avstod} — 35 av 111 avståenden var BMW, med 0 av 8
     * i 5-serien och 0 av 7 i sjuan. Datan fanns hela tiden på auto-data; vi tittade bara på sista
     * sidan av den. Vår {@code 520d 2.0 D 190 hk} står i G30 LCI, som ger 2017 via samma
     * grupperings- och faceliftregel som förut.
     *
     * <p><b>Vakten blir inte svagare av det här, den blir träffsäkrare.</b> Årtalet sparas
     * fortfarande bara när en motorlista bär vår hästkraft; skillnaden är att frågan nu ställs
     * till varje generation i tur och ordning i stället för bara till den första. Det som förut
     * blev ett nej blir ett ja på RÄTT generation — och landar vi fel kaross ({@code 520d} valde
     * förut kombin G61 och hade fått 2024) faller den kandidaten nu på hästkrafterna i stället för
     * att bli hela svaret.
     *
     * @param varaEffekter hästkrafterna ur vår egen motorlista; tom mängd stänger av provet
     */
    public Artalsprov artalMedEffektprov(String bilnamn, java.util.Set<Integer> varaEffekter) {
        return artalMedEffektprov(bilnamn, varaEffekter, varaEffekter);
    }

    /**
     * Samma prov, men med FAMILJENS hela motorlista som skiljedomare när en träff är smal.
     *
     * <p>Familjen är de av våra modellrader som slår upp samma modellsida — "BMW 730i", "740i",
     * "750i", "730d", "740d", "745e" och "M760i" är alla {@code bmw 7 series}. Se
     * {@link #familjTackning} för varför det behövs.
     *
     * @param familjensEffekter hästkrafterna för HELA familjen; samma mängd som
     *                          {@code varaEffekter} stänger av skiljedomaren
     */
    public Artalsprov artalMedEffektprov(String bilnamn, java.util.Set<Integer> varaEffekter,
                                         java.util.Set<Integer> familjensEffekter) {
        String uppslag = uppslagsnamn(bilnamn);
        List<Generation> alla = generationerFor(uppslag);
        // bilnamn, inte uppslag: beteckningen "520d" finns bara i det otranslaterade namnet —
        // uppslagsnamnet är "bmw 5 series" och bär ingen motorbeteckning alls.
        return artalMedEffektprov(alla, uppslag, bilnamn, varaEffekter, familjensEffekter,
                g -> parseMotorAlternativ(hamta(BAS + g.sokvag())));
    }

    /**
     * Så många år isär två generationer får ligga och ändå räknas som samma bil i olika kaross.
     *
     * <p>BMW 3-serien är sedan (G20, 2018) och kombi (G21, 2019); vår rad "BMW 330i 2.0 T 258 hk"
     * saknar karossord och matchar båda. Nyast vinner annars, alltså kombins 2019 — och ett för
     * HÖGT årtal är den dyra riktningen: det tystar 2018 års sedan, som i dag får sin lista.
     * Därför vägs träffar inom fönstret mot varandra och det lägsta startåret vinner.
     */
    static final int NARA_AR = 2;

    /**
     * Samma regel utan nät, så den kan provas mot en sparad modellsida.
     *
     * <p>{@code motorhamtare} slår upp en generations motorlista. Den är en parameter just för
     * att valet ska gå att testa: allt annat i den här klassen som rör nätet är otestat med flit,
     * och den här logiken är för viktig för att hamna i den lådan.
     *
     * @param bilnamn    uppslagsnamnet, för generationsvalet
     * @param radnamn    vårt eget modellnamn, som bär motorbeteckningen ("BMW 520d")
     */
    static Artalsprov artalMedEffektprov(List<Generation> alla, String bilnamn, String radnamn,
                                         java.util.Set<Integer> varaEffekter,
                                         java.util.function.Function<Generation, List<MotorAlternativ>> motorhamtare) {
        return artalMedEffektprov(alla, bilnamn, radnamn, varaEffekter, varaEffekter, motorhamtare);
    }

    /** Samma regel med familjen given. Se {@link #familjTackning}. */
    static Artalsprov artalMedEffektprov(List<Generation> alla, String bilnamn, String radnamn,
                                         java.util.Set<Integer> varaEffekter,
                                         java.util.Set<Integer> familjensEffekter,
                                         java.util.function.Function<Generation, List<MotorAlternativ>> motorhamtare) {
        Generation nyaste = valjGeneration(alla, bilnamn, null, false);
        if (nyaste == null) return new Artalsprov(null, Provutfall.OPROVAD, null);

        // Utan egna hästkrafter finns inget att pröva mot. Då gäller det gamla svaret: den
        // nyaste generationen, oprövad — vår motorlista beskriver normalt den nuvarande bilen.
        if (varaEffekter == null || varaEffekter.isEmpty())
            return new Artalsprov(basgenerationsStartArFor(alla, nyaste), Provutfall.OPROVAD, nyaste.titel());

        List<Generation> kandidater = new ArrayList<>();
        for (Generation g : alla) if (g.franAr() != null) kandidater.add(g);
        kandidater.sort(java.util.Comparator
                .comparingInt((Generation g) -> -g.franAr())
                .thenComparingInt(g -> g.titel().length()));

        String beteckning = motorbeteckning(radnamn);
        java.util.Set<Integer> familjen =
                (familjensEffekter == null || familjensEffekter.isEmpty()) ? varaEffekter : familjensEffekter;
        boolean nagonLista = false;
        Generation basta = null;
        Integer bastaAr = null;
        int bastaTackning = -1;
        int bastaEgenTackning = -1;
        boolean bastaArSmal = false;
        int provade = 0;
        for (Generation g : kandidater) {
            if (provade >= MAX_GENERATIONER_PROV) break;
            // Träff funnen: fortsätt bara så länge kandidaterna kan vara samma bil i annan kaross
            // — eller så länge träffen är SMAL, eller så länge den inte bär HELA vår egen
            // motorlista och alltså kan slås av en generation som bär mer av den.
            if (basta != null && !bastaArSmal && barHelaEgenLista(bastaEgenTackning, varaEffekter)
                    && g.franAr() < basta.franAr() - NARA_AR) break;
            provade++;

            List<MotorAlternativ> deras = motorhamtare.apply(g);
            if (deras.isEmpty()) continue;          // hämtningsfel — säger ingenting om generationen
            nagonLista = true;
            if (!barVaraEffekter(deras, beteckning, varaEffekter)) continue;

            Integer ar = basgenerationsStartArFor(alla, g);
            if (ar == null) continue;

            int tackning = familjTackning(deras, familjen);
            int egenTackning = familjTackning(deras, varaEffekter);
            // Smal = generationen delar inget MER än vår egen rads motorer med familjen, fast
            // familjen har fler att dela med. Se familjTackning.
            boolean smal = familjen.size() > varaEffekter.size() && tackning <= egenTackning;

            boolean battre = basta == null
                    || tackning > bastaTackning
                    // Lika bra täckning: den gamla kombiregeln gäller, och bara inom sitt fönster
                    // — annars hade en lika smal generation ett decennium bort kunnat vinna.
                    || (tackning == bastaTackning && ar < bastaAr && ar >= bastaAr - NARA_AR);
            if (battre) {
                basta = g; bastaAr = ar; bastaTackning = tackning;
                bastaEgenTackning = egenTackning; bastaArSmal = smal;
            }
        }

        if (basta != null) return new Artalsprov(bastaAr, Provutfall.TRAFF, basta.titel());

        // Ingen enda motorlista gick att läsa: det är ett hämtningsfel och inget nej. Samma
        // bedömning som före 2026-08-19 — årtalet står kvar oprövat i stället för att modellen
        // parkeras 30 dagar för något som var nätet.
        if (!nagonLista)
            return new Artalsprov(basgenerationsStartArFor(alla, nyaste), Provutfall.OPROVAD, nyaste.titel());

        return new Artalsprov(null, Provutfall.INGEN_TRAFF, null);
    }

    /**
     * Sant när generationens motorlista bär vår hästkraft <b>på rätt motor</b>.
     *
     * <p><b>Hästkraften ensam är för svag nyckel</b> (mätt 2026-08-19). Vår rad
     * "BMW 520d 2.0 D 190 hk" är en G30-diesel, men G60:s sida från 2023 bär
     * {@code 520i (190 Hp)} — en BENSINARE med samma effekt. Ett prov på bara siffran tog den
     * som träff och hade daterat en 2017-bil till 2023, alltså precis det fel vakten fanns för
     * att stoppa. Bär listan vår beteckning alls jämförs därför bara de motorerna: {@code 520d}
     * finns på båda sidorna, med 197 hk hos G60 och 190 hk hos G30 LCI, och skiljer dem åt.
     *
     *
     * <p><b>Gränsen, mätt 2026-08-19:</b> bär två generationer samma beteckning MED samma
     * hästkraft skiljer provet dem inte åt, och då vinner den nyaste. "BMW 730d 3.0 D 286 hk"
     * finns både som G11 (2015) och G70 (2022) och dateras till 2022, medan resten av sjuan —
     * 730i, 740i, 750i, 740d, 745e — landar på 2015. Att i stället alltid ta det LÄGSTA året vore
     * värre: "BMW 320d 190 hk" finns i både G20 (2018) och F30 (2011), och 2011 hade varit fel
     * för en rad som beskriver dagens bil. Fönstret {@link #NARA_AR} är kompromissen — det tar
     * sedanen framför kombin utan att sträcka sig ett decennium bakåt.
     * <p>Saknas beteckningen helt i listan faller provet tillbaka på ren hästkraft. Det är
     * normalfallet för alla andra märken: vår "Volkswagen golf" heter inte något som liknar
     * "1.5 TSI (150 Hp)", och utan reservregeln hade ingen icke-BMW kunnat dateras alls.
     */
    static boolean barVaraEffekter(List<MotorAlternativ> deras, String beteckning,
                                   java.util.Set<Integer> varaEffekter) {
        java.util.Set<Integer> designerade = new java.util.HashSet<>();
        java.util.Set<Integer> allaHk = new java.util.HashSet<>();
        for (MotorAlternativ m : deras) {
            if (m.hk() == null) continue;
            allaHk.add(m.hk());
            if (beteckning != null && barBeteckningen(m.namn(), beteckning)) designerade.add(m.hk());
        }
        java.util.Set<Integer> jamfor = designerade.isEmpty() ? allaHk : designerade;
        return !java.util.Collections.disjoint(varaEffekter, jamfor);
    }

    /**
     * Hur många av familjens hästkrafter generationens motorlista bär.
     *
     * <p><b>Varför en hel familj måste vara med och bestämma</b> (mätt 2026-08-19). Två
     * generationer kan bära samma beteckning MED samma effekt: "BMW 730d 3.0 D 286 hk" finns
     * både i G11 (2015) och i G70 (2022), och när bara vår egen rad fick rösta vann den nyaste —
     * 730d daterades till 2022 medan resten av sjuan landade på 2015. Ett för högt årtal är den
     * dyra riktningen: det tystar korten för 2015-2021.
     *
     * <p>Familjen avgör saken utan att gissa. Våra sju sjuor är 730i 265, 740i 340, 750i 530,
     * 730d 286, 740d 340, 745e 394 och M760i 585 hk — <b>G11 LCI:s lineup rakt av</b>. G70 delar
     * exakt en av dem, vår egen 730d:s 286, medan G11 LCI delar alla sju. En generation som bara
     * bär vår egen rads motor ur en hel familj den ska beskriva är ett <b>sammanträffande</b>,
     * inte en träff.
     *
     * <p><b>Regeln är med flit smal.</b> Den slår bara till när den nyaste träffen delar
     * ingenting utöver vår egen rad med familjen, och bara när familjen har mer att dela med. Det
     * skyddar det fall som drar åt andra hållet: "BMW 320d 190 hk" finns i både G20 (2018) och
     * F30 (2011), men G20 bär dessutom 318d:ns 150, 330i:ns 258 och 330e:ns 292 — den är alltså
     * inte smal, den gamla regeln får råda och årtalet blir 2018. Utan den spärren hade
     * familjetäckningen kunnat dra hela 3-serien tillbaka till F30.
     *
     * <p>Här jämförs rena hästkrafter utan beteckningsfilter: familjens rader har per definition
     * andra beteckningar än vår egen, och det är just DEM vi letar efter.
     */
    /**
     * Sant när träffen inte bär hela VÅR EGEN rads motorlista och alltså kan vara ett sammanträffande.
     *
     * <p><b>Varför sökningen inte får sluta där.</b> {@code smal} (se {@link #familjTackning})
     * fångar bara familjer som är större än vår egen rad, alltså i praktiken bara BMW:s
     * sifferserier. En modell som är ENSAM om sitt uppslagsnamn har {@code familjen == våra
     * effekter}, så {@code smal} kan aldrig bli sant — och då räckte det att en generation delade
     * EN enda hästkraftssiffra med oss för att vinna, eftersom slingan bröts direkt efter första
     * träffen.
     *
     * <p><b>Mätt 2026-08-20 på hela tabellen</b> (267 modeller, 1 329 sidor): det gav 24 rader ett
     * årtal från en generation som bar <b>1</b> av våra hästkrafter när en annan bar <b>4-6</b>.
     * Mercedes GLC stod på 2026 (X540, en helt ny bil) i stället för 2023, VW Tiguan på 2025 i
     * stället för 2016, BMW X3 på 2025 i stället för 2017 — alla i den dyra riktningen, alltså
     * kort som tystnar. Auto-data publicerar nästa generations sida långt före försäljningsstart,
     * och tillverkare återanvänder effektsiffror mellan generationer, så sammanträffandet är
     * regel och inte undantag.
     *
     * <p><b>Gränsen går vid vår EGEN lista, inte familjens</b>, och det är motprovet som satte
     * den: "BMW 520d 190 hk" mot en familj på fyra effekter träffar G60 som bär 190 och 252 —
     * ofullständigt mot familjen, men vår egen rad har bara 190 och den är HELT täckt. Skulle
     * familjen få avgöra hade varje bred BMW-träff fortsatt leta och dragits bakåt, alltså exakt
     * det fel {@code smal} skrevs för att hindra. Vår egen lista täckt = frågan är besvarad.
     *
     * <p><b>Riktningen är ofarlig i sig.</b> Kandidaterna gås igenom nyast först, så det enda den
     * här vidgningen kan göra är att låta en ÄLDRE generation prövas — årtalet kan bara flyttas
     * bakåt eller stå still, aldrig fram. Vinnaren avgörs sedan av {@code battre} precis som
     * förut, alltså ändras inga oavgjorda fall.
     *
     * <p>Priset är hämtningar: en modell vars bästa träff är ofullständig betar av upp till
     * {@link #MAX_GENERATIONER_PROV} generationssidor i stället för att stanna på första träffen.
     */
    private static boolean barHelaEgenLista(int egenTackning, java.util.Set<Integer> varaEffekter) {
        return egenTackning >= varaEffekter.size();
    }

    static int familjTackning(List<MotorAlternativ> deras, java.util.Set<Integer> effekter) {
        if (effekter == null || effekter.isEmpty()) return 0;
        java.util.Set<Integer> traffar = new java.util.HashSet<>();
        for (MotorAlternativ m : deras) if (m.hk() != null && effekter.contains(m.hk())) traffar.add(m.hk());
        return traffar.size();
    }

    /**
     * Motorbeteckningen i vårt eget modellnamn, eller null när namnet inte bär någon.
     *
     * <p>"BMW 520d" → {@code 520d}. Bara namn som ser ut som en beteckning räknas — en siffra
     * följd av bokstäver, eventuellt med ett inledande M — annars hade "Volkswagen golf" gett
     * beteckningen "golf" och letat efter den bland motornamnen i onödan.
     */
    static String motorbeteckning(String radnamn) {
        if (radnamn == null) return null;
        String[] ord = normalisera(radnamn).split("\\s+");
        String sista = ord[ord.length - 1];
        // M-bilarna har EN siffra ("m5"), sifferbeteckningarna två eller tre ("520d", "118d").
        // Just m5/m3 är dessutom de som behöver beteckningen mest: de är delsträngar av M550i
        // respektive M340i, och utan ordgränsprovet hade de matchat fel motor.
        return sista.matches("(m\\d|\\d{2,3})[a-z]{0,2}") ? sista : null;
    }

    /**
     * Sant när motornamnet bär beteckningen som eget ord.
     *
     * <p>Ordgränsen är inte pedanteri: {@code m5} är en äkta delsträng av {@code M550i}, och utan
     * den hade en M5-rad matchat M550i:s 530 hk och daterats efter fel bil.
     */
    private static boolean barBeteckningen(String motornamn, String beteckning) {
        if (motornamn == null) return false;
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(beteckning) + "(?![a-z0-9])")
                .matcher(normalisera(motornamn)).find();
    }


    public Integer basgenerationsStartAr(String bilnamn) {
        String uppslag = uppslagsnamn(bilnamn);
        return basgenerationsStartAr(generationerFor(uppslag), uppslag);
    }

    /** Samma regel utan hämtning, så den kan provas mot en sparad modellsida. */
    public static Integer basgenerationsStartAr(List<Generation> alla, String bilnamn) {
        return basgenerationsStartArFor(alla, valjGeneration(alla, bilnamn, null, false));
    }

    /**
     * Samma regel, men med generationen given i stället för uppslagen som den nyaste.
     *
     * <p>Delad av det gamla svaret och av {@link #artalMedEffektprov}: hittar effektprovet en
     * ÄLDRE generation ska dess startår räknas ut på precis samma sätt — grupp på chassikod, annars
     * faceliftkedjan. Utan den här hade de två vägarna kunnat ge olika årtal för samma rad.
     */
    static Integer basgenerationsStartArFor(List<Generation> alla, Generation senaste) {
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

        Map<String, String> marken = parseMarken(hamta(MARKESINDEX));
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
    static String markessidaFor(String bilnamn, Map<String, String> marken) {
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
    static String modellsidaFor(String bilnamn, List<Modell> modeller) {
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
