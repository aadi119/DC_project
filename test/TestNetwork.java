import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the same four-station network the demos use, for the tests. */
final class TestNetwork {

    final RailwayStation s1 = new RailwayStation("STATION-01", 1);
    final RailwayStation s2 = new RailwayStation("STATION-02", 2);
    final RailwayStation s3 = new RailwayStation("STATION-03", 3);
    final RailwayStation s4 = new RailwayStation("STATION-04", 4);
    final List<RailwayStation> all = List.of(s1, s2, s3, s4);

    /** STATION-04 primary, STATION-01 and STATION-02 backups, state version 1. */
    ReplicationManager replicated() {
        s4.setCoordinator(true);
        ReplicationManager replication = new ReplicationManager(s4, List.of(s1, s2));
        replication.initializeState(initialState());
        return replication;
    }

    static Map<String, TrackStateReplica.TrackStatus> initialState() {
        Map<String, TrackStateReplica.TrackStatus> state = new LinkedHashMap<>();
        for (String track : RailwaySystem.TRACKS) {
            state.put(track, TrackStateReplica.TrackStatus.clear());
        }
        state.put("TRACK-05", TrackStateReplica.TrackStatus.occupied("TRAIN-099"));
        return state;
    }

    static TrainRequest arrival(int sequence, String train, String track) {
        return new TrainRequest(sequence, train, TrainRequest.Movement.ARRIVAL, track);
    }

    static TrainRequest departure(int sequence, String train, String track) {
        return new TrainRequest(sequence, train, TrainRequest.Movement.DEPARTURE, track);
    }
}
