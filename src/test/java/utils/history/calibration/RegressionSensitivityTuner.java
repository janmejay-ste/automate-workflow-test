package utils.history.calibration;

import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.HistoricalRunStore;
import utils.history.calibration.FeedbackCaptureService.FeedbackEntry;
import utils.history.calibration.FeedbackCaptureService.FeedbackType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Recommends an optimal regression spike detection multiplier based on
 * false positive rate measured from developer feedback.
 *
 * Current rule: currentFailureRate > rollingAverage × 1.5 → spike
 *
 * Problem: 1.5 is a design-time assumption. With real run history and feedback
 * confirming which spikes were genuine vs false positives, a better multiplier
 * can be computed empirically.
 *
 * Algorithm:
 *   1. Count false positive spikes (feedback: FALSE_ALERT on RegressionSpike)
 *   2. Count confirmed real spikes (feedback: TREND_USEFUL on RegressionSpike)
 *   3. If false positive rate > 0.35 → recommend tighter multiplier (raise by 0.2)
 *   4. If false positive rate < 0.10 and missed regressions detected → loosen (drop by 0.1)
 *   5. Otherwise → no change recommended
 *
 * ADVISORY ONLY. Do not auto-apply.
 *
 * Minimum 5 confirmed spike events required for meaningful calibration.
 */
public final class RegressionSensitivityTuner {
    private static final Logger LOG = LoggerFactory.getLogger(RegressionSensitivityTuner.class);

    private static final double CURRENT_MULTIPLIER     = 1.5;
    private static final int    MIN_EVENTS             = 5;
    private static final double HIGH_FP_RATE_THRESHOLD = 0.35;
    private static final double LOW_FP_RATE_THRESHOLD  = 0.10;

    public static final class TuningRecommendation {
        public final double currentMultiplier;
        public final double recommendedMultiplier;
        public final double falsePositiveRate;
        public final int    confirmedSpikes;
        public final int    falsePositiveSpikes;
        public final String status;          // INSUFFICIENT_DATA | NO_CHANGE | RECOMMEND_TIGHTER | RECOMMEND_LOOSER
        public final String reason;
        public final double confidence;

        private TuningRecommendation(double current, double recommended, double fpRate,
                                      int confirmed, int falsePositives,
                                      String status, String reason, double confidence) {
            this.currentMultiplier     = current;
            this.recommendedMultiplier = recommended;
            this.falsePositiveRate     = fpRate;
            this.confirmedSpikes       = confirmed;
            this.falsePositiveSpikes   = falsePositives;
            this.status                = status;
            this.reason                = reason;
            this.confidence            = confidence;
        }
    }

    private RegressionSensitivityTuner() {}

    /**
     * Analyzes spike detection feedback and returns a tuning recommendation.
     */
    public static TuningRecommendation recommend() {
        List<FeedbackEntry> spikeFeedback = FeedbackCaptureService.load("RegressionSpike");

        List<FeedbackEntry> confirmedEntries = spikeFeedback.stream()
                .filter(e -> e.feedbackType == FeedbackType.TREND_USEFUL && e.value)
                .collect(Collectors.toList());

        List<FeedbackEntry> falsePositiveEntries = spikeFeedback.stream()
                .filter(e -> e.feedbackType == FeedbackType.FALSE_ALERT)
                .collect(Collectors.toList());

        int confirmed     = confirmedEntries.size();
        int falsePositives = falsePositiveEntries.size();
        int total         = confirmed + falsePositives;

        if (total < MIN_EVENTS) {
            return new TuningRecommendation(CURRENT_MULTIPLIER, CURRENT_MULTIPLIER,
                    0.0, confirmed, falsePositives,
                    "INSUFFICIENT_DATA",
                    "Need at least " + MIN_EVENTS + " spike events with feedback to tune. "
                    + "Recorded so far: " + total + ".",
                    0.0);
        }

        double fpRate      = (double) falsePositives / total;
        double confidence  = Math.min(0.90, total / 30.0);

        double recommended;
        String status;
        String reason;

        if (fpRate > HIGH_FP_RATE_THRESHOLD) {
            // Too many false positives — make detection stricter
            recommended = round2(CURRENT_MULTIPLIER + 0.2 * Math.ceil(fpRate / HIGH_FP_RATE_THRESHOLD));
            status = "RECOMMEND_TIGHTER";
            reason = String.format(
                    "False positive rate %.0f%% is too high. "
                    + "Raising the multiplier from %.1f to %.1f will require a larger failure rate "
                    + "increase before spiking, reducing noise.",
                    fpRate * 100, CURRENT_MULTIPLIER, recommended);
        } else if (fpRate < LOW_FP_RATE_THRESHOLD) {
            // Very accurate — check if any regressions are being missed
            // Without explicit missed-regression feedback, we assume the current threshold
            // is working and recommend no change unless run count is large enough
            List<JSONObject> runs = HistoricalRunStore.readAll();
            if (runs.size() > 50 && fpRate < 0.05) {
                recommended = round2(Math.max(1.2, CURRENT_MULTIPLIER - 0.1));
                status  = "RECOMMEND_LOOSER";
                reason  = String.format(
                        "False positive rate %.0f%% is very low with %d runs. "
                        + "Can be loosened slightly from %.1f to %.1f to catch earlier regressions.",
                        fpRate * 100, runs.size(), CURRENT_MULTIPLIER, recommended);
            } else {
                recommended = CURRENT_MULTIPLIER;
                status  = "NO_CHANGE";
                reason  = String.format(
                        "False positive rate %.0f%% is acceptable. Current multiplier %.1f is working well.",
                        fpRate * 100, CURRENT_MULTIPLIER);
            }
        } else {
            recommended = CURRENT_MULTIPLIER;
            status = "NO_CHANGE";
            reason = String.format(
                    "False positive rate %.0f%% is within acceptable bounds. No change needed.",
                    fpRate * 100);
        }

        LOG.info("RegressionSensitivityTuner: {} — fpRate={:.2f} recMultiplier={}",
                status, fpRate, recommended);

        return new TuningRecommendation(CURRENT_MULTIPLIER, recommended, round2(fpRate),
                confirmed, falsePositives, status, reason, round2(confidence));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
