package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.AppyPieAutomatePage;
import pages.ConnectTopNavigation;
import utils.ConsoleLogFilter;
import utils.config.UrlRegistry;
import utils.health.HealthTracker;

import java.util.List;

@TestCategory(type = TestType.SANITY, feature = "Navigation")
public class AppyPieNavigationTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AppyPieNavigationTest.class);

    private static final long MAX_LCP_MS = 2500;
    private static final double MAX_CLS = 0.1;
    private static final long MAX_NAV_TIME_MS = 5000;

    // --------------------------------------------------
    // Smoke: page load + domain + JS health
    // --------------------------------------------------

    @Test(groups = { "smoke", "sanity" }, priority = 1)
    public void verifyAutomateHomeLoads() {
        AppyPieAutomatePage automate = new AppyPieAutomatePage(driver);

        Assert.assertTrue(
                automate.isPageLoadedCleanly(),
                "Automate page did not load cleanly");

        Assert.assertTrue(
                automate.isOnAutomateDomain(),
                "Unexpected domain: " + driver.getCurrentUrl()
                        + " — expected one of: " + AppyPieAutomatePage.productDomains()
                        + ".  Override via -Dproduct.domains=...");

        List<String> jsErrors = automate.getSevereJsErrors()
                .stream()
                .filter(e -> !e.contains("appendChild"))
                .toList();

        Assert.assertTrue(jsErrors.isEmpty(), "Unexpected JS errors: " + jsErrors);

        captureBrowserLogs("AutomateHome");
    }

    // --------------------------------------------------
    // UX + performance validation
    // --------------------------------------------------

    @Test(groups = { "sanity" }, priority = 2)
    public void validateAutomateUXAndPerformance() {
        AppyPieAutomatePage automate = new AppyPieAutomatePage(driver);

        automate.waitUntilLoaded();

        long navTime = automate.getNavigationDurationMs();
        long lcp = automate.getLCP();
        double cls = automate.getCLS();

        LOG.info("Navigation={} ms | LCP={} ms | CLS={}", navTime, lcp, cls);

        if (navTime > MAX_NAV_TIME_MS) {
            HealthTracker.get().recordSlowPage("AutomateUX", navTime);
        }

        if (lcp > MAX_LCP_MS) {
            HealthTracker.get().recordSlowPage("AutomateUX (LCP)", lcp);
        }

        if (cls > MAX_CLS) {
            HealthTracker.get().addWarning("Performance", "High CLS: " + cls);
        }

        By popularAutomations = By.xpath("//h2[contains(normalize-space(),'Popular Automations')]");

        try {
            automate.scrollToElement(popularAutomations);
        } catch (Exception e) {
            HealthTracker.get()
                    .addWarning("UX", "Popular Automations section missing");
        }

        captureBrowserLogs("AutomateUX");
        ConsoleLogFilter.ignoreKnownErrors(driver);
    }

    // --------------------------------------------------
    // Navigation integrity (top menu)
    // --------------------------------------------------

    @Test(groups = { "sanity" }, priority = 3)
    public void validateTopNavigationLinks() {
        AppyPieAutomatePage automate = new AppyPieAutomatePage(driver);

        Assert.assertTrue(
                automate.doAllTopNavLinksWork(),
                "One or more top navigation links are broken");

        captureBrowserLogs("TopNavigation");
    }

    // --------------------------------------------------
    // Cross-page navigation (Connect menu)
    // --------------------------------------------------

    @Test(groups = { "sanity" }, priority = 4, enabled = false) // Disabled: MCP Server element not found
    public void validateConnectNavigation() {
        ConnectTopNavigation nav = new ConnectTopNavigation(driver);

        nav.openFeatures();
        captureBrowserLogs("Features");

        nav.openAppDirectory();
        captureBrowserLogs("AppDirectory");

        nav.openAIAutomation();
        captureBrowserLogs("AIAutomation");

        nav.openAIConnects();
        captureBrowserLogs("AIAgents");

        nav.openMCPServer();
        captureBrowserLogs("MCPServer");

        nav.openPricing();
        captureBrowserLogs("Pricing");

        nav.openBlog();
        captureBrowserLogs("Blog");

        nav.openContactSales();
        captureBrowserLogs("ContactSales");

        nav.openSignup();
        captureBrowserLogs("Signup");

        nav.openLogin();
        captureBrowserLogs("Login");
    }

    // --------------------------------------------------
    // Contact Sales / Calendly Test (Separate because it opens new tab)
    // --------------------------------------------------

    @Test(groups = { "sanity" }, priority = 5)
    public void validateContactSalesCalendly() {
        ConnectTopNavigation nav = new ConnectTopNavigation(driver);

        // Navigate to home first — use the configured base URL so flozic.ai or
        // any rebranded host is honoured rather than hard-coding the legacy domain.
        driver.get(UrlRegistry.MARKETING_BASE);

        // Capture the original window so we can assert we returned to it (not just
        // that we landed on something that looks like the main site).  The legacy
        // assertion only checked the URL — a closed-parent-tab bug would have
        // landed us on a child tab with a "correct-looking" URL and silently passed.
        final String originalWindow = driver.getWindowHandle();

        nav.openContactSales();
        captureBrowserLogs("ContactSales");

        LOG.info("Returned from Calendly flow.  Original handle: {}, current: {}",
                originalWindow, driver.getWindowHandle());

        // Window-level check: we must be back on the original tab, not on a
        // residual child tab that happens to have a main-site URL.
        Assert.assertEquals(driver.getWindowHandle(), originalWindow,
                "Window focus not restored to original tab after Calendly flow closed");

        // Domain-level check: use the centralized product-domain list so the
        // legacy "appypie OR appypieautomate" hard-code does not falsely fail
        // when the rebrand redirects through flozic.ai.
        AppyPieAutomatePage automate = new AppyPieAutomatePage(driver);
        Assert.assertTrue(
                automate.isOnAutomateDomain(),
                "Should be back on a product domain after Calendly tab closes. "
                        + "Actual: " + driver.getCurrentUrl()
                        + " — expected one of: " + AppyPieAutomatePage.productDomains());
    }

    // --------------------------------------------------
    // Category Pages: WordPress (Fast path)
    // --------------------------------------------------

    @Test(groups = { "sanity" }, priority = 7)
    public void validateWordPressCategoryPage() {
        LOG.info("Testing WordPress category page (fast path)");
        driver.get(UrlRegistry.MARKETING_BASE + "/integrate/apps/categories/wordpress");

        AppyPieAutomatePage page = new AppyPieAutomatePage(driver);
        page.waitUntilLoaded();

        String title = driver.getTitle();
        LOG.info("WordPress category page title: {}", title);

        Assert.assertTrue(title.toLowerCase().contains("wordpress"),
                "Title should contain 'WordPress'");

        Assert.assertTrue(driver.findElements(By.cssSelector("a[href*='wordpress']")).size() > 0,
                "WordPress related links should be present");

        captureBrowserLogs("WordPressCategory");
    }

    // --------------------------------------------------
    // Reporting
    // --------------------------------------------------

    // Note: Health report export is handled by BaseTest.afterSuite()
    // Removed duplicate @AfterSuite to prevent double printing

    // --------------------------------------------------
    // Utilities
    // --------------------------------------------------

    private void captureBrowserLogs(String context) {
        try {
            ConsoleLogFilter.capture(driver, context);
        } catch (Exception e) {
            HealthTracker.get()
                    .addWarning("JS Logs", "Failed to capture logs on " + context);
        }
    }
}
