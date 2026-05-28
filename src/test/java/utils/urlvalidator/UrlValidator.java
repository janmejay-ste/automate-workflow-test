package utils.urlvalidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * HTTP probe for {@link DiscoveredUrl}s.
 *
 * Strategy:
 *   1. Try HEAD with NEVER-follow redirects so we can capture the chain.
 *   2. If HEAD returns 405/501 (method-not-allowed) OR 4xx in (400, 403, 405),
 *      fall back to GET — many CDNs and APIs reject HEAD with false-positive 4xx.
 *   3. Follow up to 10 redirects manually so the full chain is recorded.
 *   4. Surface DNS/TLS/connection failures as status 0 with the error message.
 *
 * Concurrency: validates URLs in a small bounded thread pool (default 6) so a
 * scan of 100 links completes in seconds rather than minutes.  Pool size
 * tunable via {@code -Durlvalidator.parallelism=N}.
 */
public final class UrlValidator {

    private static final Logger LOG = LoggerFactory.getLogger(UrlValidator.class);

    private static final int     MAX_REDIRECTS    = 10;
    private static final Duration PER_REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(System.getProperty("urlvalidator.timeoutSec", "8")));
    private static final int     PARALLELISM      = Integer.parseInt(
            System.getProperty("urlvalidator.parallelism", "6"));

    // ── Retry policy ─────────────────────────────────────────────────────────
    // Transient failures (429, 502, 503, 504, DNS hiccups in mid-CI) must not be
    // reported as findings — that turns the validator into a CI flake amplifier.
    // We retry up to MAX_RETRIES times with exponential backoff + jitter.
    //
    //   attempt 1: immediate
    //   attempt 2: 250 ms + jitter
    //   attempt 3: 500 ms + jitter
    //   attempt 4: 1000 ms + jitter
    //
    // Jitter prevents thundering-herd if many URLs to the same host all 503 at once.
    private static final int  MAX_RETRIES = Integer.parseInt(
            System.getProperty("urlvalidator.maxRetries", "3"));
    private static final long BASE_BACKOFF_MS = Long.parseLong(
            System.getProperty("urlvalidator.backoffMs", "250"));
    private static final java.util.Set<Integer> RETRY_STATUSES =
            java.util.Set.of(408, 425, 429, 500, 502, 503, 504);
    private static final java.util.concurrent.ThreadLocalRandom RND =
            java.util.concurrent.ThreadLocalRandom.current();

    // Single shared client.  HTTP/1.1 because some legacy CDNs misbehave with HTTP/2 HEAD.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)   // we drive the chain ourselves
            .build();

    private UrlValidator() {}

    public static List<UrlValidationResult> validateAll(List<DiscoveredUrl> urls) {
        if (urls.isEmpty()) return Collections.emptyList();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM, r -> {
            Thread t = new Thread(r, "url-validator");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<UrlValidationResult>> futures = new ArrayList<>(urls.size());
            for (DiscoveredUrl du : urls) {
                futures.add(CompletableFuture.supplyAsync(() -> validate(du), pool));
            }
            List<UrlValidationResult> out = new ArrayList<>(urls.size());
            for (CompletableFuture<UrlValidationResult> f : futures) {
                try { out.add(f.get(PER_REQ_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS)); }
                catch (Exception e) {
                    LOG.debug("Validation future failed: {}", e.getMessage());
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    public static UrlValidationResult validate(DiscoveredUrl du) {
        long start = System.currentTimeMillis();
        List<String> chain = new ArrayList<>();
        chain.add(du.url);
        String currentUrl = du.url;
        int    finalStatus = 0;
        String errorMessage = null;
        UrlValidationResult.HttpMethodUsed method = UrlValidationResult.HttpMethodUsed.HEAD;

        for (int hop = 0; hop < MAX_REDIRECTS + 1; hop++) {
            ProbeOutcome probe = probe(currentUrl);
            if (probe.exception != null) {
                errorMessage = probe.exception;
                method = UrlValidationResult.HttpMethodUsed.FAILED;
                break;
            }
            method = probe.methodUsed;
            finalStatus = probe.status;

            // Redirect?
            if (isRedirectStatus(probe.status) && probe.location != null) {
                String next = resolveLocation(currentUrl, probe.location);
                if (next == null) break;
                chain.add(next);
                currentUrl = next;
                continue;
            }
            break;
        }

        long dur = System.currentTimeMillis() - start;
        return new UrlValidationResult(du, chain.get(chain.size() - 1), finalStatus,
                chain, dur, method, errorMessage);
    }

    // ── HTTP probe ──────────────────────────────────────────────────────────

    private record ProbeOutcome(int status, String location, String exception,
                                UrlValidationResult.HttpMethodUsed methodUsed) {}

    /**
     * Probe with method fallback (HEAD → GET) and transient-retry policy applied
     * around the entire probe.  A 503 that recovers on retry never appears in the
     * report — only persistent failures do.
     */
    private static ProbeOutcome probe(String url) {
        ProbeOutcome last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            // 1. HEAD first
            ProbeOutcome p = sendOnce(url, "HEAD");
            // 2. HEAD-incompatible CDN/API → fall back to GET on this same attempt
            if (p.exception == null
                    && (p.status == 405 || p.status == 501
                        || p.status == 403 || p.status == 400)) {
                p = sendOnce(url, "GET");
            }
            last = p;
            // Success or non-transient outcome → return immediately
            if (!shouldRetry(p) || attempt == MAX_RETRIES) return p;
            // Transient — back off and try again
            try { Thread.sleep(backoffDelayMs(attempt)); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return p; }
        }
        return last;
    }

    /**
     * Retry on transient HTTP statuses (429, 5xx subset) OR network-layer
     * connection issues that often clear on retry (DNS hiccup, connection
     * reset).  4xx errors other than the retry list are NOT retried — those
     * are persistent client-side problems.
     */
    private static boolean shouldRetry(ProbeOutcome p) {
        if (p == null) return false;
        if (p.exception != null) {
            String e = p.exception.toLowerCase();
            return e.contains("dns failure")
                || e.contains("connection refused")
                || e.contains("connection reset")
                || e.contains("timeout");
        }
        return RETRY_STATUSES.contains(p.status);
    }

    /** Exponential backoff with full jitter — 250 ms * 2^attempt ± random. */
    private static long backoffDelayMs(int attempt) {
        long ceiling = BASE_BACKOFF_MS * (1L << Math.min(attempt, 6));   // cap doubling at 64x
        return RND.nextLong(BASE_BACKOFF_MS, ceiling + 1);
    }

    private static ProbeOutcome sendOnce(String url, String httpMethod) {
        try {
            URI uri = URI.create(url);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(PER_REQ_TIMEOUT)
                    .method(httpMethod, HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "Appypie-URL-Validator/1.0")
                    .build();
            HttpResponse<Void> resp = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            String loc = resp.headers().firstValue("location").orElse(null);
            return new ProbeOutcome(resp.statusCode(), loc, null,
                    "HEAD".equals(httpMethod) ? UrlValidationResult.HttpMethodUsed.HEAD
                                              : UrlValidationResult.HttpMethodUsed.GET);
        } catch (IllegalArgumentException badUri) {
            return new ProbeOutcome(0, null, "Malformed URI: " + badUri.getMessage(),
                    UrlValidationResult.HttpMethodUsed.FAILED);
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new ProbeOutcome(0, null, "Timeout after " + PER_REQ_TIMEOUT,
                    UrlValidationResult.HttpMethodUsed.FAILED);
        } catch (java.net.ConnectException refused) {
            return new ProbeOutcome(0, null, "Connection refused: " + refused.getMessage(),
                    UrlValidationResult.HttpMethodUsed.FAILED);
        } catch (java.net.UnknownHostException dns) {
            return new ProbeOutcome(0, null, "DNS failure: " + dns.getMessage(),
                    UrlValidationResult.HttpMethodUsed.FAILED);
        } catch (Exception e) {
            return new ProbeOutcome(0, null, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    UrlValidationResult.HttpMethodUsed.FAILED);
        }
    }

    private static boolean isRedirectStatus(int s) {
        return s == 301 || s == 302 || s == 303 || s == 307 || s == 308;
    }

    /** Resolve a Location header (possibly relative) against the current request URL. */
    private static String resolveLocation(String currentUrl, String location) {
        try {
            URI base = URI.create(currentUrl);
            URI loc  = URI.create(location);
            return base.resolve(loc).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Used by tests to inject a synthetic outcome — keeps the public surface narrow. */
    static List<String> defaultRedirectStatuses() {
        return Arrays.asList("301", "302", "303", "307", "308");
    }
}
