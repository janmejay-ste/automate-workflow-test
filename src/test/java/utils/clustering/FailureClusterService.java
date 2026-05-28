package utils.clustering;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.ai.models.AiFailureAnalysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic failure clustering.
 *
 * Groups failures by (exceptionType + topFrame) hash, then optionally enriches
 * each cluster with the best-matching AI analysis result. This two-step design
 * keeps cluster identity stable across runs regardless of GPT availability.
 */
public final class FailureClusterService {

    private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
            "(\\w+(?:Exception|Error|Failure|TimeoutException|AssertionError|NotFoundException|StaleElementReferenceException))");

    private FailureClusterService() {}

    public static List<FailureCluster> cluster(JSONArray testFailures,
                                                List<AiFailureAnalysis> aiResults) {
        if (testFailures == null || testFailures.isEmpty()) return List.of();

        // Step 1 — Deterministic grouping by (exceptionType, topFrame) hash
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (Object item : testFailures) {
            JSONObject f = (JSONObject) item;
            String reason = getString(f, "reason", "");
            String key = buildHash(extractExceptionType(reason), extractTopFrame(reason));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }

        // Step 2 — Build cluster objects
        Map<String, AiFailureAnalysis> aiMap = buildAiMap(aiResults);
        List<FailureCluster> clusters = new ArrayList<>();

        for (Map.Entry<String, List<JSONObject>> entry : groups.entrySet()) {
            String hash = entry.getKey();
            List<JSONObject> failures = entry.getValue();
            JSONObject first = failures.get(0);
            String reason = getString(first, "reason", "");

            String exType = extractExceptionType(reason);
            String frame  = extractTopFrame(reason);
            String url    = getString(first, "url", "");

            List<String> testIds = new ArrayList<>();
            for (JSONObject f : failures) testIds.add(getString(f, "test", "Unknown"));

            FailureCluster cluster = new FailureCluster(hash, exType, frame, url, testIds);

            // Step 3 — Enrich with best-confidence AI result in cluster
            for (String testId : testIds) {
                AiFailureAnalysis ai = aiMap.get(testId);
                if (ai == null || !"SUCCESS".equals(ai.status)) continue;
                if (ai.confidence > cluster.confidence) {
                    cluster.rootCause     = ai.rootCause;
                    cluster.failureType   = ai.failureType;
                    cluster.confidence    = ai.confidence;
                    cluster.suggestedFixes = ai.suggestedFixes;
                    if (ai.rootCause != null && !ai.rootCause.isBlank()) {
                        cluster.label = truncate(ai.rootCause, 72);
                    }
                }
            }

            clusters.add(cluster);
        }

        // Sort descending by failure count
        clusters.sort(Comparator.comparingInt((FailureCluster c) -> c.count).reversed());
        return clusters;
    }

    // ── Extraction helpers ──────────────────────────────────────────────────

    static String extractExceptionType(String reason) {
        if (reason == null || reason.isBlank()) return "UnknownException";
        Matcher m = EXCEPTION_PATTERN.matcher(reason);
        return m.find() ? m.group(1) : "UnknownException";
    }

    static String extractTopFrame(String reason) {
        if (reason == null) return "unknown";
        for (String line : reason.split("\\n")) {
            String t = line.trim();
            if (!t.startsWith("at ")) continue;
            String frame = t.substring(3).trim();
            if (isFrameworkFrame(frame)) continue;
            int paren = frame.indexOf('(');
            return paren > 0 ? frame.substring(0, paren) : frame;
        }
        return "unknown";
    }

    private static boolean isFrameworkFrame(String frame) {
        return frame.startsWith("java.")
                || frame.startsWith("jdk.")
                || frame.startsWith("sun.")
                || frame.startsWith("com.sun.")
                || frame.startsWith("org.testng.")
                || frame.startsWith("org.openqa.selenium.")
                || frame.startsWith("com.google.");
    }

    private static String buildHash(String exceptionType, String topFrame) {
        String raw = exceptionType + "|" + topFrame;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static Map<String, AiFailureAnalysis> buildAiMap(List<AiFailureAnalysis> aiResults) {
        if (aiResults == null) return Map.of();
        Map<String, AiFailureAnalysis> map = new HashMap<>();
        for (AiFailureAnalysis a : aiResults) {
            if (a.testId != null) map.put(a.testId, a);
        }
        return map;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String getString(JSONObject obj, String key, String def) {
        Object v = obj.get(key);
        return v != null ? v.toString() : def;
    }
}
