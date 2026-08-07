package com.caradvice.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tester mot H2 in-memory (ingen Spring-kontext) — samma skäl som FeedbackServiceTest:
 * tabellskapandet och SQL:en måste vara portabel, prod kör Postgres.
 *
 * <p>Inget test går ut på nätet. Utan API-nyckel är tjänsten passiv, vilket är exakt det
 * läge som ska verifieras: kvoten får aldrig kunna brännas av en cachemiss.
 */
class CarVideoServiceTest {

    private JdbcTemplate jdbc;

    private CarVideoService service(String apiKey) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:cv_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        CarVideoService s = new CarVideoService(jdbc);
        ReflectionTestUtils.setField(s, "apiKey", apiKey);
        s.ensureTable();
        return s;
    }

    @Test
    void arsmodellenStripsUrSokningen() {
        // "Volvo EX60 (2024) recension" ger sämre träffar än "Volvo EX60 recension"
        assertThat(CarVideoService.normalize("Volvo EX60 (2024)")).isEqualTo("Volvo EX60");
        assertThat(CarVideoService.normalize("Kia EV6  (2022)  ")).isEqualTo("Kia EV6");
        assertThat(CarVideoService.normalize("Volvo EX60")).isEqualTo("Volvo EX60");
        assertThat(CarVideoService.normalize(null)).isEmpty();
        assertThat(CarVideoService.normalize("  ")).isEmpty();
    }

    @Test
    void utanApiNyckelArTjanstenHeltPassiv() {
        // Ingen nyckel = ingen videorad och inget nätanrop; frontend ritar då ingen ruta
        CarVideoService s = service("");
        assertThat(s.findForCarTitle("Volvo EX60 (2024)")).isEmpty();
        // och inget skrevs till cachen, så en senare nyckel får göra uppslaget på riktigt
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM car_video", Integer.class)).isZero();
    }

    @Test
    void cachadTraffServerasUtanNyttUppslag() {
        CarVideoService s = service("");   // tom nyckel: träffen kan bara komma från cachen
        jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                "Volvo EX60", "abc123", "Volvo EX60 – test", "Teknikens Värld",
                LocalDateTime.now(ZoneOffset.UTC).toString());

        Map<String, Object> v = s.findForCarTitle("Volvo EX60 (2024)");
        assertThat(v.get("videoId")).isEqualTo("abc123");
        assertThat(v.get("channel")).isEqualTo("Teknikens Värld");
        assertThat(v.get("url")).isEqualTo("https://www.youtube.com/watch?v=abc123");
        assertThat(v.get("thumbnail")).isEqualTo("https://i.ytimg.com/vi/abc123/hqdefault.jpg");
    }

    @Test
    void farskMissServerasUrCachenIStalletForNyttUppslag() {
        // En bil utan recension kostade annars 100 kvotenheter vid VARJE visning
        CarVideoService s = service("");
        jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                "Lada Niva", "", "", "", LocalDateTime.now(ZoneOffset.UTC).toString());

        assertThat(s.findForCarTitle("Lada Niva")).isEmpty();
        // raden ligger kvar orörd — ingen omskrivning, inget uppslag
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM car_video", Integer.class)).isEqualTo(1);
    }

    @Test
    void gammalMissProvasOmEftersomNylanseradBilFarRecensionerSenare() {
        CarVideoService s = service("");
        jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                "Volvo EX60", "", "", "",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(45).toString());

        // cachen räknas som utgången → tjänsten vill slå upp på nytt, men nyckeln saknas
        // så resultatet blir tomt UTAN att den gamla missen skrivs om
        assertThat(s.findForCarTitle("Volvo EX60")).isEmpty();
        String fetched = jdbc.queryForObject(
                "SELECT fetched_at FROM car_video WHERE car_name = ?", String.class, "Volvo EX60");
        assertThat(LocalDateTime.parse(fetched))
                .isBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(40));
    }

    @Test
    void dubbeltTabellskapandeArOfarligt() {
        CarVideoService s = service("");
        s.ensureTable();   // CREATE TABLE IF NOT EXISTS ska vara idempotent
        assertThat(s.findForCarTitle("Kia EV3")).isEmpty();
    }
}
