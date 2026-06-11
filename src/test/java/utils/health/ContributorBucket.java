package utils.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Tracks penalty contributions for a single source (jsErrors, testFailures,
 * fallbacks, slowPages, warnings, etc.) so the final score is reproducible
 * and explainable.
 *
 * <p>Replaces the v1 "single raw penalty pool" model where caps silently
 * suppressed contributors and there was no way to answer "why did this
 * run score 73?". Every {@link #add} call records both the raw penalty
 * (what would be applied with no cap) and the applied penalty (what
 * actually reached the score), plus an item-level breakdown.</p>
 *
 * <p>Phase A1 of the scoring overhaul (v3 plan).</p>
 *
 * <h3>JSON output structure</h3>
 * <pre>
 * {
 *   "source":       "jsErrors",
 *   "rawTotal":     32.4,
 *   "appliedTotal": 20.0,
 *   "suppressed":   12.4,
 *   "capReason":    "TOTAL_JS_PENALTY_CAP",
 *   "cappedAt":     20.0,
 *   "items": [
 *     { "context": "/signup", "message": "...", "raw": 1.0, "applied": 1.0, ... },
 *     { "context": "/login",  "message": "...", "raw": 0.2, "applied": 0.0, "suppressedReason": "CAP_REACHED" },
 *     ...
 *   ]
 * }
 * </pre>
 */
public final class ContributorBucket {

    private final String source;
    private double rawTotal     = 0.0;
    private double appliedTotal = 0.0;
    private String capReason    = null;   // null if no cap was hit
    private Double cappedAt     = null;   // numeric cap value that was hit
    private final List<Map<String, Object>> items = new ArrayList<>();

    public ContributorBucket(String source) {
        this.source = source;
    }

    /**
     * Records a single contributing item with its raw and applied penalties.
     *
     * @param itemFields  identifying fields for this item (e.g. {"context": ..., "message": ...})
     * @param raw         the penalty that would be applied if no cap was in effect
     * @param applied     the penalty that actually reached the score
     */
    public synchronized void add(Map<String, Object> itemFields, double raw, double applied) {
        Map<String, Object> entry = new LinkedHashMap<>(itemFields);
        entry.put("raw",     raw);
        entry.put("applied", applied);
        if (applied < raw) {
            entry.put("suppressedReason", "CAP_REACHED");
        }
        items.add(entry);
        rawTotal     += raw;
        appliedTotal += applied;
    }

    /**
     * Marks that a cap was reached for this source. Called from the producer
     * when {@code applied < raw} due to a hard cap (e.g. TOTAL_JS_PENALTY_CAP).
     */
    public synchronized void markCap(String reason, double capValue) {
        this.capReason = reason;
        this.cappedAt  = capValue;
    }

    public String getSource()        { return source; }
    public double getRawTotal()      { return rawTotal; }
    public double getAppliedTotal()  { return appliedTotal; }
    public double getSuppressed()    { return rawTotal - appliedTotal; }
    public String getCapReason()     { return capReason; }
    public Double getCappedAt()      { return cappedAt; }

    public List<Map<String, Object>> getItems() {
        return Collections.unmodifiableList(items);
    }

    @SuppressWarnings("unchecked")
    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("source",       source);
        o.put("rawTotal",     rawTotal);
        o.put("appliedTotal", appliedTotal);
        o.put("suppressed",   rawTotal - appliedTotal);
        if (capReason != null) o.put("capReason", capReason);
        if (cappedAt  != null) o.put("cappedAt",  cappedAt);
        JSONArray arr = new JSONArray();
        arr.addAll(items);
        o.put("items", arr);
        return o;
    }
}
