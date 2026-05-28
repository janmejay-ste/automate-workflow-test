package utils.ai.storage;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.ai.models.AiFailureAnalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class AiResultWriter {
    private static final Logger LOG = LoggerFactory.getLogger(AiResultWriter.class);
    private static final String AI_DIR = "reports/ai/failures";

    private AiResultWriter() {}

    public static void write(String testId, AiFailureAnalysis analysis) {
        if (analysis == null) return;
        try {
            Files.createDirectories(Paths.get(AI_DIR));
            Path path = Paths.get(AI_DIR, sanitize(testId) + ".json");
            Files.writeString(path, toJson(analysis).toJSONString());
        } catch (Exception e) {
            LOG.warn("Failed to write AI result for {}: {}", testId, e.getMessage());
        }
    }

    public static void writeFailure(String testId, String errorType, String message) {
        try {
            Files.createDirectories(Paths.get(AI_DIR));
            Path path = Paths.get(AI_DIR, sanitize(testId) + ".json");
            AiFailureAnalysis failed = AiFailureAnalysis.failed(testId, errorType + ": " + message);
            Files.writeString(path, toJson(failed).toJSONString());
        } catch (Exception e) {
            LOG.warn("Failed to write AI failure marker for {}: {}", testId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static JSONObject toJson(AiFailureAnalysis a) {
        JSONObject json = new JSONObject();
        json.put("testId", a.testId);
        json.put("status", a.status);
        json.put("failureType", a.failureType);
        json.put("confidence", a.confidence);
        json.put("belowThreshold", a.belowThreshold);
        json.put("advisoryOnly", a.advisoryOnly);
        json.put("rootCause", a.rootCause != null ? a.rootCause : "");
        json.put("riskLevel", a.riskLevel != null ? a.riskLevel : "MEDIUM");
        json.put("timestamp", a.timestamp);

        JSONArray fixes = new JSONArray();
        if (a.suggestedFixes != null) fixes.addAll(a.suggestedFixes);
        json.put("suggestedFixes", fixes);

        if (a.errorReason != null) json.put("errorReason", a.errorReason);

        // Developer override — preserves feedback loop field
        JSONObject override = new JSONObject();
        override.put("aiWasCorrect", null);
        json.put("developerOverride", override);

        return json;
    }

    @SuppressWarnings("unchecked")
    public static AiFailureAnalysis fromJson(JSONObject json) {
        String testId = (String) json.getOrDefault("testId", "unknown");
        String status = (String) json.getOrDefault("status", "SUCCESS");

        if ("FAILED".equals(status)) {
            return AiFailureAnalysis.failed(testId, (String) json.getOrDefault("errorReason", ""));
        }
        if ("SKIPPED".equals(status)) {
            return AiFailureAnalysis.skipped(testId, (String) json.getOrDefault("errorReason", ""));
        }

        List<String> fixes = new ArrayList<>();
        JSONArray fixesArr = (JSONArray) json.get("suggestedFixes");
        if (fixesArr != null) {
            for (Object f : fixesArr) fixes.add(f.toString());
        }

        Object conf = json.get("confidence");
        double confidence = conf instanceof Number ? ((Number) conf).doubleValue() : 0.0;

        return AiFailureAnalysis.builder(testId)
                .failureType((String) json.getOrDefault("failureType", "UNKNOWN"))
                .confidence(confidence)
                .rootCause((String) json.getOrDefault("rootCause", ""))
                .riskLevel((String) json.getOrDefault("riskLevel", "MEDIUM"))
                .suggestedFixes(fixes)
                .status("SUCCESS")
                .build();
    }

    private static String sanitize(String testId) {
        return testId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
