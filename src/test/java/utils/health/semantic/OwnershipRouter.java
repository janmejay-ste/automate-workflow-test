package utils.health.semantic;

/**
 * Maps a {@link FailureDomain} to its owning team and recommended action.
 *
 * Single source of truth for ownership routing — both the HTML dashboard and the
 * PDF report consume this rather than duplicating the team-mapping in each
 * renderer (which inevitably drifts).
 *
 * The "action" is a short imperative phrase ("investigate runtime defects", not
 * "the team should investigate") so it slots cleanly into both inline owner
 * hints and PDF section headers.
 */
public final class OwnershipRouter {

    private OwnershipRouter() {}

    public static final class Routing {
        public final String team;     // e.g. "Frontend engineering"
        public final String action;   // e.g. "investigate runtime defects"
        public final String emoji;    // single glyph for visual scanning

        public Routing(String team, String action, String emoji) {
            this.team   = team;
            this.action = action;
            this.emoji  = emoji;
        }

        /** Single-line label suitable for inline display in panels. */
        public String inlineLabel() {
            return emoji + " " + team + " — " + action;
        }
    }

    public static Routing routeFor(FailureDomain domain) {
        if (domain == null) return UNKNOWN_ROUTING;
        return switch (domain) {
            case PRODUCT        -> new Routing("Frontend engineering", "investigate runtime defects",          "👤");
            case INFRASTRUCTURE -> new Routing("DevOps / SRE",         "network, DNS, gateways",               "🛠");
            case FRAMEWORK      -> new Routing("QA automation",        "selectors, retries, harness stability","🔧");
            case TELEMETRY      -> new Routing("Platform / observability", "fix instrumentation gaps",         "📊");
            case OBSERVABILITY  -> new Routing("Suppress in filters",  "third-party / non-actionable noise",   "🔕");
            case BUSINESS       -> new Routing("Product engineering",  "user-intent transaction failed — investigate backend + UI together", "🎯");
            case UNKNOWN        -> UNKNOWN_ROUTING;
        };
    }

    /** Convenience: look up routing by domain label string (case-insensitive). */
    public static Routing routeForLabel(String domainLabel) {
        if (domainLabel == null) return UNKNOWN_ROUTING;
        for (FailureDomain d : FailureDomain.values()) {
            if (d.label.equalsIgnoreCase(domainLabel)) return routeFor(d);
        }
        return UNKNOWN_ROUTING;
    }

    private static final Routing UNKNOWN_ROUTING =
            new Routing("Unclassified", "triage and classify domain", "❓");
}
