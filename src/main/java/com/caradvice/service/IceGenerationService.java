package com.caradvice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Första årsmodellen för den generation som {@code ice_consumption}s motorlista beskriver.
 *
 * <p><b>Varför tabellen finns.</b> {@code ice_consumption} bär 957 motorrader utan någon
 * årsmodell, och {@code IceConsumptionService.engineOptionsForTitle} slår upp på märke +
 * modellord efter att ha kastat årtalet. Uppmätt 2026-08-13: <b>202 av 310 namnplåtar</b> visar
 * därför en motorlista oavsett vilken årsmodell kortet gäller.
 *
 * <p><b>Och listan är EN generation, inte en blandning.</b> Det var den mätningen som vände
 * bilden: samtliga tretton Golf-rader i vår CSV är Golf VIII (2020–2024) — {@code 1.0 eTSI
 * MHEV}, {@code 2.0 TDI 115}, {@code GTE}, {@code GTI Clubsport}, {@code R}. Golf VII har noll
 * rader hos oss. Ett kort för "Volkswagen Golf (2018)" fick alltså Golf VIII:s motorer, inte en
 * hopblandad lista. Samma form som elbilarnas klass A.
 *
 * <p><b>Varför bara ett årtal, och inte hela utbudet per generation.</b> auto-datas Golf
 * VII-sida bär 46 varianter: {@code 1.5 TGI} och {@code 1.4 TGI} (fordonsgas, aldrig sålda i
 * Sverige), R i fyra effektsteg, GTI i fyra, 4MOTION-dieslar. Vår CSV har 13 för Golf VIII där
 * auto-data har 21. <b>CSV:n är kurerad för svensk marknad, och det är dess värde.</b> Att fylla
 * den från auto-data hade bytt en kurerad men odaterad lista mot en daterad men okurerad — samma
 * feltyp vi försöker laga, fast åt andra hållet.
 *
 * <p>Därför gör den här tabellen det mindre men ärliga: den säger vilken generation listan
 * gäller, så att ett kort för en äldre årsmodell kan <b>avstå</b> i stället för att påstå.
 * Kortet faller då tillbaka på AI:ns egen motortext, precis som för en modell vi saknar helt.
 *
 * <p>Egen tabell och inte en kolumn på {@code ice_consumption}: den tabellens primärnyckel är
 * {@code (brand, variant)} och samma beteckning återkommer äkta mellan generationer
 * ("2.0 TDI 150 hk" finns i både Golf 7 och 8), så ett årtal där hade spräckt nyckeln. Samma
 * skäl som {@code car_video_sentiment} och {@code insight_upcoming} ligger i egna tabeller.
 */
@Service
public class IceGenerationService {

    private static final Logger log = LoggerFactory.getLogger(IceGenerationService.class);

    private final JdbcTemplate jdbc;

    /** Hela tabellen i minnet — den är liten (≤310 rader) och läses på varje kortrendering. */
    private volatile Map<String, Integer> cache = null;

    public IceGenerationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ice_generation (
                model_name VARCHAR(120) PRIMARY KEY,
                fran_ar INT NOT NULL
            )
            """);
    }

    /**
     * Startåret för modellens nuvarande generation, eller null när vi inte vet.
     *
     * <p>Null betyder "ingen åsikt" och ska tolkas som att listan får visas — tabellen fylls
     * över flera nätter, och ett kort får inte tappa sina motoralternativ under tiden.
     *
     * @param modelName "märke modellord", samma form som {@code IceConsumptionService.allModelNames}
     */
    public Integer franArFor(String modelName) {
        if (modelName == null) return null;
        Map<String, Integer> m = cache;
        if (m == null) {
            m = new HashMap<>();
            try {
                for (Map<String, Object> r : jdbc.queryForList("SELECT model_name, fran_ar FROM ice_generation")) {
                    m.put(((String) r.get("model_name")).toLowerCase(), ((Number) r.get("fran_ar")).intValue());
                }
            } catch (Exception e) {
                log.warn("ice_generation: kunde inte läsas: {}", e.getMessage());
            }
            cache = m;
        }
        return m.get(modelName.toLowerCase());
    }

    /** Sparar startåret. Skriver över befintligt värde — auto-data är källan, inte vi. */
    public void spara(String modelName, int franAr) {
        jdbc.update("DELETE FROM ice_generation WHERE model_name = ?", modelName);
        jdbc.update("INSERT INTO ice_generation(model_name, fran_ar) VALUES (?, ?)", modelName, franAr);
        cache = null;
    }

    /**
     * Tömmer tabellen så att nattjobbet fyller om den. Se {@code DELETE /api/admin/ice-generations}
     * för varför: arbetslistan hoppar över modeller som redan har ett årtal, så ett felaktigt
     * värde blir permanent tills raden tas bort.
     */
    public int rensa() {
        int n = jdbc.update("DELETE FROM ice_generation");
        cache = null;
        return n;
    }

    /** Sant när modellen redan har ett årtal — arbetslistan hoppar över den. */
    public boolean harArtal(String modelName) {
        return franArFor(modelName) != null;
    }

    /**
     * Hela tabellen, modellnamn i bokstavsordning — underlaget för
     * {@code GET /api/admin/ice-generations}.
     *
     * <p>Läser förbi {@link #cache} med flit: cachen är byggd för uppslag och skulle behöva
     * sorteras om vid varje anrop ändå, och en granskning ska se vad som STÅR i tabellen,
     * inte vad processen råkar minnas. Tabellen är som mest 310 rader.
     */
    public java.util.List<Map<String, Object>> lista() {
        try {
            return jdbc.queryForList("SELECT model_name, fran_ar FROM ice_generation ORDER BY model_name")
                    .stream()
                    .map(r -> {
                        Map<String, Object> rad = new java.util.LinkedHashMap<String, Object>();
                        rad.put("model", r.get("model_name"));
                        rad.put("franAr", ((Number) r.get("fran_ar")).intValue());
                        return rad;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("ice_generation: kunde inte listas: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    public long antal() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ice_generation", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
