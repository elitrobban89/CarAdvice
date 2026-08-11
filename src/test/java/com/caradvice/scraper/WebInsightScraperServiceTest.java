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
    void kommandevaktenArEttEgetAnropEfterRelevansvakten() throws Exception {
        // Trevägsbeslutet var instabilt på KOMMANDE-gränsen: natten 2026-08-07 kastades fem
        // EX60-rader som IRRELEVANTA trots att bilen säljs här, och A/B på samma batch gav
        // KOMMANDE i en körning och direkt användbar i två. Frågorna ställs därför var för sig.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        List<String> prompter = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            String prompt = inv.getArgument(1);
            prompter.add(prompt);
            // relevansvakten fäller index 0; kommandevakten ser bara de två överlevande och
            // parkerar den andra av dem (EX50)
            return groqResponse(prompt.equals(WebInsightScraperService.UPCOMING_PROMPT)
                    ? "{\"upcoming\":[1]}" : "{\"irrelevant\":[0]}");
        }).when(service).postGroq(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode skrot = mapper.readTree(
                "{\"car_make\":\"Dodge\",\"car_model\":\"Charger\",\"insight\":\"Restomod med 650 hk.\"}");
        JsonNode ex60 = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX60\",\"insight\":\"5G-uppkopplingen slutade fungera.\"}");
        JsonNode ex50 = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX50\",\"insight\":\"Säljstart 2027.\"}");

        assertThat(service.filterIrrelevant(List.of(skrot, ex60, ex50))).containsExactly(ex60, ex50);
        // EX60 är köpbar och ska INTE parkeras — det var precis förlusten fixen byggdes mot
        assertThat(WebInsightScraperService.isUpcoming(ex60)).isFalse();
        assertThat(WebInsightScraperService.isUpcoming(ex50)).isTrue();
        assertThat(prompter).containsExactly(
                WebInsightScraperService.RELEVANCE_PROMPT, WebInsightScraperService.UPCOMING_PROMPT);
    }

    @Test
    void kommandevaktenParkerarChunkenIStalletForAttTappaDen() throws Exception {
        // Relevansvakten är fail-closed och kastar hela chunken vid oläsbart svar. Kommande-
        // vakten får inte göra det: raderna är redan godkända, så ett Groq-hicka skulle
        // återinföra exakt den förlust fixen finns för. De parkeras i kön i stället.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        org.mockito.Mockito.doAnswer(inv -> groqResponse(
                WebInsightScraperService.UPCOMING_PROMPT.equals(inv.getArgument(1))
                        ? "inte json alls" : "{\"irrelevant\":[]}"))
                .when(service).postGroq(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode ins = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX60\",\"insight\":\"Räckvidden är 55-60 mil.\"}");

        assertThat(service.filterIrrelevant(List.of(ins))).containsExactly(ins);
        assertThat(WebInsightScraperService.isUpcoming(ins)).isTrue();
    }

    @Test
    void relevanspromptenAvgorInteLangreKommande() {
        // Hela poängen med uppdelningen: relevansprompten ska inte längre resonera om
        // tillgänglighet, och kommande-prompten ska inte kunna kasta något.
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .doesNotContain("\"upcoming\"")
                .contains("avgörs i ett separat steg");
        assertThat(WebInsightScraperService.UPCOMING_PROMPT)
                .doesNotContain("\"irrelevant\"")
                // gränsen användaren drog 2026-08-07: leveranser, inte nyhetsvärde
                .contains("Gränsen går vid leveranserna")
                .contains("EX50 med säljstart 2027");
    }

    @Test
    void relevanspromptenUtesluterInteLangrePaTillganglighet() {
        // Natten 2026-08-08 gav NOLL insikter: uppdelningen i bb6a4e4 flyttade
        // tillgängligheten till steg 2 men lät steg 1 behålla sin egen hårda regel
        // ("Kravet är hårt: konkreta uppgifter gör INTE en bil relevant om läsaren inte
        // kan köpa den"). Tre Audi A2 e-tron-rader kastades därför innan kommande-vakten
        // ens kördes — markUpcomingRows returnerar direkt på tom lista, så kön kunde
        // aldrig växa. Kommer meningen tillbaka svälter kön igen.
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .doesNotContain("Kravet är hårt")
                .contains("INGEN uteslutningsgrund")
                // A/B 2026-08-08: osäkerhetsregeln pekar åt behåll-hållet, kön är reversibel
                .contains("BEHÅLL raden");
    }

    @Test
    void avvecklandeModellMedBegagnatmarknadArRelevant() {
        // "modeller som lämnat den svenska marknaden" stod som IRRELEVANT-grund och hade kastat
        // varje insikt om Renault Zoe — tillverkad 2012 till mars 2024, ersatt av Renault 5, men
        // med 49 exemplar på Blocket från 58 000 kr. Läsaren köper begagnat, så en avvecklad
        // modell med levande begagnatmarknad är bland det mest användbara som finns. Samma
        // familj av fel som tillgänglighetsregeln 2026-08-08: en rimlig regel som råkade döda
        // rätt rader.
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .doesNotContain("modeller som lämnat den svenska marknaden")
                .contains("SLUTAT tillverkas är däremot INGEN")
                .contains("aldrig sålts här");
    }

    @Test
    void relevanspromptenStopparModellspecifikFordonsskatt() {
        // Natten 2026-08-10 sparades två skatterader (V60 Recharge 360 kr/år, Cayenne Turbo
        // E-Hybrid 2 714 kr/år) fast "skatter" redan stod i uteslutningslistan — vakten läste
        // ett belopp knutet till en enskild modell som en egenskap hos bilen. Beloppet följer
        // av regelverket och ändras med det, medan priset på bilen måste förbli RELEVANT
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("fordonsskatt")
                .contains("ingen egenskap hos bilen")
                .contains("Bilens pris, förbrukning och CO2-värde är däremot egenskaper");
    }

    @Test
    void relevanspromptenStopparRenoveringsobjekt() {
        // "Volvo 240 kan återställas till körglädje för omkring 38 000 kronor" släpptes igenom
        // 2026-08-10 trots att veteran-/samlarbilar och entusiastombyggnader var uteslutna —
        // texten handlar om ett renoveringsprojekt, inte om en bil någon kan köpa och köra
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("renoveringsobjekt")
                .contains("Volvo 240");
    }

    @Test
    void relevanspromptenSkiljerKonceptbilFranPresenteradModell() {
        // A/B 2026-08-08: A2 e-tron-raderna stoppades 3/3 även ensamma i sin batch tills
        // gränsen skrevs ut — "preliminär energiförbrukning" lästes som konceptbil av
        // prototypregeln, alltså en andra väg till samma bortfall
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("studie utan produktionsbeslut")
                .contains("ingen konceptbil");
    }

    @Test
    void utmarkelseundantagetKraverForstaplatsSvenskMarknadOchAktualitet() {
        // Undantaget ("mest sålda bilen i sin klass" är en köpsignal) var den bredaste
        // oskyddade läckvägen i prompten och släppte in tre olika sorters skräp:
        //   2026-08-06  danska nyregistreringar från juli 2024 (id 1174-1176) — fel marknad OCH gammalt
        //   2026-08-11  amerikansk iSeeCars-värdering (id 1212) — fel marknad
        //   2026-08-11  CarUp:s begagnattopplista, enskilda placeringar (id 1182/1184/1185/1186)
        // Användaren drog gränsen 08-11 genom att radera de fyra: förstaplats är en köpsignal,
        // en placering i en lista är marknadsstatistik. id 1183 ("återtog tronen som mest
        // sålda") behölls och är facit åt andra hållet.
        assertThat(WebInsightScraperService.RELEVANCE_PROMPT)
                .contains("ALLA TRE villkoren")
                .contains("FÖRSTAPLATSEN, inte en placering i en lista")
                .contains("SVENSKA marknaden")
                .contains("AKTUELL")
                // de tre skarpa fallen ska stå kvar som exempel, inte skrivas bort
                .contains("Sjätte plats")
                .contains("Danska nyregistreringar")
                .contains("iSeeCars")
                // ...utan att äta upp den utmärkelse som faktiskt är en köpsignal
                .contains("återtog tronen som mest sålda")
                .contains("Årets Bil/Car of the Year");
        // samma gräns i extraktionsprompten, annars plockas raden ut och stoppas först i vakten
        assertThat(WebInsightScraperService.SYSTEM_PROMPT)
                .contains("FÖRSTAPLATSEN")
                .contains("SVENSKA marknaden")
                .contains("en enskild placering");
    }

    @Test
    void saljbarhetsvaktenGallerBaraStriktaKallor() throws Exception {
        // CarUp läckte USA-modeller (Cadillac SRX) och EPA-siffror tre auditer i rad —
        // övriga källor ska inte betala för ett extra Groq-anrop på sina säljbara rader.
        // (Deras KOMMANDE-rader granskas däremot, se nästa test.)
        assertThat(WebInsightScraperService.STRICT_SOURCES).containsExactly("CarUp");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<JsonNode> insikter = List.of(mapper.readTree("{\"car_make\":\"Volvo\",\"insight\":\"Bra bil.\"}"));
        assertThat(service().filterStrict("Teknikens Värld", insikter)).isSameAs(insikter);
    }

    @Test
    void kommandeRaderGranskasAvenFranIckeStriktKalla() throws Exception {
        // Kön fylls inte av källan vakten byggdes för: natten 2026-08-06 kom alla sex nya
        // rader från Teknikens Värld och M3, och id 1178 ("Volvo planerar att öka
        // produktionen av den kommande EX60") mötte aldrig vakten eftersom M3 inte är
        // strikt källa. Kommande-vakten är därför källoberoende — men säljbara rader från
        // samma källa ska fortfarande passera oprövade.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        List<String> prompter = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            prompter.add(inv.getArgument(1));
            return groqResponse("{\"irrelevant\":[0]}");
        }).when(service).postGroq(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kommande = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX60\",\"insight\":\"Volvo planerar att öka produktionen.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        JsonNode omarkeradEX60 = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX60\",\"insight\":\"Räckvidden uppges bli 600 km.\"}");
        JsonNode saljbar = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"XC90\",\"insight\":\"Batteribyte kostar över 120 000 kr.\"}");

        // index 0 i kommande-gruppen fälls (produktionsraden) — men fällda kommande-rader
        // parkeras numera i kön i stället för att kastas, så alla tre är kvar
        assertThat(service.filterStrict("M3", List.of(kommande, omarkeradEX60, saljbar)))
                .containsExactly(kommande, omarkeradEX60, saljbar);
        // arvet gäller nu alla källor, inte bara CarUp
        assertThat(WebInsightScraperService.isUpcoming(omarkeradEX60)).isTrue();
        // bara kommande-prompten kördes — säljbarhetsvakten är fortfarande CarUp-only
        assertThat(prompter).containsExactly(WebInsightScraperService.STRICT_UPCOMING_PROMPT);
    }

    @Test
    void kommandePromptenSaknarSaljbarhetsregelnMenArvderResten() {
        // Extravaktens första regel ("går inte att köpa i Sverige idag") är negationen av
        // relevansvaktens KOMMANDE-definition. Nattkörningen 2026-08-02 flaggade Audi Q9,
        // Zeekr 9X, Q6 e-tron och GLC Electric som kommande — extravakten dödade alla fyra,
        // och eftersom CarUp är enda strikta källan kunde insight_upcoming aldrig få en rad.
        // Kommande-prompten är samma granskning MINUS den regeln.
        assertThat(WebInsightScraperService.STRICT_RELEVANCE_PROMPT)
                .contains("bilen går inte att köpa i Sverige idag");
        assertThat(WebInsightScraperService.STRICT_UPCOMING_PROMPT)
                .doesNotContain("bilen går inte att köpa i Sverige idag")
                .contains("REDAN prövat och avgjort")
                // de två raderna som slank in i kön 2026-08-04
                .contains("dollarpris för USA-marknaden")
                .contains("produktionsvolymer")
                // ...utan att äta upp det som gör en kommande modell värd att spara
                .contains("plattform");
    }

    @Test
    void extravaktenProvarKommandeModellerMotEgenPrompt() throws Exception {
        // Kön fick sina första fem rader 2026-08-04 och två av dem hörde inte hemma där
        // (EX50:s pris i dollar, Košice-fabrikens produktionsvolym) — kommande-rader ska
        // alltså granskas, bara inte mot säljbarhetsregeln.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        // guardModel är null utan Spring-injektion, och anyString() matchar inte null →
        // stubben hade fallit igenom till riktig HTTP
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        List<String> prompter = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            prompter.add(inv.getArgument(1));
            return groqResponse("{\"irrelevant\":[0]}");
        }).when(service).postGroq(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kommande = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX50\",\"insight\":\"Produktionsvolymen blir 90 000 bilar per år.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        assertThat(WebInsightScraperService.isUpcoming(kommande)).isTrue();

        // Vakten fäller index 0 → raden ska granskas, inte passera oprövad som före fixen.
        // Domen loggas, men raden parkeras i kön i stället för att kastas (se V40-testet).
        assertThat(service.filterStrict("CarUp", List.of(kommande))).containsExactly(kommande);
        assertThat(prompter).containsExactly(WebInsightScraperService.STRICT_UPCOMING_PROMPT);
    }

    @Test
    void falldKommandeRadParkerasIStalletForAttKastas() throws Exception {
        // Natten 2026-08-11 markerade kommande-vakten "Volvo V40" som kommande modell — en bil
        // som slutade tillverkas 2019 och finns i mängd på begagnatmarknaden. Extravakten dömde
        // den mot kommande-kriterierna, fällde den, och Folksams "Bra val"-rad var borta.
        // Kommande-vakten får bara parkera; att en felmarkering ändå kunde bli en radering var
        // hålet. Kön är dold för användaren och går att rensa i efterhand — en tappad rad gör
        // det inte.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        org.mockito.Mockito.doAnswer(inv -> groqResponse("{\"irrelevant\":[0]}"))
                .when(service).postGroq(anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode v40 = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"V40\",\"insight\":\"Fick Bra val i Folksams rapport 2025.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");

        assertThat(service.filterStrict("Folksam", List.of(v40))).containsExactly(v40);
        // och den ligger kvar som kommande, alltså dold tills någon släpper eller raderar den
        assertThat(WebInsightScraperService.isUpcoming(v40)).isTrue();
    }

    @Test
    void faltGroqSvarParkerarKommandeRaderIStalletForAttTappaDem() throws Exception {
        // runGuardChunk är fail-closed och returnerar tom lista när Groq inte svarar. För
        // säljbara rader är det rätt, för kommande rader vore det samma tysta bortfall som
        // V40 — de ska överleva ett vaktfel av samma skäl som markUpcomingChunk parkerar.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        org.mockito.Mockito.doReturn(null).when(service).postGroq(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode kommande = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX50\",\"insight\":\"Byggs på SPA3.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");

        assertThat(service.filterStrict("M3", List.of(kommande))).containsExactly(kommande);
    }

    @Test
    void kommandeOchSaljbaraGranskasVarForSig() throws Exception {
        // De två grupperna får olika prompt och egna index — en fälld kommande-rad får inte
        // dra med sig en säljbar rad (eller tvärtom)
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        // guardModel är null utan Spring-injektion, och anyString() matchar inte null →
        // stubben hade fallit igenom till riktig HTTP
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        List<String> prompter = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            prompter.add(inv.getArgument(1));
            // fäll index 0 i BÅDA grupperna
            return groqResponse("{\"irrelevant\":[0]}");
        }).when(service).postGroq(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode saljsFalld = mapper.readTree("{\"car_make\":\"Volvo\",\"car_model\":\"XC90\",\"insight\":\"Fem meter lång.\"}");
        JsonNode saljsKvar = mapper.readTree("{\"car_make\":\"Volvo\",\"car_model\":\"XC60\",\"insight\":\"Sliten vevaxelremskiva.\"}");
        JsonNode kommandeFalld = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"EX50\",\"insight\":\"Fabriken bygger 90 000 per år.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        JsonNode kommandeKvar = mapper.readTree(
                "{\"car_make\":\"Tesla\",\"car_model\":\"Model Y L\",\"insight\":\"Räckvidden ökar cirka 11 km.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");

        // Ordningen i indatan är medvetet blandad — resultatet ska följa den, inte grupperna.
        // Asymmetrin är avsiktlig: den fällda SÄLJBARA raden kastas (den hade blivit synlig),
        // medan den fällda KOMMANDE raden parkeras i den dolda kön.
        assertThat(service.filterStrict("CarUp", List.of(saljsFalld, kommandeFalld, saljsKvar, kommandeKvar)))
                .containsExactly(kommandeFalld, saljsKvar, kommandeKvar);
        assertThat(prompter).containsExactlyInAnyOrder(
                WebInsightScraperService.STRICT_RELEVANCE_PROMPT, WebInsightScraperService.STRICT_UPCOMING_PROMPT);
    }

    @Test
    void kommandeMarkeringenArvsInomBatchenForSammaBil() throws Exception {
        // Natten 2026-08-05 kom tre CarUp-rader om Audi A2 e-tron och relevansvakten markerade
        // bara vikten. cW-raden och WLTP-raden hamnade därmed i säljbar-gruppen och stoppades av
        // säljbarhetsregeln. Domen är inte stabil (fyra olika uppdelningar på sex A/B-observationer
        // av samma batch), så resten av bilens rader ärver markeringen i stället.
        var service = org.mockito.Mockito.spy(service());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "guardModel", "test-modell");
        List<String> prompter = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            prompter.add(inv.getArgument(1));
            return groqResponse("{\"irrelevant\":[]}");
        }).when(service).postGroq(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode markerad = mapper.readTree(
                "{\"car_make\":\"Audi\",\"car_model\":\"A2 e-tron\",\"insight\":\"Väger 1 500–1 650 kg.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        JsonNode omarkerad = mapper.readTree(
                "{\"car_make\":\"Audi\",\"car_model\":\"A2 e-tron\",\"insight\":\"Luftmotstånd cW 0,24.\"}");
        JsonNode annanBil = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"XC60\",\"insight\":\"Sliten vevaxelremskiva.\"}");

        assertThat(service.filterStrict("CarUp", List.of(markerad, omarkerad, annanBil)))
                .containsExactly(markerad, omarkerad, annanBil);
        // omarkerad ska ha ärvt markeringen — annars hade den prövats mot säljbarhetsregeln
        assertThat(WebInsightScraperService.isUpcoming(omarkerad)).isTrue();
        assertThat(WebInsightScraperService.isUpcoming(annanBil)).isFalse();
        // båda grupperna finns kvar: A2-raderna i kommande-prompten, XC60 i den säljbara
        assertThat(prompter).containsExactlyInAnyOrder(
                WebInsightScraperService.STRICT_RELEVANCE_PROMPT, WebInsightScraperService.STRICT_UPCOMING_PROMPT);
    }

    @Test
    void kommandeMarkeringenArvsInteViaTomtMarkeEllerModell() throws Exception {
        // En tom nyckel hade dragit ihop orelaterade rader till en och samma "bil"
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode markeradUtanModell = mapper.readTree(
                "{\"car_make\":\"Audi\",\"car_model\":\"\",\"insight\":\"Kommande modell.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        JsonNode annanUtanModell = mapper.readTree(
                "{\"car_make\":\"Volvo\",\"car_model\":\"\",\"insight\":\"Sliten vevaxelremskiva.\"}");
        JsonNode heltTom = mapper.readTree("{\"insight\":\"Något om en bil.\"}");

        // apiKey är null → vakterna passiva, men markeringsarvet sker före dem
        assertThat(service().filterStrict("CarUp", List.of(markeradUtanModell, annanUtanModell, heltTom)))
                .containsExactly(markeradUtanModell, annanUtanModell, heltTom);
        assertThat(WebInsightScraperService.isUpcoming(annanUtanModell)).isFalse();
        assertThat(WebInsightScraperService.isUpcoming(heltTom)).isFalse();
    }

    @Test
    void extravaktenBevararOrdningenNarKommandeBlandas() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode vanlig = mapper.readTree("{\"car_make\":\"Volvo\",\"car_model\":\"XC90\",\"insight\":\"Fem meter lång.\"}");
        JsonNode kommande = mapper.readTree(
                "{\"car_make\":\"Audi\",\"car_model\":\"Q9\",\"insight\":\"5,31 m lång.\",\""
                        + WebInsightScraperService.UPCOMING_FIELD + "\":true}");
        // apiKey är null → båda vakterna är passiva, så allt ska tillbaka i oförändrad ordning
        // (grupperingen kommande/säljbara får inte kasta om raderna)
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
