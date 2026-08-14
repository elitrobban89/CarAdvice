package com.caradvice.service;

import com.caradvice.model.EvSpec;
import com.caradvice.model.EvSpecDto;
import com.caradvice.repository.EvSpecRepository;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvSpecService {

    private final EvSpecRepository repo;

    private final IceConsumptionService iceConsumptionService;

    public EvSpecService(EvSpecRepository repo, IceConsumptionService iceConsumptionService) {
        this.repo = repo;
        this.iceConsumptionService = iceConsumptionService;
    }

    /** Alla kända bilnamn i ev_spec — används av GroqServices modellhallucinationsvakt. */
    public List<String> findAllCarNames() {
        return repo.findAllCarNames();
    }

    public EvSpecDto formatForTitle(String title, int kmPerYear) {
        EvSpec match = matchByTitle(title);
        if (match == null) return null;
        String chemistry = null;
        try { chemistry = getBatteryChemistry(title); } catch (Exception ignored) {}
        return toDto(match, kmPerYear, chemistry);
    }

    /**
     * Är titeln en bil som finns i ev_spec, dvs. en ren elbil? Används av
     * {@link ExpertInsightService#findForCarTitle} för att veta att ett kort är en elbil
     * när titeln inte säger det själv ("Kia EV6" innehåller inget drivlineord — "ev6" är
     * ETT ord). Går via samma matchning som specsiffrorna på kortet: en andra
     * implementation hade kunnat glida isär från den bil kortet faktiskt visar specar för.
     *
     * <p><b>En namnträff räcker inte som svar — {@code ice_consumption} har företräde</b>
     * (2026-08-14). "Hyundai Kona" och "Kia Niro" finns som både bensinbil och elbil, och
     * matchningen svarar ja på den nakna titeln eftersom radens ord ("Kona Electric") är en
     * äkta övermängd av titelns. Kortet klassades då som ren elbil och tappade sina
     * förbränningsinsikter — kamrems- och oljeråd som hör hemma just där.
     *
     * <p><b>Regeln bodde först hos anroparen, och det var för klent.</b> Samma företräde fanns
     * redan i {@link GroqService} på två ställen (drivmedelsvakten sedan 2026-08-09,
     * kortbygget sedan {@code ef26242}), och insiktsfiltret fick sin egen kopia i
     * {@code 128d829}. Tre kopior av en regel är tre chanser att glida isär, och en fjärde
     * anropare hade gått i samma fälla igen. Den bor nu här, i metoden som besvarar frågan.
     *
     * <p>Ingen {@code electric}-token: {@code Dacia Spring} och {@code Alpine A290} bär ordet
     * men finns BARA som elbil, så en ordlista hade tystat deras kort. Samma gräns som för
     * e-prefixen. Företrädet frågar databasen i stället och behöver ingen lista.
     *
     * <p>Fail open vid DB-fel: namnträffen finns, och en trasig uppslagning får inte göra en
     * elbil till något annat.
     */
    public boolean isKnownEv(String title) {
        if (matchByTitle(title) == null) return false;
        // Bär titeln ett drivlineord har den pekat ut sin variant själv, och raden finns —
        // då är namnet inte tvetydigt och företrädet behövs inte. Villkoret är avsiktligt
        // "drivetrain != null" och inte "ev": PHEV-titlar svarade sant före 2026-08-14 och
        // ska fortsätta göra det, annars ändras kontraktet för anropare som inte bett om det.
        // Den enda anroparen (ExpertInsightService.titleDrivetrain) når hit bara när ordet
        // saknas, så företrädet biter exakt där skadan fanns.
        String drivetrain = ExpertInsightService.drivetrainOf(CarTitle.stripYear(title == null ? "" : title));
        if (drivetrain != null) return true;
        try {
            return iceConsumptionService.consumptionForTitle(title, null, null) == null;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Fuzzy-matchning titel → ev_spec i två pass. Null om inget hittas.
     *
     * <p><b>Passen var tre och matchade på delsträngar fram till 2026-08-14.</b> Pass 1 krävde
     * att varje ord i titeln fanns som delsträng var som helst i det lagrade namnet, vilket är
     * roten till en hel buggfamilj: {@code "c-hr"} ligger inuti {@code "c-hr+"}, {@code "250"}
     * inuti {@code "250+"}, {@code "tt"} inuti {@code "quattro"}, och {@code "a"}/{@code "b"}/
     * {@code "c"} inuti nästan varje Mercedes-rad. Varje gång felet dök upp lagades det med en
     * ny token i {@link #DRIVLINEORD} — en lista som måste växa varje gång tabellen får ett
     * nytt namnmönster.
     *
     * <p><b>Mätt offline 2026-08-14</b> (398 ICE-namnplåtar och alla 553 ev_spec-rader genom
     * fyra varianter, samma upplägg som tröskelmätningen för {@code d9ff37b}):
     * <ul>
     *   <li>delsträng (dåvarande): <b>67</b> namnplåtar för förbränningsbilar nådde en elbilsrad</li>
     *   <li>ordgräns (denna): <b>52</b> — och tre av de fyra tokens som lades in samma dag
     *       ({@code c-hr+}, {@code 250+}, {@code 4matic+}) blev överflödiga, mätt genom att
     *       köra varianten både med och utan dem och få exakt samma 52</li>
     *   <li>delsträng med minst 3 tecken: 53, alltså nästan lika bra — men {@code "c-hr"} är
     *       fyra tecken och slipper igenom, så den varianten hade behållit tokenberoendet</li>
     * </ul>
     * Kostnaden var två rader som inte längre hittade sig själva, båda
     * {@code Renault Scenic E-Tech EV60/EV87 170hp (TU2025)} — se årsstrippningen nedan; det
     * var ett parsningsfel som delsträngsmatchningen dolde, inte en äkta träff som försvann.
     *
     * <p><b>Gamla pass 3 är borttaget för att det var identiskt med nya pass 1.</b> Det gjorde
     * redan exakt "titelns ord som hela ord i radens namn" — pass 1 var bara dess lösare
     * variant. Kvar finns pass 1 (titelns ord i raden) och pass 2 (radens ord i titeln).
     *
     * <p><b>Kvar olöst, och det går inte att lösa här:</b> 43-52 namnplåtar når fortfarande en
     * elbilsrad, men de flesta är modeller som finns som BÅDE förbränning och el —
     * {@code Kia Niro}, {@code Ford Mustang}, {@code Mini Cooper}, {@code Volvo S60 T8}. Ingen
     * strängregel kan skilja dem åt; det görs ett lager upp, av drivmedelsvakten och av regeln
     * att {@code ice_consumption} prövas före {@code ev_spec}.
     */
    private EvSpec matchByTitle(String title) {
        if (title == null) return null;
        String cleaned = normalize(title
                // (?<!\w) hindrar att årtalet plockas ur mitt i ett ord. Utan den kapade
                // strippningen "2025)" ur ev-databases tekniska uppdateringskod "(TU2025)"
                // och lämnade skräpordet "(tu" i titeln, varpå raden inte kunde matcha ens
                // sitt EGET namn. Delsträngsmatchningen dolde felet; ordgränsmatchningen
                // avslöjade det. Samma vakt sitter i modelYear, som annars läste 2025 som
                // annonsens årsmodell och kunde välja fel generation på det.
                .replaceAll("\\s*(?<!\\w)\\(?(19|20)\\d{2}\\+?\\)?\\s*$", "")   // strip year
                .replaceAll("(?i)\\bElectric\\b", "")         // "MG4 Electric" → "MG4"
                .replaceAll("(?i)\\be-(?=[A-Za-z])", "")      // "e-Niro" → "Niro", "e-C3" → "C3"
                .trim());
        String[] titleWords = cleaned.split("\\s+");
        java.util.Set<String> titleSet = new java.util.HashSet<>(java.util.Arrays.asList(titleWords));
        java.util.Set<String> raw = rawWords(title);
        int year = modelYear(title);

        List<EvSpec> all = repo.findAll();

        // Pass 1: alla titelns ord finns som HELA ORD i det lagrade namnet.
        // "MG4" matchar "MG4 Long Range", "Volvo EX30" matchar "Volvo EX30 Single Motor" —
        // men "Toyota C-HR" matchar INTE "Toyota C-HR+", för "c-hr" och "c-hr+" är olika ord.
        EvSpec match = pickForYear(all.stream()
                .filter(ev -> !drivlinekrock(ev.getCarName(), raw))
                .filter(ev -> {
                    java.util.Set<String> nameSet = new java.util.HashSet<>(
                            java.util.Arrays.asList(matchningsNamn(ev.getCarName()).split("\\s+")));
                    for (String w : titleWords) if (!nameSet.contains(w)) return false;
                    return true;
                })
                .collect(java.util.stream.Collectors.toList()), year, true);

        // Pass 2: alla radens ord finns som exakta ord i titeln
        // t.ex. "Tesla Model 3 Long Range" som titel hittar raden "Tesla Model 3"
        // Ordmängd och inte delsträng, så "ev" INTE matchar "phev"
        if (match == null) {
            match = pickForYear(all.stream()
                    .filter(ev -> !drivlinekrock(ev.getCarName(), raw))
                    .filter(ev -> {
                        String[] nameWords = matchningsNamn(ev.getCarName()).split("\\s+");
                        for (String w : nameWords) if (!titleSet.contains(w)) return false;
                        return true;
                    })
                    .collect(java.util.stream.Collectors.toList()), year, true);
        }

        return match;
    }

    /**
     * Väljer rad ur ett pass kandidater, med årsmodellen som tiebreak mellan generationer.
     *
     * <p>Utan årsledet plockade passen "bästa namnträff" utan att bry sig om vilken bil annonsen
     * faktiskt gäller: en MG4 från 2023 (gen 1, 51/64/77 kWh) kunde få gen 2:as siffror i
     * spec-chipsen medan {@code verifiedEngineOptions} strax intill visade rätt generation, för
     * bara den senare filtrerade på år. Samma generationsdata styr nu båda.
     *
     * @param längstaNamnetVinner passens egen tiebreak när året inte skiljer dem åt
     */
    private static EvSpec pickForYear(List<EvSpec> kandidater, int year, boolean längstaNamnetVinner) {
        List<EvSpec> kvar = specsForYear(kandidater, year);
        if (kvar.isEmpty()) return null;
        return längstaNamnetVinner
                ? kvar.stream().max(java.util.Comparator.comparingInt(ev ->
                        normalize(ev.getCarName()).split("\\s+").length)).orElse(null)
                : kvar.get(0);
    }

    /**
     * Samma generationsval som {@link #keepGenerationForYear}, men på ev_spec-rader i stället för
     * hopslagna varianter. Rör inget när årsmodell saknas, när ingen kandidat bär en generation
     * eller när valet skulle tömma listan — se den metoden för motiveringen.
     */
    private static List<EvSpec> specsForYear(List<EvSpec> kandidater, int year) {
        if (year == 0 || kandidater.size() < 2) return kandidater;
        if (kandidater.stream().noneMatch(ev -> GENERATION.containsKey(normalize(ev.getCarName())))) {
            return kandidater;
        }
        int vald = kandidater.stream()
                .mapToInt(EvSpecService::fromYear)
                .filter(from -> from <= year)
                .max().orElse(0);
        List<EvSpec> kvar = kandidater.stream()
                .filter(ev -> fromYear(ev) == vald)
                .collect(java.util.stream.Collectors.toList());
        return kvar.isEmpty() ? kandidater : kvar;
    }

    private static int fromYear(EvSpec ev) {
        Generation g = GENERATION.get(normalize(ev.getCarName()));
        return g == null ? 0 : g.fromYear();
    }

    /**
     * Drivlineord i ett lagrat namn som titeln måste bära för att raden ska få matcha.
     *
     * <p>{@code ev_spec} innehåller 21 laddhybridrader ("Kia Niro PHEV", "Volvo XC60 PHEV") vid
     * sidan av elbilarna, eftersom en laddhybrid också har batteri och elräckvidd. Matchningen
     * strippar "Electric" ur titeln för att "MG4 Electric" ska hitta "MG4" — men då blir
     * "Hyundai Kona Electric" bara "Hyundai Kona", och båda orden finns i "Hyundai Kona PHEV".
     * Live 2026-08-11 fick därför ett rent elbilskort motoralternativet "8.9 kWh (58 km) · PHEV",
     * alltså en laddhybrids 5,8 mil på en bil som går 51.
     *
     * <p>Värre för {@link #isKnownEv}, som svarar på "är kortet en ren elbil?" åt insiktsfiltret:
     * "Volvo XC60" matchade "Volvo XC60 PHEV" och en bensin-XC60 räknades som elbil, varpå
     * förbränningsinsikterna filtrerades bort från kortet.
     *
     * <p>Prövas mot titelns EGNA ord före strippningen — annars hade "Kia Niro PHEV (2021)"
     * inte kunnat hitta sin egen rad.
     */
    private static boolean drivlinekrock(String carName, java.util.Set<String> titelnsEgnaOrd) {
        for (String w : normalize(carName).split("\\s+")) {
            if (DRIVLINEORD.contains(w) && !titelnsEgnaOrd.contains(w)) return true;
        }
        return false;
    }

    /**
     * Laddhybridmarkörer i lagrade namn. Listan är inventerad ur tabellen, inte gissad.
     *
     * <p>Första versionen tog bara "phev" och "hev" — generaliserat från de två exempel som
     * fanns för handen. Det räckte inte: verifieringssöket 2026-08-11 gav kortet "Volkswagen
     * e-Golf (2019)" motoralternativet "13 kWh (70 km) · GTE". Titeln strippas till "volkswagen
     * golf" av e-prefixregeln och matchade <b>Volkswagen Golf GTE</b>, en laddhybrid — och den
     * riktiga e-Golfen (35,8 kWh, ~23 mil) finns inte ens som rad. Tabellen använder alltså
     * flera namnkonventioner för samma sak.
     *
     * <p>Genuina laddhybridrader vid inventeringen: Golf GTE, Passat GTE, Prius Plug-in,
     * RAV4 Plug-in samt de 21 raderna med PHEV i namnet. De två Plug-in-raderna är dubbletter
     * av Prius PHEV och RAV4 PHEV under en annan konvention — de raderas inte, eftersom
     * nattsynken skulle kunna återskapa dem, och spärren gör dem ofarliga där de står.
     *
     * <p>DS "E-Tense" (50,8 och 58,3 kWh) och "Jeep Compass Electric 4xe" (96,1 kWh) ser ut som
     * laddhybridmarkörer men är det inte, och står därför inte i listan — ett för brett filter
     * hade tystat riktiga elbilskort.
     *
     * <p><b>"Recharge" stod på samma undantagslista fram till 2026-08-14, och det var fel.</b>
     * Motiveringen var riktig i sak: Volvo använder namnet för både elbil och laddhybrid, och
     * våra två rader ({@code Volvo XC40 Recharge}, {@code Volvo XC40 Recharge Twin}) är båda
     * 75 kWh-elbilar. Men den skyddade bara elbilskorten — inte bensinbilen. Skarpt sök
     * 2026-08-14 (SUV/bensin/250 000 kr) gav kortet "Volvo XC40 (2022)" en elbils
     * {@code evSpec} — 75 kWh, 530 km, <i>ladda var 11:e dag</i> — samtidigt som dess
     * {@code fuelSpec} korrekt visade bensinmotorn B4 197 hk och 0,8 l/mil. Kortet bar alltså
     * en elbils laddråd och en bensinbils förbrukning på samma gång.
     *
     * <p>Priset för spärren är att ett äkta elbils-XC40 vars titel bara säger "Volvo XC40"
     * tappar sina spec-chips. Det är samma avvägning som redan gäller för e-Golf och
     * PHEV-raderna, och den går åt rätt håll: <b>fel data på en bensinbil är värre än
     * utebliven data på en elbil</b> — vakterna kräver positivt bevis.
     *
     * <p><b>e-prefixade modellnamn hör hit av samma skäl.</b> "e-Golf" är inte "Golf" — den ena
     * är en elbil, den andra en av Sveriges vanligaste bensinbilar. Matchningen strippar
     * {@code e-} ur BÅDA sidor (se {@link #matchningsNamn}) för att "Ford e-Tourneo Courier"
     * ska hitta sin rad, och utan den här spärren hade en bensin-Golf då fått e-Golfens siffror
     * och räknats som elbil av {@link #isKnownEv}. Bara de modellnamn där basordet också finns
     * som förbränningsbil står med — "Audi Q4 e-tron" behöver inget skydd, det finns ingen
     * bensin-Q4 att förväxla den med.
     *
     * <p><b>Plus-suffixade modellnamn hör hit av exakt samma skäl (2026-08-14).</b> Skarpt fall:
     * ett SUV/bensin-sök på 250 000 kr gav kortet "Toyota C-HR (2021)" rådet <i>ladda var 13:e
     * dag</i>. Blocket-matchningen och drivmedelsfiltret gjorde rätt — bilen ÄR en hybrid-C-HR —
     * men pass 1 i {@link #matchByTitle} matchar titelns ord som delsträngar i hela det lagrade
     * namnet, och {@code "c-hr"} är en delsträng av {@code "c-hr+"}. Plustecknet är det ENDA som
     * skiljer den eldrivna C-HR+ (54/72 kWh) från hybriden, så delsträngsmatchningen raderade
     * precis det tecken som bar identiteten.
     *
     * <p>Inventerat ur tabellen 2026-08-14: 19 rader bär {@code +} i namnet, varav åtta gick att
     * nå från en namnplåt som också finns i {@code ice_consumption} — C-HR+ (2 rader, mot C-HR
     * 1.8/2.0 Hybrid), CLA 250+, GLA 250+, GLB 250+ och tre AMG GT 4-Door 4MATIC+. Mercedes-trion
     * är värre än C-HR: en bensin-"GLA 250" träffade {@code GLA 250+} eftersom {@code "250"} är
     * delsträng av {@code "250+"}.
     *
     * <p><b>De plus-tokens som lades in samma dag är sedan borttagna igen.</b> Det var
     * symtomlagningen; roten satt i pass 1:s delsträngsmatchning, och när den gjordes
     * ordgränsbaserad ({@link #matchByTitle}) blev {@code c-hr+}, {@code 250+} och
     * {@code 4matic+} överflödiga — mätningen körde varianten både med och utan dem och fick
     * exakt samma 52 felträffar. {@code "c-hr"} och {@code "c-hr+"} är helt enkelt olika ord.
     * <b>Lägg inte tillbaka dem</b>, och lägg inte in nya plus-tokens utan att först visa att
     * ordgränsmatchningen inte redan täcker fallet.
     *
     * <p>{@code recharge} står kvar, för den bär inte ett ändrat ord utan ett EXTRA: raden
     * {@code Volvo XC40 Recharge} innehåller hela titeln "Volvo XC40" som riktiga ord, så
     * ordgränsen hjälper inte där. Samma sak gäller {@code Mercedes-Benz CLA} mot
     * {@code CLA 200}. Det är den kvarvarande klassen som bara en drivmedelsregel kan lösa.
     */
    private static final java.util.Set<String> DRIVLINEORD = java.util.Set.of(
            "phev", "hev", "gte", "plug-in",
            "e-golf", "e-rifter", "e-tourneo", "e-caravelle", "e-transporter",
            "recharge");

    /**
     * Lagrat namn med samma {@code e-}-strippning som titeln får, för ordjämförelserna.
     *
     * <p>Titelsidan har alltid strippat prefixet ("e-Niro" → "Niro"), men den lagrade sidan
     * har inte gjort det — så en rad vars MODELLORD bär prefixet blev omöjlig att träffa med
     * ordmatchning: "Ford e-Tourneo Courier" har orden {ford, e-tourneo, courier} medan titeln
     * ger {ford, tourneo, courier}, och "tourneo" finns inte bland radens ord. De raderna fick
     * därför aldrig några verifierade motoralternativ. Symmetrin fixar ~30 rader (e-Tourneo,
     * e-Caravelle, e-Transporter, e-Rifter, e-Traveller) utöver e-Golf.
     *
     * <p>Spärren i {@link #DRIVLINEORD} prövas mot det OSTRIPPADE namnet och måste göra det —
     * annars vore "e-golf" borta innan den hann jämföras.
     */
    private static String matchningsNamn(String carName) {
        return normalize(carName.replaceAll("(?i)\\be-(?=[A-Za-z])", ""));
    }

    /** Titelns ord som de står, utan årsstrippning eller drivlinestrippning. */
    private static java.util.Set<String> rawWords(String title) {
        return new java.util.HashSet<>(java.util.Arrays.asList(normalize(title).split("\\s+")));
    }

    /**
     * Verifierade motor-/batterivarianter för modellen, en post per batteri ("69 kWh (436–480 km)"
     * — se {@link #groupByBattery} för varför varianter slås ihop och räckvidden blir ett spann),
     * byggda av ev_spec istället för AI:ns fritext i "engineOptions" — som annars kan hitta på
     * kWh/räckvidd (skarpt fall: Volvo EX30 fick 58/77/44 kWh från AI:n trots att de riktiga
     * varianterna är 51/65/65 kWh). Ingen hästkraft per variant (inte lagrat i DB) — bara kWh
     * + räckvidd, hellre mindre information som stämmer än mer som delvis är påhittad.
     * Samma matchning som pass 3 i formatForTitle (alla titelord som ord i lagrat namn) —
     * hittar ALLA varianter av modellen, inte bara bästa träff. Returnerar null om inget
     * hittas, så AI:ns egen text används som fallback.
     */
    public String verifiedEngineOptions(String title) {
        if (title == null) return null;
        String cleaned = normalize(title
                .replaceAll("\\s*\\(?(19|20)\\d{2}\\+?\\)?\\s*$", "")
                .replaceAll("(?i)\\bElectric\\b", "")
                .replaceAll("(?i)\\be-(?=[A-Za-z])", "")
                .trim());
        String[] titleWords = cleaned.split("\\s+");
        java.util.Set<String> raw = rawWords(title);

        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<Variant> variants = new java.util.ArrayList<>();
        for (EvSpec ev : repo.findAll()) {
            if (drivlinekrock(ev.getCarName(), raw)) continue;   // se drivlinekrock: PHEV-raden på elbilskortet
            java.util.Set<String> nameSet = new java.util.HashSet<>(
                    java.util.Arrays.asList(matchningsNamn(ev.getCarName()).split("\\s+")));
            boolean matches = true;
            for (String w : titleWords) if (!nameSet.contains(w)) { matches = false; break; }
            if (!matches) continue;
            if (ev.getBatteryKwh() == null || ev.getBatteryKwh() <= 0) continue;
            int range = ev.getRangeKm() != null ? ev.getRangeKm() : 0;
            String key = ev.getBatteryKwh() + "|" + range;
            if (!seen.add(key)) continue;
            variants.add(new Variant(ev.getBatteryKwh(), range,
                    trimName(ev.getCarName(), titleSetOf(titleWords)),
                    GENERATION.get(normalize(ev.getCarName()))));
        }
        if (variants.isEmpty()) return null;
        variants = keepGenerationForYear(variants, modelYear(title));
        variants.sort(java.util.Comparator.comparingDouble(Variant::kwh).thenComparingInt(Variant::km));
        return groupByBattery(variants).stream()
                .map(EvSpecService::formatGroup)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** En matchad ev_spec-rad: batteri, räckvidd (0 = okänd), trimnamn och modellgeneration. */
    private record Variant(double kwh, int km, String trim, Generation generation) {}

    /**
     * Årsmodellen ur en AI-titel ("MG4 Long Range (2023)" → 2023), eller 0 om den saknas.
     * Samma mönster som strippningen i {@link #verifiedEngineOptions}, men året behålls i stället
     * för att kastas. "2024+" räknas som 2024.
     */
    static int modelYear(String title) {
        if (title == null) return 0;
        Matcher m = Pattern.compile("(?<!\\w)\\(?((?:19|20)\\d{2})\\+?\\)?\\s*$").matcher(title.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * Behåller bara den generation som faktiskt såldes annonsens årsmodell.
     *
     * <p>En MG4 från 2023 är första generationen och har 51/64/77 kWh; en från 2025 är den andra
     * och har 41,9/52,8/61,7/74,4. Utan filtret listade kortet alla sju batterierna för båda —
     * sant men obrukbart, och för en begagnatannons dessutom vilseledande: siffrorna för en bil
     * som inte fanns när annonsbilen tillverkades.
     *
     * <p>Filtret slår bara till när det finns något att välja mellan. Har titeln ingen årsmodell,
     * eller bär ingen av modellens rader en generation (alltså alla modeller utom MG4 i dag),
     * lämnas listan orörd. Väljer året bort precis allt behålls listan också — hellre för mycket
     * information än ett tomt fält, och det inträffar om årsmodellen är äldre än vår äldsta rad.
     *
     * @param year annonsens årsmodell, 0 om okänd
     */
    private static List<Variant> keepGenerationForYear(List<Variant> variants, int year) {
        if (year == 0) return variants;
        boolean någonTaggad = variants.stream().anyMatch(v -> v.generation() != null);
        if (!någonTaggad) return variants;

        int vald = generationForYear(variants, year);
        List<Variant> kvar = variants.stream()
                .filter(v -> fromYear(v) == vald)
                .collect(java.util.stream.Collectors.toList());
        return kvar.isEmpty() ? variants : kvar;
    }

    /** Startåret för den nyaste generationen som hunnit börja säljas årsmodell {@code year}. */
    private static int generationForYear(List<Variant> variants, int year) {
        return variants.stream()
                .mapToInt(EvSpecService::fromYear)
                .filter(from -> from <= year)
                .max()
                .orElse(0);
    }

    /** Generationens startår, 0 för otaggade rader (den äldre generationen). */
    private static int fromYear(Variant v) {
        return v.generation() == null ? 0 : v.generation().fromYear();
    }

    /**
     * Rader som tillhör en annan modellgeneration än de omärkta i samma modell.
     *
     * <p>Behövs för att {@link #groupByBattery} annars slår ihop generationer som råkar ha nästan
     * lika stora batterier: MG4:s första generation har 51 kWh/350 km och den andra 52,8 kWh/416
     * km — 3,5 % isär, alltså långt inom 8 %-toleransen. Resultatet blev raden "52.8 kWh (350–416
     * km)", som parar andra generationens batteri med första generationens räckvidd. Det är inte
     * ett spann, det är två olika bilar.
     *
     * <p>Ingen siffra kan skilja fallen åt — EV6 GT och Long Range delar batteri men går 424
     * respektive 528 km (24 % isär) och ska ändå slås ihop. Generationen måste alltså vara
     * uppgiven, inte härledd. Den ligger här och inte som en kolumn på {@code ev_spec} eftersom
     * {@code ddl-auto=validate}: ett nytt fält på entiteten hade fällt uppstarten innan någon
     * migrering hann köra. Raderna skapas i {@code DataLoader.seedEvSpecExtras}.
     */
    /**
     * En modellgeneration och året den började säljas. {@code fromYear} väljer rätt generation åt
     * en annons med årsmodell — se {@link #generationForYear}. Otaggade rader är per definition
     * den äldre generationen och behandlas som {@code fromYear = 0}.
     */
    private record Generation(String label, int fromYear) {}

    private static final Generation MG4_GEN2 = new Generation("MG4 gen 2", 2025);

    /**
     * Nissan Leafs tre generationer. Till skillnad från MG4 måste ALLA tre taggas, inte bara de
     * nyare: den otaggade raden räknas som {@code fromYear = 0}, alltså den äldsta — och vår
     * otaggade Leaf-rad är tvärtom den NYASTE bilen (2026, 75,1 kWh). Utan taggen hade en
     * annons från 2019 fått 2026 års siffror igen, fast raderna nu finns.
     *
     * <p>Gen 1 och gen 2 skapas i {@code DataLoader.LEAF_UTGANGNA}; gen 3-raden heter bara
     * "Nissan Leaf" och underhålls av nattsynken.
     */
    private static final Generation LEAF_GEN1 = new Generation("Leaf gen 1", 2011);
    private static final Generation LEAF_GEN2 = new Generation("Leaf gen 2", 2018);
    private static final Generation LEAF_GEN3 = new Generation("Leaf gen 3", 2026);

    /**
     * MG ZS EV, samma fall som Leaf: den otaggade raden ("MG ZS EV", 72,6 kWh / 440 km) är
     * faceliftens Long Range, alltså den NYARE bilen, så båda måste taggas. Pre-facelift-raden
     * skapas i {@code DataLoader.UTGANGNA_GENERATIONER}.
     */
    private static final Generation ZS_GEN1 = new Generation("ZS EV pre-facelift", 2019);
    private static final Generation ZS_GEN2 = new Generation("ZS EV facelift", 2022);

    /** e-Golf: 24,2 kWh 2014–2016, 35,8 kWh från facelift 2017. Ingen otaggad rad att ta hänsyn till. */
    private static final Generation EGOLF_GEN1 = new Generation("e-Golf 24 kWh", 2014);
    private static final Generation EGOLF_GEN2 = new Generation("e-Golf 35 kWh", 2017);

    /**
     * Škoda Enyaq under TVÅ namnkonventioner, vilket är en femte ingång till samma fel som
     * MG4, Leaf, ZS EV och e-Golf: en rad som matchar ett kort den inte hör till.
     *
     * <p>Tabellen bär både {@code Enyaq iV 60/80/85} och {@code Enyaq 60/85} — samma bil, men
     * "iV" är förfaceliftnamnet. Utan taggning stod alla fem som parallella val på samma kort,
     * och en 2022:a kunde få faceliftens siffror. Uppmätt 2026-08-12 gav ett Enyaq-kort raderna
     * "58 kWh (390 km) · 60", "82 kWh (530 km) · 80" och "82 kWh (550 km) · 85" bredvid varandra,
     * vilket ser ut som tre batterier när det är två.
     *
     * <p><b>Årtalen är hämtade från auto-data 2026-08-12, inte gissade:</b> {@code Enyaq iV}
     * 2020–2025, {@code Enyaq Coupe iV} 2022–2025, faceliften från 2025. Faceliftåret är alltså
     * 2025, inte 2024 som namnet "85" först antyder — Škoda döpte om 80 till 85 REDAN under
     * iV-generationen, så {@code Enyaq iV 85} är en äkta rad och inte en hopblandning.
     *
     * <p><b>Kapaciteten anges olika mellan generationerna och det är källornas konvention, inte
     * ett fel:</b> iV-raderna bär bruttobatteriet (82 kWh) och faceliftraderna nettot (77 kWh).
     * Det är samma fysiska pack. Att skriva om den ena hade gjort raden osann mot sin källa, och
     * grupperingen i {@code verifiedEngineOptions} slår redan ihop brutto/netto när räckvidden
     * är densamma — det är därför generationstaggen, inte en omräkning, som är rätt åtgärd.
     */
    private static final Generation ENYAQ_GEN1 = new Generation("Enyaq iV", 2020);
    private static final Generation ENYAQ_GEN2 = new Generation("Enyaq facelift", 2025);

    /**
     * Renault Zoe i tre generationer, tillagda 2026-08-13 tillsammans med raderna i
     * {@code DataLoader.UTGANGNA_GENERATIONER}. Q210/R240 2013–2016, ZE40 2017–2018,
     * ZE50 2019–2024 (då modellen lades ned och ersattes av Renault 5).
     */
    private static final Generation ZOE_GEN1 = new Generation("Zoe 22 kWh", 2013);
    private static final Generation ZOE_GEN2 = new Generation("Zoe ZE40", 2017);
    private static final Generation ZOE_GEN3 = new Generation("Zoe ZE50", 2019);

    /**
     * Tesla Model 3 före och efter Highland-faceliften (hösten 2023, i handeln 2024).
     *
     * <p>Den OTAGGADE raden hette bara "Tesla Model 3" och bar 60 kWh / 534 km, alltså exakt
     * samma värden som "Model 3 RWD (Highland)" — den är faceliftens RWD under ett kortare
     * namn, inte en äldre bil. Den måste därför taggas som GEN2, och det är precis den fällan
     * Leaf-arbetet 2026-08-11 skrev upp: <b>en otaggad rad räknas som {@code fromYear} 0,
     * alltså äldst</b>, så utan den här posten hade en 2020:a fått faceliftens siffror ändå.
     */
    private static final Generation M3_GEN1 = new Generation("Model 3 pre-facelift", 2019);
    private static final Generation M3_GEN2 = new Generation("Model 3 Highland", 2024);

    /**
     * Kia EV6 före och efter faceliften (2024, i handeln 2025). Enda modellen i hela tabellen
     * där BÅDA generationerna redan fanns som rader men ingen var taggad — inventeringen
     * 2026-08-13 gick igenom alla 539 rader och hittade bara det här fallet.
     *
     * <p>Skiljelinjen är batteriet: pre-facelift såldes som 58/77,4 kWh, faceliften som
     * 63/84 kWh. Raderna utan kWh i namnet (60, 80) är nettoangivelser av samma paket som
     * 63 respektive 84 — {@code verifiedEngineOptions} slår redan ihop dem eftersom räckvidden
     * är identisk, så de hör till samma generation som sin bruttotvilling.
     */
    private static final Generation EV6_GEN1 = new Generation("EV6 pre-facelift", 2021);
    private static final Generation EV6_GEN2 = new Generation("EV6 facelift", 2025);

    /**
     * Resten av klass A ur inventeringen 2026-08-13 — modeller vars enda rad bar den nyaste
     * generationens siffror, nu kompletterade med sin äldre generation i
     * {@code DataLoader.UTGANGNA_GENERATIONER}.
     *
     * <p>Årtalen är faceliftens/generationsskiftets FÖRSTA säljår i Sverige, inte
     * presentationsåret — det är årsmodellen på en annons som filtret jämför mot.
     */
    private static final Generation MODELY_GEN1 = new Generation("Model Y pre-facelift", 2021);
    private static final Generation MODELY_GEN2 = new Generation("Model Y Juniper", 2025);
    private static final Generation KONA_GEN1   = new Generation("Kona Electric gen 1", 2018);
    private static final Generation KONA_GEN2   = new Generation("Kona Electric gen 2", 2023);
    private static final Generation PS2_GEN1    = new Generation("Polestar 2 pre-facelift", 2020);
    private static final Generation PS2_GEN2    = new Generation("Polestar 2 facelift", 2024);
    private static final Generation ID4_GEN1    = new Generation("ID.4 launch", 2021);
    private static final Generation ID4_GEN2    = new Generation("ID.4 facelift", 2024);
    private static final Generation IONIQ5_GEN1 = new Generation("IONIQ 5 pre-facelift", 2021);
    private static final Generation IONIQ5_GEN2 = new Generation("IONIQ 5 facelift", 2024);
    private static final Generation BZ4X_GEN1   = new Generation("bZ4X pre-facelift", 2022);
    private static final Generation BZ4X_GEN2   = new Generation("bZ4X facelift", 2025);

    /**
     * ID.3 och i3 — hittade först när exponeringslistan gjordes om mot RÄTT matchningsregel.
     *
     * <p>Den första inventeringen friade båda med motiveringen att en rad med extra ord i namnet
     * ("ID.3 <b>Neo</b> 50 kWh", "i3 <b>50 xDrive</b>") inte skulle matcha en kortare titel. Det
     * var precis bakvänt: {@link #verifiedEngineOptions} kräver att varje ord i TITELN finns i
     * RADENS namn, så en längre rad matchar alltid en kortare titel. Ett kort för
     * "Volkswagen ID.3 (2022)" listade därför Neo-varianter med 630 km, bekräftat i drift.
     *
     * <p>Lärdomen är generell: <b>suffix skyddar inte en rad från att hamna på fel kort.</b>
     * Varje modell med rader ur mer än en generation måste taggas, oavsett hur namnen ser ut.
     */
    /**
     * Stellantis e-CMP: 50 kWh-paketet (46,3 användbara) blev 54 kWh (50,8) från 2023-2024.
     *
     * <p>Ett ENDA generationspar räcker för fyra namnplåtar, eftersom {@code keepGenerationForYear}
     * väljer den senaste generationen vars {@code fromYear} inte överstiger årsmodellen: ë-C4
     * (2021), ë-C4 X (2023), Corsa Electric (2020) och e-208 (2020) hamnar alla korrekt i GEN1
     * med startåret 2020, och först en 2024:a får GEN2.
     *
     * <p><b>Nyckelformen är inte självklar och därför testad:</b> {@code GENERATION} slås upp med
     * {@code normalize(carName)}, som tar bort diakritiska tecken men BEHÅLLER bindestreck — så
     * "Citroën ë-C4" blir {@code "citroen e-c4"}, inte "citroen c4". En felstavad nyckel ger
     * ingen träff och därmed en OTAGGAD rad, vilket räknas som äldst och tyst återskapar precis
     * det fel taggningen skulle laga.
     */
    private static final Generation ECMP_GEN1 = new Generation("e-CMP 50 kWh", 2020);
    private static final Generation ECMP_GEN2 = new Generation("e-CMP 54 kWh", 2024);

    private static final Generation ID3_GEN1 = new Generation("ID.3 pre-Neo", 2020);
    private static final Generation ID3_GEN2 = new Generation("ID.3 Neo", 2026);
    private static final Generation I3_GEN1  = new Generation("i3 hatchback", 2013);
    private static final Generation I3_GEN2  = new Generation("i3 Neue Klasse", 2026);

    private static final Map<String, Generation> GENERATION = Map.ofEntries(
            Map.entry("mg4 urban standard range",     MG4_GEN2),
            Map.entry("mg4 urban comfort long range", MG4_GEN2),
            Map.entry("mg4 urban premium long range", MG4_GEN2),
            Map.entry("mg4 premium long range",       MG4_GEN2),
            Map.entry("mg4 premium extended range",   MG4_GEN2),
            Map.entry("mg mg4 xpower",                MG4_GEN2),
            Map.entry("nissan leaf 24 kwh",           LEAF_GEN1),
            Map.entry("nissan leaf 30 kwh",           LEAF_GEN1),
            Map.entry("nissan leaf 40 kwh",           LEAF_GEN2),
            Map.entry("nissan leaf e+ 62 kwh",        LEAF_GEN2),
            Map.entry("nissan leaf",                  LEAF_GEN3),
            Map.entry("mg zs ev 44.5 kwh",            ZS_GEN1),
            Map.entry("mg zs ev",                     ZS_GEN2),
            Map.entry("volkswagen e-golf 24.2 kwh",   EGOLF_GEN1),
            Map.entry("volkswagen e-golf 35.8 kwh",   EGOLF_GEN2),
            Map.entry("skoda enyaq iv 60",            ENYAQ_GEN1),
            Map.entry("skoda enyaq iv 80",            ENYAQ_GEN1),
            Map.entry("skoda enyaq iv 85",            ENYAQ_GEN1),
            Map.entry("skoda enyaq 60",               ENYAQ_GEN2),
            Map.entry("skoda enyaq 85",               ENYAQ_GEN2),
            Map.entry("skoda enyaq rs",               ENYAQ_GEN2),
            Map.entry("skoda enyaq coupe 60",         ENYAQ_GEN2),
            Map.entry("skoda enyaq coupe 85",         ENYAQ_GEN2),
            Map.entry("skoda enyaq coupe 85x",        ENYAQ_GEN2),
            Map.entry("skoda enyaq coupe rs",         ENYAQ_GEN2),
            Map.entry("renault zoe 22 kwh",           ZOE_GEN1),
            Map.entry("renault zoe ze40 41 kwh",      ZOE_GEN2),
            Map.entry("renault zoe",                  ZOE_GEN3),
            Map.entry("tesla model 3 standard range plus 55 kwh", M3_GEN1),
            Map.entry("tesla model 3 long range 82 kwh",          M3_GEN1),
            Map.entry("tesla model 3",                            M3_GEN2),
            Map.entry("tesla model 3 rwd (highland)",             M3_GEN2),
            Map.entry("tesla model 3 premium rwd (highland)",     M3_GEN2),
            Map.entry("tesla model 3 premium awd (highland)",     M3_GEN2),
            Map.entry("tesla model 3 performance (highland)",     M3_GEN2),
            Map.entry("kia ev6 long range awd 77.4 kwh", EV6_GEN1),
            Map.entry("kia ev6 long range 2wd 77.4 kwh", EV6_GEN1),
            Map.entry("kia ev6 gt 77.4 kwh",             EV6_GEN1),
            Map.entry("kia ev6 standard range 2wd",      EV6_GEN2),
            Map.entry("kia ev6 standard range 63 kwh",   EV6_GEN2),
            Map.entry("kia ev6 long range awd",          EV6_GEN2),
            Map.entry("kia ev6 long range 2wd",          EV6_GEN2),
            Map.entry("kia ev6 gt",                      EV6_GEN2),
            Map.entry("kia ev6 long range 2wd 84 kwh",   EV6_GEN2),
            Map.entry("kia ev6 long range awd 84 kwh",   EV6_GEN2),
            Map.entry("tesla model y long range 75 kwh",                MODELY_GEN1),
            Map.entry("tesla model y",                                  MODELY_GEN2),
            Map.entry("tesla model y rwd (juniper)",                    MODELY_GEN2),
            Map.entry("tesla model y premium rwd (juniper - tesla 4680)", MODELY_GEN2),
            Map.entry("tesla model y premium awd (juniper)",            MODELY_GEN2),
            Map.entry("tesla model y performance (juniper)",            MODELY_GEN2),
            Map.entry("hyundai kona electric 39 kwh",    KONA_GEN1),
            Map.entry("hyundai kona electric 64 kwh",    KONA_GEN1),
            Map.entry("hyundai kona electric",           KONA_GEN2),
            Map.entry("polestar 2 long range 75 kwh",    PS2_GEN1),
            Map.entry("polestar 2",                      PS2_GEN2),
            Map.entry("volkswagen id.4 pro 77 kwh",      ID4_GEN1),
            Map.entry("volkswagen id.4",                 ID4_GEN2),
            Map.entry("hyundai ioniq 5 54 kwh",          IONIQ5_GEN1),
            Map.entry("hyundai ioniq 5 70 kwh",          IONIQ5_GEN1),
            Map.entry("hyundai ioniq 5 63 kwh rwd",      IONIQ5_GEN2),
            Map.entry("hyundai ioniq 5",                 IONIQ5_GEN2),
            Map.entry("toyota bz4x 64 kwh",              BZ4X_GEN1),
            Map.entry("toyota bz4x",                     BZ4X_GEN2),
            Map.entry("volkswagen id.3",                 ID3_GEN1),
            Map.entry("volkswagen id.3 pro 58 kwh",      ID3_GEN1),
            Map.entry("volkswagen id.3 neo 50 kwh",      ID3_GEN2),
            Map.entry("volkswagen id.3 neo 58 kwh",      ID3_GEN2),
            Map.entry("volkswagen id.3 neo 79 kwh",      ID3_GEN2),
            Map.entry("bmw i3 120 ah 37.9 kwh",          I3_GEN1),
            Map.entry("bmw i3 50 xdrive",                I3_GEN2),
            Map.entry("citroen e-c4",                    ECMP_GEN1),
            Map.entry("citroen e-c4 54 kwh",             ECMP_GEN2),
            Map.entry("citroen e-c4 x",                  ECMP_GEN1),
            Map.entry("citroen e-c4 x 54 kwh",           ECMP_GEN2),
            Map.entry("opel corsa electric 50 kwh",      ECMP_GEN1),
            Map.entry("opel corsa electric 54 kwh",      ECMP_GEN2),
            Map.entry("peugeot e-208 50 kwh",            ECMP_GEN1),
            Map.entry("peugeot e-208 54 kwh",            ECMP_GEN2),
            Map.entry("peugeot e-208 gti",               ECMP_GEN2));


    /**
     * En grupp hopslagna varianter: brutto- och nettobatteri, räckviddsspann, trimnamn och
     * generation. {@code minKwh} behövs för att toleransen ska mätas mot gruppens minsta
     * kapacitet — annars kan en kedja av närliggande värden växa ihop till en enda grupp.
     */
    private record Group(double kwh, double minKwh, int minKm, int maxKm, String trim, Generation generation) {}

    private static java.util.Set<String> titleSetOf(String[] titleWords) {
        return new java.util.HashSet<>(java.util.Arrays.asList(titleWords));
    }

    /**
     * Trimnivån ur ett lagrat namn: det som blir kvar när modellorden ur titeln tagits bort
     * ("MG4 Standard Range" + titeln "MG4" → "Standard Range"). Utan den här raden visar kortet
     * fyra MG4-rader som bara skiljer sig i kWh, där två dessutom har samma räckvidd (Long Range
     * och XPOWER, 405 km) — omöjligt att se vilken rad som är vilken bil.
     *
     * <p>Märket framför modellen skalas bort först: ev-database lagrar "MG MG4 XPOWER" medan
     * titeln bara säger "MG4", så utan det steget blir trimmet "MG XPOWER". Allt före det första
     * ordet som faktiskt finns i titeln räknas som märkesprefix.
     *
     * @return trimnamnet, eller null om inget blir kvar (raden ÄR basmodellen)
     */
    private static String trimName(String carName, java.util.Set<String> titleWords) {
        if (carName == null) return null;
        String[] words = carName.trim().split("\\s+");
        int firstTitleWord = -1;
        for (int i = 0; i < words.length; i++) {
            if (titleWords.contains(matchningsNamn(words[i]))) { firstTitleWord = i; break; }
        }
        if (firstTitleWord < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = firstTitleWord; i < words.length; i++) {
            if (titleWords.contains(matchningsNamn(words[i]))) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Batterier inom 8 % av gruppens minsta räknas som samma fysiska batteri.
     *
     * <p>Gränsen ligger där den gör för att skilja två saker åt: tillverkarnas buffert mellan
     * netto- och bruttokapacitet ligger i praktiken på 4–7 % (EX30 49/51 = 4,1 %, 65/69 = 6,2 %,
     * EV6 Standard 60/63 = 5,0 %), medan verkligt olika batterival skiljer sig mer (EV6:s
     * 77,4 mot 84 kWh = 8,5 %, EV6 Standard mot Long Range = 23 %). Toleransen var först 10 %,
     * vilket slog ihop EV6:s två generationer till en rad.
     */
    private static final double SAME_BATTERY_TOLERANCE = 1.08;

    /**
     * Räckvidder inom 2 % räknas som samma siffra.
     *
     * <p>Andra halvan av "samma bil"-testet, och den som gör att varianter blir egna rader.
     * Netto- och bruttokapacitet för samma bil har olika kWh men <b>samma</b> räckvidd (EV6
     * Standard Range: 60 kWh/428 km och 63 kWh/428 km är en bil listad två gånger). Två
     * verkliga varianter har tvärtom samma eller nära kapacitet men olika räckvidd — MG4:s
     * 52,8 kWh finns som Urban Comfort (416 km) och Urban Premium (405 km), och 61,7 kWh som
     * Premium Long Range (452 km) och XPOWER (405 km).
     *
     * <p>Toleransen ligger på 2 % för att den ska stänga just avrundningsglapp mellan källor,
     * inte äta upp en verklig skillnad: MG4:s tätaste par ligger 2,6 % isär.
     */
    private static final double SAME_RANGE_TOLERANCE = 0.02;

    /**
     * Slår ihop varianter som beskriver samma batteri. Databasen innehåller samma bil under
     * flera namn (EX30 finns både som "Single Motor"/"Twin Motor Performance" och som
     * ev-database.orgs "P3"/"P5"/"P8 AWD") och samma batteri både som netto- och
     * bruttokapacitet — EX30:s 49/51 kWh är ett batteri, 65/69 kWh är ett annat. Utan
     * ihopslagning blev kortet nio rader för en bil som har två batterier.
     *
     * <p>Jämförelsen görs mot gruppens MINSTA kWh, inte den största, så en lång kedja av
     * närliggande värden inte kan växa ihop till en enda grupp.
     *
     * <p><b>Bara dubbletten slås ihop, inte varianten.</b> Kapaciteten ensam räcker inte som
     * test: MG4:s 52,8 kWh finns som två olika bilar (Urban Comfort 416 km, Urban Premium
     * 405 km) och 61,7 kWh som två till (Premium Long Range 452 km, XPOWER 405 km) — de slogs
     * ihop till "52.8 kWh (405–416 km)" och användaren såg inte vilken version som var vilken,
     * vilket var hela poängen med kortet. Räckvidden avgör därför: samma bil under två namn
     * har olika kWh men samma räckvidd, två varianter har samma kWh men olika räckvidd.
     *
     * <p>Trimnamnet följer bara med när ALLA rader i gruppen bär samma namn. Efter ändringen
     * ovan är en hopslagen grupp nästan alltid netto/brutto av samma bil under två källors
     * namn, och där hade ett godtyckligt utvalt namn pekat ut fel rad — hellre ingen etikett
     * än en som ljuger.
     *
     * @param sorted varianter sorterade på kWh stigande, därefter km (km = 0 om okänt)
     * @return grupper med bruttokapacitet, räckviddsspann (0 = saknas) och ev. gemensamt trim
     */
    private static List<Group> groupByBattery(List<Variant> sorted) {
        List<Group> groups = new java.util.ArrayList<>();
        for (Variant v : sorted) {
            // Leta i ALLA grupper, inte bara den senaste: listan är sorterad på kapacitet, så
            // en bils netto- och bruttorad hamnar inte nödvändigtvis bredvid varandra. EV6
            // Long Range 2WD ligger som 80 kWh/582 km och 84 kWh/582 km med tre andra rader
            // emellan, och visades därför som två bilar när bara grannen jämfördes.
            int träff = -1;
            for (int i = 0; i < groups.size(); i++) {
                if (sammaBil(groups.get(i), v)) { träff = i; break; }
            }
            if (träff < 0) {
                groups.add(new Group(v.kwh(), v.kwh(), v.km(), v.km(), v.trim(), v.generation()));
                continue;
            }
            Group g = groups.get(träff);
            int minKm = g.minKm(), maxKm = g.maxKm();
            if (v.km() > 0) {
                minKm = minKm == 0 ? v.km() : Math.min(minKm, v.km());
                maxKm = Math.max(maxKm, v.km());
            }
            String trim = java.util.Objects.equals(g.trim(), v.trim()) ? g.trim() : null;
            groups.set(träff, new Group(Math.max(g.kwh(), v.kwh()),          // visa bruttokapaciteten
                    Math.min(g.minKwh(), v.kwh()), minKm, maxKm, trim, g.generation()));
        }
        // Ihopslagningen kan höja en grupps kapacitet, så ordningen sätts efter den
        groups.sort(java.util.Comparator.comparingDouble(Group::kwh).thenComparingInt(Group::minKm));
        return groups;
    }

    /**
     * Är {@code v} samma bil som gruppen redan beskriver — alltså en dubblett att slå ihop?
     *
     * <p>Tre villkor, alla nödvändiga: samma modellgeneration, kapaciteter inom
     * {@link #SAME_BATTERY_TOLERANCE} av gruppens minsta, och <b>olika</b> kapacitet med
     * <b>samma</b> räckvidd. Det sista paret är det som skiljer en dubblett från en variant:
     * exakt samma kWh betyder att raderna kommer från samma källa och beskriver olika bilar,
     * medan olika kWh + samma räckvidd är netto och brutto för en och samma bil.
     *
     * <p>Saknas räckvidden på någon av raderna finns inget att skilja dem åt med, och då slås
     * de ihop som förut — en rad utan siffror säger ändå ingenting om vilken variant den är.
     */
    private static boolean sammaBil(Group g, Variant v) {
        if (!java.util.Objects.equals(g.generation(), v.generation())) return false;
        if (v.kwh() > g.minKwh() * SAME_BATTERY_TOLERANCE) return false;
        if (v.kwh() == g.kwh()) return false;                       // samma kapacitet = egen variant
        if (g.maxKm() == 0 || v.km() == 0) return true;             // okänd räckvidd skiljer inget
        int närmast = Math.abs(v.km() - g.maxKm()) <= Math.abs(v.km() - g.minKm())
                ? g.maxKm() : g.minKm();
        return Math.abs(v.km() - närmast) <= närmast * SAME_RANGE_TOLERANCE;
    }

    /** → "69 kWh (436–480 km)", "52.8 kWh (405 km) · Long Range" eller "51 kWh". */
    private static String formatGroup(Group g) {
        StringBuilder sb = new StringBuilder(formatKwh(g.kwh())).append(" kWh");
        if (g.maxKm() > 0) {
            sb.append(g.minKm() == g.maxKm()
                    ? " (" + g.maxKm() + " km)"
                    : " (" + g.minKm() + "–" + g.maxKm() + " km)");
        }
        if (g.trim() != null && !baraKapacitet(g.trim(), g.kwh())) sb.append(" · ").append(g.trim());
        return sb.toString();
    }

    /**
     * Är trimnamnet bara en upprepning av kWh-talet som redan står först på raden?
     *
     * <p>Trimmet är det som blir kvar av radnamnet när titelns ord tagits bort, så en rad vars
     * enda särskiljande del ÄR batteristorleken gav "44.5 kWh (263 km) · 44.5 kWh". Det gäller
     * raderna vi själva lagt in för utgångna generationer, där kWh-talet är det som skiljer dem
     * åt i namnet. Ett trim som säger något mer ("e+ 62 kWh") behålls — plustecknet är
     * modellbeteckningen, inte kapaciteten.
     */
    private static boolean baraKapacitet(String trim, double kwh) {
        Matcher m = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*kwh\\s*$", Pattern.CASE_INSENSITIVE)
                .matcher(trim);
        if (!m.matches()) return false;
        return Math.abs(Double.parseDouble(m.group(1).replace(',', '.')) - kwh) < 0.05;
    }

    private static String formatKwh(double kwh) {
        return kwh == Math.floor(kwh) ? String.valueOf((int) kwh) : String.valueOf(kwh);
    }

    /**
     * Verifierad systemeffekt (hk) för modeller där AI:n historiskt gissat fel —
     * skarpt fall: MG Marvel R fick "150hk" av AI:n, riktiga siffror är 180 (Standard)
     * / 288 (Performance AWD), källa ev-database.org. Samma ordmatchning som
     * getBatteryChemistry — mest specifika nyckeln (flest ord) vinner, så "MG Marvel R
     * Performance" inte råkar matcha bara på "MG Marvel R"-nyckeln. Ingen ev_spec-kolumn
     * (skulle kräva ALTER TABLE mot produktionens validate-schema för en handfull rader).
     */
    public Integer getSystemPowerHk(String title) {
        if (title == null) return null;
        String utanAr = title.replaceAll("\\s*\\(?(19|20)\\d{2}\\+?\\)?\\s*$", "")
                .replaceAll("(?i)\\bElectric\\b", "")
                .trim();

        // Både med och utan elprefixet: "Kia e-Niro" ska kunna matcha nyckeln "Kia Niro", medan
        // nyckeln "Volkswagen e-Golf" INTE får matcha en bensin-Golf. Utan den osträckta formen i
        // mängden hade prefixet varit osynligt för nyckeln och en Golf fått e-Golfens effekt.
        java.util.Set<String> ord = new java.util.HashSet<>();
        ord.addAll(java.util.Arrays.asList(normalize(utanAr).split("\\s+")));
        ord.addAll(java.util.Arrays.asList(
                normalize(utanAr.replaceAll("(?i)\\be-(?=[A-Za-z])", "")).split("\\s+")));

        List<Systemeffekt> traffar = SYSTEM_POWER_HK.stream()
                .filter(e -> {
                    for (String w : normalize(e.namn()).split("\\s+")) if (!ord.contains(w)) return false;
                    return true;
                })
                .toList();
        if (traffar.isEmpty()) return null;

        // Mest specifika namnet vinner, så "MG Marvel R Performance" inte tas av "MG Marvel R"
        int flest = traffar.stream().mapToInt(e -> normalize(e.namn()).split("\\s+").length).max().orElse(0);
        List<Systemeffekt> kvar = traffar.stream()
                .filter(e -> normalize(e.namn()).split("\\s+").length == flest).toList();

        Integer arsmodell = CarTitle.year(title);
        if (arsmodell == null) {
            // Utan årtal går generationerna inte att skilja åt. Bär de samma effekt spelar det
            // ingen roll; skiljer de sig är rätt svar att avstå — en "verifierad" siffra som
            // gäller halva årsspannet är sämre än AI:ns gissning, för den bär vår etikett.
            long olika = kvar.stream().mapToInt(Systemeffekt::hk).distinct().count();
            return olika == 1 ? kvar.get(0).hk() : null;
        }

        // Senaste generationen som hunnit börja. Är bilen äldre än alla poster tas den äldsta —
        // årtalet kan vara AI:ns egen felgissning, och listans äldsta rad är då bästa svaret.
        final int ar = arsmodell;
        return kvar.stream()
                .filter(e -> e.franAr() <= ar)
                .max(java.util.Comparator.comparingInt(Systemeffekt::franAr))
                .or(() -> kvar.stream().min(java.util.Comparator.comparingInt(Systemeffekt::franAr)))
                .map(Systemeffekt::hk)
                .orElse(null);
    }

    /**
     * En modells systemeffekt från och med ett visst år.
     *
     * <p>Årtalet finns för att samma modellnamn bär olika effekt mellan generationer, och titeln
     * är det enda vi har att skilja dem på: e-Golf gick från 115 till 136 hk med det större
     * batteriet 2017, MG ZS EV från 143 till 156 hk med faceliften. En namnnyckel utan årtal hade
     * gett fel siffra åt halva årsspannet — och till skillnad från AI:ns gissning hade den burit
     * vår verifieringsetikett.
     *
     * @param franAr 0 när modellen bara haft en effekt, eller för den äldsta generationen
     */
    private record Systemeffekt(String namn, int franAr, int hk) {}

    /**
     * Verifierad systemeffekt (hk) för modeller där AI:n gissar fel.
     *
     * <p><b>Listan är en lapp, inte en täckning:</b> den innehåller de modeller som råkat fällas
     * på ett kort, och för alla andra av {@code ev_spec}s rader står AI:ns gissning kvar. Uppmätt
     * 2026-08-14 gav två elbilskort <b>150 hk</b> var (riktiga siffror 136 och 143), och samma
     * sökning dagen innan gav <b>95 hk</b> på tre kort — talet varierar mellan körningar, alltså
     * är det påhittat. En hållbar lösning är egen tabell fylld av nattsynken från sidan den redan
     * hämtar, samma mönster som {@code ice_generation}; tills dess växer listan en modell i taget.
     *
     * <p>Källor kontrollerade 2026-08-14 (auto-data.net + EVKX/automobile-catalog):
     * e-Golf 24,2 kWh = 85 kW, 35,8 kWh = 100 kW; ZS EV 44,5 kWh = 105 kW, 72,6 kWh = 115 kW.
     */
    private static final List<Systemeffekt> SYSTEM_POWER_HK = List.of(
        new Systemeffekt("MG Marvel R",             0, 180),
        new Systemeffekt("MG Marvel R Performance", 0, 288),
        // e-Golf: 24,2 kWh (2014-2016) mot 35,8 kWh (facelift 2017-2020) — båda rader i ev_spec
        new Systemeffekt("Volkswagen e-Golf",       0, 115),
        new Systemeffekt("Volkswagen e-Golf",    2017, 136),
        // ZS EV: 44,5 kWh (2019-2021) mot Long Range 72,6 kWh (facelift, europapremiär 2022)
        new Systemeffekt("MG ZS EV",                0, 143),
        new Systemeffekt("MG ZS EV",             2022, 156)
    );

    /**
     * Hela ev_spec med samma härledda fält som bilkortet visar — för admin-granskning av
     * datakvalitet (prisvärdhetsetiketter, saknade priser, orimliga räckvidder).
     *
     * Går via toDto och inte via en egen kopia av poängformeln: en andra implementation hade
     * kunnat glida isär från den etikett användaren faktiskt ser, vilket gör granskningen
     * värdelös just när den behövs.
     */
    public List<Map<String, Object>> listAllWithValueLabel(int kmPerYear) {
        return repo.findAll().stream()
                .sorted(java.util.Comparator.comparing(EvSpec::getCarName,
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(spec -> {
                    EvSpecDto d = toDto(spec, kmPerYear, null);
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("carName", spec.getCarName());
                    row.put("carType", d.carType());
                    row.put("batteryKwh", d.batteryKwh());
                    row.put("rangeKm", d.wltpKm());
                    row.put("maxDcKw", d.maxDcKw());
                    row.put("maxAcKw", d.maxAcKw());
                    row.put("priceKr", d.priceKr());
                    row.put("valueLabel", d.valueLabel());
                    return row;
                })
                .toList();
    }

    private EvSpecDto toDto(EvSpec spec, int kmPerYear, String chemistry) {
        int wltp   = spec.getRangeKm() != null ? spec.getRangeKm() : 0;
        int summer = (int) (wltp * 0.85);
        int winter = (int) (wltp * 0.70);

        int days = 0;
        String daysLabel = "";
        if (kmPerYear > 0 && summer > 0) {
            double daily = kmPerYear / 365.0;
            days = (int) Math.max(1, Math.round(summer / daily));
            daysLabel = days == 1 ? "ladda varje dag"
                      : days == 2 ? "ladda varannan dag"
                      : "ladda var " + days + ":e dag";
        }

        int maxDc  = spec.getMaxDcKw()    != null ? (int) spec.getMaxDcKw().doubleValue()    : 0;
        int maxAc  = spec.getMaxAcKw()    != null ? (int) spec.getMaxAcKw().doubleValue()     : 0;
        double bat = spec.getBatteryKwh() != null ? spec.getBatteryKwh()                      : 0.0;
        int price  = spec.getPriceKr()    != null ? spec.getPriceKr()                         : 0;

        String valueLabel = "";
        if (price > 0 && wltp > 0) {
            double priceUnit  = price / 100_000.0;                          // units of 100k kr
            double rangeScore = wltp / priceUnit;                           // km per 100k kr
            double batScore   = bat > 0 ? (bat / priceUnit) * 4.0 : 0;     // kWh per 100k kr, weighted
            double dcBonus    = maxDc >= 150 ? 20 : maxDc >= 100 ? 12 : maxDc >= 50 ? 5 : 0;
            double score      = rangeScore * 0.6 + batScore + dcBonus;
            valueLabel = score > 145 ? "Utmärkt prisvärdhet"
                       : score > 110 ? "Bra prisvärdhet"
                       : score > 80  ? "Ok prisvärdhet"
                       : "";
        }

        String carType = spec.getCarType() != null ? spec.getCarType() : "EV";
        return new EvSpecDto(wltp, summer, winter, days, daysLabel, bat, maxDc, maxAc, price, valueLabel, carType, chemistry);
    }

    // Battery chemistry per model. LFP: kan laddas till 100% dagligen utan slitage,
    // tåligare vid kyla. NMC: högre energitäthet, mer räckvidd per kg.
    // Format: "LFP", "NMC", "LFP/NMC" (beroende på variant)
    private static final Map<String, String> BATTERY_CHEMISTRY = new java.util.HashMap<>(Map.ofEntries(
        // Volvo — EX30 SM = LFP, alla övriga = NMC
        Map.entry("Volvo EX30 Single Motor Extended Range","NMC"),
        Map.entry("Volvo EX30 Twin Motor Performance",     "NMC"),
        Map.entry("Volvo EX30 Cross Country",              "NMC"),
        Map.entry("Volvo EX30 Single Motor",               "LFP"),
        Map.entry("Volvo EX30",                            "LFP/NMC"),
        Map.entry("Volvo EX40",                            "NMC"),
        Map.entry("Volvo XC40 Recharge",                   "NMC"),
        Map.entry("Volvo C40",                             "NMC"),
        Map.entry("Volvo EX60",                            "NMC"),
        Map.entry("Volvo EX90",                            "NMC"),
        Map.entry("Volvo ES90",                            "NMC"),
        Map.entry("Volvo XC60",                            "NMC"),
        Map.entry("Volvo XC90",                            "NMC"),
        Map.entry("Volvo S60",                             "NMC"),
        Map.entry("Volvo V60",                             "NMC"),
        // Tesla — SR/RWD = LFP, LR/Performance = NMC
        Map.entry("Tesla Model 3",                         "LFP/NMC"),
        Map.entry("Tesla Model Y",                         "LFP/NMC"),
        Map.entry("Tesla Model S",                         "NMC"),
        Map.entry("Tesla Model X",                         "NMC"),
        // Volkswagen-koncernen
        Map.entry("Volkswagen ID.3",                       "NMC"),
        Map.entry("Volkswagen ID.4",                       "NMC"),
        Map.entry("Volkswagen ID.5",                       "NMC"),
        Map.entry("Volkswagen ID.7",                       "NMC"),
        Map.entry("Volkswagen ID.Buzz",                    "NMC"),
        Map.entry("Skoda Enyaq",                           "NMC"),
        Map.entry("Skoda Elroq",                           "NMC"),
        Map.entry("Skoda Epiq",                            "NMC"),
        Map.entry("Cupra Born",                            "NMC"),
        Map.entry("Cupra Tavascan",                        "NMC"),
        Map.entry("Cupra Raval",                           "NMC"),
        Map.entry("Audi Q4 e-tron",                        "NMC"),
        Map.entry("Audi Q6 e-tron",                        "NMC"),
        Map.entry("Audi Q8 e-tron",                        "NMC"),
        Map.entry("Audi A6 e-tron",                        "NMC"),
        Map.entry("Audi e-tron GT",                        "NMC"),
        Map.entry("Porsche Taycan",                        "NMC"),
        Map.entry("Porsche Macan Electric",                "NMC"),
        Map.entry("Porsche Cayenne Electric",              "NMC"),
        // Hyundai / Kia — EV3 SR = LFP, övriga = NMC
        Map.entry("Hyundai IONIQ 5",                       "NMC"),
        Map.entry("Hyundai IONIQ 6",                       "NMC"),
        Map.entry("Hyundai IONIQ 9",                       "NMC"),
        Map.entry("Hyundai INSTER Standard Range",         "LFP"),
        Map.entry("Hyundai INSTER Long Range",             "NMC"),
        Map.entry("Hyundai INSTER",                        "LFP/NMC"),
        Map.entry("Hyundai Kona Electric",                 "NMC"),
        Map.entry("Hyundai Kona",                          "NMC"),
        Map.entry("Kia EV6",                               "NMC"),
        Map.entry("Kia EV3 Standard Range",                "LFP"),
        Map.entry("Kia EV3 Long Range",                    "NMC"),
        Map.entry("Kia EV3",                               "LFP/NMC"),
        Map.entry("Kia EV4",                               "NMC"),
        Map.entry("Kia EV9",                               "NMC"),
        Map.entry("Kia Niro EV",                           "NMC"),
        Map.entry("Genesis GV60",                          "NMC"),
        Map.entry("Genesis GV70",                          "NMC"),
        Map.entry("Genesis G80",                           "NMC"),
        // Polestar
        Map.entry("Polestar 2",                            "NMC"),
        Map.entry("Polestar 3",                            "NMC"),
        Map.entry("Polestar 4",                            "NMC"),
        Map.entry("Polestar 5",                            "NMC"),
        // BMW-koncernen
        Map.entry("BMW i4",                                "NMC"),
        Map.entry("BMW i5",                                "NMC"),
        Map.entry("BMW i7",                                "NMC"),
        Map.entry("BMW iX1",                               "NMC"),
        Map.entry("BMW iX2",                               "NMC"),
        Map.entry("BMW iX3",                               "NMC"),
        Map.entry("BMW iX",                                "NMC"),
        Map.entry("Mini Cooper E",                         "NMC"),
        Map.entry("Mini Cooper SE",                        "NMC"),
        Map.entry("Mini Aceman",                           "NMC"),
        Map.entry("Mini Countryman E",                     "NMC"),
        // Mercedes
        Map.entry("Mercedes EQA",                          "NMC"),
        Map.entry("Mercedes EQB",                          "NMC"),
        Map.entry("Mercedes EQC",                          "NMC"),
        Map.entry("Mercedes EQE",                          "NMC"),
        Map.entry("Mercedes EQS",                          "NMC"),
        Map.entry("Mercedes CLA",                          "NMC"),
        Map.entry("Mercedes G 580",                        "NMC"),
        // Stellantis (Peugeot / Opel / Citroën / Fiat / DS / Alfa / Lancia / Jeep)
        Map.entry("Peugeot e-208",                         "NMC"),
        Map.entry("Peugeot e-2008",                        "NMC"),
        Map.entry("Peugeot e-308",                         "NMC"),
        Map.entry("Peugeot e-3008",                        "NMC"),
        Map.entry("Peugeot e-5008",                        "NMC"),
        Map.entry("Opel Corsa Electric",                   "NMC"),
        Map.entry("Opel Mokka Electric",                   "NMC"),
        Map.entry("Opel Astra Electric",                   "NMC"),
        Map.entry("Opel Frontera Electric",                "LFP/NMC"),
        Map.entry("Opel Grandland Electric",               "NMC"),
        Map.entry("Citroen e-C3",                          "LFP"),
        Map.entry("Citroen e-C4",                          "NMC"),
        Map.entry("Fiat 500e",                             "NMC"),
        Map.entry("Fiat 600e",                             "NMC"),
        Map.entry("Fiat Grande Panda",                     "LFP/NMC"),
        Map.entry("Abarth 500e",                           "NMC"),
        Map.entry("Abarth 600e",                           "NMC"),
        Map.entry("DS 3 E-Tense",                          "NMC"),
        Map.entry("Alfa Romeo Junior",                     "NMC"),
        Map.entry("Lancia Ypsilon",                        "NMC"),
        Map.entry("Jeep Avenger Electric",                 "NMC"),
        // Renault / Nissan / Alpine / Mitsubishi
        Map.entry("Renault Zoe",                           "NMC"),
        Map.entry("Renault Megane E-Tech",                 "NMC"),
        Map.entry("Renault 5 E-Tech",                      "NMC"),
        Map.entry("Renault 4 E-Tech",                      "NMC"),
        Map.entry("Renault Scenic E-Tech",                 "NMC"),
        Map.entry("Renault Twingo E-Tech",                 "NMC"),
        Map.entry("Nissan Leaf",                           "NMC"),
        Map.entry("Nissan Ariya",                          "NMC"),
        Map.entry("Nissan Micra",                          "NMC"),
        Map.entry("Alpine A290",                           "NMC"),
        // Toyota / Subaru / Lexus
        Map.entry("Toyota bZ4X",                           "NMC"),
        Map.entry("Toyota C-HR",                           "NMC"),
        Map.entry("Toyota Urban Cruiser",                  "LFP/NMC"),
        Map.entry("Subaru Solterra",                       "NMC"),
        Map.entry("Lexus RZ",                              "NMC"),
        // Ford
        Map.entry("Ford Mustang Mach-E",                   "NMC"),
        Map.entry("Ford Puma Gen-E",                       "LFP"),
        Map.entry("Ford Capri",                            "NMC"),
        Map.entry("Ford Explorer",                         "NMC"),
        // BYD — alla använder Blade Battery (LFP); Seal Performance = NMC
        Map.entry("BYD Dolphin",                           "LFP"),
        Map.entry("BYD Atto 3",                            "LFP"),
        Map.entry("BYD ATTO 2",                            "LFP"),
        Map.entry("BYD Seal",                              "LFP/NMC"),
        Map.entry("BYD TANG",                              "LFP"),
        // Dacia
        Map.entry("Dacia Spring",                          "LFP"),
        // MG
        Map.entry("MG4 Standard Range",                    "LFP"),
        Map.entry("MG4 Standard",                          "LFP"),
        Map.entry("MG4",                                   "LFP/NMC"),
        Map.entry("MG ZS EV",                              "NMC"),
        Map.entry("MG Marvel R",                           "NMC"),
        // Smart (Geely/Mercedes JV)
        Map.entry("Smart 1",                               "NMC"),
        Map.entry("Smart 3",                               "NMC"),
        Map.entry("Smart 5",                               "NMC"),
        // Honda
        Map.entry("Honda eNy1",                            "NMC"),
        // Leapmotor — all LFP
        Map.entry("Leapmotor C10",                         "LFP"),
        Map.entry("Leapmotor T03",                         "LFP"),
        // NIO
        Map.entry("NIO ET5",                               "NMC"),
        Map.entry("NIO EL6",                               "NMC"),
        // Xpeng / Zeekr
        Map.entry("Xpeng G6",                              "NMC"),
        Map.entry("Xpeng G9",                              "NMC"),
        Map.entry("Zeekr 001",                             "NMC"),
        Map.entry("Zeekr 7X",                              "NMC"),
        // Mazda
        Map.entry("Mazda 6e",                              "NMC")
    ));

    private static final java.util.Set<String> PRICE_STRIP_WORDS = java.util.Set.of(
        "standard", "extended", "long", "short", "single", "twin", "dual",
        "range", "motor", "performance", "plus", "pro", "max", "rwd", "awd",
        "quattro", "xpower", "sport", "premium", "comfort", "launch", "edition",
        "gt", "rs", "cross", "country"
    );

    private static String baseModelName(String carName) {
        String base = java.util.Arrays.stream(carName.split("\\s+"))
            .filter(w -> !PRICE_STRIP_WORDS.contains(w.toLowerCase()))
            .collect(java.util.stream.Collectors.joining(" "))
            .trim();
        return base.isBlank() ? carName : base;
    }

    public String buildPriceReferenceContext() {
        java.util.Map<String, Integer> minPrices = new java.util.TreeMap<>();
        repo.findAll().stream()
            .filter(ev -> ev.getPriceKr() != null && ev.getPriceKr() > 50_000)
            .forEach(ev -> minPrices.merge(baseModelName(ev.getCarName()), ev.getPriceKr(), Math::min));
        // Only keep brands actually sold on the Swedish/European market
        java.util.Set<String> knownBrands = java.util.Set.of(
            "Audi","BMW","BYD","Citroën","Cupra","Dacia","Fiat","Ford","Honda","Hyundai",
            "Kia","Leapmotor","MG","Mazda","Mercedes","Mini","Nissan","Opel","Peugeot",
            "Renault","Seat","Škoda","Smart","Tesla","Toyota","Volkswagen","Volvo","Xpeng","Zeekr"
        );
        if (minPrices.isEmpty()) return "";
        String prices = minPrices.entrySet().stream()
            .filter(e -> knownBrands.stream().anyMatch(b -> e.getKey().startsWith(b)))
            .sorted(java.util.Map.Entry.comparingByValue())
            .limit(25)
            .map(e -> e.getKey() + " fr. " + formatSek(e.getValue()))
            .collect(java.util.stream.Collectors.joining(", "));
        String valuePicks = buildValueRangeLine();
        return "EV-referenspriser (fr.pris från databas): " + prices
            + (valuePicks.isEmpty() ? "" : "\n" + valuePicks);
    }

    /** Etablerade märken för prisvärd räckvidd-listan — inga okända kinesiska utmanarmärken. */
    private static final java.util.Set<String> VALUE_PICK_BRANDS = java.util.Set.of(
        "Audi","BMW","Citroën","Cupra","Dacia","Fiat","Ford","Honda","Hyundai",
        "Kia","MG","Mazda","Mercedes","Mini","Nissan","Opel","Peugeot",
        "Renault","Seat","Škoda","Smart","Tesla","Toyota","Volkswagen","Volvo"
    );

    /**
     * Rankar mest räckvidd per krona (nypris, minst 400 km WLTP) bland etablerade märken —
     * statistiken som lyfter t.ex. Kia EV3 och Volvo EX30 som prisvärda förslag. 400 km-golvet
     * hindrar billiga småbilar (Zoe, Spring) från att dominera listan.
     */
    String buildValueRangeLine() {
        record Pick(String name, int rangeKm, int priceKr) {
            double kmPerKrona() { return rangeKm / (double) priceKr; }
        }
        java.util.Map<String, Pick> best = new java.util.HashMap<>();
        repo.findAll().stream()
            .filter(ev -> ev.getPriceKr() != null && ev.getPriceKr() > 50_000
                    && ev.getRangeKm() != null && ev.getRangeKm() >= 400)
            .filter(ev -> VALUE_PICK_BRANDS.stream().anyMatch(b -> ev.getCarName().startsWith(b)))
            .forEach(ev -> {
                Pick p = new Pick(baseModelName(ev.getCarName()), ev.getRangeKm(), ev.getPriceKr());
                best.merge(p.name(), p, (a, b) -> a.kmPerKrona() >= b.kmPerKrona() ? a : b);
            });
        if (best.isEmpty()) return "";
        return "PRISVÄRD RÄCKVIDD (mest km räckvidd per krona i nypris, minst 400 km, etablerade märken): "
            + best.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Pick::kmPerKrona).reversed())
                .limit(8)
                .map(p -> p.name() + " (" + p.rangeKm() + " km, fr. " + formatSek(p.priceKr()) + " kr)")
                .collect(java.util.stream.Collectors.joining(", "))
            + " — lyft gärna dessa som prisvärda förslag när de passar profilen.";
    }

    private static String formatSek(int amount) {
        String s = String.valueOf(amount);
        StringBuilder sb = new StringBuilder();
        int start = s.length() % 3;
        if (start > 0) sb.append(s, 0, start);
        for (int i = start; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }

    public String getBatteryChemistry(String title) {
        if (title == null) return null;
        String cleaned = normalize(title
                .replaceAll("\\s*\\(?(19|20)\\d{2}\\+?\\)?\\s*$", "")
                .replaceAll("(?i)\\bElectric\\b", "")
                .replaceAll("(?i)\\be-(?=[A-Za-z])", "")
                .trim());
        String[] cleanedWords = cleaned.split("\\s+");
        java.util.Set<String> cleanedSet = new java.util.HashSet<>(java.util.Arrays.asList(cleanedWords));

        return BATTERY_CHEMISTRY.entrySet().stream()
                .filter(e -> {
                    String[] keyWords = normalize(e.getKey()).split("\\s+");
                    for (String w : keyWords) if (!cleanedSet.contains(w)) return false;
                    return true;
                })
                .max(java.util.Comparator.comparingInt(
                        (Map.Entry<String, String> e) -> normalize(e.getKey()).split("\\s+").length))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Gemener utan diakritik, med alla sorters blanktecken nedkokta till ett vanligt mellanslag.
     *
     * <p>Blankstegsstädningen finns för att AI:n ibland skriver modellnamn med <i>smalt hårt
     * mellanslag</i> (U+202F) eller vanligt hårt mellanslag (U+00A0) — "Hyundai IONIQ 5" såg rätt
     * ut men matchade ingenting, eftersom Javas {@code \s} bara täcker ASCII-blanktecken. Namnet
     * blev då ETT ord ("ioniq 5") i stället för två och all ordmatchning nedan missade, varpå
     * kortet föll tillbaka på AI:ns egen fritext. NFD-normaliseringen ovan hjälper inte: U+202F
     * har bara en <i>kompatibilitets</i>dekomposition till mellanslag, inte en kanonisk.
     */
    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .replaceAll("\\p{Cf}", "")          // osynliga styrtecken: zero-width space, BOM, mjukt bindestreck
                // Streckvarianter → vanligt bindestreck. Samma sorts fälla som de smala
                // mellanslagen nedan, upptäckt i ett skarpt sök 2026-08-14: AI:n skrev
                // "Toyota C‑HR" med U+2011 (icke-brytande bindestreck), och eftersom det
                // lagrade namnet bär U+002D kunde titeln aldrig matcha någon rad alls.
                // Tyst fel — kortet föll bara tillbaka på AI:ns fritext utan felutskrift.
                // Drabbar varje modell med bindestreck: C-HR, e-tron, Mach-E, I-Pace, ë-C4.
                // U+2010 hyphen, U+2011 non-breaking hyphen, U+2012 figure dash,
                // U+2013 en dash, U+2014 em dash, U+2015 horizontal bar, U+2212 minus.
                .replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                .replaceAll("[\\p{Z}\\s]+", " ")    // hårt/smalt/typografiskt mellanslag → vanligt
                .trim()
                .toLowerCase();
    }
}
