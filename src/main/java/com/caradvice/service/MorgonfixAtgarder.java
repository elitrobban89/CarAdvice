package com.caradvice.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Utför nattrutinens beslut — släpp, parkera och dölj insikter — en gång vid uppstart.
 *
 * <p><b>Varför en fil i stället för admin-API:t.</b> Molnrutinen som granskar nattjobben får inte
 * skriva mot produktionen, och det är rätt spärr: den läser skrapat webbinnehåll varje natt. Men
 * därmed fastnade varje köbeslut hos användaren (09-27: Land Cruiser 250 och CLA 45 låg kvar i kön
 * trots att båda säljs). Nu skriver rutinen sina beslut i {@code morgonfix/atgarder.json} på grenen
 * {@code auto/morgonfix}; grenen mergas när CI är grönt, Render deployar, och appen utför filen här.
 * Varje åtgärd bär ett skäl, och git-historiken är granskningsloggen.
 *
 * <p><b>Bara det som går att ångra.</b> Släpp och parkera är en flagga i {@code insight_upcoming}
 * och backas med den motsatta åtgärden. Dölj (2026-09-27) är samma sak för skräprader — skatterader,
 * dubbletter, renoveringsobjekt — i {@code insight_hidden}, och ångras med
 * {@code DELETE /api/admin/insights/{id}/dold}.
 *
 * <p><b>Radering (2026-09-27) — i två steg, med arkiv.</b> Användaren ville ha även raderingen
 * automatisk, "max 20 rader åt gången så vi inte tar bort all data". Tre säkringar:
 * (1) högst {@value #RADERA_TAK} raderingar per fil, annars avvisas hela filen;
 * (2) en rad raderas bara om den varit DOLD i minst {@value #RADERA_EFTER_DYGN} dygn — rutinen
 * döljer först och raderar tidigast en vecka senare, så den som tittar in en gång i veckan hinner
 * se och ångra varje rad; (3) raden KOPIERAS till {@code insight_raderad} innan den tas bort och
 * kan återställas med {@code POST /api/admin/insights/raderade/{id}/aterstall}. Går kopian inte att
 * skriva raderas ingenting.
 *
 * <p><b>En gång per åtgärd.</b> Filen ligger kvar i jarren och läses vid varje omstart, så varje
 * utförd åtgärd noteras i {@code morgonfix_atgard} och hoppas över nästa gång. Utan det hade en rad
 * som nattjobbet parkerat om efter ett släpp släppts igen vid varje deploy. Nyckeln är
 * datum + typ + id, så samma id kan få en ny åtgärd en senare natt.
 *
 * <p><b>Taket kollas innan något rörs.</b> Fler än {@value #TAK} NYA åtgärder betyder att något
 * skrivit fel — hela filen avvisas då, ingen del utförs. Samma säkring som autosläppet
 * ({@link UpcomingAutoReleaseService}). Allt är fail-soft: uppstarten får aldrig falla här.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class MorgonfixAtgarder {

    private static final Logger log = LoggerFactory.getLogger(MorgonfixAtgarder.class);

    static final String RESURS = "morgonfix/atgarder.json";
    static final int TAK = 12;
    static final int RADERA_TAK = 20;
    static final int RADERA_EFTER_DYGN = 7;
    static final Set<String> TYPER = Set.of("slapp", "parkera", "dolj", "radera");
    private static final java.time.format.DateTimeFormatter DOLD_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Atgard(String datum, String typ, long id, String skal) {
        String nyckel() { return datum + "|" + typ + "|" + id; }
    }

    public record Utfall(int nya, boolean avvisad, List<String> rader) {}

    private final JdbcTemplate jdbc;
    private final ExpertInsightService insikter;
    private final UpcomingInsightService kon;
    private final Supplier<InputStream> kalla;
    private final ObjectMapper json = new ObjectMapper();

    /** Se PrisUppvarmning: två konstruktorer kräver @Autowired, annars dör uppstarten. */
    @Autowired
    public MorgonfixAtgarder(JdbcTemplate jdbc, ExpertInsightService insikter, UpcomingInsightService kon) {
        this(jdbc, insikter, kon, MorgonfixAtgarder::lasResurs);
    }

    MorgonfixAtgarder(JdbcTemplate jdbc, ExpertInsightService insikter, UpcomingInsightService kon,
                      Supplier<InputStream> kalla) {
        this.jdbc = jdbc;
        this.insikter = insikter;
        this.kon = kon;
        this.kalla = kalla;
    }

    private static InputStream lasResurs() {
        try {
            ClassPathResource r = new ClassPathResource(RESURS);
            return r.exists() ? r.getInputStream() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void vidUppstart() {
        try {
            Utfall u = kor();
            if (u.avvisad() || !u.rader().isEmpty())
                log.info("Morgonfixens åtgärder: {} nya, avvisad={}, {}", u.nya(), u.avvisad(), u.rader());
        } catch (Exception e) {
            log.warn("Morgonfixens åtgärder kunde inte köras: {}", e.getMessage());
        }
    }

    void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS morgonfix_atgard (
                nyckel VARCHAR(120) PRIMARY KEY,
                utford_at VARCHAR(40),
                utfall VARCHAR(300)
            )
            """);
    }

    /** Läser filen och utför de åtgärder som inte redan är utförda. */
    public Utfall kor() {
        List<Atgard> alla = las();
        if (alla.isEmpty()) return new Utfall(0, false, List.of());
        ensureTable();

        List<Atgard> nya = new ArrayList<>();
        for (Atgard a : alla) {
            Integer finns = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM morgonfix_atgard WHERE nyckel = ?", Integer.class, a.nyckel());
            if (finns == null || finns == 0) nya.add(a);
        }
        long raderingar = nya.stream().filter(a -> a.typ().equals("radera")).count();
        long ovriga = nya.size() - raderingar;
        if (ovriga > TAK || raderingar > RADERA_TAK) {
            log.warn("Morgonfixens åtgärder AVVISADE: {} nya åtgärder (tak {}) och {} raderingar (tak {}) — ingenting utfört",
                    ovriga, TAK, raderingar, RADERA_TAK);
            return new Utfall(nya.size(), true, List.of());
        }

        List<String> rader = new ArrayList<>();
        for (Atgard a : nya) {
            String utfall = utfor(a);
            jdbc.update("INSERT INTO morgonfix_atgard(nyckel, utford_at, utfall) VALUES (?, ?, ?)",
                    a.nyckel(), nu(), kapa(utfall + " — " + a.skal(), 300));
            rader.add(a.typ() + " " + a.id() + ": " + utfall);
        }
        return new Utfall(nya.size(), false, rader);
    }

    private String utfor(Atgard a) {
        try {
            if (a.typ().equals("slapp"))
                return kon.release(a.id()) ? "släppt" : "låg inte i kön";
            if (!insikter.exists(a.id())) return "finns inte";
            if (a.typ().equals("dolj"))
                return kon.dolj(a.id(), a.skal()) ? "dold" : "FEL: kunde inte döljas";
            if (a.typ().equals("radera")) return radera(a);
            kon.mark(a.id());
            return kon.isUpcoming(a.id()) ? "parkerad" : "FEL: kunde inte parkeras";
        } catch (Exception e) {
            return "FEL: " + e.getMessage();
        }
    }

    private String radera(Atgard a) {
        String sedan = kon.doldSedan(a.id());
        if (sedan == null) return "ej raderad: raden är inte dold - dölj först, radera tidigast "
                + RADERA_EFTER_DYGN + " dygn senare";
        long dygn;
        try {
            dygn = java.time.Duration.between(
                    java.time.LocalDateTime.parse(sedan, DOLD_FORMAT).atZone(ZoneId.of("Europe/Stockholm")),
                    ZonedDateTime.now(ZoneId.of("Europe/Stockholm"))).toDays();
        } catch (Exception e) {
            return "ej raderad: kunde inte läsa när raden doldes (" + sedan + ")";
        }
        if (dygn < RADERA_EFTER_DYGN)
            return "ej raderad: dold i " + dygn + " dygn, kräver " + RADERA_EFTER_DYGN;

        ensureArkiv();
        int kopierade = jdbc.update("""
            INSERT INTO insight_raderad(insight_id, expert_name, car_make, car_model, fuel_type, category,
                                        insight, rating, raderad_at, skal)
            SELECT id, expert_name, car_make, car_model, fuel_type, category, insight, rating, ?, ?
            FROM expert_insight WHERE id = ?
            """, nu(), kapa(a.skal(), 300), a.id());
        if (kopierade != 1) return "FEL: kunde inte arkiveras - inte raderad";
        if (!insikter.deleteById(a.id())) return "finns inte";
        kon.visa(a.id()); // dold-raden pekar annars på en insikt som inte finns
        return "raderad (arkiverad)";
    }

    void ensureArkiv() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS insight_raderad (
                insight_id BIGINT PRIMARY KEY,
                expert_name VARCHAR(255),
                car_make VARCHAR(255),
                car_model VARCHAR(255),
                fuel_type VARCHAR(255),
                category VARCHAR(255),
                insight TEXT,
                rating INTEGER,
                raderad_at VARCHAR(40),
                skal VARCHAR(300)
            )
            """);
    }

    /** Arkiverade (raderade) insikter, nyast först. */
    public List<Map<String, Object>> raderade() {
        try {
            ensureArkiv();
            return jdbc.queryForList("SELECT * FROM insight_raderad ORDER BY raderad_at DESC");
        } catch (Exception e) {
            log.warn("insight_raderad kunde inte läsas: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Lägger tillbaka en raderad insikt ur arkivet. Den får ett NYTT id (tabellens sekvens går inte
     * att backa portabelt), och arkivraden tas bort först när återinsättningen lyckats.
     *
     * @return true om raden fanns i arkivet och är återställd
     */
    public boolean aterstall(long gammaltId) {
        ensureArkiv();
        int in = jdbc.update("""
            INSERT INTO expert_insight(expert_name, car_make, car_model, fuel_type, category, insight, rating)
            SELECT expert_name, car_make, car_model, fuel_type, category, insight, rating
            FROM insight_raderad WHERE insight_id = ?
            """, gammaltId);
        if (in != 1) return false;
        jdbc.update("DELETE FROM insight_raderad WHERE insight_id = ?", gammaltId);
        log.info("Insikt {} återställd ur arkivet", gammaltId);
        return true;
    }

    /** Filens åtgärder. Rader med okänd typ, felaktigt datum, saknat id eller saknat skäl hoppas över. */
    List<Atgard> las() {
        try (InputStream in = kalla.get()) {
            if (in == null) return List.of();
            JsonNode rot = json.readTree(in);
            List<Atgard> ut = new ArrayList<>();
            for (JsonNode n : rot.path("atgarder")) {
                String datum = n.path("datum").asString("");
                String typ = n.path("typ").asString("");
                long id = n.path("id").asLong(0);
                String skal = n.path("skal").asString("").trim();
                if (!datum.matches("\\d{4}-\\d{2}-\\d{2}") || !TYPER.contains(typ) || id <= 0 || skal.isEmpty()) {
                    log.warn("Morgonfix-åtgärd ogiltig, hoppas över: {}", n);
                    continue;
                }
                ut.add(new Atgard(datum, typ, id, skal));
            }
            return ut;
        } catch (Exception e) {
            log.warn("{} kunde inte läsas: {}", RESURS, e.getMessage());
            return List.of();
        }
    }

    /** Alla utförda åtgärder, nyast först — rutinen läser dem nästa natt. */
    public List<Map<String, Object>> utforda() {
        try {
            ensureTable();
            return jdbc.queryForList(
                    "SELECT nyckel, utford_at, utfall FROM morgonfix_atgard ORDER BY utford_at DESC");
        } catch (Exception e) {
            log.warn("morgonfix_atgard kunde inte läsas: {}", e.getMessage());
            return List.of();
        }
    }

    private static String kapa(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nu() {
        return ZonedDateTime.now(ZoneId.of("Europe/Stockholm")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
