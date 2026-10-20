import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

/**
 * Live updates for the web dashboard with Server-Sent Events (SSE).
 *
 * GET /api/stream keeps the HTTP response open; the server pushes
 *
 *   event: state   the full system state, whenever it changes
 *   event: feed    new station events and console lines
 *
 * A request thread only sends the headers and registers the connection;
 * one dedicated "live-stream" thread writes to every open connection. So
 * open connections never occupy the server's request thread pool.
 * Browsers reconnect automatically (for example after a server restart).
 */
public final class LiveStream {

    private static final int MAX_CLIENTS = 32;
    private static final long PUSH_INTERVAL_MS = 250;
    private static final long KEEPALIVE_MS = 15_000;

    private record Client(HttpExchange exchange, OutputStream out) {
    }

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Supplier<Map<String, Object>> state;
    private final Gson gson;

    private String lastStateJson = "";
    private long lastSeq = EventFeed.lastSeq();
    private long lastWrite = System.currentTimeMillis();

    public LiveStream(Supplier<Map<String, Object>> state, Gson gson) {
        this.state = state;
        this.gson = gson;
        Thread pusher = new Thread(this::run, "live-stream");
        pusher.setDaemon(true);
        pusher.start();
    }

    public int clientCount() {
        return clients.size();
    }

    /** Called on a request thread: opens the stream and returns at once. */
    public void open(HttpExchange exchange) throws IOException {
        if (clients.size() >= MAX_CLIENTS) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);   // 0 = chunked, open-ended body

        OutputStream out = exchange.getResponseBody();
        Client client = new Client(exchange, out);
        String first = gson.toJson(state.get());
        if (write(client, "retry: 2000\n\n" + frame("state", first))) {
            clients.add(client);
        }
    }

    private static String frame(String event, String json) {
        return "event: " + event + "\ndata: " + json + "\n\n";
    }

    private boolean write(Client client, String text) {
        try {
            client.out().write(text.getBytes(StandardCharsets.UTF_8));
            client.out().flush();
            return true;
        } catch (IOException e) {
            clients.remove(client);
            client.exchange().close();
            return false;
        }
    }

    private void run() {
        while (true) {
            try {
                Thread.sleep(PUSH_INTERVAL_MS);
                if (clients.isEmpty()) {
                    lastSeq = EventFeed.lastSeq();
                    continue;
                }
                List<String> frames = new ArrayList<>();

                List<EventFeed.Entry> entries = EventFeed.since(lastSeq);
                if (!entries.isEmpty()) {
                    lastSeq = entries.get(entries.size() - 1).seq();
                    frames.add(frame("feed", gson.toJson(Map.of("lastSeq", lastSeq, "entries", entries))));
                }

                String stateJson = gson.toJson(state.get());
                // serverTimeMs changes every time: compare the rest
                String comparable = stateJson.replaceAll("\"serverTimeMs\":\\d+", "");
                if (!comparable.equals(lastStateJson)) {
                    lastStateJson = comparable;
                    frames.add(frame("state", stateJson));
                }

                long now = System.currentTimeMillis();
                if (frames.isEmpty() && now - lastWrite > KEEPALIVE_MS) {
                    frames.add(": keep-alive\n\n");
                }
                if (!frames.isEmpty()) {
                    lastWrite = now;
                    String text = String.join("", frames);
                    for (Client client : clients) {
                        write(client, text);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // the state changed while it was being read: try again next tick
            }
        }
    }
}
