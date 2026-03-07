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
import utils.FailureArtifactManager;
import utils.TrendExporter;
import utils.health.HealthGate;
import utils.health.HealthTracker;
import utils.analytics.TestAnalyticsLogger;

import java.lang.reflect.Method;
import java.util.Arrays;

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

        // Analytics: Test Started — skip for non-test methods
        // (@BeforeMethod/@AfterMethod and other config methods)
        ITestResult result = Reporter.getCurrentTestResult();
        if (result != null && result.getMethod() != null && result.getMethod().isTest()) {
            String testClass = this.getClass().getSimpleName();
            String testMethod = result.getMethod().getMethodName();
            String testId = testClass + "." + testMethod;

            // Category validation and login detection
            TestCategory category = getCategory(result);
            if (category != null) {
                if (category.requiresLogin()) {
                    LOG.warn("\uD83D\uDD12 Authenticated Flow Detected: {}.{} requires login", testClass, testMethod);
                    // Login fail-fast: verify auth state early so we don't time out deep in the
                    // workflow
                    driver.get(BASE_URL + "/dashboard");
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
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

            // Analytics: Test Started
            TestAnalyticsLogger.get().testStarted(testClass, testMethod,
                    category != null ? category : createDefaultCategory());

            // Owner Enforcement (V4 Hardened)
            if (category != null) {
                boolean missingOwner = "Unassigned".equals(category.owner()) || category.owner().isEmpty();

                if (missingOwner) {
                    if (category.type() == TestType.FULL) {
                        LOG.error("❌ Governance Failure: Owner required for FULL test: {}", testId);
                        throw new org.testng.SkipException("Governance skip: missing owner for FULL test.");
                    } else if (category.type() == TestType.REGRESSION) {
                        LOG.warn("⚠️ Governance Warning: Owner recommended for REGRESSION test: {}", testId);
                    }
                }
            }
        }

        driver.get(BASE_URL); // Navigate to ensure clean starting state for each test
    }

    // ---------- TEST TEARDOWN ----------

    @AfterMethod(alwaysRun = true)
    public void afterMethod(ITestResult result) {
        String testClass = result.getTestClass().getRealClass().getSimpleName();
        String testMethod = result.getName();

        if (result.getStatus() == ITestResult.FAILURE) {
            String artifactFolder = null;
            if (isDriverHealthy()) {
                artifactFolder = FailureArtifactManager.capture(driver, testMethod);
            }

            // Analytics: Test Failed
            TestAnalyticsLogger.get().testFailed(testClass, testMethod, result.getThrowable(), artifactFolder);

            // Record in HealthTracker as well
            HealthTracker ht = HealthTracker.get();
            ht.recordTestFailure(
                    testClass + "." + testMethod,
                    result.getThrowable().getMessage(),
                    null // Stack trace already captured in logger
            );
            TestCategory cat = getCategory(result);
            if (cat == null)
                cat = createDefaultCategory();
            ht.addTestRecord(cat.type().name(), cat.requiresLogin() ? "Auth" : "Guest",
                    cat.feature(), testClass, testMethod, "FAIL",
                    result.getEndMillis() - result.getStartMillis());
        } else if (result.getStatus() == ITestResult.SUCCESS) {
            // Analytics: Test Passed
            TestAnalyticsLogger.get().testPassed(testClass, testMethod);
            HealthTracker ht = HealthTracker.get();
            ht.recordTestSuccess();
            TestCategory cat = getCategory(result);
            if (cat == null)
                cat = createDefaultCategory();
            ht.addTestRecord(cat.type().name(), cat.requiresLogin() ? "Auth" : "Guest",
                    cat.feature(), testClass, testMethod, "PASS",
                    result.getEndMillis() - result.getStartMillis());
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

            // Per-suite history tracking
            String suiteName = System.getProperty("suiteFile", "manual");
            String env = System.getProperty("environment", "local");
            utils.HistoryJsonWriter.appendRun(utils.analytics.AnalyticsCollector.collect(), suiteName, env);

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

    // ---------- CATEGORY RESOLUTION ----------

    /**
     * Resolves @TestCategory — method-level overrides class-level.
     */
    protected TestCategory getCategory(ITestResult result) {
        Method m = result.getMethod().getConstructorOrMethod().getMethod();
        if (m != null && m.isAnnotationPresent(TestCategory.class)) {
            return m.getAnnotation(TestCategory.class);
        }
        Class<?> clazz = result.getTestClass().getRealClass();
        return clazz.getAnnotation(TestCategory.class);
    }

    /**
     * Validates that TestNG groups match @TestCategory type.
     * Logs a warning if they drift.
     */
    private void validateCategoryConsistency(ITestResult result, TestCategory cat) {
        String[] groups = result.getMethod().getGroups();
        String expectedGroup = cat.type().name().toLowerCase();

        boolean found = Arrays.asList(groups).contains(expectedGroup);
        // Smoke tests are also in sanity group, so check both
        if (!found && cat.type() == TestType.SMOKE) {
            found = Arrays.asList(groups).contains("smoke");
        }
        if (!found && cat.type() == TestType.SANITY) {
            found = Arrays.asList(groups).contains("sanity") || Arrays.asList(groups).contains("smoke");
        }

        if (!found) {
            LOG.warn("⚠ Category mismatch: @TestCategory({}) but groups={} for {}.{}",
                    cat.type(), Arrays.toString(groups),
                    result.getTestClass().getRealClass().getSimpleName(),
                    result.getMethod().getMethodName());
        }
    }

    /**
     * Validates that every @Test method belongs to at least one group.
     * Prevents silent test drift where new methods run in full suite but not in any
     * targeted run.
     */
    private void validateTestHasGroup(ITestResult result) {
        String[] groups = result.getMethod().getGroups();
        if (groups == null || groups.length == 0) {
            LOG.warn("⚠ Ungrouped test detected: {}.{} — will only run in full suite, not in targeted runs",
                    result.getTestClass().getRealClass().getSimpleName(),
                    result.getMethod().getMethodName());
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

    private TestCategory createDefaultCategory() {
        return new TestCategory() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return TestCategory.class;
            }

            @Override
            public TestType type() {
                return TestType.REGRESSION;
            }

            @Override
            public boolean requiresLogin() {
                return false;
            }

            @Override
            public String owner() {
                return "Unassigned";
            }

            @Override
            public String feature() {
                return "General";
            }

            @Override
            public TestType.Severity severity() {
                return TestType.Severity.MEDIUM;
            }

            @Override
            public TestType.FailureType defaultFailureType() {
                return TestType.FailureType.PRODUCT_BUG;
            }
        };
    }
}
