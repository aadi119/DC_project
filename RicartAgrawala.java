import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed mutual exclusion: the Ricart–Agrawala algorithm.
 *
 * A single-line track section (one track used in both directions) may hold
 * only one train. There is no central lock: the stations agree by messages.
 *
 *   To enter, a station sends REQUEST(timestamp, id) to every other station
 *   and waits until ALL of them have replied.
 *
 *   A station that receives a REQUEST replies at once, unless
 *     - it is inside the section, or
 *     - it wants the section itself and its own request is older
 *       (smaller Lamport timestamp; ties broken by smaller station ID).
 *   In those cases it DEFERS the reply until it leaves the section.
 *
 * Because every pair of competing requests is ordered by (timestamp, id),
 * exactly one of them collects all replies first: never two trains inside.
 *
 * Messages travel over a simulated network with a random delay, each
 * station handles its messages concurrently, and every message updates the
 * stations' Lamport and vector clocks.
 */
public final class RicartAgrawala {

    /** One delivered message, for the replay on the web dashboard. */
    public record Message(String type, String from, String to, int lamport,
                          long sentAtMs, long deliveredAtMs) {
    }

    /** One train's passage through the section. */
    public record Passage(String station, String train, int requestTimestamp,
                          long requestedAtMs, long enteredAtMs, long leftAtMs,
                          List<String> deferredBy) {
    }

    public record Result(String section, List<String> participants, List<Message> messages,
                         List<Passage> passages, int maxInside, boolean mutualExclusion,
                         long elapsedMs) {
    }

    private enum State { RELEASED, WANTED, HELD }

    private final String section;
    private final long holdMs;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private final List<Passage> passages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger maxInside = new AtomicInteger();
    private final Random random = new Random();
    private final ScheduledExecutorService network;
    private final long startNanos = System.nanoTime();

    public RicartAgrawala(String section, List<RailwayStation> participants, long holdMs) {
        this.section = section;
        this.holdMs = holdMs;
        for (RailwayStation station : participants) {
            nodes.put(station.getStationName(), new Node(station));
        }
        AtomicInteger number = new AtomicInteger();
        this.network = Executors.newScheduledThreadPool(4, task -> {
            Thread thread = new Thread(task, "network-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private long now() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Sends a message that arrives after a random network delay. */
    private void send(String type, Node from, Node to, int lamport) {
        long sentAt = now();
        int[] vector = from.station.vectorAt(lamport);
        network.schedule(() -> {
            messages.add(new Message(type, from.name(), to.name(), lamport, sentAt, now()));
            if (type.equals("REQUEST")) {
                to.onRequest(from, lamport, vector);
            } else {
                to.onReply(from, lamport, vector);
            }
        }, 40 + random.nextInt(80), TimeUnit.MILLISECONDS);
    }

    // =========================================================
    // ONE STATION
    // =========================================================

    private final class Node {

        final RailwayStation station;
        State state = State.RELEASED;
        int requestTimestamp;
        CountDownLatch replies;
        final List<Node> deferred = new ArrayList<>();
        final List<String> deferredBy = new ArrayList<>();

        Node(RailwayStation station) {
            this.station = station;
        }

        String name() {
            return station.getStationName();
        }

        /** True if this station's pending request comes before the other one. */
        boolean hasPriorityOver(int otherTimestamp, Node other) {
            return requestTimestamp < otherTimestamp
                    || (requestTimestamp == otherTimestamp
                    && station.getStationId() < other.station.getStationId());
        }

        void enter(String train) throws InterruptedException {

            List<Node> others = new ArrayList<>(nodes.values());
            others.remove(this);
            long requestedAt = now();

            synchronized (this) {
                state = State.WANTED;
                deferredBy.clear();
                replies = new CountDownLatch(others.size());
                requestTimestamp = station.printEvent("MUTEX REQUEST " + section + " for " + train
                        + " → all stations (request time = this L)");
            }
            for (Node other : others) {
                send("REQUEST", this, other, requestTimestamp);
            }

            if (!replies.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(name() + " never received all replies");
            }

            synchronized (this) {
                state = State.HELD;
            }
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            long enteredAt = now();
            station.printEvent("MUTEX ENTER " + section + " : " + train + " is in the section");

            Thread.sleep(holdMs);   // the train runs through the single-line section

            station.printEvent("MUTEX EXIT " + section + " : " + train + " has left the section");
            inside.decrementAndGet();
            long leftAt = now();

            List<Node> release;
            synchronized (this) {
                state = State.RELEASED;
                release = new ArrayList<>(deferred);
                deferred.clear();
            }
            passages.add(new Passage(name(), train, requestTimestamp, requestedAt, enteredAt, leftAt,
                    List.copyOf(deferredBy)));
            for (Node waiting : release) {
                int timestamp = station.printEvent("MUTEX REPLY → " + waiting.name() + " (deferred reply sent)");
                send("REPLY", this, waiting, timestamp);
            }
        }

        synchronized void onRequest(Node from, int timestamp, int[] vector) {
            station.printReceivedEvent(timestamp, vector, "MUTEX REQUEST from " + from.name()
                    + " (msg L=" + timestamp + ")");

            boolean defer = state == State.HELD
                    || (state == State.WANTED && hasPriorityOver(timestamp, from));
            if (defer) {
                deferred.add(from);
                from.noteDeferredBy(name());
                station.printEvent("MUTEX DEFER reply to " + from.name() + " ("
                        + (state == State.HELD ? "I am in the section"
                                : "my request (ts=" + requestTimestamp + ", id " + station.getStationId()
                                        + ") comes before (ts=" + timestamp + ", id "
                                        + from.station.getStationId() + ")") + ")");
            } else {
                int reply = station.printEvent("MUTEX REPLY → " + from.name());
                send("REPLY", this, from, reply);
            }
        }

        synchronized void noteDeferredBy(String stationName) {
            deferredBy.add(stationName);
        }

        void onReply(Node from, int timestamp, int[] vector) {
            long left;
            synchronized (this) {
                station.printReceivedEvent(timestamp, vector, "MUTEX REPLY from " + from.name()
                        + " (msg L=" + timestamp + ")");
                left = replies.getCount() - 1;
            }
            replies.countDown();
            if (left == 0) {
                station.printEvent("MUTEX all replies received → may enter " + section);
            }
        }
    }

    // =========================================================
    // RUN SEVERAL REQUESTS AT THE SAME TIME
    // =========================================================

    /**
     * Each entry of requests is {station, train}. All requests are issued
     * at the same moment from their own threads.
     */
    public Result contend(List<String[]> requests) throws InterruptedException {

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (String[] request : requests) {
            Node node = nodes.get(request[0]);
            if (node == null) {
                throw new IllegalArgumentException(request[0] + " is not taking part");
            }
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    node.enter(request[1]);
                } catch (Throwable e) {
                    errors.add(e);
                }
            }, request[0] + "-mutex");
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
        }
        // deferred replies to finished requests may still be in flight
        network.shutdown();
        network.awaitTermination(2, TimeUnit.SECONDS);

        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.get(0).getMessage(), errors.get(0));
        }

        List<Passage> ordered = new ArrayList<>(passages);
        ordered.sort((a, b) -> Long.compare(a.enteredAtMs(), b.enteredAtMs()));
        List<Message> delivered = new ArrayList<>(messages);
        delivered.sort((a, b) -> Long.compare(a.sentAtMs(), b.sentAtMs()));

        return new Result(section, List.copyOf(nodes.keySet()), delivered, ordered,
                maxInside.get(), maxInside.get() <= 1 && overlapFree(ordered), now());
    }

    private static boolean overlapFree(List<Passage> ordered) {
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).enteredAtMs() < ordered.get(i - 1).leftAtMs()) {
                return false;
            }
        }
        return true;
    }

    // =========================================================
    // CONSOLE DEMONSTRATION
    // =========================================================

    public static void main(String[] args) throws Exception {

        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Terminal.banner("DISTRIBUTED MUTUAL EXCLUSION (Ricart–Agrawala)",
                "Single-line section HILLCREST TUNNEL: one train at a time");

        List<RailwayStation> stations = List.of(
                new RailwayStation("STATION-01", 1), new RailwayStation("STATION-02", 2),
                new RailwayStation("STATION-03", 3), new RailwayStation("STATION-04", 4));

        Terminal.section("THREE STATIONS REQUEST THE SECTION AT THE SAME MOMENT");
        RicartAgrawala mutex = new RicartAgrawala("HILLCREST-TUNNEL", stations, 400);
        Result result = mutex.contend(List.of(
                new String[] {"STATION-01", "TRAIN-401"},
                new String[] {"STATION-03", "TRAIN-402"},
                new String[] {"STATION-04", "TRAIN-403"}));

        Terminal.section("ORDER OF PASSAGE");
        for (Passage p : result.passages()) {
            Terminal.line(String.format("  %s  %-10s request ts=%-3d waited %4d ms  in section %4d → %4d ms%s",
                    p.station(), p.train(), p.requestTimestamp(), p.enteredAtMs() - p.requestedAtMs(),
                    p.enteredAtMs(), p.leftAtMs(),
                    p.deferredBy().isEmpty() ? "" : "   (deferred by " + String.join(", ", p.deferredBy()) + ")"));
        }
        Terminal.blank();
        Terminal.line("Messages exchanged : " + result.messages().size()
                + " (2 × (N-1) per request = " + 2 * 3 * 3 + ")");
        Terminal.line("Most trains inside : " + result.maxInside());
        Terminal.line(result.mutualExclusion()
                ? "✓ MUTUAL EXCLUSION HELD: never more than one train in the section"
                : "✗ MUTUAL EXCLUSION VIOLATED");
        Terminal.banner("MUTUAL EXCLUSION DEMONSTRATION COMPLETED");
    }
}
