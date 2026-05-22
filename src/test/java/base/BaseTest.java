package base;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestResult;
import org.testng.Reporter;
import org.testng.annotations.*;

import utils.ConsoleLogFilter;
import utils.DashboardBuilder;
import utils.DashboardLauncher;
import utils.FailureArtifactManager;
import utils.NetworkMonitor;
import utils.TrendExporter;
import utils.VideoRecorder;
import utils.VideoRecorder.VideoResult;
import utils.health.HealthGate;
import utils.health.HealthTracker;
import utils.analytics.TestAnalyticsLogger;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.logging.Level;

public abstract class BaseTest {

    // ── ThreadLocal driver — eliminates shared static state ──────────────────
    // Each execution thread owns exactly one WebDriver instance.
    // The instance field 'driver' is synced from the ThreadLocal in setUp() so all
    // existing test code (driver.findElement(...) etc.) continues to work unchanged.
    private static final ThreadLocal<WebDriver> DRIVER_HOLDER = new ThreadLocal<>();

    /** Non-static instance field — safe for parallel test execution per thread. */
    protected WebDriver driver;

    private static final Logger LOG = LoggerFactory.getLogger(BaseTest.class);

    protected static final String BASE_URL = System.getProperty("base.url", "https://appypieautomate.ai");

    // ---------- SUITE SETUP ----------

    /**
     * Suite start wall-clock millis. Captured in {@link #beforeSuite()} so
     * {@link #afterSuite()} can compute the real duration and propagate it to
     * both the analytics JSON (dashboard reads {@code totalDurationMs}) and a
     * system property the PDF cover page reads ({@code suite.duration}).
     * Previously this was never recorded and the dashboard showed "0ms" — a
     * telemetry-credibility bug.
     */
    private static volatile long suiteStartMs = 0L;

    @BeforeSuite(alwaysRun = true)
    public void beforeSuite() {
        silenceJavaUtilLogging();
        suiteStartMs = System.currentTimeMillis();
        LOG.info("Test suite started");
    }

    // ---------- TEST SETUP ----------

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        // Create driver for this thread if it doesn't exist yet
        if (DRIVER_HOLDER.get() == null) {
            WebDriver d = createDriver();
            d.manage().window().maximize();
            DRIVER_HOLDER.set(d);
        }
        // Sync instance field so subclasses use their thread's driver
        driver = DRIVER_HOLDER.get();

        ITestResult result = Reporter.getCurrentTestResult();
        if (result != null && result.getMethod() != null && result.getMethod().isTest()) {
            String testClass  = this.getClass().getSimpleName();
            String testMethod = result.getMethod().getMethodName();
            String testId     = testClass + "." + testMethod;

            TestCategory category = getCategory(result);
            if (category != null) {
                if (category.requiresLogin()) {
                    LOG.warn("Authenticated Flow Detected: {}.{} requires login", testClass, testMethod);
                    driver.get(BASE_URL + "/dashboard");
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    String currentUrl = driver.getCurrentUrl();
                    if (!currentUrl.contains("dashboard")) {
                        LOG.error("Login fail-fast: expected dashboard URL but got: {}", currentUrl);
                        throw new org.testng.SkipException(
                                "Authentication required but user is not logged in. URL: " + currentUrl);
                    }
                    LOG.info("Login verified — dashboard accessible");
                }
                validateCategoryConsistency(result, category);
            }
            validateTestHasGroup(result);

            TestAnalyticsLogger.get().testStarted(testClass, testMethod,
                    category != null ? category : createDefaultCategory());

            // Owner Enforcement
            if (category != null) {
                boolean missingOwner = "Unassigned".equals(category.owner()) || category.owner().isEmpty();
                if (missingOwner) {
                    if (category.type() == TestType.FULL) {
                        LOG.error("Governance Failure: Owner required for FULL test: {}", testId);
                        throw new org.testng.SkipException("Governance skip: missing owner for FULL test.");
                    } else if (category.type() == TestType.REGRESSION) {
                        LOG.warn("Governance Warning: Owner recommended for REGRESSION test: {}", testId);
                    }
                }
            }
        }

        driver.get(BASE_URL);
        NetworkMonitor.reset(driver);

        // Start video recording — no-op if -DrecordVideo=true is not set.
        // Must be after driver.get() so the browser window is fully initialised.
        if (result != null && result.getMethod() != null) {
            String recId = this.getClass().getSimpleName() + "_" + result.getMethod().getMethodName();
            VideoRecorder.start(recId, driver);   // stored in ThreadLocal; null-safe if disabled
        }
    }

    // ---------- TEST TEARDOWN ----------

    @AfterMethod(alwaysRun = true)
    public void afterMethod(ITestResult result) {
        String testClass  = result.getTestClass().getRealClass().getSimpleName();
        String testMethod = result.getName();

        // ── Video recorder teardown — must run FIRST to capture end-of-test state ────────────
        // stop() sets volatile stopping=true, then awaits any in-flight captureFrame() via
        // awaitTermination(3s). After this block the driver is safe to use for artifact capture.
        VideoRecorder recorder    = VideoRecorder.current();
        VideoResult   videoResult = null;
        String precomputedFailureFolder = null;

        if (recorder != null) {
            if (result.getStatus() == ITestResult.FAILURE) {
                // Pre-compute the failure folder so video and other artifacts land in the same dir
                precomputedFailureFolder = FailureArtifactManager.computeFolderName(testMethod);
                videoResult = recorder.stop(
                        Paths.get("reports/failures/" + precomputedFailureFolder), true);
            } else if (result.getStatus() == ITestResult.SUCCESS) {
                String folder = VideoRecorder.buildPassedFolder(testMethod);
                videoResult = recorder.stop(Paths.get("reports/recordings/" + folder), true);
            } else {
                recorder.stop(null, false);   // discard frames for SKIP/other statuses
            }
        }
        // ── End video recorder teardown ───────────────────────────────────────────────────────

        if (result.getStatus() == ITestResult.FAILURE) {
            String artifactFolder = null;
            if (isDriverHealthy()) {
                artifactFolder = (precomputedFailureFolder != null)
                        ? FailureArtifactManager.capture(driver, testMethod, precomputedFailureFolder)
                        : FailureArtifactManager.capture(driver, testMethod);
            }
            TestAnalyticsLogger.get().testFailed(testClass, testMethod, result.getThrowable(), artifactFolder);

            HealthTracker ht = HealthTracker.get();
            ht.recordTestFailure(testClass + "." + testMethod,
                    result.getThrowable().getMessage(), null);
            TestCategory cat = getCategory(result);
            if (cat == null) cat = createDefaultCategory();
            ht.addTestRecord(cat.type().name(), cat.requiresLogin() ? "Auth" : "Guest",
                    cat.feature(), testClass, testMethod, "FAIL",
                    result.getEndMillis() - result.getStartMillis(),
                    artifactFolder,
                    videoResult != null ? videoResult.path()      : null,
                    videoResult != null && videoResult.truncated());

        } else if (result.getStatus() == ITestResult.SUCCESS) {
            TestAnalyticsLogger.get().testPassed(testClass, testMethod);
            HealthTracker ht = HealthTracker.get();
            ht.recordTestSuccess();
            TestCategory cat = getCategory(result);
            if (cat == null) cat = createDefaultCategory();
            ht.addTestRecord(cat.type().name(), cat.requiresLogin() ? "Auth" : "Guest",
                    cat.feature(), testClass, testMethod, "PASS",
                    result.getEndMillis() - result.getStartMillis(),
                    null,
                    videoResult != null ? videoResult.path()      : null,
                    videoResult != null && videoResult.truncated());

        } else if (result.getStatus() == ITestResult.SKIP) {
            TestAnalyticsLogger.get().testSkipped(testClass, testMethod, "Skipped by TestNG");
        }

        try {
            if (driver != null) ConsoleLogFilter.ignoreKnownErrors(driver);
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

            // Suite duration — both analytics JSON and PDF cover meta need this.
            // Compute once, propagate via two channels:
            //   - utils.analytics.AnalyticsCollector reads it via system property
            //   - PdfReportBuilder.renderExportMetadata reads it via system property
            // Falls back to "(not recorded)" if beforeSuite never ran (e.g. test
            // launched outside the @BeforeSuite lifecycle).
            long durationMs = suiteStartMs == 0L ? 0L : System.currentTimeMillis() - suiteStartMs;
            System.setProperty("suite.durationMs", String.valueOf(durationMs));
            System.setProperty("suite.duration",   humanDuration(durationMs));

            TestAnalyticsLogger.get().generateReports();

            String suiteName = System.getProperty("suiteFile", "manual");
            String env       = System.getProperty("environment", "local");
            utils.HistoryJsonWriter.appendRun(utils.analytics.AnalyticsCollector.collect(), suiteName, env);

            TrendExporter.updateTrend(score);
            tracker.printReport();

            // Render dashboard / PDFs FIRST so they see the previous run as
            // "previous" (history still holds runs 1..N-1 at this point).
            // Generate executive + technical PDFs from the same semantic snapshot.
            // Non-fatal — PDF failure never blocks the suite.
            DashboardBuilder.write();
            utils.health.report.PdfReportBuilder.generateAll();

            // Then append the current run to the trend file so future runs can
            // compute their delta against this one.
            utils.health.trend.SemanticTrendWriter.appendCurrentRun();
            LOG.info("Trend exported (score={})", score);
        } finally {
            DashboardLauncher.launchIfEnabled();
            quitDriver(); // close the browser before enforcing the gate
            HealthGate.enforce(HealthTracker.get());
        }
    }

    // ---------- DRIVER ----------

    private static WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();

        boolean headless = Boolean.parseBoolean(System.getProperty("headless", "false"));
        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-gpu");
        }

        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");

        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.BROWSER, Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        LOG.info("Launching ChromeDriver (headless={}, thread={})", headless, Thread.currentThread().getId());
        return new ChromeDriver(options);
    }

    /**
     * Format a duration in millis as "18m 42s" / "42s" / "1h 03m 18s".
     * Used for the PDF cover metadata so a 56s suite reads "56s" not "56000".
     */
    private static String humanDuration(long ms) {
        if (ms <= 0) return "(not recorded)";
        long s  = ms / 1000;
        long h  = s / 3600;
        long m  = (s % 3600) / 60;
        long ss = s % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, ss);
        if (m > 0) return String.format("%dm %02ds", m, ss);
        return ss + "s";
    }

    private static void quitDriver() {
        WebDriver d = DRIVER_HOLDER.get();
        if (d != null) {
            try { d.quit(); } catch (Exception ignored) {}
            DRIVER_HOLDER.remove();
        }
    }

    // ---------- HEALTH ----------

    private boolean isDriverHealthy() {
        try {
            if (driver == null) return false;
            Set<String> handles = driver.getWindowHandles();
            if (handles == null || handles.isEmpty()) return false;
            driver.getCurrentUrl();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- CATEGORY RESOLUTION ----------

    protected TestCategory getCategory(ITestResult result) {
        Method m = result.getMethod().getConstructorOrMethod().getMethod();
        if (m != null && m.isAnnotationPresent(TestCategory.class)) {
            return m.getAnnotation(TestCategory.class);
        }
        return result.getTestClass().getRealClass().getAnnotation(TestCategory.class);
    }

    private void validateCategoryConsistency(ITestResult result, TestCategory cat) {
        String[] groups = result.getMethod().getGroups();
        String expectedGroup = cat.type().name().toLowerCase();
        boolean found = Arrays.asList(groups).contains(expectedGroup);
        if (!found && cat.type() == TestType.SMOKE)  found = Arrays.asList(groups).contains("smoke");
        if (!found && cat.type() == TestType.SANITY) found = Arrays.asList(groups).contains("sanity")
                || Arrays.asList(groups).contains("smoke");
        if (!found) {
            LOG.warn("Category mismatch: @TestCategory({}) but groups={} for {}.{}",
                    cat.type(), Arrays.toString(groups),
                    result.getTestClass().getRealClass().getSimpleName(),
                    result.getMethod().getMethodName());
        }
    }

    private void validateTestHasGroup(ITestResult result) {
        String[] groups = result.getMethod().getGroups();
        if (groups == null || groups.length == 0) {
            LOG.warn("Ungrouped test: {}.{} — will only run in full suite, not targeted runs",
                    result.getTestClass().getRealClass().getSimpleName(),
                    result.getMethod().getMethodName());
        }
    }

    // ---------- LOGGING ----------

    private void silenceJavaUtilLogging() {
        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.setLevel(Level.SEVERE);
            for (var h : root.getHandlers()) h.setLevel(Level.SEVERE);
        } catch (Exception ignored) {}
    }

    private TestCategory createDefaultCategory() {
        return new TestCategory() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return TestCategory.class; }
            @Override public TestType type()                    { return TestType.REGRESSION; }
            @Override public boolean requiresLogin()            { return false; }
            @Override public String owner()                     { return "Unassigned"; }
            @Override public String feature()                   { return "General"; }
            @Override public TestType.Severity severity()       { return TestType.Severity.MEDIUM; }
            @Override public TestType.FailureType defaultFailureType() { return TestType.FailureType.PRODUCT_BUG; }
        };
    }
}
