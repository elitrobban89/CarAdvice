package com.caradvice.scraper;

import com.caradvice.repository.ExpertInsightRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebInsightScraperServiceTest {

    private WebInsightScraperService service() {
        return new WebInsightScraperService(mock(ExpertInsightRepository.class), mock(JdbcTemplate.class));
    }

    private static String groqResponse(String content) {
        return """
            {"choices":[{"message":{"content":%s}}]}
            """.formatted(com.fasterxml.jackson.databind.node.TextNode.valueOf(content).toString());
    }

    @Test
    void parsarInsiktslista() {
        String content = """
            {"insights":[{"car_make":"Volvo","car_model":"XC40","fuel_type":"elbil","category":"suv",
            "insight":"Bra bil.","rating":8,"source_ref":""}]}
            """;
        List<JsonNode> result = service().parseInsightJson(groqResponse(content), "test");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("car_make").asText()).isEqualTo("Volvo");
        assertThat(result.get(0).path("rating").asInt()).isEqualTo(8);
    }

    @Test
    void hanterarMarkdownKodstaket() {
        String content = "```json\n{\"insights\":[{\"insight\":\"En insikt.\"}]}\n```";
        List<JsonNode> result = service().parseInsightJson(groqResponse(content), "test");
        assertThat(result).hasSize(1);
    }

    @Test
    void tomListaVidTrasigJson() {
        assertThat(service().parseInsightJson(groqResponse("inte json alls"), "test")).isEmpty();
        assertThat(service().parseInsightJson("{}", "test")).isEmpty();
        assertThat(service().parseInsightJson(groqResponse("{\"insights\":\"inte en array\"}"), "test")).isEmpty();
    }

    @Test
    void ogiltigKategoriOchDrivmedelBlirNull() {
        // Ferrari som "ekonomibil" förgiftade rekommendationsprompten — värden utanför whitelisten kastas
        assertThat(WebInsightScraperService.validOrNull("suv", Set.of("suv", "elbil"))).isEqualTo("suv");
        assertThat(WebInsightScraperService.validOrNull("SUV ", Set.of("suv"))).isEqualTo("suv");
        assertThat(WebInsightScraperService.validOrNull("sportbil", Set.of("suv", "elbil"))).isNull();
        assertThat(WebInsightScraperService.validOrNull("", Set.of("suv"))).isNull();
        assertThat(WebInsightScraperService.validOrNull(null, Set.of("suv"))).isNull();
    }

    @Test
    void mallEkoRaderIdentifieras() throws Exception {
        // AI:n ekade fältmallen som riktiga rader ("car_make car_model" / "insight") — 6 st hittades i DB
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(WebInsightScraperService.isTemplateEcho(
                mapper.readTree("{\"car_make\":\"car_make\",\"insight\":\"insight\"}"))).isTrue();
        assertThat(WebInsightScraperService.isTemplateEcho(
                mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"insight\"}"))).isTrue();
        assertThat(WebInsightScraperService.isTemplateEcho(
                mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"Bra bil.\"}"))).isFalse();
    }

    @Test
    void normaliseringIgnorerarSkiftlageOchInterpunktion() {
        assertThat(WebInsightScraperService.normalizeForCompare("Bra bil, 2 500 kg dragvikt!"))
                .isEqualTo(WebInsightScraperService.normalizeForCompare("bra bil 2500 kg dragvikt"));
        assertThat(WebInsightScraperService.normalizeForCompare("Räckvidd 63 mil."))
                .isNotEqualTo(WebInsightScraperService.normalizeForCompare("Räckvidd 53 mil."));
    }

    @Test
    void parsarDubblettIndex() {
        assertThat(service().parseDuplicateIndexes(groqResponse("{\"duplicates\":[0,2]}")))
                .containsExactlyInAnyOrder(0, 2);
        assertThat(service().parseDuplicateIndexes(groqResponse("```json\n{\"duplicates\":[]}\n```"))).isEmpty();
    }

    @Test
    void trasigtDedupSvarGerTomMangd() {
        // fail open — vid oparsbart svar sparas allt hellre än att insikter tappas
        assertThat(service().parseDuplicateIndexes(groqResponse("inte json"))).isEmpty();
        assertThat(service().parseDuplicateIndexes(groqResponse("{\"duplicates\":\"inte en array\"}"))).isEmpty();
        assertThat(service().parseDuplicateIndexes("{}")).isEmpty();
    }

    @Test
    void exaktDubblettFiltrerasMotBefintliga() throws Exception {
        var repo = mock(ExpertInsightRepository.class);
        org.mockito.Mockito.when(repo.findTop15ByCarMakeIgnoreCaseAndCarModelIgnoreCaseOrderByIdDesc("BYD", "Shark"))
                .thenReturn(List.of(new com.caradvice.model.ExpertInsight(
                        "CarUp", "BYD", "Shark", null, null, "Bilen har en maximal dragvikt på 2 500 kg.", null)));
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode dubblett = mapper.readTree(
                "{\"car_make\":\"BYD\",\"car_model\":\"Shark\",\"insight\":\"Bilen har en maximal dragvikt på 2500 kg!\"}");
        assertThat(service.filterKnownDuplicates(List.of(dubblett))).isEmpty();
    }

    @Test
    void insiktUtanModellEllerUtanBefintligaBehalls() throws Exception {
        var repo = mock(ExpertInsightRepository.class);
        org.mockito.Mockito.when(repo.findTop15ByCarMakeIgnoreCaseAndCarModelIgnoreCaseOrderByIdDesc(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode utanModell = mapper.readTree("{\"car_make\":\"\",\"car_model\":\"\",\"insight\":\"Generell insikt.\"}");
        JsonNode nyBil = mapper.readTree("{\"car_make\":\"Kia\",\"car_model\":\"EV3\",\"insight\":\"Ny insikt.\"}");
        assertThat(service.filterKnownDuplicates(List.of(utanModell, nyBil))).hasSize(2);
    }

    @Test
    void dedupPromptListarBefintligaOchIndexeradeKandidater() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kandidat = mapper.readTree(
                "{\"car_make\":\"BYD\",\"car_model\":\"Shark\",\"insight\":\"Blade-batteriet är på 32,2 kWh.\"}");
        String user = WebInsightScraperService.buildDedupUserContent(
                List.of(kandidat), java.util.Map.of("BYD Shark", List.of("Batteriet på 32,2 kWh är stort.")));
        assertThat(user).contains("BYD Shark:")
                .contains("- Batteriet på 32,2 kWh är stort.")
                .contains("0 (BYD Shark): Blade-batteriet är på 32,2 kWh.");
    }

    @Test
    void parsarWpJsonLankar() throws Exception {
        String json = """
            [{"link":"https:\\/\\/elbilen.se\\/mazda-pressar-priset\\/"},
             {"link":"https:\\/\\/elbilen.se\\/tesla-analys\\/"},
             {"other":"fält utan link ignoreras"}]
            """;
        assertThat(service().parseWpJsonLinks(json)).containsExactly(
                "https://elbilen.se/mazda-pressar-priset/",
                "https://elbilen.se/tesla-analys/");
    }

    @Test
    void tomWpJsonGerTomLankLista() throws Exception {
        assertThat(service().parseWpJsonLinks("[]")).isEmpty();
        assertThat(service().parseWpJsonLinks("{}")).isEmpty();
    }
}
