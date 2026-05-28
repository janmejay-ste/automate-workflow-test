package utils.orchestration.dto;

/**
 * Signal-aware retry recommendation for a single test failure.
 * Replaces dumb blanket retries with failure-type-specific strategy.
 */
public class RetryStrategyDto {
    public String  testId;
    public boolean shouldRetry;
    public int     maxRetries;      // 0 = no retry
    public String  retryReason;     // why retry (or why not)
    public String  failureClass;    // FLAKY_SELECTOR | PRODUCT_BUG | NETWORK_TIMEOUT |
                                    // BACKEND_ERROR | AUTH_CASCADE | UNKNOWN
    public boolean suppressIfCascade; // true = skip retry if this is a known downstream cascade
    public String  retryMode;       // IMMEDIATE | BACKOFF | ISOLATE | SUPPRESS
}
