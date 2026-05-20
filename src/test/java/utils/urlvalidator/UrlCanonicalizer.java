package utils.urlvalidator;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Normalize URLs into a canonical form for deduplication and reporting.
 *
 * Without canonicalization the discovery engine emits 4+ separate findings
 * for what is operationally the same URL:
 * <pre>
 *   https://example.com/page
 *   https://example.com/page/
 *   https://example.com/page?utm_source=x
 *   https://Example.com/page#section
 * </pre>
 * After canonicalization all four collapse to {@code https://example.com/page}.
 *
 * Transformations applied (in order):
 *   1. Lower-case the host (case-insensitive per RFC 3986)
 *   2. Strip the fragment (server never sees it; pure client-side)
 *   3. Drop tracking-only query params (utm_*, fbclid, gclid, mc_*, _hsenc, etc.)
 *   4. Sort remaining query params so {@code ?a=1&b=2} == {@code ?b=2&a=1}
 *   5. Strip the trailing slash on the path EXCEPT for the bare root
 *   6. Drop the default port (:80 for http, :443 for https)
 *
 * Pure function — same input always produces same output.  No I/O, no DNS.
 *
 * Override the tracking-param list via
 * {@code -Durlvalidator.dropParams=p1,p2,...} to extend the defaults.
 */
public final class UrlCanonicalizer {

    private UrlCanonicalizer() {}

    /** Tracking params dropped during canonicalization.  Lower-case match. */
    private static final Set<String> TRACKING_PARAMS;
    static {
        Set<String> p = new LinkedHashSet<>(Arrays.asList(
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                "utm_id", "utm_name",
                "fbclid", "gclid", "msclkid", "dclid", "yclid",
                "mc_cid", "mc_eid",
                "_hsenc", "_hsmi", "hsctatracking",
                "ref", "ref_src", "ref_url",
                "igshid",
                "vero_id", "vero_conv",
                "mkt_tok",
                "trk", "trkcampaign",
                "wickedid", "wickedsource"
        ));
        String extras = System.getProperty("urlvalidator.dropParams", "");
        for (String s : extras.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) p.add(t);
        }
        TRACKING_PARAMS = Collections.unmodifiableSet(p);
    }

    /**
     * Return the canonical form of {@code raw}, or the original string if the URL
     * cannot be parsed (in which case dedup will still work, just imperfectly).
     */
    public static String canonicalize(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return raw.trim();
        }

        String scheme = uri.getScheme();
        if (scheme == null) return raw.trim();              // protocol-relative, leave alone
        scheme = scheme.toLowerCase();

        String host = uri.getHost();
        if (host == null) return raw.trim();                // opaque URI (mailto:, etc.)
        host = host.toLowerCase();

        int port = uri.getPort();
        if (("http".equals(scheme)  && port == 80)
                || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }

        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (path.isEmpty()) path = "/";
        // Strip trailing slash unless the path is just "/"
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String query = normalizeQuery(uri.getRawQuery());

        // Fragment intentionally dropped — never reaches the server.
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1) sb.append(':').append(port);
        sb.append(path);
        if (query != null && !query.isEmpty()) sb.append('?').append(query);
        return sb.toString();
    }

    /**
     * Drop tracking params, sort the rest by key.  Returns null if no query
     * survives, otherwise the rebuilt query string (without leading '?').
     */
    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        List<String[]> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? ""   : pair.substring(eq + 1);
            if (TRACKING_PARAMS.contains(key.toLowerCase())) continue;
            kept.add(new String[]{key, val});
        }
        if (kept.isEmpty()) return null;
        return kept.stream()
                .sorted(Comparator.comparing(a -> a[0].toLowerCase()))
                .map(a -> a[1].isEmpty() ? a[0] : a[0] + "=" + a[1])
                .collect(Collectors.joining("&"));
    }

    /** Diagnostic — exposes the configured tracking-param list. */
    public static Set<String> trackingParams() {
        return TRACKING_PARAMS;
    }
}
