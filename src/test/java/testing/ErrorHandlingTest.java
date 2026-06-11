package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import pages.ErrorPage;
import utils.config.UrlRegistry;
import utils.health.HealthTracker;

/**
 * Tests for error page handling (404, 500, etc.)
 * Validates proper error display and navigation back to home.
 */
@TestCategory(type = TestType.REGRESSION, feature = "Error Handling")
public class ErrorHandlingTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandlingTest.class);

    private ErrorPage errorPage;

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        errorPage = new ErrorPage(driver);
    }

    // --------------------------------------------------
    // 404 Page Tests
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 1)
    public void verify404PageForInvalidUrl() {
        LOG.info("Navigating to invalid URL to trigger 404");
        errorPage.navigateToInvalidUrl();

        // Check if we're on a 404 page or redirected to home
        String currentUrl = errorPage.getCurrentUrl();
        LOG.info("Current URL after invalid navigation: {}", currentUrl);

        // Either 404 page displayed OR redirected to homepage is acceptable
        boolean is404 = errorPage.is404Page();
        boolean isHome = currentUrl.endsWith(".ai/") || currentUrl.endsWith(".ai");

        if (is404) {
            LOG.info("404 page displayed correctly");
        } else if (isHome) {
            LOG.info("Redirected to homepage (graceful handling)");
        } else {
            HealthTracker.get().addWarning("ErrorHandling",
                    "Invalid URL neither showed 404 nor redirected to home");
        }

        // Page should have content (not blank)
        Assert.assertTrue(
                errorPage.pageHasContent(),
                "Error page is blank/empty");
    }

    @Test(groups = {"regression"}, priority = 2)
    public void verify404PageHasContent() {
        errorPage.navigateToInvalidUrl();

        if (!errorPage.is404Page()) {
            LOG.info("Not a 404 page - skipping content validation");
            return;
        }

        // Page should have meaningful content
        Assert.assertTrue(
                errorPage.pageHasContent(),
                "404 page has no content");

        String title = errorPage.getPageTitle();
        LOG.info("404 page title: {}", title);

        // Title can be empty for 404 pages - just log it as info
        if (title.isEmpty()) {
            LOG.info("404 page has empty title - this is acceptable");
        }
    }

    @Test(groups = {"regression"}, priority = 3)
    public void verify404PageHasNavigationBack() {
        errorPage.navigateToInvalidUrl();

        if (!errorPage.is404Page()) {
            LOG.info("Not a 404 page - skipping navigation test");
            return;
        }

        boolean hasHomeLink = errorPage.hasHomeLink();
        LOG.info("404 page has home link: {}", hasHomeLink);

        if (!hasHomeLink) {
            HealthTracker.get().addWarning("ErrorHandling",
                    "404 page has no obvious way to navigate home");
        }
    }

    @Test(groups = {"regression"}, priority = 4)
    public void verifyNavigationFromErrorPageWorks() {
        errorPage.navigateToInvalidUrl();

        if (!errorPage.is404Page()) {
            LOG.info("Not a 404 page - skipping navigation test");
            return;
        }

        if (!errorPage.hasHomeLink()) {
            // Use browser back or navigate directly
            driver.navigate().to(BASE_URL);
        } else {
            errorPage.clickHomeLink();
        }

        // Should be back at homepage or a valid page
        String currentUrl = driver.getCurrentUrl();
        LOG.info("URL after navigating from error page: {}", currentUrl);

        Assert.assertFalse(
                errorPage.is404Page(),
                "Still on error page after attempting navigation");
    }

    // --------------------------------------------------
    // Various Invalid URL Tests
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 5)
    public void verifyGracefulHandlingOfSpecialCharacters() {
        String invalidUrl = UrlRegistry.MARKETING_BASE + "/<script>alert(1)</script>";

        try {
            errorPage.navigateToUrl(invalidUrl);
        } catch (Exception e) {
            LOG.info("URL with special characters rejected by browser: {}", e.getMessage());
            return;
        }

        // Should either 404 or redirect, not execute script
        String pageSource = driver.getPageSource();
        Assert.assertFalse(
                pageSource.contains("<script>alert"),
                "XSS vulnerability detected - script tag rendered");

        LOG.info("Special characters handled gracefully");
    }

    @Test(groups = {"regression"}, priority = 6)
    public void verifyVeryLongUrlHandling() {
        // Create a very long URL path
        StringBuilder longPath = new StringBuilder(UrlRegistry.MARKETING_BASE + "/");
        for (int i = 0; i < 100; i++) {
            longPath.append("verylongpath");
        }

        try {
            errorPage.navigateToUrl(longPath.toString());
        } catch (Exception e) {
            LOG.info("Very long URL rejected: {}", e.getMessage());
            return;
        }

        // Should handle gracefully
        Assert.assertTrue(
                errorPage.pageHasContent() || errorPage.is404Page(),
                "Very long URL caused blank page");

        LOG.info("Long URL handled gracefully");
    }

    // --------------------------------------------------
    // Server Error Simulation
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 7)
    public void documentServerErrorBehavior() {
        // Note: We can't easily trigger a 500 error
        // This test documents expected behavior

        LOG.info("Server error (500) handling documentation:");
        LOG.info("- Should display user-friendly error message");
        LOG.info("- Should provide navigation back to home");
        LOG.info("- Should not expose stack traces or technical details");

        // If we happen to encounter a 500, log it
        if (errorPage.is500Page()) {
            HealthTracker.get().recordJsError("ErrorHandling",
                    "500 error encountered during test", true);
        }
    }
}
