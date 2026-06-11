package utils.history.trends;

import java.util.List;
import org.json.simple.JSONObject;

/**
 * Phase C2 — Statistical summary of recent runs.
 *
 * <p>Computes mean, sample standard deviation, and the current run's z-score
 * against the last N runs. Emitted as the top-level {@code trendStats} block
 * in {@code health_snapshot.json}.</p>
 *
 * <h3>Why:</h3>
 * The existing trend chart shows raw scores over time but doesn't surface
 * whether the current run is normal noise or a meaningful deviation. A run
 * at 88 in a band that's been [85, 95] is fine; the same 88 in a band that's
 * been [90, 100] is a warning. Z-score normalises that.
 *
 * <p>The {@code outlier} flag fires when |zScore| ≥ 2.0 (≈ 2σ event,
 * ~5 % under normal distribution). When raised, the snapshot writer adds
 * an entry to {@code release.warnings} so the dashboard surfaces it.</p>
 *
 * <p>Behind a feature flag {@code -DtrendStats.enabled=true} — default off
 * during initial rollout so trend comparison isn't disrupted. Emit a stable
 * "NO_DATA" record when flag is off rather than omitting the block entirely;
 * downstream consumers don't have to branch on absence.</p>
 */
public final class TrendStatsCalculator {

    /** Window of recent runs used for the mean/stdDev calculation. */
    public static final int DEFAULT_WINDOW = 30;

    /** Minimum sample size for the z-score to be meaningful. Below this, outlier flag is always false. */
    public static final int MIN_SAMPLE_FOR_OUTLIER = 5;

    /** |z| at or above this triggers the outlier flag. ~5 % event under normal distribution. */
    public static final double OUTLIER_Z_THRESHOLD = 2.0;

    private TrendStatsCalculator() {}

    /**
     * Compute statistics for the current run against a list of prior scores.
     *
     * @param priorScores  scores from the {@link #DEFAULT_WINDOW} most-recent runs (newest first or oldest first;
     *                     order doesn't matter for mean/stdDev). The current run is NOT included here.
     * @param currentScore the score the current run produced.
     * @return a {@link TrendStats} bundle. Always non-null. When sample is empty or below
     *         {@link #MIN_SAMPLE_FOR_OUTLIER}, {@code outlier} is false and z-score is 0.
     */
    public static TrendStats compute(List<Integer> priorScores, int currentScore) {
        if (priorScores == null || priorScores.isEmpty()) {
            return new TrendStats(0, 0.0, 0.0, 0.0, false);
        }
        int n = priorScores.size();

        double mean = priorScores.stream().mapToInt(Integer::intValue).average().orElse(0.0);

        // Sample standard deviation (n-1 denominator) — Bessel-corrected; required for small samples.
        double variance = 0.0;
        for (int s : priorScores) {
            double d = s - mean;
            variance += d * d;
        }
        double stdDev = n > 1 ? Math.sqrt(variance / (n - 1)) : 0.0;

        // z-score: how many std-dev away the current run is from the recent mean.
        // Avoid divide-by-zero: when stdDev=0 (all prior runs identical), any non-equal
        // current score is technically infinite-z; we report 0 and let the caller decide.
        double zScore = stdDev > 0.0 ? (currentScore - mean) / stdDev : 0.0;

        boolean outlier = n >= MIN_SAMPLE_FOR_OUTLIER
                       && Math.abs(zScore) >= OUTLIER_Z_THRESHOLD;

        return new TrendStats(n, mean, stdDev, zScore, outlier);
    }

    /**
     * Result of {@link #compute}. Immutable record.
     *
     * @param sampleSize   number of prior runs used in the calculation
     * @param mean         arithmetic mean of prior scores
     * @param stdDev       sample standard deviation (n-1 denominator)
     * @param currentZScore (currentScore − mean) / stdDev; 0 when stdDev=0
     * @param outlier      true if {@code |zScore| ≥ 2.0} AND sampleSize ≥ 5
     */
    public record TrendStats(
            int    sampleSize,
            double mean,
            double stdDev,
            double currentZScore,
            boolean outlier
    ) {
        @SuppressWarnings("unchecked")
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("sampleSize",     sampleSize);
            // Round mean/stdDev/zScore to 2 decimal places for readability — the
            // dashboard's tabular-numeric font doesn't make 88.43219... easier to read.
            o.put("mean",           Math.round(mean    * 100.0) / 100.0);
            o.put("stdDev",         Math.round(stdDev  * 100.0) / 100.0);
            o.put("currentZScore",  Math.round(currentZScore * 100.0) / 100.0);
            o.put("outlier",        outlier);
            // Threshold + window declared in the block so an external reader doesn't
            // have to read the source code to understand the outlier definition.
            o.put("outlierThreshold", OUTLIER_Z_THRESHOLD);
            o.put("window",           DEFAULT_WINDOW);
            return o;
        }
    }
}
