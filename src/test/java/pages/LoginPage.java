package pages;

import org.openqa.selenium.WebDriver;
import utils.WaitUtils;

import java.time.Duration;

public class LoginPage {

    private final WebDriver driver;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
    }

    /**
     * Waits until the browser leaves appypie.com
     * and lands on the identity provider.
     */
    public void waitForIdentityRedirect() {
        WaitUtils.waitUntil(driver, Duration.ofSeconds(15), d ->
                d.getCurrentUrl().contains("accounts.appypie.com")
        );
    }

    public boolean isOnIdentityProvider() {
        return driver.getCurrentUrl().contains("accounts.appypie.com");
    }
}
