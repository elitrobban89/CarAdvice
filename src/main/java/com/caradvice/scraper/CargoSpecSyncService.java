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
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int DELAY_MS = 1500;

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

        int added = 0;
        List<CargoSpec> toSave = new ArrayList<>();

        for (Map.Entry<String, String> entry : makes.entrySet()) {
            String displayName = entry.getKey();
            String slug = entry.getValue();
            try {
                Thread.sleep(DELAY_MS);
                List<String> models = fetchModels(slug);
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
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);
        log.info("CargoSpec sync complete — {} new cars added", added);
        return added;
    }

    private Map<String, String> fetchMakes() {
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

    private List<String> fetchModels(String makeSlug) throws Exception {
        Document doc = Jsoup.connect(BILWEB_MODELS_URL + makeSlug)
                .userAgent(UA)
                .timeout(20_000)
                .get();
        return parseModels(doc, makeSlug);
    }

    static List<String> parseModels(Document doc, String makeSlug) {
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a[href*='/sok/" + makeSlug + "/']")) {
            String raw = link.text().trim();
            // Samma modell-href förekommer två gånger på sidan: en gång som chip med modellnamnet
            // ("A3") och en gång i toplistan som "Visa 143 annonser →". Den andra är inget
            // modellnamn — utan det här hade tabellen fått bilar som "Audi Visa 143 annonser".
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
