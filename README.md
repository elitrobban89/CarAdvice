# CarAdvice – AI Bilrådgivning

En AI-driven bilrådgivare byggd med Java Spring Boot och Groq AI. Användaren fyller i sina preferenser i ett WordPress-formulär och får tre skräddarsydda bilrekommendationer på sekunder.

**Live:** [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)

---

## Funktioner

### Sök & rekommendationer
- Rekommenderar välrecenserade bilar baserat på kategori, budget och körbehov
- Stöd för ekonomibil, familjebil, SUV, elbil, laddhybrid och småbil
- Drivmedelsfilter: bensin, diesel, hybrid — döljs automatiskt för elbil/laddhybrid
- **Växellådsfilter:** manuell / automat — döljs automatiskt för elbil/laddhybrid; AI-prompten begränsas till vald växellåda
- Budget-slider med tickmärken och live-uppdaterat värde:
  - **Köp-läge** (standard): 50 000–1 000 000 kr, steg 25 000 kr (tickmärken: 50k · 200k · 400k · 700k · 1M)
  - **Leasing-läge:** 1 000–15 000 kr/mån, steg 250 kr — AI konverterar till ungefärligt listpris (×70) för kontextuell matchning
  - Köp/Leasing-knappen sitter inline i budget-etiketten; separata värden sparas per läge
- Varnar vid orimliga kombinationer (t.ex. ekonomibil + lyxbudget)
- Anpassar råd efter körsträcka, laddmöjlighet och ny/begagnad
- Skeleton-loading: tre kortskelelett med shimmer-animation visas direkt när sökningen startar
- Roterande laddmeddelanden med tips under skeleton-laddningen
- Delade länkar auto-söker direkt när sidan öppnas (URL-parametrar triggar sökning automatiskt)
- Sökhistorik: senaste 5 sökningar sparas lokalt (localStorage) — ett klick visar sparade rekommendationer direkt utan API-anrop
- **Sparade sökningar (server-side):** inloggade användare kan spara sökningar till databasen via "Spara sökning"-knapp; visas som chips ovanför historiken vid nästa besök på vilken enhet som helst (max 20 per konto)
- Historik-badge "📋 Sparad sökning (X min sedan)" visas när resultat kommer från historik
- "Sök igen →"-knapp tillgänglig för att hämta färska resultat efter historik-visning
- Blocket- och Bytbil-länk på varje bilkort — öppnar färdig sökning på märke och modell
- Nollställ-knapp som återställer formuläret till standardvärden
- Kopiera-knapp som kopierar alla rekommendationer till clipboard
- Dela-knapp som genererar en delbar länk med alla sökinställningar som URL-parametrar
- Formuläret sparas automatiskt i localStorage och återställs vid nästa besök
- URL-parametrar har alltid högre prioritet än localStorage (delad länk visas alltid korrekt)

### Bilkortsdesign
- Tre kort med **per-kort accentfärger**: Bil 1 lila, Bil 2 blå, Bil 3 grön
- **Aurora-glödeffekt** — animerat radiellt gradient-orb bakom varje kort (CSS `@keyframes` med staggerade delays)
- **Bilbilder** — varje kort hämtar automatiskt en thumbnail från Wikipedias öppna REST API; trefallsordning: direktträff (engelska) → suffixvarianter (`_EV`, `_electric`) → Wikipedia opensearch (fuzzy titelmatchning, upp till 3 kandidater); svenska Wikipedia som sista fallback; döljs tyst om ingen bild hittas
- Sektionsrubriker (Fördelar / Nackdel / Passar dig) med dividers för tydlig läsbarhet
- **"Fråga om denna bil"-knapp** på varje kort — markerar kortet med glödande ram och öppnar chatboten fokuserad på just den bilen

### Bränslespecifikationer (bensin/diesel)
- **⛽ Bensin/Diesel**-sektion per bilkort för fossildrivna bilar
- Visar förbrukning (l/mil), växellåda (t.ex. Automat 7-växlad), hästkrafter och motorvolym
- AI-genererade värden direkt i rekommendationen — `fuelSpec: null` för elbil/laddhybrid
- Visas även som egna rader i jämförelsetabellen

### Hästkrafter (alla biltyper)
- **💪 Hästkrafter** visas på alla bilkort oavsett drivlina — elbil, laddhybrid, bensin och diesel
- Nytt `horsepower`-fält i AI-svaret (toppnivå, utanför `fuelSpec`) — AI är instruerad att alltid ange systemeffekten i hk
- Bensin/diesel: hämtas även ur `fuelSpec.horsepower` (dubblerad källa)
- Jämförelsetabellen har en dedikerad **💪 Hästkrafter**-rad för alla biltyper

### Elbils- och laddhybriddata (EV-chip)
- **⚡ Elbil**-badge eller **🔌 Laddhybrid**-badge per bil
- WLTP-räckvidd, uppskattad sommar- och vinterräckvidd
- Laddfrekvens baserat på körsträcka ("ladda var 4:e dag")
- Max DC-laddning (kW), AC-laddning (kW) och hästkrafter
- Batteristorlek (kWh) och startpris
- **Prisvärdhetsbedömning** (Utmärkt / Bra / Ok prisvärdhet) — sammansatt poäng av räckvidd/kr (60 %), batteri/kr (40 %) + DC-laddningsbonus; visas nu även för bensin/diesel baserat på hk + förbrukning per kr
- Trestegsfuzzy-matchning mot EV-databasen: (1) alla titelord i lagrad namntext, (2) alla lagrade namnord som exakta ord i titel, (3) alla titelord som exakta ord i lagrat namn — förhindrar att "Kia Niro PHEV" matchar "Kia Niro EV" och att "MG4 2025" missar "MG4 Long Range"
- Årstal strippas innan matchning oavsett om det skrivs med eller utan parenteser

### Bagageutrymme
- **🧳 Bagageutrymme-chip** visas på alla bilkort — oavsett drivmedel
- Visar standardvolym (L) och maxvolym med nedfällt baksäte
- 110+ bilar seedade: elbilar, laddhybrider, bensin, diesel och mildhybrider
- Syns även i jämförelsetabellen som egen rad

### 5-års TCO-kalkyl
- **💰 5-års TCO**-sektion på varje bilkort — total ägandekostnad (Total Cost of Ownership)
- Fem kostnadsposter: värdeminskning + drivmedel + service + fordonsskatt + halvförsäkring
- Elbil: hemmaladdning 1,50 kr/kWh; skatt 360 kr/år (bara trafikavgift); försäkring ~7 000–10 000 kr/år
- PHEV: 50/50-split el/bensin; skatt 1 500 kr/år; service 6 000 kr/år
- Hybrid: lägre schablonsskatt (2 000–3 200 kr/år) än ren bensin
- Bensin/diesel: l/mil × km/år × bränslepris; service 8 000 kr/år; skatt 1 200–4 500 kr/år beroende på kategori
- Försäkring: schablonhalvförsäkring per kategori (3 500–9 000 kr/år), justeras för pris och elbil-påslag
- Restvärde: EV 42%, ICE/hybrid 48% efter 5 år
- Visar uppdelning: värdeminskning / drivmedel / service / skatt / försäkring + kronor/månad
- **Färgindikator (plupp)** bredvid TCO-summan: 🟢 ≤ 4 500 kr/mån · 🟡 4 500–8 000 kr/mån · 🔴 > 8 000 kr/mån
- Uppdateras automatiskt utifrån vald körsträcka (km/år från formuläret)
- Syns även som egen rad i jämförelsetabellen

### Jämförelsetabell
- Scrollbar tabell under de tre bilkorten
- Rader: Pris · Blocket nu · ✔ Fördelar · ⚠ Nackdel · 🎯 Expertrecension · 🛡️ Euro NCAP · 🧳 Bagageutrymme · 🔧 Motoralternativ · 💪 Hästkrafter · 📊 Prisvärdhet · 💰 5-års TCO
- **🎯 Expertrecension** — AI:ns bilexpertkommentar per bil sida vid sida
- **🛡️ Euro NCAP** — stjärnbetyg i guld + detaljprocent (vuxna/barn/fotgängare + testår)
- **🔧 Motoralternativ** — kommaseparerade motorvarianter från AI, varje variant som pill-chip; för elbilar: batteripaket + räckvidd
- Hästkrafter och Prisvärdhet visas för alla biltyper (inte bara EV)
- Vid EV/PHEV: WLTP · Sommar · Vinter · Laddning · DC max · AC max · Batteri

### Jämförelsekontext för AI (verifierade specs)
Innan AI-anropet hämtas verifierade specifikationer ur databasen och statiska kartor och injiceras i prompten som kontext:
- **Benutrymme bak (mm)** — bakre benutrymme i millimeter för 55+ modeller; data från evspecifications.com (mätvärden) resp. kända uppskattningar för övriga; EX30=821 mm, XC40/EX40/C40=917 mm, Model Y=1 029 mm m.fl.
- **Storleksklass** — om skillnaden i benutrymme överstiger 60 mm instrueras AI att explicit nämna det i jämförelsen ("EX30 är en kompakt bil, XC40 är en mellanstor SUV — avsevärt mer plats i baksätet")
- **Batterikemi (LFP / NMC)** — visas per bil i jämförelsetexten med förklaring: LFP (litiumjärnfosfat) kan laddas till 100 % dagligen utan slitage och är tåligare i kyla; NMC ger högre energitäthet och längre räckvidd per kg; ~55 modeller kartlagda
- **Snabbladdning (DC)** — AI förklarar att DC = snabbladdning (t.ex. längs motorväg), att ≥150 kW är bra och att AC = hemmaladdning (max ~22 kW); DC-maxvärde från databasen injiceras per bil
- **Bagageutrymme** — standard- och maxvolym (L med fällda säten) injiceras från `cargo_spec`-tabellen
- Färgkodade kolumnrubriker matchar kortens accentfärger
- **TCO-stapeldiagram** under tabellen — horisontella staplar för varje bil med fem färgkodade segment: 🟣 värdeminskning · 🟠 drivmedel · 🔵 service · 🟢 fordonsskatt · 🩷 halvförsäkring; hover-tooltip visar kostnad i tkr per post

### Chatbot
- Flytande knapp nere till höger med bil-ikon i glassmorphism-design; lila/indigo-tema
- Svarar på köpråd för alla drivmedel (bensin, diesel, hybrid, elbil)
- Streaming-svar — token för token via SSE; automatisk fallback till JSON om ReadableStream saknas
- **Kontextuell efter sökning** — FAB-etiketten och snabbknappar uppdateras med de rekommenderade bilarna
- **Per-bil-fokus** — klickar man "Fråga om denna bil" ändras chatboten till att fokusera på just den bilen med specifika chips: Berätta om, Driftkostnad & skatt, Tillförlitlighet & problem, Jämför med
- Dynamiska follow-up chips baserade på svarsinnehållet
- Rensa-knapp; max 10 frågor/minut per IP
- **Persistent chatthistorik** — sparas i `localStorage`; vid sidladdning visas tidigare konversation direkt utan välkomstmeddelande; FAB-etiketten ändras till "Fortsätt chatten" när historik finns
- **Modellsplit:** chatbot använder `llama-3.1-8b-instant` (500 000 TPD), rekommendationer använder `llama-3.3-70b-versatile` (100 000 TPD) — minskar tokenförbrukning med ~60%

### Produktionsstatus

Appen är funktionellt klar för produktion. Återstående steg för live-lansering:

1. Byt Stripe-nycklar till live-värden i Render-miljövariabler
2. Ta bort testläges-bannern i `subscribe.html`
3. Registrera live webhook-endpoint i Stripe Dashboard

> **Bilexpertsamarbete:** Infrastrukturen för RAG-data är klar. Expertinsikter attributeras "Bilexpert" tills samarbete med namngiven expert är bekräftat.

---

### Bilexpert-samarbete (RAG)
- PostgreSQL-tabell `expert_insight` lagrar bilexpertis som injiceras i AI-prompten
- Nuvarande exempeldata är AI-genererad och märkt **"Bilexpert"** — attributionen ersätts med expertens riktiga namn när samarbete är bekräftat
- Relevanta insikter väljs automatiskt utifrån sökt kategori och drivmedel; chatboten avslutar svaret med källnamnet
- **Kontakt tagen med Peter Esse** om att mata databasen med verklig expertdata — infrastrukturen är klar och redo att ta emot nya insikter (Python-script `extract_insights.py` extraherar insikter från YouTube-transkript och laddar upp via admin-endpoint)
- **138 insikter inladdade** från fyra expertkällor:
  - **Bilexpert** (37): manuellt skrivna för vanliga bilar på svenska marknaden
  - **Bilexpert** (16): extraherade från YouTube-transkript via `extract_insights.py`
  - **Bilprovningen** (30): `scrape_bilprovningen.py` genererar modellspecifika besiktningsråd baserade på officiell 2025-komponentstatistik (belysning 8,8%, bromsar 5,1%, spindelled 2,6% m.fl.)
  - **Teknikens Värld** (20): kuraterade testresultat — `tv_vb_insights.py` (10) + `more_insights.py` (10, täcker XC40 Recharge, Corolla, Kia Niro, VW ID.3, Audi Q4, Ford Puma, Cupra Born, Mazda CX-5, Mégane E-Tech, m.fl.)
  - **Vi Bilägare** (20): kuraterade testresultat och rekommendationer — `tv_vb_insights.py` (10) + `more_insights.py` (10, täcker Seat Leon, Ford Kuga, Subaru Forester, Hyundai Kona EV, VW Passat, BMW 3-serie, T-Roc, Toyota bZ4X, Opel Astra, m.fl.)
- Ny insikt läggs till med: `python more_insights.py --upload --admin-key KEY` eller direkt mot admin-endpoint
- Fler kan läggas till via admin-endpoint med `expert`-parametern

### CargoSpec-skrapare (Bilweb.se)
- Daglig schemalagd sync kl **03:00 Stockholm-tid** — hämtar alla bilmärken och modeller från Bilweb.se och lägger till nya poster i `cargo_spec`-tabellen med `null`-värde för bagagevolym
- Skrapar `bilweb.se/sok/bilar` för märkeslista, sedan per märkessida för modellnamn
- Hoppar över modeller som redan finns i databasen (normaliserad jämförelse)
- 1 500 ms fördröjning mellan requests för att undvika blockering
- Utökar autocomplete-listan (`/api/cars`) automatiskt utan manuell inmatning
- Manuell trigger via admin-endpoint:
  ```bash
  curl -X POST https://caradvice.onrender.com/api/admin/sync-cargo-specs \
    -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
  ```

### EV-spec-skrapare (ev-database.org)
- Daglig schemalagd sync kl 02:00 Stockholm-tid — hämtar WLTP-räckvidd, batteristorlek, DC/AC-laddning och EUR-pris per bil
- **Auto-skapar nya poster** — bilar som finns på ev-database.org men saknas i DB läggs till automatiskt med all tillgänglig data; EUR-pris konverteras till SEK (~11.5×)
- Fuzzy-matchning i två steg mot befintliga DB-poster — förhindrar dubbletter
- Priser uppdateras på befintliga poster där `priceKr=0`
- Synken håller ingen DB-koppling öppen — varje sparande är en egen kort transaktion (förhindrar connection pool-uttömning)
- **Strukturlarm** — loggar `ERROR` om cheatsheet-sidan returnerar 0 bilar (HTML-strukturen har ändrats) eller om >50 % av bilsidorna misslyckas; synksammanfattningen visar `updated/created/failed/total`
- Kör kl **02:00 Stockholm-tid** med `zone="Europe/Stockholm"` (hanterar DST automatiskt — ingen manuell UTC-offset); aborterar med `WARN` om den mot förmodan pågår efter 08:00
- Manuell trigger via admin-endpoint:
  ```bash
  curl -X POST https://caradvice.onrender.com/api/admin/sync-ev-specs \
    -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
  ```
- Returnerar `202 Accepted` direkt; synken körs i bakgrunden (virtual thread); resultat i serverloggar

### Dynamisk autocomplete (`/api/cars`)
- **`GET /api/cars`** — returnerar union av alla bilnamn ur `cargo_spec` + `ev_spec`, sorterat A–Ö
- Autocomplete-listan hämtas live vid sidladdning istället för hårdkodad JS-array
- Nattsynkens nya elbilsposter (inkl. batterivarianter) dyker automatiskt upp i autocomplete nästa sidladdning
- **650+ bilar täcks**: CargoSpec-modeller från Bilweb.se-sync + alla EvSpec-varianter (t.ex. "Tesla Model Y Long Range", "Volvo EX30 Single Motor")

### Prenumeration & betalning (Stripe)

> **Stripe körs för närvarande i testläge.** Inga riktiga betalningar genomförs. Testkort: `4242 4242 4242 4242`, valfritt datum och CVC.
> För att aktivera produktion: byt `STRIPE_SECRET_KEY` till `sk_live_...`, `STRIPE_WEBHOOK_SECRET` till live webhook-hemligheten, ta bort testläges-bannern i `subscribe.html` och uppdatera `STRIPE_PRICE_ID` till live-prisets ID. All övrig kod är produktionsklar.

### Korsåtkomst — båda tjänsterna ingår

En prenumeration på **49 kr/mån** ger tillgång till båda tjänsterna med samma konto och token:

- **AI Bilrådgivning** — [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)
- **AI EV Laddningsassistenten** — [elitrobban.se/elbilsladdning](https://elitrobban.se/elbilsladdning/)

`ca_token` lagras i `localStorage` under domänen `elitrobban.se` och delas automatiskt mellan sidorna. `ev-charging.js` (serveras av CarAdvice-backenden) agerar access guard på elbilsladdning-sidan — kontrollerar token mot `/api/auth/me` och visar antingen innehållet eller ett betalvägg-kort.

- Ej inloggad: **max 10 sökningar/timme** och **10 chattmeddelanden/minut** (IP-baserat)
- Inloggad (gratis konto): **30 sökningar/timme** och **30 chattmeddelanden/minut**
- Aktiv prenumerant (49 kr/mån): **obegränsade sökningar och chatt på båda tjänsterna**
- Konto skapas på `/subscribe.html` — öppnas i nytt fönster
- Betalning via Stripe Checkout (hosted betalningssida)
- Prenumerationsstatusen sparas i `ca_user`-tabellen och verifieras via sessionstoken (Bearer-header)
- Stripe webhook (raw JSON-parsning, versionsoberoende) uppdaterar status automatiskt vid betalning, förnyelse, avslut och paus
- Slutdatum för prenumerationen hämtas från Stripes `current_period_end` och visas på kontosidan
- Kontosidan (`/subscribe.html`) visar prenumerationsstatus, hur länge man varit prenumerant, startdatum, **periodens slut**, förnyelsestatus (grön/orange) — samt knapp för att **avsluta prenumeration** (cancel at period end via Stripe) eller **återaktivera** om avslut redan schemalagts
- `subscription_started_at` sätts vid första aktivering (ej vid förnyelse); `/api/auth/me` returnerar formaterat datum + ISO-sträng för duration-beräkning i klienten
- WordPress-snippeten visar prenumerationsrad med kvarvarande sökningar och en sammanslagen **"Prenumerera / Logga in"**-knapp (Demo-läge) — öppnar kontosidan som popup med korrekt `window.opener`

### Övrigt
- 2-timmars svar-cache på backend — identiska sökningar kostar inga tokens
- Cache-ålder visas i resultatet: "⚡ Cachat svar (X min sedan)"
- IP-baserad rate limiting: 10/h (gäst) · 30/h (inloggad) · obegränsat (prenumerant)
- **Rate limit-persistens** — rate limit-logg sparas i `rate_limit_log`-tabellen; vid restart/deploy laddas senaste timmens trafik från DB in i minnet via `@PostConstruct` (ingen IP kan nollställa sin kvot via cold start); varje tillåten sökning sparas asynkront utan request-latens; DB-poster äldre än 2 timmar rensas varje timme
- Vänliga svenska felmeddelanden med exakt återstartstid vid kvotgräns
- 35-sekunders timeout med cold start-hint
- **PWA-stöd** — `manifest.json` gör appen installerbar på Android/iOS
- **robots.txt** — `Disallow: /` på hela `caradvice.onrender.com`; backendn är inte en innehållssajt och ska inte indexeras av sökmotorer (innehållet indexeras via `elitrobban.se`)
- **Säkerhetsheaders** — sätts på alla svar via ett globalt filter i `WebConfig`: `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `X-XSS-Protection` och `Permissions-Policy`
- **Graceful degradation** — om DB är tillfälligt otillgänglig returneras AI-rekommendationer utan EV/cargo/expert-data istället för ett 500-fel
- **HikariCP begränsad till 3 kopplingar** med keepalive var 60:e sekund och `SELECT 1`-validering — optimerad för delad free-tier PostgreSQL

---

## Teknikstack

| Del | Teknologi |
|-----|-----------|
| Backend | Java 21, Spring Boot 3.2 |
| AI | Groq API (`llama-3.3-70b-versatile`) |
| HTML-parsning | Jsoup 1.17 (EV-skraparen) |
| Databas | PostgreSQL (Render) / H2 in-memory (lokal dev) |
| ORM | Spring Data JPA / Hibernate |
| Autentisering | spring-security-crypto (BCrypt), opaka sessionstoken |
| Betalning | Stripe (testläge) — Checkout + webhooks |
| Frontend | HTML/CSS/JS (WordPress Anpassad HTML) |
| Deploy | Render.com (Docker) |
| Monitorering | UptimeRobot |

---

## Projektstruktur

```
CarAdvice/
├── Dockerfile
├── pom.xml
├── wordpress-snippet.html          ← Klistra in på WordPress-sidan
└── src/main/
    ├── java/com/caradvice/
    │   ├── CarAdviceApplication.java
    │   ├── config/
    │   │   └── WebConfig.java         ← Global CORS (tillåter elitrobban.se + localhost)
    │   ├── controller/
    │   │   ├── AuthController.java    ← /api/auth/register, login, logout, me
    │   │   ├── CarController.java     ← REST-endpoints + admin sync-trigger + rate limit-persistens
    │   │   ├── StripeController.java  ← /api/stripe/checkout, cancel, reactivate, webhook
    │   │   └── UserController.java    ← /api/user/saved-searches (CRUD)
    │   ├── data/
    │   │   └── DataLoader.java     ← Seeder: expertinsikter, EV-specs, cargo-specs
    │   ├── model/
    │   │   ├── CarPreferences.java
    │   │   ├── CarRecommendation.java  ← inkl. evSpec + cargoSpec + fuelSpec
    │   │   ├── CargoSpec.java          ← JPA-entity: bagageutrymme
    │   │   ├── CargoSpecDto.java
    │   │   ├── EvSpec.java             ← JPA-entity: elbilsdata
    │   │   ├── EvSpecDto.java
    │   │   ├── ExpertInsight.java
    │   │   ├── FuelSpecDto.java        ← AI-genererad: förbrukning, växellåda, hk, motorvolym
    │   │   ├── RateLimitLog.java       ← JPA-entity: rate limit-logg för persistens över restart
    │   │   ├── SavedSearch.java        ← JPA-entity: sparad sökning kopplad till användare
    │   │   └── User.java               ← JPA-entity: användarkonto + prenumerationsstatus + slutdatum
    │   ├── repository/
    │   │   ├── CargoSpecRepository.java
    │   │   ├── EvSpecRepository.java
    │   │   ├── ExpertInsightRepository.java
    │   │   ├── RateLimitLogRepository.java
    │   │   ├── SafetyRatingRepository.java
    │   │   ├── SavedSearchRepository.java
    │   │   └── UserRepository.java
    │   ├── scraper/
    │   │   ├── CargoSpecSyncService.java      ← Jsoup-skrapare mot Bilweb.se
    │   │   ├── CargoSpecSyncScheduler.java    ← @Scheduled cron 03:00 Stockholm-tid
    │   │   ├── EvDatabaseScraperService.java  ← Jsoup-skrapare mot ev-database.org
    │   │   └── EvSpecSyncScheduler.java       ← @Scheduled cron 02:00 Stockholm-tid
    │   └── service/
    │       ├── CargoSpecService.java   ← Fuzzy-matchning på bilnamn → bagagevolym
    │       ├── EvSpecService.java      ← Fuzzy-matchning + räckvidd/laddberäkning
    │       ├── ExpertInsightService.java
    │       ├── GroqService.java        ← Groq AI, cache, felhantering
    │       ├── SafetyRatingService.java
    │       ├── SavedSearchService.java ← CRUD för sparade sökningar (max 20/användare)
    │       ├── StripeService.java      ← Checkout-session, webhook-hantering
    │       └── UserService.java        ← Register/login (BCrypt), sessionstoken
    └── resources/
        ├── application.properties
        └── static/
            ├── car-advice-main.js  ← Bilrådgivnings-UI (serveras av Render, laddas av WordPress)
            ├── car-advice-chat.js  ← Chattbot-UI (serveras av Render, laddas av WordPress)
            ├── ev-charging.js      ← Access guard för elbilsladdning-sidan (kontrollerar prenumeration)
            ├── cancel.html         ← Visas om Stripe-betalning avbryts
            ├── manifest.json       ← PWA-manifest
            ├── subscribe.html      ← Login/register + Stripe Checkout (öppnas i nytt fönster)
            ├── success.html        ← Visas efter lyckad Stripe-betalning; visar länkar till båda tjänsterna
            └── test.html           ← Lokal testmiljö
```

---

## Köra lokalt

**1. Sätt API-nyckel:**
```bash
export GROQ_API_KEY=din_nyckel
```

**2. Starta:**
```bash
mvn spring-boot:run
```

**3. Öppna:** `http://localhost:8080/test.html`

---

## API

### `POST /api/recommend`

**Request:**
```json
{
  "budget": 400000,
  "carCategory": "suv",
  "hasCharger": true,
  "kmPerYear": 15000,
  "usage": "familj",
  "passengers": 4,
  "newCar": true,
  "fuelType": "elbil",
  "transmission": "automat",
  "budgetType": "köp"
}
```

| Fält | Typ | Värden |
|------|-----|--------|
| `budget` | int | Kronor (köp) eller kr/mån (leasing) |
| `carCategory` | string | `ekonomibil`, `familjebil`, `suv`, `elbil`, `laddhybrid`, `smaabil` |
| `hasCharger` | boolean | Laddbox hemma |
| `kmPerYear` | int | Kilometer per år |
| `usage` | string | `pendling`, `familj`, `landsväg`, `stad` |
| `passengers` | int | 1–9 |
| `newCar` | boolean | Ny eller begagnad |
| `fuelType` | string | `bensin`, `diesel`, `hybrid`, `spelar ingen roll` |
| `transmission` | string | `manuell`, `automat`, `spelar ingen roll` (null = spelar ingen roll) |
| `budgetType` | string | `köp` (standard) eller `leasing` |

**Response:**
```json
{
  "success": true,
  "recommendations": [
    {
      "title": "Volvo EX30 (2024)",
      "price": "350 000 – 400 000 kr",
      "whyRecommended": "Teknikens Värld: bäst i test i klassen",
      "pros": ["WLTP 480 km", "Låg driftkostnad", "5-stjärnigt Euro NCAP"],
      "con": "Litet bagageutrymme",
      "fitSummary": "Passar en familj som vill ha prisvärd elbil med laddbox hemma.",
      "expertOpinion": "EX30 är det smartaste köpet under 400k just nu.",
      "safetyRating": "Euro NCAP 2023: 5 stjärnor (97% vuxna)",
      "evSpec": {
        "wltpKm": 480, "summerKm": 408, "winterKm": 336,
        "daysPerCharge": 5, "daysLabel": "ladda var 5:e dag",
        "batteryKwh": 51.0, "maxDcKw": 153, "maxAcKw": 11,
        "priceKr": 350000, "valueLabel": "Utmärkt prisvärdhet", "carType": "EV"
      },
      "cargoSpec": { "cargoLiters": 318, "cargoMaxLiters": 904 }
    }
  ]
}
```

### `POST /api/chat` / `POST /api/chat/stream`

Chattbot för köpråd. Stream-varianten returnerar SSE token för token.

```json
{ "messages": [{ "role": "user", "content": "Elbil eller laddhybrid?" }],
  "context": "Aktuella rekommendationer: 1. Volvo EX30..." }
```

### `POST /api/admin/sync-ev-specs`

Startar EV-spec-synken manuellt. Kräver `X-Admin-Key`-header.

```bash
curl -X POST https://caradvice.onrender.com/api/admin/sync-ev-specs \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
# → {"status":"sync started — check server logs for result"}
```

### `POST /api/admin/sync-cargo-specs`

Startar CargoSpec-synken (Bilweb.se) manuellt. Kräver `X-Admin-Key`-header. Returnerar antal nyligen tillagda poster.

```bash
curl -X POST https://caradvice.onrender.com/api/admin/sync-cargo-specs \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `POST /api/admin/import/cargospecs`

Importerar bagagevolym-data från CSV — lägger bara till nya poster, hoppar över befintliga. Format: `car_name,cargo_liters,cargo_max_liters`.

```bash
curl -X POST https://caradvice.onrender.com/api/admin/import/cargospecs \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL" \
  -H "Content-Type: text/plain" \
  --data-binary @cargo.csv
```

### `POST /api/admin/upsert/cargospecs`

Uppdaterar befintliga poster med `null`-volym OCH lägger till nya — används för att fylla i saknad bagagedata på bilar som Bilweb-synken lade till utan volymer. Format: `car_name,cargo_liters,cargo_max_liters`.

```bash
curl -X POST https://caradvice.onrender.com/api/admin/upsert/cargospecs \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL" \
  -H "Content-Type: text/plain" \
  --data-binary @cargo.csv
```

### `DELETE /api/admin/insights?expert=Name`

Tar bort alla expertinsikter för ett givet expertnamn. Kräver `X-Admin-Key`-header.

```bash
curl -X DELETE "https://caradvice.onrender.com/api/admin/insights?expert=Bilprovningen" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `GET /api/cars`

Returnerar sorterad lista med alla bilnamn (union av CargoSpec + EvSpec). Används av autocomplete-fälten.

```json
["Audi A3", "Audi Q4 e-tron", "BMW i4", "Dacia Spring", "MG4", "Tesla Model Y Long Range", "Volvo EX30", ...]
```

### `GET /api/health`
```json
{ "status": "OK" }
```

### Auth-endpoints

| Endpoint | Metod | Beskrivning |
|---|---|---|
| `/api/auth/register` | POST | Skapa konto `{ email, password }` → `{ email, token, subscriptionStatus }` |
| `/api/auth/login` | POST | Logga in `{ email, password }` → `{ email, token, subscriptionStatus }` |
| `/api/auth/logout` | POST | Ogiltigförklara sessionstoken (Bearer-header) |
| `/api/auth/me` | GET | Hämta inloggad användares info (Bearer-header) |
| `/api/stripe/checkout` | POST | Skapa Stripe Checkout-session → `{ url }` (Bearer-header krävs) |
| `/api/stripe/cancel` | POST | Avsluta prenumeration vid periodens slut (Bearer-header krävs) |
| `/api/stripe/reactivate` | POST | Återaktivera prenumeration (ångrar schemalagd avslutning, Bearer-header krävs) |
| `/api/stripe/webhook` | POST | Stripe webhook — uppdaterar prenumerationsstatus automatiskt |
| `/api/user/saved-searches` | POST | Spara sökning (prefsJson + recommendationsJson + label, Bearer-header krävs) |
| `/api/user/saved-searches` | GET | Lista sparade sökningar för inloggad användare |
| `/api/user/saved-searches/{id}` | DELETE | Ta bort en sparad sökning (Bearer-header krävs) |

---

## Databastabeller

| Tabell | Innehåll |
|--------|----------|
| `expert_insight` | Bilexpertinsikter (RAG-kontext för AI-prompten) — märkta "Bilexpert" tills samarbete bekräftas |
| `ev_spec` | WLTP-räckvidd, batteri, DC/AC-laddning, pris per EV/PHEV-modell — auto-utökas av daglig scraper |
| `cargo_spec` | Bagageutrymme (standard + max L) för 110+ bilmodeller |
| `safety_rating` | Euro NCAP-betyg per modell (45+ bilar) |
| `ca_user` | Användarkonton: email, BCrypt-lösenordshash, Stripe customer ID, prenumerationsstatus, startdatum, slutdatum, sessionstoken, token-utgångsdatum |
| `saved_search` | Sparade sökningar per användare: preferenser (JSON), rekommendationer (JSON), etikett, skapad-tid (max 20/användare) |
| `rate_limit_log` | Rate limit-logg för `/api/recommend` — IP + tidsstämpel; seedar in-memory-kartan vid restart; städas varje timme |

---

## Deploya på Render.com

1. Pusha till GitHub
2. Skapa **Web Service** → koppla repot → **Docker** runtime, branch `master`
3. Miljövariabler:

| Variabel | Beskrivning |
|---|---|
| `GROQ_API_KEY` | API-nyckel från console.groq.com |
| `DB_URL` | PostgreSQL JDBC-URL |
| `DB_USER` | Databasanvändarnamn |
| `DB_PASS` | Databaslösenord |
| `ADMIN_KEY` | Nyckel för admin-endpoints — sätt ett starkt slumpmässigt värde i Render |
| `STRIPE_SECRET_KEY` | Stripe API-nyckel (`sk_test_...` i testläge, `sk_live_...` i produktion) |
| `STRIPE_WEBHOOK_SECRET` | Stripe webhook signing secret (`whsec_...`) |
| `STRIPE_PRICE_ID` | Stripe Price ID för prenumerationsprodukten (`price_...`) |
| `APP_BASE_URL` | Bas-URL för success/cancel-redirect (`https://caradvice.onrender.com`) |
| `CORS_ALLOWED_ORIGINS` | Kommaseparerade tillåtna origins (default: `https://elitrobban.se,http://localhost:8080,http://localhost:3000`) |

EV-spec-synken körs automatiskt varje natt kl 03:00 UTC på Render-servern — ingen lokal dator behövs.

---

## Monitorering

| Monitor | URL | Intervall |
|---|---|---|
| WordPress-sida | `https://elitrobban.se/bilradgivning/` | 5 min |
| Backend | `https://caradvice.onrender.com/api/recommend/test` | 5 min |

Backend-monitorn håller Render-instansen varm och eliminerar cold starts.

---

## WordPress-integration

Klistra in `wordpress-snippet.html` i ett **Anpassad HTML**-block på valfri WordPress-sida.

> **OBS:** WordPress synkas inte automatiskt från GitHub. Vid uppdatering av `wordpress-snippet.html` måste koden klistras in manuellt i WordPress-blocket.

---

## Token-budget (Groq gratisplan)

Groq free tier ger **100 000 tokens/dag** för `llama-3.3-70b-versatile`. Varje sökning använder upp till **1 500 output-tokens** plus ~600–800 input-tokens — totalt ~50–60 unika sökningar/dag utan cache. Identiska sökprofiler returneras från 2-timmars cache utan tokenkostnad. Chattboten använder upp till **900 output-tokens** per meddelande (höjt från 600 för att undvika avskurna svar).

**Groq 429-fallback:** om `llama-3.3-70b-versatile` svarar med 429 (dagsgräns nådd) försöker `getRecommendation()` automatiskt en gång med `llama-3.1-8b-instant` — användaren märker inte bytet. Kastar bara fel om båda modellerna nekar.

---

## Senaste bugfixar

| Fix | Beskrivning |
|-----|-------------|
| TCO leasing-kalkyl | `caParseLeaseMonthly` läste köppriser (t.ex. "330 000 kr") som månadskostnad → TCO visades som ~18 miljoner. Fixat: parsar nu bara som månadsbelopp om strängen innehåller "mån"; faller tillbaka på användarens budget-slider som leasingkostnad |
| Elbilar: "obligatorisk årsavgift" | Chatbotten påstod att BYD/MG4 m.fl. har en obligatorisk årsavgift på 1 800 kr — det finns ingen sådan generell avgift i svensk lag. System-prompt korrigerad med faktaanvisning |
| Elbilar: "turbo/ej turbo" i fördelar | AI annoterade elbilars batterivarianter med "(turbo)" / "(ej turbo)". Fixat: turbo-terminologi förbjuds för elbil/laddhybrid i systempromptarna |
| BYD Seal, Genesis GV60, Toyota Camry borttagna | Dessa bilar säljs inte i Sverige; borttagna från EV-spec-databas och systempromptarna |
| Dacia Spring saknade EV-data | Dacia Spring fattades i ev_spec-tabellen — lade till (225 km WLTP, 26,8 kWh, 30 kW DC) |
| MG4 matchade inte EV-databasen | EV-spec-posterna hette "MG4 Long Range" m.fl. — "MG4 2025" hittade ingenting. Fixat med ny Pass 3 i fuzzy-matchningen + baspost "MG4" |
| Prenumerationslängd på kontosidan | Kontosidan visar nu "Prenumerant i: X månader/år" (beräknas live i webbläsaren via ISO-datum från `/api/auth/me`), "Startade: X" och "Förnyas: X" |
| Tidzon UTC→Stockholm | Render kör i UTC — datum formaterades i UTC vilket kunde ge fel dag. Nu konverteras alla prenumerationsdatum till `Europe/Stockholm` innan formatering; ISO-strängen får `Z`-suffix så att `new Date()` i webbläsaren räknar durationen korrekt |
| Backfill subscriptionStartedAt | Befintliga aktiva prenumeranter saknade startdatum (kolumnen tillkom efter deras aktivering). Vid uppstart sätts `subscriptionStartedAt = createdAt` för alla aktiva användare där fältet är null |
| Chattbot avskuren text | `max_tokens` för chat/chatStream höjt från 600→900 — Erik Naessén-citatet och längre svar klipptes mitt i meningen |
| Sessionstoken 30 dagars utgångstid | `token_expires_at`-kolumn i `ca_user` — token ogiltigförklaras automatiskt efter 30 dagar; rensas vid logout |
| Rate limiting på login/register | Max 10 inloggningsförsök per minut per IP — returnerar 429 vid överträdelse |
| Avsluta prenumeration | Knapp på kontosidan med bekräftelsedialog — kallar Stripe med `cancelAtPeriodEnd=true`; texten ändras från "Förnyas:" till "Avslutas:" |
| Ta bort backfill-kod | `@PostConstruct backfillSubscriptionStartedAt()` i UserService borttagen efter att ha körts en gång |
| `cancel_at_period_end` parsning | `current_period_end` finns inte på rotnivå i nyare Stripe API-versioner — faller nu tillbaka på `cancel_at` som alltid finns vid avbokning |
| `cancelAtPeriodEnd` null-säkerhet | Primitiv `boolean` kraschade vid inloggning för befintliga rader (NULL i DB) — ändrat till boxad `Boolean` med null-säker getter som defaultar till `false` |
| Periodens slut i kontovyn | Visar alltid "Periodens slut: X" plus separat förnyelsestatus — grön "✓ Förnyas automatiskt" eller orange "⚠ Förnyas inte" |
| `subscriptionEndsAt` sätts direkt vid avslut | Tidigare väntade på webhook för att sätta slutdatumet — nu läses `cancel_at` direkt från Stripes svar och sparas i samma DB-anrop |
| Stripe webhook-events kompletterade | Lade till `customer.subscription.updated`, `deleted`, `paused`, `resumed` och `invoice.payment_succeeded` i Stripe Dashboard — tidigare saknades dessa och cancel-synken fungerade inte |
| Återaktivera prenumeration | Ny knapp "Återaktivera prenumeration" visas på kontosidan när `cancelAtPeriodEnd=true` — kallar `/api/stripe/reactivate` som sätter `cancelAtPeriodEnd=false` i Stripe och läser nytt `current_period_end`; knappen växlar tillbaka till "Avsluta prenumeration" vid framgång |
| Global CORS-konfiguration | `WebConfig.java` ersätter `@CrossOrigin`-annotationen på CarController — alla `/api/**`-endpoints skyddas centralt; tillåtna origins konfigureras via `CORS_ALLOWED_ORIGINS`-miljövariabeln |
| Chatthistorik UX | Välkomstmeddelandet visades alltid vid sidladdning och dolde sparad historik — nu visas historiken direkt utan hälsning; FAB-etiketten ändras till "💬 Fortsätt chatten" när historik finns; chat scrollas automatiskt till botten |
| EV-scraper-larm | Scraper loggade tyst vid strukturfel — nu loggas `ERROR` om cheatsheet returnerar 0 URL:er och `WARN` om >50 % av bilsidorna misslyckas; synksammanfattning visar `updated/created/failed/total` |
| Scraper-tidsfönster | Cron ändrad till 02:00 Stockholm-tid med DST-hantering (`zone="Europe/Stockholm"`); scraper-loopen aborterar med `WARN` om den pågår efter 08:00 stockholmstid |
| `cancelAtPeriodEnd` setter NPE | Setter tog primitiv `boolean` — Hibernate skickar `null` för befintliga DB-rader utan värde, vilket kraschade vid unboxing; ändrat till `Boolean` (getterns null-check hanterar `null → false`) |
| robots.txt | Googlebot crawlade `/` utan begränsning — lagt till `robots.txt` med `Disallow: /` för att styra bort indexering från backendsdomänen |
| Säkerhetsheaders | Inga säkerhetsheaders sattes på svar — globalt filter i `WebConfig` lägger till `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `X-XSS-Protection: 0` och `Permissions-Policy` på alla svar |
| Sammanslagen "Prenumerera / Logga in"-knapp | Demo-läget visade två separata element ("Logga in"-länk + "Prenumerera"-knapp). Nu visas en enda knapp som öppnar kontosidan som popup |
| Logout-synk: "Konto" öppnas nu som popup | "Konto"-länken för inloggade prenumeranter följde `href` som vanlig länk — subscribe.html fick inget `window.opener` och CA_LOGOUT-meddelandet nådde aldrig WordPress-sidan vid utloggning därifrån. Löst: alla klick på `ca-login-link` (utom logout) öppnar nu subscribe.html via `caOpenSubscribe()` (popup med `window.opener`) |
| Stale token rensas vid sidladdning | `/api/auth/me` ignorerade 401-svar och lämnade `ca_token`/`ca_email`/`ca_status` i localStorage. WordPress-sidan visade då "✓ Prenumerant" även efter utloggning. Löst: vid non-OK svar rensas localStorage och baren återställs till Demo-läge |
| Utloggning visade fel text | `caUpdateSubBar()` anropades med 2 args vid logout — `remaining` blev `undefined` och visade `"undefined av 10 sökningar"` |
| Storage-event efter logout | `ca_status`-borttagning skickade `!isActive = true` som `isLoggedIn` → visade "Inloggad" efter utloggning; nu reset till gäst-vy |
| `FuelSpecDto` null-säkerhet | Primitiva `double`/`int` → boxade `Double`/`Integer` så att `null`-fält från AI inte kraschar deserialisering |
| `isRateLimited` map-lookup | `compute()` följt av extra `map.get()` — använder nu returvärdet från `compute()` direkt |
| Lösenordsvalidering (skärpt) | Min 6 → min 8 tecken; max 128 tecken; email valideras med regex `^[^@\s]+@[^@\s]+\.[^@\s]+$` istf bara `contains("@")` |
| Groq 429-fallback | `getRecommendation()` retryar automatiskt med `llama-3.1-8b-instant` om 70b svarar 429 — kastar bara fel om båda modellerna nekar |
| TCO-stapeldiagram | `caTcoBarChart()` ritar horisontella staplad-bar-chart under jämförelsetabellen med fem färgkodade segment per bil |
| Bilbilder på korten | Wikipedia REST API (CORS-öppen) lazy-loadar thumbnail per bilkort efter render; försöker engelska Wikipedia → svenska Wikipedia; döljs tyst om ingen bild hittas |
| Sparade sökningar | Inloggade användare kan spara sökningar till DB via "Spara sökning"-knapp; hämtas från server vid inloggning och visas som chips ovanför historiken; DELETE tar bort enskild post |
| Rate limit-persistens | In-memory rate limit-karta seedas från DB vid uppstart (`@PostConstruct`) — sökkvoter nollställs inte längre vid deploy eller cold start; async DB-skrivning per tillåten sökning; `@Scheduled` cleanup varje timme |
| "Erik Naessén" i JS | Hårdkodat namn i expertopinions-div (rad 395 i original) — ändrat till "Bilexpert" för att matcha backend-attributionen |
| Expertrecension i jämförelsetabell | `expertOpinion` visades bara på enskilda kort, ej i compare-tabellen — ny 🎯 Expertrecension-rad tillagd |
| Euro NCAP i jämförelsetabell | Säkerhetsbetyg saknades i compare-vyn — ny 🛡️ Euro NCAP-rad med stjärnor + procentdetaljer |
| Motoralternativ i jämförelsetabell | Nytt `engineOptions`-fält på `CarRecommendation`; AI genererar kommaseparerade motorvarianter per bil i compare-prompten; visas som pill-chips i ny 🔧-rad |
| Kontextuell expertrecension vid storleksskillnad | AI instrueras att nämna storleks-/utrymmeskillnad i `expertOpinion` när jämförda bilar är i olika klasser (t.ex. Kamiq vs Karoq) |
| Off-topic expertinsikter i chatt | `buildChatExpertContext` fyllde upp med allmänna insikter (`carMake=null`) oavsett ämne — MG4-insikt dök upp vid T-Roc vs Kamiq-fråga. Löst: allmänna insikter borttagna; max 3 bilspecifika; AI-instruktion skärpt till "bara om insikten gäller exakt denna bil" |
| BYD Seal borttagen från autocomplete | BYD Seal togs bort ur DB men låg kvar i den hårdkodade CA_FC_CARS-arrayen — nu borttagen |
| Autocomplete utökat (17 bilar) | Lade till: Audi Q8 e-tron, BMW iX/i5, Fiat 500/500e, Hyundai Kona/PHEV/Electric, Kia EV9, Mercedes EQC/EQE/EQS, MG5, Renault Zoe, Cupra Born/Formentor, Škoda Elroq, VW ID.5/ID.Buzz, Volvo C40 |
| Dynamisk autocomplete från `/api/cars` | Hårdkodad CA_FC_CARS-array (~80 rader) ersatt med live-fetch från `GET /api/cars` vid sidladdning — autocomplete hålls automatiskt synkad med databasen |
| BYD Dolphin borttagen ur AI-förslag | Dolphin säljs inte på svenska marknaden — explicit regel tillagd i alla tre systempromptarna (rekommendation, chat, jämförelse) |
| Kamiq ej elbil | AI föreslog Kamiq (bensinbil) som elbil — systempromptarna korrigerade: "Kamiq är en bensinbil, rekommendera den aldrig som elbil" |
| Lazy-load autocomplete | `/api/cars` hämtades vid varje sidladdning för alla besökare — nu hämtas listan enbart när användaren klickar i ett jämförelsefält |
| Gzip-komprimering aktiverad | `server.compression.enabled=true` i `application.properties` — JS/JSON komprimeras med ~70% (135 KB → ~35 KB); 1 dags browser-cache för statiska filer |
