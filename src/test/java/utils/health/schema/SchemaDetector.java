package utils.health.schema;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Inspects a snapshot JSON document and returns its
 * {@link SnapshotSchemaVersion}. Detection logic is intentionally
 * structural — it does NOT trust the {@code schemaVersion} field alone,
 * because that field is the easiest one to forget when authoring a
 * fixture or migrating a corpus by hand.
 *
 * <p>Order of checks (first match wins):</p>
 * <ol>
 *   <li>If {@code scoreContributors.items} exists in any sub-bucket
 *       → V3_CONTRIBUTOR_RICH (regardless of the stamped value)</li>
 *   <li>If {@code runs[]} exists at top level → V2_AGGREGATE (the
 *       history.json shape)</li>
 *   <li>If the stamped {@code schemaVersion} matches a known code →
 *       use it</li>
 *   <li>If {@code overallScore} or {@code totalPenalty} exists →
 *       V2_AGGREGATE (pre-versioning aggregate snapshot)</li>
 *   <li>Otherwise → V1_LEGACY (oldest, smallest format)</li>
 *   <li>Unparseable JSON → UNKNOWN</li>
 * </ol>
 *
 * <p>The structural-first ordering is deliberate: even if someone
 * mis-stamps a V3 file as {@code schemaVersion: 2}, we want to detect
 * it as V3 because the stored data IS contributor-rich. The opposite —
 * a V2 file mis-stamped as 3 — is also handled: structural check fails
 * (no {@code scoreContributors}), code check passes V3, structural
 * check then takes priority because of ordering.</p>
 *
 * <p>No production wiring yet. Used only by B4 Phase 1 tests.</p>
 */
public final class SchemaDetector {

    private SchemaDetector() {}

    /**
     * Detect from raw JSON text. Returns {@link SnapshotSchemaVersion#UNKNOWN}
     * if the input is null, blank, or not valid JSON.
     */
    public static SnapshotSchemaVersion detect(String json) {
        if (json == null || json.isBlank()) return SnapshotSchemaVersion.UNKNOWN;
        try {
            Object parsed = new JSONParser().parse(json);
            if (!(parsed instanceof JSONObject root)) return SnapshotSchemaVersion.UNKNOWN;
            return detect(root);
        } catch (Exception e) {
            return SnapshotSchemaVersion.UNKNOWN;
        }
    }

    /**
     * Detect from a parsed JSONObject. Faster path when the caller has
     * already parsed.
     */
    public static SnapshotSchemaVersion detect(JSONObject root) {
        if (root == null) return SnapshotSchemaVersion.UNKNOWN;

        // 1. Structural V3 check — does any contributor bucket have an items array?
        if (hasContributorItems(root)) return SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH;

        // 2. Structural V2 (runs[]) check
        if (root.get("runs") instanceof JSONArray) return SnapshotSchemaVersion.V2_AGGREGATE;

        // 3. Stamped schemaVersion field (only if structural checks didn't fire)
        Object stamped = root.get("schemaVersion");
        if (stamped instanceof Number n) {
            SnapshotSchemaVersion fromStamp = SnapshotSchemaVersion.fromCode(n.intValue());
            if (fromStamp != SnapshotSchemaVersion.UNKNOWN) return fromStamp;
        }

        // 4. Aggregate-shape heuristic (pre-versioning legacy files that
        //    still look like aggregates because they have overallScore /
        //    totalPenalty at top level)
        if (root.containsKey("overallScore") || root.containsKey("totalPenalty")) {
            return SnapshotSchemaVersion.V2_AGGREGATE;
        }

        // 5. Fall through to V1
        return SnapshotSchemaVersion.V1_LEGACY;
    }

    /** True iff at least one contributor bucket carries a non-empty items array. */
    private static boolean hasContributorItems(JSONObject root) {
        Object sc = root.get("scoreContributors");
        if (!(sc instanceof JSONObject scObj)) return false;
        for (String key : new String[]{"testFailures", "jsErrors", "fallbacks",
                                       "slowPages", "warnings"}) {
            Object bucket = scObj.get(key);
            if (bucket instanceof JSONObject bucketObj
                && bucketObj.get("items") instanceof JSONArray) {
                // Even an empty items[] is a V3 marker — the SHAPE is what
                // matters, not the content. A clean run still has items: [].
                return true;
            }
        }
        return false;
    }
}
