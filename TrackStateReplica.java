import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * EXPERIMENT 5 - one replica of the railway track state.
 *
 * Every railway station owns exactly one replica. The primary's replica is
 * the authoritative copy; backups hold copies that are kept identical by
 * the primary-backup protocol in {@link ReplicationManager}.
 *
 * Consistency mechanism: a monotonically increasing STATE VERSION.
 * Each committed update produces version N+1, and a replica only accepts
 * an update whose version is exactly (its current version + 1). This
 * guarantees every replica applies the same updates in the same order,
 * and makes a missed or duplicated update impossible to apply silently.
 *
 * All methods are synchronized: the replica may be read by the main
 * thread while station worker threads are running (Experiment 6).
 *
 * TERM: every primary leads under a term number, increased at each failover.
 * A replica remembers the highest term it has seen and refuses updates from
 * an older term, so a deposed primary (cut off by a network partition, still
 * believing it leads) cannot overwrite the new primary's data.
 *
 * Experiment 8: the replica also remembers which client request IDs it has
 * applied. This table is replicated with the data, so after a failover the
 * new primary can recognise a retried request and not apply it twice.
 */
public class TrackStateReplica {

    public static final String CLEAR = "CLEAR";
    public static final String OCCUPIED = "OCCUPIED";
    public static final String NO_TRAIN = "NONE";

    /** State of one railway track, optionally with the train on it. */
    public record TrackStatus(String state, String trainId) implements java.io.Serializable {

        public static TrackStatus clear() {
            return new TrackStatus(CLEAR, NO_TRAIN);
        }

        public static TrackStatus occupied(String trainId) {
            return new TrackStatus(OCCUPIED, trainId);
        }

        public boolean isOccupied() {
            return OCCUPIED.equals(state);
        }

        @Override
        public String toString() {
            return isOccupied() ? state + " → " + trainId : state;
        }
    }

    /**
     * Replication message sent from the primary to a backup.
     * requestId is the client request that caused the update (null if none).
     */
    public record Update(long version,
                         String trackId,
                         TrackStatus newStatus,
                         String primaryName,
                         int lamportTimestamp,
                         String requestId,
                         long term) {
    }

    /** An update from a primary whose term is older than one this replica has seen. */
    public static class StaleTermException extends IllegalStateException {

        private final long messageTerm;
        private final long currentTerm;

        public StaleTermException(long messageTerm, long currentTerm) {
            super("stale term " + messageTerm + " < current term " + currentTerm);
            this.messageTerm = messageTerm;
            this.currentTerm = currentTerm;
        }

        public long getMessageTerm() {
            return messageTerm;
        }

        public long getCurrentTerm() {
            return currentTerm;
        }
    }

    /** Acknowledgement returned by a replica after applying a message. */
    public record Ack(String stationName, long version, int lamportTimestamp) {
    }

    // TreeMap → tracks are always listed in a stable order
    private final Map<String, TrackStatus> tracks = new TreeMap<>();
    private long version = 0;
    private long term = 0;

    // Experiment 8: client request ID → version at which it was applied
    private final Map<String, Long> appliedRequests = new LinkedHashMap<>();

    public synchronized long getVersion() {
        return version;
    }

    public synchronized long getTerm() {
        return term;
    }

    /** Learns about a (possibly newer) term. Terms never go back. */
    public synchronized void observeTerm(long seenTerm) {
        term = Math.max(term, seenTerm);
    }

    public synchronized TrackStatus get(String trackId) {
        return tracks.get(trackId);
    }

    /** Immutable copy of the whole track table. */
    public synchronized Map<String, TrackStatus> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(tracks));
    }

    /** Version at which a client request was applied, or null if never. */
    public synchronized Long versionOfRequest(String requestId) {
        return appliedRequests.get(requestId);
    }

    /** Immutable copy of the applied-request table. */
    public synchronized Map<String, Long> appliedRequestsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(appliedRequests));
    }

    /**
     * Applies one replicated update, enforcing in-order delivery.
     *
     * @return the new version of this replica
     * @throws IllegalStateException if the update is out of order
     */
    public synchronized long applyUpdate(Update update) {

        if (update.term() < term) {
            throw new StaleTermException(update.term(), term);
        }

        if (update.version() != version + 1) {
            throw new IllegalStateException(
                    "out-of-order update: replica at version " + version
                    + ", received version " + update.version());
        }

        tracks.put(update.trackId(), update.newStatus());
        version = update.version();
        term = update.term();

        if (update.requestId() != null) {
            appliedRequests.put(update.requestId(), version);
        }
        return version;
    }

    /**
     * Replaces the whole state (full state transfer). Used for the initial
     * state and to bring a recovered, stale replica up to date.
     */
    public synchronized void installSnapshot(Map<String, TrackStatus> state,
                                             long snapshotVersion) {
        installSnapshot(state, Map.of(), snapshotVersion);
    }

    /** Full state transfer including the applied-request table (Experiment 8). */
    public synchronized void installSnapshot(Map<String, TrackStatus> state,
                                             Map<String, Long> requests,
                                             long snapshotVersion) {
        tracks.clear();
        tracks.putAll(state);
        appliedRequests.clear();
        appliedRequests.putAll(requests);
        version = snapshotVersion;
    }

    /**
     * CRC32 over version + full track table. Two replicas with the same
     * checksum hold the same data; it gives a compact value to display.
     */
    public synchronized String checksum() {

        StringBuilder canonical = new StringBuilder("v").append(version);

        for (Map.Entry<String, TrackStatus> entry : tracks.entrySet()) {
            canonical.append(';')
                    .append(entry.getKey()).append('=')
                    .append(entry.getValue().state()).append('/')
                    .append(entry.getValue().trainId());
        }

        CRC32 crc = new CRC32();
        crc.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return String.format("%08X", crc.getValue());
    }
}
