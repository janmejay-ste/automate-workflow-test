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

    public static void navigateAndLogin(WebDriver driver) {
        String targetUrl = "https://connectcloud.appypie.com/connects";
        logger.info("Navigating to target URL: {}", targetUrl);
        driver.get(targetUrl);

        if (AuthState.hasSession(driver)) {
            logger.info("Existing session detected. Skipping manual login.");
            return;
        }

        logger.info("Redirect detected or login required. Waiting for manual intervention (Timeout: 3 minutes)...");
        try {
            waitForManualLogin(driver);
            waitForDashboardReady(driver);
            logger.info("Dashboard loaded! Proceeding with automation.");
        } catch (TimeoutException e) {
            logger.error("Timed out waiting for manual login or dashboard readiness.");
            throw e;
        }
    }

    public static void waitForManualLogin(WebDriver driver) {
        new WebDriverWait(driver, MANUAL_TIMEOUT).until(d -> {
            String url = d.getCurrentUrl();
            boolean hasSession = AuthState.hasSession(d);
            boolean onDashboard = url.contains("/connects") || url.contains("appypieautomate");

            // Fallback: Check for visible dashboard elements if session script fails
            boolean dashboardElementVisible = false;
            try {
                dashboardElementVisible = d.findElements(By.xpath("//*[contains(text(), 'My Connects')]")).size() > 0;
            } catch (Exception e) {
                // Ignore errors during check
            }

            if ((hasSession || dashboardElementVisible) && onDashboard) {
                logger.info("Session/Dashboard found! URL: {}, Session: {}, Element: {}",
                        url, hasSession, dashboardElementVisible);
                return true;
            }
            return false;
        });
    }

    public static void waitForDashboardReady(WebDriver driver) {
        logger.info("Waiting for dashboard readiness...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(45));
        wait.until(ExpectedConditions.or(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("app-sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".main-sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(), 'My Connects')]"))));
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
