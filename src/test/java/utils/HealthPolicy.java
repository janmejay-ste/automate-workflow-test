package utils;

public final class HealthPolicy {

    private HealthPolicy() {}

    public static final int STABLE_MIN = 10;
    public static final int DEGRADED_MIN = 0;

    public static final int BUILD_FAIL_THRESHOLD =
            Integer.parseInt(System.getProperty("health.fail.score", "10"));

    public static final long SLOW_PAGE_MS = 5_000;
    public static final long VERY_SLOW_PAGE_MS = 10_000;

    public static int penalty(utils.health.HealthTracker.Severity severity, boolean criticalFlow) {
        return switch (severity) {
            case CRITICAL -> criticalFlow ? 12 : 4;
            case HIGH     -> criticalFlow ? 8  : 3;
            case MEDIUM   -> criticalFlow ? 4  : 1;
            case LOW      -> 1;
        };
    }
}
