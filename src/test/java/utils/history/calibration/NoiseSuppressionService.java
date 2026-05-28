package utils.history.calibration;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Prevents repeated, identical alerts from firing every run.
 *
 * Without suppression: an unresolved regression that fires 20 runs in a row
 * causes engineers to stop reading the dashboard entirely. Alert fatigue
 * is the silent dashboard killer.
 *
 * Suppression strategy:
 *   - Computes a fingerprint: SHA-256 of (signalType + "|" + identifier + "|" + rootCause)
 *   - If the same fingerprint has fired consecutively within cooldown bounds → suppress
 *   - Suppression is advisory — callers decide whether to render the alert
 *   - Always records the firing count, even when suppressed, for trust tracking
 *
 * Storage: reports/history/calibration/suppression/{fingerprint}.json
 * Schema: {fingerprint, signalType, identifier, firstSeenAt, lastSeenAt,
 *           consecutiveCount, suppressed, schemaVersion}
 */
public final class NoiseSuppressionService {
    private static final Logger LOG = LoggerFactory.getLogger(NoiseSuppressionService.class);
    static final String SUPPRESSION_DIR = "reports/history/calibration/suppression";

    public static final class SuppressionResult {
        public final boolean suppressed;
        public final int     consecutiveCount;  // how many runs this fingerprint has fired
        public final String  fingerprint;
        public final String  reason;

        private SuppressionResult(boolean suppressed, int consecutiveCount,
                                   String fingerprint, String reason) {
            this.suppressed       = suppressed;
            this.consecutiveCount = consecutiveCount;
            this.fingerprint      = fingerprint;
            this.reason           = reason;
        }
    }

    private NoiseSuppressionService() {}

    /**
     * Checks whether an alert should be suppressed AND records the firing.
     * Callers must call this exactly once per alert, per run.
     *
     * @param signalType  e.g. "RegressionSpike", "PerformanceDegradation"
     * @param identifier  e.g. page URL, test ID, feature name
     * @param rootCause   primary classification of the underlying issue (may be empty)
     * @param maxConsecutive  suppress after this many consecutive fires (cooldown window)
     */
    @SuppressWarnings("unchecked")
    public static SuppressionResult evaluate(String signalType, String identifier,
                                              String rootCause, int maxConsecutive) {
        String fingerprint = computeFingerprint(signalType, identifier, rootCause);
        try {
            Files.createDirectories(Paths.get(SUPPRESSION_DIR));
            Path path = Paths.get(SUPPRESSION_DIR, fingerprint + ".json");

            JSONObject record = loadOrInit(path, fingerprint, signalType, identifier);
            int consecutive   = num(record, "consecutiveCount", 0) + 1;

            record.put("consecutiveCount", (long) consecutive);
            record.put("lastSeenAt",       Instant.now().toString());
            record.put("signalType",       signalType);
            record.put("identifier",       identifier != null ? identifier : "");

            Files.writeString(path, record.toJSONString());

            if (maxConsecutive > 0 && consecutive > maxConsecutive) {
                return new SuppressionResult(true, consecutive, fingerprint,
                        "Same alert fired " + consecutive + " consecutive runs (threshold: "
                                + maxConsecutive + ")");
            }
            return new SuppressionResult(false, consecutive, fingerprint, "");

        } catch (Exception e) {
            LOG.debug("NoiseSuppressionService.evaluate failed for {}/{}: {}",
                    signalType, identifier, e.getMessage());
            return new SuppressionResult(false, 0, fingerprint, "");
        }
    }

    /**
     * Resets the consecutive counter when an alert resolves (underlying issue fixed).
     * Call this when the signal is NOT fired in a run.
     */
    @SuppressWarnings("unchecked")
    public static void reset(String signalType, String identifier, String rootCause) {
        String fingerprint = computeFingerprint(signalType, identifier, rootCause);
        try {
            Path path = Paths.get(SUPPRESSION_DIR, fingerprint + ".json");
            if (!Files.exists(path)) return;
            JSONObject record = (JSONObject) new JSONParser().parse(Files.readString(path));
            record.put("consecutiveCount", 0L);
            record.put("resolvedAt", Instant.now().toString());
            Files.writeString(path, record.toJSONString());
        } catch (Exception e) {
            LOG.debug("NoiseSuppressionService.reset failed: {}", e.getMessage());
        }
    }

    /**
     * How many consecutive runs this signal/identifier combination has fired.
     * Returns 0 if never seen.
     */
    public static int getConsecutiveCount(String signalType, String identifier, String rootCause) {
        String fingerprint = computeFingerprint(signalType, identifier, rootCause);
        Path path = Paths.get(SUPPRESSION_DIR, fingerprint + ".json");
        if (!Files.exists(path)) return 0;
        try {
            JSONObject record = (JSONObject) new JSONParser().parse(Files.readString(path));
            return num(record, "consecutiveCount", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static String computeFingerprint(String signalType, String identifier, String rootCause) {
        try {
            String input = (signalType != null ? signalType : "") + "|"
                    + (identifier != null ? identifier : "") + "|"
                    + (rootCause != null ? rootCause : "");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 16); // 16-char prefix is enough for file names
        } catch (Exception e) {
            // Fallback: sanitize the combined string
            return (signalType + "_" + identifier).replaceAll("[^a-zA-Z0-9]", "_")
                    .substring(0, Math.min(32, (signalType + "_" + identifier).length()));
        }
    }

    @SuppressWarnings("unchecked")
    private static JSONObject loadOrInit(Path path, String fingerprint,
                                          String signalType, String identifier) {
        if (Files.exists(path)) {
            try { return (JSONObject) new JSONParser().parse(Files.readString(path)); }
            catch (Exception ignored) {}
        }
        JSONObject rec = new JSONObject();
        rec.put("schemaVersion",    1L);
        rec.put("fingerprint",      fingerprint);
        rec.put("signalType",       signalType);
        rec.put("identifier",       identifier != null ? identifier : "");
        rec.put("firstSeenAt",      Instant.now().toString());
        rec.put("consecutiveCount", 0L);
        return rec;
    }

    private static int num(JSONObject o, String k, int def) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
}
