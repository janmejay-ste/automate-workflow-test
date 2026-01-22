package utils;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Small helper for common explicit waits used by tests.
 * Keeps tests readable and centralizes timeout configuration.
 */
public class WaitUtils {

    private final WebDriverWait wait;

    public WaitUtils(WebDriver driver, Duration timeout) {
        this.wait = new WebDriverWait(driver, timeout);
    }

    public WaitUtils(WebDriver driver) {
        this(driver, Duration.ofSeconds(30));
    }

    public WebElement waitForVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public WebElement waitForClickable(By locator) {
        return wait.until(ExpectedConditions.elementToBeClickable(locator));
    }

    public boolean waitForTextToBePresent(By locator, String text) {
        return wait.until(ExpectedConditions.textToBePresentInElementLocated(locator, text));
    }

    public void waitForUrlContains(String fragment) {
        wait.until(ExpectedConditions.urlContains(fragment));
    }

    /** Short, test-friendly pause that uses Thread.sleep internally but centralizes usage. */
    public void shortPause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Static convenience wrappers for legacy/static usage patterns ---
    public static WebElement waitForVisible(WebDriver driver, By locator) {
        return new WaitUtils(driver).waitForVisible(locator);
    }

    public static WebElement waitForClickable(WebDriver driver, By locator) {
        return new WaitUtils(driver).waitForClickable(locator);
    }

    /**
     * Quick presence check: returns true if the element becomes visible within a short timeout.
     */
    public static boolean isElementPresent(WebDriver driver, By locator) {
        try {
            new WaitUtils(driver, Duration.ofSeconds(2)).waitForVisible(locator);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wait until any of the provided locators becomes visible and return that WebElement.
     * Uses the provided timeout; if null, uses 30s.
     */
    public static WebElement waitForAnyVisible(WebDriver driver, Duration timeout, By... locators) {
        Duration t = timeout == null ? Duration.ofSeconds(30) : timeout;
        WebDriverWait w = new WebDriverWait(driver, t);
        return w.until(d -> {
            for (By l : locators) {
                try {
                    WebElement e = d.findElement(l);
                    if (e != null && e.isDisplayed()) return e;
                } catch (Exception ignored) {
                }
            }
            return null;
        });
    }

    public static WebElement waitForAnyVisible(WebDriver driver, By... locators) {
        return waitForAnyVisible(driver, Duration.ofSeconds(30), locators);
    }

    // waitForPageLoad 
    public static void waitForPageLoad(WebDriver driver) {
        new WebDriverWait(driver, Duration.ofSeconds(30)).until(
                webDriver -> ((String) ((org.openqa.selenium.JavascriptExecutor) webDriver)
                        .executeScript("return document.readyState")).equals("complete"));
    }

    /**
     * Wait for an element to be present in the DOM (presence only, not necessarily visible).
     */
    public static WebElement waitForPresent(WebDriver driver, By locator) {
        return new WebDriverWait(driver, Duration.ofSeconds(30)).until(
                ExpectedConditions.presenceOfElementLocated(locator));
    }

    /**
     * Wait for an element to be present with custom timeout.
     */
    public static WebElement waitForPresent(WebDriver driver, By locator, Duration timeout) {
        return new WebDriverWait(driver, timeout).until(
                ExpectedConditions.presenceOfElementLocated(locator));
    }

    /**
     * Wait until a custom condition is true, with specified timeout.
     */
    public static void waitUntil(WebDriver driver, Duration timeout, Predicate<WebDriver> condition) {
        new WebDriverWait(driver, timeout).until(d -> condition.test(d));
    }
    
}