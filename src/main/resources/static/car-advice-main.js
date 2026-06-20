// BilRådgivning — main form logic
// Loaded as external script to bypass WordPress inline-script restrictions

window.onerror = function(msg, src, line) {
  var d = document.getElementById('ca-js-error');
  if (d) { d.textContent = 'JS-fel rad ' + line + ': ' + msg; d.style.display = 'block'; }
};

window._ca = function(action, arg) {
  if (window._caFns && window._caFns[action]) window._caFns[action](arg);
};

var caHasSearched = false;
var caInitialValues = {};
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
var CA_CAT_NAMES = { ekonomibil: 'Ekonomibil', smaabil: 'Sm\xe5bil', familjebil: 'Familjebil', elbil: 'Elbil', suv: 'SUV', laddhybrid: 'Laddhybrid' };
var CA_FUEL_NAMES = { bensin: 'Bensin', diesel: 'Diesel', hybrid: 'Hybrid' };
var CA_MAX_BUDGET = { ekonomibil: 200000, smaabil: 150000, familjebil: 999999, elbil: 999999, suv: 999999, laddhybrid: 999999 };

function caStartLoadingText() {
  var i = 0;
  document.getElementById('ca-loader-text').textContent = caLoadingMessages[0];
  caLoadingInterval = setInterval(function() {
    i = (i + 1) % caLoadingMessages.length;
    document.getElementById('ca-loader-text').textContent = caLoadingMessages[i];
  }, 2200);
}
function caStopLoadingText() {
  clearInterval(caLoadingInterval);
  caLoadingInterval = null;
  document.getElementById('ca-loader-text').textContent = caLoadingMessages[0];
}

function caUpdateSliderFill() {
  var slider = document.getElementById('ca-budget-slider');
  if (!slider) return;
  var pct = (parseInt(slider.value) - 50000) / (1000000 - 50000) * 100;
  document.getElementById('ca-slider-fill').style.width = pct + '%';
  document.getElementById('ca-budget-display').textContent = parseInt(slider.value).toLocaleString('sv-SE') + ' kr';
}

function caUpdateFuelVisibility() {
  var cat = document.getElementById('ca-category').value;
  var fuelField = document.getElementById('ca-fuel-field');
  var hide = (cat === 'elbil' || cat === 'laddhybrid');
  fuelField.style.display = hide ? 'none' : '';
  if (hide) document.getElementById('ca-fuel').value = 'spelar ingen roll';
}

function caSavePrefs() {
  try {
    localStorage.setItem('ca-prefs', JSON.stringify({
      category:   document.getElementById('ca-category').value,
      budget:     document.getElementById('ca-budget-slider').value,
      charger:    document.getElementById('ca-charger').value,
      km:         document.getElementById('ca-km').value,
      usage:      document.getElementById('ca-usage').value,
      passengers: document.getElementById('ca-passengers').value,
      newcar:     document.getElementById('ca-newcar').value,
      fuelType:   document.getElementById('ca-fuel').value
    }));
  } catch(e) {}
}
function caLoadPrefs() {
  try {
    var raw = localStorage.getItem('ca-prefs');
    if (!raw) return;
    var d = JSON.parse(raw);
    if (d.category)   document.getElementById('ca-category').value   = d.category;
    if (d.budget)   { document.getElementById('ca-budget-slider').value = d.budget; caUpdateSliderFill(); }
    if (d.charger)    document.getElementById('ca-charger').value     = d.charger;
    if (d.km)         document.getElementById('ca-km').value          = d.km;
    if (d.usage)      document.getElementById('ca-usage').value       = d.usage;
    if (d.passengers) document.getElementById('ca-passengers').value  = d.passengers;
    if (d.newcar)     document.getElementById('ca-newcar').value      = d.newcar;
    if (d.fuelType)   document.getElementById('ca-fuel').value        = d.fuelType;
    caUpdateFuelVisibility();
    caCheckMismatch();
  } catch(e) {}
}

function caReadUrlParams() {
  try {
    var p = new URLSearchParams(window.location.search);
    if (p.get('category'))   document.getElementById('ca-category').value   = p.get('category');
    if (p.get('budget'))   { document.getElementById('ca-budget-slider').value = p.get('budget'); caUpdateSliderFill(); }
    if (p.get('charger'))    document.getElementById('ca-charger').value     = p.get('charger');
    if (p.get('km'))         document.getElementById('ca-km').value          = p.get('km');
    if (p.get('usage'))      document.getElementById('ca-usage').value       = p.get('usage');
    if (p.get('passengers')) document.getElementById('ca-passengers').value  = p.get('passengers');
    if (p.get('newcar'))     document.getElementById('ca-newcar').value      = p.get('newcar');
    if (p.get('fuelType'))   document.getElementById('ca-fuel').value        = p.get('fuelType');
    if (p.has('category') || p.has('budget')) { caUpdateFuelVisibility(); caCheckMismatch(); }
  } catch(e) {}
}

function caSnapshotValues() {
  caInitialValues = {
    category:   document.getElementById('ca-category').value,
    budget:     document.getElementById('ca-budget-slider').value,
    charger:    document.getElementById('ca-charger').value,
    km:         document.getElementById('ca-km').value,
    usage:      document.getElementById('ca-usage').value,
    passengers: document.getElementById('ca-passengers').value,
    newcar:     document.getElementById('ca-newcar').value,
    fuelType:   document.getElementById('ca-fuel').value
  };
}

function caCheckChanges() {
  if (!caHasSearched) return;
  var ids  = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel'];
  var keys = ['category','budget','charger','km','usage','passengers','newcar','fuelType'];
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
  var btn = document.getElementById('ca-btn');
  if (!btn) return;
  btn.classList.toggle('has-changes', anyChanged);
  btn.textContent = anyChanged ? 'Uppdatera resultat →' : 'S\xf6k igen →';
}

function caBindChangeListeners() {
  var ids = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel'];
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
  if (cat) cat.addEventListener('change', caUpdateFuelVisibility);
  if (bud) bud.addEventListener('input', caUpdateSliderFill);
}

function caCheckMismatch() {
  var cat = document.getElementById('ca-category').value;
  var budget = parseInt(document.getElementById('ca-budget-slider').value) || 0;
  var max = CA_MAX_BUDGET[cat] || 999999;
  var warn = document.getElementById('ca-warning');
  if (!warn) return;
  if (budget > max) {
    warn.style.display = 'block';
    warn.textContent = '⚠️ ' + (CA_CAT_NAMES[cat] || cat) + ' brukar kosta max ' +
      max.toLocaleString('sv-SE') + ' kr. \xd6verv\xe4g att v\xe4lja Familjebil, SUV eller Laddhybrid f\xf6r denna budget.';
  } else {
    warn.style.display = 'none';
  }
}

function caSaveHistory(recommendations) {
  try {
    var entry = {
      category:        document.getElementById('ca-category').value,
      budget:          document.getElementById('ca-budget-slider').value,
      charger:         document.getElementById('ca-charger').value,
      km:              document.getElementById('ca-km').value,
      usage:           document.getElementById('ca-usage').value,
      passengers:      document.getElementById('ca-passengers').value,
      newcar:          document.getElementById('ca-newcar').value,
      fuelType:        document.getElementById('ca-fuel').value,
      timestamp:       Date.now(),
      recommendations: recommendations || []
    };
    var history = caGetHistory();
    var key = entry.category + '|' + entry.budget + '|' + entry.fuelType + '|' + entry.km + '|' + entry.usage + '|' + entry.newcar;
    history = history.filter(function(h) {
      return (h.category + '|' + h.budget + '|' + h.fuelType + '|' + h.km + '|' + h.usage + '|' + h.newcar) !== key;
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
  var budget = parseInt(entry.budget).toLocaleString('sv-SE') + ' kr';
  var fuel = (entry.fuelType && entry.fuelType !== 'spelar ingen roll') ? ' \xb7 ' + (CA_FUEL_NAMES[entry.fuelType] || entry.fuelType) : '';
  return cat + ' \xb7 ' + budget + fuel;
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
  if (entry.category)   document.getElementById('ca-category').value   = entry.category;
  if (entry.budget)   { document.getElementById('ca-budget-slider').value = entry.budget; caUpdateSliderFill(); }
  if (entry.charger)    document.getElementById('ca-charger').value     = entry.charger;
  if (entry.km)         document.getElementById('ca-km').value          = entry.km;
  if (entry.usage)      document.getElementById('ca-usage').value       = entry.usage;
  if (entry.passengers) document.getElementById('ca-passengers').value  = entry.passengers;
  if (entry.newcar)     document.getElementById('ca-newcar').value      = entry.newcar;
  if (entry.fuelType)   document.getElementById('ca-fuel').value        = entry.fuelType;
  caUpdateFuelVisibility();
  caCheckMismatch();

  if (entry.recommendations && entry.recommendations.length > 0) {
    document.getElementById('ca-divider').style.display = 'block';
    document.getElementById('ca-results').style.display = 'block';
    document.getElementById('ca-cache-badge').style.display = 'none';
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
  var q = title.replace(/\s*\(\d{4}\)\s*$/, '').trim();
  return 'https://www.blocket.se/annonser/hela_sverige/fordon/bilar?q=' + encodeURIComponent(q);
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
  document.getElementById('ca-category').value   = 'ekonomibil';
  document.getElementById('ca-budget-slider').value = 200000;
  document.getElementById('ca-charger').value    = 'false';
  document.getElementById('ca-km').value         = 1500;
  document.getElementById('ca-usage').value      = 'pendling';
  document.getElementById('ca-passengers').value = 4;
  document.getElementById('ca-newcar').value     = 'false';
  document.getElementById('ca-fuel').value       = 'spelar ingen roll';
  caUpdateSliderFill();
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

function caEvChips(ev) {
  if (!ev) return '';
  var isPhev = ev.carType === 'PHEV';
  var badgeLabel = isPhev ? '&#x1F50C; Laddhybrid' : '&#x26A1; Elbil';
  var wltpTxt = ev.wltpKm > 0 ? (isPhev ? 'Elr\xe4ckvidd '+ev.wltpKm+' km' : 'WLTP '+ev.wltpKm+' km') : '';
  var head = '<div class="ca-ev-head"><span class="ca-ev-badge">'+badgeLabel+'</span>'+(wltpTxt?'<span class="ca-ev-wltp">'+wltpTxt+'</span>':'')+'</div>';
  var chips = '';
  if (ev.summerKm > 0) chips += '<span class="ca-ev-chip ca-ev-range">&#x2600;&#xFE0F; ~'+ev.summerKm+' km sommar</span>';
  if (ev.winterKm > 0) chips += '<span class="ca-ev-chip ca-ev-winter">&#x2744;&#xFE0F; ~'+ev.winterKm+' km vinter</span>';
  if (ev.daysLabel) chips += '<div style="width:100%;height:0;margin:0"></div><span class="ca-ev-chip ca-ev-charge">&#x1F50B; '+caEsc(ev.daysLabel)+'</span>';
  if (ev.maxDcKw > 0) chips += '<span class="ca-ev-chip ca-ev-dc">&#x26A1; DC '+ev.maxDcKw+' kW</span>';
  if (ev.maxAcKw > 0) chips += '<span class="ca-ev-chip ca-ev-ac">&#x1F50C; AC '+ev.maxAcKw+' kW</span>';
  if (ev.batteryKwh > 0) chips += '<span class="ca-ev-chip ca-ev-bat">'+ev.batteryKwh+' kWh</span>';
  if (ev.priceKr > 0) chips += '<span class="ca-ev-chip ca-ev-price">fr\xe5n '+Math.round(ev.priceKr/1000)+' tkr</span>';
  if (ev.valueLabel) chips += '<span class="ca-ev-chip ca-ev-value">'+caEsc(ev.valueLabel)+'</span>';
  return '<div class="ca-ev">'+head+'<div class="ca-ev-chips">'+chips+'</div></div>';
}

function caFuelChips(fuel) {
  if (!fuel) return '';
  var chips = '';
  if (fuel.consumptionLiterPerMil > 0) chips += '<span class="ca-ev-chip ca-ev-range">&#x26FD; ' + fuel.consumptionLiterPerMil.toFixed(1) + ' l/mil</span>';
  if (fuel.gearbox) chips += '<span class="ca-ev-chip ca-ev-charge">&#x2699;&#xFE0F; ' + caEsc(fuel.gearbox) + '</span>';
  if (fuel.horsepower > 0) chips += '<span class="ca-ev-chip ca-ev-dc">&#x1F4AA; ' + fuel.horsepower + ' hk</span>';
  if (fuel.engineVolumeLiters > 0) chips += '<span class="ca-ev-chip ca-ev-bat">&#x1F527; ' + fuel.engineVolumeLiters.toFixed(1) + ' L motor</span>';
  if (!chips) return '';
  var head = '<div class="ca-ev-head"><span class="ca-ev-badge">&#x26FD; Bensin/Diesel</span></div>';
  return '<div class="ca-ev">' + head + '<div class="ca-ev-chips">' + chips + '</div></div>';
}

function caRenderCards(recommendations) {
  var container = document.getElementById('ca-cards');
  container.classList.add('fading');
  setTimeout(function() {
    container.classList.remove('fading');
    container.innerHTML = recommendations.map(function(r, i) {
      var prosHtml = (r.pros || []).map(function(p) { return '<li>' + caEsc(p) + '</li>'; }).join('');
      return '<div class="ca-card ca-card-'+(i+1)+'">' +
        '<div class="ca-card-head">' +
          '<span class="ca-card-num">Bil ' + (i + 1) + '</span>' +
          '<h3>' + caEsc(r.title) + '</h3>' +
          '<div class="ca-price">' + caEsc(r.price) + '</div>' +
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
          (r.expertOpinion ? '<hr class="ca-divider"><div class="ca-expert"><span class="ca-expert-name">&#x1F3AF; Erik Naess\xe9n</span><span class="ca-expert-text">'+caEsc(r.expertOpinion)+'</span></div>' : '') +
          (r.safetyRating ? '<div class="ca-safety"><span class="ca-safety-badge">Euro NCAP</span><span class="ca-safety-text">'+caEsc(r.safetyRating)+'</span></div>' : '') +
          (r.evSpec ? caEvChips(r.evSpec) : '') +
          (r.fuelSpec ? caFuelChips(r.fuelSpec) : '') +
          (r.cargoSpec ? caCargoChip(r.cargoSpec) : '') +
          '<button class="ca-ask-btn" data-idx="' + i + '" data-title="' + caEsc(r.title) + '">&#x1F4AC; Fr\xe5ga om Bil ' + (i + 1) + ' &mdash; ' + caEsc(r.title.replace(/\s*\(\d{4}\)\s*$/, '')) + '</button>' +
          '<div class="ca-market-links">' +
            '<a class="ca-blocket-btn" href="' + caBlocketUrl(r.title) + '" target="_blank" rel="noopener">Blocket &#x2192;</a>' +
            '<a class="ca-bytbil-btn" href="' + caBytbilUrl(r.title) + '" target="_blank" rel="noopener">Bytbil &#x2192;</a>' +
          '</div>' +
        '</div>' +
        '</div>';
    }).join('');
    caRenderCompare(recommendations);
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

function caRenderCompare(recs) {
  var cmp = document.getElementById('ca-compare');
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
    { label: '&#x1F4B0; Pris', fn: function(r){ return '<span style="color:#a5f3fc;font-weight:700;font-size:.85rem">'+caEsc(r.price)+'</span>'; } },
    { label: '&#x2714; F\xf6rdelar', fn: function(r){
      return '<ul style="margin:0;padding-left:14px">'+(r.pros||[]).map(function(p){
        return '<li style="font-size:.77rem;color:rgba(255,255,255,.7);margin-bottom:3px">'+caEsc(p)+'</li>';
      }).join('')+'</ul>';
    }},
    { label: '&#x26A0; Nackdel', fn: function(r){ return '<span style="color:#fca5a5;font-size:.8rem">'+caEsc(r.con)+'</span>'; } },
    { label: '&#x1F9F3; Bagageutrymme', fn: function(r){
      if (!r.cargoSpec || r.cargoSpec.cargoLiters <= 0) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      var txt = chip(r.cargoSpec.cargoLiters+' L', 'rgba(251,191,36,.12)');
      if (r.cargoSpec.cargoMaxLiters > 0) txt += ' <span style="font-size:.72rem;color:rgba(255,255,255,.4)">/ '+r.cargoSpec.cargoMaxLiters+' L</span>';
      return txt;
    }}
  ];
  if (hasFuel) {
    rows.push({ label: '&#x26FD; F\xf6rbrukning', fn: function(r){
      if (!r.fuelSpec || r.fuelSpec.consumptionLiterPerMil <= 0) return '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>';
      return chip(r.fuelSpec.consumptionLiterPerMil.toFixed(1)+' l/mil','rgba(251,146,60,.15)');
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
    rows.push({ label: '&#x26A1; DC max', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.maxDcKw > 0 ? chip(ev.maxDcKw+' kW','rgba(34,197,94,.12)') : '<span style="color:rgba(255,255,255,.25)">ingen DC</span>'; }); } });
    rows.push({ label: '&#x1F50C; AC max', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.maxAcKw > 0 ? chip(ev.maxAcKw+' kW','rgba(139,92,246,.14)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x1F50B; Batteri', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.batteryKwh > 0 ? chip(ev.batteryKwh+' kWh','rgba(56,189,248,.1)') : '&#x2013;'; }); } });
    rows.push({ label: '&#x1F4CA; Prisv\xe4rdhet', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.valueLabel ? chip(caEsc(ev.valueLabel),'rgba(52,211,153,.14)') : '<span style="color:rgba(255,255,255,.25)">&#x2013;</span>'; }); } });
  }
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
    fuelType:   document.getElementById('ca-fuel').value
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
  var payload = {
    budget:      parseInt(document.getElementById('ca-budget-slider').value),
    carCategory: document.getElementById('ca-category').value,
    hasCharger:  document.getElementById('ca-charger').value === 'true',
    kmPerYear:   parseInt(document.getElementById('ca-km').value) * 10,
    usage:       document.getElementById('ca-usage').value,
    passengers:  parseInt(document.getElementById('ca-passengers').value),
    newCar:      document.getElementById('ca-newcar').value === 'true',
    fuelType:    fuelVal
  };

  var controller = new AbortController();
  var timeoutId = setTimeout(function() { controller.abort(); }, 35000);
  var caToken = localStorage.getItem('ca_token') || '';
  var headers = { 'Content-Type': 'application/json' };
  if (caToken) headers['Authorization'] = 'Bearer ' + caToken;

  try {
    var r = await fetch('https://caradvice.onrender.com/api/recommend', {
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
      caRenderCards(d.recommendations);
      document.getElementById('ca-copy-btn').style.display = 'inline-block';
      document.getElementById('ca-share-result-btn').style.display = 'inline-block';
      if (d.cached) {
        var age = d.cachedAgeMinutes;
        var ageText = age < 1 ? 'precis' : age + ' min sedan';
        var badge = document.getElementById('ca-cache-badge');
        badge.textContent = '⚡ Cachat svar (' + ageText + ')';
        badge.style.display = 'inline-block';
      }
      if (!d.subscriber && typeof d.remainingSearches === 'number') caUpdateSubBar(false, d.remainingSearches);
      if (d.subscriber) caUpdateSubBar(true, null);
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
      ? '⏱ Servern svarade inte inom 35 sekunder. Render kan ha haft en cold start – f\xf6rs\xf6k igen om en stund.'
      : '🔌 Kunde inte n\xe5 servern: ' + e.message;
    document.getElementById('ca-cards').innerHTML =
      '<div class="ca-card"><div class="ca-raw">' + msg + '</div></div>';
    btn.disabled = false;
    btn.textContent = 'F\xf6rs\xf6k igen →';
  }
}

function caOpenSubscribe() {
  window.open('https://caradvice.onrender.com/subscribe.html', '_blank', 'width=480,height=650,resizable=yes');
}

function caUpdateSubBar(isSubscriber, remaining) {
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
    desc.textContent = remaining !== null ? ' – ' + remaining + ' av 30 s\xf6kningar kvar denna timme' : ' – 30 s\xf6kningar per timme';
    if (remaining !== null && remaining <= 5) bar.classList.add('ca-sub-bar-limited');
    prenBtn.style.display = 'none';
    loginLink.textContent = 'Konto';
    loginLink.href = 'https://caradvice.onrender.com/subscribe.html';
    loginLink.dataset.action = 'subscribe';
    if (caEmail) { emailEl.textContent = caEmail; emailEl.style.display = 'inline'; }
  } else if (caEmail) {
    title.textContent = 'Inloggad';
    desc.textContent = remaining !== null ? ' – ' + remaining + ' av 10 s\xf6kningar kvar denna timme' : ' – 10 s\xf6kningar per timme';
    if (remaining !== null && remaining <= 3) bar.classList.add('ca-sub-bar-limited');
    prenBtn.style.display = 'inline-block';
    prenBtn.textContent = 'Prenumerera – 99 kr/m\xe5n';
    loginLink.textContent = 'Logga ut';
    loginLink.href = '#';
    loginLink.dataset.action = 'logout';
    emailEl.textContent = caEmail; emailEl.style.display = 'inline';
  } else {
    title.textContent = 'Demo';
    desc.textContent = remaining !== null ? ' – ' + remaining + ' av 10 s\xf6kningar kvar denna timme' : ' – 10 gratis s\xf6kningar per timme';
    if (remaining !== null && remaining <= 3) bar.classList.add('ca-sub-bar-limited');
    prenBtn.style.display = 'inline-block';
    prenBtn.textContent = 'Prenumerera – 99 kr/m\xe5n';
    loginLink.textContent = 'Logga in';
    loginLink.href = 'https://caradvice.onrender.com/subscribe.html';
    loginLink.dataset.action = 'subscribe';
    emailEl.style.display = 'none';
  }
}

function caLogoutBar() {
  var token = localStorage.getItem('ca_token');
  fetch('https://caradvice.onrender.com/api/auth/logout', { method: 'POST', headers: { 'Authorization': 'Bearer ' + (token || '') } });
  localStorage.removeItem('ca_token'); localStorage.removeItem('ca_email'); localStorage.removeItem('ca_status');
  caUpdateSubBar(false, null);
}

window.addEventListener('message', function(ev) {
  if (!ev.data || !ev.data.type) return;
  if (ev.data.type === 'CA_LOGIN' || ev.data.type === 'CA_SUBSCRIBED') {
    if (ev.data.token) localStorage.setItem('ca_token', ev.data.token);
    if (ev.data.email) localStorage.setItem('ca_email', ev.data.email);
    if (ev.data.status) localStorage.setItem('ca_status', ev.data.status);
    caUpdateSubBar(ev.data.status === 'active', null);
  }
  if (ev.data.type === 'CA_LOGOUT') {
    localStorage.removeItem('ca_token'); localStorage.removeItem('ca_email'); localStorage.removeItem('ca_status');
    caUpdateSubBar(false, null);
  }
});

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
  caLoadPrefs();
  caReadUrlParams();
  caBindChangeListeners();
  caRenderHistory();

  function caBindEl(id, fn) { var el = document.getElementById(id); if (el) el.addEventListener('click', fn); }
  caBindEl('ca-btn', caGetRecommendation);
  caBindEl('ca-share-search-btn', caShareSearch);
  caBindEl('ca-reset-btn', caResetForm);
  caBindEl('ca-copy-btn', caCopyResult);
  caBindEl('ca-share-result-btn', caShareSearch);
  caBindEl('ca-login-link', function(e) { if (this.dataset.action === 'logout') { e.preventDefault(); caLogoutBar(); } });
  caBindEl('ca-prenumerera-btn', function(e) { e.preventDefault(); caOpenSubscribe(); });

  try {
    var status = localStorage.getItem('ca_status');
    caUpdateSubBar(status === 'active', null);
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
