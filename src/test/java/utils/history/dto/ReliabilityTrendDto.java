package utils.history.dto;

import java.util.List;

public class ReliabilityTrendDto {
    public String feature;
    public int    reliabilityScore;       // 0–100
    public String status;                 // HEALTHY | FRAGILE | CRITICAL
    public double primarySuccessRate;
    public double fallbackRate;
    public double criticalFailureRate;

    // Trend intelligence
    public String trend;                  // IMPROVING | DEGRADING | STABLE
    public double fallbackTrend;          // positive = fallbacks increasing
    public boolean primarySelectorDegrading;
    public boolean failureConcentrated;   // >50% failures in one feature
    public String maintenanceRisk;        // LOW | MEDIUM | HIGH | CRITICAL

    // Confidence
    public double confidence;             // 0.0–1.0
    public String confidenceLabel;        // INSUFFICIENT_DATA | LOW | MEDIUM | HIGH
}
