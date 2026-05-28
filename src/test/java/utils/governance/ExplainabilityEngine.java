package utils.governance;

import utils.governance.dto.ExplanationDto;
import utils.orchestration.dto.ExecutionPlanDto;
import utils.orchestration.dto.OrchestrationSnapshotDto;
import utils.orchestration.dto.RetryStrategyDto;
import utils.orchestration.dto.WorkflowRiskDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts opaque orchestration labels into traceable human reasoning.
 *
 * Design principle: engineers must always be able to answer "WHY did the
 * system recommend X?" without reading source code.
 *
 * Produces:
 *   - Strategy explanation ("RISK_WEIGHTED selected because: auth instability +42%, spike detected")
 *   - Per-workflow risk explanation
 *   - Per-retry suppression explanation
 *   - Minimization rationale
 *
 * All explanations are pre-generated at snapshot build time so the dashboard
 * and reports can display them without re-running analysis.
 */
public final class ExplainabilityEngine {

    private ExplainabilityEngine() {}

    /**
     * Produces all explanations for the current orchestration snapshot.
     *
     * @param snapshot from OrchestrationSnapshotBuilder
     * @return ordered list of explanations (strategy first, then per-workflow)
     */
    public static List<ExplanationDto> explain(OrchestrationSnapshotDto snapshot) {
        List<ExplanationDto> result = new ArrayList<>();
        if (snapshot == null) return result;

        // 1. Strategy explanation
        if (snapshot.executionPlan != null) {
            result.add(explainStrategy(snapshot.executionPlan));
        }

        // 2. Per-workflow risk explanations (top 5 — only where explanation adds value)
        if (snapshot.workflowRisks != null) {
            int shown = 0;
            for (WorkflowRiskDto risk : snapshot.workflowRisks) {
                if (shown++ >= 5) break;
                if ("LOW".equals(risk.riskLevel)) break; // stop at low-risk
                result.add(explainWorkflowRisk(risk));
            }
        }

        // 3. Retry suppression explanations
        if (snapshot.executionPlan != null && snapshot.executionPlan.retryRecommendations != null) {
            for (RetryStrategyDto r : snapshot.executionPlan.retryRecommendations) {
                if (!r.shouldRetry) result.add(explainSuppression(r));
            }
        }

        // 4. Minimization explanation
        if (snapshot.minimizationCandidates != null && !snapshot.minimizationCandidates.isEmpty()) {
            result.add(explainMinimization(snapshot));
        }

        return result;
    }

    // ── Strategy ─────────────────────────────────────────────────────────

    private static ExplanationDto explainStrategy(ExecutionPlanDto plan) {
        ExplanationDto e = new ExplanationDto();
        e.subject  = "Execution Strategy";
        e.decision = plan.strategy;
        e.confidence      = 0.9; // strategy selection is deterministic from clear signals
        e.confidenceLabel = "HIGH";

        List<String> factors      = new ArrayList<>();
        List<String> counterFacts = new ArrayList<>();

        switch (plan.strategy != null ? plan.strategy : "FULL_COVERAGE") {
            case "CRITICAL_PATH_ONLY" -> {
                e.headline = "Critical-path-only execution: cascade active + regression spike detected";
                factors.add("Cascade failure detected — root outage causing downstream failures");
                factors.add("Active regression spike — failure rate above rolling baseline");
                factors.add("CRITICAL-risk workflow(s) present — blast radius assessment needed");
                counterFacts.add("Full coverage not selected — spike + cascade indicates targeted focus is safer");
            }
            case "RISK_WEIGHTED" -> {
                long crit = plan.riskHotspots != null
                        ? plan.riskHotspots.stream().filter(r -> "CRITICAL".equals(r.riskLevel)).count() : 0;
                long high = plan.riskHotspots != null
                        ? plan.riskHotspots.stream().filter(r -> "HIGH".equals(r.riskLevel)).count() : 0;
                e.headline = String.format("Risk-weighted ordering: %d critical + %d high-risk workflows detected", crit, high);
                if (crit > 0) factors.add(crit + " workflow(s) at CRITICAL risk — execute first");
                if (high > 0) factors.add(high + " workflow(s) at HIGH risk — elevated priority");
                if (plan.riskHotspots != null && !plan.riskHotspots.isEmpty()
                        && plan.riskHotspots.get(0).cascadeRoot) {
                    factors.add("Highest-risk workflow is a cascade root — failure propagates downstream");
                }
                counterFacts.add("CRITICAL_PATH_ONLY not selected — no concurrent cascade + spike");
                counterFacts.add("FULL_COVERAGE not selected — risk signals present");
            }
            case "STABILITY_FIRST" -> {
                e.headline = "Stability-first ordering: unstable workflows front-loaded for early detection";
                factors.add("Unstable workflows detected — front-loading prevents cascade propagation");
                if (plan.riskHotspots != null) {
                    plan.riskHotspots.stream()
                            .filter(r -> r.stabilityScore >= 0 && r.stabilityScore < 65)
                            .limit(3)
                            .forEach(r -> factors.add(r.workflow + " stability score: " + r.stabilityScore));
                }
                counterFacts.add("RISK_WEIGHTED not selected — no CRITICAL/HIGH risk workflows");
            }
            default -> {
                e.headline = "Full coverage, equal priority: all workflows stable";
                factors.add("No CRITICAL or HIGH risk workflows detected");
                factors.add("No active regression spike");
                factors.add("No cascade failure active");
                counterFacts.add("All higher-risk strategies considered and conditions not met");
            }
        }

        e.factors       = factors;
        e.counterFactors = counterFacts;
        e.advisory = "advisory".equals(plan.mode)
                ? "This is an advisory recommendation. Review before applying to CI pipeline." : null;
        return e;
    }

    // ── Workflow risk ─────────────────────────────────────────────────────

    private static ExplanationDto explainWorkflowRisk(WorkflowRiskDto risk) {
        ExplanationDto e = new ExplanationDto();
        e.subject         = "Workflow Risk: " + risk.workflow;
        e.decision        = risk.riskLevel;
        e.confidence      = risk.confidence;
        e.confidenceLabel = risk.confidenceLabel;

        List<String> factors = new ArrayList<>();
        if (risk.primaryDriver != null)   factors.add(risk.primaryDriver);
        if (risk.secondaryDriver != null) factors.add(risk.secondaryDriver);
        if (risk.cascadeRoot)             factors.add("Cascade root — failure here propagates to downstream tests");
        if (risk.recentRegressionSpike)   factors.add("Active regression spike affecting this workflow");
        if (risk.downstreamImpact > 0)    factors.add(risk.downstreamImpact + " downstream test(s) at risk if this workflow fails");

        e.factors   = factors;
        e.headline  = risk.riskLevel + " risk: " + (risk.primaryDriver != null ? risk.primaryDriver : "multiple signals");
        e.advisory  = risk.downstreamImpact >= 5
                ? "High downstream impact — treat this workflow as critical path for this run." : null;
        return e;
    }

    // ── Retry suppression ─────────────────────────────────────────────────

    private static ExplanationDto explainSuppression(RetryStrategyDto r) {
        ExplanationDto e = new ExplanationDto();
        e.subject  = "Retry Suppression: " + r.testId;
        e.decision = "SUPPRESS";
        e.confidence      = r.suppressIfCascade ? 0.92 : 0.75;
        e.confidenceLabel = r.suppressIfCascade ? "HIGH" : "MEDIUM";

        List<String> factors = new ArrayList<>();
        if (r.suppressIfCascade) {
            factors.add("Test is a known downstream of a cascade root");
            factors.add("Cascade root must be fixed first — retrying downstream is pure waste");
            factors.add("Suppression confidence HIGH: cascade was confirmed by CorrelationSnapshotBuilder");
        } else {
            factors.add("Failure class: " + r.failureClass);
            factors.add(r.retryReason != null ? r.retryReason : "No retry authorized");
        }
        e.factors  = factors;
        e.headline = r.suppressIfCascade
                ? "Retry suppressed: test is cascade downstream — root cause must be fixed first"
                : "Retry suppressed: " + r.failureClass + " failures cannot be resolved by retrying";
        e.advisory = "To force a retry, use HumanOverrideRegistry.recordOverride() and re-run this test manually.";
        return e;
    }

    // ── Minimization ──────────────────────────────────────────────────────

    private static ExplanationDto explainMinimization(OrchestrationSnapshotDto snapshot) {
        ExplanationDto e = new ExplanationDto();
        e.subject  = "Suite Minimization Advisory";
        e.decision = "MINIMIZE_CANDIDATES_IDENTIFIED";
        e.confidence      = 0.80;
        e.confidenceLabel = "MEDIUM";

        List<String> factors = new ArrayList<>();
        if (snapshot.minimizationCandidates != null) {
            factors.add(snapshot.minimizationCandidates.size() + " workflows passed all 5 stability conditions");
            factors.add("Stability score >= 85, no flaky tests, no spike, not cascade root");
            snapshot.minimizationCandidates.stream().limit(5).forEach(w -> factors.add("Candidate: " + w));
        }
        e.factors  = factors;
        e.headline = "Minimization candidates identified — requires explicit opt-in. Core coverage is preserved.";
        e.advisory = "NEVER apply minimization automatically. Review candidates, confirm stability, opt-in manually.";
        return e;
    }
}
