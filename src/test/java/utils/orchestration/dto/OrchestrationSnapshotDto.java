package utils.orchestration.dto;

import java.util.List;

/**
 * Single source of computed orchestration intelligence.
 * All consumers (DashboardBuilder, PdfReportBuilder, ReleaseNarrator) read
 * from this DTO — no consumer recomputes orchestration logic.
 */
public class OrchestrationSnapshotDto {
    public String computedAt;
    public String mode;             // advisory

    // Execution plan
    public ExecutionPlanDto executionPlan;

    // Risk summary for dashboard
    public List<WorkflowRiskDto> workflowRisks;
    public int                   criticalRiskCount;
    public int                   highRiskCount;
    public String                highestRiskWorkflow;

    // Retry intelligence summary
    public int    totalRetryRecommendations;
    public int    suppressedRetries;   // retries we recommend skipping
    public int    authorizedRetries;   // retries we recommend running

    // Minimization recommendation (advisory)
    public List<String> minimizationCandidates; // stable workflows eligible for reduced sampling
    public String       minimizationSummary;

    // Critical path
    public List<String> criticalPathWorkflows;

    // Forward-looking signal for AI narrative
    public String  dominantRisk;      // AUTHENTICATION | PERFORMANCE | INFRASTRUCTURE | NONE
    public boolean executionOptimizationAvailable; // true when recommendations exist
}
