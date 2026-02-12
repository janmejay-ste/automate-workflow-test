package utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import pages.auth.AuthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

public class ManualLoginHelper {
    private static final Logger logger = LoggerFactory.getLogger(ManualLoginHelper.class);
    private static final Duration MANUAL_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Standard login: navigates to dashboard URL.
     */
    public static void navigateAndLogin(WebDriver driver) {
        navigateAndLogin(driver, "https://connectcloud.appypie.com/connects");
    }

    /**
     * Overload: navigates to a custom start URL (e.g. an app-pair integration page)
     * then waits for manual login to complete.
     */
    public static void navigateAndLogin(WebDriver driver, String startUrl) {
        logger.info("Navigating to start URL: {}", startUrl);
        driver.get(startUrl);

        if (AuthState.hasSession(driver)) {
            logger.info("Existing session detected. Skipping manual login.");
            return;
        }

        logger.info("Login required. Waiting for manual intervention (Timeout: 3 minutes)...");
        try {
            waitForManualLogin(driver);
            // After login, user might land on dashboard OR directly in editor
            waitForPostLoginReady(driver);
            logger.info("Post-login page loaded! Proceeding with automation.");
        } catch (TimeoutException e) {
            logger.error("Timed out waiting for manual login or post-login readiness.");
            throw e;
        }
    }

    public static void waitForManualLogin(WebDriver driver) {
        new WebDriverWait(driver, MANUAL_TIMEOUT).until(d -> {
            String url = d.getCurrentUrl();
            boolean hasSession = AuthState.hasSession(d);
            boolean onDashboard = url.contains("/connects") || url.contains("connectcloud");
            boolean onEditor = url.contains("/customeditor");

            // Fallback: Check for visible dashboard or editor elements
            boolean dashboardElementVisible = false;
            boolean editorElementVisible = false;
            try {
                dashboardElementVisible = d.findElements(By.xpath("//*[contains(text(), 'My Connects')]")).size() > 0;
                editorElementVisible = d
                        .findElements(By.cssSelector("app-custom-editor, #connectName, .canvas-container")).size() > 0;
            } catch (Exception e) {
                // Ignore errors during check
            }

            if (hasSession && (onDashboard || onEditor)) {
                logger.info("Session found! URL: {}, Dashboard: {}, Editor: {}",
                        url, dashboardElementVisible, editorElementVisible);
                return true;
            }
            if (dashboardElementVisible || editorElementVisible) {
                logger.info("Post-login element detected! URL: {}", url);
                return true;
            }
            return false;
        });
    }

    /**
     * Waits for either dashboard or editor to become ready after login.
     */
    public static void waitForPostLoginReady(WebDriver driver) {
        logger.info("Waiting for post-login readiness (dashboard or editor)...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(45));
        wait.until(ExpectedConditions.or(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("app-sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".main-sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(), 'My Connects')]")),
                ExpectedConditions.visibilityOfElementLocated(
                        By.cssSelector("app-custom-editor, #connectName, .canvas-container"))));
    }

    public static void dumpDom(WebDriver driver, String label) {
        try {
            String pageSource = driver.getPageSource();
            Path dir = Paths.get("reports/dom_dumps");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String fileName = "dom_" + label + "_" + System.currentTimeMillis() + ".html";
            Path filePath = dir.resolve(fileName);
            Files.writeString(filePath, pageSource);
            logger.info("DOM dumped successfully to: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to dump DOM: {}", e.getMessage());
        }
    }
}
