package pages;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import utils.config.UrlRegistry;

/**
 * Page Object for error pages (404, 500, etc.)
 */
public class ErrorPage {

    private static final int TIMEOUT_SEC = 10;

    private final WebDriver driver;

    // ---- Locators ----
    private final By error404Text = By
            .xpath("//*[contains(text(),'404') or contains(text(),'not found') or contains(text(),'Not Found')]");
    private final By error500Text = By
            .xpath("//*[contains(text(),'500') or contains(text(),'server error') or contains(text(),'Server Error')]");
    private final By errorPageIndicator = By
            .xpath("//*[contains(text(),'error') or contains(text(),'Error') or contains(text(),'Oops')]");
    private final By homeLink = By
            .cssSelector("a[href='/'], a[href*='home'], a:contains('Home'), a:contains('Go back')");
    private final By backButton = By
            .xpath("//a[contains(text(),'Back') or contains(text(),'Home') or contains(text(),'Return')]");

    public ErrorPage(WebDriver driver) {
        this.driver = driver;
    }

    // --------------------------------------------------
    // Navigation to trigger errors
    // --------------------------------------------------

    public void navigateToInvalidUrl() {
        driver.get(UrlRegistry.MARKETING_BASE + "/this-page-does-not-exist-12345");
        waitForPageLoad();
    }

    public void navigateToUrl(String url) {
        driver.get(url);
        waitForPageLoad();
    }

    private void waitForPageLoad() {
        new WebDriverWait(driver, Duration.ofSeconds(TIMEOUT_SEC))
                .until(d -> ((JavascriptExecutor) d)
                        .executeScript("return document.readyState")
                        .equals("complete"));
    }

    // --------------------------------------------------
    // Error Detection
    // --------------------------------------------------

    public boolean is404Page() {
        // Check URL first
        String url = driver.getCurrentUrl().toLowerCase();
        if (url.contains("404")) {
            return true;
        }

        // Check page title
        String title = driver.getTitle().toLowerCase();
        if (title.contains("404") || title.contains("not found")) {
            return true;
        }

        // Check page content
        try {
            return !driver.findElements(error404Text).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean is500Page() {
        String url = driver.getCurrentUrl().toLowerCase();
        String title = driver.getTitle().toLowerCase();

        if (url.contains("500") || title.contains("500") || title.contains("server error")) {
            return true;
        }

        try {
            return !driver.findElements(error500Text).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isErrorPage() {
        return is404Page() || is500Page() || hasErrorIndicator();
    }

    private boolean hasErrorIndicator() {
        try {
            String source = driver.getPageSource().toLowerCase();
            return source.contains("something went wrong")
                    || source.contains("oops")
                    || source.contains("page not found");
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------------------------------------
    // Error Page Content
    // --------------------------------------------------

    public String getErrorMessage() {
        try {
            WebElement errorElement = driver.findElement(errorPageIndicator);
            return errorElement.getText();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    public boolean hasHomeLink() {
        try {
            // Try multiple strategies to find home link
            if (!driver.findElements(homeLink).isEmpty()) {
                return true;
            }
            if (!driver.findElements(backButton).isEmpty()) {
                return true;
            }
            // Check for any link that goes to homepage
            for (WebElement link : driver.findElements(By.tagName("a"))) {
                String href = link.getAttribute("href");
                if (href != null && (href.equals("/") || href.endsWith(".ai/") || href.endsWith(".ai"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void clickHomeLink() {
        try {
            WebElement link = driver.findElement(homeLink);
            link.click();
        } catch (NoSuchElementException e) {
            // Try back button
            driver.findElement(backButton).click();
        }
    }

    // --------------------------------------------------
    // Page State
    // --------------------------------------------------

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public String getPageTitle() {
        return driver.getTitle();
    }

    public boolean pageHasContent() {
        String source = driver.getPageSource();
        // Check for minimal page content (not blank)
        return source != null && source.length() > 500;
    }
}
