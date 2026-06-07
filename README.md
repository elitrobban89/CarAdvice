# CarAdvice – AI Bilrådgivning

En AI-driven bilrådgivare byggd med Java Spring Boot och Groq AI. Användaren fyller i sina preferenser i ett WordPress-formulär och får tre skräddarsydda bilrekommendationer på sekunder.

**Live:** [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)

---

## Funktioner

- Rekommenderar välrecenserade bilar baserat på kategori, budget och körbehov
- Stöd för ekonomibil, familjebil, SUV, elbil, laddhybrid och småbil
- Budget-slider (50 000–1 000 000 kr) med live-uppdaterat värde
- Varnar vid orimliga kombinationer (t.ex. ekonomibil + lyxbudget)
- Anpassar råd efter körsträcka, laddmöjlighet och ny/begagnad
- Roterande laddmeddelanden under AI-anropet
- Kopiera-knapp som kopierar alla rekommendationer till clipboard
- 2-timmars svar-cache på backend — identiska sökningar kostar inga tokens
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
    "newCar": false
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

**Response (lyckat):**
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

**Response (fel):**
```json
{
  "success": false,
  "error": "Dagsgränsen för AI-anrop är nådd. Försök igen om 8 minuter."
}
```

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

Groq free tier ger **100 000 tokens/dag** för `llama-3.3-70b-versatile`. Varje anrop kostar ~600–700 tokens, vilket ger ungefär **130–150 unika sökningar per dag**.

- Identiska sökningar serveras från 2-timmars cache utan att använda tokens
- Promptarna är medvetet korta — ändra dem inte utan att räkna tokens
- `/api/recommend/test` gör **inga** Groq-anrop
- Vid 429 visas: *"Dagsgränsen för AI-anrop är nådd. Försök igen om X minuter."*
- Kvoten återställs dagligen (~midnatt UTC)
