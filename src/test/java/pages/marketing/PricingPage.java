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
 * Page Object for the flozic.ai pricing page.
 *
 * <p>Phase P2 of the marketing coverage rollout. Mirrors the {@link HomePage}
 * shape so the same smoke/functional/integrity/seo test template applies.</p>
 *
 * <p>Pricing-specific elements:</p>
 * <ul>
 *   <li>Plan/tier cards (3-4 typical)</li>
 *   <li>Primary CTA per card ("Try Free" / "Get Started" → /register)</li>
 *   <li>Contact Sales button (Calendly / sales URL)</li>
 *   <li>Optional annual/monthly billing toggle</li>
 *   <li>Optional FAQ accordion</li>
 * </ul>
 */
public final class PricingPage {

    private static final Logger LOG = LoggerFactory.getLogger(PricingPage.class);
    private static final int LOAD_TIMEOUT_SEC = 20;

    // Pricing slug — full URL constructed via UrlRegistry.integratePath().
    // All flozic.ai marketing sub-pages live under /integrate/{slug}.
    private static final String SLUG = "pricing-plan";

    private final WebDriver driver;
    private final MarketingHeaderComponent header;
    private final MarketingFooterComponent footer;

    // ── Selectors (defensive — flozic.ai DOM not pre-inspected) ───────────────

    /**
     * Pricing tier / plan cards. Production flozic.ai uses {@code .carousel_item .wrap_pricing}
     * as the per-tier container; defensive fallbacks remain for resilience against
     * future markup changes.
     */
    private static final By PLAN_CARDS = By.cssSelector(
            ".carousel_item .wrap_pricing, "
          + ".pricing-card, .plan-card, "
          + "[class*='pricing-tier'], [class*='plan-tier'], "
          + "[class*='pricing-plan'], [class*='price-card'], "
          + "[data-plan], [data-tier]");

    /**
     * Primary "BUY NOW" / "TRY NOW" CTA on Standard / Professional / Business tiers.
     * Production DOM: {@code <a class="upgrd-btn btn" onclick="try_buy(...)" href="javascript:void(0)">}.
     * Note: these are JS-handler buttons, NOT direct hrefs to /register — clicking them
     * triggers an internal {@code try_buy()} JS call that routes to login/checkout.
     */
    private static final By BUY_CTA = By.cssSelector("a.upgrd-btn");

    /**
     * Enterprise tier's "Contact Us" CTA. Production DOM:
     * {@code <a class="upgrd-contact btn" href="https://calendly.com/..." target="_blank">}.
     * Opens Calendly in a new tab.
     */
    private static final By ENTERPRISE_CONTACT_CTA = By.cssSelector("a.upgrd-contact");

    /**
     * "View all Features" expander button under each plan card.
     * Production DOM: {@code <button class="viewall-btn px-4">View all Features</button>}.
     */
    private static final By VIEW_ALL_FEATURES_BTN = By.cssSelector("button.viewall-btn");

    /**
     * Generic plan-CTA selector — union of buy + enterprise + view-all. Used by the
     * "at least one plan CTA visible" smoke assertion when we don't care which kind.
     */
    private static final By PLAN_CTA = By.cssSelector(
            "a.upgrd-btn, a.upgrd-contact, button.viewall-btn");

    /**
     * Billing-period toggle wrapper. Production DOM exposes monthly/yearly via
     * {@code #monthlyContent} / {@code #yearlyContent} with {@code display:none|block}.
     * The toggle control itself is elsewhere on the page; this selector detects
     * the content containers' presence.
     */
    private static final By BILLING_CONTENT_BLOCKS = By.cssSelector(
            "#monthlyContent, #yearlyContent");

    /** Contact Sales CTA. */
    private static final By CONTACT_SALES_CTA = By.cssSelector(
            "a[href*='calendly'], a[href*='contact-sales'], a[href*='contact_sales'], "
          + "a[title*='Contact Sales' i], a[title*='Contact sales' i]");

    /** Optional annual/monthly billing toggle. */
    private static final By BILLING_TOGGLE = By.cssSelector(
            "[class*='billing-toggle'], [class*='pricing-toggle'], "
          + "input[type='checkbox'][class*='annual'], "
          + "label[class*='annual'], button[class*='annual']");

    /** Optional FAQ section. */
    private static final By FAQ_SECTION = By.cssSelector(
            "[class*='faq'], [class*='FAQ'], section[id*='faq' i], "
          + "details, [class*='accordion']");

    /** Readiness signal — H1 or any rendered plan card. */
    private static final By DOC_READY_SIGNAL = By.cssSelector(
            "h1, main h1, [class*='pricing'] h1, [class*='pricing-card'], .plan-card");

    public PricingPage(WebDriver driver) {
        this.driver = driver;
        this.header = new MarketingHeaderComponent(driver);
        this.footer = new MarketingFooterComponent(driver);
    }

    // ── Navigation + readiness ────────────────────────────────────────────────

    public PricingPage navigate() {
        driver.get(UrlRegistry.integratePath(SLUG));
        return this;
    }

    public boolean isLoaded() {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(LOAD_TIMEOUT_SEC)).until(d -> {
                Object ready = ((JavascriptExecutor) d).executeScript("return document.readyState");
                return "complete".equals(ready);
            });
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.presenceOfElementLocated(DOC_READY_SIGNAL));
            return true;
        } catch (Exception e) {
            LOG.warn("Pricing page failed to reach loaded state: {}", e.getMessage());
            return false;
        }
    }

    // ── Plan / tier queries ────────────────────────────────────────────────────

    /** Returns the count of pricing tier cards rendered on the page. */
    public int planCardCount() {
        try {
            List<WebElement> cards = driver.findElements(PLAN_CARDS);
            // Filter to visible elements only — defensive selector may match
            // hidden hero-section duplicates on some templates.
            return (int) cards.stream().filter(WebElement::isDisplayed).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns the count of <em>actual</em> plan CTAs visible on the page.
     * Sums BUY NOW / TRY NOW buttons + Enterprise Contact Us + "View all Features"
     * expanders. Replaces the earlier "any clickable inside a card" heuristic with
     * the precise selectors observed in production DOM.
     */
    public int planCtaCount() {
        return buyCtaCount() + enterpriseContactCount() + viewAllFeaturesCount();
    }

    /** Visible BUY NOW / TRY NOW buttons. Production: one per non-Enterprise tier (typically 3). */
    public int buyCtaCount() {
        return (int) driver.findElements(BUY_CTA).stream()
                .filter(WebElement::isDisplayed)
                .count();
    }

    /** Visible Enterprise Contact Us CTAs. Production: 1 (Enterprise tier only). */
    public int enterpriseContactCount() {
        return (int) driver.findElements(ENTERPRISE_CONTACT_CTA).stream()
                .filter(WebElement::isDisplayed)
                .count();
    }

    /**
     * "View all Features" expander buttons present in the DOM. Production: 4 (one per tier).
     *
     * <p>Note: these buttons are CSS-hidden on desktop viewports — the features list
     * is shown inline (via the {@code .hidemobile} {@code <ul>}) instead. The buttons
     * are the mobile-viewport expander UI. The smoke assertion checks DOM presence,
     * not display state, so the test is viewport-independent.</p>
     */
    public int viewAllFeaturesCount() {
        // Intentionally NOT filtered by isDisplayed — these buttons are mobile-only
        // and would fail on desktop viewports otherwise.
        return driver.findElements(VIEW_ALL_FEATURES_BTN).size();
    }

    /** True if both billing-period content blocks ({@code #monthlyContent} + {@code #yearlyContent}) exist. */
    public boolean hasBillingContentBlocks() {
        return driver.findElements(BILLING_CONTENT_BLOCKS).size() >= 2;
    }

    /** True if at least one Contact Sales CTA is rendered. */
    public boolean hasContactSalesCta() {
        try {
            return driver.findElements(CONTACT_SALES_CTA).stream().anyMatch(WebElement::isDisplayed);
        } catch (Exception e) {
            return false;
        }
    }

    /** True if a billing-period toggle (Annual/Monthly) is rendered. */
    public boolean hasBillingToggle() {
        try {
            return driver.findElements(BILLING_TOGGLE).stream().anyMatch(WebElement::isDisplayed);
        } catch (Exception e) {
            return false;
        }
    }

    /** True if a FAQ section is rendered. */
    public boolean hasFaqSection() {
        try {
            return driver.findElements(FAQ_SECTION).stream().anyMatch(WebElement::isDisplayed);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Click actions ──────────────────────────────────────────────────────────

    /**
     * Clicks the first visible BUY NOW / TRY NOW plan CTA.
     *
     * <p><b>Important:</b> production DOM uses {@code onclick="try_buy(...)"} with
     * {@code href="javascript:void(0)"} — clicking does NOT necessarily route to
     * {@code /register}. The JS handler typically redirects to login (if anonymous)
     * or to a checkout flow (if authenticated). Callers should assert "URL changed"
     * or "auth domain reached", not "/register specifically".</p>
     */
    public void clickFirstBuyCta() {
        WebElement cta = new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(BUY_CTA));
        clickWithFallback(cta);
        LOG.info("First BUY CTA clicked — current URL: {}", driver.getCurrentUrl());
    }

    /**
     * Clicks the Enterprise "Contact Us" CTA.
     *
     * <p>Production DOM has {@code target="_blank"} — the Calendly URL opens in
     * a new tab. Callers should switch tabs / observe new-tab presence to verify.</p>
     */
    public void clickEnterpriseContactCta() {
        try {
            WebElement cta = driver.findElement(ENTERPRISE_CONTACT_CTA);
            clickWithFallback(cta);
            LOG.info("Enterprise Contact CTA clicked — current URL: {}", driver.getCurrentUrl());
        } catch (NoSuchElementException e) {
            throw new IllegalStateException("Enterprise Contact CTA not found on pricing page");
        }
    }

    /**
     * Clicks the first "View all Features" button. Production toggles expanded state
     * on the plan card (shows the features list).
     */
    public void clickFirstViewAllFeatures() {
        try {
            WebElement btn = driver.findElement(VIEW_ALL_FEATURES_BTN);
            clickWithFallback(btn);
            LOG.info("View all Features clicked");
        } catch (NoSuchElementException e) {
            throw new IllegalStateException("View all Features button not found");
        }
    }

    /**
     * Generic Contact Sales link — preserved for backward compatibility with the
     * earlier functional test that used the {@link #CONTACT_SALES_CTA} pattern
     * (which matches both the Enterprise Contact Us anchor and any nav-level
     * Contact Sales link).
     */
    public void clickContactSalesCta() {
        WebElement cta;
        try {
            // Prefer Enterprise contact (in-card) over any nav-level link
            cta = driver.findElements(ENTERPRISE_CONTACT_CTA).stream()
                    .filter(WebElement::isDisplayed).findFirst()
                    .orElseGet(() -> driver.findElement(CONTACT_SALES_CTA));
        } catch (NoSuchElementException e) {
            throw new IllegalStateException("No Contact Sales CTA found on pricing page");
        }
        clickWithFallback(cta);
        LOG.info("Contact Sales CTA clicked — current URL: {}", driver.getCurrentUrl());
    }

    /** Backward-compat alias — old test referenced clickFirstPlanCta. */
    public void clickFirstPlanCta() { clickFirstBuyCta(); }

    private void clickWithFallback(WebElement el) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block: 'center'});", el);
        try { el.click(); }
        catch (Exception e) { ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el); }
    }

    // ── Component accessors ───────────────────────────────────────────────────

    public MarketingHeaderComponent header() { return header; }
    public MarketingFooterComponent footer() { return footer; }
}
