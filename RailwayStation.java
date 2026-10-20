import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RailwayStation {

    // Experiment 6: simulated (fixed, deterministic) time a station
    // needs to prepare a route and signals for one train request
    public static final long TRAIN_PROCESSING_TIME_MS = 200;

    private static final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String stationName;
    private final int stationId;

    // volatile: since Experiment 6 these flags are read by several threads
    private volatile boolean active;
    private volatile boolean coordinator;

    // Experiment 6: an overloaded station is skipped by the load balancer
    private volatile boolean overloaded;

    // Lamport Logical Clock from Experiment 3
    private int lamportClock = 0;

    private final Object clockLock = new Object();

    // Experiment 3: the station's physical clock. Every station starts with
    // a different offset and drift, as real hardware clocks do.
    private static final double[] INITIAL_OFFSET_MS = {0, 850, -420, 3600, 120};
    private static final double[] DRIFT_PPM = {0, 40, -25, 60, 10};

    private final PhysicalClock physicalClock;

    // Experiment 5: every station holds a replica of the railway track state
    private final TrackStateReplica replica = new TrackStateReplica();

    // Experiment 7: this station's local event log (input for MapReduce).
    // Line format: lamport|station|EVENT_TYPE|field1|field2|field3
    private final List<String> eventLog = new ArrayList<>();

    // Experiment 6: worker thread that processes train requests (the
    // multithreading idea of Experiment 2 applied to request handling)
    private ExecutorService worker;

    public RailwayStation(String stationName, int stationId) {
        this.stationName = stationName;
        this.stationId = stationId;
        this.active = true;
        this.coordinator = false;
        int slot = stationId >= 1 && stationId < INITIAL_OFFSET_MS.length ? stationId : 0;
        this.physicalClock = new PhysicalClock(INITIAL_OFFSET_MS[slot], DRIFT_PPM[slot]);
    }

    // =========================================================
    // BASIC NODE INFORMATION
    // =========================================================

    public String getStationName() {
        return stationName;
    }

    public int getStationId() {
        return stationId;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isCoordinator() {
        return coordinator;
    }

    public void setCoordinator(boolean coordinator) {
        this.coordinator = coordinator;
    }

    public boolean isOverloaded() {
        return overloaded;
    }

    public void setOverloaded(boolean overloaded) {
        this.overloaded = overloaded;
    }

    public TrackStateReplica getReplica() {
        return replica;
    }

    public PhysicalClock getPhysicalClock() {
        return physicalClock;
    }

    @Override
    public String toString() {
        return stationName;
    }

    // =========================================================
    // LAMPORT CLOCK
    // =========================================================

    private int incrementLamportClock() {

        synchronized (clockLock) {
            lamportClock++;
            return lamportClock;
        }
    }

    public int getLamportClock() {

        synchronized (clockLock) {
            return lamportClock;
        }
    }

    public int receiveLamportTimestamp(int receivedTimestamp) {

        synchronized (clockLock) {

            lamportClock =
                    Math.max(lamportClock, receivedTimestamp) + 1;

            return lamportClock;
        }
    }

    /**
     * Send event: ticks the clock and returns the timestamp that is
     * attached to the outgoing message (Lamport rule for sends).
     */
    public int sendLamportTimestamp() {
        return incrementLamportClock();
    }

    // =========================================================
    // OUTPUT
    // =========================================================

    /** Prints a local event (ticks the Lamport clock) and returns its timestamp. */
    int printEvent(String message) {

        int timestamp = incrementLamportClock();

        printAtTimestamp(timestamp, message);

        return timestamp;
    }

    /** Prints an event whose Lamport timestamp was already assigned. */
    void printAtTimestamp(int timestamp, String message) {

        String physicalTime =
                LocalTime.now().format(timeFormatter);

        System.out.println(
                "[" + physicalTime + "] "
                + "[" + stationName + "] "
                + "[L=" + timestamp + "] "
                + message
        );

        // Web dashboard: the same event as structured data
        EventFeed.station(physicalTime, stationName, timestamp, message);
    }

    /** Prints an event caused by a received message (Lamport receive rule). */
    int printReceivedEvent(int messageTimestamp, String message) {

        int timestamp = receiveLamportTimestamp(messageTimestamp);

        printAtTimestamp(timestamp, message);

        return timestamp;
    }

    private void printElectionEvent(String message) {

        printEvent("ELECTION → " + message);
    }

    // =========================================================
    // EVENT LOG (Experiment 7 input)
    // =========================================================

    /** Appends one structured railway event to this station's local log. */
    void recordEvent(int lamportTimestamp,
                     String eventType,
                     String field1,
                     String field2,
                     String field3) {

        String line = lamportTimestamp + "|" + stationName + "|"
                + eventType + "|" + field1 + "|" + field2 + "|" + field3;

        synchronized (eventLog) {
            eventLog.add(line);
        }
    }

    public int getEventCount() {

        synchronized (eventLog) {
            return eventLog.size();
        }
    }

    /** Writes this station's log to {@code <directory>/<STATION>.log}. */
    public Path exportEventLog(Path directory) throws IOException {

        List<String> lines;

        synchronized (eventLog) {
            lines = new ArrayList<>(eventLog);
        }

        Files.createDirectories(directory);
        Path file = directory.resolve(stationName + ".log");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    // =========================================================
    // SIMULATE COORDINATOR FAILURE
    // =========================================================

    public void failNode() {

        active = false;
        coordinator = false;

        int timestamp = printEvent(
                "!!! NODE FAILURE !!! "
                + stationName
                + " has stopped responding."
        );

        recordEvent(timestamp, "NODE_EVENT", stationName, "FAILED", "NONE");
    }

    // =========================================================
    // RECOVER NODE
    // =========================================================

    public void recoverNode() {

        active = true;

        int timestamp = printEvent(
                "Node recovered and is ACTIVE."
        );

        recordEvent(timestamp, "NODE_EVENT", stationName, "RECOVERED", "NONE");
    }

    // =========================================================
    // HEARTBEAT CHECK
    // =========================================================

    public boolean checkHeartbeat() {

        if (active) {

            int timestamp = printEvent(
                    "Heartbeat check → "
                    + stationName
                    + " : ALIVE"
            );

            recordEvent(timestamp, "HEARTBEAT_EVENT", stationName, "ALIVE", "NONE");

            return true;

        } else {

            int timestamp = printEvent(
                    "Heartbeat check → "
                    + stationName
                    + " : NO RESPONSE"
            );

            recordEvent(timestamp, "HEARTBEAT_EVENT", stationName, "NO_RESPONSE", "NONE");

            return false;
        }
    }

    // =========================================================
    // BULLY ELECTION
    // =========================================================

    public static RailwayStation bullyElection(
            List<RailwayStation> nodes) {

        System.out.println();
        System.out.println(
                "=========================================================="
        );
        System.out.println(
                "              BULLY ELECTION ALGORITHM"
        );
        System.out.println(
                "=========================================================="
        );

        RailwayStation failedCoordinator = null;

        for (RailwayStation node : nodes) {

            if (node.isCoordinator()) {
                failedCoordinator = node;
                break;
            }
        }

        if (failedCoordinator != null) {

            System.out.println();
            System.out.println(
                    "Current Coordinator: "
                    + failedCoordinator.getStationName()
                    + " (ID "
                    + failedCoordinator.getStationId()
                    + ")"
            );

            System.out.println(
                    "Coordinator has failed!"
            );
        }

        // The lowest-ID active node detects the failure and initiates
        RailwayStation initiator = null;

        for (RailwayStation node : nodes) {

            if (node.isActive()) {

                if (initiator == null ||
                        node.getStationId()
                        < initiator.getStationId()) {

                    initiator = node;
                }
            }
        }

        if (initiator == null) {

            System.out.println(
                    "No active nodes available."
            );

            return null;
        }

        System.out.println();
        System.out.println(
                "STEP 1: Failure detected."
        );

        initiator.printElectionEvent(
                "Coordinator failure detected."
        );

        System.out.println();
        System.out.println(
                "STEP 2: Station-"
                + initiator.getStationId()
                + " starts Bully election."
        );

        initiator.printElectionEvent(
                "Starting election because coordinator "
                + "is not responding."
        );

        // =====================================================
        // SEND ELECTION MESSAGES TO HIGHER-ID NODES
        // =====================================================

        System.out.println();
        System.out.println(
                "STEP 3: Election messages sent to "
                + "higher-ID active nodes."
        );

        for (RailwayStation node : nodes) {

            if (node.isActive()
                    && node.getStationId()
                    > initiator.getStationId()) {

                initiator.printElectionEvent(
                        "ELECTION message sent to "
                        + node.getStationName()
                        + " (ID "
                        + node.getStationId()
                        + ")"
                );

                node.printElectionEvent(
                        "Received ELECTION message from "
                        + initiator.getStationName()
                );

                node.printElectionEvent(
                        "REPLY / OK → I have higher priority."
                );
            }
        }

        // =====================================================
        // FIND HIGHEST ACTIVE NODE
        // =====================================================

        RailwayStation winner = null;

        for (RailwayStation node : nodes) {

            if (node.isActive()) {

                if (winner == null ||
                        node.getStationId()
                        > winner.getStationId()) {

                    winner = node;
                }
            }
        }

        System.out.println();
        System.out.println(
                "STEP 4: Highest-ID active node wins."
        );

        winner.printElectionEvent(
                "Highest active station ID = "
                + winner.getStationId()
        );

        // =====================================================
        // ANNOUNCE NEW COORDINATOR
        // =====================================================

        for (RailwayStation node : nodes) {
            node.setCoordinator(false);
        }

        winner.setCoordinator(true);

        System.out.println();
        System.out.println(
                "STEP 5: New coordinator announcement."
        );

        winner.printElectionEvent(
                "COORDINATOR message sent to all active nodes."
        );

        for (RailwayStation node : nodes) {

            if (node.isActive()
                    && node != winner) {

                node.printElectionEvent(
                        "Received COORDINATOR message."
                );
            }
        }

        System.out.println();
        System.out.println(
                "=========================================================="
        );
        System.out.println(
                "BULLY ELECTION COMPLETED"
        );
        System.out.println(
                "New Coordinator: "
                + winner.getStationName()
                + " (ID "
                + winner.getStationId()
                + ")"
        );
        System.out.println(
                "=========================================================="
        );

        return winner;
    }

    // =========================================================
    // RING ELECTION
    // =========================================================

    public static RailwayStation ringElection(
            List<RailwayStation> nodes) {

        System.out.println();
        System.out.println(
                "=========================================================="
        );
        System.out.println(
                "              RING ELECTION ALGORITHM"
        );
        System.out.println(
                "=========================================================="
        );

        // Find current coordinator
        RailwayStation currentCoordinator = null;

        for (RailwayStation node : nodes) {

            if (node.isCoordinator()) {

                currentCoordinator = node;
                break;
            }
        }

        if (currentCoordinator != null) {

            System.out.println();
            System.out.println(
                    "Current Coordinator: "
                    + currentCoordinator.getStationName()
                    + " (ID "
                    + currentCoordinator.getStationId()
                    + ")"
            );

            System.out.println(
                    "Coordinator has failed!"
            );
        }

        // =====================================================
        // STEP 1 - FIND ACTIVE INITIATOR
        // =====================================================

        RailwayStation initiator = null;

        for (RailwayStation node : nodes) {

            if (node.isActive()) {

                if (initiator == null ||
                        node.getStationId()
                        < initiator.getStationId()) {

                    initiator = node;
                }
            }
        }

        System.out.println();
        System.out.println(
                "STEP 1: Coordinator failure detected."
        );

        initiator.printElectionEvent(
                "Starting Ring election."
        );

        // =====================================================
        // CREATE ORDERED RING
        // =====================================================

        List<RailwayStation> ring =
                new ArrayList<>();

        for (RailwayStation node : nodes) {

            if (node.isActive()) {
                ring.add(node);
            }
        }

        ring.sort(
                (a, b) ->
                        Integer.compare(
                                a.getStationId(),
                                b.getStationId()
                        )
        );

        System.out.println();
        System.out.println(
                "STEP 2: Logical ring created:"
        );

        for (int i = 0; i < ring.size(); i++) {

            RailwayStation current =
                    ring.get(i);

            RailwayStation next =
                    ring.get(
                            (i + 1) % ring.size()
                    );

            System.out.println(
                    current.getStationName()
                    + " → "
                    + next.getStationName()
            );
        }

        // =====================================================
        // STEP 3 - PASS ELECTION MESSAGE
        // =====================================================

        System.out.println();
        System.out.println(
                "STEP 3: Election message circulates "
                + "around the ring."
        );

        List<Integer> electionIds =
                new ArrayList<>();

        int startIndex = ring.indexOf(initiator);

        for (int i = 0; i < ring.size(); i++) {

            RailwayStation current =
                    ring.get(
                            (startIndex + i)
                                    % ring.size()
                    );

            electionIds.add(
                    current.getStationId()
            );

            RailwayStation next =
                    ring.get(
                            (startIndex + i + 1)
                                    % ring.size()
                    );

            current.printElectionEvent(
                    "Passing ELECTION message to "
                    + next.getStationName()
                    + " (ID "
                    + next.getStationId()
                    + ")"
            );

            next.printElectionEvent(
                    "Received ELECTION message."
            );
        }

        // =====================================================
        // STEP 4 - DETERMINE WINNER
        // =====================================================

        int highestId = -1;

        for (int id : electionIds) {

            if (id > highestId) {
                highestId = id;
            }
        }

        RailwayStation winner = null;

        for (RailwayStation node : ring) {

            if (node.getStationId()
                    == highestId) {

                winner = node;
                break;
            }
        }

        System.out.println();
        System.out.println(
                "STEP 4: Election message completed "
                + "one full ring."
        );

        winner.printElectionEvent(
                "Highest station ID found = "
                + winner.getStationId()
        );

        // =====================================================
        // STEP 5 - COORDINATOR MESSAGE
        // =====================================================

        for (RailwayStation node : nodes) {
            node.setCoordinator(false);
        }

        winner.setCoordinator(true);

        System.out.println();
        System.out.println(
                "STEP 5: Coordinator message "
                + "circulates around the ring."
        );

        for (int i = 0; i < ring.size(); i++) {

            RailwayStation current =
                    ring.get(i);

            RailwayStation next =
                    ring.get(
                            (i + 1) % ring.size()
                    );

            current.printElectionEvent(
                    "COORDINATOR message passed to "
                    + next.getStationName()
            );
        }

        System.out.println();
        System.out.println(
                "=========================================================="
        );
        System.out.println(
                "RING ELECTION COMPLETED"
        );
        System.out.println(
                "New Coordinator: "
                + winner.getStationName()
                + " (ID "
                + winner.getStationId()
                + ")"
        );
        System.out.println(
                "=========================================================="
        );

        return winner;
    }

    // =========================================================
    // BULLY ELECTION ON RECOVERY (Experiment 4 rule, used before
    // Experiment 5): a recovered station always starts an election.
    // If no higher-ID station answers, it becomes coordinator and the
    // current coordinator steps down.
    // =========================================================

    public static RailwayStation bullyRecoveryElection(
            RailwayStation recovered,
            List<RailwayStation> nodes) {

        Terminal.banner(
                "        BULLY ELECTION ON NODE RECOVERY"
        );

        RailwayStation previousCoordinator = null;

        for (RailwayStation node : nodes) {
            if (node.isCoordinator()) {
                previousCoordinator = node;
            }
        }

        recovered.printElectionEvent(
                "Recovered node starts an election."
        );

        RailwayStation winner = recovered;

        for (RailwayStation node : nodes) {

            if (node.isActive()
                    && node.getStationId() > recovered.getStationId()) {

                recovered.printElectionEvent(
                        "ELECTION message sent to " + node.getStationName()
                );
                node.printElectionEvent("REPLY / OK → I have higher priority.");

                if (node.getStationId() > winner.getStationId()) {
                    winner = node;
                }
            }
        }

        if (winner == recovered) {
            recovered.printElectionEvent(
                    "No higher-ID station replied → I am the COORDINATOR."
            );
        }

        for (RailwayStation node : nodes) {
            node.setCoordinator(false);
        }

        winner.setCoordinator(true);

        winner.printElectionEvent(
                "COORDINATOR message sent to all active nodes."
        );

        for (RailwayStation node : nodes) {

            if (node.isActive() && node != winner) {

                String note = (node == previousCoordinator)
                        ? " Stepping down as coordinator."
                        : "";

                node.printElectionEvent(
                        "Received COORDINATOR message from "
                        + winner.getStationName() + "." + note
                );
            }
        }

        Terminal.line("");
        Terminal.line(
                "Coordinator re-established: "
                + winner.getStationName()
                + " (ID " + winner.getStationId() + ")"
        );

        return winner;
    }

    // =========================================================
    // EXPERIMENT 5 - REPLICATION ENDPOINTS (backup side)
    // These are the "remote procedures" the primary invokes on a
    // backup. Each message carries the sender's Lamport timestamp.
    // =========================================================

    /** Backup applies one update from the primary and returns an ACK. */
    public TrackStateReplica.Ack receiveReplicationUpdate(
            TrackStateReplica.Update update) {

        if (!active) {
            throw new IllegalStateException(stationName + " is not responding");
        }

        int timestamp = receiveLamportTimestamp(update.lamportTimestamp());
        long version = replica.applyUpdate(update);

        recordEvent(timestamp, "REPLICATION_EVENT",
                "v" + version, "APPLIED", update.primaryName());

        return new TrackStateReplica.Ack(stationName, version, timestamp);
    }

    /** Backup installs a full copy of the primary's state (state transfer). */
    public TrackStateReplica.Ack receiveStateTransfer(
            Map<String, TrackStateReplica.TrackStatus> snapshot,
            Map<String, Long> appliedRequests,
            long version,
            int messageTimestamp,
            String primaryName) {

        if (!active) {
            throw new IllegalStateException(stationName + " is not responding");
        }

        int timestamp = receiveLamportTimestamp(messageTimestamp);
        replica.installSnapshot(snapshot, appliedRequests, version);

        recordEvent(timestamp, "REPLICATION_EVENT",
                "v" + version, "SNAPSHOT", primaryName);

        return new TrackStateReplica.Ack(stationName, version, timestamp);
    }

    // =========================================================
    // EXPERIMENT 8 - HEARTBEAT REPLY (failure detection)
    // =========================================================

    /**
     * Answers a heartbeat from a monitoring station. A crashed station
     * cannot answer, which is how the monitor detects the failure.
     *
     * @return true if the heartbeat was acknowledged
     */
    public boolean answerHeartbeat(int messageTimestamp, RailwayStation monitor) {

        if (!active) {
            return false;
        }

        int timestamp = printReceivedEvent(messageTimestamp,
                "HEARTBEAT ACK → " + monitor.getStationName());

        recordEvent(timestamp, "HEARTBEAT_EVENT",
                stationName, "ALIVE", monitor.getStationName());

        return true;
    }

    // =========================================================
    // EXPERIMENT 6 - TRAIN REQUEST PROCESSING (worker thread)
    // =========================================================

    /**
     * Queues a request on this station's worker thread and returns
     * immediately; different stations therefore work in parallel.
     */
    public synchronized Future<TrainRequest.Result> submitTrainRequest(
            TrainRequest request) {

        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, stationName + "-worker");
                thread.setDaemon(true);
                return thread;
            });
        }

        return worker.submit(() -> processTrainRequest(request));
    }

    /** Runs on the worker thread. Prints nothing; the result is reported later. */
    private TrainRequest.Result processTrainRequest(TrainRequest request)
            throws InterruptedException {

        String startedAt = LocalTime.now().format(timeFormatter);

        // Route and signal preparation (fixed duration → reproducible demo)
        Thread.sleep(TRAIN_PROCESSING_TIME_MS);

        int timestamp = incrementLamportClock();

        recordEvent(timestamp, "TRAIN_EVENT",
                request.trainId(), request.trackId(), request.movement().name());

        return new TrainRequest.Result(
                request,
                stationName,
                Thread.currentThread().getName(),
                startedAt,
                LocalTime.now().format(timeFormatter),
                timestamp
        );
    }

    public synchronized void shutdownWorker() {

        if (worker != null) {
            worker.shutdown();
        }
    }
}
