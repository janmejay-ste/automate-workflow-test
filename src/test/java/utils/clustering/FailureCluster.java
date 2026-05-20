package utils.clustering;

import java.util.List;

public class FailureCluster {

    public final String clusterId;       // SHA-256 prefix of (exceptionType|topFrame)
    public final String exceptionType;   // e.g., NoSuchElementException
    public final String topFrame;        // first non-framework stack frame
    public final String url;             // URL where failure occurred (best guess)
    public final List<String> affectedTests;
    public final int count;

    // Enriched by AI (via FailureClusterService)
    public String label;
    public String rootCause;
    public String failureType;           // PRODUCT_BUG / AUTOMATION_BUG / ENVIRONMENT / UNKNOWN
    public double confidence;
    public List<String> suggestedFixes;

    public FailureCluster(String clusterId, String exceptionType, String topFrame,
                          String url, List<String> affectedTests) {
        this.clusterId = clusterId;
        this.exceptionType = exceptionType;
        this.topFrame = topFrame;
        this.url = url != null ? url : "";
        this.affectedTests = List.copyOf(affectedTests);
        this.count = affectedTests.size();
        this.label = exceptionType;
        this.failureType = "UNKNOWN";
        this.confidence = 0.0;
        this.suggestedFixes = List.of();
    }
}
