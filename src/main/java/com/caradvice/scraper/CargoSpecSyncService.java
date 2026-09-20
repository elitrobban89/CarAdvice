package com.caradvice.scraper;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/** @author Robert Andersson Kopler */
@Service
public class CargoSpecSyncService {

    private static final Logger log = LoggerFactory.getLogger(CargoSpecSyncService.class);

    /**
     * Märkeslistan. Låg till 2026-09-01 på {@code /sok/bilar}, som då hade märkena i en
     * {@code select}. Sidan är borttagen — {@code www.bilweb.se/sok/bilar} 301:ar till
     * {@code bilweb.se/sok/bilar} som svarar <b>404</b>, och sedan dess hämtade nattjobbet
     * noll märken. Nya listan ligger på /alla-marken, en länk per märke.
     */
    private static final String BILWEB_MAKES_URL = "https://bilweb.se/alla-marken";
    /** Modellsidan: {@code /sok/<märkesslug>}, med modellerna som länkar till /sok/&lt;slug&gt;/&lt;modell&gt;. */
    private static final String BILWEB_MODELS_URL = "https://bilweb.se/sok/";
    /** Sajtens egen märkning på märkeslänkarna — 170 länkar, 170 träffar, inga andra /sok/-länkar på sidan. */
    private static final String MAKE_LINK_SELECTOR = "a[data-track-click=alla_marken_make_click]";
    /**
     * Modellistans behållare på märkessidan — {@code <div id="alla-modeller">} under rubriken
     * "Alla &lt;märke&gt;-modeller till salu".
     *
     * <p><b>Varför modellerna måste läsas ur just den rutan.</b> Fram till 2026-09-16 var varje
     * {@code /sok/<slug>/}-länk på sidan en modell, så parsern tog hela sidan. Natten till 09-17
     * hade Bilweb lagt till två SEO-avsnitt längst ned — "&lt;märke&gt; efter årsmodell, bränsle,
     * motortyp, kaross och växellåda" och "Här kan du köpa &lt;märke&gt;" — vars filterlänkar bär
     * exakt samma adressform. Nattjobbet lade då in <b>3551</b> nya "bilar" på en natt:
     * {@code Volvo Ystad}, {@code XPENG 2024}, {@code Saab Bensin/Etanol}, {@code Zeekr Kombi}.
     * Skadan är inte kosmetisk — arbetslistan för bagagevolymerna är {@code namnUtanVolym}, så
     * skräpet la sig först i bokstavsordningen och åt hela nattbudgeten: samma natt gav
     * {@code bagagevolymer: 0, generationsår: 0}.
     *
     * <p>Id:t är sajtens eget ankare (rubriken länkas som {@code #alla-modeller}) och överlever
     * därför klassbyten, till skillnad från Tailwind-klasserna som ligger runt länkarna.
     */
    private static final String MODEL_LIST_ID = "alla-modeller";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int DELAY_MS = 1500;
    /**
     * Taket för hur många nya bilnamn EN körning får lägga till.
     *
     * <p>Mätt utfall de nätter synken fungerat: 2–20 nya namn. Natten till 2026-09-17 ville den
     * lägga till 3551. Ett sådant hopp är alltid en markupändring, aldrig en marknad — och
     * eftersom raderna skrivs till en tabell utan raderingsväg är kostnaden asymmetrisk. Taket
     * fäller därför hela körningen <b>innan</b> {@code saveAll}, så att nästa parserhaveri blir
     * FEL i scrape-status i stället för 3551 rader att städa bort för hand.
     */
    static final int MAX_NYA_PER_KORNING = 400;

    private final CargoSpecRepository repo;

    public CargoSpecSyncService(CargoSpecRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public int syncCarNames() {
        log.info("CargoSpec sync: starting from Bilweb");

        Set<String> existing = repo.findAllCarNames().stream()
                .map(CargoSpecSyncService::normalize)
                .collect(Collectors.toSet());

        Map<String, String> makes = fetchMakes();
        // Kastar i stället för att returnera 0: en tom märkeslista är alltid ett haveri, aldrig
        // ett giltigt utfall. Den gamla fail-soften lät jobbet rapportera OK medan det inte
        // gjorde någonting, och eftersom cargo-täckningen står på 100 % kunde inget annat mått
        // larma — bara körtiden (571 s -> 3 s) skvallrade. Nu blir det FEL i scrape-status.
        if (makes.isEmpty()) {
            throw new IllegalStateException(
                    "Bilweb gav noll märken från " + BILWEB_MAKES_URL + " — sidan är borta eller omgjord");
        }
        log.info("CargoSpec sync: {} makes found on Bilweb", makes.size());

        int added = 0, utanModellista = 0;
        List<CargoSpec> toSave = new ArrayList<>();

        for (Map.Entry<String, String> entry : makes.entrySet()) {
            String displayName = entry.getKey();
            String slug = entry.getValue();
            try {
                paus();
                List<String> models = fetchModels(slug);
                // null = modellistans ruta saknas på sidan. Det är ett markupfel, inte ett märke
                // utan modeller, och de två får aldrig bli samma sak: läser man i stället hela
                // sidan blir filterlänkarna bilar (se MODEL_LIST_ID).
                if (models == null) {
                    utanModellista++;
                    log.warn("CargoSpec sync: {} ({}) saknar #{} — hoppar över märket",
                            displayName, slug, MODEL_LIST_ID);
                    continue;
                }
                for (String model : models) {
                    String carName = displayName + " " + model;
                    if (!existing.contains(normalize(carName))) {
                        toSave.add(new CargoSpec(carName, null, null));
                        existing.add(normalize(carName));
                        log.info("CargoSpec: adding '{}'", carName);
                        added++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("CargoSpec sync: interrupted after {} additions", added);
                break;
            } catch (Exception e) {
                log.warn("CargoSpec sync: skipping {} ({}): {}", displayName, slug, e.getMessage());
            }
            // Utanför try: spärren får inte kunna fångas av märkets egen catch och rapporteras
            // som "skipping Volvo". Den kostar en märkessidas överskott i precision och är värd
            // det — kontrollen är en säkring, inte en gräns synken ska gå i.
            if (added > MAX_NYA_PER_KORNING) {
                throw new IllegalStateException("Bilweb gav " + added + " nya bilnamn (senast under "
                        + displayName + ") — taket är " + MAX_NYA_PER_KORNING
                        + ", alltså har markupen ändrats. Ingenting skrevs.");
            }
        }

        // Alla märken utan modellista = sajten har gjorts om, inte 170 tomma märken. Samma skäl
        // som makes.isEmpty() kastar: ett jobb som rapporterar OK utan att göra något går inte
        // att larma på, för ingen räknare rör sig.
        if (utanModellista == makes.size()) {
            throw new IllegalStateException("Inget av " + makes.size() + " märken hade #"
                    + MODEL_LIST_ID + " — modellistans markup är ändrad");
        }
        if (utanModellista > 0) {
            log.warn("CargoSpec sync: {} av {} märken saknade modellista", utanModellista, makes.size());
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);
        log.info("CargoSpec sync complete — {} new cars added", added);
        return added;
    }

    /** Hövlighetspausen mellan märkessidorna. Egen metod för att testet ska slippa den. */
    void paus() throws InterruptedException {
        Thread.sleep(DELAY_MS);
    }

    Map<String, String> fetchMakes() {
        try {
            Document doc = Jsoup.connect(BILWEB_MAKES_URL)
                    .userAgent(UA)
                    .timeout(20_000)
                    .get();
            return parseMakes(doc);
        } catch (Exception e) {
            log.error("CargoSpec sync: fetchMakes failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Märkesnamnet står i länkens första span, annonsantalet i nästa — {@code link.text()} ger
     * alltså "Alfa Romeo 51" och duger inte. Sluggen är sista ledet i /sok/&lt;slug&gt;.
     */
    static Map<String, String> parseMakes(Document doc) {
        Map<String, String> makes = new LinkedHashMap<>();
        for (Element link : doc.select(MAKE_LINK_SELECTOR)) {
            String href = link.attr("href").trim();
            Element namn = link.selectFirst("span");
            if (namn == null || href.isBlank()) continue;
            String slug = href.substring(href.lastIndexOf('/') + 1).trim();
            String name = namn.text().trim();
            if (!slug.isBlank() && !name.isBlank()) {
                makes.put(name, slug);
            }
        }
        return makes;
    }

    List<String> fetchModels(String makeSlug) throws Exception {
        Document doc = Jsoup.connect(BILWEB_MODELS_URL + makeSlug)
                .userAgent(UA)
                .timeout(20_000)
                .get();
        return parseModels(doc, makeSlug);
    }

    /**
     * Modellnamnen på en märkessida — <b>bara</b> de som står i modellistans ruta.
     *
     * @return modellnamnen, eller {@code null} när rutan saknas på sidan. Null och tom lista är
     *         medvetet olika svar: tom betyder "märket har inga modeller", null betyder
     *         "sidan ser inte ut som vi tror", och bara det senare får stoppa märket.
     */
    static List<String> parseModels(Document doc, String makeSlug) {
        Element modellista = doc.getElementById(MODEL_LIST_ID);
        if (modellista == null) return null;
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : modellista.select("a[href*='/sok/" + makeSlug + "/']")) {
            String raw = link.text().trim();
            // Samma modell-href förekommer flera gånger på sidan: som chip med modellnamnet
            // ("A3") och i toplistan som "Visa 143 annonser →". Den andra är inget modellnamn —
            // utan det här hade tabellen fått bilar som "Audi Visa 143 annonser". Båda ligger
            // numera utanför modellistan, men filtret står kvar: det kostar ingenting och
            // nästa ommöblering kan lika gärna flytta in dem.
            if (raw.toLowerCase().contains("annons")) continue;
            // Strip listing count "(2 093)" or "(2093)" from end
            String model = raw.replaceAll("\\s*\\([\\d\\s ]+\\)\\s*$", "").trim();
            if (!model.isBlank() && model.length() > 1) {
                seen.add(model);
            }
        }
        return new ArrayList<>(seen);
    }

    static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
