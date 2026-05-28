package utils.health.semantic;

/**
 * Taxonomy of failure domains.  A single penalty pool that mixes Selenium stale-element
 * errors with /register auth redirects and 0-ms timing gaps destroys operational trust —
 * those are categorically different failures with different remediation paths.
 *
 * Every signal recorded by HealthTracker is tagged with exactly one domain so the
 * dashboard can show three independent scores (Product / Framework / Telemetry) instead
 * of one collapsed number.
 */
public enum FailureDomain {

    /**
     * The product under test is broken — auth redirects to /register, /customeditor
     * never reaches the editor, appendChild/null DOM errors emitted by the SUT itself,
     * 5xx responses from the SUT's own APIs.  These are the failures that should block
     * a release.
     */
    PRODUCT("Product", "App stability — auth, editor, frontend defects"),

    /**
     * The automation framework is broken — StaleElementReferenceException, NoSuchElement,
     * locator drift, retry exhaustion, WebDriver session loss.  These do NOT indicate the
     * product is broken; they indicate the test harness needs a fix.
     */
    FRAMEWORK("Framework", "Automation stability — selectors, retries, WebDriver"),

    /**
     * Telemetry pipeline is broken — 0-ms timings, missing metrics, stopwatch never
     * started, navigation API hook absent.  Missing data ≠ catastrophic performance.
     * These never contribute to score penalty; they reduce telemetry confidence instead.
     */
    TELEMETRY("Telemetry", "Observability integrity — missing/invalid metrics"),

    /**
     * Network / infrastructure faults outside the product — DNS, CORS preflight, TLS
     * handshake, ERR_CONNECTION_*, 5xx from CDN/analytics endpoints.  Worth tracking
     * separately because a flaky CDN should not score the product as broken.
     */
    INFRASTRUCTURE("Infrastructure", "Network, DNS, CORS, CDN failures"),

    /**
     * Observability noise — analytics double-load, third-party JS console spam, deprecated
     * API warnings, swiper-not-defined, fedcm/identity-provider noise.  Recorded for
     * diagnostic visibility, never penalised.
     */
    OBSERVABILITY("Observability", "Third-party noise — analytics, deprecations"),

    /**
     * User-intent failures — the app rendered, the harness worked, but the business
     * transaction the user was trying to complete did not succeed.  Examples:
     * connect created in URL but not persisted to backend, OAuth completed but token
     * lost, workflow activated but trigger never fires.  These are the failures that
     * make "the dashboard is green but customers can't use the product."
     */
    BUSINESS("Business", "User-intent failures — workflow/integration/activation correctness"),

    /**
     * Domain could not be inferred from the available signal.  Used sparingly — the
     * classifier should resolve to a concrete domain for almost everything.
     */
    UNKNOWN("Unknown", "Domain could not be classified");

    public final String label;
    public final String description;

    FailureDomain(String label, String description) {
        this.label       = label;
        this.description = description;
    }
}
