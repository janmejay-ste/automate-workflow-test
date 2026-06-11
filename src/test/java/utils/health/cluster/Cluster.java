package utils.health.cluster;

import java.util.List;
import java.util.Map;

/**
 * Result of grouping N raw contributor events that share a fingerprint.
 * Carries the raw→cluster amplification ratio so downstream scoring can
 * distinguish "1 event, weight 10" from "22 events, weight 10 after
 * diminishing returns" — the C4 problem statement.
 *
 * <p>Field meanings:</p>
 * <ul>
 *   <li>{@code key} — fingerprint identity. Equal keys mean same cluster.</li>
 *   <li>{@code source} — which contributor bucket this cluster belongs to.</li>
 *   <li>{@code rawEventCount} — count of raw events folded into this cluster.</li>
 *   <li>{@code rawTotalWeight} — sum of per-event weights BEFORE clustering.
 *       This is what today's flat scoring would have applied.</li>
 *   <li>{@code firstOccurrenceIndex} — zero-based position of the first event
 *       in this cluster within the run's event stream. Enables temporal
 *       analysis ("which cluster appeared first", "did failure A precede
 *       failure B") without bringing per-event timestamps into the value
 *       type. {@code -1} means temporal data is unavailable.</li>
 *   <li>{@code sampleEvent} — one representative event for display / debug.
 *       Opaque map; don't rely on key names beyond what extractors document.</li>
 * </ul>
 *
 * <p>The cluster-normalized weight is intentionally NOT stored here. That
 * value is the responsibility of a separate policy step (next phase of C4),
 * so the value type stays free of policy decisions. Test fixtures can stage
 * Clusters and feed them to whatever policy is under test.</p>
 */
public record Cluster(
        ClusterKey key,
        ContributorSource source,
        int rawEventCount,
        double rawTotalWeight,
        int firstOccurrenceIndex,
        Map<String, String> sampleEvent
) {
    /** Sentinel: no temporal data available. */
    public static final int NO_TEMPORAL_DATA = -1;

    public Cluster {
        if (key == null) throw new IllegalArgumentException("key must be non-null");
        if (source == null) throw new IllegalArgumentException("source must be non-null");
        if (rawEventCount < 1) {
            throw new IllegalArgumentException("rawEventCount must be >= 1, got " + rawEventCount);
        }
        if (rawTotalWeight < 0) {
            throw new IllegalArgumentException("rawTotalWeight must be >= 0, got " + rawTotalWeight);
        }
        if (firstOccurrenceIndex < -1) {
            throw new IllegalArgumentException(
                "firstOccurrenceIndex must be >= -1, got " + firstOccurrenceIndex);
        }
        if (sampleEvent == null) {
            sampleEvent = Map.of();
        }
    }

    /**
     * Amplification ratio: how many raw events folded into this one cluster.
     * A ratio of 1.0 means the cluster is unique; a ratio of 22.0 means
     * one root cause produced 22 events. Used by the dashboard / replay
     * report to surface amplification per-cluster without inferring it from
     * the score itself.
     */
    public double amplification() {
        return rawEventCount;
    }

    /** True if temporal ordering data is available for this cluster. */
    public boolean hasTemporalData() {
        return firstOccurrenceIndex >= 0;
    }

    /**
     * Convenience factory for unit tests: build a Cluster from a list of
     * already-extracted events that share a key. No temporal data.
     */
    public static Cluster of(ClusterKey key, ContributorSource source,
                             List<? extends Map<String, String>> events, double perEventWeight) {
        return of(key, source, events, perEventWeight, NO_TEMPORAL_DATA);
    }

    /**
     * Convenience factory with explicit firstOccurrenceIndex.
     */
    public static Cluster of(ClusterKey key, ContributorSource source,
                             List<? extends Map<String, String>> events,
                             double perEventWeight, int firstOccurrenceIndex) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must be non-empty");
        }
        return new Cluster(key, source, events.size(),
                events.size() * perEventWeight,
                firstOccurrenceIndex,
                Map.copyOf(events.get(0)));
    }
}
