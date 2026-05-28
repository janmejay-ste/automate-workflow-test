package utils.correlation;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.correlation.dto.CorrelatedFailureDto;
import utils.history.trends.TrendConfidenceCalculator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Heart of workflow intelligence — groups failures by causal relationship.
 *
 * Algorithm:
 *   1. Build a failure set from the current run's test failures
 *   2. Walk the dependency graph: for each failed test that is a root
 *      (no upstream dependency that also failed), collect its transitive
 *      downstream failures from the current failure set
 *   3. Assign each downstream failure to the shallowest root that explains it
 *   4. Remaining failures with no upstream root = independent failures
 *
 * Confidence model:
 *   - High confidence when: root fails AND all direct downstream also fail
 *   - Medium confidence when: root fails AND some (not all) downstream fail
 *   - Low confidence when: no declared dependency exists — pure co-occurrence
 */
public final class FailureCorrelationEngine {
    private static final Logger LOG = LoggerFactory.getLogger(FailureCorrelationEngine.class);

    private FailureCorrelationEngine() {}

    /**
     * Correlates a set of failed test IDs using the declared dependency graph.
     *
     * @param testFailures  JSONArray from analytics; each item has "testId" and "reason"
     * @return list of correlated failure groups, sorted by most-affected first
     */
    public static List<CorrelatedFailureDto> correlate(JSONArray testFailures) {
        if (testFailures == null || testFailures.isEmpty()) return List.of();

        WorkflowDependencyGraph graph = new WorkflowDependencyGraph();
        boolean dependencyGraphLoaded = !graph.allNodes().isEmpty();

        // Extract current failure set
        Map<String, String> failedTestReasons = new LinkedHashMap<>(); // testId → reason
        for (Object item : testFailures) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject t = (JSONObject) item;
            String testId = str(t, "testId", str(t, "test", ""));
            String reason = str(t, "reason",  str(t, "error", ""));
            if (!testId.isBlank()) failedTestReasons.put(testId, reason);
        }

        if (failedTestReasons.isEmpty()) return List.of();

        Set<String> failedTests   = failedTestReasons.keySet();
        Set<String> assignedTests = new LinkedHashSet<>(); // claimed as downstream of some root
        List<CorrelatedFailureDto> groups = new ArrayList<>();

        if (!dependencyGraphLoaded) {
            // No graph — every failure is independent but we still return one group per failure
            for (Map.Entry<String, String> entry : failedTestReasons.entrySet()) {
                CorrelatedFailureDto dto = new CorrelatedFailureDto();
                dto.rootTestId            = entry.getKey();
                dto.rootWorkflow          = WorkflowDependencyGraph.workflowOf(entry.getKey());
                dto.rootFailureReason     = entry.getValue();
                dto.downstreamFailures    = List.of();
                dto.totalAffectedTests    = 1;
                dto.correlationConfidence = 0.0;
                dto.probableOrigin        = dto.rootWorkflow + " failure";
                dto.severity              = "MEDIUM";
                groups.add(dto);
            }
            return groups;
        }

        // Walk graph: identify roots in the current failure set
        for (String testId : failedTests) {
            List<String> deps = DependencyRegistry.dependenciesOf(testId);
            boolean upstreamAlsoFailed = deps.stream().anyMatch(failedTests::contains);
            if (!upstreamAlsoFailed) {
                // This is a root failure in the current run
                Set<String> transitiveDownstream = DependencyRegistry.transitiveDownstreamOf(testId);
                List<String> affectedDownstream = transitiveDownstream.stream()
                        .filter(failedTests::contains)
                        .filter(d -> !d.equals(testId))
                        .collect(Collectors.toList());

                CorrelatedFailureDto dto = new CorrelatedFailureDto();
                dto.rootTestId            = testId;
                dto.rootWorkflow          = WorkflowDependencyGraph.workflowOf(testId);
                dto.rootFailureReason     = failedTestReasons.getOrDefault(testId, "");
                dto.downstreamFailures    = affectedDownstream;
                dto.totalAffectedTests    = 1 + affectedDownstream.size();
                dto.correlationConfidence = computeCorrelationConfidence(
                        testId, affectedDownstream, transitiveDownstream);
                dto.probableOrigin        = buildProbableOrigin(dto);
                dto.severity              = classifySeverity(dto.totalAffectedTests,
                        dto.correlationConfidence);

                groups.add(dto);
                assignedTests.add(testId);
                assignedTests.addAll(affectedDownstream);
            }
        }

        // Any failures not assigned to a root group are truly independent
        Set<String> independent = new LinkedHashSet<>(failedTests);
        independent.removeAll(assignedTests);
        for (String testId : independent) {
            CorrelatedFailureDto dto = new CorrelatedFailureDto();
            dto.rootTestId            = testId;
            dto.rootWorkflow          = WorkflowDependencyGraph.workflowOf(testId);
            dto.rootFailureReason     = failedTestReasons.getOrDefault(testId, "");
            dto.downstreamFailures    = List.of();
            dto.totalAffectedTests    = 1;
            dto.correlationConfidence = 0.0;
            dto.probableOrigin        = dto.rootWorkflow + " isolated failure";
            dto.severity              = "LOW";
            groups.add(dto);
        }

        groups.sort((a, b) -> Integer.compare(b.totalAffectedTests, a.totalAffectedTests));
        LOG.debug("FailureCorrelationEngine: {} failure groups from {} failures ({} independent)",
                groups.size(), failedTests.size(), independent.size());
        return groups;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static double computeCorrelationConfidence(String root,
                                                        List<String> actualDownstream,
                                                        Set<String> declaredDownstream) {
        if (declaredDownstream.isEmpty()) return 0.0;
        if (actualDownstream.isEmpty()) return 0.30; // root failed but no downstream — still valid
        // Confidence: fraction of declared downstream that also failed
        double coverage = (double) actualDownstream.size() / declaredDownstream.size();
        // Weighted: high coverage = high confidence
        return TrendConfidenceCalculator.compute(actualDownstream.size() + 1) * 0.4
                + coverage * 0.6;
    }

    private static String buildProbableOrigin(CorrelatedFailureDto dto) {
        if (dto.downstreamFailures.isEmpty()) {
            return dto.rootWorkflow + " failure (no downstream cascade)";
        }
        return dto.rootWorkflow + " regression causing "
                + dto.downstreamFailures.size() + " downstream failure(s)";
    }

    private static String classifySeverity(int affectedCount, double confidence) {
        if (affectedCount >= 5 && confidence >= 0.7) return "CRITICAL";
        if (affectedCount >= 3 || confidence >= 0.7) return "HIGH";
        if (affectedCount >= 2) return "MEDIUM";
        return "LOW";
    }

    private static String str(JSONObject o, String k, String def) {
        Object v = o.get(k);
        return v != null ? v.toString() : def;
    }
}
