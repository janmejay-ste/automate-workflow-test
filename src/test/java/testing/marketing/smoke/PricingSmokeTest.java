package testing.marketing.smoke;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import pages.marketing.PricingPage;
import utils.JsConsoleMonitor;
import utils.config.BrandText;

/**
 * P2 — Smoke coverage for the flozic.ai pricing page.
 *
 * <p>Mirrors {@code HomepageSmokeTest} structure with pricing-specific
 * assertions:</p>
 * <ol>
 *   <li>Page reaches a loaded state</li>
 *   <li>No FAIL-severity console errors during load</li>
 *   <li>Title contains brand name</li>
 *   <li>At least 2 pricing tier cards visible</li>
 *   <li>At least 1 plan CTA visible</li>
 *   <li>Primary nav links all present (from header component)</li>
 * </ol>
 *
 * <p>Phase P2 of the marketing-pages coverage rollout.</p>
 */
@TestCategory(type = TestType.SMOKE, requiresLogin = false, feature = "Marketing > Pricing")
public class PricingSmokeTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(PricingSmokeTest.class);

    /** Tolerance: pricing pages typically expose 3-4 tiers; require at least 2. */
    private static final int MIN_PLAN_CARDS = 2;

    private PricingPage pricing;

    @BeforeMethod(alwaysRun = true)
    public void openPricing() {
        pricing = new PricingPage(driver).navigate();
    }

    @Test(groups = {"smoke"})
    public void pageReachesLoadedState() {
        Assert.assertTrue(pricing.isLoaded(),
                "Pricing page failed to reach loaded state. URL: " + driver.getCurrentUrl());
    }

    @Test(groups = {"smoke"})
    public void noFatalConsoleErrorsDuringLoad() {
        List<String> severe = JsConsoleMonitor.getSevereErrors(driver);
        long fatal = severe.stream()
                .filter(msg -> JsConsoleMonitor.classify(msg) == JsConsoleMonitor.Severity.FAIL)
                .count();
        Assert.assertEquals(fatal, 0L,
                "FAIL-severity console errors during pricing load: " + severe);
    }

    @Test(groups = {"smoke"})
    public void titleContainsBrandName() {
        String title = driver.getTitle();
        Assert.assertTrue(title != null && title.contains(BrandText.PRODUCT_NAME),
                "Title does not contain '" + BrandText.PRODUCT_NAME + "'. Actual: " + title);
    }

    @Test(groups = {"smoke"})
    public void atLeastMinimumPlanCardsVisible() {
        int count = pricing.planCardCount();
        LOG.info("Pricing tier card count: {}", count);
        Assert.assertTrue(count >= MIN_PLAN_CARDS,
                "Expected at least " + MIN_PLAN_CARDS + " pricing tier cards. Found: " + count);
    }

    @Test(groups = {"smoke"})
    public void atLeastOnePlanCtaVisible() {
        int count = pricing.planCtaCount();
        LOG.info("Plan CTA count: {}", count);
        Assert.assertTrue(count >= 1,
                "Expected at least 1 plan CTA (BUY/TRY/Contact/View). Found: " + count);
    }

    /** Production has 3 BUY NOW / TRY NOW buttons (Standard, Professional, Business tiers). */
    @Test(groups = {"smoke"})
    public void buyCtasPresentForPaidTiers() {
        int count = pricing.buyCtaCount();
        LOG.info("Buy CTA count: {}", count);
        Assert.assertTrue(count >= 3,
                "Expected at least 3 BUY NOW / TRY NOW buttons (Standard, Professional, Business). Found: " + count);
    }

    /** Production has exactly 1 Enterprise "Contact Us" CTA (Calendly link). */
    @Test(groups = {"smoke"})
    public void enterpriseContactCtaPresent() {
        int count = pricing.enterpriseContactCount();
        LOG.info("Enterprise Contact CTA count: {}", count);
        Assert.assertTrue(count >= 1,
                "Expected at least 1 Enterprise Contact Us CTA. Found: " + count);
    }

    /** Production has 4 "View all Features" expanders (one per tier). */
    @Test(groups = {"smoke"})
    public void viewAllFeaturesButtonsPresent() {
        int count = pricing.viewAllFeaturesCount();
        LOG.info("View all Features button count: {}", count);
        Assert.assertTrue(count >= 4,
                "Expected at least 4 'View all Features' buttons (one per tier). Found: " + count);
    }

    /** Sanity check that the monthly/yearly billing-toggle content blocks both exist. */
    @Test(groups = {"smoke"})
    public void billingContentBlocksBothExist() {
        Assert.assertTrue(pricing.hasBillingContentBlocks(),
                "Expected both #monthlyContent and #yearlyContent blocks to exist");
    }

    @Test(groups = {"smoke"})
    public void primaryNavLinksAllPresent() {
        List<String> missing = pricing.header().missingLinks();
        Assert.assertTrue(missing.isEmpty(),
                "Missing primary nav links: " + missing);
    }
}
