import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Experiment 8: failure detection, failover and idempotent retries. */
class FaultToleranceTest {

    @Test
    void liveStationIsNeverDeclaredFailed() throws InterruptedException {
        TestNetwork net = new TestNetwork();
        FailureDetector detector = new FailureDetector(3, 0);
        assertFalse(detector.monitor(net.s1, net.s4, 5));
    }

    @Test
    void crashedStationIsDeclaredFailedAfterExactlyThreeMisses() throws InterruptedException {
        TestNetwork net = new TestNetwork();
        net.s4.failNode();

        // two rounds are not enough: a missed reply may only be a delay
        assertFalse(new FailureDetector(3, 0).monitor(net.s1, net.s4, 2));
        // the third consecutive miss declares the failure
        assertTrue(new FailureDetector(3, 0).monitor(net.s1, net.s4, 3));
    }

    @Test
    void failoverPromotesTheUpToDateBackupWithTheHighestId() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();
        replication.update("TRACK-01", TrackStateReplica.TrackStatus.occupied("TRAIN-1"), false);

        net.s4.failNode();
        RailwayStation newPrimary = replication.failover(net.all);

        // STATION-03 has the highest live ID but holds no replica → not eligible
        assertEquals("STATION-02", newPrimary.getStationName());
        assertEquals(2, newPrimary.getReplica().getVersion());
        assertTrue(newPrimary.isCoordinator());
    }

    @Test
    void failoverNeverPromotesAStaleReplica() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();

        net.s2.failNode();   // STATION-02 misses the next update
        replication.update("TRACK-01", TrackStateReplica.TrackStatus.occupied("TRAIN-1"), false);
        net.s2.recoverNode();   // back, but still at version 1

        net.s4.failNode();
        RailwayStation newPrimary = replication.failover(net.all);

        assertNotEquals("STATION-02", newPrimary.getStationName());
        assertEquals("STATION-01", newPrimary.getStationName());
        assertEquals(2, newPrimary.getReplica().getVersion());
    }

    @Test
    void failoverIsRefusedWhenNoReplicaIsUpToDate() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();
        net.s1.failNode();
        net.s2.failNode();
        net.s4.failNode();
        assertThrows(IllegalStateException.class, () -> replication.failover(net.all));
    }

    @Test
    void retriedRequestIsNotAppliedTwice() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();
        TrainRequest departure = TestNetwork.departure(1, "TRAIN-099", "TRACK-05");

        // the primary commits, then crashes before the reply reaches the client
        replication.injectCrashBeforeNextReply();
        assertThrows(ReplicationManager.PrimaryUnavailableException.class,
                () -> replication.handleClientRequest("REQ-1", departure));
        long committedVersion = net.s1.getReplica().getVersion();

        replication.failover(net.all);

        // the client retries with the same ID on the new primary
        ReplicationManager.ClientReply retry = replication.handleClientRequest("REQ-1", departure);
        assertTrue(retry.duplicate());
        assertEquals(committedVersion, retry.version());
        assertEquals(committedVersion, replication.getPrimary().getReplica().getVersion());
    }

    @Test
    void clientRetriesUntilEveryRequestIsCommittedExactlyOnce() {
        TestNetwork net = new TestNetwork();
        ReplicationManager replication = net.replicated();
        TrainControlClient client = new TrainControlClient(replication);

        replication.injectCrashBeforeNextReply();
        client.send("REQ-1", TestNetwork.departure(1, "TRAIN-099", "TRACK-05"));
        assertEquals(1, client.getPendingCount());

        replication.failover(net.all);
        client.retryPending();

        assertEquals(0, client.getPendingCount());
        assertEquals(1, client.getDuplicatesSuppressed());
        assertEquals(client.getSent(), client.getCommitted());
    }
}
