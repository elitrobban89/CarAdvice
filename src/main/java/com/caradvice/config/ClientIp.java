package com.caradvice.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Besökarens IP för rate limiting.
 *
 * <p>Läser {@code CF-Connecting-IP}, inte {@code X-Forwarded-For}. Render ligger bakom
 * Cloudflare, och Cloudflare skriver alltid över {@code CF-Connecting-IP} med den IP som
 * faktiskt anslöt. {@code X-Forwarded-For} däremot släpps igenom som klienten skickade den
 * (Render lägger bara till sin egen post på slutet), så den första posten var fritt vald
 * av anroparen. Med en ny påhittad IP per anrop gick timtaket på /recommend och
 * inloggningsspärren att kringgå helt.
 *
 * <p>Saknas headern (lokal körning, tester) används anslutningens egen adress.
 *
 * @author Robert Andersson Kopler
 */
public final class ClientIp {

    static final String CF_HEADER = "CF-Connecting-IP";

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String cf = request.getHeader(CF_HEADER);
        if (cf != null && !cf.isBlank()) return cf.trim();
        return request.getRemoteAddr();
    }
}
