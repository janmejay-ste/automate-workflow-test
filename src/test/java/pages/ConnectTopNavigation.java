package pages;

import java.time.Duration;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.config.UrlRegistry;

public class ConnectTopNavigation {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectTopNavigation.class);

    private static final String BASE_URL = UrlRegistry.MARKETING_BASE;

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
    // Rebrand-resilient: structural selector survives "Appy Pie Automate Blog" -> "Flozic Blog" rename.
    private final By blog = By.cssSelector("a[href='/blog/'], a[href$='/blog/']");
    private final By contactSales = By.cssSelector(
            "a[title='Contact Sales'], a[title='contact sales'], " +
            "a[href*='calendly'], a[href*='contact-sales'], " +
            "a[href*='contact_sales'], nav a[class*='contact']");
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
        if (!UrlRegistry.isOwnedMarketingHost(initialUrl) && !initialUrl.contains("appypie.com")) {
            restoreMainPage();
        }

        final String urlBeforeClick = driver.getCurrentUrl();
        String originalWindow = driver.getWindowHandle();
        int originalWindowCount = driver.getWindowHandles().size();

        WebElement el = waits.waitForVisible(locator);

        // Premium: Smooth scroll into view with slight delay
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center', behavior: 'smooth'});", el);
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
        }

        try {
            // LOCK: Inject overlay on the original tab to prevent interaction
            LOG.info("Locking original tab for: {}", label);
            ((JavascriptExecutor) driver).executeScript(
                    "var lock = document.createElement('div');" +
                            "lock.id = 'tab-locking-overlay';" +
                            "lock.style.position = 'fixed';" +
                            "lock.style.top = '0'; lock.style.left = '0';" +
                            "lock.style.width = '100%'; lock.style.height = '100%';" +
                            "lock.style.backgroundColor = 'rgba(0,0,0,0.1)';" +
                            "lock.style.zIndex = '999999';" +
                            "lock.style.cursor = 'not-allowed';" +
                            "lock.innerHTML = '<div style=\"position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);font-family:sans-serif;color:#fff;background:rgba(0,0,0,0.7);padding:20px;border-radius:10px;\">Processing in new tab...</div>';"
                            +
                            "document.body.appendChild(lock);");

            // CLICK via JS to bypass overlay interception
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);

            // Wait for either new tab OR navigation/state change
            new WebDriverWait(driver, Duration.ofSeconds(15)).until(
                    d -> d.getWindowHandles().size() != originalWindowCount
                            || !d.getCurrentUrl().equals(urlBeforeClick));

            // If new tab opened, strictly wait for it to finish
            if (driver.getWindowHandles().size() > originalWindowCount) {
                String newWindow = null;
                for (String window : driver.getWindowHandles()) {
                    if (!window.equals(originalWindow)) {
                        newWindow = window;
                        break;
                    }
                }

                if (newWindow != null) {
                    driver.switchTo().window(newWindow);
                    LOG.info("Switched to new tab: {}", label);

                    try {
                        // Wait for new tab load
                        new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> ((JavascriptExecutor) d)
                                .executeScript("return document.readyState").equals("complete"));

                        // Delay for Calendly/External content
                        Thread.sleep(1500);

                        // Scrolling in the new tab for "each page" as requested
                        ((JavascriptExecutor) driver).executeScript("window.scrollTo({top: 500, behavior: 'smooth'});");
                        Thread.sleep(800);
                        ((JavascriptExecutor) driver).executeScript("window.scrollTo({top: 0, behavior: 'smooth'});");
                        Thread.sleep(500);

                        LOG.info("New tab [{}] interaction complete: {}", label, driver.getCurrentUrl());
                    } catch (Exception e) {
                        LOG.warn("Interaction in new tab failed: {}", e.getMessage());
                    }
                }
            }
        } finally {
            // Robust Fail-Safe: Close all windows except original and switch back
            for (String handle : driver.getWindowHandles()) {
                if (!handle.equals(originalWindow)) {
                    try {
                        driver.switchTo().window(handle);
                        driver.close();
                        LOG.info("Forcibly closed extra tab");
                    } catch (Exception ignored) {
                    }
                }
            }
            driver.switchTo().window(originalWindow);

            // UNLOCK: Remove the overlay
            LOG.info("Unlocking original tab");
            ((JavascriptExecutor) driver).executeScript(
                    "var lock = document.getElementById('tab-locking-overlay');" +
                            "if(lock) lock.remove();");

            // Explicit wait for stability
            try {
                new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                        d -> ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));
            } catch (Exception ignored) {
            }
        }

        LOG.info("Navigation [{}] completed in {} ms", label, System.currentTimeMillis() - start);
    }

    private void restoreMainPage() {
        driver.get(BASE_URL);

        // Add scroll to home for aesthetics
        ((JavascriptExecutor) driver).executeScript("window.scrollTo({top: 300, behavior: 'smooth'});");
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        ((JavascriptExecutor) driver).executeScript("window.scrollTo({top: 0, behavior: 'smooth'});");

        new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(d -> ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));

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
