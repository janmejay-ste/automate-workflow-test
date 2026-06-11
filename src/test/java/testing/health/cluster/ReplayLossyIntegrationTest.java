package testing.health.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.cluster.ContributorSource;
import utils.health.cluster.replay.ReplayHarness;
import utils.health.cluster.replay.ReplayReportRenderer;
import utils.health.cluster.replay.ReplayRunResult;

/**
 * B4 Phase 3 integration test: proves that the lossy-migration provenance
 * flows correctly from {@code SchemaMigrationService} → {@code ReplayHarness}
 * → {@code ReplayRunResult} → {@code ReplayReportRenderer}.
 *
 * <p>Without this test, B4 Phase 3 would be "looks correct in isolation"
 * but possibly disconnected end-to-end. With it, the contract that a V2
 * snapshot produces a lossy-flagged row with surfaced reasons in the
 * rendered markdown is locked in.</p>
 */
public class ReplayLossyIntegrationTest {

    @Test(groups = {"sanity"})
    public void v2Snapshot_replaysAsLossyRow() {
        String v2Json = "{\"schemaVersion\":2,"
                      + "\"runs\":[{\"runId\":\"r-old\","
                      + "  \"metrics\":{\"overallScore\":75,\"totalPenalty\":25.0,"
                      + "               \"fallbacks\":[],\"testFailures\":[]}}]}";
        ReplayRunResult r = ReplayHarness.replay("legacy.history", v2Json,
                ReplayHarness.DEFAULT_CAPS);

        Assert.assertNotNull(r, "V2 snapshot must produce a replay row, not be silently skipped");
        Assert.assertTrue(r.lossy(),
                "V2-sourced replay rows must carry lossy=true — the contract that prevents "
                + "downstream consumers from misreading 'no signal' as 'no divergence'");
        Assert.assertFalse(r.migrationReasons().isEmpty(),
                "lossy row must carry migrationReasons explaining what was lost");

        // The honest "no signal" indicators: raw event count and over-cap
        // counts are both 0 because V2 has no raw events to reconstruct.
        Assert.assertEquals(r.policyA().totalRawEventCount(), 0,
                "lossy V2 row has no recoverable raw events");
        Assert.assertEquals(r.policyA().totalClustersOverCap(), 0,
                "lossy V2 row has no clusters → no over-cap clusters");

        // The deltas themselves can look NON-ZERO (Policy A applies 0 pts,
        // but the snapshot's old penalty was non-zero from when V2 was
        // written), and that's exactly why the Lossy column exists — to
        // tell readers those deltas are an artifact of the migration, not
        // a real policy comparison. The number we DO trust is rawEventCount.
        Assert.assertNotEquals(r.oldPenalty(), 0.0,
                "this fixture's V2 input had totalPenalty=25; the old penalty must be "
                + "preserved through migration so the lossy row's delta reflects "
                + "today-vs-empty rather than empty-vs-empty");
    }

    @Test(groups = {"sanity"})
    public void v3Snapshot_replaysAsNonLossyRow() {
        // Same V3 contributor-rich fixture used by the original replay tests.
        String v3Json = "{\"schemaVersion\":3,\"score\":70,\"penalty\":30.0,"
                      + "\"scoreContributors\":{"
                      + "\"fallbacks\":{\"source\":\"fallbacks\","
                      + "\"rawTotal\":220.0,\"appliedTotal\":30.0,\"suppressed\":0.0,"
                      + "\"items\":[" + repeatItem(22) + "]}}}";
        ReplayRunResult r = ReplayHarness.replay("v3.live", v3Json,
                ReplayHarness.DEFAULT_CAPS);

        Assert.assertNotNull(r);
        Assert.assertFalse(r.lossy(),
                "V3 snapshot is a native migration — must not be flagged lossy");
        Assert.assertTrue(r.migrationReasons().isEmpty(),
                "native (non-lossy) row has no migration reasons");
        Assert.assertEquals(r.policyA().totalRawEventCount(), 22,
                "V3 raw events flow through unchanged");
        Assert.assertEquals(r.policyA().perSourceClusterCount().get(ContributorSource.FALLBACKS).intValue(), 1);
    }

    @Test(groups = {"sanity"})
    public void v1Snapshot_replayReturnsNull_notSilentBadRow() {
        // V1 is recognized but UNSUPPORTED → migration returns no payload
        // → replay should skip rather than emit a misleading empty row.
        String v1Json = "{\"someOldField\":\"value\"}";
        ReplayRunResult r = ReplayHarness.replay("legacy.v1", v1Json,
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertNull(r,
                "V1 snapshot is UNSUPPORTED — must return null, not a zero-row that pretends "
                + "the snapshot was replayable");
    }

    @Test(groups = {"sanity"})
    public void writeMixedReportToDisk_forVisualReview() throws Exception {
        // Persist a mixed-lossy report so a reader can eyeball the
        // Lossy column + the migration-notes section without re-running.
        List<Map.Entry<String, String>> snapshots = List.of(
            new SimpleEntry<>("v3.amplified", v3MultiAmpFixture()),
            new SimpleEntry<>("v2.history",   v2AggregateFixture()),
            new SimpleEntry<>("v3.clean",     v3CleanFixture()));
        List<ReplayRunResult> runs = ReplayHarness.replayBatch(snapshots,
                ReplayHarness.DEFAULT_CAPS);
        String md = ReplayReportRenderer.render(runs);
        Path out = Paths.get("reports/c4-replay-mixed-lossy-fixture.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        Assert.assertTrue(Files.exists(out));
    }

    @Test(groups = {"sanity"})
    public void mixedBatch_lossyAndNativeRendered_inOneReport() {
        // Three runs: one V3 amplified, one V2 lossy, one V3 clean.
        // Renderer must surface the lossy row + migration reasons.
        List<Map.Entry<String, String>> snapshots = List.of(
            new SimpleEntry<>("v3.amplified", v3MultiAmpFixture()),
            new SimpleEntry<>("v2.history",   v2AggregateFixture()),
            new SimpleEntry<>("v3.clean",     v3CleanFixture()));

        List<ReplayRunResult> runs = ReplayHarness.replayBatch(snapshots,
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertEquals(runs.size(), 3, "all three should produce rows (V2 lossy + V3 ×2)");

        String md = ReplayReportRenderer.render(runs);

        Assert.assertTrue(md.contains("Lossy?"),
                "Lossy? column header must be present in the per-run table");
        Assert.assertTrue(md.contains("⚠ yes"),
                "at least one row in this fixture is lossy; renderer must mark it visibly");
        Assert.assertTrue(md.contains("Lossy migration notes"),
                "lossy rows must surface their migration reasons in a dedicated section");
        Assert.assertTrue(md.contains("v2.history"),
                "the lossy row label must appear in the migration-notes section");
        // The V3 rows should NOT be flagged lossy. Scope the count to the
        // per-run table region only — the "How to read this" guidance
        // section also references the marker as an example, which would
        // double-count if we searched the whole doc.
        int tableStart = md.indexOf("## Per-run breakdown");
        int tableEnd   = md.indexOf("## Distribution stats");
        Assert.assertTrue(tableStart > -1 && tableEnd > tableStart,
                "per-run table must be bounded by its headers");
        String tableSection = md.substring(tableStart, tableEnd);
        int lossyMarkers = tableSection.split("⚠ yes", -1).length - 1;
        Assert.assertEquals(lossyMarkers, 1,
                "only the V2 row should be flagged lossy in the per-run table; V3 rows must "
                + "remain unflagged. Saw " + lossyMarkers + " markers in the table region.");
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private static String repeatItem(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"F\",\"target\":\"button.x\",\"page\":\"/p\","
                    + "\"raw\":10.0,\"applied\":10.0}");
        }
        return sb.toString();
    }

    private String v3MultiAmpFixture() {
        // 3 fallback clusters × 4 events each — the multi-over-cap case.
        StringBuilder items = new StringBuilder();
        for (int c = 0; c < 3; c++) {
            for (int e = 0; e < 4; e++) {
                if (items.length() > 0) items.append(",");
                items.append("{\"name\":\"F").append(c).append("\",")
                     .append("\"target\":\"button.c").append(c).append("\",")
                     .append("\"page\":\"/p\",\"raw\":10.0,\"applied\":10.0}");
            }
        }
        return "{\"schemaVersion\":3,\"score\":70,\"penalty\":30.0,"
             + "\"scoreContributors\":{"
             + "\"fallbacks\":{\"source\":\"fallbacks\",\"rawTotal\":120.0,"
             + "\"appliedTotal\":30.0,\"suppressed\":0.0,\"items\":[" + items + "]}}}";
    }

    private String v2AggregateFixture() {
        return "{\"schemaVersion\":2,"
             + "\"runs\":[{\"runId\":\"r-old\","
             + "  \"metrics\":{\"overallScore\":80,\"totalPenalty\":20.0,"
             + "               \"fallbacks\":[],\"testFailures\":[]}}]}";
    }

    private String v3CleanFixture() {
        return "{\"schemaVersion\":3,\"score\":100,\"penalty\":0.0,"
             + "\"scoreContributors\":{\"fallbacks\":{\"items\":[]}}}";
    }
}
