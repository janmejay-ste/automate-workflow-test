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

    // Changed to Map<String, Object> so structured detail (e.g. urlFindings) can be attached.
    private final List<Map<String, Object>> testFailuresList = new ArrayList<>();

    /**
     * One-shot queue: a test that knows it is about to call Assert.fail() with
     * URL-validation failures can call {@link #queueFailureDetail(String, List)}
     * BEFORE the assertion.  The TestNG {@code afterMethod} listener then calls
     * {@link #recordTestFailure}, which drains this queue and attaches the data
     * to the newly created failure record.
     *
     * <p>This sidesteps the ordering problem: the structured data must be
     * captured in the test body (where the local variables are in scope) but the
     * failure record is created in the listener (after the test threw).</p>
     */
    private volatile String                            pendingDetailKey      = null;
    private volatile List<Map<String, Object>>         pendingDetailValue    = null;

    private final JSONArray testRecords = new JSONArray();

    // Telemetry-integrity bucket: timings that came back 0 ms or negative.
    // These represent a broken stopwatch, missing metric, or page-never-loaded —
    // they are NOT slow pages and must not contribute to the slow-page count or
    // the health-score penalty. Surfaced as severity=unknown for diagnostics.
    private final List<Map<String, String>> unknownTimingList = new ArrayList<>();

    // Granular success counters — replaces the "everything not failed = failed" model.
    // Each successful editor load, auth recovery, etc. is recorded so the dashboard can
    // compute realistic pass-rates per phase instead of relying on TestNG's binary outcome.
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> successCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Fingerprint → occurrence count. First occurrence gets full penalty;
    // repeats get 20% to prevent one flaky JS bug destroying the score.
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> jsErrorFingerprints =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Critical flows that bypass health scoring and trigger mandatory build failure.
    // Examples: auth broken, workflow creation broken, checkout broken.
    // A score of 75/100 is irrelevant when the primary user journey is broken.
    private final java.util.LinkedHashSet<String> criticalFlowFailures = new java.util.LinkedHashSet<>();

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

    /**
     * Records a critical business flow failure that mandates build failure regardless
     * of the overall health score.
     *
     * Use for: auth broken, core workflow creation broken, checkout broken.
     * These failures mean the product is fundamentally unusable — no health score
     * calculation can make a broken login page acceptable.
     */
    public synchronized void recordCriticalFlowFailure(String flowName) {
        criticalFlowFailures.add(flowName);
        criticalBroken = true;
    }

    public java.util.Set<String> getCriticalFlowFailures() {
        return Collections.unmodifiableSet(criticalFlowFailures);
    }

    public boolean hasCriticalFlowFailures() {
        return !criticalFlowFailures.isEmpty();
    }

    public synchronized void recordJsError(String context, String message, boolean critical) {
        jsErrorsList.add(Map.of("context", context, "message", message));
        if (critical)
            criticalBroken = true;

        // Normalize before fingerprinting so "main.js:442" and "main.js:451" collapse
        // to the same bug rather than inflating unique-issue counts. Strips:
        //   - line:col coordinates (\d+:\d+)
        //   - webpack chunk hashes (.[a-f0-9]{8,})
        //   - dynamic numeric IDs in property paths (obj.prop.123 → obj.prop.N)
        String normalized = normalizeJsMessage(message);
        String fingerprint = context + "|" + normalized;
        int count = jsErrorFingerprints.merge(fingerprint, 1, Integer::sum);

        // First occurrence = full penalty; repeats = 20%.
        // One root bug repeated across 6 iterations should not score as 6 independent failures.
        double pts = HealthPolicy.jsErrorPenalty(context) * (count == 1 ? 1.0 : 0.2);

        // Enforce TOTAL_JS_PENALTY_CAP — once total JS-error penalty reaches the cap,
        // further errors are recorded for diagnostics but stop draining the score.
        // Visiting many marketing pages must not nuke the score to 0 over third-party noise
        // when the automation flow itself succeeded.
        double headroom = Math.max(0.0, HealthPolicy.TOTAL_JS_PENALTY_CAP - jsErrorPenalty);
        double applied  = Math.min(pts, headroom);
        jsErrorPenalty += applied;
        rawPenalty += applied;
    }

    private static String normalizeJsMessage(String message) {
        if (message == null) return "";
        return message
                // Strip line:col coordinates — "main.js:442:18" → "main.js"
                .replaceAll(":\\d+:\\d+", "")
                // Strip lone column refs appended by some runtimes — ":442"
                .replaceAll(":\\d+(?=[^\\d]|$)", "")
                // Collapse webpack chunk hashes — ".a3f92b1c" → ".<hash>"
                .replaceAll("\\.[a-fA-F0-9]{8,}", ".<hash>")
                // Collapse dynamic numeric property segments — "items.0.id" → "items.N.id"
                .replaceAll("(?<=\\.)\\d+(?=\\.|$)", "N")
                // Collapse whitespace runs
                .replaceAll("\\s+", " ")
                .trim();
    }

    public synchronized void recordSlowPage(String context, long loadTimeMs) {
        // Telemetry-integrity rule: 0 ms or negative means the stopwatch never ran
        // (metric missing, page never loaded, navigation API hook broken). That is
        // not "fast" or "slow" — it is UNKNOWN. Record it for diagnostic visibility
        // but emit zero penalty and tag severity=unknown so the dashboard does not
        // paint it red. Missing data ≠ catastrophic performance.
        if (loadTimeMs <= 0) {
            unknownTimingList.add(Map.of(
                    "url", context,
                    "loadTime", String.valueOf(loadTimeMs),
                    "severity", "unknown",
                    "reason", "Timing metric missing or stopwatch never started"));
            return;
        }

        // Severity bands match the documented penalty tiers in HealthPolicy.slowPagePenalty:
        //   >= 20 000 ms → critical
        //   >= 10 000 ms → high
        //   >=  5 000 ms → medium
        //   <   5 000 ms → low (acceptable, no penalty)
        String severity;
        if (loadTimeMs >= 20000)      severity = "critical";
        else if (loadTimeMs >= 10000) severity = "high";
        else if (loadTimeMs >= 5000)  severity = "medium";
        else                          severity = "low";

        slowPagesList.add(Map.of(
                "url", context,
                "loadTime", String.valueOf(loadTimeMs),
                "severity", severity));
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

    public JSONArray getUnknownTimings() {
        JSONArray arr = new JSONArray();
        arr.addAll(unknownTimingList);
        return arr;
    }

    /**
     * Records a granular success for a specific phase (e.g. "EditorLoad", "AuthRecovery",
     * "GraphRender", "UrlTransition"). Multiple successes per run are aggregated by phase
     * so the dashboard can report pass-rates instead of treating only TestNG's binary
     * outcome as the source of truth. Replaces the "anything not explicitly passed = failed"
     * model that destroys observability.
     */
    public void recordSuccess(String phase) {
        if (phase == null || phase.isBlank()) return;
        successCounters.merge(phase, 1, Integer::sum);
        healthyCount++;
    }

    public Map<String, Integer> getSuccessCounters() {
        return Collections.unmodifiableMap(successCounters);
    }

    // ────────────────────────── reporting ──────────────────────────

    public void printReport() {
        System.out.println("\n========= HEALTH REPORT =========");
        System.out.printf("Raw Score    : %d (penalty %.1f, capped at %d)%n",
                getScore(), rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
        System.out.println("Status       : " + getStatus());
        System.out.printf("  JS Errors      : %d (penalty %.1f, cap %.1f)%n",
                jsErrorsList.size(), jsErrorPenalty, HealthPolicy.TOTAL_JS_PENALTY_CAP);
        System.out.printf("  Fallbacks      : %d (penalty %.1f)%n",
                fallbacks.size(), fallbackPenalty);
        System.out.printf("  Slow Pages     : %d (penalty %.1f)%n",
                slowPagesList.size(), slowPagePenaltyValue);
        System.out.printf("  Test Fails     : %d (penalty %.1f)%n",
                testFailuresList.size(), testFailurePenalty);
        System.out.printf("  Unknown Timing : %d (telemetry gap, no penalty)%n",
                unknownTimingList.size());
        if (!successCounters.isEmpty()) {
            System.out.println("  Successes      : " + successCounters);
        }
        System.out.println();

        // Semantic layer — layered scores, clustered errors, per-phase reliability.
        // The single raw score above is kept for backward compatibility; everything
        // operationally meaningful is rendered below.
        StringBuilder semanticBlock = new StringBuilder();
        try {
            utils.health.semantic.SemanticHealthSnapshot.from(this).appendTo(semanticBlock);
        } catch (Exception e) {
            semanticBlock.append("(semantic snapshot unavailable: ").append(e.getMessage()).append(")\n");
        }
        System.out.print(semanticBlock);

        System.out.println("================================\n");
    }

    // ────────────────────────── failure tracking ──────────────────────────

    /**
     * Queue a structured detail list to be attached to the <em>next</em>
     * {@link #recordTestFailure} call.  Call this in the test body, before
     * {@code Assert.fail()}, so the data is available when the TestNG listener
     * records the failure record moments later.
     *
     * @param key    the JSON key under which the list will appear (e.g. {@code "urlFindings"})
     * @param detail list of maps — each map is one row in the detail table
     */
    public synchronized void queueFailureDetail(String key, List<Map<String, Object>> detail) {
        this.pendingDetailKey   = key;
        this.pendingDetailValue = detail;
    }

    public synchronized void recordTestFailure(String testName, String reason, String stackTrace) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("test",       testName);
        record.put("reason",     reason != null ? reason : "Unknown");
        record.put("stackTrace", stackTrace != null ? stackTrace : "");

        // Drain the pre-queued structured detail (if any) into this failure record.
        if (pendingDetailKey != null && pendingDetailValue != null) {
            record.put(pendingDetailKey, pendingDetailValue);
            pendingDetailKey   = null;
            pendingDetailValue = null;
        }

        testFailuresList.add(record);
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

            // Embed the semantic snapshot — layered scores, clusters, reliability —
            // so the dashboard can render it without recomputing.
            try {
                json.put("semantic",
                        utils.health.semantic.SemanticHealthSnapshot.from(this).toJson());
            } catch (Exception e) {
                System.err.println("Failed to attach semantic snapshot: " + e.getMessage());
            }

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
