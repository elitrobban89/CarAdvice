# Morgonfix-logg

## 2026-09-28

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:29:42, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (1e889b5), status OK, Groq
3/3 modeller, vPIC 291 kontrollerade / 0 avvikelser (9:e natten i rad utan avvikelse),
kontrollräkningen (cargo total 1725 + evSpecs 599 + ice_consumption 960 = 3284 = variants)
stämmer, drivmedelsräknaren 482/397/85/16 (ingen flip), kategorivakten tyst (0/0) sedan
senaste omstarten (uptime ~11,3 h). cargo-specs-jobbet gav 0/0/0 (nya bilnamn/bagagevolymer/
generationsår) och bade medVolym (991) och bagageMissar (778) star exakt still mot
baslinjen — men 3f:s domregel undantar det: bagagesvepets 150/natt-budget användes sist
09-26, alla 778 parkerade missar är ej-hittad (ingen Volvo XC60/Golf i listan) och ingen
är eligible för omprövning förrän 30-dagarsfönstret öppnar i slutet av oktober, så
stillestånd är DET NORMALA (samma läge som generationsåren). Dagens dom blev KOLLA — en
kodlucka i kategorivakten (samma mönster som Kia Ceed 09-27) plus två kommande-rader som
verkade felaktigt parkerade.

**Fix 1 — `born` och `focus` saknades i `InsightTaxonomy.STORA_MODELLER`**
(`src/main/java/com/caradvice/model/InsightTaxonomy.java`, samma lista som fick `ceed`
09-27).

- **Vad nattrapporten visade:** id 1665-1669 (Teknikens Värld, Cupra Born) och id 1671
  (Auto Motor & Sport, Ford Focus) kom in som `smaabil`. Cupra Born delar VW ID.3:s
  MEB-plattform (samma storleksklass, ~4,3 m) — id.3 står redan i listan — och Ford Focus
  är C-segment, samma hylla som ceed/octavia. `/api/admin/kategorivakten` visade
  `totalt:0` för natten (uptime ~11,3 h, täcker hela kedjan), vilket bekräftar att vakten
  inte känner till någon av modellerna.
- **Källan för faktat:** kontrollerat mot `/api/cars` och `/api/admin/ice-generations` att
  bara "Cupra Born" resp. "Ford Focus" innehåller delsträngarna `born`/`focus` i hela
  bildatabasen (ingen kollisionsrisk). Cupra Born säljs i Sverige som halvkombi på samma
  plattform som VW ID.3; Ford Focus är en C-segmentbil, ingen stadsbil.
- **Provet:** `InsightTaxonomyTest.cupraBornOchFordFocusArIngenSmaabil` (i
  `src/test/java/com/caradvice/model/InsightTaxonomyTest.java`, efter
  `kiaCeedArIngenSmaabil`). RÖTT före fixen (`kategoriMotsagelse("smaabil", "Cupra",
  "Born")` gav `null`), GRÖNT efter (`"born"` och `"focus"` tillagda i `STORA_MODELLER`).
- **Byggresultat:** `mvn -q test` grönt: 1219 tester, 0 failures, 0 errors.

**Kobeslut i natt (`src/main/resources/morgonfix/atgarder.json`):**
- **id 1661 (Volvo EX60, slapp):** modellen finns redan i katalogen som ev_spec-varianter
  (P6, P10 AWD, P12 AWD i `/api/cars`) och leasas aktivt — Blockets annonskoll (3b) hittade
  47 annonser, Business Lease 6995-7995 kr/mån för 2027 års modell. Bestallningsbar idag,
  alltså AKTUELL enligt användarens linje, trots att annonskollen gav GRANSKA (den
  tolkningen krävde ett beslut, inte bara annonstexten).
- **id 1387 (Lexus NX 450h+, slapp):** modellen säljs redan i Sverige (Blocket-annonser
  2019/2022 års modeller, `Lexus nx` i `/api/cars` sedan 2021 i ice-generations). Insikten
  beskriver en ansiktslyftning under samma modellnamn (ökad elektrisk räckvidd, DC-laddning)
  — ARSMODELLSREGELN säger en sådan är köpbar, inte en ny generation. Legat parkerad som
  GRANSKA sedan 09-02 utan att någon flaggat regressionen förrän nu.
- Övriga 27 rader i kön (XC70, Pajero, NX-varianter redan hanterade, Santa Fe, Tucson x4,
  Range Rover, ID.3 GTI x6, C-klass Electric x4, XC100, ID. Polo GTI, Lynk & Co 10, iX5
  Hydrogen, Kia PV7) lämnas parkerade — ingen av dem är bekräftat köpbar i Sverige idag.

**Laddtips (`src/main/resources/morgonfix/laddtips.json`):** ett nytt tips om Nissan Leaf
(75 kWh / 624 km WLTP / 150 kW DC), verifierat mot `/api/ev-spec?car=Nissan Leaf`
(id 1629 och 1631, poäng 3-4 i `/api/admin/ev-fact-candidates`) och kontrollerat mot
befintliga `laddtips.json` och `ev-app.js`-staticFacts utan dubblett.

**Laddpriser (måndag, avsnitt 13):** 0 kontrollerade — molnmiljöns egress-proxy avvisade
(`connect_rejected`) samtliga testade nätverks egna prissidor (Ionity, Tesla, Circle K,
Vattenfall InCharge). Reserven i `laddpriser.json` lämnas oförändrad.

**Lämnat därhän (kräver beslut, inte kod):**
- Ingen — inget över taket, inget krävde belägg som saknades i natt.

## 2026-09-27

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:37:25 svensk tid, inom
väntat fönster), deployad commit matchar origin/master (2c9810f), status OK, Groq 3/3
modeller, vPIC 291 kontrollerade / 0 avvikelser (8:e natten i rad utan avvikelse),
kontrollräkningen (cargo total 1725 + evSpecs 599 + ice_consumption 960 = 3284 = variants)
stämmer, drivmedelsräknaren 482/397/85/16 (ingen flip, manuella oförändrat på 16),
kategorivakten tyst (0/0) sedan senaste omstarten. Dagens dom blev KOLLA — inte på grund
av något trasigt, utan en kodlucka i kategorivakten plus två kommande-rader som verkar
felaktigt parkerade (kräver beslut, se nedan).

**Fix 1 — `ceed` saknades i `InsightTaxonomy.STORA_MODELLER`**
(`src/main/java/com/caradvice/model/InsightTaxonomy.java`, listan med
`"passat", "octavia", "superb", "insignia", "mondeo"`).

- **Vad nattrapporten visade:** id 1660 (CarUp, "en begagnad Kia Ceed ... 9 500 mil mer än
  vad som angavs") kom in med kategorin `smaabil`. Kia Ceed är en Golf-klassbil
  (C-segment) — samma hylla som `passat`/`octavia` som redan står i listan — inte en
  stadsbil. `/api/admin/kategorivakten` visade `totalt:0` för natten, vilket bekräftar att
  vakten inte kan fälla en modell den inte känner till; raden gick alltså igenom
  osedd, exakt det mönster rapportens regel för nya smaabil-fel på mellanklassbilar pekar
  på.
- **Källan för faktat:** Kia Ceed säljs i Sverige som halvkombi/kombi i samma
  storleksklass som VW Golf/Skoda Octavia (ingen småbil enligt tillverkarens egen
  modellindelning); Kia Proceed är samma plattform i kombikupé-form.
- **Provet:** `InsightTaxonomyTest.kiaCeedArIngenSmaabil` (i
  `src/test/java/com/caradvice/model/InsightTaxonomyTest.java`, efter
  `motsagelsenBerattarVadSomFalldes`). RÖTT före fixen (`kategoriMotsagelse("smaabil",
  "Kia", "Ceed")` gav `null`), GRÖNT efter (`"ceed"` tillagd i `STORA_MODELLER`, fångar
  även Kia Proceed via samma delsträng).
- **Byggresultat:** `mvn -q -DskipTests package` grönt. `mvn -q test` grönt: 1169 tester,
  0 failures, 0 errors.

**Lämnat därhän (kräver beslut, inte kod):**
- **Toyota Land Cruiser 250 Hybrid (id 1657–1659, M3, kommande-kön):** raderna innehåller
  ett självständigt test ("testföraren märker knappt någon skillnad i bränsleförbrukning
  ... 10,7 l/100 km") av en färdig bil. Websökning bekräftar att den elektrifierade
  48V-drivlinan sålts i Sverige sedan början av 2025 (Toyotas egen pressrelease). Enligt
  TESTBEVISREGELN bevisar ett sådant test i sig att modellen finns i handeln — raderna
  ser ut att vara felaktigt parkerade och borde troligen släppas. Ingen kod ändrad: att
  släppa raderna är en skrivning mot produktionsdatabasen (admin-API), vilket morgonfixen
  aldrig får göra. Kräver ett användarbeslut.
- **Mercedes CLA 45 4MATIC+ (id 1664, Allt om Elbil, kommande-kön):** raden anger ett
  konkret svenskt pris ("799 000 kronor"). Websökning bekräftar att detta är
  AMG-elbilen CLA 45 4MATIC+ som annonserats för den svenska marknaden till exakt det
  priset. Enligt ÅRSMODELLSREGELN gör ett utskrivet svenskt pris i kronor bilen köpbar
  och den bör inte parkeras. Trolig orsak: `STRICT_UPCOMING_PROMPT` (används för Allt om
  Elbil/CarUp) saknar med flit säljbarhetsregeln som den vanliga `UPCOMING_PROMPT` har
  (dokumenterat i `WebInsightScraperService.java`), så prisraden fångas inte. Ingen kod
  ändrad — att ändra prompten är uttryckligen förbjudet i morgonfixen, och att släppa
  raden är en databasskrivning. Kräver ett användarbeslut.
- Fem oförklarade luckor i id-serien inom fönstret (1616–1618, 1623, 1625) som inte
  matchar någon tidigare dokumenterad radering. Insiktsantalet (1201) och
  kontrollräkningen stämmer ändå, så inget pekar på ett större tapp — men ingen källa
  fanns för att belägga VARFÖR just de fem försvann, så inget ändrat. Öppen fråga till
  nästa granskning.
- Värdeminskningsfrågan är fortfarande oprövad: inga tydliga värdeminskningsstudier i
  fönstret (id 1615 och 1610 nämner prisfall/andrahandsvärde men anekdotiskt, inte som
  statistik). Inget att fixa, ingen ny data att belägga en rad med.

**Över taket — kvar till i morgon:** inget, allt ryms (1 fil ändrad i `src/main`, 1 rad
i en lista, 1 testfil).

## 2026-09-25

**Nattrapporten visade:** scrape-status OK (18 nya insikter), ev-specs/cargo-specs OK,
Groq 3/3 modeller, vPIC 291/291 kontrollerade och 0 avvikelser åttonde natten i rad,
drivmedelsräknaren 465/395/70/16 (ingen flip). Dagens dom blev KOLLA, inte på grund av
ett trasigt jobb, utan på grund av ett hål i veteranvakten som kategorivakten-loggen
och nattens rader tillsammans bevisade.

**Fix 1 — `ford orion` saknades i `InsightTaxonomy.UTGANGNA_MODELLER`**
(`src/main/java/com/caradvice/model/InsightTaxonomy.java`, raden med
`Map.entry("ford sierra", 1993)`).

- **Vad nattrapporten visade:** en CarUp-artikel om en övergiven bilhandlare gav fyra
  Ford-rader samma natt (id 1636 Sierra, 1637 Scorpio, 1638 Escort, 1639 Orion).
  Kategorivakten (`/api/admin/kategorivakten`) hade fällt exakt en av dem: Ford Sierra
  ("modellen ford sierra slutade tillverkas 1993", källa `web-insights [veteran]`).
  Ford Orion (id 1639, "...var en kombivariant av Escort som sålts i stora mängder på
  1980-talet") stod kvar som `familjebil` trots att Orion-namnet lades ner 1993 —
  exakt samma år som Sierra, och med samma marginal till 30-årsgränsen.
- **Källan för faktat:** Ford Orion byggdes på Escort-plattformen och namnet gick upp i
  Escort-serien 1993 när Sierra ersattes av Mondeo; ingen "Orion" säljs som nybil i dag
  (samma urvalskrav — ingen levande namne — som redan gäller för övriga rader i listan).
  Ford Scorpio (1998, id 1637) och Ford Escort (namnet levde kvar till ca år 2000, id
  1638) ligger båda för nära eller under 30-årsgränsen ännu och lämnades därför orörda
  — ingen gissning, bara `ford orion` lades till.
- **Provet:** `InsightTaxonomyTest.fordOrionArUtgangenSomFordSierra` (i
  `src/test/java/com/caradvice/model/InsightTaxonomyTest.java`, före
  `levandeModellerRorsInteAvModellregeln`). RÖTT före fixen
  (`InsightTaxonomy.utgangenModell("Ford", "Orion")` gav `null`), GRÖNT efter
  (`Map.entry("ford orion", 1993)` tillagd bredvid `ford sierra`).
- **Byggresultat:** `mvn -q -DskipTests package` grönt. `mvn -q test` grönt:
  1159 tester, 0 failures, 0 errors (46 surefire-rapporter).

**Lämnat därhän (kräver beslut, inte kod):**
- Omoda 9 (rad 1628, kommande-kön) har ett utskrivet "rekommenderat pris" i kronor
  (549 900 kr) utan utskriven framtida säljstart — enligt ARSMODELLSREGELN ett
  gränsfall för KÖPBAR. Annonskollen gav ändå INGA_ANNONSER (0 träffar) samma natt, så
  bilen verkar fortfarande inte säljas. Ingen kod ändrad — kräver ett användarbeslut om
  regeln ska tolkas striktare, inte en bugg att fixa.
- Bytbil bytte statusformat i natt ("INGA LANKAR (0 artikel-URL:er)" i stället för
  vanliga "0 av 0 lästa"). En natt är inget mönster — ingen åtgärd, bara noterat för att
  jämföra mot kommande nätter.
- Värdeminskningsfrågan (se rapportens egen rubrik) är fortfarande oprövad: 0 nya
  värdeminskningsrader i fönstret. Inget att fixa, ingen ny data att belägga en rad med.

**Över taket — kvar till i morgon:** inget, allt ryms (1 fil ändrad i `src/main`, 1 rad
i en karta, 1 testfil).
