package utils.health.schema;

/**
 * The four distinct outcomes of a schema migration attempt. Kept as four
 * cases rather than two ({@code OK} / {@code FAIL}) because the user
 * triaging a bad replay needs to know exactly which branch fired:
 *
 * <ul>
 *   <li>{@link #SUCCESS_NATIVE} — input is already in the target version.
 *       No data was transformed, no data was lost.</li>
 *   <li>{@link #SUCCESS_LOSSY} — input was migrated forward, but some
 *       information could not be reconstructed. Every such result MUST
 *       carry a non-empty {@code reasons} list describing what was lost.</li>
 *   <li>{@link #UNSUPPORTED} — the input's schema version is RECOGNIZED
 *       by the detector but no migration path exists. Distinct from
 *       {@link #UNKNOWN_SCHEMA} because the corrective action is
 *       different: "write a migrator for this version" vs "investigate
 *       the unrecognized file format."</li>
 *   <li>{@link #UNKNOWN_SCHEMA} — the input could not be classified by
 *       {@link SchemaDetector}. Either unparseable JSON or a structure
 *       newer than this binary knows about.</li>
 * </ul>
 *
 * <p>The {@link #ok()} predicate covers the case where the migrated
 * snapshot is usable; callers gate downstream pipelines on it.</p>
 */
public enum MigrationOutcome {
    SUCCESS_NATIVE,
    SUCCESS_LOSSY,
    UNSUPPORTED,
    UNKNOWN_SCHEMA;

    /** True iff the migration produced a usable snapshot (regardless of lossiness). */
    public boolean ok() {
        return this == SUCCESS_NATIVE || this == SUCCESS_LOSSY;
    }
}
