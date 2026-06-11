package testing.semantic;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import utils.health.semantic.ClusterSeverity;
import utils.health.semantic.ErrorCluster;
import utils.health.semantic.FailureDomain;
import utils.health.semantic.LayeredHealthScores;

/**
 * Phase C3 — Direct exercise of {@link LayeredHealthScores#compute} under
 * sample-size-aware LOW-cluster suppression.
 *
 * <p>This test does NOT extend BaseTest — it doesn't need a browser. Each
 * test method hand-constructs {@link ErrorCluster}s and asserts on the
 * computed productHealth score under both flag values.</p>
 *
 * <p>Why this style: the existing test infrastructure can't easily produce
 * LOW-severity JS clusters as a runtime byproduct (clean smoke runs have
 * none; driver-crash runs produce HIGH/CRITICAL). The threshold formula
 * itself is the thing under test — direct invocation is the cheapest path
 * to high-confidence behavioral verification.</p>
 *
 * <h3>Formula under test</h3>
 * <pre>
 *   lowThreshold = sampleSizeAware && totalTestCount > 0
 *                    ? max(3, ceil(totalTestCount * 0.1))
 *                    : 3   (legacy)
 * </pre>
 *
 * <p>Each LOW cluster up to {@code lowThreshold} contributes 1 point to
 * the product-health penalty. LOW clusters beyond the threshold contribute
 * 0 (suppressed). Other severities (CRITICAL=20, HIGH=10, MEDIUM=5) are
 * unaffected and used here only as sanity-check controls.</p>
 */
public class C3SuppressionTest {

    private static final Logger LOG = LoggerFactory.getLogger(C3SuppressionTest.class);

    /** System-property keys we toggle for each test. Restore after the class runs. */
    private static final String FLAG = "js.suppression.sampleSizeAware";
    private static String savedFlag;

    @BeforeClass(alwaysRun = true)
    public void saveProperty() {
        savedFlag = System.getProperty(FLAG);
    }

    @AfterClass(alwaysRun = true)
    public void restoreProperty() {
        if (savedFlag == null) System.clearProperty(FLAG);
        else                   System.setProperty(FLAG, savedFlag);
    }

    /** Builds N LOW-severity PRODUCT clusters with distinct titles. */
    private static List<ErrorCluster> nLowClusters(int n) {
        List<ErrorCluster> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ErrorCluster(
                    "Sample LOW cluster #" + i,
                    /* count */         1,
                    FailureDomain.PRODUCT,
                    ClusterSeverity.LOW,
                    /* sampleMessage */ "low-severity event " + i,
                    /* sampleContext */ "ctx" + i,
                    /* reason */        "synthetic test fixture",
                    /* distinctCtx */   1));
        }
        return out;
    }

    /**
     * Legacy behaviour: with the flag OFF, only the first 3 LOW clusters
     * count, regardless of {@code totalTestCount}. With 6 LOW clusters:
     *   penalty = min(3, 6) * 1 = 3
     *   productHealth = 100 - 3 = 97
     */
    @Test(groups = {"sanity"})
    public void legacyFlagOff_caps_at_3() {
        System.setProperty(FLAG, "false");
        List<ErrorCluster> clusters = nLowClusters(6);

        // totalTestCount=999 (arbitrary high N) confirms the flag truly controls behaviour
        LayeredHealthScores scores = LayeredHealthScores.compute(
                clusters, 0, 0, 0, 0,
                /* businessSuccess */ 0, /* partial */ 0, /* failed */ 0,
                /* totalTestCount */  999);

        Assert.assertEquals(scores.productHealth, 97,
                "Flag=false should always cap LOW count at 3 → penalty 3 → productHealth 97. "
                + "totalTestCount=999 must NOT affect this path.");
        LOG.info("Legacy flag-off cap-at-3 verified: productHealth={}", scores.productHealth);
    }

    /**
     * Flag ON with a small N: threshold is max(3, ceil(N*0.1)). At N=10 the
     * ceil yields 1, so max(3,1)=3 — identical to legacy. This proves the
     * formula is conservative: it never SHRINKS the legacy threshold.
     */
    @Test(groups = {"sanity"})
    public void flagOn_smallSample_preserves_legacy_threshold() {
        System.setProperty(FLAG, "true");
        List<ErrorCluster> clusters = nLowClusters(6);

        LayeredHealthScores scores = LayeredHealthScores.compute(
                clusters, 0, 0, 0, 0, 0, 0, 0, /* totalTestCount */ 10);

        Assert.assertEquals(scores.productHealth, 97,
                "Flag=true, N=10 → threshold=max(3, ceil(10*0.1))=max(3,1)=3 → still 97");
        LOG.info("Flag-on small-sample (N=10) preserves legacy threshold=3: productHealth={}",
                scores.productHealth);
    }

    /**
     * Flag ON with medium N: threshold becomes 5. With 6 LOW clusters:
     *   penalty = min(5, 6) * 1 = 5
     *   productHealth = 100 - 5 = 95
     * This is the FIRST case where the flag changes scoring vs legacy.
     */
    @Test(groups = {"sanity"})
    public void flagOn_mediumSample_admits_5_low_clusters() {
        System.setProperty(FLAG, "true");
        List<ErrorCluster> clusters = nLowClusters(6);

        LayeredHealthScores scores = LayeredHealthScores.compute(
                clusters, 0, 0, 0, 0, 0, 0, 0, /* totalTestCount */ 50);

        Assert.assertEquals(scores.productHealth, 95,
                "Flag=true, N=50 → threshold=max(3, ceil(50*0.1))=max(3,5)=5 → "
                + "5 of 6 LOW clusters counted → penalty 5 → productHealth 95");
        LOG.info("Flag-on medium-sample (N=50, threshold=5) admits 5 LOW clusters: productHealth={}",
                scores.productHealth);
    }

    /**
     * Flag ON with large N: threshold is 10. With 6 LOW clusters all count,
     * with 12 LOW clusters only 10 count.
     */
    @Test(groups = {"sanity"})
    public void flagOn_largeSample_admits_10_low_clusters() {
        System.setProperty(FLAG, "true");

        // 6 clusters, N=100, threshold=10 → all 6 count → penalty 6
        LayeredHealthScores six = LayeredHealthScores.compute(
                nLowClusters(6), 0, 0, 0, 0, 0, 0, 0, 100);
        Assert.assertEquals(six.productHealth, 94,
                "Flag=true, N=100, 6 LOW clusters → all 6 count → penalty 6 → productHealth 94");

        // 12 clusters, N=100, threshold=10 → 10 count → penalty 10
        LayeredHealthScores twelve = LayeredHealthScores.compute(
                nLowClusters(12), 0, 0, 0, 0, 0, 0, 0, 100);
        Assert.assertEquals(twelve.productHealth, 90,
                "Flag=true, N=100, 12 LOW clusters → 10 count (threshold=10) → penalty 10 → productHealth 90");

        LOG.info("Flag-on large-sample (N=100, threshold=10): 6→{}, 12→{}",
                six.productHealth, twelve.productHealth);
    }

    /**
     * Edge case: flag ON, but totalTestCount=0 — falls back to legacy threshold=3.
     * Confirms the formula's degraded-mode behaviour: missing sample-size data
     * doesn't crash, just yields the conservative legacy threshold.
     */
    @Test(groups = {"sanity"})
    public void flagOn_zeroTestCount_falls_back_to_legacy() {
        System.setProperty(FLAG, "true");
        List<ErrorCluster> clusters = nLowClusters(6);

        LayeredHealthScores scores = LayeredHealthScores.compute(
                clusters, 0, 0, 0, 0, 0, 0, 0, /* totalTestCount */ 0);

        Assert.assertEquals(scores.productHealth, 97,
                "Flag=true but totalTestCount=0 → degraded to legacy threshold 3 → productHealth 97");
        LOG.info("Flag-on with N=0 falls back to legacy: productHealth={}",
                scores.productHealth);
    }

    /**
     * Mixed-severity sanity check — the flag affects ONLY LOW-cluster suppression,
     * not CRITICAL/HIGH/MEDIUM. Constructs one of each plus enough LOW clusters
     * to differentiate flag values, verifies the differential is exactly the
     * additional LOW clusters admitted.
     */
    @Test(groups = {"sanity"})
    public void flagOn_only_affects_low_severity() {
        List<ErrorCluster> mixed = new ArrayList<>();
        mixed.add(new ErrorCluster("CRIT", 1, FailureDomain.PRODUCT,
                ClusterSeverity.CRITICAL, "c", "c", "r", 1));        //  -20
        mixed.add(new ErrorCluster("HIGH", 1, FailureDomain.PRODUCT,
                ClusterSeverity.HIGH, "h", "h", "r", 1));            //  -10
        mixed.add(new ErrorCluster("MED",  1, FailureDomain.PRODUCT,
                ClusterSeverity.MEDIUM, "m", "m", "r", 1));          //   -5
        // 6 LOW clusters — 3 count under legacy, 5 count under flag with N=50
        mixed.addAll(nLowClusters(6));

        System.setProperty(FLAG, "false");
        LayeredHealthScores legacy = LayeredHealthScores.compute(
                mixed, 0, 0, 0, 0, 0, 0, 0, 50);

        System.setProperty(FLAG, "true");
        LayeredHealthScores flagged = LayeredHealthScores.compute(
                mixed, 0, 0, 0, 0, 0, 0, 0, 50);

        // Both: -20 (CRIT) - 10 (HIGH) - 5 (MED) - X (LOW), where X=3 legacy, X=5 flagged
        // Legacy: 100 - 20 - 10 - 5 - 3 = 62
        // Flagged: 100 - 20 - 10 - 5 - 5 = 60
        Assert.assertEquals(legacy.productHealth, 62,
                "Mixed severities + legacy: -20-10-5-3 = 38 penalty → 62");
        Assert.assertEquals(flagged.productHealth, 60,
                "Mixed severities + flagged + N=50: -20-10-5-5 = 40 penalty → 60");
        Assert.assertEquals(legacy.productHealth - flagged.productHealth, 2,
                "Exactly 2-point differential corresponds to the 2 additional LOW clusters admitted");
        LOG.info("Mixed-severity: legacy={}, flagged={}, differential={} (expected 2)",
                legacy.productHealth, flagged.productHealth,
                legacy.productHealth - flagged.productHealth);
    }
}
