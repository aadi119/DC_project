/**
 * EXPERIMENT 6 - a train scheduling request handled by the load balancer.
 *
 * The sequence number is the global order in which requests arrived.
 * Stations may process requests in parallel, but the primary commits the
 * resulting track changes strictly in sequence order (total ordering), so
 * every replica sees the same history on every run.
 */
public record TrainRequest(int sequence,
                           String trainId,
                           Movement movement,
                           String trackId) {

    public enum Movement { ARRIVAL, DEPARTURE }

    /** Outcome of a station worker thread processing one request. */
    public record Result(TrainRequest request,
                         String stationName,
                         String workerThread,
                         String startedAt,
                         String finishedAt,
                         int lamportTimestamp) {
    }

    @Override
    public String toString() {
        return String.format("%s (%-9s %s)", trainId, movement, trackId);
    }
}
