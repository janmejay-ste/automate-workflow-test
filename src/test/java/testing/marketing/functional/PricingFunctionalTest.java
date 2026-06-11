package testing.marketing.functional;

import java.time.Duration;

import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import pages.marketing.PricingPage;

/**
 * P2 — Functional (routing-only) coverage for the flozic.ai pricing page.
 *
 * <p>Verifies that pricing-page CTAs route to their declared destinations.
 * NO form submissions in this phase — per the P1/P2 scope.</p>
 */
@TestCategory(type = TestType.SANITY, requiresLogin = false, feature = "Marketing > Pricing")
public class PricingFunctionalTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(PricingFunctionalTest.class);
    private static final int URL_TRANSITION_TIMEOUT_SEC = 15;

    private PricingPage pricing;

    @BeforeMethod(alwaysRun = true)
    public void openPricing() {
        pricing = new PricingPage(driver).navigate();
        Assert.assertTrue(pricing.isLoaded(), "Pricing page failed to load before functional test");
    }

    /**
     * Clicks the first BUY NOW / TRY NOW CTA. Production DOM has these as
     * {@code onclick="try_buy(...)"} with {@code href="javascript:void(0)"} —
     * the JS handler redirects to login (anonymous user) or checkout (authenticated).
     * Assertion: SOMETHING changes (URL or a new visible auth/checkout indicator),
     * not a specific destination.
     */
    @Test(groups = {"sanity"})
    public void firstBuyCtaTriggersAuthOrCheckoutFlow() {
        String urlBefore = driver.getCurrentUrl();
        pricing.clickFirstBuyCta();

        // Give the JS handler time to fire (modal open / redirect)
        try { Thread.sleep(2500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        String urlAfter = driver.getCurrentUrl();
        boolean urlChanged = !urlAfter.equals(urlBefore);
        boolean reachedAuth = urlAfter.contains("login") || urlAfter.contains("register")
                || urlAfter.contains("signup") || urlAfter.contains("accounts.appypie")
                || urlAfter.contains("checkout") || urlAfter.contains("subscribe");

        Assert.assertTrue(urlChanged || reachedAuth,
                "BUY CTA produced no observable change. Before: " + urlBefore + " After: " + urlAfter);
        LOG.info("BUY CTA result — URL transitioned to: {}", urlAfter);
    }

    /**
     * Clicks the Enterprise "Contact Us" CTA. Production DOM uses {@code target="_blank"}
     * to open Calendly in a new tab. Assertion: a new window/tab opens with a Calendly URL.
     */
    @Test(groups = {"sanity"})
    public void enterpriseContactOpensCalendlyInNewTab() {
        if (pricing.enterpriseContactCount() == 0) {
            throw new org.testng.SkipException(
                    "Enterprise Contact CTA not present on pricing page — skipping");
        }
        String originalWindow = driver.getWindowHandle();
        int handlesBefore = driver.getWindowHandles().size();

        pricing.clickEnterpriseContactCta();

        // Wait for new tab to open
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> d.getWindowHandles().size() > handlesBefore);
        } catch (Exception e) {
            Assert.fail("Enterprise Contact click did not open a new tab. "
                    + "Window handles: " + driver.getWindowHandles().size());
        }

        // Switch to the new tab and check the URL
        String newTabUrl = null;
        for (String handle : driver.getWindowHandles()) {
            if (!handle.equals(originalWindow)) {
                driver.switchTo().window(handle);
                newTabUrl = driver.getCurrentUrl();
                driver.close();
                break;
            }
        }
        driver.switchTo().window(originalWindow);

        Assert.assertNotNull(newTabUrl, "New tab opened but URL could not be read");
        Assert.assertTrue(newTabUrl.contains("calendly"),
                "Enterprise Contact opened a new tab but URL is not Calendly. Actual: " + newTabUrl);
        LOG.info("Enterprise Contact opened Calendly tab: {}", newTabUrl);
    }

    /**
     * Clicks the first "View all Features" expander. Smoke check that the button is
     * actually interactive (clickable without throwing) — production behavior likely
     * toggles the expanded state of the features list within the card.
     */
    @Test(groups = {"sanity"})
    public void viewAllFeaturesButtonIsClickable() {
        if (pricing.viewAllFeaturesCount() == 0) {
            throw new org.testng.SkipException(
                    "No 'View all Features' buttons present — skipping");
        }
        // The assertion here is "no exception" — production toggles in-card state
        // that doesn't have a stable URL or DOM marker we can rely on cross-tier.
        pricing.clickFirstViewAllFeatures();
        LOG.info("View all Features button click completed without error");
    }

    @Test(groups = {"sanity"})
    public void headerPricingLinkKeepsUserOnPricing() {
        // Sanity: clicking Pricing from the pricing page itself should stay on /pricing
        pricing.header().clickPricing();
        pricing.header().waitForUrlContains("pricing", URL_TRANSITION_TIMEOUT_SEC);
        Assert.assertTrue(driver.getCurrentUrl().contains("pricing"),
                "Header pricing link routed away from pricing. Final: " + driver.getCurrentUrl());
    }

    @Test(groups = {"sanity"})
    public void headerLoginRoutesToAuthDomain() {
        pricing.header().clickLogin();
        waitForUrlContains("accounts.appypie", "login");
        String url = driver.getCurrentUrl();
        Assert.assertTrue(
                url.contains("accounts.appypie") || url.contains("/login"),
                "Header login did not route to auth domain. Final: " + url);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void waitForUrlContains(String... fragments) {
        new WebDriverWait(driver, Duration.ofSeconds(URL_TRANSITION_TIMEOUT_SEC)).until(d -> {
            String u = d.getCurrentUrl().toLowerCase();
            for (String f : fragments) if (u.contains(f.toLowerCase())) return true;
            return false;
        });
        LOG.info("URL transition complete: {}", driver.getCurrentUrl());
    }
}
