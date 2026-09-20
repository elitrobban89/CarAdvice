/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
/*
 * Prov för prisreglaget — steget, snäppningen och att snabbknapparna verkligen är borta.
 *
 * Kör:  node src/test/js/prisreglage-prov.js
 *
 * Klipper ut caBudgetSteg och caSnapBudget ur den riktiga car-advice-main.js och kör dem.
 * Ingen kopia av logiken finns här.
 *
 * Bakgrund (2026-09-20): snabbknapparna för 100k–300k togs bort — reglaget ensamt är hela
 * valet. Kvar blev två fel som knapparna hade dolt, båda av samma sort: reglaget kunde stå
 * STILLA fast tangenten tryckte.
 *   1. Elementets step låg fast på 5 000 medan snäppningen gick på 10 000 under 300 000 kr.
 *      Ett piltryck nedåt från 200 000 gav 195 000, som rundades tillbaka till 200 000.
 *   2. Vid zongränsen låg närmaste tal BAKÅT: 300 000 + ett steg gav 310 000, som hör till
 *      25 000-zonen och rundades ned till 300 000 igen. Skalan gick inte att köra förbi.
 * Därför bär caSnapBudget numera en RIKTNING, och elementets step följer zonen. Proven nedan
 * går igenom hela skalan, i båda riktningarna, i stället för att kontrollera de tal jag råkar
 * komma på — det var precis de talen som såg friska ut.
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

const scope = {};
new Function("scope", klippUt("caBudgetSteg") + "\n" + klippUt("caSnapBudget") +
  "\nscope.caBudgetSteg = caBudgetSteg; scope.caSnapBudget = caSnapBudget;")(scope);
const caBudgetSteg = scope.caBudgetSteg;
const caSnapBudget = scope.caSnapBudget;

let fel = 0;
function prov(namn, fn) {
  try { fn(); console.log("  ok    " + namn); }
  catch (e) { fel++; console.log("  FEL   " + namn + "\n          " + e.message); }
}

/** Alla tal reglaget kan stå på, från golvet till den utfällda skalans tak. */
function heleSkalan() {
  const v = [];
  let x = 50000;
  while (x <= 1000000) { v.push(x); x += caBudgetSteg(x); }
  return v;
}

// ── Steget följer zonen ──────────────────────────────────────────────────
prov("steget är 10 000 under 300 000, 25 000 upp till 600 000, sedan 50 000", () => {
  const vantat = { 50000: 10000, 299000: 10000, 300000: 10000, 300001: 25000,
                   600000: 25000, 600001: 50000, 1000000: 50000 };
  for (const v in vantat) {
    const fick = caBudgetSteg(Number(v));
    if (fick !== vantat[v]) throw new Error(v + " gav steg " + fick + ", väntat " + vantat[v]);
  }
});

// ── Ingen död zon: ett steg ÅT NÅGOT HÅLL ska alltid flytta reglaget ──────
prov("ett steg nedåt ger alltid ett LÄGRE tal, hela skalan igenom", () => {
  for (const v of heleSkalan()) {
    if (v === 50000) continue;                       // golvet ska inte röra sig nedåt
    const snappat = caSnapBudget(v - caBudgetSteg(v), false);
    if (!(snappat < v)) throw new Error("från " + v + " nedåt gav " + snappat);
  }
});

prov("ett steg uppåt ger alltid ett HÖGRE tal, hela skalan igenom", () => {
  for (const v of heleSkalan()) {
    if (v >= 1000000) continue;                      // taket ska inte röra sig uppåt
    const snappat = caSnapBudget(v + caBudgetSteg(v), true);
    if (!(snappat > v)) throw new Error("från " + v + " uppåt gav " + snappat);
  }
});

prov("zongränsen 300 000 går att köra förbi åt båda hållen", () => {
  const upp = caSnapBudget(300000 + caBudgetSteg(300000), true);
  if (upp !== 325000) throw new Error("uppåt från 300 000 gav " + upp);
  const ner = caSnapBudget(325000 - caBudgetSteg(325000), false);
  if (ner !== 300000) throw new Error("nedåt från 325 000 gav " + ner);
});

prov("golvet håller — inget snäpp hamnar under 50 000", () => {
  for (const v of [0, 1, 40000, 49000, 55000]) {
    if (caSnapBudget(v, false) < 50000) throw new Error(v + " gav " + caSnapBudget(v, false));
  }
});

prov("utan riktning snäpps värdet till NÄRMASTE tal (programmatiska värden)", () => {
  if (caSnapBudget(206000) !== 210000) throw new Error("206 000 gav " + caSnapBudget(206000));
  if (caSnapBudget(203000) !== 200000) throw new Error("203 000 gav " + caSnapBudget(203000));
});

// ── Elementets eget step måste följa samma zoner ─────────────────────────
prov("reglagets step sätts ur caBudgetSteg, inte ur ett fast tal", () => {
  if (kalla.indexOf("s.step = caBudgetSteg(") < 0)
    throw new Error("hittade inget s.step = caBudgetSteg(...) — då är den döda zonen tillbaka");
  if (/s\.step = 5000/.test(kalla))
    throw new Error("det fasta steget 5000 är kvar");
});

// ── Kategoriförvalen måste gå att ställa in för hand ─────────────────────
prov("varje kategoriförval ligger på reglagets rutnät", () => {
  const start = kalla.indexOf("var CA_KAT_FORVAL = {");
  if (start < 0) throw new Error("hittade inte CA_KAT_FORVAL");
  const slut = kalla.indexOf("};", start);
  const block = kalla.slice(start, slut);
  const budgetar = (block.match(/budget: (\d+)/g) || []).map(s => Number(s.split(" ")[1]));
  if (budgetar.length < 5) throw new Error("hittade bara " + budgetar.length + " förval");
  for (const b of budgetar) {
    // Ett förval mellan två steg snäpps tyst till ett annat tal än knappens text lovar —
    // och sedan 2026-09-20 beror det också på DRAGRIKTNINGEN, så samma knapp kunde ge
    // två olika svar. 125 000 (småbil) var ett sådant tal.
    if (caSnapBudget(b) !== b) throw new Error(b + " ligger mellan två steg (snäpps till " + caSnapBudget(b) + ")");
  }
  const hintar = (block.match(/hint: '([^']+)'/g) || []).join(" ");
  for (const b of budgetar) {
    if (hintar.indexOf(Math.round(b / 1000) + "k") < 0)
      throw new Error("ingen hint-text matchar " + b + " — knappen säger något annat än den sätter");
  }
});

// ── Snabbknapparna ska vara borta ur BÅDA HTML-kopiorna och ur koden ─────
prov("inga snabbknappar kvar någonstans", () => {
  const filer = [
    APP,
    path.join(__dirname, "..", "..", "main", "resources", "static", "test.html"),
    path.join(__dirname, "..", "..", "..", "wordpress-snippet.html")
  ];
  for (const f of filer) {
    const t = fs.readFileSync(f, "utf8");
    for (const ord of ["ca-bsnabb", "CA_BUDGET_SNABBVAL", "caBudgetSnabbval", "ca-budget-snabb"]) {
      if (t.indexOf(ord) >= 0) throw new Error(ord + " finns kvar i " + path.basename(f));
    }
  }
});

prov("BÅDA HTML-kopiorna bär fortfarande reglaget", () => {
  const filer = [
    path.join(__dirname, "..", "..", "main", "resources", "static", "test.html"),
    path.join(__dirname, "..", "..", "..", "wordpress-snippet.html")
  ];
  for (const f of filer) {
    const h = fs.readFileSync(f, "utf8");
    // WP-sidan är en MANUELL kopia: glider filerna isär postar den gamla snippeten fortfarande
    // ett annat formulär än det som provas här.
    if (h.indexOf('id="ca-budget-slider"') < 0) throw new Error("reglaget saknas i " + path.basename(f));
    if (h.indexOf('class="ca-slider-ticks"') < 0) throw new Error("skalstrecken saknas i " + path.basename(f));
  }
});

// ── Utseendet och träffytan injiceras från koden, inte ur snippeten ──────
prov("caBudgetReglageCss bär spårets tjocklek, fingermålet och fokusringen", () => {
  const css = klippUt("caBudgetReglageCss");
  const krav = ["#ca-wrap .ca-slider-track", "height:8px", "touch-action:pan-y", ":focus-visible"];
  for (const k of krav) {
    if (css.indexOf(k) < 0) throw new Error(k + " saknas i caBudgetReglageCss");
  }
});

prov("takknappen är kvar — den är enda vägen förbi 600 000 kr", () => {
  if (kalla.indexOf("caBudgetTakRad") < 0) throw new Error("caBudgetTakRad borta");
  if (kalla.indexOf("CA_BUDGET_TAK_HOGT") < 0) throw new Error("det höga taket borta");
});

console.log(fel ? "\n" + fel + " prov FÖLL\n" : "\nAlla prov gröna\n");
process.exit(fel ? 1 : 0);
