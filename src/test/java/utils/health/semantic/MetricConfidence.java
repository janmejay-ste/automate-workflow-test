package utils.health.semantic;

/**
 * Confidence label attached to every recorded metric so the dashboard can distinguish
 * "we measured this and it was bad" from "we couldn't measure this at all".
 *
 * Before this layer existed, missing metrics (0-ms timings, absent navigation API
 * results) were silently collapsed into "bad", which is operationally dishonest.
 *
 * Ordered from highest trust to lowest:
 *   VALID    → measurement completed, result is trustworthy
 *   PARTIAL  → measurement completed but some inputs were estimated/fallback
 *   UNKNOWN  → measurement could not be made (instrumentation absent / stopwatch never ran)
 *   FAILED   → measurement was attempted and threw (browser API error, timeout)
 */
public enum MetricConfidence {

    VALID("Valid",   "Trustworthy — measurement succeeded with all inputs present"),
    PARTIAL("Partial", "Partial — measurement succeeded but used fallback inputs"),
    UNKNOWN("Unknown", "Unknown — instrumentation never produced a value"),
    FAILED("Failed",  "Failed — measurement was attempted but errored");

    public final String label;
    public final String description;

    MetricConfidence(String label, String description) {
        this.label       = label;
        this.description = description;
    }

    /**
     * Convenience: classify a load-time reading into a confidence band.
     * Negative or zero → UNKNOWN (stopwatch never ran).
     * Otherwise → VALID.
     */
    public static MetricConfidence fromLoadTime(long ms) {
        if (ms <= 0) return UNKNOWN;
        return VALID;
    }
}
