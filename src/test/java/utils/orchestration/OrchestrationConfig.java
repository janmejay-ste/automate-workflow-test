package utils.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Loads config/orchestration.properties with typed accessors.
 * All defaults match Phase C.2 conservative starting values.
 */
public final class OrchestrationConfig {
    private static final Logger LOG = LoggerFactory.getLogger(OrchestrationConfig.class);
    private static final String CONFIG_PATH = "config/orchestration.properties";

    private static volatile Properties props;

    private OrchestrationConfig() {}

    public static List<String> getCriticalWorkflows() {
        String raw = get("orchestration.critical.workflows",
                "Authentication,Login,AppPairing,Checkout,Publishing,ConnectWorkflow");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    public static int getRetryMax(String failureClass) {
        return switch (failureClass != null ? failureClass.toUpperCase() : "UNKNOWN") {
            case "FLAKY_SELECTOR"  -> getInt("orchestration.retry.max.flaky",       2);
            case "NETWORK_TIMEOUT" -> getInt("orchestration.retry.max.environment",  1);
            case "BACKEND_ERROR"   -> getInt("orchestration.retry.max.environment",  1);
            case "PRODUCT_BUG"     -> getInt("orchestration.retry.max.product_bug",  0);
            case "AUTOMATION_BUG"  -> getInt("orchestration.retry.max.automation_bug", 1);
            default                -> getInt("orchestration.retry.max.unknown",      1);
        };
    }

    public static int getStableThreshold() {
        return getInt("orchestration.minimization.stable.threshold", 85);
    }

    public static double getMinSampleFraction() {
        return getDbl("orchestration.minimization.min.sample.fraction", 0.50);
    }

    public static int getIsolatedPoolSize()  { return getInt("orchestration.resource.isolated.pool.size", 2); }
    public static int getSharedPoolSize()    { return getInt("orchestration.resource.shared.pool.size",   4); }
    public static int getParallelThreads()   { return getInt("orchestration.resource.parallel.threads", getSharedPoolSize()); }

    public static double getRiskDegradationThreshold() {
        return getDbl("orchestration.risk.degradation.threshold", 30.0);
    }
    public static int getRiskStabilityThreshold() {
        return getInt("orchestration.risk.stability.threshold", 65);
    }

    public static String getMode() { return get("orchestration.mode", "advisory"); }

    // ── Internal ─────────────────────────────────────────────────────────

    private static Properties load() {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_PATH)) {
            p.load(fis);
        } catch (Exception e) {
            LOG.debug("OrchestrationConfig: {} not found, using defaults", CONFIG_PATH);
        }
        return p;
    }

    private static Properties get() {
        if (props == null) {
            synchronized (OrchestrationConfig.class) {
                if (props == null) props = load();
            }
        }
        return props;
    }

    private static String  get(String k, String def)  { String v = get().getProperty(k); return v != null ? v.trim() : def; }
    private static int     getInt(String k, int def)  { try { return Integer.parseInt(get(k, String.valueOf(def))); } catch (Exception e) { return def; } }
    private static double  getDbl(String k, double d) { try { return Double.parseDouble(get(k, String.valueOf(d))); } catch (Exception e) { return d; } }
}
