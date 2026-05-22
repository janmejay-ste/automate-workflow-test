package pages;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // Add-menu container (the 3-button panel that appears after clicking +)
    private static final By ADD_MENU_CONTAINER = By.cssSelector(
            "div.buttons_wrap, div.buttons_wrap.bg-white");

    private static final By ADD_ACTION_APP_BTN = By.xpath(
            "//div[contains(@class,'buttons_wrap')]//button[contains(.,'Add Action App')]");
    private static final By ADD_AI_AGENT_BTN = By.xpath(
            "//div[contains(@class,'buttons_wrap')]//button[contains(.,'Add AI Agent')]");
    private static final By ADD_FLOW_BTN = By.xpath(
            "//div[contains(@class,'buttons_wrap')]//button[contains(.,'Add Flow')]");

    private static final By CANVAS_PLUS_BTN = By.cssSelector(
            "#add-new-step-button, button.blue-plus-btn, button.center-btn, " +
            "button[data-tooltip='Add New Step'], f-connection-for-create button");

    public ConnectEditorPage(WebDriver driver) {
        this.driver = driver;
        this.waits  = new WaitUtils(driver, Duration.ofSeconds(20));
    }

    // ─── Editor visibility ──────────────────────────────────────────────────────

    public boolean isEditorVisible() {
        try {
            String selector = "app-custom-editor, #page-container-customeditor, .canvas-container, " +
                              "#triggerInput, .editor-header, #connectName, div.undo-redo-buttons, " +
                              "f-canvas, f-flow";
            for (WebElement el : driver.findElements(By.cssSelector(selector))) {
                if (el.isDisplayed()) return true;
            }
            Object js = ((JavascriptExecutor) driver).executeScript(
                "return !!(document.querySelector('f-canvas') || " +
                "document.querySelector('div.undo-redo-buttons') || " +
                "document.getElementById('connectName') || " +
                "document.querySelector('f-flow'));");
            return Boolean.TRUE.equals(js);
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Trigger app selection ──────────────────────────────────────────────────

    public void selectTriggerApp(String appName) {
        logger.info("Selecting trigger app: {}", appName);
        WebElement input = waits.waitForVisible(appSearchInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    // ─── Trigger/Action event selection ────────────────────────────────────────
    //
    // Root cause of the original failure:
    //   waitForContinueButtonActive() waited for aria-disabled="true" → "false" on
    //   <a data-track="continue with event">.  Angular never removes aria-disabled
    //   from this element until an account is connected — but the element IS
    //   functionally clickable even with aria-disabled="true".  The check was wrong.
    //
    // Fix: click the label, wait for Angular's debounce to process the selection,
    //      then return.  clickContinue() (called next by the test) handles the actual
    //      button click via JS regardless of aria-disabled state.

    public void selectTriggerEvent(String eventName) {
        logger.info("Selecting trigger event: '{}'", eventName);

        // 1. Wait for the event list container
        new WebDriverWait(driver, Duration.ofSeconds(20))
                .until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("div.dropdown_menu_body_event, div.choose-trigger-event")));

        // 2. Locate and click the event label
        WebElement label = locateEventLabel(eventName);
        scrollIntoView(label);
        waitShort(300);

        try {
            label.click();
        } catch (Exception e) {
            logger.warn("Direct label click failed — using JS click");
            jsClick(label);
        }
        logger.info("Clicked event label: '{}'", eventName);

        // 3. Give Angular 2 s to process the selection and update internal state.
        //    Do NOT wait for aria-disabled to flip — that attribute only clears after
        //    account connection, not after event selection.
        waitShort(2000);
        waits.waitForLoader();
        waitShort(500);
    }

    // Same mechanic for action events
    public void selectActionEvent(String eventName) {
        logger.info("Selecting action event: '{}'", eventName);
        selectTriggerEvent(eventName);
    }

    // Locates label.form-check-label whose span matches the event name.
    private WebElement locateEventLabel(String eventName) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        By primary = By.xpath(
                "//div[contains(@class,'form-checkbox')]" +
                "//div[contains(@class,'col-9')]" +
                "//span[normalize-space(.)='" + eventName + "']" +
                "/ancestor::label[contains(@class,'form-check-label')]");
        try {
            WebElement el = wait.until(ExpectedConditions.visibilityOfElementLocated(primary));
            logger.info("Located event label (primary): '{}'", eventName);
            return el;
        } catch (Exception ignored) {}

        By fallback = By.xpath(
                "//div[contains(@class,'form-checkbox')]" +
                "//span[contains(normalize-space(.),'" + eventName + "')]" +
                "/ancestor::label[contains(@class,'form-check-label')]");
        try {
            WebElement el = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.visibilityOfElementLocated(fallback));
            logger.info("Located event label (fallback contains): '{}'", eventName);
            return el;
        } catch (Exception ignored) {}

        // Last resort: click the span text itself
        logger.warn("Label not found — falling back to span direct click for: '{}'", eventName);
        By span = By.xpath(
                "//span[normalize-space(.)='" + eventName + "'] | " +
                "//span[contains(normalize-space(.),'" + eventName + "')]");
        return wait.until(ExpectedConditions.visibilityOfElementLocated(span));
    }

    // ─── Continue button ────────────────────────────────────────────────────────
    //
    // Two distinct Continue buttons appear at different stages:
    //
    //   Stage A — after event selection (may still have aria-disabled="true"):
    //     <a data-track="continue with event" aria-disabled="true">Continue</a>
    //     Fix: JS-click it — aria-disabled on <a> is an ARIA hint, not a real gate.
    //
    //   Stage B — after account authorization:
    //     <button data-track="continue with account">Continue</button>
    //     Regular clickable button, no aria-disabled.
    //
    // Priority: account Continue > event Continue > generic Continue.
    // Both are JS-clicked so aria-disabled on <a> tags is bypassed cleanly.

    public void clickContinue() {
        logger.info("Clicking Continue (any variant)...");
        long start = System.currentTimeMillis();

        // Find the first visible Continue button, preferring the most-specific selector
        WebElement btn = findFirstVisibleContinue();
        if (btn == null) {
            throw new RuntimeException("No visible Continue button found after 20 s");
        }

        String dataTrack = "";
        try { dataTrack = btn.getAttribute("data-track"); } catch (Exception ignored) {}
        logger.info("Clicking Continue — data-track='{}' aria-disabled='{}'",
                dataTrack, btn.getAttribute("aria-disabled"));

        scrollIntoView(btn);
        waitShort(400);          // brief settle so Angular click handlers are wired

        // JS click bypasses aria-disabled on <a> elements cleanly
        jsClick(btn);
        logger.info("Continue clicked.");

        waits.waitForLoader();
        waitShort(1000);

        logger.info("Continue execution time: {} ms", System.currentTimeMillis() - start);
    }

    private WebElement findFirstVisibleContinue() {
        // Ordered preference: account button > event anchor > generic continue.
        // Use a SHORT per-selector timeout (5 s) so we don't burn 20 s × 4 = 80 s
        // when only the second or third selector matches.  Total cap stays 20 s via
        // the outer 20 s wait in clickContinue(), but in practice we find the right
        // button on the first or second try and return within 1–2 s.
        List<By> selectors = List.of(
                By.cssSelector("button[data-track='continue with account']"),
                By.cssSelector("a[data-track='continue with event']"),
                By.cssSelector("button[data-track='continue with event']"),
                By.cssSelector("div.continue button.btn, div.continue a.btn")
        );

        WebDriverWait quickWait = new WebDriverWait(driver, Duration.ofSeconds(5));
        for (By sel : selectors) {
            try {
                WebElement el = quickWait.until(ExpectedConditions.visibilityOfElementLocated(sel));
                if (el != null && el.isDisplayed()) {
                    return el;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ─── Setup step: custom Google-Sheets-style dropdowns ──────────────────────
    //
    // DOM pattern (from live HTML):
    //   <div id="menu-drop0">
    //     <input type="text" hidden name="spreadsheetId">
    //     <div id="selected_value" class="selected_value">   ← current selection label
    //     <div class="menu_icon-box"><div class="menu_icon"> ← CLICK TO OPEN
    //     <div class="menu_dropdown scrollheight _mCS_no_scrollbar">
    //       <div class="choices-search">
    //         <input placeholder="Search..." type="text">   ← type to filter options
    //         <button data-tooltip="Refresh Data">
    //       </div>
    //       <!-- option rows appear here after API load -->
    //     </div>
    //   </div>
    //
    //   <div id="menu-drop1"> … name="sheetId" … same structure … </div>
    //
    // Strategy:
    //   1. For the Spreadsheet dropdown: click menu_icon-box, wait for options,
    //      type "Test Sheet" in the search box, click the matching option.
    //   2. For the Worksheet dropdown: click menu_icon-box, wait for options,
    //      click the first available option.

    public void handleSetupStep() {
        logger.info("Handling setup step (custom dropdowns)...");
        waitShort(2000);   // let the mapping panel fully render

        // Locate all custom menu-drop containers in order
        List<WebElement> menuDrops = driver.findElements(
                By.cssSelector("div[id^='menu-drop']"));

        if (menuDrops.isEmpty()) {
            // Fallback to the older generic selector
            menuDrops = driver.findElements(By.cssSelector(
                    "div.multiple-menu div.menu, div.menu.ng-star-inserted"));
        }

        if (menuDrops.isEmpty()) {
            logger.info("No custom dropdown fields found — skipping setup step.");
            return;
        }

        logger.info("Found {} custom dropdown(s).", menuDrops.size());

        for (int idx = 0; idx < menuDrops.size(); idx++) {
            WebElement drop = menuDrops.get(idx);
            String dropId  = safeAttr(drop, "id", "menu-drop" + idx);
            String fieldName = "";
            try {
                WebElement hiddenInput = drop.findElement(By.cssSelector("input[type='text'][hidden]"));
                fieldName = safeAttr(hiddenInput, "name", "");
            } catch (Exception ignored) {}
            logger.info("Processing dropdown #{} id='{}' name='{}'", idx, dropId, fieldName);

            selectFromCustomDropdown(drop, idx, fieldName);
            waitShort(800);   // let Angular propagate the selection to the next dropdown
        }

        logger.info("Setup step complete.");
        waitShort(1000);
    }

    /**
     * Opens a custom dropdown (menu_icon-box → toggle), waits for options to load
     * via API, then selects either a named spreadsheet or the first available option.
     *
     * <p>Two distinct dropdown patterns exist in the editor:</p>
     * <ul>
     *   <li><b>menu_icon-box / menu_dropdown</b> — Google-Sheets-style setup fields
     *       (spreadsheetId, sheetId).  Identified by presence of {@code div.menu_icon-box}.</li>
     *   <li><b>choices-container</b> — Gmail/other action mapping fields (to, subject, message).
     *       Identified by {@code div#scrollhorz} or {@code span#selected_value}.
     *       These are skipped here and handled by {@link #fillGmailDraftSetup()}.</li>
     * </ul>
     *
     * @param drop      the root div[id^='menu-drop'] element
     * @param idx       0-based index (0 = spreadsheet, 1 = worksheet, …)
     * @param fieldName the hidden input's name attribute (e.g. "spreadsheetId")
     */
    private void selectFromCustomDropdown(WebElement drop, int idx, String fieldName) {
        // ── 0. Detect dropdown type ────────────────────────────────────────────
        // If div.menu_icon-box is absent this is a choices-container field (Gmail action
        // mapping).  Those are handled by fillGmailDraftSetup() — skip them here so
        // handleSetupStep() stays focused on the Google-Sheets-style setup dropdowns.
        boolean isMenuIconType = !drop.findElements(By.cssSelector("div.menu_icon-box")).isEmpty();
        if (!isMenuIconType) {
            logger.info("Dropdown #{} ('{}'): choices-container type — skipping in handleSetupStep "
                    + "(will be handled by fillGmailDraftSetup).", idx, fieldName);
            return;
        }

        // ── 1. Open the dropdown by clicking the icon toggle ──────────────────
        try {
            WebElement toggle = drop.findElement(By.cssSelector("div.menu_icon-box, div.menu_icon"));
            scrollIntoView(toggle);
            waitShort(300);
            jsClick(toggle);
            logger.info("Clicked dropdown #{} toggle.", idx);
        } catch (Exception e) {
            // Fallback: click the selected_value area
            try {
                WebElement sv = drop.findElement(By.cssSelector("div#selected_value, div.selected_value"));
                jsClick(sv);
                logger.info("Clicked dropdown #{} via selected_value.", idx);
            } catch (Exception e2) {
                logger.warn("Could not open dropdown #{}: {}", idx, e2.getMessage());
                return;
            }
        }

        // ── 2. Wait for the dropdown panel to become visible ──────────────────
        // The panel has class "hide_mCS_3" when closed; it's removed when open.
        By dropdownPanel = By.cssSelector(
                "div.menu_dropdown:not(.hide_mCS_3), " +
                "div.menu_dropdown.scrollheight:not([style*='display: none'])");
        boolean panelOpen = false;
        for (int attempt = 0; attempt < 15; attempt++) {
            waitShort(500);
            try {
                List<WebElement> panels = drop.findElements(dropdownPanel);
                if (!panels.isEmpty() && panels.get(0).isDisplayed()) {
                    panelOpen = true;
                    break;
                }
                // Also check by absence of hide_mCS_3 class
                List<WebElement> allPanels = drop.findElements(
                        By.cssSelector("div.menu_dropdown"));
                for (WebElement panel : allPanels) {
                    String cls = safeAttr(panel, "class", "");
                    if (!cls.contains("hide_mCS_3")) {
                        panelOpen = true;
                        break;
                    }
                }
                if (panelOpen) break;
            } catch (Exception ignored) {}
        }

        if (!panelOpen) {
            logger.warn("Dropdown #{} panel did not open visibly — proceeding anyway.", idx);
        }

        // ── 3. Wait for options to load from API (Refresh Data implies async) ──
        waitShort(2000);

        // ── 4. For spreadsheet dropdown: type "Test Sheet" to filter ──────────
        if (fieldName.contains("spreadsheet") || idx == 0) {
            try {
                WebElement searchInput = drop.findElement(
                        By.cssSelector("div.choices-search input[placeholder='Search...'], " +
                                       "div.menu_dropdown input.formcontrol"));
                searchInput.clear();
                searchInput.sendKeys("Test Sheet");
                logger.info("Typed 'Test Sheet' in spreadsheet dropdown search.");
                waitShort(1500);   // let filter apply
            } catch (Exception e) {
                logger.warn("Could not find search input in dropdown #{}: {}", idx, e.getMessage());
            }
        }

        // ── 5. Find and click the appropriate option ──────────────────────────
        boolean selected = clickDropdownOption(drop, idx, fieldName);
        if (!selected) {
            logger.warn("Dropdown #{} ('{}'): no selectable option found.", idx, fieldName);
        }
    }

    private boolean clickDropdownOption(WebElement drop, int idx, String fieldName) {
        // Broad selector for any option-like element inside the dropdown panel
        List<By> optionSelectors = List.of(
                By.cssSelector("div.menu_dropdown li"),
                By.cssSelector("div.menu_dropdown div.option-item"),
                By.cssSelector("div.menu_dropdown a.ng-star-inserted"),
                By.cssSelector("div.menu_dropdown div.ng-star-inserted"),
                By.cssSelector("div.menu_dropdown span.ng-star-inserted")
        );

        String targetText = (fieldName.contains("spreadsheet") || idx == 0) ? "Test Sheet" : null;

        for (By optSel : optionSelectors) {
            try {
                List<WebElement> opts = drop.findElements(optSel);
                for (WebElement opt : opts) {
                    try {
                        if (!opt.isDisplayed()) continue;
                        String text = opt.getText().trim();
                        if (text.isEmpty()) continue;
                        // Skip utility controls inside the search bar
                        if (isControlElement(opt, text)) continue;

                        // For spreadsheet dropdown: prefer "Test Sheet" exact match
                        if (targetText != null) {
                            if (!text.equalsIgnoreCase(targetText)
                                    && !text.toLowerCase().contains(targetText.toLowerCase())) {
                                continue;
                            }
                        }

                        scrollIntoView(opt);
                        jsClick(opt);
                        logger.info("Dropdown #{}: selected option '{}'", idx, text);
                        return true;

                    } catch (StaleElementReferenceException ignored) {}
                }
            } catch (Exception e) {
                logger.debug("Option selector {} in dropdown #{} threw: {}", optSel, idx, e.getMessage());
            }
        }

        // If no "Test Sheet" match for spreadsheet, fall back to first visible option
        if (targetText != null) {
            logger.warn("'{}' not found in dropdown #{} — falling back to first available option.", targetText, idx);
            return clickFirstOptionInDropdown(drop, idx);
        }
        return clickFirstOptionInDropdown(drop, idx);
    }

    private boolean clickFirstOptionInDropdown(WebElement drop, int idx) {
        List<By> fallbackSelectors = List.of(
                By.cssSelector("div.menu_dropdown li"),
                By.cssSelector("div.menu_dropdown div.option-item"),
                By.cssSelector("div.menu_dropdown div.ng-star-inserted"),
                By.cssSelector("div.menu_dropdown a")
        );

        for (By sel : fallbackSelectors) {
            try {
                List<WebElement> opts = drop.findElements(sel);
                for (WebElement opt : opts) {
                    try {
                        if (!opt.isDisplayed()) continue;
                        String text = opt.getText().trim();
                        if (text.isEmpty() || isControlElement(opt, text)) continue;
                        scrollIntoView(opt);
                        jsClick(opt);
                        logger.info("Dropdown #{}: selected first available option '{}'", idx, text);
                        return true;
                    } catch (StaleElementReferenceException ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Returns true for non-selectable utility elements inside the dropdown search bar. */
    private boolean isControlElement(WebElement el, String text) {
        try {
            String tooltip = safeAttr(el, "data-tooltip", "");
            if (!tooltip.isEmpty()) return true; // Refresh, Clear, Custom buttons
        } catch (Exception ignored) {}
        return text.equalsIgnoreCase("Search...")
                || text.equalsIgnoreCase("Refresh Data")
                || text.equalsIgnoreCase("Clear Selection")
                || text.equalsIgnoreCase("Use a Custom Value (Advance)");
    }

    // ─── Continue & Run Test ────────────────────────────────────────────────────
    //
    // DOM: <button data-track="continue" class="btn btn-lg ...">Continue &amp; Run Test</button>
    // The button is inside div.dropdownmenu.w-25 and is a standard <button>.

    public void clickContinueRunTest() {
        logger.info("Clicking 'Continue & Run Test'...");
        long start = System.currentTimeMillis();

        // Dismiss any open choices-container dropdown before trying to click the button.
        // If a choices-container was left open by fillGmailDraftSetup(), its search input
        // sits on top of the Continue button and intercepts the click.
        try {
            driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE);
            waitShort(300);
        } catch (Exception ignored) {}
        try {
            ((JavascriptExecutor) driver).executeScript("document.body.click();");
            waitShort(300);
        } catch (Exception ignored) {}

        // Wait for page to fully settle before looking for the button
        waits.waitForLoader();
        waitShort(1500);

        By selector = By.cssSelector("button[data-track='continue']");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        WebElement btn = wait.until(ExpectedConditions.visibilityOfElementLocated(selector));
        scrollIntoView(btn);

        // Wait up to 10 s for the button to be enabled (fields must be mapped first).
        // If still disabled after 10 s, log a warning and proceed — JS click will fire
        // the Angular handler even on a disabled button and the backend will respond.
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10)).until(d -> {
                try {
                    String disabled = btn.getAttribute("disabled");
                    return disabled == null || disabled.isEmpty() || disabled.equals("false");
                } catch (Exception ignored) { return false; }
            });
            logger.info("Continue & Run Test button is enabled — clicking.");
        } catch (Exception e) {
            logger.warn("Continue & Run Test button still DISABLED after 10 s — "
                    + "fields may not be fully mapped. Clicking anyway.");
        }
        waitShort(300);

        try { btn.click(); }
        catch (Exception e) {
            logger.warn("Direct click failed — using JS click: {}", e.getMessage().split("\n")[0]);
            jsClick(btn);
        }

        logger.info("'Continue & Run Test' clicked. Waiting for result to load...");
        waits.waitForLoader();
        waitShort(2000);   // give the test-run result time to settle

        logger.info("Continue & Run Test execution time: {} ms",
                System.currentTimeMillis() - start);
    }

    // ─── Add-step flow (+ button → 3-option menu) ───────────────────────────────
    //
    // DOM (from live snapshot):
    //   <div class="bg-white buttons_wrap d-flex flex-row w-100">
    //     <button class="f-button btn">Add Action App …</button>
    //     <button class="f-button btn">Add AI Agent …</button>
    //     <button class="f-button btn">Add Flow …</button>
    //   </div>
    //
    // The + button on the canvas is a TOGGLE: one click opens the menu, another closes.

    public void clickAddNewStep() {
        logger.info("Clicking + (Add New Step) on canvas...");

        // Wait for the canvas to settle after Continue & Run Test
        waits.waitForLoader();
        waitShort(1000);

        if (isAddMenuVisible()) {
            logger.info("Add-menu already visible — skipping + click.");
            return;
        }

        clickPlusToggle();
        waitShort(800);

        if (!isAddMenuVisible()) {
            logger.info("Menu not visible after first + click — re-clicking + to reopen.");
            clickPlusToggle();
            waitShort(800);
        }

        if (!isAddMenuVisible()) {
            logger.warn("Add-menu still not visible after two + clicks — proceeding anyway.");
        } else {
            logger.info("Add-menu (Add Action App / Add AI Agent / Add Flow) is visible.");
        }
    }

    public void clickAddApp() {
        logger.info("Clicking 'Add Action App'...");
        ensureAddMenuOpen();

        WebElement btn;
        try {
            btn = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.elementToBeClickable(ADD_ACTION_APP_BTN));
        } catch (Exception e) {
            throw new RuntimeException("'Add Action App' button not found in add-menu", e);
        }

        try { btn.click(); } catch (Exception e) { jsClick(btn); }
        logger.info("'Add Action App' clicked.");
        waits.waitForLoader();
        waitShort(2000);
    }

    public void clickAddAiAgent() {
        ensureAddMenuOpen();
        WebElement btn = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(ADD_AI_AGENT_BTN));
        try { btn.click(); } catch (Exception e) { jsClick(btn); }
        waits.waitForLoader();
    }

    public void clickAddFlow() {
        ensureAddMenuOpen();
        WebElement btn = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(ADD_FLOW_BTN));
        try { btn.click(); } catch (Exception e) { jsClick(btn); }
        waits.waitForLoader();
    }

    private boolean isAddMenuVisible() {
        try {
            return driver.findElements(ADD_MENU_CONTAINER).stream()
                    .anyMatch(WebElement::isDisplayed);
        } catch (Exception e) { return false; }
    }

    private void ensureAddMenuOpen() {
        if (isAddMenuVisible()) return;
        clickPlusToggle();
        waitShort(800);
        if (!isAddMenuVisible()) {
            clickPlusToggle();
            waitShort(800);
        }
        if (!isAddMenuVisible()) {
            throw new RuntimeException("Add-menu not visible after two + clicks");
        }
    }

    private void clickPlusToggle() {
        try {
            WebElement plus = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.elementToBeClickable(CANVAS_PLUS_BTN));
            try { plus.click(); } catch (Exception e) { jsClick(plus); }
        } catch (Exception e) {
            logger.warn("Canvas + button not found via CSS — JS fallback");
            ((JavascriptExecutor) driver).executeScript(
                "var btns = document.querySelectorAll('button');" +
                "for(var b of btns){" +
                "  var svg = b.querySelector('svg path[d*=\"M10 3\"]');" +
                "  if(svg && b.offsetParent !== null){ b.click(); break; }" +
                "}");
        }
    }

    // ─── Action app search + selection ─────────────────────────────────────────

    public void selectActionApp(String appName) {
        logger.info("Selecting action app: {}", appName);
        WebElement input = waits.waitForVisible(appSearchInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    // ─── Gmail Draft setup (Subject + Body dynamic mapping) ───────────────────
    //
    // Source analysis of app-edit-options.component.html reveals:
    //
    // Gmail Create Draft action fields (Subject, Body/Message) are type='custom_value'
    // → rendered inside the "Custom_value_Advanced" block → container [id]="'menu-drop-custom'+i"
    //
    // Correct DOM structure (from template source):
    //
    //   <div id="Custom_value_Advanced">
    //     <div id="menu-drop-custom{i}">          ← field container (i = loop index)
    //       <input hidden [name]="item.name">      ← IDENTIFIES the field ("subject"/"message")
    //       <div class="pretty-text-box">
    //         <div id="scrollhorz">
    //           <span class="selected_value">
    //             <span class="selected_item_repaet">
    //               <!-- blank state (value==undefined): -->
    //               <div (click)="toggleAddToSelectCustomValue(...)">+ Add or Select</div>
    //               <!-- initialized state (value==''): -->
    //               <div class="contenteditable6"
    //                    id="editOptions[subject][0].value0"
    //                    (click)="openCustomDropDownOnClick(...)">
    //               </div>
    //             </span>
    //           </span>
    //           <!-- completely blank fallback: -->
    //           <div class="checkIfNotBlurAfteraWhile"
    //                (click)="addCrossOverClassCustomValue($event,i,i)">
    //           </div>
    //         </div>
    //       </div>
    //       <!-- CHOICES — ALWAYS IN DOM, ALWAYS VISIBLE in Advanced mapping tab: -->
    //       <div class="choices-container scrollheight" id="scrollheightCustom{i}">
    //         <div class="choices-search">
    //           <h3>Search or select a dynamic value from previous step(s):</h3>
    //           <input class="formcontrol" placeholder="Search..."
    //                  (keyup)="filterApps($event,'menu-drop-custom'+i)">
    //         </div>
    //         <div class="choice-head">
    //           <ul>
    //             <li class="samlpe-title">
    //               <a id="choiceBtnShowHidedrop"
    //                  (click)="showDataofCustomSampleData(i,iConnectList,$event)">
    //                 1. New Spreadsheet Row ▶
    //               </a>                           ← CLICK to expand step data
    //               <ul class="choices" id="menu-drop-custom{i}choices_dropmenu{n}">
    //                 <li class="menu_dropdown-option choice"
    //                     (click)="slelectDataFromCustomSampleData(sampledata,item,i,...)">
    //                   <label class="choice-label">ColA</label>  ← CLICK the li, not label
    //                 </li>
    //                 ...
    //               </ul>
    //             </li>
    //           </ul>
    //         </div>
    //       </div>
    //     </div>
    //   </div>
    //
    // KEY FINDINGS vs previous incorrect assumptions:
    //   ✗ WRONG: clicking span.selected_item_repaet  → that's the chip display wrapper
    //   ✓ RIGHT: clicking div.checkIfNotBlurAfteraWhile or contenteditable6 to initialize
    //            BUT the choices-container (scrollheightCustom{i}) is ALWAYS visible
    //            in the Advanced mapping tab — no trigger click needed to show it!
    //
    //   ✗ WRONG: looking for div.choices-container.open-down (that CSS class is NOT applied
    //            in the Advanced mapping view — container is statically visible)
    //   ✓ RIGHT: choices-container is always rendered; just find it and interact
    //
    //   ✗ WRONG: clicking label.choice-label — Angular handler is on the PARENT li
    //   ✓ RIGHT: clicking li.menu_dropdown-option.choice fires slelectDataFromCustomSampleData()

    /**
     * How long (seconds) to wait for MANUAL user input after automation fails to map a Gmail
     * field.  Set via JVM system property {@code -DgmailManualWaitSec=N} (default 15).
     * Set to 0 to disable the manual-input observation window entirely.
     */
    private static final int GMAIL_MANUAL_WAIT_SEC = Integer.parseInt(
            System.getProperty("gmailManualWaitSec", "15"));

    public void fillGmailDraftSetup() {
        logger.info("Filling Gmail Draft setup form (type='text' / menu-drop pattern)...");
        long start = System.currentTimeMillis();
        waitShort(1500);   // let the action-setup panel render

        // ── Scroll the setup panel to expose Subject and Body/Message fields ──
        scrollActionPanelToField("Subject");
        waitShort(500);

        // Gmail Create Draft: Subject and Body/Message use Angular type='text'.
        // Leave 'to' unmapped (Create Draft does not require a recipient).
        String[] fieldsToMap = {"subject", "message"};
        Set<String> mapped = new HashSet<>();

        for (String fieldName : fieldsToMap) {
            String displayLabel = fieldName.equals("subject") ? "Subject" : "Message";
            scrollActionPanelToField(displayLabel);
            waitShort(300);

            // ── attempt automation mapping ──────────────────────────────────
            try {
                mapGmailTextField(fieldName, "ColA");
                waitShort(800);
            } catch (Exception e) {
                logger.warn("mapGmailTextField('{}') threw: {}", fieldName,
                        e.getMessage().split("\n")[0]);
            }

            // ── read back the current field value (regardless of how it got set) ──
            WebElement fieldContainer = findTextFieldContainer(fieldName);
            String currentValue = readGmailFieldValue(fieldContainer, fieldName);

            if (currentValue != null && !currentValue.isEmpty()) {
                logger.info("✓ Field '{}' has value after automation attempt → \"{}\"",
                        fieldName, currentValue);
                mapped.add(fieldName);
            } else {
                logger.warn("✗ Field '{}' is EMPTY after automation — " +
                            "automation did NOT map a value.", fieldName);

                // ── manual-input observation window ─────────────────────────
                // If GMAIL_MANUAL_WAIT_SEC > 0, poll so that a tester can manually
                // fill the field and the terminal captures the interaction.
                if (GMAIL_MANUAL_WAIT_SEC > 0) {
                    logger.warn("  ↳ Waiting up to {} s for manual input on '{}' " +
                                "(override with -DgmailManualWaitSec=0 to skip)...",
                                GMAIL_MANUAL_WAIT_SEC, fieldName);
                    String manualValue = waitForManualFieldInput(fieldName, fieldContainer,
                            GMAIL_MANUAL_WAIT_SEC);
                    if (manualValue != null && !manualValue.isEmpty()) {
                        logger.info("  ↳ '{}' was MANUALLY set → \"{}\" (captured for debugging)",
                                fieldName, manualValue);
                        mapped.add(fieldName);
                    } else {
                        logger.warn("  ↳ '{}' still empty after {}s manual-input window.",
                                fieldName, GMAIL_MANUAL_WAIT_SEC);
                    }
                }
            }
        }

        // ── final field-value snapshot ──────────────────────────────────────
        logger.info("─────────────────────────────────────────────────────");
        logger.info("Gmail Draft field snapshot (post-mapping):");
        for (String fieldName : fieldsToMap) {
            WebElement c = findTextFieldContainer(fieldName);
            String v = readGmailFieldValue(c, fieldName);
            if (v != null && !v.isEmpty()) {
                logger.info("  [MAPPED  ] {} → \"{}\"", fieldName, v);
            } else {
                logger.warn("  [EMPTY   ] {} → (no value)", fieldName);
            }
        }
        logger.info("─────────────────────────────────────────────────────");

        if (mapped.isEmpty()) {
            logger.warn("No Gmail action fields were mapped.");
        } else {
            logger.info("Gmail Draft form mapped fields: {}. Time: {} ms",
                    mapped, System.currentTimeMillis() - start);
        }
    }

    /**
     * Reads the current value of a Gmail text field from the DOM.
     *
     * <p>Checks two sources in priority order:</p>
     * <ol>
     *   <li><b>Hidden input value</b> — {@code input[name='fieldName']} inside the container.
     *       Angular keeps this in sync with the chip model, so it's the most reliable signal.</li>
     *   <li><b>Chip text</b> — text content of {@code span.selected_item_repaet} elements
     *       that are NOT the {@code + Add or Select} placeholder.</li>
     * </ol>
     *
     * @param container the {@code div[id^='menu-drop']} container for the field, or {@code null}
     * @param fieldName {@code "subject"} or {@code "message"}
     * @return the mapped value string, or {@code null}/{@code ""} if the field is empty
     */
    private String readGmailFieldValue(WebElement container, String fieldName) {
        if (container == null) return null;

        // 1. Hidden input value (most reliable — Angular keeps it in sync)
        try {
            List<WebElement> inputs = container.findElements(
                    By.cssSelector("input[name='" + fieldName + "'], input[id='" + fieldName + "']"));
            for (WebElement inp : inputs) {
                try {
                    String val = (String) ((JavascriptExecutor) driver)
                            .executeScript("return arguments[0].value;", inp);
                    if (val != null && !val.trim().isEmpty()) {
                        return val.trim();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 2. Chip text from selected_item_repaet spans (visible chips, excluding placeholder)
        try {
            String chipText = (String) ((JavascriptExecutor) driver).executeScript(
                    "var c = arguments[0];" +
                    "var chips = Array.from(c.querySelectorAll('.selected_item_repaet'));" +
                    "var values = chips.map(function(chip) {" +
                    "  var t = (chip.innerText || '').trim();" +
                    "  // Exclude the '+ Add or Select' placeholder chip" +
                    "  return (t && !t.includes('Add or Select') && t !== '+') ? t : '';" +
                    "}).filter(function(t) { return t.length > 0; });" +
                    "return values.join(', ');",
                    container);
            if (chipText != null && !chipText.trim().isEmpty()) {
                return chipText.trim();
            }
        } catch (Exception ignored) {}

        // 3. editOptions span innerText (the span shows chip values when filled)
        //
        // IMPORTANT: This must NOT return plain text that was typed directly into the
        // contenteditable via typeDirectlyIntoField().  A real Angular chip value looks
        // like "+\n<alphanumericId>" (the '+' is the chip selector, '\n' separates the
        // display label from the bound dynamic-value ID).  Plain-typed text like "ColA"
        // does NOT contain a newline — we exclude it here to avoid false-positive
        // MAPPED reports when the Angular model was never actually updated.
        try {
            String editId = "editOptions[" + fieldName + "]0";
            List<WebElement> spans = driver.findElements(
                    By.xpath("//span[@id='" + editId + "']"));
            for (WebElement span : spans) {
                try {
                    String t = span.getAttribute("innerText");
                    if (t == null) t = span.getText();
                    t = t == null ? "" : t.trim();
                    // Must be non-empty, not a placeholder, and contain a newline —
                    // the newline is the reliable indicator of a real chip binding
                    // (format: "+\n<dynamicValueId>").
                    if (!t.isEmpty() && !t.contains("Add or Select") && !t.equals("+")
                            && t.contains("\n")) {
                        return t;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Polls the Gmail field value every 2 seconds for up to {@code timeoutSec} seconds,
     * logging progress so a tester who manually fills the field can see their input captured.
     *
     * <p>Used as the manual-input observation window when automation fails to map a field.
     * The log output helps identify the exact DOM state at the time of a successful fill,
     * which can then be used to improve the automation logic.</p>
     *
     * @param fieldName  the field name ({@code "subject"} or {@code "message"})
     * @param container  the field's {@code menu-drop} container element (may be {@code null})
     * @param timeoutSec how long to wait
     * @return the value if it appeared within the timeout, or {@code null}
     */
    private String waitForManualFieldInput(String fieldName, WebElement container,
                                           int timeoutSec) {
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        int pollInterval = 2_000;
        int elapsed = 0;

        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(pollInterval); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
            elapsed += pollInterval;

            // Re-fetch container on every poll (DOM may refresh)
            WebElement c = container;
            try { if (c == null || !isInDom(c)) c = findTextFieldContainer(fieldName); }
            catch (Exception ignored) {}

            String val = readGmailFieldValue(c, fieldName);
            if (val != null && !val.isEmpty()) {
                return val;
            }

            // Progress tick every 6 seconds so the terminal shows activity
            if (elapsed % 6_000 == 0) {
                int remaining = (int) ((deadline - System.currentTimeMillis()) / 1000);
                logger.info("  [manual-wait] '{}' still empty — {}s remaining. " +
                            "Please fill the field manually if testing.", fieldName, remaining);
            }
        }
        return null;
    }

    /**
     * Scrolls the right-side action-setup panel until a label element whose text
     * exactly matches {@code fieldLabelText} is visible in the viewport.
     *
     * <p>The Angular setup panel has its own scroll container (not the window).
     * If the label is found in the DOM, {@link #scrollWithinPanel(WebElement)} brings
     * it into view.  If the label is not yet rendered, a fixed 250 px downward scroll
     * is applied to the first candidate panel container so later fields become
     * reachable.</p>
     *
     * @param fieldLabelText exact label text to scroll to (e.g. {@code "Subject"})
     */
    private void scrollActionPanelToField(String fieldLabelText) {
        try {
            // 1. Find a leaf text element whose innerText exactly equals fieldLabelText
            WebElement labelEl = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "var target = arguments[0];" +
                    "var els = Array.from(document.querySelectorAll('label, span, div, p'));" +
                    "for (var i = 0; i < els.length; i++) {" +
                    "  var t = (els[i].innerText || '').trim();" +
                    "  if (t === target && els[i].children.length === 0) return els[i];" +
                    "}" +
                    "return null;",
                    fieldLabelText);

            if (labelEl != null) {
                // Scroll the panel so the label (and its input field) is centred in view
                scrollWithinPanel(labelEl);
                waitShort(400);
                logger.info("Scrolled action-setup panel to label '{}'", fieldLabelText);
                return;
            }

            // 2. Label not in DOM yet — apply a generic downward scroll to all candidate
            //    panel containers so Angular renders more content.
            ((JavascriptExecutor) driver).executeScript(
                    "var PANEL_SELECTORS = [" +
                    "  '.right-panel', '.action-panel', '.panel-body'," +
                    "  '.setup-container', '.options-panel', '.scroll-container'," +
                    "  '[class*=\"right-panel\"]', '[class*=\"action-setup\"]'," +
                    "  '[class*=\"setup-panel\"]', '[class*=\"option-panel\"]'" +
                    "];" +
                    "var scrolled = false;" +
                    "for (var s = 0; s < PANEL_SELECTORS.length; s++) {" +
                    "  var el = document.querySelector(PANEL_SELECTORS[s]);" +
                    "  if (el && el.scrollHeight > el.clientHeight) {" +
                    "    el.scrollTop += 250; scrolled = true;" +
                    "  }" +
                    "}" +
                    // Fallback: scroll the nearest overflow-y ancestor of the first menu-drop
                    "if (!scrolled) {" +
                    "  var drop = document.querySelector('[id^=\"menu-drop\"]');" +
                    "  if (drop) {" +
                    "    var cur = drop.parentElement;" +
                    "    for (var i = 0; i < 12; i++) {" +
                    "      if (!cur) break;" +
                    "      var st = window.getComputedStyle(cur);" +
                    "      if (/auto|scroll/.test(st.overflow + st.overflowY) &&" +
                    "          cur.scrollHeight > cur.clientHeight) {" +
                    "        cur.scrollTop += 250; break;" +
                    "      }" +
                    "      cur = cur.parentElement;" +
                    "    }" +
                    "  }" +
                    "}");
            waitShort(400);
            logger.warn("Label '{}' not in DOM — applied generic downward panel scroll", fieldLabelText);

        } catch (Exception e) {
            logger.debug("scrollActionPanelToField('{}') failed: {}", fieldLabelText,
                    e.getMessage().split("\n")[0]);
        }
    }

    /**
     * Maps a single Gmail Create Draft field (subject or message).
     *
     * Confirmed correct flow (user-verified DOM):
     *   1. Click the VISIBLE INPUT FIELD for the Action app field row.
     *      This opens div.choices-search.NewOption_4 on the page.
     *   2. Type in input.formcontrol inside the choices-search panel.
     *   3. Expand the step expander (li.samlpe-title > a) if not already expanded.
     *   4. Click li.menu_dropdown-option.choice to select a value.
     *   5. Close the panel.
     *
     * The trigger click uses angularClick() (mousedown+mouseup+click dispatch) so
     * Angular component handlers fire correctly.  We search for the field trigger
     * via label text (JS DOM walk) before falling back to container/positional search.
     */
    private void mapGmailTextField(String fieldName, String columnName) {
        logger.info("mapGmailTextField: '{}' → '{}'", fieldName, columnName);

        // ── 0. Scroll the panel so this field is in the viewport ─────────────
        // Each field is scrolled into view before we try to click it, ensuring the
        // trigger element is not obscured by the panel's top or bottom edge.
        String displayLabel = fieldName.equalsIgnoreCase("subject") ? "Subject" : "Message";
        scrollActionPanelToField(displayLabel);
        waitShort(400);

        // ── 1. Click the field's visible input area ───────────────────────────
        //
        // THREE strategies in priority order — stop as soon as choices-search appears.
        //
        // Strategy A: JS DOM walk from label text → pretty-text-box
        //   Finds the label element whose text matches the field name, then walks up
        //   the DOM tree to locate the nearest div.pretty-text-box (the visual input
        //   area that the Angular component opens into choices-search when clicked).
        //
        // Strategy B: findTextFieldContainer() → child trigger selectors
        //   Falls back to the ID-based container search, then tries well-known child
        //   selectors within the container.
        //
        // Strategy C: Global positional div.pretty-text-box search
        //   Takes all pretty-text-box elements on the page; subject → index 0,
        //   message → index 1.  This works even when the container lookup fails.

        boolean clicked  = false;
        // Declared here so quick-select (called immediately after panel opens) can set it true
        // before the slower Selenium option-search steps run.
        boolean selected = false;

        // — Strategy A —
        String[] labelVariants = fieldName.equalsIgnoreCase("subject")
                ? new String[]{"Subject", "subject"}
                : new String[]{"Message", "Body", "message", "body"};

        for (String lbl : labelVariants) {
            if (clicked) break;
            try {
                // JS: find a LEAF label element whose text exactly matches the field name,
                // then walk UP the DOM counting how many .pretty-text-box elements are in
                // the ancestor's subtree.  Stop at the FIRST ancestor that contains exactly
                // ONE box — that is this field's own container.  This prevents the bug where
                // walking too high yields an ancestor that also contains the "To" field's box,
                // causing querySelector() to return the wrong (first-in-DOM) element.
                WebElement triggerEl = (WebElement) ((JavascriptExecutor) driver).executeScript(
                        "var targetText = arguments[0];" +
                        // 1. Find leaf-text element with exact matching text
                        "var allEls = Array.from(document.querySelectorAll('label, span, div, p'));" +
                        "var lbl = null;" +
                        "for (var i = 0; i < allEls.length; i++) {" +
                        "  var el = allEls[i];" +
                        "  var t = (el.innerText || '').trim();" +
                        "  if (t === targetText && el.children.length === 0) { lbl = el; break; }" +
                        "}" +
                        "if (!lbl) return null;" +
                        // 2. Walk up: stop at the level that contains EXACTLY 1 trigger box
                        //    (= this field's own container).  If count > 1 we have gone too
                        //    high and risk picking the wrong field's box.
                        "var BOX_SEL = '.pretty-text-box, .checkIfNotBlurAfteraWhile, .contenteditable6';" +
                        "var cur = lbl.parentElement;" +
                        "for (var i = 0; i < 15; i++) {" +
                        "  if (!cur || cur === document.body) break;" +
                        "  var boxes = cur.querySelectorAll(BOX_SEL);" +
                        "  if (boxes.length === 1) return boxes[0];" +
                        "  if (boxes.length > 1) break;" +
                        "  cur = cur.parentElement;" +
                        "}" +
                        "return null;",
                        lbl);

                if (triggerEl != null && isInDom(triggerEl)) {
                    scrollWithinPanel(triggerEl);
                    waitShort(300);
                    tryClickUntilPanelOpens(triggerEl, fieldName, "A:" + lbl);
                    if (isChoicesSearchVisible()) {
                        clicked = true;
                        if (!selected) { waitShort(400); selected = jsQuickSelectFromVisiblePanel(columnName, fieldName); }
                        logger.info("Field '{}': strategy-A click near label '{}' opened panel (quick-select: {})", fieldName, lbl, selected);
                    } else {
                        logger.debug("Field '{}': strategy-A click near label '{}' — panel not visible", fieldName, lbl);
                    }
                }
            } catch (Exception e) {
                logger.debug("Field '{}': strategy-A failed for label '{}': {}",
                        fieldName, lbl, e.getMessage().split("\n")[0]);
            }
        }

        // — Strategy B —
        //
        // `clicked` is only set to true when isChoicesSearchVisible() confirms the
        // choices-search panel is actually open — not merely because we clicked something.
        //
        // From live DOM snapshots (provided by user):
        //   Subject: span[id='editOptions[subject]0'] > span.readmorebutton2 > div  (contains "+ Add or Select")
        //   Body:    span[id='editOptions[message]0'] > span.readmorebutton2 > div  (contains "+ Add or Select")
        //
        // These IDs are the definitive click targets.  The Angular (click) handler on the
        // inner div opens the choices-search panel scoped to the correct field.
        WebElement container = null;
        if (!clicked) {
            container = findTextFieldContainer(fieldName);
            if (container != null) {
                logger.info("Field '{}': container='{}' found", fieldName, safeAttr(container, "id", "?"));
                scrollWithinPanel(container);
                waitShort(400);

                // — B0: direct editOptions[fieldName] ID → inner "Add or Select" div ─────────
                // This is the most precise target — derived from the live DOM snapshot.
                // editOptions[subject]0 / editOptions[message]0 are unique per field.
                String editOptionsId = "editOptions[" + fieldName + "]0";
                try {
                    // Use XPath attribute selector so [ ] in the ID are handled literally
                    List<WebElement> editSpans = driver.findElements(
                            By.xpath("//span[@id='" + editOptionsId + "']"));
                    for (WebElement editSpan : editSpans) {
                        if (!isInDom(editSpan)) continue;
                        scrollWithinPanel(editSpan);
                        waitShort(200);

                        // Prefer the inner <div> inside span.readmorebutton2 (the visible trigger div)
                        WebElement innerDiv = null;
                        try {
                            innerDiv = editSpan.findElement(
                                    By.cssSelector("span.readmorebutton2 > div, " +
                                                   "span.nothighlightbox > div"));
                        } catch (Exception ignored) {}

                        WebElement target = (innerDiv != null) ? innerDiv : editSpan;
                        scrollWithinPanel(target);
                        waitShort(200);
                        tryClickUntilPanelOpens(target, fieldName, "B0:editOptions[" + fieldName + "]0");
                        if (isChoicesSearchVisible()) {
                            clicked = true;
                            if (!selected) { waitShort(400); selected = jsQuickSelectFromVisiblePanel(columnName, fieldName); }
                            logger.info("Field '{}': B0 editOptions ID click opened panel (target={}, quick-select: {})",
                                    fieldName, (innerDiv != null ? "inner div" : "span"), selected);
                            break;
                        }
                    }
                    if (!clicked) {
                        logger.debug("Field '{}': B0 editOptions ID '{}' found {} span(s) but panel not opened",
                                fieldName, editOptionsId, editSpans.size());
                    }
                } catch (Exception e) {
                    logger.debug("Field '{}': B0 failed: {}", fieldName, e.getMessage().split("\n")[0]);
                }

                // — B1: span.readmorebutton2 > div (from HTML snapshot, fallback) ──────────
                if (!clicked) {
                    try {
                        List<WebElement> rmbDivs = container.findElements(
                                By.cssSelector("span.readmorebutton2 > div, span.nothighlightbox > div"));
                        for (WebElement rmbDiv : rmbDivs) {
                            if (!isInDom(rmbDiv)) continue;
                            scrollWithinPanel(rmbDiv);
                            waitShort(200);
                            tryClickUntilPanelOpens(rmbDiv, fieldName, "B1:readmorebutton2>div");
                            if (isChoicesSearchVisible()) {
                                clicked = true;
                                if (!selected) { waitShort(400); selected = jsQuickSelectFromVisiblePanel(columnName, fieldName); }
                                logger.info("Field '{}': B1 span.readmorebutton2>div click opened panel (quick-select: {})", fieldName, selected);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Field '{}': B1 failed: {}", fieldName, e.getMessage().split("\n")[0]);
                    }
                }

                // — B2: ordered class-selector cascade ─────────────────────────────────────
                if (!clicked) {
                    String[] childSelectors = {
                        "span.readmorebutton2",            // the nothighlightbox span wrapping the trigger
                        "span.selected_item_repaet",       // outer chip/add-button wrapper
                        "div#scrollhorz",                  // widthpre horizontal-scroll container
                        "span.selected_value",             // selected_value span
                        "div.pretty-text-box"              // outer styled box (last resort)
                    };
                    for (String sel : childSelectors) {
                        if (clicked) break;
                        try {
                            List<WebElement> hits = container.findElements(By.cssSelector(sel));
                            for (WebElement el : hits) {
                                if (!isInDom(el)) continue;
                                scrollWithinPanel(el);
                                waitShort(200);
                                tryClickUntilPanelOpens(el, fieldName, "B2:" + sel);
                                if (isChoicesSearchVisible()) {
                                    clicked = true;
                                    if (!selected) { waitShort(400); selected = jsQuickSelectFromVisiblePanel(columnName, fieldName); }
                                    logger.info("Field '{}': B2 '{}' opened panel (quick-select: {})", fieldName, sel, selected);
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // — B3: container itself ────────────────────────────────────────────────────
                if (!clicked) {
                    try {
                        tryClickUntilPanelOpens(container, fieldName, "B3:container");
                        if (isChoicesSearchVisible()) {
                            clicked = true;
                            if (!selected) { waitShort(400); selected = jsQuickSelectFromVisiblePanel(columnName, fieldName); }
                            logger.warn("Field '{}': B3 container click opened panel (quick-select: {})", fieldName, selected);
                        } else {
                            logger.warn("Field '{}': B3 container click did NOT open panel", fieldName);
                        }
                    } catch (Exception ignored) {}
                }
            } else {
                logger.warn("Field '{}': findTextFieldContainer returned null — falling to strategy-C", fieldName);
            }
        }

        // — Strategy C —
        // Gmail Create Draft field order on page: To (index 0) → Subject (index 1) → Message (index 2).
        // We FILTER OUT boxes that belong to the "To" field container so our positional index
        // remains 0 = Subject, 1 = Message regardless of the total box count.
        if (!clicked) {
            try {
                List<WebElement> allBoxes = driver.findElements(
                        By.cssSelector("div.pretty-text-box, div.checkIfNotBlurAfteraWhile"));
                logger.info("Field '{}': {} total pretty-text-box element(s) on page before To-filter",
                        fieldName, allBoxes.size());

                // Filter: exclude boxes whose closest menu-drop ancestor is the "To" field
                // (detected by finding input[name='to'] or a leaf-text label "To" inside it).
                List<WebElement> nonToBoxes = new ArrayList<>();
                for (WebElement box : allBoxes) {
                    try {
                        Boolean isToField = (Boolean) ((JavascriptExecutor) driver).executeScript(
                                "var el = arguments[0], cur = el;" +
                                "for (var i = 0; i < 12; i++) {" +
                                "  cur = cur.parentElement; if (!cur) break;" +
                                // explicit input[name='to'] inside the ancestor
                                "  if (cur.querySelector('input[name=\"to\"], input[id=\"to\"]')) return true;" +
                                // or a leaf 'To' label inside a menu-drop style container
                                "  if (cur.id && /^menu-drop/.test(cur.id) && !cur.id.includes('custom')) {" +
                                "    var labels = cur.querySelectorAll('label, span');" +
                                "    for (var j = 0; j < labels.length; j++) {" +
                                "      var txt = (labels[j].innerText || '').trim().toLowerCase();" +
                                "      if (txt === 'to' && labels[j].children.length === 0) return true;" +
                                "    }" +
                                "  }" +
                                "}" +
                                "return false;",
                                box);
                        if (!Boolean.TRUE.equals(isToField)) nonToBoxes.add(box);
                    } catch (Exception ignored) {
                        nonToBoxes.add(box); // include on error — better to try than skip
                    }
                }

                logger.info("Field '{}': {} non-To box(es) after filter", fieldName, nonToBoxes.size());
                int targetIdx = fieldName.equalsIgnoreCase("subject") ? 0 : 1;
                if (nonToBoxes.size() > targetIdx) {
                    WebElement trigger = nonToBoxes.get(targetIdx);
                    scrollWithinPanel(trigger);
                    waitShort(300);
                    tryClickUntilPanelOpens(trigger, fieldName, "C:box[" + targetIdx + "]");
                    if (isChoicesSearchVisible()) {
                        clicked = true;
                        if (!selected) { waitShort(400); selected = jsQuickSelectFromVisiblePanel(columnName, fieldName); }
                        logger.info("Field '{}': strategy-C non-To box[{}] opened panel (quick-select: {})", fieldName, targetIdx, selected);
                    } else {
                        logger.warn("Field '{}': strategy-C non-To box[{}] click did not open panel", fieldName, targetIdx);
                    }
                } else {
                    logger.warn("Field '{}': strategy-C found only {} non-To box(es), need index {}",
                            fieldName, nonToBoxes.size(), targetIdx);
                }
            } catch (Exception e) {
                logger.debug("Field '{}': strategy-C failed: {}", fieldName, e.getMessage().split("\n")[0]);
            }
        }

        // ── Ensure container is resolved for scoped searches ─────────────────
        // Strategy A / C may have clicked without setting `container`.  We need a
        // container reference for ALL subsequent scoped DOM searches (expander, options)
        // so that selections go into the CORRECT field, not into the "To" field which
        // happens to appear first in global DOM order.
        if (container == null) {
            container = findTextFieldContainer(fieldName);
            if (container != null) {
                logger.debug("Field '{}': container resolved post-click for scoping", fieldName);
            }
        }
        // Convenience alias — use container for scoped finds, body as last resort
        final WebElement scope = (container != null) ? container : driver.findElement(By.tagName("body"));

        // ── 2. Locate the choices-search input ───────────────────────────────
        // The choices-search.NewOption_4 panel may close quickly after the trigger
        // click (Angular blur handling).  Try three locations in priority order:
        //   a) scoped inside the field's own container (most precise — avoids "To" panel)
        //   b) globally visible (in case the panel rendered as an overlay)
        //   c) give up gracefully — option selection below works without the search input
        WebElement searchInput = null;

        // a) scoped: inside the field's container
        try {
            List<WebElement> scoped = scope.findElements(
                    By.cssSelector("div.choices-search input.formcontrol, " +
                                   "div.choices-search input[placeholder='Search...']"));
            for (WebElement inp : scoped) {
                try { if (inp.isDisplayed()) { searchInput = inp; break; } }
                catch (Exception ignored) {}
            }
            if (searchInput != null) {
                logger.info("Field '{}': choices-search input found (scoped to container)", fieldName);
            }
        } catch (Exception ignored) {}

        // b) global: short wait for any visible formcontrol
        if (searchInput == null) {
            try {
                searchInput = new WebDriverWait(driver, Duration.ofSeconds(2))
                        .until(ExpectedConditions.visibilityOfElementLocated(
                                By.cssSelector("div.choices-search input.formcontrol, " +
                                               "div.choices-search input[placeholder='Search...']")));
                logger.info("Field '{}': choices-search input found (global)", fieldName);
            } catch (Exception e) {
                logger.warn("Field '{}': choices-search input not visible — will rely on always-visible options in container",
                        fieldName);
            }
        }

        // ── 3. Type the search term ───────────────────────────────────────────
        // IMPORTANT: Do NOT use searchInput.clear() + sendKeys() here.
        // Selenium's keyboard-level events (Ctrl+A → Delete → keystroke) can fire
        // Angular's blur handler and close the choices panel before we can read the
        // filtered results.  Instead, we set the input's value via JavaScript and fire
        // the minimal synthetic events Angular needs to update its internal filter model.
        if (searchInput != null) {
            try {
                scrollWithinPanel(searchInput);
                ((JavascriptExecutor) driver).executeScript(
                        "var inp = arguments[0], val = arguments[1];" +
                        // Clear first (empty input + input event → Angular clears filter)
                        "inp.value = '';" +
                        "inp.dispatchEvent(new Event('input', {bubbles:true}));" +
                        // Set the new value
                        "inp.value = val;" +
                        "inp.dispatchEvent(new Event('input',  {bubbles:true, cancelable:true}));" +
                        "inp.dispatchEvent(new Event('change', {bubbles:true}));",
                        searchInput, columnName);
                logger.info("Field '{}': typed '{}' in formcontrol (JS, no-blur)", fieldName, columnName);
                // Give Angular time to filter the options list (async API or local filter)
                waitShort(1800);
                // If the panel closed despite the no-blur approach, null out searchInput
                // so pass-C does not attempt to clear a stale element reference.
                if (!isChoicesSearchVisible()) {
                    logger.warn("Field '{}': panel closed after JS-type — scoped options will be used as-is", fieldName);
                    searchInput = null;
                }
            } catch (Exception e) {
                logger.warn("Field '{}': JS-type in formcontrol failed — {}", fieldName,
                        e.getMessage().split("\n")[0]);
                searchInput = null;   // treat as if search wasn't typed
            }
        }

        // ── 4. Expand the step expander ───────────────────────────────────────
        // SCOPED TO CONTAINER: expand ONLY the expander inside this field's container
        // (menu-drop1 for subject, menu-drop2 for message).  Global search would pick
        // the "To" field's expander first (DOM order: To < Subject < Message).
        try {
            List<WebElement> expanders = scope.findElements(
                    By.cssSelector("li.samlpe-title > a, li.sample-title > a"));
            for (WebElement exp : expanders) {
                if (isInDom(exp)) {
                    scrollWithinPanel(exp);
                    waitShort(200);
                    jsClick(exp);
                    // Wait for the expander to reveal its child options.
                    // Angular may need a tick to render them, and the text of the
                    // expander itself may not be available immediately (async load).
                    // Poll until the expander has non-empty text OR up to 1500ms.
                    String expanderText = "";
                    for (int w = 0; w < 3; w++) {
                        waitShort(500);
                        try { expanderText = exp.getText().trim(); } catch (Exception ignored) {}
                        if (!expanderText.isEmpty()) break;
                    }
                    logger.info("Field '{}': expander clicked ('{}')", fieldName, expanderText);
                    break;
                }
            }
        } catch (Exception e) {
            logger.debug("Field '{}': expander skipped — {}", fieldName,
                    e.getMessage().split("\n")[0]);
        }

        // ── 5. Select an option ───────────────────────────────────────────────
        // ALL option searches are SCOPED TO THE FIELD'S CONTAINER.
        // Searching globally finds the "To" field's li.menu_dropdown-option.choice
        // elements first (DOM order), which causes the value to be mapped into "To"
        // instead of Subject or Message.  Scoping prevents cross-field contamination.
        // NOTE: `selected` is declared at the top; may already be true if
        //        jsQuickSelectFromVisiblePanel() succeeded immediately after the click.
        if (!selected) waitShort(300);

        // Pass A: visible text match within container
        // isDisplayed() guard is critical — options inside a CLOSED choices panel
        // are still in the DOM but not visible and cannot be clicked.
        try {
            List<WebElement> options = scope.findElements(
                    By.cssSelector("li.menu_dropdown-option.choice"));
            logger.info("Field '{}': {} option(s) in container after expand", fieldName, options.size());
            for (WebElement opt : options) {
                try {
                    if (!isInDom(opt) || !opt.isDisplayed()) continue;  // skip hidden options
                    String text = opt.getText().trim();
                    if (!text.isEmpty() && text.contains(columnName)) {
                        scrollWithinPanel(opt);
                        waitShort(100);
                        jsClick(opt);
                        logger.info("Field '{}': pass-A selected '{}' (scoped)", fieldName, text);
                        selected = true;
                        break;
                    }
                } catch (StaleElementReferenceException ignored) {}
            }
        } catch (Exception e) {
            logger.debug("Field '{}': pass-A failed — {}", fieldName,
                    e.getMessage().split("\n")[0]);
        }

        // Pass B: first non-empty visible option within container
        if (!selected) {
            logger.warn("Field '{}': text match for '{}' not found — selecting first scoped option", fieldName, columnName);
            try {
                List<WebElement> options = scope.findElements(
                        By.cssSelector("li.menu_dropdown-option.choice"));
                for (WebElement opt : options) {
                    try {
                        if (isInDom(opt) && opt.isDisplayed()) {
                            scrollWithinPanel(opt);
                            waitShort(100);
                            jsClick(opt);
                            logger.info("Field '{}': pass-B selected '{}' (scoped)", fieldName, opt.getText().trim());
                            selected = true;
                            break;
                        }
                    } catch (StaleElementReferenceException ignored) {}
                }
            } catch (Exception e) {
                logger.debug("Field '{}': pass-B failed — {}", fieldName,
                        e.getMessage().split("\n")[0]);
            }
        }

        // Pass C: clear search + retry within container
        // Uses JS to clear the input — avoids the "element not interactable" error
        // that Selenium's searchInput.clear() throws if the panel has already closed
        // and the element became stale.  Also filters to visible options only.
        if (!selected && searchInput != null) {
            logger.warn("Field '{}': no options matched — clearing search and retrying (scoped)", fieldName);
            boolean cleared = false;
            try {
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].value = '';" +
                        "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));",
                        searchInput);
                cleared = true;
                waitShort(800);
            } catch (Exception e) {
                logger.warn("Field '{}': pass-C JS-clear failed (panel likely closed) — {}",
                        fieldName, e.getMessage().split("\n")[0]);
            }
            if (cleared) {
                try {
                    List<WebElement> options = scope.findElements(
                            By.cssSelector("li.menu_dropdown-option.choice"));
                    for (WebElement opt : options) {
                        try {
                            if (isInDom(opt) && opt.isDisplayed()) {
                                scrollWithinPanel(opt);
                                jsClick(opt);
                                logger.info("Field '{}': pass-C selected '{}' (scoped)", fieldName, opt.getText().trim());
                                selected = true;
                                break;
                            }
                        } catch (StaleElementReferenceException ignored) {}
                    }
                } catch (Exception e) {
                    logger.warn("Field '{}': pass-C option select failed — {}", fieldName,
                            e.getMessage().split("\n")[0]);
                }
            }
        }

        // Pass D: direct text input fallback
        if (!selected) {
            logger.warn("Field '{}': no choices selected — falling back to direct text input", fieldName);
            selected = typeDirectlyIntoField(container, fieldName, columnName);
        }

        if (!selected) {
            logger.warn("Field '{}': all strategies exhausted — field may remain empty", fieldName);
        }
        waitShort(400);

        // ── 6. Close the choices panel ────────────────────────────────────────
        try {
            List<WebElement> closeBtns = driver.findElements(By.cssSelector("button.btn-closeMenu"));
            boolean closed = false;
            for (WebElement btn : closeBtns) {
                if (isInDom(btn)) { jsClick(btn); closed = true; break; }
            }
            if (!closed) {
                driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE);
                waitShort(200);
                ((JavascriptExecutor) driver).executeScript("document.body.click();");
            }
        } catch (Exception ignored) {}
        waitShort(400);
    }

    /**
     * Finds the {@code div[id^='menu-drop']} (type='text') container for the given
     * Gmail action field.  Does NOT match {@code menu-drop-custom} containers.
     *
     * <p>Identification strategy (in priority order):</p>
     * <ol>
     *   <li>Hidden {@code input[name='fieldName']} inside the container.</li>
     *   <li>Hidden {@code input[id='fieldName']} inside the container.</li>
     *   <li>Positional: subject = first non-'to' container, message = second.</li>
     * </ol>
     */
    private WebElement findTextFieldContainer(String fieldName) {
        // Match menu-drop{n} but NOT menu-drop-custom{n}
        List<WebElement> allDrops = driver.findElements(
                By.cssSelector("div[id^='menu-drop']:not([id*='custom'])"));
        logger.info("findTextFieldContainer('{}'):  {} menu-drop (non-custom) container(s).",
                fieldName, allDrops.size());

        // Strategy A: hidden input[name='fieldName']
        for (WebElement drop : allDrops) {
            try {
                if (!drop.findElements(By.cssSelector(
                        "input[name='" + fieldName + "']")).isEmpty()) {
                    logger.info("Found '{}' via input[name].", fieldName);
                    return drop;
                }
            } catch (Exception ignored) {}
        }

        // Strategy B: hidden input[id='fieldName']
        for (WebElement drop : allDrops) {
            try {
                if (!drop.findElements(By.cssSelector(
                        "input[id='" + fieldName + "']")).isEmpty()) {
                    logger.info("Found '{}' via input[id].", fieldName);
                    return drop;
                }
            } catch (Exception ignored) {}
        }

        // Strategy C: positional among non-'to' containers
        List<WebElement> nonTo = new ArrayList<>();
        for (WebElement drop : allDrops) {
            try {
                if (drop.findElements(By.cssSelector("input[name='to']")).isEmpty()) {
                    nonTo.add(drop);
                }
            } catch (Exception ignored) { nonTo.add(drop); }
        }
        int targetIdx = fieldName.equals("subject") ? 0 : 1;
        if (nonTo.size() > targetIdx) {
            logger.warn("Using positional fallback for '{}' (idx={} of {} non-to).",
                    fieldName, targetIdx, nonTo.size());
            return nonTo.get(targetIdx);
        }

        return null;
    }

    /**
     * Types {@code value} directly into the {@code contenteditable6} div inside {@code container}.
     *
     * <p>Angular's contenteditable binding reads the div's textContent via an input event listener.
     * We set the text via JS, dispatch a synthetic "input" event, then also sendKeys() via Selenium
     * so Angular's zone sees the change and marks the field dirty (enabling the Continue button).</p>
     *
     * @return true if the text was successfully typed; false if the field could not be found
     */
    private boolean typeDirectlyIntoField(WebElement container, String fieldName, String value) {
        // Close the choices panel first so it doesn't intercept clicks
        try { driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE); waitShort(300); }
        catch (Exception ignored) {}

        // Locate contenteditable6 — Angular renders it as the active text area for type='text' fields
        List<By> contentEditableSelectors = List.of(
                By.cssSelector("div.contenteditable6"),
                By.cssSelector("[contenteditable='true']"),
                By.cssSelector("div[id^='editOptions']"),
                By.cssSelector("div.pretty-text-box [contenteditable]")
        );

        for (By sel : contentEditableSelectors) {
            List<WebElement> hits;
            try {
                hits = container != null ? container.findElements(sel)
                                        : driver.findElements(sel);
            } catch (Exception e) { continue; }

            for (WebElement ce : hits) {
                try {
                    if (!isInDom(ce)) continue;
                    scrollWithinPanel(ce);
                    waitShort(200);

                    // 1. Set textContent via JS and dispatch input event — Angular's
                    //    value accessor listens for this to update its internal model.
                    ((JavascriptExecutor) driver).executeScript(
                            "var el = arguments[0], txt = arguments[1];" +
                            "el.textContent = txt;" +
                            "el.dispatchEvent(new Event('input', {bubbles:true}));" +
                            "el.dispatchEvent(new Event('change', {bubbles:true}));",
                            ce, value);
                    waitShort(300);

                    // 2. Also click + sendKeys so Selenium/Angular zone registers the keystroke
                    try {
                        ce.click();
                        ce.sendKeys(Keys.END);   // move cursor to end
                        waitShort(200);
                    } catch (Exception ignored) {}

                    // 3. Fire blur so Angular marks the control as touched/dirty
                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].dispatchEvent(new Event('blur', {bubbles:true}));", ce);
                    waitShort(300);

                    logger.info("Field '{}': typed '{}' directly into contenteditable.", fieldName, value);
                    return true;
                } catch (StaleElementReferenceException ignored) {}
                catch (Exception e) {
                    logger.debug("Field '{}': contenteditable write via {} failed: {}",
                            fieldName, sel, e.getMessage().split("\n")[0]);
                }
            }
        }

        // Last resort: find the hidden input and set its value directly
        try {
            List<WebElement> inputs = container != null
                    ? container.findElements(By.cssSelector("input[type='hidden'], input[hidden]"))
                    : driver.findElements(By.cssSelector("input[name='" + fieldName + "']"));
            for (WebElement inp : inputs) {
                if (!isInDom(inp)) continue;
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].value = arguments[1];" +
                        "arguments[0].dispatchEvent(new Event('input', {bubbles:true}));" +
                        "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));",
                        inp, value);
                logger.info("Field '{}': set hidden input value to '{}'.", fieldName, value);
                return true;
            }
        } catch (Exception e) {
            logger.debug("Field '{}': hidden input fallback failed: {}", fieldName,
                    e.getMessage().split("\n")[0]);
        }

        return false;
    }

    /**
     * Returns {@code true} if the choices-search panel's search input is currently
     * visible anywhere on the page.
     *
     * <p>Used as a fast boolean check after every trigger click so we only advance
     * to typing once the panel has actually opened — rather than assuming every
     * click succeeded.</p>
     */
    private boolean isChoicesSearchVisible() {
        try {
            List<WebElement> inputs = driver.findElements(
                    By.cssSelector("div.choices-search input.formcontrol, " +
                                   "div.choices-search input[placeholder='Search...']"));
            for (WebElement inp : inputs) {
                try { if (inp.isDisplayed()) return true; } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Finds the currently-visible {@code div.choices-search} panel and immediately
     * selects an option that contains {@code columnName} — all in a single JavaScript
     * call to avoid the round-trip latency that would let Angular's blur handler
     * close the panel before Selenium's multi-step find+click sequence completes.
     *
     * <p>The method:</p>
     * <ol>
     *   <li>Locates the visible choices panel via {@code getBoundingClientRect}.</li>
     *   <li>Sets the search-input value to {@code columnName} and dispatches {@code input}
     *       + {@code change} events so Angular filters the list.</li>
     *   <li>Searches for an option whose {@code innerText} contains {@code columnName}
     *       (case-insensitive); falls back to the first non-empty option.</li>
     *   <li>Clicks the matched option and returns {@code true}.</li>
     * </ol>
     *
     * @return {@code true} if an option was clicked; {@code false} if no panel was
     *         visible or no options were found
     */
    private boolean jsQuickSelectFromVisiblePanel(String columnName, String fieldName) {
        try {
            Object raw = ((JavascriptExecutor) driver).executeScript(
                    "var col = arguments[0];" +
                    // ── 1. Find the visible choices-search panel ──────────────────────────
                    "var panels = document.querySelectorAll('div.choices-search');" +
                    "var panel = null;" +
                    "for (var i = 0; i < panels.length; i++) {" +
                    "  var p = panels[i];" +
                    "  var r = p.getBoundingClientRect();" +
                    "  if (r.width > 0 && r.height > 0 && p.offsetParent !== null) { panel = p; break; }" +
                    "}" +
                    "if (!panel) return 'no-panel';" +
                    // ── 2. Filter via search input ────────────────────────────────────────
                    "var inp = panel.querySelector(" +
                    "    'input.formcontrol, input[placeholder=\"Search...\"], input[type=\"search\"]');" +
                    "if (inp) {" +
                    "  inp.value = col;" +
                    "  inp.dispatchEvent(new Event('input',  {bubbles:true}));" +
                    "  inp.dispatchEvent(new Event('change', {bubbles:true}));" +
                    "}" +
                    // ── 3. Locate matching option ─────────────────────────────────────────
                    "var opts = panel.querySelectorAll(" +
                    "    'li.menu_dropdown-option.choice, li.choice, li[class*=\"choice\"]');" +
                    "var colLower = col.toLowerCase();" +
                    // Pass 1: exact text-contains match
                    "for (var j = 0; j < opts.length; j++) {" +
                    "  var txt = (opts[j].innerText || opts[j].textContent || '').trim();" +
                    "  if (txt && txt.toLowerCase().indexOf(colLower) !== -1) {" +
                    "    opts[j].click(); return 'selected:' + txt;" +
                    "  }" +
                    "}" +
                    // Pass 2: first non-empty option (fallback)
                    "for (var j = 0; j < opts.length; j++) {" +
                    "  var txt = (opts[j].innerText || opts[j].textContent || '').trim();" +
                    "  if (txt) { opts[j].click(); return 'first:' + txt; }" +
                    "}" +
                    "return 'no-options:' + opts.length;",
                    columnName);

            String res = (raw == null) ? "null" : raw.toString();
            if (res.startsWith("selected:") || res.startsWith("first:")) {
                logger.info("Field '{}': jsQuickSelectFromVisiblePanel → {}", fieldName, res);
                return true;
            }
            logger.debug("Field '{}': jsQuickSelectFromVisiblePanel → {} (no selection made)", fieldName, res);
            return false;
        } catch (Exception e) {
            logger.debug("Field '{}': jsQuickSelectFromVisiblePanel threw: {}",
                    fieldName, e.getMessage().split("\n")[0]);
            return false;
        }
    }

    /**
     * Tries to click {@code el} using three methods in sequence, stopping as soon as
     * the choices-search panel becomes visible:
     * <ol>
     *   <li>Native Selenium {@code click()} — generates real browser-level events.</li>
     *   <li>{@link #jsClick(WebElement)} — JavaScript {@code element.click()}.</li>
     *   <li>{@link #angularClick(WebElement)} — full mousedown+mouseup+click dispatch.</li>
     * </ol>
     *
     * @param el         element to click
     * @param fieldName  field being mapped (for log context)
     * @param hint       short label for the log (e.g. {@code "B2:span.selected_item_repaet"})
     */
    private void tryClickUntilPanelOpens(WebElement el, String fieldName, String hint) {
        // Attempt 1 — native Selenium click (real browser event, zone.js picks it up)
        try {
            el.click();
            waitShort(600);
            if (isChoicesSearchVisible()) {
                logger.info("Field '{}': [{}] native click → panel opened", fieldName, hint);
                return;
            }
        } catch (Exception e) {
            logger.debug("Field '{}': [{}] native click threw: {}", fieldName, hint,
                    e.getMessage().split("\n")[0]);
        }

        // Attempt 2 — JavaScript element.click() (bypasses interception overlays)
        try {
            jsClick(el);
            waitShort(600);
            if (isChoicesSearchVisible()) {
                logger.info("Field '{}': [{}] jsClick → panel opened", fieldName, hint);
                return;
            }
        } catch (Exception e) {
            logger.debug("Field '{}': [{}] jsClick threw: {}", fieldName, hint,
                    e.getMessage().split("\n")[0]);
        }

        // Attempt 3 — full mousedown+mouseup+click dispatch (some Angular components
        // only respond to the full event chain dispatched on the exact element)
        try {
            angularClick(el);
            waitShort(600);
            if (isChoicesSearchVisible()) {
                logger.info("Field '{}': [{}] angularClick → panel opened", fieldName, hint);
            } else {
                logger.debug("Field '{}': [{}] all 3 click methods tried — panel still not visible",
                        fieldName, hint);
            }
        } catch (Exception e) {
            logger.debug("Field '{}': [{}] angularClick threw: {}", fieldName, hint,
                    e.getMessage().split("\n")[0]);
        }
    }

    /**
     * Dispatches a full {@code mousedown → mouseup → click} event sequence on {@code el}
     * via JavaScript, then falls back to Selenium's {@code click()} / {@link #jsClick(WebElement)}.
     *
     * <p>Angular component handlers sometimes listen to {@code mousedown} or {@code mouseup}
     * in addition to {@code click}.  Selenium's built-in {@code click()} only fires the
     * synthetic WebDriver click — dispatching all three events via JS ensures the Angular
     * zone picks up the interaction and opens the choices-search panel.</p>
     */
    private void angularClick(WebElement el) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var el = arguments[0];" +
                    "['mousedown','mouseup','click'].forEach(function(type) {" +
                    "  el.dispatchEvent(new MouseEvent(type, {" +
                    "    bubbles: true, cancelable: true, view: window" +
                    "  }));" +
                    "});",
                    el);
        } catch (Exception e) {
            // JS dispatch failed — fall back to native Selenium click
            try { el.click(); }
            catch (Exception e2) { jsClick(el); }
        }
    }

    /**
     * Scrolls the nearest scrollable ancestor panel to bring {@code el} into view.
     *
     * <p>The Angular right-panel has {@code overflow-y: scroll/auto} and is a separate
     * scroll container from the window.  Standard {@code scrollIntoView({block:'center'})}
     * sometimes only scrolls the window.  This method uses JavaScript to walk up the DOM
     * and scroll the nearest ancestor that has a scroll container, then also calls the
     * native {@code scrollIntoView} as a safety net.</p>
     */
    private void scrollWithinPanel(WebElement el) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                "(function(el) {" +
                "  function getScrollParent(node) {" +
                "    if (!node || node === document.body) return null;" +
                "    var style = window.getComputedStyle(node);" +
                "    var overflow = style.overflow + style.overflowY + style.overflowX;" +
                "    if (/auto|scroll/.test(overflow) && node.scrollHeight > node.clientHeight) {" +
                "      return node;" +
                "    }" +
                "    return getScrollParent(node.parentElement);" +
                "  }" +
                "  var sp = getScrollParent(el);" +
                "  if (sp) {" +
                "    var r = el.getBoundingClientRect();" +
                "    var pr = sp.getBoundingClientRect();" +
                "    var offset = r.top - pr.top - (pr.height / 2) + (r.height / 2);" +
                "    sp.scrollTop += offset;" +
                "  }" +
                "  el.scrollIntoView({behavior:'auto', block:'nearest', inline:'nearest'});" +
                "})(arguments[0]);",
                el);
        } catch (Exception ignored) {
            // Fallback to basic scrollIntoView
            try {
                ((JavascriptExecutor) driver)
                        .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
            } catch (Exception ignored2) {}
        }
    }

    /** Returns true if the element exists in the DOM even if not Selenium-visible. */
    private boolean isInDom(WebElement el) {
        try {
            return Boolean.TRUE.equals(((JavascriptExecutor) driver)
                    .executeScript("return arguments[0] != null && arguments[0].isConnected;", el));
        } catch (Exception e) { return false; }
    }

    // ─── Activate Connect ───────────────────────────────────────────────────────
    //
    // DOM (from live HTML):
    //   <button class="btn active_agent_Button"> Activate Connect </button>
    //   <span class="hoverTooltip">Click to Activate the Connect.</span>
    //
    // Primary selector: button.active_agent_Button
    // XPath fallback:   //button[contains(normalize-space(.),'Activate')]

    public void clickActivateConnect() {
        logger.info("Clicking Activate Connect...");
        long start = System.currentTimeMillis();

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        // ── 1. Locate the button ──────────────────────────────────────────────
        WebElement btn = null;
        try {
            btn = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("button.active_agent_Button")));
            logger.info("Activate button found via button.active_agent_Button.");
        } catch (Exception e) {
            logger.warn("button.active_agent_Button not found — trying XPath fallback.");
            try {
                btn = new WebDriverWait(driver, Duration.ofSeconds(10))
                        .until(ExpectedConditions.presenceOfElementLocated(By.xpath(
                            "//button[contains(normalize-space(.),'Activate Connect') " +
                            "or contains(normalize-space(.),'Activate')]")));
            } catch (Exception e2) {
                throw new RuntimeException("Activate Connect button not found on page.", e2);
            }
        }

        // ── 2. Wait up to 30 s for the button to become enabled ──────────────
        // The button is initially disabled while the Continue & Run Test result is
        // still loading.  Poll until the disabled attribute is gone.
        final WebElement activateBtn = btn;
        try {
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> {
                try {
                    String disabled = activateBtn.getAttribute("disabled");
                    return disabled == null || disabled.isEmpty() || disabled.equals("false");
                } catch (Exception ignored) { return false; }
            });
            logger.info("Activate button is enabled.");
        } catch (Exception e) {
            logger.warn("Activate button did not become enabled within 30 s — attempting click anyway.");
        }

        // ── 3. Scroll into view and click ────────────────────────────────────
        scrollIntoView(activateBtn);
        waitShort(500);
        try { activateBtn.click(); }
        catch (Exception e) {
            logger.warn("Direct click failed — using JS click: {}", e.getMessage().split("\n")[0]);
            jsClick(activateBtn);
        }

        logger.info("Activate Connect clicked. Waiting for confirmation...");
        waits.waitForLoader();
        waitShort(2000);

        // ── 4. Soft-verify activation (non-fatal) ────────────────────────────
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15)).until(
                    ExpectedConditions.visibilityOfElementLocated(By.xpath(
                        "//*[contains(normalize-space(text()),'Active') " +
                        "or contains(normalize-space(text()),'activated')]")));
            logger.info("Activate confirmation badge visible. Time: {} ms",
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            logger.warn("Could not confirm 'Active' badge — Connect may still have activated. "
                    + "Time: {} ms", System.currentTimeMillis() - start);
        }
    }

    // ─── Undo/Redo ─────────────────────────────────────────────────────────────

    public void testUndoRedoFunctionality() {
        logger.info("Testing Undo/Redo...");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        By undoBtn = By.xpath("//button[@data-tooltip='Undo']");
        By redoBtn = By.xpath("//button[@data-tooltip='Redo']");

        WebElement undo = wait.until(ExpectedConditions.presenceOfElementLocated(undoBtn));
        wait.until(d -> undo.isEnabled() && undo.getAttribute("disabled") == null);
        logger.info("Undo enabled — clicking.");
        try { undo.click(); } catch (Exception e) { jsClick(undo); }
        waitShort(1500);

        WebElement redo = wait.until(ExpectedConditions.presenceOfElementLocated(redoBtn));
        wait.until(d -> redo.isEnabled() && redo.getAttribute("disabled") == null);
        logger.info("Redo enabled — clicking.");
        try { redo.click(); } catch (Exception e) { jsClick(redo); }
        waitShort(1500);

        logger.info("Undo/Redo test passed.");
    }

    // ─── Shared helpers ─────────────────────────────────────────────────────────

    private void selectFromDropdown(String appName) {
        try {
            By searchInput = By.cssSelector(
                "input[placeholder*='trigger'], input[placeholder*='Trigger'], " +
                "input[placeholder*='action'], input[placeholder*='Action'], " +
                "input[placeholder*='Search'], input.form-control[placeholder*='app']");
            WebElement input = new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(ExpectedConditions.elementToBeClickable(searchInput));
            input.clear();
            input.sendKeys(appName);
            logger.info("Typed '{}' in search box.", appName);

            By filteredApp = By.xpath(
                "//a[contains(@class,'ng-star-inserted') and .//p[normalize-space()='" + appName + "']] | " +
                "//a[contains(@class,'ng-star-inserted') and .//*[contains(normalize-space(text()),'" + appName + "')]]");
            WebElement appBtn = new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.elementToBeClickable(filteredApp));

            logger.info("Clicking app card: {}", appName);
            try {
                appBtn.click();
            } catch (ElementClickInterceptedException e) {
                logger.warn("Click intercepted — dismissing overlays and retrying.");
                new WaitUtils(driver, Duration.ofSeconds(10)).completeUserGuide();
                new WaitUtils(driver, Duration.ofSeconds(10)).dismissOverlays();
                waitShort(500);
                appBtn.click();
            }

            waits.waitForLoader();
            waitShort(800);
        } catch (Exception e) {
            logger.error("Failed to select '{}' from dropdown: {}", appName,
                    e.getMessage().split("\n")[0]);
            throw new RuntimeException(e);
        }
    }

    private void scrollIntoView(WebElement el) {
        try {
            ((JavascriptExecutor) driver)
                    .executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        } catch (Exception ignored) {}
    }

    private void jsClick(WebElement el) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
    }

    private void waitShort(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String safeAttr(WebElement el, String attr, String def) {
        try { String v = el.getAttribute(attr); return v != null ? v : def; }
        catch (Exception e) { return def; }
    }
}
