import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Ricart–Agrawala: one train at a time in the single-line section. */
class MutualExclusionTest {

    @RepeatedTest(5)
    void allFourStationsAtOnceStillEnterOneAtATime() throws InterruptedException {
        TestNetwork net = new TestNetwork();
        RicartAgrawala.Result r = new RicartAgrawala("TUNNEL", net.all, 50).contend(List.of(
                new String[] {"STATION-01", "TRAIN-1"}, new String[] {"STATION-02", "TRAIN-2"},
                new String[] {"STATION-03", "TRAIN-3"}, new String[] {"STATION-04", "TRAIN-4"}));

        assertTrue(r.mutualExclusion());
        assertEquals(1, r.maxInside());
        assertEquals(4, r.passages().size());
        // every request costs 2(N-1) messages
        assertEquals(4 * 2 * 3, r.messages().size());
    }

    @Test
    void equalTimestampsAreOrderedByStationId() throws InterruptedException {
        TestNetwork net = new TestNetwork();
        // fresh clocks: both requests carry Lamport time 1, so the lower ID wins
        RicartAgrawala.Result r = new RicartAgrawala("TUNNEL", net.all, 50).contend(List.of(
                new String[] {"STATION-04", "TRAIN-4"}, new String[] {"STATION-02", "TRAIN-2"}));
        assertEquals("STATION-02", r.passages().get(0).station());
        assertEquals(List.of("STATION-02"), r.passages().get(1).deferredBy());
    }
}
