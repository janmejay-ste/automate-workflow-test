package utils.health.semantic;

/**
 * Severity assigned to an aggregated error cluster — separate from the per-error
 * severity used in raw logging.  A cluster of 18 "zaraz is loaded twice" lines is
 * LOW regardless of how many times it repeated; a cluster of 2 "appendChild of null"
 * lines is HIGH because the underlying defect is real.
 */
public enum ClusterSeverity {

    INFO("Info",     "Diagnostic only — not actionable"),
    LOW("Low",       "Known third-party noise or non-actionable warning"),
    MEDIUM("Medium", "Should be triaged in next sprint"),
    HIGH("High",     "Actionable defect — fix before next release"),
    CRITICAL("Critical", "Release-blocker — flow broken or core defect");

    public final String label;
    public final String description;

    ClusterSeverity(String label, String description) {
        this.label       = label;
        this.description = description;
    }
}
