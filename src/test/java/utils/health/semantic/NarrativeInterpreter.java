package utils.health.semantic;

import java.util.List;

/**
 * Generates the "Why This Matters" business-interpretation paragraph for a
 * given semantic finding.  Without this layer, PDFs become telemetry dumps
 * that nobody reads — the same data with one sentence of plain-English
 * impact becomes a release-decision tool.
 *
 * Pure function — given the same snapshot, always emits the same text.
 * No state, no side effects, no LLM call.  When the rules become rich enough
 * to warrant data-driven generation, this is the natural place to plug it in.
 */
public final class NarrativeInterpreter {

    private NarrativeInterpreter() {}

    // ── Layered-score interpretation ─────────────────────────────────────────

    public static String forProductHealth(int score) {
        if (score >= 90) return "Product runtime stability is strong this run. No release-blocking app defects were detected.";
        if (score >= 75) return "Product runtime is healthy with minor defects worth tracking. Release can proceed; defects should be queued.";
        if (score >= 50) return "Product runtime shows real defects affecting user-facing flows. Recommend triage before release sign-off.";
        if (score >= 30) return "Product runtime is degraded — multiple defects detected across critical flows. Release should be paused for investigation.";
        return "Product runtime is broken. Core flows are not functioning. Release must be blocked until the underlying defects are resolved.";
    }

    public static String forFrameworkHealth(int score) {
        if (score >= 90) return "Automation harness is stable. Test outcomes can be trusted at face value.";
        if (score >= 75) return "Minor harness flakiness present. Outcomes are largely trustworthy; review locator/retry diagnostics.";
        if (score >= 50) return "Automation harness shows instability. Test outcomes may not reflect true product state — review the framework before drawing conclusions about the app.";
        if (score >= 30) return "Automation harness is failing repeatedly. Test outcomes cannot be trusted to represent product reality. Pause the suite and fix the framework first.";
        return "Automation framework is broken. The dashboard cannot speak to product quality until the harness is repaired.";
    }

    public static String forTelemetryConfidence(int score) {
        if (score >= 90) return "Telemetry pipeline produced trustworthy measurements throughout this run.";
        if (score >= 75) return "Telemetry pipeline is largely healthy. A few measurements came back UNKNOWN — instrument those hooks when convenient.";
        if (score >= 50) return "A meaningful share of metrics came back UNKNOWN. Confidence in performance numbers should be treated as partial.";
        if (score >= 30) return "Telemetry pipeline is degraded. Most timing/performance signals cannot be trusted this run.";
        return "Telemetry pipeline is broken. Performance and reliability metrics in this report should be treated as informational only.";
    }

    // ── Cluster interpretation ──────────────────────────────────────────────

    /**
     * Why this specific cluster matters in plain English.
     * Selected from severity + domain (no per-message LLM reasoning) so output
     * is deterministic and reproducible.
     */
    public static String forCluster(ErrorCluster cluster) {
        if (cluster == null) return "";
        ClusterSeverity sev = cluster.severity;
        FailureDomain   dom = cluster.domain;
        int             cnt = cluster.count;
        String plural = (cnt == 1) ? "occurrence" : "occurrences";

        // Observability noise — always low-impact, regardless of count
        if (dom == FailureDomain.OBSERVABILITY) {
            return "Third-party or non-actionable noise (" + cnt + " " + plural
                 + "). Has no effect on product health and is intentionally not penalised. "
                 + "Consider adding to the suppression filter to keep dashboards quieter.";
        }

        // Telemetry — instrumentation gap, not a runtime defect
        if (dom == FailureDomain.TELEMETRY) {
            return "Telemetry instrumentation did not produce a measurement (" + cnt + " " + plural
                 + "). Missing data is recorded as UNKNOWN — not as a slow page or failure. "
                 + "Telemetry confidence is reduced; performance numbers should be read with caution.";
        }

        // Infrastructure — recoverable, environment-dependent
        if (dom == FailureDomain.INFRASTRUCTURE) {
            return "Network, DNS, or upstream gateway failure (" + cnt + " " + plural
                 + "). Often environment-dependent and may resolve on retry, but if it recurs "
                 + "in clean environments it likely indicates real infrastructure instability worth escalating to DevOps.";
        }

        // Framework — automation harness problem
        if (dom == FailureDomain.FRAMEWORK) {
            return "Automation harness instability (" + cnt + " " + plural
                 + "). This does not indicate the product is broken — it indicates the test harness "
                 + "needs a fix. Until resolved, test failures in adjacent flows may be false positives.";
        }

        // Product — the real defects
        switch (sev) {
            case CRITICAL:
                return "Critical product defect affecting a release-blocking flow (" + cnt + " " + plural
                     + "). The user journey this represents is currently broken. "
                     + "Release must not proceed until the underlying issue is fixed.";
            case HIGH:
                return "Frontend runtime defect in the product (" + cnt + " " + plural
                     + "). User-facing flows may degrade, hang, or throw silent errors. "
                     + "Fix before the next release; root-cause likely a null DOM reference or type-coercion bug.";
            case MEDIUM:
                return "Product defect worth investigating (" + cnt + " " + plural
                     + "). Impact is bounded but real — schedule for the next sprint.";
            case LOW:
                return "Minor product warning (" + cnt + " " + plural
                     + "). Not release-blocking; track in the backlog.";
            default:
                return "Informational signal (" + cnt + " " + plural + ").";
        }
    }

    // ── Reliability interpretation ──────────────────────────────────────────

    public static String forReliability(PhaseReliability pr) {
        if (pr == null) return "";
        if (pr.percentage < 0) {
            return "No data captured for this phase this run — cannot draw a conclusion. "
                 + "Confirm the phase is being instrumented via HealthTracker.recordSuccess().";
        }
        if (pr.percentage >= 95) return "Phase is reliable this run.";
        if (pr.percentage >= 85) return "Phase is mostly reliable. Track over multiple runs to confirm.";
        if (pr.percentage >= 70) return "Phase is unstable. " + (pr.successes + pr.failures)
                + " attempts produced " + pr.failures + " failure" + (pr.failures == 1 ? "" : "s")
                + " — investigate the failing iterations before drawing release conclusions.";
        return "Phase is broken this run. The majority of attempts failed; this is the most actionable signal in the report.";
    }

    // ── Top-line release recommendation ─────────────────────────────────────

    public static String releaseRecommendation(LayeredHealthScores scores) {
        int product   = scores.productHealth;
        int framework = scores.frameworkHealth;
        int telemetry = scores.telemetryConfidence;
        int business  = scores.businessOutcome;   // may be NO_DATA

        // Framework must be trustworthy first — anything else is meaningless if the
        // harness can't speak to product reality.
        if (framework < 50) {
            return "HOLD — automation framework is unreliable; the dashboard cannot speak to "
                 + "product quality until the harness is repaired.";
        }

        // Business outcomes (when measured) are the strongest release signal because they
        // answer "did users get what they came for?" — the question Product Health can't
        // answer on its own. A green Product Health with broken business outcomes is the
        // exact failure mode this layer exists to catch.
        if (!scores.businessUnscored()) {
            if (business < 30) {
                return "BLOCK — user-intent transactions failed at high rates. The product rendered "
                     + "but users could not actually complete their workflows.";
            }
            if (business < 50) {
                return "BLOCK — a meaningful share of user-intent transactions failed. Investigate "
                     + "the failed business outcomes before releasing.";
            }
            if (business < 75) {
                return "PROCEED WITH CAUTION — some user-intent transactions are degraded. "
                     + "Confirm the partial outcomes are acceptable before release.";
            }
        }

        if (product < 30) {
            return "BLOCK — product runtime is broken. Core flows are not functioning.";
        }
        if (product < 50) {
            return "BLOCK — multiple product defects detected. Release should be paused for investigation.";
        }
        if (product < 75) {
            return "PROCEED WITH CAUTION — real product defects exist but are not release-blocking. "
                 + "Confirm the defects are tracked, then release if business priorities allow.";
        }
        if (telemetry < 50) {
            return "PROCEED — product and framework are healthy, but telemetry confidence is low. "
                 + "Performance/reliability numbers should be read with caution.";
        }
        if (scores.businessUnscored()) {
            return "PROCEED — runtime signals indicate a healthy release.  Note: no business-outcome "
                 + "transactions were recorded this run, so user-intent success rate is unknown.";
        }
        return "PROCEED — all signals indicate a healthy release.";
    }

    /** Narrative for the business outcome score itself (cover-page rationale). */
    public static String forBusinessOutcome(LayeredHealthScores scores) {
        if (scores.businessUnscored()) {
            return "No business-outcome transactions were recorded this run. Wire the existing "
                 + "tests to BusinessOutcomeTracker.begin(...) to enable user-intent scoring.";
        }
        int s = scores.businessOutcome;
        if (s >= 90) return "User-intent transactions completed successfully this run.";
        if (s >= 75) return "Most user-intent transactions completed. A few partial outcomes worth tracking.";
        if (s >= 50) return "User-intent success rate is mixed. Some workflows did not complete as designed.";
        if (s >= 30) return "User-intent success rate is poor. The product rendered but users frequently could not finish.";
        return "User-intent transactions are broken. The product is unusable for its primary purpose this run.";
    }

    // ── Top-risk extraction ─────────────────────────────────────────────────

    /**
     * Pick the highest-impact clusters for the executive "Top Risks" section.
     * Filters out OBSERVABILITY noise and limits to the top N by severity-then-count.
     */
    public static List<ErrorCluster> topRisks(List<ErrorCluster> all, int limit) {
        return all.stream()
                .filter(c -> c.domain != FailureDomain.OBSERVABILITY)
                .filter(c -> c.severity != ClusterSeverity.INFO && c.severity != ClusterSeverity.LOW)
                .limit(limit)
                .toList();
    }
}
