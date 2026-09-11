package com.caradvice.service;

import com.caradvice.service.UpcomingAdCheckService.Dom;
import com.caradvice.service.UpcomingAdCheckService.Rapport;
import com.caradvice.service.UpcomingAdCheckService.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Släpper ur kommande-kön de rader som annonskollen kan döma UTAN mänsklig avvägning:
 * bilen har annonser <b>och</b> raden säger ingenting om framtiden.
 *
 * <p><b>Varför den finns.</b> Fram till 2026-09-11 var varje släpp handpåläggning: rutinen
 * rapporterade {@code LARM} på morgonen och en människa fick kalla på admin-API:t rad för rad.
 * Fyra morgnar i rad gav samma sorts fynd (Skoda Epiq 09-09, Kia EV3/Ioniq 9/Dacia Spring 09-10,
 * EX40/EC40/A2 e-tron 09-11), alltså ett arbete som återkom dagligen och aldrig krävde ett
 * omdöme som inte redan fanns i domen.
 *
 * <p><b>Den gör INTE det som mättes som omöjligt.</b> {@link UpcomingAdCheckService} säger rakt
 * ut att "har Blocket annonser, parkera inte" inte går att automatisera: prövad mot hela kön
 * 2026-09-02 hade den släppt ut Hyundai Tucson (4 rader), Santa Fe (1) och Lexus NX 450h+ (1),
 * alla korrekt parkerade. Den här tjänsten tar en <b>strikt smalare</b> delmängd — raderna i
 * {@link Dom#raderUtanNyhetsord()} på en {@link Status#LARM}-bil — och det är just den delmängd
 * mätningen skiljde ut: <b>varenda</b> korrekt parkerad rad för Tucson, Santa Fe och NX 450h+ bar
 * ett nyhetsord, medan ingen av de sex felparkerade Ioniq 3-raderna gjorde det. Annonskollen
 * släpper alltså fortfarande aldrig något själv; den är oförändrat rådgivande, och beslutet
 * ligger här.
 *
 * <p><b>Restraderna måste rapporteras, annars tystnar felet.</b> Släpper man raderna utan
 * nyhetsord faller bilen från {@code LARM} till {@code GRANSKA} — "bilen säljs men varje köad rad
 * säger själv att den gäller nästa generation" — och nästa morgons rapport kallar den korrekt
 * parkerad. Det stämmer för Tucson men inte nödvändigtvis för en ansiktslyftning: den 09-11 låg
 * fyra EX40-rader i kön, två utan nyhetsord ("EX40 får en WLTP-räckvidd på upp till 575
 * kilometer") och två med ("EX40 får <b>nya</b> bakljus"), och alla fyra var felparkerade.
 * Automatiken hade tagit två av dem. Därför bär {@link Slappt#kvar()} de rader som blev kvar på
 * en bil vi faktiskt släppte ifrån — de är ett kvarstående beslut, inte ett avklarat ärende, och
 * rapporten ska skriva ut dem.
 *
 * <p><b>Taket är en säkring, inte en optimering.</b> Går annonsuppslaget sönder på ett sätt som
 * gör att allt ser sålt ut töms kön på en natt, och en parkering går inte att återskapa ur
 * rapporten. Över {@link #MAX_SLAPP_PER_KORNING} rader släpps därför <b>ingenting alls</b> och
 * utfallet säger varför. Ett släpp är i sig reversibelt ({@code POST
 * /api/admin/insights/{id}/upcoming} parkerar igen), men bara så länge någon vet vilka id:n det
 * gällde.
 */
@Service
public class UpcomingAutoReleaseService {

    private static final Logger log = LoggerFactory.getLogger(UpcomingAutoReleaseService.class);

    /**
     * Så många rader får en körning släppa. Kön har legat på 8-66 rader sedan den byggdes och de
     * fyra skarpa morgnarna gav 1-6 felparkerade rader, så taket bör aldrig slå — och slår det
     * är det ett tecken på att uppslaget ljuger, inte på att kön äntligen städas.
     */
    static final int MAX_SLAPP_PER_KORNING = 10;

    /**
     * @param slappta de id:n som släpptes (eller skulle ha släppts vid {@code dryRun})
     * @param kvar    köade rader på SAMMA bil som bär nyhetsord och alltså står kvar — se klassens
     *                javadoc om varför de måste rapporteras
     */
    public record Slappt(String carMake, String carModel, int annonser,
                         List<Long> slappta, List<Long> kvar) {}

    /**
     * @param taketSlogTill när true släpptes INGENTING, oavsett vad {@code per} innehåller
     */
    public record Utfall(int slappta, int bilar, boolean dryRun, boolean taketSlogTill,
                         List<Slappt> per, Rapport rapport) {}

    private final UpcomingAdCheckService adCheck;
    private final UpcomingInsightService upcoming;

    public UpcomingAutoReleaseService(UpcomingAdCheckService adCheck, UpcomingInsightService upcoming) {
        this.adCheck = adCheck;
        this.upcoming = upcoming;
    }

    /** Kör annonskollen och släpp det den dömer som säkert. {@code dryRun} rör ingenting. */
    public Utfall kor(boolean dryRun) {
        Rapport rapport = adCheck.granska(upcoming.list());

        List<Slappt> per = new ArrayList<>();
        int kandidater = 0;
        for (Dom dom : rapport.domar()) {
            if (dom.status() != Status.LARM || dom.raderUtanNyhetsord().isEmpty()) continue;
            List<Long> kvar = new ArrayList<>(dom.rader());
            kvar.removeAll(dom.raderUtanNyhetsord());
            per.add(new Slappt(dom.carMake(), dom.carModel(), dom.annonser(),
                    List.copyOf(dom.raderUtanNyhetsord()), List.copyOf(kvar)));
            kandidater += dom.raderUtanNyhetsord().size();
        }

        if (kandidater > MAX_SLAPP_PER_KORNING) {
            log.warn("Autosläpp AVBRUTET: annonskollen pekade ut {} rader att släppa, taket är {} — "
                            + "ingenting släpptes, granska kön för hand", kandidater, MAX_SLAPP_PER_KORNING);
            return new Utfall(0, per.size(), dryRun, true, per, rapport);
        }

        int slappta = 0;
        for (Slappt bil : per) {
            for (Long id : bil.slappta()) {
                if (!dryRun && !upcoming.release(id)) {
                    // Raden var inte längre parkerad - någon hann före, eller insikten är borta.
                    log.info("Autosläpp: {} {} rad {} var redan släppt", bil.carMake(), bil.carModel(), id);
                    continue;
                }
                slappta++;
                log.warn("Autosläpp{}: {} {} rad {} släppt — {} annonser och raden säger inget om framtiden",
                        dryRun ? " (dry run)" : "", bil.carMake(), bil.carModel(), id, bil.annonser());
            }
            if (!bil.kvar().isEmpty())
                log.warn("Autosläpp: {} {} har {} rad(er) kvar i kön som bär nyhetsord ({}) — de kräver ett beslut",
                        bil.carMake(), bil.carModel(), bil.kvar().size(), bil.kvar());
        }
        return new Utfall(slappta, per.size(), dryRun, false, per, rapport);
    }
}
