package utils.governance;

import utils.governance.dto.AuditEntryDto;
import utils.orchestration.dto.OrchestrationSnapshotDto;
import utils.orchestration.dto.RetryStrategyDto;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Prevents high-impact orchestration recommendations from being acted on
 * when supporting evidence is weak.
 *
 * Confidence thresholds (advisory applies to all; these govern ACTIONABLE status):
 *   Minimization  → requires >= 0.75  (high: modifying coverage is irreversible in a run)
 *   Suppression   → requires >= 0.65  (medium-high: suppressing retries hides failures)
 *   Prioritization → requires >= 0.50 (medium: reordering is low-risk)
 *   Strategy      → always actionable (deterministic from clear signals)
 *
 * When a recommendation falls below its threshold:
 *   - It is DOWNGRADED to "advisory only" (not removed)
 *   - A guardrail entry is added to the audit log
 *   - The UI shows a confidence warning badge
 *
 * This prevents "confident-looking" recommendations from being blindly trusted
 * when the underlying signal is too thin to support them.
 */
public final class ConfidenceGuardrailService {

    // Thresholds per decision type
    private static final double THRESHOLD_MINIMIZE   = 0.75;
    private static final double THRESHOLD_SUPPRESS   = 0.65;
    private static final double THRESHOLD_PRIORITIZE = 0.50;

    private ConfidenceGuardrailService() {}

    public static class GuardrailResult {
        public int                  totalGuarded;        // decisions downgraded to advisory-only
        public List<AuditEntryDto>  guardrailEntries;    // audit records of what was guarded
        public List<String>         guardedWorkflows;    // workflows whose recommendations were guarded
    }

    /**
     * Evaluates all orchestration recommendations against confidence thresholds.
     * Does NOT modify the snapshot — callers display confidence warnings via audit entries.
     *
     * @param snapshot from OrchestrationSnapshotBuilder
     * @return GuardrailResult with count and audit entries for guarded decisions
     */
    public static GuardrailResult evaluate(OrchestrationSnapshotDto snapshot) {
        GuardrailResult result     = new GuardrailResult();
        result.guardrailEntries    = new ArrayList<>();
        result.guardedWorkflows    = new ArrayList<>();

        if (snapshot == null) {
            result.totalGuarded = 0;
            return result;
        }

        // Check minimization candidates
        if (snapshot.minimizationCandidates != null) {
            for (String wf : snapshot.minimizationCandidates) {
                double conf = confidenceFor(wf, snapshot);
                if (conf < THRESHOLD_MINIMIZE) {
                    result.guardedWorkflows.add(wf);
                    result.guardrailEntries.add(DecisionAuditLogger.log(
                            DecisionAuditLogger.DECISION_MINIMIZE_SUITE, wf,
                            String.format("Confidence %.2f below minimization threshold (%.2f) — downgraded to advisory-only",
                                    conf, THRESHOLD_MINIMIZE),
                            List.of("LOW_CONFIDENCE_GUARDRAIL"), conf,
                            "ADVISORY"));
                }
            }
        }

        // Check retry suppressions
        if (snapshot.executionPlan != null && snapshot.executionPlan.retryRecommendations != null) {
            for (RetryStrategyDto r : snapshot.executionPlan.retryRecommendations) {
                if (!r.shouldRetry && !r.suppressIfCascade) {
                    // Non-cascade suppression — check confidence via failure class
                    double suppConf = suppressionConfidence(r.failureClass);
                    if (suppConf < THRESHOLD_SUPPRESS) {
                        result.guardedWorkflows.add(r.testId);
                        result.guardrailEntries.add(DecisionAuditLogger.log(
                                DecisionAuditLogger.DECISION_SUPPRESS_RETRY, r.testId,
                                String.format("Suppression confidence %.2f below threshold (%.2f) for class %s — advisory only",
                                        suppConf, THRESHOLD_SUPPRESS, r.failureClass),
                                List.of("CONFIDENCE_GUARDRAIL", r.failureClass), suppConf,
                                "ADVISORY"));
                    }
                }
            }
        }

        // Check high-risk workflow prioritizations (critical/high only)
        if (snapshot.workflowRisks != null) {
            for (WorkflowRiskDto risk : snapshot.workflowRisks) {
                if (!"CRITICAL".equals(risk.riskLevel) && !"HIGH".equals(risk.riskLevel)) continue;
                if (risk.confidence < THRESHOLD_PRIORITIZE) {
                    result.guardedWorkflows.add(risk.workflow);
                    result.guardrailEntries.add(DecisionAuditLogger.log(
                            DecisionAuditLogger.DECISION_PRIORITIZE_SUITE, risk.workflow,
                            String.format("%s risk assessment has low confidence (%.2f) — treat as advisory",
                                    risk.riskLevel, risk.confidence),
                            List.of("LOW_CONFIDENCE_RISK", risk.riskLevel), risk.confidence,
                            "ADVISORY"));
                }
            }
        }

        result.totalGuarded = result.guardedWorkflows.size();
        return result;
    }

    /**
     * Returns whether a specific minimization candidate has sufficient confidence.
     */
    public static boolean isSafeToMinimize(String workflow, OrchestrationSnapshotDto snapshot) {
        return confidenceFor(workflow, snapshot) >= THRESHOLD_MINIMIZE;
    }

    /**
     * Returns whether a retry suppression has sufficient confidence.
     */
    public static boolean isSafeToSuppress(RetryStrategyDto strategy) {
        if (strategy.suppressIfCascade) return true; // cascade suppression is always high confidence
        return suppressionConfidence(strategy.failureClass) >= THRESHOLD_SUPPRESS;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static double confidenceFor(String workflow, OrchestrationSnapshotDto snapshot) {
        if (snapshot.workflowRisks == null) return 0.0;
        return snapshot.workflowRisks.stream()
                .filter(r -> workflow.equals(r.workflow))
                .mapToDouble(r -> r.confidence)
                .findFirst().orElse(0.0);
    }

    /** Maps failure class to suppression confidence (based on signal reliability). */
    private static double suppressionConfidence(String failureClass) {
        return switch (failureClass != null ? failureClass : "UNKNOWN") {
            case "PRODUCT_BUG"  -> 0.90; // assertion failures are deterministic
            case "AUTH_CASCADE" -> 0.85; // auth errors are well-classified
            case "NETWORK_TIMEOUT", "BACKEND_ERROR" -> 0.70;
            case "FLAKY_SELECTOR" -> 0.60;
            case "AUTOMATION_BUG" -> 0.55;
            default -> 0.40; // UNKNOWN — very low confidence
        };
    }
}
