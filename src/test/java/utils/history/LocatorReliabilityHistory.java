package utils.history;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.history.dto.LocatorTrendDto;

import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rolling aggregate of per-feature locator reliability.
 *
 * Schema contract (schemaVersion = 1):
 *   feature, metrics{primarySuccessRate, fallbackRate, criticalFailureRate,
 *     reliabilityScore}, lastUpdated
 *
 * Reliability score formula (deterministic):
 *   score = (primarySuccessRate × 80) + ((1 − fallbackRate) × 15) + ((1 − criticalFailureRate) × 5)
 *   clamped to [0, 100]
 *
 * Status thresholds:
 *   HEALTHY  : reliabilityScore >= 85
 *   FRAGILE  : reliabilityScore >= 60
 *   CRITICAL : reliabilityScore <  60
 */
public final class LocatorReliabilityHistory {
    private static final Logger LOG = LoggerFactory.getLogger(LocatorReliabilityHistory.class);
    static final String LOCATORS_DIR = "reports/history/locators";

    private LocatorReliabilityHistory() {}

    @SuppressWarnings("unchecked")
    public static void update(String feature, double primarySuccessRate,
                               double fallbackRate, double criticalFailureRate) {
        if (feature == null || feature.isBlank()) return;
        try {
            Files.createDirectories(Paths.get(LOCATORS_DIR));
            Path path = Paths.get(LOCATORS_DIR, sanitize(feature) + ".json");

            // Clamp all rates to [0, 1]
            primarySuccessRate  = Math.max(0, Math.min(1, primarySuccessRate));
            fallbackRate        = Math.max(0, Math.min(1, fallbackRate));
            criticalFailureRate = Math.max(0, Math.min(1, criticalFailureRate));

            double score = Math.max(0, Math.min(100,
                    (primarySuccessRate  * 80.0)
                    + ((1.0 - fallbackRate)        * 15.0)
                    + ((1.0 - criticalFailureRate)  *  5.0)));

            JSONObject metrics = new JSONObject();
            metrics.put("primarySuccessRate",  round2(primarySuccessRate));
            metrics.put("fallbackRate",         round2(fallbackRate));
            metrics.put("criticalFailureRate",  round2(criticalFailureRate));
            metrics.put("reliabilityScore",     (long) Math.round(score));

            JSONObject record = new JSONObject();
            record.put("schemaVersion", 1L);
            record.put("feature",       feature);
            record.put("metrics",       metrics);
            record.put("lastUpdated",   Instant.now().toString());

            Files.writeString(path, record.toJSONString());

        } catch (Exception e) {
            LOG.debug("LocatorReliabilityHistory.update failed for {}: {}", feature, e.getMessage());
        }
    }

    public static JSONObject load(String feature) {
        Path path = Paths.get(LOCATORS_DIR, sanitize(feature) + ".json");
        if (!Files.exists(path)) return null;
        try {
            return (JSONObject) new JSONParser().parse(Files.readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    public static List<LocatorTrendDto> loadAllAsDtos() {
        try {
            Path dir = Paths.get(LOCATORS_DIR);
            if (!Files.exists(dir)) return List.of();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> {
                            try { return toDto((JSONObject) new JSONParser().parse(Files.readString(p))); }
                            catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static LocatorTrendDto toDto(JSONObject record) {
        LocatorTrendDto dto = new LocatorTrendDto();
        dto.feature = str(record, "feature", "unknown");
        JSONObject m = objOrEmpty(record, "metrics");
        dto.primarySuccessRate  = dbl(m, "primarySuccessRate",  1.0);
        dto.fallbackRate        = dbl(m, "fallbackRate",         0.0);
        dto.criticalFailureRate = dbl(m, "criticalFailureRate",  0.0);
        dto.reliabilityScore    = (int) lng(m, "reliabilityScore", 100L);
        dto.status = dto.reliabilityScore >= 85 ? "HEALTHY"
                   : dto.reliabilityScore >= 60 ? "FRAGILE" : "CRITICAL";
        return dto;
    }

    static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static JSONObject objOrEmpty(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    private static String  str(JSONObject o, String k, String def)  { Object v = o.get(k); return v != null ? v.toString() : def; }
    private static double  dbl(JSONObject o, String k, double def)  { Object v = o.get(k); return v instanceof Number ? ((Number)v).doubleValue() : def; }
    private static long    lng(JSONObject o, String k, long def)    { Object v = o.get(k); return v instanceof Number ? ((Number)v).longValue()   : def; }
    private static double  round2(double v)                          { return Math.round(v * 100.0) / 100.0; }
}
