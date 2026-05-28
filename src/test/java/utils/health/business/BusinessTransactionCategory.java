package utils.health.business;

/**
 * Coarse category of a business transaction — drives grouping in the dashboard
 * and PDF.  Not a severity, not an owner — just the kind of user intent.
 *
 * Categories are intentionally short so they all read clearly at a glance in
 * report tables and badges.  Add new values sparingly; the goal is to keep the
 * report scannable, not to model every possible feature surface.
 */
public enum BusinessTransactionCategory {

    /** User created a new connect/workflow.  Persistence is the success signal. */
    WORKFLOW_CREATION,

    /** User configured the connection to a third-party app (OAuth, API key, account selection). */
    INTEGRATION,

    /** User authenticated, switched accounts, or restored a session. */
    AUTH,

    /** User activated/published a workflow.  Effectively "go-live" for that workflow. */
    ACTIVATION,

    /** User triggered a workflow execution and observed the resulting side effect. */
    EXECUTION,

    /** User completed an onboarding step (signup, plan selection, welcome flow). */
    ONBOARDING,

    /** Fallback bucket — categorise specifically once the pattern is recurrent. */
    OTHER
}
