package base;

import org.testng.IExecutionListener;

import java.util.logging.Level;

public class TestNGInitListener implements IExecutionListener {

    @Override
    public void onExecutionStart() {
        try {
            // Silence java.util.logging global logger used by Selenium/CDP
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.setLevel(Level.SEVERE);
            for (java.util.logging.Handler h : root.getHandlers()) {
                h.setLevel(Level.SEVERE);
            }

            // Silence the selenium package loggers that use JUL
            java.util.logging.Logger.getLogger("org.openqa.selenium").setLevel(Level.SEVERE);
            java.util.logging.Logger.getLogger("org.openqa.selenium.devtools").setLevel(Level.SEVERE);

            // Optionally, silence other noisy loggers early
            System.setProperty("testng.verbose", "0");
        } catch (Exception ignored) {}
    }

    @Override
    public void onExecutionFinish() {
        // no-op
    }
}
