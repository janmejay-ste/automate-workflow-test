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
import pages.marketing.HomePage;

/**
 * P1 — Functional coverage for the flozic.ai homepage (routing-only).
 *
 * <p>Tests verify that interactive elements route to the correct destination.
 * NO form submissions in this phase — submitting signup/contact forms requires
 * a test-account hygiene policy that doesn't exist yet.</p>
 *
 * <p>Each test starts on the homepage and clicks one interactive element,
 * then asserts the URL change. Tests are independent — re-navigation in
 * {@code @BeforeMethod} resets state.</p>
 */
@TestCategory(type = TestType.SANITY, requiresLogin = false, feature = "Marketing > Homepage")
public class HomepageFunctionalTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(HomepageFunctionalTest.class);
    private static final int URL_TRANSITION_TIMEOUT_SEC = 15;

    private HomePage home;

    @BeforeMethod(alwaysRun = true)
    public void openHomepage() {
        home = new HomePage(driver).navigate();
        Assert.assertTrue(home.isLoaded(), "Homepage failed to load before functional test");
    }

    @Test(groups = {"sanity"})
    public void heroCtaRoutesToSignup() {
        home.clickHeroCta();
        waitForUrlContains("/register", "/signup");
        String url = driver.getCurrentUrl();
        Assert.assertTrue(url.contains("/register") || url.contains("/signup"),
                "Hero CTA did not route to a signup URL. Final: " + url);
    }

    @Test(groups = {"sanity"})
    public void headerSignupRoutesToRegister() {
        home.header().clickSignup();
        waitForUrlContains("/register", "/signup");
        String url = driver.getCurrentUrl();
        Assert.assertTrue(url.contains("/register") || url.contains("/signup"),
                "Header signup did not route to a register URL. Final: " + url);
    }

    @Test(groups = {"sanity"})
    public void headerLoginRoutesToAuthDomain() {
        home.header().clickLogin();
        waitForUrlContains("accounts.appypie", "login");
        String url = driver.getCurrentUrl();
        Assert.assertTrue(
                url.contains("accounts.appypie") || url.contains("/login"),
                "Header login did not route to the auth domain. Final: " + url);
    }

    @Test(groups = {"sanity"})
    public void headerPricingRoutesToPricingPage() {
        home.header().clickPricing();
        home.header().waitForUrlContains("pricing", URL_TRANSITION_TIMEOUT_SEC);
        Assert.assertTrue(driver.getCurrentUrl().contains("pricing"),
                "Header pricing link did not route to a pricing URL. Final: " + driver.getCurrentUrl());
    }

    @Test(groups = {"sanity"})
    public void headerAppDirectoryRoutesToDirectoryPage() {
        home.header().clickAppDirectory();
        home.header().waitForUrlContains("app-directory", URL_TRANSITION_TIMEOUT_SEC);
        Assert.assertTrue(driver.getCurrentUrl().contains("app-directory"),
                "Header app-directory link did not route correctly. Final: " + driver.getCurrentUrl());
    }

    // ── "Email Us" modal (#myModal) ──────────────────────────────────────────
    //
    // The modal has no standard `data-target='#myModal'` trigger in the DOM —
    // production opens it via custom JS. {@link HomePage#openEmailModal()} has
    // a {@code jQuery('#myModal').modal('show')} fallback that fires when no
    // natural trigger is found. The tests below exercise the modal's structure
    // regardless of how it was opened.

    @Test(groups = {"sanity"})
    public void emailUsModalCanBeOpened() {
        home.openEmailModal();
        Assert.assertTrue(home.isEmailModalVisible(),
                "Email Us modal did not become visible after open invocation");
    }

    @Test(groups = {"sanity"})
    public void emailUsModalTitleReadsEmailUs() {
        home.openEmailModal();
        Assert.assertEquals(home.getEmailModalTitle(), "Email Us",
                "Email Us modal title text mismatch");
    }

    @Test(groups = {"sanity"})
    public void emailUsModalCloseButtonDismisses() {
        home.openEmailModal();
        Assert.assertTrue(home.isEmailModalVisible(), "Modal must open before close test");
        home.closeEmailModal();
        Assert.assertFalse(home.isEmailModalVisible(),
                "Email Us modal did not dismiss after close-button click");
    }

    @Test(groups = {"sanity"})
    public void emailUsModalContainsContactForm() {
        home.openEmailModal();
        Assert.assertTrue(home.emailModalHasContactForm(),
                "Email Us modal opened but no Contact Form 7 form container is present");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Waits for the URL to contain ANY of the supplied fragments. */
    private void waitForUrlContains(String... fragments) {
        new WebDriverWait(driver, Duration.ofSeconds(URL_TRANSITION_TIMEOUT_SEC)).until(d -> {
            String u = d.getCurrentUrl().toLowerCase();
            for (String f : fragments) if (u.contains(f.toLowerCase())) return true;
            return false;
        });
        LOG.info("URL transition complete: {}", driver.getCurrentUrl());
    }
}
