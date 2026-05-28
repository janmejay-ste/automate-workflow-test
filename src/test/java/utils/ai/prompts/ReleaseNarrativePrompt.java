package utils.ai.prompts;

public final class ReleaseNarrativePrompt {

    private ReleaseNarrativePrompt() {}

    public static String system() {
        return "You are a release manager reviewing QA analytics before a production deployment. " +
               "Return a JSON object only with this exact schema: " +
               "{\"decision\":\"DEPLOY|HOLD|BLOCK\",\"confidence\":0.0,\"summary\":\"2-3 sentences for stakeholders\",\"topRisks\":[\"risk1\",\"risk2\"]}. " +
               "decision definitions: DEPLOY=safe to release, HOLD=needs investigation before release, BLOCK=must not release. " +
               "Return JSON only. No explanation text outside the JSON object.";
    }

    public static String user(int healthScore, String status, int failedTests,
                               int jsErrors, int slowPages,
                               double regressionPassRate, int aiAnalyzedFailures) {
        return user(healthScore, status, failedTests, jsErrors, slowPages,
                regressionPassRate, aiAnalyzedFailures,
                null, null, 0, 0, false);
    }

    public static String user(int healthScore, String status, int failedTests,
                               int jsErrors, int slowPages,
                               double regressionPassRate, int aiAnalyzedFailures,
                               String dominantTrend, String trendConfidence,
                               int flakyTests, int degradedPages,
                               boolean regressionSpikeActive) {
        StringBuilder sb = new StringBuilder();
        sb.append("Health Score: ").append(healthScore).append("/100\n");
        sb.append("Status: ").append(status).append("\n");
        sb.append("Failed Tests: ").append(failedTests).append("\n");
        sb.append("JS Product Errors: ").append(jsErrors).append("\n");
        sb.append("Slow Pages: ").append(slowPages).append("\n");
        sb.append("Regression Pass Rate: ").append(String.format("%.1f%%", regressionPassRate * 100)).append("\n");
        sb.append("AI-analyzed failure count: ").append(aiAnalyzedFailures).append("\n");

        // Trend intelligence (only append when available)
        if (dominantTrend != null && trendConfidence != null) {
            sb.append("Historical Trend: ").append(dominantTrend)
              .append(" (data confidence: ").append(trendConfidence).append(")\n");
            sb.append("Flaky Tests (oscillating): ").append(flakyTests).append("\n");
            sb.append("Performance-Degraded Pages: ").append(degradedPages).append("\n");
            sb.append("Regression Spike Active: ").append(regressionSpikeActive).append("\n");
        }
        return sb.toString().trim();
    }

    public static String user(int healthScore, String status, int failedTests,
                               int jsErrors, int slowPages,
                               double regressionPassRate, int aiAnalyzedFailures,
                               String dominantTrend, String trendConfidence,
                               int flakyTests, int degradedPages, boolean regressionSpikeActive,
                               String stabilitySignalBand, String regressionSpikeSignalBand,
                               String aiRcaSignalBand) {
        String base = user(healthScore, status, failedTests, jsErrors, slowPages,
                regressionPassRate, aiAnalyzedFailures,
                dominantTrend, trendConfidence, flakyTests, degradedPages, regressionSpikeActive);

        // Trust context tells GPT which signals to weight heavily vs treat with skepticism
        if (stabilitySignalBand != null || regressionSpikeSignalBand != null || aiRcaSignalBand != null) {
            StringBuilder sb = new StringBuilder(base);
            sb.append("\n\nSignal Trust Context (use to calibrate your confidence in the above data):\n");
            if (stabilitySignalBand != null)
                sb.append("  Stability Score trust: ").append(stabilitySignalBand).append("\n");
            if (regressionSpikeSignalBand != null)
                sb.append("  Regression Spike trust: ").append(regressionSpikeSignalBand).append("\n");
            if (aiRcaSignalBand != null)
                sb.append("  AI Root Cause Analysis trust: ").append(aiRcaSignalBand).append("\n");
            sb.append("Treat EXPERIMENTAL/UNTRUSTED signals as advisory only — do not let them drive a BLOCK decision alone.");
            return sb.toString().trim();
        }
        return base;
    }
}
