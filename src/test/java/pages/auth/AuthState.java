package pages.auth;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

public final class AuthState {

    private AuthState() {
    }

    public static boolean isOnIdP(WebDriver driver) {
        return driver.getCurrentUrl().contains("accounts.appypie.com");
    }

    public static boolean isOnAuthRoute(WebDriver driver) {
        String url = driver.getCurrentUrl().toLowerCase();
        return url.contains("/login") || url.contains("/signup");
    }

    public static boolean hasSession(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object sessionData = js.executeScript(
                    "return (window.localStorage && (" +
                            "  localStorage.getItem('authToken') || " +
                            "  localStorage.getItem('accessToken') || " +
                            "  localStorage.getItem('token') || " +
                            "  localStorage.getItem('user_data') || " +
                            "  localStorage.getItem('userInfo')" +
                            ")) || document.cookie.indexOf('PHPSESSID') !== -1 " +
                            "   || document.cookie.indexOf('session') !== -1 " +
                            "   || document.cookie.indexOf('auth') !== -1;");
            return sessionData != null && !sessionData.toString().equals("false");
        } catch (Exception e) {
            return false;
        }
    }
}
