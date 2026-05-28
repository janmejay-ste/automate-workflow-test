package utils.governance;

import utils.governance.dto.*;

import java.util.List;

/**
 * Generates governance-specific HTML sections for the dashboard and a
 * plain-text governance summary for PDF reports and AI narrative enrichment.
 *
 * All output is pre-computed from GovernanceSnapshotDto — this generator
 * contains only formatting logic, no analytical computation.
 *
 * Dashboard sections produced:
 *   - Governance health banner (HEALTHY / MONITORING / AT_RISK / CRITICAL)
 *   - Explainability panel (WHY each major decision was made)
 *   - Safety violations panel (what was blocked and why)
 *   - Suppression audit panel (suppression history and accuracy)
 *   - Override log panel (human override history)
 *
 * Text summary produced for AI narrative:
 *   - One paragraph governance context (decisions, violations, alerts)
 */
public final class GovernanceReportGenerator {

    private GovernanceReportGenerator() {}

    /**
     * Generates the complete governance HTML section for dashboard injection.
     *
     * @param snapshot from GovernanceSnapshotBuilder
     * @return HTML string (empty div if no governance data)
     */
    public static String buildDashboardHtml(GovernanceSnapshotDto snapshot) {
        if (snapshot == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='section' style='margin-bottom:20px;'>");
        sb.append(buildGovernanceBanner(snapshot));
        sb.append(buildExplainabilityPanel(snapshot));
        if (!snapshot.safetyClean) sb.append(buildSafetyViolationsPanel(snapshot));
        if (snapshot.totalSuppressionsThisRun > 0 || !snapshot.suppressionHistory.isEmpty())
            sb.append(buildSuppressionAuditPanel(snapshot));
        if (snapshot.totalOverridesLogged > 0) sb.append(buildOverrideLogPanel(snapshot));
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Produces a concise plain-text governance context for AI narrative enrichment.
     * Kept under 300 words to avoid overwhelming the AI prompt.
     */
    public static String buildNarrativeContext(GovernanceSnapshotDto snapshot) {
        if (snapshot == null) return "";
        StringBuilder sb = new StringBuilder("\n\n=== Governance Context ===");
        sb.append("\nGovernance Health: ").append(snapshot.governanceHealth);
        sb.append("\n").append(snapshot.governanceComment);
        if (snapshot.violationCount > 0)
            sb.append("\nSafety Violations: ").append(snapshot.violationCount).append(" blocked recommendation(s)");
        if (snapshot.guardedToAdvisoryCount > 0)
            sb.append("\nLow-Confidence Guard: ").append(snapshot.guardedToAdvisoryCount).append(" decision(s) downgraded");
        if (snapshot.confirmedFalseSuppressions > 0)
            sb.append("\nFALSE SUPPRESSIONS: ").append(snapshot.confirmedFalseSuppressions).append(" suppression(s) hid real failures");
        if (snapshot.governanceAlerts != null && !snapshot.governanceAlerts.isEmpty())
            snapshot.governanceAlerts.forEach(a -> sb.append("\n⚠ ").append(a));
        return sb.toString();
    }

    // ── Section builders ──────────────────────────────────────────────────

    private static String buildGovernanceBanner(GovernanceSnapshotDto snap) {
        String color = switch (snap.governanceHealth != null ? snap.governanceHealth : "HEALTHY") {
            case "CRITICAL"   -> "#ef4444";
            case "AT_RISK"    -> "#f97316";
            case "MONITORING" -> "#f59e0b";
            default           -> "#10b981";
        };
        String icon = switch (snap.governanceHealth != null ? snap.governanceHealth : "HEALTHY") {
            case "CRITICAL"   -> "🔴";
            case "AT_RISK"    -> "🟠";
            case "MONITORING" -> "🟡";
            default           -> "🟢";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;'>");
        sb.append("<div class='section-title'>Governance &amp; Explainability</div>");
        sb.append("<span style='padding:4px 14px;border-radius:20px;font-size:12px;font-weight:700;background:")
          .append(color).append("22;color:").append(color).append(";'>")
          .append(icon).append(" ").append(snap.governanceHealth).append("</span></div>");

        sb.append("<p style='font-size:13px;color:#64748b;margin-bottom:16px;'>")
          .append(escHtml(snap.governanceComment)).append("</p>");

        // Alerts
        if (snap.governanceAlerts != null && !snap.governanceAlerts.isEmpty()) {
            sb.append("<div style='display:flex;flex-direction:column;gap:6px;margin-bottom:16px;'>");
            for (String alert : snap.governanceAlerts) {
                sb.append("<div style='background:#fef3c7;border-left:3px solid #f59e0b;border-radius:6px;padding:8px 12px;font-size:12px;color:#92400e;'>")
                  .append(escHtml(alert)).append("</div>");
            }
            sb.append("</div>");
        }

        // Summary tiles
        sb.append("<div style='display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:16px;'>");
        sb.append(govTile("Decisions Audited", String.valueOf(snap.totalDecisionsLogged), "#6366f1"));
        sb.append(govTile("Safety Violations", String.valueOf(snap.violationCount),
                snap.violationCount > 0 ? "#ef4444" : "#10b981"));
        sb.append(govTile("Suppression Accuracy",
                snap.suppressionAccuracyPct >= 0 ? String.format("%.0f%%", snap.suppressionAccuracyPct) : "N/A",
                snap.confirmedFalseSuppressions > 0 ? "#ef4444" : "#10b981"));
        sb.append(govTile("Human Overrides", String.valueOf(snap.totalOverridesLogged), "#f59e0b"));
        sb.append("</div>");
        return sb.toString();
    }

    private static String buildExplainabilityPanel(GovernanceSnapshotDto snap) {
        if (snap.explanations == null || snap.explanations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-bottom:16px;'><div style='font-size:13px;font-weight:600;color:#334155;margin-bottom:10px;'>Decision Explanations</div>");
        for (ExplanationDto e : snap.explanations) {
            sb.append("<div style='background:#f8fafc;border-radius:8px;padding:12px 14px;margin-bottom:8px;border-left:3px solid #6366f1;'>");
            sb.append("<div style='font-size:12px;font-weight:600;color:#334155;'>")
              .append(escHtml(e.subject)).append(" → ").append(escHtml(e.decision)).append("</div>");
            sb.append("<div style='font-size:12px;color:#64748b;margin-top:4px;'>")
              .append(escHtml(e.headline)).append("</div>");
            if (e.factors != null && !e.factors.isEmpty()) {
                sb.append("<ul style='margin:6px 0 0 16px;padding:0;font-size:11px;color:#475569;'>");
                e.factors.forEach(f -> sb.append("<li>").append(escHtml(f)).append("</li>"));
                sb.append("</ul>");
            }
            if (e.advisory != null) {
                sb.append("<div style='font-size:11px;color:#92400e;margin-top:6px;font-style:italic;'>")
                  .append(escHtml(e.advisory)).append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String buildSafetyViolationsPanel(GovernanceSnapshotDto snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-bottom:16px;'><div style='font-size:13px;font-weight:600;color:#dc2626;margin-bottom:8px;'>Safety Violations Blocked</div>");
        for (SafetyViolationDto v : snap.safetyViolations) {
            sb.append("<div style='background:#fee2e2;border-left:3px solid #ef4444;border-radius:6px;padding:10px 14px;margin-bottom:8px;'>");
            sb.append("<div style='font-size:12px;font-weight:600;color:#dc2626;'>")
              .append(v.violationRule).append(" — blocked: ").append(v.blockedDecision)
              .append(" on ").append(escHtml(v.blockedTarget)).append("</div>");
            sb.append("<div style='font-size:12px;color:#7f1d1d;margin-top:4px;'>").append(escHtml(v.explanation)).append("</div>");
            sb.append("<div style='font-size:11px;color:#991b1b;margin-top:4px;font-weight:500;'>Action: ").append(escHtml(v.recommendedAction)).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String buildSuppressionAuditPanel(GovernanceSnapshotDto snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-bottom:16px;'><div style='font-size:13px;font-weight:600;color:#334155;margin-bottom:8px;'>Suppression Audit</div>");
        sb.append("<div style='font-size:12px;color:#64748b;margin-bottom:8px;'>Suppressed this run: <strong>")
          .append(snap.totalSuppressionsThisRun)
          .append("</strong> &nbsp;|&nbsp; Accuracy: <strong>")
          .append(snap.suppressionAccuracyPct >= 0 ? String.format("%.0f%%", snap.suppressionAccuracyPct) : "N/A")
          .append("</strong> &nbsp;|&nbsp; Hidden regressions: <strong style='color:")
          .append(snap.confirmedFalseSuppressions > 0 ? "#ef4444" : "#10b981").append(";'>")
          .append(snap.confirmedFalseSuppressions).append("</strong></div>");

        if (snap.suppressionHistory != null && !snap.suppressionHistory.isEmpty()) {
            List<SuppressionRecordDto> topSuppressed = snap.suppressionHistory.stream()
                    .sorted((a, b) -> Integer.compare(b.timesSuppressed, a.timesSuppressed))
                    .limit(5).toList();
            sb.append("<table style='width:100%;border-collapse:collapse;font-size:12px;'>");
            sb.append("<thead><tr style='color:#64748b;text-transform:uppercase;font-size:11px;'>");
            sb.append("<th style='padding:6px;text-align:left;'>Target</th><th>Reason</th><th>Count</th><th>Hidden?</th></tr></thead><tbody>");
            for (SuppressionRecordDto r : topSuppressed) {
                String hidColor = r.hidRealRegression ? "#ef4444" : "#10b981";
                sb.append("<tr style='border-bottom:1px solid #f1f5f9;'>")
                  .append("<td style='padding:6px;font-weight:500;'>").append(escHtml(r.target)).append("</td>")
                  .append("<td style='padding:6px;color:#64748b;'>").append(escHtml(r.suppressionReason)).append("</td>")
                  .append("<td style='padding:6px;'>").append(r.timesSuppressed).append("×</td>")
                  .append("<td style='padding:6px;color:").append(hidColor).append(";font-weight:600;'>")
                  .append(r.hidRealRegression ? "YES ⚠" : "No").append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String buildOverrideLogPanel(GovernanceSnapshotDto snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-bottom:8px;'><div style='font-size:13px;font-weight:600;color:#334155;margin-bottom:8px;'>Human Override Log</div>");
        sb.append("<div style='font-size:12px;color:#64748b;margin-bottom:8px;'>Total overrides: <strong>")
          .append(snap.totalOverridesLogged)
          .append("</strong> &nbsp;|&nbsp; Effective: <strong>")
          .append(snap.effectiveOverrideCount)
          .append("</strong></div>");
        if (snap.recentOverrides != null && !snap.recentOverrides.isEmpty()) {
            sb.append("<table style='width:100%;border-collapse:collapse;font-size:12px;'>");
            sb.append("<thead><tr style='color:#64748b;text-transform:uppercase;font-size:11px;'>");
            sb.append("<th style='padding:6px;text-align:left;'>Target</th><th>Decision</th><th>Reason</th><th>Owner</th></tr></thead><tbody>");
            snap.recentOverrides.stream().limit(5).forEach(o ->
                sb.append("<tr style='border-bottom:1px solid #f1f5f9;'>")
                  .append("<td style='padding:6px;font-weight:500;'>").append(escHtml(o.target)).append("</td>")
                  .append("<td style='padding:6px;color:#6366f1;'>").append(escHtml(o.decisionType)).append("</td>")
                  .append("<td style='padding:6px;color:#64748b;'>").append(escHtml(o.overrideReason)).append("</td>")
                  .append("<td style='padding:6px;'>").append(escHtml(o.owner)).append("</td></tr>")
            );
            sb.append("</tbody></table>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    // ── Shared ────────────────────────────────────────────────────────────

    private static String govTile(String label, String value, String color) {
        return String.format(
            "<div style='background:white;border-radius:10px;padding:12px 14px;border:1px solid #e2e8f0;border-left:4px solid %s;'>" +
            "<div style='font-size:10px;font-weight:600;color:#64748b;text-transform:uppercase;'>%s</div>" +
            "<div style='font-size:22px;font-weight:800;color:%s;margin-top:2px;'>%s</div></div>",
            color, label, color, value);
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
