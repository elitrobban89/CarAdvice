package com.caradvice.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Laddtipsen i elbilsassistentens "Visste du att"-karusell, skrivna av nattrutinen.
 *
 * <p><b>Varför data och inte kod.</b> Tipsen låg handskrivna i {@code ev-app.js}, en fil som finns
 * i två repon och som nattrutinens ramar förbjuder den att röra (frontend). Nu skriver rutinen
 * dem i {@code morgonfix/laddtips.json}, grenen mergas automatiskt, och assistenten hämtar dem
 * härifrån — ett nytt tips når karusellen utan att någon rör JavaScript.
 *
 * <p><b>Ren text, inte HTML.</b> Tipsen är AI-formulerade ur skrapad motorpress och visas publikt.
 * De skickas som text; {@code **fetstil**} markeras med dubbla asterisker och görs om till
 * {@code <strong>} i webbläsaren FÖRST efter att texten escapats. Ett tips med en HTML-tagg
 * avvisas här, så en injektion kan inte ens nå fram.
 *
 * <p><b>Aktuella och kopplade till verkliga rader.</b> Ett tips äldre än {@value #MAX_ALDER_DAGAR}
 * dagar visas inte. Pekar tipset på insikter och någon av dem har raderats, dolts som skräp
 * eller parkerats som kommande, faller tipset — det vilar då på något rutinen själv underkänt.
 *
 * @author Robert Andersson Kopler
 */
@Service
public class LaddtipsService {

    private static final Logger log = LoggerFactory.getLogger(LaddtipsService.class);

    static final String RESURS = "morgonfix/laddtips.json";
    static final int MAX_ALDER_DAGAR = 180;
    static final int MAX_VISADE = 12;
    static final int MAX_TECKEN = 420;

    public record Tips(String datum, String ikon, String text, String kalla, List<Long> insikter) {}

    private final ExpertInsightService insikter;
    private final UpcomingInsightService kon;
    private final Supplier<InputStream> kalla;
    private final ObjectMapper json = new ObjectMapper();
    Clock klocka = Clock.system(ZoneId.of("Europe/Stockholm"));

    @Autowired
    public LaddtipsService(ExpertInsightService insikter, UpcomingInsightService kon) {
        this(insikter, kon, LaddtipsService::lasResurs);
    }

    LaddtipsService(ExpertInsightService insikter, UpcomingInsightService kon, Supplier<InputStream> kalla) {
        this.insikter = insikter;
        this.kon = kon;
        this.kalla = kalla;
    }

    private static InputStream lasResurs() {
        try {
            ClassPathResource r = new ClassPathResource(RESURS);
            return r.exists() ? r.getInputStream() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Tipsen som ska visas just nu, nyast först, högst {@value #MAX_VISADE}. */
    public List<Map<String, Object>> aktuella() {
        LocalDate idag = LocalDate.now(klocka);
        Set<Long> kommande = kon.hiddenIds();
        Set<Long> dolda = kon.doldaIds();
        List<Map<String, Object>> ut = new ArrayList<>();
        for (Tips t : giltiga().stream().sorted(Comparator.comparing(Tips::datum).reversed()).toList()) {
            if (LocalDate.parse(t.datum()).isBefore(idag.minusDays(MAX_ALDER_DAGAR))) continue;
            if (t.insikter().stream().anyMatch(id -> kommande.contains(id) || (dolda != null && dolda.contains(id))
                    || !insikter.exists(id))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ikon", t.ikon());
            m.put("text", t.text());
            m.put("kalla", t.kalla());
            m.put("datum", t.datum());
            ut.add(m);
            if (ut.size() >= MAX_VISADE) break;
        }
        return ut;
    }

    /** Filens tips som klarar kraven. Ogiltiga hoppas över och loggas - de visas aldrig. */
    List<Tips> giltiga() {
        try (InputStream in = kalla.get()) {
            if (in == null) return List.of();
            JsonNode rot = json.readTree(in);
            List<Tips> ut = new ArrayList<>();
            for (JsonNode n : rot.path("tips")) {
                String fel = fel(n);
                if (fel != null) {
                    log.warn("Laddtips ogiltigt ({}), visas inte: {}", fel, n);
                    continue;
                }
                List<Long> ids = new ArrayList<>();
                n.path("insikter").forEach(i -> ids.add(i.asLong()));
                ut.add(new Tips(n.path("datum").asString(), n.path("ikon").asString(),
                        n.path("text").asString().trim(), n.path("kalla").asString().trim(), ids));
            }
            return ut;
        } catch (Exception e) {
            log.warn("{} kunde inte läsas: {}", RESURS, e.getMessage());
            return List.of();
        }
    }

    /** @return skälet till att tipset inte godkänns, eller null */
    static String fel(JsonNode n) {
        String datum = n.path("datum").asString("");
        String text = n.path("text").asString("").trim();
        String kalla = n.path("kalla").asString("").trim();
        String ikon = n.path("ikon").asString("");
        if (!datum.matches("\\d{4}-\\d{2}-\\d{2}")) return "datum";
        try { LocalDate.parse(datum); } catch (Exception e) { return "datum"; }
        if (text.length() < 40) return "text för kort";
        if (text.length() > MAX_TECKEN) return "text över " + MAX_TECKEN + " tecken";
        if (text.contains("<") || text.contains(">")) return "HTML i texten";
        if (kalla.isEmpty()) return "källa saknas";
        if (kalla.contains("<") || kalla.contains(">")) return "HTML i källan";
        if (ikon.isEmpty() || ikon.codePointCount(0, ikon.length()) > 2) return "ikon";
        return null;
    }
}
