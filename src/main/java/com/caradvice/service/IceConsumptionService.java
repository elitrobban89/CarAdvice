package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifierade förbrukningssiffror för bensin-/diesel-/hybridbilar (l/mil).
 * Datat kommer från Bilresa-projektets handkodade fordonsdatabas (ice-consumption.csv,
 * ~950 motorvarianter) och används dels av GET /api/ice-consumption (Bilresas kalkylator),
 * dels för att ersätta AI:ns gissade consumptionLiterPerMil med verifierade värden.
 * Ren JdbcTemplate utan JPA-entitet — samma mönster som new_car_price (validate-fällan).
 */
@Service
public class IceConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(IceConsumptionService.class);
    private static final Pattern HP_PATTERN = Pattern.compile("(\\d{2,4})\\s*hk");

    public record Variant(String brand, String variant, String fuel, double literPerMil) {}

    private final JdbcTemplate jdbc;

    // Tabellen är statisk seed-data — cachas i minnet efter första läsningen
    private volatile List<Variant> cachedAll = null;

    /**
     * Generationsårtalen. Sätts via settern och inte konstruktorn för att bryta den cirkel som
     * annars uppstår: ifyllningstjänsten behöver den här servicen för sin arbetslista.
     * Null är ett giltigt läge — då gäller det gamla beteendet, alltså lista alltid.
     */
    private IceGenerationService iceGenerations;

    public IceConsumptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIceGenerations(IceGenerationService iceGenerations) {
        this.iceGenerations = iceGenerations;
    }

    public void ensureTableAndSeed() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ice_consumption (
                brand VARCHAR(50) NOT NULL,
                variant VARCHAR(150) NOT NULL,
                fuel VARCHAR(20) NOT NULL,
                liter_per_mil NUMERIC(4,2) NOT NULL,
                PRIMARY KEY (brand, variant)
            )
            """);

        List<String[]> rows = readCsv();
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM ice_consumption", Integer.class);
        if (existing != null && existing >= rows.size()) return; // redan seedad

        int added = 0;
        for (String[] r : rows) {
            added += jdbc.update("""
                INSERT INTO ice_consumption(brand, variant, fuel, liter_per_mil)
                SELECT ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM ice_consumption WHERE brand = ? AND variant = ?)
                """, r[0], r[1], r[2], Double.parseDouble(r[3]), r[0], r[1]);
        }
        cachedAll = null;
        log.info("ice_consumption seedad — {} nya rader ({} i CSV)", added, rows.size());
    }

    /**
     * Gör tabellen lik CSV:n igen — lägger till nya rader OCH tar bort dem som inte finns kvar.
     *
     * <p><b>Varför seedningen inte räcker.</b> {@link #ensureTableAndSeed()} hoppar över allt så
     * fort tabellen har minst lika många rader som CSV:n, och den enda skrivningen är ett INSERT
     * som redan befintliga nycklar hoppar över. En RÄTTELSE i CSV:n nådde därför aldrig fram:
     * nyckeln är {@code (brand, variant)}, så ett ändrat variantnamn är en ny rad och den gamla
     * blir kvar. Skarpt fall 2026-08-20: "BMW;225e Active Tourer PHEV" saknade hästkraftsuppgift,
     * vilket gjorde effektprovet omöjligt och lät årtalet sparas oprövat (2025 för en bil från
     * 2021). Att rätta CSV:n hade gett två 225e-rader i drift och en motorlista med båda.
     *
     * <p><b>Spärren mot att tömma tabellen på ett läsfel.</b> {@code readCsv} loggar och
     * returnerar en TOM lista när filen inte går att läsa, och en synk som litar på det hade
     * raderat allt. Därför avbryts synken om CSV:n är tom eller om den skulle ta bort mer än en
     * tiondel av tabellen — en så stor rensning är ett medvetet ingrepp och ska inte kunna ske
     * som sidoeffekt av ett fel. Samma lärdom som fail-soft-tomsträngen gav.
     *
     * @return {@code tillagda}, {@code borttagna}, {@code total} — eller {@code avbrutet} med skäl
     */
    public Map<String, Object> synkaFranCsv() {
        List<String[]> rows = readCsv();
        Map<String, Object> ut = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            ut.put("avbrutet", "CSV:n gav noll rader — läsfel, inte en tom fil");
            return ut;
        }

        Set<String> iCsv = new HashSet<>();
        for (String[] r : rows) iCsv.add(r[0] + "\0" + r[1]);

        List<Map<String, Object>> befintliga =
                jdbc.queryForList("SELECT brand, variant FROM ice_consumption");
        List<String[]> attTaBort = befintliga.stream()
                .map(r -> new String[]{(String) r.get("brand"), (String) r.get("variant")})
                .filter(r -> !iCsv.contains(r[0] + "\0" + r[1]))
                .toList();

        if (attTaBort.size() * 10 > befintliga.size()) {
            ut.put("avbrutet", "synken ville ta bort " + attTaBort.size() + " av "
                    + befintliga.size() + " rader — över tiondelen, görs inte automatiskt");
            return ut;
        }

        int tillagda = 0;
        for (String[] r : rows) {
            tillagda += jdbc.update("""
                INSERT INTO ice_consumption(brand, variant, fuel, liter_per_mil)
                SELECT ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM ice_consumption WHERE brand = ? AND variant = ?)
                """, r[0], r[1], r[2], Double.parseDouble(r[3]), r[0], r[1]);
        }
        int borttagna = 0;
        for (String[] r : attTaBort) {
            borttagna += jdbc.update(
                    "DELETE FROM ice_consumption WHERE brand = ? AND variant = ?", r[0], r[1]);
        }
        cachedAll = null;
        log.info("ice_consumption synkad mot CSV — {} tillagda, {} borttagna", tillagda, borttagna);
        ut.put("tillagda", tillagda);
        ut.put("borttagna", borttagna);
        ut.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM ice_consumption", Integer.class));
        return ut;
    }

    /**
     * Karossen per modell, last ur CSV:ns femte kolumn. Tom nar vi inte VET.
     *
     * <p><b>Varfor den bor i minnet och inte i tabellen.</b> Samma skal som generationsaren:
     * {@code ice_consumption} skapas med rent SQL och bar fyra kolumner i drift, och en femte
     * hade krävt en migration pa en tabell som ar ren seed-data. Kolumnen las ur CSV:n vid
     * forsta fragan och ar oforanderlig darefter - precis som raderna sjalva.
     *
     * <p><b>Tomt ar ett riktigt svar, inte ett fel.</b> CSV:n bar "BMW;320d 2.0 190 hk" utan att
     * saga sedan eller Touring, och de tva skiljer 100 liter. En rad utan kaross beter sig exakt
     * som fore kolumnen fanns: bagageuppslaget far inte oversatta namnet till serien, och hittar
     * darfor ingen generation alls. Se {@code AutoDataScraperService.generationForBil}.
     */
    private volatile Map<String, String> karossPerModell = null;

    /** Vara karossord. Allt annat i kolumnen ar ett stavfel och behandlas som tomt. */
    private static final java.util.Set<String> KAROSSORD =
            java.util.Set.of("halvkombi", "sedan", "kombi", "suv", "coupe", "cab", "mpv", "pickup");

    /**
     * Karossen for ett {@link #allModelNames}-namn, eller null nar CSV:n inte vet.
     *
     * <p>Rader som sager emot varandra raknas som ett icke-svar: samma modellord kan bara ha en
     * kaross i den har tabellen, och tva olika varden betyder att nagon skrivit fel - inte att
     * bilen har tva karosser.
     *
     * <p><b>Ordet ar en uppslagsnyckel mot auto-datas karossrad, inte kategorin SUV i
     * rekommendationerna.</b> Kolumnen valjer vilken av modellens karosser vi hamtar
     * bagagevolymen fran; hur hog en bil maste vara for att kallas SUV mot anvandaren avgors
     * pa ett helt annat stalle (GroqService.requireSuvShapedCars).
     */
    public String karossForModell(String modellnamn) {
        Map<String, String> karta = karossPerModell;
        if (karta == null) {
            karta = new java.util.HashMap<>();
            java.util.Set<String> motsagda = new java.util.HashSet<>();
            for (String[] r : readCsv()) {
                if (r.length < 5) continue;
                String kaross = r[4].trim().toLowerCase();
                if (!KAROSSORD.contains(kaross)) continue;
                String namn = r[0] + " " + modelWord(new Variant(r[0], r[1], r[2], 0));
                String tidigare = karta.put(namn, kaross);
                if (tidigare != null && !tidigare.equals(kaross)) motsagda.add(namn);
            }
            for (String namn : motsagda) karta.remove(namn);
            if (!motsagda.isEmpty())
                log.warn("ice-consumption.csv: {} modeller har motstridig kaross och raknas som okand: {}",
                        motsagda.size(), motsagda);
            karossPerModell = karta;
        }
        return modellnamn == null ? null : karta.get(modellnamn);
    }

    private List<String[]> readCsv() {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("ice-consumption.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("brand;")) continue;
                String[] f = line.split(";");
                // Femte kolumnen (kaross) ar frivillig och lases av karossForModell - fyra falt
                // maste fortsatta duga, annars tomdes tabellen av en CSV utan kolumnen.
                if (f.length == 4 || f.length == 5) rows.add(f);
            }
        } catch (Exception e) {
            log.error("Kunde inte läsa ice-consumption.csv: {}", e.getMessage());
        }
        return rows;
    }

    public List<Variant> findAll() {
        List<Variant> all = cachedAll;
        if (all == null) {
            all = jdbc.queryForList("SELECT brand, variant, fuel, liter_per_mil FROM ice_consumption")
                    .stream().map(r -> new Variant(
                            (String) r.get("brand"), (String) r.get("variant"),
                            (String) r.get("fuel"), ((Number) r.get("liter_per_mil")).doubleValue()))
                    .toList();
            cachedAll = all;
        }
        return all;
    }

    /**
     * Distinkta "märke modellord"-kombinationer (t.ex. "Volvo V60", "Škoda Octavia") ur
     * ice_consumption — kompletterar cargo_spec/ev_spec-whitelisten med rena ICE-modeller
     * för GroqServices modellhallucinationsvakt.
     */
    public java.util.Set<String> allModelNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Variant v : findAll()) {
            names.add(v.brand() + " " + modelWord(v));
        }
        return names;
    }

    /**
     * Delar ett {@link #allModelNames}-namn i märke och modellord, t.ex.
     * {@code "Alfa Romeo giulia"} → {@code ["Alfa Romeo", "giulia"]}.
     *
     * <p><b>Varför den frågar tabellen i stället för att dela på blanksteg.</b> Märket kan vara
     * flerordigt — "Alfa Romeo", "Land Rover", "Mercedes-Benz" — och en delning på första
     * blanksteget hade gett märket "Alfa" och modellen "Romeo giulia". Namnet byggs av
     * {@code brand() + " " + modelWord()}, så den enda pålitliga vägen tillbaka är att fråga
     * samma rader som byggde det.
     *
     * @return tvåelementsfält, eller null när namnet inte finns i tabellen
     */
    public String[] delaModellnamn(String modelName) {
        if (modelName == null) return null;
        for (Variant v : findAll()) {
            String ord = modelWord(v);
            if ((v.brand() + " " + ord).equalsIgnoreCase(modelName)) {
                return new String[] { v.brand(), ord };
            }
        }
        return null;
    }

    /**
     * Effekterna vi har för en modell, i {@link #allModelNames()}-form ("Volkswagen golf").
     *
     * <p>Används av generationsifyllningen för att avgöra om auto-datas "senaste generation"
     * verkligen är den vår lista beskriver: delar de inte en enda hästkraftssiffra är det troligen
     * två olika generationer, och då ska inget årtal sparas. Uppmätt fall 2026-08-14: Mazda CX-5
     * III lanserades 2025 med en enda motor (141 hk) medan vår CSV bär CX-5 II:s sju varianter
     * (150-230 hk) — ett sparat 2025 hade tystat varje CX-5-kort från 2017 till 2024.
     */
    public java.util.Set<Integer> effekterForModell(String modelName) {
        java.util.Set<Integer> ut = new java.util.HashSet<>();
        if (modelName == null) return ut;
        for (Variant v : findAll()) {
            if (!(v.brand() + " " + modelWord(v)).equalsIgnoreCase(modelName)) continue;
            Integer hk = parseHp(v.variant());
            if (hk != null) ut.add(hk);
        }
        return ut;
    }

    /**
     * Modellordet i variant-strängen — hoppar över en inledande upprepning av märkesnamnet
     * (t.ex. "Mazda 3 2.0 Skyactiv-X 186 hk" → "3", inte "mazda"). Utan detta matchar en rad
     * som "Mazda 3 ..." ALLA Mazda-titlar (modellordet "mazda" finns per definition redan i
     * titeln via märkeskontrollen) — skarpt fall: Mazda CX-5 fick Mazda 3:ans Skyactiv-X-motor
     * i engineOptions. Samma mönster för DS (alla rader) och MG ZS/HS.
     */
    private static String modelWord(Variant v) {
        String[] words = normalize(v.variant()).split("\\s+");
        if (words.length > 1 && words[0].equals(normalize(v.brand()))) return words[1];
        return words[0];
    }

    /**
     * Titeln uppdelad i hela ord — modellordet måste matcha ETT av dessa exakt, inte bara
     * förekomma nånstans i titelsträngen. Utan detta ger korta/numeriska modellord falska
     * träffar: "3" (Mazda 3) är en substräng av "cx-30" (Mazda CX-30), "6" (Mazda 6) av
     * "cx-60" — en CX-30-titel skulle annars kunna få Mazda 3:ans motor och tvärtom.
     */
    private static java.util.Set<String> titleTokens(String t) {
        return new java.util.HashSet<>(java.util.Arrays.asList(t.split("\\s+")));
    }

    /** Rader för GET /api/ice-consumption — carName = "märke variant" som i /api/ev-consumption. */
    public List<Map<String, Object>> listForApi() {
        return findAll().stream()
                .map(v -> Map.<String, Object>of(
                        "carName", v.brand() + " " + v.variant(),
                        "fuel", v.fuel(),
                        "literPerMil", v.literPerMil()))
                .toList();
    }

    /**
     * Verifierad förbrukning för en rekommenderad bil, t.ex. "Volkswagen Golf (2019)".
     * Kandidater: märket förekommer i titeln OCH variantens modellord (första ordet)
     * förekommer i titeln. Vid flera kandidater väljs närmast hästkraftstal (om angivet),
     * annars medianvarianten.
     *
     * <p><b>Titelns eget drivlineord väger tyngre än fuelPref.</b> "Kia Sportage Hybrid" ÄR en
     * hybrid — det står i namnet — medan fuelPref bara är sökningens önskemål om hela träfflistan.
     * Ett bensinsök får hybridkort (en hybrid tankas ju med bensin), och då filtrerade fuelPref
     * bort modellens hybridrader och lämnade kvar de rena bensinraderna. Hästkraftsvalet plockade
     * sedan den som råkade ha samma effekt: {@code Kia Sportage 1.6 T-GDI 230 hk 4x4} (bensin,
     * 0,92 l/mil) i stället för {@code 1.6 T-GDI HEV 230 hk} (hybrid, 0,68) — samma effekt, fel
     * bil, 35 % fel förbrukning. Sedan 2026-08-14 bär kortet dessutom radens {@code fuel} som
     * VERIFIERAT värde, så felet blev till "bensin" på ett kort som heter Hybrid och ett
     * bensinpris i ägandekostnaden.
     *
     * <p>Saknar modellen den drivlina titeln utlovar faller vi tillbaka på fuelPref som förut —
     * en titel kan inte trolla fram rader vi inte har.
     */
    public Variant consumptionForTitle(String title, Integer horsepower, String fuelPref) {
        return consumptionForTitle(title, horsepower, fuelPref, null);
    }

    /**
     * Som ovan, men tyst när årsmodellen är äldre än den generation tabellen beskriver — samma
     * vakt som {@link #engineOptionsForTitle(String, Integer)}, se {@link IceGenerationService}.
     *
     * <p><b>Varför siffran måste falla med listan.</b> Vakten satt först bara på motorlistan, och
     * det räckte inte: förbrukningen, drivmedlet och hästkrafterna på kortet kommer ur EN rad ur
     * samma generationsblinda tabell. En Kia Sportage (2020) fick gen 5:ans (2022+) siffra märkt
     * som verifierad, och den siffran räknas dessutom om till kronor i ägandekostnaden. Värre
     * ändå: när listan tystnade föll anroparen tillbaka på den fällda radens egen beteckning, så
     * generationen kom in genom bakdörren ändå.
     *
     * <p><b>Treargsvarianten behåller det gamla beteendet med flit.</b> Den används som
     * EXISTENSPRÖVNING — {@code EvSpecService.isKnownEv}, {@code GroqService.isNonEv} och
     * {@code evSpecHorInteHit} frågar "finns namnet som förbränningsbil?" för att avgöra
     * drivlinan. Att svara null där för att bilen är gammal hade gjort en 2018 års Golf till
     * elbil. Generationen säger något om SIFFRAN, inte om vad bilen är.
     */
    public Variant consumptionForTitle(String title, Integer horsepower, String fuelPref, Integer arsmodell) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(CarTitle.stripYear(title));
        java.util.Set<String> tokens = titleTokens(t);

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            if (tokens.contains(modelWord(v))) candidates.add(v);
        }
        if (candidates.isEmpty()) return null;
        if (aldreAnGenerationen(candidates, arsmodell)) return null;

        String titelnsBransle = fuelFromTitle(title);
        if (titelnsBransle != null) {
            List<Variant> efterTitel = candidates.stream()
                    .filter(v -> v.fuel().equals(titelnsBransle)).toList();
            if (!efterTitel.isEmpty()) return pickVariant(efterTitel, horsepower);
        }

        // ELEKTRIFIERINGSBADGEN: titeln säger att bilen är laddbar, men inte vilken sort.
        // Skarpt 2026-09-17: ett laddhybridssök gav "Škoda Kodiaq iV" drivmedlet BENSIN och
        // bensinbilens förbrukning, märkt som VERIFIERAT. Tabellen har sju Kodiaq-rader och
        // ingen av dem är iV, så drivmedelsfiltret nedan gav tom lista och föll tillbaka på
        // hela kandidatlistan — alltså bensinraden. Samma skada som 2026-08-14: fel drivmedel
        // räknas vidare till kronor i ägandekostnaden.
        //
        // Badgen kan INTE läggas i drivetrainOf, som annars äger ordlistan: Škodas "iV" sitter
        // på BÅDE laddhybrider (Kodiaq, Octavia, Superb) och elbilar (Enyaq), så ordet säger
        // "elektrifierad" utan att säga vilken sort. Ett naket \biv\b hade dessutom träffat
        // romerska fyror — "Golf IV" och "Passat IV" är riktiga modellnamn, därav märkesvillkoret.
        //
        // Regeln är smal med flit: den säger bara att en REN förbränningsrad aldrig är rätt rad
        // för en laddbar bil. Hittas ingen laddhybridsrad avstår vi helt, och kortet behåller
        // AI:ns egen text — hellre ingen siffra än en verifierad-märkt fel siffra.
        if (barElektrifieringsbadge(title)) {
            List<Variant> laddbara = candidates.stream()
                    .filter(v -> "laddhybrid".equals(v.fuel())).toList();
            return laddbara.isEmpty() ? null : pickVariant(laddbara, horsepower);
        }

        if (fuelPref != null && !fuelPref.isBlank()) {
            String fp = fuelPref.toLowerCase(Locale.ROOT);
            List<Variant> filtered = candidates.stream().filter(v -> v.fuel().equals(fp)).toList();
            if (!filtered.isEmpty()) candidates = filtered;
        }

        return pickVariant(candidates, horsepower);
    }

    /** Närmast angivet hästkraftstal, annars medianvarianten på förbrukning. */
    private static Variant pickVariant(List<Variant> candidates, Integer horsepower) {
        if (horsepower != null && horsepower > 0) {
            return candidates.stream()
                    .min(Comparator.comparingInt(v -> {
                        Integer hp = parseHp(v.variant());
                        return hp == null ? 10_000 : Math.abs(hp - horsepower);
                    }))
                    .orElse(null);
        }
        List<Variant> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(Variant::literPerMil)).toList();
        return sorted.get(sorted.size() / 2);
    }

    /**
     * Drivmedlet som titeln själv utpekar, översatt till tabellens {@code fuel}-värden — null när
     * titeln inte säger något om drivlinan (de allra flesta: "Volkswagen Golf (2019)").
     *
     * <p>Samma markörer som insiktsfiltret och {@code isKnownEv} redan dömer på
     * ({@link ExpertInsightService#drivetrainOf}), så ordlistan står på ETT ställe: "Plug-in
     * Hybrid"/"PHEV" prövas före "Hybrid", annars hade en laddhybrid dömts som självladdande.
     * {@code ev} och {@code ice} ger null med flit — en elbilstitel har inget att hämta här, och
     * "bensin"/"diesel" står praktiskt taget aldrig i en biltitel (motorbeteckningen gör jobbet).
     *
     * <p>Titeln plattas först med {@code flattenSpaces}: AI-titlar bär ibland smalt hårt
     * mellanslag (U+202F), och den fällan har redan kostat en gång i CarTitle.
     */
    /**
     * Bär titeln ett MÄRKESBADGE för laddbar drivlina?
     *
     * <p>Skilt från {@link #fuelFromTitle}, som svarar på VILKEN drivlina titeln utpekar. De här
     * orden säger bara "laddbar" — Volvos {@code Recharge} sitter på både XC60 T8 (laddhybrid)
     * och EX40 (elbil), och Škodas {@code iV} på både Kodiaq (laddhybrid) och Enyaq (elbil).
     * Just därför hör de inte hemma i {@code drivetrainOf}: den lovar en drivlina, och det kan
     * de här orden inte hålla.
     *
     * <p>{@code iV} kräver Škoda i titeln. Ordet är två bokstäver och matchar annars romerska
     * fyror — "Golf IV" och "Passat IV" är riktiga modellnamn, och utan villkoret hade en
     * fjärde generations Golf blivit laddbar.
     */
    static boolean barElektrifieringsbadge(String title) {
        // foldDiacritics FÖRE ordgränsmatchningen: Javas \b är ASCII-definierad, så "\bškoda"
        // matchar ALDRIG — det finns ingen ordgräns framför "š". Provet föll på exakt det med
        // "Škoda Superb iV", och märket stavas med caron i de flesta AI-titlar. Samma familj
        // som U+202F- och U+2011-fällorna: rätt bil, fel teckenkod.
        String t = ExpertInsightService.foldDiacritics(
                ExpertInsightService.flattenSpaces(CarTitle.stripYear(title == null ? "" : title)))
                .toLowerCase(Locale.ROOT);
        if (LADDBAR_BADGE.matcher(t).find()) return true;
        return SKODA_I_TITELN.matcher(t).find() && IV_BADGE.matcher(t).find();
    }

    private static final java.util.regex.Pattern LADDBAR_BADGE =
            java.util.regex.Pattern.compile("\\b(recharge|e-tense|4xe|e-tech)\\b");
    private static final java.util.regex.Pattern SKODA_I_TITELN =
            java.util.regex.Pattern.compile("\\bskoda\\b");
    private static final java.util.regex.Pattern IV_BADGE =
            java.util.regex.Pattern.compile("\\biv\\b");

    private static String fuelFromTitle(String title) {
        String d = ExpertInsightService.drivetrainOf(
                ExpertInsightService.flattenSpaces(CarTitle.stripYear(title)));
        if ("phev".equals(d)) return "laddhybrid";
        if ("hev".equals(d))  return "hybrid";
        return null;
    }

    /**
     * Sant när kortets årsmodell är äldre än den generation kandidatraderna beskriver — då har vi
     * ingenting sant att säga om bilen och ska avstå helt.
     *
     * <p>Regeln står på ETT ställe med flit. Den satt först bara i {@code engineOptionsForTitle},
     * och då kunde samma generationsblinda rad ändå nå kortet via förbrukningssiffran, drivmedlet,
     * hästkrafterna och listans fallback. Alla uppslag som lämnar ifrån sig ett värde ur tabellen
     * ska ställa samma fråga, precis som drivmedelsföreträdet flyttade in i {@code isKnownEv}.
     *
     * <p>Nyckeln byggs ur FÖRSTA kandidaten: alla kandidater delar märke och modellord (det är
     * villkoret för att komma med), så vilken av dem vi frågar på spelar ingen roll — och formen
     * "märke modellord-i-gemener" måste vara identisk med den {@code allModelNames} bygger,
     * annars matchar uppslaget aldrig ifyllningen.
     *
     * <p>Okänd generation eller saknat årtal betyder INGEN ÅSIKT, inte "dölj": tabellen fylls
     * över flera nätter och ett kort får inte tappa sina siffror under tiden.
     */
    private boolean aldreAnGenerationen(List<Variant> candidates, Integer arsmodell) {
        if (arsmodell == null || iceGenerations == null || candidates.isEmpty()) return false;
        Variant forsta = candidates.get(0);
        Integer franAr = iceGenerations.franArFor(forsta.brand() + " " + modelWord(forsta));
        return franAr != null && arsmodell < franAr;
    }

    /**
     * Samtliga verifierade motoralternativ för en modell, som elbilskorten redan visar.
     *
     * <p>Tabellen bär 957 varianter fördelat på 304 modeller, och 197 av dem har fler än en —
     * Volvo XC60 finns som B5, B6, D4, D5, T6 PHEV och T8 PHEV. Kortet visade ändå bara EN,
     * vald av {@link #consumptionForTitle} som den hästkraftsnärmaste, så resten av utbudet var
     * osynligt trots att det låg i databasen.
     *
     * <p>Drivmedelsfiltret är avsiktligt INTE med: motoralternativ är just vad bilen går att få
     * med, och en köpare som tittar på en bensinbil har nytta av att se att samma modell finns
     * som diesel och laddhybrid. Förbrukningssiffran, som ska gälla den bil kortet visar, väljs
     * fortfarande med drivmedelsfilter i {@code consumptionForTitle}.
     *
     * <p>Sorterat på effekt, svagast först — samma ordning som en prislista. Dubbletter på
     * beteckning slås ihop: CSV:n bär samma motor för flera årsmodeller.
     *
     * @return null när modellen saknas i tabellen, så anroparen kan behålla AI:ns egen text
     */
    public String engineOptionsForTitle(String title) {
        return engineOptionsForTitle(title, null);
    }

    /**
     * Som ovan, men tyst när årsmodellen är äldre än den generation listan beskriver.
     *
     * <p>Tabellen bär EN generations motorer per modell — uppmätt 2026-08-13 är samtliga tretton
     * Golf-rader Golf VIII (2020+), och Golf VII har noll rader hos oss. Utan det här filtret
     * fick ett kort för "Volkswagen Golf (2018)" alltså 2020 års motorutbud, inklusive
     * {@code 1.0 eTSI Mild Hybrid} och {@code eHybrid PHEV} som inte fanns då. Det gällde
     * <b>202 av 310 namnplåtar</b>.
     *
     * <p><b>Null i stället för en gissning.</b> Vi kan inte visa rätt lista för en äldre
     * årsmodell — de raderna finns inte i tabellen och att hämta dem okurerat från auto-data
     * hade gett 46 Golf VII-varianter varav flera aldrig sålts i Sverige. Då är rätt svar att
     * avstå: anroparen faller tillbaka på AI:ns egen motortext, precis som för en modell vi
     * saknar helt. Ett tomt påstående är bättre än ett falskt.
     *
     * <p>Saknas årtal i titeln, eller saknar modellen ännu rad i {@code ice_generation}, visas
     * listan som förut. Tabellen fylls över flera nätter och ett kort får inte tappa sina
     * motoralternativ under tiden.
     */
    public String engineOptionsForTitle(String title, Integer arsmodell) {
        return engineOptionsForTitle(title, arsmodell, null);
    }

    /**
     * Som ovan, men bunden till en drivlina — {@code "laddhybrid"} eller {@code "hybrid"}.
     *
     * <p><b>Varför drivmedelsfiltret ändå behövdes.</b> Stycket ovan säger att motoralternativ
     * är just vad bilen går att få med, och det stämmer för ett bensinsök: att se att samma
     * modell finns som diesel är upplysande. För ett LADDHYBRIDSSÖK är det tvärtom fel, för då
     * beskriver listan en annan bil än den kortet visar. Mätt skarpt 2026-08-22: ett SUV-sök på
     * laddhybrid gav <b>Volvo XC60 Recharge</b> med motorlistan "D4 · B4 · D5 · B5 · B6" och
     * <b>Škoda Kodiaq iV</b> med "1.5 TSI · 2.0 TDI" — kortet påstod att en laddhybrid finns
     * som D5 diesel. Ingen av raderna gick att ladda.
     *
     * <p><b>Titelns eget ord vinner över preferensen</b>, samma ordning som
     * {@link #consumptionForTitle}: "Volvo XC60 T8" är en laddhybrid vad sökningen än gällde.
     *
     * <p><b>Saknas raden avstår vi helt i stället för att visa hela utbudet.</b> Det är hela
     * poängen — en tom lista låter kortet behålla AI:ns egen text, medan en oflitrerad lista
     * ser verifierad ut och är fel. Samma linje som generationsvakten: hellre ingen åsikt än
     * en om fel bil.
     *
     * @param drivlina tabellens {@code fuel}-värde att låsa till, eller null för hela utbudet
     */
    public String engineOptionsForTitle(String title, Integer arsmodell, String drivlina) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(CarTitle.stripYear(title));
        java.util.Set<String> tokens = titleTokens(t);

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            if (tokens.contains(modelWord(v))) candidates.add(v);
        }
        if (candidates.isEmpty()) return null;
        if (aldreAnGenerationen(candidates, arsmodell)) return null;

        String titelnsBransle = fuelFromTitle(title);
        String kravd = titelnsBransle != null ? titelnsBransle
                : (drivlina == null || drivlina.isBlank() ? null : drivlina.toLowerCase(Locale.ROOT));
        if (kravd != null) {
            candidates = candidates.stream().filter(v -> v.fuel().equals(kravd)).toList();
            if (candidates.isEmpty()) return null;
        }

        List<String> namn = candidates.stream()
                .sorted(Comparator.comparingInt(v -> {
                    Integer hp = parseHp(v.variant());
                    return hp == null ? Integer.MAX_VALUE : hp;
                }))
                .map(IceConsumptionService::engineDescriptor)
                .distinct()
                .toList();

        return namn.isEmpty() ? null : String.join(" · ", namn);
    }

    /** Kompakt förbrukningsrad för jämförelseprompten: median per drivmedel för modellen. */
    public String consumptionSummaryForTitle(String title) {
        return consumptionSummaryForTitle(title, null);
    }

    /**
     * Som ovan, med samma generationsvakt som kortets siffra — medianen räknas ju på exakt de
     * rader som beskriver fel generation, och det den matar är AI:ns jämförelsetext, alltså
     * något användaren läser. Faller raden bort resonerar modellen utan den, precis som för en
     * bil vi saknar helt.
     */
    public String consumptionSummaryForTitle(String title, Integer arsmodell) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(CarTitle.stripYear(title));
        java.util.Set<String> tokens = titleTokens(t);

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : findAll()) {
            if (!t.contains(normalize(v.brand()))) continue;
            if (tokens.contains(modelWord(v))) candidates.add(v);
        }
        if (candidates.isEmpty()) return null;
        if (aldreAnGenerationen(candidates, arsmodell)) return null;

        // l/100km i prompten — AI:n svarar i den enheten i consumptionLiterPerMil (frontend-konventionen)
        StringBuilder sb = new StringBuilder();
        for (String fuel : new String[]{"bensin", "diesel", "hybrid", "laddhybrid"}) {
            List<Double> values = candidates.stream()
                    .filter(v -> v.fuel().equals(fuel))
                    .map(Variant::literPerMil).sorted().toList();
            if (values.isEmpty()) continue;
            double median = values.get(values.size() / 2);
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format(Locale.forLanguageTag("sv"), "%.1f l/100km (%s)", median * 10, fuel));
        }
        return sb.isEmpty() ? null : "förbrukning ca " + sb;
    }

    static Integer parseHp(String variant) {
        Matcher m = HP_PATTERN.matcher(variant);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * Motorbeteckningen utan modellnamnet i fronten — för att visa som engineOptions utan att
     * upprepa bilens modell (som redan står i titeln på kortet). "CX-5 2.0 Skyactiv-G 165 hk"
     * → "2.0 Skyactiv-G 165 hk"; "Mazda 3 2.0 Skyactiv-X 186 hk" → "2.0 Skyactiv-X 186 hk"
     * (hoppar även över märkesupprepningen, se modelWord()).
     */
    static String engineDescriptor(Variant v) {
        String[] words = v.variant().split("\\s+");
        int skip = (words.length > 1 && words[0].equalsIgnoreCase(v.brand())) ? 2 : 1;
        if (words.length <= skip) return v.variant();
        return String.join(" ", java.util.Arrays.asList(words).subList(skip, words.length));
    }

    /** Samma blankstegsstädning som EvSpecService.normalize — se motiveringen där. */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("š", "s").replace("ë", "e").replace("é", "e")
                .replaceAll("\\p{Cf}", "")
                .replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
}
