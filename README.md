# CarAdvice – AI Bilrådgivning

[![Build & Test](https://github.com/elitrobban89/CarAdvice/actions/workflows/maven.yml/badge.svg)](https://github.com/elitrobban89/CarAdvice/actions/workflows/maven.yml)

En AI-driven bilrådgivare byggd med Java Spring Boot och Groq AI. Användaren fyller i sina preferenser i ett WordPress-formulär och får tre skräddarsydda bilrekommendationer på sekunder.

**Live:** [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/) — tjänsten används av riktiga besökare: sajten elitrobban.se har hittills under 2026 haft ~2 400 besökare och 7 900+ sidvisningar (3,05 sidor per session)

---

## Funktioner

### Sök & rekommendationer
- Rekommenderar välrecenserade bilar baserat på kategori, budget och körbehov
- Stöd för familjebil, SUV, elbil, laddhybrid och småbil. **Ekonomibil och Småbil slogs ihop 2026-08-10** — de överlappade redan i appens egen text ("Prisvärda småbilar: Sandero, Fiesta, Fabia, Polo, Yaris" mot "Stadsbilar: up!, C1, Picanto, Aygo"), och skillnaden supermini/stadsbil är inget en köpare väljer på. Småbil överlevde: 85 av 89 insikter i ett stickprov bar redan den etiketten (11 rader migrerades med `rename-category`), och namnet är konkret — "ekonomibil" är en känsla om driftkostnad som lika gärna kan gälla en begagnad Passat. Nivåstegen är **unionen** av de två, med taket ärvt från ekonomibil (250 000 kr) eftersom kategorin nu rymmer superminis och inte bara stadsbilar. Värdet lever kvar i tre lager utanför vår kontroll — delade länkar, localStorage och sparade sökningar — så `CA_CAT_ALIAS` översätter `ekonomibil` till `smaabil` vid varje läsning; utan det sätts `<select>` till ett värde som inte finns och fältet blir **tomt**, samma fel som `usage=familj` gav i en delad länk samma dag
- Drivmedelsfilter: bensin, diesel, hybrid, el — döljs automatiskt för elbil/laddhybrid (kategorin styr redan drivmedlet där); för familjebil sätts drivmedel automatiskt till "el" när laddbox hemma = Ja (kan ändras manuellt igen); AI-prompten har kategoribundna exempellistor (SUV: XC40/C-HR för bensin/diesel/hybrid, EX40 för el; Småbil: Aygo för bensin, Zoe/Renault 5 för el) så att t.ex. XC40 och EX40 aldrig blandas ihop
- **AI:ns egna marknadspåståenden tas bort ur `whyRecommended`** (`utanMarknadspastaende`): fältet ska bära en källa ("Teknikens Värld: toppbetyg"), men live 2026-08-10 skrev modellen *"Blocket-annonser visar begagnatgolv 199 000 kr för 2021-modell med 11 800 km"* — en påhittad siffra som renderas i kursiv **direkt under kortets verifierade prisrad**, där det stod 239 900–469 900 kr ur riktiga annonser. Två motstridiga Blocket-siffror med två centimeters mellanrum, varav den påhittade ser mest specifik ut. Samma skäl som bakom FABRICERA ALDRIG PRISER: en gissad siffra bredvid en verifierad är värre än ingen siffra alls. Prompten säger nu ifrån **och** meningen tas bort i kod — bara den meningen, inte hela fältet, eftersom källhänvisningen är värd att visa
- **Banderoll när kraven är för snäva** (`narrowCriteria`): prompten kräver tre bilar, men regelvakterna får fälla — och gör det rätt. Live 2026-08-10 gav familjeelbil + 400 l bagage + 200 000 kr **ett enda kort** (MG5 578 l), eftersom MG4 (363 l) och Kia Niro EV (349 l) inte klarade bagagekravet. Utan förklaring läser ett ensamt kort som att appen krånglar i stället för som ett svar på en hård fråga. Rutan räknar upp **kraven användaren faktiskt ställde** ("ren elbil · minst 400 liter bagage · familjestor bil · högst 230 000 kr") och föreslår att lätta på ett av dem — listan byggs ur `CarPreferences` och inte ur vakternas utfall, eftersom vilka vakter som fällde vad är borta när svaret sätts ihop, medan kraven alltid finns kvar och är det användaren kan ändra. **Noll bilar är ett svar, inte ett fel**: klarade ingen bil kraven returnerar `getRecommendation` tomt i stället för att kasta. Förr blev det HTTP 500 "AI:n föreslog en bilmodell som inte kunde verifieras" — ett tekniskt fel som skyllde på AI:n för något som oftast är ett rimligt svar på en hård fråga, och sökningen har numera **fem vakter som kan fälla samtidigt** (okänd modell, årsmodell, familjestorlek, drivmedel, bagage, prisgolv), så chansen att ingenting överlever hela kedjan har vuxit. Live 2026-08-10 gav samma sökning HTTP 500 i ena körningen och ett Tesla-kort i nästa. Rutan byter då rubrik till "Ingen bil matchade alla dina krav". **Visas aldrig samtidigt som budgetbanderollen**: servern skickar bara det ena beskedet (`narrowCriteria` sätts bara när `budgetShortfallFromKr` saknas), eftersom två rutor med överlappande budskap läser som ett renderingsfel — samma lärdom som budgetrutan och kategorivarningen gav
- **Kandidatlistan står i FÖRSTA prompten, inte bara i rättelsen** (`affordableModelsLine`): golvvakten nedan började bita men gjorde svaret **tunnare i stället för bättre** — live 2026-08-10 föll EV6, Ioniq 5 och Enyaq korrekt ur en 200 000-sökning, men omförsöket fyllde inte på till tre och båda sökningarna gav **ett kort**. Vakten säger vad som är fel; den här raden säger vad som är rätt, innan modellen hunnit gissa: "MODELLER SOM RYMS I BUDGETEN: Renault Zoe (fr. 58 000), … MG4 (fr. 195 000), VW ID.3 (fr. 199 000). Utgå från dessa" plus de modeller som ligger över taket och kastas. Samma data ur `EV_PRICE_FLOOR_KR`, annan tidpunkt. Utelämnas när den inte tillför något — icke-elbilssök (golven är elbilsgolv), nybil och leasing (fel prisunderlag) och när budgeten rymmer hela tabellen ändå, eftersom raden då bara kostar tokens ur en minutbudget som redan är den bindande gränsen
- **Golven är en kodvakt, inte bara prompttext** (`requireAffordableModels`): två live-sökningar 2026-08-10 visade att texten inte räckte — elbil + 200 000 kr gav **Kia EV6 i båda körningarna**, med sitt eget golv på 317 000 utskrivet tio rader ovanför regeln som förbjuder det, tillsammans med Ioniq 5 och Enyaq. Samma lärdom som familjespärren, drivmedelsvakten, årsmodellvakten och bagagekravet gav. Vakten fäller varje bil vars kända golv överstiger budget + 30 000 kr och kör **före** Blocket-uppslaget, så rättelseförsöket hinner peka ut både de fällda bilarna och **vilka modeller som faktiskt ryms** — utan den listan föreslår modellen ofta en lika dyr bil igen. Den är alltså inget dubbelarbete mot `exceedsBudgetCeiling`, som mäter mot verkliga annonser efteråt. Gäller bara begagnatsök (`harGolvvakt`): golven är begagnatpriser, så nybilssök och leasing mäts mot fel tal. Okänd modell släpps igenom — tabellen är en handfull mätta modeller, inte en marknadsöversikt. **Promptraden genereras ur samma tabell** som vakten läser, annars glider text och kod isär vid nästa mätning; talen formateras med `Locale.ROOT` eftersom svensk locale ger hårt mellanslag (U+00A0) som grupperingstecken och då matchar varken testet eller prompten det som faktiskt står där
- **Elbilar prissätts mot uppmätta begagnatgolv, inte mot nypris × ålderskoefficient** (`EV_PRICE_FLOORS`): elbil var enda kategorin utan exempellista i prompten (SUV och Småbil har sina), så AI:n fick nypristabellen plus deprecieringsregeln och räknade fram begagnatpriserna själv — systematiskt för högt. Live 2026-08-10 gav elbil + 200 000 kr förslagen EV6/Leaf/Polestar 2, där EV6:s billigaste annons låg på 316 990 kr och utlöste banderollen om att budgeten inte räcker, medan MG4 fanns från 193 990 kr och MG5 från 179 700 kr — ingen vakt hade fällt någon av dem, de föreslogs bara aldrig. Prompten bär nu golven rakt av (Zoe 58 000 → EV6 317 000) som prisankare. **Siffrorna speglar `CA_BUDGET_LEVELS.elbil` i `car-advice-main.js`** — samma tal sett från två håll: budgetrutan säger vad pengarna räcker till, listan säger åt AI:n vad den får föreslå, och glider de isär motsäger sidan sig själv i samma vy (rutan sa "MG4 kring 195 000" medan motorn föreslog EV6 för 316 990). Mätta mot Blocket med **högst 10 000 mil**, samma outlier-trimning som prisraden på korten; mät om båda listorna samtidigt
- **En bil som bara finns i databasen blir aldrig föreslagen** — den måste stå i promptens kuraterade lista. MG5 fanns i `ev_spec` (61,1 kWh, 400 km), i `cargo_spec` (578/1 456 l), i modell-whitelisten och var inte småbilsmarkerad, alltså inget hindrade den — men eftersom den saknades bland familjebilsexemplen föreslog AI:n den aldrig. Live 2026-08-10 gav elbil + 200 000 kr i stället EV6/Leaf/Polestar 2 och banderollen "budgeten räcker inte" (billigaste matchande 274 900 kr), medan MG5 låg på Blocket **från 179 700 kr** med under 10 000 mil — 18 annonser, flera på 4 500–6 500 mil kring 190 000 kr. Den är marknadens billigaste elbil med kombikaross och därför en självklar familjeelbil i det prisläget. Samma lärdom som vaktprompterna gav: **modellen agerar på namngivna exempel, inte på vad som råkar ligga i databasen**
- **Bagagefilter (`minCargoLiters`):** "Minsta bagageutrymme" med nivåer satta efter **karosstyp**, eftersom det är karossen som avgör vad som får plats — halvkombi 300 l (Zoe, Yaris) · kompakt-SUV 400 l (Kamiq, Golf) · mellankombi 500 l (V60, Niro EV) · stor kombi/stor SUV 600 l (Octavia Combi, MG5) · stor SUV eller skåpbil 700 l (V90, EV9). Prompten får samma tabell med typiska volymer uppfällt/nedfällt (kompakt-SUV 400–520 / 1 300–1 600 l, mellankombi 500–610 / 1 500–1 650, stor kombi 600–700 / 1 700–1 950, stor SUV 7-sits 600–780 / upp till 2 000, skåp-/fritidsbil 650–900 / 2 500–3 900) plus instruktionen att **byta karosstyp** i stället för motor eller utrustningsnivå när kravet inte nås — en siffra säger ingenting förrän den står bredvid en bil man känner igen. Eget filter **vid sidan av passagerarantalet**, inte i stället för: antalet säten säger hur många som får plats, volymen vad som får plats i bagaget, och en femsitsig småbil och en kombi har samma passagerarantal med helt olika lastförmåga. Kravet är **inte bara prompttext** — `requireCargoCapacity` mäter varje förslag mot `cargo_spec` och fäller bilar under gränsen, med samma omförsök som de andra regelvakterna. Mätt på **normalvolymen** (baksätet uppfällt): maxvolymen är nästan tre gånger så stor (MG5 lastar 578 l mot 1 456 l nedfällt), så fel kolumn hade gjort kravet verkningslöst. Faller bara på **positivt bevis** — en uppmätt volym under kravet — eftersom `cargo_spec` hade 243 rader den 2026-08-10 mot modell-whitelistens ~700; att kasta det omätta hade tagit fler bra bilar än dåliga. Fältet **injiceras från JS** (`caEnsureCargoField`, idempotent) så WordPress-sidan får det utan att snippeten klistras om, och är en `select` och inte ett reglage av en konkret anledning: all slider-styling i snippeten är bunden till `#ca-budget-slider` med ID-selektorer inklusive `::-webkit-slider-thumb`, som inte går att sätta inline, och ett injicerat `<style>` blockeras av sidans CSP
- **Kategorin heter "Elbil"**, inte "Elektrisk bil" — och betyder rena batterielbilar. Namnet rättas också från JS (`caFixCategoryLabels`), eftersom WP-sidans snippet är en manuell kopia där den gamla etiketten annars står kvar till nästa inklistring. Innebörden var redan kodad: kategorin `elbil` sätter `fuelIntent(...).pureEv()`, som både lägger ELBIL OBLIGATORISKT i prompten och kopplar in `requirePureEvCars`
- **Växellådsfilter:** manuell / automat — döljs automatiskt för elbil/laddhybrid; AI-prompten begränsas till vald växellåda
- Budget-slider med tickmärken och live-uppdaterat värde:
  - **Köp-läge** (standard): 50 000–1 000 000 kr, steg 25 000 kr (tickmärken: 50k · 200k · 400k · 700k · 1M)
  - **Leasing-läge:** 1 000–15 000 kr/mån, steg 250 kr — AI konverterar till ungefärligt listpris (×70) för kontextuell matchning
  - Köp/Leasing-knappen sitter inline i budget-etiketten; separata värden sparas per läge
  - **Burnout-laddaren under sökningen** (`caBurnoutStart`): ett glasmorphism-kort med ett spinnande bildäck (260 ms per varv), rök som väller ut under kortet och en ljusstrimma som blixtrar över glaset var 2,6:e sekund, under den cyklande laddtexten ("Kollar Bilprovningens statistik…"). Däcket är ritat efter en Continental PremiumContact 7 (referensbild från användaren): tjock sidovägg med präglad text och blankpolerad flerekrad alufälg. Det är dock sett **rakt framifrån**, inte i 3/4 som produktbilden — en snedställd ellips som roterar kring sin mitt vinglar som ett mynt på ett bord i stället för att snurra, och rotationen är hela poängen. Däcket måste ha **mönster** — en jämn ring går inte att skilja från en stillastående, så slitbanans klackar är det som gör varvet synligt; de ska vara **fina och täta**, grova streck läser som ett kugghjul. Fälgens fönster ska vara **kilformade**, smalast mot navet: runda fönster gav en blomma i stället för ekrar. Båda felen syntes först i en 4×-förstoring, inte i 1×-rendering. Röken ligger **bakom** glaskortet (`z-index`), annars läser den som dis framför i stället för som rök under däcket. Plymen går åt **ett** håll — ut åt höger från däckets kant, som på en riktig burnout där bilen står still och gummiröken vräker ut bakom hjulet; symmetriska puffar åt båda hållen läser som en dimmaskin. Den ska dessutom ligga **lågt och långt**: en puff som stiger rakt upp läser som ånga. Bakgrundsglöden är förskjuten åt vänster så den inte konkurrerar med plymen. Byggd **från JS**, inte i HTML-snippeten — WordPress-sidan är en manuell kopia och hade annars saknat effekten tills snippeten klistrats in på nytt, samma skäl som budgetrutan. Rörelsen körs med **Web Animations API** i stället för `@keyframes`: ett injicerat `<style>`-element hade varit kortare men sidans CSP är stram, och en blockerad stilregel ger ett stillastående hjul som ser trasigt ut — `element.animate()` är ett JS-API och berörs inte. Glöden ligger i kortets **bakgrundslager**, inte som ett absolut lager ovanpå, eftersom ett överlagt sken tvättar ur det som ligger under. `prefers-reduced-motion` ger ikonen stilla. **Fälla vid verifiering:** WAAPI skriver aldrig sina värden till DOM:en, så en skärmdump tagen via `outerHTML` visar bara grundstilarna och blixten ser ut att saknas — läs de animerade värdena med `getComputedStyle` och kopiera över dem på klonen
- **"⚡ X kr räcker till:"** — kurerad ruta under reglaget som säger vad budgeten köper på begagnatmarknaden (`CA_BUDGET_LEVELS`). Finns för **alla sex kategorier** — ekonomibil 💰, småbil 🚘, elbil ⚡, familjebil 🚗, laddhybrid 🔌 och SUV 🚙 — med egna nivåer per kategori av nödvändighet: samma 150 000 kr köper en helt annan bil i varje. Nyckeln måste matcha `option`-värdet i snippeten exakt (småbil heter `smaabil`). Ekonomibil och småbil är de enda kategorierna där budgeten kan gå **förbi** segmentet: en fabriksny Kia Picanto kostar ca 150 000 kr, så 400 000 köper inte en bättre småbil utan bara fel bil — toppnivåerna pekar därför vidare till en annan kategori i stället för att hitta på en dyrare modell. De fyra kategorier vars reglage går hela vägen till 1 000 000 kr har ett extra steg vid 749 000 — högsta nivån täckte annars 549 000–1 000 000, alltså ~45 % av reglagets spann, på en enda mening. Över 549 000 nämns inga priser eftersom Blocket-mätningen har underlag på begagnat och inte på nypris; texterna där beskriver segmentet i stället för att gissa siffror. Laddhybrid mäts på `fuel`-fältet (`Plug-in Bensin`/`Plug-in Diesel`), annars blandar fritextsökningen på modellnamnet in bensin- och dieselvarianterna, och kategorin har ett tydligt golv: under ca 160 000 kr finns nästan inga annonser med låg mätarställning, vilket den lägsta nivån säger rakt ut i stället för att lova en bil som inte finns. Nivån bär sin egen rubrik när standardformuleringen inte passar: rutan skrev förut alltid "N kr räcker till:" före texten, vilket gav självmotsägelser på de nivåer som svarar "fel budget" ("150 000 kr räcker till: **För lite** för en laddhybrid", "600 000 kr räcker till: **Långt över** vad kategorin kostar") — de heter nu "räcker inte till en laddhybrid:" respektive "är mer än kategorin kostar:". **Taket och kategorirådet står på ett ställe** (`CA_OVER_CATEGORY`): varningen läste förut sitt eget tak (`CA_MAX_BUDGET`, 200 000/150 000) medan rutan bytte nivå först vid 249 000/199 000, så i glappet varnade den ena för att budgeten var för hög medan den andra sa att den räckte till precis rätt bil — och de pekade vidare till olika kategorier (varningen till laddhybrid, rutan till elbil). **Över taket talar bara varningen**: rutan döljs där (`overCategory`-flaggan på nivån), eftersom den annars upprepade varningens råd ordagrant direkt under den — två identiska meningar på rad läser som ett renderingsfel, inte som eftertryck. Taket ligger på reglagets rutnät (250 000/200 000, steg om 25 000 kr); 249 000 gick aldrig att ställa in och syntes bara som en udda siffra i varningen. Tusentalsavgränsaren i nivåtexterna görs om till hårdmellanslag vid rendering — annars bröts "MG4 kring 195 000" mitt i talet vid radslut. Kontrollerat med headless-rendering av den riktiga sidan i både 820 px och 390 px — den smala bredden i en iframe med fast bredd, eftersom headless renderar viewporten bredare än `--window-size`. **Leasing har en egen stege** (`CA_LEASING_LEVELS`, kr/mån, gemensam för alla kategorier eftersom avtalstid och milpaket väger tyngre än karossen): reglaget spänner 1 000–15 000 kr/mån och hade tidigare ingen ruta alls, alltså minst hjälp i det läge där siffran är svårast att översätta till en bil. De nivåerna är **inte mätta** mot annonser och nämner därför inga modellnamn — samma regel som för köpnivåerna. De två hållpunkter som finns i koden stämmer mot stegen: Enyaq 4 850–4 980 kr/mån (`BlocketPriceServiceTest`) hamnar i familjebilsnivån och EV6 GT-Line 8 295 kr/mån i SUV-nivån. Mät resten med `BlocketPriceService` i leasingläge (årsfiltret av, annars töms träfflistan) innan modellnamn skrivs in. Mätta 2026-08-08 mot **samma underlag som prisraden på korten** — högst 10 000 mil och medianrelativ outlier-trimning — annars lovar rutan en bil som bara finns som utsliten. Elbilstexterna låg då en nivå efter marknaden: "MG ZS EV kring 100 000 kr" var 129 900, ID.3 stod kvar på 299 000-nivån trots att den börjar vid 198 900, och Polestar 2/ID.4/Enyaq sades "börja bli möjliga" först vid 399 000 fast de går att köpa för 209 000/229 500/279 000. Siffrorna åldras — mät om med Blockets API (gratis, ingen Groq-kvot): `sales_form=1&2`, `mileage_to=10000`, `sort=PRICE_ASC`, och **årsfönster per modell** (`year_from`/`year_to`, ±1). Utan årsfönster matchar fritextsökningen fel bilar och ger orimliga golv — en Škoda Octavia 2025 för 75 600 kr och en "Volvo V90 från 2013", en modell som inte fanns då
- Varnar vid orimliga kombinationer (t.ex. ekonomibil + lyxbudget)
- **Budgettaket gäller även nybilssök:** kontrollen hoppades tidigare över helt när "Ny" var valt, eftersom begagnatpriset är fel måttstock där. Men slutsatsen håller i **en** riktning — kostar billigaste *begagnade* exemplaret mer än taket kan ett *nytt* omöjligt kosta mindre — och det var den enda oskyddade vägen. Live 2026-08-07 föreslogs MG4 (billigaste annons 249 900 kr) för en 200 000-budget, alltså 20 000 kr över taket, utan att någon spärr utlöstes. Leasing står fortfarande utanför: där är budgeten kr/mån och Blocket hämtas inte alls
- **Säger ifrån när kriterierna inte går ihop:** budgettaket kontrolleras mot riktiga Blocket-annonser och utlöser ett omförsök, men hittar inte heller det någon bil inom taket visas korten ändå med en banderoll som förklarar varför de är för dyra och vad billigaste matchande bil faktiskt kostar. Live-fynd 2026-08-07: 100 000 kr + max 3 år gav MG4 (fr. 249 900 kr), Enyaq (fr. 374 900 kr) och EV6 (fr. 349 000 kr) — helt korrekt givet att inget 3 år gammalt matchar 100 000 kr, men utan förklaring läste det som en trasig rekommendation. Svaret bär då `budgetShortfallFromKr`, och banderollen fylls på med **vad budgeten faktiskt räcker till** — `POST /api/budget-alternatives` kör samma sökning med ålderskravet lyft och returnerar upp till tre bilar inom taket med verkliga Blocket-priser (MG ZS EV kring 100 000 kr, Nissan Leaf 2016+, Renault Zoe), plus deras åldersspann. "100 000 kr räcker inte" är korrekt men torftigt när svaret "för de pengarna är det 5–10 år gamla elbilar, till exempel dessa" går att räkna fram. Egen endpoint för att inte lägga ett tredje Groq-anrop i `/api/recommend`, som redan har 35 s klienttimeout
- **Budgettaket gäller även leasing, i kr/mån:** leasingläget stod utanför taket så länge Blocket inte hämtades där. Live 2026-08-07 föreslogs Kia EV6 GT-Line på 8 295 kr/mån mot en 5 000-budget utan att någon spärr slog till. Nu mäts månadskostnad mot månadsbudget med en egen marginal på **500 kr/mån** — köpets 30 000 kr hade gjort taket meningslöst. En enda annons räcker för att fälla en leasingbil, till skillnad från köp: tvåannonskravet finns för att en ensam fel- eller scamannons inte ska fälla en prisvärd bil, men leasingannonser läggs av bilhandlare och utbudet per modell är tunt — EV6:an hade exakt en annons, så kravet på två släppte igenom just det fall taket byggdes för. `correctedPrice` står kvar på två annonser även i leasingläge: att fälla en bil på en ensam annons är en mindre risk än att skriva om priset användaren ser till den. Två fällor är utskrivna i koden: nyprisfallbacken stängs av i leasingläge (`ev_spec` bär kronor, och 370 000 > 5 000 hade fällt varenda bil), och `budgetShortfallFromKr` sätts aldrig där, eftersom banderollen skriver ut siffran som ett köppris "på Blocket just nu". Omförsökets prompt räknar också i kr/mån och föreslår längre bindningstid som utväg
- **Årsmodellen läses med eller utan parentes** (`CarTitle`): prompten ber om "Märke Modell (år)" och koden litade på det — sexton parsers i åtta klasser matchade bara parentesformen. AI:n skriver ibland "Volkswagen ID.4 2026" i stället, och då föll allt som hänger på årtalet samtidigt: Blockets årsfilter, dedupen, nyprisuppslaget och leasingmatchningen. Live 2026-08-07 fick en ID.4 därför hela modellens annonsspann (3 995–7 961 kr/mån) och missade sitt officiella leasingpris. Ett **bart** årtal måste vara 2010 eller senare, annars äter regeln modellnamn — Peugeot 2008 och BMW 2002 är bilar, inte årsmodeller (3008 och 5008 skyddas redan av att årtal börjar på 19 eller 20). **Titeln städas till "Märke Modell (år)" där AI-svaret tolkas** (`CarTitle.normalize`, anropad från `convertRecommendations`): live 2026-08-09 kom "Nissan Leaf 2019 60 kWh" och "Renault Zoe 2019 1.3 kWh" med årtalet i MITTEN, och eftersom `year()` är ankrad i slutet av strängen gav den `null` — samma fem funktioner slutade tyst fungera igen, nu inklusive årsmodellvakten. Att laga varje konsument hade blivit fem halva lösningar. Teknikuppgifter (`60 kWh`, `204 hk`, `150 kW`) plockas samtidigt bort ur titeln; "1.3 kWh" var dessutom påhittat, Zoe har 41 eller 52 kWh batteri. Trimnamn måste överleva städningen — "Kia EV4 Long Range" och "MG4 Extended Range" är modellnamn, inte brus. **Osynliga mellanslag städas först** (`U+00A0`, `U+202F`, `U+200B`): Javas `\s` matchar bara ASCII-blanksteg, så AI-titeln "Nissan Leaf (62&nbsp;kWh) (2020)" — med *smalt* hårt mellanslag mellan siffran och enheten — gick orörd genom teknikstädningen live 2026-08-10. Följden dök upp tre steg bort och utan en enda loggrad: eftersom "62 kWh" satt kvar i titeln matchade `verifiedEngineOptions` ingen `ev_spec`-rad, och kortet föll tillbaka på AI:ns egen motorlista precis som det ska göra när ingen träff finns. Den tomma parentes som blir kvar när tekniken lyfts ut (`Nissan Leaf ( ) (2020)`) tas bort samtidigt. Samma städning i `stripYear` och `year`, som bygger på samma ankare
- **Officiella privatleasingpriser från märkena** (`LeasingPriceService`): Blockets leasingannonser är handlarnas erbjudanden — riktiga, men blandade i bindningstid, årsmodell och kampanjvillkor. Märkets eget listpris är siffran kunden faktiskt möter, och den saknades helt. Källan bakom privatleasing.skoda.se är VW Financial Services plattform: `GET https://nosp-api.vwfs-se-nosp.vwfs.io/api/category?nocache=<ts>` med headern **`Channel: skoda`** — utan den svarar API:t 500. Ingen nyckel, ingen inloggning. Samma API betjänar hela koncernen: `skoda` (32 modeller), `vw` (29), `audi` (25), `cupra` (21), `seat` (7) — `volkswagen` ger 500, rätt värde är `vw`. Priset läses ur `preamble` ("Privatleasing från 5 295 kr/mån") för både kategorier och deras varianter. Matchningen är samma prefix-på-hela-ord-regel som dedupen: "Škoda Enyaq iV 80" hittar Enyaq (5 295 kr/mån) men ärver aldrig priset till Enyaq Coupé (6 925), och märkesordet i utbudets egna namn ignoreras ("CUPRA Born" mot titelns "Cupra Born"). Ingen träff är också ett svar: modellen går inte att privatleasa. **Volvo** ligger utanför plattformen och läses ur sidmarkupen på volvocars.com (`prices[ex30-electric].contract_hire` → 3 895 kr/mån), bara `contract_hire` — "Care by Volvo" på samma sida är ett abonnemang med försäkring och service, alltså ett annat pris för en annan sak. **Volvos botskydd svarar dock 403 mot Javas HTTP-klient** även med komplett webbläsaruppsättning headers (curl med samma headers får 200 — det är TLS-fingeravtrycket som fäller, inte headers), så i praktiken faller Volvo tillbaka på Blocket-annonserna; misslyckanden cachas 30 min så det inte blir ett anrop per bil. Märken utanför dessa (Toyota, Kia, Hyundai, Tesla …) har egna sajter med egna strukturer och täcks inte
- **Leasingkortet visar riktiga annonser, aldrig en framräknad siffra:** `caLeaseMonthlyEstimate` tog köppriset delat med 85 (backendens leasingfaktor baklänges) när AI:ns pris inte redan var kr/mån. En Škoda Enyaq iV 80 (2023) fick då "~2 000 kr/mån" räknat på ett begagnatpris — för en bil som inte ens går att privatleasa. Kortet ignorerade dessutom de verkliga leasingannonserna sedan de började hämtas. Ordningen är nu: Blockets privatleasingannonser (kr/mån), annars AI:ns kr/mån, annars **"ingen leasing hittad"** — en tom uppgift är bättre än en påhittad. Prompten kräver samtidigt att leasingförslag är modeller som säljs nya i Sverige i dag, med innevarande eller nästa årsmodell; en utgången årsmodell går inte att teckna privatleasing på
- **Prisraden räknas när annonser saknas:** `correctedPrice` kräver minst 2 annonser, så en bil utan annonser stod kvar med AI:ns gissning oemotsagd — Kia EV3 på "170 000–190 000 kr" för en bil som kostar 370 000 ny. Nyprisreferensen fäller numera bilen om den ligger över budgettaket, men en bil som ryms i budgeten hade kvar sitt påhittade intervall. Nu räknas priset i stället ur verifierat nypris med **samma nedskrivningskurva som prompten föreskriver för AI:n** (`DEPRECIATION_RULE`: ×0.85 efter 1 år, ×0.75 efter 2, … ×0.34 efter 8+): "ca 315 000 kr". Talet är märkt "ca" med flit — det är en beräkning, inte marknadsdata. Nybilssök får nypriset rakt av ("fr. 370 000 kr"), och saknas årsmodell i titeln i ett begagnatsök lämnas priset orört: utan ålder finns inget att skriva ned med, och en oskriven siffra är bättre än en påhittad
- **Nybilssök mäts mot nypriset, inte mot annonserna:** ber användaren om en NY bil är begagnatpriset inte vad hen betalar. Live 2026-08-07 gav 200 000 kr + "ny" en Nissan Leaf 2018–2020 med annonser från 129 800 kr — bilen passerade taket på ett begagnatpris i ett nybilssök, och användaren fick aldrig veta att budgeten inte räcker till en ny bil. Ordningen är nu omvänd i nybilssök: nypris först (`ev_spec` för el/PHEV, `new_car_price` för bensin/diesel), Blocket sekundärt för modeller vi saknar nypris för. Begagnatsök är oförändrat — där är annonserna verkligheten och nypriset referensen. `new_car_price` slogs tidigare aldrig upp per bil, bara som prompttext; uppslaget matchar modellnamnet ord för ord och väljer generation på årsmodellen ("Volkswagen Polo 2018-2021" mot "2022+"). Banderollen byter samtidigt besked: siffran är billigaste **nypris** och texten säger "som ny" i stället för "på Blocket just nu", med rådet att söka begagnat i stället
- **Tre förslag är garanterat tre olika modeller:** dedupen låg bara i budgetomförsöket, så utan omförsök fanns ingen kontroll alls — live 2026-08-07 kom "Volkswagen ID.4 (2024)" och "Volkswagen ID.4 Pro (2022)" i samma svar. Den körs nu på varje svar, och jämförelsen tål utrustningsnivåer: två titlar är samma modell när den enas ord är en inledning av den andras (ID.4 = ID.4 Pro, EV6 = EV6 GT-Line, Enyaq = Enyaq iV). Jämförelsen går på hela ord, aldrig tecken, så EX30 ≠ EX300 och Model Y ≠ Model 3. Dubbletten tas bort i stället för att ersättas — två olika bilar är mer värt än tre kort där två är samma bil
- **Leasingspannet tar bara den billiga änden:** företagsleasing ("Businesslease 1500mil/år"), månadshyra ("HYR PER MÅNAD", 12 375 kr/mån) och privatleasing ligger i samma träfflista utan något fält som skiljer dem åt — **bindningstid finns varken som annonsfält eller filter** (enda leasingspecifika filtret är `leaseprice_month`, och det finns ingen separat sök-id för leasing; `SEARCH_ID_CAR_LEASING` ger 400). Privatleasingen för en modell är däremot ett smalt band som ryms i de 50 billigaste, så leasing hämtar aldrig `PRICE_DESC`-änden: ID.4 låg på 3 021–4 695 kr/mån där, mot 12 375 kr/mån med dyra änden med. Ett anrop mindre per bil, och ingen gissning på annonstext
- **Prisraden i leasingläge är månadskostnad:** systemprompten beskriver `price` som ett köpprisintervall, så leasingkorten visade "399 000–449 000 kr" bredvid Blockets "4 850 – 4 980 kr/mån" — två prislägen på samma kort utan att säga vilket som var vad. Prompten ber nu om månadskostnad i leasingläge, och `correctedPrice` körs mot leasingannonserna med rätt enhet
- **Fritextsökningen matchar syskonmodeller:** `q=Volkswagen ID.4` gav ID. Buzz (699 900 kr), ID.5 GTX och Tiguan Allspace; `q=Kia EV3` gav en Kia PV5. Så länge felträffarna låg mitt i relevanslistan syntes de inte, men när dyraste änden hämtas separat satte en ID. Buzz taket för ID.4:s prisspann. Regeln är smal med flit: bär modellnamnet en siffra måste annonsens märke + modell ha samma siffra (ID.4 ≠ ID.5 ≠ ID. Buzz, XC60 ≠ XC90, EV3 ≠ PV5), medan namn utan siffror (Enyaq, Model Y, Leaf) inte filtreras alls — där finns ingen sådan förväxling att skydda mot, och en gissande regel hade kostat riktiga annonser
- **Leasing och köp är två skilda sökningar:** privatleasingannonser låg mitt i köpträfflistan med månadsavgiften i samma `price.amount` och samma `price_unit` "kr" — 4 495 kr bredvid 479 000 kr. Köpsökningen filtrerar nu på `sales_form=1&2` (begagnad + ny bil till salu) och leasing på `sales_form=5`, men filtret räcker inte ensamt: mätt 2026-08-07 låg leasingannonser på 4 495 kr märkta "begagnad till salu", så beloppets storlek avgör i sista hand (köp > 10 000 kr, leasing ≤ 20 000 kr). Leasingläget hämtade tidigare ingen Blocket-data alls och visar nu verkliga kr/mån — utan årsfilter, eftersom leasingbilar är nya (årsmodell 2026–2027) medan AI:ns titelårtal hör begagnatmarknaden till. `correctedPrice` körs aldrig i leasingläge: den hade jämfört kr/mån mot ett kr-pris
- **Prisraden är billigaste riktiga annonsen, för rätt årsmodell:** två fel gjorde siffran systematiskt för hög. (1) Undre gränsen var 20:e percentilen, alltså kastades en femtedel av marknadens billigaste annonser — Kia EV3 visades från 434 900 kr när billigaste annonsen låg på 369 900 kr, Dacia Sandero från 105 000 kr mot marknadens 42 900 kr. Nu tas bara uppenbara avvikare bort, med medianrelativa gränser (0,4× och 2,5×) som fångar fluff- och scamannonser. Sökningen sorterar dessutom på pris stigande i stället för relevans: träfflistan kapas vid 50 annonser och `page` ignoreras, så de billigaste bilarna låg gömda bakom taket — EV3 börjar på **359 000 kr**, inte 369 900. Är listan full hämtas även den dyraste änden (`sort=PRICE_DESC`), annars vore maxpriset den 50:e billigaste bilen, och antalet skrivs då "50+ annonser". (2) `year_min`/`year_max` i Blocket-anropet **ignorerades helt av API:t** — rätt namn är `year_from`/`year_to`, som API:t själv listar i sitt `filters`-svar (verifierat 2026-08-07: identiskt svar med och utan, liksom med `year=`, `modelYear_min=` och `filter=[{"key":"year"}]`), så varje årsmodell av samma bil fick identiskt prisspann — en Leaf 2018 och en Leaf 2022 visades båda 129 900–198 900 kr. Årsmodellen filtreras nu på `docs[].year` (±1 år) i vår egen kod. Sidoeffekt: en fabricerad årsmodell ger nu noll annonser i stället för fel bils priser, vilket är precis vad nyprisfallbacken nedan är till för
- **Billigaste annonsen får inte vara marknadens mest slitna bil:** köpsökningen filtrerar nu på **max 10 000 mil** (`mileage_to`, enheten är alltid `SCANDINAVIAN_MILE`). Utan gränsen sattes golvet av utslitna exemplar, och eftersom budgettaket mäts mot billigaste annonsen blev bilar "köpbara" på en bil ingen tänkt sig: live 2026-08-08 gav en 200 000-budget Škoda Enyaq med prisraden "från 229 900 kr" — en bil som gått **21 091 mil** — och den slank därmed under taket (budget + 30 000 kr). Billigaste Enyaq under 10 000 mil kostade 339 900 kr. Samma bild för ID.4 (249 000 kr/15 742 mil mot 309 900 kr) och EV6 (279 900 kr/18 735 mil mot 316 990 kr), alltså tre bilar som alla borde ha fallit. Gränsen gör inte utbudet magrare utan tvärtom: träfflistan kapas vid 50 annonser, så slitna bilar tar platser från de köpbara — ID.4 gav 42 användbara annonser utan gränsen och 46 med. Äldre billiga elbilar överlever (Leaf 2018 fr. 79 800 kr, Zoe 2019 fr. 79 900 kr, e-Golf 2019 fr. 144 900 kr), och för en 250 000-budget blir svaret det förväntade: ID.3 fr. 198 900 kr (9 525 mil), Kona Electric fr. 229 900 kr (7 500 mil), MG4 Long Range fr. 249 900 kr (5 606 mil). Två skyddsnät: ger den milfiltrerade sökningen färre än 2 annonser mäts bilen mot hela marknaden i stället (taket kräver två annonser för att fälla en bil, så en tunn lista hade tyst avväpnat det), och annonser **utan** ifylld körsträcka släpps igenom. Leasing är undantaget — de annonserna gäller nya bilar och saknar körsträcka. `milage_to` och `mileage_max` ignoreras tyst av API:t precis som `year_min`/`year_max`, så `docs[].mileage` filtreras om i vår egen kod
- **Nypriset som referens när Blocket är tyst:** budgettaket och `correctedPrice` kräver båda minst 2 annonser, så en bil utan annonser kunde aldrig fällas — oavsett vad den kostar. Live 2026-08-07 föreslogs Kia EV3 för en 200 000-budget med AI-priset "170 000–190 000 kr" och noll annonser, medan kortets eget `evSpec` bar 370 000 kr. Saknas annonser finns inget bevis att bilen går att köpa alls, och då avgör nypriset ur `ev_spec`. Blocket är fortfarande måttstocken: finns annonser vinner de alltid, och banderollens siffra hämtas aldrig ur nypriset eftersom den skrivs ut som "… på Blocket just nu"
- Anpassar råd efter körsträcka, laddmöjlighet och ny/begagnad
- **Verifierad bränsleförbrukning:** AI:ns gissade l/mil ersätts med verifierad siffra ur `ice_consumption`-tabellen (957 motorvarianter) — närmaste hästkraftstal väljer variant, användarens drivmedelsval filtrerar
- **Feedback-loop:** bilar med övervägande tummen ner injiceras som undvik-signal i AI-prompten (uppdateras varje timme)
- **Bilrecension på YouTube per bilråd:** varje rekommenderat bilkort får en klickbar rad längst ned med thumbnail, videotitel och kanal. Svenska bilkanaler prioriteras (Elbilsmagasinet, Peter Esse, Teknikens Värld, Vi Bilägare, Auto Motor & Sport), engelska används i andra hand (Autotrader, carwow, Top Gear, Auto Express). Sökningen accepterar även *första provkörningen* av en nylanserad bil, eftersom en färdig recension sällan hunnit publiceras då. Videon slås upp en gång per modell och cachas — se [`GET /api/car-video`](#get-apicar-videocarvolvoex60)
- **YouTube-betyg per bilråd:** i samma block, under en tunn avdelare, visas 👍 Bra / ⚖️ Blandat / 👎 Dåligt utifrån vad kommentarerna under recensionen säger om **bilen** — beröm av kanalen eller filmningen räknas inte. Rutan innehåller antalet kommentarer domen bygger på och en mening om vad de lyfter ("flera vittnar om mjukvarustrul men berömmer stolarna"). Under 12 bilrelaterade kommentarer visas ingen ruta alls: kommentarsfält är inget representativt ägarurval, och ett betyg på tunt underlag är sämre än inget betyg
- **Dagsaktuella bränslepriser:** bensin/dieselpris hämtas från Bilresa-backendens `/api/fuel-price` (globalpetrolprices.com) och injiceras i alla AI-promptar (`FuelPriceService`, 6 h cache) — chatt och rekommendationer räknar bränslekostnad på verkligt pris; ägandekostnadskalkylen i frontend använder samma källa (6 h localStorage-cache, fallback 18/17,50 kr) i stället för hårdkodade priser
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
- **Vandrande färgkant** — samma grepp som chattpanelen: en conic-gradient ritad som ring med `mask-composite` och roterad via den registrerade vinkelvariabeln `--ca-rim-ang`. Heron får hela färgskalan på 9 s; korten håller sig i sin egen accentfärg (så numreringen fortfarande går att läsa på färgen), går långsammare (14 s), svagare (opacity .55) och med förskjutna starter så de tre inte pulserar i lockstep. Tänds vid hover. Ringen ligger i `::after` eftersom `::before` redan är upptaget av aurora-orben, och innehållet lyfts med `z-index` — en absolut pseudo utan det tvättar ur korttexten. Utan `@property`-stöd står vinkeln still och kanten blir en statisk färgring i stället för att sluta fungera
- **Prestanda (uppmätt med Playwright, riktig tid — `--virtual-time-budget` snabbspolar och gör rAF-mätning meningslös)**: harnessens tak är 60 fps. Före ringarna låg sidan redan på 36,8 fps; ringarna kostade ~5 fps (nästan allt heron — kortens tre ringar var inom brus). Den verkliga boven var `ca-hue`, animerad `filter: hue-rotate()` på heroauroran: **31,4 → 43,9 fps enbart av att ta bort den**, eftersom den om-filtrerar hela heron varje bildruta. Den togs bort — den fejkade färgrörelse på en enfärgat lila gradient och är överflödig nu när auroran har riktiga färger och ringen ger äkta vandring. Nettot blev 29,3 → 36,6 fps, alltså **oförändrad prestanda mot före ändringen men med färgvandring tillagd**. `ca-hue` är kvar på `#ca-btn` där ytan är liten (mätt till ~1 fps)
- **Bilbilder** — varje kort hämtar automatiskt en thumbnail från Wikipedias öppna REST API; trefallsordning: direktträff (engelska) → suffixvarianter (`_EV`, `_electric`) → Wikipedia opensearch (fuzzy titelmatchning, upp till 3 kandidater); svenska Wikipedia som sista fallback; döljs tyst om ingen bild hittas
- Sektionsrubriker (Fördelar / Nackdel / Passar dig) med dividers för tydlig läsbarhet
- **"Fråga om denna bil"-knapp** på varje kort — markerar kortet med glödande ram och öppnar chatboten fokuserad på just den bilen
- **Trustpilot-handlartips** under korten — länk till Trustpilots kategori för bilfirmor så användaren kan kolla handlarens omdömen innan köp
- **AI-disclaimer** under korten — påminner om att fritext (fördelar, expertomdöme, "passar dig") kan innehålla fel även om priser stäms av mot Blocket

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
- Batteristorlek (kWh) och startpris; **batterikemi (LFP/NMC) visas direkt i 🔋-chippen** — t.ex. "51 kWh · LFP" eller "75 kWh · NMC"
- **Prisvärdhetsbedömning** (Utmärkt / Bra / Ok prisvärdhet) — sammansatt poäng av räckvidd/kr (60 %), batteri/kr (40 %) + DC-laddningsbonus; visas nu även för bensin/diesel baserat på hk + förbrukning per kr
- Trestegsfuzzy-matchning mot EV-databasen: (1) alla titelord i lagrad namntext, (2) alla lagrade namnord som exakta ord i titel, (3) alla titelord som exakta ord i lagrat namn — förhindrar att "Kia Niro PHEV" matchar "Kia Niro EV" och att "MG4 2025" missar "MG4 Long Range"
- Årstal strippas innan matchning oavsett om det skrivs med eller utan parenteser
- **"Motor & batterialternativ" är verifierat mot `ev_spec`, inte AI-text** (`verifiedEngineOptions`): alla matchande varianter hämtas och **varje variant får en egen rad med sitt trimnamn** — MG4 gen 1 blir "51 kWh (350 km) · Standard Range, 64 kWh (450 km) · Long Range, 77 kWh (520 km) · Extended Range"
- **Räckvidden skiljer dubblett från variant**: kapaciteten ensam räcker inte. Samma bil listad av två källor har olika kWh men **samma** räckvidd (EV6 Standard Range ligger inne som 60 kWh/428 km och 63 kWh/428 km — netto och brutto för ett batteri), medan två verkliga varianter har samma kWh och **olika** räckvidd (MG4:s 52,8 kWh är Urban Comfort 416 km *och* Urban Premium 405 km; 61,7 kWh är Premium Long Range 452 km *och* XPOWER 405 km). Tidigare slogs allt inom 8 % ihop till ett spann, så de fyra MG4-varianterna blev två namnlösa rader — "52.8 kWh (405–416 km)" — och användaren kunde inte se vilken version raden gällde, vilket var hela poängen med fältet. Toleransen för "samma räckvidd" är 2 %: tillräckligt för avrundningsglapp mellan källor, för snävt för en verklig skillnad (MG4:s tätaste par ligger 2,6 % isär). Ihopslagningen letar bland **alla** grupper och inte bara den intilliggande — listan sorteras på kapacitet, så EV6 Long Range 2WD:s två rader (80 kWh/582 km och 84 kWh/582 km) hamnar med tre andra rader emellan och visades annars som två bilar. En hopslagen rad lämnas omärkt: den beskriver samma bil under två källors namn, och vilket av dem som skrevs ut vore godtyckligt
- **ev-databases aliasrader raderas** (`isAliasName`): Volvo EX30 låg som både Single Motor/Twin Motor Performance/Cross Country (våra namn, bruttokapacitet) och P3/P5/P8 AWD (ev-databases namn, nettokapacitet) — samma fyra bilar en gång till. Så länge närliggande batterier slogs ihop syntes det inte; med en rad per variant blev EX30 nio rader för fyra bilar. Spärren sitter i synken och inte bara som en radering, av samma skäl som `EXCLUDED_BRANDS`: annars återskapas raderna 02:00 nästa natt
- **Årsmodellen i annonsen väljer generation**: MG4 finns som två generationer med olika batterier (51/64/77 kWh mot 41,9/52,8/61,7/74,4). Utan filter listade kortet alla sju för båda — sant men obrukbart, och för en begagnatannons dessutom vilseledande, eftersom siffrorna gäller en bil som inte fanns när annonsbilen tillverkades. Filtret slår bara till när det finns något att välja mellan: saknar titeln årsmodell, eller bär ingen av modellens rader en generation, lämnas listan orörd
- **Fallbacken till AI-text är tyst** — hittas ingen `ev_spec`-rad behålls AI:ns egen motorlista med flit (hellre AI-text än inget fält). Priset är att ett matchningsfel *uppströms* ser ut som ett innehållsfel nedströms: det var precis så U+202F-buggen ovan visade sig, som "MG4:s versioner syns inte på kortet"

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
- **🔧 Motoralternativ** — kommaseparerade motorvarianter, varje variant som pill-chip; för elbilar med `ev_spec`-träff hämtas ALLA verifierade kWh/räckvidd-varianter ur databasen (ersätter AI:ns fritext helt — se "Senaste bugfixar"), annars AI:ns egen text
- Hästkrafter och Prisvärdhet visas för alla biltyper (inte bara EV)
- Vid EV/PHEV: WLTP · Sommar · Vinter · Laddning · DC max · AC max · Batteri (inkl. LFP/NMC)

### Jämförelsekontext för AI (verifierade specs)
Innan AI-anropet hämtas verifierade specifikationer ur databasen och statiska kartor och injiceras i prompten som kontext:
- **Benutrymme bak (mm)** — bakre benutrymme i millimeter för 55+ modeller; data från evspecifications.com (mätvärden) resp. kända uppskattningar för övriga; EX30=821 mm, XC40/EX40/C40=917 mm, Model Y=1 029 mm m.fl.
- **Storleksklass** — om skillnaden i benutrymme överstiger 60 mm instrueras AI att explicit nämna det i jämförelsen ("EX30 är en kompakt bil, XC40 är en mellanstor SUV — avsevärt mer plats i baksätet")
- **Batterikemi (LFP / NMC)** — visas per bil i jämförelsetexten med förklaring: LFP kan laddas till 100 % dagligen utan slitage (~3 000+ cykler), tåligare i kyla; NMC ger högre energitäthet och längre räckvidd per kg men laddas helst till 80 % (~1 000–2 000 cykler); AI lyfter skillnaden som konkret fördel/nackdel; 130+ modeller kartlagda inkl. Citroën ë-C3=LFP, Ford Puma Gen-E=LFP, hela BYD-familjen=LFP, Leapmotor=LFP
- **Snabbladdning (DC)** — AI förklarar att DC = snabbladdning (t.ex. längs motorväg), att ≥150 kW är bra och att AC = hemmaladdning (max ~22 kW); DC-maxvärde från databasen injiceras per bil
- **Bagageutrymme** — standard- och maxvolym (L med fällda säten) injiceras från `cargo_spec`-tabellen
- Färgkodade kolumnrubriker matchar kortens accentfärger
- **TCO-stapeldiagram** under tabellen — horisontella staplar för varje bil med fem färgkodade segment: 🟣 värdeminskning · 🟠 drivmedel · 🔵 service · 🟢 fordonsskatt · 🩷 halvförsäkring; hover-tooltip visar kostnad i tkr per post

### Chatbot
- Flytande knapp nere till höger med bil-ikon i glassmorphism-design; lila/indigo-tema
- **Frostat glas som faktiskt är genomskinligt** — panelens bas ligger på 0.01, så sidan bakom syns rakt igenom `blur(34px) saturate(180%)`. Läsbarheten bärs i stället av att varje textbärande del (header, bubblor, snabbknappar, inputrad, disclaimer) har egen tätare bricka
- **Adaptivt ljus/mörkt läge** — `caChatSyncGlass` mäter luminansen bakom panelen (tre punkter, `elementFromPoint` med `pointer-events` tillfälligt av) vid öppning, scroll och storleksändring. Ljus bakgrund → vitt glas med mörklila text, mörk bakgrund → lila glas med ljus text. Mätfel ger alltid mörkt läge, så en trasig mätning kan aldrig göra texten oläslig
- **Skiftande färgglöd** — mjuka färgfält (lila/cyan/rosa/turkos) driver bakom innehållet i 24 s och en conic-gradient-ring vandrar runt kanten på 9 s. Endast `transform` och en registrerad `@property`-vinkel animeras (uppmätt 61 fps med backdrop-filter kvar); avstängt vid `prefers-reduced-motion`
- Svarar på köpråd för alla drivmedel (bensin, diesel, hybrid, elbil)
- Streaming-svar — token för token via SSE; automatisk fallback till JSON om ReadableStream saknas
- **Kontextuell efter sökning** — FAB-etiketten och snabbknappar uppdateras med de rekommenderade bilarna; benutrymme bak (mm), batterikemi (LFP/NMC), Euro NCAP-betyg och verifierad bränsleförbrukning för de rekommenderade bilarna injiceras automatiskt i chattens systemprompt så AI:n kan svara korrekt på frågor som "kan jag ladda till 100%?", "hur mycket plats är det i baksätet?" eller "hur säker är den?"
- **AI-disclaimer** i chattpanelens footer — påminner om att AI-svar kan innehålla fel
- **"Info & prenumeration"-knapp** ovanför inmatningsfältet — visar ett statiskt info-kort (ingen AI/backend-anrop, räknas **aldrig** mot timpotten) med allt som ingår i prenumerationen (49 kr/mån): AI Bilrådgivning, AI EV Laddassistent och Bränslekostnadsberäkning — alla obegränsade mot begränsade demoversioner — plus en Prenumerera-knapp som öppnar `/subscribe.html`
- **Per-bil-fokus** — klickar man "Fråga om denna bil" ändras chatboten till att fokusera på just den bilen med specifika chips: Berätta om, Driftkostnad & skatt, Tillförlitlighet & problem, Jämför med
- Dynamiska follow-up chips baserade på svarsinnehållet
- Rensa-knapp; chattfrågor drar ur den kombinerade timpotten (se Rate limiting nedan) + burstspärr 20/min utloggad
- **Persistent chatthistorik** — sparas i `localStorage`; vid sidladdning visas tidigare konversation direkt utan välkomstmeddelande; FAB-etiketten ändras till "Fortsätt chatten" när historik finns
- **Modellsplit:** rekommendationer och jämförelser använder `openai/gpt-oss-120b` (`reasoning_effort: low`), fallback `openai/gpt-oss-20b`; chatboten använder `openai/gpt-oss-20b` primärt, `openai/gpt-oss-120b` som fallback. `qwen/qwen3.6-27b` (preview-tier hos Groq) är reservmodell — tredje 429-utväg och trunkeringsomförsök

### Produktionsstatus

Appen är funktionellt klar för produktion. Återstående steg för live-lansering:

1. Byt Stripe-nycklar till live-värden i Render-miljövariabler
2. Ta bort testläges-bannern i `subscribe.html`
3. Registrera live webhook-endpoint i Stripe Dashboard

> **Bilexpertsamarbete:** Infrastrukturen för RAG-data är klar. Expertinsikter attributeras "Bilexpert" tills samarbete med namngiven expert är bekräftat.

---

### Expertinsikter (RAG)
- PostgreSQL-tabell `expert_insight` lagrar bilexpertis som injiceras i AI-prompten
- Relevanta insikter väljs automatiskt utifrån sökt kategori och drivmedel; källnamnet visas i AI:ns svar (t.ex. "Teknikens Värld: bäst i test")
- **761 insikter** i drift (avläst via `GET /api/stats` 2026-07-28) från namngivna källor: Teknikens Värld, Vi Bilägare, M Sverige, Bytbil, M3, Auto Motor & Sport, Elbilen, CarUp, Folksams krocksäkerhetsstudie, Bilprovningens besiktningsstatistik samt äldre kuraterade "Bilexpert"-insikter (car.info-omdömen finns kvar historiskt men källan skrapas inte längre)
- Fylls på **automatiskt varje natt** av insiktsscrapern (se nedan); manuell import via `POST /api/admin/import/insights?expert=Namn`

### Insiktsscraper (9 motorsajter, nattlig)
- **`WebInsightScraperService`** körs kl **04:00 Stockholm-tid** på Render — efter EV-synken (02:00) och CargoSpec-synken (03:00)
- Källor och upptäcktsmetod:
  - **Teknikens Värld** — WordPress-sitemap (deras `/feed/` svarar 406)
  - **Vi Bilägare** — RSS (`rss.xml`)
  - **M Sverige** — artikellistan `allt-om-bilen/motor-testar/bilar/`
  - **Bytbil** — artikellistan `nybil.bytbil.com/posts`
  - **M3** — RSS (icke-bilartiklar ger tom insiktslista och filtreras bort automatiskt)
  - **Auto Motor & Sport** — WordPress REST API (`wp-json`); F1/racing-artiklar filtreras bort som M3:s
  - **Elbilen** — WordPress REST API, posttyperna `tester` + `artiklar` (standardtypen `posts` innehåller bara 3 poster; notisflödet `nyheter` är medvetet utelämnat)
  - **CarUp** — WordPress REST API (`wp-json`)
  - **Folksam** — krocksäkerhetsstudien "Hur säker är bilen" (dedup per bilmodell)
- **Magert utbud varnar**: en källa som hittar färre än 5 artikel-URL:er flaggas i statusraden (`MAGERT UTBUD (n)`) och loggas som `ERROR`. Ren nolla har alltid varnat, men Elbilen svalt i tysthet i månader bakom ett oskyldigt "0" — endpointen svarade 200, den innehöll bara 3 artiklar som dedupen tog varje natt
- Artikeltexten extraheras med Jsoup, skickas till Groq (`groq.insight.model`, default `openai/gpt-oss-120b`, `reasoning_effort: low`) som returnerar strukturerade insikter (märke, modell, drivmedel, kategori, insikt, betyg — källans betyg räknas om proportionerligt till skalan 1–10, t.ex. "4 av 5" → 8; tonlägesgissningar är förbjudna)
- **Inkrementell**: processade artikel-URL:er och sedda omdömen lagras i `web_insight_seen` — inga dubbletter, oavsett hur ofta synken körs
- Max 12 artiklar per källa och körning — backlog betas av gradvis över flera nätter
- 1,5 s fördröjning mellan sidhämtningar, 5 s mellan Groq-anrop (respekterar TPM-gränsen)
- **Vakterna är egna Groq-anrop** (`groq.guard.model`, default `openai/gpt-oss-120b`) och kan pekas om till en annan modell än extraktionen — Groq har separat TPM-pott per modell. **Prövat och förkastat:** `gpt-oss-20b` som vakt (prodkörning 2026-07-31 20:18). Potten fungerade — vaktanropen syntes inte längre bland 429:orna — men modellen dömde för brusigt åt båda hållen i samma körning: släppte igenom Mini Oxford Edition, europeisk försäljningsstatistik och Teslas produktionsmilstolpe, samtidigt som den stoppade alla fem DS N°8-raderna trots svenskt pris i artikeln (849 900 kr). Vakterna är kvalitetsspärren, så de ligger kvar på den stora modellen; tidsvinsten satt ändå i backoffen och per-källa-körningen
- **Svenskt pris avgör**: relevansprompten slår fast att ett angivet pris i kronor gör modellen relevant — den regeln kom till efter DS N°8-blockeringen ovan
- **Vaktens tre blinda fläckar är namngivna i prompten** (specialutgåvor, lyxbilar, designprosa). A/B-mätning 2026-07-31 med 12 insikter ur en verklig körning: med de gamla, allmänt hållna reglerna fick `gpt-oss-120b` 9/12 och `gpt-oss-20b` 7/12 — båda missade Mini Oxford Edition (jubileumsutgåva), AMG GLE 63 S (585 hk lyx-SUV) och "skalade ytor, rund pekskärm" (prosa utan innehåll). Med konkreta exempel plus två generella regler — riktmärke 1,5 Mkr för lyxbilar, och krav på minst en kontrollerbar uppgift (siffra, testresultat, känt fel, utrustning, pris) — gick 120b till 12/12 och 20b till 9/12, utan nya falska positiva. **Lärdomen upprepas:** vakten agerar på namngivna exempel, inte på allmänt formulerade principer
- **Vakterna körs per källa, inte per artikel** (chunkat om 10 insikter, 1 500 max_tokens): relevansvakt, extravakt och parafras-dedup kostade förut upp till tre Groq-anrop per artikel × 12 artiklar per källa. Varje chunk är sitt eget fail-closed-fönster
- **Tomt vaktsvar ≠ tom lista** (fälla, uppmätt 2026-07-31): `gpt-oss-120b` är en reasoning-modell och resonemanget växer med batchstorleken. Med 25 insikter och 400 tokens gick hela budgeten till reasoning — svaret blev `200` med `finish_reason=length` och **tomt content**. Tolkas det som "inget var irrelevant" blir vakten en tyst nolla som släpper igenom hela batchen, tvärtemot den dokumenterade fail-closed-regeln. `parseIndexesOrNull` skiljer därför på tom lista och uteblivet svar; det senare hoppar över chunken. Dedupen använder fortsatt fail-open-varianten `parseIndexes`
- **429-backoffen följer Groq**: `retry-after`-headern i första hand, annars väntetiden ur felmeddelandet ("try again in 8.31s"), tak 60 s, golv 1 s. Den fasta trappan 30/60/90 s sov nästan alltid för länge — alla 16 väntor i nattkörningen 2026-07-31 loggades som första försöket, dvs. omförsöket lyckades varje gång. **Uppmätt effekt** (prodkörning samma kväll): 26 väntor på totalt 271 s (4 min 31 s) mot referensens 16 × 30 s = 480 s — Groq bad om 1–21 s. Kvarvarande 429:or bär alltid en artikel-URL, dvs. det är extraktionens tokens (`MAX_TEXT_CHARS`) som är flaskhalsen nu
- **Ej skrapbara** (JavaScript-renderade utan öppet API): automotorsport.se/agarbetyg (själva ägarbetygen — artiklarna nås via wp-json), blocket.se/bilguiden, car.info/sv-se/user-reviews (borttagen som källa — sidan serverar bara ett filterskal, omdömestexterna hämtas av JS efteråt; källan sparade aldrig en enda insikt)
- Manuell trigger: `POST /api/admin/sync-web-insights`; körstatus: `GET /api/admin/scrape-status`; seed av redan processade nycklar: `POST /api/admin/import/seen-keys`; ta tillbaka en nyckel för omläsning: `DELETE /api/admin/seen-keys?key=…`
- En artikel som svarar **404/410** markeras som färdig — den kommer aldrig tillbaka men räknas annars mot källans 12 artiklar per natt så länge den ligger kvar i länklistan, och stjäl plats från en läsbar artikel. Timeout, 403 och 5xx lämnas omarkerade: de kan släppa igen, och en tyst markering hade tappat artikeln för gott.
- `extract_web_insights.py` är samma pipeline som fristående Python-verktyg för manuella körningar
- **Utmärkelser är köpsignaler**: scrape- och relevansprompterna behåller uttryckligen utmärkelser
  till specifika modeller (Årets Bil/Car of the Year, "bäst i test", topplaceringar i
  försäljningsstatistik) trots att företags- och marknadsnyheter i övrigt filtreras bort
- **Kommande modeller kastas inte, de parkeras**: relevansvakten skiljer på irrelevant (bil som inte går att köpa här och inte är på väg) och **kommande** (bekräftad modell med konkret innehåll, ännu ej säljstartad i Sverige). Kommande insikter sparas i `expert_insight` men flaggas i `insight_upcoming` och filtreras bort ur rekommendationsprompten, chattkontexten och bilkorten tills de släpps med `DELETE /api/admin/insights/{id}/upcoming`. Motivet: natten till 2026-07-31 slängdes 13 rader om nya el-GLA:n — innehåll som blir användbart när bilen når marknaden
- **Kommande-rader granskas av en egen extravakt**: extravaktens första regel ("går inte att köpa i Sverige idag") är negationen av KOMMANDE-definitionen, så de två vakterna dödade varandras beslut — CarUp är enda strikta källan och kön kunde därför aldrig få en rad (2026-08-02). Att i stället lämna kommande-rader oprövade var för trubbigt åt andra hållet: kön fick sina första fem rader 2026-08-04 och två av dem var sådant extravakten fångar — EX50:ns pris angivet i dollar för USA-marknaden och Košice-fabrikens produktionsvolym. `STRICT_UPCOMING_PROMPT` är därför samma granskning utan säljbarhetsregeln, med fabriks- och produktionssiffror uttryckligen utpekade (de läcker just på kommande modeller, där nästan allt som skrivs är fabriksnyheter) men tekniska uppgifter om bilen — plattform, batteri, räckvidd, mått — uttryckligen behållna. Stoppraderna loggas som `extravakten [<källa>/kommande]`
- **Kommande-vakten körs på alla källor, säljbarhetsvakten bara på de strikta**: kön fylls inte av källan vakten byggdes för. Natten till 2026-08-06 kom alla sex nya kommande-rader från Teknikens Värld och M3 medan CarUp inte skrev om en enda osläppt bil, så `STRICT_UPCOMING_PROMPT` fick noll rader att döma för andra natten i rad — och id 1178 ("Volvo planerar att öka produktionen av den kommande EX60") är exakt en produktionsrad som prompten stoppar, men mötte den aldrig eftersom M3 inte är strikt källa. Sedan dess returnerar `filterStrict` inte längre direkt för icke-strikta källor: kommande-rader granskas överallt, medan säljbarhetsregeln (som prövar just säljbarhet i Sverige, där CarUp:s översatta amerikanska innehåll bevisligen läcker) fortsatt bara gäller `STRICT_SOURCES`. Saknar en icke-strikt källa kommande-rader returneras listan oförändrad, så inga extra Groq-anrop tillkommer i en körning som redan är ~2/3 väntan. Prompten är därmed också källneutralt formulerad: felkällan är ämnet — en bil som inte går att provköra ger mest pressmaterial att skriva av — inte vem som skriver
- **Kommande-markeringen ärvs inom batchen, per bil**: relevansvakten avgör KOMMANDE rad för rad och domen är inte stabil. Natten till 2026-08-05 kom tre CarUp-rader om samma osläppta bil (Audi A2 e-tron) och bara vikten markerades — de två andra (cW 0,24 och WLTP-förbrukningen) hamnade därmed i säljbar-gruppen och stoppades av säljbarhetsregeln, alltså exakt den fälla `STRICT_UPCOMING_PROMPT` byggdes för att undvika. A/B mot samma batch med produktionsparametrar gav fyra olika uppdelningar på sex observationer, så det går inte att prompta bort. `filterStrict` samlar därför nycklarna (märke + modell) för redan markerade bilar innan batchen delas, och en omarkerad rad om samma bil ärver markeringen (loggas `ärver kommande-markering inom batchen för …`). Rader utan både märke och modell grupperas aldrig — en tom nyckel hade dragit ihop orelaterade rader
- **Leveranserna avgör om en bil är köpbar, inte hur ny den är**: natten till 2026-08-07 kastade relevansvakten fem rader från Volvo EX60:s förste svenske ägare — 5G-uppkopplingen som slutat fungera och vägrar återansluta, den digitala nyckeln som låser sporadiskt, och räckvidden 55–60 mil i verklig körning. EX60 säljs här (620 000 kr i `ev_spec`), står i rekommendationsprompternas Volvo-EV-lista och har haft svenska leveranser sedan mitten av juli, så vakten var ur synk med resten av systemet: motorn rekommenderar bilen medan insiktsvakten slänger ägarerfarenheter om den. A/B mot samma batch med produktionsparametrar (`max_tokens` 1500, chunk 10, temp 0.2, `reasoning_effort` low) reproducerade **inte** stoppet — samma fem rader behölls 3/3, men markerades KOMMANDE i en körning och lämnades oflaggade i två, alltså tre olika domar räknat med driftens IRRELEVANT. Prompttexten var därmed inte boven, men gränsen står nu utskriven åt båda hållen: en modell vars första kunder redan fått sina bilar är KÖPBAR oavsett hur nylanserad den är (EX60, BMW iX3 och eldrivna GLC är i det läget 2026), medan KOMMANDE är bilar längre bort — presenterade utan säljstart eller med lansering ett år eller mer fram (EX50 med säljstart 2027 är arketypen). De tre konkreta raderna importerades manuellt som id 1193-1195
- **Relevans och tillgänglighet avgörs i två anrop, inte ett**: trevägsbeslutet (irrelevant / kommande / direkt användbar) i en enda prompt var instabilt just på KOMMANDE-gränsen. Två oberoende A/B-mätningar med produktionsparametrar visar det: tre CarUp-rader om Audi A2 e-tron fick fyra olika uppdelningar på sex observationer (2026-08-05), och de fem EX60-raderna fick tre olika domar — IRRELEVANT i drift, KOMMANDE i en harness-körning, direkt användbara i två (2026-08-07). Konsekvensen var inte bara en rörig kö: rader om bilar man snart kan köpa **försvann helt**, eftersom trevägsvalet lät tillgänglighetsfrågan konkurrera med relevansfrågan om modellens uppmärksamhet. `RELEVANCE_PROMPT` avgör därför numera bara relevans och svarar `{"irrelevant": […]}`; därefter frågar `UPCOMING_PROMPT` binärt vilka av **de överlevande** raderna som handlar om en bil man ännu inte kan köpa. Kommande-vakten kan bara parkera, aldrig kasta — och svarar den oläsbart parkeras hela chunken i kön i stället för att tappas, eftersom raderna redan är godkända (kön går att släppa ur med `DELETE /api/admin/insights/{id}/upcoming`, ett bilkort går inte att ta tillbaka). Kostnaden är ett extra Groq-anrop per chunk av det som överlevt vakten
- **Relevansvakten får inte ha en egen regel om tillgänglighet**: uppdelningen ovan flyttade frågan till `UPCOMING_PROMPT` men lämnade kvar `RELEVANCE_PROMPT`:s egen uteslutning — "Kravet är hårt: konkreta uppgifter gör INTE en bil relevant om läsaren inte kan köpa den", med *plattform* och *teknik* uppräknade bland rykten. De två reglerna motsade varandra och den hårdare vann. Natten till 2026-08-08 gav hela körningen **noll** insikter från alla nio källor: 15 kandidater, 10 stoppade av relevansvakten och 5 kastade som parafras-dubbletter. Tre av stoppen var Audi A2 e-tron (cW 0,24, 61 kWh LFP i cell-till-pack, kiselkarbidhalvledarna) — exakt de rader som tidigare sparats i kön som id 1153/1157/1158. Eftersom `markUpcomingRows` returnerar direkt på tom lista kördes kommande-vakten aldrig, så kön kunde inte växa och `extravakten [<källa>/kommande]` fick noll rader för tredje natten i rad. Uteslutningen gäller nu bara bilar som **aldrig** blir tillgängliga här (andra marknader, avvecklade modeller) och lösa rykten utan kontrollerbart innehåll. A/B med produktionsparametrar: Volvo EX50 gick från behållen 0/3 till 3/3 och parkerad 3/3, A2-raderna från 0/4 till 4/4 i blandad batch, medan EX60-kontrollerna låg kvar på 3/3 behållna och 0 parkerade och stoppfallen (Polestar 3-förseningen, ID. UNYX på kinesiska marknaden, begagnad Panamera, Aston Martin DB6) fortsatt 0/3 — inga regressioner
- **En presenterad modell är inte en konceptbil**: samma A/B avslöjade en andra, oberoende väg till samma bortfall. A2-raderna stoppades 3/3 även ensamma i sin batch tills prototypregeln fick en gräns — "preliminär energiförbrukning" lästes som en konceptbil. En konceptbil är nu utskrivet *en studie utan produktionsbeslut*, medan en modell tillverkaren officiellt presenterat inte är det "även om siffrorna är preliminära och säljstarten inte är satt". Osäkerhet pekar dessutom åt behåll-hållet, spegelbilden av kommande-vaktens regel: kön är reversibel, en kastad rad är borta. **Öppen punkt:** om uppluckringen försvagat AMG-/lyxstoppet är ännu omätt — i prod-formad batch behöll den nya prompten AMG-rader i 1–2 av 3 körningar, men det gjorde HEAD också, så n=3 skiljer inte uppluckring från den kända batchinstabiliteten. Mätningen avbröts av Groqs dygnstak (TPD 200 000 tokens/org; ett vaktanrop drar ~2 400) och ska köras om isolerat på lyxregeln
- **Testomdömen behålls, märkesomdömen inte**: sammanfattade omdömen från motorpressen är relevanta även utan siffror så länge de namnger vilka egenskaper omdömet gäller ("beröm för sportig körning, interiör och ljudsystem"). Gränsen går vid omdömen om **märket** i stället för bilen — och den gäller åt båda hållen: både "har skadat varumärkets rykte" och den smickrande varianten "fortsätter märkets tradition av starka kombibilar". Dit hör också meningar som bara räknar upp drivlina eller teknik som redan följer av modellbeteckningen ("A6 Allroad i laddhybridutförande kombinerar fyrhjulsdrift med hybridteknik") — tekniken är namngiven, men ingenting i meningen går att jämföra mot en annan bil. Uppluckringen 2026-08-02 (behåll testomdömen utan siffror) öppnade båda hålen; de täpptes 08-03 efter att en sådan rad sparats skarpt, verifierat med A/B mot `gpt-oss-120b` på båda vakterna (3/3 stoppade, noll regressioner på de behållna fallen)
- **Uteslutningarna skärps av det som läckt förbi dem, inte av principer**: natten till 2026-08-10 sparades fyra rader, varav tre bröt mot uteslutningar som redan stod i `RELEVANCE_PROMPT`. Två var **fordonsskatt knuten till en enskild modell** (Volvo V60 Recharge 360 kr/år, Porsche Cayenne Turbo E-Hybrid 2 714 kr/år) fast "skatter" fanns i listan — vakten läste ett kronbelopp för en namngiven bil som en egenskap hos bilen. Uteslutningen pekar nu ut fordonsskatt, malus och trängselskatt även i modellspecifik form, med motivet utskrivet: en skattesiffra följer av regelverket och ändras när det ändras. Brasklappen åt andra hållet är lika viktig — **bilens pris, förbrukning och CO2-värde förblir RELEVANTA även när texten runt omkring handlar om skatt**, annars äter skärpningen upp bra prisrader (id 1188, VW ID.Polo Trend 320 900 kr, är exakt en sådan). Den tredje var en **Volvo 240 återställd för 38 000 kr** som passerade veteran-/entusiastregeln; prototypregeln täcker nu också renoveringsobjekt. Samma mönster som de tre blinda fläckarna ovan: vakten agerar på namngivna fall, inte på allmänt formulerade principer
- **Avvecklad modell ≠ otillgänglig modell**: `RELEVANCE_PROMPT` uteslöt "modeller som lämnat den svenska marknaden", vilket hade kastat varje insikt om **Renault Zoe** — tillverkad 2012 till mars 2024, ersatt av Renault 5, men med **49 exemplar på Blocket från 58 000 kr** (mätt 2026-08-10, max 10 000 mil). Läsaren köper begagnat, så en avvecklad modell med levande begagnatmarknad är bland det mest användbara som finns. Gränsen går nu vid bilar som **aldrig sålts här**, inte vid bilar som slutat säljas nya. Samma familj av fel som tillgänglighetsregeln 2026-08-08: en rimlig regel som råkade döda rätt rader. Rekommendationsprompten bar samma motsägelse — "nämn ALDRIG modeller som inte officiellt säljs i Sverige" stod två rader från Zoes begagnatgolv på 58 000 kr och från småbilsexemplet "Renault Zoe" — och är rättad på samma sätt, med undantaget att nybilssök och leasing fortfarande kräver en modell som går att köpa ny (e-Golf 29 annonser, Nissan Leaf 44)
- **Relevansvakten är fail-closed**: om Groq-anropet till relevansfiltret felar eller inte svarar hoppas hela batchen över den körningen istället för att sparas ofiltrerad — det är den enda spärren mot att icke-köparrelevant/hallucinerat innehåll hamnar i den "verifierade" insiktsdatabasen andra delar av appen litar på. Dubblettfiltret (parafras-dedup) är fortsatt fail-open — en dubblett är lägre risk än en tappad insikt

### Kuraterade källor (CSV-seed via admin-importen)
- **Årets Bil-juryn** — Car of the Year-resultat: Mercedes CLA vann 2026 (före Škoda Elroq
  och Kia EV4), Renault 5 E-Tech vann 2025
- **Mobility Sweden** — nyregistreringsstatistik helåret 2025: Volvo XC60 mest sålda bilen
  (17 933), Volvo EX40 mest sålda elbilen (8 788), därefter VW ID.7 och Tesla Model Y
- Uppdateras årligen (nya vinnare/årsstatistik) med `POST /api/admin/import/insights?expert=...`

### Mobility Sweden-månadssynk (automatisk, den 4:e varje månad 05:00)
- **`MobilityStatsSyncService`** hämtar senaste månadspressmeddelandet från mobilitysweden.se
  (årssidan → senaste artikeln → xlsx-bilagan "Månadsrapport Nyregistreringar <månad> <år>.xlsx")
- Xlsx:en parsas med Apache POI: arken **"PB - Rankinglista"** (alla personbilar) och
  **"Elbil ranking"** — YTD-sorterade; modell i kolumn D, månadsantal i F, ackumulerat i M
- Genererar 2–3 insikter under källnamnet **"Mobility Sweden månadsläget"** (ersätts varje
  körning — de kuraterade årsraderna under "Mobility Sweden" rörs aldrig): mest registrerade
  bilen i år, mest registrerade elbilen i år, samt månadens etta om den skiljer sig
- Rapportens versala gruppnamn normaliseras till kortens modellnamn ("VOLVO EX/XC40" → Volvo
  EX40, "VW ID.7/ID.7 TOURER" → Volkswagen ID.7)
- Statistikdatabasen på mobilitysweden.se är en Power BI-embed och kan inte skrapas direkt —
  xlsx-bilagan är samma data i maskinläsbar form
- Manuell trigger (synkron, svarar med resultatet): `POST /api/admin/sync-mobility-stats`

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
- Fuzzy-matchning i **tre** steg mot befintliga DB-poster — förhindrar dubbletter: (1) exakt normaliserat namn, (2) DB-namnets ord finns alla i det skrapade (längsta DB-namnet vinner), (3) omvänt — DB-namnet är mer specifikt än det skrapade, med räckvidden som tie-break. Steg 3 kräver ≥3 ord i det skrapade namnet och räckvidd inom 10 %, och avstår helt vid oavgjort mellan två varianter (se "Senaste bugfixar")
- Priser uppdateras på befintliga poster där `priceKr=0`
- **Fyller bagagevolym i `cargo_spec` på köpet** (`fillFromScrape`): luckan är bilar som **saknar rad**, inte rader som saknar siffra: `cargo_spec` hade 243 rader den 2026-08-10 och **samtliga bar volym**, medan `ev_spec` hade 518 elbilsvarianter — merparten av elbilarna saknar alltså bagagerad helt, och bagagefiltrets vakt kan därför bara fälla på positivt bevis. ev-database bär både `Cargo Volume` och `Cargo Volume Max` på precis de bilsidor den här synken ändå besöker, så siffran kostar **inget extra HTTP-anrop**. Täcker bara elbilar; förbränningsbilar behöver en annan källa (car.info blockerar skrapning, Bilwebs sidor bär inte volymen). Tre regler: **befintlig volym skrivs aldrig över** (DataLoaders seedade är handkontrollerade och vinner), **mest specifika namnet vinner** när flera rader matchar, och **saknas raden helt skapas den** under ev-databases variantnamn. Den sista regeln var först den motsatta ("skapa aldrig") — och gjorde funktionen verkningslös, eftersom det inte fanns en enda tom rad att fylla. Det syntes först när `cargo-coverage` svarade `utanVolym: 0` efter en 12-minuterskörning som rört noll rader; motivet till spärren höll inte heller, eftersom `/api/cars` redan är unionen av cargo_spec och ev_spec. Volymen läses ur cellen EFTER etiketten och inte ur brödtexten: `Cargo Volume` och `Cargo Volume Max` står som varsin rad i samma tabell, så en textsökning hade lika gärna kunnat plocka den andras siffra. Egen try/catch runt skrivningen — `cargo_spec` är en annan tabell och ett fel där får inte sänka EV-synken. Antalet syns som `bagagevolymer=N` i synksammanfattningen
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
- **674 bilnamn** i listan (avläst 2026-07-28): CargoSpec-modeller från Bilweb.se-sync + alla EvSpec-varianter (t.ex. "Tesla Model Y Long Range", "Volvo EX30 Single Motor")

### Prenumeration & betalning (Stripe)

> **Stripe körs för närvarande i testläge.** Inga riktiga betalningar genomförs. Testkort: `4242 4242 4242 4242`, valfritt datum och CVC.
> För att aktivera produktion: byt `STRIPE_SECRET_KEY` till `sk_live_...`, `STRIPE_WEBHOOK_SECRET` till live webhook-hemligheten, ta bort testläges-bannern i `subscribe.html` och uppdatera `STRIPE_PRICE_ID` till live-prisets ID. All övrig kod är produktionsklar.

### Korsåtkomst — båda tjänsterna ingår

En prenumeration på **49 kr/mån** ger tillgång till båda tjänsterna med samma konto och token:

- **AI Bilrådgivning** — [elitrobban.se/bilradgivning](https://elitrobban.se/bilradgivning/)
- **AI EV Laddningsassistenten** — [elitrobban.se/elbilsladdning](https://elitrobban.se/elbilsladdning/)

`ca_token` lagras i `localStorage` under domänen `elitrobban.se` och delas automatiskt mellan sidorna. `ev-charging.js` (serveras av CarAdvice-backenden) agerar access guard på elbilsladdning-sidan — kontrollerar token mot `/api/auth/me` och visar antingen innehållet eller ett betalvägg-kort.

- Ej inloggad: **max 10 sökningar/timme** — en *kombinerad* timpott där rekommendationer, jämförelser **och chattfrågor** räknas mot samma 10 (IP-baserat), plus en per-minut-burstspärr på chatten (20/min)
- Inloggad (gratis konto): **30 sökningar/timme** kombinerat (samma pott för sök + chatt), burst 50/min
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
| AI | Groq API (`openai/gpt-oss-120b` rekommendationer, `openai/gpt-oss-20b` chatt/fallback, `qwen/qwen3.6-27b` reserv) |
| HTML-parsning | Jsoup 1.17 (EV-skraparen) |
| Databas | PostgreSQL (Render) / H2 in-memory (lokal dev) |
| ORM | Spring Data JPA / Hibernate |
| Autentisering | spring-security-crypto (BCrypt), opaka sessionstoken |
| Betalning | Stripe (testläge) — Checkout + webhooks |
| Frontend | HTML/CSS/JS (WordPress Anpassad HTML) |
| Deploy | Render.com (Docker) |
| Monitorering | UptimeRobot |
| Tester | JUnit 5, Mockito, AssertJ — körs i GitHub Actions på varje push |

---

## Tester & CI

561 tester täcker backendens rena logik och HTTP-lagret (beroenden mockas med Mockito; `FeedbackServiceTest` och `IceConsumptionServiceTest` kör mot H2 in-memory för att verifiera portabel SQL):

| Testklass | Täcker |
|-----------|--------|
| `GroqServiceTest` (169) | Laddhybridsbrasklappen (`PHEV_TAX_CAVEAT`: triggas på **kategorin** laddhybrid eftersom drivmedelsrutan inte har det valet, säger uttryckligen att begagnat inte påverkas, och stänger av BEV-tvånget så en kvarglömd "el"-drivmedelsrad inte förbjuder det användaren bett om; övriga kategorier får ingen brasklapp) och drivmedelsklassningen (**"Hybrid (ej laddhybrid)" är en bensinbil**, inte en elbil — den matchade tidigare `wantsEv` på delsträngen "hybrid" och fick både BEV-tvång och en utelämnad ICE-nypristabell). Budgettaket (`exceedsBudgetCeiling`: bil 84 000 kr över budget faller, 25 000 kr över släpps igenom, gränsen går vid exakt +30 000, billig bil faller aldrig — taket har inget golv, en ensam annons fäller ingen bil, taket mäts mot billigaste annonsen och inte snittet) och sammanslagningen efter omförsöket (`mergeWithinBudget`: för dyr bil tas bort även när omförsöket inte blev bättre, omförsökets bilar först, aldrig fler än tre, tom lista när ingen håller budget, bil utan Blocket-data faller inte, **dedup på modell och inte titel** så ihopslagningen inte ger "ID.4 (2022)" bredvid "ID.4 (2021)"), promptbygget (budget/leasing, milprofil, laddbox, växellåda, ÅLDERSKRAV, familjeprofil-flaggning), systemprompt-reglerna (EV/ICE-pristabellfiltrering, exakt 3 bilar, fabricerade priser, småbilsförbud + kuraterade familjebilar, budgetutnyttjande, märkesprioritet, årsmodeller före lansering, SIFFERLOGIK/DRIVLINA i jämförelseprompten), familjespärren (`requireFamilySizedCars`, och att gränsen går vid **5+ passagerare** — den gick vid 4, vilket är formulärets defaultvärde, så strängaste läget var påslaget för alla som inte rörde fältet; ett elbilssök på 225 000 kr gav då två kort medan samma sökning med två passagerare gav tre, Zoe/Leaf/MG ZS EV, alltså just det billiga elbilsutbudet spärren höll ute. **VW ID.3 är borttagen ur småbilslistan**: Golf-klass med fem säten, samma storleksklass som MG4 som prompten samtidigt rekommenderar som familjeelbil), drivmedelsvakten (`requirePureEvCars`: hybrid i titeln fälls, förbränningsbil utan drivlineord i titeln fälls via `ice_consumption`, okänd bil släpps igenom — vakten kräver positivt bevis. **`ice_consumption` prövas före `ev_spec`**, annars släpps tvetydiga namn igenom: live 2026-08-09 gav ett elbilssök "Kia Niro (2021)", som finns som HEV, PHEV *och* elbil, och `ev_spec`:s fuzzy-matchning slog mot "Kia Niro EV" innan hybridträffen ens provades. Ett namn som finns som förbränning fälls därför även om det också finns som elbil — en titel utan drivlineord pekar inte ut någon variant, och tvetydigheten är skadlig i sig eftersom Blocket-uppslaget då matchar hybridannonser och kortet får fel prisbild; `fuelIntent` avgör när den gäller, med delsträngsfällorna "diesel" och "spelar ingen roll" som båda innehåller "el"; regelbrottet bär med sig de elbilar som fanns i svaret så att de kan visas när även omförsöket bröt mot regeln, och bara ett svar helt utan elbilar blir ett felmeddelande). **Alla tre regelvakter delar samma mönster** (`RuleViolationException` bär med sig det som klarade regeln): förut hade var och en sin egen hårda väg ut, så ett enda regelbrott bland tre bilar kostade hela svaret — live 2026-08-09 gav elbil + 225 000 kr + 5 passagerare HTTP 500 i tre försök av tre, dels på en overifierbar modell, dels på avhuggen JSON. Vakterna körs i kedja och den första som fäller avbryter resten, så `keepPassingCars` kör om kedjan på det som blev kvar tills den går ren (varje varv tar bort minst en bil, alltså terminerar loopen). Omförsöket får **4 500 tokens** mot ordinarie 3 000 — "AI-svaret blev ofullständigt" betyder att resonemanget åt upp budgeten innan JSON:en hann skrivas färdig, och samma tak upprepar oftast samma trunkering. Klarade **ingen** bil regeln görs ett **rättelseförsök** (`retryAfterRuleViolation`) som pekar ut de fällda bilarna vid namn och upprepar regeln som en instruktion — samma grepp som budgetomförsöket, och av samma skäl: omförsöket i `parseWithRetry` skickar bara om samma prompt till reservmodellen utan att säga vad som var fel, och får därför ofta samma fel igen. Live-fallet var elbil för 175 000 kr, som gav enbart hybrider i båda omgångarna och därmed HTTP 500 fast det finns gott om elbilar i det prisläget. Varje vakt bär därför både de fällda bilarnas namn och sin regel formulerad som instruktion (`avvisade()`, `rattelse()`). Går det ändå inte får felet med sig **vad som går att ändra** (`medRadOmKriterier`: högre budget, färre passagerare, annat drivmedel) i stället för bara "Försök igen" — orsaken skrivs inte om, den varierar och gissas inte, årsmodellvakten (`requireRealisticModelYears`: "Kia EV3 (2022)" avvisas eftersom EV3 lanserades 2024 — live-fall 2026-08-09 som modellvakten släppte igenom, den godkänner EV3 för att modellen finns i databasen och kontrollerar inte årtalet. `MODEL_LAUNCH_YEAR` är spegel av promptens årsmodellregel och innehåller **bara kontrollerade lanseringsår**; generationsspannen i `new_car_price` duger inte som källa — "Volkswagen Polo 2018-2021" hade gjort 2015 års Polo till en påhittad bil fast tabellen bara saknar äldre generationer. Modell utanför listan eller titel utan årtal släpps alltid igenom), modellhallucinationsvakten (`requireKnownModels`: påhittade modeller avvisas, trimvarianter i båda riktningarna godkänns, tom whitelist släpper igenom, diakritiknormalisering, källsammanslagning + enordsfiltrering i `buildKnownModelTokenSets`), Blocket-snappingen (`correctedPrice`, tröskel 2 annonser), verifierade motoralternativ ersätter AI:ns engineOptions-fritext när ev_spec-träff finns (annars behålls AI:ns text), verifierad systemeffekt (`getSystemPowerHk`) ersätter AI:ns hk-gissning för EV-modeller med känd korrekt siffra (annars behålls AI:ns värde), verifierad hk/motorbeteckning från ice_consumption ersätter AI:ns gissning för bensin-/diesel-/hybridbilar (topplevel-hk OCH fuelSpec.horsepower rättas tillsammans så kortet inte visar två olika hk-tal, engineOptions blir motorbeteckningen ur databasen med modellordet strippat), JSON-parsning av AI-svar (`<think>`-strippning, fallback-nycklar, root-array, okända fält, avhugget/feltypat JSON → begripliga fel), cachenyckel, de delade cachehjälparna (`isFresh` säger nej till saknad post och till post äldre än TTL:n på 4 h, `store` lägger in med färsk tidsstämpel), 429/felmeddelanden, feedback-kontexten (undvik-signal), modellhälsokollen (`missingModels`) |
| `EvSpecServiceTest` (58) | **`e-`-prefixet strippas nu ur BÅDA sidor, med spärr** (2026-08-11): titeln har alltid strippat det ("e-Niro" → "Niro") men det lagrade namnet inte, så rader vars *modellord* bär prefixet var omöjliga att träffa med ordmatchning och fick aldrig verifierade motoralternativ — ~30 rader (e-Tourneo, e-Caravelle, e-Transporter, e-Rifter, e-Traveller). Symmetrin öppnar samtidigt en risk: "e-Golf" är inte "Golf", så de e-prefixade modellnamn vars basord också finns som förbränningsbil står i samma spärr som PHEV-raderna och kräver att titeln bär prefixet. `isKnownEv("Volkswagen Golf")` är alltså falskt även med e-Golf-raderna på plats. **Volkswagen e-Golf lades till** (24,2 kWh 2014–2016, 35,8 kWh från facelift 2017) — bilen saknades helt, så kortet föll tillbaka på AI:ns fritext som påstod "45 kWh", en kapacitet den aldrig haft. **Trimnamn som bara upprepar kWh-talet tas bort**: raderna för utgångna generationer särskiljs av kapaciteten i namnet, vilket gav "44.5 kWh (263 km) · 44.5 kWh". Ett trim som säger något mer behålls — "e+" är modellbeteckning, inte kapacitet. **MG ZS EV och Nissan Leaf finns som flera generationer** — se `DataLoader.UTGANGNA_GENERATIONER`; ZS EV:s enda rad var faceliftens Long Range (72,6 kWh / 440 km), så kortet "MG ZS EV (2020)" fick 2022 års bil i stället för pre-faceliftens 44,5 kWh / 263 km. Bara pre-facelift-raden läggs till av oss: faceliftens Standard Range finns hos ev-database och det som har en riktig källa ska synken äga. **Laddhybridmarkörerna är inventerade ur tabellen, inte gissade** (2026-08-11): första versionen tog bara `phev`/`hev`, generaliserat från de två exempel som fanns för handen, och verifieringssöket samma dag gav kortet "Volkswagen e-Golf (2019)" motoralternativet "13 kWh (70 km) · GTE" — e-prefixregeln gör titeln till "volkswagen golf", som matchar laddhybriden `Volkswagen Golf GTE`. Listan är nu `phev`/`hev`/`gte`/`plug-in`, vilket täcker Golf GTE, Passat GTE, Prius Plug-in och RAV4 Plug-in utöver de 21 PHEV-raderna. De två Plug-in-raderna är dubbletter av Prius/RAV4 PHEV under en annan konvention och raderas *inte* — nattsynken kan återskapa dem, och spärren gör dem ofarliga där de står. Testet låser också att filtret inte blir för brett: `Volvo XC40 Recharge` (Recharge är Volvos namn för både elbil och laddhybrid), `DS 3 E-Tense` och `Jeep Compass Electric 4xe` är riktiga elbilar och deras kort ska fungera. **Nissan Leaf finns som tre generationer** (2026-08-11): tabellen hade en enda Leaf-rad — 75,1 kWh / 62,4 mil, alltså 2026 års bil — så ett kort för "Nissan Leaf (2019)" visade den enda rad som fanns, och årsfiltret hade inget att välja mellan. Gen 1 (24/30 kWh) och gen 2 (40 kWh, e+ 62 kWh) ligger i `DataLoader.LEAF_UTGANGNA` och återställs vid varje uppstart, som `MG4_GEN1`, eftersom ev-database bara listar den nuvarande bilen. **Alla tre generationerna är taggade i `GENERATION`, inte bara de nya** — otaggad rad räknas som den äldsta, och här är den otaggade raden tvärtom den nyaste. **Räckviddskonventionen skiljer sig avsiktligt:** gen 2 har riktiga WLTP-tal (270 respektive 385 km), medan gen 1 såldes före WLTP och bara har NEDC (175/250 km) som är så optimistiska att de hade återskapat just det fel raderna lades in för att åtgärda — därför verklig bruksräckvidd, ~120 och ~150 km. Priset lämnas `0`: vi saknar källa på svenska listpriser, och `toDto` sätter prisvärdhetsetikett bara när priset är > 0, så en gissning hade blivit en felaktig etikett i stället för ett tomt fält. **Laddhybridrader får inte fastna på ett elbilskort** (2026-08-11): `ev_spec` bär 21 PHEV-rader vid sidan av elbilarna, och matchningen strippar "Electric" ur titeln för att "MG4 Electric" ska hitta "MG4" — varpå "Hyundai Kona Electric" blir "Hyundai Kona" och båda orden finns i "Hyundai Kona PHEV". Live gav det ett rent elbilskort motoralternativet "8.9 kWh (58 km) · PHEV", alltså en laddhybrids 5,8 mil på en bil som går 51. Värre för `isKnownEv`, som svarar insiktsfiltret på om kortet är en ren elbil: "Volvo XC60" matchade "Volvo XC60 PHEV", så en bensin-XC60 klassades som elbil och tappade sina förbränningsinsikter. Ett drivlineord i det lagrade namnet kräver nu samma ord i titeln — prövat mot titelns egna ord *före* strippningen, annars hade PHEV-kortet blivit av med sin egen rad. **Årsmodellen väljer generation också i `matchByTitle`**: filtret satt bara i `verifiedEngineOptions`, så motorlistan kunde visa rätt generation medan spec-chipsen bredvid visade den andra (en MG4 från 2023 fick gen 2:s siffror). Samma generationsdata styr nu båda, och rör inget när årsmodell saknas. Fuzzy-matchning AI-titel → EV-spec i övrigt: pass 1–3, normalisering av diakritiska tecken **och av hårda/smala/zero-width-mellanslag** (U+00A0/U+202F/U+200B — AI-titlar med sådana matchade tidigare ingenting alls), strippning av årsmodell/`Electric`/`e-`-prefix, räckvidds- och prisvärdhetsberäkningar, prisvärd räckvidd-rankningen (km/krona, 400 km-golv, etablerade märken), verifierade motor-/batterivarianter (`verifiedEngineOptions`: **ett kort per variant, inte per batteri** — MG4:s fyra rader blir "Standard Range / Long Range / XPOWER / Extended Range" och gen 2:s sex varianter sex rader. Bara dubbletter slås ihop, och räckvidden avgör vilket som är vilket: olika kWh + samma räckvidd är netto/brutto av samma bil (EV6 Standard Range 60/63 kWh på 428 km → en rad), samma kWh + olika räckvidd är två varianter (MG4:s 52,8 kWh på 405 respektive 416 km → två rader). Ihopslagningen söker i alla grupper, inte bara den intilliggande, eftersom sorteringen på kapacitet lägger EV6 Long Range 2WD:s 80- och 84-rader isär trots samma 582 km. Tydligt olika batterier (58/77 kWh) och två generationers batterier i samma modell (EV6 77,4/84 kWh) hålls isär, dubblettrader dedupas, variant utan räckvidd slås ihop som förut (inget finns att skilja den på), ingen match ger null, märkesprefixet i "MG MG4 XPOWER" skalas bort och en hopslagen rad lämnas omärkt), verifierad systemeffekt (`getSystemPowerHk`: MG Marvel R Standard/Performance ger 180/288hk mot AI:ns tidigare felaktiga 150hk, mest specifika modellnamnet vinner, okänd modell ger null). Trepassmatchningen är utbruten till `matchByTitle` och delas av `formatForTitle` och `isKnownEv` — den senare svarar på "är kortet en ren elbil?" åt insiktsfiltret, och en egen kopia hade kunnat glida isär från den bil kortet visar specar för |
| `ExpertInsightServiceTest` (39) | RAG-urval: max 5 slumpade insikter i rekommendationer / 3 roterade i chatt (modellträff före märkesträff, vald bil i kontexten räknas som omnämnd), märkesmatchning, källmaskering, CSV-import, kategoribyte, admin-PATCH (fältvalidering, normalisering, rating-gränser), drivlinefiltret på bilkort (HEV-insikt aldrig på EV-kort, `drivetrainOf`-klassningen inkl. **förbränningsledet**: kamrem/kamkedja/bensin/diesel/E20 ger `ice`, hybridord vinner över bensinord i samma text så laddhybridinsikter inte filtreras bort från laddhybridkort, "turbo" och "olja" är medvetet utelämnade eftersom Taycan Turbo S är en elbil och elbilar har oljad reduktionsväxel, markörerna tål svenska ändelser: "hybridEN"/"laddhybridER"/"elbilARNA"), och att kortets drivlina hämtas ur `ev_spec` när titeln själv saknar drivlineord (Mustang Mach-E får ingen EcoBoost-kamremsvarning; samma insikt visas fortfarande på ett Ford Focus-kort; ev_spec-fel släcker inte insikterna). `drivetrainsCompatible` släpper förbränningsinnehåll överallt UTOM på ren elbil — en hybrid har faktiskt en bensinmotor, så Toyotas oljebytesråd hör hemma på ett Corolla Hybrid-kort |
| `ExpertInsightServiceCarLookupTest` (8) | Publika insiktslistan per bilkort (`/api/insights`): märkeskrav, annan modell utesluts, modellspecifika prioriteras, max 3, dubblettrader visas en gång, insikter om ännu ej köpbara modeller visas inte |
| `IceConsumptionServiceTest` (16) | Seed från ice-consumption.csv (957 varianter), titelmatchning (märke+modell, hk-närmaste variant, drivmedelsfilter), jämförelsesammanfattning, hk-parsning, modellord som upprepar märkesnamnet i variant-strängen hoppas över (Mazda 3/CX-5, DS-alla — annars matchar t.ex. Mazda CX-5 på Mazda 3:ans motor), ordgränsmatchning istället för substräng (Mazda CX-30/CX-60 matchar inte Mazda 3:ans/6:ans modellord "3"/"6" bara för att de råkar vara substrängar av "cx-30"/"cx-60"), `engineDescriptor` (motorbeteckning utan modellnamn/märkesupprepning för engineOptions-kortet) — mot riktig H2 |
| `SafetyRatingServiceCsvTest` (6) | CSV-parsern: citattecken, null-fält, trimning |
| `SafetyRatingServiceMatchTest` (4) | Euro NCAP-radens titelmatchning: MG4/Renault 5 träffar rätt utan att spilla över på ZS/MG5/Zoe; otestade bilar (ë-C3) ger null |
| `FeedbackServiceTest` (5) | Tumme upp/ner: röstmappning, summering per bil, ogiltig input avvisas, radering per biltitel, idempotent tabellskapande — mot riktig H2 |
| `CarTitleTest` (12) | **Osynliga mellanslag städas först** (U+00A0/U+202F/U+200B): Javas `\s` matchar bara ASCII-blanksteg, så AI-titeln "Nissan Leaf (62 kWh) (2020)" med *smalt* hårt mellanslag gick orörd genom teknikstädningen live 2026-08-10 — och eftersom "62 kWh" satt kvar i titeln hittade `verifiedEngineOptions` ingen ev_spec-rad alls, så kortet visade AI:ns egen motorlista i stället för de verifierade varianterna (inga trimnamn, ingen generationsfiltrering, räckvidder som inte hörde ihop med batteriet). Den tomma parentesen som blir kvar när tekniken lyfts ut tas bort med den. Årsmodellen ur AI-titeln med eller utan parentes, och städningen till "Märke Modell (år)": årtal mitt i titeln flyttas sist (`Nissan Leaf 2019 60 kWh` → `Nissan Leaf (2019)`, annars ger `year()` null och fem funktioner tappar årsmodellen tyst), teknikuppgifter plockas bort (`60 kWh`, `204 hk`), redan korrekt titel lämnas orörd, trimnamn överlever (`Kia EV4 Long Range`, `MG4 Extended Range`), och modellnamn som ser ut som årtal skyddas av 2010-gränsen (`Peugeot 2008`, `BMW 2002`) |
| `FuelPriceServiceTest` (2) | Bränsleprisradens format i AI-promptarna: båda priserna med, diesel utelämnas om det saknas |
| `ElectricityPriceServiceTest` (2) | Elprisradens format: hemmaladdningsintervall + snabbladdningssnitt, snabbladdningen utelämnas när priset inte kunnat hämtas |
| `EvDatabaseScraperServiceMatchTest` (26) | Nattsynkens namnmatchning — den avgör om en rad uppdateras eller om en parallell rad skapas, så en miss här ger samma bil två gånger. Steg 3 (DB-namnet mer specifikt än ev-databases) testas hårdast eftersom det är den riskabla riktningen: räckvidden avgör vilken variant som träffas (582 km → facelift, 528 → pre-facelift), utan räckvidd görs ingen omvänd matchning alls, för stort räckviddsavstånd ger ingen träff, två lika nära varianter ger ingen träff (hellre en synlig ny rad än fel variant tyst överskriven), för kort skrapat namn letar inte omvänt. Plus att steg 1 och 2 är oförändrade och att steg 2 vinner över steg 3. **Steg 2 har nu samma oavgjort-spärr** (två lika långa DB-namn som båda ryms i det skrapade namnet ger null i stället för att iterationsordningen avgör; olika längd är däremot inte oavgjort — det mest specifika namnet vinner fortfarande), och **`claimRow` spärrar kollisioner mellan anrop**: första bilen får raden, nästa bil som pekar på samma rad blockeras och loggas, samma bil två gånger är ingen kollision. **Korta DB-ord måste stå som eget ord** (`MIN_DB_WORD_FOR_SUBSTRING` = 3): delsträngsfallbacken finns för att normaliseringen delar "ID.3" till "id 3" men lämnar "ID3" hopskrivet, och för ord på ett eller två tecken skrev den systematiskt fel bil till fel rad. Uppmätt mot skarp data 2026-08-11 (518 DB-rader mot 639 bilar på cheatsheeten): `Hyundai IONIQ 3 61 kWh` träffade raden `Hyundai IONIQ 6` eftersom "6" ryms i "61", `Renault 4 E-Tech 52kWh` träffade `Renault 5 E-Tech 52kWh` ("5" i "52"), `Volkswagen ID. Polo 155 kW` träffade `Volkswagen ID.5`, `Mercedes-Benz GLC 400 4MATIC` träffade `Mercedes-Benz C 400 4MATIC` ("c" i "glc"), `Audi RS e-tron GT` träffade `Audi S e-tron GT`, och `Škoda Enyaq 85x` träffade `Škoda Enyaq 85` fast x är fyrhjulsdriften. Gränsen 3 tar samtliga; 4 provades och kostade fyra äkta träffar utan att stoppa något nytt. Bonuseffekt: de åtta Tesla Model 3/Y-varianter som fastnade i oavgjort-spärren gjorde det för att båda de lika långa DB-namnen träffade via delsträng, och matchar nu rätt rad |
| `CargoSpecServiceFillTest` (7) | Bagagevolym från nattens EV-synk: tom rad fylls, **kurerad volym skrivs aldrig över** (DataLoaders seedade är handkontrollerade), mest specifika namnet vinner (en GT-sida fyller GT-raden, inte basmodellens), **bil utan rad får en ny** (luckan är saknade rader, inte tomma — den första versionen vägrade skapa och kunde därför aldrig fylla något), en redan täckt bil får ingen dubblett under variantnamn (matchningen söker bland ALLA rader, inte bara de tomma), enordsnamn som `Volvo` matchar aldrig, och volym 0 varken fyller eller skapar — annars ser en omätt bil ut som en bil utan bagage och fälls av bagagevakten |
| `DataLoaderDedupeTest` (6) | Städningen av `ev_spec`: dubblettraderingen behåller **högsta** id per `car_name` (inte lägsta — den högsta är den kopia nattsynken faktiskt hållit uppdaterad), städningen sker **före** det unika indexet skapas (indexet misslyckas annars mot en tabell som fortfarande har dubbletter), indexet är unikt och idempotent, uteslutna märken raderas med portabel SQL (ingen `ILIKE`), och `EV6_PRISER` täcker alla sex varianterna med rimlig prisordning (AWD över 2WD, GT högst) |
| `WebInsightScraperServiceTest` (65) | **Utmärkelseundantaget kräver sedan 2026-08-11 förstaplats, svensk marknad och aktualitet.** Undantaget ("mest sålda bilen i sin klass" är en köpsignal, inte en företagsnyhet) var den bredaste oskyddade läckvägen i prompten och släppte in två sorters skräp: danska nyregistreringar från juli 2024 (id 1174–1176, fel marknad *och* för gammalt) och CarUp:s begagnattopplista där varje rad var en enskild placering — "sjätte plats", "åttonde mest sålda" (id 1182/1184/1185/1186). Alla tre villkoren måste nu vara uppfyllda, och osäkerhet på något av dem utesluter raden. Gränsen åt andra hållet är låst i samma test: id 1183 ("Tesla Model Y återtog tronen som mest sålda") är fortfarande RELEVANT, liksom Årets Bil och "bäst i test". **Värderingsstudier lyftes ur den regeln 2026-08-12 och kräver i stället marknadsangivelse.** Skärpningen dagen innan hade nämnt iSeeCars *inuti* försäljningsundantaget, vilket inte höll: natten 2026-08-12 skrapades samma iSeeCars-siffra in igen (id 1221, Jaguar I-Pace −72,2 % på fem år) därför att RELEVANT-definitionen strax ovanför uttryckligen tillåter "mätvärden" som hjälper en köpare *värdera* en bil — prompten motsade sig själv och läckan vann. Värdeminskning är en av de tyngsta kostnaderna för en köpare, så villkoret är inte längre svensk marknad utan att marknaden **framgår**: värdeminskning, restvärde, tillförlitlighet och livslängd är RELEVANTA för alla modeller oavsett var studien gjorts, men insikten måste skriva ut varifrån siffran kommer ("enligt en amerikansk undersökning") — annars läser en svensk köpare den som om den gällde här, och då utesluts raden. Kravet ställs i `SYSTEM_PROMPT` och inte bara i vakten, eftersom en rad utan brasklapp annars extraheras enbart för att falla ett steg senare och siffran går förlorad. Testet låser också gränsen åt överblockeringshållet: bilens egna mätvärden (pris, förbrukning, räckvidd, laddtid, testresultat) behöver ingen brasklapp — ett tyskt ADAC-test mäter samma bil som säljs här. Skärpningen står på **två** ställen — `SYSTEM_PROMPT` som plockar ut insikten och `RELEVANCE_PROMPT` som dömer den — och testet låser båda, eftersom en rad som extraheras bara för att stoppas ett steg senare kostar ett Groq-anrop i onödan. Uteslutningarna i `RELEVANCE_PROMPT` är i övrigt låsta med de rader som läckt förbi dem: **modellspecifik fordonsskatt** (natten 2026-08-10 sparades V60 Recharge 360 kr/år och Cayenne Turbo E-Hybrid 2 714 kr/år fast "skatter" redan stod i listan — ett belopp knutet till en enskild modell lästes som en egenskap hos bilen, men det följer av regelverket och ändras med det; bilens pris, förbrukning och CO2-värde måste förbli RELEVANTA) och **renoveringsobjekt** (Volvo 240 återställd för 38 000 kr passerade veteran-/entusiastregeln samma natt). En sedd nyckel går att glömma igen (`forgetSeen`) så en tappad artikel läses om, med LIKE-eskapning av `_` i URL:er vid prefixmatchning och längdkrav som hindrar att hela `web_insight_seen` töms; bara 404/410 markerar en artikel som färdig medan 403/429/5xx får nytt försök; och en hel insikt ryms i vaktens stopprad (`LOG_INSIGHT_CHARS`). Extravakten prövar kommande modeller mot en **egen prompt** utan säljbarhetsregeln (den regeln — "går inte att köpa i Sverige idag" — är negationen av relevansvaktens KOMMANDE-definition, så de två dödade varandras beslut och `insight_upcoming` kunde aldrig få en rad; att i stället hoppa över raderna helt släppte 2026-08-04 in ett dollarpris för USA-marknaden och en fabriks produktionsvolym i kön). Testerna låser att kommande-prompten saknar säljbarhetsregeln men behållit resten, att de två grupperna får var sin prompt och egna index, och att ordningen bevaras när kommande blandas med vanliga. **En fälld kommande-rad parkeras numera i kön i stället för att kastas** (2026-08-11): natten dessförinnan markerade kommande-vakten "Volvo V40" som kommande modell — avvecklad 2019 och full av exemplar på begagnatmarknaden — varpå extravakten dömde Folksams "Bra val"-rad mot kommande-kriterierna och den var borta. Kommande-vakten får enligt design bara parkera, men en felmarkering routade raden till den enda vakt som får kasta, så en felaktig KOMMANDE-dom blev i praktiken en radering. Kön är dold för användaren och går att rensa i efterhand medan en tappad rad inte går att få tillbaka, så domen loggas nu som WARN i stället. Asymmetrin är låst i test: den fällda **säljbara** raden kastas fortfarande (den hade blivit synlig), och ett uteblivet Groq-svar parkerar kommande-raderna i stället för att fail-closed-vägen tappar dem. Markeringen **ärvs inom batchen per bil**: relevansvakten avgör KOMMANDE rad för rad och domen är inte stabil (2026-08-05 markerades bara en av tre CarUp-rader om Audi A2 e-tron, så de två andra prövades mot säljbarhetsregeln och stoppades; A/B mot samma batch med produktionsparametrar gav fyra olika uppdelningar på sex observationer), medan rader utan både märke och modell aldrig grupperas — en tom nyckel hade dragit ihop orelaterade rader. (inkl. att ett tomt/oparsbart vaktsvar ger `null` från `parseIndexesOrNull` och därmed hoppad chunk, medan en äkta tom lista släpper igenom allt — och att dedupen behåller sin fail-open-väg) Insiktsscraperns JSON-parsning: insiktslista, markdown-kodstaket, trasig JSON → tom lista, wp-json-länklistor, whitelist för category/fuel_type, mall-eko-rader, insikter utan bilmärke sparas inte, märkesbreda insikter utan modell sparas inte (de skulle annars visas på varje bil av märket), dubblettfiltrering mot DB (normaliserad textjämförelse, fuzzy bilmatchning över märkesstavningar, batch-intern dedup, parafras-promptbygge, dedup-svarsparsning med fail open), relevansvakt (indexparsning, promptbygge, fail open utan API-nyckel — Groq-fel under själva anropet är numera fail-closed, hoppar över batchen), extravakten för strikta källor (säljbarhetsgranskningen är CarUp-only, övriga källors säljbara rader går förbi den utan extraanrop — men **kommande-vakten körs på alla källor**, eftersom kön i praktiken fylls av Teknikens Värld och M3: natten 2026-08-06 mötte id 1178 "Volvo planerar att öka produktionen av den kommande EX60" aldrig vakten som stoppar just produktionsrader, enbart för att M3 inte är strikt källa), statusradens skillnad mellan "0 nya" och en källa som inte hittade något att skrapa, att en varning inte döljer antalet sparade insikter, att Elbilen pekar på posttyperna `tester`/`artiklar` och inte standardtypen `posts`, och att car.info är borttagen som källa, 429-backoffen (`retryDelayMs`: `retry-after`-headern vinner, annars läses "try again in 2m59.56s" ur felkroppen, tak 60 s och golv 1 s, trappa 10/20/30 s bara när Groq inte säger något), och att en insikt om en kommande modell sparas men flaggas medan en vanlig insikt inte gör det |
| `UpcomingInsightServiceTest` (6) | Kommande-flaggan: markering skriver raden och tömmer cachen (annars låg den gamla mängden kvar i fem minuter), id-mängden cachas mellan uppslag (den läses på varje insiktsuppslag), `release` svarar om raden fanns, DB-fel döljer ingenting (fail open — hellre en kommande insikt synlig än att alla insikter slocknar) |
| `MobilityStatsSyncServiceTest` (9) | Mobility-månadssynken: xlsx-parsning av rankingarken (in-memory-workbook), namnnormalisering (EX/XC40 → EX40, VW → Volkswagen), periodintervall, artikel-/xlsx-länkextraktion, ersättningslogik + felväg utan rapport |
| `JobStatusServiceTest` (9) | Körstatusen för de schemalagda jobben: `track` returnerar jobbets antal och skriver start + slut, undantag ur jobbet ger `-1` och en `FEL:`-märkt rad i stället för att fälla schemaläggaren, statusskrivningen sväljer sina egna DB-fel (jobbet får aldrig krascha på loggningen), statusen härleds rätt (`OK`/`RUNNING` när sluttid saknas/`ERROR` vid felprefix/`NEVER_RUN` med schematext), och `allJobs` listar alla fyra i körordning även när DB:n svarar med fel |
| `CarControllerTest` (59) | HTTP-lagret (MockMvc): `DELETE /api/admin/seen-keys` (403 utan nyckel, antal borttagna rader, 400 när tjänsten avvisar värdet), admin-EV-spec-listan (`/api/admin/ev-specs`: 403 utan nyckel, rader med prisvärdhetsetikett, `kmPerYear` går att åsidosätta), X-Admin-Key-skyddet 403, sök- och feedback-rate-limits → 429, valideringsfel 400, cachemarkering, insiktslistan, admin-insiktslista + radering på id + PATCH (200/403/404/400), Mobility-statssynken (200/403/502), admin-feedbackradering, hälso-endpointen (spec-count + scrapestatus, DEGRADED vid tom databas, feltolerans vid DB-fel), Groq-hälsokollens statuskoder (503 UNCONFIGURED/MODEL_MISSING, 200 UNKNOWN/OK), versionsendpointen (unknown/local utan Render-variabler, commit-sha kortas till sju tecken när de finns), jobbstatuslistan i `/api/admin/scrape-status` (`jobs`-fältet per jobb, och att endpointen fortfarande svarar om jobbtabellen kraschar) |

```bash
mvn test          # kör alla tester lokalt (~1 s)
```

GitHub Actions ([maven.yml](.github/workflows/maven.yml)) bygger och kör testerna på varje push och pull request — badgen överst i denna README visar status.

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
    │   │   ├── EvSpecSyncScheduler.java       ← @Scheduled cron 02:00 Stockholm-tid
    │   │   ├── WebInsightScraperService.java  ← Insikter från 7 motorsajter via Groq-extraktion
    │   │   └── WebInsightSyncScheduler.java   ← @Scheduled cron 04:00 Stockholm-tid
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
            └── test.html           ← Lokal testmiljö — samma markup som wordpress-snippet.html, laddar de riktiga car-advice-main.js/car-advice-chat.js (inte en egen kopia)
```

---

## Köra lokalt

**1. Sätt miljövariabler:**
```bash
export GROQ_API_KEY=din_nyckel
export ADMIN_KEY=valfri_lokal_nyckel
export DDL_AUTO=update   # låter Hibernate skapa H2-schemat (prod-default är validate)
```

**2. Starta:**
```bash
mvn spring-boot:run
```

**3. Öppna:** `http://localhost:8080/test.html`

Utan `DB_URL` körs en H2 in-memory-databas som seedas automatiskt vid uppstart — ingen lokal Postgres behövs.

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
| `carCategory` | string | `familjebil`, `suv`, `elbil`, `laddhybrid`, `smaabil` (`ekonomibil` togs bort 2026-08-10, se Funktioner) |
| `hasCharger` | boolean | Laddbox hemma |
| `kmPerYear` | int | Kilometer per år |
| `usage` | string | `pendling`, `familj`, `landsväg`, `stad` |
| `passengers` | int | 1–9 |
| `newCar` | boolean | Ny eller begagnad |
| `fuelType` | string | `bensin`, `diesel`, `hybrid`, `el`, `spelar ingen roll` |
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

### `POST /api/admin/sync-web-insights`

Kör insiktsscrapern manuellt (samma jobb som nattens 04:00-körning). Returnerar `202 Accepted` direkt; synken körs i bakgrunden (virtual thread); resultat i serverloggar (sök "Web insight") eller via `GET /api/admin/scrape-status`.

```bash
curl -X POST https://caradvice.onrender.com/api/admin/sync-web-insights \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `GET /api/admin/insights/upcoming`

Insikter om modeller som är bekräftade för Sverige men ännu inte går att köpa här — sparade av scrapern, dolda för prompter och bilkort. Svar: `{"count": N, "insights": [...]}` med `insight_id`, `marked_at`, källa och biluppgifter.

```bash
curl https://caradvice.onrender.com/api/admin/insights/upcoming \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `DELETE /api/admin/insights/{id}/upcoming`

Släpper insikten när bilen börjat säljas — den blir synlig som vilken annan insikt som helst. `200 {"released": id}`, eller `404` om raden inte var flaggad. Själva insikten raderas inte (det gör `DELETE /api/admin/insights/{id}`).

### `GET /api/admin/scrape-status`

Senaste körningens status för **alla fyra schemalagda jobb** — persisterad i tabellen `web_scrape_status` så den överlever omstarter (Renders loggar rullar bort; den här endpointen svarar på "gick nattjobbet?" utan dashboarden). Toppnivån är insiktsscrapen, `jobs` listar alla fyra i körordning.

`status` per jobb är `OK` (klar), `RUNNING` (pågår — eller avbruten av en omstart mitt i), `ERROR` (körningen kastade undantag; `perSource` börjar då med `FEL: `), eller `NEVER_RUN` (då visar `info` när jobbet är schemalagt). `newInsights` är jobbets returvärde: nya insikter för scrapen, uppdaterade rader för EV-synken, nya bilar för CargoSpec-synken, importerade rader för Mobility-synken.

```bash
curl https://caradvice.onrender.com/api/admin/scrape-status \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

```json
{"status":"OK","startedAt":"2026-07-31 04:00:00","finishedAt":"2026-07-31 04:12:17",
 "newInsights":9,"perSource":"Vi Bilägare: 3, CarUp: 6","schedule":"dagligen 04:00 Europe/Stockholm",
 "jobs":{
   "ev-specs":{"status":"OK","startedAt":"2026-07-31 02:00:00","finishedAt":"2026-07-31 02:12:05","newInsights":294,"perSource":null,"schedule":"dagligen 02:00 Europe/Stockholm"},
   "cargo-specs":{"status":"OK","startedAt":"2026-07-31 03:00:00","finishedAt":"2026-07-31 03:07:47","newInsights":0,"perSource":null,"schedule":"dagligen 03:00 Europe/Stockholm"},
   "web-insights":{"status":"OK","startedAt":"2026-07-31 04:00:00","finishedAt":"2026-07-31 04:12:17","newInsights":9,"perSource":"Vi Bilägare: 3, CarUp: 6","schedule":"dagligen 04:00 Europe/Stockholm"},
   "mobility-stats":{"status":"OK","startedAt":"2026-08-04 05:00:00","finishedAt":"2026-08-04 05:00:06","newInsights":2,"perSource":"https://mobilitysweden.se/...xlsx","schedule":"den 4:e varje månad 05:00 Europe/Stockholm"}}}
```

### `GET /api/ice-consumption` (publik)

Verifierade förbrukningssiffror för ~950 bensin/diesel/hybrid/laddhybrid-varianter ur `ice_consumption`-tabellen — motsvarigheten till `GET /api/ev-consumption` för förbränningsbilar. Konsumeras av Bilresas bränslekostnadskalkylator.

```json
[{"carName":"Volkswagen Golf 1.5 TSI 150 hk","fuel":"bensin","literPerMil":0.65}, ...]
```

### `POST /api/admin/import/seen-keys`

Seedar dedup-tabellen `web_insight_seen` med redan processade nycklar (artikel-URL:er eller omdömes-refs) så att scrapern hoppar över dem. Text-body, en nyckel per rad. Svar: `{"added": N, "table": "web_insight_seen"}`.

```bash
curl -X POST https://caradvice.onrender.com/api/admin/import/seen-keys \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL" \
  -H "Content-Type: text/plain" \
  --data-binary @processed_urls.txt
```

### `DELETE /api/admin/seen-keys?key=<nyckel>&prefix=false`

Motsatsen till seeden: tar bort en nyckel ur `web_insight_seen` så nästa nattkörning läser om artikeln. Svar: `{"removed": N, "table": "web_insight_seen"}`.

Utan den är en artikel som markerats av fel skäl förlorad för gott — dedupen ser bara att nyckeln finns, aldrig varför den kom dit. Det inträffade 2026-08-01: `postGroq` gav upp efter sista 429:an, artikeln bokfördes ändå som läst och kom aldrig tillbaka. Själva tappet är fixat (extraktionen skiljer nu "Groq svarade inte" från "hittade inget"), men de redan markerade raderna behövde en väg tillbaka. Endpointen dubblar som verktyg för att köra om en enskild artikel mot en ändrad prompt.

`prefix=true` matchar alla nycklar som börjar på värdet, för att ta en hel artikelserie från samma källa. `_` och `%` eskapas eftersom de är jokertecken i LIKE och vanliga i URL:er, och prefixet måste vara minst 12 tecken så att ett slarvigt värde inte tömmer tabellen (400 annars). Kräver `X-Admin-Key`-header.

```bash
curl -X DELETE "https://caradvice.onrender.com/api/admin/seen-keys?key=https://carup.se/skrackljud-i-volvos-motor" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `GET /api/admin/cargo-coverage`

Hur stor del av `cargo_spec` som faktiskt har en uppmätt bagagevolym: `{"total":243,"medVolym":243,"utanVolym":0}` (2026-08-10). **Läs `total` rätt:** det är antalet rader i cargo_spec, INTE antalet bilar appen känner till — `/api/cars` svarade 697 och är unionen av cargo_spec och ev_spec. Den förväxlingen dolde att `utanVolym` redan var 0. Finns för att nattens ifyllning (EV-synken, se ovan) annars bara syns i Render-loggen, och för att siffran styr en designfråga: bagagevakten `requireCargoCapacity` faller bara på **positivt bevis** just för att täckningen är låg, och när den närmar sig 100 % för elbilar går regeln att skärpa. Kräver `X-Admin-Key`-header.

```bash
curl "https://caradvice.onrender.com/api/admin/cargo-coverage" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `GET /api/admin/ev-specs?kmPerYear=15000`

Hela `ev_spec` med samma härledda fält som bilkortet visar — prisvärdhetsetikett, pris, räckvidd, batteri, DC/AC. Avsett för granskning av datakvalitet: saknade priser, orimliga räckvidder, vilka bilar som faktiskt får "Utmärkt prisvärdhet". Går via samma `EvSpecService.toDto` som kortet och inte via en egen kopia av poängformeln — en andra implementation hade kunnat glida isär från den etikett användaren ser, vilket gör granskningen värdelös just när den behövs. `kmPerYear` (default 15000) styr laddintervallet i svaret. Kräver `X-Admin-Key`-header.

```bash
curl "https://caradvice.onrender.com/api/admin/ev-specs" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `GET /api/admin/insights?expert=Name&limit=50`

Listar senaste insikterna (nyast först — högsta id, tabellen saknar tidsstämpel) för kvalitetsgranskning av nattens skrapning. `expert` är valfritt filter, `limit` default 50 (max 500). Kräver `X-Admin-Key`-header.

```bash
curl "https://caradvice.onrender.com/api/admin/insights?limit=80" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `DELETE /api/admin/insights?expert=Name`

Tar bort alla expertinsikter för ett givet expertnamn. Kräver `X-Admin-Key`-header.

```bash
curl -X DELETE "https://caradvice.onrender.com/api/admin/insights?expert=Bilprovningen" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL"
```

### `DELETE /api/admin/insights/{id}`

Tar bort en enskild insikt (skräprad ur skrapningen). Svar: `{"deleted": 1, "id": 42}` eller 404 om id saknas. Kräver `X-Admin-Key`-header.

### `PATCH /api/admin/insights/{id}`

Rättar enskilda fält på en insikt utan att radera den (t.ex. felkategorisering ur skrapningen). JSON-body med valfri delmängd av `carMake`, `carModel`, `fuelType`, `category`, `insight`, `rating` — bara skickade fält ändras. `null`/tom sträng tömmer fältet, utom `insight` och `carMake` som aldrig får bli tomma; `category`/`fuelType` normaliseras till gemener; `rating` valideras 1–10. Okänt fältnamn ger 400 med felmeddelande. Svar: den uppdaterade raden. Kräver `X-Admin-Key`-header.

```bash
curl -X PATCH "https://caradvice.onrender.com/api/admin/insights/942" \
  -H "X-Admin-Key: DIN_ADMIN_NYCKEL" \
  -H "Content-Type: application/json" \
  -d '{"category": "suv"}'
```

### `POST /api/admin/insights/rename-category?from=småbil&to=smaabil`

Byter kategoristavning på alla matchande rader. `buildExpertContext` matchar exakt mot frontendens kategorivärden (`ekonomibil`, `smaabil`, `familjebil`, `elbil`, `suv`, `laddhybrid`) — rader med avvikande stavning når aldrig rekommendationsprompten. Svar: `{"updated": N, "from": ..., "to": ...}`. Kräver `X-Admin-Key`-header.

### `GET /api/cars`

Returnerar sorterad lista med alla bilnamn (union av CargoSpec + EvSpec). Används av autocomplete-fälten.

```json
["Audi A3", "Audi Q4 e-tron", "BMW i4", "Dacia Spring", "MG4", "Tesla Model Y Long Range", "Volvo EX30", ...]
```

### `GET /api/car-video?car=Volvo+EX60`

Bilrecension på YouTube för ett bilkort. Hämtas **lazy av frontend** efter att korten renderats — YouTube-uppslaget får aldrig ligga i rekommendationssvarets väg, och en bil utan recension ska bara sakna raden. Tomt objekt `{}` när ingen video finns, nyckel saknas eller dygnstaket är nått.

```json
{
  "videoId": "dQw4w9WgXcQ",
  "title": "Volvo EX60 – provkörning",
  "channel": "Teknikens Värld",
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
}
```

**Sökningen och rankningen.** Frågan är alltid märke plus modell i citattecken så träffen gäller rätt bil, med recensionsordet som OR-lista: `"Volvo EX60" recension|provkörning|test`. En nylanserad bil hinner sällan få en färdig recension men däremot en första provkörning, och den duger gott på kortet. Fem träffar hämtas (samma kostnad som en) och rankas på två saker. **Kanalen väger tyngst:** känd svensk bilkanal (Elbilsmagasinet, Peter Esse, Teknikens Värld, Vi Bilägare, Auto Motor & Sport) före känd engelsk (Autotrader, carwow, Top Gear, Auto Express) före okänd. **Inom samma kanalklass** går ett klipp vars titel lovar en provkörning (`recension`, `provkör`, `test`, `review`, `first drive`, `körd`) före en nyhetsnotis — utan den vikten valdes "Volvo EX60 levererad – nu gäller det!" före första provkörningen av samma bil (uppmätt skarpt 2026-08-07). Vid lika poäng vinner YouTubes egen ordning. Ger den svenska sökningen inga träffar alls görs en engelsk omgång (`"<bil>" review`) — bättre en engelsk recension än ingen rad. Kanalnamn matchas som gemen delsträng, så "Elbilsmagasinet Sverige" och `"Peter Esse "` (med efterföljande mellanslag, så heter kanalen faktiskt) träffar båda.

**YouTube-betyg (kommentarssentiment).** Svaret får ett `sentiment`-objekt när underlaget räcker: `{ "verdict": "bra|blandat|daligt", "label": "Bra", "summary": "…", "commentCount": 34 }`. Kommentarerna hämtas med `commentThreads.list`, som kostar **1 kvotenhet** mot sökningens 100 — den dyra delen är redan betald när videon slogs upp. Groq (`gpt-oss-20b`) klassar dem, för det är inte ett ordräkningsproblem: "bromsarna gnisslade men fixades på garanti" är inte negativt, och "grym video, tack!" säger ingenting om bilen.

Två gränser är medvetna. Prompten räknar **bara omdömen om bilen** — beröm och kritik av kanalen, filmningen eller programledaren ignoreras, annars mäter betyget videons kvalitet. Och under **12 bilrelaterade kommentarer visas ingen ruta alls**: ett "Dåligt" byggt på tre kommentarer är sämre än inget betyg. Domen cachas 30 dagar i `car_video_sentiment` och räknas om direkt om videon byts, eftersom det då är en annan kommentarstråd. Betyget är kommentarssentiment, inte ett omdöme om bilen — kommentarsfält är inget representativt ägarurval.

`DELETE /api/admin/car-video?car=Volvo+EX30` glömmer en cachad video så nästa visning slår upp på nytt — efter ändrad sökning, eller när en bil fått en video som inte håller måttet. `?all=true` tömmer hela cachen.

**Kvoten är den styrande begränsningen.** En sökning mot YouTube Data API v3 kostar 100 av dygnets 10 000 enheter, alltså 100 nya modeller per dygn. Tre spärrar håller den:

1. Träffen cachas permanent per modell i `car_video` — årtalet strippas ur titeln, så `Volvo EX60 (2024)` och `Volvo EX60` delar rad.
2. **Även en miss cachas** (tom `video_id`). Utan det hade varje visning av en bil utan recension kostat 100 enheter igen. Missar prövas om efter 30 dagar, eftersom en nylanserad bil får recensioner senare.
3. Tak på 80 nya sökningar per dygn och process — endpointen är publik, så uppräknade bilnamn hade annars kunnat bränna dygnskvoten.

Utan `YOUTUBE_API_KEY` är tjänsten helt passiv: inget nätanrop, inget skrivet till cachen, ingen ruta på kortet.

### `POST /api/feedback`

Anonym tumme upp/ner på ett rekommenderat bilkort (knappar under varje kort; en röst per bil sparas i webbläsarens `localStorage`). Max 10 röster/min per IP.

**Feedback-loopen:** bilar med netto ≥ 2 tummar ner (max 10 st, uppdateras en gång/timme) injiceras i rekommendations-systemprompten som "ANVÄNDARFEEDBACK: ... rekommendera dem BARA om inget likvärdigt alternativ finns" (`FeedbackService.dislikedCars` + `GroqService.buildFeedbackContext`).

```json
{ "carTitle": "Volvo EX30 (2024)", "vote": "up" }   →  { "status": "ok" }
```

### `GET /api/admin/feedback`

Summering per bil (kräver `X-Admin-Key`), flest röster först:

```json
[ { "car_title": "Volvo EX30 (2024)", "upvotes": 2, "downvotes": 0, "total": 2 } ]
```

### `DELETE /api/admin/feedback?car=Titel`

Tar bort alla röster för en bil (exakt titelmatchning) — städning av test-/skräpröster. Svar: `{"deleted": N, "car": "..."}`. Kräver `X-Admin-Key`-header.

### `GET /api/health`

Hälsokontroll med datastatus — rapporterar antal EV-specs i databasen och senaste insiktsscrape-körningens status. `status` blir `DEGRADED` (fortfarande HTTP 200) om EV-spec-tabellen är tom eller databasen är onåbar, så UptimeRobot-nyckelordsövervakning på `"status":"OK"` larmar vid dataproblem — samma mönster som Bilresas `warm`-nyckelord.

```json
{ "status": "OK", "evSpecs": 1243, "lastScrape": "OK", "lastScrapeFinishedAt": "2026-07-14 04:11:50", "commit": "e6fbecb" }
```

| Fält | Betydelse |
|---|---|
| `status` | `OK` när EV-spec-tabellen har data; `DEGRADED` vid tom/onåbar databas |
| `evSpecs` | Antal rader i `ev_spec`-tabellen |
| `lastScrape` | Insiktsscraperns senaste körning: `OK` / `RUNNING` / `NEVER_RUN` / `ERROR` |
| `lastScrapeFinishedAt` | När senaste körningen blev klar |
| `commit` | Kort commit-hash för koden som kör (`unknown` lokalt) — samma värde som `commit` i [`/api/version`](#get-apiversion), så ett anrop räcker för både hälsa och deploy-läge |

### `GET /api/health/groq`

Verifierar att de konfigurerade Groq-modellerna fortfarande finns i Groqs `/models`-lista (avvecklade modeller — som `llama-3.3-70b-versatile` 2026-06-29 — försvinner ur listan medan appen i övrigt ser frisk ut). Utöver de egna modellerna bevakas extramodeller via `GROQ_WATCHED_MODELS` (kommaseparerad, default `openai/gpt-oss-120b` som Tag/VaderKlader kör). Svaret cachas i 1 timme, så UptimeRobot kan pinga var 5:e minut utan att belasta Groq.

| Läge | HTTP | Body |
|---|---|---|
| Alla modeller finns | 200 | `{ "status": "OK", "models": ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b"] }` |
| Modell avvecklad | **503** | `{ "status": "MODEL_MISSING", "missing": ["..."] }` |
| Groq onåbart (transient) | 200 | `{ "status": "UNKNOWN", "error": "..." }` — inget falsklarm, fel cachas inte |
| `GROQ_API_KEY` saknas | **503** | `{ "status": "UNCONFIGURED" }` |

**UptimeRobot:** lägg till en HTTP-monitor mot `https://caradvice.onrender.com/api/health/groq` — 503 larmar automatiskt.

### `GET /api/version`

Svarar på frågan *"hann deployen ut, och är det min senaste commit som kör?"* utan att man behöver logga in i Render-dashboarden. Öppen endpoint, ingen nyckel.

```json
{
  "version": "1.0.0",
  "commit": "6754daf",
  "commitFull": "6754daf0123456789abcdef...",
  "branch": "master",
  "startedAt": "2026-07-27T21:05:12.482Z",
  "uptimeSeconds": 342
}
```

| Fält | Varifrån |
|---|---|
| `version` | `@project.version@` ur `pom.xml`, ersätts av Maven vid bygget (resursfiltrering från `spring-boot-starter-parent`) |
| `commit` / `commitFull` | Miljövariabeln `RENDER_GIT_COMMIT` som Render sätter vid deploy — kort form är de sju första tecknen |
| `branch` | Miljövariabeln `RENDER_GIT_BRANCH` |
| `startedAt` / `uptimeSeconds` | När JVM:en startade. **Låg uptime = instansen har nyss startat om** — användbart för att bekräfta spindown på free tier, och för att se om en deploy verkligen bytte process |

Kör man lokalt saknas Render-variablerna: `commit` blir `unknown` och `branch` blir `local`. Jämför `commitFull` med `git rev-parse HEAD` för att bekräfta att rätt kod ligger ute:

```bash
curl -s https://caradvice.onrender.com/api/version | jq -r .commitFull
git rev-parse HEAD
```

Den korta hashen finns även i [`/api/health`](#get-apihealth) — vill man bara veta "lever den, och är det senaste koden?" räcker det anropet. Hit för full sha, starttid och branch.

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
| `expert_insight` | Bilexpertinsikter (RAG-kontext för AI-prompten) från namngivna källor (Teknikens Värld, Vi Bilägare, M Sverige, Bytbil, M3, Auto Motor & Sport, Elbilen, CarUp, Folksam, Bilprovningen) — fylls på nattligen av insiktsscrapern |
| `ev_spec` | WLTP-räckvidd, batteri, DC/AC-laddning, pris per EV/PHEV-modell — auto-utökas av daglig scraper. Unikt index `ux_ev_spec_car_name` på `car_name` (se "Senaste bugfixar") |
| `cargo_spec` | Bagageutrymme (standard + max L) för 110+ bilmodeller |
| `safety_rating` | Euro NCAP-betyg per modell (45+ bilar) |
| `ca_user` | Användarkonton: email, BCrypt-lösenordshash, Stripe customer ID, prenumerationsstatus, startdatum, slutdatum, sessionstoken, token-utgångsdatum |
| `saved_search` | Sparade sökningar per användare: preferenser (JSON), rekommendationer (JSON), etikett, skapad-tid (max 20/användare) |
| `rate_limit_log` | Rate limit-logg för `/api/recommend` — IP + tidsstämpel; seedar in-memory-kartan vid restart; städas varje timme |
| `new_car_price` | ICE-nyprisar (SEK) per bilmodell och generation (~80 poster) — injiceras i AI-promptarna för korrekt deprecierings-beräkning; seedas vid varje uppstart (portabel `INSERT ... WHERE NOT EXISTS`) |
| `recommendation_feedback` | Tumme upp/ner per rekommenderad bil (car_title, vote ±1, created_at) — skapas med `CREATE TABLE IF NOT EXISTS` från DataLoader (ingen JPA-entitet, undviker validate-fällan) |
| `web_insight_seen` | Dedup-nycklar för insiktsscrapern (processade artikel-URL:er + sedda ägaromdömen) — skapas med `CREATE TABLE IF NOT EXISTS` från DataLoader. `WebInsightScraperService` körs kl **04:00 Stockholm**: hämtar artiklar från Teknikens Värld (sitemap), Vi Bilägare och M3 (RSS), M Sverige och Bytbil (artikellistor), Auto Motor & Sport, Elbilen och CarUp (wp-json) + Folksams krocksäkerhetsstudie, extraherar insikter via Groq (`groq.insight.model`, default `openai/gpt-oss-120b`) och sparar i `expert_insight`. Manuell trigger: `POST /api/admin/sync-web-insights`; seed av redan processade nycklar: `POST /api/admin/import/seen-keys` (text, en nyckel per rad); glöm en nyckel så artikeln läses om: `DELETE /api/admin/seen-keys?key=…` |
| `car_video_sentiment` | YouTube-betyget per modell (car_name, video_id, verdict, summary, comment_count, fetched_at) — 30 dagars TTL, räknas om när videon byts. Egen tabell i stället för kolumner på `car_video`: prod kör `ddl-auto=validate` och en ny tabell är portabel där `ALTER TABLE` är en risk, samma skäl som `insight_upcoming` |
| `car_video` | Cachad YouTube-recension per modell (car_name, video_id, title, channel, fetched_at) — tom `video_id` betyder "sökt, ingen träff" och prövas om efter 30 dagar. Skapas med `CREATE TABLE IF NOT EXISTS` från `CarVideoService` (ingen JPA-entitet, prod kör `ddl-auto=validate`) |
| `insight_upcoming` | Insikter om ännu ej köpbara modeller (insight_id, marked_at) — raden döljer insikten för prompter och bilkort tills den släpps. Egen tabell i stället för en kolumn på `expert_insight` eftersom prod kör `ddl-auto=validate`; skrivs av `UpcomingInsightService` |
| `web_scrape_status` | Senaste körningens status per schemalagt jobb (job, started_at, finished_at, new_insights, detail) — en rad per jobb (`ev-specs`, `cargo-specs`, `web-insights`, `mobility-stats`), skrivs om vid varje körning; skrivs av `JobStatusService` och läses av `GET /api/admin/scrape-status` |
| `ice_consumption` | Verifierade förbrukningssiffror (l/mil) för ~950 bensin/diesel/hybrid/laddhybrid-motorvarianter — seedas från `ice-consumption.csv` (extraherad ur Bilresa-projektets fordonsdatabas). Används av publika `GET /api/ice-consumption` (Bilresas kalkylator, l/mil) och för att ersätta AI:ns gissade `consumptionLiterPerMil` med verifierade värden i rekommendationer (hk-närmaste variant, drivmedelsfiltrerad; **konverteras ×10 till l/100km** — fältets konvention) samt injiceras som förbrukningsrader i jämförelseprompten (l/100km) |

---

## Deploya på Render.com

1. Pusha till GitHub
2. Skapa **Web Service** → koppla repot → **Docker** runtime, branch `master`
3. Miljövariabler:

| Variabel | Beskrivning |
|---|---|
| `GROQ_API_KEY` | API-nyckel från console.groq.com |
| `GROQ_MODEL` | (valfri) Primärmodell för rekommendationer/jämförelser — default `openai/gpt-oss-120b` |
| `GROQ_CHAT_MODEL` | (valfri) Chatt- och fallbackmodell — default `openai/gpt-oss-20b` |
| `GROQ_RESERVE_MODEL` | (valfri) Reservmodell (tredje 429-utväg + trunkeringsomförsök) — default `qwen/qwen3.6-27b` |
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
| Backend-hälsa (keyword) | `https://caradvice.onrender.com/api/health` — nyckelord `"status":"OK"`, larm när det saknas | 5 min |
| Groq-modeller | `https://caradvice.onrender.com/api/health/groq` | 5 min |

Backend-monitorn håller Render-instansen varm och eliminerar cold starts.

Hälsomonitorn är en keyword-monitor: den larmar både när tjänsten är nere och när servern svarar men datalagret är sjukt — tom/onåbar databas ger `"status":"DEGRADED"` och nyckelordet försvinner ur svaret (se `GET /api/health` ovan).

Groq-modellmonitorn larmar (503) den dag Groq avvecklar en konfigurerad modell — uptime-pingarna missade llama-3.3-70b-avvecklingen 2026-06-29 eftersom appen var uppe medan alla AI-anrop föll. Pingarna kostar inga tokens: `/models`-anropet är ometerat och svaret cachas 1 timme, så Groq ser max ~24 anrop/dygn oavsett pingintervall. Kollen täcker de egna modellerna (`qwen/qwen3.6-27b`, `openai/gpt-oss-20b`) **plus bevakade extramodeller** via `GROQ_WATCHED_MODELS` (default `openai/gpt-oss-120b` — Tag/VaderKlader kör den men saknar egen hälsokoll, så avveckling larmas härifrån).

---

## WordPress-integration

Klistra in `wordpress-snippet.html` i ett **Anpassad HTML**-block på valfri WordPress-sida.

> **OBS:** WordPress synkas inte automatiskt från GitHub. Vid uppdatering av `wordpress-snippet.html` måste koden klistras in manuellt i WordPress-blocket.

---

## Token-budget (Groq gratisplan)

Groq: `openai/gpt-oss-120b` (rekommendationer/jämförelser, `reasoning_effort: low`) och `openai/gpt-oss-20b` (chatt + 429-fallback, `reasoning_effort: low`). Varje sökning använder upp till **2 000 output-tokens** plus ~1 500–2 500 input-tokens (systemprompt med priskontextar). Identiska sökprofiler returneras från 4-timmars cache utan tokenkostnad. Chattboten använder upp till **1 800 output-tokens** per meddelande; historiken begränsas till senaste 8 meddelanden.

**Groq 429-fallback:** en gemensam `callGroqWithFallback(...)`-metod (varargs-kedja) används av alla flöden. Rekommendationer och jämförelser har en trestegskedja: `openai/gpt-oss-120b` → `openai/gpt-oss-20b` → `qwen/qwen3.6-27b` (reservmodell, `groq.reserve.model` — preview-tier, därför inte primär; bevakas av hälsokollen). Varje modell har egen TPM-pott hos Groq, så flera sökningar i rad går igenom även när primärmodellen är strypt. Chatten använder tvåstegskedjan som förut. `chatStream` öppnar en ny stream mot fallback-modellen vid 429 innan fel returneras.

**reasoning_content-fallback:** qwen3/gpt-oss reasoning-modeller kan returnera tomt `content`-fält och lägga svaret i `reasoning_content` — koden läser båda fälten och väljer det som har innehåll.

**Omförsök vid trunkerat/tomt svar:** om AI-svaret inte går att parsa (trunkerat JSON vid max_tokens, eller tomt content — typiskt när gpt-oss-20b bränner tokenbudgeten på reasoning) gör `parseWithRetry` automatiskt ETT omförsök med reservmodellen (`groq.reserve.model`, default `qwen/qwen3.6-27b` med `reasoning_effort: none`) innan felet "AI-svaret blev ofullständigt. Försök igen." når användaren.

**Rate limit på omförsöket är ett EGET fel** (`RateLimitedException`): får omförsöket 429 kastades förr det *ursprungliga* trunkeringsfelet vidare, så användaren fick "AI-svaret blev ofullständigt … prova högre budget, färre passagerare eller ett annat drivmedel" — fast kriterierna var oskyldiga och det enda som hjälpte var att vänta. Skarpt fall 2026-08-10: samma sökning gick igenom direkt efteråt. Symptomen var identiska i koden, vilket gjorde frågan "är det trunkering eller rate limit?" obesvarbar från loggen. Nu bär felet Groqs egen text via `buildRateLimitError` (som dessutom skiljer dygnstaket TPD/RPD från minuttaket och läser väntetiden ur svaret) plus raden "Dina kriterier är inte problemet", och `medRadOmKriterier` lägger aldrig till kriterierådet på den typen. **Räkneexempel bakom felet:** systemprompten är ~1 650 tokens, användarprompten ~400 och `max_tokens` 3 000 — en sökning drar alltså ~5 050 av minutbudgetens **8 000**, så två inom samma minut räcker för att utlösa det.

**Priskontextar cachas:** ICE-nypristabellen och EV-prisreferenserna hämtas från DB en gång per timme och återanvänds på alla anrop — sparar ~4 DB-queries per request.

---

## Senaste bugfixar

| Fix | Beskrivning |
|-----|-------------|
| Varianterna göms av ihopslagningen — en rad per variant | Följdfynd på raden nedan: även med rätt data slog `groupByBattery` ihop allt inom 8 % till ett spann, så MG4 gen 2:s fyra namngivna varianter blev två namnlösa rader (`52.8 kWh (405–416 km)`, `61.7 kWh (405–452 km)`) — trimnamnet faller bort när gruppen innehåller flera namn, alltså syntes versionerna aldrig. Räckvidden skiljer nu dubblett från variant (olika kWh + samma räckvidd = netto/brutto av samma bil; samma kWh + olika räckvidd = två varianter), och ihopslagningen söker i alla grupper i stället för bara den intilliggande, eftersom sorteringen på kapacitet lägger EV6 Long Range 2WD:s 80- och 84-rader isär trots samma 582 km. Samtidigt raderas ev-databases aliasrader för EX30 (P3/P5/P8 AWD = Single Motor/Twin Motor Performance med nettokapacitet), med spärr i synken så de inte återskapas 02:00 — annars hade bilen visat nio rader för fyra bilar. Utfall mot prod-raderna: MG4 2025 **6 rader med versionsnamn** (var 4 utan), MG4 2023 3, EX30 **4** (var 2), EV6 7 (var 3 — bilen har tre batterigenerationer × tre drivlinor) |
| MG4:s versioner syntes inte i "Motor & batterialternativ" | Kortet visade `41.9 kWh (325 km), 52.8 kWh (405 km), 61.7 kWh (405 km), 74.4 kWh (545 km)` — inga trimnamn, och 61,7 kWh parat med 405 km fast databasen har 452 km på den raden (405 km hör till XPOWER). Listan kom alltså från AI:n, inte från `ev_spec`. Orsaken låg tre steg bort: live-svaret bar titeln `Nissan Leaf (62<U+202F>kWh) (2020)` med **smalt hårt mellanslag**, och Javas `\s` matchar bara ASCII-blanksteg, så `SPEC_JUNK` i `CarTitle.normalize` bet aldrig. Med "62 kWh" kvar i titeln hittade `verifiedEngineOptions` ingen rad och `GroqService` föll tillbaka på AI:ns text — precis som den ska när ingen träff finns, alltså utan felmeddelande eller loggrad. Fix: `CarTitle` städar `\p{Cf}` och `\p{Zs}` först och tar bort den tomma parentesen som blir kvar. Verifierat mot de nio riktiga MG4-raderna: `MG4 (2025)` ger nu `41.9 kWh (325 km) · Urban Standard Range, 52.8 kWh (405–416 km), 61.7 kWh (405–452 km), 74.4 kWh (545 km) · Premium Extended Range`, `MG4 (2023)` ger gen 1:s tre. Buggen träffade varje bil vars titel bar batteriet i parentes, inte bara MG4, och samma osynliga tecken kunde slå ut Blockets årsfilter, dedupen, nyprisuppslaget och leasingmatchningen |
| Mobilanpassningen av bilkorten slog aldrig igenom | `@media (max-width: 520px)` låg på rad 70 i snippeten medan `.ca-card-head`/`.ca-card-body` definieras på rad ~449. Media queries ger **ingen** extra specificitet, så vid samma specificitet avgör källordningen — mobilreglerna förlorade mot varje huvudregel längre ned i filen och korten fick desktopens `22px 28px` även på 390 px. Blocket ligger nu sist (lägg nya mobilregler där). Uppmätt på 390 px: `22px 28px 18px` → `18px 20px 14px`; desktop oförändrad. Övriga överstyrningar i blocket (`#ca-hero`, `.ca-grid`, `.ca-field`) låg redan före sina huvudregler och fungerade hela tiden — buggen drabbade bara kortreglerna. Syntes inte förrän padding-resetten nedan fixats, eftersom allt ändå nollställdes dessförinnan |
| Budgetreglaget låg ovanpå sin egen etikett på mobil | `.ca-slider-track` har `margin-top: 12px`, men renderade 0 — samma specificitetsbugg som padding-fallet nedan, fast för `margin`, och den missades i första omgången eftersom bara `padding` togs bort ur resetten då. **38 margin-regler** nollställdes; de tre som bar `!important` (`.ca-card`, `.ca-ask-btn`, `.ca-cargo`) överlevde — spår av att buggen tidigare plåstrats lokalt i stället för vid roten. Utan sin marginal hamnade sliderns 24 px höga träffyta (som börjar `top:-10px` ovanför det 4 px tunna spåret) 10 px in i etikettens box. Fix: `margin` flyttad till samma smala elementlista som `padding`. Uppmätt på 390 px: överlapp 10 px → −2 px (luft), `margin-top` 0 → 12 px |
| Chattknappens etikett klipptes av skärmkanten | `.ca-chat-fab-wrap` är fäst 24 px från högerkanten med `align-items: center`. Etiketten är bredare än den 58 px breda knappen och växer då åt båda håll — halva hamnade utanför viewporten, så "💬 Fråga om Škoda Octavia Combi" klipptes mitt i ordet. Fix: `align-items: flex-end` så den bara växer inåt, plus `max-width: min(58vw, 240px)` med ellips eftersom bilnamnen saknar övre längdgräns och `white-space: nowrap` annars skjuter en tillräckligt lång titel in över kortinnehållet. Verifierat med en medvetet lång titel ("Volkswagen ID.Buzz Pro Lång Bas"): 12 px innanför kanten på 390 px, 24 px på 1000 px |
| Hela appens padding nollställdes av snippetens egen reset | Rad 2 i `wordpress-snippet.html` var `#ca-wrap * { box-sizing:border-box; margin:0; padding:0 }` — en reset mot WordPress-temat. Men `#ca-wrap *` har **ID-specificitet (1,0,0)** och slog därför snippetens egna klassregler (0,1,0): **32 padding-regler nollställdes**, medan de 15 som råkade ha ID-selektor överlevde. Därför hade `#ca-hero` sin luft kvar medan varje kort, chip, badge och knapp låg tryckt mot ramen. Uppmätt på produktion: kortrubriken började **3 px** från kanten på ett 818 px brett kort. Fix: padding-resetten gäller nu bara de element WP-teman faktiskt stylar (`p, ul, ol, li, h1–h4, blockquote, figure, table`) — ingen av de 32 reglerna sitter på dessa taggar, så de kan inte krocka, och `.ca-pros` behåller sin ul-reset. Verifierat före/efter mot **riktig produktionsmarkup** (Playwright, skarp sökning + injicerad fix): rubrikinset 3 → 31 px, ingen horisontell scroll på 390 px eller 1000 px. Kvarstående, ej orsakat av fixen: budgetetiketten överlappar slidern med 10 px på mobil — identiskt före och efter |
| Bilremsan slösade 83 % av sin yta | Wrappern var 814 × 80 px med `object-fit: contain` och en egen ljus platta som bakgrund. Ett 16:9-foto blev då ~142 px brett i ett 814 px brett fält — resten tom platta som läste som ett fel. Fix: remsan är 150 px hög (fotot blir ~2× större) och bakgrunden genomskinlig så överskottsytan smälter in i kortet i stället för att bilda ett eget band. `contain` behålls medvetet — Wikipedia-bilderna har spretiga proportioner och `cover` hade beskurit bilar på måfå |
| Sektionsetiketterna gick inte att läsa | `.ca-section-label` (Fördelar / Nackdel / Passar dig — de som strukturerar hela kortet) låg på `rgba(255,255,255,0.28)` vid `0.6rem` = 9,6 px och försvann mot kortbakgrunden. Höjd till 50 % opacitet och 11 px: fortfarande underordnad brödtexten, men synlig |
| EV6-varianter fick aldrig pris — synken matchade bara åt ena hållet | Sex EV6-rader låg med `priceKr = 0` i tron att nattsynken skulle fylla dem. Det kunde den aldrig: `findMatch` hade bara två steg — exakt namnträff, annars *"alla DB-namnets ord finns i det skrapade namnet"*. Den riktningen är enkelriktad. Våra rader heter `Kia EV6 Long Range 2WD 84 kWh`, ev-database säger `Kia EV6 Long Range 2WD`; orden `84` och `kwh` saknas där, så träffen uteblev varje natt och synken skapade en **parallell rad** som fick priset — samma bil under två namn, med nettobatteri i synkens rad och brutto i vår. Det unika indexet fångar det inte; namnen skiljer sig på riktigt. Fix: **steg 3 i `findMatch`** hanterar omvänd riktning (DB-namnet mer specifikt) med **räckvidden som tie-break** — `Long Range 2WD` på 582 km kan bara vara facelift-raden, pre-facelift ligger på 528. Två spärrar mot felträffar: skrapat namn måste ha ≥3 ord (annars matchar `Kia EV6` ett dussin rader) och räckvidden måste ligga inom 10 %. Två lika nära varianter ger **ingen** träff — hellre en ny rad som syns än fel variant överskriven i tysthet. Priserna ligger nu i `DataLoader.EV6_PRISER` (svenska listpriser vid lanseringen av 77,4-generationen, källa alltomelbil.se) |
| Bilar långt över budget rekommenderades (Kia EV3 på 359k för 275k-budget) | Live-fynd: budget 275 000 kr gav Kia EV3, som börjar på 359 000 kr på Blocket — 84 000 kr över och alltså inte köpbar. Budgeten var enbart en promptregel (`UTNYTTJA BUDGETEN`), aldrig kontrollerad mot marknadsdata. `correctedPrice` gjorde felet *synligt* utan att åtgärda det: den bytte AI:ns påhittade pris mot Blockets riktiga och visade 359 000 kr på ett kort som föreslagits för en 275 000-budget. Fix: `exceedsBudgetCeiling()` fäller varje bil vars **billigaste** Blocket-annons ligger mer än 30 000 kr över budget (≥2 annonser krävs, samma outlier-skydd som `correctedPrice`). Kontrollen kan inte ligga bland regelvakterna i `parseWithRetry` — priset är känt först efter berikningen — så den kör som en egen, senare runda med ett omförsök där de för dyra bilarna pekas ut vid namn med sitt verkliga pris. Slutlistan plockas ihop av de bilar som **håller budgeten ur båda omgångarna** (`mergeWithinBudget`), omförsöket först, upp till tre, dedupat på **modell och inte titel** — två listor som var för sig är dubblettfria kan ihopslagna ge "ID.4 (2022)" bredvid "ID.4 (2021)", vilket regeln om tre olika modeller förbjuder — bara om ingendera gav en köpbar bil behålls ursprungssvaret. Första versionen behöll i stället hela ursprungssvaret när omförsöket inte blev bättre, och live-testet visade genast varför det inte dög: en Volvo EX40 på 439 000 kr slank igenom mot en 275 000-budget, 164 000 kr över taket. En kortare lista med köpbara bilar är mer värd än en full lista där en bil inte går att köpa. **Bara ett tak, inget golv** — en billigare bil är fortfarande köpbar, och prompten ska kunna lägga ett prisvärt fynd bland förslagen; i stället styr `SIKTA MOT SPANNET` att minst två av tre ligger inom ±30 000 kr. Gäller inte leasing (budget i kr/mån, Blocket hämtas inte) eller nybilssök (begagnatpris är fel måttstock) |
| 756 dubblettrader i `ev_spec` (60 % av tabellen) | Skanning av `/api/ev-consumption` 2026-07-28: 1 261 rader men bara 505 unika bilnamn. Fördelningen var onaturligt ren — 127 namn i 1 exemplar (exakt DataLoaders seed-rader), 378 namn i exakt 3 (exakt de nattsynken auto-skapat), inget däremellan. Värre än slöseriet var följdfelet: `EvDatabaseScraperService` bygger sin `nameMap` som namn → EvSpec, så tre rader med samma namn kollapsar till en och nattsynken uppdaterade bara den ena — de andra två frös fast med gammal data som bilkortet kunde plocka upp, vilket gav rader som blandade netto- och bruttokapacitet för samma bil (samma familj som Kia EV6-buggarna). Fix: `DataLoader.dedupeEvSpecs()` behåller högsta id per `car_name` (den kopia `findAll()` ger `nameMap` sist, alltså den enda som hållits uppdaterad) och lägger ett unikt index `ux_ev_spec_car_name`. Synken fångar `DataIntegrityViolationException` och loggar `SCRAPER ALERT` per bil i stället för att sänka hela nattens körning. **Varför matchningen missade sina egna rader i exakt tre körningar är inte klarlagt** — indexet gör frågan ofarlig men inte besvarad: nästa gång det händer syns det i loggen i stället för i datan |
| EX30 fick fabricerade batterivarianter i "Motor & batterialternativ" | Live-fynd (användaren jämförde bilar): EX30:s `engineOptions` visade "58 kWh 150hk (420km), 77 kWh 200hk (540km), 44 kWh 150hk (480km)" — helt påhittat, de riktiga varianterna är 51/65/65 kWh. Roten: `engineOptions` var ren AI-fritext, aldrig verifierad mot `ev_spec` (till skillnad från pris/safety/förbrukning/EV-chippen som redan var det). Fix: ny `EvSpecService.verifiedEngineOptions()` hämtar ALLA matchande varianter (inte bara bästa träff som `formatForTitle`), dedupar identiska rader, sorterar kWh→räckvidd, och ersätter `engineOptions` i `enrichRecommendations` när en träff finns. Ingen hästkraft per variant (inte lagrat i DB) — hellre mindre info som stämmer än mer som delvis är påhittad |
| Blocket-snappingens tröskel sänkt 3 → 2 annonser | `correctedPrice` litade bara på Blocket-priset vid ≥3 annonser, vilket lämnade tunt annonserade bilar med AI:ns egen (okontrollerade) prisgissning. Övervägde att bygga en Java-portering av deprecieringsformeln (nypris × ålderskoefficient) för att kryssa av dessa fall istället, men tackade nej — `new_car_price`-tabellens 105 rader kodar generation som fritext i tre olika format ("2021+"/"2015-2020"/"(Gen3)") och en andra deprecieringsimplementation i Java riskerar att glida isär från prompttexten (`DEPRECIATION_RULE`). Sänkte istället tröskeln till 2 annonser — inte 1, eftersom `BlocketPriceService` bara percentil-trimmar outliers vid ≥5 träffar, så en enda annons har inget skydd mot en scam-/felannons |
| Modellhallucinationsvakt (`requireKnownModels`) | Modellexistens var tidigare bara en promptregel ("Hitta ALDRIG på Volvo-modeller") utan kodkontroll. Ny validator jämför varje rekommenderad titel mot en whitelist av ~700+ modeller ur cargo_spec/ev_spec/ice_consumption (ordmängd-delmängdsmatchning i båda riktningar så trimvarianter som "Octavia Combi" mot databasens "Skoda Octavia" godkänns) och triggar samma engångs-omförsök som familjespärren vid ett regelbrott. Whitelisten är inte uttömmande — okänd modell blockerar bara tillfälligt, aldrig permanent |
| Hallucinationsaudit: disclaimer + relevansvakt fail-closed | Ingen del av UI:t hade en synlig disclaimer om att AI-fritext kan innehålla fel — tillagd under bilkorten och i chattpanelen. `WebInsightScraperService.filterIrrelevant` sparade tidigare hela batchen ofiltrerad om Groq-relevansanropet felade ("hellre en skräprad än en tappad insikt") — det är den enda spärren mot att irrelevant/hallucinerat innehåll når den "verifierade" insiktsdatabasen, så beteendet är nu fail-closed (hoppar över batchen). Chattens `buildChatSpecFacts` injicerar nu även Euro NCAP-betyg och verifierad bränsleförbrukning, inte bara benutrymme/batterikemi. Ny loggrad flaggar rekommendationer utan någon verifierad specdata (synlighet, ej blockerande) |
| Förbrukning visades som "0,07 l/mil" | Enhetskonventionen: `consumptionLiterPerMil` bär **l/100km** trots namnet (AI:n svarar så och frontenden delar med 10 vid visning, räknar ägandekostnad på l/100km). De verifierade `ice_consumption`-värdena (äkta l/mil) injicerades utan konvertering → tiofalt fel. Nu ×10 vid enrichment, jämförelseprompten uttrycker l/100km, och AI-svar som redan är i l/mil-skala (< 3) normaliseras. Live-verifierat: Sandero 5.8 → 0,58 l/mil på kortet |
| Insiktsrotation + feedback-loop | `buildExpertContext` tog alltid samma 2 första insikterna i databasordning — nattens nya insikter nådde aldrig prompten. Nu slumpas 5 ur hela den matchande poolen per sökning. Dessutom: bilar med netto ≥ 2 tummar ner injiceras som undvik-signal i systemprompten (cachas 1h) |
| "AI-svaret blev ofullständigt" vid flera sökningar i rad | Snabba sökningar i följd fick 429 på qwen och föll tillbaka på gpt-oss-20b vars reasoning åt upp tokenbudgeten → trunkerat JSON → fel till användaren. Nu: (1) trestegskedja qwen → gpt-oss-20b → gpt-oss-120b vid 429 (egen TPM-pott per modell hos Groq), (2) automatiskt omförsök med gpt-oss-120b när svaret kom tillbaka trunkerat/tomt, för både rekommendationer och jämförelser |
| Lokal H2-boot lagad | `NewCarPriceService` använde Postgres-syntaxen `ON CONFLICT` som H2 inte stöder → lokal start kraschade i `DataLoader`. Ersatt med portabel `INSERT ... SELECT ... WHERE NOT EXISTS` (seed) och `UPDATE`-först-annars-`INSERT` (upsert); `spring.jpa.hibernate.ddl-auto` är nu `${DDL_AUTO:validate}` så H2-schemat kan skapas lokalt med `DDL_AUTO=update` |
| Groq-modellhälsokoll | Ny `GET /api/health/groq` verifierar `groq.model` + `groq.chat.model` mot Groqs `/models`-lista (1h-cache) och svarar 503 `MODEL_MISSING` vid avveckling — UptimeRobot larmar. Transienta Groq-fel ger 200 `UNKNOWN` (inga falsklarm) och cachas inte |
| Robustare AI-JSON-parsning | `extractJson` hanterar svar med bare root-array (behöll tidigare inte hakparenteserna → array-fallbacken triggades aldrig); `convertRecommendations` fångar schemafel och ger begripligt fel istället för 500; `@JsonIgnoreProperties(ignoreUnknown=true)` på `CarRecommendation` så AI:ns påhittade extrafält inte fäller parsningen |
| `extract_insights.py` avvecklad modell | Scriptet körde `llama-3.3-70b-versatile` (avvecklad 2026-06-29) → `openai/gpt-oss-120b` med `reasoning_effort: low` och `GROQ_MODEL`-env-override |
| Mockito på Java 25 | Spring Boot 3.2 pinnar Mockito 5.7/Byte Buddy 1.14 som inte kan mocka klasser på Java 25 — versions-overrides i `pom.xml` till Mockito 5.23/Byte Buddy 1.17.7 |
| TCO leasing-kalkyl | `caParseLeaseMonthly` läste köppriser (t.ex. "330 000 kr") som månadskostnad → TCO visades som ~18 miljoner. Fixat: parsar nu bara som månadsbelopp om strängen innehåller "mån"; faller tillbaka på användarens budget-slider som leasingkostnad |
| Elbilar: "obligatorisk årsavgift" | Chatbotten påstod att BYD/MG4 m.fl. har en obligatorisk årsavgift på 1 800 kr — det finns ingen sådan generell avgift i svensk lag. System-prompt korrigerad med faktaanvisning |
| Elbilar: "turbo/ej turbo" i fördelar | AI annoterade elbilars batterivarianter med "(turbo)" / "(ej turbo)". Fixat: turbo-terminologi förbjuds för elbil/laddhybrid i systempromptarna |
| BYD Seal, Genesis GV60, Toyota Camry borttagna | Dessa bilar säljs inte i Sverige; borttagna från EV-spec-databas och systempromptarna |
| Dacia Spring saknade EV-data | Dacia Spring fattades i ev_spec-tabellen — lade till (225 km WLTP, 26,8 kWh, 30 kW DC) |
| MG4 matchade inte EV-databasen | EV-spec-posterna hette "MG4 Long Range" m.fl. — "MG4 2025" hittade ingenting. Fixat med ny Pass 3 i fuzzy-matchningen + baspost "MG4" |
| Prenumerationslängd på kontosidan | Kontosidan visar nu "Prenumerant i: X månader/år" (beräknas live i webbläsaren via ISO-datum från `/api/auth/me`), "Startade: X" och "Förnyas: X" |
| Tidzon UTC→Stockholm | Render kör i UTC — datum formaterades i UTC vilket kunde ge fel dag. Nu konverteras alla prenumerationsdatum till `Europe/Stockholm` innan formatering; ISO-strängen får `Z`-suffix så att `new Date()` i webbläsaren räknar durationen korrekt |
| Backfill subscriptionStartedAt | Befintliga aktiva prenumeranter saknade startdatum (kolumnen tillkom efter deras aktivering). Vid uppstart sätts `subscriptionStartedAt = createdAt` för alla aktiva användare där fältet är null |
| Chattbot avskuren text | `max_tokens` för chat/chatStream höjt från 600→900→1200→1800 — längre svar klipptes mitt i meningen |
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
| Groq 429-fallback | `getRecommendation()` retryar automatiskt med fallback-modellen (numera `openai/gpt-oss-20b`) om primärmodellen svarar 429 — kastar bara fel om båda modellerna nekar |
| TCO-stapeldiagram | `caTcoBarChart()` ritar horisontella staplad-bar-chart under jämförelsetabellen med fem färgkodade segment per bil |
| Bilbilder på korten | Wikipedia REST API (CORS-öppen) lazy-loadar thumbnail per bilkort efter render; försöker engelska Wikipedia → svenska Wikipedia; döljs tyst om ingen bild hittas |
| Sparade sökningar | Inloggade användare kan spara sökningar till DB via "Spara sökning"-knapp; hämtas från server vid inloggning och visas som chips ovanför historiken; DELETE tar bort enskild post |
| Rate limit-persistens | In-memory rate limit-karta seedas från DB vid uppstart (`@PostConstruct`) — sökkvoter nollställs inte längre vid deploy eller cold start; async DB-skrivning per tillåten sökning; `@Scheduled` cleanup varje timme |
| Expertnamn i JS | Hårdkodat namn i expertopinions-div ändrat till "Bilexpert" för att matcha backend-attributionen |
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
| Volvo EV-hallucination förhindrad | Chatboten hittade på modeller som "C90" som inte existerar. Explicit Volvo EV-lista tillagd i alla tre systempromptarna: EX30, EX40 (f.d. XC40 Recharge), EC40 (f.d. C40 Recharge), EX60, EX90. Generell regel: nämn aldrig modeller som inte officiellt säljs på svenska marknaden |
| Škoda EV-referenspriser tillagda | Epiq (fr. 389 000 kr), Elroq (fr. 450 000 kr), Enyaq (fr. 599 500 kr) och Peaq (654 000 kr) tillagda i alla tre referensprislistorna — förbättrar AI:ns prisuppskattningar för Škoda-elbilar |
| Groq-modeller bytta (omgång 1) | `llama-3.3-70b-versatile` (deprecated 2026-06-29) → `openai/gpt-oss-120b`; fallback `llama-3.1-8b-instant` → `qwen/qwen3.6-27b` |
| Groq-modeller bytta (omgång 2) | `openai/gpt-oss-120b` visade sig vara reasoning-modell på Groq — returnerar tomt `content` eller trunkerat JSON. Primär bytt till `qwen/qwen3.6-27b` (versatile, `/no_think`); fallback till `gpt-oss-20b` (instant). `reasoning_content`-fallback tillagd; trunkerat JSON ger clean error. |
| NewCarPriceService | Ny `new_car_price`-tabell med ~65 ICE-nyprisar per generation seedas vid uppstart; injiceras i alla AI-systempromptars pris-kontext |
| Groq-anropsoptimering | ICE/EV-priskontextar cachas 1h (tidigare DB-query per anrop); compare-resultat cachas 4h; fallback max_tokens 4000→1050; chatthistorik begränsad till senaste 8 meddelanden |
| GroqService-refaktorering | `buildRequest`/`callGroqWithFallback`/`enrichRecommendations` extraherade — eliminerar ~80 rader duplikat HTTP- och enrichment-kod; `DEPRECIATION_RULE` som konstant; chat() och chatStream() får nu 429-fallback till primärmodellen |
| Admin-endpoints konsekvent | Alla admin-endpoints (18 st idag): `required=false`, gemensam `isAdminUnauthorized`-helper, konsekvent 403 JSON (tidigare: blandade 401/403, en del kastade 400 om header saknades) |
| CSV-filer i .gitignore | `*.csv` tillagd — `bilprovningen_insights.csv`, `tv_insights.csv`, `vb_insights.csv` visas inte längre som untracked i git |
| Blocket-sökning för specifika motorvarianter | `stripYear()` → `extractSearchQuery()` strippar nu motorvariant och batterikapacitet ur söktermen: "Škoda Kamiq 1.0 TSI" → "Škoda Kamiq", "Tesla Model Y Long Range" → "Tesla Model Y", "Citroën ë-C3 26 kWh" → "Citroën ë-C3" — Blocket hittade inga priser för dessa tidigare |
| Blocket: årsfilter ±1 år | `extractYear()` hämtar årtalet ur AI-titeln (t.ex. "(2021)") och lägger till `year_min`/`year_max` (±1 år) i Blocket-API-anropet — tätare urval ger mer relevanta priser; cache-nyckeln inkluderar år; Blocket-länken i JS använder samma ±1 år-filter **(rättat 2026-08-07: API:t ignorerade parametrarna helt — filtreringen sker nu på `docs[].year`)** |
| Blocket P20–P80 + 60 annonser | Hämtar nu 60 annonser (upp från 20) och visar P20–P80 istf absolut min/max — klipper havererade/extremt utrustade outliers mer aggressivt **(P20 ersatt 2026-08-07 av medianrelativa gränser, se nedan)** |
| Prisetikett "Nypris" → "Pris" | Kortet och jämförelsetabellen visade alltid "Nypris" även vid begagnad-sökning — ändrat till neutralt "Pris" som är korrekt i båda fallen |
| Max ålder-filter | Nytt `#ca-maxage`-fält i formuläret (Max 3/5/8/10/15 år) visas när "Begagnad" väljs — skickas som `maxAgeYears` till backend, injiceras i AI-prompten som explicit ÅLDERSKRAV med förbjudna årsmodeller |
| new_car_price alltid backfill | `seedDefaults()` körs nu vid varje uppstart (ON CONFLICT DO NOTHING) — nya bilar läggs till utan att tabellen behöver tömas; lade till ~12 modeller: Peugeot 3008, Ford Kuga, Citroën C3/ë-C3, Volvo S60/V90, Kia Picanto/Rio, Hyundai i10, Audi A4, BMW 3-serie, Mercedes C-klass |
| Fabricerade priser förhindras | AI-prompten skärpt med konkret Octavia-räkneexempel: nypris × ålderskoefficient visas — AI ska välja annan bil om budget inte räcker, aldrig sänka priset för att passa budget |
| Dubblat prisvärdhet-chip | `caFuelChips()` lade till `valueLabel`-chippen dubbelt (duplicerad kodrad) — en av raderna borttagen |
| Modellbyten tog aldrig effekt — properties-override fixad | `application.properties` hade `groq.model=llama-3.3-70b-versatile` hårdkodat (kvar från gammal revert-commit) vilket alltid vinner över `@Value`-defaulten i `GroqService` — alla modellbyten via kod-defaults var verkningslösa och appen anropade den avvecklade llama-modellen. Nu `${GROQ_MODEL:qwen/qwen3.6-27b}` och `${GROQ_CHAT_MODEL:openai/gpt-oss-20b}` — modell kan bytas via Render-miljövariabel utan kodändring |
| `/no_think` → `reasoning_effort` | `/no_think`-prefixet ignorerades av qwen3.6 på Groq — reasoning åt upp tokenbudgeten och JSON-svaret trunkerades ("AI-svaret blev ofullständigt"). Ersatt med Groqs riktiga parameter: `reasoning_effort: none` för qwen (stänger av reasoning helt), `reasoning_effort: low` för gpt-oss (som bara tar low/medium/high). Sätts nu per modell i alla fyra anrop (rekommendation, jämförelse, chat, chatStream) |
| MG4 visade fyra rader utan att säga vilken bil som var vilken | Live-fynd: `engineOptions` för MG4 gav "41.9 kWh (325 km), 52.8 kWh (405 km), 61.7 kWh (405 km), 74.4 kWh (545 km)" — fyra rader som bara skilde sig i kWh, och två med **samma räckvidd** (Long Range och XPOWER, båda 405 km). Namnen fanns hela tiden i `ev_spec`, `verifiedEngineOptions` kastade bara bort dem. Nu hängs trimnamnet på: "52.8 kWh (405 km) · Long Range". Märkesprefixet skalas bort först (ev-database lagrar "MG MG4 XPOWER" men titeln säger bara "MG4", annars blir trimmet "MG XPOWER"), och etiketten sätts **bara när alla rader i en hopslagen grupp bär samma namn** — EX30:s 51 kWh är P3, P5 *och* Single Motor, och ett godtyckligt utvalt namn hade pekat ut fel bil |
| Brasklapp för DC max / AC max | DC- och AC-chipsen visade bara ett kW-tal, vilket läses som "så snabbt laddar bilen alltid". AC-talet missförstås rutinmässigt: taket sitter i bilens ombordladdare, så en 22 kW-laddbox ger ändå 11 kW till en 11 kW-bil — och eftersom AC-laddning nästan alltid sker parkerad över natten avgör det sällan bilvalet. Ny hopfälld ruta ovanför korten (`caRenderChargingNotice`, samma placering som laddhybridsnotisen) förklarar båda måtten, varför AC sällan är avgörande och vad man bör jämföra i stället (DC-effekt, räckvidd, förbrukning per mil). Chipsen och jämförelsetabellens radrubriker har kortversionen som tooltip |
| Nattsynken skrev andra generationens MG4 över den första | ev-database listar sex MG4-poster, alla andra generationen (`Urban Standard Range`, `Premium Long Range`, …). `findMatch` steg 2 kräver bara att DB-namnets ord *finns i* det skrapade namnet, så "MG4 Standard Range" matchade "MG MG4 Urban Standard Range" och fick 41.9 kWh / 325 km i stället för sina 51 kWh / 350 km. **Tvetydigheten låg mellan anropen, inte inuti dem**: varje skrapat namn pekade var för sig entydigt ut en rad, men tre "Long Range"-sidor pekade på *samma* rad och den sist processade vann — vilken bils siffror raden fick berodde på cheatsheetens ordning. Åtgärdat med `claimRow`: första bilen får raden, följande blockeras och loggas som `SCRAPER ALERT`, och antalet kollisioner rapporteras i slutsummeringen. Steg 2 fick samtidigt samma oavgjort-spärr som steg 3 (latent hål: två lika långa DB-namn avgjordes av iterationsordningen). `Standard Range` och `Extended Range` hade bara en anspråkstagare var, så ingen spärr kunde se att det var fel generation — de fick i stället egna andragenerationsrader, se nästa rad |
| Andra generationens MG4 som egna rader | `seedEvSpecExtras` skapar fem gen 2-rader (`MG4 Urban Standard Range` 41,9/325, `Urban Comfort Long Range` 52,8/416, `Urban Premium Long Range` 52,8/405, `Premium Long Range` 61,7/452, `Premium Extended Range` 74,4/545 — avlästa på ev-database.org 2026-08-10, pris lämnas till synken). Namnen är längre än gen 1-namnen, så de vinner i `findMatch` steg 2 och synken uppdaterar rätt bil. `MG4_GEN1` återställer samtidigt de tre gen 1-raderna till 51/350, 64/450 och 77/520 vid varje uppstart — ev-database har inte kvar första generationen, så DataLoader är enda källan och skriver därför över befintligt värde (som `EV6_PRISER`, till skillnad från EX30-korrigeringarna). **Följdändring:** `groupByBattery` slår inte längre ihop rader över en generationsgräns. Gen 1:s 51 kWh/350 km och gen 2:s 52,8 kWh/416 km ligger 3,5 % isär, alltså inom 8 %-toleransen, och blev raden "52.8 kWh (350–416 km)" — andra generationens batteri med första generationens räckvidd. Ingen siffra kan skilja fallen åt (EV6 GT och Long Range delar batteri men går 424 mot 528 km och *ska* slås ihop), så generationen är uppgiven i `EvSpecService.GENERATION`. Den ligger i servicelagret och inte som en kolumn på `ev_spec` eftersom `ddl-auto=validate`: ett nytt entitetsfält hade fällt uppstarten innan någon migrering hann köra. **Kvarstår:** MG4-kortet visar nu sju rader — alla sanna, men en modell med två generationer i samma kort är fortfarande mycket att läsa |
| Årsmodellen i annonsen väljer MG4-generation | Med båda generationerna i tabellen listade kortet alla sju batterierna oavsett vilken bil annonsen gällde — sant men obrukbart, och för en begagnatannons vilseledande (siffror för en bil som inte fanns när annonsbilen tillverkades). `verifiedEngineOptions` behåller nu bara den generation som såldes annonsens årsmodell: "MG4 (2023)" ger 51/64/77 kWh, "MG4 (2025)" ger 41,9/52,8/61,7/74,4. Året läses med `modelYear` ur samma mönster som strippningen redan använde. Filtret slår bara till när det finns något att välja mellan — saknas årsmodell, eller bär ingen av modellens rader en generation (alla modeller utom MG4 i dag), lämnas listan orörd, och väljer året bort precis allt behålls den också (hellre för mycket information än ett tomt fält) |
