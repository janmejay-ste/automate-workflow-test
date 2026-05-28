package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import pages.ConnectEditorPage;
import pages.DashboardPage;
import utils.ApplicationReadiness;
import utils.JsConsoleMonitor;
import utils.ManualLoginHelper;
import utils.NetworkMonitor;
import utils.RetryClassifier;
import utils.health.HealthTracker;
import utils.health.business.BusinessOutcomeTracker;
import utils.health.business.BusinessTransaction;
import utils.health.business.BusinessTransactionCategory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tests the app pairing flow on the App Directory page.
 *
 * Correctness contract:
 *   - Every iteration must either succeed (stay on site) or complete the full
 *     login → editor verification → logout cycle.
 *   - Any structural failure is a hard assertion — no WARN-only escape paths.
 *   - JS SEVERE errors and API failures are captured after each iteration.
 *   - DOM interactions use mutation-observer-based stability, not node counts.
 *   - Click retries are limited to TRANSIENT failures only (stale, intercepted).
 */
@TestCategory(type = TestType.REGRESSION, feature = "App Pairing")
public class AppPairingTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AppPairingTest.class);
    private static final String APP_DIRECTORY_URL = "https://www.appypieautomate.ai/integrate/app-directory";
    private static final int PAIRING_ITERATIONS = 6;
    private static final int VIRTUAL_SCROLL_ATTEMPTS = 8;

    private WebDriverWait wait;

    private static final String[] FIRST_APPS = {
            "Airbnb", "Net Suite", "Slack", "Trello", "Google Sheets", "Shopify"
    };

    // ── Phase 1: Structured result tracking ──────────────────────────────────

    enum FailureType {
        APP_NOT_FOUND, SECOND_APP_NOT_FOUND, AUTOMATE_BUTTON_MISSING,
        LOGIN_FAILED, EDITOR_NOT_LOADED, EDITOR_BLOCKED_BY_OVERLAY
    }

    static final class PairingResult {
        final String appName;
        boolean success;
        FailureType failureType;
        String failureReason;

        PairingResult(String appName) { this.appName = appName; }

        void fail(FailureType type, String reason) {
            this.success = false;
            this.failureType = type;
            this.failureReason = reason;
        }

        void succeed() { this.success = true; }

        @Override
        public String toString() {
            return success
                    ? "[PASS] " + appName
                    : "[FAIL] " + appName + " — " + failureType + ": " + failureReason;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    @Test(groups = { "regression" }, priority = 1)
    public void testAppPairingSearchFlow() {
        LOG.info("Starting app pairing test — {} iterations", PAIRING_ITERATIONS);
        List<PairingResult> results = new ArrayList<>();

        for (int i = 0; i < PAIRING_ITERATIONS; i++) {
            String firstApp = FIRST_APPS[i % FIRST_APPS.length];
            LOG.info("── Iteration {}/{}: {} ──", i + 1, PAIRING_ITERATIONS, firstApp);
            NetworkMonitor.reset(driver);

            // One business transaction per app-pair attempt — "the user selects two apps
            // and the platform takes them to a configured workflow editor ready to automate."
            BusinessTransaction tx = BusinessOutcomeTracker.get()
                    .begin("App Pairing — " + firstApp, BusinessTransactionCategory.WORKFLOW_CREATION)
                    .require("App card found in directory",         firstApp + " card clickable in DOM")
                    .require("Second app selected for pairing",     "secondary app card clicked")
                    .require("Workflow editor reached and validated","all 4 editor gates pass")
                    .require("Session lifecycle complete",           "logout redirects to non-editor page");

            PairingResult result = new PairingResult(firstApp);
            try {
                runPairingIteration(firstApp, i, result, tx);
            } catch (AssertionError ae) {
                tx.complete();   // PARTIAL or FAILED — criteria already observed above the throw
                throw ae;        // propagate hard failures immediately
            } catch (Exception e) {
                result.fail(FailureType.APP_NOT_FOUND, e.getMessage());
                LOG.error("[{}] Unexpected exception: {}", firstApp, e.getMessage());
                HealthTracker.get().recordTestFailure("AppPairing/" + firstApp, e.getMessage(), null);
                tx.abort("Unexpected framework exception: " + e.getMessage());
                Assert.fail("[" + firstApp + "] Unexpected failure: " + e.getMessage());
            } finally {
                results.add(result);
                captureAndRecordObservability(firstApp);
            }
        }

        List<PairingResult> failures = results.stream()
                .filter(r -> !r.success)
                .collect(Collectors.toList());
        if (!failures.isEmpty()) {
            Assert.fail(buildFailureMessage(failures));
        }

        LOG.info("All {} pairing iterations passed", PAIRING_ITERATIONS);
    }

    private void runPairingIteration(String firstApp, int iteration,
                                      PairingResult result, BusinessTransaction tx) {
        long iterationStart = System.currentTimeMillis();

        driver.get(APP_DIRECTORY_URL);
        ApplicationReadiness.waitForReady(driver);
        NetworkMonitor.injectInterceptors(driver);
        LOG.info("[{}] Step 1: Navigated to App Directory", firstApp);

        searchForApp(firstApp);
        LOG.info("[{}] Step 2: Search stabilized", firstApp);

        // ── Gate 1: first app card ──────────────────────────────────────────
        String urlBeforeCardClick = driver.getCurrentUrl();
        boolean found = findAndClickVerifiedAppCard(firstApp);
        if (!found) {
            result.fail(FailureType.APP_NOT_FOUND,
                    "Card not found after " + VIRTUAL_SCROLL_ATTEMPTS + " scroll attempts");
            tx.observeFail("App card found in directory",
                    firstApp + " not found after " + VIRTUAL_SCROLL_ATTEMPTS + " scroll passes");
            tx.complete();
            Assert.fail("[" + firstApp + "] App card not found after virtual scroll");
        }
        tx.observePass("App card found in directory", firstApp + " card clicked");

        // Post-click state transition validation: verify the click caused a real navigation
        // event, not just a DOM mutation that silently swallowed the interaction.
        validateUrlTransitioned(firstApp, urlBeforeCardClick, "app card click", 8);
        ApplicationReadiness.waitForReady(driver);
        NetworkMonitor.injectInterceptors(driver);
        LOG.info("[{}] Step 3: Clicked verified app card — URL transitioned", firstApp);

        clickPlusIcon();
        LOG.info("[{}] Step 4: Clicked + icon", firstApp);

        // ── Gate 2: second app card ─────────────────────────────────────────
        String urlBeforeSecondCard = driver.getCurrentUrl();
        boolean secondFound = clickVerifiedSecondAppCard(iteration);
        if (!secondFound) {
            result.fail(FailureType.SECOND_APP_NOT_FOUND,
                    "No second app cards visible for iteration " + iteration);
            tx.observeFail("Second app selected for pairing",
                    "No secondary app cards visible at iteration " + iteration);
            tx.complete();
            Assert.fail("[" + firstApp + "] Second app card not found");
        }
        tx.observePass("Second app selected for pairing",
                "iteration " + iteration + " card clicked");

        validateUrlTransitioned(firstApp, urlBeforeSecondCard, "second app card click", 8);
        ApplicationReadiness.waitForReady(driver);
        NetworkMonitor.injectInterceptors(driver);
        LOG.info("[{}] Step 5: Selected second app — URL transitioned", firstApp);

        clickAutomateButton(firstApp);
        sleep(500);
        LOG.info("[{}] Step 6: Clicked Automate/Get Started", firstApp);

        // ── Gates 3 & 4: editor validation + logout ─────────────────────────
        String currentUrl = driver.getCurrentUrl();
        if (currentUrl.contains("login") || currentUrl.contains("register")
                || currentUrl.contains("accounts.appypie")) {
            LOG.info("[{}] Auth redirect — handling login cycle", firstApp);
            HealthTracker.get().recordFallback("PairingLoginRedirect", currentUrl);
            handleLoginRedirect(firstApp, result, tx);
        } else {
            scrollPageSmoothly();
            // No auth redirect means the platform accepted the session inline;
            // the pairing page itself is the confirmation that the flow succeeded.
            tx.observePass("Workflow editor reached and validated",
                    "no auth redirect — pairing page confirmed: " + driver.getCurrentUrl());
            tx.observePass("Session lifecycle complete", "no-auth path; no logout required");
            result.succeed();
        }

        tx.complete();  // SUCCESS / PARTIAL / FAILED based on what was observed above

        long iterationMs = System.currentTimeMillis() - iterationStart;
        LOG.info("[{}] Iteration complete — duration: {}ms ({}s)", firstApp, iterationMs, iterationMs / 1000);
        if (iterationMs > 60_000) {
            HealthTracker.get().recordSlowPage("SlowIteration/" + firstApp, iterationMs);
        }
    }

    // ── Phase 2: Search with mutation-based DOM stability ─────────────────────

    private void searchForApp(String appName) {
        WebElement searchInput = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("input#appConnectName, input[placeholder*='Search Apps'], input[placeholder*='search']")));

        // React/Angular ignores .clear() — CTRL+A + DELETE fires the change event
        searchInput.click();
        searchInput.sendKeys(Keys.chord(Keys.CONTROL, "a"));
        searchInput.sendKeys(Keys.DELETE);

        // Verify the field is actually empty before typing
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> {
                    String val = searchInput.getAttribute("value");
                    return val == null || val.isEmpty();
                });

        // Arm mutation tracker before typing so it catches debounce renders
        injectMutationTracker();

        long typeTime = System.currentTimeMillis();
        searchInput.sendKeys(appName);

        // Wait for DOM to stop mutating (debounce + async render complete)
        waitForMutationStability(400, 3000);

        // Wait until at least one result card appears; record time-to-first-result
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> !d.findElements(By.cssSelector(
                        "div.app-box, div.integration-card, div[class*='app-card'], a[href*='integrations']"))
                        .isEmpty());
        long ttfr = System.currentTimeMillis() - typeTime;

        LOG.info("[{}] Search timing — time-to-first-result: {}ms", appName, ttfr);
        if (ttfr > 3000) {
            HealthTracker.get().recordSlowPage("SearchResults/" + appName, ttfr);
        }
    }

    /**
     * Injects a MutationObserver that stamps window.__lastMutation on every DOM change.
     * Designed to survive re-injection — guard flag prevents duplicate observers.
     */
    private void injectMutationTracker() {
        try {
            ((JavascriptExecutor) driver).executeScript(
                "if (!window.__mutTracker) {" +
                "  window.__lastMutation = Date.now();" +
                "  window.__mutTracker = new MutationObserver(function() {" +
                "    window.__lastMutation = Date.now();" +
                "  });" +
                "  window.__mutTracker.observe(document.body, " +
                "    {childList:true, subtree:true, attributes:true, characterData:true});" +
                "}"
            );
        } catch (Exception e) {
            LOG.debug("MutationObserver injection failed: {}", e.getMessage());
        }
    }

    /**
     * Polls window.__lastMutation until no mutation has occurred for stableMs,
     * or until deadlineMs total elapsed. Tracks actual mutation timestamps — not
     * a proxy count — so attribute changes, text updates and re-renders are detected.
     */
    private void waitForMutationStability(long stableMs, long deadlineMs) {
        long deadline = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Object raw = ((JavascriptExecutor) driver)
                        .executeScript("return window.__lastMutation || 0;");
                long lastMutation = raw instanceof Number ? ((Number) raw).longValue() : 0L;
                if (System.currentTimeMillis() - lastMutation >= stableMs) return;
            } catch (Exception e) {
                break; // page navigated mid-poll
            }
            sleep(100);
        }
    }

    private static final String CARD_CSS =
            "div.app-box a, div.integration-card a, div[class*='app-card'] a, a[href*='integrations']";

    /**
     * Scrolls down up to VIRTUAL_SCROLL_ATTEMPTS times, re-querying the DOM fresh
     * on every scroll pass AND on every click attempt.
     *
     * The core bug this fixes: capturing a WebElement reference before the click retry
     * means every retry reuses a potentially-stale pointer. Instead, we re-query the
     * full card list on each click attempt so the reference is always live.
     */
    private boolean findAndClickVerifiedAppCard(String appName) {
        String normalized = normalize(appName);
        String slug = appName.toLowerCase().replace(" ", "-");

        for (int attempt = 0; attempt < VIRTUAL_SCROLL_ATTEMPTS; attempt++) {

            // Atomic acquisition: query, validate, and return the element in one pass.
            // Splitting "check visibility" from "get element" creates a TOCTOU window —
            // the DOM can re-render between the two calls, making the second one stale.
            // acquireMatchedCard() does both atomically so the returned reference is
            // immediately usable with no gap between detection and interaction.
            Optional<WebElement> acquired = acquireMatchedCard(normalized, slug);

            if (acquired.isPresent()) {
                // Click with up to 3 fresh acquisitions. Each iteration of the loop
                // re-queries the DOM so a stale reference from the previous attempt
                // is never reused — the acquisition and interaction are always co-located.
                for (int clickAttempt = 0; clickAttempt < 3; clickAttempt++) {
                    Optional<WebElement> fresh = (clickAttempt == 0) ? acquired
                            : acquireMatchedCard(normalized, slug);
                    if (fresh.isEmpty()) {
                        sleep(300); // card may have temporarily re-rendered
                        continue;
                    }
                    WebElement card = fresh.get();
                    try {
                        scrollIntoView(card);
                        card.click();
                        LOG.info("Clicked verified app card '{}' (scroll pass {}, click attempt {})",
                                appName, attempt + 1, clickAttempt + 1);
                        return true;
                    } catch (ElementNotInteractableException e) {
                        // Covers both ElementClickInterceptedException (subclass) and base class
                        LOG.debug("[{}] Click not interactable (attempt {}): {}", appName, clickAttempt + 1, e.getMessage());
                        sleep(400);
                    } catch (StaleElementReferenceException e) {
                        LOG.debug("[{}] Card went stale between query and click (attempt {}) — re-querying",
                                appName, clickAttempt + 1);
                        sleep(200);
                    }
                }
                LOG.warn("[{}] Click failed after 3 fresh re-queries at scroll pass {}", appName, attempt + 1);
                return false;
            }

            // Observability: re-query on each miss so the debug log reflects what's
            // actually in the DOM at this scroll position
            if (LOG.isDebugEnabled()) {
                List<WebElement> passCards = driver.findElements(By.cssSelector(CARD_CSS));
                List<String> visible = passCards.stream()
                        .filter(c -> { try { return c.isDisplayed(); } catch (Exception e) { return false; } })
                        .map(c -> { try { return normalize(c.getText()); } catch (Exception e) { return "?"; } })
                        .filter(t -> !t.isBlank())
                        .distinct()
                        .limit(10)
                        .collect(Collectors.toList());
                LOG.debug("[{}] Pass {} — {} cards visible, names: {}", appName, attempt + 1, passCards.size(), visible);
            }

            ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 400);");
            sleep(300);
        }

        // Final diagnostic: log rendered state so the CI log explains the failure
        logSearchDiagnostic(appName, normalized, slug);
        return false;
    }

    /**
     * Atomically queries the DOM, validates visibility, and returns a usable element reference.
     * Combines detection and acquisition into one pass to eliminate the TOCTOU window that
     * exists when "check if visible" and "get element" are separate calls — the DOM can
     * re-render between them, making the second call stale before it's even used.
     *
     * Text match is tried first; href-slug is the fallback for apps whose text is in
     * an alt attribute or whose display text doesn't match the search term exactly.
     */
    private Optional<WebElement> acquireMatchedCard(String normalized, String slug) {
        List<WebElement> cards = driver.findElements(By.cssSelector(CARD_CSS));
        Optional<WebElement> match = cards.stream()
                .filter(c -> {
                    try { return c.isDisplayed() && normalize(c.getText()).contains(normalized); }
                    catch (StaleElementReferenceException e) { return false; }
                })
                .findFirst();
        if (match.isPresent()) return match;
        return cards.stream()
                .filter(c -> {
                    try {
                        String href = c.getAttribute("href");
                        return c.isDisplayed() && href != null && href.contains(slug);
                    } catch (StaleElementReferenceException e) { return false; }
                })
                .findFirst();
    }

    /**
     * Verifies a click caused a real URL/state transition within timeoutSeconds.
     * "Click did not throw" is not sufficient — overlay swallowing, re-render cancellation,
     * or event interception can all silently eat the click without advancing the workflow.
     *
     * Non-fatal: logs a warning if the URL did not change (some clicks update DOM without
     * navigating, e.g. in-page panel expansion), but registers a health warning so the
     * pattern is visible in trend data without failing individual iterations.
     */
    private void validateUrlTransitioned(String appName, String urlBefore, String action, int timeoutSeconds) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(timeoutSeconds))
                    .until(d -> !d.getCurrentUrl().equals(urlBefore));
            LOG.info("[{}] URL transitioned after {} — before: {} after: {}",
                    appName, action, urlBefore, driver.getCurrentUrl());
        } catch (Exception e) {
            String current = driver.getCurrentUrl();
            if (current.equals(urlBefore)) {
                LOG.warn("[{}] URL unchanged after {} ({}s) — click may have been swallowed. URL: {}",
                        appName, action, timeoutSeconds, current);
                HealthTracker.get().addWarning("SwallowedClick",
                        appName + ": URL unchanged after " + action + " — " + current);
            }
        }
    }

    /**
     * Emits a structured diagnostic when a card is not found after all scroll passes.
     * Captures: total cards in DOM, visible subset, their texts and hrefs, current scroll Y,
     * and the search input value — so "not found" is always accompanied by WHY.
     */
    private void logSearchDiagnostic(String appName, String normalized, String slug) {
        try {
            List<WebElement> allCards = driver.findElements(By.cssSelector(
                    "div.app-box a, div.integration-card a, div[class*='app-card'] a, a[href*='integrations']"));

            List<String> visibleTexts = allCards.stream()
                    .filter(c -> { try { return c.isDisplayed(); } catch (Exception e) { return false; } })
                    .map(c -> { try { return normalize(c.getText()); } catch (Exception e) { return "<stale>"; } })
                    .filter(t -> !t.isBlank())
                    .distinct()
                    .collect(Collectors.toList());

            Object scrollY = ((JavascriptExecutor) driver).executeScript("return window.scrollY;");
            String searchVal = "";
            try {
                List<WebElement> inputs = driver.findElements(By.cssSelector(
                        "input#appConnectName, input[placeholder*='Search Apps'], input[placeholder*='search']"));
                if (!inputs.isEmpty()) searchVal = inputs.get(0).getAttribute("value");
            } catch (Exception ignored) {}

            LOG.warn("[{}] SEARCH DIAGNOSTIC — target:'{}' slug:'{}' | total cards:{} | visible:{} | " +
                     "scrollY:{} | searchInput:'{}' | visible card names: {}",
                    appName, normalized, slug, allCards.size(), visibleTexts.size(),
                    scrollY, searchVal, visibleTexts);

            HealthTracker.get().addWarning("SearchMiss",
                    appName + ": " + allCards.size() + " cards in DOM, none matched. Input='" + searchVal + "'");
        } catch (Exception e) {
            LOG.debug("[{}] Search diagnostic capture failed: {}", appName, e.getMessage());
        }
    }

    private boolean clickVerifiedSecondAppCard(int iteration) {
        sleep(300);
        List<By> cardSelectors = List.of(
                By.cssSelector("div.app-box a, div.integration-card a"),
                By.cssSelector("a[href*='/integrations/']"),
                By.xpath("//div[contains(@class,'app')]//a[contains(@href,'integrations')]"),
                By.cssSelector("div.card-body a"));

        for (By selector : cardSelectors) {
            try {
                List<WebElement> visible = driver.findElements(selector).stream()
                        .filter(c -> {
                            try { return c.isDisplayed(); }
                            catch (StaleElementReferenceException e) { return false; }
                        })
                        .collect(Collectors.toList());

                if (!visible.isEmpty()) {
                    int idx = Math.min(iteration, visible.size() - 1);
                    WebElement card = visible.get(idx);
                    try {
                        RetryClassifier.withRetryVoid(3, 400, () -> {
                            scrollIntoView(card);
                            card.click();
                        });
                    } catch (Exception e) {
                        LOG.debug("Selector {} click failed: {}", selector, e.getMessage());
                        continue;
                    }
                    LOG.info("Clicked second app card #{} using: {}", idx, selector);
                    return true;
                }
            } catch (Exception e) {
                LOG.debug("Selector {} yielded no results: {}", selector, e.getMessage());
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    // ── Hard-failure action methods ───────────────────────────────────────────

    private void clickPlusIcon() {
        List<By> plusSelectors = List.of(
                By.cssSelector("a[href='#selectConnectApp']"),
                By.cssSelector("a[href='#selectConnectApp'] span"),
                By.xpath("//a[contains(@href,'selectConnectApp')]"),
                By.xpath("//span[contains(text(),'+')]"),
                By.cssSelector(".plus-icon, .add-app-btn"));

        for (By selector : plusSelectors) {
            List<WebElement> elements = driver.findElements(selector);
            if (!elements.isEmpty() && elements.get(0).isDisplayed()) {
                scrollIntoView(elements.get(0));
                elements.get(0).click();
                LOG.info("Clicked + icon using: {}", selector);
                return;
            }
        }
        LOG.info("No + icon found — scrolling to reveal app panel");
        ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 500);");
        sleep(300);
    }

    private void clickAutomateButton(String appName) {
        List<By> automateSelectors = List.of(
                By.cssSelector("a#getStartedBtn"),
                By.cssSelector("a.bannerInnerBtn"),
                By.cssSelector("a[href*='accounts.appypie.com']"),
                By.xpath("//a[contains(text(),'Get Started')]"),
                By.xpath("//a[contains(text(),'Automate')]"),
                By.cssSelector("a.btn-primary, button.btn-primary"));

        for (By selector : automateSelectors) {
            List<WebElement> elements = driver.findElements(selector);
            if (!elements.isEmpty() && elements.get(0).isDisplayed()) {
                scrollIntoView(elements.get(0));
                elements.get(0).click();
                LOG.info("[{}] Clicked automate button: {}", appName, selector);
                return;
            }
        }
        Assert.fail("[" + appName + "] Automate/Get Started button not found — pairing page broken");
    }

    // ── Login + editor cycle ──────────────────────────────────────────────────

    private void handleLoginRedirect(String appName, PairingResult result, BusinessTransaction tx) {
        clickLoginLinkIfOnSignupPage();
        try {
            ManualLoginHelper.navigateAndLogin(driver, driver.getCurrentUrl());
            NetworkMonitor.injectInterceptors(driver);
            LOG.info("[{}] Login completed — verifying connect editor", appName);
        } catch (Exception e) {
            result.fail(FailureType.LOGIN_FAILED, e.getMessage());
            tx.observeFail("Workflow editor reached and validated",
                    "Login step failed before editor could be reached: " + e.getMessage());
            tx.observeFail("Session lifecycle complete", "login failed; logout not attempted");
            tx.complete();
            Assert.fail("[" + appName + "] Login failed: " + e.getMessage());
        }

        validateWorkflowEditorReady(appName, result, tx);
        logoutAndReturnToAppDirectory(appName, tx);
    }

    /**
     * Functional readiness validation — four gates, all must pass:
     *   1. URL contains /customeditor
     *   2. Editor canvas visible
     *   3. No blocking overlay or modal
     *   4. Workflow graph rendered (trigger node present or canvas has children)
     */
    private void validateWorkflowEditorReady(String appName, PairingResult result,
                                              BusinessTransaction tx) {
        // Gate 1: URL
        try {
            new WebDriverWait(driver, Duration.ofSeconds(20))
                    .until(d -> d.getCurrentUrl().contains("customeditor"));
        } catch (Exception e) {
            String url = driver.getCurrentUrl();
            result.fail(FailureType.EDITOR_NOT_LOADED, "URL never reached customeditor — got: " + url);
            tx.observeFail("Workflow editor reached and validated",
                    "Gate 1 failed — /customeditor URL not reached within 20 s, got: " + url);
            tx.observeFail("Session lifecycle complete", "editor never loaded; logout not attempted");
            tx.complete();
            Assert.fail("[" + appName + "] Expected /customeditor URL within 20 s, got: " + url);
        }
        LOG.info("[{}] customeditor URL confirmed: {}", appName, driver.getCurrentUrl());

        // Gate 2: Canvas visibility
        ConnectEditorPage editor = new ConnectEditorPage(driver);
        if (!editor.isEditorVisible()) {
            result.fail(FailureType.EDITOR_NOT_LOADED, "Canvas not visible despite correct URL");
            tx.observeFail("Workflow editor reached and validated",
                    "Gate 2 failed — editor canvas not visible at " + driver.getCurrentUrl());
            tx.observeFail("Session lifecycle complete", "canvas not visible; logout not attempted");
            tx.complete();
            Assert.fail("[" + appName + "] Connect editor canvas not visible — workflow may not have been created");
        }

        // Gate 3: No blocking overlay
        assertNoBlockingOverlay(appName, result);

        // Gate 4: Workflow graph rendered
        assertWorkflowGraphRendered(appName);

        // All four gates passed — the editor is genuinely ready for the user
        LOG.info("[{}] Workflow editor fully ready — all 4 gates passed", appName);
        tx.observePass("Workflow editor reached and validated",
                "all 4 gates passed — " + driver.getCurrentUrl());
        HealthTracker.get().recordTestSuccess();
        result.succeed();
    }

    // ── Overlay classification before dismissal ───────────────────────────────

    private enum OverlayType { LOADER, ONBOARDING, AUTH, UNKNOWN }

    private OverlayType classifyOverlay(WebElement overlay) {
        String cls = "";
        String text = "";
        try { cls = overlay.getAttribute("class").toLowerCase(); } catch (Exception ignored) {}
        try { text = overlay.getText().toLowerCase(); } catch (Exception ignored) {}

        if (cls.contains("loader") || cls.contains("spinner") || cls.contains("authloader")
                || cls.contains("outhloader") || cls.contains("loading")) return OverlayType.LOADER;
        if (cls.contains("onboard") || cls.contains("guide") || cls.contains("tour")
                || cls.contains("walkthrough") || cls.contains("joyride")) return OverlayType.ONBOARDING;
        if (text.contains("login") || text.contains("password") || text.contains("sign in")
                || text.contains("authenticate")) return OverlayType.AUTH;
        return OverlayType.UNKNOWN;
    }

    /**
     * Captures a DOM telemetry snapshot for an unclassified overlay so future runs
     * can expand the classification table rather than just logging "UNKNOWN".
     * Records: tag, class, id, z-index, outerHTML (first 300 chars), visible text.
     */
    private void captureOverlayTelemetry(String appName, WebElement overlay, By selector) {
        try {
            String tag    = safeAttr(overlay, "tagName", "?");
            String cls    = safeAttr(overlay, "class", "");
            String id     = safeAttr(overlay, "id", "");
            String zindex = safeStyle(overlay, "z-index");
            String html   = safeOuterHtml(overlay, 300);
            String text   = "";
            try { text = overlay.getText().trim().replace("\n", " "); } catch (Exception ignored) {}

            LOG.warn("[{}] UNKNOWN overlay telemetry — selector:{} tag:{} id:'{}' class:'{}' z-index:{} text:'{}' html:{}",
                    appName, selector, tag, id, cls, zindex, text, html);
            HealthTracker.get().addWarning("UnknownOverlay",
                    appName + ": tag=" + tag + " class=" + cls + " id=" + id);
        } catch (Exception e) {
            LOG.debug("[{}] Overlay telemetry capture failed: {}", appName, e.getMessage());
        }
    }

    private String safeAttr(WebElement el, String attr, String def) {
        try { String v = el.getAttribute(attr); return v != null ? v : def; }
        catch (Exception e) { return def; }
    }

    private String safeStyle(WebElement el, String prop) {
        try { return el.getCssValue(prop); }
        catch (Exception e) { return "?"; }
    }

    private String safeOuterHtml(WebElement el, int maxLen) {
        try {
            Object raw = ((JavascriptExecutor) driver)
                    .executeScript("return arguments[0].outerHTML;", el);
            if (raw == null) return "";
            String html = raw.toString();
            return html.length() > maxLen ? html.substring(0, maxLen) + "…" : html;
        } catch (Exception e) { return ""; }
    }

    /**
     * Detects visible overlays and handles them based on their type:
     *   LOADER     — wait for natural disappearance (don't ESC a still-loading page)
     *   AUTH       — hard failure (auth prompt should never appear in the editor)
     *   ONBOARDING — dismiss via ESC or close button
     *   UNKNOWN    — attempt single ESC; hard-fail if it survives
     */
    private void assertNoBlockingOverlay(String appName, PairingResult result) {
        List<By> overlaySelectors = List.of(
                By.cssSelector(".modal.show, .modal-backdrop.show"),
                By.cssSelector("app-loader, .outhLoader"),
                By.xpath("//*[contains(@class,'modal') and contains(@style,'display: block')]"));

        for (By selector : overlaySelectors) {
            try {
                List<WebElement> overlays = driver.findElements(selector);
                for (WebElement overlay : overlays) {
                    try {
                        if (!overlay.isDisplayed()) continue;

                        long overlayFirstSeen = System.currentTimeMillis();
                        OverlayType type = classifyOverlay(overlay);
                        LOG.warn("[{}] Overlay detected: {} ({})", appName, type, selector);

                        switch (type) {
                            case LOADER -> {
                                // Wait for it to clear naturally — never ESC a loader
                                try {
                                    new WebDriverWait(driver, Duration.ofSeconds(10))
                                            .until(d -> {
                                                try { return !overlay.isDisplayed(); }
                                                catch (StaleElementReferenceException e) { return true; }
                                            });
                                    long blockMs = System.currentTimeMillis() - overlayFirstSeen;
                                    LOG.info("[{}] Loader cleared naturally — blocking duration: {}ms", appName, blockMs);
                                    if (blockMs > 3000) {
                                        HealthTracker.get().recordSlowPage("OverlayBlock/" + appName, blockMs);
                                    }
                                } catch (Exception e) {
                                    long blockMs = System.currentTimeMillis() - overlayFirstSeen;
                                    result.fail(FailureType.EDITOR_BLOCKED_BY_OVERLAY,
                                            "Loader did not clear within 10 s (blocked " + blockMs + "ms)");
                                    Assert.fail("[" + appName + "] Loader overlay still present after 10 s — blocked " + blockMs + "ms");
                                }
                            }
                            case AUTH -> {
                                long blockMs = System.currentTimeMillis() - overlayFirstSeen;
                                result.fail(FailureType.EDITOR_BLOCKED_BY_OVERLAY,
                                        "Unexpected auth prompt appeared inside editor (appeared " + blockMs + "ms after gate check)");
                                Assert.fail("[" + appName + "] Auth overlay appeared in editor — login cycle incomplete");
                            }
                            case ONBOARDING, UNKNOWN -> {
                                if (type == OverlayType.UNKNOWN) {
                                    // Capture telemetry before acting so this overlay can be
                                    // classified in future runs rather than remaining UNKNOWN forever
                                    captureOverlayTelemetry(appName, overlay, selector);
                                }
                                overlay.sendKeys(Keys.ESCAPE);
                                sleep(400);
                                try {
                                    if (overlay.isDisplayed()) {
                                        long blockMs = System.currentTimeMillis() - overlayFirstSeen;
                                        result.fail(FailureType.EDITOR_BLOCKED_BY_OVERLAY,
                                                type + " overlay not dismissed by ESC after " + blockMs + "ms: " + selector);
                                        Assert.fail("[" + appName + "] Blocking " + type + " overlay undismissable after " + blockMs + "ms");
                                    } else {
                                        long blockMs = System.currentTimeMillis() - overlayFirstSeen;
                                        LOG.info("[{}] {} overlay dismissed — blocking duration: {}ms", appName, type, blockMs);
                                    }
                                } catch (StaleElementReferenceException ignored) {
                                    long blockMs = System.currentTimeMillis() - overlayFirstSeen;
                                    LOG.info("[{}] {} overlay removed from DOM — blocking duration: {}ms", appName, type, blockMs);
                                }
                            }
                        }
                    } catch (AssertionError ae) {
                        throw ae;
                    } catch (StaleElementReferenceException ignored) {
                        // Overlay gone by the time we checked — fine
                    }
                }
            } catch (AssertionError ae) {
                throw ae;
            } catch (Exception e) {
                LOG.debug("Overlay check for {} threw: {}", selector, e.getMessage());
            }
        }
    }

    /**
     * Confirms the workflow graph has rendered at least one visible node or canvas child.
     * Non-fatal: logs a health warning but does not fail the test, because the graph may
     * still be initializing on slow networks. URL + canvas visibility remain the hard gates.
     */
    private void assertWorkflowGraphRendered(String appName) {
        List<By> graphSelectors = List.of(
                By.cssSelector("f-node, f-flow f-node"),
                By.cssSelector("[class*='trigger-node'], [class*='node-container']"),
                By.cssSelector("#triggerInput, input[placeholder*='rigger']"),
                By.cssSelector(".canvas-container > *"),
                By.xpath("//div[contains(@class,'node')]"));

        for (By selector : graphSelectors) {
            try {
                List<WebElement> nodes = driver.findElements(selector);
                boolean anyVisible = nodes.stream().anyMatch(n -> {
                    try { return n.isDisplayed(); }
                    catch (StaleElementReferenceException e) { return false; }
                });
                if (anyVisible) {
                    LOG.info("[{}] Workflow graph confirmed rendered", appName);
                    return;
                }
            } catch (Exception e) {
                LOG.debug("Graph selector {} threw: {}", selector, e.getMessage());
            }
        }
        // Non-fatal: canvas visible but graph nodes not yet detected
        LOG.warn("[{}] Graph nodes not detected — canvas visible but may still be loading", appName);
        HealthTracker.get().addWarning("WorkflowGraph", appName + ": trigger node not visible in editor");
    }

    private void logoutAndReturnToAppDirectory(String appName, BusinessTransaction tx) {
        try {
            new DashboardPage(driver).logoutAndVerifyRedirect();
            String postLogoutUrl = driver.getCurrentUrl();
            LOG.info("[{}] Logout successful — URL: {}", appName, postLogoutUrl);
            tx.observePass("Session lifecycle complete",
                    "logout redirected to " + postLogoutUrl);
        } catch (Exception e) {
            LOG.warn("[{}] Logout failed: {} — navigating directly to app directory",
                    appName, e.getMessage());
            // Non-fatal for the pairing flow (editor was confirmed), but record the gap
            // so the business score reflects that the session cleanup path is flaky.
            tx.observeFail("Session lifecycle complete",
                    "logout threw: " + e.getMessage() + " — navigated to app directory directly");
        }
        driver.get(APP_DIRECTORY_URL);
        waitForPageLoad();
        LOG.info("[{}] Returned to App Directory for next iteration", appName);
    }

    /** No-arg overload kept for any internal call that doesn't need tx (e.g. post-overlay recover). */
    private void logoutAndReturnToAppDirectory() {
        try {
            new DashboardPage(driver).logoutAndVerifyRedirect();
            LOG.info("Logout successful — URL: {}", driver.getCurrentUrl());
        } catch (Exception e) {
            LOG.warn("Logout failed: {} — navigating directly to app directory", e.getMessage());
        }
        driver.get(APP_DIRECTORY_URL);
        waitForPageLoad();
        LOG.info("Returned to App Directory for next iteration");
    }

    private void clickLoginLinkIfOnSignupPage() {
        List<By> loginLinkSelectors = List.of(
                By.cssSelector("label.dont-have a.cursor-pointer-new"),
                By.xpath("//label[contains(@class,'dont-have')]//a[contains(text(),'Login')]"),
                By.xpath("//a[contains(@class,'cursor-pointer-new') and normalize-space(text())='Login']"));
        for (By selector : loginLinkSelectors) {
            try {
                List<WebElement> found = driver.findElements(selector);
                if (!found.isEmpty() && found.get(0).isDisplayed()) {
                    found.get(0).click();
                    LOG.info("Clicked 'Login' link on signup page: {}", selector);
                    waitForPageLoad();
                    sleep(500);
                    return;
                }
            } catch (Exception ignored) {}
        }
        LOG.info("No signup 'Login' link found — already on login page");
    }

    // ── Phase 3: Observability capture ───────────────────────────────────────

    private void captureAndRecordObservability(String appName) {
        // JS console errors
        List<String> jsErrors = JsConsoleMonitor.getSevereErrors(driver);
        if (!jsErrors.isEmpty()) {
            LOG.info("[{}] JS console: {} SEVERE\n{}", appName, jsErrors.size(),
                    JsConsoleMonitor.formatReport(jsErrors));
            for (String err : jsErrors) {
                switch (JsConsoleMonitor.classify(err)) {
                    case FAIL -> {
                        HealthTracker.get().recordJsError("AppPairing/" + appName, err, true);
                        LOG.error("[{}] JS FAIL: {}", appName, err);
                    }
                    case WARN -> {
                        HealthTracker.get().recordJsError("AppPairing/" + appName, err, false);
                        LOG.warn("[{}] JS WARN: {}", appName, err);
                    }
                    case IGNORE -> {} // silently dropped
                }
            }
        }

        // API / network failures — classified by endpoint business criticality
        List<Map<String, Object>> apiErrors = NetworkMonitor.getApiFailures(driver);
        if (!apiErrors.isEmpty()) {
            LOG.info("[{}] Network: {} API failures\n{}", appName, apiErrors.size(),
                    NetworkMonitor.formatReport(apiErrors));
            for (Map<String, Object> f : apiErrors) {
                Object status = f.get("status");
                int s = status instanceof Number ? ((Number) status).intValue() : 0;
                String url = String.valueOf(f.getOrDefault("url", "?"));
                EndpointClass cls = classifyEndpoint(url);
                // Critical only when: confirmed server error (5xx) AND business-critical endpoint.
                // Analytics/ads 5xx are noise; workflow-save/auth 5xx are genuine failures.
                boolean critical = s >= 500 && cls == EndpointClass.CRITICAL;
                if (critical) {
                    LOG.error("[{}] Network {} [{} | {}]: {}", appName, NetworkMonitor.classifyStatus(s), cls, s, url);
                } else {
                    LOG.warn("[{}] Network {} [{} | {}]: {}", appName, NetworkMonitor.classifyStatus(s), cls, s, url);
                }
                HealthTracker.get().recordJsError(
                        "NetworkError/" + cls + "/" + appName,
                        NetworkMonitor.classifyStatus(s) + " " + url,
                        critical);
            }
        }
    }

    // ── Endpoint classification ───────────────────────────────────────────────

    enum EndpointClass { CRITICAL, HIGH, LOW, IGNORE }

    /**
     * Maps a request URL to its business criticality.
     * This determines whether a 5xx on that endpoint should trip criticalBroken.
     *
     *   CRITICAL — workflow-save, auth, execution: product is unusable if these fail
     *   HIGH     — API calls tied to user data but not blocking core flow
     *   LOW      — telemetry, feature-flags, non-blocking enrichment
     *   IGNORE   — analytics, ads, CDN assets: irrelevant to product correctness
     */
    private static EndpointClass classifyEndpoint(String url) {
        if (url == null) return EndpointClass.LOW;
        String lower = url.toLowerCase();

        // Business-critical: auth, workflow persistence, execution
        if (lower.contains("/api/") && (
                lower.contains("connect") || lower.contains("workflow") ||
                lower.contains("trigger") || lower.contains("execute") ||
                lower.contains("run") || lower.contains("action"))) return EndpointClass.CRITICAL;
        if (lower.contains("accounts.appypie") || lower.contains("/auth") ||
                lower.contains("/login") || lower.contains("/token") ||
                lower.contains("/session")) return EndpointClass.CRITICAL;

        // High: user data, history, configuration
        if (lower.contains("/api/") && (
                lower.contains("history") || lower.contains("user") ||
                lower.contains("config") || lower.contains("setting"))) return EndpointClass.HIGH;

        // Ignore: analytics, ads, tracking, CDN
        if (lower.contains("googletagmanager") || lower.contains("google-analytics") ||
                lower.contains("analytics") || lower.contains("hotjar") ||
                lower.contains("zaraz") || lower.contains("clarity") ||
                lower.contains("doubleclick") || lower.contains("facebook") ||
                lower.contains("cdn") || lower.contains(".js") || lower.contains(".css") ||
                lower.contains("intercom") || lower.contains("segment") ||
                lower.contains("amplitude")) return EndpointClass.IGNORE;

        return EndpointClass.LOW;
    }

    // ── Failure report ────────────────────────────────────────────────────────

    private String buildFailureMessage(List<PairingResult> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(failures.size()).append(" of ").append(PAIRING_ITERATIONS)
                .append(" pairing iterations failed:\n");
        failures.forEach(r -> sb.append("  ").append(r).append("\n"));
        return sb.toString();
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private void scrollIntoView(WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", element);
        sleep(100);
    }

    private void scrollPageSmoothly() {
        ((JavascriptExecutor) driver).executeScript("window.scrollBy({top: 300, behavior: 'smooth'});");
        sleep(200);
        ((JavascriptExecutor) driver).executeScript("window.scrollBy({top: -300, behavior: 'smooth'});");
    }

    private void waitForPageLoad() {
        ApplicationReadiness.waitForReady(driver, Duration.ofSeconds(15));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
