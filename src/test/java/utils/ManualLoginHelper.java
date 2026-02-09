package utils;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Utility to pause test execution for manual login.
 * Navigates to login page, waits for user to complete login,
 * then continues with authenticated test scenarios.
 */
public class ManualLoginHelper {

    private static final Logger LOG = LoggerFactory.getLogger(ManualLoginHelper.class);
    private static final String LOGIN_URL = "https://accounts.appypie.com/login";

    private final WebDriver driver;

    public ManualLoginHelper(WebDriver driver) {
        this.driver = driver;
    }

    /**
     * Navigates to login page and waits for user to press Enter after manual login.
     * 
     * @return true if login appears successful (URL changed from login page)
     */
    public boolean waitForManualLogin() {
        return waitForManualLogin(LOGIN_URL);
    }

    /**
     * Navigates to specified login URL and waits for user to press Enter.
     * 
     * @param loginUrl The login page URL
     * @return true if login appears successful
     */
    public boolean waitForManualLogin(String loginUrl) {
        LOG.info("Navigating to login page: {}", loginUrl);
        driver.get(loginUrl);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  MANUAL LOGIN REQUIRED");
        System.out.println("=".repeat(60));
        System.out.println("  Browser is open at: " + loginUrl);
        System.out.println("  Please login manually in the browser.");
        System.out.println("  Press ENTER here when login is complete...");
        System.out.println("=".repeat(60) + "\n");

        // Wait for user to press Enter
        Scanner scanner = new Scanner(System.in);
        scanner.nextLine();

        String currentUrl = driver.getCurrentUrl();
        boolean loginSuccessful = !currentUrl.contains("login") && !currentUrl.contains("register");

        if (loginSuccessful) {
            LOG.info("Manual login completed successfully. Current URL: {}", currentUrl);
        } else {
            LOG.warn("Login may not be complete. Still on URL: {}", currentUrl);
        }

        return loginSuccessful;
    }

    /**
     * Checks if user is currently logged in by verifying URL doesn't contain login
     * patterns.
     */
    public boolean isLoggedIn() {
        String currentUrl = driver.getCurrentUrl();
        return !currentUrl.contains("login") &&
                !currentUrl.contains("register") &&
                !currentUrl.contains("accounts.appypie.com");
    }
}
