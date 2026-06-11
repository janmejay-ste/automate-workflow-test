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
import utils.health.cluster.replay.ReplayDistributionStats;
import utils.health.cluster.replay.ReplayHarness;
import utils.health.cluster.replay.ReplayReportRenderer;
import utils.health.cluster.replay.ReplayRunResult;

/**
 * Validates the replay harness on five synthetic snapshots that span the
 * scenarios the user called out as canonical (1 amplified, 1 benign,
 * mixed, near-identical-fingerprint, empty). The synthetic input means
 * Phase 3 can produce a decision-quality report TODAY without waiting for
 * real history to accumulate — and once real history is replayed, the
 * report format is already proven.
 *
 * <p>The fixtures intentionally include the canonical C4 scenario:
 * 22 fallback events from one root cause. That single run alone
 * demonstrates the central design question Policy A vs Policy B exists
 * to answer.</p>
 */
public class ReplayHarnessFixtureTest {

    // ────────────────────────────────────────────────────────────────────
    // Per-run assertions
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void amplifiedFallbackStorm_isDetectedAndAttributed() {
        ReplayRunResult r = ReplayHarness.replay("storm",
                snapshot22FallbacksOneRootCause(),
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertNotNull(r, "snapshot must parse");
        Assert.assertEquals(r.largestDeltaSource(), ContributorSource.FALLBACKS,
                "storm of 22 fallbacks → Fallbacks should be the largest-delta source");
        Assert.assertTrue(r.largestAmplification() >= 22.0 - 0.001,
                "amplification = 22 raw / 1 cluster = 22.0 (actual=" + r.largestAmplification() + ")");
        Assert.assertEquals(r.policyA().perSourceClusterCount().get(ContributorSource.FALLBACKS).intValue(),
                1, "all 22 events fingerprint identically → 1 cluster");
        Assert.assertEquals(r.policyA().perSourceRawEventCount().get(ContributorSource.FALLBACKS).intValue(),
                22, "raw event count preserved in result for amplification visibility");
        // Policy A applies MORE penalty for an amplified storm (preserves
        // the signal) → Policy A's SCORE is LOWER than Policy B's. This is
        // the central design difference. If they flip, one of the policies
        // has been mis-implemented.
        Assert.assertTrue(r.policyAScore() < r.policyBScore(),
                "amplified storm: Policy A (preserves) must score BELOW Policy B (flat) "
                + "because A applies more penalty for the amplification. "
                + "A=" + r.policyAScore() + " B=" + r.policyBScore());
    }

    @Test(groups = {"sanity"})
    public void benignRun_policiesAgree() {
        ReplayRunResult r = ReplayHarness.replay("benign",
                snapshot1FallbackNoAmplification(),
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertNotNull(r);
        Assert.assertEquals(r.policyADelta(), r.policyBDelta(), 0.001,
                "benign run = no amplification → policies must agree");
    }

    @Test(groups = {"sanity"})
    public void mixedRun_perSourceBreakdownIsCorrect() {
        ReplayRunResult r = ReplayHarness.replay("mixed",
                snapshotMixedContributors(),
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertNotNull(r);
        // 3 fallbacks, all distinct → 3 clusters; 2 JS errors, both same context+msg → 1 cluster
        Assert.assertEquals(r.policyA().perSourceClusterCount().get(ContributorSource.FALLBACKS).intValue(), 3);
        Assert.assertEquals(r.policyA().perSourceClusterCount().get(ContributorSource.JS_ERRORS).intValue(), 1);
        Assert.assertEquals(r.policyA().perSourceRawEventCount().get(ContributorSource.JS_ERRORS).intValue(), 2);
    }

    @Test(groups = {"sanity"})
    public void emptyRun_noClustersNoPenalty() {
        ReplayRunResult r = ReplayHarness.replay("clean", snapshotEmpty(),
                ReplayHarness.DEFAULT_CAPS);
        Assert.assertNotNull(r);
        Assert.assertEquals(r.policyA().totalApplied(), 0.0, 0.001);
        Assert.assertEquals(r.policyB().totalApplied(), 0.0, 0.001);
    }

    // ────────────────────────────────────────────────────────────────────
    // Distribution + report assertions
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void replayBatch_producesDistributionStats() {
        List<Map.Entry<String, String>> snapshots = List.of(
            new SimpleEntry<>("storm",  snapshot22FallbacksOneRootCause()),
            new SimpleEntry<>("benign", snapshot1FallbackNoAmplification()),
            new SimpleEntry<>("mixed",  snapshotMixedContributors()),
            new SimpleEntry<>("clean",  snapshotEmpty()),
            new SimpleEntry<>("near",   snapshotNearIdenticalFingerprints()));

        List<ReplayRunResult> runs = ReplayHarness.replayBatch(snapshots, ReplayHarness.DEFAULT_CAPS);
        Assert.assertEquals(runs.size(), 5, "all five fixture snapshots must parse");

        ReplayDistributionStats a = ReplayDistributionStats.forPolicyA(runs);
        ReplayDistributionStats b = ReplayDistributionStats.forPolicyB(runs);

        Assert.assertEquals(a.sampleSize(), 5);
        Assert.assertEquals(b.sampleSize(), 5);
        // Distribution semantics: at least one run must have a non-zero
        // delta or Phase 3 has nothing to evaluate.
        Assert.assertTrue(a.maxAbsDelta() > 0 || b.maxAbsDelta() > 0,
                "fixture corpus must produce SOME delta — otherwise replay tells us nothing");
    }

    @Test(groups = {"sanity"})
    public void reportRenderer_producesValidMarkdown() {
        List<Map.Entry<String, String>> snapshots = List.of(
            new SimpleEntry<>("storm",  snapshot22FallbacksOneRootCause()),
            new SimpleEntry<>("benign", snapshot1FallbackNoAmplification()),
            new SimpleEntry<>("mixed",  snapshotMixedContributors()));

        List<ReplayRunResult> runs = ReplayHarness.replayBatch(snapshots, ReplayHarness.DEFAULT_CAPS);
        String md = ReplayReportRenderer.render(runs);

        // Structural assertions on the report — these lock the contract so
        // future renderer changes can't silently strip a column.
        Assert.assertTrue(md.contains("# C4 Replay Report"),
                "report must have the C4 replay heading");
        Assert.assertTrue(md.contains("## Per-run breakdown"),
                "per-run section must be present");
        Assert.assertTrue(md.contains("## Distribution stats"),
                "distribution stats section must be present");
        Assert.assertTrue(md.contains("Largest Δ source"),
                "the attribution column the user explicitly required must be present");
        Assert.assertTrue(md.contains("Amplification"),
                "amplification column must be present");
        Assert.assertTrue(md.contains("Clusters"),
                "cluster count column must be present");
        Assert.assertTrue(md.contains("Over Cap"),
                "clusters-over-cap column must be present — the structural driver of "
                + "Policy A vs today divergence (cluster count alone is too coarse)");
        Assert.assertTrue(md.contains("Mean Δ") && md.contains("Median Δ")
                       && md.contains("p95 Δ") && md.contains("Max |Δ|"),
                "all four distribution stats (mean/median/p95/max) must be in the table");
        Assert.assertTrue(md.contains("PRESERVE_AMPLIFICATION")
                       && md.contains("FLAT_CLUSTER"),
                "both candidate policies must appear in the distribution table");
        // Storm row should show Fallbacks attribution + 22:1 amplification
        Assert.assertTrue(md.contains("Fallbacks") && md.contains("22:1"),
                "storm row must attribute to Fallbacks with 22:1 amplification — "
                + "this is the concrete answer to the 'why did the score change' question");
    }

    /**
     * Writes the report from the canonical 5-snapshot corpus to
     * {@code reports/c4-replay-fixture.md} so it can be visually inspected
     * during the policy decision. NOT a behavior assertion — this is the
     * artifact the decision-maker reads.
     */
    @Test(groups = {"sanity"})
    public void writeSampleReportToDisk_forDecisionReview() throws Exception {
        List<Map.Entry<String, String>> snapshots = List.of(
            new SimpleEntry<>("storm.fallbacks.22x1",   snapshot22FallbacksOneRootCause()),
            new SimpleEntry<>("multiAmpFallbacks.3x4",  snapshotMultipleAmplifiedClusters()),
            new SimpleEntry<>("benign.fallback.1x1",    snapshot1FallbackNoAmplification()),
            new SimpleEntry<>("mixed.f3.j2",            snapshotMixedContributors()),
            new SimpleEntry<>("nearIdentical.jsErrors", snapshotNearIdenticalFingerprints()),
            new SimpleEntry<>("clean.empty",            snapshotEmpty()));

        List<ReplayRunResult> runs = ReplayHarness.replayBatch(snapshots, ReplayHarness.DEFAULT_CAPS);
        String md = ReplayReportRenderer.render(runs);

        Path out = Paths.get("reports/c4-replay-fixture.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);

        Assert.assertTrue(Files.exists(out), "report file must be created on disk");
        Assert.assertTrue(Files.size(out) > 500,
                "report must be substantial — table + distribution + guidance");
    }

    @Test(groups = {"sanity"})
    public void unparseableSnapshot_isSilentlySkipped() {
        ReplayRunResult r = ReplayHarness.replay("garbage", "{ this is not json", null);
        Assert.assertNull(r, "unparseable snapshot must return null, not throw");

        List<Map.Entry<String, String>> mixed = List.of(
            new SimpleEntry<>("good",    snapshotEmpty()),
            new SimpleEntry<>("garbage", "{ not json"),
            new SimpleEntry<>("good2",   snapshotEmpty()));
        List<ReplayRunResult> runs = ReplayHarness.replayBatch(mixed, ReplayHarness.DEFAULT_CAPS);
        Assert.assertEquals(runs.size(), 2,
                "batch skips unparseable snapshots; good ones still produce rows");
    }

    // ────────────────────────────────────────────────────────────────────
    // Fixture builders — minimal valid snapshots
    // ────────────────────────────────────────────────────────────────────

    /**
     * THE canonical C4 fixture: 22 fallback events on the same selector +
     * page. Flat scoring → 220 raw, capped at 30. Policy A clusters them
     * (1 cluster, raw=220, applyDiminishingReturns(220, 30)) → 30.
     * Policy B clusters them (1 cluster, 1 event worth = 10) → 10.
     */
    private String snapshot22FallbacksOneRootCause() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 22; i++) {
            if (i > 0) items.append(",");
            items.append("{\"name\":\"MenuDropFallback\",")
                 .append("\"target\":\"[data-id=\\\"").append(100 + i)
                 .append("\\\"] .menu_icon-box\",")    // runtime id varies; selector normalizes
                 .append("\"page\":\"connectcloud.appypie.com/customeditor\",")
                 .append("\"raw\":10.0,\"applied\":10.0}");
        }
        return wrapSnapshot(70, 30.0, "", "", items.toString());
    }

    /** 1 fallback, 1 event. No amplification. Policies must agree. */
    private String snapshot1FallbackNoAmplification() {
        return wrapSnapshot(90, 10.0, "", "",
            "{\"name\":\"TextSearchFallback\",\"target\":\"button.continue\","
          + "\"page\":\"/customeditor\",\"raw\":10.0,\"applied\":10.0}");
    }

    /** Mixed: 3 distinct fallbacks + 2 identical JS errors. */
    private String snapshotMixedContributors() {
        String fallbacks = String.join(",",
            "{\"name\":\"F1\",\"target\":\"button.a\",\"page\":\"/p1\",\"raw\":10.0,\"applied\":10.0}",
            "{\"name\":\"F2\",\"target\":\"button.b\",\"page\":\"/p1\",\"raw\":10.0,\"applied\":10.0}",
            "{\"name\":\"F3\",\"target\":\"button.c\",\"page\":\"/p1\",\"raw\":10.0,\"applied\":10.0}");
        String jsErrors = String.join(",",
            "{\"context\":\"ConnectEditor\",\"message\":\"TypeError x\",\"raw\":5.0,\"applied\":5.0}",
            "{\"context\":\"ConnectEditor\",\"message\":\"TypeError x\",\"raw\":5.0,\"applied\":5.0}");
        return wrapSnapshot(60, 40.0, "", jsErrors, fallbacks);
    }

    /** Two near-identical JS errors that MUST NOT cluster (different property names). */
    private String snapshotNearIdenticalFingerprints() {
        String jsErrors = String.join(",",
            "{\"context\":\"ConnectEditor\","
                + "\"message\":\"Cannot read properties of null (reading 'appendChild')\","
                + "\"raw\":5.0,\"applied\":5.0}",
            "{\"context\":\"ConnectEditor\","
                + "\"message\":\"Cannot read properties of null (reading 'addEventListener')\","
                + "\"raw\":5.0,\"applied\":5.0}");
        return wrapSnapshot(85, 10.0, "", jsErrors, "");
    }

    /**
     * Three INDEPENDENT amplified fallback clusters (each with 4 events,
     * raw=40 per cluster). This is where Policy A diverges from today's
     * scoring: today's source-level cap collapses 120 raw → 30 applied;
     * Policy A's per-cluster cap gives each cluster min(40, 30) = 30,
     * summed = 90. Critical fixture — without this, Policy A's Δ from
     * today is always 0 for single-cluster cases under flag-OFF default,
     * making the replay look like Policy A is a no-op.
     */
    private String snapshotMultipleAmplifiedClusters() {
        StringBuilder items = new StringBuilder();
        for (int cluster = 0; cluster < 3; cluster++) {
            for (int evt = 0; evt < 4; evt++) {
                if (items.length() > 0) items.append(",");
                items.append("{\"name\":\"F").append(cluster).append("\",")
                     .append("\"target\":\"button.cluster").append(cluster).append("\",")
                     .append("\"page\":\"/p1\",\"raw\":10.0,\"applied\":10.0}");
            }
        }
        return wrapSnapshot(70, 30.0, "", "", items.toString());
    }

    /** Clean run — no contributors. Policies both produce 0. */
    private String snapshotEmpty() {
        return wrapSnapshot(100, 0.0, "", "", "");
    }

    /** Wrap contributor items into a minimal valid snapshot envelope. */
    private String wrapSnapshot(int score, double penalty, String testFailureItems,
                                 String jsErrorItems, String fallbackItems) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schemaVersion\":3,\"timestamp\":1780000000000,")
          .append("\"score\":").append(score).append(",")
          .append("\"smoothedScore\":").append(score).append(",")
          .append("\"penalty\":").append(penalty).append(",")
          .append("\"status\":\"WARNING\",")
          .append("\"scoreContributors\":{")
          .append("\"testFailures\":{\"source\":\"testFailures\",")
          .append("\"rawTotal\":0.0,\"appliedTotal\":0.0,\"suppressed\":0.0,\"items\":[")
          .append(testFailureItems).append("]},")
          .append("\"jsErrors\":{\"source\":\"jsErrors\",")
          .append("\"rawTotal\":0.0,\"appliedTotal\":0.0,\"suppressed\":0.0,\"items\":[")
          .append(jsErrorItems).append("]},")
          .append("\"fallbacks\":{\"source\":\"fallbacks\",")
          .append("\"rawTotal\":0.0,\"appliedTotal\":0.0,\"suppressed\":0.0,\"items\":[")
          .append(fallbackItems).append("]},")
          .append("\"totalRaw\":").append(penalty).append(",")
          .append("\"totalApplied\":").append(penalty).append(",")
          .append("\"totalSuppressed\":0.0")
          .append("}}");
        return sb.toString();
    }
}
