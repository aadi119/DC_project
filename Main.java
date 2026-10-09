import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Main {

    private static final Path EVENT_LOG_DIRECTORY = Path.of("logs");

    public static void main(String[] args) {

        // UTF-8 output so →, ✓, ⚠ and ✗ print correctly
        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "        DISTRIBUTED RAILWAY COORDINATION SYSTEM"
        );
        System.out.println(
                "        EXPERIMENTS 4 → 5 → 6 → 7 → 8"
        );
        System.out.println(
                "**********************************************************"
        );

        // =====================================================
        // CREATE RAILWAY NODES
        // =====================================================

        RailwayStation station1 =
                new RailwayStation("STATION-01", 1);

        RailwayStation station2 =
                new RailwayStation("STATION-02", 2);

        RailwayStation station3 =
                new RailwayStation("STATION-03", 3);

        RailwayStation station4 =
                new RailwayStation("STATION-04", 4);

        List<RailwayStation> nodes =
                new ArrayList<>();

        nodes.add(station1);
        nodes.add(station2);
        nodes.add(station3);
        nodes.add(station4);

        try {
            runExperiment4(station1, station2, station3, station4, nodes);

            // Bully rule on recovery: STATION-04 returns and reclaims the
            // coordinator role. The coordinator becomes the PRIMARY in Exp 5.
            System.out.println();
            System.out.println("STATION-04 is repaired and rejoins the network...");
            station4.recoverNode();
            RailwayStation coordinator =
                    RailwayStation.bullyRecoveryElection(station4, nodes);

            ReplicationManager replication =
                    runExperiment5(nodes, coordinator, List.of(station1, station2));

            LoadBalancer loadBalancer =
                    runExperiment6(nodes, replication);

            runExperiment7(nodes, loadBalancer);

            TrainControlClient client =
                    runExperiment8(nodes, replication);

            printFinalSummary(replication, client);

        } catch (IOException e) {
            System.out.println("✗ Could not read or write railway event logs: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("✗ Demonstration interrupted.");
        } catch (ExecutionException e) {
            System.out.println("✗ A station worker failed: " + e.getCause());
        } finally {
            for (RailwayStation node : nodes) {
                node.shutdownWorker();
            }
        }
    }

    // =========================================================
    // EXPERIMENT 4 - LEADER ELECTION (Bully + Ring)
    // =========================================================

    private static void runExperiment4(RailwayStation station1,
                                       RailwayStation station2,
                                       RailwayStation station3,
                                       RailwayStation station4,
                                       List<RailwayStation> nodes) {

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "        EXPERIMENT 4 - LEADER ELECTION"
        );
        System.out.println(
                "**********************************************************"
        );

        // STATION-04 initially acts as coordinator
        station4.setCoordinator(true);

        System.out.println();
        System.out.println(
                "RAILWAY NODES INITIALIZED"
        );

        System.out.println(
                "STATION-01 → ID 1"
        );
        System.out.println(
                "STATION-02 → ID 2"
        );
        System.out.println(
                "STATION-03 → ID 3"
        );
        System.out.println(
                "STATION-04 → ID 4"
        );

        System.out.println();
        System.out.println(
                "Initial Coordinator: STATION-04"
        );

        // =====================================================
        // HEARTBEAT MONITORING
        // =====================================================

        System.out.println();
        System.out.println(
                "----------------------------------------------------------"
        );
        System.out.println(
                "HEARTBEAT MONITORING"
        );
        System.out.println(
                "----------------------------------------------------------"
        );

        station1.checkHeartbeat();
        station2.checkHeartbeat();
        station3.checkHeartbeat();
        station4.checkHeartbeat();

        // =====================================================
        // SIMULATE COORDINATOR FAILURE
        // =====================================================

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "        SIMULATING COORDINATOR FAILURE"
        );
        System.out.println(
                "**********************************************************"
        );

        station4.failNode();

        // Remaining stations detect failure
        station1.checkHeartbeat();
        station2.checkHeartbeat();
        station3.checkHeartbeat();
        station4.checkHeartbeat();

        // =====================================================
        // BULLY ELECTION
        // =====================================================

        RailwayStation bullyWinner =
                RailwayStation.bullyElection(nodes);

        // =====================================================
        // SHOW RESULT
        // =====================================================

        System.out.println();
        System.out.println(
                "CURRENT RAILWAY COORDINATOR AFTER BULLY:"
        );

        if (bullyWinner != null) {

            System.out.println(
                    ">>> "
                    + bullyWinner.getStationName()
                    + " is now COORDINATOR <<<"
            );
        }

        // =====================================================
        // PREPARE RING ELECTION DEMONSTRATION
        // =====================================================

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "        PREPARING RING ELECTION DEMONSTRATION"
        );
        System.out.println(
                "**********************************************************"
        );

        // Restore STATION-04
        station4.recoverNode();

        // Make STATION-04 coordinator again
        station1.setCoordinator(false);
        station2.setCoordinator(false);
        station3.setCoordinator(false);
        station4.setCoordinator(true);

        System.out.println();
        System.out.println(
                "STATION-04 is again the coordinator."
        );

        // =====================================================
        // SIMULATE FAILURE AGAIN
        // =====================================================

        System.out.println();
        System.out.println(
                "Simulating STATION-04 coordinator failure..."
        );

        station4.failNode();

        station1.checkHeartbeat();
        station2.checkHeartbeat();
        station3.checkHeartbeat();
        station4.checkHeartbeat();

        // =====================================================
        // RING ELECTION
        // =====================================================

        RailwayStation ringWinner =
                RailwayStation.ringElection(nodes);

        // =====================================================
        // FINAL RESULT
        // =====================================================

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "              FINAL ELECTION RESULTS"
        );
        System.out.println(
                "**********************************************************"
        );

        System.out.println();

        if (bullyWinner != null) {

            System.out.println(
                    "Bully Election Winner : "
                    + bullyWinner.getStationName()
                    + " (ID "
                    + bullyWinner.getStationId()
                    + ")"
            );
        }

        if (ringWinner != null) {

            System.out.println(
                    "Ring Election Winner  : "
                    + ringWinner.getStationName()
                    + " (ID "
                    + ringWinner.getStationId()
                    + ")"
            );
        }

        System.out.println();
        System.out.println(
                "Both algorithms successfully restored "
                + "railway coordination."
        );

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "              EXPERIMENT 4 COMPLETED"
        );
        System.out.println(
                "**********************************************************"
        );
    }

    // =========================================================
    // EXPERIMENT 5 - DATA CONSISTENCY & REPLICATION
    // =========================================================

    private static ReplicationManager runExperiment5(List<RailwayStation> nodes,
                                                     RailwayStation primary,
                                                     List<RailwayStation> backups) {

        Terminal.banner(
                "EXPERIMENT 5 - DATA CONSISTENCY & REPLICATION",
                "Primary-Backup replication with synchronous propagation"
        );

        Terminal.blank();
        Terminal.line("PRIMARY NODE : " + primary.getStationName()
                + " (elected coordinator)");
        Terminal.line("BACKUP NODES : " + backups.get(0).getStationName()
                + ", " + backups.get(1).getStationName());
        Terminal.blank();
        Terminal.line("                 PRIMARY " + primary.getStationName());
        Terminal.line("                        |");
        Terminal.line("              +---------+---------+");
        Terminal.line("              ↓                   ↓");
        Terminal.line("     BACKUP " + backups.get(0).getStationName()
                + "     BACKUP " + backups.get(1).getStationName());

        ReplicationManager replication = new ReplicationManager(primary, backups);

        Terminal.section("STEP 1: INITIAL RAILWAY STATE (VERSION 1)");
        replication.initializeState(initialTrackState());

        Terminal.section("STEP 2: PRIMARY UPDATE → REPLICATE → BACKUPS");
        Terminal.line("Updating railway state...");
        Terminal.blank();
        replication.update("TRACK-02",
                TrackStateReplica.TrackStatus.occupied("TRAIN-104"), true);
        replication.update("TRACK-05",
                TrackStateReplica.TrackStatus.clear(), true);

        ReplicationManager.printTrackState("Railway state at primary",
                primary.getReplica());

        Terminal.section("STEP 3: CONSISTENCY VERIFICATION");
        replication.verifyConsistency();

        Terminal.section("STEP 4: PRIMARY FAILURE AND BACKUP PROMOTION");
        primary.failNode();
        primary.checkHeartbeat();
        System.out.println();
        RailwayStation newPrimary = replication.failover(nodes);

        Terminal.section("STEP 5: NEW PRIMARY CONTINUES ACCEPTING UPDATES");
        replication.update("TRACK-03",
                TrackStateReplica.TrackStatus.occupied("TRAIN-110"), true);

        Terminal.section("STEP 6: FAILED STATION RECOVERS (STALE REPLICA)");
        primary.recoverNode();
        replication.resynchronize(primary);

        Terminal.section("STEP 7: FINAL CONSISTENCY VERIFICATION");
        replication.verifyConsistency();

        Terminal.banner("EXPERIMENT 5 COMPLETED → Primary: "
                + newPrimary.getStationName()
                + " | State Version " + newPrimary.getReplica().getVersion());

        return replication;
    }

    /** Version 1 of the railway state, created by the primary. */
    private static Map<String, TrackStateReplica.TrackStatus> initialTrackState() {

        Map<String, TrackStateReplica.TrackStatus> state = new LinkedHashMap<>();
        state.put("TRACK-01", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-02", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-03", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-04", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-05", TrackStateReplica.TrackStatus.occupied("TRAIN-099"));
        return state;
    }

    // =========================================================
    // EXPERIMENT 6 - LOAD BALANCING
    // =========================================================

    private static LoadBalancer runExperiment6(List<RailwayStation> nodes,
                                               ReplicationManager replication)
            throws InterruptedException, ExecutionException {

        Terminal.banner(
                "EXPERIMENT 6 - LOAD BALANCING",
                "Health-aware Round-Robin over the railway stations"
        );

        // The primary handles every replicated write, so it is too busy to
        // also process train requests: it is marked OVERLOADED.
        RailwayStation primary = replication.getPrimary();
        primary.setOverloaded(true);

        LoadBalancer loadBalancer = new LoadBalancer(nodes);

        Terminal.blank();
        Terminal.line("LOAD BALANCER INITIALIZED");
        Terminal.blank();
        Terminal.line("STATION POOL (round-robin order):");

        for (RailwayStation station : loadBalancer.getPool()) {
            String reason = LoadBalancer.unavailabilityReason(station);
            Terminal.line("  " + station.getStationName() + " → "
                    + (reason == null ? "ACTIVE" : "⚠ " + reason)
                    + (station == primary ? "  (primary: busy with replication)" : ""));
        }

        // ---------------- DISTRIBUTION ----------------
        Terminal.section("TRAIN REQUEST DISTRIBUTION");

        List<Future<TrainRequest.Result>> pending = new ArrayList<>();
        long startNanos = System.nanoTime();

        for (TrainRequest request : incomingTrainRequests()) {

            LoadBalancer.Decision decision = loadBalancer.route(request);

            if (!decision.assigned()) {
                Terminal.line("✗ " + request + " → NO STATION AVAILABLE "
                        + decision.skipped());
                continue;
            }

            String skippedNote = decision.skipped().isEmpty()
                    ? ""
                    : "   (skipped " + String.join(", ", decision.skipped()) + ")";

            Terminal.line(String.format("#%d %s → %s%s",
                    request.sequence(), request,
                    decision.station().getStationName(), skippedNote));

            // Dispatch immediately; the station's worker thread starts at once
            pending.add(decision.station().submitTrainRequest(request));
        }

        Terminal.section("LOAD DISTRIBUTION SUMMARY");

        for (RailwayStation station : loadBalancer.getPool()) {
            int count = loadBalancer.getAssignmentCounts().get(station.getStationName());
            String reason = LoadBalancer.unavailabilityReason(station);
            Terminal.line("  " + station.getStationName() + " → " + count
                    + (count == 1 ? " request" : " requests")
                    + (reason == null ? "" : " [" + reason + "]"));
        }

        Terminal.blank();
        Terminal.line("✓ LOAD BALANCING COMPLETED");

        // ---------------- PARALLEL PROCESSING ----------------
        Terminal.section("STATIONS PROCESS REQUESTS IN PARALLEL (worker threads)");

        // Wait for every station (barrier); report in request order
        List<TrainRequest.Result> results = new ArrayList<>();
        for (Future<TrainRequest.Result> future : pending) {
            results.add(future.get());
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        for (TrainRequest.Result result : results) {
            Terminal.line(String.format("  %-9s on %-17s %s → %s  [L=%d]",
                    result.request().trainId(), result.workerThread(),
                    result.startedAt(), result.finishedAt(),
                    result.lamportTimestamp()));
        }

        Terminal.blank();
        Terminal.line("Processed " + results.size() + " requests in ~" + elapsedMs
                + " ms (one station alone would need ~" + results.size() * 200 + " ms)");

        // ---------------- COMMIT + REPLICATION ----------------
        Terminal.section("PRIMARY " + primary.getStationName()
                + " COMMITS TRACK CHANGES IN REQUEST ORDER");

        for (TrainRequest.Result result : results) {

            ReplicationManager.TrainCommit commit =
                    replication.commitTrainMovement(result.request());

            if (commit.accepted()) {
                Terminal.line(String.format("  #%d v%d  %s → %-20s ✓ replicated → %s",
                        result.request().sequence(), commit.version(),
                        result.request().trackId(), commit.newStatus(),
                        String.join(", ", commit.acknowledgedBy())));
            } else {
                Terminal.line(String.format("  #%d ✗ %s REJECTED: %s",
                        result.request().sequence(),
                        result.request().trainId(), commit.reason()));
            }
        }

        Terminal.blank();
        ReplicationManager.printTrackState("Railway state after train movements",
                primary.getReplica());
        Terminal.blank();
        replication.verifyConsistency();

        int totalEvents = nodes.stream().mapToInt(RailwayStation::getEventCount).sum();
        Terminal.blank();
        Terminal.line("Railway events generated so far (all station logs): " + totalEvents);

        Terminal.banner("EXPERIMENT 6 COMPLETED");

        return loadBalancer;
    }

    /** Fixed workload → identical demonstration on every run. */
    private static List<TrainRequest> incomingTrainRequests() {

        return List.of(
                new TrainRequest(1, "TRAIN-101", TrainRequest.Movement.ARRIVAL, "TRACK-01"),
                new TrainRequest(2, "TRAIN-102", TrainRequest.Movement.ARRIVAL, "TRACK-05"),
                new TrainRequest(3, "TRAIN-103", TrainRequest.Movement.ARRIVAL, "TRACK-03"),
                new TrainRequest(4, "TRAIN-104", TrainRequest.Movement.DEPARTURE, "TRACK-02"),
                new TrainRequest(5, "TRAIN-105", TrainRequest.Movement.ARRIVAL, "TRACK-02"),
                new TrainRequest(6, "TRAIN-106", TrainRequest.Movement.ARRIVAL, "TRACK-04")
        );
    }

    // =========================================================
    // EXPERIMENT 7 - MAPREDUCE (Apache Spark)
    // =========================================================

    private static void runExperiment7(List<RailwayStation> nodes,
                                       LoadBalancer loadBalancer) throws IOException {

        Terminal.banner(
                "EXPERIMENT 7 - MAPREDUCE",
                "RAILWAY EVENT ANALYTICS USING APACHE SPARK"
        );

        Terminal.blank();
        Terminal.line("Collecting distributed railway event logs...");
        Terminal.blank();

        Map<String, Path> stationLogs = new LinkedHashMap<>();

        for (RailwayStation station : nodes) {
            Path logFile = station.exportEventLog(EVENT_LOG_DIRECTORY);
            stationLogs.put(station.getStationName(), logFile);
            Terminal.line("  " + station.getStationName() + " → " + logFile
                    + " (" + station.getEventCount() + " events)");
        }

        try {
            RailwayEventAnalytics.run(stationLogs, loadBalancer.getAssignmentCounts());
            Terminal.blank();
            Terminal.line("✓ MAPREDUCE JOB COMPLETED");
        } catch (RuntimeException e) {
            Terminal.line("✗ Spark job failed: " + e);
            Terminal.line("  Run with JDK 17-23 using: mvn -q compile exec:exec");
        }
    }

    // =========================================================
    // EXPERIMENT 8 - FAULT TOLERANCE (primary-backup)
    // =========================================================

    private static TrainControlClient runExperiment8(List<RailwayStation> nodes,
                                                     ReplicationManager replication)
            throws InterruptedException {

        Terminal.banner(
                "EXPERIMENT 8 - FAULT TOLERANCE",
                "Primary-backup replication surviving node crashes"
        );

        RailwayStation primary = replication.getPrimary();
        primary.setOverloaded(false);   // load returned to normal after Exp 6

        FailureDetector detector = new FailureDetector(3, 100);
        TrainControlClient client = new TrainControlClient(replication);

        Terminal.blank();
        Terminal.line("Replica group → PRIMARY " + primary.getStationName()
                + " | BACKUPS " + stationNames(replication.getBackups())
                + " | State Version " + primary.getReplica().getVersion());
        Terminal.line("Failure detector → " + detector.getMissedLimit()
                + " missed heartbeats = node FAILED");
        Terminal.line("Fault model → crash failures (a failed node simply stops responding)");

        // ---------------- PHASE 1 ----------------
        Terminal.section("PHASE 1: NORMAL OPERATION");
        RailwayStation monitor = replication.getBackups().get(0);
        detector.monitor(monitor, primary, 1);
        Terminal.blank();
        client.send("REQ-801",
                new TrainRequest(801, "TRAIN-101", TrainRequest.Movement.DEPARTURE, "TRACK-01"));

        // ---------------- PHASE 2 ----------------
        Terminal.section("PHASE 2: BACKUP FAILURE → SYSTEM STAYS AVAILABLE");
        RailwayStation failedBackup = replication.getBackups().get(1);
        failedBackup.failNode();
        Terminal.blank();
        client.send("REQ-802",
                new TrainRequest(802, "TRAIN-102", TrainRequest.Movement.DEPARTURE, "TRACK-05"));
        Terminal.line("Replica group → PRIMARY " + replication.getPrimary().getStationName()
                + " | BACKUPS " + stationNames(replication.getBackups())
                + "   (degraded, still serving requests)");

        // ---------------- PHASE 3 ----------------
        Terminal.section("PHASE 3: PRIMARY CRASHES BEFORE REPLYING");
        replication.injectCrashBeforeNextReply();
        client.send("REQ-803",
                new TrainRequest(803, "TRAIN-105", TrainRequest.Movement.DEPARTURE, "TRACK-02"));

        Terminal.blank();
        Terminal.line("Failure detection (" + monitor.getStationName() + " monitors the primary):");
        boolean primaryFailed = detector.monitor(monitor, primary, 5);

        if (primaryFailed) {
            Terminal.section("PHASE 4: AUTOMATIC FAILOVER + CLIENT RETRY");
            replication.failover(nodes);

            Terminal.blank();
            client.retryPending();
            client.send("REQ-804",
                    new TrainRequest(804, "TRAIN-106", TrainRequest.Movement.DEPARTURE, "TRACK-04"));

            if (replication.getBackups().isEmpty()) {
                Terminal.line("⚠ Running with NO backup: one more failure would lose data → recover nodes");
            }
        }

        // ---------------- PHASE 5 ----------------
        Terminal.section("PHASE 5: FAILED NODES RECOVER AND CATCH UP");
        failedBackup.recoverNode();
        replication.resynchronize(failedBackup);
        Terminal.blank();
        primary.recoverNode();
        replication.resynchronize(primary);

        Terminal.section("FINAL CONSISTENCY VERIFICATION");
        boolean consistent = replication.verifyConsistency();

        // ---------------- SUMMARY ----------------
        Terminal.section("FAULT TOLERANCE SUMMARY");
        Terminal.line("  Crash failures injected      : 2 (backup " + failedBackup.getStationName()
                + ", primary " + primary.getStationName() + ")");
        Terminal.line("  Failure detection            : after " + detector.getMissedLimit()
                + " missed heartbeats");
        Terminal.line("  Automatic failover           : " + primary.getStationName() + " → "
                + replication.getPrimary().getStationName());
        Terminal.line("  Client requests (unique)     : " + client.getSent());
        Terminal.line("  Committed exactly once       : " + client.getCommitted());
        Terminal.line("  Lost replies → retried       : " + client.getTimeouts()
                + " → " + client.getRetries());
        Terminal.line("  Duplicates prevented         : " + client.getDuplicatesSuppressed());
        Terminal.line("  Requests lost                : " + client.getPendingCount());
        Terminal.line("  Final state                  : Version "
                + replication.getPrimary().getReplica().getVersion()
                + (consistent ? " on all replicas ✓" : " ✗ replicas differ"));

        Terminal.blank();
        if (consistent && client.getPendingCount() == 0
                && client.getCommitted() == client.getSent()) {
            Terminal.line("✓ FAULT TOLERANCE VERIFIED: 2 node crashes, no request lost, no duplicate");
        } else {
            Terminal.line("✗ FAULT TOLERANCE CHECK FAILED");
        }

        Terminal.banner("EXPERIMENT 8 COMPLETED → Primary: "
                + replication.getPrimary().getStationName()
                + " | State Version " + replication.getPrimary().getReplica().getVersion());

        return client;
    }

    private static String stationNames(List<RailwayStation> stations) {
        return stations.isEmpty()
                ? "none"
                : String.join(", ", stations.stream().map(RailwayStation::getStationName).toList());
    }

    // =========================================================
    // FINAL SUMMARY
    // =========================================================

    private static void printFinalSummary(ReplicationManager replication,
                                          TrainControlClient client) {

        RailwayStation primary = replication.getPrimary();

        Terminal.banner("DISTRIBUTED RAILWAY COORDINATION SYSTEM");
        Terminal.line("");
        Terminal.line("EXPERIMENT 4  Leader Election (Bully + Ring)");
        Terminal.line("      ↓       Coordinator established");
        Terminal.line("EXPERIMENT 5  Data Consistency + Replication");
        Terminal.line("      ↓       Primary + backups | Consistency verified");
        Terminal.line("EXPERIMENT 6  Load Balancing");
        Terminal.line("      ↓       Train requests distributed, railway events generated");
        Terminal.line("EXPERIMENT 7  Apache Spark MapReduce");
        Terminal.line("      ↓       Map → Shuffle → Reduce → Railway Event Analytics");
        Terminal.line("EXPERIMENT 8  Fault Tolerance");
        Terminal.line("              2 crashes survived | " + client.getCommitted() + "/"
                + client.getSent() + " requests committed | Primary "
                + primary.getStationName() + " | State Version "
                + primary.getReplica().getVersion());

        Terminal.banner("ALL REQUIRED EXPERIMENTS COMPLETED");
    }
}
