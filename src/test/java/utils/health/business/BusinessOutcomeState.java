package utils.health.business;

/**
 * Outcome state of a single {@link BusinessTransaction}.
 *
 * Distinct from TestNG pass/fail because:
 *   - a test can technically pass (no assertion thrown) while leaving the user's
 *     intent unfulfilled (workflow not persisted, connect not activated)
 *   - a test can fail mid-flow without the business intent ever being attempted
 *     (e.g. login broke before Create Connect was clicked → ABORTED, not FAILED)
 *
 * The four-state model lets the release decision distinguish "we never knew" from
 * "we tried and confirmed it works" from "we tried and it broke".
 */
public enum BusinessOutcomeState {

    /** Transaction has started but evaluation is not complete. Treated as in-flight; never scored. */
    STARTED,

    /** Every required criterion was observed to pass. */
    SUCCESS,

    /** Some criteria passed, some failed. The intent is partially fulfilled. */
    PARTIAL,

    /** A required criterion failed. The user's intent was not fulfilled. */
    FAILED,

    /**
     * The transaction was abandoned mid-flow due to a prior unrelated failure
     * (e.g. login broke, framework crashed).  Not the same as FAILED — we did
     * not get the chance to evaluate this intent at all.  Excluded from scoring
     * so framework breakage doesn't get blamed on the product.
     */
    ABORTED
}
