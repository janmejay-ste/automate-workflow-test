package testing;

import java.time.Duration;

import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import utils.config.UrlRegistry;

/**
 * Strict probe that proves the {@code appypieautomate.ai → flozic.ai} rebrand
 * is complete. Counterpart to {@link UrlRegistry#isOwnedMarketingHost} which
 * accepts both hosts during the transition.
 *
 * <p>Runs nightly. Fails the moment the redirect chain regresses or the legacy
 * domain stops redirecting to the current marketing host. Delete this class
 * (or relax to {@code isOwnedMarketingHost}) once the rebrand window closes
 * (~30 days post-cutover).</p>
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = false, feature = "Rebrand")
public class RebrandCompletionTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(RebrandCompletionTest.class);

    /**
     * The legacy marketing domain must 301-redirect to the current marketing host.
     * If it ever starts serving 200 directly OR redirects to a third domain, this fails.
     */
    @Test(groups = {"regression"})
    public void legacyDomainRedirectsToCurrentMarketingHost() {
        driver.get("https://www.appypieautomate.ai");

        try {
            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(d -> UrlRegistry.isCurrentMarketingHost(d.getCurrentUrl()));
        } catch (Exception e) {
            // Fall through — assertion below produces the readable failure message.
        }

        String finalUrl = driver.getCurrentUrl();
        LOG.info("Legacy domain final URL: {}", finalUrl);
        Assert.assertTrue(
                UrlRegistry.isCurrentMarketingHost(finalUrl),
                "Legacy appypieautomate.ai must redirect to "
                        + UrlRegistry.marketingHostname()
                        + ". Actual: " + finalUrl);
    }

    /**
     * Direct navigation to the current marketing host must land cleanly on it —
     * no redirect bounce, no 4xx/5xx page. This catches DNS / TLS / CDN
     * misconfigurations during the rebrand window.
     */
    @Test(groups = {"regression"})
    public void currentMarketingHostLoadsDirectly() {
        driver.get(UrlRegistry.MARKETING_BASE);

        String finalUrl = driver.getCurrentUrl();
        LOG.info("Direct navigation final URL: {}", finalUrl);
        Assert.assertTrue(
                UrlRegistry.isCurrentMarketingHost(finalUrl),
                "Direct navigation to " + UrlRegistry.MARKETING_BASE
                        + " did not stay on " + UrlRegistry.marketingHostname()
                        + ". Actual: " + finalUrl);
    }
}
