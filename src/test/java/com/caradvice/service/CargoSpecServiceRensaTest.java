package com.caradvice.service;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Städvägen efter parserhaveriet natten till 2026-09-17, då namnsynken tog bilwebs nya filter-
 * och stadslänkar för modeller och la in <b>3551</b> rader på en körning ({@code Volvo Ystad},
 * {@code XPENG 2024}). Tabellen hade dittills ingen raderingsväg alls.
 *
 * <p>Provet låser den enda regel som gör en handskriven städlista ofarlig: <b>en rad med uppmätt
 * volym raderas aldrig</b>. Volymen är det enda i tabellen som inte går att skapa om ur källan,
 * och ett felskrivet namn ska synas i svaret i stället för att tyst ta med sig mätdata.
 */
class CargoSpecServiceRensaTest {

    private final CargoSpecRepository repo = mock(CargoSpecRepository.class);
    private final CargoSpecService service = new CargoSpecService(repo, null);

    @Test
    void raderarBaraRaderUtanVolym() {
        CargoSpec skrap = new CargoSpec("Volvo Ystad", null, null);
        CargoSpec matt = new CargoSpec("Volvo V60", 529, 1441);
        when(repo.findAll()).thenReturn(List.of(skrap, matt));

        var svar = service.raderaUtanVolym("Volvo Ystad\nVolvo V60\n", false);

        assertThat(svar).containsEntry("raderade", 1).containsEntry("skyddade", 1);
        assertThat(svar.get("skyddadeRader")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("Volvo V60");

        ArgumentCaptor<Iterable<CargoSpec>> raderade = ArgumentCaptor.forClass(Iterable.class);
        verify(repo).deleteAll(raderade.capture());
        assertThat(raderade.getValue()).containsExactly(skrap);
    }

    /** Volym 0 är ett omätt fält, inte en bil utan bagage — samma läsning som arbetslistan gör. */
    @Test
    void nollaRaknasSomUtanVolym() {
        CargoSpec noll = new CargoSpec("XPENG 2024", 0, 0);
        when(repo.findAll()).thenReturn(List.of(noll));

        assertThat(service.raderaUtanVolym("XPENG 2024", false)).containsEntry("raderade", 1);
    }

    @Test
    void okantNamnRaknasSomOkantOchRaderarIngenting() {
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo V60", 529, 1441)));

        var svar = service.raderaUtanVolym("Volvo Finns-Inte", false);

        assertThat(svar).containsEntry("raderade", 0).containsEntry("okanda", 1);
        verify(repo, never()).deleteAll(anyIterable());
    }

    @Test
    void dryRunRaknarUtanAttRora() {
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo Ystad", null, null)));
        when(repo.count()).thenReturn(5236L);

        var svar = service.raderaUtanVolym("Volvo Ystad", true);

        assertThat(svar).containsEntry("raderade", 1).containsEntry("kvar", 5235L);
        verify(repo, never()).deleteAll(anyIterable());
    }

    /** Tomma rader, kommentarer och citattecken ska inte bli namn som saknas. */
    @Test
    void blankaRaderOchKommentarerHoppasOver() {
        when(repo.findAll()).thenReturn(List.of(new CargoSpec("Volvo Ystad", null, null)));

        var svar = service.raderaUtanVolym("# städlista 2026-09-17\n\n\"Volvo Ystad\"\n\n", false);

        assertThat(svar).containsEntry("raderade", 1).containsEntry("okanda", 0);
    }
}
