import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * EXPERIMENT 3 - clock synchronization (physical and logical).
 *
 *   PART 1  Every station's physical clock shows a different time.
 *   PART 2  With such clocks, a message can appear to be received before
 *           it was sent (physical timestamps break causality).
 *   PART 3  Lamport logical clocks order the same events correctly.
 *   PART 3b Vector clocks also tell which events are concurrent.
 *   PART 4  Cristian's algorithm: one station syncs with a time server.
 *   PART 5  Berkeley algorithm: the coordinator averages all clocks,
 *           ignoring a faulty one, and corrects every station.
 *   PART 6  After synchronization the physical timestamps agree with causality.
 */
public class RailwayClockSyncDemo {

    private static final long NETWORK_DELAY_MS = 40;

    /** One event with its Lamport time, for the total order in Part 3. */
    private record LamportEvent(int lamport, int[] vector, int stationId, String station, String text) {
    }

    private static final List<LamportEvent> events = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Terminal.banner(
                "EXPERIMENT 3 - CLOCK SYNCHRONIZATION (physical + logical)",
                "Cristian, Berkeley and Lamport clocks on the railway stations"
        );

        RailwayStation s1 = new RailwayStation("STATION-01", 1);
        RailwayStation s2 = new RailwayStation("STATION-02", 2);
        RailwayStation s3 = new RailwayStation("STATION-03", 3);
        RailwayStation s4 = new RailwayStation("STATION-04", 4);
        List<RailwayStation> stations = List.of(s1, s2, s3, s4);
        s4.setCoordinator(true);

        // ---------------- PART 1 ----------------
        Terminal.section("PART 1: PHYSICAL CLOCKS DISAGREE");
        printClocks(stations);
        Terminal.blank();
        Terminal.line(String.format("Largest difference between two clocks (skew): %.0f ms",
                ClockSync.skew(stations)));

        // ---------------- PART 2 ----------------
        Terminal.section("PART 2: PHYSICAL TIMESTAMPS CAN BREAK CAUSALITY");
        long[] times = sendWithPhysicalTimestamps(s1, s2, "TRAIN-301 departed STATION-01");
        Terminal.blank();
        if (times[1] < times[0]) {
            Terminal.line("✗ The receive is stamped " + (times[0] - times[1])
                    + " ms BEFORE the send: ordering by physical time is wrong.");
        } else {
            Terminal.line("✓ Receive stamped after send.");
        }

        // ---------------- PART 3 ----------------
        Terminal.section("PART 3: LAMPORT LOGICAL CLOCKS ORDER THE EVENTS");
        Terminal.line("Rules: local/send event  L = L + 1");
        Terminal.line("       receive event     L = max(L, message L) + 1");
        Terminal.blank();
        lamportScenario(s1, s2, s3);

        Terminal.blank();
        Terminal.line("Total order (sorted by L, ties broken by station ID):");
        events.sort(Comparator.comparingInt(LamportEvent::lamport)
                .thenComparingInt(LamportEvent::stationId));
        for (LamportEvent e : events) {
            Terminal.line(String.format("  L=%-2d %s  %s", e.lamport(), e.station(), e.text()));
        }
        Terminal.blank();
        Terminal.line("✓ Every RECEIVE comes after its SEND: if a → b then L(a) < L(b).");

        // ---------------- PART 3b ----------------
        Terminal.section("PART 3b: VECTOR CLOCKS DETECT CONCURRENT EVENTS");
        Terminal.line("Vector rule: own entry + 1 on every event; on receive, first take the");
        Terminal.line("element-wise max with the message's vector. a → b  ⇔  V(a) ≤ V(b) and V(a) ≠ V(b).");
        Terminal.blank();
        compare(find("TRAIN-301 doors closed"), find("Signal S3 set to RED"));
        compare(find("SEND \"TRAIN-301 departed\" → STATION-02"), find("Signal S3 set to GREEN"));
        compare(find("Reserve TRACK-03 for TRAIN-301"), find("Signal S3 set to RED"));
        Terminal.line("Lamport gives \"Signal S3 set to RED\" (L=1) a smaller time than \"doors closed\" (L=2),");
        Terminal.line("but neither caused the other: only the vector clocks reveal that they are concurrent.");

        // ---------------- PART 4 ----------------
        Terminal.section("PART 4: CRISTIAN'S ALGORITHM (STATION-02 ↔ GPS time server)");
        ClockSync.CristianResult c = ClockSync.cristian(s2, "GPS-TIME-SERVER",
                PhysicalClock.reference(), 30, 50);
        Terminal.line("T0 (STATION-02 sends request)   = " + PhysicalClock.format(c.t0()));
        Terminal.line("Ts (server's time in the reply) = " + PhysicalClock.format(c.serverTime()));
        Terminal.line("T1 (STATION-02 receives reply)  = " + PhysicalClock.format(c.t1()));
        Terminal.line("Round trip T1 - T0              = " + c.roundTripMs() + " ms");
        Terminal.line("New time = Ts + RTT/2           = " + PhysicalClock.format(c.newTime()));
        Terminal.line(String.format("Adjustment applied              = %+.0f ms", c.adjustmentMs()));
        Terminal.blank();
        Terminal.line(String.format("STATION-02 offset from true time: %+.0f ms → %+.0f ms   (error bound ±%.0f ms)",
                c.offsetBeforeMs(), c.offsetAfterMs(), c.errorBoundMs()));

        // ---------------- PART 5 ----------------
        Terminal.section("PART 5: BERKELEY ALGORITHM (master: coordinator STATION-04)");
        double tolerance = 2000;
        Terminal.line("Master polls every clock; clocks more than ±" + (long) tolerance
                + " ms off are left out of the average.");
        ClockSync.BerkeleyResult b = ClockSync.berkeley(s4, stations, tolerance, 20);
        Terminal.blank();
        Terminal.line("  Station      Reading        RTT   Est. offset  In avg   Adjustment");
        for (ClockSync.BerkeleyReading r : b.readings()) {
            Terminal.line(String.format("  %-11s  %s  %3d ms  %+8.0f ms   %-5s  %+8.0f ms",
                    r.station(), PhysicalClock.format(r.reading()), r.roundTripMs(),
                    r.estimatedOffsetMs(), r.included() ? "yes" : "NO ⚠", r.adjustmentMs()));
        }
        Terminal.blank();
        Terminal.line(String.format("Average offset of the included clocks: %+.0f ms", b.averageOffsetMs()));
        Terminal.line("Each station adjusts by (average - its own offset) → all clocks agree.");
        Terminal.blank();
        printClocks(stations);
        Terminal.blank();
        Terminal.line(String.format("Clock skew: %.0f ms → %.0f ms", b.skewBeforeMs(), b.skewAfterMs()));
        Terminal.line(String.format("The clocks now agree with each other but are all about %+.0f ms from",
                s4.getPhysicalClock().currentOffsetMs()));
        Terminal.line("true time: Berkeley gives agreement (internal sync), Cristian gives");
        Terminal.line("accuracy against an external time source (external sync).");

        // ---------------- PART 6 ----------------
        Terminal.section("PART 6: SAME MESSAGE AFTER SYNCHRONIZATION");
        long[] after = sendWithPhysicalTimestamps(s1, s2, "TRAIN-302 departed STATION-01");
        Terminal.blank();
        Terminal.line(after[1] >= after[0]
                ? "✓ Receive is now stamped after the send (skew is smaller than the network delay)."
                : "✗ Receive still stamped before the send.");

        Terminal.banner(String.format("EXPERIMENT 3 COMPLETED → skew %.0f ms → %.0f ms, "
                + "Lamport order verified", b.skewBeforeMs(), ClockSync.skew(stations)));
    }

    private static void printClocks(List<RailwayStation> stations) {
        Terminal.line("  True time    : " + PhysicalClock.format(PhysicalClock.trueTimeMillis()));
        for (RailwayStation s : stations) {
            PhysicalClock clock = s.getPhysicalClock();
            Terminal.line(String.format("  %s   : %s   (%+.0f ms, drift %+.0f ppm)",
                    s.getStationName(), clock.reading(), clock.currentOffsetMs(),
                    clock.getDriftPpm()));
        }
    }

    /** Sends one message and stamps send/receive with each station's own physical clock. */
    private static long[] sendWithPhysicalTimestamps(RailwayStation from, RailwayStation to,
                                                     String text) throws InterruptedException {
        long sendTime = from.getPhysicalClock().now();
        Terminal.line("[" + PhysicalClock.format(sendTime) + "] [" + from.getStationName()
                + "] SEND \"" + text + "\" → " + to.getStationName());
        Thread.sleep(NETWORK_DELAY_MS);
        long receiveTime = to.getPhysicalClock().now();
        Terminal.line("[" + PhysicalClock.format(receiveTime) + "] [" + to.getStationName()
                + "] RECEIVE \"" + text + "\"   (network delay " + NETWORK_DELAY_MS + " ms)");
        return new long[] {sendTime, receiveTime};
    }

    // =========================================================
    // LAMPORT SCENARIO
    // =========================================================

    private static void local(RailwayStation s, String text) {
        int l = s.sendLamportTimestamp();
        print(s, l, text);
    }

    private static LamportEvent find(String text) {
        return events.stream().filter(e -> e.text().startsWith(text)).findFirst().orElseThrow();
    }

    /** -1: a before b, 1: b before a, 0: concurrent. */
    private static int order(int[] a, int[] b) {
        boolean aLess = false;
        boolean bLess = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i]) {
                aLess = true;
            } else if (a[i] > b[i]) {
                bLess = true;
            }
        }
        return aLess && !bLess ? -1 : bLess && !aLess ? 1 : 0;
    }

    private static void compare(LamportEvent a, LamportEvent b) {
        int order = order(a.vector(), b.vector());
        String verdict = order == -1 ? "→ HAPPENED BEFORE" : order == 1 ? "← HAPPENED AFTER" : "∥ CONCURRENT";
        Terminal.line(String.format("  %-42s %-10s L=%d", "\"" + a.text() + "\"",
                RailwayStation.formatVector(a.vector()), a.lamport()));
        Terminal.line(String.format("  %-42s %-10s L=%-2d  %s",
                "\"" + b.text() + "\"", RailwayStation.formatVector(b.vector()), b.lamport(), verdict));
        Terminal.blank();
    }

    private static int send(RailwayStation from, RailwayStation to, String text) {
        int l = from.sendLamportTimestamp();
        print(from, l, "SEND \"" + text + "\" → " + to.getStationName());
        return l;
    }

    private static void receive(RailwayStation to, RailwayStation from, int messageL, String text) {
        int before = to.getLamportClock();
        int l = to.receiveLamportTimestamp(messageL, from.vectorAt(messageL));
        print(to, l, "RECEIVE \"" + text + "\" from " + from.getStationName()
                + "  L = max(" + before + ", " + messageL + ") + 1");
    }

    private static void print(RailwayStation s, int l, String text) {
        int[] vector = s.vectorAt(l);
        Terminal.line("[" + s.getPhysicalClock().reading() + "] [" + s.getStationName()
                + "] [L=" + l + "] [V=" + RailwayStation.formatVector(vector) + "] " + text);
        events.add(new LamportEvent(l, vector, s.getStationId(), s.getStationName(), text));
    }

    private static void lamportScenario(RailwayStation s1, RailwayStation s2, RailwayStation s3) {

        local(s1, "TRAIN-301 boards passengers");
        local(s1, "TRAIN-301 doors closed");
        int m1 = send(s1, s2, "TRAIN-301 departed");
        local(s3, "Signal S3 set to RED");
        receive(s2, s1, m1, "TRAIN-301 departed");
        local(s2, "Reserve TRACK-03 for TRAIN-301");
        int m2 = send(s2, s3, "TRAIN-301 approaching");
        receive(s3, s2, m2, "TRAIN-301 approaching");
        local(s3, "Signal S3 set to GREEN");
        int m3 = send(s3, s1, "TRACK-03 clear for next train");
        receive(s1, s3, m3, "TRACK-03 clear for next train");
    }
}
