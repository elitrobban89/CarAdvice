package com.caradvice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vad tjänsten faktiskt kör på — till uppstartsskärmens plattformsrad.
 *
 * <p>Java-versionen läses ur den körande JVM:en och databasens namn och version ur
 * anslutningen ({@link DatabaseMetaData}), aldrig ur en avskriven sträng. Den dagen projektet
 * uppgraderas till Java 28 eller en nyare PostgreSQL visar splashen det utan att någon rör den.
 *
 * <p>Databasraden läses EN gång och sparas: metadata kostar en tur till databasen och ändras
 * inte under en körning. Svarar databasen inte utelämnas nyckeln — splashen visar då raden
 * utan versionsnummer i stället för att hitta på ett.
 *
 * @author Robert Andersson Kopler
 */
@RestController
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final ObjectProvider<DataSource> dataSource;
    private volatile String databas;

    public SystemController(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/system")
    public Map<String, String> system() {
        Map<String, String> ut = new LinkedHashMap<>();
        ut.put("java", System.getProperty("java.version", ""));
        ut.put("springBoot", String.valueOf(SpringBootVersion.getVersion()));
        // Render sätter de här i varje deploy — splashens autodeploy-bricka visar vilken commit som kör.
        String commit = System.getenv("RENDER_GIT_COMMIT");
        if (commit != null && !commit.isBlank()) ut.put("deployCommit", commit.substring(0, Math.min(7, commit.length())));
        String branch = System.getenv("RENDER_GIT_BRANCH");
        if (branch != null && !branch.isBlank()) ut.put("deployBranch", branch);
        String db = databas();
        if (!db.isBlank()) ut.put("db", db);
        return ut;
    }

    /** T.ex. "PostgreSQL 17.4" — tom sträng när anslutningen inte svarar (då provas nästa gång igen). */
    private String databas() {
        if (databas != null) return databas;
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) return "";
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String namn = md.getDatabaseProductName();
            String ver = md.getDatabaseProductVersion();
            if (namn == null || namn.isBlank()) return "";
            // "17.4 (Debian 17.4-1.pgdg120+2)" -> "17.4": första ordet räcker på en splashrad.
            if (ver != null && !ver.isBlank()) namn += " " + ver.trim().split("\\s+")[0];
            databas = namn;
            return namn;
        } catch (Exception e) {
            log.warn("Kunde inte läsa databasversionen till uppstartsskärmen: {}", e.getMessage());
            return "";
        }
    }
}
