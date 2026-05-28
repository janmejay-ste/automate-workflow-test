package utils.history.dto;

public class LocatorTrendDto {
    public String feature;
    public double primarySuccessRate;
    public double fallbackRate;
    public double criticalFailureRate;
    public int    reliabilityScore;    // 0–100 deterministic formula
    public String status;              // HEALTHY | FRAGILE | CRITICAL
}
