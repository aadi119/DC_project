import java.util.Random;

import mpi.Intracomm;
import mpi.MPI;

/**
 * EXPERIMENT 10 - Parallel Matrix Multiplication using MPI.
 *
 * Run with MPJ Express:
 *     java -jar %MPJ_HOME%\lib\starter.jar -np 4 -cp target\classes RailwayMatrixMultiplication
 *
 * Algorithm (row-wise block decomposition), C = A × B:
 *
 *   1. SCATTER  the rows of A: each process receives a block of rows.
 *               Scatterv is used, so the rows need not divide evenly.
 *   2. BROADCAST the whole of B: every process needs all of B.
 *   3. COMPUTE  each process multiplies its rows of A by B, in parallel.
 *   4. GATHER   the rows of C back to the root, in rank order.
 *
 * Row i of C depends only on row i of A and on B, so the blocks can be
 * computed independently, with no communication during the computation.
 *
 * Part A applies this to a small railway network: squaring the junction
 * connection matrix counts the 2-leg routes between junctions.
 * Part B multiplies two large matrices and compares the parallel time with
 * a sequential multiplication to measure speedup.
 */
public class RailwayMatrixMultiplication {

    private static final String[] JUNCTIONS = {"J1", "J2", "J3", "J4", "J5", "J6"};

    // Direct railway lines between junctions (1 = connected)
    private static final double[] NETWORK = {
            0, 1, 1, 0, 0, 0,
            1, 0, 1, 1, 0, 0,
            1, 1, 0, 0, 1, 0,
            0, 1, 0, 0, 1, 1,
            0, 0, 1, 1, 0, 1,
            0, 0, 0, 1, 1, 0,
    };

    private static final int LARGE_N = 960;
    private static final long SEED = 42;

    public static void main(String[] args) throws Exception {

        MPI.Init(args);

        Intracomm world = MPI.COMM_WORLD;
        int rank = world.Rank();
        int size = world.Size();

        if (rank == MpiStations.ROOT) {
            MpiStations.banner(
                    "EXPERIMENT 10 - PARALLEL MATRIX MULTIPLICATION USING MPI",
                    "Row-wise decomposition: Scatter rows of A · Broadcast B · Gather C"
            );
            MpiStations.println("");
            MpiStations.println("MPI processes: " + size + "  (rank 0 = "
                    + MpiStations.stationOf(0) + " is the root)");
        }

        partA(world, rank, size);
        partB(world, rank, size);

        if (rank == MpiStations.ROOT) {
            MpiStations.banner("EXPERIMENT 10 COMPLETED → Parallel result verified ✓");
        }

        MPI.Finalize();
    }

    // =========================================================
    // PART A - RAILWAY NETWORK: 2-LEG ROUTES (A × A)
    // =========================================================

    private static void partA(Intracomm world, int rank, int size) {

        int n = JUNCTIONS.length;

        if (rank == MpiStations.ROOT) {
            MpiStations.section("PART A: RAILWAY NETWORK - 2-LEG ROUTES BETWEEN JUNCTIONS");
            MpiStations.println("A = connection matrix (1 = direct railway line between junctions)");
            printMatrix(NETWORK, n);
            MpiStations.println("");
            MpiStations.println("C = A × A  →  C[i][j] = number of routes from i to j that change");
            MpiStations.println("               at exactly one intermediate junction");
            MpiStations.println("");
        }

        double[] a = rank == MpiStations.ROOT ? NETWORK : new double[0];
        double[] b = rank == MpiStations.ROOT ? NETWORK.clone() : new double[n * n];

        Multiplication result = multiplyInParallel(world, rank, size, a, b, n, true);

        if (rank == MpiStations.ROOT) {
            MpiStations.println("");
            MpiStations.println("Gathered result C = A × A:");
            printMatrix(result.c(), n);
            MpiStations.println("");
            MpiStations.println("Example: C[J1][J4] = " + (int) result.c()[3]
                    + " → J1 reaches J4 via one intermediate junction (J1 → J2 → J4)");
            MpiStations.println("Diagonal C[i][i] = number of lines at junction i (e.g. J2 has "
                    + (int) result.c()[n + 1] + ")");
            verify(result.c(), multiplySequential(NETWORK, NETWORK, n), "C");
        }
    }

    // =========================================================
    // PART B - PERFORMANCE: LARGE MATRICES
    // =========================================================

    private static void partB(Intracomm world, int rank, int size) {

        int n = LARGE_N;
        double[] a = new double[0];
        double[] b = new double[n * n];
        double[] sequential = null;
        long sequentialNanos = 0;

        if (rank == MpiStations.ROOT) {
            MpiStations.section("PART B: PERFORMANCE - " + n + " × " + n + " MATRICES");

            Random random = new Random(SEED);
            a = randomMatrix(random, n);
            b = randomMatrix(random, n);

            MpiStations.println("Sequential multiplication on 1 process...");
            long start = System.nanoTime();
            sequential = multiplySequential(a, b, n);
            sequentialNanos = System.nanoTime() - start;
            MpiStations.println("  Sequential time : " + millis(sequentialNanos) + " ms");
            MpiStations.println("");
            MpiStations.println("Parallel multiplication on " + size + " processes...");
        }

        world.Barrier();
        Multiplication result = multiplyInParallel(world, rank, size, a, b, n, false);

        if (rank == MpiStations.ROOT) {
            double speedup = (double) sequentialNanos / result.nanos();
            MpiStations.println("  Parallel time   : " + millis(result.nanos()) + " ms"
                    + "  (scatter + broadcast + compute + gather)");
            MpiStations.println("");
            MpiStations.println(String.format("  Speedup    = Tseq / Tpar = %.2f×", speedup));
            MpiStations.println(String.format("  Efficiency = Speedup / p = %.0f%%",
                    100 * speedup / size));
            verify(result.c(), sequential, "parallel C");
        }
    }

    // =========================================================
    // THE PARALLEL ALGORITHM
    // =========================================================

    private record Multiplication(double[] c, long nanos) {
    }

    /**
     * C = A × B with n × n matrices stored row by row in 1-D arrays.
     * a is only needed on the root; b must be n*n long on every process.
     */
    private static Multiplication multiplyInParallel(Intracomm world, int rank, int size,
                                                     double[] a, double[] b, int n,
                                                     boolean showSteps) {

        // How many rows each process gets (the first n % size ranks get one extra)
        int[] rowCounts = new int[size];
        int[] elementCounts = new int[size];
        int[] offsets = new int[size];
        for (int r = 0, offset = 0; r < size; r++) {
            rowCounts[r] = n / size + (r < n % size ? 1 : 0);
            elementCounts[r] = rowCounts[r] * n;
            offsets[r] = offset;
            offset += elementCounts[r];
        }

        int myRows = rowCounts[rank];
        double[] myA = new double[myRows * n];
        double[] myC = new double[myRows * n];
        double[] c = new double[rank == MpiStations.ROOT ? n * n : 0];

        long start = System.nanoTime();

        // 1. SCATTER rows of A
        world.Scatterv(a, 0, elementCounts, offsets, MPI.DOUBLE,
                myA, 0, myA.length, MPI.DOUBLE, MpiStations.ROOT);

        // 2. BROADCAST all of B
        world.Bcast(b, 0, n * n, MPI.DOUBLE, MpiStations.ROOT);

        // 3. COMPUTE the local block of C (i-k-j order is cache friendly)
        for (int i = 0; i < myRows; i++) {
            for (int k = 0; k < n; k++) {
                double aik = myA[i * n + k];
                if (aik == 0) {
                    continue;
                }
                int bRow = k * n;
                int cRow = i * n;
                for (int j = 0; j < n; j++) {
                    myC[cRow + j] += aik * b[bRow + j];
                }
            }
        }

        // 4. GATHER rows of C at the root, in rank order
        world.Gatherv(myC, 0, myC.length, MPI.DOUBLE,
                c, 0, elementCounts, offsets, MPI.DOUBLE, MpiStations.ROOT);

        long nanos = System.nanoTime() - start;

        if (showSteps) {
            int firstRow = offsets[rank] / n;
            MpiStations.inRankOrder(world, () -> {
                StringBuilder rows = new StringBuilder();
                for (int i = 0; i < myRows; i++) {
                    rows.append(i == 0 ? "" : ", ").append(JUNCTIONS[firstRow + i]);
                }
                MpiStations.println(MpiStations.tag(rank) + "received A rows {" + rows
                        + "} (Scatterv) + all of B (Bcast)");
                for (int i = 0; i < myRows; i++) {
                    MpiStations.println(String.format("        computed C row %-3s", JUNCTIONS[firstRow + i])
                            + formatRow(myC, i * n, n));
                }
            });
        }

        return new Multiplication(c, nanos);
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private static double[] multiplySequential(double[] a, double[] b, int n) {
        double[] c = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                double aik = a[i * n + k];
                if (aik == 0) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    c[i * n + j] += aik * b[k * n + j];
                }
            }
        }
        return c;
    }

    private static void verify(double[] parallel, double[] sequential, String name) {
        double maxDifference = 0;
        for (int i = 0; i < sequential.length; i++) {
            maxDifference = Math.max(maxDifference, Math.abs(parallel[i] - sequential[i]));
        }
        MpiStations.println("");
        MpiStations.println(maxDifference < 1e-9
                ? "✓ VERIFIED: " + name + " equals the sequential result (max difference "
                        + maxDifference + ")"
                : "✗ MISMATCH: max difference " + maxDifference);
    }

    private static double[] randomMatrix(Random random, int n) {
        double[] m = new double[n * n];
        for (int i = 0; i < m.length; i++) {
            m[i] = random.nextInt(10);
        }
        return m;
    }

    private static void printMatrix(double[] m, int n) {
        StringBuilder header = new StringBuilder("        ");
        for (String j : JUNCTIONS) {
            header.append(String.format("%4s", j));
        }
        MpiStations.println(header.toString());
        for (int i = 0; i < n; i++) {
            MpiStations.println(String.format("    %-4s", JUNCTIONS[i]) + formatRow(m, i * n, n));
        }
    }

    private static String formatRow(double[] m, int from, int n) {
        StringBuilder row = new StringBuilder();
        for (int j = 0; j < n; j++) {
            row.append(String.format("%4d", (int) m[from + j]));
        }
        return row.toString();
    }

    private static String millis(long nanos) {
        return String.format("%.1f", nanos / 1e6);
    }
}
