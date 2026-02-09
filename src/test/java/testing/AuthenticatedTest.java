package testing;

import base.BaseTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import utils.ManualLoginHelper;

/**
 * Template test class for authenticated scenarios.
 * Run with: mvn test -Dtest=AuthenticatedTest -Dheadless=false
 * 
 * Note: headless=false is required for manual login!
 */
public class AuthenticatedTest extends BaseTest {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticatedTest.class);
    private ManualLoginHelper loginHelper;
    private boolean isLoggedIn = false;

    @BeforeClass(alwaysRun = true)
    public void loginSetup() {
        // Initialize driver first
        super.setUp();

        loginHelper = new ManualLoginHelper(driver);

        // Wait for manual login
        isLoggedIn = loginHelper.waitForManualLogin();

        Assert.assertTrue(isLoggedIn, "Manual login was not completed successfully");
        LOG.info("Login complete. Ready for authenticated tests.");
    }

    // =====================================================================
    // ADD YOUR AUTHENTICATED TEST SCENARIOS BELOW
    // =====================================================================

    @Test(groups = "authenticated", priority = 1)
    public void verifyLoggedInState() {
        LOG.info("Verifying logged in state...");
        LOG.info("Current URL: {}", driver.getCurrentUrl());

        // Verify we're not on login page
        Assert.assertFalse(driver.getCurrentUrl().contains("login"),
                "Should not be on login page after authentication");

        LOG.info("Logged in state verified successfully");
    }

    // TODO: Add more authenticated test scenarios here
    // Example:
    // @Test(groups = "authenticated", priority = 2)
    // public void testCreateNewConnect() {
    // // Navigate to create connect page
    // // Fill in connect details
    // // Verify connect was created
    // }
}
