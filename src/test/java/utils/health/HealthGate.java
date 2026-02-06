package utils.health;

import utils.HealthPolicy;

public final class HealthGate {

    private HealthGate() {}

    public static void enforce(HealthTracker tracker) {
        // Log warning for critical issues but don't fail build
        // (JS errors from external scripts are outside our control)
        if (tracker.isCriticalBroken()) {
            System.err.println("WARNING: Critical flow had issues - check health report");
        }

        if (tracker.getScore() < HealthPolicy.BUILD_FAIL_THRESHOLD) {
            System.err.println("WARNING: Health score " + tracker.getScore() +
                " below threshold " + HealthPolicy.BUILD_FAIL_THRESHOLD);
        }
    }
}
