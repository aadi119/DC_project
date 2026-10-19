# Distributed Railway Coordination System

Four railway stations (STATION-01 … STATION-04) coordinating as a distributed system.

| Experiment | Topic | Where |
|---|---|---|
| 1 | Client-server / RPC (web dashboard calls stations through a JSON-RPC style API) | `RailwayWebServer`, `RailwaySystem` |
| 2 | Multithreading: server thread pool, station worker threads | `RailwayWebServer`, `RailwayStation` |
| 3 | Lamport logical clocks | `RailwayStation` |
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
opens the browser automatically). Every button calls the real Java code:
crash and recover stations, run elections, replicate updates, dispatch trains,
run the Spark job and launch real MPI processes. A live console panel shows the
backend's output.

- `RailwaySystem` holds the live system and offers one method per action.
- `RailwayWebServer` serves the site in `web/` and the JSON API under `/api/`.
- `EventFeed` records every station event (with its Lamport time) and console line.

## Console demonstrations

Experiments 4–8:

```bash
mvn -q compile exec:exec
```

Experiments 9–10 (4 MPI processes, MPJ Express multicore mode):

```bash
java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMpiCollectives
java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMatrixMultiplication
```

On Windows, `run.cmd` (Experiments 4–8) and `run-mpi.cmd` (Experiments 9–10;
`run-mpi.cmd 9` or `run-mpi.cmd 10` runs one) switch the console to UTF-8 first
so that `→ ✓ ⚠ ✗` display correctly.

Each station's event log is written to `logs/STATION-0X.log` and used as the
MapReduce input.
