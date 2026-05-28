package utils.ai.services;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.ai.client.AiConfig;
import utils.ai.models.AiFailureAnalysis;
import utils.ai.storage.AiResultWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class AiCacheService {
    private static final Logger LOG = LoggerFactory.getLogger(AiCacheService.class);
    private static final String CACHE_DIR = "reports/ai-cache";

    private AiCacheService() {}

    public static String buildKey(String stacktrace, String url, String failureType) {
        String raw = (stacktrace != null ? stacktrace : "") + "|"
                   + (url != null ? url : "") + "|"
                   + (failureType != null ? failureType : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    @SuppressWarnings("unchecked")
    public static AiFailureAnalysis load(String cacheKey) {
        if (!AiConfig.isCacheEnabled()) return null;
        try {
            Path path = Paths.get(CACHE_DIR, cacheKey + ".json");
            if (!Files.exists(path)) return null;

            JSONObject json = (JSONObject) new JSONParser().parse(Files.readString(path));

            // TTL check
            Object ts = json.get("cacheTimestamp");
            if (ts instanceof Number) {
                long cachedAt = ((Number) ts).longValue();
                long ttlMs = AiConfig.getCacheTtlHours() * 3_600_000L;
                if (System.currentTimeMillis() - cachedAt > ttlMs) {
                    Files.deleteIfExists(path);
                    LOG.debug("Cache expired for key {}", cacheKey);
                    return null;
                }
            }

            return AiResultWriter.fromJson(json);
        } catch (Exception e) {
            LOG.debug("Cache miss for key {}: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static void store(String cacheKey, AiFailureAnalysis analysis) {
        if (!AiConfig.isCacheEnabled()) return;
        try {
            Files.createDirectories(Paths.get(CACHE_DIR));
            JSONObject json = AiResultWriter.toJson(analysis);
            json.put("cacheTimestamp", System.currentTimeMillis());
            Path path = Paths.get(CACHE_DIR, cacheKey + ".json");
            Files.writeString(path, json.toJSONString());
        } catch (Exception e) {
            LOG.debug("Failed to store cache entry {}: {}", cacheKey, e.getMessage());
        }
    }
}
