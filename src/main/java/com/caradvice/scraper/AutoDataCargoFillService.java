package com.caradvice.scraper;

import com.caradvice.service.CargoSpecService;
import com.caradvice.service.CarTitle;
import com.caradvice.service.IceConsumptionService;
import com.caradvice.service.IceGenerationService;
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
    private final com.caradvice.service.IceGenerationService iceGenerations;

    public AutoDataCargoFillService(AutoDataScraperService autoData, CargoSpecService cargoSpecs,
                                    IceConsumptionService iceConsumption,
                                    com.caradvice.service.IceGenerationService iceGenerations) {
        this.autoData = autoData;
        this.cargoSpecs = cargoSpecs;
        this.iceConsumption = iceConsumption;
        this.iceGenerations = iceGenerations;
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

    /**
     * Fyller {@code ice_generation} — startåret för den generation som modellens motorlista
     * beskriver. Se {@link com.caradvice.service.IceGenerationService} för varför bara årtalet
     * hämtas och inte hela motorutbudet.
     *
     * <p>Ligger i det här jobbet och inte i ett eget av två skäl: arbetslistan är samma
     * modellnamn som bagageifyllningen redan går, och sidcachen är varm efter den — en modell vi
     * just hämtat bagage för kostar nästan ingenting att hämta generationen för. Ett eget
     * nattjobb hade dessutom krävt egen schemaläggning, egen jobbstatusrad och egen
     * adminplumbning för samma källa och samma kadens.
     *
     * <p>Eget tak: bagagelistan krymper mot noll medan den här har 310 modeller att beta av, och
     * ett gemensamt tak hade låtit den ena svälta ut den andra.
     *
     * @return antal modeller som fick ett årtal
     */
    public int fyllGenerationsar() {
        // Kända nej filtreras bort tillsammans med de redan fyllda: taket räknar FÖRSÖK, och
        // utan det här filtret gick 118 av nattens 150 försök till modeller som missat förut.
        // Se IceGenerationService.noteraMiss för hur fronten annars stannar mitt i alfabetet.
        List<String> namn = iceConsumption.allModelNames().stream()
                .filter(n -> !iceGenerations.harArtal(n))
                .filter(n -> !iceGenerations.harFarskMiss(n))
                .toList();
        if (namn.isEmpty()) {
            log.info("auto-data generation: inga modeller kvar att pröva (fyllda eller nyligen missade)");
            return 0;
        }

        int fyllda = 0, forsokta = 0, ejHittad = 0, vaktenAvstod = 0, hamtningsfel = 0;
        for (String modell : namn) {
            if (forsokta >= MAX_PER_KORNING) break;
            forsokta++;
            try {
                // Senaste generationen är nästan alltid en FACELIFT, och dess franAr är
                // faceliftens år — 2024 för Golf VIII som kom 2020. Sparat rakt av fällde vakten
                // varje kort från 2020-2023, alltså modeller där listan var helt riktig.
                // basgenerationsStartAr går tillbaka till generationens eget startår.
                Integer franAr = autoData.basgenerationsStartAr(modell);
                if (franAr == null) {
                    iceGenerations.noteraMiss(modell, IceGenerationService.ORSAK_EJ_HITTAD);
                    ejHittad++;
                    continue;
                }
                if (!beskriverSammaGeneration(modell)) {
                    iceGenerations.noteraMiss(modell, IceGenerationService.ORSAK_VAKTEN_AVSTOD);
                    vaktenAvstod++;
                    continue;
                }
                iceGenerations.spara(modell, franAr);
                fyllda++;
                log.info("auto-data generation: {} → från {}", modell, franAr);
            } catch (Exception e) {
                // Antecknas INTE som miss: ett undantag är ett nätverksfel eller en tillfälligt
                // trasig sida, och den modellen ska prövas igen redan nästa natt. Bara ett
                // svar vi förstått — ingen träff, eller fel generation — är ett riktigt nej.
                // Sedan 2026-08-16 håller det löftet: AutoDataScraperService.hamta kastar i
                // stället för att lämna tomsträng, så felet når hit i stället för att förklä
                // sig till ett nej. Se HamtningsFel.
                log.warn("auto-data generation: {} misslyckades — {}", modell, e.getMessage());
                hamtningsfel++;
            }
        }
        // Orsakerna räknas var för sig: "utan träff" var förut EN siffra för både ett dött
        // uppslag och en vakt som avstod med flit, och just den hopslagningen gjorde att 139
        // falska nej kunde parkeras i 30 dagar utan att något såg fel ut. Stiger ejHittad mot
        // antalet försök är uppslaget trasigt — inte auto-data som saknar bilarna.
        log.info("auto-data generation: {} fyllda, {} ej hittade, {} vakten avstod, {} hämtningsfel, "
                        + "{} försökta av {} kvar ({} kända nej)",
                fyllda, ejHittad, vaktenAvstod, hamtningsfel, forsokta, namn.size(),
                iceGenerations.antalMissar());
        return fyllda;
    }

    /**
     * Sant när auto-datas senaste generation rimligen är den vår motorlista beskriver.
     *
     * <p><b>Årtalet är bara rätt om antagandet håller</b> att {@code ice_consumption} bär
     * modellens NUVARANDE generation. För de allra flesta stämmer det, men inte för en modell som
     * just bytt generation: Mazda CX-5 III kom 2025 med en enda motor (141 hk) medan vår CSV bär
     * CX-5 II:s sju varianter (150-230 hk). Ett sparat 2025 hade tystat varje CX-5-kort mellan
     * 2017 och 2024 — kort som fick en helt korrekt lista innan.
     *
     * <p>Provet är hästkrafterna: delar listorna inte en enda siffra beskriver de olika bilar.
     * Vid tveksamhet sparas inget, och då gäller "ingen åsikt" precis som för en modell vi ännu
     * inte hunnit fylla. <b>Överblockering är den dyra riktningen här</b> — den syns aldrig som
     * ett felaktigt värde utan som ett tomt fält på kortet.
     *
     * <p>Kostar en extra sidhämtning per modell (generationssidan). Märkes- och modellsidan ligger
     * redan i scraperns cache efter uppslaget ovan.
     */
    private boolean beskriverSammaGeneration(String modell) {
        java.util.Set<Integer> vara = iceConsumption.effekterForModell(modell);
        if (vara.isEmpty()) return true;   // inget att jämföra med — låt årtalet stå

        // Samma tolerans som årtalsuppslaget: motorerForBil kräver att karossordet stämmer, och
        // med det kravet får varje modell som räddas av karosstoleransen en TOM lista här — vilket
        // räknas som hämtningsfel och släpper igenom årtalet oprövat. Se motorerForGenerationsprovning.
        java.util.Set<Integer> deras = new java.util.HashSet<>();
        for (var m : autoData.motorerForGenerationsprovning(modell)) if (m.hk() != null) deras.add(m.hk());
        if (deras.isEmpty()) return true;  // tom lista är ett hämtningsfel, inte ett bevis

        if (java.util.Collections.disjoint(vara, deras)) {
            log.info("auto-data generation: {} hoppas över — vår motorlista {} delar ingen effekt "
                    + "med generationens {}, alltså troligen olika generationer", modell, vara, deras);
            return false;
        }
        return true;
    }
}
