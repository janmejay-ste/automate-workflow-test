package utils.health.business;

import java.util.*;

/**
 * One recorded user-intent transaction with its criteria, evidence, state, and
 * timing.  Immutable from the consumer's perspective once {@link #finalize()}d;
 * mutable internally during the test for criterion updates and evidence
 * accumulation.
 *
 * Thread-safety: callers should not share transactions across threads.  The
 * {@link BusinessOutcomeTracker} hands one transaction to whoever called start
 * and protects its own registry separately.
 */
public final class BusinessTransaction {

    public final String                       id;          // unique within a run, e.g. "tx-0001"
    public final String                       name;        // human label, e.g. "Connect: Gmail → Slack"
    public final BusinessTransactionCategory  category;
    public final long                         startedAtMs;

    private final List<SuccessCriterion>      criteria = new ArrayList<>();
    private final Map<String, String>         evidence = new LinkedHashMap<>();

    private BusinessOutcomeState              state    = BusinessOutcomeState.STARTED;
    private String                            failureReason;
    private long                              completedAtMs;

    BusinessTransaction(String id, String name, BusinessTransactionCategory category) {
        this.id          = id;
        this.name        = name;
        this.category    = category;
        this.startedAtMs = System.currentTimeMillis();
    }

    // ── fluent criterion + evidence API ─────────────────────────────────────

    /**
     * Declare an expectation the transaction will be checked against.  Returns
     * this transaction so calls can chain.  The criterion starts pending; call
     * {@link #observePass(String, String)} / {@link #observeFail(String, String)}
     * to record what the test actually saw.
     */
    public BusinessTransaction require(String label, String expected) {
        criteria.add(SuccessCriterion.declare(label, expected));
        return this;
    }

    /** Record that the criterion with the given label was observed and passed. */
    public BusinessTransaction observePass(String label, String observed) {
        replaceCriterion(label, c -> c.pass(observed));
        return this;
    }

    /** Record that the criterion with the given label was observed and failed. */
    public BusinessTransaction observeFail(String label, String observed) {
        replaceCriterion(label, c -> c.fail(observed));
        return this;
    }

    /** Attach a free-form evidence key/value pair (e.g. {@code "connectId" → "abc123"}). */
    public BusinessTransaction withEvidence(String key, String value) {
        if (key != null && value != null) evidence.put(key, value);
        return this;
    }

    // ── outcome transitions ─────────────────────────────────────────────────

    /**
     * Compute the outcome state from observed criteria and finalize the
     * transaction.  Idempotent — calling twice has no effect.
     */
    public BusinessTransaction complete() {
        if (state != BusinessOutcomeState.STARTED) return this;
        long passed  = criteria.stream().filter(c -> !c.isPending() &&  c.passed).count();
        long failed  = criteria.stream().filter(c -> !c.isPending() && !c.passed).count();
        long pending = criteria.stream().filter(SuccessCriterion::isPending).count();

        if (failed > 0 && passed > 0)       state = BusinessOutcomeState.PARTIAL;
        else if (failed > 0)                state = BusinessOutcomeState.FAILED;
        else if (pending > 0 && passed == 0) state = BusinessOutcomeState.STARTED; // never evaluated
        else                                state = BusinessOutcomeState.SUCCESS;

        completedAtMs = System.currentTimeMillis();
        return this;
    }

    /** Force the transaction to ABORTED — the test crashed before evaluation could complete. */
    public BusinessTransaction abort(String reason) {
        if (state != BusinessOutcomeState.STARTED && state != BusinessOutcomeState.ABORTED) return this;
        state = BusinessOutcomeState.ABORTED;
        failureReason = reason;
        completedAtMs = System.currentTimeMillis();
        return this;
    }

    /** Force the transaction to FAILED with an explicit reason. */
    public BusinessTransaction fail(String reason) {
        state = BusinessOutcomeState.FAILED;
        failureReason = reason;
        completedAtMs = System.currentTimeMillis();
        return this;
    }

    // ── accessors ───────────────────────────────────────────────────────────

    public BusinessOutcomeState state()       { return state; }
    public String               failureReason(){ return failureReason; }
    public long                 durationMs()  { return completedAtMs == 0 ? 0 : completedAtMs - startedAtMs; }
    public List<SuccessCriterion> criteria()  { return Collections.unmodifiableList(criteria); }
    public Map<String, String>    evidence()  { return Collections.unmodifiableMap(evidence); }

    // ── internal helpers ────────────────────────────────────────────────────

    private void replaceCriterion(String label, java.util.function.UnaryOperator<SuccessCriterion> op) {
        for (int i = 0; i < criteria.size(); i++) {
            if (label.equals(criteria.get(i).label)) {
                criteria.set(i, op.apply(criteria.get(i)));
                return;
            }
        }
        // Allow record-only flow: criterion never declared, just record observation.
        SuccessCriterion seed = SuccessCriterion.declare(label, "(declared on observation)");
        criteria.add(op.apply(seed));
    }
}
