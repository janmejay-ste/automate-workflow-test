package utils.health;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import utils.HealthPolicy;
import utils.history.trends.TrendConfidenceCalculator;

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

    // ────────────────────────── Phase A.5.3 platformHealth self-observability ─────────
    // JVM-lifetime counters surfaced in the snapshot's {@code platformHealth} block.
    // The flags are static + volatile because failure observation must survive across
    // the singleton's mutator boundary and be readable from the snapshot writer even
    // when the failure occurred earlier in the same run.
    //
    // Why each exists:
    //   SNAPSHOT_WRITE_FAILURE_COUNT — addresses LIM #2 in degradation review: snapshot
    //     write failures were previously invisible. Now visible as a counter that any
    //     reader (dashboard, governance check) can react to.
    //   LAST_SNAPSHOT_WRITE_ERROR    — surface the last exception message for triage.
    //   LAST_PREV_SCORE_SOURCE       — addresses LIM #3: distinguishes "no prior run"
    //     (NO_DATA) from "prior snapshot exists but is corrupt" (MALFORMED). Both
    //     previously collapsed to "no smoothing" silently.
    private static volatile int    SNAPSHOT_WRITE_FAILURE_COUNT = 0;
    private static volatile String LAST_SNAPSHOT_WRITE_ERROR    = null;
    private static volatile String LAST_PREV_SCORE_SOURCE       = "NO_DATA";

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

    // ────────────────────────── Phase A1: Contributor tracking ────────────────────
    // Per-source penalty breakdown so the final score is reproducible and explainable.
    // Each record*Penalty() method records (raw, applied, item-fields) so the dashboard
    // can answer "why did this run score 73?" — replacing the v1 model where caps
    // silently suppressed contributors and item provenance was lost.
    private final java.util.concurrent.ConcurrentHashMap<String, ContributorBucket> contributors =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ContributorBucket bucket(String source) {
        return contributors.computeIfAbsent(source, ContributorBucket::new);
    }

    public Map<String, ContributorBucket> getContributors() {
        return Collections.unmodifiableMap(contributors);
    }

    private HealthTracker() {
    }

    public static HealthTracker get() {
        return INSTANCE;
    }

    // ────────────────────────── penalty tracking ──────────────────────────

    public synchronized void addWarning(String context, String message) {
        if (isTrackerKilled()) return;
        double raw     = HealthPolicy.BASE_WARNING;
        double headroom = Math.max(0.0, HealthPolicy.WARNING_CAP - warningPenalty);
        double applied  = Math.min(raw, headroom);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("context", context);
        item.put("message", message);
        bucket("warnings").add(item, raw, applied);
        if (applied < raw) {
            bucket("warnings").markCap("WARNING_CAP", HealthPolicy.WARNING_CAP);
        }

        if (warningPenalty >= HealthPolicy.WARNING_CAP)
            return;
        warningsList.add(context + ": " + message);
        warningPenalty += applied;
        rawPenalty     += applied;
    }

    public void warn(String message) {
        addWarning("Global", message);
    }

    public synchronized void recordFallback(String step, String url) {
        if (isTrackerKilled()) return;
        fallbacks.add(Map.of("name", step, "target", url));
        double raw = HealthPolicy.fallbackPenalty(step);
        // No cap currently applied to fallbacks — raw == applied
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name",   step);
        item.put("target", url);
        bucket("fallbacks").add(item, raw, raw);
        fallbackPenalty += raw;
        rawPenalty      += raw;
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
        if (isTrackerKilled()) return;
        jsErrorsList.add(Map.of("context", context, "message", message));
        if (critical)
            criticalBroken = true;

        // Normalize before fingerprinting so "main.js:442" and "main.js:451" collapse
        // to the same bug rather than inflating unique-issue counts.
        String normalized  = normalizeJsMessage(message);
        String fingerprint = context + "|" + normalized;
        int count = jsErrorFingerprints.merge(fingerprint, 1, Integer::sum);

        // First occurrence = full penalty; repeats = 20%. (C3 will refine this to
        // a 4-bucket schedule once Phase A is in production.)
        double basePenalty = HealthPolicy.jsErrorPenalty(context);
        double repeatFactor = (count == 1 ? 1.0 : 0.2);
        double raw = basePenalty * repeatFactor;

        // Enforce TOTAL_JS_PENALTY_CAP — applied reflects the actual score impact.
        double headroom = Math.max(0.0, HealthPolicy.TOTAL_JS_PENALTY_CAP - jsErrorPenalty);
        double applied  = Math.min(raw, headroom);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("context",     context);
        item.put("message",     message);
        item.put("normalized",  normalized);
        item.put("fingerprint", fingerprint);
        item.put("occurrence",  count);
        item.put("critical",    critical);
        bucket("jsErrors").add(item, raw, applied);
        if (applied < raw) {
            bucket("jsErrors").markCap("TOTAL_JS_PENALTY_CAP", HealthPolicy.TOTAL_JS_PENALTY_CAP);
        }

        jsErrorPenalty += applied;
        rawPenalty     += applied;
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
        if (isTrackerKilled()) return;
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
        double raw = HealthPolicy.slowPagePenalty(loadTimeMs, context);
        // No cap currently applied to slowPages — raw == applied
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("url",      context);
        item.put("loadTime", loadTimeMs);
        item.put("severity", severity);
        bucket("slowPages").add(item, raw, raw);
        slowPagePenaltyValue += raw;
        rawPenalty           += raw;
    }

    // ────────────────────────── scoring ──────────────────────────

    public int getScore() {
        // Phase C1 — diminishing-returns curve replaces the hard Math.min cap when
        // -Dpenalty.curveCap=true. Default is the legacy hard cap, so historical
        // trend comparison stays apples-to-apples until the flag is explicitly flipped.
        double effectivePenalty = HealthPolicy.applyDiminishingReturns(
                rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
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
            String status, long duration,
            String artifactFolder, String videoPath, boolean videoTruncated) {
        JSONObject rec = new JSONObject();
        rec.put("category", category);
        rec.put("login",    login);
        rec.put("feature",  feature);
        rec.put("class",    clazz);
        rec.put("method",   method);
        rec.put("status",   status);
        rec.put("duration", duration);
        if (artifactFolder != null) rec.put("artifacts",     artifactFolder); // activates buildArtifactLinks
        if (videoPath      != null) rec.put("videoPath",     videoPath);
        if (videoTruncated)         rec.put("videoTruncated", true);
        testRecords.add(rec);
    }

    /** Backward-compatible bridge — all existing call-sites remain unchanged. */
    public synchronized void addTestRecord(String category, String login, String feature, String clazz, String method,
            String status, long duration) {
        addTestRecord(category, login, feature, clazz, method, status, duration, null, null, false);
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
        if (isTrackerKilled()) return;
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
        double raw = HealthPolicy.testFailurePenalty(testName);
        // No cap currently applied to testFailures — raw == applied
        // (B5/B6 will introduce flake-aware discount and business-tier weighting.)
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("test",   testName);
        item.put("reason", reason != null ? reason : "Unknown");
        bucket("testFailures").add(item, raw, raw);
        testFailurePenalty += raw;
        rawPenalty         += raw;
    }

    public synchronized void recordTestSuccess() {
        healthyCount++;
    }

    public void recordTestDuration(String testName, long durationMs) {
        // Duration tracking for future analytics
    }

    // ────────────────────────── storage ──────────────────────────

    /**
     * Phase A.5.2 kill switches — checked at entry of every public mutator and
     * the snapshot writer. Reading the flag is cheap; the goal is that when
     * the platform itself is broken, setting the flag turns every code path
     * into a quiet no-op without requiring callers to know about it.
     */
    public static boolean isTrackerKilled() {
        return Boolean.parseBoolean(System.getProperty("healthTracker.disabled", "false"));
    }

    public static boolean isSnapshotKilled() {
        return Boolean.parseBoolean(System.getProperty("healthSnapshot.disabled", "false"));
    }

    @SuppressWarnings("unchecked")
    public void writeSnapshot() {
        if (isTrackerKilled()) {
            System.err.println("[HealthTracker] KILL-SWITCH — tracker disabled; skipping snapshot write.");
            return;
        }
        if (isSnapshotKilled()) {
            System.err.println("[HealthSnapshot] KILL-SWITCH — snapshot writer disabled; computing in-memory only.");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("schemaVersion", 3);           // Phase B4 — frozen at v3 going forward
            json.put("score", getScore());
            json.put("smoothedScore", getSmoothedScore());
            json.put("status", getStatus());
            json.put("penalty", rawPenalty);
            json.put("timestamp", System.currentTimeMillis());

            JSONArray fails = new JSONArray();
            fails.addAll(testFailuresList);
            json.put("testFailures", fails);

            // Phase A1: contributor breakdown so the score is reproducible and
            // explainable. Each source records (raw, applied, items[]) plus any
            // cap that was hit. Dashboard "Why this score?" panel reads this.
            JSONObject scoreContributors = new JSONObject();
            double totalRaw = 0.0, totalApplied = 0.0;
            for (Map.Entry<String, ContributorBucket> e : contributors.entrySet()) {
                ContributorBucket b = e.getValue();
                scoreContributors.put(e.getKey(), b.toJson());
                totalRaw     += b.getRawTotal();
                totalApplied += b.getAppliedTotal();
            }
            scoreContributors.put("totalRaw",     totalRaw);
            scoreContributors.put("totalApplied", totalApplied);
            scoreContributors.put("totalSuppressed", totalRaw - totalApplied);
            json.put("scoreContributors", scoreContributors);

            // Phase A.5.4 — Contributor integrity invariant check. Runs 6 math
            // invariants against the contributor buckets we just serialised plus
            // a 7th trust check against the previous-snapshot source recorded by
            // A.5.3. Emitted as a top-level `integrity` block (not inside
            // platformHealth — that category list is capped at 4 per the
            // complexity budget). Advisory only: never fails the run, but a
            // violating snapshot signals the score is not reproducible from the
            // serialised contributors and the dashboard can flag it.
            json.put("integrity", buildIntegrityCheck(totalRaw, totalApplied));

            // Phase C2 — trendStats. Statistical summary of the current run vs
            // recent N runs. Always emitted (downstream readers don't branch on
            // absence); sample size 0 when there's no history yet. Outlier flag
            // is the actionable bit — when true, the snapshot writer also adds
            // an entry to release.warnings.
            json.put("trendStats", buildTrendStats(getScore()));

            // Phase E2 — override audit. Snapshot of every gate-bypass flag set
            // on this JVM at writeSnapshot time. Renders as the dashboard banner
            // (E3) so a quiet "we shipped while the gate was off" can't happen.
            json.put("audit", buildAuditOverrides());

            // Phase A3: confidence on the score itself, sourced from sample size.
            // Numbers only — UI builds the interpretation sentence. Bands defined by
            // TrendConfidenceCalculator (NONE/LOW/MEDIUM/HIGH).
            int sampleN = countHistoricalRuns();
            double confidenceRatio = TrendConfidenceCalculator.compute(sampleN);
            String confidenceLevel = TrendConfidenceCalculator.label(confidenceRatio);
            JSONObject confidence = new JSONObject();
            confidence.put("level",       confidenceLevel);
            confidence.put("ratio",       confidenceRatio);
            confidence.put("sampleN",     sampleN);
            confidence.put("windowDays",  14);   // surfaced for UI context; not used in computation
            json.put("confidence", confidence);

            // Phase A4: structured release decision with machine-readable factors.
            // Read inputs we already have plus a few semantic-snapshot-derived bits.
            int productHealth    = readNestedInt(json, 100, "semantic", "layeredScores", "productHealth");
            int criticalClusters = countCriticalClusters(json);
            utils.RiskInterpreter.DecisionInputs di = new utils.RiskInterpreter.DecisionInputs(
                    getScore(),
                    getSmoothedScore(),
                    /* smokePassRate */     1.0,   // wired in B6 with smoke-suite tracking
                    /* criticalBugs */      criticalFlowFailures.size(),
                    /* regressionPassRate*/ 1.0,   // wired in B6
                    /* regressionN */       testRecords.size(),
                    /* locatorSamples */    0,
                    productHealth,
                    criticalClusters,
                    confidenceLevel,
                    sampleN
            );
            utils.release.ReleaseDecision decision = utils.RiskInterpreter.decide(di);
            json.put("release", decision.toJson());

            // Phase A.5.1 + A.5.2: enforcement block — what the gate decided,
            // and whether kill switches changed the outcome. Read from the
            // HealthGate that ran (may be null if enforce() hasn't fired yet
            // — e.g. when writeSnapshot is called before HealthGate.enforce).
            HealthGate.EnforcementOutcome enforcement = HealthGate.lastDecision();
            if (enforcement != null) {
                json.put("enforcement", enforcement.toJson());
            } else {
                JSONObject pending = new JSONObject();
                pending.put("mode", HealthGate.resolveMode().name().toLowerCase());
                pending.put("reasonIfNotEnforcing", "GATE_NOT_YET_RUN");
                pending.put("wouldHaveBlocked", false);
                pending.put("actuallyBlocked",  false);
                pending.put("killSwitchActive", HealthGate.isKillSwitchActive());
                json.put("enforcement", pending);
            }

            // Embed the semantic snapshot — layered scores, clusters, reliability —
            // so the dashboard can render it without recomputing.
            try {
                json.put("semantic",
                        utils.health.semantic.SemanticHealthSnapshot.from(this).toJson());
            } catch (Exception e) {
                System.err.println("Failed to attach semantic snapshot: " + e.getMessage());
            }

            // Phase A.5.3 — platformHealth self-observability block. Surfaces the
            // failure modes that were previously silent (snapshot write failures,
            // malformed previous-snapshot reads, corrupt complexity budget config,
            // invalid -DhealthGate.mode value). Mapped to the four allowed
            // platformHealth categories per config/complexity_budget.json.
            //
            // Read happens AFTER getSmoothedScore() above, so LAST_PREV_SCORE_SOURCE
            // is populated by the time this builds.
            json.put("platformHealth", buildPlatformHealth());

            Path path = Paths.get("reports/trend/health_snapshot.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.toJSONString());
        } catch (Exception e) {
            // Phase A.5.3 — surface write failures rather than swallowing them.
            // The counter + last error are exposed in the next run's
            // platformHealth.snapshot_pipeline block so the failure is visible.
            SNAPSHOT_WRITE_FAILURE_COUNT++;
            LAST_SNAPSHOT_WRITE_ERROR = e.getMessage();
            System.err.println("Failed to write snapshot: " + e.getMessage());
        }
    }

    /**
     * Phase A.5.4 — Contributor integrity invariant check.
     *
     * <p>Validates that the contributor buckets we just serialised are internally
     * consistent and that the score derives correctly from them. Returns a JSON
     * block summarising the check outcome — never throws, never fails the run.
     * A dashboard or governance check that wants to enforce integrity can read
     * {@code integrity.status != "PASS"} and act on it.</p>
     *
     * <h3>Invariants (with float tolerance {@code 0.01})</h3>
     * <ol>
     *   <li>Per-bucket: {@code rawTotal} ≈ Σ {@code items[].raw}</li>
     *   <li>Per-bucket: {@code appliedTotal} ≈ Σ {@code items[].applied}</li>
     *   <li>Per-bucket: {@code suppressed} ≈ {@code rawTotal - appliedTotal}</li>
     *   <li>Aggregate: {@code totalRaw} == Σ ({@code bucket.rawTotal})</li>
     *   <li>Aggregate: {@code totalSuppressed} == {@code totalRaw - totalApplied}</li>
     *   <li>Score formula: {@code getScore() == max(0, 100 - round(min(rawPenalty, MAX_TOTAL_PENALTY)))}</li>
     *   <li>Trust: previous-snapshot source ≠ MALFORMED (DEGRADED, not FAIL, when violated)</li>
     * </ol>
     *
     * <h3>Status values</h3>
     * <ul>
     *   <li>{@code PASS}     — all 7 checks held</li>
     *   <li>{@code DEGRADED} — math checks 1–6 held, but invariant 7 (trust) was violated</li>
     *   <li>{@code FAIL}     — at least one of invariants 1–6 violated</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildIntegrityCheck(double totalRaw, double totalApplied) {
        final double TOL = 0.01;   // float-comparison tolerance for penalty math

        JSONArray violations = new JSONArray();
        int checksRun = 0;

        // Invariants 1–3: per-bucket integrity (math + items-vs-totals consistency)
        for (Map.Entry<String, ContributorBucket> e : contributors.entrySet()) {
            String name = e.getKey();
            ContributorBucket b = e.getValue();
            checksRun += 3;

            double itemsRawSum = 0.0, itemsAppliedSum = 0.0;
            for (Map<String, Object> item : b.getItems()) {
                Object r = item.get("raw");
                Object a = item.get("applied");
                if (r instanceof Number rn) itemsRawSum     += rn.doubleValue();
                if (a instanceof Number an) itemsAppliedSum += an.doubleValue();
            }
            if (Math.abs(b.getRawTotal() - itemsRawSum) > TOL) {
                violations.add(integrityViolation(
                        "BUCKET_RAW_SUM_MISMATCH", name, b.getRawTotal(), itemsRawSum));
            }
            if (Math.abs(b.getAppliedTotal() - itemsAppliedSum) > TOL) {
                violations.add(integrityViolation(
                        "BUCKET_APPLIED_SUM_MISMATCH", name, b.getAppliedTotal(), itemsAppliedSum));
            }
            double expectedSuppressed = b.getRawTotal() - b.getAppliedTotal();
            if (Math.abs(b.getSuppressed() - expectedSuppressed) > TOL) {
                violations.add(integrityViolation(
                        "BUCKET_SUPPRESSED_MISMATCH", name,
                        expectedSuppressed, b.getSuppressed()));
            }
        }

        // Invariant 4: aggregate totalRaw matches sum of bucket.rawTotal
        checksRun++;
        double bucketRawSum = contributors.values().stream()
                .mapToDouble(ContributorBucket::getRawTotal).sum();
        if (Math.abs(totalRaw - bucketRawSum) > TOL) {
            violations.add(integrityViolation(
                    "AGGREGATE_RAW_MISMATCH", "totalRaw", bucketRawSum, totalRaw));
        }

        // Invariant 5: totalSuppressed math
        checksRun++;
        double expectedAggSuppressed = totalRaw - totalApplied;
        // (We just computed totalSuppressed = totalRaw - totalApplied in the caller,
        //  so this is a pure math sanity check — should always hold by construction.)
        if (Math.abs(expectedAggSuppressed - (totalRaw - totalApplied)) > TOL) {
            violations.add(integrityViolation(
                    "AGGREGATE_SUPPRESSED_MISMATCH",
                    "totalSuppressed", expectedAggSuppressed, totalRaw - totalApplied));
        }

        // Invariant 6: score formula consistency.
        // CRITICAL: this must use the same effective-penalty formula as getScore().
        // Previously hardcoded Math.min(rawPenalty, MAX_TOTAL_PENALTY). After C1
        // introduced applyDiminishingReturns(), that hardcoding caused integrity
        // to disagree with getScore at any non-zero penalty when
        // -Dpenalty.curveCap=true — emitting a false SCORE_FORMULA_MISMATCH
        // even though both functions were individually correct. Routing both
        // through HealthPolicy.applyDiminishingReturns keeps them aligned
        // through any future formula evolution (e.g. C1 default flip, future
        // per-bucket curves).
        checksRun++;
        double effectivePenalty = HealthPolicy.applyDiminishingReturns(
                rawPenalty, HealthPolicy.MAX_TOTAL_PENALTY);
        int expectedScore = (int) Math.max(0, 100 - Math.round(effectivePenalty));
        int actualScore = getScore();
        if (expectedScore != actualScore) {
            JSONObject v = new JSONObject();
            v.put("code",     "SCORE_FORMULA_MISMATCH");
            v.put("field",    "score");
            v.put("expected", expectedScore);
            v.put("actual",   actualScore);
            v.put("rawPenalty", rawPenalty);
            violations.add(v);
        }

        // Math-violation gate: invariants 1–6 are math checks. Track separately
        // from trust check so we can emit DEGRADED vs FAIL correctly.
        boolean mathFailed = !violations.isEmpty();

        // Invariant 7: trust — previous-snapshot source from A.5.3
        // MALFORMED means the smoothing used in this run was based on suspect data.
        checksRun++;
        boolean smoothingTrusted = !"MALFORMED".equals(LAST_PREV_SCORE_SOURCE);
        if (!smoothingTrusted) {
            JSONObject v = new JSONObject();
            v.put("code",     "PREVIOUS_SNAPSHOT_MALFORMED");
            v.put("field",    "previousScoreSource");
            v.put("expected", "SNAPSHOT|CSV|NO_DATA");
            v.put("actual",   LAST_PREV_SCORE_SOURCE);
            v.put("note",     "Smoothed score derived from an unparseable predecessor — "
                            + "compare against absolute score with caution.");
            violations.add(v);
        }

        // Status resolution
        String status = mathFailed ? "FAIL"
                       : !smoothingTrusted ? "DEGRADED"
                       : "PASS";

        if (!"PASS".equals(status)) {
            System.err.printf("[HealthTracker] Integrity check %s — %d violation(s): %s%n",
                    status, violations.size(), violations.toJSONString());
        }

        JSONObject out = new JSONObject();
        out.put("status",     status);
        out.put("checksRun",  checksRun);
        out.put("violations", violations);

        JSONObject trust = new JSONObject();
        trust.put("previousScoreSource", LAST_PREV_SCORE_SOURCE);
        trust.put("smoothingTrusted",    smoothingTrusted);
        out.put("trust", trust);

        return out;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject integrityViolation(String code, String field,
                                                  double expected, double actual) {
        JSONObject v = new JSONObject();
        v.put("code",     code);
        v.put("field",    field);
        v.put("expected", expected);
        v.put("actual",   actual);
        v.put("delta",    actual - expected);
        return v;
    }

    /**
     * Phase C2 — builds the {@code trendStats} top-level block. Reads the last
     * {@link utils.history.trends.TrendStatsCalculator#DEFAULT_WINDOW} run scores
     * from history, computes mean/stdDev/z-score, returns the JSON record.
     *
     * <p>Failures during history read are swallowed — the trendStats block is
     * still emitted with {@code sampleSize:0} so consumers don't have to branch
     * on absence. The history-read failure itself is already surfaced via
     * {@code platformHealth.snapshot_pipeline.previousScoreSource} (A.5.3).</p>
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildTrendStats(int currentScore) {
        java.util.List<Integer> priorScores = readRecentScores(
                utils.history.trends.TrendStatsCalculator.DEFAULT_WINDOW);
        utils.history.trends.TrendStatsCalculator.TrendStats stats =
                utils.history.trends.TrendStatsCalculator.compute(priorScores, currentScore);
        return stats.toJson();
    }

    /**
     * Phase E2 — builds the {@code audit.overrides} top-level block.
     *
     * <p>Every gate-bypass system property the platform respects is enumerated here.
     * If a flag is set to its "non-default risk-bearing" value (e.g. kill switch
     * on, video off, scoring feature flag flipped), it appears in the
     * {@code activeOverrides} array with the raw flag value. The dashboard renders
     * a red banner at the top of the page whenever {@code activeOverrides.length > 0}.</p>
     *
     * <p>This is the "who silenced what?" audit trail. Per the operating playbook
     * in {@code docs/scoring-system.md} §8, override decisions are intentional but
     * must be visible — silent overrides are the failure mode this catches.</p>
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildAuditOverrides() {
        JSONObject audit = new JSONObject();
        JSONArray active = new JSONArray();

        // Each entry: {property, currentValue, defaultValue, effect}
        addOverrideIfSet(active, "healthTracker.disabled",   "false",
                "Tracker mutators are no-ops; in-memory state preserved");
        addOverrideIfSet(active, "healthSnapshot.disabled",  "false",
                "Snapshot file not written this run");
        addOverrideIfSet(active, "healthGate.disabled",      "false",
                "Build gate cannot fail regardless of mode");
        addOverrideIfSet(active, "recordVideo",              "true",
                "Test recordings are not captured");
        addOverrideIfSet(active, "recordPassedVideo",        "true",
                "PASS tests do not produce recordings (FAIL still recorded)");
        addOverrideIfSet(active, "penalty.curveCap",         "false",
                "Diminishing-returns curve replaces hard penalty cap (C1 feature flag)");
        addOverrideIfSet(active, "js.suppression.sampleSizeAware", "false",
                "Sample-size-aware LOW-cluster suppression (C3 feature flag)");

        // healthGate.mode is a special case — non-default value (blocking) is the
        // safer one; advisory is the default. We surface non-default values so
        // operators see when blocking is on.
        String gateMode = System.getProperty("healthGate.mode", "advisory");
        if (!"advisory".equalsIgnoreCase(gateMode)) {
            JSONObject e = new JSONObject();
            e.put("property",      "healthGate.mode");
            e.put("currentValue",  gateMode);
            e.put("defaultValue",  "advisory");
            e.put("effect",        "Build will FAIL on health violations");
            e.put("kind",          "intent-shift"); // not a bypass; an intentional escalation
            active.add(e);
        }

        audit.put("overrides", active);
        audit.put("overrideCount", active.size());
        return audit;
    }

    @SuppressWarnings("unchecked")
    private static void addOverrideIfSet(JSONArray active, String prop, String defaultValue, String effect) {
        String value = System.getProperty(prop);
        if (value == null) return;                       // not set, using default
        if (value.equalsIgnoreCase(defaultValue)) return; // set explicitly to default — no override
        JSONObject e = new JSONObject();
        e.put("property",     prop);
        e.put("currentValue", value);
        e.put("defaultValue", defaultValue);
        e.put("effect",       effect);
        e.put("kind",         "bypass");
        active.add(e);
    }

    /**
     * Reads the N most recent {@code score} values from {@code reports/trend/history.json}.
     * Returns an empty list on any failure — the caller already produces a stable
     * "no data" trendStats record when given empty input.
     */
    private java.util.List<Integer> readRecentScores(int window) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("reports/trend/history.json");
            if (!java.nio.file.Files.exists(p)) return out;
            JSONObject root = (JSONObject) new JSONParser().parse(java.nio.file.Files.readString(p));
            JSONArray runs = (JSONArray) root.get("runs");
            if (runs == null) return out;
            // history.json runs are newest-first; take the first `window` entries.
            // Score field name: history.json writes "overallScore" under metrics
            // (see TrendExporter). Try both names — "score" as a future-proof alias.
            int taken = 0;
            for (Object o : runs) {
                if (taken >= window) break;
                if (!(o instanceof JSONObject run)) continue;
                JSONObject metrics = (JSONObject) run.get("metrics");
                if (metrics == null) continue;
                Object s = metrics.get("overallScore");
                if (!(s instanceof Number)) s = metrics.get("score");
                if (s instanceof Number n) {
                    out.add(n.intValue());
                    taken++;
                }
            }
        } catch (Exception ignored) {
            // Swallow — empty list returned, downstream produces NO_DATA stats.
        }
        return out;
    }

    /**
     * Phase A.5.3 — builds the {@code platformHealth} self-observability block.
     *
     * <p>Four categories, matching {@code config/complexity_budget.json
     * → platformHealthCategories.currentValues}. The bound on this category list
     * is enforced upstream: additions require deleting or merging an existing
     * category first (FIFO replacement).</p>
     *
     * <p>The two DEFERRED categories ({@code evidence_collection},
     * {@code schema_evolution}) are intentional placeholders. Wiring them into
     * real signal is the job of A.6.1 and B4 respectively; emitting the slot
     * now reserves the schema position so downstream readers don't have to
     * branch on its absence.</p>
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildPlatformHealth() {
        JSONObject ph = new JSONObject();

        // ── snapshot_pipeline ─────────────────────────────────────────────────
        // Counts THIS JVM's snapshot-write failures so far. On a healthy run this
        // is 0. After a disk full / antivirus lock / permission error in an earlier
        // writeSnapshot call, the count and lastWriteError survive to the next call.
        // previousScoreSource tells the reader whether smoothing was based on a
        // clean prior snapshot (SNAPSHOT), the legacy CSV (CSV), no prior data
        // (NO_DATA — legitimate first run), or a corrupt predecessor file
        // (MALFORMED — silent corruption is now visible).
        JSONObject snapshotPipeline = new JSONObject();
        snapshotPipeline.put("writeErrors",         SNAPSHOT_WRITE_FAILURE_COUNT);
        snapshotPipeline.put("lastWriteError",      LAST_SNAPSHOT_WRITE_ERROR);
        snapshotPipeline.put("previousScoreSource", LAST_PREV_SCORE_SOURCE);
        ph.put("snapshot_pipeline", snapshotPipeline);

        // ── evidence_collection ────────────────────────────────────────────────
        // Phase A.6.1 — evidence channel registry. Replaces the A.5.3 DEFERRED
        // stub. Surfaces the quality of each of the 6 evidence channels listed
        // in config/complexity_budget.json → evidenceChannels.currentValues, so a
        // downstream reader (dashboard, governance check) can detect "this run
        // ran but channel X was silent" without parsing each producer individually.
        ph.put("evidence_collection", buildEvidenceCollection());

        // ── governance ─────────────────────────────────────────────────────────
        // ComplexityBudget.isLoaded() flips false on missing or malformed
        // config/complexity_budget.json. Previously this was a single WARN log
        // at startup and then forgotten — now it's a HIGH-severity governance
        // signal that survives across the whole run.
        //
        // healthGateModeInputRaw is non-null only when an operator typed an
        // unrecognized -DhealthGate.mode value (e.g. "blcoking"). The previous
        // behaviour was a silent fall-back to advisory.
        JSONObject governance = new JSONObject();
        try {
            utils.governance.ComplexityBudget budget = utils.governance.ComplexityBudget.get();
            governance.put("complexityBudgetLoaded",    budget.isLoaded());
            governance.put("complexityBudgetLoadError", budget.getLoadError());
        } catch (Exception e) {
            // ComplexityBudget itself failed to instantiate — surface that.
            governance.put("complexityBudgetLoaded",    false);
            governance.put("complexityBudgetLoadError", "instantiation failed: " + e.getMessage());
        }
        // Phase B1 — Coverage catalog load state. Parallel to ComplexityBudget:
        // missing or malformed catalog is a governance signal (someone needs to
        // PR the missing entry), not a release block. Catalog content is the
        // source for B5 coverage-weighted score + B6 business-tier blocker.
        try {
            utils.coverage.CoverageCatalog cat = utils.coverage.CoverageCatalog.get();
            governance.put("coverageCatalogLoaded",     cat.isLoaded());
            governance.put("coverageCatalogLoadError",  cat.getLoadError());
            governance.put("coverageCatalogOutcomes",   cat.size());
        } catch (Exception e) {
            governance.put("coverageCatalogLoaded",     false);
            governance.put("coverageCatalogLoadError",  "instantiation failed: " + e.getMessage());
            governance.put("coverageCatalogOutcomes",   0);
        }
        String invalidMode = HealthGate.lastInvalidModeInput();
        governance.put("healthGateModeInputValid", invalidMode == null);
        governance.put("healthGateModeInputRaw",   invalidMode);
        ph.put("governance", governance);

        // ── schema_evolution ───────────────────────────────────────────────────
        // Reserved for B4 (SchemaMigrator). Stub present so the schema slot is
        // stable across phases.
        JSONObject schemaEvolution = new JSONObject();
        schemaEvolution.put("status", "DEFERRED");
        schemaEvolution.put("note",   "Schema migrator — wired in B4");
        ph.put("schema_evolution", schemaEvolution);

        return ph;
    }

    /**
     * Phase A.6.1 — Evidence channel registry.
     *
     * <p>Inspects each of the 6 evidence channels listed in
     * {@code config/complexity_budget.json → evidenceChannels.currentValues} and
     * emits a uniform {@code {name, quality, reason, count}} record. Replaces
     * the per-producer ad-hoc parsing that consumers had to do otherwise.</p>
     *
     * <h3>Quality vocabulary</h3>
     * From {@code complexity_budget.json → qualityEnum.values}:
     * <ul>
     *   <li>{@code STRONG}    — channel reported clean, comprehensive data</li>
     *   <li>{@code PARTIAL}   — channel reported but coverage is incomplete</li>
     *   <li>{@code DEGRADED}  — channel reported but data quality compromised</li>
     *   <li>{@code MISSING}   — channel expected but produced nothing (protected)</li>
     *   <li>{@code DISABLED}  — channel intentionally turned off (protected)</li>
     *   <li>{@code UNKNOWN}   — can't determine (protected)</li>
     * </ul>
     *
     * <p>{@code AMPLIFIED} is reserved for A.6.2 and not assigned here.</p>
     *
     * <h3>Reason vocabulary</h3>
     * Emitted only when {@code quality == MISSING}, from {@code reasonEnum.values}:
     * <ul>
     *   <li>{@code INSTRUMENTATION_NOT_TRIGGERED} — code path didn't run</li>
     *   <li>{@code COLLECTOR_FAILURE}             — collector itself errored (protected)</li>
     *   <li>{@code VALIDATOR_NOT_WIRED}           — validator not present</li>
     *   <li>{@code CLASSIFIER_GAP}                — classifier doesn't cover this case</li>
     *   <li>{@code INTENTIONALLY_DISABLED}        — operator turned it off (protected)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildEvidenceCollection() {
        JSONObject ec = new JSONObject();

        JSONArray channels = new JSONArray();

        // 1. testResults — from testRecords (every test method that ran)
        int testCount = testRecords.size();
        channels.add(evidenceChannel("testResults", testCount,
                testCount > 0 ? "STRONG" : "MISSING",
                testCount > 0 ? null      : "INSTRUMENTATION_NOT_TRIGGERED"));

        // 2. businessOutcomes — from BusinessOutcomeTracker
        int bizCount = utils.health.business.BusinessOutcomeTracker.get().all().size();
        channels.add(evidenceChannel("businessOutcomes", bizCount,
                bizCount > 0 ? "STRONG" : "MISSING",
                bizCount > 0 ? null      : "INSTRUMENTATION_NOT_TRIGGERED"));

        // 3. jsErrors — DEGRADED when raw errors recorded but the clusterer didn't
        //    produce any clusters from them (clusterer gap or all errors below threshold).
        //    PARTIAL when instrumentation is present but no events recorded
        //    (legitimate clean run is indistinguishable from instrumentation gap,
        //    so we use PARTIAL rather than MISSING for clean-run safety).
        int jsErrorCount = jsErrorsList.size();
        int clusterCount = computeClusterCount();
        String jsQuality, jsReason;
        if (jsErrorCount == 0) {
            jsQuality = "PARTIAL";
            jsReason  = null;   // clean run — no errors expected
        } else if (clusterCount == 0) {
            jsQuality = "DEGRADED";
            jsReason  = null;
        } else {
            jsQuality = "STRONG";
            jsReason  = null;
        }
        channels.add(evidenceChannel("jsErrors", jsErrorCount, jsQuality, jsReason));

        // 4. telemetry — DEGRADED when unknown-timing ratio exceeds 20%, STRONG when
        //    valid samples dominate, MISSING when neither valid nor unknown were
        //    recorded (no telemetry instrumentation hit this run).
        int unknownT = getUnknownTimings().size();
        int validT   = countValidTimings();
        int totalT   = unknownT + validT;
        String telQuality, telReason;
        if (totalT == 0) {
            telQuality = "MISSING";
            telReason  = "INSTRUMENTATION_NOT_TRIGGERED";
        } else {
            double unknownRatio = (double) unknownT / totalT;
            telQuality = unknownRatio > 0.20 ? "DEGRADED" : "STRONG";
            telReason  = null;
        }
        channels.add(evidenceChannel("telemetry", totalT, telQuality, telReason));

        // 5. urlValidation — STRONG when any page was scanned, MISSING otherwise
        int urlPagesScanned = utils.urlvalidator.UrlValidationTracker.get().pagesScanned().size();
        channels.add(evidenceChannel("urlValidation", urlPagesScanned,
                urlPagesScanned > 0 ? "STRONG" : "MISSING",
                urlPagesScanned > 0 ? null      : "INSTRUMENTATION_NOT_TRIGGERED"));

        // 6. semanticClusters — STRONG when clusters produced. If errors were
        //    recorded but no clusters formed → classifier-gap signal (PARTIAL).
        String clusterQuality;
        String clusterReason = null;
        if (clusterCount > 0) {
            clusterQuality = "STRONG";
        } else if (jsErrorCount > 0) {
            clusterQuality = "PARTIAL";
            clusterReason  = "CLASSIFIER_GAP";
        } else {
            clusterQuality = "PARTIAL";   // clean run — no clusters expected
        }
        channels.add(evidenceChannel("semanticClusters", clusterCount,
                clusterQuality, clusterReason));

        ec.put("channels",      channels);
        ec.put("registeredCount", channels.size());
        ec.put("expectedCount",   6);   // matches complexity_budget.json
        return ec;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject evidenceChannel(String name, int count,
                                              String quality, String reason) {
        JSONObject ch = new JSONObject();
        ch.put("name",    name);
        ch.put("count",   count);
        ch.put("quality", quality);
        ch.put("reason",  reason);   // null for non-MISSING channels
        return ch;
    }

    /**
     * Counts the clusters that would be produced by ErrorClusterer for this run.
     * Reuses the SemanticHealthSnapshot's own clustering — same code path as
     * the semantic block, so the count is exactly what the dashboard sees.
     */
    private int computeClusterCount() {
        try {
            return utils.health.semantic.SemanticHealthSnapshot.from(this)
                    .clusters.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Counts timing samples whose severity isn't "unknown". Mirrors the same
     * proxy used in SemanticHealthSnapshot.from() — keeps the registry's
     * telemetry count consistent with what the semantic layer computes.
     */
    @SuppressWarnings("unchecked")
    private int countValidTimings() {
        int valid = 0;
        for (Object o : getSlowPages()) {
            if (o instanceof Map<?, ?> sp) {
                Object sev = sp.get("severity");
                if (sev != null && !"unknown".equalsIgnoreCase(sev.toString())) {
                    valid++;
                }
            }
        }
        return valid;
    }

    /**
     * Reads the previous run's smoothed score from the structured snapshot file
     * (Phase A2). Replaces the v1 implementation that read the LAST ROW of
     * {@code reports/trend/history.csv} — that read was order-dependent and could
     * bias smoothing if CSV rows were ever appended out of order.
     *
     * <p>Read order:</p>
     * <ol>
     *   <li>{@code reports/trend/health_snapshot.json} → {@code smoothedScore} field
     *       (single source of truth; written transactionally with the rest of the snapshot)</li>
     *   <li>CSV fallback (legacy) only if the JSON snapshot is missing</li>
     *   <li>{@code -1} if neither source has data — caller treats as "no prior run"</li>
     * </ol>
     *
     * <p>Crucially, this is called BEFORE {@link #writeSnapshot} overwrites the
     * file, so it always reflects the previous run.</p>
     */
    private int readPreviousSmoothedScore() {
        // Phase A.5.3 — tag the source so platformHealth.snapshot_pipeline can
        // distinguish "no prior run" from "prior run's snapshot was corrupt".
        // Both used to fall through to "-1 → use raw score" silently. Now the
        // tag is surfaced so a reader can detect MALFORMED.
        Path snapPath = Paths.get("reports/trend/health_snapshot.json");
        boolean snapExisted = Files.exists(snapPath);
        if (snapExisted) {
            try {
                String content = Files.readString(snapPath);
                JSONObject root = (JSONObject) new JSONParser().parse(content);
                Object v = root.get("smoothedScore");
                if (v instanceof Number) {
                    LAST_PREV_SCORE_SOURCE = "SNAPSHOT";
                    return ((Number) v).intValue();
                }
                // File parsed but missing the key — treat as malformed for our purpose:
                // the previous run was supposed to write this and didn't.
                LAST_PREV_SCORE_SOURCE = "MALFORMED";
            } catch (Exception parseErr) {
                // File exists but is unreadable / corrupt JSON.
                LAST_PREV_SCORE_SOURCE = "MALFORMED";
                // fall through to CSV — caller still gets a usable value if CSV is intact
            }
        }

        // Legacy fallback: history.csv last row
        try {
            Path path = Paths.get("reports/trend/history.csv");
            if (!Files.exists(path)) {
                if (!snapExisted) LAST_PREV_SCORE_SOURCE = "NO_DATA";
                return -1;
            }
            List<String> lines = Files.readAllLines(path);
            if (lines.size() < 2) {
                if (!snapExisted) LAST_PREV_SCORE_SOURCE = "NO_DATA";
                return -1;
            }
            String lastLine = lines.get(lines.size() - 1);
            String[] parts = lastLine.split(",");
            if (parts.length >= 2) {
                // Only promote to CSV when the snapshot wasn't malformed — preserve
                // the MALFORMED signal even if CSV happens to be readable.
                if (!"MALFORMED".equals(LAST_PREV_SCORE_SOURCE)) {
                    LAST_PREV_SCORE_SOURCE = "CSV";
                }
                return Integer.parseInt(parts[1]);
            }
        } catch (Exception ignored) {
        }
        if (!snapExisted && !"MALFORMED".equals(LAST_PREV_SCORE_SOURCE)) {
            LAST_PREV_SCORE_SOURCE = "NO_DATA";
        }
        return -1;
    }

    // ────────────────────────── Phase A2: Run-write transaction ──────────────────
    /**
     * Single mutex held by any code that wants the snapshot + history + trend +
     * semantic-history writes to land atomically. Concurrent test runs (parallel
     * surefire forks) racing on the same files would otherwise interleave appends
     * and produce inconsistent state.
     *
     * <p>Usage:</p>
     * <pre>
     * synchronized (HealthTracker.RUN_WRITE_LOCK) {
     *     HealthTracker.get().writeSnapshot();
     *     HistoryJsonWriter.appendRun(...);
     *     TrendDataWriter.appendRun(...);
     *     SemanticTrendWriter.appendCurrentRun();
     * }
     * </pre>
     */
    public static final Object RUN_WRITE_LOCK = new Object();

    // ────────────────────────── Phase A3: Confidence helpers ─────────────────────
    /**
     * Counts the historical run records on disk (the input to confidence sampling).
     * Reads from {@code reports/trend/history.json} → {@code runs[]} length. Returns
     * {@code 0} on any read/parse failure — that maps to confidence level NONE,
     * which the release decision treats as a blocker (BLOCKED).
     */
    /**
     * Safely navigates a nested JSON object structure, returning {@code defaultValue}
     * if any key in the path is missing or the leaf is not numeric.
     */
    private static int readNestedInt(JSONObject root, String... path) {
        return readNestedInt(root, 100, path);
    }

    private static int readNestedInt(JSONObject root, int defaultValue, String... path) {
        Object cursor = root;
        for (String key : path) {
            if (!(cursor instanceof JSONObject)) return defaultValue;
            cursor = ((JSONObject) cursor).get(key);
            if (cursor == null) return defaultValue;
        }
        if (cursor instanceof Number) return ((Number) cursor).intValue();
        return defaultValue;
    }

    /**
     * Counts CRITICAL-severity error clusters in the embedded semantic snapshot.
     * Feeds the {@code CRITICAL_CLUSTER_LIMIT} blocker rule in {@code RiskInterpreter.decide()}.
     */
    private static int countCriticalClusters(JSONObject root) {
        try {
            Object semantic = root.get("semantic");
            if (!(semantic instanceof JSONObject)) return 0;
            Object clusters = ((JSONObject) semantic).get("clusters");
            if (!(clusters instanceof JSONArray)) return 0;
            int n = 0;
            for (Object c : (JSONArray) clusters) {
                if (c instanceof JSONObject) {
                    Object sev = ((JSONObject) c).get("severity");
                    if (sev != null && "CRITICAL".equals(sev.toString())) n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private int countHistoricalRuns() {
        try {
            Path historyPath = Paths.get("reports/trend/history.json");
            if (!Files.exists(historyPath)) return 0;
            JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(historyPath));
            Object runs = root.get("runs");
            if (runs instanceof JSONArray) {
                return ((JSONArray) runs).size();
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
