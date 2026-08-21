package com.caradvice.scraper;

import com.caradvice.model.EvSpec;
import com.caradvice.repository.EvSpecRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvDatabaseScraperService {

    private static final Logger log = LoggerFactory.getLogger(EvDatabaseScraperService.class);
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final String BASE_URL = "https://ev-database.org";
    private static final String CHEATSHEET_URL = BASE_URL + "/cheatsheet/range-electric-car";
    private static final int REQUEST_DELAY_MS = 1000;
    private static final double EUR_TO_SEK = 11.5;

    /**
     * Märken som inte ska in i databasen. ev-database.org listar hela världsmarknaden, men
     * bilar utan svensk återförsäljare, verkstadsnät eller andrahandsmarknad är inga vettiga
     * förslag till en svensk köpare — och de rankar högt på räckvidd per krona, så de trängde
     * sig in bland "Utmärkt prisvärdhet" utan att gå att köpa här.
     *
     * Spärren sitter i synken och inte bara som en radering: utan den skulle nattkörningen
     * auto-skapa raderna igen vid nästa 02:00.
     */
    public static final Set<String> EXCLUDED_BRANDS = Set.of("TOGG", "Jaecoo", "Changan");

    /** Namnet börjar med ett uteslutet märke (skiftlägesokänsligt). */
    public static boolean isExcludedBrand(String carName) {
        if (carName == null) return false;
        String n = carName.trim();
        return EXCLUDED_BRANDS.stream().anyMatch(b -> n.regionMatches(true, 0, b, 0, b.length()));
    }

    /**
     * ev-databases egna beteckningar på drivlinor vi redan har under tillverkarens namn.
     *
     * <p>Volvo EX30 ligger hos oss som Single Motor, Single Motor Extended Range, Twin Motor
     * Performance och Cross Country. ev-database kallar samma bilar P3, P5 och P8 AWD — och
     * anger dessutom NETTOkapacitet där vi har brutto (49 mot 51 kWh, 65 mot 69). Raderna är
     * alltså inte fler varianter utan samma varianter en gång till, med ett annat tal i
     * kWh-kolumnen. Så länge motoralternativen slog ihop närliggande batterier syntes det
     * inte; när varje variant fick en egen rad blev EX30 nio rader för fyra bilar.
     *
     * <p>Spärren sitter i synken av samma skäl som {@link #EXCLUDED_BRANDS}: en radering
     * ensam hade återskapats vid nästa körning 02:00.
     */
    private static final Pattern ALIAS_NAME = Pattern.compile("(?i)^volvo\\s+ex30\\s+p\\d\\b.*");

    /** Raden är ev-databases alias för en bil vi redan har under tillverkarens namn. */
    public static boolean isAliasName(String carName) {
        return carName != null && ALIAS_NAME.matcher(carName.trim()).matches();
    }

    /**
     * Aliasets beteckning → namnet på DEN rad hos oss vars effekt sidan bär.
     *
     * <p><b>Varför tabellen behövs.</b> {@link #ALIAS_NAME} hoppar över hela sidan, och därmed
     * också systemeffekten — som bara står där. Uppmätt 2026-08-21 mot alla 645 cheatsheet-sidor:
     * EX30:s tre huvudvarianter är de enda rader i hela {@code ev_spec} som spärren gör
     * <b>permanent</b> effektlösa. Alla andra tomma rader har en förklaring som ligger hos källan
     * (bilen finns inte längre på sajten) eller i namngivningen. Det här hålet ligger hos oss.
     *
     * <p><b>Spärren är fortfarande rätt för RADEN.</b> Alias-sidan bär nettokapacitet där vi har
     * brutto (49 mot 51 kWh, 65 mot 69) och skulle skapa en andra rad för samma bil. Effekten
     * däremot är samma tal oavsett vad kWh-kolumnen säger — så uppslaget skriver
     * {@code ev_power} och rör inte {@code ev_spec}. Det är hela skillnaden mot att lucka upp
     * {@code ALIAS_NAME}.
     *
     * <p><b>P3 saknas med flit.</b> ev-database listar {@code EX30 P3} och {@code P3 Long Range}
     * på 110 kW (150 hk) — en MY27-instegsvariant som vi inte har någon rad för. Ett uppslag utan
     * mottagare skulle antingen skrivas till fel rad eller till ingen; alltså står den inte här.
     * {@code Cross Country} står inte heller här: den fångas inte av {@code ALIAS_NAME}, går den
     * vanliga vägen och har redan effekt.
     *
     * <p>Effektsiffrorna är avlästa på sidorna 2026-08-21: P5 = 200 kW (272 PS), P8 AWD = 315 kW
     * (428 PS). De skrivs inte in här — <b>sajten är källan</b>, precis som för alla andra rader;
     * tabellen säger bara VILKEN rad talet hör till.
     */
    private static final Map<String, String> ALIAS_EFFEKTRAD = Map.of(
            "volvo ex30 p5",            "Volvo EX30 Single Motor",
            "volvo ex30 p5 long range", "Volvo EX30 Single Motor Extended Range",
            "volvo ex30 p8 awd",        "Volvo EX30 Twin Motor Performance");

    /** Vår rads namn för ett alias vars effekt vi ändå vill ha, annars null. */
    static String effektradForAlias(String scrapedName) {
        return scrapedName == null ? null : ALIAS_EFFEKTRAD.get(normalize(scrapedName));
    }

    private final EvSpecRepository repo;
    private final com.caradvice.service.CargoSpecService cargoSpecService;
    private final com.caradvice.service.EvPowerService evPowerService;

    public EvDatabaseScraperService(EvSpecRepository repo,
                                    com.caradvice.service.CargoSpecService cargoSpecService,
                                    com.caradvice.service.EvPowerService evPowerService) {
        this.repo = repo;
        this.cargoSpecService = cargoSpecService;
        this.evPowerService = evPowerService;
    }

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

        if (carUrls.isEmpty()) {
            log.error("SCRAPER ALERT: Cheatsheet returned 0 car URLs — ev-database.org may have changed its HTML structure. Manual inspection needed.");
            return 0;
        }

        int updated = 0;
        int created = 0;
        int failed = 0;
        int collisions = 0;
        int cargoFilled = 0;   // bagagevolymer som fylldes i cargo_spec pa kopet
        int aliasEffekter = 0; // effekter hamtade ur en aliassida vars rad vi INTE uppdaterar
        Map<String, String> claims = new LinkedHashMap<>();   // DB-rad → första skrapade bilen, se claimRow
        for (String path : carUrls) {
            int hour = ZonedDateTime.now(STOCKHOLM).getHour();
            if (hour >= 8 && hour < 17) {
                log.warn("SCRAPER: Aborting at {}:xx Stockholm time — outside allowed window (02:00–07:00). updated={} created={} processed so far.", hour, updated, created);
                break;
            }
            try {
                Thread.sleep(REQUEST_DELAY_MS);
                ScrapedSpec scraped = scrapeCarPage(BASE_URL + path);
                if (scraped == null || scraped.name().isBlank()) { failed++; continue; }
                if (isExcludedBrand(scraped.name())) continue;   // inte failed — medvetet bortvald
                if (isAliasName(scraped.name())) {
                    // Raden rörs inte (nettokapacitet, dubbletter) — men EFFEKTEN på sidan är
                    // samma tal oavsett kWh-kolumnen, och för EX30 finns den ingen annanstans.
                    // Se ALIAS_EFFEKTRAD för varför bara vissa alias har en mottagare.
                    aliasEffekter += sparaAliasEffekt(scraped, nameMap) ? 1 : 0;
                    continue;                                    // samma bil, ev-databases namn
                }

                // Bagagevolymen ligger på samma sida — fyll den medan vi ändå är här. Egen
                // try/catch: cargo_spec är en annan tabell och ett fel där får inte sänka
                // EV-synken, som är sidans egentliga uppdrag.
                try {
                    if (scraped.cargoLiters() > 0
                            && cargoSpecService.fillFromScrape(scraped.name(),
                                    scraped.cargoLiters(), scraped.cargoMaxLiters())) {
                        cargoFilled++;
                        log.info("Bagagevolym ifylld från {}: {} l ({} l nedfällt)",
                                scraped.name(), scraped.cargoLiters(), scraped.cargoMaxLiters());
                    }
                } catch (Exception e) {
                    log.warn("Bagagevolym för {} kunde inte skrivas: {}", scraped.name(), e.getMessage());
                }

                EvSpec match = findMatch(scraped.name(), scraped.rangeKm(), nameMap);
                if (match == null) {
                    if (scraped.rangeKm() > 0 && scraped.batteryKwh() > 0) {
                        EvSpec newSpec = new EvSpec(
                                scraped.name(),
                                scraped.acKw() > 0 ? (double) scraped.acKw() : 11.0,
                                scraped.dcKw() > 0 ? (double) scraped.dcKw() : 0.0,
                                scraped.batteryKwh(),
                                scraped.rangeKm(),
                                scraped.priceKr()
                        );
                        try {
                            repo.save(newSpec);
                        } catch (DataIntegrityViolationException e) {
                            // ux_ev_spec_car_name slog till: raden finns redan trots att findMatch
                            // sa nej. Exakt så uppstod de 756 dubbletterna som städades bort
                            // 2026-07-28 — då tyst, nu högljutt. Hoppa över bilen och kör vidare;
                            // ett trasigt namn ska inte sänka resten av nattens synk.
                            log.error("SCRAPER ALERT: '{}' finns redan i ev_spec men findMatch hittade den inte — "
                                    + "matchningen behöver ses över. Bilen hoppas över.", scraped.name());
                            failed++;
                            continue;
                        }
                        nameMap.put(normalize(scraped.name()), newSpec);
                        sparaEffekt(newSpec.getCarName(), scraped);
                        created++;
                        log.info("Created {}: range={}km bat={}kWh DC={}kW AC={}kW price={}kr",
                                scraped.name(), scraped.rangeKm(), scraped.batteryKwh(),
                                scraped.dcKw(), scraped.acKw(), scraped.priceKr());
                    } else {
                        log.debug("No DB match and incomplete data for: {}", scraped.name());
                    }
                    continue;
                }

                String firstClaimant = claimRow(claims, normalize(match.getCarName()), scraped.name());
                if (firstClaimant != null) {
                    log.error("SCRAPER ALERT: '{}' matchar DB-raden '{}' som redan tagits av '{}' i samma "
                            + "körning — troligen olika varianter eller generationer som delar namnord. "
                            + "Bilen hoppas över; raden behöver antagligen en egen post.",
                            scraped.name(), match.getCarName(), firstClaimant);
                    collisions++;
                    continue;
                }

                // Effekten skrivs EFTER kollisionsspärren, aldrig före: en bil som blockerats
                // hade annars fått skriva sin hk till en rad den inte hör till — precis det
                // spärren finns för att hindra på räckvidd och batteri.
                sparaEffekt(match.getCarName(), scraped);

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
                if (scraped.priceKr() > 0 && (match.getPriceKr() == null || match.getPriceKr() == 0)) {
                    match.setPriceKr(scraped.priceKr());
                    changed = true;
                }

                if (changed) {
                    repo.save(match);
                    updated++;
                    log.info("Updated {}: range={}km bat={}kWh DC={}kW AC={}kW price={}kr",
                            match.getCarName(), scraped.rangeKm(), scraped.batteryKwh(),
                            scraped.dcKw(), scraped.acKw(), scraped.priceKr());
                } else if (scraped.priceKr() == 0) {
                    log.debug("No price found on page for: {}", scraped.name());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to scrape {}: {}", path, e.getMessage());
            }
        }

        int total = carUrls.size();
        log.info("Sync complete — updated={} created={} failed={} collisions={} bagagevolymer={} aliaseffekter={} total={}",
                updated, created, failed, collisions, cargoFilled, aliasEffekter, total);
        // Noll aliaseffekter betyder att uppslaget inte längre hittar sina sidor — beteckningarna
        // har bytt namn hos källan. Utan larmet står EX30 tyst utan effekt igen, och ingen siffra
        // rör sig som avslöjar det: samma fälla som cargo-coverage 602/602/0 satt i.
        if (aliasEffekter == 0 && !ALIAS_EFFEKTRAD.isEmpty()) {
            log.warn("SCRAPER ALERT: inget alias gav en effekt den här körningen — ALIAS_EFFEKTRAD "
                    + "({} uppslag) matchar inga sidnamn längre. EX30 blir utan systemeffekt.",
                    ALIAS_EFFEKTRAD.size());
        }
        if (collisions > 0) {
            log.warn("Scraper: {} bilar hoppades över för att de pekade på en redan tagen DB-rad — "
                    + "se SCRAPER ALERT-raderna ovan", collisions);
        }
        if (failed > total / 2) {
            log.error("SCRAPER ALERT: {}/{} pages failed — ev-database.org may have changed its HTML structure. Manual inspection needed.", failed, total);
        } else if (failed > 0) {
            log.warn("Scraper: {}/{} pages could not be parsed", failed, total);
        }
        return updated + created;
    }

    /**
     * Skriver systemeffekten till {@code ev_power}. Egen try/catch av samma skäl som
     * bagageifyllningen: effekten är en bonus på sidan vi ändå hämtar, och ett fel där får inte
     * sänka EV-synken, som är körningens egentliga uppdrag.
     */
    private boolean sparaEffekt(String carName, ScrapedSpec scraped) {
        if (scraped.hk() <= 0) return false;
        try {
            if (evPowerService.spara(carName, scraped.hk())) {
                log.debug("Systemeffekt för {}: {} hk", carName, scraped.hk());
                return true;
            }
        } catch (Exception e) {
            log.warn("Systemeffekt för {} kunde inte skrivas: {}", carName, e.getMessage());
        }
        return false;
    }

    /**
     * Effekten ur en aliassida, skriven till den rad {@link #ALIAS_EFFEKTRAD} pekar ut.
     *
     * <p>Uppslaget går via {@code nameMap} och inte rakt på namnet i tabellen: nyckeln i
     * {@code ev_power} måste vara radens EGEN stavning (det är den {@code EvSpecService} slår
     * upp med), och saknas raden ska ingenting skrivas alls hellre än en föräldralös nyckel.
     * Byter vi namn på raden tystnar effekten alltså i stället för att hamna fel.
     *
     * @return true när ett tal faktiskt skrevs — talet räknas för att en tom körning ska larma
     */
    private boolean sparaAliasEffekt(ScrapedSpec scraped, Map<String, EvSpec> nameMap) {
        String radnamn = effektradForAlias(scraped.name());
        if (radnamn == null || scraped.hk() <= 0) return false;
        EvSpec rad = nameMap.get(normalize(radnamn));
        if (rad == null) {
            log.warn("SCRAPER ALERT: aliaset '{}' pekar på raden '{}' som inte finns i ev_spec — "
                    + "effekten {} hk skrevs inte. ALIAS_EFFEKTRAD behöver ses över.",
                    scraped.name(), radnamn, scraped.hk());
            return false;
        }
        log.info("Systemeffekt ur aliassidan {}: {} hk → {}", scraped.name(), scraped.hk(), rad.getCarName());
        // Räknas först när talet FAKTISKT skrevs: ett orimligt hk avvisas tyst av spara(), och
        // en räknare som ökade ändå hade gjort larmet nedan blint för precis det fallet.
        return sparaEffekt(rad.getCarName(), scraped);
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
                    extractAc(text),
                    extractPrice(text),
                    extractCargoCell(doc, "Cargo Volume"),
                    extractCargoCell(doc, "Cargo Volume Max"),
                    parseSystemPowerHk(doc)
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

    /**
     * Systemeffekten i hästkrafter ur bilsidans specruta, eller 0 när raden saknas.
     *
     * <p>Raden ser ut som {@code <td>Total Power</td><td>105 kW (143 PS)</td>}. <b>PS-talet i
     * parentesen tas i första hand</b> — PS är metriska hästkrafter, alltså samma enhet som
     * svenska "hk", och att läsa den siffran direkt slipper ett avrundningssteg. Saknas
     * parentesen räknas kW om (1 kW = 1,3596 hk).
     *
     * <p>Etiketten matchas på hela cellens text och inte som delsträng i sidans brödtext: sidan
     * bär även "Total Torque" och laddeffekter i kW, och en girig regex över brödtexten hade
     * plockat vilken som helst av dem. Det är samma slags fälla som imperialkolumnen i
     * bagagecellen hos auto-data.
     */
    static int parseSystemPowerHk(Document doc) {
        if (doc == null) return 0;
        for (Element td : doc.select("td")) {
            if (!"total power".equalsIgnoreCase(td.text().trim())) continue;

            Element varde = td.nextElementSibling();
            if (varde == null) continue;
            String text = varde.text();

            Matcher ps = Pattern.compile("\\((\\d{2,4})\\s*(?:PS|hp|Hp|HP)\\)").matcher(text);
            if (ps.find()) return Integer.parseInt(ps.group(1));

            Matcher kw = Pattern.compile("(\\d{2,4})\\s*kW", Pattern.CASE_INSENSITIVE).matcher(text);
            if (kw.find()) return (int) Math.round(Integer.parseInt(kw.group(1)) * 1.3596);
        }
        return 0;
    }

    private int extractPrice(String text) {
        // Matches: €35,000 / € 35.000 / 35,000€ / EUR 35 000 / from €35,900
        Matcher m = Pattern.compile(
            "(?:€|EUR)[\\s]*(\\d{2,3}[.,\\s]\\d{3})|" +
            "(\\d{2,3}[.,\\s]\\d{3})[\\s]*(?:€|EUR)",
            Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            int result = eurToSek(raw);
            if (result > 0) return result;
        }
        return 0;
    }

    private int eurToSek(String eurStr) {
        try {
            String digits = eurStr.replaceAll("[^\\d]", "");
            double eur = Double.parseDouble(digits);
            if (eur < 10_000 || eur > 300_000) return 0;
            return (int) Math.round(eur * EUR_TO_SEK / 1000) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Minsta antal ord i det skrapade namnet för att steg 3 ska få leta. Se motiveringen där. */
    static final int MIN_SCRAPED_WORDS_FOR_REVERSE = 3;
    /**
     * Kortaste DB-ord som får träffa som delsträng i steg 2. Kortare ord måste stå som eget ord.
     *
     * <p>Delsträngsfallbacken finns för att normaliseringen delar upp namn olika beroende på
     * skiljetecken — "ID.3" blir "id 3" men "ID3" blir "id3". För långa ord är det ofarligt.
     * För ett- och tvåteckensord är det förödande, eftersom de ryms i nästan vilket annat ord
     * som helst. Uppmätt mot skarp data 2026-08-11 (518 DB-rader mot 639 bilar på cheatsheeten)
     * skrev fallbacken systematiskt fel bil till fel rad:
     *
     * <ul>
     *   <li>{@code Hyundai IONIQ 3 61 kWh} träffade raden <b>Hyundai IONIQ 6</b> — "6" ryms i "61"</li>
     *   <li>{@code Renault 4 E-Tech 52kWh} träffade <b>Renault 5 E-Tech 52kWh</b> — "5" ryms i "52"</li>
     *   <li>{@code Volkswagen ID. Polo 155 kW} träffade <b>Volkswagen ID.5</b> — "5" ryms i "155"</li>
     *   <li>{@code Mercedes-Benz GLC 400 4MATIC} träffade <b>Mercedes-Benz C 400 4MATIC</b> — "c" i "glc"</li>
     *   <li>{@code Audi RS e-tron GT} träffade <b>Audi S e-tron GT</b> — "s" i "rs"</li>
     *   <li>{@code Škoda Enyaq 85x} träffade <b>Škoda Enyaq 85</b> — "85" i "85x", och x är fyrhjulsdriften</li>
     * </ul>
     *
     * <p>Gränsen 3 tar samtliga fall ovan. 4 tecken provades också och kostade fyra äkta
     * steg 2-träffar utan att stoppa något nytt, så 3 är den lösaste gräns som räcker.
     *
     * <p>Bonuseffekt: de åtta Tesla Model 3/Y-varianter som fastnade i oavgjort-spärren gjorde
     * det för att BÅDA de lika långa DB-namnen träffade via delsträng. Utan skenträffarna är
     * de inte längre tvetydiga och matchar rätt rad.
     */
    static final int MIN_DB_WORD_FOR_SUBSTRING = 3;
    /** Hur mycket DB-radens räckvidd får avvika från den skrapade för att räknas som samma bil. */
    static final double REVERSE_RANGE_TOLERANCE = 0.10;

    /**
     * Delsträngsfallbacken i steg 2, men bara i de två former den faktiskt finns för.
     *
     * <p>Fri delsträngsmatchning ({@code normScraped.contains(w)}) tog längdgränsen som enda
     * skydd, och den räcker inte för ord som är riktiga ord: uppmätt 2026-08-14 skrev
     * <b>{@code BYD SEALION 7}</b> till raden <b>{@code BYD Seal}</b> — "seal" ryms i "sealion",
     * fyra tecken, alltså långt över gränsen. En sjöhäst är inte en säl, och raden fick en annan
     * bils räckvidd och batteri.
     *
     * <p><b>Villkoret är att hela det skrapade ordet går att göra reda för.</b> Fallbacken finns
     * för särskrivning — "ID.3" blir "id 3" medan "ID3" blir "id3", och "Long Range" skrivs ibland
     * "LongRange" — alltså för ord som är flera DB-ord skrivna ihop. Då är också det rimliga
     * kravet att ordet går att dela upp i just DB-ord: "longrange" = "long" + "range", båda ur
     * DB-namnet självt. "sealion" går inte att dela så ("seal" + "ion", och "ion" står ingenstans),
     * och det är skillnaden mellan en särskrivning och två olika bilar.
     *
     * <p>Ett undantag utöver det: <b>ordet med ett e före</b> — "c40" i "ec40". Volvo döpte om
     * C40 till EC40, och utan undantaget hade synken skapat en andra rad för samma bil.
     * Dubbletter under två namn är precis det som städades bort 2026-07-28 (756 rader). Samma
     * elprefix som {@code EvSpecService} redan hanterar för e-Golf och e-Rifter.
     *
     * <p>Mätt mot skarp data (553 DB-rader mot 645 bilar på cheatsheeten): <b>627 träffar
     * oförändrade, 3 borta, 0 som byter rad</b> — och de tre är samtliga SEALION-raderna ovan.
     * Att de nu blir utan träff är rätt utfall: synken skapar då en egen rad för Sealion, alltså
     * ett synligt tillskott i stället för en tyst överskrivning.
     */
    static boolean delstrangTraff(Set<String> scrapedWords, String dbWord, String[] dbWords) {
        for (String s : scrapedWords) {
            if (s.equals("e" + dbWord)) return true;
            if (s.contains(dbWord) && garAttDelaIDbOrd(s, dbWords)) return true;
        }
        return false;
    }

    /** Sant när ordet är en följd av DB-namnets egna ord skrivna ihop ("longrange"). */
    private static boolean garAttDelaIDbOrd(String ord, String[] dbWords) {
        if (ord.isEmpty()) return true;
        for (String w : dbWords) {
            if (!w.isEmpty() && ord.startsWith(w) && garAttDelaIDbOrd(ord.substring(w.length()), dbWords))
                return true;
        }
        return false;
    }

    EvSpec findMatch(String scrapedName, Map<String, EvSpec> nameMap) {
        return findMatch(scrapedName, 0, nameMap);
    }

    /**
     * scrapedRangeKm > 0 aktiverar steg 3 (omvänd riktning), som behöver räckvidden för att
     * kunna skilja varianter åt.
     */
    EvSpec findMatch(String scrapedName, int scrapedRangeKm, Map<String, EvSpec> nameMap) {
        String normScraped = normalize(scrapedName);

        // 1. Exact normalized match
        if (nameMap.containsKey(normScraped)) return nameMap.get(normScraped);

        // 2. All DB-name words appear in scraped name (longest DB name wins)
        //
        // Samma oavgjort-spärr som steg 3: står två lika långa DB-namn kvar på slutet finns det
        // ingen grund att välja mellan dem, och utan spärren avgjorde nameMap:ens iterationsordning
        // vilken rad som fick den skrapade bilens siffror — tyst.
        EvSpec best = null;
        int bestLen = 0;
        boolean ambiguous = false;
        for (Map.Entry<String, EvSpec> entry : nameMap.entrySet()) {
            String[] dbWords = entry.getKey().split("\\s+");
            Set<String> scrapedWords = new HashSet<>(Arrays.asList(normScraped.split("\\s+")));
            boolean allMatch = true;
            for (String w : dbWords) {
                // Korta DB-ord måste stå som eget ord — se MIN_DB_WORD_FOR_SUBSTRING för varför.
                boolean hit = scrapedWords.contains(w)
                        || (w.length() >= MIN_DB_WORD_FOR_SUBSTRING
                            && delstrangTraff(scrapedWords, w, dbWords));
                if (!hit) { allMatch = false; break; }
            }
            if (!allMatch) continue;
            if (dbWords.length > bestLen) {
                best = entry.getValue();
                bestLen = dbWords.length;
                ambiguous = false;
            } else if (dbWords.length == bestLen && entry.getValue() != best) {
                ambiguous = true;
            }
        }
        if (ambiguous) return null;
        if (best != null) return best;

        // 3. Omvänt: DB-namnet är MER specifikt än det skrapade.
        //
        // Steg 2 klarar bara att DB-namnet är kortare. Våra egna varianter heter t.ex.
        // "Kia EV6 Long Range 2WD 84 kWh" medan ev-database säger "Kia EV6 Long Range 2WD" —
        // orden "84" och "kwh" saknas i det skrapade namnet, så träffen uteblev varje natt och
        // synken skapade en parallell rad i stället. Samma bil under två namn, med netto- resp.
        // bruttobatteri. Det här steget stänger den luckan.
        //
        // Två spärrar mot felträffar, eftersom det här är den riskabla riktningen:
        // kort skrapat namn ("Kia EV6") skulle annars matcha ett dussin rader, och flera
        // varianter delar samma prefix. Räckvidden avgör vilken — pre-facelift 2WD ligger på
        // 528 km, facelift på 582, så en skrapad 582:a kan bara vara den senare.
        if (scrapedRangeKm <= 0) return null;
        Set<String> scrapedWords = new LinkedHashSet<>(Arrays.asList(normScraped.split("\\s+")));
        if (scrapedWords.size() < MIN_SCRAPED_WORDS_FOR_REVERSE) return null;

        EvSpec closest = null;
        int closestDiff = Integer.MAX_VALUE;
        boolean tie = false;
        for (Map.Entry<String, EvSpec> entry : nameMap.entrySet()) {
            Set<String> dbWords = new LinkedHashSet<>(Arrays.asList(entry.getKey().split("\\s+")));
            if (!dbWords.containsAll(scrapedWords)) continue;      // DB-namnet måste vara supermängden
            Integer dbRange = entry.getValue().getRangeKm();
            if (dbRange == null || dbRange <= 0) continue;
            if (Math.abs(dbRange - scrapedRangeKm) > scrapedRangeKm * REVERSE_RANGE_TOLERANCE) continue;

            int diff = Math.abs(dbRange - scrapedRangeKm);
            if (diff < closestDiff) { closest = entry.getValue(); closestDiff = diff; tie = false; }
            else if (diff == closestDiff) { tie = true; }
        }
        // Lika nära två varianter = ingen aning om vilken. Hellre ingen träff (och en ny rad
        // som syns) än att skriva över fel variants data i tysthet.
        return tie ? null : closest;
    }

    /**
     * Anspråksliggare för en synkkörning: vilken skrapad bil som redan skrivit till en DB-rad.
     *
     * <p>Steg 2 i {@link #findMatch} kan mycket väl vara entydigt varje gång och ändå ge fel
     * resultat, för tvetydigheten ligger mellan anropen och inte inuti dem. Skarpt fall 2026-08-10:
     * ev-database listar sex MG4-poster av andra generationen, och TRE av dem ("Premium Long
     * Range", "Urban Comfort Long Range", "Urban Premium Long Range") pekar var för sig entydigt
     * ut vår enda rad "MG4 Long Range". Den sist processade vann, tyst — vilken bils siffror raden
     * fick berodde på cheatsheetens ordning.
     *
     * <p>Samma hållning som steg 3:s oavgjort-spärr: när flera bilar gör anspråk på samma rad
     * finns det ingen grund att välja mellan dem, så den andra och alla följande hoppas över och
     * loggas högljutt. Den första skrivningen står kvar — den var redan gjord när kollisionen
     * upptäcktes, och att rulla tillbaka mitt i en nattkörning är värre än en synlig varning.
     *
     * @return null när anspråket beviljas, annars namnet på den bil som tog raden först
     */
    static String claimRow(Map<String, String> claims, String dbKey, String scrapedName) {
        String first = claims.putIfAbsent(dbKey, scrapedName);
        return first == null || first.equals(scrapedName) ? null : first;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    record ScrapedSpec(String name, int rangeKm, double batteryKwh, int dcKw, int acKw, int priceKr,
                       int cargoLiters, int cargoMaxLiters, int hk) {}

    /**
     * Bagagevolymen ur specifikationstabellen på bilsidan.
     *
     * <p>Läses ur cellen EFTER etiketten och inte ur sidans brödtext: "Cargo Volume" och
     * "Cargo Volume Max" står som varsin rad i samma tabell, och en textsökning på den första
     * hade lika gärna kunnat plocka den andras siffra. Jämförelsen är exakt (inte "börjar med"),
     * av samma skäl.
     *
     * <p>Anledningen att det görs här: {@code cargo_spec} hade 243 rader den 2026-08-10 medan
     * {@code ev_spec} hade 518 elbilsvarianter — merparten av elbilarna saknar alltså bagagerad
     * helt, och bagagefiltrets vakt kan bara fälla på positivt bevis. Sidorna nedan besöks ändå
     * varje natt, så volymen kostar inget extra anrop.
     */
    static int extractCargoCell(Document doc, String label) {
        for (Element td : doc.select("td")) {
            if (!td.text().trim().equalsIgnoreCase(label)) continue;
            Element value = td.nextElementSibling();
            if (value == null) continue;
            Matcher m = Pattern.compile("(\\d{2,4})\\s*L\\b", Pattern.CASE_INSENSITIVE).matcher(value.text());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}
