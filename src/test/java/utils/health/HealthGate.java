package utils.health;

import org.testng.Assert;
import utils.HealthPolicy;

/**
 * Enforces health score thresholds at suite teardown.
 *
 * Called from BaseTest.afterSuite() inside a finally block, AFTER all reports
 * have been flushed. Uses Assert.fail() (throws AssertionError) rather than
 * RuntimeException so TestNG/Surefire handles the failure cleanly and lifecycle
 * hooks (screenshots, artifact cleanup) are not disrupted.
 *
 * Gate conditions:
 *   1. Smoothed score < BUILD_FAIL_THRESHOLD (default 40, overridden via -Dhealth.fail.score=N)
 *   2. criticalBroken flag set (uncaught JS exception or fatal API failure recorded)
 *
 * Smoothed score (EMA) avoids single-run noise tripping the gate while still
 * catching sustained degradation over multiple runs.
 */
public final class HealthGate {

    private HealthGate() {}

    public static void enforce(HealthTracker tracker) {
        int score    = tracker.getScore();
        int smoothed = tracker.getSmoothedScore();
        String status = tracker.getStatus();

        System.out.printf("[HealthGate] Score: %d (smoothed: %d) | Status: %s%n",
                score, smoothed, status);

        StringBuilder violations = new StringBuilder();

        // Critical business flows failing bypass the score entirely.
        // A healthy score is meaningless when core user journeys are broken.
        if (tracker.hasCriticalFlowFailures()) {
            violations.append("  - Critical business flows failed (score irrelevant when these break):\n");
            tracker.getCriticalFlowFailures().forEach(f ->
                    violations.append("      * ").append(f).append("\n"));
        }

        // JS/API runtime errors that set criticalBroken without a named flow
        if (tracker.isCriticalBroken() && !tracker.hasCriticalFlowFailures()) {
            violations.append("  - Critical runtime error: uncaught JS exception or fatal API failure\n");
        }

        if (smoothed < HealthPolicy.BUILD_FAIL_THRESHOLD) {
            violations.append(String.format(
                    "  - Smoothed health score %d is below build threshold %d%n",
                    smoothed, HealthPolicy.BUILD_FAIL_THRESHOLD));
        }

        if (!violations.isEmpty()) {
            String msg = "[HealthGate] BUILD FAILED — health constraints violated:\n" + violations
                    + "  Fix test failures, eliminate JS errors, or improve page stability to raise the score.";
            System.err.println(msg);
            Assert.fail(msg);
        }

        System.out.printf("[HealthGate] PASSED — smoothed score %d >= threshold %d%n",
                smoothed, HealthPolicy.BUILD_FAIL_THRESHOLD);
    }
}
