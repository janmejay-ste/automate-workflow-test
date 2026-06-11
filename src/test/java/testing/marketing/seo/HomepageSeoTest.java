package testing.marketing.seo;

import java.util.List;

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
import pages.marketing.HomePage;
import utils.config.UrlRegistry;

/**
 * P1 — SEO metadata coverage for the flozic.ai homepage.
 *
 * <p>Verifies the minimum SEO surface a public marketing page must expose:</p>
 * <ul>
 *   <li>Canonical link points to the current marketing host</li>
 *   <li>Open Graph tags present and non-empty (og:title, og:description, og:image)</li>
 *   <li>Twitter card declared</li>
 *   <li>Page is NOT noindex'd</li>
 *   <li>At least one JSON-LD structured-data block parses</li>
 * </ul>
 *
 * <p>These tests detect the kind of silent regressions that don't show up in
 * functional QA but tank search visibility — a stripped {@code og:image} or
 * an accidental {@code noindex} won't break user flows but quietly degrades
 * the brand's discoverability.</p>
 */
@TestCategory(type = TestType.REGRESSION, requiresLogin = false, feature = "Marketing > Homepage")
public class HomepageSeoTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HomepageSeoTest.class);

    @BeforeMethod(alwaysRun = true)
    public void openHomepage() {
        new HomePage(driver).navigate();
    }

    @Test(groups = {"regression"})
    public void canonicalLinkPointsToMarketingHost() {
        String href = safeAttr(By.cssSelector("link[rel='canonical']"), "href");
        Assert.assertNotNull(href, "No <link rel='canonical'> on homepage");
        Assert.assertTrue(UrlRegistry.isOwnedMarketingHost(href),
                "Canonical href is not an owned marketing host. Actual: " + href);
    }

    @Test(groups = {"regression"})
    public void openGraphTitleIsPresent() {
        String og = safeAttr(By.cssSelector("meta[property='og:title']"), "content");
        Assert.assertTrue(og != null && !og.isBlank(),
                "og:title missing or blank");
    }

    @Test(groups = {"regression"})
    public void openGraphDescriptionIsPresent() {
        String og = safeAttr(By.cssSelector("meta[property='og:description']"), "content");
        Assert.assertTrue(og != null && !og.isBlank(),
                "og:description missing or blank");
    }

    @Test(groups = {"regression"})
    public void openGraphImageIsPresent() {
        String og = safeAttr(By.cssSelector("meta[property='og:image']"), "content");
        Assert.assertTrue(og != null && !og.isBlank(),
                "og:image missing or blank");
    }

    @Test(groups = {"regression"})
    public void twitterCardIsDeclared() {
        String card = safeAttr(By.cssSelector("meta[name='twitter:card']"), "content");
        Assert.assertTrue(card != null && !card.isBlank(),
                "twitter:card missing or blank");
    }

    @Test(groups = {"regression"})
    public void homepageIsIndexable() {
        String robots = safeAttr(By.cssSelector("meta[name='robots']"), "content");
        // robots tag is optional; if absent, default is indexable.
        if (robots != null) {
            Assert.assertFalse(robots.toLowerCase().contains("noindex"),
                    "Homepage is marked noindex — search visibility lost. Tag: " + robots);
        }
    }

    @Test(groups = {"regression"})
    public void hasAtLeastOneStructuredDataBlock() {
        List<WebElement> jsonLd = driver.findElements(By.cssSelector("script[type='application/ld+json']"));
        Assert.assertFalse(jsonLd.isEmpty(),
                "No JSON-LD structured-data block found on homepage");

        boolean anyParseable = jsonLd.stream().anyMatch(el -> {
            String body = el.getAttribute("innerHTML");
            return body != null && body.trim().startsWith("{") || body != null && body.trim().startsWith("[");
        });
        Assert.assertTrue(anyParseable,
                "JSON-LD blocks are present but none look like valid JSON");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String safeAttr(By selector, String attr) {
        try {
            WebElement el = driver.findElement(selector);
            String v = el.getAttribute(attr);
            return v;
        } catch (NoSuchElementException e) {
            LOG.debug("Element not found for {}: {}", attr, selector);
            return null;
        }
    }
}
