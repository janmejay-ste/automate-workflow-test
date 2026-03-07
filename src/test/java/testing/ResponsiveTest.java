package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import pages.ResponsiveHelper;
import utils.health.HealthTracker;

/**
 * Tests for responsive design across mobile, tablet, and desktop viewports.
 * Validates layout, navigation, and content display at different screen sizes.
 */
@TestCategory(type = TestType.REGRESSION, feature = "Responsive Design")
public class ResponsiveTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(ResponsiveTest.class);

    private ResponsiveHelper responsive;

    // Common locators for responsive checks
    private final By mainHeader = By.xpath("//h1[contains(normalize-space(),'Automate')]");
    private final By navMenu = By.cssSelector("ul.navbar-nav, nav, .nav-menu");
    private final By ctaButton = By.cssSelector("a[href*='signup'], .cta-button, .btn-primary");

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        super.setUp();
        responsive = new ResponsiveHelper(driver);
        responsive.saveOriginalSize();
    }

    @AfterMethod
    public void restoreViewport() {
        if (responsive != null) {
            responsive.restoreOriginalSize();
        }
    }

    // --------------------------------------------------
    // Mobile Viewport Tests
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 1)
    public void verifyMobileViewportRendersCorrectly() {
        LOG.info("Testing mobile viewport (375x667)");
        responsive.setMobileViewport();

        Assert.assertTrue(
                responsive.isMobileViewport(),
                "Viewport not set to mobile size");

        // Main header should still be visible
        Assert.assertTrue(
                responsive.isElementVisible(mainHeader),
                "Main header not visible on mobile");

        // Content should fit without horizontal scroll
        boolean hasHScroll = responsive.hasHorizontalScroll();
        if (hasHScroll) {
            HealthTracker.get().addWarning("Responsive",
                    "Horizontal scroll detected on mobile viewport");
        }

        LOG.info("Mobile viewport renders correctly");
    }

    @Test(groups = {"regression"}, priority = 2)
    public void verifyMobileMenuExists() {
        responsive.setMobileViewport();

        // On mobile, should have hamburger menu OR nav should be hidden
        boolean hasMobileMenu = responsive.hasMobileMenu();
        boolean navHidden = responsive.isElementHidden(navMenu);

        LOG.info("Mobile menu present: {}, Nav hidden: {}", hasMobileMenu, navHidden);

        // Either mobile menu is shown OR nav is adjusted
        if (!hasMobileMenu && !navHidden) {
            HealthTracker.get().addWarning("Responsive",
                    "Desktop nav shown on mobile without mobile menu");
        }
    }

    @Test(groups = {"regression"}, priority = 3)
    public void verifyMobileCTAVisible() {
        responsive.setMobileViewport();

        // CTA button should remain visible and clickable on mobile
        if (responsive.isElementVisible(ctaButton)) {
            Assert.assertTrue(
                    responsive.elementFitsViewport(ctaButton),
                    "CTA button overflows mobile viewport");
            LOG.info("CTA button visible and fits mobile viewport");
        } else {
            LOG.warn("CTA button not visible on mobile - may be in menu");
        }
    }

    // --------------------------------------------------
    // Tablet Viewport Tests
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 4)
    public void verifyTabletViewportRendersCorrectly() {
        LOG.info("Testing tablet viewport (768x1024)");
        responsive.setTabletViewport();

        Assert.assertTrue(
                responsive.isTabletViewport(),
                "Viewport not set to tablet size");

        // Main content should be visible
        Assert.assertTrue(
                responsive.isElementVisible(mainHeader),
                "Main header not visible on tablet");

        // No horizontal scroll
        boolean hasHScroll = responsive.hasHorizontalScroll();
        Assert.assertFalse(
                hasHScroll,
                "Horizontal scroll detected on tablet viewport");

        LOG.info("Tablet viewport renders correctly");
    }

    @Test(groups = {"regression"}, priority = 5)
    public void verifyTabletLayoutAdaptation() {
        responsive.setTabletViewport();

        // Images should be responsive
        Assert.assertTrue(
                responsive.imagesAreResponsive(),
                "Some images overflow tablet viewport");

        LOG.info("Tablet layout adapts correctly");
    }

    // --------------------------------------------------
    // Desktop Viewport Tests
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 6)
    public void verifyDesktopViewportRendersCorrectly() {
        LOG.info("Testing desktop viewport (1440x900)");
        responsive.setDesktopViewport();

        Assert.assertTrue(
                responsive.isDesktopViewport(),
                "Viewport not set to desktop size");

        // All main elements should be visible
        Assert.assertTrue(
                responsive.isElementVisible(mainHeader),
                "Main header not visible on desktop");

        Assert.assertTrue(
                responsive.isElementVisible(navMenu),
                "Navigation menu not visible on desktop");

        LOG.info("Desktop viewport renders correctly");
    }

    @Test(groups = {"regression"}, priority = 7)
    public void verifyDesktopNavigationVisible() {
        responsive.setDesktopViewport();

        // Full navigation should be visible on desktop
        Assert.assertTrue(
                responsive.isElementVisible(navMenu),
                "Navigation not visible on desktop");

        // Should NOT have hamburger menu on desktop
        boolean hasMobileMenu = responsive.hasMobileMenu();
        if (hasMobileMenu) {
            LOG.warn("Mobile menu visible on desktop - standard but not preferred");
        }

        LOG.info("Desktop navigation fully visible");
    }

    // --------------------------------------------------
    // Cross-Viewport Tests
    // --------------------------------------------------

    @Test(groups = {"regression"}, priority = 8)
    public void verifyViewportTransitions() {
        LOG.info("Testing viewport transition from mobile to desktop");

        // Start mobile
        responsive.setMobileViewport();
        boolean mobileVisible = responsive.isElementVisible(mainHeader);

        // Transition to tablet
        responsive.setTabletViewport();
        boolean tabletVisible = responsive.isElementVisible(mainHeader);

        // Transition to desktop
        responsive.setDesktopViewport();
        boolean desktopVisible = responsive.isElementVisible(mainHeader);

        Assert.assertTrue(mobileVisible && tabletVisible && desktopVisible,
                "Content not consistently visible across viewport changes");

        LOG.info("Viewport transitions work correctly");
    }

    @Test(groups = {"regression"}, priority = 9)
    public void verifySmallMobileViewport() {
        LOG.info("Testing small mobile viewport (320x568 - iPhone SE)");
        responsive.setViewport(ResponsiveHelper.MOBILE_SMALL);

        // Even on smallest mobile, content should work
        Assert.assertTrue(
                responsive.isElementVisible(mainHeader),
                "Content not visible on smallest mobile");

        // Critical check: no horizontal scroll
        boolean hasHScroll = responsive.hasHorizontalScroll();
        if (hasHScroll) {
            HealthTracker.get().addWarning("Responsive",
                    "Horizontal scroll on smallest mobile viewport (320px)");
        }

        LOG.info("Small mobile viewport handled correctly");
    }

    @Test(groups = {"regression"}, priority = 10)
    public void verifyLargeDesktopViewport() {
        LOG.info("Testing large desktop viewport (1920x1080 - Full HD)");
        responsive.setViewport(ResponsiveHelper.DESKTOP_LARGE);

        Assert.assertTrue(
                responsive.isElementVisible(mainHeader),
                "Content not visible on large desktop");

        // Content should be centered, not stretched edge-to-edge
        // This is a visual check - we log it for manual verification
        LOG.info("Large desktop renders - verify content is properly centered");
    }
}
