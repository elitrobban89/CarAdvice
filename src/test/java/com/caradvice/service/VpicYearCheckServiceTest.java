package com.caradvice.service;

import com.caradvice.service.VpicYearCheckService.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Årtalsvakten mot NHTSA vPIC.
 *
 * <p>Alla årsserier nedan är <b>uppmätta mot det skarpa API:t 2026-08-18</b>, inte påhittade —
 * det är hela poängen med en vakt vars värde står och faller med att en avvikelse betyder något.
 * Testerna går aldrig ut på nätet: {@link #vakt} byter ut hämtningen mot serierna.
 */
class VpicYearCheckServiceTest {

    private static final int IDAG = LocalDate.now().getYear();

    /** Anrop hämtningen fick ta emot — så testerna kan visa att cachen och avbrotten fungerar. */
    private final List<String> anropslogg = new ArrayList<>();

    /**
     * En vakt vars vPIC-svar kommer ur en karta i stället för från nätet.
     *
     * @param serier märke (gemener) → modellord → de år vPIC har modellen
     */
    private VpicYearCheckService vakt(Map<String, Map<String, Set<Integer>>> serier) {
        return new VpicYearCheckService() {
            @Override
            Set<String> hamtaModeller(String marke, int ar) {
                anropslogg.add(marke.toLowerCase() + " " + ar);
                Map<String, Set<Integer>> modeller = serier.get(marke.toLowerCase());
                if (modeller == null) return Set.of();
                Set<String> ut = new java.util.HashSet<>();
                modeller.forEach((namn, arList) -> { if (arList.contains(ar)) ut.add(namn); });
                return ut;
            }
        };
    }

    private static Map<String, Map<String, Set<Integer>>> volvoSerier() {
        // Uppmätt 2026-08-18 mot GetModelsForMakeYear:
        //   S90  finns 1998, saknas 1999-2016, finns 2017 →
        //   V90  finns 1996-1998, saknas 1999-2016, finns 2017-2021, saknas 2022 →
        //   XC90 finns 2003 →
        Map<String, Set<Integer>> volvo = new LinkedHashMap<>();
        volvo.put("s90", ar(1998, 1998, 2017, IDAG));
        volvo.put("v90", ar(1996, 1998, 2017, 2021));
        volvo.put("xc90", ar(2003, IDAG));
        return Map.of("volvo", volvo);
    }

    private static Set<Integer> ar(int... spann) {
        Set<Integer> ut = new java.util.HashSet<>();
        for (int i = 0; i < spann.length; i += 2) {
            for (int a = spann[i]; a <= spann[i + 1]; a++) ut.add(a);
        }
        return ut;
    }

    @Test
    void namneFranEnAnnanEpokFallerSomAvvikelse() {
        /*
         * Felet vakten byggdes för. 2026-08-18 stod "Volvo s90" som 1997 i ice_generation fast
         * vår motorlista beskriver B4/B5/B6/T8, alltså 2016 års bil — 1997 är den ombadgade
         * 960:an. Felet hittades av att en människa ögnade 172 rader; räknaren i cargo-coverage
         * kunde aldrig ha avslöjat det, för den räknar RADER och felet satt i VÄRDET.
         *
         * vPIC ser det utan att veta något om vår databas: den saknar S90 år 2000 och 2003 men
         * har den igen 2017. Ett hål mellan vårt årtal och nu = ett generationsbyte vi daterat
         * oss före.
         */
        var dom = vakt(volvoSerier()).granskaEn("Volvo", "s90", 1997, new HashMap<>(), new int[]{0});

        assertThat(dom.status()).isEqualTo(Status.AVVIKER);
        assertThat(dom.kommentar()).contains("2000", "2003").contains("generationsbyte");
    }

    @Test
    void rattatArtalGerOkOchKostarEttEndaAnrop() {
        // Efter rättningen ska S90 stå som 2016. Provet 2019 träffar direkt, och då ställs
        // ingen fler fråga — den vanliga vägen ska vara billig, annars går vakten inte att
        // köra över hela tabellen.
        var dom = vakt(volvoSerier()).granskaEn("Volvo", "s90", 2016, new HashMap<>(), new int[]{0});

        assertThat(dom.status()).isEqualTo(Status.OK);
        assertThat(anropslogg).containsExactly("volvo 2019");
    }

    @Test
    void ankaretHittarModellenAvenNarDenSlutatSaljasIUSA() {
        /*
         * GRÄNSEN SOM KOSTADE EN OMSKRIVNING. Första utkastet 2026-08-18 använde INNEVARANDE ÅR
         * som grind: "känner vPIC modellen i dag?". Volvo V90 föll då ut som INGEN_DATA, för den
         * såldes i USA 2017-2021 men inte längre — trots att raden bar exakt samma fel som S90
         * (1996 i stället för 2016). Grinden hade alltså missat hälften av det vakten byggdes för.
         *
         * Ankarstegen letar i stället framåt från provfönstret och stannar vid första träffen,
         * så en modell som lämnat marknaden fälls lika säkert som en som finns kvar.
         */
        var dom = vakt(volvoSerier()).granskaEn("Volvo", "v90", 1996, new HashMap<>(), new int[]{0});

        assertThat(dom.status()).isEqualTo(Status.AVVIKER);
        assertThat(dom.kommentar()).contains("1999", "2002");
    }

    @Test
    void okantMarkeGerIngenDataOchAldrigEnAvvikelse() {
        /*
         * ÖVERBLOCKERINGSGRÄNSEN, och den är den viktigaste i hela klassen. Mätt 2026-08-18 för
         * modellåret 2023 har vPIC NOLL modeller för Škoda, Renault, Dacia och Cupra, en för
         * Opel, en för Citroën, två för SEAT och fyra för Peugeot — och vår CSV har 212
         * motorrader i just de märkena. Rapporterades de som avvikelser vore rapporten oläsbar,
         * och en vakt som ropar varje morgon slutar man läsa. Samma skäl som SourceResult
         * medvetet saknar varning på "0 av N lästa".
         *
         * INGEN_DATA är därför en EGEN utgång och aldrig ett godkännande — att slå ihop "vPIC
         * håller med" med "vPIC vet ingenting" är exakt den hopslagning som lät 139 falska nej
         * ligga parkerade i 30 dagar.
         */
        var dom = vakt(Map.of("skoda", Map.<String, Set<Integer>>of()))
                .granskaEn("Skoda", "octavia", 2019, new HashMap<>(), new int[]{0});

        assertThat(dom.status()).isEqualTo(Status.INGEN_DATA);
        assertThat(dom.kommentar()).contains("amerikanskt register");
    }

    @Test
    void modellSomAldrigNattUsaGerIngenDataAvenMedTraffFore() {
        /*
         * Den lömska varianten: modellen fanns i USA FÖRE vårt årtal men aldrig efter. Då finns
         * inget hål att peka på — bara ett slut — och ett slut är inget generationsbyte. Volvo
         * V90 daterad 2017 är fallet: den saknas 2020? nej, den finns. Tag i stället XC90 med ett
         * årtal så sent att ankaret aldrig hittar något efteråt.
         */
        Map<String, Set<Integer>> volvo = new LinkedHashMap<>();
        volvo.put("v90", ar(1996, 1998));      // fanns bara på 90-talet
        var dom = vakt(Map.of("volvo", volvo))
                .granskaEn("Volvo", "v90", 2016, new HashMap<>(), new int[]{0});

        assertThat(dom.status()).isEqualTo(Status.INGEN_DATA);
    }

    @Test
    void hamtningsfelGerIngenAsiktAldrigEttFynd() {
        /*
         * vPIC är ett gratisregister utan avtal och går ner ibland. Ett nätverksfel ger tom
         * mängd, och tom mängd måste landa i INGEN_DATA — aldrig i AVVIKER. Att låta ett
         * hämtningsfel producera ett fynd är exakt fail-soft-fällan som en gång parkerade 139
         * modeller i 30 dagar: catch-grenens tomsträng blev ett "nej" som såg äkta ut.
         */
        var trasig = new VpicYearCheckService() {
            @Override
            Set<String> hamtaModeller(String marke, int ar) { return Set.of(); }
        };

        var dom = trasig.granskaEn("Volvo", "s90", 1997, new HashMap<>(), new int[]{0});
        assertThat(dom.status()).isEqualTo(Status.INGEN_DATA);
    }

    @Test
    void fardigtArtalDomsInteAlls() {
        // Provåren ligger i framtiden för en generation som just startat — då finns inget att
        // mäta mot, och vakten ska säga det i stället för att gissa.
        var dom = vakt(volvoSerier()).granskaEn("Volvo", "xc90", IDAG, new HashMap<>(), new int[]{0});

        assertThat(dom.status()).isEqualTo(Status.INGEN_DATA);
        assertThat(dom.kommentar()).contains("för färskt");
        assertThat(anropslogg).isEmpty();      // inget anrop alls för en rad som inte går att döma
    }

    @Test
    void rapportenRaknarVarjeStatusForSigOchRedovisarKapningen() {
        /*
         * Rapporten måste svara på "hur mycket av tabellen granskades?" och inte bara "hittade
         * du något?". En halv granskning som ser ut som en hel är samma fälla som täckningsmätaren
         * på 602/602/0 — talet såg friskt ut medan parsern var död.
         */
        List<Map<String, Object>> rader = List.of(
                rad("Volvo s90", 1997),      // AVVIKER
                rad("Volvo xc90", 2015),     // OK
                rad("Skoda octavia", 2019)); // INGEN_DATA (okänt märke)

        var rapport = vakt(volvoSerier()).granska(rader, null, VpicYearCheckServiceTest::dela);

        assertThat(rapport.kontrollerade()).isEqualTo(3);
        assertThat(rapport.hoppade()).isZero();
        assertThat(rapport.perStatus())
                .containsEntry("AVVIKER", 1L)
                .containsEntry("OK", 1L)
                .containsEntry("INGEN_DATA", 1L);
        // Bara avvikelserna följer med i svaret — de andra 170 raderna är brus i en rapport
        assertThat(rapport.avvikelser()).hasSize(1);
        assertThat(rapport.avvikelser().get(0).model()).isEqualTo("Volvo s90");
    }

    @Test
    void markesfiltretBegransarGranskningen() {
        List<Map<String, Object>> rader = List.of(rad("Volvo s90", 1997), rad("Skoda octavia", 2019));

        var rapport = vakt(volvoSerier()).granska(rader, "Volvo", VpicYearCheckServiceTest::dela);

        assertThat(rapport.kontrollerade()).isEqualTo(1);
        assertThat(rapport.avvikelser()).hasSize(1);
    }

    @Test
    void foraldralostModellnamnRaknasSomIngenDataInteSomFel() {
        // Modellnamnet finns inte längre i ice_consumption. Det är en rad som blivit
        // föräldralös, inte ett årtalsfel — och den får aldrig hamna bland avvikelserna.
        var rapport = vakt(volvoSerier())
                .granska(List.of(rad("Saab 9-5", 2010)), null, namn -> null);

        assertThat(rapport.perStatus()).containsEntry("INGEN_DATA", 1L);
        assertThat(rapport.avvikelser()).isEmpty();
    }

    @Test
    void modellnamnJamforsExaktEfterNormalisering() {
        // "V60 CC" och "v60-cc" är samma ord; "S90" och "S90 L" är det INTE. En delsträngs-
        // matchning hade låtit dem träffa varandra, och en vakt som fäller på fel grund är
        // värre än ingen vakt.
        assertThat(VpicYearCheckService.normalisera("V60 CC")).isEqualTo("v60cc");
        assertThat(VpicYearCheckService.normalisera("v60-cc")).isEqualTo("v60cc");
        assertThat(VpicYearCheckService.normalisera("S90 L")).isNotEqualTo(VpicYearCheckService.normalisera("S90"));
    }

    @Test
    void svarsparsningenTalTrasigJson() {
        var v = new VpicYearCheckService();
        assertThat(v.modellerUrSvar("{\"Results\":[{\"Model_Name\":\"S90\"},{\"Model_Name\":\"XC90\"}]}"))
                .containsExactlyInAnyOrder("s90", "xc90");
        // Fail open mot tom mängd — ett oläsbart svar är "ingen åsikt", aldrig ett fynd
        assertThat(v.modellerUrSvar("<html>502 Bad Gateway</html>")).isEmpty();
        assertThat(v.modellerUrSvar("{\"Message\":\"no results\"}")).isEmpty();
    }

    private static Map<String, Object> rad(String model, int franAr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("franAr", franAr);
        return m;
    }

    /** Samma delning som IceConsumptionService.delaModellnamn gör mot tabellen. */
    private static String[] dela(String modelName) {
        int i = modelName.lastIndexOf(' ');
        return new String[] { modelName.substring(0, i), modelName.substring(i + 1) };
    }
}
