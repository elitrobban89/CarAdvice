(function () {
  const API = window.EV_API_URL || "https://elbilsladdning.onrender.com";

  // Var FILERNA ligger — härlett ur var den här filen själv laddades ifrån.
  //
  // Skilt från API med flit: DATAN bor hos Elbilsladdning, som ligger på Renders
  // gratisnivå och somnar efter ~15 min, medan FILERNA serveras av CarAdvice som
  // ligger på betald plan och är vaken. Splashen är just det som ska synas MEDAN
  // datatjänsten vaknar — hämtas den från datatjänsten kommer den fram först när
  // den inte längre behövs.
  //
  // currentScript i stället för en hårdkodad värd: filen finns i två repon och måste
  // vara identisk i båda, så den måste fungera oavsett vem som serverar den.
  const ASSETS = (function () {
    var s = document.currentScript;
    if (s && s.src) { var i = s.src.lastIndexOf("/"); if (i > 0) return s.src.slice(0, i); }
    return "https://caradvice.onrender.com";
  })();

  // Auto-injicera uppstartssplashen. WP-sidan är en manuell kopia och laddar denna fil;
  // så här får den ev-splash.js utan att markupen behöver klistras om (samma mönster som
  // CarAdvice). Hoppar över om skriptet redan finns i sidan.
  (function injectSplash() {
    if (document.getElementById("ev-splash-js") ||
        document.querySelector('script[src*="ev-splash.js"]')) return;
    var s = document.createElement("script");
    s.id = "ev-splash-js";
    s.src = ASSETS + "/ev-splash.js";
    s.defer = true;
    (document.head || document.documentElement).appendChild(s);
  })();
  // ── Kallstartsvakt: hellre splash än halvtom sida ─────────────────────────
  //
  // Datatjänsten somnar, och en uppvakning tar ~15-30 s. Utan det här står besökaren
  // framför en sida som laddat men inte fyllts — och en tom sida ser ut som ett fel
  // medan en splash ser ut som att något händer.
  //
  // Probe med tidsgräns i stället för att mäta svarstiden: ett svar som dröjer ÄR en
  // kallstart, oavsett varför. Anropet väcker dessutom tjänsten, så väntan börjar
  // tidigare än om första riktiga datahämtningen fick göra det.
  //
  // Två vägar att visa splashen, för den kan ha hunnit olika långt: flaggan läses av
  // shouldShow() om splashskriptet ännu inte startat, och evReplaySplash() används om
  // det redan bestämt sig för att inte visa något. Guard mot att ta över ett lager
  // som redan ligger uppe.
  (function kallstartsvakt() {
    const TROSKEL_MS = 900;
    const larm = setTimeout(function () {
      window.EV_COLD_START = true;
      if (window.evReplaySplash && !document.querySelector(".ev-splash")) window.evReplaySplash();
    }, TROSKEL_MS);
    fetch(API + "/api/health", { cache: "no-store" })
      .catch(function () {})
      .then(function () { clearTimeout(larm); });
  })();

  // Splashen ska ligga kvar tills datan faktiskt finns, inte tills animationen råkar ta
  // slut. Utan signalen stängs den efter en fast tid, och vid en kallstart hann det bli
  // ~1,7 s där appen stod tom efteråt — precis det tomrum splashen fanns till för.
  //
  // BÅDE träff och miss signalerar. Ett misslyckat anrop är också ett slut på väntan, och
  // en splash som ligger kvar för data som aldrig kommer är värre än ingen splash alls.
  function evDataKlar() {
    if (window.EV_DATA_READY) return;
    window.EV_DATA_READY = true;
    try { window.dispatchEvent(new Event("ev-data-ready")); } catch (e) {}
  }
  window.evDataKlar = evDataKlar;

  let state = { lat: null, lon: null, city: "", sort: "speed", carIndex: null, cars: [], filter: "all", operatorFilter: null, lastData: null, lastRoute: null, lastCalc: null, favorites: [], evSalesRank: [], stationsOpen: false, valueRetention: [], valueRetentionKalla: "" };
  // ===== PRISLOGIK BÖRJAR — ren, testas av backend/src/test/js/pris-prov.js =====
  //
  // Låg förut inline på TRE ställen (stationskorten, chattens stationskontext och
  // laddkalkylen) med var sin kopia av växelkursen. Tre kopior av ett tal som driver
  // betyder att en rättning missar två av dem — och det här är siffror användaren
  // ser som kronor, inte interna mellanvärden.
  //
  // Blocket mellan markörerna klipps ut av provfilen, så flytta inte markörerna utan
  // att köra `node backend/src/test/js/pris-prov.js`.

  /**
   * Växelkurs EUR→SEK för utländska laddpriser.
   *
   * HÅRDKODAD MED FLIT, men på ETT ställe. En riktig kurstjänst är rätt lösning den dag
   * priserna ska stämma på kronan; tills dess är det viktiga att talet går att hitta och
   * ändra på en rad. Uppdaterad 2026-08-18.
   */
  const EUR_SEK = 11.5;

  /**
   * Antaget hemmapris per kWh, för jämförelsen "+X kr jämfört med hemmaladdning".
   *
   * Spannet i texten är 1,50–3,50 kr/kWh beroende på elavtal; 2,00 är mitten nedåt och
   * medvetet försiktig — jämförelsen ska hellre underdriva vad du sparar än överdriva.
   */
  const HEMMA_KR_PER_KWH = 2.0;

  /**
   * Tolkar ett laddpris från OpenChargeMap, som är fritext och inte ett belopp.
   *
   * {@code varEur} finns för att stationskorten visar det omräknade priset i stället för
   * råtexten just när källan angett euro — utan flaggan hade anroparen fått leta i strängen
   * en andra gång, och då är det två ställen som kan glida isär.
   *
   * @return {{krPerKwh: number|null, gratis: boolean, varEur: boolean}}
   */
  function tolkaLaddpris(raw) {
    const text = String(raw || "");
    const lower = text.toLowerCase();
    const gratis = lower.includes("gratis") || lower.includes("free");
    const varEur = text.includes("EUR");

    // Första talet i strängen. Notera att "1.234,56" INTE hanteras: OpenChargeMap skriver
    // inte tusentalsavgränsare i kr/kWh-priser, och att gissa på formatet gör mer skada
    // än nytta — ett felparsat pris blir en trovärdig men fel krona på skärmen.
    const traff = text.match(/\d+(?:[.,]\d+)?/);
    if (!traff) return { krPerKwh: null, gratis, varEur };

    const tal = parseFloat(traff[0].replace(",", "."));
    if (!isFinite(tal)) return { krPerKwh: null, gratis, varEur };

    return { krPerKwh: varEur ? tal * EUR_SEK : tal, gratis, varEur };
  }

  /** Vad en full laddning kostar, eller null när något saknas. Gratis ger null, inte 0. */
  function fullLaddningKr(batteryKwh, krPerKwh, gratis) {
    if (gratis || !batteryKwh || !krPerKwh) return null;
    return Math.round(batteryKwh * krPerKwh);
  }

  /** Verklig räckvidd i mil — WLTP minus 15 %, samma påslag som resten av appen. */
  function verkligaMil(rangeKm) {
    return rangeKm ? Math.round(rangeKm / 10 * 0.85) : null;
  }

  /** Kronor per mil, eller null när underlaget saknas. */
  function krPerMilAv(fullKr, mil) {
    return (fullKr && mil) ? Math.round(fullKr / mil) : null;
  }

  /** Vad samma laddning kostat hemma, och merkostnaden — null när stationen inte är dyrare. */
  function merKostnadMotHemma(batteryKwh, fullKr) {
    if (!batteryKwh || !fullKr) return null;
    const hemma = Math.round(batteryKwh * HEMMA_KR_PER_KWH);
    return fullKr > hemma ? fullKr - hemma : null;
  }
  // ===== PRISLOGIK SLUTAR =====

  // Platsen i utdatan dar verktygsavdelningen ska in. Deklarerad HAR och inte vid
  // verktygArea: renderResults anvander den langt tidigare i filen, och en const som
  // anvands ovanfor sin egen rad ar korrekt bara sa lange man kan bevisa att anropet
  // sker efter att modulen kort klart. Det behover inte bevisas om den star forst.
  const VERKTYG_PLATS = "<!--ev-verktyg-->";

  let evMap = null;
  let evMapMarkers = [];
  let evRoutePolyline = null;
  let savedStationsHtml = null;

  (function injectStyles() {
    const s = document.createElement("style");
    s.textContent =
      ".ev-op-chip{padding:4px 11px;border-radius:20px;border:1.5px solid rgba(59,130,246,0.2);background:rgba(59,130,246,0.06);color:rgba(147,197,253,0.7);font-size:.72rem;font-weight:600;cursor:pointer;transition:all .15s;white-space:nowrap;}" +
      ".ev-op-chip:hover{border-color:rgba(59,130,246,0.5);color:#93c5fd;background:rgba(59,130,246,0.12);}" +
      ".ev-op-chip.ev-op-active{border-color:rgba(59,130,246,0.6);color:#fff;background:linear-gradient(135deg,#1d4ed8,#2563eb);}" +
      ".ev-route-panel{background:#0d1526;border:1px solid rgba(59,130,246,0.2);border-radius:14px;padding:20px;margin-bottom:16px;}" +
      ".ev-route-title{font-size:.72rem;font-weight:700;text-transform:uppercase;letter-spacing:.1em;color:rgba(147,197,253,.7);margin-bottom:14px;}" +
      ".ev-route-row{display:flex;gap:8px;align-items:center;margin-bottom:10px;}" +
      ".ev-route-input{flex:1;padding:10px 13px;background:#060c1a;border:1.5px solid rgba(59,130,246,.2);border-radius:9px;color:#f0f4ff;font-size:.88rem;}" +
      ".ev-route-input:focus{outline:none;border-color:#3b82f6;}" +
      ".ev-route-input::placeholder{color:rgba(200,215,255,.35);}" +
      ".ev-route-btn{padding:10px 18px;background:linear-gradient(135deg,#1d4ed8,#2563eb);border:none;border-radius:9px;color:#fff;font-size:.82rem;font-weight:700;cursor:pointer;white-space:nowrap;}" +
      ".ev-route-btn:hover{opacity:.88;}" +
      ".ev-route-btn:disabled{opacity:.45;cursor:default;}" +
      ".ev-route-timeline{margin-top:14px;}" +
      ".ev-route-stop{display:flex;gap:12px;align-items:flex-start;margin-bottom:12px;}" +
      ".ev-route-dot{width:28px;height:28px;border-radius:50%;background:linear-gradient(135deg,#1d4ed8,#2563eb);border:2px solid #3b82f6;display:flex;align-items:center;justify-content:center;font-size:.7rem;font-weight:800;color:#fff;flex-shrink:0;margin-top:2px;}" +
      ".ev-route-dot.start{background:linear-gradient(135deg,#16a34a,#22c55e);border-color:#22c55e;}" +
      ".ev-route-dot.end{background:linear-gradient(135deg,#dc2626,#ef4444);border-color:#ef4444;}" +
      ".ev-route-line{width:2px;background:rgba(59,130,246,.25);margin:0 13px;flex-shrink:0;align-self:stretch;min-height:12px;}" +
      ".ev-route-info{flex:1;min-width:0;}" +
      ".ev-route-name{font-size:.88rem;font-weight:700;color:#f0f4ff;margin-bottom:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}" +
      ".ev-route-meta{font-size:.75rem;color:rgba(200,215,255,.55);}" +
      "@media(max-width:500px){" +
      ".ev-station-body{padding:10px 10px!important;gap:0 8px!important;grid-template-columns:auto 1fr!important;}" +
      ".ev-rank{grid-row:1/4!important;width:24px!important;height:24px!important;font-size:.68rem!important;}" +
      ".ev-station-name{white-space:normal!important;font-size:.9rem!important;line-height:1.3!important;}" +
      ".ev-station-addr{white-space:normal!important;font-size:.73rem!important;line-height:1.3!important;}" +
      ".ev-station-tags{gap:4px!important;margin-top:5px!important;}" +
      ".ev-station-right{grid-column:2!important;grid-row:3!important;flex-direction:row!important;align-items:center!important;justify-content:flex-start!important;gap:8px!important;flex-wrap:wrap!important;margin-top:4px!important;}" +
      ".ev-dist{font-size:.73rem!important;}" +
      ".ev-price-badge{font-size:.72rem!important;padding:2px 7px!important;}" +
      ".ev-fav-btn{margin-top:0!important;}" +
    "}" +
    ".ev-section-divider{display:flex;align-items:center;gap:10px;margin:20px 0 4px;}" +
    ".ev-divider-line{flex:1;height:1px;background:linear-gradient(90deg,transparent,rgba(251,191,36,0.4),transparent);}" +
    ".ev-divider-badge{background:rgba(251,191,36,0.1);border:1px solid rgba(251,191,36,0.3);border-radius:20px;padding:3px 14px;font-size:11px;font-weight:700;color:#fbbf24;letter-spacing:0.06em;white-space:nowrap;}" +
    ".ev-fact-nav{background:rgba(59,130,246,0.08);border:1.5px solid rgba(59,130,246,0.2);border-radius:8px;color:rgba(147,197,253,0.7);font-size:20px;width:32px;height:32px;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .15s;padding:0;line-height:1;}" +
    ".ev-fact-nav:hover{background:rgba(59,130,246,0.2);color:#93c5fd;}" +
    ".ev-fact-dot{width:8px;height:8px;border-radius:50%;border:none;background:rgba(147,197,253,0.2);cursor:pointer;padding:0;transition:all .25s;flex-shrink:0;}" +
    ".ev-fact-dot.ev-fact-dot-active{background:#3b82f6;width:20px;border-radius:4px;}" +
    // Paus/spela-knappen har TEXT och inte bara en ikon. En ensam ⏸-symbol är gissningsbar
    // men inte självklar, och knappen finns just för den som vill stanna upp och läsa en
    // tabell i lugn och ro — då ska den inte kräva en gissning till.
    ".ev-fact-play{display:inline-flex;align-items:center;gap:7px;background:rgba(59,130,246,0.08);border:1.5px solid rgba(59,130,246,0.2);border-radius:999px;color:rgba(147,197,253,0.85);font-size:12px;font-weight:600;height:32px;padding:0 14px;cursor:pointer;transition:all .15s;line-height:1;white-space:nowrap;font-family:inherit;}" +
    ".ev-fact-play:hover{background:rgba(59,130,246,0.2);color:#93c5fd;}" +
    // Pausat läge byter färg till samma gula som avdelarens badge, så det syns på en meter
    // att karusellen står still med flit och inte har hängt sig.
    ".ev-fact-play[aria-pressed='true']{background:rgba(251,191,36,0.12);border-color:rgba(251,191,36,0.38);color:#fbbf24;}" +
    ".ev-fact-play-icon{font-size:11px;line-height:1;}" +
    // Förloppslinjen gör pausen begriplig: utan den ser en pausad karusell exakt likadan ut
    // som en trasig. Den fylls på 9 s, alltså samma intervall som rotationen, och fryser
    // mitt i sitt lopp när man pausar.
    // Stationslistans öppna-knapp. Full bredd och tydlig text — den ersätter innehåll som
    // låg framme förut, så den får inte gå att missa.
    // Karusellområdet: en tonad ram som håller ihop de två korten till EN sak. Utan den
    // läste de som två lösryckta rutor mitt i resultatlistan.
    ".ev-carousel-area{background:linear-gradient(180deg,rgba(251,191,36,0.05),rgba(251,191,36,0));border:1px solid rgba(251,191,36,0.16);border-radius:16px;padding:15px 13px 7px;margin:22px 0 6px;}" +
    ".ev-carousel-head{display:flex;align-items:center;gap:11px;padding:0 3px 13px;}" +
    ".ev-carousel-head-icon{font-size:19px;line-height:1;flex-shrink:0;filter:drop-shadow(0 0 9px rgba(251,191,36,0.5));}" +
    ".ev-carousel-head-title{font-size:14px;font-weight:800;color:#fbbf24;letter-spacing:0.02em;line-height:1.2;}" +
    ".ev-carousel-head-sub{font-size:11.5px;color:rgba(147,197,253,0.55);margin-top:3px;line-height:1.35;}" +

    // --- Flikarna i karusellavdelningen -------------------------------------
    // Ligger där rubriken låg, med samma luft under. Aktiv flik bär avdelningens gula ton, så
    // det syns vilken av de två man tittar på utan att läsa texten.
    ".ev-flikar{display:flex;flex-wrap:wrap;gap:7px;padding:0 2px 14px;}" +
    ".ev-flik{display:inline-flex;align-items:center;gap:7px;padding:8px 14px;border-radius:999px;"
      + "border:1.5px solid rgba(251,191,36,.18);background:rgba(251,191,36,.05);"
      + "color:rgba(200,215,255,.62);font-size:12.5px;font-weight:700;font-family:inherit;"
      + "cursor:pointer;transition:all .16s;white-space:nowrap;}" +
    ".ev-flik:hover{border-color:rgba(251,191,36,.4);color:rgba(251,191,36,.85);}" +
    ".ev-flik-aktiv{background:rgba(251,191,36,.14);border-color:rgba(251,191,36,.55);color:#fbbf24;}" +
    ".ev-flik:focus-visible{outline:2px solid rgba(251,191,36,.6);outline-offset:2px;}" +
    "@media (max-width:420px){.ev-flik{font-size:11.5px;padding:7px 11px;}}" +

    // Verktygsavdelningen: samma form som karusellen, egen färg. Blå i stället för gul, så de
    // två avdelningarna går att skilja åt på en meter utan att formspråket blir ett nytt.
    ".ev-tools-area{background:linear-gradient(180deg,rgba(59,130,246,0.06),rgba(59,130,246,0));"
      + "border-color:rgba(59,130,246,0.2);}" +
    ".ev-tools-area .ev-carousel-head-icon{filter:drop-shadow(0 0 9px rgba(59,130,246,0.5));}" +
    ".ev-tools-area .ev-carousel-head-title{color:#93c5fd;}" +
    // --- Laddtidskalkylatorn -------------------------------------------------
    // Mörk botten TILLBAKA, men ingen ram. När kortet slutade bära .ev-funfact-card
    // förlorade det inte bara den dubbla ramen utan också sitt underlag
    // (#0f1a2e→#0a1520), och texten hamnade på avdelningens ljusare blå ton.
    // Användaren såg det direkt: "svårt läsa texten … ljus bakgrund". Det var RAMEN
    // som var problemet, inte bottnen — och de följdes åt i samma klass.
    "#ev-calc-card{background:linear-gradient(135deg,#0f1a2e,#0a1520);border-radius:12px;"
      + "padding:14px 16px;}" +
    ".ev-calc-titel{font-size:12.5px;font-weight:800;color:#93c5fd;letter-spacing:.02em;margin-bottom:14px;}" +
    // Bilnamnet i normalvikt efter rubriken: det är en upplysning om VILKEN bil siffrorna
    // gäller, inte en del av verktygets namn.
    ".ev-calc-titel span{font-weight:500;color:rgba(200,215,255,.62);}" +
    ".ev-calc-reglage{display:grid;grid-template-columns:1fr 1fr;gap:14px 22px;margin-bottom:14px;}" +
    "@media (max-width:520px){.ev-calc-reglage{grid-template-columns:1fr;}}" +
    // 11,5 px i 65 % opacitet var laseligt pa den morka bottnen men inte pa den ljusa,
    // och en etikett man kisar mot ar en etikett for lite.
    ".ev-calc-etikett{font-size:12.5px;color:rgba(205,222,255,.85);margin-bottom:8px;}" +
    // Procenttalet är det man läser av — stort, ljust och i siffrornas egen färg.
    ".ev-calc-etikett b{font-size:17px;font-weight:800;color:#fff;}" +

    // Reglagen: webbläsarens grundutseende är en vit stapel som lyser i en mörk app. Både
    // -webkit- och -moz-vägen behövs; de delar ingen enda selektor.
    ".ev-calc-slider{-webkit-appearance:none;appearance:none;width:100%;height:6px;border-radius:99px;"
      + "background:rgba(59,130,246,.16);outline:none;cursor:pointer;}" +
    ".ev-calc-slider::-webkit-slider-thumb{-webkit-appearance:none;appearance:none;width:17px;height:17px;"
      + "border-radius:50%;background:#3b82f6;border:2.5px solid #0d1526;"
      + "box-shadow:0 0 0 1px rgba(59,130,246,.5),0 2px 6px rgba(0,0,0,.5);transition:transform .12s;}" +
    ".ev-calc-slider::-webkit-slider-thumb:hover{transform:scale(1.15);}" +
    ".ev-calc-slider::-moz-range-thumb{width:17px;height:17px;border-radius:50%;background:#3b82f6;"
      + "border:2.5px solid #0d1526;box-shadow:0 0 0 1px rgba(59,130,246,.5);}" +
    ".ev-calc-slider::-moz-range-track{height:6px;border-radius:99px;background:rgba(59,130,246,.16);}" +
    ".ev-calc-slider:focus-visible{box-shadow:0 0 0 3px rgba(59,130,246,.25);}" +

    // Svaret är kortets ankare och får en ton av accenten i stället för grå genomskinlighet.
    ".ev-calc-svar{background:linear-gradient(135deg,rgba(59,130,246,.13),rgba(59,130,246,.05));"
      + "border:1px solid rgba(59,130,246,.28);border-radius:11px;padding:12px 15px;}" +
    ".ev-calc-kalla{font-size:11px;color:rgba(170,196,240,.6);margin-top:10px;line-height:1.45;}" +
    // Korten inne i området behöver luft mellan sig, annars ser de ut som ett enda långt kort.
    ".ev-carousel-area .ev-funfact-card{margin-bottom:12px;}" +
    ".ev-stations-toggle{width:100%;display:flex;align-items:center;justify-content:center;gap:9px;background:linear-gradient(135deg,rgba(59,130,246,0.16),rgba(37,99,235,0.10));border:1.5px solid rgba(59,130,246,0.38);border-radius:12px;color:#93c5fd;font-size:13.5px;font-weight:700;font-family:inherit;padding:13px 16px;cursor:pointer;transition:background .2s,border-color .2s,color .2s;margin:4px 0 14px;}" +
    ".ev-stations-toggle:hover{background:linear-gradient(135deg,rgba(59,130,246,0.26),rgba(37,99,235,0.16));border-color:rgba(59,130,246,0.62);color:#bfdbfe;}" +
    ".ev-stations-toggle .ev-chevron{font-size:10px;transition:transform .25s;}" +
    ".ev-stations-toggle[aria-expanded='true'] .ev-chevron{transform:rotate(180deg);}" +
    // Glödet pulsar BARA i hopfällt läge. Öppet är innehållet redan framme, och en knapp som
    // fortsätter blinka när den gjort sitt är bara störande.
    "@keyframes ev-glow-pulse{0%,100%{box-shadow:0 0 16px rgba(59,130,246,0.16)}50%{box-shadow:0 0 30px rgba(59,130,246,0.40)}}" +
    ".ev-stations-toggle[aria-expanded='false']{animation:ev-glow-pulse 2.8s ease-in-out infinite;}" +
    "@media (prefers-reduced-motion:reduce){.ev-stations-toggle[aria-expanded='false']{animation:none;box-shadow:0 0 18px rgba(59,130,246,0.22);}}" +
    ".ev-fact-progress{height:2px;border-radius:2px;background:rgba(147,197,253,0.14);overflow:hidden;margin-top:12px;}" +
    ".ev-fact-progress-bar{height:100%;width:0;background:linear-gradient(90deg,#3b82f6,#60a5fa);border-radius:2px;}" +
    ".ev-fact-progress-bar.ev-run{animation:ev-fact-fill 9s linear forwards;}" +
    "@keyframes ev-fact-fill{from{width:0}to{width:100%}}" +
    "@keyframes ev-slide-in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}" +
    "@keyframes ev-slide-out{from{opacity:1;transform:none}to{opacity:0;transform:translateY(-8px)}}" +
    ".ev-slide-entering{animation:ev-slide-in .5s cubic-bezier(.22,1,.36,1) forwards;}" +
    ".ev-slide-leaving{animation:ev-slide-out .35s ease forwards;pointer-events:none;position:absolute;top:0;left:0;width:100%;}" +

    // --- Märkesväljaren -----------------------------------------------------
    // Ligger här och inte i sidans <style> av samma skäl som allt annat i den här
    // funktionen: WP-sidan är en manuell kopia och uppdateras inte av en deploy.
    ".ev-picker{position:relative;}" +
    // Avtryckaren är byggd att se ut EXAKT som .ev-select — samma padding, ram, radie och
    // fokusring — så bytet inte läser som ett främmande element mitt i formuläret.
    ".ev-picker-trigger{width:100%;display:flex;align-items:center;gap:10px;padding:9px 14px 9px 9px;background:#060c1a;border:1.5px solid rgba(59,130,246,0.18);border-radius:10px;color:#f0f4ff;font-size:.92rem;font-family:inherit;text-align:left;cursor:pointer;transition:border-color .2s,box-shadow .2s;}" +
    ".ev-picker-trigger:hover{border-color:rgba(59,130,246,0.45);}" +
    ".ev-picker-trigger:focus-visible{outline:none;border-color:#3b82f6;box-shadow:0 0 0 3px rgba(59,130,246,0.15);}" +
    ".ev-picker-open .ev-picker-trigger{border-color:#3b82f6;box-shadow:0 0 0 3px rgba(59,130,246,0.15);}" +
    ".ev-picker-text{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:rgba(200,215,255,0.5);}" +
    ".ev-picker-vald{color:#f0f4ff;font-weight:600;}" +
    ".ev-picker-chevron{color:#3b82f6;font-size:.8rem;transition:transform .25s cubic-bezier(.22,1,.36,1);}" +
    ".ev-picker-open .ev-picker-chevron{transform:rotate(180deg);}" +

    // Emblemet: monogram i märkets färg. --emblem sätts per knapp, så en enda regel bär
    // alla 64 märkena i stället för 64 klasser.
    ".ev-picker-emblem{flex-shrink:0;width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:.72rem;font-weight:800;letter-spacing:.02em;color:var(--emblem,#93c5fd);background:color-mix(in srgb,var(--emblem,#3b82f6) 14%,transparent);border:1.5px solid color-mix(in srgb,var(--emblem,#3b82f6) 38%,transparent);box-shadow:inset 0 1px 0 rgba(255,255,255,.06);transition:transform .2s cubic-bezier(.22,1,.36,1),box-shadow .2s;}" +
    // color-mix saknas i äldre webbläsare och ger då ingen bakgrund alls — reservregeln
    // nedan körs bara där stödet fattas och håller plattan synlig.
    "@supports not (background:color-mix(in srgb,red 10%,transparent)){.ev-picker-emblem{background:rgba(59,130,246,.14);border-color:rgba(59,130,246,.38);}}" +
    ".ev-picker-emblem-tom{color:#3b82f6;background:rgba(59,130,246,.1);border-color:rgba(59,130,246,.25);font-size:1rem;}" +
    ".ev-picker-emblem-sm{width:26px;height:26px;border-radius:7px;font-size:.62rem;}" +
    // Bildplattan ar VIT: emblemen ar gjorda for ljus botten, och pa den morka plattan var
    // Mercedes-stjarnan, VW-ringen, Audi-ringarna och Fords markbla oval nastan osynliga.
    // Matt pa kontaktark innan valet gjordes.
    ".ev-picker-emblem-bild{background:#fff;border-color:rgba(255,255,255,.55);padding:4px;}" +
    ".ev-picker-emblem-bild img{width:100%;height:100%;object-fit:contain;display:block;}" +
    ".ev-picker-emblem-sm.ev-picker-emblem-bild{padding:3px;}" +

    // --- Väntelaget: avtryckaren medan tjänsten vaknar ------------------------
    // Texten ensam räckte inte. En knapp som byter ord men i övrigt ser exakt likadan
    // ut läser som att sidan står still — och "står still" är precis det beskedet ska
    // motbevisa. Rörelsen är beviset: så länge något snurrar pågår det något. Uppvak-
    // ningen är mätt till 115-121 s, alltså en lång stund att hålla någon lugn.
    //
    // Svepet ligger i background-IMAGE, inte i ett absolut ::after över knappen: ett
    // positionerat pseudoelement målas OVANPÅ texten och tvättar ur den (samma fälla
    // som en gång bleknade korttexterna). background-image målas över background-color
    // men under innehållet.
    ".ev-picker-vantar .ev-picker-trigger{cursor:progress;border-color:rgba(59,130,246,.42);background-image:linear-gradient(100deg,transparent 30%,rgba(96,165,250,.16) 50%,transparent 70%);background-size:220% 100%;background-repeat:no-repeat;animation:ev-picker-svep 2.4s linear infinite;}" +
    "@keyframes ev-picker-svep{from{background-position:120% 0}to{background-position:-120% 0}}" +
    // Ringen sitter UTANFÖR plattan (inset:-4px), så blixten inuti står kvar orörd:
    // snurran ska läsa som att något laddas, inte som att ikonen bytts ut.
    ".ev-picker-vantar .ev-picker-emblem-tom{position:relative;box-shadow:0 0 14px rgba(96,165,250,.3),inset 0 1px 0 rgba(255,255,255,.06);animation:ev-picker-blixt 1.6s ease-in-out infinite;}" +
    // Hela ringen är svagt tänd och BARA toppbågen ljus: utan spåret syns en ensam båge
    // knappt mot den mörka plattan — den lästes som en kantartefakt i första provet.
    // RUND, trots att plattan är en rundad fyrkant: en roterande fyrkant läser som att
    // ikonen står och vickar, en cirkel som att något laddas.
    ".ev-picker-vantar .ev-picker-emblem-tom::after{content:'';position:absolute;inset:-5px;border-radius:50%;border:2.5px solid rgba(96,165,250,.17);border-top-color:#7dd3fc;border-right-color:rgba(125,211,252,.55);animation:ev-picker-snurr .85s linear infinite;pointer-events:none;}" +
    "@keyframes ev-picker-snurr{to{transform:rotate(360deg)}}" +
    "@keyframes ev-picker-blixt{0%,100%{opacity:.6;transform:scale(1)}50%{opacity:1;transform:scale(1.07)}}" +
    // Skimret får bara finnas där background-clip:text går att lita på: utan vakten blir
    // color:transparent en OSYNLIG text i de webbläsare som inte klipper, alltså värre
    // än ingen effekt alls.
    "@supports ((-webkit-background-clip:text) or (background-clip:text)){" +
      ".ev-picker-vantar .ev-picker-text{background-image:linear-gradient(100deg,rgba(200,215,255,.45) 30%,#dbeafe 50%,rgba(200,215,255,.45) 70%);background-size:220% 100%;-webkit-background-clip:text;background-clip:text;color:transparent;animation:ev-picker-svep 2.4s linear infinite;}}" +
    // Rörelsen är en förklaring, inte en dekoration — den som stängt av animationer ska
    // ändå se att knappen väntar. Ringen och den tända ramen står kvar, stilla.
    "@media (prefers-reduced-motion:reduce){.ev-picker-vantar .ev-picker-trigger,.ev-picker-vantar .ev-picker-text,.ev-picker-vantar .ev-picker-emblem-tom,.ev-picker-vantar .ev-picker-emblem-tom::after{animation:none;}}" +

    ".ev-picker-panel{position:absolute;z-index:60;left:0;right:0;top:calc(100% + 8px);background:#0d1526;border:1.5px solid rgba(59,130,246,0.28);border-radius:14px;box-shadow:0 18px 44px rgba(0,0,0,.55);padding:12px;animation:ev-picker-in .22s cubic-bezier(.22,1,.36,1);}" +
    "@keyframes ev-picker-in{from{opacity:0;transform:translateY(-6px) scale(.985)}to{opacity:1;transform:none}}" +
    ".ev-picker-search{width:100%;padding:9px 12px;margin-bottom:10px;background:#060c1a;border:1.5px solid rgba(59,130,246,.2);border-radius:9px;color:#f0f4ff;font-size:.86rem;font-family:inherit;}" +
    ".ev-picker-search:focus{outline:none;border-color:#3b82f6;}" +
    ".ev-picker-search::placeholder{color:rgba(200,215,255,.35);}" +

    // De två stegen ligger sida vid sida i ett dubbelbrett spår som skjuts i sidled. Att
    // glida är inte dekoration: det visar att modellerna ligger INUTI märket man tryckte på,
    // så vägen tillbaka blir självklar.
    ".ev-picker-steps{display:flex;width:200%;transition:transform .32s cubic-bezier(.22,1,.36,1);}" +
    ".ev-picker-steps.ev-picker-at-models{transform:translateX(-50%);}" +
    ".ev-picker-step{width:50%;flex-shrink:0;max-height:326px;overflow-y:auto;overscroll-behavior:contain;}" +
    "@media (prefers-reduced-motion:reduce){.ev-picker-steps{transition:none;}.ev-picker-panel{animation:none;}}" +

    ".ev-picker-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(148px,1fr));gap:7px;padding:1px;}" +
    ".ev-picker-brand{display:flex;align-items:center;gap:9px;padding:8px;background:rgba(59,130,246,.04);border:1.5px solid rgba(59,130,246,.14);border-radius:11px;cursor:pointer;font-family:inherit;text-align:left;transition:border-color .16s,background .16s,transform .16s;}" +
    // GLÖDEN vid hover är bärnsten och inte blå. Blått är appens grundton — knappar, ramar,
    // reglage — så en blå hover säger inte "här är du" utan bara "här är ännu ett element".
    // Bärnsten är samma färg som karusellens och flikarnas accent, alltså redan husets, men
    // används ingen annanstans som yta. Glöden ligger i box-shadow och inte i bakgrunden:
    // en ljusare platta hade tävlat med de vita emblemplattorna om uppmärksamheten.
    ".ev-picker-brand{transition:border-color .16s,background .16s,transform .16s,box-shadow .16s;}" +
    ".ev-picker-brand:hover{background:rgba(251,191,36,.09);border-color:rgba(251,191,36,.5);"
      + "transform:translateY(-1px);"
      + "box-shadow:0 0 0 1px rgba(251,191,36,.22),0 5px 20px -6px rgba(251,191,36,.5);}" +
    ".ev-picker-brand:hover .ev-picker-brand-name{color:#fde68a;}" +
    ".ev-picker-brand:hover .ev-picker-brand-count{color:rgba(251,191,36,.55);}" +
    // Emblemet lyfts OCH får sin egen halo, så både monogram och vit bildplatta reagerar.
    ".ev-picker-brand:hover .ev-picker-emblem{transform:scale(1.07);box-shadow:0 0 13px rgba(251,191,36,.5);}" +
    ".ev-picker-brand:focus-visible{outline:2px solid #3b82f6;outline-offset:2px;}" +
    ".ev-picker-brand-txt{display:flex;flex-direction:column;min-width:0;}" +
    // Långa märkesnamn kapas med ellips i stället för att bryta raden: "Mercedes-Benz" och
    // "Rolls-Royce" gjorde annars plattan dubbelt så hög som grannens.
    ".ev-picker-brand-name{font-size:.82rem;font-weight:700;color:#f0f4ff;line-height:1.3;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}" +
    ".ev-picker-brand-count{font-size:.66rem;color:rgba(200,215,255,.45);line-height:1.3;white-space:nowrap;}" +

    ".ev-picker-back-row{display:flex;align-items:center;gap:9px;padding:0 2px 10px;position:sticky;top:0;background:#0d1526;z-index:2;}" +
    ".ev-picker-back{background:none;border:none;color:#3b82f6;font-size:.78rem;font-weight:700;cursor:pointer;padding:4px 6px 4px 0;font-family:inherit;}" +
    ".ev-picker-back:hover{color:#93c5fd;}" +
    ".ev-picker-brand-head{font-size:.86rem;font-weight:800;color:#f0f4ff;}" +
    ".ev-picker-model-list{display:flex;flex-direction:column;gap:5px;padding:1px;}" +
    ".ev-picker-model{display:flex;flex-direction:column;gap:2px;padding:9px 11px;background:rgba(59,130,246,.04);border:1.5px solid rgba(59,130,246,.13);border-radius:10px;cursor:pointer;font-family:inherit;text-align:left;transition:border-color .16s,background .16s;}" +
    // Modellraden får samma språk men dämpat: man har redan valt märke, och en lika stark
    // glöd på varje rad i en lista med fjorton hade blivit ett ljusspel.
    ".ev-picker-model:hover{background:rgba(251,191,36,.07);border-color:rgba(251,191,36,.4);"
      + "box-shadow:0 0 14px -5px rgba(251,191,36,.45);}" +
    ".ev-picker-model:hover .ev-picker-model-name{color:#fde68a;}" +
    ".ev-picker-model:focus-visible{outline:2px solid #3b82f6;outline-offset:2px;}" +
    ".ev-picker-model-name{font-size:.84rem;font-weight:600;color:#f0f4ff;}" +
    ".ev-picker-model-specs{font-size:.7rem;color:rgba(200,215,255,.45);}" +
    // Milen är det man faktiskt väljer bil på, så den får samma ljusblå som resten av
    // appens mätvärden — inte samma nedtonade grå som specarna omkring.
    ".ev-picker-model-mil{color:rgba(147,197,253,.92);font-weight:700;}" +
    ".ev-picker-model-saknas{color:rgba(251,191,36,.75);}" +
    // Laddeffekten står kvar men ett steg svagare: den avgör hur snabbt det går, inte
    // hur långt man kommer, och får därför inte konkurrera med milen om blicken.
    ".ev-picker-model-ladd{font-size:.66rem;color:rgba(200,215,255,.32);}" +
    ".ev-picker-model-legend{font-size:.66rem;color:rgba(200,215,255,.38);padding:0 2px 8px;line-height:1.4;}" +
    ".ev-picker-model-legend b{color:rgba(147,197,253,.7);font-weight:700;}" +
    // Namnbytesnotisen far amber, samma farg som appens ovriga "las har"-markorer
    ".ev-picker-model-alias{font-size:.68rem;color:rgba(251,191,36,.75);margin-top:2px;}" +
    ".ev-picker-tom{padding:22px 10px;text-align:center;font-size:.82rem;color:rgba(200,215,255,.45);}" +
    // Under 500 px blir rutnätet två kolumner och panelen lite lägre, så tangentbordet på
    // mobilen inte täcker hela listan.
    "@media (max-width:500px){.ev-picker-grid{grid-template-columns:repeat(2,1fr);}.ev-picker-step{max-height:270px;}.ev-picker-trigger{font-size:1rem;}.ev-picker-search{font-size:1rem;}}" +
    // Hjälpraden under körsträckan
    ".ev-mil-hint{font-size:.7rem;color:rgba(200,215,255,.45);margin-top:6px;line-height:1.4;}" +
    ".ev-mil-hint b{color:rgba(147,197,253,.8);font-weight:700;}" +

    // --- DIN ELBIL: specarna i grupper i stället för en radbrytande rad -------
    // Överstyr sidans egen `.ev-specs{display:flex;flex-wrap:wrap}` med en klass som JS
    // sätter, i stället för att ändra i <style> — WP-sidan är en manuell kopia och skulle
    // annars behöva klistras om för en ren layoutändring.
    // Två klasser och inget id: sidans egen regel är `.ev-specs` med EN klass, så den här
    // vinner på specificitet utan att bero på att elementet råkar heta #ev-specs.
    // Rutnät med rubrikkolumnen först: `auto 1fr` låter rubrikerna bli precis så breda som
    // det längsta ordet och håller badgesarna vänsterjusterade på samma linje i alla tre rader.
    ".ev-specs.ev-specs-rader{display:grid;grid-template-columns:auto 1fr;gap:8px 13px;align-items:center;}" +
    ".ev-spec-row{display:flex;flex-wrap:wrap;gap:7px;align-items:center;}" +
    // Rubrikerna lånar formen av sidans egna .ev-label — samma versaler, spärrning och blågrå
    // ton — så de läser som etiketter och inte som ännu en badge.
    ".ev-spec-rubrik{font-size:.62rem;font-weight:700;text-transform:uppercase;letter-spacing:.1em;"
      + "color:rgba(147,197,253,.6);white-space:nowrap;text-align:right;}" +
    // Under 560 px ryms ingen rubrikkolumn: då står rubriken PÅ egen rad ovanför sina badges,
    // vänsterställd. Två kolumner där hade klämt ihop badgesarna till en pelare.
    "@media (max-width:560px){.ev-specs.ev-specs-rader{grid-template-columns:1fr;gap:3px;}"
      + ".ev-spec-rubrik{text-align:left;margin-top:7px;}"
      + ".ev-specs.ev-specs-rader>.ev-spec-rubrik:first-child{margin-top:0;}}" +
    // Badgesarna bär nu en ikon först; luften mellan ikon och text kommer från ordmellanslaget
    // och behöver ingen egen regel. Radhöjden däremot: emoji är högre än siffrorna och sköt
    // isär raderna olika mycket beroende på vilka badges som råkade hamna där.
    ".ev-spec-row .ev-spec-badge{line-height:1.5;}";
    document.head.appendChild(s);
  })();

  function renderMap(userLat, userLon, stations) {
    if (!window.L) return;
    const mapEl = document.getElementById("ev-map");
    if (!mapEl) return;
    mapEl.style.display = "block";

    if (evRoutePolyline) { evRoutePolyline.remove(); evRoutePolyline = null; }

    if (!evMap) {
      evMap = L.map("ev-map", { zoomControl: true }).setView([userLat, userLon], 17);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
        maxZoom: 19
      }).addTo(evMap);
      evMap.invalidateSize();
    } else {
      evMapMarkers.forEach(m => m.remove());
      evMapMarkers = [];
      evMap.setView([userLat, userLon], 17);
    }

    const userIcon = L.divIcon({
      className: "",
      html: '<div style="width:18px;height:18px;border-radius:50%;background:#3b82f6;border:3px solid #fff;box-shadow:0 0 0 3px rgba(59,130,246,0.35),0 2px 8px rgba(0,0,0,0.4)"></div>',
      iconSize: [18, 18], iconAnchor: [9, 9]
    });
    evMapMarkers.push(L.marker([userLat, userLon], { icon: userIcon }).addTo(evMap).bindPopup("📍 Din position"));

    stations.forEach((s, i) => {
      if (!s.lat || !s.lon) return;
      const color = s.maxEffKw >= 100 ? "#22c55e" : s.maxEffKw >= 22 ? "#f59e0b" : "#818cf8";
      const icon = L.divIcon({
        className: "",
        html: `<div style="width:30px;height:30px;border-radius:50%;background:${color};border:2.5px solid #fff;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:800;color:#111;box-shadow:0 2px 8px rgba(0,0,0,0.45)">${i + 1}</div>`,
        iconSize: [30, 30], iconAnchor: [15, 15], popupAnchor: [0, -15]
      });
      const price = s.chargepricePerKwh || s.usageCost || "okänt pris";
      const popup = `<b>${s.name}</b><br>⚡ ${Math.round(s.maxEffKw)} kW · ${s.connectorType}<br>📍 ${s.distanceKm.toFixed(1)} km bort<br>💰 ${price}`;
      evMapMarkers.push(L.marker([s.lat, s.lon], { icon }).addTo(evMap).bindPopup(popup));
    });
  }

  function getUserId() {
    let uid = localStorage.getItem("ev-user-id");
    if (!uid) {
      uid = crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now().toString(36);
      localStorage.setItem("ev-user-id", uid);
    }
    return uid;
  }

  async function loadFavorites() {
    try {
      const r = await fetch(`${API}/api/favorites/${getUserId()}`);
      state.favorites = r.ok ? await r.json() : [];
    } catch(_) { state.favorites = []; }
  }

  async function toggleFavorite(station) {
    const existing = state.favorites.find(f => Math.abs(f.lat - station.lat) < 0.0005);
    if (existing) {
      await fetch(`${API}/api/favorites/${existing.id}?userId=${getUserId()}`, { method: "DELETE" });
      state.favorites = state.favorites.filter(f => f.id !== existing.id);
    } else {
      const resp = await fetch(`${API}/api/favorites`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          userId: getUserId(), name: station.name, address: station.address,
          lat: station.lat, lon: station.lon, maxEffKw: station.maxEffKw,
          connectorType: station.connectorType, operator: station.operator
        })
      });
      if (resp.ok) { const saved = await resp.json(); if (saved) state.favorites.push(saved); }
    }
    if (state.lastData) renderResults(state.lastData);
  }

  loadFavorites();

  // Splashen kan inte överbrygga en kallstart som tar två minuter. Uppmätt 2026-08-20:
  // uppvakningen tog 121 s, och splashens tak brann av efter 45 s — mellan 51 s och 121 s
  // stod sidan färdigladdad med en rullgardin som sa "Välj bilmodell…" och innehöll noll
  // bilar. Det är samma fel som splashen byggdes mot, ett lager längre ned: väntan syns
  // inte, och då läser den som att tjänsten är trasig.
  //
  // Utbruten som egen funktion för att gå att prova: den returnerar sin egen återställare
  // i stället för att någon annan ska minnas vad som stod där förut.
  function bilVantetext(sel) {
    if (!sel || !sel.options || !sel.options.length) return null;
    const ursprunglig = sel.options[0].textContent;
    sel.options[0].textContent = "Tjänsten startar — bilarna dyker upp strax…";
    return function () { sel.options[0].textContent = ursprunglig; };
  }

  // Texten ska inte blinka förbi på en vaken tjänst; hinner svaret före tröskeln syns den
  // aldrig. Uppmätt varmt svar är ~2 s, alltså räcker inte en tröskel på någon tiondel.
  const VANTETEXT_MS = 1200;
  let aterstallBilText = null;
  const bilVantetimer = setTimeout(function () {
    aterstallBilText = bilVantetext(document.getElementById("ev-car-select"));
    // Väntetexten satt i <select>:en, och den är DOLD sedan märkesväljaren tog över — alltså
    // låg hela kallstartsbeskedet på ett element ingen ser. Skarpt fall 2026-08-28: en
    // användare tryckte på "Välj bilmärke" och ingenting hände, för väljaren avstår från att
    // öppna innan bilarna finns (`laddar`) och avtryckaren såg exakt likadan ut färdig som
    // väntande. Uppvakningen är mätt till 121 s, så det fönstret är inte litet.
    if (markesvaljare) markesvaljare.vantar();
  }, VANTETEXT_MS);

  // ---------------------------------------------------------------------------
  // MÄRKESVÄLJAREN: bilmärke först, modellerna sedan
  //
  // Rullgardinen bar 462 bilar i EN lista. Att hitta sin egen bil betydde att rulla förbi
  // 31 andra Mercedes-modeller, och märkena låg utspridda mitt bland modellnamnen. Nu är
  // det två steg: 64 märken i ett rutnät, och först när man valt märke visas det märkets
  // modeller.
  //
  // BYGGD HELT I JS, MED FLIT. WP-sidan är en manuell kopia av elbilsladdning-web.html —
  // ny markup där syns inte förrän sidan klistras om för hand. Väljaren skapas därför ur
  // den <select> som redan finns: selecten blir kvar i DOM:en (dold) och är fortfarande
  // sanningen. Väljaren sätter `sel.value` och skickar ett "change"-event, så allt nedanför
  // — state.carIndex, renderSpecs, fetchAndRender — körs oförändrat och märker aldrig att
  // gränssnittet bytts ut. Samma skäl som bakom att stilarna injiceras i injectStyles.
  // ---------------------------------------------------------------------------

  // Handplockade färger för de märken vi har flest bilar av; resten får en färg ur paletten
  // via namnets teckensumma, så samma märke alltid får samma färg mellan sidladdningar.
  const MARKESFARG = {
    "Tesla": "#e11d48", "BMW": "#0ea5e9", "Mercedes-Benz": "#94a3b8", "Volkswagen": "#3b82f6",
    "Audi": "#ef4444", "Volvo": "#60a5fa", "Polestar": "#e2e8f0", "Kia": "#f43f5e",
    "Hyundai": "#38bdf8", "Škoda": "#22c55e", "Porsche": "#eab308", "Renault": "#facc15",
    "Peugeot": "#3b82f6", "Citroën": "#ef4444", "Opel": "#f59e0b", "Ford": "#2563eb",
    "Toyota": "#dc2626", "Nissan": "#f87171", "MG": "#f43f5e", "BYD": "#ef4444",
    "Mini": "#fbbf24", "Fiat": "#a3e635", "CUPRA": "#f97316", "Smart": "#facc15",
    "Lexus": "#cbd5e1", "Mazda": "#60a5fa", "Honda": "#f87171", "Subaru": "#818cf8",
    "Dacia": "#4ade80", "Jeep": "#a3e635", "Alpine": "#38bdf8", "XPENG": "#22d3ee"
  };
  const RESERVPALETT = ["#3b82f6", "#22c55e", "#f59e0b", "#818cf8", "#ec4899",
                        "#14b8a6", "#f97316", "#a78bfa", "#06b6d4", "#84cc16"];

  function markesfarg(marke) {
    if (MARKESFARG[marke]) return MARKESFARG[marke];
    let sum = 0;
    for (let i = 0; i < marke.length; i++) sum += marke.charCodeAt(i);
    return RESERVPALETT[sum % RESERVPALETT.length];
  }

  /**
   * Märket ur bilnamnet. Namnen bär inget eget märkesfält — ev_spec har bara car_name —
   * så första ordet får duga, med tre uppmätta undantag bland de 462 namnen:
   *   "Alfa Romeo Junior…"  första ordet ger "Alfa", som inte är ett märke
   *   "MG4 Standard"        egen grupp bredvid "MG" — 5 bilar som hörde hemma bland de 15
   *   "firefly …"           gemener, hamnade sist i en skiftlägeskänslig sortering
   */
  function markeAv(namn) {
    const n = (namn || "").trim();
    if (/^Alfa\s+Romeo/i.test(n)) return "Alfa Romeo";
    const forsta = n.split(/\s+/)[0] || "";
    if (/^MG\d/i.test(forsta)) return "MG";
    if (forsta.toLowerCase() === "firefly") return "Firefly";
    return forsta;
  }

  // Modellnamnet utan märkesprefix — men BARA när prefixet faktiskt står där. "MG4 Trophy"
  // hör till märket MG utan att börja på "MG ", och att korta den till "4 Trophy" hade
  // gjort raden obegriplig.
  function modellAv(namn, marke) {
    const n = (namn || "").trim();
    return n.toLowerCase().startsWith(marke.toLowerCase() + " ") ? n.slice(marke.length + 1) : n;
  }

  // ── Volvos namnbyten ────────────────────────────────────────────────────────
  // C40 Recharge heter EC40 och XC40 Recharge heter EX40 på nyare årsmodeller.
  //
  // BÅDA NAMNEN STÅR KVAR I DATAN, med flit. En två-tre år gammal bil — alltså precis den
  // man köper begagnad — heter fortfarande det gamla namnet i annonsen, och specarna är
  // INTE samma: XC40 Recharge ligger på 75 kWh och 150 kW DC, EX40 på 79 kWh och 207 kW.
  // Att döpa om raden hade alltså gett en begagnad bil fel laddeffekt, och laddeffekten är
  // hela poängen med den här appen. De är samma modellinje i olika åldrar, inte dubbletter.
  //
  // Aliaset gör bara att man HITTAR rätt oavsett vilket namn man känner till: raden visar
  // det andra namnet, och sökningen matchar på båda.
  const NAMNBYTEN = [
    { ny: "EC40", gammal: "C40 Recharge", gammaltOrd: "C40" },
    { ny: "EX40", gammal: "XC40 Recharge", gammaltOrd: "XC40" }
  ];

  function namnbyteFor(namn) {
    const n = namn || "";
    for (const b of NAMNBYTEN) {
      if (new RegExp("\\b" + b.ny + "\\b", "i").test(n))
        return { notis: "samma bil som " + b.gammal + " — Volvo bytte namn", ocksa: b.gammaltOrd };
      // Bara modellordet, INTE "C40 Recharge": raderna heter "Volvo C40 Single Motor" och
      // "Volvo XC40 Recharge" — ordet Recharge står bara på den ena, och provet fällde den
      // första varianten direkt. Ordgränsen räcker för att hålla isär namnen: \bC40\b matchar
      // inte inuti "EC40", eftersom E är ett ordtecken.
      if (new RegExp("\\b" + b.gammaltOrd + "\\b", "i").test(n))
        return { notis: "heter " + b.ny + " på nyare årsmodeller", ocksa: b.ny };
    }
    return null;
  }

  // Emblemet är märkets initialer. Riktiga bilmärkesloggor är varumärkesskyddade och finns
  // inte i repot — en monogramplatta i märkets färg ger igenkänningen utan att hämta bilder
  // från någon annans server.
  function emblemText(marke) {
    if (/^[A-ZÅÄÖ&]{2,4}$/.test(marke)) return marke.slice(0, 3);   // BMW, MG, BYD, GWM, NIO
    const ord = marke.split(/[\s-]+/).filter(Boolean);
    if (ord.length > 1) return (ord[0][0] + ord[1][0]).toUpperCase();
    return marke.slice(0, 2).toUpperCase();
  }

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;")
                    .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  // ── Riktiga emblem för de märken där det GÅR ────────────────────────────────
  // Nio av de femton största. Filerna ligger i repot och serveras av samma värd som den här
  // filen (ASSETS), alltså ingen hotlinkning till Wikimedia — appen lider redan av kallstarter
  // och ska inte lägga till ett tredjepartsberoende i sidladdningen.
  //
  // Alla nio är PUBLIC DOMAIN på Commons (formen är för enkel för upphovsrätt). Commons märker
  // dem samtidigt "trademarked": det är varumärket, inte licensen, och att visa märket intill
  // just den bilen är den beskrivande användning varje bilsajt gör.
  //
  // FYRA av de femton fick INGET emblem, och det är mätt och inte glömt:
  //   XPENG, Zeekr — märkets logo ÄR ett ordmärke, och i 24 px blir bokstäverna mos.
  //   Peugeot      — lejonet är för komplext för att vara fritt från upphovsrätt, så
  //                  Commons har ingen PD-version alls. Bara ordmärket, oläsligt i 24 px.
  //   Hyundai      — H-ovalen finns bara ihop med ordmärket. Filen som hette
  //                  "Hyundai Symbol.svg" var dessutom en GRÖN TRIANGEL, alltså inte
  //                  Hyundais logo alls — ett fel emblem är sämre än inget.
  //   Kia och Citroën fick sina 2026-08-28 i andra omgången: Kias ordmärke ÄR läsbart i
  //   24 px (breda bokstäver, hög kontrast) och Citroëns dubbelchevron finns som ren
  //   symbol. Båda provade på kontaktark innan de togs in.
  // Alla sex behåller monogrammet, som har samma mått — rutnätet blir jämnt ändå.
  const MARKESEMBLEM = {
    "Mercedes-Benz": "mercedes", "Volkswagen": "volkswagen", "BMW": "bmw", "Škoda": "skoda",
    "Ford": "ford", "MG": "mg", "Audi": "audi", "Smart": "smart", "Tesla": "tesla",
    "Citroën": "citroen", "Kia": "kia", "Volvo": "volvo", "Toyota": "toyota",
    "Renault": "renault", "Opel": "opel", "BYD": "byd", "CUPRA": "cupra", "Mini": "mini",
    "NIO": "nio", "Polestar": "polestar", "Subaru": "subaru", "Alpine": "alpine",
    "Geely": "geely", "Jeep": "jeep", "KGM": "kgm", "VinFast": "vinfast", "Fiat": "fiat",
    "Dacia": "dacia", "Honda": "honda", "JAC": "jac", "Mitsubishi": "mitsubishi",
    "Rolls-Royce": "rollsroyce",  "Lexus": "lexus", 
     "GWM": "gwm",  
      "Nissan": "nissan", "Mazda": "mazda", "Suzuki": "suzuki",
       
    "Mercedes": "mercedes"
  };

  /**
   * Emblemplattan för ett märke — bild när vi har en, monogram annars.
   *
   * <p>Bildplattan är VIT. Kontaktarket avgjorde det: på den mörka plattan är Mercedes-stjärnan,
   * VW-ringen, Audi-ringarna och Fords ovala mörkblå nästan osynliga, eftersom de är gjorda för
   * att ligga på ljus botten. Vit platta är dessutom vad varje bilsajt gör, av samma skäl.
   */
  function emblemHtml(marke, extraKlass) {
    const slug = MARKESEMBLEM[marke];
    const klass = "ev-picker-emblem" + (extraKlass ? " " + extraKlass : "");
    if (slug) {
      return '<span class="' + klass + ' ev-picker-emblem-bild">'
        + '<img src="' + ASSETS + '/ev-emblem/' + slug + '.svg" alt="" loading="lazy"></span>';
    }
    return '<span class="' + klass + '" style="--emblem:' + markesfarg(marke) + '">'
      + esc(emblemText(marke)) + '</span>';
  }

  function skapaMarkesvaljare() {
    const sel = document.getElementById("ev-car-select");
    if (!sel) return null;
    sel.style.display = "none";

    const rot = document.createElement("div");
    rot.className = "ev-picker";
    rot.innerHTML =
      '<button type="button" class="ev-picker-trigger" aria-haspopup="listbox" aria-expanded="false">' +
        '<span class="ev-picker-emblem ev-picker-emblem-tom">⚡</span>' +
        '<span class="ev-picker-text">Välj bilmärke…</span>' +
        '<span class="ev-picker-chevron">▾</span>' +
      '</button>' +
      '<div class="ev-picker-panel" hidden>' +
        '<input type="text" class="ev-picker-search" placeholder="Sök märke eller modell — skriv T för Tesla" autocomplete="off">' +
        '<div class="ev-picker-steps">' +
          '<div class="ev-picker-step ev-picker-brands"></div>' +
          '<div class="ev-picker-step ev-picker-models"></div>' +
        '</div>' +
      '</div>';
    sel.parentNode.insertBefore(rot, sel.nextSibling);

    const trigger = rot.querySelector(".ev-picker-trigger");
    const panel   = rot.querySelector(".ev-picker-panel");
    const steps   = rot.querySelector(".ev-picker-steps");
    const sok     = rot.querySelector(".ev-picker-search");
    const brands  = rot.querySelector(".ev-picker-brands");
    const models  = rot.querySelector(".ev-picker-models");

    let bilar = [];
    let marken = [];            // [{ marke, bilar: [{ index, namn, modell, spec }] }]
    let aktivtMarke = null;
    let laddar = true;

    function stang() {
      panel.hidden = true;
      rot.classList.remove("ev-picker-open");
      trigger.setAttribute("aria-expanded", "false");
    }
    // Väntetexten på avtryckaren. Bor här och inte bara i det returnerade objektet, eftersom
    // BÅDA vägarna behöver den: kallstartstimern utifrån, och ett klick medan bilarna hämtas.
    function visaVantetext() {
      trigger.querySelector(".ev-picker-text").textContent =
        "Tjänsten startar — bilarna dyker upp strax…";
      // Klassen bär hela väntelaget (snurran, svepet, skimret). Den sitter på ROTEN och
      // inte på avtryckaren, eftersom valj() byter ut avtryckarens innehåll — en klass
      // satt där hade följt med i tvätten.
      rot.classList.add("ev-picker-vantar");
    }
    // Väntan har exakt ETT slut, och båda utgångarna nedan går igenom den här: texten och
    // rörelsen ska aldrig kunna sluta på olika ställen.
    function slutaVanta() {
      rot.classList.remove("ev-picker-vantar");
    }

    function oppna() {
      // Ett klick medan bilarna hämtas ska SÄGA det. Att bara returnera gjorde knappen död
      // utan förklaring, och det var precis så felet visade sig för användaren: "trycker på
      // välj bilmärke, ingenting händer".
      if (laddar) { visaVantetext(); return; }
      panel.hidden = false;
      rot.classList.add("ev-picker-open");
      trigger.setAttribute("aria-expanded", "true");
      sok.value = "";
      aktivtMarke = null;
      ritaMarken("");
      visaSteg(1);
      // Fokus först när panelen målats, annars scrollar mobilen till fel läge
      setTimeout(function () { sok.focus({ preventScroll: true }); }, 30);
    }
    function visaSteg(n) {
      steps.classList.toggle("ev-picker-at-models", n === 2);
    }

    function ritaMarken(filter) {
      const f = (filter || "").trim().toLowerCase();
      // EN bokstav betyder "hoppa till bokstaven" — märken som BÖRJAR på den, inget annat.
      // Uppmätt i harnessen: med samma delsträngsregel som för längre sökningar gav "T"
      // 48 av 63 märken, eftersom nästan varje märke har ett t någonstans i sina modellnamn.
      // Det är raka motsatsen till vad ett bokstavstryck ska göra.
      //
      // Från två tecken och uppåt matchar filtret märket ELLER någon av dess modeller:
      // skriver man "enyaq" ska Škoda stå kvar, annars ser det ut som att bilen inte finns.
      const traffar = marken.filter(function (m) {
        if (!f) return true;
        const namn = m.marke.toLowerCase();
        if (f.length === 1) return namn.charAt(0) === f;
        return namn.indexOf(f) !== -1
            || m.bilar.some(function (b) { return b.sok.indexOf(f) !== -1; });
      });
      if (!traffar.length) {
        brands.innerHTML = '<div class="ev-picker-tom">Ingen bil matchar “' + esc(f) + '”.</div>';
        return;
      }
      brands.innerHTML = '<div class="ev-picker-grid">' + traffar.map(function (m) {
        return '<button type="button" class="ev-picker-brand" data-marke="' + esc(m.marke) + '">' +
          emblemHtml(m.marke) +
          // Texten i en egen kolumn: som syskon till emblemet i samma flexrad hamnade namn
          // och antal bredvid varandra, och "Alfa Romeo" och "Citroën" bröt då över två rader
          // med antalet hängande i luften.
          '<span class="ev-picker-brand-txt">' +
            '<span class="ev-picker-brand-name">' + esc(m.marke) + '</span>' +
            '<span class="ev-picker-brand-count">' + m.bilar.length + (m.bilar.length === 1 ? ' modell' : ' modeller') + '</span>' +
          '</span>' +
        '</button>';
      }).join("") + '</div>';
    }

    function ritaModeller(marke, filter) {
      const grupp = marken.find(function (m) { return m.marke === marke; });
      if (!grupp) return;
      aktivtMarke = marke;
      const f = (filter || "").trim().toLowerCase();
      const lista = grupp.bilar.filter(function (b) { return !f || b.sok.indexOf(f) !== -1; });
      models.innerHTML =
        '<div class="ev-picker-back-row">' +
          '<button type="button" class="ev-picker-back">‹ Alla märken</button>' +
          emblemHtml(marke, "ev-picker-emblem-sm") +
          '<span class="ev-picker-brand-head">' + esc(marke) + '</span>' +
        '</div>' +
        '<div class="ev-picker-model-legend">Räckvidden är <b>verklig</b> — WLTP minus 15 %, samma avdrag som resten av appen.</div>' +
        '<div class="ev-picker-model-list">' + lista.map(function (b) {
          // Saknas talet sägs det rakt ut. En rad som bara tappar milen ser identisk ut med
          // en bil som inte finns i datan, och då låter felet som ett designval.
          const mil = b.mil
            ? '<b class="ev-picker-model-mil">~' + b.mil + ' mil</b> verklig'
            : '<span class="ev-picker-model-saknas">räckvidd saknas</span>';
          return '<button type="button" class="ev-picker-model" data-index="' + b.index + '">' +
            '<span class="ev-picker-model-name">' + esc(b.modell) + '</span>' +
            '<span class="ev-picker-model-specs">' + mil + (b.batteri ? ' · ' + esc(b.batteri) : '') + '</span>' +
            '<span class="ev-picker-model-ladd">' + esc(b.ladd) + '</span>' +
            (b.notis ? '<span class="ev-picker-model-alias">' + esc(b.notis) + '</span>' : '') +
          '</button>';
        }).join("") + '</div>';
    }

    // Klicken tas emot på panelen i stället för på varje knapp: rutnätet ritas om vid varje
    // tangenttryckning, och lyssnare per knapp hade behövt sättas om lika ofta.
    panel.addEventListener("click", function (e) {
      const brandBtn = e.target.closest(".ev-picker-brand");
      if (brandBtn) { sok.value = ""; ritaModeller(brandBtn.dataset.marke, ""); visaSteg(2); sok.focus({ preventScroll: true }); return; }
      if (e.target.closest(".ev-picker-back")) { sok.value = ""; aktivtMarke = null; ritaMarken(""); visaSteg(1); return; }
      const modelBtn = e.target.closest(".ev-picker-model");
      if (modelBtn) { valj(parseInt(modelBtn.dataset.index, 10)); stang(); }
    });

    sok.addEventListener("input", function () {
      // Söker man medan ett märke är öppet filtreras det märkets modeller. Blir sökningen
      // tom igen backar vi ut till rutnätet — annars sitter man fast i ett märke.
      if (aktivtMarke && steps.classList.contains("ev-picker-at-models")) {
        if (!this.value.trim()) { aktivtMarke = null; ritaMarken(""); visaSteg(1); return; }
        ritaModeller(aktivtMarke, this.value);
        return;
      }
      ritaMarken(this.value);
    });

    sok.addEventListener("keydown", function (e) {
      if (e.key === "Escape") { stang(); trigger.focus(); return; }
      if (e.key !== "Enter") return;
      e.preventDefault();
      // Enter när bara ett märke är kvar öppnar det direkt — "tesla" + Enter är hela vägen
      // fram utan att lyfta handen från tangentbordet.
      if (!steps.classList.contains("ev-picker-at-models")) {
        const kvar = brands.querySelectorAll(".ev-picker-brand");
        if (kvar.length === 1) { this.value = ""; ritaModeller(kvar[0].dataset.marke, ""); visaSteg(2); }
        return;
      }
      const enda = models.querySelectorAll(".ev-picker-model");
      if (enda.length === 1) { valj(parseInt(enda[0].dataset.index, 10)); stang(); }
    });

    trigger.addEventListener("click", function () { if (panel.hidden) oppna(); else stang(); });
    document.addEventListener("click", function (e) {
      if (!panel.hidden && !rot.contains(e.target)) stang();
    });

    function valj(index) {
      if (isNaN(index)) return;
      const bil = bilar[index];
      if (!bil) return;
      const marke = markeAv(bil.name);
      trigger.innerHTML =
        emblemHtml(marke) +
        '<span class="ev-picker-text ev-picker-vald">' + esc(bil.name) + '</span>' +
        '<span class="ev-picker-chevron">▾</span>';
      // Selecten är fortfarande sanningen: allt nedanför lyssnar på DEN, inte på väljaren.
      sel.value = String(index);
      sel.dispatchEvent(new Event("change"));
    }

    return {
      fyll: function (cars) {
        bilar = cars;
        laddar = false;
        // Nyckeln är SKIFTLÄGESFÄLLD och visningsnamnet den stavning som förekommer flest
        // gånger. De 58 rader som lades till 2026-08-28 stavade två märken annorlunda än de
        // gamla, och rutnätet visade dem som skilda märken: "Cupra 1 modell" bredvid
        // "CUPRA 9 modeller", "Xpeng 1" bredvid "XPENG 12". Ett märke får aldrig stå två gånger.
        const karta = new Map();
        const stavningar = new Map();   // nyckel -> Map(stavning -> antal)
        cars.forEach(function (c, i) {
          const marke = markeAv(c.name);
          const nyckel = marke.toLowerCase();
          if (!stavningar.has(nyckel)) stavningar.set(nyckel, new Map());
          const r = stavningar.get(nyckel);
          r.set(marke, (r.get(marke) || 0) + 1);
          if (!karta.has(nyckel)) karta.set(nyckel, []);
          const bat = c.batteryKwh
            ? (Number.isInteger(c.batteryKwh) ? c.batteryKwh : c.batteryKwh.toFixed(1)) + " kWh" : "";
          // Verklig räckvidd på VARJE rad, inte WLTP: WLTP-talet är det man blir besviken
          // på, och samma 15 %-avdrag används redan i "Din elbil" och i laddkalkylen.
          // Samma funktion som där — inte en fjärde kopia av 0.85.
          const mil = verkligaMil(c.rangeKm);
          const byte = namnbyteFor(c.name);
          karta.get(nyckel).push({
            index: i, namn: c.name, modell: modellAv(c.name, marke),
            mil: mil, batteri: bat,
            // Tre bilar (Zoe 22, Zoe ZE40, R5 40 kWh 95 hk) har maxDcKw 0. "DC 0 kW" läste
            // som en mätning som gått fel; de kan helt enkelt inte snabbladdas, och det är
            // en sak man vill veta INNAN man väljer bilen.
            ladd: "AC " + c.maxAcKw + " kW · " + (c.maxDcKw ? "DC " + c.maxDcKw + " kW" : "ingen snabbladdning"),
            notis: byte ? byte.notis : "",
            // Söktexten bär BÅDA namnen: den som letar "XC40" ska hitta EX40 och tvärtom.
            sok: (c.name + " " + (byte ? byte.ocksa : "")).toLowerCase()
          });
        });
        // localeCompare med "sv": utan den hamnar Škoda efter Zeekr och firefly allra sist.
        marken = Array.from(karta).map(function (par) {
          const vanligast = Array.from(stavningar.get(par[0]))
            .sort(function (a, b) { return b[1] - a[1]; })[0][0];
          return { marke: vanligast, bilar: par[1] };
        }).sort(function (a, b) { return a.marke.localeCompare(b.marke, "sv"); });
        slutaVanta();
        trigger.querySelector(".ev-picker-text").textContent = "Välj bilmärke…";
        trigger.classList.add("ev-picker-klar");
      },
      vantar: visaVantetext,
      fel: function () {
        laddar = true;
        // Snurran måste bort HÄR också: ett besked om att hämtningen misslyckats som
        // fortsätter snurra säger två motsatta saker samtidigt.
        slutaVanta();
        trigger.querySelector(".ev-picker-text").textContent = "Kunde inte hämta bilar";
      }
    };
  }

  const markesvaljare = skapaMarkesvaljare();

  fetch(API + "/api/cars")
    .then(r => r.json())
    .then(cars => {
      state.cars = cars;
      clearTimeout(bilVantetimer);
      const sel = document.getElementById("ev-car-select");
      if (aterstallBilText) { aterstallBilText(); aterstallBilText = null; }
      cars.forEach((c, i) => {
        const o = document.createElement("option");
        const bat = c.batteryKwh ? (Number.isInteger(c.batteryKwh) ? c.batteryKwh : c.batteryKwh.toFixed(1)) + ' kWh · ' : '';
        const mil = verkligaMil(c.rangeKm);
        const dc  = c.maxDcKw ? `DC ${c.maxDcKw} kW` : 'ingen snabbladdning';
        o.value = i; o.textContent = `${c.name}  (${mil ? `~${mil} mil verklig · ` : ''}${bat}AC ${c.maxAcKw} kW · ${dc})`;
        sel.appendChild(o);
      });
      if (markesvaljare) markesvaljare.fyll(cars);
      evDataKlar();
    })
    .catch(() => {
      clearTimeout(bilVantetimer);
      document.getElementById("ev-car-select").innerHTML = "<option>Kunde inte hämta bilar</option>";
      if (markesvaljare) markesvaljare.fel();
      evDataKlar();
    });

  fetch(API + "/api/ev-sales-rank")
    .then(r => r.json())
    .then(rows => { if (Array.isArray(rows)) state.evSalesRank = rows; })
    .catch(() => {})
    // Rita om tipsen när försäljningstoppen hunnit hem, så den dynamiska raden kommer med.
    // renderTipsOnly avstår själv om en sökning redan hunnit rendera.
    .finally(() => renderTipsOnly());

  // Värdetappslistan: nypris ur Kvdbils artikel, medianpriset räknat på Blocket varje vecka.
  // Tom före första synken — då visas varken fyndtabellen eller faktaraden.
  fetch(API + "/api/value-retention")
    .then(r => r.json())
    .then(d => {
      if (d && Array.isArray(d.modeller)) {
        state.valueRetention = d.modeller;
        state.valueRetentionKalla = d.nyprisKalla || "";
      }
    })
    .catch(() => {})
    .finally(() => renderTipsOnly());

  // Tipsen syns direkt, utan bil och utan position. Väntar vi på användaren möts hen av en
  // tom yta, och de statiska fakta är läsvärda i sig.
  renderTipsOnly();

  document.getElementById("ev-car-select").addEventListener("change", function () {
    const idx = parseInt(this.value);
    state.carIndex = isNaN(idx) ? null : idx;
    renderSpecs();
    if (state.lat !== null && state.carIndex !== null) fetchAndRender();
  });

  function renderSpecs() {
    const box = document.getElementById("ev-specs");
    if (state.carIndex === null) { box.style.display = "none"; renderChargingNotice(false); return; }
    const c = state.cars[state.carIndex];
    const rangeMil  = c.rangeKm ? Math.round(c.rangeKm / 10) : null;
    const realMil   = rangeMil ? Math.round(rangeMil * 0.85) : null;
    const freqBadge = chargingFreqBadge(rangeMil);
    const priceStr  = c.priceKr ? `från ${(c.priceKr / 1000).toFixed(0)} tkr` : null;
    // TRE RADER, inte en radbrytande. Allt låg förut i samma flexrad, och radbrytningen
    // hamnade där bredden råkade ta slut — mellan "mil WLTP" och "mil verklig" på en smal
    // skärm, eller mitt i kontakttyperna. Läsaren fick alltså gruppera själv, och grupperna
    // finns: hur bilen laddar, hur långt den går, vad den kostar.
    //
    // Ordningen är inte alfabetisk utan efter vad appen handlar om: LADDNING först (effekt,
    // kontakter och hur ofta man måste ladda hör ihop — det är samma fråga), sedan räckvidd,
    // sist pris. Ikonerna är där för att hitta rätt rad utan att läsa.
    // "grid" och inte "flex": inline-stilen används som visa/göm-knapp här, och den slår all
    // CSS — sattes den till flex vann den över radrutnätet nedan.
    box.style.display = "grid";
    box.classList.add("ev-specs-rader");
    const laddrad = [
      `<span class="ev-spec-badge badge-ac" title="Toppeffekt från laddbox. Taket sitter i bilens ombordladdare – en kraftigare laddbox ger ändå inte mer än så här mycket.">🏠 AC max ${c.maxAcKw} kW</span>`,
      `<span class="ev-spec-badge badge-dc" title="Toppeffekt vid publik snabbladdare. Verklig effekt beror på batteriets temperatur och laddnivå, och på vad stolpen klarar.">⚡ DC max ${c.maxDcKw} kW</span>`,
      ...c.connectors.map(t => `<span class="ev-spec-badge badge-con">🔌 ${conLabel(t)}</span>`),
      freqBadge ? `<span class="ev-spec-badge badge-freq">${freqBadge}</span>` : ""
    ].filter(Boolean).join("");

    // Rubrik till vänster om varje rad. Badgesarna säger redan "AC max" och "mil WLTP", men
    // rubriken svarar på en annan fråga: vad den HÄR raden handlar om. Utan den måste man läsa
    // innehållet för att förstå varför tre rader står under varandra.
    const rader = [["Laddning", laddrad]];
    // Måttband och inte vägemoji: 🛣️ renderar som en liten landskapsbild i Chrome och läste
    // som ett trasigt ikonplacehold, medan 📏 säger "avstånd" och håller sig läsbar i 12 px.
    if (rangeMil) rader.push(["Räckvidd",
      `<span class="ev-spec-badge badge-range">📏 ~${rangeMil} mil WLTP · ~${realMil} mil verklig</span>`]);
    if (priceStr) rader.push(["Pris",
      `<span class="ev-spec-badge badge-price">💰 ${priceStr}</span>`]);
    box.innerHTML = rader.map(function (r) {
      return `<span class="ev-spec-rubrik">${r[0]}</span><div class="ev-spec-row">${r[1]}</div>`;
    }).join("");
    renderChargingNotice(true);
  }

  /**
   * Utfallbar forklaring av AC max / DC max, portad fran CarAdvice (car-advice-main.js
   * ca-charging-notice). Talen har alltid statt i badgesarna men aldrig forklarats, och en
   * anvandare som inte vet vad de betyder kan inte anvanda dem for att valja bil.
   *
   * Inbyggd <details> och ingen list-style:none - den inbyggda triangeln ar det enda som
   * visar att rutan gar att falla ut, och den vander sig sjalv nar den oppnas. Stangd som
   * default: den som redan vet ska inte behova scrolla forbi en textvagg varje gang.
   */
  function renderChargingNotice(show) {
    const id = "ev-charging-notice";
    const existing = document.getElementById(id);
    if (!show) { if (existing) existing.parentNode.removeChild(existing); return; }
    if (existing) return;

    const box = document.getElementById("ev-specs");
    if (!box || !box.parentNode) return;

    const el = document.createElement("details");
    el.id = id;
    el.setAttribute("style", "margin:12px 0 0;padding:10px 14px;background:rgba(56,189,248,.06);" +
      "border:1px solid rgba(56,189,248,.28);border-radius:10px;font-size:.82rem;line-height:1.55;" +
      "color:rgba(255,255,255,.78)");
    el.innerHTML =
      '<summary style="cursor:pointer;color:#38bdf8;font-weight:600">' +
        '&#x26A1; Vad betyder DC max och AC max?</summary>' +
      '<div style="margin-top:10px">' +
        '<strong style="color:rgba(255,255,255,.92)">DC max &mdash; likström, snabbladdning</strong><br>' +
        'Högsta effekt bilen kan ta emot vid en publik snabbladdare. Taket sätts av batteriets ' +
        'kemi, temperatur och hälsa, och ligger i praktiken mellan ca 50 kW för äldre eller ' +
        'enklare modeller och 250&#x2013;350 kW för modern snabbladdningsteknik. Högre värde ger ' +
        'betydligt kortare stopp på långresa &#x2013; typiskt 10&#x2013;80&nbsp;% på 20&#x2013;30 minuter &#x2013; ' +
        'förutsatt att laddstolpen kan leverera lika mycket.' +
        '<div style="height:8px"></div>' +
        '<strong style="color:rgba(255,255,255,.92)">AC max &mdash; växelström, normalladdning</strong><br>' +
        'Högsta effekt bilen klarar från en laddbox eller normalladdstolpe. Den gränsen sitter i ' +
        'bilens interna ombordladdare, inte i elen: vanliga värden är 11 kW (trefas 16&nbsp;A) och ' +
        '22 kW (trefas 32&nbsp;A). Har bilen AC max 11 kW spelar det ingen roll om laddboxen klarar ' +
        '22 kW &#x2013; bilen laddar ändå i högst 11 kW.' +
        '<div style="height:8px"></div>' +
        '<strong style="color:rgba(255,255,255,.92)">Varför AC max sällan avgör valet</strong><br>' +
        'AC-laddning sker nästan alltid hemma eller på jobbet, och då står bilen parkerad i ' +
        'timmar ändå. 11 kW fyller ett normalstort batteri på 6&#x2013;8 timmar, alltså över en ' +
        'natt &#x2013; att bilen skulle klara 22 kW ändrar inget när den står stilla till morgonen. ' +
        'De flesta svenska hemmainstallationer ger dessutom 11 kW; 22 kW kräver särskild el dragen ' +
        'till huset.' +
        '<div style="height:6px"></div>' +
        'Viktigare att jämföra är <strong>DC-effekten</strong> (hur korta pauserna blir på ' +
        'långresa), <strong>räckvidden</strong> (hur ofta du behöver stanna alls) och ' +
        '<strong>förbrukningen per mil</strong> (vad bilen kostar att äga över tid).' +
        '<div style="height:8px"></div>' +
        '<span style="color:rgba(255,255,255,.55)">Båda talen är toppeffekt under ideala ' +
        'förhållanden. Verklig effekt sjunker med kallt batteri och stigande laddnivå &#x2013; ' +
        'sista biten till 100&nbsp;% är alltid långsam. Ruttplaneraren här räknar redan med ' +
        'bilens DC max mot vad varje stolpe klarar, och tar det lägsta av de två.</span>' +
      '</div>';
    box.parentNode.insertBefore(el, box.nextSibling);
  }

  function conLabel(t) { return { type2:"Type 2", ccs:"CCS", chademo:"CHAdeMO" }[t] || t; }

  document.getElementById("ev-daily-mil").addEventListener("input", renderSpecs);

  // Årlig körsträcka förvald till den genomsnittliga svenskens 1 243 mil.
  //
  // Fältet stod tomt, och laddfrekvensbadgen ("ladda var 4:e dag") är den enda uppgiften i
  // hela specraden som KRÄVER en körsträcka — chargingFreqBadge returnerar null utan den.
  // Alltså saknade badgen tills man råkade fylla i rutan, och en bil man valde såg ut att
  // sakna uppgiften helt. Ett förval gör att raden alltid renderas komplett, och siffran är
  // inte gissad: den är riksgenomsnittet, alltså rätt svar för den som inte vet sitt eget.
  //
  // Skriver användaren något eget rörs det aldrig — förvalet sätts bara i ett tomt fält, så
  // en ifylld siffra överlever att skriptet körs om.
  //
  // step=1 sätts här: markupen har step=100, och 1243 är inte en multipel av 100. Talet blir
  // då stepMismatch, vilket gör fältet :invalid och får pilarna att hoppa till 1200/1300.
  const SVENSK_SNITTMIL = 1243;
  (function forvaljKorstracka() {
    const f = document.getElementById("ev-daily-mil");
    if (!f) return;
    f.step = "1";
    if (!f.value) f.value = String(SVENSK_SNITTMIL);
    const hint = document.createElement("div");
    hint.className = "ev-mil-hint";
    hint.innerHTML = "Förvalt: <b>1 243 mil/år</b> — genomsnittssvensken. Ändra till din egen siffra.";
    f.parentNode.insertBefore(hint, f.nextSibling);
  })();

  function chargingFreqBadge(rangeMil) {
    const annualMil = parseFloat(document.getElementById("ev-daily-mil").value);
    if (!annualMil || isNaN(annualMil) || !rangeMil) return null;
    const dailyMil = annualMil / 365;
    const usableMil = rangeMil * 0.85 * 0.60;
    const days = usableMil / dailyMil;
    let label;
    if (days < 1)        label = `${Math.ceil(7 / days)} ggr/vecka`;
    else if (days < 1.5) label = "ladda varje dag";
    else if (days < 2.5) label = "ladda varannan dag";
    else                 label = `ladda var ${Math.round(days)}:e dag`;
    return `🔋 ${label}`;
  }

  document.querySelectorAll("[data-sort]").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("[data-sort]").forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      state.sort = btn.dataset.sort;
      if (state.lat !== null && state.carIndex !== null) fetchAndRender();
    });
  });

  document.querySelectorAll(".ev-filter-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".ev-filter-btn").forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      state.filter = btn.dataset.filter;
      if (state.lastData) renderResults(state.lastData);
    });
  });

  function setLoc(type, text) {
    document.getElementById("ev-loc-dot").className = "ev-location-dot " + type;
    document.getElementById("ev-loc-text").textContent = text;
  }

  if (!navigator.geolocation) {
    setLoc("error", "GPS stöds inte i denna webbläsare");
    setOutput('<div class="ev-status">GPS ej tillgänglig.</div>');
  } else {
    navigator.geolocation.getCurrentPosition(
      async pos => {
        state.lat = pos.coords.latitude; state.lon = pos.coords.longitude;
        setLoc("", "Position hittad");
        reverseGeocode(state.lat, state.lon);
        if (state.carIndex !== null) fetchAndRender();
        else setOutput('<div class="ev-status">Välj din bilmodell ovan för att se laddstationer.</div>');
      },
      () => {
        setLoc("error", "Kunde inte hämta GPS-position");
        setOutput('<div class="ev-status">Tillåt platsåtkomst i webbläsaren och ladda om sidan.</div>');
      },
      { enableHighAccuracy: true, timeout: 10000 }
    );
  }

  async function reverseGeocode(lat, lon) {
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lon}&format=json`,
        { headers: { "User-Agent": "EV-Laddning-App/1.0" } });
      const d = await r.json();
      const city = d.address?.city || d.address?.town || d.address?.village || d.address?.municipality || "";
      if (city) { state.city = city; setLoc("", city); }
    } catch (_) {}
  }

  async function fetchAndRender() {
    state.operatorFilter = null;
    setOutput('<div class="ev-status"><div class="ev-spinner"></div>Söker laddstationer och frågar AI…</div>');
    try {
      const cityParam = state.city ? `&city=${encodeURIComponent(state.city)}` : "";
      const url = `${API}/api/stations?lat=${state.lat}&lon=${state.lon}&carIndex=${state.carIndex}&sort=${state.sort}${cityParam}`;
      const resp = await fetch(url);
      if (!resp.ok) throw new Error("HTTP " + resp.status);
      renderResults(await resp.json());
    } catch (e) {
      setOutput(`<div class="ev-status">Fel: ${e.message}</div>`);
    }
  }

  const OPERATOR_URLS = {
    recharge:   "https://recharge.com/se/priser",
    ionity:     "https://ionity.eu/sv/tariffs.html",
    incharge:   "https://incharge.vattenfall.se/priser",
    circlek:    "https://www.circlek.se/elbil/laddning",
    tesla:      "https://www.tesla.com/sv_SE/supercharger",
    bee:        "https://bee-charging.com",
    eon:        "https://www.eon.se/elbil",
    clever:     "https://www.clever.dk/priser",
    mer:        "https://mer.eco/se/",
    allego:     "https://www.allego.eu/sv/",
    eviny:      "https://www.eviny.no/en/ev-charging/",
    st1:        "https://www.st1.se/tanka/el"
  };

  function operatorUrl(name) {
    if (!name) return null;
    const l = name.toLowerCase();
    if (l.includes("recharge"))                              return OPERATOR_URLS.recharge;
    if (l.includes("ionity"))                                return OPERATOR_URLS.ionity;
    if (l.includes("incharge") || l.includes("vattenfall")) return OPERATOR_URLS.incharge;
    if (l.includes("circle k") || l.includes("circlek"))    return OPERATOR_URLS.circlek;
    if (l.includes("tesla"))                                 return OPERATOR_URLS.tesla;
    if (l.includes("bee"))                                   return OPERATOR_URLS.bee;
    if (l.includes("e.on") || l.includes("eon"))             return OPERATOR_URLS.eon;
    if (l.includes("clever"))                                return OPERATOR_URLS.clever;
    if (l.includes("mer"))                                   return OPERATOR_URLS.mer;
    if (l.includes("allego"))                                return OPERATOR_URLS.allego;
    if (l.includes("eviny"))                                 return OPERATOR_URLS.eviny;
    if (l.includes("st1"))                                   return OPERATOR_URLS.st1;
    return null;
  }

  function renderResults(data) {
    state.lastData = data;
    const { carName, stations, recommendation, carFact } = data;

    const uniqueOps = [...new Set(
      stations
        .filter(s => s.operator && !s.operator.includes("Unknown") && s.operator.trim() !== "")
        .map(s => s.operator)
    )].sort();

    let visible = state.filter === "fast"
        ? stations.filter(s => s.connectorType.includes("DC") && s.maxEffKw >= 50)
        : stations;
    if (state.operatorFilter) visible = visible.filter(s => s.operator === state.operatorFilter);
    const top = visible.slice(0, 10);
    let html = "";
    let stationsHtml = "";
    let factHtml = "";

    const fastDC = stations
      .filter(s => s.maxEffKw >= 50 && s.connectorType.includes("DC"))
      .sort((a, b) => a.distanceKm - b.distanceKm)[0];

    if (fastDC) {
      const url  = operatorUrl(fastDC.operator);
      const dist = fastDC.distanceKm.toFixed(1);
      const kw   = Math.round(fastDC.maxEffKw);
      const nm   = fastDC.name.length > 42 ? fastDC.name.slice(0,40)+"…" : fastDC.name;
      const pris = fastDC.chargepricePerKwh || "";
      stationsHtml += `
        <div class="ev-fast-highlight">
          <div class="ev-fast-highlight-icon">⚡</div>
          <div class="ev-fast-highlight-body">
            <div class="ev-fast-highlight-label">Närmaste snabbladdare</div>
            <div class="ev-fast-highlight-title">${nm} · ${kw} kW</div>
            <div class="ev-fast-highlight-sub">${dist} km bort · ${fastDC.connectorType}${pris ? " · " + pris : ""}</div>
          </div>
          ${url ? `<a class="ev-fast-highlight-link" href="${url}" target="_blank" rel="noopener">Se priser →</a>` : ""}
        </div>`;
    }

    if (state.sort === "price") {
      const bat = state.carIndex !== null ? state.cars[state.carIndex]?.batteryKwh : null;
      const homeMin = bat ? Math.round(bat * 1.5) : null;
      const homeMax = bat ? Math.round(bat * 3.5) : null;
      const homeCostStr = homeMin ? ` · 🔋 Full laddning ~${homeMin}–${homeMax} kr` : "";
      html += `
        <div class="ev-home-tip">
          <div class="ev-home-tip-icon">🏠</div>
          <div><strong>Billigast: hemmaladdning</strong>
          <span> · ~1,50–3,50 kr/kWh beroende på elavtal${homeCostStr}. Ladda hemma om du kan!</span></div>
        </div>`;
    }

    if (recommendation) {
      html += `
        <div class="ev-ai-card">
          <div class="ev-ai-icon">🤖</div>
          <div style="flex:1">
            <div class="ev-ai-label" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              AI-rekommendation · ${carName}
              <span style="font-size:10px;font-weight:700;letter-spacing:0.6px;background:#f55036;color:#fff;border-radius:4px;padding:2px 7px;white-space:nowrap;">⚡ GROQ</span>
            </div>
            <div class="ev-ai-text">${recommendation}</div>
          </div>
        </div>`;
    }

    // Kalkylatorn hör hemma DIREKT under AI-rekommendationen: den säger vilken station du ska
    // åka till och hur många kW den ger, och nästa fråga är alltid "hur lång tid tar det då?".
    // Sist i utdatan låg svaret fem stationskort och två karuseller bort.
    //
    // Platshållare och inte kalkylatorn själv, eftersom `calcHtml` behöver stationslistan
    // (`top`) som räknas fram längre ner. Marker­bytet nedanför faller tillbaka på att lägga
    // avdelningen sist om platshållaren av något skäl inte finns — verktyget får aldrig
    // försvinna bara för att ordningen ändras.
    html += VERKTYG_PLATS;

    const funfactHtml = buildFunfactHtml(data.funFact);

    if (state.carIndex !== null && state.cars.length > 0) {
      const selectedName = state.cars[state.carIndex]?.name;
      const allValid = state.cars.filter(c => c.priceKr > 0 && c.rangeKm > 0);

      // Uppmätt förbrukning ur Autocars "vardagstest", refererat av Vi Bilägare 2026-08-17:
      // https://www.vibilagare.se/nyheter/volvo-ex30-sticker-ut-en-av-de-torstigaste-elbilarna
      // Metoden är fyra varv på en bana i 30–80 km/tim med två stopp per varv, så att bilen
      // hinner återvinna energi vid inbromsning.
      //
      // EGEN TABELL och inte en kolumn i tabellerna nedan, av en konkret anledning: de
      // räknar på WLTP ur ev_spec, alltså typgodkännande, medan detta är UPPMÄTTA värden ur
      // ett enda test med en enda metod. Blandade i samma tabell hade de sett jämförbara ut
      // utan att vara det. Samma skäl till att Vi Bilägares egen EX30-långtestsiffra
      // (22,1 kWh/100 km över ett helt testår inklusive vintertest) står i brödtexten och
      // inte som en rad — ett annat test, alltså en annan skala.
      //
      // Renault 4 och MG4 Urban beskrivs i artikeln som "nästan lika snåla" men får ingen
      // egen siffra där. De står därför bara i brödtexten. Hitta aldrig på ett mätvärde för
      // att fylla en rad — samma regel som gäller priser i CarAdvice.
      const autocarForbrukning = [
        { name: 'Ford Puma Gen-E',                 val: 10.0 },
        { name: 'Honda Super-N',                   val: 11.0, approx: true },
        { name: 'BYD Dolphin Surf',                val: 11.0, approx: true },
        { name: 'Volvo ES90 (bakhjulsdrift)',      val: 12.4 },
        { name: 'Mini Aceman',                     val: 14.5 },
        { name: 'Citroën ë-C3',                    val: 15.5 },
        { name: 'Volvo EX30 ER Single Motor',      val: 17.3 }
      ];
      const kwh = v => v.toFixed(1).replace('.', ',');

      const modes = [
        // FYNDTABELLEN ligger först med flit — det är den enda tabellen som pekar på en
        // affär i stället för på en egenskap, och den bygger på två källor: nypriset från
        // Kvdbil och medianpriset från vår egen veckovisa Blocket-mätning. Tom lista före
        // första synken, och då faller moden bort av sig själv i filter(Boolean) nedan.
        ...(state.valueRetention.length ? [{
          icon: '📉', kind: 'varde', label: 'Störst värdetapp — fynd på begagnatmarknaden',
          colHeader: 'Kvar av nypris',
          data: state.valueRetention.map(v => ({
            name: v.model, val: v.retentionPct, price: v.medianPriceKr,
            ny: v.newPriceKr, antal: v.adCount, billigast: v.cheapestPriceKr
          })),
          formatVal: v => `${v} %`,
          factFn: (b) => `<strong>${b.name}</strong> har tappat mest: <strong>${100 - b.val} %</strong> av nypriset är borta på fem år. Ny kostade den <strong>${b.ny.toLocaleString('sv-SE')} kr</strong>, idag ligger medianen på <strong>${b.price.toLocaleString('sv-SE')} kr</strong>${b.billigast ? ` och billigaste exemplaret på ${b.billigast.toLocaleString('sv-SE')} kr` : ''}. <em>Årsmodell 2021, ${b.antal} annonser under 15 000 mil. ${state.valueRetentionKalla}; medianpriset är vår egen mätning på Blocket.</em>`
        }] : []),
        {
          icon: '🥤', kind: 'cons', label: 'Lägst uppmätt förbrukning', colHeader: 'kWh/100 km',
          // lägst är bäst här, tvärtemot de andra tabellerna som sorterar fallande
          data: [...autocarForbrukning].sort((a, b) => a.val - b.val),
          formatVal: v => `${kwh(v)} kWh`,
          factFn: (best) => `Småbil är inte samma sak som snål elbil: <strong>${best.name}</strong> drar minst av alla med <strong>${kwh(best.val)} kWh/100 km</strong> trots att den är en ombyggd bensinbil, medan <strong>Volvo EX30 ER Single Motor</strong> drar <strong>17,3</strong> – 70 % mer än den ungefär lika stora Puma och 40 % mer än den större ES90. <em>Autocars vardagstest via Vi Bilägare (2026-08-17); förbrukningen är avläst ur bilarnas egna mätare och alla kördes inte i samma väder. Renault 4 och MG4 Urban ligger nära toppen men fick ingen egen siffra.</em>`
        },
        {
          icon: '📊', label: 'Räckvidd per 100 000 kr', colHeader: 'km/100 tkr',
          data: allValid.map(c => ({ name: c.name, val: Math.round(c.rangeKm * 100000 / c.priceKr), price: c.priceKr })).sort((a, b) => b.val - a.val),
          formatVal: v => `${v} km`,
          factFn: (best) => carFact || `Bäst värde för pengarna: ${best.name} med ${best.val} km per 100 000 kr.`
        },
        {
          icon: '⚡', label: 'Snabbast DC-laddning', colHeader: 'DC max',
          data: state.cars.filter(c => c.maxDcKw > 0 && c.priceKr > 0).map(c => ({ name: c.name, val: c.maxDcKw, price: c.priceKr })).sort((a, b) => b.val - a.val),
          formatVal: v => `${v} kW`,
          factFn: (best) => `Snabbaste DC-laddning: ${best.name} med ${best.val} kW – kostar ${(best.price / 1000).toFixed(0)} 000 kr.`
        },
        {
          icon: '🛣️', label: 'Längst räckvidd (WLTP)', colHeader: 'Räckvidd',
          data: allValid.map(c => ({ name: c.name, val: c.rangeKm, price: c.priceKr })).sort((a, b) => b.val - a.val),
          formatVal: v => `${v} km`,
          factFn: (best) => `Längst räckvidd: ${best.name} med ${best.val} km WLTP – kostar ${(best.price / 1000).toFixed(0)} 000 kr.`
        },
        {
          icon: '🎯', kind: 'wltp', label: 'WLTP vs verklig räckvidd', colHeader: 'Tappar',
          data: allValid.map(c => {
            const real = Math.round(c.rangeKm * 0.85);
            return { name: c.name, val: c.rangeKm - real, price: c.priceKr, wltp: c.rangeKm, real };
          }).sort((a, b) => b.val - a.val),
          formatVal: v => `-${v} km`,
          factFn: (best) => `Störst skillnad: ${best.name} – WLTP ${best.wltp} km men ~${best.real} km verklig räckvidd (tappar ~${best.val} km). Alla bilar beräknas med 85% av WLTP som tumregel.`
        }
      ];

      const buildRow = (c, rank, hl, stripe, kind, formatVal) => {
        const rowBg  = hl ? 'background:rgba(59,130,246,0.12);border-left:3px solid #3b82f6;'
                         : stripe ? 'background:#dbeafe;' : 'background:#ffffff;';
        const bold   = hl ? 'font-weight:700;' : '';
        const td     = `padding:6px 10px;color:#111827;${bold}`;
        if (kind === 'wltp') {
          return `<tr style="${rowBg}">
              <td style="${td}color:#9ca3af;font-size:12px;">${rank}</td>
              <td style="${td}">${c.name}</td>
              <td style="${td}text-align:right;color:#374151;">${c.wltp} km</td>
              <td style="${td}text-align:right;color:#16a34a;">~${c.real} km</td>
            </tr>`;
        }
        if (kind === 'cons') {
          // approx-raderna står som "omkring 11" i artikeln — tilde i stället för att låtsas
          // att 11,0 är avläst med en decimal. Lägst förbrukning är bäst, så gröntonen ligger
          // på siffran i stället för på en prisjämförelse som saknas här.
          const tilde = c.approx ? '~' : '';
          return `<tr style="${rowBg}">
              <td style="${td}color:#9ca3af;font-size:12px;">${rank}</td>
              <td style="${td}">${c.name}</td>
              <td style="${td}text-align:right;color:#1d4ed8;">${tilde}${formatVal(c.val)}</td>
              <td style="${td}text-align:right;color:#374151;">${tilde}${(c.val / 10).toFixed(2).replace('.', ',')}</td>
            </tr>`;
        }
        return `<tr style="${rowBg}">
              <td style="${td}color:#9ca3af;font-size:12px;">${rank}</td>
              <td style="${td}">${c.name}</td>
              <td style="${td}text-align:right;color:#1d4ed8;">${formatVal(c.val)}</td>
              <td style="${td}text-align:right;color:#374151;">${(c.price / 1000).toFixed(0)} tkr</td>
            </tr>`;
      };

      const slides = modes.map((mode, mi) => {
        const { icon, label, colHeader, data, formatVal, factFn } = mode;
        if (!data.length) return '';
        const factText = factFn(data[0]);
        // kind sätts explicit på moden. Förut lästes den ur ikonen (icon === '🎯'), vilket
        // band radlayouten till en emoji — en ikonändring hade tyst gett fel kolumner.
        const kind = mode.kind || 'default';
        const th1 = kind === 'wltp' ? 'WLTP' : colHeader;
        const th2 = kind === 'wltp' ? '~Verklig' : kind === 'cons' ? 'kWh/mil' : kind === 'varde' ? 'Median idag' : 'Pris';
        const rows = data.map((c, i) => buildRow(c, i + 1, c.name === selectedName, i % 2 === 1, kind, formatVal)).join('');
        return `<div class="ev-fact-slide" data-slide="${mi}" style="display:${mi===0?'flex':'none'};gap:12px;align-items:flex-start;">
          <div class="ev-funfact-icon">${icon}</div>
          <div style="flex:1">
            <div class="ev-funfact-label">${label}</div>
            <div class="ev-funfact-text" style="margin-bottom:10px;">${factText}</div>
            <div style="border:1px solid #e5e7eb;border-radius:8px;overflow:hidden;margin-top:4px;">
              <!-- FAST höjd, inte max-height: tabellerna har olika många rader (förbruknings-
                   listan ett tiotal, de andra 73), så med max-height krympte rutan för de
                   korta och kortet hoppade i storlek vid varje slidebyte. Med en fast höjd
                   ligger tabellen still och bara innehållet scrollar. -->
              <div style="overflow-y:auto;height:220px;">
                <table style="width:100%;border-collapse:collapse;font-size:13px;">
                  <thead style="position:sticky;top:0;z-index:1;"><tr style="background:#f3f4f6;border-bottom:1px solid #e5e7eb;">
                    <th style="padding:6px 10px;text-align:left;color:#9ca3af;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;width:24px;">#</th>
                    <th style="padding:6px 10px;text-align:left;color:#9ca3af;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;">Bil</th>
                    <th style="padding:6px 10px;text-align:right;color:#9ca3af;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;">${th1}</th>
                    <th style="padding:6px 10px;text-align:right;color:#9ca3af;font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;">${th2}</th>
                  </tr></thead>
                  <tbody>${rows}</tbody>
                </table>
              </div>
            </div>
          </div>
        </div>`;
      }).filter(Boolean).join('');

      const dots = modes.map((_, i) =>
        `<button class="ev-fact-dot${i===0?' ev-fact-dot-active':''}" data-dot="${i}" aria-label="Fakta ${i+1}"></button>`
      ).join('');

      // Avdelaren låg förut här, före tabellkarusellen. Den är flyttad till en gemensam
      // rubrik över BÅDA karusellerna (se evCarouselSection) — de hör ihop och ska läsas
      // som ett område, inte som två lösryckta kort med var sitt avbrott emellan.
      factHtml += `
        <div class="ev-funfact-card" id="ev-fact-carousel" style="flex-direction:column;align-items:stretch;gap:0;">
          <div id="ev-fact-slides" data-slides style="position:relative;">${slides}</div>
          <div style="display:flex;align-items:center;justify-content:space-between;margin-top:12px;">
            <button class="ev-fact-nav" data-carousel-prev>‹</button>
            <div style="display:flex;flex-wrap:wrap;justify-content:center;gap:6px;">${dots}</div>
            <button class="ev-fact-nav" data-carousel-next>›</button>
          </div>
          <div class="ev-fact-progress"><div class="ev-fact-progress-bar"></div></div>
          <div style="display:flex;justify-content:center;margin-top:10px;">
            <button class="ev-fact-play" data-carousel-play aria-pressed="false" title="Pausa karusellen">
              <span class="ev-fact-play-icon">⏸</span><span data-carousel-play-label>Paus</span>
            </button>
          </div>
        </div>`;
    }

    if (state.favorites.length > 0) {
      html += `<div class="ev-favorites-section">
        <div class="ev-favorites-title">♥ Mina favoriter (${state.favorites.length})</div>
        ${state.favorites.map(f => `
          <div class="ev-fav-item">
            <span class="ev-fav-item-name">${f.name}</span>
            <span class="ev-fav-item-meta">⚡ ${Math.round(f.maxEffKw)} kW · ${f.connectorType}</span>
            <button class="ev-fav-remove" data-id="${f.id}" title="Ta bort">✕</button>
          </div>`).join("")}
      </div>`;
    }

    if (uniqueOps.length > 1) {
      const chipAll = !state.operatorFilter ? ' ev-op-active' : '';
      stationsHtml += `<div class="ev-op-chips" style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px;">
        <button class="ev-op-chip${chipAll}" data-op="">Alla</button>
        ${uniqueOps.map(op => {
          const act = state.operatorFilter === op ? ' ev-op-active' : '';
          return `<button class="ev-op-chip${act}" data-op="${op}">${op}</button>`;
        }).join('')}
      </div>`;
    }

    const filterNote = state.filter === "fast" ? " · DC ≥50 kW" : "";
    const opNote = state.operatorFilter ? ` · ${state.operatorFilter}` : "";
    stationsHtml += `
      <div class="ev-results-header">
        <strong>${stations.length} kompatibla stationer inom 15 km</strong>
        <span>Topp ${top.length}${filterNote}${opNote}</span>
      </div>`;

    top.forEach((s, i) => {
      const speedClass = s.maxEffKw >= 100 ? "fast" : s.maxEffKw >= 22 ? "medium" : "slow";
      const kwClass    = speedClass === "fast" ? "kw-fast" : speedClass === "medium" ? "kw-medium" : "kw-slow";
      const rawPrice   = s.chargepricePerKwh || s.usageCost || "";
      const car        = state.carIndex !== null ? state.cars[state.carIndex] : null;
      const battery    = car?.batteryKwh ?? null;
      const realMil    = verkligaMil(car?.rangeKm);
      const pris       = tolkaLaddpris(rawPrice);
      const isFree     = pris.gratis;
      const fullCost   = fullLaddningKr(battery, pris.krPerKwh, pris.gratis);
      const krPerMil   = krPerMilAv(fullCost, realMil);
      const extraKr    = merKostnadMotHemma(battery, fullCost);
      const costHint   = fullCost
        ? `<div class="ev-cost-hint">🔋 Full laddning ~${fullCost} kr${krPerMil ? " · ~" + krPerMil + " kr/mil" : ""}</div>`
        : "";
      const homeHint   = extraKr
        ? `<div class="ev-home-compare">🏠 +${extraKr} kr jämfört med hemmaladdning</div>`
        : "";
      const displayPrice = (pris.varEur && pris.krPerKwh) ? `${pris.krPerKwh.toFixed(2)} kr/kWh` : rawPrice;
      const priceBadgeCls = isFree ? " free" : "";
      const priceBadge = rawPrice ? `<div class="ev-price-badge${priceBadgeCls}">${displayPrice}</div>${costHint}${homeHint}` : "";
      const addr       = s.address ? `<div class="ev-station-addr">${s.address}</div>` : "";
      const opTag      = s.operator && !s.operator.includes("Unknown")
        ? `<span class="ev-operator-tag">${s.operator}</span>` : "";
      const connTag    = s.connectorCount > 0
        ? `<span class="ev-conncount-tag">🔌 ${s.connectorCount} laddpunkter</span>` : "";
      const isFav      = state.favorites.some(f => Math.abs(f.lat - s.lat) < 0.0005);
      const favClass   = isFav ? " saved" : "";
      const favTitle   = isFav ? "Ta bort favorit" : "Spara som favorit";
      const isDC       = s.connectorType.includes("DC");
      const timeMin    = (battery && isDC && s.maxEffKw > 0)
          ? Math.round((battery * 0.60) / s.maxEffKw * 60) : null;
      const timeStr    = timeMin === null ? null
          : timeMin < 60 ? `~${timeMin} min`
          : `~${Math.floor(timeMin/60)} tim ${timeMin % 60} min`;
      const timeTag    = timeStr ? `<span class="ev-time-tag">⏱ ${timeStr} (20→80%)</span>` : "";

      const _appEl = document.querySelector('.ev-app') || document.getElementById('ev-output');
      const isMob = (_appEl ? _appEl.offsetWidth : window.innerWidth) <= 600;
      if (isMob) {
        const priceBadgeMob = rawPrice ? `<span class="ev-price-badge${priceBadgeCls}" style="font-size:.7rem;padding:2px 6px;">${displayPrice}</span>` : "";
        stationsHtml += `
          <div class="ev-station">
            <div class="ev-station-bar ${speedClass}"></div>
            <div style="flex:1;padding:10px 12px;min-width:0;">
              <div style="display:flex;align-items:flex-start;gap:8px;min-width:0;">
                <div class="ev-rank" style="flex-shrink:0;margin-top:2px;">${i+1}</div>
                <div style="flex:1;min-width:0;">
                  <div class="ev-station-name" style="white-space:normal;line-height:1.35;">${s.name}</div>
                  ${addr}
                  <div style="display:flex;flex-wrap:wrap;gap:5px;align-items:center;margin-top:6px;">
                    <span class="ev-kw-badge ${kwClass}">⚡ ${Math.round(s.maxEffKw)} kW</span>
                    <span class="ev-connector-tag">${s.connectorType}</span>
                    ${opTag}
                    <span class="ev-dist" style="font-size:.73rem;font-weight:700;color:var(--muted);">${s.distanceKm.toFixed(1)} km</span>
                    ${priceBadgeMob}
                    ${connTag}${timeTag}
                    <button class="ev-fav-btn${favClass}" data-lat="${s.lat}" data-lon="${s.lon}" title="${favTitle}" style="margin-left:auto;">♥</button>
                  </div>
                </div>
              </div>
            </div>
          </div>`;
      } else {
        stationsHtml += `
          <div class="ev-station">
            <div class="ev-station-bar ${speedClass}"></div>
            <div class="ev-station-body">
              <div class="ev-rank">${i+1}</div>
              <div class="ev-station-main">
                <div class="ev-station-name">${s.name}</div>
                ${addr}
              </div>
              <div class="ev-station-right">
                <div class="ev-dist">${s.distanceKm.toFixed(1)} km</div>
                ${priceBadge}
                <button class="ev-fav-btn${favClass}" data-lat="${s.lat}" data-lon="${s.lon}" title="${favTitle}">♥</button>
              </div>
              <div class="ev-station-tags" style="grid-column:2">
                <span class="ev-kw-badge ${kwClass}">⚡ ${Math.round(s.maxEffKw)} kW</span>
                <span class="ev-connector-tag">${s.connectorType}</span>
                ${opTag}
                ${connTag}
                ${timeTag}
              </div>
            </div>
          </div>`;
      }
    });

    // Laddtidskalkylator
    let calcHtml = '';
    if (state.carIndex !== null && state.cars.length > 0) {
      const car = state.cars[state.carIndex];
      const dcStation = top.find(s => s.connectorType.includes('DC') && s.maxEffKw > 0);
      if (car && car.batteryKwh > 0) {
        const effKw = dcStation ? Math.min(car.maxDcKw || 50, dcStation.maxEffKw) : (car.maxDcKw || 50);
        const stLabel = dcStation
          ? (dcStation.name.length > 32 ? dcStation.name.slice(0, 30) + '…' : dcStation.name) + ' · ' + Math.round(dcStation.maxEffKw) + ' kW'
          : 'Ingen DC-station hittad';
        // Kortet bär INTE längre .ev-funfact-card och ingen egen stor ikon. Inuti
        // verktygsavdelningen hade det två ramar utanpå varandra och två ikoner som
        // konkurrerade om samma blick — 🧰 för avdelningen och ⏱ för kortet, tio pixlar isär.
        // Avdelningen är ramen; kortet är innehållet.
        //
        // Procenttalen är flyttade UPP och gjorda stora: de är det man ändrar och det man
        // läser av, och som liten grå text bredvid etiketten var de svårare att se än
        // reglagets position. Reglagen har egen stil — webbläsarens grundutseende är en vit
        // stapel som lyser som en ficklampa i en mörk app.
        calcHtml = `
          <div id="ev-calc-card">
            <div class="ev-calc-titel">⏱ Laddtidskalkylator <span>${car.name}</span></div>
            <div class="ev-calc-reglage">
              <label>
                <div class="ev-calc-etikett">Ladda från <b id="ev-calc-from-val">20</b>%</div>
                <input type="range" class="ev-calc-slider" id="ev-calc-from" min="0" max="90" value="20" oninput="evCalcUpdate()">
              </label>
              <label>
                <div class="ev-calc-etikett">Till <b id="ev-calc-to-val">80</b>%</div>
                <input type="range" class="ev-calc-slider" id="ev-calc-to" min="10" max="100" value="80" oninput="evCalcUpdate()">
              </label>
            </div>
            <div id="ev-calc-result" class="ev-calc-svar"></div>
            <div class="ev-calc-kalla">📍 ${stLabel} · effektiv laddning ${Math.round(effKw)} kW</div>
          </div>`;
      }
    }

    /*
     * Ordningen lagd om 2026-08-18. Förut: AI-kort → faktakarusell → favoriter → stationslista
     * → tabellkarusell. De två karusellerna hörde uppenbart ihop men hade fem stationskort
     * emellan sig, så den som ville jämföra tabellerna fick skrolla förbi listan varje gång.
     *
     * Nu: AI-kort och favoriter → KARUSELLOMRÅDET (båda korten under en gemensam rubrik) →
     * stationslistan hopfälld bakom en knapp → laddkalkylen.
     */
    // Tva argument och inte en ihopslagen strang: avdelningen bygger flikar av dem, och
    // da maste den veta var den ena slutar och den andra borjar.
    const carouselSection = carouselArea(funfactHtml, factHtml);

    /*
     * Stationslistan är hopfälld från start. Öppet läge lever i state och inte i DOM:en:
     * operatörschippen och sorteringen renderar om HELA utdatan, så utan det hade listan
     * fällts ihop mitt under att man filtrerade i den.
     */
    const stationsOpen = !!state.stationsOpen;
    const oppnaEtikett = `Visa ${stations.length} kompatibla stationer inom 15 km`;
    const stationsSection = stationsHtml
      ? `<button class="ev-stations-toggle" id="ev-stations-toggle" aria-expanded="${stationsOpen}"
                 aria-controls="ev-stations-body" data-open-label="${oppnaEtikett}">
           <span aria-hidden="true">⚡</span>
           <span id="ev-stations-toggle-label">${stationsOpen ? 'Dölj stationerna' : oppnaEtikett}</span>
           <span class="ev-chevron" aria-hidden="true">▼</span>
         </button>
         <div id="ev-stations-body" style="display:${stationsOpen ? 'block' : 'none'};">${stationsHtml}</div>`
      : '';

    // Stationsknappen FÖRST i utdatan, alltså direkt under kartan — #ev-map ligger
    // omedelbart ovanför #ev-output i elbilsladdning-web.html. Kartan visar samma
    // stationer som listan, så knappen hör hemma i anslutning till den.
    const verktygHtml = verktygArea(calcHtml);
    html = html.indexOf(VERKTYG_PLATS) !== -1
        ? html.split(VERKTYG_PLATS).join(verktygHtml)
        : html + verktygHtml;
    setOutput(stationsSection + html + carouselSection);

    if (state.lat && state.lon && top.length > 0)
      setTimeout(() => renderMap(state.lat, state.lon, top), 50);
  }
  /**
   * Bygger "Visste du att"-kortet. Egen funktion sedan 2026-08-18 for att tipsen ska ga att
   * visa INNAN man valt bil - de statiska fakta behover varken bil eller position, och en
   * tom sida ar ett samre forsta intryck an ett tips.
   *
   * @param funFact AI-genererat fakta ur sokresultatet, eller null fore sokning
   */
  /**
   * Ramar in karusellkorten med den gemensamma rubriken.
   *
   * Egen funktion for att BADA vagarna in ska ge samma rubrik: sokresultatet (dar bade
   * faktakortet och tabellkortet finns) och forstavyn innan man valt bil (dar bara
   * faktakortet finns). Tva kopior hade betytt tva rubriker att glomma vid nasta andring.
   */
  /**
   * Avdelning för verktygen, byggd som karusellens — samma rubrikrad, samma ram, egen färg.
   *
   * <p>Laddtidskalkylatorn låg naken sist i utdatan och såg ut som ännu ett tipskort, fast den
   * är det enda man kan RÄKNA med. Ramen säger att den är en egen sak, och den blå tonen
   * skiljer den från karusellens gula utan att införa ett nytt formspråk.
   *
   * <p>Rubriken är "Verktyg" och inte "Övriga verktyg": det finns bara ett, och "övriga"
   * lovar en lista som inte existerar. Avdelningen rymmer fler den dagen de finns.
   */
  function verktygArea(bodyHtml) {
    if (!bodyHtml) return '';
    return `<div class="ev-carousel-area ev-tools-area">
        <div class="ev-carousel-head">
          <div class="ev-carousel-head-icon">🧰</div>
          <div>
            <div class="ev-carousel-head-title">Verktyg</div>
            <div class="ev-carousel-head-sub">Räkna på laddningen för just din bil och den snabbaste stationen i närheten</div>
          </div>
        </div>
        ${bodyHtml}
      </div>`;
  }

  /**
   * Karusellavdelningen — FLIKAR när det finns två karuseller, rubrik när det finns en.
   *
   * <p>Förut låg de två korten staplade, och eftersom de har identisk uppbyggnad betydde det
   * TVÅ prickrader, TVÅ förloppslinjer och TVÅ Paus-knappar under varandra. Kontrollerna tog
   * mer höjd än innehållet, och det gick inte att se vilken Paus som pausade vad.
   *
   * <p>Flikarna ÄR rubriken: en avdelningstitel ovanför två flikar som säger samma sak hade
   * varit ett lager för mycket. Har bara den ena karusellen innehåll — förstavyn, innan man
   * valt bil och sökt — finns ingen flik att välja mellan, och då står den gamla rubriken kvar.
   *
   * <p>Båda panelerna ligger kvar i DOM:en och den inaktiva göms med {@code hidden}. Att bygga
   * om dem vid flikbytet hade nollställt karusellens position och pausläge, alltså straffat
   * den som just pausat för att läsa.
   */
  function carouselArea(tipsHtml, tabellHtml) {
    const delar = [
      { id: 'tips', ikon: '💡', etikett: 'AI-tips &amp; Visste du att', html: tipsHtml },
      { id: 'tabeller', ikon: '📊', etikett: 'Jämför bilarna', html: tabellHtml }
    ].filter(function (d) { return !!d.html; });
    if (!delar.length) return '';

    if (delar.length === 1) {
      return `<div class="ev-carousel-area">
          <div class="ev-carousel-head">
            <div class="ev-carousel-head-icon">${delar[0].ikon}</div>
            <div>
              <div class="ev-carousel-head-title">${delar[0].etikett}</div>
              <div class="ev-carousel-head-sub">Bläddra själv, eller pausa och läs i lugn och ro</div>
            </div>
          </div>
          ${delar[0].html}
        </div>`;
    }

    const flikar = delar.map(function (d, i) {
      return `<button class="ev-flik${i === 0 ? ' ev-flik-aktiv' : ''}" type="button" role="tab"`
        + ` aria-selected="${i === 0}" aria-controls="ev-flikpanel-${d.id}" data-flik="${d.id}">`
        + `<span aria-hidden="true">${d.ikon}</span> ${d.etikett}</button>`;
    }).join('');
    const paneler = delar.map(function (d, i) {
      return `<div id="ev-flikpanel-${d.id}" role="tabpanel" data-flikpanel="${d.id}"`
        + `${i === 0 ? '' : ' hidden'}>${d.html}</div>`;
    }).join('');

    return `<div class="ev-carousel-area">
        <div class="ev-flikar" role="tablist" aria-label="Tips och tabeller">${flikar}</div>
        ${paneler}
      </div>`;
  }

  /**
   * Forstavyn: tipsen ensamma, innan man valt bil och sokt.
   *
   * Utan den motts man av en tom yta, och de statiska fakta behover varken bil eller
   * position for att vara lasvarda. Kors direkt vid start och en gang till nar
   * forsaljningstoppen hunnit hem, sa den dynamiska raden kommer med nar den finns.
   *
   * Ror ALDRIG en fardig sokning - state.lastData satts av renderResults, och den vyn
   * ar rikare an den har.
   */
  function renderTipsOnly() {
    if (state.lastData) return;
    const el = document.getElementById("ev-output");
    if (!el || el.querySelector('.ev-status')) return;   // spinnern far vara ifred
    setOutput(carouselArea(buildFunfactHtml(null)));
  }

  function buildFunfactHtml(funFact) {
    let funfactHtml = "";
    {
      const staticFacts = [
        { icon: '🏆', text: 'Volvo EX40 var Sveriges mest sålda elbil 2025 med <strong>8 788</strong> nyregistreringar – och EX40/XC40 leder även första halvåret 2026, före Tesla Model Y (Mobility Sweden).' },
        { icon: '🌍', text: 'IONITY är Europas snabbaste offentliga laddnätverk med upp till <strong>350 kW</strong> per laddpunkt. I Norden finns 100+ stationer längs motorvägarna.' },
        { icon: '🇸🇪', text: 'Vattenfall InCharge är ett av Nordens största laddnätverk med över <strong>33 000 laddpunkter</strong> i Sverige, Norge, Danmark och Finland.' },
        { icon: '❄️', text: 'På kall vinterdag kan räckvidden minska med <strong>20–40%</strong> jämfört med WLTP. Värm bilen medan den laddar för att spara batterienergi.' },
        { icon: '⚡', text: 'Tesla öppnade sitt Supercharger-nätverk för andra elbilsmärken i Sverige <strong>2023</strong>. CCS (Combo) är Europas dominerande DC-laddstandard.' },
        { icon: '📈', text: 'Elbilarna går framåt: <strong>45%</strong> av Sveriges nyregistreringar 2026 väntas bli elbilar enligt Mobility Swedens prognos (justerad juli 2026) – upp från 36,5% helåret 2025.' },
        { icon: '🚗', text: 'Mercedes eldrivna CLA utsågs till <strong>Årets Bil 2026</strong> (europeiska Car of the Year) – före Škoda Elroq och Kia EV4. 2025 vann Renault 5 E-Tech.' },
        { icon: '📊', text: 'Volvo dominerar den svenska nybilsmarknaden med <strong>16,5%</strong> marknadsandel i juni 2026, före Volkswagen (13,2%) och Kia (7,4%). Tesla ligger åtta med 4,4% (Mobility Sweden).' },
        { icon: '🥇', text: 'I maj 2026 var Volkswagen ID.4 Sveriges mest sålda renodlade elbil med <strong>687</strong> nyregistreringar – tätt följt av Tesla Model Y (683) och Polestar 2 (526) (Carla.se elbilsindex).' },
        { icon: '🔌', text: 'Kia EV6 (339), Volkswagen ID.3 (285) och Škoda Enyaq (276) rundade av majitoppen bland Sveriges mest sålda elbilar 2026 – före Nissan Leaf (260) och Volvo EX40 (205) (Carla.se elbilsindex).' },
        { icon: '🚀', text: 'Elbilar gick om laddhybrider i försäljning kring årsskiftet 2025/2026 och har inte tittat tillbaka – i april 2026 passerade elbilsförsäljningen till och med diesel och närmade sig bensin, fortfarande den största kategorin (Carla.se).' },
        // Nedan: hämtade ur CarAdvice-insikterna (samma skrapade motorpress som bilkortens
        // "Vad experterna säger"). Bara laddningsrelevanta rader om bilar som går att köpa —
        // kommande modeller hör hemma i CarAdvice kommande-kö, inte i en publik faktakarusell.
        { icon: '⏱️', text: 'Låg förbrukning slår stort batteri på långresa: <strong>Mercedes CLA 250+</strong> drar bara <strong>16,5 kWh/100 km</strong> och klarar därför 80 mil med ett enda laddstopp på <strong>14 minuter</strong> (Vi Bilägare).' },
        { icon: '🔋', text: '<strong>Volvo EX30</strong> tar sig 80 mil på totalt <strong>59 minuters</strong> laddning och håller sig under 20 kWh/100 km – ett bra riktvärde för hur mycket laddtid en långresa faktiskt kostar (Vi Bilägare).' },
        // ID. Cross ar ett MEDVETET undantag fran urvalsregeln ovan och ska INTE tas bort som
        // "kommande modell". Den lanseras hosten 2026 och vi ar i mitten av augusti - det ar
        // veckor bort, inte ar, sa tipset hinner knappt bli fel innan bilen star hos handlarna.
        // Togs bort 08-14 och lades tillbaka samma dag av just det skalet. Gransen gar vid
        // modeller som ligger LANGT bort eller aldrig kommer hit (EX60, Cupra Raval).
        { icon: '🏠', text: 'Nya elbilar blir reservkraft: <strong>Volkswagen ID. Cross</strong> har både <strong>V2L</strong> (upp till 3,6 kW till externa prylar) och <strong>V2H</strong> som standard – bilen kan alltså mata ström tillbaka till hemmet (Teknikens Värld / Vi Bilägare).' },
        { icon: '⚡', text: '800 volt sprider sig nedåt i prisklasserna: <strong>BYD Atto 3 Evo</strong> fick större batteri och 800 V-laddning, och maxeffekten steg från <strong>88 kW till 220 kW</strong> (Elbilen).' },
        { icon: '🛞', text: 'Praktiskt knep i backarna: <strong>B-läget</strong> motorbromsar och laddar batteriet i stället för att elda upp farten i bromsarna. I Volvo XC40 uppges det ge <strong>en till två mils</strong> extra räckvidd – och mindre slitage på bromsbeläggen (CarUp).' },
        { icon: '🛣️', text: 'Längst på en laddning: <strong>Mercedes EQS 450+</strong> klarar <strong>925 km</strong> enligt WLTP – men räkna med mindre i verklig fart och kyla, WLTP mäts i betydligt snällare förhållanden (CarUp).' },
        // Andra omgången ur CarAdvice-insikterna (08-14). Samma urvalsregel som ovan. Fem av
        // dem slår ihop flera insiktsrader till ETT tips: batterihälsan är fem rader ur samma
        // CarUp-studie och testräckvidderna tre ur samma AMS-test — en rad per bil hade fyllt
        // karusellen med samma faktum om och om igen.
        { icon: '🔌', text: 'Laddbox är ingen lyx: <strong>Mercedes CLA 350 EQ</strong> tappar <strong>över 24 %</strong> av den tillförda energin i laddförluster när den laddas i ett vanligt hushållsuttag. Förlusterna skiljer sig dessutom mellan bilar – <strong>Hyundai Ioniq</strong> hade de största av fem testade elbilar i ADAC:s mätning (Vi Bilägare).' },
        { icon: '🩺', text: 'Batteriet håller bättre än ryktet: efter <strong>10 000 mil</strong> hade <strong>Kia e-Niro</strong> i snitt <strong>97,25 %</strong> av kapaciteten kvar, <strong>Hyundai Kona Electric</strong> 97,18 %, <strong>Kia EV6</strong> 95,95 %, <strong>Volvo XC40 Recharge</strong> 94,70 % och <strong>BMW i3</strong> knappt 94 % (CarUp).' },
        { icon: '📜', text: 'Läs batterigarantins finstil: <strong>Nissan Leaf</strong> har åtta år eller 16 000 mil på kapaciteten – men <strong>bara fem år eller 10 000 mil</strong> för enstaka battericellfel. Samtidigt har farhågan om batteribyte efter 6–7 år kommit på skam: livslängden liknar i dag bensin- och dieselbilars (Vi Bilägare / Auto Motor & Sport).' },
        { icon: '📏', text: 'Planera efter uppmätt räckvidd, inte WLTP: i Auto Motor & Sports test kom <strong>BMW iX3 50 xDrive</strong> <strong>502 km</strong> (108,7 kWh), <strong>Mercedes GLC 400 4Matic EQ</strong> 455 km (94 kWh) och <strong>Porsche Macan 4S</strong> 401 km (94,9 kWh).' },
        { icon: '🎒', text: 'Taklasten kostar räckvidd: ett taktält på <strong>Hyundai Ioniq 9</strong> ökade luftmotståndet så mycket att energiförbrukningen steg markant. Räkna med tätare laddstopp när takboxen eller tältet sitter uppe (Teknikens Värld).' },
        { icon: '🛠️', text: 'Bilen som byggström: <strong>BYD Shark</strong> kan leverera upp till <strong>6 kW</strong> via V2L – nog för att driva hantverkarens maskinpark direkt ur batteriet (Teknikens Värld / M3).' },
        { icon: '🏕️', text: '<strong>Volkswagen ID.Buzz</strong> har ett "god-natt-paket" med campingläge som håller kupéns temperatur i upp till <strong>48 timmar</strong>, plus V2L som låter högspänningsbatteriet driva externa prylar (Vi Bilägare).' },
        { icon: '🧭', text: 'Ett laddstopp räcker långt: <strong>Mercedes GLC</strong> klarar <strong>100 mil motorväg</strong> på ett enda stopp, och <strong>Citroën e-C5 Aircross</strong> har ett 97 kWh-batteri som ger över <strong>67 mils</strong> räckvidd med 565 liter bagage på köpet (Elbilen / CarUp).' },
        // Tredje omgången ur CarAdvice-insikterna (08-22), framplockade av fyndlistan
        // GET /api/admin/ev-fact-candidates. Fyndlistan poängsätter och filtrerar, men
        // formuleringen och brasklapparna nedan är handskrivna — insikterna är AI-extraherad
        // text, och en rad som gick rakt in oläst är precis det som fällde ett berömmande
        // mätvärde 2026-08-18 (Puma Gen-E).
        { icon: '📏', text: 'Uppmätt räckvidd kan slå den officiella: <strong>Audi Q6 e-tron</strong> nådde <strong>542 km</strong> på standardhjul i Edmunds räckviddstest – runt 12 % mer än den officiella EPA-siffran på 484 km – med en förbrukning kring 18 kWh/100 km (Auto Motor & Sport). EPA mäts hårdare än WLTP, så jämför inte den siffran rakt av med WLTP-talet i annonsen.' },
        { icon: '🪫', text: 'Låt inte elbilen stå oladdad för länge: en <strong>Tesla Model S</strong> som blev stående i <strong>sex månader</strong> fick ett urladdat 12-voltsbatteri och gick sedan varken att låsa upp, starta eller ladda (CarUp). Det lilla 12-voltssystemet är det som väcker högvoltsbatteriet – dör det hjälper det inte att det stora batteriet har ström kvar.' },
        // Euro 7 och batteripasset, tillagda 2026-08-18. TVÅ OLIKA förordningar som är lätta
        // att blanda ihop, och de får inte slås ihop i en rad:
        //   Euro 7 (EU 2024/1257) ställer kravet på batteriets HÄLSA — golvet nedan.
        //   Batteripasset kommer ur EU:s BATTERIFÖRORDNING (2023/1542), som gäller från
        //   18 februari 2027 och alltså är en helt annan rättsakt.
        // Skriv aldrig att batteripasset är en del av Euro 7. Det är passet som gör
        // uppgifterna läsbara på samma sätt oavsett märke, men det är Euro 7 som gör att
        // det finns ett garanterat golv att läsa av.
        //
        // De ligger MEDVETET i EN rad och inte i två: karusellen visar en slide i taget, så
        // delade upp hade läsaren sett golvet utan passet eller passet utan golvet — och
        // poängen är just att de två hakar i varandra.
        { icon: '⚖️', text: 'Batterihälsan blir mätbar och garanterad: <strong>Euro 7</strong> kräver att en ny elbil har minst <strong>80 %</strong> av batterikapaciteten kvar efter <strong>5 år eller 10 000 mil</strong> och <strong>72 %</strong> efter <strong>8 år eller 16 000 mil</strong> – nya typgodkännanden från <strong>29 november 2026</strong>, alla nyregistrerade från november 2027. Från <strong>18 februari 2027</strong> får varje ny elbil dessutom ett <strong>digitalt batteripass</strong> med samma uppgifter oavsett märke: kapacitet, kemi, ursprung och hälsa. Passet kommer ur EU:s batteriförordning, inte ur Euro 7 – men tillsammans gör de batterihälsa till något du kan läsa av i stället för att lita på (EU-förordning 2024/1257 respektive 2023/1542).' },
      ];
      /*
       * Fyndraden. ERSATTE den handskrivna Audi e-tron-raden 2026-08-18 — samma sorts tips, men
       * uppdaterad varje vecka i stället för när någon råkar komma på det. Den gamla raden togs
       * bort för att slippa samma bil på två ställen med olika siffror; det som gick förlorat i
       * bytet var två varningar den automatiska raden inte bär, om 1,63 kWh/mil och om att
       * batterigarantin börjar ta slut på 2019-bilarna.
       *
       * Källorna skrivs ut BÅDA två, för de gör olika saker: Kvdbil står för nypriset (ett
       * historiskt faktum om årsmodell 2021) och Blocket för vad bilen kostar idag. Utan den
       * uppdelningen ser det ut som att en enda källa påstått hela siffran.
       */
      const dynamicVardeFacts = [];
      if (state.valueRetention && state.valueRetention.length > 0) {
        const v = state.valueRetention[0];
        dynamicVardeFacts.push({ icon: '📉', text:
          `Fyndläge på begagnad el: <strong>${v.model}</strong> har tappat <strong>${100 - v.retentionPct} %</strong> av nypriset på fem år. Ny kostade den ${v.newPriceKr.toLocaleString('sv-SE')} kr — idag ligger medianen på <strong>${v.medianPriceKr.toLocaleString('sv-SE')} kr</strong>${v.cheapestPriceKr ? `, billigaste exemplaret på ${v.cheapestPriceKr.toLocaleString('sv-SE')} kr` : ''}. Räknat på ${v.adCount} annonser av årsmodell 2021 under 15 000 mil (${state.valueRetentionKalla}; medianpriset är vår egen mätning på Blocket).` });
      }

      const dynamicRankFacts = [];
      if (state.evSalesRank && state.evSalesRank.length > 0) {
        const top = state.evSalesRank[0];
        const second = state.evSalesRank[1];
        const period = top.periodLabel ? ' ' + top.periodLabel : '';
        const rankText =
          `${top.model} var Sveriges mest sålda elbil${period} med <strong>${top.units.toLocaleString('sv-SE')}</strong> nyregistreringar` +
          (second ? `, före ${second.model} (${second.units.toLocaleString('sv-SE')})` : '') +
          ' (elbilsvaruhuset.se / Mobility Sweden).';
        dynamicRankFacts.push({ icon: '🔋', text: rankText });
      }
      const facts = [
        ...(funFact ? [{ icon: '💡', text: funFact }] : []),
        // Fyndraden tidigt: den är det mest köpvärda tipset i hela kortleken, och den är
        // dessutom färsk varje vecka till skillnad från de statiska.
        ...dynamicVardeFacts,
        ...dynamicRankFacts,
        ...staticFacts
      ];

      const fSlideHtml = facts.map((f, i) =>
        `<div class="ev-funfact-slide" style="display:${i===0?'flex':'none'};align-items:flex-start;gap:10px;">
          <div class="ev-funfact-icon">${f.icon}</div>
          <div>
            <div class="ev-funfact-label">Visste du att</div>
            <div class="ev-funfact-text">${f.text}</div>
          </div>
        </div>`
      ).join('');

      const fDotHtml = facts.map((_, i) =>
        `<button class="ev-fact-dot${i===0?' ev-fact-dot-active':''}" aria-label="Fakta ${i+1}"></button>`
      ).join('');

      funfactHtml += `
        <div class="ev-funfact-card" id="ev-funfact-carousel" style="flex-direction:column;align-items:stretch;gap:0;">
          <div id="ev-funfact-slides" data-slides style="position:relative;min-height:48px;">${fSlideHtml}</div>
          <!-- flex-wrap: prickarna har flex-shrink:0, sa raden kan inte krympa. 20 fakta ger
               286px prickar mot 254px innermatt vid 320px viewport - utan wrap spiller de
               over kortkanten. Radbrytning haller den robust nar fler fakta tillkommer. -->
          <div style="display:flex;flex-wrap:wrap;justify-content:center;gap:6px;margin-top:10px;">${fDotHtml}</div>
          <div class="ev-fact-progress"><div class="ev-fact-progress-bar"></div></div>
          <div style="display:flex;justify-content:center;margin-top:10px;">
            <button class="ev-fact-play" data-carousel-play aria-pressed="false" title="Pausa karusellen">
              <span class="ev-fact-play-icon">⏸</span><span data-carousel-play-label>Paus</span>
            </button>
          </div>
        </div>`;
    }
    return funfactHtml;
  }


  function setOutput(html) {
    document.getElementById("ev-output").innerHTML = html;

    if (document.getElementById('ev-calc-card')) evCalcUpdate();

    // Flikbytet i karusellavdelningen. Lyssnaren sitter på fliklisten och inte på varje knapp:
    // hela utdatan byggs om vid varje sortering och filtrering, och lyssnare per knapp hade
    // behövt sättas om lika ofta. Panelerna göms med `hidden` — karusellerna fortsätter räkna
    // i bakgrunden, så den man kommer tillbaka till står där man lämnade den.
    const flikList = document.querySelector('.ev-flikar');
    if (flikList) {
      flikList.addEventListener('click', function (e) {
        const knapp = e.target.closest('.ev-flik');
        if (!knapp) return;
        const vald = knapp.dataset.flik;
        flikList.querySelectorAll('.ev-flik').forEach(function (k) {
          const aktiv = k.dataset.flik === vald;
          k.classList.toggle('ev-flik-aktiv', aktiv);
          k.setAttribute('aria-selected', aktiv ? 'true' : 'false');
        });
        document.querySelectorAll('[data-flikpanel]').forEach(function (p) {
          p.hidden = p.dataset.flikpanel !== vald;
        });
      });
    }

    // Stationslistans öppna/stäng. Etiketten för stängt läge bärs i data-open-label, så
    // antalet stationer inte behöver räknas om här — det är renderingen som vet det.
    const stToggle = document.getElementById('ev-stations-toggle');
    if (stToggle) {
      stToggle.addEventListener('click', () => {
        const oppen = stToggle.getAttribute('aria-expanded') !== 'true';
        state.stationsOpen = oppen;
        stToggle.setAttribute('aria-expanded', String(oppen));
        const body = document.getElementById('ev-stations-body');
        if (body) body.style.display = oppen ? 'block' : 'none';
        const label = document.getElementById('ev-stations-toggle-label');
        if (label) label.textContent = oppen ? 'Dölj stationerna' : stToggle.dataset.openLabel;
      });
    }

    document.querySelectorAll(".ev-op-chip").forEach(btn => {
      btn.addEventListener("click", () => {
        state.operatorFilter = btn.dataset.op || null;
        if (state.lastData) renderResults(state.lastData);
      });
    });

    document.querySelectorAll(".ev-fav-btn").forEach(btn => {
      btn.addEventListener("click", async () => {
        const lat = parseFloat(btn.dataset.lat);
        const lon = parseFloat(btn.dataset.lon);
        const station = state.lastData?.stations.find(s => Math.abs(s.lat - lat) < 0.0005);
        if (station) await toggleFavorite(station);
      });
    });

    document.querySelectorAll(".ev-fav-remove").forEach(btn => {
      btn.addEventListener("click", async () => {
        const id = parseInt(btn.dataset.id);
        const fav = state.favorites.find(f => f.id === id);
        if (fav) await toggleFavorite({ lat: fav.lat, lon: fav.lon, name: fav.name, address: fav.address, maxEffKw: fav.maxEffKw, connectorType: fav.connectorType, operator: fav.operator });
      });
    });

    function evFadeSlide(slides, dots, next, total) {
      const prev = slides[next._prev !== undefined ? next._prev : -1];
      slides.forEach((s, i) => {
        if (i === next._idx) {
          s.style.display = 'flex';
          s.classList.remove('ev-slide-leaving');
          s.classList.add('ev-slide-entering');
          setTimeout(() => s.classList.remove('ev-slide-entering'), 550);
        } else if (s === prev) {
          s.classList.add('ev-slide-leaving');
          setTimeout(() => { s.style.display = 'none'; s.classList.remove('ev-slide-leaving'); }, 360);
        } else {
          s.style.display = 'none';
        }
      });
      dots.forEach((d, i) => d.classList.toggle('ev-fact-dot-active', i === next._idx));
    }

    /**
     * Gemensam karusellmotor för båda korten ("Visste du att"-fakta och faktatabellerna).
     *
     * Skrevs ihop 2026-08-18 när paus/spela skulle in på båda. Två nästan identiska kopior
     * hade betytt två ställen att glömma vid nästa ändring — och det var precis så den
     * första versionen fick prickar på båda korten men pilar bara på det ena.
     *
     * @param cardId   kortets id
     * @param slideSel slidernas CSS-klass
     * @param onShow   valfritt, körs efter varje slidebyte (tabellkortet scrollar fram raden)
     */
    function evInitCarousel(cardId, slideSel, onShow) {
      const card = document.getElementById(cardId);
      if (!card) return;

      const slides = Array.from(card.querySelectorAll(slideSel));
      const dots   = Array.from(card.querySelectorAll('.ev-fact-dot'));
      const wrap   = card.querySelector('[data-slides]');
      const bar    = card.querySelector('.ev-fact-progress-bar');
      const btn    = card.querySelector('[data-carousel-play]');
      const total  = slides.length;
      if (!total) return;

      let current = 0, timer = null, paused = false;

      /**
       * Låser höjden till den HÖGSTA sliden.
       *
       * Utan den hoppade kortet i storlek vid varje byte: faktatexterna är olika långa
       * (Euro 7-raden är ett helt stycke, IONITY-raden en mening) och en slide som
       * lämnar är position:absolute, så behållaren hann kollapsa mitt i övergången.
       * Mäts dolt — visibility:hidden i stället för display:none, annars är höjden 0.
       */
      function equalizeHeight() {
        if (!wrap) return;
        let max = 0;
        slides.forEach(s => {
          const d = s.style.display;
          s.style.visibility = 'hidden';
          s.style.display = 'flex';
          max = Math.max(max, s.offsetHeight);
          s.style.display = d;
          s.style.visibility = '';
        });
        if (max) wrap.style.minHeight = max + 'px';
      }

      /** Startar om förloppslinjen. Reflow-raden krävs för att animationen ska tas om. */
      function restartBar() {
        if (!bar) return;
        bar.classList.remove('ev-run');
        void bar.offsetWidth;
        bar.classList.add('ev-run');
        bar.style.animationPlayState = paused ? 'paused' : 'running';
      }

      function showSlide(n) {
        const prev = current;
        current = (n + total) % total;
        slides[current]._idx = current; slides[current]._prev = prev;
        evFadeSlide(slides, dots, slides[current], total);
        restartBar();
        if (onShow) onShow(slides[current]);
      }

      function startAuto() {
        clearInterval(timer);
        // Pausat läge överlever navigering: den som pausat för att läsa en tabell vill
        // kunna bläddra vidare för hand utan att rotationen smyger igång igen.
        if (paused) return;
        timer = setInterval(() => showSlide(current + 1), 9000);
      }

      function setPaused(p) {
        paused = p;
        clearInterval(timer);
        if (bar) bar.style.animationPlayState = p ? 'paused' : 'running';
        if (btn) {
          btn.setAttribute('aria-pressed', p ? 'true' : 'false');
          btn.title = p ? 'Fortsätt karusellen' : 'Pausa karusellen';
          const icon = btn.querySelector('.ev-fact-play-icon');
          const label = btn.querySelector('[data-carousel-play-label]');
          if (icon) icon.textContent = p ? '▶' : '⏸';
          if (label) label.textContent = p ? 'Spela' : 'Paus';
        }
        if (!p) { restartBar(); startAuto(); }
      }

      card.querySelector('[data-carousel-prev]')?.addEventListener('click', () => { showSlide(current - 1); startAuto(); });
      card.querySelector('[data-carousel-next]')?.addEventListener('click', () => { showSlide(current + 1); startAuto(); });
      dots.forEach((d, i) => d.addEventListener('click', () => { showSlide(i); startAuto(); }));
      btn?.addEventListener('click', () => setPaused(!paused));

      equalizeHeight();
      // Höjden är mätt i pixlar och måste räknas om när radbrytningarna ändras.
      let rz;
      window.addEventListener('resize', () => { clearTimeout(rz); rz = setTimeout(equalizeHeight, 150); });

      restartBar();
      startAuto();
    }

    evInitCarousel('ev-funfact-carousel', '.ev-funfact-slide');

    evInitCarousel('ev-fact-carousel', '.ev-fact-slide', (activeSlide) => {
      const highlighted = activeSlide.querySelector('tr[style*="rgba(59,130,246"]');
      if (!highlighted) return;
      const tableWrap = highlighted.closest('[style*="overflow"]') || highlighted.closest('div');
      setTimeout(() => {
        if (tableWrap && tableWrap.scrollHeight > tableWrap.clientHeight) {
          tableWrap.scrollTop = highlighted.offsetTop - tableWrap.offsetTop - 40;
        }
      }, 200);
    });
  }

  // ===== CHATTBOT =====
  let chatHistory = (function(){ try{ return JSON.parse(localStorage.getItem('ev-chat')||'[]'); }catch(e){ return []; } })();
  let evChatExpanded = (function(){ try{ return localStorage.getItem('ev-chat-max') === '1'; }catch(e){ return false; } })();

  // ── Demospärr: alla utan prenumeration får EV_DEMO_MAX frågor per rullande timme ──
  // Prenumerationsfrågor (Vad ingår-knappen) och omförsök räknas inte.
  // Samma konto som Bilrådgivningen/Bränslekostnad via ca_token på elitrobban.se.
  //
  // ÄNDRAT 2026-08-22, två fel i samma spärr:
  //
  //  1. Ett KONTO gav obegränsat. evIsLoggedIn() frågade bara om det fanns ett token, så ett
  //     gratiskonto gav bort precis det prenumerationen säljer — samma fel som bensinkostnad.js
  //     hade fram till 08-20 och som bcHasUnlimited() rättade. Nu krävs ca_status === 'active'.
  //     Man behöver inte vara inloggad alls för att chatta.
  //  2. Gränsen var 3 frågor LIVSTID. Nu 30 per rullande timme, samma tal som sökningarna i
  //     CarAdvice (CarController.SEARCHES_PER_HOUR). Varje fråga är ett riktigt Groq-anrop, så
  //     till skillnad från bränslekalkylatorn finns här en faktisk kostnad att bromsa.
  var EV_DEMO_MAX = 30;
  var EV_DEMO_WINDOW_MS = 3600000;
  var evAuthValid = null; // null = ej serververifierad; true/false = svar från /api/auth/me
  function evIsLoggedIn() {
    if (document.body.classList.contains('logged-in')) return true; // WP-inloggad
    if (evAuthValid !== null) return evAuthValid;                   // serververifierat
    return !!localStorage.getItem('ca_token');                      // optimistiskt innan koll
  }
  /**
   * Obegränsat kräver AKTIV PRENUMERATION, inte bara ett konto. Spegling av bcHasUnlimited()
   * i bensinkostnad.js, inklusive gränsfallen: en prenumerant får inte låsas ute när servern
   * kallstartar (cachat ca_status gäller tills serverkollen svarat), och ett gammalt
   * ca_status='active' utan giltigt token ger inte tillgång.
   */
  function evHasUnlimited() {
    if (document.body.classList.contains('logged-in')) return true; // sajtägarens genväg, se nedan
    if (evAuthValid === false) return false;
    // Token KRÄVS, inte bara statusen. Utan det villkoret räckte ett kvarglömt
    // ca_status='active' i webbläsarlagret för att stänga av räkningen helt, medan
    // statusbaren ovanför appen (ev-charging.js) samtidigt visade 'Demo 30 av 30' —
    // den kräver token. Två olika svar på samma fråga såg ut som en trasig räknare.
    return localStorage.getItem('ca_status') === 'active' && !!localStorage.getItem('ca_token');
  }
  /** Tidsstämplar inom fönstret. Trasigt/gammalt värde ger tom lista — hellre släppa igenom. */
  function evDemoTimes() {
    var grans = Date.now() - EV_DEMO_WINDOW_MS;
    try {
      var raw = JSON.parse(localStorage.getItem('ev_demo_times') || '[]');
      if (!Array.isArray(raw)) return [];
      return raw.filter(function(t) { return typeof t === 'number' && t >= grans; });
    } catch (e) { return []; }
  }
  function evDemoRemaining() {
    return Math.max(0, EV_DEMO_MAX - evDemoTimes().length);
  }
  function evConsumeDemo() {
    var times = evDemoTimes();
    times.push(Date.now());
    try { localStorage.setItem('ev_demo_times', JSON.stringify(times)); } catch (e) {}
    evUpdateChatDemoUI();
    // Statusbaren ovanfor appen (ev-charging.js) laser samma nyckel men vet inte nar den
    // andras - utan puffen star den kvar pa full pott medan chatten raknar ner.
    if (typeof window.evRefreshQuotaBar === 'function') window.evRefreshQuotaBar();
  }
  function evUpdateChatDemoUI() {
    var bar = document.getElementById('ev-chat-demobar');
    if (!bar) return;
    bar.style.display = 'flex'; // alltid synlig — Info & prenumeration-knappen ska alltid finnas
    var info = document.getElementById('ev-chat-demoinfo');
    if (evHasUnlimited()) { if (info) info.textContent = ''; return; } // dölj demoräknaren, behåll knappen
    var left = document.getElementById('ev-chat-demoleft');
    var rem = evDemoRemaining();
    if (left) left.textContent = rem;
    if (info) info.innerHTML = rem > 0
      ? 'Demoläge · <b>' + rem + ' gratis fråg' + (rem === 1 ? 'a' : 'or') + ' kvar denna timme</b>'
      : 'Demo slut · <b>prenumerera för obegränsat</b>';
  }

  // Statiskt info-kort i chatten — ingen AI/backend-anrop, räknas aldrig mot demogränsen
  function evShowSubscriptionInfo() {
    var msgs = document.getElementById("ev-chat-messages");
    if (!msgs) return;
    var outer = document.createElement("div");
    var bubble = document.createElement("div");
    bubble.className = "ev-chat-bubble bot";
    bubble.innerHTML =
      '<div style="font-weight:800;margin-bottom:6px">💳 Prenumeration – 49 kr/mån</div>' +
      '<div style="font-size:.82rem;opacity:.85;margin-bottom:8px">Utan prenumeration är tjänsterna begränsade till 30 frågor i timmen. Som prenumerant får du <b>allt obegränsat</b>:</div>' +
      '<div style="display:flex;flex-direction:column;gap:5px;font-size:.82rem">' +
        '<div>🚗 <b>AI Bilrådgivning</b> – obegränsad chatt och bilförslag</div>' +
        '<div>⚡ <b>AI EV Laddassistent</b> – obegränsad chatt, laddstationer och ruttplanering</div>' +
        '<div>⛽ <b>Bränslekostnadsberäkning</b> – obegränsade beräkningar</div>' +
      '</div>' +
      '<div style="font-size:.75rem;opacity:.6;margin-top:8px">Avbryt när som helst.</div>';
    var cta = document.createElement("button");
    cta.textContent = "Prenumerera – 49 kr/mån →";
    cta.style.cssText = "margin-top:10px;width:100%;background:linear-gradient(135deg,#2563eb,#4f46e5);color:#fff;border:none;border-radius:9px;padding:9px 14px;font-size:.82rem;font-weight:800;cursor:pointer";
    cta.onclick = evOpenSubscribePopup;
    bubble.appendChild(cta);
    outer.appendChild(bubble);
    msgs.appendChild(outer);
    msgs.scrollTop = msgs.scrollHeight;
    if (!chatIsOpen()) chatSetOpen(true); // öppna chatten om stängd
  }
  function evVerifyLogin() {
    var token = localStorage.getItem('ca_token');
    if (!token) { evAuthValid = false; evUpdateChatDemoUI(); return; }
    fetch('https://caradvice.onrender.com/api/auth/me', { headers: { 'Authorization': 'Bearer ' + token } })
      .then(function(res) {
        if (res.ok) { evAuthValid = true; return res.json().then(function(u){ if (u && u.subscriptionStatus) localStorage.setItem('ca_status', u.subscriptionStatus); }); }
        if (res.status === 401 || res.status === 403) {
          evAuthValid = false;
          localStorage.removeItem('ca_token'); localStorage.removeItem('ca_email'); localStorage.removeItem('ca_status');
        }
      }).catch(function(){}).then(function(){ evUpdateChatDemoUI(); });
  }
  function evOpenSubscribePopup() {
    if (window.evOpenSubscribe) { window.evOpenSubscribe(); return; }
    window.open('https://caradvice.onrender.com/subscribe.html?from=elbilsladdning', '_blank', 'width=480,height=650,resizable=yes');
  }

  function initChat() {
    const style = document.createElement("style");
    style.textContent = `
      .ev-chat-fab-wrap {
        position:fixed;bottom:24px;right:24px;z-index:9999;
        display:flex;flex-direction:column;align-items:center;gap:6px;
      }
      .ev-chat-fab-label {
        background:rgba(251,191,36,0.15);border:1px solid rgba(251,191,36,0.35);
        color:#fbbf24;font-size:11px;font-weight:700;padding:3px 10px;
        border-radius:20px;white-space:nowrap;letter-spacing:0.04em;
        animation:ev-label-pulse 3s ease-in-out infinite;
      }
      @keyframes ev-label-pulse {
        0%,100%{opacity:.7;transform:translateY(0)}
        50%{opacity:1;transform:translateY(-2px)}
      }
      .ev-chat-fab-ring {
        position:relative;display:flex;align-items:center;justify-content:center;
      }
      .ev-chat-spark {
        position:absolute;font-size:14px;line-height:1;pointer-events:none;
        animation:ev-spark 2.4s ease-in-out infinite;
      }
      .ev-chat-spark:nth-child(1){top:-18px;left:50%;transform:translateX(-50%);animation-delay:0s;}
      .ev-chat-spark:nth-child(2){top:18px;left:-20px;animation-delay:.8s;}
      .ev-chat-spark:nth-child(3){top:18px;right:-20px;animation-delay:1.6s;}
      @keyframes ev-spark {
        0%,100%{opacity:.3;transform:scale(.8) translateY(0);}
        50%{opacity:1;transform:scale(1.2) translateY(-4px);}
      }
      .ev-chat-fab {
        width:56px;height:56px;border-radius:18px;
        background:linear-gradient(145deg,#1e3a8a,#2563eb);
        border:none;cursor:pointer;
        box-shadow:0 4px 16px rgba(29,78,216,.55);
        display:flex;align-items:center;justify-content:center;
        transition:transform .15s,box-shadow .15s;
      }
      .ev-chat-fab:hover{transform:scale(1.1);box-shadow:0 6px 22px rgba(29,78,216,.7);}
      .ev-chat-panel {
        position:fixed;bottom:92px;right:24px;z-index:9998;
        width:390px;
        max-height:min(560px, calc(100vh - 130px));
        max-height:min(560px, calc(100dvh - 130px));
        background:rgba(5,10,24,0.82);
        backdrop-filter:blur(32px) saturate(1.4);-webkit-backdrop-filter:blur(32px) saturate(1.4);
        border:1px solid rgba(147,197,253,0.22);border-radius:22px;
        box-shadow:0 12px 60px rgba(0,0,0,.75),0 0 0 1px rgba(255,255,255,0.05) inset,0 1px 0 rgba(147,197,253,0.12) inset;
        display:none;flex-direction:column;overflow:hidden;
        font-family:inherit;
        animation:ev-panel-in .2s cubic-bezier(.22,1,.36,1);
      }
      /* Oppet/stangt bor i en klass pa body, inte i panelens inline style.
         Da kan FAB-regeln nedan uttrycka "expanderad OCH oppen" i ren CSS
         i stallet for att JS ska spegla tillstandet at den. */
      body.ev-chat-open .ev-chat-panel{display:flex;}
      @keyframes ev-panel-in{from{opacity:0;transform:translateY(12px) scale(.97)}to{opacity:1;transform:none}}
      .ev-chat-header {
        background:linear-gradient(135deg,rgba(17,40,110,0.95),rgba(29,78,216,0.88));
        backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);
        border-bottom:1px solid rgba(147,197,253,0.18);
        color:#fff;padding:14px 16px 13px;
        display:flex;align-items:center;justify-content:space-between;
        flex-shrink:0;gap:8px;
      }
      .ev-chat-header-title { display:flex;flex-direction:column;gap:1px; }
      .ev-chat-header-name { font-weight:700;font-size:14px;display:flex;align-items:center;gap:7px; }
      .ev-chat-header-online {
        width:7px;height:7px;border-radius:50%;background:#22c55e;flex-shrink:0;
        box-shadow:0 0 6px rgba(34,197,94,0.8);
        animation:ev-pulse-green 2s ease-in-out infinite;
      }
      @keyframes ev-pulse-green{0%,100%{box-shadow:0 0 6px rgba(34,197,94,0.8)}50%{box-shadow:0 0 12px rgba(34,197,94,1)}}
      .ev-chat-header-sub { font-size:10px;font-weight:500;color:rgba(147,197,253,0.65);letter-spacing:.04em; }
      .ev-chat-header-actions { display:flex;align-items:center;gap:6px; }
      .ev-chat-header-clear {
        background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.16);
        color:rgba(255,255,255,0.7);font-size:11px;font-weight:600;padding:4px 10px;
        border-radius:20px;cursor:pointer;transition:all .15s;white-space:nowrap;
      }
      .ev-chat-header-clear:hover { background:rgba(255,255,255,0.15);color:#fff;border-color:rgba(255,255,255,0.28); }
      .ev-chat-header-close {
        background:rgba(255,255,255,0.07);border:1px solid rgba(255,255,255,0.13);
        color:rgba(255,255,255,0.7);font-size:15px;width:26px;height:26px;
        border-radius:50%;cursor:pointer;display:flex;align-items:center;justify-content:center;
        padding:0;line-height:1;transition:all .12s;
      }
      .ev-chat-header-close:hover { background:rgba(255,255,255,0.18);color:#fff; }
      .ev-chat-header-expand {
        background:rgba(255,255,255,0.07);border:1px solid rgba(255,255,255,0.13);
        color:rgba(255,255,255,0.7);width:26px;height:26px;
        border-radius:50%;cursor:pointer;display:flex;align-items:center;justify-content:center;
        padding:0;line-height:0;transition:all .12s;
      }
      .ev-chat-header-expand:hover { background:rgba(255,255,255,0.18);color:#fff; }
      .ev-chat-header-expand svg { display:block;transition:transform .18s; }
      body.ev-chat-max .ev-chat-header-expand svg { transform:rotate(180deg); }
      .ev-chat-messages {
        flex:1;overflow-y:auto;padding:16px 13px;
        display:flex;flex-direction:column;gap:11px;
        background:transparent;min-height:0;
      }
      .ev-chat-messages::-webkit-scrollbar { width:3px; }
      .ev-chat-messages::-webkit-scrollbar-track { background:transparent; }
      .ev-chat-messages::-webkit-scrollbar-thumb { background:rgba(147,197,253,0.2);border-radius:3px; }
      .ev-chat-bubble {
        max-width:84%;padding:10px 14px;border-radius:16px;
        font-size:13px;line-height:1.65;word-break:break-word;
      }
      .ev-chat-bubble.bot {
        background:rgba(10,18,40,0.75);
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border:1px solid rgba(147,197,253,0.14);
        border-radius:4px 16px 16px 16px;align-self:flex-start;color:#dde8ff;
        box-shadow:0 2px 12px rgba(0,0,0,.25);
      }
      .ev-chat-bubble.bot strong { color:#93c5fd; }
      .ev-chat-bubble.bot ul { margin:6px 0 2px 16px;padding:0;display:flex;flex-direction:column;gap:3px; }
      .ev-chat-bubble.bot li { list-style:disc; }
      .ev-chat-bubble.user {
        background:linear-gradient(135deg,#1d4ed8,#3b82f6);
        border:none;
        color:#fff;border-radius:16px 16px 4px 16px;align-self:flex-end;
        box-shadow:0 3px 14px rgba(59,130,246,0.4);
      }
      .ev-chat-quick {
        padding:10px 13px 6px;display:flex;flex-wrap:wrap;gap:7px;flex-shrink:0;
        background:rgba(4,8,20,0.55);border-top:1px solid rgba(147,197,253,0.1);
      }
      /* JS-styrt lage: satts nar samtalet borjat sa att snabbknapparna forsvinner.
         Egen klass i stallet for inline display, annars kan CSS-reglerna nedan
         (platsbrist i liggande lage, expanderat lage) aldrig ta over. */
      .ev-chat-quick.ev-chat-quick-off { display:none; }
      .ev-chat-quick-btn {
        background:rgba(59,130,246,0.1);border:1px solid rgba(147,197,253,0.22);color:#93c5fd;
        border-radius:20px;padding:5px 13px;font-size:12px;font-weight:600;
        cursor:pointer;transition:all .15s;white-space:nowrap;
        backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);
      }
      .ev-chat-quick-btn:hover { background:rgba(59,130,246,0.28);color:#fff;border-color:rgba(147,197,253,0.5); }
      .ev-chat-input-row {
        display:flex;gap:8px;padding:10px 13px 11px;
        border-top:1px solid rgba(147,197,253,0.1);
        background:rgba(4,8,20,0.55);flex-shrink:0;
      }
      .ev-chat-input {
        flex:1;border:1.5px solid rgba(147,197,253,0.18);border-radius:22px;
        padding:9px 15px;font-size:13px;outline:none;
        background:rgba(10,18,40,0.7);color:#f0f4ff;transition:border-color .15s,box-shadow .15s;
      }
      .ev-chat-input::placeholder { color:rgba(200,215,255,0.3); }
      .ev-chat-input:focus { border-color:rgba(147,197,253,0.55);box-shadow:0 0 0 3px rgba(59,130,246,0.15); }
      .ev-chat-send {
        width:40px;height:40px;border-radius:50%;
        background:linear-gradient(135deg,#1d4ed8,#3b82f6);
        color:#fff;border:none;cursor:pointer;
        font-size:16px;display:flex;align-items:center;justify-content:center;
        flex-shrink:0;transition:all .15s;
        box-shadow:0 2px 12px rgba(59,130,246,0.45);
      }
      .ev-chat-send:hover { background:linear-gradient(135deg,#2563eb,#60a5fa);box-shadow:0 4px 18px rgba(59,130,246,0.6);transform:scale(1.06); }
      .ev-chat-typing { display:flex;gap:4px;align-items:center;padding:4px 0; }
      .ev-chat-typing span {
        width:7px;height:7px;border-radius:50%;background:rgba(147,197,253,0.45);
        animation:ev-bounce .9s infinite;display:inline-block;
      }
      .ev-chat-typing span:nth-child(2) { animation-delay:.15s; }
      .ev-chat-typing span:nth-child(3) { animation-delay:.3s; }
      @keyframes ev-bounce { 0%,80%,100%{transform:translateY(0)} 40%{transform:translateY(-6px)} }
      .ev-chat-cursor{display:inline-block;width:2px;height:13px;background:#93c5fd;margin-left:2px;border-radius:1px;animation:ev-cursor-blink .55s steps(1) infinite;vertical-align:middle;}
      @keyframes ev-cursor-blink{0%,100%{opacity:1}50%{opacity:0}}
      .ev-chat-feedback{display:flex;gap:6px;margin-top:6px;padding-left:2px;align-items:center;}
      .ev-chat-thumb{background:none;border:1px solid rgba(147,197,253,0.16);color:rgba(147,197,253,0.35);font-size:12px;padding:2px 8px;border-radius:10px;cursor:pointer;transition:all .15s;line-height:1.5;}
      .ev-chat-thumb:hover{border-color:rgba(147,197,253,0.5);color:#93c5fd;}
      .ev-chat-thumb.voted{border-color:rgba(147,197,253,0.6);color:#93c5fd;background:rgba(147,197,253,0.08);}
      .ev-chat-retry{background:none;border:1px solid rgba(239,68,68,0.3);color:rgba(239,68,68,0.65);font-size:11px;font-weight:600;padding:4px 11px;border-radius:20px;cursor:pointer;margin-top:7px;display:inline-block;transition:all .15s;}
      .ev-chat-retry:hover{border-color:rgba(239,68,68,0.6);color:#ef4444;}
      @media(max-width:640px){
        /* Panelen ska vara ett kort i underkanten — inte ta over hela skarmen */
        .ev-chat-panel{
          width:auto;left:10px;right:10px;bottom:88px;border-radius:18px;
          max-height:min(440px, 58vh);
          max-height:min(440px, 58dvh);
        }
        .ev-chat-fab-wrap{right:12px;bottom:12px;gap:4px;}
        .ev-chat-fab{width:48px;height:48px;border-radius:15px;}
        .ev-chat-fab svg{width:29px;height:34px;}
        .ev-chat-fab-label{font-size:10px;padding:2px 8px;}
        .ev-chat-spark{font-size:12px;}
        .ev-chat-spark:nth-child(2){top:15px;left:-16px;}
        .ev-chat-spark:nth-child(3){top:15px;right:-16px;}
        .ev-chat-header{padding:11px 12px 10px;}
        .ev-chat-header-name{font-size:13px;}
        .ev-chat-messages{padding:12px 10px;gap:9px;}
        .ev-chat-bubble{max-width:90%;padding:9px 12px;}
        .ev-chat-quick{padding:8px 10px 4px;gap:6px;}
        .ev-chat-quick-btn{font-size:11px;padding:4px 11px;}
        .ev-chat-input-row{padding:8px 10px 9px;}
      }
      /* Liggande mobil: nastan ingen hojd kvar — hall panelen riktigt lag */
      @media(max-width:900px) and (max-height:480px){
        .ev-chat-panel{bottom:72px;max-height:min(300px, 70vh);max-height:min(300px, 70dvh);}
        .ev-chat-quick{display:none;}
        /* Brasklappen krymps men doljs ALDRIG — snabbknapparna ovan ar en genvag och kan
           tas bort nar hojden tryter, en varning om att svaren kan vara fel kan inte. */
        .ev-chat-disclaimer{padding:2px 10px 5px !important;font-size:.62rem !important;}
        .ev-chat-fab-label{display:none;}
        .ev-chat-fab{width:44px;height:44px;}
        .ev-chat-fab svg{width:27px;height:31px;}
      }
      /* Expanderat lage. Klassen sitter pa body sa att aven .ev-chat-fab-wrap gar att na,
         och sa att specificiteten (0,2,1) slar bade bas- och mediaregler ovan. */
      body.ev-chat-max .ev-chat-panel{
        width:min(560px, calc(100vw - 48px));
        max-height:calc(100vh - 120px);
        max-height:calc(100dvh - 120px);
      }
      /* Smal eller lag skarm: expanderat = helskarmsark, FAB:en i vagen doljs */
      @media(max-width:640px),(max-height:480px){
        body.ev-chat-max .ev-chat-panel{
          left:8px;right:8px;top:8px;bottom:8px;
          width:auto;max-height:none;border-radius:16px;
        }
        body.ev-chat-open.ev-chat-max .ev-chat-fab-wrap{display:none;}
        body.ev-chat-max .ev-chat-quick:not(.ev-chat-quick-off){display:flex;}
      }
    `;
    document.head.appendChild(style);

    const root = document.createElement("div");
    root.innerHTML = `
      <div class="ev-chat-fab-wrap">
        <span class="ev-chat-fab-label">✨ Fråga AI</span>
        <div class="ev-chat-fab-ring">
          <span class="ev-chat-spark">⚡</span>
          <span class="ev-chat-spark">⚡</span>
          <span class="ev-chat-spark">⚡</span>
        <button class="ev-chat-fab" id="ev-chat-fab" title="Fråga EV-assistenten">
          <svg viewBox="0 0 44 52" width="34" height="40" xmlns="http://www.w3.org/2000/svg">
            <defs>
              <radialGradient id="hg" cx="38%" cy="32%"><stop offset="0%" stop-color="#fef3c7"/><stop offset="100%" stop-color="#f59e0b"/></radialGradient>
              <radialGradient id="bg" cx="38%" cy="30%"><stop offset="0%" stop-color="#fde68a"/><stop offset="100%" stop-color="#d97706"/></radialGradient>
            </defs>
            <!-- antenna -->
            <line x1="22" y1="1" x2="22" y2="6" stroke="#92400e" stroke-width="1.8" stroke-linecap="round"/>
            <circle cx="22" cy="1" r="2" fill="#fbbf24"/>
            <!-- head -->
            <ellipse cx="22" cy="13" rx="10" ry="9" fill="url(#hg)" stroke="#d97706" stroke-width="0.8"/>
            <!-- eyes -->
            <ellipse cx="18.5" cy="12" rx="2" ry="2.2" fill="#1e3a8a"/>
            <ellipse cx="25.5" cy="12" rx="2" ry="2.2" fill="#1e3a8a"/>
            <circle cx="19.2" cy="11.2" r="0.7" fill="#fff"/>
            <circle cx="26.2" cy="11.2" r="0.7" fill="#fff"/>
            <!-- smile -->
            <path d="M17.5 16.5 Q22 20.5 26.5 16.5" stroke="#92400e" stroke-width="1.4" fill="none" stroke-linecap="round"/>
            <!-- neck -->
            <rect x="19.5" y="21" width="5" height="3" rx="1" fill="#f59e0b"/>
            <!-- body (3 rolls like michelin) -->
            <ellipse cx="22" cy="29" rx="12" ry="7" fill="url(#bg)" stroke="#d97706" stroke-width="0.7"/>
            <ellipse cx="22" cy="37" rx="10" ry="6" fill="url(#bg)" stroke="#d97706" stroke-width="0.7"/>
            <ellipse cx="22" cy="44" rx="8" ry="5" fill="url(#bg)" stroke="#d97706" stroke-width="0.7"/>
            <!-- arms -->
            <ellipse cx="9" cy="31" rx="4.5" ry="6.5" fill="url(#bg)" stroke="#d97706" stroke-width="0.7" transform="rotate(-25 9 31)"/>
            <ellipse cx="35" cy="31" rx="4.5" ry="6.5" fill="url(#bg)" stroke="#d97706" stroke-width="0.7" transform="rotate(25 35 31)"/>
            <!-- lightning bolt on chest -->
            <path d="M20 26 L17 33 L21.5 31 L19 38" fill="#1e3a8a" stroke="#1e3a8a" stroke-width="0.5" stroke-linejoin="round"/>
            <!-- sparks on arms -->
            <text x="4" y="28" font-size="7" fill="#fef08a">⚡</text>
            <text x="32" y="28" font-size="7" fill="#fef08a">⚡</text>
          </svg>
        </button>
        </div>
      </div>
      <div class="ev-chat-panel" id="ev-chat-panel">
        <div class="ev-chat-header">
          <div class="ev-chat-header-title">
            <div class="ev-chat-header-name">
              <span class="ev-chat-header-online"></span>⚡ EV-Assistenten
            </div>
            <div class="ev-chat-header-sub">AI • Laddning &amp; Elbilar</div>
          </div>
          <div class="ev-chat-header-actions">
            <button class="ev-chat-header-clear" id="ev-chat-clear">Rensa</button>
            <button class="ev-chat-header-expand" id="ev-chat-expand" title="Expandera chatten" aria-label="Expandera chatten" aria-expanded="false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 15l6-6 6 6"/></svg>
            </button>
            <button class="ev-chat-header-close" id="ev-chat-close">✕</button>
          </div>
        </div>
        <div class="ev-chat-messages" id="ev-chat-messages"></div>
        <div class="ev-chat-quick" id="ev-chat-quick">
          <button class="ev-chat-quick-btn" data-q="Ge mig råd för att köpa elbil">🚗 Elbilsköp</button>
          <button class="ev-chat-quick-btn" data-q="Vilken elbil laddar snabbast med DC?">⚡ Snabbaste DC</button>
          <button class="ev-chat-quick-btn" data-q="Vilken elbil har längst räckvidd?">🛣️ Räckvidd</button>
          <button class="ev-chat-quick-btn" data-q="Var laddar jag billigast?">💰 Billigast</button>
        </div>
        <div class="ev-chat-demobar" id="ev-chat-demobar" style="display:none;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:6px 8px;padding:7px 12px;font-size:.74rem;color:rgba(147,197,253,.78);border-top:1px solid rgba(148,163,184,.12);">
          <span id="ev-chat-demoinfo">Demoläge · <b><span id="ev-chat-demoleft">30</span> gratis frågor kvar</b></span>
          <button id="ev-chat-subbtn" type="button" style="background:rgba(59,130,246,.15);border:1px solid rgba(59,130,246,.32);color:#93c5fd;border-radius:8px;padding:4px 10px;font-size:.72rem;font-weight:700;cursor:pointer;white-space:nowrap;">💳 Info &amp; prenumeration</button>
        </div>
        <div class="ev-chat-input-row">
          <input class="ev-chat-input" id="ev-chat-input" type="text" placeholder="Skriv en fråga…" autocomplete="off" />
          <button class="ev-chat-send" id="ev-chat-send">
            <svg viewBox="0 0 20 20" width="18" height="18" fill="currentColor"><path d="M2 10.5l15-8-5 8 5 8z"/><path d="M17 2.5L9 10.5"/></svg>
          </button>
        </div>
        <!-- Samma brasklapp som bilrådgivningens chatt (ca-chat-disclaimer i
             car-advice-chat.js), ordagrant och på samma plats: sist i panelen, under
             inmatningsraden. Den här chatten svarar om räckvidd, laddeffekter och priser
             — siffror en läsare lätt tar för verifierade. Färgerna följer elbilsappens
             egen palett i stället för bilrådgivningens lila. -->
        <div class="ev-chat-disclaimer" style="padding:4px 12px 8px;font-size:.68rem;color:rgba(147,197,253,.46);line-height:1.3">
          🤖 AI-svar kan innehålla fel — dubbelkolla viktiga fakta.
        </div>
      </div>
    `;
    document.body.appendChild(root);

    chatAppendBot("Undrar du vilken elbil du bör köpa? 🚗 Jag kan ge dig tips! Välj ett ämne nedan eller ställ en egen fråga.", false);

    if (chatHistory.length > 0) {
      document.getElementById("ev-chat-quick").classList.add("ev-chat-quick-off");
      chatHistory.forEach(function(m) {
        if (m.role === "user") chatAppendUser(m.content);
        else if (m.role === "assistant") chatAppendBot(m.content, false);
      });
    }

    document.getElementById("ev-chat-fab").addEventListener("click", chatToggle);
    document.getElementById("ev-chat-close").addEventListener("click", chatToggle);
    document.getElementById("ev-chat-expand").addEventListener("click", chatToggleExpanded);
    document.getElementById("ev-chat-send").addEventListener("click", chatSend);
    document.getElementById("ev-chat-input").addEventListener("keydown", e => { if (e.key === "Enter") chatSend(); });
    document.getElementById("ev-chat-clear").addEventListener("click", chatClear);
    document.querySelectorAll(".ev-chat-quick-btn").forEach(btn =>
      btn.addEventListener("click", () => chatSendMessage(btn.dataset.q))
    );

    document.body.classList.toggle("ev-chat-max", evChatExpanded);
    chatUpdateExpandBtn();

    // Info & prenumeration-knappen: statiskt kort, ingen AI/backend, alltid gratis
    var subBtn = document.getElementById("ev-chat-subbtn");
    if (subBtn) subBtn.addEventListener("click", evShowSubscriptionInfo);

    // Visa demoräknaren för utloggade + verifiera ev. inloggning mot servern
    evUpdateChatDemoUI();
    evVerifyLogin();

    // Inloggning via CarAdvice-popupen (samma konto som övriga tjänster)
    window.addEventListener("message", function(ev) {
      if (!ev.data || !ev.data.type) return;
      if (ev.data.type === "CA_LOGIN" || ev.data.type === "CA_SUBSCRIBED") {
        if (ev.data.token)  localStorage.setItem("ca_token", ev.data.token);
        if (ev.data.email)  localStorage.setItem("ca_email", ev.data.email);
        if (ev.data.status) localStorage.setItem("ca_status", ev.data.status);
        evAuthValid = null; evVerifyLogin();
      }
      if (ev.data.type === "CA_LOGOUT") {
        localStorage.removeItem("ca_token"); localStorage.removeItem("ca_email"); localStorage.removeItem("ca_status");
        evAuthValid = false; evUpdateChatDemoUI();
      }
    });
  }

  function chatIsOpen() {
    return document.body.classList.contains("ev-chat-open");
  }

  function chatSetOpen(open) {
    document.body.classList.toggle("ev-chat-open", open);
    if (open) document.getElementById("ev-chat-input").focus();
  }

  function chatToggle() {
    chatSetOpen(!chatIsOpen());
  }

  function chatUpdateExpandBtn() {
    const btn = document.getElementById("ev-chat-expand");
    if (!btn) return;
    const lbl = evChatExpanded ? "Minska chatten" : "Expandera chatten";
    btn.title = lbl;
    btn.setAttribute("aria-label", lbl);
    btn.setAttribute("aria-expanded", evChatExpanded ? "true" : "false");
  }

  function chatToggleExpanded() {
    evChatExpanded = !evChatExpanded;
    try { localStorage.setItem("ev-chat-max", evChatExpanded ? "1" : "0"); } catch(e) {}
    document.body.classList.toggle("ev-chat-max", evChatExpanded);
    chatUpdateExpandBtn();
    const msgs = document.getElementById("ev-chat-messages");
    if (msgs) msgs.scrollTop = msgs.scrollHeight;
  }

  function chatMarkdown(text) {
    return text
      .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")
      .replace(/\*\*(.+?)\*\*/g,"<strong>$1</strong>")
      .replace(/\*(.+?)\*/g,"<em>$1</em>")
      .replace(/^[-•]\s+(.+)$/gm,"<li>$1</li>")
      .replace(/(<li>.*<\/li>)/s, "<ul>$1</ul>")
      .replace(/\n/g,"<br>");
  }

  function chatAppendBot(text, animate) {
    const msgs = document.getElementById("ev-chat-messages");
    const outer = document.createElement("div");
    const bubble = document.createElement("div");
    bubble.className = "ev-chat-bubble bot";
    outer.appendChild(bubble);
    msgs.appendChild(outer);
    msgs.scrollTop = msgs.scrollHeight;
    if (animate !== false && text.length > 0) {
      let i = 0;
      const speed = Math.max(6, Math.min(20, 2600 / text.length));
      (function tick() {
        i += 3;
        if (i >= text.length) {
          bubble.innerHTML = chatMarkdown(text);
          evAddFeedback(outer);
          msgs.scrollTop = msgs.scrollHeight;
        } else {
          bubble.textContent = text.slice(0, i);
          const cur = document.createElement("span");
          cur.className = "ev-chat-cursor";
          bubble.appendChild(cur);
          msgs.scrollTop = msgs.scrollHeight;
          setTimeout(tick, speed);
        }
      })();
    } else {
      bubble.innerHTML = chatMarkdown(text);
      if (animate !== false) evAddFeedback(outer);
    }
    return outer;
  }

  function evAddFeedback(outer) {
    const fb = document.createElement("div");
    fb.className = "ev-chat-feedback";
    fb.innerHTML = '<button class="ev-chat-thumb">👍</button><button class="ev-chat-thumb">👎</button>';
    fb.querySelectorAll(".ev-chat-thumb").forEach(function(btn) {
      btn.addEventListener("click", function() {
        fb.querySelectorAll(".ev-chat-thumb").forEach(function(b) { b.classList.remove("voted"); });
        btn.classList.add("voted");
        setTimeout(function() { fb.innerHTML = '<span style="font-size:11px;color:rgba(147,197,253,0.45)">Tack!</span>'; }, 350);
      });
    });
    outer.appendChild(fb);
  }

  function buildStationContext() {
    const d = state.lastData;
    const car = state.carIndex !== null ? state.cars[state.carIndex] : null;
    const battery = car?.batteryKwh ?? null;
    const contextParts = [];

    // Selected car specs
    if (car) {
      const specParts = ["Vald bil: " + car.name];
      if (battery) specParts.push("batteri " + battery + " kWh");
      if (car.rangeKm) specParts.push("WLTP " + car.rangeKm + " km (~" + Math.round(car.rangeKm * 0.85) + " km verklig)");
      if (car.maxDcKw) specParts.push("DC max " + car.maxDcKw + " kW");
      if (car.maxAcKw) specParts.push("AC max " + car.maxAcKw + " kW");
      if (car.priceKr) specParts.push("pris ~" + Math.round(car.priceKr / 1000) + " 000 kr");
      contextParts.push(specParts.join(" · "));
    }

    // Charging time calculator
    const calc = state.lastCalc;
    if (calc) {
      let calcStr = "Laddtidskalkylator: " + calc.carName + " · " + calc.fromPct + "% → " + calc.toPct + "% (" + calc.kwhCharge + " kWh) · " + calc.effKw + " kW effektiv · tid " + calc.timeStr;
      if (calc.cost) calcStr += " · kostnad ~" + calc.cost + " kr";
      if (calc.rangeAdded) calcStr += " · räckvidd tillkommer ~" + calc.rangeAdded + " km";
      if (calc.stationName) calcStr += " · vid station: " + calc.stationName;
      contextParts.push(calcStr);
    }

    // Carousel facts (top 3 per category from car database)
    if (state.cars.length > 0) {
      const allValid = state.cars.filter(c => c.priceKr > 0 && c.rangeKm > 0);
      const topDc = state.cars.filter(c => c.maxDcKw > 0 && c.priceKr > 0).sort((a, b) => b.maxDcKw - a.maxDcKw).slice(0, 3);
      const topRange = allValid.sort((a, b) => b.rangeKm - a.rangeKm).slice(0, 3);
      if (topDc.length) contextParts.push("Snabbaste DC (topp 3): " + topDc.map(c => c.name + " " + c.maxDcKw + " kW").join(", "));
      if (topRange.length) contextParts.push("Längst räckvidd (topp 3): " + topRange.map(c => c.name + " " + c.rangeKm + " km").join(", "));
    }

    if (!d || !d.stations || d.stations.length === 0) {
      return contextParts.length ? contextParts.join("\n") : null;
    }
    contextParts.push("--- LADDSTATIONER (" + (state.city || "din position") + ") ---");
    contextParts.push("Bil i sökning: " + d.carName);
    if (battery) {
      contextParts.push("Hemmaladdning (billigaste): ~1,50–3,50 kr/kWh · full laddning ~" + Math.round(battery * 1.5) + "–" + Math.round(battery * 3.5) + " kr");
    }
    const stationsSorted = d.stations.slice(0, 5).map(function(s) {
      const raw = s.chargepricePerKwh || s.usageCost || "";
      return Object.assign({}, s, { priceKr: tolkaLaddpris(raw).krPerKwh });
    });
    stationsSorted.forEach(function(s, i) {
      const priceStr = s.chargepricePerKwh || s.usageCost || "okänt pris";
      const fullCost = (battery && s.priceKr) ? " · full laddning ~" + Math.round(battery * s.priceKr) + " kr" : "";
      contextParts.push((i+1) + ". " + s.name + " — " + s.distanceKm.toFixed(1) + " km — " + Math.round(s.maxEffKw) + " kW " + s.connectorType + " — " + priceStr + fullCost);
    });
    const cheapest = stationsSorted.filter(s => s.priceKr).sort((a, b) => a.priceKr - b.priceKr)[0];
    if (cheapest) contextParts.push("Billigaste publik station: " + cheapest.name + " (" + (cheapest.chargepricePerKwh || cheapest.usageCost) + ", " + cheapest.distanceKm.toFixed(1) + " km bort)");
    if (d.recommendation) contextParts.push("AI-rekommendation: " + d.recommendation);
    if (d.carFact) contextParts.push("Faktaruta visad: " + d.carFact);

    const r = state.lastRoute;
    if (r) {
      contextParts.push("--- PLANERAD RUTT ---");
      const durStr = r.durationMin ? " (~" + Math.floor(r.durationMin / 60) + " tim " + (r.durationMin % 60) + " min körtid)" : "";
      contextParts.push("Från: " + r.startDisplay + " → Till: " + r.destination + " · " + r.distanceKm + " km" + durStr + " · Bil: " + r.carName);
      if (r.stopsNeeded === 0) {
        contextParts.push("Bilen klarar sträckan utan laddningsstopp.");
      } else {
        contextParts.push("Antal laddningsstopp längs rutten: " + r.stopsNeeded);
        r.stops.forEach(function(stop, i) {
          const s = stop.station;
          const price = s.chargepricePerKwh || s.usageCost || "okänt pris";
          contextParts.push("Stopp " + (i+1) + ": " + s.name + " · " + Math.round(s.maxEffKw) + " kW " + s.connectorType + " · " + price + " · " + stop.distanceFromStartKm + " km från start");
        });
      }
    }

    return contextParts.join("\n");
  }

  function saveChatHistory() {
    try { localStorage.setItem("ev-chat", JSON.stringify(chatHistory.slice(-20))); } catch(e) {}
  }

  function chatClear() {
    chatHistory = [];
    try { localStorage.removeItem("ev-chat"); } catch(e) {}
    const msgs = document.getElementById("ev-chat-messages");
    msgs.innerHTML = "";
    document.getElementById("ev-chat-quick").classList.remove("ev-chat-quick-off");
    chatAppendBot("Undrar du vilken elbil du bör köpa? 🚗 Jag kan ge dig tips! Välj ett ämne nedan eller ställ en egen fråga.", false);
  }

  function chatAppendUser(text) {
    const msgs = document.getElementById("ev-chat-messages");
    const div = document.createElement("div");
    div.innerHTML = `<div class="ev-chat-bubble user">${text}</div>`;
    msgs.appendChild(div);
    msgs.scrollTop = msgs.scrollHeight;
  }

  function chatSend() {
    const input = document.getElementById("ev-chat-input");
    const msg = input.value.trim();
    if (!msg) return;
    input.value = "";
    chatSendMessage(msg);
  }

  function evAddFollowupChips(text, outer) {
    const lower = text.toLowerCase();
    let chips = [];
    if (/räckvidd|km|wltp/.test(lower))        chips.push("Vilken elbil har längst räckvidd?", "Räcker det för pendling?");
    else if (/ladda|laddning|kw/.test(lower))   chips.push("Hur fort laddar den?", "Var hittar jag snabbladdare?");
    else if (/pris|kr|budget|köpa/.test(lower)) chips.push("Finns billigare alternativ?", "Ny eller begagnad?");
    else if (/tesla/.test(lower))               chips.push("Tesla vs Kia EV6?", "Har Tesla supercharger i Sverige?");
    else if (/kia|hyundai|ioniq/.test(lower))   chips.push("Jämför med Tesla?", "Vilken räckvidd har den?");
    if (/driftkostnad|kostnad|el per mil/.test(lower) && chips.length < 2) chips.push("Vad kostar det per mil?");
    if (/motor|prestanda|acceleration/.test(lower) && chips.length < 2)    chips.push("Vilken elbil är snabbast?");
    if (chips.length === 0) chips = ["Vilken elbil passar mig?", "Vad kostar laddning per månad?"];
    const wrap = document.createElement("div");
    wrap.style.cssText = "display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;";
    chips.slice(0, 3).forEach(function(chip) {
      const btn = document.createElement("button");
      btn.textContent = chip;
      btn.style.cssText = "padding:5px 11px;font-size:.72rem;background:rgba(59,130,246,.12);border:1px solid rgba(96,165,250,.25);border-radius:16px;color:#93c5fd;cursor:pointer;transition:background .15s;font-family:inherit;";
      btn.onmouseenter = function() { btn.style.background = "rgba(59,130,246,.22)"; };
      btn.onmouseleave = function() { btn.style.background = "rgba(59,130,246,.12)"; };
      btn.onclick = function() { wrap.remove(); document.getElementById("ev-chat-input").value = chip; chatSend(); };
      wrap.appendChild(btn);
    });
    outer.appendChild(wrap);
  }

  async function chatSendMessage(message, opts) {
    opts = opts || {};
    // Demospärr: alla utan prenumeration får EV_DEMO_MAX frågor i timmen. opts.free = prenumerationsknappen
    // (alltid gratis), opts.retry = omförsök (ska inte dra en till).
    if (!opts.free && !opts.retry && !evHasUnlimited() && evDemoRemaining() <= 0) {
      document.getElementById("ev-chat-quick").classList.add("ev-chat-quick-off");
      var gate = chatAppendBot("Du har använt dina " + EV_DEMO_MAX + " gratis frågor den här timmen. Vänta en stund, eller prenumerera för obegränsat — klicka “Vad ingår?” nedan för att läsa mer.", false);
      var lb = document.createElement("button");
      lb.className = "ev-chat-retry"; lb.textContent = "💳 Prenumerera";
      lb.onclick = evOpenSubscribePopup; gate.appendChild(lb);
      evUpdateChatDemoUI();
      return;
    }
    if (!opts.free && !opts.retry && !evHasUnlimited()) evConsumeDemo();
    document.getElementById("ev-chat-quick").classList.add("ev-chat-quick-off");
    chatAppendUser(message);
    chatHistory.push({ role: "user", content: message });
    saveChatHistory();

    const msgsEl = document.getElementById("ev-chat-messages");
    const typingDiv = document.createElement("div");
    typingDiv.innerHTML = `<div class="ev-chat-bubble bot"><div class="ev-chat-typing"><span></span><span></span><span></span></div></div>`;
    msgsEl.appendChild(typingDiv);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    const context = buildStationContext();
    const limited = chatHistory.slice(-10);

    let resp;
    try {
      resp = await fetch(`${API}/api/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: limited, context: context })
      });
    } catch (_) {
      typingDiv.remove();
      const errDiv = chatAppendBot("Kunde inte nå assistenten — kontrollera anslutningen.", false);
      const btn = document.createElement("button"); btn.className = "ev-chat-retry"; btn.textContent = "↺ Försök igen";
      btn.onclick = function() { errDiv.remove(); chatHistory.pop(); saveChatHistory(); chatSendMessage(message, {retry:true}); };
      errDiv.appendChild(btn);
      return;
    }

    typingDiv.remove();

    if (resp.status === 429) {
      chatAppendBot("Du har ställt för många frågor — vänta en minut och försök igen.", false);
      return;
    }
    if (!resp.ok) {
      const errDiv = chatAppendBot("Något gick fel (fel " + resp.status + ").", false);
      const btn = document.createElement("button"); btn.className = "ev-chat-retry"; btn.textContent = "↺ Försök igen";
      btn.onclick = function() { errDiv.remove(); chatHistory.pop(); saveChatHistory(); chatSendMessage(message, {retry:true}); };
      errDiv.appendChild(btn);
      return;
    }

    // Fallback for browsers without streaming support
    if (!resp.body || typeof resp.body.getReader !== "function") {
      try {
        const fb = await fetch(`${API}/api/chat`, {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ messages: limited, context: context })
        });
        const fbData = await fb.json();
        const fbReply = fbData.reply || fbData.error || "Inget svar.";
        chatHistory.push({ role: "assistant", content: fbReply });
        saveChatHistory();
        const fbOuter = chatAppendBot(fbReply, true);
        evAddFollowupChips(fbReply, fbOuter);
      } catch (_) { chatAppendBot("Kunde inte nå assistenten.", false); }
      return;
    }

    // Streaming bubble
    const outer = document.createElement("div");
    const bubble = document.createElement("div");
    bubble.className = "ev-chat-bubble bot";
    outer.appendChild(bubble);
    msgsEl.appendChild(outer);

    let fullText = "";
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = "";

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split("\n");
        buf = lines.pop();
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed.startsWith("data:")) continue;
          const data = trimmed.slice(5).trim();
          if (data === "[DONE]") break;
          try {
            const token = JSON.parse(data);
            if (token.startsWith("[ERR]")) throw new Error(token.slice(5));
            fullText += token;
            bubble.textContent = fullText;
            msgsEl.scrollTop = msgsEl.scrollHeight;
          } catch (parseErr) {
            if (parseErr.message && !parseErr.message.startsWith("JSON")) throw parseErr;
          }
        }
      }
    } catch (streamErr) {
      if (!fullText) {
        outer.remove();
        const errDiv = chatAppendBot(streamErr.message || "Kunde inte nå assistenten.", false);
        const btn = document.createElement("button"); btn.className = "ev-chat-retry"; btn.textContent = "↺ Försök igen";
        btn.onclick = function() { errDiv.remove(); chatHistory.pop(); saveChatHistory(); chatSendMessage(message, {retry:true}); };
        errDiv.appendChild(btn);
        return;
      }
    }

    bubble.innerHTML = chatMarkdown(fullText);
    evAddFeedback(outer);
    evAddFollowupChips(fullText, outer);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    chatHistory.push({ role: "assistant", content: fullText });
    saveChatHistory();
  }

  window.evCalcUpdate = function evCalcUpdate() {
    if (state.carIndex === null || !state.cars.length) return;
    const car = state.cars[state.carIndex];
    const fromEl   = document.getElementById('ev-calc-from');
    const toEl     = document.getElementById('ev-calc-to');
    const resultEl = document.getElementById('ev-calc-result');
    if (!fromEl || !toEl || !resultEl) return;
    let fromPct = parseInt(fromEl.value);
    let toPct   = parseInt(toEl.value);
    if (toPct <= fromPct) { toPct = Math.min(fromPct + 10, 100); toEl.value = toPct; }
    document.getElementById('ev-calc-from-val').textContent = fromPct;
    document.getElementById('ev-calc-to-val').textContent   = toPct;

    const kwhCharge  = car.batteryKwh * (toPct - fromPct) / 100;
    const dcStation  = state.lastData?.stations.find(s => s.connectorType.includes('DC') && s.maxEffKw > 0);
    const effKw      = dcStation ? Math.min(car.maxDcKw || 50, dcStation.maxEffKw) : (car.maxDcKw || 50);
    const timeMin    = effKw > 0 ? Math.round(kwhCharge / effKw * 60) : 0;
    const timeStr    = timeMin < 60 ? `${timeMin} min` : `${Math.floor(timeMin / 60)} tim ${timeMin % 60} min`;

    const rawPrice   = dcStation?.chargepricePerKwh || dcStation?.usageCost || '';
    const pris       = tolkaLaddpris(rawPrice);
    const cost       = pris.krPerKwh ? Math.round(kwhCharge * pris.krPerKwh) : null;

    const realRange  = car.rangeKm ? Math.round(car.rangeKm * 0.85) : null;
    const rangeAdded = realRange   ? Math.round(realRange * (toPct - fromPct) / 100) : null;

    state.lastCalc = {
      carName: car.name, fromPct, toPct, kwhCharge: Math.round(kwhCharge * 10) / 10,
      effKw: Math.round(effKw), timeStr, cost: cost || null, rangeAdded: rangeAdded || null,
      stationName: dcStation?.name || null
    };

    const cols = rangeAdded ? 3 : 2;
    resultEl.innerHTML = `<div style="display:grid;grid-template-columns:repeat(${cols},1fr);gap:10px;text-align:center;">
      <div>
        <div style="font-size:1.35rem;font-weight:800;color:#93c5fd">${timeStr}</div>
        <div style="font-size:11px;color:rgba(147,197,253,0.5);margin-top:2px">Tid</div>
      </div>
      <div>
        <div style="font-size:1.35rem;font-weight:800;color:${cost ? '#86efac' : '#6b7280'}">${cost ? '~' + cost + ' kr' : 'Pris saknas'}</div>
        <div style="font-size:11px;color:rgba(147,197,253,0.5);margin-top:2px">Kostnad</div>
      </div>
      ${rangeAdded ? `<div>
        <div style="font-size:1.35rem;font-weight:800;color:#c4b5fd">~${rangeAdded} km</div>
        <div style="font-size:11px;color:rgba(147,197,253,0.5);margin-top:2px">Räckvidd</div>
      </div>` : ''}
    </div>`;
  };

  // ===== RUTTPLANERING =====
  function initRouteMode() {
    const controls = document.querySelector('.ev-controls');
    if (!controls) return;
    const wrap = document.createElement('div');
    wrap.style.marginBottom = '16px';
    wrap.innerHTML =
      '<button id="ev-route-toggle" style="width:100%;padding:12px 16px;background:rgba(59,130,246,0.06);' +
      'border:1.5px solid rgba(59,130,246,0.2);border-radius:12px;color:rgba(147,197,253,0.8);font-size:.85rem;' +
      'font-weight:700;cursor:pointer;text-align:left;transition:all .18s;">🗺️ Planera rutt — hitta laddstoppar längs vägen</button>' +
      '<div id="ev-route-panel" class="ev-route-panel" style="display:none;">' +
        '<div class="ev-route-title">Planera laddstoppar längs rutten</div>' +
        '<div style="font-size:.78rem;color:rgba(147,197,253,.6);margin-bottom:10px;display:flex;align-items:center;gap:6px;">' +
          '<span style="font-size:.9rem">📍</span><span id="ev-route-start-label">Din position (GPS)</span>' +
        '</div>' +
        '<div class="ev-route-row">' +
          '<input class="ev-route-input" id="ev-route-end" type="text" placeholder="Destination (t.ex. Göteborg)">' +
          '<button class="ev-route-btn" id="ev-route-go">Sök</button>' +
        '</div>' +
        '<div id="ev-route-result"></div>' +
      '</div>';
    controls.after(wrap);

    const toggleBtn = document.getElementById('ev-route-toggle');
    const panel     = document.getElementById('ev-route-panel');
    toggleBtn.addEventListener('click', () => {
      const open = panel.style.display !== 'none';
      panel.style.display = open ? 'none' : 'block';
      toggleBtn.style.borderColor  = open ? 'rgba(59,130,246,0.2)' : 'rgba(59,130,246,0.5)';
      toggleBtn.style.background   = open ? 'rgba(59,130,246,0.06)' : 'rgba(59,130,246,0.1)';
      toggleBtn.style.color        = open ? 'rgba(147,197,253,0.8)' : '#93c5fd';
      if (!open && state.city) document.getElementById('ev-route-start-label').textContent = state.city;
    });
    document.getElementById('ev-route-go').addEventListener('click', planRoute);
    document.getElementById('ev-route-end').addEventListener('keydown', e => { if (e.key === 'Enter') planRoute(); });
  }

  async function geocodeCity(city) {
    const r = await fetch(
      `https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(city)}&format=json&limit=1&countrycodes=se`,
      { headers: { 'User-Agent': 'EV-Laddning-App/1.0' } }
    );
    const d = await r.json();
    if (!d.length) throw new Error(`Kunde inte hitta "${city}"`);
    return { lat: parseFloat(d[0].lat), lon: parseFloat(d[0].lon), display: d[0].display_name.split(',')[0] };
  }

  async function getOsrmRoute(startLat, startLon, endLat, endLon) {
    const url = `https://router.project-osrm.org/route/v1/driving/${startLon},${startLat};${endLon},${endLat}?geometries=geojson&overview=full`;
    const r = await fetch(url, { headers: { 'User-Agent': 'EV-Laddning-App/1.0' } });
    if (!r.ok) return null;
    const d = await r.json();
    if (!d.routes?.length) return null;
    return {
      coordinates: d.routes[0].geometry.coordinates,   // [[lon,lat], ...]
      distanceKm:  Math.round(d.routes[0].distance / 100) / 10,
      durationMin: Math.round(d.routes[0].duration / 60)
    };
  }

  async function planRoute() {
    const endCity  = document.getElementById('ev-route-end')?.value?.trim();
    const resultEl = document.getElementById('ev-route-result');
    if (!resultEl) return;
    if (!endCity) {
      resultEl.innerHTML = '<p style="color:#ef4444;font-size:.82rem;margin:8px 0">Ange din destination.</p>';
      return;
    }
    if (!state.lat || !state.lon) {
      resultEl.innerHTML = '<p style="color:#ef4444;font-size:.82rem;margin:8px 0">GPS-position saknas — tillåt platsåtkomst och försök igen.</p>';
      return;
    }
    if (state.carIndex === null) {
      resultEl.innerHTML = '<p style="color:#ef4444;font-size:.82rem;margin:8px 0">Välj din elbil överst på sidan först.</p>';
      return;
    }
    const btn = document.getElementById('ev-route-go');
    btn.disabled = true; btn.textContent = 'Söker…';
    resultEl.innerHTML = '<div style="color:rgba(147,197,253,.55);font-size:.82rem;margin-top:8px">Hämtar rutt och laddstationer…</div>';
    try {
      const eg = await geocodeCity(endCity);
      const sg  = { lat: state.lat, lon: state.lon, display: state.city || 'Din position' };
      const stopsUrl = `${API}/api/route-stations?startLat=${sg.lat}&startLon=${sg.lon}&endLat=${eg.lat}&endLon=${eg.lon}&carIndex=${state.carIndex}`;

      // Fetch OSRM route + charging stops in parallel
      const [osrm, resp] = await Promise.all([
        getOsrmRoute(sg.lat, sg.lon, eg.lat, eg.lon).catch(() => null),
        fetch(stopsUrl)
      ]);
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      const data = await resp.json();
      renderRouteResult(data, sg, eg, osrm);
      collapseStationsForRoute();
      if (state.lastRoute) triggerRouteProactiveMessage();
      setTimeout(() => renderRouteMap(sg, data.stops, eg, osrm?.coordinates), 80);
    } catch (e) {
      resultEl.innerHTML = `<p style="color:#ef4444;font-size:.82rem;margin:8px 0">Fel: ${e.message}</p>`;
    } finally {
      btn.disabled = false; btn.textContent = 'Sök';
    }
  }

  function renderRouteMap(sg, stops, eg, osrmCoords) {
    if (!window.L) return;
    const mapEl = document.getElementById('ev-map');
    if (!mapEl) return;
    mapEl.style.display = 'block';

    // Clear previous route polyline and markers
    if (evRoutePolyline) { evRoutePolyline.remove(); evRoutePolyline = null; }
    evMapMarkers.forEach(m => m.remove()); evMapMarkers = [];

    const markerPoints = [
      [sg.lat, sg.lon],
      ...stops.map(s => [s.station.lat, s.station.lon]),
      [eg.lat, eg.lon]
    ];

    // Use real road geometry from OSRM if available, otherwise straight line
    const polylinePoints = osrmCoords
      ? osrmCoords.map(([lon, lat]) => [lat, lon])
      : markerPoints;

    if (!evMap) {
      evMap = L.map('ev-map', { zoomControl: true }).setView(markerPoints[0], 7);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>', maxZoom: 19
      }).addTo(evMap);
    }
    evMap.fitBounds(L.latLngBounds(polylinePoints).pad(0.07));

    // Road route line — solid if real OSRM geometry, dashed if straight-line fallback
    evRoutePolyline = L.polyline(polylinePoints, {
      color: '#3b82f6', weight: 4, opacity: 0.75,
      dashArray: osrmCoords ? null : '8 6'
    }).addTo(evMap);

    const makeIcon = (color, label) => L.divIcon({
      className: '',
      html: `<div style="width:30px;height:30px;border-radius:50%;background:${color};border:2.5px solid #fff;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:800;color:#111;box-shadow:0 2px 8px rgba(0,0,0,0.45)">${label}</div>`,
      iconSize: [30, 30], iconAnchor: [15, 15], popupAnchor: [0, -15]
    });

    evMapMarkers.push(L.marker([sg.lat, sg.lon], { icon: makeIcon('#22c55e', 'A') }).addTo(evMap).bindPopup(`<b>Start</b><br>${sg.display}`));
    stops.forEach(stop => {
      const s = stop.station;
      const kw = Math.round(s.maxEffKw);
      evMapMarkers.push(L.marker([s.lat, s.lon], { icon: makeIcon('#f59e0b', stop.order) })
        .addTo(evMap)
        .bindPopup(`<b>${s.name}</b><br>⚡ ${kw} kW · ${s.connectorType}<br>📍 ${stop.distanceFromStartKm} km från start`));
    });
    evMapMarkers.push(L.marker([eg.lat, eg.lon], { icon: makeIcon('#ef4444', 'B') }).addTo(evMap).bindPopup(`<b>Destination</b><br>${eg.display}`));

    setTimeout(() => evMap && evMap.invalidateSize(), 100);
  }

  function renderRouteResult(data, sg, eg, osrm) {
    const resultEl = document.getElementById('ev-route-result');
    const { totalDistanceKm, stopsNeeded, carName, stops } = data;
    const displayKm   = osrm?.distanceKm ?? Math.round(totalDistanceKm);
    const driveStr    = osrm?.durationMin
      ? ` · ~${Math.floor(osrm.durationMin / 60)} tim ${osrm.durationMin % 60} min`
      : '';

    if (stopsNeeded === 0) {
      state.lastRoute = { destination: eg.display, startDisplay: sg.display, distanceKm: displayKm, durationMin: osrm?.durationMin ?? null, carName, stops: [], stopsNeeded: 0 };
      resultEl.innerHTML = `<div style="color:#86efac;font-size:.85rem;padding:10px 14px;background:rgba(34,197,94,.07);border:1px solid rgba(34,197,94,.2);border-radius:10px;margin-top:10px;">
        ✅ Din ${carName} klarar ${displayKm} km utan laddning!${driveStr ? ' Körtid' + driveStr + '.' : ''}
      </div>`;
      return;
    }
    if (!stops.length) {
      resultEl.innerHTML = `<div style="color:rgba(245,158,11,.8);font-size:.82rem;padding:10px 14px;background:rgba(245,158,11,.06);border:1px solid rgba(245,158,11,.2);border-radius:10px;margin-top:10px;">
        Inga kompatibla laddstationer hittades längs rutten. Kontrollera manuellt via Open Charge Map.
      </div>`;
      return;
    }

    state.lastRoute = { destination: eg.display, startDisplay: sg.display, distanceKm: displayKm, durationMin: osrm?.durationMin ?? null, carName, stops, stopsNeeded };

    let html = `<div style="font-size:.72rem;color:rgba(147,197,253,.5);margin:10px 0 12px;">${displayKm} km${driveStr} · ${stopsNeeded} laddning${stopsNeeded !== 1 ? 'ar' : ''} rekommenderat · ${carName}</div>`;
    html += '<div class="ev-route-timeline">';

    const dot = (cls, label) =>
      `<div class="ev-route-dot ${cls}" style="flex-shrink:0">${label}</div>`;
    const line = () =>
      `<div style="display:flex;margin-left:13px;margin-bottom:3px;"><div class="ev-route-line" style="height:18px"></div></div>`;

    html += `<div class="ev-route-stop">${dot('start','A')}<div class="ev-route-info"><div class="ev-route-name">${sg.display}</div><div class="ev-route-meta">Start · 0 km</div></div></div>`;

    stops.forEach(stop => {
      const s = stop.station;
      const kw = Math.round(s.maxEffKw);
      const price = s.chargepricePerKwh || s.usageCost || '';
      html += line();
      html += `<div class="ev-route-stop">${dot('',stop.order)}<div class="ev-route-info">
        <div class="ev-route-name">${s.name}</div>
        <div class="ev-route-meta">⚡ ${kw} kW · ${s.connectorType}${price ? ' · ' + price : ''}${s.address ? ' · ' + s.address : ''}</div>
        <div class="ev-route-meta" style="margin-top:1px">📍 ${stop.distanceFromStartKm} km från start</div>
      </div></div>`;
    });

    html += line();
    html += `<div class="ev-route-stop">${dot('end','B')}<div class="ev-route-info"><div class="ev-route-name">${eg.display}</div><div class="ev-route-meta">Destination · ${Math.round(totalDistanceKm)} km</div></div></div>`;
    html += '</div>';
    resultEl.innerHTML = html;
  }

  function collapseStationsForRoute() {
    const el = document.getElementById("ev-output");
    if (!el || !el.children.length) return;
    savedStationsHtml = el.innerHTML;
    const headerEl = el.querySelector(".ev-results-header strong");
    const label = headerEl ? headerEl.textContent : "Laddstationer nära dig";
    el.innerHTML =
      '<div id="ev-stations-minimized" style="cursor:pointer;padding:10px 16px;' +
      'background:rgba(59,130,246,0.05);border:1.5px dashed rgba(59,130,246,0.18);' +
      'border-radius:12px;color:rgba(147,197,253,0.5);font-size:.8rem;' +
      'display:flex;align-items:center;justify-content:space-between;transition:opacity .2s;">' +
      '<span>📍 ' + label + '</span>' +
      '<span style="font-size:.75rem;opacity:.65">visa ▾</span></div>';
    el.querySelector("#ev-stations-minimized").addEventListener("click", function() {
      if (savedStationsHtml) setOutput(savedStationsHtml);
    });
  }

  async function triggerRouteProactiveMessage() {
    const context = buildStationContext();
    const r = state.lastRoute;
    let trigger;
    if (r && r.stopsNeeded === 0) {
      trigger = `Jag har precis planerat en rutt till ${r.destination} (${r.distanceKm} km) och min ${r.carName} klarar sträckan utan laddningsstopp. Ge mig ett par konkreta tips inför resan — bör jag ladda fullt hemma eller vid startpunkten innan avfärd, och finns det något annat jag bör tänka på?`;
    } else if (r && r.stopsNeeded > 0) {
      trigger = `Jag har precis planerat en rutt till ${r.destination} (${r.distanceKm} km) med ${r.stopsNeeded} laddningsstopp. Ge en kort sammanfattning av rutten och laddstoppet, och om det är värt att ladda extra vid startpunkten innan avfärd.`;
    } else {
      trigger = "Jag har precis sökt en rutt. Kan du ge mig tips om resan och laddning längs vägen?";
    }
    const messages = [...chatHistory.slice(-4), { role: "user", content: trigger }];

    const fabLabel = document.querySelector(".ev-chat-fab-label");
    if (fabLabel) fabLabel.textContent = "💬 Ny info om din rutt!";

    if (!chatIsOpen()) chatSetOpen(true);
    const quickEl = document.getElementById("ev-chat-quick");
    if (quickEl) quickEl.classList.add("ev-chat-quick-off");

    // Ruttipset ÄR en fråga till chatboten — samma Groq-anrop som en tippad fråga, bara
    // formulerad åt användaren. Fram till 2026-08-22 gick den här vägen förbi BÅDE spärren
    // och räknaren, så en rutt till Stockholm gav ett AI-svar utan att potten rördes: baren
    // stod stilla och det såg ut som att räkningen var trasig. Den var inte trasig — den
    // såg aldrig det här anropet.
    if (!evHasUnlimited()) {
      if (evDemoRemaining() <= 0) {
        var gate = chatAppendBot("Du har använt dina " + EV_DEMO_MAX + " gratis frågor den här timmen, så jag hoppar över ruttipset. Vänta en stund, eller prenumerera för obegränsat.", false);
        var lb = document.createElement("button");
        lb.className = "ev-chat-retry"; lb.textContent = "💳 Prenumerera";
        lb.onclick = evOpenSubscribePopup; gate.appendChild(lb);
        evUpdateChatDemoUI();
        return;
      }
      evConsumeDemo();
    }

    let resp;
    try {
      resp = await fetch(`${API}/api/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages, context })
      });
    } catch (_) {
      if (fabLabel) fabLabel.textContent = "✨ Fråga AI";
      return;
    }
    if (!resp.ok || !resp.body) {
      if (fabLabel) fabLabel.textContent = "✨ Fråga AI";
      return;
    }

    const msgsEl = document.getElementById("ev-chat-messages");
    const outer  = document.createElement("div");
    const bubble = document.createElement("div");
    bubble.className = "ev-chat-bubble bot";
    outer.appendChild(bubble);
    msgsEl.appendChild(outer);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    const reader  = resp.body.getReader();
    const decoder = new TextDecoder();
    let fullText  = "";
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        decoder.decode(value, { stream: true }).split("\n").forEach(line => {
          if (!line.startsWith("data: ")) return;
          const d = line.slice(6).trim();
          if (d === "[DONE]") return;
          try { fullText += JSON.parse(d); bubble.innerHTML = chatMarkdown(fullText); msgsEl.scrollTop = msgsEl.scrollHeight; } catch(e) {}
        });
      }
    } catch(_) {}

    if (fullText) { chatHistory.push({ role: "assistant", content: fullText }); saveChatHistory(); }
    if (fabLabel) setTimeout(() => { fabLabel.textContent = "✨ Fråga AI"; }, 10000);
  }

  initChat();
  initRouteMode();
})();
