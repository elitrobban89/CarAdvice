/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
/*
 * Prov för laddhybrid som DRIVMEDEL i formuläret.
 *
 * Kör:  node src/test/js/laddhybrid-prov.js
 *
 * Klipper ut caBudgetLevelsFor och caUpdateFuelVisibility ur den riktiga car-advice-main.js
 * och kör dem. Ingen kopia av logiken finns här.
 *
 * Bakgrund (2026-09-17): kategorin var ETT fält, så "laddhybrid-SUV" gick bara att uttrycka
 * via API:t — formuläret tvingade fram antingen suv ELLER laddhybrid. Backenden klarade
 * kombinationen hela tiden (fuelIntent ger phev=true på drivmedelssträngen, requirePhevCars
 * kopplas in, adFilterFor ger "Plug-in Bensin" och suvModelsLine väljer PHEV-golven), så det
 * enda som saknades var valet i rutan. Med valet på plats följde tre följdfel som proven här
 * låser fast.
 */
const fs = require("fs");
const path = require("path");

const APP = path.join(__dirname, "..", "..", "main", "resources", "static", "car-advice-main.js");
const kalla = fs.readFileSync(APP, "utf8").split(String.fromCharCode(13)).join("");

/** Funktioner i kolumn 0 slutar på första raden som är exakt "}". */
function klippUt(namn) {
  const start = kalla.indexOf("function " + namn + "(");
  if (start < 0) throw new Error("hittade inte " + namn);
  const rader = kalla.slice(start).split("\n");
  for (let i = 1; i < rader.length; i++) if (rader[i] === "}") return rader.slice(0, i + 1).join("\n");
  throw new Error("hittade inte slutet på " + namn);
}

/** Ett objekt i kolumn 0, hämtat ur filen så innehållet är det riktiga. */
function klippUtObjekt(namn) {
  const start = kalla.indexOf("var " + namn + " = {");
  if (start < 0) throw new Error("hittade inte " + namn);
  const rader = kalla.slice(start).split("\n");
  for (let i = 1; i < rader.length; i++) if (rader[i] === "};") return rader.slice(0, i + 1).join("\n");
  throw new Error("hittade inte slutet på " + namn);
}

/** CA_BUDGET_LEVELS-objektet, hämtat ur filen så nycklarna är de riktiga. */
function klippUtNivaer() {
  const start = kalla.indexOf("var CA_BUDGET_LEVELS = {");
  if (start < 0) throw new Error("hittade inte CA_BUDGET_LEVELS");
  const rader = kalla.slice(start).split("\n");
  for (let i = 1; i < rader.length; i++) if (rader[i] === "};") return rader.slice(0, i + 1).join("\n");
  throw new Error("hittade inte slutet på CA_BUDGET_LEVELS");
}

let fel = 0;
function prov(namn, fn) {
  try { fn(); console.log("  ok    " + namn); }
  catch (e) { fel++; console.log("  FEL   " + namn + "\n          " + e.message); }
}

/** Minimal DOM: bara de element funktionerna faktiskt rör. */
function miljo(kategori, drivmedel, laddbox) {
  const el = {};
  function nytt(id) {
    const e = { id: id, value: "", style: { display: "" }, dataset: {} };
    e.closest = function () { return e; };
    el[id] = e;
    return e;
  }
  ["ca-category", "ca-fuel", "ca-fuel-field", "ca-transmission", "ca-transmission-field",
   "ca-charger"].forEach(nytt);
  el["ca-category"].value = kategori;
  el["ca-fuel"].value = drivmedel;
  el["ca-charger"].value = laddbox === undefined ? "false" : laddbox;
  el["ca-transmission"].value = "manuell";   // kvarglömt val från ett tidigare bensinsök

  const scope = {
    document: { getElementById: function (id) { return el[id] || null; } },
    caForvalPaus: true,          // rör inte de smarta förvalen i provet
    caCanonCat: function (v) { return v; },
    caUpdateMaxAgeVisibility: function () {},
    caRenderEvBudgetHint: function () {},
    caRenderCargoLevels: function () {},
    // Stubbar för det som ligger utanför det provet gäller. caVaxelladeForval är DÄREMOT
    // den riktiga: den kan sätta tillbaka ett växellådevärde, och att den avstår när fältet
    // är gömt är precis det som gör nollställningen ovan hållbar.
    caAlderForval: function () {},
    caSynkaChips: function () {},
    caDrivmedelsrad: function () {}
  };
  return { el: el, scope: scope };
}

function kor(namn, kod, scope, args) {
  const nycklar = Object.keys(scope);
  const f = new Function(...nycklar, kod + "\nreturn " + namn + ";")(...nycklar.map(k => scope[k]));
  return f.apply(null, args || []);
}

const NIVAER = klippUtObjekt("CA_OVER_CATEGORY") + "\n" + klippUtNivaer();
const BUDGET = klippUt("caBudgetLevelsFor");
const SYNLIGHET = klippUt("caUpdateFuelVisibility") + "\n" + klippUt("caVaxelladeForval");

console.log("\nLaddhybrid som drivmedel i formuläret\n");

// ── Budgetnivåerna ──────────────────────────────────────────────────────
// Tabellen byggs EN gång och skickas in i varje anrop. Byggdes den om per anrop fick varje
// svar ett nytt objekt, och då är `a !== b` sant oavsett vad koden gjorde — ett prov som
// alltid är grönt. Nu är identiteten stabil, så jämförelsen betyder något.
const TABELL = new Function(NIVAER + "\nreturn CA_BUDGET_LEVELS;")();

function nivaerFor(kategori, drivmedel) {
  const m = miljo(kategori, drivmedel);
  m.scope.CA_BUDGET_LEVELS = TABELL;
  return kor("caBudgetLevelsFor", BUDGET, m.scope, [kategori]);
}

prov("laddhybrid-SUV får laddhybridens nivåer, inte bensin-SUV:ens", () => {
  // Samma fel som suvModelsLine hade i backenden: laddhybriden är den DYRASTE varianten av
  // samma kaross, så bensinnivåerna ligger konsekvent för lågt.
  const phev = nivaerFor("suv", "laddhybrid");
  const bensin = nivaerFor("suv", "bensin");
  if (!phev) throw new Error("inga nivåer alls");
  if (phev === bensin) throw new Error("fick bensin-SUV:ens nivåer");
  if (phev !== nivaerFor("laddhybrid", "spelar ingen roll"))
    throw new Error("fick inte laddhybridens tabell");
});

prov("el som drivmedel styr fortfarande om till elbilsnivåerna", () => {
  const el = nivaerFor("suv", "el");
  if (el === nivaerFor("suv", "bensin")) throw new Error("elvägen bröts av ändringen");
});

prov("bensin-SUV rörs inte", () => {
  const b = nivaerFor("suv", "bensin");
  if (!b || b === nivaerFor("elbil", "bensin")) throw new Error("fel tabell för bensin-SUV");
});

prov("kategorin elbil/laddhybrid är redan drivmedelsbestämd och rörs inte av rutan", () => {
  const a = nivaerFor("laddhybrid", "el");
  const b = nivaerFor("laddhybrid", "spelar ingen roll");
  if (a !== b) throw new Error("drivmedlet ändrade en redan bestämd kategori");
  const c = nivaerFor("elbil", "laddhybrid");
  const d = nivaerFor("elbil", "spelar ingen roll");
  if (c !== d) throw new Error("drivmedlet ändrade elbilskategorin");
});

// ── Synligheten ─────────────────────────────────────────────────────────
function synlighet(kategori, drivmedel) {
  const m = miljo(kategori, drivmedel);
  kor("caUpdateFuelVisibility", SYNLIGHET, m.scope, []);
  return m.el;
}

prov("växellådan göms OCH nollställs för laddhybrid", () => {
  // Skadligt sedan requireTransmissionCars (2026-09-17): ett kvarglömt "Manuell" hade fällt
  // varje riktig laddhybrid och bränt ett omförsök, eftersom kortets gearbox säger Automat.
  const el = synlighet("suv", "laddhybrid");
  if (el["ca-transmission-field"].style.display !== "none") throw new Error("växellådsrutan syns");
  if (el["ca-transmission"].value !== "spelar ingen roll")
    throw new Error("värdet står kvar: " + el["ca-transmission"].value);
});

prov("laddboxfrågan STÅR KVAR för laddhybrid — den laddas ju", () => {
  const el = synlighet("suv", "laddhybrid");
  if (el["ca-charger"].style.display === "none") throw new Error("laddboxfrågan gömdes");
});

prov("drivmedelsrutan syns för suv och göms för elbil/laddhybrid som KATEGORI", () => {
  if (synlighet("suv", "laddhybrid")["ca-fuel-field"].style.display === "none")
    throw new Error("drivmedelsrutan gömdes på ett SUV-sök");
  if (synlighet("elbil", "spelar ingen roll")["ca-fuel-field"].style.display !== "none")
    throw new Error("drivmedelsrutan syns på elbilskategorin");
});

prov("bensin-SUV behåller växellådsvalet", () => {
  const el = synlighet("suv", "bensin");
  if (el["ca-transmission-field"].style.display === "none")
    throw new Error("växellådan gömdes för ett bensinsök");
  if (el["ca-transmission"].value !== "manuell") throw new Error("växellådan nollställdes i onödan");
});

// ── Formuläret bär verkligen valet ──────────────────────────────────────
prov("BÅDA HTML-kopiorna har laddhybridsalternativet", () => {
  const filer = [
    path.join(__dirname, "..", "..", "main", "resources", "static", "test.html"),
    path.join(__dirname, "..", "..", "..", "wordpress-snippet.html")
  ];
  for (const f of filer) {
    const h = fs.readFileSync(f, "utf8");
    if (h.indexOf('<option value="laddhybrid">') < 0)
      throw new Error("saknas i " + path.basename(f));
    // WP-sidan är en MANUELL kopia: glider filerna isär postar den gamla snippeten fortfarande
    // det gamla värdet, precis som ekonomibil gjorde 2026-08-22.
    if (h.indexOf('<option value="hybrid">Hybrid (ej laddhybrid)</option>') < 0)
      throw new Error("hybridraden ändrad i " + path.basename(f));
  }
});

console.log(fel ? "\n" + fel + " prov FÖLL\n" : "\nAlla prov gröna\n");
process.exit(fel ? 1 : 0);
