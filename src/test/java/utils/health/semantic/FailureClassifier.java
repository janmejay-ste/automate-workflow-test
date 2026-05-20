package utils.health.semantic;

/**
 * Classifies a raw signal (message + context) into a {@link FailureDomain} and
 * a {@link ClusterSeverity}.
 *
 * Single source of truth for "what kind of failure is this".  HealthTracker stores
 * raw signals; this class decides whether each one is the product's fault, the
 * framework's fault, infrastructure, telemetry, or noise.
 *
 * Order of rule evaluation matters — the most specific patterns are checked first,
 * the broadest catch-alls last.
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    /**
     * Result of classifying a signal.
     */
    public static final class Classification {
        public final FailureDomain    domain;
        public final ClusterSeverity  severity;
        public final String           reason;

        public Classification(FailureDomain domain, ClusterSeverity severity, String reason) {
            this.domain   = domain;
            this.severity = severity;
            this.reason   = reason;
        }
    }

    /**
     * Classifies a JS console error.  Returns a non-null Classification for every input.
     */
    public static Classification classifyJsError(String context, String message) {
        String ctx = context == null ? "" : context.toLowerCase();
        String msg = message == null ? "" : message.toLowerCase();

        // ── Observability: third-party / known-noise patterns ─────────────────────
        // These get LOW severity and the OBSERVABILITY domain so they show up on the
        // dashboard for diagnostic visibility but never drive the product/framework score.
        if (msg.contains("zaraz")
                || msg.contains("googletagmanager")
                || msg.contains("google-analytics")
                || msg.contains("hotjar")
                || msg.contains("clarity.ms")
                || msg.contains("intercom")
                || msg.contains("crisp.chat")
                || msg.contains("facebook.net")
                || msg.contains("doubleclick")
                || msg.contains("segment.com")
                || msg.contains("amplitude")
                || msg.contains("swiper is not defined")
                || msg.contains("fedcm")
                || msg.contains("identity provider")
                || msg.contains("deprecated")
                || msg.contains("frame-ancestors")
                || msg.contains("content security policy")) {
            return new Classification(FailureDomain.OBSERVABILITY, ClusterSeverity.LOW,
                    "Third-party / non-actionable noise");
        }

        // ── Infrastructure: network/DNS/CORS/5xx from anywhere ────────────────────
        if (msg.contains("err_connection_refused")
                || msg.contains("err_connection_closed")
                || msg.contains("err_connection_reset")
                || msg.contains("err_name_not_resolved")
                || msg.contains("err_internet_disconnected")
                || msg.contains("err_failed")
                || msg.contains("cors")
                || msg.contains("preflight")
                || msg.contains(" 502 ") || msg.contains(" 503 ") || msg.contains(" 504 ")
                || msg.contains("bad gateway")
                || msg.contains("service unavailable")
                || msg.contains("gateway timeout")) {
            return new Classification(FailureDomain.INFRASTRUCTURE, ClusterSeverity.HIGH,
                    "Network / DNS / CORS / upstream gateway failure");
        }

        // ── Framework: Selenium / WebDriver / locator issues ──────────────────────
        if (ctx.contains("framework") || ctx.contains("selenium") || ctx.contains("retry")
                || msg.contains("staleelementreferenceexception")
                || msg.contains("nosuchelementexception")
                || msg.contains("elementclickintercepted")
                || msg.contains("elementnotinteractable")
                || msg.contains("invalidselectorexception")
                || msg.contains("timeoutexception")  // when emitted by the harness, not the page
                || msg.contains("webdriver")
                || msg.contains("locator")) {
            return new Classification(FailureDomain.FRAMEWORK, ClusterSeverity.MEDIUM,
                    "Automation harness — locator / retry / WebDriver");
        }

        // ── Telemetry: missing metric markers ─────────────────────────────────────
        if (msg.contains("0 ms") || msg.contains("0ms")
                || msg.contains("metric missing")
                || msg.contains("stopwatch never started")
                || msg.contains("timing hook")
                || ctx.contains("unknowntiming")) {
            return new Classification(FailureDomain.TELEMETRY, ClusterSeverity.LOW,
                    "Telemetry pipeline did not produce a measurement");
        }

        // ── Product: auth / session / editor / workflow failures (high severity) ──
        if (msg.contains("/register")
                || msg.contains("/login")
                || msg.contains("session expired")
                || msg.contains("unauthorized")
                || msg.contains(" 401 ")
                || msg.contains(" 403 ")
                || ctx.contains("auth/")
                || ctx.contains("workflowcreation")
                || ctx.contains("connectpersistence")) {
            return new Classification(FailureDomain.PRODUCT, ClusterSeverity.CRITICAL,
                    "Auth / session / workflow flow broken");
        }

        // ── Product: real frontend runtime defects ────────────────────────────────
        if (msg.contains("appendchild")
                || msg.contains("addeventlistener of null")
                || msg.contains("innerhtml")
                || msg.contains("cannot read propert")
                || msg.contains("cannot set propert")
                || msg.contains("is not a function")
                || msg.contains("typeerror")
                || msg.contains("referenceerror")
                || msg.contains("syntaxerror")
                || msg.contains("uncaught")
                || msg.contains("hydration")
                || msg.contains("chunkloaderror")
                || msg.contains("loading chunk")) {
            return new Classification(FailureDomain.PRODUCT, ClusterSeverity.HIGH,
                    "Frontend runtime defect in the product under test");
        }

        // ── Product: SUT 5xx on its own APIs ──────────────────────────────────────
        if (msg.contains(" 500 ") || msg.contains("internal server error")) {
            return new Classification(FailureDomain.PRODUCT, ClusterSeverity.HIGH,
                    "Product API returned 5xx");
        }

        return new Classification(FailureDomain.UNKNOWN, ClusterSeverity.INFO,
                "Pattern did not match any classifier rule");
    }

    /**
     * Classifies a test failure based on its name/reason text.  Almost always PRODUCT,
     * but routed through here so future framework-flake detection can move it.
     */
    public static Classification classifyTestFailure(String testName, String reason) {
        String r = (reason == null ? "" : reason).toLowerCase();
        // Heuristic — framework-side issues that produced a TestNG failure
        if (r.contains("stale element") || r.contains("element not interactable")
                || r.contains("no such element") || r.contains("session not created")) {
            return new Classification(FailureDomain.FRAMEWORK, ClusterSeverity.HIGH,
                    "TestNG failure caused by harness instability");
        }
        return new Classification(FailureDomain.PRODUCT, ClusterSeverity.CRITICAL,
                "TestNG-reported assertion failure");
    }
}
