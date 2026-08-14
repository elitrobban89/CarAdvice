package com.caradvice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Årsmodellen i AI-titlar, med och utan parentes. */
class CarTitleTest {

    @Test
    void artalMittITitelnFlyttasTillSlutet() {
        // Live 2026-08-09: AI:n skrev "Nissan Leaf 2019 60 kWh". year() är ankrad i slutet av
        // strängen och gav null, så Blockets årsfilter, dedupen, nyprisuppslaget,
        // leasingmatchningen och årsmodellvakten tappade årsmodellen tyst.
        assertThat(CarTitle.normalize("Nissan Leaf 2019 60 kWh")).isEqualTo("Nissan Leaf (2019)");
        assertThat(CarTitle.year(CarTitle.normalize("Nissan Leaf 2019 60 kWh"))).isEqualTo(2019);
    }

    @Test
    void teknikuppgifterITitelnStadasBort() {
        // "1.3 kWh" är dessutom påhittat — Zoe har 41 eller 52 kWh batteri
        assertThat(CarTitle.normalize("Renault Zoe 2019 1.3 kWh")).isEqualTo("Renault Zoe (2019)");
        assertThat(CarTitle.normalize("Kia EV6 GT-Line 239 hk (2022)")).isEqualTo("Kia EV6 GT-Line (2022)");
    }

    @Test
    void smaltMellanslagRaddarInteTeknikuppgiftenForbiStadningen() {
        // Live 2026-08-10: AI:n skrev "Nissan Leaf (62 kWh) (2020)" med SMALT hårt mellanslag.
        // Javas \s matchar bara ASCII-blanksteg, så SPEC_JUNK bet inte och "62 kWh" följde med ut
        // på kortet. Följden syntes först i motorlistan: verifiedEngineOptions hittade ingen rad
        // för den titeln och kortet visade AI:ns egen lista utan trimnamn och generationsfilter.
        // OBS: titlarna nedan innehåller riktiga U+202F respektive U+00A0 — ser ut som vanliga
        // mellanslag i editorn. Byts de mot ASCII-blanksteg testar raderna ingenting.
        assertThat(CarTitle.normalize("Nissan Leaf (62 kWh) (2020)")).isEqualTo("Nissan Leaf (2020)");
        assertThat(CarTitle.normalize("MG4 (64 kWh) (2023)")).isEqualTo("MG4 (2023)");
        assertThat(CarTitle.year("Volkswagen ID.4 2026")).isEqualTo(2026);
        assertThat(CarTitle.stripYear("Volkswagen ID.4 (2026)")).isEqualTo("Volkswagen ID.4");
    }

    @Test
    void typografisktBindestreckBlirVanligtBindestreck() {
        /*
         * Live 2026-08-14, elbilssök 175 000 kr: AI:n skrev "Volkswagen e‑Golf (2018)" med
         * U+2011 (non-breaking hyphen). Namnet såg rätt ut för ögat men var en annan sträng, så
         * EvSpecService hittade ingen rad — kortet fick evSpec null och visade AI:ns egen text
         * "44 kWh 95hk (400km)" i stället för verifierade 35,8 kWh / 231 km. Bilen har aldrig
         * funnits med 44 kWh, och kortets EGEN nackdelstext sa "Batterikapacitet 35 kWh".
         *
         * Samma familj som det smala mellanslaget ovan, fast på skiljetecknet. Värst för de
         * modeller vars namn bygger på strecket: e-Golf, e-tron, C-HR, Skyactiv-G, T-GDI.
         *
         * OBS: strecken nedan är riktiga U+2011, U+2013 och U+2212 — ser ut som vanliga
         * bindestreck i editorn. Byts de mot ASCII testar raderna ingenting.
         */
        assertThat(CarTitle.normalize("Volkswagen e‑Golf (2018)")).isEqualTo("Volkswagen e-Golf (2018)");
        assertThat(CarTitle.stripYear("Toyota C–HR Hybrid (2020)")).isEqualTo("Toyota C-HR Hybrid");
        assertThat(CarTitle.normalize("Audi Q4 e−tron (2022)")).isEqualTo("Audi Q4 e-tron (2022)");
        // Vanligt bindestreck rörs förstås inte
        assertThat(CarTitle.normalize("Kia EV6 GT-Line (2022)")).isEqualTo("Kia EV6 GT-Line (2022)");
    }

    @Test
    void tomParentesForsvinnerMedTeknikuppgiften() {
        // Utan den här raden blir "Nissan Leaf (62 kWh) (2020)" till "Nissan Leaf ( ) (2020)" —
        // parentesen står kvar tom och följer med in i ordmatchningen mot ev_spec
        assertThat(CarTitle.normalize("Nissan Leaf (62 kWh) (2020)")).isEqualTo("Nissan Leaf (2020)");
        assertThat(CarTitle.normalize("Kia EV6 (77 kWh)")).isEqualTo("Kia EV6");
    }

    @Test
    void redanKorrektTitelLamnasOrord() {
        assertThat(CarTitle.normalize("Volkswagen ID.4 (2023)")).isEqualTo("Volkswagen ID.4 (2023)");
        assertThat(CarTitle.normalize("MG4 (2022)")).isEqualTo("MG4 (2022)");
    }

    @Test
    void trimnamnOchModellnamnMedSiffrorOverlever() {
        // "Long Range"/"Extended Range" är modellnamn, inte brus. Peugeot 2008 och BMW 2002 är
        // bilar — därför måste ett bart årtal vara 2010 eller senare för att räknas som årsmodell.
        assertThat(CarTitle.normalize("Kia EV4 Long Range (2026)")).isEqualTo("Kia EV4 Long Range (2026)");
        assertThat(CarTitle.normalize("MG4 Extended Range 2023")).isEqualTo("MG4 Extended Range (2023)");
        assertThat(CarTitle.normalize("Peugeot 2008 (2021)")).isEqualTo("Peugeot 2008 (2021)");
        assertThat(CarTitle.normalize("BMW 2002")).isEqualTo("BMW 2002");
    }

    @Test
    void titelUtanArtalBehallerSittNamn() {
        assertThat(CarTitle.normalize("Nissan Leaf")).isEqualTo("Nissan Leaf");
        assertThat(CarTitle.normalize(null)).isNull();
    }

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
