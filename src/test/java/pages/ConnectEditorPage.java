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
                    "input[placeholder*='Search'], input[placeholder*='search']");

    // Dropdown results for app selection
    private final By dropdownItems = By.cssSelector("li .landing-apps a, ul.dropdown-ai button");

    public ConnectEditorPage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(20));
    }

    public boolean isEditorVisible() {
        try {
            String selector = "app-custom-editor, #page-container-customeditor, .canvas-container, #triggerInput, .editor-header, #connectName";
            java.util.List<WebElement> elements = driver.findElements(By.cssSelector(selector));
            if (elements.isEmpty())
                return false;
            for (WebElement el : elements) {
                if (el.isDisplayed())
                    return true;
            }
            return driver.findElements(By.id("connectName")).size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Step 1: Search & select trigger app ───
    public void selectTriggerApp(String appName) {
        logger.info("Selecting trigger app: {}", appName);
        WebElement input = waits.waitForVisible(appSearchInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    // ─── Step 2: Select trigger event from the right panel ───
    // Click the event card container — Angular listens on the parent div, not the
    // checkbox
    public void selectTriggerEvent(String eventName) {
        logger.info("Selecting trigger event: {}", eventName);

        By eventCard = By.xpath(
                "//span[normalize-space()='" + eventName + "']" +
                        "/ancestor::div[contains(@class,'landing') or contains(@class,'card')][1]");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        WebElement card = wait.until(ExpectedConditions.visibilityOfElementLocated(eventCard));

        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", card);

        wait.until(ExpectedConditions.elementToBeClickable(card));
        card.click();
        logger.info("Trigger event selected successfully: {}", eventName);

        waits.waitForLoader();
        waitShort(1000);
    }

    // ─── Step 2.5: Handle Setup step (dropdowns: Spreadsheet, Worksheet, etc.) ───
    // This step may or may not appear depending on the app. If present, select
    // first available option from each required dropdown.
    public void handleSetupStep() {
        logger.info("Checking for Setup step (dropdown fields)...");
        By setupHeader = By.xpath("//h4[contains(text(),'Set Up')]");
        By menuDropdowns = By.cssSelector("div.multiple-menu div.menu");

        try {
            // Wait up to 5 seconds for setup section to appear
            boolean hasSetup = false;
            for (int i = 0; i < 10; i++) {
                if (!driver.findElements(setupHeader).isEmpty()) {
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
            java.util.List<WebElement> dropdowns = driver.findElements(menuDropdowns);
            logger.info("Found {} dropdown fields in setup.", dropdowns.size());

            for (int i = 0; i < dropdowns.size(); i++) {
                WebElement dropdown = dropdowns.get(i);
                try {
                    // Click on the dropdown toggle (menu_icon-box or the menu div itself)
                    WebElement toggle = dropdown.findElement(By.cssSelector(".menu_icon-box, .menu_icon"));
                    logger.info("Opening dropdown #{}", i);
                    try {
                        toggle.click();
                    } catch (Exception ce) {
                        jsClick(toggle);
                    }
                    waitShort(2000); // wait for dropdown options to load

                    // Look for option items inside the dropdown list
                    By optionItems = By.cssSelector(
                            "div.menu_dropdown ul li, div.menu_dropdown .option-item, div.menu_dropdown a, div.menu_dropdown .ng-star-inserted");
                    java.util.List<WebElement> options = dropdown.findElements(optionItems);

                    if (options.isEmpty()) {
                        logger.warn("Dropdown #{} has no options loaded. Waiting more...", i);
                        waitShort(3000);
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
                            waitShort(1500);
                            break;
                        }
                    }

                    if (!selected) {
                        logger.warn("Dropdown #{}: Could not select any option. May need manual setup.", i);
                    }

                } catch (Exception e) {
                    logger.warn("Error handling dropdown #{}: {}", i, e.getMessage());
                }
            }

            logger.info("Setup step handling complete.");
            waitShort(1000);

        } catch (Exception e) {
            logger.warn("Setup step handling error (suppressed): {}", e.getMessage());
        }
    }

    // ─── Step 3: Click "Continue" button (after event selection / setup) ───
    public void clickContinue() {
        logger.info("Clicking Continue");

        By btnLocator = By.cssSelector(
                "button[data-track='continue with account'], " +
                        "a[data-track='continue with event'], " +
                        "button[data-track='continue']");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(btnLocator));

        btn.click();
        waits.waitForLoader();
        logger.info("Continue clicked");
    }

    // ─── Step 4: Click "Continue" button (with Skip Run Test option) ───
    // DOM: <button data-track="continue" class="btn btn-lg w-100">Continue</button>
    // NOT: <button class="conntacont-btn">Skip Run Test</button>
    public void clickContinueRunTest() {
        logger.info("Clicking Continue (Run Test) button...");
        By continueRunBtn = By.cssSelector(
                "button[data-track='continue'], " +
                        "div.continue div.btngroup > button.btn");
        try {
            WebElement btn = waits.waitForClickable(continueRunBtn);
            btn.click();
        } catch (Exception e) {
            logger.warn("Standard Continue click failed, trying JS...");
            WebElement btn = waits.waitForVisible(continueRunBtn);
            jsClick(btn);
        }
        logger.info("Continue (Run Test) clicked. Waiting for loader...");
        waits.waitForLoader();
        waitShort(3000);
    }

    // ─── Step 5: Click "+" button on canvas ───
    // DOM: <button data-tooltip="Add New Step" class="blue-plus-btn">
    // NOTE: On some flows the toolbar with "Add Action App" is already visible
    public void clickAddNewStep() {
        logger.info("Clicking + (Add New Step) button on canvas...");
        By plusBtn = By.cssSelector(
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
        waitShort(1000);
    }

    // ─── Step 6: Click "Add Action App" button ───
    // DOM: <button class="f-button btn"> Add Action App <svg>...</svg></button>
    public void clickAddApp() {
        logger.info("Clicking 'Add Action App' button...");
        By addAppBtn = By.xpath(
                "//button[contains(., 'Add Action App')] | " +
                        "//button[contains(., 'Add App')]");
        try {
            WebElement btn = waits.waitForClickable(addAppBtn);
            btn.click();
        } catch (Exception e) {
            WebElement btn = waits.waitForVisible(addAppBtn);
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

    // ─── Activate Connect button (top-right) ───
    // Active only when connect is correctly configured
    public void clickActivateConnect() {
        logger.info("Clicking Activate Connect button...");
        By activateBtn = By.xpath(
                "//button[contains(text(), 'Activate Connect')] | " +
                        "//button[contains(., 'Activate')] | " +
                        "//a[contains(text(), 'Activate Connect')]");

        // Wait for button to become active (not disabled)
        try {
            new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> {
                        java.util.List<WebElement> btns = d.findElements(activateBtn);
                        for (WebElement btn : btns) {
                            if (btn.isDisplayed() && btn.isEnabled()) {
                                String disabled = btn.getAttribute("disabled");
                                String ariaDisabled = btn.getAttribute("aria-disabled");
                                if (disabled == null && (ariaDisabled == null || ariaDisabled.equals("false"))) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    });
            logger.info("Activate Connect button is ACTIVE. Clicking...");
        } catch (Exception e) {
            logger.warn("Activate Connect button may be inactive (config incomplete). Attempting click anyway...");
        }

        try {
            WebElement btn = waits.waitForVisible(activateBtn);
            btn.click();
        } catch (Exception e) {
            WebElement btn = waits.waitForVisible(activateBtn);
            jsClick(btn);
        }
        logger.info("Activate Connect clicked.");
        waits.waitForLoader();
        waitShort(2000);
    }

    // ─── Helpers ───
    private void selectFromDropdown(String appName) {
        try {
            waits.waitForVisible(dropdownItems);
            WebElement appBtn = driver.findElements(dropdownItems).stream()
                    .filter(btn -> btn.getText().toLowerCase().contains(appName.toLowerCase()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("App not found in dropdown: " + appName));

            logger.info("Clicking app button: {}", appBtn.getText());
            try {
                appBtn.click();
            } catch (org.openqa.selenium.ElementClickInterceptedException e) {
                logger.warn("Click intercepted by overlay. Dismissing guide and retrying...");
                WaitUtils vu = new WaitUtils(driver, java.time.Duration.ofSeconds(10));
                vu.completeUserGuide();
                vu.dismissOverlays();
                waitShort(500);
                jsClick(appBtn);
            }
            waits.waitForLoader();
            waitShort(2000);
        } catch (Exception e) {
            logger.error("Failed to select app from dropdown: {}", e.getMessage());
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
