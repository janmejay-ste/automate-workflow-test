package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.openqa.selenium.By;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.LoginPage;

@TestCategory(type = TestType.SANITY, feature = "Authentication")
public class LoginTest extends BaseTest {

    @Test(groups = {"smoke", "sanity"})
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
