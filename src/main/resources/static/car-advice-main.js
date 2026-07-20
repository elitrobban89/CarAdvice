// BilRådgivning — main form logic
// Loaded as external script to bypass WordPress inline-script restrictions

window.onerror = function(msg, src, line) {
  var d = document.getElementById('ca-js-error');
  if (d) { d.textContent = 'JS-fel rad ' + line + ': ' + msg; d.style.display = 'block'; }
};

window._ca = function(action, arg) {
  if (window._caFns && window._caFns[action]) window._caFns[action](arg);
};

var CA_API_BASE = 'https://caradvice.onrender.com';

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
var CA_CAT_NAMES = { ekonomibil: 'Ekonomibil', smaabil: 'Sm\xe5bil', familjebil: 'Familjebil', elbil: 'Elbil', suv: 'SUV', laddhybrid: 'Laddhybrid' };
var CA_FUEL_NAMES = { bensin: 'Bensin', diesel: 'Diesel', hybrid: 'Hybrid' };
var CA_TRANSMISSION_NAMES = { manuell: 'Manuell', automat: 'Automat' };
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
  var val = parseInt(slider.value);
  var min = caIsLeasing ? 1000 : 50000;
  var max = caIsLeasing ? 15000 : 1000000;
  var pct = (val - min) / (max - min) * 100;
  document.getElementById('ca-slider-fill').style.width = pct + '%';
  document.getElementById('ca-budget-display').textContent = caIsLeasing
    ? val.toLocaleString('sv-SE') + '\xa0kr/m\xe5n'
    : val.toLocaleString('sv-SE') + '\xa0kr';
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
      maxage:       maEl ? maEl.value : ''
    }));
  } catch(e) {}
}
function caLoadPrefs() {
  try {
    var raw = localStorage.getItem('ca-prefs');
    if (!raw) return;
    var d = JSON.parse(raw);
    if (d.category)   document.getElementById('ca-category').value   = d.category;
    caSetBudgetMode(d.budgetMode || 'köp', d.budget ? parseInt(d.budget) : undefined);
    if (d.charger)    document.getElementById('ca-charger').value     = d.charger;
    if (d.km)         document.getElementById('ca-km').value          = d.km;
    if (d.usage)      document.getElementById('ca-usage').value       = d.usage;
    if (d.passengers) document.getElementById('ca-passengers').value  = d.passengers;
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
    if (p.get('category'))   document.getElementById('ca-category').value   = p.get('category');
    caSetBudgetMode(p.get('budgetMode') || 'köp', p.get('budget') ? parseInt(p.get('budget')) : undefined);
    if (p.get('charger'))    document.getElementById('ca-charger').value     = p.get('charger');
    if (p.get('km'))         document.getElementById('ca-km').value          = p.get('km');
    if (p.get('usage'))      document.getElementById('ca-usage').value       = p.get('usage');
    if (p.get('passengers')) document.getElementById('ca-passengers').value  = p.get('passengers');
    if (p.get('newcar'))     document.getElementById('ca-newcar').value      = p.get('newcar');
    if (p.get('fuelType'))    document.getElementById('ca-fuel').value        = p.get('fuelType');
    if (p.get('transmission')) { var t = document.getElementById('ca-transmission'); if (t) t.value = p.get('transmission'); }
    if (p.get('maxage')) { var ma = document.getElementById('ca-maxage'); if (ma) ma.value = p.get('maxage'); }
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
    maxage:       maSnap ? maSnap.value : ''
  };
}

function caCheckChanges() {
  if (!caHasSearched) return;
  var ids  = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel','ca-transmission','ca-maxage'];
  var keys = ['category','budget','charger','km','usage','passengers','newcar','fuelType','transmission','maxage'];
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
  var ids = ['ca-category','ca-budget-slider','ca-charger','ca-km','ca-usage','ca-passengers','ca-newcar','ca-fuel','ca-transmission','ca-maxage'];
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
  if (chg) chg.addEventListener('change', caUpdateFuelVisibility);
  if (bud) bud.addEventListener('input', caUpdateSliderFill);
  if (nc)  nc.addEventListener('change', caUpdateMaxAgeVisibility);
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
  if (entry.category)   document.getElementById('ca-category').value   = entry.category;
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
  document.getElementById('ca-category').value   = 'ekonomibil';
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
  if (ev.maxDcKw > 0) chips += '<span class="ca-ev-chip ca-ev-dc">&#x26A1; DC '+ev.maxDcKw+' kW</span>';
  if (ev.maxAcKw > 0) chips += '<span class="ca-ev-chip ca-ev-ac">&#x1F50C; AC '+ev.maxAcKw+' kW</span>';
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

function caRenderCards(recommendations) {
  var container = document.getElementById('ca-cards');
  container.classList.add('fading');
  setTimeout(function() {
    container.classList.remove('fading');
    container.innerHTML = recommendations.map(function(r, i) {
      var prosHtml = (r.pros || []).map(function(p) { return '<li>' + caEsc(p) + '</li>'; }).join('');
      // Leasing: uppskattad månadskostnad, aldrig Blocket (begagnatmarknad).
      // Köp: Blocket-priset är sanningen när det finns; AI-priset bara som fallback.
      var priceRow;
      if (caIsLeasing) {
        var monthly = caLeaseMonthlyEstimate(r);
        priceRow = '<div class="ca-price"><span style="font-size:.62rem;font-weight:600;color:rgba(255,255,255,.35);margin-right:4px;text-transform:uppercase;letter-spacing:.04em">Leasing</span>' +
          (monthly ? '~' + monthly.toLocaleString('sv-SE') + ' kr/m\xe5n' : caEsc(r.price)) + '</div>';
      } else if (r.blocketPrice) {
        priceRow = '<div class="ca-price"><span style="font-size:.62rem;font-weight:600;color:rgba(255,255,255,.35);margin-right:4px;text-transform:uppercase;letter-spacing:.04em">Pris</span>🔵 ' + caEsc(r.blocketPrice) + '</div>';
      } else {
        priceRow = '<div class="ca-price"><span style="font-size:.62rem;font-weight:600;color:rgba(255,255,255,.35);margin-right:4px;text-transform:uppercase;letter-spacing:.04em">Pris</span>' + caEsc(r.price) + '</div>';
      }
      return '<div class="ca-card ca-card-'+(i+1)+'">' +
        '<div id="ca-img-wrap-'+i+'" style="width:100%;height:80px;overflow:hidden;border-radius:inherit;background:rgba(255,255,255,.04);margin-bottom:0;display:none">' +
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
      '</div>';
    caFetchCarImages(recommendations);
    caFetchInsights(recommendations);
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
    rows.push({ label: '&#x26A1; DC max', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.maxDcKw > 0 ? chip(ev.maxDcKw+' kW','rgba(34,197,94,.12)') : '<span style="color:rgba(255,255,255,.25)">ingen DC</span>'; }); } });
    rows.push({ label: '&#x1F50C; AC max', evOnly: true, fn: function(r){ return evCell(r, function(ev){ return ev.maxAcKw > 0 ? chip(ev.maxAcKw+' kW','rgba(139,92,246,.14)') : '&#x2013;'; }); } });
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
    if (prefs.carCategory) document.getElementById('ca-category').value = prefs.carCategory;
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
      maxAgeYears:  (function(){ var el = document.getElementById('ca-maxage'); var nc = document.getElementById('ca-newcar'); return (el && nc && nc.value !== 'true' && el.value) ? parseInt(el.value) : null; })()
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

/** Månadskostnad för leasing: direkt ur "X kr/mån"-pris, annars listpris/85 (backendens leasingfaktor). */
function caLeaseMonthlyEstimate(r) {
  var direct = caParseLeaseMonthly(r.price);
  if (direct) return Math.round(direct / 100) * 100;
  var mid = caParsePrice(r.price);
  return mid ? Math.round(mid / 85 / 100) * 100 : 0;
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
    maxage:       (function(){ var el = document.getElementById('ca-maxage'); return el ? el.value : ''; })()
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
    maxAgeYears:  (function(){ var el = document.getElementById('ca-maxage'); var nc = document.getElementById('ca-newcar'); return (el && nc && nc.value !== 'true' && el.value) ? parseInt(el.value) : null; })()
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

function caFcRenderResult(recs) {
  var result = document.getElementById('ca-fc-result');
  if (!result || !recs || recs.length < 2) return;

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
