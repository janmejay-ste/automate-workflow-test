package utils.orchestration;

import utils.correlation.dto.CorrelationSnapshotDto;
import utils.correlation.dto.WorkflowHealthDto;
import utils.history.dto.StabilityTrendDto;
import utils.history.dto.TrendSnapshotDto;
import utils.orchestration.dto.SuitePriorityDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes business-critical execution priority per test suite / workflow.
 *
 * Priority tiers:
 *   P1 (CRITICAL): Critical path + currently failing + cascade roots
 *   P1 (HIGH):     High regression risk + recent spike + flaky
 *   P2 (MEDIUM):   Moderate risk, degraded but not failing
 *   P3 (LOW):      Stable, low risk, no recent regression
 *
 * Priority affects execution ordering only. Tests are never removed.
 * Advisory output — human review required before applying.
 */
public final class ExecutionPriorityEngine {

    private ExecutionPriorityEngine() {}

    /**
     * Computes prioritized suite list from current intelligence snapshots.
     *
     * @param trendSnapshot       from TrendSnapshotBuilder
     * @param correlationSnapshot from CorrelationSnapshotBuilder
     * @param criticalPath        from CriticalPathSelector
     * @return suites ordered P1 → P2 → P3
     */
    public static List<SuitePriorityDto> compute(TrendSnapshotDto trendSnapshot,
                                                   CorrelationSnapshotDto correlationSnapshot,
                                                   List<String> criticalPath) {
        List<SuitePriorityDto> result = new ArrayList<>();

        // Build lookup tables
        Map<String, WorkflowHealthDto> healthMap = buildHealthMap(correlationSnapshot);
        Map<String, StabilityTrendDto> stabilityMap = buildStabilityMap(trendSnapshot);

        // Collect all known workflows from both sources
        java.util.Set<String> allWorkflows = new java.util.LinkedHashSet<>();
        if (correlationSnapshot != null && correlationSnapshot.workflowHealth != null) {
            correlationSnapshot.workflowHealth.forEach(w -> allWorkflows.add(w.workflow));
        }
        if (trendSnapshot != null && trendSnapshot.stabilityTrends != null) {
            trendSnapshot.stabilityTrends.forEach(s -> {
                String workflow = utils.correlation.WorkflowDependencyGraph.workflowOf(s.testId);
                allWorkflows.add(workflow);
            });
        }
        criticalPath.forEach(allWorkflows::add);

        int order = 1;
        for (String workflow : allWorkflows) {
            SuitePriorityDto dto = new SuitePriorityDto();
            dto.suite            = workflow;
            dto.isCriticalPath   = CriticalPathSelector.isCritical(workflow, criticalPath);

            WorkflowHealthDto health = healthMap.get(workflow);
            StabilityTrendDto stability = lookupStability(workflow, stabilityMap);

            // Priority logic — deterministic scoring
            String tier;
            String riskLevel;
            String reason;

            if (dto.isCriticalPath && (health == null || "CRITICAL".equals(health.status))) {
                tier      = "P1";
                riskLevel = "CRITICAL";
                reason    = "Critical path workflow with CRITICAL health — execute first, max visibility";
            } else if (dto.isCriticalPath) {
                tier      = "P1";
                riskLevel = "HIGH";
                reason    = "Critical path workflow — always P1 regardless of current health";
            } else if (health != null && "CRITICAL".equals(health.status)) {
                tier      = "P1";
                riskLevel = "CRITICAL";
                reason    = "Workflow health is CRITICAL — requires immediate attention";
            } else if (isHighRisk(health, stability, trendSnapshot)) {
                tier      = "P1";
                riskLevel = "HIGH";
                reason    = buildHighRiskReason(health, stability, trendSnapshot);
            } else if (health != null && "DEGRADED".equals(health.status)) {
                tier      = "P2";
                riskLevel = "MEDIUM";
                reason    = "Workflow is degraded — elevated priority over stable suites";
            } else if (stability != null && stability.isFlaky) {
                tier      = "P2";
                riskLevel = "MEDIUM";
                reason    = "Flaky test(s) detected in workflow — execute before stable suites";
            } else {
                tier      = "P3";
                riskLevel = "LOW";
                reason    = "Stable workflow — execute after higher-risk suites";
            }

            dto.priorityTier      = tier;
            dto.riskLevel         = riskLevel;
            dto.reason            = reason;
            dto.recommendedOrder  = order++;
            result.add(dto);
        }

        // Sort: P1 CRITICAL → P1 HIGH → P2 → P3
        result.sort((a, b) -> {
            int ta = tierScore(a.priorityTier, a.riskLevel);
            int tb = tierScore(b.priorityTier, b.riskLevel);
            return Integer.compare(ta, tb);
        });

        // Re-assign recommendedOrder after sorting
        for (int i = 0; i < result.size(); i++) result.get(i).recommendedOrder = i + 1;

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isHighRisk(WorkflowHealthDto health, StabilityTrendDto stability,
                                       TrendSnapshotDto trendSnapshot) {
        if (health != null && health.failedTests > 0 && health.hasCascadeRisk) return true;
        if (stability != null && stability.stabilityScore < OrchestrationConfig.getRiskStabilityThreshold()) return true;
        if (trendSnapshot != null && trendSnapshot.regressionSpikeActive) return true;
        return false;
    }

    private static String buildHighRiskReason(WorkflowHealthDto health, StabilityTrendDto stability,
                                               TrendSnapshotDto trendSnapshot) {
        if (health != null && health.hasCascadeRisk && health.failedTests > 0) {
            return "Cascade-critical workflow with active failures — high blast radius if skipped";
        }
        if (stability != null && stability.stabilityScore < OrchestrationConfig.getRiskStabilityThreshold()) {
            return "Stability score " + stability.stabilityScore + " below threshold — prioritize for early detection";
        }
        if (trendSnapshot != null && trendSnapshot.regressionSpikeActive) {
            return "Regression spike active in suite — run early to confirm scope";
        }
        return "Multiple risk signals detected";
    }

    private static int tierScore(String tier, String risk) {
        if ("P1".equals(tier) && "CRITICAL".equals(risk)) return 1;
        if ("P1".equals(tier)) return 2;
        if ("P2".equals(tier)) return 3;
        return 4;
    }

    private static Map<String, WorkflowHealthDto> buildHealthMap(CorrelationSnapshotDto cs) {
        if (cs == null || cs.workflowHealth == null) return Map.of();
        return cs.workflowHealth.stream()
                .collect(Collectors.toMap(w -> w.workflow, w -> w, (a, b) -> a));
    }

    private static Map<String, StabilityTrendDto> buildStabilityMap(TrendSnapshotDto ts) {
        if (ts == null || ts.stabilityTrends == null) return Map.of();
        Map<String, StabilityTrendDto> map = new java.util.LinkedHashMap<>();
        for (StabilityTrendDto s : ts.stabilityTrends) {
            String wf = utils.correlation.WorkflowDependencyGraph.workflowOf(s.testId);
            map.putIfAbsent(wf, s);
        }
        return map;
    }

    private static StabilityTrendDto lookupStability(String workflow,
                                                      Map<String, StabilityTrendDto> map) {
        // Direct match
        if (map.containsKey(workflow)) return map.get(workflow);
        // Partial match (workflow name prefix)
        return map.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().startsWith(workflow.toLowerCase()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
