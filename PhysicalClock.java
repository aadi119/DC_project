import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * EXPERIMENT 3 - the physical (hardware) clock of one station.
 *
 * Real computer clocks are never exactly right: each has an OFFSET from
 * true time and runs slightly fast or slow (DRIFT, in parts per million),
 * so the offset changes over time. This class models such a clock on top
 * of the machine's clock: now() = true time + offset + drift × elapsed.
 *
 * Clock synchronization algorithms (Cristian, Berkeley) only read now()
 * and call adjust(); they never look at the hidden offset.
 */
public final class PhysicalClock {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final double driftPpm;
    private final long createdAtNanos = System.nanoTime();
    private volatile double offsetMs;

    public PhysicalClock(double initialOffsetMs, double driftPpm) {
        this.offsetMs = initialOffsetMs;
        this.driftPpm = driftPpm;
    }

    /** A perfect clock (offset 0, no drift), e.g. a GPS time source. */
    public static PhysicalClock reference() {
        return new PhysicalClock(0, 0);
    }

    /** True time, as the machine knows it. */
    public static long trueTimeMillis() {
        return System.currentTimeMillis();
    }

    /** This clock's current reading in milliseconds since the epoch. */
    public long now() {
        return trueTimeMillis() + Math.round(currentOffsetMs());
    }

    /** How far this clock is from true time right now (ms). Hidden from the algorithms. */
    public double currentOffsetMs() {
        double elapsedMs = (System.nanoTime() - createdAtNanos) / 1e6;
        return offsetMs + elapsedMs * driftPpm / 1e6;
    }

    /** Moves the clock forward (positive) or back (negative). */
    public void adjust(double deltaMs) {
        offsetMs += deltaMs;
    }

    /** Sets the offset from true time directly (used to disturb clocks in a demo). */
    public void setOffset(double newOffsetMs) {
        adjust(newOffsetMs - currentOffsetMs());
    }

    public double getDriftPpm() {
        return driftPpm;
    }

    public static String format(long epochMillis) {
        return FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    public String reading() {
        return format(now());
    }
}
