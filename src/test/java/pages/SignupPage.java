package pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import pages.auth.AuthState;

import java.time.Duration;

import static pages.WaitUtils.waitForAuthTransition;

public class SignupPage {

    private final WebDriver driver;

    private static final By SIGNUP_LINK =
            By.cssSelector("a[href*='register'], a[href*='signup'], a.btn-signup");

    public SignupPage(WebDriver driver) {
        this.driver = driver;
    }

    public void startSignup() {
        driver.findElement(SIGNUP_LINK).click();
    }

    public void waitForSignupFlowStart() throws InterruptedException {
        waitForAuthTransition(driver, Duration.ofSeconds(30));
    }

    public boolean isInValidState() {
        return AuthState.isOnIdP(driver)
                || AuthState.isOnAuthRoute(driver)
                || AuthState.hasSession(driver);
    }
}
