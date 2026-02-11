package testing;

import base.BaseTest;
import org.testng.annotations.Test;
import utils.ManualLoginHelper;
import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pages.DashboardPage;
import pages.ConnectEditorPage;
import org.testng.Assert;

public class AuthenticatedTest extends BaseTest {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticatedTest.class);

    @Test(groups = "sanity")
    public void testUserGuideFlow() {
        logger.info("Starting User Guide Flow Verification");
        ManualLoginHelper.navigateAndLogin(driver);
        DashboardPage dashboard = new DashboardPage(driver);
        pages.WaitUtils vu = new pages.WaitUtils(driver, java.time.Duration.ofSeconds(15));

        if (!dashboard.isDashboardLoaded()) {
            logger.info("Dashboard not immediate, checking for guide/overlays...");
            vu.completeUserGuide();
        }

        dashboard.clickCreateConnect();
        logger.info("Following User Guide in Editor...");
        vu.completeUserGuide();

        ConnectEditorPage editor = new ConnectEditorPage(driver);
        Assert.assertTrue(editor.isEditorVisible(), "Editor should be visible after following guide");
    }

    @Test(groups = "sanity")
    public void sanityUserJourney() {
        logger.info("Starting Sanity User Journey (Login + Create Connect)");

        // 1. Login Flow
        ManualLoginHelper.navigateAndLogin(driver);

        DashboardPage dashboard = new DashboardPage(driver);
        ConnectEditorPage editor = new ConnectEditorPage(driver);

        // 2. Handle Landing State (Dashboard or Editor)
        pages.WaitUtils vu = new pages.WaitUtils(driver, java.time.Duration.ofSeconds(10));
        vu.dismissOverlays(); // Clear pre-landing overlays (like the tour)

        boolean inEditor = false;
        try {
            inEditor = editor.isEditorVisible();
            logger.info("Editor visibility check: {}", inEditor);
        } catch (Exception e) {
            logger.info("Editor check timed out, continuing to dashboard check...");
        }

        if (!inEditor) {
            // 3. Dashboard Verification & Navigation
            if (!dashboard.isDashboardLoaded()) {
                logger.warn("Dashboard not loaded, performing recovery overlay dismissal...");
                vu.dismissOverlays();

                // Re-check after dismissal
                if (!dashboard.isDashboardLoaded()) {
                    // Try one last check for editor (sometimes it lands there after dismissal)
                    if (editor.isEditorVisible()) {
                        logger.info("Detected Editor presence after overlay dismissal. Continuing flow.");
                    } else {
                        Assert.fail("Dashboard should be loaded after login/recovery");
                    }
                }
            }

            logger.info("Extracting landing DOM...");
            ManualLoginHelper.dumpDom(driver, "sanity_dashboard_landing");

            // 4. Navigate to 'Create Connect'
            // If we are already in the editor (lightning fast load or direct land after
            // overlay), skip click
            if (!editor.isEditorVisible()) {
                dashboard.clickCreateConnect();
            } else {
                logger.info("Already in editor after verification. Skipping click.");
            }
        } else {
            logger.info("Detected direct landing in Editor. Skipping dashboard navigation.");
            ManualLoginHelper.dumpDom(driver, "sanity_editor_landing");
        }

        // 5. Final Editor Verification & Interaction
        try {
            if (editor.isEditorVisible()) {
                logger.info("Editor visible, following potential guides...");
                vu.completeUserGuide();
                logger.info("Performing full app selection flow.");
                performFullFlow(editor);
            } else {
                logger.warn("Editor not visible, attempting direct URL navigation...");
                driver.get("https://connectcloud.appypie.com/customeditor/fresh/app/newNode");
                try {
                    vu.waitForVisible(By.cssSelector("app-custom-editor, #connectName, input[placeholder*='trigger']"));
                    vu.dismissOverlays();
                } catch (Exception e) {
                    logger.warn("Direct navigation wait/dismiss failed: {}", e.getMessage());
                }

                if (editor.isEditorVisible()) {
                    logger.info("Editor visible after direct navigation, performing flow.");
                    performFullFlow(editor);
                } else {
                    logger.warn("Could not load Editor (reached fallback navigation). Capturing DOM...");
                    ManualLoginHelper.dumpDom(driver, "failure_editor_load");
                    throw new RuntimeException("Editor load failed");
                }
            }
        } catch (Exception e) {
            logger.error("Flow interrupted at step: {}. Performing emergency logout fallback.", e.getMessage());
            // Fallback requirement: navigate to dashboard and logout
            driver.get("https://connectcloud.appypie.com/connects");
            dashboard.logout();
            Assert.fail("Sanity Journey failed: " + e.getMessage());
        }

        logger.info("Sanity User Journey completed successfully.");
    }

    private void performFullFlow(ConnectEditorPage editor) {
        // Step 1: Search & Select Trigger App
        editor.selectTriggerApp("Google Sheets");
        ManualLoginHelper.dumpDom(driver, "sanity_editor_trigger_selection");

        // Step 2: Select Trigger Event & Continue
        editor.selectTriggerEvent("New Spreadsheet Row");
        editor.clickContinue();

        // Step 3: Run Test / Continue
        editor.clickContinueRunTest();

        // Step 4: Search & Select Action App
        editor.selectActionApp("Gmail");
        ManualLoginHelper.dumpDom(driver, "sanity_editor_action_selection");

        // Step 5: Select Action & Continue
        editor.selectActionEvent("Send Email");
        editor.clickContinue();

        // Step 6: Activate Connect
        editor.clickActivateConnect();
        ManualLoginHelper.dumpDom(driver, "sanity_editor_final_activation");
    }
}
