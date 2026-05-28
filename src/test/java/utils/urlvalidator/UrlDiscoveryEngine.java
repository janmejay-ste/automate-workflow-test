package utils.urlvalidator;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Extracts every URL-bearing element from the current DOM.
 *
 * Goes well beyond {@code a, button}:
 *   anchor, button, form action, iframe src, img src, script src, link href,
 *   source/srcset, video/audio src, plus runtime {@code performance.getEntries()}
 *   so dynamically-loaded resources (XHR/fetch/JS-injected scripts) are also
 *   captured even when they're not in the static markup.
 *
 * Returns a deduplicated list — same URL appearing in both a script tag and a
 * preload link is recorded once with the first-seen source kind.
 */
public final class UrlDiscoveryEngine {

    private static final Logger LOG = LoggerFactory.getLogger(UrlDiscoveryEngine.class);

    private UrlDiscoveryEngine() {}

    /**
     * Discover all URLs reachable from the current driver state.
     *
     * @param driver       live Selenium driver
     * @param pageContext  human label for the page being scanned ("Pricing", "Home", …) —
     *                     attached to each result for forensic display
     * @return deduplicated list in DOM order
     */
    public static List<DiscoveredUrl> discover(WebDriver driver, String pageContext) {
        Set<DiscoveredUrl> out = new LinkedHashSet<>();

        // ── Static markup-based discovery ─────────────────────────────────────
        collect(driver, By.cssSelector("a[href]"),                UrlSourceKind.ANCHOR,        "href",   pageContext, out);
        collect(driver, By.cssSelector("button[data-url]"),        UrlSourceKind.BUTTON,        "data-url", pageContext, out);
        collect(driver, By.cssSelector("button[formaction]"),      UrlSourceKind.BUTTON,        "formaction", pageContext, out);
        collect(driver, By.cssSelector("form[action]"),            UrlSourceKind.FORM,          "action", pageContext, out);
        collect(driver, By.cssSelector("iframe[src]"),             UrlSourceKind.IFRAME,        "src",    pageContext, out);
        collect(driver, By.cssSelector("img[src]"),                UrlSourceKind.IMAGE,         "src",    pageContext, out);
        collect(driver, By.cssSelector("script[src]"),             UrlSourceKind.SCRIPT,        "src",    pageContext, out);
        collect(driver, By.cssSelector("link[rel='stylesheet'][href]"),
                                                                  UrlSourceKind.STYLESHEET,    "href",   pageContext, out);
        collect(driver, By.cssSelector("link[rel='preload'][href], link[rel='prefetch'][href], link[rel='preconnect'][href]"),
                                                                  UrlSourceKind.PRELOAD_LINK,  "href",   pageContext, out);
        collect(driver, By.cssSelector("source[src]"),             UrlSourceKind.SOURCE,        "src",    pageContext, out);
        collect(driver, By.cssSelector("video[src]"),              UrlSourceKind.VIDEO,         "src",    pageContext, out);
        collect(driver, By.cssSelector("audio[src]"),              UrlSourceKind.AUDIO,         "src",    pageContext, out);

        // ── Runtime discovery via performance.getEntries() ───────────────────
        // Captures every resource the browser actually fetched (XHR, fetch, JS-injected
        // scripts) even if it's no longer in the live DOM.  Free, no CDP, works on any
        // page that finished loading.  Sprint 2's CDP integration will extend this.
        collectRuntimeResources(driver, pageContext, out);

        LOG.info("URL discovery [{}]: {} unique URLs", pageContext, out.size());
        return new ArrayList<>(out);
    }

    private static void collect(WebDriver driver, By selector, UrlSourceKind kind,
                                String attribute, String pageContext,
                                Set<DiscoveredUrl> out) {
        List<WebElement> els;
        try {
            els = driver.findElements(selector);
        } catch (Exception e) {
            LOG.debug("Selector {} threw: {}", selector, e.getMessage());
            return;
        }
        for (WebElement el : els) {
            try {
                String url = el.getAttribute(attribute);
                if (!isInterestingUrl(url)) continue;
                String snippet = describeElement(el, kind);
                out.add(new DiscoveredUrl(url, kind, pageContext, snippet, attribute));
            } catch (StaleElementReferenceException stale) {
                // DOM mutated mid-walk — skip this element, the rest of the batch is fine.
            } catch (Exception e) {
                LOG.debug("Skipping element via {}: {}", selector, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRuntimeResources(WebDriver driver, String pageContext,
                                                Set<DiscoveredUrl> out) {
        if (!(driver instanceof JavascriptExecutor js)) return;
        try {
            Object raw = js.executeScript(
                    "return performance.getEntriesByType('resource').map(e => e.name);");
            if (!(raw instanceof List<?> list)) return;
            for (Object o : list) {
                String url = o == null ? null : o.toString();
                if (!isInterestingUrl(url)) continue;
                out.add(new DiscoveredUrl(url, UrlSourceKind.DYNAMIC, pageContext,
                        "performance.getEntries() — runtime fetch", "(runtime)"));
            }
        } catch (Exception e) {
            LOG.debug("Runtime resource discovery failed: {}", e.getMessage());
        }
    }

    /**
     * Filter out URLs that aren't worth validating:
     *   - blank/null
     *   - {@code javascript:}, {@code mailto:}, {@code tel:}, {@code data:} schemes
     *   - in-page anchors ({@code #...})
     *   - blob URLs
     */
    private static boolean isInterestingUrl(String url) {
        if (url == null) return false;
        String s = url.trim();
        if (s.isEmpty() || s.startsWith("#") || s.equals("javascript:void(0)")) return false;
        String lower = s.toLowerCase();
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:")
                || lower.startsWith("tel:") || lower.startsWith("data:")
                || lower.startsWith("blob:") || lower.startsWith("about:")) {
            return false;
        }
        return true;
    }

    private static String describeElement(WebElement el, UrlSourceKind kind) {
        try {
            String text = el.getText();
            if (text != null && !text.isBlank()) {
                String trimmed = text.trim().replaceAll("\\s+", " ");
                if (trimmed.length() > 60) trimmed = trimmed.substring(0, 57) + "…";
                return trimmed;
            }
        } catch (Exception ignored) {}
        return kind.name().toLowerCase();
    }
}
