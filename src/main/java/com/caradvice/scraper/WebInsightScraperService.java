package com.caradvice.scraper;

import com.caradvice.model.ExpertInsight;
import com.caradvice.repository.ExpertInsightRepository;
import com.caradvice.service.UpcomingInsightService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hämtar bilinsikter från svenska motorsajter, extraherar dem med Groq och sparar
 * i expert_insight. Inkrementell: processade artikel-URL:er och sedda ägaromdömen
 * lagras i web_insight_seen så nattliga körningar aldrig skapar dubbletter.
 */
@Service
public class WebInsightScraperService {

    private static final Logger log = LoggerFactory.getLogger(WebInsightScraperService.class);

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; CarAdviceBot/1.0; +https://caradvice.onrender.com)";
    private static final int FETCH_TIMEOUT_MS = 20_000;
    private static final long FETCH_DELAY_MS = 1_500;   // artighet mot sajterna
    private static final long GROQ_DELAY_MS = 5_000;    // TPM-gräns 8000 tokens/min
    private static final int MAX_ARTICLES_PER_SOURCE = 12;
    private static final long DEFAULT_BACKOFF_MS = 10_000;  // bara när Groq inte säger något själv
    private static final long MIN_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 60_000;
    // Höjt 3 → 5 den 2026-08-01: en artikel om missljud i en Volvomotor tappades i körningen
    // 13:31 efter tre 429:or på 15+2+6 s, alltså bara 23 s totalt. Groqs egna väntetider är
    // korta nu (median ~12 s i den körningen), så tre försök tar slut långt innan gränsen
    // hinner släppa. Värsta fallet är fortfarande begränsat: MAX_BACKOFF_MS per försök.
    private static final int GROQ_MAX_ATTEMPTS = 5;
    private static final Pattern RETRY_IN_PATTERN =
            Pattern.compile("try again in (?:(\\d+)m)?([\\d.]+)s", Pattern.CASE_INSENSITIVE);
    // Vakterna körs per källa, men chunkas: gpt-oss-120b är en reasoning-modell och
    // resonemanget växer med batchens storlek. Med 25 insikter och 400 tokens gick HELA
    // budgeten till reasoning (finish_reason=length, tomt content) — mätt 2026-07-31.
    private static final int GUARD_BATCH_SIZE = 10;
    private static final int GUARD_MAX_TOKENS = 1500;
    private static final int MIN_DISCOVERED_LINKS = 5;   // färre än så = källan håller på att sina
    private static final int MIN_TEXT_CHARS = 400;
    private static final int MAX_TEXT_CHARS = 7_000;
    // Stoppade insikter sparas aldrig — loggraden är hela underlaget för att i efterhand
    // avgöra om vakten dömde rätt. Vid 80 tecken gick det inte: utredningen av Polestar 3
    // och Volvo V70 (2026-08-01) fick i stället A/B-testa hypoteser mot Groq, eftersom
    // originaltexterna var kapade mitt i meningen. 250 rymmer en hel insikt.
    static final int LOG_INSIGHT_CHARS = 250;
    // 404/410 = artikeln är borta för gott. 403 står medvetet INTE här: den är oftast
    // botblockering som kan släppa igen, och att markera den som läst tappar artikeln tyst.
    private static final Set<Integer> PERMANENT_HTTP_STATUS = Set.of(404, 410);

    /** Svar som betyder att artikeln aldrig kommer tillbaka, och därför får markeras som läst. */
    static boolean isPermanentlyGone(int httpStatus) {
        return PERMANENT_HTTP_STATUS.contains(httpStatus);
    }

    private static final String SYSTEM_PROMPT = """
            Du är en assistent som extraherar bilexpertinsikter ur text från svenska motorsajter.

            Analysera texten och extrahera KONKRETA insikter om specifika bilar eller bilkategorier.
            Returnera ett JSON-objekt med fältet "insights" som en array.

            Varje insikt ska ha exakt dessa fält:
            - "car_make": biltillverkare (t.ex. "Volvo", "Toyota") — obligatoriskt; hoppa över
              insikter som inte handlar om ett specifikt bilmärke
            - "car_model": modell (t.ex. "EX30", "RAV4") — obligatoriskt; en insikt om ett helt
              märke eller om en motor som sitter i flera modeller ("BMW:s N47-diesel") ska hoppas
              över helt, inte sparas med tom modell
            - "fuel_type": ett av: "elbil", "bensin", "diesel", "hybrid", "laddhybrid" — eller ""
            - "category": ett av: "ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil" — eller ""
            - "insight": 1-3 meningar på svenska med källans konkreta åsikt eller fakta, i tredje person
            - "rating": källans betyg omräknat till heltal på skalan 1-10, annars "". Räkna om
              andra skalor proportionerligt: "4 av 5" eller 4 stjärnor → 8, "3 av 5" → 6,
              "7 av 10" → 7, "8,5 av 10" → 9 (avrunda). Betyget måste stå uttryckligen i
              texten (poäng, stjärnor eller "betyg X") — härled ALDRIG ett betyg ur tonläget
            - "source_ref": för ägaromdömen: recensentens namn + datum (t.ex. "Andreas Skoglund 2026-06-12"); för testresultat om en specifik bil: "märke modell" (t.ex. "Volvo V60"); annars ""

            Regler:
            - Inkludera BARA konkreta påståenden om bilar (styrkor, svagheter, mätvärden, testresultat, kända fel)
            - Insikten ska vara användbar för någon som ska KÖPA personbil i Sverige. Returnera INGA insikter alls om:
              * superbilar/hypercars, racing-/motorsportbilar eller lyxbilar långt över vanliga konsumentpriser
              * lastbilar, bussar och yrkesfordon; A-traktorer och mopedbilar
              * prototyper, konceptbilar, entusiastombyggnader, veteran-/samlarbilar
              * specialutgåvor/jubileumsmodeller där innehållet handlar om färger, fälgar och dekor
              * fabriks-, försäljnings- och företagsnyheter (nedläggningar, marknadsandelar, showrooms, mässor, lanseringar)
                — MEN utmärkelser till en specifik modell (Årets Bil/Car of the Year, "bäst i test",
                topplacering i försäljningsstatistik) är RELEVANTA och ska inkluderas
              * trafikregler, lagändringar, böter, körkorts-, besiktnings- och försäkringsregler
            - "car_make"/"car_model" måste vara bilens verkliga officiella namn — hitta aldrig på
              eller gissa modellnamn; är du osäker: sätt ""
            - "car_make" ska vara märkets vanliga kortform utan undermärken och tillägg
              (skriv "Mercedes", aldrig "Mercedes-Benz" eller "Mercedes-AMG"; "VW" skrivs "Volkswagen")
            - En insikt ska handla om en specifik bilmodell eller bilkategori — aldrig om företag,
              marknaden i stort eller branschen
            - "category" ska stämma med bilens faktiska typ:
              * "smaabil" = liten stadsbil (t.ex. Toyota Aygo, Renault Clio) — ALDRIG SUV:ar eller mellanklassbilar
              * "suv" = SUV/crossover oavsett drivlina (t.ex. Volvo XC60, Kia EV5)
              * "familjebil" = mellanstor/stor kombi, sedan eller halvkombi (t.ex. VW Passat, VW ID.7)
              * en sportbil eller lyxbil är ALDRIG "ekonomibil"/"smaabil"/"familjebil"
              * sätt "" om ingen kategori passar
            - Ignorera navigationstext, annonser, medlemserbjudanden och orelaterat innehåll
            - Varje insikt ska vara självbärande och kunna läsas utan artikelkontext
            - Max 5 insikter per artikel, max 10 för sidor med många ägaromdömen
            - Om texten inte innehåller något konkret om bilar: returnera {"insights": []}
            - Svara ENDAST med valid JSON, inget annat
            """;

    // Parafraser fångas inte av textjämförelse — två artiklar om samma bil ger "32,2 kWh
    // Blade-batteri" och "Blade-batteriet på 32,2 kWh" som skilda rader (BYD Shark fick
    // två hela uppsättningar). Kandidater vars bil redan har insikter i DB får därför
    // ett extra Groq-anrop som pekar ut faktaupprepningar.
    private static final String DEDUP_PROMPT = """
            Du jämför nya bilinsikter mot insikter som redan finns i en databas.

            En ny kandidat är DUBBLETT om dess huvudsakliga fakta eller påstående om samma bil
            redan täcks av en befintlig insikt — även när formuleringen är helt annorlunda
            (t.ex. samma dragvikt, batteristorlek, räckvidd, testresultat eller omdöme).
            En kandidat är också dubblett om den upprepar fakta från en kandidat med LÄGRE index
            (den första behålls, den senare markeras).
            En kandidat som tillför ny eller kompletterande information är INTE en dubblett.

            Svara ENDAST med valid JSON: {"duplicates": [indexen för de kandidater som är dubbletter]}
            Om ingen är dubblett: {"duplicates": []}
            """;

    // Exkluderingslistan i SYSTEM_PROMPT läcker under extraktionsarbetet (Nissan Tekton
    // "säljs ej i Sverige", Lamborghini Temerario, Porsche-auktioner) — samma lärdom som
    // med parafraserna: ett separat, smalt Groq-anrop dömer säkrare än regler inbakade
    // i den stora prompten.
    // Sverigekravet var först mjukt, med ett undantag för "snart lanserad modell med
    // bekräftat namn och konkreta uppgifter" — det undantaget blev hålet: en artikel om
    // en ännu ej säljstartad bil innehåller nästan alltid effekt, vikt eller räckvidd,
    // så vakten läste specdumpar som köpvägledning (Zeekr 9X Ultra, nästa MX-5, båda
    // 2026-07-30; Elbilens nya posttyper 2026-07-29 drar in mycket sådant). Kriteriet är
    // därför "går att köpa i Sverige idag", inte "hur konkreta uppgifterna är".
    // Sedan 2026-07-31 kastas de kommande modellerna inte längre: de sparas med
    // upcoming-flagga och hålls utanför prompter och bilkort tills bilen släpps (natten
    // till 07-31 slängdes 13 rader om nya el-GLA:n — innehåll som blir användbart om
    // några månader). Skillnaden mot IRRELEVANT är bekräftad modell + konkret innehåll.
    static final String RELEVANCE_PROMPT = """
            Du granskar bilinsikter innan de sparas i en databas vars enda syfte är att
            hjälpa svenska privatpersoner att välja och köpa personbil.

            AVGÖRANDE: anges ett svenskt pris i kronor för modellen är den RELEVANT — då
            säljs den här. Blockera aldrig en prissatt bil med motiveringen att den är ny
            eller okänd. (DS N°8 med startpris 849 900 kr stoppades felaktigt 2026-07-31.)
            Undantaget gäller BARA invändningen "ny eller okänd modell". Ett utskrivet pris
            gör aldrig en bil relevant som faller under någon av uteslutningarna nedan — en
            sportbil eller lyxbil blir inte köpvägledning för att priset står i texten.

            En insikt är IRRELEVANT om den handlar om:
            - en bil som inte går att köpa i Sverige IDAG och inte heller är på väg hit.
              Kravet är hårt: konkreta uppgifter (effekt, vikt, räckvidd, mått, testvärden,
              pris i kronor) gör INTE en bil relevant om läsaren inte kan köpa den. Hit hör
              modeller som bara säljs på andra marknader (t.ex. Lada eller varianter som
              säljs i Kina/USA), modeller som lämnat den svenska marknaden, samt rykten om
              kommande namn ("kan heta X"), plattform, teknik eller lanseringsår.
              Nylanserade modeller vars första kunder redan fått sina bilar levererade i
              Sverige är däremot KÖPBARA — erfarenheter från de allra första exemplaren är
              RELEVANTA, varken irrelevanta eller kommande. Att bilen kallas ny eller
              nylanserad, eller att leveranserna nyss börjat, ändrar inget (Volvo EX60,
              BMW iX3 och Mercedes GLC med eldrift är i det läget 2026; fem EX60-rader
              från förste ägaren kastades felaktigt 2026-08-07)
            - kuriosa, rekordförsök och bragder (längsta sträcka på en tank, extremt låg
              förbrukning med specialdäck/körstil), eller retrospektiva jämförelsetester av
              utgångna prestandabilar — underhållande, men ingen köpvägledning
            - superbilar/hypercars, racingbilar, sportbilar eller lyxbilar långt över vanliga
              konsumentpriser. Riktmärke: nybilspris över ca 1,5 miljoner kronor, ELLER en
              prestandaversion vars enda innehåll är effekt och acceleration. Hit hör även
              stora V8-SUV:ar och AMG/M/RS-toppversioner (Mercedes-AMG GLE 63 S med 585 hk
              släpptes felaktigt igenom 2026-07-31). Tvåsitsiga sportbilar och
              prestandacoupéer (Porsche 911 och Cayman, Chevrolet Corvette, Lotus Emira)
              hör hit oavsett pris. Gäller texten en begagnad bil är det modellens
              nybilspris som avgör, inte det begagnade priset — en begagnad Porsche 911
              vars värdefall angavs i kronor släpptes felaktigt igenom 2026-08-01. En
              vanlig familjebil eller elbil under den nivån är däremot RELEVANT hur snabb
              den än är
            - lastbilar, bussar, yrkesfordon, A-traktorer eller mopedbilar
            - prototyper, konceptbilar, entusiastombyggnader eller veteran-/samlarbilar
            - specialutgåvor, jubileums- och signatureditioner vars innehåll är utseende,
              namn eller upplaga i stället för egenskaper — färger, fälgar, dekor, emblem,
              "firar 25 år", "begränsad upplaga om 500 exemplar" (Mini Cooper Oxford Edition
              släpptes felaktigt igenom 2026-07-31). Handlar texten om utrustning, räckvidd
              eller pris för utgåvan är den däremot RELEVANT
            - ren design- och stämningsprosa utan något kontrollerbart: "skalade ytor", "rund
              central pekskärm", "gokart-känsla", "återger arvet". En insikt måste innehålla
              minst en sak en köpare kan kontrollera eller jämföra — en siffra, ett
              testresultat, ett känt fel, en utrustningsdetalj eller ett pris. Ett
              sammanfattat testomdöme räknas som testresultat, se RELEVANT nedan
            - auktioner, fabriks-, försäljnings- eller företagsnyheter, produktions- och
              lagersiffror, marknadsstatistik. Hit hör även FÖRSENINGAR: att en lansering
              skjutits upp eller att leveranser dröjt är en företagsnyhet, inte en egenskap
              hos bilen ("Polestar 3 har drabbats av buggar som försenat leveranserna"
              släpptes felaktigt igenom 2026-08-01). Skriver texten däremot ut vad som
              faktiskt går sönder — vilken funktion, vilket symtom, vilken åtgärd — är den
              RELEVANT som känt fel
            - trafikregler, lagändringar, böter, skatter eller försäkringsregler
            - vilken bil en känd person (idrottare, artist, politiker) kör, äger eller setts i
            En insikt är RELEVANT om den kan hjälpa en svensk bilköpare att välja eller
            värdera en personbil (styrkor, svagheter, mätvärden, testresultat, kända fel).
            Utmärkelser till en specifik modell är också RELEVANTA (Årets Bil/Car of the Year,
            "bäst i test", mest sålda bilen i sin klass) — de är köpsignaler, inte företagsnyheter.
            Sammanfattade testomdömen från motorpressen är RELEVANTA även utan siffror, så
            länge de namnger vilka egenskaper omdömet gäller: "hyllas i världspressens tester
            för prestanda och komfort", "beröm för sportig körning, interiör och ljudsystem",
            "kritiseras för hård fjädring". Ett vägt omdöme från provkörningar är ett
            testresultat, inte stämningsprosa. Gränsen går vid omdömen om MÄRKET i stället
            för bilen och vid rena adjektiv utan angiven egenskap ("en riktigt bra bil").
            Märkesomdömet räknas dit oavsett om det är negativt ("har skadat varumärkets
            rykte") eller smickrande ("fortsätter märkets tradition av starka kombibilar",
            "trogen märkets arv") — det säger något om tillverkaren, inte om bilen framför
            köparen. Att räkna upp drivlina, karossform eller teknik som redan följer av
            modellbeteckningen är inte heller ett testomdöme: "A6 Allroad i
            laddhybridutförande kombinerar fyrhjulsdrift med hybridteknik" släpptes
            felaktigt igenom 2026-08-03 — meningen namnger visserligen tekniken, men
            ingenting i den går att jämföra mot en annan bil. Undantaget gäller omdömen
            som VÄGER bilens egenskaper, inte meningar som beskriver vad utförandet heter.

            En bekräftad modell som är på väg till den svenska marknaden är RELEVANT här,
            så länge insikten har konkret innehåll (mått, effekt, räckvidd, utrustning,
            testintryck). Om bilen redan GÅR att köpa avgörs i ett separat steg efteråt —
            väg inte in den frågan, och håll inte tillbaka en rad för att bilen är ny. Lösa
            rykten om namn eller lanseringsår är däremot IRRELEVANTA.

            Svara ENDAST med valid JSON:
            {"irrelevant": [index...]}
            Om alla är relevanta: {"irrelevant": []}
            """;

    /**
     * Andra steget i relevansvakten, och avsiktligt ett eget Groq-anrop. Trevägsbeslutet
     * (irrelevant / kommande / direkt användbar) i en enda prompt visade sig instabilt på
     * just KOMMANDE-gränsen: samma rader fick olika dom mellan körningar med identiska
     * produktionsparametrar. Natten 2026-08-05 splittrades tre CarUp-rader om Audi A2
     * e-tron, och natten 2026-08-07 kastades fem rader från Volvo EX60:s förste svenske
     * ägare som IRRELEVANTA trots att EX60 säljs här — A/B på samma batch gav KOMMANDE i
     * en körning och direkt användbar i två, alltså tre olika domar på samma rader.
     *
     * <p>Frågan är binär och besvaras därför separat, utan att konkurrera med
     * relevansbedömningen om modellens uppmärksamhet. Vakten kan bara PARKERA rader, aldrig
     * kasta dem: relevansen är redan avgjord när den körs.
     */
    static final String UPCOMING_PROMPT = """
            Du avgör en enda sak om varje rad: går bilen att köpa i Sverige idag?

            Raderna är redan godkända som köpvärd information. Du ska INTE bedöma
            innehållets kvalitet eller nytta — bara modellens tillgänglighet.

            En rad är KOMMANDE om modellen ännu inte går att köpa här: presenterad men inte
            prissatt, annonserad säljstart längre fram, eller en ny generation som ännu inte
            nått marknaden (även när föregående generation säljs idag).

            Gränsen går vid leveranserna, inte vid hur ny bilen är. Har svenska kunder redan
            fått sina bilar är modellen KÖPBAR — även om den kallas ny eller nyss lanserad,
            och även om texten handlar om barnsjukdomar hos de första exemplaren. Volvo
            EX60, BMW iX3 och Mercedes GLC med eldrift är i det läget 2026. KOMMANDE är
            bilar längre bort: utan säljstart, eller med lansering ett år eller mer fram
            i tiden (Volvo EX50 med säljstart 2027 är arketypen).

            Är du osäker på om leveranserna börjat — svara KOMMANDE. En rad i kön går att
            släppa fram, en osläppt bil på ett bilkort går inte att ta tillbaka.

            Svara ENDAST med valid JSON:
            {"upcoming": [index...]}
            Om alla går att köpa idag: {"upcoming": []}
            """;

    /**
     * Källor som återkommande levererar mest skräp får en andra, smalare vakt ovanpå
     * RELEVANCE_PROMPT. CarUp publicerar mycket översatt amerikanskt innehåll
     * (mekaniker-listicles, EPA-siffror för bilar som inte säljs här) — tre auditer i rad
     * (2026-07-09, 07-25, 07-26) har visat att den generella vakten släpper igenom det
     * trots att reglerna finns, medan bra rader (VW Arteon begagnat) kommer från samma
     * källa. Att lyfta ut CarUp hade alltså kostat mer än det smakat.
     *
     * <p>Gäller bara säljbara rader. Kommande modeller prövas hos alla källor — se
     * {@link #filterStrict}.
     */
    static final Set<String> STRICT_SOURCES = Set.of("CarUp");

    static final String STRICT_RELEVANCE_PROMPT = """
            Du gör en sista, hård granskning av bilinsikter från en källa som ofta
            publicerar översatt amerikanskt innehåll. Databasen används bara av svenska
            privatpersoner som ska köpa personbil i Sverige.

            Markera en insikt som IRRELEVANT om något av detta gäller:
            - bilen går inte att köpa i Sverige idag, varken ny hos handlare eller begagnad
              på den svenska marknaden (typiska exempel: modeller som bara sålts i USA)
            - insikten bygger på utländska mätvärden eller testcykler (EPA, miles, mpg,
              amerikanska priser i dollar) för en bil som ännu inte säljs här
            - insikten är ett svepande omdöme utan konkret underlag ("en mekaniker tycker
              att den är dyr att reparera", "den är opålitlig") utan att ange vilket fel,
              vilken motor, vilken årsmodell eller vilken kostnad det handlar om. Ett
              sammanfattat testomdöme från motorpressen som namnger vilka egenskaper
              omdömet gäller ("beröm för sportig körning, interiör och ljudsystem") är
              INTE svepande — behåll det. Undantaget gäller bara bilen: omdömen om
              MÄRKET ("fortsätter märkets tradition av starka kombibilar") och meningar
              som bara räknar upp drivlina eller teknik som redan följer av
              modellbeteckningen ("i laddhybridutförande kombinerar fyrhjulsdrift med
              hybridteknik") är svepande och ska bort
            - innehållet är hämtat ur en topplista/video av typen "bilar mekaniker aldrig
              skulle köpa" utan svensk förankring

            Behåll insikten om den beskriver ett konkret, kontrollerbart förhållande om en
            bil som en svensk köpare faktiskt kan hitta i marknaden — kända fel med angiven
            motor/årsmodell, mätvärden, testresultat, sammanfattade testomdömen från
            motorpressen, utrustning eller prisläge på begagnat.

            Svara ENDAST med valid JSON: {"irrelevant": [indexen för de irrelevanta insikterna]}
            Om alla ska behållas: {"irrelevant": []}
            """;

    /**
     * Extravakten för rader som relevansvakten redan klassat som KOMMANDE. Identisk med
     * {@link #STRICT_RELEVANCE_PROMPT} sånär som på säljbarhetsregeln — den regeln är
     * negationen av KOMMANDE-definitionen och dödade hela kön 2026-08-02 när extravakten
     * fick pröva saken en gång till. Resten av CarUp:s läckor gäller lika mycket för en bil
     * som inte släppts än: kön fick sina första fem rader natten till 2026-08-04, och två av
     * dem hörde inte hemma där — EX50:ns pris angivet i dollar för USA-marknaden (id 1152)
     * och Košice-fabrikens produktionsvolym (id 1154). Fabriksregeln finns i RELEVANCE_PROMPT
     * men läcker just på kommande modeller, där nästan allt som skrivs ÄR fabriksnyheter.
     *
     * <p>Till skillnad från {@link #STRICT_RELEVANCE_PROMPT} körs den på ALLA källor, inte
     * bara {@link #STRICT_SOURCES} — se {@link #filterStrict}. Formuleringen är därför
     * källneutral: felkällan är ämnet (en bil som ännu inte finns att provköra ger mest
     * fabriks- och pressmaterial att skriva av), inte vem som skriver.
     */
    static final String STRICT_UPCOMING_PROMPT = """
            Du gör en sista, hård granskning av bilinsikter om modeller som är bekräftade för
            den svenska marknaden men ännu inte går att köpa här. Om en bil ännu inte går att
            provköra bygger det mesta som skrivs om den på tillverkarens eget pressmaterial.
            Databasen används bara av svenska privatpersoner som ska köpa personbil i Sverige.

            Att bilen ännu inte säljs här är REDAN prövat och avgjort — avvisa aldrig en
            insikt med den motiveringen. Du prövar bara innehållet.

            Markera en insikt som IRRELEVANT om något av detta gäller:
            - insikten vilar på utländska mätvärden, testcykler eller priser (EPA, miles,
              mpg, dollarpris för USA-marknaden) i stället för uppgifter som gäller den
              version som ska säljas här. Ett omräknat dollarpris är fortfarande ett
              dollarpris
            - insikten handlar om fabriker, produktionsvolymer, tillverkningskapacitet,
              leveranser eller lagersiffror. Tekniska uppgifter om bilen själv — plattform,
              batteri, räckvidd, effekt, mått, utrustning — ska däremot BEHÅLLAS
            - insikten är ett svepande omdöme utan konkret underlag ("den blir dyr", "den
              blir riktigt bra") utan att ange vilken egenskap det gäller. Ett sammanfattat
              testomdöme från motorpressen som namnger vilka egenskaper omdömet gäller
              ("beröm för sportig körning, interiör och ljudsystem") är INTE svepande —
              behåll det. Omdömen om MÄRKET ("fortsätter märkets tradition av starka
              kombibilar") och meningar som bara räknar upp drivlina eller teknik som redan
              följer av modellbeteckningen ("i laddhybridutförande kombinerar fyrhjulsdrift
              med hybridteknik") är svepande och ska bort
            - innehållet är löst rykte om en modell som inte presenterats officiellt: "kan
              komma att heta", "väntas få", uppgifter utan angiven källa

            Behåll insikten om den beskriver ett konkret, kontrollerbart förhållande om bilen
            som en svensk köpare kan värdera när den släpps — mått, räckvidd, effekt, batteri,
            plattform, utrustning, prisläge i kronor, testintryck.

            Svara ENDAST med valid JSON: {"irrelevant": [indexen för de irrelevanta insikterna]}
            Om alla ska behållas: {"irrelevant": []}
            """;

    /**
     * mode ARTICLES: hämta artikellänkar (via sitemap/rss/listing), extrahera per artikel — dedup på URL.
     * mode PAGE: extrahera insikter direkt från sidan — dedup per ägaromdöme/bilmodell via source_ref.
     */
    record Source(String expert, Mode mode, Discover discover, String url,
                  String base, String linkPattern, String kind, List<String> extraUrls) {}

    enum Mode { ARTICLES, PAGE }
    enum Discover { SITEMAP, RSS, LISTING, WPJSON, NONE }

    /**
     * Utfall för en källa. En källa som inte hittade något att skrapa (0 länkar, tom sida)
     * gav förut samma "0" i scrape-status som en källa där allt fungerade men dedupen tog
     * allt — en layoutändring hos källan kunde alltså gå obemärkt förbi hur länge som helst.
     * warning != null lyfts därför fram i statusraden i stället för antalet.
     */
    record SourceResult(int saved, String warning) {
        static SourceResult of(int saved) { return new SourceResult(saved, null); }

        String label() {
            if (warning == null) return String.valueOf(saved);
            // en varnande källa kan ändå ha levererat — dölj inte siffran bakom varningen
            return saved > 0 ? saved + " (" + warning + ")" : warning;
        }
    }

    private static final List<Source> SOURCES = List.of(
            new Source("Teknikens Värld", Mode.ARTICLES, Discover.SITEMAP,
                    "https://teknikensvarld.se/sitemap.xml", null, null,
                    "artikel/test från motortidningen Teknikens Värld", List.of()),
            new Source("Vi Bilägare", Mode.ARTICLES, Discover.RSS,
                    "https://www.vibilagare.se/rss.xml", null, null,
                    "artikel/test från motortidningen Vi Bilägare", List.of()),
            new Source("M Sverige", Mode.ARTICLES, Discover.LISTING,
                    "https://msverige.se/allt-om-bilen/motor-testar/bilar/",
                    "https://msverige.se", "href=\"(/allt-om-bilen/motor-testar/bilar/[a-z0-9\\-]+/?)\"",
                    "biltest från Riksförbundet M Sverige", List.of()),
            new Source("Bytbil", Mode.ARTICLES, Discover.LISTING,
                    "https://nybil.bytbil.com/posts",
                    "https://nybil.bytbil.com", "href=\"(/posts/[a-z0-9\\-]+)\"",
                    "biltest/nybilsartikel från Bytbil", List.of()),
            new Source("M3", Mode.ARTICLES, Discover.RSS,
                    "https://www.m3.se/feed/", null, null,
                    // M3 är en teknikssajt — icke-bilartiklar ger tom insiktslista och filtreras bort
                    "test/artikel från teknikmagasinet M3",
                    List.of("https://www.m3.se/article/1860897/basta-elbil-test.html")),
            // WordPress REST API (wp-json) — öppen på dessa sajter trots att delar av
            // sidorna är JS-renderade. Icke-bilartiklar (t.ex. F1-nyheter) ger tom
            // insiktslista från prompten och filtreras bort, precis som för M3.
            new Source("Auto Motor & Sport", Mode.ARTICLES, Discover.WPJSON,
                    "https://www.automotorsport.se/wp-json/wp/v2/posts?per_page=15&_fields=link", null, null,
                    "artikel/test från motortidningen Auto Motor & Sport", List.of()),
            // Elbilen publicerar inte i standardtypen "posts" (den innehåller 3 poster totalt)
            // utan i egna posttyper. tester + artiklar är de redaktionella; "nyheter" (7 000+)
            // är kort notisflöde och ger sällan konkreta insikter — därför medvetet utelämnad.
            new Source("Elbilen", Mode.ARTICLES, Discover.WPJSON,
                    "https://elbilen.se/wp-json/wp/v2/tester?per_page=10&_fields=link,"
                            + "https://elbilen.se/wp-json/wp/v2/artiklar?per_page=10&_fields=link", null, null,
                    "artikel/test från elbilsmagasinet Elbilen", List.of()),
            // per_page=15 räcker bara ett dygn bakåt här — CarUp publicerar flera inlägg om
            // dagen. En artikel som tappas hinner alltså falla ur discover-fönstret innan
            // nästa körning, och då hjälper det inte att rensa dedupnyckeln: enda vägen
            // tillbaka är en engångs-URL i extraUrls (senast 2026-08-02, läst 08-03 som
            // id 1138). Observera att länkarna saknar www, till skillnad från
            // wp-json-endpointen: nycklarna i web_insight_seen följer länkarna.
            new Source("CarUp", Mode.ARTICLES, Discover.WPJSON,
                    "https://www.carup.se/wp-json/wp/v2/posts?per_page=15&_fields=link", null, null,
                    "artikel/nyhet från bilsajten CarUp", List.of()),
            // car.info är borttagen: /sv-se/user-reviews serverar bara ett filterskal —
            // omdömestexterna hämtas av JS efteråt, så en ren HTTP-hämtning ser inga
            // omdömen och inga länkar till enskilda omdömen. Källan sparade aldrig en
            // enda insikt. Kräver headless browser för att återinföras.
            new Source("Folksam", Mode.PAGE, Discover.NONE,
                    "https://www.folksam.se/tester-och-goda-rad/vara-tester/hur-saker-ar-bilen", null, null,
                    "Folksams krocksäkerhetsstudie 'Hur säker är bilen' baserad på verkliga olyckor", List.of()));

    /** Slår upp en konfigurerad källa på namn. null om källan inte finns. Används av testerna. */
    static Source sourceByName(String expert) {
        return SOURCES.stream().filter(s -> s.expert().equals(expert)).findFirst().orElse(null);
    }

    @Value("${groq.api.key}")
    private String apiKey;

    // gpt-oss-120b är production-tier och bra på svensk extraktion
    @Value("${groq.insight.model:openai/gpt-oss-120b}")
    private String insightModel;

    // Vakterna (relevans, extravakt, parafras-dedup) körs som eget anrop och kan peka på en
    // annan modell än extraktionen — Groq har egen TPM-pott per modell.
    //
    // PRÖVAT OCH FÖRKASTAT: gpt-oss-20b som vakt (testkörning i prod 2026-07-31 20:18).
    // Den separata potten fungerade — vaktanropen slutade helt synas bland 429:orna — men
    // 20b dömde för brusigt åt BÅDA hållen i samma körning: släppte igenom Mini Oxford
    // Edition (dekorutgåva), europeisk försäljningsstatistik, Teslas produktionsmilstolpe
    // och en AMG GLE 63 S, samtidigt som den stoppade alla fem DS N°8-raderna trots att
    // artikeln angav svenskt pris (849 900 kr). Vakterna ÄR kvalitetsspärren mot skräp i
    // insiktsdatabasen, så de ligger kvar på den stora modellen. Tidsvinsten satt ändå i
    // retry-after-backoffen och i att vakterna körs per källa, inte i modellvalet.
    @Value("${groq.guard.model:openai/gpt-oss-120b}")
    private String guardModel;

    private final ExpertInsightRepository insightRepo;
    private final JdbcTemplate jdbc;
    private final JobStatusService jobStatus;
    private final UpcomingInsightService upcomingService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public WebInsightScraperService(ExpertInsightRepository insightRepo, JdbcTemplate jdbc,
                                    JobStatusService jobStatus, UpcomingInsightService upcomingService) {
        this.insightRepo = insightRepo;
        this.jdbc = jdbc;
        this.jobStatus = jobStatus;
        this.upcomingService = upcomingService;
    }

    public void ensureTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS web_insight_seen (
                seen_key VARCHAR(500) PRIMARY KEY
            )
            """);
        jobStatus.ensureTable();
    }

    boolean isSeen(String key) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM web_insight_seen WHERE seen_key = ?", Integer.class, truncateKey(key));
        return n != null && n > 0;
    }

    void markSeen(String key) {
        try {
            jdbc.update("INSERT INTO web_insight_seen(seen_key) VALUES (?)", truncateKey(key));
        } catch (DuplicateKeyException ignored) {
            // redan markerad — ofarligt
        }
    }

    /** Seedar redan processade nycklar (t.ex. från den lokala Python-körningen). Returnerar antal nya. */
    public int seedSeen(List<String> keys) {
        int added = 0;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            if (!isSeen(key.trim())) {
                markSeen(key.trim());
                added++;
            }
        }
        return added;
    }

    /**
     * Glömmer en processad nyckel så nästa körning läser om artikeln — motsatsen till
     * {@link #seedSeen}. Utan den är en tappad artikel förlorad för gott: dedupen ser bara
     * att nyckeln finns, aldrig varför den kom dit (carup.se/skrackljud-i-volvos-motor...
     * markerades som läst efter en rate limit 2026-08-01 och kunde inte tas tillbaka).
     * Dubblar som verktyg för att köra om en enskild artikel mot en ändrad prompt.
     *
     * <p>Med {@code prefix} matchas alla nycklar som börjar på värdet, för att ta en hel
     * artikelserie från samma källa. Kravet på {@link #MIN_FORGET_PREFIX} tecken är till för
     * att ett tomt eller ettteckens värde inte ska tömma hela tabellen.
     *
     * @return antal borttagna rader
     */
    public int forgetSeen(String key, boolean prefix) {
        String k = key == null ? "" : truncateKey(key.trim());
        if (k.isBlank()) throw new IllegalArgumentException("key saknas");
        if (prefix && k.length() < MIN_FORGET_PREFIX) {
            throw new IllegalArgumentException(
                    "prefix maaste vara minst " + MIN_FORGET_PREFIX + " tecken (fick " + k.length() + ")");
        }
        int removed = prefix
                ? jdbc.update("DELETE FROM web_insight_seen WHERE seen_key LIKE ? ESCAPE '\\'", escapeLike(k) + "%")
                : jdbc.update("DELETE FROM web_insight_seen WHERE seen_key = ?", k);
        log.info("Web insights: glömde {} sedd nyckel/nycklar för {}{}", removed, k, prefix ? " (prefix)" : "");
        return removed;
    }

    /** URL:er innehåller ofta _ och ibland %, som annars är jokertecken i LIKE. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static final int MIN_FORGET_PREFIX = 12;

    private static String truncateKey(String key) {
        return key.length() > 500 ? key.substring(0, 500) : key;
    }

    /** Kör hela synken. Returnerar antal nya insikter som sparats. */
    public int syncAll() {
        ensureTable();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Web insight sync: GROQ_API_KEY saknas — hoppar över");
            jobStatus.markStarted(JOB_NAME);
            jobStatus.markFinished(JOB_NAME, 0, "GROQ_API_KEY saknas — synken hoppades över");
            return 0;
        }
        jobStatus.markStarted(JOB_NAME);
        int total = 0;
        List<String> perSource = new ArrayList<>();
        for (Source source : SOURCES) {
            try {
                SourceResult r = source.mode() == Mode.PAGE ? processPage(source) : processArticles(source);
                log.info("Web insights [{}]: {} nya insikter", source.expert(), r.saved());
                if (r.warning() != null) {
                    log.error("SCRAPER ALERT [{}]: {} — källan kan ha ändrat sin HTML-struktur. Manuell koll behövs.",
                            source.expert(), r.warning());
                }
                total += r.saved();
                perSource.add(source.expert() + ": " + r.label());
            } catch (Exception e) {
                log.warn("Web insights [{}]: källan misslyckades: {}", source.expert(), e.getMessage());
                perSource.add(source.expert() + ": FEL (" + e.getMessage() + ")");
            }
        }
        log.info("Web insight sync klar — {} nya insikter totalt", total);
        jobStatus.markFinished(JOB_NAME, total, String.join(", ", perSource));
        return total;
    }

    // ── Körstatus (läses av GET /api/admin/scrape-status) ────────────────────

    private static final String JOB_NAME = JobStatusService.JOB_WEB_INSIGHTS;

    /** Senaste körningens status. RUNNING = startad men inte klar (eller avbruten av omstart mitt i). */
    public Map<String, Object> lastRunStatus() {
        return jobStatus.lastRun(JOB_NAME);
    }

    // ── Artikelkällor ─────────────────────────────────────────────────────────

    private SourceResult processArticles(Source source) throws Exception {
        List<String> urls = new ArrayList<>(source.extraUrls());
        urls.addAll(discover(source));
        Set<String> unique = new LinkedHashSet<>(urls);
        if (unique.isEmpty()) return new SourceResult(0, "INGA LANKAR (0 artikel-URL:er)");

        // Elbilen svalt i det tysta: endpointen svarade 200 men innehöll bara 3 artiklar, så
        // dedupen tog allt varje natt och statusraden visade ett oskyldigt "0". Ett magert men
        // icke-tomt utbud är därför också värt en varning — det är så en källa dör numera.
        String warning = unique.size() < MIN_DISCOVERED_LINKS
                ? "MAGERT UTBUD (" + unique.size() + " artikel-URL:er)" : null;

        // Insikterna samlas för HELA källan och filtreras sedan i ett svep. Vakterna och
        // parafras-dedupen kostade förut ett Groq-anrop per artikel var (upp till tre extra
        // anrop × 12 artiklar per källa); nu blir det ett par anrop per källa i stället.
        int processed = 0;
        List<JsonNode> collected = new ArrayList<>();
        List<String> extracted = new ArrayList<>();
        for (String url : unique) {
            if (processed >= MAX_ARTICLES_PER_SOURCE) break;
            if (isSeen(url)) continue;
            processed++;
            try {
                Thread.sleep(FETCH_DELAY_MS);
                String text = fetchPageText(url);
                if (text.length() < MIN_TEXT_CHARS) {
                    log.debug("Web insights [{}]: för lite text ({} tecken): {}", source.expert(), text.length(), url);
                    markSeen(url);
                    continue;
                }
                List<JsonNode> found = extractInsights(text, source.kind(), url);
                if (found == null) {
                    // Groq svarade aldrig — lämna URL:en omarkerad så nästa körning tar om den
                    log.warn("Web insights [{}]: ingen extraktion (Groq svarade inte) — {} lämnas för nästa körning",
                            source.expert(), url);
                } else {
                    collected.addAll(found);
                    extracted.add(url);
                }
                Thread.sleep(GROQ_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (HttpStatusException e) {
                // En borttagen artikel återkommer aldrig, men den räknas ändå mot
                // MAX_ARTICLES_PER_SOURCE varje natt så länge den ligger kvar i källans
                // länklista — en död länk stjäl alltså en plats från en läsbar artikel.
                // Bara permanenta svar markeras: timeout och 5xx är övergående och ska
                // få ett nytt försök nästa körning.
                if (isPermanentlyGone(e.getStatusCode())) {
                    log.info("Web insights [{}]: {} svarade {} — markeras som färdig, artikeln finns inte längre",
                            source.expert(), url, e.getStatusCode());
                    markSeen(url);
                } else {
                    log.warn("Web insights [{}]: hoppar över {}: HTTP {}", source.expert(), url, e.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("Web insights [{}]: hoppar över {}: {}", source.expert(), url, e.getMessage());
            }
        }
        int saved = saveInsights(source.expert(), collected, null);
        // Först efter sparandet — annars tappas artiklar tyst om körningen dör i filtreringen
        extracted.forEach(this::markSeen);
        return new SourceResult(saved, warning);
    }

    // ── Sidkällor (car.info, Folksam) ─────────────────────────────────────────

    private SourceResult processPage(Source source) throws Exception {
        String text = fetchPageText(source.url());
        if (text.length() < MIN_TEXT_CHARS) {
            log.warn("Web insights [{}]: för lite text på sidan ({} tecken) — JS-renderad?", source.expert(), text.length());
            return new SourceResult(0, "FOR LITE TEXT (" + text.length() + " tecken, JS-renderad?)");
        }
        List<JsonNode> insights = extractInsights(text, source.kind(), source.url());
        if (insights == null) {
            log.warn("Web insights [{}]: ingen extraktion (Groq svarade inte)", source.expert());
            return new SourceResult(0, "GROQ SVARADE INTE");
        }
        int saved = saveInsights(source.expert(), insights, source.expert());
        Thread.sleep(GROQ_DELAY_MS);
        return SourceResult.of(saved);
    }

    /** Sparar insikter. dedupExpert != null → deduplicera varje insikt via source_ref-nyckel (sidkällor). */
    int saveInsights(String expert, List<JsonNode> insights, String dedupExpert) {
        int saved = 0;
        for (JsonNode ins : filterKnownDuplicates(filterStrict(expert, filterIrrelevant(insights)))) {
            String insightText = ins.path("insight").asText("");
            if (insightText.isBlank() || isTemplateEcho(ins)) continue;

            // Insikter utan märke visas aldrig (ExpertInsightService utesluter carMake == null
            // överallt) — spara dem inte.
            if (ins.path("car_make").asText("").isBlank()) {
                log.info("Web insights: hoppar över insikt utan bilmärke: {}", truncate(insightText, LOG_INSIGHT_CHARS));
                continue;
            }

            // Utan modell hamnar insikten i findForCarTitle:s makeOnly-hink och visas på VARJE
            // bil av märket — en N47-dieselvarning dyker upp på ett BMW i4-kort. Kuraterade
            // CSV-rader får fortsatt vara märkesbreda; skrapade får inte.
            if (ins.path("car_model").asText("").isBlank()) {
                log.info("Web insights: hoppar över märkesbred insikt utan modell: {}", truncate(insightText, LOG_INSIGHT_CHARS));
                continue;
            }

            if (dedupExpert != null) {
                String ref = ins.path("source_ref").asText("").trim();
                String key = dedupExpert + "|" + (ref.isBlank()
                        ? insightText.substring(0, Math.min(60, insightText.length())) : ref);
                if (isSeen(key)) continue;
                markSeen(key);
            }

            ExpertInsight stored = insightRepo.save(new ExpertInsight(
                    expert,
                    blankToNull(ins.path("car_make").asText("")),
                    blankToNull(ins.path("car_model").asText("")),
                    validOrNull(ins.path("fuel_type").asText(""), VALID_FUEL_TYPES),
                    validOrNull(ins.path("category").asText(""), VALID_CATEGORIES),
                    insightText,
                    parseRating(ins.path("rating"))));
            if (ins.path(UPCOMING_FIELD).asBoolean(false)) {
                upcomingService.mark(stored.getId());
            }
            saved++;
        }
        return saved;
    }

    // ── Relevansvakt ──────────────────────────────────────────────────────────

    /**
     * Slänger insikter utan köparrelevans (ej-Sverige-bilar, hypercars, auktioner m.m.)
     * via ett separat Groq-anrop. Körs före dubblettfiltret så skräp aldrig kostar
     * dedup-anrop. Detta är den enda spärren mot att irrelevant/hallucinerat innehåll
     * hamnar i den "verifierade" insiktsdatabasen — därför fail-closed vid fel: hoppar
     * över hela batchen den här körningen hellre än att spara den ofiltrerad.
     *
     * <p>Två anrop, inte ett: relevansen avgörs först, därefter frågar
     * {@link #UPCOMING_PROMPT} binärt vilka av de överlevande raderna som handlar om en bil
     * man ännu inte kan köpa. Kostnaden är ett extra Groq-anrop per chunk av det som
     * ÖVERLEVT vakten — se {@link #UPCOMING_PROMPT} för varför trevägsbeslutet inte höll.
     */
    List<JsonNode> filterIrrelevant(List<JsonNode> insights) {
        return markUpcomingRows(runGuard(RELEVANCE_PROMPT, insights, "relevansvakten"));
    }

    /**
     * Kommande-vakten: markerar, kastar aldrig. Raderna har redan passerat relevansvakten,
     * så ett uteblivet svar får inte kosta dem livet — då vore fixen tillbaka på ruta ett.
     * Hela chunken parkeras i stället som kommande: kön går att släppa ur med
     * {@code DELETE /api/admin/insights/{id}/upcoming}, medan en osläppt bil som hamnat på
     * ett bilkort inte går att ta tillbaka.
     */
    private List<JsonNode> markUpcomingRows(List<JsonNode> insights) {
        if (insights.isEmpty() || apiKey == null || apiKey.isBlank()) return insights;
        for (int start = 0; start < insights.size(); start += GUARD_BATCH_SIZE) {
            markUpcomingChunk(insights.subList(start, Math.min(insights.size(), start + GUARD_BATCH_SIZE)));
        }
        return insights;
    }

    private void markUpcomingChunk(List<JsonNode> insights) {
        Set<Integer> upcoming = null;
        try {
            String body = postGroq(guardModel, UPCOMING_PROMPT, buildRelevanceUserContent(insights),
                    GUARD_MAX_TOKENS, "kommandevakten");
            if (body != null) upcoming = parseIndexesOrNull(body, "upcoming");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Web insights: kommandevakten misslyckades ({} insikter): {}",
                    insights.size(), e.getMessage());
        }
        if (upcoming == null) {
            log.warn("Web insights: kommandevakten svarade inte användbart — parkerar hela chunken ({} insikter) som kommande",
                    insights.size());
            insights.forEach(WebInsightScraperService::markUpcoming);
            return;
        }
        for (int i = 0; i < insights.size(); i++) {
            if (!upcoming.contains(i)) continue;
            JsonNode ins = insights.get(i);
            markUpcoming(ins);
            log.info("Web insights: kommandevakten markerar {} {} som kommande modell: {}",
                    ins.path("car_make").asText(), ins.path("car_model").asText(),
                    truncate(ins.path("insight").asText(""), LOG_INSIGHT_CHARS));
        }
    }

    /**
     * Extravakterna. Körs efter den generella vakten (bara på det som överlevt den — färre
     * tokens) och före dubblettfiltret. Samma fail-closed-regel: hellre en tappad insikt än
     * en oskärskådad.
     *
     * <p>Två vakter med olika räckvidd:
     * <ul>
     *   <li>{@link #STRICT_RELEVANCE_PROMPT} på säljbara rader — bara {@link #STRICT_SOURCES},
     *       eftersom den prövar säljbarhet i Sverige och det är CarUp:s översatta amerikanska
     *       innehåll som bevisligen läcker där.</li>
     *   <li>{@link #STRICT_UPCOMING_PROMPT} på kommande rader — ALLA källor. Kön fylls inte av
     *       den källa vakten byggdes för: natten 2026-08-06 kom alla sex nya rader från
     *       Teknikens Värld och M3 medan CarUp inte skrev om en enda osläppt bil, så vakten
     *       fick noll rader att döma för andra natten i rad. En av de sex (id 1178, "Volvo
     *       planerar att öka produktionen av den kommande EX60") är exakt en produktionsrad
     *       som prompten stoppar — men M3 är inte strikt källa, så den mötte aldrig vakten.
     *       Ämnet avgör felkällan här, inte källan: om en bil inte går att provköra är nästan
     *       allt som skrivs om den fabriks- och pressmaterial, oavsett vem som skriver.</li>
     * </ul>
     *
     * <p>Kommande modeller prövas alltså mot en egen prompt i stället för att lämnas oprövade.
     * Extravaktens första regel — "bilen går inte att köpa i Sverige idag" — är exakt
     * negationen av relevansvaktens KOMMANDE-definition, så de två vakterna dödade
     * varandras beslut: nattkörningen 2026-08-02 flaggade Audi Q9, Zeekr 9X, Audi Q6 e-tron
     * och Mercedes GLC Electric som kommande, och extravakten stoppade alla fyra sekunder
     * senare. Eftersom CarUp var enda strikta källan kunde insight_upcoming aldrig få en rad.
     * Att helt hoppa över dem var för trubbigt åt andra hållet: kön fick sina första rader
     * 2026-08-04 och två av fem var sådant extravakten fångar (dollarpris för USA, fabrikens
     * produktionsvolym). {@link #STRICT_UPCOMING_PROMPT} är därför samma granskning utan
     * säljbarhetsregeln — den frågan har relevansvakten redan avgjort.
     */
    List<JsonNode> filterStrict(String expert, List<JsonNode> insights) {
        boolean striktKalla = STRICT_SOURCES.contains(expert);
        Set<String> kommandeBilar = upcomingCarKeys(insights);
        List<JsonNode> saljs = new ArrayList<>();
        List<JsonNode> kommande = new ArrayList<>();
        for (JsonNode ins : insights) {
            if (!isUpcoming(ins) && kommandeBilar.contains(carKey(ins))) {
                markUpcoming(ins);
                // markUpcoming biter bara på ObjectNode — logga det som faktiskt hände
                if (isUpcoming(ins)) {
                    log.info("Web insights: ärver kommande-markering inom batchen för {} {}: {}",
                            ins.path("car_make").asText(), ins.path("car_model").asText(),
                            truncate(ins.path("insight").asText(""), LOG_INSIGHT_CHARS));
                }
            }
            (isUpcoming(ins) ? kommande : saljs).add(ins);
        }
        // Ingen kommande rad och ingen säljbarhetsvakt att köra: spara ett Groq-anrop i en
        // körning som redan är ~2/3 väntan.
        if (kommande.isEmpty() && !striktKalla) return insights;
        Set<JsonNode> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!saljs.isEmpty()) {
            kept.addAll(striktKalla
                    ? runGuard(STRICT_RELEVANCE_PROMPT, saljs, "extravakten [" + expert + "]")
                    : saljs);
        }
        if (!kommande.isEmpty()) {
            kept.addAll(runGuard(STRICT_UPCOMING_PROMPT, kommande, "extravakten [" + expert + "/kommande]"));
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode ins : insights) {
            if (kept.contains(ins)) result.add(ins);
        }
        return result;
    }

    /**
     * Vakterna körs per källa (inte per artikel) sedan 2026-07-31 — men en hel källas
     * insikter i en prompt kan bli lång, så batchen chunkas. Varje chunk är sitt eget
     * fail-closed-fönster: ett Groq-fel tappar den chunken, inte hela källan.
     */
    private List<JsonNode> runGuard(String prompt, List<JsonNode> insights, String label) {
        if (insights.isEmpty() || apiKey == null || apiKey.isBlank()) return insights;
        List<JsonNode> kept = new ArrayList<>();
        for (int start = 0; start < insights.size(); start += GUARD_BATCH_SIZE) {
            List<JsonNode> chunk = insights.subList(start, Math.min(insights.size(), start + GUARD_BATCH_SIZE));
            kept.addAll(runGuardChunk(prompt, chunk, label));
        }
        return kept;
    }

    /** Vakterna stoppar rader; markeringen av kommande modeller sköts av {@link #markUpcomingChunk}. */
    private List<JsonNode> runGuardChunk(String prompt, List<JsonNode> insights, String label) {
        Set<Integer> irrelevant;
        try {
            String body = postGroq(guardModel, prompt, buildRelevanceUserContent(insights), GUARD_MAX_TOKENS, label);
            if (body == null) {
                log.warn("Web insights: {} fick inget svar — hoppar över batchen ({} insikter) den här körningen", label, insights.size());
                return List.of();
            }
            // Ett tomt eller oparsbart svar är INTE "inget var irrelevant". Reasoning-modellen
            // kan bränna hela tokenbudgeten och svara 200 med tomt content — tolkas det som en
            // tom irrelevant-lista blir vakten en tyst nolla och batchen sparas ofiltrerad.
            irrelevant = parseIndexesOrNull(body, "irrelevant");
            if (irrelevant == null) {
                log.warn("Web insights: {} svarade utan användbar lista — hoppar över batchen ({} insikter) den här körningen",
                        label, insights.size());
                return List.of();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("Web insights: {} misslyckades — hoppar över batchen ({} insikter) den här körningen: {}",
                    label, insights.size(), e.getMessage());
            return List.of();
        }
        List<JsonNode> kept = new ArrayList<>();
        for (int i = 0; i < insights.size(); i++) {
            JsonNode ins = insights.get(i);
            if (irrelevant.contains(i)) {
                log.info("Web insights: {} stoppar {} {}: {}", label,
                        ins.path("car_make").asText(), ins.path("car_model").asText(),
                        truncate(ins.path("insight").asText(""), LOG_INSIGHT_CHARS));
                continue;
            }
            kept.add(ins);
        }
        return kept;
    }

    /** Fältet läses av saveInsights och följer med insikten genom dedupen. */
    private static void markUpcoming(JsonNode ins) {
        if (ins instanceof ObjectNode node) node.put(UPCOMING_FIELD, true);
    }

    static boolean isUpcoming(JsonNode ins) {
        return ins.path(UPCOMING_FIELD).asBoolean(false);
    }

    /**
     * Relevansvakten avgör KOMMANDE per rad, och domen är inte stabil: natten 2026-08-05 kom
     * tre CarUp-rader om samma osläppta bil (Audi A2 e-tron) och bara vikten markerades. De två
     * andra — cW 0,24 och WLTP-förbrukningen — hamnade därmed i säljbar-gruppen och stoppades av
     * säljbarhetsregeln, alltså exakt fällan {@link #STRICT_UPCOMING_PROMPT} byggdes för att
     * undvika. A/B mot samma batch med produktionsparametrar gav fyra olika uppdelningar på sex
     * observationer, så det går inte att prompta bort: en rad om en bil som redan är klassad som
     * kommande ärver därför markeringen inom batchen. Bilar utan både märke och modell grupperas
     * aldrig — en tom nyckel hade dragit ihop orelaterade rader.
     */
    private static Set<String> upcomingCarKeys(List<JsonNode> insights) {
        Set<String> keys = new HashSet<>();
        for (JsonNode ins : insights) {
            if (isUpcoming(ins)) {
                String key = carKey(ins);
                if (key != null) keys.add(key);
            }
        }
        return keys;
    }

    /** null när märke eller modell saknas — se {@link #upcomingCarKeys}. */
    private static String carKey(JsonNode ins) {
        String make = ins.path("car_make").asText("").trim().toLowerCase(Locale.ROOT);
        String model = ins.path("car_model").asText("").trim().toLowerCase(Locale.ROOT);
        return make.isEmpty() || model.isEmpty() ? null : make + " " + model;
    }

    static final String UPCOMING_FIELD = "_upcoming";

    static String buildRelevanceUserContent(List<JsonNode> insights) {
        StringBuilder sb = new StringBuilder("INSIKTER:\n");
        for (int i = 0; i < insights.size(); i++) {
            JsonNode c = insights.get(i);
            sb.append(i).append(" (").append(c.path("car_make").asText("").trim()).append(" ")
                    .append(c.path("car_model").asText("").trim()).append("): ")
                    .append(c.path("insight").asText("")).append("\n");
        }
        return sb.toString();
    }

    // ── Dubblettfiltrering mot befintliga insikter ────────────────────────────

    /**
     * Släpper bara igenom insikter som inte redan finns i DB för samma bil:
     * först normaliserad textjämförelse (gratis), sedan ett Groq-anrop för
     * parafraser. Insikter utan märke+modell och alla fel-lägen släpps igenom
     * ofiltrerade — hellre en dubblett än en tappad insikt.
     */
    List<JsonNode> filterKnownDuplicates(List<JsonNode> insights) {
        List<JsonNode> kept = new ArrayList<>();
        List<JsonNode> candidates = new ArrayList<>();
        Map<String, List<String>> existingByCar = new LinkedHashMap<>();
        for (JsonNode ins : insights) {
            String text = ins.path("insight").asText("");
            String make = ins.path("car_make").asText("").trim();
            String model = ins.path("car_model").asText("").trim();
            if (text.isBlank() || make.isBlank() || model.isBlank()) {
                kept.add(ins);
                continue;
            }
            List<String> existing = existingByCar.computeIfAbsent(make + " " + model,
                    k -> existingTexts(make, model));
            String norm = normalizeForCompare(text);
            if (existing.stream().anyMatch(e -> normalizeForCompare(e).equals(norm))) {
                log.info("Web insights: hoppar över exakt dubblett för {} {}", make, model);
                continue;
            }
            if (existing.isEmpty()) {
                // först i batchen för en ny bil — sparas, och senare rader i samma
                // batch jämförs mot den (fångar AI:ns egna upprepningar i en artikel)
                kept.add(ins);
                existing.add(text);
                continue;
            }
            candidates.add(ins);
        }
        if (!candidates.isEmpty()) {
            Set<Integer> dups = paraphraseDuplicates(candidates, existingByCar);
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode c = candidates.get(i);
                if (dups.contains(i)) {
                    log.info("Web insights: hoppar över parafras-dubblett för {} {}: {}",
                            c.path("car_make").asText(), c.path("car_model").asText(),
                            truncate(c.path("insight").asText(""), LOG_INSIGHT_CHARS));
                } else {
                    kept.add(c);
                }
            }
        }
        return kept;
    }

    /**
     * Befintliga insiktstexter för samma bil. Märket matchas med prefix åt båda hållen
     * och modellen på token-delmängd — AI:n stavar samma bil olika mellan artiklar
     * ("Mercedes-Benz CLA 45 4MATIC+" / "Mercedes AMG CLA 45 4Matic+"), och exakt
     * matchning släppte igenom hela dubblettuppsättningar.
     */
    private List<String> existingTexts(String make, String model) {
        return insightRepo.findByMakePrefix(make, PageRequest.of(0, 200)).stream()
                .filter(e -> sameCar(model, e.getCarModel()))
                .limit(15)
                .map(ExpertInsight::getInsight)
                .filter(t -> t != null && !t.isBlank())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Samma bil om den ena modellens tokens är en delmängd av den andras ("EV4" ⊆ "EV4 AWD"). */
    static boolean sameCar(String a, String b) {
        Set<String> ta = modelTokens(a);
        Set<String> tb = modelTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        return ta.containsAll(tb) || tb.containsAll(ta);
    }

    private static Set<String> modelTokens(String s) {
        if (s == null) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String t : s.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!t.isBlank()) tokens.add(t);
        }
        return tokens;
    }

    /** Gemener + all interpunktion/whitespace bort — fångar omimporter och triviala varianter. */
    static String normalizeForCompare(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private Set<Integer> paraphraseDuplicates(List<JsonNode> candidates, Map<String, List<String>> existingByCar) {
        if (apiKey == null || apiKey.isBlank()) return Set.of();
        try {
            String body = postGroq(guardModel, DEDUP_PROMPT,
                    buildDedupUserContent(candidates, existingByCar), 500, "parafras-dedup");
            return body == null ? Set.of() : parseDuplicateIndexes(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (Exception e) {
            log.warn("Web insights: parafras-dedup misslyckades — sparar utan filtrering: {}", e.getMessage());
            return Set.of();
        }
    }

    static String buildDedupUserContent(List<JsonNode> candidates, Map<String, List<String>> existingByCar) {
        Set<String> cars = new LinkedHashSet<>();
        for (JsonNode c : candidates) {
            cars.add(c.path("car_make").asText("").trim() + " " + c.path("car_model").asText("").trim());
        }
        StringBuilder sb = new StringBuilder("BEFINTLIGA INSIKTER:\n");
        for (String car : cars) {
            sb.append("\n").append(car).append(":\n");
            for (String t : existingByCar.getOrDefault(car, List.of())) {
                sb.append("- ").append(t).append("\n");
            }
        }
        sb.append("\nNYA KANDIDATER:\n");
        for (int i = 0; i < candidates.size(); i++) {
            JsonNode c = candidates.get(i);
            sb.append(i).append(" (").append(c.path("car_make").asText("").trim()).append(" ")
                    .append(c.path("car_model").asText("").trim()).append("): ")
                    .append(c.path("insight").asText("")).append("\n");
        }
        return sb.toString();
    }

    Set<Integer> parseDuplicateIndexes(String responseBody) {
        return parseIndexes(responseBody, "duplicates");
    }

    /** Fail open — tom mängd betyder "filtrera inget". Används av dedupen. */
    Set<Integer> parseIndexes(String responseBody, String field) {
        Set<Integer> parsed = parseIndexesOrNull(responseBody, field);
        return parsed == null ? Set.of() : parsed;
    }

    /**
     * Som {@link #parseIndexes} men skiljer "modellen svarade tom lista" från "inget
     * användbart svar" — null betyder det senare. Vakterna måste kunna se skillnaden:
     * en reasoning-modell som bränt tokenbudgeten svarar 200 med tomt content, och den
     * tystnaden får inte läsas som ett godkännande av hela batchen.
     */
    Set<Integer> parseIndexesOrNull(String responseBody, String field) {
        try {
            String content = contentOf(responseBody);
            if (content.isBlank()) {
                log.warn("Web insights: tomt {}-svar (tokenbudgeten kan ha gått till reasoning)", field);
                return null;
            }
            JsonNode arr = mapper.readTree(content).path(field);
            if (!arr.isArray()) return null;
            Set<Integer> out = new HashSet<>();
            arr.forEach(n -> { if (n.canConvertToInt()) out.add(n.asInt()); });
            return out;
        } catch (Exception e) {
            log.warn("Web insights: kunde inte parsa {}-svar: {}", field, e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // category/fuel_type matchar användarens sökpreferenser i buildExpertContext — ett påhittat
    // värde utanför listan gör ingen skada, men ett FELAKTIGT (Ferrari som "ekonomibil") förgiftar
    // rekommendationsprompten. Whitelist + promptregeln ovan håller fälten ärliga.
    private static final Set<String> VALID_CATEGORIES =
            Set.of("ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil");
    private static final Set<String> VALID_FUEL_TYPES =
            Set.of("elbil", "bensin", "diesel", "hybrid", "laddhybrid");

    /** AI:n ekar ibland fältmallen tillbaka som en rad ("car_make car_model" / "insight") — hittades 6 st i DB. */
    static boolean isTemplateEcho(JsonNode ins) {
        return "insight".equalsIgnoreCase(ins.path("insight").asText("").trim())
                || "car_make".equalsIgnoreCase(ins.path("car_make").asText("").trim());
    }

    static String validOrNull(String s, Set<String> allowed) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return allowed.contains(v) ? v : null;
    }

    private static Integer parseRating(JsonNode node) {
        if (node.canConvertToInt()) {
            int r = node.asInt();
            return (r >= 1 && r <= 10) ? r : null;
        }
        try {
            int r = Integer.parseInt(node.asText("").trim());
            return (r >= 1 && r <= 10) ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Artikelupptäckt ───────────────────────────────────────────────────────

    List<String> discover(Source source) throws Exception {
        return switch (source.discover()) {
            case SITEMAP -> discoverSitemap(source.url());
            case RSS -> discoverRss(source.url());
            case LISTING -> discoverListing(source);
            case WPJSON -> discoverWpJson(source);
            case NONE -> List.of();
        };
    }

    /**
     * WPJSON-källans url kan innehålla flera endpoints separerade med komma — sajter som
     * Elbilen lägger sitt redaktionella material i egna posttyper i stället för "posts",
     * och då räcker inte en enda endpoint. Länkarna varvas inte, de läggs i tur och ordning.
     */
    private List<String> discoverWpJson(Source source) throws Exception {
        List<String> links = new ArrayList<>();
        for (String endpoint : source.url().split(",")) {
            links.addAll(parseWpJsonLinks(fetchRaw(endpoint.trim())));
        }
        return links;
    }

    /** WordPress REST API: [{"link":"https://..."}, ...] — nyaste först. */
    List<String> parseWpJsonLinks(String json) throws Exception {
        List<String> links = new ArrayList<>();
        for (JsonNode post : mapper.readTree(json)) {
            String link = post.path("link").asText("");
            if (!link.isBlank()) links.add(link);
        }
        return links;
    }

    /** WordPress-sitemapindex: ta senaste post-sitemapen och returnera dess nyaste URL:er. */
    private List<String> discoverSitemap(String url) throws Exception {
        String index = fetchRaw(url);
        List<String> children = matchAll(index, "<loc>([^<]+)</loc>");
        List<String> postMaps = children.stream().filter(c -> c.toLowerCase().contains("post")).toList();
        if (postMaps.isEmpty()) postMaps = children;
        if (postMaps.isEmpty()) return List.of();

        String xml = fetchRaw(postMaps.get(postMaps.size() - 1)); // nyaste post-sitemapen ligger sist
        Matcher m = Pattern.compile("<url>\\s*<loc>([^<]+)</loc>(?:\\s*<lastmod>([^<]+)</lastmod>)?").matcher(xml);
        List<String[]> entries = new ArrayList<>();
        while (m.find()) entries.add(new String[]{m.group(1), m.group(2) == null ? "" : m.group(2)});
        entries.sort((a, b) -> b[1].compareTo(a[1]));
        return entries.stream().map(e -> e[0]).toList();
    }

    private List<String> discoverRss(String url) throws Exception {
        String xml = fetchRaw(url);
        List<String> links = matchAll(xml, "(?s)<item>.*?<link>([^<]+)</link>");
        if (links.isEmpty()) links = matchAll(xml, "(?s)<item>.*?<link><!\\[CDATA\\[([^\\]]+)\\]\\]></link>");
        return links;
    }

    private List<String> discoverListing(Source source) throws Exception {
        String html = fetchRaw(source.url());
        String listingPath = source.url().replace(source.base(), "").replaceAll("/$", "");
        List<String> urls = new ArrayList<>();
        for (String path : matchAll(html, source.linkPattern())) {
            if (path.replaceAll("/$", "").equals(listingPath)) continue;
            urls.add(source.base() + path);
        }
        return urls;
    }

    private static List<String> matchAll(String text, String regex) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) result.add(m.group(1));
        return result;
    }

    // ── Hämtning ──────────────────────────────────────────────────────────────

    private String fetchRaw(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "sv-SE,sv;q=0.9")
                .timeout(FETCH_TIMEOUT_MS)
                .ignoreContentType(true)
                .execute().body();
    }

    private String fetchPageText(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "sv-SE,sv;q=0.9")
                .timeout(FETCH_TIMEOUT_MS)
                .get();
        doc.select("script, style, noscript, svg, nav, footer, header").remove();
        return doc.body() == null ? "" : doc.body().text();
    }

    // ── Groq-extraktion ───────────────────────────────────────────────────────

    /**
     * Returnerar {@code null} när Groq aldrig svarade (kvarstående 429 eller fel) — det är
     * inte samma sak som en tom lista, som betyder "svarade, hittade inget". Skillnaden
     * avgör om artikeln får markeras som läst: tidigare gav båda fallen en tom lista, så en
     * rate limit-tappad artikel bokfördes som färdigbehandlad och kom aldrig tillbaka
     * (carup.se/skrackljud-i-volvos-motor... tappades så 2026-08-01 och lästes aldrig om).
     */
    List<JsonNode> extractInsights(String text, String kind, String label) throws Exception {
        String trimmed = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        String body = postGroq(SYSTEM_PROMPT, "Källa: " + kind + "\n\nText:\n\n" + trimmed, 1500, label);
        return body == null ? null : parseInsightJson(body, label);
    }

    /** Extraktionen körs på insiktsmodellen. */
    private String postGroq(String systemPrompt, String userContent, int maxTokens, String label) throws Exception {
        return postGroq(insightModel, systemPrompt, userContent, maxTokens, label);
    }

    /**
     * Skickar en chat completion till Groq. Returnerar svarskroppen, eller null vid fel/kvarstående 429.
     * Paketprivat för att testerna ska kunna spionera på vilken vaktprompt en batch faktiskt får.
     */
    String postGroq(String model, String systemPrompt, String userContent, int maxTokens, String label) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)),
                "max_tokens", maxTokens,
                "temperature", 0.2,
                // gpt-oss är en reasoning-modell — utan low kan hela tokenbudgeten gå åt till reasoning
                "reasoning_effort", "low");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        for (int attempt = 0; attempt < GROQ_MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                long wait = retryDelayMs(resp.headers().firstValue("retry-after").orElse(null),
                        resp.body(), attempt);
                log.info("Web insights: Groq rate limit — väntar {}s ({})", wait / 1000.0, label);
                Thread.sleep(wait);
                continue;
            }
            if (resp.statusCode() != 200) {
                log.warn("Web insights: Groq {} för {}: {}", resp.statusCode(), label, truncate(resp.body(), 200));
                return null;
            }
            return resp.body();
        }
        log.warn("Web insights: rate limit kvarstår efter {} försök — hoppar över {}", GROQ_MAX_ATTEMPTS, label);
        return null;
    }

    /**
     * Hur länge vi ska vänta på en 429. Groq säger själv till — i <code>retry-after</code>-headern
     * (sekunder) och i felmeddelandet ("try again in 2m59.56s"). Den fasta trappan 30/60/90 s
     * som stod här förut kostade 8 av 12 minuter i nattkörningen 2026-07-31: alla 16 väntor
     * loggades som första försöket, dvs. omförsöket lyckades varje gång och 30 s var för mycket.
     * Taket finns för att en enstaka lång gräns inte ska binda hela synken; golvet för att
     * inte hamra vidare direkt.
     */
    static long retryDelayMs(String retryAfterHeader, String body, int attempt) {
        Long fromServer = parseSeconds(retryAfterHeader);
        if (fromServer == null) fromServer = parseWaitFromBody(body);
        long wait = fromServer != null ? fromServer : DEFAULT_BACKOFF_MS * (attempt + 1);
        return Math.max(MIN_BACKOFF_MS, Math.min(MAX_BACKOFF_MS, wait));
    }

    /** "2.5" / "30" → millisekunder. Ogiltigt eller saknat → null. */
    private static Long parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return (long) (Double.parseDouble(raw.trim()) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Plockar "try again in 2m59.56s" ur felkroppen när headern saknas. */
    private static Long parseWaitFromBody(String body) {
        if (body == null) return null;
        Matcher m = RETRY_IN_PATTERN.matcher(body);
        if (!m.find()) return null;
        long ms = 0;
        if (m.group(1) != null) ms += Long.parseLong(m.group(1)) * 60_000;
        if (m.group(2) != null) ms += (long) (Double.parseDouble(m.group(2)) * 1000);
        return ms;
    }

    private String contentOf(String responseBody) throws Exception {
        String content = mapper.readTree(responseBody)
                .path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.startsWith("```")) {
            content = content.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "");
        }
        return content;
    }

    List<JsonNode> parseInsightJson(String responseBody, String label) {
        try {
            JsonNode insights = mapper.readTree(contentOf(responseBody)).path("insights");
            if (!insights.isArray()) return List.of();
            List<JsonNode> result = new ArrayList<>();
            insights.forEach(result::add);
            return result;
        } catch (Exception e) {
            log.warn("Web insights: kunde inte parsa JSON-svar för {}: {}", label, e.getMessage());
            return List.of();
        }
    }

    static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
