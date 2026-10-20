import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Web dashboard for the Distributed Railway Coordination System.
 *
 *   mvn -q compile exec:exec -Dmain.class=RailwayWebServer
 *   → open http://localhost:8080
 *
 * The server itself is an example of Experiment 1 (client-server) and
 * Experiment 2 (multithreading): the browser is the client, every request
 * is handled on a thread from a fixed pool, and each API call invokes the
 * real railway code in RailwaySystem.
 */
public final class RailwayWebServer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final RailwaySystem system;
    private final Path webRoot;

    private RailwayWebServer(RailwaySystem system, Path webRoot) {
        this.system = system;
        this.webRoot = webRoot;
    }

    public static void main(String[] args) throws IOException {

        // Every printed line also goes to the live feed shown in the browser
        PrintStream terminal = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new ConsoleCapture(terminal), true, StandardCharsets.UTF_8));

        int preferredPort = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path webRoot = Path.of("web").toAbsolutePath();

        stopWhenLauncherExits();

        RailwaySystem system = new RailwaySystem();
        RailwayWebServer server = new RailwayWebServer(system, webRoot);

        // Use the preferred port, or the next free one if it is taken
        HttpServer http = null;
        int port = preferredPort;
        for (; port < preferredPort + 10 && http == null; port++) {
            try {
                http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            } catch (java.net.BindException e) {
                System.out.println("⚠ Port " + port + " is already in use, trying " + (port + 1));
            }
        }
        if (http == null) {
            System.out.println("✗ No free port between " + preferredPort + " and " + (port - 1)
                    + ". Close the other server and try again.");
            System.exit(1);
        }
        port--; // the loop advanced past the port that worked

        AtomicInteger threadNumber = new AtomicInteger(1);
        http.setExecutor(Executors.newFixedThreadPool(8, task -> {
            Thread t = new Thread(task, "http-worker-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/api/", server::handleApi);
        http.createContext("/", server::handleStatic);
        http.start();

        Terminal.banner("RAILNET OPS · RAILWAY COORDINATION",
                "Open http://localhost:" + port + " in your browser",
                "Press Ctrl+C to stop");

        // Open the dashboard automatically (set NO_BROWSER=1 to skip)
        if (System.getenv("NO_BROWSER") == null && java.awt.Desktop.isDesktopSupported()) {
            try {
                java.awt.Desktop.getDesktop().browse(URI.create("http://localhost:" + port));
            } catch (Exception e) {
                System.out.println("(Could not open a browser automatically: " + e.getMessage() + ")");
            }
        }
    }

    /**
     * When started through Maven, pressing Ctrl+C can end Maven but leave this
     * server running in the background (still holding the port). Watch the
     * launching process and shut down as soon as it is gone.
     */
    private static void stopWhenLauncherExits() {
        ProcessHandle.current().parent().ifPresent(launcher -> {
            Thread watcher = new Thread(() -> {
                while (launcher.isAlive()) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                System.exit(0);
            }, "launcher-watch");
            watcher.setDaemon(true);
            watcher.start();
        });
    }

    // =========================================================
    // API
    // =========================================================

    private void handleApi(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            JsonObject body = "POST".equals(method) ? readBody(exchange) : new JsonObject();

            Object result = route(method, path, exchange.getRequestURI(), body);
            send(exchange, 200, result);

        } catch (RailwaySystem.ActionException e) {
            send(exchange, 400, Map.of("error", e.getMessage()));
        } catch (NotFound e) {
            send(exchange, 404, Map.of("error", "Unknown endpoint " + e.getMessage()));
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            send(exchange, 500, Map.of("error", e.toString()));
        }
    }

    private static class NotFound extends RuntimeException {
        NotFound(String path) {
            super(path);
        }
    }

    private Object route(String method, String path, URI uri, JsonObject body) {

        if ("GET".equals(method)) {
            return switch (path) {
                case "/api/state" -> system.state();
                case "/api/events" -> events(uri);
                case "/api/threads" -> system.threads();
                default -> throw new NotFound(path);
            };
        }

        if (!"POST".equals(method)) {
            throw new NotFound(method + " " + path);
        }

        // /api/stations/{name}/{fail|recover|overload}
        if (path.startsWith("/api/stations/")) {
            String[] parts = path.split("/");
            if (parts.length == 5) {
                return switch (parts[4]) {
                    case "fail" -> system.failStation(parts[3]);
                    case "recover" -> system.recoverStation(parts[3]);
                    case "overload" -> system.toggleOverload(parts[3]);
                    default -> throw new NotFound(path);
                };
            }
        }

        return switch (path) {
            case "/api/reset" -> system.reset();
            case "/api/heartbeat" -> system.heartbeatRound();
            case "/api/rpc" -> system.rpc(str(body, "method"), str(body, "station"),
                    body.has("id") ? body.get("id").getAsInt() : 1);
            case "/api/lamport/local" -> system.lamportLocal(str(body, "station"),
                    orDefault(str(body, "label"), "local event"));
            case "/api/lamport/message" -> system.lamportMessage(str(body, "from"), str(body, "to"),
                    orDefault(str(body, "label"), "message"));
            case "/api/clocks/cristian" -> system.clockCristian(str(body, "station"));
            case "/api/clocks/berkeley" -> system.clockBerkeley();
            case "/api/clocks/disturb" -> system.clockDisturb();
            case "/api/election" -> system.election(str(body, "algorithm"));
            case "/api/replication/update" -> system.replicationUpdate(str(body, "track"),
                    str(body, "status"), str(body, "train"));
            case "/api/replication/verify" -> system.verifyConsistency();
            case "/api/dispatch/demo" -> system.dispatchDemoBatch();
            case "/api/dispatch" -> system.dispatch(requests(body));
            case "/api/analytics" -> system.runAnalytics();
            case "/api/fault/request" -> system.clientRequest(str(body, "train"), str(body, "movement"),
                    str(body, "track"), body.has("crash") && body.get("crash").getAsBoolean());
            case "/api/fault/detect" -> system.detectFailure();
            case "/api/fault/retry" -> system.retryPending();
            case "/api/mpi/collectives" -> system.runMpiCollectives(integer(body, "processes", 4));
            case "/api/mpi/matrix" -> system.runMpiMatrix(integer(body, "processes", 4),
                    integer(body, "n", 960));
            default -> throw new NotFound(path);
        };
    }

    private Object events(URI uri) {
        long since = 0;
        String query = uri.getQuery();
        if (query != null && query.startsWith("since=")) {
            since = Long.parseLong(query.substring(6));
        }
        List<EventFeed.Entry> entries = EventFeed.since(since);
        if (entries.size() > 600) {
            entries = entries.subList(entries.size() - 600, entries.size());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lastSeq", EventFeed.lastSeq());
        result.put("entries", entries);
        return result;
    }

    private static List<String[]> requests(JsonObject body) {
        List<String[]> list = new ArrayList<>();
        JsonArray array = body.has("requests") ? body.getAsJsonArray("requests") : new JsonArray();
        for (JsonElement element : array) {
            JsonObject r = element.getAsJsonObject();
            list.add(new String[] {str(r, "train"), str(r, "movement"), str(r, "track")});
        }
        return list;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new JsonObject();
        }
        JsonElement element = JsonParser.parseString(text);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // =========================================================
    // STATIC FILES (the single-page site in web/)
    // =========================================================

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        Path file = webRoot.resolve(path.substring(1)).normalize();

        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            byte[] notFound = "Not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, notFound.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(notFound);
            }
            return;
        }

        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png"
                : "application/octet-stream";

        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // =========================================================
    // CONSOLE CAPTURE
    // =========================================================

    /** Writes to the real terminal and sends each complete line to the event feed. */
    private static final class ConsoleCapture extends OutputStream {

        private final PrintStream terminal;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        ConsoleCapture(PrintStream terminal) {
            this.terminal = terminal;
        }

        @Override
        public synchronized void write(int b) {
            terminal.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                write(bytes[i]);
            }
        }

        @Override
        public synchronized void flush() {
            terminal.flush();
        }

        private void flushLine() {
            EventFeed.console(line.toString(StandardCharsets.UTF_8));
            line.reset();
        }
    }
}
