import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFlatMapFunction;

import scala.Tuple2;

/**
 * EXPERIMENT 7 - MapReduce with Apache Spark on the railway event logs.
 *
 * Input: the local event log of every station (one file per station),
 * produced by Experiments 4-6. Each line looks like
 *
 *     lamport|station|EVENT_TYPE|field1|field2|field3
 *     e.g.  41|STATION-02|TRACK_EVENT|TRACK-02|OCCUPIED|TRAIN-105
 *
 * Each station's log becomes one Spark partition, just as each HDFS block
 * becomes one input split in Hadoop. Spark runs the map function on every
 * partition in parallel, shuffles the (key, value) pairs so that equal keys
 * meet, and reduces each key's values to a total.
 *
 * Spark runs in local mode (local[4] = 4 executor threads), so no cluster
 * or network service is needed.
 */
public final class RailwayEventAnalytics {

    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\|");

    // Field positions in a log line
    private static final int STATION = 1;
    private static final int TYPE = 2;
    private static final int SUBJECT = 3;
    private static final int VALUE = 4;

    private static final int SAMPLE_TRACK_EVENTS = 4;

    private RailwayEventAnalytics() {
    }

    // =========================================================
    // MAP FUNCTIONS
    // A mapper emits zero or more (key, value) pairs per input line,
    // exactly like a Hadoop Mapper.
    // =========================================================

    /** Analysis 1: (trackId, 1) for every TRACK_EVENT. */
    static Iterator<Tuple2<String, Integer>> mapTrackEvent(String line) {

        String[] fields = FIELD_SEPARATOR.split(line);

        if (fields.length > SUBJECT && "TRACK_EVENT".equals(fields[TYPE])) {
            return List.of(new Tuple2<>(fields[SUBJECT], 1)).iterator();
        }
        return Collections.emptyIterator();
    }

    /** Analysis 2: (trackId, 1) for every TRACK_EVENT that occupies a track. */
    static Iterator<Tuple2<String, Integer>> mapTrackOccupation(String line) {

        String[] fields = FIELD_SEPARATOR.split(line);

        if (fields.length > VALUE
                && "TRACK_EVENT".equals(fields[TYPE])
                && TrackStateReplica.OCCUPIED.equals(fields[VALUE])) {
            return List.of(new Tuple2<>(fields[SUBJECT], 1)).iterator();
        }
        return Collections.emptyIterator();
    }

    /** Analysis 3: (processing station, 1) for every TRAIN_EVENT. */
    static Iterator<Tuple2<String, Integer>> mapTrainRequestByStation(String line) {

        String[] fields = FIELD_SEPARATOR.split(line);

        if (fields.length > TYPE && "TRAIN_EVENT".equals(fields[TYPE])) {
            return List.of(new Tuple2<>(fields[STATION], 1)).iterator();
        }
        return Collections.emptyIterator();
    }

    // =========================================================
    // RESULT OF ONE MAPREDUCE RUN
    // Everything is computed by Spark first; the console report and the
    // web dashboard are both produced from this record.
    // =========================================================

    /** One input split: a station's log loaded as one Spark partition. */
    public record PartitionInfo(int partition, String station, int events) {
    }

    /** One sample record passed through the map function (key null = nothing emitted). */
    public record MapSample(String input, String key, Integer value) {
    }

    public record Result(String sparkVersion,
                         List<PartitionInfo> partitions,
                         long totalEvents,
                         List<MapSample> mapSamples,
                         long mapOutputPairs,
                         Map<String, List<Integer>> grouped,
                         Map<String, Integer> reduced,
                         long reducedTotal,
                         Map<String, Integer> occupations,
                         Map<String, Integer> requestsFromLogs,
                         Map<String, Integer> loadBalancerCounts,
                         boolean matchesLoadBalancer,
                         String busiestTrack) {
    }

    // =========================================================
    // JOB
    // =========================================================

    private static SparkConf sparkConf() {
        return new SparkConf()
                .setAppName("RailwayEventAnalytics")
                .setMaster("local[4]")
                .set("spark.ui.enabled", "false")
                .set("spark.driver.host", "localhost")
                .set("spark.driver.bindAddress", "127.0.0.1");
    }

    /**
     * Console version (Experiment 7 in Main): starts Spark, runs the
     * MapReduce analyses and prints the full report.
     *
     * @param stationLogs        station name → its exported log file
     * @param loadBalancerCounts requests the load balancer assigned per
     *                           station, used to cross-check Analysis 3
     */
    public static void run(Map<String, Path> stationLogs,
                           Map<String, Integer> loadBalancerCounts) throws IOException {

        Terminal.blank();
        Terminal.line("Starting Apache Spark (local mode, 4 worker threads)...");

        try (JavaSparkContext spark = new JavaSparkContext(sparkConf())) {

            Terminal.line("✓ Spark " + spark.version() + " context started");
            printReport(analyze(spark, stationLogs, loadBalancerCounts));
        }
    }

    // Web dashboard: one Spark context kept for the lifetime of the server
    private static JavaSparkContext sharedContext;

    /** Runs the analyses on a long-lived Spark context (started on first use). */
    public static synchronized Result analyzeWithSharedContext(
            Map<String, Path> stationLogs,
            Map<String, Integer> loadBalancerCounts) throws IOException {

        if (sharedContext == null) {
            sharedContext = new JavaSparkContext(sparkConf());
        }
        return analyze(sharedContext, stationLogs, loadBalancerCounts);
    }

    /** Runs all three MapReduce analyses with Spark and returns the results. */
    public static Result analyze(JavaSparkContext spark,
                                 Map<String, Path> stationLogs,
                                 Map<String, Integer> loadBalancerCounts) throws IOException {

        JavaRDD<String> events = loadStationLogs(spark, stationLogs);
        events.cache();

        // ---------------- input splits ----------------
        // Counted by Spark: one task per partition
        List<Integer> recordsPerPartition = events.mapPartitions(lines -> {
            int count = 0;
            while (lines.hasNext()) {
                lines.next();
                count++;
            }
            return List.of(count).iterator();
        }).collect();

        List<String> stationNames = new ArrayList<>(stationLogs.keySet());
        List<PartitionInfo> partitions = new ArrayList<>();
        for (int i = 0; i < recordsPerPartition.size(); i++) {
            partitions.add(new PartitionInfo(i, stationNames.get(i), recordsPerPartition.get(i)));
        }
        long totalEvents = events.count();

        // ---------------- ANALYSIS 1: MAP ----------------
        // Sample records fetched by Spark. Each is passed through the same
        // map function Spark uses, to show exactly what it emits.
        List<String> sampleLines = new ArrayList<>(events
                .filter(line -> line.contains("|TRACK_EVENT|"))
                .take(SAMPLE_TRACK_EVENTS));
        sampleLines.addAll(events
                .filter(line -> !line.contains("|TRACK_EVENT|"))
                .take(1));

        List<MapSample> mapSamples = new ArrayList<>();
        for (String sample : sampleLines) {
            Iterator<Tuple2<String, Integer>> emitted = mapTrackEvent(sample);
            if (emitted.hasNext()) {
                Tuple2<String, Integer> pair = emitted.next();
                mapSamples.add(new MapSample(sample, pair._1(), pair._2()));
            } else {
                mapSamples.add(new MapSample(sample, null, null));
            }
        }

        PairFlatMapFunction<String, String, Integer> trackMapper =
                RailwayEventAnalytics::mapTrackEvent;
        JavaPairRDD<String, Integer> mapped = events.flatMapToPair(trackMapper);
        mapped.cache();
        long mapOutputPairs = mapped.count();

        // ---------------- ANALYSIS 1: SHUFFLE / GROUP ----------------
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (Tuple2<String, Iterable<Integer>> group
                : mapped.groupByKey().sortByKey().collect()) {
            List<Integer> values = new ArrayList<>();
            group._2().forEach(values::add);
            grouped.put(group._1(), values);
        }

        // ---------------- ANALYSIS 1: REDUCE ----------------
        Map<String, Integer> reduced = new LinkedHashMap<>();
        long reducedTotal = 0;
        for (Tuple2<String, Integer> entry
                : mapped.reduceByKey(Integer::sum).sortByKey().collect()) {
            reduced.put(entry._1(), entry._2());
            reducedTotal += entry._2();
        }

        // ---------------- ANALYSIS 2: OCCUPATIONS ----------------
        PairFlatMapFunction<String, String, Integer> occupationMapper =
                RailwayEventAnalytics::mapTrackOccupation;
        Map<String, Integer> occupations = new LinkedHashMap<>();
        for (Tuple2<String, Integer> entry : events
                .flatMapToPair(occupationMapper)
                .reduceByKey(Integer::sum)
                .sortByKey()
                .collect()) {
            occupations.put(entry._1(), entry._2());
        }

        // ---------------- ANALYSIS 3: REQUESTS PER STATION ----------------
        // Cross-checks Experiment 6: counted from the logs, compared with
        // the load balancer's own record.
        PairFlatMapFunction<String, String, Integer> stationMapper =
                RailwayEventAnalytics::mapTrainRequestByStation;
        Map<String, Integer> fromLogs = new LinkedHashMap<>();
        for (Tuple2<String, Integer> entry : events
                .flatMapToPair(stationMapper)
                .reduceByKey(Integer::sum)
                .collect()) {
            fromLogs.put(entry._1(), entry._2());
        }

        Map<String, Integer> requestsFromLogs = new LinkedHashMap<>();
        boolean allMatch = true;
        for (Map.Entry<String, Integer> expected : loadBalancerCounts.entrySet()) {
            int counted = fromLogs.getOrDefault(expected.getKey(), 0);
            requestsFromLogs.put(expected.getKey(), counted);
            allMatch &= counted == expected.getValue();
        }

        String busiest = reduced.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("none");

        events.unpersist();
        mapped.unpersist();

        return new Result(spark.version(), partitions, totalEvents, mapSamples,
                mapOutputPairs, grouped, reduced, reducedTotal, occupations,
                requestsFromLogs, new LinkedHashMap<>(loadBalancerCounts), allMatch, busiest);
    }

    /** One RDD partition per station log (the "input splits"). */
    private static JavaRDD<String> loadStationLogs(JavaSparkContext spark,
                                                   Map<String, Path> stationLogs)
            throws IOException {

        JavaRDD<String> events = null;

        for (Path logFile : stationLogs.values()) {

            // Read with plain Java I/O, then hand each file to Spark as one
            // partition. (Spark's Hadoop-based textFile() would need extra
            // native Hadoop binaries on Windows.)
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            JavaRDD<String> partition = spark.parallelize(lines, 1);
            events = (events == null) ? partition : events.union(partition);
        }

        if (events == null) {
            throw new IllegalArgumentException("no station logs to analyse");
        }
        return events;
    }

    // =========================================================
    // CONSOLE REPORT
    // =========================================================

    private static void printReport(Result result) {

        Terminal.blank();
        Terminal.line("Input splits (one Spark partition per station log):");
        for (PartitionInfo split : result.partitions()) {
            Terminal.line(String.format("  Partition %d ← %s.log : %d events",
                    split.partition(), split.station(), split.events()));
        }
        Terminal.line("Total Events: " + result.totalEvents());

        // ---------------- ANALYSIS 1 ----------------
        Terminal.banner(
                "ANALYSIS 1 - TRACK EVENT FREQUENCY",
                "Question: how many coordination events occurred on each track?"
        );

        Terminal.section("MAP PHASE");
        Terminal.line("map(event) → emit (KEY = trackId, VALUE = 1) for every TRACK_EVENT");
        Terminal.line("             emit nothing for any other event type");
        Terminal.blank();

        for (MapSample sample : result.mapSamples()) {
            Terminal.line("Input Event: " + sample.input());
            Terminal.line(sample.key() != null
                    ? "Mapped     : (KEY = " + sample.key() + ", VALUE = " + sample.value() + ")"
                    : "Mapped     : (nothing emitted, not a TRACK_EVENT)");
            Terminal.blank();
        }

        Terminal.line("Spark map output: " + result.mapOutputPairs()
                + " (key, value) pairs from " + result.totalEvents() + " input events");

        Terminal.section("SHUFFLE / GROUP PHASE");
        Terminal.line("Grouping values by Track ID (groupByKey)...");
        Terminal.blank();
        result.grouped().forEach((track, values) -> Terminal.line("  " + track + " → " + values));

        Terminal.section("REDUCE PHASE");
        Terminal.line("reduce(trackId, values) → sum of values (reduceByKey)");
        Terminal.blank();
        result.reduced().forEach((track, count) -> Terminal.line("  " + track + " → " + count));

        Terminal.blank();
        if (result.reducedTotal() == result.mapOutputPairs()) {
            Terminal.line("✓ Check: reduced total (" + result.reducedTotal()
                    + ") = map output pairs (" + result.mapOutputPairs() + ")");
        } else {
            Terminal.line("✗ Check failed: reduced total " + result.reducedTotal()
                    + " ≠ map output " + result.mapOutputPairs());
        }

        // ---------------- ANALYSIS 2 ----------------
        Terminal.banner(
                "ANALYSIS 2 - TRACK OCCUPATION FREQUENCY",
                "MAP: (trackId, 1) for OCCUPIED events | REDUCE: sum"
        );
        Terminal.blank();
        result.occupations().forEach((track, count) -> Terminal.line("  " + track + " → " + count
                + (count == 1 ? " occupation" : " occupations")));

        // ---------------- ANALYSIS 3 ----------------
        Terminal.banner(
                "ANALYSIS 3 - TRAIN REQUESTS PROCESSED PER STATION",
                "MAP: (station, 1) per TRAIN_EVENT | REDUCE: sum"
        );
        Terminal.blank();
        result.loadBalancerCounts().forEach((station, expected) -> {
            int counted = result.requestsFromLogs().get(station);
            Terminal.line(String.format("  %s → %d requests   (load balancer: %d) %s",
                    station, counted, expected, counted == expected ? "✓" : "✗"));
        });

        Terminal.blank();
        Terminal.line(result.matchesLoadBalancer()
                ? "✓ MapReduce result matches the load balancer's routing decisions"
                : "✗ MapReduce result differs from the load balancer");

        // ---------------- FINAL ----------------
        Terminal.section("FINAL RAILWAY ANALYTICS");
        result.reduced().forEach((track, count) ->
                Terminal.line("  " + track + " → " + count + " coordination events"));
        Terminal.blank();
        Terminal.line("Busiest track: " + result.busiestTrack());
    }
}
