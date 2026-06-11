package pages.marketing.components;

import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marketing-site footer component.
 *
 * <p>Footer link inventory is discovered at runtime rather than hardcoded —
 * footer content varies more than header content across marketing pages.
 * Provides {@link #isVisible()} for smoke tests and {@link #getAllLinkHrefs()}
 * for integrity scans.</p>
 *
 * <p>Created in Phase P1 of the marketing-pages coverage rollout.</p>
 */
public final class MarketingFooterComponent {

    private static final Logger LOG = LoggerFactory.getLogger(MarketingFooterComponent.class);

    private final WebDriver driver;

    // Defensive selectors — footers on different sites use different patterns.
    private static final By FOOTER_ROOT = By.cssSelector(
            "footer, footer.site-footer, .footer, .site-footer, [role='contentinfo']");

    private static final By FOOTER_LINKS = By.cssSelector(
            "footer a, .footer a, [role='contentinfo'] a");

    public MarketingFooterComponent(WebDriver driver) {
        this.driver = driver;
    }

    /** True if a footer element is rendered and visible in the layout. */
    public boolean isVisible() {
        try {
            WebElement footer = driver.findElement(FOOTER_ROOT);
            // Scroll to footer to force render of any lazy-loaded content,
            // then check visibility.
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block: 'end', behavior: 'instant'});", footer);
            return footer.isDisplayed();
        } catch (NoSuchElementException e) {
            LOG.warn("Footer element not found in DOM");
            return false;
        }
    }

    /** Returns every href found inside the footer (relative or absolute). */
    public List<String> getAllLinkHrefs() {
        List<String> hrefs = new ArrayList<>();
        for (WebElement a : driver.findElements(FOOTER_LINKS)) {
            String href = a.getAttribute("href");
            if (href != null && !href.isBlank()) hrefs.add(href);
        }
        return hrefs;
    }

    /** Returns the count of distinct hrefs in the footer. Sanity-check helper. */
    public int linkCount() {
        return getAllLinkHrefs().size();
    }
}
