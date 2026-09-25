# Morgonfix-logg

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
