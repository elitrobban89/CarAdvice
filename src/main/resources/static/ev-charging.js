/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
// EV Laddning — access guard & status bar
// Loaded as external script to bypass WordPress CSP restrictions
//
// WordPress setup:
//   1. Add this script as an external JS resource on the elbilsladdning page
//   2. Wrap ALL protected content in:  <div id="ev-content">...</div>
//      (Fallback: .entry-content used if #ev-content is missing)
//   3. Optionally place <div id="ev-sub-bar"></div> above the content to
//      control where the status bar appears (auto-injected if missing)

var CA_API_BASE = 'https://caradvice.onrender.com';

function evOpenSubscribe() {
  window.open(CA_API_BASE + '/subscribe.html', '_blank', 'width=480,height=650,resizable=yes');
}

function evGetContentEl() {
  return document.getElementById('ev-content') ||
         document.querySelector('.ev-app') ||
         document.querySelector('.entry-content') ||
         document.querySelector('article .post-content') ||
         null;
}

// ── Styles ───────────────────────────────────────────────────────────────────

function evInjectStyles() {
  if (document.getElementById('ev-styles')) return;
  var s = document.createElement('style');
  s.id = 'ev-styles';
  // Samma runda 💳-knapp som bilrådgivningen (.ca-rundknapp i car-advice-main.js) — den breda
  // raden med "Demo – 30 av 30 frågor … Prenumerera – 49 kr/mån" är borta på båda sidorna.
  // Texten bor i knappens title, lägena i färgen: violett = demo, grön = prenumerant,
  // bärnsten = kvoten tar slut, röd puls = slut.
  s.textContent = [
    '#ev-sub-bar{display:flex;justify-content:center;margin:0 0 24px}',
    '.ev-rundknapp{position:relative;width:44px;height:44px;border-radius:50%;display:inline-flex;',
      'align-items:center;justify-content:center;font-size:1.15rem;line-height:1;padding:0;',
      'cursor:pointer;background:rgba(15,12,41,.55);border:1px solid rgba(167,139,250,.38);',
      'box-shadow:0 6px 20px -10px rgba(139,92,246,.85);',
      'transition:transform .16s ease,box-shadow .16s ease,border-color .16s ease}',
    '.ev-rundknapp:hover{transform:translateY(-2px) scale(1.07);',
      'border-color:rgba(167,139,250,.75);box-shadow:0 11px 28px -10px rgba(167,139,250,1)}',
    '.ev-rundknapp:focus-visible{outline:2px solid rgba(167,139,250,.85);outline-offset:3px}',
    '.ev-rundknapp.ev-rund-prenumerant{border-color:rgba(52,211,153,.8);background:rgba(6,78,59,.6);',
      'box-shadow:0 6px 22px -9px rgba(16,185,129,1)}',
    '.ev-rundknapp.ev-rund-prenumerant:hover{border-color:rgba(52,211,153,1);',
      'box-shadow:0 11px 28px -10px rgba(52,211,153,1)}',
    '.ev-rundknapp.ev-rund-larm{border-color:rgba(251,191,36,.8);',
      'box-shadow:0 6px 22px -9px rgba(251,191,36,.95)}',
    '.ev-rundknapp.ev-rund-slut{border-color:rgba(248,113,113,.9);background:rgba(69,10,10,.55)}',
    '.ev-rundknapp.ev-rund-slut::after{content:"";position:absolute;inset:-5px;border-radius:50%;',
      'pointer-events:none;box-shadow:0 0 20px 5px rgba(248,113,113,.75),0 0 40px 10px rgba(239,68,68,.35);',
      'animation:ev-rund-puls 1.9s ease-in-out infinite}',
    '@keyframes ev-rund-puls{0%,100%{opacity:.45;transform:scale(.94)}50%{opacity:1;transform:scale(1.06)}}',
    '@media(prefers-reduced-motion:reduce){.ev-rundknapp.ev-rund-slut::after{animation:none;opacity:.9}}'
  ].join('');
  document.head.appendChild(s);
}

// ── Demokvoten ───────────────────────────────────────────────────────────────

/**
 * Frågor kvar denna timme, läst ur SAMMA localStorage-nyckel som chatten skriver till
 * (`ev_demo_times` i `ev-app.js`, se `evDemoTimes`/`evConsumeDemo` där).
 *
 * <p>Talen står med flit på två ställen och måste hållas lika: `EV_DEMO_MAX` i `ev-app.js`
 * bestämmer när chatten spärrar, det här bara vad baren visar. Skulle de glida isär lovar
 * baren en pott som spärren inte ger.
 *
 * <p>Baren visade tidigare en FAST text ("30 frågor i timmen gratis"), och stod därför stilla
 * medan chattens egen rad räknade ner — det såg ut som att frågorna inte drogs alls.
 *
 * Trasigt eller gammalt värde ger full pott: hellre lova för mycket i en text än att skrämma
 * med en nolla som spärren inte håller med om.
 */
var EV_FRAGOR_PER_TIMME = 30;
var EV_FRAGOR_FONSTER_MS = 3600000;

function evFragorKvar() {
  var grans = Date.now() - EV_FRAGOR_FONSTER_MS;
  var anvanda = 0;
  try {
    var raw = JSON.parse(localStorage.getItem('ev_demo_times') || '[]');
    if (Array.isArray(raw)) {
      anvanda = raw.filter(function(t) { return typeof t === 'number' && t >= grans; }).length;
    }
  } catch (e) { anvanda = 0; }
  return Math.max(0, EV_FRAGOR_PER_TIMME - anvanda);
}

/**
 * Räknar om barens text utan att röra inloggningsstatusen — anropas av `ev-app.js` varje gång
 * en fråga förbrukas. Statusen läses ur `ca_status`, samma källa som `evHasUnlimited()` där.
 */
function evRefreshQuotaBar() {
  var aktiv = localStorage.getItem('ca_status') === 'active' && !!localStorage.getItem('ca_token');
  evUpdateSubBar(aktiv, !aktiv && !!localStorage.getItem('ca_token'));
}

/**
 * WordPress-inloggade har fri tillgång — sajtägarens genväg för att testa utan att betala
 * (`document.body.logged-in`, samma villkor som `evHasUnlimited()` i ev-app.js och
 * `bcHasUnlimited()` i bensinkostnad.js).
 *
 * <p><b>Genvägen SYNS nu, och det är hela poängen.</b> Fram till 2026-08-22 kände bara chatten
 * till den: den slutade räkna, medan den här baren inte visste något om saken och fortsatte
 * visa "Demo – 30 av 30 frågor kvar". För den som är inloggad i sin egen WordPress ser det ut
 * som en trasig räknare — och det var precis vad som rapporterades. Att manuell provning kan
 * luras av just den här flaggan står redan som varning i README.
 */
function evArWordpressInloggad() {
  return document.body && document.body.classList.contains('logged-in');
}
window.evRefreshQuotaBar = evRefreshQuotaBar;

// ── Status bar ───────────────────────────────────────────────────────────────

function evInjectBarIfNeeded() {
  if (document.getElementById('ev-sub-bar')) return;
  var content = evGetContentEl();
  if (!content) return;
  var bar = document.createElement('div');
  bar.id = 'ev-sub-bar';
  content.parentNode.insertBefore(bar, content);
}

function evUpdateSubBar(isSubscriber, isLoggedIn) {
  evInjectBarIfNeeded();
  var bar = document.getElementById('ev-sub-bar');
  if (!bar) return;

  var knapp = document.getElementById('ev-rund-pren');
  if (!knapp) {
    bar.innerHTML = '';
    knapp = document.createElement('button');
    knapp.type = 'button';
    knapp.id = 'ev-rund-pren';
    knapp.className = 'ev-rundknapp';
    knapp.textContent = '💳';
    // Popup och inte en vanlig länk: subscribe.html skickar CA_LOGIN tillbaka till fönstret
    // som öppnade den, och det är det meddelandet som gör knappen grön utan omladdning.
    knapp.onclick = evOpenSubscribe;
    bar.appendChild(knapp);
  }

  var caEmail = localStorage.getItem('ca_email');
  var text;
  var kvar = null;
  // Prövas FÖRE prenumerationen: en WordPress-inloggad har fri tillgång oavsett konto, och
  // knappen måste säga det — annars lovar den en nedräkning som chatten inte gör.
  if (evArWordpressInloggad()) {
    text = '✓ Obegr\xe4nsat – inloggad i WordPress, kvoten g\xe4ller inte dig';
  } else if (isSubscriber) {
    text = '✓ Prenumerant – obegr\xe4nsad \xe5tkomst' + (caEmail ? ' (' + caEmail + ')' : '');
  } else {
    // Samma villkor som caUpdateSubBar: en sparad e-post räcker för "Inloggad".
    kvar = evFragorKvar();
    text = ((isLoggedIn || caEmail) ? 'Inloggad' : 'Demo') + ' – ' + kvar + ' av '
         + EV_FRAGOR_PER_TIMME + ' fr\xe5gor kvar denna timme \xb7 prenumerant: obegr\xe4nsat';
  }
  var gron = evArWordpressInloggad() || !!isSubscriber;
  knapp.title = text;
  knapp.setAttribute('aria-label', text);
  knapp.classList.toggle('ev-rund-prenumerant', gron);
  // Samma trösklar som bilrådgivningens rad: bärnsten när det börjar ta slut, röd när det är slut.
  knapp.classList.toggle('ev-rund-larm', kvar !== null && kvar > 0 && kvar <= 5);
  knapp.classList.toggle('ev-rund-slut', kvar === 0);
}

/** Cachat läge — samma som caInit använder, så en prenumerant är grön från första bildrutan. */
function evCachatLage() {
  var harToken = !!localStorage.getItem('ca_token');
  var aktiv = harToken && localStorage.getItem('ca_status') === 'active';
  evUpdateSubBar(aktiv, harToken && !aktiv);
}

function evLoggaUtLokalt() {
  localStorage.removeItem('ca_token');
  localStorage.removeItem('ca_email');
  localStorage.removeItem('ca_status');
  evUpdateSubBar(false, false);
}

// ── Content gating ───────────────────────────────────────────────────────────
//
// BETALVÄGGEN BORTTAGEN 2026-08-22. Fram till dess dolde evShowPaywall HELA appen för
// alla utan aktiv prenumeration — även den som bara ville titta — och rubriken sa "Logga
// in för att se innehållet". Det gick tvärtemot modellen: elbilsappen ska gå att använda
// utan konto, 30 frågor i timmen är gratis (se EV_DEMO_MAX i ev-app.js), och det är
// OBEGRÄNSAT som prenumerationen säljer.
//
// Erbjudandet finns kvar där det hör hemma: statusbaren ovanför innehållet och
// "Vad ingår?"-kortet i chatten. Ingen av dem döljer något.

function evRevealContent() {
  var content = evGetContentEl();
  if (content) { content.style.display = ''; content.style.visibility = ''; }
  // Kvar med flit: WP-sidan kan ha en gammal betalväggsruta kvar i DOM:en från en tidigare
  // sidladdning eller ett cachat skript, och den ska då tas bort — inte lämnas ovanför appen.
  var gammalPaywall = document.getElementById('ev-paywall');
  if (gammalPaywall && gammalPaywall.parentNode) gammalPaywall.parentNode.removeChild(gammalPaywall);
}

// ── WordPress-blockets egna texter ───────────────────────────────────────────

/**
 * Rättar promo-rutans text vid körning i stället för att vänta på att blocket klistras om.
 *
 * Rutan ("Demo — 3 gratis sökningar … logga in som prenumerant för obegränsad tillgång") är
 * STATISK HTML i WordPress, och sidan är en manuell kopia — källan ligger i
 * Bilresa/src/elbilsladdning-promo.html och en ändring där syns inte förrän någon klistrar in
 * blocket på nytt. Det här skriptet laddas däremot på samma sida och uppdateras vid deploy, så
 * det kan laga texten direkt. Samma grepp som caTrappanHtml använder på bilrådgivningssidan.
 *
 * Båda siffrorna var fel efter 2026-08-22: kalkylatorn ger 30 beräkningar i timmen utan konto,
 * och det är prenumerationen — inte inloggningen — som tar bort gränsen.
 *
 * Bara TEXTNODEN byts, aldrig innerHTML: brickan bär en inline-SVG som annars försvinner.
 */
function evFixPromoText() {
  var badge = document.querySelector('.elpromo-demo-badge');
  if (badge) {
    for (var i = badge.childNodes.length - 1; i >= 0; i--) {
      var n = badge.childNodes[i];
      // Skriv BARA över den gamla texten. Blocket är omklistrat 2026-08-22 och bär rätt text
      // redan; en villkorslös överskrivning hade tyst nollställt varje framtida redigering.
      if (n.nodeType === 3 && /gratis s\xf6kningar/i.test(n.nodeValue)) {
        n.nodeValue = ' Demo — 30 gratis ber\xe4kningar i timmen';
        break;
      }
    }
  }
  var body = document.querySelector('.elpromo-loggedout .elpromo-body');
  if (body && /3 s\xf6kningar|logga in som prenumerant/i.test(body.textContent)) {
    body.textContent = 'Prova kalkylatorn utan konto — 30 ber\xe4kningar i timmen \xe4r gratis. '
                     + 'Prenumerera f\xf6r obegr\xe4nsad tillg\xe5ng.';
  }
  var knapp = document.querySelector('.elpromo-loggedout .elpromo-btn-outline');
  if (knapp && knapp.textContent.trim() === 'Logga in') {
    knapp.textContent = 'Prenumerera – 49\xa0kr/m\xe5n';
  }
  var demoLank = document.querySelector('.elpromo-loggedout .elpromo-btn');
  if (demoLank && demoLank.textContent.trim() === 'Testa demo') {
    demoLank.textContent = 'R\xe4kna ut resekostnaden';
  }
}

// ── Auth check ───────────────────────────────────────────────────────────────

async function evCheckAuth() {
  evInjectStyles();
  // Cachat läge FÖRST, inte "Demo": en prenumerant såg annars Demo + köpknapp tills
  // /api/auth/me hunnit svara — och för alltid om svaret aldrig kom.
  evCachatLage();

  // Innehållet visas ALLTID och direkt. Ingen väntan på serversvar, ingen dold sida:
  // appen är gratis att använda, och det enda inloggningen avgör är vad statusbaren säger.
  // Tidigare doldes sidan medan /api/auth/me svarade — på en sovande Render-instans kunde
  // det ta över en minut, och besökaren såg en tom sida under tiden.
  evRevealContent();
  // Blockets egen kod visar/döljer .elpromo-loggedout först — texten rättas efteråt, och en
  // gång till på nästa tick ifall blocket hann rendera om efter sitt eget auth-svar.
  evFixPromoText();
  setTimeout(evFixPromoText, 1200);

  var token = localStorage.getItem('ca_token');
  if (!token) return;

  try {
    var r = await fetch(CA_API_BASE + '/api/auth/me', {
      headers: { 'Authorization': 'Bearer ' + token }
    });

    // Bara 401 betyder "tokenet gäller inte". En 502/503 under omdeploy loggade förut ut
    // varje besökare som råkade ladda sidan just då.
    if (r.status === 401) { evLoggaUtLokalt(); return; }
    if (!r.ok) { evCachatLage(); return; }

    var data = await r.json();
    localStorage.setItem('ca_status', data.subscriptionStatus);
    evUpdateSubBar(data.subscriptionStatus === 'active', data.subscriptionStatus !== 'active');
  } catch(e) {
    // Serverfel eller kallstart: cachat värde får gälla, precis som bcHasUnlimited gör.
    // En prenumerant ska inte degraderas till demoläge för att Render startar om.
    evCachatLage();
  }
}

// ── Listen for login/subscribe events from popup ──────────────────────────────

window.addEventListener('message', function(ev) {
  if (!ev.data || !ev.data.type) return;
  // Meddelandet bär ett token som skrivs rakt in i localStorage — bara vårt eget API och
  // sidan själv får skicka det (samma kontroll som bilrådgivningen gör).
  if (ev.origin !== CA_API_BASE && ev.origin !== window.location.origin) return;
  if (ev.data.type === 'CA_LOGIN' || ev.data.type === 'CA_SUBSCRIBED') {
    if (ev.data.token) localStorage.setItem('ca_token', ev.data.token);
    if (ev.data.email) localStorage.setItem('ca_email', ev.data.email);
    if (ev.data.status) localStorage.setItem('ca_status', ev.data.status);
    evUpdateSubBar(ev.data.status === 'active', ev.data.status !== 'active');
  }
  if (ev.data.type === 'CA_LOGOUT') {
    localStorage.removeItem('ca_token');
    localStorage.removeItem('ca_email');
    localStorage.removeItem('ca_status');
    evUpdateSubBar(false, false);
  }
});

// Inloggning i en annan flik (t.ex. bilrådgivningen) syns här direkt — samma lyssnare som där.
window.addEventListener('storage', function(ev) {
  if (ev.key === 'ca_status' || ev.key === 'ca_token') evCachatLage();
});

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', evCheckAuth);
} else {
  evCheckAuth();
}
