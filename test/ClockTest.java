import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Experiment 3: Lamport clocks and physical clock synchronization. */
class ClockTest {

    @Test
    void lamportSendAndReceiveRules() {
        TestNetwork net = new TestNetwork();
        int send = net.s1.sendLamportTimestamp();      // 0 → 1
        assertEquals(1, send);

        net.s2.sendLamportTimestamp();                 // 0 → 1
        net.s2.sendLamportTimestamp();                 // 1 → 2
        net.s2.sendLamportTimestamp();                 // 2 → 3
        assertEquals(4, net.s2.receiveLamportTimestamp(send));   // max(3, 1) + 1

        int late = 10;
        assertEquals(11, net.s1.receiveLamportTimestamp(late));  // max(1, 10) + 1
    }

    @Test
    void receiveIsAlwaysLaterThanSend() {
        TestNetwork net = new TestNetwork();
        for (int i = 0; i < 20; i++) {
            net.s3.sendLamportTimestamp();
        }
        int send = net.s3.sendLamportTimestamp();
        assertTrue(net.s1.receiveLamportTimestamp(send) > send);
    }

    @Test
    void cristianBringsTheClockWithinTheErrorBound() {
        TestNetwork net = new TestNetwork();
        ClockSync.CristianResult r = ClockSync.cristian(net.s1, "GPS", PhysicalClock.reference(), 10, 10);

        assertTrue(Math.abs(r.offsetBeforeMs()) > 800);
        // after the sync the clock is off by at most half the round trip (+1 ms rounding)
        assertTrue(Math.abs(r.offsetAfterMs()) <= r.errorBoundMs() + 1,
                "offset " + r.offsetAfterMs() + " bound " + r.errorBoundMs());
    }

    @Test
    void berkeleyMakesClocksAgreeAndLeavesOutTheFaultyOne() {
        TestNetwork net = new TestNetwork();
        // STATION-03 starts 3600 ms off: outside the 2000 ms tolerance
        ClockSync.BerkeleyResult r = ClockSync.berkeley(net.s4, net.all, 2000, 5);

        assertTrue(r.skewBeforeMs() > 4000);
        assertTrue(r.skewAfterMs() < 30, "skew after " + r.skewAfterMs());
        ClockSync.BerkeleyReading s3 = r.readings().stream()
                .filter(x -> x.station().equals("STATION-03")).findFirst().orElseThrow();
        assertFalse(s3.included());
        assertEquals(4, r.readings().size());
    }

    @Test
    void berkeleySkipsStationsThatDoNotAnswer() {
        TestNetwork net = new TestNetwork();
        net.s2.failNode();
        ClockSync.BerkeleyResult r = ClockSync.berkeley(net.s4, net.all, 2000, 5);
        assertEquals(java.util.List.of("STATION-02"), r.unreachable());
        assertEquals(3, r.readings().size());
    }

    @Test
    void vectorClocksSeparateCausalityFromConcurrency() {
        TestNetwork net = new TestNetwork();
        int a = net.s1.sendLamportTimestamp();                       // S1 local
        int send = net.s1.sendLamportTimestamp();                    // S1 send
        int c = net.s3.sendLamportTimestamp();                       // S3 local, unrelated
        int recv = net.s2.receiveLamportTimestamp(send, net.s1.vectorAt(send));

        assertEquals("[2,1,0,0]", RailwayStation.formatVector(net.s2.vectorAt(recv)));
        assertEquals("[0,0,1,0]", RailwayStation.formatVector(net.s3.vectorAt(c)));
        // a → receive: every entry ≤ and not equal
        int[] va = net.s1.vectorAt(a);
        int[] vr = net.s2.vectorAt(recv);
        for (int i = 0; i < va.length; i++) {
            assertTrue(va[i] <= vr[i]);
        }
        // S3's event is concurrent with S1's: neither vector is ≤ the other
        int[] vc = net.s3.vectorAt(c);
        assertTrue(vc[2] > va[2] && va[0] > vc[0]);
    }
}
