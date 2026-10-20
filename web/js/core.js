'use strict';

/* =========================================================
   RailNet Ops — core runtime
   State polling, event stream, API helpers, navigation,
   autopilot and self-healing. All data comes from the Java
   backend (RailwayWebServer → RailwaySystem).
   ========================================================= */

const STATIONS = ['STATION-01', 'STATION-02', 'STATION-03', 'STATION-04'];
const TRACKS = ['TRACK-01', 'TRACK-02', 'TRACK-03', 'TRACK-04', 'TRACK-05'];
const PLACE = { 'STATION-01': 'Northgate', 'STATION-02': 'Riverside', 'STATION-03': 'Hillcrest', 'STATION-04': 'Harbour' };
const ST_COLOR = { 'STATION-01': '#60a5fa', 'STATION-02': '#34d399', 'STATION-03': '#fbbf24', 'STATION-04': '#c084fc' };
const RANK_COLOR = ['#22d3ee', '#34d399', '#fbbf24', '#c084fc', '#f472b6', '#a3e635', '#60a5fa', '#fb923c'];

const App = {
  state: null,          // latest /api/state
  prev: null,           // previous state (for change detection / animations)
  lastSeq: 0,
  events: [],           // recent station events (Lamport-stamped)
  activity: [],         // human-readable activity feed
  page: null,
  selected: null,       // selected station on the network map
  autoHeal: true,
  failovers: 0,
  listeners: [],        // fn(state, prev) called after each state update
  busyAuto: false,
};

/* ---------------- small utilities ---------------- */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const code = (s) => String(s || '').replace('STATION-', 'S');
const place = (s) => PLACE[s] || s;
const short = (t) => String(t || '').replace('TRACK-', 'T');
const station = (name) => (App.state ? App.state.stations.find((s) => s.name === name) : null);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

/** view('#id').html = markup — only touches the DOM when the markup changes. */
const NULL_VIEW = { set html(_) { /* not on this page */ } };
function view(sel) {
  const el = $(sel);
  if (!el) return NULL_VIEW;
  return { set html(markup) { if (el._markup !== markup) { el.innerHTML = markup; el._markup = markup; } } };
}

function chip(text, cls) { return `<span class="chip ${cls}">${esc(text)}</span>`; }
function info(html) { return `<span class="info">i<span class="tip">${html}</span></span>`; }
function roleChip(s) {
  if (!s.active) return chip('OFFLINE', 'c-red');
  return s.role === 'PRIMARY' ? chip('CONTROL', 'c-cyan') : s.role === 'BACKUP' ? chip('MIRROR', 'c-violet') : chip('STANDBY', 'c-grey');
}

/* ---------------- API ---------------- */

async function api(path, body) {
  const opts = body === undefined
    ? { method: 'GET' }
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const res = await fetch('/api' + path, opts);
  let data;
  try { data = await res.json(); } catch { data = { error: 'Invalid response from server' }; }
  if (!res.ok) throw new Error(data.error || 'HTTP ' + res.status);
  return data;
}

function toast(html, kind = '') {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind === 'ok' ? 'ok-t' : kind === 'bad' ? 'bad-t' : '');
  el.innerHTML = html;
  $('#toasts').appendChild(el);
  while ($('#toasts').children.length > 4) $('#toasts').firstChild.remove();
  setTimeout(() => el.remove(), kind === 'bad' ? 6500 : 4000);
}

async function busy(btn, fn) {
  if (!btn) return fn();
  const label = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '<span class="spin"></span>' + esc(btn.textContent.trim());
  try { return await fn(); } finally { btn.disabled = false; btn.innerHTML = label; }
}

/** POST an action, show the outcome, refresh the state. Returns data or null. */
async function act(btn, path, body = {}, success) {
  try {
    const data = await busy(btn, () => api(path, body));
    const msg = typeof success === 'function' ? success(data) : (success ?? data.message);
    if (msg) toast(msg, 'ok');
    await refresh();
    return data;
  } catch (e) {
    toast('✗ ' + esc(e.message), 'bad');
    return null;
  }
}

/* ---------------- activity feed (human readable) ---------------- */

function describe(e) {
  const t = e.text;
  const who = `<b>${esc(place(e.station))}</b>`;
  if (t.includes('NODE FAILURE')) return { ic: '⚠', c: 'c-red', tx: `${who} went offline` };
  if (t.startsWith('Node recovered')) return { ic: '⟳', c: 'c-green', tx: `${who} is back online` };
  if (t.startsWith('PRIMARY UPDATE')) return { ic: '◆', c: 'c-cyan', tx: `${who} committed ${esc(t.replace('PRIMARY UPDATE ', 'state '))}` };
  if (t.startsWith('PRIMARY initialises')) return { ic: '◆', c: 'c-cyan', tx: `${who} created the network state (v1)` };
  if (t.startsWith('APPLIED')) return { ic: '⇣', c: 'c-violet', tx: `${who} mirrored ${esc(t.split(' ')[1])} and acknowledged` };
  if (t.startsWith('INSTALLED snapshot')) return { ic: '⇣', c: 'c-violet', tx: `${who} caught up to ${esc(t.split(' ')[2])} via state transfer` };
  if (t.includes('PROMOTED TO PRIMARY')) return { ic: '♛', c: 'c-gold', tx: `${who} took over as control centre` };
  if (t.includes('DECLARED FAILED')) return { ic: '♥', c: 'c-red', tx: `${who} declared ${esc(t.match(/STATION-\d+/)?.[0] ? place(t.match(/STATION-\d+/)[0]) : 'a station')} failed` };
  if (t.startsWith('No reply from')) return { ic: '♥', c: 'c-amber', tx: `${who} missed a heartbeat ${esc(t.match(/\(.*\)/)?.[0] || '')}` };
  if (t.startsWith('ELECTION →')) {
    const m = t.replace('ELECTION → ', '');
    if (m.includes('COORDINATOR message sent')) return { ic: '♛', c: 'c-gold', tx: `${who} announced itself coordinator` };
    return { ic: '♛', c: 'c-gold', tx: `${who}: ${esc(m)}` };
  }
  if (t.startsWith('RPC station.getLamportClock')) return null; // load-test bursts stay out of the feed
  if (t.startsWith('REJECTED update from')) return { ic: '⛔', c: 'c-green', tx: `${who} rejected a write with a stale term` };
  if (t.startsWith('STALE TERM')) return { ic: '⇣', c: 'c-amber', tx: `${who} learned it was replaced and stepped down` };
  if (t.includes('[still believes it is primary]')) return { ic: '⚠', c: 'c-amber', tx: `${who} wrote as if it were still the control centre` };
  if (t.startsWith('MUTEX ENTER')) return { ic: '🚆', c: 'c-green', tx: `${esc(t.split(' : ')[1].split(' ')[0])} entered Hillcrest Tunnel (granted by every station)` };
  if (t.startsWith('MUTEX REQUEST ')) return t.includes('→ all') ? { ic: '✋', c: 'c-amber', tx: `${who} asked every station for Hillcrest Tunnel` } : null;
  if (t.startsWith('MUTEX DEFER')) return { ic: '⏸', c: 'c-amber', tx: `${who} deferred its reply to ${esc(place(t.match(/STATION-\d+/)[0]))}` };
  if (t.startsWith('MUTEX')) return null;
  if (t.startsWith('RMI ')) return { ic: '⇄', c: 'c-cyan', tx: `${who} answered a remote call ${esc(t.split(' ')[1])}` };
  if (t.startsWith('CLOCK SYNC')) return { ic: '⏱', c: 'c-cyan', tx: `${who} synchronised its clock with GPS (${esc(t.split('→ ')[1] || '')})` };
  if (t.startsWith('BERKELEY')) return { ic: '⏱', c: 'c-gold', tx: `${who} ran a Berkeley clock round` };
  if (t.startsWith('CLOCK ADJUST')) return { ic: '⏱', c: 'c-grey', tx: `${who} adjusted its clock ${esc(t.split(' ')[2])} ms` };
  if (t.startsWith('RPC ')) return { ic: '⇄', c: 'c-cyan', tx: `${who} served ${esc(t.split(' ')[1])}` };
  if (t.startsWith('SEND')) return { ic: '✉', c: 'c-violet', tx: `${who} sent ${esc(t.replace('SEND ', ''))}` };
  if (t.startsWith('RECEIVE')) return { ic: '✉', c: 'c-violet', tx: `${who} received ${esc(t.split(' (')[0].replace('RECEIVE ', ''))}` };
  if (t.startsWith('LOCAL EVENT')) return { ic: '•', c: 'c-grey', tx: `${who}: ${esc(t.replace('LOCAL EVENT: ', ''))}` };
  return null; // noisy protocol detail (REPLICATE, heartbeat ACKs, …) stays in the logs
}

function pushActivity(item) {
  App.activitySeq = (App.activitySeq || 0) + 1;
  App.activity.push({ ...item, id: App.activitySeq });
  if (App.activity.length > 120) App.activity.splice(0, App.activity.length - 120);
}

/* ---------------- state & events ---------------- */

function applyState(s) {
  App.prev = App.state;
  App.state = s;
  setSync();
  for (const fn of App.listeners) { try { fn(s, App.prev); } catch (e) { console.error(e); } }
  if (App.page && App.page.update) App.page.update();
}

async function refresh() {
  try {
    applyState(await api('/state'));
  } catch {
    const pill = $('#sync-pill');
    pill.className = 'sync-pill bad';
    pill.lastElementChild.textContent = 'backend offline';
  }
}

function setSync() {
  const s = App.state;
  const pill = $('#sync-pill');
  const primary = station(s.primary);
  const mirrors = s.backups.map(station).filter((m) => m && m.active && m.version === primary.version).length;
  let cls = 'ok'; let text = `in sync · v${s.version} · ${mirrors} mirror${mirrors === 1 ? '' : 's'}`;
  if (!s.primaryActive) { cls = 'bad'; text = 'control centre offline'; }
  else if (mirrors === 0) { cls = 'warn'; text = `v${s.version} · no mirror`; }
  pill.className = 'sync-pill ' + cls;
  pill.lastElementChild.textContent = text;
}

const logsBody = () => $('#logs-body');
function logClass(line) {
  if (/^[=*-]{10,}/.test(line)) return 'l-rule';
  if (line.includes('✗') || line.includes('!!!') || line.includes('FAILED')) return 'l-bad';
  if (line.includes('⚠')) return 'l-warn';
  if (line.includes('✓')) return 'l-ok';
  if (/^[A-Z0-9 ·&→:().-]{8,}$/.test(line.trim())) return 'l-head';
  return '';
}

async function pollEvents() {
  try {
    ingest(await api('/events?since=' + App.lastSeq));
  } catch { /* offline handled by refresh */ }
}

/** Adds new feed entries (from polling or the live stream), skipping ones already seen. */
function ingest(data) {
  if (data.lastSeq < App.lastSeq) App.lastSeq = 0; // server restarted: numbering starts again
  const body = logsBody();
  let added = false;
  for (const e of data.entries) {
    if (e.seq <= App.lastSeq) continue;
    if (e.kind === 'station') {
      App.events.push(e);
      const d = describe(e);
      if (d) pushActivity({ ...d, time: e.time, lamport: e.lamport, station: e.station });
    } else {
      const span = document.createElement('span');
      span.className = logClass(e.text);
      span.textContent = e.text + '\n';
      body.appendChild(span);
      added = true;
    }
  }
  if (App.events.length > 600) App.events.splice(0, App.events.length - 600);
  while (body.childNodes.length > 2000) body.removeChild(body.firstChild);
  if (added && $('#logs-follow').checked) body.scrollTop = body.scrollHeight;
  App.lastSeq = Math.max(App.lastSeq, data.lastSeq);
}

/* ---------------- live stream (Server-Sent Events) ----------------
   The server pushes state changes and new events as they happen.
   While the stream is connected, polling is skipped; if it drops,
   polling takes over until the browser reconnects. */
function connectLive() {
  if (!window.EventSource) return;
  const es = new EventSource('/api/stream');
  App.live = es;
  es.addEventListener('state', (m) => { App.liveAt = Date.now(); applyState(JSON.parse(m.data)); });
  es.addEventListener('feed', (m) => { App.liveAt = Date.now(); ingest(JSON.parse(m.data)); });
  es.onopen = () => { App.liveOpen = true; pollEvents(); };
  es.onerror = () => { App.liveOpen = false; };
}
const liveHealthy = () => App.liveOpen && App.live && App.live.readyState === 1;

/* ---------------- autopilot & self-healing ---------------- */

/** A train movement that is valid right now (or, sometimes, a deliberate conflict). */
function plannedMovement({ allowConflict = false } = {}) {
  const tracks = App.state.tracks;
  const t = pick(tracks);
  if (t.train) {
    if (allowConflict && Math.random() < 0.25) return { train: '', movement: 'ARRIVAL', track: t.track };
    return { train: t.train, movement: 'DEPARTURE', track: t.track };
  }
  return { train: '', movement: 'ARRIVAL', track: t.track };
}

async function healIfNeeded() {
  const s = App.state;
  if (!s || s.primaryActive || !App.autoHeal || App.busyAuto || Date.now() < (App.healPausedUntil || 0)) return false;
  App.busyAuto = true;
  try {
    await sleep(1200); // a moment for the incident to be visible
    const d = await api('/fault/detect', {});
    if (d.newPrimary) {
      toast(`♛ <b>${esc(place(d.newPrimary))}</b> took over as control centre (automatic failover)`, 'ok');
    }
    await refresh();
    if (App.state.client.pending.length) {
      await sleep(600);
      await api('/fault/retry', {});
      toast('↻ Unanswered requests retried safely (no duplicates)', 'ok');
      await refresh();
    }
    return true;
  } catch (e) {
    toast('✗ Self-healing: ' + esc(e.message), 'bad');
    App.healPausedUntil = Date.now() + 10000; // do not retry immediately
    return false;
  } finally {
    App.busyAuto = false;
  }
}

async function autopilotTick() {
  if (!$('#autopilot').checked || App.busyAuto || !App.state) return;
  if (!App.state.primaryActive) { await healIfNeeded(); return; }
  App.busyAuto = true;
  try {
    const roll = Math.random();
    if (roll < 0.62) {
      const n = Math.random() < 0.5 ? 1 : 2;
      const reqs = [];
      for (let i = 0; i < n; i++) reqs.push(plannedMovement({ allowConflict: true }));
      const d = await api('/dispatch', { requests: reqs });
      showDispatch(d); // map packets + activity entries (pages.js)
    } else {
      const m = plannedMovement();
      const d = await api('/fault/request', m);
      const a = d.attempt;
      pushActivity({ ic: '◆', c: 'c-cyan', time: new Date().toTimeString().slice(0, 8), lamport: '', tx: `Request <b>${esc(a.requestId)}</b> ${esc(a.status.toLowerCase().replace('_', ' '))} · ${esc(a.trainId)} ${esc(a.movement.toLowerCase())} ${esc(a.trackId)}` });
    }
    await refresh();
  } catch { /* ignore a failed tick */ } finally {
    App.busyAuto = false;
  }
}

/* ---------------- logs drawer, clock ---------------- */

$('#logs-btn').addEventListener('click', () => {
  const open = $('#logs').classList.toggle('open');
  document.body.classList.toggle('logs-open', open);
  $('#logs-btn').classList.toggle('on', open);
  if (open) logsBody().scrollTop = logsBody().scrollHeight;
});
$('#logs-close').addEventListener('click', () => { $('#logs').classList.remove('open'); $('#logs-btn').classList.remove('on'); document.body.classList.remove('logs-open'); });
$('#logs-clear').addEventListener('click', () => { logsBody().innerHTML = ''; });
setInterval(() => { $('#clock').textContent = new Date().toTimeString().slice(0, 8); }, 500);
$('#autopilot').addEventListener('change', (e) => toast(e.target.checked ? '▶ Autopilot on: trains will keep moving' : '■ Autopilot off'));

/* ---------------- router ---------------- */

const PAGES = [];
function page(def) { PAGES.push(def); }

function buildTabs() {
  $('#tabs').innerHTML = PAGES.map((p) => `<a class="tab" data-id="${p.id}" href="#/${p.id}">${p.icon}<span>${esc(p.title)}</span></a>`).join('');
}

function navigate() {
  const id = location.hash.replace('#/', '') || 'network';
  const next = PAGES.find((p) => p.id === id) || PAGES[0];
  if (App.page && App.page.leave) App.page.leave();
  App.page = next;
  $$('.tab').forEach((a) => a.classList.toggle('active', a.dataset.id === next.id));
  document.title = next.title + ' · RailNet Ops';
  $('#view').innerHTML = next.render();
  if (next.mount) next.mount();
  if (App.state && next.update) next.update();
  window.scrollTo(0, 0);
}

/* Buttons declare data-act="name"; handlers live in ACTIONS (pages.js). */
const ACTIONS = {};
document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-act]');
  if (!b || b.disabled) return;
  const fn = ACTIONS[b.dataset.act];
  if (fn) { e.preventDefault(); fn(b, e); }
});

async function boot() {
  buildTabs();
  await refresh();
  await pollEvents();
  window.addEventListener('hashchange', navigate);
  navigate();
  connectLive();
  setInterval(async () => {
    if (!liveHealthy()) { await pollEvents(); await refresh(); }
    healIfNeeded();
  }, 1400);
  setInterval(autopilotTick, 4200);
}
