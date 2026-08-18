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

    /**
     * Hur länge en miss får hindra ett nytt försök. Se {@link #noteraMiss} — fönstret finns
     * för att en död parser annars hade frusit ett tomt bord permanent.
     */
    static final int MISS_GILTIG_DAGAR = 30;

    /** Hela tabellen i minnet — den är liten (≤310 rader) och läses på varje kortrendering. */
    private volatile Map<String, Integer> cache = null;

    /** Samma sak för missarna — läses bara av nattjobbet, men är lika liten. */
    private volatile Map<String, Integer> missCache = null;

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
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ice_generation_miss (
                model_name VARCHAR(120) PRIMARY KEY,
                forsokt_dag INT NOT NULL
            )
            """);
        // Orsaken kom till 2026-08-16 och finns för att jobbet räknade alla nej i EN siffra:
        // "utan träff" var både ett dött uppslag och en vakt som avstod med flit. Skillnaden
        // syntes inte i loggen, och 139 falska nej kunde ligga parkerade i en månad utan att
        // något såg trasigt ut. Läggs till separat så en befintlig tabell inte behöver byggas om.
        try {
            jdbc.execute("ALTER TABLE ice_generation_miss ADD COLUMN IF NOT EXISTS orsak VARCHAR(40)");
        } catch (Exception e) {
            log.warn("ice_generation_miss: kunde inte lägga till orsak-kolumnen: {}", e.getMessage());
        }
    }

    /** Orsaker {@link #noteraMiss} känner till. Lagras som text — de läses av människor. */
    public static final String ORSAK_EJ_HITTAD = "ej-hittad";
    public static final String ORSAK_VAKTEN_AVSTOD = "vakten-avstod";

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
        // En modell som gett ett årtal ska inte ligga kvar som miss: träffen är färskare än
        // anteckningen, och en kvarglömd rad hade räknats i antalMissar utan att betyda något
        try {
            jdbc.update("DELETE FROM ice_generation_miss WHERE model_name = ?", modelName);
            missCache = null;
        } catch (Exception e) {
            log.warn("ice_generation_miss: kunde inte rensas för {}: {}", modelName, e.getMessage());
        }
    }

    /**
     * Tar bort EN modells årtal, och dess eventuella miss, så nattjobbet prövar om just den.
     *
     * <p>Felen kommer modellvis: 2026-08-18 bar två av 172 rader fel generation medan de övriga
     * 170 var riktiga. {@link #rensa} hade kostat tre nätter utan generationsdata för att laga
     * dem. Missen måste med — en modell som ligger som miss hoppas över lika säkert som en som
     * har ett årtal, och då hade raderingen inte gett något nytt försök.
     *
     * @return antal borttagna årtalsrader, alltså 0 eller 1
     */
    public int radera(String modelName) {
        if (modelName == null) return 0;
        int n = jdbc.update("DELETE FROM ice_generation WHERE LOWER(model_name) = LOWER(?)", modelName);
        cache = null;
        try {
            jdbc.update("DELETE FROM ice_generation_miss WHERE LOWER(model_name) = LOWER(?)", modelName);
            missCache = null;
        } catch (Exception e) {
            log.warn("ice_generation_miss: kunde inte rensas för {}: {}", modelName, e.getMessage());
        }
        return n;
    }

    /**
     * Tömmer tabellen så att nattjobbet fyller om den. Se {@code DELETE /api/admin/ice-generations}
     * för varför: arbetslistan hoppar över modeller som redan har ett årtal, så ett felaktigt
     * värde blir permanent tills raden tas bort.
     *
     * <p>Gäller felet bara enstaka modeller — använd {@link #radera} i stället.
     */
    public int rensa() {
        int n = jdbc.update("DELETE FROM ice_generation");
        cache = null;
        // Missarna töms med: anledningen att tömma är att källan gett fel data, och då är
        // "vi prövade och fick inget" lika opålitligt som årtalen. Ligger de kvar spärrar de
        // dessutom just de modeller ombyggnaden ska nå.
        try {
            jdbc.update("DELETE FROM ice_generation_miss");
            missCache = null;
        } catch (Exception e) {
            log.warn("ice_generation_miss: kunde inte tömmas: {}", e.getMessage());
        }
        return n;
    }

    /** Sant när modellen redan har ett årtal — arbetslistan hoppar över den. */
    public boolean harArtal(String modelName) {
        return franArFor(modelName) != null;
    }

    /**
     * Antecknar att modellen prövats utan att ge ett årtal, så att nattens försök går till
     * modeller vi inte testat i stället för till kända nej.
     *
     * <p><b>Varför det behövdes.</b> Arbetslistan filtrerade bara på {@link #harArtal} och
     * betades i CSV-ordning, alltså bokstavsordning, med ett tak på 150 <i>försök</i> per
     * körning. Natten 2026-08-15 gav 32 årtal — de övriga 118 försöken gick till modeller som
     * missade, och eftersom en miss inte lämnade något spår provades samma 118 om först
     * nästa natt, före varje modell som aldrig testats. Budgeten för nya modeller krymper då
     * 150 → 32 → ~7 → ~0 och fronten stannar runt märke 20 av 42. Volkswagen är märke 41 och
     * Volvo 42: <b>Golf och XC60 hade aldrig fått ett årtal.</b>
     *
     * <p><b>Varför missen ändå glöms efter {@link #MISS_GILTIG_DAGAR} dagar.</b> En miss betyder
     * inte att modellen saknar generationsdata för alltid — den 2026-08-13 missade <i>varenda</i>
     * modell därför att sajten bytt markup och parsern var död. Ett permanent nej hade
     * frusit det haveriet till ett tomt bord som aldrig fyllts igen. Fönstret gör listan
     * självläkande utan att kosta något i det normala fallet.
     */
    public void noteraMiss(String modelName, String orsak) {
        if (modelName == null) return;
        try {
            long dag = java.time.LocalDate.now().toEpochDay();
            jdbc.update("DELETE FROM ice_generation_miss WHERE model_name = ?", modelName);
            jdbc.update("INSERT INTO ice_generation_miss(model_name, forsokt_dag, orsak) VALUES (?, ?, ?)",
                    modelName, (int) dag, orsak);
            missCache = null;
        } catch (Exception e) {
            log.warn("ice_generation_miss: kunde inte skriva {}: {}", modelName, e.getMessage());
        }
    }

    /** Antal parkerade missar per orsak — svarar på "avstår vakten, eller är uppslaget dött?". */
    public Map<String, Long> missarPerOrsak() {
        Map<String, Long> ut = new java.util.LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT COALESCE(orsak, 'okand') AS orsak, COUNT(*) AS antal "
                            + "FROM ice_generation_miss GROUP BY COALESCE(orsak, 'okand') ORDER BY 2 DESC")) {
                ut.put((String) r.get("orsak"), ((Number) r.get("antal")).longValue());
            }
        } catch (Exception e) {
            log.warn("ice_generation_miss: kunde inte räknas per orsak: {}", e.getMessage());
        }
        return ut;
    }

    /**
     * Glömmer alla parkerade missar så nattjobbet prövar om dem.
     *
     * <p>Behövs efter varje rättning i uppslaget: en miss betyder "auto-data hade inget att ge",
     * och den domen är bara giltig så länge frågan ställdes rätt. När 2026-08-16 års rättningar
     * visade att chassikoder fällde varenda generation för BMW, Audi och Lexus var de 139
     * parkerade raderna inte längre nej utan obesvarade — och utan den här hade de legat kvar i
     * 30 dagar och gjort rättningen verkningslös till mitten av september.
     */
    public int rensaMissar() {
        int n = jdbc.update("DELETE FROM ice_generation_miss");
        missCache = null;
        return n;
    }

    /**
     * Sant när modellen prövats utan träff inom fönstret — arbetslistan hoppar över den.
     *
     * <p>Fail-open vid läsfel: hellre ett bortkastat försök än en modell som aldrig prövas.
     */
    public boolean harFarskMiss(String modelName) {
        if (modelName == null) return false;
        Map<String, Integer> m = missCache;
        if (m == null) {
            m = new HashMap<>();
            try {
                for (Map<String, Object> r : jdbc.queryForList("SELECT model_name, forsokt_dag FROM ice_generation_miss")) {
                    m.put(((String) r.get("model_name")).toLowerCase(), ((Number) r.get("forsokt_dag")).intValue());
                }
            } catch (Exception e) {
                log.warn("ice_generation_miss: kunde inte läsas: {}", e.getMessage());
            }
            missCache = m;
        }
        Integer dag = m.get(modelName.toLowerCase());
        if (dag == null) return false;
        return java.time.LocalDate.now().toEpochDay() - dag < MISS_GILTIG_DAGAR;
    }

    /** Antal modeller som ligger som miss just nu, oavsett ålder — syns i granskningslistan. */
    public long antalMissar() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ice_generation_miss", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
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
