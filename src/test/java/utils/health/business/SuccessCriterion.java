package utils.health.business;

/**
 * One assertion that was supposed to hold for a {@link BusinessTransaction} to
 * be considered successful.  Recorded as data, not behaviour — the test code
 * does the actual checking and tells the criterion what it observed.
 *
 * Why declarative not executable: tests already know how to assert their own
 * conditions.  Re-implementing that as a predicate hierarchy here would force
 * the test to either (a) duplicate its own assertion or (b) bend its real
 * assertion into the framework's predicate shape.  Instead the test reports
 * what it checked and what it saw; we record both for the audit trail.
 */
public final class SuccessCriterion {

    public final String  label;        // human-readable, e.g. "URL transitions to /customeditor"
    public final String  expected;     // declarative expectation, e.g. "contains /customeditor"
    public final String  observed;     // what the test actually saw (null until set)
    public final boolean passed;       // outcome — true only after observed is set and matches expected

    private SuccessCriterion(String label, String expected, String observed, boolean passed) {
        this.label    = label;
        this.expected = expected;
        this.observed = observed;
        this.passed   = passed;
    }

    /**
     * Declare a criterion before evaluation.  Pass and observed are unset.
     * Call {@link #pass(String)} or {@link #fail(String)} when the test has
     * its observation.
     */
    public static SuccessCriterion declare(String label, String expected) {
        return new SuccessCriterion(label, expected, null, false);
    }

    /** Mark the criterion as observed and passing. */
    public SuccessCriterion pass(String observedValue) {
        return new SuccessCriterion(this.label, this.expected, observedValue, true);
    }

    /** Mark the criterion as observed and failing. */
    public SuccessCriterion fail(String observedValue) {
        return new SuccessCriterion(this.label, this.expected, observedValue, false);
    }

    /** {@code true} if this criterion has not been observed yet. */
    public boolean isPending() {
        return observed == null;
    }
}
