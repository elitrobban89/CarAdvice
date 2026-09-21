package com.caradvice.service;

import com.caradvice.repository.CargoSpecRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Missminnet i {@code cargo_spec_miss} — bagagelistans skydd mot att stanna mitt i alfabetet.
 *
 * <p><b>Varför det här provet går mot en RIKTIG databas och inte mot mockar.</b> Tabellen skapas
 * av tjänstens egen konstruktor, och varje skrivning ligger i en catch som bara loggar. Går
 * {@code CREATE TABLE} fel syns det alltså inte som ett haveri utan som tystnad: inga missar
 * skrivs, arbetslistan filtrerar ingenting, och jobbet fryser precis som före fixen — fast med
 * grönt bygge. Samma fail-soft-fälla som en gång parkerade 139 modeller i 30 dagar. Övriga
 * cargo-prov kör med {@code jdbc = null} och kan därför inte se den.
 *
 * <p>H2 duger som stand-in för Postgres här: DDL:en och de fyra frågorna är ren standard-SQL
 * utan dialektberoende.
 *
 * @author Robert Andersson Kopler
 */
class CargoSpecServiceMissTest {

    private static final AtomicInteger RAKNARE = new AtomicInteger();

    private final CargoSpecRepository repo = mock(CargoSpecRepository.class);
    private final JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.h2.Driver(), "jdbc:h2:mem:cargomiss" + RAKNARE.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", ""));

    /** Konstruktorn skapar sidotabellerna — precis som i uppstarten. */
    private final CargoSpecService service = new CargoSpecService(repo, jdbc);

    @Test
    void ettNejSkrivsLasesOchRaknas() {
        service.noteraMiss("AC Cobra", CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.harFarskMiss("AC Cobra")).isTrue();
        assertThat(service.antalMissar()).isEqualTo(1);
        assertThat(service.missarPerOrsak()).containsEntry(CargoSpecService.ORSAK_EJ_HITTAD, 1L);
        // Listan ska bära bilens riktiga namn, inte nyckelns normaliserade form: den läses av
        // människor som ska kunna se om Volvo XC60 parkerats av misstag.
        assertThat(service.listaMissar()).singleElement()
                .extracting(rad -> rad.get("bil")).isEqualTo("AC Cobra");
    }

    @Test
    void enBilSomAldrigProvatsArIngetNej() {
        service.noteraMiss("AC Cobra", CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.harFarskMiss("Volvo XC60")).isFalse();
    }

    @Test
    void namnetsFormSpelarIngenRoll() {
        // Arbetslistan är cargo_spec UNION ice_consumption, och samma bil stavas olika i de två
        // ("Volvo V60" mot "Volvo v60"). Ett nej ska gälla bilen, inte strängen.
        service.noteraMiss("Volvo V60", CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.harFarskMiss("volvo  v60")).isTrue();
    }

    @Test
    void sammaBilTvaGangerGerEnRad() {
        // Annars hade antalMissar vuxit varje natt utan att en enda ny bil prövats — och det
        // talet är bagagelarmets andra halva.
        service.noteraMiss("Bentley S2", CargoSpecService.ORSAK_EJ_HITTAD);
        service.noteraMiss("Bentley S2", CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.antalMissar()).isEqualTo(1);
    }

    @Test
    void ettGammaltNejGlomsEfterFonstret() {
        // Fönstret finns för att en död parser annars hade fryst listan för alltid: dagen då
        // varenda bil missar får inte bli ett permanent nej.
        long forGammal = java.time.LocalDate.now().toEpochDay() - CargoSpecService.MISS_GILTIG_DAGAR;
        jdbc.update("INSERT INTO cargo_spec_miss(car_name, visningsnamn, forsokt_dag, orsak) VALUES (?, ?, ?, ?)",
                "chrysler royal", "Chrysler Royal", (int) forGammal, CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.harFarskMiss("Chrysler Royal")).isFalse();
        // Raden ligger kvar och räknas — den är en anteckning, inte en spärr.
        assertThat(service.antalMissar()).isEqualTo(1);
    }

    @Test
    void dagenInnanFonstretGarUtArNejetKvar() {
        long precisInom = java.time.LocalDate.now().toEpochDay() - (CargoSpecService.MISS_GILTIG_DAGAR - 1);
        jdbc.update("INSERT INTO cargo_spec_miss(car_name, visningsnamn, forsokt_dag, orsak) VALUES (?, ?, ?, ?)",
                "chrysler royal", "Chrysler Royal", (int) precisInom, CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.harFarskMiss("Chrysler Royal")).isTrue();
    }

    @Test
    void enTraffGlommerBilensNej() {
        service.noteraMiss("Citroen C1", CargoSpecService.ORSAK_EJ_HITTAD);

        service.rensaMiss("Citroen C1");

        assertThat(service.harFarskMiss("Citroen C1")).isFalse();
        assertThat(service.antalMissar()).isZero();
    }

    @Test
    void raderingsvagenTommerAlltPaEnGang() {
        // Byggd samtidigt som skrivvägen: rättas uppslaget är gamla nej inte längre svar, och
        // utan den här överlever de rättningen i 30 dagar.
        service.noteraMiss("AC Cobra", CargoSpecService.ORSAK_EJ_HITTAD);
        service.noteraMiss("Bentley S2", CargoSpecService.ORSAK_EJ_HITTAD);

        assertThat(service.rensaMissar()).isEqualTo(2);
        assertThat(service.antalMissar()).isZero();
        assertThat(service.harFarskMiss("AC Cobra")).isFalse();
    }
}
