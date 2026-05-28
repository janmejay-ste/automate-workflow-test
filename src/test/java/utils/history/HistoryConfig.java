package utils.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.util.Properties;

public final class HistoryConfig {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryConfig.class);
    private static final Properties PROPS = new Properties();

    static {
        try (FileInputStream fis = new FileInputStream("config/history.properties")) {
            PROPS.load(fis);
        } catch (Exception e) {
            LOG.debug("config/history.properties not found — using defaults");
        }
    }

    private HistoryConfig() {}

    public static int getRetentionDays()           { return intProp("history.retention.days",               90);  }
    public static int getMaxRunSnapshots()          { return intProp("history.max.run.snapshots",            500); }
    public static int getMaxPerfEntriesPerPage()    { return intProp("history.max.perf.entries.per.page",    200); }
    public static int getStabilityWindow()          { return intProp("history.stability.window",              20); }

    private static int intProp(String key, int def) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
