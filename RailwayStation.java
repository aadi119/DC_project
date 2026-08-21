import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class RailwayStation {

    private final String stationName;
    private final String masterNode;

    // Lamport Logical Clock
    private int lamportClock = 0;

    // Used to make updates to Lamport clock thread-safe
    private final Object clockLock = new Object();

    private final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public RailwayStation(String stationName, String masterNode) {
        this.stationName = stationName;
        this.masterNode = masterNode;
    }

    // ---------------------------------------------------------
    // LAMPORT CLOCK METHODS
    // ---------------------------------------------------------

    // Increment clock for a local event
    private int incrementLamportClock() {
        synchronized (clockLock) {
            lamportClock++;
            return lamportClock;
        }
    }

    // Update clock when receiving a message from another node
    public int receiveLamportTimestamp(int receivedTimestamp) {
        synchronized (clockLock) {
            lamportClock =
                    Math.max(lamportClock, receivedTimestamp) + 1;

            return lamportClock;
        }
    }

    // Get current Lamport clock value
    public int getLamportClock() {
        synchronized (clockLock) {
            return lamportClock;
        }
    }

    // ---------------------------------------------------------
    // OUTPUT METHOD
    // ---------------------------------------------------------

    private void printEvent(String threadName,
                            int logicalTimestamp,
                            String message) {

        String physicalTime =
                LocalTime.now().format(timeFormatter);

        System.out.println(
                "[" + physicalTime + "] "
                + "[" + threadName + "] "
                + "[Lamport=" + logicalTimestamp + "] "
                + message
        );
    }

    // ---------------------------------------------------------
    // SIGNAL THREAD
    // ---------------------------------------------------------

    private void signalOperations() {

        String threadName = Thread.currentThread().getName();

        String[] signals = {
                "RED -> GREEN",
                "GREEN -> YELLOW",
                "YELLOW -> RED",
                "RED -> GREEN"
        };

        for (String signal : signals) {

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            int timestamp = incrementLamportClock();

            printEvent(
                    threadName,
                    timestamp,
                    "Signal changed: " + signal
            );
        }

        printEvent(
                threadName,
                getLamportClock(),
                "Signal Thread completed."
        );
    }

    // ---------------------------------------------------------
    // HEARTBEAT THREAD
    // ---------------------------------------------------------

    private void heartbeatOperations() {

        String threadName = Thread.currentThread().getName();

        for (int i = 1; i <= 5; i++) {

            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            int timestamp = incrementLamportClock();

            printEvent(
                    threadName,
                    timestamp,
                    "Heartbeat -> "
                    + masterNode
                    + " : ALIVE"
            );
        }

        printEvent(
                threadName,
                getLamportClock(),
                "Heartbeat Thread completed."
        );
    }

    // ---------------------------------------------------------
    // TRAIN THREAD
    // ---------------------------------------------------------

    private void trainOperations() {

        String threadName = Thread.currentThread().getName();

        String[] trainEvents = {
                "Train 101 arriving at Platform 1",
                "Train 101 stopped at Platform 1",
                "Train 101 departed from Platform 1",
                "Train 102 arriving at Platform 2"
        };

        for (String event : trainEvents) {

            try {
                Thread.sleep(900);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            int timestamp = incrementLamportClock();

            printEvent(
                    threadName,
                    timestamp,
                    event
            );
        }

        printEvent(
                threadName,
                getLamportClock(),
                "Train Thread completed."
        );
    }

    // ---------------------------------------------------------
    // START ALL THREADS
    // ---------------------------------------------------------

    public void startStationOperations() {

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("       RAILWAY COORDINATION SYSTEM");
        System.out.println("       EXPERIMENT 3 - LAMPORT LOGICAL CLOCK");
        System.out.println("==========================================================");
        System.out.println("Station Node : " + stationName);
        System.out.println("Master Node  : " + masterNode);
        System.out.println("Initial Lamport Clock : L = "
                + getLamportClock());
        System.out.println();
        System.out.println("Starting railway station threads...");
        System.out.println();

        Thread signalThread =
                new Thread(
                        this::signalOperations,
                        "Signal-Thread"
                );

        Thread heartbeatThread =
                new Thread(
                        this::heartbeatOperations,
                        "Heartbeat-Thread"
                );

        Thread trainThread =
                new Thread(
                        this::trainOperations,
                        "Train-Thread"
                );

        // Start all three threads
        signalThread.start();
        heartbeatThread.start();
        trainThread.start();

        // Wait for all threads to finish
        try {
            signalThread.join();
            heartbeatThread.join();
            trainThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("All railway threads completed.");
        System.out.println("Final Lamport Clock : L = "
                + getLamportClock());
        System.out.println("==========================================================");
    }
}