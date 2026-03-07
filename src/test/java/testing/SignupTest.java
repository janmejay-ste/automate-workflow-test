package testing;

import base.BaseTest;
import base.TestCategory;
import base.TestType;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.SignupPage;

@TestCategory(type = TestType.SANITY, feature = "Authentication")
public class SignupTest extends BaseTest {

    @Test(groups = {"smoke", "sanity"})
    public void signupFlowStartsCorrectly() throws InterruptedException {

        SignupPage signup = new SignupPage(driver);

        signup.startSignup();
        signup.waitForSignupFlowStart();

        Assert.assertTrue(
                signup.isInValidState(),
                "Signup did not reach a valid auth state. URL=" + driver.getCurrentUrl()
        );
    }
}
