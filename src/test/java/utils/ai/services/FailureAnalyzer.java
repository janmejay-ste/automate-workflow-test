package utils.ai.services;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.ai.client.AiClient;
import utils.ai.client.AiConfig;
import utils.ai.models.AiFailureAnalysis;
import utils.ai.preprocess.DomReducer;
import utils.ai.preprocess.LogReducer;
import utils.ai.preprocess.StacktraceReducer;
import utils.ai.prompts.FailureAnalysisPrompt;

import java.util.ArrayList;
import java.util.List;

public final class FailureAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(FailureAnalyzer.class);

    private FailureAnalyzer() {}

    public static AiFailureAnalysis analyze(String testId, Throwable throwable,
                                             String url, String consoleLog) {
        return analyze(testId, throwable, url, consoleLog, null);
    }

    public static AiFailureAnalysis analyze(String testId, Throwable throwable,
                                             String url, String consoleLog, String dom) {
        if (!AiConfig.isFailureAnalysisEnabled()) {
            return AiFailureAnalysis.skipped(testId, "AI failure analysis disabled");
        }
        if (throwable == null) {
            return AiFailureAnalysis.skipped(testId, "No throwable provided");
        }

        try {
            // Preprocess inputs
            String reducedStack = StacktraceReducer.reduce(throwable);
            String reducedLog   = LogReducer.reduce(consoleLog);
            String reducedDom   = DomReducer.reduce(dom);

            // Cache lookup
            String cacheKey = AiCacheService.buildKey(
                    reducedStack, url, throwable.getClass().getSimpleName());
            AiFailureAnalysis cached = AiCacheService.load(cacheKey);
            if (cached != null) {
                LOG.debug("AI cache hit for {}", testId);
                return cached;
            }

            // Build prompts and call GPT
            String systemPrompt = FailureAnalysisPrompt.system();
            String userContent  = FailureAnalysisPrompt.user(
                    testId, reducedStack, url, reducedLog, reducedDom);

            JSONObject result = AiClient.chat(AiConfig.getDeepModel(), systemPrompt, userContent);
            if (result == null) {
                return AiFailureAnalysis.failed(testId, "OpenAI returned null response");
            }

            AiFailureAnalysis analysis = parseAnalysis(testId, result);

            // Store in cache for future runs
            AiCacheService.store(cacheKey, analysis);

            LOG.info("AI analysis for {}: type={} confidence={} cause={}",
                    testId, analysis.failureType, analysis.confidence, analysis.rootCause);

            return analysis;

        } catch (Exception e) {
            LOG.warn("FailureAnalyzer failed for {}: {}", testId, e.getMessage());
            return AiFailureAnalysis.failed(testId, e.getMessage());
        }
    }

    private static AiFailureAnalysis parseAnalysis(String testId, JSONObject json) {
        String failureType = getString(json, "failureType", "UNKNOWN");
        double confidence  = getDouble(json, "confidence", 0.0);
        String rootCause   = getString(json, "rootCause", "");
        String riskLevel   = getString(json, "riskLevel", "MEDIUM");

        List<String> fixes = new ArrayList<>();
        JSONArray fixesArr = (JSONArray) json.get("suggestedFixes");
        if (fixesArr != null) {
            for (Object fix : fixesArr) fixes.add(fix.toString());
        }

        return AiFailureAnalysis.builder(testId)
                .failureType(failureType)
                .confidence(confidence)
                .rootCause(rootCause)
                .suggestedFixes(fixes)
                .riskLevel(riskLevel)
                .status("SUCCESS")
                .build();
    }

    private static String getString(JSONObject obj, String key, String def) {
        Object v = obj.get(key);
        return v != null ? v.toString() : def;
    }

    private static double getDouble(JSONObject obj, String key, double def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }
}
