package com.caradvice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tester mot en riktig H2 in-memory-databas (ingen Spring-kontext) — verifierar
 * att tabellskapandet och SQL:en är portabel (samma SQL körs mot Postgres i prod).
 */
class FeedbackServiceTest {

    private FeedbackService service() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:fb_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        FeedbackService s = new FeedbackService(new JdbcTemplate(ds));
        s.ensureTable();
        return s;
    }

    @Test
    void voteValueMappning() {
        assertThat(FeedbackService.voteValue("up")).isEqualTo(1);
        assertThat(FeedbackService.voteValue("UP")).isEqualTo(1);
        assertThat(FeedbackService.voteValue("down")).isEqualTo(-1);
        assertThat(FeedbackService.voteValue("sideways")).isZero();
        assertThat(FeedbackService.voteValue(null)).isZero();
    }

    @Test
    void rosterSparasOchSummeras() {
        FeedbackService s = service();
        assertThat(s.save("Volvo EX30 (2024)", "up")).isTrue();
        assertThat(s.save("Volvo EX30 (2024)", "up")).isTrue();
        assertThat(s.save("Volvo EX30 (2024)", "down")).isTrue();
        assertThat(s.save("Dacia Sandero (2021)", "down")).isTrue();

        List<Map<String, Object>> summary = s.summary();
        assertThat(summary).hasSize(2);
        Map<String, Object> ex30 = summary.get(0); // flest röster först
        assertThat(ex30.get("car_title")).isEqualTo("Volvo EX30 (2024)");
        assertThat(((Number) ex30.get("upvotes")).intValue()).isEqualTo(2);
        assertThat(((Number) ex30.get("downvotes")).intValue()).isEqualTo(1);
        assertThat(((Number) ex30.get("total")).intValue()).isEqualTo(3);
    }

    @Test
    void ogiltigInputAvvisasUtanInsert() {
        FeedbackService s = service();
        assertThat(s.save("Volvo EX30", "sideways")).isFalse();  // ogiltig röst
        assertThat(s.save(null, "up")).isFalse();                // titel saknas
        assertThat(s.save("   ", "up")).isFalse();               // blank titel
        assertThat(s.save("x".repeat(201), "up")).isFalse();     // för lång titel
        assertThat(s.summary()).isEmpty();
    }

    @Test
    void raderingPerBiltitelTraffarBaraExaktMatchning() {
        FeedbackService s = service();
        s.save("TEST-VERIFIERING (raderas)", "up");
        s.save("TEST-VERIFIERING (raderas)", "down");
        s.save("Volvo EX30 (2024)", "up");

        assertThat(s.deleteByCarTitle("TEST-VERIFIERING (raderas)")).isEqualTo(2);
        assertThat(s.deleteByCarTitle("Finns Inte (2099)")).isZero();
        assertThat(s.deleteByCarTitle(null)).isZero();
        assertThat(s.deleteByCarTitle("  ")).isZero();

        List<Map<String, Object>> summary = s.summary();
        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).get("car_title")).isEqualTo("Volvo EX30 (2024)");
    }

    @Test
    void dubblettTabellskapandeArOfarligt() {
        FeedbackService s = service();
        s.ensureTable(); // CREATE TABLE IF NOT EXISTS ska vara idempotent
        assertThat(s.save("Kia EV3 (2024)", "up")).isTrue();
        assertThat(s.summary()).hasSize(1);
    }
}
