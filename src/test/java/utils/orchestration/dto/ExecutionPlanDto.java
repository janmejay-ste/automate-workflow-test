package utils.orchestration.dto;

import java.util.List;

/**
 * Complete execution plan produced by AdaptiveExecutionPlanner.
 * Advisory only in Phase C.2 — humans review before applying.
 */
public class ExecutionPlanDto {
    public String  strategy;           // RISK_WEIGHTED | STABILITY_FIRST | FULL_COVERAGE | CRITICAL_PATH_ONLY
    public String  strategyReason;     // why this strategy was chosen

    public List<SuitePriorityDto>     suitePriorities;     // ordered execution queue
    public List<WorkflowRiskDto>      riskHotspots;        // predicted high-risk areas
    public List<RetryStrategyDto>     retryRecommendations;
    public List<ResourceAllocationDto> resourceAllocations;

    // Aggregated execution intelligence
    public int    criticalSuiteCount;
    public int    highRiskWorkflowCount;
    public int    suppressedRetryCount;     // retries we recommend NOT running
    public int    estimatedTimeSavingPct;   // estimated % CI time saving if plan applied

    // Mode guard
    public String mode;    // always "advisory" in Phase C.2
    public String computedAt;
}
