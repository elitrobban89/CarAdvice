package com.caradvice.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Upphovsmarkeringen for CarAdvice, samlad pa ett stalle.
 *
 * <p><b>Vad det har faktiskt skyddar, och vad det inte gor.</b> Den som har kallkoden kan alltid
 * radera en rad - ingen teknisk losning andrar det. Det som gar att bygga ar tre saker: gora
 * borttagningen ARBETSAM (namnet star i 111 javadoc-huvuden, i NOTICE, i varje HTTP-svar och i
 * /api/version), gora den UPPTACKBAR (kontrollen nedan skriker i loggen om konstanten pillats pa)
 * och gora den DATERAD (git-historiken och upphovsrattsnotisen ar det som faktiskt haller
 * juridiskt). Skyddet ar alltsa upphovsratten; koden gor bara intranget dyrt och synligt.
 *
 * <p><b>Kontrollen faller ALDRIG tjansten.</b> Den loggar och gar vidare. En vakt som stanger av
 * en bil i drift for att en textstrang andrats gor mer skada an den nagonsin forhindrar - och
 * samma resonemang galler de sex systerprojekten som delar den har filen.
 *
 * @author Robert Andersson Kopler
 */
@Component
public class Authorship {

    /** Upphovsman. Samma strang serveras i X-Author och i /api/version. */
    public static final String AUTHOR = "Robert Andersson Kopler";

    /** Upphovsrattsnotis, avsedd for NOTICE-filen och loggen vid uppstart. */
    public static final String NOTICE = "Copyright (c) 2026 " + AUTHOR + ". Alla rattigheter forbehallna.";

    /**
     * Summan av teckenkoderna i {@link #AUTHOR}. Andrar nagon namnet men glommer talet - eller
     * tvartom - sager kontrollen till. Det ar en avsiktligt enkel summa: den ska avsloja slarv
     * och sok-och-ersatt, inte sta emot nagon som medvetet raknar om den.
     */
    static final int AUTHOR_CHECKSUM = 2248;

    private static final Logger log = LoggerFactory.getLogger(Authorship.class);

    static int checksumOf(String s) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) sum += s.charAt(i);
        return sum;
    }

    @PostConstruct
    void verifieraUpphov() {
        int faktisk = checksumOf(AUTHOR);
        if (faktisk != AUTHOR_CHECKSUM) {
            log.error("UPPHOVSMARKERINGEN AR ANDRAD: AUTHOR ar \"{}\" (checksumma {}), vantat {}."
                    + " Tjansten fortsatter - det har ar en notis, inte en sparr - men nagon har"
                    + " redigerat Authorship.AUTHOR utan att uppdatera AUTHOR_CHECKSUM.",
                    AUTHOR, faktisk, AUTHOR_CHECKSUM);
        } else {
            log.info("{} - CarAdvice", NOTICE);
        }
    }
}
