package com.caradvice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Varje Spring-bona maste ga att instansiera - en kontroll som INGET annat test gor.
 *
 * <p>Bakgrunden ar ett driftstopp 2026-09-13. {@code PrisUppvarmning} fick en andra,
 * paketprivat konstruktor sa att testerna kunde korta vantetiderna. Spring valjer konstruktor
 * automatiskt bara nar klassen har EN; med flera kraver den en tom konstruktor, och appen dog i
 * uppstarten med "No default constructor found". <b>Hela sviten var gron</b> - 1009 tester som
 * alla instansierar sina klasser med {@code new}, och darfor aldrig ror uppkopplingen. Render
 * rullade tillbaka, sa driften klarade sig, men bygget var trasigt tills felet hittades i loggen.
 *
 * <p>Ett riktigt {@code @SpringBootTest} hade fangat det men kraver databas och Groq-nyckel i
 * CI. Den har kontrollen ar regeln sjalv, last i reflektion: <b>en bona maste ha antingen exakt
 * en konstruktor eller exakt en markt med {@code @Autowired}.</b> Den kostar millisekunder och
 * galler hela repot, inte bara klassen som tappade fotfastet.
 *
 * @author Robert Andersson Kopler
 */
class BonkonstruktorTest {

    @Test
    void varjeBonaHarEnKonstruktorSomSpringKanValja() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));   // tacker @Service/@RestController via meta-annotering

        List<String> trasiga = new ArrayList<>();
        int granskade = 0;
        for (var def : scanner.findCandidateComponents("com.caradvice")) {
            Class<?> klass;
            try { klass = Class.forName(def.getBeanClassName()); }
            catch (ClassNotFoundException e) { continue; }
            if (klass.isInterface() || klass.isAnonymousClass()) continue;

            granskade++;
            Constructor<?>[] alla = klass.getDeclaredConstructors();
            if (alla.length == 1) continue;

            long markta = 0;
            for (Constructor<?> c : alla) if (c.isAnnotationPresent(Autowired.class)) markta++;
            boolean harTom = false;
            for (Constructor<?> c : alla) if (c.getParameterCount() == 0) harTom = true;

            if (markta != 1 && !harTom)
                trasiga.add(klass.getSimpleName() + " har " + alla.length
                        + " konstruktorer, ingen markt med @Autowired och ingen tom");
        }

        assertThat(granskade).isGreaterThan(10);   // skanningen maste faktiskt hitta bonorna
        assertThat(trasiga).isEmpty();
    }
}
