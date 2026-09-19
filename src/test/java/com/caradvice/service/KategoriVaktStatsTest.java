package com.caradvice.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bufferten som gör kategorivaktens utslag läsbara utan Render-dashboarden.
 *
 * <p>Provet 2026-09-19 kunde bara bevisa vaktens EFFEKT (raden fick null) genom att läsa tillbaka
 * insikterna — utslaget självt, med modellnamnet och motiveringen, gick inte att se någonstans.
 */
class KategoriVaktStatsTest {

    private final KategoriVaktStats stats = new KategoriVaktStats();

    @Test
    void utslagetBarModellnamnetOchMotiveringen() {
        stats.registrera("web-insights", "smaabil", "Tesla Model 3",
                "tesla model 3 är ingen småbil", "Saknar typgodkännande för dragkrok.");

        Map<String, Object> r = stats.rapport();
        assertThat(r.get("totalt")).isEqualTo(1L);
        assertThat(r.get("iBufferten")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rader = (List<Map<String, Object>>) r.get("senaste");
        assertThat(rader).hasSize(1);
        assertThat(rader.get(0).get("bil")).isEqualTo("Tesla Model 3");
        assertThat(rader.get(0).get("kategori")).isEqualTo("smaabil");
        assertThat(rader.get(0).get("motsagelse")).isEqualTo("tesla model 3 är ingen småbil");
        assertThat(rader.get(0).get("kalla")).isEqualTo("web-insights");
        assertThat(rader.get(0)).containsKey("sekunderSedan");
    }

    @Test
    void perBilVisarOmUtslagenSamlasHosEnModell() {
        stats.registrera("web-insights", "smaabil", "Tesla Model 3", "…", "A");
        stats.registrera("CSV-import [Vi Bilägare]", "smaabil", "Tesla Model 3", "…", "B");
        stats.registrera("web-insights", "suv", "Kia Niro", "…", "C");

        Map<String, Object> r = stats.rapport();
        assertThat(r.get("perBil")).isEqualTo(Map.of("Tesla Model 3", 2L, "Kia Niro", 1L));
        assertThat(r.get("perKalla")).isEqualTo(Map.of("web-insights", 2L, "CSV-import [Vi Bilägare]", 1L));
    }

    /**
     * Ett tal som inte kan röra sig kan inte larma — samma fälla som lät cargo-täckningen stå på
     * 602/602/0 medan parsern var död. Därför kapas bufferten men aldrig räknaren.
     */
    @Test
    void raknarenKapasAldrigMenBuffertenGorDet() {
        for (int i = 0; i < KategoriVaktStats.SENASTE_MAX + 12; i++)
            stats.registrera("web-insights", "suv", "Bil " + i, "…", "text");

        Map<String, Object> r = stats.rapport();
        assertThat(r.get("iBufferten")).isEqualTo(KategoriVaktStats.SENASTE_MAX);
        assertThat(r.get("totalt")).isEqualTo((long) KategoriVaktStats.SENASTE_MAX + 12);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rader = (List<Map<String, Object>>) r.get("senaste");
        // Nyast sist, äldst utkastad först
        assertThat(rader.get(rader.size() - 1).get("bil")).isEqualTo("Bil " + (KategoriVaktStats.SENASTE_MAX + 11));
        assertThat(rader.get(0).get("bil")).isEqualTo("Bil 12");
    }

    @Test
    void langInsiktKapasMenGarAttKannaIgen() {
        String lang = "x".repeat(KategoriVaktStats.INSIKT_MAX_TECKEN + 50);
        stats.registrera("web-insights", "smaabil", "Volvo XC90", "…", lang);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rader = (List<Map<String, Object>>) stats.rapport().get("senaste");
        String sparad = (String) rader.get(0).get("insikt");
        assertThat(sparad).hasSizeLessThan(lang.length()).startsWith("xxx").endsWith("[kapad]");
    }

    /**
     * Tom rapport betyder "inget sedan omstarten", aldrig "vakten fäller aldrig" — notisen är
     * det enda som skiljer de två för den som läser.
     */
    @Test
    void tomRapportBarSinEgenBrasklapp() {
        Map<String, Object> r = stats.rapport();
        assertThat(r.get("totalt")).isEqualTo(0L);
        assertThat((String) r.get("notis")).contains("nollstalls vid omstart".replace("nollstalls", "nollställs"));
        assertThat((String) r.get("notis")).contains("uptimeSeconds");
        assertThat((List<?>) r.get("senaste")).isEmpty();
    }

    @Test
    void nollstallningTommerAllt() {
        stats.registrera("web-insights", "suv", "Kia Niro", "…", "text");
        stats.nollstall();

        Map<String, Object> r = stats.rapport();
        assertThat(r.get("totalt")).isEqualTo(0L);
        assertThat((Map<?, ?>) r.get("perBil")).isEmpty();
    }
}
