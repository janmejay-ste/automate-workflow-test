package utils.urlvalidator;

import utils.health.semantic.ClusterSeverity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Pattern-based <strong>indicator</strong> for paths that should not normally
 * be exposed via public-facing navigation.
 *
 * <h3>Scope — what this is and is NOT</h3>
 *
 * This class detects <em>suspicious-looking URLs</em>, not security
 * vulnerabilities.  Concretely:
 *
 * <ul>
 *   <li>✅ Flags that a URL <em>references</em> {@code /admin}, {@code /swagger},
 *       {@code /actuator}, {@code /.env}, etc.</li>
 *   <li>❌ Does NOT verify the path is actually reachable</li>
 *   <li>❌ Does NOT inspect the response body</li>
 *   <li>❌ Does NOT check authentication / authorization boundaries</li>
 *   <li>❌ Does NOT detect leaked credentials or PII in responses</li>
 *   <li>❌ Does NOT scan response headers for security misconfiguration</li>
 * </ul>
 *
 * For real security validation — auth-bypass testing, exposed-token scanning,
 * response-content inspection — a dedicated security tool (OWASP ZAP, Burp,
 * Nuclei) is the right answer.  This detector is an <em>indicator surface</em>
 * to flag suspicious DOM references for follow-up triage.
 *
 * <h3>Matching rules</h3>
 *
 * Tiered by severity:
 *   <ul>
 *     <li>CRITICAL — direct reference to admin / auth / config surfaces</li>
 *     <li>HIGH     — introspection patterns (swagger / actuator / graphql / api-docs)</li>
 *     <li>MEDIUM   — environment leaks (staging / dev / test paths in production)</li>
 *   </ul>
 *
 * Matching is path-segment aware: a URL containing the literal string
 * {@code "admin"} inside a domain or query parameter (e.g.
 * {@code https://admins-r-us.com}) is NOT flagged.  Only the URL's path
 * component is checked, and matches are against full segments separated by
 * {@code /}, so {@code /administer/} does not match {@code admin}.
 *
 * Override the default list via system property
 * {@code -Durlvalidator.sensitive.extra=path1,path2,...}.  Default patterns
 * stay; the override extends them.
 */
public final class SensitivePathDetector {

    /**
     * One classified sensitive-path rule.  Severity drives report ordering
     * and badge color; reason is the human-facing explanation.
     */
    public static final class Match {
        public final String          matchedSegment;
        public final ClusterSeverity severity;
        public final String          reason;

        public Match(String matchedSegment, ClusterSeverity severity, String reason) {
            this.matchedSegment = matchedSegment;
            this.severity       = severity;
            this.reason         = reason;
        }
    }

    private SensitivePathDetector() {}

    /** Path segment → severity + reason. */
    private static final Map<String, Match> PATTERNS;
    static {
        Map<String, Match> p = new LinkedHashMap<>();
        // CRITICAL — direct access to admin / auth surfaces
        p.put("admin",       new Match("admin",       ClusterSeverity.CRITICAL, "Admin surface exposed via public navigation"));
        p.put("internal",    new Match("internal",    ClusterSeverity.CRITICAL, "Internal-only path referenced from public DOM"));
        p.put("private",     new Match("private",     ClusterSeverity.CRITICAL, "Private path referenced from public DOM"));
        p.put("backup",      new Match("backup",      ClusterSeverity.CRITICAL, "Backup-related path referenced — possible data exposure"));
        // HIGH — exposed introspection / debug surfaces
        p.put("swagger",     new Match("swagger",     ClusterSeverity.HIGH,     "Swagger API explorer exposed"));
        p.put("api-docs",    new Match("api-docs",    ClusterSeverity.HIGH,     "OpenAPI/Swagger docs exposed"));
        p.put("actuator",    new Match("actuator",    ClusterSeverity.HIGH,     "Spring Actuator endpoint exposed"));
        p.put("debug",       new Match("debug",       ClusterSeverity.HIGH,     "Debug endpoint exposed"));
        p.put("graphql",     new Match("graphql",     ClusterSeverity.HIGH,     "GraphQL playground/endpoint exposed"));
        p.put("metrics",     new Match("metrics",     ClusterSeverity.HIGH,     "Metrics endpoint exposed"));
        p.put("config",      new Match("config",      ClusterSeverity.HIGH,     "Config surface exposed"));
        p.put(".env",        new Match(".env",        ClusterSeverity.CRITICAL, "Environment file referenced — likely credential leak"));
        // MEDIUM — environment leaks
        p.put("staging",     new Match("staging",     ClusterSeverity.MEDIUM,   "Staging path referenced from production-looking surface"));
        p.put("dev",         new Match("dev",         ClusterSeverity.MEDIUM,   "Dev path referenced from production-looking surface"));
        p.put("test",        new Match("test",        ClusterSeverity.MEDIUM,   "Test path referenced from production-looking surface"));

        // User-supplied additions via -Durlvalidator.sensitive.extra
        String extras = System.getProperty("urlvalidator.sensitive.extra", "");
        for (String s : extras.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty() && !p.containsKey(t)) {
                p.put(t, new Match(t, ClusterSeverity.HIGH, "Custom sensitive path (via -Durlvalidator.sensitive.extra)"));
            }
        }
        PATTERNS = Collections.unmodifiableMap(p);
    }

    /**
     * Return the first matching pattern (in declaration order so the most
     * severe rules win), or empty if none matched.
     */
    public static Optional<Match> match(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String path;
        try {
            URI uri = new URI(url);
            path = uri.getPath();
            if (path == null) return Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        // Tokenise on '/' so substring matches like "administer" don't trigger "admin".
        // Special case: ".env" is checked against the last segment as-is.
        String[] segments = path.toLowerCase().split("/");
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            // Exact segment match first
            Match m = PATTERNS.get(seg);
            if (m != null) return Optional.of(m);
            // .env can appear anywhere in the segment (e.g. "app.env", ".env.production")
            if (seg.contains(".env")) {
                return Optional.of(PATTERNS.get(".env"));
            }
        }
        return Optional.empty();
    }
}
