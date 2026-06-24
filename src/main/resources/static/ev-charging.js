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
    '#ev-sub-email{font-size:.78rem;color:rgba(0,0,0,.4)}',
    '#ev-paywall{margin:16px 0 40px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}',
    '#ev-paywall-card{background:#1e1b4b;border:1px solid rgba(99,102,241,.35);border-radius:20px;',
    'padding:40px 36px;max-width:460px;margin:0 auto;text-align:center;',
    'box-shadow:0 20px 60px rgba(0,0,0,.25)}',
    '#ev-paywall-card .ev-pw-icon{font-size:2.6rem;margin-bottom:14px}',
    '#ev-paywall-card h2{color:#fff;font-size:1.3rem;margin:0 0 10px;font-weight:700}',
    '#ev-paywall-card p{color:rgba(255,255,255,.62);font-size:.9rem;line-height:1.6;margin:0 0 22px}',
    '.ev-features{list-style:none;margin:0 0 26px;padding:0;text-align:left}',
    '.ev-features li{padding:6px 0;color:rgba(255,255,255,.78);font-size:.88rem}',
    '.ev-features li::before{content:"✓ ";color:#22c55e;font-weight:700}',
    '#ev-paywall-btn{width:100%;padding:14px;background:linear-gradient(135deg,#635bff,#4f46e5);',
    'border:none;border-radius:10px;color:#fff;font-size:.95rem;font-weight:700;cursor:pointer;',
    'transition:opacity .2s;font-family:inherit}',
    '#ev-paywall-btn:hover{opacity:.88}'
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
        '<button id="ev-prenumerera-btn" onclick="evOpenSubscribe()">Prenumerera / Logga in</button>' +
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
    desc.textContent     = ' – prenumeration kr\xe4vs';
    bar.classList.add('ev-limited');
    prenBtn.style.display  = 'inline-block';
    prenBtn.textContent    = 'Prenumerera – 29\xa0kr/m\xe5n';
    loginLink.style.display = 'inline';
    loginLink.textContent   = 'Logga ut';
    loginLink.dataset.action = 'logout';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else {
    title.textContent    = 'Demo';
    desc.textContent     = ' – logga in f\xf6r \xe5tkomst';
    prenBtn.style.display  = 'inline-block';
    prenBtn.textContent    = 'Prenumerera / Logga in';
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
    evShowPaywall(false);
    evUpdateSubBar(false, false);
  } else {
    evOpenSubscribe();
  }
}

// ── Content gating ───────────────────────────────────────────────────────────

function evBuildPaywallCard(isLoggedIn) {
  var heading = isLoggedIn
    ? 'Prenumeration kr\xe4vs'
    : 'Logga in f\xf6r att se inneh\xe5llet';
  var text = isLoggedIn
    ? 'Ditt konto saknar aktiv prenumeration. Prenumerera f\xf6r 29\xa0kr/m\xe5n och f\xe5 full \xe5tkomst till b\xe5da tj\xe4nsterna.'
    : 'AI EV Laddningsassistenten och elbils k\xf6pguiden ing\xe5r i prenumerationen p\xe5 29\xa0kr/m\xe5n.';
  var btnText = isLoggedIn
    ? 'Prenumerera – 29\xa0kr/m\xe5n'
    : 'Logga in / Prenumerera';
  return (
    '<div class="ev-pw-icon">⚡</div>' +
    '<h2 id="ev-paywall-heading">' + heading + '</h2>' +
    '<p id="ev-paywall-text">' + text + '</p>' +
    '<ul class="ev-features">' +
      '<li>AI EV Laddningsassistenten</li>' +
      '<li>Elbils k\xf6pguiden</li>' +
      '<li>AI Bilr\xe5dgivning (elitrobban.se/bilradgivning)</li>' +
      '<li>Obegr\xe4nsad AI-chatt</li>' +
      '<li>Avbryt n\xe4r som helst</li>' +
    '</ul>' +
    '<button id="ev-paywall-btn" onclick="evOpenSubscribe()">' + btnText + '</button>'
  );
}

function evShowPaywall(isLoggedIn) {
  var content = evGetContentEl();
  if (content) content.style.display = 'none';

  var existing = document.getElementById('ev-paywall-card');
  if (existing) {
    existing.innerHTML = evBuildPaywallCard(isLoggedIn);
    document.getElementById('ev-paywall').style.display = 'block';
    return;
  }

  var paywall = document.createElement('div');
  paywall.id = 'ev-paywall';
  var card = document.createElement('div');
  card.id = 'ev-paywall-card';
  card.innerHTML = evBuildPaywallCard(isLoggedIn);
  paywall.appendChild(card);

  if (content) {
    content.parentNode.insertBefore(paywall, content.nextSibling);
  } else {
    document.body.appendChild(paywall);
  }
}

function evRevealContent() {
  var paywall = document.getElementById('ev-paywall');
  var content = evGetContentEl();
  if (paywall) paywall.style.display = 'none';
  if (content) { content.style.display = ''; content.style.visibility = ''; }
}

// ── Auth check ───────────────────────────────────────────────────────────────

async function evCheckAuth() {
  evInjectStyles();
  evUpdateSubBar(false, false);

  var token = localStorage.getItem('ca_token');
  if (!token) {
    evShowPaywall(false);
    return;
  }

  // Hide content while verifying to avoid flash
  var content = evGetContentEl();
  if (content) content.style.visibility = 'hidden';

  try {
    var r = await fetch(CA_API_BASE + '/api/auth/me', {
      headers: { 'Authorization': 'Bearer ' + token }
    });

    if (!r.ok) {
      localStorage.removeItem('ca_token');
      localStorage.removeItem('ca_email');
      localStorage.removeItem('ca_status');
      if (content) content.style.visibility = '';
      evShowPaywall(false);
      evUpdateSubBar(false, false);
      return;
    }

    var data = await r.json();
    localStorage.setItem('ca_status', data.subscriptionStatus);

    if (data.subscriptionStatus === 'active') {
      evRevealContent();
      evUpdateSubBar(true, false);
    } else {
      if (content) content.style.visibility = '';
      evShowPaywall(true);
      evUpdateSubBar(false, true);
    }
  } catch(e) {
    if (content) content.style.visibility = '';
    var cached = localStorage.getItem('ca_status');
    if (cached === 'active') {
      evRevealContent();
      evUpdateSubBar(true, false);
    } else {
      evShowPaywall(!!token);
      evUpdateSubBar(false, !!token);
    }
  }
}

// ── Listen for login/subscribe events from popup ──────────────────────────────

window.addEventListener('message', function(ev) {
  if (!ev.data || !ev.data.type) return;
  if (ev.data.type === 'CA_LOGIN' || ev.data.type === 'CA_SUBSCRIBED') {
    if (ev.data.token) localStorage.setItem('ca_token', ev.data.token);
    if (ev.data.email) localStorage.setItem('ca_email', ev.data.email);
    if (ev.data.status) localStorage.setItem('ca_status', ev.data.status);
    if (ev.data.status === 'active') {
      evRevealContent();
      evUpdateSubBar(true, false);
    } else {
      evShowPaywall(true);
      evUpdateSubBar(false, true);
    }
  }
  if (ev.data.type === 'CA_LOGOUT') {
    localStorage.removeItem('ca_token');
    localStorage.removeItem('ca_email');
    localStorage.removeItem('ca_status');
    evShowPaywall(false);
    evUpdateSubBar(false, false);
  }
});

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', evCheckAuth);
} else {
  evCheckAuth();
}
