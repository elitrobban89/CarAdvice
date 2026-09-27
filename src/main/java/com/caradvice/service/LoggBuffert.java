package com.caradvice.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * De senaste varningarna och felen i minnet, läsbara via {@code GET /api/admin/logg}.
 *
 * <p><b>Varför.</b> Nattrutinens regler säger på ett dussin ställen "be om Render-loggen" — efter
 * 'hoppar over batchen', 'auto-data bagage: ...', 'Drivmedel ... kunde inte skrivas', 'Autosläpp
 * AVBRUTET'. Rutinen når inte Render, så varje sådan avvikelse stannade hos en människa. Det som
 * behövs är just de raderna, och appen har dem redan i handen när den skriver dem.
 *
 * <p>Bara WARN och ERROR, och högst {@value #MAX} händelser — en ringbuffert, så minnet är fast
 * oavsett hur mycket som loggas. Bufferten töms vid omstart (som kategorivaktens); rutinen läser
 * den efter nattkedjan i samma process, och {@code uptimeSeconds} säger om en deploy kommit emellan.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class LoggBuffert extends AppenderBase<ILoggingEvent> {

    static final int MAX = 500;
    private static final DateTimeFormatter TID =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Europe/Stockholm"));

    private final Deque<Map<String, Object>> buffert = new ArrayDeque<>();

    @PostConstruct
    public void anslut() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
        setContext(ctx);
        setName("loggbuffert");
        start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(this);
    }

    @PreDestroy
    public void koppla() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)
            ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
        stop();
    }

    @Override
    protected void append(ILoggingEvent e) {
        if (!e.getLevel().isGreaterOrEqual(Level.WARN)) return;
        Map<String, Object> rad = new LinkedHashMap<>();
        rad.put("tid", TID.format(Instant.ofEpochMilli(e.getTimeStamp())));
        rad.put("niva", e.getLevel().toString());
        String logger = e.getLoggerName();
        rad.put("kalla", logger.substring(logger.lastIndexOf('.') + 1));
        rad.put("meddelande", kapa(e.getFormattedMessage(), 2000));
        IThrowableProxy t = e.getThrowableProxy();
        if (t != null) rad.put("undantag", kapa(t.getClassName() + ": " + t.getMessage(), 500));
        synchronized (buffert) {
            buffert.addLast(rad);
            while (buffert.size() > MAX) buffert.removeFirst();
        }
    }

    /**
     * Nyast först.
     *
     * @param niva "ERROR" för bara fel, annars WARN och ERROR
     * @param sok  delsträng i meddelande eller källa (skiftlägesokänslig), eller null
     */
    public List<Map<String, Object>> senaste(String niva, String sok, int limit) {
        List<Map<String, Object>> kopia;
        synchronized (buffert) {
            kopia = new ArrayList<>(buffert);
        }
        String s = sok == null ? null : sok.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> ut = new ArrayList<>();
        for (int i = kopia.size() - 1; i >= 0 && ut.size() < limit; i--) {
            Map<String, Object> r = kopia.get(i);
            if ("ERROR".equalsIgnoreCase(niva) && !"ERROR".equals(r.get("niva"))) continue;
            if (s != null && !(String.valueOf(r.get("meddelande")) + " " + r.get("kalla"))
                    .toLowerCase(Locale.ROOT).contains(s)) continue;
            ut.add(r);
        }
        return ut;
    }

    public int antal() {
        synchronized (buffert) {
            return buffert.size();
        }
    }

    private static String kapa(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
