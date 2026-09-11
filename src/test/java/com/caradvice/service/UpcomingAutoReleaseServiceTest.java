package com.caradvice.service;

import com.caradvice.service.UpcomingAdCheckService.Dom;
import com.caradvice.service.UpcomingAdCheckService.Rapport;
import com.caradvice.service.UpcomingAdCheckService.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Autosläppet ur kommande-kön.
 *
 * <p>Domarna nedan är formade efter de fyra skarpa morgnarna 09-09 till 09-11: en LARM-bil vars
 * rader BLANDAR nyhetsord och ren fakta (Volvo EX40 09-11), en GRANSKA-bil som säljs men vars
 * rader gäller nästa generation (Hyundai Tucson, korrekt parkerad sedan 08-22), och ett uppslag
 * som inte svarade. Poängen med tjänsten är gränsen mellan dem.
 */
class UpcomingAutoReleaseServiceTest {

    private final UpcomingAdCheckService adCheck = mock(UpcomingAdCheckService.class);
    private final UpcomingInsightService upcoming = mock(UpcomingInsightService.class);
    private final UpcomingAutoReleaseService tjanst = new UpcomingAutoReleaseService(adCheck, upcoming);

    private static Dom dom(String make, String model, Status status, List<Long> alla, List<Long> utanNyhetsord) {
        return new Dom(make, model, status, status == Status.INGA_ANNONSER ? 0 : 42,
                alla, utanNyhetsord, List.of());
    }

    private void rapporten(Dom... domar) {
        Map<String, Long> perStatus = new LinkedHashMap<>();
        for (Status s : Status.values()) perStatus.put(s.name(), 0L);
        for (Dom d : domar) perStatus.merge(d.status().name(), 1L, Long::sum);
        when(adCheck.granska(any())).thenReturn(
                new Rapport(domar.length, List.of(domar).stream().mapToInt(d -> d.rader().size()).sum(),
                        0, perStatus, List.of(domar)));
        when(upcoming.release(anyLong())).thenReturn(true);
    }

    @Test
    void slapperBaraLarmraderUtanNyhetsord() {
        /*
         * Hela gränsdragningen i ett prov. EX40 är LARM med fyra köade rader, varav två säger
         * ingenting om framtiden ("EX40 får en WLTP-räckvidd på upp till 575 kilometer") och två
         * bär nyhetsord ("EX40 får NYA bakljus"). Tucson säljs också, men varenda köad rad handlar
         * om femte generationen — den är korrekt parkerad och mättes som sådan 09-02. Pajero har
         * inga annonser alls.
         */
        rapporten(
                dom("Volvo", "EX40", Status.LARM, List.of(1486L, 1483L, 1474L, 1473L), List.of(1483L, 1473L)),
                dom("Hyundai", "Tucson", Status.GRANSKA, List.of(1295L, 1294L), List.of()),
                dom("Mitsubishi", "Pajero", Status.INGA_ANNONSER, List.of(1414L), List.of()),
                dom("Audi", "A2 e-tron", Status.UPPSLAG_MISSLYCKADES, List.of(1478L), List.of(1478L)));

        var utfall = tjanst.kor(false);

        assertThat(utfall.slappta()).isEqualTo(2);
        assertThat(utfall.bilar()).isEqualTo(1);
        assertThat(utfall.taketSlogTill()).isFalse();
        verify(upcoming).release(1483L);
        verify(upcoming).release(1473L);
        // Raderna med nyhetsord står kvar, liksom hela GRANSKA- och INGA_ANNONSER-bilarna
        verify(upcoming, never()).release(1486L);
        verify(upcoming, never()).release(1474L);
        verify(upcoming, never()).release(1295L);
        verify(upcoming, never()).release(1414L);
        // ...och ett uppslag som INTE svarade är inte ett godkännande att släppa
        verify(upcoming, never()).release(1478L);
    }

    @Test
    void restradernaFoljerMedIUtfallet() {
        /*
         * Den tysta fällan: när raderna utan nyhetsord släppts faller bilen från LARM till
         * GRANSKA, och nästa morgons rapport kallar den korrekt parkerad. Det stämmer för Tucson
         * men inte for EX40 - alla fyra raderna var felparkerade 09-11. Restraderna måste därför
         * stå i utfallet, annars försvinner beslutet ur rapporten i stället för att fattas.
         */
        rapporten(dom("Volvo", "EX40", Status.LARM, List.of(1486L, 1483L, 1474L, 1473L), List.of(1483L, 1473L)));

        var bil = tjanst.kor(false).per().get(0);

        assertThat(bil.carModel()).isEqualTo("EX40");
        assertThat(bil.slappta()).containsExactly(1483L, 1473L);
        assertThat(bil.kvar()).containsExactly(1486L, 1474L);
    }

    @Test
    void dryRunRorIngenRad() {
        rapporten(dom("Volvo", "EX40", Status.LARM, List.of(1483L, 1473L), List.of(1483L, 1473L)));

        var utfall = tjanst.kor(true);

        // Samma svar som en skarp körning hade gett — annars går den inte att lita på som prov
        assertThat(utfall.slappta()).isEqualTo(2);
        assertThat(utfall.dryRun()).isTrue();
        assertThat(utfall.per().get(0).slappta()).containsExactly(1483L, 1473L);
        verify(upcoming, never()).release(anyLong());
    }

    @Test
    void taketSlapperIngentingAlls() {
        /*
         * Säkringen, inte en optimering: går annonsuppslaget sönder så att allt ser sålt ut töms
         * kön på en natt, och en parkering går inte att återskapa ur rapporten. Över taket släpps
         * därför INGENTING — inte "de tio första".
         */
        List<Long> manga = new ArrayList<>();
        for (long id = 1400; id < 1400 + UpcomingAutoReleaseService.MAX_SLAPP_PER_KORNING + 1; id++) manga.add(id);
        rapporten(dom("Volvo", "EX40", Status.LARM, manga, manga));

        var utfall = tjanst.kor(false);

        assertThat(utfall.taketSlogTill()).isTrue();
        assertThat(utfall.slappta()).isZero();
        verify(upcoming, never()).release(anyLong());
        // Bilen redovisas ändå, annars vet läsaren inte vad som stoppades
        assertThat(utfall.per()).hasSize(1);
    }

    @Test
    void enRadSomRedanSlapptsRaknasInte() {
        // Körs den två gånger samma morgon ska den andra köra säga 0, inte 2 — annars ser en
        // dubbelkörning ut som att kön fylldes på igen mellan körningarna.
        rapporten(dom("Volvo", "EX40", Status.LARM, List.of(1483L, 1473L), List.of(1483L, 1473L)));
        when(upcoming.release(anyLong())).thenReturn(false);

        assertThat(tjanst.kor(false).slappta()).isZero();
    }

    @Test
    void enTomKoGorIngenting() {
        rapporten();

        var utfall = tjanst.kor(false);

        assertThat(utfall.slappta()).isZero();
        assertThat(utfall.bilar()).isZero();
        assertThat(utfall.taketSlogTill()).isFalse();
        verify(upcoming, never()).release(anyLong());
    }
}
