package utils.health.schema;

/**
 * The schema versions a {@code health_snapshot.json} (or its aggregate
 * predecessor, {@code history.json}) can be in. Exists so version
 * identity is a typed value, not a magic integer.
 *
 * <p><b>Stability contract:</b> the enum order encodes monotonic
 * evolution. {@code V1_LEGACY < V2_AGGREGATE < V3_CONTRIBUTOR_RICH}.
 * New versions append to the end. Never reorder; never recycle. Adding
 * a new version is the trigger for adding a corresponding
 * {@code SchemaMigrator} (B4 Phase 2).</p>
 *
 * <p>The {@link #c4Replayable()} predicate answers the operational
 * question that C4 Phase 3 surfaced: "can this snapshot produce a useful
 * replay report?" Without per-event raw data in {@code scoreContributors.items},
 * the answer is no — Policy A and Policy B both score zero, the report
 * is empty, and the user gets a misleading "no divergence" signal.</p>
 */
public enum SnapshotSchemaVersion {

    /** Unrecognized or unparseable. Conservative default — treat as unreplayable. */
    UNKNOWN(-1, false),

    /**
     * Pre-versioning legacy format. No {@code schemaVersion} field, no
     * {@code scoreContributors}, no {@code runs[]} aggregation. Smallest,
     * oldest format. Cannot be replayed for C4 — raw event data is gone.
     */
    V1_LEGACY(1, false),

    /**
     * Aggregate format. Has {@code schemaVersion: 2}, top-level
     * {@code runs[]} array OR aggregate fields like {@code overallScore},
     * {@code fallbacks: []}, {@code testFailures: []} as flat arrays.
     * The fallback/test-failure arrays carry COUNT-level data only —
     * no per-event raw weights or sample events. NOT C4-replayable.
     */
    V2_AGGREGATE(2, false),

    /**
     * Contributor-rich format. Has {@code schemaVersion: 3} plus
     * {@code scoreContributors} with per-source {@code items[]} arrays
     * containing raw events. THIS is the format C4 Phase 3 needs.
     */
    V3_CONTRIBUTOR_RICH(3, true);

    /** The integer code as stored in the snapshot's {@code schemaVersion} field. */
    private final int code;
    /** Whether this schema carries enough information for the C4 replay harness. */
    private final boolean c4Replayable;

    SnapshotSchemaVersion(int code, boolean c4Replayable) {
        this.code = code;
        this.c4Replayable = c4Replayable;
    }

    public int code() { return code; }

    /** True iff a snapshot in this version can be fed to {@code ReplayHarness}. */
    public boolean c4Replayable() { return c4Replayable; }

    /**
     * Resolve a numeric schema code to the enum. {@link #UNKNOWN} when
     * the code is not recognized (forward-compat: a future V4 file viewed
     * by today's binary returns UNKNOWN until a release adds the enum).
     */
    public static SnapshotSchemaVersion fromCode(int code) {
        for (SnapshotSchemaVersion v : values()) {
            if (v.code == code) return v;
        }
        return UNKNOWN;
    }
}
