package utils;

import utils.health.HealthTracker;

public class TrendExporter {
    public static void updateTrend(int score) {
        HealthTracker tracker = HealthTracker.get();
        tracker.writeSnapshot(); // Update current health snapshot for dashboard

        int penalty = Math.max(0, 100 - score);
        String status = tracker.getStatus();
        int jsErrors = tracker.getJsErrors().size();
        int failedTests = tracker.getTestFailures().size();

        TrendDataWriter.appendRun(score, penalty, status, jsErrors, failedTests);
        System.out.println("Trend updated. Score=" + score);
    }
}
