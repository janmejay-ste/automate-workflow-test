package utils.governance;

import utils.governance.dto.*;
import utils.orchestration.dto.OrchestrationSnapshotDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of computed governance intelligence.
 *
 * Consumers (DashboardBuilder, GovernanceReportGenerator, ReleaseNarrator) read
 * from the returned GovernanceSnapshotDto. No consumer recomputes governance logic.
 *
 * Build sequence:
 *   1. Run safety validation (blocking rules)
 *   2. Apply confidence guardrails (downgrade low-confidence recommendations)
 *   3. Generate explanations (WHY each decision was made)
 *   4. Log all decisions to audit trail
 *   5. Record suppressions to suppression tracker
 *   6. Load override history
 *   7. Aggregate governance health assessment
 */
public final class GovernanceSnapshotBuilder {

    private GovernanceSnapshotBuilder() {}

    /**
     * Builds the full governance snapshot from the orchestration snapshot.
     *
     * @param orchestrationSnapshot from OrchestrationSnapshotBuilder
     * @return GovernanceSnapshotDto — single source of governance truth
     */
    public static GovernanceSnapshotDto build(OrchestrationSnapshotDto orchestrationSnapshot) {
        GovernanceSnapshotDto snapshot = new GovernanceSnapshotDto();
        snapshot.computedAt = Instant.now().toString();

        if (orchestrationSnapshot == null) {
            snapshot.governanceHealth  = "HEALTHY";
            snapshot.governanceComment = "No orchestration data — governance not applicable.";
            snapshot.safetyClean       = true;
            return snapshot;
        }

        // Step 1: Safety validation
        List<SafetyViolationDto> violations = RecommendationSafetyValidator.validate(orchestrationSnapshot);
        snapshot.safetyViolations  = violations;
        snapshot.violationCount    = violations.size();
        snapshot.safetyClean       = violations.isEmpty();

        // Step 2: Confidence guardrails
        ConfidenceGuardrailService.GuardrailResult guardrails =
                ConfidenceGuardrailService.evaluate(orchestrationSnapshot);
        snapshot.guardedToAdvisoryCount      = guardrails.totalGuarded;
        snapshot.lowConfidenceDecisionCount  = guardrails.guardrailEntries.size();

        // Step 3: Explanations
        snapshot.explanations = ExplainabilityEngine.explain(orchestrationSnapshot);
        snapshot.strategyExplanation = snapshot.explanations.stream()
                .filter(e -> "Execution Strategy".equals(e.subject))
                .map(e -> e.headline)
                .findFirst().orElse(null);

        // Step 4: Audit logging (strategy selection + significant decisions)
        List<AuditEntryDto> thisRunDecisions = new ArrayList<>();
        if (orchestrationSnapshot.executionPlan != null) {
            thisRunDecisions.add(DecisionAuditLogger.log(
                    DecisionAuditLogger.DECISION_STRATEGY_SELECTED, "execution-plan",
                    orchestrationSnapshot.executionPlan.strategyReason,
                    List.of(orchestrationSnapshot.executionPlan.strategy != null
                            ? orchestrationSnapshot.executionPlan.strategy : "FULL_COVERAGE"),
                    0.9, DecisionAuditLogger.OUTCOME_ADVISORY));
        }
        // Log safety blocks
        for (SafetyViolationDto v : violations) {
            thisRunDecisions.add(DecisionAuditLogger.log(
                    DecisionAuditLogger.DECISION_SAFETY_BLOCK, v.blockedTarget,
                    v.explanation + " | Recommended: " + v.recommendedAction,
                    List.of(v.violationRule), v.blockedConfidence,
                    DecisionAuditLogger.OUTCOME_BLOCKED_BY_SAFETY));
        }
        // Log guardrail entries from confidence service
        thisRunDecisions.addAll(guardrails.guardrailEntries);
        snapshot.recentDecisions     = thisRunDecisions;
        snapshot.totalDecisionsLogged = DecisionAuditLogger.loadAll().size();

        // Step 5: Suppression audit
        SuppressionAuditTracker.record(orchestrationSnapshot);
        List<SuppressionRecordDto> suppressionHistory = SuppressionAuditTracker.loadAll();
        snapshot.suppressionHistory        = suppressionHistory;
        snapshot.totalSuppressionsThisRun  = orchestrationSnapshot.suppressedRetries;
        snapshot.confirmedFalseSuppressions = (int) suppressionHistory.stream()
                .filter(r -> r.hidRealRegression).count();
        double accuracy = SuppressionAuditTracker.computeAccuracyPct(suppressionHistory);
        snapshot.suppressionAccuracyPct = accuracy >= 0 ? accuracy : 100.0; // 100% when no outcomes known yet

        // Step 6: Override history
        List<HumanOverrideDto> overrides = HumanOverrideRegistry.loadAll();
        snapshot.recentOverrides        = overrides.stream()
                .sorted((a, b) -> (b.timestamp != null ? b.timestamp : "").compareTo(
                        a.timestamp != null ? a.timestamp : ""))
                .limit(20).toList();
        snapshot.totalOverridesLogged   = overrides.size();
        snapshot.effectiveOverrideCount = (int) overrides.stream().filter(o -> o.effectiveOverride).count();
        double overrideRate = HumanOverrideRegistry.computeEffectivenessRate(overrides);
        snapshot.overrideEffectivenessPct = overrideRate >= 0 ? overrideRate : 0.0;

        // Step 7: Governance health assessment
        snapshot.governanceAlerts = deriveAlerts(snapshot, orchestrationSnapshot);
        snapshot.governanceHealth = classifyHealth(snapshot);
        snapshot.governanceComment = buildComment(snapshot);

        return snapshot;
    }

    // ── Health classification ─────────────────────────────────────────────

    private static String classifyHealth(GovernanceSnapshotDto snap) {
        if (snap.confirmedFalseSuppressions > 0)    return "CRITICAL"; // hidden regressions
        if (!snap.safetyClean)                       return "AT_RISK";  // blocked unsafe recommendations
        if (snap.lowConfidenceDecisionCount > 3)     return "MONITORING"; // many low-conf decisions
        if (snap.guardedToAdvisoryCount > 5)         return "MONITORING";
        return "HEALTHY";
    }

    private static String buildComment(GovernanceSnapshotDto snap) {
        return switch (snap.governanceHealth) {
            case "CRITICAL" ->
                snap.confirmedFalseSuppressions + " suppression(s) hidden real regression(s). Immediate review required.";
            case "AT_RISK" ->
                snap.violationCount + " safety rule(s) blocked unsafe recommendation(s). Review violations.";
            case "MONITORING" ->
                snap.lowConfidenceDecisionCount + " low-confidence decision(s) downgraded to advisory-only.";
            default ->
                "All governance checks passed. " + snap.totalDecisionsLogged + " decisions audited.";
        };
    }

    private static List<String> deriveAlerts(GovernanceSnapshotDto snap,
                                              OrchestrationSnapshotDto orch) {
        List<String> alerts = new ArrayList<>();

        if (snap.confirmedFalseSuppressions > 0)
            alerts.add("ALERT: " + snap.confirmedFalseSuppressions +
                    " suppression(s) hid real regression(s) — recalibrate suppression rules");

        if (!snap.safetyClean)
            alerts.add("SAFETY: " + snap.violationCount + " recommendation(s) blocked by governance rules");

        if (snap.guardedToAdvisoryCount > 0)
            alerts.add("CONFIDENCE: " + snap.guardedToAdvisoryCount +
                    " high-impact recommendation(s) downgraded — insufficient signal confidence");

        if (snap.totalOverridesLogged > 0) {
            List<String> recurring = HumanOverrideRegistry.findRecurringOverrideTargets(
                    snap.recentOverrides);
            if (!recurring.isEmpty())
                alerts.add("RECURRENCE: " + recurring.size() +
                        " recommendation(s) overridden repeatedly — accuracy calibration needed: "
                        + String.join(", ", recurring.stream().limit(3).toList()));
        }

        if (orch != null && orch.executionPlan != null
                && "CRITICAL_PATH_ONLY".equals(orch.executionPlan.strategy))
            alerts.add("COVERAGE: CRITICAL_PATH_ONLY strategy active — non-critical tests deferred");

        return alerts;
    }
}
