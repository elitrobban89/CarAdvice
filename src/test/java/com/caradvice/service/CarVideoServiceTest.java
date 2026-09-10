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
        VideoSentimentService sentiment = new VideoSentimentService(jdbc);
        ReflectionTestUtils.setField(sentiment, "youtubeKey", "");
        ReflectionTestUtils.setField(sentiment, "groqKey", "");
        ReflectionTestUtils.setField(sentiment, "model", "test-modell");
        CarVideoService s = new CarVideoService(jdbc, sentiment);
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

    /** Varje par är kanal + titel, i den ordning YouTube returnerade dem. */
    private static com.fasterxml.jackson.databind.JsonNode items(String... channelAndTitle) throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < channelAndTitle.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":{\"videoId\":\"v").append(i / 2).append("\"},\"snippet\":{\"channelTitle\":\"")
              .append(channelAndTitle[i]).append("\",\"title\":\"").append(channelAndTitle[i + 1]).append("\"}}");
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(sb.append(']').toString());
    }

    @Test
    void svenskKanalGarForeEngelskSomGarForeForstaTraffen() throws Exception {
        // Användarens ordning 2026-08-07: helst svenskt klipp, i andra hand engelskt
        // (Autotrader), annars YouTubes egen relevansordning.
        assertThat(pickedId(items("Random Uploads", "test", "Autotrader", "review", "Elbilsmagasinet", "recension")))
                .isEqualTo("v2");
        assertThat(pickedId(items("Random Uploads", "test", "Autotrader", "review"))).isEqualTo("v1");
        assertThat(pickedId(items("Random Uploads", "test", "Bilcentralen", "test"))).isEqualTo("v0");
        // kanalnamnet "Peter Esse " har ett efterföljande mellanslag i verkligheten
        assertThat(pickedId(items("Autotrader", "review", "Peter Esse ", "recension"))).isEqualTo("v1");
        // delsträngsmatchning: kanalen kan byta namn utan att listan slutar fungera
        assertThat(pickedId(items("carwow", "review", "Elbilsmagasinet Sverige", "test"))).isEqualTo("v1");
        assertThat(CarVideoService.pickBest(items())).isNull();
        assertThat(CarVideoService.pickBest(null)).isNull();
    }

    @Test
    void bara_svenska_och_engelska_klipp_slapps_igenom() throws Exception {
        // Skarpt utfall: en POLSK provkorning av Audi Q5 e-hybrid hamnade pa ett bilkort trots
        // relevanceLanguage=sv/en. YouTubes sprakparameter ar ett onskemal, inte ett filter.
        assertThat(pickedId(items(
                "Motoryzacja", "Audi Q5 e-hybrid - pierwsza jazda i recenzja",
                "Autotrader",  "Audi Q5 review"))).isEqualTo("v1");
        // Bara frammande traffar: hellre INGEN videorad an en recension tittaren inte forstar.
        assertThat(CarVideoService.pickBest(items(
                "Motoryzacja", "Audi Q5 recenzja",
                "AutoBild",    "Audi Q5 Fahrbericht"))).isNull();
        // Svenska tecken far inte falla med: a, a och o ar tillatna, och e finns i "ide".
        assertThat(CarVideoService.tillatetSprak(en("Teknikens Varld", "Volvo XC60 provkorning: en het ide"))).isTrue();
        // ... men tyska u, danska/norska ae och o gor det inte.
        assertThat(CarVideoService.tillatetSprak(en("Bilnorge", "Audi Q5 prøve: kjøreglede"))).isFalse();
        assertThat(CarVideoService.tillatetSprak(en("AutoBild", "Audi Q5 Überblick"))).isFalse();
    }

    @Test
    void skoda_ar_ett_markesnamn_och_inte_ett_frammande_sprak() throws Exception {
        // Cachegranskningen 2026-09-10 flaggade tva klipp pa tecknet S-caron - och bada bar det
        // i ordet \"Skoda\". Markesnamnet skrivs sa aven pa svenska, sa utan undantaget hade
        // filtret fallt varje svensk Skoda-recension.
        assertThat(CarVideoService.tillatetSprak(
                en("Teknikens Värld", "Škoda Enyaq provkörning"))).isTrue();
        assertThat(CarVideoService.tillatetSprak(
                en("carwow", "Škoda Octavia review"))).isTrue();
        // Men resten av det slovakiska klippet faller fortfarande: najpopulárnejšej
        // bar samma tecken utanfor markesnamnet.
        assertThat(CarVideoService.tillatetSprak(en("AUTOGRÁTIS",
                "Faceliftovaná Škoda Karoq v strednej výbave! Test jej najpopulárnejšej verzie!"))).isFalse();
    }

    @Test
    void sprakkoden_avgor_men_bara_nar_den_finns() {
        // Faltet defaultAudioLanguage ar frivilligt hos YouTube. Ett tomt falt betyder INTE fel
        // sprak, sa det far inte falla klippet - da hade halva cachen tomts pa aldre klipp.
        assertThat(CarVideoService.sprakOk(null)).isTrue();
        assertThat(CarVideoService.sprakOk("")).isTrue();
        assertThat(CarVideoService.sprakOk("  ")).isTrue();
        // Svenska och engelska i alla varianter YouTube skickar
        assertThat(CarVideoService.sprakOk("sv")).isTrue();
        assertThat(CarVideoService.sprakOk("sv-SE")).isTrue();
        assertThat(CarVideoService.sprakOk("en")).isTrue();
        assertThat(CarVideoService.sprakOk("en-US")).isTrue();
        assertThat(CarVideoService.sprakOk("en-GB")).isTrue();
        // Det som fick anropet att byggas: ett slovenskt klipp vars TITEL var ren ASCII
        assertThat(CarVideoService.sprakOk("sl")).isFalse();
        assertThat(CarVideoService.sprakOk("sk")).isFalse();
        assertThat(CarVideoService.sprakOk("pl")).isFalse();
        assertThat(CarVideoService.sprakOk("de")).isFalse();
        assertThat(CarVideoService.sprakOk("nb-NO")).isFalse();
    }

    /** Ett enda item, for de prov som mater spraket och inte rankningen. */
    private static com.fasterxml.jackson.databind.JsonNode en(String kanal, String titel) throws Exception {
        return items(kanal, titel).get(0);
    }

    @Test
    void htmlEntiteterAvkodasSaKortetInteVisarAmpKod() {
        // Raden som stod pa ett bilkort: frontendens caEsc escapade YouTubes redan escapade
        // titel en gang till, sa & blev &amp; och citattecknen blev &quot; i klartext.
        assertThat(CarVideoService.avkodaHtml(
                "Förnuft &amp; Känsla: Toyota RAV4 Plug-In Hybrid AWD-i | &quot;Den våta drömmen&quot;"))
                .isEqualTo("Förnuft & Känsla: Toyota RAV4 Plug-In Hybrid AWD-i | \"Den våta drömmen\"");
        // Ampersanden avkodas SIST: annars blir &amp;quot; ett riktigt citattecken,
        // alltsa en avkodning for mycket.
        assertThat(CarVideoService.avkodaHtml("a &amp;quot; b")).isEqualTo("a &quot; b");
        assertThat(CarVideoService.avkodaHtml("utan entiteter")).isEqualTo("utan entiteter");
        assertThat(CarVideoService.avkodaHtml(null)).isNull();
    }

    @Test
    void provkorningGarForeNyhetsnotisInomSammaKanalklass() throws Exception {
        // Skarpt utfall 2026-08-07: "Volvo EX60 levererad – nu gäller det!" valdes före
        // första provkörningen av samma bil, eftersom bara kanalen vägdes.
        assertThat(pickedId(items(
                "Peter Esse ", "Volvo EX60 levererad – nu gäller det!",
                "Teknikens Värld", "Första provkörningen: Nya Volvo EX60"))).isEqualTo("v1");
        // men kanalklassen väger tyngre än titeln — en okänd kanal med "recension" i
        // titeln slår inte en känd svensk kanal
        assertThat(pickedId(items(
                "Random Uploads", "Volvo EX60 recension",
                "Elbilsmagasinet", "Volvo EX60 levererad"))).isEqualTo("v1");
        // vid lika poäng vinner YouTubes egen ordning
        assertThat(pickedId(items(
                "Elbilsmagasinet", "Volvo EX60 recension",
                "Peter Esse ", "Volvo EX60 provkörning"))).isEqualTo("v0");
    }

    private static String pickedId(com.fasterxml.jackson.databind.JsonNode items) {
        return CarVideoService.pickBest(items).path("id").path("videoId").asText();
    }

    @Test
    void glomdVideoSlasUppPaNyttNastaGang() {
        CarVideoService s = service("");
        jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                "Volvo EX30", "gammal", "Gammal träff", "Random Uploads",
                LocalDateTime.now(ZoneOffset.UTC).toString());

        assertThat(s.forget("Volvo EX30 (2024)")).isEqualTo(1);   // årtalet ska strippas även här
        assertThat(s.forget("Finns Inte")).isZero();
        assertThat(s.forget("  ")).isZero();
        assertThat(s.findForCarTitle("Volvo EX30")).isEmpty();     // cachen är borta
    }

    @Test
    void helaCachenGarAttToma() {
        CarVideoService s = service("");
        jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                "Volvo EX30", "a", "", "", LocalDateTime.now(ZoneOffset.UTC).toString());
        jdbc.update("INSERT INTO car_video(car_name, video_id, title, channel, fetched_at) VALUES (?,?,?,?,?)",
                "Kia EV6", "b", "", "", LocalDateTime.now(ZoneOffset.UTC).toString());

        assertThat(s.forgetAll()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM car_video", Integer.class)).isZero();
    }

    @Test
    void dubbeltTabellskapandeArOfarligt() {
        CarVideoService s = service("");
        s.ensureTable();   // CREATE TABLE IF NOT EXISTS ska vara idempotent
        assertThat(s.findForCarTitle("Kia EV3")).isEmpty();
    }
}
