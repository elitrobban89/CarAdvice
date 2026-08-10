package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ifyllningen av bagagevolym från nattens EV-synk. Luckan är bilar som SAKNAR rad, inte rader
 * som saknar siffra: cargo_spec hade 243 rader den 2026-08-10 och samtliga bar volym, medan
 * ev_spec hade 518 elbilsvarianter. Bagagefiltrets vakt kan därför bara fälla på positivt bevis.
 * ev-database bär siffran på sidor som ändå besöks varje natt.
 *
 * <p>Siffran 679 som stod här tidigare kom från {@code /api/cars}, som är UNIONEN av cargo_spec
 * och ev_spec — misstaget dolde att {@code utanVolym} redan var 0.
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
        // DataLoaders seedade volymer är handkontrollerade och vinner alltid över en skrapad
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
    void bilUtanRadFarEnNy() {
        // Luckan är bilar som SAKNAR rad, inte rader som saknar siffra: cargo_spec har 243 rader
        // och alla bär volym, medan ev_spec har 518 elbilsvarianter. Första versionen vägrade
        // skapa rader och kunde därför aldrig fylla någonting — det syntes först när
        // /api/admin/cargo-coverage svarade utanVolym: 0 efter en 12-minuterskörning.
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo EX30", 318, 904)));

        assertThat(service.fillFromScrape("Tesla Model Y Long Range", 854, 2158)).isTrue();
        ArgumentCaptor<CargoSpec> sparad = ArgumentCaptor.forClass(CargoSpec.class);
        verify(repo).save(sparad.capture());
        assertThat(sparad.getValue().getCarName()).isEqualTo("Tesla Model Y Long Range");
        assertThat(sparad.getValue().getCargoLiters()).isEqualTo(854);
        assertThat(sparad.getValue().getCargoMaxLiters()).isEqualTo(2158);
    }

    @Test
    void taktBilFarIngenDubblettUnderVariantnamn() {
        // Matchningen söker bland ALLA rader, inte bara de tomma — annars hade varje variantsida
        // skapat en ny rad för en bil som redan har volym
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Kia EV6", 490, 1300)));

        assertThat(service.fillFromScrape("Kia EV6 Long Range 2WD", 480, 1280)).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void enordsradMatcharAldrig() {
        // "Volvo" ensamt ligger inne som namn och hade annars fått första bästa Volvos volym.
        // Ingen match betyder numera att raden skapas — men under den skrapade bilens namn.
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo", null, null)));

        assertThat(service.fillFromScrape("Volvo EX90 Twin Motor", 655, 1915)).isTrue();
        ArgumentCaptor<CargoSpec> sparad = ArgumentCaptor.forClass(CargoSpec.class);
        verify(repo).save(sparad.capture());
        assertThat(sparad.getValue().getCarName()).isEqualTo("Volvo EX90 Twin Motor");
    }

    @Test
    void volymNollFyllerInget() {
        // Sidan saknade siffran — då ska varken rad fyllas eller skapas, annars ser en omätt
        // bil ut som en bil utan bagage och fälls av vakten
        // (findAll behövs inte: metoden returnerar innan uppslaget)

        assertThat(service.fillFromScrape("Kia EV6 GT", 0, 0)).isFalse();
        verify(repo, never()).save(any());
    }
}
