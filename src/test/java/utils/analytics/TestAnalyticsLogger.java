package utils.analytics;

import base.TestCategory;
import base.TestType;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clean orchestrator for test lifecycle analytics.
 * Delegates specialized tracking to decentralized trackers.
 */
public class TestAnalyticsLogger {

    private static final Logger LOG = LoggerFactory.getLogger(TestAnalyticsLogger.class);
    private static final TestAnalyticsLogger INSTANCE = new TestAnalyticsLogger();

    private static final String ANALYTICS_DIR = "reports/analytics";

    private final List<TestRecord> testRecords = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> testStartTimes = new ConcurrentHashMap<>();
    private final Map<String, TestCategory> testMetadataMap = new ConcurrentHashMap<>();
    private final List<FailureRecord> failures = Collections.synchronizedList(new ArrayList<>());

    private final long sessionStartTime;
    private final String sessionId;

    private TestAnalyticsLogger() {
        this.sessionStartTime = System.currentTimeMillis();
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
    }

    public static TestAnalyticsLogger get() {
        return INSTANCE;
    }

    public void testStarted(String testClass, String testMethod, TestCategory metadata) {
        String testId = testClass + "." + testMethod;
        testStartTimes.put(testId, System.currentTimeMillis());
        testMetadataMap.put(testId, metadata);
        LOG.debug("Analytics: Test started - {} [{}]", testId, metadata.type());
    }

    public void testPassed(String testClass, String testMethod) {
        recordTest(testClass, testMethod, "PASSED", null, null, null);
    }

    public void testFailed(String testClass, String testMethod, Throwable exception, String artifactFolder) {
        String testId = testClass + "." + testMethod;
        TestCategory meta = testMetadataMap.get(testId);

        TestType.FailureType failureType = utils.policy.SeverityClassifier.classify(exception, meta);
        TestType.Severity severity = (meta != null) ? meta.severity() : TestType.Severity.MEDIUM;
        String owner = (meta != null) ? meta.owner() : "Unassigned";

        String reason = exception != null ? exception.getMessage() : "Unknown error";
        String stackTrace = getStackTrace(exception);

        recordTest(testClass, testMethod, "FAILED", reason, stackTrace, failureType);

        failures.add(new FailureRecord(
                testClass, testMethod, reason, stackTrace,
                artifactFolder, System.currentTimeMillis(),
                failureType, severity, owner));

        LOG.warn("Analytics: Test FAILED - {}.{}: {} (Type: {}, Severity: {})",
                testClass, testMethod, reason, failureType, severity);
    }

    public void testSkipped(String testClass, String testMethod, String reason) {
        recordTest(testClass, testMethod, "SKIPPED", reason, null, null);
    }

    private void recordTest(String testClass, String testMethod, String status,
            String failureReason, String stackTrace, TestType.FailureType failureType) {
        String testId = testClass + "." + testMethod;
        Long startTime = testStartTimes.remove(testId);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        TestCategory meta = testMetadataMap.get(testId);

        testRecords.add(new TestRecord(
                testClass, testMethod, status, duration, failureReason, stackTrace,
                System.currentTimeMillis(),
                meta != null ? meta.type().name() : "UNKNOWN",
                meta != null && meta.requiresLogin(),
                meta != null ? meta.feature() : "General",
                meta != null ? meta.severity().name() : "MEDIUM",
                meta != null ? meta.owner() : "Unassigned",
                failureType));
    }

    @SuppressWarnings("unchecked")
    public void generateReports() {
        try {
            Path dir = Paths.get(ANALYTICS_DIR);
            Files.createDirectories(dir);

            JSONObject root = AnalyticsCollector.collect();

            // Add Session/Test info to collector data
            JSONObject session = new JSONObject();
            session.put("sessionId", sessionId);
            session.put("durationMs", System.currentTimeMillis() - sessionStartTime);
            root.put("session", session);

            JSONArray tests = new JSONArray();
            for (TestRecord tr : testRecords) {
                JSONObject test = new JSONObject();
                test.put("name", tr.testClass + "." + tr.testMethod);
                test.put("status", tr.status);
                test.put("durationMs", tr.durationMs);
                test.put("owner", tr.owner);
                test.put("severity", tr.severity);
                tests.add(test);
            }
            root.put("testDetails", tests);

            Files.write(dir.resolve("test_analytics.json"),
                    root.toJSONString().getBytes(StandardCharsets.UTF_8));
            LOG.info("Analytics reports generated in {}", ANALYTICS_DIR);

        } catch (Exception e) {
            LOG.error("Failed to generate analytics reports: {}", e.getMessage());
        }
    }

    private String getStackTrace(Throwable t) {
        if (t == null)
            return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        return trace.length() > 2000 ? trace.substring(0, 2000) + "\n... (truncated)" : trace;
    }

    public static class TestRecord {
        final String testClass, testMethod, status;
        final long durationMs;
        final String failureReason, stackTrace, testType;
        final boolean requiresLogin;
        final String feature, severity, owner;
        final TestType.FailureType failureType;

        TestRecord(String testClass, String testMethod, String status, long durationMs,
                String failureReason, String stackTrace, long timestamp, String testType,
                boolean requiresLogin, String feature, String severity, String owner,
                TestType.FailureType failureType) {
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.status = status;
            this.durationMs = durationMs;
            this.failureReason = failureReason;
            this.stackTrace = stackTrace;
            this.testType = testType;
            this.requiresLogin = requiresLogin;
            this.feature = feature;
            this.severity = severity;
            this.owner = owner;
            this.failureType = failureType;
        }
    }

    public static class FailureRecord {
        final String testClass, testMethod, reason, stackTrace, artifactFolder;
        final long timestamp;
        final TestType.FailureType failureType;
        final TestType.Severity severity;
        final String owner;

        FailureRecord(String testClass, String testMethod, String reason, String stackTrace,
                String artifactFolder, long timestamp, TestType.FailureType failureType,
                TestType.Severity severity, String owner) {
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.reason = reason;
            this.stackTrace = stackTrace;
            this.artifactFolder = artifactFolder;
            this.timestamp = timestamp;
            this.failureType = failureType;
            this.severity = severity;
            this.owner = owner;
        }
    }
}
