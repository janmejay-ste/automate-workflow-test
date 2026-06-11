package utils.health.cluster;

import java.util.Map;

/**
 * Fingerprint extractor for locator-fallback events from the action engine.
 *
 * <p><b>Identity rule:</b> a fallback's identity is the combination of the
 * page (URL path, not query string) and the original selector that needed a
 * fallback. Two fallbacks on the same selector on the same page are the
 * same root cause regardless of how many retries happened.</p>
 *
 * <p><b>Reads from event map:</b></p>
 * <ul>
 *   <li>{@code name} — fallback rule name (e.g. "TextSearchFallback")</li>
 *   <li>{@code target} — the selector or target identifier that needed a fallback</li>
 *   <li>{@code page} — URL of the page where the fallback fired</li>
 * </ul>
 *
 * <p><b>Normalization rules:</b></p>
 * <ul>
 *   <li>Strip query strings, fragments, and trailing slashes from page URLs</li>
 *   <li>Lowercase everything</li>
 *   <li>Trim whitespace</li>
 *   <li>Collapse repeated whitespace to a single space</li>
 *   <li>Strip volatile attribute values inside selectors (e.g. data-id="123" → data-id="N")</li>
 * </ul>
 *
 * <p>Missing or blank fields fall back to "?" so the fingerprint is still
 * well-formed; events with no usable identity at all map to
 * {@link ClusterKey#UNKNOWN}.</p>
 */
public final class FallbackFingerprintExtractor implements FingerprintExtractor {

    @Override
    public ContributorSource source() {
        return ContributorSource.FALLBACKS;
    }

    @Override
    public ClusterKey extract(Map<String, String> event) {
        if (event == null || event.isEmpty()) return ClusterKey.UNKNOWN;

        String name   = normalizeText(event.getOrDefault("name", ""));
        String target = normalizeSelector(event.getOrDefault("target", ""));
        String page   = normalizePage(event.getOrDefault("page", ""));

        if (name.isBlank() && target.isBlank() && page.isBlank()) {
            return ClusterKey.UNKNOWN;
        }

        // Build the fingerprint. Pipe-separated so collisions across fields
        // (a selector that happens to look like a URL, etc.) can't merge keys.
        return new ClusterKey("fallbacks│"
                + emptyAsQuestion(page) + "│"
                + emptyAsQuestion(name) + "│"
                + emptyAsQuestion(target));
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String normalizePage(String url) {
        if (url == null || url.isBlank()) return "";
        String u = url.trim().toLowerCase();
        // Strip protocol, query, fragment, trailing slash.
        u = u.replaceFirst("^https?://", "");
        int q = u.indexOf('?');  if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');  if (h >= 0) u = u.substring(0, h);
        if (u.endsWith("/") && u.length() > 1) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String normalizeSelector(String sel) {
        if (sel == null || sel.isBlank()) return "";
        String s = sel.trim();
        // Volatile attribute values: data-id="123", id="row-457", etc.
        s = s.replaceAll("(?i)(data-[a-z-]+|id|aria-controls)\\s*=\\s*['\"][^'\"]*['\"]",
                         "$1=\"N\"");
        // Standalone numeric segments inside selectors → N
        s = s.replaceAll("(?<=[/#.\\-_:])\\d+(?=[/#.\\-_:\\s\\[]|$)", "N");
        return s.toLowerCase().replaceAll("\\s+", " ");
    }

    private static String emptyAsQuestion(String s) {
        return (s == null || s.isBlank()) ? "?" : s;
    }
}
