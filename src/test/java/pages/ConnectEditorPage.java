package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class ConnectEditorPage {
    private static final Logger logger = LoggerFactory.getLogger(ConnectEditorPage.class);
    private final WebDriver driver;
    private final WaitUtils waits;

    private final By triggerInput = By.cssSelector("input[placeholder*='trigger'], input[placeholder*='Trigger']");
    private final By actionInput = By.cssSelector("input[placeholder*='action'], input[placeholder*='Action']");
    private final By dropdownItems = By.cssSelector("li .landing-apps a, ul.dropdown-ai button");

    public ConnectEditorPage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(20));
    }

    public boolean isEditorVisible() {
        try {
            // Check for presence of any editor indicator
            String selector = "app-custom-editor, #page-container-customeditor, .canvas-container, #triggerInput, .editor-header, #connectName";
            java.util.List<WebElement> elements = driver.findElements(By.cssSelector(selector));
            if (elements.isEmpty()) {
                return false;
            }
            // If any indicator is displayed, or if we have at least one significant element
            for (WebElement el : elements) {
                if (el.isDisplayed())
                    return true;
            }
            // Fallback: if #connectName is present, we are likely in the editor even if not
            // fully visible
            return driver.findElements(By.id("connectName")).size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void selectTriggerApp(String appName) {
        logger.info("Selecting trigger app: {}", appName);
        WebElement input = waits.waitForVisible(triggerInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    public void selectActionApp(String appName) {
        logger.info("Selecting action app: {}", appName);
        WebElement input = waits.waitForVisible(actionInput);
        input.clear();
        input.sendKeys(appName);
        selectFromDropdown(appName);
    }

    public void selectTriggerEvent(String eventName) {
        logger.info("Selecting trigger event: {}", eventName);
        By eventSelector = By
                .xpath("//button[contains(., '" + eventName + "')] | //button[contains(text(), '" + eventName + "')]");
        waits.waitForClickable(eventSelector).click();
        waits.waitForLoader();
    }

    public void selectActionEvent(String eventName) {
        logger.info("Selecting action event: {}", eventName);
        By eventSelector = By
                .xpath("//button[contains(., '" + eventName + "')] | //button[contains(text(), '" + eventName + "')]");
        waits.waitForClickable(eventSelector).click();
        waits.waitForLoader();
    }

    public void clickContinue() {
        logger.info("Clicking Continue button...");
        By continueBtn = By.xpath(
                "//button[contains(text(), 'Continue')] | //button[contains(., 'Continue')] | button.continue-btn");
        waits.waitForClickable(continueBtn).click();
    }

    public void clickContinueRunTest() {
        logger.info("Clicking Continue Run Test button...");
        By runTestBtn = By.xpath(
                "//button[contains(text(), 'Continue Run Test')] | //button[contains(., 'Run Test')] | button.run-test");
        waits.waitForClickable(runTestBtn).click();
    }

    public void clickActivateConnect() {
        logger.info("Clicking Activate Connect button...");
        By activateBtn = By.xpath(
                "//button[contains(text(), 'Activate Connect')] | //button[contains(., 'Activate')] | button.activate-connect");
        waits.waitForClickable(activateBtn).click();
    }

    private void selectFromDropdown(String appName) {
        // Wait for the dropdown to populate
        try {
            waits.waitForVisible(dropdownItems);
            WebElement appBtn = driver.findElements(dropdownItems).stream()
                    .filter(btn -> btn.getText().toLowerCase().contains(appName.toLowerCase()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("App not found in dropdown: " + appName));

            logger.info("Clicking app button: {}", appBtn.getText());
            appBtn.click();
            waits.waitForLoader(); // Wait for next stage (event selection or loading)
        } catch (Exception e) {
            logger.error("Failed to select app from dropdown: {}", e.getMessage());
            throw e;
        }
    }
}
