package testing;

import base.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;
import pages.SignupPage;

public class SignupTest extends BaseTest {

    @Test
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
