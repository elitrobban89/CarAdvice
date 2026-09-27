// Läser av baslinjen ur API:t (bara GET) och skriver den som JSON.
//   ADMIN_KEY=... node scripts/mat-baslinje.js docs/baslinje.json
// Nattrutinen kör den varje natt (docs/nattrutin.md, avsnitt BASLINJEN) i stället för att skriva av
// talen för hand - en avskrift kan glida, ett skript mäter samma sak varje gång.
const fs = require('fs');
const BAS = 'https://caradvice.onrender.com';
const KEY = process.env.ADMIN_KEY;
const hamta = async (p, admin = true) => {
  const r = await fetch(BAS + p, { headers: admin ? { 'X-Admin-Key': KEY } : {}, signal: AbortSignal.timeout(300000) });
  if (!KEY && admin) throw new Error('ADMIN_KEY saknas i miljon');
  if (!r.ok) throw new Error(p + ' HTTP ' + r.status);
  return r.json();
};

(async () => {
  const [stats, health, version, cars, cov, gen, ko, drivmedel, vpic, ice] = await Promise.all([
    hamta('/api/stats', false), hamta('/api/health', false), hamta('/api/version', false),
    hamta('/api/cars', false), hamta('/api/admin/cargo-coverage'), hamta('/api/admin/ice-generations'),
    hamta('/api/admin/insights/upcoming'), hamta('/api/admin/cargo-specs?drivmedel=true'),
    hamta('/api/admin/ice-generations/vpic-check'), hamta('/api/ice-consumption', false),
  ]);
  const sidor = [...(await hamta('/api/admin/insights?limit=500&page=0')), ...(await hamta('/api/admin/insights?limit=500&page=1')),
                 ...(await hamta('/api/admin/insights?limit=500&page=2'))];
  const kategorier = {};
  for (const i of sidor) { const k = i.category || 'tom'; kategorier[k] = (kategorier[k] || 0) + 1; }
  const koBilar = new Set(ko.insights.map(i => (i.car_make + ' ' + i.car_model).toLowerCase())).size;

  const bas = {
    _om: 'Nattrutinens baslinje. Rutinen skriver in nattens uppmatta tal har varje natt (utom vid LARM) - se docs/nattrutin.md avsnitt BASLINJEN. Talen i sjalva rutintexten ar historik; DENNA fil galler.',
    matt: new Date().toISOString(),
    commit: version.commit,
    stats: { models: stats.models, variants: stats.variants, insights: stats.insights },
    kontrollrakning: { cargoTotal: cov.total, evSpecs: health.evSpecs, iceConsumption: Array.isArray(ice) ? ice.length : ice.count,
                       summa: cov.total + health.evSpecs + (Array.isArray(ice) ? ice.length : ice.count), variants: stats.variants },
    evSpecs: health.evSpecs,
    apiCars: Array.isArray(cars) ? cars.length : cars.count,
    iceConsumption: Array.isArray(ice) ? ice.length : ice.count,
    cargo: cov,
    iceGenerations: { total: gen.total ?? gen.count, missar: gen.missar },
    ko: { rader: ko.insights.length, bilar: koBilar },
    drivmedel: { total: drivmedel.total, el: drivmedel.el, ice: drivmedel.ice, manuella: drivmedel.manuella },
    vpic: { kontrollerade: vpic.kontrollerade, hoppade: vpic.hoppade, vpicAnrop: vpic.vpicAnrop, perStatus: vpic.perStatus },
    insiktsraderLasta: sidor.length,
    hogstaInsiktsId: Math.max(...sidor.map(i => i.id)),
    kategorier,
  };
  fs.writeFileSync(process.argv[2], JSON.stringify(bas, null, 2) + '\n');
  const k = bas.kontrollrakning;
  console.log('skrev', process.argv[2], '| kontrollrakning', k.summa === k.variants ? 'GAR IHOP' : 'GAR INTE IHOP', k.summa, '/', k.variants);
})().catch(e => { console.error('FEL', e.message); process.exit(1); });
