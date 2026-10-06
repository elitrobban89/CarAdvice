/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
// Splash-vakten: stämmer uppgifterna på uppstartsskärmarna i ALLA webbprojekten?
//
//   node scripts/splash-vakt.js            → rapport i markdown på stdout
//   node scripts/splash-vakt.js --json     → samma sak som JSON
//
// Nattrutinen kör den varje natt (docs/nattrutin.md, avsnitt 14). Bara GET — mot apparna och
// mot GitHub. Exitkod 2 vid LARM, annars 0 (VARNING och INFO fäller inte körningen).
//
// Splasharna läser redan sina siffror live (Java-version ur JVM:en, PostgreSQL ur anslutningen,
// trafik och priser ur samma endpoints som appen). Det som kunde glida isär utan att någon såg
// det var därför inte TALEN utan KOPPLINGEN mellan kod och skärm:
//   1. Java      — kör tjänsten den Java som pom.xml säger? (en uppgradering som inte deployats)
//   2. Databas   — får splashen ett riktigt PostgreSQL-svar, eller tomt / H2 i drift?
//   3. Deploy    — är det toppen av master/main som kör? (en autodeploy som fastnat)
//   4. Siffror   — är live-talen på plats, eller har en källa slutat svara?
//   5. Integrationer — varje extern API-värd i koden ska synas i splashen. En NY värd som
//      ingen skrivit en rad för flaggas, så en ny integration aldrig glöms bort på skärmen.
//
// Lägger man till en integration: skriv splashraden OCH en rad i INTEGRATIONER nedan.
// Vakten säger till om någon av dem saknas.

const GITHUB = 'elitrobban89';
const TIMEOUT = 150000; // Render-tjänsterna kan sova — väckningen är uppmätt till ~2 min

/** Webbprojekten med splash. `system` = JSON med java/springBoot/db/deployCommit. */
const APPAR = [
  { namn: 'MiniPrisTåget', repo: 'Tag', gren: 'master', rot: 'backend/',
    system: 'https://tag-k5we.onrender.com/api/splash', db: false,
    splash: ['backend/src/main/resources/static/mpt-splash.js'],
    siffror: d => [['stationer', d.stationer > 0], ['tåg i trafik', d.tagITrafik > 0],
                   ['avgångar kommande timmen', d.avgangarNastaTimme > 0], ['punktlighet', d.punktlighet != null]] },
  { namn: 'Bankomat 2.0', repo: 'Bankomat2.0', gren: 'master', rot: 'web/',
    html: 'https://bankomat2-0.onrender.com/bankomat-2-0/', db: true,
    splash: ['web/src/main/resources/static/splash.js'],
    siffror: d => [['konton', d.konton !== undefined], ['kunder', d.kunder !== undefined], ['saldo', d.saldo !== undefined]] },
  { namn: 'Elbilsladdning', repo: 'Elbilsladdning', gren: 'main', rot: 'backend/',
    system: 'https://elbilsladdning.onrender.com/api/system', db: true,
    splash: ['backend/src/main/resources/static/ev-splash.js'],
    // Källor bakom delar som redan finns — i CarAdvice är samma värdar egna rader.
    sekundar: { 'Blocket': 'begagnatpriser bakom Bilpriser-raden', 'YouTube': 'Vroom-topplistan i karusellen' },
    extra: { url: 'https://elbilsladdning.onrender.com/api/cars', namn: 'elbilar', ok: j => Array.isArray(j) && j.length > 0 } },
  { namn: 'CarAdvice', repo: 'CarAdvice', gren: 'master', rot: '',
    system: 'https://caradvice.onrender.com/api/system', db: true,
    splash: ['src/main/resources/static/car-advice-splash.js'],
    extra: { url: 'https://caradvice.onrender.com/api/stats', namn: 'bilmodeller', ok: j => j && j.models > 0 } },
  { namn: 'VäderKläder', repo: 'VaderKlader', gren: 'main', rot: '',
    system: 'https://vaderklader-1.onrender.com/api/system', db: false,
    splash: ['src/main/resources/static/vader-splash.js'] },
  // Node-appen: versionerna mäts mot package.json/package-lock.json i stället för pom.xml.
  // Splashen ligger SIST i kalkylatorns egen fil — `splashFran` klipper bort kalkylatorn, annars
  // "syns" varje värd redan i dess egna fetch-anrop. `kod` = filerna integrationerna läses ur.
  { namn: 'Bränslekostnad', repo: 'BensinKostnad', gren: 'main', rot: '', plattform: 'node',
    system: 'https://bilresa.onrender.com/health', db: false,
    splash: ['src/bensinkostnad-wpcode.js'], splashFran: 'uppstartssplash',
    kod: ['server.js', 'src/bensinkostnad-wpcode.js'] },
];

/**
 * Kända externa värdar → vad splashen ska nämna. `ord` räcker med ett av (skiftlägesokänsligt).
 * `sekundar` = en källa bakom en rad som redan finns (en prisreserv, en av flera tidningar):
 * rapporteras som INFO om den inte nämns, eftersom tio rader är vad en telefon rymmer.
 */
const INTEGRATIONER = [
  { vard: /(^|\.)groq\.com$/,                 namn: 'Groq API',                      ord: ['groq'] },
  { vard: /trafikinfo\.trafikverket\.se$/,    namn: 'Trafikverket Open Data API',    ord: ['trafikverket'] },
  { vard: /openchargemap\.io$/,               namn: 'Open Charge Map',               ord: ['open charge map', 'openchargemap'] },
  { vard: /open-meteo\.com$/,                 namn: 'Open-Meteo',                    ord: ['open-meteo'] },
  { vard: /(^|\.)met\.no$/,                   namn: 'MET Norway',                    ord: ['met norway'] },
  { vard: /ev-database\.org$/,                namn: 'ev-database.org',               ord: ['ev-database'] },
  { vard: /blocket\.se$/,                     namn: 'Blocket',                       ord: ['blocket'] },
  { vard: /(youtube\.com|googleapis\.com|ytimg\.com)$/, namn: 'YouTube',             ord: ['youtube'] },
  { vard: /chargeprice\.app$/,                namn: 'Chargeprice',                   ord: ['chargeprice'], sekundar: 'prisreserv bakom Laddpriser-raden' },
  { vard: /api-ninjas\.com$/,                 namn: 'API Ninjas',                    ord: ['api ninjas'], sekundar: 'prisreserv bakom Laddpriser-raden' },
  { vard: /nobil\.no$/,                       namn: 'NOBIL',                         ord: ['nobil'], sekundar: 'antal uttag bakom Laddstationer-raden' },
  { vard: /(mynewsdesk\.com|cision\.com)$/,   namn: 'Pressflöden',                   ord: ['press'], sekundar: 'pressmeddelanden bakom elbilsindexet' },
  { vard: /elbilsvaruhuset\.se$/,             namn: 'Elbilsvaruhuset',               ord: ['elbilsvaruhuset'], sekundar: 'andrahandspriser' },
  { vard: /(teknikensvarld\.se|vibilagare\.se|m3\.se|msverige\.se|automotorsport\.se|carup\.se|alltomelbil\.se|folksam\.se)$/,
    namn: 'Motortidningarna', ord: ['teknikens', 'vi bil', 'expertdata'], sekundar: 'källor bakom Expertdata-raden' },
  { vard: /(mobilitysweden\.se|vpic\.nhtsa\.dot\.gov|auto-data\.net|bilweb\.se|volvocars\.com|vwfs\.io)$/,
    namn: 'Bildata (registreringar, VIN, specar, nypriser)', ord: ['bildatabas'], sekundar: 'källor bakom Bildatabas-raden' },
  { vard: /globalpetrolprices\.com$/,         namn: 'GlobalPetrolPrices',            ord: ['globalpetrolprices'] },
  { vard: /elprisetjustnu\.se$/,              namn: 'Elpriset just nu',              ord: ['elprisetjustnu'] },
  // Nominatim före den allmänna OSM-raden — första träffen vinner.
  { vard: /nominatim\.openstreetmap\.org$/,   namn: 'Nominatim',                     ord: ['nominatim'] },
  { vard: /project-osrm\.org$/,               namn: 'OSRM',                          ord: ['osrm'] },
  { vard: /(^|\.)openstreetmap\.org$/,        namn: 'OpenStreetMap-kartan',          ord: ['leaflet', 'openstreetmap'] },
];

/** Egna tjänster och sådant som inte är en integration. */
const IGNORERA = /(onrender\.com|elitrobban\.se|localhost|example\.(com|org)|w3\.org|schema\.org|github\.com|apache\.org|springframework\.org|maven\.org)$/;

// ── Rena hjälpare (provas i src/test/js/splash-vakt-prov.js) ─────────────────────────────

/** Externa värdar i en källfil: https://värd/… — bara domänen, i gemener. */
function vardarI(text) {
  const ut = new Set();
  for (const m of String(text).matchAll(/https?:\/\/([a-z0-9.-]+\.[a-z]{2,})/gi)) ut.add(m[1].toLowerCase());
  return [...ut];
}

/**
 * Splashens synliga text: kommentarer bort, \xNN och \uNNNN avkodade. Splasharna skriver å, ä, ö
 * som '\xe4' — utan avkodning hittades "Räckvidd" aldrig — och en kommentar som nämner en källa
 * är inte samma sak som en rad på skärmen.
 */
function splashText(js) {
  return String(js)
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:'"\\])\/\/.*$/gm, '$1')
    .replace(/\\x([0-9a-f]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\u([0-9a-f]{4})/gi, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .toLowerCase();
}

/** Major-versionen ur "27", "27.0.1" eller "1.8.0_392" → 27 / 27 / 8. */
function javaMajor(v) {
  const s = String(v || '').trim();
  if (!s) return null;
  const d = s.split(/[.+_-]/);
  return d[0] === '1' ? Number(d[1]) : Number(d[0]);
}

/** Spring Boot-versionen ur spring-boot-starter-parent i en pom.xml, eller null. */
function bootIPom(pom) {
  const m = String(pom || '').match(/<artifactId>\s*spring-boot-starter-parent\s*<\/artifactId>\s*<version>\s*([^<\s]+)/);
  return m ? m[1] : null;
}

/** Node-majorn ur package.json:s engines.node (">=24", "^24.1", "24.x") → 24, annars null. */
function nodeIPaket(paketJson) {
  try {
    const m = String(JSON.parse(paketJson).engines?.node || '').match(/(\d+)/);
    return m ? Number(m[1]) : null;
  } catch { return null; }
}

/** Den låsta versionen av ett paket ur package-lock.json (lockfileVersion 2/3), annars null. */
function lastVersion(lasJson, paket) {
  try { return JSON.parse(lasJson).packages?.['node_modules/' + paket]?.version || null; }
  catch { return null; }
}

/** Bara splashdelen av en fil som också bär annan kod: från första raden med `markor`. */
function splashDel(js, markor) {
  if (!markor) return String(js);
  const i = String(js).indexOf(markor);
  return i < 0 ? '' : String(js).slice(i);
}

/** Bedömer en apps integrationer: vilka värdar saknar splashrad? */
function bedomIntegrationer(vardar, text, sekundarHar = {}) {
  const fynd = [];
  const sedda = new Set();
  for (const v of vardar) {
    if (IGNORERA.test(v)) continue;
    const k = INTEGRATIONER.find(i => i.vard.test(v));
    if (!k) { fynd.push({ niva: 'VARNING', text: `okänd extern värd ${v} i koden — ny integration? Skriv en splashrad och en rad i INTEGRATIONER` }); continue; }
    if (sedda.has(k.namn)) continue;
    sedda.add(k.namn);
    const syns = k.ord.some(o => text.includes(o));
    if (syns) fynd.push({ niva: 'OK', text: `${k.namn} syns i splashen` });
    else if (k.sekundar || sekundarHar[k.namn]) fynd.push({ niva: 'INFO', text: `${k.namn} nämns inte (${sekundarHar[k.namn] || k.sekundar})` });
    else fynd.push({ niva: 'VARNING', text: `${k.namn} används i koden men saknar rad i splashen` });
  }
  return fynd;
}

// ── Hämtning ─────────────────────────────────────────────────────────────────────────────

async function hamta(url, som = 'json', huvud = {}) {
  const r = await fetch(url, { headers: { 'User-Agent': 'splash-vakt', ...huvud }, signal: AbortSignal.timeout(TIMEOUT) });
  if (!r.ok) {
    const nekad = r.headers.get('x-deny-reason');
    const e = new Error(url + ' HTTP ' + r.status + (nekad ? ` — molnets proxy nekade (x-deny-reason: ${nekad}), värden saknas i Allowed domains` : ''));
    e.status = r.status; e.nekad = nekad;
    throw e;
  }
  return som === 'json' ? r.json() : r.text();
}

/**
 * Ska ett misslyckat anrop mot en egen tjänst göras om? 2026-10-06 larmade vakten på 503/503/403
 * från tre gratistjänster som alla svarade 200 en stund senare: de väcktes samtidigt efter två
 * timmars sömn, och ETT försök gjorde en uppvakning till ett haveri. Men proxyns nej är ett annat
 * nej — det blir inte ja av att vänta, och tre försök hade bara gömt det.
 */
function forsokIgen(status, nekad) {
  if (nekad) return false;
  if (status === undefined) return true;              // nätverksfel eller timeout
  return status >= 500 || status === 403 || status === 429;
}

const FORSOK = 3, PAUS_MS = 20000;
/** hamta() mot en Render-tjänst som kan sova: upp till tre försök. */
async function hamtaVaken(url, som = 'json') {
  for (let n = 1; ; n++) {
    try { return await hamta(url, som); }
    catch (e) {
      if (n >= FORSOK || !forsokIgen(e.status, e.nekad)) {
        if (n > 1) e.message += ` (efter ${n} försök)`;
        throw e;
      }
      await new Promise(r => setTimeout(r, PAUS_MS));
    }
  }
}
// GitHub läses med git, inte REST-API:t: i molnrutinen lägger proxyn in en token som bara gäller
// CarAdvice, så api.github.com svarar 403 för de andra repona. git och raw-filerna går för alla.
const { execFileSync } = require('child_process');
const fs = require('fs'), os = require('os'), path = require('path');
const repoUrl = app => `https://github.com/${GITHUB}/${app.repo}`;
const git = args => execFileSync('git', args, { encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] });
function gitTopp(app) {
  const rad = git(['ls-remote', repoUrl(app), 'refs/heads/' + app.gren]).trim();
  if (!/^[0-9a-f]{40}\s/.test(rad)) throw new Error(`git ls-remote ${app.repo} gav inget för ${app.gren}`);
  return rad.slice(0, 40);
}
/** Filnamnen i grenens topp — en grund klon utan filinnehåll, städad efteråt. */
function gitFiler(app) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'splash-vakt-'));
  try {
    git(['clone', '-q', '--depth', '1', '--filter=blob:none', '--no-checkout', '-b', app.gren, repoUrl(app), dir]);
    return git(['-C', dir, 'ls-tree', '-r', '--name-only', 'HEAD']).split('\n').filter(Boolean);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
}
const raw = (app, fil) => hamta(`https://raw.githubusercontent.com/${GITHUB}/${app.repo}/${app.gren}/${fil}`, 'text');

/** Bankomat har ingen /api/system: siffrorna står i menysidans body-attribut. */
async function bankomatData(url) {
  const html = await hamtaVaken(url, 'text');
  const body = (html.match(/<body[^>]*>/) || [''])[0];
  const attr = n => { const m = body.match(new RegExp('data-' + n + '="([^"]*)"')); return m ? m[1] : undefined; };
  return { java: attr('java'), springBoot: attr('boot'), db: attr('db'), deployCommit: attr('commit'),
           konton: attr('konton'), kunder: attr('kunder'), saldo: attr('saldo') };
}

async function granska(app) {
  const fynd = [];
  const f = (niva, text) => fynd.push({ niva, text });

  // Driftens svar
  let d;
  try { d = app.html ? await bankomatData(app.html) : await hamtaVaken(app.system); }
  catch (e) { f('LARM', 'tjänsten svarade inte: ' + e.message); return { app: app.namn, fynd }; }

  // 1 (Node). Node mot package.json, Express mot package-lock.json
  if (app.plattform === 'node') {
    try {
      const onskad = nodeIPaket(await raw(app, app.rot + 'package.json'));
      const kor = javaMajor(d.node);
      if (!kor) f('LARM', 'splashen får ingen Node.js-version');
      else if (onskad && kor !== onskad) f('LARM', `kör Node.js ${kor} men package.json säger ${onskad} — uppgraderingen är inte driftsatt`);
      else f('OK', `Node.js ${d.node}`);
      const onskadExpress = lastVersion(await raw(app, app.rot + 'package-lock.json'), 'express');
      if (!d.express) f('LARM', 'splashen får ingen Express-version');
      else if (onskadExpress && d.express !== onskadExpress) f('LARM', `kör Express ${d.express} men package-lock.json säger ${onskadExpress} — uppgraderingen är inte driftsatt`);
      else f('OK', `Express ${d.express}`);
    } catch (e) { f('VARNING', 'kunde inte läsa package.json: ' + e.message); }
  }

  // 1. Java mot pom.xml
  if (app.plattform !== 'node') try {
    const pom = await raw(app, app.rot + 'pom.xml');
    const onskad = Number((pom.match(/<java\.version>\s*(\d+)/) || [])[1]);
    const kor = javaMajor(d.java);
    if (!kor) f('LARM', 'splashen får ingen Java-version');
    else if (onskad && kor !== onskad) f('LARM', `kör Java ${kor} men pom.xml säger ${onskad} — uppgraderingen är inte driftsatt`);
    else f('OK', `Java ${d.java}`);
    // 1b. Spring Boot mot pom.xml — samma fälla: en uppgradering som ligger i koden men inte kör
    const onskadBoot = bootIPom(pom);
    if (!d.springBoot) f('LARM', 'splashen får ingen Spring Boot-version');
    else if (onskadBoot && d.springBoot !== onskadBoot) f('LARM', `kör Spring Boot ${d.springBoot} men pom.xml säger ${onskadBoot} — uppgraderingen är inte driftsatt`);
    else f('OK', `Spring Boot ${d.springBoot}`);
  } catch (e) { f('VARNING', 'kunde inte läsa pom.xml: ' + e.message); }

  // 2. Databasen
  if (app.db) {
    if (!d.db) f('LARM', 'ingen databasversion — splashen visar raden utan PostgreSQL-nummer');
    else if (!/^postgres/i.test(d.db)) f('LARM', `databasen i drift är "${d.db}", inte PostgreSQL`);
    else f('OK', d.db);
  }

  // 3. Deployen mot grenens topp
  try {
    const topp = gitTopp(app).slice(0, 7);
    if (!d.deployCommit) f('VARNING', 'ingen deployCommit — körs tjänsten utanför Render?');
    else if (d.deployCommit !== topp) f('VARNING', `kör ${d.deployCommit} men ${app.gren} står på ${topp} — autodeployen har inte tagit senaste`);
    else f('OK', `autodeploy: ${app.gren} · ${topp}`);
  } catch (e) { f('VARNING', 'kunde inte läsa GitHub: ' + e.message); }

  // 4. Live-siffrorna
  for (const [namn, ok] of (app.siffror ? app.siffror(d) : [])) f(ok ? 'OK' : 'LARM', ok ? `${namn} på plats` : `${namn} saknas i splashen`);
  if (app.extra) {
    try { const j = await hamtaVaken(app.extra.url); f(app.extra.ok(j) ? 'OK' : 'LARM', `${app.extra.namn}: ` + (app.extra.ok(j) ? 'på plats' : 'tomt svar')); }
    catch (e) { f('LARM', `${app.extra.namn}: ${e.message}`); }
  }

  // 5. Integrationer i koden mot splashens text
  try {
    const filer = app.kod || gitFiler(app).filter(p => p.startsWith(app.rot + 'src/main/')
      && /\.(java|properties)$/.test(p));
    const vardar = new Set();
    for (let i = 0; i < filer.length; i += 8) {
      const texter = await Promise.all(filer.slice(i, i + 8).map(p => raw(app, p).catch(() => '')));
      texter.forEach(t => vardarI(t).forEach(v => vardar.add(v)));
    }
    const text = (await Promise.all(app.splash.map(p => raw(app, p))))
      .map(js => splashText(splashDel(js, app.splashFran))).join('\n');
    if (!text.trim()) f('LARM', `hittar inte splashen i ${app.splash.join(', ')} (markören "${app.splashFran}")`);
    fynd.push(...bedomIntegrationer([...vardar], text, app.sekundar));
  } catch (e) { f('VARNING', 'kunde inte granska integrationerna: ' + e.message); }

  return { app: app.namn, fynd };
}

async function main() {
  const resultat = await Promise.all(APPAR.map(granska));
  const larm = resultat.some(r => r.fynd.some(x => x.niva === 'LARM'));
  if (process.argv.includes('--json')) {
    console.log(JSON.stringify({ matt: new Date().toISOString(), larm, resultat }, null, 1));
  } else {
    const ikon = { OK: '✅', INFO: 'ℹ️', VARNING: '⚠️', LARM: '🚨' };
    const ut = [`# Splashvakten ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC`, ''];
    for (const r of resultat) {
      ut.push(`## ${r.app}`);
      for (const x of r.fynd) ut.push(`- ${ikon[x.niva]} ${x.niva}: ${x.text}`);
      ut.push('');
    }
    ut.push(larm ? '**Dom: LARM** — minst en splash visar fel eller tomt.' : '**Dom: splasharna stämmer.**');
    console.log(ut.join('\n'));
  }
  process.exit(larm ? 2 : 0);
}

if (require.main === module) main().catch(e => { console.error(e); process.exit(1); });
module.exports = { vardarI, splashText, javaMajor, bootIPom, nodeIPaket, lastVersion, splashDel, bedomIntegrationer, forsokIgen, INTEGRATIONER, IGNORERA };
