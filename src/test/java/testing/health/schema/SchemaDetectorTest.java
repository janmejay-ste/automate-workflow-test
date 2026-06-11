package testing.health.schema;

import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.schema.SchemaDetector;
import utils.health.schema.SnapshotSchemaVersion;

/**
 * Locks in {@link SchemaDetector} behavior across all version detection
 * paths AND the structural-vs-stamped boundary cases. B4's value depends
 * on correct version identification — if the detector mis-classifies,
 * forward migrations will route to the wrong handler and the corpus
 * silently corrupts.
 *
 * <p>Test categories:</p>
 * <ol>
 *   <li>Happy path — each version detected from a representative shape</li>
 *   <li>Stamped vs structural conflict — the precedence rules</li>
 *   <li>Edge / failure cases — null, blank, malformed JSON</li>
 *   <li>C4-replayability predicate — what counts as replay-ready</li>
 * </ol>
 *
 * <p>Sub-second runtime, no production state touched.</p>
 */
public class SchemaDetectorTest {

    // ────────────────────────────────────────────────────────────────────
    // Happy path
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void v3_contributorRichShape_isDetected() {
        String json = "{\"schemaVersion\":3,"
                    + "\"scoreContributors\":{"
                    + "\"fallbacks\":{\"items\":[]}}}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH);
    }

    @Test(groups = {"sanity"})
    public void v2_runsArrayShape_isDetected() {
        // The history.json shape — top-level "runs" array.
        String json = "{\"schemaVersion\":2,\"runs\":[{\"runId\":\"r1\"}]}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V2_AGGREGATE);
    }

    @Test(groups = {"sanity"})
    public void v2_aggregateNoRunsArray_isDetected() {
        // Older single-run aggregate shape — has overallScore at top
        // level but no scoreContributors and no runs array.
        String json = "{\"schemaVersion\":2,\"overallScore\":85,\"totalPenalty\":15.0}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V2_AGGREGATE);
    }

    @Test(groups = {"sanity"})
    public void v1_noVersionNoContributorsNoAggregate_isLegacy() {
        // Smallest unrecognized shape with valid JSON. Conservative fall-through.
        String json = "{\"someOldField\":\"value\"}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V1_LEGACY);
    }

    // ────────────────────────────────────────────────────────────────────
    // Structural-vs-stamped precedence
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void v3StructureWithMisstampedV2_detectedAsV3() {
        // The case where someone hand-edited a V3 file but forgot to update
        // schemaVersion. Structural check wins.
        String json = "{\"schemaVersion\":2,"
                    + "\"scoreContributors\":{\"fallbacks\":{\"items\":[]}}}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH,
                "structural V3 must win over stamped V2 — the data IS contributor-rich");
    }

    @Test(groups = {"sanity"})
    public void v2StructureWithMisstampedV3_detectedAsV2() {
        // Inverse: stamped V3 but no contributor items. Honest detection
        // says V2 because the data isn't actually contributor-rich.
        // This prevents downstream code from trying to replay an empty
        // file and producing misleading "no divergence" reports.
        String json = "{\"schemaVersion\":3,\"runs\":[{\"runId\":\"r1\"}]}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V2_AGGREGATE,
                "structural V2 (runs array) must win over stamped V3 — "
                + "honest detection prevents false-positive replays");
    }

    @Test(groups = {"sanity"})
    public void v3EmptyItemsArrays_stillDetectedAsV3() {
        // A clean run produces V3 shape with items: [] arrays. The shape
        // is the V3 marker, not the content.
        String json = "{\"schemaVersion\":3,"
                    + "\"scoreContributors\":{"
                    + "\"testFailures\":{\"items\":[]},"
                    + "\"jsErrors\":{\"items\":[]},"
                    + "\"fallbacks\":{\"items\":[]}}}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH);
    }

    // ────────────────────────────────────────────────────────────────────
    // Edge / failure
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void nullJson_unknown() {
        Assert.assertEquals(SchemaDetector.detect((String) null),
                SnapshotSchemaVersion.UNKNOWN);
    }

    @Test(groups = {"sanity"})
    public void blankJson_unknown() {
        Assert.assertEquals(SchemaDetector.detect("   "),
                SnapshotSchemaVersion.UNKNOWN);
    }

    @Test(groups = {"sanity"})
    public void malformedJson_unknown() {
        Assert.assertEquals(SchemaDetector.detect("{not valid"),
                SnapshotSchemaVersion.UNKNOWN);
    }

    @Test(groups = {"sanity"})
    public void jsonArrayAtTopLevel_unknown() {
        // semantic_history.jsonl lines are JSON objects, but if someone
        // accidentally passes the whole file (concatenated), the top-level
        // shape isn't an object. Conservative: UNKNOWN, not V1.
        Assert.assertEquals(SchemaDetector.detect("[{}, {}]"),
                SnapshotSchemaVersion.UNKNOWN);
    }

    @Test(groups = {"sanity"})
    public void unknownStampedCode_fallsBackToHeuristic() {
        // schemaVersion: 99 = future version unknown to this binary.
        // No contributor items, no runs array, no aggregate fields → V1.
        // The point: don't crash, fall through to structural heuristics.
        String json = "{\"schemaVersion\":99,\"someField\":1}";
        Assert.assertEquals(SchemaDetector.detect(json),
                SnapshotSchemaVersion.V1_LEGACY);
    }

    // ────────────────────────────────────────────────────────────────────
    // C4-replayability predicate
    // ────────────────────────────────────────────────────────────────────

    @Test(groups = {"sanity"})
    public void c4Replayable_onlyV3() {
        Assert.assertTrue(SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH.c4Replayable(),
                "V3 is the only schema with raw event data needed for C4 replay");
        Assert.assertFalse(SnapshotSchemaVersion.V2_AGGREGATE.c4Replayable());
        Assert.assertFalse(SnapshotSchemaVersion.V1_LEGACY.c4Replayable());
        Assert.assertFalse(SnapshotSchemaVersion.UNKNOWN.c4Replayable());
    }

    @Test(groups = {"sanity"})
    public void enumOrder_monotonicByCode() {
        // Lock in stable ordering — adding a new version must append, not
        // reorder. Locks against accidental refactoring that would break
        // a future SchemaMigrator chain.
        Assert.assertTrue(SnapshotSchemaVersion.V1_LEGACY.code()
                        < SnapshotSchemaVersion.V2_AGGREGATE.code());
        Assert.assertTrue(SnapshotSchemaVersion.V2_AGGREGATE.code()
                        < SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH.code());
    }

    @Test(groups = {"sanity"})
    public void fromCode_unknownCode_returnsUnknown() {
        Assert.assertEquals(SnapshotSchemaVersion.fromCode(-99),
                SnapshotSchemaVersion.UNKNOWN);
        Assert.assertEquals(SnapshotSchemaVersion.fromCode(1),
                SnapshotSchemaVersion.V1_LEGACY);
        Assert.assertEquals(SnapshotSchemaVersion.fromCode(3),
                SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH);
    }
}
