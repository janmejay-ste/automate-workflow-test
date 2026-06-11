package utils.health.schema;

import org.json.simple.JSONObject;

/**
 * Strategy for migrating one specific source schema version forward to
 * the current schema. Implementations are deliberately tiny — each
 * migrator handles exactly one source version. New version added to
 * {@link SnapshotSchemaVersion} → new migrator class added to the
 * {@code permits} list. The compiler enforces that the routing layer
 * (in {@link SchemaMigrationService}) handles every concrete migrator.
 *
 * <p><b>Lossy contract.</b> Lossy migrators MUST stamp
 * {@code migrationLossy: true} on the returned JSON and accumulate
 * non-empty {@code reasons} on the {@link MigrationResult}. The
 * {@link MigrationResult} record's constructor enforces this; this
 * comment exists so the contract is also visible at the interface
 * level.</p>
 *
 * <p><b>What stays out of this interface.</b> No replay integration, no
 * dashboard output, no I/O. Pure function from JSON to JSON, deterministic,
 * no static state. B4 Phase 3 will wire these into ReplayHarness; until
 * then they're standalone and provable.</p>
 */
public sealed interface SchemaMigrator
        permits IdentityMigratorV3, LossyV2ToV3Migrator {

    /** The source version this migrator handles. */
    SnapshotSchemaVersion sourceVersion();

    /**
     * Migrate one snapshot. Never throws; failure modes are returned as
     * {@link MigrationOutcome#UNSUPPORTED} or {@link MigrationOutcome#UNKNOWN_SCHEMA}
     * results.
     */
    MigrationResult migrate(JSONObject snapshot);
}
