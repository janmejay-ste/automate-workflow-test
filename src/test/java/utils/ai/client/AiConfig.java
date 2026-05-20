package utils.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.util.Properties;

public final class AiConfig {
    private static final Logger LOG = LoggerFactory.getLogger(AiConfig.class);
    private static final String CONFIG_PATH = "config/ai.properties";
    private static final Properties PROPS = new Properties();

    static {
        try (FileInputStream fis = new FileInputStream(CONFIG_PATH)) {
            PROPS.load(fis);
        } catch (Exception e) {
            LOG.warn("ai.properties not found at {}, AI layer will use defaults", CONFIG_PATH);
        }
    }

    private AiConfig() {}

    public static boolean isEnabled() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            LOG.warn("AI layer disabled: OPENAI_API_KEY not set");
            return false;
        }
        return Boolean.parseBoolean(PROPS.getProperty("ai.enabled", "true"));
    }

    public static boolean isFailureAnalysisEnabled() {
        return isEnabled() && Boolean.parseBoolean(PROPS.getProperty("ai.failure.analysis.enabled", "true"));
    }

    public static boolean isReleaseNarrativeEnabled() {
        return isEnabled() && Boolean.parseBoolean(PROPS.getProperty("ai.release.narrative.enabled", "true"));
    }

    public static String getApiKey() {
        return System.getenv("OPENAI_API_KEY");
    }

    public static String getDeepModel() {
        return PROPS.getProperty("ai.model.deep", "gpt-4o");
    }

    public static String getStandardModel() {
        return PROPS.getProperty("ai.model.standard", "gpt-4o-mini");
    }

    public static int getMaxRequestsPerRun() {
        return Integer.parseInt(PROPS.getProperty("ai.max.requests.per.run", "10"));
    }

    public static long getMaxTokensPerRun() {
        return Long.parseLong(PROPS.getProperty("ai.max.tokens.per.run", "200000"));
    }

    public static int getTimeoutSeconds() {
        return Integer.parseInt(PROPS.getProperty("ai.timeout.seconds", "25"));
    }

    public static boolean isCacheEnabled() {
        return Boolean.parseBoolean(PROPS.getProperty("ai.cache.enabled", "true"));
    }

    public static long getCacheTtlHours() {
        return Long.parseLong(PROPS.getProperty("ai.cache.ttl.hours", "72"));
    }

    public static double getMinConfidence() {
        return Double.parseDouble(PROPS.getProperty("ai.failure.min.confidence", "0.75"));
    }
}
