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

/** H2 in-memory, inga nätanrop — nycklarna lämnas tomma så tjänsten aldrig går ut. */
class VideoSentimentServiceTest {

    private JdbcTemplate jdbc;

    private VideoSentimentService service() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:vs_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        VideoSentimentService s = new VideoSentimentService(jdbc);
        ReflectionTestUtils.setField(s, "youtubeKey", "");
        ReflectionTestUtils.setField(s, "groqKey", "");
        ReflectionTestUtils.setField(s, "model", "test-modell");
        s.ensureTable();
        return s;
    }

    @Test
    void forTunntUnderlagGerIngenRutaAlls() {
        // Ett "Dåligt" byggt på tre kommentarer är sämre än ingen ruta
        assertThat(VideoSentimentService.toResult("daligt", "Flera klagar på mjukvaran.", 3)).isEmpty();
        assertThat(VideoSentimentService.toResult("bra", "Beröm för stolarna.", 11)).isEmpty();
        assertThat(VideoSentimentService.toResult("bra", "Beröm för stolarna.", 12)).isNotEmpty();
        assertThat(VideoSentimentService.toResult("", "", 50)).isEmpty();
        assertThat(VideoSentimentService.toResult(null, "", 50)).isEmpty();
    }

    @Test
    void domenOversattsTillSvenskEtikett() {
        assertThat(VideoSentimentService.toResult("bra", "x", 20).get("label")).isEqualTo("Bra");
        assertThat(VideoSentimentService.toResult("daligt", "x", 20).get("label")).isEqualTo("Dåligt");
        assertThat(VideoSentimentService.toResult("blandat", "x", 20).get("label")).isEqualTo("Blandat");
        Map<String, Object> r = VideoSentimentService.toResult("bra", "Beröm för räckvidden.", 34);
        assertThat(r.get("commentCount")).isEqualTo(34);
        assertThat(r.get("summary")).isEqualTo("Beröm för räckvidden.");
    }

    @Test
    void cachadDomServerasUtanNyaAnrop() {
        VideoSentimentService s = service();
        jdbc.update("INSERT INTO car_video_sentiment"
                        + "(car_name, video_id, verdict, summary, comment_count, fetched_at) VALUES (?,?,?,?,?,?)",
                "Volvo EX30", "abc123", "blandat", "Beröm för priset, kritik mot mjukvaran.", 27,
                LocalDateTime.now(ZoneOffset.UTC).toString());

        Map<String, Object> v = s.forVideo("Volvo EX30", "abc123");
        assertThat(v.get("label")).isEqualTo("Blandat");
        assertThat(v.get("commentCount")).isEqualTo(27);
    }

    @Test
    void nyValdVideoOgiltigforklararGammalDom() {
        // Byter recensionen video ska betyget räknas om — det gäller ju en annan kommentarstråd
        VideoSentimentService s = service();
        jdbc.update("INSERT INTO car_video_sentiment"
                        + "(car_name, video_id, verdict, summary, comment_count, fetched_at) VALUES (?,?,?,?,?,?)",
                "Volvo EX30", "gammal", "bra", "Idel beröm.", 40,
                LocalDateTime.now(ZoneOffset.UTC).toString());

        // utan nycklar kan den inte räkna om, men den får INTE servera den gamla domen
        assertThat(s.forVideo("Volvo EX30", "nyvideo")).isEmpty();
    }

    @Test
    void tomInputGerTomtSvar() {
        VideoSentimentService s = service();
        assertThat(s.forVideo(null, "abc")).isEmpty();
        assertThat(s.forVideo("Volvo EX30", "")).isEmpty();
        assertThat(s.forVideo("  ", "abc")).isEmpty();
    }

    @Test
    void jsonPlockasUrOmgivandeText() {
        // Reasoning-modellen ramar gärna in svaret i prosa
        VideoSentimentService s = service();
        assertThat(s.parseJson("Här kommer domen: {\"verdict\":\"bra\",\"relevanta\":20} hoppas det hjälper")
                .path("verdict").asText()).isEqualTo("bra");
        assertThat(s.parseJson("ingen json alls")).isNull();
        assertThat(s.parseJson("")).isNull();
    }
}
