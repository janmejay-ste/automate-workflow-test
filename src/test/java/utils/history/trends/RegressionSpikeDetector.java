package utils.history.trends;

import org.json.simple.JSONObject;
import utils.history.HistoricalRunStore;
import utils.history.calibration.NoiseSuppressionService;
import utils.history.calibration.SignalValidationReport;
import utils.history.dto.RegressionSpikeDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects regression spikes across historical runs.
 *
 * Spike rule: if currentFailureRate > previousRollingAverage × 1.5 → spike.
 * Minimum window: 3 prior runs to establish a rolling average.
 *
 * Severity bands (spike multiplier):
 *   LOW      : multiplier 1.5 – 2.0
 *   MEDIUM   : multiplier 2.0 – 3.0
 *   HIGH     : multiplier 3.0 – 5.0
 *   CRITICAL : multiplier > 5.0
 */
public final class RegressionSpikeDetector {

    private static final double SPIKE_THRESHOLD    = 1.5;
    private static final int    MIN_BASELINE_RUNS  = 3;

    private RegressionSpikeDetector() {}

    public static RegressionSpikeDto detect() {
        List<JSONObject> runs = HistoricalRunStore.readAll();
        RegressionSpikeDto dto = new RegressionSpikeDto();
        dto.spikeDetected   = false;
        dto.affectedTests   = new ArrayList<>();
        dto.confidence      = 0.0;
        dto.confidenceLabel = "INSUFFICIENT_DATA";

        if (runs.size() < MIN_BASELINE_RUNS + 1) {
            dto.recommendation = "Not enough run history to detect spikes (" + runs.size() + " runs, need "
                    + (MIN_BASELINE_RUNS + 1) + ").";
            return dto;
        }

        // Compute failure rates per run
        List<Double> failureRates = new ArrayList<>();
        List<List<String>> failedTestsPerRun = new ArrayList<>();
        for (JSONObject run : runs) {
            JSONObject summary = objOrEmpty(run, "summary");
            int total  = num(summary, "totalTests", 0);
            int failed = num(summary, "failed", 0);
            double rate = total > 0 ? (double) failed / total : 0.0;
            failureRates.add(rate);

            // Collect failed test IDs from failures array
            List<String> failed_tests = new ArrayList<>();
            Object failuresObj = run.get("failures");
            if (failuresObj instanceof org.json.simple.JSONArray) {
                for (Object item : (org.json.simple.JSONArray) failuresObj) {
                    if (item instanceof JSONObject) {
                        String testId = str((JSONObject) item, "testId", null);
                        if (testId != null) failed_tests.add(testId);
                    }
                }
            }
            failedTestsPerRun.add(failed_tests);
        }

        // Latest run vs rolling average of prior MIN_BASELINE_RUNS runs
        double current = failureRates.get(failureRates.size() - 1);
        List<Double> baseline = failureRates.subList(
                failureRates.size() - 1 - MIN_BASELINE_RUNS,
                failureRates.size() - 1);
        double rollingAvg = baseline.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        dto.currentFailureRate  = round2(current);
        dto.baselineFailureRate = round2(rollingAvg);
        dto.detectedAt          = Instant.now().toString();

        // Confidence scales with number of runs
        double rawConf = Math.min(1.0, runs.size() / 20.0);
        dto.confidence      = round2(rawConf);
        dto.confidenceLabel = TrendConfidenceCalculator.label(dto.confidence);

        if (rollingAvg == 0 && current == 0) {
            dto.recommendation = "No failures detected in recent runs.";
            return dto;
        }

        double multiplier = (rollingAvg > 0) ? current / rollingAvg : (current > 0 ? 10.0 : 0.0);
        dto.spikeMultiplier = round2(multiplier);

        if (multiplier >= SPIKE_THRESHOLD) {
            dto.spikeDetected  = true;
            dto.spikeType      = "FAILURE_RATE_SPIKE";
            dto.severity       = classifySeverity(multiplier);
            dto.affectedTests  = failedTestsPerRun.get(failedTestsPerRun.size() - 1);
            dto.recommendation = buildRecommendation(dto.severity, dto.affectedTests.size(),
                    dto.currentFailureRate, dto.baselineFailureRate);

            // Record triggered for validation tracking
            SignalValidationReport.recordTriggered("RegressionSpike");

            // Check noise suppression (MEDIUM/LOW spikes can be suppressed after repeated firing)
            String suppressionKey = "spike_" + String.format("%.2f", dto.currentFailureRate);
            NoiseSuppressionService.SuppressionResult suppression =
                    NoiseSuppressionService.evaluate("RegressionSpike", suppressionKey,
                            dto.spikeType, cooldownRunsFor(dto.severity));
            if (suppression.suppressed) {
                dto.recommendation = "[Suppressed after " + suppression.consecutiveCount
                        + " consecutive runs] " + dto.recommendation;
                dto.spikeDetected = false;  // suppressed — not shown on dashboard this run
            }
        } else {
            // Spike resolved — reset suppression counter
            NoiseSuppressionService.reset("RegressionSpike",
                    "spike_" + String.format("%.2f", dto.currentFailureRate), "FAILURE_RATE_SPIKE");
            dto.recommendation = "Failure rate within normal variance (×"
                    + dto.spikeMultiplier + " of rolling average).";
        }
        return dto;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int cooldownRunsFor(String severity) {
        return switch (severity != null ? severity.toUpperCase() : "LOW") {
            case "CRITICAL", "HIGH" -> 0;   // always show
            case "MEDIUM"           -> 3;
            default                 -> 5;
        };
    }

    private static String classifySeverity(double multiplier) {
        if (multiplier > 5.0)  return "CRITICAL";
        if (multiplier > 3.0)  return "HIGH";
        if (multiplier > 2.0)  return "MEDIUM";
        return "LOW";
    }

    private static String buildRecommendation(String severity, int affectedCount,
                                               double current, double baseline) {
        String pct    = String.format("%.0f%%", current * 100);
        String bPct   = String.format("%.0f%%", baseline * 100);
        String prefix = switch (severity) {
            case "CRITICAL" -> "IMMEDIATE ACTION: ";
            case "HIGH"     -> "ACTION REQUIRED: ";
            default         -> "INVESTIGATE: ";
        };
        return prefix + affectedCount + " test(s) failed (" + pct
                + " failure rate vs baseline " + bPct + "). Review recent commits.";
    }

    private static JSONObject objOrEmpty(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    private static int     num(JSONObject o, String k, int def)    { Object v = o.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }
    private static String  str(JSONObject o, String k, String def) { Object v = o.get(k); return v != null ? v.toString() : def; }
    private static double  round2(double v)                         { return Math.round(v * 100.0) / 100.0; }
}
