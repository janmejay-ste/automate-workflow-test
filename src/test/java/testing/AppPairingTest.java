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
import utils.health.HealthTracker;

import java.time.Duration;
import java.util.List;

/**
 * Tests the app pairing functionality on the App Directory page.
 * Flow: Search first app → Click "+" icon → Select second app → Click Automate
 * Handles login redirects by navigating back.
 */
@TestCategory(type = TestType.REGRESSION, feature = "App Pairing")
public class AppPairingTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AppPairingTest.class);
    private static final String APP_DIRECTORY_URL = "https://www.appypieautomate.ai/integrate/app-directory";
    private static final int PAIRING_ITERATIONS = 6;

    private WebDriverWait wait;

    // First apps to search for
    private static final String[] FIRST_APPS = {
            "Zoho", "Zoom", "Slack", "Trello", "Google Sheets", "Shopify"
    };

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    @Test(groups = { "regression" }, priority = 1)
    public void testAppPairingSearchFlow() {
        LOG.info("Starting app pairing test with {} iterations", PAIRING_ITERATIONS);

        int successfulPairings = 0;
        int loginRedirects = 0;

        for (int i = 0; i < PAIRING_ITERATIONS; i++) {
            String firstApp = FIRST_APPS[i % FIRST_APPS.length];

            LOG.info("Iteration {}: Testing pairing starting with {}", i + 1, firstApp);

            try {
                boolean result = testSinglePairing(firstApp, i);
                if (result) {
                    successfulPairings++;
                    LOG.info("Pairing {} completed - stayed on site", firstApp);
                } else {
                    loginRedirects++;
                    LOG.info("Pairing {} redirected to login - navigated back", firstApp);
                }
            } catch (Exception e) {
                LOG.warn("Pairing with {} failed: {}", firstApp, e.getMessage());
                HealthTracker.get().addWarning("AppPairing",
                        "Pairing with " + firstApp + " failed: " + e.getMessage());
            }
        }

        LOG.info("App pairing test complete: {} successful, {} login redirects",
                successfulPairings, loginRedirects);

        // At least some pairings should complete (even if they redirect to login)
        Assert.assertTrue(successfulPairings + loginRedirects >= 2,
                "Expected at least 2 pairing attempts to complete, got: " +
                        (successfulPairings + loginRedirects));
    }

    private boolean testSinglePairing(String firstApp, int iteration) {
        driver.get(APP_DIRECTORY_URL);
        waitForPageLoad();
        LOG.info("Step 1: Navigated to App Directory");

        WebElement searchInput = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("input#appConnectName, input[placeholder*='Search Apps']")));
        searchInput.clear();
        searchInput.sendKeys(firstApp);
        LOG.info("Step 2: Searched for {}", firstApp);

        WebElement appCard = findAndClickAppCard(firstApp);
        if (appCard == null) {
            throw new RuntimeException("Could not find app card for: " + firstApp);
        }
        waitForPageLoad();
        LOG.info("Step 3: Clicked on {} app card", firstApp);

        clickPlusIcon();
        LOG.info("Step 4: Clicked + icon");

        clickSecondAppCard(iteration);
        waitForPageLoad();
        LOG.info("Step 5: Selected second app");

        clickAutomateButton();
        sleep(500);
        LOG.info("Step 6: Clicked Automate/Get Started button");

        String currentUrl = driver.getCurrentUrl();
        if (currentUrl.contains("login") || currentUrl.contains("register") ||
                currentUrl.contains("accounts.appypie")) {
            LOG.info("Redirected to login/register: {}", currentUrl);
            HealthTracker.get().recordFallback("PairingLoginRedirect", currentUrl);
            driver.navigate().back();
            waitForPageLoad();

            currentUrl = driver.getCurrentUrl();
            if (currentUrl.contains("login") || currentUrl.contains("register")) {
                driver.navigate().back();
                waitForPageLoad();
            }
            return false;
        }

        scrollPageSmoothly();
        return true;
    }

    private void clickPlusIcon() {
        List<By> plusIconSelectors = List.of(
                By.cssSelector("a[href='#selectConnectApp']"),
                By.cssSelector("a[href='#selectConnectApp'] span"),
                By.xpath("//a[contains(@href,'selectConnectApp')]"),
                By.xpath("//span[contains(text(),'+')]"),
                By.cssSelector(".plus-icon, .add-app-btn"));

        for (By selector : plusIconSelectors) {
            List<WebElement> elements = driver.findElements(selector);
            if (!elements.isEmpty() && elements.get(0).isDisplayed()) {
                WebElement plusIcon = elements.get(0);
                scrollIntoView(plusIcon);
                plusIcon.click();
                LOG.info("Clicked + icon using: {}", selector);
                return;
            }
        }

        LOG.info("No + icon found, scrolling to find app cards");
        ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 500);");
        sleep(300);
    }

    private void clickSecondAppCard(int iteration) {
        sleep(300);
        List<By> cardSelectors = List.of(
                By.cssSelector("div.app-box a, div.integration-card a"),
                By.cssSelector("a[href*='/integrations/']"),
                By.xpath("//div[contains(@class,'app')]//a[contains(@href,'integrations')]"),
                By.cssSelector("div.card-body a"));

        for (By selector : cardSelectors) {
            try {
                List<WebElement> cards = driver.findElements(selector);
                if (!cards.isEmpty()) {
                    int cardIndex = Math.min(iteration, cards.size() - 1);
                    WebElement card = cards.get(cardIndex);
                    scrollIntoView(card);
                    card.click();
                    LOG.info("Clicked app card #{} using: {}", cardIndex, selector);
                    return;
                }
            } catch (Exception e) {
            }
        }
        throw new RuntimeException("Could not find any second app cards to click");
    }

    private void clickAutomateButton() {
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
                WebElement button = elements.get(0);
                scrollIntoView(button);
                button.click();
                LOG.info("Clicked automate button: {}", selector);
                return;
            }
        }
        throw new RuntimeException("Could not find Automate/Get Started button");
    }

    private WebElement findAndClickAppCard(String appName) {
        List<By> cardSelectors = List.of(
                By.xpath("//p[contains(text(),'" + appName + "')]/ancestor::a"),
                By.xpath("//span[contains(text(),'" + appName + "')]/ancestor::a"),
                By.cssSelector("a[href*='" + appName.toLowerCase().replace(" ", "-") + "']"),
                By.xpath("//*[contains(text(),'" + appName + "')]/ancestor::div[contains(@class,'card')]//a"));

        for (By selector : cardSelectors) {
            try {
                WebElement card = driver.findElement(selector);
                scrollIntoView(card);
                try {
                    card.click();
                } catch (ElementNotInteractableException e) {
                    LOG.info("Using JS click for app card: {}", appName);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", card);
                }
                return card;
            } catch (NoSuchElementException e) {
            }
        }
        return null;
    }

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
        wait.until(d -> ((JavascriptExecutor) d)
                .executeScript("return document.readyState").equals("complete"));
        sleep(100);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
