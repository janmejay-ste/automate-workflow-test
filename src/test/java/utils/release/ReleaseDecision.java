package utils.release;

import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import utils.HealthPolicy;

/**
 * Structured release decision emitted by {@code RiskInterpreter}.
 *
 * <p>Phase A4 of the scoring overhaul (v3 plan).</p>
 *
 * <p>Replaces the v1/v2 model of returning a bare {@link HealthPolicy.ReleaseStatus}
 * enum — which gave the dashboard no way to explain <i>why</i> a release was
 * blocked or what would unblock it. The dashboard now reads {@link #blockers}
 * and {@link #warnings} and renders the verbatim reasons.</p>
 *
 * <h3>Boundary rule</h3>
 * No human-readable strings here. {@link DecisionFactor#code} is the identifier;
 * the UI builds the sentence.
 *
 * @param status    one of READY / WARNING / AT_RISK / BLOCKED
 * @param blockers  factors that, if any are present, force status ≠ READY
 * @param warnings  factors that downgrade status but do not block
 * @param wouldShip the single boolean the dashboard headlines — equivalent to
 *                  {@code status == READY || status == WARNING}
 */
public record ReleaseDecision(
        HealthPolicy.ReleaseStatus status,
        List<DecisionFactor> blockers,
        List<DecisionFactor> warnings,
        boolean wouldShip) {

    @SuppressWarnings("unchecked")
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("status",     status.name());
        o.put("wouldShip",  wouldShip);

        JSONArray blockArr = new JSONArray();
        for (DecisionFactor b : blockers) blockArr.add(b.toJson());
        o.put("blockers", blockArr);

        JSONArray warnArr = new JSONArray();
        for (DecisionFactor w : warnings) warnArr.add(w.toJson());
        o.put("warnings", warnArr);

        return o;
    }
}
