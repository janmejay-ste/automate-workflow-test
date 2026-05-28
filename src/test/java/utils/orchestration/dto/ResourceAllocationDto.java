package utils.orchestration.dto;

/**
 * Resource allocation recommendation for a suite or workflow.
 * High-risk unstable suites get isolated executors for clean failure isolation.
 * Stable lightweight suites share the parallel pool.
 */
public class ResourceAllocationDto {
    public String  suite;
    public String  poolType;        // ISOLATED | SHARED
    public int     recommendedThreads; // 1 = sequential, >1 = parallel within this suite
    public String  reason;
    public boolean highMemoryRisk;  // if true, recommend higher JVM heap allocation
}
