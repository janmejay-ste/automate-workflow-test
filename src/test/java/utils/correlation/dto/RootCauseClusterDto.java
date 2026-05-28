package utils.correlation.dto;

import java.util.List;

/**
 * A cluster of failures sharing the same operational origin.
 * Unlike stacktrace-based clusters, these are grouped by workflow topology.
 */
public class RootCauseClusterDto {
    public String       clusterId;            // SHA-256 prefix of (rootWorkflow + failureType)
    public String       operationalOrigin;    // e.g. "Authentication Regression"
    public String       rootWorkflow;
    public List<String> symptoms;             // distinct failure reasons (different surface, same root)
    public List<String> affectedTests;
    public int          count;
    public String       failureType;          // PRODUCT_BUG | AUTOMATION_BUG | ENVIRONMENT | UNKNOWN
    public double       confidence;
    public List<String> suggestedActions;
}
