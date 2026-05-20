package utils.history.trends;

import utils.history.LocatorReliabilityHistory;
import utils.history.dto.LocatorTrendDto;
import utils.history.dto.ReliabilityTrendDto;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Locator reliability trend analysis — predictive maintenance intelligence.
 *
 * Identifies features where locators are degrading before they cause test failures.
 * Key signals: rising fallback rate, dropping primary success rate, failure concentration.
 *
 * Maintenance risk thresholds:
 *   LOW      : reliabilityScore >= 85
 *   MEDIUM   : reliabilityScore >= 70
 *   HIGH     : reliabilityScore >= 50
 *   CRITICAL : reliabilityScore <  50
 */
public final class ReliabilityTrendAnalyzer {

    private ReliabilityTrendAnalyzer() {}

    public static ReliabilityTrendDto analyze(LocatorTrendDto locator) {
        ReliabilityTrendDto dto = new ReliabilityTrendDto();
        dto.feature              = locator.feature;
        dto.reliabilityScore     = locator.reliabilityScore;
        dto.status               = locator.status;
        dto.primarySuccessRate   = locator.primarySuccessRate;
        dto.fallbackRate         = locator.fallbackRate;
        dto.criticalFailureRate  = locator.criticalFailureRate;

        // Primary selector degrading: success rate below 80%
        dto.primarySelectorDegrading = locator.primarySuccessRate < 0.80;

        // Failure concentration: >50% critical failure rate indicates concentrated breakage
        dto.failureConcentrated = locator.criticalFailureRate > 0.50;

        // Fallback trend: high fallback rate (>30%) means selectors are unreliable
        dto.fallbackTrend = locator.fallbackRate;

        // Maintenance risk classification
        dto.maintenanceRisk = classifyMaintenanceRisk(locator.reliabilityScore);

        // Trend direction from status
        dto.trend = deriveTrend(locator.reliabilityScore);

        // Confidence — single-point snapshot has inherent uncertainty
        dto.confidence      = computeConfidence(locator);
        dto.confidenceLabel = TrendConfidenceCalculator.label(dto.confidence);

        return dto;
    }

    public static List<ReliabilityTrendDto> analyzeAll() {
        List<LocatorTrendDto> locators = LocatorReliabilityHistory.loadAllAsDtos();
        return locators.stream()
                .map(ReliabilityTrendAnalyzer::analyze)
                // Sort: highest maintenance risk first
                .sorted((a, b) -> Integer.compare(a.reliabilityScore, b.reliabilityScore))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String classifyMaintenanceRisk(int score) {
        if (score >= 85) return "LOW";
        if (score >= 70) return "MEDIUM";
        if (score >= 50) return "HIGH";
        return "CRITICAL";
    }

    private static String deriveTrend(int score) {
        // Without historical snapshots for locators, derive from current health bucket
        // A future enhancement could track score deltas over time
        if (score >= 85) return "STABLE";
        if (score >= 60) return "DEGRADING";
        return "DEGRADING";
    }

    private static double computeConfidence(LocatorTrendDto locator) {
        // Confidence reflects how reliable the locator data signal is.
        // A feature with fallbackRate=0 and criticalFailureRate=0 has a trustworthy
        // primary success rate; high fallback usage implies ambiguity.
        double ambiguity = (locator.fallbackRate + locator.criticalFailureRate) / 2.0;
        return Math.max(0.30, Math.min(1.0, 1.0 - ambiguity));
    }
}
