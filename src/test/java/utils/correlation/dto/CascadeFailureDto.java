package utils.correlation.dto;

import java.util.List;

/**
 * Describes a cascade failure event — one root issue causing many downstream failures.
 */
public class CascadeFailureDto {
    public boolean      cascadeDetected;
    public String       rootCause;           // human-readable description of root issue
    public String       rootTestId;          // canonical upstream test that triggered cascade
    public String       rootWorkflow;        // workflow owning the root
    public int          affectedTests;       // total tests in cascade (root + downstream)
    public List<String> affectedTestIds;
    public String       severity;            // CRITICAL | HIGH | MEDIUM | LOW
    public String       recommendation;

    // Confidence in the cascade classification
    public double       confidence;
    public String       confidenceLabel;
}
