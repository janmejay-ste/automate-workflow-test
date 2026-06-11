package utils;

/**
 * Central configuration for the health-scoring model.
 *
 * Scoring formula:
 * effectivePenalty = min(rawPenalty, MAX_TOTAL_PENALTY)
 * rawScore = max(0, 100 − effectivePenalty)
 * smoothedScore = EMA_ALPHA × rawScore + (1 − EMA_ALPHA) ×
 * previousSmoothedScore
 */
public final class HealthPolicy {

    private HealthPolicy() {
    }

    // ────────────────────────── Threshold Bands (QA Intelligence)
    // ──────────────────────────

    public static final int EXCELLENT_MIN = 90;
    public static final int HEALTHY_MIN = 75;
    public static final int WARNING_MIN = 50;
    public static final int POOR_MIN = 30;

    // ────────────────────────── Risk & Release Mapping ──────────────────────────

    public enum ReleaseStatus {
        READY("Release Allowed", "badge-healthy"),
        WARNING("Review Recommended", "badge-minor"),
        AT_RISK("Decision Required", "badge-critical"),
        BLOCKED("Release Prohibited", "badge-critical");

        public final String label;
        public final String cssClass;

        ReleaseStatus(String label, String cssClass) {
            this.label = label;
            this.cssClass = cssClass;
        }
    }

    // ────────────────────────── Weighted Scoring ──────────────────────────

    public static int calculateWeightedScore(int smokeScore, int regressionScore, int fullScore) {
        // Floor Rules: If critical suites are failing, weighted score alone can't hide
        // it
        double weighted = (smokeScore * 0.5) + (regressionScore * 0.3) + (fullScore * 0.2);
        return (int) Math.round(weighted);
    }

    // ────────────────────────── Base Penalties ──────────────────────────

    public static final double BASE_TEST_FAILURE = 15;
    public static final double BASE_JS_ERROR = 1.0; // Reduced for unique errors
    public static final double TOTAL_JS_PENALTY_CAP = 20.0;
    public static final double BASE_FALLBACK = 5;
    public static final double BASE_WARNING = 0.5;
    public static final double WARNING_CAP = 10.0;
    public static final int LOCATOR_SAMPLE_THRESHOLD = 20;

    public static final int BUILD_FAIL_THRESHOLD = Integer.parseInt(System.getProperty("health.fail.score", "40"));
    public static final int MAX_TOTAL_PENALTY = 150;
    public static final double EMA_ALPHA = 0.3;

    // ────────────────────────── Flow Multipliers ──────────────────────────

    public enum FlowType {
        CRITICAL(2.0),
        CORE(1.5),
        SECONDARY(1.0);

        private final double multiplier;

        FlowType(double m) {
            this.multiplier = m;
        }

        public double multiplier() {
            return multiplier;
        }
    }

    public static FlowType flowTypeOf(String context) {
        if (context == null)
            return FlowType.SECONDARY;
        String s = context.toLowerCase();
        if (s.contains("smoke") || s.contains("login") || s.contains("signup"))
            return FlowType.CRITICAL;
        if (s.contains("sanity") || s.contains("homepage") || s.contains("navigation"))
            return FlowType.CORE;
        return FlowType.SECONDARY;
    }

    public static double testFailurePenalty(String context) {
        return BASE_TEST_FAILURE * flowTypeOf(context).multiplier();
    }

    public static double jsErrorPenalty(String context) {
        return BASE_JS_ERROR * flowTypeOf(context).multiplier();
    }

    public static double fallbackPenalty(String context) {
        return BASE_FALLBACK * flowTypeOf(context).multiplier();
    }

    public static double slowPagePenalty(long loadTimeMs, String context) {
        // Simple tiered logic for legacy HealthTracker compatibility
        if (loadTimeMs >= 20000)
            return 6.0 * flowTypeOf(context).multiplier();
        if (loadTimeMs >= 10000)
            return 3.0 * flowTypeOf(context).multiplier();
        if (loadTimeMs >= 5000)
            return 1.0 * flowTypeOf(context).multiplier();
        return 0;
    }

    public static String statusFromScore(int score) {
        if (score >= EXCELLENT_MIN)
            return "EXCELLENT";
        if (score >= HEALTHY_MIN)
            return "HEALTHY";
        if (score >= WARNING_MIN)
            return "WARNING";
        if (score >= POOR_MIN)
            return "POOR";
        return "CRITICAL";
    }

    // ────────────────────────── Phase C1 — Diminishing-Returns Curve ──────────
    /**
     * Smoothly applies a cap to a raw penalty value without the discontinuity of
     * {@code Math.min(raw, cap)}.
     *
     * <p>Default behaviour is the legacy hard cap (matches all historical runs).
     * Set {@code -Dpenalty.curveCap=true} to switch to the asymptotic curve
     * {@code f(x) = cap * (1 - exp(-x/cap))}. Values:</p>
     * <pre>
     *   f(0)        = 0
     *   f(cap)      ≈ 0.632 × cap
     *   f(2 × cap)  ≈ 0.865 × cap
     *   f(∞)        → cap   (never reaches)
     * </pre>
     *
     * <p>Useful where the existing cap collapses information: with a 20-point JS
     * cap, runs with 25 and 200 errors score identically today; under the curve
     * they score 12.85 and 19.87 — different enough to surface a problem.</p>
     *
     * <p>Behind a feature flag so trend comparison stays apples-to-apples until
     * the flip is explicit. Annotate the deploy on the dashboard trend chart.</p>
     */
    public static double applyDiminishingReturns(double rawValue, double cap) {
        if (rawValue <= 0 || cap <= 0) return 0.0;
        boolean useCurve = Boolean.parseBoolean(
                System.getProperty("penalty.curveCap", "false"));
        if (!useCurve) {
            return Math.min(rawValue, cap);
        }
        return cap * (1.0 - Math.exp(-rawValue / cap));
    }
}
