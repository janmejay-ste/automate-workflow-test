package pages.auth;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

public final class AuthState {

    private AuthState() {}

    public static boolean isOnIdP(WebDriver driver) {
        return driver.getCurrentUrl().contains("accounts.appypie.com");
    }

    public static boolean isOnAuthRoute(WebDriver driver) {
        String url = driver.getCurrentUrl().toLowerCase();
        return url.contains("/login") || url.contains("/signup");
    }

    public static boolean hasSession(WebDriver driver) {
        try {
            Object token = ((JavascriptExecutor) driver)
                    .executeScript(
                        "return window.localStorage && " +
                        "(localStorage.getItem('authToken') || " +
                        " localStorage.getItem('accessToken'));"
                    );
            return token != null;
        } catch (Exception e) {
            return false;
        }
    }
}
