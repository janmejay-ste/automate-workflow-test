package utils.history.dto;

import utils.history.calibration.SignalTrustScorer;

import java.util.List;

/**
 * Single source of computed trend intelligence.
 *
 * ALL downstream consumers (DashboardBuilder, PdfReportBuilder, ReleaseNarrator)
 * must read trend data exclusively from this DTO. No consumer recomputes trends.
 */
public class TrendSnapshotDto {
    public String computedAt;                          // ISO timestamp of snapshot
    public int    totalRunsAnalyzed;

    // Score trend (computed by TrendComputationService)
    public HistoricalTrendDto scoreTrend;

    // Stability trends — all tests, sorted most-unstable first
    public List<StabilityTrendDto> stabilityTrends;

    // Flaky tests extracted from stability trends
    public List<StabilityTrendDto> flakyTests;

    // Performance trends — all pages
    public List<PerformanceTrendDto> performanceTrends;

    // Locator reliability trends — all features
    public List<ReliabilityTrendDto> reliabilityTrends;

    // Regression spike (if any detected)
    public RegressionSpikeDto regressionSpike;

    // Overall snapshot confidence
    public double overallConfidence;                   // 0.0–1.0
    public String overallConfidenceLabel;              // INSUFFICIENT_DATA | LOW | MEDIUM | HIGH

    // Signal trust scores (from calibration layer)
    public SignalTrustScorer.TrustSummary signalTrust;

    // Aggregated signals for AI prompts
    public int    flakyTestCount;
    public int    degradedPageCount;
    public int    criticalLocatorCount;
    public boolean regressionSpikeActive;
    public String dominantTrend;                       // IMPROVING | DEGRADING | STABLE | MIXED
}
