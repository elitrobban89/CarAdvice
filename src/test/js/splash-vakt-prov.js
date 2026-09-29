/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
/*
 * Prov för splash-vakten (scripts/splash-vakt.js) — de rena delarna, utan nät.
 *
 * Kör:  node src/test/js/splash-vakt-prov.js
 *
 * Det som provas är det som gav fel svar när vakten skrevs (2026-09-29):
 *   · splasharna skriver å, ä, ö som '\xe4' — utan avkodning hittades inte "Räckvidd",
 *   · en KOMMENTAR som nämner en källa är inte en rad på skärmen,
 *   · Java-versionen kommer som "27", "27.0.1" eller gamla "1.8.0_392",
 *   · en okänd värd i koden ska ge VARNING, inte tystnad — det är hela poängen.
 */
const v = require('../../../scripts/splash-vakt.js');

let fel = 0;
function prov(namn, ok, detalj) {
  console.log((ok ? '  ok    ' : '  FEL   ') + namn + (ok ? '' : '\n          ' + detalj));
  if (!ok) fel++;
}

// --- värdar i källkod ---
const vardar = v.vardarI('String URL = "https://api.groq.com/openai/v1"; // och http://Nobil.no/api');
prov('värdar hittas och görs till gemener', vardar.includes('api.groq.com') && vardar.includes('nobil.no'), JSON.stringify(vardar));

// --- splashens synliga text ---
const js = "/* Chargeprice i en kommentar */\n// Blocket i en radkommentar\nvar t = 'R\\xe4ckvidd \\u00b7 Open Charge Map'; var u = 'https://x.se';";
const text = v.splashText(js);
prov('\\x-escapes avkodas', text.includes('räckvidd'), text);
prov('\\u-escapes avkodas', text.includes('·'), text);
prov('blockkommentar räknas inte', !text.includes('chargeprice'), text);
prov('radkommentar räknas inte', !text.includes('blocket'), text);
prov('en URL i en sträng är ingen kommentar', text.includes('https://x.se'), text);

// --- Java-versionen ---
prov('"27" → 27', v.javaMajor('27') === 27, v.javaMajor('27'));
prov('"27.0.1" → 27', v.javaMajor('27.0.1') === 27, v.javaMajor('27.0.1'));
prov('"1.8.0_392" → 8', v.javaMajor('1.8.0_392') === 8, v.javaMajor('1.8.0_392'));
prov('tomt → null', v.javaMajor('') === null, v.javaMajor(''));

// --- integrationerna ---
const b = v.bedomIntegrationer(['api.groq.com', 'api.chargeprice.app', 'ny-tjanst.example.io', 'tag-k5we.onrender.com'],
                               'fråga groq om laddning');
const niva = namn => (b.find(x => x.text.includes(namn)) || {}).niva;
prov('känd värd som syns → OK', niva('Groq') === 'OK', JSON.stringify(b));
prov('sekundär källa som inte syns → INFO', niva('Chargeprice') === 'INFO', JSON.stringify(b));
prov('OKÄND värd → VARNING (ny integration)', niva('ny-tjanst.example.io') === 'VARNING', JSON.stringify(b));
prov('egen tjänst ignoreras', !b.some(x => x.text.includes('onrender')), JSON.stringify(b));
const b2 = v.bedomIntegrationer(['api.trafikinfo.trafikverket.se'], 'ingenting här');
prov('känd huvudkälla som saknas → VARNING', b2[0] && b2[0].niva === 'VARNING', JSON.stringify(b2));
const b3 = v.bedomIntegrationer(['www.blocket.se'], 'ingenting', { 'Blocket': 'begagnatpriser' });
prov('per-app-undantag gör huvudkällan sekundär', b3[0] && b3[0].niva === 'INFO', JSON.stringify(b3));

console.log(fel ? '\n' + fel + ' prov FÖLL' : '\nAlla prov gröna');
process.exit(fel ? 1 : 0);
