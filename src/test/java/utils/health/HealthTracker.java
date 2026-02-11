package utils.health;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.HealthPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class HealthTracker {

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    private static final HealthTracker INSTANCE = new HealthTracker();

    public static HealthTracker get() {
        return INSTANCE;
    }

    // ────────────────────────── penalty tracking ──────────────────────────

    private double rawPenalty = 0;
    private double warningPenalty = 0;
    private boolean criticalBroken = false;

    // Breakdown for transparency
    private double testFailurePenalty = 0;
    private double jsErrorPenalty = 0;
    private double fallbackPenalty = 0;
    private double slowPagePenalty = 0;

    private final List<String> warnings = new ArrayList<>();
    private final List<String> fallbackDetails = new ArrayList<>();
    private final List<Map<String, String>> jsErrors = new ArrayList<>();
    private final List<Map<String, String>> slowPages = new ArrayList<>();

    private HealthTracker() {
    }

    // ────────────────────────── recording ──────────────────────────

    /** Low-severity warning (0.5 pts each, capped at WARNING_CAP). */
    public void addWarning(String context, String message) {
        warnings.add(context + " - " + message);
        double pts = HealthPolicy.BASE_WARNING;
        if (warningPenalty + pts <= HealthPolicy.WARNING_CAP) {
            warningPenalty += pts;
            rawPenalty += pts;
        }
        // Beyond cap: still recorded, but no more penalty
    }

    /** Convenience: add warning without context prefix. */
    public void warn(String message) {
        addWarning("General", message);
    }

    /** Fallback navigation — penalized based on flow type. */
    public void recordFallback(String step, String url) {
        fallbackDetails.add(step + " → " + url);
        double pts = HealthPolicy.fallbackPenalty(step);
        fallbackPenalty += pts;
        rawPenalty += pts;
    }

    /**
     * JS error — penalized based on flow type (critical flag still sets
     * criticalBroken).
     */
    public void recordJsError(String context, String message, boolean critical) {
        double pts = HealthPolicy.jsErrorPenalty(context);
        jsErrorPenalty += pts;
        rawPenalty += pts;

        jsErrors.add(Map.of(
                "context", context,
                "message", firstLine(message),
                "penalty", String.valueOf(pts)));

        if (critical) {
            criticalBroken = true;
        }
    }

    /** Slow page — tiered penalty based on load time and flow type. */
    public void recordSlowPage(String context, long loadTimeMs) {
        double pts = HealthPolicy.slowPagePenalty(loadTimeMs, context);
        if (pts > 0) {
            slowPagePenalty += pts;
            rawPenalty += pts;
            slowPages.add(Map.of(
                    "context", context,
                    "loadTimeMs", String.valueOf(loadTimeMs),
                    "penalty", String.valueOf(pts)));
        }
    }

    // ────────────────────────── suppression ──────────────────────────

    public boolean isSuppressedConsole(String text) {
        if (text == null)
            return false;
        String s = text.toLowerCase();
        return s.contains("title unexpected")
                || s.contains("mcp server")
                || s.contains("fedcm")
                || s.contains("accounts.google.com")
                || s.contains("deprecated")
                || s.contains("clarity");
    }

    // ────────────────────────── derived ──────────────────────────

    /** Raw score with soft penalty cap applied. */
    public int getScore() {
        double effective = Math.min(rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
        return Math.max(0, (int) Math.round(100 - effective));
    }

    /**
     * Smoothed score using EMA: alpha × current + (1-alpha) × previous.
     * Falls back to raw score if no history is available.
     */
    public int getSmoothedScore() {
        int raw = getScore();
        int prev = readPreviousSmoothedScore();
        if (prev < 0)
            return raw; // No history
        return (int) Math.round(
                HealthPolicy.EMA_ALPHA * raw +
                        (1 - HealthPolicy.EMA_ALPHA) * prev);
    }

    /** 5-tier status from raw score. */
    public String getStatus() {
        return HealthPolicy.statusFromScore(getScore());
    }

    public boolean isCriticalBroken() {
        return criticalBroken;
    }

    public double getRawPenalty() {
        return rawPenalty;
    }

    // ────────────────────────── getters ──────────────────────────

    public List<String> getWarningsList() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getFallbackDetails() {
        return Collections.unmodifiableList(fallbackDetails);
    }

    public List<Map<String, String>> getJsErrors() {
        return Collections.unmodifiableList(jsErrors);
    }

    // ────────────────────────── reporting ──────────────────────────

    public void printReport() {
        System.out.println("\n========= HEALTH REPORT =========");
        System.out.printf("Raw Score    : %d (penalty %.1f, capped at %d)%n",
                getScore(), rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
        System.out.printf("Smoothed     : %d (EMA α=%.1f)%n",
                getSmoothedScore(), HealthPolicy.EMA_ALPHA);
        System.out.println("Status       : " + getStatus());
        System.out.printf("  Warnings   : %d (penalty %.1f, cap %.1f)%n",
                warnings.size(), warningPenalty, HealthPolicy.WARNING_CAP);
        System.out.printf("  JS Errors  : %d (penalty %.1f)%n",
                jsErrors.size(), jsErrorPenalty);
        System.out.printf("  Fallbacks  : %d (penalty %.1f)%n",
                fallbackDetails.size(), fallbackPenalty);
        System.out.printf("  Slow Pages : %d (penalty %.1f)%n",
                slowPages.size(), slowPagePenalty);
        System.out.printf("  Test Fails : %d (penalty %.1f)%n",
                testFailures.size(), testFailurePenalty);
        System.out.println("================================\n");
    }

    // ────────────────────────── failure tracking ──────────────────────────

    private final List<Map<String, String>> testFailures = new ArrayList<>();
    private final Map<String, Long> testDurations = new HashMap<>();

    /** Test failure — penalized based on flow type of the test context. */
    public void recordTestFailure(String testName, String reason, String stackTrace) {
        testFailures.add(Map.of(
                "test", testName,
                "reason", reason != null ? reason : "Unknown",
                "stack", stackTrace != null ? stackTrace : ""));

        double pts = HealthPolicy.testFailurePenalty(testName);
        testFailurePenalty += pts;
        rawPenalty += pts;
    }

    public void recordTestDuration(String testName, long durationMs) {
        testDurations.put(testName, durationMs);
    }

    public List<Map<String, String>> getTestFailures() {
        return Collections.unmodifiableList(testFailures);
    }

    // ────────────────────────── snapshot ──────────────────────────

    @SuppressWarnings("unchecked")
    public void writeSnapshot() {
        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", System.currentTimeMillis());
            root.put("score", getScore());
            root.put("smoothedScore", getSmoothedScore());
            root.put("status", getStatus());
            root.put("criticalBroken", criticalBroken);

            // Penalty breakdown
            root.put("rawPenalty", rawPenalty);
            root.put("penaltyPoints", (int) Math.round(Math.min(rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY)));
            root.put("testFailurePenalty", testFailurePenalty);
            root.put("jsErrorPenalty", jsErrorPenalty);
            root.put("fallbackPenalty", fallbackPenalty);
            root.put("slowPagePenalty", slowPagePenalty);
            root.put("warningPenalty", warningPenalty);

            // Counts
            root.put("warningCount", warnings.size());
            root.put("jsErrorCount", jsErrors.size());
            root.put("fallbackCount", fallbackDetails.size());
            root.put("slowPagesCount", slowPages.size());

            root.put("warnings", new JSONArray() {
                {
                    addAll(warnings);
                }
            });
            root.put("fallbacks", new JSONArray() {
                {
                    addAll(fallbackDetails);
                }
            });
            root.put("jsErrors", new JSONArray() {
                {
                    addAll(jsErrors);
                }
            });

            // Slow page details
            JSONArray slowArr = new JSONArray();
            for (Map<String, String> sp : slowPages) {
                JSONObject spObj = new JSONObject();
                spObj.putAll(sp);
                slowArr.add(spObj);
            }
            root.put("slowPages", slowArr);

            // Test failures
            JSONArray failures = new JSONArray();
            for (Map<String, String> f : testFailures) {
                JSONObject fo = new JSONObject();
                fo.putAll(f);
                failures.add(fo);
            }
            root.put("testFailures", failures);

            Path out = Paths.get("reports/trend/health_snapshot.json");
            Files.createDirectories(out.getParent());
            Files.write(out, root.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // reporting must never break tests
        }
    }

    // ────────────────────────── EMA helper ──────────────────────────

    /**
     * Read the previous smoothed score from the CSV history.
     * Returns -1 if no history.
     */
    private int readPreviousSmoothedScore() {
        try {
            Path csv = Paths.get("reports/trend/health_history.csv");
            if (!Files.exists(csv))
                return -1;
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            // Walk from the end to find the last non-empty data line
            for (int i = lines.size() - 1; i >= 1; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty())
                    continue;
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return Integer.parseInt(parts[1].trim());
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ────────────────────────── helpers ──────────────────────────

    private static String firstLine(String s) {
        if (s == null)
            return "";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
