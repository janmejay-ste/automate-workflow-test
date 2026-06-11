package utils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import utils.release.DecisionFactor;
import utils.release.ReleaseDecision;

/**
 * Interprets metrics into a structured release decision.
 *
 * <p>Phase A4 of the scoring overhaul (v3 plan). The legacy
 * {@link #interpret(int, double, int, double, int) interpret()} overloads return
 * a bare {@link HealthPolicy.ReleaseStatus} for backward compatibility, but new
 * callers should use {@link #decide(DecisionInputs)} which returns a
 * {@link ReleaseDecision} carrying machine-readable blocker/warning factors.</p>
 *
 * <h3>Boundary rule</h3>
 * No human-readable strings emitted here. Codes are enum-like identifiers; the
 * dashboard maps each code to a user-facing sentence.
 */
public class RiskInterpreter {

    // ────────────────────────── Phase A4: structured decision ──────────────────
    /**
     * Bag of inputs the rule table consumes. Kept as a simple record so adding a
     * new signal (e.g. zScore in Phase C2) is a one-line schema change without
     * forking method signatures.
     */
    public record DecisionInputs(
            int score,                  // current run's raw score
            int smoothedScore,          // EMA-smoothed score
            double smokePassRate,       // 0.0–1.0
            int criticalBugs,           // count of known-critical bugs open
            double regressionPassRate,  // 0.0–1.0
            int regressionN,            // sample size for regression suite
            int locatorSamples,         // for future use
            int productHealthScore,     // semantic.layeredScores.productHealth (0–100)
            int criticalClusters,       // semantic.clusters where severity = CRITICAL
            String confidenceLevel,     // "NONE" | "LOW" | "MEDIUM" | "HIGH"
            int sampleN                 // historical run count
    ) {}

    /**
     * Structured rule-table decision. Returns a {@link ReleaseDecision} with
     * machine-readable blocker/warning codes. Each rule contributes either a
     * {@code DecisionFactor} to blockers or warnings; the final status is
     * derived from the union.
     *
     * <p>Status escalation:</p>
     * <ul>
     *   <li>Any blocker → {@code BLOCKED}</li>
     *   <li>Else any warning AND smoothedScore &lt; HEALTHY_MIN → {@code AT_RISK}</li>
     *   <li>Else any warning → {@code WARNING}</li>
     *   <li>Else → {@code READY}</li>
     * </ul>
     */
    public static ReleaseDecision decide(DecisionInputs in) {
        List<DecisionFactor> blockers = new ArrayList<>();
        List<DecisionFactor> warnings = new ArrayList<>();

        // ── BLOCKERS ────────────────────────────────────────────────────────
        if ("NONE".equals(in.confidenceLevel)) {
            blockers.add(new DecisionFactor("INSUFFICIENT_DATA", in.sampleN, 3));
        }
        if (in.smokePassRate < 0.90) {
            blockers.add(new DecisionFactor("SMOKE_FAIL", in.smokePassRate, 0.90));
        }
        if (in.criticalBugs > 0) {
            blockers.add(new DecisionFactor("CRITICAL_BUGS_OPEN", in.criticalBugs, 0));
        }
        if (in.productHealthScore < 20) {
            blockers.add(new DecisionFactor("PRODUCT_HEALTH_CRITICAL", in.productHealthScore, 20));
        }
        if (in.criticalClusters >= 3) {
            blockers.add(new DecisionFactor("CRITICAL_CLUSTER_LIMIT", in.criticalClusters, 3));
        }

        // ── WARNINGS ────────────────────────────────────────────────────────
        if (in.smoothedScore < HealthPolicy.POOR_MIN) {
            warnings.add(new DecisionFactor("SCORE_BELOW_POOR_MIN", in.smoothedScore, HealthPolicy.POOR_MIN));
        } else if (in.smoothedScore < HealthPolicy.HEALTHY_MIN) {
            warnings.add(new DecisionFactor("SCORE_BELOW_HEALTHY_MIN", in.smoothedScore, HealthPolicy.HEALTHY_MIN));
        }

        // Sample-size-aware regression check: 1/1 failure is not the same as 1/100.
        // Only warn when there's enough sample to be meaningful (kills the v2 false-red).
        if (in.regressionPassRate < 1.0 && in.regressionN >= 5) {
            warnings.add(new DecisionFactor("REGRESSION_PASS_RATE_LOW", in.regressionPassRate, 1.0));
        }

        if ("LOW".equals(in.confidenceLevel)) {
            warnings.add(new DecisionFactor("LOW_CONFIDENCE", in.sampleN, 10));
        }

        // ── STATUS RESOLUTION ───────────────────────────────────────────────
        HealthPolicy.ReleaseStatus status;
        if (!blockers.isEmpty()) {
            status = HealthPolicy.ReleaseStatus.BLOCKED;
        } else if (!warnings.isEmpty() && in.smoothedScore < HealthPolicy.HEALTHY_MIN) {
            status = HealthPolicy.ReleaseStatus.AT_RISK;
        } else if (!warnings.isEmpty()) {
            status = HealthPolicy.ReleaseStatus.WARNING;
        } else {
            status = HealthPolicy.ReleaseStatus.READY;
        }

        boolean wouldShip = (status == HealthPolicy.ReleaseStatus.READY)
                         || (status == HealthPolicy.ReleaseStatus.WARNING);

        return new ReleaseDecision(status, blockers, warnings, wouldShip);
    }

    // ────────────────────────── Legacy bridges (kept for compat) ─────────────

    /**
     * Backward-compatible 5-arg overload.  productHealthScore defaults to 100
     * (healthy) so callers that don't have semantic data don't accidentally block.
     */
    public static HealthPolicy.ReleaseStatus interpret(int score, double smokePassRate, int criticalBugs,
            double regressionPassRate, int locatorSamples) {
        return interpret(score, smokePassRate, criticalBugs, regressionPassRate, locatorSamples, 100);
    }

    /**
     * Full 6-arg overload that factors in the Product Health score from the
     * semantic layer ({@code health_snapshot.json → semantic.layeredScores.productHealth}).
     *
     * <p>Priority order:
     * <ol>
     *   <li>Smoke pass rate &lt; 90 % → BLOCKED</li>
     *   <li>Critical product bugs &gt; 0 → BLOCKED</li>
     *   <li>Product Health CRITICAL (&lt;20) AND overall score below floor → BLOCKED</li>
     *   <li>Regression failures → AT_RISK</li>
     *   <li>Overall score floors → AT_RISK / WARNING</li>
     * </ol>
     *
     * <p>The dual-gate on rule 3 ({@code productHealth < 20 AND score < POOR_MIN})
     * is intentional: a product-health crash without overall degradation is a domain
     * signal worth flagging, but not a hard release block on its own.
     */
    public static HealthPolicy.ReleaseStatus interpret(int score, double smokePassRate, int criticalBugs,
            double regressionPassRate, int locatorSamples, int productHealthScore) {
        // Priority 1: Smoke Failures > 10%
        if (smokePassRate < 0.90)
            return HealthPolicy.ReleaseStatus.BLOCKED;

        // Priority 2: Critical Product Bugs
        if (criticalBugs > 0)
            return HealthPolicy.ReleaseStatus.BLOCKED;

        // Priority 3 (NEW): Product Health CRITICAL + overall score degraded
        // productHealth < 20 = CRITICAL band in LayeredHealthScores.statusBand()
        // score < POOR_MIN ensures the signal is systemic, not an isolated domain blip
        if (productHealthScore < 20 && score < HealthPolicy.POOR_MIN)
            return HealthPolicy.ReleaseStatus.BLOCKED;

        // Priority 4: Regression Failures
        if (regressionPassRate < 1.0)
            return HealthPolicy.ReleaseStatus.AT_RISK;

        // Priority 5: Health Score Floors
        if (score < HealthPolicy.POOR_MIN)
            return HealthPolicy.ReleaseStatus.AT_RISK;
        if (score < HealthPolicy.HEALTHY_MIN)
            return HealthPolicy.ReleaseStatus.WARNING;

        return HealthPolicy.ReleaseStatus.READY;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject getStabilityMetrics() {
        JSONObject metrics = new JSONObject();
        try {
            Path historyPath = Paths.get("reports/trend/history.json");
            if (!Files.exists(historyPath))
                return metrics;

            JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(historyPath));
            JSONArray runs = (JSONArray) root.get("runs");

            // Calculate flakiness (tests that failed recently but passed in history)
            Set<String> allFailingTests = new HashSet<>();
            int totalRuns = Math.min(runs.size(), 10);
            for (int i = 0; i < totalRuns; i++) {
                JSONObject run = (JSONObject) runs.get(i);
                JSONObject runMetrics = (JSONObject) run.get("metrics");
                JSONArray failures = (JSONArray) runMetrics.get("failures");
                if (failures != null) {
                    for (Object f : failures) {
                        allFailingTests.add(((JSONObject) f).get("test").toString());
                    }
                }
            }
            Map<String, Object> history = new HashMap<>();
            history.put("totalRuns", (long) runs.size());
            history.put("flakyTestCount", (long) allFailingTests.size());
            metrics.put("history", history);
            metrics.put("windowSize", totalRuns);
        } catch (Exception e) {
            // ignore
        }
        return metrics;
    }
}
