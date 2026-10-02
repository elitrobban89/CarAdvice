# Morgonfix-logg

## 2026-10-02

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:39:09, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (a93747b), status OK, uptime
~14,9 h (ingen omstart i natt). Groq 3/3 modeller (friskt). ev-specs updated **96** - klart
över vanliga 0-25 och en fortsättning av gårdagens 31 (31→96), fortfarande långt från
290-larmet men en stigande trend värd att hålla ögonen på kommande nätter. cargo-specs gav
3/0/0 (3 nya bilnamn, 0 bagagevolymer, 0 generationsår - N inom 2-20, inget larm).
Kontrollräkningen (cargo total 1734 + evSpecs 613 + ice_consumption 960 = 3307 = variants)
stämmer exakt, liksom /api/stats (models 2187, insights 1266) och /api/cars (1974) mot
nattens +3/+3/+9. medVolym+bagageMissar: 995→996 / 781→784 (medVolym upp = OK enligt 3f,
oavsett bagageMissar). Samma ±1-glapp mellan cargo-specs-jobbets egna "bagagevolymer: 0" och
cargo-coverages medVolym-ökning som noterades 10-01 fortsätter (fortfarande för litet för
larm). Generationsåren still (291/19, väntat till fönstret 2026-10-20). vPIC: 291/0/275,
OK 126, INGEN_DATA 165, AVVIKER 0 - åttonde mätningen i rad utan avvikelse. Drivmedelsräknaren
484/399/85/16 (+1 total/+1 el, ingen flip, manuella oförändrat 16, alla 16 handsatta rader
korrekta). Kategorivakten: totalt 0 utslag sedan omstart (uptime 14,9 h) - ingen ny kontradiktion.

**Kommandevakten (annonskollen):** 0 LARM på 41 rader / 18 bilar (6 GRANSKA, 1 ANNAN_DRIVLINA,
11 INGA_ANNONSER - samma mönster som tidigare, inga nya LARM-värdiga fynd). Nattens 8 nya
köade rader (1704, 1713-1720) är alla korrekt parkerade: Range Rover Sport Electric (5 rader),
Peugeot E-208 GTi, Volkswagen ID. Polo GTI (duplicerad bil, se nedan) och "Range Rover Sport"
som GRANSKA (nästa-generation-rad, normalt utfall). Ingen av dem säljs i Sverige än enligt
annonskollen. **Uppföljning av 10-01:** gårdagens observation om att VW/Volkswagen ID. Polo
GTI räknades som två skilda "bilar" i kön p.g.a. stavningsskillnad är löst - commit 8dd763b
("Insikter: normalisera markesstavningen vid sparandet") normaliserar nu car_make till
"Volkswagen", och ikväll grupperar annonskollen id 1635 och 1704 korrekt som EN bil.

**Fynd i natt - dubblett:** insikt 1704 ("GTI-derivatet av Volkswagen ID. Polo har tagit
klivet in i den helelektriska eran", Auto Motor & Sport 2026-10-01) upprepar ordagrant samma
faktum som insikt 1635 ("GTI-derivatet av VW ID. Polo har gått över till hel-elektrisk
drivlina", Auto Motor & Sport 2026-09-25) - samma källa, samma bil, bara omskriven text.
Dold i natt med typ "dolj" i atgarder.json (se nedan), id 1635 kvarstår oförändrad som
representant för bilen i kön.

**Kontrollerat och avfärdat (ingen åtgärd):** Peugeot E-208 GTi (id 1715) kategoriserad
"smaabil" trots 280 hk/206 kW - samma mönster som VW ID. Polo GTI (1704) och ID.3 GTI-lackan
09-17. Detta är **redan utrett och avfärdat i gårdagens logg (10-01)**: InsightTaxonomy
utesluter GTI-varianter med flit från LYX_OCH_SPORTMODELLER, och Polo/208 är riktiga
småbilar efter fysisk storlek - stängd fråga, tas inte upp igen.

**PROMPTMISS att lämna öppen:** insikt 1729 (Volvo EX30, "tredje mest sålda elbilen i
september med 853 registreringar... 5 323 under jan-sep") är marknadsstatistik utan
förstaplats - SYSTEM_PROMPT (rad ~305) kräver uttryckligen FÖRSTAPLATSEN, "en placering
långt ner i en lista säger ingenting om bilen". Raden borde ha uteslutits av extraktionen.
Ingen åtgärd (ändrade prompter i skrapan är förbjudet enligt 9c/ramarna) - lämnas som fynd
till användaren. Angränsande rad 1728 (Volvo EX40, "näst mest sålda i september MEN först på
topplistan för hela perioden jan-sep") innehåller en äkta förstaplats (helårsperioden) och
bedöms INTE som promptmiss.

**Värdeminskning:** 0 rader i fönstret i natt med anknytning till värdeminskning/restvärde/
tillförlitlighet/livslängd/skrotålder. Frågan är fortfarande öppen (falskt negativt-regeln,
avsnitt 6).

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Jaguar I-Pace (id 1699, kvar i fönstret sedan tidigare, nedlagd
modell) behölls korrekt - inget tecken på överblockering i natt.

**Kobeslut (`atgarder.json`):** en rad - dolj id 1704 (dubblett, se ovan). Inget LARM från
annonskollen att slappa, inga kommande-bilar som behövde parkeras explicit (alla redan
korrekt GRANSKA/INGA_ANNONSER).

**Laddtips:** 1 nytt - BMW i3 40 xDrive (374 hk, 82,8 kWh, 710 km WLTP). Källa för
grundkandidaten var insikt 1700 (M3); siffrorna kontrollerade och bekräftade exakt mot
`/api/ev-spec?car=BMW i3 40 xDrive` (82,8 kWh, 710 km WLTP, 300 kW DC - matchar kandidaten).
**Avfärdade kandidater:** BMW iX3 40 xDrive (id 1723/1724, poäng 4) - kunde INTE beläggas:
`/api/ev-spec` har bara EN post för "BMW iX3" och den visar 108,7 kWh / 805 km (matchar den
redan publicerade iX3 50 xDrive-posten i ev-app.js, inte de påstådda 82,6 kWh / 621 km för
40 xDrive) - ingen 40 xDrive-post att stämma av mot, tipset skrivs inte. Tesla Model Y
laddkostnad (id 1711, poäng 3) - insikten anger 70 kWh batteri men `/api/ev-spec?car=Tesla
Model Y` visar 60,0 kWh - siffran går inte ihop, tipset skrivs inte.

**Laddpriser:** fredag, ingen kontroll i natt (bara måndagar).

**Splashvakten:** Dom "splasharna stämmer" (exitkod 0). Alla fem appar OK (Java 27, Spring
Boot 3.5.16, PostgreSQL 18.4 i tre). Elbilsladdning hade samma INFO-rader som tidigare
(Blocket/Elbilsvaruhuset/API Ninjas/Pressflöden/Chargeprice/NOBIL/YouTube nämns inte i
splashen) - ingen jämförelse med gårdagens exakta lista gjordes, så de rapporteras inte som
nya.

**Ingen kodfix i natt.** Inget hål i kategorivakten, inga parserfel, inga tal som gick att
belägga som fel. Byggt och testat grönt: `mvn -q -DskipTests package` grönt, `mvn test`
1224/1224 gröna (0 fel, 0 skippade).

**Baslinjen:** uppdateras till nattens mått (se docs/baslinje.json), commit a93747b.

**Lämnat därhän (kräver beslut, inte kod):**
- **Insikt 1729 (Volvo EX30, tredje plats)** - promptmiss enligt SYSTEM_PROMPT:s egen regel,
  se ovan. Ingen åtgärd möjlig inom nattrutinens ramar (prompter rörs inte).
- **ev-specs updated-trenden (31→96)** - ingen loggrad pekar på en orsak, men två nätter i
  rad över det väntade 0-25-intervallet är värt att bevaka natten efter natt.

## 2026-10-01

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:37:22, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (6316f17), status OK, uptime
~18,6 h (ingen omstart i natt), Groq 3/3 modeller (friskt). ev-specs updated 31 (något över
vanliga 0-25 men långt från 290-larmet). cargo-specs gav 0/0/0 i natt (tyst natt, cargo total
oförändrat 1731 vilket stämmer med 0 nya bilnamn). Kontrollräkningen (cargo total 1731 +
evSpecs 613 + ice_consumption 960 = 3304 = variants) stämmer exakt. medVolym+bagageMissar
rörde sig 994→995 / 781→781 (medVolym upp = OK enligt 3f:s domregel, oavsett att
cargo-specs-jobbet själv loggade 0 bagagevolymer – en liten (±1) avvikelse mellan jobbloggen
och cargo-coverage, för liten för att larma men värd att hålla ögonen på om den växer).
Generationsåren still (291/19, väntat till fönstret 2026-10-20, forsöktDag fortfarande
2026-09-20). vPIC 291 kontrollerade / 275 anrop / 0 avvikelser (ingen förändring mot
baslinjen - flera nätter i rad utan avvikelse). Drivmedelsräknaren 483/398/85/16 (+1 total/+1
el, ingen flip, manuella oförändrat 16). Kategorivakten fångade två kontradiktioner i natt
(Polestar 2 "ingen SUV" och Saab 900 "årsmodell 1988 är 30 år eller äldre") - båda vakten som
gjorde sitt jobb, inget hål. Kontrollerade specifikt om GTI-badgade nya rader (Volkswagen ID.
Polo GTI id 1704, Peugeot E-208 GTi id 1715, båda "smaabil") var samma läcka som VW ID.3 GTI
09-17 - de är INTE det: InsightTaxonomy.LYX_OCH_SPORTMODELLER utesluter GTI-varianter med
flit ("en egen gränsdragning som användaren äger"), och STORA_MODELLER träffar bara
Golf-klass och uppåt - Polo och 208 är riktiga småbilar. Ingen åtgärd.

Annonskollen (kommandevakten) gav 0 LARM på 41 rader / 19 bilar (6 GRANSKA: Range Rover
Sport, Volvo XC70, Mazda 6e, Mitsubishi Pajero, Hyundai Santa Fe, Hyundai Tucson - alla redan
avgjorda eller normalt utfall för nästa-generation-rader; 1 ANNAN_DRIVLINA: Range Rover,
sedan tidigare avgjord). Nattens 8 nya köade rader (1704, 1713-1720) är alla korrekt
parkerade (Range Rover Sport Electric x5, Peugeot E-208 GTi, Volkswagen ID. Polo GTI, samt
plain "Range Rover Sport" som GRANSKA) - annonskollen gav INGA_ANNONSER/GRANSKA på samtliga,
ingen av dem säljs i Sverige än. **Fynd att lämna till användaren:** samma bil, Volkswagen
ID. Polo GTI, ligger nu som TVÅ separata "bilar" i kön eftersom car_make stavas olika
("VW" på id 1635 från 09-25, "Volkswagen" på id 1704 från i natt) - UpcomingAdCheckService
grupperar bokstavligt på `make + " " + model` så duplikaten räknas som skilda bilar i
19-talet. Ingen kodfix i natt (ingen enskild, namngiven källa för hur grupperingen borde
normaliseras, och car_make-stavningen varierar på fler ställen än bara den här bilen -
kräver användarens beslut, inte en gissning).

**Vardeminskning/tillförlitlighet:** 1 rad i fönstret med anknytning (id 1699, Jaguar
I-Pace - "dyr att äga... risker för reservdelar när modellen lagts ner"), men ingen renodlad
värdeminsknings- eller restvärdesrad med siffra. Frågan är fortfarande öppen enligt
nattrutinens punkt om falskt negativt.

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Avvecklade modeller: id 1699 (Jaguar I-Pace, nedlagd modell,
tidigare såld i Sverige) behölls korrekt som relevant tillförlitlighetsinsikt - inget tecken
på överblockering.

**Kobeslut (`src/main/resources/morgonfix/atgarder.json`):** ingen ändring - filen var redan
en tom lista sedan 09-30, och annonskollen gav 0 LARM i natt så inget nytt underlag för
slapp/parkera/dölj. Ingen rad i kommande-kön krävde ett beslut.

**Ingen kodfix i natt.** Inget hål i kategorivakten (GTI-frågan kontrollerad och avfärdad,
se ovan), ingen parserfel, inga siffror som gick att belägga för en fix. VW/Volkswagen-
dubbletten i kön är ett namngivet fynd för användaren, inte en kodfix med entydig källa.

**Laddtips:** inga nya - nattens nya rader gäller bilar som ännu inte säljs i Sverige
(kommande-kön), och laddtipsregeln kräver bilar som går att köpa här i dag.

**Laddpriser:** torsdag, ingen kontroll i natt (bara måndagar).

**Splashvakten:** Dom: splasharna stämmer, alla fem appar OK (Java 27, Spring Boot 3.5.16,
PostgreSQL 18.4 i tre). De tre apparna som gav LARM i natten mot 09-30
(MiniPrisTåget/Bankomat 2.0/VäderKläder, nekade av nätverkspolicyn) svarade friskt i natt -
nätverksåtkomsten verkar ha vidgats sedan dess. GITHUB_TOKEN-401:an som nämndes 09-30 syns
inte heller i natt (kodhistoriken visar att Splashvakten bytt till git-baserad GitHub-läsning
41aaeaf/6316f17 sedan dess).

**Bygg- och provresultat:** inga kodändringar i natt, så inget Maven-bygge krävdes utöver
baslinjens egen körning (`node scripts/mat-baslinje.js`), som skrev ut
"kontrollrakning GAR IHOP 3304 / 3304".

**Baslinjen:** uppdaterad till natten mått (se docs/baslinje.json), commit 6316f17.

**Lämnat därhän (kräver beslut, inte kod):**
- **VW/Volkswagen ID. Polo GTI-dubbletten i kommande-kön** (id 1635 vs 1704) - samma bil
  under två stavningar av car_make, räknas som två "bilar" av annonskollen. Antingen rätta
  car_make för hand (admin-PATCH) eller normalisera i scrapern - båda kräver ett beslut om
  vilken stavning som ska vara facit.

## 2026-09-30

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:35:35, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (f3f615f), status OK, uptime
~8,1 h (ingen omstart i natt), Groq 3/3 modeller, vPIC 291 kontrollerade / 275 anrop / 0
avvikelser (10:e natten i rad utan avvikelse), kontrollräkningen (cargo total 1731 + evSpecs
599 + ice_consumption 960 = 3290 = variants) stämmer, drivmedelsräknaren 482/397/85/16 (ingen
flip, samma som baslinjen), kategorivakten fångade två nya kontradiktioner i natt (Ford Focus
och Volkswagen ID.3, båda redan i STORA_MODELLER sedan 09-27/09-28 — vakten gjorde sitt jobb,
inget hål), medVolym+bagageMissar rörde sig som ett par (994/781, båda upp — enligt 3f:s
domregel är det OK oavsett), generationsåren still (291/19, väntat till fönstret 2026-10-20).
Annonskollen (kommandevakten) gav 0 LARM på 33 rader / 15 bilar — tre nya, korrekt parkerade
bilar (VW ID. Tiguan x6, VW ID. Polo GTI, Lynk & Co 10; alla ej sålda i Sverige ännu).
Splashvakten gav sitt eget Dom: LARM, men det beror på att den här molnsessionens
nätverkspolicy nekar tre av de fem värdarna (tag-k5we.onrender.com, bankomat2-0.onrender.com,
vaderklader-1.onrender.com) — bekräftat mot miljöns dokumentation, inte bevis för att apparna
är trasiga. CarAdvice och Elbilsladdning svarade friskt (Java 27, PostgreSQL 18.4, egna
livesiffror på plats); bara GitHub-avstämningen (commit-topp, integrationer) föll på HTTP 401,
sannolikt ett ogiltigt GITHUB_TOKEN i miljön. Se rapporten för fullständig text. Dagens dom
blev KOLLA — inget trasigt i CarAdvice självt, men nya rader att döma och Splashvaktens
nätverksspärr kräver användarens beslut.

**Kobeslut (`src/main/resources/morgonfix/atgarder.json`):** filen rensad till en tom lista.
De två sedan tidigare exekverade raderna (id 1661 Volvo EX60, id 1387 Lexus NX 450h+, båda
"slappt" 2026-09-28 enligt `/api/admin/morgonfix-atgarder`) togs bort ur filen enligt formatet
("skriv över listan varje natt med bara nattens beslut"). Annonskollen gav 0 LARM i natt och
inga av kommande-kons 33 rader hade nytt underlag för slapp/parkera/dölj — de tre nya bilarna
(ID. Tiguan, ID. Polo GTI, Lynk & Co 10) ligger redan korrekt parkerade som INGA_ANNONSER.
Provet `MorgonfixAtgarderTest.verkligaFilenArGiltig` grönt mot den tomma listan.

**Ingen kodfix i natt.** Inget hål i kategorivakten, ingen parserfel, inga siffror som gick
att belägga för en fix — kategorifelen som dök upp (Ford Focus, VW ID.3) fångades redan av
vakten sedan tidigare fixar, ingen ny modell att lägga till.

**Laddtips:** inga nya. `ev-fact-candidates` gav två kandidater (Omoda 9, poäng 3, laddhybrid;
Mitsubishi Outlander, poäng 1) men ingen kändes tillräckligt stark för att verifieras och
publiceras i natt — fylls inte på för att fylla.

**Laddpriser:** ej måndag, ingen kontroll i natt.

**Bygg- och provresultat:** `mvn -q test` grönt: 1219 tester, 0 failures, 0 errors
(körning direkt efter atgarder.json-ändringen, innan commit).

**Lämnat därhän (kräver beslut, inte kod):**
- **Splashvaktens LARM för MiniPrisTåget, Bankomat 2.0 och VäderKläder** — den här
  molnsessionens nätverkspolicy nekar dessa tre värdar. En människa behöver antingen bredda
  nätverksåtkomsten för den här typen av körning, eller bekräfta apparna via annan väg.
- **GITHUB_TOKEN-miljövariabeln** gav HTTP 401 mot GitHubs API i splash-vakten (både för
  CarAdvice och Elbilsladdning) — ser ut som ett ogiltigt eller för kort värde, värt att
  kontrolleras.

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
