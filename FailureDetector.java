/**
 * EXPERIMENT 8 - Heartbeat-based failure detector.
 *
 * A monitoring station sends a heartbeat to the target every interval.
 * One missed reply may only be a delay, so the target is declared FAILED
 * only after a fixed number of consecutive missed heartbeats. This avoids
 * false alarms while still detecting a crash within a bounded time.
 */
public class FailureDetector {

    private final int missedLimit;
    private final long intervalMs;

    public FailureDetector(int missedLimit, long intervalMs) {
        this.missedLimit = missedLimit;
        this.intervalMs = intervalMs;
    }

    public int getMissedLimit() {
        return missedLimit;
    }

    /**
     * Runs heartbeat rounds from monitor to target.
     *
     * @param maxRounds rounds to run if the target keeps answering
     * @return true if the target was declared failed
     */
    public boolean monitor(RailwayStation monitor, RailwayStation target, int maxRounds)
            throws InterruptedException {

        int missed = 0;

        for (int round = 1; round <= maxRounds; round++) {

            int sendTimestamp = monitor.printEvent(
                    "HEARTBEAT #" + round + " → " + target.getStationName());

            if (target.answerHeartbeat(sendTimestamp, monitor)) {
                missed = 0;
            } else {
                missed++;
                monitor.printEvent("No reply from " + target.getStationName()
                        + " (missed " + missed + "/" + missedLimit + ")");

                if (missed >= missedLimit) {
                    int timestamp = monitor.printEvent("⚠ " + target.getStationName()
                            + " DECLARED FAILED after " + missed + " missed heartbeats");
                    monitor.recordEvent(timestamp, "NODE_EVENT",
                            target.getStationName(), "DECLARED_FAILED", "NONE");
                    return true;
                }
            }

            // Fixed heartbeat interval (controlled timing → reproducible demo)
            Thread.sleep(intervalMs);
        }

        return false;
    }
}
