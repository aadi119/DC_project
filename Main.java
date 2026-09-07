import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {

        System.out.println();
        System.out.println(
                "**********************************************************"
        );
        System.out.println(
                "        DISTRIBUTED RAILWAY COORDINATION SYSTEM"
        );
        System.out.println(
                "        EXPERIMENT 4 - LEADER ELECTION"
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
}