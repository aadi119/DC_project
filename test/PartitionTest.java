import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Network partitions: terms stop a deposed primary from overwriting data. */
class PartitionTest {

    @Test
    void replicaRejectsAnUpdateFromAnOlderTerm() {
        TrackStateReplica replica = new TrackStateReplica();
        replica.observeTerm(2);
        TrackStateReplica.Update old = new TrackStateReplica.Update(1, "TRACK-01",
                TrackStateReplica.TrackStatus.clear(), "STATION-04", 1, null, 1);
        assertThrows(TrackStateReplica.StaleTermException.class, () -> replica.applyUpdate(old));
        assertEquals(0, replica.getVersion());
    }

    @Test
    void isolatedPrimaryIsReplacedAndItsStaleWriteIsRejected() throws InterruptedException {
        TestNetwork net = new TestNetwork();
        NetworkLinks links = new NetworkLinks();
        net.all.forEach(s -> s.setLinks(links));
        ReplicationManager replication = net.replicated();
        assertEquals(1, replication.getTerm());

        // STATION-04 (primary) is cut off but keeps running
        links.isolate("STATION-04", List.of("STATION-01", "STATION-02", "STATION-03"));
        assertTrue(new FailureDetector(3, 0).monitor(net.s1, net.s4, 5));
        RailwayStation newPrimary = replication.failover(net.all);

        assertEquals("STATION-02", newPrimary.getStationName());
        assertEquals(2, replication.getTerm());
        assertEquals(net.s4, replication.getDeposed());
        assertEquals(1, net.s4.getReplica().getTerm());   // it never heard of term 2

        links.healAll();
        ReplicationManager.StaleWrite write = replication.staleWrite("TRACK-01",
                TrackStateReplica.TrackStatus.occupied("TRAIN-666"), net.all);

        assertTrue(write.steppedDown());
        assertEquals(List.of("STATION-01", "STATION-02", "STATION-03"), write.rejectedBy());
        assertNull(replication.getDeposed());
        // the stale write is gone everywhere, including on the old primary
        assertFalse(net.s4.getReplica().get("TRACK-01").isOccupied());
        assertEquals(2, net.s4.getReplica().getTerm());
        assertTrue(replication.getBackups().contains(net.s4));
        assertTrue(replication.verifyConsistency());
    }

    @Test
    void cutLinkDropsHeartbeatsInBothDirections() {
        TestNetwork net = new TestNetwork();
        NetworkLinks links = new NetworkLinks();
        net.all.forEach(s -> s.setLinks(links));
        links.cut("STATION-01", "STATION-02");
        assertFalse(net.s2.answerHeartbeat(1, net.s1));
        assertFalse(net.s1.answerHeartbeat(1, net.s2));
        assertTrue(net.s3.answerHeartbeat(1, net.s1));
    }
}
