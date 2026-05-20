package utils.urlvalidator;

import java.util.Objects;

/**
 * One URL extracted from the live DOM.
 *
 * Carries enough source-tracing context that a broken link can be reported
 * with the exact element that produced it ({@code <img src="...">} vs
 * {@code <a href="...">}), not just the URL itself.
 */
public final class DiscoveredUrl {

    public final String         url;             // original URL as discovered — kept for forensic display
    public final String         canonicalUrl;    // normalized form used for dedup / aggregation
    public final UrlSourceKind  source;
    public final String         pageContext;     // page/section label provided by the caller
    public final String         elementSnippet;  // short anchor text or selector for forensic display
    public final String         attribute;       // which attribute carried the URL — href, src, action, etc.

    public DiscoveredUrl(String url, UrlSourceKind source, String pageContext,
                         String elementSnippet, String attribute) {
        this.url            = url;
        this.canonicalUrl   = UrlCanonicalizer.canonicalize(url);
        this.source         = source;
        this.pageContext    = pageContext;
        this.elementSnippet = elementSnippet;
        this.attribute      = attribute;
    }

    /**
     * Identity uses the canonical URL + source kind so {@code /page}, {@code /page/},
     * {@code /page?utm_source=x} and {@code /page#anchor} all collapse to one
     * record per source — the dashboard stays clean even on link-heavy pages.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DiscoveredUrl d)) return false;
        return Objects.equals(canonicalUrl, d.canonicalUrl) && source == d.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalUrl, source);
    }
}
