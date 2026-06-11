package testing.dashboard;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import utils.JsConsoleMonitor;

/**
 * Phase D1/D2 — Browser-level validation that the new dashboard panels behave
 * as designed when an operator opens the rendered HTML.
 *
 * <p>This test exists because marker-count verification (which classes/IDs are
 * present in the HTML) proves structure, not behavior. The whole purpose of D1
 * and D2 is operator visibility — until somebody actually clicks the toggles
 * and sees the panels collapse, the feature's primary use case is unverified.</p>
 *
 * <p>Loads the dashboard via {@code file://} URL so the test runs without a
 * web server. Asserts on rendered state, not just HTML text presence.</p>
 *
 * <h3>Assumptions:</h3>
 * <ul>
 *   <li>A previous suite run has already generated {@code reports/trend/dashboard.html}.
 *       This test does not re-trigger dashboard generation — it asserts on the
 *       file that already exists.</li>
 *   <li>The dashboard was generated AFTER D1+D2 were wired (otherwise the
 *       sections won't be in the HTML and the test fails early with a clear
 *       message).</li>
 * </ul>
 */
@TestCategory(type = TestType.SANITY, feature = "Dashboard > D1/D2 panels")
public class DashboardPanelInteractionTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardPanelInteractionTest.class);

    private static final By D1_SECTION   = By.cssSelector(".platform-health-section");
    private static final By D1_TOGGLE    = By.cssSelector(".platform-health-toggle");
    private static final By D1_CONTENT   = By.id("platform-health-content");
    private static final By D2_SECTION   = By.cssSelector(".data-quality-section");
    private static final By D2_TOGGLE    = By.cssSelector(".data-quality-toggle");
    private static final By D2_CONTENT   = By.id("data-quality-content");

    /** Opens the locally-generated dashboard via file:// URL. */
    private void openDashboard() {
        Path dash = Paths.get("reports", "trend", "dashboard.html").toAbsolutePath();
        Assert.assertTrue(java.nio.file.Files.exists(dash),
                "Dashboard not generated yet — run a suite first. Expected at: " + dash);
        String url = "file:///" + dash.toString().replace("\\", "/");
        driver.get(url);
        new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
    }

    @Test(groups = {"sanity"})
    public void platformHealthPanelIsOpenByDefault() {
        openDashboard();
        WebElement section = driver.findElement(D1_SECTION);
        WebElement content = driver.findElement(D1_CONTENT);

        Assert.assertFalse(section.getAttribute("class").contains("collapsed"),
                "D1 section should NOT have 'collapsed' class on initial render");
        Assert.assertTrue(content.isDisplayed(),
                "D1 content should be visible on initial render");
        // Content should have non-zero height
        long offsetHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(offsetHeight > 50,
                "D1 content should have measurable height (open-by-default), got: " + offsetHeight);
        LOG.info("D1 open-by-default verified — content height={}px", offsetHeight);
    }

    @Test(groups = {"sanity"})
    public void dataQualityPanelIsOpenByDefault() {
        openDashboard();
        WebElement section = driver.findElement(D2_SECTION);
        WebElement content = driver.findElement(D2_CONTENT);

        Assert.assertFalse(section.getAttribute("class").contains("collapsed"),
                "D2 section should NOT have 'collapsed' class on initial render");
        long offsetHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(offsetHeight > 50,
                "D2 content should have measurable height (open-by-default), got: " + offsetHeight);
        LOG.info("D2 open-by-default verified — content height={}px", offsetHeight);
    }

    @Test(groups = {"sanity"})
    public void platformHealthCollapsesAndExpandsOnClick() {
        openDashboard();
        WebElement section = driver.findElement(D1_SECTION);
        WebElement toggle  = driver.findElement(D1_TOGGLE);
        WebElement content = driver.findElement(D1_CONTENT);

        long openHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(openHeight > 50, "Pre-click: content should be open");

        toggle.click();
        // CSS transition is 0.3s; wait for the class swap + animation
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        Assert.assertTrue(section.getAttribute("class").contains("collapsed"),
                "After click: D1 section should have 'collapsed' class");
        long collapsedHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(collapsedHeight < 10,
                "After click: content should collapse to near-zero height, got: " + collapsedHeight);

        // Click again → expand
        toggle.click();
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        Assert.assertFalse(section.getAttribute("class").contains("collapsed"),
                "After second click: D1 section should NOT have 'collapsed' class");
        long reopenedHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(reopenedHeight > 50,
                "After second click: content should re-expand, got: " + reopenedHeight);

        LOG.info("D1 collapse/expand cycle verified — open={}px → collapsed={}px → reopened={}px",
                openHeight, collapsedHeight, reopenedHeight);
    }

    @Test(groups = {"sanity"})
    public void dataQualityCollapsesAndExpandsOnClick() {
        openDashboard();
        WebElement section = driver.findElement(D2_SECTION);
        WebElement toggle  = driver.findElement(D2_TOGGLE);
        WebElement content = driver.findElement(D2_CONTENT);

        long openHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(openHeight > 50, "Pre-click: content should be open");

        toggle.click();
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        Assert.assertTrue(section.getAttribute("class").contains("collapsed"));
        long collapsedHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(collapsedHeight < 10,
                "After click: content should collapse, got: " + collapsedHeight);

        toggle.click();
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        Assert.assertFalse(section.getAttribute("class").contains("collapsed"));
        long reopenedHeight = ((Number) ((JavascriptExecutor) driver)
                .executeScript("return arguments[0].offsetHeight;", content)).longValue();
        Assert.assertTrue(reopenedHeight > 50);

        LOG.info("D2 collapse/expand cycle verified — open={}px → collapsed={}px → reopened={}px",
                openHeight, collapsedHeight, reopenedHeight);
    }

    @Test(groups = {"sanity"})
    public void cardsRenderWithAccentBorders() {
        openDashboard();
        // Look inside D1 content for any card with a colored left border. The cards
        // use inline style attribute, so we can't query by CSS class — instead we
        // grab all immediate-child divs of the grid and verify at least one has
        // a non-grey border-left color.
        WebElement content = driver.findElement(D1_CONTENT);
        java.util.List<WebElement> cards = content.findElements(By.cssSelector("div[style*='border-left']"));
        Assert.assertTrue(cards.size() >= 3,
                "D1 should render at least 3 cards with accent borders, got: " + cards.size());

        // Verify at least one card has a non-grey color. Compare against the
        // "neutral" / "deferred" grey #94a3b8 which would mean all panels are
        // reporting "Not reporting".
        int nonGrey = 0;
        for (WebElement c : cards) {
            String style = c.getAttribute("style");
            if (style != null && !style.toLowerCase().contains("#94a3b8")
                              && !style.toLowerCase().contains("#cbd5e1")) {
                nonGrey++;
            }
        }
        Assert.assertTrue(nonGrey > 0,
                "Expected at least one D1 card with a non-grey accent color "
              + "(i.e. real data flowing through), found 0 of " + cards.size());
        LOG.info("D1 cards: {} total, {} with non-grey accent border", cards.size(), nonGrey);
    }

    @Test(groups = {"sanity"})
    public void noJsErrorsLoadingDashboard() {
        openDashboard();
        // Give the page a moment to settle any async work
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        java.util.List<String> severe = JsConsoleMonitor.getSevereErrors(driver);
        long fatal = severe.stream()
                .filter(msg -> JsConsoleMonitor.classify(msg) == JsConsoleMonitor.Severity.FAIL)
                .count();
        Assert.assertEquals(fatal, 0L,
                "Dashboard loaded with FAIL-severity console errors: " + severe);
    }

    @Test(groups = {"sanity"})
    public void toggleHandlersAreDefined() {
        openDashboard();
        // The inline onclick attributes reference toggleCollapsibleSection;
        // verify the function actually exists in the page's JS context.
        Object exists = ((JavascriptExecutor) driver).executeScript(
                "return typeof toggleCollapsibleSection === 'function';");
        Assert.assertEquals(exists, Boolean.TRUE,
                "toggleCollapsibleSection should be defined as a function on the page");
    }
}
