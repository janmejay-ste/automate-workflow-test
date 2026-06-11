package utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import pages.auth.AuthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

public class ManualLoginHelper {
    private static final Logger logger = LoggerFactory.getLogger(ManualLoginHelper.class);
    private static final Duration MANUAL_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Standard login: navigates to dashboard URL.
     */
    public static void navigateAndLogin(WebDriver driver) {
        navigateAndLogin(driver, "https://connectcloud.appypie.com/connects");
    }

    /**
     * Overload: navigates to a custom start URL (e.g. an app-pair integration page)
     * then waits for manual login to complete.
     */
    public static void navigateAndLogin(WebDriver driver, String startUrl) {
        logger.info("Navigating to start URL: {}", startUrl);
        driver.get(startUrl);

        if (AuthState.hasSession(driver)) {
            logger.info("Existing session detected. Skipping manual login.");
            return;
        }

        // Attempt automated login first (env vars take precedence).
        String autoUser = System.getenv("AUTOMATION_LOGIN_USER");
        String autoPass = System.getenv("AUTOMATION_LOGIN_PASS");
        if (autoUser == null || autoPass == null) {
            // Fallback to provided credentials when env vars are not set
            autoUser = "janmejay@appypiellp.com";
            autoPass = "Appypie@12345";
        }

        logger.info("Login required. Attempting automated login for user: {}", autoUser);
        try {
            tryAutomatedLogin(driver, autoUser, autoPass);
            waitForPostLoginReady(driver);
            logger.info("Post-login page loaded! Proceeding with automation.");
            return;
        } catch (Exception ex) {
            logger.warn("Automated login attempt failed: {}. Falling back to manual wait.", ex.getMessage());
        }

        logger.info("Waiting for manual intervention (Timeout: 3 minutes)...");
        try {
            waitForManualLogin(driver);
            // After login, user might land on dashboard OR directly in editor
            waitForPostLoginReady(driver);
            logger.info("Post-login page loaded! Proceeding with automation.");
        } catch (TimeoutException e) {
            logger.error("Timed out waiting for manual login or post-login readiness.");
            throw e;
        }
    }

    private static void tryAutomatedLogin(WebDriver driver, String user, String pass) {
        // Attempt to find common email and password input patterns present in the login page
        WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(10));

        // Some entry points (notably the GoHighLevel prompt-builder
        // "Build my GoHighLevel workflow" → /register redirect) land on an
        // Angular signup form that has a "Login" toggle link to switch panes.
        // Without the toggle click, the email/password inputs we look for below
        // are still the SIGNUP inputs — credentials get submitted to the wrong
        // endpoint. Clicking the toggle (when it's present) switches the form
        // to the login pane; on entry points that already default to login,
        // the toggle isn't rendered and this step is a silent no-op.
        switchToLoginPaneIfSignupShown(driver);

        // Locate email input: try by id/name/placeholder
        By[] emailSelectors = new By[] {
                By.id("testing"),
                By.name("testing"),
                By.cssSelector("input[placeholder*='Email']"),
                By.cssSelector("input[placeholder*='Email Address']"),
                By.cssSelector("input[type='text'].emailInput"),
        };

        WebElement emailEl = null;
        for (By s : emailSelectors) {
            try {
                emailEl = shortWait.until(ExpectedConditions.elementToBeClickable(s));
                if (emailEl != null) break;
            } catch (Exception e) {
                // ignore and try next
            }
        }

        if (emailEl == null) throw new RuntimeException("Email input not found for automated login");
        emailEl.clear();
        emailEl.sendKeys(user);

        // Locate password input
        By[] passSelectors = new By[] {
                By.id("password"),
                By.cssSelector("input[type='password']"),
        };
        WebElement passEl = null;
        for (By s : passSelectors) {
            try {
                passEl = shortWait.until(ExpectedConditions.elementToBeClickable(s));
                if (passEl != null) break;
            } catch (Exception e) {
                // ignore
            }
        }
        if (passEl == null) throw new RuntimeException("Password input not found for automated login");
        passEl.clear();
        passEl.sendKeys(pass);

        // Try to submit: look for common submit buttons (.login-btns, button[type=submit], input[type=submit]), otherwise press ENTER
        try {
            WebElement submit = null;
            By[] submitSelectors = new By[] {
                    By.cssSelector("button[type='submit']"),
                    By.cssSelector("button.login-btns"),
                    By.cssSelector(".login-btns"),
                    By.cssSelector("input[type='submit']"),
            };
            for (By s : submitSelectors) {
                try {
                    submit = driver.findElement(s);
                    if (submit != null && submit.isDisplayed()) break;
                } catch (Exception e) {
                    submit = null;
                }
            }

            if (submit != null && submit.isDisplayed()) {
                submit.click();
            } else {
                passEl.sendKeys(Keys.ENTER);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit login form: " + e.getMessage());
        }
    }

    /**
     * If the auth page is rendering the signup pane instead of the login pane
     * (some entry points such as the GoHighLevel prompt-builder "Build my workflow"
     * redirect default to signup), clicks the "Login" toggle link to switch to the
     * login pane. Silent no-op when the toggle is not present or not visible — most
     * entry points already default to login.
     *
     * <p>Selector strategy:</p>
     * <ol>
     *   <li>Exact match: {@code a.cursor-pointer-new} with normalised text "Login"
     *       (the Angular template class on the toggle link).</li>
     *   <li>Loose fallback: any {@code <a>} on the page whose text is "Login"
     *       (covers minor class renames without breaking the test).</li>
     * </ol>
     *
     * <p>After clicking, waits up to 3 s for an email input to appear, so the
     * caller's locator chain has the right form rendered.</p>
     */
    private static void switchToLoginPaneIfSignupShown(WebDriver driver) {
        java.util.List<By> toggleSelectors = java.util.List.of(
                By.xpath("//a[contains(@class,'cursor-pointer-new') and normalize-space(.)='Login']"),
                By.xpath("//a[normalize-space(.)='Login' and not(contains(@href,'/'))]")
        );
        for (By sel : toggleSelectors) {
            try {
                java.util.List<WebElement> found = driver.findElements(sel);
                for (WebElement el : found) {
                    if (!el.isDisplayed()) continue;
                    try { el.click(); }
                    catch (Exception ex) {
                        ((org.openqa.selenium.JavascriptExecutor) driver)
                                .executeScript("arguments[0].click();", el);
                    }
                    logger.info("Auth pane: clicked 'Login' toggle to switch from signup form");
                    // Give the Angular form-swap a moment, then verify an email input is present
                    try {
                        new WebDriverWait(driver, Duration.ofSeconds(3)).until(d -> {
                            for (By emailSel : new By[] {
                                    By.id("testing"),
                                    By.cssSelector("input[placeholder*='Email']"),
                                    By.cssSelector("input.emailInput")
                            }) {
                                java.util.List<WebElement> e = d.findElements(emailSel);
                                if (!e.isEmpty() && e.get(0).isDisplayed()) return true;
                            }
                            return false;
                        });
                    } catch (TimeoutException te) {
                        // Pane swap didn't surface an email input in 3 s — let the caller's
                        // own retry loop deal with it. We've done our part.
                        logger.warn("Auth pane: clicked Login toggle but email input did not appear within 3s");
                    }
                    return;
                }
            } catch (Exception ignored) {}
        }
        // No toggle present — the page is already on the login pane, or the
        // signup-only entry point this method targets isn't in play. Nothing to do.
    }

    public static void waitForManualLogin(WebDriver driver) {
        new WebDriverWait(driver, MANUAL_TIMEOUT).until(d -> {
            String url = d.getCurrentUrl();
            boolean hasSession = AuthState.hasSession(d);
            boolean onDashboard = url.contains("/connects") || url.contains("connectcloud");
            boolean onEditor = url.contains("/customeditor");

            // Fallback: Check for visible dashboard or editor elements
            boolean dashboardElementVisible = false;
            boolean editorElementVisible = false;
            try {
                dashboardElementVisible = d.findElements(By.xpath("//*[contains(text(), 'My Connects')]")).size() > 0;
                editorElementVisible = d
                        .findElements(By.cssSelector("app-custom-editor, #connectName, .canvas-container")).size() > 0;
            } catch (Exception e) {
                // Ignore errors during check
            }

            if (hasSession && (onDashboard || onEditor)) {
                logger.info("Session found! URL: {}, Dashboard: {}, Editor: {}",
                        url, dashboardElementVisible, editorElementVisible);
                return true;
            }
            if (dashboardElementVisible || editorElementVisible) {
                logger.info("Post-login element detected! URL: {}", url);
                return true;
            }
            return false;
        });
    }

    /**
     * Waits for either dashboard or editor to become ready after login.
     */
    public static void waitForPostLoginReady(WebDriver driver) {
        logger.info("Waiting for post-login readiness (dashboard or editor)...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(45));
        wait.until(ExpectedConditions.or(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("app-sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".main-sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".sidebar")),
                ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(), 'My Connects')]")),
                ExpectedConditions.visibilityOfElementLocated(
                        By.cssSelector("app-custom-editor, #connectName, .canvas-container"))));
    }

    public static void dumpDom(WebDriver driver, String label) {
        try {
            String pageSource = driver.getPageSource();
            Path dir = Paths.get("reports/dom_dumps");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String fileName = "dom_" + label + "_" + System.currentTimeMillis() + ".html";
            Path filePath = dir.resolve(fileName);
            Files.writeString(filePath, pageSource);
            logger.info("DOM dumped successfully to: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to dump DOM: {}", e.getMessage());
        }
    }
}
