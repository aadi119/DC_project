'use strict';

/* =========================================================
   RailNet Ops — screens
   ========================================================= */

const ICON = {
  network: '<svg viewBox="0 0 20 20"><circle cx="4" cy="5" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="16" cy="5" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="10" cy="15" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M6 5h8M5 7l4 6M15 7l-4 6" stroke="currentColor" stroke-width="1.6" fill="none"/></svg>',
  dispatch: '<svg viewBox="0 0 20 20"><path d="M3 10h9M9 6l4 4-4 4" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M15 4v12" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>',
  shield: '<svg viewBox="0 0 20 20"><path d="M10 2.5l6 2.3v4.6c0 4-2.6 6.6-6 8.1-3.4-1.5-6-4.1-6-8.1V4.8z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M7.3 10l2 2 3.5-4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  timeline: '<svg viewBox="0 0 20 20"><path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" stroke-width="1.3" opacity=".45"/><circle cx="6" cy="5" r="1.8" fill="currentColor"/><circle cx="11" cy="10" r="1.8" fill="currentColor"/><circle cx="15" cy="15" r="1.8" fill="currentColor"/><path d="M6 5l5 5 4 5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>',
  insights: '<svg viewBox="0 0 20 20"><path d="M4 16V9M8 16V5M12 16v-6M16 16V3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  fleet: '<svg viewBox="0 0 20 20"><circle cx="10" cy="4" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="4" cy="15" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="10" cy="15" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="16" cy="15" r="2.2" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M10 6.5v6M8.6 6l-3.6 7M11.4 6l3.6 7" stroke="currentColor" stroke-width="1.4"/></svg>',
  routes: '<svg viewBox="0 0 20 20"><rect x="3" y="3" width="5" height="5" rx="1.2" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="12" y="3" width="5" height="5" rx="1.2" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="3" y="12" width="5" height="5" rx="1.2" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="12" y="12" width="5" height="5" rx="1.2" fill="currentColor" opacity=".7"/></svg>',
  section: '<svg viewBox="0 0 20 20"><path d="M3 16V10a7 7 0 0 1 14 0v6" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M1.5 16h17" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/><rect x="7.5" y="11" width="5" height="3" rx="1" fill="currentColor"/></svg>',
  timetable: '<svg viewBox="0 0 20 20"><rect x="3" y="3.5" width="14" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M3 8h14M8 8v8.5" stroke="currentColor" stroke-width="1.4"/></svg>',
  system: '<svg viewBox="0 0 20 20"><path d="M7 6l-4 4 4 4M13 6l4 4-4 4M11 4l-2 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
};

const local = {
  rpcLast: null, rpcHistory: [], burst: null, threads: null,
  trace: null, electionEvents: null, sweep: [], route: { i: 0, j: 3 },
  tlWindow: 80, tlSelected: null, fleetPlayed: null, lastDispatchShown: null,
};

const nowTime = () => new Date().toTimeString().slice(0, 8);
function activity(ic, c, tx) { pushActivity({ ic, c, tx, time: nowTime(), lamport: '' }); }

/* =========================================================
   NETWORK
   ========================================================= */

page({
  id: 'network', title: 'Network', icon: ICON.network,
  render: () => `
    <div class="page-head">
      <div><h1>Network</h1><p>Live operations across Northgate, Riverside, Hillcrest and Harbour. Click a station to inspect it.</p></div>
      <div class="actions">
        <button class="btn primary" data-act="rush">Rush hour</button>
        <button class="btn" data-act="sendTrain">Send a train</button>
        <button class="btn danger" data-act="incident">Simulate incident</button>
      </div>
    </div>
    <div class="grid main-side">
      <div class="panel flush">
        <div class="map-wrap" id="net-map"></div>
        <div class="map-overlay" id="net-overlay"></div>
        <div class="map-legend"><span><i style="background:#22d3ee"></i>Control centre</span><span><i style="background:#a78bfa"></i>Mirror</span><span><i style="background:#475569"></i>Standby</span><span><i style="background:#f87171"></i>Offline</span><span>♛ Coordinator</span><span><i style="background:#34d399"></i>Signal clear</span><span><i style="background:#f87171"></i>Signal red</span></div>
      </div>
      <div class="stack">
        <div class="panel" id="net-inspector"></div>
        <div class="panel"><div class="panel-head"><h3>Live activity</h3><span class="right dim" style="font-size:12px">λ = logical time</span></div><div class="feed" id="net-feed"></div></div>
      </div>
    </div>
    <div class="metrics mt" id="net-metrics"></div>
    <div class="stations mt" id="net-stations"></div>`,
  mount() {
    App.map = new NetworkMap($('#net-map'), {
      onSelect: (name) => { App.selected = App.selected === name ? null : name; this.update(true); },
    });
    if (App.state) App.map.update(App.state, null, App.selected);
  },
  leave() { if (App.map) App.map.destroy(); App.map = null; },
  update(selectionOnly) {
    const s = App.state;
    if (App.map) App.map.update(s, selectionOnly ? s : App.prev, App.selected);
    const trains = s.tracks.filter((t) => t.train).length;
    view('#net-overlay').html = `${chip('STATE v' + s.version, 'c-cyan')} ${chip(trains + ' trains on the network', 'c-green')} ${s.primaryActive ? chip('control · ' + place(s.primary), 'c-violet') : chip('control centre offline', 'c-red')}`;
    view('#net-inspector').html = inspector();
    view('#net-feed').html = feedHtml(32);
    const primary = station(s.primary);
    const synced = s.backups.map(station).filter((m) => m && m.active && m.version === primary.version).length;
    view('#net-metrics').html = [
      ['Trains running', trains, `${5 - trains} tracks free`],
      ['Network state', 'v' + s.version, 'committed writes'],
      ['Mirrors in sync', `${synced}/${s.backups.length}`, 'synchronous replication'],
      ['Trains dispatched', s.stations.reduce((n, x) => n + x.assigned, 0), `${s.client.committed} client requests committed`],
      ['Failovers', (App.state.failovers ?? 0), App.autoHeal ? 'self-healing on' : 'self-healing off'],
      ['Stations online', `${s.stations.filter((x) => x.active).length}/4`, 'heartbeat monitored'],
    ].map(([l, v, h]) => `<div class="metric"><div class="label">${l}</div><div class="value">${esc(v)}</div><div class="hint">${esc(h)}</div></div>`).join('');
    view('#net-stations').html = s.stations.map(stationCard).join('');
  },
});

function stationCard(st) {
  return `<div class="st-card ${st.active ? '' : 'down'} ${App.selected === st.name ? 'selected' : ''}" data-act="select" data-station="${st.name}">
    <span class="bar" style="background:${ST_COLOR[st.name]}"></span>
    <div class="top"><span class="dot"></span><span class="nm">${esc(place(st.name))}</span><span class="cd">${st.name}</span><span class="grow"></span>${st.coordinator ? '<span title="Coordinator" style="color:var(--gold)">♛</span>' : ''}</div>
    <div class="chips">${roleChip(st)}${st.overloaded ? chip('BUSY', 'c-amber') : ''}</div>
    <div class="stats"><div><span>λ clock</span><b>${st.lamport}</b></div><div><span>state</span><b>v${st.version}</b></div><div><span>trains</span><b>${st.assigned}</b></div></div>
  </div>`;
}

function inspector() {
  const s = App.state;
  const st = App.selected && station(App.selected);
  if (!st) {
    const coord = s.coordinator ? place(s.coordinator) : 'none';
    return `<div class="panel-head"><h3>Network summary</h3></div>
      <div class="kv">
        <span class="k">Control centre</span><b>${esc(place(s.primary))} ${s.primaryActive ? '' : '<span class="bad">offline</span>'}</b>
        <span class="k">Mirrors</span><b>${esc(s.backups.map(place).join(', ') || 'none')}</b>
        <span class="k">Coordinator</span><b>${esc(coord)}</b>
        <span class="k">Network state</span><b>v${s.version}</b>
        <span class="k">Next dispatch slot</span><b>${esc(place(s.nextInRotation))}</b>
      </div>
      <div class="dim mt-s" style="font-size:12.5px">Select a station on the map to inspect or control it.</div>`;
  }
  return `<div class="panel-head"><h3>${esc(place(st.name))}</h3><span class="sub mono">${st.name}</span><span class="right">${roleChip(st)}</span></div>
    <div class="kv">
      <span class="k">Status</span><b class="${st.active ? 'ok' : 'bad'}">${st.active ? 'online' : 'offline'}${st.overloaded ? ' · busy' : ''}</b>
      <span class="k">Logical clock (λ)</span><b class="mono">${st.lamport}</b>
      <span class="k">State replica</span><b class="mono">v${st.version} · ${esc(st.checksum)}</b>
      <span class="k">Trains handled</span><b>${st.assigned}</b>
      <span class="k">Events logged</span><b>${st.events}</b>
      <span class="k">Coordinator</span><b>${st.coordinator ? '♛ yes' : 'no'}</b>
    </div>
    <div class="row mt">
      ${st.active
        ? `<button class="btn sm danger" data-act="stFail" data-station="${st.name}">Take offline</button>`
        : `<button class="btn sm success" data-act="stRecover" data-station="${st.name}">Bring online</button>`}
      <button class="btn sm" data-act="stOverload" data-station="${st.name}">${st.overloaded ? 'Mark available' : 'Mark busy'}</button>
      <button class="btn sm" data-act="stPing" data-station="${st.name}">Ping</button>
    </div>`;
}

function feedHtml(max) {
  const items = App.activity.slice(-max).reverse();
  if (!items.length) return '<div class="empty">Waiting for activity… turn on Autopilot or dispatch a train.</div>';
  const seen = App.feedSeen || 0;
  App.feedSeen = Math.max(seen, ...items.map((a) => a.id));
  return items.map((a) => `<div class="feed-item ${a.id > seen ? 'fresh' : ''}"><span class="ic chip ${a.c}">${a.ic}</span><span class="tx">${a.tx}</span><span class="meta">${esc(a.time || '')}${a.lamport !== '' && a.lamport != null ? `<br>λ ${a.lamport}` : ''}</span></div>`).join('');
}

Object.assign(ACTIONS, {
  select: (b) => { App.selected = App.selected === b.dataset.station ? null : b.dataset.station; if (App.page.update) App.page.update(true); },
  rush: async (b) => {
    const d = await act(b, '/dispatch/demo', {}, (r) => `🚆 ${r.decisions.length} trains dispatched · ${r.commits.filter((c) => c.accepted).length} cleared · ${r.elapsedMs} ms`);
    if (d) showDispatch(d);
  },
  sendTrain: async (b) => {
    const d = await act(b, '/dispatch', { requests: [plannedMovement()] }, (r) => {
      const c = r.commits[0];
      return c ? (c.accepted ? `🚆 ${c.train} cleared on ${c.track}` : `⛔ ${c.train} held at a red signal`) : 'No station available';
    });
    if (d) showDispatch(d);
  },
  incident: async (b) => {
    const s = App.state;
    const live = s.stations.filter((x) => x.active);
    if (live.length <= 1) { toast('Only one station is online; bring one back first', 'bad'); return; }
    const target = s.primaryActive && Math.random() < 0.55 ? s.primary : pick(live.filter((x) => x.name !== s.primary)).name;
    const d = await act(b, `/stations/${target}/fail`, {}, `⚠ Power failure at <b>${esc(place(target))}</b>`);
    if (d) activity('⚠', 'c-red', `Incident: <b>${esc(place(target))}</b> lost power`);
  },
  stFail: (b) => act(b, `/stations/${b.dataset.station}/fail`, {}, `⚠ ${esc(place(b.dataset.station))} taken offline`),
  stRecover: (b) => act(b, `/stations/${b.dataset.station}/recover`, {}, (r) => '⟳ ' + esc(r.message.replace(b.dataset.station, place(b.dataset.station)))),
  stOverload: (b) => act(b, `/stations/${b.dataset.station}/overload`),
  stPing: async (b) => {
    try {
      const r = await busy(b, () => api('/rpc', { method: 'station.ping', station: b.dataset.station, id: Date.now() % 10000 }));
      toast(r.error ? `✗ ${esc(place(b.dataset.station))}: ${esc(r.error.message)}` : `⇄ ${esc(place(b.dataset.station))} answered in ${r.latencyMs} ms (λ ${r.result.lamport})`, r.error ? 'bad' : 'ok');
      refresh();
    } catch (e) { toast('✗ ' + esc(e.message), 'bad'); }
  },
});

function showDispatch(d) {
  if (App.map) App.map.dispatchTo(d.decisions.map((x) => ({ station: x.station })));
  d.commits.forEach((c) => activity(c.accepted ? '🚆' : '⛔', c.accepted ? 'c-green' : 'c-red',
    c.accepted ? `<b>${esc(c.train)}</b> cleared on ${esc(c.track)} → ${esc(c.newStatus.split(' ')[0].toLowerCase())}` : `<b>${esc(c.train)}</b> held at red signal on ${esc(c.track)}`));
}

/* =========================================================
   DISPATCH
   ========================================================= */

page({
  id: 'dispatch', title: 'Dispatch', icon: ICON.dispatch,
  render: () => `
    <div class="page-head">
      <div><h1>Dispatch</h1><p>Incoming trains are handed to station controllers in turn. Offline or busy controllers are skipped, and every controller works on its own thread.</p></div>
      <div class="actions"><button class="btn primary" data-act="rush">Rush hour</button></div>
    </div>
    <div class="grid wide-left">
      <div class="panel">
        <div class="panel-head"><h3>New train request</h3>${info('<b>Health-aware round-robin.</b> Controllers take turns (Northgate → Riverside → Hillcrest → Harbour). Before assigning, the dispatcher skips any controller that is offline or busy, then moves its pointer past the one it chose.')}</div>
        <div class="row">
          <input type="text" id="dp-train" placeholder="Train ID (blank = new train)" style="width:210px">
          <select id="dp-move"><option value="ARRIVAL">Arrival</option><option value="DEPARTURE">Departure</option></select>
          <select id="dp-track">${TRACKS.map((t) => `<option>${t}</option>`).join('')}</select>
          <button class="btn primary" data-act="dpSend">Dispatch</button>
        </div>
        <div class="grid cols-2 mt">
          <div class="wheel" id="dp-wheel"></div>
          <div><div class="dim" style="font-size:12px;margin-bottom:8px">LAST ROUTING DECISIONS</div><div id="dp-routes"></div></div>
        </div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Controller load</h3><span class="sub">trains handled per station</span></div>
        <div id="dp-load"></div>
      </div>
    </div>
    <div class="grid cols-2 mt">
      <div class="panel">
        <div class="panel-head"><h3>Controller threads</h3><span class="sub">last batch, replayed from real timings</span>${info('Each station controller owns a <b>worker thread</b>. Requests for different controllers run <b>in parallel</b>; requests for the same controller queue on its thread.')}</div>
        <div id="dp-lanes"></div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Signal box</h3><span class="sub">committed by the control centre, in arrival order</span>${info('The control centre checks every movement against the authoritative track state: an arrival needs a <b>clear</b> track, a departure needs that train on the track. Unsafe movements are held at a <b>red signal</b>.')}</div>
        <div id="dp-signals"></div>
      </div>
    </div>`,
  mount() {
    $('#dp-wheel').innerHTML = wheelSkeleton();
  },
  update() {
    const s = App.state;
    updateWheel();
    view('#dp-load').html = `<div class="loadbars">${s.stations.map((st) => {
      const max = Math.max(4, ...s.stations.map((x) => x.assigned));
      const color = !st.active ? 'var(--red)' : st.overloaded ? 'var(--amber)' : ST_COLOR[st.name];
      return `<div class="lb-row"><span><b>${esc(place(st.name))}</b><br><span class="dim mono" style="font-size:11px">${st.name}-worker</span></span>
        <div class="lb-track"><div class="lb-fill" style="width:${(st.assigned / max) * 100}%;background:${color};box-shadow:0 0 12px ${color}"></div></div>
        <span><b>${st.assigned}</b> ${!st.active ? chip('OFF', 'c-red') : st.overloaded ? chip('BUSY', 'c-amber') : ''}</span></div>`;
    }).join('')}</div>
      <div class="row mt">${s.stations.map((st) => `<button class="btn sm ${st.overloaded ? 'warn' : ''}" data-act="stOverload" data-station="${st.name}">${st.overloaded ? '◼ ' : ''}${esc(place(st.name))} ${st.overloaded ? 'busy' : ''}</button>`).join('')}</div>
      <div class="dim mt-s" style="font-size:12px">Mark a controller busy (or take it offline on the Network screen) and dispatch again: it will be skipped.</div>`;

    const d = s.results.dispatch;
    view('#dp-routes').html = d ? `<div class="signal-box">${d.decisions.slice(-6).map((x) => `<div class="sig-row" style="grid-template-columns:auto 1fr"><span class="chip c-grey mono">#${x.sequence}</span><span><b>${esc(x.train)}</b> → ${x.station ? `<b style="color:${ST_COLOR[x.station]}">${esc(place(x.station))}</b>` : '<span class="bad">no controller</span>'}${x.skipped.length ? `<br><span class="warn" style="font-size:11.5px">skipped ${esc(x.skipped.map((k) => place(k.split(' ')[0]) + ' (' + k.split('[')[1].replace(']', '').toLowerCase() + ')').join(', '))}</span>` : ''}</span></div>`).join('')}</div>` : '<div class="empty">No trains dispatched yet.</div>';
    view('#dp-lanes').html = d ? lanesHtml(d) : '<div class="empty">Dispatch a batch to watch controllers work in parallel.</div>';
    view('#dp-signals').html = d ? `<div class="signal-box">${d.commits.map((c, i) => `<div class="sig-row" style="animation-delay:${i * 90}ms"><span class="lamp ${c.accepted ? 'g' : 'r'}"></span><span><b>${esc(c.train)}</b> · ${esc(c.track)} ${c.accepted ? `→ ${esc(c.newStatus)}` : `<br><span class="bad" style="font-size:12px">held: ${esc(c.reason.split('→')[0])}</span>`}</span><span class="mono dim" style="font-size:11.5px">${c.accepted ? 'state v' + c.version : '—'}</span></div>`).join('')}</div>` : '<div class="empty">—</div>';
  },
});

const WHEEL = { cx: 190, cy: 150, r: 104, order: STATIONS, angle: (i) => -90 + i * 90 };
function wheelSkeleton() {
  const nodes = WHEEL.order.map((n, i) => {
    const a = WHEEL.angle(i) * Math.PI / 180;
    const x = WHEEL.cx + WHEEL.r * Math.cos(a); const y = WHEEL.cy + WHEEL.r * Math.sin(a);
    return `<g id="wh-${n}"><circle cx="${x}" cy="${y}" r="30" fill="#0e1a30" stroke-width="3"/><text x="${x}" y="${y - 2}" text-anchor="middle" font-weight="800" font-size="13" fill="#fff">${code(n)}</text><text x="${x}" y="${y + 13}" text-anchor="middle" font-size="9.5" fill="#94a3b8" class="wh-state"></text></g>`;
  }).join('');
  return `<svg viewBox="0 0 380 300">
    <circle cx="${WHEEL.cx}" cy="${WHEEL.cy}" r="${WHEEL.r}" fill="none" stroke="rgba(148,163,184,0.18)" stroke-width="2" stroke-dasharray="4 6"/>
    <g id="wh-pointer" style="transform-origin:${WHEEL.cx}px ${WHEEL.cy}px;transition:transform .7s cubic-bezier(.3,1.4,.5,1)">
      <path d="M${WHEEL.cx} ${WHEEL.cy} L${WHEEL.cx} ${WHEEL.cy - WHEEL.r + 40}" stroke="#22d3ee" stroke-width="4" stroke-linecap="round" filter="url(#none)"/>
      <path d="M${WHEEL.cx - 9} ${WHEEL.cy - WHEEL.r + 48} L${WHEEL.cx} ${WHEEL.cy - WHEEL.r + 34} L${WHEEL.cx + 9} ${WHEEL.cy - WHEEL.r + 48}" fill="#22d3ee"/>
    </g>
    <circle cx="${WHEEL.cx}" cy="${WHEEL.cy}" r="26" fill="#0e1a30" stroke="#22d3ee" stroke-width="2"/>
    <text x="${WHEEL.cx}" y="${WHEEL.cy + 4}" text-anchor="middle" font-size="10" font-weight="800" fill="#22d3ee" letter-spacing="1">NEXT</text>
    ${nodes}
  </svg>`;
}
function updateWheel() {
  const s = App.state;
  const ptr = $('#wh-pointer');
  if (!ptr) return;
  const idx = WHEEL.order.indexOf(s.nextInRotation);
  ptr.style.transform = `rotate(${idx * 90}deg)`;
  for (const st of s.stations) {
    const g = $('#wh-' + st.name);
    const color = !st.active ? '#f87171' : st.overloaded ? '#fbbf24' : '#34d399';
    g.querySelector('circle').setAttribute('stroke', color);
    g.querySelector('.wh-state').textContent = !st.active ? 'OFFLINE' : st.overloaded ? 'BUSY' : `${st.assigned} trains`;
  }
}

function lanesHtml(d) {
  const toMs = (t) => { const [h, m, sec] = t.split(':'); return ((+h * 60 + +m) * 60 + parseFloat(sec)) * 1000; };
  const start = Math.min(...d.processing.map((p) => toMs(p.startedAt)));
  const end = Math.max(...d.processing.map((p) => toMs(p.finishedAt)));
  const span = Math.max(1, end - start);
  const workers = [...new Set(d.processing.map((p) => p.worker))].sort();
  const lanes = workers.map((w) => `<div class="lane"><span class="ln">${esc(w)}</span><div class="lt">${d.processing.filter((p) => p.worker === w).map((p, i) => {
    const left = ((toMs(p.startedAt) - start) / span) * 100;
    const width = Math.max(6, ((toMs(p.finishedAt) - toMs(p.startedAt)) / span) * 100);
    return `<div class="job" style="left:${left}%;width:${width}%;background:${ST_COLOR[p.station]};animation-delay:${left * 6}ms" title="${esc(p.train)} λ ${p.lamport}">${esc(p.train)}</div>`;
  }).join('')}</div></div>`).join('');
  const gain = d.serialEstimateMs / Math.max(1, d.elapsedMs);
  return `<div class="lanes">${lanes}</div><div class="axis"><span>0 ms</span><span>${Math.round(span / 2)} ms</span><span>${Math.round(span)} ms</span></div>
    <div class="metrics mt"><div class="metric"><div class="label">Parallel time</div><div class="value">${d.elapsedMs} ms</div></div><div class="metric"><div class="label">One thread would take</div><div class="value">${d.serialEstimateMs} ms</div></div><div class="metric"><div class="label">Throughput gain</div><div class="value">${gain.toFixed(1)}×</div></div></div>`;
}

Object.assign(ACTIONS, {
  dpSend: async (b) => {
    const d = await act(b, '/dispatch', { requests: [{ train: $('#dp-train').value, movement: $('#dp-move').value, track: $('#dp-track').value }] }, (r) => {
      const c = r.commits[0];
      return c ? (c.accepted ? `🚆 ${c.train} cleared on ${c.track} (state v${c.version})` : `⛔ ${c.train} held: ${esc(c.reason.split('→')[0])}`) : '✗ No controller available';
    });
    if (d) showDispatch(d);
  },
});

/* =========================================================
   RESILIENCE
   ========================================================= */

page({
  id: 'resilience', title: 'Resilience', icon: ICON.shield,
  render: () => `
    <div class="page-head">
      <div><h1>Resilience</h1><p>Every write is mirrored before it counts. Failures are detected with heartbeats, the network heals itself, and a retried request is never applied twice.</p></div>
      <div class="actions">
        <label class="switch"><input type="checkbox" id="rs-heal" ${App.autoHeal ? 'checked' : ''}><span class="track"><span class="knob"></span></span><span class="switch-label">Self-healing</span></label>
        <button class="btn danger" data-act="rsCrashWrite">Crash control centre mid-write</button>
        <button class="btn warn" data-act="rsCrashMirror">Crash a mirror</button>
        <button class="btn success" data-act="rsRecoverAll">Restore all stations</button>
      </div>
    </div>
    <div class="metrics" id="rs-metrics"></div>
    <div class="grid cols-2 mt">
      <div class="panel">
        <div class="panel-head"><h3>Heartbeat monitor</h3><span class="sub">a mirror watches the control centre</span>${info('A mirror sends a <b>heartbeat</b> every 150 ms. One missed reply could be a delay, so the control centre is declared failed only after <b>3 consecutive misses</b>. Then the most up-to-date mirror with the highest ID takes over (<b>failover</b>), so no committed data is lost.')}<span class="right"><button class="btn sm" data-act="rsDetect">Run health check</button></span></div>
        <div id="rs-beats"></div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Mirror integrity</h3><span class="sub">version and fingerprint of every copy</span>${info('<b>Primary-backup replication, strongly consistent.</b> Only the control centre writes. Each write gets the next version and is sent to every mirror; it counts only after <b>every mirror acknowledges</b>. A copy accepts only version + 1, so all copies apply the same writes in the same order.')}<span class="right"><button class="btn sm" data-act="rsVerify">Verify</button></span></div>
        <div id="rs-mirrors"></div>
      </div>
    </div>
    <div class="grid cols-2 mt">
      <div class="panel">
        <div class="panel-head"><h3>Write to network state</h3><span class="sub">see the replication protocol step by step</span></div>
        <div class="row"><select id="rs-track">${TRACKS.map((t) => `<option>${t}</option>`).join('')}</select><select id="rs-status"><option value="OCCUPIED">Occupied</option><option value="CLEAR">Clear</option></select><input type="text" id="rs-train" value="TRAIN-450" style="width:140px"><button class="btn primary" data-act="rsWrite">Commit</button></div>
        <div class="trace mt" id="rs-trace"></div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Request journal</h3><span class="sub">every attempt of every request ID</span>${info('Each client request carries a <b>unique ID</b>, and the table of applied IDs is mirrored with the data. If the control centre crashes after committing but before replying, the client retries with the same ID and the new control centre <b>recognises it</b>, so nothing is applied twice.')}</div>
        <div id="rs-journal" class="scroll" style="max-height:300px"></div>
      </div>
    </div>
    <div class="panel mt">
      <div class="panel-head"><h3>Network partition</h3><span class="sub">cut links between stations · terms stop a split brain</span>${info('A <b>network partition</b> cuts stations apart while they keep running. A cut-off control centre looks dead to the others, so they fail over, but it is alive and still <b>believes it leads</b>: two leaders = <b>split brain</b>.<br><br>Every leader works under a <b>term</b> number, increased at each failover, and every copy remembers the highest term it has seen. When the old leader writes with its old term, the copies <b>reject</b> it; it learns the new term, steps down and resynchronises.')}
        <span class="right"><button class="btn sm danger" data-act="ptIsolate">Cut off control centre</button><button class="btn sm" data-act="rsDetect">Run health check</button><button class="btn sm success" data-act="ptHeal">Heal network</button><button class="btn sm warn" data-act="ptStale">Old leader writes</button></span></div>
      <div class="grid cols-2"><div><svg viewBox="0 0 440 300" id="pt-svg" class="pt-svg"></svg><div class="dim" style="font-size:12px;text-align:center">click a link to cut or restore it</div></div><div id="pt-info"></div></div>
    </div>
    <div class="panel mt">
      <div class="panel-head"><h3>Coordinator election</h3><span class="sub">choose the junction coordinator among live stations</span>${info('<b>Bully:</b> a station that notices the coordinator is gone challenges every higher ID; the highest live station wins and announces itself.<br><b>Ring:</b> an election message travels round a logical ring collecting IDs; the highest becomes coordinator.<br>The control centre is chosen separately: only an up-to-date mirror may take that role.')}<span class="right"><button class="btn sm violet" data-act="elect" data-algo="bully">Run Bully election</button><button class="btn sm violet" data-act="elect" data-algo="ring">Run Ring election</button></span></div>
      <div class="grid cols-2"><div class="wheel" id="rs-ring"></div><div id="rs-messages" class="scroll" style="max-height:330px"></div></div>
    </div>`,
  mount() {
    $('#rs-heal').addEventListener('change', (e) => { App.autoHeal = e.target.checked; toast(App.autoHeal ? 'Self-healing on: failures are handled automatically' : 'Self-healing off: run the health check yourself'); });
    $('#rs-ring').innerHTML = ringSkeleton();
    $('#pt-svg').addEventListener('click', async (ev) => {
      const l = ev.target.closest('.pt-link');
      if (l) await act(null, '/network/link', { a: l.dataset.a, b: l.dataset.b });
    });
  },
  update() {
    partitionView(App.state);
    const s = App.state; const c = s.client;
    const primary = station(s.primary);
    const synced = s.backups.map(station).filter((m) => m && m.active && m.version === primary.version).length;
    view('#rs-metrics').html = [
      ['Control centre', place(s.primary), s.primaryActive ? 'online' : 'OFFLINE'],
      ['Mirrors in sync', `${synced}/${s.backups.length}`, s.backups.map(place).join(', ') || 'none'],
      ['Failovers', (App.state.failovers ?? 0), 'automatic takeovers'],
      ['Requests protected', c.committed, 'committed exactly once'],
      ['Duplicates blocked', c.duplicates, `${c.retries} retries`],
      ['Awaiting retry', c.pending.length, c.pending.join(', ') || 'none'],
    ].map(([l, v, h]) => `<div class="metric"><div class="label">${l}</div><div class="value">${esc(v)}</div><div class="hint">${esc(h)}</div></div>`).join('');

    const d = s.results.detection;
    view('#rs-beats').html = d ? `<div class="row" style="margin-bottom:12px">${chip('monitor · ' + place(d.monitor), 'c-violet')} <span class="dim">→</span> ${chip('control · ' + place(d.target), 'c-cyan')}</div>
        <div class="beats">${(d.declaredFailed ? ['✗', '✗', '✗'] : ['♥', '♥', '♥', '♥', '♥']).map((x, i) => `<div class="beat ${d.declaredFailed ? 'miss' : 'ack'}" style="animation-delay:${i * 180}ms">${x}</div>`).join('')}</div>
        <div class="mt" style="font-size:13px">${d.declaredFailed ? `<span class="bad">${esc(place(d.target))} declared failed after 3 missed heartbeats.</span> ${d.newPrimary ? `<b>${esc(place(d.newPrimary))}</b> took over as control centre with no data loss.` : esc(d.error || '')}` : `<span class="ok">${esc(place(d.target))} answered every heartbeat.</span> No action needed.`}</div>`
      : '<div class="empty">No health check yet. Crash the control centre and it runs automatically, or press “Run health check”.</div>';

    const members = [primary, ...s.backups.map(station)];
    const ref = primary;
    view('#rs-mirrors').html = `<table><thead><tr><th>Station</th><th>Role</th><th>Version</th><th>Fingerprint</th><th></th></tr></thead><tbody>${members.map((m) => {
      const same = m.version === ref.version && m.checksum === ref.checksum;
      return `<tr><td><b>${esc(place(m.name))}</b></td><td>${roleChip(m)}</td><td class="mono">v${m.version}</td><td class="mono">${esc(m.checksum)}</td><td>${same ? '<span class="ok">✓ identical</span>' : '<span class="bad">✗ behind</span>'}</td></tr>`;
    }).join('')}</tbody></table>
      ${s.results.consistency ? `<div class="mt-s ${s.results.consistency.consistent ? 'ok' : 'bad'}" style="font-size:13px">${s.results.consistency.consistent ? '✓ Last verification: all copies identical (version, full table and fingerprint)' : '✗ Last verification found a difference'}</div>` : ''}`;

    view('#rs-trace').html = local.trace ? traceHtml(local.trace) : '<span class="tt">Commit a change to watch: control centre update → replicate → mirror applies → acknowledgement.</span>';

    view('#rs-journal').html = c.history.length ? `<table><thead><tr><th>Request</th><th>Try</th><th>Movement</th><th>Handled by</th><th>Outcome</th></tr></thead><tbody>${[...c.history].reverse().slice(0, 40).map((h) => `<tr><td class="mono"><b>${esc(h.requestId)}</b></td><td>${h.attempt}</td><td class="mono" style="font-size:12px;white-space:nowrap">${esc(h.trainId)} · ${esc(h.movement.toLowerCase())} · ${esc(short(h.trackId))}</td><td>${esc(place(h.primary))}</td><td>${{ COMMITTED: chip('COMMITTED v' + h.version, 'c-green'), DUPLICATE: chip('DUPLICATE BLOCKED', 'c-violet'), REJECTED: chip('HELD (UNSAFE)', 'c-amber'), NO_REPLY: chip('NO REPLY', 'c-red') }[h.status]}</td></tr>`).join('')}</tbody></table>` : '<div class="empty">No client requests yet. Autopilot and the crash buttons add some.</div>';

    updateRing();
    view('#rs-messages').html = local.electionEvents ? electionList(local.electionEvents) : `<div class="empty">Current coordinator: <b>${esc(s.coordinator ? place(s.coordinator) : 'none')}</b>. Take it offline on the Network screen, then run an election.</div>`;
  },
});

function traceHtml(events) {
  if (!events.length) return '<span class="tt">(no protocol messages)</span>';
  return events.map((e) => `<span class="tt">${esc(e.time)}</span> <b style="color:${ST_COLOR[e.station]}">${esc(place(e.station))}</b> <span class="tl">λ${e.lamport}</span> ${esc(e.text)}`).join('\n');
}

const RING = { cx: 190, cy: 160, r: 110 };
function ringSkeleton() {
  const nodes = STATIONS.map((n, i) => {
    const a = (-90 + i * 90) * Math.PI / 180;
    const x = RING.cx + RING.r * Math.cos(a); const y = RING.cy + RING.r * Math.sin(a);
    return `<g id="rg-${n}"><circle cx="${x}" cy="${y}" r="32" fill="#0e1a30" stroke-width="3"/><text x="${x}" y="${y - 2}" text-anchor="middle" font-weight="800" font-size="13" fill="#fff">${code(n)}</text><text class="rg-sub" x="${x}" y="${y + 13}" text-anchor="middle" font-size="9.5" fill="#94a3b8">ID ${i + 1}</text><text class="rg-crown" x="${x}" y="${y - 40}" text-anchor="middle" font-size="18" fill="#facc15">♛</text></g>`;
  }).join('');
  return `<svg viewBox="0 0 380 320"><circle cx="${RING.cx}" cy="${RING.cy}" r="${RING.r}" fill="none" stroke="rgba(167,139,250,0.25)" stroke-width="2" stroke-dasharray="5 7" class="data-link" style="stroke:rgba(167,139,250,0.35)"/>${nodes}<g id="rg-packets"></g></svg>`;
}
function ringPos(name) {
  const i = STATIONS.indexOf(name);
  const a = (-90 + i * 90) * Math.PI / 180;
  return [RING.cx + RING.r * Math.cos(a), RING.cy + RING.r * Math.sin(a)];
}
function updateRing() {
  if (!$('#rg-STATION-01')) return;
  for (const st of App.state.stations) {
    const g = $('#rg-' + st.name);
    g.querySelector('circle').setAttribute('stroke', !st.active ? '#f87171' : st.coordinator ? '#facc15' : '#a78bfa');
    g.querySelector('.rg-sub').textContent = st.active ? `ID ${st.id}` : 'OFFLINE';
    g.querySelector('.rg-crown').style.display = st.coordinator ? '' : 'none';
  }
}
function electionList(events) {
  return `<div class="signal-box">${events.map((e, i) => `<div class="sig-row" style="grid-template-columns:auto auto 1fr;animation-delay:${i * 260}ms"><span class="chip c-grey mono">${i + 1}</span><b style="color:${ST_COLOR[e.station]}">${esc(place(e.station))}</b><span style="font-size:12.5px">${esc(e.text.replace('ELECTION → ', ''))} <span class="dim mono">λ${e.lamport}</span></span></div>`).join('')}</div>`;
}
function playElection(events) {
  const layer = $('#rg-packets');
  if (!layer) return;
  events.forEach((e, i) => {
    const m = e.text.match(/(?:sent to|message to|passed to) (STATION-\d+)/);
    if (!m) return;
    setTimeout(() => {
      const [x1, y1] = ringPos(e.station); const [x2, y2] = ringPos(m[1]);
      const c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      c.setAttribute('r', 7); c.setAttribute('fill', e.text.includes('COORDINATOR') ? '#facc15' : '#a78bfa');
      c.setAttribute('filter', 'url(#glowCyan)');
      layer.appendChild(c);
      const t0 = performance.now();
      const step = (now) => {
        const k = Math.min(1, (now - t0) / 600);
        c.setAttribute('cx', x1 + (x2 - x1) * k); c.setAttribute('cy', y1 + (y2 - y1) * k);
        if (k < 1) requestAnimationFrame(step); else setTimeout(() => c.remove(), 150);
      };
      requestAnimationFrame(step);
    }, i * 260);
  });
}

Object.assign(ACTIONS, {
  ptIsolate: (b) => act(b, '/network/isolate', {}),
  ptHeal: (b) => act(b, '/network/heal', {}),
  ptStale: (b) => act(b, '/network/stale-write', {}, (d) => d.result.steppedDown ? `✓ Stale write rejected: ${place(d.result.oldPrimary)} stepped down` : '⚠ Nobody rejected it: the old leader is still isolated'),
  rsDetect: async (b) => {
    const d = await act(b, '/fault/detect', {}, (r) => (r.declaredFailed ? `⚠ ${place(r.target)} declared failed${r.newPrimary ? ` → ${place(r.newPrimary)} took over` : ''}` : `♥ ${place(r.target)} is healthy`));
  },
  rsVerify: (b) => act(b, '/replication/verify', {}, (r) => (r.consistent ? '✓ All copies are identical' : '✗ Copies differ')),
  rsWrite: async (b) => {
    const d = await act(b, '/replication/update', { track: $('#rs-track').value, status: $('#rs-status').value, train: $('#rs-train').value }, (r) => `◆ State v${r.version} committed · acknowledged by ${(r.acknowledgedBy.map(place).join(', ') || 'no mirror')}`);
    if (d) { local.trace = d.events; App.page.update(); }
  },
  rsCrashWrite: async (b) => {
    if (!App.state.primaryActive) { toast('The control centre is already offline', 'bad'); return; }
    const m = plannedMovement();
    const d = await act(b, '/fault/request', { ...m, crash: true }, (r) => `⚠ ${esc(place(r.attempt.primary))} crashed while answering ${esc(r.attempt.requestId)}: the reply was lost`);
    if (d) activity('⚠', 'c-red', `Control centre crashed during <b>${esc(d.attempt.requestId)}</b>`);
  },
  rsCrashMirror: async (b) => {
    const m = App.state.backups.map(station).find((x) => x && x.active);
    if (!m) { toast('No mirror is online', 'bad'); return; }
    act(b, `/stations/${m.name}/fail`, {}, `⚠ Mirror ${esc(place(m.name))} went offline`);
  },
  rsRecoverAll: async (b) => {
    const down = App.state.stations.filter((x) => !x.active);
    if (!down.length) { toast('All stations are already online', 'ok'); return; }
    for (const st of down) await act(b, `/stations/${st.name}/recover`, {}, (r) => '⟳ ' + esc(r.message.replace(st.name, place(st.name))));
  },
  elect: async (b) => {
    const d = await act(b, '/election', { algorithm: b.dataset.algo }, (r) => `♛ ${r.algorithm} election: ${place(r.winner)} is coordinator`);
    if (d) { local.electionEvents = d.events; App.page.update(); playElection(d.events); }
  },
});


/* ---------- network partition ---------- */
const PT_POS = { 'STATION-01': [90, 70], 'STATION-02': [90, 232], 'STATION-03': [350, 70], 'STATION-04': [350, 232] };
function partitionView(s) {
  const cut = new Set(s.cutLinks.map(([a, b]) => a + '|' + b));
  const isCut = (a, b) => cut.has(a < b ? a + '|' + b : b + '|' + a);
  let g = '';
  for (let i = 0; i < 4; i++) for (let j = i + 1; j < 4; j++) {
    const a = STATIONS[i]; const b = STATIONS[j]; const [ax, ay] = PT_POS[a]; const [bx, by] = PT_POS[b];
    const c = isCut(a, b);
    g += `<g class="pt-link" data-a="${a}" data-b="${b}"><line x1="${ax}" y1="${ay}" x2="${bx}" y2="${by}" stroke="transparent" stroke-width="16"/>
      <line x1="${ax}" y1="${ay}" x2="${bx}" y2="${by}" stroke="${c ? '#f87171' : 'rgba(34,211,238,.55)'}" stroke-width="${c ? 2 : 2.5}" ${c ? 'stroke-dasharray="6 5"' : ''}/>
      ${c ? `<text x="${(ax + bx) / 2}" y="${(ay + by) / 2 + 5}" text-anchor="middle" font-size="15" fill="#f87171">✂</text>` : ''}</g>`;
  }
  for (const st of s.stations) {
    const [x, y] = PT_POS[st.name];
    const role = !st.active ? ['OFFLINE', '#f87171'] : st.deposed ? ['THINKS IT LEADS', '#fbbf24'] : st.role === 'PRIMARY' ? ['CONTROL', '#22d3ee'] : st.role === 'BACKUP' ? ['MIRROR', '#a78bfa'] : ['STANDBY', '#7487a5'];
    g += `<circle cx="${x}" cy="${y}" r="27" fill="#0a1222" stroke="${ST_COLOR[st.name]}" stroke-width="3" ${st.deposed ? 'stroke-dasharray="5 3"' : ''}/>
      <text x="${x}" y="${y + 5}" text-anchor="middle" font-size="13" font-weight="800" fill="${ST_COLOR[st.name]}">${code(st.name)}</text>
      <text x="${x}" y="${y - 36}" text-anchor="middle" font-size="12" font-weight="700" fill="#e6edf8">${esc(place(st.name))}</text>
      <text x="${x}" y="${y + 44}" text-anchor="middle" font-size="11" font-weight="700" fill="${role[1]}">${role[0]}</text>
      <text x="${x}" y="${y + 58}" text-anchor="middle" font-size="10.5" font-family="Consolas" fill="#b4c2d8">term ${st.term} · v${st.version}</text>`;
  }
  view('#pt-svg').html = g;
  const r = s.results.staleWrite;
  const dep = s.deposed;
  let info = '';
  if (dep) {
    info += `<div class="pt-alert"><b>⚠ Split-brain risk.</b> ${esc(place(dep))} still believes it is the control centre (term ${station(dep).term}), while ${esc(place(s.primary))} leads term ${s.term}. ${s.cutLinks.length ? 'Heal the network, then let the old leader write.' : 'Let the old leader write: the copies will reject its stale term.'}</div>`;
  } else if (s.cutLinks.length) {
    info += `<div class="pt-note">${s.cutLinks.length} link${s.cutLinks.length > 1 ? 's' : ''} cut. Stations on different sides cannot exchange heartbeats or updates. Run the health check to see who is suspected.</div>`;
  } else {
    info += `<div class="pt-note">All links up. Current term <b>${s.term}</b>, led by <b>${esc(place(s.primary))}</b>. Cut off the control centre, run the health check, heal, and let the old leader write.</div>`;
  }
  if (r) {
    const x = r.result;
    info += `<div class="mt-s"><div class="dim" style="font-size:11.5px;margin-bottom:4px">LAST WRITE BY A DEPOSED LEADER</div>
      <div class="kv"><span>Old leader</span><b>${esc(place(x.oldPrimary))} · term ${x.oldTerm}</b></div>
      <div class="kv"><span>Rejected by</span><b class="ok">${x.rejectedBy.map(place).join(', ') || '—'}</b></div>
      <div class="kv"><span>Accepted by</span><b class="${x.acceptedBy.length ? 'bad' : ''}">${x.acceptedBy.map(place).join(', ') || 'nobody'}</b></div>
      <div class="kv"><span>Unreachable</span><b>${x.unreachable.map(place).join(', ') || '—'}</b></div>
      <div class="mt-s">${x.steppedDown ? chip(`✓ stale term ${x.oldTerm} < ${x.currentTerm}: stepped down and resynchronised`, 'c-green') : chip('⚠ still isolated: nobody told it about the new term', 'c-amber')}</div></div>`;
  }
  view('#pt-info').html = info;
}

/* =========================================================
   SECTION CONTROL (distributed mutual exclusion)
   ========================================================= */

const SEC_POS = { 'STATION-01': [120, 78], 'STATION-02': [120, 282], 'STATION-03': [680, 78], 'STATION-04': [680, 282] };
const SEC_SPEED = 0.45; // replay at less than real speed so messages can be followed

page({
  id: 'section', title: 'Tunnel', icon: ICON.section,
  render: () => `
    <div class="page-head">
      <div><h1>Tunnel Control</h1><p>Hillcrest Tunnel is single-track: only one train may be inside. The stations decide who goes next purely by exchanging messages; there is no central lock.</p></div>
    </div>
    <div class="grid side-main">
      <div class="panel">
        <div class="panel-head"><h3>Request the tunnel</h3>${info('<b>Ricart–Agrawala mutual exclusion.</b><br>A station that wants the tunnel sends <b>REQUEST(λ, id)</b> to every other station and waits for a <b>REPLY</b> from all of them.<br>A station receiving a request replies at once, unless it is inside the tunnel or wants it itself with an older request (smaller λ, ties broken by smaller id). Then it <b>defers</b> the reply until it leaves.<br>So exactly one request collects every reply first. Cost: 2(N−1) messages per entry.')}</div>
        <div class="dim" style="font-size:12.5px;margin-bottom:8px">Stations that want to send a train through at the same moment:</div>
        <div class="sec-picks">${STATIONS.map((s, i) => `<label class="sec-pick" style="--c:${ST_COLOR[s]}"><input type="checkbox" value="${s}" ${i !== 1 ? 'checked' : ''}><span>${esc(place(s))}</span></label>`).join('')}</div>
        <div class="row mt"><button class="btn primary" data-act="secRequest">Request together</button><button class="btn" data-act="secReplay">Replay</button></div>
        <div id="sec-verdict" class="mt"></div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Message flow</h3><span class="sub">replayed from the real run · <span style="color:var(--cyan)">REQUEST</span> · <span style="color:var(--green)">REPLY</span> · <span style="color:var(--amber)">deferred</span></span><span class="right mono dim" id="sec-clock"></span></div>
        <div class="sec-stage"><svg viewBox="0 0 800 360" id="sec-svg"></svg></div>
        <div class="sec-progress"><div id="sec-bar"></div></div>
      </div>
    </div>
    <div class="panel mt">
      <div class="panel-head"><h3>Order of passage</h3><span class="sub">who entered when, and who made them wait</span></div>
      <div id="sec-table"></div>
    </div>`,
  mount() { local.secT = 0; secPlay(false); },
  leave() { cancelAnimationFrame(local.secRaf); },
  update() {
    const r = App.state.results.section;
    for (const box of $$('.sec-pick input')) {
      const st = station(box.value);
      box.disabled = !st.active;
      box.closest('.sec-pick').classList.toggle('off', !st.active);
    }
    if (!r) {
      view('#sec-verdict').html = '<div class="empty">Pick two or more stations and send their requests at the same moment.</div>';
      view('#sec-table').html = '<div class="empty">No train has used the tunnel yet.</div>';
      return;
    }
    const x = r.result;
    const waits = x.passages.map((p) => p.enteredAtMs - p.requestedAtMs);
    view('#sec-verdict').html = `<div class="metrics"><div class="metric"><div class="label">Messages</div><div class="value">${x.messages.length}</div><div class="hint">2(N−1) = ${2 * (x.participants.length - 1)} per request</div></div><div class="metric"><div class="label">Most inside</div><div class="value ${x.maxInside <= 1 ? 'ok' : 'bad'}">${x.maxInside}</div><div class="hint">at any moment</div></div><div class="metric"><div class="label">Longest wait</div><div class="value">${Math.max(...waits)} ms</div><div class="hint">request → enter</div></div></div>
      <div class="mt-s">${x.mutualExclusion ? chip('✓ never two trains in the tunnel', 'c-green') : chip('✗ mutual exclusion violated', 'c-red')}</div>`;
    view('#sec-table').html = `<table><thead><tr><th>#</th><th>Train</th><th>From</th><th>Request λ</th><th>Waited</th><th>In tunnel</th><th>Reply deferred by</th></tr></thead><tbody>${x.passages.map((p, i) => `<tr><td>${i + 1}</td><td class="mono"><b>${esc(p.train)}</b></td><td style="color:${ST_COLOR[p.station]};font-weight:700">${esc(place(p.station))}</td><td class="mono">λ${p.requestTimestamp}</td><td class="mono">${p.enteredAtMs - p.requestedAtMs} ms</td><td class="mono">${p.enteredAtMs} → ${p.leftAtMs} ms</td><td>${p.deferredBy.length ? p.deferredBy.map((s) => `<span style="color:${ST_COLOR[s]}">${esc(place(s))}</span>`).join(', ') : '<span class="dim">nobody: went first</span>'}</td></tr>`).join('')}</tbody></table>`;
    if (local.secPlayedFor !== x) { local.secPlayedFor = x; secPlay(true); }
  },
});

/** Everything visible at time t (ms since the requests were issued). */
function secFrame(x, t) {
  let g = '';
  // tunnel
  const occupant = x ? x.passages.find((p) => p.enteredAtMs <= t && t < p.leftAtMs) : null;
  g += `<path d="M290,212 L290,160 Q400,108 510,160 L510,212" fill="rgba(148,163,184,0.06)" stroke="${occupant ? '#34d399' : 'rgba(148,163,184,0.35)'}" stroke-width="2.5"/>
    <line x1="230" y1="206" x2="570" y2="206" stroke="rgba(148,163,184,0.5)" stroke-width="3"/>
    <line x1="230" y1="214" x2="570" y2="214" stroke="rgba(148,163,184,0.5)" stroke-width="3"/>
    <text x="400" y="240" text-anchor="middle" font-size="12" fill="#7487a5" font-weight="700" letter-spacing="2">HILLCREST TUNNEL · SINGLE TRACK</text>`;
  if (occupant) {
    const p = (t - occupant.enteredAtMs) / Math.max(1, occupant.leftAtMs - occupant.enteredAtMs);
    const tx = 250 + p * 300;
    g += `<rect x="${tx - 34}" y="${192}" width="68" height="16" rx="5" fill="${ST_COLOR[occupant.station]}" filter="url(#secGlow)"/><text x="${tx}" y="${186}" text-anchor="middle" font-size="11" font-weight="700" fill="#e6edf8">${esc(occupant.train)}</text>`;
  } else {
    g += `<text x="400" y="190" text-anchor="middle" font-size="12" fill="#34d399">${x && t > 0 && t < x.elapsedMs ? '' : 'free'}</text>`;
  }
  // stations
  for (const st of STATIONS) {
    const [sx, sy] = SEC_POS[st];
    const p = x ? x.passages.find((q) => q.station === st) : null;
    let label = 'idle'; let color = '#7487a5';
    if (p && p.requestedAtMs <= t && t < p.enteredAtMs) { label = `WANTED · λ${p.requestTimestamp}`; color = '#fbbf24'; }
    else if (p && p.enteredAtMs <= t && t < p.leftAtMs) { label = 'IN TUNNEL'; color = '#34d399'; }
    else if (p && t >= p.leftAtMs) { label = 'done'; color = '#22d3ee'; }
    const part = !x || x.participants.includes(st);
    // replies this station is holding back at time t
    const held = x ? x.messages.filter((m) => m.type === 'REQUEST' && m.to === st && m.deliveredAtMs <= t && (() => {
      const reply = x.messages.find((r) => r.type === 'REPLY' && r.from === st && r.to === m.from);
      return reply && reply.sentAtMs - m.deliveredAtMs > 25 && t < reply.sentAtMs;
    })()).map((m) => m.from) : [];
    g += `<g opacity="${part ? 1 : 0.3}"><circle cx="${sx}" cy="${sy}" r="30" fill="#0a1222" stroke="${ST_COLOR[st]}" stroke-width="3"/>
      <text x="${sx}" y="${sy + 5}" text-anchor="middle" font-size="13" font-weight="800" fill="${ST_COLOR[st]}">${code(st)}</text>
      <text x="${sx}" y="${sy - 40}" text-anchor="middle" font-size="12.5" font-weight="700" fill="#e6edf8">${esc(place(st))}</text>
      <text x="${sx}" y="${sy + 50}" text-anchor="middle" font-size="11.5" font-weight="700" fill="${color}">${label}</text>
      ${held.length ? `<text x="${sx}" y="${sy + 66}" text-anchor="middle" font-size="11" fill="#fbbf24">holding reply for ${held.map((h) => code(h)).join(', ')}</text>` : ''}
      ${part ? '' : `<text x="${sx}" y="${sy + 66}" text-anchor="middle" font-size="11" fill="#f87171">offline</text>`}</g>`;
  }
  // messages in flight
  if (x) {
    for (const m of x.messages) {
      if (t < m.sentAtMs || t >= m.deliveredAtMs) continue;
      const [ax, ay] = SEC_POS[m.from]; const [bx, by] = SEC_POS[m.to];
      const k = (t - m.sentAtMs) / Math.max(1, m.deliveredAtMs - m.sentAtMs);
      const c = m.type === 'REQUEST' ? '#22d3ee' : '#34d399';
      g += `<line x1="${ax}" y1="${ay}" x2="${bx}" y2="${by}" stroke="${c}" stroke-opacity=".18" stroke-width="1.5"/>
        <circle cx="${ax + (bx - ax) * k}" cy="${ay + (by - ay) * k}" r="6" fill="${c}" filter="url(#secGlow)"/>
        <text x="${ax + (bx - ax) * k}" y="${ay + (by - ay) * k - 10}" text-anchor="middle" font-size="10" font-family="Consolas" fill="${c}">${m.type === 'REQUEST' ? 'REQ' : 'OK'} λ${m.lamport}</text>`;
    }
  }
  return `<defs><filter id="secGlow" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="3" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>${g}`;
}

function secPlay(animate) {
  cancelAnimationFrame(local.secRaf);
  const r = App.state && App.state.results.section;
  const x = r ? r.result : null;
  const svg = $('#sec-svg');
  if (!svg) return;
  if (!x || !animate) {
    const t = x ? x.elapsedMs + 1 : 0;
    svg.innerHTML = secFrame(x, t);
    $('#sec-bar').style.width = x ? '100%' : '0%';
    $('#sec-clock').textContent = x ? `t = ${x.elapsedMs} ms` : '';
    return;
  }
  const start = performance.now();
  const end = x.elapsedMs + 150;
  const tick = (now) => {
    if (!$('#sec-svg')) return;
    const t = Math.min(end, (now - start) * SEC_SPEED);
    svg.innerHTML = secFrame(x, t);
    $('#sec-bar').style.width = (t / end * 100) + '%';
    $('#sec-clock').textContent = `t = ${Math.round(t)} ms · replay ${SEC_SPEED}×`;
    if (t < end) local.secRaf = requestAnimationFrame(tick);
  };
  local.secRaf = requestAnimationFrame(tick);
}

Object.assign(ACTIONS, {
  secRequest: async (b) => {
    const picked = $$('.sec-pick input').filter((i) => i.checked && !i.disabled).map((i) => ({ station: i.value }));
    if (!picked.length) { toast('Pick at least one station', 'bad'); return; }
    await act(b, '/section/request', { requests: picked }, (d) => `🚆 ${d.result.passages.length} train(s) passed one at a time · ${d.result.messages.length} messages`);
  },
  secReplay: () => secPlay(true),
});

/* =========================================================
   TIMETABLE (planned vs actual) + session report
   ========================================================= */

const TT_STATUS = { 'ON TIME': 'c-green', DELAYED: 'c-amber', 'HELD AT SIGNAL': 'c-red', 'AWAITING RETRY': 'c-violet', 'NOT DISPATCHED': 'c-red', PLANNED: 'c-grey' };

page({
  id: 'timetable', title: 'Timetable', icon: ICON.timetable,
  render: () => `
    <div class="page-head">
      <div><h1>Timetable</h1><p>Every train movement with its planned and actual time. A movement is planned for the moment its route could be ready; queues, crashes and retries make it late.</p></div>
      <div class="actions"><button class="btn primary" data-act="ttReport">Export session report</button></div>
    </div>
    <div class="metrics" id="tt-metrics"></div>
    <div class="panel mt">
      <div class="panel-head"><h3>Departures and arrivals</h3><span class="sub">newest first · updates live</span>${info('<b>Planned</b> = request time + the route preparation time of a station (200 ms).<br><b>Actual</b> = when the control centre committed the movement and every mirror acknowledged it.<br>Late by more than 150 ms → <b>delayed</b>: for example a station had to finish another train first, or the control centre crashed and the request was retried on the new one.<br>A conflicting movement is <b>held at a red signal</b> and never runs.')}
        <span class="right"><select id="tt-filter"><option value="">All</option><option>ON TIME</option><option>DELAYED</option><option>HELD AT SIGNAL</option><option>AWAITING RETRY</option></select></span></div>
      <div id="tt-table" class="scroll" style="max-height:560px"></div>
    </div>`,
  mount() {
    $('#tt-filter').value = local.ttFilter || '';
    $('#tt-filter').addEventListener('change', (e) => { local.ttFilter = e.target.value; App.page.update(); });
  },
  update() {
    const tt = App.state.timetable;
    const s = tt.summary;
    const settled = s.onTime + s.delayed;
    view('#tt-metrics').html = [
      ['Movements', s.total, 'since the last reset'],
      ['On time', settled ? Math.round(s.onTime / settled * 100) + '%' : '—', `${s.onTime} of ${settled} completed`],
      ['Delayed', s.delayed, 'more than 150 ms late'],
      ['Average delay', s.avgDelayMs + ' ms', `worst ${s.maxDelayMs} ms`],
      ['Held at signal', s.held, 'conflicting movement refused'],
      ['Waiting', s.waiting, 'retry pending / no station'],
    ].map(([l, v, h]) => `<div class="metric"><div class="label">${l}</div><div class="value">${v}</div><div class="hint">${h}</div></div>`).join('');
    const rows = tt.journeys.filter((j) => !local.ttFilter || j.status === local.ttFilter);
    view('#tt-table').html = rows.length ? `<table><thead><tr><th>#</th><th>Train</th><th>Movement</th><th>Track</th><th>Station</th><th>Requested</th><th>Planned</th><th>Actual</th><th>Delay</th><th>Status</th><th>Note</th></tr></thead><tbody>${rows.map((j) => `<tr>
      <td class="dim">${j.id}</td><td class="mono"><b>${esc(j.train)}</b></td><td>${j.movement === 'ARRIVAL' ? '↘ arrival' : '↗ departure'}</td><td class="mono">${esc(j.track)}</td>
      <td>${j.station ? `<span style="color:${ST_COLOR[j.station]};font-weight:700">${esc(place(j.station))}</span>` : '<span class="dim">—</span>'}</td>
      <td class="mono dim">${esc(j.requestedAt)}</td><td class="mono">${esc(j.plannedAt)}</td><td class="mono">${esc(j.actualAt || '—')}</td>
      <td class="mono ${j.delayMs > 150 ? 'warn' : ''}">${j.delayMs == null ? '—' : '+' + j.delayMs + ' ms'}</td>
      <td>${chip(j.status, TT_STATUS[j.status] || 'c-grey')}</td><td class="dim" style="font-size:12px">${esc(j.note || '')}</td></tr>`).join('')}</tbody></table>`
      : '<div class="empty">No train movements yet. Dispatch trains or switch on Autopilot.</div>';
  },
});

/* ---------- session report (Markdown download) ---------- */

function sessionReport() {
  const s = App.state;
  const now = new Date();
  const L = [];
  const t = s.timetable.summary;
  L.push('# RailNet Ops session report', '', `Generated ${now.toLocaleString()}`, '');
  L.push('## Network', '', '| Station | Place | Status | Role | Term | Version | Lamport | Vector | Clock offset |', '|---|---|---|---|---|---|---|---|---|');
  for (const st of s.stations) {
    L.push(`| ${st.name} | ${place(st.name)} | ${st.active ? 'online' : 'offline'} | ${st.deposed ? 'deposed leader' : st.role.toLowerCase()}${st.coordinator ? ', coordinator' : ''} | ${st.term} | v${st.version} | ${st.lamport} | [${st.vector.join(',')}] | ${st.clockOffsetMs} ms |`);
  }
  L.push('', `- Control centre: **${place(s.primary)}** (term ${s.term}), mirrors: ${s.backups.map(place).join(', ') || 'none'}`);
  L.push(`- State version: v${s.version}; automatic failovers: ${s.failovers}`);
  L.push(`- Cut network links: ${s.cutLinks.length ? s.cutLinks.map(([a, b]) => `${place(a)}–${place(b)}`).join(', ') : 'none'}`);
  L.push(`- Clock skew between stations: ${s.clockSkewMs} ms`, '');
  L.push('## Train movements', '', `- Movements: ${t.total} (on time ${t.onTime}, delayed ${t.delayed}, held at signal ${t.held}, waiting ${t.waiting})`, `- Average delay ${t.avgDelayMs} ms, worst ${t.maxDelayMs} ms`, '');
  if (s.timetable.journeys.length) {
    L.push('| # | Train | Movement | Track | Station | Planned | Actual | Delay | Status | Note |', '|---|---|---|---|---|---|---|---|---|---|');
    for (const j of s.timetable.journeys.slice().reverse()) {
      L.push(`| ${j.id} | ${j.train} | ${j.movement.toLowerCase()} | ${j.track} | ${j.station ? place(j.station) : '—'} | ${j.plannedAt} | ${j.actualAt || '—'} | ${j.delayMs == null ? '—' : j.delayMs + ' ms'} | ${j.status} | ${(j.note || '').replace(/\|/g, '/')} |`);
    }
    L.push('');
  }
  const c = s.client;
  L.push('## Client requests (fault tolerance)', '', `- Sent ${c.sent}, committed exactly once ${c.committed}, rejected ${c.rejected}`, `- Lost replies ${c.timeouts}, retries ${c.retries}, duplicates prevented ${c.duplicates}, still pending ${c.pending.length}`, '');
  const r = s.results;
  L.push('## Latest results', '');
  if (r.election) L.push(`- Election (${r.election.algorithm}): ${place(r.election.winner)} won after ${r.election.messages} messages`);
  if (r.detection) L.push(`- Failure detection: ${place(r.detection.target)} ${r.detection.declaredFailed ? 'declared failed' : 'healthy'}${r.detection.newPrimary ? `, ${place(r.detection.newPrimary)} took over` : ''}`);
  if (r.analytics) L.push(`- Traffic analytics (Spark MapReduce): finished in ${r.analytics.elapsedMs} ms`);
  if (r.collectives) L.push('- Fleet sync (MPI Broadcast/Scatter/Gather): run completed');
  if (r.matrix) L.push('- Route planner (parallel matrix multiplication with MPI): run completed');
  if (r.section) L.push(`- Hillcrest Tunnel (Ricart–Agrawala): ${r.section.result.passages.length} train(s), ${r.section.result.messages.length} messages, mutual exclusion ${r.section.result.mutualExclusion ? 'held' : 'VIOLATED'}`);
  if (r.staleWrite) L.push(`- Deposed leader ${place(r.staleWrite.result.oldPrimary)} wrote with term ${r.staleWrite.result.oldTerm}: rejected by ${r.staleWrite.result.rejectedBy.map(place).join(', ') || 'nobody'}`);
  if (r.clock) L.push(`- Clock synchronization (${r.clock.algorithm}): skew now ${r.clock.skewMs} ms`);
  if (!Object.keys(r).length) L.push('- No experiments run yet in this session');
  L.push('');
  return L.join('\n');
}

Object.assign(ACTIONS, {
  ttReport: () => {
    const blob = new Blob([sessionReport()], { type: 'text/markdown' });
    const a = document.createElement('a');
    const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
    a.href = URL.createObjectURL(blob);
    a.download = `railnet-report-${stamp}.md`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    toast('⇩ Session report downloaded', 'ok');
  },
});

/* =========================================================
   TIMELINE (logical time)
   ========================================================= */

page({
  id: 'timeline', title: 'Timeline', icon: ICON.timeline,
  render: () => `
    <div class="page-head">
      <div><h1>Timeline</h1><p>The causal history of the network. Each event is placed by its logical time, so a cause always appears before its effect, whatever the station clocks say.</p></div>
    </div>
    <div class="panel">
      <div class="panel-head"><h3>Message a station</h3>${info('Every station keeps a <b>Lamport logical clock</b> (λ).<br>Local event: λ = λ + 1.<br>Send: λ = λ + 1, and the message carries λ.<br>Receive: λ = max(λ, λ<sub>message</sub>) + 1.<br>So if one event caused another, it always has the smaller λ.')}</div>
      <div class="row">
        <select id="tl-from">${STATIONS.map((s) => `<option value="${s}">${place(s)}</option>`).join('')}</select><span class="dim">→</span>
        <select id="tl-to">${STATIONS.map((s, i) => `<option value="${s}" ${i === 2 ? 'selected' : ''}>${place(s)}</option>`).join('')}</select>
        <input type="text" id="tl-text" value="route update" style="width:180px">
        <button class="btn primary" data-act="tlSend">Send message</button>
        <span class="dim" style="margin-left:14px">or a local event at</span>
        <select id="tl-local">${STATIONS.map((s) => `<option value="${s}">${place(s)}</option>`).join('')}</select>
        <button class="btn" data-act="tlLocal">Log event</button>
      </div>
      <div class="mt-s" id="tl-rule"></div>
    </div>
    <div class="panel mt">
      <div class="panel-head"><h3>Station clocks</h3><span class="sub">physical time · offset from GPS time</span>${info('Every station has its own <b>physical clock</b>. Each starts with a different error (offset) and runs slightly fast or slow (drift, in ppm).<br><br><b>Sync with GPS</b> runs <b>Cristian\'s algorithm</b>: the station asks a time server, measures the round trip (RTT) and sets its clock to server time + RTT/2.<br><br><b>Berkeley round</b>: the coordinator polls every clock, leaves out clocks that are more than ±2 s off, averages the rest and sends each station its own correction, so the clocks agree with each other.')}
        <span class="right"><select id="clk-station">${STATIONS.map((s) => `<option value="${s}">${place(s)}</option>`).join('')}</select><button class="btn sm" data-act="clkCristian">Sync with GPS</button><button class="btn sm primary" data-act="clkBerkeley">Berkeley round</button><button class="btn sm warn" data-act="clkDisturb">Disturb clocks</button></span></div>
      <div class="clk-faces" id="clk-faces"></div>
      <div class="clk-scale" id="clk-scale"><div class="clk-axis"></div><div class="clk-zero"><span>GPS time</span></div>${STATIONS.map((s, i) => `<div class="clk-dot r${i}" data-st="${s}" style="--c:${ST_COLOR[s]}"><span>${esc(place(s))}</span></div>`).join('')}<div class="clk-ticks"><span>−4 s</span><span>−2 s</span><span>0</span><span>+2 s</span><span>+4 s</span></div></div>
      <div id="clk-result" class="mt-s"></div>
    </div>
    <div class="panel mt">
      <div class="panel-head"><h3>Causal timeline</h3><span class="sub">x = logical time λ · arrows = messages · click an event to see its causal relations</span>${info('Each event also carries a <b>vector clock</b> V: one counter per station.<br>Event: V[own] + 1. Receive: V = max(V, V<sub>message</sub>), then V[own] + 1.<br><b>a → b</b> (a happened before b) exactly when V(a) ≤ V(b) in every entry and they differ.<br>If neither is ≤ the other, the events are <b>concurrent</b>: no message chain connects them. Lamport times alone cannot tell this.')}
        <span class="right"><span class="dim" style="font-size:12px">show last</span><select id="tl-win"><option>40</option><option selected>80</option><option>160</option></select></span></div>
      <div id="tl-rel" class="mt-s"></div>
      <div class="tl-scroll" id="tl-chart"></div>
    </div>
    <div class="grid cols-2 mt">
      <div class="panel"><div class="panel-head"><h3>Logical clocks</h3></div><div class="metrics" id="tl-clocks"></div></div>
      <div class="panel"><div class="panel-head"><h3>Total order</h3><span class="sub">sorted by (λ, station)</span></div><div id="tl-order" class="scroll" style="max-height:320px"></div></div>
    </div>`,
  mount() {
    local.clkTimer = setInterval(tickClocks, 97);
    $('#tl-win').value = String(local.tlWindow);
    $('#tl-chart').addEventListener('click', (ev) => {
      const c = ev.target.closest('circle[data-seq]');
      local.tlSelected = c ? (+c.dataset.seq === local.tlSelected ? null : +c.dataset.seq) : null;
      App.page.update();
    });
    $('#tl-win').addEventListener('change', (e) => { local.tlWindow = +e.target.value; App.page.update(); });
  },
  leave() { clearInterval(local.clkTimer); },
  update() {
    const s = App.state;
    updateClocks(s);
    view('#tl-clocks').html = s.stations.map((st) => `<div class="metric" style="border-top:3px solid ${ST_COLOR[st.name]}"><div class="label">${esc(place(st.name))}</div><div class="value mono">λ ${st.lamport}</div><div class="hint mono">V ${vec(st.vector)} · ${st.active ? 'online' : 'offline'}</div></div>`).join('');
    const win = App.events.slice(-local.tlWindow);
    const sel = win.find((e) => e.seq === local.tlSelected);
    if (sel) {
      const count = { before: 0, after: 0, concurrent: 0 };
      for (const e of win) { if (e.seq === sel.seq) continue; const o = vcOrder(e.vector, sel.vector); if (o === -1) count.before++; else if (o === 1) count.after++; else if (o === 0) count.concurrent++; }
      view('#tl-rel').html = `<div class="clk-steps"><span class="chip c-grey">selected</span><b style="color:${ST_COLOR[sel.station]}">${esc(place(sel.station))}</b><span class="mono">λ${sel.lamport} · V=${vec(sel.vector)}</span><span class="dim">${esc(sel.text)}</span>
        <span style="color:${REL_COLOR.before}">● ${count.before} happened before</span><span style="color:${REL_COLOR.after}">● ${count.after} happened after</span><span style="color:${REL_COLOR.concurrent}">◌ ${count.concurrent} concurrent</span></div>`;
    } else {
      view('#tl-rel').html = '';
    }
    view('#tl-chart').html = timelineSvg(App.events.slice(-local.tlWindow));
    const ordered = App.events.slice(-30).sort((a, b) => a.lamport - b.lamport || a.station.localeCompare(b.station));
    view('#tl-order').html = ordered.length ? `<table><thead><tr><th>λ</th><th>Vector</th><th>Station</th><th>Wall clock</th><th>Event</th></tr></thead><tbody>${ordered.map((e) => `<tr><td class="mono"><b>${e.lamport}</b></td><td class="mono dim">${vec(e.vector)}</td><td style="color:${ST_COLOR[e.station]};font-weight:700">${esc(place(e.station))}</td><td class="mono dim">${esc(e.time)}</td><td style="font-size:12.5px">${esc(e.text)}</td></tr>`).join('')}</tbody></table>` : '<div class="empty">No events yet.</div>';
  },
});

/* Vector clocks: -1 a happened before b, 1 after, 0 concurrent, null unknown. */
const vec = (v) => (v ? '[' + v.join(',') + ']' : '—');
function vcOrder(a, b) {
  if (!a || !b) return null;
  let less = false; let more = false;
  for (let i = 0; i < a.length; i++) { if (a[i] < b[i]) less = true; else if (a[i] > b[i]) more = true; }
  if (less && !more) return -1;
  if (more && !less) return 1;
  return less && more ? 0 : null; // equal vectors: same event
}
const REL_COLOR = { before: '#22d3ee', after: '#a78bfa', concurrent: '#fbbf24' };

function timelineSvg(events) {
  if (!events.length) return '<div class="empty" style="margin:16px">No events yet.</div>';
  const minL = Math.min(...events.map((e) => e.lamport));
  const maxL = Math.max(...events.map((e) => e.lamport));
  const px = Math.max(18, Math.min(90, 1180 / Math.max(1, maxL - minL)));
  const left = 140; const laneH = 74; const top = 34;
  const W = left + (maxL - minL) * px + 70; const H = top + laneH * 4 + 26;
  const x = (l) => left + (l - minL) * px;
  const y = (st) => top + STATIONS.indexOf(st) * laneH + laneH / 2;
  let svg = '';
  STATIONS.forEach((st, i) => {
    svg += `<rect x="0" y="${top + i * laneH}" width="${W}" height="${laneH}" fill="${i % 2 ? 'rgba(148,163,184,0.03)' : 'transparent'}"/>
      <line x1="${left - 10}" y1="${y(st)}" x2="${W - 20}" y2="${y(st)}" stroke="${ST_COLOR[st]}" stroke-opacity=".35" stroke-width="2"/>
      <text x="16" y="${y(st) + 4}" font-size="13" font-weight="700" fill="${ST_COLOR[st]}">${esc(place(st))}</text>`;
  });
  for (let l = Math.ceil(minL / 5) * 5; l <= maxL; l += 5) {
    svg += `<line x1="${x(l)}" y1="${top - 6}" x2="${x(l)}" y2="${H - 22}" stroke="rgba(148,163,184,0.08)"/><text x="${x(l)}" y="${top - 12}" text-anchor="middle" font-size="10" font-family="Consolas" fill="#6b7fa3">λ${l}</text>`;
  }
  // message arrows (receive events name their sender and the send time)
  for (const e of events) {
    const m = e.text.match(/from (STATION-\d+) \(msg L=(\d+)\)/);
    if (!m || +m[2] < minL) continue;
    svg += `<line x1="${x(+m[2])}" y1="${y(m[1])}" x2="${x(e.lamport)}" y2="${y(e.station)}" stroke="#a78bfa" stroke-width="1.6" stroke-opacity=".75" marker-end="url(#tlArrow)"/>`;
  }
  const sel = events.find((e) => e.seq === local.tlSelected);
  for (const e of events) {
    const kind = e.text.includes('FAILURE') ? '#f87171' : e.text.startsWith('ELECTION') ? '#facc15' : e.text.startsWith('RPC') ? '#22d3ee' : ST_COLOR[e.station];
    let ring = '';
    if (sel) {
      if (e.seq === sel.seq) ring = `<circle cx="${x(e.lamport)}" cy="${y(e.station)}" r="12" fill="none" stroke="#fff" stroke-width="2.5"/>`;
      else {
        const o = vcOrder(e.vector, sel.vector);
        const rel = o === -1 ? 'before' : o === 1 ? 'after' : o === 0 ? 'concurrent' : null;
        if (rel) ring = `<circle cx="${x(e.lamport)}" cy="${y(e.station)}" r="10" fill="none" stroke="${REL_COLOR[rel]}" stroke-width="2"${rel === 'concurrent' ? ' stroke-dasharray="3 2"' : ''}/>`;
      }
    }
    svg += `${ring}<circle data-seq="${e.seq}" style="cursor:pointer" cx="${x(e.lamport)}" cy="${y(e.station)}" r="6" fill="${kind}" stroke="#060b16" stroke-width="2"${sel && e.seq !== sel.seq ? ' opacity=".55"' : ''}><title>λ${e.lamport} · V=${vec(e.vector)} · ${esc(place(e.station))} · ${esc(e.time)}\n${esc(e.text)}</title></circle>`;
  }
  return `<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><defs><marker id="tlArrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#a78bfa"/></marker></defs>${svg}</svg>`;
}

/* ---------- physical clocks (Cristian / Berkeley) ---------- */

const pad3 = (n) => String(n).padStart(3, '0');
const fmtClock = (ms) => { const d = new Date(ms); return d.toTimeString().slice(0, 8) + '.' + pad3(d.getMilliseconds()); };
const signed = (v) => (v > 0 ? '+' : v < 0 ? '−' : '±') + Math.abs(Math.round(v)).toLocaleString() + ' ms';

function updateClocks(s) {
  local.clkBase = { server: s.serverTimeMs, at: performance.now() };
  view('#clk-faces').html = s.stations.map((st) => `<div class="clk-face" style="--c:${ST_COLOR[st.name]}">
      <div class="clk-name">${esc(place(st.name))}${st.coordinator ? ' <span class="chip c-gold">master</span>' : ''}${st.active ? '' : ' <span class="chip c-red">offline</span>'}</div>
      <div class="clk-time mono" data-off="${st.clockOffsetMs}">--:--:--.---</div>
      <div class="clk-meta"><span class="${Math.abs(st.clockOffsetMs) > 2000 ? 'bad' : ''}">${signed(st.clockOffsetMs)}</span><span class="dim">drift ${st.driftPpm > 0 ? '+' : ''}${st.driftPpm} ppm</span></div>
    </div>`).join('') + `<div class="clk-face skew"><div class="clk-name">Clock skew</div><div class="clk-time mono">${s.clockSkewMs.toLocaleString()} ms</div><div class="clk-meta"><span class="dim">largest gap between two clocks</span></div></div>`;
  for (const st of s.stations) {
    const dot = document.querySelector(`.clk-dot[data-st="${st.name}"]`);
    if (!dot) continue;
    const pct = 50 + Math.max(-4400, Math.min(4400, st.clockOffsetMs)) / 4400 * 46;
    dot.style.left = pct + '%';
    dot.classList.toggle('off', !st.active);
  }
  const r = s.results.clock;
  if (!r) { view('#clk-result').html = '<div class="empty">Disturb the clocks, then synchronise them with GPS (Cristian) or with each other (Berkeley).</div>'; return; }
  const x = r.result;
  if (r.algorithm === 'Cristian') {
    view('#clk-result').html = `<div class="clk-steps"><span class="chip c-cyan">Cristian · ${esc(place(x.client))}</span>
      <span>T0 <b class="mono">${fmtClock(x.t0)}</b></span><span>→ server Ts <b class="mono">${fmtClock(x.serverTime)}</b></span><span>→ T1 <b class="mono">${fmtClock(x.t1)}</b></span>
      <span>RTT <b>${x.roundTripMs} ms</b></span><span>new time = Ts + RTT/2 → adjust <b>${signed(x.adjustmentMs)}</b></span><span class="dim">error ≤ ±${Math.round(x.errorBoundMs)} ms</span></div>`;
  } else {
    view('#clk-result').html = `<div class="clk-steps"><span class="chip c-gold">Berkeley · master ${esc(place(x.master))}</span><span>average of included clocks <b>${signed(x.averageOffsetMs)}</b></span><span>skew <b>${Math.round(x.skewBeforeMs).toLocaleString()} ms → ${Math.round(x.skewAfterMs)} ms</b></span>${x.unreachable.length ? `<span class="bad">no reply: ${x.unreachable.map(place).join(', ')}</span>` : ''}</div>
      <table class="mt-s"><thead><tr><th>Station</th><th>Reading</th><th>RTT</th><th>Offset vs master</th><th>In average</th><th>Correction sent</th></tr></thead><tbody>${x.readings.map((g) => `<tr><td style="color:${ST_COLOR[g.station]};font-weight:700">${esc(place(g.station))}</td><td class="mono">${fmtClock(g.reading)}</td><td class="mono">${g.roundTripMs} ms</td><td class="mono">${signed(g.estimatedOffsetMs)}</td><td>${g.included ? chip('yes', 'c-green') : chip('outlier', 'c-red')}</td><td class="mono"><b>${signed(g.adjustmentMs)}</b></td></tr>`).join('')}</tbody></table>`;
  }
}

function tickClocks() {
  const base = local.clkBase;
  if (!base) return;
  const now = base.server + (performance.now() - base.at);
  for (const el of document.querySelectorAll('.clk-time[data-off]')) el.textContent = fmtClock(now + Number(el.dataset.off));
}

Object.assign(ACTIONS, {
  clkCristian: (b) => act(b, '/clocks/cristian', { station: $('#clk-station').value }, (r) => `⏱ ${place(r.result.client)} synced with GPS: adjusted ${signed(r.result.adjustmentMs)} (RTT ${r.result.roundTripMs} ms)`),
  clkBerkeley: (b) => act(b, '/clocks/berkeley', {}, (r) => `⏱ Berkeley round: skew now ${r.skewMs} ms`),
  clkDisturb: (b) => act(b, '/clocks/disturb', {}),
});

Object.assign(ACTIONS, {
  tlSend: async (b) => {
    const d = await act(b, '/lamport/message', { from: $('#tl-from').value, to: $('#tl-to').value, label: $('#tl-text').value }, (r) => `✉ delivered · receive rule ${r.rule}`);
    if (d) view('#tl-rule').html = `<span class="chip c-violet">send λ${d.sendTimestamp} · V=${vec(d.sendVector)}</span> <span class="dim">→ receiver was at λ${d.receiverBefore} →</span> <span class="chip c-cyan mono">λ = ${esc(d.rule)}</span> <span class="chip c-gold mono">V = ${vec(d.receiveVector)}</span>`;
  },
  tlLocal: async (b) => {
    const d = await act(b, '/lamport/local', { station: $('#tl-local').value, label: 'signal check' }, (r) => `• ${r.rule}`);
    if (d) view('#tl-rule').html = `<span class="chip c-grey mono">${esc(d.rule)}</span>`;
  },
});

/* =========================================================
   INSIGHTS (Apache Spark)
   ========================================================= */

page({
  id: 'insights', title: 'Insights', icon: ICON.insights,
  render: () => `
    <div class="page-head">
      <div><h1>Insights</h1><p>Traffic analytics over every station's event log, computed with Apache Spark. Results reflect everything that has happened on the network so far.</p></div>
      <div class="actions"><button class="btn violet" data-act="analyse">Run traffic analysis</button></div>
    </div>
    <div id="in-body"></div>`,
  update() {
    const a = App.state.results.analytics;
    if (!a) { view('#in-body').html = '<div class="panel"><div class="empty">Press “Run traffic analysis”. The first run starts Apache Spark (about 10–20 s); later runs are quick.</div></div>'; return; }
    const r = a.result;
    const maxV = Math.max(1, ...Object.values(r.reduced));
    view('#in-body').html = `
      <div class="metrics">
        <div class="metric"><div class="label">Events analysed</div><div class="value">${r.totalEvents}</div><div class="hint">from ${r.partitions.length} station logs</div></div>
        <div class="metric"><div class="label">Busiest track</div><div class="value">${esc(r.busiestTrack)}</div><div class="hint">${r.reduced[r.busiestTrack] || 0} coordination events</div></div>
        <div class="metric"><div class="label">Track events</div><div class="value">${r.mapOutputPairs}</div><div class="hint">emitted by the map step</div></div>
        <div class="metric"><div class="label">Engine</div><div class="value">Spark ${esc(r.sparkVersion)}</div><div class="hint">local, 4 executor threads</div></div>
        <div class="metric"><div class="label">Job time</div><div class="value">${(a.elapsedMs / 1000).toFixed(1)} s</div><div class="hint">${r.matchesLoadBalancer ? 'reconciled with dispatch ✓' : 'reconciliation mismatch'}</div></div>
      </div>
      <div class="panel mt"><div class="panel-head"><h3>How the job ran</h3><span class="sub">each station log is one partition, processed in parallel</span>${info('<b>MapReduce.</b> Map: every TRACK_EVENT becomes (track, 1); other events emit nothing. Shuffle: pairs with the same track are grouped. Reduce: the 1s are added up. Spark runs the partitions in parallel.')}</div>
        <div class="pipeline">
          <div class="stage" style="animation-delay:0ms"><h4>Sources</h4>${r.partitions.map((p) => `<div class="kv"><span class="k">${esc(place(p.station))}</span><b>${p.events}</b></div>`).join('')}</div>
          <div class="stage" style="animation-delay:180ms"><h4>Map</h4>${r.mapSamples.slice(0, 4).map((m) => `<div class="small" style="margin-bottom:4px">${m.key ? `<span class="token">(${esc(short(m.key))}, 1)</span>` : '<span class="token" style="color:var(--dim)">∅</span>'} <span class="dim mono" style="font-size:10.5px">${esc(m.input.split('|').slice(2, 5).join(' '))}</span></div>`).join('')}</div>
          <div class="stage" style="animation-delay:360ms"><h4>Shuffle</h4>${Object.entries(r.grouped).map(([k, v]) => `<div class="small"><span class="mono">${esc(short(k))}</span> ${v.map(() => '<span class="token">1</span>').join('')}</div>`).join('')}</div>
          <div class="stage" style="animation-delay:540ms"><h4>Reduce</h4>${Object.entries(r.reduced).map(([k, v]) => `<div class="kv"><span class="k">${esc(k)}</span><b>${v}</b></div>`).join('')}</div>
          <div class="stage" style="animation-delay:720ms"><h4>Result</h4><div class="small">Busiest: <b>${esc(r.busiestTrack)}</b></div><div class="small mt-s ${r.reducedTotal === r.mapOutputPairs ? 'ok' : 'bad'}">Σ ${r.reducedTotal} = ${r.mapOutputPairs} pairs ✓</div></div>
        </div>
      </div>
      <div class="grid wide-left mt">
        <div class="panel flush"><div class="panel-head" style="padding:16px 18px 0"><h3>Track heat map</h3><span class="sub">brighter and thicker = more activity</span></div><div class="map-wrap">${heatMapSVG(r.reduced)}</div></div>
        <div class="stack">
          <div class="panel"><div class="panel-head"><h3>Activity per track</h3></div><div class="cols">${Object.entries(r.reduced).map(([k, v]) => `<div class="c"><span class="cv">${v}</span><div class="cb ${k === r.busiestTrack ? 'hot' : ''}" style="height:${(v / maxV) * 140}px"></div><span class="ck">${esc(short(k))}</span></div>`).join('')}</div></div>
          <div class="panel"><div class="panel-head"><h3>Workload reconciliation</h3><span class="sub">logs vs dispatcher</span></div>
            <table><thead><tr><th>Station</th><th>From logs</th><th>Dispatcher</th><th></th></tr></thead><tbody>${Object.entries(r.loadBalancerCounts).map(([k, v]) => `<tr><td><b>${esc(place(k))}</b></td><td class="mono">${r.requestsFromLogs[k]}</td><td class="mono">${v}</td><td>${r.requestsFromLogs[k] === v ? '<span class="ok">✓</span>' : '<span class="bad">✗</span>'}</td></tr>`).join('')}</tbody></table></div>
        </div>
      </div>
      <div class="panel mt"><div class="panel-head"><h3>Occupations per track</h3></div><div class="loadbars">${Object.entries(r.occupations).map(([k, v]) => { const m = Math.max(1, ...Object.values(r.occupations)); return `<div class="lb-row"><span class="mono">${esc(k)}</span><div class="lb-track"><div class="lb-fill" style="width:${(v / m) * 100}%;background:var(--violet);box-shadow:0 0 12px var(--violet)"></div></div><span><b>${v}</b></span></div>`; }).join('')}</div></div>`;
  },
});

Object.assign(ACTIONS, {
  analyse: (b) => act(b, '/analytics', {}, (r) => `▲ Analysis complete in ${(r.elapsedMs / 1000).toFixed(1)} s · busiest ${r.result.busiestTrack}`),
});

/* =========================================================
   FLEET SYNC (MPI Broadcast · Scatter · Gather)
   ========================================================= */

page({
  id: 'fleet', title: 'Fleet Sync', icon: ICON.fleet,
  render: () => `
    <div class="page-head">
      <div><h1>Fleet Sync</h1><p>Pushes the live network state to a compute cluster of station nodes: the control node broadcasts the state, scatters the pending trains across nodes, and gathers every node's decision back.</p></div>
      <div class="actions">
        <label class="field" style="flex-direction:row;align-items:center;gap:8px">Nodes <select id="fs-np"><option>1</option><option>2</option><option selected>4</option><option>8</option></select></label>
        <button class="btn primary" data-act="fleetRun">Synchronise fleet</button>
      </div>
    </div>
    <div class="panel"><div class="panel-head"><h3>Cluster</h3><span class="sub" id="fs-sub"></span>${info('Runs as <b>real MPI processes</b> (MPJ Express).<br><b>Broadcast:</b> the root sends the exact same state to every node.<br><b>Scatter:</b> the root splits the train list into equal chunks, one per node, in rank order.<br><b>Gather:</b> every node sends its chunk of results back; the root assembles them in rank order into one buffer.')}</div>
      <div class="stage-area" id="fs-stage"></div>
    </div>
    <div class="grid cols-2 mt">
      <div class="panel"><div class="panel-head"><h3>Node decisions</h3><span class="sub">each node checks its trains against the broadcast state</span></div><div id="fs-results"></div></div>
      <div class="panel"><div class="panel-head"><h3>Verification</h3></div><div id="fs-verify"></div></div>
    </div>`,
  mount() { local.fleetPlayed = null; },
  update() {
    const s = App.state;
    view('#fs-sub').html = `state to broadcast: <span class="mono">v${s.version} · ${s.tracks.map((t) => (t.train ? short(t.track) + '=' + t.train.replace('TRAIN-', '') : short(t.track) + '=·')).join(' ')}</span>`;
    const out = s.results.collectives;
    if (!out) { view('#fs-stage').html = '<div class="empty" style="margin:120px auto;max-width:420px">Press “Synchronise fleet” to start real MPI processes and watch the data move.</div>'; view('#fs-results').html = '<div class="empty">—</div>'; view('#fs-verify').html = '<div class="empty">—</div>'; return; }
    const r = out.result;
    if (local.fleetPlayed !== out.elapsedMs) { local.fleetPlayed = out.elapsedMs; playFleet(r); }
    view('#fs-results').html = `<table><thead><tr><th>Train</th><th>Track</th><th>Node</th><th>Journey</th><th>Decision</th></tr></thead><tbody>${r.results.map((x) => `<tr><td class="mono"><b>TRAIN-${x.train}</b></td><td class="mono">T0${x.track}</td><td><b style="color:${RANK_COLOR[x.rank]}">node ${x.rank}</b></td><td>${x.minutes} min</td><td>${x.accept ? chip('PROCEED', 'c-green') : chip('HOLD', 'c-red')}</td></tr>`).join('')}</tbody></table>`;
    view('#fs-verify').html = `
      <div class="signal-box">
        <div class="sig-row"><span class="lamp ${r.broadcastVerified ? 'g' : 'r'}"></span><span><b>Broadcast</b> · all ${r.processes} nodes hold identical state (checksum ${r.checksums[0]})</span><span></span></div>
        <div class="sig-row"><span class="lamp g"></span><span><b>Scatter</b> · ${r.requests.length} trains split into ${r.processes} equal chunks of ${r.requests.length / r.processes}</span><span></span></div>
        <div class="sig-row"><span class="lamp ${r.gatherVerified ? 'g' : 'r'}"></span><span><b>Gather</b> · chunks reassembled in rank order, original order restored</span><span></span></div>
      </div>
      <div class="metrics mt"><div class="metric"><div class="label">Proceed</div><div class="value ok">${r.accepted}</div></div><div class="metric"><div class="label">Hold</div><div class="value bad">${r.held}</div></div><div class="metric"><div class="label">Cluster time</div><div class="value">${Math.round(out.elapsedMs)} ms</div></div></div>
      <details class="mt"><summary class="dim" style="cursor:pointer">Raw cluster output</summary><pre class="trace mt-s">${esc(out.output.join('\n'))}</pre></details>`;
  },
});

async function playFleet(r) {
  const stage = $('#fs-stage');
  if (!stage) return;
  const np = r.processes;
  const W = stage.clientWidth; const H = stage.clientHeight;
  const rootPos = [W / 2, 66];
  const nodePos = (i) => [W * (i + 1) / (np + 1), 220];
  stage.innerHTML = `<div class="phase" id="fs-phase">BROADCAST</div>
    <div class="node root" style="left:${rootPos[0]}px;top:${rootPos[1]}px"><div class="nt">Control node</div><div class="ns">rank 0 · root · v${r.state[0]}</div></div>
    ${Array.from({ length: np }, (_, i) => `<div class="node" id="fs-n${i}" style="left:${nodePos(i)[0]}px;top:${nodePos(i)[1]}px;border-color:${RANK_COLOR[i]}55"><div class="nt" style="color:${RANK_COLOR[i]}">node ${i}</div><div class="ns">${i === 0 ? 'root also works' : 'station worker'}</div><div class="tray" id="fs-t${i}"></div></div>`).join('')}
    <div class="buffer" id="fs-buf"></div>`;
  const phase = (t) => { const p = $('#fs-phase'); if (p) p.textContent = t; };
  const fly = (text, color, from, to, delay) => new Promise((resolve) => {
    setTimeout(() => {
      if (!$('#fs-stage')) return resolve();
      const f = document.createElement('div');
      f.className = 'flyer'; f.textContent = text; f.style.background = color;
      f.style.left = from[0] + 'px'; f.style.top = from[1] + 'px';
      stage.appendChild(f);
      requestAnimationFrame(() => requestAnimationFrame(() => { f.style.left = to[0] + 'px'; f.style.top = to[1] + 'px'; }));
      setTimeout(() => { f.style.opacity = '0'; setTimeout(() => f.remove(), 400); resolve(); }, 950);
    }, delay);
  });
  // 1 Broadcast
  await Promise.all(Array.from({ length: np }, (_, i) => fly(`state v${r.state[0]}`, '#a78bfa', rootPos, nodePos(i), i * 90)));
  for (let i = 0; i < np; i++) { const n = $('#fs-n' + i); if (n) { n.classList.add('lit'); $('#fs-t' + i).innerHTML = `<span class="chip c-violet">v${r.state[0]} ✓</span>`; } }
  await sleep(350);
  // 2 Scatter
  phase('SCATTER');
  const per = r.requests.length / np;
  await Promise.all(r.requests.map((q, k) => fly(`T${q[0]}`, RANK_COLOR[Math.floor(k / per)], rootPos, nodePos(Math.floor(k / per)), k * 110)));
  r.chunks.forEach((c, i) => { const t = $('#fs-t' + i); if (t) t.innerHTML = c.map((x) => `<span class="chip c-grey mono">${x}</span>`).join(''); });
  await sleep(400);
  // 3 Compute
  phase('LOCAL CHECKS');
  r.results.forEach((x) => {
    const t = $('#fs-t' + x.rank);
    if (!t) return;
    const chipEl = [...t.children].find((c) => c.textContent === String(x.train));
    if (chipEl) { chipEl.className = 'chip mono ' + (x.accept ? 'c-green' : 'c-red'); chipEl.textContent = `${x.train} ${x.accept ? '✓' : '✋'}`; }
  });
  await sleep(900);
  // 4 Gather
  phase('GATHER');
  const buf = $('#fs-bufr') || $('#fs-buf');
  const bufPos = [W / 2, H - 34];
  for (let i = 0; i < np; i++) {
    await fly(`node ${i}`, RANK_COLOR[i], nodePos(i), bufPos, 0);
    if (!$('#fs-buf')) return;
    const seg = document.createElement('div');
    seg.className = 'seg'; seg.style.background = RANK_COLOR[i];
    seg.textContent = r.chunks[i].join(' ');
    buf.appendChild(seg);
  }
  phase(r.gatherVerified && r.broadcastVerified ? 'COMPLETE ✓ VERIFIED' : 'COMPLETE');
}

Object.assign(ACTIONS, {
  fleetRun: (b) => act(b, '/mpi/collectives', { processes: +$('#fs-np').value }, (r) => `✓ Fleet synchronised on ${r.result.processes} nodes in ${Math.round(r.elapsedMs)} ms`),
});

/* =========================================================
   ROUTE PLANNER (parallel matrix multiplication with MPI)
   ========================================================= */

const JPOS = [[90, 150], [250, 60], [250, 240], [430, 60], [430, 240], [590, 150]];

page({
  id: 'routes', title: 'Route Planner', icon: ICON.routes,
  render: () => `
    <div class="page-head">
      <div><h1>Route Planner</h1><p>Counts every two-leg route between junctions by multiplying the network's connection matrix on the compute cluster. Click a cell to see the routes.</p></div>
      <div class="actions">
        <label class="field" style="flex-direction:row;align-items:center;gap:8px">Cluster nodes <b id="rp-np-v">4</b><input type="range" id="rp-np" min="1" max="8" value="4"></label>
        <button class="btn primary" data-act="routeRun">Compute routes</button>
      </div>
    </div>
    <div id="rp-body"></div>
    <div class="panel mt">
      <div class="panel-head"><h3>Cluster scaling</h3><span class="sub">large route matrices, timed on this machine</span>${info('The root <b>scatters rows</b> of the first matrix (uneven splits allowed), <b>broadcasts</b> the second, every node computes its rows, and the root <b>gathers</b> the result. Speedup = sequential time ÷ parallel time; efficiency = speedup ÷ nodes. Small jobs can be slower in parallel because communication costs more than it saves.')}
        <span class="right"><select id="rp-n"><option>240</option><option>480</option><option>720</option><option selected>960</option><option>1200</option></select><button class="btn sm violet" data-act="routeSweep">Run scaling test</button></span></div>
      <div class="grid wide-left"><div id="rp-chart"></div><div id="rp-perf"></div></div>
    </div>`,
  mount() { $('#rp-np').addEventListener('input', (e) => { $('#rp-np-v').textContent = e.target.value; }); },
  update() {
    const out = App.state.results.matrix;
    view('#rp-chart').html = sweepChart();
    if (!out) {
      view('#rp-body').html = '<div class="panel"><div class="empty">Press “Compute routes” to run the cluster job.</div></div>';
      view('#rp-perf').html = '<div class="empty">—</div>';
      return;
    }
    const r = out.result; const a = r.partA; const b = r.partB;
    const rankOf = []; a.ranks.forEach((rk) => rk.rows.forEach(() => rankOf.push(rk.rank)));
    const { i, j } = local.route;
    view('#rp-body').html = `<div class="grid cols-2">
      <div class="panel"><div class="panel-head"><h3>Junction network</h3><span class="sub">${esc(routeExplain(a, i, j))}</span></div><div class="graph">${graphSvg(a, i, j)}</div></div>
      <div class="panel"><div class="panel-head"><h3>Two-leg routes</h3><span class="sub">row colour = cluster node that computed it</span></div>
        <div class="row" style="align-items:flex-start;gap:20px">${matrixHtml(a, rankOf, i, j)}
          <div><div class="ranklegend" style="flex-direction:column;gap:6px">${a.ranks.map((rk) => `<span><i style="background:${RANK_COLOR[rk.rank]}"></i>node ${rk.rank} · ${rk.rows.length ? rk.rows.join(', ') : 'no rows'}</span>`).join('')}</div>
          <div class="mt ${a.verified ? 'ok' : 'bad'}" style="font-size:12.5px">${a.verified ? '✓ matches a single-machine calculation' : '✗ mismatch'}</div></div></div></div></div>`;
    const maxT = Math.max(b.sequentialMs, b.parallelMs);
    view('#rp-perf').html = `<div class="loadbars">
        <div class="lb-row"><span>Single machine</span><div class="lb-track"><div class="lb-fill" style="width:${(b.sequentialMs / maxT) * 100}%;background:#64748b"></div></div><span class="mono">${b.sequentialMs.toFixed(0)} ms</span></div>
        <div class="lb-row"><span>${r.processes} nodes</span><div class="lb-track"><div class="lb-fill" style="width:${(b.parallelMs / maxT) * 100}%;background:var(--cyan);box-shadow:0 0 12px var(--cyan)"></div></div><span class="mono">${b.parallelMs.toFixed(0)} ms</span></div></div>
      <div class="metrics mt"><div class="metric"><div class="label">Speedup</div><div class="value">${b.speedup.toFixed(2)}×</div><div class="hint">${b.n}×${b.n} matrices</div></div><div class="metric"><div class="label">Efficiency</div><div class="value">${Math.round(b.efficiency * 100)}%</div></div><div class="metric"><div class="label">Result</div><div class="value ${b.verified ? 'ok' : 'bad'}">${b.verified ? '✓' : '✗'}</div><div class="hint">verified</div></div></div>
      ${b.speedup < 1 ? '<div class="mt-s warn" style="font-size:12.5px">Slower in parallel at this size: coordination overhead outweighs the work saved.</div>' : ''}`;
  },
});

function routeExplain(a, i, j) {
  const J = a.junctions;
  if (i === j) return `${J[i]} has ${a.c[i][i]} direct line${a.c[i][i] === 1 ? '' : 's'} (round trips)`;
  const via = J.filter((_, k) => a.a[i][k] && a.a[k][j]);
  return via.length ? `${J[i]} → ${J[j]}: ${via.length} route${via.length > 1 ? 's' : ''} via ${via.join(', ')}` : `${J[i]} → ${J[j]}: no two-leg route`;
}
function graphSvg(a, i, j) {
  const n = a.junctions.length;
  const via = new Set(); for (let k = 0; k < n; k++) if (i !== j && a.a[i][k] && a.a[k][j]) via.add(k);
  let edges = '';
  for (let p = 0; p < n; p++) for (let q = p + 1; q < n; q++) {
    if (!a.a[p][q]) continue;
    const hl = (via.has(q) && (p === i || p === j)) || (via.has(p) && (q === i || q === j));
    edges += `<line x1="${JPOS[p][0]}" y1="${JPOS[p][1]}" x2="${JPOS[q][0]}" y2="${JPOS[q][1]}" class="gedge ${hl ? 'hl' : ''}"/>`;
  }
  const nodes = a.junctions.map((J, k) => `<g class="gnode ${k === i ? 'src' : k === j ? 'dst' : via.has(k) ? 'via' : ''}"><circle cx="${JPOS[k][0]}" cy="${JPOS[k][1]}" r="26"/><text x="${JPOS[k][0]}" y="${JPOS[k][1] + 5}" text-anchor="middle">${J}</text></g>`).join('');
  return `<svg viewBox="0 0 680 300"><defs><filter id="glowCyan2" filterUnits="userSpaceOnUse" x="0" y="0" width="680" height="300"><feGaussianBlur stdDeviation="3" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>${edges}${nodes}</svg>`;
}
function matrixHtml(a, rankOf, si, sj) {
  const J = a.junctions; const n = J.length;
  const max = Math.max(1, ...a.c.flat());
  let html = `<div class="mx" style="grid-template-columns:repeat(${n + 1}, auto)"><span class="h"></span>${J.map((x) => `<span class="h">${x}</span>`).join('')}`;
  for (let i = 0; i < n; i++) {
    html += `<span class="h" style="color:${RANK_COLOR[rankOf[i]]}">${J[i]}</span>`;
    for (let j = 0; j < n; j++) {
      const v = a.c[i][j];
      html += `<span class="cell ${i === si && j === sj ? 'sel' : ''}" data-act="routeCell" data-i="${i}" data-j="${j}" style="background:${RANK_COLOR[rankOf[i]]}${Math.round(20 + (v / max) * 60).toString(16).padStart(2, '0')};color:#fff;border-color:${RANK_COLOR[rankOf[i]]}55">${v}</span>`;
    }
  }
  return html + '</div>';
}
function sweepChart() {
  const pts = [...local.sweep].sort((p, q) => p.np - q.np);
  if (!pts.length) return '<div class="empty">Run the scaling test (1, 2, 4 and 8 nodes) to chart the speedup.</div>';
  const W = 620; const H = 260; const pad = 44;
  const maxS = Math.max(3, ...pts.map((p) => p.speedup)) * 1.15;
  const x = (p) => pad + ((p - 1) / 7) * (W - pad * 2);
  const y = (sp) => H - pad - (sp / maxS) * (H - pad * 1.6);
  let svg = '';
  for (let s = 0; s <= maxS; s += 1) svg += `<line x1="${pad}" y1="${y(s)}" x2="${W - pad}" y2="${y(s)}" stroke="rgba(148,163,184,0.08)"/><text x="${pad - 8}" y="${y(s) + 4}" text-anchor="end" font-size="11" fill="#6b7fa3">${s}×</text>`;
  for (let p = 1; p <= 8; p++) svg += `<text x="${x(p)}" y="${H - pad + 18}" text-anchor="middle" font-size="11" fill="#6b7fa3">${p}</text>`;
  const ideal = Math.min(8, maxS);
  svg += `<line x1="${x(1)}" y1="${y(1)}" x2="${x(ideal)}" y2="${y(ideal)}" stroke="rgba(148,163,184,0.35)" stroke-dasharray="5 5"/><text x="${x(ideal) + 6}" y="${y(ideal) + 4}" font-size="11" fill="#6b7fa3">ideal</text>`;
  svg += `<polyline points="${pts.map((p) => `${x(p.np)},${y(p.speedup)}`).join(' ')}" fill="none" stroke="#22d3ee" stroke-width="3" filter="url(#glowCyan3)"/>`;
  svg += pts.map((p) => `<circle cx="${x(p.np)}" cy="${y(p.speedup)}" r="5.5" fill="#22d3ee"/><text x="${x(p.np)}" y="${y(p.speedup) - 11}" text-anchor="middle" font-size="12" font-weight="800" fill="#e6edf8">${p.speedup.toFixed(2)}×</text>`).join('');
  svg += `<text x="${W / 2}" y="${H - 6}" text-anchor="middle" font-size="11" fill="#6b7fa3">cluster nodes · ${pts[0].n}×${pts[0].n} matrices</text>`;
  return `<svg viewBox="0 0 ${W} ${H}" style="width:100%;height:auto"><defs><filter id="glowCyan3" filterUnits="userSpaceOnUse" x="0" y="0" width="${W}" height="${H}"><feGaussianBlur stdDeviation="3" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>${svg}</svg>`;
}
function addSweep(d) {
  const b = d.result.partB;
  local.sweep = local.sweep.filter((p) => p.n === b.n && p.np !== d.result.processes);
  local.sweep.push({ np: d.result.processes, n: b.n, speedup: b.speedup });
}

Object.assign(ACTIONS, {
  routeRun: async (b) => {
    const d = await act(b, '/mpi/matrix', { processes: +$('#rp-np').value, n: +$('#rp-n').value }, (r) => `✓ Routes computed on ${r.result.processes} nodes · ${r.result.partB.speedup.toFixed(2)}× faster at scale`);
    if (d) { addSweep(d); App.page.update(); }
  },
  routeSweep: async (b) => {
    const n = +$('#rp-n').value;
    local.sweep = local.sweep.filter((p) => p.n === n);
    await busy(b, async () => {
      for (const np of [1, 2, 4, 8]) {
        try { const d = await api('/mpi/matrix', { processes: np, n }); addSweep(d); await refresh(); } catch (e) { toast('✗ ' + esc(e.message), 'bad'); break; }
      }
    });
    toast('✓ Scaling test finished', 'ok');
  },
  routeCell: (b) => { local.route = { i: +b.dataset.i, j: +b.dataset.j }; App.page.update(); },
});

/* =========================================================
   SYSTEM (station API + runtime)
   ========================================================= */

const API_METHODS = [
  ['station.ping', 'Is a station alive? Returns its logical time', true],
  ['station.getLamportClock', 'Read a station\'s logical clock', true],
  ['station.getTrackState', 'Read a station\'s copy of the track state', true],
  ['cluster.getCoordinator', 'Which station coordinates the junctions', false],
  ['replication.getPrimary', 'Control centre, mirrors and current version', false],
];

page({
  id: 'system', title: 'System', icon: ICON.system,
  render: () => `
    <div class="page-head">
      <div><h1>System</h1><p>Developer tools: the station API the dashboard runs on, and the server runtime that answers it.</p></div>
    </div>
    <div class="grid side-main">
      <div class="panel">
        <div class="panel-head"><h3>Station API</h3>${info('<b>Client-server RPC.</b> The browser sends a JSON-RPC request to the server. For a station method, the server looks the station up in the <b>Java RMI registry</b> and calls it through its <b>stub</b>: the call travels over TCP to the station\'s remote object, runs on an RMI connection thread and the result is serialized back. An offline station has been withdrawn from RMI, so the call fails with a real <b>RemoteException</b>.')}</div>
        <div class="method-list" id="sy-methods">${API_METHODS.map(([m, d], k) => `<button class="method ${k === 0 ? 'active' : ''}" data-act="pickMethod" data-m="${m}"><b><span class="verb">CALL</span>${m}</b><span>${d}</span></button>`).join('')}</div>
        <div class="row mt"><select id="sy-station">${STATIONS.map((s) => `<option value="${s}">${place(s)}</option>`).join('')}</select><button class="btn primary" data-act="callApi">Send request</button></div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Request / response</h3><span class="right" id="sy-meta"></span></div>
        <div class="grid cols-2"><div><div class="dim" style="font-size:11.5px;margin-bottom:6px">REQUEST</div><pre class="json" id="sy-req">—</pre></div><div><div class="dim" style="font-size:11.5px;margin-bottom:6px">RESPONSE</div><pre class="json" id="sy-res">—</pre></div></div>
        <div id="sy-history" class="mt scroll" style="max-height:200px"></div>
      </div>
    </div>
    <div class="grid cols-2 mt">
      <div class="panel">
        <div class="panel-head"><h3>Load test</h3><span class="sub">simultaneous API calls</span>${info('The server answers requests on a <b>pool of 8 threads</b>; simultaneous calls are spread across them and handled in parallel. Shared station data is protected with synchronisation.')}<span class="right"><select id="sy-n"><option>16</option><option selected>32</option><option>64</option></select><button class="btn sm violet" data-act="burst">Run burst</button></span></div>
        <div id="sy-burst"></div>
      </div>
      <div class="panel">
        <div class="panel-head"><h3>Runtime threads</h3><span class="sub">live</span></div>
        <div id="sy-threads"></div>
      </div>
    </div>`,
  mount() { local.method = 'station.ping'; renderApi(); },
  async update() {
    view('#sy-history').html = local.rpcHistory.length ? `<table><thead><tr><th>#</th><th>Method</th><th>Station</th><th>Result</th><th>Thread</th><th>Latency</th></tr></thead><tbody>${local.rpcHistory.slice(0, 10).map((h) => `<tr><td>${h.id}</td><td class="mono">${esc(h.method)}</td><td>${esc(h.station ? place(h.station) : '—')}</td><td>${h.error ? `<span class="bad">✗ ${esc(h.error)}</span>` : '<span class="ok">✓</span>'}</td><td class="mono">${esc(h.thread)}</td><td class="mono">${h.latency} ms</td></tr>`).join('')}</tbody></table>` : '';
    const bst = local.burst;
    view('#sy-burst').html = bst ? `<div class="metrics"><div class="metric"><div class="label">Requests</div><div class="value">${bst.n}</div></div><div class="metric"><div class="label">Wall time</div><div class="value">${bst.wall} ms</div></div><div class="metric"><div class="label">Throughput</div><div class="value">${Math.round(bst.n / (bst.wall / 1000))}/s</div></div><div class="metric"><div class="label">Threads used</div><div class="value">${Object.keys(bst.byThread).length}</div></div></div>
      <div class="loadbars mt">${Object.entries(bst.byThread).sort().map(([k, v]) => { const m = Math.max(...Object.values(bst.byThread)); return `<div class="lb-row"><span class="mono">${esc(k)}</span><div class="lb-track"><div class="lb-fill" style="width:${(v / m) * 100}%;background:var(--violet);box-shadow:0 0 10px var(--violet)"></div></div><span><b>${v}</b></span></div>`; }).join('')}</div>` : '<div class="empty">Fire a burst of simultaneous calls to see the thread pool share the work.</div>';
    try { local.threads = await api('/threads'); } catch { /* keep last */ }
    const t = local.threads;
    if (t) {
      view('#sy-threads').html = `<div class="metrics"><div class="metric"><div class="label">Live threads</div><div class="value">${t.total}</div></div><div class="metric"><div class="label">CPU cores</div><div class="value">${t.cpus}</div></div><div class="metric"><div class="label">Uptime</div><div class="value">${Math.floor(t.uptimeSeconds / 60)}m ${t.uptimeSeconds % 60}s</div></div></div>
        <div class="scroll mt" style="max-height:230px"><table><thead><tr><th>Thread</th><th>Role</th><th>State</th></tr></thead><tbody>${t.threads.map((x) => `<tr><td class="mono">${esc(x.name)}</td><td>${esc(x.group.replace('Station workers', 'Station controller').replace('Web server (request handlers)', 'API request handler'))}</td><td>${x.state === 'RUNNABLE' ? chip('RUNNING', 'c-green') : chip(x.state, 'c-grey')}</td></tr>`).join('')}</tbody></table></div>`;
    }
  },
});

function renderApi() {
  const last = local.rpcLast;
  const needs = API_METHODS.find((m) => m[0] === local.method)[2];
  if ($('#sy-station')) $('#sy-station').disabled = !needs;
  if (!last) return;
  $('#sy-req').innerHTML = jsonHtml(last.req);
  const shown = { ...last.res }; delete shown.servedBy; delete shown.latencyMs; delete shown.transport; delete shown.endpoint;
  $('#sy-res').innerHTML = jsonHtml(shown);
  $('#sy-meta').innerHTML = `${last.res.error ? chip('ERROR ' + last.res.error.code, 'c-red') : chip('200 OK', 'c-green')} ${last.res.transport ? chip(last.res.transport, 'c-violet') : ''} <span class="dim mono" style="font-size:12px">${esc(last.res.servedBy)} · ${last.res.latencyMs} ms</span>${last.res.endpoint ? `<div class="dim mono" style="font-size:11.5px;margin-top:4px">${esc(last.res.endpoint)}</div>` : ''}`;
}
function jsonHtml(v) {
  return esc(JSON.stringify(v, null, 2))
    .replace(/(&quot;[^&]*?&quot;)(\s*:)/g, '<span class="k">$1</span>$2')
    .replace(/:\s(&quot;.*?&quot;)/g, ': <span class="s">$1</span>')
    .replace(/:\s(-?\d+\.?\d*)/g, ': <span class="n">$1</span>')
    .replace(/:\s(true|false|null)/g, ': <span class="b">$1</span>');
}

let rpcSeq = 1;
Object.assign(ACTIONS, {
  pickMethod: (b) => { local.method = b.dataset.m; $$('.method').forEach((x) => x.classList.toggle('active', x === b)); renderApi(); },
  callApi: async (b) => {
    const method = local.method; const st = $('#sy-station').value;
    const needs = API_METHODS.find((m) => m[0] === method)[2];
    const req = { jsonrpc: '2.0', id: rpcSeq++, method, params: needs ? { station: st } : {} };
    try {
      const res = await busy(b, () => api('/rpc', { method, station: st, id: req.id }));
      local.rpcLast = { req, res };
      local.rpcHistory.unshift({ id: req.id, method, station: needs ? st : null, error: res.error && res.error.message, thread: res.servedBy, latency: res.latencyMs });
      renderApi();
      refresh();
    } catch (e) { toast('✗ ' + esc(e.message), 'bad'); }
  },
  burst: async (b) => {
    const n = +$('#sy-n').value;
    await busy(b, async () => {
      const t0 = performance.now();
      const res = await Promise.all(Array.from({ length: n }, (_, i) => api('/rpc', { method: 'station.getLamportClock', station: STATIONS[i % 4], id: i })));
      const byThread = {};
      res.forEach((r) => { byThread[r.servedBy] = (byThread[r.servedBy] || 0) + 1; });
      local.burst = { n, wall: Math.max(1, Math.round(performance.now() - t0)), byThread };
    });
    App.page.update();
  },
});

boot();
