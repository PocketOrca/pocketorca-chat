/* PocketOrca-Chat UI (M3). Java bridge: window.Android.* ; SSE push: window.__sseEvent
 * Views: contacts list <-> chat (single contact active at a time). */
"use strict";
var $ = function (id) { return document.getElementById(id); };
var state = {
  contacts: [],        // [{id,name,url,token}]
  currentId: null,     // active contact id (chat view)
  sessionId: null,     // chat session per contact (reset on contact switch)
  runId: "",
  busy: false,
  events: [],
  pendingApprovals: [],
  turnCid: null,       // vc41: contact that owns the active turn (status line must not leak across contacts)
  editingId: null,     // contact being edited in the sheet (null = adding)
  menuCid: null,       // vc15: contact targeted by the long-press menu
  confirmCb: null      // vc15: pending in-page confirm callback
};

/* ---------- helpers ---------- */
function toast(msg) {
  var t = $("toast"); t.textContent = msg; t.style.display = "block";
  clearTimeout(t._h); t._h = setTimeout(function () { t.style.display = "none"; }, 2600);
}
function setDot(cls) { $("connDot").className = "dot " + (cls || ""); }
function cur() {
  for (var i = 0; i < state.contacts.length; i++)
    if (state.contacts[i].id === state.currentId) return state.contacts[i];
  return null;
}
function curName() { var c = cur(); return c ? (c.name || c.url) : "PocketOrca-Chat"; }
/* vc15: long-press menu / confirm dialog helpers */
function byId(id) {
  for (var i = 0; i < state.contacts.length; i++)
    if (state.contacts[i].id === id) return state.contacts[i];
  return null;
}
function openSysSheet() {
  var s = "";
  try { s = Android.dataStats() || ""; } catch (e) { s = "读取失败: " + e; }
  var r = s ? safeParse(s) : null;
  renderPollOpts();   // vc29: sync poll-interval highlight + label
  if (!r || !r.ok) {
    $("sysStat").textContent = "读取失败: " + (r && r.error ? r.error : s);
  } else {
    $("sysStat").textContent =
      "数据库: " + r.dbPath + "\n" +
      "schema v" + r.dbVersion + " / " + (r.wal ? "WAL 开" : "WAL 关") + "\n" +
      "联系人: " + r.contacts + " 个\n" +
      "消息: " + r.msgs + " 条 / 会话指针 " + r.sessions + " 个\n" +
      "占用: " + r.dbKB + " KB";
  }
  // vc26: version info (BuildConfig via bridge) + current font size
  var ver = "";
  try { ver = Android.appVersion() || ""; } catch (e) { ver = ""; }
  $("sysVer").textContent = "PocketOrca-Chat " + (ver || "（版本未知）");
  renderFontSize();
  $("sysMask").classList.add("show");
}

/* ---------- vc26: font size (persisted, applied to bubbles+body) ---------- */
var FS_KEY = "pofont", FS_MIN = 12, FS_MAX = 22, FS_DEF = 15;
function fontSize() {
  try { var n = parseInt(localStorage.getItem(FS_KEY), 10); if (n >= FS_MIN && n <= FS_MAX) return n; } catch (e) {}
  return FS_DEF;
}
function applyFontSize(px) {
  px = Math.max(FS_MIN, Math.min(FS_MAX, px));
  try { localStorage.setItem(FS_KEY, String(px)); } catch (e) {}
  document.documentElement.style.setProperty("--fs", px + "px");
  document.documentElement.style.setProperty("--fs-dim", Math.max(10, px - 3) + "px");
  renderFontSize();
}
function renderFontSize() { var el = $("fsLabel"); if (el) el.textContent = String(fontSize()); }

/* ---------- vc29: poll interval (settings sheet; truth = Java prefs, UI mirrors via localStorage) ---------- */
var POLL_KEY = "popoll", POLL_DEF_MS = 60000;
var POLL_CHOICES = [30000, 60000, 180000, 300000];
function pollIntervalMs() {
  try {
    var n = parseInt(localStorage.getItem(POLL_KEY), 10);
    if (POLL_CHOICES.indexOf(n) >= 0) return n;
  } catch (e) {}
  return POLL_DEF_MS;
}
function renderPollOpts() {
  var cur = pollIntervalMs();
  var label = $("pollLabel");
  if (label) label.textContent = String(Math.round(cur / 1000));
  var btns = document.querySelectorAll(".pollOpt");
  for (var i = 0; i < btns.length; i++) {
    var ms = parseInt(btns[i].getAttribute("data-ms"), 10);
    btns[i].style.opacity = (ms === cur) ? "1" : "0.45";
    btns[i].style.fontWeight = (ms === cur) ? "700" : "400";
  }
}
function applyPollInterval(ms) {
  if (POLL_CHOICES.indexOf(ms) < 0) return;
  try { localStorage.setItem(POLL_KEY, String(ms)); } catch (e) {}
  try { Android.setPollInterval(String(ms)); } catch (e) { toast("设置失败: " + e); }
  renderPollOpts();
}

/* ---------- boot ---------- */
window.addEventListener("load", function () {
  applyFontSize(fontSize());   // vc26: restore saved font size
  renderPollOpts();            // vc29: restore poll interval highlight
  bind();
  reloadContacts(function () {
    if (!state.contacts.length) { showContacts(); toast("先添加你的第一个 agent"); openContactSheet(null); }
    else openContact(state.contacts[0].id);
  });
});

function reloadContacts(then) {
  var arr;
  try { arr = JSON.parse(Android.listContacts() || "[]"); } catch (e) { arr = []; }
  state.contacts = arr || [];
  if (!state.currentId && state.contacts.length) state.currentId = state.contacts[0].id;
  renderContactList();
  if (then) then();
}

/* ---------- view switching ---------- */
function showContacts() {
  setToolLine("");
  $("chatView").style.display = "none";
  $("contactsView").style.display = "flex";   // explicit (vc8 fix discipline)
  renderContactList();
  probeAllContacts();                                // vc27: background health dots
}
function showChat() {
  $("contactsView").style.display = "none";
  // vc8 fix: MUST set an explicit value. display="" only clears the inline
  // style, falling back to CSS #chatView{display:none} — both views ended up
  // hidden (black screen). vc1..7 shipped this; first exposed on device in vc7.
  $("chatView").style.display = "flex";
  renderTitle();
}
function openContact(id) {
  // vc25: 随时都能切进去。run 在 Java/服务端侧继续，busy 状态保留在 turnState，
  // 切回时 turnView() 重放终态。仅换联系人时允许改 currentId，同一联系人重复点入直接重放。
  if (id !== state.currentId) {
    turnState.save();
    state.currentId = id; state.runId = "";
  } $("view").innerHTML = "";
  botEl = null;
  showChat();
  __vc8AssertChatView();
  doHealth(true);
  // vc6: WeChat-style persistence — durable session id + local history replay.
  if (!state.sessionId) {
    var sid = "";
    try { sid = Android.chatSessionGet(state.currentId) || ""; } catch (e) { sid = ""; }
    if (sid) state.sessionId = sid;   // reuse; server 404 on next send self-heals below
  }
  replayHistory(state.currentId);
  _modelCatalog = loadModelCatalog(state.currentId);   // vc33: picker list ready before first fetch
  renderCtx(loadCtx(state.currentId));   // vc22: last known context occupancy
  turnState.restore();                   // vc25: 回合进行中切回 → 重放状态
}
/* vc6: replay local transcript from ChatDB (user/bot text lines only). */
function replayHistory(cid) {
  if (!cid) return;
  hideHistBanner();
  var arr;
  try { arr = JSON.parse(Android.chatRecent(cid, "200") || "[]"); } catch (e) { arr = []; }
  if (!arr || !arr.length) { tryRestore(cid); return; }   // vc7 F2: empty local → pull from server
  renderRows(arr);
  scrollBottom();
}
/* vc22: render DB rows → bubbles; keeps data-seq so search hits can be located. */
function renderRows(arr) {
  for (var i = 0; i < arr.length; i++) {
    var m = arr[i], el = null;
    if (m.role === "user") el = userMsg(m.text);
    else if (m.role === "bot") {
      // vc21: bot bubbles may carry data-URL images (server inlines MEDIA: tags)
      el = bubble("bot", "");
      renderBotContent(el, m.text);
    }
    else if (m.role === "sys") {
      // vc22: relay quotes start with "来自 …" — everything else is a plain system line
      el = /^来自 /.test(m.text) ? relayReplay(m.text) : sysMsg(m.text);
    }
    if (el && m.seq != null && el.setAttribute) el.setAttribute("data-seq", String(m.seq));
  }
}
/* vc7 F2: reinstall recovery — pull server-side transcript once into local DB. */
function tryRestore(cid) {
  var sid = state.sessionId;
  if (!sid) { try { sid = Android.chatSessionGet(cid) || ""; } catch (e) { sid = ""; } }
  if (!sid) return;                       // no server thread ever existed
  var fetched;
  try { fetched = Android.chatSessionWasFetched(cid) === "1"; } catch (e) { fetched = true; }
  if (fetched) return;                    // one-shot per contact
  setTimeout(function () {
    var r;
    try { r = JSON.parse(Android.getMessages(cid, sid) || "{}"); } catch (e) { r = {}; }
    var body = r && typeof r.body === "string" ? safeParse(r.body) : (r.body || null);
    if (r.http !== 200 || !body || !body.data || !body.data.length) return;   // silent give-up
    var rows = body.data, wrote = 0;
    for (var i = 0; i < rows.length; i++) {
      var m = rows[i];
      if (!m || (m.role !== "user" && m.role !== "assistant")) continue;
      var t = typeof m.content === "string" ? m.content.trim() : "";
      if (!t) continue;
      try { Android.chatInsert(cid, m.role === "user" ? "user" : "bot", t); wrote++; } catch (e) {}
    }
    try { Android.chatSessionMarkFetched(cid); } catch (e) {}
    if (wrote) { sysMsg("已从服务端恢复 " + wrote + " 条历史"); replayHistory(cid); }
  }, 300);   // after first paint; silent background
}
function safeParse(s) { try { return JSON.parse(s); } catch (e) { return null; } }
function renderTitle() { $("title").textContent = curName(); }

/* ---------- vc25: turn-state snapshot ----------
 * 允许回合进行中切走再切回：离开时把运行期 DOM 状态（状态行文字/工具行/
 * 是否有活动 bot 气泡）存进 turnState，切回时重放；run 本体在 Java/服务端
 * 继续，事件照常由 handleEvent 消费（assistant.delta 会重新 startBotBubble）。 */
var turnState = { status: "", tool: "", wasStreaming: false, cid: null };
turnState.save = function () {
  this.status = ""; this.tool = ""; this.wasStreaming = !!botEl; this.cid = state.turnCid;
  var st = document.querySelector(".statusline");
  if (st) this.status = st.textContent;
  var tl = $("toolLine");
  if (tl && tl.classList.contains("show")) this.tool = tl.textContent;
};
turnState.restore = function () {
  // vc41: the snapshotted turn belongs to a specific contact — replaying A's
  // "对方正在回复中…" into B's view was the cross-contact status leak.
  if (!state.busy || this.cid !== state.currentId || state.turnCid !== this.cid) return;
  if (this.status) setStatus(this.status);
  if (this.tool) setToolLine(this.tool);
  this.status = ""; this.tool = ""; this.wasStreaming = false;
};

/* ---------- contacts list ---------- */
function renderContactList() {
  var box = $("contactList"); box.innerHTML = "";
  for (var i = 0; i < state.contacts.length; i++) {
    var c = state.contacts[i];
    (function (c) {
      var row = document.createElement("div");
      row.className = "crow" + (c.id === state.currentId ? " active" : "");
      var dot = document.createElement("span"); dot.className = "cdot"; dot.id = "cdot-" + c.id;
      var main = document.createElement("div"); main.className = "cmain";
      var nm = document.createElement("div"); nm.className = "cname"; nm.textContent = c.name || c.url;
      var sub = document.createElement("div"); sub.className = "curl"; sub.textContent = c.url.replace(/^https?:\/\//, "");
      main.appendChild(nm); main.appendChild(sub);
      row.appendChild(dot); row.appendChild(main);
      bindLongPress(row, c);
      box.appendChild(row);
    })(c);
  }
  if (!state.contacts.length) {
    var empty = document.createElement("div"); empty.className = "empty";
    empty.textContent = "还没有联系人。点右上角「+」添加 agent 端点。";
    box.appendChild(empty);
  }
}
/* vc15: long-press = edit / delete. Inline row buttons were removed (hit area
 * too small on device); tapping the row still opens the chat. */
function bindLongPress(row, c) {
  var timer = null, fired = false;
  function start() {
    fired = false;
    clearTimeout(timer);
    timer = setTimeout(function () {
      fired = true;
      try { if (navigator.vibrate) navigator.vibrate(18); } catch (e) {}
      openContactMenu(c);
    }, 450);
  }
  function stop() { clearTimeout(timer); }
  row.addEventListener("touchstart", start, { passive: true });
  row.addEventListener("touchmove", stop, { passive: true });
  row.addEventListener("touchend", stop);
  row.addEventListener("touchcancel", stop);
  row.addEventListener("mousedown", start);
  row.addEventListener("mouseup", stop);
  row.addEventListener("mouseleave", stop);
  row.addEventListener("contextmenu", function (e) { e.preventDefault(); openContactMenu(c); });
  row.addEventListener("click", function (e) {
    e.preventDefault();
    if (fired) { fired = false; return; }   // long-press已经弹菜单，别顺带进聊天
    openContact(c.id);
  });
}
function openContactMenu(c) {
  state.menuCid = c.id;
  $("actTitle").textContent = c.name || c.url;
  $("actSub").textContent = c.url;
  $("actMask").classList.add("show");
}
function closeActMenu() { $("actMask").classList.remove("show"); }
/* vc15: WebView 没挂 WebChromeClient 时 window.confirm() 恒返回 false（vc15 前
 * 删除联系人点了没反应的根因）。全部确认走页面内弹层，不依赖原生对话框。 */
function askConfirm(title, desc, onYes) {
  $("cfTitle").textContent = title;
  $("cfDesc").textContent = desc || "";
  state.confirmCb = onYes;
  $("cfMask").classList.add("show");
}
function closeConfirm() { state.confirmCb = null; $("cfMask").classList.remove("show"); }
function delContact(c) {
  var wasChat = $("chatView").style.display === "flex";
  var r;
  try { r = JSON.parse(Android.removeContact(c.id) || "{}"); }
  catch (e) { r = { ok: false, error: String(e) }; }
  if (!r.ok) { toast("删除失败: " + (r.error || "")); return; }
  try { Android.chatClear(c.id); } catch (e) {}
  try { Android.chatSessionClear(c.id); } catch (e) {}   // vc6: WeChat-style, records die with the contact
  if (state.currentId === c.id) {
    state.sessionId = null;
    state.currentId = null;
  }
  toast("已删除");
  reloadContacts(function () {
    if (wasChat && state.currentId) openContact(state.currentId);
    else showContacts();
  });
}

/* ---------- contact sheet (add/edit) ---------- */
function openContactSheet(c) {
  state.editingId = c ? c.id : null;
  $("sheetTitle").textContent = c ? "编辑联系人" : "添加联系人";
  $("ctName").value = c ? (c.name || "") : "";
  // vc25: 地址/端口分离编辑；存储仍是完整 url（兼容旧联系人）
  var u = c ? (c.url || "") : "";
  var mh = u.match(/^(https?:)\/\/([^/:]+)(?::(\d+))?/i);
  $("ctHost").value = mh ? mh[2] : (u && !mh ? u.replace(/^https?:\/\//i, "").replace(/:\d+$/, "").replace(/\/+$/, "") : "");
  $("ctPort").value = mh && mh[3] ? mh[3] : (u ? "" : "8642");
  $("ctToken").value = c ? c.token : "";
  // vc40: WAN (Tailscale) address — optional second address with failover
  var wu = c ? (c.wanUrl || "") : "";
  var wm = wu.match(/^(https?:)\/\/([^/:]+)(?::(\d+))?/i);
  var hasWan = !!wu;
  $("ctWanToggle").checked = hasWan;
  $("ctWanBlock").style.display = hasWan ? "block" : "none";
  $("ctWanHost").value = wm ? wm[2] : (wu && !wm ? wu.replace(/^https?:\/\//i, "").replace(/:\d+$/, "").replace(/\/+$/, "") : "");
  $("ctWanPort").value = wm && wm[3] ? wm[3] : "";
  $("ctMask").classList.add("show");
}
function closeContactSheet() { $("ctMask").classList.remove("show"); }
function saveContact() {
  // vc25: 地址/端口分离 → 拼回完整 url；协议省略默认 http://
  var host = $("ctHost").value.trim().replace(/\/+$/, "");
  var port = $("ctPort").value.trim();
  var url = "";
  if (host) {
    var scheme = /^https?:\/\//i.test(host) ? "" : "http://";
    url = scheme + host + (port ? ":" + port : "");
  }
  // vc40: WAN address (only when the toggle is on)
  var wanUrl = "";
  if ($("ctWanToggle").checked) {
    var whost = $("ctWanHost").value.trim().replace(/\/+$/, "");
    if (whost) {
      var wscheme = /^https?:\/\//i.test(whost) ? "" : "http://";
      wanUrl = wscheme + whost + ($("ctWanPort").value.trim() ? ":" + $("ctWanPort").value.trim() : "");
    }
  }
  var body = JSON.stringify({
    name: $("ctName").value.trim(),
    url: url,
    wanUrl: wanUrl,
    token: $("ctToken").value.trim()
  });
  var r;
  try {
    r = state.editingId
      ? JSON.parse(Android.updateContact(state.editingId, body) || "{}")
      : JSON.parse(Android.addContact(body) || "{}");
  } catch (e) { r = { ok: false, error: String(e) }; }
  if (!r.ok) { toast("保存失败: " + (r.error || "")); return; }
  closeContactSheet();
  toast("已保存");
  reloadContacts(function () {
    var id = r.id || state.editingId || (state.contacts[0] && state.contacts[0].id);
    if (id) { state.currentId = id; state.sessionId = null; $("view").innerHTML = ""; botEl = null; showChat(); __vc8AssertChatView(); doHealth(true); }
  });
}

/* ---------- chat events ---------- */
function bind() {
  $("btnBack").onclick = showContacts;
  // vc34: btnModel removed — /model command is the only model control
  $("btnAdd").onclick = function () { openContactSheet(null); };
  $("btnCtCancel").onclick = closeContactSheet;
  $("ctSave").onclick = saveContact;
  $("ctWanToggle").onchange = function () {   // vc40: show/hide WAN address fields
    $("ctWanBlock").style.display = $("ctWanToggle").checked ? "block" : "none";
  };
  // vc15: long-press action sheet + in-page confirm
  $("actCancel").onclick = closeActMenu;
  $("actMask").onclick = function (e) { if (e.target === $("actMask")) closeActMenu(); };
  $("actEdit").onclick = function () {
    var c = byId(state.menuCid); closeActMenu();
    if (c) openContactSheet(c);
  };
  $("actDel").onclick = function () {
    var c = byId(state.menuCid); closeActMenu();
    if (!c) return;
    askConfirm("删除联系人", "「" + (c.name || c.url) + "」及其本地聊天记录将一并清除，不可恢复。", function () { delContact(c); });
  };
  $("actClear").onclick = function () {
    var c = byId(state.menuCid); closeActMenu();
    if (!c) return;
    askConfirm("清空聊天记录", "只清本地聊天记录，服务端会话和联系人保留。确认？", function () {
      try { Android.chatClear(c.id); } catch (e) {}
      if (state.currentId === c.id) { $("view").innerHTML = ""; botEl = null; }
      toast("已清空聊天记录");
    });
  };
  $("cfNo").onclick = closeConfirm;
  $("cfYes").onclick = function () {
    var cb = state.confirmCb;
    closeConfirm();
    if (cb) { try { cb(); } catch (e) { toast("操作失败: " + e); } }
  };
  $("cfMask").onclick = function (e) { if (e.target === $("cfMask")) closeConfirm(); };
  // vc15: local data panel → vc26: settings sheet
  $("btnSys").onclick = function () { openSysSheet(); };
  $("sysClose").onclick = function () { $("sysMask").classList.remove("show"); };
  $("sysMask").onclick = function (e) { if (e.target === $("sysMask")) $("sysMask").classList.remove("show"); };
  // vc26: font size controls
  $("fsMinus").onclick = function () { applyFontSize(fontSize() - 1); };
  $("fsPlus").onclick = function () { applyFontSize(fontSize() + 1); };
  $("fsReset").onclick = function () { applyFontSize(FS_DEF); };
  // vc29: poll interval options
  var pollBtns = document.querySelectorAll(".pollOpt");
  for (var pi = 0; pi < pollBtns.length; pi++) {
    pollBtns[pi].onclick = function () {
      applyPollInterval(parseInt(this.getAttribute("data-ms"), 10));
    };
  }
  $("sysWipe").onclick = function () {
    askConfirm("清空全部本地数据", "联系人、聊天记录、会话指针全部删除（服务端会话不受影响）。确认？", function () {
      var raw = "{}";
      try { raw = Android.wipeAll() || "{}"; } catch (e) {}
      var r = safeParse(raw) || {};
      $("sysMask").classList.remove("show");
      toast(r.ok ? "已清空本地数据" : ("清空失败: " + (r.error || "")));
      state.currentId = null; state.sessionId = null; state.runId = "";
      $("view").innerHTML = ""; botEl = null;
      reloadContacts(function () { showContacts(); });
    });
  };
  $("btnHealth").onclick = function () { doHealth(false); };
  $("btnEditCur").onclick = function () { var c = cur(); if (c) openContactSheet(c); };
  // vc22: history search (contacts header 🔍) + jump banner
  $("btnSearch").onclick = function () { openSearch(""); };
  $("searchClose").onclick = closeSearch;
  $("searchMask").onclick = function (e) { if (e.target === $("searchMask")) closeSearch(); };
  $("searchInput").addEventListener("input", function () { searchDebounced(); });
  $("searchInput").addEventListener("keydown", function (e) { if (e.key === "Enter") searchRender(); });
  $("histBack").onclick = function () { replayHistory(state.currentId); };
  $("input").addEventListener("keydown", function (e) {   // vc34: textarea — Enter sends, Shift+Enter newlines
    // vc36: with a Chinese IME, Enter commits the composition — never send from
    // that state (it would ship half-composed text and the IME keeps the field).
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  });
  $("input").addEventListener("input", function () {      // vc34: auto-grow textarea
    var t = $("input");
    t.style.height = "auto";
    t.style.height = Math.min(t.scrollHeight, window.innerHeight * 0.4) + "px";
  });
  // vc9 fix: NEVER assign send directly — onclick passes the PointerEvent as
  // retryText, which is truthy, so the event object became the message
  // ("[object PointerEvent]" sent to the agent). Wrap with zero args.
  $("btnSend").onclick = function () { send(); };
  $("btnStop").onclick = function () {
    if (!state.runId) return;
    var r;
    try { r = JSON.parse(Android.stopRun(state.currentId, state.runId) || "{}"); }
    catch (e) { r = { http: 0 }; }
    toast(r.http === 200 ? "已请求停止" : "停止失败 http=" + r.http);
  };
}
function __vc8AssertChatView() {
  var cw = $("chatView"), cv = $("contactsView");
  var visible = cw && cw.style.display === "flex";
  if (!visible || (cv && cv.style.display !== "none"))
    sysMsg("⚠ 视图状态异常 chat=" + (cw ? cw.style.display : "?") +
           " contacts=" + (cv ? cv.style.display : "?"));
}
window.__onBack = function () {
  // vc34: model picker sheet removed — /model command is the only model control
  if ($("ctMask").classList.contains("show")) { closeContactSheet(); return; }
  if ($("chatView").style.display !== "none") showContacts();
};

/* SSE events pushed from Java (async path) */
window.__sseEvent = function (json) {
  var ev; try { ev = JSON.parse(json); } catch (e) { return; }
  if (ev.event === "health") {                       // vc27: background probe result
    var h = ev.payload || {};
    var cls = applyHealth(h.cid, h.result);
    if (h.cid === state.currentId && $("chatView").style.display === "flex") {
      if (cls === "err" && h.result && h.result.http === 401) toast("密钥错误，请重新粘贴");
      else if (cls === "err") toast("测活失败 http=" + (h.result && h.result.http) + " " + ((h.result && h.result.error) || ""));
    }
    return;                                          // never touches turn state
  }
  handleEvent(ev.event, ev.payload || {});
};

/* vc40: contact display name by id (foreign-event toast). */
function contactName(cid) {
  for (var i = 0; i < state.contacts.length; i++)
    if (state.contacts[i].id === cid) return state.contacts[i].name || "联系人";
  return "联系人";
}

function handleEvent(name, p) {
  // vc40: route by ORIGIN contact. Java stamps payload.cid on every SSE event;
  // if it belongs to a background turn on another contact, do NOT touch the
  // on-screen turn state — only persist (bot bubble lands in the right DB row)
  // and refresh model/health bookkeeping for THAT contact.
  var originCid = p.cid || state.currentId;
  var foreign = originCid !== state.currentId;
  if (foreign && name === "assistant.completed") {
    var bgDone = p.content || "";
    if (bgDone) { try { Android.chatInsert(originCid, "bot", bgDone); } catch (e) {} }
    if (p.runtime && p.runtime.model) saveModel(originCid, p.runtime.model);
    markAlive(originCid);
    toast("「" + contactName(originCid) + "」有新回复");
    return;   // never touch botEl/runId/busy — that's the on-screen contact's turn
  }
  if (foreign && (name === "run.completed" || name === "run.failed" || name === "run.cancelled" || name === "run.interrupted")) {
    if (p.usage) captureUsage(p.usage);
    if (p.runtime && p.runtime.model) saveModel(originCid, p.runtime.model);
    return;   // background turn finished; on-screen turn state untouched
  }
  if (foreign && name !== "approval.request" && name !== "approval.responded"
      && name !== "relay.delta" && name !== "relay.status" && name !== "relay.approval"
      && name !== "relay.error" && name !== "relay.done" && name !== "chat.done") {
    return;   // any other foreign event: ignore (status lines belong to the visible turn)
  }
  state.events.push({ event: name, payload: p });
  // vc25: 回合进行中切到联系人页 → 不往已清空的 #view 里塞气泡/状态行；
  // turnDone 照常推进，切回时靠 replayHistory 看到 assistant.completed 已落库的回复。
  var offView = $("chatView").style.display !== "flex";
  if (name === "run.started") {
    state.runId = p.run_id || "";
    if (state.turnCid === state.currentId) { setStatus("对方正在回复中…"); showSteer(); runActivity("正在处理…"); }
  }
  // vc26: 非流式整条收 —— message.started / assistant.delta 只推进状态行，
  // 正文等 assistant.completed 终态一次性渲染（Tailscale 下 SSE 逐 token 卡顿，
  // 且微信式 IM 习惯整条收）。收信链路不变（SSE 仍承载审批/工具/终态事件）。
  else if (name === "message.started") { if (state.turnCid === state.currentId) setStatus("对方正在回复中…"); }
  else if (name === "assistant.delta") { /* vc26: dropped — final bubble only */ }
  else if (name === "assistant.commentary") { if (!offView) setToolLine("· " + (p.text || "")); }
  else if (name === "tool.started") {
    var tn = p.tool_name || p.tool || "tool";
    if (!offView && state.turnCid === state.currentId) { setToolLine("🔧 " + tn + " …"); runActivity("正在使用工具 " + tn); }
  }
  else if (name === "tool.completed") { if (!offView) setToolLine("✅ " + (p.tool_name || p.tool || "tool")); }
  else if (name === "tool.failed") { if (!offView) setToolLine("❌ " + (p.tool_name || p.tool || "tool") + " 失败"); }
  else if (name === "approval.request") { showApproval(p); }
  else if (name === "approval.responded") { resolveApprovalCards(p); }
  // vc11: delegation relay events (dedicated relay.* channel from Java RelaySink;
  // never touch main-turn state — botEl/runId/busy belong to the main chat only)
  else if (name === "relay.delta") { /* vc26: relay 收整条，delta 只缓冲不渲染 */ relayBufDelta(p.delta || ""); }
  else if (name === "relay.status") { relayStatus(p); }
  else if (name === "relay.approval") { showApproval(p); }
  else if (name === "relay.error") { relayFail(p.message || p.error || "转发失败"); }
  else if (name === "relay.done") { relayFinish(p); }
  else if (name === "assistant.completed") {
    // vc26: 整条上屏 —— 终态一次性建气泡渲染（不再有流式中间态气泡）
    var done = p.content || "";
    if (done && !offView) {
      var el = bubble("bot", "");
      renderBotContent(el, done);
    }
    if (p.runtime && p.runtime.model) saveModel(state.currentId, p.runtime.model);   // vc22
    // vc38: vc37 的 p.slash（服务端斜杠回执）分支已撤 —— 命令回到客户端解析，
    // 服务端不再回 slash.action，模型标签由上面的 runtime.model 同步。
    if (done) { try { Android.chatInsert(state.currentId, "bot", done); } catch (e) {} }   // vc6: local transcript
    markAlive(state.currentId);                                       // vc32: completed turn → endpoint alive
    botEl = null;
    runActivity(null);
    setStatus(""); setToolLine("");
  }
  else if (name === "run.completed" || name === "run.failed" || name === "run.cancelled" || name === "run.interrupted") {
    if (p.run_id) state.runId = p.run_id;
    if (p.usage) captureUsage(p.usage);                                        // vc22: context meter
    if (p.runtime && p.runtime.model) saveModel(state.currentId, p.runtime.model);
    if (name !== "run.failed") markAlive(state.currentId);                     // vc32: server reachable
    if (p.error) sysMsg("错误: " + p.error);
    finishTurn(name.replace("run.", ""));
  }
  else if (name === "error") {
    if (p.http === 401) { keyError(); }
    else if (p.http === 404 && state.sessionId && !state.selfHeal) { healSession(); }
    else { sysMsg("SSE 错误: " + (p.message || p.error || (p.http ? "http " + p.http : "unknown"))); finishTurn("error"); }
  }
  else if (name === "chat.done") { /* async wrap: already handled by inner events */ }
}

/* vc7 F1: server lost the session — drop pointer, rebuild, resend once (no recursion). */
function healSession() {
  var cid = state.currentId, retry = state.pendingText;
  state.selfHeal = true;
  try { Android.chatSessionClear(cid); } catch (e) {}
  state.sessionId = null;
  if (!retry) { sysMsg("会话已失效，已重建；请重发"); finishTurn("healed"); return; }
  sysMsg("会话已失效，正在重建…");
  state.busy = false;
  send(retry);   // one-shot retry with the original text
}

/* ---------- send ---------- */
function resetInput() {   // vc36: top-level (vc35 had it nested inside bind() → send() threw ReferenceError)
  var t = $("input");
  if (!t) return;
  var hadFocus = document.activeElement === t;
  // Android WebView + Chinese IME: setting .value while a composition is still
  // active gets re-committed by the IME, so the text "comes back". Blur first to
  // end the composition, then clear, then restore focus so typing can continue.
  if (hadFocus) { try { t.blur(); } catch (e) {} }
  t.value = "";
  t.style.height = "";
  requestAnimationFrame(function () {
    if (t.value) { t.value = ""; t.style.height = ""; }   // late IME commit guard
    if (hadFocus) { try { t.focus(); } catch (e) {} }
  });
}

function send(retryText) {
  // vc22: slash commands are handled on the phone — api_server has no slash dispatch,
  // a leading "/" would otherwise reach the agent as plain text.
  if (retryText === undefined && handleCommand($("input").value)) { resetInput(); return; }
  // vc41: busy is per-contact — block only if THIS contact already has a running
  // turn. Another contact's background turn must not lock the composer.
  if (state.busy && state.turnCid === state.currentId) return;
  // vc9 fix: self-heal retry is the ONLY legit argument path, and it must be
  // a non-empty string. onclick historically leaked a PointerEvent here.
  if (retryText !== undefined && (typeof retryText !== "string" || !retryText)) return;
  var text = retryText || $("input").value.trim();
  if (!text) return;
  if (!state.currentId) { toast("先添加联系人"); return; }
  resetInput();   // vc36: clear right after capture — a slow/failed session-create must not leave the text behind
  state.pendingText = retryText ? "" : text;   // vc7 F1: remember for one-shot self-heal
  state.selfHeal = !!retryText;
  if (!state.sessionId) {
    setStatus("建会话…");
    var cr;
    try { cr = JSON.parse(Android.createSession(state.currentId, "") || "{}"); } catch (e) { cr = {}; }
    if (typeof cr.body === "string") { try { cr = JSON.parse(cr.body); } catch (e) {} }
    else if (cr.body && typeof cr.body === "object") { cr = cr.body; }
    var sid = (cr.session && cr.session.id) || cr.id || cr.session_id || "";
    if (!sid) {
      state.turnDone = true;
      if (cr.http === 401) { keyError(); return; }
      sysMsg("建会话失败: " + (cr.error || (cr.http ? "http " + cr.http : "无响应")));
      return;
    }
    state.sessionId = sid;
    try { Android.chatSessionPut(state.currentId, sid); } catch (e) {}   // vc6: durable
    markAlive(state.currentId);                                          // vc32: session created → endpoint alive
  }
  userMsg(text);
  try { Android.chatInsert(state.currentId, "user", text); } catch (e) {}   // vc6: local transcript
  state.busy = true; state.events = []; state.turnDone = false;
  state.turnCid = state.currentId;   // vc41: this turn belongs to this contact
  $("btnSend").disabled = true; $("btnStop").style.display = "block";   // explicit (vc8 fix discipline)
  setStatus("连接…");

  var origHandler = window.__sseEvent;
  var turnCidAtStart = state.currentId;   // vc41: concurrent turns — guard stale chat.done
  window.__sseEvent = function (json) {
    var ev; try { ev = JSON.parse(json); } catch (e) { return; }
    handleEvent(ev.event, ev.payload || {});
    if (ev.event === "chat.done") {
      // vc41: chat.done now carries the origin cid (AsyncTurn stamps it). A
      // chat.done from ANOTHER contact's turn must not clear this turn's state
      // or restore an older handler chain.
      if ((ev.payload || {}).cid && ev.payload.cid !== turnCidAtStart) return;
      window.__sseEvent = origHandler;
      var st = (ev.payload || {}).status || "";
      if (!state.turnDone) finishTurn(st || "done");
    }
  };
  Android.chatStreamAsync(state.currentId, state.sessionId || "", text);
}

/* ---------- vc18: transient tool line ----------
 * vc17 之前 tool.started/completed 走 sysMsg()，直接 append 进 #view，位置落在
 * 回复气泡下方、且一轮里堆十几行、回合结束也不清。现在收敛成 composer 上方
 * 单独一行：只保留最后一次工具/活动，回合结束即隐藏（不进聊天记录、不进 DB）*/
function setToolLine(text) {
  var el = $("toolLine");
  if (!el) return;
  if (!text) { el.textContent = ""; el.classList.remove("show"); return; }
  el.textContent = text;
  el.classList.add("show");
}

function finishTurn(status) {
  if (state.turnDone) return;
  state.turnDone = true;
  state.busy = false;
  state.turnCid = null;   // vc41
  state.pendingText = ""; state.selfHeal = false;   // vc7: reset one-shot heal guards
  $("btnSend").disabled = false; $("btnStop").style.display = "none";
  hideSteer();
  botEl = null;
  runActivity(null);
  removeCursor(); setStatus(""); setToolLine("");
  var offView = $("chatView").style.display !== "flex";   // vc25
  if (status && status !== "completed" && !offView) sysMsg("回合结束: " + status);
}

/* ---------- vc26: run activity → foreground notification ----------
 * 用户不在 App 里也能从通知栏一眼看到 agent 在干活（微信"对方正在输入"
 * 的通知版）。JS 侧只发意图，Java 侧 MainActivity.updateRunActivity 落地。 */
function runActivity(text) {
  try { Android.setRunActivity(text == null ? "" : text); } catch (e) {}
}

/* ---------- steer (mid-run interjection) ---------- */
function showSteer() {
  if ($("steerRow")) return;
  var row = document.createElement("div"); row.id = "steerRow"; row.className = "steer";
  var inp = document.createElement("input"); inp.placeholder = "运行中插话（steer）…";
  var go = mkBtn("插入", "primary");
  go.onclick = function () {
    var t = inp.value.trim();
    if (!t || !state.runId) return;
    inp.value = "";
    var r;
    try { r = JSON.parse(Android.steerRun(state.currentId, state.runId, t) || "{}"); }
    catch (e) { r = { http: 0, error: String(e) }; }
    if (r.http === 200) { sysMsg("插话已送达 agent"); hideSteer(); }
    else { sysMsg("插话失败: " + (r.error || ("http " + r.http))); }
  };
  inp.addEventListener("keydown", function (ev) { if (ev.key === "Enter") go.onclick(); });
  row.appendChild(inp); row.appendChild(go);
  $("composer").parentNode.insertBefore(row, $("composer"));
}
function hideSteer() { var s = $("steerRow"); if (s) s.remove(); }

/* ---------- vc33: model picker (button in chat header) ----------
 * Catalog: dynamic GET /api/model/options (authed providers with real model ids,
 * e.g. deepseek/deepseek-flash). Falls back to static presets on failure/empty.
 * Locking without a session: vc33 creates one via POST /api/sessions
 * {model, require_model_lock:true} (server confirms the lock in the same call). */
var MODEL_PRESETS = ["zai/glm-5.3-flash", "custom:custom/glm-5.3-flash", "hermes-agent"];
var _modelCatalog = null;   // vc33: session cache; durable copy in localStorage per cid
function modelNames(cid) {
  var m = modelOf(cid);
  var names = (Array.isArray(_modelCatalog) && _modelCatalog.length) ? _modelCatalog.slice()
            : (loadModelCatalog(cid) || MODEL_PRESETS.slice());
  if (m && names.indexOf(m) < 0) names.unshift(m);
  return names;
}
function fetchModelCatalogAsync(cid) {
  // Background catalog fetch; re-renders the open sheet when it lands.
  setTimeout(function () {
    var r = {};
    try { r = JSON.parse(Android.modelOptions(cid) || "{}"); } catch (e) { return; }
    var body = typeof r.body === "string" ? safeParse(r.body) : r.body;
    var provs = body && body.providers;
    if (r.http !== 200 || !Array.isArray(provs)) return;
    var out = [];
    for (var i = 0; i < provs.length; i++) {
      var p = provs[i];
      if (p && p.authenticated !== false && Array.isArray(p.models)) {
        for (var j = 0; j < p.models.length; j++) {
          var mid = String(p.models[j] || "");
          if (!mid || mid === "hermes-agent") continue;
          out.push(String(p.slug || p.id || "") + "/" + mid);
        }
      }
    }
    if (out.length) { _modelCatalog = out; saveModelCatalog(cid, out); }
  }, 0);
}
/* vc34: openModelSheet/closeModelSheet/renderModelList removed with the picker button —
   /model and /model list are the only model controls now; lockModel/applyLockResult/
   fetchModelCatalogAsync stay (the /model command path uses them). */
function applyLockResult(r, name, viaSlash) {
  var body = typeof r.body === "string" ? safeParse(r.body) : r.body;
  if (r.http === 200 || r.http === 201) {
    var rt = (body && (body.runtime || (body.session && body.session.runtime))) || null;
    var shown = (rt && rt.model) || (body && body.session && body.session.model) || name;
    saveModel(state.currentId, shown);
    if (viaSlash) {
      sysMsgPersist("模型已锁定：" + shown + "\n（本会话后续回合生效）");
    } else {
      toast("模型已锁定：" + shown);   // vc34: sheet gone — toast only
    }
    return true;
  }
  var msg = (body && body.error && (body.error.message || body.error)) || r.error || ("http " + r.http);
  if (viaSlash) sysMsg("切模型失败：" + msg); else toast("切模型失败：" + msg);
  return false;
}
/* vc33: no session yet → create one WITH the model lock in a single POST /api/sessions */
function lockModel(name) {
  if (!name || !state.currentId) return;
  if (!state.sessionId) {
    var cr = {};
    try { cr = JSON.parse(Android.createSessionLocked(state.currentId, name) || "{}"); }
    catch (e) { cr = { http: 0, error: String(e) }; }
    var cbody = typeof cr.body === "string" ? safeParse(cr.body) : cr.body;
    var sid = cbody && ((cbody.session && cbody.session.id) || cbody.id || cbody.session_id) || "";
    if (sid) {
      state.sessionId = sid;
      try { Android.chatSessionPut(state.currentId, sid); } catch (e) {}
      markAlive(state.currentId);
      applyLockResult(cr, name, false);
      return;
    }
    // fall through to the old error path only if creation itself failed
    applyLockResult({ http: cr.http || 0, body: cbody || cr.body, error: cr.error }, name, false);
    return;
  }
  var r = {};
  try { r = JSON.parse(Android.setSessionModel(state.currentId, state.sessionId, name) || "{}"); }
  catch (e) { r = { http: 0, error: String(e) }; }
  applyLockResult(r, name, false);
}

/* ---------- approval card (M2: full choices + remote resolution + 401) ---------- */
var APPROVAL_LABELS = { once: "放行一次", session: "本会话放行", always: "总是放行", deny: "拒绝" };
function keyError() {
  setDot("err");
  var c = cur();
  sysMsg("🔑 密钥错误：请检查联系人「" + curName() + "」的 API Key");
  toast("密钥错误，请重新粘贴");
  if (c) openContactSheet(c); else showContacts();
}
function showApproval(p) {
  // vc25: 回合中切到联系人页 → 审批卡无处安放。离屏时只留 toast 提醒，
  // 服务端审批等用户切回或走通知；卡片不丢事件（state.events 已存档）。
  if ($("chatView").style.display !== "flex") {
    toast("⚠ 有审批请求，回到会话查看");
    return;
  }
  var card = document.createElement("div"); card.className = "approval";
  card._runId = p.run_id || "";
  card._reqId = p.request_id || "";
  card._toCid = p.toCid || "";   // vc11: relay approvals must resolve on the TARGET endpoint
  var title = document.createElement("div"); title.textContent = p.toCid ? "⚠️ 工具执行审批（转发中的 agent）" : "⚠️ 工具执行审批";
  var cmd = document.createElement("div"); cmd.className = "cmd";
  cmd.textContent = p.command || p.preview || JSON.stringify(p).slice(0, 200);
  var row = document.createElement("div"); row.className = "row";
  var choices = (p.choices && p.choices.length) ? p.choices : ["once", "deny"];
  for (var i = 0; i < choices.length; i++) {
    var b = mkBtn(APPROVAL_LABELS[choices[i]] || choices[i], choices[i] === "once" ? "primary" : "");
    b.onclick = mkApprover(choices[i], card);
    row.appendChild(b);
  }
  card.appendChild(title); card.appendChild(cmd); card.appendChild(row);
  $("view").appendChild(card); scrollBottom();
  state.pendingApprovals.push(card);
}
function mkBtn(t, cls) { var b = document.createElement("button"); b.textContent = t; if (cls) b.className = cls; return b; }
function mkApprover(choice, card) { return function () { respond(card, choice); }; }
function respond(card, choice) {
  var rid = card._runId || state.runId || "";
  if (!rid) { sysMsg("审批(" + choice + "): 缺 run_id，无法回传"); return; }
  // vc11: relay approval cards carry the TARGET contact id — resolve there,
  // not on the currently open chat (state.currentId).
  var cid = card._toCid || state.currentId;
  var r;
  try { r = JSON.parse(Android.approveRun(cid, rid, choice, card._reqId || "") || "{}"); }
  catch (e) { r = { http: 0, error: String(e) }; }
  if (r.http === 200) {
    dropCard(card);
    toast(choice === "deny" ? "已拒绝" : "已放行（" + (APPROVAL_LABELS[choice] || choice) + "）");
    sysMsg("审批(" + (APPROVAL_LABELS[choice] || choice) + "): 已提交服务端");
  } else {
    toast("审批失败 http=" + (r.http || "?"));
    sysMsg("审批失败: " + (r.error || ("http " + r.http)) + "（卡片保留可重试）");
  }
}
function dropCard(card) {
  var i = state.pendingApprovals.indexOf(card);
  if (i >= 0) state.pendingApprovals.splice(i, 1);
  if (card && card.parentNode) card.remove();
}
function resolveApprovalCards(p) {
  var n = state.pendingApprovals.length;
  while (state.pendingApprovals.length) {
    var c = state.pendingApprovals.pop();
    if (c && c.parentNode) c.remove();
  }
  if (n) {
    var how = (p && p.choice && APPROVAL_LABELS[p.choice]) ||
              (p && p.resolved !== undefined ? String(p.resolved) : "其他端已处理");
    sysMsg("审批已处理: " + how);
  }
}

/* ---------- health (per contact) ----------
 * vc27: 判定全部转后台。openContact 只发起 Android.healthAsync（POOL 线程，
 * 结果经 __sseEvent health 事件回推），本函数零阻塞 → 进联系人即时打开。
 * 列表页 showContacts 对每个联系人各发一次后台探测，回来着色 cdot。
 * 结果写 localStorage pohealth_<cid>（60s 有效），列表一开先显示上次状态。 */
var HEALTH_TTL = 60000;
function healthCache(cid) {
  try {
    var v = JSON.parse(localStorage.getItem("pohealth_" + cid) || "null");
    if (v && Date.now() - v.ts < HEALTH_TTL) return v.cls;
  } catch (e) {}
  return "";
}
function applyHealth(cid, r) {
  var cls = (r && r.http === 200) ? "ok" : "err";
  var cd = $("cdot-" + cid);
  if (cd) cd.className = "cdot " + cls;
  if (cid === state.currentId && $("chatView").style.display === "flex") setDot(cls);
  try { localStorage.setItem("pohealth_" + cid, JSON.stringify({ ts: Date.now(), cls: cls })); } catch (e) {}
  return cls;
}
/* vc32: any successful server round-trip proves the endpoint is alive.
 * Health probes only fire on contact open / list render, and a stale 401
 * (keyError) or expired cache pinned the dot red even while messaging worked.
 * Call this from every successful createSession / completed turn. */
function markAlive(cid) {
  if (!cid) return;
  var cd = $("cdot-" + cid);
  if (cd) cd.className = "cdot ok";
  if (cid === state.currentId && $("chatView").style.display === "flex") setDot("ok");
  try { localStorage.setItem("pohealth_" + cid, JSON.stringify({ ts: Date.now(), cls: "ok" })); } catch (e) {}
}
function probeHealth(cid) { try { Android.healthAsync(cid); } catch (e) {} }
function doHealth(quiet) {
  if (!state.currentId) return;
  var cached = healthCache(state.currentId);
  setDot(cached);
  var cd0 = $("cdot-" + state.currentId);
  if (cd0 && cached) cd0.className = "cdot " + cached;
  probeHealth(state.currentId);
  if (!quiet) toast("正在检测 " + curName() + " …");
}
/* vc27: contact list — paint last-known health instantly, then probe all in background. */
function probeAllContacts() {
  for (var i = 0; i < state.contacts.length; i++) {
    (function (cid) {
      var cls = healthCache(cid);
      var cd = $("cdot-" + cid);
      if (cd && cls) cd.className = "cdot " + cls;
      probeHealth(cid);
    })(state.contacts[i].id);
  }
}

/* ---------- render ---------- */
function view() { return $("view"); }
function scrollBottom() { view().scrollTop = view().scrollHeight; }
function setStatus(t) {
  var el = document.querySelector(".statusline");
  if (!el) { el = document.createElement("div"); el.className = "statusline"; view().appendChild(el); }
  el.textContent = t || "";
  if (!t) el.remove(); else scrollBottom();
}
function bubble(cls, text) {
  var m = document.createElement("div"); m.className = "msg " + cls; m.textContent = text || "";
  // vc11: long-press menu (copy / forward) on real message bubbles only —
  // sys lines and relay quote blocks are excluded
  if (cls.indexOf("relay") < 0 && (cls === "user" || cls === "bot"))
    mkLongPress(m, function () { return m.textContent; });
  view().appendChild(m); scrollBottom(); return m;
}
// vc21: data-URL image bubble (bot MEDIA: tags arrive as markdown ![](data:...)).
// Tap → save to gallery via bridge. Long-press menu not wired (image bubbles).
function imgBubble(dataUrl, caption) {
  var m = document.createElement("div"); m.className = "msg bot img";
  var img = document.createElement("img");
  img.src = dataUrl; img.alt = "图片";
  img.onclick = function () {
    toast("保存中…");
    var r = {};
    try { r = JSON.parse(Android.saveImageDataUrl(dataUrl) || "{}"); } catch (e) { r = { ok: false, error: String(e) }; }
    toast(r.ok ? "已保存到相册 Pictures/PocketOrca" : "保存失败: " + (r.error || "?"));
  };
  m.appendChild(img);
  if (caption && caption.trim()) {
    var cap = document.createElement("div"); cap.className = "imgcap"; cap.textContent = caption.trim();
    m.appendChild(cap);
  }
  view().appendChild(m); scrollBottom(); return m;
}
// vc21: split a rendered message into text + data-URL image parts.
// Server turns MEDIA:/path → ![image](data:...;base64,…) (api_server L863),
// ≤5MB images only; anything else stays as plain text.
function renderBotContent(container, fullText) {
  var re = /!\[[^\]]*\]\((data:image\/[^;]+;base64,[A-Za-z0-9+/=]+)\)/g;
  var last = 0, mm, hit = false;
  container.textContent = "";
  while ((mm = re.exec(fullText)) !== null) {
    hit = true;
    var before = fullText.slice(last, mm.index);
    if (before.trim()) {
      var tb = document.createElement("div"); tb.textContent = before; container.appendChild(tb);
    }
    var ib = document.createElement("div");
    imgBubbleInto(ib, mm[1], null);
    container.appendChild(ib);
    last = re.lastIndex;
  }
  var tail = fullText.slice(last);
  if (tail.trim() || !hit) {
    var tb2 = document.createElement("div"); tb2.textContent = tail; container.appendChild(tb2);
  }
  return hit;
}
// fill an existing wrapper with a tappable image (no extra .msg nesting)
function imgBubbleInto(wrapper, dataUrl, caption) {
  var img = document.createElement("img");
  img.src = dataUrl; img.alt = "图片";
  img.onclick = function () {
    toast("保存中…");
    var r = {};
    try { r = JSON.parse(Android.saveImageDataUrl(dataUrl) || "{}"); } catch (e) { r = { ok: false, error: String(e) }; }
    toast(r.ok ? "已保存到相册 Pictures/PocketOrca" : "保存失败: " + (r.error || "?"));
  };
  wrapper.appendChild(img);
  if (caption && caption.trim()) {
    var cap = document.createElement("div"); cap.className = "imgcap"; cap.textContent = caption.trim();
    wrapper.appendChild(cap);
  }
}
function userMsg(t) { return bubble("user", t); }
function sysMsg(t) { return bubble("sys", t); }
/* vc33: sys line + local DB persist (survives view switches & app restarts) */
function sysMsgPersist(t) {
  try { Android.chatInsert(state.currentId, "sys", t); } catch (e) {}
  return sysMsg(t);
}
var botEl = null;
function startBotBubble() {
  botEl = bubble("bot", "");
  var c = document.createElement("span"); c.className = "cursor"; botEl.appendChild(c);
}
function currentBot() { return botEl; }
function appendDelta(d) {
  if (!botEl) startBotBubble();
  var cur2 = botEl.querySelector(".cursor");
  botEl.insertBefore(document.createTextNode(d), cur2);
  scrollBottom();
}
function removeCursor() {
  if (botEl) { var c = botEl.querySelector(".cursor"); if (c) c.remove(); }
  botEl = null;
}

/* ================= vc11: delegation relay (PLAN §4, client-side scheduler) =================
 * Long-press a message → 转给… → pick a contact → the app runs ONE chat turn on the
 * TARGET endpoint (B's own session), streams the reply back as a quote block in THIS
 * chat, labeled 来自 B. RED LINE: the relay never auto-feeds B's reply into any agent —
 * it only lands in this UI + local DB. One turn, no loop, cancellable via stop run. */

var relay = { active: false, id: "", el: null, buf: "", from: "", fromName: "", label: "" };
var relaySeq = 0;

/* ---------- long-press on message bubbles ---------- */
function mkLongPress(el, getText) {
  var timer = null, startY = 0, fired = false;
  el.addEventListener("touchstart", function (e) {
    if (state.busy) return;                        // no menu mid-turn
    fired = false; startY = e.touches[0].clientY;
    timer = setTimeout(function () { fired = true; openMsgMenu(el, getText); }, 500);
  }, { passive: true });
  var cancel = function () { if (timer) { clearTimeout(timer); timer = null; } };
  el.addEventListener("touchmove", function (e) {
    if (timer && Math.abs(e.touches[0].clientY - startY) > 12) cancel();
  }, { passive: true });
  el.addEventListener("touchend", cancel);
  el.addEventListener("touchcancel", cancel);
  // desktop / emulator fallback
  el.addEventListener("contextmenu", function (e) { e.preventDefault(); if (!state.busy) openMsgMenu(el, getText); });
}
function openMsgMenu(el, getText) {
  if (navigator.vibrate) { try { navigator.vibrate(15); } catch (e) {} }
  var mask = document.createElement("div"); mask.className = "mask show";
  var sheet = document.createElement("div"); sheet.className = "sheet";
  var h = document.createElement("h2"); h.textContent = "消息操作";
  var row = document.createElement("div"); row.className = "row";
  var bCopy = mkBtn("复制", ""), bSel = mkBtn("选择文本复制", ""), bFwd = mkBtn("转给…", "primary");
  bCopy.onclick = function () {
    try { Android.copyText(el.textContent || ""); toast("已复制"); } catch (e) { toast("复制失败"); }
    mask.remove();
  };
  bSel.onclick = function () {   // vc34: selection copy — textarea for manual text selection
    mask.remove();
    var smask = document.createElement("div"); smask.className = "mask show";
    var ssheet = document.createElement("div"); ssheet.className = "sheet";
    var sh = document.createElement("h2"); sh.textContent = "选择文本复制";
    var ta = document.createElement("textarea");
    ta.value = el.textContent || "";
    ta.style.cssText = "width:100%;height:34vh;background:var(--bg3);border:1px solid #2d3540;border-radius:8px;padding:9px 12px;color:var(--fg);font-size:14px;white-space:pre-wrap";
    ta.readOnly = true;
    var srow = document.createElement("div"); srow.className = "row";
    var sClose = mkBtn("关闭", "");
    sClose.onclick = function () { smask.remove(); };
    srow.appendChild(sClose);
    ssheet.appendChild(sh); ssheet.appendChild(ta); ssheet.appendChild(srow);
    smask.appendChild(ssheet);
    smask.addEventListener("click", function (e) { if (e.target === smask) smask.remove(); });
    document.body.appendChild(smask);
    setTimeout(function () { try { ta.focus(); ta.select(); } catch (e) {} }, 50);
  };
  bFwd.onclick = function () { mask.remove(); openRelayPicker(getText()); };
  row.appendChild(bCopy); row.appendChild(bSel); row.appendChild(bFwd);
  sheet.appendChild(h); sheet.appendChild(row);
  mask.appendChild(sheet);
  mask.addEventListener("click", function (e) { if (e.target === mask) mask.remove(); });
  document.body.appendChild(mask);
}

/* ---------- contact picker ---------- */
function openRelayPicker(text) {
  var others = state.contacts.filter(function (c) { return c.id !== state.currentId; });
  if (!others.length) { toast("没有其他联系人可转发"); return; }
  var mask = document.createElement("div"); mask.className = "mask show";
  var sheet = document.createElement("div"); sheet.className = "sheet";
  var h = document.createElement("h2"); h.textContent = "转给…";
  sheet.appendChild(h);
  for (var i = 0; i < others.length; i++) (function (c) {
    var row = document.createElement("div"); row.className = "crow";
    var main = document.createElement("div"); main.className = "cmain";
    var nm = document.createElement("div"); nm.className = "cname"; nm.textContent = c.name || c.url;
    var sub = document.createElement("div"); sub.className = "curl"; sub.textContent = c.url.replace(/^https?:\/\//, "");
    main.appendChild(nm); main.appendChild(sub); row.appendChild(main);
    row.onclick = function () { mask.remove(); openRelaySheet(text, c); };
    sheet.appendChild(row);
  })(others[i]);
  var note = document.createElement("div"); note.className = "hint";
  note.textContent = "将把这条消息转给选中的 agent，回复会带「来自」标注回到当前会话。";
  sheet.appendChild(note);
  mask.appendChild(sheet);
  mask.addEventListener("click", function (e) { if (e.target === mask) mask.remove(); });
  document.body.appendChild(mask);
}

/* ---------- optional-context sheet → launch ---------- */
function openRelaySheet(text, target) {
  var mask = document.createElement("div"); mask.className = "mask show";
  var sheet = document.createElement("div"); sheet.className = "sheet";
  var h = document.createElement("h2");
  h.textContent = "转给 " + (target.name || target.url);
  var hint = document.createElement("div"); hint.className = "hint"; hint.style.margin = "0 0 10px";
  var q = text.length > 120 ? text.slice(0, 120) + "…" : text;
  hint.textContent = "转发内容：" + q;
  var inp = document.createElement("input");
  inp.placeholder = "附加说明（可选）";
  var row = document.createElement("div"); row.className = "row";
  var bCancel = mkBtn("取消", ""), bGo = mkBtn("发送转发", "primary");
  bCancel.onclick = function () { mask.remove(); };
  bGo.onclick = function () {
    var extra = inp.value.trim();
    mask.remove();
    startRelay(text, target, extra);
  };
  row.appendChild(bCancel); row.appendChild(bGo);
  sheet.appendChild(h); sheet.appendChild(hint); sheet.appendChild(inp); sheet.appendChild(row);
  mask.appendChild(sheet);
  mask.addEventListener("click", function (e) { if (e.target === mask) mask.remove(); });
  document.body.appendChild(mask);
}

/* ---------- relay turn ---------- */
function startRelay(text, target, extra) {
  if (relay.active) { toast("已有转发在进行"); return; }
  if (!state.currentId) return;
  relaySeq++;
  var rid = "r" + Date.now() + "-" + relaySeq;
  var label = target.name || target.url;
  relay.active = true; relay.id = rid; relay.el = null; relay.buf = "";
  relay.from = state.currentId; relay.fromName = curName(); relay.label = label;
  var compose = text + (extra ? "\n\n（发起方附加说明：" + extra + "）" : "");
  // A-side UX: status line + growing quote block (streamed from B)
  setStatus("转发中 → " + label + "…");
  relay.el = bubble("relay", "");
  var head = document.createElement("div"); head.className = "relay-head";
  head.textContent = "↪ 已转给 " + label + "，等待回复…";
  relay.el.appendChild(head);
  var body = document.createElement("div"); body.className = "relay-body";
  relay.el.appendChild(body);
  // cancel button (stop run on B)
  var cancel = mkBtn("取消转发", "");
  cancel.className = "relay-cancel";
  cancel.onclick = function () {
    var r;
    try { r = JSON.parse(Android.relayStop(rid) || "{}"); } catch (e) { r = { http: 0 }; }
    sysMsg(r.http === 200 ? "已请求取消转发" : "取消失败 http=" + r.http);
  };
  relay.el.appendChild(cancel);
  scrollBottom();
  try {
    Android.relayStart(state.currentId, target.id, rid, compose);
  } catch (e) {
    relayFail("转发启动失败: " + e);
  }
}
function relayBody() {
  var b = relay.el && relay.el.querySelector(".relay-body");
  return b || null;
}
function relayDelta(d) {
  // vc26: delta 只进缓冲，不渲染（整条收，relayFinish 一次性显示）
  relay.buf += d;
}
function relayBufDelta(d) { relayDelta(d); }
function relayStatus(p) {
  var head = relay.el && relay.el.querySelector(".relay-head");
  var txt = "";
  if (p.phase === "running") txt = "↪ " + relay.label + " 正在处理…";
  else if (p.phase === "tool") txt = "🔧 " + relay.label + " 使用工具：" + (p.text || "tool") + " …";
  else if (p.phase === "completed") txt = "↪ " + relay.label + " 已完成";
  if (head && txt) head.textContent = txt;
}
function relayFail(msg) {
  if (!relay.active) return;
  relay.active = false;
  var el = relay.el;
  if (el && el.parentNode) {
    var head = el.querySelector(".relay-head");
    if (head) head.textContent = "⚠ 转发失败：" + msg;
    var c = el.querySelector(".relay-cancel"); if (c) c.remove();
  }
  relay.el = null;
  setStatus("");
  toast("转发失败");
}
function relayFinish(p) {
  if (!relay.active) return;
  if (p.relayId && p.relayId !== relay.id) return;   // stale relay event
  relay.active = false;
  var content = (p.content || relay.buf || "").trim();
  var status = p.status || "completed";
  var el = relay.el;
  if (el && el.parentNode) {
    var head = el.querySelector(".relay-head");
    if (head) {
      head.textContent = status === "completed"
        ? "↪ 已转给 " + relay.label + "，回复如下（来自 " + relay.label + "）"
        : "↪ 转发结束（" + status + "）";
    }
    var body = el.querySelector(".relay-body");
    if (body) body.textContent = content || "（B 无文本回复）";
    var c = el.querySelector(".relay-cancel"); if (c) c.remove();
  }
  // durable transcript: only the final labeled quote lands in A's local DB
  // (sys-style lines are UI-only). RED LINE honored: nothing is auto-fed to B or A.
  if (content && relay.from)
    try { Android.chatInsert(relay.from, "sys", "来自 " + relay.label + " 的回复：" + content); } catch (e) {}
  relay.el = relay.buf = "";
  setStatus("");
}
/* vc11: re-render a relay quote block from the local transcript (role=sys rows
 * written by relayFinish, format "来自 <label> 的回复：<content>"). */
function relayReplay(text) {
  var t = text || "", label = "", content = t;
  var m = t.match(/^来自 (.+) 的回复：([\s\S]*)$/);
  if (m) { label = m[1]; content = m[2]; }
  var el = bubble("relay", "");
  var head = document.createElement("div"); head.className = "relay-head";
  head.textContent = label ? "↪ 来自 " + label : "↪ 转发回复";
  var body = document.createElement("div"); body.className = "relay-body";
  body.textContent = content;
  el.appendChild(head); el.appendChild(body);
  return el;
}

/* ================= vc22: slash commands (client-side) =================
 * api_server runs no slash dispatch: a message starting with "/" is delivered to
 * the agent as plain text. These commands are resolved on the phone before send();
 * anything that is NOT an exact known command still goes out as a normal message
 * (a path like "/home/dawoer/x" is not swallowed). */

var CMD_HELP_TEXT =
  "可用命令：\n" +
  "/help            显示本帮助\n" +
  "/new             开新会话（旧记录保留，agent 上下文清零）\n" +
  "/clear           清空本联系人的本地聊天记录\n" +
  "/stop            停止当前回合\n" +
  "/model [名称]    查看当前模型 / 锁定本会话模型（如 /model zai/glm-5.3-flash；/model list 看可用列表）\n" +
  "/context         查看上下文占用\n" +
  "/status          查看本地状态（版本/会话/模型/连接/轮询）\n" +
  "/search [关键词] 搜索历史聊天记录\n" +
  "其它以 / 开头的内容按普通消息发送。";

function handleCommand(raw) {
  var t = (raw || "").trim();
  if (t.charAt(0) !== "/") return false;
  var sp = t.indexOf(" "), cmd = (sp < 0 ? t : t.slice(0, sp)).toLowerCase();
  var arg = sp < 0 ? "" : t.slice(sp + 1).trim();
  // vc38: 命令解析收回客户端（vc37 交给服务端＝装上就是坏的：api_server 零斜杠分发，
  // /model 之类会当聊天文本原样喂给 agent）。现在：
  //   全部已知命令本地解析 —— /new /clear /stop 操作客户端持有的 session_id/run_id，
  //   /model 走 POST /api/sessions/{sid}/model（vc35 已验证 splitModel 拆 provider/model），
  //   /context 读本地 poctx 缓存，/status 纯本地汇总，/search 本地检索。
  //   未知 "/xxx" 仍原样发出（路径类文本不被吞）。
  if (cmd === "/help") { sysMsg(CMD_HELP_TEXT); return true; }
  if (cmd === "/new") { cmdNew(); return true; }
  if (cmd === "/clear") { cmdClear(); return true; }
  if (cmd === "/stop") { cmdStop(); return true; }
  if (cmd === "/model") { cmdModel(arg); return true; }
  if (cmd === "/context") { cmdContext(); return true; }
  if (cmd === "/status") { cmdStatus(); return true; }
  if (cmd === "/search") { openSearch(arg); return true; }
  return false;   // 其余 /xxx 原样发给服务端（agent 当普通文本处理）
}

function cmdNew() {
  if (!state.currentId) { toast("先添加联系人"); return; }
  if (state.busy) { toast("回合进行中，先 /stop"); return; }
  try { Android.chatSessionClear(state.currentId); } catch (e) {}
  state.sessionId = null; state.runId = "";
  try { localStorage.removeItem(ctxKey(state.currentId)); } catch (e) {}
  renderCtx(null);
  sysMsg("— 已开启新会话 —");
  try { Android.chatInsert(state.currentId, "sys", "— 已开启新会话 —"); } catch (e) {}
  toast("新会话已就绪，下一条消息开始新上下文");
}

function cmdClear() {
  if (!state.currentId) return;
  askConfirm("清空聊天记录", "只清本联系人的本地聊天记录，服务端会话和联系人保留。确认？", function () {
    try { Android.chatClear(state.currentId); } catch (e) {}
    $("view").innerHTML = ""; botEl = null;
    hideHistBanner();
    toast("已清空聊天记录");
  });
}

function cmdStop() {
  if (!state.runId) { sysMsg("当前没有运行中的回合"); return; }
  var r;
  try { r = JSON.parse(Android.stopRun(state.currentId, state.runId) || "{}"); }
  catch (e) { r = { http: 0 }; }
  sysMsg(r.http === 200 ? "已请求停止当前回合" : ("停止失败 http=" + r.http));
}

/* vc33: fetch the session's model straight from the server (works even before the
 * first message — POST /api/sessions persists the model at creation time). */
function fetchServerModel(cid, cb) {
  if (!state.sessionId) { cb(null); return; }
  setTimeout(function () {
    var r = {};
    try { r = JSON.parse(Android.sessionInfo(cid, state.sessionId) || "{}"); } catch (e) { r = {}; }
    var body = typeof r.body === "string" ? safeParse(r.body) : r.body;
    var m = body && body.session && body.session.model;
    if (m) saveModel(cid, String(m));
    cb(m ? String(m) : null);
  }, 0);
}

function cmdModel(arg) {
  if (!state.currentId) return;
  if (!arg) {
    var m = modelOf(state.currentId), c = loadCtx(state.currentId);
    if (m) {
      sysMsg("当前模型：" + m +
        (c ? ("\n上下文：" + fmtK(c.used) + " / " + fmtK(c.max) + "（" + c.pct + "%）") : "") +
        "\n锁定本会话模型：/model provider/model，例如 /model zai/glm-5.3-flash" +
        "\n可用模型：/model list");
    } else {
      sysMsg("当前模型：查询中…");
      fetchServerModel(state.currentId, function (sm) {
        sysMsg(sm ? ("当前模型：" + sm +
          "\n锁定本会话模型：/model provider/model，例如 /model zai/glm-5.3-flash" +
          "\n可用模型：/model list")
                  : "当前模型：未知（服务端未返回，发一条消息后再查）");
      });
    }
    return;
  }
  if (arg === "list") { cmdModelList(); return; }
  if (!state.sessionId) { lockModel(arg); return; }   // vc33: create+lock in one call
  var r = {}, body = null;
  try { r = JSON.parse(Android.setSessionModel(state.currentId, state.sessionId, arg) || "{}"); }
  catch (e) { r = { http: 0, error: String(e) }; }
  applyLockResult(r, arg, true);
}

/* vc33: /model list — show the catalog fetched from /api/model/options */
function cmdModelList() {
  var cid = state.currentId;
  sysMsg("获取模型列表…");
  setTimeout(function () {
    var r = {};
    try { r = JSON.parse(Android.modelOptions(cid) || "{}"); } catch (e) { r = {}; }
    var body = typeof r.body === "string" ? safeParse(r.body) : r.body;
    var provs = body && body.providers;
    if (r.http !== 200 || !Array.isArray(provs)) { sysMsg("模型列表获取失败（http " + r.http + "）"); return; }
    var lines = [];
    _modelCatalog = [];
    for (var i = 0; i < provs.length; i++) {
      var p = provs[i];
      if (p && p.authenticated !== false && Array.isArray(p.models) && p.models.length) {
        lines.push("【" + (p.name || p.slug) + "】");
        for (var j = 0; j < p.models.length; j++) {
          var mid = String(p.models[j] || "");
          if (!mid || mid === "hermes-agent") continue;
          var full = (p.slug || p.id) + "/" + mid;
          _modelCatalog.push(full);
          lines.push("  " + full);
        }
      }
    }
    if (_modelCatalog.length) {
      saveModelCatalog(cid, _modelCatalog);
      sysMsg("可用模型（锁定：/model 名称）：\n" + lines.join("\n"));
    } else {
      sysMsg("没有已配置凭据的模型。");
    }
  }, 0);
}

function cmdContext() {
  var c = loadCtx(state.currentId);
  if (!c) {
    sysMsg("暂无上下文数据。\n发一条消息后显示；若仍为空，说明服务端未带 context 字段（需 8T 重启 Hermes 后生效）。");
    return;
  }
  sysMsg("上下文占用：" + fmtK(c.used) + " / " + fmtK(c.max) + "（" + c.pct + "%）" +
    (c.comp ? ("\n已压缩 " + c.comp + " 次") : "") +
    (c.est ? "\n（数值为本地估算）" : ""));
}

/* vc38: /status — 纯本地汇总，不发任何请求（离线也能看） */
function cmdStatus() {
  var cid = state.currentId, ct = cur();
  var ver = "";
  try { ver = Android.appVersion() || ""; } catch (e) { ver = ""; }
  var dot = "";
  if ($("connDot")) dot = String($("connDot").className || "").replace("dot", "").trim();
  var dotTxt = dot === "ok" ? "在线" : (dot === "err" ? "连接异常（检查密钥/地址）" : "未探测");
  var c = loadCtx(cid);
  var rid = state.runId ? String(state.runId) : "";
  var lines = [];
  lines.push("PocketOrca-Chat " + (ver || "（版本未知）"));
  lines.push("联系人：" + (ct ? (ct.name || ct.url) : "无"));
  if (ct) lines.push("地址：" + (ct.url || ""));
  lines.push("会话：" + (state.sessionId ? (String(state.sessionId).slice(0, 8) + "…") : "未建立（下一条消息创建）"));
  lines.push("回合：" + (state.busy ? "进行中" : "空闲") + (rid ? (" · run " + rid.slice(0, 8)) : ""));
  lines.push("模型：" + (modelOf(cid) || "服务端默认（发一条消息后显示）"));
  lines.push("上下文：" + (c
    ? (fmtK(c.used) + " / " + fmtK(c.max) + "（" + c.pct + "%）" +
       (c.comp ? (" · 压缩" + c.comp + "次") : "") + (c.est ? " · 估算" : ""))
    : "暂无（发一条消息后显示）"));
  lines.push("在线探测：" + dotTxt);
  lines.push("新消息轮询：" + Math.round(pollIntervalMs() / 1000) + " 秒");
  sysMsg(lines.join("\n"));
}

/* ---------- context meter ---------- */
function ctxKey(cid) { return "poctx_" + cid; }
function fmtK(n) {
  n = Number(n) || 0;
  if (n >= 100000) return Math.round(n / 1000) + "K";
  if (n >= 1000) {
    var v = (n / 1000).toFixed(1);
    return (v.slice(-2) === ".0" ? v.slice(0, -2) : v) + "K";
  }
  return String(Math.round(n));
}
function loadCtx(cid) {
  if (!cid) return null;
  try { var s = localStorage.getItem(ctxKey(cid)); return s ? JSON.parse(s) : null; }
  catch (e) { return null; }
}
function captureUsage(usage) {
  if (!usage || !usage.context_used || !usage.context_max) return;   // old server: no fields
  var u = {
    used: usage.context_used, max: usage.context_max,
    pct: (usage.context_percent != null ? usage.context_percent
          : Math.round(usage.context_used / usage.context_max * 100)),
    comp: usage.compressions || 0, est: !!usage.context_estimated, ts: Date.now()
  };
  try { localStorage.setItem(ctxKey(state.currentId), JSON.stringify(u)); } catch (e) {}
  renderCtx(u);
}
function renderCtx(u) {
  var bar = $("ctxBar"), fill = $("ctxFill"), lab = $("ctxLabel");
  if (!bar) return;
  if (!u || !u.max) { bar.style.display = "none"; return; }
  var pct = u.pct != null ? u.pct : Math.round(u.used / u.max * 100);
  bar.style.display = "block";
  fill.style.width = Math.max(2, Math.min(100, pct)) + "%";
  fill.className = pct >= 85 ? "hot" : (pct >= 60 ? "warm" : "");
  lab.textContent = "上下文 " + fmtK(u.used) + " / " + fmtK(u.max) + " · " + pct + "%" +
    (u.comp ? " · 压缩" + u.comp + "次" : "") + (u.est ? " · 估算" : "");
}

/* ---------- current model (per contact, from run runtime) ---------- */
function modelKey(cid) { return "pomodel_" + cid; }
function saveModel(cid, model) {
  if (!cid || !model) return;
  try { localStorage.setItem(modelKey(cid), model); } catch (e) {}
}
function modelOf(cid) {
  try { return localStorage.getItem(modelKey(cid)) || ""; } catch (e) { return ""; }
}
/* vc33: durable model catalog (survives app restarts; refreshed by /model list & panel) */
function catalogKey(cid) { return "pomodelcat_" + cid; }
function saveModelCatalog(cid, names) {
  if (!cid || !names || !names.length) return;
  try { localStorage.setItem(catalogKey(cid), JSON.stringify(names)); } catch (e) {}
}
function loadModelCatalog(cid) {
  try { var v = localStorage.getItem(catalogKey(cid)); var a = v ? JSON.parse(v) : null; return Array.isArray(a) ? a : null; } catch (e) { return null; }
}

/* ---------- history search ---------- */
var searchTimer = null;
function openSearch(q) {
  $("searchMask").classList.add("show");
  $("searchInput").value = q || "";
  searchRender();
  setTimeout(function () { try { $("searchInput").focus(); } catch (e) {} }, 80);
}
function closeSearch() { $("searchMask").classList.remove("show"); }
function searchDebounced() { clearTimeout(searchTimer); searchTimer = setTimeout(searchRender, 220); }
function fmtTime(ts) {
  try {
    var d = new Date(Number(ts) || 0), p = function (n) { return (n < 10 ? "0" : "") + n; };
    return (d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
  } catch (e) { return ""; }
}
function searchRender() {
  var q = $("searchInput").value.trim();
  var list = $("searchList"), info = $("searchInfo");
  list.innerHTML = "";
  if (!q) { info.textContent = "输入关键词，搜索全部联系人的本地聊天记录"; return; }
  var arr = [];
  try { arr = JSON.parse(Android.chatSearch(q, "", "200") || "[]"); } catch (e) { arr = []; }
  info.textContent = arr.length
    ? ("命中 " + arr.length + " 条" + (arr.length >= 200 ? "（仅显示最近 200 条）" : ""))
    : "没有匹配的记录";
  for (var i = 0; i < arr.length; i++) list.appendChild(searchRow(arr[i], q));
}
function searchRow(m, q) {
  var row = document.createElement("div"); row.className = "srrow";
  var head = document.createElement("div"); head.className = "srhead";
  var c = byId(m.cid), who = document.createElement("span"); who.className = "who";
  who.textContent = c ? (c.name || c.url) : m.cid;
  var meta = document.createElement("span");
  meta.textContent = (m.role === "user" ? "我" : (m.role === "bot" ? "agent" : "系统")) + " · " + fmtTime(m.ts);
  head.appendChild(who); head.appendChild(meta);
  var body = document.createElement("div"); body.className = "srtext";
  var t = String(m.text || "").replace(/!\[[^\]]*\]\(data:image[^)]*\)/g, "[图片]").replace(/\s+/g, " ");
  var i0 = t.toLowerCase().indexOf(q.toLowerCase());
  var start = i0 < 0 ? 0 : Math.max(0, i0 - 24);
  var snip = (start > 0 ? "…" : "") + t.slice(start, start + 110) + (t.length > start + 110 ? "…" : "");
  var rel = snip.toLowerCase().indexOf(q.toLowerCase());
  if (rel >= 0) {
    body.appendChild(document.createTextNode(snip.slice(0, rel)));
    var b = document.createElement("b"); b.textContent = snip.slice(rel, rel + q.length);
    body.appendChild(b);
    body.appendChild(document.createTextNode(snip.slice(rel + q.length)));
  } else body.textContent = snip;
  row.appendChild(head); row.appendChild(body);
  row.onclick = function () { closeSearch(); jumpToMsg(m.cid, m.seq); };
  return row;
}

/* ---------- jump to a historical message ---------- */
function jumpToMsg(cid, seq) {
  // vc25: busy 不再拦截（见 openContact 注释）
  if (cid !== state.currentId) openContact(cid);
  setTimeout(function () { showWindow(cid, seq); }, 350);
}
function showWindow(cid, seq) {
  if (state.currentId !== cid) return;
  var arr = [];
  try { arr = JSON.parse(Android.chatWindow(cid, String(seq), "50", "50") || "[]"); } catch (e) { arr = []; }
  if (!arr.length) { toast("这条记录已不存在"); return; }
  $("view").innerHTML = ""; botEl = null;
  renderRows(arr);
  var el = $("view").querySelector('[data-seq="' + seq + '"]');
  if (el) {
    el.classList.add("hit");
    try { el.scrollIntoView({ block: "center" }); } catch (e) { el.scrollIntoView(); }
  }
  $("histText").textContent = "历史定位 · 附近 " + arr.length + " 条（非最新）";
  $("histBanner").classList.add("show");
}
function hideHistBanner() {
  var b = $("histBanner");
  if (b) b.classList.remove("show");
}
