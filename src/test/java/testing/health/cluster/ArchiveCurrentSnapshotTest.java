package testing.health.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import utils.health.cluster.replay.SnapshotArchiver;

/**
 * Manual archive trigger — invoked from the command line after a
 * targeted noisy suite produces a contributor-rich
 * {@code health_snapshot.json}.
 *
 * <pre>
 *   mvn test -Dtest=GoHighLevelMindbodyConnectTest
 *   mvn test -Dtest=ArchiveCurrentSnapshotTest \
 *            -Darchive.suite=GoHighLevelMindbodyConnectTest
 * </pre>
 *
 * <p>The suite label (via {@code -Darchive.suite=NAME}) gets embedded in
 * the archived filename and in the metadata sidecar. If omitted, defaults
 * to {@code "ad-hoc"}.</p>
 *
 * <p>This class also contains the unit-style verification: it stages a
 * known fixture as the source snapshot, archives, asserts the sidecar
 * fields, then restores. Ensures the archive contract stays stable even
 * if the snapshot schema evolves.</p>
 */
public class ArchiveCurrentSnapshotTest {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveCurrentSnapshotTest.class);

    private static final Path SNAPSHOT_PATH   = Paths.get("reports/trend/health_snapshot.json");
    private static final Path SNAPSHOT_BACKUP = Paths.get("reports/trend/health_snapshot.json.archive-test-backup");

    @BeforeMethod(alwaysRun = true)
    public void backupSnapshot() throws Exception {
        if (Files.exists(SNAPSHOT_PATH)) {
            Files.copy(SNAPSHOT_PATH, SNAPSHOT_BACKUP, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterMethod(alwaysRun = true)
    public void restoreSnapshot() throws Exception {
        if (Files.exists(SNAPSHOT_BACKUP)) {
            Files.move(SNAPSHOT_BACKUP, SNAPSHOT_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Operational entry point — invoked manually after a noisy suite.
     * Reads the live {@code health_snapshot.json}, archives it with
     * metadata sidecar.
     */
    @Test(groups = {"manual"})
    public void archiveCurrentLiveSnapshot() throws Exception {
        // Use the suite label if provided; default to "ad-hoc".
        String suiteLabel = System.getProperty("archive.suite", "ad-hoc");

        // Restore the live snapshot for the operational path — the
        // @BeforeMethod backup was for the unit-test path. If the test is
        // being run in isolation (no prior backup), this is a no-op.
        if (Files.exists(SNAPSHOT_BACKUP)) {
            Files.copy(SNAPSHOT_BACKUP, SNAPSHOT_PATH, StandardCopyOption.REPLACE_EXISTING);
        }

        Path archived = SnapshotArchiver.archive(suiteLabel);
        if (archived == null) {
            LOG.warn("[ArchiveCurrentSnapshotTest] No live snapshot found at {} — "
                  + "nothing to archive. Run a suite first.", SNAPSHOT_PATH);
            return;
        }
        LOG.info("[ArchiveCurrentSnapshotTest] Archived: {}", archived);
        LOG.info("[ArchiveCurrentSnapshotTest] Sidecar:   {}",
                archived.resolveSibling(archived.getFileName().toString().replace(".json", ".meta.json")));
        Assert.assertTrue(Files.exists(archived), "archived snapshot file must exist");
    }

    /**
     * Verification path: stage a known fixture snapshot with
     * {@code totalClustersOverCap = 2}, archive it, then assert the
     * sidecar carries the expected pre-computed C4 fields. Locks the
     * archive contract.
     */
    @Test(groups = {"sanity"})
    public void archiveSidecar_preComputesC4TriageFields() throws Exception {
        // Stage a fixture snapshot with TWO over-cap fallback clusters
        // (4 events each × weight 10 = raw 40, cap 30 → both over cap).
        // This is the multiAmpFallbacks fixture from Phase 3, slimmed.
        Files.createDirectories(SNAPSHOT_PATH.getParent());
        Files.writeString(SNAPSHOT_PATH, fixtureWith2OverCapClusters());

        Path archived = SnapshotArchiver.archive("FixtureArchiveVerification");
        Assert.assertNotNull(archived, "archive must succeed");
        Assert.assertTrue(Files.exists(archived), "archived snapshot must exist on disk");

        Path sidecar = archived.resolveSibling(
                archived.getFileName().toString().replace(".json", ".meta.json"));
        Assert.assertTrue(Files.exists(sidecar), "metadata sidecar must exist");

        JSONObject meta = (JSONObject) new JSONParser().parse(Files.readString(sidecar));
        Assert.assertEquals(meta.get("suite"), "FixtureArchiveVerification");
        Assert.assertNotNull(meta.get("gitCommit"), "gitCommit field must be populated (may be 'unknown')");
        Assert.assertNotNull(meta.get("status"));

        // schemaVersion must be stamped explicitly — the field that
        // unblocks B4 SchemaMigrator's forward-migration routing.
        Number sv = (Number) meta.get("schemaVersion");
        Assert.assertNotNull(sv, "schemaVersion must be present in sidecar");
        Assert.assertEquals(sv.intValue(), 3,
                "fixture snapshot is schemaVersion 3; sidecar must mirror it exactly");

        // The KEY assertion: the sidecar's totalClustersOverCap matches
        // what the replay harness would compute. This is what makes the
        // sidecar useful for triage-without-replay.
        Number overCap = (Number) meta.get("totalClustersOverCap");
        Assert.assertEquals(overCap.intValue(), 2,
                "fixture has 2 over-cap fallback clusters; sidecar must reflect this exactly");

        // Per-source breakdown of over-cap should attribute to FALLBACKS.
        JSONObject perSource = (JSONObject) meta.get("perSourceClustersOverCap");
        Assert.assertEquals(((Number) perSource.get("FALLBACKS")).intValue(), 2,
                "perSource breakdown must show FALLBACKS=2");

        // Both policy penalties must also be pre-computed.
        Assert.assertNotNull(meta.get("policyAPenalty"), "policyA penalty must be in sidecar");
        Assert.assertNotNull(meta.get("policyBPenalty"), "policyB penalty must be in sidecar");

        // Cleanup the test's archived files so they don't pollute real
        // ops data. The production archive directory should only contain
        // real noisy-suite snapshots.
        Files.deleteIfExists(archived);
        Files.deleteIfExists(sidecar);
    }

    @Test(groups = {"sanity"})
    public void archive_missingSnapshot_returnsNullGracefully() throws Exception {
        // Backup is held by @BeforeMethod. Delete the live file.
        Files.deleteIfExists(SNAPSHOT_PATH);
        Path result = SnapshotArchiver.archive("test-missing-source");
        Assert.assertNull(result, "missing source snapshot → archive() returns null, no exception");
    }

    // ── fixture ─────────────────────────────────────────────────────────

    private String fixtureWith2OverCapClusters() {
        StringBuilder items = new StringBuilder();
        // 2 clusters, 4 events each on distinct selectors; weight 10 →
        // each cluster's raw=40 (cap=30, so both Over Cap).
        for (int cluster = 0; cluster < 2; cluster++) {
            for (int evt = 0; evt < 4; evt++) {
                if (items.length() > 0) items.append(",");
                items.append("{\"name\":\"F").append(cluster).append("\",")
                     .append("\"target\":\"button.cluster").append(cluster).append("\",")
                     .append("\"page\":\"/p1\",\"raw\":10.0,\"applied\":10.0}");
            }
        }
        return "{\"schemaVersion\":3,\"timestamp\":1780000000000,"
             + "\"score\":40,\"smoothedScore\":40,\"penalty\":60.0,\"status\":\"WARNING\","
             + "\"scoreContributors\":{"
             + "\"fallbacks\":{\"source\":\"fallbacks\",\"rawTotal\":80.0,"
             + "\"appliedTotal\":30.0,\"suppressed\":0.0,\"items\":[" + items + "]},"
             + "\"totalRaw\":80.0,\"totalApplied\":30.0,\"totalSuppressed\":0.0}}";
    }
}
