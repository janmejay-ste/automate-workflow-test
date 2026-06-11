package utils.health.schema;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Routing layer: takes a raw snapshot (JSON string or parsed
 * {@link JSONObject}), classifies via {@link SchemaDetector}, dispatches
 * to the appropriate {@link SchemaMigrator}, and returns the
 * {@link MigrationResult}.
 *
 * <p>This is the ONLY public entry point Phase 3 integration will need.
 * Callers don't construct migrators directly — the version-to-migrator
 * routing is centralized here so adding a new migrator means one
 * additional case in {@link #migrateToCurrent(JSONObject)}, not changes
 * scattered throughout the codebase.</p>
 *
 * <p>The exhaustive switch on {@link SnapshotSchemaVersion} is
 * deliberate: when a new version is added to the enum, this method
 * stops compiling until the new case is handled. Compile-time
 * enforcement of migration coverage.</p>
 */
public final class SchemaMigrationService {

    private SchemaMigrationService() {}

    /**
     * Migrate a JSON string to the current schema. Returns
     * {@link MigrationOutcome#UNKNOWN_SCHEMA} for null/blank/unparseable input.
     */
    public static MigrationResult migrateToCurrent(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return MigrationResult.unknownSchema("input was null or blank");
        }
        try {
            Object parsed = new JSONParser().parse(snapshotJson);
            if (!(parsed instanceof JSONObject root)) {
                return MigrationResult.unknownSchema(
                    "top-level JSON value was not an object (got "
                    + (parsed == null ? "null" : parsed.getClass().getSimpleName()) + ")");
            }
            return migrateToCurrent(root);
        } catch (Exception e) {
            return MigrationResult.unknownSchema("JSON parse failed: " + e.getMessage());
        }
    }

    /** Migrate a pre-parsed snapshot. */
    public static MigrationResult migrateToCurrent(JSONObject snapshot) {
        if (snapshot == null) {
            return MigrationResult.unknownSchema("input snapshot was null");
        }
        SnapshotSchemaVersion v = SchemaDetector.detect(snapshot);
        return switch (v) {
            case V3_CONTRIBUTOR_RICH ->
                new IdentityMigratorV3().migrate(snapshot);
            case V2_AGGREGATE ->
                new LossyV2ToV3Migrator().migrate(snapshot);
            case V1_LEGACY ->
                MigrationResult.unsupported(v,
                    "V1_LEGACY format is recognized but no migration path exists. "
                    + "V1 predates the schema-stamping era and carries no recoverable "
                    + "contributor data. If V1 support is needed, write a migrator "
                    + "and add it to the SchemaMigrator sealed permits list.");
            case UNKNOWN ->
                MigrationResult.unknownSchema(
                    "schema detector could not identify the snapshot's version");
        };
    }
}
