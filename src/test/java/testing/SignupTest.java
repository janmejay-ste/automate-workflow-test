package testing;

import base.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SignupTest extends BaseTest {

	@Test
	public void signupRedirectsToIdentityProvider() {

		driver.get("https://www.appypie.com/signup");

		boolean redirected = driver.getCurrentUrl().contains("accounts.appypie.com")
				|| driver.getCurrentUrl().contains("/register");

		Assert.assertTrue(
				redirected,
				"Signup should redirect to identity provider. URL=" + driver.getCurrentUrl());
	}
}
