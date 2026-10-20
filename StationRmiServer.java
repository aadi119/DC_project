import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * EXPERIMENT 1 - Java RMI server for the railway stations.
 *
 * Creates an RMI registry and publishes every station under its name
 * (rmi://host:port/STATION-01 ...). Each station is exported as a remote
 * object; calls arrive on RMI's own connection threads, so several clients
 * are served at the same time.
 *
 * A crashed station is withdrawn: its remote object is unexported, so a
 * client still holding the old stub gets a real java.rmi.NoSuchObjectException.
 * On recovery it is exported again and re-bound in the registry.
 *
 * Standalone use (two terminals or two machines):
 *   java -cp target/classes StationRmiServer [port]
 *   java -cp target/classes StationRmiClient [host] [port]
 */
public final class StationRmiServer {

    public static final int DEFAULT_PORT = 1099;
    public static final String CONTROL_NAME = "RAILWAY-CONTROL";

    private final Registry registry;
    private final int port;
    private final Map<String, Servant> servants = new LinkedHashMap<>();
    private ControlServant control;

    private StationRmiServer(Registry registry, int port) {
        this.registry = registry;
        this.port = port;
    }

    /** Starts a registry on the first free port from preferredPort upwards. */
    public static StationRmiServer start(int preferredPort) throws RemoteException {

        // Stubs must point at an address the client can reach
        if (System.getProperty("java.rmi.server.hostname") == null) {
            System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        }

        ExportException lastError = null;
        for (int port = preferredPort; port < preferredPort + 10; port++) {
            try {
                return new StationRmiServer(LocateRegistry.createRegistry(port), port);
            } catch (ExportException e) {
                lastError = e;   // port in use, try the next one
            }
        }
        throw lastError;
    }

    public int getPort() {
        return port;
    }

    public String url(String stationName) {
        return "rmi://" + System.getProperty("java.rmi.server.hostname") + ":" + port + "/" + stationName;
    }

    /** Exports the station and binds it in the registry under its name. */
    public synchronized void publish(RailwayStation station) throws RemoteException {
        withdraw(station.getStationName());
        Servant servant = new Servant(station);
        StationRemote stub = (StationRemote) UnicastRemoteObject.exportObject(servant, 0);
        registry.rebind(station.getStationName(), stub);
        servants.put(station.getStationName(), servant);
    }

    /**
     * Withdraws a crashed station. The registry keeps the old stub (as a
     * real registry would after its server died), so clients that look it
     * up get a stub whose calls fail.
     */
    public synchronized void withdraw(String stationName) {
        Servant servant = servants.remove(stationName);
        if (servant != null) {
            try {
                UnicastRemoteObject.unexportObject(servant, true);
            } catch (NoSuchObjectException ignored) {
                // already withdrawn
            }
        }
    }

    public synchronized boolean isPublished(String stationName) {
        return servants.containsKey(stationName);
    }

    /** Publishes the operator console that can crash and repair stations. */
    public synchronized void publishControl(List<RailwayStation> stations) throws RemoteException {
        control = new ControlServant(this, stations);
        registry.rebind(CONTROL_NAME,
                (StationRemote.Control) UnicastRemoteObject.exportObject(control, 0));
    }

    /** Client-side lookup through this server's registry. */
    public StationRemote lookup(String stationName) throws Exception {
        return (StationRemote) LocateRegistry.getRegistry("127.0.0.1", port).lookup(stationName);
    }

    public synchronized void shutdown() {
        for (String name : List.copyOf(servants.keySet())) {
            withdraw(name);
        }
        try {
            if (control != null) {
                UnicastRemoteObject.unexportObject(control, true);
            }
            UnicastRemoteObject.unexportObject(registry, true);
        } catch (NoSuchObjectException ignored) {
            // already closed
        }
    }

    // =========================================================
    // REMOTE OBJECT: one railway station
    // =========================================================

    static final class Servant implements StationRemote {

        private final RailwayStation station;

        Servant(RailwayStation station) {
            this.station = station;
        }

        private void requireAlive() throws RemoteException {
            if (!station.isActive()) {
                throw new RemoteException(station.getStationName() + " is not responding");
            }
        }

        @Override
        public PingReply ping() throws RemoteException {
            requireAlive();
            String thread = Thread.currentThread().getName();
            int timestamp = station.printEvent("RMI ping() served on thread \"" + thread + "\"");
            return new PingReply(station.getStationName(), "ALIVE", timestamp, thread);
        }

        @Override
        public int getLamportClock() throws RemoteException {
            requireAlive();
            return station.getLamportClock();
        }

        @Override
        public long getStateVersion() throws RemoteException {
            requireAlive();
            return station.getReplica().getVersion();
        }

        @Override
        public Map<String, TrackStateReplica.TrackStatus> getTrackState() throws RemoteException {
            requireAlive();
            station.printEvent("RMI getTrackState() → version "
                    + station.getReplica().getVersion());
            return new TreeMap<>(station.getReplica().snapshot());
        }

        @Override
        public MessageReply deliverMessage(String sender, int lamportTimestamp, String text)
                throws RemoteException {
            requireAlive();
            int before = station.getLamportClock();
            int received = station.printReceivedEvent(lamportTimestamp,
                    "RMI message from " + sender + ": \"" + text + "\" (msg L="
                            + lamportTimestamp + ") → L = max(" + before + ", "
                            + lamportTimestamp + ") + 1");
            int reply = station.printEvent("REPLY → " + sender + ": ACK \"" + text + "\"");
            return new MessageReply(station.getStationName(), received, reply);
        }
    }

    // =========================================================
    // REMOTE OBJECT: operator console
    // =========================================================

    static final class ControlServant implements StationRemote.Control {

        private final StationRmiServer server;
        private final List<RailwayStation> stations;

        ControlServant(StationRmiServer server, List<RailwayStation> stations) {
            this.server = server;
            this.stations = stations;
        }

        private RailwayStation find(String name) throws RemoteException {
            return stations.stream().filter(s -> s.getStationName().equals(name)).findFirst()
                    .orElseThrow(() -> new RemoteException("Unknown station " + name));
        }

        @Override
        public List<String> stationNames() {
            return stations.stream().map(RailwayStation::getStationName).toList();
        }

        @Override
        public String failStation(String name) throws RemoteException {
            RailwayStation station = find(name);
            if (station.isActive()) {
                station.failNode();
            }
            server.withdraw(name);
            return name + " crashed: remote object withdrawn";
        }

        @Override
        public String recoverStation(String name) throws RemoteException {
            RailwayStation station = find(name);
            if (!station.isActive()) {
                station.recoverNode();
            }
            server.publish(station);
            return name + " recovered: re-bound at " + server.url(name);
        }
    }

    // =========================================================
    // STANDALONE SERVER
    // =========================================================

    /** Creates the four stations and publishes them; runs until stopped. */
    public static StationRmiServer startRailwayNetwork(int preferredPort,
                                                       List<RailwayStation> stations)
            throws RemoteException {

        StationRmiServer server = start(preferredPort);

        // Track state version 1, replicated from the coordinator STATION-04
        RailwayStation coordinator = stations.get(stations.size() - 1);
        coordinator.setCoordinator(true);
        ReplicationManager replication = new ReplicationManager(coordinator,
                List.of(stations.get(0), stations.get(1)));
        Map<String, TrackStateReplica.TrackStatus> state = new LinkedHashMap<>();
        for (String track : RailwaySystem.TRACKS) {
            state.put(track, TrackStateReplica.TrackStatus.clear());
        }
        state.put("TRACK-05", TrackStateReplica.TrackStatus.occupied("TRAIN-099"));
        replication.initializeState(state);

        Terminal.blank();
        for (RailwayStation station : stations) {
            server.publish(station);
            Terminal.line("  bound " + server.url(station.getStationName()));
        }
        server.publishControl(stations);
        Terminal.line("  bound " + server.url(CONTROL_NAME));
        return server;
    }

    public static void main(String[] args) throws Exception {

        Terminal.banner("RAILWAY STATION SERVER (Java RMI)");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        List<RailwayStation> stations = List.of(
                new RailwayStation("STATION-01", 1), new RailwayStation("STATION-02", 2),
                new RailwayStation("STATION-03", 3), new RailwayStation("STATION-04", 4));

        StationRmiServer server = startRailwayNetwork(port, stations);
        Terminal.blank();
        Terminal.line("Registry listening on port " + server.getPort() + ". Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
