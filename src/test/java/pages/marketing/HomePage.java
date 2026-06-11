package pages.marketing;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pages.marketing.components.MarketingFooterComponent;
import pages.marketing.components.MarketingHeaderComponent;
import utils.config.UrlRegistry;

/**
 * Page Object for the flozic.ai (marketing) homepage.
 *
 * <p>Phase P1 of the marketing coverage rollout. Establishes the page-object
 * shape that subsequent marketing pages (Pricing, Features, Signup, etc.)
 * follow:</p>
 * <ul>
 *   <li>{@link #navigate} — go to the page</li>
 *   <li>{@link #isLoaded} — readiness check</li>
 *   <li>{@link #header} / {@link #footer} — shared components</li>
 *   <li>Page-specific assertions (e.g. {@link #isHeroCtaVisible})</li>
 * </ul>
 */
public final class HomePage {

    private static final Logger LOG = LoggerFactory.getLogger(HomePage.class);
    private static final int LOAD_TIMEOUT_SEC = 20;

    private final WebDriver driver;
    private final MarketingHeaderComponent header;
    private final MarketingFooterComponent footer;

    // ── Selectors ──────────────────────────────────────────────────────────────
    // Hero CTA: defensive — flozic.ai's hero conversion target is a "Get Started"
    // / "Try Free" anchor pointing to /register. We accept multiple common
    // patterns rather than one rigid selector.
    private static final By HERO_CTA = By.cssSelector(
            "section a[href*='/register'], " +
            "section a[href*='/signup'], " +
            "div[class*='hero'] a[href*='/register'], " +
            "div[class*='hero'] a[href*='/signup'], " +
            "a[class*='hero'][href*='/register'], " +
            "a.btn-primary[href*='/register'], " +
            "a.cta-primary[href*='/register']");

    // Document-readiness signals — used for isLoaded().
    private static final By DOC_READY_SIGNAL = By.cssSelector("h1, [class*='hero'] h1, main h1");

    // ── "Email Us" modal (Bootstrap modal #myModal) ───────────────────────────
    // The modal lives in the homepage DOM (hidden by default) and is triggered
    // by some element elsewhere on the page. We discover the trigger via standard
    // Bootstrap modal-toggle patterns rather than hardcoding a specific selector.
    private static final By EMAIL_MODAL          = By.cssSelector("#myModal");
    private static final By EMAIL_MODAL_TITLE    = By.cssSelector("#myModal .modal-title");
    private static final By EMAIL_MODAL_CLOSE    = By.cssSelector("#myModal button[data-dismiss='modal'], #myModal button[data-bs-dismiss='modal'], #myModal .close");
    private static final By EMAIL_MODAL_BODY     = By.cssSelector("#myModal .modal-body");
    private static final By EMAIL_MODAL_FORM     = By.cssSelector("#myModal .wpcf7, #myModal form, #myModal [id^='wpcf7-']");
    private static final By EMAIL_MODAL_TRIGGER  = By.cssSelector(
            "[data-target='#myModal'], "
          + "[data-bs-target='#myModal'], "
          + "a[href='#myModal'], "
          + "[onclick*='myModal']");

    public HomePage(WebDriver driver) {
        this.driver = driver;
        this.header = new MarketingHeaderComponent(driver);
        this.footer = new MarketingFooterComponent(driver);
    }

    // ── Navigation + readiness ────────────────────────────────────────────────

    public HomePage navigate() {
        driver.get(UrlRegistry.MARKETING_BASE + "/");
        return this;
    }

    /** Waits up to 20s for the page to reach a usable state. */
    public boolean isLoaded() {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(LOAD_TIMEOUT_SEC)).until(d -> {
                Object ready = ((JavascriptExecutor) d).executeScript("return document.readyState");
                return "complete".equals(ready);
            });
            // H1 presence as additional structural signal
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.presenceOfElementLocated(DOC_READY_SIGNAL));
            return true;
        } catch (Exception e) {
            LOG.warn("Homepage failed to reach loaded state: {}", e.getMessage());
            return false;
        }
    }

    // ── Hero CTA ──────────────────────────────────────────────────────────────

    public boolean isHeroCtaVisible() {
        try {
            WebElement cta = driver.findElement(HERO_CTA);
            return cta.isDisplayed();
        } catch (NoSuchElementException e) {
            LOG.warn("Hero CTA not found with any of the defensive selectors");
            return false;
        }
    }

    /**
     * Clicks the primary hero CTA. Does NOT submit anything — the CTA routes
     * to {@code /register}; the receiving test asserts the URL change and
     * intentionally does not fill the form (per P1 scope).
     */
    public void clickHeroCta() {
        WebElement cta = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(HERO_CTA));
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block: 'center'});", cta);
        try {
            cta.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", cta);
        }
        LOG.info("Hero CTA clicked — current URL: {}", driver.getCurrentUrl());
    }

    // ── "Email Us" modal ──────────────────────────────────────────────────────

    /** True if the modal element exists in the DOM (whether visible or hidden). */
    public boolean isEmailModalPresentInDom() {
        return !driver.findElements(EMAIL_MODAL).isEmpty();
    }

    /** True if the modal is currently displayed (after a trigger click). */
    public boolean isEmailModalVisible() {
        try {
            WebElement m = driver.findElement(EMAIL_MODAL);
            // Bootstrap toggles either the `show` class OR style="display: block"
            String cls = m.getAttribute("class");
            String style = m.getAttribute("style");
            boolean hasShowClass = cls != null && cls.contains("show");
            boolean displayBlock = style != null && style.contains("display: block");
            return m.isDisplayed() && (hasShowClass || displayBlock);
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the modal title text (e.g. "Email Us"). Empty string if not found. */
    public String getEmailModalTitle() {
        try {
            return driver.findElement(EMAIL_MODAL_TITLE).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** True if a Bootstrap-style trigger that opens {@code #myModal} exists anywhere on the page. */
    public boolean isEmailModalTriggerPresent() {
        return !driver.findElements(EMAIL_MODAL_TRIGGER).isEmpty();
    }

    /**
     * Finds the first {@code #myModal} trigger element and clicks it. Bootstrap then
     * shows the modal via its own JS handler. After this returns, callers should poll
     * {@link #isEmailModalVisible()} (the modal animation takes ~300ms).
     *
     * @throws IllegalStateException if no trigger is found in the DOM
     */
    public void openEmailModal() {
        List<WebElement> triggers = driver.findElements(EMAIL_MODAL_TRIGGER);
        if (triggers.isEmpty()) {
            // Last resort: invoke the modal's show() via JS directly. Lets us still
            // verify the modal's structure even if we can't find the natural trigger.
            LOG.warn("No standard data-target trigger found for #myModal; invoking via JS");
            ((JavascriptExecutor) driver).executeScript(
                    "if (window.jQuery) { jQuery('#myModal').modal('show'); }");
            waitForEmailModalVisible(5);
            return;
        }
        WebElement trigger = triggers.stream()
                .filter(WebElement::isDisplayed)
                .findFirst()
                .orElse(triggers.get(0));
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block: 'center'});", trigger);
        try { trigger.click(); }
        catch (Exception e) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", trigger); }
        LOG.info("Email modal trigger clicked");
        waitForEmailModalVisible(5);
    }

    /** Clicks the modal's close (X) button. Waits up to 3s for the modal to fully hide. */
    public void closeEmailModal() {
        try {
            WebElement closeBtn = driver.findElement(EMAIL_MODAL_CLOSE);
            try { closeBtn.click(); }
            catch (Exception e) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", closeBtn); }
            new WebDriverWait(driver, Duration.ofSeconds(3))
                    .until(d -> !isEmailModalVisible());
            LOG.info("Email modal closed");
        } catch (Exception e) {
            LOG.warn("Failed to close email modal cleanly: {}", e.getMessage());
        }
    }

    private void waitForEmailModalVisible(int seconds) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(seconds))
                    .until(d -> isEmailModalVisible());
        } catch (Exception e) {
            LOG.warn("Email modal did not become visible within {}s", seconds);
        }
    }

    /** True if the modal body contains a Contact Form 7 form container. */
    public boolean emailModalHasContactForm() {
        return !driver.findElements(EMAIL_MODAL_FORM).isEmpty();
    }

    // ── Component accessors ───────────────────────────────────────────────────

    public MarketingHeaderComponent header() { return header; }
    public MarketingFooterComponent footer() { return footer; }
}
