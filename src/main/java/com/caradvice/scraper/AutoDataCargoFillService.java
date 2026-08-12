package com.caradvice.scraper;

import com.caradvice.service.CargoSpecService;
import com.caradvice.service.CarTitle;
import com.caradvice.service.IceConsumptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fyller bagagevolym för de bilar ev-database inte kan svara för — alltså bensin, diesel och
 * hybrid.
 *
 * <p>Bakgrunden: {@code cargo_spec} hade 553 rader med volym den 2026-08-12, alla hämtade ur
 * ev-database.org, som bara listar elbilar. Bilnamnen kommer däremot från Bilweb och täcker hela
 * marknaden, så förbränningsbilarna stod som namn utan siffra. Följden syntes i bagagekravet:
 * {@code GroqService.requireCargoCapacity} faller bara på POSITIVT bevis, så en omätt bil
 * passerar. Kravet var alltså skarpt för elbilar och nästan verkningslöst för resten.
 *
 * <p>Ordningen mot ev-database är medveten: den här körs EFTER, och
 * {@link CargoSpecService#fillFromScrape} skriver aldrig över en rad som redan bär volym. Det
 * gör auto-data till komplettering, inte konkurrent, och de kurerade raderna i DataLoader står
 * kvar orörda.
 */
@Service
public class AutoDataCargoFillService {

    private static final Logger log = LoggerFactory.getLogger(AutoDataCargoFillService.class);

    /**
     * Så många bilar hämtas per körning. Varje ny modell kostar upp till fyra sidhämtningar med
     * 1,5 s paus, alltså ~6 s, medan rader för en modell vi redan besökt är nästan gratis
     * (sidcachen). Taket håller nattjobbet inom rimlig tid och gör att tabellen betas av över
     * flera nätter i stället för i en körning som riskerar att timeouta.
     */
    private static final int MAX_PER_KORNING = 150;

    private final AutoDataScraperService autoData;
    private final CargoSpecService cargoSpecs;
    private final IceConsumptionService iceConsumption;

    public AutoDataCargoFillService(AutoDataScraperService autoData, CargoSpecService cargoSpecs,
                                    IceConsumptionService iceConsumption) {
        this.autoData = autoData;
        this.cargoSpecs = cargoSpecs;
        this.iceConsumption = iceConsumption;
    }

    /**
     * Arbetslistan: bilar som saknar bagagevolym.
     *
     * <p><b>Den kan inte byggas på rader utan volym, och det var första versionens fel.</b>
     * Mätt 2026-08-12 efter deployen: {@code cargo-coverage} gav 553 / 553 / 0 — alltså noll
     * rader utan volym, och ifyllningen hade ingenting att göra. Förbränningsbilarna saknas
     * nämligen inte som TOMMA rader, de saknas som rader över huvud taget: alla 553 kommer från
     * ev-database och är elbilar, och Bilweb-namnsynken som skulle skapa resten ger 0 nya varje
     * natt.
     *
     * <p>Listan byggs därför på {@code ice_consumption}, som bär 304 distinkta modeller och per
     * definition är just bensin, diesel och hybrid — exakt luckan. Rader utan volym tas med
     * också, så listan fungerar den dag Bilweb-synken börjar leverera igen.
     *
     * <p>Redan täckta bilar filtreras bort på {@code formatForTitle}, alltså samma fuzzy-matchning
     * som kortet använder. {@code fillFromScrape} hade ändå vägrat skriva över dem, men varje
     * onödig bil kostar upp till fyra sidhämtningar.
     */
    List<String> arbetslista() {
        java.util.LinkedHashSet<String> ut = new java.util.LinkedHashSet<>(cargoSpecs.namnUtanVolym());
        ut.addAll(iceConsumption.allModelNames());

        List<String> kvar = new java.util.ArrayList<>();
        for (String namn : ut) {
            try {
                if (cargoSpecs.formatForTitle(namn) != null) continue;   // redan täckt
            } catch (Exception ignored) { /* hellre ett extra försök än en tappad bil */ }
            kvar.add(namn);
        }
        return kvar;
    }

    /**
     * Fyller volym för bilar som saknar den.
     *
     * <p>Bilnamnen i tabellen bär ingen årsmodell ("Volkswagen Golf", inte "Golf (2021)"), så
     * generationsvalet får inget år att gå på och tar då den senaste generationen. Det är rätt
     * för en tabell som beskriver MODELLEN snarare än ett enskilt exemplar — och volymen ändrar
     * sig sällan mellan generationer (Golf VIII 380 l mot faceliftens 381 l), till skillnad från
     * batteri och räckvidd där samma genväg hade varit skadlig.
     *
     * @return antal rader som fylldes
     */
    public int fyllSaknadeVolymer() {
        List<String> namn = arbetslista();
        if (namn.isEmpty()) {
            log.info("auto-data bagage: inga bilar saknar volym");
            return 0;
        }

        int fyllda = 0, forsokta = 0, utanTraff = 0;
        for (String bilnamn : namn) {
            if (forsokta >= MAX_PER_KORNING) break;
            forsokta++;
            try {
                var vol = autoData.bagageForBil(bilnamn, CarTitle.year(bilnamn));
                if (vol == null || vol.minLiter() == null || vol.minLiter() <= 0) {
                    utanTraff++;
                    continue;
                }
                // Maxvolymen är frivillig hos källan; 0 betyder "vet inte" för fillFromScrape.
                int max = vol.maxLiter() != null ? vol.maxLiter() : 0;
                if (cargoSpecs.fillFromScrape(bilnamn, vol.minLiter(), max)) {
                    fyllda++;
                    log.info("auto-data bagage: {} → {} l (max {} l)", bilnamn, vol.minLiter(), max);
                }
            } catch (Exception e) {
                // En bil som strular får aldrig fälla hela nattjobbet.
                log.warn("auto-data bagage: {} misslyckades — {}", bilnamn, e.getMessage());
                utanTraff++;
            }
        }
        log.info("auto-data bagage: {} fyllda, {} utan träff, {} försökta av {} saknade",
                fyllda, utanTraff, forsokta, namn.size());
        return fyllda;
    }
}
