package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ifyllningen av bagagevolym från nattens EV-synk. cargo_spec har 679 kända bilnamn men bara
 * 185 med volym — CargoSpecSyncService hämtar bara NAMN från Bilweb och skriver null i
 * literkolumnen — så bagagefiltrets vakt kan bara fälla på positivt bevis. ev-database bär
 * siffran på sidor som ändå besöks varje natt.
 */
class CargoSpecServiceFillTest {

    private final CargoSpecRepository repo = mock(CargoSpecRepository.class);
    private final CargoSpecService service = new CargoSpecService(repo);

    @Test
    void tomRadFyllsMedSkrapadVolym() {
        CargoSpec tom = new CargoSpec("Kia EV6", null, null);
        when(repo.findAll()).thenReturn(List.of(tom));

        assertThat(service.fillFromScrape("Kia EV6 Long Range 2WD", 490, 1300)).isTrue();
        assertThat(tom.getCargoLiters()).isEqualTo(490);
        assertThat(tom.getCargoMaxLiters()).isEqualTo(1300);
        verify(repo).save(tom);
    }

    @Test
    void kurateradVolymSkrivsAldrigOver() {
        // DataLoaders 185 seedade volymer är handkontrollerade och vinner alltid över en skrapad
        CargoSpec seedad = new CargoSpec("Volvo EX30", 318, 904);
        when(repo.findAll()).thenReturn(List.of(seedad));

        assertThat(service.fillFromScrape("Volvo EX30 Single Motor", 400, 1000)).isFalse();
        assertThat(seedad.getCargoLiters()).isEqualTo(318);
        verify(repo, never()).save(any());
    }

    @Test
    void mestSpecifikaNamnetVinner() {
        // En GT-sida ska fylla GT-raden, inte basmodellens
        CargoSpec bas = new CargoSpec("Kia EV6", null, null);
        CargoSpec gt = new CargoSpec("Kia EV6 GT", null, null);
        when(repo.findAll()).thenReturn(List.of(bas, gt));

        assertThat(service.fillFromScrape("Kia EV6 GT", 480, 1260)).isTrue();
        assertThat(gt.getCargoLiters()).isEqualTo(480);
        assertThat(bas.getCargoLiters()).isNull();
    }

    @Test
    void ingenMatchSkaparIngenNyRad() {
        // ev-database har en sida per VARIANT medan bagagevolym är en modellegenskap — nya rader
        // per variant hade fyllt både tabellen och autocomplete (/api/cars) med dubbletter
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo EX30", null, null)));

        assertThat(service.fillFromScrape("Tesla Model Y Long Range", 854, 2158)).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void enordsradMatcharAldrig() {
        // "Volvo" ensamt ligger inne som namn och hade annars fått första bästa Volvos volym
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo", null, null)));

        assertThat(service.fillFromScrape("Volvo EX90 Twin Motor", 655, 1915)).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void volymNollFyllerInget() {
        // Sidan saknade siffran — då ska raden lämnas tom i stället för att sättas till 0,
        // annars ser en omätt bil ut som en bil utan bagage och fälls av vakten
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Kia EV6", null, null)));

        assertThat(service.fillFromScrape("Kia EV6 GT", 0, 0)).isFalse();
        verify(repo, never()).save(any());
    }
}
