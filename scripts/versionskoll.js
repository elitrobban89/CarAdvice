/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
// Versionskollen: ligger något projekt efter senaste stabila version av sina beroenden?
//
//   node scripts/versionskoll.js            → rapport i markdown på stdout
//   node scripts/versionskoll.js --json     → samma sak som JSON
//
// Nattrutinen kör den den 1:a varje månad (docs/nattrutin.md, avsnitt 15). Bara GET: byggfilerna
// läses från raw.githubusercontent.com, senaste version från Maven Central och npm. Skriptet
// ändrar ingenting. Rutinen får inte uppgradera beroenden (ramarna rad 10), den rapporterar.
//
// Varför inte context7: dess versionslistor ligger efter. 2026-11-10 visade den Spring Boot 4.1.0
// som senaste medan Maven Central hade 4.1.2. context7 är till för att läsa VAD som ändrats, inte
// för att avgöra OM något ändrats.
//
// Exitkod 0 även när något ligger efter (det är en rapport, inget larm). Exitkod 3 om ingen
// register-värd gick att nå, för då säger rapporten ingenting.

const PROJEKT = [
  { namn: 'CarAdvice', repo: 'elitrobban89/CarAdvice', gren: 'master', filer: ['pom.xml'] },
  { namn: 'MiniPrisTåget', repo: 'elitrobban89/Tag', gren: 'master', filer: ['backend/pom.xml'] },
  { namn: 'Elbilsladdning', repo: 'elitrobban89/Elbilsladdning', gren: 'main', filer: ['backend/pom.xml'] },
  { namn: 'VäderKläder', repo: 'elitrobban89/VaderKlader', gren: 'main', filer: ['pom.xml'] },
  { namn: 'BiltUthyrning', repo: 'elitrobban89/BiltUthyrning', gren: 'main', filer: ['pom.xml'] },
  { namn: 'Bankomat 2.0', repo: 'elitrobban89/Bankomat2.0', gren: 'master', filer: ['pom.xml', 'web/pom.xml'] },
  { namn: 'Bränslekostnad', repo: 'elitrobban89/BensinKostnad', gren: 'main', filer: ['package.json'] },
  { namn: 'Java-labbar', repo: 'elitrobban89/Java-labbar', gren: 'main', filer: ['pom.xml'] },
  { namn: 'Fångdjupet web', repo: 'elitrobban89/fangdjupet-web', gren: 'main', filer: ['pom.xml'] },
  { namn: 'Fångdjupet (grupp 8)', repo: 'ithsjava26/grupp8-fangdjupet', gren: 'main', filer: ['pom.xml'] },
  { namn: 'Bibliotekshanteraren', repo: 'elitrobban89/Laboration-1-Bibliotekshanteraren', gren: 'master', filer: ['pom.xml'] },
  { namn: 'Budgethanteraren', repo: 'elitrobban89/Laboration-2-Budgethanteraren', gren: 'main', filer: ['pom.xml'] },
];

const TIMEOUT = 30000;

// ---------- rena delar (provas i src/test/js/versionskoll-prov.js) ----------

/** Förhandsversioner räknas aldrig som "senaste": RC, milstolpar, beta, early access, -jdk5 m.fl. */
const FORHAND = /(alpha|beta|[.-]rc|[.-]cr\d|[.-]m\d|milestone|snapshot|[.-]ea\b|\bea[+.-]|preview|-jdk\d|-android|-b\d+$|incubat)/i;
function arStabil(v) { return !FORHAND.test(v); }

/** Jämför versioner del för del: siffror som tal, annat som text. "1.10" > "1.9". */
function jamfor(a, b) {
  const da = String(a).split(/[.\-+_]/), db = String(b).split(/[.\-+_]/);
  for (let i = 0; i < Math.max(da.length, db.length); i++) {
    const x = da[i] ?? '0', y = db[i] ?? '0';
    const nx = /^\d+$/.test(x), ny = /^\d+$/.test(y);
    if (nx && ny) { const d = Number(x) - Number(y); if (d) return Math.sign(d); }
    else if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

/** Högsta stabila versionen ur maven-metadata.xml. */
function senasteStabila(metadataXml) {
  const v = [...metadataXml.matchAll(/<version>([^<]+)<\/version>/g)].map(m => m[1].trim()).filter(arStabil);
  return v.sort(jamfor).pop();
}

/** Hur stort steget är: major, minor eller patch. */
function steg(nu, senast) {
  const a = String(nu).split('.'), b = String(senast).split('.');
  const del = (x, i) => Number(x[i] ?? 0);             // "27" är 27.0.0, så 27 → 27.0.1 är patch
  if (del(a, 0) !== del(b, 0)) return 'major';
  if (del(a, 1) !== del(b, 1)) return 'minor';
  return 'patch';
}

/** Beroenden, pluginer och parent ur en pom.xml, med ${egenskaper} upplösta. */
function lasPom(xml) {
  const utanKommentarer = xml.replace(/<!--[\s\S]*?-->/g, '');
  const props = {};
  const propBlock = (utanKommentarer.match(/<properties>([\s\S]*?)<\/properties>/) || [, ''])[1];
  for (const m of propBlock.matchAll(/<([\w.-]+)>([^<]*)<\/\1>/g)) props[m[1]] = m[2].trim();
  const los = v => v && v.replace(/\$\{([^}]+)\}/g, (h, k) => props[k] ?? h);
  const ut = [];
  const parent = utanKommentarer.match(/<parent>([\s\S]*?)<\/parent>/);
  const falt = (blk, f) => { const m = blk.match(new RegExp('<' + f + '>([^<]+)</' + f + '>')); return m ? m[1].trim() : undefined; };
  if (parent) ut.push({ typ: 'parent', g: falt(parent[1], 'groupId'), a: falt(parent[1], 'artifactId'), v: los(falt(parent[1], 'version')) });
  for (const typ of ['dependency', 'plugin']) {
    for (const m of utanKommentarer.matchAll(new RegExp('<' + typ + '>([\\s\\S]*?)</' + typ + '>', 'g'))) {
      const v = los(falt(m[1], 'version'));
      if (!v || v.includes('${')) continue;            // versionen styrs av parent/BOM
      const g = falt(m[1], 'groupId') || (typ === 'plugin' ? 'org.apache.maven.plugins' : undefined);
      if (g) ut.push({ typ, g, a: falt(m[1], 'artifactId'), v });
    }
  }
  const java = props['java.version'] || props['maven.compiler.release'] || props['maven.compiler.source'];
  return { java, beroenden: ut };
}

/** Beroenden ur package.json. "^5.2.1" → 5.2.1. */
function lasPackage(json) {
  const p = JSON.parse(json);
  const alla = { ...(p.dependencies || {}), ...(p.devDependencies || {}) };
  return { node: p.engines?.node, beroenden: Object.entries(alla)
    .map(([namn, spec]) => ({ typ: 'npm', a: namn, v: String(spec).replace(/^[\^~>=<\s]+/, '') }))
    .filter(b => /^\d/.test(b.v)) };
}

// ---------- nätet ----------

async function hamta(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT), headers: { 'User-Agent': 'caradvice-versionskoll' } });
  if (!r.ok) { const e = new Error(`${r.status} från ${new URL(url).host}`); e.status = r.status; throw e; }
  return r.text();
}
const cache = new Map();
function senasteMaven(g, a) {
  const k = g + ':' + a;
  if (!cache.has(k)) cache.set(k, hamta(`https://repo1.maven.org/maven2/${g.replace(/\./g, '/')}/${a}/maven-metadata.xml`).then(senasteStabila));
  return cache.get(k);
}
function senasteNpm(namn) {
  const k = 'npm:' + namn;
  if (!cache.has(k)) cache.set(k, hamta(`https://registry.npmjs.org/${encodeURIComponent(namn).replace('%40', '@')}/latest`).then(t => JSON.parse(t).version));
  return cache.get(k);
}

async function granska(projekt) {
  const rader = [], fel = [];
  let java;
  for (const fil of projekt.filer) {
    let text;
    try { text = await hamta(`https://raw.githubusercontent.com/${projekt.repo}/${projekt.gren}/${fil}`); }
    catch (e) { fel.push(`${fil}: ${e.message}`); continue; }
    const las = fil.endsWith('.json') ? lasPackage(text) : lasPom(text);
    java = java || las.java || las.node;
    for (const b of las.beroenden) {
      try {
        const senast = b.typ === 'npm' ? await senasteNpm(b.a) : await senasteMaven(b.g, b.a);
        if (senast && jamfor(senast, b.v) > 0) rader.push({ fil, namn: b.a, nu: b.v, senast, steg: steg(b.v, senast) });
      } catch (e) { fel.push(`${b.a}: ${e.message}`); }
    }
  }
  return { projekt: projekt.namn, java, efter: rader, fel };
}

async function main() {
  const resultat = [];
  for (const p of PROJEKT) resultat.push(await granska(p));
  const allaFel = resultat.flatMap(r => r.fel);
  const ingenNadd = allaFel.length > 0 && resultat.every(r => r.efter.length === 0) && allaFel.length >= PROJEKT.length;
  if (process.argv.includes('--json')) { console.log(JSON.stringify(resultat, null, 1)); }
  else {
    const efter = resultat.flatMap(r => r.efter.map(e => ({ ...e, projekt: r.projekt })));
    const antal = s => efter.filter(e => e.steg === s).length;
    console.log(`## Versionskollen\n\n${PROJEKT.length} projekt, ${efter.length} beroenden efter (major ${antal('major')}, minor ${antal('minor')}, patch ${antal('patch')}).\n`);
    if (efter.length) {
      console.log('| Projekt | Fil | Beroende | Nu | Senaste stabila | Steg |\n|---|---|---|---|---|---|');
      for (const e of efter.sort((x, y) => ['major', 'minor', 'patch'].indexOf(x.steg) - ['major', 'minor', 'patch'].indexOf(y.steg)))
        console.log(`| ${e.projekt} | ${e.fil} | ${e.namn} | ${e.nu} | ${e.senast} | ${e.steg} |`);
    }
    console.log('\nJava/Node per projekt: ' + resultat.map(r => `${r.projekt} ${r.java || '?'}`).join(', '));
    if (allaFel.length) console.log('\n**Gick inte att läsa:**\n' + allaFel.map(f => '- ' + f).join('\n'));
  }
  process.exit(ingenNadd ? 3 : 0);
}

if (require.main === module) main().catch(e => { console.error(e); process.exit(1); });
module.exports = { arStabil, jamfor, senasteStabila, steg, lasPom, lasPackage };
