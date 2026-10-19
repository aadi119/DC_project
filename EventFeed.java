import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Live feed of everything that happens in the railway system, used by the
 * web dashboard.
 *
 *   kind "station" → a station event with its physical time and Lamport
 *                    timestamp (every RailwayStation.printEvent call)
 *   kind "console" → a line printed to the terminal (captured by the server)
 *
 * Entries get increasing sequence numbers, so a client can ask for
 * "everything after #N". Only the most recent entries are kept.
 */
public final class EventFeed {

    public record Entry(long seq, String kind, String time, String station,
                        Integer lamport, String text) {
    }

    private static final int CAPACITY = 4000;

    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private static long nextSeq = 1;

    private EventFeed() {
    }

    public static synchronized void station(String time, String station,
                                            int lamport, String message) {
        add(new Entry(nextSeq++, "station", time, station, lamport, message));
    }

    public static synchronized void console(String line) {
        add(new Entry(nextSeq++, "console", null, null, null, line));
    }

    private static void add(Entry entry) {
        entries.addLast(entry);
        if (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
    }

    /** Entries with a sequence number greater than seq, oldest first. */
    public static synchronized List<Entry> since(long seq) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.seq() > seq) {
                result.add(entry);
            }
        }
        return result;
    }

    public static synchronized long lastSeq() {
        return nextSeq - 1;
    }
}
