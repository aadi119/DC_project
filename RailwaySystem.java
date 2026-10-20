import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.ServerException;
import java.util.Random;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;

/**
 * The live railway system behind the web dashboard.
 *
 * It owns the same objects the console demo uses (stations, replication
 * manager, load balancer, failure detector, client) and offers one method
 * per action a user can trigger. Every action runs the real experiment
 * code; nothing is simulated in the web layer. Each action returns the
 * station events and console lines it produced, plus structured results.
 */
public final class RailwaySystem {

    public static final List<String> TRACKS =
            List.of("TRACK-01", "TRACK-02", "TRACK-03", "TRACK-04", "TRACK-05");

    /** An action the user asked for that cannot be carried out right now. */
    public static class ActionException extends RuntimeException {
        public ActionException(String message) {
            super(message);
        }
    }

    private static final Gson GSON = new Gson();

    // One user action at a time changes the system
    private final Object lock = new Object();
    // Spark and MPI jobs run outside the main lock, one at a time each
    private final Object sparkLock = new Object();
    private final Object mpiLock = new Object();

    private final Path projectDir = Path.of("").toAbsolutePath();

    private volatile List<RailwayStation> stations;
    private volatile ReplicationManager replication;
    private volatile LoadBalancer loadBalancer;
    private volatile TrainControlClient client;
    private FailureDetector detector;

    // Experiment 1: every station is also published as a Java RMI remote object
    private final StationRmiServer rmi;
    private final Random random = new Random();

    private int nextSequence;
    private int nextClientRequest;
    private int nextTrainNumber;
    private int failovers;

    // Latest result of each kind, shown again when a page is reopened
    private final Map<String, Object> lastResults = new LinkedHashMap<>();

    public RailwaySystem() {
        rmi = startRmi();
        reset();
    }

    private static StationRmiServer startRmi() {
        try {
            String port = System.getenv("RMI_PORT");
            StationRmiServer server = StationRmiServer.start(
                    port == null ? StationRmiServer.DEFAULT_PORT : Integer.parseInt(port));
            Terminal.line("✓ Java RMI registry on port " + server.getPort());
            return server;
        } catch (RemoteException | RuntimeException e) {
            Terminal.line("⚠ Java RMI registry could not start (" + e.getMessage()
                    + "): the station API answers in-process");
            return null;
        }
    }

    /**
     * Keeps the RMI registry in line with the stations: a running station is
     * published, a crashed one is withdrawn (its remote object stops answering).
     */
    private void syncRmi() {
        if (rmi == null) {
            return;
        }
        for (RailwayStation s : stations) {
            try {
                if (s.isActive() && !rmi.isPublished(s.getStationName())) {
                    rmi.publish(s);
                } else if (!s.isActive() && rmi.isPublished(s.getStationName())) {
                    rmi.withdraw(s.getStationName());
                }
            } catch (RemoteException e) {
                Terminal.line("⚠ RMI: could not publish " + s.getStationName() + ": " + e.getMessage());
            }
        }
    }

    // =========================================================
    // SET-UP
    // =========================================================

    public Map<String, Object> reset() {
        return action(() -> {
            if (stations != null) {
                stations.forEach(RailwayStation::shutdownWorker);
            }

            Terminal.banner("RAILWAY SYSTEM STARTED (web dashboard)");

            stations = new ArrayList<>();
            for (int id = 1; id <= 4; id++) {
                stations.add(new RailwayStation(String.format("STATION-%02d", id), id));
            }
            station("STATION-04").setCoordinator(true);
            if (rmi != null) {
                for (RailwayStation s : stations) {
                    rmi.withdraw(s.getStationName());
                }
            }

            replication = new ReplicationManager(station("STATION-04"),
                    List.of(station("STATION-01"), station("STATION-02")));
            replication.initializeState(initialTrackState());

            loadBalancer = new LoadBalancer(stations);
            client = new TrainControlClient(replication);
            detector = new FailureDetector(3, 150);

            nextSequence = 1;
            nextClientRequest = 801;
            nextTrainNumber = 301;
            failovers = 0;
            lastResults.clear();
            return Map.of("message", "System reset: STATION-04 is coordinator and primary");
        });
    }

    private static Map<String, TrackStateReplica.TrackStatus> initialTrackState() {
        Map<String, TrackStateReplica.TrackStatus> state = new LinkedHashMap<>();
        state.put("TRACK-01", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-02", TrackStateReplica.TrackStatus.occupied("TRAIN-104"));
        state.put("TRACK-03", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-04", TrackStateReplica.TrackStatus.clear());
        state.put("TRACK-05", TrackStateReplica.TrackStatus.occupied("TRAIN-099"));
        return state;
    }

    // =========================================================
    // SNAPSHOT OF THE WHOLE SYSTEM (polled by the browser)
    // =========================================================

    public Map<String, Object> state() {

        RailwayStation primary = replication.getPrimary();
        List<RailwayStation> backups = replication.getBackups();
        Map<String, Integer> assigned = loadBalancer.getAssignmentCounts();

        List<Map<String, Object>> stationList = new ArrayList<>();
        String coordinator = null;

        for (RailwayStation s : stations) {
            String role = s == primary ? "PRIMARY" : backups.contains(s) ? "BACKUP" : "NONE";
            if (s.isCoordinator()) {
                coordinator = s.getStationName();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getStationName());
            m.put("id", s.getStationId());
            m.put("active", s.isActive());
            m.put("coordinator", s.isCoordinator());
            m.put("overloaded", s.isOverloaded());
            m.put("role", role);
            m.put("lamport", s.getLamportClock());
            m.put("vector", s.getVectorClock());
            m.put("version", s.getReplica().getVersion());
            m.put("checksum", s.getReplica().checksum());
            m.put("events", s.getEventCount());
            m.put("assigned", assigned.getOrDefault(s.getStationName(), 0));
            m.put("clockOffsetMs", Math.round(s.getPhysicalClock().currentOffsetMs()));
            m.put("driftPpm", s.getPhysicalClock().getDriftPpm());
            m.put("rmi", rmi != null && rmi.isPublished(s.getStationName())
                    ? rmi.url(s.getStationName()) : null);
            stationList.add(m);
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        primary.getReplica().snapshot().forEach((track, status) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("track", track);
            t.put("state", status.state());
            t.put("train", status.isOccupied() ? status.trainId() : null);
            tracks.add(t);
        });

        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("sent", client.getSent());
        clientInfo.put("committed", client.getCommitted());
        clientInfo.put("rejected", client.getRejected());
        clientInfo.put("timeouts", client.getTimeouts());
        clientInfo.put("retries", client.getRetries());
        clientInfo.put("duplicates", client.getDuplicatesSuppressed());
        clientInfo.put("pending", client.getPendingRequestIds());
        clientInfo.put("history", client.getHistory());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("stations", stationList);
        state.put("coordinator", coordinator);
        state.put("primary", primary.getStationName());
        state.put("primaryActive", primary.isActive());
        state.put("backups", backups.stream().map(RailwayStation::getStationName).toList());
        state.put("version", primary.getReplica().getVersion());
        state.put("tracks", tracks);
        state.put("nextInRotation", loadBalancer.nextInRotation());
        state.put("failovers", failovers);
        state.put("clockSkewMs", Math.round(ClockSync.skew(stations)));
        state.put("serverTimeMs", System.currentTimeMillis());
        state.put("rmiPort", rmi == null ? null : rmi.getPort());
        state.put("client", clientInfo);
        state.put("lastSeq", EventFeed.lastSeq());
        synchronized (lastResults) {
            state.put("results", new LinkedHashMap<>(lastResults));
        }
        return state;
    }

    // =========================================================
    // STATIONS: FAIL / RECOVER / OVERLOAD / HEARTBEAT
    // =========================================================

    public Map<String, Object> failStation(String name) {
        return action(() -> {
            RailwayStation s = station(name);
            if (!s.isActive()) {
                throw new ActionException(name + " has already failed");
            }
            s.failNode();
            return Map.of("message", name + " crashed (stops responding)");
        });
    }

    public Map<String, Object> recoverStation(String name) {
        return action(() -> {
            RailwayStation s = station(name);
            if (s.isActive()) {
                throw new ActionException(name + " is already running");
            }
            s.recoverNode();

            // A station that held a replica catches up before rejoining
            String message = name + " recovered";
            if (s != replication.getPrimary() && s.getReplica().getVersion() > 0
                    && replication.getPrimary().isActive()) {
                replication.resynchronize(s);
                message += ", caught up to v" + s.getReplica().getVersion() + " and rejoined as BACKUP";
            }
            return Map.of("message", message);
        });
    }

    public Map<String, Object> toggleOverload(String name) {
        return action(() -> {
            RailwayStation s = station(name);
            s.setOverloaded(!s.isOverloaded());
            Terminal.line((s.isOverloaded() ? "⚠ " : "✓ ") + name
                    + (s.isOverloaded() ? " marked OVERLOADED" : " load back to normal"));
            return Map.of("message", name + (s.isOverloaded() ? " is now OVERLOADED" : " is no longer overloaded"));
        });
    }

    /** Every station reports its own status (Experiment 4 heartbeat monitoring). */
    /**
     * One heartbeat round: a live monitor (the coordinator, or the lowest-ID
     * running station) sends a heartbeat to every other station. A crashed
     * station cannot answer, so the monitor records the missing reply.
     */
    public Map<String, Object> heartbeatRound() {
        return action(() -> {
            RailwayStation monitor = stations.stream()
                    .filter(s -> s.isCoordinator() && s.isActive()).findFirst()
                    .orElse(stations.stream().filter(RailwayStation::isActive).findFirst()
                            .orElseThrow(() -> new ActionException("No station is running")));

            Terminal.section("HEARTBEAT MONITORING (" + monitor.getStationName() + " monitors)");
            Map<String, Boolean> alive = new LinkedHashMap<>();
            for (RailwayStation s : stations) {
                if (s == monitor) {
                    alive.put(s.getStationName(), true);
                    continue;
                }
                int sendTimestamp = monitor.printEvent("HEARTBEAT → " + s.getStationName());
                boolean answered = s.answerHeartbeat(sendTimestamp, monitor);
                if (!answered) {
                    monitor.printEvent("No reply from " + s.getStationName() + " (heartbeat round)");
                }
                alive.put(s.getStationName(), answered);
            }
            return Map.of("monitor", monitor.getStationName(), "alive", alive);
        });
    }

    // =========================================================
    // EXPERIMENT 1 - RPC (client-server)
    // =========================================================

    /**
     * JSON-RPC style remote procedure call on a station. The browser is the
     * client, the server routes the call to the station object, and a
     * crashed station answers with an error instead of a result.
     */
    public Map<String, Object> rpc(String method, String stationName, Object id) {
        long start = System.nanoTime();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        try {
            Object result = switch (method) {
                case "cluster.getCoordinator" -> {
                    synchronized (lock) {
                        yield coordinatorName();
                    }
                }
                case "replication.getPrimary" -> {
                    synchronized (lock) {
                        yield Map.of(
                                "primary", replication.getPrimary().getStationName(),
                                "backups", replication.getBackups().stream()
                                        .map(RailwayStation::getStationName).toList(),
                                "version", replication.getPrimary().getReplica().getVersion());
                    }
                }
                case "station.ping", "station.getLamportClock", "station.getTrackState" -> {
                    // No system lock: stations are thread-safe, so the thread
                    // pool can serve many of these calls at the same time
                    RailwayStation s = station(stationName);
                    if (rmi != null) {
                        response.put("transport", "Java RMI");
                        response.put("endpoint", rmi.url(s.getStationName()));
                        yield stationRmiCall(method, s);
                    }
                    response.put("transport", "in-process");
                    yield stationLocalCall(method, s);
                }
                default -> throw new RpcError(-32601, "Method not found: " + method);
            };
            response.put("result", result);
        } catch (RpcError e) {
            response.put("error", Map.of("code", e.code, "message", e.getMessage()));
        } catch (ActionException e) {
            response.put("error", Map.of("code", -32602, "message", e.getMessage()));
        }

        response.put("servedBy", Thread.currentThread().getName());
        response.put("latencyMs", Math.round((System.nanoTime() - start) / 1e4) / 100.0);
        return response;
    }

    private static class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Calls the station through its RMI stub, looked up in the registry. */
    private Object stationRmiCall(String method, RailwayStation s) {
        StationRemote stub;
        try {
            stub = rmi.lookup(s.getStationName());
        } catch (Exception e) {
            throw new RpcError(-32000, s.getStationName() + " is not bound in the RMI registry");
        }
        try {
            return switch (method) {
                case "station.ping" -> {
                    StationRemote.PingReply reply = stub.ping();
                    yield Map.of("station", reply.station(), "status", reply.status(),
                            "lamport", reply.lamport(), "rmiThread", reply.servedBy());
                }
                case "station.getLamportClock" -> Map.of("station", s.getStationName(),
                        "lamport", stub.getLamportClock());
                default -> {
                    Map<String, TrackStateReplica.TrackStatus> tracks = stub.getTrackState();
                    yield Map.of("station", s.getStationName(),
                            "version", stub.getStateVersion(), "tracks", tracks);
                }
            };
        } catch (RemoteException e) {
            Throwable cause = e instanceof ServerException && e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() == null ? "" : cause.getMessage().split("\\R")[0];
            throw new RpcError(-32000, s.getStationName() + " unreachable → "
                    + cause.getClass().getName() + ": " + message);
        }
    }

    /** Fallback when no RMI registry could be started. */
    private Object stationLocalCall(String method, RailwayStation s) {
        if (!s.isActive()) {
            throw new RpcError(-32000, s.getStationName() + " is not responding (connection refused)");
        }
        return switch (method) {
            case "station.ping" -> Map.of("station", s.getStationName(), "status", "ALIVE",
                    "lamport", s.printEvent("RPC station.ping() served for web client"));
            case "station.getLamportClock" -> Map.of("station", s.getStationName(),
                    "lamport", s.getLamportClock());
            default -> Map.of("station", s.getStationName(),
                    "version", s.getReplica().getVersion(),
                    "tracks", s.getReplica().snapshot());
        };
    }

    // =========================================================
    // EXPERIMENT 3 - PHYSICAL CLOCK SYNCHRONIZATION
    // =========================================================

    /** Cristian's algorithm: the station asks the GPS time server for the time. */
    public Map<String, Object> clockCristian(String name) {
        return action(() -> {
            RailwayStation s = activeStation(name);
            ClockSync.CristianResult r = ClockSync.cristian(s, "GPS-TIME-SERVER",
                    PhysicalClock.reference(), 20 + random.nextInt(30), 20 + random.nextInt(30));
            s.printEvent(String.format("CLOCK SYNC (Cristian) with GPS time server: RTT %d ms → "
                    + "adjusted %+.0f ms (±%.0f ms)", r.roundTripMs(), r.adjustmentMs(), r.errorBoundMs()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "Cristian");
            result.put("result", r);
            result.put("skewMs", Math.round(ClockSync.skew(stations)));
            remember("clock", result);
            return result;
        });
    }

    /** Berkeley algorithm, run by the coordinator over all running stations. */
    public Map<String, Object> clockBerkeley() {
        return action(() -> {
            RailwayStation master = stations.stream()
                    .filter(s -> s.isCoordinator() && s.isActive()).findFirst()
                    .orElseThrow(() -> new ActionException(
                            "The coordinator is offline: run an election first"));
            ClockSync.BerkeleyResult r = ClockSync.berkeley(master, stations, 2000, 15);
            master.printEvent(String.format("BERKELEY round: polled %d clocks, average offset %+.0f ms"
                    + " → corrections sent", r.readings().size(), r.averageOffsetMs()));
            for (ClockSync.BerkeleyReading reading : r.readings()) {
                station(reading.station()).printEvent(String.format("CLOCK ADJUST %+.0f ms (Berkeley%s)",
                        reading.adjustmentMs(), reading.included() ? "" : ", excluded from average"));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "Berkeley");
            result.put("result", r);
            result.put("skewMs", Math.round(r.skewAfterMs()));
            remember("clock", result);
            return result;
        });
    }

    /** Gives every clock a new random error so synchronization can be shown again. */
    public Map<String, Object> clockDisturb() {
        return action(() -> {
            int faulty = random.nextInt(stations.size());
            for (int i = 0; i < stations.size(); i++) {
                double offset = i == faulty
                        ? (random.nextBoolean() ? 1 : -1) * (2600 + random.nextInt(1500))
                        : random.nextInt(2400) - 1200;
                stations.get(i).getPhysicalClock().setOffset(offset);
            }
            long skew = Math.round(ClockSync.skew(stations));
            Terminal.line("⚠ Station clocks disturbed: skew is now " + skew + " ms");
            return Map.of("message", "Clocks disturbed: skew " + skew + " ms");
        });
    }

    // =========================================================
    // EXPERIMENT 2 - MULTITHREADING
    // =========================================================

    public Map<String, Object> threads() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Integer> groups = new LinkedHashMap<>();

        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            String group = name.endsWith("-worker") ? "Station workers"
                    : name.startsWith("http-") ? "Web server (request handlers)"
                    : name.startsWith("RMI ") ? "Java RMI (remote calls)"
                    : name.contains("Executor task launch") || name.contains("dispatcher")
                            || name.contains("spark") || name.contains("Spark") ? "Apache Spark"
                    : name.equals("main") ? "Main" : "JVM / other";
            groups.merge(group, 1, Integer::sum);
            if (!group.equals("JVM / other")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("state", t.getState().name());
                m.put("group", group);
                m.put("daemon", t.isDaemon());
                list.add(m);
            }
        }
        list.sort((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", Thread.activeCount());
        result.put("groups", groups);
        result.put("threads", list);
        result.put("cpus", Runtime.getRuntime().availableProcessors());
        result.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return result;
    }

    // =========================================================
    // EXPERIMENT 3 - LAMPORT CLOCKS
    // =========================================================

    public Map<String, Object> lamportLocal(String name, String label) {
        return action(() -> {
            RailwayStation s = activeStation(name);
            int before = s.getLamportClock();
            int timestamp = s.printEvent("LOCAL EVENT: " + label);
            return Map.of("station", name, "before", before, "after", timestamp,
                    "rule", "L = L + 1 = " + timestamp);
        });
    }

    public Map<String, Object> lamportMessage(String from, String to, String label) {
        return action(() -> {
            if (from.equals(to)) {
                throw new ActionException("Choose two different stations");
            }
            RailwayStation sender = activeStation(from);
            RailwayStation receiver = activeStation(to);

            int receiverBefore = receiver.getLamportClock();
            int sendTimestamp = sender.printEvent("SEND \"" + label + "\" → " + to);
            int receiveTimestamp = receiver.printReceivedEvent(sendTimestamp,
                    sender.vectorAt(sendTimestamp),
                    "RECEIVE \"" + label + "\" from " + from + " (msg L=" + sendTimestamp
                            + ") → L = max(" + receiverBefore + ", " + sendTimestamp + ") + 1");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("from", from);
            result.put("to", to);
            result.put("sendTimestamp", sendTimestamp);
            result.put("receiverBefore", receiverBefore);
            result.put("receiveTimestamp", receiveTimestamp);
            result.put("rule", "max(" + receiverBefore + ", " + sendTimestamp + ") + 1 = " + receiveTimestamp);
            result.put("sendVector", sender.vectorAt(sendTimestamp));
            result.put("receiveVector", receiver.vectorAt(receiveTimestamp));
            return result;
        });
    }

    // =========================================================
    // EXPERIMENT 4 - LEADER ELECTION
    // =========================================================

    public Map<String, Object> election(String algorithm) {
        return action(() -> {
            if (stations.stream().noneMatch(RailwayStation::isActive)) {
                throw new ActionException("No active station can run an election");
            }
            long seq = EventFeed.lastSeq();
            RailwayStation winner = "ring".equals(algorithm)
                    ? RailwayStation.ringElection(stations)
                    : RailwayStation.bullyElection(stations);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "ring".equals(algorithm) ? "Ring" : "Bully");
            result.put("winner", winner.getStationName());
            result.put("winnerId", winner.getStationId());
            result.put("messages", stationEventsSince(seq).size());
            remember("election", result);
            return result;
        });
    }

    // =========================================================
    // EXPERIMENT 5 - REPLICATION & CONSISTENCY
    // =========================================================

    public Map<String, Object> replicationUpdate(String track, String status, String train) {
        return action(() -> {
            requireActivePrimary();
            if (!TRACKS.contains(track)) {
                throw new ActionException("Unknown track " + track);
            }
            TrackStateReplica.TrackStatus newStatus;
            if ("OCCUPIED".equals(status)) {
                if (train == null || train.isBlank()) {
                    throw new ActionException("Enter the train that occupies " + track);
                }
                newStatus = TrackStateReplica.TrackStatus.occupied(normaliseTrain(train));
            } else {
                newStatus = TrackStateReplica.TrackStatus.clear();
            }

            ReplicationManager.Commit commit = replication.update(track, newStatus, true);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", commit.version());
            result.put("track", track);
            result.put("status", newStatus.toString());
            result.put("acknowledgedBy", commit.acknowledgedBy());
            result.put("consistency", consistencyReport(false));
            remember("replication", result);
            return result;
        });
    }

    public Map<String, Object> verifyConsistency() {
        return action(() -> {
            Map<String, Object> report = consistencyReport(true);
            remember("consistency", report);
            return report;
        });
    }

    private Map<String, Object> consistencyReport(boolean print) {
        if (print) {
            Terminal.section("CONSISTENCY VERIFICATION");
            replication.verifyConsistency();
        }
        RailwayStation primary = replication.getPrimary();
        TrackStateReplica reference = primary.getReplica();

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean consistent = true;
        List<RailwayStation> members = new ArrayList<>();
        members.add(primary);
        members.addAll(replication.getBackups());

        for (RailwayStation s : members) {
            TrackStateReplica r = s.getReplica();
            boolean identical = r.getVersion() == reference.getVersion()
                    && r.snapshot().equals(reference.snapshot())
                    && r.appliedRequestsSnapshot().equals(reference.appliedRequestsSnapshot());
            consistent &= identical;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("station", s.getStationName());
            row.put("role", s == primary ? "PRIMARY" : "BACKUP");
            row.put("version", r.getVersion());
            row.put("checksum", r.checksum());
            row.put("identical", identical);
            rows.add(row);
        }
        return Map.of("consistent", consistent, "replicas", rows);
    }

    // =========================================================
    // EXPERIMENT 6 - LOAD BALANCING
    // =========================================================

    /** Builds a batch from the current track state: departures, arrivals and one conflict. */
    public Map<String, Object> dispatchDemoBatch() {
        List<String[]> batch = new ArrayList<>();
        Map<String, TrackStateReplica.TrackStatus> tracks = replication.getPrimary().getReplica().snapshot();

        String firstArrivalTrack = null;
        for (Map.Entry<String, TrackStateReplica.TrackStatus> e : tracks.entrySet()) {
            if (batch.size() >= 5) {
                break;
            }
            if (e.getValue().isOccupied()) {
                batch.add(new String[] {e.getValue().trainId(), "DEPARTURE", e.getKey()});
            } else {
                batch.add(new String[] {"", "ARRIVAL", e.getKey()});
                if (firstArrivalTrack == null) {
                    firstArrivalTrack = e.getKey();
                }
            }
        }
        // One deliberate conflict: a second train for a track that was just filled
        if (firstArrivalTrack != null) {
            batch.add(new String[] {"", "ARRIVAL", firstArrivalTrack});
        }
        return dispatch(batch);
    }

    /** Each entry: {train (blank = new train), movement, track}. */
    public Map<String, Object> dispatch(List<String[]> requests) {
        return action(() -> {
            requireActivePrimary();
            if (requests.isEmpty()) {
                throw new ActionException("No train requests to dispatch");
            }

            Terminal.section("LOAD BALANCER: " + requests.size() + " TRAIN REQUEST(S)");

            List<TrainRequest> batch = new ArrayList<>();
            for (String[] r : requests) {
                String train = r[0] == null || r[0].isBlank()
                        ? "TRAIN-" + nextTrainNumber++
                        : normaliseTrain(r[0]);
                if (!TRACKS.contains(r[2])) {
                    throw new ActionException("Unknown track " + r[2]);
                }
                batch.add(new TrainRequest(nextSequence++, train,
                        TrainRequest.Movement.valueOf(r[1]), r[2]));
            }

            // 1. Route every request (health-aware round-robin) and dispatch at once
            List<Map<String, Object>> decisions = new ArrayList<>();
            List<Future<TrainRequest.Result>> pending = new ArrayList<>();
            long start = System.nanoTime();

            for (TrainRequest request : batch) {
                LoadBalancer.Decision decision = loadBalancer.route(request);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("sequence", request.sequence());
                d.put("train", request.trainId());
                d.put("movement", request.movement().name());
                d.put("track", request.trackId());
                d.put("station", decision.assigned() ? decision.station().getStationName() : null);
                d.put("skipped", decision.skipped());
                decisions.add(d);

                Terminal.line(String.format("#%d %s → %s%s", request.sequence(), request,
                        decision.assigned() ? decision.station().getStationName() : "NO STATION AVAILABLE",
                        decision.skipped().isEmpty() ? ""
                                : "   (skipped " + String.join(", ", decision.skipped()) + ")"));

                if (decision.assigned()) {
                    pending.add(decision.station().submitTrainRequest(request));
                }
            }

            // 2. Stations process in parallel on their worker threads
            List<TrainRequest.Result> results = new ArrayList<>();
            for (Future<TrainRequest.Result> f : pending) {
                try {
                    results.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ActionException("Interrupted while waiting for stations");
                } catch (ExecutionException e) {
                    throw new ActionException("Station worker failed: " + e.getCause());
                }
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            List<Map<String, Object>> processing = new ArrayList<>();
            for (TrainRequest.Result r : results) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("train", r.request().trainId());
                p.put("station", r.stationName());
                p.put("worker", r.workerThread());
                p.put("startedAt", r.startedAt());
                p.put("finishedAt", r.finishedAt());
                p.put("lamport", r.lamportTimestamp());
                processing.add(p);
            }

            // 3. The primary commits in request order (safety check + replication)
            Terminal.line("Primary " + replication.getPrimary().getStationName()
                    + " commits in request order:");
            List<Map<String, Object>> commits = new ArrayList<>();
            for (TrainRequest.Result r : results) {
                ReplicationManager.TrainCommit c = replication.commitTrainMovement(r.request());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sequence", r.request().sequence());
                m.put("train", r.request().trainId());
                m.put("track", r.request().trackId());
                m.put("accepted", c.accepted());
                m.put("version", c.version());
                m.put("newStatus", c.accepted() ? c.newStatus().toString() : null);
                m.put("reason", c.reason());
                m.put("acknowledgedBy", c.acknowledgedBy());
                commits.add(m);
                Terminal.line(c.accepted()
                        ? String.format("  #%d v%d  %s → %s   ✓ replicated → %s", r.request().sequence(),
                                c.version(), r.request().trackId(), c.newStatus(),
                                String.join(", ", c.acknowledgedBy()))
                        : String.format("  #%d ✗ %s REJECTED: %s", r.request().sequence(),
                                r.request().trainId(), c.reason()));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("decisions", decisions);
            result.put("processing", processing);
            result.put("commits", commits);
            result.put("elapsedMs", elapsedMs);
            result.put("serialEstimateMs", results.size() * RailwayStation.TRAIN_PROCESSING_TIME_MS);
            result.put("counts", loadBalancer.getAssignmentCounts());
            remember("dispatch", result);
            return result;
        });
    }

    // =========================================================
    // EXPERIMENT 7 - MAPREDUCE (Apache Spark)
    // =========================================================

    public Map<String, Object> runAnalytics() {
        synchronized (sparkLock) {
            Map<String, Path> logs = new LinkedHashMap<>();
            Map<String, Integer> counts;
            synchronized (lock) {
                Terminal.banner("MAPREDUCE JOB (Apache Spark)", "Collecting distributed railway event logs...");
                try {
                    for (RailwayStation s : stations) {
                        logs.put(s.getStationName(), s.exportEventLog(projectDir.resolve("logs").resolve("web")));
                    }
                } catch (IOException e) {
                    throw new ActionException("Could not write event logs: " + e.getMessage());
                }
                counts = loadBalancer.getAssignmentCounts();
            }

            long seq = EventFeed.lastSeq();
            long start = System.nanoTime();
            try {
                RailwayEventAnalytics.Result result =
                        RailwayEventAnalytics.analyzeWithSharedContext(logs, counts);
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                Terminal.line("✓ MAPREDUCE JOB COMPLETED in " + elapsed + " ms — busiest track "
                        + result.busiestTrack());

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("result", result);
                out.put("elapsedMs", elapsed);
                remember("analytics", out);
                return withLogs(out, seq);
            } catch (IOException | RuntimeException e) {
                throw new ActionException("Spark job failed: " + e.getMessage());
            }
        }
    }

    // =========================================================
    // EXPERIMENT 8 - FAULT TOLERANCE
    // =========================================================

    public Map<String, Object> clientRequest(String train, String movement, String track,
                                             boolean crashBeforeReply) {
        return action(() -> {
            if (!TRACKS.contains(track)) {
                throw new ActionException("Unknown track " + track);
            }
            String requestId = "REQ-" + nextClientRequest++;
            TrainRequest request = new TrainRequest(nextSequence++,
                    train == null || train.isBlank() ? "TRAIN-" + nextTrainNumber++ : normaliseTrain(train),
                    TrainRequest.Movement.valueOf(movement), track);

            if (crashBeforeReply) {
                if (!replication.getPrimary().isActive()) {
                    throw new ActionException("The primary is already down");
                }
                replication.injectCrashBeforeNextReply();
            }
            client.send(requestId, request);
            List<TrainControlClient.Attempt> history = client.getHistory();
            return Map.of("requestId", requestId, "attempt", history.get(history.size() - 1));
        });
    }

    public Map<String, Object> detectFailure() {
        return action(() -> {
            RailwayStation primary = replication.getPrimary();
            RailwayStation monitor = replication.getBackups().stream()
                    .filter(RailwayStation::isActive).findFirst()
                    .orElse(stations.stream()
                            .filter(s -> s.isActive() && s != primary).findFirst()
                            .orElseThrow(() -> new ActionException("No live station can monitor the primary")));

            Terminal.section("FAILURE DETECTION: " + monitor.getStationName() + " monitors "
                    + primary.getStationName());
            boolean failed;
            try {
                failed = detector.monitor(monitor, primary, 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ActionException("Interrupted");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("monitor", monitor.getStationName());
            result.put("target", primary.getStationName());
            result.put("declaredFailed", failed);

            if (failed) {
                Terminal.section("AUTOMATIC FAILOVER");
                try {
                    RailwayStation newPrimary = replication.failover(stations);
                    failovers++;
                    result.put("newPrimary", newPrimary.getStationName());
                    result.put("noDataLoss", true);
                } catch (IllegalStateException e) {
                    result.put("error", "Failover impossible: " + e.getMessage());
                }
            } else {
                Terminal.line("✓ " + primary.getStationName() + " answered every heartbeat: no failover needed");
            }
            remember("detection", result);
            return result;
        });
    }

    public Map<String, Object> retryPending() {
        return action(() -> {
            if (client.getPendingCount() == 0) {
                throw new ActionException("No request is waiting for a retry");
            }
            client.retryPending();
            return Map.of("pending", client.getPendingRequestIds());
        });
    }

    // =========================================================
    // EXPERIMENTS 9-10 - MPI (MPJ Express processes)
    // =========================================================

    public Map<String, Object> runMpiCollectives(int processes) {
        if (processes != 1 && processes != 2 && processes != 4 && processes != 8) {
            throw new ActionException("Use 1, 2, 4 or 8 processes (8 requests must split evenly)");
        }
        String stateArg = "--state=" + encodeStateForMpi();
        Map<String, Object> out = runMpi("RailwayMpiCollectives", processes, List.of("--json", stateArg));
        remember("collectives", out);
        return out;
    }

    public Map<String, Object> runMpiMatrix(int processes, int n) {
        if (processes < 1 || processes > 8) {
            throw new ActionException("Use 1 to 8 processes");
        }
        if (n < 60 || n > 1600) {
            throw new ActionException("Matrix size must be between 60 and 1600");
        }
        Map<String, Object> out = runMpi("RailwayMatrixMultiplication", processes,
                List.of("--json", "--n=" + n));
        remember("matrix", out);
        return out;
    }

    /** [version, TRACK-01..05] with 0 = CLEAR or the train number, e.g. 13,0,0,110,0,0 */
    private String encodeStateForMpi() {
        TrackStateReplica replica = replication.getPrimary().getReplica();
        StringBuilder text = new StringBuilder(String.valueOf(replica.getVersion()));
        for (String track : TRACKS) {
            TrackStateReplica.TrackStatus status = replica.get(track);
            int train = 0;
            if (status != null && status.isOccupied()) {
                String digits = status.trainId().replaceAll("\\D", "");
                train = digits.isEmpty() ? 1 : Integer.parseInt(digits);
            }
            text.append(',').append(train);
        }
        return text.toString();
    }

    private Map<String, Object> runMpi(String mainClass, int processes, List<String> args) {
        Path mpjHome = findMpjHome();
        Path starter = mpjHome.resolve("lib").resolve("starter.jar");

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-jar", starter.toString(),
                "-np", String.valueOf(processes),
                "-cp", projectDir.resolve("target").resolve("classes").toString(),
                mainClass));
        command.addAll(args);

        synchronized (mpiLock) {
            Terminal.banner("MPI JOB: " + mainClass + " on " + processes + " process(es)");
            long start = System.nanoTime();
            List<String> lines = new ArrayList<>();
            String json = null;

            try {
                ProcessBuilder builder = new ProcessBuilder(command)
                        .directory(projectDir.toFile())
                        .redirectErrorStream(true);
                builder.environment().put("MPJ_HOME", mpjHome.toString());
                Process process = builder.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(MpiStations.RESULT_MARKER)) {
                            json = line.substring(MpiStations.RESULT_MARKER.length());
                        } else {
                            lines.add(line);
                            EventFeed.console(line);
                        }
                    }
                }
                if (!process.waitFor(180, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ActionException("MPI job timed out");
                }
            } catch (IOException e) {
                throw new ActionException("Could not start MPJ Express: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ActionException("Interrupted");
            }

            if (json == null) {
                throw new ActionException("MPI job finished without a result. Last output: "
                        + String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size())));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", GSON.fromJson(json, Map.class));
            out.put("output", lines);
            out.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
            out.put("command", "mpjrun -np " + processes + " " + mainClass);
            return out;
        }
    }

    private Path findMpjHome() {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("MPJ_HOME");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        candidates.add(projectDir.resolveSibling("mpj-v0_44"));
        candidates.add(Path.of("C:\\dcp\\mpj-v0_44"));
        for (Path p : candidates) {
            if (Files.exists(p.resolve("lib").resolve("starter.jar"))) {
                return p;
            }
        }
        throw new ActionException("MPJ Express not found: set MPJ_HOME to the MPJ Express folder");
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private interface Action {
        Map<String, Object> run();
    }

    /** Runs one action under the system lock and attaches the events it produced. */
    private Map<String, Object> action(Action action) {
        synchronized (lock) {
            long seq = EventFeed.lastSeq();
            try {
                return withLogs(new LinkedHashMap<>(action.run()), seq);
            } finally {
                syncRmi();
            }
        }
    }

    private Map<String, Object> withLogs(Map<String, Object> result, long seq) {
        List<EventFeed.Entry> entries = EventFeed.since(seq);
        result.put("events", entries.stream().filter(e -> e.kind().equals("station")).toList());
        result.put("console", entries.stream().filter(e -> e.kind().equals("console"))
                .map(EventFeed.Entry::text).toList());
        return result;
    }

    private List<EventFeed.Entry> stationEventsSince(long seq) {
        return EventFeed.since(seq).stream().filter(e -> e.kind().equals("station")).toList();
    }

    private void remember(String key, Object value) {
        synchronized (lastResults) {
            lastResults.put(key, value);
        }
    }

    private RailwayStation station(String name) {
        return stations.stream().filter(s -> s.getStationName().equals(name)).findFirst()
                .orElseThrow(() -> new ActionException("Unknown station " + name));
    }

    private RailwayStation activeStation(String name) {
        RailwayStation s = station(name);
        if (!s.isActive()) {
            throw new ActionException(name + " is down: recover it first");
        }
        return s;
    }

    private void requireActivePrimary() {
        RailwayStation primary = replication.getPrimary();
        if (!primary.isActive()) {
            throw new ActionException("Primary " + primary.getStationName()
                    + " is down: run failure detection to fail over first");
        }
    }

    private String coordinatorName() {
        return stations.stream().filter(RailwayStation::isCoordinator)
                .map(RailwayStation::getStationName).findFirst().orElse("none");
    }

    private static String normaliseTrain(String train) {
        String t = train.trim().toUpperCase();
        return t.startsWith("TRAIN-") ? t : "TRAIN-" + t;
    }
}
