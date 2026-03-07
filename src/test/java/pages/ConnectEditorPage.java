package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class ConnectEditorPage {
    private static final Logger logger = LoggerFactory.getLogger(ConnectEditorPage.class);
    private final WebDriver driver;
    private final WaitUtils waits;

    // Trigger/Action app search input on the right panel
    private final By appSearchInput = By.cssSelector(
            "input[placeholder*='trigger'], input[placeholder*='Trigger'], " +
                    "input[placeholder*='action'], input[placeholder*='Action'], " +
                    "input[placeholder*='Search'], input[placeholder*='search'], " +
                    "input.form-control[placeholder*='app']");

    // Dropdown results for app selection (Angular grid: each app is an <a class="ng-star-inserted"> with <p class="app-name">)
    private final By dropdownItems = By.cssSelector("a.ng-star-inserted, li .landing-apps a, ul.dropdown-ai button");

    public ConnectEditorPage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(20));
    }

    public boolean isEditorVisible() {
        try {
            String selector = "app-custom-editor, #page-container-customeditor, .canvas-container, #triggerInput, .editor-header, #connectName, div.undo-redo-buttons, f-canvas, f-flow";
            java.util.List<WebElement> elements = driver.findElements(By.cssSelector(selector));
            for (WebElement el : elements) {
                if (el.isDisplayed())
                    return true;
            }
            // Fallback: use JavaScript to check for Angular custom elements
            // (useful when elements aren't yet in Selenium's normal DOM tree)
            Object jsResult = ((org.openqa.selenium.JavascriptExecutor)driver).executeScript(
                "return !!(document.querySelector('f-canvas') || " +
                "document.querySelector('div.undo-redo-buttons') || " +
                "document.getElementById('connectName') || " +
                "document.querySelector('f-flow'));"
            );
            return Boolean.TRUE.equals(jsResult);
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Undo/Redo Test Functionality ───
    public void testUndoRedoFunctionality() {
        logger.info("Testing Undo and Redo functionality...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        
        By undoBtn = By.xpath("//button[@data-tooltip='Undo']");
        By redoBtn = By.xpath("//button[@data-tooltip='Redo']");
        
        WebElement undo = wait.until(ExpectedConditions.presenceOfElementLocated(undoBtn));
        // Assert Undo is enabled after App Selection
        wait.until(d -> undo.isEnabled() && undo.getAttribute("disabled") == null);
        logger.info("Undo button is enabled. Clicking Undo...");
        try { undo.click(); } catch (Exception e) { jsClick(undo); }
        waitShort(1500);
        
        WebElement redo = wait.until(ExpectedConditions.presenceOfElementLocated(redoBtn));
        // Assert Redo is enabled after Undo
        wait.until(d -> redo.isEnabled() && redo.getAttribute("disabled") == null);
        logger.info("Redo button is enabled after Undo. Clicking Redo...");
        try { redo.click(); } catch (Exception e) { jsClick(redo); }
        waitShort(1500);
        
        logger.info("Undo/Redo functionality test passed successfully.");
    }

    // ─── Step 1: Search & select trigger app ───
    public void selectTriggerApp(String appName) {
        logger.info("Selecting trigger app: {}", appName);
        WebElement input = waits.waitForVisible(appSearchInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    // ─── Step 2: Select trigger/action event from the right panel ───
    // Tries multiple ancestor selectors to handle different app event card structures
    public void selectTriggerEvent(String eventName) {
        logger.info("Selecting trigger event: {}", eventName);

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        WebElement card = null;

        // Strategy 1: span text → nearest div with 'landing' or 'card' class
        String[] ancestorClasses = {
                "landing", "card", "event-item", "trigger-event", "action-event",
                "item", "list-item", "option", "event-option", "event-card"
        };

        for (String cls : ancestorClasses) {
            try {
                By locator = By.xpath(
                        "//span[normalize-space()='" + eventName + "']" +
                                "/ancestor::div[contains(@class,'" + cls + "')][1]");
                card = new WebDriverWait(driver, Duration.ofSeconds(5))
                        .until(ExpectedConditions.visibilityOfElementLocated(locator));
                logger.info("Found event card via ancestor class '{}': {}", cls, eventName);
                break;
            } catch (Exception ignored) {}
        }

        // Strategy 2: any clickable ancestor div (first parent within 3 levels)
        if (card == null) {
            try {
                By locator = By.xpath(
                        "//span[normalize-space()='" + eventName + "']/ancestor::div[1]");
                card = new WebDriverWait(driver, Duration.ofSeconds(10))
                        .until(ExpectedConditions.visibilityOfElementLocated(locator));
                logger.info("Found event card via first ancestor div: {}", eventName);
            } catch (Exception ignored) {}
        }

        // Strategy 3: li parent
        if (card == null) {
            try {
                By locator = By.xpath(
                        "//span[normalize-space()='" + eventName + "']/ancestor::li[1]");
                card = new WebDriverWait(driver, Duration.ofSeconds(10))
                        .until(ExpectedConditions.visibilityOfElementLocated(locator));
                logger.info("Found event card via li parent: {}", eventName);
            } catch (Exception ignored) {}
        }

        // Strategy 4: click the span itself (last resort)
        if (card == null) {
            logger.warn("Falling back to clicking span directly for event: {}", eventName);
            By spanLocator = By.xpath("//span[normalize-space()='" + eventName + "']");
            card = wait.until(ExpectedConditions.visibilityOfElementLocated(spanLocator));
        }

        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", card);

        try {
            card.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", card);
        }
        logger.info("Trigger event selected successfully: {}", eventName);

        waits.waitForLoader();
        waitShort(1000);
    }

    // ─── Step 2.5: Handle Setup step (dropdowns: Spreadsheet, Worksheet, etc.) ───
    // This step may or may not appear depending on the app. If present, select
    // first available option from each required dropdown.
    public void handleSetupStep() {
        logger.info("Checking for Setup step (dropdown fields)...");
        // Wait for section containing "Set Up" or "Setup"
        By setupHeader = By.xpath("//*[contains(translate(text(), 'SETUP', 'setup'), 'set up') or contains(translate(text(), 'SETUP', 'setup'), 'setup')]");
        
        // Setup dropdowns could be custom inputs or multiple-menu
        By menuDropdowns = By.cssSelector(
            "div.multiple-menu div.menu, " +
            "div.choices[data-type='select-one'], " +
            "app-dynamic-dropdown, " +
            ".form-control[placeholder*='Select'], " +
            ".cutomdropdown, " +
            "div[class*='dropdown-container']"
        );

        // Wait up to 5 seconds for setup section to appear
        boolean hasSetup = false;
        for (int i = 0; i < 10; i++) {
            if (!driver.findElements(setupHeader).isEmpty() || !driver.findElements(menuDropdowns).isEmpty()) {
                hasSetup = true;
                break;
            }
            waitShort(500);
        }

        if (!hasSetup) {
            logger.info("No Setup step detected. Skipping.");
            return;
        }

        logger.info("Setup section found. Filling dropdowns...");
        long setupStart = System.currentTimeMillis();
        java.util.List<WebElement> dropdowns = driver.findElements(menuDropdowns);
        logger.info("Found {} dropdown fields in setup.", dropdowns.size());

        for (int i = 0; i < dropdowns.size(); i++) {
            long dropdownStart = System.currentTimeMillis();
            WebElement dropdown = dropdowns.get(i);
            
            // Click on the dropdown toggle (menu_icon-box or the menu div itself)
            WebElement toggle = dropdown.findElement(By.cssSelector(".menu_icon-box, .menu_icon"));
            logger.info("Opening dropdown #{}", i);
            try {
                toggle.click();
            } catch (Exception ce) {
                jsClick(toggle);
            }
            waitShort(1000); // wait for dropdown options to load

            // Look for option items inside the dropdown list
            By optionItems = By.cssSelector(
                    "div.menu_dropdown ul li, div.menu_dropdown .option-item, div.menu_dropdown a, div.menu_dropdown .ng-star-inserted");
            java.util.List<WebElement> options = dropdown.findElements(optionItems);

            if (options.isEmpty()) {
                logger.warn("Dropdown #{} has no options loaded. Waiting more...", i);
                waitShort(1500);
                options = dropdown.findElements(optionItems);
            }

            // Select first visible option
            boolean selected = false;
            for (WebElement opt : options) {
                String text = opt.getText().trim();
                if (opt.isDisplayed() && !text.isEmpty()
                        && !text.equalsIgnoreCase("Search...")
                        && !text.equalsIgnoreCase("Refresh Data")
                        && !text.equalsIgnoreCase("Clear Selection")) {
                    logger.info("Dropdown #{}: Selecting '{}'", i, text);
                    try {
                        opt.click();
                    } catch (Exception ce) {
                        jsClick(opt);
                    }
                    selected = true;
                    waitShort(800);
                    break;
                }
            }

            if (!selected) {
                throw new RuntimeException("Dropdown #" + i + ": Could not select any option. Setup failed.");
            }
            logger.info("Dropdown #{} mapped in {} ms", i, (System.currentTimeMillis() - dropdownStart));
        }

        long setupEnd = System.currentTimeMillis();
        logger.info("Setup step handling complete. Total time: {} ms", (setupEnd - setupStart));
        waitShort(1000);
    }

    // ─── Step 3: Click "Continue" button (after event selection / setup) ───
    public void clickContinue() {
        logger.info("Clicking Continue...");
        long start = System.currentTimeMillis();

        By continueBtn = By.cssSelector(
            "button[data-track='continue'], " +
            "a[data-track='continue with event'], " +
            "button[data-track='continue with account']"
        );

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(8));

        // Wait only for presence (fast)
        WebElement btn = wait.until(
            ExpectedConditions.presenceOfElementLocated(continueBtn)
        );

        // Fail immediately if disabled
        if (!btn.isEnabled()) {
            throw new RuntimeException(
                "Continue button is DISABLED — configuration incomplete."
            );
        }

        // Scroll into view (prevents intercept issues)
        ((JavascriptExecutor) driver)
            .executeScript("arguments[0].scrollIntoView({block:'center'});", btn);

        btn.click();

        // Wait only for loader to disappear (not fixed sleep)
        waits.waitForLoader();

        long end = System.currentTimeMillis();
        logger.info("Continue execution time: {} ms", (end - start));
    }

    // ─── Step 4: Click "Continue & Run Test" button ───
    // Fails immediately if button is disabled (form incomplete) rather than waiting for timeout.
    public void clickContinueRunTest() {
        logger.info("Clicking Continue (Run Test) button...");
        long start = System.currentTimeMillis();
        
        By continueRunBtn = By.cssSelector("button[data-track='continue']");
        WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(15));

        // Wait for the button to appear (not necessarily enabled yet)
        WebElement btn = shortWait.until(ExpectedConditions.presenceOfElementLocated(continueRunBtn));

        // Fail immediately if it's still disabled — no point waiting for timeout
        if (!btn.isEnabled() || "true".equals(btn.getAttribute("disabled"))) {
            throw new RuntimeException(
                "Continue & Run Test button is DISABLED — form mapping is incomplete. Check Subject/Body fields.");
        }

        btn.click();
        logger.info("Continue (Run Test) clicked. Waiting for loader...");
        waits.waitForLoader();
        waitShort(1000);
        
        long end = System.currentTimeMillis();
        logger.info("Continue (Run Test) execution time: {} ms", (end - start));
    }

    // ─── Step 5: Click "+" button on canvas ───
    // DOM: <button id="add-new-step-button" class="... blue-plus-btn ...">
    public void clickAddNewStep() {
        logger.info("Clicking + (Add New Step) button on canvas...");
        By plusBtn = By.cssSelector(
                "#add-new-step-button, " +
                        "button[data-tooltip='Add New Step'], " +
                        "button.blue-plus-btn, " +
                        "button.center-btn");
        try {
            WebElement btn = waits.waitForClickable(plusBtn);
            btn.click();
        } catch (Exception e) {
            // Toolbar may already be visible — check for "Add Action App"
            logger.info("Plus button not found, toolbar may already be visible");
        }
        waitShort(1500);
    }

    // ─── Step 6: Click "Add Action App" button ───
    // DOM: <button class="f-button btn"> Add Action App </button>
    public void clickAddApp() {
        logger.info("Clicking 'Add Action App' button...");

        WebElement btn = null;

        // Strategy 1: XPath text match - wait 30s for toolbar to render
        try {
            By addAppByXpath = By.xpath(
                    "//button[contains(., 'Add Action App')] | //button[contains(., 'Add App')]");
            btn = new WebDriverWait(driver, Duration.ofSeconds(30))
                    .until(ExpectedConditions.elementToBeClickable(addAppByXpath));
            logger.info("Found Add Action App via XPath text");
        } catch (Exception ignored) {}

        // Strategy 2: If not visible yet, click the + again and retry
        if (btn == null) {
            logger.info("Toolbar not visible, clicking + again and retrying...");
            By plusBtn = By.cssSelector("#add-new-step-button, button.blue-plus-btn");
            try {
                driver.findElement(plusBtn).click();
                waitShort(1500);
            } catch (Exception ignored2) {}

            try {
                By addAppByXpath = By.xpath(
                        "//button[contains(., 'Add Action App')] | //button[contains(., 'Add App')]");
                btn = new WebDriverWait(driver, Duration.ofSeconds(20))
                        .until(ExpectedConditions.elementToBeClickable(addAppByXpath));
                logger.info("Found Add Action App via XPath text (retry)");
            } catch (Exception ignored3) {}
        }

        // Strategy 3: f-button with non-empty text containing 'action' or 'app'
        if (btn == null) {
            try {
                java.util.List<WebElement> fBtns = driver.findElements(By.cssSelector("button.f-button"));
                for (WebElement b : fBtns) {
                    String txt = b.getText().trim().toLowerCase();
                    if (!txt.isEmpty() && (txt.contains("action") || txt.contains("add app"))) {
                        btn = b;
                        logger.info("Found Add Action App via f-button text filter: '{}'", b.getText());
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (btn == null) {
            throw new RuntimeException("Could not find Add Action App button");
        }

        try {
            btn.click();
        } catch (Exception e) {
            jsClick(btn);
        }
        logger.info("Add Action App clicked. Waiting for loader...");
        waits.waitForLoader();
        waitShort(2000);
    }



    // ─── Step 7: Search & select action app (reuses same search pattern) ───
    public void selectActionApp(String appName) {
        logger.info("Selecting action app: {}", appName);
        // After Add App, the search bar appears again
        WebElement input = waits.waitForVisible(appSearchInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    // ─── Step 8: Select action event ───
    public void selectActionEvent(String eventName) {
        logger.info("Selecting action event: {}", eventName);
        selectTriggerEvent(eventName); // same mechanic
    }

    // ─── Step 9: Fill Gmail Draft setup form ───
    // Production-grade: label-scoped contenteditable click, chip confirmation, fail-fast Continue check.
    public void fillGmailDraftSetup() {
        logger.info("Filling Gmail Draft setup form...");
        long start = System.currentTimeMillis();
        waitShort(800); // let the form fully render

        mapDynamicField("subject", "ColA");
        mapDynamicField("message", "ColB");

        // Fail immediately if Continue is still disabled — mapping is incomplete
        By continueBtn = By.xpath("//button[@data-track='continue']");
        WebElement btn = new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.presenceOfElementLocated(continueBtn));
        if (!btn.isEnabled() || "true".equals(btn.getAttribute("disabled"))) {
            throw new RuntimeException(
                "Continue button is still DISABLED after mapping — Subject/Body mapping incomplete.");
        }
        long end = System.currentTimeMillis();
        logger.info("Gmail Draft setup form successfully mapped (Continue is enabled). Execution time: {} ms", (end - start));
    }

    /**
     * Production-grade field mapper for Appy Pie's contenteditable + choices-container pattern.
     * Scopes all locators to the specific label's form-check container to avoid global selector collisions.
     * Fails hard if chip not confirmed — prevents silent partial mapping.
     *
     * @param fieldId    label id attribute, e.g. "subject" or "message"
     * @param columnName text of the column to select, e.g. "ColA"
     */
    private void mapDynamicField(String fieldId, String columnName) {
        logger.info("Mapping [{}] → {}", fieldId, columnName);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        // ── 1. Scroll the field container into view ──────────────────────────────
        By section = By.xpath(
            "//label[@id='" + fieldId + "']/ancestor::div[contains(@class,'form-check')]");
        WebElement fieldSection = wait.until(ExpectedConditions.visibilityOfElementLocated(section));
        ((JavascriptExecutor) driver)
            .executeScript("arguments[0].scrollIntoView({block:'center'})", fieldSection);

        // ── 2. Click the contenteditable div or "Add or Select" span to open the dropdown ─────────
        By editable = By.xpath(
            "//label[@id='" + fieldId + "']/ancestor::div[contains(@class,'form-check')]" +
            "//*[self::div[@contenteditable='true'] or contains(@id, 'editOptions')]");
        
        try {
            wait.until(ExpectedConditions.elementToBeClickable(editable)).click();
        } catch (Exception elementClickEx) {
            WebElement el = driver.findElement(editable);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        }

        // ── 3. Wait for choices-container to become visible ───────────────────────
        By dropdown = By.xpath(
            "//div[contains(@class,'choices-container') and not(contains(@style,'display: none'))]");
        wait.until(ExpectedConditions.visibilityOfElementLocated(dropdown));

        // ── 4. Expand the trigger step (if collapsed) ─────────────────────────────
        By stepHeader = By.xpath(
            "//li[contains(@class,'samlpe-title')]//a[contains(.,'New Spreadsheet Row')]");
        if (!driver.findElements(stepHeader).isEmpty()) {
            wait.until(ExpectedConditions.elementToBeClickable(stepHeader)).click();
            waitShort(300);
        }

        // ── 5. Click the target column label ──────────────────────────────────────
        By column = By.xpath(
            "//label[contains(@class,'choice-label') and contains(.," +
            "'" + columnName + "')]");
        wait.until(ExpectedConditions.elementToBeClickable(column)).click();

        // ── 6. Confirm chip was added (fail-fast if not, without relying on exact text)
        By chip = By.xpath(
            "//label[@id='" + fieldId + "']/ancestor::div[contains(@class,'form-check')]" +
            "//span[contains(@class,'selected_item_repaet')]");
        WebElement chipEl = wait.until(ExpectedConditions.visibilityOfElementLocated(chip));
        logger.info("[{}] mapped successfully — chip confirmed (text: '{}').", fieldId, chipEl.getText().trim());

        // ── 7. Close dropdown: click field's own label (more stable than ESC in Angular) ──
        driver.findElement(By.xpath("//label[@id='" + fieldId + "']")).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(dropdown));
    }



    // Active only when connect is correctly configured
    public void clickActivateConnect() {
        logger.info("Clicking Activate Connect...");
        long start = System.currentTimeMillis();

        By activateBtn = By.xpath("//button[contains(.,'Activate')]");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        WebElement btn = wait.until(ExpectedConditions.presenceOfElementLocated(activateBtn));

        if (!btn.isEnabled()) {
            throw new RuntimeException("Activate Connect button is disabled — configuration incomplete.");
        }

        btn.click();
        waits.waitForLoader();

        // Confirm activation badge
        By activeBadge = By.xpath("//*[contains(text(),'Active')]");
        wait.until(ExpectedConditions.visibilityOfElementLocated(activeBadge));

        long end = System.currentTimeMillis();
        logger.info("Connect activated successfully. Execution time: {} ms", (end - start));
    }

    // ─── Helpers ───
    private void selectFromDropdown(String appName) {
        try {
            // Type the app name into the search box to filter results
            By searchInput = By.cssSelector(
                "input[placeholder*='trigger'], input[placeholder*='Trigger'], " +
                "input[placeholder*='action'], input[placeholder*='Action'], " +
                "input[placeholder*='Search'], input.form-control[placeholder*='app']");
            WebElement input = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.elementToBeClickable(searchInput));
            input.clear();
            input.sendKeys(appName);
            logger.info("Typed '{}' in search box. Waiting for filtered results...", appName);

            // Wait for filtered app results to appear
            By filteredApp = By.xpath(
                "//a[contains(@class,'ng-star-inserted') and .//p[normalize-space()='" + appName + "']] | " +
                "//a[contains(@class,'ng-star-inserted') and .//*[contains(normalize-space(text()),'" + appName + "')]]");
            WebElement appBtn = new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.elementToBeClickable(filteredApp));

            logger.info("Clicking app button: {}", appName);
            try {
                appBtn.click();
            } catch (org.openqa.selenium.ElementClickInterceptedException e) {
                logger.warn("Click intercepted. Dismissing overlays and retrying...");
                WaitUtils vu = new WaitUtils(driver, java.time.Duration.ofSeconds(10));
                vu.completeUserGuide();
                vu.dismissOverlays();
                waitShort(500);
                appBtn.click();
            }
            waits.waitForLoader();
            waitShort(800);
        } catch (Exception e) {
            logger.error("Failed to select app '{}' from dropdown: {}", appName, e.getMessage().split("\n")[0]);
            throw new RuntimeException(e);
        }
    }

    private void jsClick(WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
    }

    private void waitShort(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
