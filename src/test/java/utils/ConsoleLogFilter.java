package utils;

import java.util.List;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConsoleLogFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleLogFilter.class);

    // Centralized ignore pattern list; public so other code can consult it.
    // Note: analytics patterns (zaraz/clarity) are intentionally NOT in this list
    // so they can be downgraded to LOW warnings instead of fully ignored.
    private static final List<String> IGNORE_PATTERNS = List.of(
        "swiper is not defined",
        // "zaraz is loaded twice",  // moved to analytics patterns
        "refused to apply style",
        "failed to load resource",
        "frame-ancestors 'self'",
        "not signed in with the identity provider",
        // "clarity", // moved to analytics patterns
        "deprecated api"
    );

    // Patterns that indicate third-party federated auth / FedCM / GSI messages which should be recorded
    // as THIRD_PARTY_AUTH but not treated as JS errors or penalized.
    private static final List<String> THIRD_PARTY_AUTH_PATTERNS = List.of(
        "fedcm get()",
        "[gsi_logger]",
        "not signed in with the identity provider",
        "fedcm",
        "gsi",
        "accounts.google.com"
    );

    // Patterns that indicate analytics/tracking duplication (zaraz, clarity) which pollute analytics but
    // don't break UI — downgrade these to LOW warnings instead of JS errors.
    private static final List<String> ANALYTICS_PATTERNS = List.of(
        "zaraz is loaded twice",
        "clarity",
        "clarity.ms"
    );

    // Patterns that indicate CDN or plugin assets (eg. WordPress plugins, CDN hosts, blog assets)
    private static final List<String> CDN_PLUGIN_PATTERNS = List.of(
        "wp-content/plugins",
        "cdn",
        "wp-content",
        "/blog/",
        ".cdn.",
        "assets-"
    );

    public static boolean isThirdPartyAuthLog(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        for (String p : THIRD_PARTY_AUTH_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    public static boolean isAnalyticsLog(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        for (String p : ANALYTICS_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    public static boolean isBlogPathLog(String message) {
        if (message == null) return false;
        return message.toLowerCase().contains("/blog/");
    }

    public static boolean isCdnPluginLog(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        for (String p : CDN_PLUGIN_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    public static boolean isBlockingForUI(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        // common UI-blocking indicators
        if (lower.contains("refused to apply style")) return true;
        if (lower.contains("mime type") && lower.contains("stylesheet")) return true;
        if (lower.contains("blocked") && (lower.contains("frame") || lower.contains("resource"))) return true;
        // failed to load resource might be benign; only treat as blocking when status indicates 4xx/5xx
        if (lower.contains("failed to load resource") && (lower.contains(" 4") || lower.contains(" 5") || lower.matches(".*status of [4-5][0-9][0-9].*"))) return true;
        return false;
    }

    public static boolean isIgnoredLog(String message) {
        if (message == null) return true;
        String lower = message.toLowerCase();
        // If it's a third-party auth message, treat it as ignored for JS error purposes
        if (isThirdPartyAuthLog(lower)) return true;
        for (String p : IGNORE_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    public static void ignoreKnownErrors(WebDriver driver) {
        LogEntries logs = driver.manage().logs().get(LogType.BROWSER);

        for (LogEntry log : logs) {
            String message = log.getMessage().toLowerCase();

            if (isIgnoredLog(message)) continue;

            // Print only SEVERE-level logs that are not in the ignore list
            if (message.contains("severe") || message.contains("error") || message.contains("uncaught")) {
                LOG.warn("Browser Error: {}", message);
            }
        }
    }
}