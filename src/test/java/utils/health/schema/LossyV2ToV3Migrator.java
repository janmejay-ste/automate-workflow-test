package utils.health.schema;

import java.util.ArrayList;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Migrates a V2 aggregate snapshot (the {@code history.json} shape or
 * the older single-run aggregate) forward into a V3-shaped object.
 *
 * <p><b>This migrator is always lossy.</b> V2 aggregate format does not
 * carry per-event raw contributor data — by the time a V2 file is
 * written, the events have been counted and summed and the raw shapes
 * are gone. Reconstruction is impossible. The migrator does NOT attempt
 * to invent raw events; instead it produces a V3-shaped object with
 * empty {@code items} arrays and stamps the result with explicit
 * markers so downstream code never mistakes reconstructed data for real
 * contributor data.</p>
 *
 * <p><b>What the output contains:</b></p>
 * <ul>
 *   <li>Top-level shape mirrors V3 (schemaVersion, score, penalty,
 *       scoreContributors with per-source buckets).</li>
 *   <li>Aggregate totals (overallScore / totalPenalty / individual
 *       per-source totals) are preserved where present in the V2 input.</li>
 *   <li>Every contributor bucket has {@code items: []} — never invented.</li>
 *   <li>Top-level {@code migrationLossy: true} stamp.</li>
 *   <li>Top-level {@code migrationNotes: [...]} array enumerating what
 *       was lost.</li>
 *   <li>Top-level {@code migrationSource: "V2_AGGREGATE"} for forensics.</li>
 * </ul>
 *
 * <p><b>Operational meaning for replay:</b> a downstream
 * {@code ReplayHarness} reading this migrated object will compute
 * {@code totalRawEventCount = 0} and {@code totalClustersOverCap = 0}.
 * That's the correct answer for "what does C4 say about this run?" —
 * the underlying raw data isn't available, so neither Policy A nor
 * Policy B can produce a meaningful delta. The lossy flag tells the
 * report consumer this is an honest "no signal" not a meaningful
 * "no divergence."</p>
 */
public final class LossyV2ToV3Migrator implements SchemaMigrator {

    public static final String LOSSY_FLAG_KEY   = "migrationLossy";
    public static final String NOTES_KEY        = "migrationNotes";
    public static final String SOURCE_KEY       = "migrationSource";

    @Override
    public SnapshotSchemaVersion sourceVersion() {
        return SnapshotSchemaVersion.V2_AGGREGATE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public MigrationResult migrate(JSONObject snapshot) {
        if (snapshot == null) {
            return MigrationResult.unknownSchema("input snapshot was null");
        }

        List<String> reasons = new ArrayList<>();
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH.code());

        // Preserve identification fields when present.
        copyIfPresent(snapshot, root, "timestamp");
        copyIfPresent(snapshot, root, "status");

        // V2 shape variant A: runs[] array (history.json). Take the most
        // recent run and migrate its inner metrics.
        JSONObject sourceMetrics = extractMetrics(snapshot, reasons);

        // Score + penalty: extract from either the wrapper or the inner metrics.
        Object score = firstNonNull(snapshot.get("score"),
                                     sourceMetrics.get("overallScore"),
                                     sourceMetrics.get("score"));
        if (score != null) root.put("score", score);
        else reasons.add("overall score not present in V2 input");

        Object penalty = firstNonNull(snapshot.get("penalty"),
                                       sourceMetrics.get("totalPenalty"),
                                       sourceMetrics.get("penalty"));
        if (penalty != null) root.put("penalty", penalty);

        // scoreContributors — the field whose absence motivated B4. Build
        // the V3 shape with empty items arrays. NEVER invent per-event data.
        JSONObject sc = new JSONObject();
        for (String key : new String[]{"testFailures", "jsErrors", "fallbacks"}) {
            JSONObject bucket = new JSONObject();
            bucket.put("source", key);
            // Best-effort: if the V2 input has an aggregate count for this
            // source, carry it as appliedTotal — the SUM is preserved even
            // when the per-event breakdown isn't.
            Object aggregateApplied = extractAggregateForSource(sourceMetrics, key);
            bucket.put("rawTotal",     aggregateApplied != null ? aggregateApplied : 0.0);
            bucket.put("appliedTotal", aggregateApplied != null ? aggregateApplied : 0.0);
            bucket.put("suppressed",   0.0);
            bucket.put("items", new JSONArray());  // <-- ALWAYS EMPTY. NEVER INVENTED.
            sc.put(key, bucket);
        }
        sc.put("totalRaw",        penalty != null ? penalty : 0.0);
        sc.put("totalApplied",    penalty != null ? penalty : 0.0);
        sc.put("totalSuppressed", 0.0);
        root.put("scoreContributors", sc);

        // The honest-disclosure stamps. The replay report (Phase 3, not
        // yet integrated) will surface these so the operator cannot
        // accidentally read reconstructed data as real data.
        root.put(LOSSY_FLAG_KEY, true);
        root.put(SOURCE_KEY, sourceVersion().name());

        // Build the reasons list. At minimum, the always-lossy note.
        reasons.add(0, "V2 aggregate format does not carry per-event raw "
                + "contributor data; all scoreContributors.items[] arrays are "
                + "empty in the migrated output");
        if (!hasNonEmptyAggregateFields(sourceMetrics)) {
            reasons.add("source metrics carried no fallbacks / testFailures / "
                    + "jsErrors aggregate fields — even the sum-level data is absent");
        }
        JSONArray notesArr = new JSONArray();
        notesArr.addAll(reasons);
        root.put(NOTES_KEY, notesArr);

        return MigrationResult.lossy(SnapshotSchemaVersion.V2_AGGREGATE, root, reasons);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Extract the inner metrics block we'll migrate from. Returns empty obj on miss. */
    @SuppressWarnings("unchecked")
    private static JSONObject extractMetrics(JSONObject snapshot, List<String> reasons) {
        // Variant A: top-level runs[] array → pick last entry's metrics.
        Object runs = snapshot.get("runs");
        if (runs instanceof JSONArray runsArr && !runsArr.isEmpty()) {
            Object last = runsArr.get(runsArr.size() - 1);
            if (last instanceof JSONObject lastRun) {
                Object metrics = lastRun.get("metrics");
                if (metrics instanceof JSONObject m) return m;
                // Single-run inline shape — the run itself IS the metrics block.
                return lastRun;
            }
            reasons.add("runs[] array's last entry was not a JSON object");
            return new JSONObject();
        }
        // Variant B: top-level aggregate fields (overallScore at root).
        if (snapshot.containsKey("overallScore") || snapshot.containsKey("totalPenalty")) {
            return snapshot;
        }
        // Variant C: unrecognized shape — empty extraction.
        return new JSONObject();
    }

    /** Aggregate carrier for a single source: appliedTotal-shaped if available. */
    private static Object extractAggregateForSource(JSONObject metrics, String sourceKey) {
        if (metrics == null) return null;
        // Some V2 variants store per-source aggregates as arrays of length N
        // where N is the count. Others store as a {"count": N} object. Try
        // the most common shapes; return null when nothing usable is present.
        Object direct = metrics.get(sourceKey);
        if (direct instanceof JSONArray arr) {
            // Best signal: count-as-applied. We don't have weight info from
            // V2, so we can only report event counts, not penalty points.
            // Carry the count itself — clear caveat in the migration notes.
            return (double) arr.size();
        }
        if (direct instanceof JSONObject obj && obj.get("count") instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static boolean hasNonEmptyAggregateFields(JSONObject metrics) {
        if (metrics == null) return false;
        for (String k : new String[]{"fallbacks", "testFailures", "jsErrors"}) {
            Object v = metrics.get(k);
            if (v instanceof JSONArray a && !a.isEmpty()) return true;
            if (v instanceof JSONObject o && o.containsKey("count")) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void copyIfPresent(JSONObject src, JSONObject dst, String key) {
        if (src.containsKey(key)) dst.put(key, src.get(key));
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }
}
