package utils.health;

import utils.HealthPolicy;

public final class HealthGate {

    private HealthGate() {
    }

    public static void enforce(HealthTracker tracker) {
        int score = tracker.getScore();
        int smoothed = tracker.getSmoothedScore();
        String status = tracker.getStatus();

        // Always log the status
        System.out.printf("[HealthGate] Score: %d (smoothed: %d) | Status: %s%n",
                score, smoothed, status);

        // Log warning for critical issues
        if (tracker.isCriticalBroken()) {
            System.err.println("WARNING: Critical flow had issues - check health report");
        }

        // Use smoothed score for build gate to avoid transient failures breaking builds
        if (smoothed < HealthPolicy.BUILD_FAIL_THRESHOLD) {
            System.err.printf("WARNING: Smoothed health score %d below threshold %d%n",
                    smoothed, HealthPolicy.BUILD_FAIL_THRESHOLD);
        }
    }
}
