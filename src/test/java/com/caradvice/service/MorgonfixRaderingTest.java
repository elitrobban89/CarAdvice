package com.caradvice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Automatisk radering (2026-09-27) mot en RIKTIG databas (H2): "max 20 rader åt gången så vi inte
 * tar bort all data". Proven låser de tre säkringarna — taket, att raden måste ha varit dold i en
 * vecka, och att den arkiveras innan den tas bort — och att arkivet går att återställa ur.
 *
 * @author Robert Andersson Kopler
 */
class MorgonfixRaderingTest {

    private JdbcTemplate jdbc;
    private UpcomingInsightService kon;
    private ExpertInsightService insikter;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:mf_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
            CREATE TABLE expert_insight (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, expert_name VARCHAR(255), car_make VARCHAR(255),
                car_model VARCHAR(255), fuel_type VARCHAR(255), category VARCHAR(255), insight TEXT, rating INTEGER)
            """);
        kon = new UpcomingInsightService(jdbc);
        // ExpertInsightService går via JPA - här räcker samma två frågor mot tabellen
        insikter = mock(ExpertInsightService.class);
        when(insikter.exists(anyLong())).thenAnswer(inv -> antal(inv.getArgument(0)) == 1);
        when(insikter.deleteById(anyLong())).thenAnswer(inv ->
                jdbc.update("DELETE FROM expert_insight WHERE id = ?", (Long) inv.getArgument(0)) == 1);
    }

    private int antal(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM expert_insight WHERE id = ?", Integer.class, id);
    }

    private long insikt(String text) {
        jdbc.update("INSERT INTO expert_insight(expert_name, car_make, car_model, category, insight) VALUES (?,?,?,?,?)",
                "CarUp", "Volvo", "850", "familjebil", text);
        return jdbc.queryForObject("SELECT MAX(id) FROM expert_insight", Long.class);
    }

    private void doldFor(long id, int dygnSedan) {
        kon.dolj(id, "RENOVERINGSOBJEKT");
        String tid = ZonedDateTime.now(ZoneId.of("Europe/Stockholm")).minusDays(dygnSedan)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        jdbc.update("UPDATE insight_hidden SET hidden_at = ? WHERE insight_id = ?", tid, id);
    }

    private MorgonfixAtgarder med(String... rader) {
        String fil = "{\"atgarder\":[" + String.join(",", rader) + "]}";
        return new MorgonfixAtgarder(jdbc, insikter, kon,
                () -> new ByteArrayInputStream(fil.getBytes(StandardCharsets.UTF_8)));
    }

    private static String radera(long id) {
        return "{\"datum\":\"2026-09-27\",\"typ\":\"radera\",\"id\":" + id + ",\"skal\":\"RENOVERINGSOBJEKT\"}";
    }

    @Test
    void enVeckaDoldRadArkiverasOchRaderas() {
        long id = insikt("Volvo 850 från 1995 säljs som renoveringsobjekt.");
        doldFor(id, 8);

        var u = med(radera(id)).kor();

        assertThat(u.rader()).containsExactly("radera " + id + ": raderad (arkiverad)");
        assertThat(antal(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT insight FROM insight_raderad WHERE insight_id = ?", String.class, id))
                .isEqualTo("Volvo 850 från 1995 säljs som renoveringsobjekt.");
        assertThat(kon.doldaIds()).doesNotContain(id);
    }

    @Test
    void enRadSomInteArDoldRaderasInte() {
        long id = insikt("En bra rad.");
        var u = med(radera(id)).kor();
        assertThat(u.rader().get(0)).contains("inte dold");
        assertThat(antal(id)).isEqualTo(1);
    }

    @Test
    void enNyssDoldRadRaderasInteForranEfterEnVecka() {
        // tiden mellan dölj och radera är användarens chans att ångra
        long id = insikt("Nyss dold.");
        doldFor(id, 3);
        var u = med(radera(id)).kor();
        assertThat(u.rader().get(0)).contains("dold i 3 dygn");
        assertThat(antal(id)).isEqualTo(1);
    }

    @Test
    void flerAnTjugoRaderingarAvvisarHelaFilen() {
        String[] rader = IntStream.rangeClosed(1, MorgonfixAtgarder.RADERA_TAK + 1).mapToObj(i -> {
            long id = insikt("rad " + i);
            doldFor(id, 10);
            return radera(id);
        }).toArray(String[]::new);

        var u = med(rader).kor();

        assertThat(u.avvisad()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expert_insight", Integer.class))
                .isEqualTo(MorgonfixAtgarder.RADERA_TAK + 1);
    }

    @Test
    void tjugoRaderingarGarIgenom() {
        String rader = IntStream.rangeClosed(1, MorgonfixAtgarder.RADERA_TAK).mapToObj(i -> {
            long id = insikt("rad " + i);
            doldFor(id, 10);
            return radera(id);
        }).collect(Collectors.joining(","));
        long kvar = insikt("ska stå kvar");

        var u = med(rader.split(",(?=\\{)")).kor();

        assertThat(u.avvisad()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expert_insight", Integer.class)).isEqualTo(1);
        assertThat(antal(kvar)).isEqualTo(1);
    }

    @Test
    void enArkiveradRadGarAttAterstalla() {
        long id = insikt("Ångra mig.");
        doldFor(id, 8);
        med(radera(id)).kor();

        var atgarder = new MorgonfixAtgarder(jdbc, insikter, kon);
        assertThat(atgarder.aterstall(id)).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM expert_insight WHERE insight = ?", Integer.class, "Ångra mig."))
                .isEqualTo(1);
        assertThat(atgarder.raderade()).isEmpty();
        assertThat(atgarder.aterstall(id)).isFalse(); // en gång räcker
    }
}
