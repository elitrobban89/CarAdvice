# Morgonfix-logg

## 2026-10-04

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:25:12, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (dead1eb), status OK, uptime
~12,5 h (ingen omstart under natten - appen startade 14:06 dagen innan). Groq 3/3 modeller
(friskt). ev-specs updated **12** - inom 0-25. cargo-specs gav 0/0/0 (0 nya bilnamn, 0
bagagevolymer, 0 generationsår). Kontrollräkningen (cargo total 1736 + evSpecs 617 +
ice_consumption 960 = 3313 = variants) stämmer exakt, liksom /api/cars (1979).

**medVolym+bagageMissar still tre mätningar i rad (998/784 sedan 10-02) - bedömt som OK, inte
avvikelse.** Domregeln i 3f säger att still+oförändrad är en avvikelse om inte svepet är klart;
utanVolym har legat fryst på 738 i tre baslinjemätningar i rad (10-02, 10-03, 10-04) efter att
ha sjunkit stadigt från 855 (09-23), och ligger nu nära bagageMissar (784) - samma mönster som
"listan slut" i 3f. Tolkat som att den initiala svepningen är avklarad och att det som är kvar
är den sporadiska 30-dagars-återcirkulationen, analogt med generationsårens stillestånd (3a).
Loggen bar inga WARN/ERROR från CargoSpecService i natt (inga hämtningsfel), så H=0 är
konsekvent med tolkningen. **Håll ögonen på det:** om talen fortsätter stå still bortom ett
30-dagarsfönster utan att några missar åldras ut är det skäl att se över CREATE TABLE
cargo_spec_miss enligt 3f:s andra felmöjlighet.

**Generationsåren still (291/19, väntat till fönstret 2026-10-20).** vPIC: 291/0/275, OK 126,
INGEN_DATA 165, AVVIKER 0 - tionde mätningen i rad utan avvikelse. Drivmedelsräknaren
485/400/85/16, oförändrat sedan 10-03, ingen flip, alla 16 handsatta rader korrekta.
Kategorivakten: totalt 1 utslag sedan omstart (uptime 12,5 h) - Volkswagen Passat Alltrack
(web-insights), kategorin "suv" motsagd och strippad. Vakten gjorde sitt jobb, inget nytt hål.

**Mätfälla upptäckt i natt - se tillägg i docs/nattrutin.md avsnitt 8:** hogstaInsiktsId växte
från 1744 (baslinjen) till 1766 - 22 nya rader - men scrape-status visade bara 6 nya från
web-insights (+3 från mobility-stats = 9 väntade). En WARN-rad (WebInsightScraperService,
2026-10-03 14:16:11) visar att jobbet körde en extra gång mitt på dagen, 10 minuter efter en
deploy (startedAt 12:06 UTC/14:06 CEST) - scrape-status visar bara senaste körningen (känt
sedan avsnitt 1), så den körningens egna newInsights skrevs över av nattens. De 19 extra
raderna (15 CarUp, 3 Teknikens Värld, 1 Folksam) är granskade nedan som en del av fönstret och
ingen av dem är skräp. Lagt till som ny rad i avsnitt 8 (matfällor) så nästa natt vet att
stämma av mot id-intervallet i stället för jobbets eget tal när de inte går ihop.

**Kommandevakten (annonskollen):** 0 LARM på 41 rader / 18 bilar (6 GRANSKA, 1 ANNAN_DRIVLINA,
11 INGA_ANNONSER) - oförändrat sedan baslinjen 10-03, inga nya köade rader i natt. Inga
regressioner mot TESTBEVISREGELN eller ÅRSMODELLSREGELN; Mazda 6e- och Range Rover 1578-
avgörandena står kvar som tidigare.

**Nattens 22 nya rader (id 1745-1766):** granskade mot avsnitt 6. Tre Mobility Sweden-rader
(1764-1766, UTMÄRKELSEUNDANTAGET uppfyllt - förstaplats, svensk marknad, september 2026) och
en Folksam-rad (1757, säkerhetsutmärkelse, inget sälj-undantag). Resten är CarUp-reparations-/
tillförlitlighetsfakta (1748-1763, flera artiklar per rapporterat antal - X>Y är normalt, se
avsnitt 1) och tre Teknikens Värld-specrader om Polestar 3 (1745-1747). Inga kategorifel, inga
veteran-/samlarrader, inga specialutgåvor, inga skatterader eller dubbletter. Insikt 1758
(Mercedes E 300 de, enskild bils prisrabatt) är tunn men faller inte under någon namngiven
lacktyp - lämnas, samma typ av observation som 1741-1742 i gårdagens logg.

**Värdeminskning/tillförlitlighet:** åtta rader i natt (1751, 1752, 1754, 1759, 1760, 1761,
1762, 1763) rör tillförlitlighet eller reparationskostnad - ingen av dem föll bort i
extraktionen. Fortsätter stödja att FALSKA NEGATIV-oron från avsnitt 6 inte är ett aktivt
problem just nu.

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Inget tecken på överblockering av avvecklade modeller.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** inga nya i natt - annonskollen gav 0 LARM och kommande-kön är
oförändrad, filen stod redan tom och lämnas tom. Gårdagens två dolda rader (1704, 1729) är
bara ~2 dygn gamla, under 7-dygnsgränsen för radera - ingen uppföljning än.

**Laddtips:** 0 nya - samma fyra kandidater som igår, avvisade av samma skäl: BMW iX3 40
xDrive (id 1724, "300 kW") mot `/api/ev-spec?car=BMW iX3` som visar 400 kW; Tesla Model Y (id
1711, "70 kWh") mot ev-spec som visar 60,0 kWh; Range Rover P400e Autobiography (id 1738, "41
km") mot ett tomt ev-spec-svar; VW ID.7 Tourer (id 1743) utan konkret siffra att skriva tips av.
**Marknadsfakta uppdaterade (2 st):** ersatte den inaktuella EX40-raden (2025/H1 2026, 8 788)
med januari-september 2026-siffran **8 897** ur insikt 1765 (samma ämne, färsk siffra). Lade
till en ny rad för insikt 1764 (Volvo XC60, Sveriges mest registrerade bil totalt
januari-september 2026, 10 669) - inget tidigare ämne i filen täckte detta. Kontrollerade mot
`src/main/resources/static/ev-app.js` (`dynamicRankFacts`, en separat live elbilsranking från
elbilsvaruhuset.se) - annat ämne/period, ingen dubblett. Avstod från insikt 1766 (Tesla Model Y,
enskild månad september) för att inte skriva tre marknadsrader samma natt.

**Laddpriser:** onsdag, ingen kontroll i natt (bara måndagar).

**Splashvakten:** Dom "splasharna stämmer" (exitkod 0). Alla fem appar OK (Java 27, Spring
Boot 3.5.16, PostgreSQL 18.4 i tre). Elbilsladdning hade samma sju INFO-rader som tidigare -
oförändrat, inga nya.

**Ingen kodfix i natt.** Inget hål i kategorivakten, ingen parser som slutat träffa, inget tal
som gick att belägga som fel bortom det som redan är känt (laddtips-kandidaternas avslag).
Byggt och testat grönt: `mvn -q -DskipTests package` grönt, `mvn -q test` grönt (hela sviten,
inga oväntade fel), riktade körningar av `LaddtipsServiceTest` och `MorgonfixAtgarderTest` gröna.

**Regeländring i docs/nattrutin.md (avsnitt 8, +2 rader):** dokumenterade mätfällan ovan
(scrape-status visar bara senaste körningen av ett jobb som körts mer än en gång) så att en
framtida natt stämmer av mot id-intervallet i stället för att lita på ett enskilt jobbs
newInsights när de inte går ihop. Skärper bara mätningen - inget larm sänkt, inget tak höjt.

**Grenen:** `auto/morgonfix` fanns inte (borttagen av auto-merge efter 10-03:s PR). Skapad
på nytt från `origin/master` (dead1eb). Ingen fast PR att flytta (avsnitt 11 gäller inte).

**Baslinjen:** uppdaterad till nattens mått (se docs/baslinje.json), commit dead1eb,
kontrollräkning 3313/3313.

## 2026-10-03

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:37:16, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (ee6ffd5), status OK, uptime
~16,7 h (ingen omstart i natt). Groq 3/3 modeller (friskt). ev-specs updated **18** - inom
0-25. cargo-specs gav 1/1/0 (1 nytt bilnamn, 1 bagagevolym, 0 generationsår - N inom 2-20,
inget larm). Kontrollräkningen (cargo total 1736 + evSpecs 617 + ice_consumption 960 = 3313
= variants) stämmer exakt, liksom /api/stats (models 2192, insights 1281) och /api/cars
(1979). medVolym+bagageMissar: 996→998 / 784→784 (medVolym upp = OK enligt 3f, oavsett
bagageMissar). Generationsåren still (291/19, väntat till fönstret 2026-10-20). vPIC:
291/0/275, OK 126, INGEN_DATA 165, AVVIKER 0 - nionde mätningen i rad utan avvikelse.
Drivmedelsräknaren 485/400/85/16 (+1 total/+1 el, ingen flip, manuella oförändrat 16, alla
16 handsatta rader korrekta). Kategorivakten: totalt 0 utslag sedan omstart (uptime 16,7 h)
- ingen ny kontradiktion.

**Scrape-status, ny formatering värd att notera:** Teknikens Värld rapporterade i natt
"MAGERT UTBUD (2 artikel-URL:er)" i stället för det vanliga "X av Y lästa", och Folksam
"0" utan "av N lästa". Inget av detta är ett larm (Folksam är normalt en tyst källa, och
Teknikens Värld gav ändå lästa artiklar via de andra jobben), men formatet skiljer sig från
det tidigare mönstret - värt att hålla ögonen på om det återkommer.

**Kommandevakten (annonskollen):** 0 LARM på 41 rader / 18 bilar (6 GRANSKA, 1 ANNAN_DRIVLINA,
11 INGA_ANNONSER) - oförändrat innehåll sedan baslinjen 10-02, inga nya köade rader i natt
(nattens 15 nya rader, 1730-1744, gäller uteslutande redan säljbara bilar: Hyundai Tucson,
Range Rover P400e Autobiography, Volvo V70, Volvo EX60, VW ID.7 Tourer, Tesla Model S).

**Uppföljning av gårdagens PROMPTMISS (insikt 1729):** redan åtgärdad - användaren committade
direkt på master (ee6ffd5, "Insikter: kodvakt mot forsaljningsplaceringar utan forstaplats")
efter gårdagens rapport, med en kodvakt (isPlaceringUtanForstaplats) som stoppar
försäljningsplaceringar utan förstaplats framåt, och dolde 1729 via atgarder.json (bekräftat
i /api/admin/insights/dolda, hidden_at 09:51:34). Ingen ny åtgärd behövs.

**Kvalitetsnotering, ingen känd regel att hänga den på:** id 1741-1742 (CarUp, Volvo V70)
beskriver en enskild begagnad bils skick (70 000 mil, utbytt motor, buckla, däcktryckslampa)
snarare än ett generaliserbart modellfaktum. Ingen av de kända lacktyperna i avsnitt 6 täcker
exakt detta (inte skatterad, inte renoveringsobjekt, inte en anekdot om en privatpersons
preferens), så ingen döljning föreslås - lämnas som en observation att väga in om fler CarUp-
rader av samma typ dyker upp.

**Kontrollerat och avfärdat (ingen åtgärd):** inga nya kategorifel, inga veteran-/samlarrader,
inga specialutgåvor, inga skatterader eller renoveringsobjekt i fönstret i natt.

**Värdeminskning:** insikt 1736 (CarUp, Range Rover P400e Autobiography - "tappat 65 % av
sitt nypris på fem år, motsvarande 1,1 miljoner kronor") är en värdeminskningsrad som
sparades i natt. Detta är den första raden sedan regeln infördes (avsnitt 6) - frågan om
raderna försvann i extraktionen kan nu anses delvis besvarad (minst en sparas), men fortsätts
bevakas.

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Inget tecken på överblockering av avvecklade modeller.

**Kobeslut (`atgarder.json`):** inga nya i natt - annonskollen gav 0 LARM och kommande-kön är
oförändrad. Gårdagens två beslut (dolj 1704, dolj 1729) är bekräftat utförda
(/api/admin/morgonfix-atgarder, inga FEL). Filen skrivs över till tom lista.

**Laddtips:** 1 nytt - BMW i3 50 xDrive (469 hk, 108,7 kWh, 912 km WLTP), siffrorna
kontrollerade och bekräftade exakt mot `/api/ev-spec?car=BMW i3 50 xDrive`. **Avfärdade
kandidater:** BMW iX3 40 xDrive (id 1724, "upp till 300 kW") - `/api/ev-spec?car=BMW iX3` har
bara en post (108,7 kWh/805 km, maxDcKw 400) och den visar 400 kW, inte 300 - siffran går inte
att belägga för 40 xDrive-varianten, samma typ av avslag som igår för samma bilkluster. Tesla
Model Y laddkostnad (id 1711, poäng 3) - insikten anger 70 kWh batteri men `/api/ev-spec`
visar 60,0 kWh, samma avslag som igår. Range Rover P400e Autobiography (id 1738, "41 km EV-
räckvidd") - `/api/ev-spec?car=Range Rover P400e Autobiography` svarar tomt, ingen post att
stämma av mot. VW ID.7 Tourer (id 1743, poäng 1) - insikten saknar en konkret siffra
("förbättrad batterihantering och längre räckvidd" utan tal), inget tips att skriva.

**Laddpriser:** lördag, ingen kontroll i natt (bara måndagar).

**Splashvakten:** Dom "splasharna stämmer" (exitkod 0). Alla fem appar OK (Java 27, Spring
Boot 3.5.16, PostgreSQL 18.4 i tre). Elbilsladdning hade samma sju INFO-rader som i gårdagens
logg (Blocket/Elbilsvaruhuset/API Ninjas/Pressflöden/Chargeprice/NOBIL/YouTube nämns inte i
splashen) - oförändrat, inga nya.

**Ingen kodfix i natt.** Inget hål i kategorivakten, inga parserfel, inga tal som gick att
belägga som fel. Byggt och testat grönt: `mvn -q -DskipTests package` grönt, `mvn -q test`
grönt (inga fel), riktade körningar av `MorgonfixAtgarderTest` och `LaddtipsServiceTest` gröna.

**Grenen:** `auto/morgonfix` låg kvar sedan 10-02 med en odokumenterad, oplushad commit
(dae19c4, en ren loggrättelse - ingen öppen PR fanns för den). Mergad med `origin/master`
utan konflikt (`docs/morgonfix-logg.md` auto-mergades rent). Ingen fast PR att flytta
(avsnitt 11 gäller inte - under 3 dygn, och ingen PR är öppen just nu).

**Baslinjen:** uppdateras till nattens mått (se docs/baslinje.json), commit ee6ffd5.

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
11 INGA_ANNONSER). **Rättelse:** raderna 1704 och 1713-1720 (Range Rover Sport Electric,
Peugeot E-208 GTi, Volkswagen ID. Polo GTI, "Range Rover Sport" GRANSKA) hör till FÖRRA
nattens körning (09-30→10-01, redan utredda i gårdagens logg) - INTE till natten mot 10-02.
Nattens 9 nya rader (1721-1729) gäller uteslutande redan säljbara bilar (Volvo EX30/EX40,
Tesla Model Y, BMW iX3-klustret, VW Passat eHybrid) och gav INGA nya köade rader i natt.
Annonskollens 41/18 är alltså oförändrat köinnehåll sedan 10-01, bara ombedömt i natt - inget
nytt LARM. **Uppföljning av 10-01:** gårdagens observation om att VW/Volkswagen ID. Polo GTI
räknades som två skilda "bilar" i kön p.g.a. stavningsskillnad är löst - commit 8dd763b
("Insikter: normalisera markesstavningen vid sparandet") normaliserar nu car_make till
"Volkswagen", och ikväll grupperar annonskollen id 1635 och 1704 korrekt som EN bil (19→18).

**Fynd i natt - dubblett (raden är från förra nattens körning, beslutet tas i natt):** insikt
1704 ("GTI-derivatet av Volkswagen ID. Polo har tagit
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

**Uppföljning dagtid 10-02:**
- **1729 åtgärdad:** dold via atgarder.json, och ny kodvakt `isPlaceringUtanForstaplats` i
  `saveInsights` stoppar försäljningsplaceringar utan förstaplats framåt. Mätt mot alla 1266
  rader i drift: 14 hade fallit, alla försäljningsplaceringar; testrankningar (batterihälsa
  817-819, räckvidd 947/949) och 1728 behålls. Gamla rader lämnas orörda (bilhistoria).
- **ev-specs 96 är förklarad:** a93747b (deployad 10-01 förmiddag) låter skrapan skriva över
  ett pris som ändrats mer än 3 % - förut fylldes bara tomma priser. Första natten efter
  ändringen rättade alltså en hel bunt lanseringspriser. Siffran bör sjunka tillbaka mot 0-25;
  stannar den högt flera nätter är det växelkursglidning värd att titta på (loggraden
  "Pris X: a → b kr" är INFO och syns inte i /api/admin/logg).

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
