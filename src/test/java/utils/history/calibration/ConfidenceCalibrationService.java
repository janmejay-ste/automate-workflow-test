package utils.history.calibration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.calibration.FeedbackCaptureService.FeedbackEntry;
import utils.history.calibration.FeedbackCaptureService.FeedbackType;

import java.util.List;

/**
 * Validates declared confidence values against empirical outcomes.
 *
 * A high-confidence signal that is frequently wrong is more dangerous than
 * a low-confidence signal — it causes engineers to act on incorrect information
 * without questioning it. This service exposes overconfident signals.
 *
 * Calibration gap = declaredConfidence − validatedAccuracy
 *   gap > 0.20 : signal is overconfident   → OVERCONFIDENT
 *   gap > 0.10 : signal is slightly high   → CALIBRATED_HIGH
 *   gap < -0.10: signal is underconfident  → CALIBRATED_LOW
 *   otherwise  : signal is well-calibrated → CALIBRATED
 *
 * Minimum sample size for meaningful calibration: 10 feedback entries.
 * Below that threshold, this service returns INSUFFICIENT_DATA.
 */
public final class ConfidenceCalibrationService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfidenceCalibrationService.class);

    private static final int    MIN_SAMPLES_FOR_CALIBRATION = 10;
    private static final double OVERCONFIDENT_GAP_THRESHOLD = 0.20;
    private static final double HIGH_GAP_THRESHOLD          = 0.10;

    public static final class CalibrationResult {
        public final String signalType;
        public final double avgDeclaredConfidence;   // mean confidence score as declared
        public final double validatedAccuracy;        // fraction of positive feedback
        public final double calibrationGap;           // declaredConfidence - validatedAccuracy
        public final String calibrationStatus;        // OVERCONFIDENT | CALIBRATED_HIGH | CALIBRATED | CALIBRATED_LOW | INSUFFICIENT_DATA
        public final int    sampleSize;
        public final String recommendation;

        private CalibrationResult(String signalType, double avgDeclaredConfidence,
                                   double validatedAccuracy, double calibrationGap,
                                   String status, int sampleSize, String recommendation) {
            this.signalType            = signalType;
            this.avgDeclaredConfidence = avgDeclaredConfidence;
            this.validatedAccuracy     = validatedAccuracy;
            this.calibrationGap        = calibrationGap;
            this.calibrationStatus     = status;
            this.sampleSize            = sampleSize;
            this.recommendation        = recommendation;
        }
    }

    private ConfidenceCalibrationService() {}

    /**
     * Calibrates a specific signal type using accumulated feedback.
     *
     * @param signalType          e.g. "RegressionSpike", "AiFailureAnalysis"
     * @param declaredConfidences raw confidence values declared by the signal engine
     *                            (one per fired event, in chronological order)
     */
    public static CalibrationResult calibrate(String signalType, List<Double> declaredConfidences) {
        List<FeedbackEntry> feedback = FeedbackCaptureService.load(signalType);

        // Positive feedback types (signal was correct / useful)
        long positiveCount = feedback.stream()
                .filter(e -> isPositiveFeedback(e))
                .count();
        int total = feedback.size();

        if (total < MIN_SAMPLES_FOR_CALIBRATION || declaredConfidences.isEmpty()) {
            return new CalibrationResult(signalType, 0.0, 0.0, 0.0,
                    "INSUFFICIENT_DATA", total,
                    "Collect at least " + MIN_SAMPLES_FOR_CALIBRATION
                            + " feedback entries before calibrating (" + total + " so far).");
        }

        double avgDeclared   = declaredConfidences.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        double validatedAcc  = (double) positiveCount / total;
        double gap           = round2(avgDeclared - validatedAcc);

        String status        = classify(gap);
        String recommendation = buildRecommendation(status, signalType, gap, avgDeclared, validatedAcc);

        LOG.debug("ConfidenceCalibrationService: {} — declared={:.2f} validated={:.2f} gap={:.2f} status={}",
                signalType, avgDeclared, validatedAcc, gap, status);

        return new CalibrationResult(signalType, round2(avgDeclared), round2(validatedAcc),
                gap, status, total, recommendation);
    }

    /**
     * Convenience overload: calibrate using a single representative declared confidence.
     * Useful when the confidence value is stable (e.g. a fixed formula output).
     */
    public static CalibrationResult calibrate(String signalType, double declaredConfidence) {
        return calibrate(signalType, List.of(declaredConfidence));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isPositiveFeedback(FeedbackEntry e) {
        return switch (e.feedbackType) {
            case AI_CORRECT, TREND_USEFUL, RELEASE_ACCURATE -> e.value;
            case FALSE_ALERT, IGNORED_ALERT                 -> false;
        };
    }

    private static String classify(double gap) {
        if (gap > OVERCONFIDENT_GAP_THRESHOLD) return "OVERCONFIDENT";
        if (gap > HIGH_GAP_THRESHOLD)          return "CALIBRATED_HIGH";
        if (gap < -HIGH_GAP_THRESHOLD)         return "CALIBRATED_LOW";
        return "CALIBRATED";
    }

    private static String buildRecommendation(String status, String signalType,
                                               double gap, double declared, double validated) {
        return switch (status) {
            case "OVERCONFIDENT" -> String.format(
                    "%s declares %.0f%% confidence but only %.0f%% accuracy confirmed. "
                    + "Reduce declared confidence by ~%.0f%% or improve the signal algorithm.",
                    signalType, declared * 100, validated * 100, gap * 100);
            case "CALIBRATED_HIGH" -> String.format(
                    "%s is slightly overconfident (gap: +%.0f%%). Minor tuning recommended.",
                    signalType, gap * 100);
            case "CALIBRATED_LOW" -> String.format(
                    "%s understates its confidence (gap: %.0f%%). "
                    + "Signal performs better than declared — consider raising confidence.",
                    signalType, gap * 100);
            default -> signalType + " confidence is well-calibrated. No action needed.";
        };
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
