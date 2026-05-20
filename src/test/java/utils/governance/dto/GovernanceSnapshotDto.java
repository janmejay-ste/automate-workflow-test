package utils.governance.dto;

import java.util.List;

/**
 * Single source of computed governance intelligence.
 * All consumers (DashboardBuilder, GovernanceReportGenerator, ReleaseNarrator)
 * read from this DTO — no consumer recomputes governance logic.
 */
public class GovernanceSnapshotDto {
    public String computedAt;
    public String runId;

    // Decision audit
    public List<AuditEntryDto>  recentDecisions;      // decisions from this run
    public int                  totalDecisionsLogged;

    // Explainability
    public List<ExplanationDto> explanations;          // why each major decision was made
    public String               strategyExplanation;   // headline strategy explanation

    // Safety
    public List<SafetyViolationDto> safetyViolations;  // blocked recommendations
    public int                      violationCount;    // how many were blocked this run
    public boolean                  safetyClean;       // true = no violations

    // Confidence guardrails
    public int    lowConfidenceDecisionCount;   // decisions flagged for low confidence
    public int    guardedToAdvisoryCount;       // high-impact decisions downgraded to advisory-only

    // Suppression audit
    public List<SuppressionRecordDto> suppressionHistory; // persistent suppression records
    public int    totalSuppressionsThisRun;
    public int    confirmedFalseSuppressions;    // times suppression hid a real problem
    public double suppressionAccuracyPct;        // % of suppressions that were correct

    // Human overrides
    public List<HumanOverrideDto> recentOverrides;
    public int    totalOverridesLogged;
    public int    effectiveOverrideCount;        // overrides later validated as correct
    public double overrideEffectivenessPct;      // % of overrides that improved outcome

    // Governance health
    public String governanceHealth;   // HEALTHY | MONITORING | AT_RISK | CRITICAL
    public String governanceComment;  // one-line summary for dashboard
    public List<String> governanceAlerts; // concerning patterns detected
}
