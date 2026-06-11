package utils.release;

import org.json.simple.JSONObject;

/**
 * A single blocker or warning in a {@link ReleaseDecision}. Backend emits the
 * raw code + numeric values; the dashboard maps the code to human-readable text,
 * picks colors, and decides how to surface it.
 *
 * <p>Phase A4 of the scoring overhaul (v3 plan).</p>
 *
 * <p>Boundary rule: this type contains <b>no human-readable strings</b>. The
 * {@link #code} is an enum-like identifier (e.g. {@code SMOKE_FAIL},
 * {@code CRITICAL_BUGS_OPEN}, {@code LOW_CONFIDENCE}) and {@link #value} +
 * {@link #threshold} carry the numbers a UI needs to construct a sentence.</p>
 *
 * @param code      machine-readable identifier (e.g. {@code "Z_SCORE_DROP"})
 * @param value     actual measurement that triggered the factor (e.g. {@code -2.31})
 * @param threshold the limit that was crossed (e.g. {@code -2.0}); may be null
 *                  for codes that aren't threshold-based (e.g. {@code CRITICAL_FAIL_TRANSIENT_SUSPECTED})
 */
public record DecisionFactor(String code, Object value, Object threshold) {

    public DecisionFactor(String code, Object value) {
        this(code, value, null);
    }

    @SuppressWarnings("unchecked")
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("code", code);
        if (value     != null) o.put("value",     value);
        if (threshold != null) o.put("threshold", threshold);
        return o;
    }
}
