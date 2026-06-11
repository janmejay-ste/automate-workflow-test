package utils.health.cluster;

import java.util.Map;

/**
 * Fingerprint extractor for test-level failures.
 *
 * <p><b>Identity rule:</b> a test failure's identity is the
 * test class + test method + the first non-framework stack frame's
 * class.method (the "deepest user code that threw"). This catches the
 * common case where a flaky underlying utility throws from many test
 * methods — they all cluster on the same root cause.</p>
 *
 * <p>If no usable stack frame is available, the fingerprint falls back to
 * just test class + test method. This degrades gracefully: in the worst
 * case, each test method is its own cluster (no amplification reduction),
 * which is the same shape as today's flat scoring.</p>
 *
 * <p><b>Reads from event map:</b></p>
 * <ul>
 *   <li>{@code testClass} — fully-qualified test class name</li>
 *   <li>{@code testMethod} — test method name</li>
 *   <li>{@code stackTrace} — optional; first non-framework frame is extracted</li>
 *   <li>{@code reason} — optional; used as a backup when stackTrace is absent</li>
 * </ul>
 */
public final class TestFailureFingerprintExtractor implements FingerprintExtractor {

    /** Frame prefixes we consider "framework noise" and skip when picking the deepest user frame. */
    private static final String[] FRAMEWORK_PREFIXES = {
        "org.testng.", "org.junit.", "java.", "jdk.", "sun.",
        "org.openqa.selenium.", "io.github.bonigarcia.", "org.apache.maven.",
        "org.slf4j.", "ch.qos.logback.", "base.BaseTest"   // our own teardown
    };

    @Override
    public ContributorSource source() {
        return ContributorSource.TEST_FAILURES;
    }

    @Override
    public ClusterKey extract(Map<String, String> event) {
        if (event == null) return ClusterKey.UNKNOWN;

        String testClass  = norm(event.getOrDefault("testClass",  ""));
        String testMethod = norm(event.getOrDefault("testMethod", ""));
        String stack      = event.getOrDefault("stackTrace", "");
        String reason     = norm(event.getOrDefault("reason", ""));

        String userFrame = extractDeepestUserFrame(stack);
        // If no usable user frame, fall back to the reason string (truncated).
        // Reason is often the assertion message — much more stable than a stack
        // for tests that fail in setup/teardown.
        String secondary = userFrame != null ? userFrame
                : (reason.isBlank() ? "" : truncate(reason, 80));

        if (testClass.isBlank() && testMethod.isBlank() && secondary.isBlank()) {
            return ClusterKey.UNKNOWN;
        }

        return new ClusterKey("testFailures│"
                + emptyAsQuestion(testClass)  + "│"
                + emptyAsQuestion(testMethod) + "│"
                + emptyAsQuestion(secondary));
    }

    private static String extractDeepestUserFrame(String stack) {
        if (stack == null || stack.isBlank()) return null;
        for (String raw : stack.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("at ")) continue;
            String afterAt = line.substring(3);
            // Strip the trailing "(File.java:LINE)" segment to get class.method.
            int paren = afterAt.indexOf('(');
            String frame = paren > 0 ? afterAt.substring(0, paren) : afterAt;
            if (isFramework(frame)) continue;
            return frame.toLowerCase();
        }
        return null;
    }

    private static boolean isFramework(String frame) {
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (frame.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String emptyAsQuestion(String s) {
        return (s == null || s.isBlank()) ? "?" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
