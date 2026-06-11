package utils.health.cluster.replay;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import utils.health.cluster.Cluster;
import utils.health.cluster.ClusterKey;
import utils.health.cluster.ClusterPenaltyPolicy;
import utils.health.cluster.ClusterScoringEngine;
import utils.health.cluster.ContributorSource;
import utils.health.cluster.FingerprintExtractor;
import utils.health.cluster.ScoringResult;
import utils.health.schema.MigrationResult;
import utils.health.schema.SchemaMigrationService;

/**
 * Replays one or more {@code health_snapshot.json} payloads through both
 * candidate cluster-penalty policies and produces structured comparison
 * data. The output is meant to be the input to a human decision, NOT a
 * pre-baked recommendation.
 *
 * <p><b>Reads:</b> the {@code scoreContributors} section of a snapshot,
 * specifically each bucket's {@code items} array. Each item is treated as
 * a single raw event; events are then fingerprinted and clustered using
 * the same {@link FingerprintExtractor} family that production will use.</p>
 *
 * <p><b>Does NOT modify state.</b> Pure function from snapshot JSON to
 * {@link ReplayRunResult}. No file writes, no side effects, no production
 * scoring calls.</p>
 */
public final class ReplayHarness {

    /**
     * Default per-source caps. Match HealthPolicy's current production
     * values so replay results compare against the actual deployed scoring,
     * not a fictional baseline.
     */
    public static final Map<ContributorSource, Double> DEFAULT_CAPS = Map.of(
            ContributorSource.FALLBACKS,    30.0,
            ContributorSource.JS_ERRORS,    20.0,
            ContributorSource.TEST_FAILURES, 60.0
    );

    private ReplayHarness() {}

    /**
     * Replay a single snapshot.
     *
     * @param runId        identifier for reporting (often the filename or run timestamp)
     * @param snapshotJson the raw JSON content of one health_snapshot.json
     * @param caps         per-source caps (use {@link #DEFAULT_CAPS} for production-matching)
     * @return a fully-populated result, or null if the snapshot is unparseable
     */
    public static ReplayRunResult replay(String runId, String snapshotJson,
                                         Map<ContributorSource, Double> caps) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;

        // B4 Phase 3 integration: route every snapshot through the schema
        // migrator FIRST. This converts what used to be a silent
        // "scoreContributors not present → empty result" failure mode into
        // an explicit decision tree:
        //   - V3 native → unchanged behavior (identity migrator)
        //   - V2 lossy → replay against migrated shape, lossy=true flag
        //   - V1 / unknown → return null (same as the pre-B4 silent skip)
        MigrationResult migration = SchemaMigrationService.migrateToCurrent(snapshotJson);
        if (!migration.outcome().ok()) {
            // UNSUPPORTED or UNKNOWN_SCHEMA — nothing to replay. Return
            // null to match the existing "unparseable snapshot" contract
            // that replayBatch already handles via filter.
            return null;
        }
        return replayParsed(runId, migration.migrated(), caps,
                migration.lossy(), migration.reasons());
    }

    @SuppressWarnings("unchecked")
    public static ReplayRunResult replayParsed(String runId, JSONObject root,
                                                Map<ContributorSource, Double> caps) {
        // Backward-compat: callers that pre-parse and skip migration
        // still get a non-lossy run.
        return replayParsed(runId, root, caps, false, List.of());
    }

    /**
     * Internal entry point used by {@link #replay} after migration.
     * Carries lossy provenance into the {@link ReplayRunResult}.
     */
    @SuppressWarnings("unchecked")
    public static ReplayRunResult replayParsed(String runId, JSONObject root,
                                                Map<ContributorSource, Double> caps,
                                                boolean lossy,
                                                List<String> migrationReasons) {
        if (caps == null) caps = DEFAULT_CAPS;
        if (root == null) return null;

        long timestamp = (root.get("timestamp") instanceof Number n) ? n.longValue() : 0L;
        int oldScore = (root.get("score") instanceof Number sn) ? sn.intValue() : 100;
        double oldPenalty = (root.get("penalty") instanceof Number pn) ? pn.doubleValue() : 0.0;

        JSONObject sc = (JSONObject) root.getOrDefault("scoreContributors", new JSONObject());
        Map<ContributorSource, Double> oldPerSource = extractOldPerSource(sc);

        // Build clusters by source via re-fingerprinting the items array.
        // Lossy migrations have empty items[] so this returns an empty list —
        // which is the honest "no signal" result, not silent breakage.
        List<Cluster> clusters = clusterFromSnapshot(sc);

        ScoringResult a = ClusterScoringEngine.score(clusters,
                new ClusterPenaltyPolicy.PreserveAmplification(), caps);
        ScoringResult b = ClusterScoringEngine.score(clusters,
                new ClusterPenaltyPolicy.FlatCluster(), caps);

        // Largest-delta-source: where does Policy A diverge most from old?
        ContributorSource largestSrc = null;
        double largestAbsDelta = -1;
        for (ContributorSource s : ContributorSource.values()) {
            double oldVal = oldPerSource.getOrDefault(s, 0.0);
            double newVal = a.perSourceApplied().getOrDefault(s, 0.0);
            double abs = Math.abs(newVal - oldVal);
            if (abs > largestAbsDelta) {
                largestAbsDelta = abs;
                largestSrc = s;
            }
        }
        double largestAmplification = (largestSrc == null)
                ? 0.0
                : a.amplificationRatio(largestSrc);

        return new ReplayRunResult(
                runId, timestamp, oldScore, oldPenalty,
                oldPerSource, a, b,
                largestSrc, largestAmplification,
                lossy, migrationReasons);
    }

    /**
     * Replay a list of (runId, snapshotJson) pairs. Snapshots that fail to
     * parse are skipped with their entry omitted from the result list — the
     * caller can log the discrepancy if it matters for their report.
     */
    public static List<ReplayRunResult> replayBatch(List<Map.Entry<String, String>> snapshots,
                                                    Map<ContributorSource, Double> caps) {
        List<ReplayRunResult> out = new ArrayList<>();
        if (snapshots == null) return out;
        for (Map.Entry<String, String> e : snapshots) {
            ReplayRunResult r = replay(e.getKey(), e.getValue(), caps);
            if (r != null) out.add(r);
        }
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<ContributorSource, Double> extractOldPerSource(JSONObject sc) {
        Map<ContributorSource, Double> out = new EnumMap<>(ContributorSource.class);
        if (sc == null) return out;
        for (Map.Entry<ContributorSource, String> entry : SOURCE_KEY_MAP.entrySet()) {
            JSONObject bucket = (JSONObject) sc.get(entry.getValue());
            if (bucket == null) continue;
            Object applied = bucket.get("appliedTotal");
            if (applied instanceof Number n) out.put(entry.getKey(), n.doubleValue());
        }
        return out;
    }

    /**
     * Build the list of clusters from the snapshot's contributor buckets.
     * Each bucket's items array is re-fingerprinted and grouped — the same
     * items that today's flat scoring treated as N independent penalties
     * become M clusters, where M ≤ N (often M ≪ N for amplified runs).
     */
    @SuppressWarnings("unchecked")
    private static List<Cluster> clusterFromSnapshot(JSONObject sc) {
        List<Cluster> all = new ArrayList<>();
        if (sc == null) return all;

        for (Map.Entry<ContributorSource, String> entry : SOURCE_KEY_MAP.entrySet()) {
            ContributorSource source = entry.getKey();
            JSONObject bucket = (JSONObject) sc.get(entry.getValue());
            if (bucket == null) continue;
            JSONArray items = (JSONArray) bucket.get("items");
            if (items == null || items.isEmpty()) continue;

            FingerprintExtractor extractor = FingerprintExtractor.forSource(source);

            // Group items by ClusterKey, accumulating raw weight + sample event
            Map<ClusterKey, ClusterAcc> grouped = new LinkedHashMap<>();
            int index = 0;
            for (Object o : items) {
                if (!(o instanceof JSONObject item)) continue;
                Map<String, String> eventMap = jsonItemToEventMap(source, item);
                ClusterKey key = extractor.extract(eventMap);
                double raw = (item.get("raw") instanceof Number rn) ? rn.doubleValue() : 0.0;
                final int firstIdxSnap = index;   // effectively final for the lambda
                grouped.computeIfAbsent(key, k -> new ClusterAcc(source, eventMap, firstIdxSnap))
                        .add(raw);
                index++;
            }
            for (Map.Entry<ClusterKey, ClusterAcc> ge : grouped.entrySet()) {
                ClusterAcc acc = ge.getValue();
                all.add(new Cluster(ge.getKey(), source,
                        acc.count, acc.rawTotal, acc.firstIndex, acc.sample));
            }
        }
        return all;
    }

    /** Map snapshot item shape to the event-map shape extractors expect. */
    private static Map<String, String> jsonItemToEventMap(ContributorSource source, JSONObject item) {
        Map<String, String> m = new HashMap<>();
        switch (source) {
            case FALLBACKS -> {
                m.put("name",   String.valueOf(item.getOrDefault("name",   "")));
                m.put("target", String.valueOf(item.getOrDefault("target", "")));
                m.put("page",   String.valueOf(item.getOrDefault("page",   "")));
            }
            case JS_ERRORS -> {
                m.put("context", String.valueOf(item.getOrDefault("context", "")));
                m.put("message", String.valueOf(item.getOrDefault("message", "")));
            }
            case TEST_FAILURES -> {
                // Snapshot test-failure item has just "test" (and optionally
                // "reason"/"stackTrace"). Best-effort mapping.
                String t = String.valueOf(item.getOrDefault("test", ""));
                String testClass = t;
                String testMethod = "";
                int dot = t.lastIndexOf('.');
                if (dot > 0) {
                    testClass  = t.substring(0, dot);
                    testMethod = t.substring(dot + 1);
                }
                m.put("testClass",  testClass);
                m.put("testMethod", testMethod);
                m.put("reason",     String.valueOf(item.getOrDefault("reason",     "")));
                m.put("stackTrace", String.valueOf(item.getOrDefault("stackTrace", "")));
            }
        }
        return m;
    }

    private static final Map<ContributorSource, String> SOURCE_KEY_MAP = Map.of(
            ContributorSource.FALLBACKS,    "fallbacks",
            ContributorSource.JS_ERRORS,    "jsErrors",
            ContributorSource.TEST_FAILURES, "testFailures"
    );

    /** Mutable accumulator while folding items into clusters. */
    private static final class ClusterAcc {
        final ContributorSource source;
        final Map<String, String> sample;
        final int firstIndex;
        int count = 0;
        double rawTotal = 0.0;
        ClusterAcc(ContributorSource source, Map<String, String> sample, int firstIndex) {
            this.source = source;
            this.sample = sample;
            this.firstIndex = firstIndex;
        }
        void add(double raw) { count++; rawTotal += raw; }
    }
}
