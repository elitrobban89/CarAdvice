package com.caradvice.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vakten for prisrattelserna. Den bevakar det som faktiskt gick sonder: att en mindre
 * batteriversion stod DYRARE an en storre inom samma modell, darfor att vara svenska listpriser
 * lag bredvid synkens EUR-omrakning.
 *
 * <p>Provet ar avsiktligt ett rent tabellprov utan databas. Det som gar att bevisa utan drift ar
 * att TALEN i {@link DataLoader#SVENSKA_LISTPRISER} ar inbordes konsekventa - att sjalva
 * skrivningen nar databasen visas av att raderna byter varde i produktion, vilket matts separat.
 *
 * @author Robert Andersson Kopler
 */
class SvenskaListpriserTest {

    @Test
    void storreBatteriKostarMerInomSammaModell() {
        // Kia EV3: Long Range stod pa 370 000 och Standard Range pa 425 000 - 55 000 kr fel vag.
        assertThat(DataLoader.SVENSKA_LISTPRISER.get("Kia EV3 Long Range"))
                .isGreaterThan(DataLoader.SVENSKA_LISTPRISER.get("Kia EV3 Standard Range"));
    }

    @Test
    void talenArKiasEgnaOchHyundaisEgna() {
        // Hardkodade mot kallan, inte mot varandra: ett prov som bara jamfor tva tal med varandra
        // godkanner vilket par som helst sa lange ordningen stammer.
        assertThat(DataLoader.SVENSKA_LISTPRISER)
                .containsEntry("Kia EV3 Standard Range", 429_900)   // Kias prislista 2026-01-30
                .containsEntry("Kia EV3 Long Range", 508_300)       // samma prislista
                .containsEntry("Hyundai IONIQ 5", 603_800)          // Hyundai SE, RWD 84 kWh MY27
                .containsEntry("BMW i4 eDrive40", 599_900)          // BMW SE, Active Edition
                .containsEntry("Subaru Solterra AWD", 579_900)      // svenskt lanseringspris 2023
                .containsEntry("Subaru Solterra AWD 73.1 kWh", 534_900); // Subaru SE, Limited 26MY
    }

    @Test
    void solterrasInversionArAktaOchSkaStaKvar() {
        // Den NYARE bilen ar billigare, och det ar inget fel - Subaru sankte priset med
        // faceliftet. Provet finns for att nasta genomgang inte ska "laga" ordningen.
        assertThat(DataLoader.SVENSKA_LISTPRISER.get("Subaru Solterra AWD 73.1 kWh"))
                .isLessThan(DataLoader.SVENSKA_LISTPRISER.get("Subaru Solterra AWD"));
    }

    @Test
    void raderUtanSvenskKallaFarIngetPasettPris() {
        // eDrive35 sals bara i Danmark. Skulle nagon senare frestas att satta ett harlett pris
        // faller det har: listan over rattade priser och listan over medvetet ororda far inte
        // overlappa.
        for (String utanKalla : DataLoader.PRISER_UTAN_SVENSK_KALLA) {
            assertThat(DataLoader.SVENSKA_LISTPRISER)
                    .as("%s har ingen svensk kalla och ska darfor inte sta i prislistan", utanKalla)
                    .doesNotContainKey(utanKalla);
        }
    }
}
