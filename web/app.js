'use strict';

/* =========================================================
   RailCoord — front end for the Distributed Railway
   Coordination System. Every action calls the Java backend
   (RailwayWebServer → RailwaySystem → experiment classes).
   ========================================================= */

const STATIONS = ['STATION-01', 'STATION-02', 'STATION-03', 'STATION-04'];
const TRACKS = ['TRACK-01', 'TRACK-02', 'TRACK-03', 'TRACK-04', 'TRACK-05'];
const STATION_COLOR = { 'STATION-01': '#2563eb', 'STATION-02': '#0d9488', 'STATION-03': '#d97706', 'STATION-04': '#7c3aed' };
const RANK_COLOR = ['#2563eb', '#0d9488', '#d97706', '#7c3aed', '#db2777', '#65a30d', '#0891b2', '#9333ea'];

let S = null;                 // latest system state from /api/state
let lastSeq = 0;              // last event sequence number received
let stationEvents = [];       // recent station events (with Lamport timestamps)
let currentPage = null;
let threadsInfo = null;

// Page-local memory (kept while the browser tab is open)
const memory = {
  rpcHistory: [],
  rpcLast: null,
  lamportLog: [],
  lamportLast: null,
  election: null,
  replicationLog: null,
  replicationHistory: [],
  concurrency: null,
  faultLog: [],
  sweep: [],
};

/* ---------------- utilities ---------------- */

const $ = (sel, root = document) => root.querySelector(sel);

/** view('#id').html = markup → only touches the DOM when the markup changed (no flicker). */
const NULL_VIEW = { set html(_) { /* element not on this page */ } };
function view(sel) {
  const el = $(sel);
  if (!el) return NULL_VIEW;
  return { set html(markup) { if (el._markup !== markup) { el.innerHTML = markup; el._markup = markup; } } };
}
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const num = (v) => (typeof v === 'number' ? (Number.isInteger(v) ? v : Math.round(v * 100) / 100) : v);
const stationColor = (s) => STATION_COLOR[s] || '#334155';
const shortSt = (s) => String(s || '').replace('STATION-', 'S');

async function api(path, body) {
  const options = body === undefined
    ? { method: 'GET' }
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const res = await fetch('/api' + path, options);
  let data;
  try { data = await res.json(); } catch { data = { error: 'Invalid response from server' }; }
  if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}

function toast(message, kind = '') {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind === 'ok' ? 'ok-t' : kind === 'bad' ? 'bad-t' : '');
  el.innerHTML = message;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), kind === 'bad' ? 6500 : 4200);
}

async function busy(btn, fn) {
  if (!btn) return fn();
  const original = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '<span class="spin"></span>' + btn.textContent.trim();
  try { return await fn(); } finally { btn.disabled = false; btn.innerHTML = original; }
}

/** Calls an action endpoint, shows a toast, refreshes the state. */
async function act(btn, path, body = {}, success) {
  try {
    const data = await busy(btn, () => api(path, body));
    const msg = typeof success === 'function' ? success(data) : (success || data.message);
    if (msg) toast(msg, 'ok');
    await refreshState();
    return data;
  } catch (e) {
    toast('✗ ' + esc(e.message), 'bad');
    return null;
  }
}

function pill(text, cls) { return `<span class="pill ${cls}">${esc(text)}</span>`; }
function rolePill(role) {
  return role === 'PRIMARY' ? pill('PRIMARY', 'p-green') : role === 'BACKUP' ? pill('BACKUP', 'p-blue') : pill('NO REPLICA', 'p-grey');
}
function stationByName(name) { return S ? S.stations.find((s) => s.name === name) : null; }

function concept({ tag, title, paras, terms }) {
  return `<section class="concept">
    <div><span class="tag">${esc(tag)}</span><h2>${esc(title)}</h2>${paras.map((p) => `<p>${p}</p>`).join('')}</div>
    <div class="terms">${terms.map(([b, s]) => `<div class="term"><b>${esc(b)}</b><span>${s}</span></div>`).join('')}</div>
  </section>`;
}

function card(title, sub, body, extra = '') {
  return `<section class="card"><div class="card-head"><h3>${title}</h3>${sub ? `<span class="sub">${sub}</span>` : ''}${extra ? `<span class="spacer"></span>${extra}` : ''}</div>${body}</section>`;
}

function stationOptions(selected) {
  return STATIONS.map((s) => `<option ${s === selected ? 'selected' : ''}>${s}</option>`).join('');
}
function trackOptions(selected) {
  return TRACKS.map((t) => `<option ${t === selected ? 'selected' : ''}>${t}</option>`).join('');
}

function jsonHtml(value) {
  const text = JSON.stringify(value, null, 2) ?? '';
  return esc(text)
    .replace(/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="k">$1</span>$2')
    .replace(/:\s(&quot;.*?&quot;)/g, ': <span class="s">$1</span>')
    .replace(/:\s(-?\d+\.?\d*)/g, ': <span class="n">$1</span>')
    .replace(/:\s(true|false|null)/g, ': <span class="b">$1</span>');
}

function eventLines(events) {
  if (!events || !events.length) return '<span class="lt">(no station events)</span>';
  return events.map((e) => `<span class="lt">[${esc(e.time)}]</span> <span class="ls" style="color:${stationColor(e.station)}">[${esc(e.station)}]</span> <span class="ll">[L=${e.lamport}]</span> ${esc(e.text)}`).join('\n');
}

/* ---------------- shared components ---------------- */

function stationCards({ actions = true, overload = false, showNext = false, compact = false } = {}) {
  if (!S) return '';
  return `<div class="stations${compact ? ' cols2' : ''}">${S.stations.map((s) => {
    const cls = ['station', s.active ? '' : 'down', s.role === 'PRIMARY' ? 'primary' : s.role === 'BACKUP' ? 'backup' : ''].join(' ');
    const next = showNext && S.nextInRotation === s.name ? `<span class="next-tag">${pill('◀ NEXT IN ROTATION', 'p-blue')}</span>` : '';
    return `<div class="${cls}">${next}
      <div class="st-top"><span class="status-dot"></span><span class="st-name">${s.name}</span>${s.coordinator ? '<span class="crown" title="Coordinator">♛</span>' : ''}<span class="spacer"></span><span class="muted small-text">ID ${s.id}</span></div>
      <div class="st-badges">${rolePill(s.role)}${s.active ? pill('UP', 'p-green') : pill('DOWN', 'p-red')}${s.overloaded ? pill('OVERLOADED', 'p-amber') : ''}${s.coordinator ? pill('COORDINATOR', 'p-purple') : ''}</div>
      <div class="st-stats"><span>Lamport <b>L=${s.lamport}</b></span><span>Replica <b>v${s.version}</b></span><span>Events <b>${s.events}</b></span><span>Trains <b>${s.assigned}</b></span></div>
      ${actions ? `<div class="st-actions">${s.active
        ? `<button class="btn small danger" data-act="fail" data-station="${s.name}">Crash</button>`
        : `<button class="btn small success" data-act="recover" data-station="${s.name}">Recover</button>`}
        ${overload ? `<button class="btn small" data-act="overload" data-station="${s.name}">${s.overloaded ? 'Clear overload' : 'Overload'}</button>` : ''}</div>` : ''}
    </div>`;
  }).join('')}</div>`;
}

function trackBoard() {
  if (!S) return '';
  return `<div class="tracks">${S.tracks.map((t) => `<div class="track">
    <span class="track-name">${t.track}</span>
    <div class="rail">${t.train ? `<span class="train-chip">🚆 ${esc(t.train)}</span>` : ''}</div>
    <span>${t.state === 'OCCUPIED' ? pill('OCCUPIED', 'p-red') : pill('CLEAR', 'p-green')}</span>
  </div>`).join('')}</div>`;
}

function eventFeed(events, max = 14) {
  const list = events.slice(-max).reverse();
  if (!list.length) return '<div class="empty">No station events yet. Try an action.</div>';
  return `<div class="feed">${list.map((e) => `<div class="feed-item">
      <span class="t">${esc(e.time)}</span><span class="s" style="color:${stationColor(e.station)}">${esc(e.station)}</span><span class="l">L=${e.lamport}</span><span>${esc(e.text)}</span></div>`).join('')}</div>`;
}

function gantt(processing) {
  if (!processing || !processing.length) return '<div class="empty">No parallel processing yet.</div>';
  const toMs = (t) => { const [h, m, s] = t.split(':'); return ((+h * 60 + +m) * 60 + parseFloat(s)) * 1000; };
  const start = Math.min(...processing.map((p) => toMs(p.startedAt)));
  const end = Math.max(...processing.map((p) => toMs(p.finishedAt)));
  const span = Math.max(1, end - start);
  const workers = [...new Set(processing.map((p) => p.worker))].sort();
  return `<div class="gantt">${workers.map((w) => `<div class="g-row"><span class="mono">${esc(w)}</span><div class="g-lane">${processing.filter((p) => p.worker === w).map((p) => {
      const left = ((toMs(p.startedAt) - start) / span) * 100;
      const width = Math.max(4, ((toMs(p.finishedAt) - toMs(p.startedAt)) / span) * 100);
      return `<div class="g-job" style="left:${left}%;width:${width}%;background:${stationColor(p.station)}" title="${esc(p.train)} ${esc(p.startedAt)} → ${esc(p.finishedAt)}">${esc(p.train)}</div>`;
    }).join('')}</div></div>`).join('')}
    <div class="g-axis"><span>0 ms</span><span>${Math.round(span / 2)} ms</span><span>${Math.round(span)} ms</span></div></div>`;
}

function bars(rows, max, cls = '') {
  const m = Math.max(1, max ?? Math.max(...rows.map((r) => r[1])));
  return `<div class="bars">${rows.map(([k, v, note, c]) => `<div class="bar-row"><span class="mono">${esc(k)}</span><div class="bar-track"><div class="bar-fill ${c || cls}" style="width:${(v / m) * 100}%"></div></div><span><b>${num(v)}</b> ${note ? `<span class="muted small-text">${esc(note)}</span>` : ''}</span></div>`).join('')}</div>`;
}

/* ---------------- network map (overview) ---------------- */

function networkMap() {
  if (!S) return '';
  const pos = { 'STATION-01': [130, 92], 'STATION-02': [470, 92], 'STATION-03': [470, 262], 'STATION-04': [130, 262] };
  const primary = stationByName(S.primary);
  let edges = '';
  for (const b of S.backups) {
    const bs = stationByName(b);
    if (!bs) continue;
    const [x1, y1] = pos[S.primary];
    const [x2, y2] = pos[b];
    const live = primary.active && bs.active;
    edges += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${live ? '#16a34a' : '#cbd5e1'}" stroke-width="3" class="${live ? 'flow-anim' : ''}"/>`;
  }
  const nodes = S.stations.map((s) => {
    const [x, y] = pos[s.name];
    const fill = !s.active ? '#fde2e2' : s.role === 'PRIMARY' ? '#dcfce7' : s.role === 'BACKUP' ? '#e0ebff' : '#f1f5f9';
    const stroke = !s.active ? '#dc2626' : s.role === 'PRIMARY' ? '#16a34a' : s.role === 'BACKUP' ? '#2563eb' : '#94a3b8';
    return `<g>
      ${s.role === 'PRIMARY' && s.active ? `<circle cx="${x}" cy="${y}" r="52" fill="none" stroke="#16a34a" stroke-opacity=".25" stroke-width="8"/>` : ''}
      <circle cx="${x}" cy="${y}" r="44" fill="${fill}" stroke="${stroke}" stroke-width="3"/>
      <text x="${x}" y="${y - 8}" text-anchor="middle" font-size="13" font-weight="700" fill="#0f2340">${s.name}</text>
      <text x="${x}" y="${y + 9}" text-anchor="middle" font-size="11" font-weight="700" fill="${stroke}">${s.active ? (s.role === 'NONE' ? 'NO REPLICA' : s.role) : 'DOWN'}</text>
      <text x="${x}" y="${y + 24}" text-anchor="middle" font-size="11" fill="#475569" font-family="Consolas, monospace">v${s.version} · L=${s.lamport}</text>
      ${s.coordinator ? `<text x="${x}" y="${y - 52}" text-anchor="middle" font-size="18" fill="#d4a00a">♛</text>` : ''}
      ${!s.active ? `<path d="M${x - 16} ${y - 16} L${x + 16} ${y + 16} M${x + 16} ${y - 16} L${x - 16} ${y + 16}" stroke="#dc2626" stroke-width="4" opacity=".55"/>` : ''}
    </g>`;
  }).join('');
  return `<div class="netmap"><svg viewBox="0 0 600 354" role="img" aria-label="Railway network map">
    <rect x="58" y="40" width="484" height="274" rx="120" fill="none" stroke="#cbd5e1" stroke-width="10"/>
    <rect x="58" y="40" width="484" height="274" rx="120" fill="none" stroke="#f8fafc" stroke-width="4" stroke-dasharray="3 13"/>
    ${edges}${nodes}
    <text x="300" y="186" text-anchor="middle" font-size="12" fill="#64748b">replication: primary → backups</text>
  </svg></div>
  <div class="legend mt-s"><span><i style="background:#16a34a"></i>Primary</span><span><i style="background:#2563eb"></i>Backup</span><span><i style="background:#94a3b8"></i>No replica</span><span><i style="background:#dc2626"></i>Down</span><span>♛ Coordinator</span></div>`;
}

/* =========================================================
   PAGES
   ========================================================= */

const PAGES = [];
function page(def) { PAGES.push(def); }

/* ---------------- Overview ---------------- */

page({
  id: 'overview', num: '', title: 'Overview', eyebrow: 'Distributed Railway Coordination System',
  render: () => `
    <section class="hero">
      <h2>Four railway stations, one distributed system</h2>
      <p>Every panel on this site is driven by the real Java backend: RPC calls, worker threads, Lamport clocks, elections, replication, load balancing, Apache Spark and MPI processes. Crash a station and watch the system react.</p>
      <div class="hero-kpis" id="ov-kpis"></div>
    </section>
    <div class="grid g-wide mt">
      ${card('Network map', 'live roles, replicas and Lamport clocks', '<div id="ov-map"></div>')}
      ${card('Track state', 'authoritative copy on the primary', '<div id="ov-tracks"></div>')}
    </div>
    <div class="mt">${card('Stations', 'crash or recover a station from any page', '<div id="ov-stations"></div>')}</div>
    <div class="grid g-wide mt">
      ${card('Experiments', 'ten distributed-systems concepts in one project', `<div class="exp-grid">${PAGES.filter((p) => p.num).map((p) => `<a class="exp-card" href="#/${p.id}"><div class="n">EXPERIMENT ${p.num}</div><h4>${esc(p.title)}</h4><p>${esc(p.blurb)}</p></a>`).join('')}</div>`)}
      ${card('Live station events', 'physical time · station · Lamport time', '<div id="ov-feed"></div>')}
    </div>`,
  refresh: () => {
    const up = S.stations.filter((s) => s.active).length;
    view('#ov-kpis').html = [
      [`${up}/4`, 'stations up'], [S.coordinator || 'none', 'coordinator'], [S.primary, 'primary'],
      ['v' + S.version, 'replicated state'], [lastSeq, 'events logged'], [S.client.committed, 'client requests committed'],
    ].map(([b, s]) => `<div class="hk"><b>${esc(b)}</b><span>${esc(s)}</span></div>`).join('');
    view('#ov-map').html = networkMap();
    view('#ov-tracks').html = trackBoard();
    view('#ov-stations').html = stationCards({ actions: true });
    view('#ov-feed').html = eventFeed(stationEvents, 16);
  },
});

/* ---------------- 01 RPC ---------------- */

const RPC_METHODS = {
  'station.ping': 'Is the station alive? (returns its Lamport time)',
  'station.getLamportClock': 'Read the station\'s logical clock',
  'station.getTrackState': 'Read the station\'s replica of the track state',
  'cluster.getCoordinator': 'Which station is the coordinator?',
  'replication.getPrimary': 'Which station is the primary, and its backups',
};

page({
  id: 'rpc', num: '01', group: 'Foundations', title: 'RPC & Client-Server', eyebrow: 'Experiment 01',
  blurb: 'Call procedures on remote stations through a JSON-RPC style API.',
  render: () => `
    ${concept({
      tag: 'Experiment 01', title: 'Client-server and Remote Procedure Call',
      paras: ['In a <b>client-server</b> system the client sends a request and the server returns a response. A <b>Remote Procedure Call (RPC)</b> makes a call on another machine look like an ordinary function call: the arguments are packed into a message (marshalling), sent over the network, executed remotely and the result is sent back.',
        'Here the browser is the client and the Java server routes each call to a station object. A crashed station cannot answer, so the call fails with an error, just like a real remote failure.'],
      terms: [['Request / Response', 'method + parameters in, result or error out'], ['Marshalling', 'arguments converted to JSON to travel over HTTP'], ['Remote failure', 'a crashed station returns error -32000 instead of a result']],
    })}
    <div class="grid g-wide-r mt">
      ${card('Invoke a remote procedure', '', `
        <div class="stack">
          <label class="field">Method<select id="rpc-method">${Object.keys(RPC_METHODS).map((m) => `<option>${m}</option>`).join('')}</select></label>
          <div class="muted small-text" id="rpc-desc"></div>
          <label class="field">Target station<select id="rpc-station">${stationOptions('STATION-02')}</select></label>
          <div class="row"><button class="btn primary" data-act="rpcInvoke">Invoke</button><button class="btn" data-act="rpcAll">Ping all stations</button></div>
          <div class="callout info-box small-text">Crash a station below, then call it again to see the remote error.</div>
        </div>`)}
      ${card('Request → Response', 'JSON over HTTP', `<div class="grid g2"><div><div class="muted small-text">Request</div><pre class="json" id="rpc-req">—</pre></div><div><div class="muted small-text">Response</div><pre class="json" id="rpc-res">—</pre></div></div><div class="row mt-s small-text" id="rpc-meta"></div>`)}
    </div>
    <div class="mt">${card('Stations', '', '<div id="rpc-stations"></div>')}</div>
    <div class="mt">${card('Call history', 'most recent first', '<div id="rpc-history"></div>')}</div>`,
  after: () => {
    const sync = () => {
      const m = $('#rpc-method').value;
      $('#rpc-desc').textContent = RPC_METHODS[m];
      $('#rpc-station').disabled = !m.startsWith('station.');
    };
    $('#rpc-method').addEventListener('change', sync);
    sync();
    renderRpcResult();
  },
  refresh: () => {
    view('#rpc-stations').html = stationCards({ actions: true });
    view('#rpc-history').html = memory.rpcHistory.length ? `<div class="table-wrap"><table><tr><th>#</th><th>Method</th><th>Station</th><th>Result</th><th>Served by thread</th><th>Latency</th></tr>${memory.rpcHistory.slice(0, 12).map((h) => `<tr><td>${h.id}</td><td class="mono">${esc(h.method)}</td><td>${esc(h.station || '—')}</td><td>${h.error ? `<span class="bad">✗ ${esc(h.error)}</span>` : '<span class="ok">✓ result</span>'}</td><td class="mono">${esc(h.servedBy)}</td><td>${h.latency} ms</td></tr>`).join('')}</table></div>` : '<div class="empty">No calls yet.</div>';
  },
});

let rpcId = 1;
async function rpcCall(method, station) {
  const req = { jsonrpc: '2.0', id: rpcId++, method, params: method.startsWith('station.') ? { station } : {} };
  const res = await api('/rpc', { method, station, id: req.id });
  memory.rpcLast = { req, res };
  memory.rpcHistory.unshift({ id: req.id, method, station: req.params.station, error: res.error && res.error.message, servedBy: res.servedBy, latency: res.latencyMs });
  return res;
}
function renderRpcResult() {
  if (!memory.rpcLast || !$('#rpc-req')) return;
  const { req, res } = memory.rpcLast;
  view('#rpc-req').html = jsonHtml(req);
  const shown = { ...res }; delete shown.servedBy; delete shown.latencyMs;
  view('#rpc-res').html = jsonHtml(shown);
  view('#rpc-meta').html = `${res.error ? pill('ERROR ' + res.error.code, 'p-red') : pill('OK', 'p-green')} <span class="muted">served by</span> <b class="mono">${esc(res.servedBy)}</b> <span class="muted">in</span> <b>${res.latencyMs} ms</b>`;
}

/* ---------------- 02 Multithreading ---------------- */

page({
  id: 'threads', num: '02', group: 'Foundations', title: 'Multithreading', eyebrow: 'Experiment 02',
  blurb: 'Thread pools serve concurrent requests; station worker threads run in parallel.',
  render: () => `
    ${concept({
      tag: 'Experiment 02', title: 'Several things at the same time',
      paras: ['A <b>thread</b> is an independent flow of execution inside a process. The web server handles each request on a thread from a <b>pool of 8</b>, so many clients are served at once. Each railway station also owns a <b>worker thread</b> that processes its train requests in parallel with the other stations.',
        'Shared data (track state, Lamport clocks, event logs) is protected with <b>synchronized</b> methods so threads never corrupt it.'],
      terms: [['Thread pool', 'http-worker-1 … 8 reuse threads instead of creating one per request'], ['Worker thread', 'STATION-0x-worker processes that station\'s queue'], ['Synchronization', 'locks make shared updates safe']],
    })}
    <div class="grid g2 mt">
      ${card('Concurrent requests → thread pool', '', `
        <div class="row"><label class="field">Simultaneous requests<select id="th-n"><option>8</option><option selected>16</option><option>32</option></select></label><span class="spacer"></span><button class="btn primary" data-act="threadsFire">Fire concurrent RPC calls</button></div>
        <div id="th-conc" class="mt"></div>`)}
      ${card('Parallel station workers', 'train requests processed at the same time', `
        <div class="row"><button class="btn primary" data-act="dispatchDemo">Dispatch a batch of trains</button><span class="muted small-text">uses the load balancer (Experiment 06)</span></div>
        <div id="th-gantt" class="mt"></div>`)}
    </div>
    <div class="mt">${card('Live JVM threads', 'refreshed automatically', '<div id="th-live"></div>')}</div>`,
  refresh: async () => {
    const conc = memory.concurrency;
    view('#th-conc').html = conc ? `
      <div class="kpis"><div class="kpi"><div class="label">Requests</div><div class="value">${conc.n}</div></div><div class="kpi"><div class="label">Wall time</div><div class="value">${conc.wall} ms</div></div><div class="kpi"><div class="label">Threads used</div><div class="value">${Object.keys(conc.byThread).length}</div></div></div>
      <div class="mt-s">${bars(Object.entries(conc.byThread).sort().map(([k, v]) => [k.replace('http-', ''), v, 'requests']), null, 'purple')}</div>` : '<div class="empty">Fire a burst of requests to see them spread across server threads.</div>';
    const d = S.results.dispatch;
    view('#th-gantt').html = d ? `${gantt(d.processing)}<div class="callout ok-box mt-s">${d.processing.length} requests finished in <b>${d.elapsedMs} ms</b>; one thread alone would need about <b>${d.serialEstimateMs} ms</b>.</div>` : '<div class="empty">No batch processed yet.</div>';
    try { threadsInfo = await api('/threads'); } catch { /* keep last */ }
    if (threadsInfo && $('#th-live')) {
      view('#th-live').html = `<div class="grid g2"><div>${bars(Object.entries(threadsInfo.groups), null, 'green')}<div class="muted small-text mt-s">${threadsInfo.total} live threads · ${threadsInfo.cpus} CPU cores</div></div>
        <div class="table-wrap" style="max-height:300px;overflow:auto"><table><tr><th>Thread</th><th>Group</th><th>State</th></tr>${threadsInfo.threads.map((t) => `<tr><td class="mono">${esc(t.name)}</td><td>${esc(t.group)}</td><td>${t.state === 'RUNNABLE' ? pill(t.state, 'p-green') : pill(t.state, 'p-grey')}</td></tr>`).join('')}</table></div></div>`;
    }
  },
});

/* ---------------- 03 Lamport ---------------- */

page({
  id: 'lamport', num: '03', group: 'Foundations', title: 'Lamport Clocks', eyebrow: 'Experiment 03',
  blurb: 'Logical timestamps order events without a shared clock.',
  render: () => `
    ${concept({
      tag: 'Experiment 03', title: 'Ordering events without a global clock',
      paras: ['Stations have no shared clock, and their physical clocks drift. A <b>Lamport logical clock</b> is a counter on each station that captures the <b>happened-before</b> relation: if event a caused event b, then L(a) &lt; L(b).',
        'Every station event in this system carries its Lamport time, including replication messages, elections and heartbeats.'],
      terms: [['Local event', 'L = L + 1'], ['Send', 'L = L + 1, and the message carries L'], ['Receive', 'L = max(L, L<sub>msg</sub>) + 1']],
    })}
    <div class="mt">${card('Lamport clocks right now', '', '<div id="lp-clocks" class="kpis"></div>')}</div>
    <div class="grid g-wide-r mt">
      <div class="stack">
        ${card('Local event', '', `<div class="row"><select id="lp-local">${stationOptions('STATION-01')}</select><input type="text" id="lp-local-label" value="signal check" style="flex:1"><button class="btn primary" data-act="lamportLocal">Run</button></div>`)}
        ${card('Send a message', '', `<div class="row"><select id="lp-from">${stationOptions('STATION-01')}</select><span>→</span><select id="lp-to">${stationOptions('STATION-03')}</select></div><div class="row mt-s"><input type="text" id="lp-label" value="route update" style="flex:1"><button class="btn primary" data-act="lamportSend">Send</button></div><div id="lp-rule" class="mt-s"></div>`)}
      </div>
      ${card('Space-time diagram', 'events you trigger on this page', '<div id="lp-diagram" class="diagram"></div>')}
    </div>
    <div class="mt">${card('Total order of recent events', 'sorted by (Lamport time, station ID)', '<div id="lp-order"></div>')}</div>`,
  after: () => renderLamportRule(),
  refresh: () => {
    view('#lp-clocks').html = S.stations.map((s) => `<div class="kpi" style="border-top:4px solid ${stationColor(s.name)}"><div class="label">${s.name} ${s.active ? '' : pill('DOWN', 'p-red')}</div><div class="value">L = ${s.lamport}</div></div>`).join('');
    view('#lp-diagram').html = lamportDiagram();
    const order = [...stationEvents].slice(-24).sort((a, b) => a.lamport - b.lamport || a.station.localeCompare(b.station));
    view('#lp-order').html = order.length ? `<div class="table-wrap" style="max-height:320px;overflow:auto"><table><tr><th>Lamport</th><th>Station</th><th>Physical time</th><th>Event</th></tr>${order.map((e) => `<tr><td class="mono"><b>${e.lamport}</b></td><td style="color:${stationColor(e.station)};font-weight:700">${esc(e.station)}</td><td class="mono muted">${esc(e.time)}</td><td>${esc(e.text)}</td></tr>`).join('')}</table></div>` : '<div class="empty">No events yet.</div>';
  },
});

function renderLamportRule() {
  const r = memory.lamportLast;
  if (!r || !$('#lp-rule')) return;
  view('#lp-rule').html = `<div class="callout info-box">Send at <b>L=${r.sendTimestamp}</b> on ${esc(r.from)} · receiver was at L=${r.receiverBefore} · receive rule: <b class="mono">${esc(r.rule)}</b></div>`;
}

function lamportDiagram() {
  const log = memory.lamportLog.slice(-9);
  if (!log.length) return '<div class="empty">Run a local event or send a message to draw the diagram.</div>';
  const xs = { 'STATION-01': 80, 'STATION-02': 250, 'STATION-03': 420, 'STATION-04': 590 };
  const step = 64;
  const h = 70 + log.length * step + 30;
  let body = '';
  STATIONS.forEach((s) => {
    body += `<text x="${xs[s]}" y="24" text-anchor="middle" font-weight="700" font-size="13" fill="${stationColor(s)}">${s}</text><line x1="${xs[s]}" y1="34" x2="${xs[s]}" y2="${h - 10}" stroke="#cbd5e1" stroke-width="2"/>`;
  });
  log.forEach((e, i) => {
    const y = 64 + i * step;
    if (e.type === 'local') {
      body += `<circle cx="${xs[e.station]}" cy="${y}" r="7" fill="${stationColor(e.station)}"/><text x="${xs[e.station] + 13}" y="${y + 4}" font-size="12" font-family="Consolas" fill="#0f2340">L=${e.ts} ${esc(e.label)}</text>`;
    } else {
      const y2 = y + 34;
      body += `<line x1="${xs[e.from]}" y1="${y}" x2="${xs[e.to]}" y2="${y2}" stroke="#334155" stroke-width="1.8" marker-end="url(#arr)"/>
        <circle cx="${xs[e.from]}" cy="${y}" r="7" fill="${stationColor(e.from)}"/><circle cx="${xs[e.to]}" cy="${y2}" r="7" fill="${stationColor(e.to)}"/>
        <text x="${xs[e.from] + (xs[e.to] > xs[e.from] ? -12 : 12)}" y="${y - 9}" font-size="12" text-anchor="${xs[e.to] > xs[e.from] ? 'end' : 'start'}" font-family="Consolas" fill="#0f2340">send L=${e.send}</text>
        <text x="${xs[e.to] + (xs[e.to] > xs[e.from] ? 12 : -12)}" y="${y2 + 4}" font-size="12" text-anchor="${xs[e.to] > xs[e.from] ? 'start' : 'end'}" font-family="Consolas" fill="#0f2340">recv L=${e.recv}</text>`;
    }
  });
  return `<svg viewBox="0 0 700 ${h}"><defs><marker id="arr" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#334155"/></marker></defs>${body}</svg>`;
}

/* ---------------- 04 Election ---------------- */

page({
  id: 'election', num: '04', group: 'Coordination', title: 'Leader Election', eyebrow: 'Experiment 04',
  blurb: 'Bully and Ring algorithms choose a new coordinator after a failure.',
  render: () => `
    ${concept({
      tag: 'Experiment 04', title: 'Choosing a coordinator: Bully and Ring',
      paras: ['One station acts as the <b>coordinator</b> (junction controller). When it fails, the others must agree on a new one. Both algorithms elect the <b>highest-ID live station</b>.',
        'Crash the coordinator, then run either election and watch the messages.'],
      terms: [['Bully', 'the detector sends ELECTION to higher IDs; a higher live node answers OK and takes over; the highest wins and announces COORDINATOR'], ['Ring', 'an ELECTION message travels around a logical ring collecting IDs; the highest ID becomes coordinator'], ['Note', 'the replication primary (Exp. 05) is chosen only among up-to-date replicas']],
    })}
    <div class="grid g-wide mt">
      ${card('Election ring', 'logical ring of live stations', `<div id="el-ring" class="diagram"></div>
        <div class="row mt-s"><button class="btn primary" data-act="election" data-algo="bully">Run Bully election</button><button class="btn primary" data-act="election" data-algo="ring">Run Ring election</button><button class="btn" data-act="heartbeat">Heartbeat round</button></div>`)}
      ${card('Result', '', '<div id="el-result"></div>')}
    </div>
    <div class="mt">${card('Stations', 'crash the coordinator to trigger an election', '<div id="el-stations"></div>')}</div>
    <div class="mt">${card('Election messages', 'station · Lamport time · message', '<div id="el-steps"></div>')}</div>`,
  after: () => renderElectionSteps(),
  refresh: () => {
    view('#el-ring').html = electionRing();
    view('#el-stations').html = stationCards({ actions: true });
    const r = S.results.election;
    view('#el-result').html = r ? `<div class="kpis"><div class="kpi"><div class="label">Algorithm</div><div class="value">${esc(r.algorithm)}</div></div><div class="kpi"><div class="label">New coordinator</div><div class="value">${esc(r.winner)}</div><div class="hint">ID ${r.winnerId}</div></div><div class="kpi"><div class="label">Messages</div><div class="value">${r.messages}</div></div></div>
      <div class="callout ok-box mt-s">♛ ${esc(r.winner)} has the highest ID among live stations.</div>` : '<div class="empty">No election run yet.</div>';
  },
});

function electionRing() {
  const cx = 220; const cy = 150; const R = 105;
  const angle = (i) => (-90 + i * 90) * Math.PI / 180;
  const live = S.stations.filter((s) => s.active);
  let arrows = '';
  // Ring links drawn as clockwise arcs along the circle, trimmed so arrows stop at the nodes
  const trim = 24 * Math.PI / 180;
  if (live.length >= 2) {
    live.forEach((s, i) => {
      const a = S.stations.indexOf(s); const b = S.stations.indexOf(live[(i + 1) % live.length]);
      let steps = (b - a + 4) % 4; if (steps === 0) steps = 4;
      const t1 = angle(a) + trim; const t2 = angle(a) + steps * Math.PI / 2 - trim;
      const [x1, y1] = [cx + R * Math.cos(t1), cy + R * Math.sin(t1)];
      const [x2, y2] = [cx + R * Math.cos(t2), cy + R * Math.sin(t2)];
      const large = (t2 - t1) > Math.PI ? 1 : 0;
      arrows += `<path d="M${x1.toFixed(1)} ${y1.toFixed(1)} A${R} ${R} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)}" fill="none" stroke="#94a3b8" stroke-width="2.2" marker-end="url(#rarr)" class="flow-anim"/>`;
    });
  }
  const nodes = S.stations.map((s, i) => {
    const x = cx + R * Math.cos(angle(i)); const y = cy + R * Math.sin(angle(i));
    const fill = !s.active ? '#fde2e2' : s.coordinator ? '#fef3c7' : '#fff';
    const stroke = !s.active ? '#dc2626' : s.coordinator ? '#d4a00a' : '#64748b';
    return `<circle cx="${x}" cy="${y}" r="36" fill="${fill}" stroke="${stroke}" stroke-width="3"/>
      <text x="${x}" y="${y - 3}" text-anchor="middle" font-size="12" font-weight="700" fill="#0f2340">${shortSt(s.name)}</text>
      <text x="${x}" y="${y + 13}" text-anchor="middle" font-size="11" fill="${stroke}">${s.active ? 'ID ' + s.id : 'DOWN'}</text>
      ${s.coordinator ? `<text x="${x}" y="${y - 42}" text-anchor="middle" font-size="17" fill="#d4a00a">♛</text>` : ''}`;
  }).join('');
  return `<svg viewBox="0 0 440 300"><defs><marker id="rarr" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 z" fill="#94a3b8"/></marker></defs>${arrows}${nodes}<text x="${cx}" y="${cy - 2}" text-anchor="middle" font-size="11" fill="#64748b">coordinator</text><text x="${cx}" y="${cy + 15}" text-anchor="middle" font-size="13" font-weight="700" fill="#b8860b">♛ ${esc(S.coordinator || 'none')}</text></svg>`;
}

function renderElectionSteps() {
  const box = $('#el-steps');
  if (!box) return;
  const ev = memory.election;
  box.innerHTML = ev && ev.length ? `<div class="steps">${ev.map((e, i) => `<div class="step" style="animation-delay:${i * 90}ms"><span class="i">${i + 1}</span><b style="color:${stationColor(e.station)}">${esc(e.station)}</b><span class="mono" style="color:var(--purple)">L=${e.lamport}</span><span>${esc(e.text.replace('ELECTION → ', ''))}</span></div>`).join('')}</div>` : '<div class="empty">Run an election to see every message.</div>';
}

/* ---------------- 05 Replication ---------------- */

page({
  id: 'replication', num: '05', group: 'Coordination', title: 'Consistency & Replication', eyebrow: 'Experiment 05',
  blurb: 'Primary-backup replication with synchronous ACKs and version numbers.',
  render: () => `
    ${concept({
      tag: 'Experiment 05', title: 'Primary-backup replication, strongly consistent',
      paras: ['Only the <b>primary</b> changes the track state. Each update gets the next <b>version number</b> and is sent to every <b>backup</b>; the primary waits for each ACK before the update counts as <b>committed</b> (synchronous replication).',
        'A replica only accepts version = its version + 1, so all replicas apply the same updates in the same order. Consistency is verified by comparing versions, full track tables and checksums.'],
      terms: [['Consistency model', 'strong consistency (single writer + synchronous replication)'], ['Version rule', 'accept only v(current)+1 → no gaps, no reordering'], ['Checksum', 'CRC32 fingerprint of a replica\'s data']],
    })}
    <div class="grid g-wide mt">
      ${card('Replica topology', '', '<div id="rp-topo" class="diagram"></div>')}
      ${card('Update the track state', 'committed on the primary, replicated to backups', `
        <div id="rp-tracks"></div>
        <div class="row mt"><select id="rp-track">${trackOptions('TRACK-03')}</select><select id="rp-status"><option>OCCUPIED</option><option>CLEAR</option></select><input type="text" id="rp-train" placeholder="Train e.g. 210" value="210" style="width:130px"><button class="btn primary" data-act="replicate">Commit &amp; replicate</button></div>`)}
    </div>
    <div class="grid g2 mt">
      ${card('Consistency verification', '', '<div id="rp-consistency"></div>', '<button class="btn small" data-act="verify">Verify now</button>')}
      ${card('Replication protocol', 'last update, step by step', '<div id="rp-log" class="log-box"></div>')}
    </div>
    <div class="mt">${card('Stations', 'crash a backup and update: it is dropped from the group; recover it and it catches up', '<div id="rp-stations"></div>')}</div>`,
  refresh: () => {
    view('#rp-topo').html = topologyDiagram();
    view('#rp-tracks').html = trackBoard();
    view('#rp-stations').html = stationCards({ actions: true });
    const c = S.results.consistency || (S.results.replication && S.results.replication.consistency);
    view('#rp-consistency').html = c ? consistencyTable(c) : '<div class="empty">Press “Verify now”.</div>';
    view('#rp-log').html = memory.replicationLog ? eventLines(memory.replicationLog) : '<span class="lt">Commit an update to see PRIMARY UPDATE → REPLICATE → APPLIED/ACK.</span>';
  },
});

function consistencyTable(c) {
  return `<table><tr><th>Station</th><th>Role</th><th>Version</th><th>Checksum</th><th></th></tr>${c.replicas.map((r) => `<tr><td><b>${esc(r.station)}</b></td><td>${rolePill(r.role)}</td><td class="mono">v${r.version}</td><td class="mono">${esc(r.checksum)}</td><td>${r.identical ? '<span class="ok">✓ identical</span>' : '<span class="bad">✗ differs</span>'}</td></tr>`).join('')}</table>
    <div class="callout ${c.consistent ? 'ok-box' : 'bad-box'} mt-s">${c.consistent ? '✓ ALL REPLICAS CONSISTENT (same version, same track state)' : '✗ REPLICAS INCONSISTENT'}</div>`;
}

function topologyDiagram() {
  const p = stationByName(S.primary);
  const backups = S.backups.map(stationByName);
  const others = S.stations.filter((s) => s.name !== S.primary && !S.backups.includes(s.name));
  const node = (s, x, y, label) => {
    const stroke = !s.active ? '#dc2626' : label === 'PRIMARY' ? '#16a34a' : label === 'BACKUP' ? '#2563eb' : '#94a3b8';
    return `<rect x="${x - 78}" y="${y - 32}" width="156" height="64" rx="12" fill="${!s.active ? '#fff5f5' : '#fff'}" stroke="${stroke}" stroke-width="2.5"/>
      <text x="${x}" y="${y - 8}" text-anchor="middle" font-weight="700" font-size="13" fill="#0f2340">${s.name}</text>
      <text x="${x}" y="${y + 9}" text-anchor="middle" font-size="11" font-weight="700" fill="${stroke}">${s.active ? label : 'DOWN'}</text>
      <text x="${x}" y="${y + 24}" text-anchor="middle" font-size="11" font-family="Consolas" fill="#475569">v${s.version} · ${s.checksum}</text>`;
  };
  const xs = backups.length === 1 ? [260] : backups.length === 2 ? [140, 380] : [80, 260, 440];
  let svg = node(p, 260, 52, 'PRIMARY');
  backups.forEach((b, i) => {
    const live = p.active && b.active;
    svg += `<line x1="260" y1="84" x2="${xs[i]}" y2="156" stroke="${live ? '#16a34a' : '#cbd5e1'}" stroke-width="2.5" class="${live ? 'flow-anim' : ''}"/><text x="${(260 + xs[i]) / 2 + (xs[i] < 260 ? -42 : 8)}" y="124" font-size="11" fill="#16a34a">update + ACK</text>`;
  });
  backups.forEach((b, i) => { svg += node(b, xs[i], 188, 'BACKUP'); });
  const ox = others.length === 1 ? [260] : others.map((_, i) => 140 + i * 240);
  others.forEach((o, i) => { svg += node(o, ox[i], 290, 'NO REPLICA'); });
  return `<svg viewBox="0 0 520 ${others.length ? 330 : 230}">${svg}</svg>`;
}

/* ---------------- 06 Load balancing ---------------- */

page({
  id: 'loadbalancer', num: '06', group: 'Coordination', title: 'Load Balancing', eyebrow: 'Experiment 06',
  blurb: 'Health-aware round-robin spreads train requests over live stations.',
  render: () => `
    ${concept({
      tag: 'Experiment 06', title: 'Health-aware Round-Robin',
      paras: ['Stations take turns: 01 → 02 → 03 → 04 → 01. Before assigning, the balancer <b>skips stations that are FAILED or OVERLOADED</b>, then moves its pointer past the chosen station so healthy stations share the work evenly.',
        'Each station processes its trains on its own worker thread (in parallel). The primary then commits the track changes <b>in request order</b> and rejects unsafe movements (signal held at RED).'],
      terms: [['Round-robin', 'fixed circular order, pointer moves after each assignment'], ['Health check', 'skip FAILED / OVERLOADED stations'], ['Safety', 'arrival needs a CLEAR track; departure needs that train on the track']],
    })}
    <div class="mt">${card('Station pool', 'toggle overload or crash a station, then dispatch', '<div id="lb-stations"></div>')}</div>
    <div class="grid g-wide-r mt">
      ${card('Dispatch train requests', '', `
        <div class="stack">
          <button class="btn primary" data-act="dispatchDemo">Send a demo batch (built from the current tracks)</button>
          <div class="muted small-text">or a single request:</div>
          <div class="row"><input type="text" id="lb-train" placeholder="Train (blank = new)" style="width:150px"><select id="lb-move"><option>ARRIVAL</option><option>DEPARTURE</option></select><select id="lb-track">${trackOptions('TRACK-01')}</select><button class="btn" data-act="dispatchOne">Dispatch</button></div>
          <div id="lb-dist"></div>
        </div>`)}
      ${card('Routing decisions', 'round-robin with health checks', '<div id="lb-routing"></div>')}
    </div>
    <div class="grid g2 mt">
      ${card('Parallel processing', 'station worker threads', '<div id="lb-gantt"></div>')}
      ${card('Primary commits (request order)', 'safety check + replication', '<div id="lb-commits"></div>')}
    </div>`,
  refresh: () => {
    view('#lb-stations').html = stationCards({ actions: true, overload: true, showNext: true });
    const counts = S.stations.map((s) => [s.name, s.assigned, !s.active ? 'down' : s.overloaded ? 'overloaded' : '', !s.active ? 'red' : s.overloaded ? 'amber' : '']);
    view('#lb-dist').html = `<div class="muted small-text mt-s">Total requests assigned per station</div>${bars(counts)}`;
    const d = S.results.dispatch;
    view('#lb-routing').html = d ? `<div class="table-wrap"><table><tr><th>#</th><th>Train</th><th>Movement</th><th>Track</th><th>Routed to</th><th>Skipped</th></tr>${d.decisions.map((x) => `<tr><td>${x.sequence}</td><td class="mono"><b>${esc(x.train)}</b></td><td>${esc(x.movement)}</td><td class="mono">${esc(x.track)}</td><td>${x.station ? `<b style="color:${stationColor(x.station)}">${esc(x.station)}</b>` : '<span class="bad">none available</span>'}</td><td class="small-text">${x.skipped.length ? `<span class="warn">⚠ ${esc(x.skipped.join(', '))}</span>` : '—'}</td></tr>`).join('')}</table></div>` : '<div class="empty">No requests dispatched yet.</div>';
    view('#lb-gantt').html = d ? `${gantt(d.processing)}<div class="callout ok-box mt-s">Finished in <b>${d.elapsedMs} ms</b> (one station alone: ~${d.serialEstimateMs} ms)</div>` : '<div class="empty">—</div>';
    view('#lb-commits').html = d ? `<table><tr><th>#</th><th>Train</th><th>Result</th></tr>${d.commits.map((c) => `<tr><td>${c.sequence}</td><td class="mono">${esc(c.train)}</td><td>${c.accepted ? `<span class="ok">✓ v${c.version}</span> ${esc(c.track)} → ${esc(c.newStatus)} <span class="muted small-text">→ ${esc(c.acknowledgedBy.join(', ') || 'no backup')}</span>` : `<span class="bad">✗ REJECTED</span> <span class="small-text">${esc(c.reason)}</span>`}</td></tr>`).join('')}</table>` : '<div class="empty">—</div>';
  },
});

/* ---------------- 07 MapReduce ---------------- */

page({
  id: 'mapreduce', num: '07', group: 'Data & Reliability', title: 'MapReduce (Apache Spark)', eyebrow: 'Experiment 07',
  blurb: 'Apache Spark analyses the stations\' distributed event logs.',
  render: () => `
    ${concept({
      tag: 'Experiment 07', title: 'MapReduce on the railway event logs',
      paras: ['Every station keeps its own event log. Apache Spark loads each log as one <b>partition</b> and runs <b>Map → Shuffle → Reduce</b> in parallel to answer: <i>how many coordination events happened on each track?</i>',
        'The results come from the live logs, so they change as you use the system. Requests per station are cross-checked against the load balancer.'],
      terms: [['Map', 'TRACK_EVENT → (track, 1); other events emit nothing'], ['Shuffle', 'group the pairs by key (groupByKey)'], ['Reduce', 'sum each group (reduceByKey)']],
    })}
    <div class="mt">${card('Run the Spark job', 'Apache Spark in local mode (4 threads)', `<div class="row"><button class="btn accent" data-act="analytics">Run MapReduce job</button><span class="muted small-text">The first run starts Spark and takes about 10–20 s; later runs are fast.</span></div><div id="mr-meta" class="mt-s"></div>`)}</div>
    <div id="mr-results" class="stack mt"></div>`,
  refresh: () => {
    const a = S.results.analytics;
    if (!a) { view('#mr-results').html = '<div class="empty">Run the job to analyse the current event logs.</div>'; view('#mr-meta').html = ''; return; }
    const r = a.result;
    view('#mr-meta').html = `${pill('Spark ' + r.sparkVersion, 'p-purple')} ${pill(r.totalEvents + ' events', 'p-blue')} ${pill(r.mapOutputPairs + ' map pairs', 'p-teal')} ${pill('completed in ' + a.elapsedMs + ' ms', 'p-green')}`;
    const maxV = Math.max(...Object.values(r.reduced), 1);
    view('#mr-results').html = `
      ${card('1 · Input splits', 'one partition per station log', `<div class="grid g4">${r.partitions.map((p) => `<div class="kpi" style="border-top:4px solid ${stationColor(p.station)}"><div class="label">Partition ${p.partition} ← ${esc(p.station)}.log</div><div class="value">${p.events}</div><div class="hint">events</div></div>`).join('')}</div>`)}
      <div class="grid g3w">
        ${card('2 · Map', 'event → (key, value)', `<table><tr><th>Input event</th><th>Emitted</th></tr>${r.mapSamples.map((m) => `<tr><td class="mono" style="font-size:11.5px">${esc(m.input)}</td><td class="mono" style="white-space:nowrap">${m.key ? `(${esc(m.key)}, ${m.value})` : '<span class="muted">nothing</span>'}</td></tr>`).join('')}</table>`)}
        ${card('3 · Shuffle', 'groupByKey', `<table><tr><th>Key</th><th>Values</th></tr>${Object.entries(r.grouped).map(([k, v]) => `<tr><td class="mono"><b>${esc(k)}</b></td><td class="mono">[${v.join(', ')}]</td></tr>`).join('')}</table>`)}
        ${card('4 · Reduce', 'reduceByKey(+)', `<table><tr><th>Key</th><th>Count</th></tr>${Object.entries(r.reduced).map(([k, v]) => `<tr><td class="mono"><b>${esc(k)}</b></td><td><b>${v}</b></td></tr>`).join('')}</table><div class="callout ${r.reducedTotal === r.mapOutputPairs ? 'ok-box' : 'bad-box'} mt-s">Σ = ${r.reducedTotal} ${r.reducedTotal === r.mapOutputPairs ? '=' : '≠'} ${r.mapOutputPairs} map pairs</div>`)}
      </div>
      <div class="grid g3">
        ${card('Coordination events per track', `busiest: <b>${esc(r.busiestTrack)}</b>`, `<div class="colchart">${Object.entries(r.reduced).map(([k, v]) => `<div class="col"><span class="v">${v}</span><div class="colbar ${k === r.busiestTrack ? 'hot' : ''}" style="height:${(v / maxV) * 140}px"></div><span class="k">${esc(k.replace('TRACK-', 'T'))}</span></div>`).join('')}</div>`)}
        ${card('Track occupations', 'OCCUPIED events only', bars(Object.entries(r.occupations), null, 'purple'))}
        ${card('Requests per station', 'cross-check with the load balancer', `<table><tr><th>Station</th><th>From logs</th><th>Balancer</th><th></th></tr>${Object.entries(r.loadBalancerCounts).map(([k, v]) => `<tr><td><b>${esc(k)}</b></td><td>${r.requestsFromLogs[k]}</td><td>${v}</td><td>${r.requestsFromLogs[k] === v ? '<span class="ok">✓</span>' : '<span class="bad">✗</span>'}</td></tr>`).join('')}</table><div class="callout ${r.matchesLoadBalancer ? 'ok-box' : 'bad-box'} mt-s">${r.matchesLoadBalancer ? '✓ matches the routing decisions' : '✗ differs from the load balancer'}</div>`)}
      </div>`;
  },
});

/* ---------------- 08 Fault tolerance ---------------- */

page({
  id: 'fault', num: '08', group: 'Data & Reliability', title: 'Fault Tolerance', eyebrow: 'Experiment 08',
  blurb: 'Heartbeats, automatic failover and duplicate-safe client retries.',
  render: () => `
    ${concept({
      tag: 'Experiment 08', title: 'Surviving crashes without losing or duplicating requests',
      paras: ['A backup <b>monitors the primary with heartbeats</b>; after <b>3 missed heartbeats</b> the primary is declared failed and the up-to-date backup with the highest ID is promoted (<b>failover</b>).',
        'If the primary crashes after committing but before replying, the client retries with the <b>same request ID</b>. The applied-request table is replicated, so the new primary recognises the retry and does not apply it twice (<b>idempotent retry</b>).'],
      terms: [['Fault model', 'crash faults: a failed node stops responding'], ['Failure detection', '3 consecutive missed heartbeats'], ['Recovery', 'a stale node receives the latest state and rejoins as backup']],
    })}
    <div class="kpis mt" id="ft-kpis"></div>
    <div class="grid g-wide-r mt">
      <div class="stack">
        ${card('Guided scenario', 'click the steps in order', `<div class="scenario" id="ft-scenario"></div>`)}
        ${card('Manual controls', '', `
          <div class="row"><input type="text" id="ft-train" placeholder="Train (blank = new)" style="width:140px"><select id="ft-move"><option>ARRIVAL</option><option>DEPARTURE</option></select><select id="ft-track">${trackOptions('TRACK-01')}</select></div>
          <div class="row mt-s"><label class="check"><input type="checkbox" id="ft-crash"> primary crashes before replying</label><span class="spacer"></span><button class="btn primary" data-act="ftSend">Send request</button></div>
          <div class="row mt-s"><button class="btn" data-act="ftDetect">Run failure detection</button><button class="btn" data-act="ftRetry">Retry pending</button></div>`)}
      </div>
      <div class="stack">
        ${card('Cluster', '', '<div id="ft-stations"></div>')}
        ${card('Failure detection', 'heartbeats from a backup to the primary', '<div id="ft-detect"></div>')}
      </div>
    </div>
    <div class="mt">${card('Client request attempts', 'every try of every request ID', '<div id="ft-history"></div>')}</div>
    <div class="mt">${card('Fault log', 'latest station events: failures, heartbeats, failover, state transfer', '<div id="ft-log" class="log-box"></div>')}</div>`,
  refresh: () => {
    const c = S.client;
    view('#ft-kpis').html = [['Requests sent', c.sent], ['Committed exactly once', c.committed], ['Lost replies', c.timeouts], ['Retries', c.retries], ['Duplicates prevented', c.duplicates], ['Waiting for retry', c.pending.length]]
      .map(([l, v]) => `<div class="kpi"><div class="label">${l}</div><div class="value">${v}</div></div>`).join('');
    view('#ft-stations').html = stationCards({ actions: true, compact: true });
    const d = S.results.detection;
    view('#ft-detect').html = d ? `<div class="row">${pill('monitor ' + d.monitor, 'p-blue')} → ${pill('target ' + d.target, 'p-grey')}</div>
      <div class="callout ${d.declaredFailed ? (d.newPrimary ? 'warn-box' : 'bad-box') : 'ok-box'} mt-s">${d.declaredFailed ? `⚠ ${esc(d.target)} declared FAILED after 3 missed heartbeats.${d.newPrimary ? ` <b>${esc(d.newPrimary)}</b> promoted to primary ✓ no data loss.` : ' ' + esc(d.error || '')}` : `✓ ${esc(d.target)} answered every heartbeat: no failover needed.`}</div>` : '<div class="empty">Not run yet.</div>';
    view('#ft-history').html = c.history.length ? `<div class="table-wrap" style="max-height:330px;overflow:auto"><table><tr><th>Request</th><th>Try</th><th>Train</th><th>Primary</th><th>Status</th><th>Detail</th></tr>${[...c.history].reverse().map((h) => `<tr><td class="mono"><b>${esc(h.requestId)}</b></td><td>${h.attempt}</td><td class="mono small-text" style="white-space:nowrap">${esc(h.trainId)} · ${esc(h.movement)} · ${esc(h.trackId)}</td><td>${esc(h.primary)}</td><td>${{ COMMITTED: pill('COMMITTED v' + h.version, 'p-green'), DUPLICATE: pill('DUPLICATE BLOCKED', 'p-purple'), REJECTED: pill('REJECTED', 'p-amber'), NO_REPLY: pill('NO REPLY', 'p-red') }[h.status]}</td><td class="small-text">${esc(h.detail)}</td></tr>`).join('')}</table></div>` : '<div class="empty">No client requests yet.</div>';
    view('#ft-log').html = stationEvents.length ? eventLines(stationEvents.slice(-40)) : '<span class="lt">Run the scenario to see failures, heartbeats and failover here.</span>';
    renderScenario();
  },
});

const scenarioDone = new Set();
function scenarioSteps() {
  const backup = S.backups.map(stationByName).find((s) => s && s.active);
  const down = S.stations.filter((s) => !s.active).map((s) => s.name);
  return [
    { t: 'Send a request', d: 'normal operation, replicated to all backups', act: 'sc1' },
    { t: `Crash a backup${backup ? ' (' + backup.name + ')' : ''}`, d: 'the system keeps serving with fewer copies', act: 'sc2' },
    { t: 'Send another request', d: 'committed with the remaining backup(s)', act: 'sc1' },
    { t: 'Send a request and crash the primary before it replies', d: 'committed, but the reply is lost', act: 'sc4' },
    { t: 'Run failure detection', d: '3 missed heartbeats → automatic failover', act: 'ftDetect' },
    { t: 'Retry the unanswered request', d: 'same ID → duplicate detected, not applied twice', act: 'ftRetry' },
    { t: `Recover failed stations${down.length ? ' (' + down.join(', ') + ')' : ''}`, d: 'stale replicas catch up and rejoin', act: 'sc7' },
  ];
}
function renderScenario() {
  const box = $('#ft-scenario');
  if (!box) return;
  box.innerHTML = scenarioSteps().map((s, i) => `<div class="sc-step ${scenarioDone.has(i) ? 'done' : ''}"><span class="num">${scenarioDone.has(i) ? '✓' : i + 1}</span><div><b>${esc(s.t)}</b><span>${esc(s.d)}</span></div><button class="btn small" data-act="${s.act}" data-step="${i}">Run</button></div>`).join('');
}
function validMovement() {
  const clear = S.tracks.find((t) => t.state === 'CLEAR');
  if (clear) return { train: '', movement: 'ARRIVAL', track: clear.track };
  const occ = S.tracks.find((t) => t.train);
  return { train: occ.train, movement: 'DEPARTURE', track: occ.track };
}

/* ---------------- 09 MPI collectives ---------------- */

page({
  id: 'mpi', num: '09', group: 'Parallel Computing', title: 'MPI Collectives', eyebrow: 'Experiment 09',
  blurb: 'Broadcast, Scatter and Gather between real MPI processes.',
  render: () => `
    ${concept({
      tag: 'Experiment 09', title: 'Broadcast · Scatter · Gather',
      paras: ['Real MPI processes are started with <b>MPJ Express</b>; each process is a station (rank 0 = root / coordinator). The root broadcasts the <b>live track state</b> from this system, scatters 8 train requests, every process checks its trains, and the root gathers the results.'],
      terms: [['Broadcast', 'the exact same data from the root to every process'], ['Scatter', 'equal chunks of an array, a distinct chunk per process in rank order'], ['Gather', 'chunks collected in rank order into one buffer on the root (inverse of Scatter)']],
    })}
    <div class="mt">${card('Run the MPI job', '', `<div class="row"><label class="field">Processes<select id="mpi-np"><option>1</option><option>2</option><option selected>4</option><option>8</option></select></label><button class="btn accent" data-act="mpiRun">Run with MPJ Express</button><span class="muted small-text" id="mpi-state"></span></div>`)}</div>
    <div id="mpi-results" class="stack mt"></div>`,
  refresh: () => {
    view('#mpi-state').html = `Track state to broadcast: <b class="mono">v${S.version} [${S.tracks.map((t) => (t.train ? t.train.replace('TRAIN-', '') : 0)).join(', ')}]</b>`;
    const out = S.results.collectives;
    if (!out) { view('#mpi-results').html = '<div class="empty">Run the job to start real MPI processes.</div>'; return; }
    const r = out.result;
    const np = r.processes;
    view('#mpi-results').html = `
      <div class="row">${pill(out.command, 'p-grey')} ${pill(np + ' processes', 'p-blue')} ${pill(Math.round(out.elapsedMs) + ' ms', 'p-teal')}</div>
      ${card('1 · Broadcast', 'MPI.COMM_WORLD.Bcast', `<div class="row">${pill('ROOT · rank 0 · STATION-01', 'p-purple')} sends <b class="mono">[${r.state.join(', ')}]</b></div>
        <div class="ranks mt-s" style="grid-template-columns:repeat(${Math.min(np, 4)},1fr)">${r.checksums.map((c, i) => `<div class="rank-box" style="border-color:${RANK_COLOR[i]}"><div class="rk" style="color:${RANK_COLOR[i]}">rank ${i} · STATION-${String(i + 1).padStart(2, '0')}</div>received v${r.state[0]} <span class="mono muted">checksum ${c}</span></div>`).join('')}</div>
        <div class="callout ${r.broadcastVerified ? 'ok-box' : 'bad-box'} mt-s">${r.broadcastVerified ? `✓ BROADCAST VERIFIED: all ${np} processes hold the identical state` : '✗ broadcast mismatch'}</div>`)}
      ${card('2 · Scatter', 'MPI.COMM_WORLD.Scatter', `<div class="muted small-text">Root array of 8 requests, coloured by the rank that receives each chunk</div>
        <div class="chunks mt-s">${r.requests.map((q, i) => { const rank = Math.floor(i / (8 / np)); return `<span class="chunk-item" style="background:${RANK_COLOR[rank]}">TRAIN-${q[0]} · T${q[1]}</span>`; }).join('')}</div>
        <div class="ranks mt-s" style="grid-template-columns:repeat(${Math.min(np, 4)},1fr)">${r.chunks.map((c, i) => `<div class="rank-box" style="border-color:${RANK_COLOR[i]}"><div class="rk" style="color:${RANK_COLOR[i]}">rank ${i}</div>${c.map((t) => 'TRAIN-' + t).join(', ')}</div>`).join('')}</div>`)}
      ${card('Local processing', 'each process checks its trains against the broadcast state', `<table><tr><th>Train</th><th>Track</th><th>Processed by</th><th>Travel time</th><th>Decision</th></tr>${r.results.map((x) => `<tr><td class="mono"><b>TRAIN-${x.train}</b></td><td class="mono">TRACK-0${x.track}</td><td style="color:${RANK_COLOR[x.rank]};font-weight:700">rank ${x.rank} · ${esc(x.station)}</td><td>${x.minutes} min</td><td>${x.accept ? pill('ACCEPT', 'p-green') : pill('HOLD (occupied)', 'p-red')}</td></tr>`).join('')}</table>`)}
      ${card('3 · Gather', 'MPI.COMM_WORLD.Gather', `<div class="muted small-text">Single buffer on the root, assembled in rank order</div>
        <div class="buffer mt-s">${r.chunks.map((c, i) => `<div class="seg" style="background:${RANK_COLOR[i]}"><small>rank ${i}</small>${c.join(', ')}</div>`).join('')}</div>
        <div class="row mt-s">${pill(r.accepted + ' accepted', 'p-green')} ${pill(r.held + ' held', 'p-red')}</div>
        <div class="callout ${r.gatherVerified ? 'ok-box' : 'bad-box'} mt-s">${r.gatherVerified ? '✓ GATHER VERIFIED: chunks reassembled in rank order, original order restored (inverse of Scatter)' : '✗ gather mismatch'}</div>`)}
      <details class="card"><summary><b>Raw MPJ Express output</b></summary><pre class="log-box mt-s">${esc(out.output.join('\n'))}</pre></details>`;
  },
});

/* ---------------- 10 Matrix ---------------- */

page({
  id: 'matrix', num: '10', group: 'Parallel Computing', title: 'Parallel Matrix Multiplication', eyebrow: 'Experiment 10',
  blurb: 'Row-wise MPI matrix multiplication with measured speedup.',
  render: () => `
    ${concept({
      tag: 'Experiment 10', title: 'C = A × B with MPI',
      paras: ['The root <b>scatters the rows of A</b> (Scatterv, so uneven splits work), <b>broadcasts all of B</b>, every process computes its rows of C, and the root <b>gathers C</b> in rank order. Row i of C needs only row i of A and B, so no communication is needed while computing.',
        'Part A squares the 6-junction railway connection matrix: C[i][j] counts the 2-leg routes from i to j. Part B times large matrices against a sequential run.'],
      terms: [['Speedup', 'T<sub>sequential</sub> / T<sub>parallel</sub>'], ['Efficiency', 'Speedup / number of processes'], ['Overhead', 'small matrices can be slower in parallel: communication costs more than it saves']],
    })}
    <div class="mt">${card('Run the MPI job', '', `<div class="row">
        <label class="field">Processes <b id="mx-np-v">4</b><input type="range" id="mx-np" min="1" max="8" value="4"></label>
        <label class="field">Matrix size (Part B)<select id="mx-n"><option>240</option><option>480</option><option>720</option><option selected>960</option><option>1200</option></select></label>
        <button class="btn accent" data-act="mxRun">Run</button>
        <button class="btn" data-act="mxSweep">Speedup sweep (1, 2, 4, 8 processes)</button>
      </div>`)}</div>
    <div id="mx-results" class="stack mt"></div>
    <div class="mt">${card('Speedup vs processes', 'from the runs you made (same matrix size)', '<div id="mx-sweep"></div>')}</div>`,
  after: () => {
    $('#mx-np').addEventListener('input', () => { $('#mx-np-v').textContent = $('#mx-np').value; });
  },
  refresh: () => {
    const out = S.results.matrix;
    view('#mx-sweep').html = sweepChart();
    if (!out) { view('#mx-results').html = '<div class="empty">Run the job to start real MPI processes.</div>'; return; }
    const r = out.result; const a = r.partA; const b = r.partB; const np = r.processes;
    const rankOfRow = []; a.ranks.forEach((rk) => rk.rows.forEach(() => rankOfRow.push(rk.rank)));
    const matrix = (m, coloured) => `<div class="matrix" style="grid-template-columns:repeat(${a.junctions.length + 1}, auto)"><span class="cell hdr"></span>${a.junctions.map((j) => `<span class="cell hdr">${j}</span>`).join('')}${m.map((row, i) => `<span class="cell hdr">${a.junctions[i]}</span>${row.map((v) => `<span class="cell" style="${coloured ? `background:${RANK_COLOR[rankOfRow[i]]}22;color:${RANK_COLOR[rankOfRow[i]]};font-weight:700` : ''}">${v}</span>`).join('')}`).join('')}</div>`;
    const maxT = Math.max(b.sequentialMs, b.parallelMs);
    view('#mx-results').html = `
      <div class="row">${pill(out.command, 'p-grey')} ${pill(np + ' processes', 'p-blue')}</div>
      <div class="grid g3">
        ${card('A · junction connections', '1 = direct line', matrix(a.a, false))}
        ${card('C = A × A · 2-leg routes', 'rows coloured by the rank that computed them', matrix(a.c, true))}
        ${card('Row distribution (Scatterv)', '', `<div class="ranks" style="grid-template-columns:repeat(2,1fr)">${a.ranks.map((rk) => `<div class="rank-box" style="border-color:${RANK_COLOR[rk.rank]}"><div class="rk" style="color:${RANK_COLOR[rk.rank]}">rank ${rk.rank} · ${esc(rk.station)}</div>rows ${rk.rows.length ? rk.rows.join(', ') : '<span class="muted">none</span>'}</div>`).join('')}</div><div class="callout ${a.verified ? 'ok-box' : 'bad-box'} mt-s">${a.verified ? '✓ equals the sequential result' : '✗ mismatch'}</div>`)}
      </div>
      ${card(`Part B · ${b.n} × ${b.n} matrices`, 'measured on this machine', `<div class="grid g2"><div>${bars([['Sequential', b.sequentialMs, 'ms', ''], [`Parallel (${np})`, b.parallelMs, 'ms', 'green']], maxT)}</div>
        <div class="kpis"><div class="kpi"><div class="label">Speedup</div><div class="value">${b.speedup.toFixed(2)}×</div></div><div class="kpi"><div class="label">Efficiency</div><div class="value">${Math.round(b.efficiency * 100)}%</div></div><div class="kpi"><div class="label">Result</div><div class="value ${b.verified ? 'ok' : 'bad'}">${b.verified ? '✓ verified' : '✗'}</div></div></div></div>
        ${b.speedup < 1 ? '<div class="callout warn-box mt-s">Parallel was slower: for this size, communication overhead outweighs the computation saved. Try a larger matrix.</div>' : ''}`)}
      <details class="card"><summary><b>Raw MPJ Express output</b></summary><pre class="log-box mt-s">${esc(out.output.join('\n'))}</pre></details>`;
  },
});

function sweepChart() {
  const pts = memory.sweep;
  if (!pts.length) return '<div class="empty">Run the sweep (or several single runs) to plot measured speedup.</div>';
  const W = 640; const H = 260; const pad = 46;
  const maxP = 8; const maxS = Math.max(4, ...pts.map((p) => p.speedup)) * 1.1;
  const x = (p) => pad + ((p - 1) / (maxP - 1)) * (W - pad * 2);
  const y = (s) => H - pad - (s / maxS) * (H - pad * 2);
  const sorted = [...pts].sort((a, b) => a.np - b.np);
  let svg = `<line x1="${pad}" y1="${H - pad}" x2="${W - pad}" y2="${H - pad}" stroke="#94a3b8"/><line x1="${pad}" y1="${pad / 2}" x2="${pad}" y2="${H - pad}" stroke="#94a3b8"/>`;
  for (let p = 1; p <= 8; p++) svg += `<text x="${x(p)}" y="${H - pad + 18}" text-anchor="middle" font-size="11" fill="#64748b">${p}</text>`;
  for (let s = 0; s <= maxS; s += Math.max(1, Math.round(maxS / 4))) svg += `<text x="${pad - 8}" y="${y(s) + 4}" text-anchor="end" font-size="11" fill="#64748b">${s}×</text><line x1="${pad}" y1="${y(s)}" x2="${W - pad}" y2="${y(s)}" stroke="#eef2f6"/>`;
  // Ideal (linear) speedup: speedup = number of processes, clipped to the chart
  const idealEnd = Math.min(8, maxS);
  svg += `<line x1="${x(1)}" y1="${y(1)}" x2="${x(idealEnd)}" y2="${y(idealEnd)}" stroke="#cbd5e1" stroke-dasharray="5 5"/><text x="${x(idealEnd) + 6}" y="${y(idealEnd) + 4}" font-size="11" fill="#94a3b8">ideal (linear)</text>`;
  svg += `<polyline points="${sorted.map((p) => `${x(p.np)},${y(p.speedup)}`).join(' ')}" fill="none" stroke="#2563eb" stroke-width="3"/>`;
  svg += sorted.map((p) => `<circle cx="${x(p.np)}" cy="${y(p.speedup)}" r="5" fill="#2563eb"/><text x="${x(p.np)}" y="${y(p.speedup) - 10}" text-anchor="middle" font-size="11" font-weight="700" fill="#0f2340">${p.speedup.toFixed(2)}×</text>`).join('');
  svg += `<text x="${W / 2}" y="${H - 6}" text-anchor="middle" font-size="11" fill="#64748b">MPI processes (n = ${sorted[0].n})</text>`;
  return `<div class="diagram"><svg viewBox="0 0 ${W} ${H}">${svg}</svg></div>`;
}

/* =========================================================
   ACTIONS (buttons with data-act)
   ========================================================= */

function recordEvents(data, target) {
  if (data && data.events) target.push(...data.events);
  if (target.length > 200) target.splice(0, target.length - 200);
}

const ACTIONS = {
  fail: (b) => act(b, `/stations/${b.dataset.station}/fail`),
  recover: (b) => act(b, `/stations/${b.dataset.station}/recover`),
  overload: (b) => act(b, `/stations/${b.dataset.station}/overload`),
  heartbeat: (b) => act(b, '/heartbeat', {}, (d) => 'Heartbeat round: ' + Object.entries(d.alive).map(([k, v]) => `${shortSt(k)} ${v ? '✓' : '✗'}`).join(' · ')),

  rpcInvoke: async (b) => {
    try {
      const res = await busy(b, () => rpcCall($('#rpc-method').value, $('#rpc-station').value));
      renderRpcResult();
      toast(res.error ? `✗ RPC error ${res.error.code}` : '✓ RPC returned a result', res.error ? 'bad' : 'ok');
      await refreshState();
    } catch (e) { toast('✗ ' + esc(e.message), 'bad'); }
  },
  rpcAll: async (b) => {
    await busy(b, async () => { for (const s of STATIONS) await rpcCall('station.ping', s); });
    renderRpcResult();
    await refreshState();
  },

  threadsFire: async (b) => {
    const n = +$('#th-n').value;
    await busy(b, async () => {
      const t0 = performance.now();
      const results = await Promise.all(Array.from({ length: n }, (_, i) => api('/rpc', { method: 'station.getLamportClock', station: STATIONS[i % 4], id: i })));
      const byThread = {};
      results.forEach((r) => { byThread[r.servedBy] = (byThread[r.servedBy] || 0) + 1; });
      memory.concurrency = { n, wall: Math.round(performance.now() - t0), byThread };
    });
    await refreshState();
  },
  dispatchDemo: (b) => act(b, '/dispatch/demo', {}, (d) => `✓ ${d.decisions.length} requests routed · ${d.commits.filter((c) => c.accepted).length} committed · ${d.elapsedMs} ms`),
  dispatchOne: (b) => act(b, '/dispatch', { requests: [{ train: $('#lb-train').value, movement: $('#lb-move').value, track: $('#lb-track').value }] }, (d) => {
    const c = d.commits[0]; return c ? (c.accepted ? `✓ committed v${c.version}` : `✗ rejected: ${esc(c.reason)}`) : 'No station available';
  }),

  lamportLocal: async (b) => {
    const st = $('#lp-local').value; const label = $('#lp-local-label').value;
    const d = await act(b, '/lamport/local', { station: st, label }, (r) => `${st}: ${r.rule}`);
    if (d) memory.lamportLog.push({ type: 'local', station: st, ts: d.after, label });
    refreshPage();
  },
  lamportSend: async (b) => {
    const from = $('#lp-from').value; const to = $('#lp-to').value;
    const d = await act(b, '/lamport/message', { from, to, label: $('#lp-label').value }, (r) => `receive rule: ${r.rule}`);
    if (d) { memory.lamportLog.push({ type: 'msg', from, to, send: d.sendTimestamp, recv: d.receiveTimestamp }); memory.lamportLast = d; renderLamportRule(); }
    refreshPage();
  },

  election: async (b) => {
    const d = await act(b, '/election', { algorithm: b.dataset.algo }, (r) => `♛ ${r.algorithm} election → ${r.winner}`);
    if (d) { memory.election = d.events; renderElectionSteps(); }
  },

  replicate: async (b) => {
    const d = await act(b, '/replication/update', { track: $('#rp-track').value, status: $('#rp-status').value, train: $('#rp-train').value }, (r) => `✓ v${r.version} committed · ACK from ${r.acknowledgedBy.join(', ') || 'no backup'}`);
    if (d) { memory.replicationLog = d.events; refreshPage(); }
  },
  verify: (b) => act(b, '/replication/verify', {}, (r) => (r.consistent ? '✓ all replicas consistent' : '✗ replicas differ')),

  analytics: (b) => act(b, '/analytics', {}, (r) => `✓ MapReduce completed in ${r.elapsedMs} ms`),

  ftSend: async (b) => {
    const d = await act(b, '/fault/request', { train: $('#ft-train').value, movement: $('#ft-move').value, track: $('#ft-track').value, crash: $('#ft-crash').checked }, faultMsg);
    recordEvents(d, memory.faultLog); refreshPage();
  },
  ftDetect: async (b) => {
    const d = await act(b, '/fault/detect', {}, (r) => (r.declaredFailed ? `⚠ ${r.target} declared failed${r.newPrimary ? ' → ' + r.newPrimary + ' promoted' : ''}` : `✓ ${r.target} is alive`));
    recordEvents(d, memory.faultLog); markStep(b, d); refreshPage();
  },
  ftRetry: async (b) => {
    const d = await act(b, '/fault/retry', {}, () => '✓ retried pending requests');
    recordEvents(d, memory.faultLog); markStep(b, d); refreshPage();
  },
  sc1: async (b) => {
    const m = validMovement();
    const d = await act(b, '/fault/request', { ...m, crash: false }, faultMsg);
    recordEvents(d, memory.faultLog); markStep(b, d); refreshPage();
  },
  sc2: async (b) => {
    const backup = S.backups.map(stationByName).find((s) => s && s.active);
    if (!backup) { toast('No live backup to crash', 'bad'); return; }
    const d = await act(b, `/stations/${backup.name}/fail`);
    recordEvents(d, memory.faultLog); markStep(b, d); refreshPage();
  },
  sc4: async (b) => {
    const m = validMovement();
    const d = await act(b, '/fault/request', { ...m, crash: true }, faultMsg);
    recordEvents(d, memory.faultLog); markStep(b, d); refreshPage();
  },
  sc7: async (b) => {
    const down = S.stations.filter((s) => !s.active);
    if (!down.length) { toast('All stations are already up', 'ok'); markStep(b, {}); return; }
    let last = null;
    for (const s of down) { last = await act(b, `/stations/${s.name}/recover`); recordEvents(last, memory.faultLog); }
    markStep(b, last); refreshPage();
  },

  mpiRun: (b) => act(b, '/mpi/collectives', { processes: +$('#mpi-np').value }, (r) => `✓ MPI job on ${r.result.processes} processes in ${Math.round(r.elapsedMs)} ms`),
  mxRun: async (b) => {
    const d = await act(b, '/mpi/matrix', { processes: +$('#mx-np').value, n: +$('#mx-n').value }, (r) => `✓ speedup ${r.result.partB.speedup.toFixed(2)}× on ${r.result.processes} processes`);
    if (d) addSweepPoint(d);
  },
  mxSweep: async (b) => {
    const n = +$('#mx-n').value;
    memory.sweep = memory.sweep.filter((p) => p.n === n);
    await busy(b, async () => {
      for (const np of [1, 2, 4, 8]) {
        try { const d = await api('/mpi/matrix', { processes: np, n }); addSweepPoint(d); await refreshState(); } catch (e) { toast('✗ ' + esc(e.message), 'bad'); break; }
      }
    });
    toast('✓ Sweep finished', 'ok');
  },
};

function faultMsg(r) {
  const a = r.attempt;
  return { COMMITTED: `✓ ${a.requestId} committed v${a.version}`, DUPLICATE: `✓ ${a.requestId} duplicate blocked`, REJECTED: `⚠ ${a.requestId} rejected`, NO_REPLY: `⚠ ${a.requestId}: no reply (primary crashed)` }[a.status];
}
function markStep(b, d) { if (d && b.dataset.step !== undefined) { scenarioDone.add(+b.dataset.step); renderScenario(); } }
function addSweepPoint(d) {
  const b = d.result.partB;
  memory.sweep = memory.sweep.filter((p) => p.n === b.n && p.np !== d.result.processes);
  memory.sweep.push({ np: d.result.processes, n: b.n, speedup: b.speedup });
  refreshPage();
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-act]');
  if (!b || b.disabled) return;
  const fn = ACTIONS[b.dataset.act];
  if (fn) fn(b);
});

/* =========================================================
   ROUTER, POLLING, CONSOLE
   ========================================================= */

function buildNav() {
  let html = ''; let group = null;
  for (const p of PAGES) {
    if (p.group && p.group !== group) { group = p.group; html += `<div class="nav-group">${esc(group)}</div>`; }
    html += `<a class="nav-item" data-page="${p.id}" href="#/${p.id}"><span class="nav-num">${p.num || '◎'}</span>${esc(p.title)}</a>`;
  }
  $('#nav').innerHTML = html;
}

function navigate() {
  const id = (location.hash.replace('#/', '') || 'overview');
  currentPage = PAGES.find((p) => p.id === id) || PAGES[0];
  document.querySelectorAll('.nav-item').forEach((a) => a.classList.toggle('active', a.dataset.page === currentPage.id));
  $('#page-title').textContent = currentPage.title;
  $('#page-eyebrow').textContent = currentPage.eyebrow;
  document.title = currentPage.title + ' · RailCoord';
  $('#page').innerHTML = currentPage.render();
  if (currentPage.after) currentPage.after();
  refreshPage();
  window.scrollTo(0, 0);
}

function refreshPage() {
  if (!S || !currentPage || !currentPage.refresh) return;
  try { currentPage.refresh(); } catch (e) { console.error(e); }
}

function renderChips() {
  const up = S.stations.filter((s) => s.active).length;
  const maxL = Math.max(...S.stations.map((s) => s.lamport));
  view('#status-chips').html = [
    `♛ Coordinator <b>${esc(S.coordinator || 'none')}</b>`,
    `Primary <b>${esc(S.primary)}</b>${S.primaryActive ? '' : ' <span class="bad">DOWN</span>'}`,
    `State <b>v${S.version}</b>`,
    `Stations up <b>${up}/4</b>`,
    `Max Lamport <b>${maxL}</b>`,
  ].map((c) => `<span class="chip">${c}</span>`).join('');
}

async function refreshState() {
  try {
    S = await api('/state');
    setConnected(true);
    renderChips();
    refreshPage();
  } catch {
    setConnected(false);
  }
}

function setConnected(on) {
  $('#conn-dot').className = 'dot ' + (on ? 'on' : 'off');
  $('#conn-text').textContent = on ? 'Connected to Java backend' : 'Backend not reachable';
}

const consoleBody = () => $('#console-body');
function consoleClass(line) {
  if (/^[=*-]{10,}/.test(line)) return 'c-rule';
  if (line.includes('✗') || line.includes('!!!') || line.includes('FAILED')) return 'c-bad';
  if (line.includes('⚠')) return 'c-warn';
  if (line.includes('✓')) return 'c-ok';
  if (/^[A-Z0-9 ·&→:().-]{8,}$/.test(line.trim())) return 'c-head';
  return '';
}

async function pollEvents() {
  try {
    const data = await api('/events?since=' + lastSeq);
    const body = consoleBody();
    let added = false;
    for (const e of data.entries) {
      if (e.kind === 'station') {
        stationEvents.push(e);
      } else {
        const span = document.createElement('span');
        span.className = consoleClass(e.text);
        span.textContent = e.text + '\n';
        body.appendChild(span);
        added = true;
      }
    }
    if (stationEvents.length > 400) stationEvents = stationEvents.slice(-400);
    while (body.childNodes.length > 1500) body.removeChild(body.firstChild);
    if (added && $('#console-follow').checked) body.scrollTop = body.scrollHeight;
    lastSeq = data.lastSeq;
  } catch { /* connection state handled by refreshState */ }
}

$('#console-toggle').addEventListener('click', () => {
  const open = $('#console').classList.toggle('open');
  $('#console-toggle').classList.toggle('on', open);
  if (open) consoleBody().scrollTop = consoleBody().scrollHeight;
});
$('#console-close').addEventListener('click', () => { $('#console').classList.remove('open'); $('#console-toggle').classList.remove('on'); });
$('#console-clear').addEventListener('click', () => { consoleBody().innerHTML = ''; });
$('#reset-btn').addEventListener('click', async (e) => {
  if (!confirm('Reset the railway system to its initial state?')) return;
  await act(e.target, '/reset', {}, '✓ System reset');
  Object.assign(memory, { rpcHistory: [], rpcLast: null, lamportLog: [], lamportLast: null, election: null, replicationLog: null, faultLog: [], concurrency: null });
  scenarioDone.clear();
  navigate();
});

window.addEventListener('hashchange', navigate);

(async function start() {
  buildNav();
  await refreshState();
  await pollEvents();
  navigate();
  setInterval(async () => { await pollEvents(); await refreshState(); }, 1500);
})();
