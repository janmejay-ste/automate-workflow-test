package utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Map;

public final class DashboardLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardLauncher.class);
    private static final File DASHBOARD = new File("reports/trend/dashboard.html");

    private DashboardLauncher() {
    }

    public static void launchIfEnabled() {
        if (!shouldLaunch()) {
            LOG.debug("Dashboard launch skipped");
            return;
        }

        if (!DASHBOARD.exists()) {
            LOG.warn("Dashboard file not found: {}", DASHBOARD.getAbsolutePath());
            return;
        }

        tryLaunch(DASHBOARD);
    }

    private static boolean shouldLaunch() {
        boolean explicit = Boolean.getBoolean("trend.open");
        boolean ci = isCiEnvironment();
        boolean desktop = isDesktopAvailable();
        return explicit || (desktop && !ci);
    }

    private static boolean isDesktopAvailable() {
        try {
            return Desktop.isDesktopSupported() && !GraphicsEnvironment.isHeadless();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tryLaunch(File f) {
        try {
            if (isDesktopAvailable()) {
                Desktop.getDesktop().browse(f.toURI());
                LOG.info("Dashboard opened: {}", f.getAbsolutePath());
                return;
            }
            launchByOs(f);
        } catch (Exception ex) {
            LOG.warn("Failed to launch dashboard: {}", ex.getMessage());
        }
    }

    private static void launchByOs(File f) throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        String[] command;
        if (os.contains("mac")) {
            command = new String[] { "open", f.getAbsolutePath() };
        } else if (os.contains("win")) {
            command = new String[] { "cmd", "/c", "start", f.getAbsolutePath() };
        } else {
            command = new String[] { "xdg-open", f.getAbsolutePath() };
        }

        new ProcessBuilder(command).start();
        LOG.info("Dashboard launched via command: {}", String.join(" ", command));
    }

    private static boolean isCiEnvironment() {
        try {
            Map<String, String> env = System.getenv();
            return env.containsKey("CI")
                    || env.containsKey("GITHUB_ACTIONS")
                    || env.containsKey("TRAVIS")
                    || env.containsKey("JENKINS_URL")
                    || env.containsKey("GITLAB_CI");
        } catch (Exception e) {
            return false;
        }
    }

}
