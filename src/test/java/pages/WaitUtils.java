package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import pages.auth.AuthState;

import java.time.Duration;

public class WaitUtils {

    private final WebDriver driver;
    private final Duration timeout;

    public WaitUtils(WebDriver driver, Duration timeout) {
        this.driver = driver;
        this.timeout = timeout;
    }

    // ---------------------------------------------
    // Generic condition wait
    // ---------------------------------------------
    public static void waitForAuthTransition(
            WebDriver driver,
            Duration timeout) {
        long end = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < end) {
            if (AuthState.isOnIdP(driver)
                    || AuthState.isOnAuthRoute(driver)
                    || AuthState.hasSession(driver)) {
                return;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Auth wait interrupted", e);
            }
        }

        throw new RuntimeException(
                "Auth transition failed. Final URL=" + driver.getCurrentUrl());
    }

    // ---------------------------------------------
    // Selenium-style waits
    // ---------------------------------------------
    public WebElement waitForClickable(By locator) {
        return new WebDriverWait(driver, timeout)
                .until(ExpectedConditions.elementToBeClickable(locator));
    }

    public WebElement waitForVisible(By locator) {
        return new WebDriverWait(driver, timeout)
                .until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    public WebElement waitForClickable(WebElement element) {
        return new WebDriverWait(driver, timeout)
                .until(ExpectedConditions.elementToBeClickable(element));
    }

    public WebElement waitForPresent(By locator) {
        return new WebDriverWait(driver, timeout)
                .until(ExpectedConditions.presenceOfElementLocated(locator));
    }
}
