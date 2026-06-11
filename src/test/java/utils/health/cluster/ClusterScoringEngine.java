package utils.health.cluster;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure scoring engine: given clusters and a policy, compute applied
 * penalty. No I/O, no static state, no production-class wiring.
 *
 * <p>Intended call shape:</p>
 * <pre>
 *   ScoringResult result = ClusterScoringEngine.score(
 *       clusters, new ClusterPenaltyPolicy.PreserveAmplification(), caps);
 * </pre>
 *
 * <p>The replay harness (Phase 3) will invoke this twice per historical
 * run — once per candidate policy — and compare the two
 * {@link ScoringResult}s side by side. The HealthTracker integration
 * (Phase 4) will call this once, with whichever policy survives replay.</p>
 */
public final class ClusterScoringEngine {

    private ClusterScoringEngine() {}

    /**
     * Score a list of clusters under the given policy. Clusters are
     * partitioned by {@link ContributorSource} so each source's policy
     * application is independent — a fallback storm cannot leak penalty
     * into the JS-error bucket, by construction.
     *
     * @param clusters all clusters from a run, any sources mixed in
     * @param policy   which policy to apply
     * @param caps     per-source diminishing-returns cap. Missing sources
     *                 default to {@code Double.MAX_VALUE} (no cap).
     */
    public static ScoringResult score(
            List<Cluster> clusters,
            ClusterPenaltyPolicy policy,
            Map<ContributorSource, Double> caps) {

        if (policy == null) throw new IllegalArgumentException("policy must be non-null");
        if (caps == null) caps = Map.of();

        // Partition by source — clusters from one source never contaminate
        // another. This is the structural guarantee that closes the
        // "fallback storm leaks into JS errors" failure mode.
        Map<ContributorSource, List<Cluster>> bySource = (clusters == null)
                ? new EnumMap<>(ContributorSource.class)
                : clusters.stream().collect(Collectors.groupingBy(
                        Cluster::source,
                        () -> new EnumMap<>(ContributorSource.class),
                        Collectors.toList()));

        Map<ContributorSource, Double> perSourceApplied = new LinkedHashMap<>();
        Map<ContributorSource, Integer> perSourceClusters = new LinkedHashMap<>();
        Map<ContributorSource, Integer> perSourceRaw = new LinkedHashMap<>();
        Map<ContributorSource, Integer> perSourceOverCap = new LinkedHashMap<>();

        double total = 0.0;
        // Iterate enum order for deterministic output across runs.
        for (ContributorSource source : ContributorSource.values()) {
            List<Cluster> forSource = bySource.getOrDefault(source, List.of());
            if (forSource.isEmpty()) continue;
            double cap = caps.getOrDefault(source, Double.MAX_VALUE);
            double applied = policy.applyAcrossClusters(forSource, cap);
            int rawEvents = forSource.stream().mapToInt(Cluster::rawEventCount).sum();
            // Count clusters whose individual raw weight crosses the cap —
            // these are the clusters that drive Policy-A-vs-today divergence.
            int overCap = (int) forSource.stream()
                    .filter(c -> c.rawTotalWeight() > cap)
                    .count();
            perSourceApplied.put(source, applied);
            perSourceClusters.put(source, forSource.size());
            perSourceRaw.put(source, rawEvents);
            perSourceOverCap.put(source, overCap);
            total += applied;
        }

        return new ScoringResult(
                policy.name(),
                total,
                perSourceApplied,
                perSourceClusters,
                perSourceRaw,
                perSourceOverCap);
    }

    /**
     * Comparison shortcut: run both candidate policies on the same input
     * and return both results. Phase 3 replay will call this per historical
     * run to compute the delta-and-reason table.
     */
    public static PolicyComparison compare(
            List<Cluster> clusters,
            Map<ContributorSource, Double> caps) {
        ScoringResult policyA = score(clusters, new ClusterPenaltyPolicy.PreserveAmplification(), caps);
        ScoringResult policyB = score(clusters, new ClusterPenaltyPolicy.FlatCluster(), caps);
        return new PolicyComparison(policyA, policyB);
    }

    /** Side-by-side outcome under both candidate policies for one input. */
    public record PolicyComparison(ScoringResult preserveAmplification, ScoringResult flatCluster) {
        /** Δ = preserveAmplification.total − flatCluster.total. */
        public double delta() {
            return preserveAmplification.totalApplied() - flatCluster.totalApplied();
        }
    }
}
