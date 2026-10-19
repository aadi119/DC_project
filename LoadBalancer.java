import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXPERIMENT 6 - Load Balancing.
 *
 * Health-aware Round-Robin. Stations form a fixed circular list
 * (STATION-01 → 02 → 03 → 04 → 01 ...). A pointer marks whose turn is
 * next. For each request the balancer walks the circle from the pointer
 * and picks the first station that is
 *
 *   - ACTIVE        (a failed station cannot process trains), and
 *   - NOT OVERLOADED.
 *
 * The pointer then moves to the slot after the chosen station, so the
 * healthy stations keep sharing requests evenly and skipped stations do
 * not shift the rotation.
 */
public class LoadBalancer {

    /** Routing decision for one request; station is null if none was available. */
    public record Decision(TrainRequest request,
                           RailwayStation station,
                           List<String> skipped) {

        public boolean assigned() {
            return station != null;
        }
    }

    private final List<RailwayStation> pool;
    private final Map<RailwayStation, Integer> assignments = new LinkedHashMap<>();
    private int nextIndex = 0;

    public LoadBalancer(List<RailwayStation> stations) {

        List<RailwayStation> ordered = new ArrayList<>(stations);
        ordered.sort(Comparator.comparingInt(RailwayStation::getStationId));
        this.pool = List.copyOf(ordered);

        for (RailwayStation station : pool) {
            assignments.put(station, 0);
        }
    }

    public List<RailwayStation> getPool() {
        return pool;
    }

    /** Why a station cannot take requests right now, or null if it can. */
    public static String unavailabilityReason(RailwayStation station) {

        if (!station.isActive()) {
            return "FAILED";
        }
        if (station.isOverloaded()) {
            return "OVERLOADED";
        }
        return null;
    }

    /** Picks the station for one request (synchronized: one routing decision at a time). */
    public synchronized Decision route(TrainRequest request) {

        List<String> skipped = new ArrayList<>();

        for (int step = 0; step < pool.size(); step++) {

            int index = (nextIndex + step) % pool.size();
            RailwayStation candidate = pool.get(index);
            String reason = unavailabilityReason(candidate);

            if (reason == null) {
                nextIndex = (index + 1) % pool.size();
                assignments.merge(candidate, 1, Integer::sum);
                return new Decision(request, candidate, skipped);
            }

            skipped.add(candidate.getStationName() + " [" + reason + "]");
        }

        // Every station is failed or overloaded
        return new Decision(request, null, skipped);
    }

    /** Station whose turn is next (before health checks). */
    public synchronized String nextInRotation() {
        return pool.get(nextIndex).getStationName();
    }

    /** Requests assigned per station, in pool order. */
    public synchronized Map<String, Integer> getAssignmentCounts() {

        Map<String, Integer> counts = new LinkedHashMap<>();

        for (Map.Entry<RailwayStation, Integer> entry : assignments.entrySet()) {
            counts.put(entry.getKey().getStationName(), entry.getValue());
        }

        return counts;
    }
}
