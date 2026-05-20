package utils.governance.dto;

import java.util.List;

/**
 * Human-readable explanation of WHY a specific orchestration decision was made.
 * Produced by ExplainabilityEngine.
 *
 * Purpose: replace opaque labels ("RISK_WEIGHTED") with traceable reasoning
 * ("RISK_WEIGHTED selected because: authentication instability +42%, spike detected").
 */
public class ExplanationDto {
    public String       subject;        // what is being explained (e.g. "Execution Strategy")
    public String       decision;       // the decision itself (e.g. "RISK_WEIGHTED")
    public String       headline;       // one-line plain-English summary
    public List<String> factors;        // ordered contributing factors (most influential first)
    public List<String> counterFactors; // what did NOT trigger (helps verify reasoning)
    public double       confidence;     // how much data supported this explanation
    public String       confidenceLabel;
    public String       advisory;       // additional human guidance (may be null)
}
