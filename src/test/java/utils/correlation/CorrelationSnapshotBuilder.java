package utils.correlation;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.correlation.dto.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of computed workflow correlation intelligence.
 *
 * ALL downstream consumers — DashboardBuilder, PdfReportBuilder, ReleaseNarrator —
 * must consume CorrelationSnapshotDto from this builder. No consumer re-runs
 * correlation analysis independently. This prevents divergence between dashboard
 * and PDF, and keeps correlation logic in one place.
 *
 * Call pattern:
 *   CorrelationSnapshotDto snapshot = CorrelationSnapshotBuilder.build(analytics);
 *   // Pass snapshot to all consumers
 */
public final class CorrelationSnapshotBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationSnapshotBuilder.class);

    private CorrelationSnapshotBuilder() {}

    /**
     * Builds a complete correlation snapshot from the current run's analytics.
     *
     * @param analytics  the JSONObject produced by AnalyticsCollector.collect()
     * @return a fully populated CorrelationSnapshotDto
     */
    @SuppressWarnings("unchecked")
    public static CorrelationSnapshotDto build(JSONObject analytics) {
        CorrelationSnapshotDto snapshot = new CorrelationSnapshotDto();
        snapshot.computedAt = Instant.now().toString();

        try {
            JSONArray testFailures = analytics != null ? (JSONArray) analytics.get("testFailures") : null;
            JSONArray tests        = analytics != null ? (JSONArray) analytics.get("tests")        : null;

            int totalFailures = testFailures != null ? testFailures.size() : 0;
            snapshot.totalFailures = totalFailures;

            boolean dependencyGraphLoaded = !DependencyRegistry.getGraph().isEmpty();
            snapshot.dependencyGraphLoaded = dependencyGraphLoaded;

            // Step 1 — Correlate failures using dependency graph
            List<CorrelatedFailureDto> correlated = FailureCorrelationEngine.correlate(testFailures);
            snapshot.correlatedFailures = correlated;

            // Step 2 — Build operational root cause clusters
            List<RootCauseClusterDto> rcClusters = RootCauseClusterBuilder.build(correlated, testFailures);
            snapshot.rootCauseClusters     = rcClusters;
            snapshot.rootCauseClusterCount = rcClusters.size();

            // Step 3 — Detect cascade failure
            CascadeFailureDto cascade = CascadeFailureDetector.detect(correlated, totalFailures);
            snapshot.cascadeFailure   = cascade;
            snapshot.cascadeDetected  = cascade.cascadeDetected;

            // Step 4 — Per-workflow health
            List<WorkflowHealthDto> workflowHealth = WorkflowHealthScorer.score(tests);
            snapshot.workflowHealth        = workflowHealth;
            snapshot.criticalWorkflowCount = (int) workflowHealth.stream()
                    .filter(w -> "CRITICAL".equals(w.status)).count();
            snapshot.degradedWorkflowCount = (int) workflowHealth.stream()
                    .filter(w -> "DEGRADED".equals(w.status)).count();

            // Step 5 — Compute explained vs unexplained failures
            if (cascade.cascadeDetected && cascade.affectedTestIds != null) {
                snapshot.explainedByCorrelation = cascade.affectedTestIds.size();
            } else {
                snapshot.explainedByCorrelation = (int) correlated.stream()
                        .filter(cf -> cf.downstreamFailures != null && !cf.downstreamFailures.isEmpty())
                        .mapToInt(cf -> cf.downstreamFailures.size())
                        .sum();
            }
            snapshot.unexplainedFailures = totalFailures - snapshot.explainedByCorrelation;
            snapshot.independentFailureCount = (int) correlated.stream()
                    .filter(cf -> cf.downstreamFailures == null || cf.downstreamFailures.isEmpty())
                    .count();

            // Step 6 — Dominant failure workflow
            snapshot.dominantFailureWorkflow = workflowHealth.isEmpty() ? null
                    : workflowHealth.get(0).workflow; // worst-first sorted

            LOG.info("CorrelationSnapshotBuilder: built — totalFailures={} cascadeDetected={} "
                    + "rootClusters={} criticalWorkflows={}",
                    totalFailures, cascade.cascadeDetected,
                    rcClusters.size(), snapshot.criticalWorkflowCount);

        } catch (Exception e) {
            LOG.warn("CorrelationSnapshotBuilder failed — returning partial snapshot: {}",
                    e.getMessage());
        }

        return snapshot;
    }

    /** Convenience overload for callers that pass null analytics (offline/test scenarios). */
    public static CorrelationSnapshotDto buildEmpty() {
        CorrelationSnapshotDto snapshot = new CorrelationSnapshotDto();
        snapshot.computedAt = Instant.now().toString();
        return snapshot;
    }
}
