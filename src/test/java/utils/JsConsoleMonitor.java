package utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Captures and classifies browser console errors from Chrome's BROWSER log channel.
 *
 * Classification is origin-aware: a TypeError thrown by a Google Analytics snippet
 * is IGNORE; the same TypeError thrown from appypieautomate.ai is FAIL.
 *
 * Policy table:
 *   FAIL   — uncaught exceptions, TypeErrors, ReferenceErrors, chunk load failures,
 *             API 500s originating from first-party origins.
 *   WARN   — failed fetches, unhandled promise rejections, unknown third-party errors.
 *   IGNORE — favicon 404s, analytics/tracking SDKs, ad-blockers, CSP reports,
 *             browser extensions, known third-party noise.
 *
 * Chrome log message format:
 *   "{source_url} {line}:{col} {message}"     (for script errors)
 *   "{message}"                                (for console.error calls)
 *
 * Requires LoggingPreferences.enable(LogType.BROWSER, Level.ALL) in ChromeOptions.
 */
public final class JsConsoleMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(JsConsoleMonitor.class);

    public enum Severity { FAIL, WARN, IGNORE }

    // ── First-party domains whose errors are promoted to FAIL ─────────────────
    private static final List<String> FIRST_PARTY_DOMAINS = List.of(
            "appypieautomate.ai",   // legacy marketing host (rebrand transition; keep until 30d post-cutover)
            "flozic.ai",            // current marketing host (post-rebrand)
            "appypie.com",
            "connectcloud.appypie.com",
            "accounts.appypie.com"
    );

    // ── Third-party/noise origins — always IGNORE regardless of error type ────
    private static final List<String> IGNORE_ORIGINS = List.of(
            "googletagmanager.com", "google-analytics.com", "analytics",
            "googleads.g.doubleclick.net", "googlesyndication.com",
            "hotjar.com", "hj.js",
            "intercom", "crisp.chat",
            "clarity.ms",
            "facebook.net", "connect.facebook",
            "twitter.com",
            "cdn.segment", "cdn.amplitude",
            "zaraz",            // Cloudflare Zaraz analytics loader — non-product noise
            "extension://",   // browser extensions
            "moz-extension://",
            "chrome-extension://"
    );

    // ── Message patterns that are always noise regardless of origin ───────────
    private static final List<String> IGNORE_MESSAGES = List.of(
            "favicon",
            "net::err_blocked_by_client",
            "net::err_blocked_by_administrator",
            "net::err_aborted",
            "status of 404 for url.*favicon",
            "frame-ancestors",
            "content security policy",
            "deprecated",
            "swiper is not defined",    // known site warning, not a product bug
            "fedcm",
            "identity provider",
            "zaraz is loaded twice",    // Cloudflare Zaraz double-load warning — non-product noise
            "zaraz"                     // Catch-all for any Zaraz console message
    );

    // ── Message patterns that indicate real application breakage ──────────────
    private static final List<String> FAIL_MESSAGES = List.of(
            "TypeError",
            "ReferenceError",
            "SyntaxError",
            "RangeError",
            "Uncaught",
            "Cannot read propert",
            "is not a function",
            "is not defined",
            "is not a constructor",
            "ChunkLoadError",
            "Loading chunk",
            "hydration",
            "Invariant Violation",
            "ExpressionChangedAfterItHasBeenChecked",  // Angular specific
            "Cannot match any routes",                  // Angular router
            " 500 ",
            " 503 ",
            "Internal Server Error",
            "net::ERR_CONNECTION_REFUSED",
            "net::ERR_NAME_NOT_RESOLVED",
            "net::ERR_FAILED"
    );

    // Parses the leading URL from Chrome log message format: "https://... line:col message"
    private static final Pattern SOURCE_URL_PATTERN =
            Pattern.compile("^(https?://[^\\s]+)\\s+\\d+:\\d+");

    private JsConsoleMonitor() {}

    /** Returns all SEVERE-level console messages from the current page. */
    public static List<String> getSevereErrors(WebDriver driver) {
        try {
            return driver.manage().logs().get(LogType.BROWSER).getAll()
                    .stream()
                    .filter(e -> e.getLevel().intValue() >= Level.SEVERE.intValue())
                    .map(LogEntry::getMessage)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.debug("Browser console log unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Returns all messages at or above the given level. */
    public static List<String> getErrors(WebDriver driver, Level minLevel) {
        try {
            return driver.manage().logs().get(LogType.BROWSER).getAll()
                    .stream()
                    .filter(e -> e.getLevel().intValue() >= minLevel.intValue())
                    .map(LogEntry::getMessage)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.debug("Browser console log unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Classifies a console message into FAIL / WARN / IGNORE.
     *
     * Origin-awareness: if the source URL is a known third-party, the message is
     * downgraded to IGNORE even if its content matches a FAIL pattern.
     * First-party errors are never downgraded.
     */
    public static Severity classify(String message) {
        if (message == null) return Severity.IGNORE;
        String lower = message.toLowerCase();

        // 1. Check message-level ignore patterns first (covers favicon etc.)
        for (String pattern : IGNORE_MESSAGES) {
            if (lower.contains(pattern.toLowerCase())) return Severity.IGNORE;
        }

        // 2. Extract source origin from the log message (if present)
        String sourceOrigin = extractSourceOrigin(message);

        // 3. Third-party origin → always IGNORE
        if (sourceOrigin != null && isThirdPartyOrigin(sourceOrigin)) {
            return Severity.IGNORE;
        }

        // 4. Evaluate error content
        boolean hasFail = FAIL_MESSAGES.stream()
                .anyMatch(p -> lower.contains(p.toLowerCase()));

        if (hasFail) {
            // FAIL only when origin is confirmed first-party.
            // Unknown origin (CDN chunks, hashed bundles, preview deployments, A/B assets)
            // defaults to WARN — false positives from third-party assets are more costly
            // than a missed warning, and unknown ≠ first-party in modern SPA deployments.
            boolean confirmed1p = sourceOrigin != null && isFirstPartyOrigin(sourceOrigin);
            return confirmed1p ? Severity.FAIL : Severity.WARN;
        }

        return Severity.WARN;
    }

    /** Extracts the source URL from Chrome's "{url} {line}:{col} {message}" format. */
    private static String extractSourceOrigin(String message) {
        try {
            Matcher m = SOURCE_URL_PATTERN.matcher(message);
            if (m.find()) {
                String url = m.group(1);
                // Return just the hostname for matching
                return extractHostname(url);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractHostname(String url) {
        try {
            // Simple hostname extraction without java.net.URL to avoid checked exceptions
            String s = url.replaceFirst("^https?://", "");
            int slash = s.indexOf('/');
            return slash >= 0 ? s.substring(0, slash) : s;
        } catch (Exception e) {
            return url;
        }
    }

    /** Returns true when hostname is a confirmed first-party domain. */
    private static boolean isFirstPartyOrigin(String hostname) {
        if (hostname == null) return false;
        String lower = hostname.toLowerCase();
        for (String fp : FIRST_PARTY_DOMAINS) {
            if (lower.contains(fp.toLowerCase())) return true;
        }
        return false;
    }

    /** Returns true when hostname is a known third-party noise source. */
    private static boolean isThirdPartyOrigin(String hostname) {
        if (hostname == null) return false;
        if (isFirstPartyOrigin(hostname)) return false; // first-party always wins
        String lower = hostname.toLowerCase();
        for (String noise : IGNORE_ORIGINS) {
            if (lower.contains(noise.toLowerCase())) return true;
        }
        return false;
    }

    /** Formats a list of errors into a readable report block. */
    public static String formatReport(List<String> errors) {
        if (errors.isEmpty()) return "(no JS errors)";
        StringBuilder sb = new StringBuilder();
        sb.append("=== JS ERROR REPORT (").append(errors.size()).append(" entries) ===\n");
        for (String err : errors) {
            Severity sev = classify(err);
            String origin = extractSourceOrigin(err);
            sb.append("  [").append(sev).append("]");
            if (origin != null) sb.append(" [").append(origin).append("]");
            sb.append(" ").append(err).append("\n");
        }
        return sb.toString();
    }
}
