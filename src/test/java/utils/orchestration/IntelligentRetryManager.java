package utils.orchestration;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.correlation.dto.CorrelationSnapshotDto;
import utils.orchestration.dto.RetryStrategyDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Signal-aware retry manager. Replaces blanket retries with failure-class-specific
 * strategy, suppressing wasteful retries and authorizing useful ones.
 *
 * Failure classification → retry decision:
 *   FLAKY_SELECTOR    → retry (up to 2x) — likely environment/timing issue
 *   PRODUCT_BUG       → no retry — assertion fails consistently, retrying wastes time
 *   NETWORK_TIMEOUT   → retry once — transient infrastructure issue
 *   BACKEND_ERROR     → retry once — server error may be transient
 *   AUTH_CASCADE      → suppress downstream retries — root is upstream, not this test
 *   AUTOMATION_BUG    → retry once — locator may be stale
 *   UNKNOWN           → retry once — insufficient signal to suppress
 *
 * Cascade suppression: if a test is a known downstream of a cascade root,
 * retrying it is pure waste — the root must be fixed first.
 *
 * ALL output is advisory. This manager never executes retries itself.
 */
public final class IntelligentRetryManager {

    private IntelligentRetryManager() {}

    /**
     * Produces retry recommendations for all test failures in the current run.
     *
     * @param testFailures        JSONArray from analytics (each: testId, reason, failureType)
     * @param correlationSnapshot from CorrelationSnapshotBuilder (for cascade detection)
     * @return per-failure retry recommendations
     */
    public static List<RetryStrategyDto> recommend(JSONArray testFailures,
                                                    CorrelationSnapshotDto correlationSnapshot) {
        if (testFailures == null || testFailures.isEmpty()) return List.of();

        // Build cascade downstream set — these tests should NOT be retried
        Set<String> cascadeDownstream = buildCascadeDownstream(correlationSnapshot);

        List<RetryStrategyDto> result = new ArrayList<>();
        for (Object item : testFailures) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject t = (JSONObject) item;
            String testId      = str(t, "testId", str(t, "test", ""));
            String reason      = str(t, "reason",  str(t, "error", ""));
            String failureType = str(t, "failureType", "UNKNOWN"); // from analytics classification

            if (testId.isBlank()) continue;

            RetryStrategyDto dto = new RetryStrategyDto();
            dto.testId = testId;

            // Classify failure
            dto.failureClass       = classifyFailure(reason, failureType);
            dto.suppressIfCascade  = cascadeDownstream.contains(testId);

            if (dto.suppressIfCascade) {
                dto.shouldRetry = false;
                dto.maxRetries  = 0;
                dto.retryMode   = "SUPPRESS";
                dto.retryReason = "Downstream cascade of root failure — fix root first, retry is wasteful";
            } else {
                dto.maxRetries  = OrchestrationConfig.getRetryMax(dto.failureClass);
                dto.shouldRetry = dto.maxRetries > 0;
                dto.retryMode   = buildRetryMode(dto.failureClass, dto.maxRetries);
                dto.retryReason = buildRetryReason(dto.failureClass, dto.maxRetries);
            }

            result.add(dto);
        }
        return result;
    }

    /** Counts how many retries this manager recommends suppressing. */
    public static int countSuppressed(List<RetryStrategyDto> strategies) {
        return (int) strategies.stream().filter(s -> !s.shouldRetry).count();
    }

    /** Counts how many retries this manager authorizes. */
    public static int countAuthorized(List<RetryStrategyDto> strategies) {
        return (int) strategies.stream().filter(s -> s.shouldRetry).count();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String classifyFailure(String reason, String failureType) {
        if (reason == null) reason = "";
        String r = reason.toUpperCase();

        // Use analytics-provided classification when reliable
        if ("PRODUCT_BUG".equals(failureType))   return "PRODUCT_BUG";
        if ("ENVIRONMENT".equals(failureType))    return "NETWORK_TIMEOUT";

        // Signal-based classification from error message
        if (r.contains("NOSUCHELEMENT") || r.contains("STALE") || r.contains("TIMEOUT")
                && r.contains("ELEMENT"))         return "FLAKY_SELECTOR";
        if (r.contains("ASSERT") || r.contains("EXPECTED") || r.contains("EXPECTED_CONDITION"))
                                                  return "PRODUCT_BUG";
        if (r.contains("CONNECTION") || r.contains("SOCKET") || r.contains("READ TIMED OUT"))
                                                  return "NETWORK_TIMEOUT";
        if (r.contains("500") || r.contains("503") || r.contains("INTERNAL SERVER"))
                                                  return "BACKEND_ERROR";
        if (r.contains("401") || r.contains("403") || r.contains("UNAUTHORIZED")
                || r.contains("FORBIDDEN"))       return "AUTH_CASCADE";
        if ("AUTOMATION_BUG".equals(failureType)) return "AUTOMATION_BUG";

        return "UNKNOWN";
    }

    private static String buildRetryMode(String failureClass, int maxRetries) {
        if (maxRetries == 0) return "SUPPRESS";
        return switch (failureClass) {
            case "FLAKY_SELECTOR"  -> "BACKOFF";     // wait before retry — timing-sensitive
            case "NETWORK_TIMEOUT" -> "ISOLATE";     // retry in isolated thread to avoid interference
            case "BACKEND_ERROR"   -> "BACKOFF";
            default                -> "IMMEDIATE";
        };
    }

    private static String buildRetryReason(String failureClass, int maxRetries) {
        if (maxRetries == 0) {
            return switch (failureClass) {
                case "PRODUCT_BUG" -> "Product assertion failure — retrying cannot change the outcome";
                default            -> "No retry authorized";
            };
        }
        return switch (failureClass) {
            case "FLAKY_SELECTOR"  -> "Selector instability — retry with backoff (max " + maxRetries + "x)";
            case "NETWORK_TIMEOUT" -> "Transient network issue — isolated retry (max " + maxRetries + "x)";
            case "BACKEND_ERROR"   -> "Transient server error — backoff retry (max " + maxRetries + "x)";
            case "AUTOMATION_BUG"  -> "Stale locator possible — one retry authorized";
            default                -> "Unknown failure — one retry authorized to gather more signal";
        };
    }

    private static Set<String> buildCascadeDownstream(CorrelationSnapshotDto cs) {
        if (cs == null || !cs.cascadeDetected || cs.cascadeFailure == null
                || cs.cascadeFailure.affectedTestIds == null) {
            return Set.of();
        }
        // Downstream = cascade affected minus the root itself
        String root = cs.cascadeFailure.rootTestId;
        return cs.cascadeFailure.affectedTestIds.stream()
                .filter(id -> !id.equals(root))
                .collect(Collectors.toSet());
    }

    private static String str(JSONObject o, String k, String def) {
        Object v = o.get(k); return v != null ? v.toString() : def;
    }
}
