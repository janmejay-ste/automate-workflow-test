package utils.orchestration;

import utils.history.dto.StabilityTrendDto;
import utils.history.dto.TrendSnapshotDto;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Identifies suites eligible for reduced test sampling in time-constrained runs.
 *
 * STRICT ADVISORY — this engine recommends, never decides.
 * Core coverage is NEVER auto-pruned. Humans must explicitly opt into minimization.
 *
 * Minimization eligibility (all conditions must hold):
 *   1. Risk level is LOW (no CRITICAL/HIGH/MEDIUM risk signals)
 *   2. Stability score >= configured STABLE_THRESHOLD (default 85)
 *   3. No flaky tests in workflow
 *   4. No regression spike touching this workflow
 *   5. No cascade role (not a root, not high downstream impact)
 *
 * Expanded coverage recommendation:
 *   Applied to CRITICAL or HIGH risk workflows — recommends running MORE tests,
 *   deeper coverage, not just the regression suite.
 *
 * Output format:
 *   minimizationCandidates — workflow names eligible for reduced sampling
 *   expansionCandidates    — workflow names that need deeper coverage
 *   summary                — human-readable recommendation
 */
public final class SuiteMinimizationEngine {

    private static final int STABLE_THRESHOLD = 85;
    private static final int HIGH_DOWNSTREAM_CUTOFF = 3;

    private SuiteMinimizationEngine() {}

    public static class MinimizationResult {
        public List<String> minimizationCandidates = new ArrayList<>();
        public List<String> expansionCandidates    = new ArrayList<>();
        public String       summary;
    }

    /**
     * Evaluates all workflows and produces minimization/expansion recommendations.
     *
     * @param trendSnapshot from TrendSnapshotBuilder (for stability + spike data)
     * @param workflowRisks from RegressionRiskPredictor (for risk level + cascade info)
     * @return advisory recommendations, never auto-applied
     */
    public static MinimizationResult evaluate(TrendSnapshotDto trendSnapshot,
                                               List<WorkflowRiskDto> workflowRisks) {
        MinimizationResult out = new MinimizationResult();

        if (workflowRisks == null || workflowRisks.isEmpty()) {
            out.summary = "No workflow risk data available — no minimization recommendations.";
            return out;
        }

        Map<String, StabilityTrendDto> stabMap = buildStabilityMap(trendSnapshot);
        boolean spikeActive = trendSnapshot != null && trendSnapshot.regressionSpikeActive;

        for (WorkflowRiskDto risk : workflowRisks) {
            if (risk.workflow == null) continue;

            StabilityTrendDto stab = stabMap.get(risk.workflow);

            // Expansion: high-risk workflows need MORE coverage
            if ("CRITICAL".equals(risk.riskLevel) || "HIGH".equals(risk.riskLevel)) {
                out.expansionCandidates.add(risk.workflow);
                continue;
            }

            // Minimization: only eligible when fully stable
            if (isMinimizationEligible(risk, stab, spikeActive)) {
                out.minimizationCandidates.add(risk.workflow);
            }
        }

        out.summary = buildSummary(out.minimizationCandidates, out.expansionCandidates,
                workflowRisks.size());
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isMinimizationEligible(WorkflowRiskDto risk, StabilityTrendDto stab,
                                                   boolean spikeActive) {
        if (!"LOW".equals(risk.riskLevel))        return false;
        if (risk.recentRegressionSpike)            return false;
        if (spikeActive)                           return false; // global spike — don't minimize anything
        if (risk.cascadeRoot)                      return false;
        if (risk.downstreamImpact >= HIGH_DOWNSTREAM_CUTOFF) return false;
        if (stab == null)                          return false; // no stability data — don't minimize
        if (stab.stabilityScore < STABLE_THRESHOLD) return false;
        if (stab.isFlaky)                          return false;
        return true;
    }

    private static String buildSummary(List<String> minimize, List<String> expand, int total) {
        if (minimize.isEmpty() && expand.isEmpty()) {
            return "All " + total + " workflows at MEDIUM risk or insufficient stability data. "
                   + "No minimization or expansion recommendations.";
        }

        StringBuilder sb = new StringBuilder();
        if (!expand.isEmpty()) {
            sb.append(expand.size()).append(" workflow(s) need EXPANDED coverage: ")
              .append(String.join(", ", expand)).append(". ");
        }
        if (!minimize.isEmpty()) {
            sb.append(minimize.size()).append("/").append(total)
              .append(" stable workflow(s) are minimization candidates: ")
              .append(String.join(", ", minimize))
              .append(". Requires explicit human opt-in — core coverage preserved.");
        }
        return sb.toString().trim();
    }

    private static Map<String, StabilityTrendDto> buildStabilityMap(TrendSnapshotDto ts) {
        if (ts == null || ts.stabilityTrends == null) return Map.of();
        Map<String, StabilityTrendDto> map = new java.util.LinkedHashMap<>();
        for (StabilityTrendDto s : ts.stabilityTrends) {
            String wf = utils.correlation.WorkflowDependencyGraph.workflowOf(s.testId);
            StabilityTrendDto existing = map.get(wf);
            if (existing == null || s.stabilityScore < existing.stabilityScore) map.put(wf, s);
        }
        return map;
    }
}
