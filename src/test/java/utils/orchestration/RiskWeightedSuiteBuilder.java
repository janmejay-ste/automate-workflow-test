package utils.orchestration;

import utils.orchestration.dto.SuitePriorityDto;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orders test suites by composite risk for execution scheduling.
 *
 * Risk ordering (highest to lowest):
 *   1. P1 CRITICAL — critical path + CRITICAL health
 *   2. P1 HIGH     — critical path or high risk
 *   3. P1 MEDIUM   — regression spike active or cascade root
 *   4. P2 MEDIUM   — degraded or flaky
 *   5. P3 LOW      — stable
 *
 * Within each tier, suites with higher downstream impact are ordered first.
 * INVARIANT: This affects execution ordering ONLY.
 *            Core coverage is never silently skipped or removed.
 *            No suite is omitted from the output list.
 */
public final class RiskWeightedSuiteBuilder {

    private RiskWeightedSuiteBuilder() {}

    /**
     * Merges priority tiers and risk predictions into a single ordered execution queue.
     *
     * @param suitePriorities from ExecutionPriorityEngine
     * @param workflowRisks   from RegressionRiskPredictor
     * @return ordered list — index 0 executes first
     */
    public static List<SuitePriorityDto> build(List<SuitePriorityDto> suitePriorities,
                                                List<WorkflowRiskDto> workflowRisks) {
        if (suitePriorities == null || suitePriorities.isEmpty()) return List.of();

        Map<String, WorkflowRiskDto> riskMap = buildRiskMap(workflowRisks);

        List<SuitePriorityDto> ordered = new ArrayList<>(suitePriorities);

        ordered.sort((a, b) -> {
            int scoreA = compositeScore(a, riskMap.get(a.suite));
            int scoreB = compositeScore(b, riskMap.get(b.suite));
            if (scoreA != scoreB) return Integer.compare(scoreA, scoreB); // lower score = higher priority

            // Secondary: downstream impact (higher = execute first)
            int downA = downstreamImpact(riskMap.get(a.suite));
            int downB = downstreamImpact(riskMap.get(b.suite));
            return Integer.compare(downB, downA); // desc
        });

        // Re-assign recommendedOrder after sort
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).recommendedOrder = i + 1;

        return ordered;
    }

    /**
     * Returns a human-readable summary of how many suites fall in each tier.
     */
    public static String summarize(List<SuitePriorityDto> ordered) {
        if (ordered == null || ordered.isEmpty()) return "No suites to schedule.";

        long p1 = ordered.stream().filter(s -> "P1".equals(s.priorityTier)).count();
        long p2 = ordered.stream().filter(s -> "P2".equals(s.priorityTier)).count();
        long p3 = ordered.stream().filter(s -> "P3".equals(s.priorityTier)).count();

        return String.format("Execution order: %d P1 (execute first), %d P2 (elevated), %d P3 (stable)",
                p1, p2, p3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Composite sort score — lower is higher priority.
     * Combines tier-level ordering with risk signal overlay.
     */
    private static int compositeScore(SuitePriorityDto suite, WorkflowRiskDto risk) {
        int base = tierBaseScore(suite.priorityTier, suite.riskLevel);

        if (risk == null) return base;

        // Overlay: cascade roots and regression spikes push earlier within tier
        if (risk.cascadeRoot)           base -= 2;
        if (risk.recentRegressionSpike) base -= 1;
        if ("CRITICAL".equals(risk.riskLevel)) base -= 1;

        return base;
    }

    private static int tierBaseScore(String tier, String riskLevel) {
        if ("P1".equals(tier) && "CRITICAL".equals(riskLevel)) return 10;
        if ("P1".equals(tier) && "HIGH".equals(riskLevel))     return 20;
        if ("P1".equals(tier))                                  return 25;
        if ("P2".equals(tier))                                  return 30;
        return 40; // P3
    }

    private static int downstreamImpact(WorkflowRiskDto risk) {
        return risk != null ? risk.downstreamImpact : 0;
    }

    private static Map<String, WorkflowRiskDto> buildRiskMap(List<WorkflowRiskDto> risks) {
        if (risks == null) return Map.of();
        return risks.stream()
                .collect(Collectors.toMap(r -> r.workflow, r -> r, (a, b) -> a));
    }
}
