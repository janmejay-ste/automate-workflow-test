package utils.orchestration;

import utils.orchestration.dto.ResourceAllocationDto;
import utils.orchestration.dto.SuitePriorityDto;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recommends thread and executor pool allocation per suite.
 *
 * Allocation rules:
 *   ISOLATED pool — for high-risk or cascade-root workflows; failure isolation
 *                   prevents noise contaminating stable suite results.
 *   SHARED pool   — for P2/P3 stable suites; maximize throughput via parallelism.
 *
 * Thread recommendations (advisory, based on risk and stability):
 *   CRITICAL risk → 1 thread (sequential, cleanest failure signal)
 *   HIGH risk     → 2 threads (limited parallelism)
 *   MEDIUM risk   → configured parallel threads
 *   LOW risk      → configured parallel threads (full throughput)
 *
 * Memory risk flagging:
 *   Suites with high downstream impact (≥5) or cascade roots are flagged
 *   as high-memory-risk — integrations typically load more page state.
 *
 * All output is advisory. CI infrastructure decides actual thread allocation.
 */
public final class ResourceAllocationOptimizer {

    private ResourceAllocationOptimizer() {}

    /**
     * Produces resource allocation recommendations for all suites.
     *
     * @param orderedSuites from RiskWeightedSuiteBuilder
     * @param workflowRisks from RegressionRiskPredictor
     * @return one ResourceAllocationDto per suite, same order as input
     */
    public static List<ResourceAllocationDto> allocate(List<SuitePriorityDto> orderedSuites,
                                                        List<WorkflowRiskDto> workflowRisks) {
        if (orderedSuites == null || orderedSuites.isEmpty()) return List.of();

        Map<String, WorkflowRiskDto> riskMap = buildRiskMap(workflowRisks);
        List<ResourceAllocationDto> result = new ArrayList<>();

        int parallelThreads = OrchestrationConfig.getParallelThreads();

        for (SuitePriorityDto suite : orderedSuites) {
            WorkflowRiskDto risk = riskMap.get(suite.suite);

            ResourceAllocationDto dto = new ResourceAllocationDto();
            dto.suite = suite.suite;

            String riskLevel = risk != null ? risk.riskLevel : suite.riskLevel;

            // Pool assignment
            boolean needsIsolation = isIsolationRequired(suite, risk);
            dto.poolType = needsIsolation ? "ISOLATED" : "SHARED";

            // Thread recommendation
            dto.recommendedThreads = recommendThreads(riskLevel, needsIsolation, parallelThreads);

            // Memory risk
            dto.highMemoryRisk = isHighMemoryRisk(risk, suite);

            // Reason
            dto.reason = buildReason(suite, risk, dto.poolType, dto.recommendedThreads);

            result.add(dto);
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isIsolationRequired(SuitePriorityDto suite, WorkflowRiskDto risk) {
        if ("P1".equals(suite.priorityTier) && "CRITICAL".equals(suite.riskLevel)) return true;
        if (risk == null) return false;
        if (risk.cascadeRoot)                           return true;
        if ("CRITICAL".equals(risk.riskLevel))          return true;
        if ("HIGH".equals(risk.riskLevel) && risk.recentRegressionSpike) return true;
        return false;
    }

    private static int recommendThreads(String riskLevel, boolean isolated, int parallelThreads) {
        if (isolated) {
            // Isolated suites: sequential or minimal parallelism for clean failure signal
            return switch (riskLevel != null ? riskLevel : "LOW") {
                case "CRITICAL" -> 1;
                case "HIGH"     -> 2;
                default         -> Math.min(2, parallelThreads);
            };
        }
        // Shared pool: maximize throughput
        return switch (riskLevel != null ? riskLevel : "LOW") {
            case "CRITICAL" -> 1;
            case "HIGH"     -> Math.max(1, parallelThreads / 2);
            case "MEDIUM"   -> parallelThreads;
            default         -> parallelThreads;
        };
    }

    private static boolean isHighMemoryRisk(WorkflowRiskDto risk, SuitePriorityDto suite) {
        if (risk == null) return false;
        return risk.cascadeRoot || risk.downstreamImpact >= 5 || "CRITICAL".equals(risk.riskLevel);
    }

    private static String buildReason(SuitePriorityDto suite, WorkflowRiskDto risk,
                                       String poolType, int threads) {
        if ("ISOLATED".equals(poolType)) {
            if (risk != null && risk.cascadeRoot)
                return "Cascade root — isolated executor prevents failure noise contamination";
            if ("CRITICAL".equals(suite.riskLevel))
                return "CRITICAL risk suite — isolated pool, " + threads + " thread(s) for clean failure signal";
            return "High-risk with spike — isolated to prevent parallel interference";
        }
        if (threads > 1)
            return "Stable suite — shared pool, " + threads + " parallel threads for throughput";
        return "Stable suite — shared pool, sequential execution";
    }

    private static Map<String, WorkflowRiskDto> buildRiskMap(List<WorkflowRiskDto> risks) {
        if (risks == null) return Map.of();
        return risks.stream()
                .collect(Collectors.toMap(r -> r.workflow, r -> r, (a, b) -> a));
    }
}
