package base;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestResult;
import org.testng.Reporter;
import org.testng.annotations.*;

import utils.ConsoleLogFilter;
import utils.DashboardBuilder;
import utils.DashboardLauncher;
import utils.TrendExporter;
import utils.health.HealthGate;
import utils.health.HealthTracker;
import utils.analytics.TestAnalyticsLogger;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;
import java.util.logging.Level;

public abstract class BaseTest {

    protected static WebDriver driver;
    private static final Logger LOG = LoggerFactory.getLogger(BaseTest.class);

    protected static final String BASE_URL = System.getProperty("base.url", "https://appypieautomate.ai");

    // ---------- SUITE SETUP ----------

    @BeforeSuite(alwaysRun = true)
    public void beforeSuite() {
        silenceJavaUtilLogging();
        LOG.info("Test suite started");
    }

    // ---------- TEST SETUP ----------

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        if (driver == null) {
            driver = createDriver();
            driver.manage().window().maximize();
        }

        // Analytics: Test Started
        ITestResult result = Reporter.getCurrentTestResult();
        if (result != null && result.getMethod() != null) {
            TestAnalyticsLogger.get().testStarted(
                    this.getClass().getSimpleName(),
                    result.getMethod().getMethodName());
        }

        driver.get(BASE_URL); // Navigate to ensure clean starting state for each test
    }

    // ---------- TEST TEARDOWN ----------

    @AfterMethod(alwaysRun = true)
    public void afterMethod(ITestResult result) {
        String testClass = result.getTestClass().getRealClass().getSimpleName();
        String testMethod = result.getName();

        if (result.getStatus() == ITestResult.FAILURE) {
            captureFailureArtifacts(result);

            // Analytics: Test Failed
            TestAnalyticsLogger.get().testFailed(testClass, testMethod, result.getThrowable());

            // Record in HealthTracker as well
            HealthTracker.get().recordTestFailure(
                    testClass + "." + testMethod,
                    result.getThrowable().getMessage(),
                    null // Stack trace already captured in logger
            );
        } else if (result.getStatus() == ITestResult.SUCCESS) {
            // Analytics: Test Passed
            TestAnalyticsLogger.get().testPassed(testClass, testMethod);
        } else if (result.getStatus() == ITestResult.SKIP) {
            // Analytics: Test Skipped
            TestAnalyticsLogger.get().testSkipped(testClass, testMethod, "Skipped by TestNG");
        }

        try {
            if (driver != null) {
                ConsoleLogFilter.ignoreKnownErrors(driver);
            }
        } catch (Throwable t) {
            LOG.warn("Console log filtering failed: {}", t.getMessage());
        }
    }

    // ---------- SUITE TEARDOWN ----------

    @AfterSuite(alwaysRun = true)
    public void afterSuite() {
        try {
            HealthTracker tracker = HealthTracker.get();
            int score = tracker.getScore();

            // Generate analytics reports
            TestAnalyticsLogger.get().generateReports();

            TrendExporter.updateTrend(score);
            tracker.printReport();
            DashboardBuilder.write(); // Generate updated dashboard HTML
            LOG.info("Trend exported (score={})", score);
        }
        // catch (Throwable t) {
        // LOG.warn("Trend export failed: {}", t.getMessage());
        // }
        finally {
            DashboardLauncher.launchIfEnabled();
            HealthGate.enforce(HealthTracker.get());

            // quitDriver();
        }
    }

    // ---------- DRIVER ----------

    private static WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();

        boolean headless = Boolean.parseBoolean(
                // for headless test
                // System.getProperty("headless", "true"));

                // for local test
                System.getProperty("headless", "false"));

        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-gpu");
        }

        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");

        LOG.info("Launching ChromeDriver (headless={})", headless);
        return new ChromeDriver(options);
    }

    // private static void quitDriver() {
    // try {
    // if (driver != null) {
    // driver.quit();
    // }
    // } catch (Exception ignored) {
    // } finally {
    // driver = null;
    // }
    // }

    // ---------- FAILURE HANDLING ----------

    private void captureFailureArtifacts(ITestResult result) {
        if (!isDriverHealthy())
            return;

        try {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);

            String fileName = String.format(
                    "%s_%s_%d.png",
                    result.getTestClass().getRealClass().getSimpleName(),
                    result.getName(),
                    Instant.now().toEpochMilli());

            File dest = new File("reports/screenshots/" + fileName);
            dest.getParentFile().mkdirs();
            Files.copy(src.toPath(), dest.toPath());

            LOG.info("Screenshot captured: {}", dest.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    // ---------- HEALTH ----------

    private boolean isDriverHealthy() {
        try {
            if (driver == null)
                return false;
            Set<String> handles = driver.getWindowHandles();
            if (handles == null || handles.isEmpty())
                return false;
            driver.getCurrentUrl();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- LOGGING ----------

    private void silenceJavaUtilLogging() {
        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.setLevel(Level.SEVERE);
            for (var h : root.getHandlers()) {
                h.setLevel(Level.SEVERE);
            }
        } catch (Exception ignored) {
        }
    }
}
