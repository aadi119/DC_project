import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Experiment 6: health-aware round-robin. */
class LoadBalancerTest {

    @Test
    void healthyStationsShareRequestsEvenly() {
        TestNetwork net = new TestNetwork();
        LoadBalancer balancer = new LoadBalancer(net.all);

        for (int i = 1; i <= 8; i++) {
            balancer.route(TestNetwork.arrival(i, "TRAIN-" + i, "TRACK-01"));
        }
        assertEquals(Map.of("STATION-01", 2, "STATION-02", 2, "STATION-03", 2, "STATION-04", 2),
                balancer.getAssignmentCounts());
    }

    @Test
    void failedAndOverloadedStationsAreSkipped() {
        TestNetwork net = new TestNetwork();
        net.s2.failNode();
        net.s4.setOverloaded(true);
        LoadBalancer balancer = new LoadBalancer(net.all);

        for (int i = 1; i <= 6; i++) {
            LoadBalancer.Decision decision = balancer.route(TestNetwork.arrival(i, "TRAIN-" + i, "TRACK-01"));
            String chosen = decision.station().getStationName();
            assertTrue(chosen.equals("STATION-01") || chosen.equals("STATION-03"), chosen);
        }
        Map<String, Integer> counts = balancer.getAssignmentCounts();
        assertEquals(3, counts.get("STATION-01"));
        assertEquals(3, counts.get("STATION-03"));
        assertEquals(0, counts.get("STATION-02"));
        assertEquals(0, counts.get("STATION-04"));
    }

    @Test
    void skippedStationIsReportedWithItsReason() {
        TestNetwork net = new TestNetwork();
        net.s1.failNode();
        LoadBalancer balancer = new LoadBalancer(net.all);

        LoadBalancer.Decision decision = balancer.route(TestNetwork.arrival(1, "TRAIN-1", "TRACK-01"));
        assertEquals("STATION-02", decision.station().getStationName());
        assertEquals(java.util.List.of("STATION-01 [FAILED]"), decision.skipped());
    }

    @Test
    void noStationAvailableGivesAnUnassignedDecision() {
        TestNetwork net = new TestNetwork();
        net.all.forEach(RailwayStation::failNode);
        LoadBalancer balancer = new LoadBalancer(net.all);
        assertFalse(balancer.route(TestNetwork.arrival(1, "TRAIN-1", "TRACK-01")).assigned());
    }
}
