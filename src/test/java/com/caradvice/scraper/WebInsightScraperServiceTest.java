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

    private static WebInsightScraperService serviceWithExisting(com.caradvice.model.ExpertInsight... existing) {
        var repo = mock(ExpertInsightRepository.class);
        org.mockito.Mockito.when(repo.findByMakePrefix(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(existing));
        return new WebInsightScraperService(repo, mock(JdbcTemplate.class));
    }

    @Test
    void exaktDubblettFiltrerasMotBefintliga() throws Exception {
        var service = serviceWithExisting(new com.caradvice.model.ExpertInsight(
                "CarUp", "BYD", "Shark", null, null, "Bilen har en maximal dragvikt på 2 500 kg.", null));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode dubblett = mapper.readTree(
                "{\"car_make\":\"BYD\",\"car_model\":\"Shark\",\"insight\":\"Bilen har en maximal dragvikt på 2500 kg!\"}");
        assertThat(service.filterKnownDuplicates(List.of(dubblett))).isEmpty();
    }

    @Test
    void insiktUtanModellEllerUtanBefintligaBehalls() throws Exception {
        var service = serviceWithExisting();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode utanModell = mapper.readTree("{\"car_make\":\"\",\"car_model\":\"\",\"insight\":\"Generell insikt.\"}");
        JsonNode nyBil = mapper.readTree("{\"car_make\":\"Kia\",\"car_model\":\"EV3\",\"insight\":\"Ny insikt.\"}");
        assertThat(service.filterKnownDuplicates(List.of(utanModell, nyBil))).hasSize(2);
    }

    @Test
    void dubblettMedAnnanMarkesStavningFiltreras() throws Exception {
        // AMG CLA 45 kom in dubbelt: "Mercedes-Benz CLA 45 4MATIC+" och "Mercedes AMG CLA 45 4Matic+"
        var service = serviceWithExisting(new com.caradvice.model.ExpertInsight(
                "TV", "Mercedes-Benz", "CLA 45 4MATIC+", null, null, "Bilen levererar 680 hk.", null));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode dubblett = mapper.readTree(
                "{\"car_make\":\"Mercedes\",\"car_model\":\"AMG CLA 45 4Matic+\",\"insight\":\"Bilen levererar 680 hk!\"}");
        assertThat(service.filterKnownDuplicates(List.of(dubblett))).isEmpty();
    }

    @Test
    void exaktUpprepningInomSammaBatchFiltreras() throws Exception {
        var service = serviceWithExisting();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode forsta = mapper.readTree(
                "{\"car_make\":\"Mini\",\"car_model\":\"Cooper Cabrio\",\"insight\":\"Billig som cabriolet.\"}");
        JsonNode upprepning = mapper.readTree(
                "{\"car_make\":\"Mini\",\"car_model\":\"Cooper Cabrio\",\"insight\":\"Billig, som cabriolet!\"}");
        assertThat(service.filterKnownDuplicates(List.of(forsta, upprepning))).hasSize(1);
    }

    @Test
    void sammaBilPaTokenDelmangd() {
        assertThat(WebInsightScraperService.sameCar("CLA 45 4MATIC+", "AMG CLA 45 4Matic+")).isTrue();
        assertThat(WebInsightScraperService.sameCar("EV4", "EV4 AWD")).isTrue();
        assertThat(WebInsightScraperService.sameCar("Shark", "Shark")).isTrue();
        assertThat(WebInsightScraperService.sameCar("XC40", "XC60")).isFalse();
        assertThat(WebInsightScraperService.sameCar("", "Shark")).isFalse();
        assertThat(WebInsightScraperService.sameCar("Shark", null)).isFalse();
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
    void parsarIrrelevantIndex() {
        assertThat(service().parseIndexes(groqResponse("{\"irrelevant\":[1,3]}"), "irrelevant"))
                .containsExactlyInAnyOrder(1, 3);
        // fail open — trasigt svar får aldrig kasta bort hela batchen
        assertThat(service().parseIndexes(groqResponse("inte json"), "irrelevant")).isEmpty();
        assertThat(service().parseIndexes(groqResponse("{\"irrelevant\":\"nej\"}"), "irrelevant")).isEmpty();
    }

    @Test
    void relevansPromptListarIndexeradeInsikter() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode ins = mapper.readTree(
                "{\"car_make\":\"Nissan\",\"car_model\":\"Tekton\",\"insight\":\"Säljs enbart i Afrika och Mellanöstern.\"}");
        String user = WebInsightScraperService.buildRelevanceUserContent(List.of(ins));
        assertThat(user).contains("0 (Nissan Tekton): Säljs enbart i Afrika och Mellanöstern.");
    }

    @Test
    void relevansvaktenSlapperIgenomAlltUtanApiNyckel() throws Exception {
        // apiKey är null i testtjänsten — vakten ska då vara helt passiv (fail open)
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode ins = mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"Bra bil.\"}");
        assertThat(service().filterIrrelevant(List.of(ins))).hasSize(1);
        assertThat(service().filterIrrelevant(List.of())).isEmpty();
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
