import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EXPERIMENT 2 - multithreading in a distributed system.
 *
 *   PART 1  A station runs its activities (signals, heartbeats, trains) on
 *           three threads at the same time.
 *   PART 2  A multithreaded station server: a pool of worker threads serves
 *           many client requests concurrently; the shared platform table is
 *           protected by synchronization so two trains never get the same
 *           platform.
 *   PART 3  Why synchronization matters: a shared counter updated without a
 *           lock loses updates (race condition); a synchronized and an
 *           atomic counter do not.
 */
public class RailwayMultithreadingDemo {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {

        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Terminal.banner(
                "EXPERIMENT 2 - MULTITHREADING IN A DISTRIBUTED SYSTEM",
                "Concurrent station activities, a thread-pool server, thread safety"
        );

        stationActivities();
        threadPoolServer();
        raceCondition();

        Terminal.banner("EXPERIMENT 2 COMPLETED");
    }

    private static void log(String text) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] ["
                + Thread.currentThread().getName() + "] " + text);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================
    // PART 1 - THREE ACTIVITIES OF ONE STATION RUN CONCURRENTLY
    // =========================================================

    private static void stationActivities() throws InterruptedException {

        Terminal.section("PART 1: STATION-01 RUNS THREE ACTIVITIES CONCURRENTLY");
        log("Creating Signal-Thread, Heartbeat-Thread and Train-Thread");
        Terminal.blank();

        Thread signal = new Thread(() -> {
            for (String change : new String[] {"RED → GREEN", "GREEN → YELLOW", "YELLOW → RED"}) {
                pause(500);
                log("Signal at STATION-01 changed: " + change);
            }
            log("Signal processing completed");
        }, "Signal-Thread");

        Thread heartbeat = new Thread(() -> {
            for (int i = 1; i <= 4; i++) {
                pause(350);
                log("Heartbeat " + i + " → coordinator STATION-04 : ALIVE");
            }
            log("Heartbeat monitoring completed");
        }, "Heartbeat-Thread");

        Thread train = new Thread(() -> {
            for (String event : new String[] {"TRAIN-101 arriving at Platform 1",
                    "TRAIN-101 departed from Platform 1", "TRAIN-102 arriving at Platform 2"}) {
                pause(450);
                log(event);
            }
            log("Train event processing completed");
        }, "Train-Thread");

        long start = System.nanoTime();
        signal.start();
        heartbeat.start();
        train.start();
        log("All three threads started; main thread waits with join()");

        signal.join();
        heartbeat.join();
        train.join();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        Terminal.blank();
        log("All threads finished in ~" + elapsed + " ms "
                + "(one after another they would need ~4250 ms)");
    }

    // =========================================================
    // PART 2 - MULTITHREADED STATION SERVER (thread pool)
    // =========================================================

    /** Shared state of the server: which train holds which platform. */
    static final class PlatformTable {

        private final Map<Integer, String> platforms = new LinkedHashMap<>();

        PlatformTable(int count) {
            for (int p = 1; p <= count; p++) {
                platforms.put(p, null);
            }
        }

        /**
         * Gives the train its preferred platform if free, else the first free
         * one. synchronized: check-then-assign happens as one atomic step.
         */
        synchronized int assign(String train, int preferred) {
            if (platforms.get(preferred) == null) {
                platforms.put(preferred, train);
                return preferred;
            }
            for (Map.Entry<Integer, String> entry : platforms.entrySet()) {
                if (entry.getValue() == null) {
                    entry.setValue(train);
                    return entry.getKey();
                }
            }
            return -1;
        }

        synchronized Map<Integer, String> snapshot() {
            return new LinkedHashMap<>(platforms);
        }
    }

    record Booking(String train, int preferred, int assigned, String worker, long waitedMs) {
    }

    private static void threadPoolServer() throws Exception {

        Terminal.section("PART 2: STATION SERVER WITH A POOL OF 3 WORKER THREADS");

        PlatformTable table = new PlatformTable(6);
        AtomicInteger workerNumber = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3,
                task -> new Thread(task, "Worker-" + workerNumber.incrementAndGet()));

        String[] trains = {"TRAIN-201", "TRAIN-202", "TRAIN-203", "TRAIN-204", "TRAIN-205", "TRAIN-206"};
        int[] preferred = {1, 1, 2, 3, 2, 4};

        log("6 clients send platform requests at the same moment");
        Terminal.blank();

        // All clients start together so the requests really compete
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Booking>> replies = new ArrayList<>();
        long start = System.nanoTime();

        for (int i = 0; i < trains.length; i++) {
            String train = trains[i];
            int wanted = preferred[i];
            long queuedAt = System.nanoTime();
            replies.add(pool.submit(() -> {
                startSignal.await();
                long waited = (System.nanoTime() - queuedAt) / 1_000_000;
                log("serving " + train + " (wants Platform " + wanted + ")");
                pause(300);   // route check and signal setting
                int platform = table.assign(train, wanted);
                log(platform < 0 ? train + " → no platform free"
                        : train + " → Platform " + platform
                        + (platform != wanted ? "   (Platform " + wanted + " taken)" : ""));
                return new Booking(train, wanted, platform, Thread.currentThread().getName(), waited);
            }));
        }
        startSignal.countDown();

        List<Booking> bookings = new ArrayList<>();
        for (Future<Booking> reply : replies) {
            bookings.add(reply.get());
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        Terminal.blank();
        Terminal.line("Bookings (reply order = request order):");
        for (Booking b : bookings) {
            Terminal.line(String.format("  %s  wanted P%d → got %-4s on %s  (waited %3d ms in the queue)",
                    b.train(), b.preferred(), b.assigned() < 0 ? "none" : "P" + b.assigned(),
                    b.worker(), b.waitedMs()));
        }
        Terminal.blank();
        Terminal.line("Platform table:");
        table.snapshot().forEach((platform, train) ->
                Terminal.line("  Platform " + platform + " : " + (train == null ? "free" : train)));

        long distinct = bookings.stream().filter(b -> b.assigned() > 0)
                .map(Booking::assigned).distinct().count();
        long served = bookings.stream().filter(b -> b.assigned() > 0).count();
        Terminal.blank();
        Terminal.line("6 requests served by 3 threads in ~" + elapsed
                + " ms (a single thread would need ~1800 ms)");
        Terminal.line(distinct == served
                ? "✓ No platform was given to two trains (synchronized assign)"
                : "✗ A platform was double-booked");
        Terminal.line(served < bookings.size()
                ? "⚠ " + (bookings.size() - served) + " train(s) could not get a platform (all platforms in use)"
                : "✓ Every train got a platform");
    }

    // =========================================================
    // PART 3 - RACE CONDITION vs SYNCHRONIZATION
    // =========================================================

    private static int unsafeCount = 0;
    private static int lockedCount = 0;
    private static final Object COUNT_LOCK = new Object();

    private static void raceCondition() throws InterruptedException {

        Terminal.section("PART 3: RACE CONDITION ON A SHARED TRAIN COUNTER");

        int threads = 4;
        int perThread = 250_000;
        AtomicInteger atomicCount = new AtomicInteger();

        Terminal.line(threads + " gate threads each count " + String.format("%,d", perThread)
                + " passing trains into three shared counters:");
        Terminal.line("  unsafe       count++                (no lock)");
        Terminal.line("  synchronized synchronized(lock) { count++ }");
        Terminal.line("  atomic       AtomicInteger.incrementAndGet()");

        List<Thread> gates = new ArrayList<>();
        for (int t = 1; t <= threads; t++) {
            gates.add(new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    unsafeCount++;
                    synchronized (COUNT_LOCK) {
                        lockedCount++;
                    }
                    atomicCount.incrementAndGet();
                }
            }, "Gate-" + t));
        }
        gates.forEach(Thread::start);
        for (Thread gate : gates) {
            gate.join();
        }

        int expected = threads * perThread;
        Terminal.blank();
        Terminal.line(String.format("  Expected     : %,d", expected));
        Terminal.line(String.format("  unsafe       : %,d   %s", unsafeCount,
                unsafeCount == expected ? "(no update lost this run: races are timing dependent)"
                        : "✗ " + String.format("%,d", expected - unsafeCount) + " updates LOST"));
        Terminal.line(String.format("  synchronized : %,d   %s", lockedCount,
                lockedCount == expected ? "✓ correct" : "✗ wrong"));
        Terminal.line(String.format("  atomic       : %,d   %s", atomicCount.get(),
                atomicCount.get() == expected ? "✓ correct" : "✗ wrong"));
        Terminal.blank();
        Terminal.line("count++ is read → add → write; two threads can read the same value");
        Terminal.line("and both write it back, so one increment disappears.");
    }
}
