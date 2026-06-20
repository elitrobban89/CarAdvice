package com.caradvice.scraper;

import com.caradvice.model.EvSpec;
import com.caradvice.repository.EvSpecRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvDatabaseScraperService {

    private static final Logger log = LoggerFactory.getLogger(EvDatabaseScraperService.class);
    private static final String BASE_URL = "https://ev-database.org";
    private static final String CHEATSHEET_URL = BASE_URL + "/cheatsheet/range-electric-car";
    private static final int REQUEST_DELAY_MS = 1000;

    private final EvSpecRepository repo;

    public EvDatabaseScraperService(EvSpecRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public int syncFromEvDatabase() {
        log.info("Starting EV spec sync from ev-database.org");
        List<EvSpec> existing = repo.findAll();
        if (existing.isEmpty()) {
            log.warn("No existing EvSpec records — seed data first.");
            return 0;
        }

        // Build lookup: normalized name → record
        Map<String, EvSpec> nameMap = new LinkedHashMap<>();
        for (EvSpec s : existing) {
            nameMap.put(normalize(s.getCarName()), s);
        }

        List<String> carUrls = fetchCarUrls();
        log.info("Found {} cars on ev-database.org cheatsheet", carUrls.size());

        int updated = 0;
        int created = 0;
        for (String path : carUrls) {
            try {
                Thread.sleep(REQUEST_DELAY_MS);
                ScrapedSpec scraped = scrapeCarPage(BASE_URL + path);
                if (scraped == null || scraped.name().isBlank()) continue;

                EvSpec match = findMatch(scraped.name(), nameMap);
                if (match == null) {
                    if (scraped.rangeKm() > 0 && scraped.batteryKwh() > 0) {
                        EvSpec newSpec = new EvSpec(
                                scraped.name(),
                                scraped.acKw() > 0 ? (double) scraped.acKw() : 11.0,
                                scraped.dcKw() > 0 ? (double) scraped.dcKw() : 0.0,
                                scraped.batteryKwh(),
                                scraped.rangeKm(),
                                0
                        );
                        repo.save(newSpec);
                        nameMap.put(normalize(scraped.name()), newSpec);
                        created++;
                        log.info("Created {}: range={}km bat={}kWh DC={}kW AC={}kW",
                                scraped.name(), scraped.rangeKm(), scraped.batteryKwh(),
                                scraped.dcKw(), scraped.acKw());
                    } else {
                        log.debug("No DB match and incomplete data for: {}", scraped.name());
                    }
                    continue;
                }

                boolean changed = false;
                if (scraped.rangeKm() > 0 && !Objects.equals(match.getRangeKm(), scraped.rangeKm())) {
                    match.setRangeKm(scraped.rangeKm());
                    changed = true;
                }
                if (scraped.batteryKwh() > 0 && !Objects.equals(match.getBatteryKwh(), scraped.batteryKwh())) {
                    match.setBatteryKwh(scraped.batteryKwh());
                    changed = true;
                }
                if (scraped.dcKw() > 0 && !Objects.equals(match.getMaxDcKw(), (double) scraped.dcKw())) {
                    match.setMaxDcKw((double) scraped.dcKw());
                    changed = true;
                }
                if (scraped.acKw() > 0 && !Objects.equals(match.getMaxAcKw(), (double) scraped.acKw())) {
                    match.setMaxAcKw((double) scraped.acKw());
                    changed = true;
                }

                if (changed) {
                    repo.save(match);
                    updated++;
                    log.info("Updated {}: range={}km bat={}kWh DC={}kW AC={}kW",
                            match.getCarName(), scraped.rangeKm(), scraped.batteryKwh(),
                            scraped.dcKw(), scraped.acKw());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Failed to scrape {}: {}", path, e.getMessage());
            }
        }

        log.info("Sync complete — updated {} records, created {} new records.", updated, created);
        return updated + created;
    }

    private List<String> fetchCarUrls() {
        try {
            Document doc = Jsoup.connect(CHEATSHEET_URL)
                    .userAgent("Mozilla/5.0 (compatible; CarAdviceBot/1.0; +https://caradvice.onrender.com)")
                    .timeout(20_000)
                    .get();
            Elements links = doc.select("a[href*=/car/]");
            List<String> urls = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Element link : links) {
                String href = link.attr("href");
                if (href.matches(".*/car/\\d+/.*") && seen.add(href)) {
                    urls.add(href.startsWith("http") ? href.replace(BASE_URL, "") : href);
                }
            }
            return urls;
        } catch (Exception e) {
            log.error("Failed to fetch cheatsheet: {}", e.getMessage());
            return List.of();
        }
    }

    private ScrapedSpec scrapeCarPage(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; CarAdviceBot/1.0; +https://caradvice.onrender.com)")
                    .timeout(20_000)
                    .get();

            // Car name from h1 or page title
            String name = "";
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) name = h1.text().replaceAll("\\s*\\(MY\\d.*", "").trim();
            if (name.isBlank()) name = doc.title().replaceAll("[|\\-].*", "").trim();

            String text = doc.body().text();

            return new ScrapedSpec(
                    name,
                    extractWltp(text),
                    extractBattery(text),
                    extractDc(text),
                    extractAc(text)
            );
        } catch (Exception e) {
            log.debug("Skipped {}: {}", url, e.getMessage());
            return null;
        }
    }

    private int extractWltp(String text) {
        // "WLTP ... NNN km" within 50 chars
        Matcher m = Pattern.compile("WLTP[^\\d]{0,50}(\\d{3,4})\\s*km", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        // Fallback: any "NNN km" that looks like a range
        m = Pattern.compile("(\\d{3,4})\\s*km\\b").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private double extractBattery(String text) {
        // Prefer explicit "useable" marker
        Matcher m = Pattern.compile("([1-9][\\d.]+)\\s*kWh\\s*(?:useable|usable|net|netto)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Double.parseDouble(m.group(1));
        // Any kWh >= 10 (avoids short PHEV ranges misread as battery)
        m = Pattern.compile("([1-9][\\d.]+)\\s*kWh", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            if (v >= 10) return v;
        }
        return 0.0;
    }

    private int extractDc(String text) {
        // "NNN kW DC" or "NNN kW (max)" or "DC ... NNN kW"
        Matcher m = Pattern.compile("(\\d{2,4})\\s*kW\\s*(?:DC|fast|peak|max)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("(?:DC|fast\\s*charg)[^\\d]{0,20}(\\d{2,4})\\s*kW", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private int extractAc(String text) {
        // "NNN kW AC" or "AC ... NNN kW"
        Matcher m = Pattern.compile("(\\d{1,3})\\s*kW\\s*AC", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("(?:AC|home|destination)[^\\d]{0,20}(\\d{1,3})\\s*kW", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private EvSpec findMatch(String scrapedName, Map<String, EvSpec> nameMap) {
        String normScraped = normalize(scrapedName);

        // 1. Exact normalized match
        if (nameMap.containsKey(normScraped)) return nameMap.get(normScraped);

        // 2. All DB-name words appear in scraped name (longest DB name wins)
        EvSpec best = null;
        int bestLen = 0;
        for (Map.Entry<String, EvSpec> entry : nameMap.entrySet()) {
            String[] dbWords = entry.getKey().split("\\s+");
            Set<String> scrapedWords = new HashSet<>(Arrays.asList(normScraped.split("\\s+")));
            boolean allMatch = true;
            for (String w : dbWords) {
                if (!scrapedWords.contains(w) && !normScraped.contains(w)) { allMatch = false; break; }
            }
            if (allMatch && dbWords.length > bestLen) {
                best = entry.getValue();
                bestLen = dbWords.length;
            }
        }
        return best;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    record ScrapedSpec(String name, int rangeKm, double batteryKwh, int dcKw, int acKw) {}
}
