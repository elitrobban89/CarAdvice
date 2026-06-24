// Bränslekostnadsberäkning — access guard & status bar
// Loaded as external script to bypass WordPress CSP restrictions
//
// WordPress setup:
//   1. Add this script as an external JS resource on the branslekostnad page
//   2. Wrap ALL protected content in:  <div id="bc-content">...</div>
//   3. Optionally place <div id="bc-sub-bar"></div> above the content to
//      control where the status bar appears (auto-injected if missing)

var CA_API_BASE = 'https://caradvice.onrender.com';

function bcGuardOpenSubscribe() {
  window.open(CA_API_BASE + '/subscribe.html', '_blank', 'width=480,height=650,resizable=yes');
}

function bcGuardGetContentEl() {
  return document.getElementById('bc-content') ||
         document.querySelector('.bc-app') ||
         document.querySelector('.entry-content') ||
         document.querySelector('article .post-content') ||
         null;
}

// ── Styles ───────────────────────────────────────────────────────────────────

function bcGuardInjectStyles() {
  if (document.getElementById('bc-guard-styles')) return;
  var s = document.createElement('style');
  s.id = 'bc-guard-styles';
  s.textContent = [
    '#bc-sub-bar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;',
    'background:rgba(99,102,241,.08);border:1px solid rgba(99,102,241,.25);border-radius:14px;',
    'padding:11px 18px;margin-bottom:24px;',
    'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}',
    '#bc-sub-bar.bc-limited{background:rgba(245,158,11,.08);border-color:rgba(245,158,11,.3)}',
    '#bc-sub-left{font-size:.85rem;color:rgba(0,0,0,.6);line-height:1.4}',
    '#bc-sub-left strong{color:#1a1a2e}',
    '#bc-sub-right{display:flex;gap:8px;align-items:center;flex-shrink:0}',
    '#bc-prenumerera-btn{padding:8px 20px;background:linear-gradient(135deg,#635bff,#4f46e5);',
    'border:none;border-radius:10px;color:#fff;font-size:.85rem;font-weight:700;cursor:pointer;',
    'white-space:nowrap;transition:opacity .2s;display:inline-block;font-family:inherit}',
    '#bc-prenumerera-btn:hover{opacity:.88}',
    '#bc-login-link{font-size:.78rem;color:#635bff;cursor:pointer;white-space:nowrap;',
    'background:none;border:none;text-decoration:underline;padding:0;font-family:inherit}',
    '#bc-sub-email{font-size:.78rem;color:rgba(0,0,0,.4)}',
    '#bc-paywall{margin:16px 0 40px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}',
    '#bc-paywall-card{background:#1e1b4b;border:1px solid rgba(99,102,241,.35);border-radius:20px;',
    'padding:40px 36px;max-width:460px;margin:0 auto;text-align:center;',
    'box-shadow:0 20px 60px rgba(0,0,0,.25)}',
    '#bc-paywall-card .bc-pw-icon{font-size:2.6rem;margin-bottom:14px}',
    '#bc-paywall-card h2{color:#fff;font-size:1.3rem;margin:0 0 10px;font-weight:700}',
    '#bc-paywall-card p{color:rgba(255,255,255,.62);font-size:.9rem;line-height:1.6;margin:0 0 22px}',
    '.bc-features{list-style:none;margin:0 0 26px;padding:0;text-align:left}',
    '.bc-features li{padding:6px 0;color:rgba(255,255,255,.78);font-size:.88rem}',
    '.bc-features li::before{content:"✓ ";color:#22c55e;font-weight:700}',
    '#bc-paywall-btn{width:100%;padding:14px;background:linear-gradient(135deg,#635bff,#4f46e5);',
    'border:none;border-radius:10px;color:#fff;font-size:.95rem;font-weight:700;cursor:pointer;',
    'transition:opacity .2s;font-family:inherit}',
    '#bc-paywall-btn:hover{opacity:.88}'
  ].join('');
  document.head.appendChild(s);
}

// ── Status bar ───────────────────────────────────────────────────────────────

function bcGuardInjectBarIfNeeded() {
  if (document.getElementById('bc-sub-bar')) return;
  var content = bcGuardGetContentEl();
  if (!content) return;
  var bar = document.createElement('div');
  bar.id = 'bc-sub-bar';
  content.parentNode.insertBefore(bar, content);
}

function bcGuardUpdateSubBar(isSubscriber, isLoggedIn) {
  bcGuardInjectBarIfNeeded();
  var bar = document.getElementById('bc-sub-bar');
  if (!bar) return;

  var caEmail = localStorage.getItem('ca_email');

  if (!document.getElementById('bc-sub-title')) {
    bar.innerHTML =
      '<div id="bc-sub-left"><strong id="bc-sub-title">Demo</strong><span id="bc-sub-desc"></span></div>' +
      '<div id="bc-sub-right">' +
        '<span id="bc-sub-email"></span>' +
        '<button id="bc-login-link" style="display:none" onclick="bcGuardLoginLinkClick()"></button>' +
        '<button id="bc-prenumerera-btn" onclick="bcGuardOpenSubscribe()">Prenumerera / Logga in</button>' +
      '</div>';
  }

  var title     = document.getElementById('bc-sub-title');
  var desc      = document.getElementById('bc-sub-desc');
  var loginLink = document.getElementById('bc-login-link');
  var prenBtn   = document.getElementById('bc-prenumerera-btn');
  var emailEl   = document.getElementById('bc-sub-email');
  bar.classList.remove('bc-limited');

  if (isSubscriber) {
    title.textContent     = '✓ Prenumerant';
    desc.textContent      = ' – obegr\xe4nsad \xe5tkomst';
    prenBtn.style.display   = 'none';
    loginLink.style.display = 'inline';
    loginLink.textContent   = 'Konto';
    loginLink.dataset.action = 'account';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else if (isLoggedIn) {
    title.textContent     = 'Inloggad';
    desc.textContent      = ' – prenumeration kr\xe4vs';
    bar.classList.add('bc-limited');
    prenBtn.style.display   = 'inline-block';
    prenBtn.textContent     = 'Prenumerera – 49\xa0kr/m\xe5n';
    loginLink.style.display = 'inline';
    loginLink.textContent   = 'Logga ut';
    loginLink.dataset.action = 'logout';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else {
    title.textContent     = 'Demo';
    desc.textContent      = ' – logga in f\xf6r \xe5tkomst';
    prenBtn.style.display   = 'inline-block';
    prenBtn.textContent     = 'Prenumerera / Logga in';
    loginLink.style.display = 'none';
    if (emailEl) { emailEl.textContent = ''; emailEl.style.display = 'none'; }
  }
}

function bcGuardLoginLinkClick() {
  var link = document.getElementById('bc-login-link');
  if (link && link.dataset.action === 'logout') {
    var token = localStorage.getItem('ca_token');
    fetch(CA_API_BASE + '/api/auth/logout', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + (token || '') }
    });
    localStorage.removeItem('ca_token');
    localStorage.removeItem('ca_email');
    localStorage.removeItem('ca_status');
    bcGuardShowPaywall(false);
    bcGuardUpdateSubBar(false, false);
  } else {
    bcGuardOpenSubscribe();
  }
}

// ── Content gating ───────────────────────────────────────────────────────────

function bcGuardBuildPaywallCard(isLoggedIn) {
  var heading = isLoggedIn
    ? 'Prenumeration kr\xe4vs'
    : 'Logga in f\xf6r att anv\xe4nda kalkylatorn';
  var text = isLoggedIn
    ? 'Ditt konto saknar aktiv prenumeration. Prenumerera f\xf6r 49\xa0kr/m\xe5n och f\xe5 full \xe5tkomst till alla tre tj\xe4nsterna.'
    : 'Br\xe4nslekostnadsber\xe4kning, EV Laddningsassistenten och AI Bilr\xe5dgivning ing\xe5r i prenumerationen p\xe5 49\xa0kr/m\xe5n.';
  var btnText = isLoggedIn
    ? 'Prenumerera – 49\xa0kr/m\xe5n'
    : 'Logga in / Prenumerera';
  return (
    '<div class="bc-pw-icon">⛽</div>' +
    '<h2 id="bc-paywall-heading">' + heading + '</h2>' +
    '<p id="bc-paywall-text">' + text + '</p>' +
    '<ul class="bc-features">' +
      '<li>Br\xe4nslekostnadsber\xe4kning</li>' +
      '<li>EV Laddningsassistenten</li>' +
      '<li>AI Bilr\xe5dgivning (elitrobban.se/bilradgivning)</li>' +
      '<li>Obegr\xe4nsad AI-chatt</li>' +
      '<li>Avbryt n\xe4r som helst</li>' +
    '</ul>' +
    '<button id="bc-paywall-btn" onclick="bcGuardOpenSubscribe()">' + btnText + '</button>' +
    '<div style="margin-top:22px;padding-top:16px;border-top:1px solid rgba(255,255,255,.12)">' +
      '<p style="margin:0;font-size:.78rem;color:rgba(255,255,255,.45);line-height:1.7">' +
        'Mer intresserad av elbilar? ' +
        '<a href="https://elitrobban.se/elbilsladdning/" target="_blank" ' +
           'style="color:#a5b4fc;font-weight:600;text-decoration:none">AI EV Laddassistent ↗</a>' +
        ' — ing\xe5r ocks\xe5 i prenumerationen.' +
      '</p>' +
    '</div>'
  );
}

function bcGuardShowPaywall(isLoggedIn) {
  var content = bcGuardGetContentEl();
  if (content) content.style.display = 'none';

  var existing = document.getElementById('bc-paywall-card');
  if (existing) {
    existing.innerHTML = bcGuardBuildPaywallCard(isLoggedIn);
    document.getElementById('bc-paywall').style.display = 'block';
    return;
  }

  var paywall = document.createElement('div');
  paywall.id = 'bc-paywall';
  var card = document.createElement('div');
  card.id = 'bc-paywall-card';
  card.innerHTML = bcGuardBuildPaywallCard(isLoggedIn);
  paywall.appendChild(card);

  if (content) {
    content.parentNode.insertBefore(paywall, content.nextSibling);
  } else {
    document.body.appendChild(paywall);
  }
}

function bcGuardRevealContent() {
  var paywall = document.getElementById('bc-paywall');
  var content = bcGuardGetContentEl();
  if (paywall) paywall.style.display = 'none';
  if (content) { content.style.display = ''; content.style.visibility = ''; }
}

// ── Auth check ───────────────────────────────────────────────────────────────

async function bcGuardCheckAuth() {
  bcGuardInjectStyles();
  bcGuardUpdateSubBar(false, false);

  var token = localStorage.getItem('ca_token');
  if (!token) {
    bcGuardShowPaywall(false);
    return;
  }

  var content = bcGuardGetContentEl();
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
      bcGuardShowPaywall(false);
      bcGuardUpdateSubBar(false, false);
      return;
    }

    var data = await r.json();
    localStorage.setItem('ca_status', data.subscriptionStatus);

    if (data.subscriptionStatus === 'active') {
      bcGuardRevealContent();
      bcGuardUpdateSubBar(true, false);
    } else {
      if (content) content.style.visibility = '';
      bcGuardShowPaywall(true);
      bcGuardUpdateSubBar(false, true);
    }
  } catch(e) {
    if (content) content.style.visibility = '';
    var cached = localStorage.getItem('ca_status');
    if (cached === 'active') {
      bcGuardRevealContent();
      bcGuardUpdateSubBar(true, false);
    } else {
      bcGuardShowPaywall(!!token);
      bcGuardUpdateSubBar(false, !!token);
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
      bcGuardRevealContent();
      bcGuardUpdateSubBar(true, false);
    } else {
      bcGuardShowPaywall(true);
      bcGuardUpdateSubBar(false, true);
    }
  }
  if (ev.data.type === 'CA_LOGOUT') {
    localStorage.removeItem('ca_token');
    localStorage.removeItem('ca_email');
    localStorage.removeItem('ca_status');
    bcGuardShowPaywall(false);
    bcGuardUpdateSubBar(false, false);
  }
});

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bcGuardCheckAuth);
} else {
  bcGuardCheckAuth();
}
