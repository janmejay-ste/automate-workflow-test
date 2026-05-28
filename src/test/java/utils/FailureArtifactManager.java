package utils;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

public class FailureArtifactManager {
    private static final Logger LOG = LoggerFactory.getLogger(FailureArtifactManager.class);

    /**
     * Computes a timestamped folder name without creating the directory.
     * Used by VideoRecorder so video and other artifacts share the exact same folder
     * (pre-computed once, before both systems run) with no timestamp mismatch.
     */
    public static String computeFolderName(String testName) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        return testName + "_" + ts;
    }

    /**
     * Captures Screenshot, DOM Snapshot, URL, and Console logs for debugging.
     * Returns the relative folder path (e.g. 'testMethod_20260228_131523_123') to be passed to analytics.
     */
    public static String capture(WebDriver driver, String testName) {
        return capture(driver, testName, computeFolderName(testName));
    }

    /**
     * Overload that accepts a pre-computed folder name so VideoRecorder and FailureArtifactManager
     * write to the exact same directory without generating two different timestamps.
     */
    public static String capture(WebDriver driver, String testName, String precomputedFolderName) {
        if (driver == null) return null;

        try {
            String folderName = precomputedFolderName;
            String relativeBaseDir = "reports/failures/" + folderName;
            Path baseDir = Paths.get(relativeBaseDir);
            Files.createDirectories(baseDir);

            // 1. Screenshot
            try {
                File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                Files.copy(screenshot.toPath(),
                        baseDir.resolve("screenshot.png"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.error("Failed to capture screenshot artifact", e);
            }

            // 2. DOM Snapshot
            try {
                String dom = driver.getPageSource();
                Files.writeString(baseDir.resolve("dom.html"), dom);
            } catch (Exception e) {
                LOG.error("Failed to capture DOM artifact", e);
            }

            // 3. Current URL
            try {
                Files.writeString(baseDir.resolve("url.txt"), driver.getCurrentUrl());
            } catch (Exception e) {
                LOG.error("Failed to capture URL artifact", e);
            }

            // 4. Console Logs (May fail if browser doesn't support it or preferences aren't set)
            try {
                StringBuilder consoleLog = new StringBuilder();
                driver.manage().logs().get(LogType.BROWSER).forEach(entry -> {
                    consoleLog.append(entry.getLevel())
                            .append(" ")
                            .append(entry.getMessage())
                            .append("\n");
                });
                
                // Add some extra contextual info as requested
                try {
                    Long continueButtons = (Long) ((JavascriptExecutor) driver).executeScript(
                        "return document.querySelectorAll('button[data-track=\"continue\"]:not([disabled])').length;");
                    consoleLog.append("\n--- Extra Context ---\n");
                    consoleLog.append("Enabled Continue Buttons: ").append(continueButtons).append("\n");
                } catch (Exception ignored) {}

                Files.writeString(baseDir.resolve("console.log"), consoleLog.toString());
            } catch (Exception e) {
                LOG.warn("Could not capture console logs (browser may not support it): {}", e.getMessage());
            }

            LOG.info("Failure artifacts captured at: {}", relativeBaseDir);

            // Return only the folder name so DashboardBuilder can build relative links cleanly
            return folderName;

        } catch (Exception e) {
            LOG.error("Artifact capture system failed entirely (test will not block): {}", e.getMessage());
            return null;
        }
    }
}

