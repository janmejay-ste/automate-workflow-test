package pages;

import java.time.Duration;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectTopNavigation {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectTopNavigation.class);

    private static final String BASE_URL = "https://www.appypieautomate.ai";

    private final WebDriver driver;
    private final WaitUtils waits;

    public ConnectTopNavigation(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(15));
    }

    // -------------------- Locators --------------------

    private final By features = By.cssSelector("a[title='features']");
    private final By appDirectory = By.cssSelector("a[title='App Directory']");
    private final By aiAutomation = By.cssSelector("a[title='AI Automation']");
    private final By aiConnects = By.cssSelector("a[title='AI Agents']");
    private final By mcpServer = By.cssSelector("a[title='MCP Server']");
    private final By pricing = By.cssSelector("a[title='Pricing']");
    private final By blog = By.cssSelector("a[title='Appy Pie Automate Blog']");
    private final By contactSales = By.cssSelector("a[title='Contact Sales']");
    private final By signup = By.cssSelector("a[title='Sign Up']");
    private final By login = By.cssSelector("a[title='log in']");

    // -------------------- Internal navigation --------------------

    private void clickAndVerifyInternal(By locator, String expectedUrlPart) {
        long start = System.currentTimeMillis();

        WebElement el = waits.waitForVisible(locator);
        el.click();

        waitForUrlContains(expectedUrlPart);

        LOG.info("Navigation [{}] completed in {} ms",
                expectedUrlPart,
                System.currentTimeMillis() - start);
    }

    // -------------------- External navigation --------------------

    private void clickVerifyAndReturn(By locator, String label) {
        long start = System.currentTimeMillis();

        // Only restore main page if we're not already on the automate domain
        String initialUrl = driver.getCurrentUrl();
        if (!initialUrl.contains("appypieautomate.ai") && !initialUrl.contains("appypie.com")) {
            restoreMainPage();
        }

        final String urlBeforeClick = driver.getCurrentUrl();
        String originalWindow = driver.getWindowHandle();
        int originalWindowCount = driver.getWindowHandles().size();

        WebElement el = waits.waitForVisible(locator);

        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView(true);", el);

        el.click();

        // Wait for either new tab OR navigation/state change
        new WebDriverWait(driver, Duration.ofSeconds(15)).until(d -> d.getWindowHandles().size() != originalWindowCount
                || !d.getCurrentUrl().equals(urlBeforeClick));

        // If new tab opened, wait for it to load completely then close it
        if (driver.getWindowHandles().size() > originalWindowCount) {
            // Get the new window handle (there should only be one new tab)
            String newWindow = null;
            for (String window : driver.getWindowHandles()) {
                if (!window.equals(originalWindow)) {
                    newWindow = window;
                    break;
                }
            }

            if (newWindow != null) {
                driver.switchTo().window(newWindow);

                // Wait for the new tab to fully load (especially for Calendly which is slow)
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(30))
                            .until(d -> {
                                String readyState = (String) ((JavascriptExecutor) d)
                                        .executeScript("return document.readyState");
                                return "complete".equals(readyState);
                            });

                    // Extra wait for Calendly to ensure the page is interactive
                    if (driver.getCurrentUrl().contains("calendly")) {
                        Thread.sleep(2000); // Allow Calendly iframe to initialize
                    }

                    LOG.info("New tab [{}] loaded: {}", label, driver.getCurrentUrl());
                } catch (Exception e) {
                    LOG.warn("Timeout waiting for new tab to load: {}", e.getMessage());
                }

                driver.close();
            }

            // Switch back and wait for main window
            driver.switchTo().window(originalWindow);

            // Wait for original window to be ready before continuing
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> ((JavascriptExecutor) d)
                            .executeScript("return document.readyState")
                            .equals("complete"));
        }

        LOG.info("Navigation [{}] completed in {} ms",
                label,
                System.currentTimeMillis() - start);
    }

    private void restoreMainPage() {
        driver.get(BASE_URL);

        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(d -> ((JavascriptExecutor) d)
                        .executeScript("return document.readyState")
                        .equals("complete"));

        waits.waitForVisible(signup);
    }

    private void waitForUrlContains(String fragment) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(d -> d.getCurrentUrl().toLowerCase().contains(fragment.toLowerCase()));
    }

    // -------------------- Public API --------------------

    public void openFeatures() {
        clickAndVerifyInternal(features, "features");
    }

    public void openAppDirectory() {
        clickAndVerifyInternal(appDirectory, "app-directory");
    }

    public void openAIAutomation() {
        clickVerifyAndReturn(aiAutomation, "ai-workflow-builder");
    }

    public void openAIConnects() {
        clickVerifyAndReturn(aiConnects, "ai-agents");
    }

    public void openMCPServer() {
        clickAndVerifyInternal(mcpServer, "mcp");
    }

    public void openPricing() {
        clickAndVerifyInternal(pricing, "pricing-plan");
    }

    public void openBlog() {
        clickVerifyAndReturn(blog, "blog");
    }

    public void openContactSales() {
        clickVerifyAndReturn(contactSales, "calendly");
    }

    public void openSignup() {
        clickVerifyAndReturn(signup, "register");
    }

    public void openLogin() {
        clickVerifyAndReturn(login, "login");
    }
}
