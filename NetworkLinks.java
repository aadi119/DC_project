import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The communication links between stations, used to simulate a NETWORK
 * PARTITION: a cut link drops every message between its two stations, in
 * both directions, while both stations keep running.
 *
 * A partition is harder than a crash: a station cut off from the others
 * looks dead to them, but it is alive and may still believe it is in
 * charge (split brain). Term numbers in ReplicationManager deal with that.
 */
public final class NetworkLinks {

    private final Set<String> cut = new TreeSet<>();

    private static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    public synchronized boolean connected(String a, String b) {
        return a.equals(b) || !cut.contains(key(a, b));
    }

    public synchronized void cut(String a, String b) {
        if (!a.equals(b)) {
            cut.add(key(a, b));
        }
    }

    public synchronized void restore(String a, String b) {
        cut.remove(key(a, b));
    }

    /** Cuts every link of one station: it is alone in its own partition. */
    public synchronized void isolate(String station, List<String> all) {
        for (String other : all) {
            cut(station, other);
        }
    }

    public synchronized void healAll() {
        cut.clear();
    }

    public synchronized boolean isPartitioned() {
        return !cut.isEmpty();
    }

    /** Cut links as [a, b] pairs. */
    public synchronized List<List<String>> cutLinks() {
        List<List<String>> links = new ArrayList<>();
        for (String k : cut) {
            String[] parts = k.split("\\|");
            links.add(List.of(parts[0], parts[1]));
        }
        return links;
    }
}
