package utils.orchestration;

import utils.correlation.dto.CorrelationSnapshotDto;
import utils.history.dto.TrendSnapshotDto;
import utils.orchestration.dto.*;

import java.time.Instant;
import java.util.List;

/**
 * Core orchestration coordinator. Combines all intelligence signals into a
 * single executable plan.
 *
 * Strategy selection (deterministic, in priority order):
 *   CRITICAL_PATH_ONLY — cascade detected AND regression spike active AND CI budget tight
 *   RISK_WEIGHTED      — high-risk workflows present (CRITICAL or HIGH risk count ≥ 1)
 *   STABILITY_FIRST    — no spike, but unstable workflows need front-loading
 *   FULL_COVERAGE      — all stable, no risk signals — run everything equally
 *
 * All output is advisory. No test is removed from coverage.
 * Advisory mode is enforced structurally: consumers must read `mode` field.
 */
public final class AdaptiveExecutionPlanner {

    private AdaptiveExecutionPlanner() {}

    /**
     * Builds a complete execution plan from current intelligence snapshots.
     *
     * @param trendSnapshot       from TrendSnapshotBuilder
     * @param correlationSnapshot from CorrelationSnapshotBuilder
     * @param testFailures        JSONArray of current run failures (passed through to retry manager)
     * @return ExecutionPlanDto with strategy, ordered suites, retry recommendations, resource allocations
     */
    public static ExecutionPlanDto plan(TrendSnapshotDto trendSnapshot,
                                        CorrelationSnapshotDto correlationSnapshot,
                                        org.json.simple.JSONArray testFailures) {
        ExecutionPlanDto plan = new ExecutionPlanDto();
        plan.mode        = "advisory";
        plan.computedAt  = Instant.now().toString();

        // Step 1: Build component inputs
        List<String>             criticalPath      = CriticalPathSelector.select(
                correlationSnapshot != null ? correlationSnapshot.workflowHealth : null);
        List<WorkflowRiskDto>    riskHotspots      = RegressionRiskPredictor.predict(
                trendSnapshot, correlationSnapshot);
        List<SuitePriorityDto>   rawPriorities     = ExecutionPriorityEngine.compute(
                trendSnapshot, correlationSnapshot, criticalPath);
        List<SuitePriorityDto>   orderedSuites     = RiskWeightedSuiteBuilder.build(
                rawPriorities, riskHotspots);
        List<RetryStrategyDto>   retryRecs         = IntelligentRetryManager.recommend(
                testFailures, correlationSnapshot);
        List<ResourceAllocationDto> resources      = ResourceAllocationOptimizer.allocate(
                orderedSuites, riskHotspots);

        // Step 2: Populate plan
        plan.suitePriorities      = orderedSuites;
        plan.riskHotspots         = riskHotspots;
        plan.retryRecommendations = retryRecs;
        plan.resourceAllocations  = resources;

        // Step 3: Aggregated intelligence
        plan.criticalSuiteCount     = countByTierAndRisk(orderedSuites, "P1", "CRITICAL");
        plan.highRiskWorkflowCount  = countByRiskLevel(riskHotspots, "HIGH") +
                                      countByRiskLevel(riskHotspots, "CRITICAL");
        plan.suppressedRetryCount   = IntelligentRetryManager.countSuppressed(retryRecs);
        plan.estimatedTimeSavingPct = estimateTimeSaving(plan.suppressedRetryCount, retryRecs.size(),
                                                         plan.highRiskWorkflowCount);

        // Step 4: Strategy selection
        plan.strategy       = selectStrategy(trendSnapshot, correlationSnapshot, riskHotspots);
        plan.strategyReason = buildStrategyReason(plan.strategy, trendSnapshot, correlationSnapshot,
                                                   riskHotspots);

        return plan;
    }

    // ── Strategy selection ────────────────────────────────────────────────

    private static String selectStrategy(TrendSnapshotDto trend,
                                          CorrelationSnapshotDto correlation,
                                          List<WorkflowRiskDto> risks) {
        boolean spikeActive    = trend != null && trend.regressionSpikeActive;
        boolean cascadeActive  = correlation != null && correlation.cascadeDetected;
        boolean hasCritical    = risks.stream().anyMatch(r -> "CRITICAL".equals(r.riskLevel));
        boolean hasHigh        = risks.stream().anyMatch(r -> "HIGH".equals(r.riskLevel));
        boolean hasUnstable    = risks.stream().anyMatch(r ->
                r.stabilityScore >= 0 && r.stabilityScore < OrchestrationConfig.getRiskStabilityThreshold());

        if (cascadeActive && spikeActive && hasCritical) return "CRITICAL_PATH_ONLY";
        if (hasCritical || hasHigh)                      return "RISK_WEIGHTED";
        if (hasUnstable || spikeActive)                  return "STABILITY_FIRST";
        return "FULL_COVERAGE";
    }

    private static String buildStrategyReason(String strategy, TrendSnapshotDto trend,
                                               CorrelationSnapshotDto correlation,
                                               List<WorkflowRiskDto> risks) {
        return switch (strategy) {
            case "CRITICAL_PATH_ONLY" ->
                    "Cascade active + regression spike detected — critical-path focus minimizes blast radius";
            case "RISK_WEIGHTED" -> {
                long critCount = risks.stream().filter(r -> "CRITICAL".equals(r.riskLevel)).count();
                long highCount = risks.stream().filter(r -> "HIGH".equals(r.riskLevel)).count();
                yield String.format("%d CRITICAL + %d HIGH risk workflows detected — run high-risk suites first",
                        critCount, highCount);
            }
            case "STABILITY_FIRST" -> {
                boolean spike = trend != null && trend.regressionSpikeActive;
                yield spike
                        ? "Regression spike active — front-load unstable workflows for early detection"
                        : "Unstable workflows detected — stability-first ordering reduces false alarm propagation";
            }
            default -> "All workflows stable — full coverage, equal priority";
        };
    }

    // ── Count helpers ─────────────────────────────────────────────────────

    private static int countByTierAndRisk(List<SuitePriorityDto> suites, String tier, String risk) {
        if (suites == null) return 0;
        return (int) suites.stream()
                .filter(s -> tier.equals(s.priorityTier) && risk.equals(s.riskLevel))
                .count();
    }

    private static int countByRiskLevel(List<WorkflowRiskDto> risks, String level) {
        if (risks == null) return 0;
        return (int) risks.stream().filter(r -> level.equals(r.riskLevel)).count();
    }

    /**
     * Rough estimate: each suppressed retry saves ~avg_retry_cost; each high-risk
     * workflow executing earlier catches failures sooner (reduces wasted parallel work).
     * Not a precise CI benchmark — advisory signal only.
     */
    private static int estimateTimeSaving(int suppressed, int total, int highRiskCount) {
        if (total == 0) return 0;
        int retryPct = total > 0 ? (suppressed * 100 / total) / 3 : 0; // retries ~33% of test time
        int priorityPct = Math.min(highRiskCount * 2, 15);              // priority saves up to 15%
        return Math.min(retryPct + priorityPct, 40);                    // cap at 40%
    }
}
