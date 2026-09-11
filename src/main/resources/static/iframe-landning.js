// ── Landa i appen på sidorna där den ligger i ett <iframe> ──────────────────────────────
//
//   https://elitrobban.se/minipristaget/   (tågappen)
//   https://elitrobban.se/bankomat-2-0/    (bankomaten)
//
// RESERVEN, inte huvudvägen. Appen i ramen rullar fram sig själv när VÄLKOMSTSPLASHEN lyft
// (scrollIntoView inifrån ramen fungerar över domängränsen), och så ska det vara: rullningen
// hör ihop med att appen är klar att använda, inte med att sidan råkat ladda.
//
// Men båda tjänsterna ligger på Renders gratisnivå och sover. Uppmätt från kall start: 36 s
// för tåget, 12 s för bankomaten — och under den tiden händer ingenting alls i föräldersidan.
// Därför väntar den här filen ut splashvägen och rullar bara om ingen annan gjort det.
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

  // 25 s: appens egen splash hinner lyfta och rulla fram ramen först i alla normala fall
  // (mätt 7,7 s för tåget och 9,3 s för bankomaten på en vaken tjänst). Slår det här taket
  // till betyder det att tjänsten sover — då är en rullning bättre än en sida som står still.
  var TAK_MS = 25000;
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
