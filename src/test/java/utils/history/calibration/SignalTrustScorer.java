package utils.history.calibration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.calibration.SignalValidationReport.ValidationSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-signal trust scores — meta-confidence intelligence.
 *
 * Not all signals deserve equal trust. A stability score backed by 500 runs
 * and high developer usefulness feedback is far more trustworthy than an
 * AI RCA with few feedback confirmations.
 *
 * Trust score formula (0–100, deterministic):
 *   base = usefulnessRate × 60          (most important: did it lead to action?)
 *   + (1 − falsePositiveRate) × 25      (second: how accurate is it?)
 *   + (1 − ignoredRate) × 15            (third: are engineers reading it?)
 *
 * Sample size weight: score × confidence(sampleSize)
 * (low sample counts are penalized — a trust score from 3 samples isn't trustworthy)
 *
 * Trust bands:
 *   TRUSTED      : score >= 80
 *   MODERATE     : score >= 55
 *   EXPERIMENTAL : score >= 30
 *   UNTRUSTED    : score <  30
 */
public final class SignalTrustScorer {
    private static final Logger LOG = LoggerFactory.getLogger(SignalTrustScorer.class);

    public static final class TrustScore {
        public final String signalType;
        public final int    score;              // 0–100
        public final String band;              // TRUSTED | MODERATE | EXPERIMENTAL | UNTRUSTED
        public final double usefulnessRate;
        public final double falsePositiveRate;
        public final double ignoredRate;
        public final int    sampleSize;
        public final String confidenceLabel;

        private TrustScore(String signalType, int score, String band,
                            double usefulnessRate, double fpRate, double ignoredRate,
                            int sampleSize, String confidenceLabel) {
            this.signalType      = signalType;
            this.score           = score;
            this.band            = band;
            this.usefulnessRate  = usefulnessRate;
            this.falsePositiveRate = fpRate;
            this.ignoredRate     = ignoredRate;
            this.sampleSize      = sampleSize;
            this.confidenceLabel = confidenceLabel;
        }
    }

    public static final class TrustSummary {
        public final Map<String, TrustScore> scores;   // signalType → TrustScore

        private TrustSummary(Map<String, TrustScore> scores) {
            this.scores = scores;
        }

        public TrustScore get(String signalType) {
            return scores.get(signalType);
        }

        public int scoreFor(String signalType) {
            TrustScore ts = scores.get(signalType);
            return ts != null ? ts.score : -1;
        }

        public String bandFor(String signalType) {
            TrustScore ts = scores.get(signalType);
            return ts != null ? ts.band : "INSUFFICIENT_DATA";
        }
    }

    // Canonical signal type names used across the platform
    public static final String SIGNAL_STABILITY_SCORE    = "StabilityScore";
    public static final String SIGNAL_PERFORMANCE_TREND  = "PerformanceTrend";
    public static final String SIGNAL_LOCATOR_RELIABILITY= "LocatorReliability";
    public static final String SIGNAL_REGRESSION_SPIKE   = "RegressionSpike";
    public static final String SIGNAL_AI_RCA             = "AiFailureAnalysis";
    public static final String SIGNAL_RELEASE_NARRATIVE  = "ReleaseNarrative";

    private SignalTrustScorer() {}

    /**
     * Computes trust scores for all known signal types from persisted validation data.
     */
    public static TrustSummary computeAll() {
        List<ValidationSummary> summaries = SignalValidationReport.computeAll();
        Map<String, TrustScore> result = new LinkedHashMap<>();

        for (ValidationSummary v : summaries) {
            TrustScore ts = computeFromSummary(v);
            result.put(v.signalType, ts);
        }

        // Ensure all canonical signals are represented (even with zero data)
        for (String canonical : List.of(SIGNAL_STABILITY_SCORE, SIGNAL_PERFORMANCE_TREND,
                SIGNAL_LOCATOR_RELIABILITY, SIGNAL_REGRESSION_SPIKE,
                SIGNAL_AI_RCA, SIGNAL_RELEASE_NARRATIVE)) {
            result.computeIfAbsent(canonical, st -> defaultTrustScore(st));
        }

        LOG.debug("SignalTrustScorer: computed trust for {} signal types", result.size());
        return new TrustSummary(result);
    }

    /**
     * Computes a trust score for a single signal type.
     */
    public static TrustScore compute(String signalType) {
        ValidationSummary v = SignalValidationReport.compute(signalType);
        return computeFromSummary(v);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static TrustScore computeFromSummary(ValidationSummary v) {
        if (v.triggered < 3) {
            return defaultTrustScore(v.signalType);
        }

        // Raw score formula
        double raw = (v.usefulnessRate * 60.0)
                + ((1.0 - v.falsePositiveRate) * 25.0)
                + ((1.0 - v.ignoredRate)       * 15.0);

        // Weight by sample confidence (penalizes low sample sizes)
        double sampleConf = utils.history.trends.TrendConfidenceCalculator.compute(v.triggered);
        double weighted   = raw * sampleConf;

        int score = (int) Math.round(Math.max(0, Math.min(100, weighted)));
        String band = classify(score);
        String confidenceLabel = utils.history.trends.TrendConfidenceCalculator.label(sampleConf);

        return new TrustScore(v.signalType, score, band,
                v.usefulnessRate, v.falsePositiveRate, v.ignoredRate,
                v.triggered, confidenceLabel);
    }

    private static TrustScore defaultTrustScore(String signalType) {
        return new TrustScore(signalType, 0, "INSUFFICIENT_DATA",
                0.0, 0.0, 0.0, 0, "INSUFFICIENT_DATA");
    }

    private static String classify(int score) {
        if (score >= 80) return "TRUSTED";
        if (score >= 55) return "MODERATE";
        if (score >= 30) return "EXPERIMENTAL";
        return "UNTRUSTED";
    }
}
