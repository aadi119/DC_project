import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import mpi.Intracomm;

/**
 * EXPERIMENTS 9-10 - helpers shared by the MPI programs.
 *
 * Each MPI process represents one railway station: rank 0 → STATION-01,
 * rank 1 → STATION-02, and so on. Rank 0 is the root (coordinator).
 *
 * MPI processes run at the same time, so their printed lines would mix in
 * a random order. inRankOrder() lets the processes print one after another
 * (rank 0, then 1, then 2 ...) using MPI barriers, giving readable output.
 */
public final class MpiStations {

    public static final int ROOT = 0;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // UTF-8 output so →, ✓ and ✗ print correctly
    private static final PrintStream OUT = new PrintStream(
            new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private MpiStations() {
    }

    public static String stationOf(int rank) {
        return String.format("STATION-%02d", rank + 1);
    }

    /** Line prefix: physical time, station and MPI rank. */
    public static String tag(int rank) {
        return "[" + LocalTime.now().format(TIME) + "] [" + stationOf(rank) + "] [rank " + rank + "] ";
    }

    public static void println(String text) {
        // MPJ Express drops completely empty lines when relaying output
        OUT.println(text.isEmpty() ? " " : text);
    }

    public static void banner(String... lines) {
        OUT.println(" ");
        OUT.println(Terminal.HEAVY_RULE);
        for (String line : lines) {
            OUT.println(line);
        }
        OUT.println(Terminal.HEAVY_RULE);
    }

    public static void section(String title) {
        OUT.println(" ");
        OUT.println(Terminal.LIGHT_RULE);
        OUT.println(title);
        OUT.println(Terminal.LIGHT_RULE);
    }

    /** Runs action on each rank in turn (0, 1, 2 ...), separated by barriers. */
    public static void inRankOrder(Intracomm comm, Runnable action) {

        int rank = comm.Rank();

        for (int turn = 0; turn < comm.Size(); turn++) {
            if (turn == rank) {
                action.run();
                OUT.flush();
            }
            comm.Barrier();
        }
    }
}
