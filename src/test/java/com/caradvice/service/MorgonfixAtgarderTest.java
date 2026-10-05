package com.caradvice.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** @author Robert Andersson Kopler */
class MorgonfixAtgarderTest {

    private JdbcTemplate jdbc;
    private ExpertInsightService insikter;
    private UpcomingInsightService kon;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        insikter = mock(ExpertInsightService.class);
        kon = mock(UpcomingInsightService.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    }

    private MorgonfixAtgarder med(String fil) {
        return new MorgonfixAtgarder(jdbc, insikter, kon,
                () -> fil == null ? null : new ByteArrayInputStream(fil.getBytes(StandardCharsets.UTF_8)));
    }

    private static String rad(String typ, long id) {
        return "{\"datum\":\"2026-09-27\",\"typ\":\"" + typ + "\",\"id\":" + id + ",\"skal\":\"prov\"}";
    }

    private static String fil(String... rader) {
        return "{\"atgarder\":[" + String.join(",", rader) + "]}";
    }

    @Test
    void slappUtforsOchNoteras() {
        when(kon.release(1657L)).thenReturn(true);

        var u = med(fil(rad("slapp", 1657))).kor();

        assertThat(u.rader()).containsExactly("slapp 1657: släppt");
        verify(jdbc).update(anyString(), eq("2026-09-27|slapp|1657"), anyString(), anyString());
    }

    @Test
    void parkeraKontrollerarAttRadenFinns() {
        when(insikter.exists(5L)).thenReturn(false);
        when(insikter.exists(6L)).thenReturn(true);
        when(kon.isUpcoming(6L)).thenReturn(true);

        var u = med(fil(rad("parkera", 5), rad("parkera", 6))).kor();

        assertThat(u.rader()).containsExactly("parkera 5: finns inte", "parkera 6: parkerad");
        verify(kon, never()).mark(5L);
        verify(kon).mark(6L);
    }

    @Test
    void redanUtfordAtgardGorsInteIgen() {
        // filen ligger kvar i jarren: en omstart får inte släppa en rad som nattjobbet parkerat om
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("2026-09-27|slapp|1657"))).thenReturn(1);

        var u = med(fil(rad("slapp", 1657))).kor();

        assertThat(u.nya()).isZero();
        verify(kon, never()).release(anyLong());
    }

    @Test
    void overTaketUtforsIngenting() {
        String[] rader = IntStream.rangeClosed(1, MorgonfixAtgarder.TAK + 1)
                .mapToObj(i -> rad("slapp", i)).toArray(String[]::new);

        var u = med(fil(rader)).kor();

        assertThat(u.avvisad()).isTrue();
        verify(kon, never()).release(anyLong());
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void taketGallerBaraNyaAtgarder() {
        // tretton rader varav två redan utförda = elva nya, under taket
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("2026-09-27|slapp|1"))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("2026-09-27|slapp|2"))).thenReturn(1);
        String[] rader = IntStream.rangeClosed(1, 13).mapToObj(i -> rad("slapp", i)).toArray(String[]::new);

        var u = med(fil(rader)).kor();

        assertThat(u.avvisad()).isFalse();
        verify(kon, times(11)).release(anyLong());
    }

    @Test
    void doljSkickarMedSkaletOchKontrollerarRaden() {
        when(insikter.exists(1482L)).thenReturn(true);
        when(kon.dolj(1482L, "prov")).thenReturn(true);
        when(insikter.exists(9L)).thenReturn(false);

        var u = med(fil(rad("dolj", 1482), rad("dolj", 9))).kor();

        assertThat(u.rader()).containsExactly("dolj 1482: dold", "dolj 9: finns inte");
        verify(kon, never()).dolj(eq(9L), anyString());
        // dölj är INTE radera: raden ska finnas kvar och gå att visa igen
        verify(insikter, never()).deleteById(anyLong());
    }

    @Test
    void raderingAvRadSomInteFinnsGorIngenting() {
        // hela raderingsvägen provas mot riktig databas i MorgonfixRaderingTest
        var u = med(fil(rad("radera", 1482))).kor();

        assertThat(u.rader()).containsExactly("radera 1482: finns inte");
        verify(insikter, never()).deleteById(anyLong());
    }

    @Test
    void radUtanSkalHoppasOver() {
        String utanSkal = "{\"datum\":\"2026-09-27\",\"typ\":\"slapp\",\"id\":7}";
        assertThat(med(fil(utanSkal)).las()).isEmpty();
    }

    @Test
    void saknadEllerTrasigFilGorIngenting() {
        assertThat(med(null).kor().rader()).isEmpty();
        assertThat(med("{inte json").kor().rader()).isEmpty();
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void verkligaFilenArGiltig() throws Exception {
        var atgarder = new MorgonfixAtgarder(jdbc, insikter, kon).las();
        int iFilen;
        try (var in = new ClassPathResource(MorgonfixAtgarder.RESURS).getInputStream()) {
            iFilen = new ObjectMapper().readTree(in).path("atgarder").size();
        }
        // den incheckade filen får aldrig ha en rad som tyst hoppas över i drift
        assertThat(atgarder).hasSize(iFilen);
        assertThat(atgarder.size()).isLessThanOrEqualTo(MorgonfixAtgarder.TAK);
        assertThat(atgarder.stream().map(MorgonfixAtgarder.Atgard::nyckel).collect(Collectors.toSet()))
                .hasSize(atgarder.size());
    }
}
