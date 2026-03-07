package utils.health;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.HealthPolicy;

/**
 * Singleton tracker for runtime health metrics and penalties.
 * Aggregates penalties from various sources and calculates health scores.
 */
public class HealthTracker {

    private static final HealthTracker INSTANCE = new HealthTracker();
    private String suiteName = "REGRESSION";
    private String environment = "QA";
    private double rawPenalty = 0;
    private double warningPenalty = 0;
    private boolean criticalBroken = false;
    private long healthyCount = 0;

    // Breakdown for transparency
    private double testFailurePenalty = 0;
    private double jsErrorPenalty = 0;
    private double fallbackPenalty = 0;
    private double slowPagePenaltyValue = 0;

    private final List<String> warningsList = new ArrayList<>();
    private final List<Map<String, String>> fallbacks = new ArrayList<>();
    private final List<Map<String, String>> jsErrorsList = new ArrayList<>();
    private final List<Map<String, String>> slowPagesList = new ArrayList<>();
    private final List<Map<String, String>> testFailuresList = new ArrayList<>();
    private final JSONArray testRecords = new JSONArray();

    private HealthTracker() {
    }

    public static HealthTracker get() {
        return INSTANCE;
    }

    // ────────────────────────── penalty tracking ──────────────────────────

    public synchronized void addWarning(String context, String message) {
        if (warningPenalty >= HealthPolicy.WARNING_CAP)
            return;
        warningsList.add(context + ": " + message);
        warningPenalty += HealthPolicy.BASE_WARNING;
        rawPenalty += HealthPolicy.BASE_WARNING;
    }

    public void warn(String message) {
        addWarning("Global", message);
    }

    public synchronized void recordFallback(String step, String url) {
        fallbacks.add(Map.of("name", step, "target", url));
        double pts = HealthPolicy.fallbackPenalty(step);
        fallbackPenalty += pts;
        rawPenalty += pts;
    }

    public synchronized void recordJsError(String context, String message, boolean critical) {
        jsErrorsList.add(Map.of("context", context, "message", message));
        if (critical)
            criticalBroken = true;

        double pts = HealthPolicy.jsErrorPenalty(context);
        jsErrorPenalty += pts;
        rawPenalty += pts;
    }

    public synchronized void recordSlowPage(String context, long loadTimeMs) {
        slowPagesList.add(Map.of("url", context, "loadTime", String.valueOf(loadTimeMs), "severity",
                loadTimeMs > 5000 ? "critical" : loadTimeMs > 3000 ? "high" : "medium"));
        double pts = HealthPolicy.slowPagePenalty(loadTimeMs, context);
        slowPagePenaltyValue += pts;
        rawPenalty += pts;
    }

    // ────────────────────────── scoring ──────────────────────────

    public int getScore() {
        double effectivePenalty = Math.min(rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
        return (int) Math.max(0, 100 - Math.round(effectivePenalty));
    }

    public int getSmoothedScore() {
        int raw = getScore();
        int prev = readPreviousSmoothedScore();
        if (prev < 0)
            return raw;
        return (int) Math.round(HealthPolicy.EMA_ALPHA * raw + (1 - HealthPolicy.EMA_ALPHA) * prev);
    }

    public String getStatus() {
        return HealthPolicy.statusFromScore(getScore());
    }

    public boolean isCriticalBroken() {
        return criticalBroken;
    }

    public double getRawPenalty() {
        return rawPenalty;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warningsList);
    }

    public JSONArray getFallbacks() {
        JSONArray arr = new JSONArray();
        arr.addAll(fallbacks);
        return arr;
    }

    public JSONArray getJsErrors() {
        JSONArray arr = new JSONArray();
        arr.addAll(jsErrorsList);
        return arr;
    }

    public JSONArray getSlowPages() {
        JSONArray arr = new JSONArray();
        arr.addAll(slowPagesList);
        return arr;
    }

    public JSONArray getTestFailures() {
        JSONArray arr = new JSONArray();
        arr.addAll(testFailuresList);
        return arr;
    }

    public JSONArray getTestRecords() {
        return testRecords;
    }

    @SuppressWarnings("unchecked")
    public synchronized void addTestRecord(String category, String login, String feature, String clazz, String method,
            String status, long duration) {
        JSONObject rec = new JSONObject();
        rec.put("category", category);
        rec.put("login", login);
        rec.put("feature", feature);
        rec.put("class", clazz);
        rec.put("method", method);
        rec.put("status", status);
        rec.put("duration", duration);
        testRecords.add(rec);
    }

    public String getSuiteName() {
        return suiteName;
    }

    public void setSuiteName(String name) {
        this.suiteName = name;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String env) {
        this.environment = env;
    }

    public long getHealthyCount() {
        return healthyCount;
    }

    // ────────────────────────── reporting ──────────────────────────

    public void printReport() {
        System.out.println("\n========= HEALTH REPORT =========");
        System.out.printf("Raw Score    : %d (penalty %.1f, capped at %d)%n",
                getScore(), rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
        System.out.println("Status       : " + getStatus());
        System.out.printf("  JS Errors  : %d (penalty %.1f)%n",
                jsErrorsList.size(), jsErrorPenalty);
        System.out.printf("  Fallbacks  : %d (penalty %.1f)%n",
                fallbacks.size(), fallbackPenalty);
        System.out.printf("  Slow Pages : %d (penalty %.1f)%n",
                slowPagesList.size(), slowPagePenaltyValue);
        System.out.printf("  Test Fails : %d (penalty %.1f)%n",
                testFailuresList.size(), testFailurePenalty);
        System.out.println("================================\n");
    }

    // ────────────────────────── failure tracking ──────────────────────────

    public synchronized void recordTestFailure(String testName, String reason, String stackTrace) {
        testFailuresList.add(Map.of("test", testName, "reason", reason != null ? reason : "Unknown", "stackTrace",
                stackTrace != null ? stackTrace : ""));
        double pts = HealthPolicy.testFailurePenalty(testName);
        testFailurePenalty += pts;
        rawPenalty += pts;
    }

    public synchronized void recordTestSuccess() {
        healthyCount++;
    }

    public void recordTestDuration(String testName, long durationMs) {
        // Duration tracking for future analytics
    }

    // ────────────────────────── storage ──────────────────────────

    @SuppressWarnings("unchecked")
    public void writeSnapshot() {
        try {
            JSONObject json = new JSONObject();
            json.put("score", getScore());
            json.put("smoothedScore", getSmoothedScore());
            json.put("status", getStatus());
            json.put("penalty", rawPenalty);
            json.put("timestamp", System.currentTimeMillis());

            JSONArray fails = new JSONArray();
            fails.addAll(testFailuresList);
            json.put("testFailures", fails);

            Path path = Paths.get("reports/trend/health_snapshot.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.toJSONString());
        } catch (Exception e) {
            System.err.println("Failed to write snapshot: " + e.getMessage());
        }
    }

    private int readPreviousSmoothedScore() {
        try {
            Path path = Paths.get("reports/trend/history.csv");
            if (!Files.exists(path))
                return -1;
            List<String> lines = Files.readAllLines(path);
            if (lines.size() < 2)
                return -1;
            String lastLine = lines.get(lines.size() - 1);
            String[] parts = lastLine.split(",");
            if (parts.length >= 2)
                return Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
        }
        return -1;
    }
}
