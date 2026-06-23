package com.caradvice.scraper;

import com.caradvice.model.CargoSpec;
import com.caradvice.repository.CargoSpecRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
    private static final String BILWEB_MAKES_URL = "https://www.bilweb.se/sok/bilar";
    private static final String BILWEB_MODELS_URL = "https://www.bilweb.se/sok/bilar/";
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
        if (makes.isEmpty()) {
            log.error("CargoSpec sync: failed to fetch makes — Bilweb page structure may have changed");
            return 0;
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

            Map<String, String> makes = new LinkedHashMap<>();
            for (Element option : doc.select("select option[value]")) {
                String slug = option.attr("value").trim();
                String name = option.text().trim();
                if (!slug.isBlank() && !name.isBlank()) {
                    makes.put(name, slug);
                }
            }
            return makes;
        } catch (Exception e) {
            log.error("CargoSpec sync: fetchMakes failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<String> fetchModels(String makeSlug) throws Exception {
        Document doc = Jsoup.connect(BILWEB_MODELS_URL + makeSlug)
                .userAgent(UA)
                .timeout(20_000)
                .get();

        Set<String> seen = new LinkedHashSet<>();
        Elements links = doc.select("a[href*='/sok/" + makeSlug + "/']");
        for (Element link : links) {
            String raw = link.text().trim();
            // Strip listing count "(2 093)" or "(2093)" from end
            String model = raw.replaceAll("\\s*\\([\\d\\s ]+\\)\\s*$", "").trim();
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
