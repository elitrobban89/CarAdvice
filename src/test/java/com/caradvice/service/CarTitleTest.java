package com.caradvice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Årsmodellen i AI-titlar, med och utan parentes. */
class CarTitleTest {

    @Test
    void parentesformenArKvar() {
        assertThat(CarTitle.stripYear("Volkswagen ID.4 (2026)")).isEqualTo("Volkswagen ID.4");
        assertThat(CarTitle.year("Volkswagen ID.4 (2026)")).isEqualTo(2026);
        assertThat(CarTitle.stripYear("Dacia Sandero (2021+)")).isEqualTo("Dacia Sandero");
        assertThat(CarTitle.year("Dacia Sandero (2021+)")).isEqualTo(2021);
    }

    @Test
    void artalUtanParentesRaknasOcksa() {
        // Live 2026-08-07: AI:n skrev "Volkswagen ID.4 2026" och varenda parser missade årtalet
        assertThat(CarTitle.stripYear("Volkswagen ID.4 2026")).isEqualTo("Volkswagen ID.4");
        assertThat(CarTitle.year("Volkswagen ID.4 2026")).isEqualTo(2026);
        assertThat(CarTitle.stripYear("MG4 2026")).isEqualTo("MG4");
        assertThat(CarTitle.year("Nissan Leaf 2018")).isEqualTo(2018);
    }

    @Test
    void modellnamnSomSerUtSomArtalRorsInte() {
        // Peugeot 2008 och BMW 2002 är bilar, inte årsmodeller — därför gränsen vid 2010
        assertThat(CarTitle.stripYear("Peugeot 2008")).isEqualTo("Peugeot 2008");
        assertThat(CarTitle.year("Peugeot 2008")).isNull();
        assertThat(CarTitle.stripYear("BMW 2002")).isEqualTo("BMW 2002");

        // Med parentes är avsikten otvetydig och årtalet plockas ändå
        assertThat(CarTitle.stripYear("Peugeot 2008 (2022)")).isEqualTo("Peugeot 2008");
        assertThat(CarTitle.year("Peugeot 2008 (2022)")).isEqualTo(2022);
        // ... och ett årtal efter modellnamnet fungerar också
        assertThat(CarTitle.stripYear("Peugeot 2008 2022")).isEqualTo("Peugeot 2008");
    }

    @Test
    void modellsiffrorSomInteArArtalRorsInte() {
        assertThat(CarTitle.stripYear("Peugeot 3008")).isEqualTo("Peugeot 3008");
        assertThat(CarTitle.stripYear("Peugeot 5008")).isEqualTo("Peugeot 5008");
        assertThat(CarTitle.stripYear("Volvo V90")).isEqualTo("Volvo V90");
        assertThat(CarTitle.stripYear("Tesla Model 3")).isEqualTo("Tesla Model 3");
        assertThat(CarTitle.year("Tesla Model 3")).isNull();
    }

    @Test
    void taligMotTommaVarden() {
        assertThat(CarTitle.stripYear(null)).isNull();
        assertThat(CarTitle.year(null)).isNull();
        assertThat(CarTitle.stripYear("  Volvo EX30  ")).isEqualTo("Volvo EX30");
    }
}
