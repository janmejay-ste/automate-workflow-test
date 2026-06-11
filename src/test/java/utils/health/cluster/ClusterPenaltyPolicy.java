package utils.health.cluster;

import java.util.List;

import utils.HealthPolicy;

/**
 * Strategy: given a list of {@link Cluster}s for a single contributor
 * source, decide how many penalty points they should apply.
 *
 * <p><b>Why a strategy, not a single function:</b> the C4 design space has
 * (at least) two reasonable candidates, and choosing between them before
 * replay data is collected would be premature. Both policies are
 * implemented so that the replay harness can score the same historical
 * runs under each and surface where they diverge.</p>
 *
 * <p><b>Policy A — Preserve Amplification.</b> Cluster's rawTotalWeight is
 * the input. Diminishing returns is applied to the SUM of per-event
 * weights, so 22 events at weight 10 (raw 220) get reduced to ~one
 * diminishing-curve value but the curve still sees a high raw input.
 * Preserves the signal that "this thing happened a lot."</p>
 *
 * <p><b>Policy B — Flat Cluster.</b> Cluster contributes a fixed weight
 * per cluster regardless of rawEventCount. 22 events and 2 events on the
 * same fingerprint both contribute the same. Maximally amplification-
 * insensitive.</p>
 *
 * <p>The right answer is almost certainly somewhere in between (or
 * source-dependent). The point of Phase 2 is to make both available so
 * Phase 3 can measure where they actually differ on real data.</p>
 */
public sealed interface ClusterPenaltyPolicy
        permits ClusterPenaltyPolicy.PreserveAmplification,
                ClusterPenaltyPolicy.FlatCluster {

    /** Human-readable label for logs / replay reports. */
    String name();

    /**
     * Sum of applied penalty across the supplied clusters. Clusters must
     * all belong to the same {@link ContributorSource} — callers partition
     * by source before invoking.
     *
     * @param clusters all clusters from one source. May be empty.
     * @param cap the diminishing-returns cap to use for this source.
     *            Pulled from {@link HealthPolicy} per source.
     */
    double applyAcrossClusters(List<Cluster> clusters, double cap);

    // ────────────────────────────────────────────────────────────────────
    // Policy A — Preserve Amplification
    // ────────────────────────────────────────────────────────────────────

    /**
     * Diminishing returns applied to the cluster's rawTotalWeight (which
     * equals rawEventCount × per-event weight). A storm of 22 retries at
     * weight 10 → curve(220, cap) — still feels the amplification, but
     * doesn't apply 220 linearly.
     */
    final class PreserveAmplification implements ClusterPenaltyPolicy {
        @Override public String name() { return "PRESERVE_AMPLIFICATION"; }

        @Override
        public double applyAcrossClusters(List<Cluster> clusters, double cap) {
            if (clusters == null || clusters.isEmpty()) return 0.0;
            double total = 0.0;
            for (Cluster c : clusters) {
                // Each cluster gets diminishing returns applied to its own
                // raw total. Sum across clusters — independent clusters
                // are independent root causes; their penalties don't share
                // a curve.
                total += HealthPolicy.applyDiminishingReturns(c.rawTotalWeight(), cap);
            }
            return total;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Policy B — Flat Cluster
    // ────────────────────────────────────────────────────────────────────

    /**
     * Each cluster contributes a fixed weight regardless of event count.
     * Implemented as: take ONE per-event weight from the cluster (via
     * rawTotalWeight / rawEventCount) and apply it once per cluster.
     * If rawEventCount is 0 (shouldn't happen — Cluster's constructor
     * enforces >= 1), defaults to 0.
     */
    final class FlatCluster implements ClusterPenaltyPolicy {
        @Override public String name() { return "FLAT_CLUSTER"; }

        @Override
        public double applyAcrossClusters(List<Cluster> clusters, double cap) {
            if (clusters == null || clusters.isEmpty()) return 0.0;
            double total = 0.0;
            for (Cluster c : clusters) {
                double perEvent = c.rawEventCount() > 0
                        ? c.rawTotalWeight() / c.rawEventCount()
                        : 0.0;
                total += perEvent;  // one event's weight, regardless of count
            }
            // Apply diminishing returns at the source level so a wave of
            // independent clusters can't run away unbounded either.
            return HealthPolicy.applyDiminishingReturns(total, cap);
        }
    }
}
