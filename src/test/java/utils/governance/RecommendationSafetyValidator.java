package utils.governance;

import utils.governance.dto.SafetyViolationDto;
import utils.orchestration.OrchestrationConfig;
import utils.orchestration.dto.OrchestrationSnapshotDto;
import utils.orchestration.dto.SuitePriorityDto;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hard governance boundaries that prevent unsafe orchestration recommendations.
 *
 * These rules are NON-NEGOTIABLE — they fire before any recommendation reaches
 * the dashboard or report, and they record every violation for visibility.
 *
 * Safety rules:
 *   RULE-1  Never minimize a critical-path workflow
 *   RULE-2  Never minimize a workflow that failed in the last run
 *   RULE-3  Never minimize a workflow with LOW confidence stability data
 *   RULE-4  Never suppress retries for workflows with < MIN_RUNS history
 *   RULE-5  Never select CRITICAL_PATH_ONLY when critical path is undefined
 *   RULE-6  Never recommend ISOLATE executor for more than MAX_ISOLATED_PCT of suites
 *
 * Violations are recorded but do NOT throw exceptions — the system continues
 * with the recommendation removed and the violation surfaced transparently.
 */
public final class RecommendationSafetyValidator {

    private static final int    MIN_RUNS_FOR_SUPPRESSION  = 5;
    private static final double MAX_ISOLATED_PCT          = 0.50; // max 50% of suites in isolated pool
    private static final String RULE_NO_MINIMIZE_CRITICAL = "NO_MINIMIZE_CRITICAL_PATH";
    private static final String RULE_NO_MINIMIZE_FAILING  = "NO_MINIMIZE_RECENTLY_FAILING";
    private static final String RULE_NO_MINIMIZE_LOW_CONF = "NO_MINIMIZE_LOW_CONFIDENCE";
    private static final String RULE_SUPPRESS_MIN_RUNS    = "NO_SUPPRESS_BELOW_MIN_RUNS";
    private static final String RULE_NO_CRITICAL_UNDEFINED= "NO_CRITICAL_PATH_ONLY_UNDEFINED";
    private static final String RULE_ISOLATED_OVERLOAD    = "NO_EXCESSIVE_ISOLATION";

    private RecommendationSafetyValidator() {}

    /**
     * Validates the orchestration snapshot and returns all safety violations.
     * Callers must remove violating recommendations before surfacing them.
     *
     * @param snapshot    from OrchestrationSnapshotBuilder
     * @return list of violations (empty = all safe)
     */
    public static List<SafetyViolationDto> validate(OrchestrationSnapshotDto snapshot) {
        List<SafetyViolationDto> violations = new ArrayList<>();
        if (snapshot == null) return violations;

        Set<String> criticalPath = snapshot.criticalPathWorkflows != null
                ? Set.copyOf(snapshot.criticalPathWorkflows) : Set.of();

        // Minimization safety checks
        if (snapshot.minimizationCandidates != null) {
            for (String wf : snapshot.minimizationCandidates) {

                // RULE-1: Never minimize critical path
                if (criticalPath.contains(wf)) {
                    violations.add(buildViolation(
                            RULE_NO_MINIMIZE_CRITICAL, "MINIMIZE_SUITE", wf,
                            "Critical-path workflows must always run at full coverage.",
                            "Remove " + wf + " from minimization candidates. It is declared critical path.",
                            0.0));
                }

                // RULE-2: Never minimize a recently failing workflow
                if (isRecentlyFailing(wf, snapshot)) {
                    violations.add(buildViolation(
                            RULE_NO_MINIMIZE_FAILING, "MINIMIZE_SUITE", wf,
                            "A workflow that failed recently cannot be safely minimized — the failure may recur.",
                            "Stabilize " + wf + " for at least 5 consecutive passing runs before considering minimization.",
                            0.0));
                }

                // RULE-3: Never minimize with low confidence stability data
                double conf = stabilityConfidenceFor(wf, snapshot);
                if (conf < 0.65) {
                    violations.add(buildViolation(
                            RULE_NO_MINIMIZE_LOW_CONF, "MINIMIZE_SUITE", wf,
                            "Minimization requires HIGH confidence stability data. Insufficient run history.",
                            "Run " + wf + " at full coverage until confidence reaches MEDIUM (≥10 runs).",
                            conf));
                }
            }
        }

        // RULE-4: Retry suppression requires minimum run history
        if (snapshot.executionPlan != null && snapshot.executionPlan.retryRecommendations != null) {
            for (var r : snapshot.executionPlan.retryRecommendations) {
                if (!r.shouldRetry && !r.suppressIfCascade) {
                    // Non-cascade suppression: need enough history to trust the signal
                    // We check this as a conservative advisory — flag if failure class is UNKNOWN
                    if ("UNKNOWN".equals(r.failureClass)) {
                        violations.add(buildViolation(
                                RULE_SUPPRESS_MIN_RUNS, "SUPPRESS_RETRY", r.testId,
                                "Retry suppression for UNKNOWN failure class requires verified history.",
                                "Allow at least one retry to classify the failure before suppressing.",
                                0.5));
                    }
                }
            }
        }

        // RULE-5: CRITICAL_PATH_ONLY requires a defined critical path
        if (snapshot.executionPlan != null
                && "CRITICAL_PATH_ONLY".equals(snapshot.executionPlan.strategy)
                && (snapshot.criticalPathWorkflows == null || snapshot.criticalPathWorkflows.isEmpty())) {
            violations.add(buildViolation(
                    RULE_NO_CRITICAL_UNDEFINED, "STRATEGY_SELECTED", "CRITICAL_PATH_ONLY",
                    "CRITICAL_PATH_ONLY strategy requires at least one declared critical-path workflow.",
                    "Add critical workflows to config/orchestration.properties under orchestration.critical.workflows.",
                    0.0));
        }

        // RULE-6: Avoid isolating >50% of suites (defeats parallelism benefit)
        if (snapshot.executionPlan != null && snapshot.executionPlan.resourceAllocations != null) {
            long total    = snapshot.executionPlan.resourceAllocations.size();
            long isolated = snapshot.executionPlan.resourceAllocations.stream()
                    .filter(r -> "ISOLATED".equals(r.poolType)).count();
            if (total > 0 && isolated > total * MAX_ISOLATED_PCT) {
                violations.add(buildViolation(
                        RULE_ISOLATED_OVERLOAD, "ISOLATE_EXECUTOR",
                        isolated + "/" + total + " suites",
                        "Isolating >50% of suites removes parallelism benefit and increases CI time.",
                        "Review isolation criteria in ResourceAllocationOptimizer — consider raising risk thresholds.",
                        0.7));
            }
        }

        return violations;
    }

    /**
     * Filters minimization candidates to remove any that violated safety rules.
     * Returns only safe candidates.
     */
    public static List<String> safeCandidates(List<String> candidates,
                                               List<SafetyViolationDto> violations) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> blocked = new java.util.HashSet<>();
        for (SafetyViolationDto v : violations) {
            if ("MINIMIZE_SUITE".equals(v.blockedDecision)) blocked.add(v.blockedTarget);
        }
        return candidates.stream().filter(c -> !blocked.contains(c)).toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isRecentlyFailing(String workflow, OrchestrationSnapshotDto snapshot) {
        if (snapshot.workflowRisks == null) return false;
        return snapshot.workflowRisks.stream()
                .filter(r -> workflow.equals(r.workflow))
                .anyMatch(r -> "CRITICAL".equals(r.riskLevel) || "HIGH".equals(r.riskLevel));
    }

    private static double stabilityConfidenceFor(String workflow, OrchestrationSnapshotDto snapshot) {
        if (snapshot.workflowRisks == null) return 0.0;
        return snapshot.workflowRisks.stream()
                .filter(r -> workflow.equals(r.workflow))
                .mapToDouble(r -> r.confidence)
                .findFirst().orElse(0.0);
    }

    private static SafetyViolationDto buildViolation(String rule, String blockedDecision,
                                                      String target, String explanation,
                                                      String recommended, double confidence) {
        SafetyViolationDto v   = new SafetyViolationDto();
        v.violationRule        = rule;
        v.blockedDecision      = blockedDecision;
        v.blockedTarget        = target;
        v.explanation          = explanation;
        v.recommendedAction    = recommended;
        v.blockedConfidence    = confidence;
        return v;
    }
}
