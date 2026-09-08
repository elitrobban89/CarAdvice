package com.caradvice.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiFailureStatsTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rader(AiFailureStats s) {
        return (List<Map<String, Object>>) s.rapport().get("senaste");
    }

    @Test
    void raknarPerModellSaManSerOmFelenSamlas() {
        AiFailureStats s = new AiFailureStats();
        s.registrera("rek", "qwen/qwen3.8-27b", "stop", "Unexpected character", "{trasig");
        s.registrera("rek", "qwen/qwen3.8-27b", "stop", "Unexpected character", "{trasig");
        s.registrera("rek", "openai/gpt-oss-120b", "length", "Unexpected end", "{halv");

        assertThat(s.rapport().get("perModell"))
                .isEqualTo(Map.of("qwen/qwen3.8-27b", 2L, "openai/gpt-oss-120b", 1L));
    }

    @Test
    void rasvaretSparasSaFeletGarAttLasaIEfterhand() {
        AiFailureStats s = new AiFailureStats();
        s.registrera("rek", "m", "stop", "orsak", "{\"recommendations\": [\"avhugget");
        Map<String, Object> rad = rader(s).get(0);
        assertThat(rad.get("rasvar")).isEqualTo("{\"recommendations\": [\"avhugget");
        assertThat(rad.get("finishReason")).isEqualTo("stop");
    }

    @Test
    void langtRasvarKapasMenLangdenBevaras() {
        AiFailureStats s = new AiFailureStats();
        String langt = "x".repeat(AiFailureStats.RASVAR_MAX_TECKEN + 500);
        s.registrera("rek", "m", "length", "orsak", langt);

        Map<String, Object> rad = rader(s).get(0);
        // Längden är hela poängen vid ett avhugget svar — den får aldrig gå förlorad i kapningen.
        assertThat(rad.get("langd")).isEqualTo(AiFailureStats.RASVAR_MAX_TECKEN + 500);
        assertThat((String) rad.get("rasvar")).endsWith("…[kapad]");
    }

    @Test
    void buffertenVaxerInteFritt() {
        AiFailureStats s = new AiFailureStats();
        for (int i = 0; i < AiFailureStats.SENASTE_MAX + 7; i++) s.registrera("rek", "m", "stop", "o", "nr" + i);

        assertThat(rader(s)).hasSize(AiFailureStats.SENASTE_MAX);
        // Äldst faller ut först — det senaste felet är det man felsöker.
        assertThat(rader(s).get(rader(s).size() - 1).get("rasvar"))
                .isEqualTo("nr" + (AiFailureStats.SENASTE_MAX + 6));
    }

    @Test
    void nullRasvarKraschaInte() {
        AiFailureStats s = new AiFailureStats();
        s.registrera("rek", "m", "stop", "orsak", null);
        assertThat(rader(s).get(0).get("langd")).isEqualTo(0);
    }
}
