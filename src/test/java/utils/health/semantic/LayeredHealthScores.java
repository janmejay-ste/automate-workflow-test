package utils.health.semantic;

import java.util.List;

/**
 * Four independent scores derived from clustered errors, reliability data, and
 * business-transaction outcomes.  Replaces the single "0/100" number that
 * collapsed every signal into one penalty pool.
 *
 *   Product Health         — app stability (auth, editor, frontend defects)
 *   Framework Health       — automation stability (selectors, retries, WebDriver)
 *   Telemetry Confidence   — observability trust (missing metrics, UNKNOWN timings)
 *   Business Outcome       — user-intent success rate (workflows/integrations actually completed)
 *
 * Each score is on the same 0–100 scale but only counts signals from its own
 * domain.  An auth redirect tanks Product Health without touching Framework Health.
 * A workflow that didn't persist tanks Business Outcome even if Product Health is good
 * — the inverse of the old "the app rendered, ship it" failure mode.
 */
public final class LayeredHealthScores {

    /** Sentinel for "no data" — the renderer should display this as "N/A" / "—". */
    public static final int NO_DATA = -1;

    public final int productHealth;
    public final int frameworkHealth;
    public final int telemetryConfidence;
    public final int businessOutcome;   // NO_DATA when no transactions recorded

    public LayeredHealthScores(int product, int framework, int telemetry, int businessOutcome) {
        this.productHealth       = clamp(product);
        this.frameworkHealth     = clamp(framework);
        this.telemetryConfidence = clamp(telemetry);
        this.businessOutcome     = (businessOutcome == NO_DATA) ? NO_DATA : clamp(businessOutcome);
    }

    /** Backwards-compatible 3-arg constructor — business outcome defaults to NO_DATA. */
    public LayeredHealthScores(int product, int framework, int telemetry) {
        this(product, framework, telemetry, NO_DATA);
    }

    /**
     * Compute layered scores from a set of clusters + a count of unknown-timing
     * telemetry gaps.
     *
     * Each CRITICAL cluster:  −20 to its domain's score
     * Each HIGH cluster:      −10
     * Each MEDIUM cluster:    −5
     * Each LOW cluster:       −1 (only first three count — clustered noise is bounded)
     * Each INFO cluster:      0
     *
     * Telemetry confidence is 100 minus the share of measurements that came back
     * UNKNOWN — penalised proportionally, capped at −50 so one missing metric in
     * a healthy run can't tank confidence.
     */
    /**
     * Compute layered scores from clusters, telemetry counts, test failures, and
     * business-transaction outcomes.  All arguments are required; pass 0 for
     * business-transaction counts when no business outcomes were recorded and the
     * business score will return NO_DATA.
     *
     * Each CRITICAL cluster:  −20 to its domain's score
     * Each HIGH cluster:      −10
     * Each MEDIUM cluster:    −5
     * Each LOW cluster:       −1 (only first three count — clustered noise is bounded)
     * Each INFO cluster:      0
     *
     * Business outcome scoring:
     *   score = round( (success * 100 + partial * 50) / (success + partial + failed) )
     *   ABORTED and STARTED transactions are excluded.
     *   No scorable transactions → NO_DATA.
     */
    public static LayeredHealthScores compute(List<ErrorCluster> clusters,
                                              int unknownTimingCount,
                                              int validTimingCount,
                                              int testFailuresProduct,
                                              int testFailuresFramework,
                                              int businessSuccess,
                                              int businessPartial,
                                              int businessFailed) {
        LayeredHealthScores base = compute(clusters, unknownTimingCount, validTimingCount,
                                           testFailuresProduct, testFailuresFramework);

        int scorable = businessSuccess + businessPartial + businessFailed;
        int bizScore = (scorable == 0)
                ? NO_DATA
                : (int) Math.round((businessSuccess * 100.0 + businessPartial * 50.0) / scorable);

        return new LayeredHealthScores(base.productHealth, base.frameworkHealth,
                                       base.telemetryConfidence, bizScore);
    }

    public static LayeredHealthScores compute(List<ErrorCluster> clusters,
                                              int unknownTimingCount,
                                              int validTimingCount,
                                              int testFailuresProduct,
                                              int testFailuresFramework) {
        int product   = 100;
        int framework = 100;

        if (clusters != null) {
            int lowCounted = 0;
            for (ErrorCluster c : clusters) {
                int delta = switch (c.severity) {
                    case CRITICAL -> 20;
                    case HIGH     -> 10;
                    case MEDIUM   -> 5;
                    case LOW      -> (lowCounted++ < 3) ? 1 : 0;
                    case INFO     -> 0;
                };
                switch (c.domain) {
                    case PRODUCT, INFRASTRUCTURE -> product -= delta;
                    case FRAMEWORK              -> framework -= delta;
                    case TELEMETRY, OBSERVABILITY, UNKNOWN -> { /* no impact on P or F */ }
                }
            }
        }
        // TestNG-reported failures contribute on top of cluster-derived penalties.
        product   -= testFailuresProduct   * 15;
        framework -= testFailuresFramework * 15;

        // Telemetry confidence: ratio of valid to total timing samples
        int totalTimings = validTimingCount + unknownTimingCount;
        int telemetry;
        if (totalTimings == 0) {
            telemetry = 100;                                     // nothing to measure ≠ broken
        } else {
            int unknownPct = (int) Math.round(100.0 * unknownTimingCount / totalTimings);
            telemetry = 100 - Math.min(unknownPct, 50);          // cap loss at -50
        }

        return new LayeredHealthScores(product, framework, telemetry);
    }

    public String productStatus()    { return statusBand(productHealth); }
    public String frameworkStatus()  { return statusBand(frameworkHealth); }
    public String telemetryStatus()  { return statusBand(telemetryConfidence); }
    public String businessStatus()   { return businessOutcome == NO_DATA ? "NO DATA" : statusBand(businessOutcome); }

    /** {@code true} if no business transactions were recorded — score should render as "—". */
    public boolean businessUnscored() { return businessOutcome == NO_DATA; }

    private static String statusBand(int score) {
        if (score >= 90) return "EXCELLENT";
        if (score >= 75) return "GOOD";
        if (score >= 50) return "MODERATE";
        if (score >= 30) return "POOR";
        return "CRITICAL";
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
}
