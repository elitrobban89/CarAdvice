package com.caradvice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Uppvärmningens hela poäng är att den försöker IGEN när källan sover - och att den slutar
 * försöka när den lyckats. Båda halvorna mäts här; nätet rörs aldrig.
 */
class PrisUppvarmningTest {

    /** Inga väntetider i testet - tre försök direkt efter varandra. */
    private static final long[] SNABBT = {0L, 0L, 0L};

    @Test
    void slutarForsokaSaSnartBadaPriserFinns() {
        FuelPriceService bransle = mock(FuelPriceService.class);
        ElectricityPriceService el = mock(ElectricityPriceService.class);
        when(bransle.varmUppForsok()).thenReturn(true);
        when(el.varmUppForsok()).thenReturn(true);

        int forsok = new PrisUppvarmning(bransle, el, SNABBT).varmUpp();

        assertThat(forsok).isEqualTo(1);
        verify(bransle, times(1)).varmUppForsok();
        verify(el, times(1)).varmUppForsok();
    }

    @Test
    void forsokerIgenNarKallanSover() {
        /*
         * Det verkliga fallet: bilresa.onrender.com ligger pa gratisnivan och behover 115-121 s
         * pa sig att vakna, medan hamtningen har 8 sekunders timeout. Ett ensamt forsok vid
         * uppstart hade darfor gett noll varje gang kallan rakat somna.
         */
        FuelPriceService bransle = mock(FuelPriceService.class);
        ElectricityPriceService el = mock(ElectricityPriceService.class);
        when(bransle.varmUppForsok()).thenReturn(false, false, true);
        when(el.varmUppForsok()).thenReturn(true);

        int forsok = new PrisUppvarmning(bransle, el, SNABBT).varmUpp();

        assertThat(forsok).isEqualTo(3);
        verify(bransle, times(3)).varmUppForsok();
        // Elpriset lyckades pa forsta forsoket och ska INTE hamtas om i de foljande.
        verify(el, times(1)).varmUppForsok();
    }

    @Test
    void gerUppEfterSistaForsoketIStalletForAttSnurra() {
        FuelPriceService bransle = mock(FuelPriceService.class);
        ElectricityPriceService el = mock(ElectricityPriceService.class);
        when(bransle.varmUppForsok()).thenReturn(false);
        when(el.varmUppForsok()).thenReturn(false);

        int forsok = new PrisUppvarmning(bransle, el, SNABBT).varmUpp();

        assertThat(forsok).isEqualTo(3);
        verify(bransle, times(3)).varmUppForsok();
        verify(el, times(3)).varmUppForsok();
    }

    @Test
    void ettUndantagFallerInteHelaUppvarmningen() {
        // Ett natverksfel i den ena tjansten far aldrig hindra den andra fran att varmas.
        FuelPriceService bransle = mock(FuelPriceService.class);
        ElectricityPriceService el = mock(ElectricityPriceService.class);
        when(bransle.varmUppForsok()).thenThrow(new RuntimeException("connect timeout"));
        when(el.varmUppForsok()).thenReturn(true);

        int forsok = new PrisUppvarmning(bransle, el, SNABBT).varmUpp();

        assertThat(forsok).isEqualTo(3);
        verify(el, times(1)).varmUppForsok();
    }

    @Test
    void standardschematTackerEnKallstart() {
        // Uppvakningen ar uppmatt till 115-121 s: sista forsoket MASTE ligga efter det.
        long summa = 0;
        for (long v : PrisUppvarmning.STANDARD_FORSOK_MS) summa += v;

        assertThat(summa).isGreaterThan(121_000L);
        assertThat(PrisUppvarmning.STANDARD_FORSOK_MS[0]).isZero();
    }
}
