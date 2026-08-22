package com.caradvice.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tokenmätningen som gör Groq-budgeten tunbar. Utan den syntes promptstorleken bara i
 * 413-felen, alltså först när minuttaket redan sprängts.
 */
class TokenUsageStatsTest {

    @Test
    void rapportenRaknarMotMINUTTAKETOchInteMotSvaret() {
        TokenUsageStats s = new TokenUsageStats();
        // Groq mäter prompt + RESERVERADE tokens. Svarets längd spelar ingen roll för taket —
        // ett kort svar på en stor prompt kostar precis lika mycket av minutbudgeten.
        s.registrera("openai/gpt-oss-120b", 4700, 900, 3000);

        Map<String, Object> r = s.rapport();
        assertThat(r.get("varstaMotMinuttaket")).isEqualTo(7700);
        assertThat(r.get("anropSomRymsPerMinut")).isEqualTo(1);   // 8000 / 7700
    }

    @Test
    void kortarePromptGerFlerAnropPerMinut() {
        TokenUsageStats s = new TokenUsageStats();
        s.registrera("openai/gpt-oss-120b", 2000, 900, 3000);   // 5000 mot taket

        assertThat(s.rapport().get("anropSomRymsPerMinut")).isEqualTo(1);

        TokenUsageStats s2 = new TokenUsageStats();
        s2.registrera("openai/gpt-oss-120b", 1000, 900, 3000);  // 4000 mot taket
        assertThat(s2.rapport().get("anropSomRymsPerMinut")).isEqualTo(2);
    }

    @Test
    void snittOchMaxHallsIsarPerModell() {
        TokenUsageStats s = new TokenUsageStats();
        s.registrera("a", 1000, 100, 3000);
        s.registrera("a", 3000, 300, 3000);
        s.registrera("b", 500, 50, 1800);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modeller = (List<Map<String, Object>>) s.rapport().get("perModell");
        assertThat(modeller).hasSize(2);
        Map<String, Object> a = modeller.get(0);
        assertThat(a.get("modell")).isEqualTo("a");
        assertThat(a.get("anrop")).isEqualTo(2L);
        assertThat(a.get("promptSnitt")).isEqualTo(2000L);
        assertThat(a.get("promptMax")).isEqualTo(3000);
    }

    @Test
    void detaljlistanVaxerInteFritt() {
        TokenUsageStats s = new TokenUsageStats();
        for (int i = 0; i < TokenUsageStats.SENASTE_MAX + 15; i++) s.registrera("a", 100 + i, 10, 3000);

        Map<String, Object> r = s.rapport();
        assertThat(r.get("matPunkter")).isEqualTo(TokenUsageStats.SENASTE_MAX);
        // Sammanräkningen per modell tappar däremot ingenting
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modeller = (List<Map<String, Object>>) r.get("perModell");
        assertThat(modeller.get(0).get("anrop")).isEqualTo((long) TokenUsageStats.SENASTE_MAX + 15);
    }

    @Test
    void tomRapportPastarIngetOmKapaciteten() {
        Map<String, Object> r = new TokenUsageStats().rapport();
        assertThat(r.get("matPunkter")).isEqualTo(0);
        assertThat(r.get("varstaMotMinuttaket")).isEqualTo(0);
        assertThat(r.get("anropSomRymsPerMinut")).isNull();   // hellre null än ett påhittat tal
    }
}
