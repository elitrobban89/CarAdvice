(function () {
  var CA_CHAT_API = window.CA_API_URL || (typeof CA_API_BASE !== 'undefined' ? CA_API_BASE : "https://caradvice.onrender.com");
  var caChatHistory = (function(){ try{ return JSON.parse(localStorage.getItem('ca-chat')||'[]'); }catch(e){ return []; } })();

  // Intercept caRenderCards to capture current recommendations as context
  (function() {
    function hook() {
      if (typeof window.caRenderCards === 'function' && !window.caRenderCards.__caHooked) {
        var _orig = window.caRenderCards;
        window.caRenderCards = function(recs) {
          window._caRecommendations = recs;
          var result = _orig.call(this, recs);
          caChatSetRecsContext(recs);
          return result;
        };
        window.caRenderCards.__caHooked = true;
      }
    }
    document.addEventListener('DOMContentLoaded', hook);
    setTimeout(hook, 500);
  })();

  function shortName(title) {
    return title.replace(/\s*\(\d{4}\)\s*$/, '').trim();
  }

  function caChatSetCarImg(title) {
    var wrap = document.getElementById('ca-chat-img-wrap');
    if (wrap) wrap.style.display = 'none';
    if (title && typeof window.caFetchOneImage === 'function') {
      window.caFetchOneImage(title, 'ca-chat-img-wrap', 'ca-chat-car-img');
    }
  }

  window.caChatFocusCar = function(idx, title) {
    var name = title.replace(/\s*\(\d{4}\)\s*$/, '').trim();
    var recs = window._caRecommendations || [];

    caChatSetCarImg(title);

    // Update FAB label
    var labelEl = document.querySelector('.ca-chat-fab-label');
    if (labelEl) labelEl.textContent = '💬 Fråga om ' + name;

    // Update chips focused on this specific car
    var quick = document.getElementById('ca-chat-quick');
    if (quick) {
      var otherNames = recs.filter(function(r, i) { return i !== idx; })
                           .map(function(r) { return r.title.replace(/\s*\(\d{4}\)\s*$/, '').trim(); });
      var chips = [
        { label: '🔍 Berätta om ' + name, q: 'Berätta mer om ' + name + ' — vad är fördelarna och nackdelarna?' },
        { label: '💰 Driftkostnad & skatt', q: 'Vad kostar det att äga ' + name + ' per månad? Räkna in skatt, försäkring och bränsle/el.' },
        { label: '🔧 Tillförlitlighet & problem', q: 'Vilka vanliga problem eller fel brukar ' + name + ' ha?' },
      ];
      if (otherNames.length > 0) {
        chips.push({ label: '⚖️ Jämför med ' + otherNames[0], q: 'Jämför ' + name + ' med ' + otherNames[0] + ' — vilken är bäst för mig?' });
      }
      quick.innerHTML = chips.map(function(c) {
        return '<button class="ca-chat-quick-btn" data-q="' + c.q.replace(/"/g, '&quot;') + '">' + c.label + '</button>';
      }).join('');
      quick.querySelectorAll('.ca-chat-quick-btn').forEach(function(btn) {
        btn.addEventListener('click', function() { caChatSendMessage(btn.dataset.q); });
      });
      quick.style.display = 'flex';
    }

    // Contextual message
    var hasUserMsgs = caChatHistory.some(function(m) { return m.role === 'user'; });
    if (!hasUserMsgs) {
      var firstBot = document.querySelector('#ca-chat-messages .ca-chat-bubble.bot');
      if (firstBot) {
        firstBot.innerHTML = caChatMarkdown('Du har valt **' + name + '** — vad vill du veta? Jag kan svara på frågor om driftkostnad, tillförlitlighet, räckvidd och mer! 🚗');
      }
    } else {
      caChatAppendBot('Fokuserar på **' + name + '**! Vad vill du veta om den?', true);
    }

    // Open chat panel
    var panel = document.getElementById('ca-chat-panel');
    if (panel && panel.style.display === 'none') caChatToggle();
  };

  function caChatSetRecsContext(recs) {
    if (!recs || !recs.length) return;
    var names = recs.slice(0, 3).map(function(r) { return shortName(r.title); });
    caChatSetCarImg(recs[0].title);

    // Update FAB label
    var labelEl = document.querySelector('.ca-chat-fab-label');
    if (labelEl) labelEl.textContent = '💬 Fråga om ' + names[0];

    // Update quick chips
    caChatUpdateQuickChips(recs, names);

    // Update greeting or post contextual note
    var hasUserMsgs = caChatHistory.some(function(m) { return m.role === 'user'; });
    if (!hasUserMsgs) {
      var firstBot = document.querySelector('#ca-chat-messages .ca-chat-bubble.bot');
      if (firstBot) {
        firstBot.innerHTML = caChatMarkdown(
          'Bra sökning! Du fick förslag på **' + names.join(', ') + '** 🚗\n\nVill du jämföra dem, veta mer om någon specifik bil, eller få hjälp med något annat?'
        );
      }
    } else {
      var panelOpen = document.getElementById('ca-chat-panel') &&
                      document.getElementById('ca-chat-panel').style.display !== 'none';
      if (panelOpen) {
        caChatAppendBot('Ny sökning klar! Förslag: **' + names.join(', ') + '**. Fråga mig vad du vill veta!', true);
      }
    }
  }

  function caChatUpdateQuickChips(recs, names) {
    var quick = document.getElementById('ca-chat-quick');
    if (!quick) return;
    var chips = [];
    if (names.length >= 1) chips.push({ label: '🔍 Berätta om ' + names[0], q: 'Berätta mer om ' + names[0] + ' — vad är för- och nackdelarna?' });
    if (names.length >= 2) chips.push({ label: '⚖️ Jämför ' + names[0] + ' vs ' + names[1], q: 'Jämför ' + names[0] + ' och ' + names[1] + ' — vilken passar mig bäst?' });
    chips.push({ label: '🎯 Vilken passar mig bäst?', q: 'Av ' + names.join(', ') + ' — vilken passar mig bäst baserat på mina önskemål?' });
    if (names.length >= 1) chips.push({ label: '💰 Driftkostnad & skatt', q: 'Vad kostar det att äga ' + names[0] + ' per månad? Skatt, försäkring och driftkostnad.' });
    quick.innerHTML = chips.slice(0, 4).map(function(c) {
      return '<button class="ca-chat-quick-btn" data-q="' + c.q.replace(/"/g, '&quot;') + '">' + c.label + '</button>';
    }).join('');
    quick.querySelectorAll('.ca-chat-quick-btn').forEach(function(btn) {
      btn.addEventListener('click', function() { caChatSendMessage(btn.dataset.q); });
    });
    quick.style.display = 'flex';
  }

  function detectMentionedCar(msg) {
    var recs = window._caRecommendations;
    if (!recs || !recs.length || !msg) return null;
    var msgLower = msg.toLowerCase();
    var best = null, bestScore = 0;
    recs.forEach(function(r) {
      var name = r.title.replace(/\s*\(\d{4}\)\s*$/, '').toLowerCase();
      var words = name.split(' ').filter(function(w) { return w.length > 3; });
      var score = words.filter(function(w) { return msgLower.indexOf(w) !== -1; }).length;
      if (score > bestScore) { bestScore = score; best = r; }
    });
    return bestScore > 0 ? best : null;
  }

  function buildCarContext(userMsg) {
    var recs = window._caRecommendations;
    if (!recs || recs.length === 0) return null;
    var lines = ["Aktuella bilrekommendationer:"];
    recs.forEach(function(r, i) {
      var priceInfo = r.price;
      if (r.blocketPrice) priceInfo += " (Blocket just nu: " + r.blocketPrice + ")";
      lines.push((i+1) + ". " + r.title + " — " + priceInfo + " — " + r.whyRecommended);
    });
    if (userMsg) {
      var focused = detectMentionedCar(userMsg);
      if (focused) {
        var focusName = focused.title.replace(/\s*\(\d{4}\)\s*$/, '');
        lines.push("\nOBS: Användaren frågar specifikt om " + focusName + ". Svara ENBART om denna bil, inte om de andra.");
      }
    }
    return lines.join("\n");
  }

  function caSaveChatHistory() {
    try { localStorage.setItem('ca-chat', JSON.stringify(caChatHistory.slice(-20))); } catch(e) {}
  }

  function initCaChat() {
    var style = document.createElement("style");
    style.textContent = `
      .ca-chat-fab-wrap {
        position:fixed;bottom:24px;right:24px;z-index:9999;
        display:flex;flex-direction:column;align-items:center;gap:6px;
      }
      .ca-chat-fab-label {
        background:rgba(139,92,246,0.15);border:1px solid rgba(139,92,246,0.4);
        color:#c4b5fd;font-size:11px;font-weight:700;padding:3px 10px;
        border-radius:20px;white-space:nowrap;letter-spacing:0.04em;
        animation:ca-label-pulse 3s ease-in-out infinite;
      }
      @keyframes ca-label-pulse {
        0%,100%{opacity:.7;transform:translateY(0)}
        50%{opacity:1;transform:translateY(-2px)}
      }
      .ca-chat-fab-ring {
        position:relative;display:flex;align-items:center;justify-content:center;
      }
      .ca-chat-spark {
        position:absolute;font-size:13px;line-height:1;pointer-events:none;
        animation:ca-spark 2.4s ease-in-out infinite;
      }
      .ca-chat-spark:nth-child(1){top:-16px;left:50%;transform:translateX(-50%);animation-delay:0s;}
      .ca-chat-spark:nth-child(2){top:16px;left:-18px;animation-delay:.9s;}
      .ca-chat-spark:nth-child(3){top:16px;right:-18px;animation-delay:1.8s;}
      @keyframes ca-spark {
        0%,100%{opacity:.3;transform:scale(.8) translateY(0);}
        50%{opacity:1;transform:scale(1.2) translateY(-4px);}
      }
      .ca-chat-fab {
        width:58px;height:58px;border-radius:18px;
        background:linear-gradient(145deg,#4c1d95,#6d28d9,#7c3aed);
        border:none;cursor:pointer;
        box-shadow:0 4px 20px rgba(109,40,217,.6);
        display:flex;align-items:center;justify-content:center;
        transition:transform .15s,box-shadow .15s;
      }
      .ca-chat-fab:hover{transform:scale(1.08);box-shadow:0 6px 28px rgba(109,40,217,.8);}
      .ca-chat-panel {
        position:fixed;bottom:96px;right:24px;z-index:9998;
        width:380px;max-height:540px;
        background:rgba(15,12,41,0.78);
        backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);
        border:1px solid rgba(139,92,246,0.25);border-radius:20px;
        box-shadow:0 8px 48px rgba(0,0,0,.65),0 0 0 1px rgba(255,255,255,0.04) inset;
        display:flex;flex-direction:column;overflow:hidden;
        font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
      }
      .ca-chat-header {
        background:linear-gradient(135deg,rgba(76,29,149,0.9),rgba(109,40,217,0.8));
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border-bottom:1px solid rgba(139,92,246,0.2);
        color:#fff;padding:13px 16px;
        display:flex;align-items:center;justify-content:space-between;
        font-weight:700;font-size:14px;flex-shrink:0;gap:8px;
      }
      .ca-chat-header-title { display:flex;align-items:center;gap:8px; }
      .ca-chat-header-actions { display:flex;align-items:center;gap:6px; }
      .ca-chat-header-clear {
        background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.18);
        color:rgba(255,255,255,0.75);font-size:11px;font-weight:600;padding:3px 9px;
        border-radius:20px;cursor:pointer;transition:all .15s;white-space:nowrap;
      }
      .ca-chat-header-clear:hover { background:rgba(255,255,255,0.16);color:#fff; }
      .ca-chat-header-close {
        background:none;border:none;color:rgba(255,255,255,0.7);font-size:20px;
        cursor:pointer;padding:0 2px;line-height:1;transition:color .12s;
      }
      .ca-chat-header-close:hover { color:#fff; }
      .ca-chat-messages {
        flex:1;overflow-y:auto;padding:14px 12px;
        display:flex;flex-direction:column;gap:10px;
        background:transparent;min-height:0;
      }
      .ca-chat-messages::-webkit-scrollbar { width:4px; }
      .ca-chat-messages::-webkit-scrollbar-track { background:transparent; }
      .ca-chat-messages::-webkit-scrollbar-thumb { background:rgba(139,92,246,0.3);border-radius:4px; }
      .ca-chat-bubble {
        max-width:85%;padding:10px 13px;border-radius:14px;
        font-size:13px;line-height:1.6;word-break:break-word;
      }
      .ca-chat-bubble.bot {
        background:rgba(30,24,60,0.7);
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border:1px solid rgba(139,92,246,0.18);
        border-radius:4px 14px 14px 14px;align-self:flex-start;color:#e9d5ff;
      }
      .ca-chat-bubble.bot strong { color:#c4b5fd; }
      .ca-chat-bubble.bot ul { margin:6px 0 2px 16px;padding:0;display:flex;flex-direction:column;gap:3px; }
      .ca-chat-bubble.bot li { list-style:disc; }
      .ca-chat-bubble.user {
        background:linear-gradient(135deg,rgba(109,40,217,0.85),rgba(139,92,246,0.8));
        backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);
        border:1px solid rgba(167,139,250,0.2);
        color:#fff;border-radius:14px 14px 4px 14px;align-self:flex-end;
      }
      .ca-chat-quick {
        padding:10px 12px 4px;display:flex;flex-wrap:wrap;gap:7px;flex-shrink:0;
        background:rgba(15,12,41,0.5);border-top:1px solid rgba(139,92,246,0.12);
      }
      .ca-chat-quick-btn {
        background:rgba(139,92,246,0.1);border:1px solid rgba(139,92,246,0.28);color:#c4b5fd;
        border-radius:20px;padding:5px 12px;font-size:12px;font-weight:600;
        cursor:pointer;transition:all .15s;white-space:nowrap;
        backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);
      }
      .ca-chat-quick-btn:hover { background:rgba(139,92,246,0.35);color:#fff;border-color:rgba(167,139,250,0.55); }
      .ca-chat-input-row {
        display:flex;gap:8px;padding:10px 12px;
        border-top:1px solid rgba(139,92,246,0.12);
        background:rgba(15,12,41,0.5);flex-shrink:0;
      }
      .ca-chat-input {
        flex:1;border:1px solid rgba(139,92,246,0.22);border-radius:22px;
        padding:8px 14px;font-size:13px;outline:none;
        background:rgba(30,24,60,0.6);color:#f3e8ff;transition:border-color .15s,box-shadow .15s;
        backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);
      }
      .ca-chat-input::placeholder { color:rgba(196,181,253,0.35); }
      .ca-chat-input:focus { border-color:rgba(167,139,250,0.55);box-shadow:0 0 0 3px rgba(139,92,246,0.12); }
      .ca-chat-send {
        width:38px;height:38px;border-radius:50%;
        background:linear-gradient(135deg,#6d28d9,#8b5cf6);
        color:#fff;border:none;cursor:pointer;
        font-size:16px;display:flex;align-items:center;justify-content:center;
        flex-shrink:0;transition:all .15s;
        box-shadow:0 2px 10px rgba(109,40,217,0.4);
      }
      .ca-chat-send:hover { background:linear-gradient(135deg,#7c3aed,#a78bfa);box-shadow:0 4px 14px rgba(109,40,217,0.6); }
      .ca-chat-typing { display:flex;gap:4px;align-items:center;padding:4px 0; }
      .ca-chat-typing span {
        width:7px;height:7px;border-radius:50%;background:rgba(167,139,250,0.5);
        animation:ca-bounce .9s infinite;display:inline-block;
      }
      .ca-chat-typing span:nth-child(2) { animation-delay:.15s; }
      .ca-chat-typing span:nth-child(3) { animation-delay:.3s; }
      @keyframes ca-bounce { 0%,80%,100%{transform:translateY(0)} 40%{transform:translateY(-6px)} }
      .ca-chat-cursor{display:inline-block;width:2px;height:13px;background:#c4b5fd;margin-left:2px;border-radius:1px;animation:ca-cursor-blink .55s steps(1) infinite;vertical-align:middle;}
      @keyframes ca-cursor-blink{0%,100%{opacity:1}50%{opacity:0}}
      .ca-chat-feedback{display:flex;gap:6px;margin-top:6px;padding-left:2px;align-items:center;}
      .ca-chat-thumb{background:none;border:1px solid rgba(139,92,246,0.2);color:rgba(196,181,253,0.38);font-size:12px;padding:2px 8px;border-radius:10px;cursor:pointer;transition:all .15s;line-height:1.5;}
      .ca-chat-thumb:hover{border-color:rgba(139,92,246,0.55);color:#c4b5fd;}
      .ca-chat-thumb.voted{border-color:rgba(139,92,246,0.7);color:#c4b5fd;background:rgba(139,92,246,0.1);}
      .ca-chat-retry{background:none;border:1px solid rgba(239,68,68,0.3);color:rgba(239,68,68,0.65);font-size:11px;font-weight:600;padding:4px 11px;border-radius:20px;cursor:pointer;margin-top:7px;display:inline-block;transition:all .15s;}
      .ca-chat-retry:hover{border-color:rgba(239,68,68,0.6);color:#ef4444;}
      @media(max-width:400px){
        .ca-chat-panel{width:calc(100vw - 16px);right:8px;bottom:92px;}
        .ca-chat-fab-wrap{right:12px;bottom:12px;}
      }
    `;
    document.head.appendChild(style);

    var root = document.createElement("div");
    root.innerHTML = `
      <div class="ca-chat-fab-wrap">
        <span class="ca-chat-fab-label">🚗 Fråga AI</span>
        <div class="ca-chat-fab-ring">
          <span class="ca-chat-spark">⚡</span>
          <span class="ca-chat-spark">⛽</span>
          <span class="ca-chat-spark">⚡</span>
          <button class="ca-chat-fab" id="ca-chat-fab" title="Fråga bilrådgivaren">
            <svg viewBox="0 0 52 40" width="38" height="30" xmlns="http://www.w3.org/2000/svg">
              <!-- car body -->
              <rect x="4" y="18" width="44" height="14" rx="5" fill="rgba(255,255,255,0.15)" stroke="rgba(255,255,255,0.35)" stroke-width="1.2"/>
              <!-- roof -->
              <path d="M14 18 Q18 8 22 7 L30 7 Q34 8 38 18Z" fill="rgba(255,255,255,0.2)" stroke="rgba(255,255,255,0.35)" stroke-width="1.2"/>
              <!-- windows -->
              <path d="M16 18 Q19 10 22 9 L29 9 Q32 10 35 18Z" fill="rgba(196,181,253,0.25)"/>
              <!-- wheels -->
              <circle cx="14" cy="33" r="5.5" fill="#1e1b4b" stroke="rgba(167,139,250,0.6)" stroke-width="1.5"/>
              <circle cx="14" cy="33" r="2.5" fill="rgba(167,139,250,0.5)"/>
              <circle cx="38" cy="33" r="5.5" fill="#1e1b4b" stroke="rgba(167,139,250,0.6)" stroke-width="1.5"/>
              <circle cx="38" cy="33" r="2.5" fill="rgba(167,139,250,0.5)"/>
              <!-- headlight -->
              <rect x="44" y="21" width="4" height="3" rx="1.5" fill="#fef08a"/>
              <!-- lightning bolt (EV) -->
              <path d="M24 12 L21 19 L25 17 L23 24" fill="#fef08a" stroke="#fef08a" stroke-width="0.4" stroke-linejoin="round"/>
              <!-- fuel drop (petrol) -->
              <path d="M31 11 Q33 8 33 12 Q33 15 31 15 Q29 15 29 12 Q29 8 31 11Z" fill="rgba(251,191,36,0.8)"/>
            </svg>
          </button>
        </div>
      </div>
      <div class="ca-chat-panel" id="ca-chat-panel" style="display:none;">
        <div class="ca-chat-header">
          <div class="ca-chat-header-title">
            <div id="ca-chat-img-wrap" style="width:40px;height:28px;flex-shrink:0;border-radius:5px;overflow:hidden;background:rgba(255,255,255,.08);display:none;margin-right:6px">
              <img id="ca-chat-car-img" src="" alt="" style="width:100%;height:100%;object-fit:contain">
            </div>
            <span>🚗 Bilrådgivaren</span>
          </div>
          <div class="ca-chat-header-actions">
            <button class="ca-chat-header-clear" id="ca-chat-clear">Rensa</button>
            <button class="ca-chat-header-close" id="ca-chat-close">✕</button>
          </div>
        </div>
        <div class="ca-chat-messages" id="ca-chat-messages"></div>
        <div class="ca-chat-quick" id="ca-chat-quick">
          <button class="ca-chat-quick-btn" data-q="Elbil eller laddhybrid — vad passar mig?">⚡ Elbil vs hybrid</button>
          <button class="ca-chat-quick-btn" data-q="Vilken bil ger bäst värde för pengarna?">💰 Bäst värde</button>
          <button class="ca-chat-quick-btn" data-q="Vilken begagnad bil är mest pålitlig?">🔧 Pålitlighet</button>
          <button class="ca-chat-quick-btn" data-q="Vad ska jag tänka på när jag köper begagnad bil?">📋 Köpguide</button>
        </div>
        <div class="ca-chat-input-row">
          <input class="ca-chat-input" id="ca-chat-input" type="text" placeholder="Ställ en fråga om bilar…" autocomplete="off"/>
          <button class="ca-chat-send" id="ca-chat-send">➤</button>
        </div>
      </div>
    `;
    document.body.appendChild(root);

    if (caChatHistory.length > 0) {
      document.getElementById("ca-chat-quick").style.display = "none";
      caChatHistory.forEach(function(m) {
        if (m.role === "user") caChatAppendUser(m.content);
        else if (m.role === "assistant") caChatAppendBot(m.content, false);
      });
      var msgsEl = document.getElementById("ca-chat-messages");
      msgsEl.scrollTop = msgsEl.scrollHeight;
      var labelEl = document.querySelector(".ca-chat-fab-label");
      if (labelEl) labelEl.textContent = "💬 Fortsätt chatten";
    } else {
      caChatAppendBot("Hej! Jag hjälper dig hitta rätt bil — oavsett om det är bensin, diesel, hybrid eller elbil 🚗⚡ Välj ett ämne eller ställ en egen fråga!", false);
    }

    document.getElementById("ca-chat-fab").addEventListener("click", caChatToggle);
    document.getElementById("ca-chat-close").addEventListener("click", caChatToggle);
    document.getElementById("ca-chat-send").addEventListener("click", caChatSend);
    document.getElementById("ca-chat-clear").addEventListener("click", caChatClear);
    document.getElementById("ca-chat-input").addEventListener("keydown", function(e) { if (e.key === "Enter") caChatSend(); });
    document.querySelectorAll(".ca-chat-quick-btn").forEach(function(btn) {
      btn.addEventListener("click", function() { caChatSendMessage(btn.dataset.q); });
    });
  }

  function caChatToggle() {
    var panel = document.getElementById("ca-chat-panel");
    var open = panel.style.display === "none";
    panel.style.display = open ? "flex" : "none";
    if (open) document.getElementById("ca-chat-input").focus();
  }

  function caChatMarkdown(text) {
    return text
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
      .replace(/\*(.+?)\*/g, "<em>$1</em>")
      .replace(/^[-•]\s+(.+)$/gm, "<li>$1</li>")
      .replace(/^\d+\.\s+(.+)$/gm, "<li>$1</li>")
      .replace(/(<li>[\s\S]*<\/li>)/, "<ul>$1</ul>")
      .replace(/\n/g, "<br>");
  }

  function caChatAppendBot(text, animate) {
    var msgs = document.getElementById("ca-chat-messages");
    var outer = document.createElement("div");
    var bubble = document.createElement("div");
    bubble.className = "ca-chat-bubble bot";
    outer.appendChild(bubble);
    msgs.appendChild(outer);
    msgs.scrollTop = msgs.scrollHeight;
    if (animate !== false && text.length > 0) {
      var i = 0;
      var speed = Math.max(6, Math.min(20, 2600 / text.length));
      (function tick() {
        i += 3;
        if (i >= text.length) {
          bubble.innerHTML = caChatMarkdown(text);
          caAddFeedback(outer);
          msgs.scrollTop = msgs.scrollHeight;
        } else {
          bubble.textContent = text.slice(0, i);
          var cur = document.createElement("span"); cur.className = "ca-chat-cursor";
          bubble.appendChild(cur);
          msgs.scrollTop = msgs.scrollHeight;
          setTimeout(tick, speed);
        }
      })();
    } else {
      bubble.innerHTML = caChatMarkdown(text);
      if (animate !== false) caAddFeedback(outer);
    }
    return outer;
  }

  function caAddFeedback(outer) {
    var fb = document.createElement("div"); fb.className = "ca-chat-feedback";
    fb.innerHTML = '<button class="ca-chat-thumb">👍</button><button class="ca-chat-thumb">👎</button>';
    fb.querySelectorAll(".ca-chat-thumb").forEach(function(btn) {
      btn.addEventListener("click", function() {
        fb.querySelectorAll(".ca-chat-thumb").forEach(function(b) { b.classList.remove("voted"); });
        btn.classList.add("voted");
        setTimeout(function() { fb.innerHTML = '<span style="font-size:11px;color:rgba(196,181,253,0.45)">Tack!</span>'; }, 350);
      });
    });
    outer.appendChild(fb);
  }

  function caChatAppendUser(text) {
    var msgs = document.getElementById("ca-chat-messages");
    var div = document.createElement("div");
    div.innerHTML = '<div class="ca-chat-bubble user">' + text.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;") + '</div>';
    msgs.appendChild(div);
    msgs.scrollTop = msgs.scrollHeight;
  }

  function caChatClear() {
    caChatHistory = [];
    try { localStorage.removeItem("ca-chat"); } catch(e) {}
    var msgs = document.getElementById("ca-chat-messages");
    msgs.innerHTML = "";
    document.getElementById("ca-chat-quick").style.display = "flex";
    caChatAppendBot("Hej! Jag hjälper dig hitta rätt bil — oavsett om det är bensin, diesel, hybrid eller elbil 🚗⚡ Välj ett ämne eller ställ en egen fråga!", false);
  }

  function caChatSend() {
    var input = document.getElementById("ca-chat-input");
    var msg = input.value.trim();
    if (!msg) return;
    input.value = "";
    caChatSendMessage(msg);
  }

  function caAddFollowupChips(text, outer) {
    var lower = text.toLowerCase();
    var chips = [];
    if (/elbil|ev|electric/.test(lower))                 chips.push("Elbil eller laddhybrid?", "Räcker räckvidden?");
    else if (/begagnad|used/.test(lower))                chips.push("Vad ska jag kolla vid köp?", "Vilken år är bäst?");
    else if (/hybrid|phev/.test(lower))                  chips.push("Laddhybrid vs elhybrid?", "Hur mycket laddar jag?");
    else if (/bensin|diesel/.test(lower))                chips.push("Diesel eller bensin för pendling?", "Vad kostar det per mil?");
    if (/driftkostnad|försäkring|skatt/.test(lower) && chips.length < 2) chips.push("Vilken har lägst driftkostnad?");
    if (/pris|budget|kr/.test(lower) && chips.length < 2)               chips.push("Vad ger bäst värde för pengarna?");
    if (/suv|familj|barn/.test(lower) && chips.length < 2)              chips.push("Vilken SUV passar en familj?");
    if (chips.length === 0) chips = ["Elbil eller bensin — vad passar mig?", "Vilken bil är billigast att äga?"];
    var wrap = document.createElement("div");
    wrap.style.cssText = "display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;";
    chips.slice(0, 3).forEach(function(chip) {
      var btn = document.createElement("button");
      btn.textContent = chip;
      btn.style.cssText = "padding:5px 11px;font-size:.72rem;background:rgba(139,92,246,.12);border:1px solid rgba(167,139,250,.25);border-radius:16px;color:#c4b5fd;cursor:pointer;transition:background .15s;font-family:inherit;";
      btn.onmouseenter = function() { btn.style.background = "rgba(139,92,246,.22)"; };
      btn.onmouseleave = function() { btn.style.background = "rgba(139,92,246,.12)"; };
      btn.onclick = function() { wrap.remove(); document.getElementById("ca-chat-input").value = chip; caChatSend(); };
      wrap.appendChild(btn);
    });
    outer.appendChild(wrap);
  }

  async function caChatSendMessage(message) {
    document.getElementById("ca-chat-quick").style.display = "none";
    caChatAppendUser(message);
    caChatHistory.push({ role: "user", content: message });
    caSaveChatHistory();

    var mentionedCar = detectMentionedCar(message);
    var isAppearanceQ = /ser ut|utseende|bild|design|exteriör|looks|stilig|se ut|färg|form/i.test(message);
    if (mentionedCar) caChatSetCarImg(mentionedCar.title);

    var msgsEl = document.getElementById("ca-chat-messages");
    var typingDiv = document.createElement("div");
    typingDiv.innerHTML = '<div class="ca-chat-bubble bot"><div class="ca-chat-typing"><span></span><span></span><span></span></div></div>';
    msgsEl.appendChild(typingDiv);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    var context = buildCarContext(message);
    var limited = caChatHistory.slice(-10);

    var resp;
    try {
      resp = await fetch(CA_CHAT_API + "/api/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: limited, context: context })
      });
    } catch (_) {
      typingDiv.remove();
      var errDiv = caChatAppendBot("Kunde inte nå bilrådgivaren — kontrollera anslutningen.", false);
      var btn = document.createElement("button"); btn.className = "ca-chat-retry"; btn.textContent = "↺ Försök igen";
      btn.onclick = function() { errDiv.remove(); caChatHistory.pop(); caSaveChatHistory(); caChatSendMessage(message); };
      errDiv.appendChild(btn);
      return;
    }

    typingDiv.remove();

    if (resp.status === 429) {
      caChatAppendBot("Du har ställt för många frågor — vänta en minut och försök igen.", false);
      return;
    }
    if (!resp.ok) {
      var errDiv2 = caChatAppendBot("Något gick fel (fel " + resp.status + ").", false);
      var btn2 = document.createElement("button"); btn2.className = "ca-chat-retry"; btn2.textContent = "↺ Försök igen";
      btn2.onclick = function() { errDiv2.remove(); caChatHistory.pop(); caSaveChatHistory(); caChatSendMessage(message); };
      errDiv2.appendChild(btn2);
      return;
    }

    // Fallback for browsers without streaming support
    if (!resp.body || typeof resp.body.getReader !== "function") {
      try {
        var fb = await fetch(CA_CHAT_API + "/api/chat", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ messages: limited, context: context })
        });
        var fbData = await fb.json();
        var fbReply = fbData.reply || fbData.error || "Inget svar.";
        caChatHistory.push({ role: "assistant", content: fbReply });
        caSaveChatHistory();
        var fbOuter = caChatAppendBot(fbReply, true);
        caAddFollowupChips(fbReply, fbOuter);
      } catch (_) { caChatAppendBot("Kunde inte nå bilrådgivaren.", false); }
      return;
    }

    // Streaming bubble
    var outer = document.createElement("div");
    var bubble = document.createElement("div");
    bubble.className = "ca-chat-bubble bot";
    outer.appendChild(bubble);
    msgsEl.appendChild(outer);

    var fullText = "";
    var reader = resp.body.getReader();
    var decoder = new TextDecoder();
    var buf = "";

    try {
      while (true) {
        var chunk = await reader.read();
        if (chunk.done) break;
        buf += decoder.decode(chunk.value, { stream: true });
        var lines = buf.split("\n");
        buf = lines.pop();
        for (var i = 0; i < lines.length; i++) {
          var trimmed = lines[i].trim();
          if (!trimmed.startsWith("data:")) continue;
          var data = trimmed.slice(5).trim();
          if (data === "[DONE]") break;
          try {
            var token = JSON.parse(data);
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
        var errDiv3 = caChatAppendBot(streamErr.message || "Kunde inte nå bilrådgivaren.", false);
        var btn3 = document.createElement("button"); btn3.className = "ca-chat-retry"; btn3.textContent = "↺ Försök igen";
        btn3.onclick = function() { errDiv3.remove(); caChatHistory.pop(); caSaveChatHistory(); caChatSendMessage(message); };
        errDiv3.appendChild(btn3);
        return;
      }
    }

    bubble.innerHTML = caChatMarkdown(fullText);

    if (isAppearanceQ && mentionedCar && typeof window.caFetchOneImage === 'function') {
      var ts = Date.now();
      var iwId = 'ca-mi-' + ts, iId = 'ca-mii-' + ts;
      var imgWrap = document.createElement('div');
      imgWrap.id = iwId;
      imgWrap.style.cssText = 'display:none;margin-top:10px;height:90px;border-radius:8px;overflow:hidden;background:rgba(255,255,255,.04)';
      imgWrap.innerHTML = '<img id="' + iId + '" src="" alt="' + mentionedCar.title.replace(/"/g,'') + '" style="width:100%;height:100%;object-fit:contain">';
      bubble.appendChild(imgWrap);
      window.caFetchOneImage(mentionedCar.title, iwId, iId);
    }

    caAddFeedback(outer);
    caAddFollowupChips(fullText, outer);
    msgsEl.scrollTop = msgsEl.scrollHeight;

    caChatHistory.push({ role: "assistant", content: fullText });
    caSaveChatHistory();
  }

  window.caChatSetRecsContext = caChatSetRecsContext;
  initCaChat();
})();
