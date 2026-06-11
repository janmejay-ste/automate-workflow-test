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

    /**
     * Phase A.6.2 — number of distinct (page/test) contexts this cluster was observed in.
     * Together with {@link #count}, drives the {@link #amplification()} classifier:
     * count tells you how many events fired; distinctContextCount tells you how spread
     * those events were across contexts. The combination is what makes amplification
     * orthogonal to suppression — a {count=20, distinctContextCount=20} cluster is
     * NORMAL_REPEAT (real signal), a {count=20, distinctContextCount=1} cluster is
     * a RETRY_STORM (one underlying issue retrying).
     */
    public final int              distinctContextCount;

    public ErrorCluster(String title, int count, FailureDomain domain,
                        ClusterSeverity severity, String sampleMessage, String sampleContext,
                        String reason, int distinctContextCount) {
        this.title                = title;
        this.count                = count;
        this.domain               = domain;
        this.severity             = severity;
        this.sampleMessage        = sampleMessage;
        this.sampleContext        = sampleContext;
        this.reason               = reason;
        this.distinctContextCount = distinctContextCount;
    }

    /**
     * Backward-compat 7-arg constructor — defaults {@code distinctContextCount}
     * to {@code count}, the conservative assumption that "no info → assume
     * spread" so legacy callers don't accidentally trigger RETRY_STORM
     * classification on data that pre-dates context tracking.
     */
    public ErrorCluster(String title, int count, FailureDomain domain,
                        ClusterSeverity severity, String sampleMessage, String sampleContext,
                        String reason) {
        this(title, count, domain, severity, sampleMessage, sampleContext, reason, count);
    }

    /**
     * Phase A.6.2 — classify amplification pattern. Returns one of:
     * <ul>
     *   <li>{@code NONE}             — count == 1, no amplification</li>
     *   <li>{@code RETRY_STORM}      — high events-per-context ratio (≥ 5×)</li>
     *   <li>{@code BOOT_LOOP}        — heuristic: ≥ 3 distinct contexts with high count
     *       AND average events-per-context between 2 and 5 (looks like page reloads
     *       each triggering the same error)</li>
     *   <li>{@code EMBEDDED_REPEAT}  — distinctContextCount == 1 AND count ≥ 2 (intra-context loop)</li>
     *   <li>{@code NORMAL_REPEAT}    — multi-context with each context contributing roughly one</li>
     * </ul>
     *
     * <p>Values match {@code config/complexity_budget.json
     * → amplificationClassifications.values} — that file is the source of truth for
     * the enum and its severity rankings.</p>
     */
    public String amplification() {
        if (count <= 1) return "NONE";
        int distinct = Math.max(1, distinctContextCount);
        double ratio = (double) count / distinct;

        if (distinct == 1) {
            return "EMBEDDED_REPEAT";   // all events from one context
        }
        if (ratio >= 5.0) {
            return "RETRY_STORM";       // few contexts producing many events
        }
        if (distinct >= 3 && ratio >= 2.0 && ratio < 5.0) {
            return "BOOT_LOOP";         // multiple contexts each producing several events
        }
        return "NORMAL_REPEAT";         // multi-context, ~1 event per context
    }
}
