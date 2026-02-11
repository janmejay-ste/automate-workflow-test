package pages;

import org.openqa.selenium.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for responsive/viewport testing.
 * Provides methods to resize browser and validate responsive behavior.
 */
public class ResponsiveHelper {

    private static final Logger LOG = LoggerFactory.getLogger(ResponsiveHelper.class);

    // Standard viewport sizes
    public static final Dimension MOBILE_SMALL = new Dimension(320, 568); // iPhone SE
    public static final Dimension MOBILE_MEDIUM = new Dimension(375, 667); // iPhone 6/7/8
    public static final Dimension MOBILE_LARGE = new Dimension(414, 896); // iPhone XR/11
    public static final Dimension TABLET_PORTRAIT = new Dimension(768, 1024); // iPad
    public static final Dimension TABLET_LANDSCAPE = new Dimension(1024, 768); // iPad landscape
    public static final Dimension DESKTOP_SMALL = new Dimension(1280, 720); // HD
    public static final Dimension DESKTOP_MEDIUM = new Dimension(1440, 900); // Standard
    public static final Dimension DESKTOP_LARGE = new Dimension(1920, 1080); // Full HD

    private final WebDriver driver;
    private Dimension originalSize;

    public ResponsiveHelper(WebDriver driver) {
        this.driver = driver;
    }

    // --------------------------------------------------
    // Viewport Management
    // --------------------------------------------------

    public void saveOriginalSize() {
        originalSize = driver.manage().window().getSize();
        LOG.info("Saved original window size: {}x{}", originalSize.getWidth(), originalSize.getHeight());
    }

    public void restoreOriginalSize() {
        if (originalSize != null) {
            driver.manage().window().setSize(originalSize);
            LOG.info("Restored original window size");
            waitForResize();
        }
    }

    public void setViewport(Dimension dimension) {
        driver.manage().window().setSize(dimension);
        LOG.info("Set viewport to {}x{}", dimension.getWidth(), dimension.getHeight());
        waitForResize();
    }

    public void setMobileViewport() {
        setViewport(MOBILE_MEDIUM);
    }

    public void setTabletViewport() {
        setViewport(TABLET_PORTRAIT);
    }

    public void setDesktopViewport() {
        setViewport(DESKTOP_MEDIUM);
    }

    private void waitForResize() {
        try {
            Thread.sleep(500); // Allow browser to adjust
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------
    // Viewport Detection
    // --------------------------------------------------

    public int getCurrentWidth() {
        return driver.manage().window().getSize().getWidth();
    }

    public int getCurrentHeight() {
        return driver.manage().window().getSize().getHeight();
    }

    public boolean isMobileViewport() {
        return getCurrentWidth() < 768;
    }

    public boolean isTabletViewport() {
        int width = getCurrentWidth();
        return width >= 768 && width < 1024;
    }

    public boolean isDesktopViewport() {
        return getCurrentWidth() >= 1024;
    }

    // --------------------------------------------------
    // Responsive Element Validation
    // --------------------------------------------------

    public boolean isElementVisible(By locator) {
        try {
            WebElement element = driver.findElement(locator);
            return element.isDisplayed();
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    public boolean isElementHidden(By locator) {
        try {
            WebElement element = driver.findElement(locator);
            return !element.isDisplayed();
        } catch (NoSuchElementException e) {
            return true; // Element not found = hidden
        }
    }

    public boolean elementFitsViewport(By locator) {
        try {
            WebElement element = driver.findElement(locator);
            Rectangle rect = element.getRect();
            int viewportWidth = getCurrentWidth();

            // Check if element width exceeds viewport (causes horizontal scroll)
            return rect.getWidth() <= viewportWidth;
        } catch (NoSuchElementException e) {
            return true;
        }
    }

    // --------------------------------------------------
    // Mobile-Specific Checks
    // --------------------------------------------------

    public boolean hasMobileMenu() {
        // Common mobile menu selectors
        By[] mobileMenuSelectors = {
                By.cssSelector(".hamburger, .mobile-menu, .nav-toggle"),
                By.cssSelector("[class*='hamburger'], [class*='mobile-menu']"),
                By.cssSelector("button[aria-label*='menu'], button[aria-label*='Menu']"),
                By.xpath("//button[contains(@class, 'menu') or contains(@class, 'toggle')]")
        };

        for (By selector : mobileMenuSelectors) {
            try {
                if (!driver.findElements(selector).isEmpty()) {
                    WebElement menu = driver.findElement(selector);
                    if (menu.isDisplayed()) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public boolean hasHorizontalScroll() {
        Long scrollWidth = (Long) ((JavascriptExecutor) driver)
                .executeScript("return document.documentElement.scrollWidth");
        Long clientWidth = (Long) ((JavascriptExecutor) driver)
                .executeScript("return document.documentElement.clientWidth");

        boolean hasScroll = scrollWidth > clientWidth;
        if (hasScroll) {
            LOG.warn("Horizontal scroll detected: scrollWidth={}, clientWidth={}", scrollWidth, clientWidth);
        }
        return hasScroll;
    }

    // --------------------------------------------------
    // Content Validation
    // --------------------------------------------------

    public boolean contentIsReadable(By locator) {
        try {
            WebElement element = driver.findElement(locator);
            Rectangle rect = element.getRect();

            // Check for minimum readable size
            return rect.getWidth() >= 200 && rect.getHeight() >= 20;
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    public boolean imagesAreResponsive() {
        for (WebElement img : driver.findElements(By.tagName("img"))) {
            try {
                Rectangle rect = img.getRect();
                int viewportWidth = getCurrentWidth();

                if (rect.getWidth() > viewportWidth) {
                    LOG.warn("Image exceeds viewport: {} > {}", rect.getWidth(), viewportWidth);
                    return false;
                }
            } catch (Exception ignored) {
            }
        }
        return true;
    }
}
