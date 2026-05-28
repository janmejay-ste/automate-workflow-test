package utils.correlation;

import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.correlation.dto.CorrelatedFailureDto;
import utils.correlation.dto.RootCauseClusterDto;

import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Evolves failure clustering beyond stacktrace matching to operational origin grouping.
 *
 * Old approach: same stacktrace → same cluster (surface-level, misses real relationships)
 * New approach: same workflow origin → same cluster (even with different exception types)
 *
 * Example:
 *   LoginTest: TimeoutException
 *   AuthenticatedTest: UnauthorizedException
 *   DashboardTest: NoSuchElementException
 *
 *   Old: 3 separate clusters
 *   New: 1 cluster — "Authentication Regression" — 3 different symptoms, 1 root cause
 *
 * Input: list of CorrelatedFailureDto from FailureCorrelationEngine
 */
public final class RootCauseClusterBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(RootCauseClusterBuilder.class);

    private RootCauseClusterBuilder() {}

    /**
     * Builds operational root cause clusters from pre-correlated failure groups.
     *
     * @param correlatedFailures output from FailureCorrelationEngine.correlate()
     * @param testFailures       raw test failures JSONArray for extracting reasons
     * @return clusters sorted by total affected tests descending
     */
    public static List<RootCauseClusterDto> build(List<CorrelatedFailureDto> correlatedFailures,
                                                   JSONArray testFailures) {
        if (correlatedFailures == null || correlatedFailures.isEmpty()) return List.of();

        // Build reason lookup: testId → reason
        Map<String, String> reasons = new LinkedHashMap<>();
        if (testFailures != null) {
            for (Object item : testFailures) {
                if (item instanceof org.json.simple.JSONObject) {
                    org.json.simple.JSONObject t = (org.json.simple.JSONObject) item;
                    String testId = str(t, "testId", str(t, "test", ""));
                    String reason = str(t, "reason",  str(t, "error", ""));
                    if (!testId.isBlank()) reasons.put(testId, reason);
                }
            }
        }

        // Group correlated failures by rootWorkflow
        Map<String, List<CorrelatedFailureDto>> byWorkflow = new LinkedHashMap<>();
        for (CorrelatedFailureDto cf : correlatedFailures) {
            String workflow = cf.rootWorkflow != null ? cf.rootWorkflow : "Unknown";
            byWorkflow.computeIfAbsent(workflow, k -> new ArrayList<>()).add(cf);
        }

        List<RootCauseClusterDto> clusters = new ArrayList<>();
        for (Map.Entry<String, List<CorrelatedFailureDto>> entry : byWorkflow.entrySet()) {
            String workflow = entry.getKey();
            List<CorrelatedFailureDto> group = entry.getValue();

            RootCauseClusterDto dto = new RootCauseClusterDto();
            dto.rootWorkflow = workflow;
            dto.clusterId    = computeClusterId(workflow, group);

            // Collect all affected tests
            Set<String> allAffected = new LinkedHashSet<>();
            for (CorrelatedFailureDto cf : group) {
                allAffected.add(cf.rootTestId);
                allAffected.addAll(cf.downstreamFailures);
            }
            dto.affectedTests = new ArrayList<>(allAffected);
            dto.count         = dto.affectedTests.size();

            // Collect distinct symptom descriptions
            Set<String> symptomSet = new LinkedHashSet<>();
            for (String testId : allAffected) {
                String reason = reasons.getOrDefault(testId, "");
                if (!reason.isBlank()) {
                    String symptom = extractSymptom(reason);
                    if (!symptom.isBlank()) symptomSet.add(symptom);
                }
            }
            dto.symptoms = new ArrayList<>(symptomSet);

            // Derive operational origin name
            dto.operationalOrigin = buildOriginName(workflow, dto.symptoms, dto.count);

            // Aggregate confidence (average across correlated groups)
            dto.confidence = group.stream()
                    .mapToDouble(cf -> cf.correlationConfidence)
                    .average().orElse(0.0);
            dto.confidence = Math.round(dto.confidence * 100.0) / 100.0;

            // Failure type — prefer most specific type from group
            dto.failureType = deriveFailureType(group);

            // Suggested actions
            dto.suggestedActions = buildSuggestedActions(dto);

            clusters.add(dto);
        }

        clusters.sort((a, b) -> Integer.compare(b.count, a.count));
        LOG.debug("RootCauseClusterBuilder: {} operational clusters from {} correlated groups",
                clusters.size(), correlatedFailures.size());
        return clusters;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String computeClusterId(String workflow, List<CorrelatedFailureDto> group) {
        try {
            String input = workflow + "|" + group.size();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 12);
        } catch (Exception e) {
            return workflow.replaceAll("[^a-zA-Z0-9]", "_").substring(0, Math.min(12, workflow.length()));
        }
    }

    private static String extractSymptom(String reason) {
        // Extract exception type or first meaningful error token
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\w+(?:Exception|Error|Failure|Timeout|Unauthorized|NotFound))")
                .matcher(reason);
        return m.find() ? m.group(1) : reason.substring(0, Math.min(60, reason.length())).trim();
    }

    private static String buildOriginName(String workflow, List<String> symptoms, int count) {
        if (count == 1) return workflow + " failure";
        if (symptoms.size() > 1) return workflow + " regression (" + symptoms.size() + " symptom types)";
        if (!symptoms.isEmpty()) return workflow + " " + symptoms.get(0) + " cascade";
        return workflow + " regression";
    }

    private static String deriveFailureType(List<CorrelatedFailureDto> group) {
        // Use the failure type of the root — most representative
        for (CorrelatedFailureDto cf : group) {
            if (cf.correlationConfidence >= 0.5) {
                // High-confidence root: guess from reason
                String reason = cf.rootFailureReason != null ? cf.rootFailureReason.toUpperCase() : "";
                if (reason.contains("ASSERT") || reason.contains("EXPECTED")) return "PRODUCT_BUG";
                if (reason.contains("NOSUCHELEMENT") || reason.contains("STALE")) return "AUTOMATION_BUG";
                if (reason.contains("TIMEOUT") && reason.contains("CONNECTION")) return "ENVIRONMENT";
            }
        }
        return "UNKNOWN";
    }

    private static List<String> buildSuggestedActions(RootCauseClusterDto dto) {
        List<String> actions = new ArrayList<>();
        actions.add("Investigate " + dto.rootWorkflow + " workflow for root regression");
        if (dto.count >= 3) {
            actions.add("Check if " + dto.rootWorkflow + " is an upstream dependency causing cascade");
        }
        if (dto.symptoms.size() > 1) {
            actions.add("Multiple symptom types suggest infrastructure/auth issue rather than test code");
        }
        return actions;
    }

    private static String str(org.json.simple.JSONObject o, String k, String def) {
        Object v = o.get(k); return v != null ? v.toString() : def;
    }
}
