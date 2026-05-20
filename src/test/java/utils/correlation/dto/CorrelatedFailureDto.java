package utils.correlation.dto;

import java.util.List;

/**
 * A group of failures that share a common operational origin.
 * One CorrelatedFailureDto describes one root failure and all its dependents.
 */
public class CorrelatedFailureDto {
    public String       rootTestId;               // the upstream test that failed first/causally
    public String       rootWorkflow;             // human-readable workflow name
    public String       rootFailureReason;        // first failure reason / exception type
    public List<String> downstreamFailures;       // test IDs symptomatic of the root
    public int          totalAffectedTests;       // root + downstream count
    public double       correlationConfidence;    // 0.0–1.0
    public String       probableOrigin;           // e.g. "Authentication outage", "DB connection"
    public String       severity;                 // CRITICAL | HIGH | MEDIUM | LOW
}
