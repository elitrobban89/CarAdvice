package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.model.CargoSpecDto;
import com.caradvice.repository.CargoSpecRepository;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CargoSpecService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CargoSpecService.class);

    private final CargoSpecRepository repo;

    /**
     * Årsmodellen bor i en EGEN tabell, inte som kolumn på {@code cargo_spec}.
     *
     * <p>Skälet är {@code ddl-auto=validate}: ett nytt fält på entiteten valideras när
     * EntityManagerFactory startar, alltså INNAN någon {@code @PostConstruct} hinner köra sitt
     * {@code ALTER TABLE} — appen hade fallit i uppstarten på den kolumn den själv skulle skapa.
     * Samma grepp som {@code ice_generation}, {@code car_video} och {@code ev_power}: en liten
     * sidotabell som koden skapar själv, kopplad på bilnamnet.
     */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Cachead årsmodellkarta — tabellen är liten men läses en gång per bilkort. */
    private volatile Map<String, Integer> arsmodellCache = Map.of();
    private volatile long arsmodellCacheTid = 0L;
    private static final long ARSMODELL_TTL_MS = 10 * 60 * 1000L;

    /**
     * Kurerad generationsmarkör: ordet i RADENS namn som pekar ut den generation en modell säljs
     * som ny från ett visst år, för de fall där vår egen data inte kan skilja generationerna åt.
     *
     * <p><b>Varför MG4 inte går att härleda</b> (mätt 2026-09-05 mot ev-databases egna bilsidor):
     * MG säljer 2026 två MG4 sida vid sida — {@code MG MG4 XPOWER} på 4 287 mm med 388 l och
     * {@code MG MG4 Urban Standard Range} på 4 395 mm med 577 l, alltså två olika karosser. BÅDA
     * sidorna har rubriken "(MY26)" och BÅDA säger "Available since February 2026", så varken
     * årsmodellen i {@code cargo_spec_year} eller tillgänglighetsdatumet skiljer dem åt. Steg (4)
     * i {@link #valjBastaRad}, kortast namn, gav därför "MG MG4 XPOWER" (tre ord) före
     * "MG MG4 Urban Standard Range" (fem) — förra karossens volym på ett kort för den nya bilen.
     *
     * <p>Att den längre karossen är den nyare generationen är EXTERN kunskap; den står inte på
     * sidorna. Den bor därför i en kurerad rad här i stället för i en härledd regel som "störst
     * kaross vinner" — den regeln hade valt {@code Enyaq Coupé} framför {@code Enyaq} för varje
     * generiskt Enyaq-kort, alltså bytt ett fel mot ett större.
     *
     * <p>Nyckeln är modellordet så som det står i KORTETS titel. Regeln kräver ett årtal i titeln
     * (ett odaterat kort ska fortsatt få basraden) och lämnar kandidatlistan orörd när ingen
     * användbar rad bär markören — en titel som själv namnger en variant ("MG4 XPOWER (2026)")
     * har redan filtrerat bort de andra raderna och påverkas därför inte.
     */
    private static final Map<String, Generationsmarkor> NYA_GENERATIONEN = Map.of(
            "mg4", new Generationsmarkor("urban", 2026));

    /** Ordet som märker ut den nya generationens rader, och första årsmodell det gäller. */
    private record Generationsmarkor(String markorord, int franAr) {}


    public CargoSpecService(CargoSpecRepository repo, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
        skapaArsmodellTabell();
    }

    private void skapaArsmodellTabell() {
        if (jdbc == null) return;
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS cargo_spec_year ("
                    + "car_name VARCHAR(200) PRIMARY KEY, from_year INT NOT NULL)");
        } catch (Exception e) {
            log.warn("cargo_spec_year kunde inte skapas: {}", e.getMessage());
        }
    }

    /**
     * Årsmodellen per rad, tom map när tabellen saknas eller är tom.
     *
     * <p>En tom map betyder "ingen rad är daterad" och ger exakt det beteende matchningen hade
     * före 2026-09-04 — det är med flit: ett DB-fel ska inte kunna göra bagagevolymer OSYNLIGA,
     * bara odaterade.
     */
    Map<String, Integer> arsmodeller() {
        if (jdbc == null) return Map.of();
        long nu = System.currentTimeMillis();
        if (nu - arsmodellCacheTid < ARSMODELL_TTL_MS) return arsmodellCache;
        try {
            Map<String, Integer> ny = new HashMap<>();
            for (Map<String, Object> rad : jdbc.queryForList("SELECT car_name, from_year FROM cargo_spec_year")) {
                Object namn = rad.get("car_name");
                Object ar = rad.get("from_year");
                if (namn != null && ar instanceof Number n) ny.put(normalize(namn.toString()), n.intValue());
            }
            arsmodellCache = ny;
            arsmodellCacheTid = nu;
        } catch (Exception e) {
            log.warn("cargo_spec_year kunde inte läsas: {}", e.getMessage());
        }
        return arsmodellCache;
    }

    /** Skriver årsmodellen för EN rad. Idempotent; nyare värde vinner. */
    void sattArsmodell(String carName, int year) {
        if (jdbc == null || carName == null || carName.isBlank() || year < 1990 || year > 2100) return;
        try {
            jdbc.update("INSERT INTO cargo_spec_year (car_name, from_year) VALUES (?, ?) "
                    + "ON CONFLICT (car_name) DO UPDATE SET from_year = EXCLUDED.from_year",
                    carName, year);
            arsmodellCacheTid = 0L;   // tvinga omläsning
        } catch (Exception e) {
            log.warn("Årsmodell {} för {} kunde inte skrivas: {}", year, carName, e.getMessage());
        }
    }

    /** Alla kända bilnamn i cargo_spec — används av GroqServices modellhallucinationsvakt. */
    public List<String> findAllCarNames() {
        return repo.findAllCarNames();
    }

    /** Arbetslistan för auto-data-ifyllningen: bilnamn som ännu saknar bagagevolym. */
    public List<String> namnUtanVolym() {
        return repo.findNamesWithoutVolume();
    }

    /**
     * Alla rader som har en volym, som {@code {carName, cargoLiters, cargoMaxLiters}}.
     *
     * <p>Räknaren i {@link #coverage()} säger hur MÅNGA rader som är fyllda, aldrig vilka tal de
     * bär. Det räckte inte 2026-08-14: täckningen stod på 602/602/0 medan parsern var död, och
     * en täckning på 100 % kan inte röra sig och larmar därför aldrig. Samma instrument som
     * {@code GET /api/admin/ice-generations} är för årtalen.
     */
    public List<Map<String, Object>> allaMedVolym() {
        return repo.findAllWithVolume().stream()
                .map(c -> {
                    Map<String, Object> rad = new LinkedHashMap<>();
                    rad.put("carName", c.getCarName());
                    rad.put("cargoLiters", c.getCargoLiters());
                    rad.put("cargoMaxLiters", c.getCargoMaxLiters());
                    return rad;
                })
                .toList();
    }

    /**
     * Hur stor del av tabellen som faktiskt har en uppmätt volym.
     *
     * <p>Finns för att täckningen annars inte går att mäta: admin-API:t hade `import` och
     * `upsert` för bagagedata men inget som läser, så enda sättet att se om nattens ifyllning
     * gjort något var att läsa Render-loggen. Siffran styr dessutom en designfråga —
     * bagagevaktens {@code requireCargoCapacity} faller bara på positivt bevis just för att
     * täckningen är låg, och när den närmar sig 100 % för elbilar går den regeln att skärpa.
     *
     * <p><b>Läs {@code total} rätt:</b> det är antalet rader i cargo_spec (243 den 2026-08-10),
     * INTE antalet bilar appen känner till. {@code /api/cars} svarade 697 och användes först
     * som nämnare här, men den är unionen av cargo_spec och ev_spec — misstaget dolde att
     * {@code utanVolym} redan var 0 och att nattens ifyllning därför inte kunde göra något.
     *
     * @return {@code total} = alla kända bilnamn, {@code medVolym} = de med siffra
     */
    public Map<String, Long> coverage() {
        long total = repo.count();
        long medVolym = repo.countWithVolume();
        return new LinkedHashMap<>(Map.of(
                "total", total,
                "medVolym", medVolym,
                "utanVolym", total - medVolym));
    }

    /**
     * Fyller en tom bagagevolym med en uppmätt siffra från nattens EV-synk.
     *
     * <p>Luckan är bilar som <b>saknar rad</b>, inte rader som saknar siffra: {@code cargo_spec}
     * har 243 rader och samtliga bär volym, medan {@code ev_spec} har 518 elbilsvarianter.
     * Första versionen fyllde bara rader där volymen var {@code null} och vägrade skapa nya —
     * den kunde alltså aldrig fylla någonting, vilket syntes först när
     * {@code /api/admin/cargo-coverage} svarade {@code utanVolym: 0} efter en 12-minuterskörning
     * som rört noll rader. Motivet till spärren ("nya rader per variant skräpar ned
     * autocomplete") höll inte heller: {@code /api/cars} är unionen av cargo_spec och ev_spec,
     * så namnen ligger redan där.
     *
     * <p>ev-database bär både "Cargo Volume" och "Cargo Volume Max" på de bilsidor EV-synken
     * ändå besöker varje natt, så volymen kostar inget extra anrop — men den täcker bara elbilar.
     *
     * <p>Tre regler:
     * <ul>
     *   <li><b>Befintlig volym skrivs aldrig över.</b> DataLoaders seedade volymer är
     *       handkontrollerade och vinner alltid över en skrapad siffra.</li>
     *   <li><b>Mest specifika namnet vinner</b> när flera rader matchar, samma regel som
     *       {@link #formatForTitle}: "Kia EV6 GT" före "Kia EV6" för en GT-sida.</li>
     *   <li><b>Saknas raden helt skapas den</b>, under ev-databases variantnamn. Uppslaget tar
     *       ändå längsta matchande namn, så en GT-rad vid sidan av basmodellen gör lookupen mer
     *       exakt och inte sämre.</li>
     * </ul>
     *
     * @return true om en rad fylldes eller skapades
     */
    @Transactional
    public boolean fillFromScrape(String scrapedName, int liters, int maxLiters) {
        return fillFromScrape(scrapedName, liters, maxLiters, 0);
    }

    /**
     * Som ovan, men med sidans årsmodell.
     *
     * <p><b>Årtalet skrivs BARA på en rad som är sidans egen bil</b> — den som skapades här, eller
     * en vars namn är exakt det skrapade. En rad som matchades under ett KORTARE namn (skrapad
     * "Kia EV6 Long Range 2WD" fyller raden "Kia EV6") får förbli odaterad: daterade vi den med
     * 2026 hade ett EV6-kort från 2022 stängts ute från sin egen bagagevolym. Generalisten ska
     * gälla alla år; bara varianten bär sitt årtal.
     */
    @Transactional
    public boolean fillFromScrape(String scrapedName, int liters, int maxLiters, int modelYear) {
        if (scrapedName == null || scrapedName.isBlank() || liters <= 0) return false;
        Set<String> scrapedWords = new HashSet<>(Arrays.asList(normalize(scrapedName).split("\\s+")));

        // Sök bland ALLA rader, inte bara de tomma: en rad som redan bär volym betyder att bilen
        // är täckt, och då ska ingen ny rad skapas för samma bil under ett variantnamn.
        CargoSpec match = repo.findAll().stream()
                .filter(cs -> {
                    String[] nameWords = normalize(cs.getCarName()).split("\\s+");
                    if (nameWords.length < 2) return false;   // "Volvo" ensamt matchar allt
                    for (String w : nameWords) if (!scrapedWords.contains(w)) return false;
                    return true;
                })
                .max(Comparator.comparingInt(cs -> normalize(cs.getCarName()).split("\\s+").length))
                .orElse(null);

        if (match == null) {
            repo.save(new CargoSpec(scrapedName, liters, maxLiters > 0 ? maxLiters : null));
            if (modelYear > 0) sattArsmodell(scrapedName, modelYear);
            return true;
        }
        // Årtalet skrivs FÖRE den tidiga returen nedan: en rad som redan bär volym ska ändå bli
        // daterad, annars hade backfyllningen krävt att tabellen tömdes först.
        if (modelYear > 0 && normalize(match.getCarName()).equals(normalize(scrapedName)))
            sattArsmodell(match.getCarName(), modelYear);
        if (match.getCargoLiters() != null && match.getCargoLiters() > 0) return false;

        match.setCargoLiters(liters);
        if (maxLiters > 0) match.setCargoMaxLiters(maxLiters);
        repo.save(match);
        return true;
    }

    public CargoSpecDto formatForTitle(String title) {
        CargoSpec match = matchForTitle(title);
        if (match == null || match.getCargoLiters() == null) return null;
        return new CargoSpecDto(match.getCargoLiters(),
                match.getCargoMaxLiters() != null ? match.getCargoMaxLiters() : 0);
    }

    /**
     * Vilken rad en korttitel FAKTISKT landar på, och vilka andra rader som var möjliga.
     *
     * <p>Finns sedan 2026-09-04 av samma skäl som listan över hela tabellen: ett fel som sitter i
     * VALET mellan rader syns inte i värdena. MG4 har tre volymer i tabellen — 363 l (första
     * generationen, 4 287 mm), 388 l (samma kaross enligt ev-database) och 577 l (MY26-bilen som
     * ev-database kallar "MG4 Urban", 4 395 mm och en helt annan bil) — och frågan "vilken av dem
     * får ett MG4-kort?" gick inte att svara på utan att läsa Render-loggen.
     */
    public Map<String, Object> traffForTitle(String title) {
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("titel", title);
        CargoSpec match = matchForTitle(title);
        Map<String, Integer> ar = arsmodeller();
        ut.put("titelAr", CarTitle.year(title));
        ut.put("matchadRad", match == null ? null : match.getCarName());
        ut.put("liter", match == null ? null : match.getCargoLiters());
        ut.put("maxLiter", match == null ? null : match.getCargoMaxLiters());
        ut.put("matchadArsmodell", match == null ? null : ar.get(normalize(match.getCarName())));
        String cleaned = normalize(CarTitle.stripYear(title == null ? "" : title));
        String forstaOrdet = cleaned.isBlank() ? "" : cleaned.split("\\s+")[0];
        List<Map<String, Object>> kandidater = new ArrayList<>();
        // Rader UTAN volym listas med (cargoLiters null) sedan 2026-09-05. De filtrerades bort
        // förut, och just därför gick Enyaq-skuggningen inte att se: den tomma raden
        // "Skoda Enyaq" VANN valet men syntes varken i kandidatlistan eller i tabellistningen.
        // Ett instrument som döljer de rader som kan vinna svarar på fel fråga.
        for (CargoSpec cs : repo.findAll()) {
            if (forstaOrdet.isBlank() || !normalize(cs.getCarName()).contains(forstaOrdet)) continue;
            Map<String, Object> rad = new LinkedHashMap<>();
            rad.put("carName", cs.getCarName());
            rad.put("cargoLiters", cs.getCargoLiters());
            rad.put("cargoMaxLiters", cs.getCargoMaxLiters());
            rad.put("arsmodell", ar.get(normalize(cs.getCarName())));
            kandidater.add(rad);
        }
        ut.put("kandidater", kandidater);
        return ut;
    }

    private CargoSpec matchForTitle(String title) {
        if (title == null) return null;
        String cleaned = normalize(CarTitle.stripYear(title));
        String[] titleWords = cleaned.split("\\s+");
        Set<String> titleSet = new HashSet<>(Arrays.asList(titleWords));

        List<CargoSpec> all = repo.findAll();
        Integer titelAr = CarTitle.year(title);
        Map<String, Integer> ar = arsmodeller();

        // Pass 1: varje ord i titeln måste vara ett ORD i radens namn.
        //
        // Var SUBSTRÄNG till 2026-09-05, och det gav fel bil åt både kort och chatt: "x3" finns i
        // "ix3", "tt" i "quattro", "cross" i "across", "a" i "a klass". Uppmätt i drift innan
        // bytet: Mercedes C-, E- och S-klass fick alla A-Klass 370 l, Tesla Model S fick Model 3:s
        // 594, BMW X3 fick iX3:ans 510, Audi Q7 fick SQ7:ans 428, Volvo C70 fick XC70:ans 408,
        // Suzuki S-Cross fick Suzuki Across 446 och Hyundai H-1 (skåpbil) fick i10:ans 252.
        //
        // Bytet mättes mot alla 1 837 bilnamn i /api/cars: 23 titlar bytte till RÄTT rad, 9 fick
        // en volym de saknade, och 22 tappade sin — men 20 av de 22 var fel bil (silence är rätt
        // svar där). De två äkta förlusterna var stavningsvarianter, "Fiat 500 X" mot "Fiat 500X"
        // och "Kia Cee´d" mot "Kia Ceed", och lagades som data. De 52 verkligt renderade
        // korttitlarna i car_video ändrades i ETT fall: Kia Niro EV fick sin egen 475 l i stället
        // för Niro PHEV:s 349. Titlar med trimord ("Volvo XC60 B4 AWD") går via pass 2 och rörs
        // inte av regeln.
        List<CargoSpec> kandidater = all.stream()
                .filter(cs -> {
                    Set<String> namnOrd = new HashSet<>(
                            Arrays.asList(normalize(cs.getCarName()).split("\\s+")));
                    for (String w : titleWords) if (!namnOrd.contains(w)) return false;
                    return true;
                })
                .toList();
        CargoSpec match = valjBastaRad(kandidater, cleaned, titelAr, ar);

        // Pass 2: all stored-name words are exact words in title (longest match wins)
        if (match == null) {
            match = all.stream()
                    .filter(cs -> {
                        String[] nameWords = normalize(cs.getCarName()).split("\\s+");
                        for (String w : nameWords) if (!titleSet.contains(w)) return false;
                        return true;
                    })
                    .max(Comparator.comparingInt(cs ->
                            normalize(cs.getCarName()).split("\\s+").length))
                    .orElse(null);
        }

        return match;
    }

    /**
     * Vilken av flera matchande rader som gäller för titeln.
     *
     * <p><b>Felet regeln lagar</b> (uppmätt 2026-09-04). Pass 1 tog {@code findFirst()} ur
     * {@code repo.findAll()} — alltså tabellordningen — och MG4 har rader från TVÅ generationer:
     * {@code MG4} 363 l (4 287 mm), {@code MG4 Premium/XPOWER} 388 l (samma kaross enligt
     * ev-database) och {@code MG4 Urban} 577 l, som är MY26-bilen på 4 395 mm. Ett kort för
     * "MG4 (2026)" fick förra generationens 363 l, och vilken rad som vann var en slump i
     * radordningen snarare än ett val.
     *
     * <p>Ordningen är: <b>(0)</b> {@link #generationsfilter} när modellen har en kurerad
     * generationsmarkör — två generationer kan bära SAMMA årsmodell och skiljs då inte av något
     * annat steg; <b>(1)</b> en rad vars årsmodell ligger EFTER kortets år får aldrig
     * användas — en kommande generation beskriver inte en äldre bil; <b>(2)</b> högsta årsmodell
     * som ryms i kortets år vinner; <b>(3)</b> odaterade rader vinner när titeln saknar år, så
     * "MG4" utan årtal fortsätter ge basraden; <b>(4)</b> kortast namn (närmast titeln); och
     * <b>(5)</b> vid kvarstående lika: MINSTA volymen. Sista steget är medvetet konservativt —
     * hellre lova för lite bagage än för mycket.
     *
     * <p><b>EN TOM RAD FÅR VINNA, och det är med flit.</b> Tabellen bär både en tom rad
     * {@code Skoda Enyaq} och en ifylld {@code Škoda Enyaq iV} (585 l); den tomma vinner på steg
     * (4) och {@link #formatForTitle} svarar {@code null}. Ett steg som lät ifyllda rader gå före
     * tomma provades 2026-09-05 och <b>backades efter skarp mätning</b>: av tabellens 998 tomma
     * rader kunde 123 nå en ifylld rad, men matchningen är substrängbaserad och raden bredvid är
     * ofta EN ANNAN BIL. Uppmätt i drift innan backningen: {@code Audi TT} → e-tron GT quattro
     * 405 l ("tt" är en substräng av "quattro"), {@code Ford Mustang} → Mustang Mach-E 402 l,
     * {@code Fiat Panda} → Grande Panda 361 l, {@code Citroen C5} → C5 Aircross 580 l,
     * {@code BMW X3 M} → iX3 510 l, {@code Audi e-tron} → A6 e-tron 502 l.
     *
     * <p>Namnen räcker alltså inte för att avgöra om grannraden är samma bil, och regeln bytte
     * TYSTNAD mot FEL TAL på ett kort — precis det som bagagearbetet 09-04 handlade om att få
     * bort. Skuggningen är därför ett DATAFEL (en dubblettrad utan siffra), inte ett fel i
     * rangordningen, och lagas rad för rad med {@code POST /api/admin/upsert/cargospecs}.
     */
    private CargoSpec valjBastaRad(List<CargoSpec> kandidater, String rentTitel,
                                   Integer titelAr, Map<String, Integer> ar) {
        kandidater = generationsfilter(kandidater, rentTitel, titelAr, ar);
        CargoSpec bast = null;
        int bastAr = -1, bastOrd = Integer.MAX_VALUE, bastLiter = Integer.MAX_VALUE;
        for (CargoSpec cs : kandidater) {
            Integer radAr = ar.get(normalize(cs.getCarName()));
            if (titelAr != null && radAr != null && radAr > titelAr) continue;   // framtida generation
            int arPoang = radAr == null ? (titelAr == null ? Integer.MAX_VALUE : -1) : radAr;
            int ord = normalize(cs.getCarName()).split("\\s+").length;
            int liter = cs.getCargoLiters() == null ? Integer.MAX_VALUE : cs.getCargoLiters();
            if (bast == null
                    || arPoang > bastAr
                    || (arPoang == bastAr && ord < bastOrd)
                    || (arPoang == bastAr && ord == bastOrd && liter < bastLiter)) {
                bast = cs; bastAr = arPoang; bastOrd = ord; bastLiter = liter;
            }
        }
        return bast;
    }

    /**
     * Kandidaterna som hör till den nya generationen, när modellen har en kurerad markör i
     * {@link #NYA_GENERATIONEN} och kortets år ligger på eller efter markörens första årsmodell.
     *
     * <p>Filtret är en INSKRÄNKNING och aldrig ett tillägg: hittar det ingen användbar rad med
     * markörordet lämnas listan orörd, och de vanliga stegen avgör som förut. Därför påverkas
     * varken "MG4 XPOWER (2026)" (som redan filtrerat bort Urban-raderna i pass 1) eller ett
     * odaterat "MG4" (som saknar årtal och alltså aldrig når hit).
     *
     * <p>Raden måste vara <b>användbar</b> för titelns år för att räknas — annars kunde filtret
     * lämna kvar bara rader som steg (1) sedan kastar, och matchningen hade tappat en volym den
     * hade före regeln. Ett filter som gör en bil OSYNLIG är värre än ett som väljer fel rad.
     */
    private List<CargoSpec> generationsfilter(List<CargoSpec> kandidater, String rentTitel,
                                              Integer titelAr, Map<String, Integer> ar) {
        if (titelAr == null || rentTitel == null || rentTitel.isBlank() || kandidater.size() < 2)
            return kandidater;
        for (String titelord : rentTitel.split("\\s+")) {
            Generationsmarkor markor = NYA_GENERATIONEN.get(titelord);
            if (markor == null || titelAr < markor.franAr()) continue;
            List<CargoSpec> traffar = kandidater.stream()
                    .filter(cs -> {
                        String namn = normalize(cs.getCarName());
                        if (!Arrays.asList(namn.split("\\s+")).contains(markor.markorord())) return false;
                        Integer radAr = ar.get(namn);
                        return radAr == null || radAr <= titelAr;
                    })
                    .toList();
            if (!traffar.isEmpty()) return traffar;
        }
        return kandidater;
    }

    // Updates existing entries that have null cargo_liters; inserts new entries
    @Transactional
    public int upsertCsv(String csv) {
        List<CargoSpec> all = repo.findAll();
        Map<String, CargoSpec> byNorm = new HashMap<>();
        for (CargoSpec cs : all) byNorm.put(normalize(cs.getCarName()), cs);

        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isBlank() || line.startsWith("#") || line.toLowerCase().startsWith("car_name")) continue;
            String[] parts = line.split(",", 3);
            String name = parts[0].trim().replaceAll("^\"|\"$", "");
            if (name.isBlank() || parts.length < 2) continue;
            Integer liters = null, maxLiters = null;
            try { liters = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
            if (parts.length > 2) { try { maxLiters = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {} }
            if (liters == null) continue;

            String normName = normalize(name);
            CargoSpec existing = byNorm.get(normName);
            if (existing != null && existing.getCargoLiters() == null) {
                existing.setCargoLiters(liters);
                if (maxLiters != null) existing.setCargoMaxLiters(maxLiters);
                repo.save(existing);
                count++;
            } else if (existing == null) {
                CargoSpec newEntry = new CargoSpec(name, liters, maxLiters);
                repo.save(newEntry);
                byNorm.put(normName, newEntry);
                count++;
            }
        }
        return count;
    }

    // CSV format: car_name,cargo_liters,cargo_max_liters (header row optional, cargo cols optional)
    public int importCsv(String csv) {
        Set<String> existing = repo.findAllCarNames().stream()
                .map(CargoSpecService::normalize)
                .collect(Collectors.toSet());
        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isBlank() || line.startsWith("#") || line.toLowerCase().startsWith("car_name")) continue;
            String[] parts = line.split(",", 3);
            String name = parts[0].trim().replaceAll("^\"|\"$", "");
            if (name.isBlank()) continue;
            Integer liters = null, maxLiters = null;
            if (parts.length > 1) { try { liters = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {} }
            if (parts.length > 2) { try { maxLiters = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {} }
            if (!existing.contains(normalize(name))) {
                repo.save(new CargoSpec(name, liters, maxLiters));
                existing.add(normalize(name));
                count++;
            }
        }
        return count;
    }

    // Rear legroom in mm — verified from evspecifications.com; estimates marked with ~
    private static final Map<String, Integer> LEGROOM_MM = new java.util.HashMap<>(Map.ofEntries(
        // Volvo
        Map.entry("Volvo EX30",              821),
        Map.entry("Volvo EX40",              917),
        Map.entry("Volvo C40",               917),
        Map.entry("Volvo XC40",              917),
        Map.entry("Volvo EX60",              950),
        Map.entry("Volvo EX90",              926),
        Map.entry("Volvo ES90",              980),  // ~
        Map.entry("Volvo XC60",              975),  // ~
        Map.entry("Volvo XC90",             1000),  // ~
        Map.entry("Volvo S60",               940),  // ~
        Map.entry("Volvo V60",               940),  // ~
        // Tesla
        Map.entry("Tesla Model 3",           894),
        Map.entry("Tesla Model Y",          1029),
        Map.entry("Tesla Model S",           975),
        // Volkswagen
        Map.entry("Volkswagen ID.3",         879),
        Map.entry("Volkswagen ID.4",         954),
        Map.entry("Volkswagen ID.5",         950),
        Map.entry("Volkswagen ID.7",        1000),
        Map.entry("Volkswagen ID.Buzz",     1100),
        // Hyundai
        Map.entry("Hyundai IONIQ 5",        1001),
        Map.entry("Hyundai IONIQ 6",         985),
        Map.entry("Hyundai IONIQ 9",        1100),  // ~
        Map.entry("Hyundai INSTER",          700),  // ~
        Map.entry("Hyundai Kona",            820),
        Map.entry("Hyundai Kona Electric",   820),
        // Kia
        Map.entry("Kia EV6",               1006),
        Map.entry("Kia EV3",                820),
        Map.entry("Kia EV9",               1087),
        Map.entry("Kia Niro EV",             937),
        Map.entry("Kia Niro",               937),
        // Polestar
        Map.entry("Polestar 2",             862),
        Map.entry("Polestar 3",             950),
        Map.entry("Polestar 4",             910),
        // BMW
        Map.entry("BMW i4",                 944),
        Map.entry("BMW i5",                 980),
        Map.entry("BMW i7",               1040),  // ~
        Map.entry("BMW iX1",                910),
        Map.entry("BMW iX2",                885),  // ~
        Map.entry("BMW iX3",                920),
        Map.entry("BMW iX",                 988),
        // Audi
        Map.entry("Audi Q4 e-tron",         984),
        Map.entry("Audi Q6 e-tron",         990),
        Map.entry("Audi Q8 e-tron",         960),
        Map.entry("Audi A6 e-tron",         990),  // ~
        Map.entry("Audi e-tron GT",         875),  // ~
        // Mercedes
        Map.entry("Mercedes EQA",           693),
        Map.entry("Mercedes EQB",           968),
        Map.entry("Mercedes EQC",           880),
        Map.entry("Mercedes EQE",          1050),
        Map.entry("Mercedes EQS",          1050),
        Map.entry("Mercedes CLA",           870),  // ~
        Map.entry("Mercedes G 580",         900),  // ~
        // Skoda
        Map.entry("Skoda Enyaq",            945),
        Map.entry("Skoda Elroq",            900),
        Map.entry("Skoda Epiq",             820),  // ~
        // Cupra / Seat
        Map.entry("Cupra Born",             879),
        Map.entry("Cupra Tavascan",         900),  // ~
        Map.entry("Cupra Raval",            720),  // ~
        // MG
        Map.entry("MG4",                    870),
        Map.entry("MG ZS EV",              920),
        Map.entry("MG ZS",                  920),
        Map.entry("MG5",                    870),
        // BYD
        Map.entry("BYD Atto 3",             880),
        Map.entry("BYD ATTO 2",             760),  // ~
        Map.entry("BYD Dolphin",            810),
        Map.entry("BYD Seal",               900),  // ~
        Map.entry("BYD TANG",              1000),  // ~
        // Dacia
        Map.entry("Dacia Spring",           843),
        // Fiat
        Map.entry("Fiat 500e",              702),
        Map.entry("Fiat 600e",              780),  // ~
        Map.entry("Fiat Grande Panda",      800),  // ~
        // Abarth
        Map.entry("Abarth 500e",            702),  // same as Fiat 500e
        Map.entry("Abarth 600e",            780),  // same as Fiat 600e
        // Renault
        Map.entry("Renault Zoe",            740),
        Map.entry("Renault Megane E-Tech",  835),
        Map.entry("Renault 5 E-Tech",       770),  // ~
        Map.entry("Renault 4 E-Tech",       850),  // ~
        Map.entry("Renault Scenic E-Tech",  950),  // ~
        Map.entry("Renault Twingo E-Tech",  650),  // ~
        // Nissan
        Map.entry("Nissan Leaf",            808),
        Map.entry("Nissan Ariya",           940),
        Map.entry("Nissan Micra",           750),  // ~
        // Toyota / Subaru
        Map.entry("Toyota bZ4X",            897),
        Map.entry("Toyota C-HR",            870),  // ~
        Map.entry("Toyota Urban Cruiser",   870),  // ~ (same platform as Suzuki e VITARA)
        Map.entry("Subaru Solterra",        942),  // ~ (same platform as bZ4X)
        // Ford
        Map.entry("Ford Mustang Mach-E",    975),
        Map.entry("Ford Capri",             940),  // ~
        Map.entry("Ford Explorer",         1000),  // ~
        Map.entry("Ford Puma Gen-E",        820),  // ~
        // Peugeot
        Map.entry("Peugeot e-208",          762),  // ~
        Map.entry("Peugeot e-2008",         820),  // ~
        Map.entry("Peugeot e-308",          870),  // ~
        Map.entry("Peugeot e-3008",         905),  // ~
        Map.entry("Peugeot e-5008",         960),  // ~
        // Opel
        Map.entry("Opel Corsa Electric",    780),  // ~
        Map.entry("Opel Mokka Electric",    800),  // ~
        Map.entry("Opel Astra Electric",    866),  // ~
        Map.entry("Opel Frontera Electric", 850),  // ~
        Map.entry("Opel Grandland Electric",940),  // ~
        // Citroën
        Map.entry("Citroen e-C3",           750),  // ~
        Map.entry("Citroen e-C4",           867),  // ~
        // DS
        Map.entry("DS 3 E-Tense",           790),  // ~
        Map.entry("DS 4 E-Tense",           880),  // ~
        Map.entry("DS 7 E-Tense",           950),  // ~
        // Mini
        Map.entry("Mini Cooper E",          720),  // ~
        Map.entry("Mini Cooper SE",         720),  // ~
        Map.entry("Mini Aceman",            780),  // ~
        Map.entry("Mini Countryman E",      900),  // ~
        // Porsche
        Map.entry("Porsche Taycan",         875),  // ~
        Map.entry("Porsche Macan Electric", 900),  // ~
        Map.entry("Porsche Cayenne Electric",980), // ~
        // Genesis
        Map.entry("Genesis GV60",           950),  // ~
        Map.entry("Genesis GV70",           960),  // ~
        Map.entry("Genesis G80",           1000),  // ~
        // Smart
        Map.entry("Smart 1",                875),  // ~
        Map.entry("Smart 3",                820),  // ~
        Map.entry("Smart 5",                940),  // ~
        // Alfa Romeo / Lancia
        Map.entry("Alfa Romeo Junior",      850),  // ~
        Map.entry("Lancia Ypsilon",         750),  // ~
        // Jeep
        Map.entry("Jeep Avenger Electric",  800),  // ~
        // Honda
        Map.entry("Honda eNy1",             880),  // ~
        // Lexus
        Map.entry("Lexus RZ",               950),  // ~
        // Leapmotor
        Map.entry("Leapmotor C10",          920),  // ~
        // NIO
        Map.entry("NIO ET5",                910),  // ~
        Map.entry("NIO EL6",                950),  // ~
        // Zeekr
        Map.entry("Zeekr 001",             1000),  // ~
        Map.entry("Zeekr 7X",              960),   // ~
        // Alpine
        Map.entry("Alpine A290",            730),  // ~
        // Mazda
        Map.entry("Mazda 6e",               970),  // ~
        // Xpeng
        Map.entry("Xpeng G6",              950),
        Map.entry("Xpeng G9",              960)    // ~
    ));

    public Integer getLegroom(String title) {
        if (title == null) return null;
        String cleaned = normalize(CarTitle.stripYear(title));
        String[] cleanedWords = cleaned.split("\\s+");
        Set<String> cleanedSet = new HashSet<>(Arrays.asList(cleanedWords));

        // Pass 1: all map-key words are present in title
        return LEGROOM_MM.entrySet().stream()
                .filter(e -> {
                    String[] keyWords = normalize(e.getKey()).split("\\s+");
                    for (String w : keyWords) if (!cleanedSet.contains(w)) return false;
                    return true;
                })
                .max(Comparator.comparingInt(e -> normalize(e.getKey()).split("\\s+").length))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
