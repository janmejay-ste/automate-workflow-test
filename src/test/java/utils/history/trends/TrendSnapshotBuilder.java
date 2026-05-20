package utils.history.trends;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.HistoricalRunStore;
import utils.history.TrendComputationService;
import utils.history.calibration.FeedbackCaptureService;
import utils.history.calibration.SignalTrustScorer;
import utils.history.dto.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of computed trend intelligence.
 *
 * All downstream consumers — DashboardBuilder, PdfReportBuilder, ReleaseNarrator —
 * must consume TrendSnapshotDto from this builder and never recompute trends
 * themselves. This prevents score mismatches, inconsistent reports, and trust erosion.
 *
 * Call pattern:
 *   TrendSnapshotDto snapshot = TrendSnapshotBuilder.build();
 *   // Pass snapshot to all consumers
 */
public final class TrendSnapshotBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(TrendSnapshotBuilder.class);

    // Default window for score trend computation
    private static final int SCORE_WINDOW    = 20;
    // Default window for performance trend computation
    private static final int PERF_WINDOW     = 20;

    private TrendSnapshotBuilder() {}

    public static TrendSnapshotDto build() {
        TrendSnapshotDto snapshot = new TrendSnapshotDto();
        snapshot.computedAt = Instant.now().toString();

        try {
            // --- Run count ---
            List<?> runs = HistoricalRunStore.readAll();
            snapshot.totalRunsAnalyzed = runs.size();

            // --- Score trend ---
            snapshot.scoreTrend = TrendComputationService.computeScoreTrend(SCORE_WINDOW);

            // --- Stability trends (enriched) ---
            List<StabilityComputationEngine.Result> stabilityResults =
                    StabilityComputationEngine.computeAll();
            snapshot.stabilityTrends = stabilityResults.stream()
                    .map(r -> r.dto)
                    .collect(Collectors.toList());
            snapshot.flakyTests = snapshot.stabilityTrends.stream()
                    .filter(d -> d.isFlaky)
                    .collect(Collectors.toList());
            snapshot.flakyTestCount = snapshot.flakyTests.size();

            // --- Performance trends ---
            List<PerformanceTrendAnalyzer.Result> perfResults =
                    PerformanceTrendAnalyzer.analyzeAll();
            snapshot.performanceTrends = perfResults.stream()
                    .map(r -> r.dto)
                    .collect(Collectors.toList());
            snapshot.degradedPageCount = (int) perfResults.stream()
                    .filter(r -> r.dto.isDegraded)
                    .count();

            // --- Reliability trends ---
            List<ReliabilityTrendDto> reliabilityTrends =
                    ReliabilityTrendAnalyzer.analyzeAll();
            snapshot.reliabilityTrends = reliabilityTrends;
            snapshot.criticalLocatorCount = (int) reliabilityTrends.stream()
                    .filter(r -> "CRITICAL".equals(r.status))
                    .count();

            // --- Regression spike ---
            snapshot.regressionSpike  = RegressionSpikeDetector.detect();
            snapshot.regressionSpikeActive = snapshot.regressionSpike.spikeDetected;

            // --- Overall confidence ---
            double scoreConf = TrendConfidenceCalculator.compute(snapshot.totalRunsAnalyzed);
            double stabConf  = snapshot.stabilityTrends.isEmpty() ? 0.0
                    : snapshot.stabilityTrends.stream()
                        .mapToInt(d -> d.totalRuns).average().orElse(0);
            double stabConfScore = TrendConfidenceCalculator.compute((int) stabConf);
            snapshot.overallConfidence = TrendConfidenceCalculator.combine(scoreConf, stabConfScore);
            snapshot.overallConfidenceLabel = TrendConfidenceCalculator.label(snapshot.overallConfidence);

            // --- Dominant trend ---
            snapshot.dominantTrend = deriveDominantTrend(snapshot);

            // --- Signal trust scores (from calibration layer) ---
            // Import any aiWasCorrect overrides from developer reviews before scoring
            FeedbackCaptureService.importAiOverrides();
            snapshot.signalTrust = SignalTrustScorer.computeAll();

            LOG.debug("TrendSnapshotBuilder: built snapshot — runs={}, flaky={}, degraded={}, "
                    + "spike={}, confidence={}",
                    snapshot.totalRunsAnalyzed, snapshot.flakyTestCount,
                    snapshot.degradedPageCount, snapshot.regressionSpikeActive,
                    snapshot.overallConfidenceLabel);

        } catch (Exception e) {
            LOG.warn("TrendSnapshotBuilder failed — returning partial snapshot: {}", e.getMessage());
        }

        return snapshot;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String deriveDominantTrend(TrendSnapshotDto s) {
        if (s.scoreTrend == null) return "STABLE";

        String scoreDir = s.scoreTrend.trend; // IMPROVING | DEGRADING | STABLE

        boolean hasWarnings = s.regressionSpikeActive
                || s.flakyTestCount > 0
                || s.degradedPageCount > 0
                || s.criticalLocatorCount > 0;

        if ("IMPROVING".equals(scoreDir) && !hasWarnings) return "IMPROVING";
        if ("DEGRADING".equals(scoreDir) || s.regressionSpikeActive) return "DEGRADING";
        if (hasWarnings) return "MIXED";
        return "STABLE";
    }
}
