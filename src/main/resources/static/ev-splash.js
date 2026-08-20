// EV Laddningsassistent — uppstartssplash (el-tema, Groq-driven boot-sekvens)
// Extern fil (WordPress blockerar inline <script>). Injiceras av ev-app.js så den
// live-inbäddade WP-sidan får den utan att markupen behöver klistras om.
// Lägger ett fullskärms-takeoverlager över appen: en pulserande laddningskärna (batteri
// som laddas), en typewriter-bootrad, och statusrader som tickar igenom appens datakällor
// och tänds gröna en efter en innan lagret tonar bort och appen visas.
//   • Groq AI       — språkmodellen som driver laddassistenten (/api/health)
//   • Elbilar       — antal modeller i ev_spec-databasen (LIVE /api/cars)
//   • Laddstationer — Open Charge Map realtidsdata
//   • Priser        — Chargeprice, aktuella kWh-priser
//   • Ruttplanering — laddstopp längs resan
//   • Laddtips      — smarta databastips
// Visas första besöket per webbläsare; tvinga fram med ?splash=1 eller window.evReplaySplash().
(function () {
  'use strict';

  var API = window.EV_API_URL || 'https://elbilsladdning.onrender.com';
  var SEEN_KEY = 'ev_splash_seen_v1';
  var CARS_KEY = 'ev_car_count';

  var CARS_FLOOR = 90; // siffran visas aldrig lägre än detta även innan /api/cars svarat
  var CARS_ROW = 2;    // raden som visar antal elbilar

  var FORCE = /[?&]splash=1/.test(location.search);

  // Alla datakällor appen faktiskt använder — de med tag:'ONLINE' får en pulsande grön pill.
  var ROWS = [
    { ic: '🤖', t: 'Groq AI',        s: 'Spr\xe5kmodell startad', tag: 'ONLINE' },
    { ic: '🗄️', t: 'PostgreSQL',     s: 'ev_spec-databasen ansluten', tag: 'ONLINE' },
    { ic: '🔋', t: 'Elbilar',        kind: 'cars' },
    { ic: '🔌', t: 'Laddstationer',  s: 'Open Charge Map \xb7 realtidsdata', tag: 'LIVE' },
    { ic: '💰', t: 'Laddpriser',     s: 'Chargeprice \xb7 aktuella kWh-priser' },
    { ic: '📊', t: 'S\xe4ljstatistik', s: 'Mest s\xe5lda elbilar i Sverige' },
    { ic: '🗺️', t: 'Ruttplanering',  s: 'Laddstopp l\xe4ngs din resa \xb7 OSRM' },
    { ic: '💡', t: 'Laddtips',       s: 'Smarta tips fr\xe5n databasen' }
  ];

  var BOOT_PHRASES = ['ansluter till Groq AI', 'l\xe4ser in laddstationer', 'kalibrerar laddeffekt &amp; priser', 'planerar optimala laddstopp'];

  function fmt(n) { return n.toLocaleString('sv-SE'); }
  function readInt(key) { try { return parseInt(localStorage.getItem(key), 10) || 0; } catch (e) { return 0; } }
  function clampFloor(live, floorVal) { return Math.max(floorVal, Math.floor((live || 0) / 5) * 5); }

  var targetCars = clampFloor(readInt(CARS_KEY), CARS_FLOOR);
  var animated = {};

  function carsText(e) {
    var n = Math.round(targetCars * e);
    var plus = e >= 1 ? '+' : '';
    return '<b>' + fmt(n) + plus + '</b> elbilsmodeller \xb7 specar &amp; laddeffekt';
  }

  function injectStyles() {
    if (document.getElementById('ev-splash-style')) return;
    var css = document.createElement('style');
    css.id = 'ev-splash-style';
    css.textContent = [
      '.ev-splash{position:fixed;inset:0;z-index:99999;overflow:hidden;',
        'display:flex;flex-direction:column;align-items:center;justify-content:center;',
        'padding:30px 22px;text-align:center;',
        "font-family:'Segoe UI',system-ui,-apple-system,BlinkMacSystemFont,Roboto,sans-serif;",
        'background:radial-gradient(ellipse at 50% 0%,#0d1b34,#060c1a 60%,#04060f 100%);',
        'opacity:1;transition:opacity .5s ease;}',
      '.ev-splash.ev-sp-out{opacity:0;}',
      '.ev-splash::before{content:"";position:absolute;inset:0;pointer-events:none;',
        'background:radial-gradient(ellipse at 74% 8%,rgba(34,197,94,.14) 0%,transparent 48%),',
        'radial-gradient(ellipse at 16% 92%,rgba(59,130,246,.18) 0%,transparent 46%);',
        'animation:ev-sp-aurora 7s ease-in-out infinite alternate;}',
      // elektrisk scanline som sveper uppifrån och ned
      '.ev-splash::after{content:"";position:absolute;left:0;right:0;height:2px;top:0;pointer-events:none;',
        'background:linear-gradient(90deg,transparent,rgba(96,165,250,.7),rgba(34,197,94,.7),transparent);',
        'box-shadow:0 0 18px rgba(59,130,246,.6);animation:ev-sp-scan 3.4s linear infinite;opacity:.7;}',
      '.ev-sp-inner{position:relative;z-index:1;width:100%;max-width:392px;',
        'display:flex;flex-direction:column;align-items:center;}',
      // Glaskort — frostat panel med djup glow som håller hela boot-sekvensen
      // OBS: topp/botten-glöden ligger som bakgrundslager (inte ::before) — ett absolut­positionerat
      // ::before målas ovanpå den statiska texten och lägger en slöja som tvättar ur den.
      '.ev-sp-card{position:relative;width:100%;padding:30px 22px 24px;border-radius:26px;',
        'display:flex;flex-direction:column;align-items:center;',
        'background:radial-gradient(120% 55% at 50% -8%,rgba(96,165,250,.28),transparent 68%),',
          'radial-gradient(100% 45% at 50% 108%,rgba(34,197,94,.15),transparent 70%),',
          'linear-gradient(160deg,rgba(19,33,60,.92),rgba(8,15,30,.95));',
        '-webkit-backdrop-filter:blur(24px) saturate(150%);backdrop-filter:blur(24px) saturate(150%);',
        'border:1px solid rgba(147,197,253,.22);',
        'box-shadow:0 30px 80px rgba(0,0,0,.6),inset 0 1px 0 rgba(255,255,255,.18),',
          'inset 0 0 50px rgba(59,130,246,.08),0 0 70px rgba(34,197,94,.18),0 0 120px rgba(37,99,235,.12);',
        'animation:ev-sp-rise .55s ease both;}',
      // Laddningskärna (batteri + blixt)
      '.ev-sp-core{position:relative;width:96px;height:96px;margin-bottom:16px;',
        'display:flex;align-items:center;justify-content:center;animation:ev-sp-rise .5s ease both;}',
      '.ev-sp-ring{position:absolute;inset:0;border-radius:50%;',
        'background:conic-gradient(from 0deg,rgba(34,197,94,0),#22c55e 16%,#3b82f6 50%,#818cf8 76%,rgba(129,140,248,0));',
        '-webkit-mask:radial-gradient(farthest-side,transparent calc(100% - 4px),#000 calc(100% - 3px));',
        'mask:radial-gradient(farthest-side,transparent calc(100% - 4px),#000 calc(100% - 3px));',
        'filter:drop-shadow(0 0 8px rgba(59,130,246,.6));animation:ev-sp-rot 1.5s linear infinite;}',
      '.ev-sp-pulse{position:absolute;inset:6px;border-radius:50%;border:1.5px solid rgba(59,130,246,.45);',
        'animation:ev-sp-pulse 2.1s ease-out infinite;}',
      '.ev-sp-pulse.p2{animation-delay:1.05s;border-color:rgba(34,197,94,.4);}',
      '.ev-sp-node{position:relative;width:62px;height:62px;border-radius:19px;',
        'background:linear-gradient(145deg,rgba(23,44,74,.9),rgba(21,58,86,.85));',
        '-webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px);',
        'border:1px solid rgba(147,197,253,.4);',
        'display:flex;align-items:center;justify-content:center;',
        'box-shadow:0 8px 30px rgba(37,99,235,.55),0 0 34px rgba(34,197,94,.35),',
          'inset 0 1px 0 rgba(255,255,255,.25),inset 0 0 18px rgba(59,130,246,.25);}',
      '.ev-sp-node svg{filter:drop-shadow(0 0 6px rgba(74,222,128,.7));}',
      '.ev-sp-bolt{position:absolute;top:-5px;right:-5px;width:24px;height:24px;border-radius:50%;',
        'background:#04101c;border:1px solid rgba(34,197,94,.6);display:flex;align-items:center;justify-content:center;',
        'box-shadow:0 0 12px rgba(34,197,94,.65);animation:ev-sp-boltpulse 1.5s ease-in-out infinite;}',
      '.ev-sp-bolt svg{width:12px;height:12px;fill:#4ade80;}',
      // Titel + chip
      '.ev-sp-title{font-size:1.35rem;font-weight:800;letter-spacing:-.4px;margin:0 0 8px;',
        'background:linear-gradient(120deg,#fff 36%,#93c5fd 100%);',
        '-webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;',
        'filter:drop-shadow(0 1px 8px rgba(96,165,250,.55));',
        'animation:ev-sp-rise .5s ease .05s both;}',
      '.ev-sp-chip{display:inline-flex;align-items:center;gap:5px;margin:0 0 14px;padding:3px 11px;',
        'border-radius:20px;background:rgba(34,197,94,.1);border:1px solid rgba(34,197,94,.4);',
        'font-size:.6rem;font-weight:800;letter-spacing:.16em;text-transform:uppercase;color:#86efac;',
        'animation:ev-sp-rise .5s ease .08s both;}',
      '.ev-sp-chip svg{width:11px;height:11px;fill:#4ade80;}',
      // Typewriter-bootrad
      '.ev-sp-boot{font-family:ui-monospace,SFMono-Regular,"Cascadia Code",Consolas,monospace;',
        'font-size:.74rem;color:rgba(191,219,254,.85);margin:0 0 20px;min-height:1.2em;',
        'letter-spacing:.2px;animation:ev-sp-rise .5s ease .1s both;}',
      '.ev-sp-boot .pr{color:#4ade80;font-weight:700;margin-right:5px;}',
      '.ev-sp-cur{display:inline-block;width:7px;height:.95em;background:#4ade80;margin-left:3px;',
        'vertical-align:-1px;animation:ev-sp-blink 1s steps(1) infinite;}',
      // Rader (glasmorf)
      '.ev-sp-rows{width:100%;display:flex;flex-direction:column;gap:7px;}',
      '.ev-sp-row{display:flex;align-items:center;gap:11px;text-align:left;padding:9px 12px;border-radius:13px;',
        'background:rgba(147,197,253,.08);border:1px solid rgba(147,197,253,.2);',
        'box-shadow:inset 0 1px 0 rgba(255,255,255,.1);',
        'opacity:0;transform:translateY(8px);transition:opacity .35s ease,transform .35s ease,border-color .3s,background .3s,box-shadow .3s;}',
      '.ev-sp-row.show{opacity:1;transform:translateY(0);}',
      '.ev-sp-row.done{border-color:rgba(52,211,153,.5);background:rgba(34,197,94,.14);',
        'box-shadow:inset 0 1px 0 rgba(255,255,255,.12),0 0 22px rgba(34,197,94,.22);}',
      '.ev-sp-ic{font-size:1.05rem;flex-shrink:0;width:22px;text-align:center;filter:drop-shadow(0 0 5px rgba(59,130,246,.4));}',
      '.ev-sp-tx{flex:1;min-width:0;display:flex;flex-direction:column;line-height:1.25;}',
      '.ev-sp-tx b{font-size:.83rem;font-weight:700;color:#f4f8ff;display:flex;align-items:center;gap:7px;}',
      '.ev-sp-tx i{font-size:.69rem;font-style:normal;color:rgba(191,219,254,.82);',
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
      '.ev-sp-tx i b{color:#7dd3fc;font-weight:800;font-style:normal;display:inline;}',
      // "online"-pill med pulsande grön prick
      '.ev-sp-onl{display:inline-flex;align-items:center;gap:4px;padding:1px 7px 1px 5px;border-radius:20px;',
        'font-size:.52rem;font-weight:800;letter-spacing:.1em;text-transform:uppercase;',
        'color:#6ee7b7;background:rgba(34,197,94,.14);border:1px solid rgba(34,197,94,.4);',
        'box-shadow:0 0 10px rgba(34,197,94,.25);}',
      '.ev-sp-onl.live{color:#7dd3fc;background:rgba(59,130,246,.14);border-color:rgba(59,130,246,.4);box-shadow:0 0 10px rgba(59,130,246,.25);}',
      '.ev-sp-onl .dot{width:5px;height:5px;border-radius:50%;background:currentColor;',
        'box-shadow:0 0 6px currentColor;animation:ev-sp-onlpulse 1.4s ease-in-out infinite;}',
      '.ev-sp-st{flex-shrink:0;width:20px;height:20px;display:flex;align-items:center;justify-content:center;}',
      '.ev-sp-spin{width:14px;height:14px;border-radius:50%;',
        'border:2px solid rgba(59,130,246,.2);border-top-color:#60a5fa;animation:ev-sp-spin .6s linear infinite;}',
      '.ev-sp-check{width:19px;height:19px;border-radius:50%;background:rgba(34,197,94,.18);',
        'border:1px solid rgba(34,197,94,.55);color:#4ade80;font-size:11px;font-weight:900;',
        'display:flex;align-items:center;justify-content:center;animation:ev-sp-pop .3s ease;}',
      // Batteri-laddstapel (progress)
      '.ev-sp-batt{position:relative;width:100%;height:12px;border-radius:4px;margin-top:20px;',
        'border:1.5px solid rgba(96,165,250,.45);background:rgba(255,255,255,.05);overflow:hidden;}',
      '.ev-sp-batt::after{content:"";position:absolute;right:-6px;top:50%;transform:translateY(-50%);',
        'width:4px;height:6px;border-radius:0 2px 2px 0;background:rgba(96,165,250,.6);}',
      '.ev-sp-fill{height:100%;width:0;border-radius:2px;',
        'background:linear-gradient(90deg,#3b82f6,#22c55e);transition:width .55s ease;',
        'box-shadow:0 0 12px rgba(34,197,94,.6);position:relative;}',
      // laddglimt som far över stapeln
      '.ev-sp-fill::after{content:"";position:absolute;inset:0;',
        'background:linear-gradient(90deg,transparent,rgba(255,255,255,.45),transparent);',
        'animation:ev-sp-shine 1.4s linear infinite;}',
      '.ev-sp-pct{margin-top:9px;font-size:.66rem;font-weight:700;letter-spacing:.08em;',
        'color:rgba(147,197,253,.7);font-family:ui-monospace,Consolas,monospace;}',
      // "Boot complete"-flärt
      '.ev-splash.ev-sp-ready .ev-sp-node{box-shadow:0 6px 30px rgba(37,99,235,.75),0 0 36px rgba(34,197,94,.6);animation:ev-sp-charge .6s ease;}',
      '.ev-splash.ev-sp-ready .ev-sp-ring{animation-duration:.5s;}',
      '.ev-splash.ev-sp-ready .ev-sp-boot{color:#6ee7b7;}',
      '.ev-splash.ev-sp-ready .ev-sp-boot .pr{color:#22c55e;}',
      '.ev-splash.ev-sp-ready .ev-sp-fill{background:linear-gradient(90deg,#22c55e,#4ade80);box-shadow:0 0 16px rgba(34,197,94,.7);}',
      '.ev-splash.ev-sp-ready .ev-sp-pct{color:#6ee7b7;}',
      // Skip
      '.ev-splash-skip{position:absolute;top:14px;right:16px;z-index:2;background:rgba(255,255,255,.07);',
        'border:1px solid rgba(255,255,255,.14);color:rgba(255,255,255,.6);font-size:.68rem;font-weight:600;',
        'padding:4px 11px;border-radius:20px;cursor:pointer;transition:all .15s;',
        "font-family:inherit;letter-spacing:.02em;}",
      '.ev-splash-skip:hover{background:rgba(255,255,255,.15);color:#fff;}',
      // Keyframes
      '@keyframes ev-sp-spin{to{transform:rotate(360deg);}}',
      '@keyframes ev-sp-rot{to{transform:rotate(360deg);}}',
      '@keyframes ev-sp-rise{from{opacity:0;transform:translateY(10px);}to{opacity:1;transform:translateY(0);}}',
      '@keyframes ev-sp-pulse{0%{transform:scale(.72);opacity:.65;}100%{transform:scale(1.35);opacity:0;}}',
      '@keyframes ev-sp-boltpulse{0%,100%{box-shadow:0 0 10px rgba(34,197,94,.45);}50%{box-shadow:0 0 18px rgba(34,197,94,.9);}}',
      '@keyframes ev-sp-charge{0%{transform:scale(1);}45%{transform:scale(1.15);}100%{transform:scale(1);}}',
      '@keyframes ev-sp-aurora{0%{opacity:.7;}100%{opacity:1;}}',
      '@keyframes ev-sp-blink{0%,100%{opacity:1;}50%{opacity:0;}}',
      '@keyframes ev-sp-pop{0%{transform:scale(.4);opacity:0;}60%{transform:scale(1.15);}100%{transform:scale(1);opacity:1;}}',
      '@keyframes ev-sp-scan{0%{top:-2px;opacity:0;}12%{opacity:.7;}88%{opacity:.7;}100%{top:100%;opacity:0;}}',
      '@keyframes ev-sp-shine{0%{transform:translateX(-100%);}100%{transform:translateX(100%);}}',
      '@keyframes ev-sp-onlpulse{0%,100%{opacity:1;transform:scale(1);}50%{opacity:.45;transform:scale(1.35);}}',
      // Mobil
      '@media (max-width:520px){',
        '.ev-splash{justify-content:flex-start;padding:34px 12px 20px;}',
        '.ev-sp-card{padding:24px 15px 20px;border-radius:22px;}',
        '.ev-sp-title{font-size:1.2rem;}',
        '.ev-sp-core{width:78px;height:78px;margin-bottom:12px;}',
        '.ev-sp-node{width:54px;height:54px;border-radius:16px;}',
        '.ev-sp-chip{margin-bottom:12px;}',
        '.ev-sp-boot{margin-bottom:15px;font-size:.72rem;}',
        '.ev-sp-rows{gap:7px;}',
        '.ev-sp-row{padding:8px 12px;gap:10px;}',
        '.ev-sp-tx b{font-size:.8rem;}.ev-sp-tx i{font-size:.67rem;}',
        '.ev-sp-batt{margin-top:15px;}',
      '}',
      '@media (prefers-reduced-motion:reduce){',
        '.ev-splash *{animation:none!important;transition:none!important;}',
        '.ev-splash::after{display:none;}}'
    ].join('');
    document.head.appendChild(css);
  }

  var BATT_SVG =
    '<svg viewBox="0 0 40 40" width="30" height="30" xmlns="http://www.w3.org/2000/svg">' +
      '<rect x="7" y="11" width="24" height="20" rx="4" fill="rgba(255,255,255,0.08)" stroke="rgba(147,197,253,0.55)" stroke-width="1.6"/>' +
      '<rect x="16" y="7" width="8" height="4" rx="1.5" fill="rgba(147,197,253,0.55)"/>' +
      '<path d="M21 14 L15 23 H19 L18 28 L25 19 H21 L21 14 Z" fill="#4ade80" stroke="#22c55e" stroke-width="0.6"/>' +
    '</svg>';

  var BOLT_SVG =
    '<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M13 2L4.5 13.5H11L10 22L19.5 10.5H13L13 2Z"/></svg>';

  function subFor(row) {
    if (row.kind === 'cars') return 'L\xe4ser elbilsdatabasen…';
    return row.s;
  }

  function tagHtml(tag) {
    if (!tag) return '';
    var cls = tag === 'LIVE' ? 'ev-sp-onl live' : 'ev-sp-onl';
    return '<span class="' + cls + '"><span class="dot"></span>' + tag + '</span>';
  }

  function rowsHtml() {
    return ROWS.map(function (r, i) {
      return '<div class="ev-sp-row" data-i="' + i + '">' +
        '<span class="ev-sp-ic">' + r.ic + '</span>' +
        '<span class="ev-sp-tx"><b>' + r.t + tagHtml(r.tag) + '</b><i class="ev-sp-suba">' + subFor(r) + '</i></span>' +
        '<span class="ev-sp-st"><span class="ev-sp-spin"></span></span>' +
      '</div>';
    }).join('');
  }

  function template() {
    return '' +
      '<button class="ev-splash-skip" type="button" aria-label="Hoppa \xf6ver">Hoppa \xf6ver ✕</button>' +
      '<div class="ev-sp-inner">' +
        '<div class="ev-sp-card">' +
          '<div class="ev-sp-core">' +
            '<span class="ev-sp-ring"></span>' +
            '<span class="ev-sp-pulse"></span><span class="ev-sp-pulse p2"></span>' +
            '<span class="ev-sp-node">' + BATT_SVG + '<span class="ev-sp-bolt">' + BOLT_SVG + '</span></span>' +
          '</div>' +
          '<h3 class="ev-sp-title">EV Laddningsassistent</h3>' +
          '<span class="ev-sp-chip">' + BOLT_SVG + ' Powered by Groq AI</span>' +
          '<p class="ev-sp-boot"><span class="pr">▸</span><span class="ev-sp-boot-tx"></span><span class="ev-sp-cur"></span></p>' +
          '<div class="ev-sp-rows">' + rowsHtml() + '</div>' +
          '<div class="ev-sp-batt"><div class="ev-sp-fill"></div></div>' +
          '<div class="ev-sp-pct">0% laddat</div>' +
        '</div>' +
      '</div>';
  }

  function suba(i) { return document.querySelector('.ev-sp-row[data-i="' + i + '"] .ev-sp-suba'); }

  function animate(el, dur, render) {
    var start = performance.now();
    (function step(now) {
      var p = Math.min(1, (now - start) / dur);
      el.innerHTML = render(1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(step);
    })(performance.now());
  }

  function animateCars() {
    if (animated.cars) return;
    var el = suba(CARS_ROW);
    if (!el) return;
    animated.cars = true;
    animate(el, 1400, function (e) { return carsText(e); });
  }
  function refreshCars() {
    if (!animated.cars) return;
    var el = suba(CARS_ROW);
    if (el) el.innerHTML = carsText(1);
  }

  function fetchStats() {
    fetch(API + '/api/cars')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.length) return;
        targetCars = clampFloor(d.length, CARS_FLOOR);
        try { localStorage.setItem(CARS_KEY, String(d.length)); } catch (e) {}
        refreshCars();
      })
      .catch(function () {});
  }

  function startBoot(el) {
    var pi = 0, ci = 0, mode = 'type', stopped = false;
    function set(txt) { el.innerHTML = txt; }
    function tick() {
      if (stopped) return;
      var phrase = BOOT_PHRASES[pi];
      if (mode === 'type') {
        ci++; set(phrase.slice(0, ci));
        if (ci >= phrase.length) { mode = 'hold'; ci = 0; setTimeout(tick, 900); return; }
        setTimeout(tick, 40);
      } else if (mode === 'hold') {
        mode = 'erase'; ci = phrase.length; setTimeout(tick, 30);
      } else {
        ci -= 2; if (ci < 0) ci = 0; set(phrase.slice(0, ci));
        if (ci <= 0) { pi = (pi + 1) % BOOT_PHRASES.length; mode = 'type'; }
        setTimeout(tick, 22);
      }
    }
    tick();
    return { stop: function (finalTxt) { stopped = true; set(finalTxt); } };
  }

  // Taket är inte förhandlingsbart: kommer datan aldrig måste splashen ändå släppa.
  // Men hur länge det är rimligt att vänta beror på VARFÖR vi väntar, och det vet vi:
  //
  // Svarade tjänsten snabbt på kallstartsproben och datan ändå dröjer, då är något
  // sönder längre bak — då hjälper ingen väntan och 9 s räcker gott.
  //
  // Är EV_COLD_START satt vaknar tjänsten, och det är en KÄND väntan med ett känt slut.
  // Uppmätta uppvakningar: 13,3 s när regeln skrevs, 73 s senare samma dag, och 121 s vid
  // en mätning mot en verkligt nedspunnen tjänst 2026-08-20. 75 s täcker de två första.
  //
  // Att sikta på det tredje vore fel: en splash som ligger kvar i två minuter är sämre än
  // att släppa, och ingen splashlängd är rätt svar på en tjänst som tar så lång tid att
  // vakna. Väntan som blir kvar efter taket bärs av gränssnittet i stället — rullgardinen
  // säger att tjänsten startar, se bilVantetext() i ev-app.js.
  var MAX_HALL_VARM_MS = 9000;
  var MAX_HALL_KALL_MS = 75000;

  function narDataFinns(cb) {
    if (window.EV_DATA_READY) return cb();
    var tak_ms = window.EV_COLD_START ? MAX_HALL_KALL_MS : MAX_HALL_VARM_MS;
    var gjort = false;
    function ga() {
      if (gjort) return;
      gjort = true;
      clearTimeout(tak);
      window.removeEventListener('ev-data-ready', ga);
      cb();
    }
    var tak = setTimeout(ga, tak_ms);
    window.addEventListener('ev-data-ready', ga);
  }

  // Animationen fyller hit, inte hela vägen. De sista åtta procenten är "appen fick sin
  // data" och sätts av finish(). Stapeln stod förr på 100 % under hela väntan, alltså
  // läste kortet som klart medan det just då var det enda som inte var det.
  var ANIM_PCT = 92;

  function markSeen() { try { localStorage.setItem(SEEN_KEY, '1'); } catch (e) {} }
  function setPct(el, p) { if (el) el.textContent = Math.round(p) + '% laddat'; }

  function run() {
    if (document.querySelector('.ev-splash')) return;
    injectStyles();

    var overlay = document.createElement('div');
    overlay.className = 'ev-splash';
    overlay.innerHTML = template();
    document.body.appendChild(overlay);
    // Lås scroll medan splashen tar över
    var prevOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';

    var fill    = overlay.querySelector('.ev-sp-fill');
    var pctEl   = overlay.querySelector('.ev-sp-pct');
    var bootTx  = overlay.querySelector('.ev-sp-boot-tx');
    var cursor  = overlay.querySelector('.ev-sp-cur');
    var rows    = overlay.querySelectorAll('.ev-sp-row');
    var timers  = [];
    var finished = false;
    var reduce  = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var boot    = reduce ? null : startBoot(bootTx);

    function finish() {
      if (finished) return;
      finished = true;
      timers.forEach(clearTimeout);
      overlay.classList.add('ev-sp-ready');
      if (boot) boot.stop('fulladdad — kör igång ✓');
      else if (bootTx) bootTx.textContent = 'redo — k\xf6r ig\xe5ng ✓';
      if (cursor) cursor.style.display = 'none';
      if (fill) fill.style.width = '100%';
      setPct(pctEl, 100);
      document.documentElement.style.overflow = prevOverflow;
      timers.push(setTimeout(function () {
        overlay.classList.add('ev-sp-out');
        setTimeout(function () { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }, 540);
      }, 800));
      markSeen();
    }

    // Animationen klar är inte samma sak som appen klar. Skip-knappen går medvetet förbi
    // det här — besökaren ska alltid kunna ta sig ur, även mitt i en väntan.
    function slutfor() {
      if (window.EV_DATA_READY) return finish();
      // Vet vi varför det dröjer ska det stå. "väntar på datan" när tjänsten sover säger
      // inget om att väntan har ett slut — och den kan bli lång, se taket ovan.
      var txt = window.EV_COLD_START ? 'tjänsten vaknar — det kan ta en stund…' : 'väntar på datan…';
      if (boot) boot.stop(txt);
      else if (bootTx) bootTx.textContent = txt;
      narDataFinns(finish);
    }

    overlay.querySelector('.ev-splash-skip').addEventListener('click', finish);
    fetchStats();

    if (reduce) {
      if (bootTx) bootTx.textContent = 'redo — k\xf6r ig\xe5ng';
      if (cursor) cursor.style.display = 'none';
      rows.forEach(function (row) {
        row.classList.add('show', 'done');
        row.querySelector('.ev-sp-st').innerHTML = '<span class="ev-sp-check">✓</span>';
      });
      animated.cars = true;
      var cEl = suba(CARS_ROW); if (cEl) cEl.innerHTML = carsText(1);
      if (fill) fill.style.width = ANIM_PCT + '%';
      setPct(pctEl, ANIM_PCT);
      timers.push(setTimeout(slutfor, 2200));
      return;
    }

    // ~5,5 s total: rader tickar in (laddkänsla), sen "fulladdad"-flärt
    var START = 400, STAGGER = 440, FLIP = 340;
    rows.forEach(function (row, i) {
      var appear = START + i * STAGGER;
      timers.push(setTimeout(function () {
        row.classList.add('show');
        if (i === CARS_ROW) animateCars();
      }, appear));
      timers.push(setTimeout(function () {
        row.classList.add('done');
        row.querySelector('.ev-sp-st').innerHTML = '<span class="ev-sp-check">✓</span>';
        var pct = (i + 1) / rows.length * ANIM_PCT;
        if (fill) fill.style.width = Math.round(pct) + '%';
        setPct(pctEl, pct);
        if (i === rows.length - 1) timers.push(setTimeout(slutfor, 500));
      }, appear + FLIP));
    });
  }

  window.evReplaySplash = function () {
    var o = document.querySelector('.ev-splash');
    if (o && o.parentNode) o.parentNode.removeChild(o);
    animated = {};
    run();
  };

  function shouldShow() {
    if (FORCE) return true;
    // Kallstart trumfar "har redan setts". Datatjänsten somnar efter ~15 min, och utan
    // det här ser en aterkommande besokare en sida som laddat men inte fyllts — vilket
    // laser som ett fel, medan en splash laser som att nagot hander. Flaggan sätts av
    // kallstartsvakten i ev-app.js nar API:t inte svarat inom tidsgransen.
    if (window.EV_COLD_START) return true;
    try { return !localStorage.getItem(SEEN_KEY); } catch (e) { return true; }
  }

  function boot() { if (shouldShow()) run(); }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
