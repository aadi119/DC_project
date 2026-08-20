public class Main {

    public static void main(String[] args) {

        System.out.println("==============================================================");
        System.out.println("           RAILWAY COORDINATION SYSTEM");
        System.out.println("                  EXPERIMENT 2");
        System.out.println("              MULTITHREADING DEMONSTRATION");
        System.out.println("==============================================================");

        System.out.println();

        System.out.println("Station Node    : STATION-01");
        System.out.println("Master Junction : JUNCTION-01");
        System.out.println("Worker Threads  : 3");

        System.out.println();
        System.out.println("Starting concurrent railway operations...");
        System.out.println();

        // Create railway station node
        RailwayStation station =
                new RailwayStation("STATION-01", "JUNCTION-01");

        // Start all three threads
        station.startStationOperations();

        System.out.println();
        System.out.println(
                "Main Thread: All railway activity threads started successfully."
        );

        System.out.println(
                "Main Thread: Signal, heartbeat and train events are executing concurrently."
        );

        System.out.println();

        // Keep main thread alive while worker threads execute
        try {
            Thread.sleep(8000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            System.out.println(
                    "Main Thread: Execution interrupted."
            );
        }

        System.out.println();
        System.out.println("==============================================================");
        System.out.println("        MULTITHREADING DEMONSTRATION COMPLETED");
        System.out.println("==============================================================");
    }
}