import java.util.ArrayList;
import java.util.List;

/**
 * EXPERIMENT 3 - physical clock synchronization algorithms.
 *
 * CRISTIAN'S ALGORITHM (external synchronization)
 *   A client asks an accurate time server for the time.
 *     T0 = client clock when the request is sent
 *     Ts = server clock when it answers
 *     T1 = client clock when the reply arrives
 *   The reply took about half the round trip, so the client sets its clock
 *   to Ts + (T1 - T0) / 2. The error is at most ± (T1 - T0) / 2.
 *
 * BERKELEY ALGORITHM (internal synchronization)
 *   A master polls every station's clock and estimates each offset
 *   (correcting for half the round trip). Clocks that are too far off are
 *   left out of the average (fault tolerance). The master computes the
 *   average offset and tells every station how much to adjust, so all
 *   clocks agree with each other (not necessarily with true time).
 *
 * Messages travel over a simulated network: each leg waits a real delay,
 * so the round-trip times below are measured, not assumed.
 */
public final class ClockSync {

    private ClockSync() {
    }

    public record CristianResult(String client, String server,
                                 long t0, long serverTime, long t1,
                                 long roundTripMs, long newTime, double adjustmentMs,
                                 double errorBoundMs, double offsetBeforeMs,
                                 double offsetAfterMs) {
    }

    public record BerkeleyReading(String station, long reading, long roundTripMs,
                                  double estimatedOffsetMs, boolean included,
                                  double adjustmentMs, double offsetBeforeMs,
                                  double offsetAfterMs) {
    }

    public record BerkeleyResult(String master, double averageOffsetMs, double toleranceMs,
                                 List<BerkeleyReading> readings, List<String> unreachable,
                                 double skewBeforeMs, double skewAfterMs) {
    }

    private static void networkDelay(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================
    // CRISTIAN
    // =========================================================

    public static CristianResult cristian(RailwayStation client, String serverName,
                                          PhysicalClock serverClock,
                                          long requestDelayMs, long replyDelayMs) {

        PhysicalClock clock = client.getPhysicalClock();
        double offsetBefore = clock.currentOffsetMs();

        long t0 = clock.now();                 // request leaves the client
        networkDelay(requestDelayMs);
        long serverTime = serverClock.now();   // server reads its clock and replies
        networkDelay(replyDelayMs);
        long t1 = clock.now();                 // reply arrives

        long roundTrip = t1 - t0;
        long newTime = serverTime + roundTrip / 2;
        double adjustment = newTime - t1;
        clock.adjust(adjustment);

        return new CristianResult(client.getStationName(), serverName, t0, serverTime, t1,
                roundTrip, newTime, adjustment, roundTrip / 2.0, offsetBefore,
                clock.currentOffsetMs());
    }

    // =========================================================
    // BERKELEY
    // =========================================================

    public static BerkeleyResult berkeley(RailwayStation master, List<RailwayStation> stations,
                                          double toleranceMs, long legDelayMs) {

        PhysicalClock masterClock = master.getPhysicalClock();
        List<RailwayStation> polled = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        List<Long> readings = new ArrayList<>();
        List<Long> roundTrips = new ArrayList<>();
        List<Double> estimates = new ArrayList<>();

        double skewBefore = skew(stations);

        // 1. Poll every station's clock (the master reads its own clock too)
        for (RailwayStation station : stations) {
            if (!station.isActive()) {
                unreachable.add(station.getStationName());
                continue;
            }
            if (station == master) {
                polled.add(station);
                readings.add(masterClock.now());
                roundTrips.add(0L);
                estimates.add(0.0);
                continue;
            }
            long sent = masterClock.now();
            networkDelay(legDelayMs);
            long reading = station.getPhysicalClock().now();
            networkDelay(legDelayMs);
            long received = masterClock.now();

            long roundTrip = received - sent;
            // The station's clock at "received" is about reading + RTT/2
            double estimatedOffset = reading + roundTrip / 2.0 - received;

            polled.add(station);
            readings.add(reading);
            roundTrips.add(roundTrip);
            estimates.add(estimatedOffset);
        }

        // 2. Fault-tolerant average: ignore clocks too far from the master
        double sum = 0;
        int included = 0;
        for (double estimate : estimates) {
            if (Math.abs(estimate) <= toleranceMs) {
                sum += estimate;
                included++;
            }
        }
        double average = included == 0 ? 0 : sum / included;

        // 3. Send every station its own correction: average - its offset
        List<BerkeleyReading> result = new ArrayList<>();
        for (int i = 0; i < polled.size(); i++) {
            RailwayStation station = polled.get(i);
            double estimate = estimates.get(i);
            double adjustment = average - estimate;
            double before = station.getPhysicalClock().currentOffsetMs();
            station.getPhysicalClock().adjust(adjustment);
            result.add(new BerkeleyReading(station.getStationName(), readings.get(i),
                    roundTrips.get(i), estimate, Math.abs(estimate) <= toleranceMs, adjustment,
                    before, station.getPhysicalClock().currentOffsetMs()));
        }

        return new BerkeleyResult(master.getStationName(), average, toleranceMs, result,
                unreachable, skewBefore, skew(stations));
    }

    /** Largest difference between any two running clocks (checked against true time). */
    public static double skew(List<RailwayStation> stations) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (RailwayStation station : stations) {
            if (station.isActive()) {
                double offset = station.getPhysicalClock().currentOffsetMs();
                min = Math.min(min, offset);
                max = Math.max(max, offset);
            }
        }
        return min > max ? 0 : max - min;
    }
}
