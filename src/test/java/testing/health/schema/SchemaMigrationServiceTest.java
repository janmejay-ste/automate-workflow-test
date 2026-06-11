package testing.health.schema;

import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.schema.LossyV2ToV3Migrator;
import utils.health.schema.MigrationOutcome;
import utils.health.schema.MigrationResult;
import utils.health.schema.SchemaMigrationService;
import utils.health.schema.SnapshotSchemaVersion;

/**
 * Locks in B4 Phase 2 behavior across all four migration outcomes and
 * the never-pretend-reconstruction-succeeded invariants.
 *
 * <p>Each test exercises one of:</p>
 * <ol>
 *   <li>SUCCESS_NATIVE — V3 input passes through unchanged</li>
 *   <li>SUCCESS_LOSSY — V2 input migrated forward, lossy flag + reasons</li>
 *   <li>UNSUPPORTED — V1 input recognized but no migration path</li>
 *   <li>UNKNOWN_SCHEMA — unparseable / unrecognized input</li>
 * </ol>
 *
 * <p>Plus invariant checks on {@link MigrationResult} that prevent
 * mis-construction (SUCCESS_NATIVE + lossy=true throws,
 * SUCCESS_LOSSY + empty reasons throws, etc.).</p>
 */
public class SchemaMigrationServiceTest {

    // ────────────────────────────────────────────────────────────────────
    // Outcome path 1: SUCCESS_NATIVE (V3 → V3 identity)
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void v3Input_passesThroughNative_notLossy() {
        String json = "{\"schemaVersion\":3,"
                    + "\"score\":85,\"penalty\":15.0,"
                    + "\"scoreContributors\":{"
                    + "\"fallbacks\":{\"source\":\"fallbacks\",\"items\":[]}}}";
        MigrationResult r = SchemaMigrationService.migrateToCurrent(json);

        Assert.assertEquals(r.outcome(), MigrationOutcome.SUCCESS_NATIVE);
        Assert.assertEquals(r.sourceVersion(), SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH);
        Assert.assertFalse(r.lossy(), "native migration is never lossy");
        Assert.assertTrue(r.reasons().isEmpty(), "no reasons for a clean identity migration");
        Assert.assertNotNull(r.migrated(), "migrated JSON must be present");
        Assert.assertTrue(r.outcome().ok());
    }

    // ────────────────────────────────────────────────────────────────────
    // Outcome path 2: SUCCESS_LOSSY (V2 → V3)
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void v2AggregateInput_migratedLossy_itemsArraysAreEmpty() {
        // V2 history.json shape: runs[] array with inner metrics.
        String json = "{\"schemaVersion\":2,"
                    + "\"runs\":[{\"runId\":\"r1\","
                    + "  \"metrics\":{\"overallScore\":85,\"totalPenalty\":15.0,"
                    + "               \"fallbacks\":[],\"testFailures\":[]}}]}";
        MigrationResult r = SchemaMigrationService.migrateToCurrent(json);

        Assert.assertEquals(r.outcome(), MigrationOutcome.SUCCESS_LOSSY);
        Assert.assertEquals(r.sourceVersion(), SnapshotSchemaVersion.V2_AGGREGATE);
        Assert.assertTrue(r.lossy(), "V2→V3 is structurally always lossy");
        Assert.assertFalse(r.reasons().isEmpty(),
                "lossy migration MUST carry reasons — this is the never-pretend invariant");

        // The migrated object MUST carry the lossy stamps.
        JSONObject m = r.migrated();
        Assert.assertEquals(m.get(LossyV2ToV3Migrator.LOSSY_FLAG_KEY), Boolean.TRUE,
                "top-level migrationLossy flag must be set on the migrated JSON itself");
        Assert.assertEquals(m.get(LossyV2ToV3Migrator.SOURCE_KEY), "V2_AGGREGATE",
                "migrationSource must record what was migrated FROM");
        Assert.assertTrue(m.get(LossyV2ToV3Migrator.NOTES_KEY) instanceof JSONArray,
                "migrationNotes must be present and an array");

        // Aggregate score / penalty preserved.
        Assert.assertNotNull(m.get("score"));
        Assert.assertNotNull(m.get("penalty"));

        // scoreContributors present, items arrays EMPTY (never invented).
        JSONObject sc = (JSONObject) m.get("scoreContributors");
        Assert.assertNotNull(sc, "migrated output must have V3-shape scoreContributors");
        for (String key : List.of("fallbacks", "testFailures", "jsErrors")) {
            JSONObject bucket = (JSONObject) sc.get(key);
            Assert.assertNotNull(bucket, key + " bucket must exist");
            JSONArray items = (JSONArray) bucket.get("items");
            Assert.assertNotNull(items, key + ".items must exist");
            Assert.assertTrue(items.isEmpty(),
                    "scoreContributors." + key + ".items MUST be empty — V2 has no raw events to "
                    + "reconstruct. Inventing items would defeat the never-pretend invariant.");
        }
    }

    @Test(groups = {"sanity"})
    public void v2_topLevelAggregate_noRunsArray_stillMigrates() {
        // Older shape: schemaVersion 2 with aggregate fields at top level,
        // no runs[] wrapper.
        String json = "{\"schemaVersion\":2,\"overallScore\":92,\"totalPenalty\":8.0}";
        MigrationResult r = SchemaMigrationService.migrateToCurrent(json);
        Assert.assertEquals(r.outcome(), MigrationOutcome.SUCCESS_LOSSY);
        Assert.assertTrue(r.lossy());
    }

    @Test(groups = {"sanity"})
    public void migratedV2_passesC4ReplayHarnessAsZeroSignal() {
        // The downstream contract: a migrated V2 snapshot must replay
        // without throwing, but must produce zero raw events / zero
        // over-cap clusters. That's the correct "no signal" outcome.
        String json = "{\"schemaVersion\":2,"
                    + "\"runs\":[{\"runId\":\"r1\","
                    + "  \"metrics\":{\"overallScore\":50,\"totalPenalty\":50.0,"
                    + "               \"fallbacks\":[],\"testFailures\":[]}}]}";
        MigrationResult r = SchemaMigrationService.migrateToCurrent(json);
        Assert.assertEquals(r.outcome(), MigrationOutcome.SUCCESS_LOSSY);

        // Run the migrated output through the actual replay harness.
        utils.health.cluster.replay.ReplayRunResult run =
                utils.health.cluster.replay.ReplayHarness.replayParsed(
                    "v2-migrated", r.migrated(),
                    utils.health.cluster.replay.ReplayHarness.DEFAULT_CAPS);
        Assert.assertNotNull(run, "migrated V2 must parse via replay harness");
        Assert.assertEquals(run.policyA().totalRawEventCount(), 0,
                "migrated V2 has no raw events — replay must report 0");
        Assert.assertEquals(run.policyA().totalClustersOverCap(), 0,
                "migrated V2 has no clusters — over-cap must be 0");
    }

    // ────────────────────────────────────────────────────────────────────
    // Outcome path 3: UNSUPPORTED (V1)
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void v1LegacyInput_returnsUnsupported_notUnknown() {
        // V1 has no schemaVersion, no scoreContributors, no aggregate.
        String json = "{\"someOldField\":\"value\"}";
        MigrationResult r = SchemaMigrationService.migrateToCurrent(json);

        Assert.assertEquals(r.outcome(), MigrationOutcome.UNSUPPORTED,
                "V1 is RECOGNIZED but unmigratable — UNSUPPORTED, not UNKNOWN. "
                + "This distinction is the user-requested separation: V99 = unknown, "
                + "V1 = unsupported. Logs need this difference for triage.");
        Assert.assertEquals(r.sourceVersion(), SnapshotSchemaVersion.V1_LEGACY,
                "sourceVersion must show what was recognized, even on failure paths");
        Assert.assertNull(r.migrated(),
                "unsupported migrations must NOT carry a fake migrated payload");
        Assert.assertFalse(r.lossy());
        Assert.assertFalse(r.outcome().ok());
    }

    // ────────────────────────────────────────────────────────────────────
    // Outcome path 4: UNKNOWN_SCHEMA
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void malformedJson_returnsUnknownSchema_distinctFromUnsupported() {
        MigrationResult r = SchemaMigrationService.migrateToCurrent("{not valid json");
        Assert.assertEquals(r.outcome(), MigrationOutcome.UNKNOWN_SCHEMA,
                "unparseable JSON is UNKNOWN_SCHEMA — distinct from UNSUPPORTED. "
                + "This is the V99-equivalent case the user called out.");
        Assert.assertEquals(r.sourceVersion(), SnapshotSchemaVersion.UNKNOWN);
        Assert.assertNull(r.migrated());
    }

    @Test(groups = {"sanity"})
    public void nullInput_returnsUnknownSchema() {
        Assert.assertEquals(SchemaMigrationService.migrateToCurrent((String) null).outcome(),
                MigrationOutcome.UNKNOWN_SCHEMA);
        Assert.assertEquals(SchemaMigrationService.migrateToCurrent((JSONObject) null).outcome(),
                MigrationOutcome.UNKNOWN_SCHEMA);
    }

    // ────────────────────────────────────────────────────────────────────
    // MigrationResult invariants — the never-pretend contract
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"}, expectedExceptions = IllegalArgumentException.class)
    public void invariant_successNativeCannotBeLossy() {
        new MigrationResult(MigrationOutcome.SUCCESS_NATIVE,
                SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH, new JSONObject(),
                true,  // <-- lossy contradiction
                List.of());
    }

    @Test(groups = {"sanity"}, expectedExceptions = IllegalArgumentException.class)
    public void invariant_successLossyMustCarryReasons() {
        new MigrationResult(MigrationOutcome.SUCCESS_LOSSY,
                SnapshotSchemaVersion.V2_AGGREGATE, new JSONObject(),
                true,
                List.of());   // <-- empty reasons → constructor must throw
    }

    @Test(groups = {"sanity"}, expectedExceptions = IllegalArgumentException.class)
    public void invariant_unsupportedCannotCarryMigratedPayload() {
        new MigrationResult(MigrationOutcome.UNSUPPORTED,
                SnapshotSchemaVersion.V1_LEGACY,
                new JSONObject(),  // <-- a fake "successful" payload
                false, List.of("reason"));
    }

    @Test(groups = {"sanity"}, expectedExceptions = IllegalArgumentException.class)
    public void invariant_successMustCarryMigratedPayload() {
        new MigrationResult(MigrationOutcome.SUCCESS_NATIVE,
                SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH,
                null,   // <-- claims success but no payload
                false, List.of());
    }

    // ────────────────────────────────────────────────────────────────────
    // Routing exhaustiveness
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void allDetectableVersions_haveARoutingCase() {
        // For every version the detector might return, the service must
        // produce SOME MigrationResult — never throw, never return null.
        // If a new version is added without a routing case, the
        // SchemaMigrationService switch breaks at compile time (it's an
        // exhaustive switch on a non-default-arm enum). This test is a
        // belt-and-braces runtime check for the same property.
        for (SnapshotSchemaVersion v : SnapshotSchemaVersion.values()) {
            // Construct minimal JSON that the detector will resolve to v.
            String json = jsonForVersion(v);
            MigrationResult r = SchemaMigrationService.migrateToCurrent(json);
            Assert.assertNotNull(r, "no MigrationResult for " + v);
            Assert.assertNotNull(r.outcome(), "no outcome for " + v);
        }
    }

    private static String jsonForVersion(SnapshotSchemaVersion v) {
        return switch (v) {
            case V3_CONTRIBUTOR_RICH ->
                "{\"schemaVersion\":3,\"scoreContributors\":{\"fallbacks\":{\"items\":[]}}}";
            case V2_AGGREGATE ->
                "{\"schemaVersion\":2,\"runs\":[{\"runId\":\"r1\",\"metrics\":{}}]}";
            case V1_LEGACY ->
                "{\"oldField\":\"x\"}";
            case UNKNOWN ->
                "[1,2,3]";   // top-level array → detector returns UNKNOWN
        };
    }
}
