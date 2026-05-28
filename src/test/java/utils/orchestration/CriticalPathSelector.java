package utils.orchestration;

import utils.correlation.dto.WorkflowHealthDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Identifies business-critical workflows that always receive highest execution
 * priority and strongest gating weight — regardless of current health.
 *
 * Critical path workflows:
 *   1. Declared in config/orchestration.properties (orchestration.critical.workflows)
 *   2. Any workflow whose failure would cascade to >= CRITICAL_DOWNSTREAM_THRESHOLD others
 *   3. Any workflow currently in CRITICAL health (health < 50)
 *
 * Critical path workflows always:
 *   - Execute first (P1)
 *   - Receive maximum retry attention
 *   - Are never minimized or sampled down
 *   - Block release when failed (regardless of overall score)
 */
public final class CriticalPathSelector {

    private static final int CRITICAL_DOWNSTREAM_THRESHOLD = 5;

    private CriticalPathSelector() {}

    /**
     * Returns the list of workflows on the critical execution path.
     *
     * @param workflowHealth  per-workflow health from CorrelationSnapshotBuilder
     * @return workflow names on critical path, sorted alphabetically for determinism
     */
    public static List<String> select(List<WorkflowHealthDto> workflowHealth) {
        Set<String> declared = Set.copyOf(OrchestrationConfig.getCriticalWorkflows());
        Set<String> result   = new java.util.LinkedHashSet<>(declared);

        if (workflowHealth != null) {
            for (WorkflowHealthDto w : workflowHealth) {
                // Cascade-critical: failure here propagates widely
                if (w.downstreamImpact >= CRITICAL_DOWNSTREAM_THRESHOLD) {
                    result.add(w.workflow);
                }
                // Currently critical health: needs maximum attention
                if ("CRITICAL".equals(w.status)) {
                    result.add(w.workflow);
                }
            }
        }

        return result.stream().sorted().collect(Collectors.toList());
    }

    /**
     * Returns true if the given workflow is on the critical path.
     */
    public static boolean isCritical(String workflow, List<String> criticalPath) {
        if (workflow == null || criticalPath == null) return false;
        return criticalPath.stream().anyMatch(cp ->
                cp.equalsIgnoreCase(workflow) || workflow.toLowerCase().contains(cp.toLowerCase()));
    }

    /**
     * Returns the critical path as a concise display string.
     */
    public static String toDisplayString(List<String> criticalPath) {
        if (criticalPath == null || criticalPath.isEmpty()) return "None declared";
        return String.join(" → ", criticalPath);
    }
}
