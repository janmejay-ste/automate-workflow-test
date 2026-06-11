package testing.marketing.seo;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import pages.marketing.PricingPage;
import utils.config.UrlRegistry;

/**
 * P2 — SEO metadata coverage for the flozic.ai pricing page.
 *
 * <p>Same assertions as {@code HomepageSeoTest} but rooted on /pricing-plan.
 * Pricing pages have particularly high SEO value (commercial intent queries),
 * so degradation here directly affects conversion-search visibility.</p>
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = false, feature = "Marketing > Pricing")
public class PricingSeoTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(PricingSeoTest.class);

    @BeforeMethod(alwaysRun = true)
    public void openPricing() {
        new PricingPage(driver).navigate();
    }

    @Test(groups = {"regression"})
    public void canonicalLinkPointsToMarketingHost() {
        String href = safeAttr(By.cssSelector("link[rel='canonical']"), "href");
        Assert.assertNotNull(href, "No <link rel='canonical'> on pricing page");
        Assert.assertTrue(UrlRegistry.isOwnedMarketingHost(href),
                "Canonical href is not an owned marketing host. Actual: " + href);
    }

    @Test(groups = {"regression"})
    public void openGraphTitleIsPresent() {
        String og = safeAttr(By.cssSelector("meta[property='og:title']"), "content");
        Assert.assertTrue(og != null && !og.isBlank(), "og:title missing or blank");
    }

    @Test(groups = {"regression"})
    public void openGraphDescriptionIsPresent() {
        String og = safeAttr(By.cssSelector("meta[property='og:description']"), "content");
        Assert.assertTrue(og != null && !og.isBlank(), "og:description missing or blank");
    }

    @Test(groups = {"regression"})
    public void openGraphImageIsPresent() {
        String og = safeAttr(By.cssSelector("meta[property='og:image']"), "content");
        Assert.assertTrue(og != null && !og.isBlank(), "og:image missing or blank");
    }

    @Test(groups = {"regression"})
    public void pricingPageIsIndexable() {
        String robots = safeAttr(By.cssSelector("meta[name='robots']"), "content");
        if (robots != null) {
            Assert.assertFalse(robots.toLowerCase().contains("noindex"),
                    "Pricing page is marked noindex — high-intent search visibility lost. Tag: " + robots);
        }
    }

    private String safeAttr(By selector, String attr) {
        try {
            WebElement el = driver.findElement(selector);
            return el.getAttribute(attr);
        } catch (NoSuchElementException e) {
            LOG.debug("Element not found for {}: {}", attr, selector);
            return null;
        }
    }
}
