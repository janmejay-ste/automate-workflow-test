package utils.health.semantic;

/**
 * One aggregated cluster of error events that share the same root cause.
 *
 * Replaces flat "JS Errors: 36" reporting with named, severity-tagged buckets:
 *   zaraz duplicate injection │ 18 │ LOW    │ OBSERVABILITY
 *   appendChild of null       │  2 │ HIGH   │ PRODUCT
 *   auth network failures     │  4 │ HIGH   │ INFRASTRUCTURE
 *
 * The dashboard renders one row per cluster.
 */
public final class ErrorCluster {

    public final String           title;          // Human-readable, derived from sample message
    public final int              count;          // Number of raw events collapsed into this cluster
    public final FailureDomain    domain;
    public final ClusterSeverity  severity;
    public final String           sampleMessage;  // Representative original line
    public final String           sampleContext;
    public final String           reason;         // Why classifier picked this domain/severity

    public ErrorCluster(String title, int count, FailureDomain domain,
                        ClusterSeverity severity, String sampleMessage, String sampleContext,
                        String reason) {
        this.title         = title;
        this.count         = count;
        this.domain        = domain;
        this.severity      = severity;
        this.sampleMessage = sampleMessage;
        this.sampleContext = sampleContext;
        this.reason        = reason;
    }
}
