package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hämtar bränsle- och elpriserna en gång vid uppstart, i bakgrunden.
 *
 * <p><b>Varför den behövs.</b> Båda pristjänsterna är byggda för att aldrig blockera en
 * sidladdning: {@code senastKandaPriser} och {@code senastKandSnabbladdning} svarar med
 * <b>nollor</b> tills en hämtning lyckats, och den första hämtningen sker i praktiken när
 * någon ställer en AI-fråga. Splashen läser samma tal ur {@code /api/stats}, så efter varje
 * deploy stod den med "0,00 kr/l" tills dagens första chattfråga råkade värma cachen.
 * Mätt 2026-09-13: klockan 17:41 startade tjänsten om, och två timmar senare visade
 * {@code live} fortfarande bensin 0, diesel 0 och snabbladdning 0.
 *
 * <p><b>Varför den inte bara kan anropa en gång.</b> Källorna ({@code bilresa.onrender.com} och
 * {@code elbilsladdning.onrender.com}) ligger på Renders gratisnivå och sover. Uppvakningen är
 * uppmätt till 115-121 sekunder, medan hämtningen har 8 sekunders timeout — ett ensamt försök
 * vid uppstart skulle alltså missa varje gång källan råkat somna. Därför fyra försök med
 * växande mellanrum, som slutar så fort båda priserna finns.
 *
 * <p>Trådan är en daemon: den får aldrig hålla JVM:en vid liv, och ett misslyckat försök
 * ändrar ingenting - det lata beteendet i tjänsterna står kvar oförändrat som sista utväg.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class PrisUppvarmning {

    private static final Logger log = LoggerFactory.getLogger(PrisUppvarmning.class);

    /** 0 s, 30 s, 2 min, 4 min: täcker både en vaken källa och en som kallstartar. */
    static final long[] STANDARD_FORSOK_MS = {0L, 30_000L, 120_000L, 240_000L};

    private final FuelPriceService bransle;
    private final ElectricityPriceService el;
    private final long[] forsokEfterMs;

    /**
     * <b>@Autowired behovs trots att klassen bara har en PUBLIK konstruktor.</b> Spring valjer
     * automatiskt bara nar det finns EN konstruktor overhuvudtaget; hittar den flera - har den
     * paketprivata som testerna anvander for att korta vantetiderna - kraver den en tom
     * konstruktor i stallet, och utan den dog uppstarten med "No default constructor found"
     * (09-13, deployen av a4e09e0 rullades tillbaka av Render). Synligheten spelar ingen roll.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PrisUppvarmning(FuelPriceService bransle, ElectricityPriceService el) {
        this(bransle, el, STANDARD_FORSOK_MS);
    }

    PrisUppvarmning(FuelPriceService bransle, ElectricityPriceService el, long[] forsokEfterMs) {
        this.bransle = bransle;
        this.el = el;
        this.forsokEfterMs = forsokEfterMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startaUppvarmning() {
        Thread t = new Thread(this::varmUpp, "prisuppvarmning");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Försöker tills båda priserna finns eller försöken tar slut.
     *
     * @return antal genomförda försök
     */
    int varmUpp() {
        boolean bransleKlar = false, elKlar = false;
        int forsok = 0;
        for (long vantan : forsokEfterMs) {
            if (bransleKlar && elKlar) break;
            if (vantan > 0) {
                try { Thread.sleep(vantan); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            forsok++;
            // Varje tjänst prövas för sig: bränslepriset kan lyckas medan elpriset sover.
            try { if (!bransleKlar) bransleKlar = bransle.varmUppForsok(); }
            catch (Exception e) { log.debug("Uppvärmning bränslepris: {}", e.getMessage()); }
            try { if (!elKlar) elKlar = el.varmUppForsok(); }
            catch (Exception e) { log.debug("Uppvärmning elpris: {}", e.getMessage()); }
        }
        if (bransleKlar && elKlar)
            log.info("Prisuppvärmning klar efter {} försök - bensin/diesel och snabbladdning i cachen", forsok);
        else
            log.warn("Prisuppvärmning gav upp efter {} försök (bränsle {}, el {}) - splashen visar 0 kr tills "
                    + "första AI-anropet fyller cachen", forsok, bransleKlar ? "OK" : "tomt", elKlar ? "OK" : "tomt");
        return forsok;
    }
}
