package utils.governance.dto;

/**
 * Describes a recommendation that was blocked by a hard governance rule.
 * Produced by RecommendationSafetyValidator.
 *
 * Violations are recorded for visibility — they explain what the system
 * WOULD have recommended and WHY the safety rule prevented it.
 */
public class SafetyViolationDto {
    public String violationRule;        // which rule was triggered (e.g. "NO_MINIMIZE_CRITICAL_PATH")
    public String blockedDecision;      // what was blocked (e.g. "MINIMIZE_SUITE")
    public String blockedTarget;        // which workflow/test
    public String explanation;          // why the rule exists
    public String recommendedAction;    // what the team should do instead
    public double blockedConfidence;    // confidence of the blocked recommendation
}
