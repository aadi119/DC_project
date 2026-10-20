import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * EXPERIMENT 5 - Data Consistency & Replication.
 *
 * Primary-backup (passive) replication with SYNCHRONOUS propagation:
 *
 *   1. Only the primary accepts writes (single writer → no write conflicts).
 *   2. The primary applies the update and gives it the next state version.
 *   3. The update is sent to every backup, one after another; the primary
 *      waits for each backup's ACK before moving on.
 *   4. The write counts as committed only when every reachable backup
 *      has acknowledged it. Every committed version therefore exists on
 *      all replicas, so a backup can take over with no data loss.
 *
 * Methods are synchronized: the manager is the primary's write path, and
 * writes must be applied one at a time in a single global order.
 */
public class ReplicationManager {

    /** A committed write and the backups that acknowledged it. */
    public record Commit(long version, List<String> acknowledgedBy) {
    }

    /** What the primary decided for one train movement (Experiment 6). */
    public record TrainCommit(TrainRequest request,
                              boolean accepted,
                              long version,
                              TrackStateReplica.TrackStatus newStatus,
                              String reason,
                              List<String> acknowledgedBy) {
    }

    /** Reply to a client request (Experiment 8). */
    public record ClientReply(String requestId,
                              boolean duplicate,
                              long version,
                              TrainCommit commit) {
    }

    /** Thrown when the primary cannot answer a client (crashed or unreachable). */
    public static class PrimaryUnavailableException extends RuntimeException {
        public PrimaryUnavailableException(String message) {
            super(message);
        }
    }

    private RailwayStation primary;
    private final List<RailwayStation> backups;

    // What clients have been told is committed. After a failover the new
    // primary must still hold exactly this state, otherwise data was lost.
    private long lastCommittedVersion = 0;
    private Map<String, TrackStateReplica.TrackStatus> lastCommittedState = Map.of();

    // Experiment 8 fault injection: crash the primary after it commits the
    // next client request but before the reply reaches the client
    private boolean crashBeforeNextReply = false;

    public ReplicationManager(RailwayStation primary, List<RailwayStation> backups) {
        this.primary = primary;
        this.backups = new ArrayList<>(backups);
    }

    public synchronized RailwayStation getPrimary() {
        return primary;
    }

    public synchronized List<RailwayStation> getBackups() {
        return List.copyOf(backups);
    }

    // =========================================================
    // INITIAL STATE
    // =========================================================

    /** The primary creates version 1 and ships it to every backup. */
    public synchronized void initializeState(
            Map<String, TrackStateReplica.TrackStatus> initialState) {

        TrackStateReplica primaryReplica = primary.getReplica();
        long version = primaryReplica.getVersion() + 1;
        primaryReplica.installSnapshot(initialState, version);

        int timestamp = primary.printEvent(
                "PRIMARY initialises railway track state → Version " + version);

        for (Map.Entry<String, TrackStateReplica.TrackStatus> entry
                : initialState.entrySet()) {
            primary.recordEvent(timestamp, "TRACK_EVENT", entry.getKey(),
                    entry.getValue().state(), entry.getValue().trainId());
        }

        printTrackState("Track state at " + primary.getStationName(),
                primaryReplica);
        Terminal.blank();

        replicateSnapshotToBackups();
        recordCommit();
    }

    // =========================================================
    // WRITE PATH: PRIMARY UPDATE → SYNCHRONOUS REPLICATION
    // =========================================================

    /**
     * Applies one track change at the primary and replicates it to every
     * backup before returning.
     *
     * @param verbose print every protocol step (Experiment 5) or stay
     *                silent and let the caller print a summary (Experiment 6)
     */
    public synchronized Commit update(String trackId,
                                      TrackStateReplica.TrackStatus newStatus,
                                      boolean verbose) {
        return update(trackId, newStatus, verbose, null);
    }

    /** As above, recording the client request that caused the update. */
    public synchronized Commit update(String trackId,
                                      TrackStateReplica.TrackStatus newStatus,
                                      boolean verbose,
                                      String requestId) {

        TrackStateReplica primaryReplica = primary.getReplica();
        long version = primaryReplica.getVersion() + 1;

        // STEP 1: primary applies the update locally
        int updateTimestamp = verbose
                ? primary.printEvent("PRIMARY UPDATE v" + version + " : "
                        + trackId + " → " + newStatus)
                : primary.sendLamportTimestamp();

        primaryReplica.applyUpdate(new TrackStateReplica.Update(
                version, trackId, newStatus,
                primary.getStationName(), updateTimestamp, requestId));

        primary.recordEvent(updateTimestamp, "TRACK_EVENT",
                trackId, newStatus.state(), newStatus.trainId());

        // STEP 2: replicate to each backup and wait for its ACK
        List<String> acknowledgedBy = new ArrayList<>();
        Iterator<RailwayStation> iterator = backups.iterator();

        while (iterator.hasNext()) {

            RailwayStation backup = iterator.next();

            int sendTimestamp = verbose
                    ? primary.printEvent("REPLICATE v" + version + " → "
                            + backup.getStationName())
                    : primary.sendLamportTimestamp();

            TrackStateReplica.Update message = new TrackStateReplica.Update(
                    version, trackId, newStatus,
                    primary.getStationName(), sendTimestamp, requestId);

            try {
                TrackStateReplica.Ack ack = backup.receiveReplicationUpdate(message,
                        primary.vectorAt(sendTimestamp));

                // The ACK is a message too: merge its timestamp at the primary
                primary.receiveLamportTimestamp(ack.lamportTimestamp(),
                        backup.vectorAt(ack.lamportTimestamp()));

                if (ack.version() != version) {
                    throw new IllegalStateException("ACK for wrong version "
                            + ack.version());
                }

                acknowledgedBy.add(backup.getStationName());

                if (verbose) {
                    backup.printAtTimestamp(ack.lamportTimestamp(),
                            "APPLIED v" + version + " from "
                            + primary.getStationName()
                            + " (msg L=" + sendTimestamp + ") → ACK v"
                            + ack.version());
                }

            } catch (IllegalStateException e) {
                // A backup that cannot apply the update leaves the replica
                // group; it must resynchronise before rejoining.
                Terminal.line("⚠ " + backup.getStationName()
                        + " did not acknowledge (" + e.getMessage()
                        + ") → removed from replica group");
                iterator.remove();
            }
        }

        // STEP 3: every reachable backup has the update → commit
        recordCommit();

        if (verbose) {
            Terminal.line("✓ REPLICATION SUCCESSFUL → Version " + version
                    + " stored on " + primary.getStationName()
                    + (acknowledgedBy.isEmpty() ? "" : ", "
                    + String.join(", ", acknowledgedBy)));
            Terminal.blank();
        }

        return new Commit(version, acknowledgedBy);
    }

    /**
     * EXPERIMENT 6: the primary is the authority on track safety. It checks
     * the requested movement against the replicated state and only then
     * writes it. Rejected movements change no state but are still logged
     * as coordination events.
     */
    public synchronized TrainCommit commitTrainMovement(TrainRequest request) {
        return commitTrainMovement(request, null);
    }

    /** As above, tagging the resulting update with a client request ID. */
    public synchronized TrainCommit commitTrainMovement(TrainRequest request,
                                                        String requestId) {

        TrackStateReplica.TrackStatus current =
                primary.getReplica().get(request.trackId());

        String rejection = null;
        TrackStateReplica.TrackStatus target = null;

        if (current == null) {
            rejection = "unknown track";
        } else if (request.movement() == TrainRequest.Movement.ARRIVAL) {
            if (current.isOccupied()) {
                rejection = request.trackId() + " occupied by " + current.trainId()
                        + " → signal held at RED";
            } else {
                target = TrackStateReplica.TrackStatus.occupied(request.trainId());
            }
        } else {
            if (!current.isOccupied() || !current.trainId().equals(request.trainId())) {
                rejection = request.trainId() + " is not on " + request.trackId();
            } else {
                target = TrackStateReplica.TrackStatus.clear();
            }
        }

        if (rejection != null) {
            int timestamp = primary.sendLamportTimestamp();
            primary.recordEvent(timestamp, "TRACK_EVENT",
                    request.trackId(), "CONFLICT", request.trainId());
            return new TrainCommit(request, false,
                    primary.getReplica().getVersion(), null, rejection, List.of());
        }

        Commit commit = update(request.trackId(), target, false, requestId);
        return new TrainCommit(request, true, commit.version(), target, null,
                commit.acknowledgedBy());
    }

    // =========================================================
    // EXPERIMENT 8 - CLIENT REQUESTS WITH DUPLICATE DETECTION
    // =========================================================

    /**
     * Entry point for client requests. Every request carries a unique ID.
     * If the primary has already applied that ID (for example, the client
     * is retrying after a lost reply), the stored result is returned and
     * the request is NOT applied a second time (idempotent retry).
     *
     * @throws PrimaryUnavailableException if the primary does not answer
     */
    public synchronized ClientReply handleClientRequest(String requestId,
                                                        TrainRequest request) {

        if (!primary.isActive()) {
            throw new PrimaryUnavailableException(
                    primary.getStationName() + " is not responding");
        }

        Long appliedAt = primary.getReplica().versionOfRequest(requestId);
        if (appliedAt != null) {
            return new ClientReply(requestId, true, appliedAt, null);
        }

        TrainCommit commit = commitTrainMovement(request, requestId);

        if (crashBeforeNextReply) {
            crashBeforeNextReply = false;
            primary.failNode();
            throw new PrimaryUnavailableException(primary.getStationName()
                    + (commit.accepted()
                            ? " crashed after committing v" + commit.version()
                            : " crashed after rejecting the request")
                    + ", reply lost");
        }

        return new ClientReply(requestId, false, commit.version(), commit);
    }

    /** Fault injection: the primary crashes right after the next commit. */
    public synchronized void injectCrashBeforeNextReply() {
        crashBeforeNextReply = true;
    }

    // =========================================================
    // CONSISTENCY VERIFICATION
    // =========================================================

    /**
     * Compares the real state of every replica with the primary: the
     * version number AND the full track table must be identical.
     */
    public synchronized boolean verifyConsistency() {

        TrackStateReplica reference = primary.getReplica();
        long referenceVersion = reference.getVersion();
        Map<String, TrackStateReplica.TrackStatus> referenceState = reference.snapshot();

        printReplicaRow(primary, "PRIMARY");
        boolean consistent = true;

        for (RailwayStation backup : backups) {

            printReplicaRow(backup, "BACKUP ");

            TrackStateReplica replica = backup.getReplica();
            if (replica.getVersion() != referenceVersion
                    || !replica.snapshot().equals(referenceState)
                    || !replica.appliedRequestsSnapshot()
                            .equals(reference.appliedRequestsSnapshot())) {
                consistent = false;
            }
        }

        Terminal.blank();

        if (consistent) {
            Terminal.line("✓ ALL REPLICAS CONSISTENT (same version, same track state)");
        } else {
            Terminal.line("✗ REPLICAS INCONSISTENT");
        }

        return consistent;
    }

    // =========================================================
    // FAILOVER: PROMOTE A BACKUP
    // =========================================================

    /**
     * Elects a new primary after the primary fails. The Bully rule from
     * Experiment 4 (highest ID wins) applies, but only to stations whose
     * replica holds the latest committed version. A station without the
     * data must never lead, or committed updates would be lost.
     */
    public synchronized RailwayStation failover(List<RailwayStation> stations) {

        RailwayStation failedPrimary = primary;

        Terminal.line("PRIMARY FAILURE DETECTED → "
                + failedPrimary.getStationName() + " FAILED");
        Terminal.blank();
        Terminal.line("Candidate evaluation (only an up-to-date replica may become primary):");

        long latestVersion = stations.stream()
                .filter(RailwayStation::isActive)
                .mapToLong(station -> station.getReplica().getVersion())
                .max()
                .orElse(0);

        List<RailwayStation> eligible = new ArrayList<>();

        for (RailwayStation station : stations) {

            long version = station.getReplica().getVersion();
            String verdict;

            if (!station.isActive()) {
                verdict = "FAILED";
            } else if (latestVersion > 0 && version == latestVersion) {
                verdict = "ACTIVE | Version " + version + " | ELIGIBLE";
                eligible.add(station);
            } else if (version == 0) {
                verdict = "ACTIVE | Version 0 | NOT ELIGIBLE (holds no replica)";
            } else {
                verdict = "ACTIVE | Version " + version + " | NOT ELIGIBLE (stale)";
            }

            Terminal.line("  " + station.getStationName() + " : " + verdict);
        }

        RailwayStation newPrimary = eligible.stream()
                .max(Comparator.comparingInt(RailwayStation::getStationId))
                .orElseThrow(() -> new IllegalStateException(
                        "no up-to-date replica survived the failure"));

        Terminal.blank();
        Terminal.line("Bully rule among eligible replicas → highest ID: "
                + newPrimary.getStationName());
        Terminal.blank();

        for (RailwayStation station : stations) {
            station.setCoordinator(false);
        }

        newPrimary.setCoordinator(true);
        backups.remove(newPrimary);
        backups.remove(failedPrimary);
        primary = newPrimary;

        int timestamp = newPrimary.printEvent("ELECTION → "
                + newPrimary.getStationName() + " PROMOTED TO PRIMARY");

        for (RailwayStation station : stations) {
            if (station.isActive() && station != newPrimary) {
                station.printReceivedEvent(timestamp, newPrimary.vectorAt(timestamp),
                        "Received COORDINATOR message: new primary = "
                        + newPrimary.getStationName());
            }
        }

        Terminal.blank();
        printTrackState("Recovered track state at " + newPrimary.getStationName(),
                newPrimary.getReplica());
        Terminal.blank();

        TrackStateReplica recovered = newPrimary.getReplica();
        Terminal.line("Last committed before failure : Version " + lastCommittedVersion);
        Terminal.line("Recovered on new primary      : Version " + recovered.getVersion());

        if (recovered.getVersion() == lastCommittedVersion
                && recovered.snapshot().equals(lastCommittedState)) {
            Terminal.line("✓ NO DATA LOSS (every committed update survived the failure)");
        } else {
            Terminal.line("✗ DATA LOSS DETECTED");
        }

        Terminal.blank();
        printReplicaGroup();
        return newPrimary;
    }

    // =========================================================
    // RECOVERY OF A STALE REPLICA
    // =========================================================

    /**
     * A recovered station still holds the state it had when it failed.
     * If that version is older than the primary's, it receives a full
     * state transfer and then rejoins the replica group as a backup.
     */
    public synchronized void resynchronize(RailwayStation station) {

        long staleVersion = station.getReplica().getVersion();
        long currentVersion = primary.getReplica().getVersion();

        Terminal.blank();
        Terminal.line(station.getStationName() + " replica : Version " + staleVersion);
        Terminal.line(primary.getStationName() + " (primary) : Version " + currentVersion);
        Terminal.blank();

        if (staleVersion < currentVersion) {
            Terminal.line("⚠ STALE REPLICA DETECTED: " + station.getStationName()
                    + " missed " + (currentVersion - staleVersion)
                    + " update(s) while it was down");
            Terminal.blank();
            transferState(station);
        } else {
            Terminal.line("Replica already up to date.");
        }

        if (!backups.contains(station)) {
            backups.add(station);
        }

        Terminal.blank();
        Terminal.line("✓ " + station.getStationName()
                + " caught up and rejoined as BACKUP (no automatic fail-back of the primary role)");
        printReplicaGroup();
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private void replicateSnapshotToBackups() {

        Iterator<RailwayStation> iterator = backups.iterator();

        while (iterator.hasNext()) {
            RailwayStation backup = iterator.next();
            try {
                transferState(backup);
            } catch (IllegalStateException e) {
                Terminal.line("⚠ " + backup.getStationName()
                        + " unreachable → removed from replica group");
                iterator.remove();
            }
        }
    }

    /** Full state transfer from the primary to one station. */
    private void transferState(RailwayStation target) {

        TrackStateReplica primaryReplica = primary.getReplica();
        long version = primaryReplica.getVersion();

        int sendTimestamp = primary.printEvent("STATE TRANSFER v" + version
                + " → " + target.getStationName());

        TrackStateReplica.Ack ack = target.receiveStateTransfer(
                primaryReplica.snapshot(),
                primaryReplica.appliedRequestsSnapshot(), version,
                sendTimestamp, primary.vectorAt(sendTimestamp), primary.getStationName());

        primary.receiveLamportTimestamp(ack.lamportTimestamp(),
                target.vectorAt(ack.lamportTimestamp()));

        target.printAtTimestamp(ack.lamportTimestamp(),
                "INSTALLED snapshot v" + version + " from "
                + primary.getStationName() + " (msg L=" + sendTimestamp
                + ") → ACK v" + ack.version());
    }

    private void recordCommit() {
        lastCommittedVersion = primary.getReplica().getVersion();
        lastCommittedState = primary.getReplica().snapshot();
    }

    private void printReplicaRow(RailwayStation station, String role) {
        TrackStateReplica replica = station.getReplica();
        Terminal.line(String.format("  %s (%s) : Version %d | Checksum %s",
                station.getStationName(), role,
                replica.getVersion(), replica.checksum()));
    }

    private void printReplicaGroup() {
        List<String> names = backups.stream()
                .map(RailwayStation::getStationName).toList();
        Terminal.line("Replica group → PRIMARY " + primary.getStationName()
                + " | BACKUPS " + (names.isEmpty() ? "none" : String.join(", ", names)));
    }

    public static void printTrackState(String title, TrackStateReplica replica) {

        Terminal.line(title + " (Version " + replica.getVersion() + "):");

        for (Map.Entry<String, TrackStateReplica.TrackStatus> entry
                : replica.snapshot().entrySet()) {
            Terminal.line("  " + entry.getKey() + " → " + entry.getValue());
        }
    }
}
