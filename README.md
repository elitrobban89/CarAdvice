# CarAdvice – AI Bilrådgivning

En AI-driven bilrådgivare byggd med Java Spring Boot och Groq AI. Användaren fyller i sina preferenser i ett WordPress-formulär och får tre skräddarsydda bilrekommendationer på sekunder.

**Live:** [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)

---

## Funktioner

### Sök & rekommendationer
- Rekommenderar välrecenserade bilar baserat på kategori, budget och körbehov
- Stöd för ekonomibil, familjebil, SUV, elbil, laddhybrid och småbil
- Drivmedelsfilter: bensin, diesel, hybrid — döljs automatiskt för elbil/laddhybrid
- Budget-slider (50 000–1 000 000 kr) med tickmärken (50k · 200k · 400k · 700k · 1M) och live-uppdaterat värde
- Varnar vid orimliga kombinationer (t.ex. ekonomibil + lyxbudget)
- Anpassar råd efter körsträcka, laddmöjlighet och ny/begagnad
- Skeleton-loading: tre kortskelelett med shimmer-animation visas direkt när sökningen startar
- Roterande laddmeddelanden med tips under skeleton-laddningen
- Delade länkar auto-söker direkt när sidan öppnas (URL-parametrar triggar sökning automatiskt)
- Sökhistorik: senaste 5 sökningar sparas med resultat — ett klick visar sparade rekommendationer direkt utan API-anrop
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
- Sektionsrubriker (Fördelar / Nackdel / Passar dig) med dividers för tydlig läsbarhet
- **"Fråga om denna bil"-knapp** på varje kort — markerar kortet med glödande ram och öppnar chatboten fokuserad på just den bilen

### Bränslespecifikationer (bensin/diesel)
- **⛽ Bensin/Diesel**-sektion per bilkort för fossildrivna bilar
- Visar förbrukning (l/mil), växellåda (t.ex. Automat 7-växlad), hästkrafter och motorvolym
- AI-genererade värden direkt i rekommendationen — `fuelSpec: null` för elbil/laddhybrid
- Visas även som egna rader i jämförelsetabellen

### Elbils- och laddhybriddata (EV-chip)
- **⚡ Elbil**-badge eller **🔌 Laddhybrid**-badge per bil
- WLTP-räckvidd, uppskattad sommar- och vinterräckvidd
- Laddfrekvens baserat på körsträcka ("ladda var 4:e dag")
- Max DC-laddning (kW) och AC-laddning (kW)
- Batteristorlek (kWh) och startpris
- Prisvärdhetsbedömning (Utmärkt / Bra / Ok prisvärdhet)
- Tvåstegs fuzzy-matchning: titel-ord mot DB-namn och DB-namn som exakta ord i titel (förhindrar att "Kia Niro PHEV" matchar "Kia Niro EV")

### Bagageutrymme
- **🧳 Bagageutrymme-chip** visas på alla bilkort — oavsett drivmedel
- Visar standardvolym (L) och maxvolym med nedfällt baksäte
- 110+ bilar seedade: elbilar, laddhybrider, bensin, diesel och mildhybrider
- Syns även i jämförelsetabellen som egen rad

### Jämförelsetabell
- Scrollbar tabell under de tre bilkorten
- Rader: Pris · Fördelar · Nackdel · 🧳 Bagageutrymme
- Vid EV/PHEV: WLTP · Sommar · Vinter · Laddning · DC max · AC max · Batteri · Prisvärdhet
- Färgkodade kolumnrubriker matchar kortens accentfärger

### Chatbot
- Flytande knapp nere till höger med bil-ikon i glassmorphism-design; lila/indigo-tema
- Svarar på köpråd för alla drivmedel (bensin, diesel, hybrid, elbil)
- Streaming-svar — token för token via SSE; automatisk fallback till JSON om ReadableStream saknas
- **Kontextuell efter sökning** — FAB-etiketten och snabbknappar uppdateras med de rekommenderade bilarna
- **Per-bil-fokus** — klickar man "Fråga om denna bil" ändras chatboten till att fokusera på just den bilen med specifika chips: Berätta om, Driftkostnad & skatt, Tillförlitlighet & problem, Jämför med
- Dynamiska follow-up chips baserade på svarsinnehållet
- Rensa-knapp; max 10 frågor/minut per IP; sparar historik i localStorage

### Bilexpert-samarbete (RAG)
- PostgreSQL-tabell `expert_insight` lagrar **Erik Naesséns** bilexpertis
- Relevanta insikter injiceras automatiskt i AI-prompten baserat på sökt kategori och drivmedel
- Chatboten matchar insikter mot bilmärken som nämns i konversationen och avslutar svaret med `**Erik Naessén:** …`
- 13 startinsikter laddas vid första start; fler kan läggas till via psql eller admin-endpoint

### EV-spec-skrapare (ev-database.org)
- Daglig schemalagd sync kl 03:00 UTC — hämtar WLTP-räckvidd, batteristorlek, DC/AC-laddning och EUR-pris per bil
- **Auto-skapar nya poster** — bilar som finns på ev-database.org men saknas i DB läggs till automatiskt med all tillgänglig data; EUR-pris konverteras till SEK (~11.5×)
- Fuzzy-matchning i två steg mot befintliga DB-poster — förhindrar dubbletter
- Priser uppdateras på befintliga poster där `priceKr=0`
- Synken håller ingen DB-koppling öppen — varje sparande är en egen kort transaktion (förhindrar connection pool-uttömning)
- Manuell trigger via admin-endpoint:
  ```bash
  curl -X POST https://caradvice.onrender.com/api/admin/sync-ev-specs \
    -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
  ```
- Returnerar `202 Accepted` direkt; synken körs i bakgrunden (virtual thread); resultat i serverloggar

### Prenumeration & betalning (Stripe — testläge)

> **Stripe körs för närvarande i testläge.** Inga riktiga betalningar genomförs. Testkort: `4242 4242 4242 4242`, valfritt datum och CVC.

- Ej inloggad: **max 10 sökningar/timme** och **10 chattmeddelanden/minut** (IP-baserat)
- Inloggad (gratis konto): **30 sökningar/timme** och **30 chattmeddelanden/minut**
- Aktiv prenumerant (99 kr/mån): **obegränsade sökningar och chatt**
- Konto skapas på `/subscribe.html` — öppnas i nytt fönster
- Betalning via Stripe Checkout (hosted betalningssida)
- Prenumerationsstatusen sparas i `ca_user`-tabellen och verifieras via sessionstoken (Bearer-header)
- Stripe webhook (raw JSON-parsning, versionsoberoende) uppdaterar status automatiskt vid betalning, förnyelse, avslut och paus
- Slutdatum för prenumerationen hämtas från Stripes `current_period_end` och visas på kontosidan
- Kontosidan (`/subscribe.html`) visar prenumerationsstatus, **startdatum** ("Startade: X") och **slutdatum** ("Förnyas: X")
- `subscription_started_at` sätts vid första aktivering (ej vid förnyelse); `/api/auth/me` returnerar formaterat datum + ISO-sträng för duration-beräkning i klienten
- WordPress-snippeten visar prenumerationsrad med kvarvarande sökningar och en sammanslagen **"Prenumerera / Logga in"**-knapp (Demo-läge) — öppnar kontosidan som popup med korrekt `window.opener`

### Övrigt
- 2-timmars svar-cache på backend — identiska sökningar kostar inga tokens
- Cache-ålder visas i resultatet: "⚡ Cachat svar (X min sedan)"
- IP-baserad rate limiting: 10/h (gäst) · 30/h (inloggad) · obegränsat (prenumerant)
- Vänliga svenska felmeddelanden med exakt återstartstid vid kvotgräns
- 35-sekunders timeout med cold start-hint
- **PWA-stöd** — `manifest.json` gör appen installerbar på Android/iOS
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
    │   ├── controller/
    │   │   ├── AuthController.java    ← /api/auth/register, login, logout, me
    │   │   ├── CarController.java     ← REST-endpoints + admin sync-trigger
    │   │   └── StripeController.java  ← /api/stripe/checkout, /api/stripe/webhook
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
    │   │   └── User.java               ← JPA-entity: användarkonto + prenumerationsstatus + slutdatum
    │   ├── repository/
    │   │   ├── CargoSpecRepository.java
    │   │   ├── EvSpecRepository.java
    │   │   ├── ExpertInsightRepository.java
    │   │   ├── SafetyRatingRepository.java
    │   │   └── UserRepository.java
    │   ├── scraper/
    │   │   ├── EvDatabaseScraperService.java  ← Jsoup-skrapare mot ev-database.org
    │   │   └── EvSpecSyncScheduler.java       ← @Scheduled cron 03:00 UTC
    │   └── service/
    │       ├── CargoSpecService.java   ← Fuzzy-matchning på bilnamn → bagagevolym
    │       ├── EvSpecService.java      ← Fuzzy-matchning + räckvidd/laddberäkning
    │       ├── ExpertInsightService.java
    │       ├── GroqService.java        ← Groq AI, cache, felhantering
    │       ├── SafetyRatingService.java
    │       ├── StripeService.java      ← Checkout-session, webhook-hantering
    │       └── UserService.java        ← Register/login (BCrypt), sessionstoken
    └── resources/
        ├── application.properties
        └── static/
            ├── car-advice-chat.js  ← Chattbot-UI (serveras av Render, laddas av WordPress)
            ├── cancel.html         ← Visas om Stripe-betalning avbryts
            ├── manifest.json       ← PWA-manifest
            ├── subscribe.html      ← Login/register + Stripe Checkout (öppnas i nytt fönster)
            ├── success.html        ← Visas efter lyckad Stripe-betalning
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
  "fuelType": "elbil"
}
```

| Fält | Typ | Värden |
|------|-----|--------|
| `budget` | int | Kronor |
| `carCategory` | string | `ekonomibil`, `familjebil`, `suv`, `elbil`, `laddhybrid`, `smaabil` |
| `hasCharger` | boolean | Laddbox hemma |
| `kmPerYear` | int | Kilometer per år |
| `usage` | string | `pendling`, `familj`, `landsväg`, `stad` |
| `passengers` | int | 1–9 |
| `newCar` | boolean | Ny eller begagnad |
| `fuelType` | string | `bensin`, `diesel`, `hybrid`, `spelar ingen roll` |

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
| `/api/stripe/webhook` | POST | Stripe webhook — uppdaterar prenumerationsstatus automatiskt |

---

## Databastabeller

| Tabell | Innehåll |
|--------|----------|
| `expert_insight` | Erik Naesséns bilexpertis (RAG-kontext) |
| `ev_spec` | WLTP-räckvidd, batteri, DC/AC-laddning, pris per EV/PHEV-modell — auto-utökas av daglig scraper |
| `cargo_spec` | Bagageutrymme (standard + max L) för 110+ bilmodeller |
| `safety_rating` | Euro NCAP-betyg per modell (45+ bilar) |
| `ca_user` | Användarkonton: email, BCrypt-lösenordshash, Stripe customer ID, prenumerationsstatus, startdatum, slutdatum, sessionstoken |

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

Groq free tier ger **100 000 tokens/dag** för `llama-3.3-70b-versatile`. Varje sökning använder upp till **1 500 output-tokens** (höjt från 1 024 för att ge marginal för fuelSpec + verbose AI-svar), plus ~600–800 input-tokens för system-prompt och användarprompt — totalt ~50–60 unika sökningar/dag utan cache. Identiska sökprofiler returneras från 2-timmars cache utan tokenkostnad.

---

## Senaste bugfixar

| Fix | Beskrivning |
|-----|-------------|
| Prenumerationslängd på kontosidan | Kontosidan visar nu "Prenumerant i: X månader/år" (beräknas live i webbläsaren via ISO-datum från `/api/auth/me`), "Startade: X" och "Förnyas: X" |
| Tidzon UTC→Stockholm | Render kör i UTC — datum formaterades i UTC vilket kunde ge fel dag. Nu konverteras alla prenumerationsdatum till `Europe/Stockholm` innan formatering; ISO-strängen får `Z`-suffix så att `new Date()` i webbläsaren räknar durationen korrekt |
| Sammanslagen "Prenumerera / Logga in"-knapp | Demo-läget visade två separata element ("Logga in"-länk + "Prenumerera"-knapp). Nu visas en enda knapp som öppnar kontosidan som popup |
| Logout-synk: "Konto" öppnas nu som popup | "Konto"-länken för inloggade prenumeranter följde `href` som vanlig länk — subscribe.html fick inget `window.opener` och CA_LOGOUT-meddelandet nådde aldrig WordPress-sidan vid utloggning därifrån. Löst: alla klick på `ca-login-link` (utom logout) öppnar nu subscribe.html via `caOpenSubscribe()` (popup med `window.opener`) |
| Stale token rensas vid sidladdning | `/api/auth/me` ignorerade 401-svar och lämnade `ca_token`/`ca_email`/`ca_status` i localStorage. WordPress-sidan visade då "✓ Prenumerant" även efter utloggning. Löst: vid non-OK svar rensas localStorage och baren återställs till Demo-läge |
| Utloggning visade fel text | `caUpdateSubBar()` anropades med 2 args vid logout — `remaining` blev `undefined` och visade `"undefined av 10 sökningar"` |
| Storage-event efter logout | `ca_status`-borttagning skickade `!isActive = true` som `isLoggedIn` → visade "Inloggad" efter utloggning; nu reset till gäst-vy |
| `FuelSpecDto` null-säkerhet | Primitiva `double`/`int` → boxade `Double`/`Integer` så att `null`-fält från AI inte kraschar deserialisering |
| `isRateLimited` map-lookup | `compute()` följt av extra `map.get()` — använder nu returvärdet från `compute()` direkt |
