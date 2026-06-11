package utils.config;

/**
 * Centralised URL constants for the test suite.
 *
 * <p>Created as part of the {@code appypieautomate.ai → flozic.ai} rebrand
 * migration. Replaces scattered hard-coded marketing URLs.</p>
 *
 * <h2>Domain partition</h2>
 * <ul>
 *   <li><b>Marketing surface</b> ({@link #MARKETING_BASE}) — affected by the rebrand.
 *       Override via {@code -Dmarketing.baseUrl=...} for parallel testing.</li>
 *   <li><b>Auth surface</b> ({@link #AUTH_BASE}) — {@code accounts.appypie.com}, unchanged.</li>
 *   <li><b>Product surface</b> ({@link #CONNECT_BASE}) — {@code connectcloud.appypie.com}, unchanged.</li>
 * </ul>
 *
 * <h2>Lenient vs strict ownership checks</h2>
 * <ul>
 *   <li>{@link #isOwnedMarketingHost(String)} — accepts <b>both</b> {@code flozic.ai} and
 *       {@code appypieautomate.ai}. Use for general assertions during the transition
 *       window so brief redirect-chain states don't trigger false positives.</li>
 *   <li>{@link #isCurrentMarketingHost(String)} — strict. Only {@code flozic.ai}.
 *       Use for the dedicated rebrand-completion probe.</li>
 * </ul>
 *
 * <p>Once the transition window closes (target ~30 days post-cutover), delete
 * the legacy fallback inside {@link #isOwnedMarketingHost} and migrate any
 * remaining callers to the strict variant.</p>
 */
public final class UrlRegistry {

    /** Marketing site (rebrand-affected). Override with {@code -Dmarketing.baseUrl=...}. */
    public static final String MARKETING_BASE = System.getProperty(
            "marketing.baseUrl", "https://www.flozic.ai");

    /** Auth domain — unchanged across the rebrand. */
    public static final String AUTH_BASE = "https://accounts.appypie.com";

    /** Product domain (post-login) — unchanged across the rebrand. */
    public static final String CONNECT_BASE = "https://connectcloud.appypie.com";

    /** Marketing host only (no scheme, no www.), for {@code url.contains()} checks. */
    public static String marketingHostname() {
        return MARKETING_BASE.replaceFirst("^https?://(www\\.)?", "");
    }

    /**
     * Builds a marketing sub-page URL under the {@code /integrate/} prefix.
     *
     * <p>All flozic.ai marketing sub-pages (Pricing, App Directory, Features, AI
     * Automation, AI Agents, MCP Server, etc.) live at
     * {@code https://www.flozic.ai/integrate/{slug}}. Only the homepage is at
     * the bare root. This helper centralises that convention so page objects
     * don't each hardcode the prefix.</p>
     *
     * <p>Example: {@code integratePath("pricing-plan")}
     * → {@code https://www.flozic.ai/integrate/pricing-plan}.</p>
     */
    public static String integratePath(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must be non-blank");
        }
        String normalized = slug.startsWith("/") ? slug.substring(1) : slug;
        return MARKETING_BASE + "/integrate/" + normalized;
    }

    /**
     * Lenient ownership check during the rebrand transition.
     * Accepts both the current marketing host ({@code flozic.ai}) and the legacy host
     * ({@code appypieautomate.ai}). Used by tests that may observe URLs mid-redirect.
     */
    public static boolean isOwnedMarketingHost(String url) {
        if (url == null) return false;
        return url.contains(marketingHostname())
            || url.contains("appypieautomate.ai");
    }

    /**
     * Strict ownership check — only the current marketing host. Use for rebrand-completion
     * probes that intentionally fail if the redirect chain regresses to the legacy domain.
     */
    public static boolean isCurrentMarketingHost(String url) {
        return url != null && url.contains(marketingHostname());
    }

    private UrlRegistry() {}
}
