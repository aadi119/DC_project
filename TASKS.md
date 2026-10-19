# Tasks

Open work for the Distributed Railway Coordination System. Before starting,
read [PROJECT_GUIDE.md](PROJECT_GUIDE.md) and follow its testing checklist (§9).

**How to use this file**
- Put your name next to a task when you take it: `- [ ] **A** … — @name`.
- Work on your own branch (e.g. `feature/rmi`) and merge through a pull request.
- Tick the box (`- [x]`) when the "Done when" criteria are met and the change is merged.
- Packages C, D, E, G and K all edit `web/js/pages.js`: merge those one at a time.
- The console demo output (`run.cmd`, `run-mpi.cmd`) is used in the lab reports
  and must not change unless a task says so. Check with the diff described in the guide.

---

## Priority order

1. **A, B, I**: close the remaining course gaps.
2. **F**: tests protect everyone else's work.
3. **C or D**: the most impressive new concept per hour of work.
4. Everything else.

## Suggested split (4 people)

| Person | Focus | Packages |
|---|---|---|
| 1 | Java core | A → B → E |
| 2 | Java + algorithms | C → D |
| 3 | Frontend | G → L → K |
| 4 | Quality and docs | F → I → H or J |

---

## Work packages

### A. Java RMI layer (Experiment 1) · Java · medium
- [ ] Expose `ping`, `getLamportClock` and `getTrackState` of a station over Java RMI.
- [ ] Add an Experiment 1 section to the console demo that calls a station through the RMI registry.

**Files:** new `StationRemote.java`, new `StationRmiServer.java`, `Main.java`.
**Done when:** a client looks the station up in the RMI registry, calls it and prints the result; calling a crashed station produces a `RemoteException` that is handled and printed.

### B. Console demos for Experiments 1–3 · Java · small
- [ ] Experiment 1: a client-server call (can reuse package A).
- [ ] Experiment 2: the multithreading demo (signal, heartbeat and train threads running concurrently). It existed in the first commit of the repo and was later removed.
- [ ] Experiment 3: a Lamport message exchange between two stations, showing the send and receive rules.

**Files:** `Main.java` (new methods before `runExperiment4`).
**Done when:** `run.cmd` prints Experiments 1 → 8 in order, and the Exp 4–8 part of the output is unchanged (diff check).

### C. Mutual exclusion on a single-line section · Java + JS · large
- [ ] Implement Ricart–Agrawala (or a token ring) for one shared single-line track section.
- [ ] Add a "Section Control" screen showing requests, replies or the token, and who is inside the section.

**Files:** new Java class, `RailwaySystem.java`, `RailwayWebServer.java` (new endpoint), `web/js/pages.js`.
**Done when:** two trains request the section at the same time, only one enters at a time, and the message flow (with Lamport times) is visible on the screen.

### D. Vector clocks · Java + JS · medium
- [ ] Give every station a vector clock alongside its Lamport clock and attach it to every event.
- [ ] On the Timeline screen, highlight pairs of events that are concurrent (not ordered by happened-before).

**Files:** `RailwayStation.java`, `EventFeed.java`, `web/js/pages.js`.
**Done when:** the timeline correctly marks concurrent events, and a tooltip shows each event's vector.

### E. Epoch/term numbers + network partition simulator · Java + JS · large
- [ ] Give each primary a term number that increases on every failover; replicas reject writes from an older term.
- [ ] Add "cut link between X and Y" to the Resilience screen.
- [ ] Show the split-brain risk and how terms prevent it.

**Files:** `ReplicationManager.java`, `TrackStateReplica.java`, `RailwaySystem.java`, `web/js/pages.js`.
**Done when:** after a partition and failover, the old primary's writes are rejected because its term is stale, and the screen explains why.

### F. Automated tests (JUnit) · Java · medium
- [ ] Add JUnit 5 to `pom.xml` (test sources in `src/test/java` or a `test/` folder configured in the pom).
- [ ] Test replication version ordering (a replica rejects anything other than version + 1).
- [ ] Test that the load balancer skips failed and overloaded stations and keeps rotation fair.
- [ ] Test that the failure detector needs exactly 3 missed heartbeats.
- [ ] Test the idempotent retry (a duplicate request ID is not applied twice).
- [ ] Test that failover never promotes a station without the latest version.

**Done when:** `mvn test` passes, and none of the existing run commands change.

### G. Guided tour / viva presentation mode · JS + CSS · medium
- [ ] A "Start tour" button that walks through all 8 screens.
- [ ] On each step, highlight the relevant panel and show a short explanation (which concept it demonstrates).

**Files:** `web/js/core.js`, `web/styles.css` (maybe a new `web/js/tour.js` added to `index.html`).
**Done when:** the tour runs start to finish, can be closed at any step, and does not break any screen.

### H. Live updates with Server-Sent Events · Java + JS · medium
- [ ] Add an SSE endpoint that pushes state changes and new events.
- [ ] Switch the frontend from polling every 1.4 s to SSE, with polling as a fallback.

**Files:** `RailwayWebServer.java`, `web/js/core.js`.
**Done when:** updates appear instantly, the server's thread pool is not exhausted by open connections, and reconnecting after a server restart works.

### I. Lab documents for Experiments 9 and 10 · docs · small
- [ ] Flowcharts for Experiments 9 and 10, in the same compact style as `docs/experiment5_flowchart.png` to `experiment8_flowchart.png`.
- [ ] Screenshots of the Fleet Sync and Route Planner screens, plus terminal screenshots from `run-mpi.cmd`.
- [ ] Word documents in the same format as the Experiment 5–8 reports (aim, theory, problem-solving, output, code, conclusion, research paper).
- [ ] Optional: refresh the UI screenshots in the Experiment 5–7 reports with the RailNet Ops design.

**Files:** `docs/`, Word files.
**Done when:** both reports are complete and ready to submit.

### J. Docker image / one-click run · DevOps · medium
- [ ] `Dockerfile` that builds the project and includes MPJ Express and a JDK 21 runtime.
- [ ] README section: build and run with Docker.

**Done when:** `docker run -p 8080:8080 <image>` serves the full site, including the Spark and MPI screens.

### K. Timetable screen + session report export · JS · medium
- [ ] Timetable screen: each train's planned vs actual times and delays (data from dispatch results and events).
- [ ] "Export report" button: downloads a Markdown or PDF summary of the session (failovers, requests, analytics results).

**Files:** `web/js/pages.js` (maybe new backend fields in `RailwaySystem.java`).
**Done when:** the timetable updates live, and the exported report reflects what actually happened.

### L. Light theme, mobile layout, accessibility · CSS · small–medium
- [ ] A light/dark theme switch (colour tokens are already on `:root` in `styles.css`).
- [ ] Usable on a phone (navigation, map and tables).
- [ ] Keyboard navigation, focus outlines and sufficient contrast.

**Files:** `web/styles.css`, `web/index.html`, small `core.js` changes.
**Done when:** every screen is usable at 375 px width and with the keyboard alone.

---

## Known issues to fix

- [ ] `Main.java` (Exp 6) prints "one station alone would need ~N ms" using a hard-coded `200` instead of the processing-time constant in `RailwayStation`.
- [ ] In the Exp 4/5 console demo, a crashed station "reports" its own heartbeat (`checkHeartbeat()` is called on the dead node). A live station should detect it instead, as `FailureDetector` does in Exp 8. This changes console output, so update the lab screenshots if you fix it.
- [ ] Station "processing" of a train request is a fixed 200 ms pause (simulated route preparation). Optionally make stations do real work (e.g. compute a route or check timetable conflicts).
- [ ] No persistence: all state is lost when the server restarts. Optionally add a write-ahead log or snapshot file per replica.
- [ ] Everything runs in one JVM (stations are objects). Long-term: run each station as its own process communicating over RMI or sockets (builds on package A).

## More ideas (not yet planned)

- [ ] Two-phase commit to reserve a multi-track route atomically.
- [ ] Chandy–Lamport snapshot of the global network state.
- [ ] Gossip protocol for spreading station health.
- [ ] Configurable network (number of stations and tracks).
- [ ] Scenario recorder: save a sequence of actions and replay it.
- [ ] Sound or desktop notifications for incidents.
