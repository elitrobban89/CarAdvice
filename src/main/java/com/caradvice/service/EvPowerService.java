package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Systemeffekten (hk) per {@code ev_spec}-rad, hämtad av nattsynken.
 *
 * <p><b>Varför tabellen finns.</b> Elbilskortets hästkraftstal var AI:ns gissning för i praktiken
 * varje bil: {@code EvSpecService.SYSTEM_POWER_HK} innehöll <b>två</b> handskrivna poster mot
 * {@code ev_spec}:s 553 rader. Att gissningen inte gick att lita på syns i data — samma två kort
 * fick <b>150 hk</b> i en körning 2026-08-14 och <b>95 hk</b> dagen innan, medan de riktiga
 * siffrorna är 136 (e-Golf) och 143 (MG ZS EV). Ett tal som varierar mellan körningar är inte en
 * uppgift, det är brus, och det stod bredvid verifierad räckvidd och batteristorlek som om det
 * hörde till samma familj.
 *
 * <p><b>Källan kostar ingenting extra.</b> ev-database.org skriver
 * {@code <td>Total Power</td><td>105 kW (143 PS)</td>} på varje bilsida — exakt den sida
 * {@code EvDatabaseScraperService} redan hämtar för räckvidd, batteri, laddeffekt och bagage.
 * Fältet läses i samma svep; inga nya anrop, ingen ny kadens.
 *
 * <p><b>Egen tabell och inte en kolumn på {@code ev_spec}:</b> den är JPA-mappad mot
 * {@code ddl-auto=validate}, så ett nytt entitetsfält fäller uppstarten innan någon migrering
 * hinner köra. Samma skäl och samma mönster som {@code ice_generation}, {@code car_video_sentiment}
 * och {@code insight_upcoming}.
 *
 * <p>Nyckeln är radens {@code car_name} — alltså samma sträng synken redan matchat fram — så
 * uppslaget från en biltitel kan gå via {@code EvSpecService}:s vanliga titelmatchning och ärver
 * dess årsmodellfilter. Det är också vad som gör svaret generationsrätt utan egen årslogik: en
 * 2019 års MG ZS EV matchar raden {@code MG ZS EV 44.5 kWh} och får 143 hk, medan en 2023:a
 * matchar {@code MG ZS EV} och får 156.
 */
@Service
public class EvPowerService {

    private static final Logger log = LoggerFactory.getLogger(EvPowerService.class);

    /** Orimliga tal skrivs inte: en elbil ligger mellan en mopedbils och en Rimacs effekt. */
    static final int MIN_HK = 40;
    static final int MAX_HK = 2000;

    private final JdbcTemplate jdbc;

    /** Hela tabellen i minnet — liten (≤ antalet ev_spec-rader) och läses vid varje kortrendering. */
    private volatile Map<String, Integer> cache = null;

    public EvPowerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ev_power (
                car_name VARCHAR(160) PRIMARY KEY,
                hk INT NOT NULL
            )
            """);
    }

    /**
     * Effekten för en {@code ev_spec}-rad, eller null när den inte hämtats än.
     *
     * <p>Null betyder "ingen åsikt": anroparen faller tillbaka på den handskrivna listan och
     * därefter på AI:ns värde. Tabellen fylls över natten och ett kort får inte tappa sitt
     * hk-fält under tiden.
     */
    public Integer hkFor(String carName) {
        if (carName == null) return null;
        Map<String, Integer> m = cache;
        if (m == null) {
            m = new HashMap<>();
            try {
                for (Map<String, Object> r : jdbc.queryForList("SELECT car_name, hk FROM ev_power")) {
                    m.put(((String) r.get("car_name")).toLowerCase(), ((Number) r.get("hk")).intValue());
                }
            } catch (Exception e) {
                log.warn("ev_power: kunde inte läsas: {}", e.getMessage());
            }
            cache = m;
        }
        return m.get(carName.toLowerCase());
    }

    /**
     * Sparar effekten för en rad. Skriver över befintligt värde — sajten är källan, inte vi.
     *
     * @return true när raden skrevs, false när talet var orimligt och alltså inte togs emot
     */
    public boolean spara(String carName, int hk) {
        if (carName == null || carName.isBlank() || hk < MIN_HK || hk > MAX_HK) return false;
        jdbc.update("DELETE FROM ev_power WHERE car_name = ?", carName);
        jdbc.update("INSERT INTO ev_power(car_name, hk) VALUES (?, ?)", carName, hk);
        cache = null;
        return true;
    }

    public long antal() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ev_power", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Tömmer tabellen så att nattsynken fyller om den — samma skäl som för {@code ice_generation}. */
    public int rensa() {
        int n = jdbc.update("DELETE FROM ev_power");
        cache = null;
        return n;
    }
}
