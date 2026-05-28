package utils;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Captures API-level failures (4xx / 5xx / network errors) and tracks in-flight
 * request count for network-idle detection used by ApplicationReadiness.
 *
 * Implementation: JavaScript-level XHR + fetch interception.
 * Design rationale: CDP requires version-specific Selenium DevTools bindings that
 * drift with every Chrome release. The JS approach is version-agnostic and
 * covers Angular's HttpClient and legacy XHR.
 *
 * Known blind spots (documented):
 *   - WebSocket frames (no request lifecycle to intercept with fetch/XHR)
 *   - SSE streams (one long-lived connection, shows as never-idle)
 *   - Service worker traffic (runs outside page context)
 *   - Cross-origin iframes (sandboxed; interceptors don't reach them)
 *   - Apps that freeze window.fetch before injection runs
 *
 * These require CDP or proxy-level interception. Added as future work.
 *
 * Usage:
 *   NetworkMonitor.injectInterceptors(driver);  // call after every driver.get()
 *   ... interact with page ...
 *   List<Map<String,Object>> failures = NetworkMonitor.getApiFailures(driver);
 *   NetworkMonitor.reset(driver);               // between iterations
 */
public final class NetworkMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkMonitor.class);

    private static final String INTERCEPTOR_JS =
        "if (!window.__nmActive) {" +
        "  window.__nmActive = true;" +
        "  window.__nmErrors = [];" +
        "  window.__nmPending = 0;" +   // in-flight request counter for network-idle detection
        // ── fetch interceptor ──────────────────────────────────────────────
        "  const _fetch = window.fetch;" +
        "  window.fetch = function() {" +
        "    const url = arguments[0] instanceof Request" +
        "        ? arguments[0].url : String(arguments[0] || '');" +
        "    const method = (arguments[1] && arguments[1].method) || 'GET';" +
        "    window.__nmPending = (window.__nmPending || 0) + 1;" +
        "    return _fetch.apply(this, arguments)" +
        "      .then(function(r) {" +
        "        window.__nmPending = Math.max(0, (window.__nmPending || 1) - 1);" +
        "        if (r.status >= 400) {" +
        "          window.__nmErrors.push({url:url, status:r.status, method:method, type:'fetch'});" +
        "        }" +
        "        return r;" +
        "      }, function(err) {" +
        "        window.__nmPending = Math.max(0, (window.__nmPending || 1) - 1);" +
        "        window.__nmErrors.push({url:url, status:0, method:method, type:'fetch', error:err.message});" +
        "        throw err;" +
        "      });" +
        "  };" +
        // ── XHR interceptor ────────────────────────────────────────────────
        "  const _open = XMLHttpRequest.prototype.open;" +
        "  XMLHttpRequest.prototype.open = function(m, u) {" +
        "    this.__nmUrl = u; this.__nmMethod = m;" +
        "    _open.apply(this, arguments);" +
        "  };" +
        "  const _send = XMLHttpRequest.prototype.send;" +
        "  XMLHttpRequest.prototype.send = function() {" +
        "    window.__nmPending = (window.__nmPending || 0) + 1;" +
        "    this.addEventListener('loadend', function() {" +
        "      window.__nmPending = Math.max(0, (window.__nmPending || 1) - 1);" +
        "      if (this.status >= 400 || this.status === 0) {" +
        "        window.__nmErrors.push({url:this.__nmUrl||'', status:this.status," +
        "          method:this.__nmMethod||'XHR', type:'xhr'});" +
        "      }" +
        "    });" +
        "    _send.apply(this, arguments);" +
        "  };" +
        "}";

    private NetworkMonitor() {}

    /**
     * Injects fetch + XHR interceptors and initialises the pending-request counter.
     * Guard flag prevents double-injection. Must be re-called after full navigation.
     */
    public static void injectInterceptors(WebDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(INTERCEPTOR_JS);
        } catch (Exception e) {
            LOG.debug("NetworkMonitor inject failed (page navigating?): {}", e.getMessage());
        }
    }

    /**
     * Returns all captured API failures since last reset().
     * Each entry: url, status (int), method, type (fetch|xhr), error (optional).
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getApiFailures(WebDriver driver) {
        try {
            Object raw = ((JavascriptExecutor) driver)
                    .executeScript("return window.__nmErrors || [];");
            if (raw instanceof List) return (List<Map<String, Object>>) raw;
        } catch (Exception e) {
            LOG.debug("NetworkMonitor collect failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Returns the current in-flight request count.
     * Used by ApplicationReadiness.waitForNetworkIdle().
     */
    public static int getPendingCount(WebDriver driver) {
        try {
            Object raw = ((JavascriptExecutor) driver)
                    .executeScript("return window.__nmPending || 0;");
            return raw instanceof Number ? ((Number) raw).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Clears captured errors and resets the injection guard.
     * Call between test iterations to prevent cross-contamination.
     */
    public static void reset(WebDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "window.__nmErrors = []; window.__nmPending = 0; window.__nmActive = false;");
        } catch (Exception e) {
            LOG.debug("NetworkMonitor reset failed: {}", e.getMessage());
        }
    }

    /** Formatted report of all captured API errors. */
    public static String formatReport(List<Map<String, Object>> failures) {
        if (failures.isEmpty()) return "(no API failures)";
        StringBuilder sb = new StringBuilder();
        sb.append("=== NETWORK ERROR REPORT (").append(failures.size()).append(" entries) ===\n");
        for (Map<String, Object> f : failures) {
            int status = f.get("status") instanceof Number ? ((Number) f.get("status")).intValue() : -1;
            sb.append("  [").append(classifyStatus(status)).append("] ")
              .append(f.getOrDefault("method", "?")).append(" ")
              .append(f.getOrDefault("url", "?"))
              .append(" → HTTP ").append(status);
            Object err = f.get("error");
            if (err != null) sb.append(" (").append(err).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    public static String classifyStatus(int status) {
        if (status == 401 || status == 403) return "AUTH_FAILURE";
        if (status == 404)                  return "NOT_FOUND";
        if (status >= 500)                  return "SERVER_ERROR";
        if (status >= 400)                  return "CLIENT_ERROR";
        if (status == 0)                    return "NETWORK_FAILURE";
        return "UNKNOWN";
    }

    public static boolean hasServerErrors(List<Map<String, Object>> failures) {
        return failures.stream().anyMatch(f -> {
            int s = f.get("status") instanceof Number ? ((Number) f.get("status")).intValue() : 0;
            return s == 0 || s >= 500 || s == 401 || s == 403;
        });
    }
}
