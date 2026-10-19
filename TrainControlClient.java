import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXPERIMENT 8 - Client that sends train movement requests to the primary.
 *
 * Fault-tolerance behaviour on the client side:
 *   - every request has a unique request ID;
 *   - if the primary does not reply (crash / timeout), the request is kept
 *     and re-sent later with the SAME ID;
 *   - the primary uses the ID to detect a retry of an already committed
 *     request, so a retry can never apply the same change twice.
 *
 * The client also counts what happened, so the final fault-tolerance
 * summary is computed from real outcomes.
 */
public class TrainControlClient {

    /** One attempt to send a request, as shown on the web dashboard. */
    public record Attempt(String requestId, int attempt, String trainId, String movement,
                          String trackId, String primary, String status,
                          Long version, String detail) {
    }

    private final ReplicationManager replication;
    private final List<Attempt> history = new ArrayList<>();
    private final Map<String, Integer> attemptsPerRequest = new HashMap<>();
    private final Map<String, TrainRequest> awaitingRetry = new LinkedHashMap<>();

    private int sent = 0;
    private int committed = 0;
    private int rejected = 0;
    private int timeouts = 0;
    private int retries = 0;
    private int duplicatesSuppressed = 0;

    public TrainControlClient(ReplicationManager replication) {
        this.replication = replication;
    }

    /** Sends a new request to the current primary. */
    public void send(String requestId, TrainRequest request) {
        sent++;
        Terminal.line("CLIENT → " + requestId + "  " + request
                + "  sent to primary " + replication.getPrimary().getStationName());
        attempt(requestId, request);
    }

    /** Re-sends every request that did not get a reply. */
    public void retryPending() {

        for (Map.Entry<String, TrainRequest> entry : List.copyOf(awaitingRetry.entrySet())) {
            retries++;
            awaitingRetry.remove(entry.getKey());
            Terminal.line("CLIENT → RETRY " + entry.getKey() + "  " + entry.getValue()
                    + "  sent to new primary " + replication.getPrimary().getStationName());
            attempt(entry.getKey(), entry.getValue());
        }
    }

    private void attempt(String requestId, TrainRequest request) {

        int attempt = attemptsPerRequest.merge(requestId, 1, Integer::sum);
        String primaryName = replication.getPrimary().getStationName();

        try {
            ReplicationManager.ClientReply reply =
                    replication.handleClientRequest(requestId, request);

            if (reply.duplicate()) {
                duplicatesSuppressed++;
                committed++;
                Terminal.line("✓ " + requestId + " already committed at v" + reply.version()
                        + " → duplicate detected, NOT applied again");
                record(requestId, attempt, request, primaryName, "DUPLICATE", reply.version(),
                        "already applied at v" + reply.version() + " → not applied twice");
            } else if (reply.commit().accepted()) {
                committed++;
                List<String> backups = reply.commit().acknowledgedBy();
                Terminal.line("✓ " + requestId + " COMMITTED v" + reply.version() + " : "
                        + request.trackId() + " → " + reply.commit().newStatus()
                        + (backups.isEmpty()
                                ? "   ⚠ no backup available"
                                : "   replicated → " + String.join(", ", backups)));
                record(requestId, attempt, request, primaryName, "COMMITTED", reply.version(),
                        request.trackId() + " → " + reply.commit().newStatus()
                                + (backups.isEmpty() ? " (no backup available)"
                                        : " · replicated → " + String.join(", ", backups)));
            } else {
                rejected++;
                Terminal.line("✗ " + requestId + " REJECTED: " + reply.commit().reason());
                record(requestId, attempt, request, primaryName, "REJECTED", null,
                        reply.commit().reason());
            }

        } catch (ReplicationManager.PrimaryUnavailableException e) {
            timeouts++;
            awaitingRetry.put(requestId, request);
            Terminal.line("⚠ " + requestId + " NO REPLY (" + e.getMessage()
                    + ") → client will retry");
            record(requestId, attempt, request, primaryName, "NO_REPLY", null, e.getMessage());
        }
    }

    private void record(String requestId, int attempt, TrainRequest request, String primary,
                        String status, Long version, String detail) {
        history.add(new Attempt(requestId, attempt, request.trainId(),
                request.movement().name(), request.trackId(), primary, status, version, detail));
    }

    public List<Attempt> getHistory() {
        return List.copyOf(history);
    }

    public List<String> getPendingRequestIds() {
        return List.copyOf(awaitingRetry.keySet());
    }

    public int getSent() {
        return sent;
    }

    public int getCommitted() {
        return committed;
    }

    public int getRejected() {
        return rejected;
    }

    public int getTimeouts() {
        return timeouts;
    }

    public int getRetries() {
        return retries;
    }

    public int getDuplicatesSuppressed() {
        return duplicatesSuppressed;
    }

    public int getPendingCount() {
        return awaitingRetry.size();
    }
}
