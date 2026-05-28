package utils.ai.models;

import java.util.List;

public class AiFailureAnalysis {

    public final String testId;
    public final String failureType;
    public final double confidence;
    public final boolean belowThreshold;
    public final boolean advisoryOnly;
    public final String rootCause;
    public final List<String> suggestedFixes;
    public final String riskLevel;
    public final String status; // SUCCESS, FAILED, SKIPPED
    public final String errorReason;
    public final long timestamp;
    public final DeveloperOverride developerOverride;

    private AiFailureAnalysis(Builder b) {
        this.testId = b.testId;
        this.failureType = b.failureType;
        this.confidence = b.confidence;
        this.belowThreshold = b.confidence < utils.ai.client.AiConfig.getMinConfidence();
        this.advisoryOnly = this.belowThreshold;
        this.rootCause = b.rootCause;
        this.suggestedFixes = b.suggestedFixes != null ? b.suggestedFixes : List.of();
        this.riskLevel = b.riskLevel;
        this.status = b.status;
        this.errorReason = b.errorReason;
        this.timestamp = System.currentTimeMillis();
        this.developerOverride = new DeveloperOverride();
    }

    public static Builder builder(String testId) {
        return new Builder(testId);
    }

    public static AiFailureAnalysis failed(String testId, String reason) {
        return new Builder(testId)
                .status("FAILED")
                .errorReason(reason)
                .confidence(0)
                .build();
    }

    public static AiFailureAnalysis skipped(String testId, String reason) {
        return new Builder(testId)
                .status("SKIPPED")
                .errorReason(reason)
                .confidence(0)
                .build();
    }

    public static class DeveloperOverride {
        public Boolean aiWasCorrect = null; // null = not yet reviewed
    }

    public static class Builder {
        private final String testId;
        private String failureType = "UNKNOWN";
        private double confidence = 0;
        private String rootCause = "";
        private List<String> suggestedFixes = List.of();
        private String riskLevel = "MEDIUM";
        private String status = "SUCCESS";
        private String errorReason = null;

        public Builder(String testId) {
            this.testId = testId;
        }

        public Builder failureType(String v)        { this.failureType = v;    return this; }
        public Builder confidence(double v)          { this.confidence = v;     return this; }
        public Builder rootCause(String v)           { this.rootCause = v;      return this; }
        public Builder suggestedFixes(List<String> v){ this.suggestedFixes = v; return this; }
        public Builder riskLevel(String v)           { this.riskLevel = v;      return this; }
        public Builder status(String v)              { this.status = v;         return this; }
        public Builder errorReason(String v)         { this.errorReason = v;    return this; }
        public AiFailureAnalysis build()             { return new AiFailureAnalysis(this); }
    }
}
