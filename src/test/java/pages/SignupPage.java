package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import utils.WaitUtils;

public class SignupPage {

    private final WebDriver driver;

    // Stable, semantic locator
    private final By signupButton =
            By.cssSelector("form button[type='submit']");

    public SignupPage(WebDriver driver) {
        this.driver = driver;
    }

    // =========================
    // STATE CHECKS
    // =========================

    public boolean isSignupButtonDisabled() {
        WebElement btn = WaitUtils.waitForPresent(driver, signupButton);

        if (!btn.isDisplayed()) {
            throw new IllegalStateException("Signup button exists but is not visible");
        }

        // HTML disabled attribute
        if (btn.getAttribute("disabled") != null) return true;

        // aria-disabled
        if ("true".equalsIgnoreCase(btn.getAttribute("aria-disabled"))) return true;

        // CSS-based disabled patterns
        String cls = btn.getAttribute("class");
        if (cls != null && (
                cls.contains("disabled")
                || cls.contains("is-disabled")
                || cls.contains("btn-disabled")
        )) return true;

        // Selenium fallback
        return !btn.isEnabled();
    }
}
