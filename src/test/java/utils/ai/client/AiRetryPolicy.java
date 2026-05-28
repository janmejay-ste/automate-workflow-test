package utils.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiRetryPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(AiRetryPolicy.class);
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;

    private AiRetryPolicy() {}

    public static boolean isRetryable(int httpStatus) {
        return httpStatus == 429 || (httpStatus >= 500 && httpStatus < 600);
    }

    public static int maxRetries() {
        return MAX_RETRIES;
    }

    public static void sleepBeforeRetry(int attempt) {
        try {
            long delay = BASE_DELAY_MS * (1L << attempt); // 1s, 2s, 4s
            LOG.warn("OpenAI retry attempt {} — waiting {}ms", attempt + 1, delay);
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
