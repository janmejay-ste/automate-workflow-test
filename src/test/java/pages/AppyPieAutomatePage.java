package pages;

import org.openqa.selenium.*;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class AppyPieAutomatePage {

    private static final Logger LOG = LoggerFactory.getLogger(AppyPieAutomatePage.class);

    // ---- Constants ----
    private static final int PAGE_LOAD_TIMEOUT_SEC = 20;
    private static final int DOC_READY_TIMEOUT_SEC = 5;

    private static final int MOBILE_WIDTH = 375;
    private static final int MOBILE_HEIGHT = 812;

    // ---- Driver & waits ----
    private final WebDriver driver;
    private final WaitUtils waits;

    // ---- Locators ----
    private final By headerTitle = By.xpath("//h1[contains(normalize-space(),'Automate')]");

    private final By topNavLinks = By.cssSelector("ul.navbar-nav a");

    public AppyPieAutomatePage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SEC));
    }

    // --------------------------------------------------
    // Page readiness
    // --------------------------------------------------

    public void waitUntilLoaded() {
        waitForDocumentReady();
        LOG.info("AppyPie Automate page loaded (ready state complete)");
    }

    public void waitForHeader(By locator) {
        waits.waitForVisible(locator);
    }

    private void waitForDocumentReady() {
        new org.openqa.selenium.support.ui.WebDriverWait(
                driver, Duration.ofSeconds(DOC_READY_TIMEOUT_SEC))
                .until(d -> ((JavascriptExecutor) d)
                        .executeScript("return document.readyState")
                        .equals("complete"));
    }

    public boolean isPageLoadedCleanly() {
        waitUntilLoaded();
        String source = driver.getPageSource().toLowerCase();
        return !source.contains("something went wrong")
                && !source.contains("white screen");
    }

    // --------------------------------------------------
    // Navigation
    // --------------------------------------------------

    public boolean isOnAutomateDomain() {
        return driver.getCurrentUrl()
                .toLowerCase()
                .contains("appypieautomate");
    }

    public boolean doAllTopNavLinksWork() {
        int count = driver.findElements(topNavLinks).size();

        for (int i = 0; i < count; i++) {
            List<WebElement> links = driver.findElements(topNavLinks);
            WebElement link = links.get(i);

            if (!link.isDisplayed() || !link.isEnabled()) {
                continue; // skip non-interactable nav items
            }

            String href = link.getAttribute("href");
            if (href == null || href.isBlank()) {
                continue;
            }

            try {
                ((JavascriptExecutor) driver)
                        .executeScript("arguments[0].scrollIntoView(true);", link);

                waits.waitForClickable(link).click();
                waitForDocumentReady();

                if (driver.getTitle().contains("404")
                        || driver.getCurrentUrl().contains("/404")) {
                    return false;
                }

                driver.navigate().back();
                waits.waitForVisible(headerTitle);

            } catch (ElementNotInteractableException e) {
                LOG.warn("Skipping non-interactable nav link: {}", href);
            }
        }
        return true;
    }

    // --------------------------------------------------
    // Primary actions
    // --------------------------------------------------

    public boolean isPrimaryActionClickable(By ctaLocator) {
        try {
            WebElement cta = waits.waitForClickable(ctaLocator);
            return cta.isDisplayed() && cta.isEnabled();
        } catch (TimeoutException e) {
            return false;
        }
    }

    // --------------------------------------------------
    // JS errors
    // --------------------------------------------------

    public List<String> getSevereJsErrors() {
        LogEntries logs = driver.manage().logs().get(LogType.BROWSER);
        return logs.getAll().stream()
                .filter(e -> e.getLevel().equals(Level.SEVERE))
                .map(LogEntry::getMessage)
                .collect(Collectors.toList());
    }

    // --------------------------------------------------
    // Performance (modern API)
    // --------------------------------------------------

    public long getNavigationDurationMs() {
        Object value = ((JavascriptExecutor) driver).executeScript(
                "return performance.getEntriesByType('navigation')[0].duration;");
        return ((Number) value).longValue();
    }

    public long getLCP() {
        Object value = ((JavascriptExecutor) driver).executeScript(
                "const e = performance.getEntriesByType('largest-contentful-paint');" +
                        "return e.length ? e[e.length - 1].startTime : -1;");
        return ((Number) value).longValue();
    }

    public double getCLS() {
        Object value = ((JavascriptExecutor) driver).executeScript(
                "const s = performance.getEntriesByType('layout-shift');" +
                        "return s.reduce((sum, e) => sum + e.value, 0);");
        return ((Number) value).doubleValue();
    }

    // --------------------------------------------------
    // Responsive smoke
    // --------------------------------------------------

    public boolean rendersCorrectlyOnMobile() {
        Dimension originalSize = driver.manage().window().getSize();
        try {
            driver.manage().window()
                    .setSize(new Dimension(MOBILE_WIDTH, MOBILE_HEIGHT));
            waits.waitForVisible(headerTitle);
            return driver.findElement(headerTitle).isDisplayed();
        } finally {
            driver.manage().window().setSize(originalSize);
        }
    }

    // --------------------------------------------------
    // Utilities
    // --------------------------------------------------

    public void scrollToElement(By locator) {
        WebElement element = driver.findElement(locator);
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView(true);", element);
    }

    public void scrollToBottom() {
        ((JavascriptExecutor) driver)
                .executeScript("window.scrollTo(0, document.body.scrollHeight)");
    }
}
