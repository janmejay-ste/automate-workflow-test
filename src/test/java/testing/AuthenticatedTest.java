package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.testng.annotations.Test;
import utils.ManualLoginHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pages.DashboardPage;
import pages.ConnectEditorPage;
import pages.WaitUtils;
import org.testng.Assert;

@TestCategory(type = TestType.FULL, requiresLogin = true, feature = "Sanity Journey")
public class AuthenticatedTest extends BaseTest {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticatedTest.class);
    private static final String BASE_URL = "https://www.appypieautomate.ai";

    @Test(groups = {"full"})
    public void sanityUserJourney() {
        logger.info("=== Sanity: Login → Dashboard → Create Connect → Search Apps ===");

        DashboardPage dashboard = new DashboardPage(driver);
        ConnectEditorPage editor = new ConnectEditorPage(driver);
        WaitUtils vu = new WaitUtils(driver, java.time.Duration.ofSeconds(15));

        try {
            // 1. Login
            ManualLoginHelper.navigateAndLogin(driver);
            vu.dismissOverlays();

            // 2. Dashboard check
            if (!dashboard.isDashboardLoaded()) {
                vu.dismissOverlays();
                if (!dashboard.isDashboardLoaded() && !editor.isEditorVisible()) {
                    throw new RuntimeException("Neither dashboard nor editor loaded after login");
                }
            }
            logger.info("Dashboard loaded.");
            ManualLoginHelper.dumpDom(driver, "sanity_dashboard");

            // 3. Create Connect → Editor
            if (!editor.isEditorVisible()) {
                dashboard.clickCreateConnect();
                logger.info("Clicked Create Connect.");
            }

            // 4. Handle User Guide (wait for it to load, then complete it)
            vu.completeUserGuide();
            vu.dismissOverlays();

            Assert.assertTrue(editor.isEditorVisible(), "Editor should be visible");
            logger.info("Editor visible. Starting app selection flow.");

            // ─── TRIGGER APP ───
            // 5. Search & select trigger app
            editor.selectTriggerApp("Google Sheets");
            ManualLoginHelper.dumpDom(driver, "sanity_trigger_selected");

            // 6. Select trigger event
            editor.selectTriggerEvent("New Spreadsheet Row");

            // 7. Click Continue (after event selection)
            editor.clickContinue();

            // 8. Click Continue (second continue after loader)
            editor.clickContinue();

            // 9. Handle Setup step (Spreadsheet, Worksheet dropdowns — appears after 2nd
            // Continue)
            editor.handleSetupStep();

            // 10. Click Continue & Run Test
            editor.clickContinueRunTest();

            // ─── ACTION APP ───
            // 11. Click + (Add New Step) on canvas
            editor.clickAddNewStep();

            // 12. Click "Add App" button
            editor.clickAddApp();

            // 13. Search & select action app
            editor.selectActionApp("Gmail");
            ManualLoginHelper.dumpDom(driver, "sanity_action_selected");

            // 14. Select action event
            editor.selectActionEvent("Create Draft");

            // 15. Click Continue (after action event)
            //editor.clickContinue();
           // raw xpath removed
           editor.clickContinue();

            // 16. Click Continue (second continue after loader)
            // editor.clickContinue();
           // raw xpath removed
           editor.clickContinue();

            // 17. Handle Setup step for action (if present)
            editor.handleSetupStep();

            // 18. Click Continue & Run Test
            editor.clickContinueRunTest();

            // 19. Click "Activate Connect" (top-right) — only active if config is correct
            editor.clickActivateConnect();
            ManualLoginHelper.dumpDom(driver, "sanity_connect_activated");

            logger.info("=== Sanity Journey PASSED — Connect successfully set up ===");

        } catch (Exception e) {
            logger.error("Sanity flow failed: {}. Performing logout recovery.", e.getMessage());
            ManualLoginHelper.dumpDom(driver, "sanity_failure");

            // Recovery: logout → base URL
            try {
                String postLogoutUrl = dashboard.logoutAndVerifyRedirect();
                logger.info("Recovery logout done. Redirected to: {}", postLogoutUrl);
            } catch (Exception logoutEx) {
                logger.error("Logout recovery also failed: {}", logoutEx.getMessage());
                driver.get(BASE_URL);
                logger.info("Forced navigation to base URL.");
            }

            Assert.fail("Sanity Journey failed: " + e.getMessage());
        }
    }
}
