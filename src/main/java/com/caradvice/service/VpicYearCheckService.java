package com.caradvice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Oberoende kontroll av årtalen i {@code ice_generation} mot NHTSA:s öppna fordonsregister vPIC.
 *
 * <p><b>Varför den finns.</b> {@code ice_generation} fylls från en enda källa, och 2026-08-18
 * visade sig två av 172 rader bära fel generation: "Volvo s90" stod som <b>1997</b> och
 * "Volvo v90" som <b>1996</b> — den ombadgade 960:an — medan vår motorlista beskriver 2016 års
 * bil. Felet hittades genom att en människa ögnade listan. Räknaren i {@code cargo-coverage}
 * kunde aldrig ha avslöjat det, för den räknar <i>rader</i> och felet satt i <i>värdet</i>. Det
 * är samma blindhet som täckningsmätaren hade när bagageparsern var död.
 *
 * <p><b>Signalen vPIC bär.</b> {@code GetModelsForMakeYear} svarar med de modeller som såldes i
 * USA ett givet modellår. Ställs frågan för flera år framträder generationsbytet som ett
 * <b>hål i årsserien</b>: S90 finns 1998, saknas 1999–2016 och kommer tillbaka 2017. Ligger vårt
 * årtal <i>före</i> ett sådant hål pekar det på en äldre generation än den som säljs i dag.
 *
 * <p><b>Att modellen saknas är i sig inget fynd — det är ankaret som fäller.</b> Nästan alla
 * europeiska modeller saknas i vPIC <i>alla</i> år, så en frånvaro säger ingenting. Vakten kräver
 * därför två saker: att modellen saknas åren strax efter vårt årtal, OCH att den dyker upp igen
 * senare. Först då finns ett hål att peka på. Utan det andra villkoret hade varenda Škoda och
 * Renault rapporterats som en avvikelse.
 *
 * <p><b>Den är en VAKT, inte en källa — och det är ingen detalj.</b> Den skriver aldrig ett
 * årtal, av två skäl som båda är mätta:
 * <ul>
 *   <li><b>Amerikanska modellår ligger före.</b> S90:s andra generation är 2016 här och 2017
 *       där. Skrevs vPIC:s år rakt in hade varje S90 från 2016 tystats — och ett för högt årtal
 *       är den dyra riktningen, precis som {@code basgenerationsStartAr} beskriver.</li>
 *   <li><b>Täckningen är amerikansk.</b> Mätt 2026-08-18 för modellåret 2023 har vPIC
 *       <b>noll</b> modeller för Škoda, Renault, Dacia och Cupra, en för Opel, en för Citroën,
 *       två för SEAT och fyra för Peugeot. Vår CSV har 212 motorrader i just de märkena. Även
 *       BMW faller utanför: våra rader heter "BMW 320d" medan vPIC listar "3-Series".</li>
 * </ul>
 *
 * <p><b>Därför är {@link Status#INGEN_DATA} en egen utgång och aldrig ett godkännande.</b> Att
 * slå ihop "vPIC håller med" med "vPIC vet ingenting" hade gjort rapporten värdelös på det sätt
 * som kostat det här projektet mest: två sorters svar i samma siffra döljer vilket av dem som
 * växer. Se {@code IceGenerationService.missarPerOrsak} för samma lärdom en gång tidigare.
 *
 * <p><b>Varför den inte är ett nattjobb.</b> Den läser 170 rader och skulle larma varje morgon
 * på de ~100 modeller vPIC inte känner till. En avvikelse som är falsk varje dag gör att
 * rapporten slutar läsas — samma skäl som {@code SourceResult} medvetet saknar varning på
 * "0 av N lästa". Den körs när någon frågar, och svarar då på en fråga som ställts med flit.
 */
@Service
public class VpicYearCheckService {

    private static final Logger log = LoggerFactory.getLogger(VpicYearCheckService.class);

    private static final String BAS = "https://vpic.nhtsa.dot.gov/api/vehicles/GetModelsForMakeYear";

    /**
     * Åren efter vårt påstådda startår som provas för att hitta ett hål i serien.
     *
     * <p><b>Två prov och inte ett.</b> vPIC saknar enstaka år av rena dataskäl, och ett ensamt
     * missat år hade då sett ut som ett generationsbyte. Två prov tre år isär kräver ett
     * <i>ihållande</i> uppehåll — ett riktigt generationshål är flera decennier långt
     * (S90 saknas 1999–2016), aldrig tre år.
     *
     * <p><b>Varför inte år ett och två.</b> En generation lever 6–8 år, så tre respektive sex år
     * efter starten ska bilen fortfarande finnas. Ett kortare avstånd hade riskerat att träffa
     * ett faceliftglapp i stället för ett generationsbyte.
     */
    static final int[] PROV_AR_EFTER = { 3, 6 };

    /**
     * Steget i ankarstegen — åren efter provfönstret där modellen letas upp igen.
     *
     * <p><b>Ankaret är det som gör tystnaden till ett fynd.</b> Att modellen saknas åren efter
     * vårt årtal betyder ingenting i sig: nästan alla europeiska modeller saknas i vPIC ALLA år.
     * Först när den dyker upp <i>senare</i> finns ett hål, och ett hål är ett generationsbyte.
     *
     * <p><b>Ankaret får inte vara "i år".</b> Första utkastet 2026-08-18 använde innevarande år
     * som grind, och då föll Volvo V90 — som såldes i USA 2017–2021 men inte längre — ut som
     * "ingen data", trots att den bar exakt samma fel som S90 (1996 i stället för 2016). Mätt
     * samma dag: med stegen nedan hittas ankaret 2017 och båda raderna fälls. En grind på ett
     * enda år hade alltså missat hälften av det den byggdes för.
     */
    static final int ANKARE_STEG_AR = 3;

    /** Hur många vPIC-anrop en granskning får kosta. Träffas taket rapporteras det, aldrig tyst. */
    static final int MAX_ANROP = 400;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Utfallet för en modell. */
    public enum Status {
        /** vPIC har modellen i dag och ser inget hål efter vårt årtal. */
        OK,
        /** vPIC har modellen i dag men saknar den i åren strax efter vårt årtal — troligen fel generation. */
        AVVIKER,
        /** vPIC känner inte modellen i dag. Ingen åsikt — nästan alla europeiska märken hamnar här. */
        INGEN_DATA
    }

    public record Dom(String model, Integer vartArtal, Status status, String kommentar) {}

    /** Svaret från en granskning: domarna plus vad körningen faktiskt hann göra. */
    public record Rapport(int kontrollerade, int hoppade, int anrop,
                          Map<String, Long> perStatus, List<Dom> avvikelser) {}

    /**
     * Granskar rader ur {@code ice_generation} mot vPIC.
     *
     * @param rader  raderna från {@code IceGenerationService.lista()} ({@code model}, {@code franAr})
     * @param marke  valfritt märkesfilter, t.ex. "Volvo" — null granskar alla
     * @param split  slår upp märke + modellord ur modellnamnet (se
     *               {@code IceConsumptionService.delaModellnamn})
     */
    public Rapport granska(List<Map<String, Object>> rader, String marke,
                           java.util.function.Function<String, String[]> split) {
        Map<String, Set<String>> cache = new HashMap<>();
        int[] anrop = { 0 };
        int kontrollerade = 0, hoppade = 0;
        Map<Status, Long> rakning = new LinkedHashMap<>();
        List<Dom> avvikelser = new ArrayList<>();

        for (Map<String, Object> rad : rader) {
            String model = (String) rad.get("model");
            Object ar = rad.get("franAr");
            if (model == null || !(ar instanceof Number)) continue;

            String[] delar = split.apply(model);
            if (delar == null) {
                // Modellnamnet finns inte längre i ice_consumption — en rad som blivit
                // föräldralös, inte ett årtalsfel. Egen kommentar så den inte läses som ett nej.
                rakning.merge(Status.INGEN_DATA, 1L, Long::sum);
                kontrollerade++;
                continue;
            }
            if (marke != null && !marke.equalsIgnoreCase(delar[0])) continue;

            if (anrop[0] >= MAX_ANROP) { hoppade++; continue; }

            Dom dom = granskaEn(delar[0], delar[1], ((Number) ar).intValue(), cache, anrop);
            kontrollerade++;
            rakning.merge(dom.status(), 1L, Long::sum);
            if (dom.status() == Status.AVVIKER) avvikelser.add(dom);
        }

        Map<String, Long> perStatus = new LinkedHashMap<>();
        for (Status s : Status.values()) perStatus.put(s.name(), rakning.getOrDefault(s, 0L));

        log.info("vPIC-årtalskoll: {} kontrollerade, {} avvikelser, {} utan data, {} hoppade, {} anrop",
                kontrollerade, perStatus.get(Status.AVVIKER.name()),
                perStatus.get(Status.INGEN_DATA.name()), hoppade, anrop[0]);
        return new Rapport(kontrollerade, hoppade, anrop[0], perStatus, avvikelser);
    }

    /** Domen för en modell. Paketsynlig så logiken går att pröva utan HTTP. */
    Dom granskaEn(String marke, String modellord, int vartArtal,
                  Map<String, Set<String>> cache, int[] anrop) {
        String modell = normalisera(modellord);
        String helaNamnet = marke + " " + modellord;
        int idag = LocalDate.now().getYear();

        // Steg 1: fanns modellen åren strax efter vårt påstådda startår? Hittas den där är
        // årtalet förenligt med vPIC och vi är klara — det är den billiga och vanliga vägen,
        // ETT anrop för en riktig rad.
        List<Integer> saknade = new ArrayList<>();
        for (int steg : PROV_AR_EFTER) {
            int ar = vartArtal + steg;
            if (ar > idag) continue;                    // framtiden bevisar ingenting
            if (finns(marke, modell, ar, cache, anrop)) {
                return new Dom(helaNamnet, vartArtal, Status.OK,
                        "vPIC har modellen " + ar + ", alltså efter vårt årtal " + vartArtal);
            }
            saknade.add(ar);
        }
        // Ett årtal så färskt att proven ligger i framtiden går inte att döma.
        if (saknade.isEmpty()) {
            return new Dom(helaNamnet, vartArtal, Status.INGEN_DATA,
                    "årtalet " + vartArtal + " är för färskt — provåren ligger i framtiden");
        }

        // Steg 2: ANKARET. Att modellen saknas betyder ingenting i sig — nästan alla europeiska
        // modeller saknas i vPIC alla år. Först när den dyker upp SENARE finns ett hål, och ett
        // hål är ett generationsbyte. Stegen går framåt till innevarande år och stannar vid
        // första träffen.
        int forstaAnkare = saknade.get(saknade.size() - 1) + ANKARE_STEG_AR;
        for (int ar = forstaAnkare; ar <= idag; ar += ANKARE_STEG_AR) {
            if (!finns(marke, modell, ar, cache, anrop)) continue;
            return new Dom(helaNamnet, vartArtal, Status.AVVIKER,
                    "vPIC saknar modellen " + saknade + " men har den " + ar
                            + " — vårt årtal " + vartArtal + " ligger troligen före ett generationsbyte");
        }

        // Ingen träff någonstans efter vårt årtal: vPIC känner helt enkelt inte modellen.
        // Det är den vanligaste utgången och den säger ingenting om raden.
        return new Dom(helaNamnet, vartArtal, Status.INGEN_DATA,
                "vPIC har ingen " + marke + " " + modellord + " efter " + vartArtal
                        + " — amerikanskt register, ingen åsikt");
    }

    /** Finns modellen hos vPIC det året? Cachen är per märke och år — samma år delas av många rader. */
    private boolean finns(String marke, String normaliseratModellord, int ar,
                          Map<String, Set<String>> cache, int[] anrop) {
        String nyckel = marke.toLowerCase() + "|" + ar;
        Set<String> modeller = cache.get(nyckel);
        if (modeller == null) {
            modeller = hamtaModeller(marke, ar);
            anrop[0]++;
            cache.put(nyckel, modeller);
        }
        return modeller.contains(normaliseratModellord);
    }

    /**
     * Modellnamnen vPIC har för märket och året, normaliserade.
     *
     * <p>Paketsynlig så testerna kan mata in ett svar utan att gå ut på nätet.
     *
     * <p><b>Ett fel ger tom mängd med flit.</b> vPIC är ett gratis register utan avtal, och det
     * går ner ibland. En tom mängd betyder att steg 1 svarar {@link Status#INGEN_DATA} — alltså
     * "ingen åsikt", aldrig en avvikelse. Att låta ett nätverksfel producera ett fynd hade varit
     * exakt fail-soft-fällan som en gång parkerade 139 modeller i 30 dagar.
     */
    Set<String> hamtaModeller(String marke, int ar) {
        String url = BAS + "/make/" + URLEncoder.encode(marke, StandardCharsets.UTF_8)
                + "/modelYear/" + ar + "?format=json";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> svar = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (svar.statusCode() != 200) {
                log.warn("vPIC svarade {} för {} {}", svar.statusCode(), marke, ar);
                return Set.of();
            }
            return modellerUrSvar(svar.body());
        } catch (Exception e) {
            log.warn("vPIC {} {} misslyckades — {}", marke, ar, e.getMessage());
            return Set.of();
        }
    }

    /** {@code {"Results":[{"Model_Name":"S90"}, …]}} → normaliserade modellord. */
    Set<String> modellerUrSvar(String json) {
        Set<String> ut = new HashSet<>();
        try {
            JsonNode resultat = mapper.readTree(json).path("Results");
            if (!resultat.isArray()) return ut;
            for (JsonNode rad : resultat) {
                String namn = rad.path("Model_Name").asText(null);
                if (namn != null && !namn.isBlank()) ut.add(normalisera(namn));
            }
        } catch (Exception e) {
            log.warn("vPIC: svaret gick inte att tolka — {}", e.getMessage());
        }
        return ut;
    }

    /**
     * Gemener utan skiljetecken: "V60 CC" och "v60-cc" blir samma ord.
     *
     * <p>Jämförelsen är <b>exakt</b> efter normaliseringen och aldrig ungefärlig. En delsträngs-
     * matchning hade låtit "S90" träffa "S90 L" och tvärtom, och en vakt som fäller på fel
     * grund är värre än ingen vakt: hela värdet ligger i att en avvikelse betyder något.
     */
    static String normalisera(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
