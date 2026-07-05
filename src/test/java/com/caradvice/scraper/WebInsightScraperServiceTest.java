package com.caradvice.scraper;

import com.caradvice.repository.ExpertInsightRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

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
}
