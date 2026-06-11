package testing.dashboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import utils.DashboardBuilder;

/**
 * D3: behavioral verification for the {@code trendStats} surface in the
 * dashboard. Stages controlled snapshots (outlier / non-outlier /
 * under-sample) and asserts on data-attribute reconciliation in the
 * rendered HTML. Same fixture pattern as
 * {@code ScoreDriversRowFixtureTest} — no browser, sub-second runtime.
 */
public class TrendStatsBlockFixtureTest {

    private static final Logger LOG = LoggerFactory.getLogger(TrendStatsBlockFixtureTest.class);

    private static final Path SNAPSHOT_PATH    = Paths.get("reports/trend/health_snapshot.json");
    private static final Path SNAPSHOT_BACKUP  = Paths.get("reports/trend/health_snapshot.json.trendstats-backup");
    private static final Path ANALYTICS_PATH   = Paths.get("reports/analytics/test_analytics.json");
    private static final Path ANALYTICS_BACKUP = Paths.get("reports/analytics/test_analytics.json.trendstats-backup");
    private static final Path DASHBOARD_PATH   = Paths.get("reports/trend/dashboard.html");
    private static final Path DASHBOARD_BACKUP = Paths.get("reports/trend/dashboard.html.trendstats-backup");

    /** Container attrs the renderer emits. */
    private static final Pattern OUTLIER_RE     = Pattern.compile("data-outlier='(true|false)'");
    private static final Pattern Z_SCORE_RE     = Pattern.compile("data-z-score='(-?\\d+(?:\\.\\d+)?)'");
    private static final Pattern SAMPLE_SIZE_RE = Pattern.compile("data-sample-size='(\\d+)'");
    private static final Pattern MEAN_RE        = Pattern.compile("data-mean='(-?\\d+(?:\\.\\d+)?)'");
    private static final Pattern STDDEV_RE      = Pattern.compile("data-stddev='(-?\\d+(?:\\.\\d+)?)'");

    @BeforeMethod(alwaysRun = true)
    public void backup() throws Exception {
        if (Files.exists(SNAPSHOT_PATH))  Files.copy(SNAPSHOT_PATH,  SNAPSHOT_BACKUP,  StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(ANALYTICS_PATH)) Files.copy(ANALYTICS_PATH, ANALYTICS_BACKUP, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(DASHBOARD_PATH)) Files.copy(DASHBOARD_PATH, DASHBOARD_BACKUP, StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterMethod(alwaysRun = true)
    public void restore() throws Exception {
        if (Files.exists(SNAPSHOT_BACKUP))  Files.move(SNAPSHOT_BACKUP,  SNAPSHOT_PATH,  StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(ANALYTICS_BACKUP)) Files.move(ANALYTICS_BACKUP, ANALYTICS_PATH, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(DASHBOARD_BACKUP)) Files.move(DASHBOARD_BACKUP, DASHBOARD_PATH, StandardCopyOption.REPLACE_EXISTING);
    }

    // ────────────────────────────────────────────────────────────────────
    // Outlier path — most important rendering case
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void outlierTrue_rendersBadgeAndDataAttributes() throws Exception {
        // Stage a snapshot where this run is 2.5σ from the mean of 30 runs.
        writeSnapshot(buildSnapshotWithTrendStats(30, 2.5, 83.4, 4.2, true, 2.0));
        writeAnalytics(buildAnalytics(60, 1));

        DashboardBuilder.write();
        String html = Files.readString(DASHBOARD_PATH);

        // 1. Component marker present
        Assert.assertTrue(html.contains("data-component='trend-stats'"),
                "trendStats block must render its component marker");

        // 2. Outlier flag flows through to the data attribute
        String outlier = single(OUTLIER_RE, html, "data-outlier");
        Assert.assertEquals(outlier, "true",
                "snapshot's outlier=true must reach the rendered data attribute");

        // 3. Numeric attributes reconcile with the staged snapshot values
        Assert.assertEquals(parseDouble(Z_SCORE_RE,  html), 2.5, 0.001);
        Assert.assertEquals(parseDouble(MEAN_RE,     html), 83.4, 0.05);
        Assert.assertEquals(parseDouble(STDDEV_RE,   html), 4.2,  0.05);
        Assert.assertEquals(Integer.parseInt(single(SAMPLE_SIZE_RE, html, "data-sample-size")), 30);

        // 4. The human-facing badge text mentions outlier
        Assert.assertTrue(html.contains("Outlier"),
                "outlier badge must include the word 'Outlier' so a reader knows what to read");

        LOG.info("[TrendStatsBlockFixtureTest] Outlier path verified: z=2.5, sample=30, mean=83.4");
    }

    // ────────────────────────────────────────────────────────────────────
    // Non-outlier path — block still renders so the data attrs are queryable
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void outlierFalse_rendersAsWithinRange() throws Exception {
        writeSnapshot(buildSnapshotWithTrendStats(30, 0.5, 83.4, 4.2, false, 2.0));
        writeAnalytics(buildAnalytics(60, 1));
        DashboardBuilder.write();
        String html = Files.readString(DASHBOARD_PATH);

        Assert.assertTrue(html.contains("data-component='trend-stats'"));
        Assert.assertEquals(single(OUTLIER_RE, html, "data-outlier"), "false");
        Assert.assertTrue(html.contains("Within normal range"),
                "non-outlier rendering must show the 'within range' label");
        Assert.assertFalse(html.contains("⚠ Outlier"),
                "non-outlier rendering must NOT emit the outlier warning glyph");
    }

    // ────────────────────────────────────────────────────────────────────
    // Under-sample path — block suppressed (no meaningful judgement yet)
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void sampleBelowMinimum_blockIsSuppressed() throws Exception {
        // 4 samples is below the C2 MIN_SAMPLE_FOR_OUTLIER threshold (5).
        // Renderer must suppress entirely — outlier judgement on n=4 is noise.
        writeSnapshot(buildSnapshotWithTrendStats(4, 2.5, 80.0, 4.0, true, 2.0));
        writeAnalytics(buildAnalytics(60, 1));
        DashboardBuilder.write();
        String html = Files.readString(DASHBOARD_PATH);

        Assert.assertFalse(html.contains("data-component='trend-stats'"),
                "trendStats block must not render when sampleSize < MIN_SAMPLE_FOR_OUTLIER. "
                + "Outlier judgement on a tiny sample would be misleading.");
    }

    // ────────────────────────────────────────────────────────────────────
    // Missing-block path — block absent, dashboard still renders
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void missingTrendStats_dashboardStillRendersCleanly() throws Exception {
        // Snapshot without trendStats at all — earlier-format snapshots
        // (pre-C2) didn't carry this block.
        writeSnapshot(buildSnapshotMissingTrendStats());
        writeAnalytics(buildAnalytics(100, 0));
        DashboardBuilder.write();
        String html = Files.readString(DASHBOARD_PATH);

        Assert.assertTrue(Files.exists(DASHBOARD_PATH),
                "dashboard must still render even when trendStats is missing");
        Assert.assertFalse(html.contains("data-component='trend-stats'"),
                "trendStats block must be absent (not rendered) when the snapshot doesn't carry it");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void writeSnapshot(String json) throws Exception {
        Files.createDirectories(SNAPSHOT_PATH.getParent());
        Files.writeString(SNAPSHOT_PATH, json);
    }

    private static void writeAnalytics(String json) throws Exception {
        Files.createDirectories(ANALYTICS_PATH.getParent());
        Files.writeString(ANALYTICS_PATH, json);
    }

    private String buildSnapshotWithTrendStats(int sampleSize, double z, double mean,
                                                 double stdDev, boolean outlier, double threshold) {
        return "{"
             + "\"schemaVersion\":3,\"timestamp\":1780000000000,"
             + "\"score\":60,\"smoothedScore\":60,\"penalty\":40.0,\"status\":\"WARNING\","
             + "\"scoreContributors\":{\"totalRaw\":40.0,\"totalApplied\":40.0,\"totalSuppressed\":0.0},"
             + "\"trendStats\":{"
                + "\"sampleSize\":" + sampleSize + ","
                + "\"currentZScore\":" + z + ","
                + "\"mean\":" + mean + ","
                + "\"stdDev\":" + stdDev + ","
                + "\"outlier\":" + outlier + ","
                + "\"outlierThreshold\":" + threshold + ","
                + "\"window\":30"
             + "},"
             + "\"semantic\":{\"layeredScores\":{\"productHealth\":60,\"frameworkHealth\":100,"
                + "\"telemetryConfidence\":100,\"businessOutcome\":null},"
                + "\"clusters\":[],\"reliabilities\":[],\"businessOutcomes\":{"
                + "\"success\":0,\"partial\":0,\"failed\":0,\"aborted\":0,\"transactions\":[]},"
                + "\"urlValidation\":{\"pagesScanned\":0,\"urlsValidated\":0,\"findingsCount\":0,\"findings\":[]},"
                + "\"telemetry\":{\"unknown\":0,\"valid\":0},\"errorCountByDomain\":{},"
                + "\"amplification\":{\"totalClusters\":0,\"byClassification\":{"
                + "\"RETRY_STORM\":0,\"BOOT_LOOP\":0,\"EMBEDDED_REPEAT\":0,"
                + "\"NORMAL_REPEAT\":0,\"NONE\":0}}}"
             + "}";
    }

    private String buildSnapshotMissingTrendStats() {
        return "{"
             + "\"schemaVersion\":3,\"timestamp\":1780000000000,"
             + "\"score\":100,\"smoothedScore\":100,\"penalty\":0.0,\"status\":\"EXCELLENT\","
             + "\"scoreContributors\":{\"totalRaw\":0.0,\"totalApplied\":0.0,\"totalSuppressed\":0.0},"
             + "\"semantic\":{\"layeredScores\":{\"productHealth\":100,\"frameworkHealth\":100,"
                + "\"telemetryConfidence\":100,\"businessOutcome\":null},"
                + "\"clusters\":[],\"reliabilities\":[],\"businessOutcomes\":{"
                + "\"success\":0,\"partial\":0,\"failed\":0,\"aborted\":0,\"transactions\":[]},"
                + "\"urlValidation\":{\"pagesScanned\":0,\"urlsValidated\":0,\"findingsCount\":0,\"findings\":[]},"
                + "\"telemetry\":{\"unknown\":0,\"valid\":0},\"errorCountByDomain\":{},"
                + "\"amplification\":{\"totalClusters\":0,\"byClassification\":{"
                + "\"RETRY_STORM\":0,\"BOOT_LOOP\":0,\"EMBEDDED_REPEAT\":0,"
                + "\"NORMAL_REPEAT\":0,\"NONE\":0}}}"
             + "}";
    }

    private String buildAnalytics(int overallScore, int failedCount) {
        boolean hasFail = failedCount > 0;
        return String.join("\n", List.of(
                "{",
                "  \"metadata\": {\"environment\": \"QA\", \"suite\": \"FIXTURE\",",
                "    \"gitBranch\": \"fixture\", \"gitHash\": \"fixture\", \"timestamp\": 1780000000000},",
                "  \"overallScore\": " + overallScore + ",",
                "  \"smokePassRate\": 1.0,",
                "  \"regressionPassRate\": 1.0,",
                "  \"criticalProductBugs\": 0,",
                "  \"totalLocatorSamples\": 0,",
                "  \"totalDurationMs\": 1000,",
                "  \"totalTestCount\": " + Math.max(1, failedCount) + ",",
                "  \"failedCount\": " + failedCount + ",",
                "  \"failureTypeCounts\": {},",
                "  \"locators\": {},",
                "  \"jsErrors\": {\"auditMode\": false, \"product\": []},",
                "  \"tests\": [" + (hasFail
                        ? "{\"category\":\"FIXTURE\",\"login\":\"Guest\",\"feature\":\"Fixture\","
                          + "\"class\":\"FixtureTest\",\"method\":\"fakeFail\","
                          + "\"status\":\"FAIL\",\"duration\":100}"
                        : "") + "],",
                "  \"slowPages\": [],",
                "  \"testFailures\": " + (hasFail
                        ? "[{\"test\":\"FixtureTest.fakeFail\",\"reason\":\"fixture\"}]"
                        : "[]"),
                "}"
        ));
    }

    private static String single(Pattern p, String html, String label) {
        Matcher m = p.matcher(html);
        Assert.assertTrue(m.find(), "expected at least one match for " + label);
        return m.group(1);
    }

    private static double parseDouble(Pattern p, String html) {
        Matcher m = p.matcher(html);
        Assert.assertTrue(m.find(), "expected numeric attribute match");
        return Double.parseDouble(m.group(1));
    }
}
