package base;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

import utils.ConsoleLogFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.logging.Level;

import utils.TrendExporter;
import utils.HealthTracker;

public class BaseTest {

    protected static WebDriver driver;
    private static final Logger LOG = LoggerFactory.getLogger(BaseTest.class);

    @BeforeSuite(alwaysRun = true)
    public void setUpSuite() {
        // Quiet down java.util.logging (Selenium/CDP emits noisy warnings); set root level to SEVERE
        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.setLevel(Level.SEVERE);
            java.util.logging.Handler[] handlers = root.getHandlers();
            for (java.util.logging.Handler h : handlers) {
                h.setLevel(Level.SEVERE);
            }
        } catch (Exception ignore) {}

        // Reduce TestNG remote reporting chatter by disabling its verbose message
        System.setProperty("testng.verbose", "0");

        // Initialize one shared browser for the entire suite to avoid relaunch between classes
driver = createDriver();
        try {
            driver.manage().window().maximize();
        } catch (Exception ignore) {}

        // Register shutdown hook to export trend in case JVM exits unexpectedly
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    int score = HealthTracker.get().getScore();
                    TrendExporter.updateTrend(score);
                    LOG.info("Trend exported from shutdown hook (score={})", score);
                } catch (Throwable t) {
                    LOG.debug("Shutdown hook failed to export trend: {}", t.getMessage());
                }
            }, "trend-export-shutdown-hook"));
        } catch (Exception ignore) {}
    }

    @AfterMethod(alwaysRun = true)
    public void afterEachTest(ITestResult result) {
        // Try to capture screenshot only if driver/session/window is valid
        try {
            if (result.getStatus() == ITestResult.FAILURE) {
                try {
                    if (isDriverWindowAvailable()) {
                        File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                        File dest = new File("reports/screenshots/" + result.getName() + ".png");
                        dest.getParentFile().mkdirs();
                        Files.copy(screenshot.toPath(), dest.toPath());
                        LOG.info("Screenshot saved: {}", dest.getAbsolutePath());
                    } else {
                        LOG.warn("Driver window not available; skipping screenshot for {}", result.getName());
                    }
                } catch (org.openqa.selenium.NoSuchWindowException | org.openqa.selenium.NoSuchSessionException nsw) {
                    LOG.warn("Driver window/session missing when taking screenshot: {}", nsw.getMessage());
                    // Try to re-initialize driver so subsequent tests can continue
                    try {
                        recreateDriver();
                    } catch (Exception e) {
                        LOG.debug("Failed to recreate driver after screenshot failure: {}", e.getMessage());
                    }
                } catch (IOException ioe) {
                    LOG.debug("Failed saving screenshot: {}", ioe.getMessage());
                }
            }
        } catch (Throwable t) {
            LOG.debug("Unexpected error in afterEachTest screenshot flow: {}", t.getMessage());
        }

        // Attempt to ignore known console errors if driver is available, else recreate
        try {
            if (isDriverWindowAvailable()) {
                ConsoleLogFilter.ignoreKnownErrors(driver);
            } else {
                LOG.warn("Driver window not available during afterEachTest; attempting to recreate");
                recreateDriver();
            }
        } catch (org.openqa.selenium.NoSuchWindowException | org.openqa.selenium.NoSuchSessionException ns) {
            LOG.warn("Driver window/session missing when checking logs: {}", ns.getMessage());
            try {
                recreateDriver();
            } catch (Exception e) {
                LOG.debug("Failed to recreate driver after log-check failure: {}", e.getMessage());
            }
        } catch (Throwable t) {
            LOG.debug("Unexpected error in afterEachTest log-check flow: {}", t.getMessage());
        }
    }

    private boolean isDriverWindowAvailable() {
        if (driver == null) return false;
        try {
            Set<String> handles = driver.getWindowHandles();
            return handles != null && !handles.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private synchronized void recreateDriver() {
        try {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignore) {}
                driver = null;
            }
        } catch (Throwable ignored) {}

driver = createDriver();
        try { driver.manage().window().maximize(); } catch (Exception ignore) {}
        LOG.info("Recreated ChromeDriver for remaining tests");
    }
private ChromeDriver createDriver() {
    ChromeOptions options = new ChromeOptions();

    // Headless toggle
    boolean headless = Boolean.parseBoolean(
            System.getProperty("headless", "false")
    );

    if (headless) {
        options.addArguments("--headless=new");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-gpu");
    }

    // Stability flags (safe even locally)
    options.addArguments("--disable-dev-shm-usage");
    options.addArguments("--no-sandbox");

    LOG.info("Launching ChromeDriver (headless={})", headless);
    return new ChromeDriver(options);
}

    @AfterSuite(alwaysRun = true)
    public void tearDownSuite() {
        try {
            // Export final trend (safe due to idempotent guard)
            int score = HealthTracker.get().getScore();
            TrendExporter.updateTrend(score);
        } catch (Throwable t) {
            LOG.debug("Failed updating trend in AfterSuite: {}", t.getMessage());
        }

        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception ignore) {}
        }
    }
}