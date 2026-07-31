package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Insikter om bilar som är bekräftade för Sverige men ännu inte går att köpa här.
 * Relevansvakten markerar dem i stället för att slänga dem — innehållet blir användbart
 * när bilen släpps, men fram till dess får det varken nå rekommendationsprompten eller
 * bilkorten (en insikt om en bil läsaren inte kan köpa är i praktiken en felrekommendation).
 *
 * Flaggan ligger i en egen tabell i stället för en kolumn på expert_insight: prod kör
 * ddl-auto=validate, så en ny mappad kolumn hade fällt uppstarten tills schemat ändrats
 * för hand. Samma mönster som web_insight_seen och recommendation_feedback.
 */
@Service
public class UpcomingInsightService {

    private static final Logger log = LoggerFactory.getLogger(UpcomingInsightService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final JdbcTemplate jdbc;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public UpcomingInsightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS insight_upcoming (
                insight_id BIGINT PRIMARY KEY,
                marked_at VARCHAR(40)
            )
            """);
    }

    /** Markerar en sparad insikt som "kommande modell". */
    public void mark(Long insightId) {
        if (insightId == null) return;
        try {
            ensureTable();
            jdbc.update("DELETE FROM insight_upcoming WHERE insight_id = ?", insightId);
            jdbc.update("INSERT INTO insight_upcoming(insight_id, marked_at) VALUES (?, ?)", insightId, now());
            cache.clear();
        } catch (Exception e) {
            log.warn("Kunde inte markera insikt {} som kommande: {}", insightId, e.getMessage());
        }
    }

    /** Släpper insikten — bilen går att köpa, raden syns som vilken insikt som helst. */
    public boolean release(Long insightId) {
        try {
            ensureTable();
            int removed = jdbc.update("DELETE FROM insight_upcoming WHERE insight_id = ?", insightId);
            cache.clear();
            return removed > 0;
        } catch (Exception e) {
            log.warn("Kunde inte släppa insikt {}: {}", insightId, e.getMessage());
            return false;
        }
    }

    /**
     * Id:n som ska döljas. Cachead i 5 min — den läses på varje insiktsuppslag, och
     * tabellen ändras bara vid nattsynken eller en admin-åtgärd.
     * Vid DB-fel returneras en tom mängd: hellre en kommande insikt synlig än att
     * insiktsfunktionen slocknar helt.
     */
    @SuppressWarnings("unchecked")
    public Set<Long> hiddenIds() {
        Long fetchedAt = (Long) cache.get("at");
        if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS) {
            return (Set<Long>) cache.get("ids");
        }
        Set<Long> ids;
        try {
            ensureTable();
            ids = Set.copyOf(jdbc.queryForList("SELECT insight_id FROM insight_upcoming", Long.class));
        } catch (Exception e) {
            log.warn("Kunde inte läsa kommande-flaggor: {}", e.getMessage());
            return Set.of();
        }
        cache.put("ids", ids);
        cache.put("at", System.currentTimeMillis());
        return ids;
    }

    public boolean isUpcoming(Long insightId) {
        return insightId != null && hiddenIds().contains(insightId);
    }

    /** Admin-vy: vilka insikter ligger och väntar, och sedan när. */
    public List<Map<String, Object>> list() {
        try {
            ensureTable();
            return jdbc.queryForList("""
                SELECT u.insight_id, u.marked_at, i.expert_name, i.car_make, i.car_model, i.insight
                FROM insight_upcoming u JOIN expert_insight i ON i.id = u.insight_id
                ORDER BY u.insight_id DESC
                """);
        } catch (Exception e) {
            log.warn("Kunde inte lista kommande insikter: {}", e.getMessage());
            return List.of();
        }
    }

    private static String now() {
        return ZonedDateTime.now(ZoneId.of("Europe/Stockholm"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
