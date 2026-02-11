package utils.analytics;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized analytics logger that captures comprehensive test execution data.
 * Tracks test failures, performance metrics, and execution patterns for
 * debugging.
 */
public class TestAnalyticsLogger {

    private static final Logger LOG = LoggerFactory.getLogger(TestAnalyticsLogger.class);
    private static final TestAnalyticsLogger INSTANCE = new TestAnalyticsLogger();

    private static final String ANALYTICS_DIR = "reports/analytics";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // Test execution tracking
    private final List<TestRecord> testRecords = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> testStartTimes = new ConcurrentHashMap<>();
    private final List<FailureRecord> failures = Collections.synchronizedList(new ArrayList<>());
    private final List<PerformanceRecord> performanceMetrics = Collections.synchronizedList(new ArrayList<>());

    // Session info
    private long sessionStartTime;
    private String sessionId;

    private TestAnalyticsLogger() {
        this.sessionStartTime = System.currentTimeMillis();
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
    }

    public static TestAnalyticsLogger get() {
        return INSTANCE;
    }

    // =====================================================================
    // TEST LIFECYCLE TRACKING
    // =====================================================================

    public void testStarted(String testClass, String testMethod) {
        String testId = testClass + "." + testMethod;
        testStartTimes.put(testId, System.currentTimeMillis());
        LOG.debug("Analytics: Test started - {}", testId);
    }

    public void testPassed(String testClass, String testMethod) {
        recordTest(testClass, testMethod, "PASSED", null, null);
    }

    public void testFailed(String testClass, String testMethod, Throwable exception) {
        String reason = exception != null ? exception.getMessage() : "Unknown error";
        String stackTrace = getStackTrace(exception);

        recordTest(testClass, testMethod, "FAILED", reason, stackTrace);

        // Also record in failures list for quick access
        failures.add(new FailureRecord(
                testClass,
                testMethod,
                reason,
                stackTrace,
                System.currentTimeMillis()));

        LOG.warn("Analytics: Test FAILED - {}.{}: {}", testClass, testMethod, reason);
    }

    public void testSkipped(String testClass, String testMethod, String reason) {
        recordTest(testClass, testMethod, "SKIPPED", reason, null);
    }

    private void recordTest(String testClass, String testMethod, String status,
            String failureReason, String stackTrace) {
        String testId = testClass + "." + testMethod;
        Long startTime = testStartTimes.remove(testId);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        testRecords.add(new TestRecord(
                testClass,
                testMethod,
                status,
                duration,
                failureReason,
                stackTrace,
                System.currentTimeMillis()));
    }

    // =====================================================================
    // PERFORMANCE TRACKING
    // =====================================================================

    public void recordPageLoad(String pageName, String url, long loadTimeMs) {
        performanceMetrics.add(new PerformanceRecord(
                "PAGE_LOAD",
                pageName,
                url,
                loadTimeMs,
                System.currentTimeMillis()));
        LOG.debug("Analytics: Page load - {} = {}ms", pageName, loadTimeMs);
    }

    public void recordNavigation(String label, long durationMs) {
        performanceMetrics.add(new PerformanceRecord(
                "NAVIGATION",
                label,
                null,
                durationMs,
                System.currentTimeMillis()));
    }

    public void recordApiCall(String endpoint, long responseTimeMs, int statusCode) {
        PerformanceRecord record = new PerformanceRecord(
                "API_CALL",
                endpoint,
                null,
                responseTimeMs,
                System.currentTimeMillis());
        record.statusCode = statusCode;
        performanceMetrics.add(record);
    }

    // =====================================================================
    // CUSTOM EVENT TRACKING
    // =====================================================================

    public void logEvent(String category, String action, String label) {
        LOG.info("Analytics Event: [{}] {} - {}", category, action, label);
    }

    public void logWarning(String context, String message) {
        LOG.warn("Analytics Warning: [{}] {}", context, message);
    }

    // =====================================================================
    // REPORT GENERATION
    // =====================================================================

    public void generateReports() {
        try {
            Path analyticsDir = Paths.get(ANALYTICS_DIR);
            Files.createDirectories(analyticsDir);

            // 1. Full analytics JSON
            generateFullAnalyticsJson(analyticsDir);

            // 2. Failures only JSON
            generateFailuresJson(analyticsDir);

            // 3. Human-readable summary
            generateSummaryText(analyticsDir);

            LOG.info("Analytics reports generated in {}", ANALYTICS_DIR);

        } catch (Exception e) {
            LOG.error("Failed to generate analytics reports: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void generateFullAnalyticsJson(Path dir) throws Exception {
        JSONObject root = new JSONObject();

        // Session info
        JSONObject session = new JSONObject();
        session.put("sessionId", sessionId);
        session.put("startTime", DATE_FORMAT.format(new Date(sessionStartTime)));
        session.put("endTime", DATE_FORMAT.format(new Date()));
        session.put("durationMs", System.currentTimeMillis() - sessionStartTime);
        root.put("session", session);

        // Summary statistics
        JSONObject summary = new JSONObject();
        long passed = testRecords.stream().filter(t -> "PASSED".equals(t.status)).count();
        long failed = testRecords.stream().filter(t -> "FAILED".equals(t.status)).count();
        long skipped = testRecords.stream().filter(t -> "SKIPPED".equals(t.status)).count();
        double avgDuration = testRecords.stream().mapToLong(t -> t.durationMs).average().orElse(0);

        summary.put("totalTests", testRecords.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("passRate", testRecords.isEmpty() ? 0 : (passed * 100.0 / testRecords.size()));
        summary.put("avgDurationMs", avgDuration);
        root.put("summary", summary);

        // Test records
        JSONArray tests = new JSONArray();
        for (TestRecord tr : testRecords) {
            JSONObject test = new JSONObject();
            test.put("class", tr.testClass);
            test.put("method", tr.testMethod);
            test.put("status", tr.status);
            test.put("durationMs", tr.durationMs);
            if (tr.failureReason != null) {
                test.put("failureReason", tr.failureReason);
            }
            if (tr.stackTrace != null) {
                test.put("stackTrace", tr.stackTrace);
            }
            tests.add(test);
        }
        root.put("tests", tests);

        // Performance metrics
        JSONArray perf = new JSONArray();
        for (PerformanceRecord pr : performanceMetrics) {
            JSONObject metric = new JSONObject();
            metric.put("type", pr.type);
            metric.put("name", pr.name);
            metric.put("durationMs", pr.durationMs);
            if (pr.url != null)
                metric.put("url", pr.url);
            if (pr.statusCode > 0)
                metric.put("statusCode", pr.statusCode);
            perf.add(metric);
        }
        root.put("performance", perf);

        // Failures detail
        JSONArray failuresArr = new JSONArray();
        for (FailureRecord fr : failures) {
            JSONObject fail = new JSONObject();
            fail.put("class", fr.testClass);
            fail.put("method", fr.testMethod);
            fail.put("reason", fr.reason);
            fail.put("stackTrace", fr.stackTrace);
            fail.put("timestamp", DATE_FORMAT.format(new Date(fr.timestamp)));
            failuresArr.add(fail);
        }
        root.put("failures", failuresArr);

        Files.write(dir.resolve("test_analytics.json"),
                root.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private void generateFailuresJson(Path dir) throws Exception {
        JSONObject root = new JSONObject();
        root.put("totalFailures", failures.size());
        root.put("generatedAt", DATE_FORMAT.format(new Date()));

        JSONArray failuresArr = new JSONArray();
        for (FailureRecord fr : failures) {
            JSONObject fail = new JSONObject();
            fail.put("test", fr.testClass + "." + fr.testMethod);
            fail.put("reason", fr.reason);
            fail.put("stackTrace", fr.stackTrace);
            failuresArr.add(fail);
        }
        root.put("failures", failuresArr);

        Files.write(dir.resolve("failures.json"),
                root.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private void generateSummaryText(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();

        sb.append("=".repeat(60)).append("\n");
        sb.append("  TEST ANALYTICS SUMMARY\n");
        sb.append("=".repeat(60)).append("\n\n");

        sb.append("Session ID: ").append(sessionId).append("\n");
        sb.append("Start Time: ").append(DATE_FORMAT.format(new Date(sessionStartTime))).append("\n");
        sb.append("End Time:   ").append(DATE_FORMAT.format(new Date())).append("\n");
        sb.append("Duration:   ").append(formatDuration(System.currentTimeMillis() - sessionStartTime)).append("\n\n");

        long passed = testRecords.stream().filter(t -> "PASSED".equals(t.status)).count();
        long failed = testRecords.stream().filter(t -> "FAILED".equals(t.status)).count();
        long skipped = testRecords.stream().filter(t -> "SKIPPED".equals(t.status)).count();

        sb.append("-".repeat(40)).append("\n");
        sb.append("  TEST RESULTS\n");
        sb.append("-".repeat(40)).append("\n");
        sb.append(String.format("  Total:   %d\n", testRecords.size()));
        sb.append(String.format("  Passed:  %d ✓\n", passed));
        sb.append(String.format("  Failed:  %d ✗\n", failed));
        sb.append(String.format("  Skipped: %d ○\n", skipped));
        sb.append(String.format("  Pass Rate: %.1f%%\n",
                testRecords.isEmpty() ? 0 : (passed * 100.0 / testRecords.size())));
        sb.append("\n");

        if (!failures.isEmpty()) {
            sb.append("-".repeat(40)).append("\n");
            sb.append("  FAILURES\n");
            sb.append("-".repeat(40)).append("\n");
            for (FailureRecord fr : failures) {
                sb.append(String.format("  [FAIL] %s.%s\n", fr.testClass, fr.testMethod));
                sb.append(String.format("         Reason: %s\n\n", fr.reason));
            }
        }

        if (!performanceMetrics.isEmpty()) {
            sb.append("-".repeat(40)).append("\n");
            sb.append("  PERFORMANCE METRICS\n");
            sb.append("-".repeat(40)).append("\n");

            // Group by type
            double avgPageLoad = performanceMetrics.stream()
                    .filter(p -> "PAGE_LOAD".equals(p.type))
                    .mapToLong(p -> p.durationMs)
                    .average().orElse(0);

            long slowestPage = performanceMetrics.stream()
                    .filter(p -> "PAGE_LOAD".equals(p.type))
                    .mapToLong(p -> p.durationMs)
                    .max().orElse(0);

            sb.append(String.format("  Avg Page Load: %.0fms\n", avgPageLoad));
            sb.append(String.format("  Slowest Page:  %dms\n", slowestPage));
        }

        sb.append("\n").append("=".repeat(60)).append("\n");

        Files.write(dir.resolve("summary.txt"),
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // =====================================================================
    // HELPER CLASSES
    // =====================================================================

    private static class TestRecord {
        final String testClass;
        final String testMethod;
        final String status;
        final long durationMs;
        final String failureReason;
        final String stackTrace;

        TestRecord(String testClass, String testMethod, String status,
                long durationMs, String failureReason, String stackTrace, long timestamp) {
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.status = status;
            this.durationMs = durationMs;
            this.failureReason = failureReason;
            this.stackTrace = stackTrace;
        }
    }

    private static class FailureRecord {
        final String testClass;
        final String testMethod;
        final String reason;
        final String stackTrace;
        final long timestamp;

        FailureRecord(String testClass, String testMethod, String reason,
                String stackTrace, long timestamp) {
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.reason = reason;
            this.stackTrace = stackTrace;
            this.timestamp = timestamp;
        }
    }

    private static class PerformanceRecord {
        final String type;
        final String name;
        final String url;
        final long durationMs;
        int statusCode;

        PerformanceRecord(String type, String name, String url, long durationMs, long timestamp) {
            this.type = type;
            this.name = name;
            this.url = url;
            this.durationMs = durationMs;
        }
    }

    // =====================================================================
    // UTILITIES
    // =====================================================================

    private String getStackTrace(Throwable t) {
        if (t == null)
            return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        // Truncate very long stack traces
        return trace.length() > 2000 ? trace.substring(0, 2000) + "\n... (truncated)" : trace;
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%dm %ds", minutes, seconds);
    }

    // =====================================================================
    // GETTERS FOR EXTERNAL USE
    // =====================================================================

    public int getTotalTests() {
        return testRecords.size();
    }

    public int getFailedCount() {
        return failures.size();
    }

    public List<FailureRecord> getFailures() {
        return Collections.unmodifiableList(failures);
    }
}
