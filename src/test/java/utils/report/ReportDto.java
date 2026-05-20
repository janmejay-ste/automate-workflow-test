package utils.report;

import utils.TrendDataWriter;
import utils.ai.models.AiFailureAnalysis;
import utils.ai.models.AiReleaseNarrative;
import utils.clustering.FailureCluster;
import utils.correlation.dto.CorrelationSnapshotDto;
import utils.history.dto.TrendSnapshotDto;
import utils.governance.dto.GovernanceSnapshotDto;
import utils.orchestration.dto.OrchestrationSnapshotDto;

import java.util.List;

/**
 * Unified report data model consumed by both DashboardBuilder (HTML) and
 * PdfReportBuilder (PDF). Both builders read from this DTO — no file re-reading,
 * no logic duplication, no report divergence.
 */
public class ReportDto {

    // ── Release Decision ──────────────────────────────────────────────────
    public String releaseStatus      = "UNKNOWN";
    public String releaseStatusCss   = "status-degraded";
    public String releaseStatusDesc  = "";

    // ── Scores ───────────────────────────────────────────────────────────
    public int    healthScore;
    public int    previousScore      = -1;   // -1 means no history available
    public int    trendDelta;                 // signed: positive = improvement
    public double smokePassRate;
    public double regressionPassRate;

    // ── Bug Counts ────────────────────────────────────────────────────────
    public int productBugs;
    public int automationBugs;
    public int environmentIssues;
    public int thirdPartyIssues;

    // ── Run Metadata ──────────────────────────────────────────────────────
    public String environment   = "QA";
    public String suite         = "REGRESSION";
    public String gitBranch     = "main";
    public String gitHash       = "n/a";
    public long   durationMs;
    public long   timestamp;
    public double penaltyPoints;

    // ── AI ────────────────────────────────────────────────────────────────
    public AiReleaseNarrative aiNarrative;
    public List<AiFailureAnalysis> aiAnalyses;
    public int aiAnalyzedCount;

    // ── Failure Intelligence ──────────────────────────────────────────────
    public List<FailureCluster> failureClusters;

    // ── Workflow Correlation Intelligence ────────────────────────────────
    public CorrelationSnapshotDto correlationSnapshot;

    // ── Historical Trend Intelligence ─────────────────────────────────────
    public TrendSnapshotDto trendSnapshot;             // single source of truth for all trend signals

    // ── Orchestration Intelligence ────────────────────────────────────────
    public OrchestrationSnapshotDto orchestrationSnapshot; // advisory execution plan

    // ── Governance Intelligence ───────────────────────────────────────────
    public GovernanceSnapshotDto governanceSnapshot;       // explainability, safety, audit

    // ── Trend ─────────────────────────────────────────────────────────────
    public List<TrendDataWriter.RunData> trendHistory;
    public int    trendHealthy, trendMinor, trendDegraded, trendAtRisk, trendCritical;
    public double avgScore;

    // ── Counts ────────────────────────────────────────────────────────────
    public int passedCount;
    public int failedCount;
    public int jsErrorCount;
    public int slowPageCount;
    public int fallbackCount;
    public int warningCount;
}
