package utils.urlvalidator;

import java.util.List;

/**
 * The outcome of probing a {@link DiscoveredUrl} over the network.
 *
 * Captures the full redirect chain (not just the final status) because:
 *   - HTTP→HTTPS downgrades are only visible via the chain
 *   - infinite-redirect loops can only be diagnosed from the chain
 *   - external-domain hops can only be flagged from the chain
 *
 * A {@code status} of 0 means the request failed before producing an HTTP
 * response (DNS failure, connection refused, TLS handshake error).  Code 0
 * is NOT treated as a 5xx — the {@link UrlFindingClassifier} maps it to a
 * dedicated "unreachable" classification.
 */
public final class UrlValidationResult {

    public final DiscoveredUrl     discovered;
    public final String            finalUrl;
    public final int               status;           // 0 = no response
    public final List<String>      redirectChain;    // ordered: original → … → finalUrl
    public final long              durationMs;
    public final HttpMethodUsed    method;
    public final String            errorMessage;    // populated when status == 0

    public enum HttpMethodUsed { HEAD, GET, FAILED }

    public UrlValidationResult(DiscoveredUrl discovered, String finalUrl, int status,
                               List<String> redirectChain, long durationMs,
                               HttpMethodUsed method, String errorMessage) {
        this.discovered    = discovered;
        this.finalUrl      = finalUrl;
        this.status        = status;
        this.redirectChain = redirectChain;
        this.durationMs    = durationMs;
        this.method        = method;
        this.errorMessage  = errorMessage;
    }

    public boolean isSuccessful()  { return status >= 200 && status < 400; }
    public boolean isBroken()      { return status >= 400 && status < 600; }
    public boolean isUnreachable() { return status == 0; }
    public int     redirectCount() { return Math.max(0, redirectChain.size() - 1); }
}
