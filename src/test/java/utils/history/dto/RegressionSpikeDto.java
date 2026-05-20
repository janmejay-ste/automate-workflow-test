package utils.history.dto;

import java.util.List;

public class RegressionSpikeDto {
    public boolean spikeDetected;
    public String  spikeType;            // FAILURE_RATE_SPIKE | SCORE_DROP | FLAKY_SURGE
    public double  currentFailureRate;
    public double  baselineFailureRate;  // rolling average before spike
    public double  spikeMultiplier;      // currentFailureRate / baselineFailureRate
    public String  severity;             // LOW | MEDIUM | HIGH | CRITICAL
    public String  detectedAt;           // ISO timestamp
    public List<String> affectedTests;
    public String  recommendation;

    // Confidence
    public double  confidence;
    public String  confidenceLabel;
}
