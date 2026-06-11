package utils.health.cluster.replay;

import java.util.List;
import java.util.Map;

import utils.health.cluster.ContributorSource;
import utils.health.cluster.ScoringResult;

/**
 * One row in the C4 replay report. Captures both candidate-policy outcomes
 * for a single historical snapshot, plus the metadata needed to answer
 * "why did this run's score change?" without re-deriving it from raw inputs.
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code runId} — opaque identifier (filename, timestamp, etc.)</li>
 *   <li>{@code timestamp} — snapshot timestamp in millis. {@code 0} = unknown.</li>
 *   <li>{@code oldScore} — the score the snapshot itself stored (status quo).</li>
 *   <li>{@code oldPenalty} — penalty as stored in the snapshot.</li>
 *   <li>{@code oldPerSourceApplied} — per-source breakdown from the snapshot.
 *       Used to compute per-source deltas against the candidate policies.</li>
 *   <li>{@code policyA} — outcome under {@code PreserveAmplification}.</li>
 *   <li>{@code policyB} — outcome under {@code FlatCluster}.</li>
 *   <li>{@code largestDeltaSource} — source where {@code policyA − old} has the
 *       largest absolute value. This is the "why" attribution column.</li>
 *   <li>{@code largestAmplification} — that source's amplification ratio
 *       (raw events / cluster count). The C4 problem statement made concrete.</li>
 * </ul>
 */
public record ReplayRunResult(
        String runId,
        long timestamp,
        int oldScore,
        double oldPenalty,
        Map<ContributorSource, Double> oldPerSourceApplied,
        ScoringResult policyA,
        ScoringResult policyB,
        ContributorSource largestDeltaSource,
        double largestAmplification,
        /**
         * True iff the upstream B4 schema migrator produced this result
         * via a lossy V2→V3 conversion. When set, the per-policy numbers
         * are NOT a real C4 signal — they reflect an empty contributor
         * set because raw events were never persisted in V2. The report
         * surfaces this so readers don't conflate "lossy migration
         * produced 0" with "policies agree".
         */
        boolean lossy,
        /** Reasons the migration was lossy. Empty for native (non-lossy) runs. */
        List<String> migrationReasons
) {
    public ReplayRunResult {
        migrationReasons = migrationReasons == null ? List.of() : List.copyOf(migrationReasons);
    }

    /**
     * Backward-compatible factory for non-lossy callers (used by all
     * pre-B4 construction sites and any test that doesn't care about
     * migration status).
     */
    public static ReplayRunResult native_(String runId, long timestamp, int oldScore,
            double oldPenalty, Map<ContributorSource, Double> oldPerSourceApplied,
            ScoringResult policyA, ScoringResult policyB,
            ContributorSource largestDeltaSource, double largestAmplification) {
        return new ReplayRunResult(runId, timestamp, oldScore, oldPenalty,
                oldPerSourceApplied, policyA, policyB,
                largestDeltaSource, largestAmplification,
                false, List.of());
    }

    /** Δ for Policy A: applied penalty under A minus the snapshot's old penalty. */
    public double policyADelta() { return policyA.totalApplied() - oldPenalty; }

    /** Δ for Policy B: applied penalty under B minus the snapshot's old penalty. */
    public double policyBDelta() { return policyB.totalApplied() - oldPenalty; }

    /** Score under Policy A (clamped to [0,100]): 100 − policyA.totalApplied. */
    public int policyAScore() {
        return clamp(100 - (int) Math.round(policyA.totalApplied()));
    }

    /** Score under Policy B (clamped to [0,100]): 100 − policyB.totalApplied. */
    public int policyBScore() {
        return clamp(100 - (int) Math.round(policyB.totalApplied()));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
}
