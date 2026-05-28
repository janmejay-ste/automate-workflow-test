package utils.history.trends;

import utils.history.TrendComputationService;
import utils.history.dto.StabilityTrendDto;

import java.util.List;

/**
 * Produces enriched stability classifications for all tests.
 *
 * Classification is continuous (scored), not binary. A score of 55 is
 * meaningfully different from 35 — both are UNSTABLE but one is far closer
 * to recovery.
 *
 * Thresholds:
 *   HIGHLY_STABLE : score >= 90
 *   MOSTLY_STABLE : score >= 70
 *   UNSTABLE      : score >= 40
 *   CRITICAL      : score <  40
 */
public final class StabilityComputationEngine {

    public static final class Result {
        public final StabilityTrendDto dto;
        public final String classification;      // HIGHLY_STABLE | MOSTLY_STABLE | UNSTABLE | CRITICAL
        public final OscillationDetector.Result oscillation;

        private Result(StabilityTrendDto dto, String classification,
                       OscillationDetector.Result oscillation) {
            this.dto            = dto;
            this.classification = classification;
            this.oscillation    = oscillation;
        }
    }

    private StabilityComputationEngine() {}

    public static Result compute(StabilityTrendDto dto) {
        OscillationDetector.Result osc = OscillationDetector.detect(dto.recentHistory);

        // Re-use oscillationRate from OscillationDetector for consistency
        dto.oscillationRate = osc.oscillationRate;

        // Recompute isFlaky using canonical thresholds
        dto.isFlaky = dto.stabilityScore < 70 && osc.oscillationRate > 0.20;

        String classification = classify(dto.stabilityScore);
        return new Result(dto, classification, osc);
    }

    /** Computes enriched results for all tests from persistent store. */
    public static List<Result> computeAll() {
        List<StabilityTrendDto> raw = TrendComputationService.computeStabilityTrends();
        return raw.stream().map(StabilityComputationEngine::compute).toList();
    }

    private static String classify(int score) {
        if (score >= 90) return "HIGHLY_STABLE";
        if (score >= 70) return "MOSTLY_STABLE";
        if (score >= 40) return "UNSTABLE";
        return "CRITICAL";
    }
}
