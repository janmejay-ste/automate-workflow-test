package utils.ai.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiEnrichmentExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(AiEnrichmentExecutor.class);

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile ExecutorService executor;

    private AiEnrichmentExecutor() {}

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return; // already initialized — prevent duplicate pools
        }
        executor = Executors.newFixedThreadPool(2);
        LOG.info("AiEnrichmentExecutor initialized (pool size: 2)");
    }

    public static void submit(Runnable task) {
        if (executor == null || executor.isShutdown()) {
            LOG.warn("AiEnrichmentExecutor not initialized or already shut down — AI task skipped");
            return;
        }
        executor.submit(task);
    }

    /**
     * Shuts down the executor and waits for all queued AI tasks to complete.
     * Must be called in @AfterSuite before DashboardBuilder.write().
     */
    public static void shutdownAndAwait(int timeoutSeconds) {
        if (executor == null) return;
        executor.shutdown();
        try {
            boolean finished = executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.warn("AI enrichment tasks did not complete within {}s — dashboard may show partial AI data", timeoutSeconds);
                executor.shutdownNow();
            } else {
                LOG.info("All AI enrichment tasks completed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
