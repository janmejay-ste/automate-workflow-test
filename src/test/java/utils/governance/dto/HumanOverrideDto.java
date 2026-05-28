package utils.governance.dto;

/**
 * Records a human override of an orchestration recommendation.
 * Stored persistently so the governance layer can learn which recommendations
 * are frequently overridden (low accuracy signal → calibration candidate).
 */
public class HumanOverrideDto {
    public String overrideId;           // UUID
    public String timestamp;            // ISO-8601
    public String decisionType;         // what decision was overridden
    public String target;               // workflow or test
    public String originalDecision;     // what the system recommended
    public String humanDecision;        // what the human chose instead
    public String overrideReason;       // why the human disagreed (optional but encouraged)
    public String owner;                // who made the override
    public boolean effectiveOverride;   // populated retrospectively: was human right?
}
