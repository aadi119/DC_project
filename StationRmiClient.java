import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;
import java.util.Map;

/**
 * EXPERIMENT 1 - client of the railway stations (Java RMI).
 *
 * Runs in its own JVM. It finds the stations through the RMI registry and
 * calls them through stubs: each call is a remote procedure call over TCP.
 * The client keeps its own Lamport clock, so the messages it exchanges
 * with the stations are ordered as in Experiment 3.
 */
public class StationRmiClient {

    private static final String CLIENT = "CONTROL-CENTRE";

    private final Registry registry;
    private final String host;
    private final int port;

    private int lamportClock = 0;
    private int calls = 0;
    private int failures = 0;
    private long totalMicros = 0;

    public StationRmiClient(String host, int port) throws RemoteException {
        this.host = host;
        this.port = port;
        this.registry = LocateRegistry.getRegistry(host, port);
    }

    public static void main(String[] args) throws Exception {

        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : StationRmiServer.DEFAULT_PORT;

        new StationRmiClient(host, port).runDemo();
    }

    private static void say(String text) {
        System.out.println("[CLIENT] " + text);
    }

    private StationRemote lookup(String name) throws Exception {
        return (StationRemote) registry.lookup(name);
    }

    /** Times one remote call and counts it. */
    private <T> T call(String description, RemoteCall<T> remoteCall) throws RemoteException {
        say("→ " + description);
        long start = System.nanoTime();
        calls++;
        try {
            T result = remoteCall.invoke();
            long micros = (System.nanoTime() - start) / 1000;
            totalMicros += micros;
            say("← reply in " + String.format("%.2f", micros / 1000.0) + " ms");
            return result;
        } catch (RemoteException e) {
            failures++;
            throw e;
        }
    }

    private interface RemoteCall<T> {
        T invoke() throws RemoteException;
    }

    public void runDemo() throws Exception {

        System.out.println();
        System.out.println(Terminal.HEAVY_RULE);
        System.out.println("RAILWAY CONTROL CENTRE (Java RMI client, separate JVM, PID "
                + ProcessHandle.current().pid() + ")");
        System.out.println(Terminal.HEAVY_RULE);

        // ---------------- STEP 1 ----------------
        Terminal.section("STEP 1: LOOK UP THE STATIONS IN THE RMI REGISTRY");
        String[] names = registry.list();
        Arrays.sort(names);
        say("Registry rmi://" + host + ":" + port + " lists " + names.length + " remote objects:");
        for (String name : names) {
            say("   " + name);
        }

        // ---------------- STEP 2 ----------------
        Terminal.section("STEP 2: REMOTE CALL ping() ON EVERY STATION");
        for (String name : names) {
            if (name.equals(StationRmiServer.CONTROL_NAME)) {
                continue;
            }
            StationRemote station = lookup(name);
            StationRemote.PingReply reply = call(name + ".ping()", station::ping);
            say("  " + reply.station() + " : " + reply.status() + "   L=" + reply.lamport()
                    + "   served by server thread \"" + reply.servedBy() + "\"");
            System.out.println();
        }

        // ---------------- STEP 3 ----------------
        Terminal.section("STEP 3: READ THE REPLICATED TRACK STATE REMOTELY");
        StationRemote primary = lookup("STATION-04");
        Map<String, TrackStateReplica.TrackStatus> tracks =
                call("STATION-04.getTrackState()", primary::getTrackState);
        long version = primary.getStateVersion();
        say("Track state (version " + version + ") returned by value (serialized):");
        tracks.forEach((track, status) -> say("   " + track + " : " + status));

        // ---------------- STEP 4 ----------------
        Terminal.section("STEP 4: MESSAGE WITH LAMPORT TIMESTAMP (RPC + logical clock)");
        StationRemote station2 = lookup("STATION-02");
        lamportClock++;
        int sendTimestamp = lamportClock;
        say("SEND \"TRAIN-201 requests TRACK-03\" to STATION-02 with L=" + sendTimestamp);
        StationRemote.MessageReply reply = call("STATION-02.deliverMessage(...)",
                () -> station2.deliverMessage(CLIENT, sendTimestamp, "TRAIN-201 requests TRACK-03"));
        int before = lamportClock;
        lamportClock = Math.max(lamportClock, reply.replyTimestamp()) + 1;
        say("RECEIVE ACK (msg L=" + reply.replyTimestamp() + ") → client L = max("
                + before + ", " + reply.replyTimestamp() + ") + 1 = " + lamportClock);

        // ---------------- STEP 5 ----------------
        Terminal.section("STEP 5: A STATION CRASHES → THE CALL FAILS WITH RemoteException");
        StationRemote.Control control = (StationRemote.Control) registry.lookup(StationRmiServer.CONTROL_NAME);
        StationRemote station3 = lookup("STATION-03");
        say("Operator: " + control.failStation("STATION-03"));
        try {
            call("STATION-03.ping()", station3::ping);
            say("✗ unexpected reply from a crashed station");
        } catch (RemoteException e) {
            say("✗ " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            say("  STATION-03 is unreachable → client fails over to STATION-02");
            StationRemote.PingReply fallback = call("STATION-02.ping()", station2::ping);
            say("  " + fallback.station() + " : " + fallback.status() + "   L=" + fallback.lamport());
        }

        // ---------------- STEP 6 ----------------
        Terminal.section("STEP 6: STATION RECOVERS → NEW LOOKUP, CALLS WORK AGAIN");
        say("Operator: " + control.recoverStation("STATION-03"));
        StationRemote recovered = lookup("STATION-03");
        StationRemote.PingReply again = call("STATION-03.ping()", recovered::ping);
        say("  " + again.station() + " : " + again.status() + "   L=" + again.lamport());

        // ---------------- SUMMARY ----------------
        Terminal.section("CLIENT SUMMARY");
        int succeeded = calls - failures;
        say("Remote calls made       : " + calls);
        say("Succeeded               : " + succeeded);
        say("Failed (RemoteException): " + failures);
        say("Average round trip      : "
                + String.format("%.2f", succeeded == 0 ? 0 : totalMicros / 1000.0 / succeeded) + " ms");
        say("Client Lamport clock    : L=" + lamportClock);
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
