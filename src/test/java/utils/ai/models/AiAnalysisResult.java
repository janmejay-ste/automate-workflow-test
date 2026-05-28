package utils.ai.models;

public class AiAnalysisResult {

    public enum Type { FAILURE_ANALYSIS, RELEASE_NARRATIVE }

    public final Type type;
    public final boolean success;
    public final String skippedReason;

    private AiAnalysisResult(Type type, boolean success, String skippedReason) {
        this.type = type;
        this.success = success;
        this.skippedReason = skippedReason;
    }

    public static AiAnalysisResult skipped(String reason) {
        return new AiAnalysisResult(null, false, reason);
    }

    public static AiAnalysisResult success(Type type) {
        return new AiAnalysisResult(type, true, null);
    }
}
