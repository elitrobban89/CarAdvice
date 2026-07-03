package com.caradvice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enhetstester för CSV-parsern. Ren funktion utan beroenden —
 * inga mocks behövs, bara indata och förväntat resultat.
 */
class SafetyRatingServiceCsvTest {

    @Test
    void splittarEnkelKommaseparadRad() {
        String[] f = SafetyRatingService.parseCsvLine("Volvo,XC60,2024,5");
        assertThat(f).containsExactly("Volvo", "XC60", "2024", "5");
    }

    @Test
    void behallerKommanInomCitattecken() {
        String[] f = SafetyRatingService.parseCsvLine("\"Bra bil, men dyr\",8");
        assertThat(f).containsExactly("Bra bil, men dyr", "8");
    }

    @Test
    void tomtFaltBlirNull() {
        String[] f = SafetyRatingService.parseCsvLine("Volvo,,XC60");
        assertThat(f).containsExactly("Volvo", null, "XC60");
    }

    @Test
    void trimmarWhitespaceRuntFalt() {
        String[] f = SafetyRatingService.parseCsvLine("  Volvo , XC60  ");
        assertThat(f).containsExactly("Volvo", "XC60");
    }

    @Test
    void tomtSistaFaltBlirNull() {
        String[] f = SafetyRatingService.parseCsvLine("Volvo,");
        assertThat(f).containsExactly("Volvo", null);
    }

    @Test
    void citatteckenTasBortFranFalt() {
        String[] f = SafetyRatingService.parseCsvLine("\"Volvo\",\"XC60\"");
        assertThat(f).containsExactly("Volvo", "XC60");
    }
}
