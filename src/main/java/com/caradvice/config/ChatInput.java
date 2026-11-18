package com.caradvice.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Det klienten skickar till chatten, rensat innan det når AI:n.
 *
 * <p>Bara {@code user}- och {@code assistant}-meddelanden släpps igenom. Roller tas inte från
 * klienten: ett eget {@code system}-meddelande hade annars kunnat skriva över systemprompten
 * och göra bilrådgivaren till en gratis allmän chattbot. Längderna kapas så att ett skript
 * inte kan skicka megabyte per anrop och tömma Groq-kvoten med några få frågor.
 *
 * @author Robert Andersson Kopler
 */
public final class ChatInput {

    public static final int MAX_MESSAGE_CHARS = 4_000;
    public static final int MAX_CONTEXT_CHARS = 20_000;

    private ChatInput() {}

    public static List<Map<String, String>> messages(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, String>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object role = m.get("role");
            Object content = m.get("content");
            if (!("user".equals(role) || "assistant".equals(role))) continue;
            if (!(content instanceof String text) || text.isBlank()) continue;
            if (text.length() > MAX_MESSAGE_CHARS) text = text.substring(0, MAX_MESSAGE_CHARS);
            out.add(Map.of("role", (String) role, "content", text));
        }
        return out;
    }

    public static String context(Object raw) {
        if (!(raw instanceof String s)) return null;
        return s.length() > MAX_CONTEXT_CHARS ? s.substring(0, MAX_CONTEXT_CHARS) : s;
    }
}
