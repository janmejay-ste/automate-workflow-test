package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;

public class DashboardLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(DashboardLauncher.class);
    private static final String DASHBOARD = "reports/trend/dashboard.html";

    @SuppressWarnings("deprecation")
	public static void launchIfEnabled() {
        try {
            boolean explicit = Boolean.getBoolean("trend.open");
            boolean ci = isCiEnvironment();

            boolean desktopAvailable = false;
            try {
                desktopAvailable = Desktop.isDesktopSupported() && !GraphicsEnvironment.isHeadless();
            } catch (Throwable t) {
                desktopAvailable = false;
            }

            boolean shouldOpen = explicit || (desktopAvailable && !ci);

            if (!shouldOpen) {
                LOG.debug("Dashboard opening skipped (explicit={}, desktopAvailable={}, ci={})", explicit, desktopAvailable, ci);
                return;
            }

            File f = new File(DASHBOARD);
            if (!f.exists()) {
                LOG.warn("Dashboard file not found, will not attempt to open: {}", f.getAbsolutePath());
                return;
            }

            if (desktopAvailable) {
                try {
                    LOG.info("Opening dashboard using Desktop.browse(): {}", f.getAbsolutePath());
                    Desktop.getDesktop().browse(f.toURI());
                    LOG.info("Dashboard launched: {}", f.getAbsolutePath());
                    return;
                } catch (Throwable t) {
                    LOG.debug("Desktop.browse() failed: {}", t.getMessage());
                    // fallback to Runtime.exec below
                }
            }

            String cmd;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) {
                cmd = "open " + quotePath(f.getAbsolutePath());
            } else if (os.contains("win")) {
                cmd = "cmd /c start " + quotePath(f.getAbsolutePath());
            } else {
                cmd = "xdg-open " + quotePath(f.getAbsolutePath());
            }
            LOG.info("Launching dashboard using command: {}", cmd);
            Runtime.getRuntime().exec(cmd);
            LOG.info("Dashboard launched: {}", f.getAbsolutePath());
        } catch (Exception ex) {
            LOG.debug("Failed launching dashboard: {}", ex.getMessage());
        }
    }

    private static boolean isCiEnvironment() {
        try {
            Map<String, String> env = System.getenv();
            // Common CI env vars: CI (general), GITHUB_ACTIONS, TRAVIS, JENKINS_URL, GITLAB_CI
            if (env.containsKey("CI")) return true;
            if (env.containsKey("GITHUB_ACTIONS")) return true;
            if (env.containsKey("TRAVIS")) return true;
            if (env.containsKey("JENKINS_URL")) return true;
            if (env.containsKey("GITLAB_CI")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static String quotePath(String p) {
        if (p == null) return "";
        if (p.contains(" ")) return '"' + p + '"';
        return p;
    }
}