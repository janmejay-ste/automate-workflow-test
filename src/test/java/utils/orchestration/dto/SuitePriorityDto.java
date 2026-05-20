package utils.orchestration.dto;

/**
 * Execution priority recommendation for a test suite / workflow.
 * Affects ordering only — never silently skips core coverage.
 */
public class SuitePriorityDto {
    public String  suite;           // suite or workflow name
    public String  priorityTier;   // P1 | P2 | P3
    public String  riskLevel;      // CRITICAL | HIGH | MEDIUM | LOW
    public boolean isCriticalPath; // true if on the critical business path
    public String  reason;         // human-readable rationale
    public int     recommendedOrder; // 1 = execute first
}
