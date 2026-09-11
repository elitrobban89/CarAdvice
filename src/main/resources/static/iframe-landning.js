// ── Landa i appen på sidorna där den ligger i ett <iframe> ──────────────────────────────
//
//   https://elitrobban.se/minipristaget/   (tågappen)
//   https://elitrobban.se/bankomat-2-0/    (bankomaten)
//
// Rullar fram ramen DIREKT, så att appens egen välkomstsplash spelar i bild.
//
// Första versionen väntade ut appen i ramen (den rullar också fram sig själv). Det var fel
// ordning: splashen hann spela klart för en skärm ingen tittade på, och besökaren kom ned
// till appen precis när den var över — "jag fick ingen splash screen". Nu rullar
// föräldersidan fram ramen med en gång, och splashen syns medan den går.
//
// Att göra det HÄR och inte bara i appen har ett andra skäl: båda tjänsterna ligger på
// Renders gratisnivå och sover. Uppmätt från kall start dröjde det 36 s för tåget och 12 s
// för bankomaten innan appen inuti ens hann köra sin egen rullning.
//
// EXTERN fil därför att WordPress blockerar inline-<script>, och serverad av CarAdvice
// därför att den tjänsten ligger på betald plan och alltid är vaken. Klistra in raden
//   <script src="https://caradvice.onrender.com/iframe-landning.js" defer></script>
// i samma Anpassad HTML-block som <iframe>-taggen.
//
// Samma regler som de andra landningsrullningarna: en gång, aldrig över en djuplänk, aldrig
// när besökaren själv redan tagit tag i sidan, och aldrig när ramen redan syns.
(function () {
  'use strict';

  // 600 ms: bara så mycket att sidhuvud, bilder och block hunnit lägga sig, annars siktar
  // mätningen på en ram som fortfarande flyttar sig. Splashen i ramen börjar senare än så
  // även på en vaken tjänst, så den spelar i bild.
  var TAK_MS = 600;
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

  /** Sidhuvud som ligger kvar överst (sticky/fixed) och annars hade täckt ramens topp. */
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

  /** Appramen — den som pekar på en av våra tjänster, annars sidans första iframe. */
  function appRamen() {
    var ramar = document.querySelectorAll('iframe');
    for (var i = 0; i < ramar.length; i++) {
      var src = ramar[i].getAttribute('src') || '';
      if (src.indexOf('onrender.com') >= 0) return ramar[i];
    }
    // Inbäddade videor och kartor ska INTE rullas fram — bara en app vi själva kör.
    return null;
  }

  function rulla() {
    if (gjort) return;
    gjort = true;
    if (location.hash || egenScroll) return;
    // Har sidan redan flyttat sig har antingen appen i ramen rullat fram sig själv (det
    // normala) eller besökaren gjort det — och då ska ingen rycka i sidan en andra gång.
    if (window.pageYOffset > 40) return;
    var ram = appRamen();
    if (!ram) return;
    var topp = ram.getBoundingClientRect().top + window.pageYOffset - fastHuvudHojd() - 12;
    if (topp <= 8) return; // ramen syns redan
    var lugnt = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    try { window.scrollTo({ top: topp, behavior: lugnt ? 'auto' : 'smooth' }); }
    catch (e) { window.scrollTo(0, topp); }
  }

  function start() { setTimeout(rulla, TAK_MS); }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
