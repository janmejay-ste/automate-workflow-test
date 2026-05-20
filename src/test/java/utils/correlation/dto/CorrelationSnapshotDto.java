package utils.correlation.dto;

import java.util.List;

/**
 * Single source of computed workflow correlation intelligence.
 *
 * All downstream consumers — DashboardBuilder, PdfReportBuilder, ReleaseNarrator —
 * consume this DTO. No consumer recomputes correlation logic.
 */
public class CorrelationSnapshotDto {
    public String computedAt;

    // Correlated failure groups
    public List<CorrelatedFailureDto> correlatedFailures;
    public int                        independentFailureCount;  // failures with no correlation

    // Root cause clusters (operational origin grouping)
    public List<RootCauseClusterDto>  rootCauseClusters;
    public int                        rootCauseClusterCount;

    // Cascade detection
    public CascadeFailureDto cascadeFailure;
    public boolean           cascadeDetected;

    // Per-workflow health
    public List<WorkflowHealthDto> workflowHealth;
    public int                     criticalWorkflowCount;
    public int                     degradedWorkflowCount;

    // Aggregated signals for AI prompts and dashboard
    public int    totalFailures;
    public int    explainedByCorrelation;   // failures that are downstream symptoms
    public int    unexplainedFailures;      // failures needing separate investigation
    public String dominantFailureWorkflow;  // workflow with most failures
    public boolean dependencyGraphLoaded;  // false when config/workflow-dependencies.json has no real entries
}
