import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class RailwayStation {

    private final String stationName;
    private final int stationId;

    private boolean active;
    private boolean coordinator;

    // Lamport Logical Clock from Experiment 3
    private int lamportClock = 0;

    private final Object clockLock = new Object();

    private final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public RailwayStation(String stationName, int stationId) {
        this.stationName = stationName;
        this.stationId = stationId;
        this.active = true;
        this.coordinator = false;
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

    // =========================================================
    // OUTPUT
    // =========================================================

    private void printEvent(String message) {

        int timestamp = incrementLamportClock();

        String physicalTime =
                LocalTime.now().format(timeFormatter);

        System.out.println(
                "[" + physicalTime + "] "
                + "[" + stationName + "] "
                + "[L=" + timestamp + "] "
                + message
        );
    }

    private void printElectionEvent(String message) {

        int timestamp = incrementLamportClock();

        String physicalTime =
                LocalTime.now().format(timeFormatter);

        System.out.println(
                "[" + physicalTime + "] "
                + "[" + stationName + "] "
                + "[L=" + timestamp + "] "
                + "ELECTION → " + message
        );
    }

    // =========================================================
    // SIMULATE COORDINATOR FAILURE
    // =========================================================

    public void failNode() {

        active = false;
        coordinator = false;

        printEvent(
                "!!! NODE FAILURE !!! "
                + stationName
                + " has stopped responding."
        );
    }

    // =========================================================
    // RECOVER NODE
    // =========================================================

    public void recoverNode() {

        active = true;

        printEvent(
                "Node recovered and is ACTIVE."
        );
    }

    // =========================================================
    // HEARTBEAT CHECK
    // =========================================================

    public boolean checkHeartbeat() {

        if (active) {

            printEvent(
                    "Heartbeat check → "
                    + stationName
                    + " : ALIVE"
            );

            return true;

        } else {

            printEvent(
                    "Heartbeat check → "
                    + stationName
                    + " : NO RESPONSE"
            );

            return false;
        }
    }

    // =========================================================
    // BULly ELECTION
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

        // Find the highest active node
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
}