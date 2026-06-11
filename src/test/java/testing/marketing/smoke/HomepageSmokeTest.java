package testing.marketing.smoke;

import java.util.List;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import pages.marketing.HomePage;
import utils.JsConsoleMonitor;
import utils.config.BrandText;

/**
 * P1 — Smoke coverage for the flozic.ai marketing homepage.
 *
 * <p>Five independent assertions, &lt; 30s total:</p>
 * <ol>
 *   <li>Page reaches a loaded state (DOM ready + H1 present)</li>
 *   <li>No FAIL-severity console errors during load</li>
 *   <li>Title contains the brand name</li>
 *   <li>Hero CTA is visible above the fold</li>
 *   <li>Primary nav links all present</li>
 * </ol>
 *
 * <p>Phase P1 of the marketing-pages coverage rollout. Establishes the smoke-test
 * shape that every subsequent page (Pricing, Features, Signup, ...) follows.</p>
 */
@TestCategory(type = TestType.SMOKE, requiresLogin = false, feature = "Marketing > Homepage")
public class HomepageSmokeTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HomepageSmokeTest.class);

    private HomePage home;

    @BeforeMethod(alwaysRun = true)
    public void openHomepage() {
        home = new HomePage(driver).navigate();
    }

    @Test(groups = {"smoke"})
    public void pageReachesLoadedState() {
        Assert.assertTrue(home.isLoaded(),
                "Homepage failed to reach loaded state within timeout. URL: " + driver.getCurrentUrl());
    }

    @Test(groups = {"smoke"})
    public void noFatalConsoleErrorsDuringLoad() {
        List<String> severe = JsConsoleMonitor.getSevereErrors(driver);
        long fatal = severe.stream()
                .filter(msg -> JsConsoleMonitor.classify(msg) == JsConsoleMonitor.Severity.FAIL)
                .count();
        Assert.assertEquals(fatal, 0L,
                "FAIL-severity console errors during homepage load: " + severe);
    }

    @Test(groups = {"smoke"})
    public void titleContainsBrandName() {
        String title = driver.getTitle();
        Assert.assertTrue(title != null && title.contains(BrandText.PRODUCT_NAME),
                "Title does not contain '" + BrandText.PRODUCT_NAME + "'. Actual: " + title);
    }

    @Test(groups = {"smoke"})
    public void heroCtaIsVisible() {
        Assert.assertTrue(home.isHeroCtaVisible(),
                "Hero CTA not visible — none of the defensive selectors matched");
    }

    @Test(groups = {"smoke"})
    public void primaryNavLinksAllPresent() {
        List<String> missing = home.header().missingLinks();
        Assert.assertTrue(missing.isEmpty(),
                "Missing primary nav links: " + missing);
    }

    @Test(groups = {"smoke"})
    public void emailUsModalPresentInDom() {
        Assert.assertTrue(home.isEmailModalPresentInDom(),
                "#myModal (Email Us modal) is not in the homepage DOM");
    }
}
