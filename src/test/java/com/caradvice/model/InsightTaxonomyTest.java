package com.caradvice.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kategorivakten: bilens egen modell får motsäga kategorin AI:n satte.
 *
 * <p>Bakgrunden är natten mot 2026-09-19, då Saab 9-3 och Cadillac BLS sparades som
 * {@code smaabil} trots att promptregeln uttryckligen förbjudit mellanklassbilar sedan
 * 2026-08-10. Genomgången samma morgon visade att felet fanns åt BÅDA håll i tabellen:
 * nio för stora bilar som {@code smaabil} och tio låga bilar som {@code suv}.
 */
class InsightTaxonomyTest {

    @Test
    void storBilFarInteVaraSmaabil() {
        // De nio raderna som faktiskt låg i drift 2026-09-19
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Saab", "9-3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Cadillac", "BLS")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Tesla", "Model 3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Polestar", "2")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID.7")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Dacia", "Jogger")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Dacia", "Duster Extreme")).isNull();
    }

    @Test
    void lagBilFarInteVaraSuv() {
        // Samma tio rader åt andra hållet — vakten som bara ser ett håll är halv
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Kia", "Niro")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Hyundai", "Kona Electric")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Hyundai", "Kona N")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Polestar", "2")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volkswagen", "Golf Alltrack")).isNull();
    }

    /**
     * ID.3 är listans enda kompaktbil, tillagd 2026-09-19 em på användarens beslut: den ströks ur
     * småbilslistan i rekommendationsprompten för att den är Golf-klass med fem säten, men regeln
     * fanns bara på läsvägen — sex ID.3 GTI-rader och två vanliga ID.3 låg som smaabil i drift.
     */
    @Test
    void id3ArAldrigSmaabil() {
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID.3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID.3 GTI")).isNull();
        // AI:n skriver bagge formerna
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID3")).isNull();
        // Syskonen far inte falla med: ID.4 och ID. Polo ar inte samma bil
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Volkswagen", "ID. Polo")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volkswagen", "ID.4")).isEqualTo("suv");
        // suv-hallet var redan tackt av LAGA_MODELLER och ska fortsatta falla
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volkswagen", "ID.3")).isNull();
    }

    @Test
    void riktigaSmaabilarOchSuvarSlapperIgenom() {
        // Fäller på positivt bevis: en modell utanför listorna rörs aldrig
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Yaris")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Aygo X")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Dacia", "Sandero")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Volvo", "XC60")).isEqualTo("suv");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Skoda", "Kodiaq")).isEqualTo("suv");
    }

    @Test
    void yarisCrossFallerMenYarisStarKvar() {
        // Yaris Cross är en SUV, Yaris är bilen kategorin handlar om — delsträngen får inte
        // ta med sig syskonet i fallet
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Yaris Cross")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", "Yaris Hybrid")).isEqualTo("smaabil");
    }

    @Test
    void vaktenRorInteAndraKategorier() {
        // Bara smaabil och suv har motbevis — en Model 3 som familjebil eller elbil är sann
        assertThat(InsightTaxonomy.canonicalCategory("familjebil", "Tesla", "Model 3")).isEqualTo("familjebil");
        assertThat(InsightTaxonomy.canonicalCategory("elbil", "Tesla", "Model 3")).isEqualTo("elbil");
        assertThat(InsightTaxonomy.canonicalCategory("suv", "Tesla", "Model Y")).isEqualTo("suv");
    }

    @Test
    void aliasKanoniseradsForeMotbeviset() {
        // "småbil" skrivs om till "smaabil" först — annars hade aliaset gått fritt förbi vakten
        assertThat(InsightTaxonomy.canonicalCategory("småbil", "Saab", "9-3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("ekonomibil", "Tesla", "Model 3")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("SMAABIL ", "Dacia", "Duster")).isNull();
    }

    @Test
    void utanModellRorsIngenting() {
        // Märkesbreda rader (CSV-kurerade) har ingen modell att motsäga kategorin med
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", "Toyota", null)).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.canonicalCategory("smaabil", null, "")).isEqualTo("smaabil");
        assertThat(InsightTaxonomy.kategoriMotsagelse("suv", "Kia", null)).isNull();
    }

    @Test
    void motsagelsenBerattarVadSomFalldes() {
        // Texten går till loggen — utan den syns aldrig VARFÖR en kategori försvann
        assertThat(InsightTaxonomy.kategoriMotsagelse("smaabil", "Tesla", "Model 3"))
                .isEqualTo("tesla model 3 är ingen småbil");
        assertThat(InsightTaxonomy.kategoriMotsagelse("suv", "Kia", "Niro"))
                .isEqualTo("kia niro är ingen SUV");
        assertThat(InsightTaxonomy.kategoriMotsagelse("smaabil", "Toyota", "Yaris")).isNull();
    }

    @Test
    void okandKategoriArFortfarandeNull() {
        // Whitelisten gäller som förut — vakten läggs till, den ersätter ingenting
        assertThat(InsightTaxonomy.canonicalCategory("sportbil", "Porsche", "911")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory("", "Toyota", "Yaris")).isNull();
        assertThat(InsightTaxonomy.canonicalCategory(null, "Toyota", "Yaris")).isNull();
    }
}
