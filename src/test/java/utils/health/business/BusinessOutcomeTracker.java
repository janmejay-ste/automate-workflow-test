package utils.health.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton registry of business transactions for the current suite.
 *
 * Parallel to {@link utils.health.HealthTracker} but with strictly different
 * semantics: HealthTracker records runtime telemetry (did the app stay up?),
 * BusinessOutcomeTracker records user-intent transactions (did the user's
 * goal succeed?).  Combining them would have collapsed two answers into one.
 *
 * Public API is intentionally narrow:
 *   - {@link #begin(String, BusinessTransactionCategory)} → returns a transaction
 *   - {@link #all()}     → unmodifiable view for renderers
 *   - {@link #reset()}   → for test isolation
 */
public final class BusinessOutcomeTracker {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessOutcomeTracker.class);
    private static final BusinessOutcomeTracker INSTANCE = new BusinessOutcomeTracker();

    private final Map<String, BusinessTransaction> transactions = new ConcurrentHashMap<>();
    private final AtomicInteger                    counter      = new AtomicInteger();

    private BusinessOutcomeTracker() {}

    public static BusinessOutcomeTracker get() {
        return INSTANCE;
    }

    /**
     * Create and register a new transaction in the STARTED state.
     */
    public BusinessTransaction begin(String name, BusinessTransactionCategory category) {
        String id = String.format("tx-%04d", counter.incrementAndGet());
        BusinessTransaction tx = new BusinessTransaction(id, name, category);
        transactions.put(id, tx);
        LOG.debug("Started business transaction {} [{}]: {}", id, category, name);
        return tx;
    }

    /** All transactions recorded this suite, insertion order. */
    public List<BusinessTransaction> all() {
        return List.copyOf(transactions.values());
    }

    /** Discard all recorded transactions.  Used for test isolation only. */
    public void reset() {
        transactions.clear();
        counter.set(0);
    }

    // ── aggregate counts (used by SemanticHealthSnapshot scoring) ───────────

    public int countByState(BusinessOutcomeState state) {
        int n = 0;
        for (BusinessTransaction t : transactions.values()) {
            if (t.state() == state) n++;
        }
        return n;
    }

    /** Number of transactions excluded from scoring (STARTED or ABORTED). */
    public int countExcluded() {
        return countByState(BusinessOutcomeState.STARTED)
             + countByState(BusinessOutcomeState.ABORTED);
    }
}
