package pages;

import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.WaitUtils;

public class AppyPieHomePage {

    private static final Logger LOG = LoggerFactory.getLogger(AppyPieHomePage.class);

    private WebDriver driver;
    private Actions actions;
    private WaitUtils waits;

    // removed obsolete Other Platforms locator (site no longer contains this element)
    private By automateOption = By.xpath("//a[contains(@href,'appypieautomate.ai')]");

    // Broader fallbacks: any anchor with 'automate' in href or visible text (case-insensitive)
    private By anyAutomateLinkHref = By.xpath("//a[contains(translate(@href,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'automate')]");
    private By anyAutomateLinkText = By.xpath("//a[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'automate')]");

    public AppyPieHomePage(WebDriver driver) {
        this.driver = driver;
        // increase wait to be tolerant of slow pages
        this.waits = new WaitUtils(driver, Duration.ofSeconds(20));
        this.actions = new Actions(driver);
    }

    public void navigateToAutomate() {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // If user is on the main appypie.com site, redirect them straight to the Automate site
        try {
            String cur = driver.getCurrentUrl().toLowerCase();
            if (cur.contains("appypie.com")) {
                // Prefer the primary automate domain (.com) and fall back to .ai
                String[] directCandidates = new String[] {
                        "https://appypieautomate.com",
                        "https://www.appypieautomate.com",
                        "https://appypieautomate.ai",
                        "https://www.appypieautomate.ai"
                };

                for (String url : directCandidates) {
                    try {
                        LOG.info("On appypie.com — redirecting user to {}", url);
                        driver.get(url);
                        waits.shortPause(1200);
                        if (driver.getCurrentUrl().toLowerCase().contains("automate")) {
                            LOG.info("Redirect to {} succeeded", url);
                            return;
                        }
                    } catch (Exception ex) {
                        LOG.warn("Direct redirect to {} failed: {}", url, ex.getMessage());
                    }
                }

                // If none of the direct redirects worked, continue to normal fallback attempts below
                LOG.warn("Direct redirects from appypie.com to Automate all failed — falling back to link/menu search");
            }
        } catch (Exception ignored) {
            // ignore and continue with regular attempts
        }

        // Allow page header scripts to load (they are slow + buggy)
        waits.shortPause(1500);
        js.executeScript("window.scrollTo(0, 0)");

        // First Attempt → Desktop hover menu
        // Try once, then retry quickly if hover didn't reveal the option (helps flaky headers)
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                // Use a class-based parent selector instead of relying on the removed 'Other Platforms' text
                By parentLi = By.xpath("//li[contains(@class,'feature-menu')]");

                WebElement li = waits.waitForVisible(parentLi);

                actions.moveToElement(li).pause(Duration.ofMillis(600)).perform();

                WebElement automate = waits.waitForClickable(automateOption);

                automate.click();
                LOG.info("Desktop navigation succeeded (attempt {})", attempt + 1);
                 return;

            } catch (Exception ex) {
                LOG.warn("⚠ Desktop menu not available on attempt {} → retrying...", attempt + 1);
                waits.shortPause(600);
            }
        }

        // SECOND ATTEMPT → Mobile hamburger
        try {
            WebElement hamburger = waits.waitForClickable(By.xpath("//button[contains(@class,'navbar-toggler')]") );

            hamburger.click();
            waits.shortPause(800);

            WebElement automateMobile = waits.waitForClickable(automateOption);

            automateMobile.click();
            LOG.info("Mobile navigation succeeded");
             return;

        } catch (Exception ex) {
            LOG.warn("Mobile menu also unavailable");
        }

        // BROAD SEARCH → try any link that looks like it points to Automate
        try {
            if (WaitUtils.isElementPresent(driver, anyAutomateLinkHref)) {
                WebElement link = driver.findElement(anyAutomateLinkHref);
                LOG.info("Found automate link by href ({}). Clicking...", link.getAttribute("href"));
                link.click();
                // give it a moment to navigate
                waits.shortPause(1000);
                if (driver.getCurrentUrl().toLowerCase().contains("automate")) {
                    LOG.info("Navigation via broad href link succeeded");
                    return;
                }
            } else if (WaitUtils.isElementPresent(driver, anyAutomateLinkText)) {
                WebElement link = driver.findElement(anyAutomateLinkText);
                LOG.info("Found automate link by text ({}). Clicking...", link.getText());
                link.click();
                waits.shortPause(1000);
                if (driver.getCurrentUrl().toLowerCase().contains("automate")) {
                    LOG.info("Navigation via broad text link succeeded");
                    return;
                }
            } else {
                LOG.warn("No visible 'Automate' link found via broad search");
            }
        } catch (Exception ex) {
            LOG.warn("Broad automate-link search/click failed: {}", ex.getMessage());
        }

        // FINAL FALLBACK → Try direct navigation to known automate domains before failing
        String[] candidates = new String[] {
                "https://appypieautomate.com",
                "https://www.appypieautomate.com",
                "https://appypieautomate.ai",
                "https://www.appypieautomate.ai"
        };

        for (String url : candidates) {
            try {
                LOG.warn("Attempting direct navigation to {} as a fallback", url);
                driver.get(url);
                // wait briefly for redirect/landing
                waits.shortPause(1200);
                if (driver.getCurrentUrl().toLowerCase().contains("automate")) {
                    LOG.info("Direct navigation to {} succeeded", url);
                    return;
                }
            } catch (Exception ex) {
                LOG.warn("Direct navigation to {} failed: {}", url, ex.getMessage());
            }
        }

        // If we reach here, all attempts failed — throw to preserve existing failure semantics
        throw new RuntimeException("Automate menu unreachable (desktop + mobile + link search + direct nav all failed)");
    }

}