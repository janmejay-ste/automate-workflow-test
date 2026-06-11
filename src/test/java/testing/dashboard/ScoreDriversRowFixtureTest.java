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
 * Fixture-based verification for the Score Drivers row in the dashboard
 * Triage Box. Addresses the gap identified in the previous validation cycle:
 * a normally-passing smoke run produces {@code status: READY} which suppresses
 * the entire Triage Box, leaving the score-drivers row code path un-exercised.
 *
 * <p>Rather than waiting for a slow failing fixture (~15 min ExploreMenu run),
 * this test stages a controlled snapshot with non-READY status + non-zero
 * contributors, calls {@link DashboardBuilder#write()}, and asserts on the
 * rendered HTML. Fast (<5s) and deterministic.</p>
 *
 * <p>Does NOT extend BaseTest — no browser needed. Pure file I/O + Java
 * invocation of DashboardBuilder.</p>
 *
 * <h3>Why this style</h3>
 * From the validation cycle notes:
 * <blockquote>
 *   "For UI/reporting components, fixture-based verification is usually
 *    faster and more reliable than waiting for a real failing suite."
 * </blockquote>
 */
public class ScoreDriversRowFixtureTest {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreDriversRowFixtureTest.class);

    private static final Path SNAPSHOT_PATH    = Paths.get("reports/trend/health_snapshot.json");
    private static final Path SNAPSHOT_BACKUP  = Paths.get("reports/trend/health_snapshot.json.fixture-backup");
    private static final Path ANALYTICS_PATH   = Paths.get("reports/analytics/test_analytics.json");
    private static final Path ANALYTICS_BACKUP = Paths.get("reports/analytics/test_analytics.json.fixture-backup");
    private static final Path DASHBOARD_PATH   = Paths.get("reports/trend/dashboard.html");
    private static final Path DASHBOARD_BACKUP = Paths.get("reports/trend/dashboard.html.fixture-backup");

    @BeforeMethod(alwaysRun = true)
    public void backupRealArtifacts() throws Exception {
        // Save real snapshot + analytics + dashboard so production state isn't
        // corrupted by the test's fixture-injection. AfterMethod restores them.
        if (Files.exists(SNAPSHOT_PATH))  Files.copy(SNAPSHOT_PATH,  SNAPSHOT_BACKUP,  StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(ANALYTICS_PATH)) Files.copy(ANALYTICS_PATH, ANALYTICS_BACKUP, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(DASHBOARD_PATH)) Files.copy(DASHBOARD_PATH, DASHBOARD_BACKUP, StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterMethod(alwaysRun = true)
    public void restoreRealArtifacts() throws Exception {
        // Always restore — never leave fixture data in the production artifacts
        if (Files.exists(SNAPSHOT_BACKUP))  Files.move(SNAPSHOT_BACKUP,  SNAPSHOT_PATH,  StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(ANALYTICS_BACKUP)) Files.move(ANALYTICS_BACKUP, ANALYTICS_PATH, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(DASHBOARD_BACKUP)) Files.move(DASHBOARD_BACKUP, DASHBOARD_PATH, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The core verification: stage a snapshot with non-READY status + non-zero
     * contributors, regenerate the dashboard, assert the score-drivers row
     * rendered with the expected per-contributor values.
     */
    @Test(groups = {"sanity"})
    public void scoreDriversRow_rendersWithExpectedContent() throws Exception {
        // Fixture: penalty = 15 (1 test failure) + 20 (2 fallbacks @ 10 each)
        //                  + 5 (5 JS errors @ 1 each) = 40 applied
        // Score = 100 - 40 = 60 → WARNING band → triage box renders.
        //
        // DashboardBuilder.write() derives status from test_analytics.json
        // (overallScore + smokePassRate + ...), NOT from health_snapshot.json,
        // so both fixtures must be staged for the triage box to render.
        String snap = buildFixtureSnapshot();
        Files.createDirectories(SNAPSHOT_PATH.getParent());
        Files.writeString(SNAPSHOT_PATH, snap);

        String analytics = buildFixtureAnalytics(60, 1);
        Files.createDirectories(ANALYTICS_PATH.getParent());
        Files.writeString(ANALYTICS_PATH, analytics);

        LOG.info("[ScoreDriversRowFixtureTest] Fixture written: snapshot={}B analytics={}B",
                snap.length(), analytics.length());

        // Re-render dashboard from the fixture
        DashboardBuilder.write();
        Assert.assertTrue(Files.exists(DASHBOARD_PATH),
                "Dashboard HTML should be regenerated from the fixture snapshot");

        String html = Files.readString(DASHBOARD_PATH);

        // 1. Triage Box must render (status != READY)
        Assert.assertTrue(html.contains("30-Second Triage"),
                "Triage Box should render when status != READY");

        // 2. Score Drivers header present (the addition under verification)
        Assert.assertTrue(html.contains("Score drivers"),
                "Score Drivers header should render inside Triage Box");

        // 3. Each contributor's item count appears in the row
        // The fixture has: 1 test failure, 2 fallbacks, 5 JS error clusters
        Assert.assertTrue(html.contains(">1</strong>&nbsp;test failure(s)")
                       || html.contains(">1</strong> test failure(s)"),
                "Test failure count (1) should appear in the score-drivers row");
        Assert.assertTrue(html.contains(">2</strong>&nbsp;locator fallback(s)")
                       || html.contains(">2</strong> locator fallback(s)"),
                "Fallback count (2) should appear in the score-drivers row");
        Assert.assertTrue(html.contains(">5</strong>&nbsp;JS error cluster(s)")
                       || html.contains(">5</strong> JS error cluster(s)"),
                "JS error cluster count (5) should appear in the score-drivers row");

        // 4. Each contributor's applied-penalty value appears
        Assert.assertTrue(html.contains("(15 pts)"),
                "Test failure penalty (15 pts) should appear");
        Assert.assertTrue(html.contains("(20 pts)"),
                "Fallback penalty (20 pts) should appear");
        Assert.assertTrue(html.contains("(5 pts)"),
                "JS error penalty (5 pts) should appear");

        // 5. Total applied
        Assert.assertTrue(html.contains("= 40 pts applied"),
                "Total applied (40 pts) should appear at end of row");

        // 6. Penalty reconciliation — the sum of per-bucket rendered pts must
        //    equal the rendered total. Catches silent drift if someone later
        //    changes a bucket's penalty weight (e.g. fallbackPenalty=12) without
        //    keeping the total computation in sync.
        double summedBuckets = sumRenderedBucketPts(html);
        double renderedTotal = parseRenderedTotalPts(html);
        Assert.assertEquals(summedBuckets, renderedTotal, 0.001,
                "Sum of per-bucket data-bucket-pts must equal data-penalty-total. "
                + "Drift here means the rendering layer is computing the breakdown and "
                + "the total from different sources.");

        // 7. Items reconciliation — Σ(per-bucket items) == container total-items.
        //    Forward-compat for C4: when raw events get clustered, the same
        //    pattern will validate Σ(per-bucket clusters) == total clusters and
        //    Σ(per-bucket raw-events) == total raw-events, exposing
        //    amplification ratios per bucket without losing reconciliation.
        int summedItems  = sumRenderedBucketItems(html);
        int renderedItems = parseRenderedTotalItems(html);
        Assert.assertEquals(summedItems, renderedItems,
                "Sum of per-bucket data-bucket-items must equal data-total-items. "
                + "Catches divergence between display loop and total-items computation.");

        LOG.info("[ScoreDriversRowFixtureTest] Reconciliation passed: "
                + "Σ(pts)={} == total={} | Σ(items)={} == total-items={}",
                summedBuckets, renderedTotal, summedItems, renderedItems);

        LOG.info("[ScoreDriversRowFixtureTest] All Score-Drivers assertions passed.");
    }

    /**
     * Negative verification: a fixture with {@code status: READY} (penalty=0)
     * must NOT render the Triage Box at all — and therefore not the score-drivers
     * row either. Confirms the gating works as intended (no false-positive renders).
     */
    @Test(groups = {"sanity"})
    public void scoreDriversRow_isHiddenOnReadyRuns() throws Exception {
        String fixture = buildReadyFixtureSnapshot();
        Files.createDirectories(SNAPSHOT_PATH.getParent());
        Files.writeString(SNAPSHOT_PATH, fixture);

        // READY analytics: overallScore=100, no failures
        String analytics = buildFixtureAnalytics(100, 0);
        Files.createDirectories(ANALYTICS_PATH.getParent());
        Files.writeString(ANALYTICS_PATH, analytics);

        DashboardBuilder.write();
        String html = Files.readString(DASHBOARD_PATH);

        Assert.assertFalse(html.contains("30-Second Triage"),
                "Triage Box should NOT render when status is READY");
        Assert.assertFalse(html.contains("Score drivers"),
                "Score Drivers row should NOT render when triage box is hidden");

        LOG.info("[ScoreDriversRowFixtureTest] READY-status gate verified — triage hidden.");
    }

    // ── Reconciliation helpers ──────────────────────────────────────────────
    //
    // The reconciliation pulls values from machine-readable data attributes
    // (`data-bucket-pts`, `data-penalty-total`) rather than the human-facing
    // text ("(15 pts)", "= 40 pts applied"). The visible copy is allowed to
    // change without breaking the contract; what must stay stable is the
    // attribute interface between the renderer and the reconciliation suite.

    /** Per-bucket: <span data-bucket-pts='15' ...>. */
    private static final Pattern BUCKET_PTS_RE =
            Pattern.compile("data-bucket-pts='(\\d+(?:\\.\\d+)?)'");

    /** Per-bucket: <span data-bucket-items='5' ...>. */
    private static final Pattern BUCKET_ITEMS_RE =
            Pattern.compile("data-bucket-items='(\\d+)'");

    /** Container: <div data-penalty-total='40' ...>. */
    private static final Pattern TOTAL_PTS_RE =
            Pattern.compile("data-penalty-total='(\\d+(?:\\.\\d+)?)'");

    /** Container: <div data-total-items='8' ...>. */
    private static final Pattern TOTAL_ITEMS_RE =
            Pattern.compile("data-total-items='(\\d+)'");

    private double sumRenderedBucketPts(String html) {
        Matcher m = BUCKET_PTS_RE.matcher(html);
        double sum = 0.0;
        int matchCount = 0;
        while (m.find()) {
            sum += Double.parseDouble(m.group(1));
            matchCount++;
        }
        Assert.assertTrue(matchCount >= 1,
                "Expected at least 1 data-bucket-pts attribute in rendered HTML");
        return sum;
    }

    private double parseRenderedTotalPts(String html) {
        Matcher m = TOTAL_PTS_RE.matcher(html);
        Assert.assertTrue(m.find(),
                "Expected exactly one data-penalty-total attribute in rendered HTML");
        return Double.parseDouble(m.group(1));
    }

    private int sumRenderedBucketItems(String html) {
        Matcher m = BUCKET_ITEMS_RE.matcher(html);
        int sum = 0;
        int matchCount = 0;
        while (m.find()) {
            sum += Integer.parseInt(m.group(1));
            matchCount++;
        }
        Assert.assertTrue(matchCount >= 1,
                "Expected at least 1 data-bucket-items attribute in rendered HTML");
        return sum;
    }

    private int parseRenderedTotalItems(String html) {
        Matcher m = TOTAL_ITEMS_RE.matcher(html);
        Assert.assertTrue(m.find(),
                "Expected exactly one data-total-items attribute in rendered HTML");
        return Integer.parseInt(m.group(1));
    }

    // ── Fixture builders ────────────────────────────────────────────────────

    /**
     * Minimal {@code test_analytics.json} fixture that exercises the
     * status-derivation path inside {@link DashboardBuilder#write()}.
     * RiskInterpreter uses overallScore + smokePassRate + criticalProductBugs;
     * the remaining fields are required by buildHtml() not to NPE on missing
     * sub-objects.
     */
    private String buildFixtureAnalytics(int overallScore, int failedCount) {
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
                        ? "{\"category\": \"FIXTURE\", \"login\": \"Guest\", \"feature\": \"Fixture\","
                                + " \"class\": \"FixtureTest\", \"method\": \"fakeFail\","
                                + " \"status\": \"FAIL\", \"duration\": 100}"
                        : "") + "],",
                "  \"slowPages\": [],",
                "  \"testFailures\": " + (hasFail
                        ? "[{\"test\": \"FixtureTest.fakeFail\", \"reason\": \"fixture\"}]"
                        : "[]"),
                "}"
        ));
    }


    /** Snapshot for the non-READY test: score=60, 3 contributor buckets populated. */
    private String buildFixtureSnapshot() {
        return String.join("\n", List.of(
                "{",
                "  \"schemaVersion\": 3,",
                "  \"timestamp\": 1780000000000,",
                "  \"score\": 60,",
                "  \"smoothedScore\": 60,",
                "  \"penalty\": 40.0,",
                "  \"status\": \"WARNING\",",
                "  \"testFailures\": [{\"test\": \"FixtureTest.fakeFail\", \"reason\": \"fixture\", \"stackTrace\": \"\"}],",
                "  \"scoreContributors\": {",
                "    \"testFailures\": {",
                "      \"source\": \"testFailures\",",
                "      \"rawTotal\": 15.0,",
                "      \"appliedTotal\": 15.0,",
                "      \"suppressed\": 0.0,",
                "      \"items\": [",
                "        {\"test\": \"FixtureTest.fakeFail\", \"raw\": 15.0, \"applied\": 15.0}",
                "      ]",
                "    },",
                "    \"fallbacks\": {",
                "      \"source\": \"fallbacks\",",
                "      \"rawTotal\": 20.0,",
                "      \"appliedTotal\": 20.0,",
                "      \"suppressed\": 0.0,",
                "      \"items\": [",
                "        {\"name\": \"FixtureFallback1\", \"target\": \"selectorA\", \"raw\": 10.0, \"applied\": 10.0},",
                "        {\"name\": \"FixtureFallback2\", \"target\": \"selectorB\", \"raw\": 10.0, \"applied\": 10.0}",
                "      ]",
                "    },",
                "    \"jsErrors\": {",
                "      \"source\": \"jsErrors\",",
                "      \"rawTotal\": 5.0,",
                "      \"appliedTotal\": 5.0,",
                "      \"suppressed\": 0.0,",
                "      \"items\": [",
                "        {\"context\": \"ctx1\", \"message\": \"err1\", \"raw\": 1.0, \"applied\": 1.0},",
                "        {\"context\": \"ctx2\", \"message\": \"err2\", \"raw\": 1.0, \"applied\": 1.0},",
                "        {\"context\": \"ctx3\", \"message\": \"err3\", \"raw\": 1.0, \"applied\": 1.0},",
                "        {\"context\": \"ctx4\", \"message\": \"err4\", \"raw\": 1.0, \"applied\": 1.0},",
                "        {\"context\": \"ctx5\", \"message\": \"err5\", \"raw\": 1.0, \"applied\": 1.0}",
                "      ]",
                "    },",
                "    \"totalRaw\": 40.0,",
                "    \"totalApplied\": 40.0,",
                "    \"totalSuppressed\": 0.0",
                "  },",
                "  \"release\": {\"status\": \"WARNING\", \"wouldShip\": true, \"blockers\": [], \"warnings\": []},",
                "  \"enforcement\": {\"mode\": \"advisory\", \"wouldHaveBlocked\": false,",
                "    \"actuallyBlocked\": false, \"killSwitchActive\": false, \"violations\": []},",
                "  \"semantic\": {\"layeredScores\": {\"productHealth\": 60, \"frameworkHealth\": 100,",
                "    \"telemetryConfidence\": 100, \"businessOutcome\": null},",
                "    \"clusters\": [], \"reliabilities\": [], \"businessOutcomes\": {",
                "      \"success\": 0, \"partial\": 0, \"failed\": 0, \"aborted\": 0, \"transactions\": []},",
                "    \"urlValidation\": {\"pagesScanned\": 0, \"urlsValidated\": 0, \"findingsCount\": 0, \"findings\": []},",
                "    \"telemetry\": {\"unknown\": 0, \"valid\": 0}, \"errorCountByDomain\": {},",
                "    \"amplification\": {\"totalClusters\": 0, \"byClassification\": {",
                "      \"RETRY_STORM\": 0, \"BOOT_LOOP\": 0, \"EMBEDDED_REPEAT\": 0,",
                "      \"NORMAL_REPEAT\": 0, \"NONE\": 0}}}",
                "}"
        ));
    }

    /** Snapshot for the READY test: score=100, no contributors, triage should not render. */
    private String buildReadyFixtureSnapshot() {
        return String.join("\n", List.of(
                "{",
                "  \"schemaVersion\": 3,",
                "  \"timestamp\": 1780000000000,",
                "  \"score\": 100,",
                "  \"smoothedScore\": 100,",
                "  \"penalty\": 0.0,",
                "  \"status\": \"EXCELLENT\",",
                "  \"testFailures\": [],",
                "  \"scoreContributors\": {",
                "    \"totalRaw\": 0.0, \"totalApplied\": 0.0, \"totalSuppressed\": 0.0",
                "  },",
                "  \"release\": {\"status\": \"READY\", \"wouldShip\": true, \"blockers\": [], \"warnings\": []},",
                "  \"enforcement\": {\"mode\": \"advisory\", \"wouldHaveBlocked\": false,",
                "    \"actuallyBlocked\": false, \"killSwitchActive\": false, \"violations\": []},",
                "  \"semantic\": {\"layeredScores\": {\"productHealth\": 100, \"frameworkHealth\": 100,",
                "    \"telemetryConfidence\": 100, \"businessOutcome\": null},",
                "    \"clusters\": [], \"reliabilities\": [], \"businessOutcomes\": {",
                "      \"success\": 0, \"partial\": 0, \"failed\": 0, \"aborted\": 0, \"transactions\": []},",
                "    \"urlValidation\": {\"pagesScanned\": 0, \"urlsValidated\": 0, \"findingsCount\": 0, \"findings\": []},",
                "    \"telemetry\": {\"unknown\": 0, \"valid\": 0}, \"errorCountByDomain\": {},",
                "    \"amplification\": {\"totalClusters\": 0, \"byClassification\": {",
                "      \"RETRY_STORM\": 0, \"BOOT_LOOP\": 0, \"EMBEDDED_REPEAT\": 0,",
                "      \"NORMAL_REPEAT\": 0, \"NONE\": 0}}}",
                "}"
        ));
    }
}
