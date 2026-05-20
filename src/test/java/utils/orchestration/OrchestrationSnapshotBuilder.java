package utils.orchestration;

import utils.correlation.dto.CorrelationSnapshotDto;
import utils.history.dto.TrendSnapshotDto;
import utils.orchestration.dto.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of truth for orchestration intelligence.
 *
 * Consumers (DashboardBuilder, PdfReportBuilder, ReleaseNarrator) read from the
 * returned OrchestrationSnapshotDto. No consumer recomputes orchestration logic.
 *
 * Build sequence:
 *   1. Select critical path
 *   2. Predict workflow risks
 *   3. Compute suite priorities
 *   4. Risk-weight the suite order
 *   5. Build execution plan (retry + resource allocations included)
 *   6. Evaluate minimization candidates
 *   7. Aggregate snapshot-level summary fields
 */
public final class OrchestrationSnapshotBuilder {

    private OrchestrationSnapshotBuilder() {}

    /**
     * Builds the full orchestration snapshot from upstream intelligence snapshots.
     *
     * @param trendSnapshot       from TrendSnapshotBuilder
     * @param correlationSnapshot from CorrelationSnapshotBuilder
     * @param testFailures        current-run failure array from analytics (may be null)
     * @return OrchestrationSnapshotDto — immutable after construction
     */
    public static OrchestrationSnapshotDto build(TrendSnapshotDto trendSnapshot,
                                                  CorrelationSnapshotDto correlationSnapshot,
                                                  org.json.simple.JSONArray testFailures) {
        OrchestrationSnapshotDto snapshot = new OrchestrationSnapshotDto();
        snapshot.computedAt = Instant.now().toString();
        snapshot.mode       = "advisory";

        // Step 1: Critical path
        List<String> criticalPath = CriticalPathSelector.select(
                correlationSnapshot != null ? correlationSnapshot.workflowHealth : null);
        snapshot.criticalPathWorkflows = criticalPath;

        // Step 2: Workflow risks
        List<WorkflowRiskDto> risks = RegressionRiskPredictor.predict(trendSnapshot, correlationSnapshot);
        snapshot.workflowRisks = risks;
        snapshot.criticalRiskCount = (int) risks.stream()
                .filter(r -> "CRITICAL".equals(r.riskLevel)).count();
        snapshot.highRiskCount = (int) risks.stream()
                .filter(r -> "HIGH".equals(r.riskLevel)).count();
        snapshot.highestRiskWorkflow = risks.isEmpty() ? null : risks.get(0).workflow;

        // Steps 3–5: Execution plan (internally runs priority, retry, resources)
        ExecutionPlanDto plan = AdaptiveExecutionPlanner.plan(trendSnapshot, correlationSnapshot, testFailures);
        snapshot.executionPlan = plan;

        // Step 6: Minimization
        SuiteMinimizationEngine.MinimizationResult minimization =
                SuiteMinimizationEngine.evaluate(trendSnapshot, risks);
        snapshot.minimizationCandidates = minimization.minimizationCandidates;
        snapshot.minimizationSummary    = minimization.summary;

        // Step 7: Retry summary (from plan)
        if (plan.retryRecommendations != null) {
            snapshot.totalRetryRecommendations = plan.retryRecommendations.size();
            snapshot.suppressedRetries  = plan.suppressedRetryCount;
            snapshot.authorizedRetries  = IntelligentRetryManager.countAuthorized(plan.retryRecommendations);
        }

        // Step 8: Forward-looking signals for AI narrative
        snapshot.dominantRisk = deriveDominantRisk(risks, correlationSnapshot, trendSnapshot);
        snapshot.executionOptimizationAvailable =
                !minimization.minimizationCandidates.isEmpty()
                || !minimization.expansionCandidates.isEmpty()
                || snapshot.suppressedRetries > 0
                || snapshot.criticalRiskCount > 0;

        return snapshot;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Derives the dominant risk category for AI narrative enrichment.
     * Picks the single most impactful risk signal across all data sources.
     */
    private static String deriveDominantRisk(List<WorkflowRiskDto> risks,
                                              CorrelationSnapshotDto correlation,
                                              TrendSnapshotDto trend) {
        // Auth cascade → AUTHENTICATION (high blast radius)
        if (correlation != null && correlation.cascadeDetected
                && correlation.cascadeFailure != null) {
            String root = correlation.cascadeFailure.rootTestId;
            if (root != null && (root.toLowerCase().contains("auth")
                    || root.toLowerCase().contains("login"))) {
                return "AUTHENTICATION";
            }
        }

        // Performance degradation in critical path workflows
        boolean criticalPerfDegraded = risks.stream()
                .filter(r -> "CRITICAL".equals(r.riskLevel) || "HIGH".equals(r.riskLevel))
                .anyMatch(r -> r.degradationPct > 50.0);
        if (criticalPerfDegraded) return "PERFORMANCE";

        // Infrastructure: regression spike active (transient env issue)
        if (trend != null && trend.regressionSpikeActive) return "INFRASTRUCTURE";

        return "NONE";
    }
}
