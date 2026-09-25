package com.caradvice.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Säger till GitHub att nattkedjan är klar ({@code repository_dispatch}, händelsen
 * {@code nattkedjan-klar}), så att molnrutinen som granskar natten startar direkt i stället för
 * på ett gissat klockslag. Rutinen är kopplad till händelsen på claude.ai sedan 2026-09-25.
 *
 * <p>Tokenen ({@code GITHUB_DISPATCH_TOKEN} på Render) är en finkornig token med skrivrätt till
 * repots innehåll — det är den behörighet GitHub kräver för en dispatch. Saknas den görs
 * ingenting: den fasta reservkörningen tar natten ändå.
 *
 * <p>Kastar aldrig. Utfallet returneras som text och hamnar i {@link NattkedjanStatus}, så att
 * granskningen kan se att signalen föll — ett tyst misslyckande här hade bara märkts som en
 * rapport som kom senare än vanligt.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class GitHubSignal {

    private static final Logger log = LoggerFactory.getLogger(GitHubSignal.class);
    static final String URL = "https://api.github.com/repos/elitrobban89/CarAdvice/dispatches";
    static final String KROPP = "{\"event_type\":\"nattkedjan-klar\"}";

    /** Själva anropet — utbytbart i prov. Svarar med HTTP-status. */
    interface Avsandare {
        int posta(String url, String token, String kropp) throws Exception;
    }

    private final String token;
    Avsandare avsandare = GitHubSignal::postaPaRiktigt;

    public GitHubSignal(@Value("${GITHUB_DISPATCH_TOKEN:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    public String skicka() {
        if (token.isEmpty()) {
            log.info("Nattkedjan: ingen GITHUB_DISPATCH_TOKEN - ingen signal, reservkörningen tar natten");
            return "ingen token";
        }
        try {
            int status = avsandare.posta(URL, token, KROPP);
            // GitHub svarar 204 No Content på en godtagen dispatch
            if (status == 204) {
                log.info("Nattkedjan: signal skickad till GitHub");
                return "skickad";
            }
            log.warn("Nattkedjan: GitHub avvisade signalen med HTTP {}", status);
            return "fel: HTTP " + status;
        } catch (Exception e) {
            log.warn("Nattkedjan: signalen gick inte fram: {}", e.getMessage());
            return "fel: " + e.getClass().getSimpleName();
        }
    }

    private static int postaPaRiktigt(String url, String token, String kropp) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(kropp))
                .build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
