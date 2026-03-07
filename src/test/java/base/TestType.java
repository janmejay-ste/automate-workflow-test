package base;

/**
 * Test classification types for execution filtering and dashboard reporting.
 */
public enum TestType {
    SMOKE,
    SANITY,
    REGRESSION,
    FULL;

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum FailureType {
        PRODUCT_BUG,
        AUTOMATION_BUG,
        ENVIRONMENT,
        TEST_ASSERTION
    }
}
