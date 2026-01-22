package testing;

import base.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.LoginPage;

public class LoginTest extends BaseTest {

    @Test(groups = "login")
    public void loginRedirectsToIdentityProvider() {

        driver.get("https://www.appypie.com/login");

        LoginPage login = new LoginPage(driver);

        // Assert redirect happens
        login.waitForIdentityRedirect();

        Assert.assertTrue(
                login.isOnIdentityProvider(),
                "Login should redirect to identity provider. URL=" + driver.getCurrentUrl()
        );
    }
}
