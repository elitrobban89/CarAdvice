package com.caradvice.service;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvFactCandidateServiceTest {

    private ExpertInsightRepository repo;
    private UpcomingInsightService upcoming;
    private EvFactCandidateService service;

    @BeforeEach
    void setUp() {
        repo = mock(ExpertInsightRepository.class);
        upcoming = mock(UpcomingInsightService.class);
        when(upcoming.hiddenIds()).thenReturn(Set.of());
        service = new EvFactCandidateService(repo, upcoming);
    }

    private static ExpertInsight insikt(long id, String expert, String make, String model, String text) {
        ExpertInsight i = new ExpertInsight(expert, make, model, "elbil", "suv", text, null);
        try {
            Field f = ExpertInsight.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(i, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return i;
    }

    private void nyaRader(ExpertInsight... rader) {
        when(repo.findByIdGreaterThanOrderByIdDesc(anyLong(), any(Pageable.class))).thenReturn(List.of(rader));
        when(repo.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(List.of(rader));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> kandidater(Map<String, Object> svar) {
        return (List<Map<String, Object>>) svar.get("kandidater");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> avslag(Map<String, Object> svar) {
        return (Map<String, Integer>) svar.get("avvisadePerOrsak");
    }

    @Test
    void laddningsrelevantRadBlirKandidat() {
        nyaRader(insikt(1240, "Auto Motor & Sport", "Audi", "Q6 e-tron",
                "I Edmunds räckviddstest nådde Audi Q6 e-tron 542 km på 20-tumshjul och förbrukade 1,82 kWh per mil."));

        List<Map<String, Object>> ut = kandidater(service.hittaKandidater(1239L, 25));

        assertThat(ut).hasSize(1);
        assertThat(ut.get(0).get("id")).isEqualTo(1240L);
        assertThat((List<String>) ut.get(0).get("teman")).contains("rackvidd", "forbrukning");
    }

    @Test
    void radUtanLaddkopplingFallerMedEgenOrsak() {
        nyaRader(insikt(1293, "Auto Motor & Sport", "Hyundai", "Tucson",
                "Bakdörrarnas öppningsvinkel ökas från 73 till 83 grader, vilket underlättar insteg."));

        Map<String, Object> svar = service.hittaKandidater(1292L, 25);

        assertThat(kandidater(svar)).isEmpty();
        assertThat(avslag(svar)).containsEntry("ingen-laddkoppling", 1);
    }

    @Test
    void markeSomInteSaljerElbilarHarFallerAvAnnanOrsakAnSaknadLaddkoppling() {
        // Genesis GV90: laddsiffrorna är utmärkta, bilen går bara inte att köpa här.
        nyaRader(insikt(1287, "M3", "Genesis", "GV90",
                "Genesis GV90 har ett 123,5 kWh-batteri och kan laddas från 10 % till 80 % på 22 minuter med en 350 kW-laddare."));

        Map<String, Object> svar = service.hittaKandidater(1286L, 25);

        assertThat(kandidater(svar)).isEmpty();
        // Två sorters nej i samma siffra hade dolt vilket av dem som växer
        assertThat(avslag(svar)).containsEntry("marknad-saknas", 1).doesNotContainKey("ingen-laddkoppling");
    }

    @Test
    void kommandeModellFallerAvEgenOrsak() {
        when(upcoming.hiddenIds()).thenReturn(Set.of(1292L));
        nyaRader(insikt(1292, "Auto Motor & Sport", "Volvo", "EX60",
                "Bilen uppges klara 200 km räckvidd på ren el och laddar med 200 kW."));

        Map<String, Object> svar = service.hittaKandidater(1291L, 25);

        assertThat(kandidater(svar)).isEmpty();
        assertThat(avslag(svar)).containsEntry("kommande-modell", 1);
    }

    @Test
    void radUtanMarkeSlipperMarknadsfiltret() {
        // Ett faktum om ett laddnätverk har inget märke att pröva — och är ofta det bästa tipset.
        nyaRader(insikt(1300, "Elbilen", null, null,
                "Ionitys nya stationer laddar med upp till 400 kW och byggs ut längs E4."));

        List<Map<String, Object>> ut = kandidater(service.hittaKandidater(1299L, 25));

        assertThat(ut).hasSize(1);
        assertThat(ut.get(0).get("utanMarke")).isEqualTo(true);
    }

    @Test
    void sammaFaktumUrTvaKallorBlirEnRadMedBadaKallorna() {
        String text = "Laddhybriden C 400 e 4MATIC utvecklar upp till 395 hästkrafter, har ett 20 kWh-batteri "
                + "som ger cirka tio mil elektrisk räckvidd och kan laddas med 11 kW hemma eller 55 kW DC.";
        nyaRader(
                insikt(1277, "M3", "Mercedes", "C-Klass", text),
                insikt(1266, "Teknikens Värld", "Mercedes", "C 400 e 4MATIC", text.replace("cirka", "ca")));

        Map<String, Object> svar = service.hittaKandidater(1265L, 25);
        List<Map<String, Object>> ut = kandidater(svar);

        assertThat(ut).hasSize(1);
        assertThat(avslag(svar)).containsEntry("dubblett", 1);
        assertThat(ut.get(0)).containsKey("ocksaFran");
    }

    @Test
    void hogstaIdArVattenmarketForNastaKorning() {
        nyaRader(
                insikt(1297, "CarUp", "Volvo", "240", "Nya däck och bromsar, ingen laddning alls."),
                insikt(1240, "Auto Motor & Sport", "Audi", "Q6 e-tron", "Räckvidd 542 km uppmätt."));

        assertThat(service.hittaKandidater(1239L, 25).get("hogstaId")).isEqualTo(1297L);
    }

    @Test
    void tomtSvarSagerIfranIStalletForAttSeFrisktUt() {
        when(repo.findByIdGreaterThanOrderByIdDesc(anyLong(), any(Pageable.class))).thenReturn(List.of());

        Map<String, Object> svar = service.hittaKandidater(9999L, 25);

        // Ett tomt jobb som inte kan larma är precis det som lät cargo-parsern ligga död
        assertThat(svar).containsKey("varning");
        assertThat(svar.get("granskade")).isEqualTo(0);
    }

    @Test
    void matvardeMedHartMellanslagRaknasOchFramhavs() {
        // U+00A0 mellan tal och enhet: Javas \s matchar det inte, och utan teckenklassen
        // hade raden både tappat poäng och fått fetstilen på fel ställe.
        nyaRader(insikt(1240, "Auto Motor & Sport", "Audi", "Q6 e-tron",
                "Audi Q6 e-tron nådde 542 km med fulladdat batteri i Edmunds räckviddstest."));

        Map<String, Object> k = kandidater(service.hittaKandidater(1239L, 25)).get(0);

        assertThat((String) k.get("karusellrad")).contains("<strong>542 km</strong>");
        assertThat((int) k.get("poang")).isGreaterThan(1);
    }

    @Test
    void karusellradenBarKallanOchStaticFactsFormatet() {
        nyaRader(insikt(1240, "Auto Motor & Sport", "Audi", "Q6 e-tron",
                "Audi Q6 e-tron nådde 542 km med fulladdat batteri i Edmunds räckviddstest."));

        String rad = (String) kandidater(service.hittaKandidater(1239L, 25)).get(0).get("karusellrad");

        assertThat(rad).startsWith("{ icon: '").contains("text: '").endsWith("' },");
        assertThat(rad).contains("(Auto Motor & Sport).");
    }

    @Test
    void aterkallelseArIngenVissteDuAtt() {
        nyaRader(insikt(1301, "Vi Bilägare", "Tesla", "Model Y",
                "Tesla återkallar 12 000 bilar sedan laddluckan kunnat öppnas under färd."));

        Map<String, Object> svar = service.hittaKandidater(1300L, 25);

        assertThat(kandidater(svar)).isEmpty();
        assertThat(avslag(svar)).containsEntry("olamplig-som-tips", 1);
    }

    // --- De tre felen som bara det skarpa provet mot driftens riktiga rader hittade (08-22) ---

    @Test
    void bensinbilensLiterforbrukningArIngenLaddfakta() {
        // Temat "förbrukning" är drivmedelsblint: utan elmarkörskravet blev den här en kandidat.
        // (Driftens rad 1268 skrev "bränsleförbrukning" och faller redan på ordgränsen — här
        // står ordet fritt, vilket är det svårare fallet och det enda som provar elmarkören.)
        nyaRader(insikt(1268, "Teknikens Värld", "Kia", "K4 Sportswagon",
                "Provkörningen visar en förbrukning på 0,71 liter per mil för "
                        + "1,6 T-GDI-motorn med 180 hk, vilket är konkurrenskraftigt för en bensindriven kombi."));

        Map<String, Object> svar = service.hittaKandidater(1267L, 25);

        assertThat(kandidater(svar)).isEmpty();
        assertThat(avslag(svar)).containsEntry("inte-eldrift", 1);
    }

    @Test
    void epaInutiReparationskostnadArIngenRackvidd() {
        // "rEPArationskostnad" träffade nyckelordet epa utan ordgräns, och ett haverifall
        // om en trasig laddhybrid blev kandidat på temat räckvidd.
        nyaRader(insikt(1296, "CarUp", "Volvo", "XC90",
                "En 2019 års Volvo XC90 laddhybrid drabbades av ett fel i ERAD-enheten som ledde till "
                        + "att elmotorn kopplade ur, med en reparationskostnad på över 100 000 kronor."));

        Map<String, Object> svar = service.hittaKandidater(1295L, 25);

        assertThat(kandidater(svar)).isEmpty();
        assertThat(avslag(svar)).containsEntry("ingen-laddkoppling", 1);
    }

    @Test
    void markeSomFinnsIEvSpecMenInteISverigeFallerAnda() {
        // ev_spec bär tolv Zeekr-rader (mätt 08-22) — tabellen är europeisk, inte svensk,
        // och den första versionen av vakten släppte därför igenom precis den här raden.
        nyaRader(insikt(1288, "Auto Motor & Sport", "Zeekr", "001",
                "Zeekr 001 kan ladda med upp till 1 500 kW vid Geelys nya laddare, vilket ger extremt kort laddtid."));

        Map<String, Object> svar = service.hittaKandidater(1287L, 25);

        assertThat(kandidater(svar)).isEmpty();
        assertThat(avslag(svar)).containsEntry("marknad-saknas", 1);
    }

    @Test
    void okantMarkeFallerOppetOchSlippsIgenom() {
        // Listan är handhållen, så den MÅSTE falla öppet: ett tyst bortfiltrerat tips
        // kostar mer än ett falskt fynd som tar en rad att läsa bort.
        nyaRader(insikt(1400, "Elbilen", "Cupra", "Born",
                "Cupra Born laddar med upp till 170 kW och har ett 79 kWh-batteri."));

        assertThat(kandidater(service.hittaKandidater(1399L, 25))).hasSize(1);
    }

    @Test
    void radSomBaraNamnerLaddningFangasOcksa() {
        // Föll igenom hela temalistan fram till 08-22 trots att den blev ett av de två tips
        // som faktiskt lades in i karusellen — hittad av det skarpa provet, inte av fixturerna.
        nyaRader(insikt(1249, "CarUp", "Tesla", "Model S",
                "En Tesla Model S från 2017 som stod parkerad i sex månader fick ett urladdat "
                        + "12-voltsbatteri, vilket gjorde att bilen inte gick att låsa upp, starta eller "
                        + "ladda högvoltsbatteriet."));

        List<Map<String, Object>> ut = kandidater(service.hittaKandidater(1248L, 25));

        assertThat(ut).hasSize(1);
        assertThat((List<String>) ut.get(0).get("teman")).contains("laddning");
    }
}
