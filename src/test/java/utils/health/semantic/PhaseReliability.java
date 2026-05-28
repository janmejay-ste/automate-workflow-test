package utils.health.semantic;

/**
 * Reliability percentage for one named phase (e.g. "EditorLoad", "AuthRecovery",
 * "GraphRender", "UrlTransition").  Derived from HealthTracker.successCounters
 * combined with failure counts in the same phase.
 *
 * This is the metric the user actually cares about: "Editor loads 9 of 10 = 90 %"
 * rather than "Passed tests 0, Failed tests 2".  One TestNG @Test method that
 * iterates 10 links and fails on the 10th should not collapse the report to
 * "0 passed".
 */
public final class PhaseReliability {

    public final String phase;
    public final int    successes;
    public final int    failures;
    public final double percentage;   // 0.0 – 100.0; -1 if undefined
    public final MetricConfidence confidence;

    public PhaseReliability(String phase, int successes, int failures) {
        this.phase     = phase;
        this.successes = successes;
        this.failures  = failures;
        int total = successes + failures;
        if (total == 0) {
            this.percentage = -1.0;
            this.confidence = MetricConfidence.UNKNOWN;
        } else {
            this.percentage = (successes * 100.0) / total;
            // Small sample sizes get PARTIAL confidence — one or two data points
            // are not enough to label a trend.
            this.confidence = (total >= 5) ? MetricConfidence.VALID : MetricConfidence.PARTIAL;
        }
    }

    /** Pretty-format for printReport(). */
    public String asLine() {
        if (percentage < 0) {
            return String.format("  %-20s : no data        [%s]", phase, confidence.label);
        }
        return String.format("  %-20s : %5.1f%%  (%d / %d successful) [%s]",
                phase, percentage, successes, successes + failures, confidence.label);
    }
}
