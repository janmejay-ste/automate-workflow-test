package pages;

import org.openqa.selenium.WebDriver;
import pages.auth.AuthState;

import java.time.Duration;

import static pages.WaitUtils.waitForAuthTransition;

public class LoginPage {

    private final WebDriver driver;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
    }

    public void waitForLoginFlowStart() throws InterruptedException {
        waitForAuthTransition(driver, Duration.ofSeconds(30));
    }

    public boolean isInValidState() {
        return AuthState.isOnIdP(driver)
                || AuthState.isOnAuthRoute(driver)
                || AuthState.hasSession(driver);
    }
}
