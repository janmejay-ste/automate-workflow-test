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
 * Creates a Connect workflow on production (connectcloud.appypie.com):
 *   Trigger App  : Google Sheets
 *   Trigger Event: New Spreadsheet Row
 *   Action App   : Gmail
 *   Action Event : Draft
 * Then activates the connect.
 *
 * Login is performed automatically using the supplied credentials.
 */
@TestCategory(type = TestType.FULL, requiresLogin = true, feature = "Connect Workflow")
public class CreateConnectWorkflowTest extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(CreateConnectWorkflowTest.class);

    // ── Credentials ──────────────────────────────────────────────────────────────
    private static final String LOGIN_URL  = "https://accounts.appypie.com/login";
    private static final String EMAIL      = "vexeluserflowtest099@yopmail.com";
    private static final String PASSWORD   = "Test@123";

    // ── Workflow parameters ───────────────────────────────────────────────────────
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

        // ── Step 1: Automated Login ────────────────────────────────────────────────
        logger.info("Step 1: Navigating to login page: {}", LOGIN_URL);
        driver.get(LOGIN_URL);
        performAutomatedLogin();
        logger.info("Step 1 DONE: Login flow completed.");

        // ── Step 2: Wait for dashboard ─────────────────────────────────────────────
        logger.info("Step 2: Waiting for dashboard to load...");
        waitForDashboardOrEditor(driver, dashboard, editor);
        waits.dismissOverlays();
        waits.completeUserGuide();
        waits.dismissOverlays();
        logger.info("Step 2 DONE: Dashboard loaded.");

        // ── Step 3: Open Create Connect editor ────────────────────────────────────
        logger.info("Step 3: Opening Create Connect editor...");
        if (!editor.isEditorVisible()) {
            dashboard.clickCreateConnect();
            Thread.sleep(2000);
            waits.dismissOverlays();
            waits.completeUserGuide();
        }
        Assert.assertTrue(editor.isEditorVisible(), "Connect editor should be visible after clicking Create Connect");
        logger.info("Step 3 DONE: Editor is visible.");

        // ── Step 4: Select Trigger App ────────────────────────────────────────────
        logger.info("Step 4: Selecting trigger app: {}", TRIGGER_APP);
        editor.selectTriggerApp(TRIGGER_APP);
        logger.info("Step 4 DONE: Trigger app '{}' selected.", TRIGGER_APP);

        // ── Step 5: Select Trigger Event ──────────────────────────────────────────
        logger.info("Step 5: Selecting trigger event: {}", TRIGGER_EVENT);
        editor.selectTriggerEvent(TRIGGER_EVENT);
        logger.info("Step 5 DONE: Trigger event '{}' selected.", TRIGGER_EVENT);

        // ── Step 6: Continue (after trigger event) ────────────────────────────────
        logger.info("Step 6: Clicking Continue (after trigger event)...");
        editor.clickContinue();

        // ── Step 7: Continue (second continue / account connect) ──────────────────
        logger.info("Step 7: Clicking Continue (second - after loader)...");
        editor.clickContinue();

        // ── Step 8: Handle optional Setup step ────────────────────────────────────
        logger.info("Step 8: Handling optional Mandatory dropdowns...");
        editor.handleSetupStep();

        // ── Step 9: Continue & Run Test ───────────────────────────────────────────
        logger.info("Step 9: Clicking Continue & Run Test...");
        editor.clickContinueRunTest();
// -- Extra but mandatory Step Continue Button  ───────────────────────────────────────────
logger.info("Step 9.5: Clicking Continue (after Run Test)...");
editor.clickContinue();


        // ── Step 10: Add Action App ───────────────────────────────────────────────
        logger.info("Step 10: Clicking + to add action app...");
        editor.clickAddNewStep();
        // It will open + to setup Further connect Action App with Trigger
        editor.clickAddApp();

        // ── Step 11: Select Action App ────────────────────────────────────────────
        logger.info("Step 11: Selecting action app: {}", ACTION_APP);
        editor.selectActionApp(ACTION_APP);
        logger.info("Step 11 DONE: Action app '{}' selected.", ACTION_APP);

        // ── Step 12: Select Action Event ──────────────────────────────────────────
        logger.info("Step 12: Selecting action event: {}", ACTION_EVENT);
        editor.selectActionEvent(ACTION_EVENT);
        logger.info("Step 12 DONE: Action event '{}' selected.", ACTION_EVENT);

        // ── Steps 13-16: Continue/Setup/Run Test for action ──────────────
        logger.info("Step 13: Attempting Continue (after action event) - It is needed...");
        try { editor.clickContinue(); } catch (Exception ignored) {}

        logger.info("Step 14: Attempting Continue (second/account connect)...");
        try { editor.clickContinue(); } catch (Exception ignored) {}

        logger.info("Step 15: Handling action setup dropdowns...");
        try { editor.handleSetupStep(); } catch (Exception ignored) {}

        // ── Step 15.5: Fill Gmail Draft setup form (Subject + Body mapping) ───────
        logger.info("Step 15.5: Filling Gmail Draft setup form...");
        try {
            editor.fillGmailDraftSetup();
            logger.info("Step 15.5 DONE: Gmail Draft form filled.");
        } catch (Exception e) {
            logger.warn("Gmail Draft form fill failed (non-critical): {}", e.getMessage().split("\n")[0]);
        }

        logger.info("Step 16: Attempting Continue & Run Test (action) - may not be needed...");
        try { editor.clickContinueRunTest(); } catch (Exception ignored) {}

        // ── Step 17: Activate Connect ──────────────────────────────────────────────
        logger.info("Step 17: Activating the Connect...");
        editor.clickActivateConnect();
        logger.info("Step 17 DONE: Connect ACTIVATED.");

        logger.info("=== SUCCESS: Google Sheets → Gmail Draft Connect created and activated! ===");
    }

    /** Tries to click Continue but swallows any failure (step may not be needed). */
    private void tryClickContinue(ConnectEditorPage editor) {
        try {
            editor.clickContinue();
        } catch (Exception e) {
            logger.info("Continue not found at this step (skipping): {}", e.getMessage().split("\n")[0]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Fills in email + password and submits the login form automatically.
     * Falls back to waiting up to 3 minutes for manual completion if the automated
     * fill fails (e.g. CAPTCHA / SSO redirect).
     */
    private void performAutomatedLogin() throws InterruptedException {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        try {
            // Wait for email field — the page uses id="testing" (not "email"!)
            WebElement emailField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#testing, input.emailInput, input[type='email'], input[name='email']")));
            emailField.clear();
            emailField.sendKeys(EMAIL);
            logger.info("Email entered.");

            Thread.sleep(500);

            // Fill password — id="password"
            WebElement passwordField = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector("#password, input[type='password'], input[name='password']")));
            passwordField.clear();
            passwordField.sendKeys(PASSWORD);
            logger.info("Password entered.");

            Thread.sleep(500);

            // Click Login button — class="login-btns"
            WebElement loginBtn = wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector("button.login-btns, button[type='submit'], input[type='submit']")));
            loginBtn.click();
            logger.info("Login button clicked. Waiting for redirect...");

            // Wait for redirect away from login page to production URL
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> {
                String url = d.getCurrentUrl();
                return !url.contains("accounts.appypie") || url.contains("connectcloud");
            });
            logger.info("Automated login succeeded. Current URL: {}", driver.getCurrentUrl());

        } catch (Exception e) {
            logger.warn("Automated login flow failed ({}). Waiting for manual login (up to 3 min)...", e.getMessage());
            // Fallback: wait for the user to complete login manually
            new WebDriverWait(driver, Duration.ofMinutes(3)).until(d -> {
                String url = d.getCurrentUrl();
                return url.contains("connectcloud") || url.contains("/connects") || url.contains("/customeditor");
            });
            logger.info("Manual login completed. URL: {}", driver.getCurrentUrl());
        }
    }

    /**
     * Waits for either the dashboard or the editor to become ready after login.
     */
    private void waitForDashboardOrEditor(WebDriver drv, DashboardPage dashboard, ConnectEditorPage editor) {
        new WebDriverWait(drv, Duration.ofSeconds(45)).until(d -> {
            try { return dashboard.isDashboardLoaded(); } catch (Exception ignored) {}
            try { return editor.isEditorVisible(); } catch (Exception ignored) {}
            return false;
        });
    }
}
