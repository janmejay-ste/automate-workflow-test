package utils.correlation;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.correlation.dto.WorkflowHealthDto;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes per-workflow health scores — far more actionable than suite-wide health.
 *
 * Suite-wide: "Health Score: 62" — unclear where to focus.
 * Per-workflow: "Authentication: 22 | Dashboard: 71 | AppPairing: 84" — immediate priority.
 *
 * Formula:
 *   workflowHealth = (passedInWorkflow / totalInWorkflow) × 100
 *   Penalized by cascade risk: if this workflow is an upstream dependency of others,
 *   failures here are amplified in importance.
 *
 * Status thresholds:
 *   HEALTHY  : score >= 80
 *   DEGRADED : score >= 50
 *   CRITICAL : score <  50
 */
public final class WorkflowHealthScorer {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowHealthScorer.class);

    private WorkflowHealthScorer() {}

    /**
     * Computes per-workflow health from the current run's test results.
     *
     * @param tests        JSONArray from analytics; each item has "testId"/"test", "status",
     *                     optionally "reason"
     * @return list of workflow health scores, sorted worst-first
     */
    @SuppressWarnings("unchecked")
    public static List<WorkflowHealthDto> score(JSONArray tests) {
        if (tests == null || tests.isEmpty()) return List.of();

        WorkflowDependencyGraph graph = new WorkflowDependencyGraph();

        // Group tests by workflow
        Map<String, List<JSONObject>> byWorkflow = new LinkedHashMap<>();
        for (Object item : tests) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject t  = (JSONObject) item;
            String testId = str(t, "testId", str(t, "test", str(t, "name", "")));
            if (testId.isBlank()) continue;
            String workflow = WorkflowDependencyGraph.workflowOf(testId);
            byWorkflow.computeIfAbsent(workflow, k -> new ArrayList<>()).add(t);
        }

        List<WorkflowHealthDto> result = new ArrayList<>();

        for (Map.Entry<String, List<JSONObject>> entry : byWorkflow.entrySet()) {
            String workflow    = entry.getKey();
            List<JSONObject> wTests = entry.getValue();

            WorkflowHealthDto dto = new WorkflowHealthDto();
            dto.workflow          = workflow;
            dto.totalTests        = wTests.size();
            dto.failedTestIds     = new ArrayList<>();

            int passed = 0;
            String topFailReason = null;
            for (JSONObject t : wTests) {
                String status = str(t, "status", "UNKNOWN").toUpperCase();
                if ("PASS".equals(status) || "PASSED".equals(status)) {
                    passed++;
                } else if ("FAIL".equals(status) || "FAILED".equals(status)) {
                    String testId = str(t, "testId", str(t, "test", str(t, "name", "")));
                    dto.failedTestIds.add(testId);
                    if (topFailReason == null) {
                        topFailReason = str(t, "reason", str(t, "error", ""));
                    }
                }
            }

            dto.passedTests       = passed;
            dto.failedTests       = dto.failedTestIds.size();
            dto.passRate          = dto.totalTests > 0 ? (double) passed / dto.totalTests : 1.0;
            dto.healthScore       = (int) Math.round(dto.passRate * 100);
            dto.status            = dto.healthScore >= 80 ? "HEALTHY"
                                  : dto.healthScore >= 50 ? "DEGRADED" : "CRITICAL";
            dto.topFailureReason  = topFailReason != null ? topFailReason : "";

            // Cascade risk: how many tests downstream could be affected if this fails?
            Set<String> downstreamSet = new LinkedHashSet<>();
            for (JSONObject t : wTests) {
                String testId = str(t, "testId", str(t, "test", str(t, "name", "")));
                downstreamSet.addAll(DependencyRegistry.transitiveDownstreamOf(testId));
            }
            dto.downstreamImpact  = downstreamSet.size();
            dto.hasCascadeRisk    = dto.downstreamImpact > 0;

            result.add(dto);
        }

        // Sort: worst health first, then highest cascade risk for ties
        result.sort((a, b) -> {
            int cmp = Integer.compare(a.healthScore, b.healthScore);
            return cmp != 0 ? cmp : Integer.compare(b.downstreamImpact, a.downstreamImpact);
        });

        return result;
    }

    private static String str(JSONObject o, String k, String def) {
        Object v = o.get(k);
        return v != null ? v.toString() : def;
    }
}
