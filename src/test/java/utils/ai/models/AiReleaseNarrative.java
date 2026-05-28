package utils.ai.models;

import java.util.List;

public class AiReleaseNarrative {

    public final String decision;   // DEPLOY, HOLD, BLOCK
    public final double confidence;
    public final String summary;
    public final List<String> topRisks;
    public final boolean available;
    public final long timestamp;

    private AiReleaseNarrative(String decision, double confidence, String summary,
                                List<String> topRisks, boolean available) {
        this.decision = decision;
        this.confidence = confidence;
        this.summary = summary;
        this.topRisks = topRisks != null ? topRisks : List.of();
        this.available = available;
        this.timestamp = System.currentTimeMillis();
    }

    public static AiReleaseNarrative of(String decision, double confidence,
                                        String summary, List<String> topRisks) {
        return new AiReleaseNarrative(decision, confidence, summary, topRisks, true);
    }

    public static AiReleaseNarrative unavailable() {
        return new AiReleaseNarrative("N/A", 0, "AI narrative not available", List.of(), false);
    }
}
