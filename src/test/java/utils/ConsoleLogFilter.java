package utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.health.HealthTracker;

import java.util.List;
import java.util.logging.Level;

public final class ConsoleLogFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleLogFilter.class);

    private static final List<String> IGNORE = List.of(
            "frame-ancestors",
            "swiper is not defined",
            "deprecated");

    private static final List<String> AUTH = List.of(
            "fedcm", "accounts.google.com", "identity provider");

    private ConsoleLogFilter() {
    }

    public static void capture(WebDriver driver, String context) {
        try {
            LogEntries logs = driver.manage().logs().get(LogType.BROWSER);
            HealthTracker tracker = HealthTracker.get();

            for (LogEntry e : logs) {
                String msg = e.getMessage();
                if (msg == null)
                    continue;
                String l = msg.toLowerCase();

                if (matches(l, IGNORE))
                    continue;

                if (matches(l, AUTH)) {
                    LOG.debug("[AUTH] {}", msg);
                    continue;
                }

                if (e.getLevel().intValue() >= Level.SEVERE.intValue()
                        || l.contains("uncaught")) {
                    tracker.recordJsError(context, msg, false);
                    utils.analytics.JsErrorTracker.get().recordError(context, msg, null);
                }

            }
        } catch (Exception ex) {
            LOG.warn("Console capture failed: {}", ex.getMessage());
        }
    }

    private static boolean matches(String s, List<String> list) {
        return list.stream().anyMatch(s::contains);
    }

    public static void ignoreKnownErrors(WebDriver driver) {

        capture(driver, "GLOBAL");
    }
}
