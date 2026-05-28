package utils.history;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rolling aggregate of per-test stability intelligence.
 *
 * Schema contract (schemaVersion = 1):
 *   testId, metrics{totalRuns, totalPasses, passRate, failureRate,
 *     retryDependencyRate, oscillationRate, stabilityScore},
 *   recentHistory[last N "PASS"/"FAIL" strings], lastUpdated
 *
 * Stability score formula (deterministic, no ML):
 *   score = (passRate × 70) − (retryDependencyRate × 20) − (oscillationRate × 10)
 *   clamped to [0, 100]
 *
 * Flaky threshold: stabilityScore < 70 AND oscillationRate > 0.2
 */
public final class TestStabilityTracker {
    private static final Logger LOG = LoggerFactory.getLogger(TestStabilityTracker.class);
    static final String TESTS_DIR = "reports/history/tests";

    private TestStabilityTracker() {}

    @SuppressWarnings("unchecked")
    public static void update(String testId, boolean passed) {
        if (testId == null || testId.isBlank()) return;
        try {
            Files.createDirectories(Paths.get(TESTS_DIR));
            Path path = Paths.get(TESTS_DIR, sanitize(testId) + ".json");
            JSONObject record = loadOrInit(path, testId);

            JSONObject metrics = (JSONObject) record.get("metrics");

            long totalRuns   = lng(metrics, "totalRuns",   0L) + 1;
            long totalPasses = lng(metrics, "totalPasses", 0L) + (passed ? 1L : 0L);
            double passRate  = totalRuns > 0 ? totalPasses / (double) totalRuns : 1.0;

            metrics.put("totalRuns",   totalRuns);
            metrics.put("totalPasses", totalPasses);
            metrics.put("passRate",    round2(passRate));
            metrics.put("failureRate", round2(1.0 - passRate));

            // Update bounded recentHistory
            int window = HistoryConfig.getStabilityWindow();
            JSONArray recent = (JSONArray) record.get("recentHistory");
            recent.add(passed ? "PASS" : "FAIL");
            while (recent.size() > window) recent.remove(0);

            // Oscillation = transition density in recent window
            double oscillation = computeOscillation(recent);
            metrics.put("oscillationRate", round2(oscillation));

            // Stability score — deterministic formula
            double retryRate = dbl(metrics, "retryDependencyRate", 0.0);
            double score     = Math.max(0.0, Math.min(100.0,
                    (passRate * 70.0) - (retryRate * 20.0) - (oscillation * 10.0)));
            metrics.put("stabilityScore", (long) Math.round(score));

            record.put("lastUpdated", Instant.now().toString());
            Files.writeString(path, record.toJSONString());

        } catch (Exception e) {
            LOG.debug("TestStabilityTracker.update failed for {}: {}", testId, e.getMessage());
        }
    }

    /** Records a retry dependency observation for the given test. */
    @SuppressWarnings("unchecked")
    public static void recordRetry(String testId) {
        if (testId == null || testId.isBlank()) return;
        try {
            Path path = Paths.get(TESTS_DIR, sanitize(testId) + ".json");
            if (!Files.exists(path)) return;
            JSONObject record  = (JSONObject) new JSONParser().parse(Files.readString(path));
            JSONObject metrics = (JSONObject) record.get("metrics");

            long totalRuns   = lng(metrics, "totalRuns", 1L);
            long retries     = lng(metrics, "totalRetries", 0L) + 1;
            metrics.put("totalRetries",       retries);
            metrics.put("retryDependencyRate", round2(retries / (double) totalRuns));

            record.put("lastUpdated", Instant.now().toString());
            Files.writeString(path, record.toJSONString());
        } catch (Exception e) {
            LOG.debug("TestStabilityTracker.recordRetry failed for {}: {}", testId, e.getMessage());
        }
    }

    public static JSONObject load(String testId) {
        Path path = Paths.get(TESTS_DIR, sanitize(testId) + ".json");
        if (!Files.exists(path)) return null;
        try {
            return (JSONObject) new JSONParser().parse(Files.readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    public static List<JSONObject> loadAll() {
        try {
            Path dir = Paths.get(TESTS_DIR);
            if (!Files.exists(dir)) return List.of();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> {
                            try { return (JSONObject) new JSONParser().parse(Files.readString(p)); }
                            catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static JSONObject loadOrInit(Path path, String testId) {
        if (Files.exists(path)) {
            try { return (JSONObject) new JSONParser().parse(Files.readString(path)); }
            catch (Exception ignored) {}
        }
        JSONObject rec     = new JSONObject();
        JSONObject metrics = new JSONObject();
        rec.put("schemaVersion", 1L);
        rec.put("testId", testId);
        metrics.put("totalRuns",          0L);
        metrics.put("totalPasses",        0L);
        metrics.put("totalRetries",       0L);
        metrics.put("passRate",           1.0);
        metrics.put("failureRate",        0.0);
        metrics.put("retryDependencyRate", 0.0);
        metrics.put("oscillationRate",    0.0);
        metrics.put("stabilityScore",     100L);
        rec.put("metrics", metrics);
        rec.put("recentHistory", new JSONArray());
        rec.put("lastUpdated", Instant.now().toString());
        return rec;
    }

    private static double computeOscillation(JSONArray history) {
        if (history.size() < 2) return 0.0;
        int transitions = 0;
        for (int i = 1; i < history.size(); i++) {
            if (!history.get(i).equals(history.get(i - 1))) transitions++;
        }
        return transitions / (double) (history.size() - 1);
    }

    static String sanitize(String testId) {
        return testId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static double round2(double v)       { return Math.round(v * 100.0) / 100.0; }
    private static long    lng(JSONObject o, String k, long def)   { Object v = o.get(k); return v instanceof Number ? ((Number)v).longValue()   : def; }
    private static double  dbl(JSONObject o, String k, double def) { Object v = o.get(k); return v instanceof Number ? ((Number)v).doubleValue() : def; }
}
