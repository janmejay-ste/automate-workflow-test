package utils.health.cluster.replay;

import java.util.Arrays;
import java.util.List;

/**
 * Aggregate statistics for the score deltas produced by replaying N runs
 * through a single policy. The distribution shape matters more than any
 * single run: a policy that produces median Δ=+18, max Δ=+24 is telling a
 * very different story from one that produces median Δ=+2, max Δ=+47.
 *
 * <p>Computed via empirical (nearest-rank) percentile — no JDK statistics
 * lib required, and the dataset is always small enough (tens of runs) that
 * fancy interpolation wouldn't change the story.</p>
 */
public record ReplayDistributionStats(
        String policyName,
        int sampleSize,
        double meanDelta,
        double medianDelta,
        double p95Delta,
        double maxAbsDelta
) {
    public static ReplayDistributionStats forPolicyA(List<ReplayRunResult> runs) {
        return compute("PRESERVE_AMPLIFICATION",
                runs.stream().mapToDouble(ReplayRunResult::policyADelta).toArray());
    }

    public static ReplayDistributionStats forPolicyB(List<ReplayRunResult> runs) {
        return compute("FLAT_CLUSTER",
                runs.stream().mapToDouble(ReplayRunResult::policyBDelta).toArray());
    }

    private static ReplayDistributionStats compute(String name, double[] deltas) {
        if (deltas == null || deltas.length == 0) {
            return new ReplayDistributionStats(name, 0, 0, 0, 0, 0);
        }
        double[] sorted = deltas.clone();
        Arrays.sort(sorted);
        double mean = 0;
        for (double d : sorted) mean += d;
        mean /= sorted.length;

        double median = percentileNearestRank(sorted, 50);
        double p95    = percentileNearestRank(sorted, 95);

        // Max absolute delta — what's the worst single swing this policy
        // could produce on the replayed corpus?
        double maxAbs = 0;
        for (double d : sorted) maxAbs = Math.max(maxAbs, Math.abs(d));

        return new ReplayDistributionStats(name, sorted.length, mean, median, p95, maxAbs);
    }

    /** Nearest-rank percentile. Input must be pre-sorted ascending. */
    private static double percentileNearestRank(double[] sortedAsc, int pct) {
        if (sortedAsc.length == 0) return 0;
        // rank = ceil(P/100 * N), 1-indexed
        int rank = (int) Math.ceil((pct / 100.0) * sortedAsc.length);
        int idx = Math.min(Math.max(rank - 1, 0), sortedAsc.length - 1);
        return sortedAsc[idx];
    }
}
