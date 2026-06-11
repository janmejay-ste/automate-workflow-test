package testing.health.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import utils.health.schema.SchemaDetector;
import utils.health.schema.SnapshotSchemaVersion;

/**
 * Runs the detector against the actual files on disk that motivated B4.
 * If these tests pass, the detector is correctly classifying the real
 * production corpus — not just synthetic fixtures.
 *
 * <p>Each assertion is wrapped so a missing file just logs and passes
 * (the report directory layout may differ across environments). The
 * point is: when the files DO exist, they must classify correctly.</p>
 */
public class SchemaDetectorRealFileTest {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaDetectorRealFileTest.class);

    @Test(groups = {"sanity"})
    public void currentHealthSnapshot_classifiesAsV3() throws Exception {
        Path p = Paths.get("reports/trend/health_snapshot.json");
        if (!Files.exists(p)) {
            LOG.info("[real-file] {} not present — skipping. Run any suite to create it.", p);
            return;
        }
        SnapshotSchemaVersion v = SchemaDetector.detect(Files.readString(p));
        LOG.info("[real-file] {} → {}", p, v);
        Assert.assertEquals(v, SnapshotSchemaVersion.V3_CONTRIBUTOR_RICH,
                "current production snapshot must classify as V3 — the format the "
                + "C4 replay harness requires");
    }

    @Test(groups = {"sanity"})
    public void historyJson_classifiesAsV2Aggregate() throws Exception {
        Path p = Paths.get("reports/trend/history.json");
        if (!Files.exists(p)) {
            LOG.info("[real-file] {} not present — skipping.", p);
            return;
        }
        SnapshotSchemaVersion v = SchemaDetector.detect(Files.readString(p));
        LOG.info("[real-file] {} → {}", p, v);
        Assert.assertEquals(v, SnapshotSchemaVersion.V2_AGGREGATE,
                "history.json carries the runs[] aggregate shape — must classify as V2. "
                + "This is the file C4 Phase 3 could not replay; B4 codifies that as a "
                + "first-class fact instead of an undocumented gotcha.");
    }
}
