import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Experiments 1 and 2: Java RMI calls and thread-safe shared state. */
class RmiAndThreadsTest {

    @Test
    void remoteCallsWorkAndACrashedStationThrowsRemoteException() throws Exception {
        TestNetwork net = new TestNetwork();
        StationRmiServer server = StationRmiServer.startRailwayNetwork(21099, net.all);
        try {
            StationRemote stub = server.lookup("STATION-02");
            StationRemote.PingReply reply = stub.ping();
            assertEquals("STATION-02", reply.station());
            assertEquals("ALIVE", reply.status());
            assertTrue(reply.servedBy().startsWith("RMI"), reply.servedBy());

            // the track state is copied by value through serialization
            assertEquals(5, server.lookup("STATION-04").getTrackState().size());

            // Lamport receive rule applied on the server side
            int before = stub.getLamportClock();
            StationRemote.MessageReply message = stub.deliverMessage("TEST", before + 50, "hello");
            assertEquals(before + 51, message.receiveTimestamp());

            // crash: the old stub stops working
            StationRemote stale = server.lookup("STATION-03");
            net.s3.failNode();
            server.withdraw("STATION-03");
            assertThrows(RemoteException.class, stale::ping);

            // recovery: a new lookup gives a working stub
            net.s3.recoverNode();
            server.publish(net.s3);
            assertEquals("ALIVE", server.lookup("STATION-03").ping().status());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void platformIsNeverGivenToTwoTrains() throws Exception {
        RailwayMultithreadingDemo.PlatformTable table = new RailwayMultithreadingDemo.PlatformTable(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        // 8 trains all want platform 1 at the same moment
        for (int i = 0; i < 8; i++) {
            String train = "TRAIN-" + i;
            pool.submit(() -> {
                start.await();
                results.add(table.assign(train, 1));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Set<Integer> distinct = new HashSet<>(results);
        assertEquals(8, results.size());
        assertEquals(8, distinct.size(), "a platform was double-booked: " + results);
    }

    @Test
    void stationEventLogIsSafeUnderConcurrentWrites() throws Exception {
        RailwayStation station = new RailwayStation("STATION-01", 1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        Set<Integer> timestamps = ConcurrentHashMap.newKeySet();
        for (int t = 0; t < 6; t++) {
            pool.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    int ts = station.sendLamportTimestamp();
                    timestamps.add(ts);
                    station.recordEvent(ts, "TEST_EVENT", "a", "b", "c");
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(3000, station.getEventCount());
        // the clock lock hands out every timestamp exactly once
        assertEquals(3000, timestamps.size());
        assertEquals(3000, station.getLamportClock());
    }
}
