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
    // JOB
    // =========================================================

    /**
     * Runs the MapReduce analyses.
     *
     * @param stationLogs        station name → its exported log file
     * @param loadBalancerCounts requests the load balancer assigned per
     *                           station, used to cross-check Analysis 3
     */
    public static void run(Map<String, Path> stationLogs,
                           Map<String, Integer> loadBalancerCounts) throws IOException {

        SparkConf conf = new SparkConf()
                .setAppName("RailwayEventAnalytics")
                .setMaster("local[4]")
                .set("spark.ui.enabled", "false")
                .set("spark.driver.host", "localhost")
                .set("spark.driver.bindAddress", "127.0.0.1");

        Terminal.blank();
        Terminal.line("Starting Apache Spark (local mode, 4 worker threads)...");

        try (JavaSparkContext spark = new JavaSparkContext(conf)) {

            Terminal.line("✓ Spark " + spark.version() + " context started");

            JavaRDD<String> events = loadStationLogs(spark, stationLogs);
            events.cache();

            printInputSplits(events, new ArrayList<>(stationLogs.keySet()));

            Map<String, Integer> trackEvents = runTrackEventFrequency(events);
            runTrackOccupationFrequency(events);
            runRequestsPerStation(events, loadBalancerCounts);

            Terminal.section("FINAL RAILWAY ANALYTICS");
            trackEvents.forEach((track, count) ->
                    Terminal.line("  " + track + " → " + count + " coordination events"));

            String busiest = trackEvents.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("none");
            Terminal.blank();
            Terminal.line("Busiest track: " + busiest);
        }
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

    private static void printInputSplits(JavaRDD<String> events, List<String> stationNames) {

        // Counted by Spark: one task per partition
        List<Integer> recordsPerPartition = events.mapPartitions(lines -> {
            int count = 0;
            while (lines.hasNext()) {
                lines.next();
                count++;
            }
            return List.of(count).iterator();
        }).collect();

        Terminal.blank();
        Terminal.line("Input splits (one Spark partition per station log):");

        for (int i = 0; i < recordsPerPartition.size(); i++) {
            Terminal.line(String.format("  Partition %d ← %s.log : %d events",
                    i, stationNames.get(i), recordsPerPartition.get(i)));
        }

        Terminal.line("Total Events: " + events.count());
    }

    // =========================================================
    // ANALYSIS 1 - TRACK EVENT FREQUENCY (full Map/Shuffle/Reduce view)
    // =========================================================

    private static Map<String, Integer> runTrackEventFrequency(JavaRDD<String> events) {

        Terminal.banner(
                "ANALYSIS 1 - TRACK EVENT FREQUENCY",
                "Question: how many coordination events occurred on each track?"
        );

        // -------------------- MAP --------------------
        Terminal.section("MAP PHASE");
        Terminal.line("map(event) → emit (KEY = trackId, VALUE = 1) for every TRACK_EVENT");
        Terminal.line("             emit nothing for any other event type");
        Terminal.blank();

        // Sample records fetched by Spark. Each is passed through the same
        // map function Spark uses, to show exactly what it emits.
        List<String> samples = new ArrayList<>(events
                .filter(line -> line.contains("|TRACK_EVENT|"))
                .take(SAMPLE_TRACK_EVENTS));
        samples.addAll(events
                .filter(line -> !line.contains("|TRACK_EVENT|"))
                .take(1));

        for (String sample : samples) {

            Terminal.line("Input Event: " + sample);

            Iterator<Tuple2<String, Integer>> emitted = mapTrackEvent(sample);
            if (emitted.hasNext()) {
                Tuple2<String, Integer> pair = emitted.next();
                Terminal.line("Mapped     : (KEY = " + pair._1()
                        + ", VALUE = " + pair._2() + ")");
            } else {
                Terminal.line("Mapped     : (nothing emitted, not a TRACK_EVENT)");
            }
            Terminal.blank();
        }

        PairFlatMapFunction<String, String, Integer> mapper =
                RailwayEventAnalytics::mapTrackEvent;
        JavaPairRDD<String, Integer> mapped = events.flatMapToPair(mapper);
        mapped.cache();

        long mapOutputRecords = mapped.count();
        Terminal.line("Spark map output: " + mapOutputRecords
                + " (key, value) pairs from " + events.count() + " input events");

        // ---------------- SHUFFLE / GROUP ----------------
        Terminal.section("SHUFFLE / GROUP PHASE");
        Terminal.line("Grouping values by Track ID (groupByKey)...");
        Terminal.blank();

        List<Tuple2<String, Iterable<Integer>>> grouped =
                mapped.groupByKey().sortByKey().collect();

        for (Tuple2<String, Iterable<Integer>> group : grouped) {
            List<Integer> values = new ArrayList<>();
            group._2().forEach(values::add);
            Terminal.line("  " + group._1() + " → " + values);
        }

        // -------------------- REDUCE --------------------
        Terminal.section("REDUCE PHASE");
        Terminal.line("reduce(trackId, values) → sum of values (reduceByKey)");
        Terminal.blank();

        List<Tuple2<String, Integer>> reduced =
                mapped.reduceByKey(Integer::sum).sortByKey().collect();

        Map<String, Integer> result = new LinkedHashMap<>();
        long total = 0;

        for (Tuple2<String, Integer> entry : reduced) {
            Terminal.line("  " + entry._1() + " → " + entry._2());
            result.put(entry._1(), entry._2());
            total += entry._2();
        }

        Terminal.blank();
        if (total == mapOutputRecords) {
            Terminal.line("✓ Check: reduced total (" + total
                    + ") = map output pairs (" + mapOutputRecords + ")");
        } else {
            Terminal.line("✗ Check failed: reduced total " + total
                    + " ≠ map output " + mapOutputRecords);
        }

        return result;
    }

    // =========================================================
    // ANALYSIS 2 - TRACK OCCUPATION FREQUENCY
    // =========================================================

    private static void runTrackOccupationFrequency(JavaRDD<String> events) {

        Terminal.banner(
                "ANALYSIS 2 - TRACK OCCUPATION FREQUENCY",
                "MAP: (trackId, 1) for OCCUPIED events | REDUCE: sum"
        );
        Terminal.blank();

        PairFlatMapFunction<String, String, Integer> mapper =
                RailwayEventAnalytics::mapTrackOccupation;

        List<Tuple2<String, Integer>> occupations = events
                .flatMapToPair(mapper)
                .reduceByKey(Integer::sum)
                .sortByKey()
                .collect();

        for (Tuple2<String, Integer> entry : occupations) {
            Terminal.line("  " + entry._1() + " → " + entry._2()
                    + (entry._2() == 1 ? " occupation" : " occupations"));
        }
    }

    // =========================================================
    // ANALYSIS 3 - TRAIN REQUESTS PROCESSED PER STATION
    // Cross-checks Experiment 6: the counts are computed from the logs
    // and compared with the load balancer's own record.
    // =========================================================

    private static void runRequestsPerStation(JavaRDD<String> events,
                                              Map<String, Integer> loadBalancerCounts) {

        Terminal.banner(
                "ANALYSIS 3 - TRAIN REQUESTS PROCESSED PER STATION",
                "MAP: (station, 1) per TRAIN_EVENT | REDUCE: sum"
        );
        Terminal.blank();

        PairFlatMapFunction<String, String, Integer> mapper =
                RailwayEventAnalytics::mapTrainRequestByStation;

        Map<String, Integer> fromLogs = new LinkedHashMap<>();
        for (Tuple2<String, Integer> entry : events
                .flatMapToPair(mapper)
                .reduceByKey(Integer::sum)
                .collect()) {
            fromLogs.put(entry._1(), entry._2());
        }

        boolean allMatch = true;

        for (Map.Entry<String, Integer> expected : loadBalancerCounts.entrySet()) {

            int counted = fromLogs.getOrDefault(expected.getKey(), 0);
            boolean match = counted == expected.getValue();
            allMatch &= match;

            Terminal.line(String.format("  %s → %d requests   (load balancer: %d) %s",
                    expected.getKey(), counted, expected.getValue(),
                    match ? "✓" : "✗"));
        }

        Terminal.blank();
        Terminal.line(allMatch
                ? "✓ MapReduce result matches the load balancer's routing decisions"
                : "✗ MapReduce result differs from the load balancer");
    }
}
