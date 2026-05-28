package utils.urlvalidator;

/**
 * The kind of issue identified for a validated URL.
 *
 * One {@link UrlValidationResult} can produce multiple findings (e.g. a URL that
 * is both 404 and on a sensitive path — though in practice the BROKEN finding
 * absorbs everything else for clarity).
 *
 * Ordered roughly by severity in declaration so {@code values()} produces a
 * sensible default sort.
 */
public enum UrlFindingType {

    /** Server returned 4xx/5xx. */
    BROKEN,

    /** Request never produced an HTTP response — DNS failure, connection refused, TLS error. */
    UNREACHABLE,

    /** URL exposes a sensitive path (e.g. /admin, /swagger, /actuator). */
    SENSITIVE_PATH,

    /** Chain crossed from HTTPS to HTTP — mixed-content / downgrade risk. */
    HTTPS_DOWNGRADE,

    /** Followed >5 redirects — likely loop, misconfiguration, or tracking chain. */
    EXCESSIVE_REDIRECTS,

    /** Redirected to a domain we did not expect (off-platform link disguised as in-platform). */
    UNEXPECTED_EXTERNAL_REDIRECT,

    /** Loaded over HTTP from an HTTPS page (mixed content). */
    MIXED_CONTENT,

    /** Diagnostic only — recorded but no policy violation. */
    INFORMATIONAL
}
