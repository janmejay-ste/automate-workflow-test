package utils.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AiRateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(AiRateLimiter.class);

    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final AtomicLong tokenCount = new AtomicLong(0);

    private AiRateLimiter() {}

    public static boolean allowRequest(long estimatedTokens) {
        int maxRequests = AiConfig.getMaxRequestsPerRun();
        long maxTokens = AiConfig.getMaxTokensPerRun();

        if (requestCount.get() >= maxRequests) {
            LOG.warn("AI budget exceeded: max requests ({}) reached for this suite run", maxRequests);
            return false;
        }
        if (tokenCount.get() + estimatedTokens > maxTokens) {
            LOG.warn("AI budget exceeded: token limit ({}) would be exceeded", maxTokens);
            return false;
        }
        return true;
    }

    public static void recordUsage(long actualTokens) {
        int reqs = requestCount.incrementAndGet();
        long tokens = tokenCount.addAndGet(actualTokens);
        LOG.debug("AI usage: {} requests, {} tokens total this run", reqs, tokens);
    }

    public static void reset() {
        requestCount.set(0);
        tokenCount.set(0);
    }

    public static int getRequestCount() {
        return requestCount.get();
    }

    public static long getTokenCount() {
        return tokenCount.get();
    }
}
