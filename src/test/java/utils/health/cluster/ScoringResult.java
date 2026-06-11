package utils.health.cluster;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome of applying a {@link ClusterPenaltyPolicy} to a partitioned set
 * of clusters. Carries per-source breakdown so the replay report can
 * surface where any score delta came from, not just that the total moved.
 */
public record ScoringResult(
        String policyName,
        double totalApplied,
        Map<ContributorSource, Double> perSourceApplied,
        Map<ContributorSource, Integer> perSourceClusterCount,
        Map<ContributorSource, Integer> perSourceRawEventCount,
        /**
         * Per source, the number of clusters whose {@code rawTotalWeight}
         * exceeds the source cap. THIS is the structural condition under
         * which Policy A's per-cluster cap diverges from today's source-
         * level cap. {@code 0} for a source means Policy A produces
         * identical output to today's flat scoring for that source, no
         * matter how many clusters or how high the amplification ratio.
         */
        Map<ContributorSource, Integer> perSourceClustersOverCap
) {
    public ScoringResult {
        if (policyName == null || policyName.isBlank()) {
            throw new IllegalArgumentException("policyName must be non-blank");
        }
        perSourceApplied         = unmodifiable(perSourceApplied);
        perSourceClusterCount    = unmodifiable(perSourceClusterCount);
        perSourceRawEventCount   = unmodifiable(perSourceRawEventCount);
        perSourceClustersOverCap = unmodifiable(perSourceClustersOverCap);
    }

    /**
     * Convenience: per-source amplification ratio = raw / clusters.
     * Returns 0 for sources with no clusters.
     */
    public double amplificationRatio(ContributorSource source) {
        int clusters = perSourceClusterCount.getOrDefault(source, 0);
        int events = perSourceRawEventCount.getOrDefault(source, 0);
        return clusters == 0 ? 0.0 : ((double) events) / clusters;
    }

    /** Sum of cluster counts across all sources. */
    public int totalClusterCount() {
        return perSourceClusterCount.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Sum of raw event counts across all sources. */
    public int totalRawEventCount() {
        return perSourceRawEventCount.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Sum of clusters-over-cap across all sources. */
    public int totalClustersOverCap() {
        return perSourceClustersOverCap.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static <K, V> Map<K, V> unmodifiable(Map<K, V> m) {
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }
}
