# 🚗 CarAdvice – AI Bilrådgivning

En AI-driven bilrådgivare byggd med Java Spring Boot och Groq AI. Användaren fyller i sina preferenser i ett WordPress-formulär och får tre skräddarsydda bilrekommendationer på sekunder.

**Live:** [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)

---

## Funktioner

- Rekommenderar välrecenserade bilar baserat på kategori, budget och körbehov
- Stöd för ekonomibil, familjebil, elbil och småbil
- Varnar vid orimliga kombinationer (t.ex. ekonomibil + lyxbudget)
- Anpassar råd efter mil per år, laddmöjlighet och ny/begagnad
- Modernt mörkt formulär med live-ändringsindikation och animerade resultat
- Strukturerade bilkort med pris, källhänvisning, fördelar, nackdel och personlig sammanfattning

---

## Teknikstack

| Del | Teknologi |
|-----|-----------|
| Backend | Java 21, Spring Boot 3.2 |
| AI | Groq API (`llama-3.3-70b-versatile`) |
| Frontend | HTML/CSS/JS (WordPress Anpassad HTML) |
| Deploy | Render.com (Docker) |

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
    │   │   └── CarController.java      ← REST: POST /api/recommend, GET /api/health
    │   ├── model/
    │   │   ├── CarPreferences.java     ← Input-record
│   │   └── CarRecommendation.java  ← Output-record (title, price, pros, con, fitSummary)
    │   └── service/
    │       └── GroqService.java        ← Groq AI-integration
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
    "budget": 300000,
    "carCategory": "familjebil",
    "hasCharger": true,
    "kmPerYear": 15000,
    "usage": "familj",
    "passengers": 4,
    "newCar": false
  }'
```

---

## API

### `POST /api/recommend`

**Request:**
```json
{
  "budget": 300000,
  "carCategory": "familjebil",
  "hasCharger": true,
  "kmPerYear": 15000,
  "usage": "familj",
  "passengers": 4,
  "newCar": false
}
```

| Fält | Typ | Värden |
|------|-----|--------|
| `budget` | int | Kronor |
| `carCategory` | string | `ekonomibil`, `familjebil`, `elbil`, `smaabil` |
| `hasCharger` | boolean | Laddare hemma |
| `kmPerYear` | int | Kilometer per år |
| `usage` | string | `pendling`, `familj`, `landsväg`, `stad` |
| `passengers` | int | 1–9 |
| `newCar` | boolean | Ny eller begagnad |

**Response:**
```json
{
  "success": true,
  "recommendations": [
    {
      "title": "Volvo V60 T6 Recharge (2021)",
      "price": "280 000 – 340 000 kr",
      "whyRecommended": "Toppbetyg av Teknikens Värld för komfort och säkerhet...",
      "pros": ["Låg driftkostnad som laddhybrid", "Stort bagageutrymme", "Räcker för daglig pendling på el"],
      "con": "Begränsat begagnat utbud i denna årsmodell",
      "fitSummary": "Passar perfekt för familjekörning med laddbox hemma."
    }
  ]
}
```

### `GET /api/health`
```json
{ "status": "OK" }
```

### `GET /api/recommend/test`

Kontrollerar att Groq API-nyckeln är konfigurerad. Används av UptimeRobot för att hålla Render-instansen varm — gör **inga** Groq-anrop för att spara token-budget.

```json
{ "status": "OK", "groq": "OK", "rekommendation": true }
```

---

## Deploya på Render.com

1. Pusha till GitHub
2. Skapa **Web Service** på [render.com](https://render.com) → koppla repot
3. Välj **Docker** som runtime, branch `master`
4. Lägg till miljövariabel: `GROQ_API_KEY`
5. Deploy — tjänsten startar automatiskt

**OBS:** Render free tier spinner ned tjänsten efter 15 min inaktivitet (cold start ~30–60 sek). Löses med UptimeRobot-monitor på 5 min intervall (se nedan).

---

## Monitorering

Tjänsten övervakas med [UptimeRobot](https://uptimerobot.com) via två monitorer:

| Monitor | URL | Typ |
|---|---|---|
| WordPress-sida | `https://elitrobban.se/bilradgivning/` | HTTP(s) |
| Backend + Groq AI | `https://caradvice.onrender.com/api/recommend/test` | HTTP(s) – Keyword: `rekommendation` |

Backend-monitorn körs var 5:e minut vilket även håller Render-instansen varm och eliminerar cold starts för användarna.

---

## WordPress-integration

Klistra in `wordpress-snippet.html` i ett **Anpassad HTML**-block på valfri WordPress-sida. Formuläret anropar Render-URL:en direkt från webbläsaren.

**Relaterade snippets:**
- `footer-projects-snippet.html` — tre projektkort till footern (Bränslekostnadsberäkning, AI Bilrådgivning, Väder&Kläder)
- `project-links-snippet.html` — två projektkort för bränslekostnadsidan
