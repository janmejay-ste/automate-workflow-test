package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import utils.WaitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class AppyPieAutomatePage {

    private static final Logger LOG = LoggerFactory.getLogger(AppyPieAutomatePage.class);

    private WebDriver driver;
    private WaitUtils waits;

    private By headerTitle = By.xpath("//h1[contains(text(),'Automate')]");
    private By menuItems = By.xpath("//ul[contains(@class,'navbar-nav')]//a"); // Adjust based on DOM

    public AppyPieAutomatePage(WebDriver driver) {
        this.driver = driver;
        this.waits = new WaitUtils(driver, Duration.ofSeconds(20));
    }

    public boolean isFullyLoaded() {
        waits.waitForVisible(headerTitle);
        // still check document.readyState directly for completeness
        new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(5))
                .until(webDriver -> ((JavascriptExecutor) webDriver).executeScript("return document.readyState").equals("complete"));

        return true;
    }

    public long getLoadTime() {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Long loadTime = (Long) js.executeScript("return performance.timing.loadEventEnd - performance.timing.navigationStart;");
        return loadTime;
    }

    public List<String> getMenuTexts() {
        waits.waitForVisible(menuItems);
        List<WebElement> els = driver.findElements(menuItems);
        return els.stream().map(e -> e.getText().trim()).filter(t -> !t.isEmpty()).collect(Collectors.toList());
    }

    public boolean checkSpellingErrors(List<String> menuTexts) {
        for (String text : menuTexts) {
            if (text.matches(".*[^a-zA-Z ]+.*")) {
                LOG.warn("Suspicious menu text: {}", text);
                return false;
            }
        }
        return true;
    }

    public long getLCP() {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object entry = js.executeScript("let entries = performance.getEntriesByType('largest-contentful-paint');"
                + "return entries && entries.length > 0 ? entries[entries.length - 1].startTime : -1;");

        if (entry instanceof Long)
            return (Long) entry;
        if (entry instanceof Number)
            return ((Number) entry).longValue();
        return -1; // fallback
    }

    public double getCLS() {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object cls = js.executeScript("let shifts = performance.getEntriesByType('layout-shift');"
                + "return shifts && shifts.length > 0 ?" + "shifts.reduce((sum, e) => sum + e.value, 0) : 0;");
        return ((Number) cls).doubleValue();
    }

    public void scrollToBottom() {
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
    }

    public void scrollToElement(By locator) {
        WebElement element = waits.waitForVisible(locator);
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
    }

}