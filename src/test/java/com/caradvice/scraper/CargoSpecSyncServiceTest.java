package com.caradvice.scraper;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Parsern körs mot sparade sidor från bilweb.se. Märkeslistan är hämtad 2026-09-01 — dagen då den
 * gamla källan {@code /sok/bilar} visade sig svara 404 och nattjobbet tyst slutade hämta märken.
 * Märkessidorna är hämtade 2026-09-17, morgonen efter att sajten lagt till filter- och
 * stadsavsnitten längst ned. Fixturerna är sidornas egen markup (länkblocken, utan annonslistorna).
 */
class CargoSpecSyncServiceTest {

    private Document fixtur(String namn) {
        try (InputStream in = getClass().getResourceAsStream("/bilweb/" + namn)) {
            if (in == null) throw new IllegalStateException("saknar fixtur " + namn);
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void alla_markenGerHelaMarkeslistan() {
        var makes = CargoSpecSyncService.parseMakes(fixtur("alla-marken.html"));

        assertThat(makes).hasSize(170);
        // Bilweb skriver "Mercedes", inte "Mercedes-Benz" — samma stavning som bilkorten redan
        // använder sedan namnrättelsen 2026-08-27, så namnen möts utan omskrivning.
        assertThat(makes).containsEntry("Audi", "audi")
                .containsEntry("Alfa Romeo", "alfa-romeo")
                .containsEntry("Mercedes", "mercedes");
    }

    @Test
    void markesnamnetTarInteMedAnnonsantalet() {
        // Namnet ligger i första spanen och antalet i nästa, så link.text() ger "Alfa Romeo 51".
        // Ett sådant namn hade blivit bilar som "Alfa Romeo 51 Giulia" i tabellen.
        var makes = CargoSpecSyncService.parseMakes(fixtur("alla-marken.html"));

        assertThat(makes.keySet()).noneMatch(namn -> namn.matches(".*\\d+$"));
    }

    @Test
    void markessidanGerModellerna() {
        var modeller = CargoSpecSyncService.parseModels(fixtur("sok-audi.html"), "audi");

        // 48 = raderna i "Alla Audi-modeller till salu". Toppchipen ovanför listan är en
        // delmängd av dem, så antalet är oförändrat sedan den gamla markupen.
        assertThat(modeller).hasSize(48);
        assertThat(modeller).contains("A3", "A4 Allroad", "Q4 e-tron", "RS e-tron GT", "SQ8 e-tron");
    }

    @Test
    void visaAnnonserLankenArIngenModell() {
        // Varje populär modell har TVÅ länkar med samma href: chipet med modellnamnet och
        // toplistans "Visa 143 annonser →". Utan filtret blev den andra en bil i tabellen.
        var modeller = CargoSpecSyncService.parseModels(fixtur("sok-volvo.html"), "volvo");

        assertThat(modeller).noneMatch(m -> m.toLowerCase().contains("annons"));
    }

    /**
     * Natten till 2026-09-17 la Bilweb till två SEO-avsnitt längst ned på varje märkessida —
     * "&lt;märke&gt; efter årsmodell, bränsle, motortyp, kaross och växellåda" och "Här kan du köpa
     * &lt;märke&gt;". Filterlänkarna där bär exakt samma adressform som modellänkarna
     * ({@code /sok/volvo/2023} bredvid {@code /sok/volvo/v60}), så parsern, som tog hela sidan,
     * lade in <b>3551</b> nya "bilar" den natten: {@code Volvo Ystad}, {@code XPENG 2024},
     * {@code Saab Bensin/Etanol}, {@code Zeekr Kombi}.
     *
     * <p>Volvo är fixturen just för att märket har <i>siffermodeller</i> (144, 242, 780) bredvid
     * årsfiltren (1996, 2023): ett filter som bara kastade sifferlänkar hade tagit modellerna med.
     */
    @Test
    void filterlankarnaLangstNedArIngaModeller() {
        var modeller = CargoSpecSyncService.parseModels(fixtur("sok-volvo.html"), "volvo");

        assertThat(modeller).hasSize(38);
        assertThat(modeller).contains("144", "242", "XC60", "EX90", "V60", "Amazon");
        assertThat(modeller).doesNotContain(
                "2023", "2026",                     // årsmodell
                "Bensin", "Diesel", "Laddhybrid",   // bränsle
                "SUV", "Kombi", "Sedan",            // kaross
                "Manuell", "Automat",               // växellåda
                "Ystad", "Haninge", "Växjö");       // städer
    }

    /**
     * Saknad modellista är ett markupfel, inte ett märke utan modeller — och de två får aldrig
     * bli samma svar. Hade den saknade rutan gett tom lista kunde jobbet rapportera OK medan
     * det slutat hämta, precis som märkeslistan gjorde 2026-09-01.
     */
    @Test
    void utanModellistaGerNullInteTomLista() {
        Document utan = Jsoup.parse("<html><body><a href='/sok/volvo/v60'>V60</a></body></html>");

        assertThat(CargoSpecSyncService.parseModels(utan, "volvo")).isNull();
    }

    // ---- syncCarNames: spärrarna ------------------------------------------------------------

    /** Servar fixturer i stället för nätet, utan hövlighetspaus. */
    private static class FejkadSync extends CargoSpecSyncService {
        private final Map<String, String> makes;
        private final Map<String, List<String>> modeller;

        FejkadSync(CargoSpecRepository repo, Map<String, String> makes,
                   Map<String, List<String>> modeller) {
            super(repo);
            this.makes = makes;
            this.modeller = modeller;
        }

        @Override void paus() { }
        @Override Map<String, String> fetchMakes() { return makes; }
        @Override List<String> fetchModels(String slug) { return modeller.get(slug); }
    }

    private static Map<String, String> marken(int antal) {
        Map<String, String> ut = new LinkedHashMap<>();
        for (int i = 0; i < antal; i++) ut.put("Marke" + i, "marke" + i);
        return ut;
    }

    @Test
    void taketFallerKorningenOchSkriverIngenting() {
        // 20 märken × 30 modeller = 600 nya namn, alltså över taket. Det är den natt som
        // faktiskt inträffade 2026-09-17 i miniatyr: 3551 nya "bilar" på en körning.
        CargoSpecRepository repo = mock(CargoSpecRepository.class);
        when(repo.findAllCarNames()).thenReturn(List.of());
        Map<String, List<String>> modeller = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            List<String> m = new ArrayList<>();
            for (int j = 0; j < 30; j++) m.add("modell" + i + "_" + j);
            modeller.put("marke" + i, m);
        }
        var service = new FejkadSync(repo, marken(20), modeller);

        assertThatThrownBy(service::syncCarNames)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taket är " + CargoSpecSyncService.MAX_NYA_PER_KORNING);

        // Poängen med spärren: den fäller FÖRE skrivningen. En rad som hunnit in går inte att
        // ångra — tabellen har ingen raderingsväg i nattjobbet.
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void allaMarkenUtanModellistaArEttHaveri() {
        CargoSpecRepository repo = mock(CargoSpecRepository.class);
        when(repo.findAllCarNames()).thenReturn(List.of());
        var service = new FejkadSync(repo, marken(5), Map.of());   // fetchModels ger null

        assertThatThrownBy(service::syncCarNames)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inget av 5 märken");
    }

    @Test
    void ettMarkeUtanModellistaStopparInteResten() {
        CargoSpecRepository repo = mock(CargoSpecRepository.class);
        when(repo.findAllCarNames()).thenReturn(List.of());
        Map<String, List<String>> modeller = new LinkedHashMap<>();
        modeller.put("marke0", null);                       // saknar rutan
        modeller.put("marke1", List.of("V60", "V90"));
        var service = new FejkadSync(repo, marken(2), modeller);

        assertThat(service.syncCarNames()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        var sparade = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(sparade.capture());
        assertThat(((List<CargoSpec>) sparade.getValue()).stream().map(CargoSpec::getCarName))
                .containsExactly("Marke1 V60", "Marke1 V90");
    }
}
