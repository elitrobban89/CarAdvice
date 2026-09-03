package com.caradvice.service;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tester för findForCarTitle — den publika insiktslistan per bilkort
 * (konsumeras av GET /api/insights). Repo mockas.
 */
class ExpertInsightServiceCarLookupTest {

    private final ExpertInsightRepository repo = mock(ExpertInsightRepository.class);
    private final EvSpecService evSpecService = mock(EvSpecService.class);
    private final UpcomingInsightService upcomingService = mock(UpcomingInsightService.class);
    private final ExpertInsightService service = new ExpertInsightService(repo, evSpecService, upcomingService);

    private static ExpertInsight insight(String expert, String make, String model, String text, Integer rating) {
        return new ExpertInsight(expert, make, model, "el", "kombi", text, rating);
    }

    @Test
    void marketMasteFinnasITiteln() {
        when(repo.findAll()).thenReturn(List.of(
                insight("Teknikens Värld", "Tesla", "Model 3", "Toppbetyg i test.", 9)));

        assertThat(service.findForCarTitle("Volvo XC60 (2020)")).isEmpty();
        assertThat(service.findForCarTitle("Tesla Model 3 (2021)")).hasSize(1);
    }

    @Test
    void kommandeInsiktVisasIntePåBilkortet() {
        // Bilen är bekräftad för Sverige men går inte att köpa än — insikten finns kvar i DB,
        // men på ett bilkort skulle den läsas som en rekommendation
        ExpertInsight kommande = insight("Teknikens Värld", "Mercedes", "GLA", "Tre elvarianter.", null);
        org.springframework.test.util.ReflectionTestUtils.setField(kommande, "id", 12L);
        ExpertInsight saljs = insight("Vi Bilägare", "Mercedes", "GLA", "Bra andrahandsvärde.", null);
        org.springframework.test.util.ReflectionTestUtils.setField(saljs, "id", 13L);
        when(repo.findAll()).thenReturn(List.of(kommande, saljs));
        when(upcomingService.hiddenIds()).thenReturn(java.util.Set.of(12L));

        List<Map<String, Object>> result = service.findForCarTitle("Mercedes GLA (2024)");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("insight")).isEqualTo("Bra andrahandsvärde.");
    }

    @Test
    void insiktOmAnnanModellAvSammaMarkeUtesluts() {
        when(repo.findAll()).thenReturn(List.of(
                insight("Vi Bilägare", "Tesla", "Model S", "Dyr i inköp.", null),
                insight("Teknikens Värld", "Tesla", "Model 3", "Bäst i klassen.", 9)));

        List<Map<String, Object>> result = service.findForCarTitle("Tesla Model 3 (2021)");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("insight")).isEqualTo("Bäst i klassen.");
    }

    @Test
    void modellspecifikaPrioriterasForeMarkesgenerellaOchMax3() {
        when(repo.findAll()).thenReturn(List.of(
                insight("M Sverige", "Tesla", null, "Generellt om märket 1.", null),
                insight("M Sverige", "Tesla", null, "Generellt om märket 2.", null),
                insight("Teknikens Värld", "Tesla", "Model 3", "Modellspecifik 1.", 8),
                insight("Vi Bilägare", "Tesla", "Model 3", "Modellspecifik 2.", 7),
                insight("car.info", "Tesla", "Model 3", "Modellspecifik 3.", 9)));

        List<Map<String, Object>> result = service.findForCarTitle("Tesla Model 3 (2021)");
        assertThat(result).hasSize(3);
        // Alla tre platser tas av modellspecifika — de generella trängs ut
        assertThat(result).allSatisfy(m ->
                assertThat((String) m.get("insight")).startsWith("Modellspecifik"));
    }

    @Test
    void ratingMedNarDenFinnsOchExpertnamnFallerTillbaka() {
        when(repo.findAll()).thenReturn(List.of(
                insight(null, "Kia", "EV6", "Vann årets elbilstest.", 10)));

        List<Map<String, Object>> result = service.findForCarTitle("Kia EV6 (2022)");
        assertThat(result.get(0).get("expert")).isEqualTo("Bilexpert");
        assertThat(result.get(0).get("rating")).isEqualTo(10);
    }

    @Test
    void dubblettradIDbVisasBaraEnGang() {
        when(repo.findAll()).thenReturn(List.of(
                insight("Bilprovningen", "Volvo", "XC60", "Avgassystemet har anmärkningar på 1,6%.", null),
                insight("Bilprovningen", "Volvo", "XC60", "Avgassystemet har anmärkningar på 1,6%.", null)));

        assertThat(service.findForCarTitle("Volvo XC60 (2019)")).hasSize(1);
    }

    @Test
    void tomEllerNullTitelGerTomLista() {
        assertThat(service.findForCarTitle(null)).isEmpty();
        assertThat(service.findForCarTitle("  ")).isEmpty();
    }

    @Test
    void hartMellanslagITitelnHindrarInteInsiktsmatchningen() {
        // Tredje stallet med samma bugg: modellen matchas med contains("ioniq 5") mot AI:ns
        // titel, som ibland innehaller smalt hart mellanslag (U+202F) i stallet for vanligt.
        String nnbsp = String.valueOf((char) 0x202F);
        when(repo.findAll()).thenReturn(List.of(
                insight("Teknikens Värld", "Hyundai", "IONIQ 5", "Snabbladdar i toppklass.", 9)));

        assertThat(service.findForCarTitle("Hyundai IONIQ" + nnbsp + "5 (2024)")).hasSize(1);
    }

    @Test
    void tremaITitelnHindrarInteMarkesmatchningen() {
        // Vara EGNA bilnamn stavar samma marke pa tva satt: bildatabasen bar bade
        // "Citroen C5 Aircross" och "Citroën C5 Aircross Long Range". Marketskontrollen ar
        // titel.contains(carMake), sa 7 rader med carMake "Citroën" var osynliga pa de
        // bilnamn som saknar trema (matt i drift 2026-08-27).
        when(repo.findAll()).thenReturn(List.of(
                insight("CarUp", "Citroën", "C5 Aircross", "Mjuk fjadring.", null)));

        assertThat(service.findForCarTitle("Citroen C5 Aircross (2025)")).hasSize(1);
        assertThat(service.findForCarTitle("Citroën C5 Aircross Long Range")).hasSize(1);
    }

    @Test
    void tremaIModellnamnetHindrarInteModellmatchningen() {
        // id 144 hette "Mégane E-Tech" och nadde inget enda kort — titlarna skriver "Megane".
        when(repo.findAll()).thenReturn(List.of(
                insight("CarUp", "Renault", "Mégane E-Tech", "Racker 47 mil.", null)));

        assertThat(service.findForCarTitle("Renault Megane E-Tech (2024)")).hasSize(1);
    }

    @Test
    void avkodningenSlarInteUtDrivlinevaktensSvenskaOrd() {
        // Avkodningen far ALDRIG rora drivlinemarkorerna: ICE_MARKER bar "tandstift" och
        // "forgasare" med svenska tecken, och en generell avkodning hade gjort dem omojliga
        // att traffa — varpa en bensintext skulle slinka in pa ett rent elbilskort.
        assertThat(ExpertInsightService.drivetrainOf("Bilen behover nya tändstift.")).isEqualTo("ice");
        assertThat(ExpertInsightService.drivetrainOf("En gammal förgasarmotor.")).isEqualTo("ice");
    }

    @Test
    void elbilsradSomNAMNERBensinFallerInteBortFranSittEgetKort() {
        // Uppmatt 2026-09-03: id 233 ("Toyota bZ4X ar billigare i drift ... jamfort med
        // motsvarande bensinbil") och id 1259 (Ford Puma Gen-E) klassades som
        // forbranningsinnehall och foll bort fran sina EGNA elbilskort. Ordet star i en
        // jamforelse; raden ar ett faktum OM elbilen. Radens egen drivmedelsruta bryter.
        when(evSpecService.isKnownEv("Toyota bZ4X")).thenReturn(true);
        when(repo.findAll()).thenReturn(List.of(new ExpertInsight(
                "Vi Bilägare", "Toyota", "bZ4X", "elbil", "suv",
                "Toyota bZ4X är billigare i drift på en semesterresa jämfört med motsvarande bensinbil.", null)));

        assertThat(service.findForCarTitle("Toyota bZ4X")).hasSize(1);
    }

    @Test
    void enBensinradFallerFortfarandePaElbilskortet() {
        // Motprovet, och skalet att regeln ar skriven som EN cell och inte som ett allmant
        // foretrade for fuel_type: sager raden sjalv "bensin" ska den fortsatta falla.
        when(evSpecService.isKnownEv("Toyota bZ4X")).thenReturn(true);
        when(repo.findAll()).thenReturn(List.of(new ExpertInsight(
                "CarUp", "Toyota", "bZ4X", "bensin", "suv",
                "Motorn behöver nya tändstift efter 6 000 mil.", null)));

        assertThat(service.findForCarTitle("Toyota bZ4X")).isEmpty();
    }

    @Test
    void drivlineordIModellnamnetVinnerOverDrivmedelsrutan() {
        // Ordningen ar modellnamn -> text -> drivmedelsruta. Sager modellnamnet "PHEV" ar
        // raden en laddhybrid aven om drivmedelsrutan rakar saga elbil.
        when(evSpecService.isKnownEv("Kia Niro")).thenReturn(true);
        when(repo.findAll()).thenReturn(List.of(new ExpertInsight(
                "CarUp", "Kia", "Niro PHEV", "elbil", "suv",
                "Laddhybriden går 5 mil på el.", null)));

        assertThat(service.findForCarTitle("Kia Niro")).isEmpty();
    }
}
