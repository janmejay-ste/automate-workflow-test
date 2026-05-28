package utils.history.dto;

import java.util.List;

public class StabilityTrendDto {
    public String testId;
    public int    stabilityScore;   // 0–100 deterministic formula
    public double passRate;
    public double failureRate;
    public double oscillationRate;  // PASS→FAIL transition density in recentHistory
    public int    totalRuns;
    public boolean isFlaky;         // stabilityScore < 70 && oscillationRate > 0.2
    public String lastUpdated;
    public List<String> recentHistory;  // last N outcomes as "PASS"/"FAIL" strings
}
