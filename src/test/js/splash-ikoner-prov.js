/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
/*
 * Prov för de rörliga splash-ikonerna — att VARJE rad har en rörelse, och att rörelsen
 * faktiskt rör sig.
 *
 * Kör:  node src/test/js/splash-ikoner-prov.js
 *
 * Läser de riktiga filerna CarAdvice serverar (ingen kopia av CSS:en bor här):
 *   · car-advice-splash.js  — bilrådgivningens splash
 *   · ev-splash.js          — Elbilsladdnings splash (masterkopian bor i det repot,
 *                             drift-prov.js där håller dem identiska)
 *   · bensinkostnad.js      — Bilresas splash ligger sist i den filen
 *
 * Bakgrund (2026-09-21): ikonerna var stillbilder bredvid rader som tickade in. När alla
 * tio fick egen rörelse föll två av dem ändå i mätningen, och båda av samma skäl:
 *
 *   · blixten kördes med steps(1,end). Den interpolerar INTE mellan keyframes — ikonen
 *     hoppade mellan lägen och stod stilla 85 % av varvet. "animation-name är satt" var
 *     grönt hela tiden; det som föll var mätningen av om transformen ändrade sig.
 *   · myntet låg på rotateY(0) genom 55 % av varvet innan det vände sig.
 *
 * Därför provar det här inte att en egenskap är satt, utan formen på keyframesen: en
 * rörelse som står stilla över halva varvet är inte en rörelse. Den fullständiga
 * mätningen (beräknad transform i riktig tid över CDP) görs utanför det här provet —
 * se anteckningarna om harnessen. Det här är vakten som fångar en återgång i koden.
 */
const fs = require('path') && require('fs');
const path = require('path');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static');

const FILER = [
  { fil: 'car-advice-splash.js', rad: 'ca-sp', ic: 'ca-ic-', undantag: ['groq'] },
  { fil: 'ev-splash.js',         rad: 'ev-sp', ic: 'ev-ic-', undantag: [] },
  { fil: 'bensinkostnad.js',     rad: 'bcsp',  ic: 'bcsp-i-', undantag: [] }
];

// Rörelser som MED FLIT hackar i steg i stället för att glida. En tickande klocka ska
// ticka. Listan finns för att undantaget ska vara skrivet, inte underförstått.
const FAR_HACKA = ['klocka'];

let fel = 0;
const ok   = (t) => console.log('  ok    ' + t);
const fall = (t, d) => { console.log('  FEL   ' + t); if (d) console.log('        ' + d); fel++; };

for (const f of FILER) {
  console.log('\n' + f.fil);
  const kod = fs.readFileSync(path.join(STATIC, f.fil), 'utf8');

  // --- ROWS: varje rad ska ha en rörelse ---
  const start = kod.indexOf('var ROWS = [');
  const rows = kod.slice(start, kod.indexOf('];', start));
  const antalRader = (rows.match(/\{ ic:/g) || []).length;
  const rorelser = [...rows.matchAll(/an: '([a-z]+)'/g)].map((m) => m[1]);
  const utanRorelse = antalRader - rorelser.length - f.undantag.length;
  if (antalRader === 0) fall('hittade ROWS', 'ingen ROWS-array i filen');
  else if (utanRorelse === 0) ok('alla ' + antalRader + ' rader har en rörelse');
  else fall(utanRorelse + ' av ' + antalRader + ' rader saknar rörelse',
            'en ny rad utan an: blir en stillbild bredvid nio som rör sig');

  // --- Vilolaget: ikonerna rör sig redan innan raden tänds ---
  if (/animation:[a-z-]*i?[-]?vilar /.test(kod) || kod.indexOf('-vilar ') > 0) ok('vilolaget andas medan raden laddar');
  else fall('vilolaget saknas', 'ikonerna står stilla tills raden tänds');

  // --- Glöden: drop-shadow, aldrig text-shadow ---
  // wp-emoji byter ut emojin mot en <img> på WP-sidan, och text-shadow biter inte på en
  // bild. En glöd i text-shadow syns i harnessen och försvinner i drift.
  const ikonReglerMedTextShadow = [...kod.matchAll(new RegExp("'\\." + f.rad + "-row\\.done[^']*text-shadow", 'g'))];
  if (ikonReglerMedTextShadow.length === 0) ok('glöden ligger i drop-shadow, inte text-shadow');
  else fall('text-shadow i en tänd ikonregel', 'osynlig i drift där wp-emoji gjort emojin till <img>');

  for (const namn of rorelser) {
    // Fram till '}}' — slutet på HELA keyframes-blocket, inte första stoppet. CSS:en är
    // skriven som flera JS-strängar i rad, så ett block slutar ofta två rader längre ner;
    // ett mönster som stannade vid första '}' läste bara första stoppet och rapporterade
    // varenda flerradig rörelse som frusen. Provet mätte då sin egen parser, inte CSS:en.
    const reg = new RegExp('@keyframes ' + f.ic + namn + '\\{([^]*?)\\}\\}\',', 'm');
    const traff = kod.match(reg);
    if (!traff) { fall(namn + ': keyframes saknas', 'rörelsen är namngiven men aldrig definierad'); continue; }

    const deklaration = kod.indexOf("'." + f.rad + '-row.done .' + f.ic + namn + '{') >= 0
      || kod.indexOf("'." + f.rad + '-row.done .' + f.rad + '-ic.' + f.ic + namn + '{') >= 0;
    if (!deklaration) { fall(namn + ': ingen regel tänder rörelsen', '@keyframes utan animation: är död kod'); continue; }

    // Hackar den i steg fast den inte får?
    const stegar = new RegExp("\\." + f.ic + namn + "\\{[^']*animation:[^']*steps\\(").test(kod)
      || new RegExp("\\." + f.ic + namn + "\\{filter:[^']*animation:[^']*steps\\(").test(kod);
    if (stegar && FAR_HACKA.indexOf(namn) < 0) {
      fall(namn + ': kör med steps()', 'steps() interpolerar inte — ikonen står stilla mellan keyframes');
      continue;
    }

    // Står den stilla över halva varvet? Läs procentstoppen och se efter ett glapp där
    // transformen är oförändrad. Det var precis så blixten och myntet föll i mätningen.
    // Skarvarna mellan JS-strängarna bort, annars bryts ett stopp mitt itu.
    const kropp = traff[1].replace(/',[\s]*'/g, '');
    const stopp = [...kropp.matchAll(/(\d+)%(?:,(\d+)%)?\{transform:([^;}]*)/g)]
      .flatMap((m) => (m[2] ? [[+m[1], m[3]], [+m[2], m[3]]] : [[+m[1], m[3]]]))
      .sort((a, b) => a[0] - b[0]);
    let storstaGlapp = 0, vid = 0;
    for (let i = 1; i < stopp.length; i++) {
      if (stopp[i][1].trim() === stopp[i - 1][1].trim()) {
        const glapp = stopp[i][0] - stopp[i - 1][0];
        if (glapp > storstaGlapp) { storstaGlapp = glapp; vid = stopp[i - 1][0]; }
      }
    }
    // 35 %, inte 50: filmklappan stod still 74 % och föll direkt, men pusselbiten i
    // VaderKlader stod still 40 % och hade sluppit igenom en gräns satt vid hälften.
    // En tredjedel av varvet frusen syns — och gränsen ska fånga den sortens rörelse,
    // inte bara den värsta.
    if (storstaGlapp > 35 && FAR_HACKA.indexOf(namn) < 0) {
      fall(namn + ': stillastående ' + storstaGlapp + ' % av varvet (från ' + vid + ' %)',
           'samma transform i två stopp i rad — ikonen ser frusen ut mellan accenterna');
    } else {
      ok(namn + ' rör sig genom hela varvet');
    }
  }
}

console.log(fel === 0 ? '\nAlla prov gröna' : '\n' + fel + ' prov FÖLL');
process.exit(fel === 0 ? 0 : 1);
