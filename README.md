# CarAdvice – AI Bilrådgivning

En AI-driven bilrådgivare byggd med Java Spring Boot och Groq AI. Användaren fyller i sina preferenser i ett WordPress-formulär och får tre skräddarsydda bilrekommendationer på sekunder.

**Live:** [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)

---

## Funktioner

- Rekommenderar välrecenserade bilar baserat på kategori, budget och körbehov
- Stöd för ekonomibil, familjebil, SUV, elbil, laddhybrid och småbil
- Drivmedelsfilter: bensin, diesel, hybrid — döljs automatiskt för elbil/laddhybrid
- Budget-slider (50 000–1 000 000 kr) med tickmärken (50k · 200k · 400k · 700k · 1M) och live-uppdaterat värde
- Varnar vid orimliga kombinationer (t.ex. ekonomibil + lyxbudget)
- Anpassar råd efter körsträcka, laddmöjlighet och ny/begagnad
- Roterande laddmeddelanden med tips under AI-anropet
- Sökhistorik: senaste 5 sökningar sparas med resultat — ett klick visar sparade rekommendationer direkt utan API-anrop
- Historik-badge "📋 Sparad sökning (X min sedan)" visas när resultat kommer från historik
- "Sök igen →"-knapp tillgänglig för att hämta färska resultat efter historik-visning
- Blocket- och Bytbil-länk på varje bilkort — öppnar färdig sökning på märke och modell
- Nollställ-knapp som återställer formuläret till standardvärden
- Kopiera-knapp som kopierar alla rekommendationer till clipboard
- Dela-knapp som genererar en delbar länk med alla sökinställningar som URL-parametrar
- Formuläret sparas automatiskt i localStorage och återställs vid nästa besök
- URL-parametrar har alltid högre prioritet än localStorage (delad länk visas alltid korrekt)
- 2-timmars svar-cache på backend — identiska sökningar kostar inga tokens
- Cache-ålder visas i resultatet: "⚡ Cachat svar (X min sedan)"
- Cache-eviction: max 200 poster, äldsta halvan rensas vid överskridande
- IP-baserad rate limiting: max 10 förfrågningar per IP och timme
- Vänliga svenska felmeddelanden med exakt återstartstid vid kvotgräns
- 35-sekunders timeout med cold start-hint

---

## Teknikstack

| Del | Teknologi |
|-----|-----------|
| Backend | Java 21, Spring Boot 3.2 |
| AI | Groq API (`llama-3.3-70b-versatile`) |
| Frontend | HTML/CSS/JS (WordPress Anpassad HTML) |
| Deploy | Render.com (Docker) |
| Monitorering | UptimeRobot |

---

## Projektstruktur

```
CarAdvice/
├── Dockerfile                          ← Multi-stage Docker-build
├── pom.xml                             ← Maven-konfiguration
├── wordpress-snippet.html              ← Klistra in på WordPress-sidan
├── footer-projects-snippet.html        ← Projektkort till footern
├── project-links-snippet.html          ← Projektkort till bränslekostnadsidan
└── src/main/
    ├── java/com/caradvice/
    │   ├── CarAdviceApplication.java   ← Spring Boot startpunkt
    │   ├── controller/
    │   │   └── CarController.java      ← REST-endpoints + CORS
    │   ├── model/
    │   │   ├── CarPreferences.java     ← Input-record
    │   │   └── CarRecommendation.java  ← Output-record
    │   └── service/
    │       └── GroqService.java        ← Groq AI-integration, cache, felhantering
    └── resources/
        └── application.properties
```

---

## Köra lokalt

**1. Sätt API-nyckel** (från [console.groq.com](https://console.groq.com)):
```bash
export GROQ_API_KEY=din_nyckel
```

**2. Starta:**
```bash
mvn spring-boot:run
```

**3. Testa:**
```bash
curl -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{
    "budget": 150000,
    "carCategory": "ekonomibil",
    "hasCharger": false,
    "kmPerYear": 15000,
    "usage": "pendling",
    "passengers": 2,
    "newCar": false,
    "fuelType": "bensin"
  }'
```

---

## API

### `POST /api/recommend`

**Request:**
```json
{
  "budget": 150000,
  "carCategory": "ekonomibil",
  "hasCharger": false,
  "kmPerYear": 15000,
  "usage": "pendling",
  "passengers": 2,
  "newCar": false
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
| `fuelType` | string | `bensin`, `diesel`, `hybrid`, `spelar ingen roll` (default) |

**Response (lyckat, färskt svar):**
```json
{
  "success": true,
  "recommendations": [
    {
      "title": "Volkswagen Golf 1.0 TSI (2019)",
      "price": "130 000 – 160 000 kr",
      "whyRecommended": "Toppbetyg i Teknikens Värld för komfort och bränsleekonomi",
      "pros": ["5,5 l/100 km ger ~8 250 kr/år", "Låga underhållskostnader", "Brett servicenät"],
      "con": "Mindre kraftfull motor kan kännas trög i kuperad terräng",
      "fitSummary": "Passar en lågmilare som pendlar i stad med begränsad budget."
    }
  ]
}
```

**Response (cache-träff):** Samma struktur plus `"cached": true, "cachedAgeMinutes": 14`.

**Response (fel):**
```json
{
  "success": false,
  "error": "Dagsgränsen för AI-anrop är nådd. Försök igen om 8 minuter."
}
```

**Rate limiting:** Max 10 anrop per IP och timme. Vid överskridande: HTTP 429 med `"error": "För många förfrågningar från din IP. Försök igen om en stund."`

### `GET /api/health`
```json
{ "status": "OK" }
```

### `GET /api/recommend/test`

Kontrollerar att Groq API-nyckeln är konfigurerad. Används av UptimeRobot — gör **inga** Groq-anrop.

```json
{ "status": "OK", "groq": "OK", "rekommendation": true }
```

---

## Deploya på Render.com

1. Pusha till GitHub
2. Skapa **Web Service** på [render.com](https://render.com) → koppla repot
3. Välj **Docker** som runtime, branch `master`
4. Lägg till miljövariabel: `GROQ_API_KEY`
5. Deploy startar automatiskt vid varje push

**OBS:** Render free tier spinnar ned tjänsten efter 15 min inaktivitet (cold start ~30–60 sek). Löses med UptimeRobot-monitor på 5 min intervall.

---

## Monitorering

Tjänsten övervakas med [UptimeRobot](https://uptimerobot.com) via två monitorer:

| Monitor | URL | Intervall |
|---|---|---|
| WordPress-sida | `https://elitrobban.se/bilradgivning/` | 5 min |
| Backend | `https://caradvice.onrender.com/api/recommend/test` | 5 min |

Backend-monitorn håller även Render-instansen varm och eliminerar cold starts.

---

## WordPress-integration

Klistra in `wordpress-snippet.html` i ett **Anpassad HTML**-block på valfri WordPress-sida. Formuläret anropar Render-URL:en direkt från webbläsaren — ingen server-side WordPress-kod krävs.

> **OBS:** WordPress synkas inte automatiskt från GitHub. Vid uppdatering av `wordpress-snippet.html` måste koden klistras in manuellt i WordPress-blocket.

**Relaterade snippets:**
- `footer-projects-snippet.html` — projektkort till footern
- `project-links-snippet.html` — projektkort till bränslekostnadsidan

---

## Token-budget (Groq gratisplan)

Groq free tier ger **100 000 tokens/dag** för `llama-3.3-70b-versatile`.

### Förbrukning per anrop

| Del | Tokens |
|-----|--------|
| System-prompt (input) | ~60 |
| User-prompt (input) | ~30 |
| Svar från AI (output, max) | 1 024 |
| **Totalt per unikt anrop** | **~1 100–1 150** |

Det ger ungefär **85–90 unika sökningar per dag** innan kvoten nås.

### Cache (2 timmar)

Identiska sökprofiler (samma budget, kategori, körsträcka etc.) returneras direkt från minnet utan att använda tokens. En populär kombination som söks 10 gånger under två timmar kostar alltså bara ~1 150 tokens istället för ~11 500.

Cache nollställs vid Render-omstart (deploy eller nedstängning).

### Hur kvoten förlängs

| Åtgärd | Effekt |
|--------|--------|
| Cache-träff | 0 tokens (100% besparing) |
| `/api/recommend/test` (UptimeRobot) | 0 tokens |
| Promptarna är avsiktligt korta | Ändra inte utan att räkna tokens |

### Vid 429-fel

Användaren ser: *"Dagsgränsen för AI-anrop är nådd. Försök igen om X minuter."*
Kvoten återställs dagligen (~midnatt UTC). Uppgradering till Groq Dev Tier ger 500 000 tokens/dag.
