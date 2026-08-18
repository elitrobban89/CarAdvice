/*
 * Prov för chattbubblans markdown — särskilt pipe-tabellerna.
 *
 * Kör:  node src/test/js/chat-markdown-prov.js
 *
 * Klipper ut caChatTables och caChatMarkdown ur den riktiga car-advice-chat.js och kör dem.
 * Ingen kopia av logiken finns här.
 *
 * Tabellstödet tillkom 2026-08-18 för värdetappslistan: chatten har hela fyndtabellen i sin
 * kontext och ombeds svara med en markdown-tabell. Utan renderingen blev svaret en radsallad
 * av pipe-tecken — alltså en funktion som ser ut att fungera men producerar oläsligt resultat.
 */
const fs = require("fs");
const path = require("path");

const APP = path.join(__dirname, "..", "..", "main", "resources", "static", "car-advice-chat.js");
// CRLF strippas: filen har Windows-radslut, och radjamforelsen nedan matchar annars aldrig.
const kalla = fs.readFileSync(APP, "utf8").split(String.fromCharCode(13)).join("");

function klippUt(namn) {
  const start = kalla.indexOf("  function " + namn + "(");
  if (start < 0) throw new Error("hittade inte " + namn + " i car-advice-chat.js");
  const rader = kalla.slice(start).split("\n");
  for (let i = 1; i < rader.length; i++) if (rader[i] === "  }") return rader.slice(0, i + 1).join("\n");
  throw new Error("hittade inte slutet på " + namn);
}

const F = new Function(klippUt("caChatTables") + "\n" + klippUt("caChatMarkdown")
  + "\n return { caChatTables, caChatMarkdown };")();

let fel = 0;
function prov(namn, fn) {
  try { fn(); console.log("  ok    " + namn); }
  catch (e) { fel++; console.log("  FEL   " + namn + "\n          " + e.message); }
}
function innehaller(html, bit) {
  if (!html.includes(bit)) throw new Error("saknar " + JSON.stringify(bit) + "\n          i: " + html.slice(0, 220));
}

console.log("\nChattbubblans markdown ur car-advice-chat.js\n");

const TABELL = [
  "Här är de som tappat mest:",
  "",
  "| Modell | Kvar av nypris | Median idag |",
  "|---|---|---|",
  "| Audi e-tron 55 quattro | 38 % | 365 000 kr |",
  "| Nissan Leaf e+ 62 kWh | 41 % | 189 900 kr |",
  "",
  "Källa: Kvdbil och Blocket."
].join("\n");

prov("pipe-tabell blir en riktig table med thead och tbody", () => {
  const h = F.caChatMarkdown(TABELL);
  innehaller(h, '<table class="ca-chat-table">');
  innehaller(h, "<thead>");
  innehaller(h, "<th>Modell</th>");
  innehaller(h, "<td>Audi e-tron 55 quattro</td>");
  innehaller(h, "<td>38 %</td>");
});

prov("avgränsarraden blir INGEN datarad", () => {
  const h = F.caChatMarkdown(TABELL);
  if (h.includes("<td>---</td>")) throw new Error("avgränsaren renderades som en rad");
});

prov("tabellen scrollar i eget spann — bubblan är smal", () => {
  innehaller(F.caChatMarkdown(TABELL), 'class="ca-chat-table-wrap"');
});

prov("inga <br> inuti tabellen", () => {
  const h = F.caChatMarkdown(TABELL);
  const tab = h.slice(h.indexOf("<table"), h.indexOf("</table>"));
  if (tab.includes("<br>")) throw new Error("br hamnade inne i tabellen: " + tab.slice(0, 160));
});

prov("texten runt tabellen finns kvar", () => {
  const h = F.caChatMarkdown(TABELL);
  innehaller(h, "Här är de som tappat mest:");
  innehaller(h, "Källa: Kvdbil och Blocket.");
});

prov("fetstil och listor fungerar som förut", () => {
  const h = F.caChatMarkdown("**Volvo EX30** är bra\n- punkt ett\n- punkt två");
  innehaller(h, "<strong>Volvo EX30</strong>");
  innehaller(h, "<li>punkt ett</li>");
  innehaller(h, "<ul>");
});

prov("HTML i modellens svar escapas fortfarande", () => {
  const h = F.caChatMarkdown("<script>alert(1)</script>");
  if (h.includes("<script>")) throw new Error("HTML escapades inte — XSS-väg");
  innehaller(h, "&lt;script&gt;");
});

prov("en ensam pipe-rad utan avgränsare blir INTE en tabell", () => {
  // Modellen skriver ibland "a | b" i löptext. Utan avgränsarrad är det ingen tabell.
  const h = F.caChatMarkdown("| bara en rad |");
  if (h.includes("<table")) throw new Error("byggde tabell utan avgränsarrad");
});

prov("text utan tabell rör sig inte", () => {
  const h = F.caChatMarkdown("Rad ett\nRad två");
  innehaller(h, "Rad ett<br>Rad två");
});

console.log(fel === 0 ? "\nAlla prov gröna\n" : "\n" + fel + " prov föll\n");
process.exit(fel === 0 ? 0 : 1);
