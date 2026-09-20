package com.caradvice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vakten for upphovsmarkeringen. Den bevakar tre saker som alla har gatt sonder for mig en gang:
 * att checksumman faktiskt hor till namnet (jag skrev in ett GISSAT tal forst, vilket hade gjort
 * att tjansten larmade vid varje uppstart), att markeringen finns kvar i ALLA Java-filer, och att
 * NOTICE namner samma person som koden gor.
 *
 * @author Robert Andersson Kopler
 */
class AuthorshipTest {

    @Test
    void checksummanHorTillNamnet() {
        // Poangen med kontrollen ar att den ska vara TYST nar inget ar andrat. Ett gissat tal
        // hade gett ett larm i loggen vid varje uppstart - ett larm som alltid tjuter ar inget larm.
        assertThat(Authorship.checksumOf(Authorship.AUTHOR)).isEqualTo(Authorship.AUTHOR_CHECKSUM);
    }

    @Test
    void kontrollenUpptackerEttAndratNamn() {
        assertThat(Authorship.checksumOf("Nagon Annan")).isNotEqualTo(Authorship.AUTHOR_CHECKSUM);
    }

    @Test
    void allaJavafilerBarForfattaren() throws Exception {
        // Utan det har provet tystnar markeringen sa fort nagon lagger till en ny klass: det
        // ar inte borttagning som ar den sannolika luckan, det ar nasta fil som skrivs.
        try (Stream<Path> filer = Files.walk(Path.of("src"))) {
            List<Path> utan = filer
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return !Files.readString(p).contains(Authorship.AUTHOR);
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .toList();
            assertThat(utan)
                    .as("Java-filer utan @author %s - kor scratchpad/watermark.js", Authorship.AUTHOR)
                    .isEmpty();
        }
    }

    @Test
    void noticeNamnerSammaUpphovsman() throws Exception {
        assertThat(Files.readString(Path.of("NOTICE"))).contains(Authorship.AUTHOR);
    }
}
