package testing.health.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.cluster.replay.ReplayHarness;
import utils.health.cluster.replay.ReplayRunResult;
import utils.health.schema.LossyV2ToV3Migrator;
import utils.health.schema.MigrationOutcome;
import utils.health.schema.MigrationResult;
import utils.health.schema.SchemaMigrationService;
import utils.health.schema.SnapshotSchemaVersion;

/**
 * Runs B4 Phase 2 migrators against the actual on-disk files that
 * triggered B4. Two cases:
 *
 * <ul>
 *   <li>{@code reports/trend/health_snapshot.json} → must migrate as
 *       SUCCESS_NATIVE (already V3, no transformation).</li>
 *   <li>{@code reports/trend/history.json} → must migrate as
 *       SUCCESS_LOSSY (V2 aggregate, all items arrays empty in output,
 *       lossy stamp present, downstream replay produces 0 raw events
 *       which is the honest "no signal" result).</li>
 * </ul>
 *
 * <p>If these tests pass, B4 Phase 2 has correctly handled the exact
 * inputs that C4 Phase 3 could not replay — replacing the silent
 * "scoreContributors not present" failure mode with an explicit
 * "lossy migration, no recoverable signal" path.</p>
 *
 * <p>Tests skip gracefully when the files don't exist (e.g. CI without
 * historical data).</p>
 */
public class SchemaMigrationRealFileTest {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrationRealFileTest.class);

    @Test(groups = {"sanity"})
    public void currentSnapshot_migratesNative() throws Exception {
        Path p = Paths.get("reports/trend/health_snapshot.json");
        if (!Files.exists(p)) {
            LOG.info("[real-file] {} not present — skipping.", p);
            return;
        }
        MigrationResult r = SchemaMigrationService.migrateToCurrent(Files.readString(p));
        LOG.info("[real-file] {} → outcome={}, source={}, lossy={}",
                p, r.outcome(), r.sourceVersion(), r.lossy());
        Assert.assertEquals(r.outcome(), MigrationOutcome.SUCCESS_NATIVE,
                "current snapshot is V3 — must pass through unchanged");
        Assert.assertEquals(r.sourceVersion(), SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH);
        Assert.assertFalse(r.lossy());
    }

    @Test(groups = {"sanity"})
    public void historyJson_migratesLossy_withEmptyItemsAndHonestStamps() throws Exception {
        Path p = Paths.get("reports/trend/history.json");
        if (!Files.exists(p)) {
            LOG.info("[real-file] {} not present — skipping.", p);
            return;
        }
        MigrationResult r = SchemaMigrationService.migrateToCurrent(Files.readString(p));
        LOG.info("[real-file] {} → outcome={}, source={}, lossy={}, reasons={}",
                p, r.outcome(), r.sourceVersion(), r.lossy(), r.reasons());

        Assert.assertEquals(r.outcome(), MigrationOutcome.SUCCESS_LOSSY,
                "history.json is V2 aggregate — must migrate lossy");
        Assert.assertEquals(r.sourceVersion(), SnapshotSchemaVersion.V2_AGGREGATE);
        Assert.assertTrue(r.lossy());
        Assert.assertFalse(r.reasons().isEmpty(),
                "lossy migration MUST carry at least one reason");

        // The migrated object itself must declare its lossy provenance.
        JSONObject m = r.migrated();
        Assert.assertEquals(m.get(LossyV2ToV3Migrator.LOSSY_FLAG_KEY), Boolean.TRUE,
                "migrated history.json must carry migrationLossy=true so no downstream "
                + "consumer can mistake reconstructed shape for real contributor data");
        Assert.assertEquals(m.get(LossyV2ToV3Migrator.SOURCE_KEY), "V2_AGGREGATE");
        Assert.assertTrue(m.get(LossyV2ToV3Migrator.NOTES_KEY) instanceof JSONArray);

        // scoreContributors must exist with EMPTY items arrays.
        JSONObject sc = (JSONObject) m.get("scoreContributors");
        Assert.assertNotNull(sc);
        for (String key : new String[]{"fallbacks", "testFailures", "jsErrors"}) {
            JSONObject bucket = (JSONObject) sc.get(key);
            Assert.assertNotNull(bucket, key + " bucket must exist after migration");
            JSONArray items = (JSONArray) bucket.get("items");
            Assert.assertTrue(items.isEmpty(),
                    key + ".items must be EMPTY — V2 has no raw events to reconstruct, "
                    + "inventing items would violate the never-pretend invariant");
        }

        // Downstream contract: migrated V2 must replay through the C4
        // harness without throwing and produce a 0-signal report.
        ReplayRunResult run = ReplayHarness.replayParsed("history-migrated", m,
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertNotNull(run);
        Assert.assertEquals(run.policyA().totalRawEventCount(), 0,
                "migrated V2 must produce 0 raw events through replay — the honest "
                + "answer for \"what does C4 say?\" when the data isn't recoverable");
        Assert.assertEquals(run.policyA().totalClustersOverCap(), 0,
                "migrated V2 must produce 0 over-cap clusters");
    }
}
