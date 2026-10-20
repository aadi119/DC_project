# Distributed Railway Coordination System

Four railway stations (STATION-01 … STATION-04) coordinating as a distributed system.

> **Editing the project?** Read [PROJECT_GUIDE.md](PROJECT_GUIDE.md) first: architecture,
> API reference, how the website is built, testing checklist and known gotchas.

| Experiment | Topic | Where |
|---|---|---|
| 1 | Client-server communication with Java RMI: stations are remote objects in an RMI registry, called from a separate client JVM | `StationRemote`, `StationRmiServer`, `StationRmiClient`, `RailwayRmiDemo` |
| 2 | Multithreading: concurrent station threads, a thread-pool server, synchronization vs race conditions | `RailwayMultithreadingDemo`, `RailwayWebServer`, `RailwayStation` |
| 3 | Clock synchronization: physical (Cristian, Berkeley) and logical (Lamport) | `PhysicalClock`, `ClockSync`, `RailwayClockSyncDemo`, `RailwayStation` |
| 4 | Bully + Ring election, heartbeats | `RailwayStation`, `Main` |
| 5 | Data consistency + primary-backup replication | `ReplicationManager`, `TrackStateReplica` |
| 6 | Health-aware Round-Robin load balancing | `LoadBalancer`, `TrainRequest` |
| 7 | MapReduce on the station event logs (Apache Spark) | `RailwayEventAnalytics` |
| 8 | Fault tolerance: failure detection, failover, idempotent client retries | `FailureDetector`, `TrainControlClient`, `ReplicationManager` |
| 9 | MPI collective communication: Broadcast, Scatter, Gather | `RailwayMpiCollectives`, `MpiStations` |
| 10 | Parallel matrix multiplication using MPI | `RailwayMatrixMultiplication`, `MpiStations` |

## Requirements

- JDK 17–23 (JDK 21 recommended)
- Apache Maven 3.9+
- Experiments 9–10: [MPJ Express](https://sourceforge.net/projects/mpjexpress/) 0.44
  (Java MPI), unzipped, with `MPJ_HOME` pointing to it. Only its API jar is kept
  in `lib/` so that the project compiles.

## Web dashboard (all 10 experiments)

```bash
mvn -q compile exec:exec -Dmain.class=RailwayWebServer
```

Then open http://localhost:8080 (on Windows, `run-site.cmd` does this for you and
opens the browser automatically).

**RailNet Ops** is a railway operations console in which every concept runs as
part of normal operation; every button calls the real Java code:

| Screen | What happens | Concepts at work |
|---|---|---|
| Network | Animated map: trains, signals, heartbeats, replication packets; station inspector; Autopilot | replication, heartbeats, Lamport clocks |
| Dispatch | Round-robin routing wheel, controller worker threads, signal box | load balancing, multithreading |
| Resilience | Incidents, heartbeat monitor, automatic failover, mirror integrity, request journal, elections | fault tolerance, consistency, Bully/Ring |
| Timeline | Causal timeline drawn from real events by logical time; live station clocks with Cristian and Berkeley synchronization | Lamport clocks, physical clock sync |
| Insights | Traffic analytics pipeline and track heat map | MapReduce on Apache Spark |
| Fleet Sync | Live state pushed to a compute cluster, animated | MPI Broadcast / Scatter / Gather |
| Route Planner | Two-leg routes on a junction graph, cluster scaling chart | parallel matrix multiplication (MPI) |
| System | Station API explorer (calls go through Java RMI), load test, live threads | RPC / Java RMI, thread pool |

The Logs button opens the backend's live console output.

- `RailwaySystem` holds the live system and offers one method per action.
- `RailwayWebServer` serves the site in `web/` and the JSON API under `/api/`.
- `EventFeed` records every station event (with its Lamport time) and console line.

## Console demonstrations

Experiments 1, 2 and 3 (one program each):

```bash
mvn -q compile exec:exec -Dmain.class=RailwayRmiDemo
mvn -q compile exec:exec -Dmain.class=RailwayMultithreadingDemo
mvn -q compile exec:exec -Dmain.class=RailwayClockSyncDemo
```

`RailwayRmiDemo` starts the RMI server and launches the client as a second JVM.
To run them in two terminals (or on two machines) instead:

```bash
java -cp target/classes StationRmiServer 1099
java -cp target/classes StationRmiClient 127.0.0.1 1099
```

Experiments 4–8 (one connected run):

```bash
mvn -q compile exec:exec
```

Experiments 9–10 (4 MPI processes, MPJ Express multicore mode):

```bash
java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMpiCollectives
java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMatrixMultiplication
```

On Windows, `run.cmd` (Experiments 4–8; `run.cmd 1`, `run.cmd 2`, `run.cmd 3`
runs one of the first three, `run.cmd all` runs 1 to 8) and `run-mpi.cmd`
(Experiments 9–10; `run-mpi.cmd 9` or `run-mpi.cmd 10` runs one) switch the
console to UTF-8 first so that `→ ✓ ⚠ ✗` display correctly.

Each station's event log is written to `logs/STATION-0X.log` and used as the
MapReduce input.

## Automated tests

```bash
mvn test
```

JUnit 5 tests in `test/` check the core guarantees: replicas accept only the
next version, the load balancer skips failed and overloaded stations and keeps
the rotation fair, a station is declared failed after exactly 3 missed
heartbeats, failover never promotes a stale replica, a retried request is not
applied twice, Cristian and Berkeley reduce clock skew, Lamport's rules hold,
RMI calls to a crashed station throw `RemoteException`, and shared state is
thread-safe. GitHub Actions runs them on every push.
