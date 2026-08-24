package com.caradvice.service;

import com.caradvice.model.CarPreferences;
import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ExpertInsightService {

    /** Max insikter i rekommendationsprompten — 5 st ≈ 300 tokens, ryms i TPM-budgeten */
    static final int MAX_RECOMMEND_INSIGHTS = 5;

    private final ExpertInsightRepository repo;
    private final EvSpecService evSpecService;
    private final UpcomingInsightService upcomingService;

    public ExpertInsightService(ExpertInsightRepository repo, EvSpecService evSpecService,
                                UpcomingInsightService upcomingService) {
        this.repo = repo;
        this.evSpecService = evSpecService;
        this.upcomingService = upcomingService;
    }

    /**
     * Filtrerar bort insikter om bilar som ännu inte går att köpa i Sverige. De sparas av
     * scrapern men får inte nå prompter eller bilkort — en insikt om en bil läsaren inte
     * kan köpa läses som en rekommendation. Admin-vyerna går medvetet förbi det här.
     */
    private List<ExpertInsight> visible(List<ExpertInsight> insights) {
        Set<Long> hidden = upcomingService.hiddenIds();
        if (hidden.isEmpty()) return insights;
        return insights.stream().filter(i -> !hidden.contains(i.getId())).toList();
    }

    public String buildExpertContext(CarPreferences prefs) {
        String category = prefs.carCategory();
        String fuelType = ("spelar ingen roll".equalsIgnoreCase(prefs.fuelType()))
                ? category
                : prefs.fuelType();

        List<ExpertInsight> matched = visible(repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase(category, fuelType));
        if (matched.isEmpty()) return "";

        // Slumpat urval så hela insiktspoolen roterar in i prompten över tid — med fast
        // databasordning användes alltid samma äldsta rader och nattens nya insikter nådde aldrig AI:n
        List<ExpertInsight> pool = new ArrayList<>(matched);
        Collections.shuffle(pool);
        List<ExpertInsight> insights = pool.subList(0, Math.min(MAX_RECOMMEND_INSIGHTS, pool.size()));

        return formatInsights(insights, "Expertinsikter (använd som extra underlag i din analys):\n");
    }

    /** Max insikter som injiceras i chattens systemprompt */
    static final int MAX_CHAT_INSIGHTS = 3;

    public String buildChatExpertContext(List<String> recentMessages) {
        return buildChatExpertContext(recentMessages, null);
    }

    /**
     * carContext = de bilar användaren just fått rekommenderade/valt. Räknas med i
     * matchningen så att en vald Audi ger Audi-insikter även om användaren skriver
     * "vad tycker du om den?" utan att nämna märket.
     */
    public String buildChatExpertContext(List<String> recentMessages, String carContext) {
        List<ExpertInsight> all = visible(repo.findAll());
        if (all.isEmpty()) return "";

        // Only include insights whose car make is explicitly mentioned in the conversation.
        // Never add general (carMake == null) insights — they appear regardless of topic and cause off-topic noise.
        String combined = flattenSpaces(String.join(" ", recentMessages) + " "
                + (carContext == null ? "" : carContext));
        List<ExpertInsight> modelMatches = new ArrayList<>();
        List<ExpertInsight> makeMatches = new ArrayList<>();

        for (ExpertInsight i : all) {
            if (i.getCarMake() == null || !combined.contains(i.getCarMake().toLowerCase())) continue;
            String model = i.getCarModel();
            if (model != null && !model.isBlank() && combined.contains(model.toLowerCase())) modelMatches.add(i);
            else makeMatches.add(i);
        }

        if (modelMatches.isEmpty() && makeMatches.isEmpty()) return "";

        // Modellträffar går före rena märkesträffar, och märkesträffarna roteras: med fast
        // databasordning vann alltid de äldsta raderna (26 Audi-rader → samma tre varje gång)
        Collections.shuffle(modelMatches);
        Collections.shuffle(makeMatches);
        List<ExpertInsight> selected = new ArrayList<>(modelMatches);
        selected.addAll(makeMatches);
        if (selected.size() > MAX_CHAT_INSIGHTS) selected = selected.subList(0, MAX_CHAT_INSIGHTS);

        return formatInsights(selected, "Bilexpertinsikter (referera BARA till dessa om de direkt gäller den bil användaren frågar om just nu — inkludera dem INTE om de handlar om en annan bil):\n");
    }

    /** Max insikter som visas publikt per bilkort */
    static final int MAX_CARD_INSIGHTS = 3;

    // Drivlinemarkörer — mest specifika först: "PHEV" innehåller "HEV" som innehåller "EV",
    // därav helordsmatchning och prövningsordningen phev → hev → ev → ice.
    // Substantiven tillåter svenska ändelser (\w*): "\bhybrid\b" missade "hybridEN" och
    // "laddhybridER", vilket var ofarligt när utfallet ändå blev null — men sedan ICE-ledet
    // tillkom skulle en obestämd hybridtext i stället fastna på "bensinmotor" och klassas
    // som förbränning, och därmed filtreras bort från hybridkort.
    private static final java.util.regex.Pattern PHEV_MARKER =
            java.util.regex.Pattern.compile("\\b(phev|laddhybrid\\w*|plug[- ]?in)\\b");
    private static final java.util.regex.Pattern HEV_MARKER =
            java.util.regex.Pattern.compile("\\b(hev|elhybrid\\w*|self[- ]?charging|hybrid\\w*)\\b");
    private static final java.util.regex.Pattern EV_MARKER =
            java.util.regex.Pattern.compile("\\b(ev|elbil\\w*|electric)\\b");
    /**
     * Förbränningsmarkörer — prövas SIST, efter hybridmarkörerna, eftersom en hybridinsikt
     * nästan alltid nämner bensinmotorn också ("laddhybriden ... 2,7 l/100 km bensin"): den
     * ska klassas som phev/hev, inte ice. Bara ord som är omöjliga på en ren elbil får stå
     * här. Medvetet UTELÄMNADE: "turbo" (Porsche Taycan Turbo S är en elbil), "växellåda"
     * och "olja" (elbilar har reduktionsväxel med olja), samt "skyactiv" (Mazda MX-30 är en
     * elbil med e-Skyactiv-drivlina).
     *
     * MOTORKODERNA tillkom 2026-08-24: en bensin-Polo-insikt ("115-hästkrafts TSI-motor")
     * saknade varje ord i listan ovan, fick drivlina null och slank därför förbi filtret rakt
     * in på elbilskortet ID. Polo — delsträngen "polo" matchar "id. polo". Koderna nedan är
     * omöjliga på en ren elbil. Att en laddhybrid också bär dem är ofarligt: ICE prövas SIST,
     * och en ice-insikt utesluts bara på EV-kort ({@link #drivetrainsCompatible}).
     */
    private static final java.util.regex.Pattern ICE_MARKER = java.util.regex.Pattern.compile(
            "\\b(bensin\\w*|diesel\\w*|etanol\\w*|e5|e10|e20|e85"
            + "|kamrem\\w*|kamkedj\\w*|partikelfilter\\w*|avgas\\w*|tändstift\\w*|förgasar\\w*"
            + "|tsi|tfsi|tdi|hdi|bluehdi|dci|cdti|crdi|multijet|ecoboost|puretech)\\b");

    /**
     * Gemener med alla sorters blanktecken nedkokta till ett vanligt mellanslag. Behövs eftersom
     * matchningen nedan är {@code contains("ioniq 5")} mot AI-text som ibland innehåller hårt
     * (U+00A0) eller smalt hårt mellanslag (U+202F) — se motiveringen i EvSpecService.normalize.
     * Bara "höstacken" städas; märken och modeller i databasen kommer från kurerad CSV.
     */
    static String flattenSpaces(String s) {
        if (s == null) return "";
        return s.replaceAll("\\p{Cf}", "")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim()
                .toLowerCase();
    }

    /** Drivlina ur en text: "phev", "hev", "ev" eller "ice" — null om ospecificerad. */
    static String drivetrainOf(String s) {
        if (s == null) return null;
        String t = s.toLowerCase();
        if (PHEV_MARKER.matcher(t).find()) return "phev";
        if (HEV_MARKER.matcher(t).find()) return "hev";
        if (EV_MARKER.matcher(t).find()) return "ev";
        if (ICE_MARKER.matcher(t).find()) return "ice";
        return null;
    }

    /**
     * Kortets drivlina. Titelorden först ("Kia Niro EV" → ev), men de flesta elbilstitlar
     * saknar drivlineord helt — "EV6", "EX30" och "Mach-E" är ETT ord var, så {@code \bev\b}
     * missar dem och filtret nedan stod avstängt på precis de kort som behövde det. Faller
     * därför tillbaka på ev_spec: finns bilen där är den en ren elbil. Fail open — ett
     * DB-fel får inte släcka insikterna på kortet, bara filtreringen.
     */
    private String titleDrivetrain(String rawTitle, String flattened) {
        String fromText = drivetrainOf(flattened);
        if (fromText != null) return fromText;
        try {
            // isKnownEv bär själv företrädet ice_consumption-före-ev_spec sedan 2026-08-14:
            // "Hyundai Kona" och "Kia Niro" finns som både bensinbil och elbil, och en naken
            // titel svarade tidigare "ev" — varpå kortet klassades som ren elbil och tappade
            // sina förbränningsinsikter. Kopian låg först här; den flyttades in i metoden så
            // att alla anropare får samma svar. Rör den inte här.
            return evSpecService.isKnownEv(rawTitle) ? "ev" : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Får en insikt om drivlinan {@code insight} visas på ett kort med drivlinan {@code card}?
     * Samma drivlina duger alltid. Förbränningsinnehåll ("ice") är däremot fel BARA på en ren
     * elbil — en laddhybrid och en hybrid har faktiskt en bensinmotor, så Toyotas oljebytesråd
     * hör hemma på ett Corolla Hybrid-kort även om det nämner "bensinmotorer". Utan det
     * undantaget hade ice-ledet tystat hybridkorten på köpet.
     */
    static boolean drivetrainsCompatible(String card, String insight) {
        if (card.equals(insight)) return true;
        if ("ice".equals(insight)) return !"ev".equals(card);
        return false;
    }

    /**
     * Publika insikter för ett bilkort. Märket måste finnas i titeln; modellspecifika
     * träffar prioriteras och insikter om en ANNAN modell av samma märke utesluts
     * (en Model S-insikt ska inte visas på ett Model 3-kort). Har kortet en känd drivlina
     * (se {@link #titleDrivetrain}) utesluts insikter om en annan drivlinevariant — Vi
     * Bilägares Niro HEV-test (4,8 l/100 km) ska aldrig visas på ett Kia Niro EV-kort, och
     * en märkesbred förbränningsvarning (Fords EcoBoost-kamrem, BMW:s N47-diesel) ska aldrig
     * visas på ett Mustang Mach-E- eller i4-kort. Slumpat urval inom grupperna så hela
     * poolen roterar över tid.
     */
    public List<Map<String, Object>> findForCarTitle(String title) {
        if (title == null || title.isBlank()) return List.of();
        String t = flattenSpaces(title);
        String titleDrive = titleDrivetrain(title, t);

        List<ExpertInsight> makeAndModel = new ArrayList<>();
        List<ExpertInsight> makeOnly = new ArrayList<>();
        for (ExpertInsight i : visible(repo.findAll())) {
            if (i.getCarMake() == null || !t.contains(i.getCarMake().toLowerCase())) continue;
            if (titleDrive != null) {
                String insightDrive = drivetrainOf(i.getCarModel());
                if (insightDrive == null) insightDrive = drivetrainOf(i.getInsight());
                if (insightDrive != null && !drivetrainsCompatible(titleDrive, insightDrive)) continue;
            }
            if (i.getCarModel() != null) {
                if (t.contains(i.getCarModel().toLowerCase())) makeAndModel.add(i);
            } else {
                makeOnly.add(i);
            }
        }

        Collections.shuffle(makeAndModel);
        Collections.shuffle(makeOnly);
        List<ExpertInsight> selected = new ArrayList<>(makeAndModel);
        selected.addAll(makeOnly);

        // DB:n innehåller enstaka dubblettrader (samma insikt sparad två gånger) —
        // visa aldrig samma text två gånger på ett kort
        Set<String> seenTexts = new HashSet<>(); // "Grindvakt" påse som vägrar dubletter
        return selected.stream()
                .filter(i -> seenTexts.add(i.getInsight()))
                /*Knepet är att add() gör
två saker i ett enda anrop: den lägger in
texten och svarar om den var ny ( true ) eller
redan fanns ( false ). Eftersom svaret
används direkt som villkor i filter() blir
raden ett filter: första gången en insiktstext
passerar släpps den igenom, andra gången
svarar add() false och insikten faller bort.
Att den "känner igen" texten avgörs av
String :ens equals/hashCode — samma
tecken i samma ordning. Utan raden kan
samma expertcitat dyka upp tre gånger på
ett bilkort.*/
                .limit(MAX_CARD_INSIGHTS).map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("expert", resolveExpertName(i.getExpertName()));
            m.put("insight", i.getInsight());
            if (i.getRating() != null) m.put("rating", i.getRating());
            return m;
        }).toList();
    }

    public ExpertInsight save(ExpertInsight insight) {
        return repo.save(insight);
    }

    /** Totalt antal expertinsikter i databasen — matar uppstartssplashens "X insikter". */
    public long count() {
        return repo.count();
    }

    /** Admin: senaste insikterna (högsta id först — tabellen saknar created_at), valfritt filtrerat på expert/källa. */
    public List<Map<String, Object>> listRecent(String expert, int limit) {
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<ExpertInsight> rows = (expert == null || expert.isBlank())
                ? repo.findAllByOrderByIdDesc(page)
                : repo.findByExpertNameIgnoreCaseOrderByIdDesc(expert, page);
        return rows.stream().map(this::toAdminMap).toList();
    }

    private Map<String, Object> toAdminMap(ExpertInsight i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("expert", resolveExpertName(i.getExpertName()));
        m.put("carMake", i.getCarMake());
        m.put("carModel", i.getCarModel());
        m.put("category", i.getCategory());
        m.put("insight", i.getInsight());
        m.put("rating", i.getRating());
        return m;
    }

    /** Fält som får ändras via admin-PATCH — id styrs av URL:en och expert av importflödena */
    private static final List<String> EDITABLE_FIELDS =
            List.of("carMake", "carModel", "fuelType", "category", "insight", "rating");

    /**
     * Admin: rätta enskilda fält på en insikt — felkategoriserade rader (Kia EV3 som
     * "smaabil") behövde tidigare raderas eftersom bara DELETE fanns. Endast nycklar
     * som skickas med ändras; null/tom sträng tömmer fältet, utom insight och carMake
     * som aldrig får bli tomma. category/fuelType normaliseras till gemener eftersom
     * buildExpertContext matchar exakt mot frontendens värden.
     * @return uppdaterad rad, tom Optional om id saknas
     * @throws IllegalArgumentException vid okänt fältnamn eller ogiltigt värde
     */
    @Transactional
    public Optional<Map<String, Object>> updateInsight(Long id, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty())
            throw new IllegalArgumentException("Ange minst ett fält att ändra: " + String.join(", ", EDITABLE_FIELDS));
        for (String key : fields.keySet())
            if (!EDITABLE_FIELDS.contains(key))
                throw new IllegalArgumentException("Okänt fält: " + key + " (tillåtna: " + String.join(", ", EDITABLE_FIELDS) + ")");

        ExpertInsight row = (id == null) ? null : repo.findById(id).orElse(null);
        if (row == null) return Optional.empty();

        if (fields.containsKey("insight"))  row.setInsight(requireText(fields.get("insight"), "insight"));
        if (fields.containsKey("carMake"))  row.setCarMake(requireText(fields.get("carMake"), "carMake"));
        if (fields.containsKey("carModel")) row.setCarModel(optionalText(fields.get("carModel"), false));
        if (fields.containsKey("fuelType")) row.setFuelType(optionalText(fields.get("fuelType"), true));
        if (fields.containsKey("category")) row.setCategory(optionalText(fields.get("category"), true));
        if (fields.containsKey("rating"))   row.setRating(parseRating(fields.get("rating")));
        return Optional.of(toAdminMap(repo.save(row)));
    }

    private String requireText(Object v, String field) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        throw new IllegalArgumentException(field + " måste vara en icke-tom sträng");
    }

    private String optionalText(Object v, boolean lowercase) {
        if (v == null) return null;
        if (!(v instanceof String s)) throw new IllegalArgumentException("Fältet måste vara en sträng eller null");
        if (s.isBlank()) return null;
        return lowercase ? s.trim().toLowerCase() : s.trim();
    }

    private Integer parseRating(Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        int r;
        try {
            r = (v instanceof Number n) ? n.intValue() : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("rating måste vara ett heltal 1-10 eller null");
        }
        if (r < 1 || r > 10) throw new IllegalArgumentException("rating måste vara 1-10");
        return r;
    }

    /** Admin: radera en enskild insikt (skräprad ur skrapningen). @return true om raden fanns */
    public boolean deleteById(Long id) {
        if (id == null || !repo.existsById(id)) return false;
        repo.deleteById(id);
        return true;
    }

    /**
     * Admin: byt kategoristavning på alla rader ("småbil" → "smaabil"). buildExpertContext
     * matchar exakt mot frontendens kategorivärden, så en avvikande stavning gör att
     * raderna aldrig når rekommendationsprompten. @return antal uppdaterade rader
     */
    @Transactional
    public int renameCategory(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) return 0;
        return repo.renameCategory(from.trim(), to.trim());
    }

    @Transactional
    public void deleteByExpert(String expertName) {
        repo.deleteByExpertName(expertName);
    }

    public long countByExpert(String expertName) {
        return repo.countByExpertName(expertName);
    }

    public int importCsv(String csv, String expertName) {
        int count = 0;
        for (String line : csv.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("car_make")) continue;
            String[] f = SafetyRatingService.parseCsvLine(line);
            if (f.length < 5) continue;
            String carMake   = blank(f[0]) ? null : f[0];
            String carModel  = blank(f[1]) ? null : f[1];
            String fuelType  = blank(f[2]) ? null : f[2];
            String category  = blank(f[3]) ? null : f[3];
            String insight   = f[4];
            Integer rating   = null;
            if (f.length > 5 && !blank(f[5])) {
                try { rating = Integer.parseInt(f[5].trim()); } catch (NumberFormatException ignored) {}
            }
            repo.save(new ExpertInsight(expertName, carMake, carModel, fuelType, category, insight, rating));
            count++;
        }
        return count;
    }

    public int importCsv(String csv) {
        return importCsv(csv, "Bilexpert");
    }

    private boolean blank(String s) { return s == null || s.isBlank() || s.equals("null"); }

    private String formatInsights(List<ExpertInsight> insights, String header) {
        StringBuilder sb = new StringBuilder(header);
        for (ExpertInsight i : insights) {
            sb.append("- ");
            if (i.getCarMake() != null && i.getCarModel() != null)
                sb.append(i.getCarMake()).append(" ").append(i.getCarModel()).append(": ");
            sb.append(i.getInsight());
            if (i.getRating() != null)
                sb.append(" [").append(i.getRating()).append("/10]");
            sb.append(" (").append(resolveExpertName(i.getExpertName())).append(")\n");
        }
        return sb.toString();
    }

    private String resolveExpertName(String name) {
        return (name == null || name.isBlank()) ? "Bilexpert" : name;
    }
}
