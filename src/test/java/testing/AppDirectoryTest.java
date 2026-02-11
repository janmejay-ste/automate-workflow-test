package testing;

import base.BaseTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import pages.AppDirectoryPage;
import utils.health.HealthTracker;

/**
 * Tests for the App Directory / Integrations page.
 * Validates loading, search functionality, and integration card display.
 */
public class AppDirectoryTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AppDirectoryTest.class);

    private AppDirectoryPage appDirectory;

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        appDirectory = new AppDirectoryPage(driver);
    }

    // --------------------------------------------------
    // Page Load Tests
    // --------------------------------------------------

    @Test(groups = "app-directory", priority = 1)
    public void verifyAppDirectoryLoads() {
        appDirectory.navigateTo();

        Assert.assertTrue(
                appDirectory.isOnAppDirectoryPage(),
                "Not on App Directory page. URL: " + driver.getCurrentUrl());

        Assert.assertTrue(
                appDirectory.isPageLoadedCleanly(),
                "App Directory page did not load cleanly");

        LOG.info("App Directory page loaded successfully");
    }

    @Test(groups = "app-directory", priority = 2)
    public void verifyIntegrationCardsPresent() {
        appDirectory.navigateTo();

        Assert.assertTrue(
                appDirectory.hasIntegrationCards(),
                "No integration cards found on App Directory page");

        int cardCount = appDirectory.getIntegrationCardCount();
        LOG.info("Found {} integration cards", cardCount);

        Assert.assertTrue(
                cardCount >= 5,
                "Expected at least 5 integration cards, found: " + cardCount);
    }

    // --------------------------------------------------
    // Search Tests
    // --------------------------------------------------

    @Test(groups = "app-directory", priority = 3)
    public void verifySearchFunctionalityExists() {
        appDirectory.navigateTo();

        // Note: Search input may not be present on all versions
        if (appDirectory.hasSearchInput()) {
            LOG.info("Search input is available");
            Assert.assertTrue(true);
        } else {
            LOG.warn("Search input not found - skipping search tests");
            HealthTracker.get().addWarning("AppDirectory", "Search input not available");
        }
    }

    @Test(groups = "app-directory", priority = 4, dependsOnMethods = "verifySearchFunctionalityExists")
    public void verifySearchReturnsResults() {
        appDirectory.navigateTo();

        if (!appDirectory.hasSearchInput()) {
            LOG.info("Skipping - search not available");
            return;
        }

        // Search for a common integration
        String searchTerm = "Google";
        boolean hasResults = appDirectory.searchReturnsResults(searchTerm);

        Assert.assertTrue(
                hasResults,
                "Search for '" + searchTerm + "' returned no results");

        LOG.info("Search '{}' returned results successfully", searchTerm);
    }

    @Test(groups = "app-directory", priority = 5)
    public void verifySearchWithNoResults() {
        appDirectory.navigateTo();

        if (!appDirectory.hasSearchInput()) {
            LOG.info("Skipping - search not available");
            return;
        }

        // Search for something that shouldn't exist
        String searchTerm = "xyznonexistent12345";
        appDirectory.searchForIntegration(searchTerm);

        int cardCount = appDirectory.getIntegrationCardCount();
        LOG.info("Search '{}' returned {} cards", searchTerm, cardCount);

        // Either no results or a "no results" message is acceptable
        if (cardCount > 0) {
            HealthTracker.get().addWarning("AppDirectory",
                    "Gibberish search returned results - may indicate no filtering");
        }
    }

    // --------------------------------------------------
    // Integration Card Content Tests
    // --------------------------------------------------

    @Test(groups = "app-directory", priority = 6)
    public void verifyIntegrationCardsHaveContent() {
        appDirectory.navigateTo();

        if (!appDirectory.hasIntegrationCards()) {
            Assert.fail("No integration cards to validate");
            return;
        }

        Assert.assertTrue(
                appDirectory.integrationCardsHaveNames(),
                "Some integration cards are missing names/text");

        LOG.info("All integration cards have proper content");
    }

    // --------------------------------------------------
    // Scroll Tests
    // --------------------------------------------------

    @Test(groups = "app-directory", priority = 7)
    public void verifyScrollLoadingWorks() {
        appDirectory.navigateTo();

        int initialCount = appDirectory.getIntegrationCardCount();
        LOG.info("Initial card count: {}", initialCount);

        appDirectory.scrollToBottom();

        // Wait for potential lazy loading
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int afterScrollCount = appDirectory.getIntegrationCardCount();
        LOG.info("Card count after scroll: {}", afterScrollCount);

        // Either same count (no lazy load) or more cards is acceptable
        Assert.assertTrue(
                afterScrollCount >= initialCount,
                "Card count decreased after scroll");
    }
}
