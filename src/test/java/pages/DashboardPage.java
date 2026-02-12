package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class DashboardPage {
    private static final Logger logger = LoggerFactory.getLogger(DashboardPage.class);
    private final WebDriver driver;
    private final WaitUtils waits;

    private final By sidebar = By.cssSelector("app-sidebar, #sidebar, .main-sidebar, .sidebar");
    private final By myConnectsHeader = By.xpath("//*[contains(text(), 'My Connects')]");

    public DashboardPage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(20));
    }

    public boolean isDashboardLoaded() {
        logger.info("Checking if Dashboard is loaded...");
        try {
            WebElement sb = waits.waitForVisible(sidebar);
            logger.info("Sidebar found: {}", sb.isDisplayed());

            WebElement header = waits.waitForVisible(myConnectsHeader);
            logger.info("Header 'My Connects' found: {}", header.isDisplayed());

            return true;
        } catch (Exception e) {
            logger.warn("Dashboard loading check failed: {}", e.getMessage());
            return false;
        }
    }

    public void clickCreateConnect() {
        logger.info("Clicking 'Create Connect' button...");
        WaitUtils vu = new WaitUtils(driver, java.time.Duration.ofSeconds(10));
        vu.dismissOverlays(); // Clear blocking tours/popups

        // Use a more specific selector targeting the blue sidebar button
        By specificCreateBtn = By.cssSelector(
                "#sidebar a#createConnect, .side-bar a#createConnect, .left-side-bar button.create-connect, .createapp a#createConnect, a[href='/connects/create']");

        try {
            WebElement btn = vu.waitForClickable(specificCreateBtn);
            btn.click();
        } catch (Exception e) {
            logger.warn("Standard click failed, attempting JS click: {}", e.getMessage());
            WebElement btn = vu.waitForVisible(specificCreateBtn);
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
        }
    }

    public void logout() {
        logger.info("Performing logout...");
        By profileBtn = By.id("ProfileUser");
        By logoutLink = By.id("logout");

        try {
            waits.waitForClickable(profileBtn).click();
            logger.info("Profile menu opened.");
            waits.waitForClickable(logoutLink).click();
            logger.info("Logout link clicked.");
        } catch (Exception e) {
            logger.warn("Standard logout failed, attempting JS click sequence: {}", e.getMessage());
            WebElement pBtn = waits.waitForVisible(profileBtn);
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", pBtn);

            WebElement lLink = waits.waitForVisible(logoutLink);
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", lLink);
        }
    }

    /**
     * Logs out and verifies that the browser redirects to the base URL.
     * Returns the final URL for assertion.
     */
    public String logoutAndVerifyRedirect() {
        // First navigate to dashboard to ensure we can access profile menu
        String dashboardUrl = "https://connectcloud.appypie.com/connects";
        if (!driver.getCurrentUrl().contains("/connects")) {
            logger.info("Not on dashboard, navigating there before logout...");
            driver.get(dashboardUrl);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
        }

        logout();

        // Wait for redirect to base URL
        logger.info("Waiting for redirect to base URL after logout...");
        try {
            new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(d -> {
                        String url = d.getCurrentUrl().toLowerCase();
                        return url.contains("appypieautomate.ai") || url.contains("appypie.com/login");
                    });
        } catch (Exception e) {
            logger.warn("Redirect wait timed out. Current URL: {}", driver.getCurrentUrl());
        }

        String finalUrl = driver.getCurrentUrl();
        logger.info("Post-logout URL: {}", finalUrl);
        return finalUrl;
    }
}
