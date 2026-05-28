package utils.health.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.health.HealthTracker;
import utils.health.semantic.*;
import utils.health.trend.SemanticTrendAnalyzer;
import utils.health.trend.SemanticTrendAnalyzer.LayeredField;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Generates the executive-grade and technical-deep-dive PDF reports.
 *
 * Architectural contract:
 *   <pre>
 *   HealthTracker → SemanticHealthSnapshot → PdfReportBuilder → PDF
 *   </pre>
 *
 * This class never reaches into HealthTracker directly for interpretation —
 * it consumes only the {@link SemanticHealthSnapshot} so the PDF and HTML
 * dashboard share one semantic truth.  No render-layer policy lives here:
 *   - severity comes from {@link ClusterSeverity}
 *   - domain ownership comes from {@link OwnershipRouter}
 *   - "Why this matters" text comes from {@link NarrativeInterpreter}
 *   - layered scores come from {@link LayeredHealthScores}
 *
 * Each renderer choice (font, page-break, color) is presentation-only.
 * Replace OpenHTMLtoPDF with iText later and the semantic layer is untouched.
 */
public final class PdfReportBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(PdfReportBuilder.class);
    private static final Path   REPORTS_DIR = Paths.get("reports", "trend");

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private PdfReportBuilder() {}

    /**
     * Generate both the executive and technical reports for the current run.
     * Safe to call from {@code BaseTest.afterSuite()} — failures are logged
     * and swallowed; the test run never fails because PDF generation failed.
     */
    public static void generateAll() {
        try {
            SemanticHealthSnapshot snapshot = SemanticHealthSnapshot.from(HealthTracker.get());
            generate(snapshot, ReportMode.EXECUTIVE,
                     REPORTS_DIR.resolve("executive_report.pdf"));
            generate(snapshot, ReportMode.TECHNICAL,
                     REPORTS_DIR.resolve("technical_report.pdf"));
        } catch (Exception e) {
            LOG.warn("PDF report generation failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Render one report.  Public so callers can target a specific mode/path
     * (e.g. CI uploading only the executive PDF as a release artifact).
     */
    public static void generate(SemanticHealthSnapshot snapshot,
                                ReportMode mode,
                                Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            String html  = renderHtml(snapshot, mode);
            String xhtml = toXhtml(html);
            try (OutputStream os = Files.newOutputStream(outputPath)) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.withHtmlContent(xhtml, null);
                builder.toStream(os);
                builder.run();
            }
            LOG.info("PDF [{}] written to {}", mode, outputPath.toAbsolutePath());
        } catch (Exception e) {
            LOG.warn("Failed to render {} PDF: {}", mode, e.getMessage());
        }
    }

    /**
     * Normalize HTML5 markup into strict XHTML before handing it to the PDF
     * renderer.  OpenHTMLtoPDF parses with XML SAX — void tags like
     * {@code <meta>}, {@code <br>}, {@code <hr>}, {@code <img>} must be
     * self-closed and ampersands must be entities.
     *
     * Doing this here means the render templates can stay HTML5-style; the
     * conversion is one-shot, deterministic, and isolated to this method.
     */
    private static String toXhtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings()
           .syntax(Document.OutputSettings.Syntax.xml)
           .escapeMode(Entities.EscapeMode.xhtml)
           .prettyPrint(false);
        return doc.html();
    }

    // ── HTML composition ─────────────────────────────────────────────────────

    private static String renderHtml(SemanticHealthSnapshot snap, ReportMode mode) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><style>");
        html.append(stylesheet());
        html.append("</style></head><body>");

        html.append(renderCoverPage(snap, mode));
        html.append(renderReleaseDecision(snap));
        html.append(renderTopRisks(snap));
        html.append(renderPerDomain(snap));
        html.append(renderReliability(snap));
        html.append(renderBusinessOutcomes(snap, mode));

        if (mode == ReportMode.TECHNICAL) {
            html.append(renderTechnicalAppendix(snap));
        }

        html.append(renderFooter(mode));
        html.append("</body></html>");
        return html.toString();
    }

    private static String renderCoverPage(SemanticHealthSnapshot snap, ReportMode mode) {
        HealthTracker t = HealthTracker.get();
        String title = (mode == ReportMode.EXECUTIVE)
                ? "Executive Quality Report"
                : "Technical Quality Deep-Dive";

        // Trend deltas — read against the persisted JSONL history.  When no
        // history exists ("first recorded run"), the cards render the score
        // alone without a delta line.
        SemanticTrendAnalyzer.ScoreDelta dProduct =
                SemanticTrendAnalyzer.deltaFor(LayeredField.PRODUCT,   snap.scores.productHealth,       5);
        SemanticTrendAnalyzer.ScoreDelta dFramework =
                SemanticTrendAnalyzer.deltaFor(LayeredField.FRAMEWORK, snap.scores.frameworkHealth,     5);
        SemanticTrendAnalyzer.ScoreDelta dTelemetry =
                SemanticTrendAnalyzer.deltaFor(LayeredField.TELEMETRY, snap.scores.telemetryConfidence, 5);

        // Fourth card: Business Outcome.  Renders "—" with a NO DATA badge when
        // no transactions were recorded so absence is visible, not hidden.
        String businessCard = snap.scores.businessUnscored()
                ? scoreCardNoData("Business Outcome", "user-intent — record transactions to enable")
                : scoreCardSimple("Business Outcome", snap.scores.businessOutcome,
                                  snap.scores.businessStatus());

        return ""
            + "<section class='cover'>"
            + "  <div class='cover-label'>Quality Intelligence Report</div>"
            + "  <h1 class='cover-title'>" + esc(title) + "</h1>"
            + "  <div class='cover-meta'>"
            +      renderExportMetadata()
            + "  </div>"
            + "  <div class='cover-status cover-status-4col'>"
            +      scoreCardWithDelta("Product Health",       snap.scores.productHealth,
                                      snap.scores.productStatus(),       dProduct)
            +      scoreCardWithDelta("Framework Health",     snap.scores.frameworkHealth,
                                      snap.scores.frameworkStatus(),     dFramework)
            +      scoreCardWithDelta("Telemetry Confidence", snap.scores.telemetryConfidence,
                                      snap.scores.telemetryStatus(),     dTelemetry)
            +      businessCard
            + "  </div>"
            + "</section>"
            + "<div class='page-break'></div>";
    }

    /** Score card without a trend delta — used for business outcomes (no history yet). */
    private static String scoreCardSimple(String label, int value, String status) {
        String tone = scoreTone(value);
        return "<div class='score-card " + tone + "'>"
             + "<div class='sc-label'>" + esc(label) + "</div>"
             + "<div class='sc-value'>" + value + "<span class='sc-max'>/100</span></div>"
             + "<div class='sc-status'>" + esc(status) + "</div>"
             + "</div>";
    }

    /** Score card placeholder for "no data" — distinct from a 0 score. */
    private static String scoreCardNoData(String label, String hint) {
        return "<div class='score-card tone-nodata'>"
             + "<div class='sc-label'>" + esc(label) + "</div>"
             + "<div class='sc-value sc-value-nodata'>—</div>"
             + "<div class='sc-status'>NO DATA</div>"
             + "<div class='sc-delta'>" + esc(hint) + "</div>"
             + "</div>";
    }

    /**
     * Export metadata block — Build ID, Commit SHA, Branch, Environment,
     * Generated At, Suite Duration.  Values are read from system properties or
     * environment variables when available (so CI can inject Build ID / SHA),
     * with safe fallbacks for local runs.
     */
    private static String renderExportMetadata() {
        HealthTracker t = HealthTracker.get();
        String buildId      = pick("BUILD_ID",       "build.id",       "(local)");
        String commitSha    = pick("GIT_COMMIT_SHA", "git.commit.sha", "(unknown)");
        String branch       = pick("GIT_BRANCH",     "git.branch",     "(unknown)");
        String duration     = pick(null,             "suite.duration", "(not recorded)");

        StringBuilder s = new StringBuilder();
        s.append(metaRow("Suite",         t.getSuiteName()));
        s.append(metaRow("Environment",   t.getEnvironment()));
        s.append(metaRow("Build ID",      buildId));
        s.append(metaRow("Commit SHA",    commitSha));
        s.append(metaRow("Branch",        branch));
        s.append(metaRow("Generated",     TS.format(Instant.now())));
        if (!"(not recorded)".equals(duration)) {
            s.append(metaRow("Suite Duration", duration));
        }
        return s.toString();
    }

    /**
     * Look up a metadata value: env var first (CI injects these),
     * then system property (local override), then fallback.
     */
    private static String pick(String envVar, String sysProp, String fallback) {
        if (envVar != null) {
            String v = System.getenv(envVar);
            if (v != null && !v.isBlank()) return v;
        }
        if (sysProp != null) {
            String v = System.getProperty(sysProp);
            if (v != null && !v.isBlank()) return v;
        }
        return fallback;
    }

    private static String metaRow(String key, String value) {
        return "<div><span class='k'>" + esc(key) + "</span>"
             + "<span class='v'>" + esc(value) + "</span></div>";
    }

    /**
     * Score card with an inline delta line ("▲ +5 vs prev · ▲ +2.3 vs 5-run avg")
     * or "first recorded run" when no history is available.
     */
    private static String scoreCardWithDelta(String label, int value, String status,
                                             SemanticTrendAnalyzer.ScoreDelta delta) {
        String tone = scoreTone(value);
        return "<div class='score-card " + tone + "'>"
             + "<div class='sc-label'>" + esc(label) + "</div>"
             + "<div class='sc-value'>" + value + "<span class='sc-max'>/100</span></div>"
             + "<div class='sc-status'>" + esc(status) + "</div>"
             + "<div class='sc-delta'>" + esc(delta.inlineLabel()) + "</div>"
             + "</div>";
    }

    private static String renderReleaseDecision(SemanticHealthSnapshot snap) {
        String recommendation = NarrativeInterpreter.releaseRecommendation(snap.scores);
        String tone = toneClassFor(recommendation);
        return ""
            + "<section>"
            + "  <h2>Release Decision</h2>"
            + "  <div class='release-box " + tone + "'>"
            + "    <div class='release-label'>Recommendation</div>"
            + "    <div class='release-text'>" + esc(recommendation) + "</div>"
            + "  </div>"
            + "  <h3>Why this is the recommendation</h3>"
            + "  <table class='narrative'>"
            +     narrativeRow("Product Health",       snap.scores.productHealth,
                              NarrativeInterpreter.forProductHealth(snap.scores.productHealth))
            +     narrativeRow("Framework Health",     snap.scores.frameworkHealth,
                              NarrativeInterpreter.forFrameworkHealth(snap.scores.frameworkHealth))
            +     narrativeRow("Telemetry Confidence", snap.scores.telemetryConfidence,
                              NarrativeInterpreter.forTelemetryConfidence(snap.scores.telemetryConfidence))
            + "  </table>"
            + "</section>"
            + "<div class='page-break'></div>";
    }

    private static String renderTopRisks(SemanticHealthSnapshot snap) {
        List<ErrorCluster> risks = NarrativeInterpreter.topRisks(snap.clusters, 5);
        StringBuilder s = new StringBuilder();
        s.append("<section><h2>Top Risks</h2>");
        if (risks.isEmpty()) {
            s.append("<p class='empty'>No critical or high-severity issues detected. "
                  + "Lower-severity diagnostics are listed in the Per-Domain section.</p>");
        } else {
            s.append("<p class='hint'>The highest-impact issues from this run. "
                  + "Observability noise (third-party scripts, deprecations) is excluded.</p>");
            for (ErrorCluster c : risks) {
                OwnershipRouter.Routing r = OwnershipRouter.routeFor(c.domain);
                s.append("<div class='risk-card'>")
                 .append("<div class='risk-head'>")
                 .append("<span class='sev sev-").append(c.severity.name().toLowerCase()).append("'>")
                 .append(esc(c.severity.label)).append("</span>")
                 .append("<span class='dom'>").append(esc(c.domain.label)).append("</span>")
                 .append("<span class='cnt'>×").append(c.count).append("</span>")
                 .append("</div>")
                 .append("<div class='risk-title'>").append(esc(c.title)).append("</div>")
                 .append("<div class='risk-why'><b>Why this matters:</b> ")
                 .append(esc(NarrativeInterpreter.forCluster(c))).append("</div>")
                 .append("<div class='risk-route'><b>Owner:</b> ")
                 .append(esc(r.team)).append(" — ").append(esc(r.action)).append("</div>")
                 .append("</div>");
            }
        }
        s.append("</section><div class='page-break'></div>");
        return s.toString();
    }

    private static String renderPerDomain(SemanticHealthSnapshot snap) {
        StringBuilder s = new StringBuilder();
        s.append("<section><h2>Issues by Domain</h2>");
        s.append("<p class='hint'>Each panel maps to a single ownership boundary. "
              + "Empty panels are explicit ✓ Clean signals, not omissions.</p>");

        // Render every domain (including clean ones) in priority order
        FailureDomain[] order = {
            FailureDomain.PRODUCT,
            FailureDomain.INFRASTRUCTURE,
            FailureDomain.FRAMEWORK,
            FailureDomain.TELEMETRY,
            FailureDomain.OBSERVABILITY
        };
        for (FailureDomain d : order) {
            List<ErrorCluster> rows = snap.clusters.stream()
                    .filter(c -> c.domain == d)
                    .toList();
            OwnershipRouter.Routing route = OwnershipRouter.routeFor(d);
            s.append("<div class='domain-panel'>")
             .append("<div class='domain-head'><span class='dom-title'>")
             .append(esc(d.label)).append(" Issues</span>");
            if (rows.isEmpty()) {
                s.append("<span class='clean'>✓ Clean</span>");
            } else {
                s.append("<span class='cnt'>").append(rows.size())
                 .append(" cluster").append(rows.size() == 1 ? "" : "s").append("</span>");
            }
            s.append("</div>");
            s.append("<div class='owner'>").append(esc(route.team))
             .append(" — ").append(esc(route.action)).append("</div>");
            if (rows.isEmpty()) {
                s.append("<div class='empty-inline'>No issues detected in this domain this run.</div>");
            } else {
                s.append("<table class='cluster-tbl'>");
                for (ErrorCluster c : rows) {
                    s.append("<tr>")
                     .append("<td class='c-title'>").append(esc(c.title)).append("</td>")
                     .append("<td class='c-cnt'>×").append(c.count).append("</td>")
                     .append("<td class='c-sev'><span class='sev sev-")
                     .append(c.severity.name().toLowerCase()).append("'>")
                     .append(esc(c.severity.label)).append("</span></td>")
                     .append("</tr>");
                }
                s.append("</table>");
            }
            s.append("</div>");
        }
        s.append("</section><div class='page-break'></div>");
        return s.toString();
    }

    /**
     * Business Outcomes — the user-intent transactions captured during the run.
     * Renders a summary count line + a per-transaction table with criteria evidence.
     * In TECHNICAL mode the criteria/evidence is fully expanded; in EXECUTIVE mode
     * we only show the verdict + name to keep the page count tight.
     */
    private static String renderBusinessOutcomes(SemanticHealthSnapshot snap, ReportMode mode) {
        StringBuilder s = new StringBuilder();
        s.append("<section><h2>Business Outcomes</h2>");
        if (snap.businessOutcomes.isEmpty()) {
            s.append("<p class='empty'>No business-outcome transactions recorded this run. ")
             .append("Wire test flows to <code>BusinessOutcomeTracker.get().begin(name, category)</code> ")
             .append("to enable user-intent scoring.</p>");
            s.append("</section><div class='page-break'></div>");
            return s.toString();
        }

        s.append("<p class='hint'>")
         .append(esc(NarrativeInterpreter.forBusinessOutcome(snap.scores)))
         .append("</p>");

        // Summary counts row
        s.append("<table class='biz-summary'><tr>")
         .append("<th>Success</th><th>Partial</th><th>Failed</th><th>Aborted</th></tr><tr>")
         .append("<td class='biz-success'>").append(snap.businessSuccess).append("</td>")
         .append("<td class='biz-partial'>").append(snap.businessPartial).append("</td>")
         .append("<td class='biz-failed'>" ).append(snap.businessFailed ).append("</td>")
         .append("<td class='biz-aborted'>").append(snap.businessAborted).append("</td>")
         .append("</tr></table>");

        // Per-transaction table
        s.append("<table class='biz-tx'><thead><tr>")
         .append("<th>Transaction</th><th>Category</th><th>State</th><th>Duration</th>");
        if (mode == ReportMode.TECHNICAL) {
            s.append("<th>Criteria (passed / total)</th>");
        }
        s.append("</tr></thead><tbody>");

        for (utils.health.business.BusinessTransaction tx : snap.businessOutcomes) {
            long passed = tx.criteria().stream().filter(c -> !c.isPending() && c.passed).count();
            long total  = tx.criteria().size();
            String stateClass = "state-" + tx.state().name().toLowerCase();
            s.append("<tr>")
             .append("<td>").append(esc(tx.name)).append("</td>")
             .append("<td class='biz-cat'>").append(esc(tx.category.name())).append("</td>")
             .append("<td><span class='biz-state ").append(stateClass).append("'>")
             .append(tx.state().name()).append("</span></td>")
             .append("<td class='biz-dur'>").append(formatDur(tx.durationMs())).append("</td>");
            if (mode == ReportMode.TECHNICAL) {
                s.append("<td class='biz-crit'>").append(passed).append(" / ").append(total).append("</td>");
            }
            s.append("</tr>");

            // Failure reason inline if FAILED/PARTIAL/ABORTED
            if (tx.state() != utils.health.business.BusinessOutcomeState.SUCCESS
                    && tx.failureReason() != null) {
                int span = (mode == ReportMode.TECHNICAL) ? 5 : 4;
                s.append("<tr><td colspan='").append(span)
                 .append("' class='biz-reason'>⚠ ").append(esc(tx.failureReason())).append("</td></tr>");
            }

            // Criteria details — TECHNICAL only
            if (mode == ReportMode.TECHNICAL && !tx.criteria().isEmpty()) {
                s.append("<tr><td colspan='5' class='biz-crit-detail'>");
                for (utils.health.business.SuccessCriterion c : tx.criteria()) {
                    String mark = c.isPending() ? "○" : (c.passed ? "✓" : "✗");
                    String cls  = c.isPending() ? "crit-pending"
                                : c.passed      ? "crit-pass" : "crit-fail";
                    s.append("<div class='").append(cls).append("'>")
                     .append(mark).append(" ").append(esc(c.label));
                    if (!c.isPending()) {
                        s.append(" <span class='crit-obs'>(observed: ")
                         .append(esc(c.observed)).append(")</span>");
                    }
                    s.append("</div>");
                }
                s.append("</td></tr>");
            }
        }
        s.append("</tbody></table>");
        s.append("</section><div class='page-break'></div>");
        return s.toString();
    }

    private static String formatDur(long ms) {
        if (ms <= 0) return "—";
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm %02ds", ms / 60_000, (ms % 60_000) / 1000);
    }

    private static String renderReliability(SemanticHealthSnapshot snap) {
        StringBuilder s = new StringBuilder();
        s.append("<section><h2>Phase Reliability</h2>");
        if (snap.reliabilities.isEmpty()) {
            s.append("<p class='empty'>No phase-level reliability data captured. "
                  + "Instrument flows with HealthTracker.recordSuccess() to populate this section.</p>");
        } else {
            s.append("<p class='hint'>Per-phase pass rate over this run. Confidence "
                  + "labels reflect sample size — small N is marked PARTIAL.</p>");
            s.append("<table class='reliab-tbl'><thead><tr>")
             .append("<th>Phase</th><th>Pass %</th><th>Successes / Total</th>")
             .append("<th>Confidence</th><th>Interpretation</th>")
             .append("</tr></thead><tbody>");
            for (PhaseReliability pr : snap.reliabilities) {
                String pct = pr.percentage < 0 ? "—"
                           : String.format("%.1f%%", pr.percentage);
                s.append("<tr>")
                 .append("<td>").append(esc(pr.phase)).append("</td>")
                 .append("<td class='pct'>").append(pct).append("</td>")
                 .append("<td>").append(pr.successes).append(" / ")
                 .append(pr.successes + pr.failures).append("</td>")
                 .append("<td><span class='conf conf-")
                 .append(pr.confidence.name().toLowerCase()).append("'>")
                 .append(esc(pr.confidence.label)).append("</span></td>")
                 .append("<td>").append(esc(NarrativeInterpreter.forReliability(pr))).append("</td>")
                 .append("</tr>");
            }
            s.append("</tbody></table>");
        }
        s.append("</section><div class='page-break'></div>");
        return s.toString();
    }

    private static String renderTechnicalAppendix(SemanticHealthSnapshot snap) {
        StringBuilder s = new StringBuilder();
        s.append("<section><h2>Technical Appendix</h2>");

        // Full clusters (all domains, including Observability)
        s.append("<h3>All Error Clusters</h3>");
        if (snap.clusters.isEmpty()) {
            s.append("<p class='empty'>No clusters this run.</p>");
        } else {
            s.append("<table class='cluster-full'>");
            s.append("<thead><tr><th>Title</th><th>Domain</th><th>Severity</th>")
             .append("<th>×</th><th>Sample context</th></tr></thead><tbody>");
            for (ErrorCluster c : snap.clusters) {
                s.append("<tr>")
                 .append("<td>").append(esc(c.title)).append("</td>")
                 .append("<td>").append(esc(c.domain.label)).append("</td>")
                 .append("<td><span class='sev sev-")
                 .append(c.severity.name().toLowerCase()).append("'>")
                 .append(esc(c.severity.label)).append("</span></td>")
                 .append("<td>").append(c.count).append("</td>")
                 .append("<td class='ctx'>").append(esc(c.sampleContext)).append("</td>")
                 .append("</tr>");
            }
            s.append("</tbody></table>");
        }

        // Telemetry summary
        s.append("<h3>Telemetry Integrity</h3>");
        s.append("<table class='kv'>")
         .append("<tr><th>Valid timing samples</th><td>").append(snap.validTimingCount).append("</td></tr>")
         .append("<tr><th>UNKNOWN timing samples</th><td>").append(snap.unknownTimingCount).append("</td></tr>")
         .append("<tr><th>Telemetry confidence score</th><td>").append(snap.scores.telemetryConfidence).append(" / 100</td></tr>")
         .append("</table>");

        // Error count by domain
        if (!snap.errorCountByDomain.isEmpty()) {
            s.append("<h3>Error Count by Domain</h3>");
            s.append("<table class='kv'>");
            for (Map.Entry<FailureDomain, Integer> e : snap.errorCountByDomain.entrySet()) {
                s.append("<tr><th>").append(esc(e.getKey().label))
                 .append("</th><td>").append(e.getValue()).append("</td></tr>");
            }
            s.append("</table>");
        }

        // URL Validation findings — TECHNICAL only because the table is dense
        // and forensic.  Executive mode keeps the cover-page-level pass rate
        // unchanged.
        utils.urlvalidator.UrlValidationTracker urlT = utils.urlvalidator.UrlValidationTracker.get();
        if (urlT.totalUrlsValidated() > 0) {
            s.append("<h3>URL Validation</h3>");
            s.append("<table class='kv'>")
             .append("<tr><th>Pages scanned</th><td>").append(urlT.pagesScanned().size()).append("</td></tr>")
             .append("<tr><th>URLs validated</th><td>").append(urlT.totalUrlsValidated()).append("</td></tr>")
             .append("<tr><th>Findings</th><td>").append(urlT.totalFindings()).append("</td></tr>")
             .append("</table>");
            if (urlT.totalFindings() > 0) {
                s.append("<table class='cluster-full'><thead><tr>")
                 .append("<th>Type</th><th>Severity</th><th>Domain</th><th>Status</th><th>URL</th>")
                 .append("</tr></thead><tbody>");
                for (utils.urlvalidator.UrlFinding f : urlT.findings()) {
                    s.append("<tr>")
                     .append("<td>").append(esc(f.type.name().replace('_', ' '))).append("</td>")
                     .append("<td><span class='sev sev-").append(f.severity.name().toLowerCase()).append("'>")
                     .append(esc(f.severity.label)).append("</span></td>")
                     .append("<td>").append(esc(f.domain.label)).append("</td>")
                     .append("<td>").append(f.result.status == 0 ? "—" : f.result.status).append("</td>")
                     .append("<td class='ctx'>").append(esc(f.result.discovered.url)).append("</td>")
                     .append("</tr>");
                }
                s.append("</tbody></table>");
            }
        }

        s.append("</section>");
        return s.toString();
    }

    private static String renderFooter(ReportMode mode) {
        return "<div class='footer'>" + esc(mode.name())
             + " · Generated by Appy Pie Automate · " + TS.format(Instant.now())
             + "</div>";
    }

    // ── Small render helpers ────────────────────────────────────────────────

    private static String scoreCard(String label, int value, String status) {
        String tone = scoreTone(value);
        return "<div class='score-card " + tone + "'>"
             + "<div class='sc-label'>" + esc(label) + "</div>"
             + "<div class='sc-value'>" + value + "<span class='sc-max'>/100</span></div>"
             + "<div class='sc-status'>" + esc(status) + "</div>"
             + "</div>";
    }

    private static String narrativeRow(String label, int value, String narrative) {
        return "<tr>"
             + "<th class='n-label'>" + esc(label) + "</th>"
             + "<td class='n-score'><span class='" + scoreTone(value) + "'>"
             + value + "</span></td>"
             + "<td class='n-text'>" + esc(narrative) + "</td>"
             + "</tr>";
    }

    private static String scoreTone(int value) {
        if (value >= 90) return "tone-excellent";
        if (value >= 75) return "tone-good";
        if (value >= 50) return "tone-moderate";
        if (value >= 30) return "tone-poor";
        return "tone-critical";
    }

    private static String toneClassFor(String recommendation) {
        if (recommendation.startsWith("BLOCK")) return "tone-critical";
        if (recommendation.startsWith("HOLD"))  return "tone-poor";
        if (recommendation.startsWith("PROCEED WITH CAUTION")) return "tone-moderate";
        return "tone-good";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── Stylesheet — print-optimised, page-break aware ──────────────────────

    private static String stylesheet() {
        return ""
            + "@page { size: A4; margin: 22mm 18mm; }"
            + "body { font-family: 'Helvetica','Arial',sans-serif; color:#1e293b; font-size:11pt; line-height:1.45; }"
            + "h1,h2,h3 { color:#0f172a; margin:0 0 8pt 0; }"
            + "h1 { font-size:24pt; font-weight:900; }"
            + "h2 { font-size:16pt; border-bottom:1pt solid #cbd5e1; padding-bottom:4pt; margin-top:14pt; }"
            + "h3 { font-size:12pt; color:#334155; margin-top:12pt; }"
            + "section { page-break-inside: avoid; margin-bottom:14pt; }"
            + ".page-break { page-break-after: always; }"
            + ".hint { color:#64748b; font-size:9.5pt; margin:2pt 0 8pt 0; }"
            + ".empty { color:#94a3b8; font-style:italic; padding:8pt; background:#f8fafc; border-radius:4pt; }"
            // Cover
            + ".cover { padding-top:30mm; text-align:center; }"
            + ".cover-label { color:#64748b; font-size:10pt; letter-spacing:2pt; text-transform:uppercase; }"
            + ".cover-title { font-size:32pt; margin:6pt 0 18pt 0; }"
            + ".cover-meta { display:table; margin:0 auto 22pt auto; }"
            + ".cover-meta div { display:table-row; }"
            + ".cover-meta .k { display:table-cell; padding:3pt 14pt 3pt 0; color:#94a3b8; text-align:right; }"
            + ".cover-meta .v { display:table-cell; padding:3pt 0; font-weight:700; text-align:left; }"
            + ".cover-status { display:table; width:100%; margin-top:16pt; }"
            + ".score-card { display:table-cell; padding:10pt 14pt; border:1pt solid #e2e8f0; border-radius:6pt; "
            +              "vertical-align:top; width:33%; }"
            + ".cover-status-4col .score-card { width:25%; padding:8pt 10pt; }"
            + ".sc-value-nodata { color:#cbd5e1; font-style:italic; }"
            + ".tone-nodata { color:#94a3b8; } .tone-nodata .sc-value { color:#cbd5e1; }"
            + ".sc-label { font-size:9pt; color:#64748b; text-transform:uppercase; letter-spacing:0.5pt; }"
            + ".sc-value { font-size:30pt; font-weight:900; line-height:1; }"
            + ".sc-max { font-size:11pt; color:#94a3b8; font-weight:400; margin-left:2pt; }"
            + ".sc-status { font-size:10pt; font-weight:700; margin-top:3pt; }"
            + ".sc-delta  { font-size:8.5pt; color:#64748b; margin-top:3pt; font-style:italic; }"
            // Tones
            + ".tone-excellent { color:#15803d; } .tone-excellent .sc-value { color:#15803d; }"
            + ".tone-good      { color:#1d4ed8; } .tone-good      .sc-value { color:#1d4ed8; }"
            + ".tone-moderate  { color:#b45309; } .tone-moderate  .sc-value { color:#b45309; }"
            + ".tone-poor      { color:#c2410c; } .tone-poor      .sc-value { color:#c2410c; }"
            + ".tone-critical  { color:#991b1b; } .tone-critical  .sc-value { color:#991b1b; }"
            // Release box
            + ".release-box { padding:14pt; border-radius:6pt; border-left:5pt solid; margin:6pt 0 12pt 0; "
            +                "background:#f8fafc; border-left-color:#3b82f6; }"
            + ".release-box.tone-critical { background:#fef2f2; border-left-color:#991b1b; }"
            + ".release-box.tone-poor     { background:#fff7ed; border-left-color:#c2410c; }"
            + ".release-box.tone-moderate { background:#fefce8; border-left-color:#b45309; }"
            + ".release-box.tone-good     { background:#f0fdf4; border-left-color:#15803d; }"
            + ".release-label { font-size:9pt; color:#64748b; letter-spacing:1pt; text-transform:uppercase; }"
            + ".release-text  { font-size:13pt; font-weight:700; margin-top:3pt; }"
            // Narrative table
            + "table.narrative { width:100%; border-collapse:collapse; }"
            + "table.narrative th, table.narrative td { padding:6pt 4pt; vertical-align:top; "
            +                                          "border-bottom:0.5pt solid #e2e8f0; text-align:left; }"
            + ".n-label { width:130pt; color:#475569; font-weight:700; }"
            + ".n-score { width:50pt; font-weight:900; font-size:14pt; }"
            + ".n-text  { color:#334155; }"
            // Severity badges
            + ".sev { display:inline-block; padding:1pt 6pt; border-radius:8pt; font-size:8pt; "
            +        "font-weight:700; letter-spacing:0.3pt; }"
            + ".sev-critical { background:#fee2e2; color:#991b1b; }"
            + ".sev-high     { background:#fed7aa; color:#9a3412; }"
            + ".sev-medium   { background:#fef3c7; color:#854d0e; }"
            + ".sev-low      { background:#dbeafe; color:#1e40af; }"
            + ".sev-info     { background:#f1f5f9; color:#475569; }"
            // Risk cards
            + ".risk-card { border:0.5pt solid #e2e8f0; border-left:3pt solid #c2410c; "
            +              "padding:8pt 10pt; margin-bottom:8pt; border-radius:4pt; page-break-inside:avoid; }"
            + ".risk-head { font-size:9pt; }"
            + ".risk-head .dom { color:#64748b; margin-left:6pt; }"
            + ".risk-head .cnt { color:#94a3b8; margin-left:6pt; font-variant-numeric:tabular-nums; }"
            + ".risk-title { font-size:11pt; font-weight:700; margin:3pt 0; }"
            + ".risk-why { color:#334155; font-size:10pt; margin-top:3pt; }"
            + ".risk-route { color:#475569; font-size:9.5pt; margin-top:2pt; }"
            // Domain panels
            + ".domain-panel { border:0.5pt solid #e2e8f0; border-top:2pt solid #6366f1; "
            +                 "padding:8pt 10pt; margin-bottom:6pt; border-radius:4pt; page-break-inside:avoid; }"
            + ".domain-head { display:flex; justify-content:space-between; align-items:baseline; }"
            + ".dom-title { font-weight:700; }"
            + ".clean { color:#15803d; font-weight:700; font-size:9pt; }"
            + ".owner { color:#94a3b8; font-size:9pt; margin:2pt 0 4pt 0; }"
            + ".empty-inline { color:#94a3b8; font-style:italic; font-size:9pt; padding:4pt 0; text-align:center; }"
            + "table.cluster-tbl { width:100%; border-collapse:collapse; font-size:9.5pt; }"
            + "table.cluster-tbl td { padding:2pt 4pt; border-bottom:0.5pt solid #f1f5f9; }"
            + ".c-cnt { width:30pt; text-align:right; color:#64748b; font-variant-numeric:tabular-nums; }"
            + ".c-sev { width:70pt; }"
            // Reliability table
            + "table.reliab-tbl { width:100%; border-collapse:collapse; font-size:9.5pt; }"
            + "table.reliab-tbl th, table.reliab-tbl td { padding:4pt 6pt; border-bottom:0.5pt solid #e2e8f0; "
            +                                            "text-align:left; vertical-align:top; }"
            + "table.reliab-tbl th { background:#f8fafc; color:#475569; font-size:9pt; }"
            + ".pct { font-weight:700; font-variant-numeric:tabular-nums; }"
            + ".conf { display:inline-block; padding:1pt 6pt; border-radius:3pt; font-size:8pt; font-weight:600; }"
            + ".conf-valid   { background:#dcfce7; color:#166534; }"
            + ".conf-partial { background:#fef3c7; color:#854d0e; }"
            + ".conf-unknown { background:#f1f5f9; color:#475569; }"
            + ".conf-failed  { background:#fee2e2; color:#991b1b; }"
            // Appendix tables
            + "table.cluster-full { width:100%; border-collapse:collapse; font-size:9pt; }"
            + "table.cluster-full th, table.cluster-full td { padding:3pt 5pt; "
            +                                                "border-bottom:0.5pt solid #e2e8f0; text-align:left; }"
            + "table.cluster-full th { background:#f8fafc; color:#475569; }"
            + ".ctx { color:#64748b; font-family:'Consolas',monospace; font-size:8pt; }"
            + "table.kv { width:60%; border-collapse:collapse; font-size:9.5pt; }"
            + "table.kv th, table.kv td { padding:3pt 6pt; text-align:left; border-bottom:0.5pt solid #e2e8f0; }"
            + "table.kv th { color:#475569; width:50%; }"
            // Business outcomes
            + "table.biz-summary { border-collapse:collapse; margin:6pt 0 10pt 0; font-size:9.5pt; }"
            + "table.biz-summary th, table.biz-summary td { padding:4pt 12pt; border:0.5pt solid #e2e8f0; text-align:center; }"
            + "table.biz-summary th { background:#f8fafc; color:#475569; font-size:9pt; }"
            + "table.biz-summary td { font-weight:900; font-size:14pt; font-variant-numeric:tabular-nums; }"
            + ".biz-success { color:#15803d; } .biz-partial { color:#b45309; }"
            + ".biz-failed  { color:#991b1b; } .biz-aborted { color:#64748b; }"
            + "table.biz-tx { width:100%; border-collapse:collapse; font-size:9.5pt; margin-top:8pt; }"
            + "table.biz-tx th, table.biz-tx td { padding:4pt 6pt; border-bottom:0.5pt solid #e2e8f0; text-align:left; }"
            + "table.biz-tx th { background:#f8fafc; color:#475569; font-size:9pt; }"
            + ".biz-cat { color:#64748b; font-size:8.5pt; }"
            + ".biz-dur { color:#64748b; font-variant-numeric:tabular-nums; text-align:right; }"
            + ".biz-state { display:inline-block; padding:1pt 6pt; border-radius:8pt; font-size:8pt; font-weight:700; }"
            + ".state-success { background:#dcfce7; color:#166534; }"
            + ".state-partial { background:#fef3c7; color:#854d0e; }"
            + ".state-failed  { background:#fee2e2; color:#991b1b; }"
            + ".state-aborted { background:#f1f5f9; color:#64748b; }"
            + ".state-started { background:#dbeafe; color:#1e40af; }"
            + ".biz-reason { background:#fef2f2; color:#7f1d1d; font-style:italic; padding:4pt 8pt; }"
            + ".biz-crit-detail { padding:4pt 8pt 8pt 22pt; background:#fafbfc; font-size:9pt; }"
            + ".crit-pass    { color:#166534; }"
            + ".crit-fail    { color:#991b1b; }"
            + ".crit-pending { color:#94a3b8; }"
            + ".crit-obs     { color:#64748b; font-size:8pt; }"
            // Footer
            + ".footer { position:fixed; bottom:0; left:0; right:0; text-align:center; "
            +           "color:#94a3b8; font-size:8pt; letter-spacing:0.5pt; }";
    }
}
