package utils.health.cluster;

/**
 * The contributor sources that participate in cluster-normalized scoring
 * (C4 roadmap item). Each source has its own fingerprint extractor because
 * the right grouping rule differs: a fallback's identity is its selector +
 * page, while a JS error's identity is its normalized message + context.
 *
 * <p>This enum is the single authoritative list of clusterable sources.
 * Adding a new contributor to scoring means adding an entry here AND a
 * corresponding {@link FingerprintExtractor} registered in the factory.
 * The compiler will not let you add the enum value without a factory entry
 * (exhaustive switch in {@link FingerprintExtractor#forSource}).</p>
 */
public enum ContributorSource {
    /** Locator-fallback events from action-engine retries. */
    FALLBACKS,
    /** JS errors recorded by HealthTracker.recordJsError. */
    JS_ERRORS,
    /** Test-level failures (TestNG ITestResult.FAILURE). */
    TEST_FAILURES
}
