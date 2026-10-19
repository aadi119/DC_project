'use strict';

/* =========================================================
   Live network map: tracks, signals, trains, stations,
   replication and dispatch packets. Everything it shows is
   derived from the real backend state; animations play when
   that state changes.
   ========================================================= */

const GEO = {
  stations: {
    'STATION-01': [190, 175],
    'STATION-02': [810, 145],
    'STATION-03': [830, 455],
    'STATION-04': [200, 470],
  },
  tracks: {
    'TRACK-01': 'M190,175 C 400,88 600,80 810,145',
    'TRACK-02': 'M810,145 C 905,245 910,355 830,455',
    'TRACK-03': 'M830,455 C 640,548 390,560 200,470',
    'TRACK-04': 'M200,470 C 112,385 108,262 190,175',
    'TRACK-05': 'M190,175 C 430,255 590,345 830,455',
  },
  dispatch: [500, 36],
  labelAbove: { 'STATION-01': true, 'STATION-02': true },
};

const SVG_NS = 'http://www.w3.org/2000/svg';
const ease = {
  out: (t) => 1 - Math.pow(1 - t, 3),
  in: (t) => t * t * t,
  inOut: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
};

function svgEl(tag, attrs = {}, parent) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (parent) parent.appendChild(el);
  return el;
}

const MAP_DEFS = `
  <defs>
    <pattern id="gridPattern" width="32" height="32" patternUnits="userSpaceOnUse">
      <path d="M32 0H0V32" fill="none" stroke="rgba(148,163,184,0.06)" stroke-width="1"/>
    </pattern>
    <radialGradient id="mapGlow" cx="50%" cy="45%" r="60%">
      <stop offset="0%" stop-color="rgba(34,211,238,0.07)"/><stop offset="100%" stop-color="rgba(0,0,0,0)"/>
    </radialGradient>
    <linearGradient id="trainGrad" x1="0" x2="1">
      <stop offset="0%" stop-color="#0ea5e9"/><stop offset="100%" stop-color="#67e8f9"/>
    </linearGradient>
    <filter id="glowGreen" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="3" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    <filter id="glowRed" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="3" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    <filter id="glowGold" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="2.5" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    <filter id="glowCyan" x="-200%" y="-200%" width="500%" height="500%"><feGaussianBlur stdDeviation="4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    <filter id="glowTrain" x="-50%" y="-100%" width="200%" height="300%"><feGaussianBlur stdDeviation="3.5" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
  </defs>`;

class NetworkMap {
  constructor(host, { onSelect, showDispatch = true } = {}) {
    this.onSelect = onSelect;
    this.trains = {};      // track → { id, el, t, anim }
    this.packets = [];
    this.running = true;

    host.innerHTML = `<svg viewBox="0 0 1000 600" role="img" aria-label="Live railway network">${MAP_DEFS}
      <rect width="1000" height="600" fill="url(#gridPattern)"/><rect width="1000" height="600" fill="url(#mapGlow)"/></svg>`;
    this.svg = host.querySelector('svg');

    this.gRails = svgEl('g', {}, this.svg);
    this.gLinks = svgEl('g', {}, this.svg);
    this.gSignals = svgEl('g', {}, this.svg);
    this.gTrains = svgEl('g', {}, this.svg);
    this.gPackets = svgEl('g', {}, this.svg);
    this.gStations = svgEl('g', {}, this.svg);

    this.paths = {};
    this.signals = {};
    for (const [track, d] of Object.entries(GEO.tracks)) this.buildTrack(track, d);
    if (showDispatch) this.buildDispatch();
    this.stationEls = {};
    for (const name of STATIONS) this.buildStation(name);

    this.frame = this.frame.bind(this);
    requestAnimationFrame(this.frame);
  }

  /* ---------- construction ---------- */

  buildTrack(track, d) {
    const g = svgEl('g', { class: 'rail' }, this.gRails);
    svgEl('path', { d, class: 'rail-base' }, g);
    svgEl('path', { d, class: 'rail-sleepers' }, g);
    const steel = svgEl('path', { d, class: 'rail-steel' }, g);
    svgEl('path', { d, class: 'rail-gap' }, g);
    this.paths[track] = { g, path: steel, len: steel.getTotalLength() };

    const mid = this.pointAt(track, 0.5);
    const nrm = this.normalAt(track, 0.5);
    const lab = svgEl('text', { x: mid.x + nrm.x * 26, y: mid.y + nrm.y * 26 + 4, 'text-anchor': 'middle', class: 'track-label' }, this.gRails);
    lab.textContent = track;

    this.signals[track] = [0.1, 0.9].map((t) => {
      const p = this.pointAt(track, t);
      const n = this.normalAt(track, t);
      const x = p.x - n.x * 18; const y = p.y - n.y * 18;
      svgEl('circle', { cx: x, cy: y, r: 7.5, class: 'signal-post' }, this.gSignals);
      return svgEl('circle', { cx: x, cy: y, r: 4.5, class: 'signal green' }, this.gSignals);
    });
  }

  buildDispatch() {
    const [x, y] = GEO.dispatch;
    const g = svgEl('g', { transform: `translate(${x},${y})` }, this.gStations);
    svgEl('path', { d: 'M-16,-12 L0,-21 L16,-12 L16,9 L0,18 L-16,9 Z', fill: '#0e1a30', stroke: 'rgba(34,211,238,0.6)', 'stroke-width': 1.5 }, g);
    const t = svgEl('text', { y: 4, 'text-anchor': 'middle', 'font-size': 11, 'font-weight': 800, fill: '#22d3ee' }, g);
    t.textContent = 'D';
    const l = svgEl('text', { x: 26, y: 4, 'font-size': 10.5, 'font-weight': 700, fill: '#6b7fa3', 'letter-spacing': 1.5 }, g);
    l.textContent = 'DISPATCH';
  }

  buildStation(name) {
    const [x, y] = GEO.stations[name];
    const above = GEO.labelAbove[name];
    const g = svgEl('g', { class: 'stn alive', transform: `translate(${x},${y})` }, this.gStations);
    const halo = svgEl('circle', { r: 32, class: 'halo' }, g);
    const ring = svgEl('circle', { r: 40, class: 'ring' }, g);
    svgEl('circle', { r: 32, class: 'core' }, g);
    const codeT = svgEl('text', { y: 1, 'text-anchor': 'middle', class: 'code' }, g);
    codeT.textContent = code(name);
    const lam = svgEl('text', { y: 17, 'text-anchor': 'middle', class: 'lam' }, g);
    const crown = svgEl('text', { y: -46, 'text-anchor': 'middle', class: 'crown' }, g);
    crown.textContent = '♛';
    const nameT = svgEl('text', { y: above ? -64 : 66, 'text-anchor': 'middle', class: 'name' }, g);
    nameT.textContent = place(name);
    const roleT = svgEl('text', { y: above ? -78 : 82, 'text-anchor': 'middle', class: 'role' }, g);
    if (above) { nameT.setAttribute('y', -60); roleT.setAttribute('y', -76); crown.setAttribute('y', -92); }
    g.addEventListener('click', () => this.onSelect && this.onSelect(name));
    this.stationEls[name] = { g, halo, ring, lam, crown, roleT };
  }

  /* ---------- geometry ---------- */

  pointAt(track, t) {
    const { path, len } = this.paths[track];
    return path.getPointAtLength(Math.max(0, Math.min(1, t)) * len);
  }

  normalAt(track, t) {
    const a = this.pointAt(track, t - 0.01); const b = this.pointAt(track, t + 0.01);
    const dx = b.x - a.x; const dy = b.y - a.y; const L = Math.hypot(dx, dy) || 1;
    return { x: -dy / L, y: dx / L };
  }

  angleAt(track, t) {
    const a = this.pointAt(track, t - 0.01); const b = this.pointAt(track, t + 0.01);
    return Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI;
  }

  /* ---------- state → picture ---------- */

  update(s, prev, selected) {
    const primary = s.primary;
    for (const st of s.stations) {
      const e = this.stationEls[st.name];
      e.g.setAttribute('class', `stn ${st.active ? 'alive' : 'down'} ${selected === st.name ? 'selected' : ''}`);
      const color = !st.active ? '#f87171' : st.role === 'PRIMARY' ? '#22d3ee' : st.role === 'BACKUP' ? '#a78bfa' : '#475569';
      e.ring.setAttribute('stroke', color);
      e.halo.setAttribute('stroke', color);
      e.lam.textContent = `λ ${st.lamport}`;
      e.crown.style.display = st.coordinator ? '' : 'none';
      e.roleT.textContent = !st.active ? 'OFFLINE' : st.role === 'PRIMARY' ? `CONTROL · v${st.version}` : st.role === 'BACKUP' ? `MIRROR · v${st.version}` : 'STANDBY';
      e.roleT.setAttribute('fill', color);
    }

    // replication links: control centre → mirrors
    this.gLinks.innerHTML = '';
    const [px, py] = GEO.stations[primary];
    for (const b of s.backups) {
      const bs = s.stations.find((x) => x.name === b);
      const [bx, by] = GEO.stations[b];
      const live = s.primaryActive && bs.active;
      svgEl('line', { x1: px, y1: py, x2: bx, y2: by, class: 'data-link' + (live ? '' : ' off') }, this.gLinks);
    }

    // signals and trains
    for (const t of s.tracks) {
      const occupied = t.state === 'OCCUPIED';
      this.signals[t.track].forEach((sig) => sig.setAttribute('class', 'signal ' + (occupied ? 'red' : 'green')));
      this.paths[t.track].g.setAttribute('class', 'rail' + (occupied ? ' occupied' : ''));
      const current = this.trains[t.track];
      if (occupied && (!current || current.id !== t.train)) {
        if (current) this.leave(t.track);
        this.enter(t.track, t.train, !prev);
      } else if (!occupied && current) {
        this.leave(t.track);
      }
    }

    // a new committed version travels to every live mirror
    if (prev && s.version > prev.version && s.primaryActive) {
      s.backups.forEach((b, i) => {
        if (s.stations.find((x) => x.name === b).active) {
          this.packet(GEO.stations[primary], GEO.stations[b], '#22d3ee', 900, i * 120);
        }
      });
    }
    // a new control centre after failover
    if (prev && prev.primary !== primary) this.burst(primary);
  }

  /* ---------- trains ---------- */

  enter(track, id, instant) {
    const g = svgEl('g', {}, this.gTrains);
    const body = svgEl('g', {}, g);
    svgEl('rect', { x: -22, y: -7.5, width: 44, height: 15, rx: 6, fill: 'url(#trainGrad)', class: 'train-body' }, body);
    for (let i = 0; i < 4; i++) svgEl('rect', { x: -16 + i * 9, y: -3.5, width: 5, height: 5, rx: 1.2, fill: '#082f49', opacity: 0.85 }, body);
    svgEl('rect', { x: 17, y: -5.5, width: 4, height: 11, rx: 2, fill: '#fef9c3' }, body);
    const label = svgEl('text', { y: -15, 'text-anchor': 'middle', class: 'train-label' }, g);
    label.textContent = id;
    const fromStart = Math.random() < 0.5;
    const train = { id, g, body, t: instant ? 0.5 : (fromStart ? 0 : 1), anim: null, dir: fromStart ? 1 : -1 };
    if (!instant) train.anim = { from: train.t, to: 0.5, start: performance.now(), dur: 2400, ease: ease.out, onDone: null };
    this.trains[track] = train;
    this.place(track, train);
  }

  leave(track) {
    const train = this.trains[track];
    if (!train) return;
    delete this.trains[track];
    const to = train.dir > 0 ? 1.04 : -0.04;
    train.anim = { from: train.t, to, start: performance.now(), dur: 1900, ease: ease.in, onDone: () => train.g.remove() };
    train.leaving = track;
    this.leavingTrains = this.leavingTrains || [];
    this.leavingTrains.push(train);
  }

  place(track, train) {
    const p = this.pointAt(track, train.t);
    let angle = this.angleAt(track, train.t);
    if (train.dir < 0) angle += 180;
    train.g.setAttribute('transform', `translate(${p.x.toFixed(1)},${p.y.toFixed(1)})`);
    train.body.setAttribute('transform', `rotate(${angle.toFixed(1)})`);
  }

  /* ---------- packets & effects ---------- */

  packet(from, to, color = '#22d3ee', dur = 900, delay = 0) {
    const el = svgEl('circle', { r: 6, fill: color, class: 'packet', opacity: 0 }, this.gPackets);
    this.packets.push({ el, from, to, start: performance.now() + delay, dur });
  }

  dispatchTo(assignments) {
    assignments.forEach((a, i) => {
      if (!a.station) return;
      this.packet(GEO.dispatch, GEO.stations[a.station], a.color || '#fbbf24', 800, i * 160);
    });
  }

  message(from, to, delay = 0, color = '#a78bfa') {
    this.packet(GEO.stations[from], GEO.stations[to], color, 750, delay);
  }

  burst(name) {
    const [x, y] = GEO.stations[name];
    const c = svgEl('circle', { cx: x, cy: y, r: 40, class: 'burst' }, this.gPackets);
    setTimeout(() => c.remove(), 1300);
  }

  /* ---------- animation loop ---------- */

  frame(now) {
    if (!this.running) return;
    const all = [...Object.entries(this.trains)];
    for (const [track, train] of all) this.step(track, train, now);
    if (this.leavingTrains) {
      this.leavingTrains = this.leavingTrains.filter((train) => {
        this.step(train.leaving, train, now);
        return !!train.anim;
      });
    }
    this.packets = this.packets.filter((p) => {
      const k = (now - p.start) / p.dur;
      if (k < 0) return true;
      if (k >= 1) { p.el.remove(); return false; }
      const e = ease.inOut(k);
      p.el.setAttribute('cx', p.from[0] + (p.to[0] - p.from[0]) * e);
      p.el.setAttribute('cy', p.from[1] + (p.to[1] - p.from[1]) * e);
      p.el.setAttribute('opacity', k < 0.1 ? k * 10 : k > 0.85 ? (1 - k) / 0.15 : 1);
      return true;
    });
    requestAnimationFrame(this.frame);
  }

  step(track, train, now) {
    if (!train.anim) return;
    const a = train.anim;
    const k = Math.min(1, (now - a.start) / a.dur);
    train.t = a.from + (a.to - a.from) * a.ease(k);
    this.place(track, train);
    if (a.onDone && k > 0.8) train.g.setAttribute('opacity', (1 - k) / 0.2);
    if (k >= 1) {
      train.anim = null;
      if (a.onDone) a.onDone();
    }
  }

  destroy() { this.running = false; }
}

/** Static heat map of the network (used by Insights). */
function heatMapSVG(counts) {
  const max = Math.max(1, ...Object.values(counts));
  const color = (v) => {
    const k = v / max;
    const r = Math.round(34 + k * (248 - 34)); const g = Math.round(211 - k * (211 - 113)); const b = Math.round(238 - k * (238 - 113));
    return `rgb(${r},${g},${b})`;
  };
  let rails = '';
  for (const [track, d] of Object.entries(GEO.tracks)) {
    const v = counts[track] || 0;
    rails += `<path d="${d}" fill="none" stroke="#0f1a2e" stroke-width="26" stroke-linecap="round"/>
      <path d="${d}" fill="none" stroke="${color(v)}" stroke-width="${6 + (v / max) * 12}" stroke-linecap="round" opacity="0.9" filter="url(#glowCyan)"/>`;
  }
  const nodes = STATIONS.map((n) => {
    const [x, y] = GEO.stations[n];
    return `<circle cx="${x}" cy="${y}" r="26" fill="#0e1a30" stroke="#475569" stroke-width="2"/><text x="${x}" y="${y + 5}" text-anchor="middle" font-weight="800" font-size="14" fill="#fff">${code(n)}</text>`;
  }).join('');
  return `<svg viewBox="0 0 1000 600">${MAP_DEFS}<rect width="1000" height="600" fill="url(#gridPattern)"/>${rails}${nodes}</svg>`;
}
