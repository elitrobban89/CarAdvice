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

    public ExpertInsightService(ExpertInsightRepository repo) {
        this.repo = repo;
    }

    public String buildExpertContext(CarPreferences prefs) {
        String category = prefs.carCategory();
        String fuelType = ("spelar ingen roll".equalsIgnoreCase(prefs.fuelType()))
                ? category
                : prefs.fuelType();

        List<ExpertInsight> matched = repo.findByCategoryIgnoreCaseOrFuelTypeIgnoreCase(category, fuelType);
        if (matched.isEmpty()) return "";

        // Slumpat urval så hela insiktspoolen roterar in i prompten över tid — med fast
        // databasordning användes alltid samma äldsta rader och nattens nya insikter nådde aldrig AI:n
        List<ExpertInsight> pool = new ArrayList<>(matched);
        Collections.shuffle(pool);
        List<ExpertInsight> insights = pool.subList(0, Math.min(MAX_RECOMMEND_INSIGHTS, pool.size()));

        return formatInsights(insights, "Expertinsikter (använd som extra underlag i din analys):\n");
    }

    public String buildChatExpertContext(List<String> recentMessages) {
        List<ExpertInsight> all = repo.findAll();
        if (all.isEmpty()) return "";

        // Only include insights whose car make is explicitly mentioned in the conversation.
        // Never add general (carMake == null) insights — they appear regardless of topic and cause off-topic noise.
        String combined = String.join(" ", recentMessages).toLowerCase();
        List<ExpertInsight> matched = new ArrayList<>();

        for (ExpertInsight i : all) {
            if (i.getCarMake() != null && combined.contains(i.getCarMake().toLowerCase())) {
                matched.add(i);
            }
        }

        if (matched.isEmpty()) return "";
        List<ExpertInsight> selected = matched.stream().limit(3).toList();
        return formatInsights(selected, "Bilexpertinsikter (referera BARA till dessa om de direkt gäller den bil användaren frågar om just nu — inkludera dem INTE om de handlar om en annan bil):\n");
    }

    /** Max insikter som visas publikt per bilkort */
    static final int MAX_CARD_INSIGHTS = 3;

    // Drivlinemarkörer — mest specifika först: "PHEV" innehåller "HEV" som innehåller "EV",
    // därav helordsmatchning och prövningsordningen phev → hev → ev
    private static final java.util.regex.Pattern PHEV_MARKER =
            java.util.regex.Pattern.compile("\\b(phev|laddhybrid|plug[- ]?in)\\b");
    private static final java.util.regex.Pattern HEV_MARKER =
            java.util.regex.Pattern.compile("\\b(hev|elhybrid|self[- ]?charging|hybrid)\\b");
    private static final java.util.regex.Pattern EV_MARKER =
            java.util.regex.Pattern.compile("\\b(ev|elbil|electric)\\b");

    /** Drivlina ur en text: "phev", "hev" eller "ev" — null om ospecificerad. */
    static String drivetrainOf(String s) {
        if (s == null) return null;
        String t = s.toLowerCase();
        if (PHEV_MARKER.matcher(t).find()) return "phev";
        if (HEV_MARKER.matcher(t).find()) return "hev";
        if (EV_MARKER.matcher(t).find()) return "ev";
        return null;
    }

    /**
     * Publika insikter för ett bilkort. Märket måste finnas i titeln; modellspecifika
     * träffar prioriteras och insikter om en ANNAN modell av samma märke utesluts
     * (en Model S-insikt ska inte visas på ett Model 3-kort). Anger titeln en drivlina
     * utesluts insikter om en annan drivlinevariant — Vi Bilägares Niro HEV-test
     * (4,8 l/100 km) ska aldrig visas på ett Kia Niro EV-kort. Slumpat urval inom
     * grupperna så hela poolen roterar över tid.
     */
    public List<Map<String, Object>> findForCarTitle(String title) {
        if (title == null || title.isBlank()) return List.of();
        String t = title.toLowerCase();
        String titleDrive = drivetrainOf(t);

        List<ExpertInsight> makeAndModel = new ArrayList<>();
        List<ExpertInsight> makeOnly = new ArrayList<>();
        for (ExpertInsight i : repo.findAll()) {
            if (i.getCarMake() == null || !t.contains(i.getCarMake().toLowerCase())) continue;
            if (titleDrive != null) {
                String insightDrive = drivetrainOf(i.getCarModel());
                if (insightDrive == null) insightDrive = drivetrainOf(i.getInsight());
                if (insightDrive != null && !titleDrive.equals(insightDrive)) continue;
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
        Set<String> seenTexts = new HashSet<>();
        return selected.stream()
                .filter(i -> seenTexts.add(i.getInsight()))
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
