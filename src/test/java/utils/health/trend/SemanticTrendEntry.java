package utils.health.trend;

import java.util.List;
import java.util.Map;

/**
 * One persisted run's semantic snapshot, structured for trend analysis.
 *
 * Stored in {@code reports/trend/semantic_history.jsonl} — one JSON object per
 * line, append-only.  JSONL chosen over a single JSON array because:
 *   - append is O(1) without re-parsing the whole file
 *   - mid-write corruption of one line never destroys earlier history
 *   - trivial to tail / grep for forensic investigation
 *
 * The fingerprint on each cluster is the recurrence key — same fingerprint
 * across runs means the same underlying defect, even if line numbers shifted.
 */
public final class SemanticTrendEntry {

    public final String                runId;          // ISO-8601 UTC timestamp
    public final long                  timestampMs;
    public final String                suite;
    public final String                environment;

    public final int                   productHealth;
    public final int                   frameworkHealth;
    public final int                   telemetryConfidence;

    public final int                   unknownTimingCount;
    public final int                   validTimingCount;

    public final List<ReliabilityRow>  reliability;
    public final List<ClusterRow>      clusters;
    public final Map<String, Integer>  errorCountByDomain;

    public SemanticTrendEntry(String runId, long timestampMs, String suite, String environment,
                              int productHealth, int frameworkHealth, int telemetryConfidence,
                              int unknownTimingCount, int validTimingCount,
                              List<ReliabilityRow> reliability,
                              List<ClusterRow> clusters,
                              Map<String, Integer> errorCountByDomain) {
        this.runId               = runId;
        this.timestampMs         = timestampMs;
        this.suite               = suite;
        this.environment         = environment;
        this.productHealth       = productHealth;
        this.frameworkHealth     = frameworkHealth;
        this.telemetryConfidence = telemetryConfidence;
        this.unknownTimingCount  = unknownTimingCount;
        this.validTimingCount    = validTimingCount;
        this.reliability         = reliability;
        this.clusters            = clusters;
        this.errorCountByDomain  = errorCountByDomain;
    }

    /** Per-phase reliability row carried across runs. */
    public static final class ReliabilityRow {
        public final String phase;
        public final int    successes;
        public final int    failures;
        public final double percentage;   // -1 if undefined

        public ReliabilityRow(String phase, int successes, int failures, double percentage) {
            this.phase      = phase;
            this.successes  = successes;
            this.failures   = failures;
            this.percentage = percentage;
        }
    }

    /** Slimmed-down cluster carried across runs — sample messages omitted for size. */
    public static final class ClusterRow {
        public final String fingerprint;   // stable across runs — recurrence key
        public final String title;
        public final String domain;
        public final String severity;
        public final int    count;

        public ClusterRow(String fingerprint, String title, String domain,
                          String severity, int count) {
            this.fingerprint = fingerprint;
            this.title       = title;
            this.domain      = domain;
            this.severity    = severity;
            this.count       = count;
        }
    }
}
