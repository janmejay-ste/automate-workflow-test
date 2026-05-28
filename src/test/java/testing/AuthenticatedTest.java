package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.ConnectEditorPage;
import pages.DashboardPage;
import pages.WaitUtils;
import utils.ManualLoginHelper;
import utils.health.HealthTracker;

import java.time.Duration;

/**
 * Sanity journey: Login → Dashboard → Create Connect → wait for editor to load → Logout.
 *
 * No app selection, no trigger/action wiring — this test validates that the
 * core navigation shell works end-to-end and that logout via the profile
 * dropdown functions correctly after a fresh editor session.
 */
@TestCategory(type = TestType.FULL, requiresLogin = true, feature = "Sanity Journey",
        owner = "Janmejay")
public class AuthenticatedTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticatedTest.class);

    private static final By EDITOR_LOADER = By.cssSelector(
            "app-loader, .outhLoader, .authLoader, [class*='loader'], [class*='spinner']");

    @Test(groups = { "full" })
    public void sanityUserJourney() {
        LOG.info("=== Sanity: Login → Dashboard → Create Connect → Editor Load → Logout ===");

        DashboardPage dashboard = new DashboardPage(driver);
        ConnectEditorPage editor = new ConnectEditorPage(driver);
        WaitUtils vu = new WaitUtils(driver, Duration.ofSeconds(15));

        // ── Business-outcome instrumentation ──────────────────────────────────
        // This test exercises one user-intent transaction: "Sanity Journey".
        // Declare its required criteria up front so a) the test reads as the
        // intent it represents, and b) any criterion that's left pending will
        // surface as "never evaluated" in the report instead of silently passing.
        utils.health.business.BusinessTransaction tx =
            utils.health.business.BusinessOutcomeTracker.get().begin(
                "Sanity Journey — Login → Create Connect → Logout",
                utils.health.business.BusinessTransactionCategory.WORKFLOW_CREATION)
            .require("Authenticated session established",
                     "Post-login URL on connectcloud / dashboard visible")
            .require("Connect editor canvas rendered",
                     "editor.isEditorVisible() == true")
            .require("Editor loader cleared",
                     "no visible loader after 30s")
            .require("Logout redirected to known auth domain",
                     "URL matches appypie.com/login | accounts.appypie | appypieautomate.ai");

        try {
            // ── Step 1: Login ─────────────────────────────────────────────────
            ManualLoginHelper.navigateAndLogin(driver);
            vu.dismissOverlays();
            tx.observePass("Authenticated session established",
                           "post-login URL: " + driver.getCurrentUrl());
            LOG.info("Step 1: Login complete");

            // ── Step 2: Verify dashboard ──────────────────────────────────────
            if (!dashboard.isDashboardLoaded()) {
                vu.dismissOverlays();
                if (!dashboard.isDashboardLoaded() && !editor.isEditorVisible()) {
                    throw new RuntimeException("Neither dashboard nor editor loaded after login");
                }
            }
            LOG.info("Step 2: Dashboard loaded");
            ManualLoginHelper.dumpDom(driver, "sanity_dashboard");

            // ── Step 3: Open Connect editor ───────────────────────────────────
            if (!editor.isEditorVisible()) {
                dashboard.clickCreateConnect();
                LOG.info("Step 3: Clicked Create Connect");
            } else {
                LOG.info("Step 3: Editor already visible — skipping Create Connect click");
            }

            // Dismiss any onboarding guide that appears
            vu.completeUserGuide();
            vu.dismissOverlays();

            Assert.assertTrue(editor.isEditorVisible(),
                    "Connect editor canvas not visible after Create Connect");
            tx.observePass("Connect editor canvas rendered",
                           "isEditorVisible at " + driver.getCurrentUrl());
            tx.withEvidence("editorUrl", driver.getCurrentUrl());
            LOG.info("Step 3: Editor canvas confirmed visible");

            // ── Step 4: Wait for editor to fully load ─────────────────────────
            // The editor shows loaders (app-loader, outhLoader) while the new connect
            // is being initialised on the backend. Gate checks must not run until
            // these are gone.
            waitForEditorReady("sanity");
            tx.observePass("Editor loader cleared", "no visible loader element");
            LOG.info("Step 4: Editor fully loaded — no active loaders");

            ManualLoginHelper.dumpDom(driver, "sanity_editor_ready");

            // ── Step 5: Logout via profile dropdown ───────────────────────────
            String postLogoutUrl = dashboard.logoutAndVerifyRedirect();
            LOG.info("Step 5: Logout successful — redirected to: {}", postLogoutUrl);

            boolean validRedirect =
                    postLogoutUrl.contains("appypie.com/login") ||
                    postLogoutUrl.contains("accounts.appypie") ||
                    postLogoutUrl.contains("appypieautomate.ai");
            if (validRedirect) {
                tx.observePass("Logout redirected to known auth domain", postLogoutUrl);
            } else {
                tx.observeFail("Logout redirected to known auth domain", postLogoutUrl);
            }
            tx.withEvidence("postLogoutUrl", postLogoutUrl);
            Assert.assertTrue(validRedirect, "Post-logout URL unexpected: " + postLogoutUrl);

            tx.complete();
            LOG.info("=== Sanity Journey PASSED ===  (business outcome: {})", tx.state());

        } catch (AssertionError ae) {
            // Some criterion already recorded a failure (or remained pending) — finalize
            // the transaction so its true state is captured before the assertion bubbles up.
            tx.complete();
            throw ae;
        } catch (Exception e) {
            // Unexpected exception — the transaction was abandoned before evaluation could
            // complete.  Marking ABORTED keeps this out of the business-outcome score so
            // framework breakage doesn't get scored as a product failure.
            tx.abort("Unexpected exception mid-flow: " + e.getMessage());
            LOG.error("Sanity flow failed: {}", e.getMessage());
            ManualLoginHelper.dumpDom(driver, "sanity_failure");
            HealthTracker.get().recordTestFailure("SanityJourney", e.getMessage(), null);

            // Recovery logout so the session doesn't leak into the next test
            try {
                dashboard.logoutAndVerifyRedirect();
                LOG.info("Recovery logout complete");
            } catch (Exception logoutEx) {
                LOG.error("Recovery logout also failed: {}", logoutEx.getMessage());
                driver.get("https://www.appypieautomate.ai");
            }

            Assert.fail("Sanity Journey failed: " + e.getMessage());
        }
    }

    /**
     * Waits for all visible loader/spinner elements on the editor page to clear.
     * Waits up to 5 s for a loader to appear first (it may already be clearing),
     * then up to 30 s for all loaders to disappear.
     */
    private void waitForEditorReady(String context) {
        long start = System.currentTimeMillis();

        // Phase 1: let any loader that's about to appear do so
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.presenceOfElementLocated(EDITOR_LOADER));
        } catch (Exception ignored) {
            // No loader appeared — editor may already be fully ready
        }

        // Phase 2: wait until all loaders are gone
        try {
            new WebDriverWait(driver, Duration.ofSeconds(30))
                    .until(d -> d.findElements(EDITOR_LOADER).stream()
                            .noneMatch(el -> {
                                try { return el.isDisplayed(); }
                                catch (StaleElementReferenceException e) { return false; }
                            }));
            long ms = System.currentTimeMillis() - start;
            LOG.info("[{}] Editor loader cleared in {}ms", context, ms);
            if (ms > 5000) {
                HealthTracker.get().recordSlowPage("EditorLoad/" + context, ms);
            }
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            LOG.warn("[{}] Editor loader still present after 30 s ({}ms) — continuing", context, ms);
            HealthTracker.get().recordSlowPage("EditorLoadTimeout/" + context, ms);
        }
    }
}
