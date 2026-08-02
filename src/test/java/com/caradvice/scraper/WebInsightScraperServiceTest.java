package com.caradvice.scraper;

import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.service.UpcomingInsightService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebInsightScraperServiceTest {

    private WebInsightScraperService service() {
        return new WebInsightScraperService(mock(ExpertInsightRepository.class), mock(JdbcTemplate.class),
                mock(JobStatusService.class), mock(UpcomingInsightService.class));
    }

    @Test
    void tomtVaktsvarSkiljsFranTomLista() {
        // gpt-oss-120b är en reasoning-modell: med för snål tokenbudget svarar den 200 med
        // finish_reason=length och TOMT content (uppmätt 2026-07-31). Läses det som "inget var
        // irrelevant" blir vakten en tyst nolla och hela batchen sparas ofiltrerad.
        var service = service();
        assertThat(service.parseIndexesOrNull(groqResponse(""), "irrelevant")).isNull();
        assertThat(service.parseIndexesOrNull(groqResponse("inte json"), "irrelevant")).isNull();
        assertThat(service.parseIndexesOrNull(groqResponse("{\"irrelevant\":\"inte en array\"}"), "irrelevant")).isNull();

        // ...men en äkta tom lista är ett giltigt svar och ska släppa igenom allt
        assertThat(service.parseIndexesOrNull(groqResponse("{\"irrelevant\":[]}"), "irrelevant")).isEmpty();
        assertThat(service.parseIndexesOrNull(groqResponse("{\"irrelevant\":[0,2]}"), "irrelevant"))
                .containsExactlyInAnyOrder(0, 2);
    }

    @Test
    void dedupBeharSinFailOpenViaParseIndexes() {
        // dubblettfiltret ska fortsatt spara hellre än tappa vid trasigt svar
        assertThat(service().parseIndexes(groqResponse(""), "duplicates")).isEmpty();
        assertThat(service().parseIndexes(groqResponse("{\"duplicates\":[1]}"), "duplicates")).containsExactly(1);
    }

    @Test
    void relevanspromptenLaterSvensktPrisAvgora() {
        // DS N°8 stoppades 2026-07-31 trots startpris 849 900 kr i artikeln — ett svenskt
        // pris betyder att bilen säljs här, och då får vakten inte blockera den
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("svenskt pris i kronor")
                .contains("RELEVANT");
    }

    @Test
    void relevanspromptenTackerDeTreKandaBlindaFlackarna() {
        // A/B-mätning 2026-07-31: både 20b och 120b släppte igenom specialutgåvor, lyx-SUV:ar
        // och innehållslös designprosa. Med konkreta exempel gick 120b från 9/12 till 12/12 —
        // försvinner reglerna kommer läckorna tillbaka
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("1,5 miljoner")          // prisnivå för lyxbilar
                .contains("jubileums")             // specialutgåvor
                .contains("kontrollerbart");       // designprosa utan substans
    }

    @Test
    void relevanspromptenStopparSportbilarTrotsUtskrivetPris() {
        // En begagnad Porsche 911 ("över en miljon kronor") passerade båda vakterna
        // 2026-08-01. Två hål: svenskt-pris-regeln var skriven som ett generellt RELEVANT
        // och slog ut lyxpunkten, och 1,5-Mkr-riktmärket gällde bara nybilspris. Undantaget
        // är nu låst till invändningen "ny eller okänd" och sportbilar utesluts oavsett pris
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("ny eller okänd modell")   // prisundantaget är avgränsat
                .contains("Porsche 911")             // namngivet exempel enligt konventionen
                .contains("oavsett pris")
                .contains("nybilspris som avgör");   // begagnatpriset får inte rädda bilen
    }

    @Test
    void relevanspromptenSkiljerForseningFranKantFel() {
        // "Polestar 3 har drabbats av buggar som forsenat leveranserna" slapptes igenom
        // 2026-08-01 fast foretagsnyheter redan var uteslutna — en forsening sager inget om
        // bilen. Gransen mot kanda fel maste sta kvar: skrivs symtomet ut ska insikten sparas
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("FÖRSENINGAR")
                .contains("vilket symtom");        // det som skiljer kant fel fran foretagsnyhet
    }

    // ── 429-backoff: Groq säger själv hur länge vi ska vänta ───────────────────

    @Test
    void retryDelayFoljerRetryAfterHeadern() {
        assertThat(WebInsightScraperService.retryDelayMs("2.5", null, 0)).isEqualTo(2500);
    }

    @Test
    void retryDelayLaserVantetidenUrFelmeddelandetNarHeadernSaknas() {
        String body = "{\"error\":{\"message\":\"Rate limit reached, try again in 8.31s\"}}";
        assertThat(WebInsightScraperService.retryDelayMs(null, body, 0)).isEqualTo(8310);
    }

    @Test
    void retryDelayTolkarMinuterOchSekunder() {
        String body = "Rate limit reached for model, limit 1000 per day, try again in 2m59.56s";
        // 2m59.56s = 179 560 ms, men taket kapar — en enstaka lång gräns får inte binda hela synken
        assertThat(WebInsightScraperService.retryDelayMs(null, body, 0)).isEqualTo(60_000);
    }

    @Test
    void retryDelayFallerTillbakaPaTrappaNarGroqInteSagerNagot() {
        assertThat(WebInsightScraperService.retryDelayMs(null, "inget här", 0)).isEqualTo(10_000);
        assertThat(WebInsightScraperService.retryDelayMs(null, null, 2)).isEqualTo(30_000);
        // försök 4 och 5 (antalet höjdes till 5 den 2026-08-01) får inte spränga taket
        assertThat(WebInsightScraperService.retryDelayMs(null, null, 3)).isEqualTo(40_000);
        assertThat(WebInsightScraperService.retryDelayMs(null, null, 4)).isEqualTo(50_000);
    }

    @Test
    void retryDelayHallerSigOvanGolvet() {
        // Groq svarar ibland "try again in 0.05s" — hamra inte vidare direkt
        assertThat(WebInsightScraperService.retryDelayMs("0.05", null, 0)).isEqualTo(1_000);
    }

    @Test
    void retryDelayIgnorerarSkrapIHeadern() {
        assertThat(WebInsightScraperService.retryDelayMs("snart", "try again in 4s", 0)).isEqualTo(4_000);
    }

    private static String groqResponse(String content) {
        return """
            {"choices":[{"message":{"content":%s}}]}
            """.formatted(com.fasterxml.jackson.databind.node.TextNode.valueOf(content).toString());
    }

    @Test
    void parsarInsiktslista() {
        String content = """
            {"insights":[{"car_make":"Volvo","car_model":"XC40","fuel_type":"elbil","category":"suv",
            "insight":"Bra bil.","rating":8,"source_ref":""}]}
            """;
        List<JsonNode> result = service().parseInsightJson(groqResponse(content), "test");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("car_make").asText()).isEqualTo("Volvo");
        assertThat(result.get(0).path("rating").asInt()).isEqualTo(8);
    }

    @Test
    void hanterarMarkdownKodstaket() {
        String content = "```json\n{\"insights\":[{\"insight\":\"En insikt.\"}]}\n```";
        List<JsonNode> result = service().parseInsightJson(groqResponse(content), "test");
        assertThat(result).hasSize(1);
    }

    @Test
    void tomListaVidTrasigJson() {
        assertThat(service().parseInsightJson(groqResponse("inte json alls"), "test")).isEmpty();
        assertThat(service().parseInsightJson("{}", "test")).isEmpty();
        assertThat(service().parseInsightJson(groqResponse("{\"insights\":\"inte en array\"}"), "test")).isEmpty();
    }

    @Test
    void ogiltigKategoriOchDrivmedelBlirNull() {
        // Ferrari som "ekonomibil" förgiftade rekommendationsprompten — värden utanför whitelisten kastas
        assertThat(WebInsightScraperService.validOrNull("suv", Set.of("suv", "elbil"))).isEqualTo("suv");
        assertThat(WebInsightScraperService.validOrNull("SUV ", Set.of("suv"))).isEqualTo("suv");
        assertThat(WebInsightScraperService.validOrNull("sportbil", Set.of("suv", "elbil"))).isNull();
        assertThat(WebInsightScraperService.validOrNull("", Set.of("suv"))).isNull();
        assertThat(WebInsightScraperService.validOrNull(null, Set.of("suv"))).isNull();
    }

    @Test
    void mallEkoRaderIdentifieras() throws Exception {
        // AI:n ekade fältmallen som riktiga rader ("car_make car_model" / "insight") — 6 st hittades i DB
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(WebInsightScraperService.isTemplateEcho(
                mapper.readTree("{\"car_make\":\"car_make\",\"insight\":\"insight\"}"))).isTrue();
        assertThat(WebInsightScraperService.isTemplateEcho(
                mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"insight\"}"))).isTrue();
        assertThat(WebInsightScraperService.isTemplateEcho(
                mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"Bra bil.\"}"))).isFalse();
    }

    @Test
    void normaliseringIgnorerarSkiftlageOchInterpunktion() {
        assertThat(WebInsightScraperService.normalizeForCompare("Bra bil, 2 500 kg dragvikt!"))
                .isEqualTo(WebInsightScraperService.normalizeForCompare("bra bil 2500 kg dragvikt"));
        assertThat(WebInsightScraperService.normalizeForCompare("Räckvidd 63 mil."))
                .isNotEqualTo(WebInsightScraperService.normalizeForCompare("Räckvidd 53 mil."));
    }

    @Test
    void parsarDubblettIndex() {
        assertThat(service().parseDuplicateIndexes(groqResponse("{\"duplicates\":[0,2]}")))
                .containsExactlyInAnyOrder(0, 2);
        assertThat(service().parseDuplicateIndexes(groqResponse("```json\n{\"duplicates\":[]}\n```"))).isEmpty();
    }

    @Test
    void trasigtDedupSvarGerTomMangd() {
        // fail open — vid oparsbart svar sparas allt hellre än att insikter tappas
        assertThat(service().parseDuplicateIndexes(groqResponse("inte json"))).isEmpty();
        assertThat(service().parseDuplicateIndexes(groqResponse("{\"duplicates\":\"inte en array\"}"))).isEmpty();
        assertThat(service().parseDuplicateIndexes("{}")).isEmpty();
    }

    private static WebInsightScraperService serviceWithExisting(com.caradvice.model.ExpertInsight... existing) {
        var repo = mock(ExpertInsightRepository.class);
        org.mockito.Mockito.when(repo.findByMakePrefix(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(existing));
        return new WebInsightScraperService(repo, mock(JdbcTemplate.class), mock(JobStatusService.class), mock(com.caradvice.service.UpcomingInsightService.class));
    }

    @Test
    void exaktDubblettFiltrerasMotBefintliga() throws Exception {
        var service = serviceWithExisting(new com.caradvice.model.ExpertInsight(
                "CarUp", "BYD", "Shark", null, null, "Bilen har en maximal dragvikt på 2 500 kg.", null));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode dubblett = mapper.readTree(
                "{\"car_make\":\"BYD\",\"car_model\":\"Shark\",\"insight\":\"Bilen har en maximal dragvikt på 2500 kg!\"}");
        assertThat(service.filterKnownDuplicates(List.of(dubblett))).isEmpty();
    }

    @Test
    void insiktUtanModellEllerUtanBefintligaBehalls() throws Exception {
        var service = serviceWithExisting();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode utanModell = mapper.readTree("{\"car_make\":\"\",\"car_model\":\"\",\"insight\":\"Generell insikt.\"}");
        JsonNode nyBil = mapper.readTree("{\"car_make\":\"Kia\",\"car_model\":\"EV3\",\"insight\":\"Ny insikt.\"}");
        assertThat(service.filterKnownDuplicates(List.of(utanModell, nyBil))).hasSize(2);
    }

    @Test
    void dubblettMedAnnanMarkesStavningFiltreras() throws Exception {
        // AMG CLA 45 kom in dubbelt: "Mercedes-Benz CLA 45 4MATIC+" och "Mercedes AMG CLA 45 4Matic+"
        var service = serviceWithExisting(new com.caradvice.model.ExpertInsight(
                "TV", "Mercedes-Benz", "CLA 45 4MATIC+", null, null, "Bilen levererar 680 hk.", null));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode dubblett = mapper.readTree(
                "{\"car_make\":\"Mercedes\",\"car_model\":\"AMG CLA 45 4Matic+\",\"insight\":\"Bilen levererar 680 hk!\"}");
        assertThat(service.filterKnownDuplicates(List.of(dubblett))).isEmpty();
    }

    @Test
    void exaktUpprepningInomSammaBatchFiltreras() throws Exception {
        var service = serviceWithExisting();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode forsta = mapper.readTree(
                "{\"car_make\":\"Mini\",\"car_model\":\"Cooper Cabrio\",\"insight\":\"Billig som cabriolet.\"}");
        JsonNode upprepning = mapper.readTree(
                "{\"car_make\":\"Mini\",\"car_model\":\"Cooper Cabrio\",\"insight\":\"Billig, som cabriolet!\"}");
        assertThat(service.filterKnownDuplicates(List.of(forsta, upprepning))).hasSize(1);
    }

    @Test
    void insiktUtanMarkeSparasInte() throws Exception {
        // Insikter utan carMake visas aldrig (ExpertInsightService utesluter dem) — SAE-studier
        // och kändisnotiser utan bil kom ändå in i DB via scrapen
        var repo = mock(ExpertInsightRepository.class);
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class), mock(JobStatusService.class), mock(com.caradvice.service.UpcomingInsightService.class));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode utanMarke = mapper.readTree("{\"car_make\":\"\",\"insight\":\"Studie om återcirkulation.\"}");
        JsonNode medMarke = mapper.readTree("{\"car_make\":\"Volvo\",\"car_model\":\"EX30\",\"insight\":\"Bra bil.\"}");
        assertThat(service.saveInsights("TV", List.of(utanMarke, medMarke), null)).isEqualTo(1);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1))
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void kommandeModellSparasMenFlaggas() throws Exception {
        // Relevansvakten markerar noden i stället för att kasta insikten — raden sparas men
        // hålls borta från prompter och bilkort tills bilen går att köpa (el-GLA-fallet)
        var repo = mock(ExpertInsightRepository.class);
        var upcoming = mock(UpcomingInsightService.class);
        var sparad = new com.caradvice.model.ExpertInsight("TV", "Mercedes", "GLA", null, null, "Tre varianter.", null);
        org.springframework.test.util.ReflectionTestUtils.setField(sparad, "id", 77L);
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any())).thenReturn(sparad);
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class), mock(JobStatusService.class), upcoming);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kommande = mapper.readTree("{\"car_make\":\"Mercedes\",\"car_model\":\"GLA\","
                + "\"insight\":\"Tre varianter.\",\"" + WebInsightScraperService.UPCOMING_FIELD + "\":true}");

        assertThat(service.saveInsights("TV", List.of(kommande), null)).isEqualTo(1);
        org.mockito.Mockito.verify(upcoming).mark(77L);
    }

    @Test
    void vanligInsiktFlaggasInteSomKommande() throws Exception {
        var repo = mock(ExpertInsightRepository.class);
        var upcoming = mock(UpcomingInsightService.class);
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class), mock(JobStatusService.class), upcoming);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode vanlig = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX30\",\"insight\":\"Bra bil.\"}");

        assertThat(service.saveInsights("TV", List.of(vanlig), null)).isEqualTo(1);
        org.mockito.Mockito.verify(upcoming, org.mockito.Mockito.never())
                .mark(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markesbredInsiktUtanModellSparasInte() throws Exception {
        // Utan carModel hamnar raden i findForCarTitle:s makeOnly-hink och visas på VARJE bil av
        // märket — CarUps N47-dieselvarning hade annars dykt upp på ett BMW i4-kort
        var repo = mock(ExpertInsightRepository.class);
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class), mock(JobStatusService.class), mock(com.caradvice.service.UpcomingInsightService.class));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode utanModell = mapper.readTree(
                "{\"car_make\":\"BMW\",\"car_model\":\"\",\"insight\":\"N47-dieseln kan få kamkedjebrott.\"}");
        JsonNode medModell = mapper.readTree(
                "{\"car_make\":\"BMW\",\"car_model\":\"320d\",\"insight\":\"Kamkedjan bör kontrolleras vid köp.\"}");
        assertThat(service.saveInsights("CarUp", List.of(utanModell, medModell), null)).isEqualTo(1);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1))
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sammaBilPaTokenDelmangd() {
        assertThat(WebInsightScraperService.sameCar("CLA 45 4MATIC+", "AMG CLA 45 4Matic+")).isTrue();
        assertThat(WebInsightScraperService.sameCar("EV4", "EV4 AWD")).isTrue();
        assertThat(WebInsightScraperService.sameCar("Shark", "Shark")).isTrue();
        assertThat(WebInsightScraperService.sameCar("XC40", "XC60")).isFalse();
        assertThat(WebInsightScraperService.sameCar("", "Shark")).isFalse();
        assertThat(WebInsightScraperService.sameCar("Shark", null)).isFalse();
    }

    @Test
    void dedupPromptListarBefintligaOchIndexeradeKandidater() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kandidat = mapper.readTree(
                "{\"car_make\":\"BYD\",\"car_model\":\"Shark\",\"insight\":\"Blade-batteriet är på 32,2 kWh.\"}");
        String user = WebInsightScraperService.buildDedupUserContent(
                List.of(kandidat), java.util.Map.of("BYD Shark", List.of("Batteriet på 32,2 kWh är stort.")));
        assertThat(user).contains("BYD Shark:")
                .contains("- Batteriet på 32,2 kWh är stort.")
                .contains("0 (BYD Shark): Blade-batteriet är på 32,2 kWh.");
    }

    @Test
    void parsarIrrelevantIndex() {
        assertThat(service().parseIndexes(groqResponse("{\"irrelevant\":[1,3]}"), "irrelevant"))
                .containsExactlyInAnyOrder(1, 3);
        // fail open — trasigt svar får aldrig kasta bort hela batchen
        assertThat(service().parseIndexes(groqResponse("inte json"), "irrelevant")).isEmpty();
        assertThat(service().parseIndexes(groqResponse("{\"irrelevant\":\"nej\"}"), "irrelevant")).isEmpty();
    }

    @Test
    void relevansPromptListarIndexeradeInsikter() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode ins = mapper.readTree(
                "{\"car_make\":\"Nissan\",\"car_model\":\"Tekton\",\"insight\":\"Säljs enbart i Afrika och Mellanöstern.\"}");
        String user = WebInsightScraperService.buildRelevanceUserContent(List.of(ins));
        assertThat(user).contains("0 (Nissan Tekton): Säljs enbart i Afrika och Mellanöstern.");
    }

    @Test
    void relevansvaktenSlapperIgenomAlltUtanApiNyckel() throws Exception {
        // apiKey är null i testtjänsten — vakten ska då vara helt passiv (fail open)
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode ins = mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"Bra bil.\"}");
        assertThat(service().filterIrrelevant(List.of(ins))).hasSize(1);
        assertThat(service().filterIrrelevant(List.of())).isEmpty();
    }

    @Test
    void extravaktenGallerBaraStriktaKallor() throws Exception {
        // CarUp läckte USA-modeller (Cadillac SRX) och EPA-siffror tre auditer i rad —
        // övriga källor ska inte betala för ett extra Groq-anrop
        assertThat(WebInsightScraperService.STRICT_SOURCES).containsExactly("CarUp");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<JsonNode> insikter = List.of(mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"Bra bil.\"}"));
        assertThat(service().filterStrict("Teknikens Värld", insikter)).isSameAs(insikter);
    }

    @Test
    void extravaktenProvarInteKommandeModeller() throws Exception {
        // Extravaktens första regel ("går inte att köpa i Sverige idag") är negationen av
        // relevansvaktens KOMMANDE-definition. Nattkörningen 2026-08-02 flaggade Audi Q9,
        // Zeekr 9X, Q6 e-tron och GLC Electric som kommande — extravakten dödade alla fyra,
        // och eftersom CarUp är enda strikta källan kunde insight_upcoming aldrig få en rad.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kommande = mapper.readTree(
                "{\"car_make\":\"Audi\",\"car_model\":\"Q9\",\"insight\":\"5,31 m lång.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        assertThat(WebInsightScraperService.isUpcoming(kommande)).isTrue();
        // En batch som bara innehåller kommande modeller ska aldrig nå vakten
        List<JsonNode> baraKommande = List.of(kommande);
        assertThat(service().filterStrict("CarUp", baraKommande)).isSameAs(baraKommande);
    }

    @Test
    void extravaktenBevararOrdningenNarKommandeBlandas() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode vanlig = mapper.readTree("{\"car_make\":\"Volvo\",\"car_model\":\"XC90\",\"insight\":\"Fem meter lång.\"}");
        JsonNode kommande = mapper.readTree(
                "{\"car_make\":\"Audi\",\"car_model\":\"Q9\",\"insight\":\"5,31 m lång.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        // apiKey är null → vakten är passiv, så allt ska tillbaka i oförändrad ordning
        assertThat(service().filterStrict("CarUp", List.of(vanlig, kommande, vanlig)))
                .containsExactly(vanlig, kommande, vanlig);
    }

    @Test
    void striktKallaSparasFortfarandeNarVaktenArPassiv() throws Exception {
        // apiKey är null i testtjänsten → extravakten ska vara passiv, inte blockera CarUp helt
        var repo = mock(ExpertInsightRepository.class);
        var service = new WebInsightScraperService(repo, mock(JdbcTemplate.class), mock(JobStatusService.class), mock(com.caradvice.service.UpcomingInsightService.class));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode ins = mapper.readTree(
                "{\"car_make\":\"Volkswagen\",\"car_model\":\"Arteon\",\"insight\":\"Mest begagnade är laddhybrider.\"}");
        assertThat(service.saveInsights("CarUp", List.of(ins), null)).isEqualTo(1);
    }

    @Test
    void statusradenSkiljerNollNyaFranTystMisslyckadKalla() {
        // "0" i scrape-status betydde både "allt fungerade, dedupen tog allt" och "hittade
        // ingenting att skrapa" — en layoutändring hos källan kunde gå obemärkt förbi
        assertThat(WebInsightScraperService.SourceResult.of(0).label()).isEqualTo("0");
        assertThat(WebInsightScraperService.SourceResult.of(3).label()).isEqualTo("3");
        assertThat(new WebInsightScraperService.SourceResult(0, "INGA LANKAR (0 artikel-URL:er)").label())
                .isEqualTo("INGA LANKAR (0 artikel-URL:er)");
    }

    @Test
    void parsarWpJsonLankar() throws Exception {
        String json = """
            [{"link":"https:\\/\\/elbilen.se\\/mazda-pressar-priset\\/"},
             {"link":"https:\\/\\/elbilen.se\\/tesla-analys\\/"},
             {"other":"fält utan link ignoreras"}]
            """;
        assertThat(service().parseWpJsonLinks(json)).containsExactly(
                "https://elbilen.se/mazda-pressar-priset/",
                "https://elbilen.se/tesla-analys/");
    }

    @Test
    void tomWpJsonGerTomLankLista() throws Exception {
        assertThat(service().parseWpJsonLinks("[]")).isEmpty();
        assertThat(service().parseWpJsonLinks("{}")).isEmpty();
    }

    @Test
    void varningDoljerInteAntaletSparadeInsikter() {
        // en magert levererande källa kan ändå ha gett träffar — siffran får inte försvinna
        assertThat(new WebInsightScraperService.SourceResult(2, "MAGERT UTBUD (3 artikel-URL:er)").label())
                .isEqualTo("2 (MAGERT UTBUD (3 artikel-URL:er))");
        assertThat(new WebInsightScraperService.SourceResult(0, "MAGERT UTBUD (3 artikel-URL:er)").label())
                .isEqualTo("MAGERT UTBUD (3 artikel-URL:er)");
    }

    @Test
    void elbilenPekarPaEgnaPosttyperInteStandardPosts() {
        // elbilen.se/wp-json/wp/v2/posts innehåller 3 poster totalt — allt redaktionellt
        // ligger i posttyperna tester/artiklar. Källan svalt tyst tills detta upptäcktes.
        String url = WebInsightScraperService.sourceByName("Elbilen").url();
        assertThat(url).contains("/wp/v2/tester").contains("/wp/v2/artiklar");
        assertThat(url).doesNotContain("/wp/v2/posts");
    }

    @Test
    void carInfoArBorttagenSomKalla() {
        // JS-renderat filterskal utan omdömestext — sparade aldrig en insikt
        assertThat(WebInsightScraperService.sourceByName("Bilägare (car.info)")).isNull();
    }

    // --- vägen tillbaka för en sedd nyckel ---

    @Test
    void tappadArtikelKanGlommasOchLasasOm() {
        // carup.se/skrackljud-i-volvos-motor markerades som läst efter en rate limit
        // 2026-08-01 och var därmed permanent förlorad — seedSeen kunde bara lägga TILL
        var jdbc = mock(JdbcTemplate.class);
        var service = serviceWith(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(service.forgetSeen("https://carup.se/skrackljud-i-volvos-motor", false)).isEqualTo(1);
        verify(jdbc).update("DELETE FROM web_insight_seen WHERE seen_key = ?",
                "https://carup.se/skrackljud-i-volvos-motor");
    }

    @Test
    void prefixmatchningEskaperarUnderstreckIUrler() {
        // _ är jokertecken i LIKE och vanligt i URL:er — oeskapat raderar prefixet
        // "…/volvo_xc90" även raden "…/volvoaxc90" från en helt annan artikel
        var jdbc = mock(JdbcTemplate.class);
        var service = serviceWith(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);

        assertThat(service.forgetSeen("https://carup.se/volvo_xc90", true)).isEqualTo(3);
        verify(jdbc).update("DELETE FROM web_insight_seen WHERE seen_key LIKE ? ESCAPE '\\'",
                "https://carup.se/volvo\\_xc90%");
    }

    @Test
    void kortPrefixFarInteTommaHelaTabellen() {
        var service = serviceWith(mock(JdbcTemplate.class));
        assertThatThrownBy(() -> service.forgetSeen("h", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forgetSeen("  ", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forgetSeen(null, false)).isInstanceOf(IllegalArgumentException.class);
        // exakt matchning har ingen längdgräns — en kort nyckel träffar ändå bara en rad
        assertThatCode(() -> service.forgetSeen("h", false)).doesNotThrowAnyException();
    }

    // --- döda länkar och loggbredd ---

    @Test
    void baraBortagnaArtiklarMarkerasSomLasta() {
        // en död länk räknas mot MAX_ARTICLES_PER_SOURCE varje natt och stjäl plats från en
        // läsbar artikel — men 403 (botblockering) och 5xx kan släppa igen och måste få nytt försök
        assertThat(WebInsightScraperService.isPermanentlyGone(404)).isTrue();
        assertThat(WebInsightScraperService.isPermanentlyGone(410)).isTrue();
        assertThat(WebInsightScraperService.isPermanentlyGone(403)).isFalse();
        assertThat(WebInsightScraperService.isPermanentlyGone(429)).isFalse();
        assertThat(WebInsightScraperService.isPermanentlyGone(503)).isFalse();
    }

    @Test
    void stoppadeInsikterLoggasIHelhet() {
        // Blockerade insikter sparas aldrig, så loggraden är hela underlaget för att avgöra
        // om vakten dömde rätt. Vid 80 tecken kapades texten mitt i meningen och utredningen
        // av Polestar 3/Volvo V70 fick A/B-testa mot Groq i stället för att läsa loggen.
        String insikt = "Polestar 3 har drabbats av flera mjukvarufel: skärmen fryser vid start och "
                + "farthållaren slår av sig själv, men tre OTA-uppdateringar har åtgärdat det mesta "
                + "enligt ägarna.";
        assertThat(insikt.length()).isBetween(80, WebInsightScraperService.LOG_INSIGHT_CHARS);
        assertThat(WebInsightScraperService.truncate(insikt, WebInsightScraperService.LOG_INSIGHT_CHARS))
                .isEqualTo(insikt);
    }

    private WebInsightScraperService serviceWith(JdbcTemplate jdbc) {
        return new WebInsightScraperService(mock(ExpertInsightRepository.class), jdbc,
                mock(JobStatusService.class), mock(UpcomingInsightService.class));
    }
}
