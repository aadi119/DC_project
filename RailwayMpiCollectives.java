import java.util.Arrays;

import mpi.Intracomm;
import mpi.MPI;

/**
 * EXPERIMENT 9 - MPI Collective Communication: Broadcast, Scatter, Gather.
 *
 * Run with MPJ Express, one MPI process per railway station:
 *     java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMpiCollectives
 *
 * A collective operation involves EVERY process in the communicator:
 *
 *   BROADCAST (Bcast)  sends the exact same piece of data from one root
 *                      process to all other processes in the communicator.
 *                      → the coordinator shares the current railway track state.
 *   SCATTER            splits an array on the root into equal chunks and sends
 *                      a distinct chunk to each process, in rank order.
 *                      → the coordinator divides the train requests among stations.
 *   GATHER             collects the distinct chunks from all processes in rank
 *                      order and assembles them into a single buffer on the
 *                      root. It is the inverse of Scatter.
 *                      → the coordinator collects the processed schedule.
 *
 * All the data travels through real MPI calls; nothing is shared directly.
 */
public class RailwayMpiCollectives {

    // ---------------- broadcast data ----------------
    // [version, TRACK-01 .. TRACK-05]; 0 = CLEAR, otherwise the train number on it
    private static final int STATE_SIZE = 6;

    // ---------------- scatter data ----------------
    // One train request = [train, track, distance km, speed km/h]
    private static final int REQUEST_FIELDS = 4;
    private static final int[][] TRAIN_REQUESTS = {
            {201, 1, 150, 100},
            {202, 3, 80, 60},
            {203, 2, 210, 120},
            {204, 5, 45, 45},
            {205, 4, 300, 150},
            {206, 3, 90, 72},
            {207, 2, 64, 48},
            {208, 5, 175, 105},
    };

    // ---------------- gather data ----------------
    // One result = [train, track, processed-by rank, travel minutes, decision]
    private static final int RESULT_FIELDS = 5;
    private static final int ACCEPT = 1;
    private static final int HOLD = 0;

    public static void main(String[] args) throws Exception {

        MPI.Init(args);

        Intracomm world = MPI.COMM_WORLD;
        int rank = world.Rank();
        int size = world.Size();

        if (TRAIN_REQUESTS.length % size != 0) {
            if (rank == MpiStations.ROOT) {
                MpiStations.println("✗ " + TRAIN_REQUESTS.length
                        + " train requests cannot be split evenly over " + size + " processes");
            }
            MPI.Finalize();
            return;
        }

        if (rank == MpiStations.ROOT) {
            printIntroduction(size);
        }
        world.Barrier();

        int[] trackState = broadcastTrackState(world, rank, size);
        int[] myRequests = scatterTrainRequests(world, rank, size);
        int[] myResults = processRequests(world, rank, myRequests, trackState);
        gatherResults(world, rank, size, myResults);

        MPI.Finalize();
    }

    private static void printIntroduction(int size) {

        MpiStations.banner(
                "EXPERIMENT 9 - MPI COLLECTIVE COMMUNICATION",
                "Broadcast · Scatter · Gather (MPJ Express)"
        );
        MpiStations.println("");
        MpiStations.println("MPI processes started: " + size + "  (one process per railway station)");
        for (int r = 0; r < size; r++) {
            MpiStations.println("  Rank " + r + " → " + MpiStations.stationOf(r)
                    + (r == MpiStations.ROOT ? "  (ROOT / coordinator)" : ""));
        }
    }

    // =========================================================
    // 1. BROADCAST
    // =========================================================

    private static int[] broadcastTrackState(Intracomm world, int rank, int size) {

        int[] state = new int[STATE_SIZE];

        if (rank == MpiStations.ROOT) {
            MpiStations.section("1. BROADCAST  (root → every process)   MPI.COMM_WORLD.Bcast");
            // Railway state held by the coordinator (final state of the replication experiments)
            state = new int[] {13, 0, 0, 110, 0, 0};
            MpiStations.println("Root " + MpiStations.stationOf(rank)
                    + " holds the railway track state and broadcasts it:");
            MpiStations.println("  " + describeState(state));
            MpiStations.println("");
        }

        world.Bcast(state, 0, STATE_SIZE, MPI.INT, MpiStations.ROOT);

        final int[] received = state;
        MpiStations.inRankOrder(world, () -> MpiStations.println(MpiStations.tag(rank)
                + "received state v" + received[0] + " " + Arrays.toString(
                        Arrays.copyOfRange(received, 1, STATE_SIZE)) + "  checksum " + checksum(received)));

        // Verify: gather every station's checksum at the root and compare them
        int[] myChecksum = {checksum(state)};
        int[] allChecksums = new int[size];
        world.Gather(myChecksum, 0, 1, MPI.INT, allChecksums, 0, 1, MPI.INT, MpiStations.ROOT);

        if (rank == MpiStations.ROOT) {
            boolean identical = Arrays.stream(allChecksums).allMatch(c -> c == allChecksums[0]);
            MpiStations.println("");
            MpiStations.println(identical
                    ? "✓ BROADCAST VERIFIED: all " + size + " stations hold the identical track state"
                    : "✗ BROADCAST FAILED: stations hold different states");
        }
        return state;
    }

    // =========================================================
    // 2. SCATTER
    // =========================================================

    private static int[] scatterTrainRequests(Intracomm world, int rank, int size) {

        int perStation = TRAIN_REQUESTS.length / size;
        int chunk = perStation * REQUEST_FIELDS;

        int[] all = new int[TRAIN_REQUESTS.length * REQUEST_FIELDS];

        if (rank == MpiStations.ROOT) {
            MpiStations.section("2. SCATTER  (root splits the data, one part per process)   MPI.COMM_WORLD.Scatter");
            MpiStations.println("Root holds " + TRAIN_REQUESTS.length + " train requests → "
                    + perStation + " per station:");
            MpiStations.println("");
            MpiStations.println("  Train      Track      Distance   Speed");
            for (int i = 0; i < TRAIN_REQUESTS.length; i++) {
                int[] r = TRAIN_REQUESTS[i];
                System.arraycopy(r, 0, all, i * REQUEST_FIELDS, REQUEST_FIELDS);
                MpiStations.println(String.format("  TRAIN-%d  TRACK-%02d   %4d km   %4d km/h",
                        r[0], r[1], r[2], r[3]));
            }
            MpiStations.println("");
        }

        int[] mine = new int[chunk];
        world.Scatter(all, 0, chunk, MPI.INT, mine, 0, chunk, MPI.INT, MpiStations.ROOT);

        MpiStations.inRankOrder(world, () -> {
            StringBuilder trains = new StringBuilder();
            for (int i = 0; i < perStation; i++) {
                trains.append(i == 0 ? "" : ", ").append("TRAIN-").append(mine[i * REQUEST_FIELDS]);
            }
            MpiStations.println(MpiStations.tag(rank) + "received " + perStation
                    + " requests → " + trains);
        });
        return mine;
    }

    // =========================================================
    // LOCAL PROCESSING (each process works on its own part)
    // =========================================================

    /**
     * Each station computes the travel time of its trains and checks the
     * requested track against the broadcast state (occupied → HOLD).
     */
    private static int[] processRequests(Intracomm world, int rank,
                                         int[] requests, int[] trackState) {

        int count = requests.length / REQUEST_FIELDS;
        int[] results = new int[count * RESULT_FIELDS];

        for (int i = 0; i < count; i++) {
            int train = requests[i * REQUEST_FIELDS];
            int track = requests[i * REQUEST_FIELDS + 1];
            int distance = requests[i * REQUEST_FIELDS + 2];
            int speed = requests[i * REQUEST_FIELDS + 3];

            int minutes = Math.round(distance * 60f / speed);
            int decision = trackState[track] == 0 ? ACCEPT : HOLD;

            int base = i * RESULT_FIELDS;
            results[base] = train;
            results[base + 1] = track;
            results[base + 2] = rank;
            results[base + 3] = minutes;
            results[base + 4] = decision;
        }

        if (rank == MpiStations.ROOT) {
            MpiStations.section("LOCAL PROCESSING  (every station works on its own requests in parallel)");
        }
        world.Barrier();

        MpiStations.inRankOrder(world, () -> {
            for (int i = 0; i < count; i++) {
                int base = i * RESULT_FIELDS;
                MpiStations.println(MpiStations.tag(rank) + "TRAIN-" + results[base]
                        + " → travel " + results[base + 3] + " min, TRACK-0" + results[base + 1]
                        + (results[base + 4] == ACCEPT
                                ? " CLEAR → ACCEPT"
                                : " OCCUPIED by TRAIN-" + trackState[results[base + 1]] + " → HOLD"));
            }
        });
        return results;
    }

    // =========================================================
    // 3. GATHER
    // =========================================================

    private static void gatherResults(Intracomm world, int rank, int size, int[] myResults) {

        int[] all = new int[myResults.length * size];

        if (rank == MpiStations.ROOT) {
            MpiStations.section("3. GATHER  (every process sends its part back to root)   MPI.COMM_WORLD.Gather");
        }

        world.Gather(myResults, 0, myResults.length, MPI.INT,
                all, 0, myResults.length, MPI.INT, MpiStations.ROOT);

        if (rank != MpiStations.ROOT) {
            return;
        }

        int total = all.length / RESULT_FIELDS;
        int accepted = 0;

        MpiStations.println("Root " + MpiStations.stationOf(rank)
                + " gathered " + total + " results (joined in rank order):");
        MpiStations.println("");
        MpiStations.println("  Train      Track      Processed by   Travel time   Decision");

        for (int i = 0; i < total; i++) {
            int base = i * RESULT_FIELDS;
            boolean accept = all[base + 4] == ACCEPT;
            accepted += accept ? 1 : 0;
            MpiStations.println(String.format("  TRAIN-%d  TRACK-%02d   %s     %3d min       %s",
                    all[base], all[base + 1], MpiStations.stationOf(all[base + 2]),
                    all[base + 3], accept ? "ACCEPT" : "HOLD (track occupied)"));
        }

        MpiStations.println("");
        MpiStations.println("Accepted: " + accepted + " | Held: " + (total - accepted));

        // The single buffer on the root, chunk by chunk
        int perStation = total / size;
        MpiStations.println("");
        MpiStations.println("Single buffer on root (one chunk per rank, in rank order):");
        StringBuilder layout = new StringBuilder("  [");
        for (int r = 0; r < size; r++) {
            layout.append(r == 0 ? " " : " | ").append("rank ").append(r).append(": ");
            for (int i = 0; i < perStation; i++) {
                layout.append(i == 0 ? "" : ", ")
                        .append(all[(r * perStation + i) * RESULT_FIELDS]);
            }
        }
        MpiStations.println(layout.append(" ]").toString());

        // Gather is the inverse of Scatter: chunk k must come from rank k and
        // the trains must be back in their original (scattered) order
        boolean inverseOfScatter = total == TRAIN_REQUESTS.length;
        for (int i = 0; i < total && inverseOfScatter; i++) {
            int base = i * RESULT_FIELDS;
            inverseOfScatter = all[base] == TRAIN_REQUESTS[i][0]
                    && all[base + 2] == i / perStation;
        }

        MpiStations.println("");
        MpiStations.println(inverseOfScatter
                ? "✓ GATHER VERIFIED: " + size + " chunks (" + total + " results) reassembled in rank order"
                        + " → original order restored (inverse of Scatter)"
                : "✗ GATHER FAILED: chunks are missing or out of rank order");

        MpiStations.banner("EXPERIMENT 9 COMPLETED → Broadcast ✓  Scatter ✓  Gather ✓");
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private static String describeState(int[] state) {
        StringBuilder text = new StringBuilder("Version " + state[0] + " |");
        for (int track = 1; track < STATE_SIZE; track++) {
            text.append(" TRACK-0").append(track).append(' ')
                    .append(state[track] == 0 ? "CLEAR" : "OCCUPIED(TRAIN-" + state[track] + ")");
        }
        return text.toString();
    }

    private static int checksum(int[] values) {
        return Arrays.hashCode(values);
    }
}
