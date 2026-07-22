// BilRådgivning — uppstartssplash (AI-boot-sekvens, Groq-inspirerad)
// Extern fil (WordPress blockerar inline <script>). Lägger ett laddlager över #ca-hero:
// en pulserande AI-kärna + Groq-korall-accenter, en typewriter-bootrad, och statusrader
// som tickar igenom alla riktiga datakällor/scrapers innan lagret tonar bort.
//   • Bildata      — ev-database.org (elbilsspecar) + Bilweb (cargospecar) + ICE-förbrukning
//   • Elbilsdata   — ev-database.org (WLTP, batteri, laddeffekt)
//   • Säkerhet     — Euro NCAP + Folksam
//   • Expertdata   — Teknikens Värld, Vi Bilägare, car.info, Auto Motor & Sport m.fl.
//   • Marknadspris — Blocket (dagsaktuella priser)
// models/variants/insights hämtas LIVE från /api/stats (klampas mot golvvärdena nedan så
// siffran aldrig undersäljer, och cachas för direkt "aktuellt värde" nästa besök).
// Visas första besöket per webbläsare (gäst som inloggad); tvinga fram med ?splash=1 eller
// window.caReplaySplash().
(function () {
  'use strict';

  var API = window.CA_API_URL ||
            (typeof CA_API_BASE !== 'undefined' ? CA_API_BASE : 'https://caradvice.onrender.com');
  var SEEN_KEY     = 'ca_splash_seen_v1';
  var MODELS_KEY   = 'ca_model_count';
  var VARIANTS_KEY = 'ca_variant_count';
  var INS_KEY      = 'ca_insight_count';

  // Golvvärden — siffran visas aldrig lägre än detta även innan /api/stats svarat.
  var CAR_MODELS   = 670;
  var CAR_VARIANTS = 2200;

  var MODELS_ROW = 1; // bildatabasen (modeller + varianter)
  var INS_ROW    = 5; // expertdata (live insiktsantal)

  var FORCE = /[?&]splash=1/.test(location.search);

  var ROWS = [
    { ic: '🤖', t: 'Groq AI',        s: 'Startar spr\xe5kmodellen' },
    { ic: '🚗', t: 'Bildatabas',     kind: 'models' },
    { ic: '⚡',       t: 'Elbilsdata',     s: 'R\xe4ckvidd, batteri &amp; laddeffekt \xb7 ev-database.org' },
    { ic: '⛽',       t: 'F\xf6rbrukning', s: 'Verifierad l/mil &amp; kWh/mil' },
    { ic: '🛡️', t: 'S\xe4kerhet', s: 'Euro NCAP &amp; Folksam krocktester' },
    { ic: '📰', t: 'Expertdata',     kind: 'insights' },
    { ic: '🔵', t: 'Marknadspriser', s: 'Dagsaktuella priser fr\xe5n Blocket' }
  ];

  var BOOT_PHRASES = ['ansluter till Groq AI', 'l\xe4ser in bildata &amp; k\xe4llor', 'rankar 2\xa0200+ varianter', 'kalibrerar rekommendationsmotorn'];
  var INS_SRC = 'Teknikens V\xe4rld, Vi Bil\xe4gare, car.info…';

  function fmt(n) { return n.toLocaleString('sv-SE'); }
  function readInt(key) { try { return parseInt(localStorage.getItem(key), 10) || 0; } catch (e) { return 0; } }
  function clampFloor(live, floorVal) { return Math.max(floorVal, Math.floor((live || 0) / 10) * 10); }

  // Bästa kända mål (cache→golv), uppdateras när /api/stats svarar
  var targets = {
    models:   clampFloor(readInt(MODELS_KEY), CAR_MODELS),
    variants: clampFloor(readInt(VARIANTS_KEY), CAR_VARIANTS)
  };
  var cachedInsights = readInt(INS_KEY);
  var liveInsights   = 0;
  var animated = {};

  function modelsText(e, tm, tv) {
    var plus = e >= 1 ? '+' : '';
    return '<b>' + fmt(Math.round(tm * e)) + plus + '</b> bilmodeller \xb7 ' +
           '<b>' + fmt(Math.round(tv * e)) + plus + '</b> varianter';
  }
  function insightsText(n) {
    return n > 0 ? '<b>' + fmt(n) + '</b> insikter \xb7 ' + INS_SRC : INS_SRC;
  }

  function injectStyles() {
    if (document.getElementById('ca-splash-style')) return;
    var css = document.createElement('style');
    css.id = 'ca-splash-style';
    css.textContent = [
      '.ca-splash{position:absolute;inset:0;z-index:50;border-radius:inherit;overflow:hidden;',
        'display:flex;flex-direction:column;align-items:center;justify-content:center;',
        'padding:30px 22px;text-align:center;',
        "font-family:'Segoe UI',system-ui,-apple-system,BlinkMacSystemFont,Roboto,sans-serif;",
        'background:linear-gradient(135deg,#0f0c29,#241a45,#0f0c29);',
        'opacity:1;transition:opacity .5s ease;}',
      '.ca-splash.ca-sp-out{opacity:0;}',
      '.ca-splash::before{content:"";position:absolute;inset:0;pointer-events:none;',
        'background:radial-gradient(ellipse at 70% 10%,rgba(245,80,54,.14) 0%,transparent 50%),',
        'radial-gradient(ellipse at 18% 90%,rgba(124,58,237,.16) 0%,transparent 48%);',
        'animation:ca-sp-aurora 7s ease-in-out infinite alternate;}',
      '.ca-sp-inner{position:relative;z-index:1;width:100%;max-width:384px;',
        'display:flex;flex-direction:column;align-items:center;}',
      // AI-kärna
      '.ca-sp-core{position:relative;width:92px;height:92px;margin-bottom:16px;',
        'display:flex;align-items:center;justify-content:center;animation:ca-sp-rise .5s ease both;}',
      '.ca-sp-ring{position:absolute;inset:0;border-radius:50%;',
        'background:conic-gradient(from 0deg,rgba(245,80,54,0),#f55036 20%,#a855f7 55%,#7c3aed 75%,rgba(124,58,237,0));',
        '-webkit-mask:radial-gradient(farthest-side,transparent calc(100% - 3px),#000 calc(100% - 2px));',
        'mask:radial-gradient(farthest-side,transparent calc(100% - 3px),#000 calc(100% - 2px));',
        'animation:ca-sp-rot 1.5s linear infinite;}',
      '.ca-sp-pulse{position:absolute;inset:6px;border-radius:50%;border:1.5px solid rgba(245,80,54,.45);',
        'animation:ca-sp-pulse 2.1s ease-out infinite;}',
      '.ca-sp-pulse.p2{animation-delay:1.05s;border-color:rgba(167,139,250,.4);}',
      '.ca-sp-node{position:relative;width:60px;height:60px;border-radius:19px;',
        'background:linear-gradient(145deg,#241a4d,#3b2a72);border:1px solid rgba(167,139,250,.3);',
        'display:flex;align-items:center;justify-content:center;',
        'box-shadow:0 6px 24px rgba(109,40,217,.5),0 0 22px rgba(245,80,54,.28);}',
      '.ca-sp-bolt{position:absolute;top:-5px;right:-5px;width:23px;height:23px;border-radius:50%;',
        'background:#1c1030;border:1px solid rgba(245,80,54,.55);display:flex;align-items:center;justify-content:center;',
        'box-shadow:0 0 12px rgba(245,80,54,.6);animation:ca-sp-boltpulse 1.5s ease-in-out infinite;}',
      '.ca-sp-bolt svg{width:11px;height:11px;fill:#f87060;}',
      // Titel + chip
      '.ca-sp-title{font-size:1.35rem;font-weight:800;letter-spacing:-.4px;margin:0 0 8px;',
        'background:linear-gradient(120deg,#fff 38%,rgba(167,139,250,.92) 100%);',
        '-webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;',
        'animation:ca-sp-rise .5s ease .05s both;}',
      '.ca-sp-chip{display:inline-flex;align-items:center;gap:5px;margin:0 0 14px;padding:3px 11px;',
        'border-radius:20px;background:rgba(245,80,54,.1);border:1px solid rgba(245,80,54,.4);',
        'font-size:.6rem;font-weight:800;letter-spacing:.16em;text-transform:uppercase;color:#f9a58f;',
        'animation:ca-sp-rise .5s ease .08s both;}',
      '.ca-sp-chip svg{width:11px;height:11px;fill:#f87060;}',
      // Typewriter-bootrad
      '.ca-sp-boot{font-family:ui-monospace,SFMono-Regular,"Cascadia Code",Consolas,monospace;',
        'font-size:.74rem;color:rgba(233,213,255,.82);margin:0 0 20px;min-height:1.2em;',
        'letter-spacing:.2px;animation:ca-sp-rise .5s ease .1s both;}',
      '.ca-sp-boot .pr{color:#f87060;font-weight:700;margin-right:5px;}',
      '.ca-sp-cur{display:inline-block;width:7px;height:.95em;background:#f87060;margin-left:3px;',
        'vertical-align:-1px;animation:ca-sp-blink 1s steps(1) infinite;}',
      // Rader
      '.ca-sp-rows{width:100%;display:flex;flex-direction:column;gap:8px;}',
      '.ca-sp-row{display:flex;align-items:center;gap:11px;text-align:left;padding:9px 13px;border-radius:12px;',
        'background:rgba(255,255,255,.045);border:1px solid rgba(139,92,246,.16);',
        'opacity:0;transform:translateY(8px);transition:opacity .35s ease,transform .35s ease,border-color .3s,background .3s;}',
      '.ca-sp-row.show{opacity:1;transform:translateY(0);}',
      '.ca-sp-row.done{border-color:rgba(52,211,153,.3);background:rgba(52,211,153,.06);}',
      '.ca-sp-ic{font-size:1.05rem;flex-shrink:0;width:22px;text-align:center;}',
      '.ca-sp-tx{flex:1;min-width:0;display:flex;flex-direction:column;line-height:1.25;}',
      '.ca-sp-tx b{font-size:.83rem;font-weight:700;color:#f1e9ff;}',
      '.ca-sp-tx i{font-size:.69rem;font-style:normal;color:rgba(233,213,255,.62);',
        'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
      '.ca-sp-tx i b{color:#c4b5fd;font-weight:800;font-style:normal;}',
      '.ca-sp-st{flex-shrink:0;width:20px;height:20px;display:flex;align-items:center;justify-content:center;}',
      '.ca-sp-spin{width:14px;height:14px;border-radius:50%;',
        'border:2px solid rgba(245,80,54,.2);border-top-color:#f87060;animation:ca-sp-spin .6s linear infinite;}',
      '.ca-sp-check{width:19px;height:19px;border-radius:50%;background:rgba(52,211,153,.18);',
        'border:1px solid rgba(52,211,153,.55);color:#34d399;font-size:11px;font-weight:900;',
        'display:flex;align-items:center;justify-content:center;animation:ca-sp-pop .3s ease;}',
      // Progress (Groq korall → lila)
      '.ca-sp-bar{width:100%;height:3px;border-radius:3px;margin-top:18px;overflow:hidden;background:rgba(255,255,255,.1);}',
      '.ca-sp-fill{height:100%;width:0;border-radius:3px;',
        'background:linear-gradient(90deg,#f55036,#a855f7,#7c3aed);transition:width .55s ease;',
        'box-shadow:0 0 10px rgba(245,80,54,.5);}',
      // "Boot complete"-flärt
      '.ca-splash.ca-sp-ready .ca-sp-node{box-shadow:0 6px 30px rgba(109,40,217,.75),0 0 36px rgba(245,80,54,.6);animation:ca-sp-charge .6s ease;}',
      '.ca-splash.ca-sp-ready .ca-sp-ring{animation-duration:.5s;}',
      '.ca-splash.ca-sp-ready .ca-sp-boot{color:#6ee7b7;}',
      '.ca-splash.ca-sp-ready .ca-sp-boot .pr{color:#34d399;}',
      '.ca-splash.ca-sp-ready .ca-sp-fill{background:linear-gradient(90deg,#34d399,#a855f7,#7c3aed);box-shadow:0 0 16px rgba(52,211,153,.6);}',
      // Skip
      '.ca-splash-skip{position:absolute;top:12px;right:14px;z-index:2;background:rgba(255,255,255,.07);',
        'border:1px solid rgba(255,255,255,.14);color:rgba(255,255,255,.6);font-size:.68rem;font-weight:600;',
        'padding:4px 11px;border-radius:20px;cursor:pointer;transition:all .15s;',
        "font-family:inherit;letter-spacing:.02em;}",
      '.ca-splash-skip:hover{background:rgba(255,255,255,.15);color:#fff;}',
      // Keyframes
      '@keyframes ca-sp-spin{to{transform:rotate(360deg);}}',
      '@keyframes ca-sp-rot{to{transform:rotate(360deg);}}',
      '@keyframes ca-sp-rise{from{opacity:0;transform:translateY(10px);}to{opacity:1;transform:translateY(0);}}',
      '@keyframes ca-sp-pulse{0%{transform:scale(.72);opacity:.65;}100%{transform:scale(1.35);opacity:0;}}',
      '@keyframes ca-sp-boltpulse{0%,100%{box-shadow:0 0 10px rgba(245,80,54,.45);}50%{box-shadow:0 0 18px rgba(245,80,54,.85);}}',
      '@keyframes ca-sp-charge{0%{transform:scale(1);}45%{transform:scale(1.15);}100%{transform:scale(1);}}',
      '@keyframes ca-sp-aurora{0%{opacity:.7;}100%{opacity:1;}}',
      '@keyframes ca-sp-blink{0%,100%{opacity:1;}50%{opacity:0;}}',
      '@keyframes ca-sp-pop{0%{transform:scale(.4);opacity:0;}60%{transform:scale(1.15);}100%{transform:scale(1);opacity:1;}}',
      // Mobil: förankra innehållet i toppen (hero:n är hög pga formuläret → centrering hamnar
      // under fold), och kompaktare kärna/rader så allt syns i en skärmhöjd.
      '@media (max-width:520px){',
        '.ca-splash{justify-content:flex-start;padding:38px 13px 20px;}',
        '.ca-sp-title{font-size:1.2rem;}',
        '.ca-sp-core{width:74px;height:74px;margin-bottom:11px;}',
        '.ca-sp-node{width:52px;height:52px;border-radius:16px;}',
        '.ca-sp-chip{margin-bottom:11px;}',
        '.ca-sp-boot{margin-bottom:15px;font-size:.72rem;}',
        '.ca-sp-rows{gap:7px;}',
        '.ca-sp-row{padding:8px 12px;gap:10px;}',
        '.ca-sp-tx b{font-size:.8rem;}.ca-sp-tx i{font-size:.67rem;}',
        '.ca-sp-bar{margin-top:14px;}',
      '}',
      '@media (prefers-reduced-motion:reduce){',
        '.ca-splash *{animation:none!important;transition:none!important;}}'
    ].join('');
    document.head.appendChild(css);
  }

  var CAR_SVG =
    '<svg viewBox="0 0 52 40" width="34" height="26" xmlns="http://www.w3.org/2000/svg">' +
      '<rect x="4" y="18" width="44" height="14" rx="5" fill="rgba(255,255,255,0.15)" stroke="rgba(255,255,255,0.35)" stroke-width="1.2"/>' +
      '<path d="M14 18 Q18 8 22 7 L30 7 Q34 8 38 18Z" fill="rgba(255,255,255,0.2)" stroke="rgba(255,255,255,0.35)" stroke-width="1.2"/>' +
      '<path d="M16 18 Q19 10 22 9 L29 9 Q32 10 35 18Z" fill="rgba(196,181,253,0.25)"/>' +
      '<circle cx="14" cy="33" r="5.5" fill="#1e1b4b" stroke="rgba(167,139,250,0.6)" stroke-width="1.5"/>' +
      '<circle cx="14" cy="33" r="2.5" fill="rgba(167,139,250,0.5)"/>' +
      '<circle cx="38" cy="33" r="5.5" fill="#1e1b4b" stroke="rgba(167,139,250,0.6)" stroke-width="1.5"/>' +
      '<circle cx="38" cy="33" r="2.5" fill="rgba(167,139,250,0.5)"/>' +
      '<rect x="44" y="21" width="4" height="3" rx="1.5" fill="#fef08a"/>' +
    '</svg>';

  var GROQ_SVG =
    '<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M13 2L4.5 13.5H11L10 22L19.5 10.5H13L13 2Z"/></svg>';

  function subFor(row) {
    if (row.kind === 'models')   return 'L\xe4ser bildatabasen…';
    if (row.kind === 'insights') return insightsText(cachedInsights);
    return row.s;
  }

  function rowsHtml() {
    return ROWS.map(function (r, i) {
      return '<div class="ca-sp-row" data-i="' + i + '">' +
        '<span class="ca-sp-ic">' + r.ic + '</span>' +
        '<span class="ca-sp-tx"><b>' + r.t + '</b><i class="ca-sp-suba">' + subFor(r) + '</i></span>' +
        '<span class="ca-sp-st"><span class="ca-sp-spin"></span></span>' +
      '</div>';
    }).join('');
  }

  function template() {
    return '' +
      '<button class="ca-splash-skip" type="button" aria-label="Hoppa \xf6ver">Hoppa \xf6ver ✕</button>' +
      '<div class="ca-sp-inner">' +
        '<div class="ca-sp-core">' +
          '<span class="ca-sp-ring"></span>' +
          '<span class="ca-sp-pulse"></span><span class="ca-sp-pulse p2"></span>' +
          '<span class="ca-sp-node">' + CAR_SVG + '<span class="ca-sp-bolt">' + GROQ_SVG + '</span></span>' +
        '</div>' +
        '<h3 class="ca-sp-title">AI Bilr\xe5dgivning</h3>' +
        '<span class="ca-sp-chip">' + GROQ_SVG + ' Powered by Groq AI</span>' +
        '<p class="ca-sp-boot"><span class="pr">▸</span><span class="ca-sp-boot-tx"></span><span class="ca-sp-cur"></span></p>' +
        '<div class="ca-sp-rows">' + rowsHtml() + '</div>' +
        '<div class="ca-sp-bar"><div class="ca-sp-fill"></div></div>' +
      '</div>';
  }

  function suba(i) { return document.querySelector('.ca-sp-row[data-i="' + i + '"] .ca-sp-suba'); }

  function animate(el, dur, render) {
    var start = performance.now();
    (function step(now) {
      var p = Math.min(1, (now - start) / dur);
      el.innerHTML = render(1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(step);
    })(performance.now());
  }

  function animateModels() {
    if (animated.models) return;
    var el = suba(MODELS_ROW);
    if (!el) return;
    animated.models = true;
    var tm = targets.models, tv = targets.variants;
    animate(el, 1500, function (e) { return modelsText(e, tm, tv); });
  }
  function refreshModels() {
    if (!animated.models) return; // animeras strax med färska mål
    var el = suba(MODELS_ROW);
    if (el) el.innerHTML = modelsText(1, targets.models, targets.variants);
  }

  function animateInsights() {
    var n = liveInsights || cachedInsights || 0;
    var el = suba(INS_ROW);
    if (!el || !n) return;
    if (animated.insights) { el.innerHTML = insightsText(n); return; }
    animated.insights = true;
    var from = cachedInsights || 0;
    animate(el, 1200, function (e) { return insightsText(Math.round(from + (n - from) * e)); });
  }

  function fetchStats() {
    fetch(API + '/api/stats')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d) return;
        if (d.models > 0)   { targets.models   = clampFloor(d.models, CAR_MODELS);     try { localStorage.setItem(MODELS_KEY, String(d.models)); } catch (e) {} }
        if (d.variants > 0) { targets.variants = clampFloor(d.variants, CAR_VARIANTS); try { localStorage.setItem(VARIANTS_KEY, String(d.variants)); } catch (e) {} }
        refreshModels();
        if (d.insights > 0) { liveInsights = d.insights; try { localStorage.setItem(INS_KEY, String(d.insights)); } catch (e) {} animateInsights(); }
      })
      .catch(function () {});
  }

  // Typewriter-bootrad — cyklar BOOT_PHRASES tills finish() stoppar den
  function startBoot(el) {
    var pi = 0, ci = 0, mode = 'type';
    var stopped = false;
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

  function markSeen() { try { localStorage.setItem(SEEN_KEY, '1'); } catch (e) {} }

  function run() {
    var hero = document.getElementById('ca-hero');
    if (!hero || document.querySelector('.ca-splash')) return;

    injectStyles();
    if (getComputedStyle(hero).position === 'static') hero.style.position = 'relative';

    var overlay = document.createElement('div');
    overlay.className = 'ca-splash';
    overlay.innerHTML = template();
    hero.appendChild(overlay);

    var fill    = overlay.querySelector('.ca-sp-fill');
    var bootTx  = overlay.querySelector('.ca-sp-boot-tx');
    var cursor  = overlay.querySelector('.ca-sp-cur');
    var rows    = overlay.querySelectorAll('.ca-sp-row');
    var timers  = [];
    var finished = false;
    var reduce  = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var boot    = reduce ? null : startBoot(bootTx);

    function finish() {
      if (finished) return;
      finished = true;
      timers.forEach(clearTimeout);
      overlay.classList.add('ca-sp-ready');
      if (boot) boot.stop('klar — hitta din dr\xf6mbil ✓');
      else if (bootTx) bootTx.textContent = 'klar — hitta din dr\xf6mbil ✓';
      if (cursor) cursor.style.display = 'none';
      if (fill) fill.style.width = '100%';
      timers.push(setTimeout(function () {
        overlay.classList.add('ca-sp-out');
        setTimeout(function () { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }, 540);
      }, 800));
      markSeen();
    }

    overlay.querySelector('.ca-splash-skip').addEventListener('click', finish);
    fetchStats();

    if (reduce) {
      if (bootTx) bootTx.textContent = 'redo — hitta din dr\xf6mbil';
      if (cursor) cursor.style.display = 'none';
      rows.forEach(function (row) {
        row.classList.add('show', 'done');
        row.querySelector('.ca-sp-st').innerHTML = '<span class="ca-sp-check">✓</span>';
      });
      animated.models = true; // så refreshModels() uppdaterar till live-siffran när /api/stats svarar
      var mEl = suba(MODELS_ROW); if (mEl) mEl.innerHTML = modelsText(1, targets.models, targets.variants);
      animateInsights();
      if (fill) fill.style.width = '100%';
      timers.push(setTimeout(finish, 2200));
      return;
    }

    // ~5,5 s total: rader tickar in (loading-känsla), sen "boot complete"-flärt
    var START = 420, STAGGER = 500, FLIP = 360;
    rows.forEach(function (row, i) {
      var appear = START + i * STAGGER;
      timers.push(setTimeout(function () {
        row.classList.add('show');
        if (i === MODELS_ROW) animateModels();
        if (i === INS_ROW)    animateInsights();
      }, appear));
      timers.push(setTimeout(function () {
        row.classList.add('done');
        row.querySelector('.ca-sp-st').innerHTML = '<span class="ca-sp-check">✓</span>';
        if (fill) fill.style.width = Math.round((i + 1) / rows.length * 100) + '%';
        if (i === rows.length - 1) timers.push(setTimeout(finish, 500));
      }, appear + FLIP));
    });
  }

  window.caReplaySplash = function () {
    var o = document.querySelector('.ca-splash');
    if (o && o.parentNode) o.parentNode.removeChild(o);
    animated = {};
    run();
  };

  function shouldShow() {
    if (FORCE) return true;
    try { return !localStorage.getItem(SEEN_KEY); } catch (e) { return true; }
  }

  function boot() { if (shouldShow()) run(); }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
