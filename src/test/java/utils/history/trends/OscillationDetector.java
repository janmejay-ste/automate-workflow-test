package utils.history.trends;

import java.util.List;

/**
 * Detects PASS→FAIL oscillation patterns in a test's recent history.
 *
 * Oscillation = alternating outcome transitions, not just repeated failures.
 * A test that always fails is not flaky — it's broken. A test that passes
 * and fails unpredictably is the true flakiness signal.
 */
public final class OscillationDetector {

    public static final class Result {
        public final boolean oscillationDetected;
        public final double  oscillationRate;    // transitions / (totalOutcomes - 1)
        public final String  severity;           // NONE | LOW | MEDIUM | HIGH | CRITICAL

        private Result(boolean detected, double rate, String severity) {
            this.oscillationDetected = detected;
            this.oscillationRate     = rate;
            this.severity            = severity;
        }
    }

    private OscillationDetector() {}

    /**
     * Analyzes a list of outcome strings ("PASS" / "FAIL") from oldest to newest.
     */
    public static Result detect(List<String> recentHistory) {
        if (recentHistory == null || recentHistory.size() < 3) {
            return new Result(false, 0.0, "NONE");
        }

        int transitions = countTransitions(recentHistory);
        int maxPossibleTransitions = recentHistory.size() - 1;
        double rate = maxPossibleTransitions > 0
                ? (double) transitions / maxPossibleTransitions
                : 0.0;

        String severity;
        if (rate == 0.0) {
            severity = "NONE";
        } else if (rate < 0.20) {
            severity = "LOW";
        } else if (rate < 0.40) {
            severity = "MEDIUM";
        } else if (rate < 0.60) {
            severity = "HIGH";
        } else {
            severity = "CRITICAL";
        }

        boolean detected = rate >= 0.20;
        return new Result(detected, round2(rate), severity);
    }

    private static int countTransitions(List<String> history) {
        int count = 0;
        for (int i = 1; i < history.size(); i++) {
            String prev = history.get(i - 1);
            String curr = history.get(i);
            if (!prev.equals(curr)) {
                count++;
            }
        }
        return count;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
