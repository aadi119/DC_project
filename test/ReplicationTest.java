import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Experiment 5: primary-backup replication and consistency. */
class ReplicationTest {

    private static TrackStateReplica.Update update(long version, String track) {
        return new TrackStateReplica.Update(version, track,
                TrackStateReplica.TrackStatus.occupied("TRAIN-1"), "STATION-04", 1, null, 1);
    }

    @Test
    void replicaAcceptsOnlyTheNextVersion() {
        TrackStateReplica replica = new TrackStateReplica();
        assertEquals(1, replica.applyUpdate(update(1, "TRACK-01")));
        assertEquals(2, replica.applyUpdate(update(2, "TRACK-02")));

        // a gap (missed update) and a duplicate are both refused
        assertThrows(IllegalStateException.class, () -> replica.applyUpdate(update(4, "TRACK-03")));
        assertThrows(IllegalStateException.class, () -> replica.applyUpdate(update(2, "TRACK-03")));
        assertEquals(2, replica.getVersion());
    }

    @Test
    void everyUpdateReachesAllBackups() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();

        ReplicationManager.Commit commit = replication.update("TRACK-02",
                TrackStateReplica.TrackStatus.occupied("TRAIN-104"), false);

        assertEquals(2, commit.version());
        assertEquals(java.util.List.of("STATION-01", "STATION-02"), commit.acknowledgedBy());
        assertTrue(replication.verifyConsistency());
        assertEquals(net.s4.getReplica().checksum(), net.s1.getReplica().checksum());
        assertEquals(net.s4.getReplica().checksum(), net.s2.getReplica().checksum());
    }

    @Test
    void checksumChangesWhenTheStateChanges() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();
        String before = net.s4.getReplica().checksum();
        replication.update("TRACK-01", TrackStateReplica.TrackStatus.occupied("TRAIN-7"), false);
        assertNotEquals(before, net.s4.getReplica().checksum());
    }

    @Test
    void conflictingArrivalIsRejected() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();

        // TRACK-05 is occupied by TRAIN-099 in the initial state
        ReplicationManager.TrainCommit commit =
                replication.commitTrainMovement(TestNetwork.arrival(1, "TRAIN-500", "TRACK-05"));
        assertFalse(commit.accepted());
        assertEquals(1, net.s4.getReplica().getVersion());
    }

    @Test
    void staleReplicaCatchesUpByStateTransfer() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();

        net.s2.failNode();
        replication.update("TRACK-01", TrackStateReplica.TrackStatus.occupied("TRAIN-11"), false);
        replication.update("TRACK-03", TrackStateReplica.TrackStatus.occupied("TRAIN-12"), false);
        assertEquals(1, net.s2.getReplica().getVersion());

        net.s2.recoverNode();
        replication.resynchronize(net.s2);
        assertEquals(3, net.s2.getReplica().getVersion());
        assertTrue(replication.verifyConsistency());
    }
}
