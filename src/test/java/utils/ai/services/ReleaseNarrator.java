package utils.ai.services;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.ai.client.AiClient;
import utils.ai.client.AiConfig;
import utils.ai.models.AiFailureAnalysis;
import utils.ai.models.AiReleaseNarrative;
import utils.ai.prompts.ReleaseNarrativePrompt;
import utils.correlation.dto.CorrelationSnapshotDto;
import utils.history.calibration.SignalTrustScorer;
import utils.history.dto.TrendSnapshotDto;
import utils.governance.GovernanceReportGenerator;
import utils.governance.dto.GovernanceSnapshotDto;
import utils.orchestration.dto.OrchestrationSnapshotDto;

import java.util.ArrayList;
import java.util.List;

public final class ReleaseNarrator {
    private static final Logger LOG = LoggerFactory.getLogger(ReleaseNarrator.class);

    private ReleaseNarrator() {}

    public static AiReleaseNarrative generate(JSONObject analytics,
                                               List<AiFailureAnalysis> aiResults) {
        return generate(analytics, aiResults, null, null);
    }

    public static AiReleaseNarrative generate(JSONObject analytics,
                                               List<AiFailureAnalysis> aiResults,
                                               TrendSnapshotDto trendSnapshot) {
        return generate(analytics, aiResults, trendSnapshot, null);
    }

    public static AiReleaseNarrative generate(JSONObject analytics,
                                               List<AiFailureAnalysis> aiResults,
                                               TrendSnapshotDto trendSnapshot,
                                               CorrelationSnapshotDto correlationSnapshot,
                                               OrchestrationSnapshotDto orchestrationSnapshot) {
        return generate(analytics, aiResults, trendSnapshot, correlationSnapshot,
                orchestrationSnapshot, null);
    }

    public static AiReleaseNarrative generate(JSONObject analytics,
                                               List<AiFailureAnalysis> aiResults,
                                               TrendSnapshotDto trendSnapshot,
                                               CorrelationSnapshotDto correlationSnapshot,
                                               OrchestrationSnapshotDto orchestrationSnapshot,
                                               GovernanceSnapshotDto governanceSnapshot) {
        String extra = buildOrchestrationContext(orchestrationSnapshot)
                + GovernanceReportGenerator.buildNarrativeContext(governanceSnapshot);
        return generateInternal(analytics, aiResults, trendSnapshot, correlationSnapshot, extra);
    }

    public static AiReleaseNarrative generate(JSONObject analytics,
                                               List<AiFailureAnalysis> aiResults,
                                               TrendSnapshotDto trendSnapshot,
                                               CorrelationSnapshotDto correlationSnapshot) {
        return generateInternal(analytics, aiResults, trendSnapshot, correlationSnapshot, "");
    }

    private static AiReleaseNarrative generateInternal(JSONObject analytics,
                                               List<AiFailureAnalysis> aiResults,
                                               TrendSnapshotDto trendSnapshot,
                                               CorrelationSnapshotDto correlationSnapshot,
                                               String extraContext) {
        if (!AiConfig.isReleaseNarrativeEnabled()) {
            return AiReleaseNarrative.unavailable();
        }
        if (analytics == null) {
            return AiReleaseNarrative.unavailable();
        }

        try {
            int healthScore        = getInt(analytics, "overallScore", 0);
            String status          = getString(analytics, "status", "UNKNOWN");
            int failedTests        = safeArraySize(analytics, "testFailures");
            int jsErrorCount       = jsProductErrorCount(analytics);
            int slowPages          = safeArraySize(analytics, "slowPages");
            double regressionPass  = getDouble(analytics, "regressionPassRate", 1.0);
            int aiAnalyzed         = aiResults != null
                    ? (int) aiResults.stream().filter(a -> "SUCCESS".equals(a.status)).count()
                    : 0;

            // Extract trend signals when snapshot is available
            String dominantTrend    = trendSnapshot != null ? trendSnapshot.dominantTrend   : null;
            String trendConfidence  = trendSnapshot != null ? trendSnapshot.overallConfidenceLabel : null;
            int flakyTests          = trendSnapshot != null ? trendSnapshot.flakyTestCount   : 0;
            int degradedPages       = trendSnapshot != null ? trendSnapshot.degradedPageCount : 0;
            boolean spikeActive     = trendSnapshot != null && trendSnapshot.regressionSpikeActive;

            // Extract signal trust bands for AI context
            String stabilityBand     = null;
            String spikeBand         = null;
            String aiRcaBand         = null;
            if (trendSnapshot != null && trendSnapshot.signalTrust != null) {
                stabilityBand = trendSnapshot.signalTrust.bandFor(SignalTrustScorer.SIGNAL_STABILITY_SCORE);
                spikeBand     = trendSnapshot.signalTrust.bandFor(SignalTrustScorer.SIGNAL_REGRESSION_SPIKE);
                aiRcaBand     = trendSnapshot.signalTrust.bandFor(SignalTrustScorer.SIGNAL_AI_RCA);
            }

            // Add correlation context to prompt when available
            StringBuilder correlationContext = new StringBuilder();
            if (correlationSnapshot != null) {
                if (correlationSnapshot.cascadeDetected && correlationSnapshot.cascadeFailure != null) {
                    correlationContext.append("\nCascade Detected: YES — ")
                            .append(correlationSnapshot.cascadeFailure.rootWorkflow)
                            .append(" causing ").append(correlationSnapshot.cascadeFailure.affectedTests - 1)
                            .append(" downstream failures. Fix root first.");
                }
                if (correlationSnapshot.criticalWorkflowCount > 0) {
                    correlationContext.append("\nCritical Workflows: ")
                            .append(correlationSnapshot.criticalWorkflowCount);
                }
                if (correlationSnapshot.unexplainedFailures > 0) {
                    correlationContext.append("\nUnexplained Independent Failures: ")
                            .append(correlationSnapshot.unexplainedFailures);
                }
            }

            String systemPrompt = ReleaseNarrativePrompt.system();
            String userContent  = ReleaseNarrativePrompt.user(
                    healthScore, status, failedTests, jsErrorCount,
                    slowPages, regressionPass, aiAnalyzed,
                    dominantTrend, trendConfidence, flakyTests, degradedPages, spikeActive,
                    stabilityBand, spikeBand, aiRcaBand)
                    + correlationContext.toString()
                    + (extraContext != null ? extraContext : "");

            JSONObject result = AiClient.chat(AiConfig.getStandardModel(), systemPrompt, userContent);
            if (result == null) {
                return AiReleaseNarrative.unavailable();
            }

            AiReleaseNarrative narrative = parseNarrative(result);
            LOG.info("AI release narrative: decision={} confidence={}",
                    narrative.decision, narrative.confidence);

            return narrative;

        } catch (Exception e) {
            LOG.warn("ReleaseNarrator failed: {}", e.getMessage());
            return AiReleaseNarrative.unavailable();
        }
    }

    private static String buildOrchestrationContext(OrchestrationSnapshotDto orch) {
        if (orch == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== Orchestration Intelligence (advisory) ===");
        sb.append("\nExecution Strategy: ").append(orch.executionPlan != null ? orch.executionPlan.strategy : "N/A");
        if (orch.criticalRiskCount > 0)
            sb.append("\nCritical Risk Workflows: ").append(orch.criticalRiskCount);
        if (orch.highRiskCount > 0)
            sb.append("\nHigh Risk Workflows: ").append(orch.highRiskCount);
        if (orch.highestRiskWorkflow != null)
            sb.append("\nHighest Risk: ").append(orch.highestRiskWorkflow);
        if (orch.suppressedRetries > 0)
            sb.append("\nSuppressed Retries: ").append(orch.suppressedRetries)
              .append(" (cascade downstream — not worth retrying)");
        if (orch.dominantRisk != null && !"NONE".equals(orch.dominantRisk))
            sb.append("\nDominant Risk Category: ").append(orch.dominantRisk);
        if (orch.executionOptimizationAvailable)
            sb.append("\nExecution optimization recommendations available — review advisory plan.");
        return sb.toString();
    }

    private static AiReleaseNarrative parseNarrative(JSONObject json) {
        String decision   = getString(json, "decision", "HOLD");
        double confidence = getDouble(json, "confidence", 0.0);
        String summary    = getString(json, "summary", "");

        List<String> risks = new ArrayList<>();
        JSONArray risksArr = (JSONArray) json.get("topRisks");
        if (risksArr != null) {
            for (Object r : risksArr) risks.add(r.toString());
        }

        return AiReleaseNarrative.of(decision, confidence, summary, risks);
    }

    private static int safeArraySize(JSONObject obj, String key) {
        Object v = obj.get(key);
        if (v instanceof JSONArray) return ((JSONArray) v).size();
        return 0;
    }

    private static int jsProductErrorCount(JSONObject analytics) {
        Object jsObj = analytics.get("jsErrors");
        if (!(jsObj instanceof JSONObject)) return 0;
        Object prodArr = ((JSONObject) jsObj).get("product");
        if (prodArr instanceof JSONArray) return ((JSONArray) prodArr).size();
        return 0;
    }

    private static String getString(JSONObject obj, String key, String def) {
        Object v = obj.get(key);
        return v != null ? v.toString() : def;
    }

    private static int getInt(JSONObject obj, String key, int def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private static double getDouble(JSONObject obj, String key, double def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }
}
