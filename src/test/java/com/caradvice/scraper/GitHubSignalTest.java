package com.caradvice.scraper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signalen till GitHub när nattkedjan är klar (2026-09-25). Den får aldrig kasta, och varje
 * utfall ska gå att läsa i efterhand — ett tyst fel hade bara märkts som en senare rapport.
 *
 * @author Robert Andersson Kopler
 */
class GitHubSignalTest {

    private final List<String> anrop = new ArrayList<>();

    private GitHubSignal medSvar(String token, int status) {
        GitHubSignal s = new GitHubSignal(token);
        s.avsandare = (url, t, kropp) -> { anrop.add(url + " " + t + " " + kropp); return status; };
        return s;
    }

    @Test
    void utanTokenSkickasIngenting() {
        assertThat(medSvar("", 204).skicka()).isEqualTo("ingen token");
        assertThat(medSvar(null, 204).skicka()).isEqualTo("ingen token");
        assertThat(anrop).isEmpty();
    }

    @Test
    void godtagenDispatchArSkickad() {
        assertThat(medSvar("abc", 204).skicka()).isEqualTo("skickad");
        assertThat(anrop).containsExactly(GitHubSignal.URL + " abc {\"event_type\":\"nattkedjan-klar\"}");
    }

    @Test
    void avvisadDispatchBarStatuskoden() {
        // 401 = tokenen har gått ut, 404 = fel repo eller saknad behörighet
        assertThat(medSvar("abc", 401).skicka()).isEqualTo("fel: HTTP 401");
        assertThat(medSvar("abc", 404).skicka()).isEqualTo("fel: HTTP 404");
    }

    @Test
    void natverksfelKastarInte() {
        GitHubSignal s = new GitHubSignal("abc");
        s.avsandare = (url, t, kropp) -> { throw new IOException("connect timed out"); };
        assertThat(s.skicka()).isEqualTo("fel: IOException");
    }

    @Test
    void tokenMedRadbrytningTvattas() {
        // Inklistrad i Renders fält kan den få ett avslutande radslut
        medSvar("abc\n", 204).skicka();
        assertThat(anrop.get(0)).contains(" abc {");
    }
}
