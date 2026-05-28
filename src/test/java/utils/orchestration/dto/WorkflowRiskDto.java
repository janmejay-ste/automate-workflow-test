package utils.orchestration.dto;

/**
 * Predicted regression risk for a single workflow.
 * Deterministic — derived from trend/stability/correlation data, not ML.
 */
public class WorkflowRiskDto {
    public String  workflow;
    public String  riskLevel;       // CRITICAL | HIGH | MEDIUM | LOW
    public double  confidence;      // 0.0–1.0
    public String  confidenceLabel; // HIGH | MEDIUM | LOW | INSUFFICIENT_DATA
    public String  primaryDriver;   // what drove this risk level (e.g. "Stability collapse")
    public String  secondaryDriver; // secondary contributing factor, may be null

    // Risk input signals
    public int     stabilityScore;
    public double  degradationPct;
    public boolean recentRegressionSpike;
    public boolean cascadeRoot;     // true if this workflow is a cascade root in current run
    public int     downstreamImpact; // how many tests downstream would be affected by failure
}
