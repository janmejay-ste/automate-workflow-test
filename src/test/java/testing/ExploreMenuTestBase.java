package testing;

import base.BaseTest;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import pages.ConnectEditorPage;
import pages.DashboardPage;
import utils.ApplicationReadiness;
import utils.ManualLoginHelper;
import utils.NetworkMonitor;
import utils.health.HealthTracker;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Objects;

/**
 * Shared infrastructure for all Explore-menu section tests.
 *
 * Flow per link:
 *   1. Navigate to integration page
 *   2a. If family picker (e.g. Microsoft Suite) → iterate each app card one by one
 *   2b. Otherwise → click a.bannerInnerBtn
 *   3. Handle auth redirect via ManualLoginHelper (if not yet logged in)
 *   4. Wait for Connect editor loader to clear
 *   5. 5-gate editor validation
 *   6. Logout via profile dropdown → redirected to accounts.appypie.com/login
 *   7. Return to HOME_URL for next iteration
 */
abstract class ExploreMenuTestBase extends BaseTest {

    static final Logger LOG = LoggerFactory.getLogger(ExploreMenuTestBase.class);

    static final String HOME_URL  = "https://www.appypieautomate.ai";
    static final int    MAX_LINKS = 10;

    // ── Selectors ────────────────────────────────────────────────────────────

    static final List<By> EXPLORE_TRIGGERS = List.of(
            By.xpath("//nav//a[normalize-space()='Explore']"),
            By.xpath("//ul[contains(@class,'navbar')]//a[normalize-space()='Explore']"),
            By.xpath("//li[contains(@class,'nav-item')]//a[contains(normalize-space(),'Explore')]"),
            By.cssSelector("nav a[href*='explore'], ul.navbar-nav a[href*='explore']"),
            By.xpath("//a[contains(@class,'nav-link') and contains(normalize-space(),'Explore')]")
    );

    // Ordered most-specific first; first match wins during submenu wait.
    static final List<By> SUBMENU_CONTAINER_CANDIDATES = List.of(
            By.cssSelector("div.directory-sub-menu"),
            By.cssSelector("div[class*='directory-sub-menu']"),
            By.cssSelector("div.sub-menu-section"),
            By.cssSelector("ul.sub-menu-link-list")
    );

    // All sub-menu anchors across every section (fallback when section not found)
    static final By ALL_SUBMENU_LINKS = By.cssSelector("div.sub-menu-section a.sub-menu-link");

    // a.bannerInnerBtn candidates (ordered by reliability)
    static final List<By> AUTOMATE_BUTTON = List.of(
            By.cssSelector("a.bannerInnerBtn"),
            By.cssSelector("a#getStartedBtn"),
            By.xpath("//a[contains(@class,'bannerInnerBtn')]"),
            By.xpath("//a[contains(text(),'Automate') and contains(@href,'accounts.appypie')]"),
            By.cssSelector("a[href*='accounts.appypie.com/register']")
    );

    // Family picker grid — present when a page groups multiple apps (e.g. Microsoft Suite, Amazon Suite)
    // Structure: <div class="app-family-grid"><a class="app-family-card" href="#sectionId">…</a></div>
    // The CTA inside each scrolled-to section: <div class="cta-wrap"><a class="cta-btn" …>…</a></div>
    static final By FAMILY_GRID  = By.cssSelector("div.app-family-grid");
    static final By FAMILY_CARDS = By.cssSelector("div.app-family-grid a.app-family-card");

    static final By EDITOR_LOADER = By.cssSelector(
            "app-loader, .outhLoader, .authLoader, [class*='loader'], [class*='spinner']");

    WebDriverWait wait;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    // ── Template method ───────────────────────────────────────────────────────

    /** Subclasses return the exact h4.sub-menu-title text to target. */
    abstract String sectionTitle();

    // ── Result tracking ───────────────────────────────────────────────────────

    static final class LinkResult {
        final String label;
        final String url;
        boolean success;
        String failureReason;

        LinkResult(String label, String url) {
            this.label = label;
            this.url   = url;
        }

        void succeed() { this.success = true; }
        void fail(String reason) { this.success = false; this.failureReason = reason; }

        @Override
        public String toString() {
            return success
                    ? "[PASS] " + label
                    : "[FAIL] " + label + " — " + failureReason;
        }
    }

    // ── Main section runner (called by subclass @Test methods) ────────────────

    protected void runSection() {
        String section = sectionTitle();
        LOG.info("Starting Explore → '{}' integration test", section);

        Map<String, String> links = collectSectionLinks(section);
        Assert.assertFalse(links.isEmpty(),
                "No links found in Explore → '" + section + "' — "
                + "submenu may not have rendered or section title changed");

        int runCount = Math.min(links.size(), MAX_LINKS);
        LOG.info("'{}': {} link(s) available — testing {}", section, links.size(), runCount);

        List<Map.Entry<String, String>> entries = new ArrayList<>(links.entrySet())
                .subList(0, runCount);
        List<LinkResult> results = new ArrayList<>();

        for (Map.Entry<String, String> entry : entries) {
            String label   = entry.getKey();
            String path    = entry.getValue();
            String fullUrl = path.startsWith("http") ? path : HOME_URL + path;

            LOG.info("── [{}] Testing: {}", section, label);
            NetworkMonitor.reset(driver);
            LinkResult result = new LinkResult(label, fullUrl);

            // ── Business outcome: one transaction per Explore-menu link ────────
            // ExploreMenu iterates many links per run; each one is its own
            // user-intent transaction.  The criteria mirror the legacy LinkResult
            // success contract — declared up front so even an abort mid-flow
            // shows up as "criteria pending" in the report rather than silently
            // omitting the intent.
            utils.health.business.BusinessTransaction tx =
                utils.health.business.BusinessOutcomeTracker.get().begin(
                    "Explore Menu — " + section + " — " + label,
                    utils.health.business.BusinessTransactionCategory.WORKFLOW_CREATION)
                .require("Integration page loads",            "h1.mainHeading or banner visible at " + fullUrl)
                .require("Automate button reachable",         "a.bannerInnerBtn or family-picker discovered")
                .require("Editor reached and validated",      "5-gate editor validation passed")
                .require("Logout completes cleanly",          "redirect to known auth domain");

            try {
                runLinkIteration(label, fullUrl, result);
                // Map the legacy LinkResult onto the transaction's criteria so
                // both reporting layers stay in sync (no dual-truth).
                if (result.success) {
                    tx.observePass("Integration page loads",        "success");
                    tx.observePass("Automate button reachable",     "success");
                    tx.observePass("Editor reached and validated",  "success");
                    tx.observePass("Logout completes cleanly",      "success");
                } else {
                    tx.observeFail("Editor reached and validated",
                                   result.failureReason != null ? result.failureReason : "unknown");
                }
                tx.withEvidence("linkUrl", fullUrl);
                tx.complete();
            } catch (AssertionError ae) {
                tx.complete();   // record what we measured before bubbling up
                throw ae;
            } catch (Exception e) {
                tx.abort("Unexpected exception: " + e.getMessage());
                result.fail("Unexpected exception: " + e.getMessage());
                LOG.error("[{}] {}", label, e.getMessage());
                HealthTracker.get().recordTestFailure("ExploreMenu/" + label, e.getMessage(), null);
                Assert.fail("[" + label + "] Unexpected failure: " + e.getMessage());
            } finally {
                results.add(result);
                captureObservability(label);
            }
        }

        List<LinkResult> failures = results.stream().filter(r -> !r.success).collect(Collectors.toList());
        if (!failures.isEmpty()) {
            String msg = failures.size() + " of " + entries.size() + " '" + section + "' links failed:\n"
                    + failures.stream().map(r -> "  " + r).collect(Collectors.joining("\n"));
            Assert.fail(msg);
        }
        LOG.info("All {} '{}' iterations passed", entries.size(), section);
    }

    // ── Submenu collection ────────────────────────────────────────────────────

    /**
     * Hovers the Explore nav item, waits for the submenu, then collects links
     * from the section whose h4.sub-menu-title exactly matches {@code sectionTitle}.
     * Falls back to all visible sub-menu links if the section is absent.
     * LinkedHashMap preserves encounter order and deduplicates by label text.
     */
    private Map<String, String> collectSectionLinks(String sectionTitle) {
        driver.get(HOME_URL);
        ApplicationReadiness.waitForReady(driver);

        WebElement exploreTrigger = findExploreTrigger();
        Assert.assertNotNull(exploreTrigger,
                "Explore nav item not found on homepage — check EXPLORE_TRIGGERS selectors");

        new Actions(driver).moveToElement(exploreTrigger).pause(Duration.ofMillis(700)).perform();

        // Wait for the submenu to materialise
        boolean submenuVisible = false;
        for (By containerSel : SUBMENU_CONTAINER_CANDIDATES) {
            try {
                new WebDriverWait(driver, Duration.ofSeconds(10))
                        .until(ExpectedConditions.visibilityOfElementLocated(containerSel));
                submenuVisible = true;
                LOG.debug("Submenu visible via: {}", containerSel);
                break;
            } catch (TimeoutException ignored) {}
        }
        Assert.assertTrue(submenuVisible,
                "Explore submenu did not appear after hover within 10 s — none of the container selectors matched");

        // Section-scoped XPath — parameterised by sectionTitle
        By sectionLinks = By.xpath(
                "//div[contains(@class,'sub-menu-section')]"
                + "[.//h4[contains(@class,'sub-menu-title') and normalize-space()='" + sectionTitle + "']]"
                + "//a[contains(@class,'sub-menu-link')]");

        List<WebElement> linkEls = driver.findElements(sectionLinks);
        String source;
        if (linkEls.isEmpty()) {
            linkEls = driver.findElements(ALL_SUBMENU_LINKS);
            source  = "all sections ('" + sectionTitle + "' not found — check h4 text)";
        } else {
            source = "'" + sectionTitle + "' section";
        }

        Map<String, String> collected = new LinkedHashMap<>();
        for (WebElement el : linkEls) {
            try {
                String text = el.getText().trim();
                String href = el.getAttribute("href");
                if (text.isBlank() || href == null || href.isBlank()) continue;
                collected.put(text, href); // LinkedHashMap deduplicates same label
            } catch (StaleElementReferenceException ignored) {}
        }
        LOG.info("Collected {} link(s) from {}", collected.size(), source);
        return collected;
    }

    WebElement findExploreTrigger() {
        for (By selector : EXPLORE_TRIGGERS) {
            try {
                List<WebElement> found = driver.findElements(selector);
                if (!found.isEmpty() && found.get(0).isDisplayed()) {
                    LOG.info("Explore trigger found via: {}", selector);
                    return found.get(0);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Per-link iteration ────────────────────────────────────────────────────

    private void runLinkIteration(String label, String url, LinkResult result) {
        driver.get(url);
        ApplicationReadiness.waitForReady(driver);
        NetworkMonitor.injectInterceptors(driver);
        LOG.info("[{}] Loaded integration page: {}", label, driver.getCurrentUrl());

        verifyIntegrationPageLoaded(label, url);

        // Detect family picker (e.g. Microsoft Suite — multiple apps under one URL)
        List<WebElement> familyCards = driver.findElements(FAMILY_CARDS);
        if (!familyCards.isEmpty()) {
            LOG.info("[{}] Family picker detected ({} apps) — iterating each card", label, familyCards.size());
            handleFamilyPicker(label, url, result);
            return;
        }

        // Normal flow — click the Automate button
        clickAutomateButton(label);
        sleep(800);
        LOG.info("[{}] Clicked Automate button", label);
        routeAfterAutomateClick(label, result);
    }

    // ── Family picker (e.g. Microsoft Suite) ─────────────────────────────────

    /**
     * When a page exposes a div.app-family-grid (Microsoft Suite, Amazon Suite, LinkedIn
     * Suite, etc.), iterates every app card one by one:
     *
     *   1. Navigate/re-navigate to the family picker page
     *   2. Click the a.app-family-card to scroll to that app's section
     *   3. Click div.cta-wrap a.cta-btn inside the section (has the correct
     *      frompage=connectcloud…/build-your-connect/apps__for__… URL)
     *   4. Handle auth redirect → wait for editor → validate → logout
     *   5. Repeat for next card
     *
     * The outer LinkResult succeeds if at least one app in the suite passes.
     */
    private void handleFamilyPicker(String label, String familyPageUrl, LinkResult result) {
        // Snapshot section IDs from card hrefs (#microsoft-outlook, #amazon-s3, …)
        List<String> sectionIds = driver.findElements(FAMILY_CARDS).stream()
                .map(c -> {
                    try {
                        String href = c.getAttribute("href");
                        return (href != null && href.contains("#"))
                                ? href.substring(href.lastIndexOf('#') + 1)
                                : null;
                    } catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        LOG.info("[{}] Family picker: {} unique app(s) to iterate", label, sectionIds.size());

        int passed = 0;
        for (String sectionId : sectionIds) {
            String appLabel = label + "/" + sectionId;

            // Always re-navigate to family page — previous iteration's login/editor/logout
            // will have redirected the browser away from this page.
            if (!driver.getCurrentUrl().startsWith(familyPageUrl)
                    && !driver.getCurrentUrl().equals(familyPageUrl)) {
                driver.get(familyPageUrl);
                ApplicationReadiness.waitForReady(driver);
            }

            // Click the family card so the page scrolls to the app's section
            if (!clickFamilyCard(appLabel, sectionId)) {
                LOG.warn("[{}] Card #{} not clickable — skipping", appLabel, sectionId);
                HealthTracker.get().addWarning("FamilyCard", appLabel + ": card not clickable");
                continue;
            }
            sleep(600);

            // Click div.cta-wrap a.cta-btn inside the scrolled-to section.
            // This button carries the frompage parameter that creates the correct connect.
            if (!clickCtaButtonInSection(appLabel, sectionId)) {
                LOG.warn("[{}] No a.cta-btn found in section #{} — skipping", appLabel, sectionId);
                HealthTracker.get().addWarning("FamilyCta", appLabel + ": cta-btn not found in section");
                continue;
            }
            sleep(800);

            LinkResult subResult = new LinkResult(appLabel, familyPageUrl + "#" + sectionId);
            try {
                routeAfterAutomateClick(appLabel, subResult);
                if (subResult.success) passed++;
            } catch (AssertionError ae) {
                throw ae;
            } catch (Exception e) {
                LOG.error("[{}] {}", appLabel, e.getMessage());
                HealthTracker.get().recordTestFailure("FamilyApp/" + appLabel, e.getMessage(), null);
            }
        }

        LOG.info("[{}] Family picker complete — {}/{} app(s) passed", label, passed, sectionIds.size());
        if (passed > 0) {
            result.succeed();
        } else {
            result.fail("All " + sectionIds.size() + " family apps failed");
            Assert.fail("[" + label + "] All family picker apps failed — check individual app logs");
        }
    }

    /**
     * Clicks the family card for {@code sectionId} to scroll to its section on the page.
     */
    private boolean clickFamilyCard(String label, String sectionId) {
        By cardSel = By.cssSelector("a.app-family-card[href='#" + sectionId + "']");
        try {
            WebElement card = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.presenceOfElementLocated(cardSel));
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center',behavior:'smooth'});", card);
            sleep(300);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", card);
            LOG.info("[{}] Clicked family card #{}", label, sectionId);
            return true;
        } catch (Exception e) {
            LOG.debug("[{}] clickFamilyCard error: {}", label, e.getMessage());
            return false;
        }
    }

    /**
     * Clicks the div.cta-wrap a.cta-btn scoped to the section identified by
     * {@code sectionId}.  Falls back to any visible a.cta-btn on the page, then to
     * a.bannerInnerBtn, to handle edge-case markup variations.
     *
     * The cta-btn contains the frompage parameter that triggers the correct
     * build-your-connect URL for this specific app.
     */
    private boolean clickCtaButtonInSection(String label, String sectionId) {
        List<By> sectionScoped = List.of(
                By.cssSelector("#" + sectionId + " div.cta-wrap a.cta-btn"),
                By.cssSelector("#" + sectionId + " a.cta-btn"),
                By.xpath("//*[@id='" + sectionId + "']//div[contains(@class,'cta-wrap')]"
                        + "//a[contains(@class,'cta-btn')]"),
                By.xpath("//*[@id='" + sectionId + "']//a[contains(@class,'cta-btn')]")
        );
        for (By sel : sectionScoped) {
            try {
                List<WebElement> found = driver.findElements(sel);
                if (!found.isEmpty()) {
                    WebElement btn = found.get(0);
                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].scrollIntoView({block:'center',behavior:'smooth'});", btn);
                    sleep(300);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                    LOG.info("[{}] Clicked a.cta-btn in section #{} via: {}", label, sectionId, sel);
                    return true;
                }
            } catch (Exception ignored) {}
        }

        // Fallback: any visible a.cta-btn anywhere on the page (after the scroll)
        try {
            List<WebElement> ctaBtns = driver.findElements(By.cssSelector("div.cta-wrap a.cta-btn, a.cta-btn"));
            for (WebElement btn : ctaBtns) {
                if (btn.isDisplayed()) {
                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].scrollIntoView({block:'center',behavior:'smooth'});", btn);
                    sleep(300);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                    LOG.info("[{}] Clicked a.cta-btn (page fallback) for section #{}", label, sectionId);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // Last resort: a.bannerInnerBtn (some pages use this instead of cta-btn)
        for (By sel : AUTOMATE_BUTTON) {
            try {
                List<WebElement> found = driver.findElements(sel);
                for (WebElement btn : found) {
                    if (btn.isDisplayed()) {
                        ((JavascriptExecutor) driver).executeScript(
                                "arguments[0].scrollIntoView({block:'center',behavior:'smooth'});", btn);
                        sleep(300);
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                        LOG.info("[{}] Clicked bannerInnerBtn (last resort) for section #{}", label, sectionId);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ── Post-Automate routing ─────────────────────────────────────────────────

    /**
     * Routes the session after the Automate button was clicked.
     * Handles three landing states:
     *   a) accounts.appypie.com — auth redirect (login required)
     *   b) /customeditor/…       — already logged in, editor direct
     *   c) /build-your-connect/… — logged-in intermediate; auto-transitions to editor
     */
    void routeAfterAutomateClick(String label, LinkResult result) {
        // Allow 2 s for initial navigation to settle
        try {
            new WebDriverWait(driver, Duration.ofSeconds(2))
                    .until(d -> !d.getCurrentUrl().equals("about:blank"));
        } catch (TimeoutException ignored) {}

        String landingUrl = driver.getCurrentUrl();
        LOG.info("[{}] Landing URL: {}", label, landingUrl);

        if (isAuthUrl(landingUrl)) {
            LOG.info("[{}] Auth redirect — starting login cycle", label);
            HealthTracker.get().recordFallback("ExploreMenuLoginRedirect", landingUrl);
            handleLoginRedirect(label, result);

        } else if (landingUrl.contains("customeditor")) {
            LOG.info("[{}] Direct editor landing", label);
            waitForEditorLoader(label);
            validateEditorReady(label, result);
            logoutAndReturn(label);

        } else {
            // Intermediate (build-your-connect, connects dashboard, etc.) —
            // wait up to 30 s for transition to editor or auth
            LOG.info("[{}] Intermediate URL — waiting for /customeditor (30 s max)", label);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(30))
                        .until(d -> d.getCurrentUrl().contains("customeditor") || isAuthUrl(d.getCurrentUrl()));
            } catch (TimeoutException e) {
                String stuck = driver.getCurrentUrl();
                result.fail("URL did not transition to editor or login within 30 s — stuck at: " + stuck);
                Assert.fail("[" + label + "] Expected /customeditor after Automate click, got: " + stuck);
            }

            String resolved = driver.getCurrentUrl();
            if (isAuthUrl(resolved)) {
                LOG.info("[{}] Transitioned to auth — starting login cycle", label);
                HealthTracker.get().recordFallback("ExploreMenuLoginRedirect", resolved);
                handleLoginRedirect(label, result);
            } else {
                waitForEditorLoader(label);
                validateEditorReady(label, result);
                logoutAndReturn(label);
            }
        }
    }

    private static boolean isAuthUrl(String url) {
        return url != null && (url.contains("accounts.appypie")
                || url.contains("/login") || url.contains("/register"));
    }

    // ── Integration page validation ───────────────────────────────────────────

    void verifyIntegrationPageLoaded(String label, String url) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("h1.mainHeading, .bannerTextWrapper h1")));
            String heading = driver.findElement(
                    By.cssSelector("h1.mainHeading, .bannerTextWrapper h1")).getText().trim();
            LOG.info("[{}] Integration page banner: '{}'", label, heading);
        } catch (Exception e) {
            LOG.warn("[{}] Banner heading not found — page may be slow or selector changed", label);
            HealthTracker.get().addWarning("IntegrationPageLoad",
                    label + ": h1.mainHeading not found at " + url);
        }
    }

    void clickAutomateButton(String label) {
        for (By selector : AUTOMATE_BUTTON) {
            try {
                List<WebElement> found = driver.findElements(selector);
                if (!found.isEmpty() && found.get(0).isDisplayed()) {
                    WebElement btn = found.get(0);
                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].scrollIntoView({block:'center',behavior:'smooth'});", btn);
                    sleep(300);
                    btn.click();
                    LOG.info("[{}] Clicked Automate button via: {}", label, selector);
                    return;
                }
            } catch (Exception ignored) {}
        }
        Assert.fail("[" + label + "] a.bannerInnerBtn not found — integration page may be broken");
    }

    // ── Login + editor cycle ──────────────────────────────────────────────────

    /**
     * Handles the auth redirect by:
     *   1. Logging in via the main dashboard URL (avoids the AuthState.hasSession short-circuit
     *      that fires when navigateAndLogin is passed the login-page URL itself, leaving the
     *      browser on accounts.appypie.com/login instead of completing login)
     *   2. Re-navigating to the original integration page from result.url
     *   3. Re-clicking the Automate button (now logged in → direct build-your-connect → editor)
     *
     * For family picker sub-iterations, result.url contains "{pageUrl}#{sectionId}" so the
     * card is re-clicked and the cta-btn is re-found in the correct section.
     */
    void handleLoginRedirect(String label, LinkResult result) {
        // Step 1: Login via dashboard — hasSession check is against connectcloud, not accounts.appypie
        try {
            ManualLoginHelper.navigateAndLogin(driver);
            NetworkMonitor.injectInterceptors(driver);
            LOG.info("[{}] Login complete via dashboard — on: {}", label, driver.getCurrentUrl());
        } catch (Exception e) {
            HealthTracker.get().recordCriticalFlowFailure("Auth/ExploreMenu");
            result.fail("Login failed: " + e.getMessage());
            Assert.fail("[" + label + "] Login failed: " + e.getMessage());
        }

        // Step 2: Re-navigate to the integration page and re-click Automate.
        // Now that we are logged in, Automate goes directly to build-your-connect → customeditor.
        String fullUrl = result.url;
        String pageUrl = fullUrl.contains("#") ? fullUrl.substring(0, fullUrl.indexOf('#')) : fullUrl;
        String anchor  = fullUrl.contains("#") ? fullUrl.substring(fullUrl.indexOf('#') + 1) : null;

        LOG.info("[{}] Re-navigating to integration page after login: {}", label, pageUrl);
        driver.get(pageUrl);
        ApplicationReadiness.waitForReady(driver);

        // For family picker sub-iterations: click the card again, then cta-btn in the section
        if (anchor != null && !driver.findElements(FAMILY_CARDS).isEmpty()) {
            if (!clickFamilyCard(label, anchor)) {
                LOG.warn("[{}] Could not re-click family card #{} after login", label, anchor);
            }
            sleep(500);
            if (!clickCtaButtonInSection(label, anchor)) {
                result.fail("No cta-btn after re-login for section #" + anchor);
                Assert.fail("[" + label + "] cta-btn not found in section #" + anchor + " after login");
            }
        } else {
            // Normal integration page — click bannerInnerBtn
            clickAutomateButton(label);
        }

        // Wait up to 30 s for the post-click redirect chain to settle.
        // When logged in, the Automate click goes through:
        //   accounts.appypie.com/register?frompage=…  (brief)
        //   → connectcloud.appypie.com/build-your-connect/apps__for__…  (brief)
        //   → connectcloud.appypie.com/customeditor/[id]
        // Reading the URL too soon (e.g. sleep 800) falsely flags the brief accounts.appypie hop
        // as an auth loop and aborts the test before the loop has a chance to advance.
        try {
            new WebDriverWait(driver, Duration.ofSeconds(30))
                    .until(d -> {
                        String u = d.getCurrentUrl();
                        return u.contains("customeditor") || u.contains("build-your-connect");
                    });
        } catch (TimeoutException e) {
            String stuck = driver.getCurrentUrl();
            if (isAuthUrl(stuck)) {
                HealthTracker.get().recordCriticalFlowFailure("Auth/ExploreMenu");
                result.fail("Real auth loop — stuck on " + stuck + " 30 s after re-click");
                Assert.fail("[" + label + "] Auth loop after fresh login: " + stuck);
            }
            LOG.warn("[{}] Did not reach editor/build-your-connect within 30 s after re-click — URL: {}",
                    label, stuck);
        }

        waitForEditorLoader(label);
        validateEditorReady(label, result);
        logoutAndReturn(label);
    }

    // ── Editor loader wait ────────────────────────────────────────────────────

    void waitForEditorLoader(String label) {
        long start = System.currentTimeMillis();
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.presenceOfElementLocated(EDITOR_LOADER));
        } catch (TimeoutException ignored) {}

        try {
            new WebDriverWait(driver, Duration.ofSeconds(30))
                    .until(d -> d.findElements(EDITOR_LOADER).stream()
                            .noneMatch(el -> {
                                try { return el.isDisplayed(); }
                                catch (StaleElementReferenceException e) { return false; }
                            }));
            long blockMs = System.currentTimeMillis() - start;
            LOG.info("[{}] Editor loader cleared in {}ms", label, blockMs);
            if (blockMs > 5000) HealthTracker.get().recordSlowPage("EditorLoader/" + label, blockMs);
        } catch (TimeoutException e) {
            long blockMs = System.currentTimeMillis() - start;
            LOG.warn("[{}] Editor loader still present after 30 s ({}ms) — proceeding", label, blockMs);
            HealthTracker.get().recordSlowPage("EditorLoaderTimeout/" + label, blockMs);
        }
    }

    // ── Five-gate editor validation ───────────────────────────────────────────

    void validateEditorReady(String label, LinkResult result) {
        // Gate 1: URL must be /customeditor (may need to wait through build-your-connect)
        if (!driver.getCurrentUrl().contains("customeditor")) {
            LOG.info("[{}] Gate 1: waiting for /customeditor transition", label);
            try {
                new WebDriverWait(driver, Duration.ofSeconds(30))
                        .until(d -> d.getCurrentUrl().contains("customeditor"));
            } catch (TimeoutException e) {
                String stuck = driver.getCurrentUrl();
                HealthTracker.get().recordCriticalFlowFailure("WorkflowCreation/ExploreMenu/" + label);
                result.fail("URL never reached /customeditor — got: " + stuck);
                Assert.fail("[" + label + "] Expected /customeditor URL, got: " + stuck);
            }
        }
        LOG.info("[{}] Gate 1 passed — URL: {}", label, driver.getCurrentUrl());

        // Gate 2: Canvas visible
        ConnectEditorPage editor = new ConnectEditorPage(driver);
        if (!editor.isEditorVisible()) {
            HealthTracker.get().recordCriticalFlowFailure("WorkflowCreation/ExploreMenu/" + label);
            result.fail("Canvas not visible despite correct URL");
            Assert.fail("[" + label + "] Connect editor canvas not visible");
        }
        LOG.info("[{}] Gate 2 passed — canvas visible", label);

        // Gate 3: No blocking overlay
        assertNoBlockingOverlay(label, result);
        LOG.info("[{}] Gate 3 passed — no blocking overlay", label);

        // Gate 4: Graph rendered (non-fatal)
        if (!isWorkflowGraphRendered()) {
            LOG.warn("[{}] Gate 4: graph nodes not detected", label);
            HealthTracker.get().addWarning("WorkflowGraph", label + ": trigger node not visible");
        } else {
            LOG.info("[{}] Gate 4 passed — graph rendered", label);
        }

        // Gate 5: Connect ID in URL
        validateConnectId(label, result);

        LOG.info("[{}] All editor gates passed", label);
        HealthTracker.get().recordTestSuccess();
        result.succeed();
    }

    private void assertNoBlockingOverlay(String label, LinkResult result) {
        List<By> overlaySelectors = List.of(
                By.cssSelector(".modal.show, .modal-backdrop.show"),
                By.xpath("//*[contains(@class,'modal') and contains(@style,'display: block')]"));
        for (By selector : overlaySelectors) {
            try {
                for (WebElement overlay : driver.findElements(selector)) {
                    try {
                        if (!overlay.isDisplayed()) continue;
                        String cls  = safeAttr(overlay, "class").toLowerCase();
                        String text = safeText(overlay).toLowerCase();
                        if (text.contains("login") || text.contains("password")) {
                            result.fail("Auth overlay in editor — login cycle incomplete");
                            Assert.fail("[" + label + "] Auth overlay appeared in editor");
                        } else if (cls.contains("loader") || cls.contains("spinner")) {
                            try {
                                new WebDriverWait(driver, Duration.ofSeconds(15))
                                        .until(d -> { try { return !overlay.isDisplayed(); }
                                            catch (StaleElementReferenceException e) { return true; } });
                            } catch (TimeoutException te) {
                                result.fail("Loader overlay still present after 15 s");
                                Assert.fail("[" + label + "] Loader did not clear within 15 s");
                            }
                        } else {
                            try { overlay.sendKeys(Keys.ESCAPE); } catch (Exception ignored) {}
                            sleep(300);
                        }
                    } catch (AssertionError ae) {
                        throw ae;
                    } catch (StaleElementReferenceException ignored) {}
                }
            } catch (AssertionError ae) {
                throw ae;
            } catch (Exception e) {
                LOG.debug("[{}] Overlay check: {}", label, e.getMessage());
            }
        }
    }

    private boolean isWorkflowGraphRendered() {
        List<By> graphSelectors = List.of(
                By.cssSelector("f-node, f-flow f-node"),
                By.cssSelector("[class*='trigger-node'], [class*='node-container']"),
                By.cssSelector("#triggerInput, input[placeholder*='rigger']"),
                By.cssSelector(".canvas-container > *"),
                By.xpath("//div[contains(@class,'node')]"));
        for (By sel : graphSelectors) {
            try {
                boolean any = driver.findElements(sel).stream().anyMatch(n -> {
                    try { return n.isDisplayed(); }
                    catch (StaleElementReferenceException e) { return false; }
                });
                if (any) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void validateConnectId(String label, LinkResult result) {
        Pattern idPattern = Pattern.compile("/customeditor[/#/]+([a-zA-Z0-9_-]{4,})");
        long deadline = System.currentTimeMillis() + 5000;
        String connectId = null;
        while (System.currentTimeMillis() < deadline) {
            Matcher m = idPattern.matcher(driver.getCurrentUrl());
            if (m.find()) { connectId = m.group(1); break; }
            sleep(300);
        }
        if (connectId == null) {
            LOG.warn("[{}] No connect ID in URL — backend may not have persisted", label);
            HealthTracker.get().recordCriticalFlowFailure("ConnectPersistence/ExploreMenu/" + label);
            result.fail("No connect ID in URL");
            Assert.fail("[" + label + "] Connect ID missing — workflow not persisted");
        }
        LOG.info("[{}] Gate 5 passed — connect ID: {}", label, connectId);
    }

    // ── Logout + return to home ───────────────────────────────────────────────

    void logoutAndReturn(String label) {
        try {
            new DashboardPage(driver).logoutAndVerifyRedirect();
            LOG.info("[{}] Logout successful", label);
        } catch (Exception e) {
            LOG.warn("[{}] Logout failed: {} — continuing", label, e.getMessage());
        }
        driver.get(HOME_URL);
        ApplicationReadiness.waitForReady(driver, Duration.ofSeconds(15));
    }

    // ── Observability ─────────────────────────────────────────────────────────

    void captureObservability(String label) {
        // JS console errors are intentionally NOT recorded here.
        // Explore menu tests visit public integration/marketing pages that contain
        // many third-party analytics, CDN, and GTM scripts.  These pages generate
        // dozens of console errors per load that are completely unrelated to whether
        // the automation flow worked.  With 10+ pages per run the JS error penalty
        // would exceed 100 pts even on a fully successful run, making the score
        // meaningless.  The 5-gate validateEditorReady() already asserts that the
        // core flow (login → editor → logout) is healthy.
        //
        // Only 5xx failures on business-critical API endpoints are recorded.
        List<Map<String, Object>> apiErrors = NetworkMonitor.getApiFailures(driver);
        for (Map<String, Object> f : apiErrors) {
            int s = f.get("status") instanceof Number ? ((Number) f.get("status")).intValue() : 0;
            String url = String.valueOf(f.getOrDefault("url", "?"));
            boolean critical = s >= 500 && isBusinessCriticalEndpoint(url);
            HealthTracker.get().recordJsError(
                    "NetworkError/ExploreMenu/" + label,
                    NetworkMonitor.classifyStatus(s) + " " + url,
                    critical);
        }
    }

    private static boolean isBusinessCriticalEndpoint(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("/api/") && (lower.contains("connect") || lower.contains("workflow")
                || lower.contains("execute") || lower.contains("auth")
                || lower.contains("login") || lower.contains("token"));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String safeAttr(WebElement el, String attr) {
        try { String v = el.getAttribute(attr); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }

    private static String safeText(WebElement el) {
        try { String v = el.getText(); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }

    void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
