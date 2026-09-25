/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
/*
 * Prov för servicekostnaden i 5-årstotalen (TCO).
 *
 * Kör:  node src/test/js/tco-service-prov.js
 *
 * Klipper ut caServiceCost med tabeller och hjälpfunktioner ur den riktiga car-advice-main.js
 * och kör dem. Ingen kopia av logiken finns här.
 *
 * Bakgrund (2026-09-25): servicen var 3 000 / 6 000 / 8 000 kr per år för el / laddhybrid /
 * övrigt, utan källa och utan märke. Nu: drivlinesnitt år 1–3 och år 4–5 ur Vi Bilägare, KVD
 * och Moveabout, en märkesfaktor ur Vi Bilägares tabell för 19 märken, och milskalning.
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

/** En var-sats i kolumn 0, fram till första raden som slutar på ";". */
function klippUtVar(namn) {
  const start = kalla.indexOf("var " + namn + " = ");
  if (start < 0) throw new Error("hittade inte " + namn);
  const rader = kalla.slice(start).split("\n");
  for (let i = 0; i < rader.length; i++) if (rader[i].trimEnd().endsWith(";")) return rader.slice(0, i + 1).join("\n");
  throw new Error("hittade inte slutet på " + namn);
}

const kod = [
  klippUtVar("CA_SERVICE_AR_1_3"), klippUtVar("CA_SERVICE_AR_4_5"), klippUtVar("CA_SLITDELAR_AR_4_5"),
  klippUtVar("CA_SERVICE_MARKE"), klippUtVar("CA_SERVICE_MARKE_SNITT"), klippUtVar("CA_SERVICE_MARKE_ALIAS"),
  klippUt("caServiceMarkesfaktor"), klippUt("caServiceDrivlina"), klippUt("caServiceCost"),
  "return { caServiceCost, caServiceMarkesfaktor, CA_SERVICE_MARKE, CA_SERVICE_MARKE_SNITT };"
].join("\n");
const { caServiceCost, caServiceMarkesfaktor, CA_SERVICE_MARKE, CA_SERVICE_MARKE_SNITT } = new Function(kod)();

let fel = 0;
function prov(namn, fn) {
  try { fn(); console.log("  ok    " + namn); }
  catch (e) { fel++; console.log("  FEL   " + namn + "\n          " + e.message); }
}
function lika(fatt, vant, vad) {
  if (Math.abs(fatt - vant) > 0.5) throw new Error((vad || "") + " fick " + fatt + ", väntade " + vant);
}

const bensin = (title) => ({ title: title, fuelSpec: { fuel: "bensin", consumptionLiterPerMil: 0.6 } });
const elbil  = (title) => ({ title: title, evSpec: { carType: "BEV", batteryKwh: 77, wltpKm: 500 } });
const phev   = (title) => ({ title: title, evSpec: { carType: "PHEV" } });
const hybrid = (title) => ({ title: title, fuelSpec: { fuel: "Hybrid", consumptionLiterPerMil: 0.45 } });

console.log("TCO-servicen");

prov("bensinbil av okänt märke: 3 x 4 000 + 2 x (4 000 + 2 500) = 25 000", () => {
  lika(caServiceCost(bensin("MG ZS 1.5"), 15000), 25000);
});

prov("elbil av okänt märke: 3 x 2 000 + 2 x (1 700 + 800) = 11 000", () => {
  lika(caServiceCost(elbil("Polestar 2"), 15000), 11000);
});

prov("självladdande hybrid räknas som hybrid, inte som bensinbil", () => {
  // Förr fick den bensinbilens 8 000 kr/år; nu hybridsnittet: 3 x 5 800 + 2 x (4 100 + 2 500)
  lika(caServiceCost(hybrid("Lynk & Co 01"), 15000), 30600);
});

prov("laddhybrid via evSpec ger samma som hybrid", () => {
  lika(caServiceCost(phev("Lynk & Co 01"), 15000), caServiceCost(hybrid("Lynk & Co 01"), 15000));
});

prov("märkesfaktorn är roten ur kvoten mot snittet — Toyota 1,19, Audi 0,83", () => {
  lika(caServiceMarkesfaktor("Toyota Corolla") * 100, Math.sqrt(14356 / 10176) * 100);
  lika(caServiceMarkesfaktor("Audi A4") * 100, Math.sqrt(6960 / 10176) * 100);
  const toyota = caServiceMarkesfaktor("Toyota Corolla"), audi = caServiceMarkesfaktor("Audi A4");
  if (toyota > 1.25 || audi < 0.75) throw new Error("faktorn gick utanför ±25 %: " + toyota + " / " + audi);
});

prov("snittet i koden stämmer mot tabellen den står bredvid", () => {
  const v = Object.values(CA_SERVICE_MARKE);
  if (v.length !== 19) throw new Error("tabellen har " + v.length + " märken, källan har 19");
  lika(v.reduce((a, b) => a + b, 0) / v.length, CA_SERVICE_MARKE_SNITT, "snitt");
});

prov("slitdelarna följer inte märket — bromsjobbet kostar detsamma", () => {
  const f = caServiceMarkesfaktor("Toyota Aygo X");
  lika(caServiceCost(bensin("Toyota Aygo X"), 15000), 3 * 4000 * f + 2 * (4000 * f + 2500));
});

prov("VW, Škoda, Citroën och Mercedes-Benz hittar sina rader", () => {
  lika(caServiceMarkesfaktor("VW Golf"), caServiceMarkesfaktor("Volkswagen Golf"));
  lika(caServiceMarkesfaktor("Škoda Enyaq"), caServiceMarkesfaktor("Skoda Enyaq"));
  lika(caServiceMarkesfaktor("Citroën C4"), caServiceMarkesfaktor("Citroen C4"));
  lika(caServiceMarkesfaktor("Mercedes-Benz GLC"), caServiceMarkesfaktor("Mercedes GLC"));
  if (caServiceMarkesfaktor("VW Golf") === 1) throw new Error("VW föll till schablonen");
});

prov("märke utan källa och tom rubrik ger faktor 1", () => {
  lika(caServiceMarkesfaktor("BYD Seal"), 1);
  lika(caServiceMarkesfaktor(""), 1);
  lika(caServiceMarkesfaktor(undefined), 1);
});

prov("under 2 000 mil/år styr tiden, över det skalar kostnaden", () => {
  lika(caServiceCost(bensin("MG ZS"), 10000), caServiceCost(bensin("MG ZS"), 15000), "1 000 mil");
  lika(caServiceCost(bensin("MG ZS"), 40000), 2 * caServiceCost(bensin("MG ZS"), 20000), "4 000 mil");
});

console.log(fel ? "\n" + fel + " prov FALLERADE" : "\nalla prov gröna");
process.exit(fel ? 1 : 0);
