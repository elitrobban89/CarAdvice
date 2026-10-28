# Morgonfix-logg

## 2026-10-28

**DOM: KOLLA** - inget trasigt (hälsa OK, kedjan gick 00:34:10 inom fönstret vintertid, Groq 3/3,
AVVIKER 0 i vPIC, drivmedel 511/425/86/16 utan flip, ad-check 0 LARM, splashvakten grönt), men en
ny PROMPTMISS i kön (Polestar 4 SUV, id 2069 - redan i handeln) krävde ett kobeslut i natt, och två
sedan tidigare dokumenterade öppna frågor (id 2038 veteranvakt-lucka, id 2021 BMW X5-kötvilling)
kvarstår obelagda. Dessutom en ny öppen fråga ur marknadsregeln (Elaris, se nedan).

**Ett kobeslut i natt - SLÄPP (PROMPTMISS, ny upptäckt):**
- **2069 (Polestar 4 SUV, CarUp)** - ad-check gav INGA_ANNONSER, men Polestar 4 säljs redan i
  Sverige (nypris ca 599 000-659 900 kr hos Polestar, Blocket-annonser från 559 900 kr,
  privatleasing erbjuds - kontrollerat mot blocket.se/minbilkoll 2026-10-28). car_model "4 SUV" är
  en stavningsvariant som missar Blockets annonser (de heter bara "Polestar 4") - samma typ av fel
  som Ioniq 3/"Ionix 3" 10-08. Klass (b) i avsnitt 3b, hör inte till kommande-kön.

**Ingen kodfix i natt** - inget tillräckligt belagt kodfel hittades. Tre filer ändrade, alla
datafiler (räknas inte mot femfilstaket): `docs/baslinje.json` (mat-baslinje.js, kontrollräkning
3439/3439 går ihop), `src/main/resources/morgonfix/atgarder.json` (1 kobeslut, se ovan) och denna
loggbok. Inga nya laddtips i natt - enda kandidaten med poäng (2071, Nissan Leaf VIVNE-batteribyte
62 kWh) går inte att belägga mot /api/ev-spec eftersom det är ett eftermarknadsbyte, inte
fabriksspec, och originalsiffran "60 km på vintervägar" hittar inget stöd i internationella källor
om VIVNE-bytet (som i stället talar om 172→366 km i ett dokumenterat fall) - skrivs inte.
2067 (VW ID. Polo, 7,51 kWh/100 km på Wolfsburg-Wien) stämmer INTE mot /api/ev-spec (334 km WLTP på
37,5 kWh ≈ 11,2 kWh/100 km) - troligen en icke-representativ eco-körning, skrivs inte. Övriga
kandidater (2015, 2030, 2031) redan skrivna/uteslutna tidigare natt.

**Grundkollen:** lastScrapeFinishedAt 2026-10-28 00:34:10 (inom väntat fönster 00:15-00:50
vintertid), deployad commit 76d0018 matchar origin/master, status OK, ingen omstart (uptime
~23,6 h). ev-specs updated **5** (inom 0-25, kollisioner 182, evSpecs 654→654 still). cargo-specs
gav **0/0/0** (0 nya bilnamn, 0 av 0 bagagevolymer kvar, 0 av 0 generationsår kvar - båda
arbetslistorna tömda just nu). Kontrollräkningen (cargo total 1825 + evSpecs 654 + ice_consumption
960 = 3439 = /api/stats variants) stämmer exakt. /api/cars 2089 (oförändrat). models 2302→2302,
insights 1586→1601 (+15 nattens rader).

**BAGAGELARMET (3f) - medVolym 1043→1043 (oförändrat), bagageMissar 825→825 (oförändrat).** KB i
cargo-specs var 0 av 0 kvar - undantaget i 3f gäller: listan är slut (allt fyllt eller parkerat),
så "still + oförändrad" är OK, inte avvikelse.

**Generationsåren:** total 291→291, missar 19→19 (291+19=310, stämmer). Samtliga 19 missar har
forsöktDag **2026-10-21** (6 dagar sedan, 23 dagar kvar), 16 vakten-avstod / 3 ej-hittad -
oförändrat, väntat stillestånd enligt 3a/3f (KG=0 av 0 kvar).

**vPIC:** 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt. **Drivmedelsräknaren:** 511/425/86/16 - helt oförändrat mot baslinjen 10-27, alla
16 handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip. **Kategorivakten:** 1 utslag sedan
omstart (uptime ~23,6 h) - Mercedes S-klass felaktigt taggad familjebil, vakten strök kategorin
korrekt (lyxbil, inte familjebil). Ingen regression.

**Nattens 15 nya rader (id 2057-2071):** Toyota Land Cruiser (ramchassi), fem rader ur samma
ADAC-mörkertest med reflexväst (BYD Seal, Dacia Duster, Hyundai Tucson, Mercedes CLA, Tesla
Model Y - olika utfall per modell, samma typ av klunga som Safety Coach-fallet 1253-1257, ingen
bugg), Volvo EX90 (bromsfel, känt säkerhetsfel), Mercedes EQE SUV (rostskydd), Audi Q4 e-tron +
Q4 Sportback e-tron (två separata återkallelser), VW ID. Polo (förbrukningstest, se laddtips ovan
om varför siffran inte publiceras där), Mercedes S-klass (värdeminskning, kategori korrekt struken
av vakten), Polestar 4 SUV (kommande-kö-PROMPTMISS, se kobeslut ovan), Saab 9-5 NG Vector
(lågmilt exemplar), Nissan Leaf (VIVNE-batteribyte, se laddtips ovan). Alla 15 godtagbara som
insikter - inga kategorifel eller veteranrader. Värdeminskning: 1 rad (Mercedes S-klass), inte
längre en öppen fråga i sig men bevakas som vanligt.

**Övriga rader i fönstret (id 2022-2056):** tidigare granskade i gårdagens och förrgårdagens
rapporter, inga nya problem. **id 2038 (Volvo 850 R) kvarstår som öppen fråga** - samma
veteranvakt-lucka ("hittades efter 24 år i en fallfärdig lada" matchar inte regexet
`gömd i \d+ år`, "850" finns inte i UTGANGNA_MODELLER), fortsatt inte belagt nog för en kodfix utan
användaren.

**MÖJLIG KÖTVILLING, id 2021 (BMW X5 M60e xDrive) - oförändrad:** fortfarande ingen namngiven källa
som bekräftar att just M60e xDrive-varianten säljs i Sverige. Kvarstår som förslag.

**Tre regler:** SKATTER - inget underlag i natt. RENOVERINGSOBJEKT - inget underlag (2038 diskuteras
ovan, bedöms som hållbarhetsanekdot snarare än säljpitch för ett renoveringsobjekt). AVVECKLADE
MODELLER ÄR RELEVANTA - Saab 9-5 (2070, avvecklat märke) behölls korrekt, ingen överblockering sedd.

**Marknadsregeln - NY ÖPPEN FRÅGA (Elaris):** nätet är öppet i natt (kontrollbilen Volvo XC40 gav
träffar på Blocket). Stickprov på ovanliga märken i cargo_spec/ev_spec: Dongfeng (0 Blocket-träffar,
men märket SÄLJS i Sverige via Novus Cars/DFSK sedan 2024 - bara specifikt "Dongfeng Box" är
osäkert, för smalt underlag för ett förslag) och Elaris (0 Blocket-träffar, webbkällor nämner inte
Sverige som marknad och antyder att bolaget var insolvent i januari 2025) - Elaris är en möjlig
kandidat för radering men INTE verifierat mot två oberoende källor med aktuellt 2026-läge. Skrivs
som öppen fråga, inget raderingsförslag än.

**Kommandevakten (annonskollen):** 0 LARM, 8 GRANSKA, 1 ANNAN_DRIVLINA, 20 INGA_ANNONSER (varav
2069 släppt i natt, se kobeslut) på 56 rader / 29 bilar. Samtliga GRANSKA/ANNAN_DRIVLINA-fall
oförändrade och redan dokumenterade (Bayon-tvillingarna, Tucson gen 5, Mazda 6e, Pajero, Santa Fe,
Volvo XC70, BMW i3, Range Rover Sport, Range Rover 1578).

**Splashvakten:** grönt i alla sex projekt - "Dom: splasharna stämmer." INFO-raderna hos
Elbilsladdning är återkommande sedan tidigare, inga nya.

**Baslinjen:** skriven (`node scripts/mat-baslinje.js`), kontrollräkning 3439/3439 går ihop.



**DOM: KOLLA** - inget trasigt (hälsa OK, kedjan gick 00:32:39 inom fönstret vintertid, Groq 3/3,
AVVIKER 0 i vPIC, drivmedel 511/425/86/16 utan flip, ad-check 0 LARM, splashvakten grönt), men två
öppna frågor (BMW X5 M60e xDrive-tvillingen 2021 och Volvo 850 R-veteranluckan 2038, båda redan
dokumenterade 10-26) kvarstår, och fyra kobeslut krävde dom i natt.

**Ingen kodfix i natt** - inget tillräckligt belagt kodfel hittades. Tre filer ändrade, alla
datafiler (räknas inte mot femfilstaket): `docs/baslinje.json` (mat-baslinje.js, kontrollräkning
3439/3439 går ihop), `src/main/resources/morgonfix/atgarder.json` (4 kobeslut, se nedan) och denna
loggbok. Inga nya laddtips i natt - alla kandidater (2015, 1999, 2002) redan skrivna 10-23/10-24,
1998 (Peaq 90 förbrukning) fortsatt diskvalificerad (siffran stämmer inte mot ev-spec, samma skäl
som 10-26), 2006 diskvalificerad (samma felattribuerade Kia EV5-rad som dolts 10-23), 2030/2031
(Tesla Model S-batterihälsa) fortsatt uteslutna utan jämförelsetabell, 2005 för vagt underlag.

**Kobeslut i natt - SLÄPP (ARSMODELLSREGELN, regression upptäckt i natt):**
- **1844, 1845, 1846 (BMW iX3 M60 xDrive)** - parkerade sedan 10-09. ev_spec "BMW iX3 M60 xDrive"
  ger nu 816 000 kr, 108,7 kWh, 400 kW DC - matchar radernas egna tal exakt (108,7 kWh i 1845, 400 kW
  i 1846). cargo_spec "bmw ix3" = el, /api/cars listar BMW iX3. Samma mönster som BMW iX4 Neue
  Klasse-fixet 10-24.
- **1715 (Peugeot E-208 GTi)** - parkerad sedan 10-01. "Peugeot e-208 GTi" finns som eget namn i
  /api/cars, skilt från bas-e-208 (388 000 kr) - ev_spec ger 483 000 kr / 50,8 kWh / 206 kW,
  stämmer mot radens 54 kWh / 206 kW (280 hk).
- **Kontrollerat men INTE släppt:** VW ID.3 GTI (1545, 1552-1554, 1556-1557) - ev-spec-svaret för
  "Volkswagen ID.3 GTI" (331 km / 37,5 kWh / 390 000 kr) matchar INTE radernas 79 kWh / 60 mil, och
  /api/cars saknar "ID.3 GTI" helt (bara "ID. Polo GTI" finns, med en TREDJE spec). Sannolikt en
  fuzzy-matchningsträff på fel bil - ingen källa, inget beslut. Kvarstår parkerad.

**Grundkollen:** lastScrapeFinishedAt 00:32:39 (inom väntat fönster 00:15-00:50 vintertid), deployad
commit fcb0c8a matchar origin/master, status OK, ingen omstart (uptime ~23,6 h). ev-specs updated
**5** (inom 0-25, kollisioner 182, evSpecs 654→654 still). cargo-specs gav **1/0/0** (1 nytt
bilnamn, 0 av 92 bagagevolymer, 0 av 0 generationsår kvar). Kontrollräkningen (cargo total 1825 +
evSpecs 654 + ice_consumption 960 = 3439 = /api/stats variants) stämmer exakt. /api/cars 2089 (+1,
matchar 1 nytt bilnamn). models 2301→2302, insights 1578→1586 (+8 nattens rader).

**BAGAGELARMET (3f) - medVolym 1043→1043 (oförändrat), bagageMissar 733→825 (+92).** KB i
cargo-specs var 92 (inte 0). medVolym STILL + bagageMissar VUXIT = VÄNTAT enligt domregeln -
jobbet betade av bilar auto-data inte har. Ingen avvikelse.

**Generationsåren:** total 291→291, missar 19→19 (291+19=310, stämmer). forsöktDag för samtliga 19
missar är nu **2026-10-21** (6 dagar sedan, 24 dagar kvar till nästa fönster) - de provades alltså
om efter att fönstret stängde 10-20, så ingen notis krävs (16 vakten-avstod, 3 ej-hittad).

**vPIC:** 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt. **Drivmedelsräknaren:** 511/425/86/16 - helt oförändrat mot baslinjen 10-26, alla
16 handsatta rader (11 el + 5 ice) fortsatt korrekta. **Kategorivakten:** 0 utslag sedan omstart
(uptime ~23,6 h) - inget att rapportera.

**Nattens 8 nya rader (id 2049-2056):** Chery Lepas L6/L8 (kommande, nya köade bilar, inga
annonser än - korrekt), Ford Ranger (krockkuddefel, känt defekt-typ), Kia K4 Sportswagon hybrid x2
(pris + förbrukning), Volvo EX60 (funktionsjämförelse), Mazda CX-5 (Google Gemini-stöd), Honda
Civic (ARN-tvist). Alla 8 godtagbara - inga skatterader, kategorifel eller veteranrader.

**Övriga rader i fönstret (id 2007-2048):** stark skörd värdeminskningsrader (2008-2013 reparations-
kostnader, 2030-2032 batteridegradering Tesla Model S, 2033-2037 reparationskostnader, 2039-2042
"svårsålda" CarUp-rader) - alla godtagbara (bilens egna mätvärden/reliabilitet, inget marknadskrav).
**id 2038 (Volvo 850 R) kvarstår som öppen fråga** - samma veteranvakt-lucka som dokumenterades
10-26 ("hittades efter 24 år" matchar inte regexet `gömd i \d+ år`), fortsatt inte belagt nog för en
kodfix. id 1999-2029 tidigare granskade, inga nya problem.

**MÖJLIG KÖTVILLING, id 2021 (BMW X5 M60e xDrive) - oförändrad sedan 10-26:** fortfarande ingen
namngiven källa som bekräftar att den säljs i Sverige (ingen ev_spec/cargo_spec-träff för just
"X5 M60e xDrive"). Kvarstår som förslag, inget kobeslut.

**Tre regler:** SKATTER - inget underlag. RENOVERINGSOBJEKT - inget underlag (2038 diskuteras ovan).
AVVECKLADE MODELLER ÄR RELEVANTA - inget underlag, ingen överblockering sedd.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kommandevakten (annonskollen):** 0 LARM, 8 GRANSKA, 1 ANNAN_DRIVLINA, 21 INGA_ANNONSER på 59
rader / 30 bilar. Samtliga GRANSKA/ANNAN_DRIVLINA-fall oförändrade och redan dokumenterade (Bayon-
tvillingarna, Tucson gen 5, Mazda 6e, Pajero, Santa Fe, Volvo XC70, BMW i3, Range Rover Sport,
Range Rover 1578). Fyra INGA_ANNONSER-rader släppta i natt (se kobeslut ovan). Nattens nya köade
bilar (Chery Lepas L6/L8) saknar Blocket-annonser - korrekt, inget beslut krävs än.

**Splashvakten:** grönt i alla sex projekt (MiniPrisTåget, Bankomat 2.0, Elbilsladdning, CarAdvice,
VäderKläder, Bränslekostnad) - "Dom: splasharna stämmer." INFO-raderna hos Elbilsladdning är
återkommande sedan tidigare, inga nya.

**Baslinjen:** skriven (`node scripts/mat-baslinje.js`), kontrollräkning 3439/3439 går ihop.

**DOM: KOLLA** - inget trasigt (hälsa OK, kedjan gick 00:32:11 inom fönstret vintertid, Groq 3/3,
AVVIKER 0 i vPIC, drivmedel 511/425/86/16 utan flip, ad-check 0 LARM, splashvakten grönt), men
nattens 3 nya rader krävde dom och en möjlig BMW X5-kötvilling (se nedan) är värd att hålla ögonen
på.

**Ingen kodfix i natt** - inget tillräckligt belagt kodfel hittades för att lagas utan användaren.
Tre filer ändrade, alla datafiler (räknas inte mot femfilstaket): `docs/baslinje.json`
(mat-baslinje.js, kontrollräkning 3438/3438 går ihop), `src/main/resources/morgonfix/atgarder.json`
(tömd - inga kobeslut i natt, se nedan) och `src/main/resources/morgonfix/laddpriser.json`
(måndagskontroll, se nedan). Inga nya laddtips i natt - enda nya kandidaten (1998, Skoda Peaq 90
förbrukning) gav en siffra som inte stämde mot /api/ev-spec (se nedan).

**Grundkollen:** lastScrapeFinishedAt 00:32:11 (inom väntat fönster 00:15-00:50 vintertid - första
natten efter övergången), deployad commit 6377ca4 matchar origin/master, status OK, ingen omstart
(uptime ~23 h). ev-specs updated **5** (inom 0-25, kollisioner 182, evSpecs 654→654 still - inga
nya rader i detta ledet i natt). cargo-specs gav **0/5/0** (0 nya bilnamn, 5 av 250 bagagevolymer,
0 av 0 generationsår kvar) - inget larmmönster. Kontrollräkningen (cargo total 1824 + evSpecs 654 +
ice_consumption 960 = 3438 = /api/stats variants) stämmer exakt. /api/cars 2088 (oförändrat,
matchar 0 nya bilnamn). models 2301→2301, insights 1576→1578 (+3 nattens rader, -1 därför att
baslinjens mätning 10-25 00:53 UTC skedde 6 minuter FÖRE samma morgons radering av 1940 - ingen
tappad rad, bara sekvensen mellan mätning och radering).

**BAGAGELARMET (3f) - medVolym 1038→1043 (+5), bagageMissar 588→733 (+145).** Summan (+150) träffar
samma nattliga 150-tak som svepet sedan 10-21. medVolym UPPÅT = OK enligt domregeln oavsett
bagageMissar. Fortsätt bevaka tills kurvan planar ut.

**Generationsåren:** total 291→291, missar 19→19 (291+19=310, stämmer). Samtliga 19 missar har
fortsatt forsöktDag **2026-10-21**, 16 vakten-avstod / 3 ej-hittad - oförändrat, väntat stillestånd
enligt 3a.

**vPIC:** 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt. **Drivmedelsräknaren:** 511/425/86/16 - ice +1 (cargo-specs-ledets normala
påfyllning), el och manuella oförändrade. Alla 16 handsatta rader (11 el + 5 ice) fortsatt korrekta,
ingen flip. **Kategorivakten:** 0 utslag sedan omstart (uptime ~23 h) - inget att rapportera.

**Nattens 3 nya rader (id 2046-2048, Vi Bilägare, Chrysler 300C):** alla 3 godtagbara - V6-tillför-
litlighet (begagnatköp), dieselspec (CRD, utvecklad med Mercedes), bensinspec (Hemi V8 350 hk).
Ingen kategorifel, inga skatterader eller renoveringsobjekt.

**Övriga rader i fönstret (id 1999-2045):** 2030-2045 (natten 10-25) redan bedömda i loggboken
nedan - 15/16 godtagbara, **id 2038 (Volvo 850 R) kvarstår som öppen fråga** (möjlig veteranvakt-
lucka, se gårdagens post - jag har verifierat `InsightTaxonomy.java` själv i natt: "hittades efter
24 år" matchar inte `gömd i \d+ år`, och "850" finns inte i `UTGANGNA_MODELLER` - fortsatt inte nog
belagt för en fix). id 1999-2029 tidigare granskade, inga nya problem.

**MÖJLIG KÖTVILLING, id 2021 (BMW X5 M60e xDrive, Auto Motor & Sport, INGA_ANNONSER i kön) - inte
lagad i natt:** delar natt och expert med 2020/2022 ("BMW X5" i de vanliga raderna, om takbox och
M Performance-paket). Samma sökmönster som Mazda 6e- och Bayon-tvillingarna i 3b, men 2020/2022
beskriver tillbehör till DAGENS X5, inte uppenbart samma bil som M60e xDrive-varianten - jag har
ingen namngiven källa som bekräftar att M60e xDrive säljs i Sverige än. Skrivs som förslag, inget
kobeslut i atgarder.json.

**Tre regler:** SKATTER - inget underlag. RENOVERINGSOBJEKT - inget underlag (2038 diskuteras ovan).
AVVECKLADE MODELLER ÄR RELEVANTA - inget underlag, ingen överblockering sedd.

**Värdeminskning:** INTE längre oprövad (se gårdagens post) - ovanligt stark skörd igen i natt:
3 rader batteridegradering (Tesla Model S, 2030-2032, redan skrivna 10-25), 4 rader om svårsålda
begagnade bilar (2039-2042), flera reparationskostnadsrader (2008-2013, 2033-2037) och en
motorhållbarhetsrad (2007, Saab 900 Aero) - alla från natten 10-25, redan räknade där.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kommandevakten (annonskollen):** 0 LARM, 8 GRANSKA, 1 ANNAN_DRIVLINA, 19 INGA_ANNONSER på 57
rader / 28 bilar - oförändrat mot baslinjen. Samtliga tidigare dokumenterade fall oförändrade
(Bayon-tvillingarna, Tucson gen 5, Mazda 6e, Pajero, Santa Fe, Range Rover 1578, BMW i3). Nattens
nya köade bilar (BYD Seagull 2026/2028/2029, MG Go! 2025/2027, Honda 0 Alpha 2024) och BMW X5 M60e
xDrive (2021, se kötvilling-frågan ovan) saknar alla Blocket-annonser - korrekt INGA_ANNONSER,
inget beslut krävs än. Inga dolda rader har nått 7-dygnsgränsen (2006: 3 dygn, 1987: 4 dygn).

**Laddtips:** inga nya i natt. `ev-fact-candidates` gav 8 kandidater, men alla var redan skrivna
(2015, 2002, 1999) eller uteslutna av samma skäl som 10-25 (2006 dold i natt som felattribuerad,
2031/2030 ensamma batterihälsotester utan spec-tabell, 2005 för vagt). **1998** (Skoda Peaq 90,
Cw-värde/förbrukning) var en ny kandidat men förbrukningen "1,5 kWh/mil enligt WLTP" stämmer inte
mot /api/ev-spec (647 km WLTP / 86,0 kWh ≈ 1,33 kWh/mil) - för stor avvikelse för att skriva som
fakta, samma typ av fel som Puma Gen-E 08-18.

**Laddpriserna (måndag):** 5 nätverk kontrollerade mot egna prissidor, **0 ändrade** - ionity
6,09, allego 6,30 (Sverige-blocket, "Pay as you go Ultrafast"), mer 5,10 (högtrafik 07-11/16-20),
circle k/circlek 6,09 (egna ultrasnabba, engångsbetalning), lidl 5,80 (DC via smartphone) kr/kWh,
alla oförändrade sedan 10-19 - bara `kontrollerad`-datum uppdaterat.

**Kobesluten:** inga i natt - inget LARM i annonskollen, inga dolda rader vid 7-dygnsgränsen, och
ingen tillräckligt belagd slapp/parkera-kandidat. `atgarder.json` skrivet tomt.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK, CarAdvice-raden visar autodeploy
master · 6377ca4 (matchar /api/version). Samma INFO-rader som tidigare, inget nytt.

**Testat:** `mvn -q -Dtest=LaddtipsServiceTest,MorgonfixAtgarderTest test` grönt (exitkod 0). `mvn
test` (hela sviten) grönt, exitkod 0 - WARN/ERROR-raderna i loggen är samma avsiktliga
testfixturer som tidigare nätter. `mvn -q -DskipTests package` grönt.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning 3438/3438
GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` (grenen fanns inte sedan
föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-25

**DOM: KOLLA** - inget trasigt (hälsa OK, kedjan gick 01:29:20 inom fönstret, Groq 3/3, AVVIKER 0
i vPIC, drivmedel 510/425/85/16 utan flip, ad-check 0 LARM), men nattens 16 rader krävde dom, en
dold rad nådde äntligen den riktiga 7-dygnsgränsen och en möjlig lucka i veteranvakten (se nedan)
är värd att hålla ögonen på.

**Ingen kodfix i natt** - inget tillräckligt belagt kodfel hittades för att lagas utan användaren
(se veteranvakten nedan). Två filer ändrade, båda datafiler (räknas inte mot femfilstaket):
`docs/baslinje.json` (mat-baslinje.js, kontrollräkning 3438/3438 går ihop) och
`src/main/resources/morgonfix/atgarder.json` (ett kobeslut, se nedan). Inga nya laddtips i natt -
ingen kandidat klarade källkravet (se nedan).

**Grundkollen:** lastScrapeFinishedAt 01:29:20 (inom väntat fönster 01:15-01:50 sommartid - sista
natten innan vintertid), deployad commit 8d9fed8 matchar origin/master, status OK, ingen omstart
(uptime ~23,6 h). ev-specs updated **5** (inom 0-25, kollisioner 182, evSpecs 654→654 still - inga
nya rader i detta ledet i natt). cargo-specs gav **0/0/0** (0 nya bilnamn, 0 av 400 bagagevolymer,
0 av 0 generationsår kvar) - inget larmmönster (N>50 är taket, 0 är lågt men inte ett eget larm).
Kontrollräkningen (cargo total 1824 + evSpecs 654 + ice_consumption 960 = 3438 = /api/stats
variants) stämmer exakt. /api/cars 2088 (oförändrat, matchar 0 nya bilnamn).

**BAGAGELARMET (3f) - medVolym 1037→1038 (+1), bagageMissar 438→588 (+150).** KB i cargo-specs
var 400 (>0), så undantaget i 3f gäller inte - men domregeln ger VÄNTAT ändå: medVolym rörde sig
UPPÅT (+1, om än svagt) samtidigt som bagageMissar växte kraftigt. Summan (+151) träffar igen
samma nattliga 150-tak som återuppbyggnaden av `cargo_spec_miss` sedan 10-21 (tömd för hand).
Fortsätt bevaka tills kurvan planar ut.

**Generationsåren:** total 291→291, missar 19→19 (291+19=310, stämmer). Samtliga 19 missar har
fortfarande forsöktDag **2026-10-21**, 16 vakten-avstod / 3 ej-hittad - oförändrat sedan förra
veckans omprövning, väntat stillestånd enligt 3a.

**vPIC:** 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt, sjunde-plus natten i rad utan avvikelse. **Drivmedelsräknaren:** 510/425/85/16 -
total och el steg (+2/+2) från cargo-specs-ledets normala påfyllning, ice och manuella oförändrade.
Alla 16 handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip. **Kategorivakten:** 0 utslag
sedan omstart (uptime ~23,6 h) - inget att rapportera, inte ett tecken på att vakten är trasig.

**Nattens 16 nya rader (id 2030-2045, alla CarUp/Allt om Elbil):** 15 av 16 godtagbara. Inga
kategorifel (suv/familjebil/smaabil-tilldelningarna stämmer mot bilstorlekarna), inga skatterader
eller renoveringsobjekt. **Ovanligt stark skörd för värdeminskning/tillförlitlighet/livslängd: 8
rader i natt** (2030-2032 Tesla Model S batteridegradering, 2033 V60 D6 hybridbatteri 185 000 kr,
2034 XC90 T8 ERAD-haveri, 2035 V40 växellåda, 2036 V70 växellåda, 2037 XC70 vinkelväxel) plus 4
rader om svårsålda begagnade bilar (2039-2042: XC90 D5, 850 R, E-klass, A6/Touareg) som också hör
hit (värdeminskning/restvärde-läckan är därmed INTE längre oprövad - frågan kan stängas eller
omformuleras till en räknare).

**MÖJLIG VETERANVAKT-LUCKA (öppen fråga, inte lagad i natt) - id 2038 (Volvo 850 R, CarUp):**
"När den hittades efter 24 år i en fallfärdig lada hade bilen fortfarande fungerande elsystem och
ingen allvarlig rost i chassit, vilket demonstrerar Volvos byggkvalitet." Raden behöll category
`familjebil` och fuelType `bensin`. Volvo 850 R tillverkades ca 1995-1997, alltså på gränsen till
30-årsregeln, och "hittades efter 24 år i en fallfärdig lada" är samma barnfynd-mönster som
`SAMLARORD`-regexet (`InsightTaxonomy.java` rad 228-230) ursprungligen skrevs för ("Volvo 240
gömd i 44 år") - men texten säger "hittades efter 24 år" i stället för "gömd i 24 år" och träffar
därför inte `gömd i \d+ år`. Modellen "850" finns heller inte i `UTGANGNA_MODELLER`. **Jag lagar
INTE detta i natt** - en enda rad är inte nog för att mäta mot hela tabellen som koden själv kräver
(se klasskommentarerna), och jag har inte verifierat 850 R:s exakta sista tillverkningsår. Förslag
till användaren: om fler barnfynd-rader dyker upp med annan fras än "gömd i", bredda `SAMLARORD`
eller lägg till `volvo 850` i `UTGANGNA_MODELLER` (kräver verifierat slutår).

**Tre regler:** SKATTER - inget underlag. RENOVERINGSOBJEKT - inget underlag (2038 diskuteras ovan
som ett veteran-/barnfyndsfall, inte en skatterad). AVVECKLADE MODELLER ÄR RELEVANTA - inget
underlag, ingen överblockering sedd.

**Observation att hålla koll på:** Teknikens Värld (0 av 2), Vi Bilägare (0 av 1) gav 0 nya rader
i natt trots lästa artiklar. En natt är inte bevis (regeln kräver två i rad) och gårdagens
per-källa-siffror finns inte loggade för jämförelse - håll koll imorgon.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kommandevakten (annonskollen):** 0 LARM, 8 GRANSKA, 1 ANNAN_DRIVLINA, 19 INGA_ANNONSER på 57
rader / 28 bilar (ner från 63/29 i baslinjen - gårdagens 6 BMW iX4-släpp förklarar skillnaden).
Samtliga tidigare dokumenterade fall oförändrade (Bayon-tvillingarna, Tucson gen 5, Mazda 6e,
Pajero, Santa Fe, Range Rover 1578, BMW i3). **Nattens 3 nya köade bilar** (BYD Seagull 2026/2028/
2029, MG Go! 2025/2027, Honda 0 Alpha 2024) samt BMW X5 M60e xDrive (2021, inte en ny köad bil men
nytt radinnehåll) - alla fyra saknas i `/api/ev-spec` och `/api/cars`, alltså (a) ännu inte
sluppna, korrekt INGA_ANNONSER, inget beslut krävs än.

**Dolda/raderade:** **1940** (Mercedes-Maybach S 680) har nu verkligen varit dold **8 dygn**
(sedan 10-17) - gårdagens raderingsförsök avvisades av appen eftersom det i själva verket bara var
6 dygn då (nattens räkning 10-24 var fel, +1 dag för tidigt). Fortsatt bedömd som skräp enligt
samma regel (engångsbeställning åt en namngiven kändis, ingen generell köpinformation) - **radera
i natt** (se atgarder.json). 2006 (Kia EV5, 2 dygn) och 1987 (Volvo V60 CC, 3 dygn) har inte nått
gränsen än.

**Laddtips:** inga nya i natt. `ev-fact-candidates` gav 9 kandidater, men alla var redan skrivna
eller redan uteslutna: **2015** (Renault Megane E-Tech) och **2002** (Skoda Kodiaq iV) skrevs
redan 10-24. **1999/1998** (Skoda Peaq 90) är dubbletter av redan skrivet tips (10-23). **2006**
(Kia EV5) är samma felattribuerade siffra som redan dolts i natt. **2031/2030** (Tesla Model S,
Björn Nyland-test) och **1988** (Tesla Model S P85) är ensamma batterihälsotester utan
spec-tabell att stämma av mot - samma skäl som 1988 uteslöts redan 10-24. **2005** (Skoda Kodiaq
iV, poäng 1) bygger på "Skodas internationella material" - för vagt och lågt poängsatt för att
skriva som fakta.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK, CarAdvice-raden visar autodeploy
master · 8d9fed8 (matchar /api/version). Samma INFO-rader som tidigare (Elbilsladdnings
roaming-prisreserver m.m.), inget nytt.

**Veckan (söndag):**
- Nattens domar senaste 7 nätterna: 10-18 KOLLA, 10-19 **GRONT** (rutinens egen dom, men
  körningen slutade efter rapporten och tappade baslinjen/mandagspriserna den natten - sedan
  lagat, se 2b72f65), 10-20 KOLLA, 10-21 KOLLA, 10-22 KOLLA, 10-23 KOLLA, 10-24 KOLLA, 10-25 (i
  natt) KOLLA.
- Morgonfixar: 10-18 (ingen kodfix, låste 4 Tucson-rader), 10-20 (baslinje + laddtips Audi A5
  Avant e-hybrid), 10-21 (baslinje + kobeslut Polestar 4 Rear, TESTBEVISREGELN), 10-22 (3 kobeslut,
  1 dolj, 1 laddtips), 10-23 (kobeslut 10 parkera/2 radera, 1 laddtips), 10-24 (kobeslut 6 slapp/1
  radera, 2 laddtips). Inga reverts i veckan.
- Regelförändring i docs/nattrutin.md: 2b72f65 (10-19) - rutinen hoppar inte längre över fas 2 på
  gröna nätter, lagad efter samma natts incident.
- Veckans databasbeslut (10-18 till 10-24 ur GET /api/admin/morgonfix-atgarder): **15 parkerade,
  10 släppta, 2 dolda, 2 raderade, 1 "ej raderad"** (1940, räknefelet ovan - rättas i natt).
- **Väntar på dig:** inget formellt blockerat denna vecka (alla PR:er auto-mergades, inga LARM).
  Den enda öppna punkten är veteranvakt-luckan ovan (id 2038) - en förslagspunkt, inte ett larm.

**Testat:** `mvn -q -Dtest=LaddtipsServiceTest,MorgonfixAtgarderTest test` grönt (exitkod 0,
`verkligaFilenArGiltig` godkänner den enda raden - 1 radera under taket 20). `mvn test` (hela
sviten) grönt, exitkod 0 - WARN/ERROR-raderna i loggen är samma avsiktliga testfixturer som
tidigare nätter (ogiltiga atgarder-rader, HTML-injektion, DB nere). `mvn -q -DskipTests package`
grönt.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning 3438/3438
GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` (grenen fanns inte sedan
föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-24

**DOM: KOLLA** - inget trasigt (hälsa OK, kedjan gick 01:39:27 inom fönstret, Groq 3/3, AVVIKER 0
i vPIC, drivmedel oförändrat 508/423/85/16, ad-check 0 LARM), men en ARSMODELLSREGEL-regression i
kommandevakten (BMW iX4, se nedan) krävde ett kobeslut och en dold rad nådde 7-dygnsgränsen.

**Ingen kodfix i natt** - inget belagt kodfel hittades. Tre filer ändrade, alla datafiler (räknas
inte mot femfilstaket): `docs/baslinje.json` (mat-baslinje.js, kontrollräkning 3438/3438 går
ihop), `src/main/resources/morgonfix/atgarder.json` (kobeslut, se nedan) och
`src/main/resources/morgonfix/laddtips.json` (två nya tips, se nedan).

**Grundkollen:** lastScrapeFinishedAt 01:39:27 (inom väntat fönster 01:15-01:50 sommartid - sista
natten innan vintertid), deployad commit 1bdfd11 matchar origin/master, status OK, ingen omstart
(uptime ~11,2 h). ev-specs updated **5** (inom 0-25, kollisioner 182, evSpecs 654→654 still).
cargo-specs gav **9/2/0** (9 nya bilnamn, 2 av 553 bagagevolymer, 0 av 0 generationsår kvar) - N
inom 2-20, inget larmmönster. Kontrollräkningen (cargo total 1824 + evSpecs 654 + ice_consumption
960 = 3438 = /api/stats variants) stämmer exakt. /api/cars 2079→2088 (+9, matchar nya bilnamn).

**BAGAGELARMET (3f) - medVolym 1035→1037 (+2), bagageMissar 290→438 (+148).** Summan (+150)
träffar exakt samma nattliga tak som återuppbyggnaden sedan 10-21 (cargo_spec_miss tömd för
hand). medVolym UPPÅT = OK enligt domregeln oavsett bagageMissar. Fortsätt bevaka tills kurvan
planar ut.

**Generationsåren:** total 291→291, missar 19→19 (291+19=310, stämmer). Samtliga 19 missar har nu
forsöktDag **2026-10-21** (omprövade när 30-dagarsfönstret öppnade igen efter 10-20) och gav
samma friska fördelning 16 vakten-avstod / 3 ej-hittad igen - det väntade utfallet enligt 3a.

**vPIC:** 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt. **Drivmedelsräknaren:** 508/423/85/16, identiskt med baslinjen - alla 16
handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip. **Kategorivakten:** 1 utslag sedan
omstart (uptime ~11,2 h) - Tesla Model 3 (2016, Vi Bilägare) klassades fel som `smaabil` och
kategorin ströks korrekt ("tesla model 3 är ingen småbil") - känt mönster sedan 09-19, vakten
gjorde sitt jobb, ingen ny läcka.

**Nattens 15 nya rader (id 2015-2029):** 15 av 15 godtagbara, inga kategorifel, inga skatterader,
renoveringsobjekt eller veterandrag. Fyra rader (2016 Tesla Model 3, 2017 Tesla Model Y, 2018
Ford Mustang Mach-E, 2019 Volvo EX60 "50 000 beställningar") är tunna/opinionsbaserade
andrahandsclaims utan hård mätdata - ingen regelbrott, men svaga; noterat, inte föreslagna för
åtgärd. Värdeminskning/tillförlitlighet/livslängd: 1 rad i natt (2023, Skoda Enyaq, adaptiv
farthållare som ökade hastigheten) - frågan fortsatt besvarad (inte längre oprövad sedan flera
nätter). Tre regler (skatter/renoveringsobjekt/avvecklade modeller): inget underlag i natt.

**Marknadsregeln:** inget nytt märke att kontrollera i natt - BYD, MG och Honda säljs redan i
Sverige (bara de tre nya kommande-modellerna - Seagull, Go!, 0 Alpha - är okända där ännu, se
nedan).

**Kommandevakten (annonskollen):** 0 LARM, 8 GRANSKA, 1 ANNAN_DRIVLINA, 20 INGA_ANNONSER på 63
rader / 29 bilar. Samtliga tidigare dokumenterade fall oförändrade (Bayon-tvillingarna, Tucson
gen 5, Mazda 6e, Pajero, Santa Fe, Range Rover 1578, BMW i3). **Nattens 3 nya köade bilar** (BYD
Seagull 2026/2028/2029, MG Go! 2025/2027, Honda 0 Alpha 2024) - alla (a) ännu inte sluppna,
korrekt INGA_ANNONSER, inget beslut krävs än.

**FYND - ARSMODELLSREGEL-REGRESSION (BMW iX4, id 1815-1820):** nattens insikt **2004**
("Svenska priser för BMW iX4 börjar på 749 900 kr för iX4 40 xDrive och 809 900 kr för iX4 50
xDrive", scrapad natten mot 10-23 men inte korskollad mot kön då) bevisar att bilen nu är
beställningsbar i Sverige. Bekräftat mot `/api/ev-spec?car=BMW%20iX4` (897 000 kr, EV, 828 km
WLTP), mot cargo_spec-drivmedel (`bmw ix4 40 xdrive`/`50 xdrive`: el) och mot `/api/cars`. De sex
köade raderna (1815, olåst parkerad 10-08, samt tvillingarna 1816-1820) är därmed en regression
enligt ARSMODELLSREGELN ("ett utskrivet svenskt pris... parkeras en sådan rad igen är det en
regression"). **Släppta i natt** (se atgarder.json), källa: insikt 2004 + ev_spec + cargo_spec +
/api/cars.

**Dolda/raderade:** uppföljning av dolda rader - **1940** (Mercedes-Maybach S 680, dold 10-17,
exakt 7 dygn) fortsatt bedömd som skräp (engångsbeställning åt namngiven kändis, ingen generell
köpinfo) → **raderad i natt** (se atgarder.json). 2006 (1 dygn) och 1987 (2 dygn) har inte nått
gränsen än.

**Laddtips:** 2 nya (se `laddtips.json`). `ev-fact-candidates` gav flera kandidater: **2015
Renault Megane E-Tech** (poäng 4, laddeffekt 130→165 kW) - stämt mot `/api/ev-spec?car=Renault
Megane E-Tech` (maxDcKw 165, exakt match) - SKREVS. **2002 Skoda Kodiaq iV** (poäng 3, 25,7 kWh/50
kW) - stämt mot `/api/ev-spec?car=Skoda Kodiaq iV` (batteryKwh 25.7, maxDcKw 50, exakt match) -
SKREVS. **2006 Kia EV5** (redan doljd i natt, se ovan) uteslöts - samma AI-extraktionsfel som
Puma Gen-E 08-18. **1998/1999 Skoda Peaq 90** uteslöts - redan tipsat 10-23 (insikt 1999),
dubblett. **1988 Tesla Model S P85** (batterihälsa, poäng 3) uteslöts - ett ensamt test utan
spec-tabell att stämma av mot, för osäkert att skriva som fakta.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK (nu även Bränslekostnad med i
skriptet), CarAdvice-raden visar autodeploy master · 1bdfd11 (matchar /api/version). Samma
INFO-rader som tidigare, inget nytt.

**Testat:** `mvn -q -Dtest=LaddtipsServiceTest,MorgonfixAtgarderTest test` grönt (exitkod 0,
`verkligaFilenArGiltig` godkänner båda filerna - 7 slapp/radera-rader under taket 12/20). `mvn
test` (hela sviten) grönt, exitkod 0 - WARN/ERROR-raderna i loggen är testfixturer som avsiktligt
övar felvägar (ogiltiga atgarder-rader, HTML-injektion, DB nere). `mvn -q -DskipTests package`
grönt.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning 3438/3438
GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` (grenen fanns inte sedan
föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-23

**DOM: KOLLA** - inget trasigt (hälsa OK, Groq 3/3, AVVIKER 0 i vPIC, drivmedel oförändrat
508/423/85/16, ad-check 0 LARM), men nattens 17 rader krävde dom och kön hade flera sedan länge
olåsta GRANSKA/INGA_ANNONSER-rader värda att formalisera.

**Ingen kodfix i natt** - inget belagt kodfel hittades. Tre filer ändrade, alla datafiler (räknas
inte mot femfilstaket): `docs/baslinje.json` (mat-baslinje.js, kontrollräkning 3429/3429 går
ihop), `src/main/resources/morgonfix/atgarder.json` (kobeslut, se nedan) och
`src/main/resources/morgonfix/laddtips.json` (ett nytt tips, se nedan).

**Grundkollen:** lastScrapeFinishedAt 01:42:14 (inom väntat fönster 01:15-01:50 sommartid),
deployad commit 96d2283 matchar origin/master, status OK, ingen omstart (uptime ~10,6 h vid
grundkollen). ev-specs updated **5** (inom 0-25, kollisioner 182, evSpecs still 654→654). cargo-
specs gav **6/7/0** (6 nya bilnamn, 7 av 701 bagagevolymer, 0 av 0 generationsår kvar) - OK.
Kontrollräkningen (cargo total 1815 + evSpecs 654 + ice_consumption 960 = 3429 = /api/stats
variants) stämmer exakt.

**BAGAGELARMET (3f) - medVolym 1028→1035 (+7), bagageMissar 147→290 (+143).** Summan (+150)
träffar exakt samma nattliga tak som själva svepet i september (62+88 och 61+89 = 150 båda
nätterna). Loggboken 10-21/10-22 visar att `cargo_spec_miss` tömdes för hand 10-21 och byggs upp
från 0 igen - detta är alltså fortsättningen av samma återuppbyggnad, inte en ny avvikelse.
medVolym UPPÅT = OK enligt domregeln oavsett bagageMissar. Värt att fortsätta bevaka de närmaste
nätterna tills kurvan planar ut.

**Generationsåren:** total 291→291, missar 19→19 (291+19=310, stämmer). Samtliga 19 missar har
forsöktDag 2026-10-21 (omprövade när 30-dagarsfönstret öppnade igen) och fördelningen är tillbaka
till det friska mönstret 16 vakten-avstod / 3 ej-hittad - bekräftar att generationsårsregressionen
(fe8f9f9, lagad 10-21/10-22) håller.

**vPIC:** 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt. **Drivmedelsräknaren:** 508/423/85/16, identiskt med baslinjen - alla 16
handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip. **Kategorivakten:** 1 utslag sedan
omstart (uptime ~10,6 h) - Cupra Born (2003, återkallelse-raden) klassades fel som `smaabil` och
kategorin ströks korrekt ("cupra born är ingen småbil"); vakten gjorde sitt jobb, ingen läcka.

**Nattens 17 nya rader (id 1998-2014):** 17 av 17 bra. Inga kategorifel, inga skatterader,
renoveringsobjekt eller veterandrag. **Skoda Peaq 90** (1998-2000, Teknikens Värld) och **Skoda
Kodiaq/Kodiaq iV** (2001-2002, 2005) är nya modeller men båda redan beställningsbara i Sverige
(Peaq 90: dealerpriser på bilweb.se; Kodiaq iV: begagnade annonser med 2026 års modell på
minbilkoll.se) - ingen av dem hör till kommande-kön, ingen PROMPTMISS. **Värdeminskning/
tillförlitlighet/livslängd:** 1 rad i natt (2007, Saab 900 Aero, 161 000 mil på samma turbomotor)
- fortfarande en öppen fråga generellt (regeln är oprövad), men natten gav äntligen en träff.

**Tre regler:** SKATTER - inget underlag. RENOVERINGSOBJEKT - inget underlag. AVVECKLADE MODELLER
ÄR RELEVANTA - inget underlag i natt (Saab 900 Aero är en avvecklad modell men raden handlar om
tillförlitlighet, inte om avveckling i sig).

**Kommandevakten (annonskollen):** 0 LARM, 8 GRANSKA, 1 ANNAN_DRIVLINA, 16 INGA_ANNONSER på 56
rader / 25 bilar (ned från baslinjens 58/27 - förklaras av gårdagens släpp av 1788/1991).
Samtliga tidigare dokumenterade fall oförändrade (Bayon-tvillingarna, Tucson gen 5, Mazda 6e,
Pajero, Santa Fe, Range Rover 1578 - redan låsta eller dokumenterade). **Formaliserade i natt**
(se atgarder.json) - länge dokumenterade men aldrig låsta GRANSKA/INGA_ANNONSER-rader:
- **parkera 1713** (Range Rover Sport) - raden säger själv "blir elbil", gäller kommande Sport
  Electric, inte dagens 46-annonsers generation. Olåst sedan 10-01.
- **parkera 1714, 1717, 1718, 1719, 1720** (Range Rover Sport Electric) - INGA_ANNONSER, 0
  Blocket-annonser under namnet. Olåst sedan 10-01.
- **parkera 1573** (Volvo XC70 laddhybrid, Kina) - samma bil och skäl som redan parkerade 1986
  (10-22). Olåst sedan 09-18.
- **parkera 1574, 1575** (Mercedes C-klass Electric) - INGA_ANNONSER, samma bil som redan
  parkerade 1902 (PROMPTMISS-tvilling, 10-15). Olåst sedan 09-18. **1576, 1577 är samma klunga
  men ryms inte under taket i natt - över taket, kvar till i morgon.** *Tillägg 10-23 08:33
  (manuell statuskoll): 1576 + 1577 parkerade för hand via admin-POST, låsta i drift - inget
  kvar av klungan.*
- **parkera 1622** (BMW iX5 Hydrogen) - vätgas-forskningsbil, INGA_ANNONSER. Olåst sedan 09-23.

**Marknadsregeln:** inget nytt märke att kontrollera i natt - alla märken i nattens rader säljs i
Sverige.

**Dolda/raderade:** uppföljning av dolda rader - **1912** (Smart "??", dold 10-15, 8 dygn) och
**1910** (VW Golf-anekdot, dold 10-15, 8 dygn) har båda nått 7-dygnsgränsen och bedöms fortsatt
som skräp enligt samma regel → **raderade i natt** (se atgarder.json). 1987 (1 dygn) och 1940 (6
dygn) har inte nått gränsen än.

**Laddtips:** ett nytt tips skrivet (se `laddtips.json`): **Skoda Peaq 90** snabbladdning 199 kW
DC (10-80 % på 28 min) - stämt mot `/api/ev-spec?car=Skoda%20Peaq%2090` (maxDcKw 199, exakt
match). Två kandidater avvisade efter kontroll: **Kia EV5** (id 2006, "113 kWh-batteri, 400 kW")
motsäger ev-spec rejält (60,3 kWh, 150 kW DC) - samma typ av AI-extraktionsfel som Puma Gen-E
08-18, skrivs inte. **Skoda Peaq 90 Cw-värde/förbrukning** (id 1998, "1,5 kWh/mil enligt WLTP")
ger vid omräkning (86 kWh / 64,7 mil) 1,33 kWh/mil - en ~13 % avvikelse, för stor för att belägga.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK, CarAdvice-raden visar autodeploy
master · 96d2283 (matchar /api/version). Samma INFO-rader hos Elbilsladdning som tidigare.

**Testat:** `mvn -q -Dtest=LaddtipsServiceTest,MorgonfixAtgarderTest test` grönt (12/12 rader i
atgarder.json, `verkligaFilenArGiltig` godkänner båda filerna). `mvn test` (hela sviten) grönt,
`MVN_EXIT=0` - ERROR-raderna i loggen är testfixturer som avsiktligt övar felvägar (DB nere, Groq
nere, Bilweb nere). `mvn -q -DskipTests package` grönt.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning 3429/3429
GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` enligt 9a (grenen fanns inte
sedan föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-22

**DOM: KOLLA** - inget trasigt (hälsa OK, Groq 3/3, AVVIKER 0 i vPIC, ad-check 0 LARM, inga tal
föll), men tre kobeslut och en döljning krävde omdöme, och Vi Bilägare timeout-ade en gång.

**Ingen kodfix i natt** - inget belagt kodfel hittades. Tre filer ändrade, alla datafiler (räknas
inte mot femfilstaket): `docs/baslinje.json` (mat-baslinje.js, kontrollräkning 3423/3423 går
ihop), `src/main/resources/morgonfix/atgarder.json` (kobeslut, se nedan) och
`src/main/resources/morgonfix/laddtips.json` (ett nytt tips, se nedan).

**BAGAGELARMET (3f) - FALLET FRÅN 831 TILL 147 ÄR INTE EN AVVIKELSE:** enligt loggboken 10-21
tömdes `cargo_spec_miss` (831 rader) och `ice_generation_miss` (19 rader) för hand efter
generationsårsregressionen lagades (`fe8f9f9`). Talet bygger nu upp på nytt från 0 - precis som
loggen förutspådde. medVolym steg samtidigt (1023→1028), vilket är det friska tecknet enligt
domregeln. **Generationsårsregressionen är bekräftat lagad:** missar-listan visar nu 16
vakten-avstod / 3 ej-hittad igen (det friska mönstret, var 19/0 ej-hittad igår), forsöktDag
2026-10-21 - selektorfixen i `fe8f9f9` fungerar mot auto-data.net.

**Kobeslut i natt (3 rader, se `atgarder.json`):**
- **slapp 1991** (Subaru Solterra) - säljs redan i Sverige (ev_spec 534 900 kr, cargo_spec
  el), raden gäller EU:s modellutbud 2028, inte en ny generation.
- **slapp 1788** (BMW iX5) - ev_spec har redan ett utskrivet pris (1 215 000 kr) och
  annonskollen hittade en Blocket-annons med eget svenskt pris (1 175 900 kr, 2027, 0 mil).
  Omprövning av en tidigare parkering (10-06) i ljuset av ny annonsdata - ARSMODELLSREGELN.
- **parkera 1986** (Volvo XC70 laddhybrid) - finns bara i Kina, Volvo "överväger" Europa.
  Varken ev_spec eller cargo_spec har en el-variant av XC70.
- **dolj 1987** (Volvo V60 Cross Country) - anekdot om två lotterivinnares privata omdöme,
  ingen mätbar uppgift (ovriga kända läcktyper, avsnitt 6).

**Nytt laddtips (1 st, se `laddtips.json`):** Volkswagen ID. Polo, 37 kWh/334 km WLTP - stämt
mot `/api/ev-spec?car=Volkswagen%20ID.%20Polo` (37,5 kWh / 334 km, matchar). Två andra kandidater
avvisades efter kontroll: **VW ID. Cross** instegsvariant (37 kWh/324 km) hittar ingen matchande
rad i ev_spec (bara 51,7 kWh/52 kWh-varianter) - gick inte att belägga. **Audi A3 Sportback
e-hybrid** (kandidat-id 1969, "141 km elektrisk räckvidd, 499 800 kr") motsäger ev_spec rejält
(67 km, 420 000 kr) - troligen samma typ av AI-extraktionsfel som Puma Gen-E 08-18, skrivs inte.

**Testat:** `mvn -q -Dtest=LaddtipsServiceTest,MorgonfixAtgarderTest test` - 7/7 och 11/11 gröna,
`verkligaFilenArGiltig` godkänner båda filerna. `mvn -q -DskipTests package` grönt.

## 2026-10-21

**Efter rapporten (för hand, 10-21 förmiddag) - läs detta först:**
- Generationsregressionen nedan är LAGAD i `fe8f9f9`: auto-data.net gör nu varje generation till
  `<a class="generation">` direkt under `#generr`, och generationssidans motorer till
  `<a class="modification">`. Båda gamla selektorerna gav noll träffar (motorlistan också,
  fast det syntes inte i rapporten). Prov mot tre färska fixturer, 1252 tester.
- **Båda misstabellerna TÖMDA** efter deployen (`DELETE .../ice-generations/missar` 19,
  `DELETE .../cargo-specs/missar` 831). **bagageMissar som faller från 831 natten mot 10-22 är
  tömningen, INTE en avvikelse enligt 3f** - talet byggs upp på nytt från 0. Detsamma gäller
  generationsårens missar (19 -> nytt tal). Väntat: färre ej-hittad, inga "noll generationer"
  i /api/admin/logg.
- **Honda Prelude 1962 SLÄPPT** på användarens beslut (`7545726`, utförd 08:13).

**DOM: KOLLA** - inget akut trasigt (alla statusar OK, 0 LARM, AVVIKER 0 i vPIC, ingen flip i
drivmedel), men generationsårsvakten (3a) visar en trolig parserregression (se nedan), och en
ny kommande-rad krävde ett kobeslut.

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:36:19, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (6a891eb), status OK, ingen omstart
i natt (uptime ~23,6 h vid grundkollen). Groq 3/3 modeller (friskt, HTTP 200, exakt referensen).
ev-specs updated **3** - inom 0-25 (kollisioner 182, evSpecs still 650→650, väntat). cargo-specs
gav **1/0/0** (1 nytt bilnamn, 0 av 1 bagagevolymer kvar, 0 av 19 generationsår kvar) - status OK.
Kontrollräkningen (cargo-coverage total 1806 + evSpecs 650 + ice_consumption 960 = 3416 =
/api/stats variants) stämmer exakt, bekräftat av mat-baslinje.js (3416/3416).

**GENERATIONSÅRSVAKTEN (3a) - TROLIG PARSERREGRESSION, inget belagt fix möjligt härifrån:**
30-dagarsfönstret stängde 2026-10-20 och alla 19 missar provades om i natt (forsoktDag nu
2026-10-20, var 2026-09-20). Resultatet: **samtliga 19 är nu `ej-hittad`** - innan omprövningen
var 16 av 19 `vakten-avstod` (medvetet avstående, friskt) och bara 3 `ej-hittad` enligt
nattrutin.md:124 ("BMW 316d avstår fortfarande" stod som en STÄNGD fråga, dvs vakten-avstod).
Render-loggen bekräftar: `AutoDataScraperService "auto-data: modellsidan gav noll generationer —
struktur ändrad?"` loggades **exakt 19 gånger** i natt (01:18:16-01:18:55), en per försök. BMW
316d - uttryckligen dokumenterad som vakten-avstod-fallet - är nu `ej-hittad` i stället, vilket är
en konkret regression mot ett tidigare stängt fall. Enligt regeln i 3a ("Domineras missarna av
ej-hittad är UPPSLAGET trasigt - inte auto-data som saknar bilarna") pekar detta mot att
`AutoDataScraperService.parseGenerationer`s selektor (`#generr > div` / `a[href*=-generation-]`
mot auto-data.net) har slutat träffa. **Kunde inte verifieras eller lagas härifrån:**
www.auto-data.net är inte i molnmiljöns Allowed domains (anropet avvisades av egress-proxyn,
connect_rejected) - jag kan inte se sidans nuvarande HTML för att bekräfta eller skriva ett
rött-innan/grönt-efter-prov. **Total (291) och missar (19) har inte FALLIT** - ingen felaktig
data visas, bara fler tomma fält än väntat. Lämnas som förslag: en framtida körning med
nätåtkomst till auto-data.net bör kontrollera selektorn mot en känd modell (t.ex. BMW 316d).

Generationsåren (samma jobb som cargo-specs): total 291→291, missar 19→19 (291+19=310, stämmer).
vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar
referensen exakt. Drivmedelsräknaren oförändrad 506/421/85/16 - alla 16 handsatta rader (11 el +
5 ice) fortsatt korrekta, ingen flip (kontrollerat programmatiskt mot kända EV-markörer, inga
äkta träffar). Kategorivakten: 0 utslag sedan omstart (uptime ~23,6 h).

**Bagagelarmet (3f):** medVolym still (1023→1023) + bagageMissar VUXIT (830→831) = **VÄNTAT**,
inte avvikelse (cargo-specs KB var 1, den ena bilen gav en miss, inte en volym).

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sex tjänster OK, CarAdvice-raden visar autodeploy
master · 6a891eb (matchar /api/version). Samma kända INFO-rader hos Elbilsladdning som tidigare.

**Nattens 18 nya rader (id 1957-1974):** 16 av 18 bra. Två tunna utan angivet utfall - **1967**
(BMW IX3, "ingick i en provkörning där den jämfördes med andra premium-SUV-modeller" - säger
inget om resultatet) och **1968** (Mercedes GLC 400, samma mönster, "testades ... tillsammans
med andra lyx-SUV-modeller"). Inga kategorifel (kategorivakten 0 utslag), inga skatterader,
renoveringsobjekt eller samlar-/veteranträffar. Värdeminskning/tillförlitlighet/livslängd/
skrotålder: **0 rader i natt** - fortsatt öppen fråga, inte ett friskhetstecken.

**Tre regler:** SKATTER - inget underlag i natt. RENOVERINGSOBJEKT - inget underlag. AVVECKLADE
MODELLER ÄR RELEVANTA - 1974 (Polestar 2, produktionen nedlagd, säljs bara som lagerbil) är
exemplet i natt: raden behölls med fylld kategori/drivmedel och ligger INTE i kommande-kön -
korrekt hantering, ingen överblockering.

**Kommandevakten (annonskollen):** 0 LARM, 10 GRANSKA, 1 ANNAN_DRIVLINA, 17 INGA_ANNONSER på 58
rader / 28 bilar (upp från baslinjens 56/26). Alla tidigare dokumenterade fall oförändrade
(Bayon-tvillingarna, Tucson gen 5, Range Rover 1578/1713, Mazda 6e, Pajero, Santa Fe, XC70, BMW
i3/iX5 - samtliga redan låsta eller dokumenterade sedan tidigare nätter). **Ny GRANSKA-rad:
Honda Prelude (1962)** - Auto Motor & Sport skriver om "Prelude Limited Edition" (e:HEV-hybrid,
S+ Shift), annonskollen hittar bara gamla JDM-Preludes (1984-1993) i Sverige. Ingen namngiven
källa bekräftar svensk säljstart eller pris för den nya generationen - **lämnad oavgjord, ingen
rad i atgarder.json**, flaggas för nästa natt.

**Kobeslut i natt (1 rad, se `atgarder.json`):** **slapp 1973** (Polestar 4 Rear, bakhjulsdriven)
- TESTBEVISREGELN: Allt om Elbil ger ett eget uppmätt studievärde (67,7 poäng prestanda, 77,9
poäng körupplevelse av 100), vilket bevisar att varianten testats/finns i handeln. Korroboreras
av nattens egna rader 1963-1965 (Auto Motor & Sport) som jämför just denna RWD-variant mot AWD.
Mercedes GLC 400 (1968) självsläpptes redan av UpcomingAutoReleaseService 01:36:23 i natt (35
annonser, "raden säger inget om framtiden") - ingen åtgärd behövdes.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Dolda/raderade:** inga nya döljningar i natt. Uppföljning av befintliga dolda rader: 1940 (4
dygn), 1910/1912 (6 dygn) - ingen har nått 7 dygn än, ingen kvalificerar för radera.

**Laddtips:** inget nytt tips skrivet i natt. Toppkandidaterna gick inte att belägga mot
`/api/ev-spec?car=...`: **1966** (VW ID. Cross, "ny instegsvariant" 37 kWh/324 km/90 kW) matchar
INTE ev-spec-raden för ID. Cross (51,7 kWh/427 km/105 kW DC - annan, redan känd trim). **1961**
(Volvo EX60, Edmunds EPA-test 50,2 mil) är en amerikansk EPA-siffra, inte jämförbar med vår
WLTP-baserade ev-spec (810 km). **1957/1958** (VW ID. Polo instegsvariant) - ev-spec ger 299 000
kr för 37,5 kWh/334 km, men insikt 1957 påstår 320 800 kr för samma batteristorlek - en
diskrepans på ~7 % som inte går att förklara. **1929** (Audi A6 e-tron performance) är redan
tipset från 10-15. Ingen kandidat kvar att skriva - fyller inte på för att fylla.

**Ingen kodfix i natt** - generationsårsregressionen (ovan) saknar en entydig, verifierbar orsak
härifrån (auto-data.net är inte nåbart i molnmiljön). Inget annat namngivet vakthål, parserfel
eller belagd datarad att rätta. `mvn test` (hela sviten, Java 27) gick grönt - `MVN_EXIT=0`,
inga testfel (WARN-raderna i loggen är testfixturer som avsiktligt övar felvägar, t.ex. "DB nere"
och ogiltig atgarder.json-fixtur). `MorgonfixAtgarderTest` kört separat, grönt.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3416/3416 GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` enligt 9a (grenen fanns inte
sedan föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-20

**DOM: KOLLA** - inget trasigt (alla statusar OK, 0 LARM, AVVIKER 0, ingen flip i drivmedel),
men flera öppna frågor kräver ett öga: rating fortsatt null på alla rader, en WARN i cargo-specs
om "struktur ändrad?", och en LeBron James-rad som visade sig redan dold sedan 10-17 (ingen ny
åtgärd, bara dubbelkollad).

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:34:50, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (2b72f65), status OK, ingen
omstart i natt (uptime ~18,1 h vid grundkollen). Groq 3/3 modeller (friskt, HTTP 200, exakt
referensen). ev-specs updated **3** - inom 0-25 (kollisioner 182, evSpecs still 650→650,
väntat). cargo-specs gav **2/0/2** (2 nya bilnamn, 0 av 2 bagagevolymer kvar, 0 av 0
generationsår kvar) - status OK, inget över 50-taket. Kontrollräkningen (cargo-coverage total
1805 + evSpecs 650 + ice_consumption 960 = 3415 = /api/stats variants) stämmer exakt, bekräftat
av mat-baslinje.js.

**AutoDataScraperService-WARN ("modellsidan gav noll generationer — struktur ändrad?") x2** -
samma mönster och samma antal som natten till 10-17 (där det bedömdes konsekvent med
generationsårens KG=0 kvar, inte ett strukturbrott). Ingen kodfix - ingen namngiven modell att
peka ut, och läget är oförändrat mot ett redan dokumenterat, återkommande WARN.

**Baslinjen (2026-10-19) jämförd mot i natt:** models 2276→2278 (+2, matchar cargo-specs nya
bilnamn), variants 3413→3415, insights 1486→1489 (+3, matchar id-intervallet 1954-1956 exakt).
cargo total 1803→1805 (medVolym still 1023, bagageMissar 828→830 - domregeln 3f: still+vuxit =
VANTAT, OK). evPowers still 540→540 (väntat, evSpecs still). apiCars 2063→2065. Inget tal har
FALLIT. Generationsåren still (291/19, fönstret stänger just i dag 2026-10-20; forsoktDag
fortfarande 2026-09-20 - håll koll i morgon om den inte rört sig). vPIC: 291/0/275, OK 126,
INGEN_DATA 165, **AVVIKER 0** - matchar referensen exakt. Drivmedelsräknaren oförändrad
506/421/85/16 - alla 16 handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip.
Kategorivakten: 0 utslag sedan omstart (uptime ~18 h).

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sex tjänster OK, CarAdvice-raden visar autodeploy
master · 2b72f65 (matchar /api/version).

**Kommandevakten (annonskollen): 0 LARM, 9 GRANSKA, 1 ANNAN_DRIVLINA, 16 INGA_ANNONSER på 56
rader / 26 bilar** - oförändrat mot baslinjen (56/26). Ingen ny köbeslut i natt.
**Observation:** extravakten [Auto Motor & Sport/kommande] parkerade nattens egna rad 1956 (Audi
A6 e-tron, jämförelsetest) i kön av misstag (01:25), men UpcomingAutoReleaseService släppte den
korrekt samma natt (01:34, "46 annonser och raden säger inget om framtiden") - självrättat,
ingen åtgärd behövdes. De olåsta GRANSKA-raderna (Range Rover Sport 1713, Volvo XC70 1573, Mazda
6e 1560/1551, Mitsubishi Pajero 1414/1412, Hyundai Santa Fe 1368) är samma redan dokumenterade
("ingen ny källa att belägga en låsning med") läge som 10-18 - lämnade olåsta igen.

**Nattens 3 nya rader (id 1954-1956):** 1954 (VW ID.Buzz, högspänningssäkring utlöst vid
Ionity-laddare i Elverum) och 1955 (Toyota Land Cruiser, ytrost bekräftat normalt fenomen) är
sakliga tillförlitlighets-/driftfakta, inga kategorifel. 1956 (Audi A6 e-tron, jämförelsetest mot
Volvo ES90/BMW i5) är tunn - säger att ett test finns utan att säga vad det visade - men
dubbleras inte av 1936 som redan ger testets faktiska kritik; ingen skräprad, bara svag. Inget
kategorifel, inga skatterader, renoveringsobjekt eller samlar-/veteranträffar. Värdeminskning/
tillförlitlighet/livslängd/skrotålder: **1 relevant rad** (1937, Tesla Model S, norska Nettbil -
marknadsangivelse finns, så ingen regel bruten).

**LeBron James-raden (1940, Mercedes-Maybach S 680) dubbelkollad:** redan dold sedan 2026-10-17
("engångsbeställning, ingen generell köpinformation", användarens eget beslut) - 3 dygn gammal,
inte 7 än. Ingen ny åtgärd. Samma gäller 1910 (VW Golf, 5 dygn) och 1912 (Smart "??", 5 dygn) -
inga kvalificerar för radera än.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Laddtips:** ett nytt tips skrivet (`src/main/resources/morgonfix/laddtips.json`) - Audi A5
Avant e-hybrid quattro saknar DC-snabbladdning helt (verifierat mot `/api/ev-spec?car=...`:
maxDcKw 0, maxAcKw 11, batteryKwh 25,9 - exakt mot insikt 1909). Toppkandidaten i
ev-fact-candidates (1929, Audi A6 e-tron performance) är en dublett av tipset från 10-15 och
hoppades över. VW ID. Tiguan-kandidaterna (1903, 1922, 1921, 1924) hoppades också över - de
angivna siffrorna per utrustningsnivå (596 km/606 900 kr respektive 450 km/559 900 kr) gick inte
att belägga mot `/api/ev-spec?car=Volkswagen Tiguan`, som bara ger EN rad (607 km, 552 000 kr,
77 kWh) som inte matchar någon av de påstådda nivåerna exakt.

**Dolda/raderade:** inga nya döljningar eller raderingar i natt (se LeBron-raden ovan).

**Ingen kodfix i natt** - inget namngivet vakthål, parserfel eller belagd datarad att rätta.
All ändring i natt var baslinjen + ett laddtips + en tömd `atgarder.json` (inga nya köbeslut; de
fyra Tucson-parkeringarna från 10-18 är redan utförda enligt `/api/admin/morgonfix-atgarder` och
skrivs inte om). `mvn -q test` (hela sviten) gick grönt, `BUILD SUCCESS`.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3415/3415 GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` enligt 9a (grenen fanns inte
sedan föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-19

**DOM: GRONT** (rutinens egen dom) - men körningen slutade efter rapporten och sköt aldrig upp
någon gren. Rad 5 i nattrutin.md sa "fas 2 bara om domen INTE ar GRONT" medan avsnitt 9 sa att
baslinjen ska föras fram även gröna nätter; rutinen följde rad 5. Samma motsägelse har gjort att
måndagens laddpriser aldrig skrivits en grön natt (senast 10-05). Efterhand, för hand 08:30:

- **Baslinjen** mätt med `scripts/mat-baslinje.js` (kontrollräkningen går ihop 3413/3413):
  insights 1478 -> 1486, högsta id 1945 -> 1953, cargo oförändrat (KB=0 bekräftar avbetad front).
- **Laddpriser: 6 kontrollerade, 0 ändrade** - IONITY 6,09, Allego 6,30, Mer 5,10 (höglast),
  Circle K 6,09, Lidl 5,80 kr/kWh; alla `kontrollerad` -> 2026-10-19. Mer gav 403 från molnets
  proxy i natt (`se.mer.eco` saknas i Allowed domains) men gick bra lokalt.
- **Fyndlistan 1946-1953:** 8 granskade, 0 kandidater (alla `ingen-laddkoppling`) - kamremsrader
  och lagerbilsrabatter. Vattenmärket nu 1953.
- **Lagat i nattrutin.md:** rad 5 och avsnitt 9:s GRONT-stycke säger nu samma sak - en grön natt
  skriver baslinjen, laddpriserna (måndag) och laddtipsen, men inga kodfixar.

## 2026-10-18

**DOM: KOLLA** - inget trasigt, men en namngiven mätfälla (3f) visar en natts stillestånd utan
förklarande loggrad, plus fyra kön-rader låsta mot en känd autosläpp-bugg.

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:27:27, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (ecb4a50), status OK, ingen
omstart i natt (uptime ~13,8 h vid grundkollen). Groq 3/3 modeller (friskt, HTTP 200, exakt
referensen). ev-specs updated **5** - inom 0-25 (kollisioner 182, inget trendskifte mot
290-gränsen eftersom evSpecs står still 650→650, väntat). **cargo-specs gav 0/0/0** (0 nya
bilnamn, 0 bagagevolymer, 0 generationsår) - lägre än alla tidigare nätter i loggboken (lägsta
tidigare var 2). Status OK, inget över 50-taket. Kontrollräkningen (cargo-coverage total 1803 +
evSpecs 650 + ice_consumption 960 = 3413 = /api/stats variants) stämmer exakt, bekräftat av
mat-baslinje.js.

**Baslinjen (2026-10-17) jämförd mot i natt:** models 2276→2276 (oförändrat), variants
3413→3413 (oförändrat), insights 1475→1478 (+3, matchar id-intervallet 1943-1945 exakt, alla
CarUp). cargo total 1803→1803, evSpecs 650→650, apiCars 2063→2063 - **ingenting i cargo/ev/ice-
tabellerna rörde sig alls i natt**, konsekvent med cargo-specs 0/0/0. Inget tal har FALLIT.

**3f-FYNDET (öppen fråga, ej LARM ännu):** medVolym STILL (1023) OCH bagageMissar OFÖRÄNDRAD
(828) - enligt domregeln i 3f är detta AVVIKELSE (inte det väntade "still + vuxit"-mönstret).
Skillnaden mot gårdagens liknande nollrapport: i natt saknas HELT den förklarande loggraden
('auto-data bagage: ...' eller liknande) i Render-loggen - sökt på "auto-data", "bagage",
"cargo", "generation", noll träffar (iBufferten 31, ingen omstart har tömt bufferten, så
raden borde synas om jobbet körde). `/api/admin/cargo-specs/missar` ser fortsatt frisk ut (828
rader, alla "ej-hittad", inga kända röda flaggor som Volvo XC60 eller Volkswagen Golf,
senaste provadDag 2026-10-16). **Detta är den FÖRSTA natten med en ren 0/0-stillestånd i just
AutoDataCargoFillService-spåret** (gårdagens 0-rapport förklarades av två egna WARN-rader,
dagens har inga). Domregeln säger "ligger den nära 0 TVÅ nätter i rad är det LARM" - i natt är
det en natt, så KOLLA, inte LARM. **Håll koll i morgon:** blir det en andra natt i rad med
medVolym+bagageMissar helt stilla, eller en andra natt med cargo-specs 0/0/0 utan förklarande
loggrad, ska det eskaleras. Ingen kodfix i natt - ingen entydig orsak att peka på (kan vara en
arbetslista som tillfälligt tog slut, kan vara ett tystnat skrivfel; utan loggraden går de inte
att skilja från varandra).

Generationsåren still (291/19, väntat till fönstret 2026-10-20, forsoktDag fortfarande
2026-09-20, dagarKvar 2). vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165,
**AVVIKER 0** - matchar referensen exakt. Drivmedelsräknaren oförändrad 506/421/85/16 - alla 16
handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip. Kategorivakten: 0 utslag sedan
omstart (uptime ~13,8 h).

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sex tjänster OK, CarAdvice-raden visar
autodeploy master · ecb4a50 (matchar /api/version). Samma kända INFO-rader hos Elbilsladdning
som tidigare.

**Kommandevakten (annonskollen): 0 LARM, 9 GRANSKA, 1 ANNAN_DRIVLINA, 16 INGA_ANNONSER på 56
rader / 26 bilar** (kön gick 57→56 sedan baslinjen). **Förklaringen till minskningen:**
UpcomingAutoReleaseService släppte 01:27:32 raden 1841 (BMW iX5 60 xDrive, "3 annonser och
raden säger inget om framtiden") - en ren batteri-/räckviddsspec utan framtidsord, och bilen
finns redan i cargo_spec ("bmw ix5 60 xdrive": el) - korrekt autosläpp, samma mönster som
tidigare natters VW ID.Polo/Audi A6 e-tron. Ingen låst rad (1788, samma bil, "Neue Klasse",
GRANSKA) rördes. Bayon-låsen (1904/1905) höll, ingen släppning i natt.

**NY ÅTGÄRD: fyra Tucson-rader låsta mot samma autosläpp-bugg som åt Bayon-tvillingen 10-16.**
Hyundai Tucson gen 5 (avgjort 10-10) har sju rader i kön (1274, 1293-1295, 1783-1785) som alla
gäller samma icke-sålda 2027-generation. Bara 1783-1785 var låsta via tidigare beslut
(`raderBeslutade`) - 1274/1293/1294/1295 stod olåsta trots att de är lika avgjorda, samma
sårbarhet som Bayon hade innan 10-16-lagningen. **Parkerade i natt (atgarder.json):** 1295,
1294, 1293, 1274 - samma källa som redan låsta syskon (avgjort 10-10, Tucson gen 5 ej till
salu i Sverige). Inga övriga GRANSKA-rader (Range Rover Sport 1713, Volvo XC70 1573, Mazda 6e
1560/1551, Mitsubishi Pajero 1414/1412, Hyundai Santa Fe 1368) är specifikt "avgjorda" i samma
dokumenterade mening - lämnade olåsta, ingen ny källa att belägga en låsning med i natt.

**Nattens 3 nya rader (id 1943-1945, alla CarUp):** granskade mot avsnitt 6. 1943 och 1945
(Mercedes S 500 W220, rostskydd 1998-2002 resp. M113-motorns hållbarhet 40-50 000 mil) samt
1944 (Suzuki SX4, pålitlighet efter 62 000 mil) är tillförlitlighetsomdömen om specifika
generationer/motorer - inga landsspecifika statistik-siffror som kräver marknadsangivelse,
snarare tekniska omdömen. Inget kategorifel, inga skatterader, renoveringsobjekt eller samlar-/
veteranträffar. Värdeminskning/tillförlitlighet/livslängd/skrotålder: **3 rader i natt**
(frågan därmed inte längre 0 - men se ovan, ingen kräver marknadsangivelse enligt min läsning).

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Dolda/raderade:** inga nya döljningar eller raderingar i natt. `/api/admin/insights/dolda`
visar 1940 (10-17, 1 dygn), 1912 och 1910 (båda 10-15, 3 dygn) - ingen har nått 7 dygn än,
ingen kvalificerar för radera.

**Laddtips:** inget nytt skrivet i natt - natten gav inga laddnings-/batteri-/räckvidds-/
laddpriskandidater bland CarUp:s tre nya rader (de handlar om tillförlitlighet, inte laddning).

**Laddpriser:** söndag, ej måndag - ingen kontroll i natt.

**Ingen kodfix i natt** - cargo-specs 0/0/0-fyndet (se 3f ovan) saknar en entydig orsak att peka
ut i kod, och en patch utan ett rött/grönt prov hade inte varit belagd. All ändring i natt var
kobeslut (atgarder.json, fyra parkeringar) + baslinjen. Det riktade provet
`MorgonfixAtgarderTest` (11 tester, inklusive `verkligaFilenArGiltig` mot den nya filen) gick
grönt, `BUILD SUCCESS`.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3413/3413 GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` enligt 9a (grenen fanns
inte sedan föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den
normalt.

## 2026-10-17

**DOM: KOLLA** - inget trasigt, men en öppen fråga (flera källor gav 0 i natt) och en
annonskolls-LARM som bedöms vara samma redan låsta Bayon-fall som 10-16, inte ett nytt fynd.

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:34:15, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (97ef4c6), status OK, ingen
omstart i natt (uptime ~16,7 h vid grundkollen). Groq 3/3 modeller (friskt, HTTP 200). ev-specs
updated **37** - något över det vanliga 0-25 (men långt under 290-gränsen för att
kollisionsspärren slutat verka, och evSpecs växer sunt 630→650 samtidigt) - ingen avvikelse.
cargo-specs gav 2/0/0 (2 nya bilnamn, 0 bagagevolymer, 0 generationsår) - lågt N, status OK.
"generationsår: 0" förklaras av två WARN-rader i Render-loggen ("auto-data: modellsidan gav
noll generationer") för natten - konsekvent, inte strukturbrott. Kontrollräkningen
(cargo-coverage total 1803 + evSpecs 650 + ice_consumption 960 = 3413 = /api/stats variants)
stämmer exakt (bekräftat både manuellt och av mat-baslinje.js).

**Baslinjen (2026-10-16) jämförd mot i natt:** models 2254→2276, variants 3383→3413,
insights 1471→1475 (+4, matchar id-intervallet 1939-1942 exakt). cargo total 1793→1803
(medVolym 1013→1023, UPPÅT), evSpecs 630→650, apiCars 2041→2063. Inget tal har FALLIT. Enligt
domregeln i 3f: medVolym uppåt = bra oavsett bagageMissar (826→828). **Öppen fråga:**
cargo-specs-jobbets egen räknare sa "bagagevolymer: 0" i natt men medVolym och evPowers (520→540)
steg ändå - mest troligt för att EvDatabaseScraperService också fyller bagagevolym direkt för
elbilar (ett annat kodspår än AutoDataCargoFillService.fyllSaknadeVolymer, som gav 0 i natt och
är det jobbet som faktiskt loggas i perSource). Ingen tappad rad - kontrollräkningen går ihop -
men räknaren i scrape-status speglar alltså inte hela bilden. Ingen kodfix (ingen entydig bugg,
bara två rader i samma jobb som mäter olika saker). Generationsåren still (291/19, väntat till
fönstret 2026-10-20). vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165,
**AVVIKER 0** - matchar referensen exakt. Drivmedelsräknaren 495→506 total, 410→421 el (växer
sunt), ice 85 (oförändrat), manuella 16 (oförändrat) - alla 16 handsatta rader fortsatt korrekta,
ingen flip. Kategorivakten: 0 utslag sedan omstart (uptime ~16,8 h vid avläsning).

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sex tjänster OK (observera: skriptet kontrollerar
nu även ett sjätte projekt, "Bränslekostnad" (Node/Express) - nytt sedan 10-16, inget för
rutinen att göra åt, scripts/ rörs inte).

**Kommandevakten (annonskollen): 1 LARM, 7 GRANSKA, 1 ANNAN_DRIVLINA, 17 INGA_ANNONSER på 57
rader / 26 bilar.** LARM-raden är **Hyundai Bayon (1905, 1904)** - samma fall som 10-16:s
huvudfynd. Kontrollerat i natt: båda raderna har `beslutad_at: 2026-10-16 09:52:21` (låsta via
den kod som deployades samma morgon, commit 97ef4c6 "autosläppet rör inte beslutade
parkeringar"), och Render-loggen visar INGEN släppning av 1904/1905 i natt - bara en annan rad
(1941, VW ID.Polo, korrekt autosläppt: "10 annonser och raden säger inget om framtiden").
**Bedömning: inte ett nytt fynd** - annonskollen vet inte om låset och kommer flagga samma
Blocket-annonser (gamla bensin-Bayon) varje natt framöver. Ingen ny atgärd skriven - låset håller.
Om detta är fel bedömning (låset har slutat verka eller en äkta ny Bayon-generation faktiskt
säljs) bör användaren säga till. GRANSKA-raderna matchar väntade mönster (BMW i3 Neue Klasse,
Tucson, Range Rover Sport, Volvo XC70, Mazda 6e, Mitsubishi Pajero, Hyundai Santa Fe) - inget nytt
där.

**Nattens 4 nya rader (id 1939-1942):** granskade mot avsnitt 6. Inget kategorifel, inga
skatterader, renoveringsobjekt eller samlar-/veteranträffar. Värdeminskning/tillförlitlighet/
livslängd/skrotålder: 0 rader i natt (frågan fortsatt OPRÖVAD, lyfts igen). **Svag rad värd att
flagga, inte dölja:** 1940 (Auto Motor & Sport, Mercedes-Maybach S 680) beskriver en
specialbyggd bil åt en namngiven kändis (LeBron James) - korrekt `category: null`, men
innehållet är en engångs-fabriksnyhet om en enskild persons specialbeställning, inte generell
köpinformation. Passar möjligen "fabriks- och lagernyheter" i de kända läcktyperna, men för
osäkert för att dölja på egen hand ("en rad du bara TROR är skräp döljer du inte") - flaggas åt
användaren, samma hantering som 1910 fick 10-15. Övriga tre rader (1939 Peugeot e208, 1941
VW ID.Polo, 1942 Volvo ES90) är normala specar/testomdömen, inget att anmärka på.

**Observation att hålla koll på:** Teknikens Värld (0 av 3), Vi Bilägare (0 av 10), CarUp
(0 av 12) och Allt om Elbil (0 av 5) gav alla 0 nya rader i natt trots lästa artiklar - all
nattens skörd (4 rader) kom från Auto Motor & Sport. En enda natt är inte bevis på
överblockering (regeln kräver två i rad för samma källa), och jag saknar gårdagens
per-källa-siffror för att avgöra om något av dessa fyra är natt nummer två. Håll koll imorgon -
upprepas nollan för någon av dem är det en trolig överblockering värd att söka Render-loggen
efter "hoppar över batchen".

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Dolda/raderade:** inga nya doljningar eller raderingar i natt. `/api/admin/insights/dolda`
visar fortsatt bara 1912 (Smart "??", dold 10-15) och 1910 (VW Golf-anekdot, dold 10-15) - båda
under 7 dygn, ingen kvalificerar för radera än.

**Kobeslut (`atgarder.json`):** inga nya i natt. Filen skrevs om med en tom lista - gårdagens
lås (1904, 1784) ligger kvar skyddade via `morgonfix_atgard`-tabellen
(`lasTidigareParkeringar`), inte via filens innehåll, så en tom fil släpper dem inte. Bekräftat
i natt: ingen av dem syns släppt i Render-loggen.

**Laddtips:** inget nytt. Toppkandidaten (1929, Audi A6 e-tron performance) är redan skriven
10-15. Nästa (1887, BYD Dolphin G) saknar fortfarande en matchande ev-spec-rad (uteslutet sedan
10-13/10-14). Tredje (1938, Nissan Leaf 2010, 122 hk) - samma obelagda siffra som 10-16,
fortfarande ingen källa att stämma av mot.

**Laddpriser:** lördag, ej måndag - ingen kontroll i natt.

**Ingen kodfix i natt och ingen Maven-körning** - inget kodfel hittades med en entydig orsak och
ett rött/grönt prov inom 9c:s gränser. Natten rör bara data/dokumentationsfiler som ramarna
undantar från "kodfiler" (loggboken, atgarder.json, baslinje.json), så inget bygge krävs. Det
riktade provet `MorgonfixAtgarderTest` kördes ändå mot den nya (tomma) atgarder.json och gick
grönt (`verkligaFilenArGiltig` m.fl., BUILD SUCCESS).

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3413/3413 GÅR IHOP (bekräftat av mat-baslinje.js självt).

**Grenanmärkning:** den här körningen committar på `auto/morgonfix` enligt 9a (grenen fanns inte
sedan föregående merge, skapad från origin/master) - auto-merge-workflowen bör ta den normalt.

## 2026-10-16

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:34:55, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (28bdddf), status OK, ingen
omstart i natt (uptime ~18,5 h). Groq 3/3 modeller (friskt, HTTP 200). ev-specs updated **4** -
inom 0-25. cargo-specs gav 7/0/0 (7 nya bilnamn, 0 bagagevolymer, 0 generationsår) - lågt N,
status OK. Kontrollräkningen (cargo-coverage total 1793 + evSpecs 630 + ice_consumption 960 =
3383 = /api/stats variants) stämmer exakt.

**Baslinjen (2026-10-15) jämförd mot i natt:** models 2247→2254, variants 3376→3383,
insights 1467→1471 (+4, matchar id-intervallet 1935-1938 exakt). cargo total 1786→1793,
evSpecs oförändrad 630, apiCars 2034→2041. Inget tal har FALLIT. medVolym+bagageMissar: medVolym
STILL (1013) + bagageMissar VUXIT (819→826) - enligt domregeln i 3f VÄNTAT (jobbet betade de 7
nya namnen utan att hitta volym). Generationsåren still (291/19, väntat till fönstret
2026-10-20). vPIC: 291/0/275, OK 126, INGEN_DATA 165, **AVVIKER 0** - matchar referensen exakt.
Drivmedelsräknaren oförändrad 495/410/85/16 - alla 16 handsatta rader korrekta, ingen flip.
Kategorivakten: 0 utslag sedan omstart (uptime ~18,5 h).

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK, samma kända INFO-rader hos
Elbilsladdning som tidigare.

**HUVUDFYND - UpcomingAutoReleaseService åt en manuellt parkerad tvilling:** 1904 (Hyundai
Bayon) parkerades 10-15 med motiveringen att raden är tvilling till 1905 (nästa generationens
Bayon, samma Vi Bilägare-artikel). Render-loggen visar att UpcomingAutoReleaseService släppte
1904 igen 01:35 i natt ("raden säger inget om framtiden") eftersom just den meningen saknar ett
eget nyhetsord, medan 1905 behöll sitt och stod kvar. Ingen kodfix - tjänstens egen javadoc
varnar redan för enradiga nyhetsordskontroller (EX40-fallet 09-11) och en patch utan skarpt prov
riskerar att tysta andra bilars autosläpp. Dokumenterat som ny mätfälla i docs/nattrutin.md
(3b) så att nästa natt känner igen mönstret direkt. Samma mönster (1936, Audi A6 e-tron) var
korrekt autosläpp - testomdöme om en redan såld bil som skraparen felaktigt köade.

**Nattens 4 nya rader (id 1935-1938):** granskade mot avsnitt 6. Inget kategorifel, inga
skatterader, renoveringsobjekt eller samlar-/veteranträffar. Värdeminskning: 1 rad (1937, Tesla
Model S behåller värde bättre än bensin/diesel enligt norska Nettbil) - marknadsangivelsen
("norska... Nettbil") uppfyller kravet. Inga nya köade rader i natt (kön gick 57→56, förklaras
av gårdagens släpp 1682/1683 minus parkeringarna 1902/1904 samt nattens autosläpp av 1904 och
1936).

**Marknadsregeln:** inget nytt märke att kontrollera i natt. Nätet är öppet (kontrollbilen Volvo
XC40 gav 200/stort svar mot Blockets API).

**Kobeslut (`atgarder.json`):** en rad. **Parkera:** 1904 igen (se huvudfyndet ovan - skälet
från 10-15 gäller fortfarande, tjänsten åt bara den enskilda meningens nyhetsord).

**Laddtips:** inget nytt. Toppkandidaten (1929, Audi A6 e-tron performance, poäng 5) är redan
skriven 10-15. Nästa (1887, BYD Dolphin G, poäng 4) saknar fortsatt en matchande ev-spec-rad att
belägga förbrukningen mot (uteslutet samma skäl 10-13/10-14). Tredje (1938, Nissan Leaf 2010,
poäng 3) anger 122 hk för originalmotorn - en siffra jag inte kan belägga mot någon källa i natt
och som avviker från vad jag känner till om bilen; hellre inget tips än en ogrundad siffra.

**Laddpriser:** fredag, ej måndag - ingen kontroll i natt.

**Ingen kodfix i natt** - nattens enda fynd (autosläppet av 1904) är ett medvetet dokumenterat
avvägningsproblem i UpcomingAutoReleaseService, inte ett entydigt fel med ett tydligt rött/grönt
prov inom 9c:s gränser. Allt arbete i natt var ett kobeslut (atgarder.json) + en dokumentationsrad
(nattrutin.md 3b) + baslinjen.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3383/3383 GÅR IHOP.

**Grenanmärkning:** den här körningen committar på `claude/happy-planck-352f12` (sessionens
tilldelade gren), inte `auto/morgonfix` som avsnitt 9a annars föreskriver - auto-merge-workflowen
(.github/workflows/morgonfix-automerge.yml) triggar därför INTE automatiskt på den här PR:en.
Användaren behöver granska och mergea för hand denna gång.

## 2026-10-15

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:36:49, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (9b45ccf), status OK, ingen
omstart i natt (uptime 67071 s). Groq 3/3 modeller (friskt, HTTP 200). ev-specs updated **8** -
inom 0-25. cargo-specs gav 3/0/0 (3 nya bilnamn, 0 bagagevolymer, 0 generationsår) - lågt N,
status OK. Kontrollräkningen (cargo-coverage total 1786 + evSpecs 630 + ice_consumption 960 =
3376 = /api/stats variants) stämmer exakt.

**Baslinjen (2026-10-14) jämförd mot i natt:** models 2243→2247, variants 3371→3376,
insights 1446→1467 (+21, matchar id-intervallet 1914-1934 exakt). cargo total 1782→1786,
evSpecs 629→630, apiCars 2030→2034. Inget tal har FALLIT. medVolym+bagageMissar: medVolym
UPPÅT (1012→1013) - enligt domregeln i 3f OK oavsett bagageMissar (816→819, också uppåt).
Generationsåren still (291/19, väntat till fönstret 2026-10-20, forsoktDag fortfarande
2026-09-20). vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** -
matchar referensen exakt. Drivmedelsräknaren 494→495 total, 409→410 el, ice 85 (oförändrat),
manuella 16 (oförändrat) - alla 16 handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip.
Kategorivakten: totalt 4 utslag sedan omstart (uptime ~18,6 h) - Tesla Model 3 (x2), Tesla
Model 3 Performance, Volkswagen ID.3, alla smaabil-motsägelser som vakten fångade (kategorifältet
strökt), ingen ny modell att lägga till i InsightTaxonomy.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK, samma fem kända INFO-rader hos
Elbilsladdning som tidigare.

**Kommandevakten (annonskollen):** 0 LARM på 57 rader / 26 bilar (9 GRANSKA, 1 ANNAN_DRIVLINA,
16 INGA_ANNONSER). Könedgången från gårdagens 58 rader förklaras helt av att 1895 (MG MG3)
släpptes i går natt. Inget nytt LARM-fynd i annonskollen själv i natt.

**STÖRRE FYND - kön missar HELA historier, inte bara enstaka tvillingrader (ny mätfälla,
dokumenterad i docs/nattrutin.md 3b):** id 1902 (Vi Bilägare, "Mercedes C-klass (2026)
deklarerar en räckvidd på över 76 mil ... en elbil") och id 1904 (Vi Bilägare, "Hyundai Bayon
kommer som hybrid ...") ligger som VANLIGA insikter, inte i kön - men båda hör till artiklar
som SAMMA natt gav parkerade kommande-rader (1574-1577 "Mercedes C-klass Electric", respektive
1905 "Hyundai Bayon"-nästa generation). 1902s 76 mil matchar 1577s 762 km nästan exakt (76,2 mil),
och `/api/ev-spec?car=Mercedes%20C-klass` samt `?car=Mercedes%20C-klass%20Electric` ger båda
tomt `{}` - det finns ingen katalograd för en elektrisk C-klass, vilket stöder att 1902 beskriver
samma overifierade framtidsbil som redan ligger parkerad under ett annat modellnamn. Ingen
atgärd går att göra i atgarder.json (raden ligger inte i kön - "parkera" förutsätter ett
insight_id ur /upcoming), och en riktig fix kräver en ändring i UPCOMING_PROMPT/STRICT_UPCOMING_
PROMPT, förbjudet under morgonfixen (9c). Lämnas åt användaren.

**Ytterligare öppen fråga:** id 1912 (Allt om Elbil) har `carModel` literally `"??"` ("Smart
lanserar en liten elbil med pris från 259 900 kronor") - extraktionen kunde inte namnge modellen
och skrev ett platshållartecken i stället för att lämna fältet tomt. Samma prisnivå som de
tidigare uteslutna Smart Fortwo-kandidaterna (1886/1891, se 10-14-loggen) - möjligen samma bil,
går inte att belägga. Ingen fix möjlig utan att röra skraparens AI-extraktion (förbjudet).
Kan vara värt att användaren kollar manuellt vilken Smart-modell det gäller och rättar
carMake/carModel direkt i databasen.

**Ytterligare öppen fråga:** id 1910 (CarUp, Volkswagen Golf) beskriver EN specifik bils
motorhaveri och en enskild verkstads bedömning ("kostnaden för en ny motor överstiger bilens
värde, rekommenderas skrotas") - låter mer som en anekdot om en enskild bil/annons än en
generell tillförlitlighetssiffra för modellen Golf. För osäkert för att dölja på egen hand
("en rad du bara TROR är skräp döljer du inte") - flaggas åt användaren i stället.

**Nattens 21 nya rader (id 1914-1934):** granskade mot avsnitt 6. Inget kategorifel utöver det
kodvakten redan fångade (Tesla Model 3 x3, VW ID.3 - se Kategorivakten ovan). Inga skatterader,
renoveringsobjekt eller samlar-/veteranträffar. Värdeminskning/tillförlitlighet/livslängd: 0
rader i natt (frågan fortsatt OPRÖVAD). Flera återkallelserader (Ford Kuga 1900, Mustang Mach-E
1901, VW ID.4/ID.7/ID.Buzz/ID.3 1916-1919, Audi Q3 1920, Volvo EX30 1926-1928) - alla distinkta
fakta (olika modeller eller olika återkallelseorsaker för EX30), ingen dubblett. Volvo EX30
nackstödsåterkallelsen (1926, Auto Motor & Sport) upprepar samma sakuppgift som 1915 (Vi
Bilägare, samma natt 10-13) - två oberoende källor om samma recall, bedöms INTE som skräp
(legitim nyhet, inte en AI-genererad upprepning) men noteras.

**Kobeslut (`atgarder.json`):** två rader, båda SLAPP. **Volkswagen ID. Tiguan (1682, 1683):**
GRANSKA i annonskollen (bygger på nyhetsordet "nya" i de gamla raderna), men bilen är bevisligen
köpbar nu - nattens egna rader 1921/1922 (Auto Motor & Sport) ger utskrivna svenska priser
(Life 559 900 kr, Style 606 900 kr), och `/api/ev-spec?car=Volkswagen%20Tiguan` har redan en
matchande spec (607 km WLTP, 77 kWh, 165 kW DC, 552 000 kr) under namnet utan "ID."-prefix.
ARSMODELLSREGELN: utskrivet svenskt pris slår annonskollens GRANSKA-dom.

**Laddtips:** 1 nytt. `ev-fact-candidates` gav 60 granskade, topkandidat **1929 Audi A6 e-tron
performance** (poäng 5) - 280 kW, 94,9 kWh, 270 kW DC enligt insikten, stämmer mot
`/api/ev-spec?car=Audi A6 e-tron performance` (765 km WLTP, 94,9 kWh exakt, 269 kW DC ~stämmer)
- skrevs med 269 kW för att matcha specen exakt. Andrakandidaten (1887, BYD Dolphin G,
förbrukning) uteslöts - redan bedömd 10-14 (ingen matchande ev-spec-rad för laddhybridvarianten).

**Laddpriser:** torsdag, ej måndag - ingen kontroll i natt.

**Regeländring i `docs/nattrutin.md` (3b, 1 rad):** dokumenterade den nya mätfällan ovan
("kön missar hela historier") som en konkret, namngiven uppföljning till Mazda-lärdomen, så att
nästa natt känner igen mönstret snabbare och vet att ingen atgarder.json-åtgärd är möjlig för den.

**Ingen kodfix i natt** - inget av nattens fynd (prompt-missarna för kön och för carModel) går
att laga inom 9c:s gränser utan att röra skraparens prompter, vilket är förbjudet. Allt arbete i
natt var kobeslut (atgarder.json) + laddtips + en dokumentationsrad (nattrutin.md) + baslinjen.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3376/3376 GÅR IHOP.

## 2026-10-14

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:37:00, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (a7eca27), status OK, uptime
~13,6 h (omstart 10:57 UTC 10-13 - sammanfaller med gårdagens Bilweb-fix, se nedan, inte en
nattlig omstart). Groq 3/3 modeller (friskt, HTTP 200). ev-specs updated **16** - inom 0-25.
cargo-specs gav 2/0/0 (2 nya bilnamn, 0 bagagevolymer, 0 generationsår) - lågt N, status OK.
Kontrollräkningen (cargo-coverage total 1782 + evSpecs 629 + ice_consumption 960 = 3371 =
/api/stats variants) stämmer exakt.

**Gårdagens LARM (natten mot 10-13) är åtgärdat utanför rutinen:** Bilweb bytte
`#alla-modeller` till flikar (`#tab-models`/`#tab-attributes`) och synken ville lägga till 405
bilar - taket på 400 föll (LARM). Användaren fixade parsern direkt på master samma dag (commit
a7eca27, läser nu bara `#tab-models`) med ny fixtur och två tester. Ingen baslinje skrevs för
10-13 (LARM, enligt baslinje-regeln) - i natt jämförs i stället mot baslinjen 10-12, över två
nätters förändring. Fixet höll: N=2 i natt, ingen ny LARM.

**Baslinjen (2026-10-12) jämförd mot i natt, över två nätter:** models 2236→2243, variants
3361→3371, insights 1407→1446 (+39, matchar id-intervallet 1875-1913). cargo total 1775→1782,
evSpecs 626→629, apiCars 2023→2030. Inget tal har FALLIT. medVolym+bagageMissar: medVolym
UPPÅT (1009→1012) - enligt domregeln i 3f OK oavsett bagageMissar (812→816, också uppåt).
Generationsåren still (291/19, väntat till fönstret 2026-10-20, forsoktDag fortfarande
2026-09-20). vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** -
matchar referensen exakt. Drivmedelsräknaren 491→494 total, 406→409 el, ice 85 (oförändrat),
manuella 16 (oförändrat) - alla 16 handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip.
Kategorivakten: totalt 1 utslag sedan omstart (uptime ~13,6 h) - **Tesla Model 3** (web-insights,
smaabil-motsägelse), vakten gjorde sitt jobb.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK. Elbilsladdning har fem kända
INFO-rader om källor som inte nämns i splashen (Blocket, Elbilsvaruhuset, API Ninjas,
Pressflöden, Chargeprice) - inget nytt.

**Kommandevakten (annonskollen):** 0 LARM på 58 rader / 27 bilar (10 GRANSKA, 1 ANNAN_DRIVLINA,
16 INGA_ANNONSER). **Fynd i natt: id 1895 (MG MG3) var felaktigt GRANSKA** - texten "redan i en
nästan NY motor finns hål i två kolvar" matchade nyhetsordet "ny" i UpcomingAdCheckService, men
beskriver en reliabilitetsskada i en redan såld bil, inte en kommande modell. Blocket visar 5
annonser på exakt den beskrivna motorn (MG3 Luxury Hybrid+ 1,5 194hk, 2024, 199 900 kr) -
**släppt** (ARSMODELLSREGELN: utskrivet svenskt pris). Dokumenterat som ny mätfälla i
docs/nattrutin.md (3b). **Nya köade rader i natt/igår:** Hyundai Bayon 1905 (GRANSKA, "den nya
generationen" - **parkerad**), BYD Atto 8 1897-1899 (INGA_ANNONSER, ej lanserad i Sverige -
**parkerade**, tillkom 10-13 men var oavgjorda).

**Nattens 14 nya rader (id 1900-1913):** granskade mot avsnitt 6. Inget kategorifel (kodvakten
höll - Tesla Model 3 ovan var smaabil-motsägelsen som redan fångades). Inga skatterader,
renoveringsobjekt eller samlar-/veteranträffar. Värdeminskning/tillförlitlighet/livslängd: 0
rader i natt (frågan fortsatt OPRÖVAD). Volvo EX60 (1890) har tom kategori - ny modell utan
taxonomi-mappning, ingen avvikelse.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** fem rader. **Slapp:** 1895 (MG MG3, se ovan). **Parkera:** 1905
(Hyundai Bayon), 1897/1898/1899 (BYD Atto 8, tre rader samma bil).

**Laddtips:** 1 nytt. `ev-fact-candidates` gav tio kandidater: **1882 Smart #2** (poäng 4) -
82 hk, 130 Nm, 36 kWh och 65 kW DC skrevs (räckvidd 29 mil/290 km matchar `/api/ev-spec?car=Smart
%232` exakt, batteri 35,8→36 kWh, DC 65 kW exakt) - **priset (259 900 kr) uteslöts**, det
motsäger spec-tabellens 230 000 kr och gick inte att belägga. **1891/1886 Smart Fortwo**
(poäng 6, högst) uteslöts helt - `/api/ev-spec?car=Smart Fortwo` gav tomt svar, ingen spec-rad
att belägga räckvidden (290 km) eller laddtiden mot. **1887 BYD Dolphin G** (förbrukning
urladdat batteri) uteslöts - `/api/ev-spec` ger bara en annan Dolphin G-variant (427 km/60,5 kWh,
inte laddhybridens), ingen källa att stämma 4,5 l/100 km mot.

**Laddpriser:** onsdag, ej måndag - ingen kontroll i natt.

**Ingen kodfix i natt** - den ad-check-bugg som hittades (se ovan) är ett regex-hål i
`UpcomingAdCheckService.NYHETSORD` (ordet "ny" matchar oavsett vad det syftar på), men en patch
riskerar att släcka legitima "ny/nya generation"-träffar på andra bilar utan ett tydligt,
smalt villkor och prov - för osäkert för en natt. Lämnas som förslag: smalna av matchningen så
"ny" inte ensamt räknas som nyhetsord när det föregås av "nästan" (eller motsvarande), och lägg
ett regressionsprov på id 1895-mönstret. All ändring i natt var kobeslut (atgarder.json) +
laddtips + dokumentation av mätfällan (nattrutin.md) + baslinjen.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning
3371/3371 GÅR IHOP.

## 2026-10-10

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:35:47, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (876532e), status OK, uptime
~23,6 h (ingen omstart i natt). Groq 3/3 modeller (friskt, HTTP 200, OK). ev-specs updated **6** -
inom 0-25. cargo-specs gav 2/0/0 (2 nya bilnamn, 0 bagagevolymer, 0 generationsår) - lågt N, inget
N högt/M=0/K=0-mönster (det kräver ett HÖGT N). Kontrollräkningen (cargo-coverage total 1775 +
evSpecs 626 + ice_consumption 960 = 3361 = /api/stats variants) stämmer exakt.

**Baslinjen (2026-10-09, commit c43021f) jämförd mot i natt:** models 2232→2236, variants
3357→3361, insights 1385→1402 (+17, matchar web-insights newInsights). cargo total 1773→1775,
evSpecs 624→626, apiCars 2019→2023. Inget tal har FALLIT. medVolym+bagageMissar: medVolym STILL
(1009→1009) + bagageMissar VUXIT (810→812) - enligt domregeln i 3f är detta VÄNTAT (jobbet betade
av de 2 nya bilnamnen), inte en avvikelse. Generationsåren still (291/19, väntat till fönstret
2026-10-20). vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165, **AVVIKER 0** -
matchar referensen exakt. Drivmedelsräknaren 490→491 total, 405→406 el, ice 85, manuella 16
(oförändrat) - alla 16 handsatta rader (11 el + 5 ice) fortsatt korrekta, ingen flip. Kategorivakten:
totalt 2 utslag sedan omstart (uptime ~23,6 h) - **Volvo 240 och Volvo 760** (web-insights
[veteran], CarUp), båda med kategori och drivmedel strukna av vakten. Vakten gjorde sitt jobb,
ingen ny lucka.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK. Inget nytt att flagga.

**Kommandevakten (annonskollen):** 0 LARM på 60 rader / 27 bilar (10 GRANSKA, 1 ANNAN_DRIVLINA,
16 INGA_ANNONSER). Nattens 2 nya köade rader - **1866 Hyundai Ioniq 4** och **1867 Hyundai
Ioniq 7** (båda 0 annonser, ännu ej lanserade i Sverige) - korrekt obehandlade, ingen åtgärd krävs.
Tidigare öppen fråga från 10-08 (1784 saknades i /upcoming) är nu LÖST - 1784 syns i natt
tillsammans med övriga Tucson-rader, ingen kodändring behövdes från min sida.
**Tre äldre köade rader flyttade till SLAPP i natt** (se kobeslut nedan) - Kia EV9 (1843),
Hyundai Ioniq 3 (1824) och Volkswagen ID. Polo (1825) visade sig redan finnas i cargo_spec OCH
ha aktiva Blocket-leasingerbjudanden med riktiga 2027-priser, trots att radernas egen text talar
om mässvisning/kommande teknik - användarens linje (beställningsbar = aktuell).
**Öppen fråga, oförändrad sedan 10-08:** de fyra gamla Tucson-raderna (1274, 1293-1295) grupperas
fortfarande av annonskollen med dagens Tucson-annonser och får GRANSKA - oklart om nuvarande eller
kommande generation. Väntar fortsatt på mänsklig granskning.

**Nattens 17 nya rader (id 1851-1867):** granskade mot avsnitt 6. Inget kategorifel (kodvakten
höll). Inga skatterader, renoveringsobjekt eller tecken på överblockering av avvecklade modeller.
Värdeminskning/tillförlitlighet/livslängd: 0 rader i natt (frågan fortsatt OPRÖVAD). Två veteranrader
(1864, 1865 - Volvo 240/760) fångades av veteranvakten, se kategorivakten ovan.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** fem rader. **Slapp:** 1843 (Kia EV9), 1824 (Hyundai Ioniq 3),
1825 (Volkswagen ID. Polo) - alla tre redan i cargo_spec och med aktiva Blocket-annonser/leasing,
se ovan. **Radera:** 1704 och 1729 - båda dolda sedan 2026-10-02 (nu 8 dygn, över 7-dygnsgränsen),
fortsatt bedömda som skräp (DUBBLETT respektive PROMPTMISS) enligt samma regel som när de doldes.

**Laddtips:** 1 nytt. `ev-fact-candidates` gav flera kandidater: **1847 Renault Twingo E-Tech
Electric** (poäng 6) - batteri 27,5 kWh och räckvidd 263 km matchar `/api/ev-spec?car=Renault
Twingo` exakt - SKREVS (laddeffekten i tipset satt till spec-värdet 50 kW DC, inte insiktens egna
ospecificerade siffra). **1830/1828/1849 BMW iX4** uteslöts - bilen är ännu inte köbar i Sverige
(kommande-kön). **1831 Mercedes GLC 400** uteslöts - `/api/ev-spec?car=Mercedes GLC 400` gav tomt
svar, gick inte att belägga mot spec-tabellen.

**Laddpriser:** lördag, ej måndag - ingen kontroll i natt.

**Ingen kodfix i natt** - inget kategorifel, inget parserhaveri, ingen entydig kodorsak att laga.
Allt arbete i natt var databeslut (kobeslut + laddtips) samt baslinjen.

**Baslinjen:** uppdaterad till i natts mätning (se docs/baslinje.json), kontrollräkning 3361/3361
GÅR IHOP.

## 2026-10-08

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:37:35, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (919d349), status OK, uptime
~18,3 h (ingen omstart i natt). Groq 3/3 modeller (friskt, HTTP 200). ev-specs updated **8** -
inom 0-25. cargo-specs gav 11/6/0 (11 nya bilnamn, 6 bagagevolymer, 0 generationsår) - under
larmgränsen 50, inget N högt/M=0/K=0-mönster. Kontrollräkningen (cargo-coverage total 1764 +
evSpecs 620 + ice_consumption 960 = 3344 = /api/stats variants) stämmer exakt.

**Baslinjen (2026-10-05, commit 277021a) jämförd mot i natt, med 10-06 och 10-07 som
mellansteg:** models 2192→2223, cargo total 1736→1764, evSpecs 617→620, apiCars 1979→2010.
Inget tal har FALLIT. medVolym+bagageMissar: 998/784 → 1005/805 över tre nätter - **medVolym
UPPÅT, enligt domregeln i 3f OK oavsett bagageMissar.** Generationsåren still (291/19, väntat
till fönstret 2026-10-20). vPIC: 291 kontrollerade/0 hoppade/275 anrop, OK 126, INGEN_DATA 165,
**AVVIKER 0** - oförändrat, matchar referensen exakt. Drivmedelsräknaren 486/401/85/16 (+1
total/+1 el sedan baslinjen) - ingen flip, alla 16 handsatta rader (11 el + 5 ice) korrekta.
Kategorivakten: totalt 0 utslag sedan omstart (uptime ~18,3 h).

**EV-synkens kollisionsskydd (gårdagens fynd):** Render-loggen visar WARN-summeringen "176
bilar hoppades över" från ev-specs-körningen 01:12 - samma normala kollisionsspärr som
nattrutinen 1b beskriver (per-bil-raden loggas nu INFO sedan gårdagens fix av användaren,
commit 919d349). Ingen avvikelse - evSpecs rörde sig uppåt (617→620) samtidigt.

**Splashvakten: DOM GRÖNT** (exitkod 0) - alla sju tjänster OK. Bränslekostnad (403 i natt
10-07) svarar nu normalt. Elbilsladdning 6cee8ef mot README-commiten c803bf3 förklaras nu
korrekt av skriptet självt (dokumentationsändring, ingen ombyggnad) - ingen VARNING längre.
Ingenting nytt att flagga.

**Kommandevakten (annonskollen):** 0 LARM på 46 rader / 22 bilar (7 GRANSKA, 1 ANNAN_DRIVLINA,
14 INGA_ANNONSER). Nattens 2 nya köade rader - **1815 BMW iX4** (Neue Klasse, 0 annonser) och
**1804 Hyundai Ioniq 3** (ny modell, 0 annonser) - är båda nästa-generations/ej lanserade
modeller som ännu inte säljs i Sverige. PARKERADE i atgarder.json i natt, källa annonskollens
INGA_ANNONSER-dom.

**Öppna frågor, oförändrade sedan tidigare nätter (ingen fix, FÖRBJUDET enligt 9c):**
- Insikt **1784** (Hyundai Tucson 2027, laddhybrid-räckvidd) saknas fortfarande i
  `/upcoming`-kön trots att 1783/1785 om samma bil är köade - samma UPCOMING_PROMPT-lucka som
  10-06/10-07. Ingen atgarder.json-rad möjlig för den (går inte att slå upp via `/upcoming`).
- De fyra gamla Tucson-raderna (1274, 1293-1295) grupperas fortfarande av annonskollen med
  dagens Tucson-annonser och får GRANSKA - oklart om de beskriver nuvarande eller kommande
  generationen. Över en och en halv månad gammal fråga nu - väntar på mänsklig granskning.

**Nattens 10 nya rader utanför kön (id 1805-1814, exkl. kommande-kön ovan):** granskade mot
avsnitt 6. **Ett kategorifel hittat och lagat (se kodfix nedan): id 1807 (Vi Bilägare, Cupra
Raval) bar `suv`** trots att Raval delar MEB Entry-plattformen och batteristorleken (37-52 kWh)
med Volkswagen ID.Polo, som samma natt korrekt fick `smaabil` (id 1806, 1809-1812) på samma
plattform - en halvkombi, inte en SUV. Kategorivaktens egen buffert fångade INTE detta (totalt
0 i natt) eftersom "raval" saknades i `InsightTaxonomy.LAGA_MODELLER`. Övriga nio rader utan
kategorifel: 1808 Cupra Terramar (suv - korrekt, Tiguan/Q3-plattform, inte samma fall som
Raval), 1813 Polestar 5 (tom kategori - vakten gjorde sitt jobb, gränsfall sedan tidigare),
1814 Ford Maverick (suv - ingen "pickup"-kategori finns i taxonomin, ingen ny lucka). Återkallelser
(1806, 1808) är relevanta kända fel, ingen lacktyp. Värdeminskning/tillförlitlighet/livslängd:
0 rader i natt (skrivs ut även vid noll enligt avsnitt 6 - frågan är fortsatt OPRÖVAD).

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Inget tecken på överblockering av avvecklade modeller.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** två rader - parkera 1815 (BMW iX4) och parkera 1804 (Hyundai
Ioniq 3), se ovan. De två dolda raderna (1704, 1729, sedan 10-02) är nu 6 dygn gamla -
fortfarande under 7-dygnsgränsen för radera (nästa natt de kan raderas är 10-09).

**Laddtips:** 1 nytt. `ev-fact-candidates` gav tre kandidater: **1810 VW ID. Polo GTI**
(poäng 6, styrkt av 1802+1798) - batteri 51,7/52 kWh, räckvidd 431 km och 105 kW DC matchar
`/api/ev-spec?car=Volkswagen ID. Polo GTI` exakt - SKREVS. 1784 (Hyundai Tucson 2027)
uteslöts - kommande-modell, samma skäl som tidigare nätter. 1805 (Skoda Octavia fullhybrid)
uteslöts - kan inte laddas externt, fel ämne för elbilsassistentens laddtips.

**Laddpriser:** torsdag, ej måndag - ingen kontroll i natt.

**Kodfix (1 fil, hål i kategorivakten, 9c(b)):**
`src/main/java/com/caradvice/model/InsightTaxonomy.java` rad 110 - lade till `"raval"` i
`LAGA_MODELLER` (källa: id 1807 ovan + plattformsjämförelsen mot ID.Polo). Provet
`InsightTaxonomyTest.cupraRavalArIngenSuv` (`src/test/java/com/caradvice/model/InsightTaxonomyTest.java`)
var RÖTT före fixen (`kategoriMotsagelse("suv", "Cupra", "Raval")` gav `null`) och GRÖNT efter
(gav `"cupra raval är ingen SUV"`). Ingen annan kodändring i natt.

Byggt och testat grönt: `mvn -q -DskipTests package` grönt, `mvn test`
**1236 prov / 0 fel / 0 failures / 0 skipped** (inkl. `MorgonfixAtgarderTest.verkligaFilenArGiltig`,
`LaddtipsServiceTest.verkligaFilenArGiltig` och det nya `InsightTaxonomyTest`-provet mot de
verkliga filerna/koden). Filer ändrade i natt (kodtaket 9d, exkl. loggbok/atgarder/baslinje):
**3 av 5** - `InsightTaxonomy.java`, `InsightTaxonomyTest.java`, `laddtips.json`.

**Grenen:** `auto/morgonfix` fanns inte (borttagen av auto-merge efter 10-07:s PR). Skapad på
nytt från `origin/master` (919d349). Ingen fast PR att flytta (avsnitt 11 gäller inte).

**Baslinjen:** uppdaterad i natt (domen är KOLLA, inte LARM) - committas i samma commit som
morgonfixen enligt avsnitt BASLINJEN/9.

## 2026-10-07

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:35:29, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (e625527), status OK, uptime
~17,7 h (ingen omstart i natt). Groq 3/3 modeller (friskt). ev-specs updated **11** - inom
0-25. cargo-specs gav 16/1/0 (16 nya bilnamn, 1 bagagevolym, 0 generationsår) - under
larmgränsen 50. Kontrollräkningen (cargo-coverage total 1753 + evSpecs 618 + ice_consumption
960 = 3331 = /api/stats variants) stämmer exakt.

**Baslinjen (2026-10-05, commit 277021a) jämfördes mot i natt, med 10-06:s LARM-natt som
mellansteg (ingen baslinje skrevs då):** models 2192→2210, cargo total 1736→1753 (+17 över två
nätter, konsekvent med cargo-specs-ledets +1 (10-06) och +16 (i natt)), evSpecs 617→618,
apiCars 1979→1997. Inget tal har FALLIT.

**medVolym+bagageMissar: 998/784 → 999/800 över två nätter - medVolym UPPÅT, enligt domregeln
i 3f OK oavsett bagageMissar.** Generationsåren still (291/19, väntat till fönstret
2026-10-20, forsøktDag fortfarande 2026-09-20 - kontrollerat i ice-generations/missar).
vPIC: 291/0/275, OK 126, INGEN_DATA 165, **AVVIKER 0** - oförändrat, nu 9+ mätningar i rad
utan avvikelse. Drivmedelsräknaren 485/400/85/16, oförändrat, ingen flip, alla 16 handsatta
rader korrekta. Kategorivakten: totalt 0 utslag sedan omstart (uptime 17,7 h).

**Ny upptäckt, ingen fix i natt:** Render-loggen (ERROR) visar gott om rader från
`EvDatabaseScraperService` i formatet "SCRAPER ALERT: '<variant>' matchar DB-raden '<namn>'
som redan tagits ... i samma körning", t.ex. Fiat 500e Cabrio/Hatchback, Opel Zafira Electric
XL, BYD Dolphin Surf, flera Ford/Hyundai/Kia-varianter. Buffertens iBufferten stod på 212 vid
avläsning (100 av dem SCRAPER ALERT i urvalet jag drog), alla från ev-specs-jobbets körning
01:05-01:12. Ingen tidigare morgonfix-logg nämner detta mönster, så jag vet inte om det är
nytt eller bara aldrig sökts fram förut - flaggar det i stället för att gissa en fix. Troligen
samma namnkollisionsskydd som redan finns i cargo-specs (avsnitt 1c), men här för
ev_spec-varianter: flera trim/batteri-varianter av samma modell hoppas över i stället för att
få egna rader. Ingen siffra har fallit på grund av det (evSpecs rörde sig uppåt), men värt att
hålla ögonen på om evSpecs börjar stå still trots att cargo-specs fortsätter hitta nya namn.

**Kommandevakten (annonskollen):** 0 LARM på 45 rader / 21 bilar (8 GRANSKA, 1 ANNAN_DRIVLINA,
12 INGA_ANNONSER). Nattens enda nya köade rad (**1797 Leapmotor B03X**, Auto Motor & Sport)
fick GRANSKA (24 annonser) - men till skillnad från de övriga GRANSKA-raderna påstår insikten
INTE att bilen gäller nästa generation, och annonserna är genuina nybilsannonser (t.ex.
"Leapmotor B03X Design Promax 53 kWh 382km WLTP ... Serviceavtal", 319 900 kr, 0 mil).
`/api/ev-spec?car=Leapmotor B03X` bekräftar: 310 000 kr, WLTP 382 km, 53,0 kWh - matchar
annonserna. Bilen är alltså redan i katalogen med svenskt pris, inte en kommande modell -
SLÄPPT i atgarder.json i natt (se nedan), enligt användarens linje.

**Öppen fråga sedan 10-06, oförändrad:** insikt 1784 (Hyundai Tucson 2027, laddhybrid-räckvidd)
saknas fortfarande i kommande-kön trots att 1783/1785 om samma bil är köade - samma lucka i
UPCOMING_PROMPT som tidigare, ingen kod att ändra härifrån (FÖRBJUDET enligt 9c).
**Också oförändrat:** de fyra gamla Tucson-raderna i kön (1274, 1293-1295, från 08-20/08-22)
grupperas av annonskollen med samma 49 annonser som 1783/1785 och får GRANSKA - en blandning av
dagens Tucson-annonser (inkl. leasingannonser med 2026/2027 årsmodell) och begagnade äldre
bilar gör det osäkert om dessa fyra rader beskriver en redan såld generation eller den
kommande 2027-faceliften. Ingen ny information i natt för att avgöra det - lämnas parkerat,
flaggas för mänsklig granskning (över en månad gammal fråga nu).

**Nattens 9 nya rader (id 1795-1803):** 1795 Citroën C3 Aircross (Vi Bilägare, högst milkostnad
i långtest - driftskostnad, ingen lacktyp). 1796 Mercedes EQS (M3, steer-by-wire - teknikfakta).
1797 Leapmotor B03X (se ovan, släppt). 1798 Renault Megane E-Tech (Auto Motor & Sport,
batteriuppdatering). 1799-1800 Tesla Model 3/Model Y Powershare Home Backup (CarUp, äkta
per-modell-fakta ur samma nyhet, ingen bugg). 1801-1802 Kia EV9 (CarUp, pris/laddning).
1803 Tesla Model Y (Allt om Elbil, Autopilot-ändring). Inga kategorifel - samtliga kategorier
(suv/smaabil/familjebil/elbil) stämmer med modellerna. Värdeminskning/tillförlitlighet/
livslängd: 0 rader av de 9 (ingen sådan rad i natt - skrivs ut även vid noll enligt avsnitt 6).

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Inget tecken på överblockering av avvecklade modeller.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** en rad - slapp 1797 (Leapmotor B03X, se ovan). De två dolda
raderna (1704, 1729, sedan 10-02) är 5 dygn gamla - under 7-dygnsgränsen för radera.

**Laddtips:** 0 nya. Enda kandidaten (`ev-fact-candidates`, 1802 Kia EV9, poäng 6) avvisas:
insiktens "WLTP‑räckvidd på 505 km" matchar INTE `/api/ev-spec?car=Kia EV9` (579 km i databasen)
- siffran går inte att belägga, skriver inte tipset. Andra kandidaten (1784, Hyundai Tucson)
utesluten - kommande-modell, samma skäl som 10-06.

**Laddpriser:** ej måndag, ingen kontroll i natt.

**Splashvakten: DOM LARM** (en riktig LARM, en ny VARNING):
- 🚨 LARM: **Bränslekostnad** (bilresa.onrender.com) svarar HTTP 403 - molnets proxy nekar
  (x-deny-reason: host_not_allowed). Miljöfråga för användaren (Allowed domains), inte ett
  apphaveri - lagas inte härifrån.
- ⚠️ VARNING (ny i natt, första gången sedd): **Elbilsladdning** kör 6cee8ef men main står på
  c803bf3 - autodeployen har inte tagit senaste. Enligt avsnitt 14: LARM bara om skillnaden
  kvarstår TVÅ nätter i rad - detta är natt 1, så KOLLA räcker. Flaggar för uppföljning imorgon.
- CarAdvice själv OK i alla punkter (Java 27, Spring Boot 4.1.1, PostgreSQL 18.4, autodeploy
  e625527, alla live-siffror på plats). MiniPrisTåget, Bankomat 2.0, VäderKläder: OK.

**Ingen kodfix i natt.** Inget hål i kategorivakten, ingen parser som slutat träffa, inget tal
som gick att belägga som fel (SCRAPER ALERT-fyndet ovan är för löst förstått för en fix, se
därom). Byggt och testat grönt: `mvn -q -DskipTests package` grönt, `mvn test` **1235 prov /
0 fel / 0 failures / 0 skipped** (inkl. `MorgonfixAtgarderTest.verkligaFilenArGiltig` och
`LaddtipsServiceTest` mot de verkliga filerna).

**Grenen:** `auto/morgonfix` fanns inte (borttagen av auto-merge efter 10-06:s PR). Skapad på
nytt från `origin/master` (e625527). Ingen fast PR att flytta (avsnitt 11 gäller inte).

**Baslinjen:** INTE uppdaterad i natt - domen är LARM (splashvakten), så enligt BASLINJEN-regeln
ligger baslinjen från 2026-10-05 (commit 277021a) kvar oförändrad.

## 2026-10-06

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:38:26, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (ba5c2da), status OK, uptime
~2,27 h (ingen omstart under nattkedjan - appen startade 22:20:30 UTC, före kedjans 23:00 UTC).
Groq 3/3 modeller (friskt). ev-specs updated **3** - inom 0-25. cargo-specs gav 1/0/0 (1 nytt
bilnamn, 0 bagagevolymer, 0 generationsår) - under larmgränsen 50. Kontrollräkningen
(cargo-coverage total 1737 + evSpecs 617 + ice_consumption 960 = 3314 = /api/stats variants)
stämmer exakt, liksom models (2192→2193) och /api/cars (1979→1980) - allt konsekvent med
cargo-specs-ledets +1 bilnamn, ingen tappad rad.

**medVolym+bagageMissar: 998/784 → 998/785 - still/+1, enligt domregeln i 3f VÄNTAT** (sopet
är sedan tidigare avklarat, kvar är bara enstaka 30-dagars-återcirkulation). Generationsåren
still (291/19, väntat till fönstret 2026-10-20, forsøktDag fortfarande 2026-09-20). vPIC:
291/0/275, OK 126, INGEN_DATA 165, AVVIKER 0 - oförändrat. Drivmedelsräknaren 485/400/85/16,
oförändrat, ingen flip, alla 16 handsatta rader korrekta. Kategorivakten: totalt 2 utslag sedan
omstart (BMW 7-serie/7-serien, Highway Assistant-raderna 1787/1775) - vakten strök kategorin
korrekt (7-serien är en lyxbil, inte familjebil); inget hål.

**Splashvakten: DOM LARM** (exitkod 2) - **rapportens viktigaste fynd, väntar på dig:**
MiniPrisTåget (HTTP 503), VäderKläder (HTTP 503) och Bränslekostnad (HTTP 403) svarade inte.
CarAdvice själv är OK i alla punkter (Java 27, PostgreSQL 18.4, autodeploy ba5c2da, alla
live-siffror på plats). De tre trasiga tjänsterna är drift/kod i andra repon - lagas INTE
härifrån enligt regeln i avsnitt 14.

**Kommandevakten (annonskollen):** 0 LARM på 46 rader / 21 bilar (7 GRANSKA, 1 ANNAN_DRIVLINA,
13 INGA_ANNONSER). Nattens 5 nya köade rader (1792 BMW i3, 1788 BMW iX5, 1787 BMW 7-serie, 1785
och 1783 Hyundai Tucson 2027) är alla nästa-generations-modeller som ännu inte säljs i Sverige
- parkerade i atgarder.json i natt med annonskollens dom som källa (se nedan).

**Öppen fråga, ingen fix:** insikt **1784** (Vi Bilägare, "Laddhybridvarianten av Hyundai
Tucson 2027 har en elektrisk räckvidd...") beskriver samma nya Tucson 2027 som 1783/1785 men
hamnade INTE i kommande-kön (`/api/admin/insights/upcoming` saknar den, `ev-fact-candidates`
listar den som en vanlig kandidat). Sannolik lucka i kommande-klassificeringen (UPCOMING_PROMPT
är en AI-prompt, inte kod - FORBJUDET att ändra enligt 9c). Ingen atgarder.json-rad skrevs för
1784 eftersom den inte går att slå upp via `/upcoming`-listan som 9h kräver; flaggas här för
granskning i stället för en gissad skrivning.

**Nattens 21 nya rader (id 1774-1794):** sex rader är samma Highway Assistant-nyhet applicerad
på flera BMW-modeller (3-serie/3-serien x2, 7-serie/7-serien x2, X5, iX3) - klunga av sanna
per-modell-fakta ur en artikel, ingen bugg (jfr Safety Coach-klungan 1253-1257). Fem rader är
tillvalspriser för uppvärmda säten/baksäten (VW ID.7, Mercedes GLC, Volvo EX60, BMW iX3, Mini
Cooper) - inga lackor. Resten är bilbegagnatkostnader (CarUp/Skoda Enyaq, Porsche Taycan) och
leasingpriser (Kia EV2, Renault 4) samt de fem kommande-raderna ovan. Inget kategorifel utöver
7-serie-strykningen (redan nämnd). Vardeminskning/restvärde: 1 rad i natt (1789, Porsche Taycan
Cross Turismo 4S, -49 % på tre år) - stödjer fortsatt att FALSKA NEGATIV inte är ett aktivt
problem.

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Inget tecken på överblockering av avvecklade modeller.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** fem rader - parkera 1792 (BMW i3, GRANSKA), 1788 (BMW iX5,
INGA_ANNONSER), 1787 (BMW 7-serie, INGA_ANNONSER), 1785 och 1783 (Hyundai Tucson 2027, GRANSKA)
- samtliga nästa-generations-modeller som annonskollen visar ännu inte säljs i Sverige.

**Laddtips:** 0 nya. Samma tre kandidater som tidigare avvisade (BMW iX3 40 xDrive 1724 mot
ev-specs; VW ID.7 Tourer 1743 utan konkret siffra i själva insikten, trots att ev-spec har data;
Range Rover P400e Autobiography 1738 - ev-spec fortfarande tomt). Ny kandidat 1784 (Hyundai
Tucson 2027) utesluten med flit - gäller en bil som inte går att köpa i Sverige än (samma skäl
som kommande-parkeringen ovan), oavsett att fyndlistan inte själv märkt den "kommande-modell".

**Laddpriser:** ej måndag, ingen kontroll i natt.

**Ingen kodfix i natt.** Inget hål i kategorivakten (7-serien är vakten som fungerar), ingen
parser som slutat träffa, inget tal som gick att belägga som fel. Splashvakten pekar på drift i
andra repon, inte kod här. Byggt och testat grönt: `mvn -q -DskipTests package` grönt,
`mvn test` 1235 prov / 0 fel / 0 failures (inkl. `MorgonfixAtgarderTest.verkligaFilenArGiltig`
mot den verkliga filen).

**Grenen:** `auto/morgonfix` fanns inte (borttagen av auto-merge efter 10-05:s PR). Skapad på
nytt från `origin/master` (ba5c2da). Ingen fast PR att flytta (avsnitt 11 gäller inte).

**Baslinjen:** INTE uppdaterad i natt - domen är LARM (splashvakten), så enligt BASLINJEN-regeln
ligger gårdagens baslinje (commit 277021a, 2026-10-05) kvar oförändrad.

## 2026-10-05

**Nattrapporten visade:** kedjan gick (lastScrapeFinishedAt 01:28:54, inom väntat fönster
01:15-01:50 sommartid), deployad commit matchar origin/master (277021a), status OK, uptime
~15,4 h (ingen omstart i natt). Groq 3/3 modeller (friskt). ev-specs updated **3** - inom
0-25. cargo-specs gav 0/0/0 (0 nya bilnamn, 0 bagagevolymer, 0 generationsår).
Kontrollräkningen (cargo total 1736 + evSpecs 617 + ice_consumption 960 = 3313 = variants)
stämmer exakt, liksom /api/stats (models 2192, insights 1308) och /api/cars (1979).

**medVolym+bagageMissar still på 998/784 - fjärde baslinjemätningen i rad (10-02 till 10-05)
med samma tal.** Fortsätter bedömas som OK enligt 10-04:s tolkning (svepet avklarat, kvar är
bara den sporadiska 30-dagars-återcirkulationen): `/cargo-specs/missar` visar att inga nya
missar skrivits sedan 2026-10-01 (sista 3 st), och de äldsta (2026-09-21, 150 st) åldras
tidigast ut 2026-10-21 - helt konsekvent med att arbetslistan är tom just nu, inte att
skrivvägen är trasig. Ingen WARN/ERROR-rad i loggen om "auto-data bagage" (iBufferten 183,
ingen trunkering), så inget tecken på fel åt det hållet heller.

**Generationsåren still (291/19, väntat till fönstret 2026-10-20).** vPIC: 291/0/275, OK 126,
INGEN_DATA 165, AVVIKER 0. Drivmedelsräknaren 485/400/85/16, oförändrat, ingen flip, alla 16
handsatta rader korrekta. Kategorivakten: totalt 0 utslag sedan omstart (uptime 15,4 h).

**Kommandevakten (annonskollen):** 0 LARM på 42 rader / 19 bilar (6 GRANSKA, 1 ANNAN_DRIVLINA,
12 INGA_ANNONSER). Nattens enda nya köad rad (1767, Smart #3 Brabus, Teknikens Värld) var en
FELAKTIG parkering: `/api/ev-spec?car=Smart %233` och `/api/cars` visar att Smart #3 (Brabus,
Premium, Pro+) redan finns i katalogen med svenskt pris (552 000 kr, WLTP 415 km) sedan
tidigare. Insikten är ett eget uppmätt testomdöme (428 hk, 0-100 på 3,7s, testförarens egna
intryck av styrning/bromsar) - TESTBEVISREGELN: ett sådant test bevisar i sig att bilen finns
att köpa. Släppt i atgarder.json i natt (se nedan) i stället för att ligga kvar som en
regression i kön.

**Nattens 7 nya rader (id 1767-1773):** 1767 Smart #3 Brabus-test (se ovan, släppt). 1768
Volkswagen Golf GTI (Teknikens Värld, familjebil, jämförelsetext mot Smart #3) - korrekt
storleksmässigt (Golf-baserad, ingen kategorivakt-kontradiktion), men samma nameplate som 1234
(Golf GTI, TOM kategori under KATEGORIFELET) - ingen säker regression, bara en observation att
hålla ögonen på om fler GTI-rader får en fylld kategori. 1769-1773 är fem tillförlitlighetsrader
från CarUp (Bo Ericsson): Mazda3 (smaabil), Toyota RAV4, Lexus NX, Subaru Forester, Mazda CX-5
(alla suv) - sällan motor-/växellådsskador, ingen av dem säljstatistik eller annan namngiven
lacktyp. Kategorifördelningen (suv 558→562, smaabil 185→187, familjebil 241→242) går exakt ihop
med raderna - inget nytt hål.

**Värdeminskning/tillförlitlighet:** 5 rader i natt (1769-1773), alla tillförlitlighet. Stödjer
fortsatt att FALSKA NEGATIV-oron från avsnitt 6 inte är ett aktivt problem.

**Tre regler (skatter/renoveringsobjekt/avvecklade modeller):** inga skatterader eller
renoveringsobjekt i natt. Inget tecken på överblockering av avvecklade modeller.

**Marknadsregeln:** inget nytt märke att kontrollera i natt.

**Kobeslut (`atgarder.json`):** en rad - slapp 1767 (Smart #3 Brabus, se ovan, TESTBEVISREGELN).
Gårdagens två dolda rader (1704, 1729) är nu 3 dygn gamla, fortfarande under 7-dygnsgränsen för
radera.

**Laddtips:** 0 nya - samma tre kandidater som tidigare, avvisade av samma skäl (BMW iX3 40
xDrive 1724 "300 kW" mot ev-specs 400 kW; VW ID.7 Tourer 1743 utan konkret siffra; Range Rover
P400e Autobiography 1738 mot tomt ev-spec-svar). Ingen ny marknadsfaktarad - inget nytt ämne med
färsk siffra i natt.

**Laddpriser (måndag):** försökte nå nätverkens egna prissidor (Ionity, Tesla, Circle K,
Vattenfall InCharge, E.ON, Mer, Allego, Clever) - samtliga antingen blockerade (403/000, troligen
molnmiljöns utgående allowlist eller bot-skydd) eller JavaScript-renderade utan statiskt
prisdata i HTML-svaret (Ionity, Circle K). 0 kontrollerade, 0 ändrade - reserven i
`laddpriser.json` står kvar oförändrad enligt regeln "kan du inte nå sidan, hoppa över
nätverket".

**Splashvakten:** Dom "splasharna stämmer" (exitkod 0). Alla fem appar OK (Java 27, Spring Boot
3.5.16, PostgreSQL 18.4 i tre). Elbilsladdning hade samma sju INFO-rader som tidigare -
oförändrat.

**Ingen kodfix i natt.** Inget hål i kategorivakten, ingen parser som slutat träffa, inget tal
som gick att belägga som fel bortom det redan kända (laddtips-kandidaternas avslag, Golf
GTI-observationen som inte är entydig nog att fixa). Byggt och testat grönt: se nedan.

**Grenen:** `auto/morgonfix` fanns inte (borttagen av auto-merge efter 10-04:s PR). Skapad på
nytt från `origin/master` (277021a). Ingen fast PR att flytta (avsnitt 11 gäller inte).

**Baslinjen:** uppdaterad till nattens mått (se docs/baslinje.json), commit 277021a,
kontrollräkning 3313/3313.

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
