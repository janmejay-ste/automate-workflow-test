package utils.history.calibration;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Enforces per-severity cooldown windows so that medium/low alerts
 * do not fire on every run once they become persistent.
 *
 * Cooldown rules (by severity):
 *   CRITICAL : no cooldown — show every run (unresolved criticals always visible)
 *   HIGH     : no cooldown — show every run
 *   MEDIUM   : cooldown 3 runs — suppress after showing, re-show after 3 runs pass
 *   LOW      : cooldown 5 runs — suppress after showing, re-show after 5 runs pass
 *
 * Run count is used rather than wall-clock time because test suites can run
 * irregularly. Run-based cooldowns are deterministic and predictable.
 *
 * Storage: reports/history/calibration/cooldowns/{alertKey}.json
 * Schema: {alertKey, severity, firstSeenRun, lastShownRun, showCount,
 *           cooldownRuns, suppressedUntilRun, schemaVersion}
 */
public final class AlertCooldownManager {
    private static final Logger LOG = LoggerFactory.getLogger(AlertCooldownManager.class);
    static final String COOLDOWN_DIR = "reports/history/calibration/cooldowns";

    public static final class CooldownResult {
        public final boolean onCooldown;
        public final int     runsUntilNextShow;  // 0 when not on cooldown
        public final int     showCount;          // how many times this alert has been shown

        private CooldownResult(boolean onCooldown, int runsUntilNextShow, int showCount) {
            this.onCooldown        = onCooldown;
            this.runsUntilNextShow = runsUntilNextShow;
            this.showCount         = showCount;
        }
    }

    private AlertCooldownManager() {}

    /**
     * Checks whether an alert is on cooldown.
     * Does NOT record the show — call recordShown() after deciding to display.
     *
     * @param alertKey       unique identifier for this alert (signalType + identifier)
     * @param severity       CRITICAL | HIGH | MEDIUM | LOW
     * @param currentRunNum  monotonically increasing run counter
     */
    public static CooldownResult check(String alertKey, String severity, int currentRunNum) {
        Path path = Paths.get(COOLDOWN_DIR, sanitize(alertKey) + ".json");
        if (!Files.exists(path)) {
            return new CooldownResult(false, 0, 0);
        }
        try {
            JSONObject record = (JSONObject) new JSONParser().parse(Files.readString(path));
            int suppressedUntil = num(record, "suppressedUntilRun", 0);
            int showCount       = num(record, "showCount", 0);

            if (currentRunNum <= suppressedUntil) {
                int remaining = suppressedUntil - currentRunNum + 1;
                return new CooldownResult(true, remaining, showCount);
            }
            return new CooldownResult(false, 0, showCount);

        } catch (Exception e) {
            return new CooldownResult(false, 0, 0);
        }
    }

    /**
     * Records that an alert was shown this run and sets the next suppression window.
     * Call ONLY when you actually render the alert.
     *
     * @param alertKey       unique identifier for this alert
     * @param severity       CRITICAL | HIGH | MEDIUM | LOW
     * @param currentRunNum  current run counter
     */
    @SuppressWarnings("unchecked")
    public static void recordShown(String alertKey, String severity, int currentRunNum) {
        try {
            Files.createDirectories(Paths.get(COOLDOWN_DIR));
            Path path = Paths.get(COOLDOWN_DIR, sanitize(alertKey) + ".json");

            JSONObject record = loadOrInit(path, alertKey, severity, currentRunNum);
            int showCount = num(record, "showCount", 0) + 1;
            int cooldownRuns = cooldownFor(severity);

            record.put("severity",           severity);
            record.put("lastShownRun",        (long) currentRunNum);
            record.put("showCount",           (long) showCount);
            record.put("cooldownRuns",        (long) cooldownRuns);
            record.put("suppressedUntilRun",  (long) (currentRunNum + cooldownRuns));
            record.put("lastUpdated",         Instant.now().toString());

            Files.writeString(path, record.toJSONString());

        } catch (Exception e) {
            LOG.debug("AlertCooldownManager.recordShown failed for {}: {}", alertKey, e.getMessage());
        }
    }

    /**
     * Clears the cooldown record when the underlying alert resolves.
     */
    public static void clear(String alertKey) {
        try {
            Path path = Paths.get(COOLDOWN_DIR, sanitize(alertKey) + ".json");
            if (Files.exists(path)) Files.delete(path);
        } catch (Exception e) {
            LOG.debug("AlertCooldownManager.clear failed for {}: {}", alertKey, e.getMessage());
        }
    }

    /**
     * Returns the total number of times an alert has been shown (not suppressed).
     */
    public static int getShowCount(String alertKey) {
        Path path = Paths.get(COOLDOWN_DIR, sanitize(alertKey) + ".json");
        if (!Files.exists(path)) return 0;
        try {
            JSONObject record = (JSONObject) new JSONParser().parse(Files.readString(path));
            return num(record, "showCount", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int cooldownFor(String severity) {
        return switch (severity != null ? severity.toUpperCase() : "LOW") {
            case "CRITICAL", "HIGH" -> 0;  // always show
            case "MEDIUM"           -> 3;
            default                 -> 5;  // LOW and unknown
        };
    }

    @SuppressWarnings("unchecked")
    private static JSONObject loadOrInit(Path path, String alertKey,
                                          String severity, int currentRunNum) {
        if (Files.exists(path)) {
            try { return (JSONObject) new JSONParser().parse(Files.readString(path)); }
            catch (Exception ignored) {}
        }
        JSONObject rec = new JSONObject();
        rec.put("schemaVersion",    1L);
        rec.put("alertKey",         alertKey);
        rec.put("severity",         severity);
        rec.put("firstSeenRun",     (long) currentRunNum);
        rec.put("showCount",        0L);
        rec.put("suppressedUntilRun", 0L);
        return rec;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static int num(JSONObject o, String k, int def) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
}
