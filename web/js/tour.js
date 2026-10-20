'use strict';

/* =========================================================
   RailNet Ops — guided tour
   Walks through the screens, highlights the panel where each
   distributed-systems concept is at work and explains it.
   Keys: → / Enter next, ← back, Esc close.
   ========================================================= */

const TOUR = [
  { page: 'network', sel: '#net-map', title: 'A live railway network',
    text: 'Four stations (Northgate, Riverside, Hillcrest, Harbour) run as independent nodes. Trains, signals, heartbeats and replication packets on this map come from the Java backend in real time; nothing here is pre-recorded.' },
  { page: 'system', sel: '#sy-methods', title: 'Client–server: remote procedure calls',
    text: 'Pick a method and send it. The server looks the station up in a <b>Java RMI registry</b> and calls it through a stub: the call crosses TCP, runs on an RMI thread and the result is serialized back. Crash a station first and the call fails with a real <b>RemoteException</b>.' },
  { page: 'system', sel: '#sy-threads', title: 'Multithreading',
    text: 'The server answers on a pool of 8 threads; RMI and live updates run on threads of their own, and each station processes trains on its own worker thread. <b>Run burst</b> fires simultaneous calls and shows them spread over the pool.' },
  { page: 'timeline', sel: '#tl-chart', title: 'Logical clocks: Lamport and vector',
    text: 'Every event carries a Lamport time λ (send: λ+1, receive: max(λ, λ<sub>msg</sub>)+1), so causes always come first. <b>Click any event</b>: vector clocks show which events happened before it, after it, or concurrently with it.' },
  { page: 'timeline', sel: '#clk-faces', title: 'Physical clock synchronization',
    text: 'Each station has its own drifting hardware clock. <b>Sync with GPS</b> runs Cristian’s algorithm (server time + RTT/2). <b>Berkeley round</b> lets the coordinator average all clocks, ignoring a faulty one, so they agree with each other.' },
  { page: 'resilience', sel: '#rs-ring', title: 'Leader election: Bully and Ring',
    text: 'When the coordinator fails, the live stations elect a new one. <b>Bully</b>: challenge every higher ID, the highest live station wins. <b>Ring</b>: an election message collects IDs round a logical ring.' },
  { page: 'resilience', sel: '#rs-mirrors', title: 'Replication and consistency',
    text: 'Only the control centre writes. Each write gets the next version and must be acknowledged by every mirror before it counts; a copy only accepts version + 1. Matching fingerprints prove the copies are identical: <b>strong consistency</b>.' },
  { page: 'dispatch', sel: '#dp-wheel', title: 'Load balancing',
    text: 'Train requests are shared round-robin, skipping stations that are offline or overloaded. Stations then work on their own threads in parallel, and the control centre commits the results in request order.' },
  { page: 'insights', sel: '#in-body', title: 'MapReduce with Apache Spark',
    text: 'The stations’ event logs are analysed by a real Spark job: <b>map</b> each line to (key, 1), <b>shuffle</b> by key, <b>reduce</b> by adding. The pipeline and heat map show what each phase produced.' },
  { page: 'resilience', sel: '#rs-beats', title: 'Fault tolerance: primary-backup',
    text: 'A mirror sends heartbeats to the control centre. After 3 missed replies it is declared failed and the most up-to-date mirror takes over. Requests carry unique IDs, so a retry after a lost reply is never applied twice.' },
  { page: 'fleet', sel: '#fs-stage', title: 'MPI collectives: Broadcast, Scatter, Gather',
    text: 'Real MPI processes (MPJ Express) start on <b>Synchronise fleet</b>. <b>Broadcast</b> sends the same state to every rank, <b>Scatter</b> gives each rank its own equal chunk, <b>Gather</b> collects the chunks back in rank order: the inverse of Scatter.' },
  { page: 'routes', sel: '#rp-chart', title: 'Parallel matrix multiplication with MPI',
    text: 'Two-leg routes are a matrix product A×A. The rows are scattered over the MPI processes, B is broadcast, each rank multiplies its rows and the result is gathered. The chart shows how the time falls as processes are added.' },
  { page: 'section', sel: '#sec-svg', title: 'Beyond the syllabus: mutual exclusion',
    text: 'Hillcrest Tunnel is single-track. Stations agree who enters with the <b>Ricart–Agrawala</b> algorithm: REQUEST to all, enter after every REPLY, defer replies while you hold or have the older request. Never two trains inside.' },
  { page: 'resilience', sel: '#pt-svg', title: 'Beyond the syllabus: partitions and terms',
    text: 'Cut the links of the control centre: it is replaced while still running and believes it leads (split brain). Every leader has a <b>term</b>; when the old one writes with its stale term, the copies reject it and it steps down.' },
];

const Tour = { i: -1, el: null };

function tourOpen(i = 0) {
  Tour.i = i;
  if (!Tour.el) {
    Tour.el = document.createElement('div');
    Tour.el.className = 'tour';
    Tour.el.innerHTML = '<div class="tour-spot"></div><div class="tour-card" role="dialog" aria-live="polite" aria-label="Guided tour"></div>';
    document.body.appendChild(Tour.el);
    Tour.el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-tour]');
      if (!b) return;
      const a = b.dataset.tour;
      if (a === 'next') tourGo(Tour.i + 1);
      else if (a === 'back') tourGo(Tour.i - 1);
      else tourClose();
    });
  }
  Tour.el.classList.add('on');
  tourGo(i);
}

function tourClose() {
  Tour.i = -1;
  if (Tour.el) Tour.el.classList.remove('on');
  window.removeEventListener('resize', tourPlace);
  window.removeEventListener('scroll', tourPlace, true);
}

async function tourGo(i) {
  if (i < 0) return;
  if (i >= TOUR.length) { tourClose(); toast('✓ Tour finished'); return; }
  Tour.i = i;
  const step = TOUR[i];
  if (location.hash !== '#/' + step.page) {
    location.hash = '#/' + step.page;
    await sleep(350); // let the screen render
  }
  const card = Tour.el.querySelector('.tour-card');
  card.innerHTML = `<div class="tour-step">${i + 1} / ${TOUR.length}</div>
    <h3>${step.title}</h3><p>${step.text}</p>
    <div class="tour-actions"><button class="btn sm" data-tour="close">Close</button><span class="grow"></span>
      <button class="btn sm" data-tour="back" ${i === 0 ? 'disabled' : ''}>Back</button>
      <button class="btn sm primary" data-tour="next">${i === TOUR.length - 1 ? 'Finish' : 'Next'}</button></div>`;
  const target = tourTarget();
  if (target) target.scrollIntoView({ block: 'center', behavior: 'smooth' });
  window.addEventListener('resize', tourPlace);
  window.addEventListener('scroll', tourPlace, true);
  setTimeout(tourPlace, 30);
  setTimeout(tourPlace, 450);
  card.querySelector('[data-tour="next"]').focus({ preventScroll: true });
}

function tourTarget() {
  const step = TOUR[Tour.i];
  if (!step) return null;
  const el = document.querySelector(step.sel);
  return el ? (el.closest('.panel') || el) : null;
}

function tourPlace() {
  if (Tour.i < 0 || !Tour.el) return;
  const spot = Tour.el.querySelector('.tour-spot');
  const card = Tour.el.querySelector('.tour-card');
  const t = tourTarget();
  const pad = 8;
  if (!t) { spot.style.opacity = 0; card.style.left = '50%'; card.style.top = '30%'; card.style.transform = 'translateX(-50%)'; return; }
  const r = t.getBoundingClientRect();
  spot.style.opacity = 1;
  spot.style.left = r.left - pad + 'px';
  spot.style.top = r.top - pad + 'px';
  spot.style.width = r.width + pad * 2 + 'px';
  spot.style.height = r.height + pad * 2 + 'px';
  // card below the target if there is room, else above, else inside the bottom
  const cw = Math.min(400, window.innerWidth - 24);
  const ch = card.offsetHeight || 220;
  let top = r.bottom + 16;
  if (top + ch > window.innerHeight - 12) top = r.top - ch - 16;
  if (top < 12) top = Math.max(12, window.innerHeight - ch - 16);
  const left = Math.max(12, Math.min(window.innerWidth - cw - 12, r.left + r.width / 2 - cw / 2));
  card.style.transform = 'none';
  card.style.width = cw + 'px';
  card.style.left = left + 'px';
  card.style.top = top + 'px';
}

document.addEventListener('keydown', (e) => {
  if (Tour.i < 0) return;
  if (e.key === 'Escape') tourClose();
  else if (e.key === 'ArrowRight') tourGo(Tour.i + 1);
  else if (e.key === 'ArrowLeft') tourGo(Tour.i - 1);
});

document.getElementById('tour-btn').addEventListener('click', () => tourOpen(0));
