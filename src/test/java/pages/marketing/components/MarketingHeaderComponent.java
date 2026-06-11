package pages.marketing.components;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pages.ConnectTopNavigation;

/**
 * Marketing-site top navigation component.
 *
 * <p>Wraps {@link ConnectTopNavigation} — the existing nav POM was originally
 * scoped to the Connect product surface but the selectors target the marketing
 * site header (post-rebrand: {@code flozic.ai}). Rather than duplicate
 * selectors, this component delegates click-actions to it while presenting
 * a marketing-domain-flavored API.</p>
 *
 * <p>Created in Phase P1 of the marketing-pages coverage rollout. Used by every
 * marketing page object via {@code page.header()}.</p>
 */
public final class MarketingHeaderComponent {

    private static final Logger LOG = LoggerFactory.getLogger(MarketingHeaderComponent.class);

    private final WebDriver driver;
    private final ConnectTopNavigation delegate;

    // ── Self-contained visibility locators (don't depend on delegate state) ────
    // Structural (href-based) selectors are preferred over title-attribute selectors
    // because the latter are brand-text-dependent and break on rebrands. Each entry
    // accepts multiple href patterns to cover variant routing (e.g. /pricing vs /pricing-plan).
    private static final Map<String, By> PRIMARY_LINKS = primaryLinks();
    private static Map<String, By> primaryLinks() {
        Map<String, By> m = new LinkedHashMap<>();
        m.put("Features",       By.cssSelector("a[href*='/features'], a[title='features']"));
        m.put("App Directory",  By.cssSelector(
                "a[href*='/integrate/app-directory'], a[href*='/app-directory'], "
              + "a[href*='/integrations'], a[title='App Directory']"));
        m.put("AI Automation",  By.cssSelector(
                "a[href*='/ai-workflow'], a[href*='/ai-automation'], a[title='AI Automation']"));
        m.put("AI Agents",      By.cssSelector(
                "a[href*='/ai-agents'], a[href*='/agents'], a[title='AI Agents']"));
        m.put("MCP Server",     By.cssSelector("a[href*='/mcp'], a[title='MCP Server']"));
        m.put("Pricing",        By.cssSelector(
                "a[href*='/pricing'], a[href*='/pricing-plan'], a[title='Pricing']"));
        m.put("Blog",           By.cssSelector("a[href='/blog/'], a[href$='/blog/']"));
        m.put("Sign Up",        By.cssSelector(
                "a[href*='/register'], a[href*='/signup'], a[title='Sign Up']"));
        m.put("Login",          By.cssSelector(
                "a[href*='accounts.appypie'], a[href*='/login'], a[title='log in']"));
        return m;
    }

    public MarketingHeaderComponent(WebDriver driver) {
        this.driver   = driver;
        this.delegate = new ConnectTopNavigation(driver);
    }

    // ── Visibility queries (used by smoke tests) ───────────────────────────────

    /** True if every primary nav link is present in the DOM. */
    public boolean allPrimaryLinksVisible() {
        return missingLinks().isEmpty();
    }

    /** Returns the names of any primary links that are NOT findable. */
    public List<String> missingLinks() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, By> e : PRIMARY_LINKS.entrySet()) {
            if (driver.findElements(e.getValue()).isEmpty()) {
                missing.add(e.getKey());
            }
        }
        return missing;
    }

    /** Returns true if the header element itself is rendered. */
    public boolean isHeaderVisible() {
        try {
            WebElement header = driver.findElement(By.cssSelector("header, nav.navbar, [class*='header']"));
            return header.isDisplayed();
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    // ── Click actions (delegate to ConnectTopNavigation) ───────────────────────

    public void clickFeatures()      { delegate.openFeatures(); }
    public void clickAppDirectory()  { delegate.openAppDirectory(); }
    public void clickAIAutomation()  { delegate.openAIAutomation(); }
    public void clickAIConnects()    { delegate.openAIConnects(); }
    public void clickMCPServer()     { delegate.openMCPServer(); }
    public void clickPricing()       { delegate.openPricing(); }
    public void clickBlog()          { delegate.openBlog(); }
    public void clickContactSales()  { delegate.openContactSales(); }
    public void clickSignup()        { delegate.openSignup(); }
    public void clickLogin()         { delegate.openLogin(); }

    /** Helper: waits up to {@code seconds} for the URL to contain {@code fragment}. */
    public void waitForUrlContains(String fragment, int seconds) {
        new WebDriverWait(driver, Duration.ofSeconds(seconds))
                .until(d -> d.getCurrentUrl().toLowerCase().contains(fragment.toLowerCase()));
        LOG.info("URL transitioned to contain '{}': {}", fragment, driver.getCurrentUrl());
    }
}
