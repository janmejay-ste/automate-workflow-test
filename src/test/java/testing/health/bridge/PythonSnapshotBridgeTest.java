package testing.health.bridge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.cluster.replay.ReplayHarness;
import utils.health.cluster.replay.ReplayRunResult;
import utils.health.schema.MigrationOutcome;
import utils.health.schema.MigrationResult;
import utils.health.schema.SchemaDetector;
import utils.health.schema.SchemaMigrationService;
import utils.health.schema.SnapshotSchemaVersion;

/**
 * Phase 6 of the Python migration plan: prove the snapshot contract
 * works end-to-end by loading an actual {@code python-health-snapshot.json}
 * (produced by the Python/Playwright stack at
 * {@code ../automate-workflow-py/}) and feeding it through every Java
 * consumer that the hybrid pipeline depends on:
 *
 * <ul>
 *   <li>{@link SchemaDetector} — must classify as
 *       {@link SnapshotSchemaVersion#V3_CONTRIBUTOR_RICH}</li>
 *   <li>{@link SchemaMigrationService} — must produce a
 *       {@link MigrationOutcome#SUCCESS_NATIVE} result (no lossy migration
 *       needed because Python already writes v3)</li>
 *   <li>{@link ReplayHarness} — must process the migrated snapshot and
 *       produce a {@link ReplayRunResult}</li>
 * </ul>
 *
 * <p>The fixture file ({@code src/test/resources/python-health-snapshot-fixture.json})
 * is a copy of the actual output written by the Python stack's
 * {@code utils/snapshot_writer.py}. If the Python writer's output shape
 * ever diverges from what these Java consumers expect, this test fails
 * — making the cross-language contract a compile-time-anchored regression
 * check instead of a verbal agreement.</p>
 *
 * <p><b>What this test does NOT do:</b></p>
 * <ul>
 *   <li>It does NOT modify any Java production code. Phase 6 of the
 *       migration plan is verification, not implementation.</li>
 *   <li>It does NOT exercise the DashboardBuilder merge step, because
 *       no such merge step exists in production yet. Per the plan, that
 *       integration is deferred until this contract test is green.</li>
 * </ul>
 */
public class PythonSnapshotBridgeTest {

    private static final Logger LOG = LoggerFactory.getLogger(PythonSnapshotBridgeTest.class);

    private static final String FIXTURE_RESOURCE = "python-health-snapshot-fixture.json";

    /**
     * Number of test records the Python stack produced in the captured run.
     * Update this constant + re-copy the fixture file whenever a new test
     * class is migrated. The fixture should always reflect the current
     * Python-suite output so this test catches schema drift, not
     * test-count drift.
     *
     * <p>Current fixture: 18 tests, all baseline-smoke cohort (unattended-
     * headless run; auth test excluded due to R-1 captcha constraint).</p>
     */
    private static final int EXPECTED_TEST_COUNT = 18;

    // ────────────────────────────────────────────────────────────────────
    // Schema detection
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void pythonSnapshot_classifiesAsV3() throws Exception {
        String json = loadFixture();
        SnapshotSchemaVersion v = SchemaDetector.detect(json);
        LOG.info("[bridge] SchemaDetector → {}", v);
        Assert.assertEquals(v, SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH,
                "Python snapshot writer MUST produce v3 schema. If this fails, "
                + "the Python utils/snapshot_writer.py has diverged from the cross-"
                + "language contract documented in docs/snapshot-contract.md.");
    }

    @Test(groups = {"sanity"})
    public void pythonSnapshot_carriesSourceAttribution() throws Exception {
        JSONObject root = (JSONObject) new JSONParser().parse(loadFixture());
        Object source = root.get("source");
        Assert.assertEquals(source, "python",
                "Python snapshots MUST carry source='python' for downstream merge "
                + "logic to distinguish origin. Java snapshots omit this field.");
    }

    @Test(groups = {"sanity"})
    public void pythonSnapshot_stampsSchemaVersion3() throws Exception {
        JSONObject root = (JSONObject) new JSONParser().parse(loadFixture());
        Object stamped = root.get("schemaVersion");
        Assert.assertTrue(stamped instanceof Number,
                "schemaVersion field MUST be a number");
        Assert.assertEquals(((Number) stamped).intValue(), 3,
                "Python snapshot's schemaVersion must equal Java SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH.code() = 3");
    }

    // ────────────────────────────────────────────────────────────────────
    // Migration service
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void pythonSnapshot_migrationOutcomeIsNative() throws Exception {
        String json = loadFixture();
        MigrationResult result = SchemaMigrationService.migrateToCurrent(json);
        LOG.info("[bridge] MigrationService → outcome={}, source={}, lossy={}",
                result.outcome(), result.sourceVersion(), result.lossy());

        Assert.assertEquals(result.outcome(), MigrationOutcome.SUCCESS_NATIVE,
                "Python writes v3 directly, so migration must be SUCCESS_NATIVE "
                + "(no lossy transformation required). A non-NATIVE outcome here "
                + "would mean Python's output isn't actually v3-shape despite "
                + "the schemaVersion stamp.");
        Assert.assertFalse(result.lossy(),
                "SUCCESS_NATIVE results are never lossy — value-type invariant.");
        Assert.assertEquals(result.sourceVersion(), SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH);
        Assert.assertNotNull(result.migrated(),
                "Successful migration must carry the migrated JSON object.");
    }

    // ────────────────────────────────────────────────────────────────────
    // Test records preservation
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void pythonSnapshot_testRecordsAreReadable() throws Exception {
        JSONObject root = (JSONObject) new JSONParser().parse(loadFixture());
        Object tests = root.get("tests");
        Assert.assertTrue(tests instanceof JSONArray,
                "Python snapshot's tests field must be a JSONArray");
        JSONArray testsArr = (JSONArray) tests;
        Assert.assertEquals(testsArr.size(), EXPECTED_TEST_COUNT,
                "Fixture was captured with " + EXPECTED_TEST_COUNT + " test records "
                + "(1 AuthenticatedSanityJourney + 10 PricingSmokeTest). "
                + "If this count differs, the fixture is stale — re-copy from "
                + "../automate-workflow-py/reports/trend/python-health-snapshot.json");

        // Verify each test record has the fields the Java analytics pipeline expects.
        for (Object o : testsArr) {
            Assert.assertTrue(o instanceof JSONObject, "Each test record must be a JSON object");
            JSONObject t = (JSONObject) o;
            for (String required : new String[]{"category", "login", "feature", "class",
                                                  "method", "status", "duration"}) {
                Assert.assertNotNull(t.get(required),
                        "Test record is missing required field '" + required
                        + "': " + t.toJSONString());
            }
        }
    }

    @Test(groups = {"sanity"})
    public void pythonSnapshot_cohortFieldIsPreservedThroughMigration() throws Exception {
        // Downstream-consumer verification: the cohort field added in
        // Phase 0 of the Python migration must survive the Java
        // schema-migration pipeline. If it were silently dropped by
        // IdentityMigratorV3 (or any future migrator), future report
        // generation that depended on cohort would silently lose data.
        //
        // This is the "additive field preserved end-to-end" contract
        // the user-requested correction explicitly called out.
        String json = loadFixture();
        MigrationResult result = SchemaMigrationService.migrateToCurrent(json);
        Assert.assertNotNull(result.migrated(),
                "Migration must produce a migrated payload to check field preservation");

        JSONArray testsAfter = (JSONArray) result.migrated().get("tests");
        Assert.assertNotNull(testsAfter, "migrated 'tests' array must exist");
        Assert.assertEquals(testsAfter.size(), EXPECTED_TEST_COUNT,
                "Migration must preserve all test records");

        int withCohort = 0;
        for (Object o : testsAfter) {
            JSONObject t = (JSONObject) o;
            if (t.get("cohort") != null) withCohort++;
        }
        Assert.assertEquals(withCohort, EXPECTED_TEST_COUNT,
                "EVERY test record must carry a non-null cohort field through the "
                + "migration pipeline. If this fails, IdentityMigratorV3 or one of "
                + "the JSON merge steps is dropping additive fields — which would "
                + "make any future cohort-aware report generation silently broken.");
    }

    @Test(groups = {"sanity"})
    public void pythonSnapshot_allRecordedTestsPassed() throws Exception {
        JSONObject root = (JSONObject) new JSONParser().parse(loadFixture());
        JSONArray testsArr = (JSONArray) root.get("tests");
        int passCount = 0;
        for (Object o : testsArr) {
            if ("PASS".equals(((JSONObject) o).get("status"))) passCount++;
        }
        Assert.assertEquals(passCount, EXPECTED_TEST_COUNT,
                "Fixture was captured from a clean Python run; expected all "
                + EXPECTED_TEST_COUNT + " tests to be PASS.");
    }

    // ────────────────────────────────────────────────────────────────────
    // Replay harness — proves the C4 pipeline can consume Python output
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void pythonSnapshot_replayHarnessProducesResult() throws Exception {
        String json = loadFixture();
        ReplayRunResult r = ReplayHarness.replay(
                "python-fixture", json, ReplayHarness.DEFAULT_CAPS);

        Assert.assertNotNull(r,
                "ReplayHarness must produce a result from a valid Python v3 snapshot. "
                + "A null result would indicate the harness's schema migration step "
                + "rejected the input.");
        Assert.assertFalse(r.lossy(),
                "Python snapshots replay natively (no lossy migration). lossy=true "
                + "would indicate the harness routed through LossyV2ToV3Migrator, "
                + "which means SchemaDetector mis-classified the input.");

        // The Python snapshot has empty scoreContributors.items[] arrays by design
        // (Python doesn't compute penalties — Java does). Verify the harness handles
        // this honestly: 0 raw events, 0 clusters, 0 over-cap clusters.
        Assert.assertEquals(r.policyA().totalRawEventCount(), 0,
                "Python's empty contributor items[] must propagate as 0 raw events. "
                + "Inventing events would violate the never-pretend invariant.");
        Assert.assertEquals(r.policyA().totalClustersOverCap(), 0,
                "Same reasoning — 0 clusters over cap when items are empty.");
        Assert.assertEquals(r.policyADelta(), 0.0, 0.001,
                "Python snapshot has penalty=0 and no contributors. Both policies "
                + "produce 0 applied penalty. Delta = 0 - 0 = 0.");

        LOG.info("[bridge] ReplayHarness → A.applied={}, B.applied={}, rawEvents={}, clusters={}",
                r.policyA().totalApplied(),
                r.policyB().totalApplied(),
                r.policyA().totalRawEventCount(),
                r.policyA().totalClusterCount());
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    /**
     * Load the fixture from the classpath (src/test/resources). Using the
     * classpath rather than a hardcoded file path means the test runs the
     * same way locally and in CI, regardless of working directory.
     */
    private static String loadFixture() throws Exception {
        ClassLoader cl = PythonSnapshotBridgeTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(FIXTURE_RESOURCE)) {
            Assert.assertNotNull(is,
                    "Fixture not found on classpath: " + FIXTURE_RESOURCE
                    + ". Make sure src/test/resources/" + FIXTURE_RESOURCE
                    + " exists and is included in the test classpath.");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
