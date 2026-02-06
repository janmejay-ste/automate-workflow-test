package testing;

import base.BaseTest;
import org.openqa.selenium.By;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.LoginPage;

public class LoginTest extends BaseTest {

    @Test(groups = "login")
    public void loginFlowStartsCorrectly() throws InterruptedException {

        // Click the login link to start auth flow
        driver.findElement(By.cssSelector("a[title='log in']")).click();

        LoginPage login = new LoginPage(driver);
        login.waitForLoginFlowStart();

        Assert.assertTrue(
                login.isInValidState(),
                "Login did not reach a valid auth state. URL=" + driver.getCurrentUrl()
        );
    }
}
