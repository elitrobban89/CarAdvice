// BilRådgivning — main form logic
// Loaded as external script to bypass WordPress inline-script restrictions

window.onerror = function(msg, src, line) {
  var d = document.getElementById('ca-js-error');
  if (d) { d.textContent = 'JS-fel rad ' + line + ': ' + msg; d.style.display = 'block'; }
};

window._ca = function(action, arg) {
  if (window._caFns && window._caFns[action]) window._caFns[action](arg);
};

var CA_API_BASE = window.CA_API_URL || 'https://caradvice.onrender.com';

// Auto-ladda uppstartssplashen om sidan inte redan inkluderar den — så WordPress-sidor
// som bara har <script> för denna fil får splashen utan att snippet-HTML:en ändras.
// Guard: hoppa över om taggen redan finns eller splashen redan körts (undviker dubbelladdning).
(function caLoadSplash() {
  if (window.caReplaySplash || document.querySelector('script[src*="car-advice-splash"]')) return;
  var s = document.createElement('script');
  s.src = CA_API_BASE + '/car-advice-splash.js';
  s.defer = true;
  (document.head || document.documentElement).appendChild(s);
})();

// Mobil-CSS för formuläret injiceras här (samma regler som @media(max-width:520px) i
// WP-snippeten) så befintliga WordPress-sidor får den kompaktare mobil-layouten utan att
// snippet-HTML:en klistras om. Läggs sist i <body> så den vinner över snippetens inline-<style>.
(function caMobileCss() {
  if (document.getElementById('ca-mobile-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-mobile-css';
  s.textContent = '@media(max-width:520px){' +
    '#ca-wrap{padding:0 8px;margin:22px auto;}' +
    '.ca-grid{grid-template-columns:1fr;gap:12px;margin-bottom:12px;}' +
    '#ca-hero{padding:24px 15px;}' +
    '#ca-hero p.ca-sub{margin-bottom:20px;font-size:.95rem;color:rgba(255,255,255,.72);line-height:1.45;}' +
    '.ca-field label{margin-bottom:6px;font-size:.8rem;letter-spacing:.4px;color:rgba(255,255,255,.82);}' +
    '.ca-field select,.ca-field input[type="number"]{padding:13px 15px;font-size:1rem;border-radius:12px;}' +
    '.ca-fc-sub{color:rgba(255,255,255,.55);}.ca-fc-header{color:rgba(255,255,255,.9);}' +
    '.ca-history-label{color:rgba(255,255,255,.42);}' +
    '}';
  (document.body || document.documentElement).appendChild(s);
})();

// Polish-lager: mer glöd + glasmorphism + skiftande lila. Injiceras (som mobil-CSS:en)
// så WP-sidan slipper omklistring; läggs sist i <body> → vinner över snippetens inline-<style>.
(function caPolishCss() {
  if (document.getElementById('ca-polish-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-polish-css';
  s.textContent = [
    // Skiftande lila nyansdrift + aurora-drift för glödlagren
    '@keyframes ca-hue{0%{filter:hue-rotate(-12deg)}50%{filter:hue-rotate(16deg)}100%{filter:hue-rotate(-12deg)}}',
    '@keyframes ca-aurora{from{opacity:.62;transform:scale(1)}to{opacity:1;transform:scale(1.06) translate(1.5%,-1.5%)}}',
    '@keyframes ca-btn-glow{from{box-shadow:0 6px 24px rgba(139,92,246,.5),0 0 40px rgba(167,139,250,.2),inset 0 1px 0 rgba(255,255,255,.25)}to{box-shadow:0 8px 34px rgba(167,139,250,.72),0 0 74px rgba(139,92,246,.4),inset 0 1px 0 rgba(255,255,255,.32)}}',
    '@keyframes ca-sheen{0%,58%{left:-60%}82%,100%{left:130%}}',
    // Hero: glödande lila kant + djupare glow, och en skiftande aurora i ::before
    '#ca-hero{border:1px solid rgba(167,139,250,.28);box-shadow:0 24px 60px rgba(0,0,0,.4),0 0 90px rgba(139,92,246,.22),inset 0 1px 0 rgba(255,255,255,.09);}',
    '#ca-hero h2{text-shadow:0 0 34px rgba(167,139,250,.4);}',
    '#ca-hero::before{',
      'background:',
        'radial-gradient(ellipse at 72% 12%,rgba(139,92,246,.28) 0%,transparent 55%),',
        'radial-gradient(ellipse at 12% 88%,rgba(99,102,241,.2) 0%,transparent 48%),',
        'radial-gradient(ellipse at 88% 92%,rgba(217,70,239,.14) 0%,transparent 50%);',
      'animation:ca-hue 20s ease-in-out infinite,ca-aurora 12s ease-in-out infinite alternate;}',
    // Fält: mer glas + lila fokus-glöd
    '.ca-field select,.ca-field input[type="number"]{backdrop-filter:blur(10px) saturate(140%);-webkit-backdrop-filter:blur(10px) saturate(140%);border-color:rgba(167,139,250,.22);box-shadow:inset 0 1px 0 rgba(255,255,255,.06);}',
    '.ca-field input[type="number"]:focus,.ca-field select:focus{border-color:rgba(167,139,250,.7);box-shadow:0 0 0 3px rgba(139,92,246,.28),0 0 34px rgba(167,139,250,.4),inset 0 1px 0 rgba(255,255,255,.1);}',
    // Sök-knapp: ljusare skiftande lila, pulserande glöd + vandrande sheen
    '#ca-btn{position:relative;overflow:hidden;background:linear-gradient(135deg,#a855f7 0%,#8b5cf6 45%,#6366f1 100%);text-shadow:0 1px 8px rgba(30,10,60,.45);animation:ca-btn-glow 2.8s ease-in-out infinite alternate,ca-hue 16s ease-in-out infinite;}',
    '#ca-btn::after{content:"";position:absolute;top:0;left:-60%;width:45%;height:100%;background:linear-gradient(100deg,transparent,rgba(255,255,255,.35),transparent);transform:skewX(-18deg);pointer-events:none;animation:ca-sheen 5s ease-in-out infinite;}',
    '#ca-btn:hover{box-shadow:0 12px 40px rgba(167,139,250,.7),0 0 80px rgba(139,92,246,.4),inset 0 1px 0 rgba(255,255,255,.3);}',
    // Kort: glasigare + topp-highlight; starkare lila hover-glow på Bil 1
    '.ca-card{backdrop-filter:blur(12px) saturate(140%);-webkit-backdrop-filter:blur(12px) saturate(140%);box-shadow:0 2px 14px rgba(0,0,0,.22),inset 0 1px 0 rgba(255,255,255,.07);}',
    '.ca-card-1:hover{border-color:rgba(139,92,246,.65);box-shadow:0 16px 50px rgba(139,92,246,.3),0 0 54px rgba(167,139,250,.2),inset 0 1px 0 rgba(255,255,255,.08);}',
    '.ca-card-2:hover{box-shadow:0 16px 50px rgba(14,165,233,.24),0 0 50px rgba(56,189,248,.18),inset 0 1px 0 rgba(255,255,255,.08);}',
    '.ca-card-3:hover{box-shadow:0 16px 50px rgba(16,185,129,.22),0 0 50px rgba(52,211,153,.16),inset 0 1px 0 rgba(255,255,255,.08);}',
    // ── Vandrande färgkant, samma grepp som chattpanelens ────────────────────
    // Ringen ritas med conic-gradient + mask-composite och roteras via en registrerad
    // vinkelvariabel. Utan @property går vinkeln inte att animera och kanten står stilla
    // som en statisk färgring — degraderar alltså snyggt i äldre webbläsare.
    '@property --ca-rim-ang{syntax:"<angle>";initial-value:0deg;inherits:false;}',
    '@keyframes ca-rim{to{--ca-rim-ang:360deg;}}',
    // Hero: hela färgskalan, som chattpanelen. ::before är upptaget av auroran, så ::after.
    '#ca-hero::after{content:"";position:absolute;inset:0;z-index:0;pointer-events:none;',
      'border-radius:inherit;padding:2px;',
      'background:conic-gradient(from var(--ca-rim-ang),#a78bfa,#38bdf8,#22d3ee,#f472b6,#fbbf24,#a78bfa);',
      '-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);',
      'mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);',
      '-webkit-mask-composite:xor;mask-composite:exclude;',
      'opacity:.85;filter:saturate(140%);animation:ca-rim 9s linear infinite;}',
    // Hero-innehållet över ringen — annars målas rubriken under pseudon
    '#ca-hero>*{position:relative;z-index:1;}',
    // Auroran får cyan, rosa och turkos utöver lila så skiftningen syns som färg och
    // inte bara som ljusstyrka (hue-rotate på enbart lila ger nästan ingen upplevd rörelse)
    '#ca-hero::before{',
      'background:',
        'radial-gradient(ellipse at 72% 12%,rgba(139,92,246,.3) 0%,transparent 55%),',
        'radial-gradient(ellipse at 18% 22%,rgba(56,189,248,.17) 0%,transparent 52%),',
        'radial-gradient(ellipse at 12% 88%,rgba(99,102,241,.2) 0%,transparent 48%),',
        'radial-gradient(ellipse at 88% 92%,rgba(244,114,182,.15) 0%,transparent 50%),',
        'radial-gradient(ellipse at 52% 62%,rgba(45,212,191,.1) 0%,transparent 46%);',
      // ca-hue är BORTTAGEN här med flit. Animerad filter:hue-rotate() på den här ytan
      // om-filtrerar hela heron varje bildruta: uppmätt 31,4 → 43,9 fps när den togs bort,
      // klart dyrast av allt på sidan. Den var ett billigt sätt att fejka färgrörelse på en
      // enfärgat lila gradient — överflödig nu när auroran har riktiga färger och ringen
      // ovan ger äkta färgvandring. Kvar på #ca-btn där ytan är liten (mätt till ~1 fps).
      'animation:ca-aurora 12s ease-in-out infinite alternate;}',
    // Korten: ringen håller sig i kortets egen färgfamilj så numreringen 1/2/3 fortfarande
    // går att läsa på färgen. ::before är upptaget av orben, ::after är fritt.
    // Långsammare och svagare än heron — tre samtidiga ringar ska inte stjäla blicken.
    '.ca-card::after{content:"";position:absolute;inset:0;z-index:0;pointer-events:none;',
      'border-radius:inherit;padding:1.5px;',
      '-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);',
      'mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);',
      '-webkit-mask-composite:xor;mask-composite:exclude;',
      'opacity:.55;animation:ca-rim 14s linear infinite;}',
    '.ca-card-1::after{background:conic-gradient(from var(--ca-rim-ang),#8b5cf6,#a78bfa,#c4b5fd,#6366f1,#8b5cf6);}',
    // Förskjutna starter så de tre korten inte pulserar i lockstep
    '.ca-card-2::after{background:conic-gradient(from var(--ca-rim-ang),#0ea5e9,#38bdf8,#67e8f9,#3b82f6,#0ea5e9);animation-delay:-4.6s;}',
    '.ca-card-3::after{background:conic-gradient(from var(--ca-rim-ang),#10b981,#34d399,#6ee7b7,#14b8a6,#10b981);animation-delay:-9.3s;}',
    // Kanten tänds tydligare när man hovrar kortet man läser
    '.ca-card:hover::after{opacity:.95;animation-duration:7s;}',
    // Respektera reduced motion
    '@media(prefers-reduced-motion:reduce){#ca-hero::before,#ca-hero::after,#ca-btn,#ca-btn::after,.ca-card::after{animation:none!important;}}'
  ].join('');
  (document.body || document.documentElement).appendChild(s);
})();

// Dagsaktuella bränslepriser från Bilresa-backenden (6 h localStorage-cache) —
// används i ägandekostnadskalkylen; värdena nedan är fallback om API:et inte svarar
var CA_FUEL_PRICES = { bensin: 18, diesel: 17.5 };
(function caLoadFuelPrices() {
  try {
    var c = localStorage.getItem('ca_fuel_prices');
    if (c) {
      var o = JSON.parse(c);
      if (Date.now() - o.ts < 6 * 60 * 60 * 1000) { CA_FUEL_PRICES = o.p; return; }
    }
  } catch(e) {}
  fetch('https://bilresa.onrender.com/api/fuel-price')
    .then(function(r) { return r.json(); })
    .then(function(d) {
      if (d && d.bensin95 > 0) {
        CA_FUEL_PRICES = { bensin: d.bensin95, diesel: (d.diesel > 0 ? d.diesel : 17.5) };
        try { localStorage.setItem('ca_fuel_prices', JSON.stringify({ ts: Date.now(), p: CA_FUEL_PRICES })); } catch(e) {}
      }
    })
    .catch(function() { /* fallback-priserna räcker */ });
})();

var caHasSearched = false;
var caInitialValues = {};
var caCurrentRecs = null;
var caSavedFromServer = [];
var caCurrentKm = 15000;
var caCurrentCategory = '';
// Sätts av sökningen när servern inte hittade en enda bil inom budgettaket
var caBudgetShortfall = null;
var caNarrowCriteria = null;   // {kvar, krav[]} nar vakterna gallrat bort bilar utan budgetdom
var caShortfallBudget = 0;
var caShortfallMaxAge = null;
var caShortfallNewCar = false;   // nybilssök: siffran är ett nypris, inte ett annonspris
var caShortfallPayload = null;   // preferenserna sökningen använde, för alternativuppslaget
var caIsLeasing = false;
var caKopBudget = 200000;
var caLeasingBudget = 3000;
var caLoadingMessages = [
  'AI:n analyserar dina behov…',
  'Kollar Bilprovningens statistik…',
  'J\xe4mf\xf6r driftkostnader…',
  'S\xf6ker p\xe5 svenska marknaden…',
  'V\xe4ger pris mot tillf\xf6rlitlighet…',
  'H\xe4mtar v\xe4lrecenserade alternativ…'
];
var caLoadingInterval = null;

var CA_HISTORY_KEY = 'ca-history';
var CA_HISTORY_MAX = 5;
var CA_CAT_NAMES = { smaabil: 'Sm\xe5bil', familjebil: 'Familjebil', elbil: 'Elbil', suv: 'SUV', laddhybrid: 'Laddhybrid' };

/**
 * Kategorivärden som inte längre finns i formuläret, översatta till det som ersatte dem.
 *
 * Ekonomibil slogs ihop med Småbil 2026-08-10, men värdet lever kvar i tre lager utanför vår
 * kontroll: delade länkar (?category=ekonomibil), localStorage hos alla som sökt förut, och
 * sparade sökningar i databasen. Utan översättningen sätts <select> till ett värde som inte
 * finns, och då blir fältet TOMT — samma fel som usage=familj gav i en delad länk 2026-08-10.
 */
var CA_CAT_ALIAS = { ekonomibil: 'smaabil' };
function caCanonCat(v) { return (v && CA_CAT_ALIAS[v]) || v; }
var CA_FUEL_NAMES = { bensin: 'Bensin', diesel: 'Diesel', hybrid: 'Hybrid' };
var CA_TRANSMISSION_NAMES = { manuell: 'Manuell', automat: 'Automat' };
// Kategorierna där budgeten kan gå FÖRBI segmentet — gränsen OCH rådet står på ett ställe.
// Varningen läste förut sitt eget tak (200 000/150 000) medan budgetrutan bytte nivå först vid
// 249 000/199 000. I glappet varnade den ena för att budgeten var för hög medan den andra sa
// att den räckte till precis rätt bil ("Ekonomibil brukar kosta max 200 000 kr" ovanför
// "Här räcker budgeten till en fabriksny småbil"), och de pekade dessutom vidare till olika
// kategorier — varningen till laddhybrid, rutan till elbil.
// Taket ligger PÅ reglagets rutnät (steg om 25 000 kr): 249 000 gick aldrig att ställa in och
// hade bara visats som en udda siffra i varningstexten.
var CA_OVER_CATEGORY = {
  // Ekonomibil och Småbil slogs ihop 2026-08-10: de överlappade redan i appens egen text
  // ("Prisvärda småbilar — Sandero, Fiesta, Fabia, Polo, Yaris" mot "Stadsbilar — up!, C1,
  // Picanto, Aygo"), och skillnaden supermini/stadsbil är inget en köpare väljer på. Småbil
  // överlevde: 85 av 89 insikter bar redan den etiketten, och namnet är konkret — "ekonomibil"
  // är en känsla om driftkostnad som lika gärna kan gälla en begagnad Passat.
  // Taket ärvdes från ekonomibil (250 000), eftersom den sammanslagna kategorin rymmer
  // superminis och inte bara stadsbilar.
  smaabil:    { over: 250000, byt: 'familjebil, SUV eller elbil' }
};

// Burnout-hjulet under laddtexten. Elementen skapas från JS, inte i HTML-snippeten: sidan på
// WordPress är en manuell kopia och hade annars saknat effekten tills snippeten klistrats in på
// nytt — samma skäl som budgetrutan byggs så.
//
// Rörelsen körs med Web Animations API i stället för @keyframes. Ett <style>-element hade varit
// kortare men CSP:n på sidan är stram, och en blockerad stilregel ger ett stillastående hjul som
// ser trasigt ut. element.animate() är ett JS-API och berörs inte.
var caBurnoutAnims = [];
var caSmokeInterval = null;

function caBurnoutBox() {
  var box = document.getElementById('ca-burnout');
  if (box) return box;
  var loader = document.getElementById('ca-loader');
  if (!loader) return null;
  box = document.createElement('div');
  box.id = 'ca-burnout';
  // Glöden ligger i BAKGRUNDSLAGRET på rutan, inte som ett absolut lager ovanpå. Ett överlagt
  // sken tvättar ur det som ligger under — samma fälla som kortens ::before-glow gick i.
  // Glöden är förskjuten åt vänster så den inte konkurrerar med rökplymen till höger
  box.setAttribute('style', 'position:relative;display:flex;justify-content:center;' +
    'padding:12px 0 14px;overflow:hidden;' +
    'background:radial-gradient(ellipse at 42% 50%,rgba(139,92,246,.18),transparent 60%)');
  // Röken ligger BAKOM glaskortet (z-index 0 mot kortets 1) så puffarna ser ut att välla fram
  // under däcket i stället för att ligga som dis framför det.
  box.innerHTML =
    '<div id="ca-smoke" style="position:absolute;inset:0;z-index:0;pointer-events:none"></div>' +
    '<div style="position:absolute;left:12%;right:12%;bottom:9px;height:2px;border-radius:2px;' +
      'z-index:0;background:linear-gradient(90deg,transparent,rgba(139,92,246,.5) 30%,' +
      'rgba(139,92,246,.5) 70%,transparent)"></div>' +
    '<div id="ca-glass" style="position:relative;z-index:1;width:72px;height:72px;' +
      'border-radius:18px;display:flex;align-items:center;justify-content:center;overflow:hidden;' +
      'background:linear-gradient(145deg,rgba(255,255,255,.10),rgba(255,255,255,.03));' +
      '-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);' +
      'border:1px solid rgba(255,255,255,.16);' +
      'box-shadow:0 6px 22px rgba(0,0,0,.32),inset 0 1px 0 rgba(255,255,255,.22)">' +
      // Däcket: gummit måste ha MÖNSTER, annars är en roterande ring omöjlig att skilja från
      // en stillastående. Klackarna sitter som streck runt slitbanan och gör varvet synligt.
      // Ritat efter en Continental PremiumContact 7: tjock sidovägg med präglad text och en
      // blankpolerad flerekrad alufälg. Däcket är dock sett RAKT FRAMIFRÅN, inte i 3/4 som
      // produktbilden — en snedställd ellips som roterar kring sin mitt vinglar som ett mynt
      // på ett bord i stället för att snurra, och rotationen är hela poängen här.
      '<svg id="ca-wheel" width="48" height="48" viewBox="0 0 42 42" aria-hidden="true">' +
        '<defs>' +
          '<linearGradient id="ca-rim" x1="0" y1="0" x2="0.7" y2="1">' +
            '<stop offset="0" stop-color="#e8eaef"/><stop offset="0.45" stop-color="#a9aeba"/>' +
            '<stop offset="1" stop-color="#6f7482"/></linearGradient>' +
        '</defs>' +
        '<circle cx="21" cy="21" r="20.4" fill="#0a0810"/>' +          // slitbanans kant
        '<circle cx="21" cy="21" r="19.4" fill="#191521"/>' +          // gummi
        // Klackarna gör varvet synligt — en jämn ring går inte att skilja från en stillastående.
        // Fina och täta: grova streck läser som kugghjul, inte som slitbana.
        '<circle cx="21" cy="21" r="19.9" fill="none" stroke="#37304a" stroke-width="1.2" ' +
          'stroke-dasharray="1 1.7"/>' +
        // Präglad text på sidoväggen, antydd som streck
        '<circle cx="21" cy="21" r="16" fill="none" stroke="#282235" stroke-width="1" ' +
          'stroke-dasharray="1.2 2.4"/>' +
        '<circle cx="21" cy="21" r="13" fill="url(#ca-rim)"/>' +       // alufälg
        '<circle cx="21" cy="21" r="13" fill="none" stroke="#0f0d16" stroke-width="1.1"/>' +
        // Fem ekrar: fönstren skärs ut ur fälgen och är BREDA och kilformade, smalast mot navet.
        // Runda fönster gav en blomma i stället för ekrar — det är kilformen som gör det till fälg.
        '<g fill="#120f1a">' +
          '<path id="ca-eker" d="M17.4 10.1 Q21 8.1 24.6 10.1 L22 16.3 Q21 17.1 20 16.3 Z"/>' +
          '<use href="#ca-eker" transform="rotate(72 21 21)"/>' +
          '<use href="#ca-eker" transform="rotate(144 21 21)"/>' +
          '<use href="#ca-eker" transform="rotate(216 21 21)"/>' +
          '<use href="#ca-eker" transform="rotate(288 21 21)"/>' +
        '</g>' +
        '<circle cx="21" cy="21" r="4.2" fill="#1c1727" stroke="#8b5cf6" stroke-width="1.1"/>' +
        '<circle cx="21" cy="21" r="1.4" fill="#c4b5fd"/>' +
      '</svg>' +
      // Blixten: en smal ljusstrimma som sveper snett över glaset med jämna mellanrum
      '<div id="ca-flash" style="position:absolute;top:-40%;left:-75%;width:60%;height:180%;' +
        'transform:rotate(18deg);opacity:0;pointer-events:none;' +
        'background:linear-gradient(90deg,rgba(196,181,253,0),rgba(255,255,255,.95) 45%,' +
        'rgba(196,181,253,.85) 60%,rgba(196,181,253,0))"></div>' +
    '</div>';
  loader.appendChild(box);
  return box;
}

/** Rökpuff under däcket — driver ut åt sidan och uppåt medan den tunnas ut. */
function caSpawnSmoke() {
  var smoke = document.getElementById('ca-smoke');
  if (!smoke) return;
  var size = 16 + Math.random() * 18;
  var puff = document.createElement('div');
  // Föds vid däckets högra kant, inte i mitten — annars ser puffarna ut att komma ur navet
  puff.setAttribute('style', 'position:absolute;left:calc(50% + 14px);bottom:6px;margin-left:' +
    (-size / 2) + 'px;width:' + size + 'px;height:' + size + 'px;border-radius:50%;' +
    'background:radial-gradient(circle,rgba(222,217,238,.7),rgba(222,217,238,0) 70%)');
  smoke.appendChild(puff);
  // Röken går åt ETT håll, bakåt från däcket — som på en riktig burnout där bilen står still
  // och gummiröken vräker ut bakom hjulet. Symmetriska puffar åt båda hållen läser som en
  // dimmaskin, inte som ett däck som sliter.
  // Plymen ska ligga LÅGT och långt: en puff som stiger rakt upp läser som ånga, inte som
  // gummirök som vräker ut bakom ett däck.
  var anim = puff.animate(
    [{ transform: 'translate(0,0) scale(.35)', opacity: .85 },
     { transform: 'translate(' + (52 + Math.random() * 96) + 'px,' +
        (-3 - Math.random() * 13) + 'px) scale(2.3)', opacity: 0 }],
    { duration: 1000 + Math.random() * 550, easing: 'ease-out' });
  anim.onfinish = function() { puff.remove(); };
}

function caBurnoutStart() {
  var box = caBurnoutBox();
  if (!box) return;
  box.style.display = 'flex';
  // Ikonen visas stilla för den som bett om mindre rörelse — samma regel som splash-skärmarna
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  var wheel = document.getElementById('ca-wheel');
  var glass = document.getElementById('ca-glass');
  var flash = document.getElementById('ca-flash');
  // Burnout: däcket spinner loss, alltså snabbt. 260 ms per varv är precis under gränsen där
  // mönstret blir ett suddigt band och rotationen slutar gå att uppfatta.
  caBurnoutAnims.push(wheel.animate(
    [{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }],
    { duration: 260, iterations: Infinity, easing: 'linear' }));
  // Skakningen säljer att däcket sliter mot underlaget i stället för att rulla fritt
  caBurnoutAnims.push(glass.animate(
    [{ transform: 'translateX(-1px)' }, { transform: 'translateX(1px)' }],
    { duration: 80, direction: 'alternate', iterations: Infinity }));
  // Glaset andas svagt så rutan inte står helt död mellan blixtarna
  caBurnoutAnims.push(glass.animate(
    [{ boxShadow: '0 6px 22px rgba(0,0,0,.32),inset 0 1px 0 rgba(255,255,255,.22)' },
     { boxShadow: '0 6px 30px rgba(139,92,246,.42),inset 0 1px 0 rgba(255,255,255,.30)' }],
    { duration: 1400, direction: 'alternate', iterations: Infinity, easing: 'ease-in-out' }));
  caBurnoutAnims.push(flash.animate(
    [{ transform: 'translateX(0) rotate(18deg)', opacity: 0, offset: 0 },
     { opacity: .95, offset: .08 },
     { opacity: .95, offset: .16 },
     { transform: 'translateX(420%) rotate(18deg)', opacity: 0, offset: .28 },
     { transform: 'translateX(420%) rotate(18deg)', opacity: 0, offset: 1 }],
    { duration: 2600, iterations: Infinity, easing: 'ease-out' }));
  caSpawnSmoke();
  caSmokeInterval = setInterval(caSpawnSmoke, 95);
}

function caBurnoutStop() {
  clearInterval(caSmokeInterval);
  caSmokeInterval = null;
  caBurnoutAnims.forEach(function(a) { a.cancel(); });
  caBurnoutAnims = [];
  var smoke = document.getElementById('ca-smoke');
  if (smoke) smoke.innerHTML = '';   // annars ligger halvfärdiga puffar kvar till nästa sökning
  var box = document.getElementById('ca-burnout');
  if (box) box.style.display = 'none';
}

function caStartLoadingText() {
  var i = 0;
  document.getElementById('ca-loader-text').textContent = caLoadingMessages[0];
  caLoadingInterval = setInterval(function() {
    i = (i + 1) % caLoadingMessages.length;
    document.getElementById('ca-loader-text').textContent = caLoadingMessages[i];
  }, 2200);
  caBurnoutStart();
}
function caStopLoadingText() {
  clearInterval(caLoadingInterval);
  caLoadingInterval = null;
  document.getElementById('ca-loader-text').textContent = caLoadingMessages[0];
  caBurnoutStop();
}

// Vad budgeten räcker till på den svenska begagnatmarknaden, per kategori. KURERADE siffror
// — de åldras och behöver ses över, precis som tågprojektets resolveModel-lager.
// Nivåerna är kategorispecifika av nödvändighet: samma 150 000 kr köper en helt annan bil
// som elbil, kombi, SUV, laddhybrid och stadsbil. Alla sex kategorier i väljaren har egna
// nivåer. Modellnamn nämns bara där prisläget är mätt, och bara där annonsunderlaget
// räcker (minst ca 10 träffar). Nyckeln måste matcha option-värdet i snippeten exakt —
// småbil heter "smaabil" där.
//
// Mätta 2026-08-08 mot Blocket med SAMMA underlag som prisraden på korten: högst
// 10 000 mil och medianrelativ outlier-trimning (0,4×), annars lovar rutan en bil som
// bara finns som utsliten. Elbilstexterna låg då en nivå efter marknaden — "MG ZS EV
// kring 100 000 kr" var 129 900, ID.3 låg kvar på 299 000-nivån trots att den börjar vid
// 198 900, och Polestar 2/ID.4/Enyaq "börjar bli möjliga" först vid 399 000 fast de går att
// köpa för 209 000/229 500/279 000.
//
// Mät om med Blockets API (gratis, ingen Groq-kvot): sales_form=1&2, mileage_to=10000,
// sort=PRICE_ASC. Använd årsfönster per modell (year_from/year_to, ±1) — utan det matchar
// fritextsökningen fel bilar och ger orimliga golv: en Škoda Octavia 2025 för 75 600 kr och
// en "Volvo V90 från 2013", en modell som inte fanns då.
var CA_BUDGET_LEVELS = {
  // Ekonomibil och småbil är de enda kategorierna där budgeten kan gå FÖRBI segmentet:
  // en fabriksny Picanto kostar ca 150 000 kr, så 400 000 köper inte en bättre småbil
  // utan bara fel bil. Toppnivåerna pekar därför vidare till en annan kategori i stället
  // för att hitta på en dyrare modell.
  // Sammanslagen av Ekonomibil + Småbil (2026-08-10). Nivåerna är UNIONEN: stadsbilarna i
  // botten där de är billigast, superminis där de faktiskt kostar. Båda listornas modeller
  // finns kvar — de var poängen med respektive kategori och beskriver tillsammans hela
  // segmentet, från en 12 år gammal up! till en fabriksny Yaris.
  smaabil: { ikon: '🚘', nivaer: [
    { upTo:  99000, txt: 'Stadsbilar och \xe4ldre sm\xe5bilar, ca 8–12 \xe5r. VW up! fr\xe5n ca 45 000 kr, Dacia Sandero 45 000, Citro\xebn C1 och Peugeot 108 kring 59 000, Ford Fiesta 60 000, Kia Picanto 68 000 och VW Polo 75–80 000.' },
    { upTo: 149000, txt: 'Nyare exemplar, ca 2–8 \xe5r. Kia Picanto fr\xe5n ca 84 000 kr, Toyota Aygo X 100–135 000, Dacia Sandero fr\xe5n 100 000 och Toyota Yaris kring 125 000.' },
    { upTo: 199000, txt: 'N\xe4stan ny — Suzuki Swift fr\xe5n ca 155 000 kr, Toyota Yaris kring 180 000. Picanto och Aygo X g\xe5r att f\xe5 fabriksnya kring 150 000.' },
    { upTo: CA_OVER_CATEGORY.smaabil.over, txt: 'H\xe4r r\xe4cker budgeten till en fabriksny sm\xe5bil med full garanti.' },
    { upTo: Infinity, overCategory: true }
  ] },
  // Elbilsnivaerna delar sina siffror med GroqService.EV_PRICE_FLOORS, som ger AI:n samma golv
  // som prisankare — rutan sager vad pengarna racker till, prompten vad AI:n far foresla. Gar de
  // isar motsager sidan sig sjalv i samma vy: 2026-08-10 sa rutan "MG4 kring 195 000" medan
  // motorn foreslog EV6 for 316 990 och skrev att budgeten inte rackte. Mat om BADA samtidigt.
  elbil: { ikon: '⚡', nivaer: [
    { upTo:  99000, txt: 'De \xe4ldsta elbilarna — Renault Zoe fr\xe5n ca 58 000 kr och Nissan Leaf fr\xe5n ca 70 000. Kort r\xe4ckvidd och ett batteri som b\xf6rjar bli \xe5ldrat.' },
    { upTo: 149000, txt: 'Liten begagnad elbil, ca 6–10 \xe5r. MG ZS EV fr\xe5n ca 130 000 kr och e-Golf kring 139 000 — Leaf och Zoe ligger under det.' },
    { upTo: 199000, txt: 'Kompaktelbil med riktig r\xe4ckvidd — Kia Niro EV fr\xe5n ca 175 000 kr, Hyundai Kona Electric och MG4 kring 195 000, VW ID.3 knappt 199 000.' },
    { upTo: 249000, txt: 'Nyare begagnad elbil, ca 3–5 \xe5r. Polestar 2 fr\xe5n ca 209 000 kr, Tesla Model 3 kring 215 000 och VW ID.4 fr\xe5n 229 500.' },
    { upTo: 299000, txt: 'Familjeelbil i begagnat skick — Škoda Enyaq fr\xe5n ca 279 000 kr, och b\xe4ttre exemplar av ID.4 och Polestar 2.' },
    { upTo: 399000, txt: 'Nyare familjeelbil eller el-SUV — Enyaq, ID.4 och Polestar 2 med l\xe5g m\xe4tarst\xe4llning och full r\xe4ckvidd.' },
    { upTo: 549000, txt: 'Ny eller n\xe4stan ny familjeelbil, eller en st\xf6rre el-SUV n\xe5gra \xe5r gammal.' },
    { upTo: 749000, txt: 'Stor el-SUV eller premiumsedan, ny eller n\xe5got \xe5r gammal.' },
    { upTo: Infinity, txt: 'Premiumsegmentet — stora elbilar med l\xe5ng r\xe4ckvidd och snabb laddning. H\xe4r styr utrustningsniv\xe5n priset mer \xe4n modellvalet.' }
  ] },
  familjebil: { ikon: '🚗', nivaer: [
    { upTo:  99000, txt: 'De \xe4ldsta kombibilarna, ca 10–12 \xe5r. Ford Focus kombi fr\xe5n ca 60 000 kr och Peugeot 308 SW kring 75 000.' },
    { upTo: 149000, txt: 'Kombi, ca 8–10 \xe5r. Škoda Octavia kombi fr\xe5n ca 130 000 kr, Kia Ceed SW kring 140 000.' },
    { upTo: 199000, txt: 'Rymlig kombi, ca 6–9 \xe5r. VW Passat och Volvo V60 fr\xe5n ca 150 000 kr.' },
    { upTo: 249000, txt: 'Nyare kombi, ca 5–7 \xe5r. Toyota Corolla Touring Sports fr\xe5n ca 209 000 kr, Volvo V90 kring 220 000 och Škoda Superb kombi 239 000.' },
    { upTo: 299000, txt: 'Volvo V60 av nyare \xe5rsmodell, eller b\xe4ttre exemplar av V90 och Superb.' },
    { upTo: 399000, txt: 'N\xe4stan ny kombi — l\xe5g m\xe4tarst\xe4llning, ofta laddhybrid.' },
    { upTo: 549000, txt: 'Ny eller n\xe4stan ny familjebil i mellanklassen.' },
    { upTo: 749000, txt: 'Stor kombi eller premiummellanklass, ny eller n\xe5got \xe5r gammal.' },
    { upTo: Infinity, txt: 'Premiumsegmentet — stora kombibilar med full utrustning. H\xe4r styr utrustningsniv\xe5n priset mer \xe4n modellvalet.' }
  ] },
  // Laddhybrid har ett tydligt golv: under ca 160 000 kr finns nästan inga annonser med
  // låg mätarställning. Mätt med fuel-fältet "Plug-in Bensin"/"Plug-in Diesel", eftersom
  // fritextsökningen på modellnamnet annars blandar in bensin- och dieselvarianterna.
  // Modeller med tunt underlag (Audi A3 e-tron n=2, Ioniq n=3) namnges inte.
  laddhybrid: { ikon: '🔌', nivaer: [
    { upTo: 149000, rubrik: 'r\xe4cker inte till en laddhybrid:',
      txt: 'De med l\xe5g m\xe4tarst\xe4llning b\xf6rjar kring 160 000 kr. F\xf6r pengarna f\xe5r du en nyare bensin- eller dieselbil i st\xe4llet.' },
    { upTo: 199000, txt: 'De f\xf6rsta laddhybriderna — Kia Niro plug-in fr\xe5n ca 166 000 kr, BMW 330e kring 189 000 och Ford Kuga PHEV 190 000.' },
    { upTo: 249000, txt: 'VW Passat GTE fr\xe5n ca 199 000 kr och Volvo V60 Twin Engine kring 209 000.' },
    { upTo: 299000, txt: 'Škoda Superb iV fr\xe5n ca 255 000 kr, och b\xe4ttre exemplar av Passat GTE och V60.' },
    { upTo: 399000, txt: 'Volvo V90 T8 kring 300 000 kr, XC60 T8 fr\xe5n 330 000 och Toyota RAV4 plug-in 335 000.' },
    { upTo: 549000, txt: 'Ny eller n\xe4stan ny laddhybrid-SUV.' },
    { upTo: 749000, txt: 'Stor laddhybrid-SUV, ny eller n\xe5got \xe5r gammal.' },
    { upTo: Infinity, txt: 'Premiumsegmentet — stora laddhybrider med l\xe5ng elr\xe4ckvidd. H\xe4r styr utrustningsniv\xe5n priset mer \xe4n modellvalet.' }
  ] },
  suv: { ikon: '🚙', nivaer: [
    { upTo:  99000, txt: 'De \xe4ldsta SUV:arna, ca 10 \xe5r. Nissan Qashqai fr\xe5n ca 69 000 kr.' },
    { upTo: 149000, txt: 'Kompakt-SUV, ca 8–9 \xe5r. Kia Sportage fr\xe5n ca 135 000 kr och Hyundai Tucson kring 139 000.' },
    { upTo: 199000, txt: 'VW Tiguan fr\xe5n ca 172 000 kr och Volvo XC60 kring 190 000 — b\xe5da ca 8 \xe5r gamla.' },
    { upTo: 249000, txt: 'Volvo XC40 fr\xe5n ca 232 000 kr, Toyota RAV4 och Škoda Kodiaq kring 249 000.' },
    { upTo: 299000, txt: 'Nyare exemplar av XC40, RAV4 och Kodiaq — l\xe4gre m\xe4tarst\xe4llning och mer utrustning.' },
    { upTo: 399000, txt: 'Volvo XC60 fr\xe5n ca 308 000 kr och Toyota RAV4 kring 329 000, b\xe5da n\xe5gra \xe5r gamla.' },
    { upTo: 549000, txt: 'Ny eller n\xe4stan ny mellanklass-SUV.' },
    { upTo: 749000, txt: 'Stor SUV med tre s\xe4tesrader eller premiummodell, ny eller n\xe5got \xe5r gammal.' },
    { upTo: Infinity, txt: 'Premiumsegmentet — stora SUV:ar med full utrustning. H\xe4r styr utrustningsniv\xe5n priset mer \xe4n modellvalet.' }
  ] }
};

// Leasingreglaget (1 000–15 000 kr/mån) hade ingen ruta alls — nivåerna ovan är köppriser och
// gick inte att återanvända, så det läge där siffran är SVÅRAST att översätta till en bil fick
// minst hjälp. En gemensam stege räcker: månadskostnaden skiljer sig långt mindre mellan
// kategorierna än köppriset gör, eftersom avtalstid och milpaket väger tyngre än karossen.
//
// OBS: de här nivåerna är INTE mätta mot annonser, till skillnad från köpnivåerna ovan. Därför
// nämns inga modellnamn — samma regel som gäller där. De två hållpunkter som finns i koden
// stämmer: Škoda Enyaq låg på 4 850–4 980 kr/mån (BlocketPriceServiceTest) och Kia EV6 GT-Line
// på 8 295 kr/mån. Mät resten med BlocketPriceService i leasingläge (årsfiltret av, annars töms
// träfflistan) innan någon skriver in modellnamn här.
var CA_LEASING_LEVELS = { ikon: '📄', nivaer: [
  { upTo:  1999, rubrik: 'r\xe4cker s\xe4llan till privatleasing:',
    txt: 'De flesta avtal b\xf6rjar kring 2 500 kr/m\xe5n. Under det handlar det om kampanjer p\xe5 de minsta stadsbilarna.' },
  { upTo:  2999, txt: 'De minsta stadsbilarna, korta avtal och l\xe5ga milpaket.' },
  { upTo:  3999, txt: 'Sm\xe5bil eller kompakt bensinbil, ibland en liten elbil p\xe5 kampanj.' },
  { upTo:  4499, txt: 'Kompakt elbil eller v\xe4lutrustad sm\xe5bil — h\xe4r b\xf6rjar utbudet bli brett.' },
  { upTo:  6999, txt: 'Familjebil, kombi eller familjeelbil.' },
  { upTo:  9999, txt: 'Mellanklass-SUV eller st\xf6rre elbil, ofta med generösare milpaket.' },
  { upTo: Infinity, txt: 'Premiumsegmentet — stora SUV:ar och premiumelbilar. Kolla milpaketet, det styr m\xe5nadskostnaden lika mycket som bilen.' }
] };

/**
 * Nivåstegen för vald kategori — men DRIVMEDLET går före när det pekar åt ett annat håll.
 *
 * Kategorierna ekonomibil/familjebil/SUV/småbil har prisnivåer mätta på förbränningsbilar,
 * eftersom det är det normala fallet. Väljer användaren drivmedel "el" är de siffrorna fel
 * marknad: familjebil + el visade 2026-08-10 "Toyota Corolla Touring Sports från ca 209 000 kr,
 * Volvo V90 kring 220 000" för en sökning som bara kan ge elbilar, alltså samma sorts
 * självmotsägelse inom en och samma vy som budgetrutan och kategorivarningen redan städat bort.
 * Elbil och laddhybrid är redan drivmedelsbestämda och rörs inte.
 */
function caBudgetLevelsFor(kategori) {
  if (!kategori) return null;
  var fuel = document.getElementById('ca-fuel');
  var elbilssok = fuel && fuel.value === 'el'
                  && kategori !== 'elbil' && kategori !== 'laddhybrid';
  return CA_BUDGET_LEVELS[elbilssok ? 'elbil' : kategori] || null;
}

// Elementet skapas från JS, inte i HTML-snippeten: WordPress-sidan är en manuell kopia och
// hade annars saknat rutan tills snippeten klistrades in på nytt.
function caRenderEvBudgetHint() {
  var slider = document.getElementById('ca-budget-slider');
  if (!slider) return;
  var cat = document.getElementById('ca-category');
  var hint = document.getElementById('ca-ev-budget-hint');
  // Kategorier utan egna nivåer får ingen ruta alls — hellre tyst än en gissning.
  // Leasing har en egen stege: samma för alla kategorier, och i kr/mån i stället för köppris.
  var niva = caIsLeasing ? CA_LEASING_LEVELS : caBudgetLevelsFor(cat ? cat.value : null);
  if (!niva) { if (hint) hint.style.display = 'none'; return; }

  if (!hint) {
    hint = document.createElement('div');
    hint.id = 'ca-ev-budget-hint';
    hint.setAttribute('style', 'margin-top:6px;padding:7px 10px;background:rgba(139,92,246,.09);' +
      'border:1px solid rgba(139,92,246,.28);border-radius:8px;font-size:.75rem;line-height:1.45;' +
      'color:rgba(255,255,255,.68)');
    var ticks = slider.closest('.ca-field');
    ticks = ticks ? ticks.querySelector('.ca-slider-ticks') : null;
    if (ticks && ticks.parentNode) ticks.parentNode.insertBefore(hint, ticks.nextSibling);
    else slider.parentNode.parentNode.appendChild(hint);
  }
  var val = parseInt(slider.value) || 0;
  var level = niva.nivaer[niva.nivaer.length - 1];
  for (var i = 0; i < niva.nivaer.length; i++) {
    if (val <= niva.nivaer[i].upTo) { level = niva.nivaer[i]; break; }
  }
  // Över kategorins tak talar varningen redan, med samma råd ur CA_OVER_CATEGORY och med
  // varningens egen gula stil. Rutan hade upprepat meningen ordagrant direkt under den —
  // två identiska råd på rad läser som ett renderingsfel, inte som eftertryck.
  if (level.overCategory) { hint.style.display = 'none'; return; }
  hint.style.display = 'block';
  // Rubriken hörde förut ihop med texten bara när nivån svarade på "vad får jag". Nivåer som
  // svarar "fel budget" gav självmotsägelser: "150 000 kr räcker till: För lite för en
  // laddhybrid" och "600 000 kr räcker till: Långt över vad kategorin kostar". Nivån bär
  // därför sin egen rubrik när standardformuleringen inte passar.
  var enhet = caIsLeasing ? '\xa0kr/m\xe5n ' : '\xa0kr ';
  // Tusentalsavgränsaren i nivåtexterna är ett vanligt mellanslag, så "MG4 kring 195 000"
  // kunde brytas mitt i talet vid radslut ("...kring 195" / "000, VW ID.3"). Hårdmellanslag
  // vid rendering i stället för i varje sträng: en regel att minnas i stället för fyrtio.
  // Reglagets egen siffra behöver det inte, toLocaleString('sv-SE') ger redan U+00A0.
  var txt = level.txt.replace(/(\d) (?=\d{3}(\D|$))/g, '$1\xa0');
  hint.innerHTML = '<strong style="color:#c4b5fd">' + caEsc(niva.ikon) + ' ' +
    caEsc(val.toLocaleString('sv-SE')) + enhet + caEsc(level.rubrik || 'r\xe4cker till:') +
    '</strong> ' + caEsc(txt);
}

function caUpdateSliderFill() {
  var slider = document.getElementById('ca-budget-slider');
  if (!slider) return;
  var val = parseInt(slider.value);
  var min = caIsLeasing ? 1000 : 50000;
  var max = caIsLeasing ? 15000 : 1000000;
  var pct = (val - min) / (max - min) * 100;
  document.getElementById('ca-slider-fill').style.width = pct + '%';
  document.getElementById('ca-budget-display').textContent = caIsLeasing
    ? val.toLocaleString('sv-SE') + '\xa0kr/m\xe5n'
    : val.toLocaleString('sv-SE') + '\xa0kr';
  caRenderEvBudgetHint();
}

function caSetBudgetMode(mode, value) {
  caIsLeasing = (mode === 'leasing');
  var s = document.getElementById('ca-budget-slider');
  var ticks = document.getElementById('ca-slider-ticks');
  if (!s) return;
  if (caIsLeasing) {
    s.min = 1000; s.max = 15000; s.step = 250;
    s.value = (value !== undefined) ? value : caLeasingBudget;
    if (ticks) ticks.innerHTML = '<span>1k</span><span>3k</span><span>5k</span><span>8k</span><span>15k</span>';
  } else {
    s.min = 50000; s.max = 1000000; s.step = 25000;
    s.value = (value !== undefined) ? value : caKopBudget;
    if (ticks) ticks.innerHTML = '<span>50k</span><span>200k</span><span>400k</span><span>700k</span><span>1M</span>';
  }
  caUpdateSliderFill();
  // Utan den här hängde varningen kvar från köpläget efter ett byte till leasing
  caCheckMismatch();
  var kopBtn = document.getElementById('ca-mode-kop');
  var leaseBtn = document.getElementById('ca-mode-leasing');
  if (kopBtn) kopBtn.classList.toggle('ca-mode-active', !caIsLeasing);
  if (leaseBtn) leaseBtn.classList.toggle('ca-mode-active', caIsLeasing);
}

function caUpdateFuelVisibility() {
  var cat = document.getElementById('ca-category').value;
  var fuelField = document.getElementById('ca-fuel-field');
  var transField = document.getElementById('ca-transmission-field');
  var hide = (cat === 'elbil' || cat === 'laddhybrid');
  fuelField.style.display = hide ? 'none' : '';
  if (transField) transField.style.display = hide ? 'none' : '';
  if (hide) {
    document.getElementById('ca-fuel').value = 'spelar ingen roll';
    var t = document.getElementById('ca-transmission');
    if (t) t.value = 'spelar ingen roll';
  } else if (cat === 'familjebil') {
    var charger = document.getElementById('ca-charger');
    if (charger && charger.value === 'true') document.getElementById('ca-fuel').value = 'el';
  }
  caUpdateMaxAgeVisibility();
  // Drivmedlet sätts här PROGRAMMATISKT (familjebil + laddbox blir "el"), och en tilldelning
  // i JS utlöser inget change-event. Utan det här anropet visade budgetrutan bensinkombiernas
  // priser för en sökning appen själv just gjort till en elbilssökning.
  caRenderEvBudgetHint();
}

function caUpdateMaxAgeVisibility() {
  var newcarEl = document.getElementById('ca-newcar');
  var maxAgeField = document.getElementById('ca-maxage-field');
  if (!newcarEl || !maxAgeField) return;
  maxAgeField.style.display = newcarEl.value === 'true' ? 'none' : '';
}

function caSavePrefs() {
  try {
    var t = document.getElementById('ca-transmission');
    var maEl = document.getElementById('ca-maxage');
    localStorage.setItem('ca-prefs', JSON.stringify({
      category:     document.getElementById('ca-category').value,
      budget:       document.getElementById('ca-budget-slider').value,
      budgetMode:   caIsLeasing ? 'leasing' : 'köp',
      charger:      document.getElementById('ca-charger').value,
      km:           document.getElementById('ca-km').value,
      usage:        document.getElementById('ca-usage').value,
      passengers:   document.getElementById('ca-passengers').value,
      newcar:       document.getElementById('ca-newcar').value,
      fuelType:     document.getElementById('ca-fuel').value,
      transmission: t ? t.value : 'spelar ingen roll',
      maxage:       maEl ? maEl.value : '',
      cargo:        (function(){ var c = document.getElementById('ca-cargo'); return c ? c.value : '0'; })()
    }));
  } catch(e) {}
}
// ── Bagagefiltret ────────────────────────────────────────────────────────────
// Byggs från JS och inte i HTML-snippeten: WordPress-sidan är en manuell kopia, så ett nytt
// fält i snippeten syns inte förrän den klistrats in på nytt. Injektionen är idempotent
// (returnerar direkt om #ca-cargo redan finns), så snippeten kan bära fältet också utan att
// det dubbleras — samma grepp som budgetrutan och burnout-laddaren.
// Rullgardin och inte reglage, trots att en fri siffra vore exaktare: ALL slider-styling i
// snippeten är bunden till #ca-budget-slider (ID-selektorer, inklusive ::-webkit-slider-thumb
// som inte går att sätta inline), och ett injicerat <style> blockeras av sidans CSP — samma
// begränsning som burnout-laddaren fick kringgå med Web Animations API. En select ärver
// .ca-field select automatiskt och ser rätt ut på WP-sidan utan att snippeten klistras om.
// Nivåerna bär exempelbilar av samma skäl som budgetrutan gör det: "400 l" säger ingenting
// förrän det står bredvid en bil man känner igen.
// Nivåerna är satta efter KAROSSTYP och inte efter jämna hundratal: det är karossen som avgör
// vad som får plats, och intervallen nedan är typiska för respektive klass. Modellnamnen står
// kvar som ankare — "500 l" säger ingenting förrän det står bredvid en bil man känner igen.
var CA_CARGO_LEVELS = [
  { v: 0,   txt: 'Spelar ingen roll' },
  { v: 300, txt: 'Minst 300 l — halvkombi (Zoe, Yaris)' },
  { v: 400, txt: 'Minst 400 l — kompakt-SUV (Kamiq, Golf)' },
  { v: 500, txt: 'Minst 500 l — mellankombi (V60, Niro EV)' },
  { v: 600, txt: 'Minst 600 l — stor kombi eller stor SUV (Octavia Combi, MG5)' },
  { v: 700, txt: 'Minst 700 l — stor SUV eller sk\xe5pbil (V90, EV9)' }
];
function caEnsureCargoField() {
  if (document.getElementById('ca-cargo')) return;
  var pass = document.getElementById('ca-passengers');
  if (!pass) return;
  var rad = pass.closest('.ca-grid');
  if (!rad) return;
  var opts = CA_CARGO_LEVELS.map(function(n) {
    return '<option value="' + n.v + '">' + n.txt + '</option>';
  }).join('');
  var wrap = document.createElement('div');
  wrap.className = 'ca-grid';
  wrap.innerHTML =
    '<div class="ca-field">' +
      '<label>Minsta bagageutrymme</label>' +
      '<select id="ca-cargo">' + opts + '</select>' +
    '</div>';
  rad.parentNode.insertBefore(wrap, rad.nextSibling);
}
/** Kravet i liter, eller null när "Spelar ingen roll" är valt. */
function caCargoValue() {
  var el = document.getElementById('ca-cargo');
  var v = el ? parseInt(el.value) : 0;
  return v > 0 ? v : null;
}
// Kategorin heter "Elbil" och betyder rena elbilar. Etiketten rättas också från JS eftersom
// WP-sidans snippet är en manuell kopia — annars stod "Elektrisk bil" kvar till nästa inklistring.
function caFixCategoryLabels() {
  var sel = document.getElementById('ca-category');
  if (!sel) return;
  for (var i = 0; i < sel.options.length; i++) {
    if (sel.options[i].value === 'elbil') sel.options[i].textContent = 'Elbil';
  }
}
function caLoadPrefs() {
  try {
    var raw = localStorage.getItem('ca-prefs');
    if (!raw) return;
    var d = JSON.parse(raw);
    if (d.category)   document.getElementById('ca-category').value   = caCanonCat(d.category);
    caSetBudgetMode(d.budgetMode || 'köp', d.budget ? parseInt(d.budget) : undefined);
    if (d.charger)    document.getElementById('ca-charger').value     = d.charger;
    if (d.km)         document.getElementById('ca-km').value          = d.km;
    if (d.usage)      document.getElementById('ca-usage').value       = d.usage;
    if (d.passengers) document.getElementById('ca-passengers').value  = d.passengers;
    if (d.cargo) { var cg = document.getElementById('ca-cargo'); if (cg) cg.value = d.cargo; }
    if (d.newcar)     document.getElementById('ca-newcar').value      = d.newcar;
    if (d.fuelType)   document.getElementById('ca-fuel').value        = d.fuelType;
    if (d.transmission) { var t = document.getElementById('ca-transmission'); if (t) t.value = d.transmission; }
    if (d.maxage) { var ma = document.getElementById('ca-maxage'); if (ma) ma.value = d.maxage; }
    caUpdateFuelVisibility();
    caCheckMismatch();
  } catch(e) {}
}

function caReadUrlParams() {
  try {
    var p = new URLSearchParams(window.location.search);
    if (p.get('category'))   document.getElementById('ca-category').value   = caCanonCat(p.get('category'));
    caSetBudgetMode(p.get('budgetMode') || 'köp', p.get('budget') ? parseInt(p.get('budget')) : undefined);
    if (p.get('charger'))    document.getElementById('ca-charger').value     = p.get('charger');
    if (p.get('km'))         document.getElementById('ca-km').value          = p.get('km');
    if (p.get('usage'))      document.getElementById('ca-usage').value       = p.get('usage');
    if (p.get('passengers')) document.getElementById('ca-passengers').value  = p.get('passengers');
    if (p.get('newcar'))     document.getElementById('ca-newcar').value      = p.get('newcar');
    if (p.get('fuelType'))    document.getElementById('ca-fuel').value        = p.get('fuelType');
    if (p.get('transmission')) { var t = document.getElementById('ca-transmission'); if (t) t.value = p.get('transmission'); }
    if (p.get('maxage')) { var ma = document.getElementById('ca-maxage'); if (ma) ma.value = p.get('maxage'); }
    if (p.get('cargo')) { var cg = document.getElementById('ca-cargo'); if (cg) cg.value = p.get('cargo'); }
    if (p.has('category') || p.has('budget')) { caUpdateFuelVisibility(); caCheckMismatch(); }
  } catch(e) {}
}

function caSnapshotValues() {
  var t = document.getElementById('ca-transmission');
  var maSnap = document.getElementById('ca-maxage');
  caInitialValues = {
    category:     document.getElementById('ca-category').value,
    budget:       document.getElementById('ca-budget-slider').value,
    budgetMode:   caIsLeasing ? 'leasing' : 'köp',
    charger:      document.getElementById('ca-charger').value,
    km:           document.getElementById('ca-km').value,
    usage:        document.getElementById('ca-usage').value,
    passengers:   document.getElementById('ca-passengers').value,
    newcar:       document.getElementById('ca-newcar').value,
    fuelType:     document.getElementById('ca-fuel').value,
    transmission: t ? t.value : 'spelar ingen roll',
    maxage:       maSnap ? maSnap.value : '',
    cargo:        (function(){ var c = document.getElementById('ca-cargo'); return c ? c.value : '0'; })()
  };
}

function caCheckChanges() {
  if (!caHasSearched) return;
  var ids  = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel','ca-transmission','ca-maxage','ca-cargo'];
  var keys = ['category','budget','charger','km','usage','passengers','newcar','fuelType','transmission','maxage','cargo'];
  var anyChanged = false;
  ids.forEach(function(id, i) {
    var el = document.getElementById(id);
    if (!el) return;
    var field = el.closest('.ca-field');
    if (!field) return;
    var changed = el.value !== caInitialValues[keys[i]];
    field.classList.toggle('changed', changed);
    if (changed) anyChanged = true;
  });
  if ((caIsLeasing ? 'leasing' : 'köp') !== caInitialValues.budgetMode) anyChanged = true;
  var btn = document.getElementById('ca-btn');
  if (!btn) return;
  btn.classList.toggle('has-changes', anyChanged);
  btn.textContent = anyChanged ? 'Uppdatera resultat →' : 'S\xf6k igen →';
}

function caBindChangeListeners() {
  var ids = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel','ca-transmission','ca-maxage','ca-cargo'];
  ids.forEach(function(id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('change', caCheckChanges);
    el.addEventListener('input', caCheckChanges);
  });
  ['ca-category','ca-budget-slider'].forEach(function(id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('change', caCheckMismatch);
    el.addEventListener('input', caCheckMismatch);
  });
  var cat = document.getElementById('ca-category');
  var bud = document.getElementById('ca-budget-slider');
  var nc  = document.getElementById('ca-newcar');
  var chg = document.getElementById('ca-charger');
  if (cat) cat.addEventListener('change', caUpdateFuelVisibility);
  if (cat) cat.addEventListener('change', caRenderEvBudgetHint);
  if (chg) chg.addEventListener('change', caUpdateFuelVisibility);
  // Drivmedlet styr numera VILKEN nivåstege rutan läser (se caBudgetLevelsFor) — utan den här
  // raden byttes texten först när budgeten eller kategorin rördes, alltså oftast aldrig.
  var fuel = document.getElementById('ca-fuel');
  if (fuel) fuel.addEventListener('change', caRenderEvBudgetHint);
  if (bud) bud.addEventListener('input', caUpdateSliderFill);
  if (nc)  nc.addEventListener('change', caUpdateMaxAgeVisibility);
}

function caCheckMismatch() {
  var warn = document.getElementById('ca-warning');
  if (!warn) return;
  var cat = document.getElementById('ca-category').value;
  var budget = parseInt(document.getElementById('ca-budget-slider').value) || 0;
  // Leasing undantas: där är budgeten kr/mån och taket är ett köppris, så jämförelsen
  // hade varnat för fel sak (eller aldrig utlöst, vilket den inte gjorde i praktiken).
  var over = caIsLeasing ? null : CA_OVER_CATEGORY[cat];
  if (over && budget > over.over) {
    warn.style.display = 'block';
    warn.textContent = '⚠️ ' + (CA_CAT_NAMES[cat] || cat) + ' kostar s\xe4llan mer \xe4n ' +
      over.over.toLocaleString('sv-SE') + ' kr. Byt till ' + over.byt +
      ' f\xf6r att f\xe5 ut n\xe5got av pengarna.';
  } else {
    warn.style.display = 'none';
  }
}

function caSaveHistory(recommendations) {
  try {
    var tEl = document.getElementById('ca-transmission');
    var entry = {
      category:        document.getElementById('ca-category').value,
      budget:          document.getElementById('ca-budget-slider').value,
      charger:         document.getElementById('ca-charger').value,
      km:              document.getElementById('ca-km').value,
      usage:           document.getElementById('ca-usage').value,
      passengers:      document.getElementById('ca-passengers').value,
      newcar:          document.getElementById('ca-newcar').value,
      fuelType:        document.getElementById('ca-fuel').value,
      transmission:    tEl ? tEl.value : 'spelar ingen roll',
      budgetMode:      caIsLeasing ? 'leasing' : 'köp',
      timestamp:       Date.now(),
      recommendations: recommendations || []
    };
    var history = caGetHistory();
    var key = entry.category + '|' + entry.budget + '|' + entry.fuelType + '|' + entry.transmission + '|' + entry.km + '|' + entry.usage + '|' + entry.newcar;
    history = history.filter(function(h) {
      return (h.category + '|' + h.budget + '|' + h.fuelType + '|' + (h.transmission||'') + '|' + h.km + '|' + h.usage + '|' + h.newcar) !== key;
    });
    history.unshift(entry);
    history = history.slice(0, CA_HISTORY_MAX);
    localStorage.setItem(CA_HISTORY_KEY, JSON.stringify(history));
    caRenderHistory();
  } catch(e) {}
}

function caGetHistory() {
  try {
    var raw = localStorage.getItem(CA_HISTORY_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch(e) { return []; }
}

function caHistoryLabel(entry) {
  var cat = CA_CAT_NAMES[entry.category] || entry.category;
  var isLease = entry.budgetMode === 'leasing';
  var budget = parseInt(entry.budget).toLocaleString('sv-SE') + (isLease ? '\xa0kr/m\xe5n' : '\xa0kr');
  var fuel = (entry.fuelType && entry.fuelType !== 'spelar ingen roll') ? ' \xb7 ' + (CA_FUEL_NAMES[entry.fuelType] || entry.fuelType) : '';
  var trans = (entry.transmission && entry.transmission !== 'spelar ingen roll') ? ' \xb7 ' + (CA_TRANSMISSION_NAMES[entry.transmission] || entry.transmission) : '';
  var mode = isLease ? ' \xb7 Leasing' : '';
  return cat + ' \xb7 ' + budget + mode + fuel + trans;
}

function caTimeAgo(ts) {
  var diff = Date.now() - ts;
  var mins = Math.floor(diff / 60000);
  if (mins < 1) return 'nyss';
  if (mins < 60) return mins + ' min sedan';
  var hours = Math.floor(mins / 60);
  if (hours < 24) return hours + ' tim sedan';
  var days = Math.floor(hours / 24);
  return days === 1 ? 'ig\xe5r' : days + ' dagar sedan';
}

function caRenderHistory() {
  var area = document.getElementById('ca-history-area');
  if (!area) return;
  var history = caGetHistory();
  if (history.length === 0) { area.innerHTML = ''; return; }
  var chips = history.map(function(entry, i) {
    return '<button class="ca-history-chip" onclick="window._ca(\'history\',' + i + ')">' +
      '<span class="ca-history-chip-text">' + caEsc(caHistoryLabel(entry)) + '</span>' +
      '<span class="ca-history-chip-time">\xb7 ' + caTimeAgo(entry.timestamp) + '</span>' +
      '<span class="ca-history-chip-del" onclick="event.stopPropagation();window._ca(\'delHistory\',' + i + ')" title="Ta bort">\xd7</span>' +
      '</button>';
  }).join('');
  area.innerHTML = '<div class="ca-history-label">Tidigare s\xf6kningar</div><div class="ca-history-chips">' + chips + '</div>';
}

function caDeleteHistory(index) {
  try {
    var history = caGetHistory();
    history.splice(index, 1);
    localStorage.setItem(CA_HISTORY_KEY, JSON.stringify(history));
    caRenderHistory();
  } catch(e) {}
}

function caLoadFromHistory(index) {
  var history = caGetHistory();
  var entry = history[index];
  if (!entry) return;
  if (entry.category)   document.getElementById('ca-category').value   = caCanonCat(entry.category);
  if (entry.budget)   { document.getElementById('ca-budget-slider').value = entry.budget; caUpdateSliderFill(); }
  if (entry.charger)    document.getElementById('ca-charger').value     = entry.charger;
  if (entry.km)         document.getElementById('ca-km').value          = entry.km;
  if (entry.usage)      document.getElementById('ca-usage').value       = entry.usage;
  if (entry.passengers) document.getElementById('ca-passengers').value  = entry.passengers;
  if (entry.newcar)     document.getElementById('ca-newcar').value      = entry.newcar;
  caSetBudgetMode(entry.budgetMode || 'köp', entry.budget ? parseInt(entry.budget) : undefined);
  if (entry.fuelType)    document.getElementById('ca-fuel').value        = entry.fuelType;
  if (entry.transmission) { var tEl = document.getElementById('ca-transmission'); if (tEl) tEl.value = entry.transmission; }
  caUpdateFuelVisibility();
  caCheckMismatch();

  if (entry.recommendations && entry.recommendations.length > 0) {
    document.getElementById('ca-divider').style.display = 'block';
    document.getElementById('ca-results').style.display = 'block';
    document.getElementById('ca-cache-badge').style.display = 'none';
    caBudgetShortfall = null;   // historikposten bär ingen budgetdom — visa aldrig en gammal
    caNarrowCriteria = null;
    caShortfallPayload = null;
    caRenderCards(entry.recommendations);
    document.getElementById('ca-copy-btn').style.display = 'inline-block';
    document.getElementById('ca-share-result-btn').style.display = 'inline-block';
    var age = Math.round((Date.now() - entry.timestamp) / 60000);
    var ageText = age < 1 ? 'nyss' : age < 60 ? age + ' min sedan' : Math.floor(age / 60) + ' tim sedan';
    var hbadge = document.getElementById('ca-history-badge');
    hbadge.textContent = '📋 Sparad s\xf6kning (' + ageText + ')';
    hbadge.style.display = 'inline-block';
    caHasSearched = true;
    caSnapshotValues();
    document.getElementById('ca-btn').textContent = 'S\xf6k igen →';
  } else {
    caGetRecommendation();
  }
}

function caEsc(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function caBlocketUrl(title) {
  var yearMatch = title.match(/\((\d{4})\+?\)\s*$/);
  var q = title.replace(/\s*\(\d{4}\+?\)\s*$/, '').trim();
  var url = 'https://www.blocket.se/mobility/search/car?q=' + encodeURIComponent(q);
  if (yearMatch) {
    var y = parseInt(yearMatch[1]);
    url += '&year_min=' + (y - 1) + '&year_max=' + (y + 1);
  }
  return url;
}

function caBytbilUrl(title) {
  var q = title.replace(/\s*\(\d{4}\)\s*$/, '').trim();
  var parts = q.split(' ');
  var make = parts[0].toLowerCase();
  var model = parts[1] ? parts[1].toLowerCase() : '';
  return model
    ? 'https://www.bytbil.com/bil/' + make + '/' + model
    : 'https://www.bytbil.com/bil/' + make;
}

function caResetForm() {
  document.getElementById('ca-category').value   = 'smaabil';
  document.getElementById('ca-budget-slider').value = 200000;
  document.getElementById('ca-charger').value    = 'false';
  document.getElementById('ca-km').value         = 1500;
  document.getElementById('ca-usage').value      = 'pendling';
  document.getElementById('ca-passengers').value = 4;
  document.getElementById('ca-newcar').value     = 'false';
  document.getElementById('ca-fuel').value       = 'spelar ingen roll';
  var tEl = document.getElementById('ca-transmission'); if (tEl) tEl.value = 'spelar ingen roll';
  var maEl = document.getElementById('ca-maxage'); if (maEl) maEl.value = '10';
  caSetBudgetMode('köp', 200000);
  caUpdateFuelVisibility();
  caCheckMismatch();
  if (caHasSearched) caCheckChanges();
  try { localStorage.removeItem('ca-prefs'); } catch(e) {}
}

function caSkeletonHTML() {
  var card = '<div class="ca-skeleton">' +
    '<div class="ca-skeleton-line" style="width:28%;height:10px;margin-bottom:18px"></div>' +
    '<div class="ca-skeleton-line" style="width:68%;height:15px;margin-bottom:8px"></div>' +
    '<div class="ca-skeleton-line" style="width:38%;height:11px;margin-bottom:20px"></div>' +
    '<div class="ca-skeleton-line" style="width:100%;height:52px;border-radius:8px;margin-bottom:18px"></div>' +
    '<div class="ca-skeleton-line" style="width:88%"></div>' +
    '<div class="ca-skeleton-line" style="width:72%"></div>' +
    '<div class="ca-skeleton-line" style="width:80%;margin-bottom:18px"></div>' +
    '<div class="ca-skeleton-line" style="width:100%;height:36px;border-radius:8px;margin-bottom:14px"></div>' +
    '<div class="ca-skeleton-line" style="width:60%"></div>' +
    '</div>';
  return card + card + card;
}

function caCargoChip(cargo) {
  if (!cargo || cargo.cargoLiters <= 0) return '';
  var txt = '&#x1F9F3; ' + cargo.cargoLiters + ' L bagageutrymme';
  if (cargo.cargoMaxLiters > 0) txt += ' <span style="opacity:.6;font-size:.75em">(upp till ' + cargo.cargoMaxLiters + ' L)</span>';
  return '<div class="ca-cargo">' + txt + '</div>';
}

function caEvChips(ev, hp) {
  if (!ev) return '';
  var isPhev = ev.carType === 'PHEV';
  var badgeLabel = isPhev ? '&#x1F50C; Laddhybrid' : '&#x26A1; Elbil';
  var wltpTxt = ev.wltpKm > 0 ? (isPhev ? 'Elr\xe4ckvidd '+ev.wltpKm+' km' : 'WLTP '+ev.wltpKm+' km') : '';
  var head = '<div class="ca-ev-head"><span class="ca-ev-badge">'+badgeLabel+'</span>'+(wltpTxt?'<span class="ca-ev-wltp">'+wltpTxt+'</span>':'')+'</div>';
  var chips = '';
  if (ev.summerKm > 0) chips += '<span class="ca-ev-chip ca-ev-range">&#x2600;&#xFE0F; ~'+ev.summerKm+' km sommar</span>';
  if (ev.winterKm > 0) chips += '<span class="ca-ev-chip ca-ev-winter">&#x2744;&#xFE0F; ~'+ev.winterKm+' km vinter</span>';
  if (ev.daysLabel) chips += '<div style="width:100%;height:0;margin:0"></div><span class="ca-ev-chip ca-ev-charge">&#x1F50B; '+caEsc(ev.daysLabel)+'</span>';
  // Tooltiparna upprepar kortversionen av brasklappen i caRenderChargingNotice — chipset syns
  // långt innan man fäller ut rutan, och AC-talet missförstås rutinmässigt som "kräver 22 kW-box"
  if (ev.maxDcKw > 0) chips += '<span class="ca-ev-chip ca-ev-dc" title="Toppeffekt vid publik snabbladdare. Verklig effekt beror p\xe5 batteriets temperatur och laddniv\xe5, och p\xe5 vad stolpen klarar.">&#x26A1; DC '+ev.maxDcKw+' kW</span>';
  if (ev.maxAcKw > 0) chips += '<span class="ca-ev-chip ca-ev-ac" title="Toppeffekt fr\xe5n laddbox. Taket sitter i bilens ombordladdare - en kraftigare laddbox ger \xe4nd\xe5 inte mer \xe4n s\xe5 h\xe4r mycket.">&#x1F50C; AC '+ev.maxAcKw+' kW</span>';
  if (hp > 0) chips += '<span class="ca-ev-chip ca-ev-dc">&#x1F4AA; '+hp+' hk</span>';
  if (ev.batteryKwh > 0) chips += '<span class="ca-ev-chip ca-ev-bat">&#x1F50B; '+ev.batteryKwh+' kWh'+(ev.chemistry ? ' &middot; '+ev.chemistry : '')+'</span>';
  if (ev.priceKr > 0) chips += '<span class="ca-ev-chip ca-ev-price">fr\xe5n '+Math.round(ev.priceKr/1000)+' tkr</span>';
  if (ev.valueLabel) chips += '<span class="ca-ev-chip ca-ev-value">'+caEsc(ev.valueLabel)+'</span>';
  return '<div class="ca-ev">'+head+'<div class="ca-ev-chips">'+chips+'</div></div>';
}

function caValueLabelCombustion(fuel, price) {
  if (!fuel || !price || price < 30000) return '';
  var hp    = fuel.horsepower > 0 ? fuel.horsepower : 0;
  var fuelL = fuel.consumptionLiterPerMil > 0 ? fuel.consumptionLiterPerMil : 0;
  if (!hp || !fuelL) return '';
  var hpPerKr  = hp / price * 10000;
  var effBonus = Math.max(0, 8 - fuelL);
  var score = hpPerKr + effBonus;
  if (score > 10) return 'Utmärkt prisvärdhet';
  if (score > 7)  return 'Bra prisvärdhet';
  if (score > 5)  return 'Ok prisvärdhet';
  return '';
}

function caFuelChips(fuel, price) {
  if (!fuel) return '';
  var chips = '';
  var isTurbo = fuel.gearbox && /turbo|tsi|tdi|gti|gdi|crdi|vtec.*t|t-gdi/i.test(fuel.gearbox);
  var isAuto  = fuel.gearbox && /automat|dsg|cvt|pdk|steptronic|s-tronic|e-cvt/i.test(fuel.gearbox);
  if (fuel.consumptionLiterPerMil > 0) chips += '<span class="ca-ev-chip ca-ev-range">&#x26FD; ' + (fuel.consumptionLiterPerMil / 10).toFixed(2) + ' l/mil</span>';
  if (fuel.horsepower > 0) chips += '<span class="ca-ev-chip ca-ev-dc">&#x1F4AA; ' + fuel.horsepower + ' hk</span>';
  if (fuel.engineVolumeLiters > 0) chips += '<span class="ca-ev-chip ca-ev-bat">&#x1F527; ' + fuel.engineVolumeLiters.toFixed(1) + ' L</span>';
  if (isTurbo)  chips += '<span class="ca-ev-chip ca-ev-charge">&#x1F300; Turbo</span>';
  if (fuel.gearbox) {
    var gearLabel = fuel.gearbox.replace(/\s*\(.*?\)/g, '').trim();
    chips += '<span class="ca-ev-chip" style="background:rgba(167,139,250,.13)">&#x2699;&#xFE0F; ' + caEsc(gearLabel) + '</span>';
  }
  if (!chips) return '';
  var autoTag = isAuto ? '<span style="font-size:.6rem;background:rgba(52,211,153,.18);color:#6ee7b7;padding:1px 6px;border-radius:8px;margin-left:6px;font-weight:700">AUTOMAT</span>' : '';
  var head = '<div class="ca-ev-head"><span class="ca-ev-badge">&#x26FD; Bensin/Diesel</span>' + autoTag + '</div>';
  var valueLabel = caValueLabelCombustion(fuel, price);
  if (valueLabel) chips += '<span class="ca-ev-chip ca-ev-value">' + caEsc(valueLabel) + '</span>';
  return '<div class="ca-ev">' + head + '<div class="ca-ev-chips">' + chips + '</div></div>';
}

// Kategori-brasklapp för laddhybrider. Visas EN gång ovanför korten, inte per kort — identisk
// text på tre kort läser som ett renderingsfel, och varningen gäller kategorin och inte den
// enskilda bilen. Sätts som syskon före #ca-cards eftersom kortlistan är ett grid: en banner
// som första barn hade tagit en egen kolumnruta.
function caRenderPhevTaxNotice() {
  var host = document.getElementById('ca-cards');
  if (!host) return;
  var existing = document.getElementById('ca-phev-tax-notice');
  if (caCurrentCategory !== 'laddhybrid') { if (existing) existing.parentNode.removeChild(existing); return; }
  if (existing) return;
  var el = document.createElement('div');
  el.id = 'ca-phev-tax-notice';
  el.setAttribute('style', 'margin:0 0 16px;padding:12px 14px;background:rgba(251,191,36,.08);' +
    'border:1px solid rgba(251,191,36,.35);border-radius:10px;font-size:.82rem;line-height:1.5;' +
    'color:rgba(255,255,255,.8)');
  el.innerHTML =
    '<strong style="color:#fbbf24">&#x26A0; Ny laddhybridsskatt fr\xe5n 2027</strong><br>' +
    'EU:s ber\xe4kning av laddhybriders koldioxidutsl\xe4pp sk\xe4rps 1 januari 2027 — f\xf6rbrukningen ' +
    'm\xe4ts d\xe5 med b\xe5de fulladdat och n\xe4stan tomt batteri, s\xe5 samma bil f\xe5r ett h\xf6gre ' +
    'officiellt CO₂-v\xe4rde och d\xe4rmed h\xf6gre fordonsskatt. Det g\xe4ller <strong>nya</strong> ' +
    'laddhybrider som registreras fr\xe5n 2027 — en redan registrerad bil beh\xe5ller sin skatt. ' +
    'Bilar med litet batteri och under ca 5–6 mils elr\xe4ckvidd drabbas h\xe5rdast. ' +
    '<a href="https://carup.se/chocken-bilskatt-kan-oka-med-1300/" target="_blank" rel="noopener" ' +
    'style="color:#fbbf24;text-decoration:underline">K\xe4lla: CarUp &#x2192;</a>';
  host.parentNode.insertBefore(el, host);
}

// Brasklapp för DC/AC-effekterna på korten. Båda talen är TOPPEFFEKT under ideala förhållanden
// och läses annars som "så snabbt laddar bilen alltid". AC-talet är det som oftast missförstås:
// det är bilens ombordladdare som sätter taket, så en 22 kW-laddbox ger ändå bara 11 kW till en
// bil som klarar 11. Hopfälld som standard — den gäller varje elbilskort och ska förklara på
// begäran, inte tränga undan resultaten. Samma placering som laddhybridsnotisen: syskon före
// #ca-cards, eftersom kortlistan är ett grid och en banner som första barn tar en kolumnruta.
function caRenderChargingNotice(recs) {
  var host = document.getElementById('ca-cards');
  if (!host) return;
  var existing = document.getElementById('ca-charging-notice');
  var show = (recs || []).some(function(r) {
    return r.evSpec && (r.evSpec.maxDcKw > 0 || r.evSpec.maxAcKw > 0);
  });
  if (!show) { if (existing) existing.parentNode.removeChild(existing); return; }
  if (existing) return;

  var el = document.createElement('details');
  el.id = 'ca-charging-notice';
  el.setAttribute('style', 'margin:0 0 16px;padding:10px 14px;background:rgba(56,189,248,.06);' +
    'border:1px solid rgba(56,189,248,.28);border-radius:10px;font-size:.82rem;line-height:1.55;' +
    'color:rgba(255,255,255,.78)');
  el.innerHTML =
    // Ingen list-style:none — den inbyggda triangeln är det enda som visar att rutan går att
    // fälla ut, och den vänder sig själv när den öppnas
    '<summary style="cursor:pointer;color:#38bdf8;font-weight:600">' +
      '&#x26A1; Vad betyder DC max och AC max?</summary>' +
    '<div style="margin-top:10px">' +
      '<strong style="color:rgba(255,255,255,.92)">DC max &mdash; likstr\xf6m, snabbladdning</strong><br>' +
      'H\xf6gsta effekt bilen kan ta emot vid en publik snabbladdare. Taket s\xe4tts av batteriets ' +
      'kemi, temperatur och h\xe4lsa, och ligger i praktiken mellan ca 50 kW f\xf6r \xe4ldre eller ' +
      'enklare modeller och 250&#x2013;350 kW f\xf6r modern snabbladdningsteknik. H\xf6gre v\xe4rde ger ' +
      'betydligt kortare stopp p\xe5 l\xe5ngresa &#x2013; typiskt 10&#x2013;80\xa0% p\xe5 20&#x2013;30 minuter &#x2013; ' +
      'f\xf6rutsatt att laddstolpen kan leverera lika mycket.' +
      '<div style="height:8px"></div>' +
      '<strong style="color:rgba(255,255,255,.92)">AC max &mdash; v\xe4xelstr\xf6m, normalladdning</strong><br>' +
      'H\xf6gsta effekt bilen klarar fr\xe5n en laddbox eller normalladdstolpe. Den gr\xe4nsen sitter i ' +
      'bilens interna ombordladdare, inte i elen: vanliga v\xe4rden \xe4r 11 kW (trefas 16\xa0A) och ' +
      '22 kW (trefas 32\xa0A). Har bilen AC max 11 kW spelar det ingen roll om laddboxen klarar ' +
      '22 kW &#x2013; bilen laddar \xe4nd\xe5 i h\xf6gst 11 kW.' +
      '<div style="height:8px"></div>' +
      '<strong style="color:rgba(255,255,255,.92)">Varf\xf6r AC max s\xe4llan avg\xf6r valet</strong><br>' +
      'AC-laddning sker n\xe4stan alltid hemma eller p\xe5 jobbet, och d\xe5 st\xe5r bilen parkerad i ' +
      'timmar \xe4nd\xe5. 11 kW fyller ett normalstort batteri p\xe5 6&#x2013;8 timmar, allts\xe5 \xf6ver en ' +
      'natt &#x2013; att bilen skulle klara 22 kW \xe4ndrar inget n\xe4r den st\xe5r stilla till morgonen. ' +
      'De flesta svenska hemmainstallationer ger dessutom 11 kW; 22 kW kr\xe4ver s\xe4rskild el dragen ' +
      'till huset.' +
      '<div style="height:6px"></div>' +
      'Viktigare att j\xe4mf\xf6ra \xe4r <strong>DC-effekten</strong> (hur korta pauserna blir p\xe5 ' +
      'l\xe5ngresa), <strong>r\xe4ckvidden</strong> (hur ofta du beh\xf6ver stanna alls) och ' +
      '<strong>f\xf6rbrukningen per mil</strong> (vad bilen kostar att \xe4ga \xf6ver tid).' +
      '<div style="height:8px"></div>' +
      '<span style="color:rgba(255,255,255,.55)">B\xe5da talen \xe4r toppeffekt under ideala ' +
      'f\xf6rh\xe5llanden. Verklig effekt sjunker med kallt batteri och stigande laddniv\xe5 &#x2013; ' +
      'sista biten till 100\xa0% \xe4r alltid l\xe5ngsam.</span>' +
    '</div>';
  host.parentNode.insertBefore(el, host);
}

// Budgetbanderoll. Servern har redan gjort ett omförsök och ändå inte hittat en enda bil
// inom taket — då är kriterierna omöjliga, typiskt låg budget plus hårt ålderskrav. Utan
// den här raden läser tre bilar till dubbla priset som en trasig rekommendation i stället
// för som ett svar på en omöjlig fråga. Samma placering som laddhybridsnotisen.
function caRenderBudgetNotice() {
  var host = document.getElementById('ca-cards');
  if (!host) return;
  var existing = document.getElementById('ca-budget-notice');
  if (existing) existing.parentNode.removeChild(existing);
  if (!caBudgetShortfall) return;

  var kr = function(n) { return Number(n).toLocaleString('sv-SE') + '\xa0kr'; };
  // Nybilssök mäts mot nypriset, inte mot annonserna — då är "på Blocket just nu" fel besked,
  // och rådet "tillåt äldre bilar" är meningslöst när användaren bett om en ny bil
  var nybil = caShortfallNewCar;
  var orsak = nybil
    ? 'Din budget p\xe5 ' + kr(caShortfallBudget) + ' r\xe4cker inte till en NY bil i den h\xe4r kategorin.'
    : caShortfallMaxAge
      ? 'Din budget p\xe5 ' + kr(caShortfallBudget) + ' r\xe4cker inte till en bil som \xe4r max ' +
        caShortfallMaxAge + ' \xe5r gammal.'
      : 'Din budget p\xe5 ' + kr(caShortfallBudget) + ' r\xe4cker inte till n\xe5gon bil i den h\xe4r kategorin.';
  var kalla = nybil ? ' som ny. ' : ' p\xe5 Blocket just nu. ';
  var rad = nybil
    ? 'H\xf6j budgeten, v\xe4lj en billigare kategori — eller s\xf6k begagnat, d\xe4r r\xe4cker pengarna l\xe4ngre.'
    : caShortfallMaxAge
      ? 'H\xf6j budgeten, till\xe5t \xe4ldre bilar eller v\xe4lj en billigare kategori.'
      : 'H\xf6j budgeten eller v\xe4lj en billigare kategori.';

  var el = document.createElement('div');
  el.id = 'ca-budget-notice';
  el.setAttribute('style', 'margin:0 0 16px;padding:12px 14px;background:rgba(248,113,113,.08);' +
    'border:1px solid rgba(248,113,113,.35);border-radius:10px;font-size:.82rem;line-height:1.55;' +
    'color:rgba(255,255,255,.8)');
  el.innerHTML =
    '<strong style="color:#fca5a5">&#x26A0; F\xf6rslagen ligger \xf6ver din budget</strong><br>' +
    caEsc(orsak) + ' Billigaste bilen som matchar dina \xf6vriga krav b\xf6rjar p\xe5 ' +
    '<strong>' + caEsc(kr(caBudgetShortfall)) + '</strong>' + kalla +
    'Korten nedan visas \xe4nd\xe5 s\xe5 du ser vad som finns — men de \xe4r allts\xe5 dyrare \xe4n du angav. ' +
    caEsc(rad) +
    '<div id="ca-budget-alts" style="margin-top:9px"></div>';
  host.parentNode.insertBefore(el, host);
  caFetchBudgetAlternatives();
}

// Banderoll för snäva krav. Prompten kräver tre bilar, men regelvakterna får fälla — och gör
// det rätt: familjeelbil + 400 l bagage + 200 000 kr gav live 2026-08-10 ett enda kort (MG5),
// eftersom MG4 (363 l) och Niro EV (349 l) inte klarade bagagekravet. Utan den här raden läser
// ett ensamt kort som att appen krånglar i stället för som ett svar på en hård fråga.
// Visas ALDRIG samtidigt som budgetbanderollen: servern skickar bara det ena beskedet, och två
// rutor med överlappande budskap läser som ett renderingsfel (samma lärdom som budgetrutan gav).
function caRenderNarrowNotice() {
  var host = document.getElementById('ca-cards');
  if (!host) return;
  var existing = document.getElementById('ca-narrow-notice');
  if (existing) existing.parentNode.removeChild(existing);
  if (!caNarrowCriteria || !caNarrowCriteria.krav || !caNarrowCriteria.krav.length) return;

  var n = caNarrowCriteria.kvar;
  // Noll bilar är inte ett fel utan ett svar: servern returnerar tomt när ingen bil klarade
  // kraven, i stället för det tekniska "AI:n föreslog en bilmodell som inte kunde verifieras"
  // som skyllde på AI:n för en hård fråga.
  var rubrik = n === 0 ? 'Ingen bil matchade alla dina krav'
             : n === 1 ? 'Bara en bil matchade alla dina krav'
                       : n + ' bilar matchade alla dina krav';
  var el = document.createElement('div');
  el.id = 'ca-narrow-notice';
  el.setAttribute('style', 'margin:0 0 16px;padding:12px 14px;background:rgba(251,191,36,.08);' +
    'border:1px solid rgba(251,191,36,.35);border-radius:10px;font-size:.82rem;line-height:1.55;' +
    'color:rgba(255,255,255,.8)');
  el.innerHTML =
    // &#x2139; ensamt renderas som ett vanligt serif-"i" och läser som en stray bokstav —
    // variantväljaren FE0F tvingar emojiformen, samma som budgetrutans &#x26A0; får gratis
    '<strong style="color:#fcd34d">&#x2139;&#xFE0F; ' + caEsc(rubrik) + '</strong><br>' +
    (n === 0
      ? 'Alla f\xf6rslag f\xf6ll p\xe5 minst ett av kraven, s\xe5 vi visar hellre inget \xe4n en bil som inte st\xe4mmer. '
      : 'Vi visar hellre f\xe4rre bilar som st\xe4mmer \xe4n tre d\xe4r n\xe5gra inte g\xf6r det. ') +
    'Kraven som gallrade: ' + caEsc(caNarrowCriteria.krav.join(' \xb7 ')) + '. ' +
    'L\xe4tta p\xe5 ett av dem f\xf6r fler alternativ.';
  host.parentNode.insertBefore(el, host);
}

// Vad räcker budgeten faktiskt till? Hämtas lazy och bara när banderollen visas, eftersom
// svaret kostar ett eget Groq-anrop plus Blocket-uppslag. Poängen: "100 000 kr räcker inte"
// är korrekt men torftigt när svaret "för de pengarna är det 5–10 år gamla elbilar, till
// exempel MG ZS EV från 99 000 kr" går att räkna fram ur riktiga annonser.
function caFetchBudgetAlternatives() {
  var box = document.getElementById('ca-budget-alts');
  if (!box || !caShortfallPayload) return;
  var headers = { 'Content-Type': 'application/json' };
  var t = localStorage.getItem('ca_token') || '';
  if (t) headers['Authorization'] = 'Bearer ' + t;

  fetch(CA_API_BASE + '/api/budget-alternatives', {
    method: 'POST', headers: headers, body: JSON.stringify(caShortfallPayload)
  })
    .then(function(res) { return res.ok ? res.json() : null; })
    .then(function(d) {
      if (!d || !d.alternatives || !d.alternatives.length) return;
      var nu = new Date().getFullYear();
      var alder = [];
      var items = d.alternatives.map(function(a) {
        var m = /\((\d{4})\)/.exec(a.title || '');
        if (m) alder.push(nu - parseInt(m[1], 10));
        return '<li style="margin:2px 0"><strong>' + caEsc(a.title) + '</strong> fr\xe5n ' +
               caEsc(Number(a.fromKr).toLocaleString('sv-SE')) + '\xa0kr</li>';
      }).join('');
      var spann = '';
      if (alder.length) {
        var min = Math.min.apply(null, alder), max = Math.max.apply(null, alder);
        spann = ' Det \xe4r ' + (min === max ? 'ca ' + min : 'ca ' + min + '–' + max) +
                ' \xe5r gamla bilar — \xe4ldre \xe4n ditt krav, men de finns i din prisklass.';
      }
      box.innerHTML =
        '<div style="font-weight:600;color:rgba(255,255,255,.85)">Det h\xe4r r\xe4cker budgeten till:</div>' +
        '<ul style="margin:4px 0 0;padding-left:18px;color:rgba(255,255,255,.72)">' + items + '</ul>' +
        (spann ? '<div style="margin-top:4px;color:rgba(255,255,255,.6)">' + caEsc(spann.trim()) + '</div>' : '');
    })
    .catch(function() {});
}

function caRenderCards(recommendations) {
  caRestoreResults();
  var container = document.getElementById('ca-cards');
  container.classList.add('fading');
  caRenderPhevTaxNotice();
  caRenderChargingNotice(recommendations);
  caRenderBudgetNotice();
  caRenderNarrowNotice();
  setTimeout(function() {
    container.classList.remove('fading');
    container.innerHTML = recommendations.map(function(r, i) {
      var prosHtml = (r.pros || []).map(function(p) { return '<li>' + caEsc(p) + '</li>'; }).join('');
      // Leasing: verkliga privatleasingannonser (blocketPrice är kr/mån här), annars AI:ns
      // kr/mån. Saknas båda sägs det rakt ut — förr räknades listpris/85 fram i stället.
      // Köp: Blocket-priset är sanningen när det finns; AI-priset bara som fallback.
      var priceRow;
      if (caIsLeasing) {
        priceRow = '<div class="ca-price"><span style="font-size:.62rem;font-weight:600;color:rgba(255,255,255,.35);margin-right:4px;text-transform:uppercase;letter-spacing:.04em">Leasing</span>' +
          (r.blocketPrice ? '🔵 ' + caEsc(r.blocketPrice)
            : caParseLeaseMonthly(r.price) ? caEsc(r.price)
            : '<span style="color:rgba(255,255,255,.45)">ingen leasing hittad</span>') + '</div>';
      } else if (r.blocketPrice) {
        priceRow = '<div class="ca-price"><span style="font-size:.62rem;font-weight:600;color:rgba(255,255,255,.35);margin-right:4px;text-transform:uppercase;letter-spacing:.04em">Pris</span>🔵 ' + caEsc(r.blocketPrice) + '</div>';
      } else {
        priceRow = '<div class="ca-price"><span style="font-size:.62rem;font-weight:600;color:rgba(255,255,255,.35);margin-right:4px;text-transform:uppercase;letter-spacing:.04em">Pris</span>' + caEsc(r.price) + '</div>';
      }
      return '<div class="ca-card ca-card-'+(i+1)+'">' +
        // Remsan var 80 px hög med en egen ljus platta som bakgrund. Med object-fit:contain
        // blev ett 16:9-foto ~142 px brett i ett 814 px brett fält — 83 % av ytan var tom
        // platta, vilket läste som ett fel snarare än ett designval. Nu: dubbelt så hög remsa
        // (fotot blir ~2× större) och genomskinlig bakgrund så överskottsytan smälter in i
        // kortet i stället för att bilda ett eget grått band. contain behålls — Wikipedia-
        // bilderna har vitt spretiga proportioner och cover hade beskurit bilar på måfå.
        '<div id="ca-img-wrap-'+i+'" style="width:100%;height:150px;overflow:hidden;border-radius:inherit;background:transparent;margin-bottom:0;display:none">' +
          '<img id="ca-img-'+i+'" src="" alt="'+caEsc(r.title)+'" style="width:100%;height:100%;object-fit:contain;object-position:center center;transition:opacity .4s">' +
        '</div>' +
        '<div class="ca-card-head">' +
          '<span class="ca-card-num">Bil ' + (i + 1) + '</span>' +
          '<h3>' + caEsc(r.title) + '</h3>' +
          priceRow +
        '</div>' +
        '<div class="ca-card-body">' +
          '<div class="ca-why">' + caEsc(r.whyRecommended) + '</div>' +
          '<span class="ca-section-label">F\xf6rdelar</span>' +
          '<ul class="ca-pros">' + prosHtml + '</ul>' +
          '<hr class="ca-divider">' +
          '<span class="ca-section-label">Nackdel</span>' +
          '<div class="ca-con">&#x26A0; ' + caEsc(r.con) + '</div>' +
          '<span class="ca-section-label">Passar dig</span>' +
          '<div class="ca-fit">' + caEsc(r.fitSummary) + '</div>' +
          (r.expertOpinion ? '<hr class="ca-divider"><div class="ca-expert"><span class="ca-expert-name">&#x1F3AF; Bilexpert</span><span class="ca-expert-text">'+caEsc(r.expertOpinion)+'</span></div>' : '') +
          (r.safetyRating ? '<div class="ca-safety"><span class="ca-safety-badge">Euro NCAP</span><span class="ca-safety-text">'+caEsc(r.safetyRating)+'</span></div>' : '') +
          '<div id="ca-insights-'+i+'"></div>' +
          (r.evSpec ? caEvChips(r.evSpec, r.horsepower) : '') +
          (r.fuelSpec ? caFuelChips(r.fuelSpec, caParsePrice(r.price)) : '') +
          (r.cargoSpec ? caCargoChip(r.cargoSpec) : '') +
          caTcoHtml(r, caCurrentKm) +
          '<button class="ca-ask-btn" data-idx="' + i + '" data-title="' + caEsc(r.title) + '">&#x1F4AC; Fr\xe5ga om Bil ' + (i + 1) + ' &mdash; ' + caEsc(r.title.replace(/\s*\(\d{4}\)\s*$/, '')) + '</button>' +
          '<div class="ca-market-links">' +
            '<a class="ca-blocket-btn" href="' + caBlocketUrl(r.title) + '" target="_blank" rel="noopener">Blocket &#x2192;</a>' +
            '<a class="ca-bytbil-btn" href="' + caBytbilUrl(r.title) + '" target="_blank" rel="noopener">Bytbil &#x2192;</a>' +
          '</div>' +
          '<div id="ca-video-' + i + '"></div>' +
          '<div class="ca-fb" data-title="' + caEsc(r.title) + '" style="margin-top:12px;padding:10px 12px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:10px;display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap">' +
            '<span style="font-size:.8rem;font-weight:600;color:rgba(255,255,255,.7)">Var f\xf6rslaget bra?</span>' +
            '<div style="display:flex;gap:8px">' +
              '<button class="ca-fb-btn" data-vote="up" title="Bra f\xf6rslag" style="background:rgba(52,211,153,.1);border:1px solid rgba(52,211,153,.35);border-radius:8px;padding:5px 14px;cursor:pointer;font-size:1rem;line-height:1.3;transition:transform .15s,background .15s">&#x1F44D;</button>' +
              '<button class="ca-fb-btn" data-vote="down" title="D\xe5ligt f\xf6rslag" style="background:rgba(248,113,113,.1);border:1px solid rgba(248,113,113,.35);border-radius:8px;padding:5px 14px;cursor:pointer;font-size:1rem;line-height:1.3;transition:transform .15s,background .15s">&#x1F44E;</button>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '</div>';
    }).join('') +
      '<div class="ca-dealer-tip" style="grid-column:1/-1;margin-top:14px;padding:10px 14px;border:1px solid rgba(255,255,255,.12);border-radius:10px;font-size:.8rem;color:rgba(255,255,255,.55);line-height:1.5">' +
        '&#x1F4A1; <strong style="color:rgba(255,255,255,.75)">Tips vid k\xf6p fr\xe5n bilhandlare:</strong> kolla firmans omd\xf6men p\xe5 ' +
        '<a href="https://se.trustpilot.com/categories/cars_trucks" target="_blank" rel="noopener" style="color:#7ec8ff;text-decoration:underline">Trustpilot</a>' +
        ' innan du sl\xe5r till &mdash; d\xe4r betygs\xe4tter riktiga kunder svenska bilfirmor.' +
      '</div>' +
      '<div class="ca-ai-disclaimer" style="grid-column:1/-1;margin-top:8px;padding:0 4px;font-size:.72rem;color:rgba(255,255,255,.35);line-height:1.4">' +
        '&#x1F916; F\xf6rslagen \xe4r AI-genererade. Priser stäms av mot Blocket-annonser men fritext (f\xf6rdelar, expertomd\xf6me m.m.) kan innehålla fel &mdash; dubbelkolla alltid mot annonsen innan k\xf6p.' +
      '</div>';
    caFetchCarImages(recommendations);
    caFetchInsights(recommendations);
    caFetchVideos(recommendations);
    caRenderCompare(recommendations);
    caWireFeedback(container);
    container.querySelectorAll('.ca-ask-btn').forEach(function(btn) {
      btn.addEventListener('click', function() {
        var title = btn.dataset.title;
        var shortTitle = title.replace(/\s*\(\d{4}\)\s*$/, '');
        container.querySelectorAll('.ca-card').forEach(function(c) { c.classList.remove('ca-card-selected'); });
        container.querySelectorAll('.ca-ask-btn').forEach(function(b) {
          b.classList.remove('ca-ask-btn-active');
          b.innerHTML = '&#x1F4AC; Fr\xe5ga om ' + caEsc(b.dataset.title.replace(/\s*\(\d{4}\)\s*$/, ''));
        });
        btn.closest('.ca-card').classList.add('ca-card-selected');
        btn.classList.add('ca-ask-btn-active');
        btn.innerHTML = '&#x2713; Vald &mdash; fr\xe5ga mig om ' + caEsc(shortTitle);
        if (window.caChatFocusCar) window.caChatFocusCar(parseInt(btn.dataset.idx), title);
      });
    });
  }, 250);
}

// Hämtar DB-insikter (Teknikens Värld, Vi Bilägare, car.info-ägare, Folksam m.fl.) per bilkort
// och visar dem med källhänvisning under expertblocket. Inline-styles (ingen CSS i WP-snippeten
// behövs). Tomt svar = sektionen visas inte alls.
function caFetchInsights(recommendations) {
  recommendations.forEach(function(r, i) {
    var box = document.getElementById('ca-insights-' + i);
    if (!box || !r.title) return;
    fetch(CA_API_BASE + '/api/insights?car=' + encodeURIComponent(r.title))
      .then(function(res) { return res.ok ? res.json() : []; })
      .then(function(list) {
        if (!list || !list.length) return;
        var items = list.map(function(ins) {
          var rating = ins.rating ? ' <span style="color:#fbbf24;font-weight:600">' + ins.rating + '/10</span>' : '';
          return '<div style="margin-bottom:7px;font-size:.8rem;line-height:1.55;color:rgba(255,255,255,.68)">' +
                 '&#x201C;' + caEsc(ins.insight) + '&#x201D;' + rating +
                 ' <span style="color:rgba(255,255,255,.4);font-style:italic;white-space:nowrap">&mdash; ' + caEsc(ins.expert) + '</span></div>';
        }).join('');
        box.innerHTML =
          '<div style="background:rgba(251,191,36,.05);border:1px solid rgba(251,191,36,.18);border-radius:10px;padding:11px 14px;margin-bottom:14px">' +
            '<span style="font-size:.7rem;font-weight:700;text-transform:uppercase;letter-spacing:.08em;color:#fcd34d;display:block;margin-bottom:6px">&#x1F4F0; Vad experterna s\xe4ger</span>' +
            items +
          '</div>';
      })
      .catch(function() {});
  });
}

// Bilrecension på YouTube, hämtad efter att korten renderats — samma lazy-mönster som
// insikterna. Saknas video (eller API-nyckel) ritas ingen ruta alls, aldrig en tom platshållare.
// Thumbnailen ligger på i.ytimg.com och laddas lazy så den inte konkurrerar med bilbilderna.
function caFetchVideos(recommendations) {
  recommendations.forEach(function(r, i) {
    var box = document.getElementById('ca-video-' + i);
    if (!box || !r.title) return;
    fetch(CA_API_BASE + '/api/car-video?car=' + encodeURIComponent(r.title))
      .then(function(res) { return res.ok ? res.json() : null; })
      .then(function(v) {
        if (!v || !v.videoId) return;
        // Ett block: klickbar videorad överst, betyget som en avdelad rad under. Två
        // separata rutor tog för mycket höjd längst ned på ett redan innehållstungt kort.
        box.innerHTML =
          '<div style="margin-top:10px;background:rgba(255,0,0,.06);border:1px solid rgba(255,0,0,.22);' +
               'border-radius:10px;overflow:hidden">' +
            '<a href="' + caEsc(v.url) + '" target="_blank" rel="noopener" ' +
               'style="display:flex;gap:11px;align-items:center;padding:9px 11px;text-decoration:none;' +
               'transition:background .15s" ' +
               'onmouseover="this.style.background=\'rgba(255,0,0,.09)\'" ' +
               'onmouseout="this.style.background=\'transparent\'">' +
              '<img src="' + caEsc(v.thumbnail) + '" alt="" loading="lazy" width="86" height="48" ' +
                   'style="width:86px;height:48px;object-fit:cover;border-radius:6px;flex-shrink:0;background:rgba(255,255,255,.06)">' +
              '<span style="min-width:0">' +
                '<span style="display:block;font-size:.74rem;font-weight:700;color:#ff6b6b;text-transform:uppercase;letter-spacing:.05em">' +
                  '&#x25B6; Se bilrecension p\xe5 YouTube</span>' +
                '<span style="display:block;font-size:.76rem;color:rgba(255,255,255,.62);line-height:1.35;margin-top:2px;' +
                      'overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + caEsc(v.title) + '</span>' +
                (v.channel ? '<span style="display:block;font-size:.68rem;color:rgba(255,255,255,.38);margin-top:1px">' +
                  caEsc(v.channel) + '</span>' : '') +
              '</span>' +
            '</a>' + caSentimentHTML(v.sentiment) +
          '</div>';
      })
      .catch(function() {});
  });
}

// YouTube-betyg: vad kommentarerna under recensionen säger om BILEN (inte om videon).
// Rutan ritas bara när Groq hittat tillräckligt många bilrelaterade kommentarer —
// ett betyg byggt på en handfull kommentarer är sämre än inget betyg.
function caSentimentHTML(s) {
  if (!s || !s.verdict) return '';
  var tone = s.verdict === 'bra'
        ? { col: '#6ee7b7', pill: 'rgba(52,211,153,.14)', icon: '👍' }
      : s.verdict === 'daligt'
        ? { col: '#fca5a5', pill: 'rgba(248,113,113,.14)', icon: '👎' }
        : { col: '#fcd34d', pill: 'rgba(251,191,36,.14)', icon: '⚖️' };
  // Sitter inuti videoblocket: bara en avdelande linje, ingen egen ram eller bakgrund.
  // Domen bär färgen som en pill, summaryn får hela bredden och full radhöjd.
  return '<div style="border-top:1px solid rgba(255,255,255,.09);padding:8px 11px;' +
              'background:rgba(0,0,0,.13)">' +
      '<div style="display:flex;align-items:baseline;gap:8px;flex-wrap:wrap">' +
        '<span style="font-size:.82rem;font-weight:700;color:' + tone.col + ';background:' + tone.pill +
              ';border-radius:999px;padding:2px 9px;white-space:nowrap">' +
          tone.icon + '\xa0' + caEsc(s.label) + '</span>' +
        '<span style="font-size:.7rem;color:rgba(255,255,255,.42)">' +
          'i ' + caEsc(String(s.commentCount)) + ' kommentarer om bilen</span>' +
      '</div>' +
      (s.summary ? '<div style="margin-top:5px;font-size:.78rem;line-height:1.5;color:rgba(255,255,255,.66)">' +
        caEsc(s.summary) + '</div>' : '') +
    '</div>';
}

// Tumme upp/ner per bilkort — en röst per bil sparas i localStorage så samma bil inte röstas om
function caWireFeedback(container) {
  container.querySelectorAll('.ca-fb').forEach(function(box) {
    var title = box.dataset.title;
    function markVoted(v) {
      box.innerHTML = '<span style="font-size:.8rem;color:' +
        (v === 'up' ? '#6ee7b7' : 'rgba(255,255,255,.55)') + '">' +
        (v === 'up' ? '&#x1F44D;' : '&#x1F44E;') + ' Tack f\xf6r din feedback!</span>';
    }
    var voted = null;
    try { voted = localStorage.getItem('ca_fb_' + title); } catch (e) {}
    if (voted) { markVoted(voted); return; }
    box.querySelectorAll('.ca-fb-btn').forEach(function(btn) {
      btn.addEventListener('mouseenter', function() { btn.style.transform = 'scale(1.12)'; });
      btn.addEventListener('mouseleave', function() { btn.style.transform = 'scale(1)'; });
      btn.addEventListener('click', function() {
        var vote = btn.dataset.vote;
        fetch(CA_API_BASE + '/api/feedback', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ carTitle: title, vote: vote })
        }).catch(function() {});
        try { localStorage.setItem('ca_fb_' + title, vote); } catch (e) {}
        markVoted(vote);
      });
    });
  });
}

function caRenderCompare(recs, targetEl) {
  var cmp = targetEl || document.getElementById('ca-compare');
  if (!cmp || !recs || recs.length < 2) return;
  var hasEv   = recs.some(function(r){ return r.evSpec; });
  var hasFuel = recs.some(function(r){ return r.fuelSpec; });
  var S = 'style="';
  var th = S+'padding:11px 14px;text-align:left;font-size:.8rem;border-bottom:1px solid rgba(255,255,255,.08)"';
  var td = S+'padding:10px 14px;vertical-align:top;border-bottom:1px solid rgba(255,255,255,.04)"';
  var tl = S+'padding:10px 14px;font-size:.72rem;font-weight:700;color:rgba(255,255,255,.3);white-space:nowrap;vertical-align:middle;letter-spacing:.03em;border-bottom:1px solid rgba(255,255,255,.04)"';
  function cell(r, fn) { return '<td '+td+'>'+fn(r)+'</td>'; }
  function evCell(r, fn) {
    if (!r.evSpec) return '<td '+td+'><span style="color:rgba(255,255,255,.2)">&#x2013;</span></td>';
    return '<td '+td+'>'+fn(r.evSpec)+'</td>';
  }
  function chip(text, color) { return '<span style="display:inline-block;font-size:.75rem;font-weight:700;padding:3px 9px;border-radius:20px;background:'+color+';white-space:nowrap">'+text+'</span>'; }
  var accentColors = ['#a78bfa','#38bdf8','#34d399'];
  var headerCells = recs.map(function(r, i) {
    var short = r.title.replace(/\s*\(\d{4}\)\s*$/, '').split(' ').slice(0, 4).join(' ');
    var col = accentColors[i] || '#a78bfa';
    return '<th '+th+'><span style="font-size:.65rem;font-weight:800;color:'+col+';text-transform:uppercase;letter-spacing:.08em">Bil '+(i+1)+'</span><br><span style="font-weight:700;color:#e2e8f0;font-size:.82rem">'+caEsc(short)+'</span></th>';
  }).join('');
  var rows = [
    // Prisraden (AI:ns kalkyl) borttagen — Blocket nu är sanningen; AI-priset visas bara som fallback
    { label: '&#x1F535; Blocket nu', fn: function(r){
      if (!r.blocketPrice) return '<span style="color:#a5f3fc;font-weight:700;font-size:.85rem">'+caEsc(r.price)+'</span>';
      return '<a href="'+caBlocketUrl(r.title)+'" target="_blank" rel="noopener" style="color:#60a5fa;font-size:.8rem;font-weight:600;text-decoration:none">'+caEsc(r.blocketPrice)+'&#x2192;</a>';
    }},
    { label: '&#x2714; F\xf6rdelar', fn: function(r){
      return '<ul style="margin:0;padding-left:14px">'+(r.pros||[]).map(function(p){
        return '<li style="font-size:.77rem;color:rgba(255,255,255,.7);margin-bottom:3px">'+caEsc(p)+'</li>';
      }).join('')+'</ul>';
    }},
    { label: '&#x26A0; Nackdel', fn: function(r){ return '<span style="color:#fca5a5;font-size:.8rem">'+caEsc(r.con)+'</span>'; } },
    { label: '&#x1F3AF; Expertrecension', fn: function(r){
      if (!r.expertOpinion) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return '<span style="font-size:.78rem;color:rgba(255,255,255,.75);font-style:italic">'+caEsc(r.expertOpinion)+'</span>';
    }},
    { label: '&#x1F6E1;&#xFE0F; Euro NCAP', fn: function(r){
      if (!r.safetyRating) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      var parts = r.safetyRating.split(' \u00b7 ');
      var stars = parts[0] || '';
      var details = parts.slice(1).join(' \u00b7 ');
      return '<span style="font-size:.95rem;letter-spacing:.05em;color:#fcd34d">' + caEsc(stars) + '</span>' +
        (details ? '<br><span style="font-size:.7rem;color:rgba(255,255,255,.45)">' + caEsc(details) + '</span>' : '');
    }},
    { label: '&#x1F9F3; Bagageutrymme', fn: function(r){
      if (!r.cargoSpec || r.cargoSpec.cargoLiters <= 0) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      var txt = chip(r.cargoSpec.cargoLiters+' L', 'rgba(251,191,36,.12)');
      if (r.cargoSpec.cargoMaxLiters > 0) txt += ' <span style="font-size:.72rem;color:rgba(255,255,255,.4)">/ '+r.cargoSpec.cargoMaxLiters+' L</span>';
      return txt;
    }},
    { label: '&#x1F527; Motor &amp; batterialternativ', fn: function(r){
      if (!r.engineOptions) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return r.engineOptions.split(',').map(function(opt) {
        return '<span style="display:inline-block;font-size:.72rem;color:rgba(255,255,255,.65);background:rgba(255,255,255,.06);border-radius:12px;padding:2px 8px;margin:2px 2px 2px 0">' + caEsc(opt.trim()) + '</span>';
      }).join('');
    }}
  ];
  if (hasFuel) {
    rows.push({ label: '&#x26FD; F\xf6rbrukning', fn: function(r){
      if (!r.fuelSpec || r.fuelSpec.consumptionLiterPerMil <= 0) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return chip((r.fuelSpec.consumptionLiterPerMil / 10).toFixed(2)+' l/mil','rgba(251,146,60,.15)');
    }});
    rows.push({ label: '&#x2699;&#xFE0F; V\xe4xell\xe5da', fn: function(r){
      if (!r.fuelSpec || !r.fuelSpec.gearbox) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return '<span style="font-size:.78rem;color:rgba(255,255,255,.75)">'+caEsc(r.fuelSpec.gearbox)+'</span>';
    }});
    rows.push({ label: '&#x1F4AA; H\xe4stkrafter', fn: function(r){
      if (!r.fuelSpec || r.fuelSpec.horsepower <= 0) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return chip(r.fuelSpec.horsepower+' hk','rgba(139,92,246,.18)');
    }});
    rows.push({ label: '&#x1F527; Motorvolym', fn: function(r){
      if (!r.fuelSpec || r.fuelSpec.engineVolumeLiters <= 0) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return chip(r.fuelSpec.engineVolumeLiters.toFixed(1)+' L','rgba(56,189,248,.1)');
    }});
  }
  if (hasEv) {
    rows.push({ label: '&#x1F4CF; WLTP', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.wltpKm > 0 ? chip(ev.wltpKm+' km','rgba(56,189,248,.15)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x2600;&#xFE0F; Sommar', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.summerKm > 0 ? chip('~'+ev.summerKm+' km','rgba(59,130,246,.18)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x2744;&#xFE0F; Vinter', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.winterKm > 0 ? chip('~'+ev.winterKm+' km','rgba(148,163,184,.15)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x1F50B; Laddning', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.daysLabel ? '<span style="font-size:.8rem;color:#fcd34d;font-weight:600">'+caEsc(ev.daysLabel)+'</span>' : '&#x2013;'; }); } });
    rows.push({ label: '<span title="Toppeffekt vid publik snabbladdare. Avg\xf6r hur korta pauserna blir p\xe5 l\xe5ngresa - h\xf6gre \xe4r b\xe4ttre, f\xf6rutsatt att stolpen klarar lika mycket." style="border-bottom:1px dotted rgba(255,255,255,.3);cursor:help">&#x26A1; DC max</span>', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.maxDcKw > 0 ? chip(ev.maxDcKw+' kW','rgba(34,197,94,.12)') : '<span style="color:rgba(255,255,255,.25)">ingen DC</span>'; }); } });
    rows.push({ label: '<span title="Toppeffekt fr\xe5n laddbox, satt av bilens ombordladdare. S\xe4llan avg\xf6rande: 11 kW fyller batteriet \xf6ver natten \xe4nd\xe5, och de flesta hemmainstallationer ger inte mer." style="border-bottom:1px dotted rgba(255,255,255,.3);cursor:help">&#x1F50C; AC max</span>', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.maxAcKw > 0 ? chip(ev.maxAcKw+' kW','rgba(139,92,246,.14)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x1F50B; Batteri', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.batteryKwh > 0 ? chip(ev.batteryKwh+' kWh'+(ev.chemistry ? ' &middot; '+ev.chemistry : ''),'rgba(56,189,248,.1)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x1F4AA; H\xe4stkrafter', fn: function(r) {
    var hp = r.horsepower || (r.fuelSpec && r.fuelSpec.horsepower) || 0;
    return hp > 0 ? chip(hp + ' hk', 'rgba(251,191,36,.13)') : '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
  } });
rows.push({ label: '&#x1F4CA; Prisv\xe4rdhet', fn: function(r) {
    if (r.evSpec && r.evSpec.valueLabel) return chip(caEsc(r.evSpec.valueLabel), 'rgba(52,211,153,.14)');
    var cl = caValueLabelCombustion(r.fuelSpec, caParsePrice(r.price));
    return cl ? chip(caEsc(cl), 'rgba(52,211,153,.14)') : '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
  } });
  }
  rows.push({ label: '&#x1F4B0; 5-\xe5rs TCO', fn: function(r) {
    if (caIsLeasing) {
      var tcoL = caTcoLeasingCalc(r, caCurrentKm, parseInt(document.getElementById('ca-budget-slider').value) || 0);
      if (!tcoL) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return '<span style="color:#a5f3fc;font-weight:700;font-size:.85rem">~' + tcoL.total.toLocaleString('sv-SE') + ' kr</span>' +
        '<br><span style="font-size:.65rem;color:rgba(255,255,255,.35)">' + tcoL.perMonth.toLocaleString('sv-SE') + ' kr/m\xe5n</span>';
    }
    var tco = caTcoCalc(r, caCurrentKm);
    if (!tco) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
    return '<span style="color:#a5f3fc;font-weight:700;font-size:.85rem">~' + tco.total.toLocaleString('sv-SE') + ' kr</span>' +
      '<br><span style="font-size:.65rem;color:rgba(255,255,255,.35)">' + tco.perMonth.toLocaleString('sv-SE') + ' kr/m\xe5n</span>';
  }});
  var rowsHtml = rows.map(function(row) {
    var cells = row.evOnly
      ? recs.map(function(r){ return row.fn(r); }).join('')
      : recs.map(function(r){ return cell(r, row.fn); }).join('');
    return '<tr><td '+tl+'>'+row.label+'</td>'+cells+'</tr>';
  }).join('');
  cmp.innerHTML =
    '<div style="background:rgba(255,255,255,.02);border:1px solid rgba(139,92,246,.18);border-radius:18px;overflow:hidden;margin-top:40px">'+
      '<div style="padding:16px 18px 8px;display:flex;align-items:center;gap:10px;border-bottom:1px solid rgba(255,255,255,.06)">'+
        '<span style="font-size:.65rem;font-weight:800;text-transform:uppercase;letter-spacing:.1em;color:rgba(167,139,250,.7)">J\xe4mf\xf6r bilar</span>'+
        (hasEv ? '<span style="font-size:.7rem;color:rgba(255,255,255,.28)">inkl. elbilsdata</span>' : '')+
      '</div>'+
      '<div style="overflow-x:auto">'+
        '<table style="width:100%;border-collapse:collapse;min-width:420px">'+
          '<thead><tr style="border-bottom:1px solid rgba(255,255,255,.08)"><th style="padding:10px 14px;width:110px"></th>'+headerCells+'</tr></thead>'+
          '<tbody>'+rowsHtml+'</tbody>'+
        '</table>'+
      '</div>'+
      caTcoBarChart(recs) +
    '</div>';
}

function caTcoBarChart(recs) {
  if (caIsLeasing) return '';
  var tcos = recs.map(function(r) { return caTcoCalc(r, caCurrentKm); });
  var valid = tcos.filter(Boolean);
  if (valid.length < 2) return '';
  var maxTotal = Math.max.apply(null, valid.map(function(t) { return t.total; }));
  var segments = [
    { key: 'depreciation', label: 'V\xe4rdeminskning', color: '#8b5cf6' },
    { key: 'fuel',         label: 'Drivmedel',           color: '#f97316' },
    { key: 'service',      label: 'Service',              color: '#38bdf8' },
    { key: 'tax',          label: 'Fordonsskatt',         color: '#22c55e' },
    { key: 'insurance',    label: 'Halv\xadförs\xe4kring', color: '#ec4899' }
  ];
  var bars = recs.map(function(r, i) {
    var tco = tcos[i];
    if (!tco) return '';
    var name = r.title.replace(/\s*\(\d{4}\)\s*$/, '');
    var segs = segments.map(function(s) {
      var w = (tco[s.key] / maxTotal * 100).toFixed(1);
      return '<span title="' + s.label + ': ' + Math.round(tco[s.key]/1000) + 'k\xa0kr" ' +
        'style="display:inline-block;height:100%;width:' + w + '%;background:' + s.color + ';flex-shrink:0"></span>';
    }).join('');
    return '<div style="margin-bottom:10px">' +
      '<div style="display:flex;justify-content:space-between;align-items:baseline;margin-bottom:3px">' +
        '<span style="font-size:.72rem;color:rgba(255,255,255,.6)">' + caEsc(name) + '</span>' +
        '<span style="font-size:.72rem;font-weight:700;color:#a5f3fc">' + tco.total.toLocaleString('sv-SE') + '\xa0kr</span>' +
      '</div>' +
      '<div style="display:flex;height:16px;border-radius:6px;overflow:hidden;background:rgba(255,255,255,.06)">' + segs + '</div>' +
    '</div>';
  }).join('');
  var legend = segments.map(function(s) {
    return '<span style="display:inline-flex;align-items:center;gap:4px;font-size:.63rem;color:rgba(255,255,255,.45)">' +
      '<span style="width:9px;height:9px;border-radius:2px;background:' + s.color + ';flex-shrink:0"></span>' + s.label + '</span>';
  }).join('');
  return '<div style="padding:14px 18px 16px;border-top:1px solid rgba(255,255,255,.06)">' +
    '<div style="font-size:.63rem;font-weight:800;text-transform:uppercase;letter-spacing:.1em;color:rgba(167,139,250,.7);margin-bottom:10px">TCO-f\xf6rdelning (5\xa0\xe5r)</div>' +
    bars +
    '<div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:8px">' + legend + '</div>' +
  '</div>';
}

function caFetchOneImage(title, wrapId, imgId) {
  var q = title.replace(/\s*\([^)]*\)\s*$/, '').trim();
  var base = q
    // Karosseri/variant (tar med allt som följer efter)
    .replace(/\s+(Kombi|Estate|SW|Break|Wagon|Touring|Hatchback|Sedan|Coupe|Cabriolet|Cabrio|Avant|Sportback|Allroad|Shooting\s*Brake|Fastback|Cross\s*Country)(\s.*)?$/i, '')
    // EV/PHEV-varianter
    .replace(/\s+(PHEV|HEV|Recharge|e-tron|Plug.?in|GTE|EV|Electric|T[4-9]|B[3-9]|xDrive\d*|quattro|AWD|4WD|Hybrid|Long\s*Range|Performance)(\s.*)?$/i, '')
    // Motorkod + resten (1.0 TSI 110hk, 2.0 TDI osv)
    .replace(/\s+\d+[.,]?\d*\s*(TSI|TDI|TFSI|TCI|HDi|CDi|CDTi|GTI|GTD|GTS|Turbo|EcoBoost|SkyActiv|VTi|THP|dCi|TCe|SCe|GTe)(\s.*)?$/i, '')
    // Kvar motorvolym utan motorkod (1.0, 2.0 osv)
    .replace(/\s+\d+[.,]\d+(\s.*)?$/, '')
    .trim();
  // Överskridande Wikipedia-artikelnamn för bilar som krockar med annat (vapen, emblem m.m.)
  var WIKI_OVERRIDES = {
    'MG4':              'MG4_EV',
    'MG 4':             'MG4_EV',
    // en-wikis "BMW i3" handlar numera om nya Neue Klasse-sedanen (2026) — gamla
    // hatchbacken (2013–2022, den som rekommenderas begagnad) har egen artikel
    'BMW i3':           'BMW_i3_(hatchback)',
    'MG ZS EV':         'MG_ZS_EV',
    'MG ZS':            'MG_ZS',
    'MG5':              'MG5_(car)',
    'Smart 1':          'Smart_#1',
    'Smart 3':          'Smart_#3',
    'Smart 5':          'Smart_#5',
    'Fiat Grande Panda':'Fiat_Grande_Panda',
    'Alpine A290':      'Alpine_A290'
  };
  var wikiQ = (WIKI_OVERRIDES[base] || base.replace(/\s+/g, '_'));
  var titleCaseQ = base.split(' ').map(function(w) { return w.charAt(0).toUpperCase() + w.slice(1).toLowerCase(); }).join('_');
  var origQ = q.replace(/\s+/g, '_');
  // Fånga elementen VID ANROPET — vid ny sökning ersätts korten (samma id:n) och en
  // sen bildträff från förra sökningen skrev annars in FEL bils foto i det nya kortet.
  // Frånkopplade element är ofarliga att skriva till.
  var wrapEl = document.getElementById(wrapId);
  var imgEl  = document.getElementById(imgId);
  if (!wrapEl || !imgEl) return;
  function setImg(src) {
    imgEl.onerror = function() { wrapEl.style.display = 'none'; };
    imgEl.src = src;
    wrapEl.style.display = 'block';
  }
  // Avvisa logotyper/emblem/vapen/interiörer: för smala, extremt porträttformat, eller icke-foto
  var BAD_THUMB_KEYWORDS = ['logo', 'emblem', 'badge', 'gun', 'weapon', 'flag', 'coat_of_arms', 'icon',
                            '.svg', 'interior', 'cockpit', 'dashboard', 'seats'];
  // Modellord (utan märket, diakritik/skiljetecken normaliserade) — används för att avvisa
  // redirects till FEL bil: en-wiki redirectar t.ex. "Dacia Spring" → "Renault Kwid"
  function caNormTokens(s) {
    return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
            .replace(/[^a-z0-9]+/g, ' ').trim().split(' ');
  }
  var modelTokens = caNormTokens(base).slice(1);
  if (!modelTokens.length) modelTokens = caNormTokens(base);
  function fetchThumb(url) {
    return fetch(url).then(function(resp) {
      if (!resp.ok) throw new Error('not ok');
      return resp.json();
    }).then(function(data) {
      var pageTitle = (data.titles && data.titles.normalized) || data.title || '';
      if (pageTitle) {
        var pageTokens = caNormTokens(pageTitle);
        if (!modelTokens.some(function(t) { return pageTokens.indexOf(t) !== -1; }))
          throw new Error('fel artikel (redirect till annan bil)');
      }
      if (!data.thumbnail || !data.thumbnail.source) throw new Error('no thumb');
      var src = data.thumbnail.source;
      var srcLower = src.toLowerCase();
      if (BAD_THUMB_KEYWORDS.some(function(kw) { return srcLower.indexOf(kw) !== -1; })) throw new Error('bad image');
      var w = data.thumbnail.width  || 0;
      var h = data.thumbnail.height || 1;
      if (w < 120 || h > w * 1.8) throw new Error('bad aspect');
      // Bild vars FILNAMN innehåller bilens namn prioriteras: "2018_Nissan_Leaf_Tekna.jpg"
      // slår "Geneva_Motor_Show_1134.jpg" även när båda ligger i rätt artikel
      var fileName = src.split('/').pop();
      try { fileName = decodeURIComponent(fileName); } catch (e) {}
      var fileTokens = caNormTokens(fileName);
      var nameInFile = caNormTokens(base).some(function(t) {
        return t.length >= 2 && fileTokens.indexOf(t) !== -1;
      });
      return { src: src, nameInFile: nameInFile };
    });
  }
  function summaryUrl(lang, title) {
    return 'https://' + lang + '.wikipedia.org/api/rest_v1/page/summary/' + encodeURIComponent(title.replace(/\s+/g, '_'));
  }
  // Kandidattitlar utöver basnamnet:
  // 1. EV-prefixet "ë-"/"e-" framför modellkoden saknar ofta egen artikel ("Citroën ë-C3" → "Citroën C3")
  // 2. Trimnivå som sista ord ("... Urban") gör alla varianter till 404 — prova utan
  var EV_PREFIX = /(^|\s)[eë]-(?=[A-Z]?\d)/gi;
  var titles = [wikiQ, wikiQ + '_automobile'];
  function addCandidate(t) { if (t && titles.indexOf(t) === -1) titles.push(t); }
  addCandidate(base.replace(EV_PREFIX, '$1'));
  var words = base.split(/\s+/);
  if (words.length >= 3) {
    var dropped = words.slice(0, -1).join(' ');
    addCandidate(dropped);
    addCandidate(dropped.replace(EV_PREFIX, '$1'));
  }
  // de-wiki har utmärkt biltäckning och egna artiklar där en-wiki bara har redirects (Dacia Spring)
  var urls = [];
  titles.forEach(function(t) { urls.push(summaryUrl('en', t)); });
  titles.forEach(function(t) { urls.push(summaryUrl('sv', t)); urls.push(summaryUrl('de', t)); });
  // Deterministisk prioritetsordning (inte race): alla kandidater hämtas parallellt men
  // utvärderas i ordning — en-wiki före sv/de, och en bild med bilens namn i filnamnet
  // vinner över en godkänd bild utan (samma bil får alltid samma bild).
  function runOrdered() {
    var pending = urls.map(function(u) { return fetchThumb(u).catch(function() { return null; }); });
    Promise.all(pending).then(function(results) {
      var named = null, first = null;
      results.forEach(function(res) {
        if (!res) return;
        if (res.nameInFile && !named) named = res;
        if (!first) first = res;
      });
      if (named || first) { setImg((named || first).src); return; }
      // Sista utväg: fritextsökning — fetchThumbs vakter gäller även dessa träffar
      fetch('https://en.wikipedia.org/w/api.php?action=opensearch&search=' + encodeURIComponent(base + ' electric car') + '&limit=3&format=json&origin=*')
        .then(function(r) { return r.ok ? r.json() : null; })
        .then(function(srData) {
          if (!srData || !srData[1]) return;
          return Promise.any(srData[1].map(function(t) {
            return fetchThumb('https://en.wikipedia.org/api/rest_v1/page/summary/' + encodeURIComponent(t.replace(/ /g, '_')));
          }));
        })
        .then(function(res) { if (res) setImg(res.src); })
        .catch(function() {});
    });
  }
  // Generationsfällor: en-wikis huvudartikel visar NYASTE generationen. För äldre årsmodeller
  // hämtas fotot från en källa med rätt generation FÖRST; nyare årsmodeller kör vanliga flödet
  // (en Leaf 2026 SKA visa nya generationen). beforeYear = nya generationens första årsmodell.
  var GEN_TRAPS = {
    'Nissan Leaf': { beforeYear: 2025, lang: 'sv', title: 'Nissan_Leaf' }
  };
  var yearMatch = title.match(/\((\d{4})\)/);
  var carYear = yearMatch ? parseInt(yearMatch[1], 10) : null;
  var trap = GEN_TRAPS[base];
  if (trap && carYear && carYear < trap.beforeYear) {
    fetchThumb(summaryUrl(trap.lang, trap.title))
      .then(function(res) { setImg(res.src); })
      .catch(runOrdered);
  } else {
    runOrdered();
  }
}
window.caFetchOneImage = caFetchOneImage;

function caFetchCarImages(recs) {
  recs.forEach(function(r, i) {
    caFetchOneImage(r.title, 'ca-img-wrap-' + i, 'ca-img-' + i);
  });
}

// ── Sparade sökningar (server-side) ──────────────────────────────────────────

function caSavedLabel(prefs) {
  var cat = CA_CAT_NAMES[prefs.carCategory] || prefs.carCategory || '';
  var isLease = prefs.budgetType === 'leasing';
  var budget = prefs.budget ? parseInt(prefs.budget).toLocaleString('sv-SE') + (isLease ? '\xa0kr/m\xe5n' : '\xa0kr') : '';
  var fuel = (prefs.fuelType && prefs.fuelType !== 'spelar ingen roll') ? ' \xb7 ' + (CA_FUEL_NAMES[prefs.fuelType] || prefs.fuelType) : '';
  var trans = (prefs.transmission && prefs.transmission !== 'spelar ingen roll') ? ' \xb7 ' + (CA_TRANSMISSION_NAMES[prefs.transmission] || prefs.transmission) : '';
  var mode = isLease ? ' \xb7 Leasing' : '';
  return [cat, budget].filter(Boolean).join(' \xb7 ') + mode + fuel + trans;
}

function caRenderSaved() {
  var area = document.getElementById('ca-saved-area');
  if (!area) return;
  if (caSavedFromServer.length === 0) { area.innerHTML = ''; return; }
  var chips = caSavedFromServer.map(function(s) {
    return '<button class="ca-history-chip" onclick="caLoadSavedEntry(\'' + s.id + '\')">' +
      '<span class="ca-history-chip-text">♥ ' + caEsc(s.label || 'Sparad sökning') + '</span>' +
      '<span class="ca-history-chip-del" onclick="event.stopPropagation();caDeleteSaved(' + s.id + ')" title="Ta bort">\xd7</span>' +
      '</button>';
  }).join('');
  area.innerHTML = '<div class="ca-history-label">Sparade s\xf6kningar</div><div class="ca-history-chips">' + chips + '</div>';
}

function caLoadSavedEntry(id) {
  var s = caSavedFromServer.find(function(x) { return String(x.id) === String(id); });
  if (!s) return;
  try {
    var prefs = JSON.parse(s.prefsJson);
    if (prefs.carCategory) document.getElementById('ca-category').value = caCanonCat(prefs.carCategory);
    if (prefs.budget)    { document.getElementById('ca-budget-slider').value = prefs.budget; caUpdateSliderFill(); }
    if (prefs.hasCharger !== undefined) document.getElementById('ca-charger').value = prefs.hasCharger ? 'true' : 'false';
    if (prefs.kmPerYear) document.getElementById('ca-km').value = Math.round(prefs.kmPerYear / 10);
    if (prefs.usage)     document.getElementById('ca-usage').value = prefs.usage;
    if (prefs.passengers) document.getElementById('ca-passengers').value = prefs.passengers;
    if (prefs.newCar !== undefined) document.getElementById('ca-newcar').value = prefs.newCar ? 'true' : 'false';
    caSetBudgetMode(prefs.budgetType === 'leasing' ? 'leasing' : 'köp', prefs.budget ? parseInt(prefs.budget) : undefined);
    if (prefs.fuelType)    document.getElementById('ca-fuel').value = prefs.fuelType;
    if (prefs.transmission) { var tEl2 = document.getElementById('ca-transmission'); if (tEl2) tEl2.value = prefs.transmission; }
    if (prefs.maxAgeYears) { var maEl2 = document.getElementById('ca-maxage'); if (maEl2) maEl2.value = prefs.maxAgeYears; }
    caUpdateFuelVisibility(); caCheckMismatch();
    var recs = JSON.parse(s.recommendationsJson || '[]');
    if (recs.length > 0) {
      document.getElementById('ca-divider').style.display = 'block';
      document.getElementById('ca-results').style.display = 'block';
      document.getElementById('ca-cache-badge').style.display = 'none';
      caBudgetShortfall = null;   // sparad sökning bär ingen budgetdom
      caNarrowCriteria = null;
      caShortfallPayload = null;
      caRenderCards(recs);
      caCurrentRecs = recs;
      caShowSaveBtn(true);
      document.getElementById('ca-copy-btn').style.display = 'inline-block';
      document.getElementById('ca-share-result-btn').style.display = 'inline-block';
      var hbadge = document.getElementById('ca-history-badge');
      if (hbadge) { hbadge.textContent = '♥ Sparad s\xf6kning'; hbadge.style.display = 'inline-block'; }
      caHasSearched = true; caSnapshotValues();
      document.getElementById('ca-btn').textContent = 'S\xf6k igen →';
    } else {
      caGetRecommendation();
    }
  } catch(e) {}
}

async function caDeleteSaved(id) {
  var token = localStorage.getItem('ca_token');
  if (!token) return;
  try {
    var r = await fetch(CA_API_BASE + '/api/user/saved-searches/' + id, {
      method: 'DELETE',
      headers: { 'Authorization': 'Bearer ' + token }
    });
    if (r.ok) {
      caSavedFromServer = caSavedFromServer.filter(function(s) { return s.id !== id; });
      caRenderSaved();
    }
  } catch(e) {}
}

async function caLoadSavedFromServer() {
  var token = localStorage.getItem('ca_token');
  if (!token) return;
  try {
    var r = await fetch(CA_API_BASE + '/api/user/saved-searches', {
      headers: { 'Authorization': 'Bearer ' + token }
    });
    if (!r.ok) return;
    caSavedFromServer = await r.json();
    caEnsureSavedArea();
    caRenderSaved();
  } catch(e) {}
}

function caEnsureSavedArea() {
  if (document.getElementById('ca-saved-area')) return;
  var histArea = document.getElementById('ca-history-area');
  if (!histArea) return;
  var div = document.createElement('div');
  div.id = 'ca-saved-area';
  histArea.parentNode.insertBefore(div, histArea);
}

function caShowSaveBtn(show) {
  var token = localStorage.getItem('ca_token');
  if (!token) return;
  var btn = document.getElementById('ca-save-btn');
  if (!btn) {
    var ref = document.getElementById('ca-share-result-btn');
    if (!ref) return;
    btn = document.createElement('button');
    btn.id = 'ca-save-btn';
    btn.className = ref.className;
    btn.style.cssText = 'margin-left:6px';
    btn.textContent = 'Spara s\xf6kning';
    btn.addEventListener('click', caSaveSearch);
    ref.parentNode.insertBefore(btn, ref.nextSibling);
  }
  btn.style.display = show ? 'inline-block' : 'none';
}

async function caSaveSearch() {
  var token = localStorage.getItem('ca_token');
  if (!token || !caCurrentRecs) return;
  var btn = document.getElementById('ca-save-btn');
  if (btn) { btn.textContent = 'Sparar…'; btn.disabled = true; }
  try {
    var prefs = {
      budget: parseInt(document.getElementById('ca-budget-slider').value),
      carCategory: document.getElementById('ca-category').value,
      hasCharger: document.getElementById('ca-charger').value === 'true',
      kmPerYear: parseInt(document.getElementById('ca-km').value) * 10,
      usage: document.getElementById('ca-usage').value,
      passengers: parseInt(document.getElementById('ca-passengers').value),
      newCar: document.getElementById('ca-newcar').value === 'true',
      fuelType:     document.getElementById('ca-fuel').value,
      transmission: (function(){ var t = document.getElementById('ca-transmission'); return t ? t.value : 'spelar ingen roll'; })(),
      budgetType:   caIsLeasing ? 'leasing' : 'köp',
      maxAgeYears:  (function(){ var el = document.getElementById('ca-maxage'); var nc = document.getElementById('ca-newcar'); return (el && nc && nc.value !== 'true' && el.value) ? parseInt(el.value) : null; })(),
      minCargoLiters: caCargoValue()
    };
    var label = caSavedLabel(prefs);
    var r = await fetch(CA_API_BASE + '/api/user/saved-searches', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ prefsJson: JSON.stringify(prefs), recommendationsJson: JSON.stringify(caCurrentRecs), label: label })
    });
    if (r.ok) {
      var saved = await r.json();
      caSavedFromServer.unshift({ id: saved.id, label: label, prefsJson: JSON.stringify(prefs), recommendationsJson: JSON.stringify(caCurrentRecs) });
      caEnsureSavedArea();
      caRenderSaved();
      if (btn) { btn.textContent = '♥ Sparad!'; setTimeout(function() { btn.textContent = 'Spara s\xf6kning'; btn.disabled = false; }, 2500); }
    } else {
      if (btn) { btn.textContent = 'Spara s\xf6kning'; btn.disabled = false; }
    }
  } catch(e) {
    if (btn) { btn.textContent = 'Spara s\xf6kning'; btn.disabled = false; }
  }
}

// ── TCO-kalkyl (5-år, uppskattning) ─────────────────────────────────────────

function caParsePrice(priceStr) {
  if (!priceStr) return 0;
  // strip whitespace, thousands separators (. and ,) and "kr"
  var s = priceStr.replace(/[\s.,]/g, '').replace(/kr/gi, '');
  var m = s.match(/(\d+)[–\-—](\d+)/);
  if (m) return (parseInt(m[1]) + parseInt(m[2])) / 2;
  m = s.match(/(\d{4,7})/);
  return m ? parseInt(m[1]) : 0;
}

function caVehicleTaxPerYear(r) {
  var isEv   = r.evSpec && r.evSpec.carType !== 'PHEV';
  var isPhev = r.evSpec && r.evSpec.carType === 'PHEV';
  var cat    = (r.category || '').toLowerCase();
  var title  = (r.title || '').toLowerCase();
  var isHybrid = !isEv && !isPhev && (title.indexOf('hybrid') !== -1);
  if (isEv) return 360;
  if (isPhev) return 1500;
  if (isHybrid) return cat.indexOf('suv') !== -1 ? 3200 : 2000;
  if (cat.indexOf('suv') !== -1) return 4500;
  // 'ekonomibil' �r ett legacy-v�rde efter sammanslagningen 2026-08-10 - kan fortfarande komma
  // ur ett cachat svar eller en gammal sparad s�kning, s� kontrollen st�r kvar
  if (cat.indexOf('smaabil') !== -1 || cat.indexOf('ekonomibil') !== -1) return 1200;
  return 3000;
}

function caInsurancePerYear(r) {
  var isEv   = r.evSpec && r.evSpec.carType !== 'PHEV';
  var isPhev = r.evSpec && r.evSpec.carType === 'PHEV';
  var cat    = (r.category || '').toLowerCase();
  var price  = caParsePrice(r.price);
  var base = 5500;
  if (cat.indexOf('suv') !== -1) base = 7000;
  else if (cat.indexOf('smaabil') !== -1 || cat.indexOf('ekonomibil') !== -1) base = 3500;
  if (isEv)   base += 1500;
  if (isPhev) base += 500;
  if (price > 600000) base += 2000;
  else if (price < 200000) base -= 1000;
  return Math.round(base / 500) * 500;
}

function caTcoCalc(r, kmPerYear) {
  var price = caParsePrice(r.price);
  if (!price) return null;
  var km = kmPerYear || 15000;
  var years = 5;
  var isEv   = r.evSpec && r.evSpec.carType !== 'PHEV';
  var isPhev = r.evSpec && r.evSpec.carType === 'PHEV';

  // Drivmedelskostnad
  var fuelCost = 0;
  if (isEv && r.evSpec.batteryKwh > 0 && r.evSpec.wltpKm > 0) {
    var kwhPerKm = r.evSpec.batteryKwh / r.evSpec.wltpKm;
    fuelCost = kwhPerKm * km * years * 1.5;
  } else if (isPhev) {
    // el: 0.20 kWh/km × km × 0.5 × years × 1.50 kr/kWh
    // bensin: ~4.5 l/100km × (km×0.5/100) × years × dagsaktuellt bensinpris
    fuelCost = (0.20 * km * 0.5 * years * 1.5) + (4.5 * (km * 0.5 / 100) * years * CA_FUEL_PRICES.bensin);
  } else if (r.fuelSpec && r.fuelSpec.consumptionLiterPerMil > 0) {
    // AI returnerar l/100km trots fältnamnet "PerMil"; >7 l/100km ≈ dieselbil
    var fuelPrice = r.fuelSpec.consumptionLiterPerMil > 7 ? CA_FUEL_PRICES.diesel : CA_FUEL_PRICES.bensin;
    fuelCost = r.fuelSpec.consumptionLiterPerMil * (km / 100) * years * fuelPrice;
  } else {
    fuelCost = 6.5 * (km / 100) * years * CA_FUEL_PRICES.bensin; // schablonbensin 6.5 l/100km
  }

  // Servicekostnad
  var serviceCost = (isEv ? 3000 : isPhev ? 6000 : 8000) * years;

  // Värdeminskning (billiga begagnade tappar ~40% i värde, dyra nya ~52–58%)
  var deprRate = isEv ? 0.58 : price < 80000 ? 0.35 : price < 150000 ? 0.42 : 0.52;
  var depreciation = price * deprRate;

  // Fordonsskatt + försäkring (halvförsäkring)
  var taxCost       = caVehicleTaxPerYear(r) * years;
  var insuranceCost = caInsurancePerYear(r) * years;

  var total = Math.round((fuelCost + serviceCost + depreciation + taxCost + insuranceCost) / 1000) * 1000;
  return {
    total:       total,
    fuel:        Math.round(fuelCost / 1000) * 1000,
    service:     Math.round(serviceCost / 1000) * 1000,
    depreciation:Math.round(depreciation / 1000) * 1000,
    tax:         Math.round(taxCost / 1000) * 1000,
    insurance:   Math.round(insuranceCost / 1000) * 1000,
    perMonth:    Math.round(total / (years * 12) / 100) * 100
  };
}

function caParseLeaseMonthly(priceStr) {
  if (!priceStr) return 0;
  if (!/m[åa]n/i.test(priceStr)) return 0;
  var s = priceStr.replace(/[\s ]/g, '').replace(/kr\/m[åa]n/gi, '').replace(/\/m[åa]n/gi, '').replace(/kr/gi, '');
  var m = s.match(/(\d+)[–\-—](\d+)/);
  if (m) return (parseInt(m[1]) + parseInt(m[2])) / 2;
  m = s.match(/(\d{3,6})/);
  return m ? parseInt(m[1]) : 0;
}

/**
 * Månadskostnad för leasing, i tur och ordning: riktiga leasingannonser (blocketPrice är
 * kr/mån i leasingläge sedan backend hämtar sales_form=5), annars AI:ns kr/mån-pris.
 *
 * Tidigare fanns ett tredje steg: listpris/85, backendens leasingfaktor baklänges. Det var en
 * påhittad siffra utan källa — en Škoda Enyaq iV 80 (2023) fick "~2 000 kr/mån" räknat på ett
 * begagnatpris, för en bil som inte ens går att privatleasa. Finns ingen leasinguppgift ska
 * kortet säga det, inte räkna fram ett tal.
 */
function caLeaseMonthlyEstimate(r) {
  var fromAds = caParseLeaseMonthly(r.blocketPrice);
  if (fromAds) return Math.round(fromAds / 100) * 100;
  var direct = caParseLeaseMonthly(r.price);
  return direct ? Math.round(direct / 100) * 100 : 0;
}

function caTcoLeasingCalc(r, kmPerYear, monthlyFallback) {
  var monthly = caLeaseMonthlyEstimate(r) || monthlyFallback || 0;
  if (!monthly || monthly < 500) return null;
  var km = kmPerYear || 15000;
  var years = 5;
  var isEv   = r.evSpec && r.evSpec.carType !== 'PHEV';
  var isPhev = r.evSpec && r.evSpec.carType === 'PHEV';

  var fuelCost = 0;
  if (isEv && r.evSpec.batteryKwh > 0 && r.evSpec.wltpKm > 0) {
    fuelCost = (r.evSpec.batteryKwh / r.evSpec.wltpKm) * km * years * 1.5;
  } else if (isPhev) {
    fuelCost = (0.20 * km * 0.5 * years * 1.5) + (4.5 * (km * 0.5 / 100) * years * CA_FUEL_PRICES.bensin);
  } else if (r.fuelSpec && r.fuelSpec.consumptionLiterPerMil > 0) {
    var fp = r.fuelSpec.consumptionLiterPerMil > 7 ? CA_FUEL_PRICES.diesel : CA_FUEL_PRICES.bensin;
    fuelCost = r.fuelSpec.consumptionLiterPerMil * (km / 100) * years * fp;
  } else {
    fuelCost = 6.5 * (km / 100) * years * CA_FUEL_PRICES.bensin;
  }

  var leaseCost = monthly * 12 * years;
  var total = Math.round((leaseCost + fuelCost) / 1000) * 1000;
  return {
    total:    total,
    lease:    Math.round(leaseCost / 1000) * 1000,
    fuel:     Math.round(fuelCost / 1000) * 1000,
    monthly:  monthly,
    perMonth: Math.round(total / (years * 12) / 100) * 100
  };
}

function caTcoDot(perMonth) {
  var color, glow, label;
  if (perMonth <= 4500)      { color = '#22c55e'; glow = '#22c55e66'; label = 'L\xe5g TCO'; }
  else if (perMonth <= 8000) { color = '#eab308'; glow = '#eab30866'; label = 'Medel TCO'; }
  else                       { color = '#ef4444'; glow = '#ef444466'; label = 'H\xf6g TCO'; }
  return '<span title="' + label + '" style="display:inline-block;width:10px;height:10px;border-radius:50%;' +
    'background:' + color + ';box-shadow:0 0 7px ' + glow + ';margin-left:7px;vertical-align:middle;flex-shrink:0"></span>';
}

function caTcoHtml(r, kmPerYear) {
  if (caIsLeasing) {
    var tcoL = caTcoLeasingCalc(r, kmPerYear, parseInt(document.getElementById('ca-budget-slider').value) || 0);
    if (!tcoL) return '';
    return '<hr class="ca-divider">' +
      '<span class="ca-section-label" style="font-size:.95rem;font-weight:700">&#x1F4B0; 5-\xe5rs leasingkostnad</span>' +
      '<div style="background:rgba(255,255,255,.03);border-radius:10px;padding:10px 14px;margin-top:6px">' +
        '<div style="display:flex;align-items:center;margin-bottom:5px">' +
          '<span style="font-size:1rem;font-weight:700;color:#a5f3fc">~' + tcoL.total.toLocaleString('sv-SE') + ' kr</span>' +
          caTcoDot(tcoL.perMonth) +
        '</div>' +
        '<div style="font-size:.72rem;color:rgba(255,255,255,.45);line-height:1.9">' +
          '&#x1F4CB; Leasingavgifter: ' + tcoL.lease.toLocaleString('sv-SE') + ' kr<br>' +
          '&#x26FD; Drivmedel: ' + tcoL.fuel.toLocaleString('sv-SE') + ' kr' +
        '</div>' +
        '<div style="margin-top:5px;font-size:.68rem;color:rgba(255,255,255,.25)">' +
          'Service &amp; f\xf6rs\xe4kring ing\xe5r ofta i leasing &bull; uppskattning' +
        '</div>' +
        '<div style="margin-top:2px;font-size:.7rem;color:rgba(255,255,255,.3)">' +
          '&#x2248; ' + tcoL.perMonth.toLocaleString('sv-SE') + ' kr/m\xe5n' +
        '</div>' +
      '</div>';
  }
  var tco = caTcoCalc(r, kmPerYear);
  if (!tco) return '';
  return '<hr class="ca-divider">' +
    '<span class="ca-section-label" style="font-size:.95rem;font-weight:700">&#x1F4B0; 5-\xe5rs TCO</span>' +
    '<div style="background:rgba(255,255,255,.03);border-radius:10px;padding:10px 14px;margin-top:6px">' +
      '<div style="display:flex;align-items:center;margin-bottom:5px">' +
        '<span style="font-size:1rem;font-weight:700;color:#a5f3fc">~' + tco.total.toLocaleString('sv-SE') + ' kr</span>' +
        caTcoDot(tco.perMonth) +
      '</div>' +
      '<div style="font-size:.72rem;color:rgba(255,255,255,.45);line-height:1.9">' +
        '&#x1F4C9; V\xe4rdeminskning: ' + tco.depreciation.toLocaleString('sv-SE') + ' kr<br>' +
        '&#x26FD; Drivmedel: ' + tco.fuel.toLocaleString('sv-SE') + ' kr<br>' +
        '&#x1F527; Service: ' + tco.service.toLocaleString('sv-SE') + ' kr<br>' +
        '&#x1F3E6; Fordonsskatt: ' + tco.tax.toLocaleString('sv-SE') + ' kr<br>' +
        '&#x1F6E1;&#xFE0F; Halvf\xf6rs\xe4kring: ' + tco.insurance.toLocaleString('sv-SE') + ' kr' +
      '</div>' +
      '<div style="margin-top:5px;font-size:.68rem;color:rgba(255,255,255,.25)">' +
        'Alla belopp \xe4r totalt \xf6ver 5 \xe5r &bull; uppskattning' +
      '</div>' +
      '<div style="margin-top:2px;font-size:.7rem;color:rgba(255,255,255,.3)">' +
        '&#x2248; ' + tco.perMonth.toLocaleString('sv-SE') + ' kr/m\xe5n' +
      '</div>' +
    '</div>';
}

function caFallbackCopy(text) {
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.style.cssText = 'position:fixed;opacity:0;top:0;left:0';
  document.body.appendChild(ta);
  ta.focus(); ta.select();
  document.execCommand('copy');
  document.body.removeChild(ta);
}

function caCopyResult() {
  var cards = document.querySelectorAll('.ca-card');
  var lines = ['Mina bilrekommendationer – elitrobban.se/bilradgivning\n'];
  cards.forEach(function(card, i) {
    var title = card.querySelector('h3') ? card.querySelector('h3').textContent : '';
    var price = card.querySelector('.ca-price') ? card.querySelector('.ca-price').textContent : '';
    var pros = Array.from(card.querySelectorAll('.ca-pros li')).map(function(li) {
      return '  ✓ ' + li.textContent.trim();
    }).join('\n');
    var con = card.querySelector('.ca-con') ? card.querySelector('.ca-con').textContent.trim() : '';
    lines.push((i + 1) + '. ' + title + '\n' + price + '\n' + pros + '\n' + con);
  });
  var text = lines.join('\n\n');
  var btn = document.getElementById('ca-copy-btn');
  function confirm() {
    btn.textContent = '✓ Kopierat!'; btn.classList.add('copied');
    setTimeout(function() { btn.textContent = 'Kopiera lista'; btn.classList.remove('copied'); }, 2500);
  }
  if (navigator.clipboard) { navigator.clipboard.writeText(text).then(confirm).catch(function() { caFallbackCopy(text); confirm(); }); }
  else { caFallbackCopy(text); confirm(); }
}

function caShareSearch() {
  var params = new URLSearchParams({
    budget:     document.getElementById('ca-budget-slider').value,
    category:   document.getElementById('ca-category').value,
    charger:    document.getElementById('ca-charger').value,
    km:         document.getElementById('ca-km').value,
    usage:      document.getElementById('ca-usage').value,
    passengers: document.getElementById('ca-passengers').value,
    newcar:     document.getElementById('ca-newcar').value,
    fuelType:     document.getElementById('ca-fuel').value,
    transmission: (function(){ var t = document.getElementById('ca-transmission'); return t ? t.value : 'spelar ingen roll'; })(),
    budgetMode:   caIsLeasing ? 'leasing' : 'köp',
    maxage:       (function(){ var el = document.getElementById('ca-maxage'); return el ? el.value : ''; })(),
    // Utan den här raden tappar en delad länk bagagekravet, och mottagaren får en ANNAN sökning
    // än avsändaren gjorde — tyst, eftersom formuläret ser rätt ut och bara resultatet skiljer.
    // Samma asymmetri fanns åt andra hållet i caReadUrlParams.
    cargo:        (function(){ var c = document.getElementById('ca-cargo'); return c ? c.value : '0'; })()
  });
  var url = window.location.origin + window.location.pathname + '?' + params.toString();
  var btns = [document.getElementById('ca-share-search-btn'), document.getElementById('ca-share-result-btn')];
  function confirmBtn(btn) {
    if (!btn) return;
    var orig = btn.textContent;
    btn.textContent = '✓ L\xe4nk kopierad!'; btn.classList.add('copied');
    setTimeout(function() { btn.textContent = orig; btn.classList.remove('copied'); }, 2500);
  }
  var clicked = event && event.target ? event.target : btns[0];
  if (navigator.share) {
    navigator.share({ title: 'AI Bilr\xe5dgivning', url: url }).catch(function() {});
  } else if (navigator.clipboard) {
    navigator.clipboard.writeText(url).then(function() { confirmBtn(clicked); }).catch(function() { caFallbackCopy(url); confirmBtn(clicked); });
  } else {
    caFallbackCopy(url); confirmBtn(clicked);
  }
}

async function caGetRecommendation() {
  var btn = document.getElementById('ca-btn');
  var loader = document.getElementById('ca-loader');
  var results = document.getElementById('ca-results');
  var divider = document.getElementById('ca-divider');

  btn.disabled = true;
  btn.textContent = 'H\xe4mtar…';
  document.getElementById('ca-copy-btn').style.display = 'none';
  document.getElementById('ca-share-result-btn').style.display = 'none';
  document.getElementById('ca-cache-badge').style.display = 'none';
  document.getElementById('ca-history-badge').style.display = 'none';
  divider.style.display = 'block';
  results.style.display = 'block';
  document.getElementById('ca-cards').innerHTML = caSkeletonHTML();
  loader.style.display = 'block';
  caStartLoadingText();

  var fuelVal = document.getElementById('ca-fuel').value;
  caCurrentKm = parseInt(document.getElementById('ca-km').value) * 10;
  caCurrentCategory = document.getElementById('ca-category').value;
  var payload = {
    budget:      parseInt(document.getElementById('ca-budget-slider').value),
    carCategory: document.getElementById('ca-category').value,
    hasCharger:  document.getElementById('ca-charger').value === 'true',
    kmPerYear:   caCurrentKm,
    usage:       document.getElementById('ca-usage').value,
    passengers:  parseInt(document.getElementById('ca-passengers').value),
    newCar:      document.getElementById('ca-newcar').value === 'true',
    fuelType:     fuelVal,
    transmission: (function(){ var t = document.getElementById('ca-transmission'); return t ? t.value : 'spelar ingen roll'; })(),
    budgetType:   caIsLeasing ? 'leasing' : 'köp',
    maxAgeYears:  (function(){ var el = document.getElementById('ca-maxage'); var nc = document.getElementById('ca-newcar'); return (el && nc && nc.value !== 'true' && el.value) ? parseInt(el.value) : null; })(),
    minCargoLiters: caCargoValue()
  };

  var controller = new AbortController();
  var timeoutId = setTimeout(function() { controller.abort(); }, 35000);
  var caToken = localStorage.getItem('ca_token') || '';
  var headers = { 'Content-Type': 'application/json' };
  if (caToken) headers['Authorization'] = 'Bearer ' + caToken;

  try {
    var r = await fetch(CA_API_BASE + '/api/recommend', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    clearTimeout(timeoutId);
    caStopLoadingText();
    loader.style.display = 'none';

    if (r.status === 429) {
      document.getElementById('ca-cards').innerHTML = '';
      document.getElementById('ca-rate-limit-box').style.display = 'block';
      btn.disabled = false;
      btn.textContent = 'Prenumerera och s\xf6k →';
      return;
    }

    document.getElementById('ca-rate-limit-box').style.display = 'none';
    var d = await r.json();

    if (d.success && d.recommendations) {
      // Sätts före renderingen: caRenderCards ritar banderollen ovanför korten
      caBudgetShortfall = d.budgetShortfallFromKr || null;
      caNarrowCriteria = d.narrowCriteria || null;
      caShortfallBudget = payload.budget;
      caShortfallMaxAge = payload.maxAgeYears;
      caShortfallNewCar = !!payload.newCar;
      caShortfallPayload = payload;
      caRenderCards(d.recommendations);
      caCurrentRecs = d.recommendations;
      document.getElementById('ca-copy-btn').style.display = 'inline-block';
      document.getElementById('ca-share-result-btn').style.display = 'inline-block';
      caShowSaveBtn(true);
      if (d.cached) {
        var age = d.cachedAgeMinutes;
        var ageText = age < 1 ? 'precis' : age + ' min sedan';
        var badge = document.getElementById('ca-cache-badge');
        badge.textContent = '⚡ Cachat svar (' + ageText + ')';
        badge.style.display = 'inline-block';
      }
      if (d.subscriber) caUpdateSubBar(true, false, null);
      else if (d.loggedIn) caUpdateSubBar(false, true, d.remainingSearches);
      else caUpdateSubBar(false, false, d.remainingSearches);
      caSavePrefs();
      caSaveHistory(d.recommendations);
    } else {
      document.getElementById('ca-cards').innerHTML =
        '<div class="ca-card"><div class="ca-raw">⚠️ ' + caEsc(d.error || 'Ok\xe4nt fel') + '</div></div>';
    }

    caHasSearched = true;
    caSnapshotValues();
    document.querySelectorAll('.ca-field.changed').forEach(function(f) { f.classList.remove('changed'); });
    btn.classList.remove('has-changes');
    btn.disabled = false;
    btn.textContent = 'S\xf6k igen →';

  } catch (e) {
    clearTimeout(timeoutId);
    caStopLoadingText();
    loader.style.display = 'none';
    var msg = e.name === 'AbortError'
      ? '⏱ Servern svarade inte inom 35 sekunder – försök igen om en stund.'
      : '🔌 Kunde inte n\xe5 servern: ' + e.message;
    document.getElementById('ca-cards').innerHTML =
      '<div class="ca-card"><div class="ca-raw">' + msg + '</div></div>';
    btn.disabled = false;
    btn.textContent = 'F\xf6rs\xf6k igen →';
  }
}

function caOpenSubscribe() {
  window.open(CA_API_BASE + '/subscribe.html', '_blank', 'width=480,height=650,resizable=yes');
}

function caUpdateSubBar(isSubscriber, isLoggedIn, remaining) {
  var bar = document.getElementById('ca-sub-bar');
  var title = document.getElementById('ca-sub-title');
  var desc = document.getElementById('ca-sub-desc');
  var loginLink = document.getElementById('ca-login-link');
  var prenBtn = document.getElementById('ca-prenumerera-btn');
  var emailEl = document.getElementById('ca-sub-email');
  var caEmail = localStorage.getItem('ca_email');

  if (!bar || !title || !desc || !prenBtn) return;
  bar.classList.remove('ca-sub-bar-limited');
  if (isSubscriber) {
    title.textContent = '✓ Prenumerant';
    desc.textContent = ' – obegr\xe4nsade s\xf6kningar';
    prenBtn.style.display = 'none';
    loginLink.style.display = 'inline';
    loginLink.textContent = 'Konto';
    loginLink.href = CA_API_BASE + '/subscribe.html';
    loginLink.dataset.action = 'subscribe';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
    var evPromo = document.getElementById('ca-ev-promo');
    if (evPromo) evPromo.style.display = 'flex';
  } else if (isLoggedIn || caEmail) {
    var evPromo = document.getElementById('ca-ev-promo');
    if (evPromo) evPromo.style.display = 'none';
    title.textContent = 'Inloggad';
    desc.textContent = remaining !== null ? ' – ' + remaining + ' av 30 s\xf6kningar kvar denna timme' : ' – 30 s\xf6kningar per timme';
    if (remaining !== null && remaining <= 5) bar.classList.add('ca-sub-bar-limited');
    prenBtn.style.display = 'inline-block';
    prenBtn.textContent = 'Prenumerera – 49\xa0kr/m\xe5n';
    loginLink.style.display = 'inline';
    loginLink.textContent = 'Logga ut';
    loginLink.href = '#';
    loginLink.dataset.action = 'logout';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else {
    title.textContent = 'Demo';
    desc.textContent = remaining !== null ? ' – ' + remaining + ' av 10 s\xf6kningar kvar denna timme' : ' – 10 gratis s\xf6kningar per timme';
    if (remaining !== null && remaining <= 3) bar.classList.add('ca-sub-bar-limited');
    prenBtn.style.display = 'inline-block';
    prenBtn.textContent = 'Prenumerera / Logga in';
    loginLink.style.display = 'none';
    emailEl.style.display = 'none';
    var evPromo = document.getElementById('ca-ev-promo');
    if (evPromo) evPromo.style.display = 'none';
  }
}

function caLogoutBar() {
  var token = localStorage.getItem('ca_token');
  fetch(CA_API_BASE + '/api/auth/logout', { method: 'POST', headers: { 'Authorization': 'Bearer ' + (token || '') } });
  localStorage.removeItem('ca_token'); localStorage.removeItem('ca_email'); localStorage.removeItem('ca_status');
  caUpdateSubBar(false, false, null);
}

window.addEventListener('focus', function() {
  if (localStorage.getItem('ca_scroll_to_app')) {
    localStorage.removeItem('ca_scroll_to_app');
    var el = document.getElementById('ca-wrap');
    if (el) setTimeout(function() { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); }, 100);
  }
});

window.addEventListener('storage', function(ev) {
  if (ev.key === 'ca_status') {
    if (ev.newValue === null) {
      caUpdateSubBar(false, false, null);
    } else {
      var isActive = ev.newValue === 'active';
      caUpdateSubBar(isActive, !isActive, null);
    }
  }
});

window.addEventListener('message', function(ev) {
  if (!ev.data || !ev.data.type) return;
  if (ev.data.type === 'CA_SCROLL_TO_APP') {
    var el = document.getElementById('ca-wrap');
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    return;
  }

  if (ev.data.type === 'CA_LOGIN' || ev.data.type === 'CA_SUBSCRIBED') {
    if (ev.data.token) localStorage.setItem('ca_token', ev.data.token);
    if (ev.data.email) localStorage.setItem('ca_email', ev.data.email);
    if (ev.data.status) localStorage.setItem('ca_status', ev.data.status);
    var isActive = ev.data.status === 'active';
    caUpdateSubBar(isActive, !isActive, null);
  }
  if (ev.data.type === 'CA_LOGOUT') {
    localStorage.removeItem('ca_token'); localStorage.removeItem('ca_email'); localStorage.removeItem('ca_status');
    caUpdateSubBar(false, false, null);
  }
});

// ── Fri bilj\xe4mf\xf6relse ─────────────────────────────────────────────────────────

var caFcLoading = false;

var caFcCarsFetched = false;
function caFcFetchCars() {
  if (caFcCarsFetched) return;
  caFcCarsFetched = true;
  var datalist = document.getElementById('ca-fc-datalist');
  fetch(CA_API_BASE + '/api/cars')
    .then(function(r) { return r.json(); })
    .then(function(cars) {
      if (datalist) {
        datalist.innerHTML = cars.map(function(name) { return '<option value="' + caEsc(name) + '">'; }).join('');
      }
    })
    .catch(function() {});
}

function caFcInit() {
  var btn = document.getElementById('ca-fc-btn');
  if (btn) btn.addEventListener('click', caFcCompare);
  ['ca-fc-car1','ca-fc-car2'].forEach(function(id) {
    var el = document.getElementById(id);
    if (el) {
      el.addEventListener('focus', caFcFetchCars);
      el.addEventListener('keydown', function(e) { if (e.key === 'Enter') caFcCompare(); });
    }
  });
}

function caFcCompare() {
  if (caFcLoading) return;
  var car1 = (document.getElementById('ca-fc-car1').value || '').trim();
  var car2 = (document.getElementById('ca-fc-car2').value || '').trim();
  if (!car1 || !car2) { alert('V\xe4lj tv\xe5 bilar att j\xe4mf\xf6ra.'); return; }
  if (car1.toLowerCase() === car2.toLowerCase()) { alert('V\xe4lj tv\xe5 olika bilar.'); return; }

  caFcLoading = true;
  var btn = document.getElementById('ca-fc-btn');
  var loader = document.getElementById('ca-fc-loader');
  var result = document.getElementById('ca-fc-result');
  btn.disabled = true; btn.textContent = 'H\xe4mtar…';
  loader.style.display = 'block'; result.innerHTML = '';

  var token = localStorage.getItem('ca_token');
  var hdrs = { 'Content-Type': 'application/json' };
  if (token) hdrs['Authorization'] = 'Bearer ' + token;

  fetch(CA_API_BASE + '/api/compare-cars', {
    method: 'POST', headers: hdrs,
    body: JSON.stringify({ car1: car1, car2: car2 })
  })
  .then(function(r) { return r.json(); })
  .then(function(data) {
    caFcLoading = false; btn.disabled = false; btn.textContent = 'J\xe4mf\xf6r →';
    loader.style.display = 'none';
    if (!data.success) {
      result.innerHTML = '<div style="color:#fca5a5;font-size:.8rem;padding:10px 0">' + caEsc(data.error || 'N\xe5got gick fel.') + '</div>';
      return;
    }
    caFcRenderResult(data.recommendations);
  })
  .catch(function() {
    caFcLoading = false; btn.disabled = false; btn.textContent = 'J\xe4mf\xf6r →';
    loader.style.display = 'none';
    result.innerHTML = '<div style="color:#fca5a5;font-size:.8rem;padding:10px 0">N\xe5got gick fel. F\xf6rs\xf6k igen.</div>';
  });
}

// Minimerar den gamla "Dina rekommendationer"-sektionen till en klickbar rad när en
// fri jämförelse visas ovanför den — annars ser man både 3 bilkort + kompakt jämförelse
// staplat. Klick på raden återställer sektionen. Samma mönster som stationslistan i Elbilsladdning.
function caMinimizeResults() {
  var results = document.getElementById('ca-results');
  var cards = document.getElementById('ca-cards');
  if (!results || !cards) return;
  var count = cards.querySelectorAll('.ca-card').length;
  if (!count) return;
  if (results.style.display === 'none') return;
  results.style.display = 'none';

  var bar = document.getElementById('ca-results-collapsed');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'ca-results-collapsed';
    results.parentNode.insertBefore(bar, results);
    bar.addEventListener('click', function() {
      document.getElementById('ca-results').style.display = 'block';
      bar.style.display = 'none';
    });
  }
  bar.style.cssText = 'cursor:pointer;padding:10px 16px;margin-bottom:14px;background:rgba(255,255,255,.04);' +
    'border:1px solid rgba(255,255,255,.12);border-radius:10px;font-size:.82rem;color:rgba(255,255,255,.65);' +
    'display:flex;align-items:center;justify-content:space-between;gap:10px';
  bar.innerHTML = '<span>&#x1F4CB; Dina rekommendationer (' + count + ' bilar)</span><span style="color:#7ec8ff;white-space:nowrap">visa &#x25BE;</span>';
}

// Tar bort en ev. minimerad rad och visar resultatsektionen igen — körs innan nya
// bilkort renderas så inget föråldrat minimerat läge blir kvar från en tidigare fri jämförelse.
function caRestoreResults() {
  var bar = document.getElementById('ca-results-collapsed');
  if (bar) bar.style.display = 'none';
  var results = document.getElementById('ca-results');
  if (results) results.style.display = 'block';
}

function caFcRenderResult(recs) {
  var result = document.getElementById('ca-fc-result');
  if (!result || !recs || recs.length < 2) return;

  caMinimizeResults();

  var mini = recs.slice(0, 2).map(function(r, i) {
    var col = i === 0 ? '#a78bfa' : '#38bdf8';
    return '<div class="ca-fc-mini-card" style="border-color:' + col + '33">' +
      '<div id="ca-fc-img-wrap-' + i + '" style="width:100%;height:60px;overflow:hidden;border-radius:8px;background:rgba(255,255,255,.04);margin-bottom:8px;display:none">' +
        '<img id="ca-fc-img-' + i + '" src="" alt="' + caEsc(r.title) + '" style="width:100%;height:100%;object-fit:contain;object-position:center center;transition:opacity .4s">' +
      '</div>' +
      '<div style="font-size:.65rem;font-weight:800;color:' + col + ';text-transform:uppercase;letter-spacing:.08em;margin-bottom:3px">Bil ' + (i + 1) + '</div>' +
      '<div style="font-weight:700;color:#e2e8f0;font-size:.85rem">' + caEsc(r.title) + '</div>' +
      (r.blocketPrice
        ? '<div style="font-size:.62rem;color:rgba(255,255,255,.35);text-transform:uppercase;letter-spacing:.04em;margin-top:5px">Blocket nu</div><div style="font-size:.8rem;color:#60a5fa;font-weight:600">🔵 ' + caEsc(r.blocketPrice) + '</div>'
        : '<div style="font-size:.62rem;color:rgba(255,255,255,.35);text-transform:uppercase;letter-spacing:.04em;margin-top:4px">Pris</div><div style="color:#a5f3fc;font-size:.8rem;font-weight:600">' + caEsc(r.price) + '</div>') +
      '<a href="' + caBlocketUrl(r.title) + '" target="_blank" rel="noopener" style="display:inline-block;margin-top:8px;font-size:.72rem;color:#60a5fa;text-decoration:none">S\xf6k p\xe5 Blocket →</a>' +
    '</div>';
  }).join('');

  result.innerHTML = '<div class="ca-fc-mini-row">' + mini + '</div>';

  recs.slice(0, 2).forEach(function(r, i) {
    caFetchOneImage(r.title, 'ca-fc-img-wrap-' + i, 'ca-fc-img-' + i);
  });

  var cmpDiv = document.createElement('div');
  result.appendChild(cmpDiv);
  caRenderCompare(recs, cmpDiv);

  var chatBtn = document.createElement('button');
  chatBtn.className = 'ca-fc-chat-btn';
  var n1 = recs[0].title.replace(/\s*\(\d{4}\)\s*$/, '');
  var n2 = recs[1].title.replace(/\s*\(\d{4}\)\s*$/, '');
  chatBtn.textContent = '💬 Fr\xe5ga chatboten om ' + n1 + ' vs ' + n2;
  chatBtn.addEventListener('click', function() {
    var panel = document.getElementById('ca-chat-panel');
    if (panel) panel.style.display = 'flex';
    if (window.caChatFocusCar) window.caChatFocusCar(0, recs[0].title);
  });
  result.appendChild(chatBtn);

  window._caRecommendations = recs;
  if (window.caChatSetRecsContext) window.caChatSetRecsContext(recs);

  setTimeout(function() { result.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }, 150);
}

function caInit() {
  window._caFns = {
    recommend: caGetRecommendation,
    share: caShareSearch,
    reset: caResetForm,
    copy: caCopyResult,
    history: caLoadFromHistory,
    delHistory: caDeleteHistory
  };

  caUpdateSliderFill();
  // Injiceras FÖRE caLoadPrefs — annars finns inte reglaget när det sparade värdet ska sättas
  caEnsureCargoField();
  caFixCategoryLabels();
  caLoadPrefs();
  caReadUrlParams();
  caBindChangeListeners();
  caRenderHistory();
  caFcInit();
  var kopBtn = document.getElementById('ca-mode-kop');
  var leaseBtn = document.getElementById('ca-mode-leasing');
  if (kopBtn) kopBtn.addEventListener('click', function() {
    caKopBudget = parseInt(document.getElementById('ca-budget-slider').value) || caKopBudget;
    caSetBudgetMode('köp'); caCheckChanges();
  });
  if (leaseBtn) leaseBtn.addEventListener('click', function() {
    if (!caIsLeasing) caKopBudget = parseInt(document.getElementById('ca-budget-slider').value) || caKopBudget;
    caSetBudgetMode('leasing'); caCheckChanges();
  });

  function caBindEl(id, fn) { var el = document.getElementById(id); if (el) el.addEventListener('click', fn); }
  caBindEl('ca-btn', caGetRecommendation);
  caBindEl('ca-share-search-btn', caShareSearch);
  caBindEl('ca-reset-btn', caResetForm);
  caBindEl('ca-copy-btn', caCopyResult);
  caBindEl('ca-share-result-btn', caShareSearch);
  caBindEl('ca-login-link', function(e) { e.preventDefault(); if (this.dataset.action === 'logout') { caLogoutBar(); } else { caOpenSubscribe(); } });
  caBindEl('ca-prenumerera-btn', function(e) { e.preventDefault(); caOpenSubscribe(); });

  try {
    var status = localStorage.getItem('ca_status');
    var isActive = status === 'active';
    var hasToken = !!localStorage.getItem('ca_token');
    // Hide subscribe button immediately if we have a token — /api/auth/me will correct it
    if (hasToken) {
      var pb = document.getElementById('ca-prenumerera-btn');
      if (pb) pb.style.display = 'none';
    }
    caUpdateSubBar(isActive, hasToken && !isActive, null);
  } catch(e) {}

  try {
    var caToken = localStorage.getItem('ca_token');
    if (caToken) {
      fetch(CA_API_BASE + '/api/auth/me', {
        headers: { 'Authorization': 'Bearer ' + caToken }
      }).then(function(r) {
        if (!r.ok) {
          localStorage.removeItem('ca_token'); localStorage.removeItem('ca_email'); localStorage.removeItem('ca_status');
          caUpdateSubBar(false, false, null);
          return null;
        }
        return r.json();
      }).then(function(d) {
        if (!d) return;
        localStorage.setItem('ca_status', d.subscriptionStatus || 'inactive');
        var active = d.subscriptionStatus === 'active';
        caUpdateSubBar(active, !active, null);
        caLoadSavedFromServer();
      }).catch(function() {});
    }
  } catch(e) {}

  try {
    var p = new URLSearchParams(window.location.search);
    if (p.has('category') || p.has('budget')) caGetRecommendation();
  } catch(e) {}
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', caInit);
} else {
  caInit();
}
