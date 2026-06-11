package testing.marketing.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import pages.marketing.HomePage;
import utils.urlvalidator.UrlValidationRunner;

/**
 * P1 — Internal link integrity scan for the flozic.ai homepage.
 *
 * <p>Delegates to the existing {@link UrlValidationRunner} which discovers
 * every URL reachable from the rendered page (anchors, scripts, iframes,
 * images, fetches) and classifies findings by severity.</p>
 *
 * <p>This test sets a tolerance budget rather than a strict zero — third-party
 * tracking pixels and analytics URLs sometimes 4xx briefly and shouldn't fail
 * the marketing build. The semantic-layer scoring (via HealthTracker) records
 * the full finding set for the dashboard.</p>
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = false, feature = "Marketing > Homepage")
public class HomepageLinksTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HomepageLinksTest.class);

    /** Tolerance: more than this many high-severity findings fails the run. */
    private static final int MAX_HIGH_SEVERITY_FINDINGS = 5;

    @Test(groups = {"regression"})
    public void homepageInternalLinksAreReachable() {
        new HomePage(driver).navigate();

        UrlValidationRunner.ScanSummary summary =
                UrlValidationRunner.scan(driver, "Marketing Homepage");

        LOG.info("Homepage URL scan: {}", summary);

        // The full finding set is recorded by UrlValidationTracker; this assertion
        // just enforces a coarse upper bound on the count. The dashboard surfaces
        // the per-finding detail under semantic.urlValidation.
        Assert.assertTrue(summary.findingsCount <= MAX_HIGH_SEVERITY_FINDINGS,
                "Homepage produced " + summary.findingsCount + " URL findings "
                        + "(tolerance: " + MAX_HIGH_SEVERITY_FINDINGS + "). See dashboard for detail.");
    }
}
