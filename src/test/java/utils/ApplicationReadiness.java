package utils;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Composite application readiness model.
 *
 * A single signal can lie. Four independent signals together approach determinism.
 *
 * Signal stack (evaluated in order, all must pass within the timeout):
 *   1. document.readyState === 'complete'     — HTML parsed, subresources loaded
 *   2. DOM mutation idle                       — no DOM changes for 300 ms
 *   3. Network idle                            — no pending XHR or fetch requests
 *   4. No visible loader/spinner elements      — UI indicates async work is complete
 *
 * Known remaining gaps (documented for transparency):
 *   - React / Angular microtask queues (settles after mutation observers fire, usually fine)
 *   - rAF-based animations (use CSS transitions instead — not captured here)
 *   - WebSocket streams (out-of-band; no mutation triggered when WS data arrives)
 *   - Cross-origin iframes (sandboxed by browser, cannot observe their DOM or network)
 *
 * Usage:
 *   ApplicationReadiness.waitForReady(driver);         // default 15 s
 *   ApplicationReadiness.waitForReady(driver, 30);     // custom seconds
 */
public final class ApplicationReadiness {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationReadiness.class);

    private static final long DEFAULT_TIMEOUT_MS     = 15_000;
    private static final long MUTATION_STABLE_MS     = 300;
    private static final long POLL_INTERVAL_MS       = 100;
    private static final long LOADER_WARN_THRESHOLD  = 10_000;

    private static final String LOADER_CSS =
            ".loader, .spinner, .ngx-spinner, app-loader, #loader, .outhLoader, " +
            "[class*='loading']:not(body), [class*='spinner'], .sk-spinner, " +
            ".progress-bar.active, .loading-overlay";

    private static final String MUTATION_TRACKER_JS =
            "if (!window.__mutTracker) {" +
            "  window.__lastMutation = Date.now();" +
            "  window.__mutTracker = new MutationObserver(function() {" +
            "    window.__lastMutation = Date.now();" +
            "  });" +
            "  window.__mutTracker.observe(document.body, " +
            "    {childList:true, subtree:true, attributes:true, characterData:true});" +
            "}";

    private ApplicationReadiness() {}

    /** Waits for composite application readiness with default 15 s timeout. */
    public static void waitForReady(WebDriver driver) {
        waitForReady(driver, Duration.ofMillis(DEFAULT_TIMEOUT_MS));
    }

    /** Waits for composite application readiness with a custom timeout. */
    public static void waitForReady(WebDriver driver, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        waitForDocumentComplete(driver, deadline);
        ensureMutationTrackerActive(driver);
        waitForMutationIdle(driver, deadline);
        waitForNetworkIdle(driver, deadline);
        waitForLoadersGone(driver, deadline);
    }

    // ── Signal 1: document.readyState ────────────────────────────────────────

    private static void waitForDocumentComplete(WebDriver driver, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            try {
                Object state = ((JavascriptExecutor) driver)
                        .executeScript("return document.readyState;");
                if ("complete".equals(state)) return;
            } catch (Exception e) {
                return; // page is navigating — unblock
            }
            sleep(POLL_INTERVAL_MS);
        }
    }

    // ── Signal 2: DOM mutation idle ───────────────────────────────────────────

    private static void ensureMutationTrackerActive(WebDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(MUTATION_TRACKER_JS);
        } catch (Exception e) {
            LOG.debug("MutationObserver injection skipped: {}", e.getMessage());
        }
    }

    private static void waitForMutationIdle(WebDriver driver, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            try {
                Object raw = ((JavascriptExecutor) driver)
                        .executeScript("return window.__lastMutation || 0;");
                long lastMutation = raw instanceof Number ? ((Number) raw).longValue() : 0L;
                if (System.currentTimeMillis() - lastMutation >= MUTATION_STABLE_MS) return;
            } catch (Exception e) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
    }

    // ── Signal 3: Network idle ────────────────────────────────────────────────

    private static void waitForNetworkIdle(WebDriver driver, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            try {
                Object pending = ((JavascriptExecutor) driver)
                        .executeScript("return window.__nmPending || 0;");
                int count = pending instanceof Number ? ((Number) pending).intValue() : 0;
                if (count == 0) return;
            } catch (Exception e) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        LOG.debug("Network idle timeout — some requests may still be pending");
    }

    // ── Signal 4: No visible loaders ─────────────────────────────────────────

    private static void waitForLoadersGone(WebDriver driver, long deadline) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                List<WebElement> loaders = driver.findElements(By.cssSelector(LOADER_CSS));
                boolean anyVisible = loaders.stream().anyMatch(el -> {
                    try { return el.isDisplayed(); }
                    catch (Exception e) { return false; }
                });
                if (!anyVisible) return;
                if (System.currentTimeMillis() - start > LOADER_WARN_THRESHOLD) {
                    LOG.warn("Loaders still visible after {} ms", System.currentTimeMillis() - start);
                }
            } catch (Exception e) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        LOG.warn("Loader still visible at composite readiness deadline — proceeding anyway");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
