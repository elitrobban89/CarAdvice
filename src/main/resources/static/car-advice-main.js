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

// Mobilens fingermål och läsbarhet. Mätt på riktiga sidan (elitrobban.se/bilradgivning) i
// både 360 och 390 px: Köp/Leasing var 25 px höga, Jämför-knappen 33, tummarna 33 och
// prenumerationsknappen 38 — alla under de 44 px som är minsta träffsäkra fingermål.
// Kategorichipsen la sig 4+2 med dubbelbreda knappar på andra raden, och elbils-promons
// rubrik hade white-space:nowrap och klipptes 36 px utanför skärmkanten.
//
// Selektorerna är #ca-wrap-prefixade med flit: en del av reglerna de rättar bor i
// WP-sidans egen <style> och en del injiceras SENARE av kortkoden — id-specificitet
// vinner över båda oavsett ordning, medan en ren klassregel hade förlorat mot den ena
// eller den andra beroende på när den hamnade i dokumentet.
(function caMobilTouchCss() {
  if (document.getElementById('ca-mobil-touch-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-mobil-touch-css';
  s.textContent = '@media(max-width:520px){' +
    // Fingermålen. min-height i stället för fast höjd: texten får fortfarande växa.
    '#ca-wrap .ca-mode-btn{min-height:40px;padding:9px 16px;font-size:.8rem;}' +
    // Köp/Leasing satt som float:right INUTI budgetetiketten. Med 40 px höjd föll floaten
    // nedanför etikettraden och skalstrecken (50k…1M) la sig BREDVID den i stället för under:
    // hela skalan pressades ihop till 123 av 264 px. På mobil får växeln därför en egen rad i
    // full bredd — två lika breda hälfter är också lättare att träffa än två små piller.
    '#ca-wrap #ca-budget-mode{float:none;display:flex;width:100%;margin:9px 0 0;gap:8px;}' +
    '#ca-wrap #ca-budget-mode .ca-mode-btn{flex:1 1 0;}' +
    '#ca-wrap .ca-slider-ticks{clear:both;}' +
    // Budgetreglaget var 24 px hogt - det enda reglaget i formularet, och det man MASTE dra.
    // Fingermalen sattes till 44 px i a09a460 men reglaget missades. Inmatningen ar genomskinlig
    // och bara tummen syns, sa en hogre ruta ar ren traffyta: inget flyttar sig visuellt.
    // Vaxer NEDAT (top oforandrat) for att Kop/Leasing-knapparna ligger 12 px ovanfor spåret,
    // och tummen dras tillbaka med negativ marginal sa den stannar mitt pa spåret.
    '#ca-wrap #ca-budget-slider{height:44px;}' +
    '#ca-wrap #ca-budget-slider::-webkit-slider-thumb{margin-top:-10px;}' +
    '#ca-wrap #ca-budget-slider::-moz-range-thumb{margin-top:-10px;}' +
    '#ca-wrap #ca-fc-btn{min-height:44px;padding:12px 20px;font-size:.82rem;}' +
    '#ca-wrap #ca-prenumerera-btn{min-height:44px;padding:12px 22px;font-size:.85rem;}' +
    '#ca-wrap .ca-fb-btn{min-width:56px;min-height:44px;}' +
    '#ca-wrap .ca-blocket-btn,#ca-wrap .ca-bytbil-btn{min-height:44px;}' +
    '#ca-wrap .ca-fc-input{min-height:44px;font-size:.85rem;}' +
    // Länken i jämförelsetabellen var 18 px hög — en radhöjd utan egen yta att träffa.
    '#ca-wrap .ca-cmp-lank{display:inline-block;padding:7px 0;}' +
    // Rutnät i stället för flex-wrap: auto-fit ger tre jämnbreda chips även på en 360 px-skärm
    // och två jämnbreda när gruppen bara har två (laddare hemma), i stället för att sista
    // raden sträcks ut till dubbel bredd.
    '#ca-wrap .ca-chips{display:grid;grid-template-columns:repeat(auto-fit,minmax(78px,1fr));gap:8px;}' +
    // Resultatknapparna ligger pa rubrikens rad. Pa en smal skarm ska de falla ned under den i
    // stallet for att klamma ihop "Dina rekommendationer" till tva ord per rad.
    '#ca-wrap .ca-result-actions{margin-left:0!important;width:100%;}' +
    '#ca-wrap .ca-chip{min-width:0;padding:11px 6px;font-size:.74rem;}' +
    // Promorutans rubrik: nowrap på en 228 px bred rad klipper alltid på mobil.
    '#ca-wrap .ca-ev-promo-title{display:block;white-space:normal;overflow-wrap:anywhere;margin-bottom:3px;}' +
  '}';
  (document.body || document.documentElement).appendChild(s);
})();

// Emblemet på bilkortet. Egen injektion av samma skäl som mobil-CSS:en ovan: WP-sidan är
// en manuell kopia och ska slippa klistras om för en ren stiländring.
//
// VIT platta. Logotyperna är gjorda för ljus botten — på en mörk blir Mercedes-stjärnan,
// VW-ringen och Audi-ringarna nästan osynliga. Mätt på kontaktark i Elbilsassistenten
// innan valet gjordes, och samma slutsats gäller här.
(function caEmblemCss() {
  if (document.getElementById('ca-emblem-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-emblem-css';
  s.textContent = '.ca-title-rad{display:flex;align-items:center;gap:11px;}' +
    '.ca-title-rad h3{margin:0;}' +
    '.ca-emblem{width:38px;height:38px;flex-shrink:0;border-radius:10px;background:#fff;' +
    'border:1.5px solid rgba(255,255,255,.5);padding:5px;box-sizing:border-box;' +
    'display:flex;align-items:center;justify-content:center;' +
    'box-shadow:0 2px 10px rgba(0,0,0,.35);}' +
    '.ca-emblem img{width:100%;height:100%;object-fit:contain;display:block;}' +
    '@media(max-width:520px){.ca-emblem{width:32px;height:32px;padding:4px;}}' +
    // "Fler val": en rad som ser ut som en rad, inte som en knapp bland formulärets fält.
    '#ca-fler-btn,.ca-hopfall-btn{display:flex;align-items:center;gap:9px;width:100%;margin:2px 0 14px;'
    + 'padding:11px 14px;background:rgba(255,255,255,.04);border:1px dashed rgba(167,139,250,.35);'
    + 'border-radius:10px;color:rgba(226,232,240,.82);font-family:inherit;font-size:.8rem;'
    + 'font-weight:700;letter-spacing:.02em;cursor:pointer;transition:all .16s;text-align:left;}' +
    '#ca-fler-btn:hover,.ca-hopfall-btn:hover{background:rgba(139,92,246,.12);border-color:rgba(167,139,250,.6);color:#fff;}' +
    // Innehållsförteckningen på knappen: utan den vet man inte om det är värt att fälla ut.
    '.ca-fler-hint{font-weight:400;font-size:.72rem;color:rgba(226,232,240,.45);}' +
    '.ca-fler-pil{margin-left:auto;color:#a78bfa;transition:transform .2s;}' +
    '#ca-fler-btn.ca-fler-oppen .ca-fler-pil,.ca-hopfall-btn.ca-fler-oppen .ca-fler-pil{transform:rotate(180deg);}' +
    // Hopfallda lador: samma [hidden]-fälla som #ca-fler. Har ar det inte .ca-grid utan
    // rutornas egna display-regler som slar webblasarens [hidden], sa den maste sagas explicit.
    '#ca-freecompare[hidden],.ca-history-chips[hidden]{display:none!important;}' +
    // Fritt-jamfor-lådan har sin egen rubrik inuti; nar den ligger bakom en knapp med samma
    // text blir den en upprepning. Underrubriken far vara kvar - den forklarar.
    '#ca-freecompare .ca-fc-header{display:none;}' +
    '#ca-freecompare{margin-top:0;}' +
    '@media(max-width:520px){.ca-fler-hint{display:none;}}' +
    // hidden-ATTRIBUTET räcker inte: webbläsarens egen regel för [hidden] är display:none,
    // men .ca-grid sätter display:grid med högre specificitet och vinner. Lådan har därför
    // ALDRIG varit ihopfälld i drift — och mitt prov läste .hidden-EGENSKAPEN, som var true
    // hela tiden. Ett grönt prov som mäter fel sak är värre än inget prov.
    '#ca-fler[hidden]{display:none;}' +
    // Sammanfattningsraden: samma tysta ton som notiserna, men med värdet framhävt — det är
    // raden som ersätter en fråga, och då måste svaret gå att läsa i förbifarten.
    '#ca-fuel-sum{display:flex;align-items:center;gap:9px;flex-wrap:wrap;margin:0 0 12px;'
    + 'padding:9px 12px;background:rgba(255,255,255,.035);border:1px solid rgba(255,255,255,.09);'
    + 'border-radius:10px;font-size:.78rem;}' +
    '.ca-fuel-sum-etikett{color:rgba(226,232,240,.5);font-weight:700;letter-spacing:.02em;'
    + 'text-transform:uppercase;font-size:.68rem;}' +
    '.ca-fuel-sum-varde{color:#e2e8f0;font-weight:700;}' +
    '.ca-fuel-sum-hjalp{margin-left:auto;color:rgba(226,232,240,.4);font-size:.72rem;}' +
    '@media(max-width:520px){.ca-fuel-sum-hjalp{margin-left:0;}}' +
    '#ca-fc-specs{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px;}' +
    '#ca-fc-specs:empty{display:none;}' +
    '#ca-fc-specs .ca-ev{margin:0;}' +
    '@media(max-width:640px){#ca-fc-specs{grid-template-columns:1fr;}}' +
    // Valknapparna. Ikonen först och etiketten under: i en rad om fem blir texten smal, och
    // en ikon ovanför läser snabbare än en ikon bredvid när bredden är knapp.
    '.ca-chips{display:flex;flex-wrap:wrap;gap:7px;}' +
    '.ca-chip{display:flex;flex-direction:column;align-items:center;gap:5px;flex:1 1 0;'
    + 'min-width:64px;padding:10px 8px;background:rgba(255,255,255,.045);'
    + 'border:1.5px solid rgba(255,255,255,.1);border-radius:12px;color:rgba(226,232,240,.62);'
    + 'font-family:inherit;font-size:.72rem;font-weight:700;cursor:pointer;'
    + 'transition:background .16s,border-color .16s,color .16s,transform .16s,box-shadow .16s;}' +
    '.ca-chip-ikon{font-size:1.15rem;line-height:1;filter:grayscale(.55) opacity(.75);transition:filter .16s,transform .16s;}' +
    '.ca-chip-txt{text-align:center;line-height:1.2;}' +
    // Prislappen: mindre och tystare an namnet, men inte sa tyst att den blir dekoration -
    // det ar den som talar om vad knappen faktiskt staller in.
    '.ca-chip-hint{font-size:.62rem;font-weight:600;letter-spacing:.01em;color:rgba(226,232,240,.5);'
    + 'white-space:nowrap;'
    + 'text-align:center;line-height:1.15;}' +
    '.ca-chip-aktiv .ca-chip-hint{color:rgba(226,232,240,.78);}' +
    '.ca-chip:hover{background:rgba(139,92,246,.12);border-color:rgba(167,139,250,.45);color:#fff;transform:translateY(-1px);}' +
    '.ca-chip:hover .ca-chip-ikon{filter:none;transform:scale(1.08);}' +
    // Det valda alternativet bär husets lila och en glöd, så det syns utan att man läser.
    '.ca-chip-aktiv{background:linear-gradient(135deg,rgba(139,92,246,.3),rgba(99,102,241,.18));'
    + 'border-color:rgba(167,139,250,.75);color:#fff;'
    + 'box-shadow:0 0 0 1px rgba(167,139,250,.3),0 4px 16px -4px rgba(139,92,246,.55);}' +
    '.ca-chip-aktiv .ca-chip-ikon{filter:none;}' +
    '.ca-chip:focus-visible{outline:2px solid rgba(167,139,250,.8);outline-offset:2px;}' +
    // Under 520 px ryms inte fem knappar på en rad utan att texten bryts mitt i ordet.
    '@media(max-width:520px){.ca-chip{min-width:56px;font-size:.68rem;padding:9px 5px;}'
    + '.ca-chip-ikon{font-size:1.05rem;}}' +
    // Bilväljaren i den fria jämförelsen. Lila i stället för Elbilsassistentens blå:
    // samma två steg, men husets färg är en annan här.
    // Panelen ankras mot RADEN och inte mot .ca-vp. Behållaren sitter som flexbarn bredvid
    // fältet och är i praktiken nollbred, så en panel med left:0;right:0 mot den hamnade
    // utanför kortet och klipptes av högerkanten — syntes direkt på Bil 2, som ligger längst
    // till höger. Raden är alltid lika bred som båda fälten tillsammans.
    '.ca-fc-pickers{position:relative;}' +
    '.ca-vp{display:contents;}' +
    // Fast position och ett z-index högt nog att stå över sidans glaslager. Bottnen är
    // helt ogenomskinlig — en panel man kan läsa igenom går inte att sikta i.
    '.ca-vp-panel{position:fixed;z-index:99999;background:#1a1235;border:1.5px solid rgba(139,92,246,.4);border-radius:13px;box-shadow:0 18px 44px rgba(0,0,0,.6);padding:11px;}' +
    '.ca-vp-sok{width:100%;box-sizing:border-box;padding:8px 11px;margin-bottom:9px;background:rgba(255,255,255,.06);border:1px solid rgba(139,92,246,.3);border-radius:9px;color:#e2e8f0;font-size:.8rem;outline:none;}' +
    '.ca-vp-steg{display:flex;width:200%;transition:transform .3s cubic-bezier(.22,1,.36,1);}' +
    '.ca-vp-steg.ca-vp-at-modeller{transform:translateX(-50%);}' +
    '.ca-vp-marken,.ca-vp-modeller{width:50%;flex-shrink:0;max-height:300px;overflow-y:auto;overscroll-behavior:contain;}' +
    '.ca-vp-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(132px,1fr));gap:6px;padding:1px;}' +
    '.ca-vp-marke,.ca-vp-modell{display:flex;align-items:center;gap:8px;padding:7px;width:100%;background:rgba(139,92,246,.07);border:1.5px solid rgba(139,92,246,.18);border-radius:10px;color:#e2e8f0;font-family:inherit;text-align:left;cursor:pointer;transition:all .15s;}' +
    '.ca-vp-marke:hover,.ca-vp-modell:hover{background:rgba(139,92,246,.2);border-color:rgba(139,92,246,.6);}' +
    '.ca-vp-txt{display:flex;flex-direction:column;min-width:0;}' +
    '.ca-vp-namn{font-size:.78rem;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}' +
    '.ca-vp-antal{font-size:.64rem;color:rgba(255,255,255,.45);}' +
    '.ca-vp-mono{width:30px;height:30px;flex-shrink:0;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:.62rem;font-weight:800;background:rgba(139,92,246,.22);color:#c4b5fd;border:1.5px solid rgba(139,92,246,.35);}' +
    // Emblemplattan är mindre här än på kortet — rutnätet rymmer fler märken då.
    '.ca-vp .ca-emblem{width:30px;height:30px;padding:4px;border-radius:8px;box-shadow:none;}' +
    '.ca-vp-lista{display:flex;flex-direction:column;gap:5px;padding:1px;}' +
    '.ca-vp-modell{font-size:.78rem;font-weight:600;}' +
    '.ca-vp-back-rad{display:flex;align-items:center;gap:9px;padding:0 2px 9px;position:sticky;top:0;background:#1a1235;z-index:2;}' +
    '.ca-vp-back{background:none;border:none;color:#a78bfa;font-size:.75rem;font-weight:700;cursor:pointer;font-family:inherit;padding:3px 5px 3px 0;}' +
    '.ca-vp-tom{padding:18px 8px;text-align:center;font-size:.78rem;color:rgba(255,255,255,.45);}';
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
    // ── Groq-badgen: skenet som sveper fram och tillbaka ─────────────────────
    // Ljuset ligger i ::before med z-index:-1. Badgen har lös text ("Drivs av") som inte går
    // att lyfta med z-index — ett positivt z-index på skenet hade lagt sig ÖVER texten.
    // Negativt z-index målar ovanpå badgens bakgrund men under innehållet, och isolation
    // håller lagret inne i badgen.
    '@keyframes ca-groq-svep{0%{left:-32%}50%{left:96%}100%{left:-32%}}',
    // Färgerna kommer ur Groqs egen SVG: #f43e01 för märket, #f3f3ee för texten. Guldet som
    // låg här förut var husets eget och hörde inte till märket — nu andas plaketten i deras
    // orange i stället, så den ser ut att komma från samma ställe som logotypen i den.
    '@keyframes ca-groq-glod{0%,100%{box-shadow:0 0 16px -8px rgba(244,62,1,.8),inset 0 0 12px -9px rgba(244,62,1,.6)}',
      '50%{box-shadow:0 0 30px -5px rgba(244,62,1,.95),0 0 56px -14px rgba(251,146,60,.7),inset 0 0 18px -8px rgba(251,146,60,.75)}}',
    '.ca-groq-badge{position:relative;overflow:hidden;isolation:isolate;display:inline-flex;',
      'align-items:center;padding:8px 15px;gap:0;text-decoration:none;',
      'border:1px solid rgba(244,62,1,.42);border-radius:14px;',
      'background:linear-gradient(120deg,rgba(244,62,1,.13),rgba(15,12,41,.5) 55%,rgba(244,62,1,.1));',
      'animation:ca-groq-glod 3.4s ease-in-out infinite;transition:transform .16s,border-color .16s;}',
    // Nu när plaketten är en LÄNK ska den svara på att man pekar på den.
    '.ca-groq-badge:hover{transform:translateY(-1px);border-color:rgba(244,62,1,.85);}',
    '.ca-groq-badge:focus-visible{outline:2px solid rgba(244,62,1,.9);outline-offset:3px;}',
    // Logotypen rörs inte: ingen filter, ingen omfärgning, ingen skalning i höjdled. Det är
    // hela poängen med att använda deras fil i stället för en egen tolkning av den.
    '.ca-groq-logo{display:block;height:32px;width:auto;}',
    '.ca-groq-badge::before{content:"";position:absolute;top:-30%;bottom:-30%;left:-32%;width:34%;z-index:-1;',
      'pointer-events:none;border-radius:50%;',
      'background:linear-gradient(90deg,transparent,rgba(244,62,1,.45),rgba(255,214,190,.85),rgba(251,146,60,.45),transparent);',
      'filter:blur(4px);animation:ca-groq-svep 3.4s cubic-bezier(.45,.02,.55,.98) infinite;}',
    // Respektera reduced motion
    '@media(prefers-reduced-motion:reduce){#ca-hero::before,#ca-hero::after,#ca-btn,#ca-btn::after,.ca-card::after,',
      '.ca-groq-badge,.ca-groq-badge::before{animation:none!important;}',
      '.ca-groq-badge::before{display:none;}}'
  ].join('');
  (document.body || document.documentElement).appendChild(s);
})();

/**
 * Formulärets ÖVRE del: samma vandrande färg som bilkorten, och en tydligare läsordning.
 *
 * Heron hade redan en färgring och en aurora, men allt INUTI den var grålila — Demo-raden,
 * elbilspromon, sammanfattningarna och chipsen delade en och samma ton. Bilkorten längre ned
 * skiljer sig däremot åt på färgen (violett, blått, grönt), och det är den skillnaden som
 * saknades uppe i formuläret.
 *
 * Två regler håller det från att bli rörigt:
 *
 * 1. FÄRGEN VANDRAR BARA DÄR MAN KAN GÖRA NÅGOT. Demo-raden, elbilspromon och det valda
 *    chipset lever; notisraderna (Blocket-villkoren, drivmedelssammanfattningen) står stilla
 *    med en tyst färgkant i vänsterkanten. Rör sig allting blir ingenting viktigt.
 * 2. RINGARNA GÅR I OTAKT. Samma grepp som korten: förskjutna negativa delays, annars pulserar
 *    de i lockstep och läser som ett enda blinkande objekt.
 *
 * Ringen återanvänder {@code --ca-rim-ang} och {@code ca-rim} från polishlagret ovan — utan
 * @property står vinkeln stilla och kanten degraderar till en statisk färgring, vilket är ett
 * fullgott utseende i sig.
 *
 * <b>Paint order.</b> Ringen ligger i ::after med z-index 0 och innehållet lyfts till z-index 1.
 * Utan lyftet målas pseudon över texten — samma fälla som en gång tvättade ur bilkortens
 * statiska text när glöden låg i ett absolut ::before.
 *
 * Injiceras i stället för att skrivas i snippeten: WP-sidan är en manuell kopia, och en ren
 * stiländring ska aldrig kräva att den klistras om.
 */
(function caToppCss() {
  if (document.getElementById('ca-topp-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-topp-css';
  // Ringen: en regel, tre värdar. padding = ringens tjocklek, masken skär ur mitten.
  var ring = 'content:"";position:absolute;inset:0;z-index:0;pointer-events:none;'
    + 'border-radius:inherit;padding:2px;'
    + '-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);'
    + 'mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);'
    + '-webkit-mask-composite:xor;mask-composite:exclude;';
  s.textContent = [
    // ── Demo-raden: violett, kortens första familj ──────────────────────────
    '#ca-sub-bar{position:relative;isolation:isolate;}',
    '#ca-sub-bar>*{position:relative;z-index:1;}',
    // Färgerna måste spänna över HELA färghjulet för att rörelsen ska gå att se. Första
    // försöket höll varje ring inom en familj (#8b5cf6 → #a78bfa → #c4b5fd → #6366f1) — det är
    // 19 graders färgton totalt, alltså samma lila hela varvet, och en ring som byter mellan
    // fyra nyanser av samma färg mot en lila botten läser som stillastående även när den
    // bevisligen roterar. Heron har alltid spänt violett → cyan → rosa → bärnsten, och det är
    // därför DEN syns. Familjen bor nu i vilken färg ringen VILAR i (violett här, grönt på
    // promon) medan resan går genom hela hjulet.
    // Samma behandling som heron ger sin egen ring: full mättnad och nästan full opacitet.
    // Korten klarar sig på .55 för att de ligger på mörkt glas där en tunn ring redan har stark
    // kontrast — de övre rutorna ligger på heron egen lila botten, och där drunknar samma
    // inställning. Rutorna får också en svag glöd i familjens färg, samma grepp som kortens
    // hover, så ringen lyfter från bakgrunden i stället för att smälta in i den.
    '#ca-sub-bar{background:rgba(15,12,41,.42);box-shadow:0 6px 26px -12px rgba(139,92,246,.75);}',
    '#ca-sub-bar::after{' + ring + 'opacity:.95;filter:saturate(150%);',
      'background:conic-gradient(from var(--ca-rim-ang),#a78bfa,#38bdf8,#22d3ee,#f472b6,#a78bfa);',
      'animation:ca-rim 13s linear infinite;}',
    // Kvotraden byter till bärnsten när sökningarna tar slut (ca-sub-bar-limited). Ringen
    // måste följa med, annars säger kanten fortfarande "allt är som vanligt".
    '#ca-sub-bar.ca-sub-bar-limited::after{',
      'background:conic-gradient(from var(--ca-rim-ang),#f59e0b,#fbbf24,#fcd34d,#f97316,#f59e0b);}',
    // ── Elbilspromon: grön, samma familj som kort 3 och som rutans egen text ─
    '#ca-ev-promo{position:relative;isolation:isolate;}',
    '#ca-ev-promo>*{position:relative;z-index:1;}',
    '#ca-ev-promo{background:rgba(15,12,41,.42);box-shadow:0 6px 26px -12px rgba(16,185,129,.7);}',
    '#ca-ev-promo::after{' + ring + 'opacity:.95;filter:saturate(150%);',
      'background:conic-gradient(from var(--ca-rim-ang),#34d399,#22d3ee,#a3e635,#2dd4bf,#34d399);',
      'animation:ca-rim 13s linear infinite;animation-delay:-4.4s;}',
    // ── Det valda chipset: blått, kortens andra familj ───────────────────────
    // Bara det AKTIVA chipset ringas. Ringar på alla fem hade gjort valet omöjligt att se —
    // det är skillnaden mot grannarna som bär informationen, inte glansen i sig.
    '.ca-chip{position:relative;}',
    '.ca-chip-aktiv{isolation:isolate;}',
    '.ca-chip-aktiv>*{position:relative;z-index:1;}',
    '.ca-chip-aktiv::after{' + ring + 'opacity:.85;padding:1.5px;',
      'background:conic-gradient(from var(--ca-rim-ang),#38bdf8,#c4b5fd,#f472b6,#22d3ee,#38bdf8);',
      'animation:ca-rim 12s linear infinite;animation-delay:-3s;}',
    // ── Notisraderna: ingen låda alls, bara en färgkant ──────────────────────
    // De två raderna låg i varsin grå platta i exakt samma bredd och ton som fälten ovanför,
    // fast de inte går att ändra i — formuläret läste som nio likadana slabbar i rad. Utan
    // bakgrund faller de tillbaka dit de hör hemma: en anteckning intill det de förklarar.
    '#ca-fuel-sum{background:none;border:none;border-left:2px solid rgba(56,189,248,.55);',
      'border-radius:0;padding:2px 0 2px 11px;}',
    '.ca-blocket-note{font-size:.76rem;line-height:1.45;color:#8b93a7;',
      'padding:2px 0 2px 11px;border-left:2px solid rgba(167,139,250,.45);}',
    '.ca-blocket-note b{color:rgba(226,232,240,.85);font-weight:700;}',
    // ── Rubrikerna i samma ton ───────────────────────────────────────────────
    // "SNABBSTART" satt på .45 och .7rem medan "BILKATEGORI" satt på .65 och .78rem — samma
    // sorts rubrik i två olika styrkor, vilket läser som två olika nivåer utan att vara det.
    // Ett litet färgstreck före varje rubrik: sektionerna går att räkna i förbifarten, och
    // strecket knyter ihop rubrikerna med ringarnas palett. Inline-element i flödet, inte en
    // absolut pseudo — en sådan hade lagt sig över etiketten i stället för bredvid den.
    '#ca-hero .ca-field>label::before{content:"";display:inline-block;',
      'width:3px;height:.72em;margin-right:8px;vertical-align:-1px;border-radius:2px;',
      'background:linear-gradient(180deg,#a78bfa,#38bdf8);}',
    // ── Lodrät rytm: avstånd som grupperar i stället för att radas upp ───────
    // Allt utom rubriken låg på 16 px, vilket gör att ögat inte ser var ett stycke slutar och
    // nästa börjar. Nu är det tätt INOM en grupp och luftigt MELLAN dem. Marginaler mellan
    // syskon kollapsar, så talen nedan är avstånd och inte summor.
    '#ca-hero .ca-sub{margin-bottom:26px;}',
    '#ca-hero #ca-sub-bar{margin-bottom:11px;}',   // hör ihop med promon under
    '#ca-hero #ca-ev-promo{margin-bottom:26px;}',  // slut på "om tjänsten", början på formuläret
    '#ca-hero .ca-grid{margin-bottom:18px;}',
    // Tomma rutnät bär fortfarande sin marginal och lämnar luft mitt i formuläret
    '#ca-hero .ca-grid:empty{margin-bottom:0;}',
    // Notisen sitter ihop med fältet den förklarar, inte mitt emellan två
    '#ca-hero #ca-usedcar-note{margin-top:-6px;}',
    '#ca-hero #ca-fuel-sum{margin:0 0 18px;}',
    '#ca-hero #ca-fler-btn{margin-bottom:20px;}',
    // ── De två raderna sveps igenom när de kommer i bild ────────────────────
    // Ljuset ligger i ::before (ringen har ::after) och går EN gång per sidladdning, inte i
    // loop: en slinga i ögonvrån blir en flimrande skylt, medan ett svep som passerar när
    // raden dyker upp läser som att den slås på. Raderna får sitt svep 0,16 s isär så det
    // känns som en enda rörelse nedför sidan och inte som två samtidiga blixtar.
    //
    // Rutan lutar 14 grader, är suddad och bredare än sin bana, så kanterna aldrig går att
    // urskilja som en rektangel. Innehållet ligger på z-index 1, så ljuset passerar BAKOM
    // texten — samma paint order som ringen.
    '@keyframes ca-svep{from{transform:translateX(-130%) skewX(-14deg)}to{transform:translateX(340%) skewX(-14deg)}}',
    '#ca-sub-bar::before,#ca-ev-promo::before{content:"";position:absolute;top:-25%;bottom:-25%;',
      'left:0;width:40%;z-index:0;pointer-events:none;opacity:0;border-radius:44%;filter:blur(6px);',
      'background:linear-gradient(90deg,transparent,rgba(255,255,255,.44),rgba(196,181,253,.34),transparent);}',
    '#ca-sub-bar.ca-svept::before{opacity:1;animation:ca-svep 1.05s cubic-bezier(.36,0,.2,1) both;}',
    // Promons svep bär dess egen gröna ton, samma logik som ringarnas familjer.
    '#ca-ev-promo.ca-svept::before{opacity:1;animation:ca-svep 1.05s cubic-bezier(.36,0,.2,1) .16s both;',
      'background:linear-gradient(90deg,transparent,rgba(255,255,255,.4),rgba(110,231,183,.36),transparent);}',
    // Raden lyfter sig samtidigt en aning — svepet ensamt läser som en reflex, svepet plus
    // lyftet läser som att raden landar.
    '#ca-sub-bar.ca-svept,#ca-ev-promo.ca-svept{animation:ca-svep-lyft .5s cubic-bezier(.22,1,.36,1) both;}',
    '#ca-ev-promo.ca-svept{animation-delay:.16s;}',
    '@keyframes ca-svep-lyft{from{transform:translateY(7px);opacity:.35}to{transform:none;opacity:1}}',
    // ── Chipsen på mobil: tre per rad, som mobillagret redan syftade till ───
    // minmax(78px,1fr) skulle ge "tre jämnbreda chips även på en 360 px-skärm", men på 390 px
    // ryms fyra — och med fem kategorier blir raderna 4+1 med en ensam Småbil under. 95 px
    // tvingar fram tre kolumner och därmed 3+2, medan laddare-gruppens två chips fortfarande
    // får en halva var (auto-fit skapar aldrig fler kolumner än det finns barn).
    '@media(max-width:520px){#ca-wrap .ca-chips{grid-template-columns:repeat(auto-fit,minmax(82px,1fr));}}',
    // Reduced motion: ringarna står kvar som statiska färgkanter, bara rörelsen tas bort.
    '@media(prefers-reduced-motion:reduce){#ca-sub-bar::after,#ca-ev-promo::after,',
      '.ca-chip-aktiv::after,#ca-sub-bar.ca-svept,#ca-ev-promo.ca-svept,',
      '#ca-sub-bar.ca-svept::before,#ca-ev-promo.ca-svept::before{animation:none!important;}',
      '#ca-sub-bar::before,#ca-ev-promo::before{display:none;}}'
  ].join('');
  (document.body || document.documentElement).appendChild(s);
})();

// Jämförelsetabellens eget lager: glas, glöd och skiftande färg. Injiceras av samma skäl som
// polish-lagret ovan — WP-sidan är en manuell kopia och ska slippa klistras om för en ren
// stiländring. Klasser i stället för inline-stilar här: hover, sticky kolumn, animation och
// ::before/::after går inte att sätta inline, och tabellen har fler celler än något annat på
// sidan (rader × bilar) — inline hade blåst upp markupen i onödan.
(function caCompareCss() {
  if (document.getElementById('ca-compare-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-compare-css';
  // Sticky etikettkolumn: ett LJUST glaslager, inte en mörk platta. Första försöket var
  // rgba(20,14,38,.97) — nästan svart — och det smälte in mot min harness mörka botten men
  // läste som ett hål mitt i rutan på den riktiga sidan, där panelen är upplyst lila.
  // backdrop-filter gör jobbet som ogenomskinligheten gjorde: det som scrollar under suddas
  // bort så etiketten går att läsa. Fallbacken nedan täcker webbläsare utan stöd — utan den
  // syns värdena rakt igenom kolumnen så fort tabellen sidscrollar.
  var LBL = 'linear-gradient(90deg,rgba(255,255,255,.075),rgba(255,255,255,.03))';
  var LBL_FALLBACK = 'linear-gradient(90deg,rgba(49,34,94,.97),rgba(46,32,88,.93))';
  s.textContent = [
    // Tre färglager som vandrar var för sig. inherits:true krävs för att ::before ska ärva
    // dem — animationen sitter på .ca-cmp, gradienterna på pseudon.
    '@property --ca-cmp-f1{syntax:"<color>";initial-value:rgba(139,92,246,.30);inherits:true;}',
    '@property --ca-cmp-f2{syntax:"<color>";initial-value:rgba(56,189,248,.22);inherits:true;}',
    '@property --ca-cmp-f3{syntax:"<color>";initial-value:rgba(236,72,153,.16);inherits:true;}',
    // Åtta stopp = hela färgcirkeln. Alfat hålls lågt och jämnt: det är en TON rutan bär,
    // inte en färgplatta, och texten ska vara lika läsbar i korall som i lila.
    '@keyframes ca-cmp-f1{',
      '0%,100%{--ca-cmp-f1:rgba(139,92,246,.30)}12.5%{--ca-cmp-f1:rgba(217,70,239,.28)}',
      '25%{--ca-cmp-f1:rgba(244,63,94,.26)}37.5%{--ca-cmp-f1:rgba(251,113,90,.26)}',
      '50%{--ca-cmp-f1:rgba(251,191,36,.22)}62.5%{--ca-cmp-f1:rgba(52,211,153,.24)}',
      '75%{--ca-cmp-f1:rgba(45,212,191,.26)}87.5%{--ca-cmp-f1:rgba(59,130,246,.30)}}',
    '@keyframes ca-cmp-f2{',
      '0%,100%{--ca-cmp-f2:rgba(56,189,248,.22)}12.5%{--ca-cmp-f2:rgba(45,212,191,.20)}',
      '25%{--ca-cmp-f2:rgba(132,204,22,.18)}37.5%{--ca-cmp-f2:rgba(251,191,36,.18)}',
      '50%{--ca-cmp-f2:rgba(251,146,60,.20)}62.5%{--ca-cmp-f2:rgba(244,114,182,.20)}',
      '75%{--ca-cmp-f2:rgba(167,139,250,.24)}87.5%{--ca-cmp-f2:rgba(99,102,241,.24)}}',
    '@keyframes ca-cmp-f3{',
      '0%,100%{--ca-cmp-f3:rgba(236,72,153,.16)}12.5%{--ca-cmp-f3:rgba(251,113,90,.16)}',
      '25%{--ca-cmp-f3:rgba(250,204,21,.14)}37.5%{--ca-cmp-f3:rgba(34,197,94,.15)}',
      '50%{--ca-cmp-f3:rgba(20,184,166,.16)}62.5%{--ca-cmp-f3:rgba(56,189,248,.16)}',
      '75%{--ca-cmp-f3:rgba(129,140,248,.18)}87.5%{--ca-cmp-f3:rgba(192,132,252,.17)}}',
    '@keyframes ca-cmp-in{from{opacity:0;transform:translateY(7px)}to{opacity:1;transform:none}}',
    '@keyframes ca-cmp-aurora{0%{transform:translate(0,0) scale(1);opacity:.7}50%{transform:translate(2.5%,-2%) scale(1.09);opacity:1}100%{transform:translate(0,0) scale(1);opacity:.7}}',
    '@keyframes ca-cmp-shift{to{background-position:200% 50%}}',
    // Stapeln fylls VÄNSTER→HÖGER med clip-path och inte med scaleX: en skalad stapel drar
    // ihop segmenten och färgfälten glider på plats, medan clip-path avtäcker dem där de
    // hör hemma. Glimten sveper förbi en gång efteråt, glöden andas sedan vidare.
    '@keyframes ca-cmp-fyll{from{clip-path:inset(0 100% 0 0)}to{clip-path:inset(0 0 0 0)}}',
    '@keyframes ca-cmp-glimt{from{transform:translateX(-130%)}to{transform:translateX(360%)}}',
    '@keyframes ca-cmp-glod{0%,100%{box-shadow:inset 0 1px 3px rgba(0,0,0,.45),0 0 0 1px rgba(255,255,255,.05),0 0 16px -7px rgba(167,139,250,.85)}' +
      '50%{box-shadow:inset 0 1px 3px rgba(0,0,0,.45),0 0 0 1px rgba(255,255,255,.09),0 0 28px -4px rgba(167,139,250,.95)}}',
    '@keyframes ca-cmp-puls{0%,100%{opacity:.55}50%{opacity:1}}',
    // ── Rutan ────────────────────────────────────────────────────────────────
    // isolation:isolate håller aurorans och ringens z-index inne i rutan — utan den
    // kryper de över det som ligger ovanför i det fria jämförelseflödet.
    '.ca-cmp{position:relative;margin-top:40px;border-radius:20px;overflow:hidden;isolation:isolate;' +
      'background-color:rgba(255,255,255,.025);' +
      'background-image:linear-gradient(155deg,var(--ca-cmp-f1,rgba(139,92,246,.30)),' +
        'var(--ca-cmp-f2,rgba(56,189,248,.22)) 48%,var(--ca-cmp-f3,rgba(236,72,153,.16)));' +
      'animation:ca-cmp-f1 37s linear infinite,ca-cmp-f2 53s linear infinite,ca-cmp-f3 71s linear infinite;' +
      'backdrop-filter:blur(16px) saturate(150%);-webkit-backdrop-filter:blur(16px) saturate(150%);' +
      'border:1px solid rgba(167,139,250,.2);' +
      'box-shadow:0 26px 64px -22px rgba(0,0,0,.6),0 0 80px -34px rgba(139,92,246,.55),inset 0 1px 0 rgba(255,255,255,.07);}',
    // Aurora bakom allt: tre färgfält som driver långsamt. Färgerna är OLIKA (lila/cyan/rosa)
    // så rörelsen läses som färg och inte bara som ljusstyrka — samma lärdom som heron gav.
    '.ca-cmp::before{content:"";position:absolute;inset:-25%;z-index:0;pointer-events:none;' +
      'background:radial-gradient(ellipse at 22% 0%,var(--ca-cmp-f1,rgba(139,92,246,.3)),transparent 55%),' +
      'radial-gradient(ellipse at 86% 12%,var(--ca-cmp-f2,rgba(56,189,248,.22)),transparent 52%),' +
      'radial-gradient(ellipse at 62% 100%,var(--ca-cmp-f3,rgba(236,72,153,.16)),transparent 55%);' +
      'animation:ca-cmp-aurora 17s ease-in-out infinite;}',
    // Vandrande färgkant, samma grepp (och samma registrerade vinkel) som heron och korten.
    '.ca-cmp::after{content:"";position:absolute;inset:0;z-index:5;pointer-events:none;' +
      'border-radius:inherit;padding:1.5px;' +
      'background:conic-gradient(from var(--ca-rim-ang),#a78bfa,#38bdf8,#22d3ee,#f472b6,#fbbf24,#a78bfa);' +
      '-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);' +
      'mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);' +
      '-webkit-mask-composite:xor;mask-composite:exclude;' +
      'opacity:.6;filter:saturate(140%);animation:ca-rim 11s linear infinite;}',
    '.ca-cmp>*{position:relative;z-index:1;}',
    // ── Rubrikraden ──────────────────────────────────────────────────────────
    '.ca-cmp-head{display:flex;align-items:center;gap:9px;flex-wrap:wrap;padding:16px 18px 13px;border-bottom:1px solid rgba(255,255,255,.07);}',
    '.ca-cmp-titel{font-size:.74rem;font-weight:800;text-transform:uppercase;letter-spacing:.12em;' +
      'background:linear-gradient(90deg,#c4b5fd,#7dd3fc,#f9a8d4,#c4b5fd);background-size:200% 100%;' +
      '-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent;' +
      'animation:ca-cmp-shift 9s linear infinite;}',
    // Utan background-clip:text blir texten OSYNLIG i stället för ofärgad — fallbacken är
    // alltså inte kosmetisk, den är skillnaden mellan en rubrik och ingen rubrik.
    '@supports not ((background-clip:text) or (-webkit-background-clip:text)){' +
      '.ca-cmp-titel{color:#c4b5fd;-webkit-text-fill-color:#c4b5fd;background:none;animation:none;}}',
    '.ca-cmp-ev{display:inline-flex;align-items:center;gap:5px;font-size:.66rem;font-weight:700;' +
      'letter-spacing:.05em;text-transform:uppercase;color:#7dd3fc;padding:3px 10px;border-radius:999px;' +
      'background:rgba(56,189,248,.12);border:1px solid rgba(56,189,248,.32);' +
      'box-shadow:0 0 18px -5px rgba(56,189,248,.85),inset 0 1px 0 rgba(255,255,255,.08);}',
    '.ca-cmp-ev i{font-style:normal;animation:ca-cmp-puls 2.4s ease-in-out infinite;}',
    '.ca-cmp-legend{margin-left:auto;font-size:.67rem;color:rgba(226,232,240,.42);white-space:nowrap;}',
    '.ca-cmp-legend b{color:#fbbf24;font-weight:700;text-shadow:0 0 10px rgba(251,191,36,.8);}',
    '@media(max-width:640px){.ca-cmp-legend{margin-left:0;}}',
    // ── Tabellen ─────────────────────────────────────────────────────────────
    '.ca-cmp-scroll{overflow-x:auto;}',
    '.ca-cmp-tab{width:100%;border-collapse:separate;border-spacing:0;min-width:430px;}',
    '.ca-cmp-h{padding:13px 14px 11px;text-align:left;vertical-align:bottom;border-bottom:1px solid rgba(255,255,255,.09);transition:background-color .18s;}',
    '.ca-cmp-hbox{display:flex;align-items:center;gap:9px;}',
    '.ca-cmp-hnum{display:block;font-size:.6rem;font-weight:800;text-transform:uppercase;letter-spacing:.09em;color:var(--ca-acc,#a78bfa);}',
    '.ca-cmp-hnamn{display:block;font-weight:700;color:#eef2ff;font-size:.84rem;line-height:1.25;}',
    // Glödande underkant i kolumnens färg: den bär numreringen 1/2/3 nedåt genom tabellen
    // utan att varje cell behöver upprepa färgen.
    '.ca-cmp-h::after{content:"";display:block;height:2px;margin-top:10px;border-radius:2px;' +
      'background:linear-gradient(90deg,var(--ca-acc,#a78bfa),transparent);' +
      'box-shadow:0 0 12px var(--ca-acc,#a78bfa);opacity:.8;}',
    // Emblemplattan i huvudet är mindre än kortets, och monogrammet är reservvägen för de
    // märken som saknar fil — hellre två bokstäver i kolumnens färg än en tom lucka.
    '.ca-cmp-hbox .ca-emblem{width:32px;height:32px;padding:4px;border-radius:9px;}',
    '.ca-cmp-mono{width:32px;height:32px;flex-shrink:0;border-radius:9px;display:flex;align-items:center;' +
      'justify-content:center;font-size:.66rem;font-weight:800;letter-spacing:.02em;color:var(--ca-acc,#a78bfa);' +
      'background:rgba(255,255,255,.06);border:1.5px solid var(--ca-acc,#a78bfa);' +
      'box-shadow:0 0 14px -4px var(--ca-acc,#a78bfa),inset 0 1px 0 rgba(255,255,255,.08);}',
    '.ca-cmp-hoek{position:sticky;left:0;z-index:4;width:152px;padding:10px 14px;background:' + LBL + ';' +
      'backdrop-filter:blur(14px) saturate(150%);-webkit-backdrop-filter:blur(14px) saturate(150%);' +
      'border-bottom:1px solid rgba(255,255,255,.09);border-right:1px solid rgba(255,255,255,.07);}',
    // Etikettkolumnen fastnar vid vänsterkanten: utan den vet man inte VAD man läser så fort
    // tabellen sidscrollat ett steg, och på mobil scrollar den alltid.
    // Etiketterna radbryter. Med nowrap sköt "Motor & batterialternativ" ut ur kolumnen och
    // lade sig över Bil 1 — bredden sätts av hörncellen och etiketten brydde sig inte om den.
    '.ca-cmp-lbl{position:sticky;left:0;z-index:3;padding:11px 13px;text-align:left;font-size:.72rem;' +
      'font-weight:700;color:rgba(226,232,240,.66);line-height:1.35;vertical-align:middle;' +
      'letter-spacing:.01em;border-bottom:1px solid rgba(255,255,255,.05);background:' + LBL + ';' +
      'backdrop-filter:blur(14px) saturate(150%);-webkit-backdrop-filter:blur(14px) saturate(150%);' +
      'border-right:1px solid rgba(255,255,255,.07);transition:color .18s,box-shadow .18s;}',
    '@supports not ((backdrop-filter:blur(1px)) or (-webkit-backdrop-filter:blur(1px))){' +
      '.ca-cmp-lbl,.ca-cmp-hoek{background:' + LBL_FALLBACK + ';}}',
    '.ca-cmp-c{padding:11px 14px;vertical-align:top;border-bottom:1px solid rgba(255,255,255,.045);transition:background-color .18s,box-shadow .18s;}',
    // Raderna vecklar in sig i tur och ordning när tabellen ritas
    '.ca-cmp-rad{animation:ca-cmp-in .5s cubic-bezier(.22,1,.36,1) both;animation-delay:calc(var(--i,0)*42ms);}',
    '.ca-cmp-rad:hover .ca-cmp-c{background-color:rgba(255,255,255,.045);}',
    '.ca-cmp-rad:hover .ca-cmp-lbl{color:#fff;box-shadow:inset 3px 0 0 rgba(167,139,250,.9);}',
    // Kolumnmarkering. En ren CSS-lösning kräver :has() på tabellen; data-attributet sätts
    // i stället av ETT mouseover på rutan och funkar överallt.
    '.ca-cmp[data-hov="0"] .ca-cmp-k0,.ca-cmp[data-hov="1"] .ca-cmp-k1,.ca-cmp[data-hov="2"] .ca-cmp-k2{background-color:rgba(255,255,255,.05);}',
    '.ca-cmp[data-hov="0"] th.ca-cmp-k0,.ca-cmp[data-hov="1"] th.ca-cmp-k1,.ca-cmp[data-hov="2"] th.ca-cmp-k2{background-color:rgba(255,255,255,.06);}',
    // Bäst i raden: guldton + stjärna. Markeringen sätts bara när det finns EN vinnare.
    '.ca-cmp-vinst{background-image:linear-gradient(90deg,rgba(251,191,36,.14),transparent 72%);' +
      'box-shadow:inset 0 0 0 1px rgba(251,191,36,.15);}',
    '.ca-cmp-vinst .ca-cmp-chip{border-color:rgba(251,191,36,.5);box-shadow:0 0 20px -5px rgba(251,191,36,.95),inset 0 1px 0 rgba(255,255,255,.1);}',
    '.ca-cmp-stjarna{float:right;margin:0 0 2px 7px;font-size:.68rem;color:#fbbf24;filter:drop-shadow(0 0 6px rgba(251,191,36,.85));}',
    // ── Värdena ──────────────────────────────────────────────────────────────
    '.ca-cmp-chip{display:inline-block;font-size:.76rem;font-weight:700;padding:3px 10px;border-radius:999px;' +
      'white-space:nowrap;color:#f1f5f9;border:1px solid rgba(255,255,255,.1);' +
      'box-shadow:inset 0 1px 0 rgba(255,255,255,.08);transition:transform .16s,box-shadow .16s;}',
    '.ca-cmp-c:hover .ca-cmp-chip{transform:translateY(-1px);}',
    '.ca-cmp-tom{color:rgba(255,255,255,.22);}',
    '.ca-cmp-pris{color:#a5f3fc;font-weight:700;font-size:.85rem;text-shadow:0 0 14px rgba(165,243,252,.4);}',
    '.ca-cmp-lank{color:#7dd3fc;font-size:.82rem;font-weight:700;text-decoration:none;' +
      'border-bottom:1px solid rgba(125,211,252,.3);transition:color .16s,text-shadow .16s,border-color .16s;}',
    '.ca-cmp-lank:hover{color:#bae6fd;text-shadow:0 0 14px rgba(125,211,252,.85);border-color:rgba(125,211,252,.75);}',
    '.ca-cmp-lista{margin:0;padding-left:15px;}',
    '.ca-cmp-lista li{font-size:.78rem;line-height:1.45;color:rgba(226,232,240,.78);margin-bottom:3px;}',
    '.ca-cmp-lista li::marker{color:rgba(167,139,250,.75);}',
    '.ca-cmp-minus{color:#fca5a5;font-size:.8rem;line-height:1.45;}',
    '.ca-cmp-cit{font-size:.79rem;line-height:1.5;color:rgba(226,232,240,.78);font-style:italic;}',
    '.ca-cmp-txt{font-size:.79rem;color:rgba(226,232,240,.78);}',
    '.ca-cmp-sub{display:block;font-size:.7rem;color:rgba(226,232,240,.45);margin-top:2px;}',
    '.ca-cmp-stjarnor{font-size:.98rem;letter-spacing:.06em;color:#fcd34d;text-shadow:0 0 14px rgba(252,211,77,.55);}',
    '.ca-cmp-opt{display:inline-block;font-size:.72rem;color:rgba(226,232,240,.7);background:rgba(255,255,255,.06);' +
      'border:1px solid rgba(255,255,255,.07);border-radius:12px;padding:2px 9px;margin:2px 3px 2px 0;}',
    '.ca-cmp-hjalp{border-bottom:1px dotted rgba(255,255,255,.35);cursor:help;}',
    // ── TCO-stapeln ──────────────────────────────────────────────────────────
    '.ca-cmp-tco{padding:15px 18px 17px;border-top:1px solid rgba(255,255,255,.07);}',
    '.ca-cmp-tco-rub{font-size:.64rem;font-weight:800;text-transform:uppercase;letter-spacing:.11em;' +
      'color:rgba(167,139,250,.8);margin-bottom:11px;text-shadow:0 0 16px rgba(167,139,250,.45);}',
    '.ca-cmp-bar-rad{margin-bottom:11px;}',
    '.ca-cmp-bar-topp{display:flex;justify-content:space-between;align-items:center;gap:9px;margin-bottom:5px;}',
    '.ca-cmp-bar-namn{display:flex;align-items:center;gap:7px;min-width:0;font-size:.76rem;color:rgba(226,232,240,.72);}',
    '.ca-cmp-bar-namn .ca-emblem{width:22px;height:22px;padding:3px;border-radius:6px;box-shadow:none;}',
    '.ca-cmp-bar-namn .ca-cmp-mono{width:22px;height:22px;border-radius:6px;font-size:.55rem;}',
    '.ca-cmp-lag{font-size:.6rem;font-weight:800;text-transform:uppercase;letter-spacing:.06em;color:#fbbf24;' +
      'background:rgba(251,191,36,.13);border:1px solid rgba(251,191,36,.4);border-radius:999px;padding:1px 8px;' +
      'white-space:nowrap;box-shadow:0 0 16px -5px rgba(251,191,36,.95);}',
    '.ca-cmp-bar-sum{font-size:.78rem;font-weight:800;color:#a5f3fc;white-space:nowrap;text-shadow:0 0 14px rgba(165,243,252,.45);}',
    // Två animationer på samma element: fyllningen körs en gång, glöden pulsar vidare och
    // startar först när stapeln är helt framme (fördröjningarna nedan följer varandra).
    '.ca-cmp-bar{position:relative;display:flex;height:18px;border-radius:7px;overflow:hidden;' +
      'background:rgba(255,255,255,.055);' +
      'animation:ca-cmp-fyll 1.05s cubic-bezier(.22,1,.36,1) both,ca-cmp-glod 4.6s ease-in-out infinite;' +
      'animation-delay:calc(var(--i,0)*150ms),calc(var(--i,0)*150ms + 1.05s);}',
    // Glimten: en ljusstrimma som sveper igenom precis när fyllningen når fram
    '.ca-cmp-bar::after{content:"";position:absolute;top:0;left:0;width:26%;height:100%;pointer-events:none;' +
      'background:linear-gradient(100deg,transparent,rgba(255,255,255,.55),transparent);' +
      'transform:translateX(-130%);animation:ca-cmp-glimt 1.4s ease-out both;' +
      'animation-delay:calc(var(--i,0)*150ms + .5s);}',
    '.ca-cmp-seg{height:100%;flex-shrink:0;transition:filter .2s;' +
      'box-shadow:inset 0 1px 0 rgba(255,255,255,.24),inset 0 -7px 11px -7px rgba(0,0,0,.5);}',
    '.ca-cmp-bar:hover{animation-play-state:paused;box-shadow:inset 0 1px 3px rgba(0,0,0,.45),0 0 0 1px rgba(255,255,255,.12),0 0 32px -3px rgba(167,139,250,1);}',
    '.ca-cmp-bar:hover .ca-cmp-seg{filter:saturate(145%) brightness(1.2);}',
    '.ca-cmp-legend-rad{display:flex;flex-wrap:wrap;gap:10px;margin-top:9px;}',
    '.ca-cmp-leg{display:inline-flex;align-items:center;gap:5px;font-size:.65rem;color:rgba(226,232,240,.5);}',
    '.ca-cmp-prick{width:9px;height:9px;border-radius:3px;flex-shrink:0;box-shadow:0 0 9px -1px currentColor;}',
    '@media(max-width:520px){.ca-cmp{margin-top:28px;border-radius:16px;}' +
      '.ca-cmp-c,.ca-cmp-lbl{padding:9px 10px;}.ca-cmp-hoek{width:118px;padding:9px 10px;}' +
      '.ca-cmp-lbl{font-size:.68rem;}.ca-cmp-h{padding:11px 10px 9px;}.ca-cmp-tab{min-width:390px;}}',
    // animation:none tar bort clip-path-fyllningen också, och då står stapeln helt framme
    // direkt — den slocknar alltså inte, den slutar bara röra sig.
    '@media(prefers-reduced-motion:reduce){.ca-cmp,.ca-cmp::before,.ca-cmp::after,.ca-cmp-titel,.ca-cmp-ev i,' +
      '.ca-cmp-rad,.ca-cmp-bar,.ca-cmp-bar::after{animation:none!important;}' +
      '.ca-cmp-bar::after{display:none;}}'
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

/**
 * Loggar ett svalt fel i stället för att tiga.
 *
 * Återställningsfunktionerna (sparad sökning, localStorage, URL-parametrar) ligger i ett enda
 * try/catch, så ett saknat element avbryter mitt i och lämnar formuläret HALVT ifyllt — utan
 * ett ljud. Det hände på riktigt 2026-08-10 när bagagefältet skulle återställas: kategorin hann
 * sättas, resten inte, och fixen såg trasig ut fast den var korrekt. Samma sorts tystnad gjorde
 * både AI:ns påhittade Blocket-siffra och rate limit-felet svåra att hitta samma dag.
 *
 * console.warn och inte throw: en trasig sparad sökning ska inte fälla hela sidan.
 */
function caWarn(vad, e) {
  try { console.warn('CarAdvice: ' + vad + ' avbröts — formuläret kan vara halvt ifyllt', e); } catch (x) {}
}
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
  if (hide) {
    document.getElementById('ca-fuel').value = 'spelar ingen roll';
  } else {
    // LADDBOX HEMMA => EL, oavsett kategori. Regeln fanns förut bara för familjebil, utan att
    // det fanns något som gjorde familjebilar mer elbenägna än SUV:ar eller småbilar — den som
    // säger sig ha laddbox hemma har svarat på drivmedelsfrågan i praktiken.
    //
    // "Nej" tar tillbaka förvalet i stället för att låsa kvar el: annars hade ett felklick på
    // Ja lämnat sökningen elbilsbunden utan att något syntes.
    //
    // Rör aldrig ett eget val (dataset.rord) — samma regel som växellådan och åldern.
    var charger = document.getElementById('ca-charger');
    var fuel = document.getElementById('ca-fuel');
    if (!caForvalPaus && charger && fuel && !fuel.dataset.rord) {
      fuel.value = (charger.value === 'true') ? 'el' : 'spelar ingen roll';
    }
  }

  // VÄXELLÅDAN följer numera drivmedlet också, inte bara kategorin. Kategorivägen dolde den
  // redan för "elbil"/"laddhybrid", men DRIVMEDELSRUTAN gjorde det inte: "Småbil" + "El"
  // lämnade frågan kvar, och en ren elbil har ingen växellåda att välja. Värre än onödig —
  // ett kvarglömt "Manuell" från ett tidigare bensinsök följde med i payloaden och blev
  // "rekommendera endast bilar med denna växellåda" på ett elbilssök. Därför nollställs den
  // också, inte bara göms.
  var fuelVal   = document.getElementById('ca-fuel').value;
  var doljTrans = hide || fuelVal === 'el';
  if (transField) transField.style.display = doljTrans ? 'none' : '';
  if (doljTrans) { var tr = document.getElementById('ca-transmission'); if (tr) tr.value = 'spelar ingen roll'; }

  // LADDARE HEMMA används BARA till att styra bort från el: svaret "nej" blir texten "undvik
  // renodlad elbil (BEV) och laddhybrid (PHEV)" i buildPrompt. Har användaren redan valt
  // bensin, diesel eller hybrid är frågan meningslös. Svaret rörs INTE, bara frågan göms —
  // byter man tillbaka till el står det kvar som det stod.
  //
  // Rutan har inget eget id i markupen (till skillnad från #ca-fuel-field), därav closest().
  var chgEl    = document.getElementById('ca-charger');
  var chgField = (chgEl && chgEl.closest) ? chgEl.closest('.ca-field') : null;
  var laddbart = hide || fuelVal === 'el' || fuelVal === 'spelar ingen roll';
  if (chgField) chgField.style.display = laddbart ? '' : 'none';

  caUpdateMaxAgeVisibility();
  // Drivmedlet sätts här PROGRAMMATISKT (laddbox hemma blir "el"), och en tilldelning
  // i JS utlöser inget change-event. Utan de här anropen visade budgetrutan bensinkombiernas
  // priser — och bagagestegen bensinbilarnas ankare — för en sökning appen själv just gjort
  // till en elbilssökning.
  caRenderEvBudgetHint();
  caRenderCargoLevels();
  // Sist: fältet kan just ha gömts eller tvingats om, och förvalet läser det läget.
  caVaxelladeForval();
  caAlderForval();
  caSynkaChips();
  caDrivmedelsrad();
}

/**
 * Raden som säger vilket drivmedel sökningen faktiskt får.
 *
 * <p><b>Varför den behövs.</b> Drivmedelsrutan ligger sedan 2026-09-04 under "Fler val", och
 * laddarfrågan avgör värdet i båda riktningarna. Ja syntes redan (rutan sattes till El), men
 * NEJ syntes inte alls: chippet stod kvar på "Spelar ingen roll" medan payloaden
 * {@code charger:false} ger prompten "undvik renodlad elbil (BEV) och laddhybrid (PHEV) —
 * föreslå ENDAST elhybrid (HEV)". Appen hade alltså bestämt bensin/hybrid utan att säga det.
 *
 * <p>Raden ändrar ingenting i det som skickas. Den läser bara läget och skriver ut det, så att
 * ett förval aldrig är osynligt — samma princip som att ett eget klick (dataset.rord) alltid
 * vinner över förvalet.
 */
function caDrivmedelsrad() {
  var rad = document.getElementById('ca-fuel-sum');
  if (!rad) return;                          // körs före caFlerVal vid sidladdning
  var cat  = document.getElementById('ca-category');
  var fuel = document.getElementById('ca-fuel');
  var chg  = document.getElementById('ca-charger');
  if (!cat || !fuel) { rad.style.display = 'none'; return; }

  // Elbil och laddhybrid som KATEGORI svarar redan på frågan — då göms rutan, och då ska
  // raden inte upprepa den.
  var k = caCanonCat ? caCanonCat(cat.value) : cat.value;
  if (k === 'elbil' || k === 'laddhybrid') { rad.style.display = 'none'; return; }

  var v = fuel.value;
  var text;
  if (v === 'el')            text = '⚡ El';
  else if (v === 'bensin')   text = '⛽ Bensin';
  else if (v === 'diesel')   text = '🛢️ Diesel';
  else if (v === 'hybrid')   text = '♻️ Hybrid (ej laddbar)';
  else if (chg && chg.value === 'false') text = '⛽ Bensin eller elhybrid — inget laddbart';
  else                       text = '✨ Spelar ingen roll';

  rad.style.display = '';
  rad.innerHTML = '<span class="ca-fuel-sum-etikett">Drivmedel</span>'
    + '<span class="ca-fuel-sum-varde">' + text + '</span>'
    + '<span class="ca-fuel-sum-hjalp">ändra under Fler val</span>';
}

/**
 * Åldersrutan startar dold i markupen och visades bara när ny/begagnad stod på "begagnad".
 *
 * <p>När ny/begagnad togs bort ur formuläret föll den grinden ihop till "returnera tidigt",
 * och rutan blev ONÅBAR: inte gömd bakom "Fler val" utan borta även utfälld. Åldern gick
 * fortfarande med i sökningen — förvalet per kategori — men gick inte att ändra.
 *
 * <p>Nu är varje sökning begagnad, så rutan ska alltid vara framme. Kvar att göra är bara
 * att lyfta markupens style="display:none".
 */
function caUpdateMaxAgeVisibility() {
  var maxAgeField = document.getElementById('ca-maxage-field');
  if (!maxAgeField) return;
  maxAgeField.style.display = '';
}

// ── Ny/begagnad togs bort ur formuläret 2026-08-28 ───────────────────────────
// Frågan var ställd till användaren men besvarad av appen: rekommendationerna prisas mot
// Blockets BEGAGNATANNONSER, och budgettaket mäts mot billigaste annonsen. Ett "Ny" i rutan
// bytte måttstock till nypriset utan att någonstans säga det. Nu är svaret alltid "begagnad",
// och rutan är utbytt mot en notis som säger vad appen faktiskt visar.
//
// FÄLTET FÅR ALLTSÅ SAKNAS. Alla avläsningar av #ca-newcar går genom caIsNewCar(), som
// svarar false när elementet är borta — läser man .value direkt på ett borttaget element
// kastar sidan TypeError innan formuläret ens skickats.
function caIsNewCar() {
  var el = document.getElementById('ca-newcar');
  return !!el && el.value === 'true';
}

// Åldersfiltret satt förr fast i villkoret `el && nc && nc.value !== 'true'`. Utan #ca-newcar
// blev `nc` null, hela uttrycket false och maxAgeYears null — ålderskravet hade FÖRSVUNNIT
// tyst ur varje sökning. Samlat här så att båda anroparna (sparad sökning och den skarpa
// payloaden) får exakt samma svar.
function caMaxAgeYears() {
  if (caIsNewCar()) return null;
  var el = document.getElementById('ca-maxage');
  // SAKNAS rutan gäller förvalet — inte "inget ålderskrav". Rullgardinen togs bort ur
  // formuläret 2026-08-28 (man är nästan alltid ute efter en bil på högst fem år, och en
  // fråga vars svar är detsamma varje gång är en fråga för mycket), men årtalet används
  // fortfarande: det står som ÅLDERSKRAV i prompten och avgör vilka årsmodeller Blocket-
  // uppslaget mäter priset mot. Returnerades null här hade kravet försvunnit tyst med rutan
  // — exakt samma fälla som #ca-newcar bar, och den upptäcktes bara för att den letades upp.
  if (!el || !el.value) return parseInt(CA_MAXAGE_FORVAL);
  var n = parseInt(el.value);
  return isNaN(n) ? parseInt(CA_MAXAGE_FORVAL) : n;
}

// Nytt åldersförval: max 5 år (markupen hade 10). Körs FÖRE caLoadPrefs och caReadUrlParams,
// så en sparad inställning och en delningslänk fortfarande vinner över förvalet.
//
// Byggs från JS och inte bara i HTML-snippeten av samma skäl som bagagefältet nedan:
// WordPress-sidan är en manuell kopia, så en ändring i snippeten syns inte förrän den
// klistrats in på nytt. Funktionen är idempotent och klarar båda kopiorna — den med rutan
// kvar och den utan.
var CA_MAXAGE_FORVAL = '5';

// Körsträckan förvald till genomsnittssvenskens 1 243 mil i stället för den runda 1 500:an.
// Samma siffra som Elbilsassistenten använder — står två appar på samma sida och säger olika
// saker om vad en normal körsträcka är, är minst en av dem fel.
//
// step=1 hör ihop med talet: markupen hade step=100, och 1243 är inte en multipel av det.
// Fältet blir då stepMismatch (alltså :invalid) och pilarna hoppar till 1200/1300.
var CA_KM_FORVAL = '1243';
function caForvalKorstracka() {
  var km = document.getElementById('ca-km');
  if (!km) return;
  km.step = '1';
  km.value = CA_KM_FORVAL;
  if (document.getElementById('ca-km-hint')) return;   // idempotent: snippeten kan bära den
  var hint = document.createElement('div');
  hint.id = 'ca-km-hint';
  hint.style.cssText = 'font-size:0.72rem;color:#8b93a7;margin-top:5px;line-height:1.4;';
  hint.innerHTML = 'Genomsnittssvensken kör <b>1 243 mil/år</b> — ändra till din egen siffra.';
  km.parentNode.insertBefore(hint, km.nextSibling);
}

// ── Val som knappar i stället för rullgardiner ───────────────────────────────
// En rullgardin döljer alternativen tills man öppnar den, och tvingar fram två klick för
// ett val mellan fem. Med fyra fält kvar i formuläret finns det plats att visa dem: ikonen
// säger vad valet ÄR innan man läst etiketten, och det aktiva valet syns utan att man
// öppnar något.
//
// SELECTEN BLIR KVAR och är fortfarande värdet — knapparna sätter .value och skickar
// change. Allt annat (caLoadPrefs, caReadUrlParams, caCheckChanges, payloaden) läser
// selecten och behöver inte veta att den fått ett nytt ansikte. Samma grepp som
// märkesväljaren använder mot sin dolda select.
var caChipsRader = [];

var CA_IKONER = {
  'ca-category':     { familjebil: '\uD83D\uDC6A', suv: '\uD83D\uDE99', elbil: '\u26A1',
                       laddhybrid: '\uD83D\uDD0C', smaabil: '\uD83D\uDE97' },
  'ca-fuel':         { 'spelar ingen roll': '\u2728', bensin: '\u26FD', diesel: '\uD83D\uDEE2\uFE0F',
                       hybrid: '\u267B\uFE0F', el: '\u26A1' },
  'ca-transmission': { 'spelar ingen roll': '\u2728', manuell: '\uD83D\uDD79\uFE0F', automat: '\uD83D\uDD04' },
  'ca-charger':      { 'true': '\uD83C\uDFE0', 'false': '\uD83D\uDEAB' }
};

/** Bygger en knapprad ur en selects egna options. Idempotent. */
function caChips(id) {
  var sel = document.getElementById(id);
  if (!sel || sel.dataset.chips) return;
  var ikoner = CA_IKONER[id] || {};
  sel.dataset.chips = '1';
  sel.style.display = 'none';

  var rad = document.createElement('div');
  rad.className = 'ca-chips';
  Array.prototype.forEach.call(sel.options, function (o) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'ca-chip';
    b.dataset.varde = o.value;
    // Prislappen st\u00e5r bara p\u00e5 kategoriknapparna: det \u00e4r de som b\u00e4r ett f\u00f6rval, och en
    // rad under "Ja"/"Nej" i laddboxfr\u00e5gan hade varit brus.
    var forval = id === 'ca-category' ? CA_KAT_FORVAL[caCanonCat(o.value)] : null;
    b.innerHTML = '<span class="ca-chip-ikon">' + (ikoner[o.value] || '\u2022') + '</span>'
      + '<span class="ca-chip-txt">' + caEsc(o.textContent) + '</span>'
      + (forval ? '<span class="ca-chip-hint">' + caEsc(forval.hint) + '</span>' : '');
    rad.appendChild(b);
  });
  sel.parentNode.insertBefore(rad, sel.nextSibling);

  function synka() {
    Array.prototype.forEach.call(rad.children, function (b) {
      b.classList.toggle('ca-chip-aktiv', b.dataset.varde === sel.value);
    });
  }
  rad.addEventListener('click', function (e) {
    var b = e.target.closest('.ca-chip');
    if (!b) return;
    sel.value = b.dataset.varde;
    // Växellådan slutar följa kategorin, och drivmedlet slutar följa laddboxen, så snart man
    // valt själv — se caVaxelladeForval och caUpdateFuelVisibility.
    if (id === 'ca-transmission' || id === 'ca-fuel') sel.dataset.rord = '1';
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    synka();
  });
  caChipsRader.push(synka);
  synka();
}

/**
 * Håller knapparna i takt med selecten när NÅGON ANNAN ändrar den.
 *
 * <p>En tilldelning i JS utlöser inget change-event, och formuläret sätter värden
 * programmatiskt på flera ställen: sparade sökningar, delningslänkar, Nollställ, och
 * caUpdateFuelVisibility som tvingar drivmedlet till "spelar ingen roll" för elbil. Utan
 * den här skulle knapparna visa ett annat val än det som faktiskt skickas.
 */
function caSynkaChips() { caChipsRader.forEach(function (f) { f(); }); }

/**
 * Växellådans förval följer kategorin: AUTOMAT överallt utom på Småbil, där manuell är
 * regel och inte undantag i det prisläget.
 *
 * <p>"Ekonomibil" är samma sak som Småbil — det är ett ALIAS (CA_CAT_ALIAS) från en äldre
 * version av formuläret, och lever kvar i sparade sökningar och delningslänkar. Därför
 * jämförs den kanoniserade kategorin, inte råvärdet.
 *
 * <p>Rör aldrig ett val användaren gjort själv (dataset.rord), och aldrig när fältet är
 * dolt: för elbil och laddhybrid tvingar caUpdateFuelVisibility värdet till "spelar ingen
 * roll", och ett förval ovanpå det hade skickat en växellåda på en bil som inte har någon.
 */
/**
 * Åldersförvalet följer kategorin.
 *
 * <p>Elbil och laddhybrid: 5 år. Tekniken och räckvidden rör sig snabbt, och batterigarantin
 * är det som avgör om en äldre bil är ett fynd eller en risk.
 *
 * <p>Familjebil och SUV: 5 år. De köps som bruksbilar och ska hålla några år till, så
 * garanti och servicehistorik väger tyngre än det sista prisavdraget.
 *
 * <p>Småbil/ekonomibil: 10 år. Där är en äldre bil ofta hela poängen — en tio år gammal
 * kombi är billig, driftsäker och väl beprövad, och ett femårstak hade stängt ute precis
 * det utbudet.
 *
 * <p>Samma två regler som växellådan: rör aldrig ett val användaren gjort själv, och jämför
 * den KANONISERADE kategorin — "ekonomibil" är ett alias för "smaabil" och lever kvar i
 * sparade sökningar.
 */
var CA_ALDER_PER_KATEGORI = { elbil: '5', laddhybrid: '5', familjebil: '5', suv: '5', smaabil: '10' };

/**
 * Prislappen på kategoriknappen — och förvalet den sätter.
 *
 * <p>Ersätter snabbstartsraden, som var fyra knappar ovanför fem kategoriknappar där båda
 * raderna gjorde nästan samma sak. Nu bär kategorin sitt eget förval, och formuläret blev en
 * rad kortare utan att något val försvann.
 *
 * <p><b>Beloppen är hämtade ur {@link CA_BUDGET_LEVELS}, inte påhittade.</b> Varje kategori
 * får den nivå där segmentets normala bilar faktiskt börjar: familjebil 300 000 (Enyaq från
 * 279 000 som elbil), SUV 350 000 (XC60 från 308 000), elbil 300 000, laddhybrid 250 000
 * (Passat GTE 199 000, V60 T8 209 000) och småbil 125 000 (Picanto 84 000, Yaris 125 000).
 * Alla ligger dessutom på reglagets steg om 25 000 från 50 000 — ett förval som inte går att
 * ställa in för hand hade sett ut som ett fel.
 *
 * <p>Kortformen "300k" är inte kosmetik. Fem lika breda knappar i heroens spalt ger 49 px
 * innanför kanterna, och "el · 300 000 kr" mätte 65 px — den spillde ut ur knappen medan de
 * andra låg på 48 av 49. Utskrivet belopp fick alltså plats bara så länge inget drivmedel stod
 * före det.
 *
 * <p>Bara familjebil bär ett drivmedel, och det är arvet från snabbstartens "Barnfamilj":
 * el förutsätter laddbox och är ett verkligt val, medan "bensin" på småbil hade varit en
 * gissning om en köpare vi inte vet något om.
 */
var CA_KAT_FORVAL = {
  familjebil: { budget: 300000, drivmedel: 'el', hint: 'el · 300k' },
  suv:        { budget: 350000,                  hint: '350k' },
  elbil:      { budget: 300000,                  hint: '300k' },
  laddhybrid: { budget: 250000,                  hint: '250k' },
  smaabil:    { budget: 125000,                  hint: '125k' }
};

/**
 * Sätter kategorins förval — men aldrig över något användaren själv bestämt.
 *
 * <p>Samma regel som växellådan, åldern och drivmedlet: {@code dataset.rord} betyder att
 * människan valt själv, och då rör vi ingenting. Reglaget får sin flagga när man drar i det,
 * och {@code caForvalPaus} stänger av hela mekanismen medan en sparad sökning, en
 * delningslänk eller en historikpost återställs — de ÄR egna val, de sattes bara
 * programmatiskt.
 */
function caKategoriForval() {
  if (caForvalPaus) return;
  var kat = caCanonCat(document.getElementById('ca-category').value);
  var f = CA_KAT_FORVAL[kat];
  if (!f) return;
  var slider = document.getElementById('ca-budget-slider');
  if (slider && !slider.dataset.rord && String(slider.value) !== String(f.budget)) {
    slider.value = f.budget;
    slider.dispatchEvent(new Event('input', { bubbles: true }));
  }
  var fuel = document.getElementById('ca-fuel');
  if (f.drivmedel && fuel && !fuel.dataset.rord && fuel.value !== f.drivmedel) {
    fuel.value = f.drivmedel;
    fuel.dispatchEvent(new Event('change', { bubbles: true }));
  }
}

/**
 * Kör en återställning UTAN att de smarta förvalen får säga sitt.
 *
 * <p>De tre förvalen (drivmedel, växellåda, ålder) backar för ett eget val via dataset.rord.
 * En sparad sökning, en delningslänk och en historikpost ÄR egna val — de sattes bara
 * programmatiskt, och en JS-tilldelning bär ingen flagga. Utan skydd skrev förvalet över dem
 * i samma andetag som de återställdes: en sparad bensinsökning med laddbox hemma kom tillbaka
 * som elbilssökning.
 *
 * <p>FÖRSTA försöket satte dataset.rord vid återställningen, och det var värre än felet det
 * lagade: ca-prefs skrivs vid VARJE sökning, så alla som använt appen förut fick flaggan satt
 * redan vid sidladdning — och då slog laddbox-regeln aldrig till igen under hela besöket.
 * Skyddet måste gälla ÖGONBLICKET, inte resten av sessionen. dataset.rord betyder fortfarande
 * exakt en sak: människan klickade själv.
 */
var caForvalPaus = false;

function caUtanForval(fn) {
  caForvalPaus = true;
  try { fn(); } finally { caForvalPaus = false; }
}

/** Nollställ ska ge ett JUNGFRULIGT formulär — annars sitter förra sökningens egna val kvar för alltid. */
function caSlappEgnaVal() {
  ['ca-fuel', 'ca-transmission', 'ca-maxage', 'ca-budget-slider'].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) delete el.dataset.rord;
  });
}

function caAlderForval() {
  var a = document.getElementById('ca-maxage');
  if (caForvalPaus || !a || a.dataset.rord) return;
  var kat = caCanonCat(document.getElementById('ca-category').value);
  var v = CA_ALDER_PER_KATEGORI[kat];
  if (!v) return;
  a.value = v;
}

function caVaxelladeForval() {
  var t = document.getElementById('ca-transmission');
  var falt = document.getElementById('ca-transmission-field');
  if (caForvalPaus || !t || t.dataset.rord) return;
  if (falt && falt.style.display === 'none') return;
  var kat = caCanonCat(document.getElementById('ca-category').value);
  t.value = (kat === 'smaabil') ? 'manuell' : 'automat';
  caSynkaChips();
}

/**
 * Fyra fält framme, resten under "Fler val".
 *
 * <p>Formuläret hade nio rutor, och de flesta har ett förval som stämmer för nästan alla.
 * Det som verkligen styr svaret är kategori, budget, laddmöjlighet och drivmedel — resten är
 * finjustering. Nio frågor läser som ett formulär man ska fylla i; fyra läser som en fråga
 * man ska svara på.
 *
 * <p><b>Ihopfällt, inte borttaget.</b> Varje ruta här styr något riktigt: körsträckan sätter
 * milprofilen och driftkostnadsregeln, växellådan och bagaget har egna kodvakter, och åldern
 * avgör vilka årsmodeller Blocket mäts mot. Raderade hade de tagit sina vakter med sig — se
 * vad som nästan hände när #ca-newcar försvann och maxAgeYears tyst blev null.
 *
 * <p>Rutorna FLYTTAS in i behållaren i stället för att döljas, så rutnätet inte får hål.
 * Körs efter caEnsureCargoField, eftersom bagagefältet injiceras därifrån.
 */
function caFlerVal() {
  if (document.getElementById('ca-fler')) return;

  // PASSAGERARE bort ur formuläret HELT, inte in under Fler val. Behöver man en större bil
  // väljer man kategorin Familjebil, och den tänder samma vakt: requiresFamilySizedCar är
  // "kategori innehåller familj ELLER användning innehåller familj ELLER passagerare >= 5".
  // Värdet (4, markupens förval) skickas fortfarande, så prompten och cachenyckeln ser
  // likadana ut. Kvar utan väg in är bara "icke-familjekategori OCH 5+ passagerare" — en
  // liten bil till sex personer, och det svaret vore ändå fel.
  var pass = document.getElementById('ca-passengers');
  var passRuta = pass && pass.closest ? pass.closest('.ca-field') : null;
  if (passRuta) passRuta.style.display = 'none';

  // ANVÄNDNING bort ur formuläret på samma sätt (2026-09-04). Fältet matar en enda mening i
  // prompten ("Användning: pendling"), och den vakt det en gång fanns för — usage som
  // innehåller "familj" i requiresFamilySizedCar — kan inte tändas av rullgardinens
  // alternativ (pendling/blandat/landsväg/stad). Kategorin Familjebil tänder samma vakt.
  // Markupens förval skickas fortfarande, så prompten och cachenyckeln ser likadana ut.
  var usage = document.getElementById('ca-usage');
  var usageRuta = usage && usage.closest ? usage.closest('.ca-field') : null;
  if (usageRuta) usageRuta.style.display = 'none';

  // DRIVMEDLET flyttar in hit (2026-09-04). Laddarfrågan avgör det i båda riktningarna —
  // Ja ger El, Nej ger bensin/elhybrid — och den som vill säga "diesel" hittar rutan här.
  // Raden #ca-fuel-sum nedanför visar alltid vad valet blev, så inget sker i det tysta.
  var ids = ['ca-fuel', 'ca-km', 'ca-cargo', 'ca-transmission', 'ca-maxage'];
  var rutor = [];
  ids.forEach(function (id) {
    var el = document.getElementById(id);
    var ruta = el && el.closest ? el.closest('.ca-field') : null;
    if (ruta && rutor.indexOf(ruta) === -1) rutor.push(ruta);
  });
  if (!rutor.length) return;

  var knapp = document.createElement('button');
  knapp.type = 'button';
  knapp.id = 'ca-fler-btn';
  knapp.setAttribute('aria-expanded', 'false');
  knapp.innerHTML = '<span>Fler val</span><span class="ca-fler-hint">drivmedel, körsträcka, bagage, växellåda, ålder</span><span class="ca-fler-pil">▾</span>';

  var box = document.createElement('div');
  box.id = 'ca-fler';
  box.className = 'ca-grid';
  box.hidden = true;

  // Knappen och lådan läggs EFTER den rutnätsrad där första flyttade fältet satt — inte
  // inuti den. Ett .ca-grid som barn till ett annat .ca-grid blir en cell som i sin tur är
  // ett rutnät, och då hamnar de utfällda fälten i EN kolumn medan cellen bredvid gapar tom.
  // Det syntes först när provet fällde ut lådan; hopfälld såg allt rätt ut.
  var ankare = rutor[0];
  var rad = ankare.closest('.ca-grid') || ankare.parentNode;
  rad.parentNode.insertBefore(knapp, rad.nextSibling);
  knapp.parentNode.insertBefore(box, knapp.nextSibling);
  rutor.forEach(function (r) { box.appendChild(r); });

  // Sammanfattningsraden står MELLAN de synliga fälten och knappen: drivmedlet är inte
  // längre en fråga på skärmen, och då måste svaret synas någonstans.
  var sum = document.createElement('div');
  sum.id = 'ca-fuel-sum';
  knapp.parentNode.insertBefore(sum, knapp);
  caDrivmedelsrad();

  // Rader som blev helt tomma när fälten flyttades ska inte lämna en lucka i formuläret.
  Array.prototype.forEach.call(document.querySelectorAll('#ca-wrap .ca-grid'), function (g) {
    if (g.id === 'ca-fler') return;
    var kvar = g.querySelectorAll('.ca-field');
    var synliga = Array.prototype.filter.call(kvar, function (f) { return f.style.display !== 'none'; });
    if (!synliga.length) g.style.display = 'none';
  });

  knapp.addEventListener('click', function () {
    var oppet = box.hidden;
    box.hidden = !oppet;
    knapp.setAttribute('aria-expanded', oppet ? 'true' : 'false');
    knapp.classList.toggle('ca-fler-oppen', oppet);
  });
}

function caAnpassaBegagnatFormular() {
  var maxAge = document.getElementById('ca-maxage');
  if (maxAge) maxAge.value = CA_MAXAGE_FORVAL;

  // Åldersrutan göms inte längre — den flyttar in under "Fler val" tillsammans med de andra
  // sällanfrågorna (se caFlerVal). Förvalet 5 år står kvar, men den som vill ha en äldre bil
  // kan fortfarande säga det: att gömma ett val är sämre än att fälla ihop det.

  // Notisen kan redan stå i markupen (snippeten omklistrad) ELLER byggas ur ny/begagnad-rutan.
  // BÅDA vägarna måste kompakteras, annars ser WordPress-sidan annorlunda ut än test.html —
  // och den skillnaden syns inte förrän någon klistrar om snippeten.
  var befintlig = document.getElementById('ca-usedcar-note');
  if (befintlig) { caKompaktNotis(befintlig); return; }

  var sel = document.getElementById('ca-newcar');
  if (!sel) return;
  sel.value = 'false';
  var falt = sel.closest ? sel.closest('.ca-field') : null;
  if (!falt) { sel.style.display = 'none'; return; }
  falt.id = 'ca-usedcar-note';
  caKompaktNotis(falt);
}

/**
 * Notisen "Bilarna vi visar" som en smal rad över hela bredden.
 *
 * <p>Etiketten är borta sedan 2026-09-04: notisen är ingen fråga, och när drivmedelsrutan
 * flyttade ned under "Fler val" blev notisen ensam kvar i sin rutnätsrad — en halvbred ruta
 * med en tom cell bredvid. Idempotent, så den tål att köras på båda markupvarianterna.
 */
function caKompaktNotis(falt) {
  falt.style.gridColumn = '1 / -1';
  falt.innerHTML =
    // Neutral vit genomskinlighet i stället för en blå ram: sidans tema är violett, och en
    // blåtonad ruta läste som ett främmande element mitt i formuläret. Utseendet flyttat till
    // klassen i caToppCss — inline vinner alltid över en stilregel, så notisen gick inte att
    // ge en vänsterkant så länge ramen stod här.
    '<div class="ca-blocket-note">' +
    'Begagnat ur <b>Blockets annonser</b>, <b>högst 5 år gamla</b> — priserna mäts mot riktiga annonser.' +
    '</div>';
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
      newcar:       caIsNewCar() ? 'true' : 'false',
      fuelType:     document.getElementById('ca-fuel').value,
      transmission: t ? t.value : 'spelar ingen roll',
      maxage:       maEl ? maEl.value : '',
      cargo:        (function(){ var c = document.getElementById('ca-cargo'); return c ? c.value : '0'; })()
    }));
  } catch(e) { caWarn('att spara inställningar', e); }
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
//
// EGEN STEGE FÖR ELBIL (2026-08-20). Ett batteri höjer golvet, så samma kaross rymmer olika
// mycket beroende på drivlina, och en stege med bensinbilar som ankare ljuger för den som sökt
// elbil. Laddhybrid och hybrid ligger kvar på bensinstegen: de bygger på samma karosser.
//
// ALLA ANKARE ÄR UPPMÄTTA UR cargo_spec, inte valda ur minnet — och det var nödvändigt, för
// den gamla listan var fel i tre av fem steg: Yaris stod som "minst 300" på 286 l, Golf som
// "minst 400" på 381, Niro som "minst 500" på 475, och sämst av allt bar 700-steget V90 (560)
// och EV9 (333), alltså två bilar som inte ens är i närheten. Ändras ett ankare: läs av
// GET /api/admin/cargo-specs igen, hitta inte på en siffra.
//
// Talen i kommentarerna nedan är avläsningen 2026-08-20 och står där för att nästa läsare ska
// kunna se att ankaret ligger ÖVER sin nivå utan att slå upp något.
var CA_CARGO_LEVELS = {
  // Elbilar: 600-steget är tunt med flit — mellan Model 3 (594) och Model Y (854) finns nästan
  // inga folkliga elbilar, och det är en sann sak om marknaden, inte en lucka i datan.
  el: [
    { v: 0,   txt: 'Spelar ingen roll' },
    { v: 300, txt: 'Minst 300 l — halvkombi (EX30, Zoe)' },                  // 318, 338
    { v: 400, txt: 'Minst 400 l — kompakt-SUV (Leaf, Kona Electric)' },      // 435, 466
    { v: 500, txt: 'Minst 500 l — mellanklass (IONIQ 5, ID.4)' },            // 527, 543
    { v: 600, txt: 'Minst 600 l — stor SUV (XPENG G9, Smart #5)' },          // 660, 630
    { v: 700, txt: 'Minst 700 l — stor SUV eller sk\xe5pbil (Model Y, \xeb-Berlingo)' } // 854, 775
  ],
  ovrigt: [
    { v: 0,   txt: 'Spelar ingen roll' },
    { v: 300, txt: 'Minst 300 l — halvkombi (Corolla, Golf)' },              // 361, 381
    { v: 400, txt: 'Minst 400 l — kompakt-SUV (Kamiq, Astra)' },             // 400, 422
    { v: 500, txt: 'Minst 500 l — mellankombi (XC60, V60)' },                // 505, 529
    { v: 600, txt: 'Minst 600 l — stor kombi eller stor SUV (Octavia, Tiguan)' }, // 600, 615
    { v: 700, txt: 'Minst 700 l — stor SUV eller sk\xe5pbil (Kodiaq, Sorento)' }  // 765, 898
  ]
};

/**
 * Stegen som gäller för nuvarande val — elbilsstegen bara när sökningen ÄR en elbilssökning.
 *
 * Samma villkor som caBudgetLevelsFor använder, och av samma skäl: drivmedelsrutan göms när
 * kategorin är "elbil" eller "laddhybrid" och nollställs då till "spelar ingen roll", så
 * kategorin måste läsas med. Laddhybrid räknas som bensin här — karossen är densamma.
 */
function caCargoLevels() {
  var cat = document.getElementById('ca-category');
  var fuel = document.getElementById('ca-fuel');
  var elbilssok = (cat && cat.value === 'elbil') || (fuel && fuel.value === 'el');
  return CA_CARGO_LEVELS[elbilssok ? 'el' : 'ovrigt'];
}

/**
 * Ritar om alternativen utan att tappa användarens val.
 *
 * Nivåernas VÄRDEN är identiska i båda stegarna — bara texten skiljer — så det valda talet
 * överlever alltid bytet. Vore de olika hade fältet tyst nollställts när drivmedlet ändrades,
 * alltså samma sorts osynliga återställning som bagagefältet redan drabbats av en gång.
 */
function caRenderCargoLevels() {
  var sel = document.getElementById('ca-cargo');
  if (!sel) return;
  var valt = sel.value;
  sel.innerHTML = caCargoLevels().map(function(n) {
    return '<option value="' + n.v + '">' + n.txt + '</option>';
  }).join('');
  sel.value = valt;
}
function caEnsureCargoField() {
  if (document.getElementById('ca-cargo')) return;
  var pass = document.getElementById('ca-passengers');
  if (!pass) return;
  var rad = pass.closest('.ca-grid');
  if (!rad) return;
  var opts = caCargoLevels().map(function(n) {
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
/**
 * Trappan: var du står nu och vad en prenumeration ger.
 *
 * TVÅ steg sedan 2026-08-22, inte tre. Mellansteget "gratiskonto" är borta: alla utan
 * prenumeration har samma pott, och ett konto skapas för att prenumerera. Trattmätningen
 * bakom beslutet står i CarControllers kommentar — 13 sökningar på sju dygn från EN nyckel
 * och noll organiska konton, alltså en spärr som aldrig hade någon att omvända.
 *
 * Byggs från JS och inte i HTML-snippeten av två skäl: WP-sidan är en manuell kopia, så ett
 * nytt stycke i snippeten syns inte förrän den klistras om, och stegen bär INLINE-stilar
 * eftersom ett injicerat <style> blockeras av sidans CSP — samma begränsning som budgetrutan
 * och burnout-laddaren fick kringgå.
 *
 * Siffran kommer från CA_SEARCHES_PER_HOUR, som speglar CarControllers konstant. Hårdkodas
 * den här glider de isär vid nästa gränsändring.
 */
function caTrappanHtml(harRad) {
  var rad = function(etikett, text, stark) {
    return '<div style="display:flex;gap:8px;align-items:baseline;margin-top:6px">'
         + '<span style="flex:0 0 auto;font-weight:700;color:' + (stark ? '#fcd34d' : 'rgba(255,255,255,.55)') + '">'
         + etikett + '</span>'
         + '<span style="color:rgba(255,255,255,.8)">' + text + '</span></div>';
  };
  return (harRad ? '<div style="margin-top:10px;padding-top:10px;border-top:1px solid rgba(245,158,11,.25)"></div>' : '')
       + rad('Utan konto', CA_SEARCHES_PER_HOUR + ' sökningar per timme — kostar ingenting', false)
       + rad('49 kr/mån', '<b>obegränsat</b>, plus AI EV Laddassistent', true);
}

/**
 * Fyller rutan som visas när kvoten tagit slut.
 *
 * Rubriken kommer från SERVERNS 429-svar när det finns — den vet de riktiga gränserna och
 * pekar redan på nästa steg. Den statiska texten i snippeten sa "10 gratis sökningar den här
 * timmen", vilket var sant före 2026-08-16 och fel efteråt; att låta servern tala är enda
 * sättet att slippa den sortens glidning igen.
 */
function caFyllKvotrutan(serverMeddelande) {
  var box = document.getElementById('ca-rate-limit-box');
  if (!box) return;
  var rubrik = serverMeddelande || ('Du har använt dina ' + CA_SEARCHES_PER_HOUR + ' sökningar denna timme.');
  box.innerHTML = '<div style="font-weight:600">⏱ ' + rubrik + '</div>'
    + caTrappanHtml(true)
    // rel="opener" och inte noopener: target="_blank" implicerar noopener i alla moderna
    // webbläsare sedan 2021, och utan en opener-referens kan subscribe.html inte skicka
    // tillbaka CA_LOGIN. Då skapas kontot utan att appen någonsin får veta det. Se
    // meddelandelyssnaren nedan — den kontrollerar avsändarens origin.
    + '<div style="margin-top:10px"><a id="ca-rate-limit-link" href="' + CA_API_BASE
    + '/subscribe.html" target="_blank" rel="opener">Prenumerera – 49 kr/mån →</a></div>';
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
    if (d.newcar)   { var ncEl = document.getElementById('ca-newcar'); if (ncEl) ncEl.value = d.newcar; }
    if (d.fuelType)   document.getElementById('ca-fuel').value        = d.fuelType;
    if (d.transmission) { var t = document.getElementById('ca-transmission'); if (t) t.value = d.transmission; }
    if (d.maxage) { var ma = document.getElementById('ca-maxage'); if (ma) ma.value = d.maxage; }
    caUtanForval(caUpdateFuelVisibility);
    caCheckMismatch();
  } catch(e) { caWarn('sparade inställningar', e); }
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
    if (p.get('newcar'))   { var ncEl3 = document.getElementById('ca-newcar'); if (ncEl3) ncEl3.value = p.get('newcar'); }
    if (p.get('fuelType'))    document.getElementById('ca-fuel').value        = p.get('fuelType');
    if (p.get('transmission')) { var t = document.getElementById('ca-transmission'); if (t) t.value = p.get('transmission'); }
    if (p.get('maxage')) { var ma = document.getElementById('ca-maxage'); if (ma) ma.value = p.get('maxage'); }
    if (p.get('cargo')) { var cg = document.getElementById('ca-cargo'); if (cg) cg.value = p.get('cargo'); }
    if (p.has('category') || p.has('budget')) { caUtanForval(caUpdateFuelVisibility); caCheckMismatch(); }
  } catch(e) { caWarn('länkens parametrar', e); }
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
    newcar:       caIsNewCar() ? 'true' : 'false',
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
  // Under avsvalningen BÄR knappen sin nedräkning. Utan undantaget skrev varje ändring i
  // formuläret över texten med "Uppdatera resultat →" tills nästa sekundtick skrev tillbaka
  // siffran — en knapp som blinkar mellan "klicka på mig" och "vänta", medan den är låst.
  if (!caCooldownTimer) btn.textContent = anyChanged ? 'Uppdatera resultat →' : 'S\xf6k igen →';
}

function caBindChangeListeners() {
  var ids = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel','ca-transmission','ca-maxage','ca-cargo'];
  ids.forEach(function(id) {
    var el = document.getElementById(id);
    if (!el) return;
    // Har användaren rört åldern själv slutar den följa kategorin — samma regel som
    // växellådan, och samma flagga.
    if (id === 'ca-maxage') el.addEventListener('change', function () { el.dataset.rord = '1'; });
    // Ett draget reglage ar ett eget val: kategoriforvalet ska inte kasta om det efterat.
    if (id === 'ca-budget-slider') el.addEventListener('input', function () { el.dataset.rord = '1'; });
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
  // Forst forvalet, sedan de som laser vad forvalet satte.
  if (cat) cat.addEventListener('change', caKategoriForval);
  if (cat) cat.addEventListener('change', caUpdateFuelVisibility);
  if (cat) cat.addEventListener('change', caRenderEvBudgetHint);
  // Bagagestegen byter ankarbilar med drivmedlet (se caCargoLevels). Kategorin måste vara med
  // eftersom "elbil" göms drivmedelsrutan och nollställer den — utan den här raden hade en
  // elbilssökning fått bensinbilar som exempel.
  if (cat) cat.addEventListener('change', caRenderCargoLevels);
  if (chg) chg.addEventListener('change', caUpdateFuelVisibility);
  // Drivmedlet styr numera VILKEN nivåstege rutan läser (se caBudgetLevelsFor) — utan den här
  // raden byttes texten först när budgeten eller kategorin rördes, alltså oftast aldrig.
  var fuel = document.getElementById('ca-fuel');
  if (fuel) fuel.addEventListener('change', caRenderEvBudgetHint);
  if (fuel) fuel.addEventListener('change', caRenderCargoLevels);
  // Utan den här raden reagerade växellåde- och laddarrutan bara när KATEGORIN byttes —
  // drivmedelsrutan kunde stå på "El" med växellådefrågan kvar synlig hur länge som helst.
  if (fuel) fuel.addEventListener('change', caUpdateFuelVisibility);
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
      newcar:          caIsNewCar() ? 'true' : 'false',
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
  } catch(e) { caWarn('att spara historik', e); }
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

/**
 * Fäller ihop en låda bakom en klickbar rad — samma rad som "Fler val".
 *
 * <p>Under sökknappen låg två fullstora block som ingen bad om: den fria jämförelsen och
 * tidigare sökningar. De är bra att ha och dåliga att alltid se, vilket är precis vad en
 * hopfällbar rad är till för.
 *
 * <p><b>Öppet läge minns per flik</b> (sessionStorage, inte localStorage): den som fällt ut
 * jämförelsen för att använda den vill inte fälla ut den igen efter varje sökning, men nästa
 * besök ska börja lugnt igen.
 *
 * <p>Lådan göms med {@code hidden}-attributet OCH en explicit display-regel. Webbläsarens egen
 * {@code [hidden]}-regel förlorar mot vilken display-regel som helst med högre specificitet —
 * exakt samma fälla som gjorde att #ca-fler aldrig var ihopfälld i drift.
 */
/**
 * Rullar ned till appen när splashen är klar.
 *
 * <p><b>Varför.</b> På elitrobban.se ligger formuläret långt ned under sidans egen text och
 * bild — uppmätt låg Demo-raden 543 px under vikningen vid {@code scrollY 0}. Den som klickar
 * sig hit kommer för bilrådgivaren, och splashen har dessutom just lovat att den startar.
 * Utan den här raden möttes man av rubriken "Bilrådgivning" och fick leta själv.
 *
 * <p><b>Tre saker den aldrig gör.</b>
 * <ul>
 *   <li>Rör inte en läsare som redan tagit över. Hjul, touch, tangent eller en egen rullning
 *       under väntan avbryter — den som börjat läsa sidans text ska inte ryckas därifrån.</li>
 *   <li>Rullar inte om appen redan syns. Landar man med appen i bild finns inget att göra.</li>
 *   <li>Rullar inte förbi splashen. Den ligger som ett heltäckande lager och äter ändå rullningen;
 *       vi väntar tills den plockats bort ur DOM:en.</li>
 * </ul>
 *
 * <p>Splashen visas bara en gång per webbläsare ({@code SEEN_KEY} i splashskriptet), så för en
 * återvändare finns ingen splash att vänta på — därför rullar vi ändå efter en kort frist.
 * Taket på 25 s finns för det fall splashen fastnar: hellre en sen rullning än ingen alls.
 *
 * <p>Följer prefers-reduced-motion: samma slutposition, utan glidningen.
 */
function caRullaTillAppen() {
  var mal = document.getElementById('ca-wrap');
  if (!mal || window.location.hash) return;   // en ankarlänk vet bättre än vi
  var avbrutet = false;
  var startY = window.scrollY || 0;
  function taOver() { avbrutet = true; }
  ['wheel', 'touchstart', 'keydown', 'pointerdown'].forEach(function (t) {
    window.addEventListener(t, taOver, { once: true, passive: true });
  });

  var start = Date.now();
  (function vanta() {
    if (avbrutet || Math.abs((window.scrollY || 0) - startY) > 40) return;
    var splash = document.querySelector('.ca-splash');
    var vantat = Date.now() - start;
    // Splashen kvar: vänta ut den. Ingen splash inom 1,2 s: det finns ingen att vänta på.
    if ((splash || vantat < 1200) && vantat < 25000) { setTimeout(vanta, 150); return; }
    var r = mal.getBoundingClientRect();
    var h = window.innerHeight || document.documentElement.clientHeight;
    if (r.top >= 0 && r.top < h * 0.5) return;  // appen syns redan
    var stilla = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    try { mal.scrollIntoView({ behavior: stilla ? 'auto' : 'smooth', block: 'start' }); } catch (_) {}
  })();
}

/**
 * Slår ihop resultatrubriken och dess knappar till EN rad.
 *
 * <p>"Dina rekommendationer" låg på en rad och "Kopiera lista / Dela länk / Spara sökning" på
 * nästa, direkt ovanför bilkorten. Två rader för en rubrik och tre knappar, precis där man vill
 * komma åt svaret.
 *
 * <p><b>Ihopslagna, inte gömda.</b> Knapparna är sådant man gör i samma andetag som man läser
 * svaret — bakom ett extra klick hade de blivit osynliga i praktiken. Rubriken tar vänsterkanten,
 * knapparna högerkanten, och på en smal skärm bryter de till egen rad av sig själva:
 * {@code .ca-result-header} har redan {@code flex-wrap:wrap}.
 *
 * <p>Flyttas i DOM:en i stället för att styras med CSS — de är syskon, och två syskon går inte
 * att lägga på samma flexrad utan att den ena blir barn till den andra. Idempotent, och
 * knapparnas egen visning ({@code style.display}) rörs inte: den sätts och nollställs på
 * knapparna själva, inte på behållaren.
 */
/**
 * Gömmer undan "Dela sökning" och "Nollställ" ur huvudknappraden.
 *
 * <p>De satt som två fyrkantiga knappar bredvid "Hitta min bil" och tog plats från sidans enda
 * viktiga knapp. Nu ligger de som små textlänkar på egen rad under den — undanstoppade men
 * fortfarande nåbara. Delning finns dessutom kvar i resultatpanelen.
 *
 * <p><b>Varför i kod och inte bara i markupen.</b> Blocket är omklistrat i WordPress-sidan, och
 * en ändring i wordpress-snippet.html syns inte förrän någon klistrar in det på nytt. Samma
 * grepp som evFixPromoText i ev-charging.js: skriptet laddas på sidan och uppdateras vid deploy,
 * så det kan flytta knapparna direkt. Har blocket redan den nya raden gör funktionen ingenting.
 *
 * <p>Stilen skrivs med TVÅ id-selektorer. Blockets egen CSS ligger i en {@code <style>} i
 * body:n, alltså senare i dokumentordningen än ett injicerat huvud-style — vid samma
 * specificitet hade blockets gamla knappstil vunnit.
 */
function caGomUndanSmaval() {
  var dela = document.getElementById('ca-share-search-btn');
  var noll = document.getElementById('ca-reset-btn');
  if (!dela && !noll) return;

  if (!document.getElementById('ca-smaval-style')) {
    var st = document.createElement('style');
    st.id = 'ca-smaval-style';
    st.textContent =
      '#ca-smaval{display:flex;align-items:center;justify-content:flex-end;gap:8px;margin-top:10px;}' +
      '#ca-smaval .ca-smaval-sep{color:rgba(255,255,255,0.18);font-size:0.8rem;}' +
      '#ca-smaval #ca-share-search-btn,#ca-smaval #ca-reset-btn{' +
        'background:none;border:none;padding:7px 6px;font-family:inherit;font-size:0.82rem;' +
        'color:rgba(255,255,255,0.4);cursor:pointer;white-space:nowrap;text-decoration:underline;' +
        'text-decoration-color:rgba(255,255,255,0.16);text-underline-offset:3px;' +
        'transition:color 0.2s,text-decoration-color 0.2s;border-radius:0;box-shadow:none;}' +
      '#ca-smaval #ca-share-search-btn:hover,#ca-smaval #ca-reset-btn:hover{' +
        'color:rgba(255,255,255,0.85);text-decoration-color:rgba(255,255,255,0.45);}' +
      '#ca-smaval #ca-share-search-btn.copied{color:#34d399;text-decoration-color:rgba(52,211,153,0.45);}';
    document.head.appendChild(st);
  }

  var rad = document.getElementById('ca-smaval');
  if (!rad) {
    var area = document.getElementById('ca-btn-area');
    if (!area) return;
    rad = document.createElement('div');
    rad.id = 'ca-smaval';
    area.parentNode.insertBefore(rad, area.nextSibling);
  }
  if (dela && dela.parentNode !== rad) rad.appendChild(dela);
  if (dela && noll && !rad.querySelector('.ca-smaval-sep')) {
    var sep = document.createElement('span');
    sep.className = 'ca-smaval-sep';
    sep.setAttribute('aria-hidden', 'true');
    sep.textContent = '·';
    rad.appendChild(sep);
  }
  if (noll && noll.parentNode !== rad) rad.appendChild(noll);
}

function caResultatradIhop() {
  var head = document.querySelector('#ca-results .ca-result-header');
  var akt  = document.querySelector('#ca-results .ca-result-actions');
  if (!head || !akt || akt.parentNode === head) return;
  akt.style.marginBottom = '0';
  akt.style.marginLeft = 'auto';
  head.appendChild(akt);
}

function caHopfallbar(box, titel, hint, nyckel) {
  if (!box || box.dataset.hopfalld) return null;
  box.dataset.hopfalld = '1';
  var knapp = document.createElement('button');
  knapp.type = 'button';
  knapp.className = 'ca-hopfall-btn';
  knapp.innerHTML = '<span>' + caEsc(titel) + '</span>'
    + (hint ? '<span class="ca-fler-hint">' + caEsc(hint) + '</span>' : '')
    + '<span class="ca-fler-pil">▾</span>';
  box.parentNode.insertBefore(knapp, box);

  var oppet = false;
  try { oppet = sessionStorage.getItem('ca_oppen_' + nyckel) === '1'; } catch (_) {}
  function stall(nyOppet) {
    oppet = nyOppet;
    box.hidden = !oppet;
    knapp.classList.toggle('ca-fler-oppen', oppet);
    knapp.setAttribute('aria-expanded', oppet ? 'true' : 'false');
    try { sessionStorage.setItem('ca_oppen_' + nyckel, oppet ? '1' : '0'); } catch (_) {}
  }
  stall(oppet);
  knapp.addEventListener('click', function () { stall(box.hidden); });
  return knapp;
}

function caRenderHistory() {
  var area = document.getElementById('ca-history-area');
  if (!area) return;
  var history = caGetHistory();
  // Tom historik ska inte lämna en knapp som fäller ut ingenting.
  if (history.length === 0) { area.innerHTML = ''; return; }
  var chips = history.map(function(entry, i) {
    return '<button class="ca-history-chip" onclick="window._ca(\'history\',' + i + ')">' +
      '<span class="ca-history-chip-text">' + caEsc(caHistoryLabel(entry)) + '</span>' +
      '<span class="ca-history-chip-time">\xb7 ' + caTimeAgo(entry.timestamp) + '</span>' +
      '<span class="ca-history-chip-del" onclick="event.stopPropagation();window._ca(\'delHistory\',' + i + ')" title="Ta bort">\xd7</span>' +
      '</button>';
  }).join('');
  // Rubriken ar sjalv knappen som faller ihop listan. Antalet star i hinten, sa man ser om
  // det ar vart att oppna utan att oppna.
  area.innerHTML = '<div class="ca-history-chips">' + chips + '</div>';
  var lada = area.querySelector('.ca-history-chips');
  delete lada.dataset.hopfalld;
  caHopfallbar(lada, 'Tidigare s\xf6kningar',
    history.length + (history.length === 1 ? ' sparad' : ' sparade'), 'historik');
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
  if (entry.newcar)   { var ncEl4 = document.getElementById('ca-newcar'); if (ncEl4) ncEl4.value = entry.newcar; }
  caSetBudgetMode(entry.budgetMode || 'köp', entry.budget ? parseInt(entry.budget) : undefined);
  if (entry.fuelType)    document.getElementById('ca-fuel').value        = entry.fuelType;
  if (entry.transmission) { var tEl = document.getElementById('ca-transmission'); if (tEl) tEl.value = entry.transmission; }
  caUtanForval(caUpdateFuelVisibility);
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
  document.getElementById('ca-km').value         = CA_KM_FORVAL;
  document.getElementById('ca-usage').value      = 'pendling';
  document.getElementById('ca-passengers').value = 4;
  var ncEl5 = document.getElementById('ca-newcar'); if (ncEl5) ncEl5.value = 'false';
  document.getElementById('ca-fuel').value       = 'spelar ingen roll';
  var tEl = document.getElementById('ca-transmission'); if (tEl) tEl.value = 'spelar ingen roll';
  var maEl = document.getElementById('ca-maxage'); if (maEl) maEl.value = CA_MAXAGE_FORVAL;
  caSlappEgnaVal();
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

/**
 * Förbränningsspecen för en bil, eller null när bilen är en REN elbil.
 *
 * <p>Prompten säger redan "Elbil/laddhybrid: fuelSpec=null", men en instruktion är ingen vakt:
 * en Kia EV6 kom tillbaka med "DSG-automatik" och fick den utskriven som spec. En elbil har
 * varken växellåda, motorvolym eller l/100 km.
 *
 * <p>Vakten sitter i VISNINGEN och inte bara i backenden med flit: svar som redan ligger
 * cachade i databasen rättas då utan omkörning.
 *
 * <p>Laddhybrider behåller sin fuelSpec — de HAR en förbränningsmotor med växellåda.
 */
function caIceSpec(r) {
  if (!r || !r.fuelSpec) return null;
  if (r.evSpec && r.evSpec.carType !== 'PHEV') return null;
  return r.fuelSpec;
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

// ── Märkesemblem på bilkorten ────────────────────────────────────────────────
// Samma filer som Elbilsassistenten använder, och de serveras redan härifrån
// (/ev-emblem/*.svg) — appen behöver alltså varken ny fil eller nytt beroende.
//
// Alla är public domain på Wikimedia Commons. Commons märker dem samtidigt "trademarked":
// det är varumärket och inte licensen, och att visa märket intill just den bilen är den
// beskrivande användning varje bilsajt gör.
//
// Listan är de slugs som FAKTISKT finns i katalogen. Alternativet — att gissa filnamnet ur
// märket och låta 404:an bli fallbacken — hade gett en misslyckad förfrågan per okänt märke
// vid varje sökning, och CarAdvices bildatabas har långt fler märken än elbilstabellen.
var CA_EMBLEM = ['alpine', 'audi', 'bmw', 'byd', 'citroen', 'cupra', 'dacia', 'fiat', 'ford',
  'geely', 'gwm', 'honda', 'jac', 'jeep', 'kgm', 'kia', 'lexus', 'mazda', 'mercedes', 'mg',
  'mini', 'mitsubishi', 'nio', 'nissan', 'opel', 'polestar', 'renault', 'rollsroyce', 'skoda',
  'smart', 'subaru', 'suzuki', 'tesla', 'toyota', 'vinfast', 'volkswagen', 'volvo'];

/**
 * Emblemet för en biltitel, eller tom sträng när märket saknar fil.
 *
 * <p>Titeln börjar med märket ("Volkswagen ID.4 (2022)"), men inte alltid med ETT ord:
 * "Alfa Romeo" och "Land Rover" är två. Därför prövas tvåordsprefixet först.
 *
 * <p>Diakriterna kokas bort innan uppslaget — "Škoda" och "Citroën" heter skoda.svg och
 * citroen.svg. Samma sorts avkodning som insiktsmatchningen i backenden behövde.
 */
function caEmblemHtml(title) {
  var t = String(title || '').trim();
  if (!t) return '';
  var ord = t.split(/\s+/);
  var kandidater = [ord.slice(0, 2).join(' '), ord[0]];
  for (var i = 0; i < kandidater.length; i++) {
    var slug = String(kandidater[i] || '').normalize('NFD').replace(/[̀-ͯ]/g, '')
      .toLowerCase().replace(/[^a-z0-9]/g, '');
    if (slug && CA_EMBLEM.indexOf(slug) !== -1) {
      return '<span class="ca-emblem"><img src="' + CA_API_BASE + '/ev-emblem/' + slug + '.svg"'
        + ' alt="" loading="lazy" onerror="this.parentNode.style.display=\'none\'"></span>';
    }
  }
  return '';
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
          '<div class="ca-title-rad">' + caEmblemHtml(r.title) + '<h3>' + caEsc(r.title) + '</h3></div>' +
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
          (caIceSpec(r) ? caFuelChips(caIceSpec(r), caParsePrice(r.price)) : '') +
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

/**
 * Emblemet för jämförelsetabellen, med monogram som reservväg.
 *
 * <p>caEmblemHtml ger tom sträng för de märken som saknar SVG-fil, och en tom lucka mitt i
 * en kolumnrubrik ser ut som ett fel. Två bokstäver i kolumnens färg gör samma jobb: de
 * ankrar kolumnen visuellt så man hittar tillbaka till rätt bil när tabellen sidscrollar.
 */
function caCmpEmblem(title, accent) {
  var e = caEmblemHtml(title);
  if (e) return e;
  var ord = String(title || '').trim().split(/\s+/);
  var bok = (ord[0] || '?').replace(/[^\wåäöÅÄÖ]/g, '').slice(0, 2).toUpperCase();
  return '<span class="ca-cmp-mono" style="--ca-acc:' + accent + '">' + caEsc(bok || '?') + '</span>';
}

/**
 * Index för bilen som vinner raden, eller -1.
 *
 * <p>Två spärrar med flit: färre än två mätbara värden är ingen jämförelse, och delad
 * förstaplats markeras inte alls. En stjärna på två av tre celler säger ingenting — och en
 * stjärna på båda bilarna i den fria jämförelsen (som alltid är två) hade varit rent brus.
 */
function caCmpVinnare(recs, tal, rikt) {
  var v = recs.map(function(r) {
    var n = 0;
    try { n = tal(r); } catch (e) { n = 0; }
    return (typeof n === 'number' && isFinite(n) && n > 0) ? n : null;
  });
  var giltiga = v.filter(function(n) { return n !== null; });
  if (giltiga.length < 2) return -1;
  var bast = rikt === 'lag' ? Math.min.apply(null, giltiga) : Math.max.apply(null, giltiga);
  var antal = giltiga.filter(function(n) { return n === bast; }).length;
  if (antal !== 1) return -1;
  for (var i = 0; i < v.length; i++) if (v[i] === bast) return i;
  return -1;
}

function caRenderCompare(recs, targetEl) {
  var cmp = targetEl || document.getElementById('ca-compare');
  if (!cmp || !recs || recs.length < 2) return;
  var hasEv   = recs.some(function(r){ return r.evSpec; });
  var hasFuel = recs.some(function(r){ return caIceSpec(r); });
  // Kolumnfärgerna är desamma som bilkortens 1/2/3 — samma bil ska bära samma färg hela vägen
  var accent = ['#a78bfa', '#38bdf8', '#34d399'];
  var TOM = '<span class="ca-cmp-tom">&#x2013;</span>';

  function chip(text, color) { return '<span class="ca-cmp-chip" style="background:' + color + '">' + text + '</span>'; }
  function ev(r, fn) { return r.evSpec ? fn(r.evSpec) : TOM; }
  function hk(r) { var f = caIceSpec(r); return r.horsepower || (f && f.horsepower) || 0; }
  function stjarnor(r) { return r.safetyRating ? (r.safetyRating.match(/★/g) || []).length : 0; }

  // Varje rad kan bära en dom: tal() ger det jämförbara värdet och rikt säger åt vilket håll
  // som är bättre. Rader utan tal() (fördelar, expertomdöme, motoralternativ) döms inte —
  // där finns ingen ordning som går att mäta.
  var rows = [
    // Prisraden (AI:ns kalkyl) borttagen — Blocket nu är sanningen; AI-priset visas bara som fallback
    { label: '&#x1F535; Blocket nu', rikt: 'lag',
      tal: function(r){ return caParsePrice(r.blocketPrice || r.price); },
      fn: function(r){
        if (!r.blocketPrice) return '<span class="ca-cmp-pris">' + caEsc(r.price) + '</span>';
        return '<a class="ca-cmp-lank" href="' + caBlocketUrl(r.title) + '" target="_blank" rel="noopener">'
          + caEsc(r.blocketPrice) + '&#xA0;&#x2192;</a>';
      }},
    { label: '&#x2714; F\xf6rdelar', fn: function(r){
      return '<ul class="ca-cmp-lista">' + (r.pros || []).map(function(p){
        return '<li>' + caEsc(p) + '</li>';
      }).join('') + '</ul>';
    }},
    { label: '&#x26A0; Nackdel', fn: function(r){ return '<span class="ca-cmp-minus">' + caEsc(r.con) + '</span>'; } },
    { label: '&#x1F3AF; Expertrecension', fn: function(r){
      if (!r.expertOpinion) return TOM;
      return '<span class="ca-cmp-cit">' + caEsc(r.expertOpinion) + '</span>';
    }},
    { label: '&#x1F6E1;&#xFE0F; Euro NCAP', rikt: 'hog', tal: stjarnor, fn: function(r){
      if (!r.safetyRating) return TOM;
      var parts = r.safetyRating.split(' · ');
      var stars = parts[0] || '';
      var details = parts.slice(1).join(' · ');
      return '<span class="ca-cmp-stjarnor">' + caEsc(stars) + '</span>' +
        (details ? '<span class="ca-cmp-sub">' + caEsc(details) + '</span>' : '');
    }},
    { label: '&#x1F9F3; Bagageutrymme', rikt: 'hog',
      tal: function(r){ return r.cargoSpec ? r.cargoSpec.cargoLiters : 0; },
      fn: function(r){
        if (!r.cargoSpec || r.cargoSpec.cargoLiters <= 0) return TOM;
        var txt = chip(r.cargoSpec.cargoLiters + ' L', 'rgba(251,191,36,.16)');
        if (r.cargoSpec.cargoMaxLiters > 0) txt += ' <span class="ca-cmp-sub" style="display:inline">/ ' + r.cargoSpec.cargoMaxLiters + ' L</span>';
        return txt;
      }},
    { label: '&#x1F527; Motor &amp; batterialternativ', fn: function(r){
      if (!r.engineOptions) return TOM;
      return r.engineOptions.split(',').map(function(opt) {
        return '<span class="ca-cmp-opt">' + caEsc(opt.trim()) + '</span>';
      }).join('');
    }}
  ];
  if (hasFuel) {
    rows.push({ label: '&#x26FD; F\xf6rbrukning', rikt: 'lag',
      tal: function(r){ var f = caIceSpec(r); return f ? f.consumptionLiterPerMil : 0; },
      fn: function(r){
        var f = caIceSpec(r);
        if (!f || f.consumptionLiterPerMil <= 0) return TOM;
        return chip((f.consumptionLiterPerMil / 10).toFixed(2) + ' l/mil', 'rgba(251,146,60,.18)');
      }});
    rows.push({ label: '&#x2699;&#xFE0F; V\xe4xell\xe5da', fn: function(r){
      var f = caIceSpec(r);
      if (!f || !f.gearbox) return TOM;
      return '<span class="ca-cmp-txt">' + caEsc(f.gearbox) + '</span>';
    }});
    rows.push({ label: '&#x1F527; Motorvolym', rikt: 'hog',
      tal: function(r){ var f = caIceSpec(r); return f ? f.engineVolumeLiters : 0; },
      fn: function(r){
        var f = caIceSpec(r);
        if (!f || f.engineVolumeLiters <= 0) return TOM;
        return chip(f.engineVolumeLiters.toFixed(1) + ' L', 'rgba(56,189,248,.14)');
      }});
  }
  if (hasEv) {
    rows.push({ label: '&#x1F4CF; WLTP', rikt: 'hog',
      tal: function(r){ return r.evSpec ? r.evSpec.wltpKm : 0; },
      fn: function(r){ return ev(r, function(e){ return e.wltpKm > 0 ? chip(e.wltpKm + ' km', 'rgba(56,189,248,.18)') : TOM; }); }});
    rows.push({ label: '&#x2600;&#xFE0F; Sommar', rikt: 'hog',
      tal: function(r){ return r.evSpec ? r.evSpec.summerKm : 0; },
      fn: function(r){ return ev(r, function(e){ return e.summerKm > 0 ? chip('~' + e.summerKm + ' km', 'rgba(59,130,246,.2)') : TOM; }); }});
    rows.push({ label: '&#x2744;&#xFE0F; Vinter', rikt: 'hog',
      tal: function(r){ return r.evSpec ? r.evSpec.winterKm : 0; },
      fn: function(r){ return ev(r, function(e){ return e.winterKm > 0 ? chip('~' + e.winterKm + ' km', 'rgba(148,163,184,.18)') : TOM; }); }});
    rows.push({ label: '&#x1F50B; Laddning',
      fn: function(r){ return ev(r, function(e){ return e.daysLabel ? '<span class="ca-cmp-txt" style="color:#fcd34d;font-weight:600">' + caEsc(e.daysLabel) + '</span>' : TOM; }); }});
    rows.push({ label: '<span class="ca-cmp-hjalp" title="Toppeffekt vid publik snabbladdare. Avg\xf6r hur korta pauserna blir p\xe5 l\xe5ngresa - h\xf6gre \xe4r b\xe4ttre, f\xf6rutsatt att stolpen klarar lika mycket.">&#x26A1; DC max</span>',
      rikt: 'hog', tal: function(r){ return r.evSpec ? r.evSpec.maxDcKw : 0; },
      fn: function(r){ return ev(r, function(e){ return e.maxDcKw > 0 ? chip(e.maxDcKw + ' kW', 'rgba(34,197,94,.16)') : '<span class="ca-cmp-tom">ingen DC</span>'; }); }});
    rows.push({ label: '<span class="ca-cmp-hjalp" title="Toppeffekt fr\xe5n laddbox, satt av bilens ombordladdare. S\xe4llan avg\xf6rande: 11 kW fyller batteriet \xf6ver natten \xe4nd\xe5, och de flesta hemmainstallationer ger inte mer.">&#x1F50C; AC max</span>',
      rikt: 'hog', tal: function(r){ return r.evSpec ? r.evSpec.maxAcKw : 0; },
      fn: function(r){ return ev(r, function(e){ return e.maxAcKw > 0 ? chip(e.maxAcKw + ' kW', 'rgba(139,92,246,.18)') : TOM; }); }});
    rows.push({ label: '&#x1F50B; Batteri', rikt: 'hog',
      tal: function(r){ return r.evSpec ? r.evSpec.batteryKwh : 0; },
      fn: function(r){ return ev(r, function(e){ return e.batteryKwh > 0 ? chip(e.batteryKwh + ' kWh' + (e.chemistry ? ' &middot; ' + e.chemistry : ''), 'rgba(56,189,248,.14)') : TOM; }); }});
  }
  // Hästkrafter och prisvärdhet gäller ALLA drivlinor och ligger därför utanför båda
  // grindarna. Tidigare låg hästkrafterna i bensin- OCH elgrenen, vilket gav TVÅ rader med
  // samma etikett så fort en blandad lista jämfördes, och prisvärdheten låg i elgrenen och
  // föll bort helt för en ren bensinjämförelse — trots att caValueLabelCombustion räknar den.
  rows.push({ label: '&#x1F4AA; H\xe4stkrafter', rikt: 'hog', tal: hk, fn: function(r) {
    var v = hk(r);
    return v > 0 ? chip(v + ' hk', 'rgba(251,191,36,.16)') : TOM;
  }});
  rows.push({ label: '&#x1F4CA; Prisv\xe4rdhet', fn: function(r) {
    if (r.evSpec && r.evSpec.valueLabel) return chip(caEsc(r.evSpec.valueLabel), 'rgba(52,211,153,.16)');
    var cl = caValueLabelCombustion(caIceSpec(r), caParsePrice(r.price));
    return cl ? chip(caEsc(cl), 'rgba(52,211,153,.16)') : TOM;
  }});
  rows.push({ label: '&#x1F4B0; 5-\xe5rs TCO', rikt: 'lag',
    tal: function(r) {
      var t = caIsLeasing
        ? caTcoLeasingCalc(r, caCurrentKm, parseInt(document.getElementById('ca-budget-slider').value) || 0)
        : caTcoCalc(r, caCurrentKm);
      return t ? t.total : 0;
    },
    fn: function(r) {
      var tco = caIsLeasing
        ? caTcoLeasingCalc(r, caCurrentKm, parseInt(document.getElementById('ca-budget-slider').value) || 0)
        : caTcoCalc(r, caCurrentKm);
      if (!tco) return TOM;
      return '<span class="ca-cmp-pris">~' + tco.total.toLocaleString('sv-SE') + ' kr</span>' +
        '<span class="ca-cmp-sub">' + tco.perMonth.toLocaleString('sv-SE') + ' kr/m\xe5n</span>';
    }});

  var nagonVinnare = false;
  var rowsHtml = rows.map(function(row, ri) {
    var vinnare = row.tal ? caCmpVinnare(recs, row.tal, row.rikt) : -1;
    if (vinnare >= 0) nagonVinnare = true;
    var cells = recs.map(function(r, i) {
      var vann = i === vinnare;
      return '<td class="ca-cmp-c ca-cmp-k' + i + (vann ? ' ca-cmp-vinst' : '') + '" data-kol="' + i + '">' +
        // Stjärnan FÖRE innehållet: den flyter höger, och en float placeras vid den rad där
        // den står. Sist i cellen hamnade den under TCO-radens kr/mån-rad i stället för bredvid.
        (vann ? '<span class="ca-cmp-stjarna" title="B\xe4st i raden">&#x2605;</span>' : '') +
        row.fn(r) +
      '</td>';
    }).join('');
    return '<tr class="ca-cmp-rad" style="--i:' + ri + '">' +
      '<th scope="row" class="ca-cmp-lbl">' + row.label + '</th>' + cells + '</tr>';
  }).join('');

  var headerCells = recs.map(function(r, i) {
    var short = r.title.replace(/\s*\(\d{4}\)\s*$/, '').split(' ').slice(0, 4).join(' ');
    var col = accent[i] || '#a78bfa';
    return '<th class="ca-cmp-h ca-cmp-k' + i + '" data-kol="' + i + '" style="--ca-acc:' + col + '">' +
        '<span class="ca-cmp-hbox">' + caCmpEmblem(r.title, col) + '<span>' +
          '<span class="ca-cmp-hnum">Bil ' + (i + 1) + '</span>' +
          '<span class="ca-cmp-hnamn">' + caEsc(short) + '</span>' +
        '</span></span>' +
      '</th>';
  }).join('');

  cmp.innerHTML =
    '<div class="ca-cmp">' +
      '<div class="ca-cmp-head">' +
        '<span class="ca-cmp-titel">J\xe4mf\xf6r bilar</span>' +
        (hasEv ? '<span class="ca-cmp-ev"><i>&#x26A1;</i>inkl. elbilsdata</span>' : '') +
        (nagonVinnare ? '<span class="ca-cmp-legend"><b>&#x2605;</b> b\xe4st i raden</span>' : '') +
      '</div>' +
      '<div class="ca-cmp-scroll">' +
        '<table class="ca-cmp-tab">' +
          '<thead><tr><th class="ca-cmp-hoek"></th>' + headerCells + '</tr></thead>' +
          '<tbody>' + rowsHtml + '</tbody>' +
        '</table>' +
      '</div>' +
      caTcoBarChart(recs) +
    '</div>';

  // Kolumnen man pekar på tänds hela vägen ner. Ett mouseover på rutan i stället för en
  // lyssnare per cell: tabellen har rader × bilar celler, och alla bär redan data-kol.
  var ruta = cmp.querySelector('.ca-cmp');
  if (ruta) {
    ruta.addEventListener('mouseover', function(e) {
      var t = e.target;
      var kol = null;
      while (t && t !== ruta) {
        if (t.getAttribute && t.getAttribute('data-kol') !== null) { kol = t.getAttribute('data-kol'); break; }
        t = t.parentNode;
      }
      if (kol !== null) ruta.setAttribute('data-hov', kol);
      else ruta.removeAttribute('data-hov');
    });
    ruta.addEventListener('mouseleave', function() { ruta.removeAttribute('data-hov'); });
  }
}

function caTcoBarChart(recs) {
  if (caIsLeasing) return '';
  var tcos = recs.map(function(r) { return caTcoCalc(r, caCurrentKm); });
  var valid = tcos.filter(Boolean);
  if (valid.length < 2) return '';
  var maxTotal = Math.max.apply(null, valid.map(function(t) { return t.total; }));
  var minTotal = Math.min.apply(null, valid.map(function(t) { return t.total; }));
  var billigast = valid.filter(function(t) { return t.total === minTotal; }).length === 1 ? minTotal : null;
  var accent = ['#a78bfa', '#38bdf8', '#34d399'];
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
      return '<span class="ca-cmp-seg" title="' + s.label + ': ' + Math.round(tco[s.key]/1000) + 'k\xa0kr" ' +
        'style="width:' + w + '%;background:' + s.color + '"></span>';
    }).join('');
    return '<div class="ca-cmp-bar-rad">' +
      '<div class="ca-cmp-bar-topp">' +
        '<span class="ca-cmp-bar-namn">' + caCmpEmblem(r.title, accent[i] || '#a78bfa') +
          '<span>' + caEsc(name) + '</span>' +
          (billigast !== null && tco.total === billigast ? '<span class="ca-cmp-lag">L\xe4gst</span>' : '') +
        '</span>' +
        '<span class="ca-cmp-bar-sum">' + tco.total.toLocaleString('sv-SE') + '\xa0kr</span>' +
      '</div>' +
      '<div class="ca-cmp-bar" style="--i:' + i + '">' + segs + '</div>' +
    '</div>';
  }).join('');
  var legend = segments.map(function(s) {
    return '<span class="ca-cmp-leg">' +
      '<span class="ca-cmp-prick" style="background:' + s.color + ';color:' + s.color + '"></span>' + s.label + '</span>';
  }).join('');
  return '<div class="ca-cmp-tco">' +
    '<div class="ca-cmp-tco-rub">TCO-f\xf6rdelning (5\xa0\xe5r)</div>' +
    bars +
    '<div class="ca-cmp-legend-rad">' + legend + '</div>' +
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
  // Wikipedia stavar märket med diakritik där vår databas skriver ASCII. För de flesta modeller
  // spelar det ingen roll — "Skoda Octavia" är en redirect till "Škoda Octavia" — men redirecten
  // finns inte alltid: "Skoda Enyaq" är 404 på en-wiki medan "Škoda Enyaq" är 200, och kortet
  // blev därför helt utan bild. Diakritikformen läggs till som EGEN kandidat i stället för att
  // ersätta ASCII-formen: den kostar en parallell hämtning och rör inte modellerna som fungerar.
  //
  // Riktningen är alltså den MOTSATTA mot auto-data-uppslaget, som måste fälla ë till e för att
  // träffa märkessluggen. Samma märkesnamn, två källor, två stavningar — håll dem isär.
  var WIKI_MARKEN = { 'Skoda': 'Škoda' };
  Object.keys(WIKI_MARKEN).forEach(function(ascii) {
    titles.slice().forEach(function(t) {
      if (t.indexOf(ascii) !== 0) return;
      addCandidate(WIKI_MARKEN[ascii] + t.slice(ascii.length));
    });
  });
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
    if (prefs.newCar !== undefined) { var ncEl2 = document.getElementById('ca-newcar'); if (ncEl2) ncEl2.value = prefs.newCar ? 'true' : 'false'; }
    caSetBudgetMode(prefs.budgetType === 'leasing' ? 'leasing' : 'köp', prefs.budget ? parseInt(prefs.budget) : undefined);
    if (prefs.fuelType)    document.getElementById('ca-fuel').value = prefs.fuelType;
    if (prefs.transmission) { var tEl2 = document.getElementById('ca-transmission'); if (tEl2) tEl2.value = prefs.transmission; }
    if (prefs.maxAgeYears) { var maEl2 = document.getElementById('ca-maxage'); if (maEl2) maEl2.value = prefs.maxAgeYears; }
    // caSaveSearch skickar med minCargoLiters — utan raden här sparas kravet men återställs
    // aldrig, så en sparad sökning ger ett ANNAT resultat än den gjorde när den sparades.
    // Samma asymmetri som delningslänken hade tills den lagades tidigare idag.
    if (prefs.minCargoLiters) { var cgEl = document.getElementById('ca-cargo'); if (cgEl) cgEl.value = prefs.minCargoLiters; }
    caUtanForval(caUpdateFuelVisibility); caCheckMismatch();
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
  } catch(e) { caWarn('sparad sökning', e); }
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
      newCar: caIsNewCar(),
      fuelType:     document.getElementById('ca-fuel').value,
      transmission: (function(){ var t = document.getElementById('ca-transmission'); return t ? t.value : 'spelar ingen roll'; })(),
      budgetType:   caIsLeasing ? 'leasing' : 'köp',
      maxAgeYears:  caMaxAgeYears(),
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

// Ska ägandekostnaden räkna med dieselpris? Använder det VERIFIERADE drivmedlet från
// ice_consumption när backend skickar med det (fältet fuelSpec.fuel, tillkom 2026-08-14).
//
// Tidigare gissade båda TCO-funktionerna på förbrukningen: "> 7 l/100 km ≈ dieselbil".
// Det gjorde en Kia Sportage 1.6 T-GDI (8,0 l/100 km, bensin) till diesel och en snål
// diesel under 7 till bensin — fel åt båda hållen. Tröskeln står kvar som fallback för
// kort utan DB-träff, där vi inte har något bättre, men den ska aldrig vinna över ett
// verifierat värde.
function caIsDiesel(fuelSpec) {
  if (!fuelSpec) return false;
  if (fuelSpec.fuel) return /diesel/i.test(fuelSpec.fuel);
  return fuelSpec.consumptionLiterPerMil > 7;
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
    // AI returnerar l/100km trots fältnamnet "PerMil"
    var fuelPrice = caIsDiesel(r.fuelSpec) ? CA_FUEL_PRICES.diesel : CA_FUEL_PRICES.bensin;
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
    var fp = caIsDiesel(r.fuelSpec) ? CA_FUEL_PRICES.diesel : CA_FUEL_PRICES.bensin;
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
    newcar:     caIsNewCar() ? 'true' : 'false',
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

// ── Avsvalningen efter en sökning ────────────────────────────────────────────
// Knappen var låst UNDER sökningen men släpptes i samma sekund som svaret kom, och då
// klickar man igen. Groqs minuttak är per modell och kedjan har tre, men EN sökning kan
// kosta flera anrop (fallback vid 429, reservmodellen vid parse- eller regelfel, plus
// budget- och regelomförsöken) — två-tre klick i rad tömmer alltså hela kedjan, och
// eftersom varje fallbacksteg är en egen rundtur till Groq slog klienttimeouten till innan
// felet ens hann formuleras. Användaren fick "timeout" när svaret var "vänta 20 sekunder".
//
// Nedräkningen är därför inte en artighet utan spärren: det andra klicket kan inte inträffa.
// Vid ett AI-tak räknar den ner exakt så länge Groq själv säger (retryAfterSeconds), annars
// den korta grundtiden nedan.
var CA_COOLDOWN_SECONDS = 12;
var caCooldownTimer = null;

function caKnappNedrakning(btn, sekunder, etikett) {
  if (caCooldownTimer) { clearInterval(caCooldownTimer); caCooldownTimer = null; }
  var kvar = Math.max(1, Math.round(sekunder || 0));
  btn.disabled = true;
  btn.textContent = etikett + ' ' + kvar + ' s…';
  caCooldownTimer = setInterval(function () {
    kvar--;
    if (kvar <= 0) {
      clearInterval(caCooldownTimer);
      caCooldownTimer = null;
      btn.disabled = false;
      btn.textContent = 'S\xf6k igen →';
      // Har användaren ändrat något medan den räknade ner ska knappen säga "Uppdatera
      // resultat" — caCheckChanges vet, och den avstod just för att nedräkningen ägde texten.
      caCheckChanges();
      return;
    }
    btn.textContent = etikett + ' ' + kvar + ' s…';
  }, 1000);
}

/**
 * Låter ljuset svepa genom Demo-raden och elbilspromon när de kommer i bild.
 *
 * <p>Svepet går EN gång per sidladdning. En slinga i ögonvrån blir en flimrande skylt; ett
 * svep som passerar precis när raden dyker upp läser i stället som att raden slås på.
 *
 * <p><b>Varför en observatör och inte bara vid start.</b> På en telefon ligger promon ofta
 * redan under vikningen när sidan laddas, och ett svep man aldrig ser är samma sak som inget
 * svep. Tröskeln 0,35 gör att raden måste vara påtagligt i bild — annars fyrar den av när
 * bara överkanten skymtar, och rörelsen är över innan man hunnit titta.
 *
 * <p>Faller tillbaka på att bara sätta klassen direkt om {@code IntersectionObserver} saknas,
 * och hoppar över hela effekten vid reduced motion.
 */
function caSvepVidSyn() {
  try {
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    var mal = ['ca-sub-bar', 'ca-ev-promo']
      .map(function (id) { return document.getElementById(id); })
      .filter(function (el) { return el && !el.classList.contains('ca-svept'); });
    if (!mal.length) return;
    if (!('IntersectionObserver' in window)) {
      mal.forEach(function (el) { el.classList.add('ca-svept'); });
      return;
    }
    var obs = new IntersectionObserver(function (poster) {
      poster.forEach(function (p) {
        if (!p.isIntersecting) return;
        p.target.classList.add('ca-svept');
        obs.unobserve(p.target); // en gång räcker
      });
    }, { threshold: 0.35 });
    mal.forEach(function (el) { obs.observe(el); });
  } catch (_) { /* utan svep ser raderna ut precis som förut */ }
}

/**
 * Byter ut den hemmagjorda Groq-plaketten mot Groqs egen.
 *
 * Den gamla var en {@code <span>} med en generisk blixt och texten "Drivs av Groq AI" — inte
 * Groqs märke, inte deras färg, och framför allt ingen länk. Deras dokumentation
 * (console.groq.com/docs/badge) ber om just tre saker: deras SVG oförändrad, en länk till
 * groq.com med {@code target="_blank"} och {@code rel="noopener noreferrer"}, och alt-texten
 * ordagrant. Nu uppfylls alla tre.
 *
 * <b>Varför i JS och inte bara i snippeten.</b> Sidan i WordPress är en manuell kopia som
 * släpar — den 2026-09-10 låg den en månad efter repot. Markupen finns därför på båda
 * ställena: i snippeten som facit, och här så att den WP-sida som INTE klistrats om ändå får
 * den riktiga badgen vid nästa sidladdning.
 *
 * Idempotent: har sidans egen markup redan bilden gör funktionen ingenting, så en omklistrad
 * sida inte får två badgar.
 */
function caGroqBadge() {
  var gammal = document.querySelector('.ca-groq-badge');
  if (!gammal || gammal.querySelector('.ca-groq-logo')) return;
  var lank = document.createElement('a');
  lank.className = 'ca-groq-badge';
  lank.href = 'https://groq.com';
  lank.target = '_blank';
  lank.rel = 'noopener noreferrer';
  var bild = document.createElement('img');
  bild.className = 'ca-groq-logo';
  // Bilden serveras av oss och inte av console.groq.com: en tredjepartsvärd som ligger nere
  // eller byter sökväg tar annars badgen med sig. CA_API_BASE pekar på samma värd som JS:en.
  bild.src = CA_API_BASE + '/powered-by-groq-dark.svg';
  bild.alt = 'Powered by Groq for fast inference.';
  bild.width = 53;
  bild.height = 32;
  lank.appendChild(bild);
  gammal.parentNode.replaceChild(lank, gammal);
}

/**
 * Rullar ned till snurran när sökningen startar.
 *
 * Knappen sitter längst ned i ett formulär som är över tusen pixlar högt, och laddaren ritas
 * NEDANFÖR den. På en vanlig skärm hamnade den därför under vikningen: man tryckte, ingenting
 * syntes hända, och de sekunder AI:n tänker såg ut som en död sida.
 *
 * Rullar bara när det behövs. Ligger snurran redan helt i bild står sidan still — att rycka
 * till i en vy användaren redan tittar på är värre än att låta bli. Marginalen på 24 px gör att
 * en snurra som nätt och jämnt skymtar i underkanten ändå räknas som dold.
 *
 * Följer prefers-reduced-motion: samma slutdestination, men utan den glidande rörelsen.
 */
function caScrollaTillLastning(el) {
  if (!el) return;
  try {
    var r = el.getBoundingClientRect();
    var h = window.innerHeight || document.documentElement.clientHeight;
    if (r.top >= 0 && r.bottom <= h - 24) return;
    var stilla = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    el.scrollIntoView({ behavior: stilla ? 'auto' : 'smooth', block: 'center' });
  } catch (_) { /* en utebliven rullning får aldrig fälla sökningen */ }
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
  caScrollaTillLastning(loader);

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
    newCar:      caIsNewCar(),
    fuelType:     fuelVal,
    transmission: (function(){ var t = document.getElementById('ca-transmission'); return t ? t.value : 'spelar ingen roll'; })(),
    budgetType:   caIsLeasing ? 'leasing' : 'köp',
    maxAgeYears:  caMaxAgeYears(),
    minCargoLiters: caCargoValue()
  };

  // Höjt från 35 s till 75 s 2026-08-28. En sökning är inte ETT anrop: kedjan provar tre
  // modeller i tur och ordning vid 429, servern kan dessutom sova upp till 25 s när alla tre
  // är fulla, och ovanpå det ligger reservmodellen och budget-/regelomförsöken. 35 s räckte
  // inte för den kedjan, så den vanligaste "timeouten" var i själva verket en väntan som
  // klienten klippte av — användaren fick "servern svarade inte" när svaret var "vänta".
  // Taket måste alltså vara större än serverns paus plus rundturerna, annars byter man bara
  // ett ärligt besked mot ett missvisande.
  var controller = new AbortController();
  var timeoutId = setTimeout(function() { controller.abort(); }, 75000);
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
      // Servern vet de riktiga gränserna och pekar redan på nästa steg i trappan — läs dess
      // text i stället för att upprepa en siffra här. Ett trasigt svar får inte fälla rutan.
      var kvotData = null, kvotSvar = null;
      try { kvotData = await r.json(); kvotSvar = kvotData && kvotData.error; } catch (e) { /* rubriken faller tillbaka */ }

      // TVÅ HELT OLIKA 429. `aiBusy` är Groqs minuttak — det släpper av sig självt och har
      // inget med användarens pott att göra. Att svara på det med prenumerationsrutan vore
      // både fel och oärligt: pengar hjälper inte mot ett tak som lyfter om 20 sekunder.
      if (kvotData && kvotData.aiBusy) {
        document.getElementById('ca-rate-limit-box').style.display = 'none';
        document.getElementById('ca-cards').innerHTML =
          '<div class="ca-card"><div class="ca-raw">⏳ ' +
          caEsc(kvotSvar || 'AI-tj\xe4nsten \xe4r upptagen just nu — f\xf6rs\xf6k strax igen.') +
          '</div></div>';
        caKnappNedrakning(btn, kvotData.retryAfterSeconds || CA_COOLDOWN_SECONDS, 'V\xe4nta');
        return;
      }

      document.getElementById('ca-cards').innerHTML = '';
      caFyllKvotrutan(kvotSvar);
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
    // Ett cachat svar kostade inga Groq-tokens, alltså finns inget att svalna från — då vore
    // nedräkningen bara i vägen.
    if (d.cached) {
      btn.disabled = false;
      btn.textContent = 'S\xf6k igen →';
    } else {
      caKnappNedrakning(btn, CA_COOLDOWN_SECONDS, 'Klar om');
    }

  } catch (e) {
    clearTimeout(timeoutId);
    caStopLoadingText();
    loader.style.display = 'none';
    var msg = e.name === 'AbortError'
      ? '⏱ Servern svarade inte inom 75 sekunder – försök igen om en stund.'
      : '🔌 Kunde inte n\xe5 servern: ' + e.message;
    document.getElementById('ca-cards').innerHTML =
      '<div class="ca-card"><div class="ca-raw">' + msg + '</div></div>';
    // Även ett avbrutet försök har hunnit kosta tokens hos Groq — utan avsvalning klickar
    // man rakt in i taket som just fällde sökningen.
    caKnappNedrakning(btn, CA_COOLDOWN_SECONDS, 'F\xf6rs\xf6k igen om');
  }
}

function caOpenSubscribe() {
  window.open(CA_API_BASE + '/subscribe.html', '_blank', 'width=480,height=650,resizable=yes');
}

/* Måste spegla CarController.SEARCHES_PER_HOUR. Baren räknar ner mot det här talet, så ändras
   gränsen i backend måste den ändras här. /api/search-status skickar numera med limit +
   period, och när de finns vinner de över konstanten nedan.

   Ett enda tal sedan 2026-08-22: gratiskontot är avskaffat och alla utan prenumeration har
   samma pott, inloggad som ej. Dygnstaket (100) står MEDVETET inte här — det är en
   kostnadsbroms mot skript, inte ett erbjudande, och en besökare når det aldrig. */
var CA_SEARCHES_PER_HOUR = 30;

function caUpdateSubBar(isSubscriber, isLoggedIn, remaining, limit, period) {
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
  } else if (isLoggedIn || caEmail) {
    title.textContent = 'Inloggad';
    var inLim = limit || CA_SEARCHES_PER_HOUR;
    var inPer = (period === 'day') ? 'i dag' : 'denna timme';
    desc.textContent = remaining !== null ? ' – ' + remaining + ' av ' + inLim + ' s\xf6kningar kvar ' + inPer : ' – ' + inLim + ' s\xf6kningar per timme';
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
    var anonLim = limit || CA_SEARCHES_PER_HOUR;
    var anonPer = (period === 'day') ? 'i dag' : 'denna timme';
    // Nästa steg står med i baren, inte bara i väggen: den som ser "12 av 30 kvar" utan att
    // veta vad en prenumeration ger har ingen anledning att skaffa en. Sedan gratiskontot
    // avskaffades är det obegränsat som ÄR erbjudandet — inte en större gratispott.
    var anonKvar = remaining !== null
      ? ' – ' + remaining + ' av ' + anonLim + ' s\xf6kningar kvar ' + anonPer
      : ' – ' + anonLim + ' gratis s\xf6kningar per timme';
    desc.textContent = anonKvar + ' \xb7 prenumerant: obegr\xe4nsat';
    if (remaining !== null && remaining <= 2) bar.classList.add('ca-sub-bar-limited');
    prenBtn.style.display = 'inline-block';
    prenBtn.textContent = 'Prenumerera / Logga in';
    loginLink.style.display = 'none';
    emailEl.style.display = 'none';
  }
  caUpdateEvPromo(isSubscriber);
}

/**
 * Vägen till elbilsassistenten ska synas för ALLA.
 *
 * Rutan var dold i två av tre grenar och visades bara för prenumeranter, så den som inte
 * betalade fick aldrig veta att assistenten fanns. Underrubriken sa dessutom "Ingår också i
 * din prenumeration", vilket läst av en utloggad är ett prisbesked och inte en inbjudan.
 *
 * Sedan 2026-08-22 behövs varken konto eller prenumeration för att använda den: 30 frågor i
 * timmen är gratis, obegränsat kräver prenumeration. Rubriken måste säga just det.
 *
 * Texten byggs från JS och inte i snippeten: WP-sidan är en manuell kopia, så en ändrad
 * text där syns inte förrän någon klistrar om blocket.
 */
function caUpdateEvPromo(isSubscriber) {
  var promo = document.getElementById('ca-ev-promo');
  if (!promo) return;
  promo.style.display = 'flex';
  var sub = promo.querySelector('.ca-ev-promo-sub');
  if (sub) sub.textContent = isSubscriber
    ? 'Ing\xe5r i din prenumeration'
    : 'Prova gratis – 30 fr\xe5gor i timmen, inget konto beh\xf6vs';
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
  // Avsändaren måste vara vårt eget API eller sidan själv. Meddelandet bär ett inloggnings-
  // token som skrivs rakt in i localStorage, så utan den här kontrollen kunde vilket annat
  // skript som helst på WordPress-sidan logga in en användare som någon annan. Kontrollen
  // blev nödvändig när länkarna till subscribe.html fick rel="opener".
  if (ev.origin !== CA_API_BASE && ev.origin !== window.location.origin) return;
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

// ── Märkesväljaren i den fria jämförelsen ────────────────────────────────────
// Rutorna var fritextfält med en datalist på 815 bilnamn. En datalist visar bara det man
// redan börjat skriva, alltså måste man VETA vad bilen heter för att hitta den — och stavar
// man "Skoda" utan hake, eller "VW", får man inget. Samma två steg som Elbilsassistenten
// använder: märke först, modeller sedan.
//
// Fältet blir kvar och är fortfarande värdet. Väljaren skriver i det och lämnar datalisten
// orörd, så den som hellre skriver kan fortsätta göra det — och caFcCompare, som läser
// .value, behöver inte veta att något ändrats.
var caBilarCache = null;

function caMarkeAv(namn) {
  var n = String(namn || '').trim();
  if (/^Alfa\s+Romeo/i.test(n)) return 'Alfa Romeo';
  if (/^Land\s+Rover/i.test(n)) return 'Land Rover';
  return n.split(/\s+/)[0] || '';
}

/** Emblemet för ett MÄRKE (caEmblemHtml tar en biltitel). Tom sträng när filen saknas. */
function caMarkeEmblem(marke) {
  return caEmblemHtml(marke + ' x');
}

function caFcValjare(input) {
  if (!input || input.dataset.valjare) return;
  input.dataset.valjare = '1';
  input.setAttribute('readonly', 'readonly');   // panelen är vägen in; datalisten blir kvar för den som tar bort attributet
  input.style.cursor = 'pointer';

  // Panelen bor i <body>, inte bredvid fältet. Som barn till kortet hamnade den i samma
  // stackningssammanhang som sidans glaslager, och de målades ÖVER den: texten under lyste
  // igenom och klicken tog i fel element. Ett z-index räcker inte mot ett sammanhang som
  // ligger högre upp — elementet måste ut ur det.
  //
  // Priset är att positionen måste räknas fram, och att den måste räknas OM när sidan rullar.
  var rot = document.createElement('div');
  rot.className = 'ca-vp';
  (document.body || document.documentElement).appendChild(rot);
  rot.innerHTML = '<div class="ca-vp-panel" hidden>'
    + '<input type="text" class="ca-vp-sok" placeholder="Sök märke eller modell">'
    + '<div class="ca-vp-steg"><div class="ca-vp-marken"></div><div class="ca-vp-modeller"></div></div>'
    + '</div>';
  var panel = rot.querySelector('.ca-vp-panel');
  var sok = rot.querySelector('.ca-vp-sok');
  var steg = rot.querySelector('.ca-vp-steg');
  var gridEl = rot.querySelector('.ca-vp-marken');
  var listEl = rot.querySelector('.ca-vp-modeller');
  var marken = [], aktivt = null;

  function bygg(cars) {
    var karta = {};
    cars.forEach(function (namn) {
      var m = caMarkeAv(namn);
      var k = m.toLowerCase();
      if (!karta[k]) karta[k] = { stavning: {}, bilar: [] };
      karta[k].stavning[m] = (karta[k].stavning[m] || 0) + 1;
      karta[k].bilar.push(namn);
    });
    marken = Object.keys(karta).map(function (k) {
      var st = karta[k].stavning;
      var vanligast = Object.keys(st).sort(function (a, b) { return st[b] - st[a]; })[0];
      return { marke: vanligast, bilar: karta[k].bilar };
    }).sort(function (a, b) { return a.marke.localeCompare(b.marke, 'sv'); });
  }

  function ritaMarken(f) {
    f = (f || '').trim().toLowerCase();
    var träffar = marken.filter(function (m) {
      if (!f) return true;
      if (f.length === 1) return m.marke.toLowerCase().charAt(0) === f;
      return m.marke.toLowerCase().indexOf(f) !== -1
        || m.bilar.some(function (b) { return b.toLowerCase().indexOf(f) !== -1; });
    });
    gridEl.innerHTML = träffar.length
      ? '<div class="ca-vp-grid">' + träffar.map(function (m) {
          return '<button type="button" class="ca-vp-marke" data-marke="' + caEsc(m.marke) + '">'
            + (caMarkeEmblem(m.marke) || '<span class="ca-vp-mono">' + caEsc(m.marke.slice(0, 2).toUpperCase()) + '</span>')
            + '<span class="ca-vp-txt"><span class="ca-vp-namn">' + caEsc(m.marke) + '</span>'
            + '<span class="ca-vp-antal">' + m.bilar.length + (m.bilar.length === 1 ? ' modell' : ' modeller') + '</span></span>'
            + '</button>';
        }).join('') + '</div>'
      : '<div class="ca-vp-tom">Ingen bil matchar.</div>';
  }

  function ritaModeller(marke, f) {
    var g = marken.filter(function (m) { return m.marke === marke; })[0];
    if (!g) return;
    aktivt = marke;
    f = (f || '').trim().toLowerCase();
    var lista = g.bilar.filter(function (b) { return !f || b.toLowerCase().indexOf(f) !== -1; });
    listEl.innerHTML = '<div class="ca-vp-back-rad"><button type="button" class="ca-vp-back">‹ Alla märken</button>'
      + '<span class="ca-vp-namn">' + caEsc(marke) + '</span></div>'
      + '<div class="ca-vp-lista">' + lista.map(function (b) {
          return '<button type="button" class="ca-vp-modell" data-namn="' + caEsc(b) + '">' + caEsc(b) + '</button>';
        }).join('') + '</div>';
  }

  function visa(n) { steg.classList.toggle('ca-vp-at-modeller', n === 2); }

  // Panelen läggs över hela fältraden, inte bara över det klickade fältet: 815 bilar blir
  // många märken, och en panel lika smal som rutan hade gett en enkolumns pelare.
  function placera() {
    var rad = input.closest('.ca-fc-pickers') || input;
    var r = rad.getBoundingClientRect();
    panel.style.left = Math.round(r.left) + 'px';
    panel.style.top = Math.round(r.bottom + 7) + 'px';
    panel.style.width = Math.round(r.width) + 'px';
  }
  var placeraOm = function () { if (!panel.hidden) placera(); };
  window.addEventListener('scroll', placeraOm, true);
  window.addEventListener('resize', placeraOm);

  function stang() { panel.hidden = true; rot.classList.remove('ca-vp-open'); }

  function oppna() {
    fetch(CA_API_BASE + '/api/cars').then(function (r) { return r.json(); }).then(function (cars) {
      caBilarCache = cars;
      bygg(cars);
      panel.hidden = false;
      placera();
      rot.classList.add('ca-vp-open');
      sok.value = ''; aktivt = null; ritaMarken(''); visa(1);
      setTimeout(function () { sok.focus({ preventScroll: true }); }, 30);
    }).catch(function () {});
  }

  input.addEventListener('click', function () { if (panel.hidden) oppna(); else stang(); });
  input.addEventListener('focus', function () { if (panel.hidden) oppna(); });

  panel.addEventListener('click', function (e) {
    var b = e.target.closest('.ca-vp-marke');
    if (b) { sok.value = ''; ritaModeller(b.dataset.marke, ''); visa(2); sok.focus({ preventScroll: true }); return; }
    if (e.target.closest('.ca-vp-back')) { sok.value = ''; aktivt = null; ritaMarken(''); visa(1); return; }
    var m = e.target.closest('.ca-vp-modell');
    if (m) {
      input.value = m.dataset.namn;
      stang();
      caFcVisaSpec(input);
      if (typeof caCheckChanges === 'function') caCheckChanges();
    }
  });
  sok.addEventListener('input', function () {
    if (aktivt && steg.classList.contains('ca-vp-at-modeller')) {
      if (!this.value.trim()) { aktivt = null; ritaMarken(''); visa(1); return; }
      ritaModeller(aktivt, this.value); return;
    }
    ritaMarken(this.value);
  });
  sok.addEventListener('keydown', function (e) { if (e.key === 'Escape') { stang(); input.focus(); } });
  document.addEventListener('click', function (e) {
    if (!panel.hidden && !rot.contains(e.target) && e.target !== input) stang();
  });
}

// Specarna för en vald bil, nyckel = bilnamnet. Väljaren är gjord för att bläddras i, och
// utan cache blev det ett nätanrop per klick på samma bil.
var caFcSpecCache = {};

/**
 * Visar räckvidd, batteri och laddeffekt direkt när en modell valts — utan att man först
 * måste göra en jämförelse.
 *
 * <p>Siffrorna kommer från /api/ev-spec, som går genom samma formatForTitle som
 * rekommendationskorten, och renderas med samma caEvChips. Två vägar till samma bil får
 * inte kunna visa olika tal.
 *
 * <p>Bensinbilar och elbilar vi inte har specar för ger tomt svar och därmed ingen ruta —
 * inget felmeddelande, för det är inte ett fel att en bil saknar elbilsdata.
 */
function caFcVisaSpec(input) {
  var pickers = document.querySelector('.ca-fc-pickers');
  if (!pickers) return;
  var rad = document.getElementById('ca-fc-specs');
  if (!rad) {
    rad = document.createElement('div');
    rad.id = 'ca-fc-specs';
    rad.innerHTML = '<div id="ca-fc-car1-spec"></div><div id="ca-fc-car2-spec"></div>';
    pickers.parentNode.insertBefore(rad, pickers.nextSibling);
  }
  var box = document.getElementById(input.id + '-spec');
  if (!box) return;

  var namn = (input.value || '').trim();
  if (!namn) { box.innerHTML = ''; return; }

  // Körsträckan styr "ladda var N:e dag" — läs formulärets värde så rutan säger samma sak
  // som kortet skulle ha gjort för samma sökning. Den ingår därför i cachenyckeln: annars
  // hade en ändrad körsträcka fått tillbaka gamla laddintervallet ur cachen.
  var milEl = document.getElementById('ca-km');
  var km = milEl && parseInt(milEl.value) > 0 ? parseInt(milEl.value) * 10 : 12430;
  var nyckel = namn + '@' + km;
  if (caFcSpecCache[nyckel] !== undefined) { box.innerHTML = caFcSpecCache[nyckel]; return; }

  fetch(CA_API_BASE + '/api/ev-spec?car=' + encodeURIComponent(namn) + '&kmPerYear=' + km)
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var html = (d && d.wltpKm > 0) ? caEvChips(d, 0) : '';
      caFcSpecCache[nyckel] = html;
      // Hann man byta bil medan svaret var i luften vinner det senare valet.
      if ((input.value || '').trim() === namn) box.innerHTML = html;
    })
    .catch(function () { box.innerHTML = ''; });
}

function caFcInit() {
  var btn = document.getElementById('ca-fc-btn');
  if (btn) btn.addEventListener('click', caFcCompare);
  ['ca-fc-car1','ca-fc-car2'].forEach(function(id) {
    var el = document.getElementById(id);
    if (el) {
      el.addEventListener('focus', caFcFetchCars);
      caFcValjare(el);
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

// ── Vägen vid rubriken ───────────────────────────────────────────────────────
// En bil som kör på en väg som aldrig tar slut, till höger om "Hitta din drömbil".
//
// Byggs från JS av samma skäl som allt annat i den här filen: WP-sidan är en manuell kopia
// och ska slippa klistras om för en ren utsmyckning. Rubriken flyttas in i en flexrad
// tillsammans med vägen — h2:an behåller sin identitet och därmed sina stilar.
//
// Bilen står stilla och VÄGEN rullar. Att i stället köra bilen över ytan hade krävt att den
// försvinner ut i kanten och dyker upp igen, och just den hoppen är vad "aldrig tar slut"
// inte får se ut som. Mittlinjen förskjuts exakt en periodlängd (32 px) per varv, så
// slingan går ihop utan synligt skarvsteg.
//
// Bilen är en inline-SVG och inte 🚗: emojin pekar åt VÄNSTER i de flesta teckensnitt, och en
// bil som kör baklänges längs en väg som rullar åt höger var det första jag såg.
(function caVagCss() {
  if (document.getElementById('ca-vag-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-vag-css';
  s.textContent = [
    // Rubrikraden: h2:an tar sin plats, vägen resten
    // Remsan ar hogre an rubriken, sa raden far sin egen bottenmarginal: h2:ns egen
    // marginal ligger INNE i flexraden och ger noll luft ned till underrubriken.
    '.ca-rubrikrad{display:flex;align-items:center;gap:16px;margin-bottom:7px;}',
    '.ca-rubrikrad h2{margin-bottom:0!important;flex:0 0 auto;}',
    // Masken åt båda håll är hela poängen: utan den slutar vägen tvärt i två kanter, och
    // en väg med synliga ändar tar per definition slut.
    '.ca-vag{position:relative;flex:1 1 auto;min-width:70px;height:72px;overflow:hidden;',
      '-webkit-mask:linear-gradient(90deg,transparent,#000 16%,#000 84%,transparent);',
      'mask:linear-gradient(90deg,transparent,#000 16%,#000 84%,transparent);}',
    // ── Soluppgången framför bilen ──────────────────────────────────────────
    // Bilens nos och strålkastare pekar åt höger, så solen ligger åt höger: man kör MOT den.
    // Horisonten är asfaltens överkant (24 px från nederkant), och eftersom vägbanan ritas
    // efter solen i DOM:en skär den av nedre halvan — det är den avskurna cirkeln som gör att
    // en gul prick läser som en soluppgång i stället för som en lampa.
    // ── Landskapet bakom vägen ───────────────────────────────────────────────
    // Tre lager som alla rör sig LÅNGSAMMARE än vägbanan. Det är hela poängen: parallaxen är
    // det som gör en 18 px hög himmel till ett djup i stället för en tapet. Vägbanan rullar på
    // 1,15 s, stolparna på 2,6 s, kullarna på 60 s och molnen på 90 s.
    //
    // Kullarna är radialgradienter som upprepas i sidled, inte en bild: två lager med olika
    // storlek, ton och tempo ger en fjärran ås bakom en närmare, och en fil att hålla synkad
    // mindre. Den bortre är ljusare — luftperspektiv, avstånd bleker.
    // Staden pa horisonten. Tre lager rektangler i olika hojd och takt ger en skyline utan en
    // enda bildfil: hojden sitter i background-size och husen i en repeating-gradient, sa var
    // lager blir en husrad. Rundade kullar provades forst och blev bubblor i den har skalan -
    // atta pixlar hoga hus laser som stad, atta pixlar hoga kullar laser som ingenting.
    '.ca-vag-stad{position:absolute;left:0;bottom:51px;width:calc(100% + 160px);height:11px;',
      'pointer-events:none;',
      'background-image:repeating-linear-gradient(90deg,rgba(58,44,98,.95) 0 6px,transparent 6px 26px),',
        'repeating-linear-gradient(90deg,rgba(70,54,118,.9) 0 4px,transparent 4px 17px),',
        'repeating-linear-gradient(90deg,rgba(48,36,84,.95) 0 8px,transparent 8px 43px);',
      'background-size:80px 8px,80px 5px,80px 11px;',
      'background-position:0 100%,26px 100%,52px 100%;background-repeat:repeat-x;',
      'animation:ca-vag-kulle 60s linear infinite;}',
    // Ljusdiset over staden: det som skiljer en svart siluett fran en stad man tror ar bebodd.
    '.ca-vag-stadsljus{position:absolute;left:0;right:0;bottom:51px;height:9px;pointer-events:none;',
      'background:linear-gradient(180deg,transparent,rgba(196,181,253,.16));',
      '-webkit-mask:linear-gradient(90deg,transparent,#000 25%,#000 75%,transparent);',
      'mask:linear-gradient(90deg,transparent,#000 25%,#000 75%,transparent);}',
    '@keyframes ca-vag-kulle{from{transform:translateX(0)}to{transform:translateX(-80px)}}',
    '.ca-vag-kullar-bort{position:absolute;left:0;bottom:53px;width:calc(100% + 200px);height:8px;',
      'pointer-events:none;opacity:.65;',
      'background-image:radial-gradient(ellipse 52px 8px at 50% 100%,rgba(109,88,168,.7) 0 98%,transparent 100%);',
      'background-size:104px 8px;background-repeat:repeat-x;background-position:0 100%;',
      'animation:ca-vag-kulle-bort 96s linear infinite;}',
    '@keyframes ca-vag-kulle-bort{from{transform:translateX(0)}to{transform:translateX(-104px)}}',
    // Molnen: tunna streck som färgas av soluppgången. Låg opacitet — de ska antydas, inte
    // läsas, annars konkurrerar de med solen om en himmel som är arton pixlar hög.
    '.ca-vag-moln{position:absolute;left:0;top:2px;width:calc(100% + 260px);height:9px;',
      'pointer-events:none;opacity:.5;',
      'background-image:radial-gradient(ellipse 26px 3px at 30% 50%,rgba(253,186,116,.5) 0 70%,transparent 100%),',
        'radial-gradient(ellipse 17px 2px at 78% 80%,rgba(251,207,232,.45) 0 70%,transparent 100%);',
      'background-size:130px 9px;background-repeat:repeat-x;',
      'animation:ca-vag-molndrift 90s linear infinite;}',
    '@keyframes ca-vag-molndrift{from{transform:translateX(0)}to{transform:translateX(-130px)}}',
    // Fåglarna: tre streck som glider förbi högt uppe, med en mjuk våg i banan så de seglar i
    // stället för att åka på en skena. De passerar var 22:a sekund — sällan nog att det känns
    // som en händelse när man ser dem.
    '.ca-vag-faglar{position:absolute;left:-14%;top:1px;width:34px;height:9px;pointer-events:none;',
      'opacity:.75;animation:ca-vag-fagelfard 22s linear infinite;}',
    '@keyframes ca-vag-fagelfard{0%{left:-14%;transform:translateY(0)}',
      '25%{transform:translateY(2.5px)}50%{transform:translateY(-1.5px)}75%{transform:translateY(2px)}',
      '62%{left:118%}100%{left:118%;transform:translateY(0)}}',
    '.ca-vag-himmel{position:absolute;left:0;right:0;top:0;bottom:51px;pointer-events:none;',
      'background:radial-gradient(ellipse 60% 150% at 72% 100%,rgba(251,191,36,.34),rgba(244,63,94,.16) 45%,transparent 72%),',
      'linear-gradient(180deg,transparent 45%,rgba(251,113,133,.1) 78%,rgba(251,146,60,.16));}',
    // Solen går en långsam båge över himlen i stället för att stå still och andas. Sol och
    // strålar sitter i en gemensam vagn så de aldrig glider isär — hade de haft var sin
    // animation räckte en bildruta av olikhet för att strålarna skulle hamna bredvid solen.
    //
    // Bågen är EN animation med tre håll (vänster-lågt, mitten-högt, höger-lågt) och
    // {@code alternate}, alltså vänder den och går tillbaka. Två ändpunkter hade gett en rak
    // linje, och ett varv utan alternate hade hoppat tillbaka synligt vid varje omstart.
    //
    // 96 sekunder ett varv: rörelsen ska upptäckas, inte iakttas. Höjden är tagen så att solen
    // aldrig når stripens överkant — 42 px hög remsa, horisonten 24 px upp, och en 19 px sol
    // som toppar 9 px över horisonten slutar 1,5 px innanför kanten. Toppar den högre klipps
    // den av ramen och ser trasig ut i stället för hög.
    '.ca-vag-solvagn{position:absolute;left:72%;bottom:51px;width:0;height:0;pointer-events:none;',
      'animation:ca-vag-bana 96s ease-in-out infinite alternate;}',
    '@keyframes ca-vag-bana{0%{transform:translate(-52px,5px)}50%{transform:translate(0,-9px)}',
      '100%{transform:translate(52px,5px)}}',
    '.ca-vag-sol{position:absolute;left:0;bottom:0;width:19px;height:19px;margin-left:-9.5px;',
      'margin-bottom:-9.5px;border-radius:50%;pointer-events:none;',
      'background:radial-gradient(circle,#fffbeb 0 28%,#fde68a 48%,#fbbf24 68%,rgba(251,146,60,.85) 88%,rgba(251,146,60,0) 100%);',
    // Två animationer med olika uppgifter: dagern äger glorian och följer bågen (svagast vid
    // horisonten, starkast i topp), pulsen äger ljusstyrkan och andas i egen takt. Delade de
    // på box-shadow hade den ena tyst vunnit över den andra.
      'animation:ca-vag-dager 96s ease-in-out infinite alternate,ca-vag-puls 5s ease-in-out infinite alternate;}',
    '@keyframes ca-vag-dager{0%,100%{box-shadow:0 0 11px 2px rgba(251,146,60,.45),0 0 24px 5px rgba(244,63,94,.2)}',
      '50%{box-shadow:0 0 20px 5px rgba(253,224,71,.8),0 0 46px 12px rgba(251,146,60,.45)}}',
    '@keyframes ca-vag-puls{from{filter:brightness(.94)}to{filter:brightness(1.12)}}',
    // Strålarna: en solfjäder som vrider sig bakom solen. Två lager — korta täta strålar nära
    // skivan och fyra långa som når ut i himlen — för att en ensam konisk gradient antingen
    // blir ett hjul (om den syns) eller ingenting alls (om den inte gör det).
    '.ca-vag-stralar{position:absolute;left:0;bottom:0;width:78px;height:78px;margin-left:-39px;',
      'margin-bottom:-39px;pointer-events:none;opacity:.5;',
      'background:conic-gradient(from 0deg,rgba(253,224,71,.9) 0 2.5deg,transparent 2.5deg 45deg,',
        'rgba(253,224,71,.75) 45deg 47.5deg,transparent 47.5deg 90deg,',
        'rgba(253,224,71,.9) 90deg 92.5deg,transparent 92.5deg 135deg,',
        'rgba(253,224,71,.75) 135deg 137.5deg,transparent 137.5deg 180deg,',
        'rgba(253,224,71,.9) 180deg 182.5deg,transparent 182.5deg 225deg,',
        'rgba(253,224,71,.75) 225deg 227.5deg,transparent 227.5deg 270deg,',
        'rgba(253,224,71,.9) 270deg 272.5deg,transparent 272.5deg 315deg,',
        'rgba(253,224,71,.75) 315deg 317.5deg,transparent 317.5deg 360deg);',
      '-webkit-mask:radial-gradient(circle,transparent 10%,#000 16%,rgba(0,0,0,.45) 40%,transparent 62%);',
      'mask:radial-gradient(circle,transparent 10%,#000 16%,rgba(0,0,0,.45) 40%,transparent 62%);',
      'animation:ca-vag-snurr 34s linear infinite;}',
    // Den inre kransen: kortare, tätare och motsatt rotationsriktning, så skenet lever i stället
    // för att snurra som ett hjul.
    '.ca-vag-krans{position:absolute;left:0;bottom:0;width:40px;height:40px;margin-left:-20px;',
      'margin-bottom:-20px;pointer-events:none;opacity:.42;',
      'background:conic-gradient(from 0deg,rgba(255,251,235,.85) 0 2deg,transparent 2deg 22.5deg,',
        'rgba(255,251,235,.7) 22.5deg 24.5deg,transparent 24.5deg 45deg);',
      'background-size:100% 100%;',
      '-webkit-mask:radial-gradient(circle,transparent 22%,#000 30%,transparent 58%);',
      'mask:radial-gradient(circle,transparent 22%,#000 30%,transparent 58%);',
      'animation:ca-vag-snurr-bak 21s linear infinite;}',
    '@keyframes ca-vag-snurr-bak{to{transform:rotate(-360deg)}}',
    // Ljusstrimman på asfalten följer solen i sidled — utan den ligger solen bakom vägen i
    // stället för att lysa på den, och står den still avslöjar den att solen rört sig.
    '.ca-vag-glans{position:absolute;left:72%;bottom:36px;width:74px;height:15px;margin-left:-37px;',
      'pointer-events:none;border-radius:2px;',
      'background:radial-gradient(ellipse 50% 120% at 50% 0%,rgba(253,224,71,.3),transparent 70%);',
      'animation:ca-vag-glans 96s ease-in-out infinite alternate;}',
    '@keyframes ca-vag-glans{0%{transform:translateX(-52px);opacity:.5}',
      '50%{transform:translateX(0);opacity:1}100%{transform:translateX(52px);opacity:.5}}',
    // ── Havet under vagbanan ────────────────────────────────────────────────
    // Vagen gar pa en bank med vatten nedanfor. De nio pixlarna under asfalten stod tomma och
    // visade heron bakgrund; nu ligger havet dar, med solens vag i sig.
    // Vattnet var marinblått mot nästan svart och läste som sörja i en nio pixlars remsa. Nu
    // tjugo pixlar och en ljusare skala — turkos vid horisonten, korallblått i mitten, djupare
    // blått längst ned. Att den ljusaste tonen ligger ÖVERST är det som gör ytan till vatten:
    // himlen speglas där, och botten är alltid mörkast.
    '.ca-vag-hav{position:absolute;left:0;right:0;bottom:0;height:28px;pointer-events:none;',
      'background:linear-gradient(180deg,#4bb6d6 0,#2f8fc4 22%,#1f66a8 52%,#173f7d 78%,#12245c 100%);',
      'box-shadow:inset 0 1px 0 rgba(186,240,255,.5);}',
    // Vågkammarna: tre rader ljusa streck i olika täthet och takt. Den översta raden är tätast
    // och ljusast — nära horisonten står vågorna tätt ihop sett i perspektiv, längre ned glesnar
    // de. Utan den skillnaden ser ytan platt ut oavsett hur mycket den rör sig.
    '.ca-vag-vagor{position:absolute;left:0;bottom:0;width:calc(100% + 120px);height:28px;',
      'pointer-events:none;opacity:.85;',
      'background-image:repeating-linear-gradient(90deg,rgba(224,252,255,.75) 0 5px,transparent 5px 13px),',
        'repeating-linear-gradient(90deg,rgba(186,240,255,.5) 0 7px,transparent 7px 22px),',
        'repeating-linear-gradient(90deg,rgba(147,213,240,.42) 0 9px,transparent 9px 31px);',
      'background-size:60px 1px,72px 1.5px,88px 1.5px;background-repeat:repeat-x;',
      'background-position:0 4px,26px 12px,54px 21px;',
      'animation:ca-vag-vagskvalp 7s linear infinite;}',
    '@keyframes ca-vag-vagskvalp{from{transform:translateX(0)}to{transform:translateX(-60px)}}',
    // Solvägen på vattnet följer solen i sidled, precis som glansen på asfalten. Utan den lyser
    // solen på vägen men inte på havet, och då ligger de i två olika världar. Den går hela vägen
    // ned genom vattnet och smalnar av — en solväg är bred vid betraktaren och spetsig vid solen.
    '.ca-vag-solvag{position:absolute;left:72%;bottom:0;width:54px;height:28px;margin-left:-27px;',
      'pointer-events:none;',
      'background:radial-gradient(ellipse 26% 108% at 50% 0%,rgba(255,247,214,.85),rgba(253,224,71,.5) 34%,',
        'rgba(103,232,249,.34) 62%,transparent 84%);',
      'animation:ca-vag-glans 96s ease-in-out infinite alternate;}',
    // Glittret: enstaka gnistor i solvägen som tänds och slocknar. Tre punkter räcker — det är
    // oregelbundenheten som läser som glitter, inte antalet.
    '.ca-vag-glitter{position:absolute;left:72%;bottom:0;width:64px;height:28px;margin-left:-32px;',
      'pointer-events:none;',
      'background-image:radial-gradient(circle,rgba(255,255,255,.95) 0 .7px,transparent 1.2px),',
        'radial-gradient(circle,rgba(224,252,255,.85) 0 .6px,transparent 1.1px),',
        'radial-gradient(circle,rgba(255,247,214,.9) 0 .5px,transparent 1px);',
      'background-size:17px 9px,23px 12px,13px 15px;',
      'background-position:3px 5px,9px 14px,1px 22px;background-repeat:repeat-x;',
      'animation:ca-vag-glans 96s ease-in-out infinite alternate,ca-vag-glitter 2.6s ease-in-out infinite alternate;}',
    '@keyframes ca-vag-glitter{from{opacity:.35}to{opacity:1}}',
    // Solskenet som lagger sig OVER allt: vagbana, hav och bada bilarna. mix-blend-mode:screen
    // gor att det ADDERAR ljus i stallet for att lagga en gul hinna over motivet - en vanlig
    // genomskinlig ruta hade grumlat den roda lacken i stallet for att fa den att glodga.
    // Ritas sist i markupen sa det hamnar ovanpa bilarna, och foljer solen i sidled.
    '.ca-vag-solsken{position:absolute;left:72%;bottom:0;width:170px;height:58px;margin-left:-85px;',
      'pointer-events:none;z-index:3;mix-blend-mode:screen;',
      'background:radial-gradient(ellipse 46% 64% at 50% 74%,rgba(253,224,71,.34),rgba(251,146,60,.18) 42%,transparent 74%);',
      'animation:ca-vag-glans 96s ease-in-out infinite alternate;}',
    // ── Stranden mellan vagen och vattnet ──────────────────────────────────
    // Atta pixlar sand under vagbanken. Kornigheten ar en punktgradient och inte en bild:
    // helt slat sand laser som en brun list, och det ar prickarna som gor den till strand.
    '.ca-vag-strand{position:absolute;left:0;right:0;bottom:28px;height:8px;pointer-events:none;',
      'background-image:radial-gradient(circle,rgba(120,90,58,.5) 0 .5px,transparent .9px),',
        'linear-gradient(180deg,#b99a72,#9d7c55 55%,#7d6041);',
      'background-size:5px 4px,100% 100%;}',
    // Skummet i vattenbrynet: en skurad kant som skjuts UPP pa sanden och drar sig tillbaka.
    // Tva vagor med olika takt och fas - en ensam skulle andas som en maskin, tva laser som hav.
    // Kanten ar radialgradienter som upprepas, sa vagbagarna far en bage i stallet for en rak
    // linje, och hela remsan driver dessutom i sidled sa monstret aldrig star still.
    '.ca-vag-skum{position:absolute;left:0;bottom:26px;width:calc(100% + 90px);height:6px;',
      'pointer-events:none;opacity:.72;',
      'background-image:radial-gradient(ellipse 9px 4px at 50% 100%,rgba(255,255,255,.85) 0 58%,rgba(224,252,255,.4) 58%,transparent 100%),',
      'radial-gradient(ellipse 6px 3px at 50% 100%,rgba(255,255,255,.6) 0 55%,transparent 100%);',
      'background-size:27px 6px,41px 5px;background-repeat:repeat-x;background-position:0 100%,13px 100%;',
      'animation:ca-vag-skvalp 5.5s ease-in-out infinite alternate,ca-vag-skumdrift 23s linear infinite;}',
    '@keyframes ca-vag-skvalp{from{transform:translateY(2.5px);opacity:.65}to{transform:translateY(-1.5px);opacity:1}}',
    '@keyframes ca-vag-skumdrift{from{background-position-x:0,13px}to{background-position-x:-27px,-28px}}',
    '.ca-vag-skum-bak{position:absolute;left:0;bottom:23px;width:calc(100% + 90px);height:5px;',
      'pointer-events:none;opacity:.55;',
      'background-image:radial-gradient(ellipse 11px 4px at 50% 100%,rgba(224,252,255,.8) 0 58%,transparent 100%);',
      'background-size:29px 5px;background-repeat:repeat-x;background-position:0 100%;',
      'animation:ca-vag-skvalp 7.8s ease-in-out infinite alternate -2.6s,ca-vag-skumdrift 31s linear infinite;}',
    // Asfalten
    '.ca-vag-yta{position:absolute;left:0;right:0;bottom:36px;height:15px;border-radius:2px;',
      'background:linear-gradient(180deg,#2b2247,#171126);',
      'box-shadow:inset 0 1px 0 rgba(255,255,255,.07),0 4px 14px rgba(0,0,0,.45);}',
    // Mittlinjen. Bredare än ytan och förskjuten en hel period per varv.
    '.ca-vag-linje{position:absolute;top:50%;left:0;width:calc(100% + 40px);height:2px;',
      'transform:translateY(-50%);border-radius:2px;',
      'background:repeating-linear-gradient(90deg,rgba(251,191,36,.9) 0 14px,transparent 14px 32px);',
      'animation:ca-vag-rull 1.15s linear infinite;}',
    '@keyframes ca-vag-rull{from{transform:translate(0,-50%)}to{transform:translate(-32px,-50%)}}',
    // Kantlinjen längst ner ger vägen djup utan att konkurrera med mittlinjen
    '.ca-vag-kant{position:absolute;left:0;bottom:36px;width:calc(100% + 24px);height:1px;',
      'background:repeating-linear-gradient(90deg,rgba(255,255,255,.22) 0 8px,transparent 8px 24px);',
      'animation:ca-vag-kant 0.85s linear infinite;}',
    '@keyframes ca-vag-kant{from{transform:translateX(0)}to{transform:translateX(-24px)}}',
    // Lyktstolparna passerar långsammare än vägbanan — parallaxen gör att vägen får djup
    '.ca-vag-stolpar{position:absolute;left:0;bottom:51px;width:calc(100% + 90px);height:16px;',
      'background:repeating-linear-gradient(90deg,rgba(167,139,250,.4) 0 2px,transparent 2px 90px);',
      'animation:ca-vag-stolp 2.6s linear infinite;}',
    '@keyframes ca-vag-stolp{from{transform:translateX(0)}to{transform:translateX(-90px)}}',
    // Bilen: står still i sidled, guppar lite. Skuggan följer med guppet.
    '.ca-vag-bil{position:absolute;left:44%;bottom:40px;width:46px;height:21px;',
      'animation:ca-vag-gupp .42s ease-in-out infinite alternate;',
      'filter:drop-shadow(0 4px 5px rgba(0,0,0,.55)) drop-shadow(3px 0 4px rgba(251,191,36,.45));}',
    '@keyframes ca-vag-gupp{from{transform:translateY(0)}to{transform:translateY(-1.2px)}}',
    // ── Sportbilen som kör om ────────────────────────────────────────────────
    // Den ligger i den NÄRMASTE filen: några pixlar lägre, en aning större och med en tyngre
    // skugga. Utan den skillnaden ser en omkörning ut som två bilar som krockar i samma spår.
    //
    // Positionen animeras i procent av vägremsan (left), inte i pixlar med transform. Remsan
    // är elastisk — den krymper när rubriken tar plats — och en omkörning mätt i pixlar hade
    // slutat mitt i bild på en smal skärm och långt utanför på en bred.
    //
    // Cykeln är 13 s men själva passagen bara 4,3 s av dem: bilen ska komma, dra förbi och
    // försvinna, och sedan ska vägen få vara i fred en stund. En sportbil som varvar i loop
    // utan paus blir en karusell, inte en omkörning.
    '.ca-vag-sport{position:absolute;bottom:37px;width:53px;height:19px;left:-20%;',
      'pointer-events:none;z-index:2;',
      'filter:drop-shadow(0 4px 6px rgba(0,0,0,.6)) drop-shadow(-6px 0 7px rgba(239,68,68,.35));',
      'animation:ca-vag-omkorning 13s linear infinite;}',
    '@keyframes ca-vag-omkorning{0%{left:-20%}33%{left:118%}100%{left:118%}}',
    // Fartstrimman ligger BAKOM bilen och töjs ut i färdriktningens motsats.
    '.ca-vag-sport-strimma{position:absolute;bottom:41px;height:2px;width:34px;left:-20%;',
      'margin-left:-30px;border-radius:2px;pointer-events:none;z-index:1;',
      'background:linear-gradient(90deg,transparent,rgba(248,113,113,.75),rgba(254,202,202,.9));',
      'animation:ca-vag-omkorning 13s linear infinite;}',
    '.ca-vag-hjul{transform-box:fill-box;transform-origin:center;animation:ca-vag-snurr .34s linear infinite;}',
    '@keyframes ca-vag-snurr{to{transform:rotate(360deg)}}',
    // Fartstrecken bakom bilen: tre streck som skjuts bakåt i olika takt
    '.ca-vag-fart{position:absolute;bottom:46px;height:1.5px;border-radius:2px;',
      'background:linear-gradient(90deg,transparent,rgba(186,230,253,.75));',
      'animation:ca-vag-fartlinje 1s linear infinite;}',
    '@keyframes ca-vag-fartlinje{0%{opacity:0;transform:translateX(6px) scaleX(.4)}',
      '25%{opacity:.9}100%{opacity:0;transform:translateX(-26px) scaleX(1)}}',
    // Strålkastarkäglan framåt
    '.ca-vag-ljus{position:absolute;left:calc(44% + 42px);bottom:42px;width:34px;height:12px;',
      'background:linear-gradient(90deg,rgba(253,230,138,.5),transparent);',
      'clip-path:polygon(0 38%,100% 0,100% 100%,0 62%);pointer-events:none;',
      'animation:ca-vag-ljuspuls 2.4s ease-in-out infinite;}',
    '@keyframes ca-vag-ljuspuls{0%,100%{opacity:.55}50%{opacity:.9}}',
    // ── Vägen på mobil: egen rad under rubriken, nedskalad ──────────────────
    // Under 560 px konkurrerar vägen med rubriken om bredden, och rubriken vinner — därför låg
    // scenen dold här förut. Lösningen är inte att klämma in den bredvid rubriken utan att
    // flytta ned den: hela raden bryts, och vägen får en egen rad i full bredd.
    //
    // Nedskalningen görs med transform och inte genom att skriva om varje mått. Scenen består
    // av ett tjugotal pixelvärden — asfalt, sol, strålar, två bilar, stolpar — och en mobil
    // kopia av alla hade blivit två sanningar som glider isär vid nästa ändring. Bredden sätts
    // till 1/0,74 så att den skalade bredden landar på exakt 100 %, och den negativa
    // marginalen tar bort luften som den outnyttjade höjden annars lämnar.
    '@media(max-width:560px){.ca-rubrikrad{flex-wrap:wrap;gap:0;}',
      '.ca-vag{display:block;flex:0 0 135.1%;min-width:0;height:72px;',
        'transform:scale(.74);transform-origin:left top;margin:-2px 0 -7px;}}',
    '@media(prefers-reduced-motion:reduce){.ca-vag-linje,.ca-vag-kant,.ca-vag-stolpar,',
      '.ca-vag-bil,.ca-vag-hjul,.ca-vag-fart,.ca-vag-ljus,.ca-vag-sol,.ca-vag-stralar,',
      '.ca-vag-krans,.ca-vag-solvagn,.ca-vag-glans{animation:none!important;}',
      '.ca-vag-sport,.ca-vag-sport-strimma,.ca-vag-faglar{display:none;}',
      '.ca-vag-stad,.ca-vag-kullar-bort,.ca-vag-moln,.ca-vag-vagor,',
      '.ca-vag-solvag,.ca-vag-glitter,.ca-vag-solsken,.ca-vag-skum,',
      '.ca-vag-skum-bak{animation:none!important;}',
      '.ca-vag-fart{opacity:.5;}}'
  ].join('');
  (document.body || document.documentElement).appendChild(s);
})();

/**
 * Sätter vägen bredvid rubriken.
 *
 * <p>Idempotent: körs om utan att dubblera, eftersom WP-sidan kan ladda skriptet en gång till
 * vid mjuka sidbyten. Saknas rubriken händer ingenting — hellre ingen väg än ett undantag som
 * stoppar resten av initieringen.
 */
function caByggVag() {
  try {
    if (document.querySelector('.ca-vag')) return;
    var hero = document.getElementById('ca-hero');
    var h2 = hero && hero.querySelector('h2');
    if (!h2) return;

    var rad = document.createElement('div');
    rad.className = 'ca-rubrikrad';
    h2.parentNode.insertBefore(rad, h2);
    rad.appendChild(h2);

    var vag = document.createElement('div');
    vag.className = 'ca-vag';
    vag.setAttribute('aria-hidden', 'true');
    vag.innerHTML =
      // Solen först: allt som ritas efter den skär av den vid horisonten.
      '<div class="ca-vag-himmel"></div>' +
      '<div class="ca-vag-moln"></div>' +
      '<svg class="ca-vag-faglar" viewBox="0 0 34 9" xmlns="http://www.w3.org/2000/svg" fill="none" stroke="rgba(226,232,240,.8)" stroke-width=".9" stroke-linecap="round">' +
        '<path d="M1 4 Q3 2 5 4 Q7 2 9 4"/><path d="M13 7 Q14.6 5.6 16.2 7 Q17.8 5.6 19.4 7"/>' +
        '<path d="M24 3 Q25.8 1.4 27.6 3 Q29.4 1.4 31.2 3"/>' +
      '</svg>' +
      '<div class="ca-vag-kullar-bort"></div>' +
      '<div class="ca-vag-stadsljus"></div>' +
      '<div class="ca-vag-stad"></div>' +
      '<div class="ca-vag-solvagn">' +
        '<div class="ca-vag-stralar"></div>' +
        '<div class="ca-vag-krans"></div>' +
        '<div class="ca-vag-sol"></div>' +
      '</div>' +
      '<div class="ca-vag-stolpar"></div>' +
      '<div class="ca-vag-hav"></div>' +
      '<div class="ca-vag-vagor"></div>' +
      '<div class="ca-vag-solvag"></div>' +
      '<div class="ca-vag-glitter"></div>' +
      '<div class="ca-vag-skum-bak"></div>' +
      '<div class="ca-vag-strand"></div>' +
      '<div class="ca-vag-skum"></div>' +
      '<div class="ca-vag-yta"></div>' +
      '<div class="ca-vag-glans"></div>' +
      '<div class="ca-vag-linje"></div>' +
      '<div class="ca-vag-kant"></div>' +
      '<span class="ca-vag-fart" style="left:calc(44% - 6px);width:16px;animation-delay:0s"></span>' +
      '<span class="ca-vag-fart" style="left:calc(44% - 2px);width:11px;bottom:51px;animation-delay:.35s"></span>' +
      '<span class="ca-vag-fart" style="left:calc(44% - 9px);width:14px;bottom:42px;animation-delay:.62s"></span>' +
      '<div class="ca-vag-ljus"></div>' +
      // Sportbilen ritas EFTER den lila bilen sa den passerar framfor den.
      '<span class="ca-vag-sport-strimma"></span>' +
      '<svg class="ca-vag-sport" viewBox="0 0 53 19" xmlns="http://www.w3.org/2000/svg">' +
        '<defs><linearGradient id="ca-vag-rod" x1="0" y1="0" x2="0" y2="1">' +
          '<stop offset="0" stop-color="#fca5a5"/><stop offset="42%" stop-color="#ef4444"/>' +
          '<stop offset="100%" stop-color="#991b1b"/></linearGradient></defs>' +
        // Lag och lang kaross med kilformad nos at hoger
        '<path d="M1.5 14.5 L2.4 11.4 Q2.8 9.9 5 9.6 L16 8.4 L22 5.4 Q23.6 4.6 26 4.6 L33 4.6 Q35.4 4.6 36.8 5.8 L40.5 8.9 L48.5 10.2 Q51.5 10.7 51.5 13 L51.5 14.5 Z" fill="url(#ca-vag-rod)"/>' +
        '<path d="M19.5 8.6 L23.4 6.1 Q24.2 5.7 25.6 5.7 L28.6 5.7 L28.6 8.6 Z" fill="#1e293b" opacity=".85"/>' +
        '<path d="M30 5.7 L32.8 5.7 Q34.2 5.7 35 6.5 L36.9 8.6 L30 8.6 Z" fill="#1e293b" opacity=".85"/>' +
        // Sidostrimma i Ferraris gula, och stralkastare
        '<rect x="6" y="11.6" width="12" height="1.1" rx=".5" fill="#fde047" opacity=".8"/>' +
        '<rect x="49.2" y="11" width="2.3" height="2" rx="1" fill="#fef3c7"/>' +
        '<g class="ca-vag-hjul"><circle cx="13.5" cy="14.2" r="3.7" fill="#0f172a"/>' +
          '<circle cx="13.5" cy="14.2" r="1.5" fill="#e2e8f0"/></g>' +
        '<g class="ca-vag-hjul"><circle cx="40" cy="14.2" r="3.7" fill="#0f172a"/>' +
          '<circle cx="40" cy="14.2" r="1.5" fill="#e2e8f0"/></g>' +
      '</svg>' +
      '<svg class="ca-vag-bil" viewBox="0 0 46 21" xmlns="http://www.w3.org/2000/svg">' +
        '<defs>' +
          '<linearGradient id="ca-vag-lack" x1="0" y1="0" x2="0" y2="1">' +
            '<stop offset="0" stop-color="#c4b5fd"/><stop offset="55%" stop-color="#8b5cf6"/>' +
            '<stop offset="100%" stop-color="#5b21b6"/></linearGradient>' +
        '</defs>' +
        // Kaross: nos åt höger, kupé i mitten, bakparti något högre
        '<path d="M2.5 15.5 L3.6 10.6 Q4 8.9 6 8.6 L13.5 7.6 L18.5 3.9 Q19.8 3 21.8 3 L28.5 3 ' +
              'Q30.8 3 32 4.4 L35.6 8.4 L40.8 9.4 Q43.5 9.9 43.5 12.6 L43.5 15.5 Z" ' +
              'fill="url(#ca-vag-lack)"/>' +
        // Rutor
        '<path d="M15.8 7.8 L19.8 4.9 Q20.6 4.4 21.8 4.4 L24.2 4.4 L24.2 8.1 Z" fill="#bae6fd" opacity=".9"/>' +
        '<path d="M25.6 4.4 L28.4 4.4 Q29.9 4.4 30.7 5.3 L33.2 8.1 L25.6 8.1 Z" fill="#bae6fd" opacity=".9"/>' +
        // Strålkastare
        '<rect x="41.4" y="10.6" width="2.2" height="2.4" rx="1" fill="#fde68a"/>' +
        // Hjulhus + hjul med eker så rotationen syns
        '<g class="ca-vag-hjul"><circle cx="12.5" cy="15.4" r="4.1" fill="#1f1830"/>' +
          '<circle cx="12.5" cy="15.4" r="1.7" fill="#cbd5e1"/>' +
          '<rect x="12.1" y="11.9" width=".8" height="7" fill="#94a3b8" opacity=".85"/></g>' +
        '<g class="ca-vag-hjul"><circle cx="33.5" cy="15.4" r="4.1" fill="#1f1830"/>' +
          '<circle cx="33.5" cy="15.4" r="1.7" fill="#cbd5e1"/>' +
          '<rect x="33.1" y="11.9" width=".8" height="7" fill="#94a3b8" opacity=".85"/></g>' +
      '</svg>' +
      '<div class="ca-vag-solsken"></div>';
    rad.appendChild(vag);
  } catch (e) {
    try { console.warn('CarAdvice: vägen vid rubriken kunde inte byggas', e); } catch (x) {}
  }
}

// ── Litet liv i knapparna ────────────────────────────────────────────────────
// Kategorichipsen stod helt still tills man förde muspekaren över dem. Nu andas ikonerna
// och den valda kategorin glöder långsamt.
//
// Förskjutna starter (nth-child) med flit: rör sig alla i takt läses det som ett fel i
// renderingen snarare än som liv. Samma skäl som de tre kortens färgkanter fick olika
// startlägen.
//
// Bara transform, opacity och box-shadow — de kostar ingen omritning av layouten. Ytorna är
// dessutom små, till skillnad från heron där en animerad filter:hue-rotate mätte 31,4 mot
// 43,9 fps och därför togs bort.
(function caRorelseCss() {
  if (document.getElementById('ca-rorelse-css')) return;
  var s = document.createElement('style');
  s.id = 'ca-rorelse-css';
  var d = '';
  // Ikonerna: en kategori i taget, var och en en bit in i cykeln
  for (var i = 1; i <= 6; i++) {
    d += '.ca-chips .ca-chip:nth-child(' + i + ') .ca-chip-ikon{animation-delay:-' + (i * 0.62).toFixed(2) + 's;}';
  }
  s.textContent = [
    '@keyframes ca-ikon-liv{0%,100%{transform:translateY(0) rotate(0deg)}',
      '50%{transform:translateY(-1.6px) rotate(-4deg)}}',
    '@keyframes ca-chip-glod{0%,100%{box-shadow:0 0 0 1px rgba(167,139,250,.3),0 4px 16px -4px rgba(139,92,246,.55)}',
      '50%{box-shadow:0 0 0 1px rgba(167,139,250,.6),0 7px 26px -3px rgba(139,92,246,.95)}}',
    '.ca-chip-ikon{animation:ca-ikon-liv 3.4s ease-in-out infinite;}',
    // animation:none och inte play-state:paused — en pausad animation fortsätter skriva sitt
    // värde och vinner då över hover-transformen, som därmed aldrig syntes.
    '.ca-chip:hover .ca-chip-ikon{animation:none;transform:scale(1.12);}',
    '.ca-chip-aktiv{animation:ca-chip-glod 2.8s ease-in-out infinite;}',
    d,
    '@media(prefers-reduced-motion:reduce){.ca-chip-ikon,.ca-chip-aktiv{animation:none!important;}}'
  ].join('');
  (document.body || document.documentElement).appendChild(s);
})();

function caInit() {
  window._caFns = {
    recommend: caGetRecommendation,
    share: caShareSearch,
    reset: caResetForm,
    copy: caCopyResult,
    history: caLoadFromHistory,
    delHistory: caDeleteHistory
  };

  caByggVag();
  caGroqBadge();
  caResultatradIhop();
  caGomUndanSmaval();
  caRullaTillAppen();
  caHopfallbar(document.getElementById('ca-freecompare'),
    'Jämför bilar fritt', 'två bilar mot varandra', 'jamfor');
  caSvepVidSyn();
  caUpdateSliderFill();
  // Injiceras FÖRE caLoadPrefs — annars finns inte reglaget när det sparade värdet ska sättas
  caEnsureCargoField();
  caFixCategoryLabels();
  // Före caLoadPrefs: sätter åldersförvalet och byter ut ny/begagnad-rutan mot notisen,
  // så att en sparad inställning och en delningslänk fortfarande vinner över förvalet.
  caAnpassaBegagnatFormular();
  caForvalKorstracka();
  // Före caFlerVal: knappraderna ska med när fälten flyttas, inte lämnas kvar.
  ['ca-category', 'ca-charger', 'ca-fuel', 'ca-transmission'].forEach(caChips);
  // Efter caEnsureCargoField och förvalen: rutorna måste finnas OCH vara ifyllda innan de
  // flyttas, annars fälls tomma fält ihop.
  caFlerVal();
  // Efter caFlerVal: raden ska ligga överst i formuläret, och caFlerVal flyttar fält mellan
  // rutnäten. Egen klass och inget .ca-grid, så den aldrig plockas in i "Fler val"-lådan.
  caLoadPrefs();
  caReadUrlParams();
  // Efter att kategori och drivmedel återställts, aldrig före: fältet byggdes med förvalen och
  // hade annars visat bensinbilar som ankare för en sparad elbilssökning ända tills användaren
  // rörde en ruta. Samma ordningsfälla som budgetrutans nivåstege haft.
  caRenderCargoLevels();
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

  /**
   * Binder en knapp EN gång — och tar bort markupens onclick när den gör det.
   *
   * <p>Fem knappar bär både `onclick="window._ca(...)"` i snippeten och en lyssnare härifrån.
   * Båda vägarna leder till samma funktion, så ett klick körde den TVÅ gånger. För sökknappen
   * betyder det två fulla /api/recommend — två Groq-kedjor och två avdrag från timkvoten — per
   * tryckning. Uppmätt i webbläsare mot den riktiga snippeten 2026-08-29: ett klick, ett
   * funktionsanrop räknat på window._caFns-vägen, men TVÅ nätverksanrop.
   *
   * <p><b>Om det slog igenom i drift vet vi inte säkert</b>, och det är hela poängen: WordPress
   * CSP kan blockera inline-attribut, och gör den det fyrade bara lyssnaren. Beteendet hängde
   * alltså på värdsidans policy i stället för på vår kod, och lättas policyn någon gång
   * fördubblas varje sökning tyst.
   *
   * <p><b>Attributet är ändå kvar som reserv där reserven behövs.</b> Går main.js inte att ladda
   * körs den här raden aldrig, attributet står orört och knappen fungerar via `window._ca`.
   * Vi tar bara bort det i det läge där vi bevisligen har ersatt det.
   *
   * <p>`history` och `delHistory` i `_caFns` har inga lyssnare — de anropas från onclick i
   * dynamiskt renderad HTML och rörs inte.
   */
  function caBindEl(id, fn) {
    var el = document.getElementById(id);
    if (!el) return;
    el.removeAttribute('onclick');
    el.addEventListener('click', fn);
  }
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
