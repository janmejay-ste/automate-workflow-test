package testing.marketing.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import pages.marketing.PricingPage;
import utils.urlvalidator.UrlValidationRunner;

/**
 * P2 — Internal link integrity scan for the flozic.ai pricing page.
 *
 * <p>Same tolerance pattern as {@code HomepageLinksTest}: a coarse upper bound
 * on URL findings, with per-finding detail available in the dashboard via
 * {@code semantic.urlValidation}.</p>
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = false, feature = "Marketing > Pricing")
public class PricingLinksTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(PricingLinksTest.class);
    private static final int MAX_HIGH_SEVERITY_FINDINGS = 5;

    @Test(groups = {"regression"})
    public void pricingInternalLinksAreReachable() {
        new PricingPage(driver).navigate();

        UrlValidationRunner.ScanSummary summary =
                UrlValidationRunner.scan(driver, "Marketing Pricing");

        LOG.info("Pricing URL scan: {}", summary);

        Assert.assertTrue(summary.findingsCount <= MAX_HIGH_SEVERITY_FINDINGS,
                "Pricing produced " + summary.findingsCount + " URL findings "
                        + "(tolerance: " + MAX_HIGH_SEVERITY_FINDINGS + "). See dashboard for detail.");
    }
}
