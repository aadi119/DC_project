# Distributed Railway Coordination System

Four railway stations (STATION-01 … STATION-04) coordinating as a distributed system.

| Experiment | Topic | Where |
|---|---|---|
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

## Run

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
