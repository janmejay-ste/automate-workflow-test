package utils;

import java.util.Map;

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

    // ────────────────────────── Flow types ──────────────────────────

    public enum FlowType {
        CRITICAL(2.0),
        CORE(1.5),
        SECONDARY(1.0);

        private final double multiplier;

        FlowType(double multiplier) {
            this.multiplier = multiplier;
        }

        public double multiplier() {
            return multiplier;
        }
    }

    /**
     * Map known context strings to flow types.
     * Anything not listed defaults to SECONDARY.
     */
    private static final Map<String, FlowType> FLOW_MAP = Map.ofEntries(
            // Critical flows (revenue / user-acquisition)
            Map.entry("Signup", FlowType.CRITICAL),
            Map.entry("Login", FlowType.CRITICAL),
            // Core flows (main product)
            Map.entry("HomePage", FlowType.CORE),
            Map.entry("NavigateToAutomate", FlowType.CORE),
            Map.entry("AutomateHome", FlowType.CORE),
            Map.entry("AutomateUX", FlowType.CORE));

    public static FlowType flowTypeOf(String context) {
        if (context == null)
            return FlowType.SECONDARY;
        // Try exact match first
        FlowType ft = FLOW_MAP.get(context);
        if (ft != null)
            return ft;
        // Try prefix/contains match for composite contexts like
        // "Signup.verifySignupFlow"
        String lower = context.toLowerCase();
        if (lower.contains("signup") || lower.contains("login"))
            return FlowType.CRITICAL;
        if (lower.contains("homepage") || lower.contains("automate"))
            return FlowType.CORE;
        return FlowType.SECONDARY;
    }

    // ────────────────────────── Thresholds (5-tier) ──────────────────────────

    public static final int HEALTHY_MIN = 90;
    public static final int MINOR_MIN = 75;
    public static final int DEGRADED_MIN = 60;
    public static final int AT_RISK_MIN = 40;
    // Below 40 = CRITICAL

    /** Kept for backward compat – old code references these */
    public static final int STABLE_MIN = HEALTHY_MIN;

    public static final int BUILD_FAIL_THRESHOLD = Integer.parseInt(System.getProperty("health.fail.score", "40"));

    // ────────────────────────── Caps ──────────────────────────

    /** Hard ceiling on total penalty so score never saturates silently. */
    public static final int MAX_TOTAL_PENALTY = 150;

    /** Maximum penalty from LOW warnings (0.5 × N, capped). */
    public static final double WARNING_CAP = 10.0;

    // ────────────────────────── EMA ──────────────────────────

    /** Exponential moving-average alpha for score smoothing. */
    public static final double EMA_ALPHA = 0.3;

    // ────────────────────────── Base penalties ──────────────────────────

    public static final double BASE_TEST_FAILURE = 15;
    public static final double BASE_JS_ERROR = 6;
    public static final double BASE_FALLBACK = 5;
    public static final double BASE_WARNING = 0.5;

    // Slow-page tiers
    public static final long SLOW_PAGE_MS = 5_000;
    public static final long VERY_SLOW_PAGE_MS = 10_000;
    public static final long CRITICAL_SLOW_MS = 20_000;

    public static final double BASE_SLOW_PAGE = 1;
    public static final double BASE_VERY_SLOW = 3;
    public static final double BASE_CRITICAL_SLOW = 6;

    // ────────────────────────── Penalty helpers ──────────────────────────

    /** Penalty for a test failure in the given context. */
    public static double testFailurePenalty(String context) {
        return BASE_TEST_FAILURE * flowTypeOf(context).multiplier();
    }

    /** Penalty for a JS error in the given context. */
    public static double jsErrorPenalty(String context) {
        return BASE_JS_ERROR * flowTypeOf(context).multiplier();
    }

    /** Penalty for a fallback navigation in the given context. */
    public static double fallbackPenalty(String context) {
        return BASE_FALLBACK * flowTypeOf(context).multiplier();
    }

    /** Penalty for a slow page, tiered by load time and flow type. */
    public static double slowPagePenalty(long loadTimeMs, String context) {
        double base;
        if (loadTimeMs >= CRITICAL_SLOW_MS) {
            base = BASE_CRITICAL_SLOW;
        } else if (loadTimeMs >= VERY_SLOW_PAGE_MS) {
            base = BASE_VERY_SLOW;
        } else if (loadTimeMs >= SLOW_PAGE_MS) {
            base = BASE_SLOW_PAGE;
        } else {
            return 0; // Not slow
        }
        return base * flowTypeOf(context).multiplier();
    }

    /**
     * Status label from score (5-tier).
     */
    public static String statusFromScore(int score) {
        if (score >= HEALTHY_MIN)
            return "HEALTHY";
        if (score >= MINOR_MIN)
            return "MINOR";
        if (score >= DEGRADED_MIN)
            return "DEGRADED";
        if (score >= AT_RISK_MIN)
            return "AT_RISK";
        return "CRITICAL";
    }

    // ────────────────────── Legacy bridge ──────────────────────

    /**
     * @deprecated Use the specific penalty methods instead.
     */
    @Deprecated
    public static int penalty(utils.health.HealthTracker.Severity severity, boolean criticalFlow) {
        return switch (severity) {
            case CRITICAL -> criticalFlow ? 12 : 4;
            case HIGH -> criticalFlow ? 8 : 3;
            case MEDIUM -> criticalFlow ? 4 : 1;
            case LOW -> 1;
        };
    }
}
