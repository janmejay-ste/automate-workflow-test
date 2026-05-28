package utils.governance.dto;

import java.util.List;

/**
 * Immutable record of a single orchestration decision.
 * Written by DecisionAuditLogger; read by GovernanceSnapshotBuilder and reports.
 */
public class AuditEntryDto {
    public String       timestamp;      // ISO-8601
    public String       runId;          // suite run identifier
    public String       decisionType;   // SUPPRESS_RETRY | PRIORITIZE_SUITE | MINIMIZE_SUITE |
                                        // EXPAND_COVERAGE | ISOLATE_EXECUTOR | STRATEGY_SELECTED
    public String       target;         // workflow or test name
    public String       reason;         // human-readable rationale
    public List<String> signals;        // which signals contributed (e.g. "AuthFailureCascade")
    public double       confidence;     // 0.0–1.0
    public String       confidenceLabel; // HIGH | MEDIUM | LOW | INSUFFICIENT_DATA
    public String       outcome;        // APPLIED | ADVISORY | BLOCKED_BY_SAFETY | OVERRIDDEN
    public String       overriddenBy;   // who/what overrode (null if not overridden)
}
