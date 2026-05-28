package utils.correlation;

import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.correlation.dto.CascadeFailureDto;
import utils.correlation.dto.CorrelatedFailureDto;
import utils.history.trends.TrendConfidenceCalculator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects cascade failures — one upstream outage causing many downstream test failures.
 *
 * Without cascade detection: 40 failed tests looks catastrophic.
 * With cascade detection: "1 authentication outage detected" — much more actionable.
 *
 * Cascade criteria:
 *   - A single root failure explains >= CASCADE_MIN_RATIO of all failures
 *   - OR a single root failure has >= CASCADE_MIN_ABSOLUTE downstream failures
 *   - The correlated failure group has confidence >= 0.5
 *
 * The most severe cascade is reported. If multiple cascades exist, the largest is primary.
 */
public final class CascadeFailureDetector {
    private static final Logger LOG = LoggerFactory.getLogger(CascadeFailureDetector.class);

    private static final double CASCADE_MIN_RATIO    = 0.40;  // root covers 40%+ of all failures
    private static final int    CASCADE_MIN_ABSOLUTE = 3;     // OR root has 3+ downstream

    private CascadeFailureDetector() {}

    /**
     * Detects the primary cascade failure, if any.
     *
     * @param correlatedFailures  output from FailureCorrelationEngine
     * @param totalFailureCount   total number of failed tests in this run
     */
    public static CascadeFailureDto detect(List<CorrelatedFailureDto> correlatedFailures,
                                            int totalFailureCount) {
        CascadeFailureDto dto = new CascadeFailureDto();
        dto.cascadeDetected = false;

        if (correlatedFailures == null || correlatedFailures.isEmpty()
                || totalFailureCount == 0) {
            return dto;
        }

        // Find the most impactful correlated group
        CorrelatedFailureDto best = null;
        for (CorrelatedFailureDto cf : correlatedFailures) {
            if (cf.downstreamFailures == null || cf.downstreamFailures.isEmpty()) continue;
            if (best == null || cf.totalAffectedTests > best.totalAffectedTests) {
                best = cf;
            }
        }

        if (best == null) return dto;

        double coverageRatio = (double) best.totalAffectedTests / totalFailureCount;
        boolean meetsCascadeCriteria = (coverageRatio >= CASCADE_MIN_RATIO && best.correlationConfidence >= 0.50)
                || (best.downstreamFailures.size() >= CASCADE_MIN_ABSOLUTE && best.correlationConfidence >= 0.40);

        if (!meetsCascadeCriteria) {
            dto.recommendation = String.format(
                    "No cascade detected. Largest correlated group: %s (%d tests, %.0f%% coverage, confidence %.0f%%).",
                    best.rootWorkflow, best.totalAffectedTests, coverageRatio * 100,
                    best.correlationConfidence * 100);
            return dto;
        }

        dto.cascadeDetected    = true;
        dto.rootTestId         = best.rootTestId;
        dto.rootWorkflow       = best.rootWorkflow;
        dto.rootCause          = best.probableOrigin;
        dto.affectedTests      = best.totalAffectedTests;
        dto.affectedTestIds    = new ArrayList<>();
        dto.affectedTestIds.add(best.rootTestId);
        dto.affectedTestIds.addAll(best.downstreamFailures);
        dto.severity           = classifySeverity(best.totalAffectedTests, totalFailureCount, coverageRatio);
        dto.confidence         = Math.round(best.correlationConfidence * 100.0) / 100.0;
        dto.confidenceLabel    = TrendConfidenceCalculator.label(dto.confidence);
        dto.recommendation     = buildRecommendation(dto);

        LOG.info("CascadeFailureDetector: cascade detected — root={} affectedTests={} severity={}",
                dto.rootTestId, dto.affectedTests, dto.severity);
        return dto;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String classifySeverity(int affected, int total, double ratio) {
        if (ratio >= 0.70 || affected >= 10) return "CRITICAL";
        if (ratio >= 0.50 || affected >= 5)  return "HIGH";
        if (ratio >= 0.30 || affected >= 3)  return "MEDIUM";
        return "LOW";
    }

    private static String buildRecommendation(CascadeFailureDto dto) {
        return String.format(
                "ROOT CAUSE DETECTED: %s is causing %d downstream test failures. "
                + "Fix the root (%s) first — downstream failures will likely self-resolve. "
                + "Do not file separate bugs for each downstream failure.",
                dto.rootWorkflow, dto.affectedTests - 1, dto.rootTestId);
    }
}
