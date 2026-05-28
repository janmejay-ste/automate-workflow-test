package utils.history.trends;

/**
 * Computes confidence scores for trend intelligence based on sample size.
 *
 * 3 runs ≠ 300 runs. Weak statistical trends must never be presented
 * as authoritative. This calculator ensures all trend consumers receive
 * an honest confidence signal alongside computed intelligence.
 *
 * Confidence bands by sample size:
 *   INSUFFICIENT_DATA : < 3  samples  → confidence 0.0
 *   LOW               : 3–9  samples  → confidence 0.1–0.49
 *   MEDIUM            : 10–29 samples → confidence 0.5–0.79
 *   HIGH              : 30+  samples  → confidence 0.8–1.0
 */
public final class TrendConfidenceCalculator {

    private static final int THRESHOLD_LOW    = 3;
    private static final int THRESHOLD_MEDIUM = 10;
    private static final int THRESHOLD_HIGH   = 30;

    private TrendConfidenceCalculator() {}

    /**
     * Computes raw confidence in [0.0, 1.0] from the number of data points.
     */
    public static double compute(int sampleSize) {
        if (sampleSize < THRESHOLD_LOW) return 0.0;
        if (sampleSize < THRESHOLD_MEDIUM) {
            // Linear scale from 0.1 to 0.49 within [3, 9]
            return 0.10 + (sampleSize - THRESHOLD_LOW) * (0.39 / (THRESHOLD_MEDIUM - THRESHOLD_LOW));
        }
        if (sampleSize < THRESHOLD_HIGH) {
            // Linear scale from 0.50 to 0.79 within [10, 29]
            return 0.50 + (sampleSize - THRESHOLD_MEDIUM) * (0.29 / (THRESHOLD_HIGH - THRESHOLD_MEDIUM));
        }
        // Linear scale from 0.80 to 1.0 for 30+, capped at 1.0
        double raw = 0.80 + (sampleSize - THRESHOLD_HIGH) * (0.20 / 70.0);
        return Math.min(1.0, raw);
    }

    /**
     * Human-readable label for a confidence value.
     */
    public static String label(double confidence) {
        if (confidence <= 0.0)  return "INSUFFICIENT_DATA";
        if (confidence < 0.50)  return "LOW";
        if (confidence < 0.80)  return "MEDIUM";
        return "HIGH";
    }

    /**
     * Convenience method: compute confidence label directly from sample size.
     */
    public static String labelFromSize(int sampleSize) {
        return label(compute(sampleSize));
    }

    /**
     * Combines multiple confidence signals into an aggregate score.
     * Uses weighted harmonic-mean approximation (penalises low-confidence signals).
     */
    public static double combine(double... confidences) {
        if (confidences.length == 0) return 0.0;
        double sumReciprocal = 0.0;
        int validCount = 0;
        for (double c : confidences) {
            if (c > 0.0) {
                sumReciprocal += 1.0 / c;
                validCount++;
            }
        }
        if (validCount == 0) return 0.0;
        return validCount / sumReciprocal;
    }
}
