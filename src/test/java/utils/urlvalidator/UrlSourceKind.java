package utils.urlvalidator;

/**
 * Where a URL was discovered in the DOM.
 *
 * Used by {@link UrlDiscoveryEngine} to tag every extracted URL with its origin
 * so downstream classification and reporting can group findings by source
 * (e.g. broken iframes are categorically different from broken anchors).
 *
 * Add new kinds sparingly — the goal is operational legibility in the report,
 * not exhaustive taxonomy.
 */
public enum UrlSourceKind {

    ANCHOR("a[href]"),
    BUTTON("button[data-url|formaction|onclick→location]"),
    FORM("form[action]"),
    IFRAME("iframe[src]"),
    IMAGE("img[src]"),
    SCRIPT("script[src]"),
    STYLESHEET("link[rel=stylesheet][href]"),
    PRELOAD_LINK("link[rel=preload|prefetch|preconnect][href]"),
    SOURCE("source[src|srcset]"),
    VIDEO("video[src]"),
    AUDIO("audio[src]"),
    /** Discovered at runtime via JS (Sprint 2 — performance.getEntries, fetch interception). */
    DYNAMIC("runtime-network");

    public final String selectorHint;

    UrlSourceKind(String selectorHint) {
        this.selectorHint = selectorHint;
    }
}
