package utils.urlvalidator;

import utils.health.semantic.ClusterSeverity;
import utils.health.semantic.FailureDomain;

/**
 * A classified URL issue ready for inclusion in the semantic snapshot.
 *
 * Severity and domain come from the semantic layer's existing enums so URL
 * findings render with the same badges as other clusters — no parallel
 * taxonomy to maintain.
 */
public final class UrlFinding {

    public final UrlValidationResult result;
    public final UrlFindingType      type;
    public final ClusterSeverity     severity;
    public final FailureDomain       domain;
    public final String              reason;

    public UrlFinding(UrlValidationResult result, UrlFindingType type,
                      ClusterSeverity severity, FailureDomain domain, String reason) {
        this.result   = result;
        this.type     = type;
        this.severity = severity;
        this.domain   = domain;
        this.reason   = reason;
    }
}
