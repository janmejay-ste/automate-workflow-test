package utils.history.calibration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.TrendComputationService;
import utils.history.dto.StabilityTrendDto;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Recommends calibrated stability thresholds based on real execution history.
 *
 * Current thresholds are design-time assumptions:
 *   CRITICAL  : score < 40
 *   UNSTABLE  : score < 70
 *
 * After accumulating runs with real test outcomes and developer feedback,
 * the actual distribution of scores at real failure points may differ.
 * This service analyzes that distribution and recommends adjusted thresholds.
 *
 * Algorithm:
 *   1. Load all stability records
 *   2. Separate truly_problematic tests (isFlaky=true OR high false alert rate)
 *      from stable tests
 *   3. Find the score at which the boundary between them produces the least
 *      misclassification
 *   4. Report as a recommendation with confidence score
 *
 * IMPORTANT: DO NOT auto-apply calibrations.
 * Output is ADVISORY ONLY. Human review required before changing thresholds.
 *
 * Minimum required tests for meaningful calibration: 10
 */
public final class StabilityThresholdCalibrator {
    private static final Logger LOG = LoggerFactory.getLogger(StabilityThresholdCalibrator.class);
    private static final int MIN_TESTS_FOR_CALIBRATION = 10;

    public static final class CalibrationRecommendation {
        public final int    currentUnstableThreshold;   // 70 (design-time assumption)
        public final int    currentCriticalThreshold;   // 40 (design-time assumption)
        public final int    recommendedUnstableThreshold;
        public final int    recommendedCriticalThreshold;
        public final double confidence;                  // 0.0–1.0
        public final String status;                      // INSUFFICIENT_DATA | NO_CHANGE | RECOMMEND_TIGHTER | RECOMMEND_LOOSER
        public final String rationale;
        public final int    sampleSize;

        private CalibrationRecommendation(int currentUnstable, int currentCritical,
                                           int recUnstable, int recCritical,
                                           double confidence, String status,
                                           String rationale, int sampleSize) {
            this.currentUnstableThreshold    = currentUnstable;
            this.currentCriticalThreshold    = currentCritical;
            this.recommendedUnstableThreshold = recUnstable;
            this.recommendedCriticalThreshold = recCritical;
            this.confidence                  = confidence;
            this.status                      = status;
            this.rationale                   = rationale;
            this.sampleSize                  = sampleSize;
        }
    }

    private static final int DESIGN_UNSTABLE_THRESHOLD  = 70;
    private static final int DESIGN_CRITICAL_THRESHOLD  = 40;

    private StabilityThresholdCalibrator() {}

    /**
     * Analyzes historical stability data and returns threshold recommendations.
     * Never modifies any configuration — advisory output only.
     */
    public static CalibrationRecommendation recommend() {
        List<StabilityTrendDto> records = TrendComputationService.computeStabilityTrends();

        if (records.size() < MIN_TESTS_FOR_CALIBRATION) {
            return new CalibrationRecommendation(
                    DESIGN_UNSTABLE_THRESHOLD, DESIGN_CRITICAL_THRESHOLD,
                    DESIGN_UNSTABLE_THRESHOLD, DESIGN_CRITICAL_THRESHOLD,
                    0.0, "INSUFFICIENT_DATA",
                    "Need at least " + MIN_TESTS_FOR_CALIBRATION
                            + " tests with run history. Currently: " + records.size() + ".",
                    records.size());
        }

        // Tests with empirically confirmed problems
        List<StabilityTrendDto> problematic = records.stream()
                .filter(r -> r.isFlaky || r.failureRate > 0.30)
                .collect(Collectors.toList());

        List<StabilityTrendDto> stable = records.stream()
                .filter(r -> !r.isFlaky && r.failureRate <= 0.10)
                .collect(Collectors.toList());

        if (problematic.isEmpty() || stable.isEmpty()) {
            return new CalibrationRecommendation(
                    DESIGN_UNSTABLE_THRESHOLD, DESIGN_CRITICAL_THRESHOLD,
                    DESIGN_UNSTABLE_THRESHOLD, DESIGN_CRITICAL_THRESHOLD,
                    0.30, "NO_CHANGE",
                    "Not enough separation between stable and problematic tests to calibrate. "
                    + "Continue collecting data.",
                    records.size());
        }

        // Find the optimal UNSTABLE threshold: highest problematic score (upper bound of flaky cluster)
        int maxProblematicScore = problematic.stream()
                .mapToInt(r -> r.stabilityScore).max().orElse(DESIGN_UNSTABLE_THRESHOLD);

        // Find the optimal CRITICAL threshold: P75 of problematic scores
        List<Integer> problematicScores = problematic.stream()
                .map(r -> r.stabilityScore).sorted().collect(Collectors.toList());
        int p75Index = (int) Math.ceil(problematicScores.size() * 0.75) - 1;
        int p75Score = problematicScores.get(Math.max(0, p75Index));

        int recUnstable  = Math.max(maxProblematicScore, DESIGN_UNSTABLE_THRESHOLD);
        int recCritical  = Math.max(p75Score, DESIGN_CRITICAL_THRESHOLD);

        // Ensure unstable > critical
        if (recCritical >= recUnstable) {
            recCritical = recUnstable - 10;
        }

        double confidence = Math.min(0.95, records.size() / 100.0);
        String status;
        if (recUnstable > DESIGN_UNSTABLE_THRESHOLD || recCritical > DESIGN_CRITICAL_THRESHOLD) {
            status = "RECOMMEND_TIGHTER";
        } else if (recUnstable < DESIGN_UNSTABLE_THRESHOLD || recCritical < DESIGN_CRITICAL_THRESHOLD) {
            status = "RECOMMEND_LOOSER";
        } else {
            status = "NO_CHANGE";
        }

        String rationale = String.format(
                "Based on %d tests: %d problematic (flaky/high-failure) and %d stable. "
                + "Max flaky score: %d. P75 critical band: %d. "
                + "Recommended thresholds: UNSTABLE<%d, CRITICAL<%d. "
                + "ADVISORY ONLY — requires human review before applying.",
                records.size(), problematic.size(), stable.size(),
                maxProblematicScore, p75Score, recUnstable, recCritical);

        LOG.info("StabilityThresholdCalibrator: {} — recUnstable={} recCritical={} confidence={}",
                status, recUnstable, recCritical, String.format("%.2f", confidence));

        return new CalibrationRecommendation(
                DESIGN_UNSTABLE_THRESHOLD, DESIGN_CRITICAL_THRESHOLD,
                recUnstable, recCritical,
                round2(confidence), status, rationale, records.size());
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
