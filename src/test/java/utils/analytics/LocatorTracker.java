package utils.analytics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks locator health per feature.
 * Identifies where primary locators are failing and fallbacks are being used.
 */
public class LocatorTracker {
    private static final LocatorTracker INSTANCE = new LocatorTracker();

    // Feature -> {PrimarySuccess, FallbackUsed, TotalSamples}
    private final Map<String, LongAdder[]> stats = new ConcurrentHashMap<>();

    private LocatorTracker() {
    }

    public static LocatorTracker get() {
        return INSTANCE;
    }

    private LongAdder[] getCounter(String feature) {
        return stats.computeIfAbsent(feature, k -> new LongAdder[] {
                new LongAdder(),
                new LongAdder(),
                new LongAdder()
        });
    }

    public void recordPrimarySuccess(String feature) {
        String f = (feature == null || feature.isEmpty()) ? "General" : feature;
        LongAdder[] c = getCounter(f);
        c[0].increment();
        c[2].increment();
    }

    public void recordFallbackUsed(String feature) {
        String f = (feature == null || feature.isEmpty()) ? "General" : feature;
        LongAdder[] c = getCounter(f);
        c[1].increment();
        c[2].increment();
    }

    public void recordFailure(String feature) {
        String f = (feature == null || feature.isEmpty()) ? "General" : feature;
        getCounter(f)[2].increment();
    }

    public Map<String, long[]> getStats() {
        Map<String, long[]> results = new HashMap<>();
        stats.forEach((f, counters) -> {
            results.put(f, new long[] { counters[0].sum(), counters[1].sum(), counters[2].sum() });
        });
        return results;
    }

    public double getPrimarySuccessRate(String feature) {
        LongAdder[] s = stats.get(feature);
        if (s == null)
            return 100.0;
        long success = s[0].sum();
        long fallbacks = s[1].sum();
        if (success + fallbacks == 0)
            return 100.0;
        return (success * 100.0) / (success + fallbacks);
    }

    public double getGlobalSuccessRate() {
        long[] g = getGlobalStats();
        if (g[2] == 0)
            return 100.0;
        return (g[0] * 100.0) / (g[0] + g[1]);
    }

    public long[] getGlobalStats() {
        long s = 0, f = 0, t = 0;
        for (LongAdder[] c : stats.values()) {
            s += c[0].sum();
            f += c[1].sum();
            t += c[2].sum();
        }
        return new long[] { s, f, t };
    }
}
