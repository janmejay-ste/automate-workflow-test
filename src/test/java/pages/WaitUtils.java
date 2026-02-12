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

    public void waitForLoader() {
        By loader = By.cssSelector("app-loader, #loader, .outhLoader");
        try {
            // Short wait to see if loader appears
            new WebDriverWait(driver, Duration.ofMillis(1500))
                    .until(ExpectedConditions.presenceOfElementLocated(loader));
            System.out.println("Loader detected, waiting for it to disappear...");
            new WebDriverWait(driver, timeout)
                    .until(ExpectedConditions.invisibilityOfElementLocated(loader));
        } catch (Exception e) {
            // If it never appears, that's fine too
        }
    }

    public void completeUserGuide() {
        By nextBtn = By.xpath(
                "//button[contains(text(), 'Next')] | //button[contains(., 'Next')] | //button[contains(text(), 'Got it')] | //button[contains(., 'Got it')] | //button[contains(text(), 'Done')] | //button[contains(text(), 'Get started')] | //button[contains(text(), 'Get Started')] | //button[contains(@class, 'guide-btn-primary')]");
        By closeBtn = By.cssSelector(".guide-close-btn, .closebtn-guide, .joyride-step__close");
        By guideContainer = By.cssSelector(
                ".user-guide-container, .guide-overlay, .joyride-step__container, .guide-tooltip, .app-user-guide");

        try {
            // Wait up to 5 seconds for guide to appear (it loads with a delay)
            System.out.println("Waiting for User Guide to appear...");
            boolean guideFound = false;
            for (int wait = 0; wait < 10; wait++) {
                if (!driver.findElements(guideContainer).isEmpty()) {
                    guideFound = true;
                    System.out.println("User Guide detected after " + (wait * 500) + "ms");
                    break;
                }
                Thread.sleep(500);
            }
            if (!guideFound) {
                System.out.println("No User Guide appeared after 5s. Continuing.");
                return;
            }

            // Navigate through guide steps
            int maxSteps = 15;
            while (maxSteps > 0) {
                java.util.List<WebElement> containers = driver.findElements(guideContainer);
                if (containers.isEmpty()) {
                    Thread.sleep(1000);
                    containers = driver.findElements(guideContainer);
                    if (containers.isEmpty())
                        break;
                }

                java.util.List<WebElement> buttons = driver.findElements(nextBtn);
                boolean clicked = false;
                for (WebElement btn : buttons) {
                    if (btn.isDisplayed() && btn.isEnabled()) {
                        String text = btn.getText();
                        System.out.println("User Guide step (" + (16 - maxSteps) + "): Clicking "
                                + (text.isEmpty() ? "Next button" : text));
                        try {
                            btn.click();
                        } catch (Exception ce) {
                            // JS click fallback
                            ((org.openqa.selenium.JavascriptExecutor) driver)
                                    .executeScript("arguments[0].click();", btn);
                        }
                        Thread.sleep(1500);
                        clicked = true;
                        break;
                    }
                }

                if (!clicked) {
                    java.util.List<WebElement> closeButtons = driver.findElements(closeBtn);
                    for (WebElement cb : closeButtons) {
                        if (cb.isDisplayed() && cb.isEnabled()) {
                            System.out.println("User Guide: Clicking close button fallback");
                            cb.click();
                            Thread.sleep(1000);
                            clicked = true;
                            break;
                        }
                    }
                }

                if (!clicked)
                    break;
                maxSteps--;
            }

            // Final check: make sure guide is fully gone
            Thread.sleep(500);
            if (!driver.findElements(guideContainer).isEmpty()) {
                System.out.println("Guide still present after loop. Force-closing...");
                java.util.List<WebElement> closeBtns = driver.findElements(closeBtn);
                for (WebElement cb : closeBtns) {
                    if (cb.isDisplayed()) {
                        cb.click();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("User Guide handling error (suppressed): " + e.getMessage());
        }
    }

    public void dismissOverlays() {
        By skipBtn = By.xpath(
                "//button[contains(text(), 'Skip')] | //a[contains(text(), 'Skip')] | //*[contains(@class, 'skip')]");
        By closeBtn = By
                .cssSelector(".close, .close-btn, [aria-label='Close'], .modal-header .btn-close, .guide-close-btn");
        By guideContainer = By.cssSelector(".user-guide-container, .guide-overlay");

        try {
            // Check for specific onboarding guide first
            if (!driver.findElements(guideContainer).isEmpty()) {
                System.out.println("Onboarding guide detected: Dismissing...");
                java.util.List<WebElement> guideCloses = driver.findElements(By.cssSelector(".guide-close-btn"));
                if (!guideCloses.isEmpty()) {
                    guideCloses.get(0).click();
                    Thread.sleep(500); // Wait for fade out
                }
            }

            // Check for Skip button
            java.util.List<WebElement> skips = driver.findElements(skipBtn);
            if (!skips.isEmpty() && skips.get(0).isDisplayed()) {
                System.out.println("Overlay detected: Clicking Skip button");
                skips.get(0).click();
                return;
            }

            // Fallback to General Close button
            java.util.List<WebElement> closes = driver.findElements(closeBtn);
            for (WebElement close : closes) {
                if (close.isDisplayed()) {
                    System.out.println("Overlay detected: Clicking Close button (" + close.getAttribute("class") + ")");
                    close.click();
                    break;
                }
            }
        } catch (Exception e) {
            // Suppress errors during overlay dismissal attempts
        }
    }
}
