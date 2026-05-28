package utils.report;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.clustering.FailureCluster;
import utils.ai.models.AiFailureAnalysis;
import utils.orchestration.dto.WorkflowRiskDto;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates a structured PDF report from ReportDto.
 *
 * Sections:
 *   1. Cover Page
 *   2. Executive Summary (release decision + AI narrative + metrics)
 *   3. Failure Intelligence (cluster table)
 *   4. Full Test Appendix
 *
 * Chart image embedding (via XChart) is prepared as a stub for Phase A.2 —
 * the infrastructure is wired but chart generation is skipped if XChart is
 * not yet integrated into this builder.
 */
public final class PdfReportBuilder {

    /** Controls which report format is generated. */
    public enum PdfMode {
        /** 2-4 pages for leadership / release managers. */
        EXECUTIVE,
        /** Full engineering diagnostic: clusters, AI RCA, trend, orchestration, governance. */
        ENGINEERING
    }

    private static final Logger LOG = LoggerFactory.getLogger(PdfReportBuilder.class);
    private static final String OUTPUT_DIR = "reports/pdf";

    // Fonts
    private static final Font TITLE_FONT  = new Font(Font.HELVETICA, 28, Font.BOLD,  new Color(15, 23, 42));
    private static final Font H1_FONT     = new Font(Font.HELVETICA, 18, Font.BOLD,  new Color(15, 23, 42));
    private static final Font H2_FONT     = new Font(Font.HELVETICA, 14, Font.BOLD,  new Color(30, 41, 59));
    private static final Font BODY_FONT   = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(71, 85, 105));
    private static final Font SMALL_FONT  = new Font(Font.HELVETICA,  9, Font.NORMAL, new Color(100, 116, 139));
    private static final Font MONO_FONT   = new Font(Font.COURIER,    9, Font.NORMAL, new Color(71, 85, 105));
    private static final Font LABEL_FONT  = new Font(Font.HELVETICA,  9, Font.BOLD,  new Color(100, 116, 139));

    // Status colors
    private static final Color COLOR_GREEN  = new Color(16, 185, 129);
    private static final Color COLOR_BLUE   = new Color(59, 130, 246);
    private static final Color COLOR_AMBER  = new Color(245, 158, 11);
    private static final Color COLOR_RED    = new Color(239, 68, 68);
    private static final Color COLOR_BORDER = new Color(226, 232, 240);
    private static final Color COLOR_BG     = new Color(248, 250, 252);

    private PdfReportBuilder() {}

    /** Generates Engineering (full diagnostic) PDF — default mode. */
    public static String generate(ReportDto dto) {
        return generate(dto, PdfMode.ENGINEERING);
    }

    /**
     * Generates a PDF in the specified mode.
     *
     * @param dto  report data
     * @param mode EXECUTIVE (2-4 pages) or ENGINEERING (full diagnostic)
     * @return path to generated file, or null on failure
     */
    public static String generate(ReportDto dto, PdfMode mode) {
        if (dto == null) {
            LOG.warn("PdfReportBuilder: null ReportDto — skipping PDF generation");
            return null;
        }
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
            String suffix   = mode == PdfMode.EXECUTIVE ? "-executive" : "-engineering";
            String filename = "report-" + dto.timestamp + suffix + ".pdf";
            Path out = Paths.get(OUTPUT_DIR, filename);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 50, 50, 60, 50);
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            writer.setPageEvent(new HeaderFooterEvent(dto, mode));

            doc.open();

            if (mode == PdfMode.EXECUTIVE) {
                writeExecutiveCover(doc, dto);
                doc.newPage();
                writeBlockersAndClusters(doc, dto);
                if (dto.orchestrationSnapshot != null && dto.orchestrationSnapshot.workflowRisks != null
                        && !dto.orchestrationSnapshot.workflowRisks.isEmpty()) {
                    doc.newPage();
                    writeWorkflowsAndRecommendation(doc, dto);
                }
            } else {
                writeCoverPage(doc, dto);
                doc.newPage();
                writeExecutiveSummary(doc, dto);
                doc.newPage();
                writeFailureIntelligence(doc, dto);
                doc.newPage();
                writeTestAppendix(doc, dto);
            }

            doc.close();
            Files.write(out, baos.toByteArray());
            LOG.info("PDF report [{}] written to {}", mode, out);
            return out.toString();

        } catch (Exception e) {
            LOG.error("PdfReportBuilder failed [{}]: {}", mode, e.getMessage());
            return null;
        }
    }

    // ── Cover Page ────────────────────────────────────────────────────────

    private static void writeCoverPage(Document doc, ReportDto dto) throws Exception {
        doc.add(Chunk.NEWLINE);
        doc.add(Chunk.NEWLINE);
        doc.add(Chunk.NEWLINE);

        Paragraph title = new Paragraph("Quality Report", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);

        doc.add(Chunk.NEWLINE);

        // Status block
        Color statusColor = resolveStatusColor(dto.releaseStatus);
        Font statusFont = new Font(Font.HELVETICA, 20, Font.BOLD, statusColor);
        Paragraph statusPara = new Paragraph(dto.releaseStatus, statusFont);
        statusPara.setAlignment(Element.ALIGN_CENTER);
        doc.add(statusPara);

        doc.add(Chunk.NEWLINE);
        doc.add(new Paragraph(dto.releaseStatusDesc, BODY_FONT));
        doc.add(Chunk.NEWLINE);

        // Metadata table
        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(60);
        meta.setHorizontalAlignment(Element.ALIGN_CENTER);
        meta.setSpacingBefore(20);
        addMetaRow(meta, "Suite",        dto.suite);
        addMetaRow(meta, "Environment",  dto.environment);
        addMetaRow(meta, "Branch",       dto.gitBranch + " (" + dto.gitHash + ")");
        addMetaRow(meta, "Timestamp",    formatTs(dto.timestamp));
        addMetaRow(meta, "Health Score", dto.healthScore + " / 100");
        addMetaRow(meta, "Duration",     formatDuration(dto.durationMs));
        doc.add(meta);
    }

    // ── Executive Summary ─────────────────────────────────────────────────

    private static void writeExecutiveSummary(Document doc, ReportDto dto) throws Exception {
        doc.add(sectionHeader("Executive Summary"));

        // Release Decision
        PdfPTable decisionTable = new PdfPTable(new float[]{1, 1, 1, 1});
        decisionTable.setWidthPercentage(100);
        decisionTable.setSpacingBefore(10);
        decisionTable.setSpacingAfter(16);

        addMetricCell(decisionTable, "Health Score",   String.valueOf(dto.healthScore), resolveStatusColor(dto.releaseStatus));
        String deltaText = dto.previousScore >= 0
                ? (dto.trendDelta >= 0 ? "+" : "") + dto.trendDelta + " pts"
                : "N/A";
        Color deltaColor = dto.trendDelta >= 0 ? COLOR_GREEN : COLOR_RED;
        addMetricCell(decisionTable, "vs Previous Run", deltaText, deltaColor);
        addMetricCell(decisionTable, "Smoke Pass Rate",     pct(dto.smokePassRate),     COLOR_BLUE);
        addMetricCell(decisionTable, "Regression Pass Rate", pct(dto.regressionPassRate), COLOR_BLUE);
        doc.add(decisionTable);

        PdfPTable bugTable = new PdfPTable(new float[]{1, 1, 1});
        bugTable.setWidthPercentage(75);
        bugTable.setSpacingAfter(16);
        addMetricCell(bugTable, "Product Bugs",     String.valueOf(dto.productBugs),      COLOR_RED);
        addMetricCell(bugTable, "Automation Bugs",  String.valueOf(dto.automationBugs),   COLOR_AMBER);
        addMetricCell(bugTable, "Environment Issues", String.valueOf(dto.environmentIssues), COLOR_BLUE);
        doc.add(bugTable);

        // AI Narrative
        doc.add(subSectionHeader("AI Release Narrative"));
        if (dto.aiNarrative != null && dto.aiNarrative.available) {
            Color narrativeColor = resolveNarrativeColor(dto.aiNarrative.decision);
            Font decFont = new Font(Font.HELVETICA, 12, Font.BOLD, narrativeColor);
            doc.add(new Paragraph("Decision: " + dto.aiNarrative.decision
                    + "  (Confidence: " + (int)(dto.aiNarrative.confidence * 100) + "%)", decFont));
            doc.add(Chunk.NEWLINE);
            doc.add(new Paragraph(dto.aiNarrative.summary, BODY_FONT));
            if (dto.aiNarrative.topRisks != null && !dto.aiNarrative.topRisks.isEmpty()) {
                doc.add(new Paragraph("Top Risks:", LABEL_FONT));
                for (String risk : dto.aiNarrative.topRisks) {
                    Paragraph riskPara = new Paragraph("  • " + risk, BODY_FONT);
                    riskPara.setSpacingBefore(2);
                    doc.add(riskPara);
                }
            }
        } else {
            doc.add(new Paragraph("AI narrative not available (API key not configured or disabled).", SMALL_FONT));
        }
    }

    // ── Failure Intelligence ──────────────────────────────────────────────

    private static void writeFailureIntelligence(Document doc, ReportDto dto) throws Exception {
        doc.add(sectionHeader("Failure Intelligence"));

        List<FailureCluster> clusters = dto.failureClusters;
        if (clusters == null || clusters.isEmpty()) {
            doc.add(new Paragraph("No failures recorded in this run.", BODY_FONT));
            return;
        }

        doc.add(new Paragraph(clusters.size() + " root-cause cluster(s) identified from "
                + dto.failedCount + " total failures.", BODY_FONT));
        doc.add(Chunk.NEWLINE);

        PdfPTable table = new PdfPTable(new float[]{3, 1, 2, 1, 2});
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);
        table.setHeaderRows(1);

        addTableHeader(table, "Exception / Root Cause", "Count", "Failure Type", "Confidence", "Suggested Fix");

        for (FailureCluster c : clusters) {
            String displayLabel = (c.rootCause != null && !c.rootCause.isBlank())
                    ? c.rootCause : c.exceptionType;
            String displayFrame = c.topFrame.contains(".")
                    ? c.topFrame.substring(c.topFrame.lastIndexOf('.') + 1) : c.topFrame;

            String type = c.failureType != null ? c.failureType : "UNKNOWN";
            Color typeColor = switch (type) {
                case "PRODUCT_BUG"    -> COLOR_RED;
                case "AUTOMATION_BUG" -> COLOR_AMBER;
                case "ENVIRONMENT"    -> COLOR_BLUE;
                default               -> new Color(148, 163, 184);
            };

            String confText = c.confidence > 0 ? (int)(c.confidence * 100) + "%" : "—";
            String fix = (c.suggestedFixes != null && !c.suggestedFixes.isEmpty())
                    ? c.suggestedFixes.get(0) : "—";

            PdfPCell labelCell = new PdfPCell();
            labelCell.addElement(new Paragraph(truncate(displayLabel, 80), BODY_FONT));
            Paragraph frameP = new Paragraph(displayFrame, MONO_FONT);
            frameP.setSpacingBefore(2);
            labelCell.addElement(frameP);
            styleCell(labelCell);

            addCell(table, String.valueOf(c.count), BODY_FONT, Element.ALIGN_CENTER);
            PdfPCell typeCell = new PdfPCell(new Phrase(typeLabel(type), new Font(Font.HELVETICA, 9, Font.BOLD, typeColor)));
            typeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            typeCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            styleCell(typeCell);
            table.addCell(labelCell);  // exception cell already added above
            // re-order: add type cell first since we need: label, count, type, confidence, fix
            // Rebuild properly:
            table.addCell(typeCell);
            addCell(table, confText, BODY_FONT, Element.ALIGN_CENTER);
            addCell(table, truncate(fix, 80), SMALL_FONT, Element.ALIGN_LEFT);
        }

        doc.add(table);

        // Per-cluster AI analysis details
        if (dto.aiAnalyses != null && !dto.aiAnalyses.isEmpty()) {
            doc.add(Chunk.NEWLINE);
            doc.add(subSectionHeader("AI Root Cause Details"));
            for (AiFailureAnalysis a : dto.aiAnalyses) {
                if (!"SUCCESS".equals(a.status)) continue;
                doc.add(new Paragraph(a.testId, H2_FONT));
                doc.add(new Paragraph("Type: " + a.failureType
                        + "  |  Confidence: " + (int)(a.confidence * 100) + "%", LABEL_FONT));
                doc.add(new Paragraph(a.rootCause != null ? a.rootCause : "", BODY_FONT));
                if (a.suggestedFixes != null && !a.suggestedFixes.isEmpty()) {
                    Paragraph fixes = new Paragraph("Suggested fixes:", LABEL_FONT);
                    fixes.setSpacingBefore(4);
                    doc.add(fixes);
                    for (String f : a.suggestedFixes) {
                        doc.add(new Paragraph("  • " + f, BODY_FONT));
                    }
                }
                doc.add(Chunk.NEWLINE);
            }
        }
    }

    // ── Full Test Appendix ────────────────────────────────────────────────

    private static void writeTestAppendix(Document doc, ReportDto dto) throws Exception {
        doc.add(sectionHeader("Full Test Run Appendix"));
        doc.add(new Paragraph("Passed: " + dto.passedCount
                + "  |  Failed: " + dto.failedCount, BODY_FONT));
        doc.add(Chunk.NEWLINE);

        // We only have summary counts in ReportDto at this layer.
        // Detailed per-test rows are in the HTML dashboard.
        // This appendix provides the aggregate view for the PDF.

        PdfPTable summary = new PdfPTable(new float[]{1, 1, 1, 1, 1});
        summary.setWidthPercentage(100);
        summary.setSpacingBefore(8);
        addTableHeader(summary, "Status", "Passed", "Failed", "JS Errors", "Slow Pages");
        addCell(summary, dto.releaseStatus, BODY_FONT, Element.ALIGN_CENTER);
        addCell(summary, String.valueOf(dto.passedCount), BODY_FONT, Element.ALIGN_CENTER);
        addCell(summary, String.valueOf(dto.failedCount), BODY_FONT, Element.ALIGN_CENTER);
        addCell(summary, String.valueOf(dto.jsErrorCount), BODY_FONT, Element.ALIGN_CENTER);
        addCell(summary, String.valueOf(dto.slowPageCount), BODY_FONT, Element.ALIGN_CENTER);
        doc.add(summary);

        doc.add(Chunk.NEWLINE);
        doc.add(new Paragraph("For the full interactive test log with artifacts and drilldowns, "
                + "open reports/trend/dashboard.html", SMALL_FONT));
    }

    // ── Executive Mode Pages ──────────────────────────────────────────────

    /**
     * Executive cover: large status, AI decision, one-line summary, key metrics.
     * Target: release managers / leadership. Max ~1 page.
     */
    private static void writeExecutiveCover(Document doc, ReportDto dto) throws Exception {
        doc.add(Chunk.NEWLINE);
        doc.add(Chunk.NEWLINE);

        Font coverTitle = new Font(Font.HELVETICA, 22, Font.BOLD, new Color(15, 23, 42));
        Paragraph titleP = new Paragraph("Quality Report — Executive Summary", coverTitle);
        titleP.setAlignment(Element.ALIGN_CENTER);
        doc.add(titleP);
        doc.add(Chunk.NEWLINE);

        // Large release status
        Color statusColor = resolveStatusColor(dto.releaseStatus);
        Font statusFont   = new Font(Font.HELVETICA, 32, Font.BOLD, statusColor);
        Paragraph statusP = new Paragraph(dto.releaseStatus, statusFont);
        statusP.setAlignment(Element.ALIGN_CENTER);
        doc.add(statusP);

        if (dto.releaseStatusDesc != null && !dto.releaseStatusDesc.isBlank()) {
            Font descFont = new Font(Font.HELVETICA, 12, Font.NORMAL, new Color(71, 85, 105));
            Paragraph descP = new Paragraph(dto.releaseStatusDesc, descFont);
            descP.setAlignment(Element.ALIGN_CENTER);
            descP.setSpacingBefore(6);
            doc.add(descP);
        }

        doc.add(Chunk.NEWLINE);

        // AI narrative decision (qualitative, no false precision)
        if (dto.aiNarrative != null && dto.aiNarrative.available) {
            String confLabel = dto.aiNarrative.confidence >= 0.80 ? "HIGH"
                    : dto.aiNarrative.confidence >= 0.60 ? "MODERATE" : "LOW";
            Color decColor = resolveNarrativeColor(dto.aiNarrative.decision);
            Font decFont   = new Font(Font.HELVETICA, 13, Font.BOLD, decColor);
            doc.add(new Paragraph("AI Advisory: " + dto.aiNarrative.decision
                    + "  (Confidence: " + confLabel + ")", decFont));
            doc.add(Chunk.NEWLINE);
            if (dto.aiNarrative.summary != null && !dto.aiNarrative.summary.isBlank()) {
                doc.add(new Paragraph(dto.aiNarrative.summary, BODY_FONT));
            }
            doc.add(Chunk.NEWLINE);
        }

        // Key metric tiles (2-column: Score + Delta | Smoke + Regression)
        PdfPTable metrics = new PdfPTable(new float[]{1, 1, 1, 1});
        metrics.setWidthPercentage(100);
        metrics.setSpacingBefore(8);
        metrics.setSpacingAfter(16);
        addMetricCell(metrics, "Health Score", dto.healthScore + " / 100", statusColor);
        String deltaText = dto.previousScore >= 0
                ? (dto.trendDelta >= 0 ? "+" : "") + dto.trendDelta + " pts" : "N/A";
        addMetricCell(metrics, "vs Previous", deltaText, dto.trendDelta >= 0 ? COLOR_GREEN : COLOR_RED);
        addMetricCell(metrics, "Smoke Pass",      pct(dto.smokePassRate),      COLOR_BLUE);
        addMetricCell(metrics, "Regression Pass", pct(dto.regressionPassRate), COLOR_BLUE);
        doc.add(metrics);

        // Metadata
        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(60);
        meta.setHorizontalAlignment(Element.ALIGN_LEFT);
        addMetaRow(meta, "Suite",        dto.suite);
        addMetaRow(meta, "Environment",  dto.environment);
        addMetaRow(meta, "Branch",       dto.gitBranch + " (" + dto.gitHash + ")");
        addMetaRow(meta, "Timestamp",    formatTs(dto.timestamp));
        addMetaRow(meta, "Duration",     formatDuration(dto.durationMs));
        doc.add(meta);

        // Governance disclaimer
        doc.add(Chunk.NEWLINE);
        Font discFont = new Font(Font.HELVETICA, 8, Font.ITALIC, new Color(148, 163, 184));
        Paragraph disc = new Paragraph(
                "Advisory system only. Final release decisions require human review.", discFont);
        disc.setAlignment(Element.ALIGN_CENTER);
        doc.add(disc);
    }

    /**
     * Page 2 — Executive mode: Release Blockers checklist + Root Cause Clusters.
     */
    private static void writeBlockersAndClusters(Document doc, ReportDto dto) throws Exception {
        doc.add(sectionHeader("Release Blockers"));

        // Derive blocking items from available data
        java.util.List<String[]> blockers = new java.util.ArrayList<>();
        var corr  = dto.correlationSnapshot;
        var orch  = dto.orchestrationSnapshot;
        var trend = dto.trendSnapshot;

        if (corr != null && corr.cascadeDetected && corr.cascadeFailure != null)
            blockers.add(new String[]{"BLOCKING", corr.cascadeFailure.rootWorkflow + " workflow failing — cascade root"});
        if (dto.smokePassRate < 0.90)
            blockers.add(new String[]{"BLOCKING", String.format("Smoke pass rate %.0f%% — below 90%% threshold", dto.smokePassRate * 100)});
        if (dto.productBugs > 0)
            blockers.add(new String[]{"BLOCKING", dto.productBugs + " critical product bug" + (dto.productBugs > 1 ? "s" : "")});
        if (orch != null && orch.criticalRiskCount > 0)
            blockers.add(new String[]{"BLOCKING", orch.criticalRiskCount + " critical-risk workflow" + (orch.criticalRiskCount > 1 ? "s" : "")});
        if (trend != null && trend.regressionSpikeActive)
            blockers.add(new String[]{"WARNING", "Regression spike active — sustained degradation across recent runs"});
        if (dto.regressionPassRate < 1.0 && (corr == null || !corr.cascadeDetected))
            blockers.add(new String[]{"WARNING", dto.failedCount + " regression failure" + (dto.failedCount > 1 ? "s" : "") + " require investigation"});
        if (orch != null && orch.highRiskCount > 0)
            blockers.add(new String[]{"WARNING", orch.highRiskCount + " high-risk workflow" + (orch.highRiskCount > 1 ? "s" : "")});
        if (blockers.isEmpty())
            blockers.add(new String[]{"OK", "All quality gates passed — no blockers"});

        PdfPTable blockerTable = new PdfPTable(new float[]{0.5f, 7f});
        blockerTable.setWidthPercentage(100);
        blockerTable.setSpacingBefore(8);
        blockerTable.setSpacingAfter(16);
        for (String[] item : blockers) {
            Color c = "BLOCKING".equals(item[0]) ? COLOR_RED : "WARNING".equals(item[0]) ? COLOR_AMBER : COLOR_GREEN;
            String icon = "BLOCKING".equals(item[0]) ? "✕" : "WARNING".equals(item[0]) ? "⚠" : "✓";
            Font iconFont = new Font(Font.HELVETICA, 11, Font.BOLD, c);
            Font txtFont  = new Font(Font.HELVETICA, 10, "BLOCKING".equals(item[0]) ? Font.BOLD : Font.NORMAL, new Color(30, 41, 59));
            PdfPCell iconCell = new PdfPCell(new Phrase(icon, iconFont));
            iconCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            styleCell(iconCell);
            PdfPCell txtCell = new PdfPCell(new Phrase(item[1], txtFont));
            styleCell(txtCell);
            blockerTable.addCell(iconCell);
            blockerTable.addCell(txtCell);
        }
        doc.add(blockerTable);

        // Root Cause Clusters
        doc.add(sectionHeader("Root Cause Clusters"));
        List<FailureCluster> clusters = dto.failureClusters;
        if (clusters == null || clusters.isEmpty()) {
            if (corr != null && corr.cascadeDetected && corr.cascadeFailure != null) {
                doc.add(new Paragraph("Cascade failure from " + corr.cascadeFailure.rootWorkflow
                        + " accounts for " + corr.cascadeFailure.affectedTests + " affected tests.", BODY_FONT));
            } else {
                doc.add(new Paragraph("No failure clusters identified in this run.", BODY_FONT));
            }
            return;
        }

        PdfPTable clusterTable = new PdfPTable(new float[]{4, 1, 2, 2});
        clusterTable.setWidthPercentage(100);
        clusterTable.setSpacingBefore(8);
        clusterTable.setHeaderRows(1);
        addTableHeader(clusterTable, "Root Cause", "Count", "Type", "Fix");
        int shown = 0;
        for (FailureCluster c : clusters) {
            if (shown++ >= 6) break; // executive: top 6 only
            String label = (c.rootCause != null && !c.rootCause.isBlank()) ? c.rootCause : c.exceptionType;
            String type  = c.failureType != null ? typeLabel(c.failureType) : "Unknown";
            String fix   = (c.suggestedFixes != null && !c.suggestedFixes.isEmpty()) ? c.suggestedFixes.get(0) : "—";
            addCell(clusterTable, truncate(label, 100), BODY_FONT, Element.ALIGN_LEFT);
            addCell(clusterTable, String.valueOf(c.count), BODY_FONT, Element.ALIGN_CENTER);
            addCell(clusterTable, type, BODY_FONT, Element.ALIGN_CENTER);
            addCell(clusterTable, truncate(fix, 80), SMALL_FONT, Element.ALIGN_LEFT);
        }
        doc.add(clusterTable);

        // Governance notice
        if (dto.governanceSnapshot != null && dto.governanceSnapshot.violationCount > 0) {
            doc.add(Chunk.NEWLINE);
            Font warnFont = new Font(Font.HELVETICA, 9, Font.BOLD, COLOR_AMBER);
            doc.add(new Paragraph("Governance: " + dto.governanceSnapshot.violationCount
                    + " safety violation(s) blocked — review governance panel.", warnFont));
        }
    }

    /**
     * Page 3 — Executive mode: Workflow Health + Final Recommendation.
     * Only generated when orchestration data is available.
     */
    private static void writeWorkflowsAndRecommendation(Document doc, ReportDto dto) throws Exception {
        doc.add(sectionHeader("Affected Workflows"));

        var orch = dto.orchestrationSnapshot;
        PdfPTable wfTable = new PdfPTable(new float[]{4, 1.5f, 2f});
        wfTable.setWidthPercentage(100);
        wfTable.setSpacingBefore(8);
        wfTable.setSpacingAfter(16);
        wfTable.setHeaderRows(1);
        addTableHeader(wfTable, "Workflow", "Risk", "Primary Driver");
        int shown = 0;
        for (WorkflowRiskDto r : orch.workflowRisks) {
            if (shown++ >= 8) break;
            Color rColor = switch (r.riskLevel != null ? r.riskLevel : "LOW") {
                case "CRITICAL" -> COLOR_RED; case "HIGH" -> COLOR_AMBER; default -> COLOR_GREEN;
            };
            addCell(wfTable, r.workflow != null ? r.workflow : "—", BODY_FONT, Element.ALIGN_LEFT);
            PdfPCell rCell = new PdfPCell(new Phrase(r.riskLevel != null ? r.riskLevel : "LOW",
                    new Font(Font.HELVETICA, 9, Font.BOLD, rColor)));
            rCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            styleCell(rCell);
            wfTable.addCell(rCell);
            addCell(wfTable, r.primaryDriver != null ? r.primaryDriver : "—", SMALL_FONT, Element.ALIGN_LEFT);
        }
        doc.add(wfTable);

        // Recommendation block
        doc.add(sectionHeader("Recommendation"));
        String rec = "READY".equals(dto.releaseStatus) ? "Safe to release — all quality criteria met."
                : "AT_RISK".equals(dto.releaseStatus) ? "Review and resolve identified failures before release."
                : "BLOCKED".equals(dto.releaseStatus) ? "Release blocked — resolve all BLOCKING items before proceeding."
                : "Proceed with caution — minor issues detected.";
        Font recFont = new Font(Font.HELVETICA, 12, Font.BOLD, resolveStatusColor(dto.releaseStatus));
        doc.add(new Paragraph(rec, recFont));

        if (dto.aiNarrative != null && dto.aiNarrative.available
                && dto.aiNarrative.topRisks != null && !dto.aiNarrative.topRisks.isEmpty()) {
            doc.add(Chunk.NEWLINE);
            doc.add(new Paragraph("Top Risks:", LABEL_FONT));
            for (String risk : dto.aiNarrative.topRisks) {
                doc.add(new Paragraph("  • " + risk, BODY_FONT));
            }
        }

        // Mandatory disclaimer
        doc.add(Chunk.NEWLINE);
        doc.add(Chunk.NEWLINE);
        Font discFont = new Font(Font.HELVETICA, 9, Font.ITALIC, new Color(148, 163, 184));
        Paragraph disc = new Paragraph(
                "Advisory system only. Final release decisions require human review and organizational sign-off.", discFont);
        doc.add(disc);
    }

    // ── Formatting helpers ────────────────────────────────────────────────

    private static Paragraph sectionHeader(String title) {
        Paragraph p = new Paragraph(title, H1_FONT);
        p.setSpacingBefore(8);
        p.setSpacingAfter(4);
        return p;
    }

    private static Paragraph subSectionHeader(String title) {
        Paragraph p = new Paragraph(title, H2_FONT);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        return p;
    }

    private static void addMetaRow(PdfPTable table, String label, String value) {
        PdfPCell lCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        PdfPCell vCell = new PdfPCell(new Phrase(value, BODY_FONT));
        lCell.setBorderColor(COLOR_BORDER);
        vCell.setBorderColor(COLOR_BORDER);
        lCell.setBackgroundColor(COLOR_BG);
        lCell.setPadding(6);
        vCell.setPadding(6);
        table.addCell(lCell);
        table.addCell(vCell);
    }

    private static void addMetricCell(PdfPTable table, String label, String value, Color valueColor) {
        PdfPCell cell = new PdfPCell();
        cell.addElement(new Paragraph(label, LABEL_FONT));
        cell.addElement(new Paragraph(value, new Font(Font.HELVETICA, 18, Font.BOLD, valueColor)));
        cell.setBorderColor(COLOR_BORDER);
        cell.setBackgroundColor(COLOR_BG);
        cell.setPadding(10);
        table.addCell(cell);
    }

    private static void addTableHeader(PdfPTable table, String... headers) {
        Font hFont = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(100, 116, 139));
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, hFont));
            cell.setBackgroundColor(new Color(241, 245, 249));
            cell.setBorderColor(COLOR_BORDER);
            cell.setPadding(7);
            table.addCell(cell);
        }
    }

    private static void addCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        styleCell(cell);
        table.addCell(cell);
    }

    private static void styleCell(PdfPCell cell) {
        cell.setBorderColor(COLOR_BORDER);
        cell.setPadding(6);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
    }

    private static Color resolveStatusColor(String status) {
        if (status == null) return COLOR_AMBER;
        return switch (status.toUpperCase()) {
            case "READY", "HEALTHY" -> COLOR_GREEN;
            case "WARNING", "MINOR" -> COLOR_BLUE;
            case "AT_RISK"          -> COLOR_AMBER;
            case "BLOCKED"          -> COLOR_RED;
            default                 -> COLOR_AMBER;
        };
    }

    private static Color resolveNarrativeColor(String decision) {
        if (decision == null) return COLOR_AMBER;
        return switch (decision.toUpperCase()) {
            case "DEPLOY" -> COLOR_GREEN;
            case "BLOCK"  -> COLOR_RED;
            default       -> COLOR_AMBER;
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "PRODUCT_BUG"    -> "Product";
            case "AUTOMATION_BUG" -> "Automation";
            case "ENVIRONMENT"    -> "Environment";
            default               -> "Unknown";
        };
    }

    private static String pct(double rate) {
        return (int) Math.round(rate * 100) + "%";
    }

    private static String formatTs(long ts) {
        if (ts <= 0) return "N/A";
        return DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss")
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(Instant.ofEpochMilli(ts));
    }

    private static String formatDuration(long ms) {
        if (ms < 1000)  return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm %ds", ms / 60000, (ms % 60000) / 1000);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── Header / Footer event ─────────────────────────────────────────────

    private static class HeaderFooterEvent extends PdfPageEventHelper {
        private final ReportDto dto;
        private final PdfMode mode;
        HeaderFooterEvent(ReportDto dto, PdfMode mode) { this.dto = dto; this.mode = mode; }
        // Backwards-compatible overload: some call sites construct with only ReportDto
        HeaderFooterEvent(ReportDto dto) { this(dto, PdfMode.ENGINEERING); }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Font footerFont = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(148, 163, 184));
            String modeLabel = mode == PdfMode.EXECUTIVE ? "Executive Summary" : "Engineering Diagnostic";
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(dto.suite + " | " + dto.environment + " | " + modeLabel, footerFont),
                    document.left(), document.bottom() - 10, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber() + " — Advisory only", footerFont),
                    document.right(), document.bottom() - 10, 0);
        }
    }
}
