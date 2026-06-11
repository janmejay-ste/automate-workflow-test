package pages;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import utils.config.UrlRegistry;

/**
 * Page Object for the App Directory / Integrations page.
 * URL: {@code UrlRegistry.MARKETING_BASE + "/integrate/app-directory"}
 * (formerly hardcoded to appypieautomate.ai; rebrand-migrated to flozic.ai).
 */
public class AppDirectoryPage {

    private static final Logger LOG = LoggerFactory.getLogger(AppDirectoryPage.class);
    private static final int PAGE_LOAD_TIMEOUT_SEC = 20;

    private final WebDriver driver;
    private final WaitUtils waits;

    // ---- Locators ----

    private final By searchInput = By
            .cssSelector("input#appConnectName, input[type='search'], input[placeholder*='Search']");
    private final By integrationCards = By
            .cssSelector("a[href*='/integrate/apps/'], a[href*='/integrations'], .integration-card, .app-card");
    private final By categoryFilters = By.cssSelector(".category-filter, [class*='category'], .filter-item, aside a");
    private final By loadingSpinner = By.cssSelector(".loading, .spinner, [class*='loading']");

    public AppDirectoryPage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SEC));
    }

    // --------------------------------------------------
    // Navigation
    // --------------------------------------------------

    public void navigateTo() {
        driver.get(UrlRegistry.MARKETING_BASE + "/integrate/app-directory");
        waitUntilLoaded();
    }

    public void waitUntilLoaded() {
        waitForDocumentReady();
        LOG.info("App Directory page loaded");
    }

    private void waitForDocumentReady() {
        new WebDriverWait(driver, Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SEC))
                .until(d -> ((JavascriptExecutor) d)
                        .executeScript("return document.readyState")
                        .equals("complete"));
    }

    // --------------------------------------------------
    // Page State Validation
    // --------------------------------------------------

    public boolean isOnAppDirectoryPage() {
        String url = driver.getCurrentUrl().toLowerCase();
        return url.contains("app-directory") || url.contains("integrations") || url.contains("integrate");
    }

    public boolean isPageLoadedCleanly() {
        waitUntilLoaded();
        String source = driver.getPageSource().toLowerCase();
        // Check for actual error pages, not just any occurrence of 'error' (which may
        // appear in CSS classes)
        return !source.contains("something went wrong")
                && !source.contains("page not found")
                && !source.contains("410 gone")
                && !source.contains("500 internal");
    }

    public boolean hasIntegrationCards() {
        try {
            List<WebElement> cards = driver.findElements(integrationCards);
            LOG.info("Found {} integration cards", cards.size());
            return !cards.isEmpty();
        } catch (Exception e) {
            LOG.warn("Error finding integration cards: {}", e.getMessage());
            return false;
        }
    }

    public int getIntegrationCardCount() {
        return driver.findElements(integrationCards).size();
    }

    // --------------------------------------------------
    // Search Functionality
    // --------------------------------------------------

    public boolean hasSearchInput() {
        try {
            return driver.findElement(searchInput).isDisplayed();
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    public void searchForIntegration(String query) {
        WebElement input = waits.waitForVisible(searchInput);
        input.clear();
        input.sendKeys(query);

        // Wait for search results to update
        try {
            Thread.sleep(1000); // Allow debounce
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        LOG.info("Searched for integration: {}", query);
    }

    public boolean searchReturnsResults(String query) {
        searchForIntegration(query);

        // Wait for loading to complete
        waitForSearchResults();

        List<WebElement> results = driver.findElements(integrationCards);
        LOG.info("Search '{}' returned {} results", query, results.size());
        return !results.isEmpty();
    }

    private void waitForSearchResults() {
        try {
            // Wait for spinner to disappear if present
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(d -> d.findElements(loadingSpinner).isEmpty()
                            || !d.findElement(loadingSpinner).isDisplayed());
        } catch (Exception e) {
            // No spinner found, continue
        }
    }

    // --------------------------------------------------
    // Category Filters
    // --------------------------------------------------

    public boolean hasCategoryFilters() {
        try {
            return !driver.findElements(categoryFilters).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public List<WebElement> getCategoryFilters() {
        return driver.findElements(categoryFilters);
    }

    // --------------------------------------------------
    // Integration Card Details
    // --------------------------------------------------

    public boolean integrationCardsHaveNames() {
        List<WebElement> cards = driver.findElements(integrationCards);
        int cardsWithText = 0;

        for (WebElement card : cards) {
            String text = card.getText();
            // Also check for title/aria-label attributes or h3 children
            if (text == null || text.trim().isEmpty()) {
                text = card.getAttribute("title");
            }
            if (text == null || text.trim().isEmpty()) {
                text = card.getAttribute("aria-label");
            }
            if (text != null && !text.trim().isEmpty()) {
                cardsWithText++;
            }
        }

        LOG.info("Found {}/{} integration cards with names", cardsWithText, cards.size());
        // Pass if majority of cards have text (some may be icons only)
        return cards.isEmpty() || cardsWithText >= cards.size() / 2;
    }

    public boolean integrationCardsHaveImages() {
        List<WebElement> cards = driver.findElements(integrationCards);

        for (WebElement card : cards) {
            try {
                List<WebElement> images = card.findElements(By.tagName("img"));
                if (images.isEmpty()) {
                    LOG.warn("Found integration card with no image");
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    // --------------------------------------------------
    // Scroll & Load More
    // --------------------------------------------------

    public void scrollToBottom() {
        ((JavascriptExecutor) driver)
                .executeScript("window.scrollTo(0, document.body.scrollHeight)");
    }

    public void scrollToElement(WebElement element) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView(true);", element);
    }
}
