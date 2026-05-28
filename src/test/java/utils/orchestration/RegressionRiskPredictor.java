package utils.orchestration;

import utils.correlation.dto.CorrelationSnapshotDto;
import utils.correlation.dto.WorkflowHealthDto;
import utils.history.dto.PerformanceTrendDto;
import utils.history.dto.StabilityTrendDto;
import utils.history.dto.TrendSnapshotDto;
import utils.history.trends.TrendConfidenceCalculator;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Predicts regression risk concentration per workflow using deterministic rules.
 *
 * Predicts WHERE risk is concentrated, never WHICH specific failure will occur.
 * All predictions are explainable — each risk level has a named driver.
 *
 * Risk scoring (additive, capped at CRITICAL):
 *   +40  stability score below threshold (chronic instability)
 *   +30  workflow health is CRITICAL
 *   +25  active regression spike
 *   +20  cascade root (failure here amplifies)
 *   +20  performance degradation > 50%
 *   +15  workflow health is DEGRADED
 *   +10  flaky test(s) in workflow
 *
 * Score → level:
 *   >= 50 → CRITICAL
 *   >= 30 → HIGH
 *   >= 15 → MEDIUM
 *   <  15 → LOW
 */
public final class RegressionRiskPredictor {

    private RegressionRiskPredictor() {}

    /**
     * Predicts risk levels for all known workflows.
     *
     * @param trendSnapshot       from TrendSnapshotBuilder
     * @param correlationSnapshot from CorrelationSnapshotBuilder
     * @return risk predictions, sorted CRITICAL → HIGH → MEDIUM → LOW
     */
    public static List<WorkflowRiskDto> predict(TrendSnapshotDto trendSnapshot,
                                                 CorrelationSnapshotDto correlationSnapshot) {
        List<WorkflowRiskDto> result = new ArrayList<>();

        Map<String, WorkflowHealthDto> healthMap = buildHealthMap(correlationSnapshot);
        Map<String, StabilityTrendDto> stabMap   = buildStabilityMap(trendSnapshot);
        Map<String, PerformanceTrendDto> perfMap  = buildPerfMap(trendSnapshot);

        boolean spikeActive = trendSnapshot != null && trendSnapshot.regressionSpikeActive;
        String cascadeRoot = correlationSnapshot != null && correlationSnapshot.cascadeDetected
                && correlationSnapshot.cascadeFailure != null
                ? utils.correlation.WorkflowDependencyGraph.workflowOf(
                        correlationSnapshot.cascadeFailure.rootTestId) : null;

        java.util.Set<String> allWorkflows = new java.util.LinkedHashSet<>();
        healthMap.keySet().forEach(allWorkflows::add);
        stabMap.keySet().forEach(allWorkflows::add);
        OrchestrationConfig.getCriticalWorkflows().forEach(allWorkflows::add);

        for (String workflow : allWorkflows) {
            WorkflowHealthDto health = healthMap.get(workflow);
            StabilityTrendDto stab   = stabMap.get(workflow);
            PerformanceTrendDto perf  = perfMap.get(workflow);

            int riskScore        = 0;
            String primaryDriver  = null;
            String secondaryDriver = null;

            // Score each risk signal
            if (stab != null && stab.stabilityScore < OrchestrationConfig.getRiskStabilityThreshold()) {
                riskScore += 40;
                primaryDriver = "Stability score " + stab.stabilityScore
                        + " below threshold (" + OrchestrationConfig.getRiskStabilityThreshold() + ")";
            }
            if (health != null && "CRITICAL".equals(health.status)) {
                riskScore += 30;
                if (primaryDriver == null) primaryDriver = "Workflow health is CRITICAL (" + health.healthScore + "/100)";
                else secondaryDriver = "Workflow health CRITICAL";
            }
            if (spikeActive) {
                riskScore += 25;
                if (primaryDriver == null) primaryDriver = "Active regression spike in current run";
                else if (secondaryDriver == null) secondaryDriver = "Regression spike active";
            }
            if (workflow.equals(cascadeRoot)) {
                riskScore += 20;
                if (primaryDriver == null) primaryDriver = "Cascade root — failure propagates downstream";
                else if (secondaryDriver == null) secondaryDriver = "Cascade root";
            }
            if (perf != null && perf.isDegraded) {
                riskScore += 20;
                if (primaryDriver == null)
                    primaryDriver = "Performance degraded by " + String.format("%.0f%%", perf.degradationPct);
                else if (secondaryDriver == null)
                    secondaryDriver = "Performance degraded " + String.format("%.0f%%", perf.degradationPct);
            }
            if (health != null && "DEGRADED".equals(health.status)) {
                riskScore += 15;
                if (primaryDriver == null) primaryDriver = "Workflow health is DEGRADED";
            }
            if (stab != null && stab.isFlaky) {
                riskScore += 10;
                if (primaryDriver == null) primaryDriver = "Flaky test(s) detected";
                else if (secondaryDriver == null) secondaryDriver = "Flaky";
            }

            if (riskScore == 0 && health == null && stab == null) continue; // no data — skip

            WorkflowRiskDto dto          = new WorkflowRiskDto();
            dto.workflow                 = workflow;
            dto.riskLevel                = classifyRisk(riskScore);
            dto.primaryDriver            = primaryDriver != null ? primaryDriver : "No significant risk signals";
            dto.secondaryDriver          = secondaryDriver;
            dto.stabilityScore           = stab != null ? stab.stabilityScore : -1;
            dto.degradationPct           = perf != null ? perf.degradationPct : 0.0;
            dto.recentRegressionSpike    = spikeActive;
            dto.cascadeRoot              = workflow.equals(cascadeRoot);
            dto.downstreamImpact         = health != null ? health.downstreamImpact : 0;

            // Confidence scales with data richness
            int signals = (health != null ? 1 : 0) + (stab != null ? 1 : 0) + (perf != null ? 1 : 0);
            dto.confidence      = TrendConfidenceCalculator.compute(
                    stab != null ? stab.totalRuns : (signals * 5));
            dto.confidenceLabel = TrendConfidenceCalculator.label(dto.confidence);

            result.add(dto);
        }

        result.sort((a, b) -> riskOrder(a.riskLevel) - riskOrder(b.riskLevel));
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String classifyRisk(int score) {
        if (score >= 50) return "CRITICAL";
        if (score >= 30) return "HIGH";
        if (score >= 15) return "MEDIUM";
        return "LOW";
    }

    private static int riskOrder(String risk) {
        return switch (risk != null ? risk : "LOW") {
            case "CRITICAL" -> 1; case "HIGH" -> 2; case "MEDIUM" -> 3; default -> 4;
        };
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
            // Keep worst stability score for the workflow
            StabilityTrendDto existing = map.get(wf);
            if (existing == null || s.stabilityScore < existing.stabilityScore) map.put(wf, s);
        }
        return map;
    }

    private static Map<String, PerformanceTrendDto> buildPerfMap(TrendSnapshotDto ts) {
        if (ts == null || ts.performanceTrends == null) return Map.of();
        Map<String, PerformanceTrendDto> map = new java.util.LinkedHashMap<>();
        for (PerformanceTrendDto p : ts.performanceTrends) {
            if (p.page == null) continue;
            String wf = utils.correlation.WorkflowDependencyGraph.workflowOf(p.page);
            map.putIfAbsent(wf, p);
        }
        return map;
    }
}
