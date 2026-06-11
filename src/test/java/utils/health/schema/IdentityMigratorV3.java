package utils.health.schema;

import org.json.simple.JSONObject;

/**
 * Identity migrator: input is already at V3, no transformation needed.
 * Exists so the routing layer can dispatch uniformly — every recognized
 * version has a migrator, even the trivial case.
 */
public final class IdentityMigratorV3 implements SchemaMigrator {

    @Override
    public SnapshotSchemaVersion sourceVersion() {
        return SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH;
    }

    @Override
    public MigrationResult migrate(JSONObject snapshot) {
        if (snapshot == null) {
            return MigrationResult.unknownSchema("input snapshot was null");
        }
        // Pass the input through unchanged. The MigrationResult constructor
        // enforces lossy=false for SUCCESS_NATIVE — so even an attacker who
        // tampered with this code to claim "lossy=true" would fail validation.
        return MigrationResult.success(SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH, snapshot);
    }
}
