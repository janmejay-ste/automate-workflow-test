package utils.correlation.dto;

import java.util.List;

/**
 * Per-workflow health score and failure summary.
 * Much more actionable than suite-wide health for debugging prioritization.
 */
public class WorkflowHealthDto {
    public String       workflow;             // e.g. "Authentication", "Dashboard", "AppPairing"
    public int          healthScore;          // 0–100
    public String       status;              // HEALTHY | DEGRADED | CRITICAL
    public int          totalTests;
    public int          passedTests;
    public int          failedTests;
    public double       passRate;
    public boolean      hasCascadeRisk;       // true if this workflow is upstream of others
    public List<String> failedTestIds;
    public String       topFailureReason;     // most common failure reason in this workflow
    public int          downstreamImpact;     // number of downstream tests at risk if this fails
}
