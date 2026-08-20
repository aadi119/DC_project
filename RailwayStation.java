import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class RailwayStation {

    private String stationName;
    private String masterJunction;

    // Formatter for timestamps including milliseconds
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Constructor
    public RailwayStation(String stationName, String masterJunction) {
        this.stationName = stationName;
        this.masterJunction = masterJunction;
    }

    // Method to generate current timestamp
    private String getTimestamp() {
        return LocalTime.now().format(TIME_FORMAT);
    }

    // Thread 1: Signal Processing
    public void processSignalUpdates() {

        Thread signalThread = new Thread(() -> {

            String[] signals = {
                "RED",
                "GREEN",
                "YELLOW",
                "RED",
                "GREEN"
            };

            for (String signal : signals) {

                System.out.println(
                    "[" + getTimestamp() + "] "
                    + "[" + Thread.currentThread().getName() + "] "
                    + "Signal at " + stationName
                    + " changed to " + signal
                );

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    System.out.println(
                        "[" + getTimestamp() + "] "
                        + "[Signal-Thread] "
                        + "Signal processing interrupted."
                    );

                    return;
                }
            }

            System.out.println(
                "[" + getTimestamp() + "] "
                + "[Signal-Thread] "
                + "Signal processing completed."
            );

        }, "Signal-Thread");

        signalThread.start();
    }

    // Thread 2: Heartbeat Monitoring
    public void monitorMasterHeartbeat() {

        Thread heartbeatThread = new Thread(() -> {

            for (int i = 1; i <= 7; i++) {

                System.out.println(
                    "[" + getTimestamp() + "] "
                    + "[" + Thread.currentThread().getName() + "] "
                    + "Heartbeat check " + i
                    + " -> Master " + masterJunction
                    + " : ALIVE"
                );

                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    System.out.println(
                        "[" + getTimestamp() + "] "
                        + "[Heartbeat-Thread] "
                        + "Heartbeat monitoring interrupted."
                    );

                    return;
                }
            }

            System.out.println(
                "[" + getTimestamp() + "] "
                + "[Heartbeat-Thread] "
                + "Heartbeat monitoring completed."
            );

        }, "Heartbeat-Thread");

        heartbeatThread.start();
    }

    // Thread 3: Train Event Processing
    public void processTrainEvents() {

        Thread trainThread = new Thread(() -> {

            String[] trainEvents = {
                "Train 101 arriving at Platform 1",
                "Train 101 departed from Platform 1",
                "Train 102 arriving at Platform 2",
                "Train 102 departed from Platform 2",
                "Train 103 arriving at Platform 1"
            };

            for (String event : trainEvents) {

                System.out.println(
                    "[" + getTimestamp() + "] "
                    + "[" + Thread.currentThread().getName() + "] "
                    + event
                );

                try {
                    Thread.sleep(900);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    System.out.println(
                        "[" + getTimestamp() + "] "
                        + "[Train-Thread] "
                        + "Train event processing interrupted."
                    );

                    return;
                }
            }

            System.out.println(
                "[" + getTimestamp() + "] "
                + "[Train-Thread] "
                + "Train event processing completed."
            );

        }, "Train-Thread");

        trainThread.start();
    }

    // Start all railway activities
    public void startStationOperations() {

        System.out.println();
        System.out.println("Starting railway station threads...");
        System.out.println();

        processSignalUpdates();
        monitorMasterHeartbeat();
        processTrainEvents();
    }
}