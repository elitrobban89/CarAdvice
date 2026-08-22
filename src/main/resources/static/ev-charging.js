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
  s.textContent = [
    '#ev-sub-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;',
    'background:rgba(99,102,241,.08);border:1px solid rgba(99,102,241,.25);border-radius:14px;',
    'padding:11px 18px;margin-bottom:24px;',
    'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}',
    '#ev-sub-bar.ev-limited{background:rgba(245,158,11,.08);border-color:rgba(245,158,11,.3)}',
    '#ev-sub-left{font-size:.85rem;color:rgba(0,0,0,.6);line-height:1.4}',
    '#ev-sub-left strong{color:#1a1a2e}',
    '#ev-sub-right{display:flex;gap:8px;align-items:center;flex-shrink:0}',
    '#ev-prenumerera-btn{padding:8px 20px;background:linear-gradient(135deg,#635bff,#4f46e5);',
    'border:none;border-radius:10px;color:#fff;font-size:.85rem;font-weight:700;cursor:pointer;',
    'white-space:nowrap;transition:opacity .2s;display:inline-block;font-family:inherit}',
    '#ev-prenumerera-btn:hover{opacity:.88}',
    '#ev-login-link{font-size:.78rem;color:#635bff;cursor:pointer;white-space:nowrap;',
    'background:none;border:none;text-decoration:underline;padding:0;font-family:inherit}',
    '#ev-sub-email{font-size:.78rem;color:rgba(0,0,0,.4)}'
  ].join('');
  document.head.appendChild(s);
}

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

  var caEmail = localStorage.getItem('ca_email');

  if (!document.getElementById('ev-sub-title')) {
    bar.innerHTML =
      '<div id="ev-sub-left"><strong id="ev-sub-title">Demo</strong><span id="ev-sub-desc"></span></div>' +
      '<div id="ev-sub-right">' +
        '<span id="ev-sub-email"></span>' +
        '<button id="ev-login-link" style="display:none" onclick="evLoginLinkClick()"></button>' +
        '<button id="ev-prenumerera-btn" onclick="evOpenSubscribe()">Prenumerera – 49\xa0kr/m\xe5n</button>' +
      '</div>';
  }

  var title     = document.getElementById('ev-sub-title');
  var desc      = document.getElementById('ev-sub-desc');
  var loginLink = document.getElementById('ev-login-link');
  var prenBtn   = document.getElementById('ev-prenumerera-btn');
  var emailEl   = document.getElementById('ev-sub-email');
  bar.classList.remove('ev-limited');

  if (isSubscriber) {
    title.textContent    = '✓ Prenumerant';
    desc.textContent     = ' – obegr\xe4nsad \xe5tkomst';
    prenBtn.style.display  = 'none';
    loginLink.style.display = 'inline';
    loginLink.textContent   = 'Konto';
    loginLink.dataset.action = 'account';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else if (isLoggedIn) {
    title.textContent    = 'Inloggad';
    // Sa " – prenumeration krävs" fram till 2026-08-22, vilket var sant när betalväggen
    // dolde allt. Nu är appen gratis att använda; prenumerationen tar bort gränsen.
    desc.textContent     = ' – 30 fr\xe5gor i timmen, obegr\xe4nsat som prenumerant';
    prenBtn.style.display  = 'inline-block';
    prenBtn.textContent    = 'Prenumerera – 49\xa0kr/m\xe5n';
    loginLink.style.display = 'inline';
    loginLink.textContent   = 'Logga ut';
    loginLink.dataset.action = 'logout';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else {
    title.textContent    = 'Demo';
    // "logga in för åtkomst" var beskedet när betalväggen dolde sidan. Inget konto behövs.
    desc.textContent     = ' – 30 fr\xe5gor i timmen gratis, inget konto beh\xf6vs';
    prenBtn.style.display  = 'inline-block';
    prenBtn.textContent    = 'Prenumerera – 49\xa0kr/m\xe5n';
    loginLink.style.display = 'none';
    if (emailEl) { emailEl.textContent = ''; emailEl.style.display = 'none'; }
  }
}

function evLoginLinkClick() {
  var link = document.getElementById('ev-login-link');
  if (link && link.dataset.action === 'logout') {
    var token = localStorage.getItem('ca_token');
    fetch(CA_API_BASE + '/api/auth/logout', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + (token || '') }
    });
    localStorage.removeItem('ca_token');
    localStorage.removeItem('ca_email');
    localStorage.removeItem('ca_status');
    // Utloggning döljer inte längre innehållet — appen är gratis att använda utan konto.
    evUpdateSubBar(false, false);
  } else {
    evOpenSubscribe();
  }
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
  evUpdateSubBar(false, false);

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

    if (!r.ok) {
      localStorage.removeItem('ca_token');
      localStorage.removeItem('ca_email');
      localStorage.removeItem('ca_status');
      evUpdateSubBar(false, false);
      return;
    }

    var data = await r.json();
    localStorage.setItem('ca_status', data.subscriptionStatus);
    evUpdateSubBar(data.subscriptionStatus === 'active', data.subscriptionStatus !== 'active');
  } catch(e) {
    // Serverfel eller kallstart: cachat värde får gälla, precis som bcHasUnlimited gör.
    // En prenumerant ska inte degraderas till demoläge för att Render startar om.
    var cached = localStorage.getItem('ca_status');
    evUpdateSubBar(cached === 'active', cached !== 'active');
  }
}

// ── Listen for login/subscribe events from popup ──────────────────────────────

window.addEventListener('message', function(ev) {
  if (!ev.data || !ev.data.type) return;
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

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', evCheckAuth);
} else {
  evCheckAuth();
}
