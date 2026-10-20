import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * EXPERIMENT 1 - client-server communication using Java RMI.
 *
 * This JVM is the SERVER: it starts an RMI registry and publishes the four
 * railway stations. It then launches the CLIENT (StationRmiClient) as a
 * separate JVM process, which finds the stations in the registry and calls
 * them remotely. Server-side events are printed with the station name and
 * Lamport time; client lines start with [CLIENT].
 */
public class RailwayRmiDemo {

    public static void main(String[] args) throws Exception {

        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Terminal.banner(
                "EXPERIMENT 1 - CLIENT-SERVER COMMUNICATION (Java RMI)",
                "Railway stations as remote objects, called from another JVM"
        );

        List<RailwayStation> stations = List.of(
                new RailwayStation("STATION-01", 1), new RailwayStation("STATION-02", 2),
                new RailwayStation("STATION-03", 3), new RailwayStation("STATION-04", 4));

        Terminal.section("SERVER: START RMI REGISTRY AND PUBLISH STATIONS (PID "
                + ProcessHandle.current().pid() + ")");
        StationRmiServer server = StationRmiServer.startRailwayNetwork(
                StationRmiServer.DEFAULT_PORT, stations);
        Terminal.blank();
        Terminal.line("Registry listening on port " + server.getPort());

        // Start the client in its own JVM: a real remote call between processes
        Terminal.blank();
        Terminal.line("Launching the client in a separate JVM...");

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process client = new ProcessBuilder(
                java.toString(),
                "-Dstdout.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "StationRmiClient", "127.0.0.1", String.valueOf(server.getPort()))
                .inheritIO()
                .start();
        int exitCode = client.waitFor();

        server.shutdown();

        Terminal.banner(exitCode == 0
                ? "EXPERIMENT 1 COMPLETED → client and server communicated through Java RMI"
                : "✗ CLIENT FAILED (exit code " + exitCode + ")");
    }
}
