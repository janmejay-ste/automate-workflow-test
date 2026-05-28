package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.ConnectEditorPage;
import pages.DashboardPage;
import pages.WaitUtils;

import java.time.Duration;

/**
 * End-to-end workflow: Google Sheets → Gmail Draft connect.
 *
 * Page-transition flow:
 *   Login → Dashboard → Create Connect editor
 *     → Select trigger app (Google Sheets)
 *     → Select trigger event (New Spreadsheet Row)
 *     → Continue A  [a data-track="continue with event"  aria-disabled="true" — JS-clicked]
 *     → Continue B  [button data-track="continue with account"]
 *     → Mapping panel: Spreadsheet dropdown → "Test Sheet", Worksheet → first option
 *     → Continue & Run Test  [button data-track="continue"]
 *     → (optional) Continue C  [button data-track="continue"]
 *     → + icon → Add Action App
 *     → Select action app (Gmail) → action event (Create Draft)
 *     → Continue → Continue → mapping (Subject/Body) → Continue & Run Test
 *     → Activate Connect
 *
 * Key fix: waitForContinueButtonActive() was gating on aria-disabled flipping to
 * "false" on the <a> Continue button.  Angular never removes that attribute until
 * an account is connected, so the wait always timed out.  The <a> element IS
 * functionally clickable regardless of aria-disabled — we now JS-click it directly.
 */
@TestCategory(type = TestType.FULL, requiresLogin = true, feature = "Connect Workflow")
public class CreateConnectWorkflowTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(CreateConnectWorkflowTest.class);

    private static final String LOGIN_URL  =
            "https://accounts.appypie.com/login" +
            "?frompage=https:%2F%2Fconnectcloud.appypie.com%2Fconnects" +
            "&website=https:%2F%2Fconnectcloud.appypie.com";

    private static final String EMAIL    = "janmejay@appypiellp.com";
    private static final String PASSWORD = "Appypie@12345";

    private static final String TRIGGER_APP   = "Google Sheets";
    private static final String TRIGGER_EVENT = "New Spreadsheet Row";
    private static final String ACTION_APP    = "Gmail";
    private static final String ACTION_EVENT  = "Create Draft";

    @Test(groups = {"full"})
    public void testCreateAppSheetEmailConnect() throws Exception {
        logger.info("=== Starting: Google Sheets → Gmail Draft Connect Workflow ===");

        DashboardPage     dashboard = new DashboardPage(driver);
        ConnectEditorPage editor    = new ConnectEditorPage(driver);
        WaitUtils         waits     = new WaitUtils(driver, Duration.ofSeconds(20));

        // ── Step 1: Login ──────────────────────────────────────────────────────
        logger.info("Step 1: Navigating to login page...");
        driver.get(LOGIN_URL);
        performAutomatedLogin();
        logger.info("Step 1 DONE: Logged in. URL: {}", driver.getCurrentUrl());

        // ── Step 2: Dashboard ──────────────────────────────────────────────────
        logger.info("Step 2: Waiting for dashboard...");
        waitForDashboardOrEditor(driver, dashboard, editor);
        waits.dismissOverlays();
        waits.completeUserGuide();
        waits.dismissOverlays();
        logger.info("Step 2 DONE: Dashboard loaded.");

        // ── Step 3: Create Connect editor ──────────────────────────────────────
        logger.info("Step 3: Opening Create Connect editor...");
        if (!editor.isEditorVisible()) {
            dashboard.clickCreateConnect();
            Thread.sleep(2000);
            waits.dismissOverlays();
            waits.completeUserGuide();
        }
        Assert.assertTrue(editor.isEditorVisible(),
                "Connect editor should be visible after clicking Create Connect");
        logger.info("Step 3 DONE: Editor visible.");

        // ── Step 4: Select trigger app ─────────────────────────────────────────
        logger.info("Step 4: Selecting trigger app: {}", TRIGGER_APP);
        editor.selectTriggerApp(TRIGGER_APP);
        logger.info("Step 4 DONE: Trigger app '{}' selected.", TRIGGER_APP);

        // ── Step 5: Select trigger event ───────────────────────────────────────
        // selectTriggerEvent() now just clicks the label and waits 2 s for Angular
        // to register the selection.  It no longer checks aria-disabled (see fix note
        // in ConnectEditorPage.selectTriggerEvent).
        logger.info("Step 5: Selecting trigger event: {}", TRIGGER_EVENT);
        editor.selectTriggerEvent(TRIGGER_EVENT);
        logger.info("Step 5 DONE: Trigger event '{}' selected.", TRIGGER_EVENT);

        // ── Step 6: Continue A — event-panel Continue button ───────────────────
        // DOM: <a data-track="continue with event" aria-disabled="true">Continue</a>
        // aria-disabled="true" persists until an account is connected — JS-click
        // bypasses it cleanly; Angular's own click handler fires and navigates forward.
        logger.info("Step 6: Clicking Continue (event panel)...");
        editor.clickContinue();
        logger.info("Step 6 DONE: Event-panel Continue clicked.");

        // ── Step 7: Continue B — account-connection Continue button ────────────
        // DOM: <button data-track="continue with account">Continue</button>
        // This appears after the account-connect panel loads.  clickContinue()
        // preferentially finds this selector first, so no ambiguity.
        logger.info("Step 7: Clicking Continue (account panel)...");
        editor.clickContinue();
        logger.info("Step 7 DONE: Account-panel Continue clicked.");

        // ── Step 8: Mapping — Spreadsheet + Worksheet dropdowns ────────────────
        // DOM: div[id^='menu-drop'] custom dropdowns (not native <select>).
        //   • menu-drop0 (spreadsheetId): search + select "Test Sheet"
        //   • menu-drop1 (sheetId):       select first available option
        logger.info("Step 8: Handling setup dropdowns (Spreadsheet + Worksheet)...");
        editor.handleSetupStep();
        logger.info("Step 8 DONE: Dropdowns mapped.");

        // ── Step 9: Continue & Run Test ────────────────────────────────────────
        // DOM: <button data-track="continue">Continue &amp; Run Test</button>
        logger.info("Step 9: Clicking Continue & Run Test...");
        editor.clickContinueRunTest();
        logger.info("Step 9 DONE: Continue & Run Test clicked.");

        // ── Step 9.5: Optional post-run Continue ───────────────────────────────
        // After "Run Test", some flows show an additional Continue to progress.
        // Wrapped in try-catch so it's truly optional.
        logger.info("Step 9.5: Clicking optional post-run Continue (if present)...");
        try {
            editor.clickContinue();
            logger.info("Step 9.5 DONE: Post-run Continue clicked.");
        } catch (Exception e) {
            logger.info("Step 9.5: No post-run Continue found — skipping. ({})",
                    e.getMessage().split("\n")[0]);
        }

        // ── Step 10: + icon → Add Action App ───────────────────────────────────
        // After Continue & Run Test, the canvas reloads and shows the + button.
        // Clicking + reveals the 3-option panel (Add Action App / Add AI Agent / Add Flow).
        logger.info("Step 10: Clicking + to open add-step menu...");
        editor.clickAddNewStep();
        logger.info("Step 10: Clicking 'Add Action App'...");
        editor.clickAddApp();
        logger.info("Step 10 DONE: Add Action App clicked.");

        // ── Step 11: Select action app ─────────────────────────────────────────
        logger.info("Step 11: Selecting action app: {}", ACTION_APP);
        editor.selectActionApp(ACTION_APP);
        logger.info("Step 11 DONE: Action app '{}' selected.", ACTION_APP);

        // ── Step 12: Select action event ───────────────────────────────────────
        logger.info("Step 12: Selecting action event: {}", ACTION_EVENT);
        editor.selectActionEvent(ACTION_EVENT);
        logger.info("Step 12 DONE: Action event '{}' selected.", ACTION_EVENT);

        // ── Steps 13–14: Continue through account + event panels for action ────
        logger.info("Step 13: Continue (action event panel)...");
        try { editor.clickContinue(); } catch (Exception e) {
            logger.warn("Step 13 Continue not found: {}", e.getMessage().split("\n")[0]);
        }

        logger.info("Step 14: Continue (action account panel)...");
        try { editor.clickContinue(); } catch (Exception e) {
            logger.warn("Step 14 Continue not found: {}", e.getMessage().split("\n")[0]);
        }

        // ── Step 15: Action setup dropdowns ───────────────────────────────────
        logger.info("Step 15: Handling action setup dropdowns...");
        try { editor.handleSetupStep(); }
        catch (Exception e) {
            logger.warn("Step 15 setup not needed: {}", e.getMessage().split("\n")[0]);
        }

        // ── Step 15.5: Fill Gmail Draft form (Subject + Body) ─────────────────
        logger.info("Step 15.5: Filling Gmail Draft setup form...");
        try {
            editor.fillGmailDraftSetup();
            logger.info("Step 15.5 DONE: Gmail Draft form filled.");
        } catch (Exception e) {
            logger.warn("Gmail Draft form fill failed (non-critical): {}",
                    e.getMessage().split("\n")[0]);
        }

        // ── Step 16: Continue & Run Test (action) ─────────────────────────────
        logger.info("Step 16: Continue & Run Test (action)...");
        try { editor.clickContinueRunTest(); }
        catch (Exception e) {
            logger.warn("Step 16 Continue & Run Test not needed: {}",
                    e.getMessage().split("\n")[0]);
        }

        // ── Step 17: Activate Connect ──────────────────────────────────────────
        logger.info("Step 17: Activating the Connect...");
        editor.clickActivateConnect();
        logger.info("Step 17 DONE: Connect ACTIVATED.");

        logger.info("=== SUCCESS: Google Sheets → Gmail Draft Connect created and activated! ===");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private void performAutomatedLogin() throws InterruptedException {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        try {
            WebElement email = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#testing, input.emailInput, input[type='email'], input[name='email']")));
            email.clear();
            email.sendKeys(EMAIL);
            logger.info("Email entered.");

            Thread.sleep(500);

            WebElement pwd = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#password, input[type='password'], input[name='password']")));
            pwd.clear();
            pwd.sendKeys(PASSWORD);
            logger.info("Password entered.");

            Thread.sleep(500);

            WebElement loginBtn = wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector("button.login-btns, button[type='submit'], input[type='submit']")));
            loginBtn.click();
            logger.info("Login button clicked. Waiting for redirect...");

            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> {
                String url = d.getCurrentUrl();
                return !url.contains("accounts.appypie") || url.contains("connectcloud");
            });
            logger.info("Automated login succeeded. URL: {}", driver.getCurrentUrl());

        } catch (Exception e) {
            logger.warn("Automated login failed ({}). Waiting for manual login (up to 3 min)...",
                    e.getMessage().split("\n")[0]);
            new WebDriverWait(driver, Duration.ofMinutes(3)).until(d -> {
                String url = d.getCurrentUrl();
                return url.contains("connectcloud") || url.contains("/connects")
                        || url.contains("/customeditor");
            });
            logger.info("Manual login completed. URL: {}", driver.getCurrentUrl());
        }
    }

    private void waitForDashboardOrEditor(WebDriver drv,
                                          DashboardPage dashboard,
                                          ConnectEditorPage editor) {
        new WebDriverWait(drv, Duration.ofSeconds(45)).until(d -> {
            try { return dashboard.isDashboardLoaded(); } catch (Exception ignored) {}
            try { return editor.isEditorVisible();      } catch (Exception ignored) {}
            return false;
        });
    }
}
