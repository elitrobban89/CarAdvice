// ── Bränslekostnadsberäkningen — frontend för WordPress-sidan
//    https://elitrobban.se/branslekostnad-berakning/
//
//    ⚠️ DEN HÄR FILEN FINNS I TVÅ REPON OCH MÅSTE VARA IDENTISK I BÅDA.
//      · CarAdvice  src/main/resources/static/bensinkostnad.js  ← SERVERAS härifrån
//      · Bilresa    src/bensinkostnad-wpcode.js                 ← TESTAS där (35 tester)
//
//    Uppdelningen är inte snygg, men var och en av halvorna har ett skäl:
//
//    SERVERAS av CarAdvice därför att Bilresa ligger på Renders gratisnivå och somnar efter
//    ~15 min. Mätt 2026-08-20: 13,3 s för uppvakningen mot 0,09 s varm. Eftersom sidans KOD
//    hämtades därifrån blockerades hela gränssnittet av kallstarten — inte bara datan — och
//    användaren fick gateway timeout. CarAdvice ligger på betald plan och somnar aldrig.
//
//    TESTAS i Bilresa därför att testerna redan bor där: frontend.test.js kör filen i en
//    vm-kontext med DOM-stubb. CarAdvice är ett Maven-projekt utan JS-runner, och att införa
//    en valdes bort 2026-08-20.
//
//    Ändrar du något: ändra i BÅDA. Bilresas server.test.js har ett drift-test som jämför
//    sin kopia mot den som CarAdvice faktiskt serverar och gör därför skillnaden hörbar.
//
//    Filen var ursprungligen ett WPCode-snippet. Den är en extern fil därför att en git push
//    ska räcka för utrullning OCH därför att inline-skript blockeras av sidans CSP — båda
//    skälen gäller fortfarande; det enda som ändrats är vilken tjänst som är värd.

// ── Global state ─────────────────────────────────────
var bcStartLat = null, bcStartLon = null, bcMap = null, bcRouteLayer = null;
var bcIsElectric = false, bcIsDiesel = false;
// Senast beräknade ruttens ändpunkter — laddstoppen behöver båda koordinatparen
// (bcStartLat är null när starten geokodats från text i stället för GPS)
var bcRouteStartLat = null, bcRouteStartLon = null, bcRouteEndLat = null, bcRouteEndLon = null;
var bcStopMarkers = [];

// ── Bildata: l/10km för bensin/diesel, kWh/mil för elbilar (el) ──
// Förbrukning kompletteras automatiskt från CarAdvice vid laddning:
// EV från /api/ev-consumption, bensin/diesel/hybrid från /api/ice-consumption (24h cache).
// Den statiska databasen nedan är fallback när API:et inte svarar.
var BC_CAR_DB = {
  "Abarth": {
    "500 1.4 T-Jet 135 hk":0.72, "595 1.4 T-Jet 160 hk":0.75, "595 Competizione 1.4 T-Jet 180 hk":0.85, "695 1.4 T-Jet 180 hk":0.88, "695 Biposto 1.4 T-Jet 190 hk":0.95
  },
  "Alfa Romeo": {
    "Giulia 2.0 T 200 hk":0.75, "Giulia 2.0 T 280 hk":0.82, "Giulia Quadrifoglio 2.9 V6 510 hk":1.15,
    "Giulia 2.2 Diesel 160 hk (diesel)":0.56, "Giulia 2.2 Diesel 210 hk (diesel)":0.60,
    "Stelvio 2.0 T 200 hk":0.88, "Stelvio 2.0 T 280 hk":0.95, "Stelvio Quadrifoglio 2.9 V6 510 hk":1.25,
    "Stelvio 2.2 Diesel 160 hk (diesel)":0.62, "Stelvio 2.2 Diesel 210 hk (diesel)":0.68,
    "Tonale 1.5 MHEV 160 hk":0.78, "Tonale Plug-in Hybrid 280 hk":0.45,
    "147 1.6":0.72, "147 2.0":0.82, "147 GTA 3.2":1.05,
    "156 1.8":0.78, "156 2.5 V6":1.00, "159 1.9 TDI (diesel)":0.60
  },
  "Alpine": {
    "A110 1.8 T 252 hk":0.82, "A110 S 1.8 T 292 hk":0.90, "A110 GT 1.8 T 300 hk":0.90, "A110 R 300 hk":0.95,
    "A290 (el)":1.60
  },
  "Audi": {
    "A1 25 TFSI 95 hk":0.58, "A1 30 TFSI 116 hk":0.62, "A1 35 TFSI 150 hk":0.68, "A1 40 TFSI 200 hk":0.78,
    "A3 30 TFSI 110 hk":0.62, "A3 35 TFSI 150 hk":0.68, "A3 40 TFSI 204 hk":0.75,
    "A3 35 TDI 150 hk (diesel)":0.52, "A3 40 TDI 200 hk (diesel)":0.56,
    "A3 45 TFSI e PHEV 245 hk":0.48, "S3 2.0 TFSI 310 hk":0.90, "RS3 2.5 TFSI 400 hk":1.10,
    "A4 35 TFSI 150 hk":0.72, "A4 40 TFSI 204 hk":0.78, "A4 45 TFSI 265 hk":0.88,
    "A4 35 TDI 163 hk (diesel)":0.55, "A4 40 TDI 204 hk (diesel)":0.58, "A4 50 TDI 286 hk (diesel)":0.62,
    "A4 55 TFSI e PHEV 265 hk":0.52, "S4 3.0 TFSI 341 hk":1.00, "RS4 2.9 TFSI 450 hk":1.18,
    "A5 35 TFSI 150 hk":0.74, "A5 40 TFSI 204 hk":0.80, "A5 45 TFSI 265 hk":0.90,
    "A5 35 TDI 163 hk (diesel)":0.56, "A5 40 TDI 204 hk (diesel)":0.60,
    "S5 3.0 TFSI 341 hk":1.02, "RS5 2.9 TFSI 450 hk":1.20,
    "A6 40 TFSI 204 hk":0.82, "A6 45 TFSI 265 hk":0.90, "A6 55 TFSI 340 hk":1.00,
    "A6 35 TDI 163 hk (diesel)":0.58, "A6 40 TDI 204 hk (diesel)":0.62, "A6 50 TDI 286 hk (diesel)":0.68,
    "A6 55 TFSI e PHEV 367 hk":0.55, "S6 2.9 TFSI 450 hk":1.08, "RS6 4.0 TFSI 630 hk":1.30,
    "A7 45 TFSI 265 hk":0.88, "A7 55 TFSI 340 hk":0.98,
    "A7 40 TDI 204 hk (diesel)":0.64, "A7 50 TDI 286 hk (diesel)":0.70,
    "S7 2.9 TFSI 450 hk":1.10, "RS7 4.0 TFSI 630 hk":1.32,
    "A8 55 TFSI 340 hk":1.08, "A8 60 TFSI e PHEV 449 hk":0.70,
    "A8 50 TDI 286 hk (diesel)":0.75, "S8 4.0 TFSI 571 hk":1.30,
    "Q2 35 TFSI 150 hk":0.68, "Q2 35 TDI 150 hk (diesel)":0.54,
    "Q3 35 TFSI 150 hk":0.72, "Q3 45 TFSI 230 hk":0.82,
    "Q3 35 TDI 150 hk (diesel)":0.56, "Q3 40 TDI 200 hk (diesel)":0.60, "RS Q3 2.5 TFSI 400 hk":1.02,
    "Q5 40 TFSI 204 hk":0.88, "Q5 45 TFSI 265 hk":0.95,
    "Q5 40 TDI 204 hk (diesel)":0.65, "Q5 50 TDI 286 hk (diesel)":0.70,
    "Q5 55 TFSI e PHEV 367 hk":0.58, "SQ5 3.0 TFSI 341 hk":1.05,
    "Q7 45 TFSI 340 hk":1.08, "Q7 45 TDI 231 hk (diesel)":0.78, "Q7 50 TDI 286 hk (diesel)":0.82,
    "Q7 60 TFSI e PHEV 462 hk":0.68, "SQ7 4.0 TFSI 507 hk":1.28,
    "Q8 55 TFSI 340 hk":1.15, "Q8 50 TDI 286 hk (diesel)":0.80, "SQ8 4.0 TFSI 507 hk":1.30, "RS Q8 4.0 TFSI 600 hk":1.42,
    "TT 45 TFSI 245 hk":0.80, "TTS 2.0 TFSI 320 hk":0.90, "TT RS 2.5 TFSI 400 hk":1.05,
    "e-tron 50 quattro (el)":2.10, "e-tron 55 quattro (el)":2.20, "e-tron GT (el)":1.95, "RS e-tron GT (el)":2.10,
    "Q4 e-tron 35 (el)":1.65, "Q4 e-tron 40 (el)":1.75, "Q4 e-tron 50 quattro (el)":1.85,
    "Q8 e-tron 50 (el)":2.00, "Q8 e-tron 55 (el)":2.10, "Q8 e-tron S (el)":2.30
  },
  "BMW": {
    "116i 1.5 T 109 hk":0.58, "118i 1.5 T 136 hk":0.62, "120i 2.0 T 178 hk":0.68, "128ti 2.0 T 265 hk":0.80, "M135i xDrive 306 hk":0.88,
    "116d 2.0 D 116 hk (diesel)":0.48, "118d 2.0 D 150 hk (diesel)":0.52, "120d 2.0 D 190 hk (diesel)":0.55,
    "218i Active Tourer 136 hk":0.68, "220i 2.0 T 170 hk":0.72, "230i 2.0 T 245 hk":0.80, "M240i xDrive 374 hk":0.98,
    "218d Active Tourer 150 hk (diesel)":0.52, "220d Active Tourer 190 hk (diesel)":0.56, "225e Active Tourer PHEV":0.45,
    "318i 2.0 T 156 hk":0.62, "320i 2.0 T 184 hk":0.68, "330i 2.0 T 258 hk":0.78, "340i xDrive 3.0 T 374 hk":0.95,
    "316d 2.0 D 116 hk (diesel)":0.48, "318d 2.0 D 150 hk (diesel)":0.52, "320d 2.0 D 190 hk (diesel)":0.55, "330d 3.0 D 286 hk (diesel)":0.62,
    "330e PHEV 292 hk":0.45, "M3 3.0 T 480 hk":1.08, "M3 Competition 510 hk":1.15,
    "420i 2.0 T 184 hk":0.72, "430i 2.0 T 245 hk":0.82, "M440i xDrive 374 hk":0.98,
    "420d 2.0 D 190 hk (diesel)":0.58, "430d 3.0 D 286 hk (diesel)":0.65,
    "430e PHEV 292 hk":0.48, "M4 3.0 T 480 hk":1.10, "M4 Competition 510 hk":1.18,
    "520i 2.0 T 184 hk":0.75, "530i 2.0 T 252 hk":0.85, "540i 3.0 T 340 hk":0.98,
    "518d 2.0 D 150 hk (diesel)":0.55, "520d 2.0 D 190 hk (diesel)":0.60, "530d 3.0 D 286 hk (diesel)":0.65, "540d 3.0 D 340 hk (diesel)":0.72,
    "530e PHEV 292 hk":0.50, "545e xDrive PHEV 394 hk":0.58, "M5 4.4 V8 600 hk":1.28, "M5 Competition 625 hk":1.35,
    "730i 2.0 T 265 hk":0.92, "740i 3.0 T 340 hk":1.05, "750i xDrive 4.4 V8 530 hk":1.25,
    "730d 3.0 D 286 hk (diesel)":0.72, "740d 3.0 D 340 hk (diesel)":0.80,
    "745e PHEV 394 hk":0.68, "M760i xDrive 6.6 V12 585 hk":1.45,
    "X1 sDrive18i 140 hk":0.70, "X1 xDrive20i 192 hk":0.78, "X1 xDrive25i 231 hk":0.85,
    "X1 sDrive18d 150 hk (diesel)":0.55, "X1 xDrive18d 150 hk (diesel)":0.58, "X1 xDrive25d 231 hk (diesel)":0.64, "X1 xDrive25e PHEV":0.50,
    "X1 xDrive30 (el)":1.75,
    "X2 sDrive18i 140 hk":0.72, "X2 xDrive25i 192 hk":0.82, "X2 M35i xDrive 306 hk":0.95,
    "X2 sDrive18d 150 hk (diesel)":0.55, "X2 xDrive18d 150 hk (diesel)":0.58,
    "X3 xDrive20i 184 hk":0.82, "X3 xDrive30i 252 hk":0.90, "X3 M40i 360 hk":1.05,
    "X3 xDrive20d 190 hk (diesel)":0.64, "X3 xDrive30d 286 hk (diesel)":0.70, "X3 M40d 326 hk (diesel)":0.78,
    "X3 xDrive30e PHEV":0.55, "X3 M 3.0 T 510 hk":1.30,
    "X4 xDrive20i 184 hk":0.85, "X4 xDrive30i 252 hk":0.92, "X4 M40i 360 hk":1.08,
    "X4 xDrive20d 190 hk (diesel)":0.66, "X4 xDrive30d 286 hk (diesel)":0.72, "X4 M 3.0 T 510 hk":1.32,
    "X5 xDrive40i 340 hk":1.02, "X5 xDrive50i 4.4 V8 462 hk":1.20,
    "X5 xDrive30d 286 hk (diesel)":0.76, "X5 xDrive40d 340 hk (diesel)":0.82, "X5 M50d 400 hk (diesel)":0.92,
    "X5 xDrive45e PHEV 394 hk":0.65, "X5 M 4.4 V8 600 hk":1.40, "X5 M Competition 625 hk":1.48,
    "X6 xDrive40i 340 hk":1.08, "X6 xDrive30d 286 hk (diesel)":0.80, "X6 xDrive40d 340 hk (diesel)":0.88, "X6 M 4.4 V8 600 hk":1.45,
    "X7 xDrive40i 340 hk":1.12, "X7 xDrive30d 286 hk (diesel)":0.85, "X7 xDrive40d 340 hk (diesel)":0.92, "X7 M60i 4.4 V8 585 hk":1.42,
    "iX xDrive40 (el)":1.90, "iX xDrive50 (el)":2.00, "iX M60 (el)":2.20,
    "i4 eDrive35 (el)":1.55, "i4 eDrive40 (el)":1.65, "i4 xDrive40 (el)":1.75, "i4 M50 (el)":1.90,
    "i5 eDrive40 (el)":1.75, "i5 xDrive40 (el)":1.85, "i5 M60 (el)":2.00,
    "iX3 (el)":1.90, "i7 xDrive60 (el)":2.10, "i7 M70 (el)":2.30
  },
  "BYD": {
    "Dolphin Standard 45 kWh (el)":1.35, "Dolphin 60 kWh (el)":1.45,
    "Seal 82 kWh RWD (el)":1.60, "Seal 82 kWh AWD (el)":1.72,
    "Atto 3 Standard Range (el)":1.65, "Atto 3 Extended Range (el)":1.80,
    "Han 85 kWh RWD (el)":1.75, "Han AWD 100 kWh (el)":1.90,
    "Seal U (el)":1.85, "Tang (el)":2.05
  },
  "Cadillac": {
    "CT5 2.0 Turbo 237 hk":0.98, "CT5-V 3.0 TT 360 hk":1.12, "CT5-V Blackwing 6.2 V8 668 hk":1.55,
    "Escalade 6.2 V8 426 hk":1.55, "Lyriq (el)":2.00
  },
  "Citroën": {
    "C1 1.0 VTi 68 hk":0.48, "C1 1.2 VTi 82 hk":0.52,
    "C3 1.2 PureTech 83 hk":0.55, "C3 1.2 PureTech 110 hk":0.60, "C3 1.2 PureTech 110 hk EAT6":0.63,
    "C3 Aircross 1.2 PureTech 110 hk":0.68, "C3 Aircross 1.2 PureTech 130 hk":0.72,
    "C4 1.2 PureTech 130 hk":0.65, "C4 1.2 PureTech 130 hk EAT8":0.68, "C4 1.5 BlueHDi 130 hk (diesel)":0.52,
    "C5 Aircross 1.2 PureTech 130 hk":0.78, "C5 Aircross 1.2 PureTech 130 hk EAT8":0.81,
    "C5 Aircross 1.5 BlueHDi 130 hk (diesel)":0.60, "C5 Aircross 1.5 BlueHDi 130 hk EAT8 (diesel)":0.63, "C5 Aircross PHEV 225 hk":0.48,
    "Berlingo 1.2 PureTech 110 hk":0.72, "Berlingo 1.2 PureTech 110 hk EAT8":0.75, "Berlingo 1.5 BlueHDi 130 hk (diesel)":0.58,
    "ë-C4 (el)":1.60, "ë-C3 (el)":1.45
  },
  "Cupra": {
    "Born 58 kWh (el)":1.60, "Born 77 kWh (el)":1.65, "Born 82 kWh (el)":1.68,
    "Formentor 1.5 TSI 150 hk":0.78, "Formentor 2.0 TSI 190 hk":0.85, "Formentor 2.0 TSI 300 hk":0.95, "Formentor VZ 2.5 TFSI 390 hk":1.12,
    "Formentor 1.4 e-Hybrid 204 hk":0.48, "Formentor 1.4 e-Hybrid 245 hk":0.50,
    "Leon 1.5 TSI 150 hk":0.65, "Leon 2.0 TSI 190 hk":0.75, "Leon VZ 2.0 TSI 300 hk":0.92,
    "Ateca 1.5 TSI 150 hk":0.75, "Ateca 2.0 TSI 190 hk":0.85, "Ateca 2.0 TSI 300 hk":0.98,
    "Tavascan (el)":1.75
  },
  "Dacia": {
    "Sandero 1.0 SCe 65 hk":0.55, "Sandero 1.0 TCe 90 hk":0.58, "Sandero 1.0 TCe 90 hk CVT":0.61,
    "Sandero Stepway 1.0 TCe 90 hk":0.62, "Sandero Stepway 1.0 TCe 90 hk CVT":0.65,
    "Sandero Stepway 1.0 TCe 110 hk":0.65, "Sandero Stepway 1.0 TCe 110 hk CVT":0.68,
    "Duster 1.0 TCe 90 hk":0.72, "Duster 1.3 TCe 130 hk":0.78, "Duster 1.3 TCe 130 hk EDC":0.82,
    "Duster 1.3 TCe 150 hk 4x4 EDC":0.88, "Duster 1.5 dCi 115 hk (diesel)":0.58,
    "Logan 1.0 SCe 75 hk":0.60, "Jogger 1.0 TCe 110 hk":0.72, "Jogger 1.0 TCe 110 hk CVT":0.75,
    "Jogger 1.6 Hybrid 140 hk":0.68,
    "Spring 27 kWh (el)":1.30, "Spring 33 kWh (el)":1.35, "Bigster 1.2 TCe 140 hk":0.78, "Bigster 1.2 TCe 140 hk EDC":0.81
  },
  "DS": {
    "DS 3 1.2 PureTech 130 hk":0.68, "DS 3 1.5 BlueHDi 130 hk (diesel)":0.54, "DS 3 E-Tense (el)":1.65,
    "DS 4 1.2 PureTech 130 hk":0.72, "DS 4 1.5 BlueHDi 130 hk (diesel)":0.56, "DS 4 PHEV 225 hk":0.48, "DS 4 E-Tense (el)":1.70,
    "DS 7 1.6 PureTech 225 hk":0.82, "DS 7 BlueHDi 180 hk (diesel)":0.64, "DS 7 E-Tense 300 hk PHEV":0.50,
    "DS 9 1.6 PureTech 225 hk":0.88, "DS 9 E-Tense PHEV 360 hk":0.55
  },
  "Fiat": {
    "500 1.0 MHEV 70 hk":0.52, "500 1.4 100 hk":0.60, "500 Abarth 1.4 T-Jet 135 hk":0.72,
    "500e 42 kWh (el)":1.40, "500e 87 kWh (el)":1.48,
    "Punto 1.2 69 hk":0.58, "Tipo 1.4 95 hk":0.65, "Tipo 1.6 MultiJet 120 hk (diesel)":0.52,
    "500X 1.0 120 hk":0.70, "Panda 1.0 MHEV 70 hk":0.48, "600e (el)":1.55
  },
  "Ford": {
    "Fiesta 1.0 EcoBoost 95 hk":0.55, "Fiesta 1.0 EcoBoost 125 hk":0.58, "Fiesta 1.0 EcoBoost 125 hk Auto":0.61,
    "Fiesta ST 1.5 EcoBoost 200 hk":0.82,
    "Focus 1.0 EcoBoost 125 hk":0.62, "Focus 1.0 EcoBoost 125 hk Auto":0.65,
    "Focus 1.0 EcoBoost 155 hk":0.65, "Focus 1.5 EcoBoost 182 hk":0.70, "Focus ST 2.0 EcoBoost 280 hk":0.92,
    "Focus 1.5 EcoBlue 95 hk (diesel)":0.50, "Focus 1.5 EcoBlue 120 hk (diesel)":0.52, "Focus 1.5 EcoBlue 120 hk Auto (diesel)":0.55,
    "Mondeo 1.5 EcoBoost 165 hk":0.78, "Mondeo 2.0 EcoBoost 240 hk":0.88, "Mondeo Hybrid 187 hk":0.62,
    "Mondeo 2.0 EcoBlue 150 hk (diesel)":0.58, "Mondeo 2.0 EcoBlue 180 hk (diesel)":0.62,
    "Mustang 2.3 EcoBoost 290 hk":1.05, "Mustang 5.0 V8 449 hk":1.40,
    "Mustang Mach-E 75 kWh RWD (el)":1.80, "Mustang Mach-E 99 kWh AWD (el)":1.95, "Mustang Mach-E GT (el)":2.10,
    "Kuga 1.5 EcoBoost 150 hk":0.82, "Kuga 1.5 EcoBoost 150 hk Auto":0.85,
    "Kuga 2.5 FHEV 190 hk Hybrid":0.68, "Kuga PHEV 225 hk":0.52, "Kuga 2.0 EcoBlue 150 hk (diesel)":0.63,
    "Explorer 2.3 EcoBoost 249 hk":1.15, "Explorer PHEV 457 hk":0.75,
    "Puma 1.0 EcoBoost 125 hk":0.60, "Puma 1.0 EcoBoost 125 hk Auto":0.63,
    "Puma 1.0 EcoBoost 155 hk":0.62, "Puma ST 1.5 EcoBoost 200 hk":0.85,
    "EcoSport 1.0 EcoBoost 125 hk":0.68, "Capri (el)":1.80
  },
  "Genesis": {
    "G70 2.0 T 252 hk":0.80, "G70 3.3 Twin Turbo 370 hk":1.08, "G70 2.2 D 202 hk (diesel)":0.60,
    "G80 2.5 T 304 hk":0.88, "G80 3.5 T 380 hk":1.05, "G80 2.2 D 202 hk (diesel)":0.68, "G80 (el)":1.90,
    "GV70 2.5 T 304 hk":0.92, "GV70 3.5 T 380 hk":1.10, "GV70 2.2 D 202 hk (diesel)":0.72, "GV70 Electrified (el)":1.95,
    "GV80 2.5 T 300 hk":1.02, "GV80 3.5 T 380 hk":1.18, "GV80 2.2 D 202 hk (diesel)":0.80,
    "GV60 (el)":1.85, "GV60 Performance (el)":2.00
  },
  "Honda": {
    "Jazz 1.5 e:HEV Hybrid 109 hk":0.50, "Civic 1.0 VTEC Turbo 126 hk":0.62, "Civic 1.5 VTEC Turbo 182 hk":0.68,
    "Civic e:HEV 2.0 Hybrid 184 hk":0.52, "Civic Type R 2.0 VTEC Turbo 329 hk":1.02,
    "Accord 2.0 e:HEV Hybrid 215 hk":0.65,
    "CR-V 1.5 VTEC Turbo 193 hk":0.80, "CR-V 2.0 e:HEV Hybrid 184 hk":0.68, "CR-V e:PHEV 184 hk":0.50,
    "HR-V 1.5 e:HEV Hybrid 131 hk":0.65, "ZR-V 2.0 e:HEV Hybrid 184 hk":0.72,
    "e (el)":1.80, "e:Ny1 (el)":1.70
  },
  "Hyundai": {
    "i10 1.0 67 hk":0.50, "i10 1.0 T-GDI 100 hk":0.55, "i10 1.0 T-GDI 100 hk AMT":0.57,
    "i20 1.0 T-GDI 100 hk":0.56, "i20 1.0 T-GDI 100 hk DCT":0.59,
    "i20 1.0 T-GDI 120 hk":0.60, "i20 1.0 T-GDI 120 hk DCT":0.63, "i20 N Line 1.0 T-GDI 120 hk":0.62, "i20 N 1.6 T-GDI 204 hk":0.88,
    "i30 1.0 T-GDI 120 hk":0.62, "i30 1.0 T-GDI 120 hk DCT":0.65,
    "i30 1.5 T-GDI 160 hk":0.68, "i30 1.5 T-GDI 160 hk DCT":0.71,
    "i30 2.0 T-GDI 275 hk N":0.98, "i30 N 2.0 T-GDI 280 hk":0.98,
    "Tucson 1.6 T-GDI 150 hk":0.80, "Tucson 1.6 T-GDI 150 hk DCT":0.83,
    "Tucson 1.6 T-GDI Hybrid 230 hk":0.70, "Tucson 1.6 T-GDI Hybrid 230 hk 4x4":0.75,
    "Tucson 2.0 T-GDI 265 hk 4x4":0.95, "Tucson PHEV 265 hk":0.55,
    "Tucson 1.6 CRDi 115 hk (diesel)":0.62, "Tucson 2.0 CRDi 185 hk (diesel)":0.68,
    "Santa Fe 2.5 T-GDI 277 hk":0.98, "Santa Fe 1.6 T-GDI Hybrid 230 hk":0.82,
    "Santa Fe PHEV 291 hk":0.60, "Santa Fe 2.2 CRDi 202 hk (diesel)":0.72,
    "Kona 1.0 T-GDI 120 hk":0.68, "Kona 1.0 T-GDI 120 hk DCT":0.71,
    "Kona 1.6 T-GDI 198 hk":0.80, "Kona N 2.0 T-GDI 280 hk":1.00,
    "Kona Electric 39 kWh (el)":1.50, "Kona Electric 65 kWh (el)":1.65,
    "IONIQ 5 58 kWh RWD (el)":1.70, "IONIQ 5 72 kWh RWD (el)":1.80, "IONIQ 5 72 kWh AWD (el)":1.90, "IONIQ 5 N (el)":2.10,
    "IONIQ 6 53 kWh RWD (el)":1.50, "IONIQ 6 77 kWh RWD (el)":1.60, "IONIQ 6 77 kWh AWD (el)":1.72,
    "IONIQ 9 110 kWh (el)":2.10
  },
  "Jaguar": {
    "XE 2.0 P250 250 hk":0.80, "XE 2.0 D165 165 hk (diesel)":0.60, "XE 2.0 D204 204 hk (diesel)":0.62,
    "XF 2.0 P250 250 hk":0.85, "XF 2.0 P300 300 hk":0.92, "XF 2.0 D204 204 hk (diesel)":0.65, "XF 3.0 D300 300 hk (diesel)":0.70,
    "F-Pace 2.0 P300 300 hk":0.98, "F-Pace SVR 5.0 V8 550 hk":1.42,
    "F-Pace 2.0 D204 204 hk (diesel)":0.72, "F-Pace 3.0 D300 300 hk (diesel)":0.78,
    "E-Pace 1.5 P160 160 hk":0.82, "F-Type 2.0 P300 300 hk":1.05, "F-Type R 5.0 V8 575 hk":1.45,
    "I-Pace (el)":2.05
  },
  "Jeep": {
    "Renegade 1.3 T4 150 hk":0.80, "Renegade 4xe PHEV 240 hk":0.50,
    "Compass 1.3 T4 150 hk":0.80, "Compass 4xe PHEV 240 hk":0.50,
    "Grand Cherokee 2.0 T 272 hk":1.10, "Grand Cherokee 3.6 V6 293 hk":1.25, "Grand Cherokee 4xe PHEV 380 hk":0.68,
    "Wrangler 2.0 T 272 hk":1.20, "Wrangler 4xe PHEV 380 hk":0.72,
    "Avenger 1.2 T 100 hk":0.72, "Avenger (el)":1.60
  },
  "Kia": {
    "Picanto 1.0 67 hk":0.50, "Picanto 1.0 T-GDI 100 hk":0.55,
    "Picanto 1.0 67 hk":0.50, "Picanto 1.0 T-GDI 100 hk":0.55, "Picanto 1.2 84 hk":0.52,
    "Rio 1.0 T-GDI 100 hk":0.58, "Rio 1.0 T-GDI 100 hk DCT":0.61,
    "Rio 1.0 T-GDI 120 hk":0.62, "Rio 1.0 T-GDI 120 hk DCT":0.65,
    "Ceed 1.0 T-GDI 120 hk":0.62, "Ceed 1.0 T-GDI 120 hk DCT":0.65,
    "Ceed 1.5 T-GDI 160 hk":0.68, "Ceed 1.5 T-GDI 160 hk DCT":0.71,
    "ProCeed 1.5 T-GDI 160 hk":0.70, "ProCeed 1.5 T-GDI 160 hk DCT":0.73, "Ceed GT 1.6 T-GDI 204 hk":0.88,
    "XCeed 1.0 T-GDI 120 hk":0.70, "XCeed 1.5 T-GDI 160 hk DCT":0.75,
    "Stinger 2.0 T-GDI 255 hk":0.95, "Stinger 3.3 T-GDI 370 hk":1.12,
    "Sportage 1.6 T-GDI 150 hk":0.80, "Sportage 1.6 T-GDI 150 hk DCT":0.83,
    "Sportage 1.6 T-GDI HEV 230 hk":0.68, "Sportage 1.6 T-GDI HEV 230 hk 4x4":0.73,
    "Sportage 1.6 T-GDI 230 hk 4x4":0.92, "Sportage PHEV 265 hk":0.55,
    "Sportage 1.6 CRDi 136 hk (diesel)":0.62, "Sportage 2.0 CRDi 185 hk (diesel)":0.68,
    "Sorento 1.6 T-GDI HEV 230 hk":0.78, "Sorento 2.5 T-GDI 281 hk":1.00,
    "Sorento PHEV 291 hk":0.60, "Sorento 2.2 CRDi 202 hk (diesel)":0.78,
    "Niro 1.6 GDI HEV 141 hk":0.62, "Niro PHEV 183 hk":0.48, "Niro EV 65 kWh (el)":1.65,
    "EV6 58 kWh RWD (el)":1.55, "EV6 77 kWh RWD (el)":1.65, "EV6 77 kWh AWD (el)":1.78, "EV6 GT (el)":2.10,
    "EV9 76 kWh RWD (el)":2.00, "EV9 99 kWh AWD (el)":2.10, "EV3 (el)":1.55
  },
  "Lancia": {
    "Ypsilon 1.0 Hybrid 70 hk":0.55, "Ypsilon (el)":1.55
  },
  "Land Rover": {
    "Defender 90 2.0 P300 300 hk":1.10, "Defender 90 3.0 P400 400 hk":1.20, "Defender 90 5.0 V8 525 hk":1.55,
    "Defender 90 2.0 D200 200 hk (diesel)":0.82, "Defender 90 3.0 D300 300 hk (diesel)":0.88,
    "Defender 110 2.0 P300 300 hk":1.15, "Defender 110 3.0 P400 400 hk":1.25,
    "Defender 110 2.0 D200 200 hk (diesel)":0.85, "Defender 110 3.0 D300 300 hk (diesel)":0.92,
    "Discovery 2.0 P300 300 hk":1.12, "Discovery 3.0 D300 300 hk (diesel)":0.88,
    "Discovery Sport 2.0 P250 249 hk":0.95, "Discovery Sport PHEV 309 hk":0.58,
    "Discovery Sport 2.0 D150 150 hk (diesel)":0.70, "Discovery Sport 2.0 D200 200 hk (diesel)":0.75,
    "Range Rover Evoque 2.0 P300 300 hk":0.98, "Range Rover Evoque PHEV 309 hk":0.55,
    "Range Rover Evoque 2.0 D150 150 hk (diesel)":0.68, "Range Rover Evoque 2.0 D200 200 hk (diesel)":0.72,
    "Range Rover Sport 3.0 P360 360 hk":1.12, "Range Rover Sport 4.4 P530 530 hk":1.35, "Range Rover Sport SVR 5.0 V8 575 hk":1.55,
    "Range Rover Sport PHEV 440 hk":0.68, "Range Rover Sport 3.0 D300 300 hk (diesel)":0.85,
    "Range Rover 3.0 P360 360 hk":1.18, "Range Rover 4.4 P530 530 hk":1.42,
    "Range Rover PHEV 510 hk":0.75, "Range Rover 3.0 D300 300 hk (diesel)":0.90
  },
  "Lexus": {
    "IS 300h 2.5 Hybrid 223 hk":0.65, "IS 500 5.0 V8 472 hk":1.20,
    "ES 300h 2.5 Hybrid 218 hk":0.60,
    "NX 250h 2.5 Hybrid 207 hk":0.68, "NX 350h 2.5 Hybrid 244 hk":0.72, "NX 450h+ PHEV 309 hk":0.45,
    "UX 250h 2.0 Hybrid 184 hk":0.55, "UX 300e (el)":1.90,
    "RX 350h 2.5 Hybrid 250 hk":0.78, "RX 450h+ PHEV 309 hk":0.50, "RX 500h 2.4 T Hybrid 371 hk":0.85,
    "LC 500 5.0 V8 477 hk":1.10, "LC 500h 3.5 Hybrid 359 hk":0.90
  },
  "Lotus": {
    "Emira 2.0 T AMG 360 hk":1.02, "Emira V6 3.5 400 hk":1.10,
    "Eletre S (el)":2.15, "Eletre R (el)":2.30, "Emeya (el)":2.00, "Emeya R (el)":2.20
  },
  "Lucid": {
    "Air Pure (el)":1.55, "Air Touring (el)":1.65, "Air Grand Touring (el)":1.75, "Air Sapphire (el)":2.10
  },
  "Maserati": {
    "Ghibli 2.0 Mild Hybrid 330 hk":1.00, "Ghibli 3.0 V6 350 hk":1.10, "Ghibli 3.0 V6 Diesel (diesel)":0.78, "Ghibli Trofeo 3.8 V8 580 hk":1.42,
    "Levante 2.0 Mild Hybrid 330 hk":1.12, "Levante 3.0 V6 350 hk":1.18, "Levante 3.0 V6 275 hk (diesel)":0.88, "Levante Trofeo 3.8 V8 580 hk":1.55,
    "GranTurismo 3.0 Nettuno V6 490 hk":1.30, "GranTurismo Folgore (el)":2.10,
    "Grecale 2.0 MHEV 300 hk":1.00, "Grecale Trofeo 3.0 V6 530 hk":1.28, "Grecale Folgore (el)":2.00
  },
  "Mazda": {
    "Mazda 2 1.5 90 hk":0.52, "Mazda 2 Hybrid 116 hk":0.48,
    "Mazda 3 2.0 Skyactiv-G 122 hk":0.65, "Mazda 3 2.0 Skyactiv-G 122 hk Automat":0.68,
    "Mazda 3 2.0 Skyactiv-G 150 hk":0.68, "Mazda 3 2.0 Skyactiv-G 150 hk Automat":0.71,
    "Mazda 3 2.0 Skyactiv-X 186 hk":0.68, "Mazda 3 Turbo 2.5 265 hk":0.88,
    "Mazda 3 1.8 Skyactiv-D 116 hk (diesel)":0.52, "Mazda 3 2.2 Skyactiv-D 184 hk (diesel)":0.58,
    "Mazda 6 2.0 Skyactiv-G 165 hk":0.75, "Mazda 6 2.5 Skyactiv-G 194 hk":0.82,
    "Mazda 6 2.0 Skyactiv-D 145 hk (diesel)":0.58, "Mazda 6 2.2 Skyactiv-D 175 hk (diesel)":0.62,
    "CX-30 2.0 Skyactiv-G 122 hk":0.68, "CX-30 2.0 Skyactiv-G 150 hk":0.72, "CX-30 2.0 Skyactiv-G 150 hk Automat":0.75,
    "CX-30 2.0 Skyactiv-X 186 hk":0.75, "CX-30 2.0 e-Skyactiv G 150 hk":0.72,
    "CX-5 2.0 Skyactiv-G 165 hk":0.80, "CX-5 2.0 Skyactiv-G 165 hk Automat":0.83,
    "CX-5 2.5 Skyactiv-G 194 hk":0.88, "CX-5 2.5 Skyactiv-G 194 hk Automat":0.91, "CX-5 2.5 Turbo 230 hk":0.95,
    "CX-5 2.2 Skyactiv-D 150 hk (diesel)":0.62, "CX-5 2.2 Skyactiv-D 184 hk (diesel)":0.65,
    "CX-60 2.5 PHEV 327 hk":0.50, "CX-60 e-Skyactiv D 254 hk (diesel)":0.65, "CX-60 3.3 e-Skyactiv D 280 hk (diesel)":0.68,
    "CX-90 3.3 Turbo 254 hk":0.98, "CX-90 PHEV 323 hk":0.62,
    "MX-5 1.5 132 hk":0.68, "MX-5 2.0 184 hk":0.72, "MX-30 (el)":1.70
  },
  "Mercedes-Benz": {
    "A 180 1.3 136 hk":0.65, "A 200 1.3 163 hk":0.68, "A 250 2.0 224 hk":0.78,
    "A 180 d 1.5 116 hk (diesel)":0.52, "A 200 d 2.0 150 hk (diesel)":0.55, "A 220 d 2.0 190 hk (diesel)":0.58,
    "A 250 e PHEV 218 hk":0.45, "A 45 S AMG 2.0 421 hk":1.05,
    "B 180 1.3 136 hk":0.68, "B 200 1.3 163 hk":0.72, "B 250 e PHEV 218 hk":0.48,
    "C 180 1.5 170 hk":0.70, "C 200 1.5 204 hk":0.75, "C 300 2.0 258 hk":0.85,
    "C 180 d 2.0 122 hk (diesel)":0.55, "C 200 d 2.0 163 hk (diesel)":0.58, "C 220 d 2.0 200 hk (diesel)":0.60, "C 300 d 2.0 265 hk (diesel)":0.65,
    "C 300 e PHEV 313 hk":0.50, "C 43 AMG 3.0 T 408 hk":1.00,
    "E 200 2.0 204 hk":0.82, "E 300 2.0 258 hk":0.90, "E 450 3.0 367 hk":1.05,
    "E 200 d 2.0 163 hk (diesel)":0.62, "E 220 d 2.0 200 hk (diesel)":0.64, "E 300 d 2.0 265 hk (diesel)":0.68,
    "E 300 e PHEV 313 hk":0.55, "E 53 AMG 3.0 T 435 hk":1.10, "E 63 AMG S 4.0 V8 612 hk":1.40,
    "S 450 3.0 367 hk":1.10, "S 500 3.0 449 hk":1.20, "S 580 4.0 V8 503 hk":1.35,
    "S 350 d 3.0 286 hk (diesel)":0.80, "S 400 d 3.0 330 hk (diesel)":0.82,
    "S 500 e PHEV 510 hk":0.72, "S 63 AMG 4.0 V8 612 hk":1.48,
    "CLA 180 1.3 136 hk":0.68, "CLA 250 2.0 224 hk":0.82, "CLA 45 S AMG 2.0 421 hk":1.08,
    "GLA 200 1.3 163 hk":0.75, "GLA 250 2.0 224 hk":0.85, "GLA 200 d 2.0 150 hk (diesel)":0.60, "GLA 250 e PHEV 218 hk":0.50, "GLA 45 S AMG 421 hk":1.10,
    "GLB 200 1.3 163 hk":0.80, "GLB 250 2.0 224 hk":0.88, "GLB 200 d 2.0 150 hk (diesel)":0.62, "GLB 250 e PHEV 218 hk":0.52,
    "GLC 200 2.0 204 hk":0.88, "GLC 300 2.0 258 hk":0.95, "GLC 300 e PHEV 313 hk":0.58, "GLC 43 AMG 3.0 T 421 hk":1.10,
    "GLC 200 d 2.0 163 hk (diesel)":0.68, "GLC 220 d 2.0 197 hk (diesel)":0.70, "GLC 300 d 2.0 265 hk (diesel)":0.72,
    "GLE 450 3.0 367 hk":1.08, "GLE 53 AMG 3.0 T 449 hk":1.20, "GLE 63 AMG S 4.0 V8 612 hk":1.50,
    "GLE 300 d 2.0 265 hk (diesel)":0.80, "GLE 400 d 3.0 330 hk (diesel)":0.85,
    "GLS 450 3.0 367 hk":1.18, "GLS 400 d 3.0 330 hk (diesel)":0.90,
    "G 500 4.0 V8 422 hk":1.65, "G 63 AMG 4.0 V8 585 hk":1.90,
    "EQA 250 (el)":1.75, "EQA 300 4MATIC (el)":1.88,
    "EQB 250 (el)":1.80, "EQB 300 4MATIC (el)":1.90,
    "EQC 400 4MATIC (el)":2.10,
    "EQE 300 (el)":1.70, "EQE 350 (el)":1.75, "EQE 500 4MATIC (el)":1.85, "EQE 53 AMG (el)":2.00,
    "EQS 450 (el)":1.75, "EQS 580 4MATIC (el)":1.85, "EQS 53 AMG (el)":2.10
  },
  "MG": {
    "MG3 1.5 106 hk":0.65,
    "MG ZS 1.5 106 hk":0.70, "MG ZS EV Standard Range (el)":1.60, "MG ZS EV Long Range (el)":1.72,
    "MG4 EV Standard 51 kWh (el)":1.48, "MG4 EV Long Range 64 kWh (el)":1.55, "MG4 EV Extended 77 kWh (el)":1.62, "MG4 EV XPOWER AWD (el)":1.75,
    "MG5 EV Standard Range (el)":1.58, "MG5 EV Long Range (el)":1.68,
    "MG Marvel R (el)":1.85, "MG HS 1.5 T 162 hk":0.80, "MG HS PHEV 258 hk":0.50,
    "MG Cyberster (el)":1.80
  },
  "Mini": {
    "Cooper 1.5 136 hk":0.65, "Cooper S 2.0 178 hk":0.75, "Cooper S 2.0 204 hk":0.78, "John Cooper Works 2.0 231 hk":0.92,
    "Cooper SE (el)":1.60, "Cooper C (el)":1.55, "Cooper S (el)":1.65,
    "Clubman Cooper S 2.0 192 hk":0.75,
    "Countryman Cooper S 2.0 192 hk":0.78, "Countryman Cooper SE ALL4 PHEV 224 hk":0.50, "Countryman JCW 2.0 306 hk":1.00, "Countryman (el)":1.75,
    "Aceman (el)":1.60
  },
  "Mitsubishi": {
    "Colt 1.0 T 90 hk":0.58, "Colt 1.3 T 143 hk":0.65,
    "Lancer 1.5 109 hk":0.70, "Lancer EVO X 2.0 Turbo 295 hk":1.15,
    "Eclipse Cross 1.5 T 163 hk":0.82, "Eclipse Cross PHEV 188 hk":0.52,
    "Outlander 2.5 165 hk":0.92, "Outlander PHEV 301 hk":0.55,
    "ASX 1.0 T 101 hk":0.68, "ASX 1.3 T 158 hk":0.75,
    "L200 2.2 D 150 hk (diesel)":0.90, "L200 2.2 D 180 hk (diesel)":0.95
  },
  "Nio": {
    "ET5 75 kWh Standard Range (el)":1.60, "ET5 100 kWh Long Range (el)":1.72, "ET5 Touring (el)":1.72,
    "ET7 75 kWh (el)":1.78, "ET7 100 kWh (el)":1.85,
    "EL6 75 kWh (el)":1.80, "EL6 100 kWh (el)":1.90,
    "EL8 100 kWh (el)":2.00
  },
  "Nissan": {
    "Micra 1.0 IG-T 100 hk":0.55,
    "Juke 1.0 DIG-T 117 hk":0.68, "Juke 1.6 Hybrid 143 hk":0.62,
    "Qashqai 1.3 DIG-T 140 hk":0.75, "Qashqai 1.3 DIG-T 158 hk":0.78, "Qashqai e-POWER 190 hk":0.65,
    "Qashqai 1.5 dCi 115 hk (diesel)":0.58,
    "X-Trail 1.5 T 163 hk":0.85, "X-Trail e-POWER 213 hk":0.72, "X-Trail 2.0 dCi 177 hk (diesel)":0.68,
    "Leaf 40 kWh (el)":1.55, "Leaf e+ 62 kWh (el)":1.65, "Ariya 63 kWh FWD (el)":1.80, "Ariya 87 kWh AWD (el)":2.00,
    "GT-R 3.8 V6 570 hk":1.55
  },
  "Opel": {
    "Corsa 1.2 75 hk":0.55, "Corsa 1.2 T 100 hk":0.58, "Corsa 1.2 T 100 hk AT8":0.61,
    "Corsa 1.2 T 130 hk":0.62, "Corsa 1.2 T 130 hk AT8":0.65, "Corsa 1.5 D 102 hk (diesel)":0.50, "Corsa-e (el)":1.55,
    "Astra 1.2 T 110 hk":0.62, "Astra 1.2 T 130 hk":0.65, "Astra 1.2 T 130 hk AT8":0.68,
    "Astra PHEV 180 hk":0.48, "Astra 1.5 D 130 hk (diesel)":0.52, "Astra-e (el)":1.65,
    "Insignia 1.5 T 140 hk":0.75, "Insignia 2.0 T 200 hk":0.82, "Insignia 2.0 D 170 hk (diesel)":0.60,
    "Mokka 1.2 T 100 hk":0.68, "Mokka 1.2 T 130 hk":0.72, "Mokka 1.2 T 130 hk AT8":0.75,
    "Mokka 1.5 D 110 hk (diesel)":0.55, "Mokka-e (el)":1.65,
    "Grandland 1.2 T 130 hk":0.82, "Grandland 1.2 T 130 hk AT8":0.85,
    "Grandland X 1.2 T 130 hk":0.82, "Grandland X PHEV 225 hk":0.50,
    "Grandland X 1.5 D 130 hk (diesel)":0.62, "Grandland X 1.5 D 130 hk AT8 (diesel)":0.65,
    "Crossland 1.2 T 110 hk":0.68, "Crossland 1.5 D 102 hk (diesel)":0.54
  },
  "Peugeot": {
    "108 1.0 VTi 72 hk":0.48,
    "108 1.0 VTi 72 hk":0.48,
    "208 1.2 PureTech 75 hk":0.55, "208 1.2 PureTech 100 hk":0.58, "208 1.2 PureTech 100 hk EAT8":0.61,
    "208 1.2 PureTech 130 hk":0.62, "208 1.2 PureTech 130 hk EAT8":0.65,
    "208 1.5 BlueHDi 100 hk (diesel)":0.48,
    "e-208 50 kWh (el)":1.45, "e-208 54 kWh (el)":1.48,
    "308 1.2 PureTech 110 hk":0.62, "308 1.2 PureTech 110 hk EAT8":0.65,
    "308 1.2 PureTech 130 hk":0.65, "308 1.2 PureTech 130 hk EAT8":0.68,
    "308 1.6 PureTech 180 hk":0.78, "308 1.6 PureTech 180 hk EAT8":0.81,
    "308 1.5 BlueHDi 130 hk (diesel)":0.52, "308 PHEV 225 hk":0.48, "e-308 (el)":1.60,
    "408 1.2 PureTech 130 hk":0.72, "408 1.2 PureTech 130 hk EAT8":0.75, "408 PHEV 225 hk":0.50, "e-408 (el)":1.68,
    "508 1.6 PureTech 180 hk":0.80, "508 1.6 PureTech 225 hk":0.85, "508 2.0 BlueHDi 160 hk (diesel)":0.60, "508 PHEV 360 hk":0.55,
    "2008 1.2 PureTech 100 hk":0.62, "2008 1.2 PureTech 100 hk EAT8":0.65, "2008 1.2 PureTech 130 hk":0.65, "2008 1.2 PureTech 130 hk EAT8":0.68, "2008 1.2 PureTech 155 hk EAT8":0.72, "2008 1.5 BlueHDi 110 hk (diesel)":0.52, "2008 1.5 BlueHDi 130 hk (diesel)":0.55, "e-2008 50 kWh (el)":1.55, "e-2008 54 kWh (el)":1.60,
    "3008 1.2 PureTech 130 hk":0.78, "3008 1.2 PureTech 130 hk EAT8":0.81,
    "3008 1.6 PureTech 180 hk EAT8":0.90,
    "3008 1.5 BlueHDi 130 hk (diesel)":0.60, "3008 PHEV 225 hk":0.50, "e-3008 73 kWh (el)":1.65, "e-3008 98 kWh (el)":1.72,
    "5008 1.2 PureTech 130 hk":0.85, "5008 1.2 PureTech 130 hk EAT8":0.88,
    "5008 1.5 BlueHDi 130 hk (diesel)":0.65, "5008 1.5 BlueHDi 130 hk EAT8 (diesel)":0.68,
    "5008 2.0 BlueHDi 180 hk (diesel)":0.72
  },
  "Polestar": {
    "Polestar 2 Standard Range Single Motor (el)":1.60, "Polestar 2 Long Range Single Motor (el)":1.68, "Polestar 2 Long Range Dual Motor (el)":1.78,
    "Polestar 3 Long Range Dual Motor (el)":2.00, "Polestar 3 Long Range Dual Motor Performance (el)":2.10,
    "Polestar 4 Long Range Single Motor (el)":1.70, "Polestar 4 Long Range Dual Motor (el)":1.80
  },
  "Porsche": {
    "718 Cayman 2.0 300 hk":0.88, "718 Cayman S 2.5 350 hk":0.98, "718 Cayman GTS 4.0 400 hk":1.05, "718 Cayman GT4 RS 4.0 500 hk":1.20,
    "718 Boxster 2.0 300 hk":0.88, "718 Boxster S 2.5 350 hk":0.98,
    "911 Carrera 3.0 385 hk":0.95, "911 Carrera S 3.0 450 hk":1.02, "911 Carrera 4S 3.0 450 hk":1.08, "911 Turbo S 3.8 650 hk":1.30, "911 GT3 4.0 510 hk":1.15,
    "Macan 2.0 T 265 hk":0.92, "Macan S 2.9 V6 380 hk":1.05, "Macan GTS 2.9 V6 440 hk":1.12, "Macan Turbo 2.9 V6 440 hk":1.15,
    "Macan (el)":1.90, "Macan 4 (el)":1.95, "Macan Turbo (el)":2.10,
    "Panamera 2.9 V6 330 hk":1.00, "Panamera 4 E-Hybrid PHEV 462 hk":0.68, "Panamera GTS 4.0 V8 480 hk":1.22, "Panamera Turbo S E-Hybrid PHEV 700 hk":0.88,
    "Cayenne 3.0 340 hk":1.10, "Cayenne S 2.9 V6 440 hk":1.20, "Cayenne GTS 4.0 V8 460 hk":1.30, "Cayenne Turbo 4.0 V8 550 hk":1.45,
    "Cayenne E-Hybrid PHEV 462 hk":0.65, "Cayenne 3.0 Diesel 262 hk (diesel)":0.82,
    "Taycan (el)":1.95, "Taycan 4S (el)":2.05, "Taycan GTS (el)":2.10, "Taycan Turbo (el)":2.18, "Taycan Turbo S (el)":2.30,
    "Taycan Sport Turismo (el)":1.98, "Taycan Cross Turismo (el)":2.05
  },
  "Renault": {
    "Twingo 1.0 65 hk":0.50, "Twingo Electric (el)":1.35,
    "Twingo 1.0 65 hk":0.50, "Twingo Electric (el)":1.35,
    "Clio 1.0 TCe 90 hk":0.55, "Clio 1.0 TCe 90 hk EDC":0.58,
    "Clio 1.0 TCe 100 hk":0.58, "Clio 1.0 TCe 100 hk EDC":0.61,
    "Clio 1.3 TCe 130 hk":0.62, "Clio 1.3 TCe 130 hk EDC":0.65,
    "Clio E-Tech Hybrid 145 hk":0.52, "Clio RS 1.8 T 220 hk":0.90,
    "Clio 1.5 dCi 85 hk (diesel)":0.50,
    "Megane 1.3 TCe 115 hk":0.62, "Megane 1.3 TCe 115 hk EDC":0.65,
    "Megane 1.3 TCe 140 hk":0.65, "Megane 1.3 TCe 140 hk EDC":0.68,
    "Megane E-Tech PHEV 160 hk":0.48, "Megane RS 1.8 T 280 hk":0.98,
    "Megane 1.5 dCi 110 hk (diesel)":0.52,
    "Megane E-Tech 40 kWh (el)":1.55, "Megane E-Tech 60 kWh (el)":1.62,
    "Captur 1.0 TCe 90 hk":0.65, "Captur 1.0 TCe 90 hk EDC":0.68,
    "Captur 1.3 TCe 140 hk":0.70, "Captur 1.3 TCe 140 hk EDC":0.73, "Captur E-Tech PHEV 160 hk":0.48,
    "Arkana 1.3 TCe 140 hk":0.72, "Arkana E-Tech Hybrid 145 hk":0.62,
    "Kadjar 1.3 TCe 140 hk":0.78, "Kadjar 1.5 dCi 115 hk (diesel)":0.62,
    "Koleos 2.0 dCi 175 hk (diesel)":0.88,
    "Talisman 1.3 TCe 140 hk":0.78, "Talisman E-Tech PHEV 225 hk":0.52,
    "Scenic E-Tech 60 kWh (el)":1.60, "Scenic E-Tech 87 kWh (el)":1.68,
    "Austral E-Tech Hybrid 200 hk":0.68, "Austral E-Tech PHEV 300 hk":0.52,
    "Zoe R110 52 kWh (el)":1.52,
    "5 E-Tech 40 kWh (el)":1.35, "5 E-Tech 52 kWh (el)":1.42
  },
  "Rolls-Royce": {
    "Ghost 6.75 V12 563 hk":2.00, "Ghost Extended 6.75 V12 563 hk":2.10,
    "Phantom 6.75 V12 571 hk":2.20, "Phantom Extended 6.75 V12 571 hk":2.30,
    "Wraith 6.6 V12 632 hk":2.10, "Dawn 6.6 V12 571 hk":2.05,
    "Cullinan 6.75 V12 571 hk":2.25, "Spectre (el)":2.50
  },
  "Saab": {
    "9-3 1.8 122 hk":0.75, "9-3 2.0 T 175 hk":0.85, "9-3 2.0 T Aero 250 hk":1.00, "9-3 2.8 V6 T 280 hk":1.08,
    "9-3 1.9 TiD 120 hk (diesel)":0.60, "9-3 1.9 TTiD 180 hk (diesel)":0.65,
    "9-5 2.0 T 150 hk":0.90, "9-5 2.3 T Aero 260 hk":1.08, "9-5 2.8 V6 T 300 hk":1.15
  },
  "SEAT": {
    "Ibiza 1.0 MPI 80 hk":0.55, "Ibiza 1.0 TSI 95 hk":0.58, "Ibiza 1.0 TSI 95 hk DSG":0.61,
    "Ibiza 1.0 TSI 110 hk":0.60, "Ibiza 1.0 TSI 110 hk DSG":0.63, "Ibiza FR 1.5 TSI 150 hk":0.68, "Ibiza FR 1.5 TSI 150 hk DSG":0.71,
    "Leon 1.0 TSI 90 hk":0.58, "Leon 1.5 TSI 130 hk":0.65, "Leon 1.5 TSI 130 hk DSG":0.68,
    "Leon 1.5 TSI 150 hk":0.68, "Leon 1.5 TSI 150 hk DSG":0.71,
    "Leon 2.0 TSI 190 hk":0.80, "Leon 2.0 TSI 190 hk DSG":0.83, "Leon e-Hybrid PHEV 204 hk":0.48,
    "Leon 2.0 TDI 115 hk (diesel)":0.50, "Leon 2.0 TDI 150 hk (diesel)":0.52,
    "Ateca 1.5 TSI 150 hk":0.75, "Ateca 1.5 TSI 150 hk DSG":0.78,
    "Ateca 2.0 TSI 190 hk":0.85, "Ateca 2.0 TSI 190 hk DSG":0.88, "Ateca 2.0 TSI 300 hk 4x4":0.98, "Ateca 2.0 TDI 150 hk (diesel)":0.60,
    "Tarraco 1.5 TSI 150 hk":0.88, "Tarraco 1.5 TSI 150 hk DSG":0.91,
    "Tarraco 2.0 TSI 190 hk 4x4":0.98, "Tarraco 2.0 TSI 190 hk DSG 4x4":1.01, "Tarraco 2.0 TDI 150 hk (diesel)":0.68,
    "Arona 1.0 TSI 110 hk":0.65, "Arona 1.0 TSI 110 hk DSG":0.68
  },
  "Skoda": {
    "Fabia 1.0 MPI 65 hk":0.52, "Fabia 1.0 TSI 95 hk":0.56, "Fabia 1.0 TSI 95 hk DSG":0.59,
    "Fabia 1.0 TSI 116 hk":0.60, "Fabia 1.0 TSI 116 hk DSG":0.63,
    "Fabia Monte Carlo 1.5 TSI 150 hk":0.70, "Fabia Monte Carlo 1.5 TSI 150 hk DSG":0.73,
    "Octavia 1.0 TSI 110 hk":0.65, "Octavia 1.0 TSI 110 hk DSG":0.68,
    "Octavia 1.5 TSI 150 hk":0.70, "Octavia 1.5 TSI 150 hk DSG":0.73,
    "Octavia 2.0 TSI 180 hk":0.80, "Octavia 2.0 TSI 180 hk DSG":0.83, "Octavia RS 2.0 TSI 245 hk":0.95, "Octavia RS 2.0 TSI 245 hk DSG":0.98,
    "Octavia 2.0 TDI 115 hk (diesel)":0.52, "Octavia 2.0 TDI 150 hk (diesel)":0.55, "Octavia 2.0 TDI 200 hk (diesel)":0.58,
    "Octavia iV PHEV 204 hk":0.48,
    "Superb 1.5 TSI 150 hk":0.78, "Superb 1.5 TSI 150 hk DSG":0.81,
    "Superb 2.0 TSI 200 hk":0.88, "Superb 2.0 TSI 280 hk":0.98, "Superb iV PHEV 218 hk":0.52,
    "Superb 2.0 TDI 150 hk (diesel)":0.58, "Superb 2.0 TDI 200 hk (diesel)":0.62,
    "Scala 1.0 TSI 95 hk":0.62, "Scala 1.0 TSI 95 hk DSG":0.65, "Scala 1.5 TSI 150 hk":0.68, "Scala 1.5 TSI 150 hk DSG":0.71,
    "Kamiq 1.0 TSI 95 hk":0.65, "Kamiq 1.0 TSI 110 hk":0.67, "Kamiq 1.0 TSI 110 hk DSG":0.69, "Kamiq 1.5 TSI 150 hk":0.72, "Kamiq 1.5 TSI 150 hk DSG":0.74, "Kamiq 2.0 TDI 115 hk (diesel)":0.54,
    "Karoq 1.0 TSI 116 hk":0.75, "Karoq 1.0 TSI 116 hk DSG":0.78,
    "Karoq 1.5 TSI 150 hk":0.82, "Karoq 1.5 TSI 150 hk DSG":0.85,
    "Karoq 2.0 TSI 190 hk":0.90, "Karoq 2.0 TDI 116 hk (diesel)":0.60, "Karoq 2.0 TDI 150 hk (diesel)":0.62,
    "Kodiaq 1.5 TSI 150 hk":0.88, "Kodiaq 1.5 TSI 150 hk DSG":0.91,
    "Kodiaq 2.0 TSI 190 hk":0.95, "Kodiaq 2.0 TSI 190 hk DSG":0.98, "Kodiaq RS 2.0 TSI 245 hk":1.05,
    "Kodiaq 2.0 TDI 150 hk (diesel)":0.68, "Kodiaq 2.0 TDI 200 hk (diesel)":0.72,
    "Enyaq 60 (el)":1.72, "Enyaq 80 (el)":1.82, "Enyaq 80x AWD (el)":1.90, "Enyaq RS (el)":2.00,
    "Elroq 50 (el)":1.60, "Elroq 85 (el)":1.65, "Elroq 85x AWD (el)":1.75
  },
  "Smart": {
    "#1 Pure (el)":1.65, "#1 Pro (el)":1.68, "#1 Brabus (el)":1.85,
    "#3 Pro (el)":1.72, "#3 Brabus (el)":1.90
  },
  "Subaru": {
    "Impreza 2.0 150 hk":0.78, "Impreza WRX 2.5 T 268 hk":1.05, "Impreza STI 2.5 T 301 hk":1.18,
    "Legacy 2.5 173 hk":0.88, "Outback 2.5 173 hk":0.95, "Outback 2.0D 150 hk (diesel)":0.72,
    "Forester 2.0 e-BOXER Hybrid 150 hk":0.80, "Forester 2.5 184 hk":0.90,
    "XV 2.0 e-BOXER Hybrid 150 hk":0.75, "BRZ 2.4 234 hk":0.85,
    "WRX 2.4 T 268 hk":1.05, "Solterra (el)":1.90
  },
  "Suzuki": {
    "Alto 1.0 66 hk":0.45, "Swift 1.0 Boosterjet 111 hk":0.55, "Swift 1.2 Hybrid MHEV 90 hk":0.50, "Swift Sport 1.4 Boosterjet 140 hk":0.75,
    "Baleno 1.2 Hybrid 90 hk":0.55, "Vitara 1.4 Boosterjet 129 hk":0.70, "Vitara 1.5 MHEV 102 hk":0.65,
    "SX4 S-Cross 1.4 Boosterjet 129 hk":0.72, "Jimny 1.5 102 hk":0.78,
    "Ignis 1.2 MHEV 83 hk":0.55, "Swace 1.8 Hybrid 122 hk":0.48, "Across PHEV 306 hk":0.55
  },
  "Tesla": {
    "Model 3 Standard Range (el)":1.45, "Model 3 Long Range AWD (el)":1.55, "Model 3 Performance (el)":1.65,
    "Model 3 Highland Long Range (el)":1.52, "Model 3 Highland Performance (el)":1.62,
    "Model Y Standard Range (el)":1.60, "Model Y Long Range AWD (el)":1.70, "Model Y Performance (el)":1.80,
    "Model Y Juniper Long Range (el)":1.68,
    "Model S Long Range (el)":1.70, "Model S Plaid (el)":1.90,
    "Model X Long Range (el)":2.00, "Model X Plaid (el)":2.20,
    "Cybertruck AWD (el)":2.80
  },
  "Toyota": {
    "Aygo X 1.0 72 hk":0.48,
    "Yaris 1.5 Hybrid 116 hk":0.48, "Yaris 1.5 125 hk":0.55,
    "GR Yaris 1.6 Turbo 261 hk":0.82, "GR Yaris 1.6 Turbo 300 hk Circuit":0.88,
    "Yaris Cross 1.5 Hybrid 116 hk":0.52,
    "Corolla 1.8 Hybrid 122 hk":0.55, "Corolla 2.0 Hybrid 184 hk":0.60, "Corolla GR Sport 2.0 Hybrid 197 hk":0.62,
    "Camry 2.5 Hybrid 218 hk":0.68,
    "C-HR 1.8 Hybrid 122 hk":0.62, "C-HR 2.0 Hybrid 197 hk":0.68,
    "Prius 2.5 Hybrid 223 hk":0.52, "Prius 2.0 PHEV 223 hk":0.48,
    "RAV4 2.5 Hybrid 222 hk":0.75, "RAV4 2.5 AWD-i Hybrid 222 hk":0.78, "RAV4 PHEV 306 hk":0.55, "RAV4 2.0 175 hk":0.80,
    "RAV4 2.5 D-4D 204 hk (diesel)":0.65,
    "bZ4X 71 kWh FWD (el)":1.80, "bZ4X 71 kWh AWD (el)":1.92,
    "Land Cruiser 2.8 D 204 hk (diesel)":0.95, "Land Cruiser 3.3 D 309 hk (diesel)":0.98,
    "Highlander 2.5 AWD Hybrid 248 hk":0.88,
    "Crown 2.5 PHEV 300 hk":0.58
  },
  "Volkswagen": {
    "Polo 1.0 MPI 80 hk":0.55, "Polo 1.0 TSI 95 hk":0.58, "Polo 1.0 TSI 95 hk DSG":0.61,
    "Polo 1.0 TSI 110 hk":0.62, "Polo 1.0 TSI 110 hk DSG":0.65, "Polo GTI 2.0 TSI 207 hk":0.88, "Polo GTI 2.0 TSI 207 hk DSG":0.91,
    "Golf 1.0 TSI 110 hk":0.62, "Golf 1.0 TSI 110 hk DSG":0.65,
    "Golf 1.0 eTSI 110 hk MHEV":0.60, "Golf 1.5 TSI 130 hk":0.65, "Golf 1.5 TSI 130 hk DSG":0.68,
    "Golf 1.5 eTSI 150 hk MHEV":0.62,
    "Golf GTI 2.0 TSI 245 hk":0.92, "Golf GTI Clubsport 300 hk":1.00, "Golf R 2.0 TSI 333 hk":1.08,
    "Golf 2.0 TDI 115 hk (diesel)":0.52, "Golf 2.0 TDI 150 hk (diesel)":0.55,
    "Golf eHybrid PHEV 204 hk":0.48, "Golf GTE PHEV 245 hk":0.52,
    "Passat 1.5 TSI 150 hk":0.75, "Passat 1.5 TSI 150 hk DSG":0.78,
    "Passat 2.0 TSI 190 hk":0.85, "Passat 2.0 TSI 190 hk DSG":0.88,
    "Passat 1.5 eTSI MHEV 150 hk":0.72, "Passat GTE PHEV 218 hk":0.52,
    "Passat 2.0 TDI 150 hk (diesel)":0.58, "Passat 2.0 TDI 200 hk (diesel)":0.62,
    "Arteon 2.0 TSI 190 hk":0.85, "Arteon 2.0 TSI 280 hk":0.95, "Arteon 2.0 TDI 150 hk (diesel)":0.65,
    "Tiguan 1.5 TSI 130 hk":0.78, "Tiguan 1.5 TSI 130 hk DSG":0.81,
    "Tiguan 1.5 eTSI MHEV 150 hk":0.75,
    "Tiguan 2.0 TSI 190 hk":0.88, "Tiguan 2.0 TSI 190 hk DSG":0.91, "Tiguan R 2.0 TSI 320 hk":1.02,
    "Tiguan eHybrid PHEV 245 hk":0.55, "Tiguan 2.0 TDI 150 hk (diesel)":0.62, "Tiguan 2.0 TDI 200 hk (diesel)":0.65,
    "T-Roc 1.0 TSI 110 hk":0.72, "T-Roc 1.0 TSI 110 hk DSG":0.75,
    "T-Roc 1.5 TSI 150 hk":0.78, "T-Roc 1.5 TSI 150 hk DSG":0.81,
    "T-Roc 2.0 TSI 190 hk":0.88, "T-Roc R 2.0 TSI 300 hk":1.02,
    "T-Roc 2.0 TDI 150 hk (diesel)":0.62,
    "T-Cross 1.0 TSI 95 hk":0.68, "T-Cross 1.0 TSI 95 hk DSG":0.71,
    "T-Cross 1.0 TSI 110 hk":0.72, "T-Cross 1.0 TSI 110 hk DSG":0.75,
    "Taigo 1.0 TSI 110 hk":0.70, "Taigo 1.0 TSI 110 hk DSG":0.73,
    "Touareg 3.0 V6 TSI 340 hk":1.05, "Touareg eHybrid PHEV 462 hk":0.68, "Touareg 3.0 TDI 231 hk (diesel)":0.78, "Touareg 3.0 TDI 286 hk (diesel)":0.82,
    "ID.3 45 kWh (el)":1.55, "ID.3 58 kWh (el)":1.65, "ID.3 77 kWh (el)":1.72, "ID.3 GTX Performance (el)":1.85,
    "ID.4 52 kWh (el)":1.72, "ID.4 77 kWh RWD (el)":1.82, "ID.4 77 kWh AWD (el)":1.92, "ID.4 GTX (el)":2.00,
    "ID.5 77 kWh (el)":1.85, "ID.5 GTX (el)":2.00,
    "ID.7 77 kWh (el)":1.68, "ID.7 Tourer 77 kWh (el)":1.72, "ID.7 GTX (el)":1.80,
    "ID.Buzz 77 kWh (el)":2.00, "ID.Buzz LWB 7-sits (el)":2.10
  },
  "Volvo": {
    "S60 B3 163 hk":0.68, "S60 B4 197 hk":0.75, "S60 B5 AWD 250 hk":0.82, "S60 T8 PHEV 455 hk":0.55,
    "V60 B3 163 hk":0.70, "V60 B4 197 hk":0.78, "V60 B5 AWD 250 hk":0.85, "V60 T8 PHEV 455 hk":0.55,
    "V60 B4 D (diesel)":0.55, "V60 B5 D AWD (diesel)":0.60,
    "V60 Cross Country B4 197 hk":0.82, "V60 Cross Country B5 AWD 250 hk":0.88,
    "V70 2.0 T 203 hk":0.85, "V70 2.4 D5 163 hk (diesel)":0.65,
    "V90 B4 197 hk":0.80, "V90 B5 AWD 250 hk":0.88, "V90 B6 AWD 300 hk":0.95, "V90 T8 PHEV 455 hk":0.58,
    "V90 B4 D (diesel)":0.58, "V90 B5 D AWD (diesel)":0.62,
    "V90 Cross Country B5 AWD 250 hk":0.92, "V90 Cross Country B5 D AWD (diesel)":0.65,
    "S90 B4 197 hk":0.82, "S90 B5 AWD 250 hk":0.90, "S90 B6 AWD 300 hk":0.98, "S90 T8 PHEV 455 hk":0.58,
    "XC40 B3 163 hk":0.75, "XC40 B4 197 hk":0.80, "XC40 B5 AWD 247 hk":0.88, "XC40 T5 PHEV 262 hk":0.55,
    "XC40 B4 D (diesel)":0.60,
    "XC40 Recharge Single Motor (el)":1.75, "XC40 Recharge Twin Motor AWD (el)":1.90,
    "EX30 Single Motor 272 hk (el)":1.50, "EX30 Single Motor Extended Range (el)":1.55, "EX30 Twin Motor Performance (el)":1.68,
    "EX40 Single Motor (el)":1.75, "EX40 Twin Motor AWD (el)":1.88,
    "EC40 Single Motor (el)":1.78, "EC40 Twin Motor AWD (el)":1.90,
    "EX90 Twin Motor (el)":2.00, "EX90 Twin Motor Performance (el)":2.10,
    "XC60 B4 197 hk":0.85, "XC60 B5 AWD 250 hk":0.92, "XC60 B6 AWD 300 hk":1.00, "XC60 T6 PHEV 340 hk":0.58, "XC60 T8 PHEV 455 hk":0.60,
    "XC60 D4 FWD 190 hk (diesel)":0.62, "XC60 D5 AWD 235 hk (diesel)":0.68,
    "XC70 2.0 T5 AWD 231 hk":0.98, "XC70 D5 AWD 220 hk (diesel)":0.75,
    "XC90 B5 AWD 250 hk":1.02, "XC90 B6 AWD 300 hk":1.10, "XC90 T8 PHEV 455 hk":0.65,
    "XC90 D5 AWD 235 hk (diesel)":0.75,
    "C30 1.6 100 hk":0.62, "C30 T5 230 hk":0.98, "C70 2.0 180 hk":0.85, "EM90 (el)":2.20
  },
  "VinFast": {
    "VF6 (el)":1.75, "VF7 (el)":1.88, "VF8 Standard Range (el)":1.95, "VF9 (el)":2.20
  },
  "Xpeng": {
    "G6 RWD (el)":1.70, "G6 AWD (el)":1.80, "G9 RWD (el)":1.88, "G9 AWD (el)":2.00, "P7 (el)":1.72, "X9 (el)":2.10
  },
  "Zeekr": {
    "001 WE (el)":1.68, "001 FR AWD (el)":1.85, "007 RWD (el)":1.55, "007 AWD (el)":1.65, "X (el)":1.60, "009 100 kWh (el)":2.10
  }
};

// ── Bränsleläge ───────────────────────────────────────
function bcSetFuelMode(mode) {
  var wasElectric = bcIsElectric;
  bcIsElectric = mode === 'electric';
  bcIsDiesel   = mode === 'diesel';

  // Aktivera rätt badge
  var badges = document.querySelectorAll('.bc-fuel-badge');
  for (var i = 0; i < badges.length; i++) badges[i].classList.remove('active');
  var active = document.querySelectorAll('.bc-fuel-badge.' + mode);
  for (var j = 0; j < active.length; j++) active[j].classList.add('active');

  var consUnit    = document.getElementById('bc-consUnit');
  var consHint    = document.getElementById('bc-consHint');
  var priceLabel  = document.getElementById('bc-priceLabel');
  var priceUnit   = document.getElementById('bc-priceUnit');
  var priceHint   = document.getElementById('bc-priceHint');
  var card3Header = document.getElementById('bc-card3Header');
  var fLabel1     = document.getElementById('bc-fLabel1');
  var fExpr1      = document.getElementById('bc-fExpr1');
  var fExpr2      = document.getElementById('bc-fExpr2');
  var rLitersLbl  = document.getElementById('bc-rLitersLabel');
  var rLitersUnit = document.getElementById('bc-rLitersUnit');
  var priceInput  = document.getElementById('bc-price');

  var priceBtn = document.getElementById('bc-priceBtn');
  var priceBtnLbl = document.getElementById('bc-priceBtnLabel');

  // Pris i SEK/l och SEK/kWh är inte utbytbara — rensa vid byte el ↔ fossilt
  if (priceInput && wasElectric !== bcIsElectric) priceInput.value = '';

  if (bcIsElectric) {
    if (consUnit)    consUnit.textContent    = 'kWh/mil';
    if (consHint)    consHint.textContent    = 'En elbil drar i genomsnitt 1,5 till 2,0 kWh per mil. Ange din bils förbrukning i kWh/mil.';
    if (priceLabel)  priceLabel.textContent  = 'Pris per kWh';
    if (priceUnit)   priceUnit.textContent   = 'SEK/kWh';
    if (priceHint)   priceHint.textContent   = 'Hemmaladdning ca 1–2 SEK/kWh · Snabbladdning ca 3–6 SEK/kWh';
    if (card3Header) card3Header.textContent = 'Laddningspris';
    if (fLabel1)     fLabel1.textContent     = 'kWh åtgång';
    if (fExpr1)      fExpr1.textContent      = 'mil × kWh/mil';
    if (fExpr2)      fExpr2.textContent      = 'kWh × SEK/kWh';
    if (rLitersLbl)  rLitersLbl.textContent  = 'Energiåtgång';
    if (rLitersUnit) rLitersUnit.textContent = 'kWh';
    if (priceInput)  priceInput.placeholder  = 't.ex. 2.50';
    if (priceBtn)    priceBtn.style.display  = '';
    if (priceBtnLbl) priceBtnLbl.textContent = 'Hämta elpris';
    // Hämta spotpris om fältet är tomt
    if (priceInput && !priceInput.value) setTimeout(bcFetchElPrice, 0);
  } else if (bcIsDiesel) {
    if (consUnit)    consUnit.textContent    = 'l/10km';
    if (consHint)    consHint.innerHTML      = 'Dieselförbrukning per 10 km. Kompakt ca 0,5 &bull; mellanklass ca 0,55–0,65 &bull; SUV ca 0,65–0,80';
    if (priceLabel)  priceLabel.textContent  = 'Pris per liter (diesel)';
    if (priceUnit)   priceUnit.textContent   = 'SEK/l';
    if (card3Header) card3Header.textContent = 'Dieselpris';
    if (fLabel1)     fLabel1.textContent     = 'Liter åtgång';
    if (fExpr1)      fExpr1.textContent      = 'mil × l/10km';
    if (fExpr2)      fExpr2.textContent      = 'liter × SEK/liter';
    if (rLitersLbl)  rLitersLbl.textContent  = 'Dieselåtgång';
    if (rLitersUnit) rLitersUnit.textContent = 'liter';
    if (priceInput)  priceInput.placeholder  = 't.ex. 18.50';
    if (priceBtn)    priceBtn.style.display  = '';
    if (priceBtnLbl) priceBtnLbl.textContent = 'Hämta pris';
    // Hämta dieselpris om fältet är tomt
    var pEl = document.getElementById('bc-price');
    if (pEl && !pEl.value) setTimeout(bcFetchFuelPrice, 0);
  } else {
    if (consUnit)    consUnit.textContent    = 'l/10km';
    if (consHint)    consHint.innerHTML      = 'Fylls i automatiskt vid fordonsval &mdash; kan justeras manuellt. Liten bil ca 0,5–0,6 &bull; mellanklass ca 0,7–0,9 &bull; SUV ca 0,9–1,2';
    if (priceLabel)  priceLabel.textContent  = 'Pris per liter';
    if (priceUnit)   priceUnit.textContent   = 'SEK/l';
    if (card3Header) card3Header.textContent = 'Bränslepris';
    if (fLabel1)     fLabel1.textContent     = 'Liter åtgång';
    if (fExpr1)      fExpr1.textContent      = 'mil × l/10km';
    if (fExpr2)      fExpr2.textContent      = 'liter × SEK/liter';
    if (rLitersLbl)  rLitersLbl.textContent  = 'Bränsleåtgång';
    if (rLitersUnit) rLitersUnit.textContent = 'liter';
    if (priceInput)  priceInput.placeholder  = 't.ex. 19.50';
    if (priceBtn)    priceBtn.style.display  = '';
    if (priceBtnLbl) priceBtnLbl.textContent = 'Hämta pris';
    // Hämta bensinpris om fältet är tomt (t.ex. efter byte från el)
    if (priceInput && !priceInput.value) setTimeout(bcAutoFetchFuelPrice, 0);
  }

  // Laddningsval-chips hör bara hemma i elläget
  var chargeChips = document.getElementById('bc-chargeChips');
  if (chargeChips) chargeChips.style.display = bcIsElectric ? 'flex' : 'none';
}

// ── Ladda EV-förbrukning dynamiskt från CarAdvice ────────────────
var BC_EV_CACHE_KEY = 'bc_ev_cache';
var BC_EV_CACHE_TTL = 24 * 60 * 60 * 1000; // 24 timmar

function bcLoadEvConsumption() {
  // Försök läsa från localStorage-cache först
  try {
    var cached = localStorage.getItem(BC_EV_CACHE_KEY);
    if (cached) {
      var obj = JSON.parse(cached);
      if (Date.now() - obj.ts < BC_EV_CACHE_TTL) {
        bcApplyEvData(obj.data);
        return;
      }
    }
  } catch(e) {}

  fetch('https://caradvice.onrender.com/api/ev-consumption')
    .then(function(r) { return r.json(); })
    .then(function(list) {
      try { localStorage.setItem(BC_EV_CACHE_KEY, JSON.stringify({ ts: Date.now(), data: list })); } catch(e) {}
      bcApplyEvData(list);
    })
    .catch(function() { /* API otillgänglig — statisk DB räcker */ });
}

function bcApplyEvData(list) {
  var BRAND_MAP = {
    'Abarth':'Abarth','Alfa':'Alfa Romeo','Alpine':'Alpine',
    'Audi':'Audi','BMW':'BMW','BYD':'BYD',
    'Cadillac':'Cadillac',
    'Citroën':'Citroën','Citroen':'Citroën','CitroÃ«n':'Citroën',
    'CUPRA':'Cupra','Cupra':'Cupra',
    'Dacia':'Dacia','DS':'DS','Fiat':'Fiat','Ford':'Ford',
    'Genesis':'Genesis','Honda':'Honda','Hyundai':'Hyundai',
    'Jaguar':'Jaguar','Jeep':'Jeep','Kia':'Kia',
    'Lancia':'Lancia','Lexus':'Lexus','Lotus':'Lotus','Lucid':'Lucid',
    'Maserati':'Maserati','Mazda':'Mazda',
    'Mercedes-Benz':'Mercedes-Benz','Mercedes':'Mercedes-Benz',
    'MG':'MG','MG4':'MG','MG5':'MG',
    'MINI':'Mini','Mini':'Mini',
    'Mitsubishi':'Mitsubishi','NIO':'Nio','Nio':'Nio',
    'Nissan':'Nissan','Opel':'Opel','Peugeot':'Peugeot',
    'Polestar':'Polestar','Porsche':'Porsche','Renault':'Renault',
    'Rolls-Royce':'Rolls-Royce',
    'SEAT':'SEAT','Seat':'SEAT',
    'Skoda':'Skoda','Škoda':'Skoda','Å koda':'Skoda',
    'Smart':'Smart','Subaru':'Subaru','Suzuki':'Suzuki','Tesla':'Tesla',
    'Toyota':'Toyota','VinFast':'VinFast',
    'Volkswagen':'Volkswagen','VW':'Volkswagen',
    'Volvo':'Volvo','XPENG':'Xpeng','Xpeng':'Xpeng',
    'Zeekr':'Zeekr'
  };
  var PREFIX_IN_MODEL = { 'MG4':true,'MG5':true };
  var TWO_WORD = { 'Land Rover':'Land Rover','Alfa Romeo':'Alfa Romeo','Range Rover':'Land Rover','Rolls-Royce':'Rolls-Royce' };

  var added = 0;
  list.forEach(function(entry) {
    var name = (entry.carName || '').trim();
    if (!name || !entry.kwhPerMil) return;
    var parts = name.split(' ');
    var brand = null, modelParts = null;

    if (parts.length >= 2) {
      var two = parts[0] + ' ' + parts[1];
      if (TWO_WORD[two]) { brand = TWO_WORD[two]; modelParts = parts.slice(2); }
    }
    if (!brand && BRAND_MAP[parts[0]]) {
      brand = BRAND_MAP[parts[0]];
      modelParts = PREFIX_IN_MODEL[parts[0]] ? parts : parts.slice(1);
    }
    if (!brand || !modelParts || !modelParts.length) return;

    var model = modelParts.join(' ').replace(/\s*\(el\)\s*$/i, '').trim() + ' (el)';
    // MG-modeller heter "MG ..." i den statiska databasen ("MG ZS EV", "MG4 EV") —
    // normalisera API-namnen likadant så listan inte blandar "IM5..." och "MG..."
    if (brand === 'MG' && model.indexOf('MG') !== 0) model = 'MG ' + model;
    if (!BC_CAR_DB[brand]) BC_CAR_DB[brand] = {};
    if (!BC_CAR_DB[brand][model]) { BC_CAR_DB[brand][model] = entry.kwhPerMil; added++; }
  });
  if (added > 0) bcInitBrands();
}

// ── Ladda bensin/diesel/hybrid-förbrukning dynamiskt från CarAdvice ──
// Servern (ice_consumption-tabellen) är källan framåt — den statiska BC_CAR_DB
// är fallback när API:et inte svarar. Samma mönster som EV-laddningen ovan.
var BC_ICE_CACHE_KEY = 'bc_ice_cache';
var BC_ICE_CACHE_TTL = 24 * 60 * 60 * 1000; // 24 timmar

function bcLoadIceConsumption() {
  try {
    var cached = localStorage.getItem(BC_ICE_CACHE_KEY);
    if (cached) {
      var obj = JSON.parse(cached);
      if (Date.now() - obj.ts < BC_ICE_CACHE_TTL) {
        bcApplyIceData(obj.data);
        return;
      }
    }
  } catch(e) {}

  fetch('https://caradvice.onrender.com/api/ice-consumption')
    .then(function(r) { return r.json(); })
    .then(function(list) {
      try { localStorage.setItem(BC_ICE_CACHE_KEY, JSON.stringify({ ts: Date.now(), data: list })); } catch(e) {}
      bcApplyIceData(list);
    })
    .catch(function() { /* API otillgänglig — statisk DB räcker */ });
}

function bcApplyIceData(list) {
  if (!list || !list.forEach) return;
  // Märket i carName är alltid en exakt BC_CAR_DB-nyckel (datat genererades ur samma DB) —
  // matcha längsta nyckelprefix så tvåordsmärken som "Alfa Romeo" och "Land Rover" fungerar
  var brands = Object.keys(BC_CAR_DB);
  var added = 0;
  list.forEach(function(entry) {
    var name = (entry.carName || '').trim();
    if (!name || !entry.literPerMil) return;
    var brand = null;
    for (var i = 0; i < brands.length; i++) {
      if (name.indexOf(brands[i] + ' ') === 0 && (!brand || brands[i].length > brand.length)) {
        brand = brands[i];
      }
    }
    if (!brand) brand = name.split(' ')[0]; // nytt märke från servern
    var variant = name.slice(brand.length).trim();
    if (!variant) return;
    // Servern skickar drivmedel som eget fält; UI:t läser "(diesel)"-suffix ur namnet
    if (entry.fuel === 'diesel' && variant.indexOf('(diesel)') === -1) variant += ' (diesel)';
    if (!BC_CAR_DB[brand]) BC_CAR_DB[brand] = {};
    if (!BC_CAR_DB[brand][variant]) { BC_CAR_DB[brand][variant] = entry.literPerMil; added++; }
  });
  if (added > 0) bcInitBrands();
}

// ── Dropdown: märken ──────────────────────────────────
function bcInitBrands() {
  var sel = document.getElementById('bc-brand');
  if (!sel) return;
  while (sel.options.length > 1) sel.remove(1);
  Object.keys(BC_CAR_DB).sort().forEach(function(b) {
    var o = document.createElement('option');
    o.value = o.textContent = b;
    sel.appendChild(o);
  });
}

function bcOnBrandChange() {
  var brand    = document.getElementById('bc-brand').value;
  var modelSel = document.getElementById('bc-model');
  modelSel.innerHTML = '<option value="">Välj modell...</option>';
  document.getElementById('bc-cons').value = '';
  bcSetFuelMode('petrol');
  if (!brand) { modelSel.disabled = true; return; }
  Object.keys(BC_CAR_DB[brand]).sort().forEach(function(m) {
    var o = document.createElement('option');
    o.value = o.textContent = m;
    modelSel.appendChild(o);
  });
  modelSel.disabled = false;
}

function bcOnModelChange() {
  var brand = document.getElementById('bc-brand').value;
  var model = document.getElementById('bc-model').value;
  if (!brand || !model) return;
  var val   = BC_CAR_DB[brand][model];
  var field = document.getElementById('bc-cons');
  field.value = val;
  if (model.indexOf('(el)') !== -1) {
    field.placeholder = 't.ex. 1.75';
    bcSetFuelMode('electric');
  } else if (model.indexOf('(diesel)') !== -1) {
    field.placeholder = 't.ex. 0.55';
    bcSetFuelMode('diesel');
  } else {
    field.placeholder = 't.ex. 0.85';
    bcSetFuelMode('petrol');
  }
}

// ── Hämta aktuellt bränslepris ────────────────────────
var BC_FUEL_CACHE_KEY = 'bc_fuel_cache';
var BC_FUEL_CACHE_TTL = 6 * 60 * 60 * 1000; // 6 timmar

// Ungefärliga fallback-priser (uppdateras manuellt vid behov)
var BC_FUEL_FALLBACK = { bensin95: 18.90, diesel: 17.50 };

// Senaste kända stad från GPS
var bcCurrentCity = null;
var bcCurrentLat  = null;
var bcCurrentLon  = null;

// ── Glödande källbadge + effekter ─────────────────────
function bcInjectEffectStyles() {
  if (document.getElementById('bc-effect-styles')) return;
  var s = document.createElement('style');
  s.id = 'bc-effect-styles';
  s.textContent =
    '.bc-src-badge{display:inline-flex;align-items:center;gap:7px;margin-top:8px;padding:5px 13px;border-radius:999px;' +
      'font-size:0.72rem;font-weight:700;letter-spacing:0.03em;font-family:inherit;line-height:1;user-select:none}' +
    '.bc-src-badge .bc-src-dot{width:7px;height:7px;border-radius:50%;background:currentColor;' +
      'animation:bcDotPulse 1.6s ease-in-out infinite}' +
    '.bc-src-badge.live{color:#059669;border:1.5px solid rgba(16,185,129,0.4);background:rgba(16,185,129,0.07);' +
      'animation:bcGlowGreen 2.6s ease-in-out infinite}' +
    '.bc-src-badge.el{color:#7c3aed;border:1.5px solid rgba(139,92,246,0.45);background:rgba(139,92,246,0.08);' +
      'animation:bcGlowViolet 2.6s ease-in-out infinite}' +
    '.bc-src-badge.fallback{color:#b45309;border:1.5px solid rgba(251,191,36,0.45);background:rgba(251,191,36,0.08)}' +
    '.bc-src-badge.fallback .bc-src-dot{animation:none}' +
    '@keyframes bcGlowGreen{0%,100%{box-shadow:0 0 6px rgba(16,185,129,0.25)}50%{box-shadow:0 0 16px rgba(16,185,129,0.55)}}' +
    '@keyframes bcGlowViolet{0%,100%{box-shadow:0 0 6px rgba(139,92,246,0.3)}50%{box-shadow:0 0 18px rgba(139,92,246,0.6)}}' +
    '@keyframes bcDotPulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:0.45;transform:scale(0.75)}}' +
    '.bc-charge-chips{display:flex;gap:8px;margin-top:9px;flex-wrap:wrap}' +
    '.bc-charge-chip{border:1.5px solid #c7d2fe;background:#fff;color:#4f46e5;border-radius:999px;padding:7px 13px;' +
      'font-size:0.75rem;font-weight:700;cursor:pointer;font-family:inherit;line-height:1;' +
      'transition:border-color 0.2s,box-shadow 0.2s,background 0.2s,color 0.2s}' +
    '.bc-charge-chip:hover{border-color:#6366f1;box-shadow:0 0 10px rgba(99,102,241,0.3)}' +
    '.bc-charge-chip.active{background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;border-color:transparent;' +
      'box-shadow:0 2px 10px rgba(99,102,241,0.4)}' +
    '.bc-charge-link{font-size:0.75rem;font-weight:600;color:#7c3aed;text-decoration:none;align-self:center;' +
      'padding:6px 2px;transition:opacity 0.2s}' +
    '.bc-charge-link:hover{opacity:0.75;text-decoration:underline;color:#7c3aed}' +
    '.bc-compare{background:#fff;border:1.5px solid #e2e8f0;border-radius:14px;padding:16px 18px;margin-bottom:14px}' +
    '.bc-compare h4{font-size:0.72rem;font-weight:700;text-transform:uppercase;letter-spacing:0.08em;color:#6b7280;margin:0 0 6px}' +
    '.bc-cmp-row{display:flex;align-items:center;gap:10px;padding:9px 0;border-bottom:1px solid #f1f5f9;flex-wrap:wrap}' +
    '.bc-cmp-row:last-of-type{border-bottom:none}' +
    '.bc-cmp-ico{font-size:1.15rem;flex-shrink:0}' +
    '.bc-cmp-name{flex:1;min-width:140px;font-size:0.85rem;font-weight:600;color:#374151}' +
    '.bc-cmp-name small{display:block;font-weight:400;color:#9ca3af;font-size:0.72rem;margin-top:1px}' +
    '.bc-cmp-cost{font-weight:800;color:#1e2a3a;font-size:0.95rem;white-space:nowrap}' +
    '.bc-cmp-diff{font-size:0.72rem;font-weight:700;border-radius:999px;padding:4px 10px;white-space:nowrap}' +
    '.bc-cmp-diff.cheaper{color:#059669;background:rgba(16,185,129,0.10);box-shadow:0 0 8px rgba(16,185,129,0.25)}' +
    '.bc-cmp-diff.pricier{color:#dc2626;background:rgba(239,68,68,0.08)}' +
    '.bc-cmp-diff.same{color:#6b7280;background:#f3f4f6}' +
    '.bc-cmp-note{font-size:0.7rem;color:#9ca3af;margin:8px 0 0}' +
    '.bc-share-row{display:flex;justify-content:center;margin-bottom:14px}' +
    '.bc-share-btn{display:inline-flex;align-items:center;gap:7px;border:1.5px solid #c7d2fe;background:#fff;color:#4f46e5;' +
      'border-radius:999px;padding:10px 20px;font-size:0.85rem;font-weight:700;cursor:pointer;font-family:inherit;line-height:1;' +
      'transition:border-color 0.2s,box-shadow 0.2s,background 0.2s,color 0.2s}' +
    '.bc-share-btn:hover{border-color:#6366f1;box-shadow:0 0 14px rgba(99,102,241,0.35)}' +
    '.bc-share-btn.copied{background:linear-gradient(135deg,#10b981,#059669);color:#fff;border-color:transparent;' +
      'box-shadow:0 0 14px rgba(16,185,129,0.45)}' +
    '.bc-price-flash{animation:bcPriceFlash 1.1s ease}' +
    '@keyframes bcPriceFlash{0%{box-shadow:0 0 0 0 rgba(139,92,246,0)}35%{box-shadow:0 0 16px 3px rgba(139,92,246,0.45)}100%{box-shadow:0 0 0 0 rgba(139,92,246,0)}}' +
    '@media (prefers-reduced-motion:reduce){.bc-src-badge,.bc-src-badge .bc-src-dot,.bc-price-flash{animation:none!important}}';
  document.head.appendChild(s);
}

// kind: 'fuel' (grön, globalpetrolprices) · 'el' (violett, elprisetjustnu)
//     · 'fast' (bärnsten, snittpris snabbladdare)
//     · 'faststation' (bärnsten, närmaste snabbladdare) · 'fallback' (bärnsten)
function bcSetSourceBadge(kind) {
  bcInjectEffectStyles();
  var hint = document.getElementById('bc-priceHint');
  if (!hint || !hint.parentNode) return;
  var badge = document.getElementById('bc-srcBadge');
  if (!badge) {
    badge = document.createElement('span');
    badge.id = 'bc-srcBadge';
    hint.parentNode.insertBefore(badge, hint.nextSibling);
  }
  if (kind === 'el') {
    badge.className = 'bc-src-badge el';
    badge.innerHTML = '<span class="bc-src-dot"></span>LIVE · elprisetjustnu.se';
  } else if (kind === 'fuel') {
    badge.className = 'bc-src-badge live';
    badge.innerHTML = '<span class="bc-src-dot"></span>LIVE · globalpetrolprices.com';
  } else if (kind === 'fast') {
    badge.className = 'bc-src-badge fallback';
    badge.innerHTML = '<span class="bc-src-dot"></span>SNITTPRIS · snabbladdare';
  } else if (kind === 'faststation') {
    badge.className = 'bc-src-badge fallback';
    badge.innerHTML = '<span class="bc-src-dot"></span>NÄRMASTE SNABBLADDARE';
  } else {
    badge.className = 'bc-src-badge fallback';
    badge.innerHTML = '<span class="bc-src-dot"></span>RESERVPRIS · kan avvika';
  }
}

function bcFlashPrice(el) {
  bcInjectEffectStyles();
  el.classList.remove('bc-price-flash');
  void el.offsetWidth; // starta om animationen
  el.classList.add('bc-price-flash');
}

function bcFetchFuelPrice(city, lat, lon) {
  if (bcIsElectric || bcSharedApplying) return;
  var btn  = document.getElementById('bc-priceBtn');
  var lbl  = document.getElementById('bc-priceBtnLabel');
  var hint = document.getElementById('bc-priceHint');
  if (btn) { btn.disabled = true; btn.classList.add('fetching'); }
  if (lbl) lbl.textContent = 'Hämtar...';
  if (hint) { hint.textContent = 'Hämtar aktuellt pris...'; hint.className = 'bc-hint loading'; }

  // Bygg cache-nyckel per stad (eller global)
  var cacheKey = BC_FUEL_CACHE_KEY + (city ? '_' + city : '');
  var cached = null;
  try {
    var c = localStorage.getItem(cacheKey);
    if (c) {
      var obj = JSON.parse(c);
      if (Date.now() - obj.ts < BC_FUEL_CACHE_TTL) cached = obj.data;
    }
  } catch(e) {}

  function applyPrice(data) {
    var price = bcIsDiesel ? data.diesel : data.bensin95;
    var priceEl = document.getElementById('bc-price');
    if (priceEl) { priceEl.value = price.toFixed(2); bcFlashPrice(priceEl); }
    if (hint) {
      var location = data._city ? ' i ' + data._city : '';
      var src = data._source === 'fallback'
        ? 'Ungefärligt rikspris' + location + ' · kan variera lokalt'
        : 'Aktuellt pris' + location + ' · uppdaterat ' + (data.updated || 'idag');
      hint.textContent = src;
      hint.className = 'bc-hint';
      bcSetSourceBadge(data._source === 'fallback' ? 'fallback' : 'fuel');
    }
    if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
    if (lbl) lbl.textContent = 'Hämta pris';
  }

  if (cached) { applyPrice(cached); return; }

  // Bygg URL med platsparametrar om de finns
  var url = 'https://bilresa.onrender.com/api/fuel-price';
  var params = [];
  if (city) params.push('city=' + encodeURIComponent(city));
  if (lat)  params.push('lat=' + lat);
  if (lon)  params.push('lon=' + lon);
  if (params.length) url += '?' + params.join('&');

  fetch(url)
    .then(function(r) {
      if (!r.ok) throw new Error('no data');
      return r.json();
    })
    .then(function(data) {
      try { localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), data: data })); } catch(e) {}
      applyPrice(data);
    })
    .catch(function() {
      var fallback = { bensin95: BC_FUEL_FALLBACK.bensin95, diesel: BC_FUEL_FALLBACK.diesel, _source: 'fallback', _city: city || null };
      applyPrice(fallback);
    });
}

function bcAutoFetchFuelPrice(city, lat, lon) {
  if (bcIsElectric) { bcFetchElPrice(); return; }
  bcFetchFuelPrice(city || bcCurrentCity || null, lat || bcCurrentLat || null, lon || bcCurrentLon || null);
}

// ── Elpris: spotpris från elprisetjustnu.se via Bilresa-backend ──
var BC_EL_CACHE_KEY = 'bc_el_cache';
var BC_EL_CACHE_TTL = 60 * 60 * 1000; // 1 timme — spotpriset ändras varje timme

// Schablon för hemmaladdning ovanpå spotpriset (SEK/kWh inkl moms):
// energiskatt ~0,69 + överföringsavgift ~0,45 + elhandlarpåslag ~0,10
var BC_EL_SURCHARGE = 1.25;
var BC_EL_FALLBACK_TOTAL = 2.00; // används när backend inte svarar alls

// Genomsnittligt snabbladdarpris (SEK/kWh inkl moms) — sista reserv när
// Elbilsladdning-backendens /api/charging-price inte svarar; operatörerna
// tar ca 4–7 kr/kWh
var BC_EL_FAST_AVG = 4.75;

// Snabbladdarpris från Elbilsladdning-backenden: närmaste DC-station med känd
// operatör (kräver position), annars riksgenomsnitt av operatörstabellen
var BC_FAST_API = 'https://elbilsladdning.onrender.com/api/charging-price';
var BC_FAST_CACHE_KEY = 'bc_fast_cache';
var BC_FAST_CACHE_TTL = 30 * 60 * 1000; // 30 min — operatörstabellen ändras sällan

// Grov latitudmappning till elområde — gränserna går vid ungefär
// Umeå (SE1/SE2), Gävle (SE2/SE3) och norra Skåne/Kalmar (SE3/SE4)
function bcElZoneFromLat(lat) {
  if (!lat) return 'SE3';
  if (lat >= 63.6) return 'SE1';
  if (lat >= 61.0) return 'SE2';
  if (lat >= 57.0) return 'SE3';
  return 'SE4';
}

// Laddningsval-chips (🏠 Hemma / ⚡ Snabbladdare) — visas bara i elläget.
// Skapas vid första anropet, därefter växlas bara synlighet och aktiv chip.
function bcRenderChargeChips(activeKind) {
  bcInjectEffectStyles();
  var hint = document.getElementById('bc-priceHint');
  if (!hint || !hint.parentNode) return;
  var box = document.getElementById('bc-chargeChips');
  if (!box) {
    box = document.createElement('div');
    box.id = 'bc-chargeChips';
    box.className = 'bc-charge-chips';
    box.innerHTML =
      '<button type="button" class="bc-charge-chip" id="bc-chipHome">🏠 Hemmaladdning</button>' +
      '<button type="button" class="bc-charge-chip" id="bc-chipFast">⚡ Snabbladdare ~' +
        BC_EL_FAST_AVG.toFixed(2).replace('.', ',') + ' kr/kWh</button>' +
      '<a class="bc-charge-link" href="https://elitrobban.se/elbilsladdning/">Laddpriser per operatör →</a>';
    // Läggs efter källbadgen om den hunnit skapas, annars efter hinten
    var anchor = document.getElementById('bc-srcBadge') || hint;
    anchor.parentNode.insertBefore(box, anchor.nextSibling);
    document.getElementById('bc-chipHome').addEventListener('click', function() { bcSelectChargeMode('home'); });
    document.getElementById('bc-chipFast').addEventListener('click', function() { bcSelectChargeMode('fast'); });
  }
  box.style.display = bcIsElectric ? 'flex' : 'none';
  var home = document.getElementById('bc-chipHome');
  var fast = document.getElementById('bc-chipFast');
  if (home) home.classList.toggle('active', activeKind === 'home');
  if (fast) fast.classList.toggle('active', activeKind === 'fast');
}

function bcSelectChargeMode(kind) {
  if (!bcIsElectric) return;
  if (kind === 'fast') {
    bcFetchFastPrice(); // närmaste station / riksgenomsnitt, konstant som reserv
  } else {
    bcFetchElPrice(); // hämtar spotpriset och markerar hemma-chippen
  }
}

// Sätter pris, hint och badge utifrån /api/charging-price-svaret.
// data = null → backend svarade inte → konstanten BC_EL_FAST_AVG.
function bcApplyFastPrice(data) {
  var kr = (data && typeof data.priceKr === 'number' && data.priceKr > 0)
    ? data.priceKr : BC_EL_FAST_AVG;
  var priceEl = document.getElementById('bc-price');
  if (priceEl) { priceEl.value = kr.toFixed(2); bcFlashPrice(priceEl); }
  var hint = document.getElementById('bc-priceHint');
  if (hint) {
    if (data && data.source === 'nearest-station') {
      hint.textContent = 'Pris från närmaste snabbladdare: ' + (data.operator || data.station) +
        ' · ' + String(data.distanceKm).replace('.', ',') + ' km bort · verifiera i operatörens app';
    } else if (data && data.source === 'national-average') {
      hint.textContent = 'Riksgenomsnitt bland svenska laddoperatörer · använd 📍-knappen för pris nära dig';
    } else {
      hint.textContent = 'Genomsnittligt snabbladdarpris · operatörerna tar ca 4–7 kr/kWh';
    }
    hint.className = 'bc-hint';
  }
  bcSetSourceBadge(data && data.source === 'nearest-station' ? 'faststation' : 'fast');
  var fastChip = document.getElementById('bc-chipFast');
  if (fastChip) fastChip.textContent = '⚡ Snabbladdare ~' + kr.toFixed(2).replace('.', ',') + ' kr/kWh';
  bcRenderChargeChips('fast');
}

function bcFetchFastPrice() {
  if (!bcIsElectric || bcSharedApplying) return;
  var lat = bcStartLat !== null ? bcStartLat : bcCurrentLat;
  var lon = bcStartLon !== null ? bcStartLon : bcCurrentLon;
  var hasPos = lat !== null && lon !== null;
  // Position avrundad till ~1 km i cachenyckeln — små GPS-hopp ska inte spränga cachen
  var cacheKey = BC_FAST_CACHE_KEY + '_' +
    (hasPos ? lat.toFixed(2) + '_' + lon.toFixed(2) : 'riks');
  var cached = null;
  try {
    var c = localStorage.getItem(cacheKey);
    if (c) {
      var obj = JSON.parse(c);
      if (Date.now() - obj.ts < BC_FAST_CACHE_TTL) cached = obj.data;
    }
  } catch(e) {}
  if (cached) { bcApplyFastPrice(cached); return; }

  var hint = document.getElementById('bc-priceHint');
  if (hint) { hint.textContent = 'Hämtar snabbladdarpris...'; hint.className = 'bc-hint loading'; }
  bcRenderChargeChips('fast');

  fetch(BC_FAST_API + (hasPos ? '?lat=' + lat + '&lon=' + lon : ''))
    .then(function(r) {
      if (!r.ok) throw new Error('no data');
      return r.json();
    })
    .then(function(data) {
      try { localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), data: data })); } catch(e) {}
      bcApplyFastPrice(data);
    })
    .catch(function() { bcApplyFastPrice(null); });
}

function bcFetchElPrice() {
  if (!bcIsElectric || bcSharedApplying) return;
  var btn  = document.getElementById('bc-priceBtn');
  var lbl  = document.getElementById('bc-priceBtnLabel');
  var hint = document.getElementById('bc-priceHint');
  if (btn) { btn.disabled = true; btn.classList.add('fetching'); }
  if (lbl) lbl.textContent = 'Hämtar...';
  if (hint) { hint.textContent = 'Hämtar aktuellt spotpris...'; hint.className = 'bc-hint loading'; }

  var zone = bcElZoneFromLat(bcCurrentLat);
  var cacheKey = BC_EL_CACHE_KEY + '_' + zone;
  var cached = null;
  try {
    var c = localStorage.getItem(cacheKey);
    if (c) {
      var obj = JSON.parse(c);
      if (Date.now() - obj.ts < BC_EL_CACHE_TTL) cached = obj.data;
    }
  } catch(e) {}

  function applyElPrice(data) {
    // Spotpriset är exkl moms — hemmaladdningspris = spot × 1,25 + schablon
    var estimate = data._source === 'unavailable'
      ? BC_EL_FALLBACK_TOTAL
      : data.spot * 1.25 + BC_EL_SURCHARGE;
    var priceEl = document.getElementById('bc-price');
    if (priceEl) { priceEl.value = estimate.toFixed(2); bcFlashPrice(priceEl); }
    if (hint) {
      if (data._source === 'elprisetjustnu') {
        hint.textContent = 'Spotpris ' + data.zone + ' just nu ' + data.spot.toFixed(2).replace('.', ',') +
          ' kr/kWh · uppskattat hemmaladdningspris inkl moms, skatt & nätavgift';
        // Billigaste kommande priset — visas bara när det är märkbart billigare (>10 %).
        // Källan har numera 15-minintervall, så minuterna visas också.
        if (data.cheapest && data.cheapest.spot < data.spot * 0.9) {
          var ch = new Date(data.cheapest.start);
          hint.textContent += ' · 💡 billigast kl ' + ('0' + ch.getHours()).slice(-2) + ':' +
            ('0' + ch.getMinutes()).slice(-2) + ' (' +
            data.cheapest.spot.toFixed(2).replace('.', ',') + ' kr/kWh spot)';
        }
        bcSetSourceBadge('el');
      } else {
        hint.textContent = 'Ungefärligt hemmaladdningspris · kan variera med avtal och elområde';
        bcSetSourceBadge('fallback');
      }
      hint.className = 'bc-hint';
    }
    bcRenderChargeChips('home');
    if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
    if (lbl) lbl.textContent = 'Hämta elpris';
  }

  if (cached) { applyElPrice(cached); return; }

  fetch('https://bilresa.onrender.com/api/electricity-price?zone=' + zone)
    .then(function(r) {
      if (!r.ok) throw new Error('no data');
      return r.json();
    })
    .then(function(data) {
      try { localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), data: data })); } catch(e) {}
      applyElPrice(data);
    })
    .catch(function() {
      applyElPrice({ zone: zone, spot: null, _source: 'unavailable' });
    });
}

// ── Autocomplete för startpunkt ───────────────────────
var bcSuggestTimer  = null;
var bcSuggestEl     = null;

function bcInitStartAutocomplete() {
  var startEl = document.getElementById('bc-start');
  if (!startEl) return;
  var wrap = startEl.closest('.bc-input-wrap');
  if (!wrap) return;

  bcSuggestEl = document.createElement('ul');
  bcSuggestEl.className = 'bc-suggestions';
  bcSuggestEl.style.display = 'none';
  wrap.appendChild(bcSuggestEl);

  startEl.addEventListener('input', function() {
    bcStartLat = null; bcStartLon = null;
    var val = startEl.value.trim();
    clearTimeout(bcSuggestTimer);
    if (val.length < 2) { bcHideSuggestions(); return; }
    bcSuggestTimer = setTimeout(function() { bcFetchSuggestions(val); }, 320);
  });

  startEl.addEventListener('blur', function() {
    setTimeout(bcHideSuggestions, 180);
  });

  startEl.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') bcHideSuggestions();
  });
}

function bcFetchSuggestions(query) {
  fetch('https://nominatim.openstreetmap.org/search?q=' + encodeURIComponent(query) +
        '&format=json&limit=5&accept-language=sv&countrycodes=se,no,dk,fi')
    .then(function(r) { return r.json(); })
    .then(bcShowSuggestions)
    .catch(bcHideSuggestions);
}

function bcShowSuggestions(results) {
  if (!bcSuggestEl || !results) { bcHideSuggestions(); return; }
  bcSuggestEl.innerHTML = '';
  if (!results.length) { bcSuggestEl.style.display = 'none'; return; }
  results.forEach(function(item) {
    var li   = document.createElement('li');
    li.className = 'bc-suggestion-item';
    var parts = (item.display_name || '').split(',');
    var label = parts.slice(0, 3).join(',').trim();
    li.textContent = label;
    li.title = item.display_name || '';
    li.addEventListener('mousedown', function(e) {
      e.preventDefault();
      var el = document.getElementById('bc-start');
      if (el) el.value = label;
      bcStartLat = parseFloat(item.lat);
      bcStartLon = parseFloat(item.lon);
      bcHideSuggestions();
      bcAutoRoute();
    });
    bcSuggestEl.appendChild(li);
  });
  bcSuggestEl.style.display = 'block';
}

function bcHideSuggestions() {
  if (bcSuggestEl) bcSuggestEl.style.display = 'none';
}

// ── GPS ───────────────────────────────────────────────
function bcFetchGPS() {
  try {
    if (!navigator.geolocation) {
      bcSetGpsHint('⚠️ GPS stöds inte av din webbläsare. Ange startort manuellt.', 'error');
      return;
    }
    var btn = document.getElementById('bc-gpsBtn');
    if (btn) { btn.disabled = true; btn.classList.add('fetching'); }
    var lbl = document.getElementById('bc-gpsBtnLabel');
    if (lbl) lbl.textContent = 'Hämtar...';
    bcSetGpsHint('📡 Hämtar position — godkänn platsdelning om du tillfrågas...', 'loading');

    navigator.geolocation.getCurrentPosition(
      function(pos) {
        bcStartLat = pos.coords.latitude;
        bcStartLon = pos.coords.longitude;
        fetch(
          'https://nominatim.openstreetmap.org/reverse?lat=' + bcStartLat +
          '&lon=' + bcStartLon + '&format=json&accept-language=sv'
        )
        .then(function(r) { return r.json(); })
        .then(function(data) {
          var a = data.address || {};
          var road = a.road || a.pedestrian || a.footway || '';
          var num  = a.house_number ? ' ' + a.house_number : '';
          var city = a.city || a.town || a.village || a.municipality || a.county || '';
          var fullAddr = road ? (road + num + (city ? ', ' + city : '')) : city;
          var startEl = document.getElementById('bc-start');
          if (startEl) startEl.value = fullAddr;
          // Spara stad och position för priset
          bcCurrentCity = city;
          bcCurrentLat  = bcStartLat;
          bcCurrentLon  = bcStartLon;
          bcSetGpsHint('', '');
          if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
          var lbl3 = document.getElementById('bc-gpsBtnLabel');
          if (lbl3) lbl3.textContent = 'Hämta GPS';
          bcAutoRoute();
          // Hämta bensinpris automatiskt baserat på stad
          bcAutoFetchFuelPrice(city, bcStartLat, bcStartLon);
        })
        .catch(function() {
          bcSetGpsHint('⚠️ Kunde inte hämta ortnamn. Ange startort manuellt.', 'error');
          if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
          var lbl4 = document.getElementById('bc-gpsBtnLabel');
          if (lbl4) lbl4.textContent = 'Försök igen';
        });
      },
      function(err) {
        var errText = {
          1: '🔒 Åtkomst nekad. Tillåt platsdelning i webbläsarens inställningar.',
          2: '📵 Position ej tillgänglig. Kontrollera att GPS är påslaget.',
          3: '⏱ Timeout. Tryck igen eller ange startort manuellt.'
        };
        bcSetGpsHint(errText[err.code] || '❌ GPS-fel (kod ' + err.code + ').', 'error');
        if (btn) { btn.disabled = false; btn.classList.remove('fetching'); }
        var lbl2 = document.getElementById('bc-gpsBtnLabel');
        if (lbl2) lbl2.textContent = 'Försök igen';
      },
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 60000 }
    );
  } catch(e) {
    bcSetGpsHint('❌ GPS-fel: ' + e.message, 'error');
  }
}

function bcSetGpsHint(msg, type) {
  var el = document.getElementById('bc-gpsHint');
  if (!el) return;
  el.textContent = msg;
  el.className = 'bc-hint' + (type ? ' ' + type : '');
}

// ── Auto-rutt (triggas när start eller destination lämnas) ───
function bcAutoRoute() {
  var destEl  = document.getElementById('bc-dest');
  var startEl = document.getElementById('bc-start');
  if (!destEl) return;
  var destVal  = destEl.value.trim();
  var startVal = startEl ? startEl.value.trim() : '';

  if (!destVal || (!startVal && bcStartLat === null)) return;

  bcSetCalcStatus('🗺 Beräknar rutt...');
  bcRouteEndLat = null; // ny rutt på gång — gamla laddstopp får inte hänga kvar

  var startPromise = (bcStartLat !== null)
    ? Promise.resolve({ lat: bcStartLat, lon: bcStartLon })
    : bcGeocodeAddress(startVal);

  startPromise
    .then(function(start) {
      return bcGeocodeAddress(destVal)
        .then(function(end) {
          return bcFetchRoute(start.lon, start.lat, end.lon, end.lat)
            .then(function(route) {
              bcRouteStartLat = start.lat; bcRouteStartLon = start.lon;
              bcRouteEndLat   = end.lat;   bcRouteEndLon   = end.lon;
              var kmEl = document.getElementById('bc-km');
              if (kmEl) kmEl.value = parseFloat((route.distanceKm / 10).toFixed(1));
              bcShowMapRoute(
                start.lat, start.lon, end.lat, end.lon,
                route.coordinates,
                startVal || 'Start',
                destVal
              );
              bcSetCalcStatus('');
              var mapCard = document.getElementById('bc-mapCard');
              if (mapCard) mapCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            });
        });
    })
    .catch(function(e) {
      bcSetCalcStatus('⚠️ ' + e.message);
    });
}

// ── Geocoding & routing ───────────────────────────────
function bcGeocodeAddress(query) {
  return fetch('https://nominatim.openstreetmap.org/search?q=' + encodeURIComponent(query) + '&format=json&limit=1&accept-language=sv')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (!data.length) throw new Error('Hittade inte "' + query + '" — kontrollera stavningen.');
      return { lat: parseFloat(data[0].lat), lon: parseFloat(data[0].lon) };
    });
}

function bcFetchRoute(sLon, sLat, eLon, eLat) {
  return fetch('https://router.project-osrm.org/route/v1/driving/' + sLon + ',' + sLat + ';' + eLon + ',' + eLat + '?overview=full&geometries=geojson')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (data.code !== 'Ok') throw new Error('Kunde inte beräkna rutt.');
      return {
        distanceKm: data.routes[0].distance / 1000,
        coordinates: data.routes[0].geometry.coordinates
      };
    });
}

function bcShowMapRoute(sLat, sLon, eLat, eLon, routeCoords, startName, endName) {
  var card = document.getElementById('bc-mapCard');
  if (card) card.classList.add('show');

  if (!bcMap) {
    bcMap = L.map('bc-map');
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    }).addTo(bcMap);
  } else {
    if (bcRouteLayer) bcMap.removeLayer(bcRouteLayer);
    bcMap.eachLayer(function(l) { if (l instanceof L.Marker) bcMap.removeLayer(l); });
  }

  var latLngs = routeCoords.map(function(c) { return [c[1], c[0]]; });
  bcRouteLayer = L.polyline(latLngs, { color: '#6366f1', weight: 5, opacity: 0.85 }).addTo(bcMap);

  var pinA = L.divIcon({ className: '', html: '<div style="background:#059669;color:#fff;border-radius:50%;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-size:14px;box-shadow:0 2px 6px rgba(0,0,0,0.3)">A</div>', iconSize:[28,28], iconAnchor:[14,14] });
  var pinB = L.divIcon({ className: '', html: '<div style="background:#dc2626;color:#fff;border-radius:50%;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-size:14px;box-shadow:0 2px 6px rgba(0,0,0,0.3)">B</div>', iconSize:[28,28], iconAnchor:[14,14] });

  L.marker([sLat, sLon], { icon: pinA }).addTo(bcMap).bindPopup('<b>Start:</b> ' + startName);
  L.marker([eLat, eLon], { icon: pinB }).addTo(bcMap).bindPopup('<b>Destination:</b> ' + endName);

  bcMap.fitBounds(bcRouteLayer.getBounds(), { padding: [24, 24] });
}

// ── Hjälpfunktioner ───────────────────────────────────
function bcShowError(msg) {
  var el = document.getElementById('bc-error');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
}
function bcClearError() {
  var el = document.getElementById('bc-error');
  if (el) el.classList.remove('show');
}
function bcSetCalcStatus(msg) {
  var el = document.getElementById('bc-calcStatus');
  if (el) el.textContent = msg;
}
function bcFmt(n, d) {
  return n.toLocaleString('sv-SE', { minimumFractionDigits: d, maximumFractionDigits: d });
}
function bcTrace(id, text, val) {
  var el = document.getElementById(id);
  if (!el) return;
  el.innerHTML = '<span class="bc-tarrow">→</span> ' + text + ' <span class="bc-tval">' + val + '</span>';
}

// ── Count-up animation ────────────────────────────────
function bcCountUp(id, endVal, decimals) {
  var el = document.getElementById(id);
  if (!el) return;
  var startTime = null;
  var duration  = 900;
  function step(ts) {
    if (!startTime) startTime = ts;
    var p    = Math.min((ts - startTime) / duration, 1);
    var ease = 1 - Math.pow(1 - p, 3);
    el.textContent = bcFmt(endVal * ease, decimals);
    if (p < 1) requestAnimationFrame(step);
    else el.textContent = bcFmt(endVal, decimals);
  }
  requestAnimationFrame(step);
}

// ── Demo-counter (localStorage, 30 per rullande timme för icke-prenumeranter) ──────────────
/*
 * Var 3 stycken LIVSTID fram till 2026-08-22 — en vägg, inte ett demo. Talet är nu detsamma
 * som sökningarna i CarAdvice (CarController.SEARCHES_PER_HOUR), och av samma skäl: samma
 * pott för alla utan prenumeration, och kontot skapas för att prenumerera.
 *
 * Fönstret är rullande och inte livstid, för räknaren ligger i localStorage och kostar
 * ingenting per körning: själva beräkningen är ren matematik på redan cachad pris- och
 * förbrukningsdata, utan ett enda AI-anrop. En livstidsvägg tog alltså betalt för något som
 * inte kostar något — och den gick att kringgå genom att tömma webbläsarlagret.
 */
var BC_DEMO_MAX = 30;
var BC_DEMO_WINDOW_MS = 3600000;

// null = serverkollen har inte svarat ännu; true/false = verifierat svar från CarAdvice
var bcAuthValid = null;

// Sant om en berakning blockerades av demogransen — anvands for att kora om den
// automatiskt sa fort anvandaren loggat in (utan att hen behover klicka igen).
var bcWasDemoBlocked = false;

// Verifiera ca_token mot CarAdvice-backenden (samma konto som Bilrådgivningen/Elbilsladdning).
// 401/403 = ogiltig/utgången session → städa localStorage så demoläget gäller.
// Nätverksfel/5xx (t.ex. Render cold start) = fail open — token får gälla tills servern svarar.
function bcVerifyLogin() {
  var token = localStorage.getItem('ca_token');
  if (!token) { bcAuthValid = false; bcUpdateDemoUI(); return; }
  fetch('https://caradvice.onrender.com/api/auth/me', {
    headers: { 'Authorization': 'Bearer ' + token }
  }).then(function(res) {
    if (res.ok) {
      bcAuthValid = true;
      return res.json().then(function(u) {
        if (u && u.subscriptionStatus) localStorage.setItem('ca_status', u.subscriptionStatus);
      });
    }
    if (res.status === 401 || res.status === 403) {
      bcAuthValid = false;
      localStorage.removeItem('ca_token');
      localStorage.removeItem('ca_email');
      localStorage.removeItem('ca_status');
    }
  }).catch(function() {}).then(function() {
    bcUpdateDemoUI();
    // Blev anvandaren blockerad av demogransen och ar nu inloggad? Kor om berakningen.
    if (bcWasDemoBlocked && bcHasUnlimited()) { bcWasDemoBlocked = false; bcCalculate(); }
  });
}

/**
 * Obegränsad tillgång — kräver AKTIV PRENUMERATION, inte bara ett konto.
 *
 * Tidigare styrde en bcIsLoggedIn() åtkomsten, och den frågade bara om det fanns ett giltigt
 * token. Ett gratiskonto gav därför obegränsade sökningar, alltså precis det prenumerationen
 * säljer — och demot blev meningslöst för alla som orkade skapa ett konto.
 *
 * ca_status skrivs av bcVerifyLogin ur /api/auth/me och av CA_LOGIN-meddelandet. Innan
 * serverkollen svarat gäller det cachade värdet: en betalande får inte låsas ute för att
 * Render kallstartar, och en icke-betalande kan inte sätta värdet själv utan att också ha
 * ett token som servern underkänner vid nästa koll.
 *
 * body.logged-in är kvar med flit: det är sajtägarens egen genväg för att kunna testa
 * kalkylatorn utan att betala. Får sajten fler WordPress-användare blir den en lucka.
 */
function bcHasUnlimited() {
  if (document.body.classList.contains('logged-in')) return true;
  if (bcAuthValid === false) return false;
  return localStorage.getItem('ca_status') === 'active';
}
/** Tidsstämplar inom fönstret. Trasigt/gammalt värde ger tom lista — hellre släppa igenom. */
function bcDemoTimes() {
  var grans = Date.now() - BC_DEMO_WINDOW_MS;
  try {
    var raw = JSON.parse(localStorage.getItem('bc_demo_times') || '[]');
    if (!Array.isArray(raw)) return [];
    return raw.filter(function(t) { return typeof t === 'number' && t >= grans; });
  } catch (e) { return []; }
}
function bcDemoRemaining() {
  return Math.max(0, BC_DEMO_MAX - bcDemoTimes().length);
}
function bcUpdateDemoUI() {
  var banner   = document.getElementById('bc-demoBanner');
  var loginCta = document.getElementById('bc-loginCta');

  if (bcHasUnlimited()) {
    if (banner)   banner.style.display   = 'none';
    if (loginCta) loginCta.style.display = 'none';
    // Inloggad -> lyft demosparren: aktivera knappen igen och ta bort ett ev.
    // kvarvarande "du har anvant alla N demosokningar"-meddelande
    var loggedBtn = document.getElementById('bc-calcBtn');
    if (loggedBtn) loggedBtn.disabled = false;
    var errEl = document.getElementById('bc-error');
    if (errEl && errEl.textContent.indexOf('demos') !== -1) bcClearError();
    return;
  }

  if (banner) banner.style.display = 'flex';

  var rem = bcDemoRemaining();
  // Texten byggs HÄR och inte i WordPress-blocket: blocket hade "av 5" hårdkodat medan
  // BC_DEMO_MAX var 3 (alltså "3 av 5"), och sa "Logga in för obegränsad tillgång" trots att
  // det är prenumerationen som ger det. Byggd från JS behöver blocket inte klistras om —
  // vilket är hela skälet att talet kunde höjas till 30 utan att WP-sidan rörs.
  var text = document.getElementById('bc-demoText');
  if (!text && banner) {
    text = document.createElement('span');
    text.id = 'bc-demoText';
    var gammal = banner.querySelector('span:not(#bc-demoText)');
    if (gammal) banner.replaceChild(text, gammal); else banner.appendChild(text);
  }
  if (text) {
    text.innerHTML = 'Demoläge — <strong>' + rem + ' av ' + BC_DEMO_MAX + '</strong> beräkningar kvar denna timme. '
      + '<a href="#" id="bc-demoSubLink">Prenumerera</a> för obegränsad tillgång.';
    // Lyssnare i stället för inline onclick: sidans CSP tillåter inte inline-kod, och
    // attributet hade dessutom krävt tre lager av citattecken i en JS-byggd sträng.
    var lank = document.getElementById('bc-demoSubLink');
    if (lank) lank.addEventListener('click', function(ev) {
      ev.preventDefault();
      if (window.bcGuardOpenSubscribe) bcGuardOpenSubscribe();
      else window.open('https://caradvice.onrender.com/subscribe.html', '_blank', 'width=480,height=650,resizable=yes');
    });
  }
  var countEl = document.getElementById('bc-demoCount');
  if (countEl) countEl.textContent = rem;
  var ctaCountEl = document.getElementById('bc-loginCtaCount');
  if (ctaCountEl) ctaCountEl.textContent = BC_DEMO_MAX;
  var btn = document.getElementById('bc-calcBtn');
  if (rem === 0 && btn) btn.disabled = true;
}
function bcIncrementDemo() {
  var times = bcDemoTimes();
  times.push(Date.now());
  try { localStorage.setItem('bc_demo_times', JSON.stringify(times)); } catch (e) {}
  bcUpdateDemoUI();
}

// ── Beräkning ─────────────────────────────────────────
function bcCalculate() {
  bcClearError();
  bcSetCalcStatus('');
  document.getElementById('bc-results').classList.remove('show');

  // Blockera om demo-gränsen är nådd
  if (!bcHasUnlimited() && bcDemoRemaining() === 0) {
    bcShowError('Du har använt alla ' + BC_DEMO_MAX + ' demosökningar. Prenumerera för obegränsad tillgång.');
    var loginCta = document.getElementById('bc-loginCta');
    if (loginCta) loginCta.style.display = 'flex';
    bcWasDemoBlocked = true; // kom ihag sa vi kan kora om berakningen direkt efter inloggning
    return;
  }
  bcWasDemoBlocked = false;

  var dest = document.getElementById('bc-dest').value.trim();
  var cons = parseFloat(document.getElementById('bc-cons').value);
  var pris = parseFloat(document.getElementById('bc-price').value);
  var btn  = document.getElementById('bc-calcBtn');

  if (isNaN(cons) || cons <= 0) {
    if (bcIsElectric) {
      bcShowError('Ange en giltig förbrukning i kWh/mil (t.ex. 1.75). En elbil drar i genomsnitt 1,5–2,0 kWh/mil.');
    } else {
      bcShowError('Ange en giltig förbrukning i l/10km (t.ex. 0.85).');
    }
    return;
  }
  if (isNaN(pris) || pris <= 0) {
    if (bcIsElectric) {
      bcShowError('Ange ett giltigt laddningspris i SEK/kWh (t.ex. 2.50).');
    } else {
      bcShowError('Ange ett giltigt bränslepris i SEK (t.ex. 19.50).');
    }
    return;
  }

  var startVal = document.getElementById('bc-start') ? document.getElementById('bc-start').value.trim() : '';

  if (dest && (bcStartLat !== null || startVal)) {
    if (btn) { btn.disabled = true; btn.textContent = 'Beräknar rutt...'; }
    bcSetCalcStatus('🔍 Söker adresser...');
    bcRouteEndLat = null; // ny rutt på gång — gamla laddstopp får inte hänga kvar

    var startPromise2 = (bcStartLat !== null)
      ? Promise.resolve({ lat: bcStartLat, lon: bcStartLon })
      : bcGeocodeAddress(startVal);

    startPromise2
      .then(function(start) {
        bcSetCalcStatus('🗺 Ritar rutt...');
        return bcGeocodeAddress(dest)
          .then(function(end) {
            return bcFetchRoute(start.lon, start.lat, end.lon, end.lat)
              .then(function(route) {
                bcRouteStartLat = start.lat; bcRouteStartLon = start.lon;
                bcRouteEndLat   = end.lat;   bcRouteEndLon   = end.lon;
                document.getElementById('bc-km').value = parseFloat((route.distanceKm / 10).toFixed(1));
                bcShowMapRoute(start.lat, start.lon, end.lat, end.lon, route.coordinates,
                  startVal || 'Start', dest);
                bcSetCalcStatus('');
              });
          });
      })
      .catch(function(e) {
        bcSetCalcStatus('⚠️ ' + e.message + ' Använder manuellt angiven sträcka.');
      })
      .finally(function() {
        if (btn) { btn.disabled = false; btn.textContent = 'Räkna ut kostnaden'; }
        bcDoCalculate(cons, pris);
      });
  } else {
    bcDoCalculate(cons, pris);
  }
}

function bcDoCalculate(cons, pris) {
  var milOneWay = parseFloat(document.getElementById('bc-km').value);
  if (isNaN(milOneWay) || milOneWay <= 0) {
    bcShowError('Ange en körsträcka i mil, eller fyll i destination och aktivera GPS för automatisk beräkning.');
    return;
  }
  var returresaEl = document.getElementById('bc-returresa');
  var returresa = returresaEl && returresaEl.checked;
  var mil = returresa ? milOneWay * 2 : milOneWay;
  var km     = mil * 10;
  var amount = mil * cons; // liter eller kWh
  var kostnad = amount * pris;

  bcCountUp('bc-rKm',     km,      1);
  bcCountUp('bc-rMil',    mil,     2);
  bcCountUp('bc-rLiters', amount,  2);
  bcCountUp('bc-rCost',   kostnad, 2);

  if (returresa) {
    bcTrace('bc-t1', bcFmt(milOneWay,2) + ' mil × 2 (tur & retur) =', bcFmt(mil,2) + ' mil (' + bcFmt(km,1) + ' km)');
  } else {
    bcTrace('bc-t1', bcFmt(mil,2) + ' mil =', bcFmt(km,1) + ' km');
  }
  if (bcIsElectric) {
    bcTrace('bc-t2', bcFmt(mil,2) + ' mil × ' + bcFmt(cons,2) + ' kWh/mil =', bcFmt(amount,2) + ' kWh');
    bcTrace('bc-t3', bcFmt(amount,2) + ' kWh × ' + bcFmt(pris,2) + ' SEK/kWh =', bcFmt(kostnad,2) + ' SEK');
  } else {
    bcTrace('bc-t2', bcFmt(mil,2) + ' mil × ' + bcFmt(cons,2) + ' l/10km =', bcFmt(amount,2) + ' liter');
    bcTrace('bc-t3', bcFmt(amount,2) + ' l × ' + bcFmt(pris,2) + ' SEK/l =', bcFmt(kostnad,2) + ' SEK');
  }
  bcTrace('bc-t4', 'Kostnad per mil:', bcFmt(kostnad / mil, 2) + ' SEK/mil');
  bcRenderCo2(amount * bcCo2Factor());
  bcRenderComparison(mil, kostnad);
  bcRenderExtras(mil, kostnad);
  bcFetchChargeStops();
  bcRenderShareButton();
  // Håll adressfältets URL delbar — bokmärke/kopiera fungerar direkt
  try { history.replaceState(null, '', bcBuildShareUrl()); } catch(e) {}

  document.getElementById('bc-results').classList.add('show');
  document.getElementById('bc-mapCard').scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  // Räkna upp demo-counter om utloggad
  if (!bcHasUnlimited()) {
    bcIncrementDemo();
    if (bcDemoRemaining() === 0) {
      bcShowError('Du har nu använt alla ' + BC_DEMO_MAX + ' demosökningar. Prenumerera för obegränsad tillgång.');
    }
  }
}

// ── Räkna om direkt när returresa-kryssrutan ändras ──────────────
// Används när resultaten redan visas – triggar inte ny rutthämtning
// och räknar inte upp demo-countern.
function bcRefreshCalc() {
  var resultsEl = document.getElementById('bc-results');
  if (!resultsEl || !resultsEl.classList.contains('show')) return;
  var cons = parseFloat(document.getElementById('bc-cons').value);
  var pris = parseFloat(document.getElementById('bc-price').value);
  if (isNaN(cons) || cons <= 0 || isNaN(pris) || pris <= 0) return;
  var milOneWay = parseFloat(document.getElementById('bc-km').value);
  if (isNaN(milOneWay) || milOneWay <= 0) return;

  var returresaEl = document.getElementById('bc-returresa');
  var returresa   = returresaEl && returresaEl.checked;
  var mil    = returresa ? milOneWay * 2 : milOneWay;
  var km     = mil * 10;
  var amount = mil * cons;
  var kostnad = amount * pris;

  bcCountUp('bc-rKm',     km,      1);
  bcCountUp('bc-rMil',    mil,     2);
  bcCountUp('bc-rLiters', amount,  2);
  bcCountUp('bc-rCost',   kostnad, 2);

  if (returresa) {
    bcTrace('bc-t1', bcFmt(milOneWay,2) + ' mil × 2 (tur & retur) =', bcFmt(mil,2) + ' mil (' + bcFmt(km,1) + ' km)');
  } else {
    bcTrace('bc-t1', bcFmt(mil,2) + ' mil =', bcFmt(km,1) + ' km');
  }
  if (bcIsElectric) {
    bcTrace('bc-t2', bcFmt(mil,2) + ' mil × ' + bcFmt(cons,2) + ' kWh/mil =', bcFmt(amount,2) + ' kWh');
    bcTrace('bc-t3', bcFmt(amount,2) + ' kWh × ' + bcFmt(pris,2) + ' SEK/kWh =', bcFmt(kostnad,2) + ' SEK');
  } else {
    bcTrace('bc-t2', bcFmt(mil,2) + ' mil × ' + bcFmt(cons,2) + ' l/10km =', bcFmt(amount,2) + ' liter');
    bcTrace('bc-t3', bcFmt(amount,2) + ' l × ' + bcFmt(pris,2) + ' SEK/l =', bcFmt(kostnad,2) + ' SEK');
  }
  bcTrace('bc-t4', 'Kostnad per mil:', bcFmt(kostnad / mil, 2) + ' SEK/mil');
  bcRenderCo2(amount * bcCo2Factor());
  bcRenderComparison(mil, kostnad);
  bcRenderExtras(mil, kostnad);
}

// ── CO₂-utsläpp per resa ─────────────────────────────────────────
// Bensin/diesel: kg CO₂ per liter vid förbränning (Trafikverkets schabloner).
// El: kg CO₂ per kWh med svensk produktionsmix — inte livscykelvärden.
var BC_CO2 = { petrol: 2.36, diesel: 2.68, electric: 0.04 };

function bcCo2Factor() {
  return bcIsElectric ? BC_CO2.electric : bcIsDiesel ? BC_CO2.diesel : BC_CO2.petrol;
}

// Injicerar en CO₂-ruta i resultatgriden (före totalkostnadskortet)
function bcRenderCo2(co2kg) {
  var grid = document.querySelector('#bc-results .bc-res-grid');
  if (!grid) return;
  var item = document.getElementById('bc-co2Item');
  if (!item) {
    item = document.createElement('div');
    item.className = 'bc-res-item';
    item.id = 'bc-co2Item';
    item.innerHTML = '<div class="bc-rlabel">CO₂-utsläpp</div>' +
      '<div class="bc-rvalue" id="bc-rCo2">—</div>' +
      '<div class="bc-runit" id="bc-rCo2Unit">kg CO₂</div>';
    grid.insertBefore(item, grid.querySelector('.bc-big'));
  }
  var unitEl = document.getElementById('bc-rCo2Unit');
  if (unitEl) unitEl.textContent = bcIsElectric ? 'kg CO₂ · svensk elmix' : 'kg CO₂ · vid förbränning';
  bcCountUp('bc-rCo2', co2kg, co2kg < 10 ? 2 : 1);
}

// ── Extrarader: milersättning, samåkning och pendlingskalkyl ─────
var BC_MILERSATTNING = 25;  // kr/mil — Skatteverkets skattefria schablon för egen bil
var BC_WORKDAYS = 220;      // arbetsdagar/år i pendlingskalkylen
var bcPersons = 1, bcCommute = false, bcLastMil = 0, bcLastCost = 0;

function bcInjectExtrasStyles() {
  if (document.getElementById('bc-extras-styles')) return;
  var s = document.createElement('style');
  s.id = 'bc-extras-styles';
  s.textContent =
    '.bc-extras{margin-top:14px;padding:12px 16px;background:rgba(99,102,241,.06);' +
      'border:1px solid rgba(99,102,241,.18);border-radius:12px;font-size:.88rem;line-height:1.7}' +
    '.bc-extra-row{padding:3px 0}' +
    '.bc-extra-good{color:#059669;font-weight:600}' +
    '.bc-extra-bad{color:#d97706;font-weight:600}' +
    '.bc-pers-chip{display:inline-block;min-width:30px;padding:3px 8px;margin:0 3px;border-radius:8px;' +
      'border:1px solid rgba(99,102,241,.35);background:transparent;cursor:pointer;font:inherit}' +
    '.bc-pers-chip.active{background:#6366f1;color:#fff;border-color:#6366f1;font-weight:700}' +
    '.bc-stops{margin-top:14px;padding:12px 16px;background:rgba(245,158,11,.07);' +
      'border:1px solid rgba(245,158,11,.25);border-radius:12px;font-size:.88rem;line-height:1.7}' +
    '.bc-stops-title{font-weight:700;margin-bottom:4px}' +
    '.bc-stops-note{margin-top:6px;font-size:.76rem;opacity:.65}';
  document.head.appendChild(s);
}

function bcEsc(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function bcRenderExtras(mil, kostnad) {
  bcInjectExtrasStyles();
  bcLastMil = mil;
  bcLastCost = kostnad;
  var anchor = document.getElementById('bc-compare') || document.querySelector('#bc-results .bc-trace');
  if (!anchor) return;
  var box = document.getElementById('bc-extras');
  if (!box) {
    box = document.createElement('div');
    box.id = 'bc-extras';
    box.className = 'bc-extras';
    box.innerHTML =
      '<div class="bc-extra-row" id="bc-milersRow"></div>' +
      '<div class="bc-extra-row">👥 Dela kostnaden: <span id="bc-persChips"></span> <span id="bc-perPerson"></span></div>' +
      '<div class="bc-extra-row"><label style="cursor:pointer"><input type="checkbox" id="bc-commuteChk"> ' +
        '🔁 Pendlingsresa — visa årskostnad</label> <span id="bc-annualCost"></span></div>';
    anchor.insertAdjacentElement('afterend', box);
    var chips = document.getElementById('bc-persChips');
    for (var i = 1; i <= 5; i++) {
      (function(n) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'bc-pers-chip';
        b.id = 'bc-pers' + n;
        b.textContent = n;
        b.addEventListener('click', function() { bcPersons = n; bcUpdateExtras(); });
        chips.appendChild(b);
      })(i);
    }
    document.getElementById('bc-commuteChk').addEventListener('change', function() {
      bcCommute = !!this.checked;
      bcUpdateExtras();
    });
  }
  // Synka state (t.ex. från delad länk) till kontrollerna
  var chk = document.getElementById('bc-commuteChk');
  if (chk) chk.checked = bcCommute;
  bcUpdateExtras();
}

function bcUpdateExtras() {
  var milers = document.getElementById('bc-milersRow');
  if (milers) {
    var ers  = bcLastMil * BC_MILERSATTNING;
    var diff = ers - bcLastCost;
    milers.innerHTML = '🧾 Skatteverkets milersättning (' + BC_MILERSATTNING + ' kr/mil skattefritt): <strong>' +
      bcFmt(ers, 0) + ' kr</strong> — ' +
      (diff >= 0
        ? '<span class="bc-extra-good">täcker bränslekostnaden med ' + bcFmt(diff, 0) + ' kr marginal</span>'
        : '<span class="bc-extra-bad">täcker inte bränslekostnaden, ' + bcFmt(-diff, 0) + ' kr saknas</span>');
  }
  for (var i = 1; i <= 5; i++) {
    var c = document.getElementById('bc-pers' + i);
    if (c) c.classList.toggle('active', i === bcPersons);
  }
  var pp = document.getElementById('bc-perPerson');
  if (pp) pp.innerHTML = bcPersons > 1
    ? '→ <strong>' + bcFmt(bcLastCost / bcPersons, 2) + ' kr/person</strong>'
    : '';
  var annual = document.getElementById('bc-annualCost');
  if (annual) {
    if (bcCommute) {
      var retur = document.getElementById('bc-returresa');
      // Utan retur-kryss antas pendlaren ändå köra hem — dubbla dagskostnaden
      var dagskostnad = (retur && retur.checked) ? bcLastCost : bcLastCost * 2;
      annual.innerHTML = '→ <strong>' + bcFmt(dagskostnad * BC_WORKDAYS, 0) + ' kr/år</strong> (' +
        BC_WORKDAYS + ' dagar tur & retur)';
    } else {
      annual.innerHTML = '';
    }
  }
}

// ── Laddstopp längs rutten (elläge) ──────────────────────────────
// Elbilsladdning-backendens ruttplanering: generisk elbil med 40 mil räckvidd
// (75 % utnyttjas per etapp), bästa station per stopp från Open Charge Map.
var BC_ROUTE_API = 'https://elbilsladdning.onrender.com/api/route-stations';
var BC_STOPS_RANGE_KM = 400;
var bcStopsCache = {}; // per rutt i minnet — varje anrop kostar OCM-sökningar

function bcFetchChargeStops() {
  var box = document.getElementById('bc-chargeStops');
  var destEl = document.getElementById('bc-dest');
  var milOneWay = parseFloat(document.getElementById('bc-km') ? document.getElementById('bc-km').value : '');
  // Bara elläge + känd rutt + resa lång nog att ens kunna kräva stopp (hopp = 300 km)
  if (!bcIsElectric || bcRouteStartLat === null || bcRouteEndLat === null ||
      !destEl || !destEl.value.trim() || isNaN(milOneWay) || milOneWay * 10 < 250) {
    if (box) box.style.display = 'none';
    return;
  }
  var key = [bcRouteStartLat.toFixed(2), bcRouteStartLon.toFixed(2),
             bcRouteEndLat.toFixed(2), bcRouteEndLon.toFixed(2)].join('_');
  if (bcStopsCache[key]) { bcRenderChargeStops(bcStopsCache[key]); return; }
  fetch(BC_ROUTE_API + '?startLat=' + bcRouteStartLat + '&startLon=' + bcRouteStartLon +
        '&endLat=' + bcRouteEndLat + '&endLon=' + bcRouteEndLon + '&rangeKm=' + BC_STOPS_RANGE_KM)
    .then(function(r) {
      if (!r.ok) throw new Error('no data');
      return r.json();
    })
    .then(function(data) {
      bcStopsCache[key] = data;
      bcRenderChargeStops(data);
    })
    .catch(function() { if (box) box.style.display = 'none'; });
}

function bcRenderChargeStops(data) {
  bcInjectExtrasStyles();
  var results = document.getElementById('bc-results');
  if (!results) return;
  var box = document.getElementById('bc-chargeStops');
  if (!box) {
    box = document.createElement('div');
    box.id = 'bc-chargeStops';
    box.className = 'bc-stops';
    // Direkt under kartan — stoppen hör visuellt ihop med rutten
    var anchor = document.getElementById('bc-mapCard') ||
                 document.getElementById('bc-extras') ||
                 document.getElementById('bc-compare') ||
                 document.querySelector('#bc-results .bc-trace');
    if (!anchor) return;
    anchor.insertAdjacentElement('afterend', box);
  }
  if (!data || !data.stopsNeeded || !data.stops || !data.stops.length) {
    box.style.display = 'none';
    return;
  }
  var html = '<div class="bc-stops-title">🔌 Laddstopp längs rutten</div>';
  data.stops.forEach(function(s) {
    if (!s.station) return;
    var st = s.station;
    html += '<div>Stopp ' + s.order + ' · ' + Math.round(s.distanceFromStartKm) + ' km från start · <strong>' +
      bcEsc(st.name) + '</strong> · ' + Math.round(st.maxEffKw) + ' kW' +
      (st.chargepricePerKwh ? ' · ' + bcEsc(st.chargepricePerKwh) : '') + '</div>';
  });
  html += '<div class="bc-stops-note">Beräknat på 40 mil räckvidd · stationer från Open Charge Map · priser ungefärliga — verifiera i operatörens app</div>';
  box.innerHTML = html;
  box.style.display = 'block';
  // ⚡-markörer på kartan för varje stopp
  if (bcMap && typeof L !== 'undefined') {
    bcStopMarkers.forEach(function(m) { bcMap.removeLayer(m); });
    bcStopMarkers = [];
    data.stops.forEach(function(s) {
      if (!s.station) return;
      var icon = L.divIcon({ className: '', html:
        '<div style="background:#f59e0b;color:#fff;border-radius:50%;width:26px;height:26px;display:flex;' +
        'align-items:center;justify-content:center;font-size:13px;box-shadow:0 2px 6px rgba(0,0,0,0.3)">⚡</div>',
        iconSize: [26, 26], iconAnchor: [13, 13] });
      var m = L.marker([s.station.lat, s.station.lon], { icon: icon }).addTo(bcMap)
        .bindPopup('<b>' + bcEsc(s.station.name) + '</b><br>' + Math.round(s.station.maxEffKw) + ' kW' +
          (s.station.chargepricePerKwh ? ' · ' + bcEsc(s.station.chargepricePerKwh) : ''));
      bcStopMarkers.push(m);
    });
  }
}

// ── Delbara länkar: beräkningen kodas i URL-hashen (#bc=...) ─────
// Hashen skickas aldrig till servern och stör därmed inte WordPress-cachen.
var bcSharedApplying = false; // stoppar auto-prishämtningar från att skriva över delade värden

function bcBuildShareUrl() {
  var p = new URLSearchParams();
  function add(key, id) {
    var el = document.getElementById(id);
    if (el && el.value && el.value.trim()) p.set(key, el.value.trim());
  }
  add('start', 'bc-start');
  add('dest',  'bc-dest');
  add('mil',   'bc-km');
  p.set('mode', bcIsElectric ? 'el' : bcIsDiesel ? 'diesel' : 'bensin');
  add('cons', 'bc-cons');
  add('pris', 'bc-price');
  var retur = document.getElementById('bc-returresa');
  if (retur && retur.checked) p.set('retur', '1');
  if (bcPersons > 1) p.set('pers', String(bcPersons));
  if (bcCommute) p.set('pendla', '1');
  return location.origin + location.pathname + location.search + '#bc=' + p.toString();
}

function bcApplySharedLink() {
  if (location.hash.indexOf('#bc=') !== 0) return false;
  var p;
  try { p = new URLSearchParams(location.hash.slice(4)); } catch(e) { return false; }
  bcSharedApplying = true;
  var modeMap = { el: 'electric', diesel: 'diesel', bensin: 'petrol' };
  bcSetFuelMode(modeMap[p.get('mode')] || 'petrol');
  function setVal(id, v) { var el = document.getElementById(id); if (el && v) el.value = v; }
  setVal('bc-start', p.get('start'));
  setVal('bc-dest',  p.get('dest'));
  setVal('bc-km',    p.get('mil'));
  setVal('bc-cons',  p.get('cons'));
  setVal('bc-price', p.get('pris'));
  var retur = document.getElementById('bc-returresa');
  if (retur) retur.checked = p.get('retur') === '1';
  var pers = parseInt(p.get('pers'), 10);
  bcPersons = (pers >= 1 && pers <= 5) ? pers : 1;
  bcCommute = p.get('pendla') === '1';
  // Kör beräkningen så den delade länken visar resultatet direkt.
  // Flaggan släpps först här så modbytets schemalagda prishämtning hunnit no-op:a.
  setTimeout(function() { bcSharedApplying = false; bcCalculate(); }, 300);
  return true;
}

function bcRenderShareButton() {
  bcInjectEffectStyles();
  var anchor = document.getElementById('bc-compare') || document.querySelector('#bc-results .bc-trace');
  if (!anchor) return;
  var row = document.getElementById('bc-shareRow');
  if (!row) {
    row = document.createElement('div');
    row.id = 'bc-shareRow';
    row.className = 'bc-share-row';
    row.innerHTML = '<button type="button" class="bc-share-btn" id="bc-shareBtn">🔗 Dela beräkningen</button>';
    anchor.insertAdjacentElement('afterend', row);
    document.getElementById('bc-shareBtn').addEventListener('click', bcShareCalculation);
  }
}

function bcShareCalculation() {
  var url = bcBuildShareUrl();
  try { history.replaceState(null, '', url); } catch(e) {}
  var btn = document.getElementById('bc-shareBtn');
  function confirmCopied() {
    if (!btn) return;
    btn.textContent = '✓ Länk kopierad!';
    btn.classList.add('copied');
    setTimeout(function() {
      btn.innerHTML = '🔗 Dela beräkningen';
      btn.classList.remove('copied');
    }, 2200);
  }
  function copyFallback() {
    var ta = document.createElement('textarea');
    ta.value = url;
    ta.style.cssText = 'position:fixed;opacity:0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); confirmCopied(); } catch(e) {}
    ta.remove();
  }
  if (navigator.share) {
    navigator.share({ title: 'Bränslekostnadsberäkning', url: url }).catch(function() {});
  } else if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url).then(confirmCopied, copyFallback);
  } else {
    copyFallback();
  }
}

// ── Bränslejämförelse: samma resa med genomsnittsbil ─────────────
// Genomsnittsförbrukning för jämförelseraderna (svensk blandad körning)
var BC_CMP_CONS = { petrol: 0.75, diesel: 0.60, electric: 1.70 };
var bcCmpToken = 0; // skyddar mot att en långsam hämtning skriver över en nyare beräkning

// Priser utan UI-sidoeffekter — läser samma localStorage-cache som prisknapparna
function bcGetFuelPricesAsync() {
  try {
    var c = localStorage.getItem(BC_FUEL_CACHE_KEY);
    if (c) {
      var obj = JSON.parse(c);
      if (Date.now() - obj.ts < BC_FUEL_CACHE_TTL) return Promise.resolve(obj.data);
    }
  } catch(e) {}
  return fetch('https://bilresa.onrender.com/api/fuel-price')
    .then(function(r) { if (!r.ok) throw new Error('no data'); return r.json(); })
    .then(function(data) {
      try { localStorage.setItem(BC_FUEL_CACHE_KEY, JSON.stringify({ ts: Date.now(), data: data })); } catch(e) {}
      return data;
    })
    .catch(function() { return { bensin95: BC_FUEL_FALLBACK.bensin95, diesel: BC_FUEL_FALLBACK.diesel }; });
}

function bcGetElHomePriceAsync() {
  var zone = bcElZoneFromLat(bcCurrentLat);
  var cacheKey = BC_EL_CACHE_KEY + '_' + zone;
  function estimate(data) {
    return data && typeof data.spot === 'number'
      ? data.spot * 1.25 + BC_EL_SURCHARGE
      : BC_EL_FALLBACK_TOTAL;
  }
  try {
    var c = localStorage.getItem(cacheKey);
    if (c) {
      var obj = JSON.parse(c);
      if (Date.now() - obj.ts < BC_EL_CACHE_TTL) return Promise.resolve(estimate(obj.data));
    }
  } catch(e) {}
  return fetch('https://bilresa.onrender.com/api/electricity-price?zone=' + zone)
    .then(function(r) { if (!r.ok) throw new Error('no data'); return r.json(); })
    .then(function(data) {
      try { localStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), data: data })); } catch(e) {}
      return estimate(data);
    })
    .catch(function() { return BC_EL_FALLBACK_TOTAL; });
}

function bcRenderComparison(mil, kostnad) {
  bcInjectEffectStyles();
  var trace = document.querySelector('#bc-results .bc-trace');
  if (!trace) return;
  var box = document.getElementById('bc-compare');
  if (!box) {
    box = document.createElement('div');
    box.id = 'bc-compare';
    box.className = 'bc-compare';
    trace.insertAdjacentElement('afterend', box);
  }
  box.innerHTML = '<h4>Samma resa med annat drivmedel</h4><p class="bc-cmp-note">Hämtar aktuella priser…</p>';
  var token = ++bcCmpToken;

  Promise.all([bcGetFuelPricesAsync(), bcGetElHomePriceAsync()]).then(function(res) {
    if (token !== bcCmpToken) return; // en nyare beräkning äger rutan nu
    var fuel = res[0], elPris = res[1];
    var current = bcIsElectric ? 'electric' : bcIsDiesel ? 'diesel' : 'petrol';
    var alts = [
      { key: 'petrol', ico: '⛽', name: 'Bensinbil',
        sub: 'snitt ' + bcFmt(BC_CMP_CONS.petrol, 2) + ' l/10km × ' + bcFmt(fuel.bensin95, 2) + ' kr/l',
        cost: mil * BC_CMP_CONS.petrol * fuel.bensin95,
        co2: mil * BC_CMP_CONS.petrol * BC_CO2.petrol },
      { key: 'diesel', ico: '🛢️', name: 'Dieselbil',
        sub: 'snitt ' + bcFmt(BC_CMP_CONS.diesel, 2) + ' l/10km × ' + bcFmt(fuel.diesel, 2) + ' kr/l',
        cost: mil * BC_CMP_CONS.diesel * fuel.diesel,
        co2: mil * BC_CMP_CONS.diesel * BC_CO2.diesel },
      { key: 'electric', ico: '⚡', name: 'Elbil (hemmaladdning)',
        sub: 'snitt ' + bcFmt(BC_CMP_CONS.electric, 2) + ' kWh/mil × ' + bcFmt(elPris, 2) + ' kr/kWh',
        cost: mil * BC_CMP_CONS.electric * elPris,
        co2: mil * BC_CMP_CONS.electric * BC_CO2.electric }
    ].filter(function(a) { return a.key !== current; });

    var html = '<h4>Samma resa med annat drivmedel</h4>';
    alts.forEach(function(a) {
      var diff = (a.cost - kostnad) / kostnad * 100;
      var badge = Math.abs(diff) < 2
        ? '<span class="bc-cmp-diff same">ungefär samma</span>'
        : diff < 0
          ? '<span class="bc-cmp-diff cheaper">' + bcFmt(-diff, 0) + ' % billigare</span>'
          : '<span class="bc-cmp-diff pricier">+' + bcFmt(diff, 0) + ' % dyrare</span>';
      html += '<div class="bc-cmp-row"><span class="bc-cmp-ico">' + a.ico + '</span>' +
        '<span class="bc-cmp-name">' + a.name +
          '<small>' + a.sub + ' · ~' + bcFmt(a.co2, a.co2 < 10 ? 1 : 0) + ' kg CO₂</small></span>' +
        '<span class="bc-cmp-cost">' + bcFmt(a.cost, 0) + ' kr</span>' + badge + '</div>';
    });
    html += '<p class="bc-cmp-note">Jämförelsen gäller en genomsnittsbil med aktuella priser — din bils faktiska förbrukning kan avvika. ' +
      'CO₂ avser förbränning (bensin/diesel) resp. svensk elmix (el), inte livscykel.</p>';
    box.innerHTML = html;
  });
}

// ── Injicera demo-UI om det saknas i HTML-blocket ────────────────
function bcInjectDemoUI() {
  var wrap = document.querySelector('.bc-wrap');
  if (!wrap) return;

  // Demo-banner. Bygg alltid texten ur BC_DEMO_MAX (utan "av N"-total som kan bli
  // gammal) och skriv om en ev. hardkodad banner fran WordPress-HTML:en sa den inte
  // visar en foraldrad siffra — JS:et laddas fran Render, sa detta racker utan WP-paste.
  var bannerInner = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><span>Demoläge — <strong><span id="bc-demoCount">' + BC_DEMO_MAX + '</span> sökningar kvar</strong>. <a href="#" onclick="if(window.bcGuardOpenSubscribe){bcGuardOpenSubscribe();}else{window.open(\'https://caradvice.onrender.com/subscribe.html?from=bransle\',\'_blank\',\'width=480,height=650,resizable=yes\');}return false;" style="color:#92400e;font-weight:700">Logga in</a> för obegränsad tillgång.</span>';
  var existingBanner = document.getElementById('bc-demoBanner');
  if (existingBanner) {
    existingBanner.innerHTML = bannerInner; // korrigerar gammal "av 5"-text fran WP-HTML
  } else {
    var banner = document.createElement('div');
    banner.id = 'bc-demoBanner';
    banner.style.cssText = 'display:none;align-items:center;gap:10px;background:rgba(251,191,36,0.08);border:1.5px solid rgba(251,191,36,0.35);border-radius:12px;padding:12px 16px;margin-bottom:14px;font-size:0.84rem;color:#92400e;line-height:1.4;font-family:inherit';
    banner.innerHTML = bannerInner;
    wrap.insertBefore(banner, wrap.firstChild);
  }

  // Login-CTA (läggs in efter results-div). Skriv om ev. hardkodad WP-variant sa
  // onclick-URL:en (?from=bransle) och texten alltid ar den aktuella — self-healing
  // pa samma satt som bannern, sa ingen WordPress-paste kravs.
  var ctaInner = '<div style="font-size:1rem;font-weight:800;color:#fff">Vill du ha obegränsad tillgång?</div><p style="font-size:0.85rem;color:rgba(255,255,255,0.78);line-height:1.5;margin:0">Du kör i demoläge med <span id="bc-loginCtaCount">' + BC_DEMO_MAX + '</span> sökningar totalt. Logga in med ditt konto — samma inloggning som Bilrådgivningen och Elbilsladdning — för att använda kalkylatorn utan begränsning.</p><a href="#" onclick="if(window.bcGuardOpenSubscribe){bcGuardOpenSubscribe();}else{window.open(\'https://caradvice.onrender.com/subscribe.html?from=bransle\',\'_blank\',\'width=480,height=650,resizable=yes\');}return false;" style="display:inline-flex;align-items:center;gap:8px;padding:11px 20px;background:#fff;color:#1e2a3a;border-radius:10px;font-size:0.88rem;font-weight:700;text-decoration:none;align-self:flex-start">Logga in</a>';
  var existingCta = document.getElementById('bc-loginCta');
  if (existingCta) {
    existingCta.innerHTML = ctaInner;
  } else {
    var results = document.getElementById('bc-results');
    if (results) {
      var cta = document.createElement('div');
      cta.id = 'bc-loginCta';
      cta.style.cssText = 'display:none;flex-direction:column;gap:10px;margin-top:14px;background:linear-gradient(135deg,#1a3a5c,#2d1b69);border-radius:16px;padding:24px 22px;font-family:inherit';
      cta.innerHTML = ctaInner;
      results.parentNode.insertBefore(cta, results.nextSibling);
    }
  }
}

// ── Event wiring ──────────────────────────────────────
function bcWireEvents() {
  bcInitBrands();
  bcLoadEvConsumption();
  bcLoadIceConsumption();
  bcInjectDemoUI();
  bcInitStartAutocomplete();
  // Delad länk? Fyll i fälten och räkna — annars auto-hämta bensinpris
  if (!bcApplySharedLink()) {
    setTimeout(bcAutoFetchFuelPrice, 600);
  }

  // Style the page h1 title
  try {
    document.querySelectorAll('h1').forEach(function(el) {
      if (el.textContent.trim().toLowerCase().indexOf('bränslekostnad') !== -1 && !el.querySelector('.bc-page-title')) {
        el.innerHTML = '<span class="bc-page-title">' + el.textContent + '</span>';
      }
    });
  } catch(e) {}

  var gpsBtn  = document.getElementById('bc-gpsBtn');
  var calcBtn = document.getElementById('bc-calcBtn');
  var brand   = document.getElementById('bc-brand');
  var model   = document.getElementById('bc-model');
  var dest    = document.getElementById('bc-dest');
  var start   = document.getElementById('bc-start');

  var priceBtn = document.getElementById('bc-priceBtn');

  if (gpsBtn)   gpsBtn.addEventListener('click', bcFetchGPS);
  if (priceBtn) priceBtn.addEventListener('click', function() { bcAutoFetchFuelPrice(); });
  if (brand)    brand.addEventListener('change', bcOnBrandChange);
  if (model)    model.addEventListener('change', bcOnModelChange);
  if (dest)     dest.addEventListener('blur',  bcAutoRoute);
  if (start)    start.addEventListener('blur',  bcAutoRoute);
  if (start)    start.addEventListener('input', function() { bcStartLat = null; bcStartLon = null; });

  var returresaChk = document.getElementById('bc-returresa');
  if (returresaChk) returresaChk.addEventListener('change', bcRefreshCalc);

  if (calcBtn) calcBtn.addEventListener('click', function(e) {
    var r = document.createElement('span');
    r.className = 'bc-ripple';
    var rect = calcBtn.getBoundingClientRect();
    r.style.left = (e.clientX - rect.left) + 'px';
    r.style.top  = (e.clientY - rect.top)  + 'px';
    calcBtn.appendChild(r);
    setTimeout(function() { r.remove(); }, 700);
    bcCalculate();
  });

  // Event delegation som backup
  document.addEventListener('click', function(e) {
    var el = e.target;
    while (el && el !== document) {
      if (el.id === 'bc-gpsBtn')   { bcFetchGPS();  break; }
      if (el.id === 'bc-calcBtn')  { bcCalculate(); break; }
      if (el.id === 'bc-priceBtn') { bcAutoFetchFuelPrice(); break; }
      if (el.classList && el.classList.contains('bc-fuel-badge')) {
        var newMode = el.classList.contains('electric') ? 'electric'
                    : el.classList.contains('diesel')   ? 'diesel' : 'petrol';
        // l/10km och kWh/mil är olika enheter — rensa förbrukningen vid byte el ↔ fossilt
        if ((newMode === 'electric') !== bcIsElectric) {
          var consEl = document.getElementById('bc-cons');
          if (consEl) consEl.value = '';
        }
        bcSetFuelMode(newMode);
        break;
      }
      el = el.parentNode;
    }
  });
  document.addEventListener('change', function(e) {
    if (e.target.id === 'bc-brand')    bcOnBrandChange();
    if (e.target.id === 'bc-model')    bcOnModelChange();
    if (e.target.id === 'bc-returresa') bcRefreshCalc();
  });
}

// ── Uppdatera demo-UI efter inloggning via popup ──────
window.addEventListener('message', function(ev) {
  if (!ev.data || !ev.data.type) return;
  if (ev.data.type === 'CA_LOGIN' || ev.data.type === 'CA_SUBSCRIBED') {
    if (ev.data.token) localStorage.setItem('ca_token', ev.data.token);
    if (ev.data.email) localStorage.setItem('ca_email', ev.data.email);
    if (ev.data.status) localStorage.setItem('ca_status', ev.data.status);
    bcAuthValid = null;      // ny token — låt serverkollen avgöra
    bcVerifyLogin();
  }
  if (ev.data.type === 'CA_LOGOUT') {
    localStorage.removeItem('ca_token');
    localStorage.removeItem('ca_email');
    localStorage.removeItem('ca_status');
    bcAuthValid = false;
    bcUpdateDemoUI();
  }
});

// ── Starta när DOM är redo ────────────────────────────
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', function() { bcWireEvents(); bcUpdateDemoUI(); bcVerifyLogin(); });
} else {
  bcWireEvents();
  bcUpdateDemoUI();
  bcVerifyLogin();
}


// ═══════════════════════════════════════════════════════
//  BRÄNSLEKOSTNAD — uppstartssplash (fuel-tema, glas + glow)
//  Fullskärms-takeover som visas första besöket per webbläsare. Statusrader tickar
//  igenom alla datakällor kalkylatorn använder och tänds gröna en efter en, med en
//  bränslemätare (amber→grön) som progress. Auto-injiceras med resten av /bensinkostnad.js.
//  Återspela med ?splash=1 eller window.bcReplaySplash().
// ═══════════════════════════════════════════════════════
(function () {
  'use strict';

  var SEEN_KEY = 'bc_splash_seen_v1';
  var CARS_FLOOR = 800;
  var CARS_ROW = 1;
  var FORCE = /[?&]splash=1/.test(location.search);

  var ROWS = [
    { ic: '⛽', t: 'Br\xe4nslepriser',  s: 'Dagsaktuella bensin &amp; diesel', tag: 'ONLINE' },
    { ic: '🚗', t: 'Bildatabas',  kind: 'cars' },
    { ic: '⚡', t: 'Elpriser',        s: 'elprisetjustnu.se \xb7 SE1–SE4 spotpris', tag: 'LIVE' },
    { ic: '🔋', t: 'Elf\xf6rbrukning', s: 'kWh/mil f\xf6r elbilar \xb7 CarAdvice' },
    { ic: '🛢️', t: 'F\xf6rbrukning', s: 'l/mil bensin, diesel &amp; hybrid' },
    { ic: '🗺️', t: 'Ruttber\xe4kning', s: 'Verklig str\xe4cka via v\xe4gn\xe4tet' },
    { ic: '💰', t: 'Sparkalkyl',   s: 'J\xe4mf\xf6r bensin, diesel &amp; el' }
  ];

  var BOOT_PHRASES = ['l\xe4ser in br\xe4nslepriser', 'h\xe4mtar elpris SE1–SE4', 'kalibrerar f\xf6rbrukning per mil', 'r\xe4knar ut din kostnad'];

  function fmt(n) { return n.toLocaleString('sv-SE'); }
  function clampFloor(live, floorVal) { return Math.max(floorVal, Math.floor((live || 0) / 10) * 10); }

  function countCars() {
    try {
      var db = window.BC_CAR_DB || (typeof BC_CAR_DB !== 'undefined' ? BC_CAR_DB : null);
      if (!db) return 0;
      var n = 0;
      for (var k in db) { if (Object.prototype.hasOwnProperty.call(db, k)) n += Object.keys(db[k]).length; }
      return n;
    } catch (e) { return 0; }
  }

  var targetCars = clampFloor(countCars(), CARS_FLOOR);
  var animated = {};

  function carsText(e) {
    var n = Math.round(targetCars * e);
    var plus = e >= 1 ? '+' : '';
    return '<b>' + fmt(n) + plus + '</b> bilmodeller \xb7 l/mil &amp; kWh/mil';
  }

  function injectStyles() {
    if (document.getElementById('bcsp-style')) return;
    var css = document.createElement('style');
    css.id = 'bcsp-style';
    css.textContent = [
      '.bcsp{position:fixed;inset:0;z-index:99999;overflow:hidden;',
        'display:flex;flex-direction:column;align-items:center;justify-content:center;',
        'padding:30px 22px;text-align:center;',
        "font-family:'Segoe UI',system-ui,-apple-system,BlinkMacSystemFont,Roboto,sans-serif;",
        'background:radial-gradient(ellipse at 50% 0%,#2a1c08,#120c04 58%,#0a0703 100%);',
        'opacity:1;transition:opacity .5s ease;}',
      '.bcsp.bcsp-out{opacity:0;}',
      '.bcsp::before{content:"";position:absolute;inset:0;pointer-events:none;',
        'background:radial-gradient(ellipse at 76% 8%,rgba(245,158,11,.18) 0%,transparent 48%),',
          'radial-gradient(ellipse at 16% 92%,rgba(34,197,94,.14) 0%,transparent 46%);',
        'animation:bcsp-aurora 7s ease-in-out infinite alternate;}',
      '.bcsp::after{content:"";position:absolute;left:0;right:0;height:2px;top:0;pointer-events:none;',
        'background:linear-gradient(90deg,transparent,rgba(251,146,60,.7),rgba(34,197,94,.6),transparent);',
        'box-shadow:0 0 18px rgba(245,158,11,.6);animation:bcsp-scan 3.4s linear infinite;opacity:.7;}',
      '.bcsp-inner{position:relative;z-index:1;width:100%;max-width:392px;',
        'display:flex;flex-direction:column;align-items:center;}',
      // Glaskort — glöden ligger i background-lagret (inte ::before) så texten inte tvättas ur
      '.bcsp-card{position:relative;width:100%;padding:30px 22px 24px;border-radius:26px;',
        'display:flex;flex-direction:column;align-items:center;',
        'background:radial-gradient(120% 55% at 50% -8%,rgba(251,191,36,.26),transparent 68%),',
          'radial-gradient(100% 45% at 50% 108%,rgba(34,197,94,.14),transparent 70%),',
          'linear-gradient(160deg,rgba(46,34,16,.92),rgba(18,12,5,.95));',
        '-webkit-backdrop-filter:blur(24px) saturate(150%);backdrop-filter:blur(24px) saturate(150%);',
        'border:1px solid rgba(251,191,36,.22);',
        'box-shadow:0 30px 80px rgba(0,0,0,.6),inset 0 1px 0 rgba(255,255,255,.16),',
          'inset 0 0 50px rgba(245,158,11,.08),0 0 70px rgba(245,158,11,.16),0 0 120px rgba(251,146,60,.1);',
        'animation:bcsp-rise .55s ease both;}',
      // Kärna (bränsledroppe + blixt)
      '.bcsp-core{position:relative;width:96px;height:96px;margin-bottom:16px;',
        'display:flex;align-items:center;justify-content:center;animation:bcsp-rise .5s ease both;}',
      '.bcsp-ring{position:absolute;inset:0;border-radius:50%;',
        'background:conic-gradient(from 0deg,rgba(245,158,11,0),#f59e0b 16%,#fb923c 46%,#22c55e 78%,rgba(34,197,94,0));',
        '-webkit-mask:radial-gradient(farthest-side,transparent calc(100% - 4px),#000 calc(100% - 3px));',
        'mask:radial-gradient(farthest-side,transparent calc(100% - 4px),#000 calc(100% - 3px));',
        'filter:drop-shadow(0 0 8px rgba(245,158,11,.6));animation:bcsp-rot 1.5s linear infinite;}',
      '.bcsp-pulse{position:absolute;inset:6px;border-radius:50%;border:1.5px solid rgba(245,158,11,.45);',
        'animation:bcsp-pulse 2.1s ease-out infinite;}',
      '.bcsp-pulse.p2{animation-delay:1.05s;border-color:rgba(34,197,94,.4);}',
      '.bcsp-node{position:relative;width:62px;height:62px;border-radius:19px;',
        'background:linear-gradient(145deg,rgba(74,54,20,.92),rgba(58,40,14,.88));',
        'border:1px solid rgba(251,191,36,.4);',
        'display:flex;align-items:center;justify-content:center;',
        'box-shadow:0 8px 30px rgba(245,158,11,.5),0 0 34px rgba(251,146,60,.32),',
          'inset 0 1px 0 rgba(255,255,255,.22),inset 0 0 18px rgba(245,158,11,.22);}',
      '.bcsp-node svg{filter:drop-shadow(0 0 6px rgba(251,191,36,.75));}',
      '.bcsp-bolt{position:absolute;top:-5px;right:-5px;width:24px;height:24px;border-radius:50%;',
        'background:#160d03;border:1px solid rgba(34,197,94,.6);display:flex;align-items:center;justify-content:center;',
        'box-shadow:0 0 12px rgba(34,197,94,.6);animation:bcsp-boltpulse 1.5s ease-in-out infinite;}',
      '.bcsp-bolt svg{width:12px;height:12px;fill:#4ade80;filter:none;}',
      '.bcsp-title{font-size:1.35rem;font-weight:800;letter-spacing:-.4px;margin:0 0 8px;',
        'background:linear-gradient(120deg,#fff 34%,#fcd34d 100%);',
        '-webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;',
        'filter:drop-shadow(0 1px 8px rgba(245,158,11,.5));animation:bcsp-rise .5s ease .05s both;}',
      '.bcsp-chip{display:inline-flex;align-items:center;gap:5px;margin:0 0 14px;padding:3px 11px;',
        'border-radius:20px;background:rgba(245,158,11,.12);border:1px solid rgba(245,158,11,.42);',
        'font-size:.6rem;font-weight:800;letter-spacing:.14em;text-transform:uppercase;color:#fcd34d;',
        'animation:bcsp-rise .5s ease .08s both;}',
      '.bcsp-chip svg{width:11px;height:11px;fill:#fbbf24;}',
      '.bcsp-boot{font-family:ui-monospace,SFMono-Regular,"Cascadia Code",Consolas,monospace;',
        'font-size:.74rem;color:rgba(253,230,138,.85);margin:0 0 20px;min-height:1.2em;',
        'letter-spacing:.2px;animation:bcsp-rise .5s ease .1s both;}',
      '.bcsp-boot .pr{color:#4ade80;font-weight:700;margin-right:5px;}',
      '.bcsp-cur{display:inline-block;width:7px;height:.95em;background:#fbbf24;margin-left:3px;',
        'vertical-align:-1px;animation:bcsp-blink 1s steps(1) infinite;}',
      '.bcsp-rows{width:100%;display:flex;flex-direction:column;gap:7px;}',
      '.bcsp-row{display:flex;align-items:center;gap:11px;text-align:left;padding:9px 12px;border-radius:13px;',
        'background:rgba(251,191,36,.07);border:1px solid rgba(251,191,36,.18);',
        'box-shadow:inset 0 1px 0 rgba(255,255,255,.09);',
        'opacity:0;transform:translateY(8px);transition:opacity .35s ease,transform .35s ease,border-color .3s,background .3s,box-shadow .3s;}',
      '.bcsp-row.show{opacity:1;transform:translateY(0);}',
      '.bcsp-row.done{border-color:rgba(52,211,153,.5);background:rgba(34,197,94,.13);',
        'box-shadow:inset 0 1px 0 rgba(255,255,255,.12),0 0 22px rgba(34,197,94,.2);}',
      '.bcsp-ic{font-size:1.05rem;flex-shrink:0;width:22px;text-align:center;filter:drop-shadow(0 0 5px rgba(245,158,11,.4));}',
      '.bcsp-tx{flex:1;min-width:0;display:flex;flex-direction:column;line-height:1.25;}',
      '.bcsp-tx b{font-size:.83rem;font-weight:700;color:#fff6e6;display:flex;align-items:center;gap:7px;}',
      '.bcsp-tx i{font-size:.69rem;font-style:normal;color:rgba(253,230,138,.78);',
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
      '.bcsp-tx i b{color:#fcd34d;font-weight:800;font-style:normal;display:inline;}',
      '.bcsp-onl{display:inline-flex;align-items:center;gap:4px;padding:1px 7px 1px 5px;border-radius:20px;',
        'font-size:.52rem;font-weight:800;letter-spacing:.1em;text-transform:uppercase;',
        'color:#6ee7b7;background:rgba(34,197,94,.14);border:1px solid rgba(34,197,94,.4);',
        'box-shadow:0 0 10px rgba(34,197,94,.25);}',
      '.bcsp-onl.live{color:#fcd34d;background:rgba(245,158,11,.14);border-color:rgba(245,158,11,.42);box-shadow:0 0 10px rgba(245,158,11,.25);}',
      '.bcsp-onl .dot{width:5px;height:5px;border-radius:50%;background:currentColor;',
        'box-shadow:0 0 6px currentColor;animation:bcsp-onlpulse 1.4s ease-in-out infinite;}',
      '.bcsp-st{flex-shrink:0;width:20px;height:20px;display:flex;align-items:center;justify-content:center;}',
      '.bcsp-spin{width:14px;height:14px;border-radius:50%;',
        'border:2px solid rgba(245,158,11,.2);border-top-color:#fbbf24;animation:bcsp-spin .6s linear infinite;}',
      '.bcsp-check{width:19px;height:19px;border-radius:50%;background:rgba(34,197,94,.18);',
        'border:1px solid rgba(34,197,94,.55);color:#4ade80;font-size:11px;font-weight:900;',
        'display:flex;align-items:center;justify-content:center;animation:bcsp-pop .3s ease;}',
      // Bränslemätare (progress)
      '.bcsp-gauge{position:relative;width:100%;height:12px;border-radius:6px;margin-top:20px;',
        'border:1.5px solid rgba(251,191,36,.4);background:rgba(255,255,255,.05);overflow:hidden;}',
      '.bcsp-fill{height:100%;width:0;border-radius:5px;',
        'background:linear-gradient(90deg,#f59e0b,#fb923c,#22c55e);transition:width .55s ease;',
        'box-shadow:0 0 12px rgba(245,158,11,.6);position:relative;}',
      '.bcsp-fill::after{content:"";position:absolute;inset:0;',
        'background:linear-gradient(90deg,transparent,rgba(255,255,255,.45),transparent);',
        'animation:bcsp-shine 1.4s linear infinite;}',
      '.bcsp-pct{margin-top:9px;font-size:.66rem;font-weight:700;letter-spacing:.08em;',
        'color:rgba(252,211,77,.75);font-family:ui-monospace,Consolas,monospace;}',
      '.bcsp.bcsp-ready .bcsp-node{box-shadow:0 6px 30px rgba(245,158,11,.7),0 0 36px rgba(34,197,94,.55);animation:bcsp-charge .6s ease;}',
      '.bcsp.bcsp-ready .bcsp-ring{animation-duration:.5s;}',
      '.bcsp.bcsp-ready .bcsp-boot{color:#6ee7b7;}',
      '.bcsp.bcsp-ready .bcsp-boot .pr{color:#22c55e;}',
      '.bcsp.bcsp-ready .bcsp-fill{background:linear-gradient(90deg,#22c55e,#4ade80);box-shadow:0 0 16px rgba(34,197,94,.7);}',
      '.bcsp.bcsp-ready .bcsp-pct{color:#6ee7b7;}',
      '.bcsp-skip{position:absolute;top:14px;right:16px;z-index:2;background:rgba(255,255,255,.07);',
        'border:1px solid rgba(255,255,255,.14);color:rgba(255,255,255,.6);font-size:.68rem;font-weight:600;',
        'padding:4px 11px;border-radius:20px;cursor:pointer;transition:all .15s;',
        "font-family:inherit;letter-spacing:.02em;}",
      '.bcsp-skip:hover{background:rgba(255,255,255,.15);color:#fff;}',
      '@keyframes bcsp-spin{to{transform:rotate(360deg);}}',
      '@keyframes bcsp-rot{to{transform:rotate(360deg);}}',
      '@keyframes bcsp-rise{from{opacity:0;transform:translateY(10px);}to{opacity:1;transform:translateY(0);}}',
      '@keyframes bcsp-pulse{0%{transform:scale(.72);opacity:.65;}100%{transform:scale(1.35);opacity:0;}}',
      '@keyframes bcsp-boltpulse{0%,100%{box-shadow:0 0 10px rgba(34,197,94,.45);}50%{box-shadow:0 0 18px rgba(34,197,94,.9);}}',
      '@keyframes bcsp-charge{0%{transform:scale(1);}45%{transform:scale(1.15);}100%{transform:scale(1);}}',
      '@keyframes bcsp-aurora{0%{opacity:.7;}100%{opacity:1;}}',
      '@keyframes bcsp-blink{0%,100%{opacity:1;}50%{opacity:0;}}',
      '@keyframes bcsp-pop{0%{transform:scale(.4);opacity:0;}60%{transform:scale(1.15);}100%{transform:scale(1);opacity:1;}}',
      '@keyframes bcsp-scan{0%{top:-2px;opacity:0;}12%{opacity:.7;}88%{opacity:.7;}100%{top:100%;opacity:0;}}',
      '@keyframes bcsp-shine{0%{transform:translateX(-100%);}100%{transform:translateX(100%);}}',
      '@keyframes bcsp-onlpulse{0%,100%{opacity:1;transform:scale(1);}50%{opacity:.45;transform:scale(1.35);}}',
      '@media (max-width:520px){',
        '.bcsp{justify-content:flex-start;padding:34px 12px 20px;}',
        '.bcsp-card{padding:24px 15px 20px;border-radius:22px;}',
        '.bcsp-title{font-size:1.2rem;}',
        '.bcsp-core{width:78px;height:78px;margin-bottom:12px;}',
        '.bcsp-node{width:54px;height:54px;border-radius:16px;}',
        '.bcsp-chip{margin-bottom:12px;}',
        '.bcsp-boot{margin-bottom:15px;font-size:.72rem;}',
        '.bcsp-rows{gap:6px;}.bcsp-row{padding:8px 12px;gap:10px;}',
        '.bcsp-tx b{font-size:.8rem;}.bcsp-tx i{font-size:.67rem;}',
        '.bcsp-gauge{margin-top:15px;}',
      '}',
      '@media (prefers-reduced-motion:reduce){',
        '.bcsp *{animation:none!important;transition:none!important;}.bcsp::after{display:none;}}'
    ].join('');
    document.head.appendChild(css);
  }

  var DROP_SVG =
    '<svg viewBox="0 0 40 40" width="30" height="30" xmlns="http://www.w3.org/2000/svg">' +
      '<path d="M20 6 C20 6 30 18 30 26 A10 10 0 0 1 10 26 C10 18 20 6 20 6 Z" ' +
        'fill="rgba(251,191,36,0.2)" stroke="rgba(252,211,77,0.75)" stroke-width="1.6"/>' +
      '<path d="M16 25 a4 5 0 0 0 4 5" fill="none" stroke="rgba(255,255,255,0.5)" stroke-width="1.4" stroke-linecap="round"/>' +
    '</svg>';

  var BOLT_SVG =
    '<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M13 2L4.5 13.5H11L10 22L19.5 10.5H13L13 2Z"/></svg>';

  function subFor(row) {
    if (row.kind === 'cars') return 'R\xe4knar bilmodeller…';
    return row.s;
  }

  function tagHtml(tag) {
    if (!tag) return '';
    var cls = tag === 'LIVE' ? 'bcsp-onl live' : 'bcsp-onl';
    return '<span class="' + cls + '"><span class="dot"></span>' + tag + '</span>';
  }

  function rowsHtml() {
    return ROWS.map(function (r, i) {
      return '<div class="bcsp-row" data-i="' + i + '">' +
        '<span class="bcsp-ic">' + r.ic + '</span>' +
        '<span class="bcsp-tx"><b>' + r.t + tagHtml(r.tag) + '</b><i class="bcsp-suba">' + subFor(r) + '</i></span>' +
        '<span class="bcsp-st"><span class="bcsp-spin"></span></span>' +
      '</div>';
    }).join('');
  }

  function template() {
    return '' +
      '<button class="bcsp-skip" type="button" aria-label="Hoppa \xf6ver">Hoppa \xf6ver ✕</button>' +
      '<div class="bcsp-inner">' +
        '<div class="bcsp-card">' +
          '<div class="bcsp-core">' +
            '<span class="bcsp-ring"></span>' +
            '<span class="bcsp-pulse"></span><span class="bcsp-pulse p2"></span>' +
            '<span class="bcsp-node">' + DROP_SVG + '<span class="bcsp-bolt">' + BOLT_SVG + '</span></span>' +
          '</div>' +
          '<h3 class="bcsp-title">Br\xe4nslekostnad</h3>' +
          '<span class="bcsp-chip">' + BOLT_SVG + ' Dagsaktuella priser</span>' +
          '<p class="bcsp-boot"><span class="pr">▸</span><span class="bcsp-boot-tx"></span><span class="bcsp-cur"></span></p>' +
          '<div class="bcsp-rows">' + rowsHtml() + '</div>' +
          '<div class="bcsp-gauge"><div class="bcsp-fill"></div></div>' +
          '<div class="bcsp-pct">0% klart</div>' +
        '</div>' +
      '</div>';
  }

  function suba(i) { return document.querySelector('.bcsp-row[data-i="' + i + '"] .bcsp-suba'); }

  function animate(el, dur, render) {
    var start = performance.now();
    (function step(now) {
      var p = Math.min(1, (now - start) / dur);
      el.innerHTML = render(1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(step);
    })(performance.now());
  }

  function animateCars() {
    if (animated.cars) return;
    var el = suba(CARS_ROW);
    if (!el) return;
    animated.cars = true;
    animate(el, 1400, function (e) { return carsText(e); });
  }

  function startBoot(el) {
    var pi = 0, ci = 0, mode = 'type', stopped = false;
    function set(txt) { el.innerHTML = txt; }
    function tick() {
      if (stopped) return;
      var phrase = BOOT_PHRASES[pi];
      if (mode === 'type') {
        ci++; set(phrase.slice(0, ci));
        if (ci >= phrase.length) { mode = 'hold'; ci = 0; setTimeout(tick, 900); return; }
        setTimeout(tick, 40);
      } else if (mode === 'hold') {
        mode = 'erase'; ci = phrase.length; setTimeout(tick, 30);
      } else {
        ci -= 2; if (ci < 0) ci = 0; set(phrase.slice(0, ci));
        if (ci <= 0) { pi = (pi + 1) % BOOT_PHRASES.length; mode = 'type'; }
        setTimeout(tick, 22);
      }
    }
    tick();
    return { stop: function (finalTxt) { stopped = true; set(finalTxt); } };
  }

  function markSeen() { try { localStorage.setItem(SEEN_KEY, '1'); } catch (e) {} }
  function setPct(el, p) { if (el) el.textContent = Math.round(p) + '% klart'; }

  function run() {
    if (document.querySelector('.bcsp')) return;
    injectStyles();

    var overlay = document.createElement('div');
    overlay.className = 'bcsp';
    overlay.innerHTML = template();
    document.body.appendChild(overlay);
    var prevOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';

    var fill    = overlay.querySelector('.bcsp-fill');
    var pctEl   = overlay.querySelector('.bcsp-pct');
    var bootTx  = overlay.querySelector('.bcsp-boot-tx');
    var cursor  = overlay.querySelector('.bcsp-cur');
    var rows    = overlay.querySelectorAll('.bcsp-row');
    var timers  = [];
    var finished = false;
    var reduce  = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var boot    = reduce ? null : startBoot(bootTx);

    function finish() {
      if (finished) return;
      finished = true;
      timers.forEach(clearTimeout);
      overlay.classList.add('bcsp-ready');
      if (boot) boot.stop('klart — din kostnad ber\xe4knas ✓');
      else if (bootTx) bootTx.textContent = 'klart — din kostnad ber\xe4knas ✓';
      if (cursor) cursor.style.display = 'none';
      if (fill) fill.style.width = '100%';
      setPct(pctEl, 100);
      document.documentElement.style.overflow = prevOverflow;
      timers.push(setTimeout(function () {
        overlay.classList.add('bcsp-out');
        setTimeout(function () { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }, 540);
      }, 800));
      markSeen();
    }

    overlay.querySelector('.bcsp-skip').addEventListener('click', finish);

    if (reduce) {
      if (bootTx) bootTx.textContent = 'redo — din kostnad ber\xe4knas';
      if (cursor) cursor.style.display = 'none';
      rows.forEach(function (row) {
        row.classList.add('show', 'done');
        row.querySelector('.bcsp-st').innerHTML = '<span class="bcsp-check">✓</span>';
      });
      animated.cars = true;
      var cEl = suba(CARS_ROW); if (cEl) cEl.innerHTML = carsText(1);
      if (fill) fill.style.width = '100%';
      setPct(pctEl, 100);
      timers.push(setTimeout(finish, 2200));
      return;
    }

    var START = 400, STAGGER = 480, FLIP = 340;
    rows.forEach(function (row, i) {
      var appear = START + i * STAGGER;
      timers.push(setTimeout(function () {
        row.classList.add('show');
        if (i === CARS_ROW) animateCars();
      }, appear));
      timers.push(setTimeout(function () {
        row.classList.add('done');
        row.querySelector('.bcsp-st').innerHTML = '<span class="bcsp-check">✓</span>';
        var pct = (i + 1) / rows.length * 100;
        if (fill) fill.style.width = Math.round(pct) + '%';
        setPct(pctEl, pct);
        if (i === rows.length - 1) timers.push(setTimeout(finish, 500));
      }, appear + FLIP));
    });
  }

  window.bcReplaySplash = function () {
    var o = document.querySelector('.bcsp');
    if (o && o.parentNode) o.parentNode.removeChild(o);
    animated = {};
    run();
  };

  function shouldShow() {
    if (FORCE) return true;
    try { return !localStorage.getItem(SEEN_KEY); } catch (e) { return true; }
  }

  function boot() { if (shouldShow()) run(); }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
