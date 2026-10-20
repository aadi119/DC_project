# Tasks

Open work for the Distributed Railway Coordination System. Before starting,
read [PROJECT_GUIDE.md](PROJECT_GUIDE.md) and follow its testing checklist (§9).

**How to use this file**
- Put your name next to a task when you take it: `- [ ] … — @name`.
- Work on your own branch (e.g. `feature/persistence`) and merge through a pull request.
- Tick the box (`- [x]`) when the change is merged and `mvn test` passes.
- The console demo output (`run.cmd`, `run-mpi.cmd`) is used in the lab reports
  and must not change unless a task says so. Check with the diff described in the guide.

---

## Done

All original work packages are complete:

- [x] **A. Java RMI**: stations are remote objects in an RMI registry; the website's station API calls them through RMI stubs; a crashed station raises a real `RemoteException`.
- [x] **B. Console demos for Experiments 1–3**: `run.cmd 1`, `run.cmd 2`, `run.cmd 3` (`run.cmd all` runs 1 → 8). Exp 4–8 output unchanged.
- [x] **C. Mutual exclusion**: Ricart–Agrawala for the single-track Hillcrest Tunnel, Tunnel screen with animated message replay, console demo.
- [x] **D. Vector clocks**: every station and event carries a vector; the Timeline marks events before / after / concurrent with a selected event.
- [x] **E. Terms + network partition simulator**: links can be cut; a deposed primary's stale-term writes are rejected and it steps down.
- [x] **F. Automated tests**: JUnit 5 in `test/` (34 tests), GitHub Actions CI.
- [x] **G. Guided tour**: Tour button, 14 steps over all screens.
- [x] **H. Live updates (SSE)**: `/api/stream`, polling fallback, automatic reconnect.
- [x] **I. Lab documents for Experiments 9 and 10**: flowcharts (`docs/experiment9_flowchart.png`, `docs/experiment10_flowchart.png`), UI screenshots, Word reports.
- [x] **J. Docker image**: `Dockerfile` (JDK 21 + Spark + MPJ Express). The run command was verified outside Docker; the image itself still needs one `docker build` on a machine with Docker.
- [x] **K. Timetable + session report export**.
- [x] **L. Light theme + keyboard accessibility** (laptop layout; phone layout not required).
- [x] Known issue: hard-coded `200` in `Main.java` replaced by the processing-time constant.
- [x] Known issue: the website's heartbeat round is now sent by a live monitor.

---

## Still open (optional)

- [ ] **Docker check**: run `docker build -t railnet .` and `docker run -p 8080:8080 railnet` once, open every screen (especially Fleet Sync and Route Planner, which need MPJ Express inside the image).
- [ ] **Exp 4/5 console heartbeat**: in the console demo a crashed station still prints its own "NO RESPONSE" heartbeat line (the original Experiment 4 code). It is kept on purpose because the lab screenshots show it; if you change it, retake those screenshots.
- [ ] **Majority quorum**: an isolated primary keeps committing alone until it is replaced (its writes are discarded when it steps down). Requiring acknowledgements from a majority would prevent those lost writes.
- [ ] **Persistence**: all state is lost when the server restarts. Add a write-ahead log or a snapshot file per replica.
- [ ] **Real station processing**: a train request is a fixed 200 ms pause. Stations could compute a route or check timetable conflicts instead.
- [ ] **Stations as separate processes**: the RMI layer exists (`StationRmiServer` / `StationRmiClient`); the next step is running each station in its own JVM and replicating over RMI.
- [ ] **Label wording**: Fleet Sync says PROCEED where the console demo says ACCEPT; pick one word for both.

## More ideas (not planned)

- [ ] Two-phase commit to reserve a multi-track route atomically.
- [ ] Chandy–Lamport snapshot of the global network state.
- [ ] Gossip protocol for spreading station health.
- [ ] Configurable network (number of stations and tracks).
- [ ] Scenario recorder: save a sequence of actions and replay it.
