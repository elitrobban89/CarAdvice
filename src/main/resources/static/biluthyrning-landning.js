// ── Biluthyrningssidan: landa i appkortet, inte i säljtexten ────────────────────────────
//
//   https://elitrobban.se/biluthyrning/
//
// Sidan är INTE en inbäddad app som de andra — appen öppnas i egen flik (cookies måste vara
// förstaparts, se wordpress-embed.html) — så det som ska rullas fram är kortet med knappen
// "Öppna BilUthyrning". Det ligger under sidhuvud, rubrik och ett stycke säljtext.
//
// Filen är EXTERN därför att WordPress blockerar inline-<script> på sidan, och den serveras
// av CarAdvice därför att den tjänsten ligger på betald plan och alltid är vaken. Den laddas
// från blocket i wordpress-embed.html — ändras filen här räcker en deploy, men läggs den till
// FÖRSTA gången måste blocket klistras om i WordPress.
//
// Samma regler som elbilsappens och bränslekalkylatorns rullning: en gång, aldrig ovanpå en
// djuplänk, och aldrig när besökaren själv redan tagit tag i sidan.
(function () {
  'use strict';

  var TAK_MS = 700;          // tid för sidhuvud/typsnitt att lägga sig innan vi mäter
  var gjort = false;
  var egenScroll = false;

  var EGNA_TANGENTER = { PageDown: 1, PageUp: 1, End: 1, Home: 1, ArrowDown: 1, ArrowUp: 1, ' ': 1 };
  function egenRorelse(e) {
    if (e.type === 'keydown' && !EGNA_TANGENTER[e.key]) return;
    egenScroll = true;
  }
  ['wheel', 'touchmove', 'keydown'].forEach(function (t) {
    window.addEventListener(t, egenRorelse, { passive: true });
  });

  /** Sidhuvud som ligger kvar överst (sticky/fixed) och annars hade täckt kortets topp. */
  function fastHuvudHojd() {
    var hojd = 0;
    var kandidater = document.querySelectorAll('header, #wpadminbar, .site-header, [class*="sticky"]');
    for (var i = 0; i < kandidater.length; i++) {
      var pos = '';
      try { pos = getComputedStyle(kandidater[i]).position; } catch (e) { continue; }
      if (pos !== 'fixed' && pos !== 'sticky') continue;
      var r = kandidater[i].getBoundingClientRect();
      if (r.top <= 4 && r.bottom > hojd) hojd = r.bottom;
    }
    return Math.min(hojd, 160);
  }

  function rulla() {
    if (gjort) return;
    gjort = true;
    // En djuplänk och ett eget scrollval är båda uttryckta önskemål om var sidan ska stå.
    if (location.hash || egenScroll) return;
    var mal = document.querySelector('.bu-cta-wrap') || document.querySelector('.bu-cta');
    if (!mal) return;
    var topp = mal.getBoundingClientRect().top + window.pageYOffset - fastHuvudHojd() - 14;
    if (topp <= 8) return; // kortet syns redan
    var lugnt = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    try { window.scrollTo({ top: topp, behavior: lugnt ? 'auto' : 'smooth' }); }
    catch (e) { window.scrollTo(0, topp); }
  }

  function start() { setTimeout(rulla, TAK_MS); }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
