package utils.analytics;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks page-level performance against a baseline.
 */
public class PerformanceTracker {
    private static final Logger LOG = LoggerFactory.getLogger(PerformanceTracker.class);
    private static final PerformanceTracker INSTANCE = new PerformanceTracker();
    private static final String BASELINE_FILE = "config/performance.baselines.json";

    public enum Speed {
        FAST, NORMAL, SLOW, VERY_SLOW
    }

    private String baselineVersion = "unknown";
    private final Map<String, Long> baselines = new ConcurrentHashMap<>();
    private final Map<String, Long> measurements = new ConcurrentHashMap<>();

    private PerformanceTracker() {
        loadBaselines();
    }

    public static PerformanceTracker get() {
        return INSTANCE;
    }

    private void loadBaselines() {
        try {
            if (Files.exists(Paths.get(BASELINE_FILE))) {
                String content = Files.readString(Paths.get(BASELINE_FILE));
                JSONObject json = (JSONObject) new JSONParser().parse(content);
                this.baselineVersion = String.valueOf(json.getOrDefault("baselineVersion", "unknown"));

                JSONObject pages = (JSONObject) json.getOrDefault("pages", json);
                for (Object key : pages.keySet()) {
                    Object val = pages.get(key);
                    if (val instanceof Number) {
                        baselines.put(key.toString(), ((Number) val).longValue());
                    } else if (val != null) {
                        baselines.put(key.toString(), Long.parseLong(val.toString()));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not load performance baselines: {}", e.getMessage());
        }
    }

    public void recordPageLoad(String page, long durationMs) {
        measurements.put(page, durationMs);
    }

    public Speed getClassification(String page, long durationMs) {
        long baseline = baselines.getOrDefault(page, 2000L); // Default 2s baseline
        if (durationMs < baseline)
            return Speed.FAST;
        if (durationMs <= baseline * 1.5)
            return Speed.NORMAL;
        if (durationMs <= baseline * 2.0)
            return Speed.SLOW;
        return Speed.VERY_SLOW;
    }

    public Map<String, Long> getMeasurements() {
        return measurements;
    }

    public Map<String, Long> getBaselines() {
        return baselines;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }
}
