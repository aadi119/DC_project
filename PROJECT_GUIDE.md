# Distributed Railway Coordination System — Project Guide

This document explains the whole project so that a person, or an AI coding
assistant, can understand it and change it safely. Read it before editing.

---

## 1. What this project is

An undergraduate Distributed Systems project. Four railway stations
(`STATION-01` … `STATION-04`) cooperate as a distributed system. Every course
experiment is implemented in Java on top of the same running system:

| # | Concept | Main classes |
|---|---|---|
| 1 | Client-server with Java RMI | `StationRemote`, `StationRmiServer`, `StationRmiClient`, `RailwayRmiDemo`, `RailwaySystem.rpc()` |
| 2 | Multithreading | `RailwayMultithreadingDemo`, station worker threads, server thread pool |
| 3 | Clock synchronization: Cristian, Berkeley, Lamport (+ vector clocks) | `PhysicalClock`, `ClockSync`, `RailwayClockSyncDemo`, `RailwayStation` |
| 4 | Leader election (Bully + Ring) | `RailwayStation.bullyElection / ringElection` |
| 5 | Data consistency + replication | `ReplicationManager`, `TrackStateReplica` |
| 6 | Load balancing (health-aware round-robin) | `LoadBalancer`, `TrainRequest` |
| 7 | MapReduce with Apache Spark | `RailwayEventAnalytics` |
| 8 | Fault tolerance | `FailureDetector`, `TrainControlClient`, `ReplicationManager` |
| 9 | MPI Broadcast / Scatter / Gather | `RailwayMpiCollectives`, `MpiStations` |
| 10 | Parallel matrix multiplication (MPI) | `RailwayMatrixMultiplication`, `MpiStations` |

Beyond the syllabus: distributed mutual exclusion (`RicartAgrawala`), term
numbers + network partitions (`NetworkLinks`, `ReplicationManager.staleWrite`),
vector clocks, live push updates (`LiveStream`), a timetable and a guided tour.

There are **three ways to run it**:

1. **Console demos**: Experiments 1, 2, 3 (one program each) and 4–8 in
   sequence (`Main`), printed to the terminal.
2. **MPI console demo**: Experiments 9–10 as real MPI processes.
3. **Web dashboard "RailNet Ops"**: one live system covering all 10
   concepts, driven from the browser (`RailwayWebServer`).

The console demos are used for practical-record screenshots, so their output
must stay deterministic and unchanged (see §9).

---

## 2. Requirements and running

- **JDK 17–23** (JDK 21 recommended). JDK 24+ will not run Spark 4.0.1 (see §10).
- **Maven 3.9+**. On the author's machine Maven also lives at
  `C:\dcp\apache-maven-3.9.9`; the `.cmd` scripts fall back to it.
- **MPJ Express 0.44** (Java MPI) for Experiments 9–10 and the MPI screens of
  the site. Unzip it and set `MPJ_HOME`. Default fallback path used by the
  scripts and the server: `C:\dcp\mpj-v0_44`. Only its API jar is committed
  (`lib/mpj.jar`, MIT licence) so the project compiles without it.

| What | Windows script | Plain command |
|---|---|---|
| Web dashboard (all 10) | `run-site.cmd` | `mvn -q compile exec:exec -Dmain.class=RailwayWebServer` |
| Console demo, Exp 1 / 2 / 3 | `run.cmd 1` / `2` / `3` (`run.cmd all` = 1 → 8) | `mvn -q compile exec:exec -Dmain.class=RailwayRmiDemo` (`RailwayMultithreadingDemo`, `RailwayClockSyncDemo`) |
| Console demo, Exp 4–8 | `run.cmd` | `mvn -q compile exec:exec` |
| Mutual exclusion demo | – | `mvn -q compile exec:exec -Dmain.class=RicartAgrawala` |
| Tests | – | `mvn test` (JUnit 5, sources in `test/`, also run by GitHub Actions) |
| Docker (whole site) | – | `docker build -t railnet .` then `docker run -p 8080:8080 railnet` |
| MPI demo, Exp 9–10 | `run-mpi.cmd` (`run-mpi.cmd 9` / `10` for one) | `java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMpiCollectives` (or `RailwayMatrixMultiplication`) |

The scripts run `chcp 65001` first so `→ ✓ ⚠ ✗` display correctly in the
Windows terminal.

**Web server details:**
- Default port **8080**. Override with the env var `PORT`. If the port is
  taken, the server tries the next 9 ports and prints which one it used.
- It opens the browser automatically; set `NO_BROWSER=1` to stop that (useful
  for automated testing).
- It shuts itself down when the Maven process that launched it ends (Ctrl+C).
- `run-site.cmd` kills any leftover `RailwayWebServer` Java process before
  starting.
- It binds to `BIND_HOST` (default `127.0.0.1`; `0.0.0.0` in Docker).
- It also starts a **Java RMI registry** on port 1099 (env `RMI_PORT`, next free
  port if taken) and publishes every running station in it.

---

## 3. Repository layout

The Java sources sit in the **repository root** (default package, no
`src/main/java`). `pom.xml` sets `<sourceDirectory>${project.basedir}</sourceDirectory>`
and only compiles `*.java` in the root.

```
Main.java                      Console demo: Experiments 4 → 8 in sequence
RailwayRmiDemo.java            Exp 1 console demo: RMI server + client launched in a second JVM
StationRemote.java             Exp 1 remote interface (+ PingReply, MessageReply, Control)
StationRmiServer.java          Exp 1 RMI registry, publish/withdraw stations, standalone server
StationRmiClient.java          Exp 1 RMI client (standalone or launched by the demo)
RailwayMultithreadingDemo.java Exp 2 console demo: station threads, thread-pool server, race condition
PhysicalClock.java             Exp 3 drifting hardware clock (offset + drift ppm)
ClockSync.java                 Exp 3 Cristian and Berkeley algorithms
RailwayClockSyncDemo.java      Exp 3 console demo: physical vs Lamport vs vector clocks
RicartAgrawala.java            Distributed mutual exclusion for the single-track tunnel (+ demo)
NetworkLinks.java              Cut/restore links between stations (network partitions)
LiveStream.java                Server-Sent Events push of state and events (/api/stream)
RailwayStation.java            A station: Lamport + vector clock, physical clock, replica,
                               event log, worker thread, heartbeats, Bully/Ring (Exp 2–4)
TrackStateReplica.java         One versioned copy of the track state (+ applied request IDs)
ReplicationManager.java        Primary-backup replication, consistency checks, failover,
                               resync, client requests with duplicate detection (Exp 5, 8)
LoadBalancer.java              Health-aware round-robin (Exp 6)
TrainRequest.java              Train request record + worker result record
RailwayEventAnalytics.java     Spark MapReduce over the station event logs (Exp 7)
FailureDetector.java           Heartbeat failure detector, 3 missed beats = failed (Exp 8)
TrainControlClient.java        Client with request IDs, retries, attempt history (Exp 8)
RailwayMpiCollectives.java     MPI Bcast / Scatter / Gather program (Exp 9)
RailwayMatrixMultiplication.java  MPI matrix multiplication program (Exp 10)
MpiStations.java               MPI helpers: rank → station, rank-ordered printing, JSON
Terminal.java                  Console banner/section helpers
EventFeed.java                 In-memory feed of station events + console lines (for the web)
RailwaySystem.java             The live system behind the website: one method per action
RailwayWebServer.java          HTTP server: serves web/ and the JSON API under /api/
web/index.html                 Site shell (top navigation, logs drawer)
web/styles.css                 Dark "control room" design system
web/js/core.js                 API helpers, state polling, routing, autopilot, self-healing
web/js/network-map.js          Animated SVG network map (trains, signals, packets)
web/js/pages.js                The ten screens + their button handlers
web/js/tour.js                 Guided tour (Tour button)
test/                          JUnit 5 tests (mvn test)
Dockerfile, .dockerignore      Container image with JDK 21, Spark and MPJ Express
.github/workflows/tests.yml    CI: mvn test on every push
pom.xml                        Maven build (Spark, Gson, MPJ system jar, exec plugin)
log4j2.properties              Silences Spark's internal logging
lib/mpj.jar                    MPJ Express API jar (compile only)
docs/                          Flowcharts and screenshots used in the lab reports
run.cmd / run-mpi.cmd / run-site.cmd   Windows launchers
```

Generated and ignored: `target/`, `logs/` (station event logs written for Spark).

---

## 4. The distributed model (how the pieces fit)

### Stations
- 4 stations, IDs 1–4. Higher ID = higher election priority.
- Each `RailwayStation` has:
  - `active` (crashed stations are inactive), `coordinator`, `overloaded` flags,
  - a **Lamport clock** (`printEvent` = local event, `sendLamportTimestamp`,
    `receiveLamportTimestamp` / `printReceivedEvent` = receive rule),
  - a **vector clock** ticked together with it; `vectorAt(lamport)` gives the
    vector of any of the station's events, and station-to-station messages
    pass the sender's vector to the receive methods,
  - a **physical clock** (`getPhysicalClock()`, offset/drift per station ID),
  - optional `NetworkLinks` (`setLinks`): a cut link drops replication,
    state transfer and heartbeats between the two stations,
  - its own **replica** (`TrackStateReplica`),
  - its own **event log** (`recordEvent`, exported to `logs/STATION-0X.log`),
  - a single **worker thread** for train requests (`submitTrainRequest`).
- Every printed station event has the form
  `[HH:mm:ss.SSS] [STATION-0X] [L=n] message` and is also published to
  `EventFeed`.

### Track state and replication (strong consistency)
- 5 tracks `TRACK-01` … `TRACK-05`, each `CLEAR` or `OCCUPIED` by a train.
- **Primary-backup**: only the primary writes. Each write gets version N+1.
  It is sent to each backup in turn, and the primary waits for every ACK
  before the write counts as committed (synchronous replication).
- A replica accepts **only version = its version + 1** (no gaps, no reordering).
- Consistency check = same version + same full track table + same applied
  request IDs (a CRC32 checksum is displayed).
- A backup that cannot ACK is removed from the group; it must `resynchronize`
  (full state transfer) before rejoining.

### Failover (important design rule)
Plain Bully would elect the highest live ID, which may hold **no data**.
`ReplicationManager.failover()` therefore only considers stations whose replica
has the **latest version**, then applies the highest-ID rule among them. The
station that has no replica (version 0) is never promoted. The coordinator
flag is set on the new primary.

Note that in the web dashboard, "coordinator" (chosen by Bully/Ring elections)
and "primary/control centre" (chosen by failover) are separate roles that
usually coincide.

### Terms and network partitions
- `ReplicationManager` keeps a **term**, +1 at every failover; every replica
  remembers the highest term seen and rejects updates from an older term
  (`TrackStateReplica.StaleTermException`).
- If the replaced primary is still running (partition, not crash) it becomes
  `getDeposed()`. `staleWrite()` lets it write with its old term: replicas reject
  it, it learns the new term, steps down and is resynchronised (a divergent
  replica is replaced even if its version is not lower).
- The term is not printed in the console demos, so their output is unchanged.

### Mutual exclusion (Ricart–Agrawala)
`new RicartAgrawala(section, participants, holdMs).contend(requests)`: each
request runs on its own thread, messages go through a scheduled executor with
random 40–120 ms delays and update Lamport/vector clocks. Returns every
message (sent/delivered times), each passage and whether mutual exclusion held.

### Load balancing
Circular order 01 → 02 → 03 → 04. For each request the balancer starts at its
pointer and skips stations that are `FAILED` (inactive) or `OVERLOADED`, then
moves the pointer past the chosen station. Stations process their trains in
parallel on their worker threads. The **primary then commits the movements in
request order** and enforces safety: an arrival needs a CLEAR track, and a
departure needs that train on the track. Otherwise it is rejected ("signal
held at RED").

### Fault tolerance
- `FailureDetector`: a backup sends heartbeats; **3 consecutive misses** →
  declared failed → `failover()`.
- `TrainControlClient` sends requests with unique IDs (`REQ-801`, …). If the
  primary crashes **after committing but before replying**
  (`ReplicationManager.injectCrashBeforeNextReply()`), the client retries with
  the same ID. The applied-ID table is replicated, so the new primary returns
  the stored result: **the request is never applied twice** (idempotent retry).

### MapReduce (Spark)
Input: the 4 station event logs, one Spark partition each. Line format:

```
lamport|STATION-0X|EVENT_TYPE|field1|field2|field3
33|STATION-02|TRACK_EVENT|TRACK-03|OCCUPIED|TRAIN-110
```

Event types: `TRACK_EVENT`, `TRAIN_EVENT`, `REPLICATION_EVENT`, `NODE_EVENT`,
`HEARTBEAT_EVENT`. Analyses: events per track (Map → groupByKey → reduceByKey,
with a check that the reduced total equals the number of map pairs),
occupations per track, and train requests per station (cross-checked against
the load balancer). `RailwayEventAnalytics.analyze()` computes a `Result`
record. The console prints it via `printReport()`, and the web uses
`analyzeWithSharedContext()` (one long-lived Spark context).

### MPI (MPJ Express, multicore mode)
- Each MPI process = a station (rank 0 = `STATION-01`, the root).
- Programs accept optional arguments after the class name:
  - `RailwayMpiCollectives --state=13,0,0,110,0,0 --json`: state = `[version, TRACK-01..05]`,
    where 0 = clear and otherwise the train number.
  - `RailwayMatrixMultiplication --n=960 --json`: matrix size for the timing part (60–2000).
- With `--json` the root prints one extra line `@@RESULT@@{...}` that the web
  server parses (`MpiStations.RESULT_MARKER`).
- Exp 9 needs a process count that divides 8 (1, 2, 4, 8). Exp 10 uses
  `Scatterv/Gatherv`, so any count works.
- In multicore mode the "processes" are threads in one JVM, so never keep
  mutable `static` state in MPI classes.

---

## 5. Web backend

`RailwayWebServer` uses the JDK's built-in `com.sun.net.httpserver` (no web
framework), a fixed pool of **8 threads named `http-worker-N`**, and Gson for
JSON. All console output is copied into `EventFeed` (the "Logs" drawer in the
site shows it).

`RailwaySystem` owns the live objects (stations, `ReplicationManager`,
`LoadBalancer`, `TrainControlClient`, `FailureDetector`). Rules:

- Every state-changing method runs through `action(...)`, which holds one lock
  and returns `{...result, events: [station events], console: [lines]}`.
- Invalid actions throw `RailwaySystem.ActionException` → HTTP 400 `{ "error": "…" }`.
- Spark and MPI jobs run outside the main lock (own locks) because they are slow.
- `remember(key, value)` stores the latest result of each kind; it is returned
  in `state.results` so a page can redraw after a reload.
- Initial state (after `reset`): STATION-04 is primary and coordinator,
  backups are 01 and 02, and TRACK-02 = TRAIN-104, TRACK-05 = TRAIN-099.

### API reference

All POST bodies are JSON. All responses are JSON.

| Method | Path | Body | Purpose |
|---|---|---|---|
| GET | `/api/state` | – | Whole system snapshot (see below) |
| GET | `/api/events?since=N` | – | Feed entries after sequence N (max 600) |
| GET | `/api/threads` | – | Live JVM threads grouped by role |
| POST | `/api/reset` | – | Rebuild the system from its initial state |
| POST | `/api/stations/{STATION-0X}/fail` | – | Crash a station |
| POST | `/api/stations/{STATION-0X}/recover` | – | Recover (auto-resync if it held a replica) |
| POST | `/api/stations/{STATION-0X}/overload` | – | Toggle overloaded |
| POST | `/api/heartbeat` | – | The coordinator (or lowest live station) sends a heartbeat to every station; returns `monitor` and `alive` |
| POST | `/api/rpc` | `{method, station, id}` | JSON-RPC style call: `station.*` methods go through the **Java RMI** stub (`transport`, `endpoint` in the reply); also `cluster.getCoordinator`, `replication.getPrimary` |
| POST | `/api/lamport/local` | `{station, label}` | Local event (L = L + 1) |
| POST | `/api/lamport/message` | `{from, to, label}` | Send + receive (receive rule shown) |
| POST | `/api/election` | `{algorithm: "bully" \| "ring"}` | Run an election among live stations |
| POST | `/api/replication/update` | `{track, status: "OCCUPIED" \| "CLEAR", train}` | Primary write + synchronous replication |
| POST | `/api/replication/verify` | – | Consistency report |
| POST | `/api/dispatch` | `{requests: [{train, movement: "ARRIVAL" \| "DEPARTURE", track}]}` | Route, process in parallel, commit; blank `train` = new train |
| POST | `/api/dispatch/demo` | – | Batch built from the current tracks (includes one conflict) |
| POST | `/api/analytics` | – | Export logs and run the Spark job |
| POST | `/api/fault/request` | `{train, movement, track, crash}` | Client request with an ID; `crash: true` = primary crashes before replying |
| POST | `/api/fault/detect` | – | Heartbeat detection; fails over if the primary is dead |
| POST | `/api/fault/retry` | – | Client retries unanswered requests (same IDs) |
| POST | `/api/mpi/collectives` | `{processes: 1\|2\|4\|8}` | Run Exp 9 with the live state |
| POST | `/api/mpi/matrix` | `{processes: 1..8, n: 60..1600}` | Run Exp 10 |
| GET | `/api/stream` | – | Server-Sent Events: `state` (on change) and `feed` (new entries) |
| POST | `/api/clocks/cristian` | `{station}` | Cristian sync of one station with the GPS time server |
| POST | `/api/clocks/berkeley` | – | Berkeley round run by the coordinator |
| POST | `/api/clocks/disturb` | – | Give every physical clock a new random error |
| POST | `/api/section/request` | `{requests: [{station, train?}]}` | Ricart–Agrawala contest for Hillcrest Tunnel |
| POST | `/api/network/link` | `{a, b}` | Cut or restore the link between two stations |
| POST | `/api/network/isolate` | `{station?}` | Cut every link of a station (default: the primary) |
| POST | `/api/network/heal` | – | Restore all links |
| POST | `/api/network/stale-write` | – | The deposed primary writes with its old term |

`/api/state` shape (abridged):

```json
{
  "stations": [{"name":"STATION-01","id":1,"active":true,"coordinator":false,"overloaded":false,
                "role":"BACKUP","lamport":12,"version":5,"checksum":"9F3AA820","events":14,"assigned":2}],
  "coordinator":"STATION-04", "primary":"STATION-04", "primaryActive":true,
  "backups":["STATION-01","STATION-02"], "version":5,
  "tracks":[{"track":"TRACK-01","state":"OCCUPIED","train":"TRAIN-301"}],
  "nextInRotation":"STATION-02", "failovers":0,
  "client":{"sent":0,"committed":0,"rejected":0,"timeouts":0,"retries":0,"duplicates":0,"pending":[],"history":[]},
  "lastSeq":120,
  "results":{"dispatch":{},"election":{},"replication":{},"consistency":{},"detection":{},
             "analytics":{},"collectives":{},"matrix":{}}
}
```

Newer fields: per station `vector`, `term`, `deposed`, `clockOffsetMs`,
`driftPpm`, `rmi` (URL or null); top level `term`, `deposed`, `cutLinks`,
`clockSkewMs`, `serverTimeMs`, `rmiPort`, `timetable {summary, journeys}`;
`results` may also hold `clock`, `section`, `staleWrite`.

`role` is `PRIMARY`, `BACKUP` or `NONE`. Event-feed entries look like
`{seq, kind: "station"|"console", time, station, lamport, vector, text}`.

### Adding a backend action
1. Add a method to `RailwaySystem` that wraps its work in `action(() -> { … return Map.of(...); })`.
2. Add a `case "/api/your/path" -> system.yourMethod(...)` in `RailwayWebServer.route()`.
3. Throw `ActionException` for user errors; never let a crashed station act.

---

## 6. Web frontend ("RailNet Ops")

Plain HTML/CSS/JavaScript with **no build step and no external libraries**.
The server serves `web/` directly from disk, so frontend edits only need a
browser reload (Ctrl+F5), not a server restart.

The product deliberately does **not** say "Experiment 1…10". Each concept
appears as a natural feature of a railway operations console. Technical
explanations live in small ⓘ tooltips (`info()` helper).

Stations get display names: 01 Northgate, 02 Riverside, 03 Hillcrest,
04 Harbour (`PLACE` in `core.js`). UI wording: primary = **control centre**,
backup = **mirror**, no replica = **standby**, overloaded = **busy**.

### Screens (`web/js/pages.js`)

| id | Title | What it shows | Backend used |
|---|---|---|---|
| `network` | Network | Animated map, station inspector, live activity, metrics, Rush hour, Simulate incident | state, dispatch, stations/*, rpc |
| `dispatch` | Dispatch | Routing wheel, controller load, worker-thread lanes, signal box | dispatch, stations/*/overload |
| `resilience` | Resilience | Self-healing toggle, incidents, heartbeat monitor, mirror integrity, replication trace, request journal, network partition + terms, election lab | fault/*, replication/*, network/*, election |
| `section` | Tunnel | Ricart–Agrawala contest for the single-track tunnel, animated replay | section/request |
| `timetable` | Timetable | Planned vs actual time of each movement, delays, Markdown session report | state.timetable |
| `timeline` | Timeline | Causal timeline (click an event: before / after / concurrent by vector clocks), station clocks with Cristian / Berkeley | lamport/*, clocks/*, events |
| `insights` | Insights | Spark pipeline, track heat map, charts, reconciliation | analytics |
| `fleet` | Fleet Sync | Animated Broadcast → Scatter → Gather on the cluster | mpi/collectives |
| `routes` | Route Planner | Junction graph + clickable 2-leg route matrix, cluster scaling chart | mpi/matrix |
| `system` | System | API explorer, load test across server threads, live threads | rpc, threads |

### `core.js` — runtime
- `App.state` / `App.prev`: the latest and previous `/api/state`. Updates
  arrive by **Server-Sent Events** (`connectLive()` → `applyState` / `ingest`);
  while the stream is down, `refresh()` + `pollEvents()` poll every 1.4 s.
- `api(path, body)`: **GET if `body` is undefined, POST otherwise.** Always
  pass `{}` for POST endpoints without a body (forgetting this caused a real
  bug: "Unknown endpoint").
- `act(button, path, body, successMessage)`: POST with a spinner, a toast, and a refresh.
- `view('#id').html = markup`: only touches the DOM when the markup changes.
  Use it for anything refreshed by polling, otherwise animations restart and
  the screen flickers.
- Buttons use `data-act="name"`; handlers live in the global `ACTIONS` object
  (`Object.assign(ACTIONS, {...})` in `pages.js`).
- A page is registered with
  `page({ id, title, icon, render(), mount?(), update?(), leave?() })`.
  `render` returns static HTML once, `mount` wires inputs and builds
  persistent SVG, and `update` runs after every state refresh.
- **Autopilot** (`autopilotTick`, every 4.2 s): dispatches 1–2 valid train
  movements, or sends a client request, using `plannedMovement()`.
- **Self-healing** (`healIfNeeded`): if the primary is down and `App.autoHeal`
  is on, it calls `/fault/detect` and then `/fault/retry` (10 s cooldown after an error).
- `describe(event)` turns raw station events into human activity-feed text;
  noisy protocol lines return `null` and only appear in Logs.

### `network-map.js` — `NetworkMap`
SVG 1000×600. Station positions and track paths are in `GEO` (TRACK-05 is the
diagonal). `update(state, prev, selected)` diffs states:
- a track becoming occupied → a train enters (animated); a track becoming clear → the train leaves,
- a version increase → packets fly from the primary to each live mirror,
- a primary change → a burst ring around the new primary.
It also exposes `dispatchTo(assignments)`, `packet()` and `heatMapSVG(counts)`.

### Design system (`styles.css`)
A theme button switches `data-theme="light"` on `<html>` (saved in localStorage);
in light mode diagram containers stay dark "screens". Keyboard: elements that act
as buttons but are not `<button>` get `role="button" tabindex="0"`; Enter/Space
activates them (core.js).
Dark theme tokens on `:root` (`--cyan` accent, `--violet`, `--green`, `--amber`, `--red`);
building blocks `.panel`, `.metric(s)`, `.chip c-*`, `.btn primary|violet|danger|success|warn`,
`.feed`, `.signal-box`, `.lanes`, `.stage-area`/`.node`/`.flyer` (Fleet Sync).
Layout grids: `.grid` + `.cols-2|3|4`, `.main-side`, `.side-main`, `.wide-left`.

---

## 7. Console demo storyline (`Main.java`)

1. **Exp 4**: STATION-04 coordinator → fails → Bully elects STATION-03 →
   STATION-04 restored → fails again → Ring elects STATION-03.
2. STATION-04 recovers and reclaims coordinator (Bully on recovery).
3. **Exp 5**: STATION-04 primary, backups 01 & 02 → v1 initial state →
   v2 TRACK-02 OCCUPIED TRAIN-104 → v3 TRACK-05 CLEAR → consistency ✓ →
   STATION-04 fails → STATION-02 promoted (STATION-03 has no replica) →
   v4 TRACK-03 OCCUPIED TRAIN-110 → STATION-04 recovers stale (v3) → state transfer → consistent at v4.
4. **Exp 6**: STATION-02 (primary) marked overloaded → 6 trains routed
   01, 03, 04, 01, 03, 04 → processed in parallel → commits v5–v9; TRAIN-103 rejected.
5. **Exp 7**: Spark over 57 events → TRACK-02 busiest (4 events).
6. **Exp 8**: backup STATION-04 crashes → REQ-802 still commits →
   primary STATION-02 crashes after committing REQ-803 → 3 missed heartbeats →
   STATION-01 promoted → retry of REQ-803 detected as a duplicate → REQ-804 → recovery → all at v13.

---

## 8. Conventions

- Java 17 language level, default package, sources in the repo root.
- Comment style: short block comments explaining the distributed-systems idea;
  section banners `// ===== … =====`.
- Console output uses `Terminal.banner/section/line`, and symbols `→ ✓ ⚠ ✗`.
- Shared mutable state is guarded (`synchronized`, `volatile`); keep it that way.
- No randomness in the console demos.
- Frontend: vanilla JS, `'use strict'`, 2-space indent, single quotes,
  template literals for HTML, always `esc()` user or server text inserted into HTML.

---

## 9. Testing checklist (do this after changes)

1. Build and tests: `mvn -q compile` and `mvn test` (all green).
2. **Console output must not change.** Save the old output, run
   `mvn -q compile exec:exec > new.txt`, and diff after stripping timestamps,
   e.g. `sed -E 's/^\[[0-9:.]+\]//; s/[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}//g; s/~[0-9]+ ms//'`.
   Only the wall-clock times and the "~N ms" figure may differ.
3. MPI: `run-mpi.cmd` → both programs print `✓ … VERIFIED` and must **not**
   print `@@RESULT@@` (that line appears only with `--json`).
4. Web: start with `NO_BROWSER=1` (use `PORT=8090` if 8080 is busy), open each
   screen, use its buttons, and check the browser console. Quick API check:
   `curl -s localhost:8080/api/state`.
5. JS syntax: `node --check web/js/core.js` (and the other files in `web/js`).
6. Experiments 1–3: `run.cmd 1`, `run.cmd 2`, `run.cmd 3` finish with "COMPLETED".

---

## 10. Known gotchas

- **JDK 23 + Spark**: Hadoop calls `Subject.getSubject`, which needs
  `-Djava.security.manager=allow`. A Maven profile adds it for JDK 17–23. JDK 24+
  refuses that flag, so stay on JDK ≤ 23.
- **Spark on Windows**: `textFile()` needs Hadoop's `winutils`; we avoid it by
  reading logs with Java I/O and `parallelize(lines, 1)` per station.
- **Spark needs `--add-opens` flags**; they are in the `exec-maven-plugin`
  arguments, so always run through Maven (`exec:exec`), not a bare `java Main`.
- **MPJ Express drops empty lines** when relaying output; `MpiStations.println`
  prints a single space instead. MPI output from different ranks is serialised
  with `MpiStations.inRankOrder` (barriers).
- **Encoding**: run `chcp 65001` (the scripts do) or the symbols show as `?`.
- **Port in use**: a server left running from an earlier session holds 8080.
  The server now exits when its launcher dies and falls back to the next free
  port, and `run-site.cmd` kills leftovers.
- **SVG glow filters on straight lines**: a filter sized relative to an
  element's bounding box makes perfectly horizontal or vertical lines
  disappear. Use `filterUnits="userSpaceOnUse"` with an explicit region.
- **Do not** put mutable `static` fields in MPI programs (ranks share one JVM in multicore mode).
- **Stale Maven build**: if `mvn test` reports "cannot find symbol" for classes
  that exist, run `mvn clean test` once.
- **Browser extensions** can inject elements into screenshots taken with a
  local browser (e.g. a screen-recorder toolbar); remove them before capturing.
- Every git commit for this repository is authored by the repository user; do
  not add AI co-author trailers.
