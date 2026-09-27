# Nattrutinens ramar

Den här filen är gränserna för allt nattrutinen gör. **Rutinen får aldrig ändra den** - bara
användaren. Auto-merge-workflowen mergar ingen PR som rör filen. Allt i `docs/nattrutin.md` gäller
INOM de här ramarna; säger den något som strider mot en rad här, gäller raden här.

## Det som aldrig får hända
1. Ingen commit direkt på master, aldrig `push --force`, aldrig nycklar eller hemligheter i en fil.
2. Rutinen ändrar aldrig: `.github/`, `.claude/`, `pom.xml`, `mvnw`, `.mvn/`, `Dockerfile`,
   `render.yaml`, `scripts/` eller den här filen. Behövs en ändring där: skriv förslaget i rapporten.
3. Inga skrivningar direkt mot produktionens admin-API (POST/PATCH/DELETE). Alla databasbeslut går
   genom `src/main/resources/morgonfix/atgarder.json`, som appen utför efter merge.

## Databasbesluten (atgarder.json)
4. Typerna är `slapp`, `parkera`, `dolj` och `radera` - inga andra.
5. Högst 12 slapp/parkera/dolj och högst 20 `radera` per fil. Fler avvisar appen hela filen.
6. `radera` gäller bara en rad som varit DOLD i minst 7 dygn. Rutinen döljer först och raderar
   tidigast en vecka senare - det är användarens tid att se och ångra. Varje raderad rad kopieras
   till `insight_raderad` och kan återställas.
7. Varje beslut har ett skäl som namnger regeln och källan.

## Koden
8. Högst 5 ändrade kodfiler per natt (loggboken, atgarder.json, baslinje.json och
   docs/nattrutin.md räknas inte).
9. Varje kodfix har ett prov som är rött före och grönt efter. Bygget och hela testsviten ska vara
   gröna före commit.
10. Ingen ny funktionalitet, inga uppgraderade beroenden, inga ändringar i frontend eller
    `wordpress-snippet.html`.

## Rutinens egna regler (docs/nattrutin.md)
11. Rutinen FÅR förbättra `docs/nattrutin.md`: skärpa en regel som läckte, förtydliga en som
    misstolkades, lägga till en mätfälla den själv gick i, korta bort avgjord historik.
12. Högst 40 ändrade rader i den filen per natt. Auto-merge stoppar större ändringar.
13. En regeländring får aldrig: ta bort eller försvaga ett larm, höja ett tak, vidga det rutinen får
    ändra, eller strida mot en rad i den här filen. Den får bara göra rutinen noggrannare.
14. Varje regeländring står i loggboken med vad som hände i natt som motiverar den.

## Laddtipsen (elbilsassistentens karusell)
16. Rutinen får lägga till tips i `src/main/resources/morgonfix/laddtips.json` - det är data, inte
    frontend. Ren text utan HTML, varje tips med källa och de insikts-id:n det vilar på. Högst
    3 nya per natt. Tipsen visas publikt, så ett tips med en siffra rutinen inte kan belägga
    skrivs inte.

17. Samma fil bär marknadsfakta (typ "marknad"): rutinen får ersätta en inaktuell rad med en ny
    siffra ur en namngiven källa, men aldrig lägga till en siffra den inte kan belägga.
18. Rutinen får uppdatera `src/main/resources/morgonfix/laddpriser.json` på måndagar: bara ur
    nätverkets egen prissida, högst 10 ändringar per vecka. Mottagaren avvisar priser utanför
    1-15 kr/kWh och hopp på mer än 60 %.

## Baslinjen
15. Vid LARM skrivs ingen ny baslinje. Ett tal som faller är alltid en avvikelse, även mot en färsk
    baslinje.
